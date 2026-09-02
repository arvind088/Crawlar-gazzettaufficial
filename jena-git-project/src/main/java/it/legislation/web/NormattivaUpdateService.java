package it.legislation.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import it.legislation.crawler.NormattivaUpdateRunner;

@Service
public class NormattivaUpdateService {

    private static final String DEFAULT_SOURCE_URL = "https://api.normattiva.it/t/normattiva.api";
    private static final Path DEFAULT_UPDATES_OUTPUT = Path.of("data", "clean", "normattiva_updates.tsv");
    private static final Path DEFAULT_DETAILS_OUTPUT = Path.of("data", "clean", "normattiva_details.tsv");
    private static final Path DEFAULT_EVIDENCE_OUTPUT = Path.of("data", "clean", "normattiva_relation_evidence.tsv");
    private static final Path DEFAULT_RELATION_CANDIDATES_OUTPUT = Path.of("data", "clean", "normattiva_relation_candidates.tsv");
    private static final Path DEFAULT_UPDATES_IMPORT = Path.of("data", "import", "normattiva_updates.json");
    private static final Path DEFAULT_DETAILS_IMPORT = Path.of("data", "import", "normattiva_details.json");
    private static final Path DEFAULT_RELATIONS_OUTPUT = Path.of("data", "clean", "normattiva_modifications_auto.tsv");
    private static final Path DEFAULT_RDF_OUTPUT = Path.of("data", "rdf", "normattiva_modifications_auto.ttl");

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean detailRunning = new AtomicBoolean(false);
    private final AtomicBoolean evidenceRunning = new AtomicBoolean(false);
    private final LegalActQueryService queryService;
    private final NormattivaRunner runner;
    private final NormattivaDetailRunner detailRunner;
    private final NormattivaEvidenceRunner evidenceRunner;
    private final Path updatesOutput;
    private final Path detailsOutput;
    private final Path evidenceOutput;
    private final Path relationCandidatesOutput;
    private final Path updatesImport;
    private final Path detailsImport;
    private final Path relationsOutput;
    private final Path rdfOutput;
    private volatile NormattivaUpdateResult lastResult;

    @Autowired
    public NormattivaUpdateService(LegalActQueryService queryService) {
        this(queryService, NormattivaUpdateRunner::run, NormattivaUpdateRunner::runDetails, NormattivaUpdateRunner::runEvidenceScan);
    }

    NormattivaUpdateService(LegalActQueryService queryService, NormattivaRunner runner) {
        this(queryService, runner, NormattivaUpdateRunner::runDetails, NormattivaUpdateRunner::runEvidenceScan);
    }

    NormattivaUpdateService(
            LegalActQueryService queryService,
            NormattivaRunner runner,
            NormattivaDetailRunner detailRunner
    ) {
        this(queryService, runner, detailRunner, NormattivaUpdateRunner::runEvidenceScan);
    }

    NormattivaUpdateService(
            LegalActQueryService queryService,
            NormattivaRunner runner,
            NormattivaDetailRunner detailRunner,
            NormattivaEvidenceRunner evidenceRunner
    ) {
        this(queryService, runner, detailRunner, evidenceRunner, DEFAULT_UPDATES_OUTPUT, DEFAULT_DETAILS_OUTPUT, DEFAULT_EVIDENCE_OUTPUT, DEFAULT_RELATION_CANDIDATES_OUTPUT, DEFAULT_UPDATES_IMPORT, DEFAULT_DETAILS_IMPORT, DEFAULT_RELATIONS_OUTPUT, DEFAULT_RDF_OUTPUT);
    }

    NormattivaUpdateService(
            LegalActQueryService queryService,
            NormattivaRunner runner,
            Path updatesOutput,
            Path relationsOutput,
            Path rdfOutput
    ) {
        this(queryService, runner, NormattivaUpdateRunner::runDetails, updatesOutput, DEFAULT_DETAILS_OUTPUT, DEFAULT_EVIDENCE_OUTPUT, DEFAULT_RELATION_CANDIDATES_OUTPUT, DEFAULT_UPDATES_IMPORT, DEFAULT_DETAILS_IMPORT, relationsOutput, rdfOutput);
    }

