package it.legislation.web;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import it.legislation.crawler.GazzettaArchiveCrawlRunner;
import it.legislation.crawler.GazzettaArchiveDiscoveryRunner;
import it.legislation.crawler.GazzettaScraper;

@Service
public class ArchiveCrawlerService {

    private static final Path DEFAULT_ARCHIVE_LINKS = Path.of("data", "clean", "gazzetta_archive_links.tsv");
    private static final Path DEFAULT_REGISTRY = Path.of("data", "registry", "crawl_registry.tsv");
    private static final int DEFAULT_CRAWL_LIMIT = 10;
    private static final int HARD_MAX_CRAWL_LIMIT = 500;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CrawlerStatusService statusService;
    private final ArchiveDiscoverer archiveDiscoverer;
    private final ArchiveLinkWriter archiveLinkWriter;
    private final ArchiveLinkReader archiveLinkReader;
    private final ActCrawler actCrawler;
    private final Path archiveLinksPath;
    private final Path registryPath;
    private volatile ArchiveCrawlerResult lastResult;

    @Autowired
    public ArchiveCrawlerService(CrawlerStatusService statusService) {
        this(
                statusService,
                GazzettaArchiveDiscoveryRunner::discover,
                GazzettaArchiveDiscoveryRunner::writeLinks,
                GazzettaArchiveCrawlRunner::readActUrls,
                GazzettaScraper::crawlGazzettaActUrls,
                DEFAULT_ARCHIVE_LINKS,
                DEFAULT_REGISTRY
        );
    }

    ArchiveCrawlerService(
            CrawlerStatusService statusService,
            ArchiveDiscoverer archiveDiscoverer,
            ArchiveLinkWriter archiveLinkWriter,
            ArchiveLinkReader archiveLinkReader,
            ActCrawler actCrawler,
            Path archiveLinksPath,
            Path registryPath
    ) {
        this.statusService = statusService;
        this.archiveDiscoverer = archiveDiscoverer;
        this.archiveLinkWriter = archiveLinkWriter;
        this.archiveLinkReader = archiveLinkReader;
        this.actCrawler = actCrawler;
        this.archiveLinksPath = archiveLinksPath;
        this.registryPath = registryPath;
    }

    public ArchiveCrawlerResult discover(LocalDate startDate, LocalDate endDate) throws IOException {
        if (!running.compareAndSet(false, true)) {
            return runningResult("discover", startDate, endDate, 0);
        }

        OffsetDateTime startedAt = OffsetDateTime.now();
        lastResult = new ArchiveCrawlerResult(
                "RUNNING",
                "discover",
                startedAt.toString(),
                null,
                startDate.toString(),
                endDate.toString(),
                0,
                0,
                0,
                0,
                0,
                "Archive discovery started.",
                statusService.status()
        );

        try {
            List<GazzettaArchiveDiscoveryRunner.ArchiveActLink> links = archiveDiscoverer.discover(startDate, endDate);
            archiveLinkWriter.write(links, archiveLinksPath, startedAt);
            OffsetDateTime finishedAt = OffsetDateTime.now();
            ArchiveCrawlerResult result = new ArchiveCrawlerResult(
                    "COMPLETED",
                    "discover",
                    startedAt.toString(),
                    finishedAt.toString(),
                    startDate.toString(),
                    endDate.toString(),
                    0,
                    links.size(),
                    links.size(),
                    0,
                    0,
                    "Archive discovery completed.",
                    statusService.status()
            );
            lastResult = result;
            return result;
        } catch (IOException | RuntimeException e) {
            ArchiveCrawlerResult result = failedResult("discover", startedAt, startDate, endDate, 0, e);
            lastResult = result;
            return result;
        } finally {
            running.set(false);
        }
    }

    public ArchiveCrawlerResult crawl(int limit) throws IOException {
        int safeLimit = clampLimit(limit);
        if (!running.compareAndSet(false, true)) {
            return runningResult("crawl", null, null, safeLimit);
        }

        OffsetDateTime startedAt = OffsetDateTime.now();
        lastResult = new ArchiveCrawlerResult(
                "RUNNING",
                "crawl",
                startedAt.toString(),
                null,
                null,
                null,
                safeLimit,
                0,
                0,
                0,
                0,
                "Archive crawl started.",
                statusService.status()
        );

        try {
            List<String> allLinks = archiveLinkReader.read(archiveLinksPath, 0);
            List<String> availableLinks = linksMissingFromRegistry(allLinks);
            int effectiveLimit = effectiveLimit(safeLimit, availableLinks.size());
            List<String> selectedLinks = applyLimit(availableLinks, effectiveLimit);
            int changedRecords = selectedLinks.isEmpty() ? 0 : actCrawler.crawl(selectedLinks);
            OffsetDateTime finishedAt = OffsetDateTime.now();
            ArchiveCrawlerResult result = new ArchiveCrawlerResult(
                    "COMPLETED",
                    "crawl",
                    startedAt.toString(),
                    finishedAt.toString(),
                    null,
                    null,
                    effectiveLimit,
                    0,
                    availableLinks.size(),
                    selectedLinks.size(),
                    changedRecords,
                    "Archive crawl completed.",
                    statusService.status()
            );
            lastResult = result;
            return result;
        } catch (IOException | RuntimeException e) {
            ArchiveCrawlerResult result = failedResult("crawl", startedAt, null, null, safeLimit, e);
            lastResult = result;
            return result;
        } finally {
            running.set(false);
        }
    }

