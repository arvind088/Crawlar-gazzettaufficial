package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.junit.jupiter.api.Test;

/**
 * Runs the validation queries the specification requires, and asserts their
 * expected results (FR-5.1, FR-5.2, FR-5.3, US-D1, TC-07).
 *
 * <p>These queries already existed, documented in {@code VALIDATION_QUERIES.md}
 * with hand-verified answers. Their weakness was that nothing executed them: the
 * expected results lived in prose, CI ran only {@code mvn test}, and a change to
 * the data model could silently invalidate every one of them. Encoding them here
 * turns the strongest documentation artifact in the project into a regression
 * gate.
 *
 * <p>The fixture is the seed data from CONTEXT.md section 3.1, so these
 * assertions describe acts whose correct answers can be checked against the
 * Gazzetta Ufficiale record by hand.
 */
class ValidationQueriesTest {

    private static final String PREFIXES = """
            PREFIX eli:     <http://data.europa.eu/eli/ontology#>
            PREFIX rdfs:    <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX dcterms: <http://purl.org/dc/terms/>
            """;

    private static final String GU = "http://www.gazzettaufficiale.it/eli/";
    private static final String DL_18 = GU + "id/2020/03/17/20G00034/sg";
    private static final String LEGGE_27 = GU + "id/2020/04/24/20G00043/sg";
    private static final String DL_19 = GU + "id/2020/03/25/20G00035/sg";
    private static final String LEGGE_35 = GU + "id/2020/05/22/20G00057/sg";

    /** Query 1 - every act, with title and publication date. */
    @Test
    void allActsAreListedWithTitleAndDate() throws IOException {
        Path dir = newStoreDirectory();
        List<QuerySolution> rows = run(dir, PREFIXES + """
                SELECT ?act ?label ?date WHERE {
                  ?act a eli:LegalResource ;
                       rdfs:label ?label ;
                       eli:date_publication ?date .
                } ORDER BY ?date
                """);

        assertEquals(4, rows.size(), "the seed data defines exactly four acts");
        assertEquals(DL_18, uri(rows.get(0), "act"), "earliest published act first");
    }

    /** Query 2 - acts filtered by publication year. */
    @Test
    void actsCanBeFilteredByYear() throws IOException {
        Path dir = newStoreDirectory();
        assertEquals(4, run(dir, PREFIXES + """
                SELECT ?act WHERE {
                  ?act a eli:LegalResource ; eli:date_publication ?date .
                  FILTER(YEAR(?date) = 2020)
                }
                """).size());

        assertEquals(0, run(dir, PREFIXES + """
                SELECT ?act WHERE {
                  ?act a eli:LegalResource ; eli:date_publication ?date .
                  FILTER(YEAR(?date) = 1999)
                }
                """).size());
    }

    /** Query 3 - acts filtered by type: Decreto Legge versus Legge. */
    @Test
    void actsCanBeFilteredByDocumentType() throws IOException {
        Path dir = newStoreDirectory();
        assertEquals(2, run(dir, PREFIXES + """
                SELECT ?act WHERE {
                  ?act eli:type_document <%stables/resource-type#DECRETOLEGGE> .
                }
                """.formatted(GU)).size());

        assertEquals(2, run(dir, PREFIXES + """
                SELECT ?act WHERE {
                  ?act eli:type_document <%stables/resource-type#LEGGE> .
                }
                """.formatted(GU)).size());
    }

    /** Query 4 - most recently published acts. */
    @Test
    void latestActsComeBackNewestFirst() throws IOException {
        Path dir = newStoreDirectory();
        List<QuerySolution> rows = run(dir, PREFIXES + """
                SELECT ?act ?date WHERE {
                  ?act a eli:LegalResource ; eli:date_publication ?date .
                } ORDER BY DESC(?date) LIMIT 2
                """);

        assertEquals(LEGGE_35, uri(rows.get(0), "act"), "Legge 35/2020 is the most recent");
        assertEquals(LEGGE_27, uri(rows.get(1), "act"));
    }

    /**
     * Query 5 - conversion-link validation (FR-5.3, TC-02). Every eli:commences
     * subject must be a Legge and its object a Decreto Legge; a conversion
     * pointing the other way would be a modelling error.
     */
    @Test
    void everyConversionLinkRunsFromALeggeToADecretoLegge() throws IOException {
        Path dir = newStoreDirectory();
        List<QuerySolution> wrongWayRound = run(dir, PREFIXES + """
                SELECT ?legge ?decreto WHERE {
                  ?legge eli:commences ?decreto .
                  FILTER NOT EXISTS {
                    ?legge   eli:type_document <%stables/resource-type#LEGGE> .
                    ?decreto eli:type_document <%stables/resource-type#DECRETOLEGGE> .
                  }
                }
                """.formatted(GU, GU));

        assertTrue(wrongWayRound.isEmpty(),
                "found a conversion that is not Legge -> Decreto Legge: " + wrongWayRound);

        List<QuerySolution> conversions = run(dir, PREFIXES + """
                SELECT ?legge ?decreto WHERE { ?legge eli:commences ?decreto . } ORDER BY ?legge
                """);
        assertEquals(2, conversions.size(), "the seed data defines two conversion pairs");
        assertEquals(LEGGE_27, uri(conversions.get(0), "legge"));
        assertEquals(DL_18, uri(conversions.get(0), "decreto"));
        assertEquals(LEGGE_35, uri(conversions.get(1), "legge"));
        assertEquals(DL_19, uri(conversions.get(1), "decreto"));
    }