    NormattivaUpdateService(
            LegalActQueryService queryService,
            NormattivaRunner runner,
            Path updatesOutput,
            Path detailsOutput,
            Path relationsOutput,
            Path rdfOutput
    ) {
        this(queryService, runner, NormattivaUpdateRunner::runDetails, updatesOutput, detailsOutput, DEFAULT_EVIDENCE_OUTPUT, DEFAULT_RELATION_CANDIDATES_OUTPUT, DEFAULT_UPDATES_IMPORT, DEFAULT_DETAILS_IMPORT, relationsOutput, rdfOutput);
    }

    NormattivaUpdateService(
            LegalActQueryService queryService,
            NormattivaRunner runner,
            NormattivaDetailRunner detailRunner,
            Path updatesOutput,
            Path detailsOutput,
            Path relationsOutput,
            Path rdfOutput
    ) {
        this(queryService, runner, detailRunner, updatesOutput, detailsOutput, DEFAULT_EVIDENCE_OUTPUT, DEFAULT_RELATION_CANDIDATES_OUTPUT, DEFAULT_UPDATES_IMPORT, DEFAULT_DETAILS_IMPORT, relationsOutput, rdfOutput);
    }

    NormattivaUpdateService(
            LegalActQueryService queryService,
            NormattivaRunner runner,
            NormattivaDetailRunner detailRunner,
            Path updatesOutput,
            Path detailsOutput,
            Path evidenceOutput,
            Path relationsOutput,
            Path rdfOutput
    ) {
        this(queryService, runner, detailRunner, NormattivaUpdateRunner::runEvidenceScan, updatesOutput, detailsOutput, evidenceOutput, DEFAULT_RELATION_CANDIDATES_OUTPUT, DEFAULT_UPDATES_IMPORT, DEFAULT_DETAILS_IMPORT, relationsOutput, rdfOutput);
    }

    NormattivaUpdateService(
            LegalActQueryService queryService,
            NormattivaRunner runner,
            NormattivaDetailRunner detailRunner,
            NormattivaEvidenceRunner evidenceRunner,
            Path updatesOutput,
            Path detailsOutput,
            Path evidenceOutput,
            Path relationsOutput,
            Path rdfOutput
    ) {
        this(queryService, runner, detailRunner, evidenceRunner, updatesOutput, detailsOutput, evidenceOutput, DEFAULT_RELATION_CANDIDATES_OUTPUT, DEFAULT_UPDATES_IMPORT, DEFAULT_DETAILS_IMPORT, relationsOutput, rdfOutput);
    }

    NormattivaUpdateService(
            LegalActQueryService queryService,
            NormattivaRunner runner,
            NormattivaDetailRunner detailRunner,
            Path updatesOutput,
            Path detailsOutput,
            Path evidenceOutput,
            Path relationCandidatesOutput,
            Path updatesImport,
            Path detailsImport,
            Path relationsOutput,
            Path rdfOutput
    ) {
        this(queryService, runner, detailRunner, NormattivaUpdateRunner::runEvidenceScan, updatesOutput, detailsOutput, evidenceOutput, relationCandidatesOutput, updatesImport, detailsImport, relationsOutput, rdfOutput);
    }

