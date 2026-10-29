package it.legislation.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import it.legislation.ingest.GazzettaDiscoveryRunner;

/**
 * The Gazzetta Ufficiale RSS discovery ({@code data/clean/gazzetta_discovery.tsv}): the laws,
 * decree-laws and legislative decrees the Gazzetta has published, and whether Normattiva
 * has them yet. The About &amp; Help page lists the ones still waiting.
 */
@Component
public class DiscoveryReport {

    /** One act announced by the Gazzetta RSS feed. */
    public record Row(String codice, String guDate, String type, String number, String dateOfAct, String status,
                      String url) {
        public boolean waiting() {
            return "WAITING".equals(status);
        }
    }

    private final Path file;
    private FileTime loadedAt;
    private List<Row> rows = List.of();

    public DiscoveryReport() {
        this(GazzettaDiscoveryRunner.DEFAULT_OUT);
    }

    public DiscoveryReport(Path file) {
        this.file = file;
    }

    /** Every act the feed announced, newest first. */
    public synchronized List<Row> all() {
        try {
            if (!Files.exists(file)) {
                return List.of();
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

    /** The acts published in the Gazzetta and not yet in Normattiva. */
    public List<Row> waiting() {
        return all().stream().filter(Row::waiting).toList();
    }

    private static List<Row> read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return List.of();
        }
        List<String> header = List.of(lines.get(0).split("\t", -1));
        List<Row> read = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] c = line.split("\t", -1);
            read.add(new Row(cell(header, c, "codice"), cell(header, c, "gu_date"), cell(header, c, "type"),
                    cell(header, c, "number"), cell(header, c, "date_of_act"), cell(header, c, "status"),
                    cell(header, c, "gazzetta_url")));
        }
        return List.copyOf(read);
    }

    private static String cell(List<String> header, String[] cells, String column) {
        int i = header.indexOf(column);
        return i < 0 || i >= cells.length ? "" : cells[i];
    }
}
