package it.legislation.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import it.legislation.eli.EliUriService;
import it.legislation.relations.CorpusIndex;
import it.legislation.source.RawStore;
import it.legislation.source.gazzetta.HttpPageFetcher;
import it.legislation.source.normattiva.ExportRequest;
import it.legislation.source.normattiva.NormattivaClient;
import it.legislation.source.normattiva.NormattivaSampleFetchRunner;

/**
 * The daily update (R7b). Keeps the store current without downloading the
 * whole corpus again.
 *
 * <ol>
 *   <li>Window: from the end of the last successful run (less one hour) to now,
 *       from {@link IngestionWatermark}. A failed run does not move it, so the
 *       next run asks for the same period again.</li>
 *   <li>Asks Normattiva {@code ricerca/aggiornati} which acts changed in the
 *       window, and keeps those in the corpus scope (LEGGE, DECRETO-LEGGE,
 *       DECRETO LEGISLATIVO, from {@value #FIRST_YEAR}).</li>
 *   <li>Refreshes the act lists of the current year and adds the acts Normattiva
 *       lists but that are not imported yet (their text was not exported on an
 *       earlier day).</li>
 *   <li>Downloads those acts, all versions, into
 *       {@code data/raw/normattiva/updates/yyyy-mm-dd/}.</li>
 *   <li>Runs the pipeline: import, Gazzetta check, relations. Facts are added and
 *       changed results get new dated assessments, so a run is additive.</li>
 *   <li>Records the run in the run log, and advances the watermark only when
 *       nothing failed.</li>
 * </ol>
 *
 * It needs the raw corpus on disk, so it runs where the corpus and the store
 * live: on the application's server, or by hand on the development machine.
 *
 * <pre>
 * mvn -B compile exec:java "-Dexec.mainClass=it.legislation.ingest.DailyUpdateRunner"
 * </pre>
 *
 * Options: {@code --lookback-days N} (first run only, default 7), {@code --root}
 * (default {@code data/raw/normattiva}), {@code --dry-run true} (list what would
 * be fetched, change nothing).
 */
public class DailyUpdateRunner {

    public static final String SOURCE = "normattiva-daily";
    public static final int FIRST_YEAR = 2020;
    static final List<String> TYPES = List.of("LEGGE", "DECRETO-LEGGE", "DECRETO LEGISLATIVO");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(\\d+)");

    /** One act as Normattiva lists it. */
    public record ActRef(String codice, String type, LocalDate documentDate, int number, String gu, String label) {

        /** The export that brings this act, all versions. */
        ExportRequest export() {
            return export(ExportRequest.Mode.ALL_VERSIONS);
        }

        /** The export of this act in one mode: all versions, the original text or the current text. */
        ExportRequest export(ExportRequest.Mode mode) {
            return ExportRequest.akn(mode, type, documentDate.getYear(),
                    documentDate.getMonthValue(), documentDate.getDayOfMonth(), number);
        }

        boolean inScope() {
            return TYPES.contains(type) && documentDate.getYear() >= FIRST_YEAR;
        }

        /** Reads an entry of {@code listaAtti} (both {@code ricerca/aggiornati} and {@code ricerca/avanzata}). */
        static Optional<ActRef> from(JsonNode act) {
            String codice = act.path("codiceRedazionale").asText("");
            String type = act.path("denominazioneAtto").asText("").trim().toUpperCase(java.util.Locale.ROOT);
            LocalDate date = date(act);
            Matcher n = LEADING_NUMBER.matcher(act.has("numeroProvvedimento")
                    ? act.path("numeroProvvedimento").asText("") : act.path("numeroAtto").asText(""));
            if (codice.isEmpty() || type.isEmpty() || date == null || !n.find()) {
                return Optional.empty();
            }
            String label = act.path("descrizioneAtto").asText(type + " " + date + " n. " + n.group(1));
            return Optional.of(new ActRef(codice, type, date, Integer.parseInt(n.group(1)),
                    act.path("dataGU").asText(""), label));
        }

