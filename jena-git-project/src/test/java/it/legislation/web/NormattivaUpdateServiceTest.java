package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import it.legislation.crawler.NormattivaUpdateRunner;

class NormattivaUpdateServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void runsNormattivaUpdateAndStoresLatestResult() throws Exception {
        LegalActQueryService queryService = new LegalActQueryService(List.of(tempDir.resolve("missing.ttl")));
        NormattivaUpdateService service = new NormattivaUpdateService(
                queryService,
                (sourceUrl, updatesOutput, relationsOutput, rdfOutput) -> new NormattivaUpdateRunner.Result(
                        sourceUrl,
                        8,
                        3,
                        updatesOutput.toString(),
                        relationsOutput.toString(),
                        rdfOutput.toString()
                )
        );

        NormattivaUpdateResult result = service.runUpdate();

        assertEquals("COMPLETED", result.state());
        assertEquals(8, result.updatesRead());
        assertEquals(3, result.relationRows());
        assertEquals(result, service.lastResult());
    }
}
