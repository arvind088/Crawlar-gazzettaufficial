package it.legislation.source.normattiva;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class NormattivaClientTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void updatedActsUsesOfficialPathAndBody() throws IOException {
        FakeTransport transport = new FakeTransport()
                .reply(200, "{\"listaAtti\":[{\"codiceRedazionale\":\"26G00139\",\"ultimiAttiModificanti\":\"26G00167\"}]}");
        NormattivaClient client = new NormattivaClient(transport);

        JsonNode result = client.updatedActs(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7));

        FakeTransport.Call call = transport.calls.get(0);
        assertEquals("POST", call.method());
        assertEquals("ricerca/aggiornati", call.path());
        JsonNode body = json.readTree(call.body());
        assertEquals("2026-08-01T00:00:00Z", body.get("dataInizioAggiornamento").asText());
        assertEquals("2026-08-07T00:00:00Z", body.get("dataFineAggiornamento").asText());
        assertEquals("26G00167", result.path("listaAtti").path(0).path("ultimiAttiModificanti").asText());
    }

    @Test
    void basePathIncludesBffOpendataPrefix() {
        HttpNormattivaTransport transport = new HttpNormattivaTransport();
        assertEquals("https://api.normattiva.it/t/normattiva.api/bff-opendata/v1/api/v1/ricerca/aggiornati",
                transport.url("ricerca/aggiornati"));
        assertEquals("https://example.org/file.zip", transport.url("https://example.org/file.zip"));
    }

    @Test
    void detailByUrnSendsVersionedUrn() throws IOException {
        FakeTransport transport = new FakeTransport()
                .reply(200, "{\"success\":true,\"data\":{\"atto\":{\"titolo\":\"DECRETO LEGISLATIVO 30 giugno 2003, n. 196\"}}}");
        NormattivaClient client = new NormattivaClient(transport);

        JsonNode detail = client.actDetailByUrn("urn:nir:stato:decreto.legislativo:2003-06-30;196@originale");

        assertEquals("atto/dettaglio-atto-urn", transport.calls.get(0).path());
        assertEquals("urn:nir:stato:decreto.legislativo:2003-06-30;196@originale",
                json.readTree(transport.calls.get(0).body()).get("urn").asText());
        assertTrue(detail.path("data").path("atto").path("titolo").asText().contains("196"));
    }

    @Test
    void exportRequestsConfirmsPollsAndDownloads() throws IOException {
        byte[] zip = "PK-fake-zip".getBytes(StandardCharsets.UTF_8);
        FakeTransport transport = new FakeTransport()
                .reply(202, "abc-123")
                .reply(204, "")
                .reply(200, "{\"stato\":2}")
                .reply(303, Map.of("x-ipzs-location", "https://download.example/abc-123.zip"), "")
                .replyBytes(200, zip);
        long[] now = {0};
        NormattivaClient client = new NormattivaClient(transport, d -> now[0] += d.toMillis(), () -> now[0]);

        byte[] result = client.export(
                ExportRequest.akn(ExportRequest.Mode.ALL_VERSIONS, "DECRETO LEGISLATIVO", 2003, 6, 30, 196),
                Duration.ofMinutes(1), Duration.ofSeconds(5));

        assertEquals(new String(zip, StandardCharsets.UTF_8), new String(result, StandardCharsets.UTF_8));
        assertEquals("ricerca-asincrona/nuova-ricerca", transport.calls.get(0).path());
        JsonNode body = json.readTree(transport.calls.get(0).body());
        assertEquals("AKN", body.get("formato").asText());
        assertEquals("M", body.get("richiestaExport").asText());
        assertEquals("DECRETO LEGISLATIVO", body.path("parametriRicerca").get("denominazioneAtto").asText());
        assertEquals(196, body.path("parametriRicerca").get("numeroProvvedimento").asInt());
        assertEquals("PUT", transport.calls.get(1).method());
        assertEquals("ricerca-asincrona/conferma-ricerca", transport.calls.get(1).path());
        assertEquals("abc-123", json.readTree(transport.calls.get(1).body()).get("token").asText());
        assertEquals("ricerca-asincrona/check-status/abc-123", transport.calls.get(2).path());
        assertEquals("https://download.example/abc-123.zip", transport.calls.get(4).path());
    }

    @Test
    void downloadFollowsRedirectsByHand() throws IOException {
        FakeTransport transport = new FakeTransport()
                .reply(302, Map.of("location", "https://files.example/export.zip"), "")
                .replyBytes(200, "ZIP".getBytes(StandardCharsets.UTF_8));
        NormattivaClient client = new NormattivaClient(transport);

        byte[] zip = client.downloadExport("tok", null);

        assertEquals("ZIP", new String(zip, StandardCharsets.UTF_8));
        assertEquals("collections/download/collection-asincrona/tok", transport.calls.get(0).path());
        assertEquals("https://files.example/export.zip", transport.calls.get(1).path());
    }

    @Test
    void statusSeeOtherMeansCompletedWithIpzsLocation() throws IOException {
        FakeTransport transport = new FakeTransport()
                .reply(303, Map.of("x-ipzs-location", "collections/download/collection-asincrona/tok"), "");
        NormattivaClient client = new NormattivaClient(transport);

        ExportStatus status = client.exportStatus("tok");

        assertTrue(status.isCompleted());
        assertEquals("collections/download/collection-asincrona/tok", status.location());
    }

    @Test
    void failedExportStateIsReported() {
        FakeTransport transport = new FakeTransport()
                .reply(200, "tok")
                .reply(200, "")
                .reply(200, "{\"stato\":4,\"descrizioneErrore\":\"errore interno\"}");
        NormattivaClient client = new NormattivaClient(transport, d -> { }, () -> 0L);

        String message = assertThrowsMessage(() -> client.export(
                ExportRequest.akn(ExportRequest.Mode.ORIGINAL, "LEGGE", 2020, 4, 24, 27),
                Duration.ofMinutes(1), Duration.ofSeconds(1)));

        assertTrue(message.contains("state 4"), message);
        assertTrue(message.contains("errore interno"), message);
    }

    @Test
    void httpErrorsKeepStatusAndBody() {
        FakeTransport transport = new FakeTransport().reply(409, "<html>Request blocked by WAF</html>");
        NormattivaClient client = new NormattivaClient(transport);

        String message = assertThrowsMessage(() -> client.updatedActs(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)));

        assertTrue(message.contains("HTTP 409"), message);
        assertTrue(message.contains("blocked"), message);
    }

    @Test
    void advancedSearchAddsPagingAndCriteria() throws IOException {
        FakeTransport transport = new FakeTransport().reply(200, "{\"listaAtti\":[]}");
        NormattivaClient client = new NormattivaClient(transport);
        var criteria = client.criteria();
        criteria.put("classeProvvedimento", "1");

        client.advancedSearch(criteria, 1, 5);

        JsonNode body = json.readTree(transport.calls.get(0).body());
        assertEquals("ricerca/avanzata", transport.calls.get(0).path());
        assertEquals("1", body.get("classeProvvedimento").asText());
        assertEquals(5, body.path("paginazione").get("numeroElementiPerPagina").asInt());
    }

    @Test
    void fileStemIsStableAndSafe() {
        ExportRequest request = ExportRequest.akn(ExportRequest.Mode.ALL_VERSIONS, "DECRETO LEGISLATIVO", 2003, 6, 30, 196);
        assertEquals("DECRETO_LEGISLATIVO_2003-06-30_196_M_AKN", request.fileStem());
    }

    interface Action {
        void run() throws Exception;
    }

    private static String assertThrowsMessage(Action action) {
        try {
            action.run();
        } catch (Exception exception) {
            return exception.getMessage();
        }
        throw new AssertionError("Expected an exception");
    }
}
