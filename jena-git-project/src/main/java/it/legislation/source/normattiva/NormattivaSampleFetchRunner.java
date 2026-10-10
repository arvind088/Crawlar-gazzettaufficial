package it.legislation.source.normattiva;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import it.legislation.source.RawStore;

/**
 * Fetches the reference acts used to study Normattiva AKN markup (R2 spike):
 *
 * <ul>
 *   <li>D.Lgs. 196/2003 (Privacy Code), modified many times: all versions</li>
 *   <li>D.Lgs. 101/2018, the act that modified it: original text</li>
 *   <li>D.L. 18/2020 and L. 27/2020, a decree-law and its conversion law: original texts</li>
 *   <li>one recent law Normattiva classifies as never updated (baseline)</li>
 * </ul>
 *
 * Files are saved unchanged under {@code data/raw/normattiva/} with SHA-256 in {@code manifest.tsv};
 * export archives are also unpacked next to the ZIP for easy reading.
 *
 * <pre>
 * mvn -B "-Dexec.mainClass=it.legislation.source.normattiva.NormattivaSampleFetchRunner" exec:java
 * </pre>
 */
public class NormattivaSampleFetchRunner {

    record Sample(String folder, ExportRequest request, List<String> detailUrns) {}

    /** Smallest exports first, so a slow queue shows early whether exports work at all. */
    static final List<Sample> SAMPLES = List.of(
            new Sample("l-2020-027-conversion-law",
                    ExportRequest.akn(ExportRequest.Mode.ORIGINAL, "LEGGE", 2020, 4, 24, 27),
                    List.of("urn:nir:stato:legge:2020-04-24;27@originale")),
            new Sample("dl-2020-018-cura-italia",
                    ExportRequest.akn(ExportRequest.Mode.ORIGINAL, "DECRETO-LEGGE", 2020, 3, 17, 18),
                    List.of("urn:nir:stato:decreto.legge:2020-03-17;18@originale")),
            new Sample("dlgs-2018-101-modifier",
                    ExportRequest.akn(ExportRequest.Mode.ORIGINAL, "DECRETO LEGISLATIVO", 2018, 8, 10, 101),
                    List.of("urn:nir:stato:decreto.legislativo:2018-08-10;101@originale")),
            new Sample("dlgs-2003-196-privacy-code",
                    ExportRequest.akn(ExportRequest.Mode.ALL_VERSIONS, "DECRETO LEGISLATIVO", 2003, 6, 30, 196),
                    List.of("urn:nir:stato:decreto.legislativo:2003-06-30;196@originale",
                            "urn:nir:stato:decreto.legislativo:2003-06-30;196"))
    );

    private static final Duration POLL_EVERY = Duration.ofSeconds(10);

    private final NormattivaClient client;
    private final RawStore store;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final List<String> report = new ArrayList<>();
    private final Duration exportTimeout;
    private String current = "";

    NormattivaSampleFetchRunner(NormattivaClient client, RawStore store, Duration exportTimeout) {
        this.client = client;
        this.store = store;
        this.exportTimeout = exportTimeout;
    }

    /**
     * Arguments (all optional): {@code --only <folder>} fetches one sample (or {@code baseline}),
     * {@code --timeout-min <n>} waits up to n minutes per export (default 20),
     * {@code --root <dir>} changes the output folder.
     */
    public static void main(String[] args) throws IOException {
        Path root = Path.of("data/raw/normattiva");
        String only = null;
        Duration timeout = Duration.ofMinutes(20);
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--only" -> only = args[i + 1];
                case "--timeout-min" -> timeout = Duration.ofMinutes(Long.parseLong(args[i + 1]));
                case "--root" -> root = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        NormattivaSampleFetchRunner runner =
                new NormattivaSampleFetchRunner(new NormattivaClient(), new RawStore(root), timeout);
        boolean ok = runner.run(only);
        System.out.println();
        System.out.println("Summary:");
        runner.report.forEach(System.out::println);
        System.out.println(ok ? "All samples fetched." : "Some samples failed: see lines marked FAIL.");
    }

