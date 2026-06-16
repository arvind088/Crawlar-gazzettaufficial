package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrawlerStatusServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void summarizesCrawlerOutputFiles() throws Exception {
        Path registry = tempDir.resolve("crawl_registry.tsv");
        Path rssUpdates = tempDir.resolve("gazzetta_rss_updates.tsv");
        Path rdfDelta = tempDir.resolve("gazzetta_metadata_delta.ttl");
        Path rawDir = Files.createDirectories(tempDir.resolve("raw"));

        Files.writeString(registry, """
                eliUri	source	sourceUrl	publicationDate	redactionalCode	contentHash	firstSeenAt	lastCheckedAt	lastChangedAt	status	lastError
                http://example.test/1	gazzetta	http://example.test/1	2026-06-12	26G00117	hash	2026-06-13T10:00:00+02:00	2026-06-13T10:00:00+02:00	2026-06-13T10:00:00+02:00	NEW	
                http://example.test/2	gazzetta	http://example.test/2	2026-06-13	26G00118	hash	2026-06-14T10:00:00+02:00	2026-06-14T10:00:00+02:00	2026-06-14T10:00:00+02:00	CHANGED	
                """, StandardCharsets.UTF_8);

        Files.writeString(rssUpdates, """
                title	link	published	description	fetchDate
                Act 1	http://example.test/1	Fri, 12 Jun 2026 18:25:16 GMT		2026-06-13T12:53:53+02:00
                Act 2	http://example.test/2	Fri, 12 Jun 2026 18:25:17 GMT		2026-06-14T12:53:53+02:00
                """, StandardCharsets.UTF_8);

        Files.writeString(rdfDelta, "@prefix eli: <http://data.europa.eu/eli/ontology#> .", StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("act-1.html"), "<html></html>", StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("act-2.html"), "<html></html>", StandardCharsets.UTF_8);

        CrawlerStatusService service = new CrawlerStatusService(registry, rssUpdates, rdfDelta, rawDir);

        CrawlerStatus status = service.status();

        assertEquals(2, status.registryRecords());
        assertEquals(1, status.registryStatusCounts().get("NEW"));
        assertEquals(1, status.registryStatusCounts().get("CHANGED"));
        assertEquals("2026-06-13", status.latestPublicationDate());
        assertEquals("2026-06-14T10:00:00+02:00", status.lastCheckedAt());
        assertEquals(2, status.rssLinkCount());
        assertEquals("2026-06-14T12:53:53+02:00", status.rssFetchDate());
        assertEquals(2, status.rawSnapshotCount());
        assertFalse(status.loadedFiles().isEmpty());
        assertEquals(0, status.missingFiles().size());
    }
}
