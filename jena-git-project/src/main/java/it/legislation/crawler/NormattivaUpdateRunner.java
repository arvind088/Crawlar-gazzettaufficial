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
    private static final Path DEFAULT_RELATIONS_OUTPUT = Path.of("data", "clean", "normattiva_modifications_auto.tsv");
    private static final Path DEFAULT_RDF_OUTPUT = Path.of("data", "rdf", "normattiva_modifications_auto.ttl");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ITALIAN_DATE = Pattern.compile(
            "\\b\\d{1,2}\\s+(?:gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre)\\s+\\d{4}\\b",
            Pattern.CASE_INSENSITIVE
    );

    public static void main(String[] args) throws IOException {
        String sourceUrl = valueOrDefault("NORMATTIVA_SOURCE_URL", DEFAULT_SOURCE_URL);
        Path updatesOutput = Path.of(valueOrDefault("NORMATTIVA_UPDATES_OUTPUT", DEFAULT_UPDATES_OUTPUT.toString()));
        Path relationsOutput = Path.of(valueOrDefault("NORMATTIVA_RELATIONS_OUTPUT", DEFAULT_RELATIONS_OUTPUT.toString()));
        Path rdfOutput = Path.of(valueOrDefault("NORMATTIVA_RDF_OUTPUT", DEFAULT_RDF_OUTPUT.toString()));

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

    static String updatedActsRequestBody(OffsetDateTime start, OffsetDateTime end) {
        String safeStart = start == null ? OffsetDateTime.now(ZoneOffset.UTC).minusDays(lookbackDays()).toString() : start.toString();
        String safeEnd = end == null ? OffsetDateTime.now(ZoneOffset.UTC).toString() : end.toString();
        return """
                {"dataInizioAggiornamento":"%s","dataFineAggiornamento":"%s"}
                """.formatted(safeStart, safeEnd).trim();
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
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Normattiva OpenData update request failed with HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Normattiva OpenData update request interrupted.", exception);
        }
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

    private static OffsetDateTime fetchedAt(OffsetDateTime start, OffsetDateTime end) {
        return end == null ? OffsetDateTime.now(ZoneOffset.UTC) : end;
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
