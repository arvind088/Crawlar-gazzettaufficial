package it.legislation.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FR-1.3: no update may be lost because a run was delayed or failed.
 */
class IngestionWatermarkTest {

    private static final String SOURCE = "normattiva";
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 9, 4, 6, 45, 0, 0, ZoneOffset.UTC);

    @Test
    void theFirstRunUsesTheFallbackLookback(@TempDir Path dir) throws IOException {
        IngestionWatermark watermark = new IngestionWatermark(dir.resolve("watermarks.tsv"));

        IngestionWatermark.Window window =
                watermark.windowFor(SOURCE, NOW, Duration.ofDays(7));

        assertEquals(NOW.minusDays(7), window.start());
        assertEquals(NOW, window.end());
        assertFalse(window.resumed());
    }

    @Test
    void aLaterRunResumesFromTheLastSuccessfulEnd(@TempDir Path dir) throws IOException {
        IngestionWatermark watermark = new IngestionWatermark(
                dir.resolve("watermarks.tsv"), Duration.ZERO, Duration.ofDays(90));

        IngestionWatermark.Window first = watermark.windowFor(SOURCE, NOW, Duration.ofDays(7));
        watermark.recordSuccess(SOURCE, first, NOW, "12 updates");

        OffsetDateTime tomorrow = NOW.plusDays(1);
        IngestionWatermark.Window second =
                watermark.windowFor(SOURCE, tomorrow, Duration.ofDays(7));

        assertEquals(NOW, second.start(), "the second run must start where the first ended");
        assertTrue(second.resumed());
    }

    /**
     * The case a rolling window cannot handle: the job is down for longer than
     * the lookback. Resuming from the cursor still covers the whole outage.
     */
    @Test
    void anOutageLongerThanTheLookbackIsStillCovered(@TempDir Path dir) throws IOException {
        IngestionWatermark watermark = new IngestionWatermark(
                dir.resolve("watermarks.tsv"), Duration.ZERO, Duration.ofDays(90));

        IngestionWatermark.Window first = watermark.windowFor(SOURCE, NOW, Duration.ofDays(7));
        watermark.recordSuccess(SOURCE, first, NOW, "");

        OffsetDateTime afterOutage = NOW.plusDays(30);
        IngestionWatermark.Window resumed =
                watermark.windowFor(SOURCE, afterOutage, Duration.ofDays(7));

        assertEquals(NOW, resumed.start());
        assertEquals(30, Duration.between(resumed.start(), resumed.end()).toDays(),
                "the whole 30-day gap must be requested, not just the last 7 days");
    }

    @Test
    void aFailedRunLeavesTheCursorWhereItWas(@TempDir Path dir) throws IOException {
        IngestionWatermark watermark = new IngestionWatermark(
                dir.resolve("watermarks.tsv"), Duration.ZERO, Duration.ofDays(90));

        IngestionWatermark.Window first = watermark.windowFor(SOURCE, NOW, Duration.ofDays(7));
        watermark.recordSuccess(SOURCE, first, NOW, "");

        OffsetDateTime later = NOW.plusDays(1);
        IngestionWatermark.Window attempt = watermark.windowFor(SOURCE, later, Duration.ofDays(7));
        watermark.recordFailure(SOURCE, attempt, later, "HTTP 409");

        IngestionWatermark.Window retry =
                watermark.windowFor(SOURCE, later.plusHours(1), Duration.ofDays(7));

        assertEquals(NOW, retry.start(),
                "a failed run must not advance the cursor past data it never fetched");
    }

    @Test
    void anOverlapIsSubtractedSoNothingFallsBetweenTwoWindows(@TempDir Path dir) throws IOException {
        IngestionWatermark watermark = new IngestionWatermark(
                dir.resolve("watermarks.tsv"), Duration.ofHours(1), Duration.ofDays(90));

        IngestionWatermark.Window first = watermark.windowFor(SOURCE, NOW, Duration.ofDays(7));
        watermark.recordSuccess(SOURCE, first, NOW, "");

        IngestionWatermark.Window second =
                watermark.windowFor(SOURCE, NOW.plusDays(1), Duration.ofDays(7));

        assertEquals(NOW.minusHours(1), second.start());
    }

    @Test
    void aVeryOldCursorIsCappedAtTheMaximumSpan(@TempDir Path dir) throws IOException {
        IngestionWatermark watermark = new IngestionWatermark(
                dir.resolve("watermarks.tsv"), Duration.ZERO, Duration.ofDays(90));

        IngestionWatermark.Window first = watermark.windowFor(SOURCE, NOW, Duration.ofDays(7));
        watermark.recordSuccess(SOURCE, first, NOW, "");

        OffsetDateTime muchLater = NOW.plusDays(400);
        IngestionWatermark.Window capped =
                watermark.windowFor(SOURCE, muchLater, Duration.ofDays(7));

        assertEquals(muchLater.minusDays(90), capped.start());
        assertTrue(capped.truncated());
    }

    @Test
    void theCursorSurvivesANewInstance(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("watermarks.tsv");

        IngestionWatermark first = new IngestionWatermark(file, Duration.ZERO, Duration.ofDays(90));
        first.recordSuccess(SOURCE, first.windowFor(SOURCE, NOW, Duration.ofDays(7)), NOW, "");

        IngestionWatermark reopened = new IngestionWatermark(file, Duration.ZERO, Duration.ofDays(90));

        assertEquals(NOW, reopened.lastSuccessfulEnd(SOURCE).orElseThrow());
    }
}
