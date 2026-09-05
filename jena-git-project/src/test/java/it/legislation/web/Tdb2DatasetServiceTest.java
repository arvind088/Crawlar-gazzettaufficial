package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the triple store is the system of record (FR-2.1, NFR-1) rather
 * than a cache rebuilt from files, and that ingestion is additive (FR-1.4).
 *
 * <p>The first test is the strengthened form of manual test case TC-11. TC-11 as
 * written restarts the process and checks the triple count, which an
 * implementation that re-parses its Turtle files at startup would also pass.
 * Removing the file before reopening is what actually distinguishes a persistent
 * store from a file cache.
 */
class Tdb2DatasetServiceTest {

    private static final String ACT = "http://www.gazzettaufficiale.it/eli/id/2020/03/17/20G00034/sg";

    private static final String TURTLE = """
            @prefix eli:  <http://data.europa.eu/eli/ontology#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .

            <%s>
              a eli:LegalResource ;
              rdfs:label "Decreto-legge 17 marzo 2020, n. 18" ;
              eli:id_local "20G00034" ;
              eli:date_publication "2020-03-17"^^xsd:date .
            """.formatted(ACT);

    @Test
    void dataSurvivesRestartEvenWhenTheSourceFileIsGone() throws IOException {
        Path workspace = newStoreDirectory();
        Path store = workspace.resolve("tdb2");
        Path turtle = workspace.resolve("acts.ttl");
        Files.writeString(turtle, TURTLE, StandardCharsets.UTF_8);

        Tdb2DatasetService first = Tdb2DatasetService.forRdfPaths(store, List.of(turtle));
        long ingested = first.status().triples();
        first.close();

        assertTrue(ingested > 0, "expected the bootstrap file to be ingested");

        // The file is the bootstrap source, not the record. Once ingested, the
        // store must stand on its own.
        Files.delete(turtle);

        Tdb2DatasetService reopened = Tdb2DatasetService.forRdfPaths(store, List.of(turtle));
        try {
            assertEquals(ingested, reopened.status().triples(),
                    "triple count changed after restart without the source file");
            assertTrue(containsAct(reopened), "the act was not retrievable after restart");
        } finally {
            reopened.close();
        }
    }

    @Test
    void aFileIsIngestedOnceRatherThanOnEveryRead() throws IOException {
        Path workspace = newStoreDirectory();
        Path store = workspace.resolve("tdb2");
        Path turtle = workspace.resolve("acts.ttl");
        Files.writeString(turtle, TURTLE, StandardCharsets.UTF_8);

        Tdb2DatasetService service = Tdb2DatasetService.forRdfPaths(store, List.of(turtle));
        try {
            long afterFirstRead = service.status().triples();
            long afterSecondRead = service.status().triples();
            long afterThirdRead = service.size();

            assertEquals(afterFirstRead, afterSecondRead);
            assertEquals(afterFirstRead, afterThirdRead);
        } finally {
            service.close();
        }
    }

    @Test
    void addingTriplesIsAdditiveAndIdempotent() throws IOException {
        Path workspace = newStoreDirectory();
        Path store = workspace.resolve("tdb2");
        Path turtle = workspace.resolve("acts.ttl");
        Files.writeString(turtle, TURTLE, StandardCharsets.UTF_8);

        Tdb2DatasetService service = Tdb2DatasetService.forRdfPaths(store, List.of(turtle));
        try {
            long before = service.status().triples();

            Model addition = ModelFactory.createDefaultModel();
            addition.add(
                    addition.createResource(ACT),
                    addition.createProperty("http://data.europa.eu/eli/ontology#", "number"),
                    "18"
            );

            long gained = service.add(addition);
            assertEquals(1L, gained, "one new statement should have been added");
            assertEquals(before + 1, service.size(), "the earlier statements must still be there");

            // A graph is a set: re-adding the same statement changes nothing and
            // removes nothing.
            assertEquals(0L, service.add(addition));
            assertEquals(before + 1, service.size());
            assertTrue(containsAct(service), "the originally ingested act must survive an addition");
        } finally {
            service.close();
        }
    }


    /**
     * TDB2 memory-maps its index files, and on Windows a mapped file cannot be
     * deleted until the JVM releases the mapping — which does not reliably happen
     * at {@code close()}. JUnit's {@code @TempDir} therefore fails the test during
     * cleanup with a DirectoryNotEmptyException, even when every assertion passed.
     *
     * <p>Store directories are created under {@code target/} instead and left in
     * place; {@code mvn clean} removes them.
     */
    private static Path newStoreDirectory() throws IOException {
        Path directory = Path.of("target", "test-stores", UUID.randomUUID().toString());
        Files.createDirectories(directory);
        return directory;
    }

    private boolean containsAct(Tdb2DatasetService service) throws IOException {
        return service.read(dataset -> {
            String ask = "ASK { <" + ACT + "> ?predicate ?object }";
            try (QueryExecution execution =
                         QueryExecutionFactory.create(QueryFactory.create(ask), dataset)) {
                return execution.execAsk();
            }
        });
    }
}
