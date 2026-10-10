package it.legislation.source.normattiva;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Client for the official Normattiva OpenData API.
 *
 * <p>All paths are relative to {@link HttpNormattivaTransport#PRODUCTION_BASE}
 * ({@code .../bff-opendata/v1/api/v1}). The client only fetches: it never
 * writes RDF and never decides legal relations.
 */
public class NormattivaClient {

    static final String UPDATED_ACTS = "ricerca/aggiornati";
    static final String ADVANCED_SEARCH = "ricerca/avanzata";
    static final String DETAIL_BY_URN = "atto/dettaglio-atto-urn";
    static final String EXPORT_NEW = "ricerca-asincrona/nuova-ricerca";
    static final String EXPORT_CONFIRM = "ricerca-asincrona/conferma-ricerca";
    static final String EXPORT_STATUS = "ricerca-asincrona/check-status/";
    static final String EXPORT_DOWNLOAD = "collections/download/collection-asincrona/";
    static final String LOCATION_HEADER = "x-ipzs-location";

    private final NormattivaTransport transport;
    private final ObjectMapper json = new ObjectMapper();
    private final Sleeper sleeper;
    private final LongSupplier clockMillis;

    /** Receives one line per export step, so a long export shows its progress. */
    public interface Progress {
        void report(String line);
    }

    /** Waits between status polls; replaced in tests. */
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public NormattivaClient() {
        this(new HttpNormattivaTransport());
    }

    public NormattivaClient(NormattivaTransport transport) {
        this(transport, duration -> Thread.sleep(duration.toMillis()), System::currentTimeMillis);
    }

    public NormattivaClient(NormattivaTransport transport, Sleeper sleeper, LongSupplier clockMillis) {
        this.transport = transport;
        this.sleeper = sleeper;
        this.clockMillis = clockMillis;
    }

    /** Raw JSON of {@code POST ricerca/aggiornati}: acts changed between two days (inclusive). */
    public JsonNode updatedActs(LocalDate from, LocalDate to) throws IOException {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("End " + to + " is before start " + from);
        }
        ObjectNode body = json.createObjectNode();
        body.put("dataInizioAggiornamento", from + "T00:00:00Z");
        body.put("dataFineAggiornamento", to + "T00:00:00Z");
        return postJson("Updated acts search", UPDATED_ACTS, body.toString());
    }

    /**
     * Raw JSON of {@code POST ricerca/avanzata}. {@code criteria} holds Normattiva field names,
     * for example {@code denominazioneAtto}, {@code annoProvvedimento}, {@code classeProvvedimento}
     * ("1" = never updated, "2" = updated, "3" = repealed).
     */
    public JsonNode advancedSearch(ObjectNode criteria, int page, int pageSize) throws IOException {
        if (page < 1 || pageSize < 1) {
            throw new IllegalArgumentException("page and pageSize start from 1");
        }
        ObjectNode body = criteria.deepCopy();
        body.put("orderType", "recente");
        ObjectNode paging = body.putObject("paginazione");
        paging.put("paginaCorrente", page);
        paging.put("numeroElementiPerPagina", pageSize);
        return postJson("Advanced search", ADVANCED_SEARCH, body.toString());
    }

    /**
     * How many acts match the criteria of an export, using {@code ricerca/avanzata}
     * ({@code numeroAttiTrovati}). Used to size exports and, after import, to check
     * that nothing is missing.
     */
    public int count(ExportRequest request) throws IOException {
        JsonNode result = advancedSearch(request.criteria(json), 1, 1);
        JsonNode found = result.path("numeroAttiTrovati");
        if (!found.isNumber() && !found.isTextual()) {
            throw new NormattivaApiException("Count for " + request.fileStem() + " has no numeroAttiTrovati: "
                    + result.toString().substring(0, Math.min(200, result.toString().length())));
        }
        return found.asInt();
    }

    /** Every act matching the criteria of an export ({@code listaAtti} of all result pages). */
    public java.util.List<JsonNode> listAll(ExportRequest request) throws IOException {
        java.util.List<JsonNode> acts = new java.util.ArrayList<>();
        int pageSize = 50;
        for (int page = 1; page <= 200; page++) {
            JsonNode result = advancedSearch(request.criteria(json), page, pageSize);
            JsonNode list = result.path("listaAtti");
            if (!list.isArray() || list.isEmpty()) {
                break;
            }
            list.forEach(acts::add);
            int total = result.path("numeroAttiTrovati").asInt(Integer.MAX_VALUE);
            if (acts.size() >= total || list.size() < pageSize) {
                break;
            }
        }
        return acts;
    }

    /** A new, empty criteria object for {@link #advancedSearch}. */
    public ObjectNode criteria() {
        return json.createObjectNode();
    }

    /**
     * Raw JSON of {@code POST atto/dettaglio-atto-urn}. The URN may carry a version suffix:
     * {@code @originale} for the original text or {@code !vig=YYYY-MM-DD} for the text in force on a day.
     */
    public JsonNode actDetailByUrn(String urn) throws IOException {
        ObjectNode body = json.createObjectNode();
        body.put("urn", urn);
        return postJson("Act detail " + urn, DETAIL_BY_URN, body.toString());
    }

    /** Starts and confirms an asynchronous export; returns its token. */
    public String startExport(ExportRequest request) throws IOException {
        NormattivaTransport.Response created = transport.post(EXPORT_NEW, request.toJson(json));
        if (!created.isSuccess()) {
            throw new NormattivaApiException("Export request " + request.fileStem(), created.status(), created.text());
        }
        String token = created.text().trim().replace("\"", "");
        if (token.isEmpty()) {
            throw new NormattivaApiException("Export request returned no token for " + request.fileStem());
        }
        ObjectNode confirm = json.createObjectNode();
        confirm.put("token", token);
        NormattivaTransport.Response confirmed = transport.put(EXPORT_CONFIRM, confirm.toString());
        if (!confirmed.isSuccess()) {
            throw new NormattivaApiException("Export confirmation " + token, confirmed.status(), confirmed.text());
        }
        return token;
    }

    /** One status check of an export. */
    public ExportStatus exportStatus(String token) throws IOException {
        NormattivaTransport.Response response = transport.get(EXPORT_STATUS + token);
        if (!response.isSuccess() && response.status() != 303) {
            throw new NormattivaApiException("Export status " + token, response.status(), response.text());
        }
        String location = response.header(LOCATION_HEADER).or(() -> response.header("location")).orElse(null);
        int code = response.status() == 303 ? ExportStatus.COMPLETED : 2;
        String message = null;
        String raw = response.text().isBlank() ? null : excerpt(response.text());
        if (response.body() != null && response.body().length > 0) {
            try {
                JsonNode body = json.readTree(response.body());
                if (body.hasNonNull("stato")) {
                    code = body.get("stato").asInt(code);
                }
                message = text(body, "descrizioneErrore", text(body, "descrizioneStato", null));
            } catch (IOException ignored) {
                // Not JSON: keep the status derived from the HTTP code.
            }
        }
        return new ExportStatus(code, location, message, raw);
    }

    /** Downloads a finished export (a ZIP archive), following up to 5 redirects by hand. */
    public byte[] downloadExport(String token, String location) throws IOException {
        String path = location != null && !location.isBlank() ? location : EXPORT_DOWNLOAD + token;
        for (int hop = 0; hop < 5; hop++) {
            NormattivaTransport.Response response = transport.get(path);
            if (response.isSuccess()) {
                return response.body();
            }
            boolean redirect = response.status() >= 300 && response.status() < 400;
            String next = response.header("location").or(() -> response.header(LOCATION_HEADER)).orElse(null);
            if (!redirect || next == null || next.isBlank()) {
                throw new NormattivaApiException("Export download " + token, response.status(), response.text());
            }
            path = next;
        }
        throw new NormattivaApiException("Export download " + token + " redirected more than 5 times");
    }

    /** Starts an export, polls until it is done, and returns the ZIP bytes. */
    public byte[] export(ExportRequest request, Duration timeout, Duration pollEvery) throws IOException {
        return export(request, timeout, pollEvery, line -> { });
    }

    /** As {@link #export(ExportRequest, Duration, Duration)}, reporting each step to {@code progress}. */
    public byte[] export(ExportRequest request, Duration timeout, Duration pollEvery, Progress progress)
            throws IOException {
        long started = clockMillis.getAsLong();
        String token = startExport(request);
        progress.report("export started, token " + token);
        long deadline = started + timeout.toMillis();
        int lastCode = Integer.MIN_VALUE;
        while (true) {
            ExportStatus status = exportStatus(token);
            long seconds = (clockMillis.getAsLong() - started) / 1000;
            if (status.code() != lastCode) {
                progress.report(seconds + "s state " + status.code() + " (" + ExportStatus.describe(status.code()) + ")"
                        + (status.message() == null ? "" : ": " + status.message())
                        + (status.raw() == null ? "" : "  raw=" + status.raw()));
                lastCode = status.code();
            }
            if (status.isCompleted()) {
                byte[] zip = downloadExport(token, status.location());
                progress.report("downloaded " + zip.length + " bytes");
                return zip;
            }
            if (status.isDone()) {
                throw new NormattivaApiException("Export " + request.fileStem() + " ended with state "
                        + status.code() + (status.message() == null ? "" : ": " + status.message()));
            }
            if (clockMillis.getAsLong() >= deadline) {
                throw new NormattivaApiException("Export " + request.fileStem() + " not ready after "
                        + timeout.toSeconds() + "s (token " + token + ")");
            }
            try {
                sleeper.sleep(pollEvery);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for export " + token, exception);
            }
        }
    }

    private JsonNode postJson(String operation, String path, String body) throws IOException {
        NormattivaTransport.Response response = transport.post(path, body);
        if (!response.isSuccess()) {
            throw new NormattivaApiException(operation, response.status(), response.text());
        }
        return json.readTree(response.body());
    }

    private static String excerpt(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }
}