    NormattivaUpdateService(
            LegalActQueryService queryService,
            NormattivaRunner runner,
            NormattivaDetailRunner detailRunner,
            NormattivaEvidenceRunner evidenceRunner,
            Path updatesOutput,
            Path detailsOutput,
            Path evidenceOutput,
            Path relationCandidatesOutput,
            Path updatesImport,
            Path detailsImport,
            Path relationsOutput,
            Path rdfOutput
    ) {
        this.queryService = queryService;
        this.runner = runner;
        this.detailRunner = detailRunner;
        this.evidenceRunner = evidenceRunner;
        this.updatesOutput = updatesOutput;
        this.detailsOutput = detailsOutput;
        this.evidenceOutput = evidenceOutput;
        this.relationCandidatesOutput = relationCandidatesOutput;
        this.updatesImport = updatesImport;
        this.detailsImport = detailsImport;
        this.relationsOutput = relationsOutput;
        this.rdfOutput = rdfOutput;
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
                    updatesOutput,
                    relationsOutput,
                    rdfOutput
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

    public NormattivaDetailFetchResult runDetailFetch(int limit) {
        if (!detailRunning.compareAndSet(false, true)) {
            return new NormattivaDetailFetchResult(
                    "RUNNING",
                    null,
                    null,
                    0,
                    0,
                    "A Normattiva detail fetch is already running.",
                    detailsOutput.toAbsolutePath().normalize().toString()
            );
        }

        OffsetDateTime startedAt = OffsetDateTime.now();
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        try {
            NormattivaUpdateRunner.DetailFetchResult runnerResult = detailRunner.run(
                    DEFAULT_SOURCE_URL,
                    updatesOutput,
                    detailsOutput,
                    boundedLimit
            );
            return new NormattivaDetailFetchResult(
                    "COMPLETED",
                    startedAt.toString(),
                    OffsetDateTime.now().toString(),
                    runnerResult.candidatesRead(),
                    runnerResult.detailsWritten(),
                    "Normattiva detail fetch completed.",
                    runnerResult.detailsPath()
            );
        } catch (IOException | RuntimeException exception) {
            return new NormattivaDetailFetchResult(
                    "FAILED",
                    startedAt.toString(),
                    OffsetDateTime.now().toString(),
                    0,
                    0,
                    exception.getMessage() == null ? "Normattiva detail fetch failed." : exception.getMessage(),
                    detailsOutput.toAbsolutePath().normalize().toString()
            );
        } finally {
            detailRunning.set(false);
        }
    }

    public NormattivaEvidenceScanResult runEvidenceScan(int limit) {
        if (!evidenceRunning.compareAndSet(false, true)) {
            return new NormattivaEvidenceScanResult(
                    "RUNNING",
                    null,
                    null,
                    0,
                    0,
                    "A Normattiva evidence scan is already running.",
                    evidenceOutput.toAbsolutePath().normalize().toString()
            );
        }

        OffsetDateTime startedAt = OffsetDateTime.now();
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        try {
            NormattivaUpdateRunner.EvidenceScanResult runnerResult = evidenceRunner.run(
                    detailsOutput,
                    evidenceOutput,
                    boundedLimit
            );
            return new NormattivaEvidenceScanResult(
                    "COMPLETED",
                    startedAt.toString(),
                    OffsetDateTime.now().toString(),
                    runnerResult.detailsRead(),
                    runnerResult.evidenceRows(),
                    "Normattiva evidence scan completed.",
                    runnerResult.evidencePath()
            );
        } catch (IOException | RuntimeException exception) {
            return new NormattivaEvidenceScanResult(
                    "FAILED",
                    startedAt.toString(),
                    OffsetDateTime.now().toString(),
                    0,
                    0,
                    exception.getMessage() == null ? "Normattiva evidence scan failed." : exception.getMessage(),
                    evidenceOutput.toAbsolutePath().normalize().toString()
            );
        } finally {
            evidenceRunning.set(false);
        }
    }

    public NormattivaImportResult importUpdateCandidates() {
        OffsetDateTime startedAt = OffsetDateTime.now();
        try {
            NormattivaUpdateRunner.ImportResult result = NormattivaUpdateRunner.importOpenDataUpdates(
                    importPath(updatesImport),
                    updatesOutput
            );
            return new NormattivaImportResult(
                    "COMPLETED",
                    startedAt.toString(),
                    OffsetDateTime.now().toString(),
                    result.rowsWritten(),
                    "Normattiva update import completed.",
                    result.inputPath(),
                    result.outputPath()
            );
        } catch (IOException | RuntimeException exception) {
            return new NormattivaImportResult(
                    "FAILED",
                    startedAt.toString(),
                    OffsetDateTime.now().toString(),
                    0,
                    exception.getMessage() == null ? "Normattiva update import failed." : exception.getMessage(),
                    updatesImport.toAbsolutePath().normalize().toString(),
                    updatesOutput.toAbsolutePath().normalize().toString()
            );
        }
    }

    public NormattivaImportResult importDetailCandidates() {
        OffsetDateTime startedAt = OffsetDateTime.now();
        try {
            NormattivaUpdateRunner.ImportResult result = NormattivaUpdateRunner.importActDetails(
                    importPath(detailsImport),
                    detailsOutput
            );
            return new NormattivaImportResult(
                    "COMPLETED",
                    startedAt.toString(),
                    OffsetDateTime.now().toString(),
                    result.rowsWritten(),
                    "Normattiva detail import completed.",
                    result.inputPath(),
                    result.outputPath()
            );
        } catch (IOException | RuntimeException exception) {
            return new NormattivaImportResult(
                    "FAILED",
                    startedAt.toString(),
                    OffsetDateTime.now().toString(),
                    0,
                    exception.getMessage() == null ? "Normattiva detail import failed." : exception.getMessage(),
                    detailsImport.toAbsolutePath().normalize().toString(),
                    detailsOutput.toAbsolutePath().normalize().toString()
            );
        }
    }

    public NormattivaRelationCandidateRunResult runRelationCandidateExtraction(int limit) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        try {
            NormattivaUpdateRunner.RelationCandidateResult result = NormattivaUpdateRunner.runRelationCandidateExtraction(
                    evidenceOutput,
                    relationCandidatesOutput,
                    boundedLimit
            );
            return new NormattivaRelationCandidateRunResult(
                    "COMPLETED",
                    startedAt.toString(),
                    OffsetDateTime.now().toString(),
                    result.evidenceRowsRead(),
                    result.candidatesWritten(),
                    "Normattiva relation candidate extraction completed.",
                    result.relationCandidatesPath()
            );
        } catch (IOException | RuntimeException exception) {
            return new NormattivaRelationCandidateRunResult(
                    "FAILED",
                    startedAt.toString(),
                    OffsetDateTime.now().toString(),
                    0,
                    0,
                    exception.getMessage() == null ? "Normattiva relation candidate extraction failed." : exception.getMessage(),
                    relationCandidatesOutput.toAbsolutePath().normalize().toString()
            );
        }
    }

