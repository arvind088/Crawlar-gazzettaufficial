package it.legislation.web;

import java.io.IOException;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledNormattivaUpdateJob {

    private final NormattivaUpdateService normattivaUpdateService;
    private final boolean enabled;
    private final String cron;
    private final String zone;
    private volatile String lastTriggeredAt;
    private volatile String lastState;
    private volatile String lastMessage;

    public ScheduledNormattivaUpdateJob(
            NormattivaUpdateService normattivaUpdateService,
            @Value("${legal.normattiva.schedule.enabled:true}") boolean enabled,
            @Value("${legal.normattiva.schedule.cron:0 45 6 * * *}") String cron,
            @Value("${legal.normattiva.schedule.zone:Europe/Rome}") String zone
    ) {
        this.normattivaUpdateService = normattivaUpdateService;
        this.enabled = enabled;
        this.cron = cron;
        this.zone = zone;
        this.lastState = enabled ? "SCHEDULED" : "DISABLED";
        this.lastMessage = enabled ? "Automatic Normattiva update is scheduled." : "Automatic Normattiva update is disabled.";
    }

    @Scheduled(cron = "${legal.normattiva.schedule.cron:0 45 6 * * *}", zone = "${legal.normattiva.schedule.zone:Europe/Rome}")
    public void runScheduledUpdate() throws IOException {
        if (!enabled) {
            return;
        }

        lastTriggeredAt = OffsetDateTime.now().toString();
        NormattivaUpdateResult result = normattivaUpdateService.runUpdate();
        lastState = result.state();
        lastMessage = result.message();
    }

    public NormattivaAutomationStatus status() {
        return new NormattivaAutomationStatus(
                enabled,
                cron,
                zone,
                lastTriggeredAt,
                lastState,
                lastMessage
        );
    }
}
