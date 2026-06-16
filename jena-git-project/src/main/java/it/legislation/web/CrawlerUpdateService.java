package it.legislation.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import it.legislation.crawler.GazzettaRssUpdateRunner;
import it.legislation.crawler.GazzettaScraper;

@Service
public class CrawlerUpdateService {

    private static final String DEFAULT_RSS_URL = "https://www.gazzettaufficiale.it/rss/SG";
    private static final Path DEFAULT_RSS_UPDATES = Path.of("data", "clean", "gazzetta_rss_updates.tsv");
    private static final Path DEFAULT_REGISTRY = Path.of("data", "registry", "crawl_registry.tsv");
    private static final int DEFAULT_MAX_RSS_ENTRIES = 20;
    private static final int HARD_MAX_RSS_ENTRIES = 100;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CrawlerStatusService statusService;
    private final RssFetcher rssFetcher;
    private final ActCrawler actCrawler;
    private final Path rssUpdatesPath;
    private final Path registryPath;
    private volatile CrawlerUpdateResult lastResult;

    @Autowired
    public CrawlerUpdateService(CrawlerStatusService statusService) {
        this(
                statusService,
                GazzettaRssUpdateRunner::fetchEntries,
                GazzettaScraper::crawlGazzettaActUrls,
                DEFAULT_RSS_UPDATES,
                DEFAULT_REGISTRY
        );
    }

    CrawlerUpdateService(
            CrawlerStatusService statusService,
            RssFetcher rssFetcher,
            ActCrawler actCrawler,
            Path rssUpdatesPath,
            Path registryPath
    ) {
        this.statusService = statusService;
        this.rssFetcher = rssFetcher;
        this.actCrawler = actCrawler;
        this.rssUpdatesPath = rssUpdatesPath;
        this.registryPath = registryPath;
    }

    public CrawlerUpdateResult runUpdate(int maxRssEntries, int maxLinks) throws IOException {
        int safeMaxRssEntries = clamp(maxRssEntries, DEFAULT_MAX_RSS_ENTRIES, HARD_MAX_RSS_ENTRIES);

        if (!running.compareAndSet(false, true)) {
            CrawlerUpdateResult current = lastResult;
            return new CrawlerUpdateResult(
                    "RUNNING",
                    current == null ? null : current.startedAt(),
                    null,
                    safeMaxRssEntries,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    "An update check is already running.",
                    current == null ? statusService.status() : current.crawlerStatus()
            );
        }

        OffsetDateTime startedAt = OffsetDateTime.now();
        lastResult = new CrawlerUpdateResult(
                "RUNNING",
                startedAt.toString(),
                null,
                safeMaxRssEntries,
                0,
                0,
                0,
                0,
                0,
                0,
                "Update check started.",
                statusService.status()
        );

        try {
            List<GazzettaRssUpdateRunner.RssEntry> entries = rssFetcher.fetch(DEFAULT_RSS_URL, safeMaxRssEntries);
            int added = GazzettaRssUpdateRunner.appendNewEntries(entries, rssUpdatesPath, startedAt);
            List<String> links = entries.stream()
                    .map(GazzettaRssUpdateRunner.RssEntry::link)
                    .filter(link -> link != null && !link.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
            List<String> newLinks = findLinksMissingFromRegistry(links);
            int changedRecords = newLinks.isEmpty() ? 0 : actCrawler.crawl(newLinks);
            OffsetDateTime finishedAt = OffsetDateTime.now();

            CrawlerUpdateResult result = new CrawlerUpdateResult(
                    "COMPLETED",
                    startedAt.toString(),
                    finishedAt.toString(),
                    safeMaxRssEntries,
                    0,
                    entries.size(),
                    added,
                    links.size(),
                    newLinks.size(),
                    changedRecords,
                    "Update check completed.",
                    statusService.status()
            );
            lastResult = result;
            return result;
        } catch (IOException | RuntimeException e) {
            OffsetDateTime finishedAt = OffsetDateTime.now();
            CrawlerUpdateResult result = new CrawlerUpdateResult(
                    "FAILED",
                    startedAt.toString(),
                    finishedAt.toString(),
                    safeMaxRssEntries,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    e.getMessage() == null ? "Update check failed." : e.getMessage(),
                    statusService.status()
            );
            lastResult = result;
            return result;
        } finally {
            running.set(false);
        }
    }

    public CrawlerUpdateResult lastResult() {
        return lastResult;
    }

    private int clamp(int value, int defaultValue, int maxValue) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.min(value, maxValue);
    }

    private List<String> findLinksMissingFromRegistry(List<String> links) throws IOException {
        Set<String> knownEliUris = readRegistryEliUris();
        Set<String> missingLinks = new LinkedHashSet<>();
        for (String link : links) {
            String canonicalEliUri = canonicalEliUri(link);
            if (!knownEliUris.contains(canonicalEliUri)) {
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

    private String canonicalEliUri(String value) {
        String normalized = value.trim()
                .replaceFirst("^https://www\\.gazzettaufficiale\\.it", "http://www.gazzettaufficiale.it")
                .replaceFirst("/SG$", "/sg");
        return normalized;
    }

    @FunctionalInterface
    interface RssFetcher {
        List<GazzettaRssUpdateRunner.RssEntry> fetch(String rssUrl, int maxEntries) throws IOException;
    }

    @FunctionalInterface
    interface ActCrawler {
        int crawl(List<String> links);
    }
}