    /** Query 6 - ELI-level validation: flag acts missing a title or a date. */
    @Test
    void everySeedActHasATitleAndAPublicationDate() throws IOException {
        Path dir = newStoreDirectory();
        List<QuerySolution> incomplete = run(dir, PREFIXES + """
                SELECT ?act WHERE {
                  ?act a eli:LegalResource .
                  FILTER (NOT EXISTS { ?act rdfs:label ?label } ||
                          NOT EXISTS { ?act eli:date_publication ?date })
                }
                """);

        assertTrue(incomplete.isEmpty(), "acts missing mandatory metadata: " + incomplete);
    }

    /**
     * Query 7 - multi-version acts (FR-5.2, FR-4.4, TC-04). The specification
     * requires this to return a Legge from the seed data, not only the
     * hand-authored sample.
     */
    @Test
    void multiVersionActsAreFoundWithTheirExpressions() throws IOException {
        Path dir = newStoreDirectory();
        List<QuerySolution> rows = run(dir, PREFIXES + """
                SELECT ?work (COUNT(?expression) AS ?versions) WHERE {
                  ?work eli:is_realized_by ?expression .
                } GROUP BY ?work HAVING (COUNT(?expression) > 1)
                """);

        assertEquals(1, rows.size(), "Legge 35/2020 is the seed act with two Expressions");
        assertEquals(LEGGE_35, uri(rows.get(0), "work"));
    }

    /** Exactly one Expression of a multi-version Work may be in force (US-A3). */
    @Test
    void exactlyOneExpressionOfTheMultiVersionActIsInForce() throws IOException {
        Path dir = newStoreDirectory();
        List<QuerySolution> inForce = run(dir, PREFIXES + """
                SELECT ?expression WHERE {
                  <%s> eli:is_realized_by ?expression .
                  ?expression eli:in_force
                    <http://publications.europa.eu/resource/authority/eli-in-force/IN_FORCE> .
                }
                """.formatted(LEGGE_35));

        assertEquals(1, inForce.size());
        assertTrue(uri(inForce.get(0), "expression").contains("vigente"),
                "the current text should be the one in force, not the original");
    }

    /**
     * TC-03: navigating a relation that is not a conversion. The test exists to
     * prove generic navigability, and was previously impossible to run because
     * no such predicate was present in any dataset.
     */
    @Test
    void nonConversionRelationsExistToNavigate() throws IOException {
        Path dir = newStoreDirectory();
        assertTrue(run(dir, PREFIXES + "SELECT ?act ?topic WHERE { ?act eli:is_about ?topic . }")
                .size() >= 1, "eli:is_about is required by TC-03");
        assertTrue(run(dir, PREFIXES + "SELECT ?act ?body WHERE { ?act eli:passed_by ?body . }")
                .size() >= 1, "eli:passed_by is required by TC-03");
    }

    // ------------------------------------------------------------------ setup

    private List<QuerySolution> run(Path dir, String query) throws IOException {
        // A fresh store directory per query keeps TDB2 lock handling out of the
        // picture; the seed file is small enough that re-ingesting is free.
        Tdb2DatasetService store = store(newStoreDirectory());
        try {
            return store.read(dataset -> {
                List<QuerySolution> rows = new ArrayList<>();
                try (QueryExecution execution =
                             QueryExecutionFactory.create(QueryFactory.create(query), dataset)) {
                    ResultSet results = execution.execSelect();
                    while (results.hasNext()) {
                        rows.add(results.nextSolution());
                    }
                }
                return rows;
            });
        } finally {
            store.close();
        }
    }

    /**
     * Loads only the seed file, so these assertions stay stable as crawled data
     * grows. The queries themselves are written against the whole store.
     */

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

    private Tdb2DatasetService store(Path dir) throws IOException {
        Path seed = Path.of("data", "rdf", "seed_acts.ttl");
        assertTrue(Files.exists(seed),
                "seed data required by CONTEXT.md 3.1 is missing at " + seed.toAbsolutePath());
        return Tdb2DatasetService.forRdfPaths(dir, List.of(seed));
    }

    private String uri(QuerySolution solution, String name) {
        return solution.get(name).asResource().getURI();
    }
}