        private static LocalDate date(JsonNode act) {
            try {
                if (act.has("annoProvvedimento") && act.has("meseProvvedimento") && act.has("giornoProvvedimento")) {
                    return LocalDate.of(act.path("annoProvvedimento").asInt(), act.path("meseProvvedimento").asInt(),
                            act.path("giornoProvvedimento").asInt());
                }
                String emanation = act.path("dataEmanazione").asText("");
                return emanation.length() >= 10 ? LocalDate.parse(emanation.substring(0, 10)) : null;
            } catch (java.time.DateTimeException e) {
                return null;
            }
        }
    }

    /** What the runner needs from Normattiva. Separate from the HTTP client so the runner can be tested offline. */
    public interface Source {
        List<JsonNode> updatedActs(LocalDate from, LocalDate to) throws IOException;

        List<JsonNode> listed(String type, int year) throws IOException;

        byte[] export(ExportRequest request) throws IOException;
    }

    /** Import, Gazzetta check and relations, in that order. */
    public interface Pipeline {
        String run(LocalDate today) throws IOException;
    }

    /** What one run did. */
    public record Summary(IngestionWatermark.Window window, int updated, int outOfScope, int missing,
                          int fetched, int fromSingleTextExports, int notExportedYet, List<String> failures,
                          String pipeline, boolean dryRun) {}

    /**
     * When the all-versions export of an act comes back empty (Normattiva does this for some recent acts, though
     * their text is on its site), the original text and the current text are asked for separately: the original
     * gives the act (Layer 1) and its change list, the current text its history.
     */
    static final List<ExportRequest.Mode> FALLBACK = List.of(ExportRequest.Mode.ORIGINAL, ExportRequest.Mode.CURRENT);

    private final Source source;
    private final Pipeline pipeline;
    private final Path root;
    private final Path acts;
    private final IngestionWatermark watermark;
    private final IngestionRunLog runLog;

    public DailyUpdateRunner(Source source, Pipeline pipeline, Path root, Path acts,
                             IngestionWatermark watermark, IngestionRunLog runLog) {
        this.source = source;
        this.pipeline = pipeline;
        this.root = root;
        this.acts = acts;
        this.watermark = watermark;
        this.runLog = runLog;
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        Path root = Path.of(options.getOrDefault("--root", AknImportRunner.DEFAULT_IN.toString()));
        boolean dryRun = Boolean.parseBoolean(options.getOrDefault("--dry-run", "false"));
        Duration lookback = Duration.ofDays(Long.parseLong(options.getOrDefault("--lookback-days", "7")));
        NormattivaClient client = new NormattivaClient();
        DailyUpdateRunner runner = new DailyUpdateRunner(clientSource(client), defaultPipeline(root),
                root, AknImportRunner.LAYER1, new IngestionWatermark(IngestionWatermark.DEFAULT_PATH),
                new IngestionRunLog());
        Summary s = runner.run(IngestionWatermark.nowUtc(), lookback, dryRun, DailyUpdateRunner::log);

        System.out.println();
        System.out.println("Window:            " + s.window().describe());
        System.out.println("Changed acts:      " + s.updated() + " in scope, " + s.outOfScope() + " outside the corpus scope");
        System.out.println("Listed, missing:   " + s.missing() + " (listed by Normattiva, not imported yet)");
        System.out.println("Downloaded:        " + s.fetched() + " acts"
                + (s.fromSingleTextExports() > 0 ? " (" + s.fromSingleTextExports()
                        + " from the original/current text, the all-versions export being empty)" : "")
                + (s.notExportedYet() > 0
                ? ", " + s.notExportedYet() + " still without text (retried next run)" : ""));
        s.failures().forEach(f -> System.out.println("  FAIL " + f));
        if (s.pipeline() != null) {
            System.out.println(s.pipeline());
        }
        System.out.println(s.dryRun() ? "Dry run: nothing downloaded or changed."
                : s.failures().isEmpty() ? "Watermark advanced to " + s.window().end()
                : "Watermark NOT advanced: the next run asks for the same period again.");
    }