    public List<NormattivaUpdateCandidate> listUpdateCandidates(int limit) throws IOException {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        if (!Files.exists(updatesOutput)) {
            return List.of();
        }

        List<String> lines = Files.readAllLines(updatesOutput, StandardCharsets.UTF_8);
        if (lines.size() < 2) {
            return List.of();
        }

        Map<String, Integer> header = headerIndex(lines.get(0));
        List<NormattivaUpdateCandidate> candidates = new ArrayList<>();
        for (int index = 1; index < lines.size() && candidates.size() < boundedLimit; index++) {
            if (lines.get(index).isBlank()) {
                continue;
            }
            List<String> fields = splitTsv(lines.get(index));
            candidates.add(candidateFromRow(header, fields));
        }
        return candidates;
    }

    public List<NormattivaDetailCandidate> listDetailCandidates(int limit) throws IOException {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        if (!Files.exists(detailsOutput)) {
            return List.of();
        }

        List<String> lines = Files.readAllLines(detailsOutput, StandardCharsets.UTF_8);
        if (lines.size() < 2) {
            return List.of();
        }

        Map<String, Integer> header = headerIndex(lines.get(0));
        List<NormattivaDetailCandidate> details = new ArrayList<>();
        for (int index = 1; index < lines.size() && details.size() < boundedLimit; index++) {
            if (lines.get(index).isBlank()) {
                continue;
            }
            List<String> fields = splitTsv(lines.get(index));
            details.add(detailFromRow(header, fields));
        }
        return details;
    }

    public List<NormattivaRelationEvidenceCandidate> listRelationEvidence(int limit) throws IOException {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        if (!Files.exists(evidenceOutput)) {
            return List.of();
        }

        List<String> lines = Files.readAllLines(evidenceOutput, StandardCharsets.UTF_8);
        if (lines.size() < 2) {
            return List.of();
        }

        Map<String, Integer> header = headerIndex(lines.get(0));
        List<NormattivaRelationEvidenceCandidate> evidence = new ArrayList<>();
        for (int index = 1; index < lines.size() && evidence.size() < boundedLimit; index++) {
            if (lines.get(index).isBlank()) {
                continue;
            }
            List<String> fields = splitTsv(lines.get(index));
            evidence.add(evidenceFromRow(header, fields));
        }
        return evidence;
    }

    public List<NormattivaRelationCandidate> listRelationCandidates(int limit) throws IOException {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        if (!Files.exists(relationCandidatesOutput)) {
            return List.of();
        }

        List<String> lines = Files.readAllLines(relationCandidatesOutput, StandardCharsets.UTF_8);
        if (lines.size() < 2) {
            return List.of();
        }

        Map<String, Integer> header = headerIndex(lines.get(0));
        List<NormattivaRelationCandidate> candidates = new ArrayList<>();
        for (int index = 1; index < lines.size() && candidates.size() < boundedLimit; index++) {
            if (lines.get(index).isBlank()) {
                continue;
            }
            List<String> fields = splitTsv(lines.get(index));
            candidates.add(relationCandidateFromRow(header, fields));
        }
        return candidates;
    }

