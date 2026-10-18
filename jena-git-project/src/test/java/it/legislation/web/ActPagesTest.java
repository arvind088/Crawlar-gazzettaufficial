package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import it.legislation.eli.EliUriService;
import it.legislation.store.Tdb2DatasetService;

/** The Legal acts and Act details pages, on a trimmed copy of real data. */
class ActPagesTest {

    private static final String DL_18 = "https://example.it/eli/id/2020/03/17/20G00034/sg";
    private static final String L_27 = "https://example.it/eli/id/2020/04/29/20G00045/sg";

    private final Tdb2DatasetService store =
            Tdb2DatasetService.inMemoryForRdfPaths(List.of(Path.of("src/test/resources/web/acts.ttl")));
    private final ActCatalog catalog = new ActCatalog(store);
    private final ActPages pages = new ActPages(catalog, new EliUriService("https://example.it"));

    @Test
    void listsImportedActsNewestFirstWithLatestStatuses() throws IOException {
        List<ActCatalog.ActRow> acts = catalog.acts();

        // The external act (outside the corpus) is not listed.
        assertEquals(2, acts.size());
        assertEquals("20G00045", acts.get(0).localId());
        ActCatalog.ActRow decree = acts.get(1);
        assertEquals("D.L. 17 marzo 2020, n. 18", decree.citation());
        assertEquals(2, decree.versions());
        assertEquals("CONVERTED", decree.conversionStatus());
        assertEquals("MATCH", decree.gazzettaStatus(), "the later check wins over the earlier ERROR");
    }

    @Test
    void listPageFiltersByTypeAndSearch() throws IOException {
        String all = pages.list(null, null, null, null, 1).getBody();
        assertTrue(all.contains("2 acts"));
        assertTrue(all.contains("href=\"/eli/id/2020/03/17/20G00034/sg\""), "links stay on this host");

        String laws = pages.list(null, "LEGGE", null, null, 1).getBody();
        assertTrue(laws.contains("1 act<"));
        assertTrue(laws.contains(">L. 24 aprile 2020, n. 27</a>"));
        assertFalse(laws.contains(">D.L. 17 marzo 2020, n. 18</a>"));
        assertTrue(laws.contains("<input type=\"hidden\" name=\"type\" value=\"LEGGE\">"),
                "a new search keeps the chosen filters");

        String search = pages.list("sanitario", null, null, null, 1).getBody();
        assertTrue(search.contains(">D.L. 17 marzo 2020, n. 18</a>"));
        assertFalse(search.contains(">L. 24 aprile 2020, n. 27</a>"));
    }

    @Test
    void listPageShowsTheCorpusInNumbers() throws IOException {
        String html = pages.list(null, null, null, null, 1).getBody();
        assertTrue(html.contains("<span class=\"k\">Acts</span><span class=\"v\">2</span>"));
        assertTrue(html.contains("<span class=\"k\">Confirmed by Gazzetta</span><span class=\"v good\">1</span>"),
                "only the decree has a Gazzetta check, and its latest is MATCH");
        assertTrue(html.contains("amends 1 &middot; changes 1"));
        assertTrue(html.contains("1 converted"));
        assertTrue(html.contains("Gazzetta &#10003;"));
        assertTrue(html.contains("href=\"/acts.csv\""));
    }

    @Test
    void listDownloadsAsCsvWithTheSameFilters() throws IOException {
        String csv = pages.csv(null, "DECRETO-LEGGE", null, null).getBody();
        String[] lines = csv.split("\r\n");
        assertEquals(2, lines.length, "header and one decree-law");
        assertTrue(lines[0].startsWith("eli,code,type,date,number,citation,title"));
        assertTrue(lines[1].contains("20G00034,DECRETO-LEGGE,2020-03-17,18,\"D.L. 17 marzo 2020, n. 18\""),
                "a value with a comma is quoted");
        assertTrue(lines[1].endsWith(",CONVERTED,MATCH"));
    }

    @Test
    void decreePageShowsConversionChecksVersionsAndRelations() throws IOException {
        ActCatalog.ActDetails act = catalog.details(DL_18).orElseThrow();
        assertEquals("CONVERTED", act.conversion().orElseThrow().value("conversionStatus"));
        assertEquals(2, act.gazzettaHistory().size());
        assertEquals("MATCH", act.gazzetta().orElseThrow().value("gazzettaCheck"));
        assertEquals("/original", act.versions().get(0).uri().substring(act.versions().get(0).uri().lastIndexOf('/')));

        ActCatalog.Relation amended = act.relations().stream().filter(r -> !r.outgoing()).findFirst().orElseThrow();
        assertEquals("amends", amended.propertyName());
        assertEquals(L_27, amended.other().uri());
        assertEquals("A", amended.latest().value("trustLevel"), "the latest assessment is shown");

        String html = pages.details(act, "/eli/id/2020/03/17/20G00034/sg");
        assertTrue(html.contains("Conversion into law"));
        assertTrue(html.contains("Converted by <a href=\"/eli/id/2020/04/29/20G00045/sg\">L. 24 aprile 2020, n. 27</a>"),
                "the answer comes first");
        assertTrue(html.contains("43 of 60"));
        assertTrue(html.contains("<details><summary>How was this verified?</summary>"),
                "the checks of a clean conversion are one click away");
        assertTrue(html.contains("The law came within the 60 days"));
        assertTrue(html.contains("<span class=\"trust a\""));
        assertTrue(html.contains("<span class=\"rel amends\">amended by</span>"));
        assertTrue(html.contains("Original text"));
        assertTrue(html.contains("amended by"));
        assertTrue(html.contains("D.Lgs. 30 marzo 2001, n. 165"));
        assertTrue(html.contains("outside the imported corpus"));
        assertTrue(html.contains("History (2 assessments)"));
        assertTrue(html.contains("?format=ttl"));
    }

    @Test
    void lawPageListsTheDecreeItConverts() throws IOException {
        ActCatalog.ActDetails law = catalog.details(L_27).orElseThrow();
        assertEquals(1, law.converts().size());
        assertEquals(DL_18, law.converts().get(0).decree().uri());

        String html = pages.details(law, "/eli/id/2020/04/29/20G00045/sg");
        assertTrue(html.contains("Decree-laws this law converts"));
        assertTrue(html.contains("D.L. 17 marzo 2020, n. 18"));
    }

    @Test
    void unknownActHasNoDetails() throws IOException {
        assertTrue(catalog.details("https://example.it/eli/id/1999/01/01/X/sg").isEmpty());
        assertFalse(catalog.isAct("https://example.it/eli/ext/decreto_legislativo/2001-03-30/165"));
        assertTrue(catalog.isAct(DL_18));
    }

    @Test
    void citationsAndDates() {
        assertEquals("D.Lgs. 30 giugno 2003, n. 196", Html.citation("DECRETO_LEGISLATIVO", "2003-06-30", "196"));
        assertEquals("1 gennaio 2024", Html.italianDate("2024-01-01"));
        assertEquals("&lt;b&gt;", Html.escape("<b>"));
    }
}
