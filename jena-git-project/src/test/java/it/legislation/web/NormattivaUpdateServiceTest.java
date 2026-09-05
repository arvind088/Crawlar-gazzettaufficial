package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void listsNormattivaDetailCandidates() throws Exception {
        LegalActQueryService queryService = new LegalActQueryService(
                List.of(tempDir.resolve("missing.ttl"))
        );
        Path detailsOutput = tempDir.resolve("normattiva_details.tsv");
        Files.write(detailsOutput, List.of(
                "codice_redazionale\tdata_gu\ttitolo_atto\tdenominazione_atto\tnumero_atto\tdetail_title\tdetail_subtitle\tact_type\tact_type_code\tact_date\tact_number\tpublication_date\tforce_start_date\tforce_end_date\ttext_in_force\tarticle_html\tendpoint\tfetched_at",
                "005G0104\t2005-05-16\tCodice dell'amministrazione digitale\tDECRETO LEGISLATIVO\t82\tCodice dell'amministrazione digitale\tTesto vigente\tDECRETO LEGISLATIVO\tDLGS\t2005-03-07\t82\t2005-05-16\t2025-03-20\t\tvigente\t<p>Articolo 1</p>\thttps://api.normattiva.it/t/normattiva.api/api/v1/atto/dettaglio-atto\t2026-08-29T10:00:00Z"
        ), StandardCharsets.UTF_8);

        try {
            NormattivaUpdateService service = new NormattivaUpdateService(
                    queryService,
                    (sourceUrl, updatesPath, relationsOutput, rdfOutput) -> null,
                    tempDir.resolve("updates.tsv"),
                    detailsOutput,
                    tempDir.resolve("relations.tsv"),
                    tempDir.resolve("relations.ttl")
            );

            List<NormattivaDetailCandidate> details = service.listDetailCandidates(10);

            assertEquals(1, details.size());
            NormattivaDetailCandidate detail = details.get(0);
            assertEquals("005G0104", detail.code());
            assertEquals("Codice dell'amministrazione digitale", detail.detailTitle());
            assertEquals("2005-03-07", detail.actDate());
            assertEquals("2025-03-20", detail.forceStartDate());
            assertTrue(detail.articleHtml().contains("Articolo 1"));
        } finally {
            queryService.closeForTests();
        }
    }

    @Test
    void returnsEmptyDetailCandidatesWhenFileIsMissing() throws Exception {
        LegalActQueryService queryService = new LegalActQueryService(
                List.of(tempDir.resolve("missing.ttl"))
        );
        try {
            NormattivaUpdateService service = new NormattivaUpdateService(
                    queryService,
                    (sourceUrl, updatesPath, relationsOutput, rdfOutput) -> null,
                    tempDir.resolve("updates.tsv"),
                    tempDir.resolve("missing-details.tsv"),
                    tempDir.resolve("relations.tsv"),
                    tempDir.resolve("relations.ttl")
            );

            assertTrue(service.listDetailCandidates(10).isEmpty());
        } finally {
            queryService.closeForTests();
        }
    }

    @Test
    void listsNormattivaRelationEvidenceCandidates() throws Exception {
        LegalActQueryService queryService = new LegalActQueryService(
                List.of(tempDir.resolve("missing.ttl"))
        );
        Path evidenceOutput = tempDir.resolve("normattiva_relation_evidence.tsv");
        Files.write(evidenceOutput, List.of(
                "codice_redazionale\tdata_gu\ttitolo_atto\tdetail_title\tevidence_type\tevidence_text",
                "005G0104\t2005-05-16\tCodice dell'amministrazione digitale\tCodice dell'amministrazione digitale\tconversion\tIl decreto-legge e' convertito, con modificazioni."
        ), StandardCharsets.UTF_8);

        try {
            NormattivaUpdateService service = new NormattivaUpdateService(
                    queryService,
                    (sourceUrl, updatesPath, relationsOutput, rdfOutput) -> null,
                    NormattivaUpdateRunner::runDetails,
                    tempDir.resolve("updates.tsv"),
                    tempDir.resolve("details.tsv"),
                    evidenceOutput,
                    tempDir.resolve("relations.tsv"),
                    tempDir.resolve("relations.ttl")
            );

            List<NormattivaRelationEvidenceCandidate> evidence = service.listRelationEvidence(10);

            assertEquals(1, evidence.size());
            NormattivaRelationEvidenceCandidate row = evidence.get(0);
            assertEquals("005G0104", row.code());
            assertEquals("2005-05-16", row.gazzettaDate());
            assertEquals("conversion", row.evidenceType());
            assertTrue(row.evidenceText().contains("convertito"));
        } finally {
            queryService.closeForTests();
        }
    }

    @Test
    void returnsEmptyRelationEvidenceWhenFileIsMissing() throws Exception {
        LegalActQueryService queryService = new LegalActQueryService(
                List.of(tempDir.resolve("missing.ttl"))
        );
        try {
            NormattivaUpdateService service = new NormattivaUpdateService(
                    queryService,
                    (sourceUrl, updatesPath, relationsOutput, rdfOutput) -> null,
                    NormattivaUpdateRunner::runDetails,
                    tempDir.resolve("updates.tsv"),
                    tempDir.resolve("details.tsv"),
                    tempDir.resolve("missing-evidence.tsv"),
                    tempDir.resolve("relations.tsv"),
                    tempDir.resolve("relations.ttl")
            );

            assertTrue(service.listRelationEvidence(10).isEmpty());
        } finally {
            queryService.closeForTests();
        }
    }

    @Test
    void runsNormattivaDetailFetch() throws Exception {
        LegalActQueryService queryService = new LegalActQueryService(
                List.of(tempDir.resolve("missing.ttl"))
        );
        Path updatesOutput = tempDir.resolve("updates.tsv");
        Path detailsOutput = tempDir.resolve("details.tsv");
        AtomicReference<Path> requestedUpdatesPath = new AtomicReference<>();
        AtomicReference<Path> requestedDetailsPath = new AtomicReference<>();
        AtomicInteger requestedLimit = new AtomicInteger();

        try {
            NormattivaUpdateService service = new NormattivaUpdateService(
                    queryService,
                    (sourceUrl, updatesPath, relationsOutput, rdfOutput) -> null,
                    (sourceUrl, updatesPath, detailsPath, limit) -> {
                        requestedUpdatesPath.set(updatesPath);
                        requestedDetailsPath.set(detailsPath);
                        requestedLimit.set(limit);
                        return new NormattivaUpdateRunner.DetailFetchResult(
                                sourceUrl,
                                2,
                                1,
                                detailsPath.toString()
                        );
                    },
                    updatesOutput,
                    detailsOutput,
                    tempDir.resolve("relations.tsv"),
                    tempDir.resolve("relations.ttl")
            );

            NormattivaDetailFetchResult result = service.runDetailFetch(10);

            assertEquals("COMPLETED", result.state());
            assertEquals(2, result.candidatesRead());
            assertEquals(1, result.detailsWritten());
            assertEquals(updatesOutput, requestedUpdatesPath.get());
            assertEquals(detailsOutput, requestedDetailsPath.get());
            assertEquals(10, requestedLimit.get());
        } finally {
            queryService.closeForTests();
        }
    }

    @Test
    void runsNormattivaEvidenceScan() throws Exception {
        LegalActQueryService queryService = new LegalActQueryService(
                List.of(tempDir.resolve("missing.ttl"))
        );
        Path detailsOutput = tempDir.resolve("details.tsv");
        Path evidenceOutput = tempDir.resolve("evidence.tsv");
        AtomicReference<Path> requestedDetailsPath = new AtomicReference<>();
        AtomicReference<Path> requestedEvidencePath = new AtomicReference<>();
        AtomicInteger requestedLimit = new AtomicInteger();

        try {
            NormattivaUpdateService service = new NormattivaUpdateService(
                    queryService,
                    (sourceUrl, updatesPath, relationsOutput, rdfOutput) -> null,
                    NormattivaUpdateRunner::runDetails,
                    (detailsPath, evidencePath, limit) -> {
                        requestedDetailsPath.set(detailsPath);
                        requestedEvidencePath.set(evidencePath);
                        requestedLimit.set(limit);
                        return new NormattivaUpdateRunner.EvidenceScanResult(
                                detailsPath.toString(),
                                4,
                                2,
                                evidencePath.toString()
                        );
                    },
                    tempDir.resolve("updates.tsv"),
                    detailsOutput,
                    evidenceOutput,
                    tempDir.resolve("relations.tsv"),
                    tempDir.resolve("relations.ttl")
            );

            NormattivaEvidenceScanResult result = service.runEvidenceScan(10);

            assertEquals("COMPLETED", result.state());
            assertEquals(4, result.detailsRead());
            assertEquals(2, result.evidenceRows());
            assertEquals(detailsOutput, requestedDetailsPath.get());
            assertEquals(evidenceOutput, requestedEvidencePath.get());
            assertEquals(10, requestedLimit.get());
        } finally {
            queryService.closeForTests();
        }
    }

    @Test
    void listsNormattivaRelationCandidates() throws Exception {
        LegalActQueryService queryService = new LegalActQueryService(
                List.of(tempDir.resolve("missing.ttl"))
        );
        Path relationCandidatesOutput = tempDir.resolve("normattiva_relation_candidates.tsv");
        Files.write(relationCandidatesOutput, List.of(
                "source_uri\ttarget_uri\trelation_type\tevidence_type\tevidence_text\treview_status",
                "https://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg\thttps://www.gazzettaufficiale.it/eli/id/2025/03/01/25G00028/sg\teli:commences\tconversion\tConversion evidence\tneeds_review"
        ), StandardCharsets.UTF_8);

        try {
            NormattivaUpdateService service = new NormattivaUpdateService(
                    queryService,
                    (sourceUrl, updatesPath, relationsOutput, rdfOutput) -> null,
                    NormattivaUpdateRunner::runDetails,
                    NormattivaUpdateRunner::runEvidenceScan,
                    tempDir.resolve("updates.tsv"),
                    tempDir.resolve("details.tsv"),
                    tempDir.resolve("evidence.tsv"),
                    relationCandidatesOutput,
                    tempDir.resolve("import-updates.json"),
                    tempDir.resolve("import-details.tsv"),
                    tempDir.resolve("relations.tsv"),
                    tempDir.resolve("relations.ttl")
            );

            List<NormattivaRelationCandidate> candidates = service.listRelationCandidates(10);

            assertEquals(1, candidates.size());
            assertEquals("eli:commences", candidates.get(0).relationType());
            assertEquals("needs_review", candidates.get(0).reviewStatus());
        } finally {
            queryService.closeForTests();
        }
    }

}
