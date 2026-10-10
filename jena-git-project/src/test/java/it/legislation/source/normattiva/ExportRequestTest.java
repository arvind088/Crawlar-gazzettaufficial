package it.legislation.source.normattiva;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ExportRequestTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void yearSliceSendsOnlyTypeAndYear() throws Exception {
        ExportRequest request = ExportRequest.slice(ExportRequest.Mode.ALL_VERSIONS, "DECRETO-LEGGE", 2024, null);

        JsonNode params = json.readTree(request.toJson(json)).path("parametriRicerca");

        assertEquals("DECRETO-LEGGE", params.get("denominazioneAtto").asText());
        assertEquals(2024, params.get("annoProvvedimento").asInt());
        assertFalse(params.has("meseProvvedimento"));
        assertFalse(params.has("numeroProvvedimento"));
        assertEquals("DECRETO_LEGGE_2024_M_AKN", request.fileStem());
    }

    @Test
    void monthSliceAddsTheMonth() throws Exception {
        ExportRequest request = ExportRequest.slice(ExportRequest.Mode.ALL_VERSIONS, "LEGGE", 2024, 3);

        JsonNode params = json.readTree(request.toJson(json)).path("parametriRicerca");

        assertEquals(3, params.get("meseProvvedimento").asInt());
        assertEquals("LEGGE_2024-03_M_AKN", request.fileStem());
        assertTrue(request.criteria(json).has("annoProvvedimento"));
    }
}
