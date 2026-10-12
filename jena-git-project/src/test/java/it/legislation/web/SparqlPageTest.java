package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import it.legislation.eli.EliUriService;
import it.legislation.store.Tdb2DatasetService;

/** The SPARQL page runs a query and shows the rows, or explains what is wrong. */
class SparqlPageTest {

    private final SparqlPage page = new SparqlPage(
            Tdb2DatasetService.inMemoryForRdfPaths(List.of(Path.of("src/test/resources/web/acts.ttl"))),
            new EliUriService("https://example.it"), "queries", 30);

    @Test
    void showsResultRowsWithLocalLinks() {
        String html = page.page("SELECT ?act WHERE { ?act <http://data.europa.eu/eli/ontology#id_local> \"20G00034\" }",
                null).getBody();
        assertTrue(html.contains("1 row"));
        assertTrue(html.contains("href=\"/eli/id/2020/03/17/20G00034/sg\""));
        assertTrue(html.contains("/sparql?output=csv&amp;query="));
    }

    @Test
    void reportsInvalidQueries() {
        assertTrue(page.page("SELEC nothing", null).getBody().contains("not valid SPARQL"));
    }

    @Test
    void offersTheExampleQueries() {
        String html = page.page(null, null).getBody();
        assertTrue(html.contains("08-conversions"));
        assertTrue(html.contains("Run query"));
    }
}
