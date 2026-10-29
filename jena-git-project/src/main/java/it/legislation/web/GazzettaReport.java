package it.legislation.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import it.legislation.ingest.GazzettaCheckRunner;

/**
 * The result of the Gazzetta Ufficiale check, one line per act, as the check wrote it
 * ({@code data/clean/gazzetta_check.tsv}). The RDF keeps only ELI facts; the pages read
 * the outcome of the check from this report, so they can say what was compared.
 */
@Component
public class GazzettaReport {

    /** One act: the outcome, what differs (or why it could not be read), and the GU issue. */
    public record Row(String codice, String type, String number, String dateOfAct, String guDate, String status,
                      String differences, String guNumber, String url) {
        public boolean matched() {
            return "MATCH".equals(status);
        }
    }

    private final Path file;
    private FileTime loadedAt;
    private Map<String, Row> rows = Map.of();

    public GazzettaReport() {
        this(GazzettaCheckRunner.DEFAULT_REPORT);
    }

    public GazzettaReport(Path file) {
        this.file = file;
    }

    /** The check of one act, by its redactional code (e.g. 20G00034). */
    public Optional<Row> forCode(String codice) {
        return codice == null ? Optional.empty() : Optional.ofNullable(rows().get(codice));
    }

    /** Every act checked, in the report's order. */
    public List<Row> all() {
        return new ArrayList<>(rows().values());
    }

    /** Re-read when the file changes, so a new check shows without a restart. */
    private synchronized Map<String, Row> rows() {
        try {
            if (!Files.exists(file)) {
                return Map.of();
            }
            FileTime modified = Files.getLastModifiedTime(file);
            if (!modified.equals(loadedAt)) {
                rows = read(file);
                loadedAt = modified;
            }
        } catch (IOException | RuntimeException e) {
            return rows;
        }
        return rows;
    }

    private static Map<String, Row> read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return Map.of();
        }
        List<String> header = List.of(lines.get(0).split("\t", -1));
        Map<String, Row> read = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] cells = line.split("\t", -1);
            Row row = new Row(cell(header, cells, "codice"), cell(header, cells, "type"), cell(header, cells, "number"),
                    cell(header, cells, "date_of_act"), cell(header, cells, "gu_date"), cell(header, cells, "status"),
                    cell(header, cells, "differences"), cell(header, cells, "gu_number"), cell(header, cells, "gazzetta_url"));
            read.put(row.codice(), row);
        }
        return Collections.unmodifiableMap(read);
    }

    private static String cell(List<String> header, String[] cells, String column) {
        int i = header.indexOf(column);
        return i < 0 || i >= cells.length ? "" : cells[i];
    }
}