    public Summary run(OffsetDateTime now, Duration firstLookback, boolean dryRun,
                       java.util.function.Consumer<String> progress) throws IOException {
        IngestionWatermark.Window window = watermark.windowFor(SOURCE, now, firstLookback);
        IngestionRunLog.Run run = IngestionRunLog.Run.started(SOURCE, "daily", now);
        List<String> failures = new ArrayList<>();
        Map<String, ActRef> toFetch = new LinkedHashMap<>();
        int updated = 0;
        int outOfScope = 0;
        int missing = 0;
        int fetched = 0;
        int notExported = 0;
        int fromSingleText = 0;
        String pipelineReport = null;
        try {
            progress.accept("window " + window.describe());
            for (JsonNode node : source.updatedActs(window.start().toLocalDate(), window.end().toLocalDate())) {
                Optional<ActRef> act = ActRef.from(node);
                if (act.isEmpty() || !act.get().inScope()) {
                    outOfScope++;
                    continue;
                }
                updated++;
                toFetch.putIfAbsent(act.get().codice(), act.get());
            }
            progress.accept(updated + " changed acts in scope");

            Set<String> imported = imported();
            for (int year : yearsToList(window)) {
                for (String type : TYPES) {
                    List<JsonNode> listed = source.listed(type, year);
                    if (!dryRun) {
                        saveList(type, year, listed);
                    }
                    for (JsonNode node : listed) {
                        Optional<ActRef> act = ActRef.from(node);
                        if (act.isPresent() && !imported.contains(act.get().codice())
                                && toFetch.putIfAbsent(act.get().codice(), act.get()) == null) {
                            missing++;
                        }
                    }
                }
            }
            progress.accept(missing + " listed acts not imported yet");

            if (!dryRun) {
                RawStore store = new RawStore(root);
                String day = window.end().toLocalDate().toString();
                for (ActRef act : toFetch.values()) {
                    try {
                        int files = download(store, day, act, act.export());
                        int single = 0;
                        if (files == 0) {
                            for (ExportRequest.Mode mode : FALLBACK) {
                                single += download(store, day, act, act.export(mode));
                            }
                        }
                        if (files + single == 0) {
                            notExported++;
                            progress.accept("no text yet  " + act.codice() + "  " + act.label());
                        } else {
                            fetched++;
                            if (single > 0) {
                                fromSingleText++;
                            }
                            progress.accept("downloaded   " + act.codice() + "  " + act.label() + " (" + (files + single)
                                    + " files" + (single > 0 ? ", original/current text: all-versions export empty" : "")
                                    + ")");
                        }
                    } catch (IOException | RuntimeException e) {
                        failures.add(act.codice() + " " + act.label() + ": " + e.getMessage());
                        progress.accept("FAIL         " + act.codice() + "  " + e.getMessage());
                    }
                }
                pipelineReport = pipeline.run(now.toLocalDate());
            }
        } catch (IOException | RuntimeException e) {
            failures.add("run: " + e.getMessage());
        }

        OffsetDateTime finished = IngestionWatermark.nowUtc();
        if (!dryRun) {
            String note = fetched + " downloaded, " + notExported + " without text, " + failures.size() + " failed";
            if (failures.isEmpty()) {
                watermark.recordSuccess(SOURCE, window, finished, note);
                runLog.append(run.completed(finished, updated + missing, fetched, fetched, 0,
                        window.start().toString(), window.end().toString(), note));
            } else {
                watermark.recordFailure(SOURCE, window, finished, String.join("; ", failures));
                IngestionRunLog.Run done = run.completed(finished, updated + missing, fetched, fetched, failures.size(),
                        window.start().toString(), window.end().toString(), note);
                runLog.append(fetched == 0 && failures.stream().anyMatch(f -> f.startsWith("run:"))
                        ? done.failed(finished, String.join("; ", failures)) : done);
            }
        }
        return new Summary(window, updated, outOfScope, missing, fetched, fromSingleText, notExported, failures,
                pipelineReport, dryRun);
    }

    /** Asks for one export, keeps the ZIP under {@code updates/<day>/} and unpacks it; returns the XML files found. */
    private int download(RawStore store, String day, ActRef act, ExportRequest request) throws IOException {
        byte[] zip = source.export(request);
        RawStore.SavedFile saved = store.save("updates/" + day + "/" + request.fileStem() + ".zip", zip,
                "daily update " + day + " " + act.codice());
        return NormattivaSampleFetchRunner.unzip(zip, saved.path().getParent().resolve(request.fileStem()));
    }

    /** The current year, and also the previous one in January (acts of late December are published in January). */
    static List<Integer> yearsToList(IngestionWatermark.Window window) {
        Set<Integer> years = new TreeSet<>();
        years.add(window.end().getYear());
        years.add(window.start().getYear());
        if (window.end().getMonthValue() == 1) {
            years.add(window.end().getYear() - 1);
        }
        return new ArrayList<>(years);
    }

