package it.legislation.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrawlRegistryTest {

    private static final String ACT_URI = "http://www.gazzettaufficiale.it/eli/id/2025/01/02/24A06897/sg";
    private static final String SOURCE_URL = "https://www.gazzettaufficiale.it/atto/example";

    @TempDir
    Path tempDir;

    @Test
    void upsertSuccessTracksNewUnchangedAndChangedRecords() throws Exception {
        CrawlRegistry registry = new CrawlRegistry(tempDir.resolve("crawl_registry.tsv"));
        CleanLegalActRecord record = CleanLegalActRecord.builder(ACT_URI)
                .publicationDate(LocalDate.of(2025, 1, 2))
                .localId("24A06897")
                .build();

        OffsetDateTime firstRun = OffsetDateTime.parse("2026-06-12T10:00:00Z");
        OffsetDateTime secondRun = OffsetDateTime.parse("2026-06-12T11:00:00Z");
        OffsetDateTime thirdRun = OffsetDateTime.parse("2026-06-12T12:00:00Z");

        assertEquals(CrawlRegistry.UpdateStatus.NEW, registry.upsertSuccess(record, SOURCE_URL, "hash-a", firstRun));
        assertEquals(1, registry.countRecords());

        assertEquals(CrawlRegistry.UpdateStatus.UNCHANGED, registry.upsertSuccess(record, SOURCE_URL, "hash-a", secondRun));
        CrawlRegistry.Entry unchanged = registry.findByEliUri(ACT_URI).orElseThrow();
        assertEquals("2026-06-12T10:00Z", unchanged.getFirstSeenAt());
        assertEquals("2026-06-12T10:00Z", unchanged.getLastChangedAt());
        assertEquals("UNCHANGED", unchanged.getStatus());

        assertEquals(CrawlRegistry.UpdateStatus.CHANGED, registry.upsertSuccess(record, SOURCE_URL, "hash-b", thirdRun));
        CrawlRegistry.Entry changed = registry.findByEliUri(ACT_URI).orElseThrow();
        assertEquals("hash-b", changed.getContentHash());
        assertEquals("CHANGED", changed.getStatus());
        assertEquals("2026-06-12T12:00Z", changed.getLastChangedAt());
    }

    @Test
    void createsRegistryHeaderWhenMissing() throws Exception {
        Path registryPath = tempDir.resolve("registry").resolve("crawl_registry.tsv");
        CrawlRegistry registry = new CrawlRegistry(registryPath);

        registry.ensureExists();

        assertTrue(registryPath.toFile().exists());
        assertEquals(0, registry.countRecords());
    }
}
