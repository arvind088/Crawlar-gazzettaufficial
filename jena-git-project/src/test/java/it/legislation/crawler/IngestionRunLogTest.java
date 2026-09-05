package it.legislation.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FR-1.5 and US-C2: what a run fetched, transformed and loaded must be
 * recoverable after a restart, and a partial failure must be distinguishable
 * from a total one.
 */
class IngestionRunLogTest {

    private static final OffsetDateTime START =
            OffsetDateTime.of(2026, 9, 4, 6, 15, 0, 0, ZoneOffset.UTC);

    @Test
    void aCompletedRunIsRecoverableFromDisk(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("runs.tsv");
        IngestionRunLog log = new IngestionRunLog(file);

        IngestionRunLog.Run run = IngestionRunLog.Run.started("gazzetta", "SCHEDULED", START);
        log.append(run.completed(START.plusMinutes(2), 20, 20, 4, 0, "", "", "Update check completed."));

        // A new instance stands in for a restarted process.
        List<IngestionRunLog.Run> recovered = new IngestionRunLog(file).recent(10);

        assertEquals(1, recovered.size());
        assertEquals("gazzetta", recovered.get(0).source());
        assertEquals("COMPLETED", recovered.get(0).state());
        assertEquals(20, recovered.get(0).itemsFetched());
        assertEquals(4, recovered.get(0).itemsLoaded());
    }

    @Test
    void aRunWithFailedItemsIsDistinguishableFromACleanOne(@TempDir Path dir) throws IOException {
        IngestionRunLog log = new IngestionRunLog(dir.resolve("runs.tsv"));

        IngestionRunLog.Run clean = IngestionRunLog.Run.started("gazzetta", "SCHEDULED", START)
                .completed(START.plusMinutes(1), 10, 10, 10, 0, "", "", "");
        IngestionRunLog.Run partial = IngestionRunLog.Run.started("gazzetta", "SCHEDULED", START)
                .completed(START.plusMinutes(1), 10, 8, 8, 2, "", "", "");

        assertEquals("COMPLETED", clean.state());
        assertEquals("COMPLETED_WITH_ERRORS", partial.state(),
                "a run that dropped items must not report itself as clean");
        assertNotEquals(clean.state(), partial.state());

        log.append(clean);
        log.append(partial);
        assertEquals(2, log.readAll().size());
    }

    @Test
    void aFailedRunKeepsTheCountsItHadReached(@TempDir Path dir) throws IOException {
        IngestionRunLog log = new IngestionRunLog(dir.resolve("runs.tsv"));

        IngestionRunLog.Run failed = IngestionRunLog.Run.started("normattiva", "SCHEDULED", START)
                .failed(START.plusSeconds(30), "Normattiva OpenData request failed with HTTP 409");
        log.append(failed);

        IngestionRunLog.Run stored = log.recent(1).get(0);
        assertEquals("FAILED", stored.state());
        assertTrue(stored.message().contains("409"),
                "the reason a run failed is the point of recording it");
    }

    @Test
    void theLogIsAppendOnlyAndNewestFirstWhenRead(@TempDir Path dir) throws IOException {
        IngestionRunLog log = new IngestionRunLog(dir.resolve("runs.tsv"));

        for (int day = 1; day <= 3; day++) {
            log.append(IngestionRunLog.Run
                    .started("gazzetta", "SCHEDULED", START.plusDays(day))
                    .completed(START.plusDays(day).plusMinutes(1), day, day, day, 0, "", "", "day " + day));
        }

        List<IngestionRunLog.Run> all = log.readAll();
        assertEquals(3, all.size(), "earlier runs must never be overwritten");
        assertEquals("day 1", all.get(0).message());

        List<IngestionRunLog.Run> recent = log.recent(2);
        assertEquals(2, recent.size());
        assertEquals("day 3", recent.get(0).message(), "most recent run first");
    }

    @Test
    void tabsAndNewlinesInAMessageCannotCorruptTheFile(@TempDir Path dir) throws IOException {
        IngestionRunLog log = new IngestionRunLog(dir.resolve("runs.tsv"));

        log.append(IngestionRunLog.Run.started("normattiva", "MANUAL", START)
                .failed(START.plusSeconds(5), "broke\there\nand here"));

        List<IngestionRunLog.Run> stored = log.recent(10);
        assertEquals(1, stored.size(), "a multi-line message must stay one row");
        assertTrue(stored.get(0).message().startsWith("broke here"));
    }

    @Test
    void readingAnAbsentLogReturnsNothingRatherThanFailing(@TempDir Path dir) throws IOException {
        assertTrue(new IngestionRunLog(dir.resolve("never-written.tsv")).recent(10).isEmpty());
    }
}
