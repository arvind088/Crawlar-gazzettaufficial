package it.legislation.source.gazzetta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class GazzettaActPageTest {

    private static GazzettaActPage page(String file, String url) throws IOException {
        String html = Files.readString(Path.of("src/test/resources/gazzetta", file));
        return GazzettaActPage.parse(html, url).orElseThrow();
    }

    @Test
    void readsLawPage() throws IOException {
        GazzettaActPage p = page("legge-2026-103.html", "https://www.gazzettaufficiale.it/eli/id/2026/06/20/26G00123/sg");

        assertEquals("26G00123", p.codice());
        assertEquals(LocalDate.of(2026, 6, 20), p.publicationDate());
        assertEquals("LEGGE", p.typeCode());
        assertEquals(LocalDate.of(2026, 6, 19), p.documentDate());
        assertEquals("103", p.number());
        assertTrue(p.title().startsWith("Conversione in legge del decreto-legge 24 aprile 2026, n. 55"), p.title());
        assertTrue(p.title().endsWith("rimpatri volontari assistiti."), "codice must be removed: " + p.title());
        assertEquals("141", p.guNumber());
        assertEquals("http://www.gazzettaufficiale.it/eli/gu/2026/06/20/141/sg", p.guIssueUri());
        assertEquals(LocalDate.of(2026, 6, 21), p.entryIntoForce());
    }

    @Test
    void readsDecreeLawPage() throws IOException {
        GazzettaActPage p = page("decreto-legge-2026-154.html", "https://www.gazzettaufficiale.it/eli/id/2026/08/28/26G00174/sg");

        assertEquals("26G00174", p.codice());
        assertEquals("DECRETO-LEGGE", p.typeCode());
        assertEquals("154", p.number());
        assertEquals(LocalDate.of(2026, 8, 28), p.documentDate());
        assertEquals("199", p.guNumber());
        assertEquals(LocalDate.of(2026, 8, 29), p.entryIntoForce());
    }

    @Test
    void pageWithoutActIsEmpty() {
        assertTrue(GazzettaActPage.parse("<html><body><h1>Pagina non trovata</h1></body></html>", "https://x").isEmpty());
    }
}