    public ArchiveCrawlerResult lastResult() {
        return lastResult;
    }

    private ArchiveCrawlerResult runningResult(String action, LocalDate startDate, LocalDate endDate, int maxLinksToCrawl) throws IOException {
        ArchiveCrawlerResult current = lastResult;
        return new ArchiveCrawlerResult(
                "RUNNING",
                action,
                current == null ? null : current.startedAt(),
                null,
                startDate == null ? null : startDate.toString(),
                endDate == null ? null : endDate.toString(),
                maxLinksToCrawl,
                0,
                0,
                0,
                0,
                "An archive crawler action is already running.",
                current == null ? statusService.status() : current.crawlerStatus()
        );
    }

    private ArchiveCrawlerResult failedResult(
            String action,
            OffsetDateTime startedAt,
            LocalDate startDate,
            LocalDate endDate,
            int maxLinksToCrawl,
            Exception exception
    ) throws IOException {
        return new ArchiveCrawlerResult(
                "FAILED",
                action,
                startedAt.toString(),
                OffsetDateTime.now().toString(),
                startDate == null ? null : startDate.toString(),
                endDate == null ? null : endDate.toString(),
                maxLinksToCrawl,
                0,
                0,
                0,
                0,
                exception.getMessage() == null ? "Archive crawler action failed." : exception.getMessage(),
                statusService.status()
        );
    }

    private int clampLimit(int value) {
        if (value < 0) {
            return DEFAULT_CRAWL_LIMIT;
        }
        if (value == 0) {
            return 0;
        }
        return Math.min(value, HARD_MAX_CRAWL_LIMIT);
    }

    private int effectiveLimit(int requestedLimit, int availableLinks) {
        if (availableLinks <= 0) {
            return 0;
        }
        if (requestedLimit == 0) {
            return availableLinks;
        }
        return Math.min(requestedLimit, availableLinks);
    }

    private List<String> linksMissingFromRegistry(List<String> links) throws IOException {
        Set<String> knownEliUris = readRegistryEliUris();
        Set<String> missingLinks = new LinkedHashSet<>();
        for (String link : links) {
            String canonicalEliUri = canonicalEliUriFromArchiveUrl(link);
            if (canonicalEliUri.isBlank() || !knownEliUris.contains(canonicalEliUri)) {
                missingLinks.add(link);
            }
        }
        return List.copyOf(missingLinks);
    }

    private Set<String> readRegistryEliUris() throws IOException {
        if (!Files.exists(registryPath)) {
            return Set.of();
        }

        Set<String> uris = new LinkedHashSet<>();
        List<String> lines = Files.readAllLines(registryPath, StandardCharsets.UTF_8);
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            String[] columns = line.split("\t", -1);
            if (columns.length > 0 && !columns[0].isBlank()) {
                uris.add(canonicalEliUri(columns[0]));
            }
        }
        return uris;
    }

    private List<String> applyLimit(List<String> links, int limit) {
        if (limit == 0 || links.size() <= limit) {
            return links;
        }
        return List.copyOf(links.subList(0, limit));
    }

    private String canonicalEliUriFromArchiveUrl(String value) {
        try {
            URI uri = URI.create(value);
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) {
                return canonicalEliUri(value);
            }

            String publicationDate = "";
            String localId = "";
            for (String pair : query.split("&")) {
                int separator = pair.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
                String fieldValue = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
                if ("atto.dataPubblicazioneGazzetta".equals(key)) {
                    publicationDate = fieldValue;
                }
                if ("atto.codiceRedazionale".equals(key)) {
                    localId = fieldValue;
                }
            }

            if (publicationDate.isBlank() || localId.isBlank()) {
                return canonicalEliUri(value);
            }

            LocalDate date = LocalDate.parse(publicationDate);
            return String.format(
                    "http://www.gazzettaufficiale.it/eli/id/%04d/%02d/%02d/%s/sg",
                    date.getYear(),
                    date.getMonthValue(),
                    date.getDayOfMonth(),
                    localId.trim()
            );
        } catch (IllegalArgumentException | DateTimeParseException ignored) {
            return canonicalEliUri(value);
        }
    }

    private String canonicalEliUri(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replaceFirst("^https://www\\.gazzettaufficiale\\.it", "http://www.gazzettaufficiale.it")
                .replaceFirst("/SG$", "/sg");
    }

    @FunctionalInterface
    interface ArchiveDiscoverer {
        List<GazzettaArchiveDiscoveryRunner.ArchiveActLink> discover(LocalDate startDate, LocalDate endDate) throws IOException;
    }

    @FunctionalInterface
    interface ArchiveLinkWriter {
        void write(List<GazzettaArchiveDiscoveryRunner.ArchiveActLink> links, Path output, OffsetDateTime discoveredAt) throws IOException;
    }

    @FunctionalInterface
    interface ArchiveLinkReader {
        List<String> read(Path input, int limit) throws IOException;
    }

    @FunctionalInterface
    interface ActCrawler {
        int crawl(List<String> links);
    }
}
