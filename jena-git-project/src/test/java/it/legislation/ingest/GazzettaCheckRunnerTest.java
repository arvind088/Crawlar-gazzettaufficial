package it.legislation.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import it.legislation.source.gazzetta.PageFetcher;

class GazzettaCheckRunnerTest {

    @TempDir
    Path temp;

    private static final String ACTS = """
            @prefix eli: <http://data.europa.eu/eli/ontology#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix rt:  <http://www.gazzettaufficiale.it/eli/tables/resource-type#> .

            <https://example.test/eli/id/2026/06/20/26G00123/sg> a eli:LegalResource ;
                eli:id_local "26G00123" ; eli:number "103" ; eli:type_document rt:LEGGE ;
                eli:date_document "2026-06-19"^^xsd:date ; eli:date_publication "2026-06-20"^^xsd:date ;
                eli:title "Conversione in legge del decreto-legge 24 aprile 2026, n. 55, recante disposizioni urgenti in materia di rimpatri volontari assistiti."@it ;
                owl:sameAs <http://www.gazzettaufficiale.it/eli/id/2026/06/20/26G00123/sg> .

            <https://example.test/eli/id/2026/08/28/26G00174/sg> a eli:LegalResource ;
                eli:id_local "26G00174" ; eli:number "999" ; eli:type_document rt:DECRETO-LEGGE ;
                eli:date_document "2026-08-28"^^xsd:date ; eli:date_publication "2026-08-28"^^xsd:date ;
                eli:title "Misure urgenti"@it ;
                owl:sameAs <http://www.gazzettaufficiale.it/eli/id/2026/08/28/26G00174/sg> .

            <https://example.test/eli/id/2026/09/01/26G99999/sg> a eli:LegalResource ;
                eli:id_local "26G99999" ; eli:number "1" ; eli:type_document rt:LEGGE ;
                eli:date_document "2026-09-01"^^xsd:date ; eli:date_publication "2026-09-01"^^xsd:date ;
                eli:title "Atto inesistente"@it ;
                owl:sameAs <http://www.gazzettaufficiale.it/eli/id/2026/09/01/26G99999/sg> .
            """;

    /** Serves the two test pages; anything else gets an empty page, as Gazzetta does for unknown acts. */
    static class FakeFetcher implements PageFetcher {
        final List<String> urls = new ArrayList<>();
        final Map<String, String> pages;

        FakeFetcher() throws IOException {
            pages = Map.of(
                    "https://www.gazzettaufficiale.it/eli/id/2026/06/20/26G00123/sg",
                    Files.readString(Path.of("src/test/resources/gazzetta/legge-2026-103.html")),
                    "https://www.gazzettaufficiale.it/eli/id/2026/08/28/26G00174/sg",
                    Files.readString(Path.of("src/test/resources/gazzetta/decreto-legge-2026-154.html")));
        }

        @Override
        public Page fetch(String url) {
            urls.add(url);
            return new Page(200, pages.getOrDefault(url, "<html><body>Nessun atto</body></html>"));
        }
    }

    @Test
    void confirmsMatchingActsFlagsDifferencesAndUsesCache() throws IOException {
        Path in = temp.resolve("acts.ttl");
        Files.writeString(in, ACTS);
        Path out = temp.resolve("check.ttl");
        Path report = temp.resolve("check.tsv");
        Path cache = temp.resolve("cache");
        FakeFetcher fetcher = new FakeFetcher();
        GazzettaCheckRunner runner = new GazzettaCheckRunner(fetcher, cache, false);

        GazzettaCheckRunner.Summary first = runner.run(in, out, report, Integer.MAX_VALUE, line -> {});

        assertEquals(3, first.checks().size());
        assertEquals(1, first.counts().get(GazzettaCheckRunner.Status.MATCH));
        assertEquals(1, first.counts().get(GazzettaCheckRunner.Status.MISMATCH));
        assertEquals(1, first.counts().get(GazzettaCheckRunner.Status.NOT_FOUND));
        GazzettaCheckRunner.Check law = first.checks().get(0);
        assertEquals("26G00123", law.act().codice());
        assertFalse(law.titleDiffers(), "same title with different spacing must count as equal");
        GazzettaCheckRunner.Check decree = first.checks().get(1);
        assertEquals(List.of("number: 999 ≠ 154"), decree.differences());
        assertTrue(decree.titleDiffers());
        assertTrue(first.written());

        Model rdf = RDFDataMgr.loadModel(out.toString());
        String ttl = Files.readString(out);
        assertTrue(ttl.contains("\"MATCH\""), ttl);
        assertTrue(ttl.contains("\"NOT_FOUND\""), ttl);
        assertTrue(ttl.contains("2026-06-21"), "entry into force of the confirmed act: " + ttl);
        assertTrue(ttl.contains("eli/gu/2026/06/20/141/sg"), ttl);
        assertFalse(ttl.contains("2026-08-29"), "no Gazzetta facts are added to an act that does not match");
        assertTrue(rdf.size() > 5);
        assertTrue(Files.readString(report).contains("26G00174\t2026-08-28\tDECRETO-LEGGE\t999\t2026-08-28\tMISMATCH\tnumber: 999 ≠ 154"));

        assertTrue(Files.exists(cache.resolve("2026_06_20_26G00123_sg.html")));
        assertFalse(Files.exists(cache.resolve("2026_09_01_26G99999_sg.html")), "empty pages are not cached");

        GazzettaCheckRunner.Summary second = runner.run(in, out, report, Integer.MAX_VALUE, line -> {});
        assertEquals(2, second.cached());
        assertEquals(1, second.fetched(), "only the unknown act is asked again");
        assertFalse(second.written(), "same result must not rewrite the files");
    }

    @Test
    void offlineRunUsesOnlyTheCache() throws IOException {
        Path in = temp.resolve("acts.ttl");
        Files.writeString(in, ACTS);
        FakeFetcher fetcher = new FakeFetcher();
        GazzettaCheckRunner.Summary summary = new GazzettaCheckRunner(fetcher, temp.resolve("empty-cache"), true)
                .run(in, temp.resolve("o.ttl"), temp.resolve("o.tsv"), Integer.MAX_VALUE, line -> {});

        assertEquals(0, fetcher.urls.size());
        assertEquals(3, summary.counts().get(GazzettaCheckRunner.Status.ERROR));
    }
}
