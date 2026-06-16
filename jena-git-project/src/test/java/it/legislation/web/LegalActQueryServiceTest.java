package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegalActQueryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void searchesLegalActsFromGeneratedTurtle() throws Exception {
        Path turtle = tempDir.resolve("gazzetta_metadata_delta.ttl");
        Files.writeString(turtle, """
                @prefix eli: <http://data.europa.eu/eli/ontology#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix dcterms: <http://purl.org/dc/terms/> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

                <http://www.gazzettaufficiale.it/eli/id/2026/06/12/26G00117/sg>
                  a eli:LegalResource ;
                  rdfs:label "Disposizioni per la prevenzione del melanoma" ;
                  eli:date_publication "2026-06-12"^^xsd:date ;
                  eli:date_document "2026-05-15"^^xsd:date ;
                  eli:type_document <http://www.gazzettaufficiale.it/eli/tables/resource-type#LEGGE> ;
                  eli:id_local "26G00117" ;
                  dcterms:source <http://www.gazzettaufficiale.it/eli/id/2026/06/12/26G00117/SG> .
                """, StandardCharsets.UTF_8);

        LegalActQueryService service = new LegalActQueryService(List.of(turtle));

        List<LegalActSummary> results = service.searchActs("melanoma", 10);

        assertEquals(1, results.size());
        assertEquals("26G00117", results.get(0).localId());
        assertEquals("2026-06-12", results.get(0).publicationDate());
        assertTrue(results.get(0).type().endsWith("#LEGGE"));
        assertTrue(service.findByLocalId("26G00117").isPresent());
        assertFalse(service.status().loadedFiles().isEmpty());
    }

    @Test
    void runsCustomSelectQuery() throws Exception {
        Path turtle = tempDir.resolve("gazzetta_metadata_delta.ttl");
        Files.writeString(turtle, """
                @prefix eli: <http://data.europa.eu/eli/ontology#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

                <http://www.gazzettaufficiale.it/eli/id/2026/06/15/26A02811/sg>
                  a eli:LegalResource ;
                  rdfs:label "Liquidazione coatta amministrativa" ;
                  eli:date_publication "2026-06-15"^^xsd:date ;
                  eli:type_document <http://www.gazzettaufficiale.it/eli/tables/resource-type#DECRETO> .
                """, StandardCharsets.UTF_8);

        LegalActQueryService service = new LegalActQueryService(List.of(turtle));

        SparqlQueryResult result = service.executeSelectQuery("""
                PREFIX eli: <http://data.europa.eu/eli/ontology#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

                SELECT ?act ?title ?date WHERE {
                  ?act a eli:LegalResource ;
                       rdfs:label ?title ;
                       eli:date_publication ?date .
                }
                LIMIT 20
                """);

        assertEquals(List.of("act", "title", "date"), result.columns());
        assertEquals(1, result.rows().size());
        assertEquals("Liquidazione coatta amministrativa", result.rows().get(0).get("title"));
        assertEquals("2026-06-15", result.rows().get(0).get("date"));
    }
}