    boolean run(String only) {
        boolean ok = true;
        int total = SAMPLES.size() + 1, index = 0;
        for (Sample sample : SAMPLES) {
            index++;
            if (only != null && !only.equals(sample.folder())) {
                continue;
            }
            current = "[" + index + "/" + total + " " + sample.folder() + "] ";
            ok &= fetchDetails(sample);
            ok &= fetchExport(sample.folder(), sample.request());
        }
        if (only == null || only.equals("baseline")) {
            current = "[" + total + "/" + total + " baseline] ";
            ok &= fetchBaseline();
        }
        return ok;
    }

    private void log(String line) {
        System.out.println(java.time.LocalTime.now().withNano(0) + " " + current + line);
        System.out.flush();
    }

    private void result(String line) {
        report.add(line);
        log(line);
    }

    private boolean fetchDetails(Sample sample) {
        boolean ok = true;
        for (String urn : sample.detailUrns()) {
            String name = sample.folder() + "/detail_" + urn.replaceAll("[^A-Za-z0-9.;=-]+", "_") + ".json";
            try {
                JsonNode detail = client.actDetailByUrn(urn);
                RawStore.SavedFile saved = store.save(name, json.writeValueAsBytes(detail), "dettaglio-atto-urn " + urn);
                result("OK    " + saved.status() + "  " + name);
            } catch (IOException | RuntimeException exception) {
                result("FAIL  " + name + "  " + exception.getMessage());
                ok = false;
            }
        }
        return ok;
    }

    private boolean fetchExport(String folder, ExportRequest request) {
        String name = folder + "/" + request.fileStem() + ".zip";
        try {
            log("requesting export " + request.fileStem() + " (waits up to " + exportTimeout.toMinutes() + " min)");
            byte[] zip = client.export(request, exportTimeout, POLL_EVERY, this::log);
            RawStore.SavedFile saved = store.save(name, zip, "export " + request.fileStem());
            int entries = unzip(zip, saved.path().getParent().resolve(request.fileStem()));
            result("OK    " + saved.status() + "  " + name + "  (" + entries + " files)");
            return true;
        } catch (IOException | RuntimeException exception) {
            result("FAIL  " + name + "  " + exception.getMessage());
            return false;
        }
    }

    /** Finds one recent law never updated (classeProvvedimento 1) and exports its original AKN. */
    private boolean fetchBaseline() {
        String folder = "baseline-never-updated";
        try {
            ObjectNode criteria = client.criteria();
            criteria.put("denominazioneAtto", "LEGGE");
            criteria.put("annoProvvedimento", LocalDate.now().getYear() - 1);
            criteria.put("classeProvvedimento", "1");
            JsonNode result = client.advancedSearch(criteria, 1, 5);
            store.save(folder + "/search.json", json.writeValueAsBytes(result), "ricerca/avanzata classe 1");
            JsonNode first = result.path("listaAtti").path(0);
            if (first.isMissingNode()) {
                result("FAIL  " + folder + "  search returned no acts");
                return false;
            }
            ExportRequest request = ExportRequest.akn(ExportRequest.Mode.ORIGINAL,
                    first.path("denominazioneAtto").asText(),
                    first.path("annoProvvedimento").asInt(),
                    first.path("meseProvvedimento").asInt(),
                    first.path("giornoProvvedimento").asInt(),
                    first.path("numeroProvvedimento").asInt());
            log("baseline act: " + first.path("descrizioneAtto").asText(request.fileStem()));
            return fetchExport(folder, request);
        } catch (IOException | RuntimeException exception) {
            result("FAIL  " + folder + "  " + exception.getMessage());
            return false;
        }
    }

    /** Unpacks a ZIP into {@code target}, refusing entries that would escape it. */
    static int unzip(byte[] zip, Path target) throws IOException {
        int count = 0;
        Path base = target.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path out = base.resolve(entry.getName()).normalize();
                if (!out.startsWith(base)) {
                    throw new IOException("Unsafe ZIP entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.write(out, in.readAllBytes());
                    count++;
                }
            }
        }
        return count;
    }
}
