package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import it.legislation.eli.EliUriService;
import it.legislation.store.Tdb2DatasetService;

/** The SPARQL page lists the example questions, runs a query and shows readable rows, or explains what is wrong. */
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
    void showsActsAsCitationsAndTypesByName() {
        String html = page.page("SELECT ?act ?type WHERE { ?act <http://data.europa.eu/eli/ontology#id_local> \"20G00034\" ; "
                + "<http://data.europa.eu/eli/ontology#type_document> ?type }", null).getBody();
        // Not the bare path or the resource-type IRI, but how people name them.
        assertTrue(html.contains("<b>D.L. 17 marzo 2020, n. 18</b></a>"), html);
        assertTrue(html.contains(">Decreto-legge</span>"));
        assertFalse(html.contains(">/eli/id/2020/03/17/20G00034/sg<"));
        assertFalse(html.contains(">http://www.gazzettaufficiale.it/eli/tables/resource-type#DECRETO-LEGGE<"));
        // The full IRI is kept in the tooltip.
        assertTrue(html.contains("title=\"https://example.it/eli/id/2020/03/17/20G00034/sg\""));
    }

    @Test
    void showsVocabularyTermsByShortName() {
        String html = page.page("SELECT ?p WHERE { ?r <http://example.org/italian-legislation/ontology#relationProperty> ?p }",
                null).getBody();
        assertTrue(html.contains(">eli:amends</span>"));
    }

    @Test
    void reportsInvalidQueries() {
        assertTrue(page.page("SELEC nothing", null).getBody().contains("not valid SPARQL"));
    }

    @Test
    void offersTheExampleQuestionsByTitle() {
        String html = page.page(null, null).getBody();
        assertTrue(html.contains("href=\"/query?example=03-amends\""));
        assertTrue(html.contains("Acts that amend other acts"), "the title comes from the file's first comment");
        assertTrue(html.contains("Run query"));
        assertFalse(html.contains("Result <span"), "nothing runs until a question is chosen");
    }

    @Test
    void runsAChosenExampleAndShowsItsDescription() {
        String html = page.page(null, "08-decree-law-status").getBody();
        assertTrue(html.contains("aria-current=\"page\">8 &middot; Decree-laws by conversion status"));
        assertTrue(html.contains("latest check of each"), "the description is shown above the query");
        assertTrue(html.contains("CONVERTED"));
    }

    @Test
    void examplesInItalian() {
        LocaleContextHolder.setLocale(Locale.ITALIAN);
        try {
            String html = page.page(null, "08-decree-law-status").getBody();
            assertTrue(html.contains("Decreti-legge per stato della conversione"), "the it: comment line is the title");
            assertTrue(html.contains(">Esegui la query<"));
            assertTrue(html.contains(">Risultato <span"));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void examplesHaveATitleAndOnlyTheNumberedFilesAreListed() {
        var examples = page.examples();
        assertEquals(8, examples.size(), "queries/more is not listed");
        examples.values().forEach(e -> {
            assertFalse(e.titleEn().startsWith("PREFIX"), e.name() + " has no title comment");
            assertFalse(e.titleIt().isEmpty(), e.name() + " has no Italian title (# it: …)");
        });
    }
}
