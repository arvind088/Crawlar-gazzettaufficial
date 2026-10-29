package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.i18n.LocaleContextHolder;

import it.legislation.eli.EliUriService;
import it.legislation.store.Tdb2DatasetService;

/** The Legal acts and Act details pages, on a trimmed copy of real data. */
class ActPagesTest {

    private static final String DL_18 = "http://www.gazzettaufficiale.it/eli/id/2020/03/17/20G00034/sg";
    private static final String L_27 = "http://www.gazzettaufficiale.it/eli/id/2020/04/29/20G00045/sg";

    private final Tdb2DatasetService store =
            Tdb2DatasetService.inMemoryForRdfPaths(List.of(Path.of("src/test/resources/web/acts.ttl")));
    private final ActCatalog catalog = new ActCatalog(store);
    private static final Path NONE = Path.of("target", "no-such-report.tsv");
    private final ActPages pages = new ActPages(catalog, new EliUriService("https://example.it"),
            new DataFreshness(NONE), new GazzettaReport(NONE), new RelationReport(NONE));

    @Test
    void listsImportedActsNewestFirstWithConversionStatus() throws IOException {
        List<ActCatalog.ActRow> acts = catalog.acts();

        // The external act (outside the corpus) is not listed.
        assertEquals(2, acts.size());
        assertEquals("20G00045", acts.get(0).localId());
        ActCatalog.ActRow decree = acts.get(1);
        assertEquals("D.L. 17 marzo 2020, n. 18", decree.citation());
        assertEquals(2, decree.versions());
        assertEquals("CONVERTED", decree.conversionStatus(), "L. 27/2020's title says it converts it");
        assertEquals(null, acts.get(0).conversionStatus(), "only decree-laws have one");
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
        assertTrue(html.contains("<span class=\"k\">Versions</span><span class=\"v\">3</span>"));
        assertTrue(html.contains("amends 1 &middot; changes 1"));
        assertTrue(html.contains("1 converted · 0 not converted · 0 pending"));
        assertFalse(html.contains("Gazzetta &#10003;"), "no Gazzetta badge: the check is a report");
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
        assertTrue(lines[1].endsWith(",CONVERTED"));
    }

    @Test
    void decreePageShowsConversionVersionsAndRelations() throws IOException {
        ActCatalog.ActDetails act = catalog.details(DL_18).orElseThrow();
        ActCatalog.Conversion conversion = act.conversion().orElseThrow();
        assertEquals("CONVERTED", conversion.status());
        assertEquals(L_27, conversion.law().uri());
        assertEquals(43L, conversion.days());
        assertEquals("/original", act.versions().get(0).uri().substring(act.versions().get(0).uri().lastIndexOf('/')));

        ActCatalog.Relation amended = act.relations().stream().filter(r -> !r.outgoing()).findFirst().orElseThrow();
        assertEquals("converts", amended.propertyName(), "the converting law's relation is shown as a conversion");
        assertEquals(L_27, amended.other().uri());

        String html = pages.details(act, "/eli/id/2020/03/17/20G00034/sg");
        assertTrue(html.contains("Conversion into law"));
        assertTrue(html.contains("Converted by <a href=\"/eli/id/2020/04/29/20G00045/sg\">L. 24 aprile 2020, n. 27</a>"),
                "the answer comes first");
        assertTrue(html.contains("43 of 60"));
        assertFalse(html.contains("How was this verified?"), "no C1-C6 checks any more");
        assertFalse(html.contains("<span class=\"trust"), "no trust levels any more");
        assertTrue(html.contains("<span class=\"rel converts\">converts into law</span>"));
        assertTrue(html.contains("href=\"https://www.gazzettaufficiale.it/eli/id/2020/03/17/20G00034/sg\""),
                "the Gazzetta Ufficiale page: the act's own identifier");
        assertTrue(html.contains("<code class=\"mono\">http://www.gazzettaufficiale.it/eli/id/2020/03/17/20G00034/sg</code>"),
                "the ELI shown is the official one");
        assertTrue(html.contains("Original text"));
        assertTrue(html.contains("changed by <a href=\"/eli/id/2020/04/29/20G00045/sg\">L. 24 aprile 2020, n. 27</a>"),
                "each version names the act that produced it");
        assertEquals(1, act.versions().get(1).changedBy().size());
        assertTrue(html.contains("D.Lgs. 30 marzo 2001, n. 165"));
        assertTrue(html.contains("<td>D.Lgs. 30 marzo 2001, n. 165</td>"), "an act outside the corpus: its citation, no link");
        assertTrue(html.contains("?format=ttl"));
    }

