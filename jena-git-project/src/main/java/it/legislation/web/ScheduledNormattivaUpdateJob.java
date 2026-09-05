package it.legislation.web;

import java.io.IOException;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;

import it.legislation.crawler.IngestionRunLog;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledNormattivaUpdateJob {

    private final NormattivaUpdateService normattivaUpdateService;
    private final IngestionRunLog runLog = new IngestionRunLog();
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

        OffsetDateTime startedAt = OffsetDateTime.now();
        lastTriggeredAt = startedAt.toString();

        IngestionRunLog.Run run = IngestionRunLog.Run.started("normattiva", "SCHEDULED", startedAt);
        try {
            NormattivaUpdateResult result = normattivaUpdateService.runUpdate();
            lastState = result.state();
            lastMessage = result.message();
            recordRun(run.completed(
                    OffsetDateTime.now(),
                    result.updatesRead(),
                    result.updatesRead(),
                    result.relationRows(),
                    "FAILED".equals(result.state()) ? 1 : 0,
                    "",
                    "",
                    result.message()));
        } catch (IOException | RuntimeException failure) {
            lastState = "FAILED";
            lastMessage = failure.getMessage() == null
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage();
            recordRun(run.failed(OffsetDateTime.now(), lastMessage));
            throw failure;
        }
    }

    private void recordRun(IngestionRunLog.Run run) {
        try {
            runLog.append(run);
        } catch (IOException exception) {
            lastMessage = lastMessage + " (run log unavailable: " + exception.getMessage() + ")";
        }
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
