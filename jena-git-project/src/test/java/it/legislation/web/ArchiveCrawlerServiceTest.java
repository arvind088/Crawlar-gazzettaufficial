package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import it.legislation.crawler.GazzettaArchiveDiscoveryRunner;

class ArchiveCrawlerServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversArchiveLinksAndWritesTsv() throws Exception {
        Path registry = tempDir.resolve("crawl_registry.tsv");
        Path rssUpdates = tempDir.resolve("gazzetta_rss_updates.tsv");
        Path rdfDelta = tempDir.resolve("gazzetta_metadata_delta.ttl");
        Path rawDir = Files.createDirectories(tempDir.resolve("raw"));
        Path archiveLinks = tempDir.resolve("archive_links.tsv");
        List<List<GazzettaArchiveDiscoveryRunner.ArchiveActLink>> writtenLinks = new ArrayList<>();

        Files.writeString(registry, "eliUri\tsource\tsourceUrl\tpublicationDate\tredactionalCode\tcontentHash\tfirstSeenAt\tlastCheckedAt\tlastChangedAt\tstatus\tlastError\n", StandardCharsets.UTF_8);
        Files.writeString(rssUpdates, "title\tlink\tpublished\tdescription\tfetchDate\n", StandardCharsets.UTF_8);
        Files.writeString(rdfDelta, "@prefix eli: <http://data.europa.eu/eli/ontology#> .", StandardCharsets.UTF_8);

        ArchiveCrawlerService service = new ArchiveCrawlerService(
                new CrawlerStatusService(registry, rssUpdates, rdfDelta, rawDir),
                (startDate, endDate) -> List.of(
                        new GazzettaArchiveDiscoveryRunner.ArchiveActLink(
                                LocalDate.parse("2026-06-15"),
                                "136",
                                "26A02811",
                                "https://www.gazzettaufficiale.it/atto/one"
                        )
                ),
                (links, output, discoveredAt) -> {
                    writtenLinks.add(List.copyOf(links));
                    Files.writeString(output, "publication_date\tissue_number\tlocal_id\tact_url\tdiscovered_at\n", StandardCharsets.UTF_8);
                },
                (input, limit) -> List.of(),
                links -> 0,
                archiveLinks,
                registry
        );

        ArchiveCrawlerResult result = service.discover(
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-16")
        );

        assertEquals("COMPLETED", result.state());
        assertEquals("discover", result.action());
        assertEquals(1, result.discoveredLinks());
        assertEquals(1, writtenLinks.get(0).size());
        assertEquals(result, service.lastResult());
    }

    @Test
    void crawlsSelectedArchiveLinks() throws Exception {
        Path registry = tempDir.resolve("crawl_registry.tsv");
        Path rssUpdates = tempDir.resolve("gazzetta_rss_updates.tsv");
        Path rdfDelta = tempDir.resolve("gazzetta_metadata_delta.ttl");
        Path rawDir = Files.createDirectories(tempDir.resolve("raw"));
        Path archiveLinks = tempDir.resolve("archive_links.tsv");
        List<List<String>> crawledLinks = new ArrayList<>();

        Files.writeString(registry, "eliUri\tsource\tsourceUrl\tpublicationDate\tredactionalCode\tcontentHash\tfirstSeenAt\tlastCheckedAt\tlastChangedAt\tstatus\tlastError\n", StandardCharsets.UTF_8);
        Files.writeString(rssUpdates, "title\tlink\tpublished\tdescription\tfetchDate\n", StandardCharsets.UTF_8);
        Files.writeString(rdfDelta, "@prefix eli: <http://data.europa.eu/eli/ontology#> .", StandardCharsets.UTF_8);

        ArchiveCrawlerService service = new ArchiveCrawlerService(
                new CrawlerStatusService(registry, rssUpdates, rdfDelta, rawDir),
                (startDate, endDate) -> List.of(),
                (links, output, discoveredAt) -> {},
                (input, limit) -> List.of(
                        "https://www.gazzettaufficiale.it/atto/one",
                        "https://www.gazzettaufficiale.it/atto/two"
                ),
                links -> {
                    crawledLinks.add(List.copyOf(links));
                    return links.size();
                },
                archiveLinks,
                registry
        );

        ArchiveCrawlerResult result = service.crawl(1);

        assertEquals("COMPLETED", result.state());
        assertEquals("crawl", result.action());
        assertEquals(2, result.linksAvailable());
        assertEquals(1, result.linksCrawled());
        assertEquals(1, result.changedRecords());
        assertEquals(List.of("https://www.gazzettaufficiale.it/atto/one"), crawledLinks.get(0));
    }

    @Test
    void skipsKnownArchiveLinksAndClampsLimitToAvailableLinks() throws Exception {
        Path registry = tempDir.resolve("crawl_registry.tsv");
        Path rssUpdates = tempDir.resolve("gazzetta_rss_updates.tsv");
        Path rdfDelta = tempDir.resolve("gazzetta_metadata_delta.ttl");
        Path rawDir = Files.createDirectories(tempDir.resolve("raw"));
        Path archiveLinks = tempDir.resolve("archive_links.tsv");
        List<List<String>> crawledLinks = new ArrayList<>();

        Files.writeString(registry, """
                eliUri	source	sourceUrl	publicationDate	redactionalCode	contentHash	firstSeenAt	lastCheckedAt	lastChangedAt	status	lastError
                http://www.gazzettaufficiale.it/eli/id/2026/06/16/26G00118/sg	gazzetta	source	2026-06-16	26G00118	hash	first	checked	changed	NEW	
                """, StandardCharsets.UTF_8);
        Files.writeString(rssUpdates, "title\tlink\tpublished\tdescription\tfetchDate\n", StandardCharsets.UTF_8);
        Files.writeString(rdfDelta, "@prefix eli: <http://data.europa.eu/eli/ontology#> .", StandardCharsets.UTF_8);

        ArchiveCrawlerService service = new ArchiveCrawlerService(
                new CrawlerStatusService(registry, rssUpdates, rdfDelta, rawDir),
                (startDate, endDate) -> List.of(),
                (links, output, discoveredAt) -> {},
                (input, limit) -> List.of(
                        "https://www.gazzettaufficiale.it/atto/serie_generale/caricaDettaglioAtto/originario?atto.dataPubblicazioneGazzetta=2026-06-16&atto.codiceRedazionale=26G00118&elenco30giorni=false",
                        "https://www.gazzettaufficiale.it/atto/serie_generale/caricaDettaglioAtto/originario?atto.dataPubblicazioneGazzetta=2026-06-16&atto.codiceRedazionale=26A02939&elenco30giorni=false"
                ),
                links -> {
                    crawledLinks.add(List.copyOf(links));
                    return links.size();
                },
                archiveLinks,
                registry
        );

        ArchiveCrawlerResult result = service.crawl(282);

        assertEquals(1, result.maxLinksToCrawl());
        assertEquals(1, result.linksAvailable());
        assertEquals(1, result.linksCrawled());
        assertEquals(List.of(
                "https://www.gazzettaufficiale.it/atto/serie_generale/caricaDettaglioAtto/originario?atto.dataPubblicazioneGazzetta=2026-06-16&atto.codiceRedazionale=26A02939&elenco30giorni=false"
        ), crawledLinks.get(0));
    }
}