    @Test
    void lawPageListsTheDecreeItConverts() throws IOException {
        ActCatalog.ActDetails law = catalog.details(L_27).orElseThrow();
        assertEquals(1, law.converts().size());
        assertEquals(DL_18, law.converts().get(0).decree().uri());
        assertEquals(43L, law.converts().get(0).days());

        String html = pages.details(law, "/eli/id/2020/04/29/20G00045/sg");
        assertTrue(html.contains("Decree-laws this law converts"));
        assertTrue(html.contains("D.L. 17 marzo 2020, n. 18"));
    }

    @Test
    void unknownActHasNoDetails() throws IOException {
        assertTrue(catalog.details("http://www.gazzettaufficiale.it/eli/id/1999/01/01/X/sg").isEmpty());
        assertTrue(catalog.isAct("https://example.it/eli/ext/decreto_legislativo/2001-03-30/165"),
                "an act outside the corpus is an eli:LegalResource too");
        assertTrue(catalog.isAct(DL_18));
    }

    @Test
    void pagesInItalian() throws IOException {
        LocaleContextHolder.setLocale(Locale.ITALIAN);
        try {
            String list = pages.list(null, null, null, null, 1).getBody();
            assertTrue(list.contains("<html lang=\"it\">"));
            assertTrue(list.contains(">Cerca un atto<"));
            assertTrue(list.contains(">Atti normativi<"), "the menu");
            assertTrue(list.contains("2 atti"));
            assertTrue(list.contains(">Convertito<"), "statuses are translated");
            assertTrue(list.contains("aria-current=\"true\">Italiano"), "the language switch shows the current language");

            String details = pages.details(catalog.details(DL_18).orElseThrow(), "/eli/id/2020/03/17/20G00034/sg");
            assertTrue(details.contains("Convertito da <a href=\"/eli/id/2020/04/29/20G00045/sg\">"));
            assertTrue(details.contains("43 giorni"));
            assertTrue(details.contains("modificato da"));
            assertTrue(details.contains("D.L. 17 marzo 2020, n. 18"), "citations are the same in both languages");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
        assertTrue(pages.list(null, null, null, null, 1).getBody().contains(">Find an act<"), "English by default");
    }

    @Test
    void languageSwitchKeepsThePage() {
        assertEquals("?lang=it", Html.currentUrlWithLang("it"), "outside a request only the parameter");
        assertEquals("Converted", Html.statusWords("CONVERTED"));
        assertEquals("Something new", Html.statusWords("SOMETHING_NEW"), "unknown statuses still read well");
    }

    @Test
    void bothLanguagesHaveTheSameTexts() throws IOException {
        java.util.Properties en = new java.util.Properties();
        java.util.Properties it = new java.util.Properties();
        try (var a = getClass().getResourceAsStream("/i18n/messages_en.properties");
             var b = getClass().getResourceAsStream("/i18n/messages_it.properties")) {
            en.load(new java.io.InputStreamReader(a, java.nio.charset.StandardCharsets.UTF_8));
            it.load(new java.io.InputStreamReader(b, java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(en.keySet(), it.keySet(), "every English text has an Italian one and vice versa");
    }

    @Test
    void citationsAndDates() {
        assertEquals("D.Lgs. 30 giugno 2003, n. 196", Html.citation("DECRETO_LEGISLATIVO", "2003-06-30", "196"));
        assertEquals("1 gennaio 2024", Html.italianDate("2024-01-01"));
        assertEquals("&lt;b&gt;", Html.escape("<b>"));
    }

    @Test
    void numbersSayTheyCoverTheWholeDatasetAndHowRecentItIs(@TempDir Path dir) throws IOException {
        Path watermarks = dir.resolve("ingestion_watermarks.tsv");
        Files.writeString(watermarks, "source\tlastSuccessfulEnd\tlastRunStartedAt\tlastRunFinishedAt\tlastRunState\tnote\n"
                + "normattiva-daily\t2026-10-28T21:44:04Z\t2026-10-21T13:55:55Z\t2026-10-28T22:58:06Z\tSUCCESS\t\n");
        ActPages fresh = new ActPages(catalog, new EliUriService("https://example.it"), new DataFreshness(watermarks),
                new GazzettaReport(NONE), new RelationReport(NONE));

        String html = fresh.list("sanitario", null, null, null, 1).getBody();
        assertTrue(html.contains("<p class=\"scope muted\">Whole dataset &middot; data updated to 28 October 2026"
                + " &middot; acts from 2020</p>"), "searching does not change the numbers, so they say so");

        String never = new ActPages(catalog, new EliUriService("https://example.it"),
                new DataFreshness(dir.resolve("missing.tsv")), new GazzettaReport(NONE), new RelationReport(NONE)).list(null, null, null, null, 1).getBody();
        assertTrue(never.contains("<p class=\"scope muted\">Whole dataset &middot; acts from 2020</p>"),
                "no date when the daily update has never run");
    }

    @Test
    void versionsAreListedNewestFirstWithTheOriginalOneClickAway() throws IOException {
        ActCatalog.ActDetails act = catalog.details(DL_18).orElseThrow();
        String html = pages.details(act, "/eli/id/2020/03/17/20G00034/sg");
        assertTrue(html.contains("newest first"));
        assertTrue(html.indexOf("<b>30 aprile 2020</b>") < html.indexOf("<b>17 marzo 2020</b>"),
                "the text in force comes before the original");
        assertTrue(html.contains("newest first &middot; <a href=\"/eli/id/2020/03/17/20G00034/sg/"),
                "a direct link to the original text in the heading");
    }

    private static final String GAZZETTA_CHECK = "codice\tgu_date\ttype\tnumber\tdate_of_act\tstatus\tdifferences\t"
            + "title_differs\tgu_number\tentry_into_force\tgazzetta_url\n"
            + "20G00034\t2020-03-17\tDECRETO-LEGGE\t18\t2020-03-17\tMATCH\t\t\t70\t2020-03-17\t"
            + "https://www.gazzettaufficiale.it/eli/id/2020/03/17/20G00034/sg\n"
            + "20G00045\t2020-04-29\tLEGGE\t27\t2020-04-24\tERROR\tnot in cache (offline run)\t\t\t\t"
            + "https://www.gazzettaufficiale.it/eli/id/2020/04/29/20G00045/sg\n";

    @Test
    void homepageSaysWhatThePortalIsAndHowToSearch() throws IOException {
        String html = pages.list(null, null, null, null, 1).getBody();
        assertTrue(html.contains("<h1>Explore Italian legislation and its connections</h1>"));
        assertTrue(html.contains("<a href=\"/about\">How it works</a>"));
        assertTrue(html.contains("aria-describedby=\"search-hint\""));
        assertTrue(html.contains("“COVID”, “18/2020” or “20G00034”"));
        assertTrue(html.contains("<a href=\"/about\">About &amp; Help</a>"), "in the menu and the footer");
        assertTrue(html.contains("<a href=\"/about#glossary\">Glossary</a>"));
        assertTrue(html.contains("<a href=\"/about#sources\">Data sources</a>"));
        assertTrue(html.contains("<a href=\"/about#coverage\">Data updates</a>"));
    }

    @Test
    void aNumberFindsTheActWithThatNumberNotEveryTitleContainingIt() throws IOException {
        assertTrue(pages.list("18/2020", null, null, null, 1).getBody().contains("1 act<"));
        assertTrue(pages.list("18", null, null, null, 1).getBody().contains(">D.L. 17 marzo 2020, n. 18</a>"));
        assertFalse(pages.list("18", null, null, null, 1).getBody().contains(">L. 24 aprile 2020, n. 27</a>"),
                "L. 27 has 18 in neither its number nor its year");
        assertTrue(pages.list("n. 27", null, null, null, 1).getBody().contains(">L. 24 aprile 2020, n. 27</a>"));
        assertTrue(pages.list("2020", null, null, null, 1).getBody().contains("2 acts"), "a year finds that year's acts");
        assertTrue(pages.list("18/2019", null, null, null, 1).getBody().contains("No act matches"));
    }

    @Test
    void actPageExplainsTermsWhereTheyAppear() throws IOException {
        String html = pages.details(catalog.details(DL_18).orElseThrow(), "/eli/id/2020/03/17/20G00034/sg");
        for (String term : List.of("g-eli", "g-version", "g-relations", "g-decree-law", "g-gazzetta")) {
            assertTrue(html.contains("<a class=\"q\" href=\"/about#" + term + "\""), term);
        }
        assertTrue(html.contains("Each version is the text in force from a date."));
        assertTrue(html.contains("Not checked yet."), "no report: says so rather than claiming a check");
    }

    @Test
    void actPageSaysWhatTheGazzettaCheckFound(@TempDir Path dir) throws IOException {
        Path report = dir.resolve("gazzetta_check.tsv");
        Files.writeString(report, GAZZETTA_CHECK);
        ActPages checked = new ActPages(catalog, new EliUriService("https://example.it"), new DataFreshness(NONE),
                new GazzettaReport(report), new RelationReport(NONE));

        String decree = checked.details(catalog.details(DL_18).orElseThrow(), "/eli/id/2020/03/17/20G00034/sg");
        assertTrue(decree.contains("<b>Confirmed.</b> Type, number, date of the act and publication date agree"));
        assertTrue(decree.contains("Published in GU n. 70 of 17 marzo 2020"));

        String law = checked.details(catalog.details(L_27).orElseThrow(), "/eli/id/2020/04/29/20G00045/sg");
        assertTrue(law.contains("<b>Not confirmed.</b> The Gazzetta page could not be read"));
    }

    @Test
    void aboutPageAnswersTheFiveQuestionsFromTheData(@TempDir Path dir) throws IOException {
        Path report = dir.resolve("gazzetta_check.tsv");
        Files.writeString(report, GAZZETTA_CHECK);
        Path watermarks = dir.resolve("ingestion_watermarks.tsv");
        Files.writeString(watermarks, "source\tlastSuccessfulEnd\tlastRunStartedAt\tlastRunFinishedAt\tlastRunState\tnote\n"
                + "normattiva-daily\t2026-10-28T21:44:04Z\t2026-10-21T13:55:55Z\t2026-10-28T22:58:06Z\tSUCCESS\t"
                + "0 downloaded, 29 without text, 0 failed\n");
        Path discovery = dir.resolve("gazzetta_discovery.tsv");
        Files.writeString(discovery, "codice\tgu_date\ttype\tnumber\tdate_of_act\ttitle\tstatus\tpage\tgu_number"
                + "\tentry_into_force\tgazzetta_url\n"
                + "26G00188\t2026-09-29\tDECRETO-LEGGE\t168\t2026-09-29\tDECRETO-LEGGE 29 settembre 2026, n. 168\tWAITING\tread"
                + "\t227\t2026-09-30\thttps://www.gazzettaufficiale.it/eli/id/2026/09/29/26G00188/sg\n"
                + "20G00034\t2020-03-17\tDECRETO-LEGGE\t18\t2020-03-17\tDECRETO-LEGGE 17 marzo 2020, n. 18\tIN_NORMATTIVA"
                + "\tread\t70\t2020-03-17\thttps://www.gazzettaufficiale.it/eli/id/2020/03/17/20G00034/sg\n");
        String html = new AboutPage(catalog, new DataFreshness(watermarks), new GazzettaReport(report),
                new DiscoveryReport(discovery)).about().getBody();

        for (String id : List.of("what", "sources", "use", "relations", "coverage", "glossary")) {
            assertTrue(html.contains("id=\"" + id + "\""), id);
        }
        for (String term : AboutPage.TERMS) {
            assertTrue(html.contains("<dt id=\"g-" + term + "\">"), term);
        }
        assertTrue(html.contains("aria-current=\"page\">About &amp; Help</a>"));
        assertTrue(html.contains("2 acts dated from 2020 to 2020, with 3 versions"));
        assertTrue(html.contains("updated to 28 October 2026"));
        assertTrue(html.contains("29 recent acts are listed by Normattiva but sent without text"));
        assertTrue(html.contains("1 of 2 acts are confirmed by their Gazzetta Ufficiale page"));
        assertTrue(html.contains("L. 24 aprile 2020, n. 27"), "the act not confirmed is named");
        assertTrue(html.contains("Repeals always means the whole act."));
        assertTrue(html.contains("1 of the 2 laws, decree-laws and legislative decrees announced by the Gazzetta Ufficiale RSS feed"));
        assertTrue(html.contains("D.L. 29 settembre 2026, n. 168"), "the act waiting for Normattiva is named");
        assertFalse(html.toLowerCase(Locale.ROOT).contains("universit"), "presented as a sample, not a university project");
    }

    @Test
    void relationsSayWhoChangedWhomAndWhen(@TempDir Path dir) throws IOException {
        Path relations = dir.resolve("relations.tsv");
        Files.writeString(relations, "from_codice\tfrom\tproperty\tto_codice\tto\tsources\n");
        Files.writeString(dir.resolve("version_history.tsv"), "act_codice\tact\tversion_date\tchanged_by_codice\tchanged_by"
                + "\tevent_date\n"
                + "20G00034\tDECRETO-LEGGE 2020-03-17 n. 18\t2020-04-30\t20G00045\tLEGGE 2020-04-24 n. 27\t2020-04-29\n");
        ActPages pagesWithDates = new ActPages(catalog, new EliUriService("https://example.it"), new DataFreshness(NONE),
                new GazzettaReport(NONE), new RelationReport(relations));

        String decree = pagesWithDates.details(catalog.details(DL_18).orElseThrow(), "/eli/id/2020/03/17/20G00034/sg");
        assertTrue(decree.contains("<th>Modifying act</th><th>Relation</th><th>Changed act</th><th>Date</th>"));
        int changedBy = decree.indexOf("Changed by other acts");
        String row = decree.substring(decree.indexOf("<tbody>", changedBy), decree.indexOf("</tbody>", changedBy));
        assertTrue(row.indexOf("L. 24 aprile 2020, n. 27") < row.indexOf("converts into law"), "the modifying act first");
        assertTrue(row.indexOf("converts into law") < row.indexOf("<b>D.L. 17 marzo 2020, n. 18</b>"), "then this act");
        assertTrue(row.contains("<td class=\"nowrap\">2020-04-29</td>"), "when the change was published");
        assertFalse(row.contains("Conversione in legge"), "citations only, no titles");

        int changes = decree.indexOf("What this act changes");
        String out = decree.substring(decree.indexOf("<tbody>", changes), decree.indexOf("</tbody>", changes));
        assertTrue(out.indexOf("<b>D.L. 17 marzo 2020, n. 18</b>") < out.indexOf("D.Lgs. 30 marzo 2001, n. 165"),
                "this act first, then the act it changes");
        assertTrue(out.contains("<td class=\"nowrap\">2020-03-17</td>"), "no history line: the modifying act's date");
    }
}
