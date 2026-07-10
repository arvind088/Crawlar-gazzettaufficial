package it.legislation.web;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import it.legislation.crawler.NormattivaUpdateRunner;

@Service
public class NormattivaUpdateService {

    private static final String DEFAULT_SOURCE_URL = "https://www.normattiva.it/";
    private static final Path DEFAULT_UPDATES_OUTPUT = Path.of("data", "clean", "normattiva_updates.tsv");
    private static final Path DEFAULT_RELATIONS_OUTPUT = Path.of("data", "clean", "normattiva_modifications_auto.tsv");
    private static final Path DEFAULT_RDF_OUTPUT = Path.of("data", "rdf", "normattiva_modifications_auto.ttl");

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final LegalActQueryService queryService;
    private final NormattivaRunner runner;
    private volatile NormattivaUpdateResult lastResult;

    @Autowired
    public NormattivaUpdateService(LegalActQueryService queryService) {
        this(queryService, NormattivaUpdateRunner::run);
    }

    NormattivaUpdateService(LegalActQueryService queryService, NormattivaRunner runner) {
        this.queryService = queryService;
        this.runner = runner;
    }

    public NormattivaUpdateResult runUpdate() throws IOException {
        if (!running.compareAndSet(false, true)) {
            NormattivaUpdateResult current = lastResult;
            return new NormattivaUpdateResult(
                    "RUNNING",
                    current == null ? null : current.startedAt(),
                    null,
                    0,
                    0,
                    "A Normattiva update is already running.",
                    current == null ? queryService.status() : current.dataStatus()
            );
        }

        OffsetDateTime startedAt = OffsetDateTime.now();
        lastResult = new NormattivaUpdateResult(
                "RUNNING",
                startedAt.toString(),
                null,
                0,
                0,
                "Normattiva update started.",
                queryService.status()
        );

        try {
            NormattivaUpdateRunner.Result runnerResult = runner.run(
                    DEFAULT_SOURCE_URL,
                    DEFAULT_UPDATES_OUTPUT,
                    DEFAULT_RELATIONS_OUTPUT,
                    DEFAULT_RDF_OUTPUT
            );
            NormattivaUpdateResult result = new NormattivaUpdateResult(
                    "COMPLETED",
                    startedAt.toString(),
                    OffsetDateTime.now().toString(),
                    runnerResult.updatesRead(),
                    runnerResult.relationRows(),
                    "Normattiva update completed.",
                    queryService.status()
            );
            lastResult = result;
            return result;
        } catch (IOException | RuntimeException exception) {
            NormattivaUpdateResult result = new NormattivaUpdateResult(
                    "FAILED",
                    startedAt.toString(),
                    OffsetDateTime.now().toString(),
                    0,
                    0,
                    exception.getMessage() == null ? "Normattiva update failed." : exception.getMessage(),
                    queryService.status()
            );
            lastResult = result;
            return result;
        } finally {
            running.set(false);
        }
    }

    public NormattivaUpdateResult lastResult() {
        return lastResult;
    }

    @FunctionalInterface
    interface NormattivaRunner {
        NormattivaUpdateRunner.Result run(String sourceUrl, Path updatesOutput, Path relationsOutput, Path rdfOutput) throws IOException;
    }
}
