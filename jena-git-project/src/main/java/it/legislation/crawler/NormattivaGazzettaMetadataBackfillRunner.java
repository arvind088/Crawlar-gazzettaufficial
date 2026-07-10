package it.legislation.crawler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

public class NormattivaGazzettaMetadataBackfillRunner {

    private static final Path NORMATTIVA_MODIFICATIONS = Path.of("data", "rdf", "normattiva_modifications.ttl");
    private static final Path NORMATTIVA_AUTO_MODIFICATIONS = Path.of("data", "rdf", "normattiva_modifications_auto.ttl");
    private static final String GAZZETTA_ELI_PREFIX = "http://www.gazzettaufficiale.it/eli/id/";

    public static void main(String[] args) throws IOException {
        List<String> gazzettaUris = readGazzettaUris(List.of(NORMATTIVA_MODIFICATIONS, NORMATTIVA_AUTO_MODIFICATIONS));
        if (gazzettaUris.isEmpty()) {
            System.out.println("No Gazzetta ELI URIs found in Normattiva relationship RDF.");
            return;
        }

        int changedRecords = GazzettaScraper.crawlGazzettaActUrls(gazzettaUris);
        System.out.printf("Backfilled Gazzetta metadata for %d changed relation act(s).%n", changedRecords);
    }

    static List<String> readGazzettaUris(List<Path> turtlePaths) throws IOException {
        Model model = ModelFactory.createDefaultModel();
        for (Path turtlePath : turtlePaths) {
            if (!Files.exists(turtlePath)) {
                continue;
            }
            try (InputStream inputStream = Files.newInputStream(turtlePath)) {
                RDFDataMgr.read(model, inputStream, Lang.TURTLE);
            }
        }

        Set<String> uris = new LinkedHashSet<>();
        model.listSubjects()
                .filterKeep(resource -> resource.isURIResource() && isGazzettaEliUri(resource.getURI()))
                .forEachRemaining(resource -> uris.add(resource.getURI()));
        model.listObjects()
                .filterKeep(RDFNode::isURIResource)
                .mapWith(RDFNode::asResource)
                .filterKeep(resource -> isGazzettaEliUri(resource.getURI()))
                .forEachRemaining(resource -> uris.add(resource.getURI()));

        return List.copyOf(uris);
    }

    private static boolean isGazzettaEliUri(String uri) {
        return uri != null
                && uri.startsWith(GAZZETTA_ELI_PREFIX)
                && uri.toLowerCase().endsWith("/sg");
    }
}
