package it.legislation.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import it.legislation.ingest.ScheduledDailyUpdateJob;
import it.legislation.store.Tdb2DatasetService;

/**
 * {@code GET /api/health}: is the store readable, how big is it, which RDF files
 * fed it, and what the daily update last did. Used by the host's health check.
 */
@RestController
public class HealthController {

    private final Tdb2DatasetService datasetService;
    private final ScheduledDailyUpdateJob dailyUpdate;

    public HealthController(Tdb2DatasetService datasetService, ScheduledDailyUpdateJob dailyUpdate) {
        this.datasetService = datasetService;
        this.dailyUpdate = dailyUpdate;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() throws IOException {
        Tdb2DatasetService.LoadStatus status = datasetService.status();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.triples() > 0 ? "UP" : "EMPTY");
        body.put("triples", status.triples());
        body.put("loadedFiles", status.loadedFiles());
        body.put("missingFiles", status.missingFiles());
        body.put("dailyUpdate", dailyUpdate.status());
        return body;
    }
}
