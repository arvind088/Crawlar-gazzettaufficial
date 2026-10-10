package it.legislation.source.normattiva;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.legislation.source.RawStore;

/**
 * Downloads the thesis corpus from the Normattiva OpenData API: every act of the
 * chosen types and years, with all its versions, as Akoma Ntoso.
 *
 * <p>For each act type and year it first counts the acts ({@code ricerca/avanzata}),
 * then exports them. A year with more acts than {@code --max-per-export} is
 * exported one month at a time, so no single export is too large. A slice whose
 * ZIP is already in the raw store is skipped, so an interrupted run can simply be
 * started again. Counts are written to {@code corpus/counts.tsv} for the import
 * to check against.
 *
 * <pre>
 * mvn -B compile exec:java "-Dexec.mainClass=it.legislation.source.normattiva.NormattivaCorpusRunner"
 * </pre>
 *
 * Options: {@code --from 2020}, {@code --to <this year>},
 * {@code --types "LEGGE,DECRETO-LEGGE,DECRETO LEGISLATIVO"}, {@code --max-per-export 100},
 * {@code --timeout-min 30}, {@code --root data/raw/normattiva}, {@code --count-only true},
 * {@code --refresh true} (download again even when a ZIP exists).
 */
public class NormattivaCorpusRunner {

    static final List<String> DEFAULT_TYPES = List.of("LEGGE", "DECRETO-LEGGE", "DECRETO LEGISLATIVO");
    private static final Duration POLL_EVERY = Duration.ofSeconds(10);

    private final NormattivaClient client;
    private final RawStore store;
    private final Path root;
    private final Duration timeout;
    private final int maxPerExport;
    private final boolean refresh;
    private final boolean countOnly;
    private final List<String> failures = new ArrayList<>();
    private int exported;
    private int skipped;

    NormattivaCorpusRunner(NormattivaClient client, Path root, Duration timeout, int maxPerExport,
                           boolean refresh, boolean countOnly) {
        this.client = client;
        this.root = root;
        this.store = new RawStore(root);
        this.timeout = timeout;
        this.maxPerExport = maxPerExport;
        this.refresh = refresh;
        this.countOnly = countOnly;
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        int from = Integer.parseInt(options.getOrDefault("--from", "2020"));
        int to = Integer.parseInt(options.getOrDefault("--to", Integer.toString(LocalDate.now().getYear())));
        List<String> types = options.containsKey("--types")
                ? Arrays.stream(options.get("--types").split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList()
                : DEFAULT_TYPES;
        NormattivaCorpusRunner runner = new NormattivaCorpusRunner(
                new NormattivaClient(),
                Path.of(options.getOrDefault("--root", "data/raw/normattiva")),
                Duration.ofMinutes(Long.parseLong(options.getOrDefault("--timeout-min", "30"))),
                Integer.parseInt(options.getOrDefault("--max-per-export", "100")),
                Boolean.parseBoolean(options.getOrDefault("--refresh", "false")),
                Boolean.parseBoolean(options.getOrDefault("--count-only", "false")));
        runner.run(types, from, to);
    }

    void run(List<String> types, int from, int to) throws IOException {
        Path countsFile = root.resolve("corpus").resolve(CorpusCounts.FILE);
        CorpusCounts counts = CorpusCounts.read(countsFile);
        for (String type : types) {
            for (int year = from; year <= to; year++) {
                try {
                    int count = client.count(ExportRequest.slice(ExportRequest.Mode.ALL_VERSIONS, type, year, null));
                    counts.put(type, year, count, OffsetDateTime.now(ZoneOffset.UTC).withNano(0).toString());
                    counts.write(countsFile);
                    log(type + " " + year + ": " + count + " acts");
                    if (count == 0 || countOnly) {
                        continue;
                    }
                    if (count <= maxPerExport) {
                        exportSlice(ExportRequest.slice(ExportRequest.Mode.ALL_VERSIONS, type, year, null));
                    } else {
                        exportByMonth(type, year, count);
                    }
                } catch (IOException | RuntimeException exception) {
                    failures.add(type + " " + year + ": " + describe(exception));
                    log("FAIL " + type + " " + year + ": " + describe(exception));
                }
            }
        }
        System.out.println();
        System.out.println("Counts (act type, year, acts):");
        counts.all().forEach((key, count) -> System.out.println("  " + key.actType() + "\t" + key.year() + "\t" + count));
        System.out.println("Exports downloaded: " + exported + ", already present (skipped): " + skipped
                + ", failed: " + failures.size());
        failures.forEach(failure -> System.out.println("  FAIL " + failure));
        System.out.println("Next: mvn -B compile exec:java \"-Dexec.mainClass=it.legislation.ingest.AknImportRunner\"");
    }

    private void exportByMonth(String type, int year, int yearCount) throws IOException {
        int sum = 0;
        for (int month = 1; month <= 12; month++) {
            ExportRequest request = ExportRequest.slice(ExportRequest.Mode.ALL_VERSIONS, type, year, month);
            int count = client.count(request);
            sum += count;
            if (count > 0) {
                log(type + " " + year + "-" + String.format("%02d", month) + ": " + count + " acts");
                exportSlice(request);
            }
        }
        if (sum != yearCount) {
            log("WARNING " + type + " " + year + ": months add up to " + sum + " but the year has " + yearCount);
        }
    }

    private void exportSlice(ExportRequest request) throws IOException {
        String folder = "corpus/" + request.actType().replaceAll("[^A-Za-z0-9]+", "_") + "/" + request.year();
        String name = folder + "/" + request.fileStem() + ".zip";
        Path zipPath = root.resolve(name);
        if (Files.exists(zipPath) && !refresh) {
            skipped++;
            log("skip " + name + " (already downloaded)");
            return;
        }
        log("export " + request.fileStem() + " (waits up to " + timeout.toMinutes() + " min)");
        byte[] zip = client.export(request, timeout, POLL_EVERY, line -> log("  " + line));
        RawStore.SavedFile saved = store.save(name, zip, "export " + request.fileStem());
        int files = NormattivaSampleFetchRunner.unzip(zip, saved.path().getParent().resolve(request.fileStem()));
        exported++;
        log("OK " + saved.status() + " " + name + " (" + files + " files, " + zip.length / 1024 + " KB)");
    }

    private static void log(String line) {
        System.out.println(LocalTime.now().withNano(0) + " " + line);
        System.out.flush();
    }

    private static String describe(Throwable exception) {
        return exception.getClass().getSimpleName() + ": " + exception.getMessage();
    }
}
