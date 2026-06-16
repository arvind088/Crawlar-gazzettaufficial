package it.legislation.web;

import java.io.IOException;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledCrawlerUpdateJob {

    private final CrawlerUpdateService crawlerUpdateService;
    private final boolean enabled;
    private final String cron;
    private final String zone;
    private final int maxEntries;
    private final int maxLinks;
    private volatile String lastTriggeredAt;
    private volatile String lastState;
    private volatile String lastMessage;

    public ScheduledCrawlerUpdateJob(
            CrawlerUpdateService crawlerUpdateService,
            @Value("${legal.crawler.schedule.enabled:true}") boolean enabled,
            @Value("${legal.crawler.schedule.cron:0 15 6 * * *}") String cron,
            @Value("${legal.crawler.schedule.zone:Europe/Rome}") String zone,
            @Value("${legal.crawler.schedule.max-entries:50}") int maxEntries,
            @Value("${legal.crawler.schedule.max-links:0}") int maxLinks
    ) {
        this.crawlerUpdateService = crawlerUpdateService;
        this.enabled = enabled;
        this.cron = cron;
        this.zone = zone;
        this.maxEntries = maxEntries;
        this.maxLinks = maxLinks;
        this.lastState = enabled ? "SCHEDULED" : "DISABLED";
        this.lastMessage = enabled ? "Automatic crawler update is scheduled." : "Automatic crawler update is disabled.";
    }

    @Scheduled(cron = "${legal.crawler.schedule.cron:0 15 6 * * *}", zone = "${legal.crawler.schedule.zone:Europe/Rome}")
    public void runScheduledUpdate() throws IOException {
        if (!enabled) {
            return;
        }

        lastTriggeredAt = OffsetDateTime.now().toString();
        CrawlerUpdateResult result = crawlerUpdateService.runUpdate(maxEntries, maxLinks);
        lastState = result.state();
        lastMessage = result.message();
    }

    public CrawlerAutomationStatus status() {
        return new CrawlerAutomationStatus(
                enabled,
                cron,
                zone,
                maxEntries,
                maxLinks,
                lastTriggeredAt,
                lastState,
                lastMessage
        );
    }
}