    private Set<String> imported() throws IOException {
        Set<String> codici = new TreeSet<>();
        if (Files.exists(acts)) {
            CorpusIndex.read(acts).acts().forEach(a -> codici.add(a.codice()));
        }
        return codici;
    }

    /** Same file as NormattivaCorpusRunner writes, so the import and the relation rules see the latest list. */
    private void saveList(String type, int year, List<JsonNode> listed) throws IOException {
        ObjectMapper json = new ObjectMapper();
        ArrayNode array = json.createArrayNode();
        listed.forEach(array::add);
        String stem = ExportRequest.slice(ExportRequest.Mode.ALL_VERSIONS, type, year, null).fileStem();
        Path file = root.resolve("corpus").resolve("lists").resolve(stem + ".json");
        Files.createDirectories(file.getParent());
        Files.write(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(array));
    }

    /** {@link Source} backed by the Normattiva OpenData API. */
    public static Source clientSource(NormattivaClient client) {
        return new Source() {
            @Override
            public List<JsonNode> updatedActs(LocalDate from, LocalDate to) throws IOException {
                List<JsonNode> acts = new ArrayList<>();
                JsonNode result = client.updatedActs(from, to);
                JsonNode list = result.path("listaAtti").isArray() ? result.path("listaAtti")
                        : result.path("data").path("listaAtti");
                list.forEach(acts::add);
                return acts;
            }

            @Override
            public List<JsonNode> listed(String type, int year) throws IOException {
                return client.listAll(ExportRequest.slice(ExportRequest.Mode.ALL_VERSIONS, type, year, null));
            }

            @Override
            public byte[] export(ExportRequest request) throws IOException {
                return client.export(request, Duration.ofMinutes(15), Duration.ofSeconds(5));
            }
        };
    }

    /** Import → Gazzetta check → Gazzetta RSS discovery → relations, with the default files. */
    public static Pipeline defaultPipeline(Path root) {
        return defaultPipeline(root, System.getenv().getOrDefault("LEGAL_ELI_BASE_URI", new EliUriService().baseUri()));
    }

    /** The same pipeline, minting identifiers on {@code base}. */
    public static Pipeline defaultPipeline(Path root, String base) {
        return today -> {
            AknImportRunner.Summary imported = new AknImportRunner(new EliUriService(base))
                    .run(root, AknImportRunner.LAYER1, AknImportRunner.LAYER2);
            GazzettaCheckRunner.Summary checked = new GazzettaCheckRunner(new HttpPageFetcher(1000, 2),
                    GazzettaCheckRunner.DEFAULT_CACHE, false)
                    .run(AknImportRunner.LAYER1, GazzettaCheckRunner.DEFAULT_OUT, GazzettaCheckRunner.DEFAULT_REPORT,
                            Integer.MAX_VALUE, line -> { });
            // The Gazzetta RSS feed: acts already published, and whether Normattiva has them yet.
            GazzettaDiscoveryRunner.Summary discovered = new GazzettaDiscoveryRunner(new HttpPageFetcher(1000, 2),
                    GazzettaCheckRunner.DEFAULT_CACHE, false)
                    .run(GazzettaDiscoveryRunner.DEFAULT_RSS, AknImportRunner.LAYER1, GazzettaDiscoveryRunner.DEFAULT_OUT,
                            line -> { });
            RelationRunner.Summary related = new RelationRunner().run(AknImportRunner.LAYERS, root,
                    RelationRunner.DEFAULT_OUT, RelationRunner.DEFAULT_CONVERSIONS, RelationRunner.DEFAULT_RELATIONS, today);
            return "Pipeline: " + imported.acts() + " acts, " + imported.expressions() + " versions; Gazzetta "
                    + checked.counts() + " (" + checked.fetched() + " pages fetched); Gazzetta RSS "
                    + discovered.acts().size() + " acts, " + discovered.waiting() + " waiting for Normattiva; "
                    + related.result().relations().size() + " relations, "
                    + related.result().conversions().size() + " decree-laws";
        };
    }

    private static void log(String line) {
        System.out.println(java.time.LocalTime.now().withNano(0) + " " + line);
        System.out.flush();
    }
}