    private static NormattivaUpdateCandidate candidateFromRow(Map<String, Integer> header, List<String> fields) {
        String title = firstNonBlank(field(header, fields, "titolo_atto"), field(header, fields, "title"));
        String actName = field(header, fields, "denominazione_atto");
        return new NormattivaUpdateCandidate(
                field(header, fields, "codice_redazionale"),
                firstNonBlank(title, actName),
                field(header, fields, "data_gu"),
                field(header, fields, "data_emanazione"),
                firstNonBlank(field(header, fields, "data_ultima_modifica"), field(header, fields, "update_date")),
                actName,
                field(header, fields, "numero_atto"),
                firstNonBlank(field(header, fields, "ultimi_atti_modificanti"), field(header, fields, "description")),
                firstNonBlank(field(header, fields, "endpoint"), field(header, fields, "normattiva_links")),
                field(header, fields, "fetched_at")
        );
    }

    private static NormattivaDetailCandidate detailFromRow(Map<String, Integer> header, List<String> fields) {
        return new NormattivaDetailCandidate(
                field(header, fields, "codice_redazionale"),
                field(header, fields, "data_gu"),
                field(header, fields, "titolo_atto"),
                field(header, fields, "denominazione_atto"),
                field(header, fields, "numero_atto"),
                field(header, fields, "detail_title"),
                field(header, fields, "detail_subtitle"),
                field(header, fields, "act_type"),
                field(header, fields, "act_type_code"),
                field(header, fields, "act_date"),
                field(header, fields, "act_number"),
                field(header, fields, "publication_date"),
                field(header, fields, "force_start_date"),
                field(header, fields, "force_end_date"),
                field(header, fields, "text_in_force"),
                field(header, fields, "article_html"),
                field(header, fields, "endpoint"),
                field(header, fields, "fetched_at")
        );
    }

    private static NormattivaRelationEvidenceCandidate evidenceFromRow(Map<String, Integer> header, List<String> fields) {
        return new NormattivaRelationEvidenceCandidate(
                field(header, fields, "codice_redazionale"),
                field(header, fields, "data_gu"),
                field(header, fields, "titolo_atto"),
                field(header, fields, "detail_title"),
                field(header, fields, "evidence_type"),
                field(header, fields, "evidence_text")
        );
    }

    private static NormattivaRelationCandidate relationCandidateFromRow(Map<String, Integer> header, List<String> fields) {
        return new NormattivaRelationCandidate(
                field(header, fields, "source_uri"),
                field(header, fields, "target_uri"),
                field(header, fields, "relation_type"),
                field(header, fields, "evidence_type"),
                field(header, fields, "evidence_text"),
                field(header, fields, "review_status")
        );
    }

    private static Map<String, Integer> headerIndex(String line) {
        List<String> headers = splitTsv(line);
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int position = 0; position < headers.size(); position++) {
            index.put(headers.get(position), position);
        }
        return index;
    }

    private static String field(Map<String, Integer> header, List<String> fields, String name) {
        Integer position = header.get(name);
        if (position == null || position >= fields.size()) {
            return "";
        }
        return fields.get(position);
    }

    private static List<String> splitTsv(String line) {
        String[] values = line.split("\t", -1);
        List<String> fields = new ArrayList<>(values.length);
        for (String value : values) {
            fields.add(unquote(value));
        }
        return fields;
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed;
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static Path importPath(Path preferred) {
        if (Files.exists(preferred)) {
            return preferred;
        }
        String fileName = preferred.getFileName() == null ? "" : preferred.getFileName().toString();
        String alternateName;
        if (fileName.endsWith(".json")) {
            alternateName = fileName.substring(0, fileName.length() - 5) + ".tsv";
        } else if (fileName.endsWith(".tsv")) {
            alternateName = fileName.substring(0, fileName.length() - 4) + ".json";
        } else {
            return preferred;
        }
        Path parent = preferred.getParent();
        Path alternate = parent == null ? Path.of(alternateName) : parent.resolve(alternateName);
        return Files.exists(alternate) ? alternate : preferred;
    }

    @FunctionalInterface
    interface NormattivaRunner {
        NormattivaUpdateRunner.Result run(String sourceUrl, Path updatesOutput, Path relationsOutput, Path rdfOutput) throws IOException;
    }

    @FunctionalInterface
    interface NormattivaDetailRunner {
        NormattivaUpdateRunner.DetailFetchResult run(String sourceUrl, Path updatesInput, Path detailsOutput, int limit) throws IOException;
    }

    @FunctionalInterface
    interface NormattivaEvidenceRunner {
        NormattivaUpdateRunner.EvidenceScanResult run(Path detailsInput, Path evidenceOutput, int limit) throws IOException;
    }
}
