package it.legislation.crawler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * An append-only record of what each ingestion run fetched, transformed and
 * loaded (FR-1.5, US-C2).
 *
 * <p>Run outcomes were previously held in {@code volatile} fields on the update
 * services, so the entire history was lost on restart and the status view could
 * only ever say "not run yet in this session". An auditable pipeline needs the
 * record to outlive the process.
 *
 * <p>Failures are recorded per run <em>and</em> counted per item, so a run that
 * partially succeeded is distinguishable from one that failed outright — which
 * is what US-C2's "failed items are distinguishable from successful ones" asks
 * for.
 */
public class IngestionRunLog {

    public static final Path DEFAULT_PATH = Path.of("data", "registry", "ingestion_runs.tsv");

    private static final List<String> HEADER = List.of(
            "runId",
            "source",
            "trigger",
            "startedAt",
            "finishedAt",
            "state",
            "itemsFetched",
            "itemsTransformed",
            "itemsLoaded",
            "itemsFailed",
            "windowStart",
            "windowEnd",
            "message"
    );

    private final Path path;

    public IngestionRunLog() {
        this(DEFAULT_PATH);
    }

    public IngestionRunLog(Path path) {
        this.path = path;
    }

    public static String newRunId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Appends one run. Never rewrites or truncates earlier entries. */
    public synchronized void append(Run run) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        boolean fresh = !Files.exists(path) || Files.size(path) == 0;
        if (fresh) {
            Files.writeString(path, String.join("\t", HEADER) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        }
        Files.writeString(path, run.toTsv() + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /** Most recent runs first. */
    public List<Run> recent(int limit) throws IOException {
        List<Run> runs = readAll();
        Collections.reverse(runs);
        if (limit > 0 && runs.size() > limit) {
            return List.copyOf(runs.subList(0, limit));
        }
        return List.copyOf(runs);
    }

    public List<Run> readAll() throws IOException {
        List<Run> runs = new ArrayList<>();
        if (!Files.exists(path)) {
            return runs;
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            runs.add(Run.fromTsv(line));
        }
        return runs;
    }

    public Path getPath() {
        return path;
    }

    public record Run(
            String runId,
            String source,
            String trigger,
            String startedAt,
            String finishedAt,
            String state,
            int itemsFetched,
            int itemsTransformed,
            int itemsLoaded,
            int itemsFailed,
            String windowStart,
            String windowEnd,
            String message
    ) {
        public static Run started(String source, String trigger, OffsetDateTime startedAt) {
            return new Run(newRunId(), source, trigger, startedAt.toString(), "",
                    "RUNNING", 0, 0, 0, 0, "", "", "");
        }

        public Run completed(
                OffsetDateTime finishedAt,
                int fetched,
                int transformed,
                int loaded,
                int failed,
                String windowStart,
                String windowEnd,
                String message
        ) {
            return new Run(runId, source, trigger, startedAt, finishedAt.toString(),
                    failed > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED",
                    fetched, transformed, loaded, failed,
                    windowStart, windowEnd, message);
        }

        public Run failed(OffsetDateTime finishedAt, String message) {
            return new Run(runId, source, trigger, startedAt, finishedAt.toString(),
                    "FAILED", itemsFetched, itemsTransformed, itemsLoaded, itemsFailed,
                    windowStart, windowEnd, message);
        }

        static Run fromTsv(String line) {
            String[] v = line.split("\t", -1);
            return new Run(
                    at(v, 0), at(v, 1), at(v, 2), at(v, 3), at(v, 4), at(v, 5),
                    number(at(v, 6)), number(at(v, 7)), number(at(v, 8)), number(at(v, 9)),
                    at(v, 10), at(v, 11), at(v, 12)
            );
        }

        String toTsv() {
            return String.join("\t",
                    clean(runId), clean(source), clean(trigger), clean(startedAt),
                    clean(finishedAt), clean(state),
                    String.valueOf(itemsFetched), String.valueOf(itemsTransformed),
                    String.valueOf(itemsLoaded), String.valueOf(itemsFailed),
                    clean(windowStart), clean(windowEnd), clean(message));
        }

        private static String at(String[] values, int index) {
            return index < values.length ? values[index] : "";
        }

        private static int number(String value) {
            try {
                return value.isBlank() ? 0 : Integer.parseInt(value.trim());
            } catch (NumberFormatException exception) {
                return 0;
            }
        }

        private static String clean(String value) {
            if (value == null) {
                return "";
            }
            return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
        }
    }
}
