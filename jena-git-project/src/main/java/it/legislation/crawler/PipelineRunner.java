package it.legislation.crawler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class PipelineRunner {

    private static final Path DATA_DIR = Paths.get("data");
    private static final Path RAW_GAZZETTA_DIR = DATA_DIR.resolve("raw").resolve("gazzetta");
    private static final Path RAW_NORMATTIVA_DIR = DATA_DIR.resolve("raw").resolve("normattiva");
    private static final Path CLEAN_DIR = DATA_DIR.resolve("clean");
    private static final Path RDF_DIR = DATA_DIR.resolve("rdf");
    private static final Path REGISTRY_DIR = DATA_DIR.resolve("registry");
    private static final Path REPORTS_DIR = DATA_DIR.resolve("reports");
    private static final Path REGISTRY_FILE = REGISTRY_DIR.resolve("crawl_registry.tsv");

    public static void main(String[] args) throws IOException {
        PipelineRunner runner = new PipelineRunner();
        runner.run();
    }

    public void run() throws IOException {
        List<String> reportLines = new ArrayList<>();
        OffsetDateTime startedAt = OffsetDateTime.now();

        add(reportLines, "Pipeline run started at: " + startedAt);
        ensureDirectories(reportLines);

        CrawlRegistry registry = new CrawlRegistry(REGISTRY_FILE);
        registry.ensureExists();
        add(reportLines, "Registry file: " + registry.getRegistryPath().toAbsolutePath().normalize());
        add(reportLines, "Known registry records: " + registry.countRecords());

        runStage(reportLines, "1. Discover candidate Gazzetta updates",
                "TODO: read latest archive/RSS/date pages and normalize discovered act URLs to canonical ELI URIs.");

        runStage(reportLines, "2. Compare candidates with crawl registry",
                "TODO: skip unchanged acts, mark new or stale acts for crawling, and update lastCheckedAt.");

        runStage(reportLines, "3. Fetch raw source data",
                "TODO: cache Gazzetta HTML under data/raw/gazzetta and Normattiva source data under data/raw/normattiva.");

        runStage(reportLines, "4. Clean and normalize records",
                "TODO: convert raw extraction into cleaned TSV/CSV records under data/clean.");

        runStage(reportLines, "5. Generate RDF/Turtle",
                "TODO: use Apache Jena Model API to write valid TTL files under data/rdf.");

        runStage(reportLines, "6. Load or replace Fuseki graphs",
                "TODO: load generated TTL into named graphs for Gazzetta, Normattiva, and integrated data.");

        OffsetDateTime finishedAt = OffsetDateTime.now();
        add(reportLines, "Pipeline run finished at: " + finishedAt);
        writeReport(reportLines, startedAt);

        System.out.println();
        System.out.println("Pipeline skeleton completed. No live crawling has been performed yet.");
    }

    private void ensureDirectories(List<String> reportLines) throws IOException {
        for (Path directory : List.of(
                RAW_GAZZETTA_DIR,
                RAW_NORMATTIVA_DIR,
                CLEAN_DIR,
                RDF_DIR,
                REGISTRY_DIR,
                REPORTS_DIR
        )) {
            Files.createDirectories(directory);
            add(reportLines, "Ensured directory: " + directory.toAbsolutePath().normalize());
        }
    }

    private void runStage(List<String> reportLines, String stageName, String detail) {
        add(reportLines, stageName);
        add(reportLines, "  " + detail);
    }

    private void writeReport(List<String> reportLines, OffsetDateTime startedAt) throws IOException {
        Files.createDirectories(REPORTS_DIR);
        String timestamp = startedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path reportPath = REPORTS_DIR.resolve("pipeline-run-" + timestamp + ".txt");
        Files.write(reportPath, reportLines, StandardCharsets.UTF_8);
        add(reportLines, "Report written to: " + reportPath.toAbsolutePath().normalize());
    }

    private void add(List<String> reportLines, String message) {
        reportLines.add(message);
        System.out.println(message);
    }
}
