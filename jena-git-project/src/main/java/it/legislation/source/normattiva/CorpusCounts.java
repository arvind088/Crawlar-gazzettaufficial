package it.legislation.source.normattiva;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How many acts Normattiva reports for each act type and year, as counted before
 * export. Stored in {@code corpus/counts.tsv}; the import compares its own totals
 * against these numbers, so a missing slice is visible.
 */
public final class CorpusCounts {

    public static final String FILE = "counts.tsv";
    private static final String HEADER = "act_type\tyear\tcount\tcounted_at";

    /** Key: act type + year. */
    public record Key(String actType, int year) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int byType = actType.compareTo(other.actType);
            return byType != 0 ? byType : Integer.compare(year, other.year);
        }
    }

    private final Map<Key, Integer> counts = new TreeMap<>();
    private final Map<Key, String> countedAt = new TreeMap<>();

    public static CorpusCounts read(Path file) throws IOException {
        CorpusCounts result = new CorpusCounts();
        if (!Files.exists(file)) {
            return result;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("act_type")) {
                continue;
            }
            String[] cells = line.split("\t", -1);
            Key key = new Key(cells[0], Integer.parseInt(cells[1]));
            result.counts.put(key, Integer.parseInt(cells[2]));
            result.countedAt.put(key, cells.length > 3 ? cells[3] : "");
        }
        return result;
    }

    public void put(String actType, int year, int count, String when) {
        Key key = new Key(actType, year);
        counts.put(key, count);
        countedAt.put(key, when);
    }

    public Map<Key, Integer> all() {
        return counts;
    }

    public void write(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        counts.forEach((key, count) -> lines.add(String.join("\t",
                key.actType(), Integer.toString(key.year()), Integer.toString(count), countedAt.getOrDefault(key, ""))));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
