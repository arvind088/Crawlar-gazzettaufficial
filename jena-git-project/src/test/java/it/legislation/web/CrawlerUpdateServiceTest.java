package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import it.legislation.crawler.CrawlRegistry;
import it.legislation.crawler.GazzettaRssUpdateRunner;

class CrawlerUpdateServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void runsUpdateCheckOnlyForRssLinksMissingFromRegistry() throws Exception {
        Path registry = tempDir.resolve("crawl_registry.tsv");
        Path rssUpdates = tempDir.resolve("gazzetta_rss_updates.tsv");
        Path rdfDelta = tempDir.resolve("gazzetta_metadata_delta.ttl");
        Path rawDir = Files.createDirectories(tempDir.resolve("raw"));
        List<List<String>> crawledLinks = new ArrayList<>();

        Files.writeString(registry, CrawlRegistry.headerLine() + System.lineSeparator()
                + String.join("\t",
                        "http://www.gazzettaufficiale.it/eli/id/2026/06/12/26G00117/sg",
                        "gazzetta",
                        "https://www.gazzettaufficiale.it/eli/id/2026/06/12/26G00117/SG",
                        "2026-06-12",
                        "26G00117",
                        "hash",
                        "2026-06-13T10:00:00+02:00",
                        "2026-06-13T10:00:00+02:00",
                        "2026-06-13T10:00:00+02:00",
                        "NEW",
                        ""
                ) + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(rdfDelta, "@prefix eli: <http://data.europa.eu/eli/ontology#> .", StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("act-1.html"), "<html></html>", StandardCharsets.UTF_8);

        CrawlerStatusService statusService = new CrawlerStatusService(registry, rssUpdates, rdfDelta, rawDir);
        CrawlerUpdateService service = new CrawlerUpdateService(
                statusService,
                (rssUrl, maxEntries) -> List.of(
                        new GazzettaRssUpdateRunner.RssEntry(
                                "Known",
                                "https://www.gazzettaufficiale.it/eli/id/2026/06/12/26G00117/SG",
                                "published",
                                ""
                        ),
                        new GazzettaRssUpdateRunner.RssEntry(
                                "New one",
                                "https://www.gazzettaufficiale.it/eli/id/2026/06/15/26A02932/SG",
                                "published",
                                ""
                        ),
                        new GazzettaRssUpdateRunner.RssEntry(
                                "New two",
                                "https://www.gazzettaufficiale.it/eli/id/2026/06/15/26A02948/SG",
                                "published",
                                ""
                        )
                ),
                links -> {
                    crawledLinks.add(List.copyOf(links));
                    return links.size();
                },
                rssUpdates,
                registry
        );

        CrawlerUpdateResult result = service.runUpdate(10, 0);

        assertEquals("COMPLETED", result.state());
        assertEquals(3, result.rssEntriesRead());
        assertEquals(3, result.rssEntriesAdded());
        assertEquals(3, result.linksAvailable());
        assertEquals(2, result.linksCrawled());
        assertEquals(2, result.changedRecords());
        assertEquals(List.of(
                "https://www.gazzettaufficiale.it/eli/id/2026/06/15/26A02932/SG",
                "https://www.gazzettaufficiale.it/eli/id/2026/06/15/26A02948/SG"
        ), crawledLinks.get(0));
        assertEquals(3, result.crawlerStatus().rssLinkCount());
        assertFalse(result.crawlerStatus().loadedFiles().isEmpty());
        assertEquals(result, service.lastResult());
    }
}
