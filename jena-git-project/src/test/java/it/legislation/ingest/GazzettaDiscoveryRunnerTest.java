package it.legislation.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Gazzetta RSS feed drives the act-page crawler and lists the acts Normattiva does not have yet. */
class GazzettaDiscoveryRunnerTest {

    @TempDir
    Path temp;

    private static final String RSS = "title\tlink\tpublished\tdescription\tfetchDate\n"
            + "LEGGE 19 giugno 2026, n.103\thttp://www.gazzettaufficiale.it/eli/id/2026/06/20/26G00123/SG\tSat\t\tx\n"
            + "DECRETO-LEGGE 28 agosto 2026, n. 154\thttp://www.gazzettaufficiale.it/eli/id/2026/08/28/26G00174/SG\tFri\t\tx\n"
            + "DECRETO DEL PRESIDENTE DELLA REPUBBLICA 7 maggio 2026\thttp://www.gazzettaufficiale.it/eli/id/2026/06/20/26A03072/SG\tSat\t\tx\n"
            + "AGENZIA ITALIANA DEL FARMACO - COMUNICATO\thttp://www.gazzettaufficiale.it/eli/id/2026/10/27/26A05614/SG\tTue\t\tx\n"
            + "LEGGE 19 giugno 2026, n.103\thttp://www.gazzettaufficiale.it/eli/id/2026/06/20/26G00123/SG\tSat\t\ty\n";

    /** Layer 1 holds only the law: Normattiva has not delivered the decree-law yet. */
    private static final String LAYER1 = """
            @prefix eli: <http://data.europa.eu/eli/ontology#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix rt:  <http://www.gazzettaufficiale.it/eli/tables/resource-type#> .
            <http://www.gazzettaufficiale.it/eli/id/2026/06/20/26G00123/sg> a eli:LegalResource ;
                eli:id_local "26G00123" ; eli:number "103" ; eli:type_document rt:LEGGE ;
                eli:date_document "2026-06-19"^^xsd:date ; eli:date_publication "2026-06-20"^^xsd:date .
            """;

    @Test
    void readsNewActPagesAndListsWhatNormattivaDoesNotHaveYet() throws IOException {
        Path rss = temp.resolve("rss.tsv");
        Files.writeString(rss, RSS);
        Path layer1 = temp.resolve("layer1.ttl");
        Files.writeString(layer1, LAYER1);
        Path out = temp.resolve("discovery.tsv");
        GazzettaCheckRunnerTest.FakeFetcher fetcher = new GazzettaCheckRunnerTest.FakeFetcher();
        List<String> log = new ArrayList<>();

        GazzettaDiscoveryRunner.Summary first = new GazzettaDiscoveryRunner(fetcher, temp.resolve("cache"), false)
                .run(rss, layer1, out, log::add);

        assertEquals(2, first.acts().size(), "only laws, decree-laws and legislative decrees, each once");
        assertEquals(2, first.fetched());
        assertEquals(1, first.waiting());
        GazzettaDiscoveryRunner.Found decree = first.acts().stream().filter(f -> f.codice().equals("26G00174")).findFirst()
                .orElseThrow();
        assertEquals("WAITING", decree.status());
        assertEquals("read", decree.page());
        assertEquals("DECRETO-LEGGE", decree.type());
        assertTrue(log.get(0).startsWith("WAITING  26G00174"));
        GazzettaDiscoveryRunner.Found law = first.acts().stream().filter(f -> f.codice().equals("26G00123")).findFirst()
                .orElseThrow();
        assertEquals("IN_NORMATTIVA", law.status());
        assertEquals("103", law.number(), "number from the page heading");
        assertEquals("141", law.guNumber());
        assertTrue(Files.exists(temp.resolve("cache/2026_06_20_26G00123_sg.html")), "saved where the Gazzetta check reads");

        // A later run without the saved pages (the scheduled job on GitHub keeps no HTML) reads nothing again.
        GazzettaCheckRunnerTest.FakeFetcher again = new GazzettaCheckRunnerTest.FakeFetcher();
        GazzettaDiscoveryRunner.Summary second = new GazzettaDiscoveryRunner(again, temp.resolve("empty-cache"), false)
                .run(rss, layer1, out, line -> { });
        assertEquals(0, second.fetched());
        assertEquals("141", second.acts().stream().filter(f -> f.codice().equals("26G00123")).findFirst()
                .orElseThrow().guNumber(), "details kept in the report");
    }

    @Test
    void recognisesTitlesAndLinks() {
        assertEquals("DECRETO_LEGISLATIVO", GazzettaDiscoveryRunner.announced("DECRETO LEGISLATIVO 3 marzo 2026, n. 21",
                "http://www.gazzettaufficiale.it/eli/id/2026/03/10/26G00040/SG").orElseThrow().type());
        assertEquals("2026-10-01", GazzettaDiscoveryRunner.announced("DECRETO-LEGGE 1° ottobre 2026, n. 171",
                "http://www.gazzettaufficiale.it/eli/id/2026/10/01/26G00190/SG").orElseThrow().dateOfAct().toString());
        assertTrue(GazzettaDiscoveryRunner.announced("LEGGE 13 ottobre 2026, n. 180",
                "http://www.gazzettaufficiale.it/eli/id/2026/10/23/26A05611/SG").isEmpty(), "a notice about a law, not a law");
        assertTrue(GazzettaDiscoveryRunner.announced("DECRETO 12 ottobre 2026",
                "http://www.gazzettaufficiale.it/eli/id/2026/10/20/26A05500/SG").isEmpty());
    }
}
