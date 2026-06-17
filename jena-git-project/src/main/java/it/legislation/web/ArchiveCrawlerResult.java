package it.legislation.web;

public record ArchiveCrawlerResult(
        String state,
        String action,
        String startedAt,
        String finishedAt,
        String startDate,
        String endDate,
        int maxLinksToCrawl,
        int discoveredLinks,
        int linksAvailable,
        int linksCrawled,
        int changedRecords,
        String message,
        CrawlerStatus crawlerStatus
) {
}
