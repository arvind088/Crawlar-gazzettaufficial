package it.legislation.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GazzettaArchiveDiscoveryRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesIssueLinksFromYearArchive() {
        String html = """
                <a href="/gazzetta/serie_generale/caricaDettaglio?dataPubblicazioneGazzetta=2026-06-15&amp;numeroGazzetta=136">n. 136</a>
                <a href="/gazzetta/serie_generale/caricaDettaglio?dataPubblicazioneGazzetta=2026-06-16&amp;numeroGazzetta=137">n. 137</a>
                """;

        List<GazzettaArchiveDiscoveryRunner.IssueLink> links =
                GazzettaArchiveDiscoveryRunner.parseIssueLinks(html);

        assertEquals(2, links.size());
        assertEquals(LocalDate.parse("2026-06-15"), links.get(0).publicationDate());
        assertEquals("136", links.get(0).issueNumber());
        assertEquals(
                "https://www.gazzettaufficiale.it/gazzetta/serie_generale/caricaDettaglio?dataPubblicazioneGazzetta=2026-06-15&numeroGazzetta=136",
                links.get(0).url()
        );
    }

    @Test
    void parsesActLinksFromIssueDetail() {
        GazzettaArchiveDiscoveryRunner.IssueLink issue = new GazzettaArchiveDiscoveryRunner.IssueLink(
                LocalDate.parse("2026-06-15"),
                "136",
                "https://www.gazzettaufficiale.it/example"
        );
        String html = """
                <a href="/atto/serie_generale/caricaDettaglioAtto/originario?atto.codiceRedazionale=26A02811&amp;atto.dataPubblicazioneGazzetta=2026-06-15&amp;elenco30giorni=false">DECRETO</a>
                <a href="/atto/serie_generale/caricaDettaglioAtto/originario?atto.codiceRedazionale=26A02812&amp;atto.dataPubblicazioneGazzetta=2026-06-15&amp;elenco30giorni=false">DECRETO</a>
                """;

        List<GazzettaArchiveDiscoveryRunner.ArchiveActLink> links =
                GazzettaArchiveDiscoveryRunner.parseActLinks(issue, html);

        assertEquals(2, links.size());
        assertEquals("26A02811", links.get(0).localId());
        assertEquals("136", links.get(0).issueNumber());
        assertTrue(links.get(0).actUrl().contains("codiceRedazionale=26A02811"));
    }

    @Test
    void writesArchiveLinksAsTsv() throws Exception {
        Path output = tempDir.resolve("gazzetta_archive_links.tsv");
        OffsetDateTime discoveredAt = OffsetDateTime.parse("2026-06-17T12:00:00+02:00");

        GazzettaArchiveDiscoveryRunner.writeLinks(List.of(
                new GazzettaArchiveDiscoveryRunner.ArchiveActLink(
                        LocalDate.parse("2026-06-15"),
                        "136",
                        "26A02811",
                        "https://www.gazzettaufficiale.it/atto/example"
                )
        ), output, discoveredAt);

        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertEquals("publication_date\tissue_number\tlocal_id\tact_url\tdiscovered_at", lines.get(0));
        assertTrue(lines.get(1).contains("2026-06-15\t136\t26A02811"));
    }
}
