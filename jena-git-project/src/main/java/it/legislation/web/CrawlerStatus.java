package it.legislation.web;

import java.util.List;
import java.util.Map;

public record CrawlerStatus(
        int registryRecords,
        Map<String, Integer> registryStatusCounts,
        String latestPublicationDate,
        String lastCheckedAt,
        int rssLinkCount,
        String rssFetchDate,
        long rdfDeltaBytes,
        String rdfDeltaLastModified,
        int rawSnapshotCount,
        List<String> loadedFiles,
        List<String> missingFiles
) {
}
