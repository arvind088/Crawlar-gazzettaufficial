package it.legislation.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GazzettaScraperTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsCleanRecordFromEmbeddedEliMetadata() {
        Document doc = Jsoup.parse("""
                <html>
                  <head>
                    <meta about="gu:id/2025/01/02/24A06897/sg" typeof="eli:LegalResource">
                    <meta about="gu:id/2025/01/02/24A06897/sg" property="eli:title" content="  Riclassificazione   del medicinale  ">
                    <meta about="gu:id/2025/01/02/24A06897/sg" property="eli:date_publication" content="2025-01-02">
                    <meta about="gu:id/2025/01/02/24A06897/sg" property="eli:date_document" content="2024-12-09">
                    <meta about="gu:id/2025/01/02/24A06897/sg" property="eli:type_document" resource="gu:tables/resource-type#DETERMINA">
                    <meta about="gu:id/2025/01/02/24A06897/sg" property="eli:id_local" content="24A06897">
                    <meta about="gu:id/2025/01/02/24A06897/sg" property="eli:is_realized_by" resource="gu:id/2025/01/02/24A06897/sg/ita">
                    <meta about="gu:id/2025/01/02/24A06897/sg/ita" property="eli:is_embodied_by" resource="gu:id/2025/01/02/24A06897/sg/ita/html">
                    <meta about="gu:id/2025/01/02/24A06897/sg" property="eli:version" resource="gu:tables/versions#ORIGINAL">
                    <meta about="gu:id/2025/01/02/24A06897/sg/ita/html" property="eli:format" content="text/html">
                    <meta about="gu:id/2025/01/02/24A06897/sg/ita" property="eli:language" resource="http://publications.europa.eu/resource/authority/language/ITA">
                    <meta about="gu:id/2025/01/02/24A06897/sg/ita" property="eli:publisher" resource="http://www.ipzs.it">
                  </head>
                </html>
                """);

        CleanLegalActRecord record = GazzettaScraper
                .extractCleanLegalActRecord(doc, "https://www.gazzettaufficiale.it/atto/example")
                .orElseThrow();

        assertEquals("http://www.gazzettaufficiale.it/eli/id/2025/01/02/24A06897/sg", record.getEliUri());
        assertEquals("Riclassificazione del medicinale", record.getTitle().orElseThrow());
        assertEquals(LocalDate.of(2025, 1, 2), record.getPublicationDate().orElseThrow());
        assertEquals("http://www.gazzettaufficiale.it/eli/tables/resource-type#DETERMINA", record.getDocumentTypeUri().orElseThrow());
        assertEquals("http://www.gazzettaufficiale.it/eli/id/2025/01/02/24A06897/sg/ita", record.getRealizedByUri().orElseThrow());
        assertEquals("http://www.iana.org/assignments/media-types/text/html", record.getFormatUri().orElseThrow());
    }

    @Test
    void derivesCanonicalEliUriFromLongActUrlWhenAboutIsMissing() {
        Document doc = Jsoup.parse("""
                <html>
                  <head>
                    <meta property="eli:title" content="Example title">
                    <meta property="eli:date_publication" content="2025-01-07">
                    <meta property="eli:id_local" content="25A00056">
                  </head>
                </html>
                """);
        String sourceUrl = "https://www.gazzettaufficiale.it/atto/serie_generale/caricaDettaglioAtto/originario"
                + "?atto.dataPubblicazioneGazzetta=2025-01-07"
                + "&atto.codiceRedazionale=25A00056"
                + "&elenco30giorni=false";

        CleanLegalActRecord record = GazzettaScraper.extractCleanLegalActRecord(doc, sourceUrl).orElseThrow();

        assertEquals("http://www.gazzettaufficiale.it/eli/id/2025/01/07/25A00056/sg", record.getEliUri());
        assertTrue(record.getSourceUrl().isPresent());
    }

    @Test
    void createsStableHashAndCachePathForEliUri() {
        Path cachePath = GazzettaScraper.cachePathForEliUri(
                Path.of("data", "raw", "gazzetta"),
                "http://www.gazzettaufficiale.it/eli/id/2025/01/02/24A06897/sg"
        );

        assertEquals(Path.of("data", "raw", "gazzetta", "2025_01_02_24A06897_sg.html"), cachePath);
        assertEquals(GazzettaScraper.sha256("same content"), GazzettaScraper.sha256("same content"));
    }

    @Test
    void repairsCommonGazzettaQuoteMojibakeInTitles() {
        CleanLegalActRecord record = CleanLegalActRecord
                .builder("http://www.gazzettaufficiale.it/eli/id/2026/06/12/26A02945/sg")
                .title("Portale \u00C2\u00ABTrovaNormeFarmaco\u00C2\u00BB e \u0164test\u0165")
                .build();

        assertEquals("Portale \u00ABTrovaNormeFarmaco\u00BB e \u00ABtest\u00BB", record.getTitle().orElseThrow());
    }

    @Test
    void processesActHtmlThroughRegistryAndSkipsUnchanged() throws Exception {
        CrawlRegistry registry = new CrawlRegistry(tempDir.resolve("crawl_registry.tsv"));
        Path rawRoot = tempDir.resolve("raw");
        String sourceUrl = "https://www.gazzettaufficiale.it/eli/id/2025/01/02/24A06897/sg";
        String html = """
                <html>
                  <head>
                    <meta about="gu:id/2025/01/02/24A06897/sg" typeof="eli:LegalResource">
                    <meta about="gu:id/2025/01/02/24A06897/sg" property="eli:title" content="Riclassificazione del medicinale">
                    <meta about="gu:id/2025/01/02/24A06897/sg" property="eli:date_publication" content="2025-01-02">
                    <meta about="gu:id/2025/01/02/24A06897/sg" property="eli:id_local" content="24A06897">
                  </head>
                </html>
                """;

        assertTrue(GazzettaScraper.processActHtml(sourceUrl, html, registry, rawRoot).isPresent());
        assertTrue(GazzettaScraper.processActHtml(sourceUrl, html, registry, rawRoot).isEmpty());
        assertTrue(Files.exists(rawRoot.resolve("2025_01_02_24A06897_sg.html")));
        assertEquals(1, registry.countRecords());
    }
}
