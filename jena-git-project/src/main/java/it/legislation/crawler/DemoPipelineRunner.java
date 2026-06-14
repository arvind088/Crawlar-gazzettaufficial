package it.legislation.crawler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class DemoPipelineRunner {

    private static final Path GAZZETTA_DELTA = Path.of("data", "rdf", "gazzetta_metadata_delta.ttl");
    private static final Path DEMO_QUERY_DIR = Path.of("queries", "demo-gazzetta");

    public static void main(String[] args) throws IOException {
        printStage("1. Discover latest Gazzetta RSS entries");
        GazzettaRssUpdateRunner.main(new String[0]);

        printStage("2. Crawl discovered Gazzetta legal acts and generate RDF delta");
        GazzettaRssCrawlerRunner.main(new String[0]);

        printStage("3. Query generated Gazzetta RDF delta");
        if (!Files.exists(GAZZETTA_DELTA)) {
            System.out.println("No Gazzetta RDF delta found yet: " + GAZZETTA_DELTA.toAbsolutePath().normalize());
            System.out.println("Run the crawler again after new RSS entries are available, or delete the demo registry for a fresh demo.");
            return;
        }

        SampleRdfQueryRunner.main(new String[]{
                GAZZETTA_DELTA.toString(),
                DEMO_QUERY_DIR.toString()
        });
    }

    private static void printStage(String title) {
        System.out.println();
        System.out.println("============================================================");
        System.out.println(title);
        System.out.println("============================================================");
    }
}
