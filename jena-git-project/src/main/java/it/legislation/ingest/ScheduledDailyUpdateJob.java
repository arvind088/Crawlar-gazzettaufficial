package it.legislation.ingest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import it.legislation.source.normattiva.NormattivaClient;

/**
 * Runs {@link DailyUpdateRunner} inside the application, once a day.
 *
 * <p>Off by default ({@code legal.daily-update.enabled=false}): it needs the raw
 * corpus next to the application and a network that Normattiva accepts (it
 * blocks GitHub's machines, see docs/update-routine-rationale.md section 7).
 * On the server that has both, set {@code LEGAL_DAILY_UPDATE_ENABLED=true}.
 *
 * <p>The run rewrites the RDF files under {@code data/rdf}; the store notices
 * the changed files on the next read and adds their statements (additive, as
 * every ingestion). One run at a time: a run that is still going when the next
 * one is due makes the next one a no-op.
 */
@Component
public class ScheduledDailyUpdateJob {

    private final boolean enabled;
    private final String cron;
    private final String zone;
    private final Path root;
    private final String baseUri;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile String lastStarted;
    private volatile String lastResult;

    public ScheduledDailyUpdateJob(
            @Value("${legal.daily-update.enabled:false}") boolean enabled,
            @Value("${legal.daily-update.cron:0 30 6 * * *}") String cron,
            @Value("${legal.daily-update.zone:Europe/Rome}") String zone,
            @Value("${legal.daily-update.corpus-root:data/raw/normattiva}") String root,
            @Value("${legal.eli.base-uri}") String baseUri) {
        this.enabled = enabled;
        this.cron = cron;
        this.zone = zone;
        this.root = Path.of(root);
        this.baseUri = baseUri;
        this.lastResult = enabled ? "scheduled, not run yet" : "disabled";
    }

    @Scheduled(cron = "${legal.daily-update.cron:0 30 6 * * *}", zone = "${legal.daily-update.zone:Europe/Rome}")
    public void runScheduled() {
        if (enabled) {
            runNow();
        }
    }

    /** Runs the update now, unless one is already running. */
    public void runNow() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        lastStarted = IngestionWatermark.nowUtc().toString();
        try {
            DailyUpdateRunner runner = new DailyUpdateRunner(
                    DailyUpdateRunner.clientSource(new NormattivaClient()),
                    DailyUpdateRunner.defaultPipeline(root, baseUri),
                    root, AknImportRunner.DEFAULT_OUT,
                    new IngestionWatermark(IngestionWatermark.DEFAULT_PATH), new IngestionRunLog());
            DailyUpdateRunner.Summary s = runner.run(IngestionWatermark.nowUtc(), Duration.ofDays(7), false, line -> { });
            lastResult = s.failures().isEmpty()
                    ? "OK: " + s.updated() + " changed, " + s.fetched() + " downloaded"
                    : "FAILED (" + s.failures().size() + "): " + s.failures().get(0);
        } catch (Exception failure) {
            // The runner has already written the run log and left the watermark where it was.
            lastResult = "FAILED: " + failure.getMessage();
        } finally {
            running.set(false);
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("cron", cron);
        status.put("zone", zone);
        status.put("running", running.get());
        status.put("lastStarted", lastStarted);
        status.put("lastResult", lastResult);
        return status;
    }
}
