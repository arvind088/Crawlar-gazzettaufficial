package it.legislation.crawler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A persisted cursor marking how far each source has been ingested (FR-1.3).
 *
 * <p>Without this, the Normattiva job asked for a fixed rolling window —
 * {@code now − lookbackDays} — on every run. If the application was down for
 * longer than that window, the updates published in the gap were never requested
 * again and were lost silently. The requirement is explicit that a delayed or
 * failed run must not lose data, which a rolling window cannot guarantee.
 *
 * <p>The watermark advances only after a run succeeds. A failed run therefore
 * leaves it where it was, and the next run re-requests the same period. Combined
 * with additive ingestion, re-requesting is harmless: the same statements simply
 * arrive twice and the second time change nothing.
 *
 * <p>Two safety rails on the computed window:
 * <ul>
 *   <li>an <em>overlap</em> is subtracted, so an act published in the same second
 *       the previous run ended cannot fall between two windows;</li>
 *   <li>a <em>maximum span</em> caps the first run, or a run after a long outage,
 *       so it does not attempt to fetch years of history in one request.</li>
 * </ul>
 */
public class IngestionWatermark {

    public static final Path DEFAULT_PATH = Path.of("data", "registry", "ingestion_watermarks.tsv");

    private static final List<String> HEADER = List.of(
            "source",
            "lastSuccessfulEnd",
            "lastRunStartedAt",
            "lastRunFinishedAt",
            "lastRunState",
            "note"
    );

    private static final Duration DEFAULT_OVERLAP = Duration.ofHours(1);
    private static final Duration DEFAULT_MAX_SPAN = Duration.ofDays(90);

    private final Path path;
    private final Duration overlap;
    private final Duration maxSpan;

    public IngestionWatermark(Path path) {
        this(path, DEFAULT_OVERLAP, DEFAULT_MAX_SPAN);
    }

    public IngestionWatermark(Path path, Duration overlap, Duration maxSpan) {
        this.path = path;
        this.overlap = overlap;
        this.maxSpan = maxSpan;
    }

    /**
     * The window a run should request: from the last successful end (less the
     * overlap) up to {@code now}. Falls back to {@code fallbackLookback} when the
     * source has never completed a run.
     */
    public Window windowFor(String source, OffsetDateTime now, Duration fallbackLookback) throws IOException {
        Optional<OffsetDateTime> last = lastSuccessfulEnd(source);

        OffsetDateTime start = last
                .map(end -> end.minus(overlap))
                .orElseGet(() -> now.minus(fallbackLookback));

        OffsetDateTime earliestAllowed = now.minus(maxSpan);
        boolean truncated = start.isBefore(earliestAllowed);
        if (truncated) {
            start = earliestAllowed;
        }
        if (start.isAfter(now)) {
            start = now;
        }

        return new Window(start, now, last.isPresent(), truncated);
    }

    public Optional<OffsetDateTime> lastSuccessfulEnd(String source) throws IOException {
        Entry entry = read().get(source);
        if (entry == null || entry.lastSuccessfulEnd.isBlank()) {
            return Optional.empty();
        }
        return parse(entry.lastSuccessfulEnd);
    }

    /** Advances the cursor. Call only after the run has actually succeeded. */
    public void recordSuccess(String source, Window window, OffsetDateTime finishedAt, String note)
            throws IOException {
        write(source, new Entry(
                source,
                window.end().toString(),
                window.start().toString(),
                finishedAt.toString(),
                "SUCCESS",
                note == null ? "" : note
        ));
    }

    /**
     * Records the attempt without moving the cursor, so the next run re-requests
     * the same period.
     */
    public void recordFailure(String source, Window window, OffsetDateTime finishedAt, String reason)
            throws IOException {
        Entry previous = read().get(source);
        write(source, new Entry(
                source,
                previous == null ? "" : previous.lastSuccessfulEnd,
                window.start().toString(),
                finishedAt.toString(),
                "FAILED",
                reason == null ? "" : reason
        ));
    }

    public Path getPath() {
        return path;
    }

    Map<String, Entry> read() throws IOException {
        Map<String, Entry> entries = new LinkedHashMap<>();
        if (!Files.exists(path)) {
            return entries;
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            Entry entry = Entry.fromTsv(line);
            entries.put(entry.source, entry);
        }
        return entries;
    }

    private synchronized void write(String source, Entry entry) throws IOException {
        Map<String, Entry> entries = read();
        entries.put(source, entry);

        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        List<String> lines = new ArrayList<>();
        lines.add(String.join("\t", HEADER));
        for (Entry value : entries.values()) {
            lines.add(value.toTsv());
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static Optional<OffsetDateTime> parse(String value) {
        try {
            return Optional.of(OffsetDateTime.parse(value));
        } catch (DateTimeParseException exception) {
            return Optional.empty();
        }
    }

    /** The period a run should request, and how it was arrived at. */
    public record Window(OffsetDateTime start, OffsetDateTime end, boolean resumed, boolean truncated) {

        public String describe() {
            if (!resumed) {
                return "first run for this source; using the fallback lookback";
            }
            if (truncated) {
                return "resumed from the last successful run, capped at the maximum span";
            }
            return "resumed from the last successful run";
        }
    }

    record Entry(
            String source,
            String lastSuccessfulEnd,
            String lastRunStartedAt,
            String lastRunFinishedAt,
            String lastRunState,
            String note
    ) {
        static Entry fromTsv(String line) {
            String[] values = line.split("\t", -1);
            return new Entry(
                    at(values, 0),
                    at(values, 1),
                    at(values, 2),
                    at(values, 3),
                    at(values, 4),
                    at(values, 5)
            );
        }

        String toTsv() {
            return String.join("\t",
                    clean(source),
                    clean(lastSuccessfulEnd),
                    clean(lastRunStartedAt),
                    clean(lastRunFinishedAt),
                    clean(lastRunState),
                    clean(note));
        }

        private static String at(String[] values, int index) {
            return index < values.length ? values[index] : "";
        }

        private static String clean(String value) {
            if (value == null) {
                return "";
            }
            return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
        }
    }

    /** Convenience for callers that only have a UTC clock. */
    public static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
