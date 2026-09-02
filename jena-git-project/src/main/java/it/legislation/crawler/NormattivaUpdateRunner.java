package it.legislation.crawler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class NormattivaUpdateRunner {

    private static final String DEFAULT_SOURCE_URL = "https://api.normattiva.it/t/normattiva.api";
    private static final Path DEFAULT_UPDATES_OUTPUT = Path.of("data", "clean", "normattiva_updates.tsv");
    private static final Path DEFAULT_DETAILS_OUTPUT = Path.of("data", "clean", "normattiva_details.tsv");
    private static final Path DEFAULT_EVIDENCE_OUTPUT = Path.of("data", "clean", "normattiva_relation_evidence.tsv");
    private static final Path DEFAULT_RELATION_CANDIDATES_OUTPUT = Path.of("data", "clean", "normattiva_relation_candidates.tsv");
    private static final Path DEFAULT_UPDATES_IMPORT = Path.of("data", "import", "normattiva_updates.json");
    private static final Path DEFAULT_DETAILS_IMPORT = Path.of("data", "import", "normattiva_details.tsv");
    private static final Path DEFAULT_RELATIONS_OUTPUT = Path.of("data", "clean", "normattiva_modifications_auto.tsv");
    private static final Path DEFAULT_RDF_OUTPUT = Path.of("data", "rdf", "normattiva_modifications_auto.ttl");
    private static final String USER_AGENT = "Crawlar-gazzettaufficial thesis demo; https://github.com/arvind088/Crawlar-gazzettaufficial";
    private static final Pattern ELI_HTTP_URI = Pattern.compile("https?://www\\.gazzettaufficiale\\.it/eli/id/\\d{4}/\\d{2}/\\d{2}/[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ITALIAN_DATE = Pattern.compile(
            "\\b\\d{1,2}\\s+(?:gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre)\\s+\\d{4}\\b",
            Pattern.CASE_INSENSITIVE
    );

    public static void main(String[] args) throws IOException {
        String sourceUrl = valueOrDefault("NORMATTIVA_SOURCE_URL", DEFAULT_SOURCE_URL);
        Path updatesOutput = Path.of(valueOrDefault("NORMATTIVA_UPDATES_OUTPUT", DEFAULT_UPDATES_OUTPUT.toString()));
        Path detailsOutput = Path.of(valueOrDefault("NORMATTIVA_DETAILS_OUTPUT", DEFAULT_DETAILS_OUTPUT.toString()));
        Path evidenceOutput = Path.of(valueOrDefault("NORMATTIVA_EVIDENCE_OUTPUT", DEFAULT_EVIDENCE_OUTPUT.toString()));
        Path relationCandidatesOutput = Path.of(valueOrDefault("NORMATTIVA_RELATION_CANDIDATES_OUTPUT", DEFAULT_RELATION_CANDIDATES_OUTPUT.toString()));
        Path relationsOutput = Path.of(valueOrDefault("NORMATTIVA_RELATIONS_OUTPUT", DEFAULT_RELATIONS_OUTPUT.toString()));
        Path rdfOutput = Path.of(valueOrDefault("NORMATTIVA_RDF_OUTPUT", DEFAULT_RDF_OUTPUT.toString()));

        if (args.length > 0 && "import-updates".equalsIgnoreCase(args[0])) {
            Path importInput = args.length > 1 ? Path.of(args[1]) : Path.of(valueOrDefault("NORMATTIVA_UPDATES_IMPORT", DEFAULT_UPDATES_IMPORT.toString()));
            ImportResult result = importOpenDataUpdates(importInput, updatesOutput);
            System.out.println("Imported Normattiva update rows: " + result.rowsWritten());
            System.out.println("Updates TSV: " + result.outputPath());
            return;
        }

        if (args.length > 0 && "import-details".equalsIgnoreCase(args[0])) {
            Path importInput = args.length > 1 ? Path.of(args[1]) : Path.of(valueOrDefault("NORMATTIVA_DETAILS_IMPORT", DEFAULT_DETAILS_IMPORT.toString()));
            ImportResult result = importActDetails(importInput, detailsOutput);
            System.out.println("Imported Normattiva detail rows: " + result.rowsWritten());
            System.out.println("Details TSV: " + result.outputPath());
            return;
        }

        if (args.length > 0 && "details".equalsIgnoreCase(args[0])) {
            DetailFetchResult result = fetchAndWriteActDetails(
                    sourceUrl,
                    updatesOutput,
                    detailsOutput,
                    detailLimit(),
                    NormattivaUpdateRunner::postJson
            );
            System.out.println("Read Normattiva update candidates: " + result.candidatesRead());
            System.out.println("Wrote Normattiva detail rows: " + result.detailsWritten());
            System.out.println("Details TSV: " + detailsOutput.toAbsolutePath().normalize());
            return;
        }

        if (args.length > 0 && "evidence".equalsIgnoreCase(args[0])) {
            EvidenceScanResult result = runEvidenceScan(detailsOutput, evidenceOutput, evidenceLimit());
            System.out.println("Scanned Normattiva detail rows: " + result.detailsRead());
            System.out.println("Wrote relation evidence rows: " + result.evidenceRows());
            System.out.println("Evidence TSV: " + evidenceOutput.toAbsolutePath().normalize());
            return;
        }

        if (args.length > 0 && "candidates".equalsIgnoreCase(args[0])) {
            RelationCandidateResult result = runRelationCandidateExtraction(evidenceOutput, relationCandidatesOutput, evidenceLimit());
            System.out.println("Read relation evidence rows: " + result.evidenceRowsRead());
            System.out.println("Wrote relation candidate rows: " + result.candidatesWritten());
            System.out.println("Relation candidates TSV: " + relationCandidatesOutput.toAbsolutePath().normalize());
            return;
        }

        Result result = run(sourceUrl, updatesOutput, relationsOutput, rdfOutput);

        System.out.println("Downloaded Normattiva updates: " + result.updatesRead());
        System.out.println("Inferred Normattiva relation rows: " + result.relationRows());
        System.out.println("Updates TSV: " + updatesOutput.toAbsolutePath().normalize());
        System.out.println("Relations TSV: " + relationsOutput.toAbsolutePath().normalize());
        System.out.println("RDF output: " + rdfOutput.toAbsolutePath().normalize());
    }

    public static Result run(
            String sourceUrl,
            Path updatesOutput,
            Path relationsOutput,
            Path rdfOutput
    ) throws IOException {
        OffsetDateTime end = envDate("NORMATTIVA_UPDATE_END")
                .orElse(OffsetDateTime.now(ZoneOffset.UTC));
        OffsetDateTime start = envDate("NORMATTIVA_UPDATE_START")
                .orElse(end.minusDays(lookbackDays()));

        return runOpenData(
                sourceUrl,
                start,
                end,
                updatesOutput,
                relationsOutput,
                rdfOutput,
                NormattivaUpdateRunner::postJson
        );
    }

    public static EvidenceScanResult runEvidenceScan(
            Path detailsInput,
            Path evidenceOutput,
            int limit
    ) throws IOException {
        List<NormattivaRelationEvidence> evidence = scanRelationEvidence(detailsInput, limit);
        writeRelationEvidence(evidence, evidenceOutput);
        return new EvidenceScanResult(
                detailsInput.toAbsolutePath().normalize().toString(),
                countDataRows(detailsInput),
                evidence.size(),
                evidenceOutput.toAbsolutePath().normalize().toString()
        );
    }

    public static DetailFetchResult runDetails(
            String sourceUrl,
            Path updatesInput,
            Path detailsOutput,
            int limit
    ) throws IOException {
        return fetchAndWriteActDetails(
                sourceUrl,
                updatesInput,
                detailsOutput,
                limit,
                NormattivaUpdateRunner::postJson
        );
    }

    public static ImportResult importOpenDataUpdates(Path input, Path updatesOutput) throws IOException {
        requireExistingInput(input);
        List<NormattivaOpenDataUpdate> updates;
        if (isJson(input)) {
            updates = parseOpenDataUpdates(Files.readString(input, StandardCharsets.UTF_8));
        } else {
            updates = readOpenDataUpdates(input);
        }
        writeOpenDataUpdates(updates, updatesOutput, input.toAbsolutePath().normalize().toUri().toString(), OffsetDateTime.now(ZoneOffset.UTC));
        return new ImportResult(
                input.toAbsolutePath().normalize().toString(),
                updates.size(),
                updatesOutput.toAbsolutePath().normalize().toString()
        );
    }

    public static ImportResult importActDetails(Path input, Path detailsOutput) throws IOException {
        requireExistingInput(input);
        int rowsWritten;
        if (isJson(input)) {
            List<NormattivaFetchedActDetail> details = parseImportedActDetails(
                    Files.readString(input, StandardCharsets.UTF_8),
                    input.toAbsolutePath().normalize().toUri().toString(),
                    OffsetDateTime.now(ZoneOffset.UTC).toString()
            );
            writeActDetails(details, detailsOutput);
            rowsWritten = details.size();
        } else {
            createParent(detailsOutput);
            Files.copy(input, detailsOutput, StandardCopyOption.REPLACE_EXISTING);
            rowsWritten = countDataRows(detailsOutput);
        }
        return new ImportResult(
                input.toAbsolutePath().normalize().toString(),
                rowsWritten,
                detailsOutput.toAbsolutePath().normalize().toString()
        );
    }

    public static RelationCandidateResult runRelationCandidateExtraction(
            Path evidenceInput,
            Path relationCandidatesOutput,
            int limit
    ) throws IOException {
        List<NormattivaRelationCandidate> candidates = extractRelationCandidates(evidenceInput, limit);
        writeRelationCandidates(candidates, relationCandidatesOutput);
        return new RelationCandidateResult(
                evidenceInput.toAbsolutePath().normalize().toString(),
                countDataRows(evidenceInput),
                candidates.size(),
                relationCandidatesOutput.toAbsolutePath().normalize().toString()
        );
    }

    static Result runOpenData(
            String sourceUrl,
            OffsetDateTime start,
            OffsetDateTime end,
            Path updatesOutput,
            Path relationsOutput,
            Path rdfOutput,
            HttpJsonClient client
    ) throws IOException {
        String endpoint = updatedActsEndpoint(sourceUrl);
        String requestBody = updatedActsRequestBody(start, end);
        String json = client.postJson(endpoint, requestBody);
        List<NormattivaOpenDataUpdate> updates = parseOpenDataUpdates(json);

        writeOpenDataUpdates(updates, updatesOutput, endpoint, fetchedAt(start, end));

        // The OpenData "aggiornati" endpoint discovers changed acts. Relation RDF is intentionally
        // left untouched until a later detail/related-acts step can derive links without guessing.
        int relationRows = 0;

        return new Result(
                endpoint,
                updates.size(),
                relationRows,
                updatesOutput.toAbsolutePath().normalize().toString(),
                relationsOutput.toAbsolutePath().normalize().toString(),
                rdfOutput.toAbsolutePath().normalize().toString()
        );
    }

    static String updatedActsEndpoint(String sourceUrl) {
        String base = sourceUrl == null || sourceUrl.isBlank() ? DEFAULT_SOURCE_URL : sourceUrl.trim();
        return base.replaceAll("/+$", "") + "/api/v1/ricerca/aggiornati";
    }

    static String actDetailEndpoint(String sourceUrl) {
        String base = sourceUrl == null || sourceUrl.isBlank() ? DEFAULT_SOURCE_URL : sourceUrl.trim();
        return base.replaceAll("/+$", "") + "/api/v1/atto/dettaglio-atto";
    }

    static String updatedActsRequestBody(OffsetDateTime start, OffsetDateTime end) {
        String safeStart = start == null ? OffsetDateTime.now(ZoneOffset.UTC).minusDays(lookbackDays()).toString() : start.toString();
        String safeEnd = end == null ? OffsetDateTime.now(ZoneOffset.UTC).toString() : end.toString();
        return """
                {"dataInizioAggiornamento":"%s","dataFineAggiornamento":"%s"}
                """.formatted(safeStart, safeEnd).trim();
    }

    static String actDetailRequestBody(NormattivaOpenDataUpdate update) {
        return """
                {"dataGU":"%s","codiceRedazionale":"%s"}
                """.formatted(jsonText(update == null ? "" : update.dataGu()), jsonText(update == null ? "" : update.codiceRedazionale())).trim();
    }

    static List<NormattivaActDetail> fetchActDetails(
            String sourceUrl,
            NormattivaOpenDataUpdate update,
            HttpJsonClient client
    ) throws IOException {
        String json = client.postJson(actDetailEndpoint(sourceUrl), actDetailRequestBody(update));
        return parseActDetails(json);
    }

    static DetailFetchResult fetchAndWriteActDetails(
            String sourceUrl,
            Path updatesInput,
            Path detailsOutput,
            int limit,
            HttpJsonClient client
    ) throws IOException {
        List<NormattivaOpenDataUpdate> candidates = readOpenDataUpdates(updatesInput);
        int boundedLimit = Math.min(Math.max(1, limit), candidates.size());
        String endpoint = actDetailEndpoint(sourceUrl);
        OffsetDateTime fetchedAt = OffsetDateTime.now(ZoneOffset.UTC);
        List<NormattivaFetchedActDetail> fetchedDetails = new ArrayList<>();

        for (int index = 0; index < boundedLimit; index++) {
            NormattivaOpenDataUpdate candidate = candidates.get(index);
            if (candidate.codiceRedazionale().isBlank() || candidate.dataGu().isBlank()) {
                continue;
            }
            for (NormattivaActDetail detail : fetchActDetails(sourceUrl, candidate, client)) {
                fetchedDetails.add(new NormattivaFetchedActDetail(candidate, detail, endpoint, fetchedAt.toString()));
            }
        }

        writeActDetails(fetchedDetails, detailsOutput);
        return new DetailFetchResult(
                endpoint,
                candidates.size(),
                fetchedDetails.size(),
                detailsOutput.toAbsolutePath().normalize().toString()
        );
    }

    static List<NormattivaOpenDataUpdate> parseOpenDataUpdates(String json) throws IOException {
        JsonNode root = JSON.readTree(json == null || json.isBlank() ? "{}" : json);
        JsonNode acts = root.path("listaAtti");
        if (!acts.isArray()) {
            acts = root.path("data").path("listaAtti");
        }
        if (!acts.isArray()) {
            return List.of();
        }

        List<NormattivaOpenDataUpdate> updates = new ArrayList<>();
        for (JsonNode act : acts) {
            updates.add(new NormattivaOpenDataUpdate(
                    text(act, "codiceRedazionale"),
                    firstText(act, "dataGU", "dataGUStr"),
                    firstText(act, "denominazioneAtto", "tipoProvvedimentoDescrizione"),
                    firstText(act, "numeroAtto", "numeroProvvedimento", "numeroAttoAlfanumerico"),
                    firstText(act, "titoloAtto", "descrizioneAtto"),
                    text(act, "dataEmanazione"),
                    text(act, "dataUltimaModifica"),
                    text(act, "ultimiAttiModificanti")
            ));
        }
        return List.copyOf(updates);
    }

    static List<NormattivaActDetail> parseActDetails(String json) throws IOException {
        JsonNode root = JSON.readTree(json == null || json.isBlank() ? "{}" : json);
        JsonNode data = root.path("data");
        List<JsonNode> actNodes = new ArrayList<>();

        addActNode(actNodes, root.path("atto"));
        addActNode(actNodes, data.path("atto"));
        JsonNode list = data.path("lista");
        if (!list.isArray()) {
            list = root.path("lista");
        }
        if (list.isArray()) {
            for (JsonNode act : list) {
                addActNode(actNodes, act);
            }
        }

        List<NormattivaActDetail> details = new ArrayList<>();
        for (JsonNode act : actNodes) {
            details.add(new NormattivaActDetail(
                    text(act, "titolo"),
                    text(act, "sottoTitolo"),
                    text(act, "tipoProvvedimentoDescrizione"),
                    text(act, "tipoProvvedimentoCodice"),
                    actDate(act),
                    text(act, "numeroProvvedimento"),
                    firstText(act, "dataPubblicazioneInGazzetta", "dataGU"),
                    text(act, "articoloDataInizioVigenza"),
                    text(act, "articoloDataFineVigenza"),
                    text(act, "testoInVigore"),
                    text(act, "articoloHtml")
            ));
        }
        return List.copyOf(details);
    }

    static List<NormattivaFetchedActDetail> parseImportedActDetails(
            String json,
            String endpoint,
            String fetchedAt
    ) throws IOException {
        JsonNode root = JSON.readTree(json == null || json.isBlank() ? "{}" : json);
        JsonNode rows = root.isArray() ? root : firstArray(root, "details", "items", "rows");
        if (!rows.isArray()) {
            rows = JSON.createArrayNode().add(root);
        }

        List<NormattivaFetchedActDetail> details = new ArrayList<>();
        for (JsonNode row : rows) {
            NormattivaOpenDataUpdate candidate = openDataUpdate(firstPresent(row, "candidate", "update", "attoAggiornato"));
            JsonNode response = firstPresent(row, "response", "detailResponse", "rawResponse");
            JsonNode detailNode = firstPresent(row, "detail", "atto");
            List<NormattivaActDetail> parsedDetails;
            if (!response.isMissingNode() && !response.isNull()) {
                parsedDetails = parseActDetails(response.toString());
            } else if (!detailNode.isMissingNode() && !detailNode.isNull()) {
                parsedDetails = parseActDetails(JSON.createObjectNode().set("atto", detailNode).toString());
            } else {
                parsedDetails = parseActDetails(row.toString());
            }
            for (NormattivaActDetail detail : parsedDetails) {
                details.add(new NormattivaFetchedActDetail(candidate, detail, endpoint, fetchedAt));
            }
        }
        return List.copyOf(details);
    }

    static List<NormattivaOpenDataUpdate> readOpenDataUpdates(Path input) throws IOException {
        if (input == null || !Files.exists(input)) {
            return List.of();
        }

        List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);
        if (lines.size() < 2) {
            return List.of();
        }

        Map<String, Integer> header = headerIndex(lines.get(0));
        List<NormattivaOpenDataUpdate> updates = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            if (lines.get(index).isBlank()) {
                continue;
            }
            List<String> fields = splitTsv(lines.get(index));
            updates.add(new NormattivaOpenDataUpdate(
                    field(header, fields, "codice_redazionale"),
                    field(header, fields, "data_gu"),
                    field(header, fields, "denominazione_atto"),
                    field(header, fields, "numero_atto"),
                    field(header, fields, "titolo_atto"),
                    field(header, fields, "data_emanazione"),
                    field(header, fields, "data_ultima_modifica"),
                    field(header, fields, "ultimi_atti_modificanti")
            ));
        }
        return List.copyOf(updates);
    }

    static List<NormattivaRelationEvidence> scanRelationEvidence(Path detailsInput, int limit) throws IOException {
        if (detailsInput == null || !Files.exists(detailsInput)) {
            return List.of();
        }

        List<String> lines = Files.readAllLines(detailsInput, StandardCharsets.UTF_8);
        if (lines.size() < 2) {
            return List.of();
        }

        int boundedLimit = Math.max(1, Math.min(limit, 200));
        Map<String, Integer> header = headerIndex(lines.get(0));
        List<NormattivaRelationEvidence> evidenceRows = new ArrayList<>();
        for (int index = 1; index < lines.size() && evidenceRows.size() < boundedLimit; index++) {
            if (lines.get(index).isBlank()) {
                continue;
            }
            List<String> fields = splitTsv(lines.get(index));
            String evidenceText = evidenceText(header, fields);
            String evidenceType = evidenceType(evidenceText);
            if (evidenceType.isBlank()) {
                continue;
            }
            evidenceRows.add(new NormattivaRelationEvidence(
                    field(header, fields, "codice_redazionale"),
                    field(header, fields, "data_gu"),
                    field(header, fields, "titolo_atto"),
                    field(header, fields, "detail_title"),
                    evidenceType,
                    evidenceSnippet(evidenceText, evidenceType)
            ));
        }
        return List.copyOf(evidenceRows);
    }

    static List<NormattivaRelationCandidate> extractRelationCandidates(Path evidenceInput, int limit) throws IOException {
        if (evidenceInput == null || !Files.exists(evidenceInput)) {
            return List.of();
        }

        List<String> lines = Files.readAllLines(evidenceInput, StandardCharsets.UTF_8);
        if (lines.size() < 2) {
            return List.of();
        }

        int boundedLimit = Math.max(1, Math.min(limit, 200));
        Map<String, Integer> header = headerIndex(lines.get(0));
        List<NormattivaRelationCandidate> candidates = new ArrayList<>();
        for (int index = 1; index < lines.size() && candidates.size() < boundedLimit; index++) {
            if (lines.get(index).isBlank()) {
                continue;
            }
            List<String> fields = splitTsv(lines.get(index));
            String evidenceText = field(header, fields, "evidence_text");
            List<String> eliUris = eliHttpUris(evidenceText);
            if (eliUris.size() < 2) {
                continue;
            }
            String sourceUri = eliUris.get(eliUris.size() - 1);
            String relationType = relationPredicate(field(header, fields, "evidence_type"));
            for (int uriIndex = 0; uriIndex < eliUris.size() - 1 && candidates.size() < boundedLimit; uriIndex++) {
                candidates.add(new NormattivaRelationCandidate(
                        sourceUri,
                        eliUris.get(uriIndex),
                        relationType,
                        field(header, fields, "evidence_type"),
                        evidenceText,
                        "needs_review"
                ));
            }
        }
        return List.copyOf(candidates);
    }

    static List<NormattivaUpdate> parseUpdates(String html, String baseUrl) {
        Document document = Jsoup.parse(html == null ? "" : html, baseUrl);
        Map<String, NormattivaUpdate> updates = new LinkedHashMap<>();

        for (Element link : document.select("a[href*='uri-res/N2Ls']")) {
            Element container = updateContainer(link);
            String text = cleanText(container.text());
            if (!looksLikeUpdate(text)) {
                continue;
            }

            List<String> normattivaLinks = normattivaLinks(container, baseUrl);
            if (normattivaLinks.isEmpty()) {
                continue;
            }

            String title = title(container);
            String date = date(text);
            String key = title + "|" + date + "|" + String.join("|", normattivaLinks);
            updates.putIfAbsent(key, new NormattivaUpdate(
                    title,
                    date,
                    text,
                    normattivaLinks
            ));
        }

        return List.copyOf(updates.values());
    }

    static List<CleanModificationRecord> inferRelations(List<NormattivaUpdate> updates) {
        Set<CleanModificationRecord> records = new LinkedHashSet<>();
        for (NormattivaUpdate update : updates) {
            if (update.normattivaLinks().size() < 2) {
                continue;
            }

            List<String> links = update.normattivaLinks();
            String modifier = links.get(links.size() - 1);
            for (int index = 0; index < links.size() - 1; index++) {
                records.add(CleanModificationRecord.of(modifier, links.get(index), update.description()));
            }
        }
        return new ArrayList<>(records);
    }

    static void writeUpdates(List<NormattivaUpdate> updates, Path output, OffsetDateTime fetchedAt) throws IOException {
        createParent(output);
        List<String> lines = new ArrayList<>();
        lines.add("title\tupdate_date\tdescription\tnormattiva_links\tfetched_at");
        for (NormattivaUpdate update : updates) {
            lines.add(String.join("\t",
                    cleanField(update.title()),
                    cleanField(update.updateDate()),
                    cleanField(update.description()),
                    cleanField(String.join(" ", update.normattivaLinks())),
                    fetchedAt.toString()
            ));
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    static void writeOpenDataUpdates(
            List<NormattivaOpenDataUpdate> updates,
            Path output,
            String endpoint,
            OffsetDateTime fetchedAt
    ) throws IOException {
        createParent(output);
        List<String> lines = new ArrayList<>();
        lines.add("codice_redazionale\tdata_gu\tdenominazione_atto\tnumero_atto\ttitolo_atto\tdata_emanazione\tdata_ultima_modifica\tultimi_atti_modificanti\tendpoint\tfetched_at");
        for (NormattivaOpenDataUpdate update : updates) {
            lines.add(String.join("\t",
                    cleanField(update.codiceRedazionale()),
                    cleanField(update.dataGu()),
                    cleanField(update.denominazioneAtto()),
                    cleanField(update.numeroAtto()),
                    cleanField(update.titoloAtto()),
                    cleanField(update.dataEmanazione()),
                    cleanField(update.dataUltimaModifica()),
                    cleanField(update.ultimiAttiModificanti()),
                    cleanField(endpoint),
                    fetchedAt.toString()
            ));
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    static void writeActDetails(List<NormattivaFetchedActDetail> details, Path output) throws IOException {
        createParent(output);
        List<String> lines = new ArrayList<>();
        lines.add("codice_redazionale\tdata_gu\ttitolo_atto\tdenominazione_atto\tnumero_atto\tdetail_title\tdetail_subtitle\tact_type\tact_type_code\tact_date\tact_number\tpublication_date\tforce_start_date\tforce_end_date\ttext_in_force\tarticle_html\tendpoint\tfetched_at");
        for (NormattivaFetchedActDetail fetched : details) {
            NormattivaOpenDataUpdate candidate = fetched.candidate();
            NormattivaActDetail detail = fetched.detail();
            lines.add(String.join("\t",
                    cleanField(candidate.codiceRedazionale()),
                    cleanField(candidate.dataGu()),
                    cleanField(candidate.titoloAtto()),
                    cleanField(candidate.denominazioneAtto()),
                    cleanField(candidate.numeroAtto()),
                    cleanField(detail.title()),
                    cleanField(detail.subtitle()),
                    cleanField(detail.actType()),
                    cleanField(detail.actTypeCode()),
                    cleanField(detail.actDate()),
                    cleanField(detail.actNumber()),
                    cleanField(detail.publicationDate()),
                    cleanField(detail.forceStartDate()),
                    cleanField(detail.forceEndDate()),
                    cleanField(detail.textInForce()),
                    cleanField(detail.articleHtml()),
                    cleanField(fetched.endpoint()),
                    cleanField(fetched.fetchedAt())
            ));
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    static void writeRelationEvidence(List<NormattivaRelationEvidence> evidenceRows, Path output) throws IOException {
        createParent(output);
        List<String> lines = new ArrayList<>();
        lines.add("codice_redazionale\tdata_gu\ttitolo_atto\tdetail_title\tevidence_type\tevidence_text");
        for (NormattivaRelationEvidence evidence : evidenceRows) {
            lines.add(String.join("\t",
                    cleanField(evidence.code()),
                    cleanField(evidence.gazzettaDate()),
                    cleanField(evidence.candidateTitle()),
                    cleanField(evidence.detailTitle()),
                    cleanField(evidence.evidenceType()),
                    cleanField(evidence.evidenceText())
            ));
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    static void writeRelationCandidates(List<NormattivaRelationCandidate> candidates, Path output) throws IOException {
        createParent(output);
        List<String> lines = new ArrayList<>();
        lines.add("source_uri\ttarget_uri\trelation_type\tevidence_type\tevidence_text\treview_status");
        for (NormattivaRelationCandidate candidate : candidates) {
            lines.add(String.join("\t",
                    cleanField(candidate.sourceUri()),
                    cleanField(candidate.targetUri()),
                    cleanField(candidate.relationType()),
                    cleanField(candidate.evidenceType()),
                    cleanField(candidate.evidenceText()),
                    cleanField(candidate.reviewStatus())
            ));
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    static void writeRelations(List<CleanModificationRecord> relations, Path output) throws IOException {
        createParent(output);
        List<String> lines = new ArrayList<>();
        lines.add("eliSubject\teliObject\tmodtext");
        for (CleanModificationRecord relation : relations) {
            lines.add(String.join("\t",
                    cleanField(relation.getSubjectUri()),
                    cleanField(relation.getObjectUri()),
                    cleanField(relation.getModificationText())
            ));
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    static void writeRdf(List<CleanModificationRecord> relations, Path output) throws IOException {
        createParent(output);
        Model model = new ModificationRdfModelBuilder().build(relations);
        try (OutputStream outputStream = Files.newOutputStream(output)) {
            RDFDataMgr.write(outputStream, model, RDFFormat.TURTLE_PRETTY);
        }
    }

    private static Element updateContainer(Element link) {
        Element container = link;
        for (int level = 0; level < 6 && container.parent() != null; level++) {
            Element parent = container.parent();
            String parentText = cleanText(parent.text());
            if (looksLikeUpdate(parentText) && parentText.length() <= 2500) {
                container = parent;
            }
        }
        return container;
    }

    private static boolean looksLikeUpdate(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        return lower.contains("modifiche")
                || lower.contains("modifica")
                || lower.contains("convertito")
                || lower.contains("conversione")
                || lower.contains("banca dati");
    }

    private static List<String> normattivaLinks(Element container, String baseUrl) {
        Set<String> links = new LinkedHashSet<>();
        for (Element link : container.select("a[href*='uri-res/N2Ls']")) {
            links.add(link.absUrl("href").isBlank() ? absoluteUrl(baseUrl, link.attr("href")) : link.absUrl("href"));
        }
        return List.copyOf(links);
    }

    private static String title(Element container) {
        Element heading = container.selectFirst("h1, h2, h3, h4, h5");
        if (heading != null && !heading.text().isBlank()) {
            return cleanText(heading.text());
        }
        String text = cleanText(container.text());
        int sentenceEnd = text.indexOf(". ");
        if (sentenceEnd > 0 && sentenceEnd < 140) {
            return text.substring(0, sentenceEnd + 1);
        }
        return text.length() > 120 ? text.substring(0, 120) : text;
    }

    private static String date(String text) {
        Matcher matcher = ITALIAN_DATE.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group() : "";
    }

    private static String fetch(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .timeout(15000)
                .followRedirects(true)
                .get()
                .outerHtml();
    }

    private static String postJson(String url, String jsonBody) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException(openDataFailureMessage(response.statusCode(), response.body()));
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Normattiva OpenData update request interrupted.", exception);
        }
    }

    static String openDataFailureMessage(int statusCode, String body) {
        String message = "Normattiva OpenData request failed with HTTP " + statusCode;
        String detail = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (detail.isBlank()) {
            return message;
        }
        if (detail.length() > 500) {
            detail = detail.substring(0, 500) + "...";
        }
        return message + ": " + detail;
    }

    private static String absoluteUrl(String baseUrl, String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        URI base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        return base.resolve(href).toString();
    }

    private static void createParent(Path output) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static void requireExistingInput(Path input) throws IOException {
        if (input == null || !Files.exists(input)) {
            throw new IOException("Normattiva import input not found: " + (input == null ? "" : input.toAbsolutePath().normalize()));
        }
    }

    private static boolean isJson(Path input) {
        String fileName = input.getFileName() == null ? "" : input.getFileName().toString().toLowerCase();
        return fileName.endsWith(".json");
    }

    private static JsonNode firstArray(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isArray()) {
                return value;
            }
        }
        return JSON.createArrayNode();
    }

    private static JsonNode firstPresent(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return JSON.getNodeFactory().missingNode();
    }

    private static NormattivaOpenDataUpdate openDataUpdate(JsonNode act) {
        if (act == null || act.isMissingNode() || act.isNull()) {
            return new NormattivaOpenDataUpdate("", "", "", "", "", "", "", "");
        }
        return new NormattivaOpenDataUpdate(
                text(act, "codiceRedazionale"),
                firstText(act, "dataGU", "dataGUStr"),
                firstText(act, "denominazioneAtto", "tipoProvvedimentoDescrizione"),
                firstText(act, "numeroAtto", "numeroProvvedimento", "numeroAttoAlfanumerico"),
                firstText(act, "titoloAtto", "descrizioneAtto"),
                text(act, "dataEmanazione"),
                text(act, "dataUltimaModifica"),
                text(act, "ultimiAttiModificanti")
        );
    }

    private static List<String> eliHttpUris(String text) {
        Set<String> uris = new LinkedHashSet<>();
        Matcher matcher = ELI_HTTP_URI.matcher(text == null ? "" : text);
        while (matcher.find()) {
            uris.add(matcher.group().replaceAll("[),.;]+$", ""));
        }
        return List.copyOf(uris);
    }

    private static String relationPredicate(String evidenceType) {
        return switch (evidenceType == null ? "" : evidenceType) {
            case "conversion" -> "eli:commences";
            case "repeal" -> "ilg:repeals";
            case "substitution" -> "ilg:substitutes";
            default -> "ilg:modifies";
        };
    }

    private static String cleanText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String cleanField(String value) {
        return cleanText(value).replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static String valueOrDefault(String environmentName, String defaultValue) {
        String value = System.getenv(environmentName);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private static OptionalDate envDate(String environmentName) {
        String value = System.getenv(environmentName);
        if (value == null || value.isBlank()) {
            return OptionalDate.empty();
        }
        try {
            return OptionalDate.of(OffsetDateTime.parse(value));
        } catch (RuntimeException exception) {
            LocalDate date = LocalDate.parse(value);
            return OptionalDate.of(date.atStartOfDay().atOffset(ZoneOffset.UTC));
        }
    }

    private static int lookbackDays() {
        String value = System.getenv("NORMATTIVA_UPDATE_LOOKBACK_DAYS");
        if (value == null || value.isBlank()) {
            return 7;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return 7;
        }
    }

    private static int detailLimit() {
        String value = System.getenv("NORMATTIVA_DETAILS_LIMIT");
        if (value == null || value.isBlank()) {
            return 20;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return 20;
        }
    }

    private static int evidenceLimit() {
        String value = System.getenv("NORMATTIVA_EVIDENCE_LIMIT");
        if (value == null || value.isBlank()) {
            return 50;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return 50;
        }
    }

    private static OffsetDateTime fetchedAt(OffsetDateTime start, OffsetDateTime end) {
        return end == null ? OffsetDateTime.now(ZoneOffset.UTC) : end;
    }

    private static int countDataRows(Path input) throws IOException {
        if (input == null || !Files.exists(input)) {
            return 0;
        }
        return Math.max(0, Files.readAllLines(input, StandardCharsets.UTF_8).size() - 1);
    }

    private static String evidenceText(Map<String, Integer> header, List<String> fields) {
        String htmlText = Jsoup.parse(field(header, fields, "article_html")).text();
        return cleanText(String.join(" ",
                field(header, fields, "titolo_atto"),
                field(header, fields, "detail_title"),
                field(header, fields, "detail_subtitle"),
                field(header, fields, "text_in_force"),
                htmlText
        ));
    }

    private static String evidenceType(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        if (lower.contains("convertit") || lower.contains("conversione")) {
            return "conversion";
        }
        if (lower.contains("abrogat")) {
            return "repeal";
        }
        if (lower.contains("sostitui")) {
            return "substitution";
        }
        if (lower.contains("modif")) {
            return "modification";
        }
        return "";
    }

    private static String evidenceSnippet(String text, String evidenceType) {
        String clean = cleanText(text);
        String lower = clean.toLowerCase();
        String keyword = switch (evidenceType) {
            case "conversion" -> lower.contains("conversione") ? "conversione" : "convertit";
            case "repeal" -> "abrogat";
            case "substitution" -> "sostitui";
            default -> "modif";
        };
        int index = lower.indexOf(keyword);
        if (index < 0) {
            return clean.length() > 220 ? clean.substring(0, 220) : clean;
        }
        int start = Math.max(0, index - 80);
        int end = Math.min(clean.length(), index + 140);
        return clean.substring(start, end);
    }

    private static void addActNode(List<JsonNode> actNodes, JsonNode act) {
        if (act != null && act.isObject() && !act.isEmpty()) {
            actNodes.add(act);
        }
    }

    private static String actDate(JsonNode act) {
        String year = text(act, "annoProvvedimento");
        String month = text(act, "meseProvvedimento");
        String day = text(act, "giornoProvvedimento");
        if (year.isBlank() || month.isBlank() || day.isBlank()) {
            return "";
        }
        return "%s-%s-%s".formatted(year, leftPad2(month), leftPad2(day));
    }

    private static String leftPad2(String value) {
        return value.length() == 1 ? "0" + value : value;
    }

    private static String jsonText(String value) {
        return cleanText(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
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
            fields.add(value.trim());
        }
        return fields;
    }

    private static String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = text(node, fieldName);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    @FunctionalInterface
    interface HttpJsonClient {
        String postJson(String url, String jsonBody) throws IOException;
    }

    public record NormattivaUpdate(
            String title,
            String updateDate,
            String description,
            List<String> normattivaLinks
    ) {
    }

    public record NormattivaOpenDataUpdate(
            String codiceRedazionale,
            String dataGu,
            String denominazioneAtto,
            String numeroAtto,
            String titoloAtto,
            String dataEmanazione,
            String dataUltimaModifica,
            String ultimiAttiModificanti
    ) {
    }

    public record NormattivaActDetail(
            String title,
            String subtitle,
            String actType,
            String actTypeCode,
            String actDate,
            String actNumber,
            String publicationDate,
            String forceStartDate,
            String forceEndDate,
            String textInForce,
            String articleHtml
    ) {
    }

    public record NormattivaFetchedActDetail(
            NormattivaOpenDataUpdate candidate,
            NormattivaActDetail detail,
            String endpoint,
            String fetchedAt
    ) {
    }

    public record DetailFetchResult(
            String sourceUrl,
            int candidatesRead,
            int detailsWritten,
            String detailsPath
    ) {
    }

    public record NormattivaRelationEvidence(
            String code,
            String gazzettaDate,
            String candidateTitle,
            String detailTitle,
            String evidenceType,
            String evidenceText
    ) {
    }

    public record NormattivaRelationCandidate(
            String sourceUri,
            String targetUri,
            String relationType,
            String evidenceType,
            String evidenceText,
            String reviewStatus
    ) {
    }

    public record ImportResult(
            String inputPath,
            int rowsWritten,
            String outputPath
    ) {
    }

    public record EvidenceScanResult(
            String detailsPath,
            int detailsRead,
            int evidenceRows,
            String evidencePath
    ) {
    }

    public record RelationCandidateResult(
            String evidencePath,
            int evidenceRowsRead,
            int candidatesWritten,
            String relationCandidatesPath
    ) {
    }

    private record OptionalDate(OffsetDateTime value) {
        static OptionalDate of(OffsetDateTime value) {
            return new OptionalDate(value);
        }

        static OptionalDate empty() {
            return new OptionalDate(null);
        }

        OffsetDateTime orElse(OffsetDateTime fallback) {
            return value == null ? fallback : value;
        }
    }

    public record Result(
            String sourceUrl,
            int updatesRead,
            int relationRows,
            String updatesPath,
            String relationsPath,
            String rdfPath
    ) {
    }
}
