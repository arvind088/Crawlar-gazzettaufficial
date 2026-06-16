package it.legislation.web;

public record CrawlerUpdateResult(
        String state,
        String startedAt,
        String finishedAt,
        int maxRssEntries,
        int maxLinksToCrawl,
        int rssEntriesRead,
        int rssEntriesAdded,
        int linksAvailable,
        int linksCrawled,
        int changedRecords,
        String message,
        CrawlerStatus crawlerStatus
) {
}
