package it.legislation.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import it.legislation.ingest.RelationRunner;

/**
 * The evidence behind each relation, as the relation finder wrote it: how many
 * changes, of which kinds, Normattiva's editor's notes ({@code data/clean/relations.tsv}),
 * and the versions each change produced, with their dates
 * ({@code data/clean/version_history.tsv}).
 *
 * <p>The RDF keeps only the ELI link ({@code eli:amends}, {@code eli:changes}…); the act
 * pages read this report to say what was changed, when, and where the relation was found.
 * Acts are matched by their key, type + date + number, so acts outside the corpus match too.
 */
@Component
public class RelationReport {

    /** One relation: where it was found, how many changes of which kinds, and the editor's notes. */
    public record Link(String property, String sources, int changes, Map<String, Integer> kinds, List<String> notes) {}

    /** A version of the changed act that the change produced: in force from {@code versionDate}. */
    public record Change(String versionDate, String eventDate) {}

    private final Path relationsFile;
    private final Path versionsFile;
    private FileTime relationsLoaded;
    private FileTime versionsLoaded;
    private Map<String, Link> links = Map.of();
    private Map<String, List<Change>> changes = Map.of();

    public RelationReport() {
        this(RelationRunner.DEFAULT_RELATIONS);
    }

    /** {@code relationsFile}; the version history is read from {@code version_history.tsv} beside it. */
    public RelationReport(Path relationsFile) {
        this.relationsFile = relationsFile;
        this.versionsFile = relationsFile.resolveSibling("version_history.tsv");
    }

    /** The key used to match acts: {@code DECRETO_LEGGE 2020-12-18 n. 172}. */
    public static String key(String typeCode, String date, String number) {
        if (typeCode == null || date == null || number == null) {
            return "";
        }
        return typeCode.toUpperCase(Locale.ROOT).replace('-', '_') + " " + (date.length() > 10 ? date.substring(0, 10) : date)
                + " n. " + number;
    }

    /** The relation from one act to another, if the finder recorded one. */
    public synchronized Optional<Link> link(String fromKey, String toKey) {
        reload();
        return Optional.ofNullable(links.get(fromKey + "→" + toKey));
    }

    /** The versions of {@code actKey} that {@code changedByKey} produced, oldest first. */
    public synchronized List<Change> versions(String actKey, String changedByKey) {
        reload();
        return changes.getOrDefault(actKey + "←" + changedByKey, List.of());
    }

    private void reload() {
        try {
            if (Files.exists(relationsFile)) {
                FileTime modified = Files.getLastModifiedTime(relationsFile);
                if (!modified.equals(relationsLoaded)) {
                    links = readLinks(relationsFile);
                    relationsLoaded = modified;
                }
            }
            if (Files.exists(versionsFile)) {
                FileTime modified = Files.getLastModifiedTime(versionsFile);
                if (!modified.equals(versionsLoaded)) {
                    changes = readChanges(versionsFile);
                    versionsLoaded = modified;
                }
            }
        } catch (IOException | RuntimeException e) {
            // keep what was read last; the pages still show the relation itself
        }
    }

    private static Map<String, Link> readLinks(Path file) throws IOException {
        Map<String, Link> read = new HashMap<>();
        Table table = Table.read(file);
        for (String[] cells : table.rows) {
            String notes = table.cell(cells, "notes");
            List<String> list = new ArrayList<>();
            if (!notes.isBlank()) {
                Arrays.stream(notes.split(java.util.regex.Pattern.quote(RelationRunner.NOTE_SEPARATOR)))
                        .map(String::trim).filter(n -> !n.isEmpty()).forEach(list::add);
            } else if (!table.cell(cells, "example_note").isBlank()) {
                list.add(table.cell(cells, "example_note").trim());
            }
            int count;
            try {
                count = Integer.parseInt(table.cell(cells, "textual_mods").trim());
            } catch (NumberFormatException e) {
                count = 0;
            }
            read.put(normal(table.cell(cells, "from")) + "→" + normal(table.cell(cells, "to")),
                    new Link(table.cell(cells, "property"), table.cell(cells, "sources"), count,
                            kinds(table.cell(cells, "kinds")), List.copyOf(list)));
        }
        return read;
    }

    private static Map<String, List<Change>> readChanges(Path file) throws IOException {
        Map<String, List<Change>> read = new HashMap<>();
        Table table = Table.read(file);
        for (String[] cells : table.rows) {
            read.computeIfAbsent(normal(table.cell(cells, "act")) + "←" + normal(table.cell(cells, "changed_by")),
                    k -> new ArrayList<>()).add(new Change(table.cell(cells, "version_date"), table.cell(cells, "event_date")));
        }
        read.values().forEach(list -> list.sort(java.util.Comparator.comparing(Change::versionDate)));
        return read;
    }

    /** "DECRETO-LEGGE 2020-12-18 n. 172" → "DECRETO_LEGGE 2020-12-18 n. 172". */
    private static String normal(String key) {
        int space = key.indexOf(' ');
        return space < 0 ? key : key.substring(0, space).toUpperCase(Locale.ROOT).replace('-', '_') + key.substring(space);
    }

    /** "{AMENDMENT=3, INSERTION=6}" → {AMENDMENT=3, INSERTION=6}. */
    private static Map<String, Integer> kinds(String value) {
        Map<String, Integer> kinds = new LinkedHashMap<>();
        String inner = value.replace("{", "").replace("}", "").trim();
        if (inner.isEmpty()) {
            return kinds;
        }
        for (String part : inner.split(",")) {
            String[] kv = part.trim().split("=");
            if (kv.length == 2) {
                try {
                    kinds.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
                } catch (NumberFormatException ignored) {
                    // skip a malformed entry
                }
            }
        }
        return kinds;
    }

    private record Table(List<String> header, List<String[]> rows) {
        static Table read(Path file) throws IOException {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return new Table(List.of(), List.of());
            }
            List<String[]> rows = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                if (!line.isBlank()) {
                    rows.add(line.split("\t", -1));
                }
            }
            return new Table(List.of(lines.get(0).split("\t", -1)), rows);
        }

        String cell(String[] cells, String column) {
            int i = header.indexOf(column);
            return i < 0 || i >= cells.length ? "" : cells[i];
        }
    }
}
