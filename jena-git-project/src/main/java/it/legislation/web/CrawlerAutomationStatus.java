package it.legislation.web;

public record CrawlerAutomationStatus(
        boolean enabled,
        String cron,
        String zone,
        int maxEntries,
        int maxLinks,
        String lastTriggeredAt,
        String lastState,
        String lastMessage
) {
}
