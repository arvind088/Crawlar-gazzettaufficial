package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        LegalActQueryService queryService = new LegalActQueryService(
                List.of(tempDir.resolve("missing.ttl"))
        );
        try {
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
        } finally {
            queryService.closeForTests();
        }
    }

    @Test
    void listsOfficialOpenDataUpdateCandidates() throws Exception {
        LegalActQueryService queryService = new LegalActQueryService(
                List.of(tempDir.resolve("missing.ttl"))
        );
        Path updatesOutput = tempDir.resolve("normattiva_updates.tsv");
        Files.write(updatesOutput, List.of(
                "codice_redazionale\tdata_gu\tdenominazione_atto\tnumero_atto\ttitolo_atto\tdata_emanazione\tdata_ultima_modifica\tultimi_atti_modificanti\tendpoint\tfetched_at",
                "005G0104\t2005-05-16\tDECRETO LEGISLATIVO\t82\tCodice dell'amministrazione digitale\t2005-03-07\t2025-03-20\tLegge 2025 n. 52\thttps://api.normattiva.it/t/normattiva.api/api/v1/ricerca/aggiornati\t2026-08-29T10:00:00Z"
        ), StandardCharsets.UTF_8);

        try {
            NormattivaUpdateService service = new NormattivaUpdateService(
                    queryService,
                    (sourceUrl, updatesPath, relationsOutput, rdfOutput) -> null,
                    updatesOutput,
                    tempDir.resolve("relations.tsv"),
                    tempDir.resolve("relations.ttl")
            );

            List<NormattivaUpdateCandidate> candidates = service.listUpdateCandidates(10);

            assertEquals(1, candidates.size());
            NormattivaUpdateCandidate candidate = candidates.get(0);
            assertEquals("005G0104", candidate.code());
            assertEquals("Codice dell'amministrazione digitale", candidate.title());
            assertEquals("2005-05-16", candidate.gazzettaDate());
            assertEquals("2025-03-20", candidate.lastModifiedDate());
            assertEquals("Legge 2025 n. 52", candidate.modifyingActs());
        } finally {
            queryService.closeForTests();
        }
    }

    @Test
    void returnsEmptyUpdateCandidatesWhenFileIsMissing() throws Exception {
        LegalActQueryService queryService = new LegalActQueryService(
                List.of(tempDir.resolve("missing.ttl"))
        );
        try {
            NormattivaUpdateService service = new NormattivaUpdateService(
                    queryService,
                    (sourceUrl, updatesPath, relationsOutput, rdfOutput) -> null,
                    tempDir.resolve("missing-updates.tsv"),
                    tempDir.resolve("relations.tsv"),
                    tempDir.resolve("relations.ttl")
            );

            assertTrue(service.listUpdateCandidates(10).isEmpty());
        } finally {
            queryService.closeForTests();
        }
    }

}
