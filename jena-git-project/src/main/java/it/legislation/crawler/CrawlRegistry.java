package it.legislation.crawler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CrawlRegistry {

    public enum UpdateStatus {
        NEW,
        CHANGED,
        UNCHANGED
    }

    private static final String SOURCE_GAZZETTA = "gazzetta";

    private static final List<String> HEADER_COLUMNS = List.of(
            "eliUri",
            "source",
            "sourceUrl",
            "publicationDate",
            "redactionalCode",
            "contentHash",
            "firstSeenAt",
            "lastCheckedAt",
            "lastChangedAt",
            "status",
            "lastError"
    );

    private final Path registryPath;

    public CrawlRegistry(Path registryPath) {
        this.registryPath = registryPath;
    }

    public void ensureExists() throws IOException {
        Path parent = registryPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        if (!Files.exists(registryPath) || Files.size(registryPath) == 0) {
            Files.writeString(registryPath, headerLine() + System.lineSeparator(), StandardCharsets.UTF_8);
        }
    }

    public long countRecords() throws IOException {
        return readEntries().size();
    }

    public Optional<Entry> findByEliUri(String eliUri) throws IOException {
        return Optional.ofNullable(readEntries().get(eliUri));
    }

    public UpdateStatus upsertSuccess(CleanLegalActRecord record, String sourceUrl, String contentHash, OffsetDateTime checkedAt) throws IOException {
        ensureExists();
        Map<String, Entry> entries = readEntries();
        Entry previous = entries.get(record.getEliUri());
        UpdateStatus status = determineStatus(previous, contentHash);
        String now = checkedAt.toString();
        boolean changed = status == UpdateStatus.NEW || status == UpdateStatus.CHANGED;

        Entry updated = new Entry(
                record.getEliUri(),
                SOURCE_GAZZETTA,
                sourceUrl,
                record.getPublicationDate().map(Object::toString).orElse(""),
                record.getLocalId().orElse(""),
                contentHash,
                previous == null ? now : previous.firstSeenAt,
                now,
                changed ? now : previous.lastChangedAt,
                status.name(),
                ""
        );

        entries.put(record.getEliUri(), updated);
        writeEntries(entries);
        return status;
    }

    public Path getRegistryPath() {
        return registryPath;
    }

    public static String headerLine() {
        return String.join("\t", HEADER_COLUMNS);
    }

    private UpdateStatus determineStatus(Entry previous, String contentHash) {
        if (previous == null) {
            return UpdateStatus.NEW;
        }
        if (contentHash.equals(previous.contentHash)) {
            return UpdateStatus.UNCHANGED;
        }
        return UpdateStatus.CHANGED;
    }

    private Map<String, Entry> readEntries() throws IOException {
        ensureExists();
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(registryPath, StandardCharsets.UTF_8);

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            Entry entry = Entry.fromTsv(line);
            entries.put(entry.eliUri, entry);
        }

        return entries;
    }

    private void writeEntries(Map<String, Entry> entries) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(headerLine());
        for (Entry entry : entries.values()) {
            lines.add(entry.toTsv());
        }
        Files.write(registryPath, lines, StandardCharsets.UTF_8);
    }

    public static final class Entry {
        private final String eliUri;
        private final String source;
        private final String sourceUrl;
        private final String publicationDate;
        private final String redactionalCode;
        private final String contentHash;
        private final String firstSeenAt;
        private final String lastCheckedAt;
        private final String lastChangedAt;
        private final String status;
        private final String lastError;

        private Entry(
                String eliUri,
                String source,
                String sourceUrl,
                String publicationDate,
                String redactionalCode,
                String contentHash,
                String firstSeenAt,
                String lastCheckedAt,
                String lastChangedAt,
                String status,
                String lastError
        ) {
            this.eliUri = eliUri;
            this.source = source;
            this.sourceUrl = sourceUrl;
            this.publicationDate = publicationDate;
            this.redactionalCode = redactionalCode;
            this.contentHash = contentHash;
            this.firstSeenAt = firstSeenAt;
            this.lastCheckedAt = lastCheckedAt;
            this.lastChangedAt = lastChangedAt;
            this.status = status;
            this.lastError = lastError;
        }

        public String getEliUri() {
            return eliUri;
        }

        public String getContentHash() {
            return contentHash;
        }

        public String getStatus() {
            return status;
        }

        public String getFirstSeenAt() {
            return firstSeenAt;
        }

        public String getLastChangedAt() {
            return lastChangedAt;
        }

        private static Entry fromTsv(String line) {
            String[] values = line.split("\t", -1);
            return new Entry(
                    valueAt(values, 0),
                    valueAt(values, 1),
                    valueAt(values, 2),
                    valueAt(values, 3),
                    valueAt(values, 4),
                    valueAt(values, 5),
                    valueAt(values, 6),
                    valueAt(values, 7),
                    valueAt(values, 8),
                    valueAt(values, 9),
                    valueAt(values, 10)
            );
        }

        private String toTsv() {
            return String.join("\t",
                    cleanField(eliUri),
                    cleanField(source),
                    cleanField(sourceUrl),
                    cleanField(publicationDate),
                    cleanField(redactionalCode),
                    cleanField(contentHash),
                    cleanField(firstSeenAt),
                    cleanField(lastCheckedAt),
                    cleanField(lastChangedAt),
                    cleanField(status),
                    cleanField(lastError)
            );
        }

        private static String valueAt(String[] values, int index) {
            return index < values.length ? values[index] : "";
        }

        private static String cleanField(String value) {
            if (value == null) {
                return "";
            }
            return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
        }
    }
}
