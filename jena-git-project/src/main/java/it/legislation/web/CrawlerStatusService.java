package it.legislation.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class CrawlerStatusService {

    private static final Path REGISTRY = Path.of("data", "registry", "crawl_registry.tsv");
    private static final Path RSS_UPDATES = Path.of("data", "clean", "gazzetta_rss_updates.tsv");
    private static final Path GAZZETTA_DELTA = Path.of("data", "rdf", "gazzetta_metadata_delta.ttl");
    private static final Path RAW_GAZZETTA = Path.of("data", "raw", "gazzetta");

    private final Path registryPath;
    private final Path rssUpdatesPath;
    private final Path rdfDeltaPath;
    private final Path rawGazzettaPath;

    public CrawlerStatusService() {
        this(REGISTRY, RSS_UPDATES, GAZZETTA_DELTA, RAW_GAZZETTA);
    }

    CrawlerStatusService(Path registryPath, Path rssUpdatesPath, Path rdfDeltaPath, Path rawGazzettaPath) {
        this.registryPath = registryPath;
        this.rssUpdatesPath = rssUpdatesPath;
        this.rdfDeltaPath = rdfDeltaPath;
        this.rawGazzettaPath = rawGazzettaPath;
    }

    public CrawlerStatus status() throws IOException {
        List<String> loadedFiles = new ArrayList<>();
        List<String> missingFiles = new ArrayList<>();
        RegistrySummary registry = readRegistry(loadedFiles, missingFiles);
        RssSummary rss = readRssUpdates(loadedFiles, missingFiles);
        FileSummary rdfDelta = readRdfDelta(loadedFiles, missingFiles);

        return new CrawlerStatus(
                registry.records(),
                registry.statusCounts(),
                registry.latestPublicationDate(),
                registry.lastCheckedAt(),
                rss.linkCount(),
                rss.fetchDate(),
                rdfDelta.bytes(),
                rdfDelta.lastModified(),
                countRawSnapshots(),
                loadedFiles,
                missingFiles
        );
    }

    private RegistrySummary readRegistry(List<String> loadedFiles, List<String> missingFiles) throws IOException {
        if (!Files.exists(registryPath)) {
            missingFiles.add(absolute(registryPath));
            return new RegistrySummary(0, Map.of(), null, null);
        }

        loadedFiles.add(absolute(registryPath));
        List<String> lines = Files.readAllLines(registryPath);
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        String latestPublicationDate = null;
        String lastCheckedAt = null;
        int records = 0;

        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            String[] columns = line.split("\t", -1);
            records++;
            String publicationDate = value(columns, 3);
            String checkedAt = value(columns, 7);
            String status = value(columns, 9);
            if (!status.isBlank()) {
                statusCounts.merge(status, 1, Integer::sum);
            }
            latestPublicationDate = maxText(latestPublicationDate, publicationDate);
            lastCheckedAt = maxText(lastCheckedAt, checkedAt);
        }

        return new RegistrySummary(records, statusCounts, latestPublicationDate, lastCheckedAt);
    }

    private RssSummary readRssUpdates(List<String> loadedFiles, List<String> missingFiles) throws IOException {
        if (!Files.exists(rssUpdatesPath)) {
            missingFiles.add(absolute(rssUpdatesPath));
            return new RssSummary(0, null);
        }

        loadedFiles.add(absolute(rssUpdatesPath));
        List<String> lines = Files.readAllLines(rssUpdatesPath);
        String fetchDate = null;
        int linkCount = 0;

        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            String[] columns = line.split("\t", -1);
            linkCount++;
            fetchDate = maxText(fetchDate, value(columns, 4));
        }

        return new RssSummary(linkCount, fetchDate);
    }

    private FileSummary readRdfDelta(List<String> loadedFiles, List<String> missingFiles) throws IOException {
        if (!Files.exists(rdfDeltaPath)) {
            missingFiles.add(absolute(rdfDeltaPath));
            return new FileSummary(0L, null);
        }

        loadedFiles.add(absolute(rdfDeltaPath));
        Instant modified = Files.getLastModifiedTime(rdfDeltaPath).toInstant();
        return new FileSummary(Files.size(rdfDeltaPath), modified.toString());
    }

    private int countRawSnapshots() throws IOException {
        if (!Files.isDirectory(rawGazzettaPath)) {
            return 0;
        }
        try (var stream = Files.list(rawGazzettaPath)) {
            return (int) stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".html"))
                    .count();
        }
    }

    private String absolute(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private String value(String[] columns, int index) {
        if (index >= columns.length) {
            return "";
        }
        return columns[index].trim();
    }

    private String maxText(String current, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return current;
        }
        if (current == null || candidate.compareTo(current) > 0) {
            return candidate;
        }
        return current;
    }

    private record RegistrySummary(
            int records,
            Map<String, Integer> statusCounts,
            String latestPublicationDate,
            String lastCheckedAt
    ) {
    }

    private record RssSummary(
            int linkCount,
            String fetchDate
    ) {
    }

    private record FileSummary(
            long bytes,
            String lastModified
    ) {
    }
}
