package it.legislation.crawler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TsvModParser {

    public List<CleanModificationRecord> parse(Path tsvPath) throws IOException {
        List<String> lines = Files.readAllLines(tsvPath, StandardCharsets.UTF_8);
        Set<CleanModificationRecord> records = new LinkedHashSet<>();

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }

            String[] columns = line.split("\t", -1);
            if (columns.length < 3) {
                continue;
            }

            records.add(CleanModificationRecord.of(columns[0], columns[1], columns[2]));
        }

        return new ArrayList<>(records);
    }
}
