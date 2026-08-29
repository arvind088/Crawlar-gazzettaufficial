package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegalActQueryServiceTest {

    @TempDir
    Path tempDir;

    private final List<LegalActQueryService> services = new ArrayList<>();

    @AfterEach
    void closeServices() {
        services.forEach(LegalActQueryService::closeForTests);
    }

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

        LegalActQueryService service = service(turtle);

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

        LegalActQueryService service = service(turtle);

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

    @Test
    void searchesRelationOnlyActsByIdFromUri() throws Exception {
        Path turtle = tempDir.resolve("normattiva_modifications.ttl");
        Files.writeString(turtle, """
                @prefix eli: <http://data.europa.eu/eli/ontology#> .
                @prefix ilg: <http://example.org/italian-legislation/ontology#> .

                <http://www.gazzettaufficiale.it/eli/id/2025/03/01/25G00028/sg>
                  a eli:LegalResource ;
                  ilg:modifies <http://www.gazzettaufficiale.it/eli/id/2025/01/16/25G00006/sg> .

                <http://www.gazzettaufficiale.it/eli/id/2025/01/16/25G00006/sg>
                  a eli:LegalResource ;
                  ilg:modifiedBy <http://www.gazzettaufficiale.it/eli/id/2025/03/01/25G00028/sg> .
                """, StandardCharsets.UTF_8);

        LegalActQueryService service = service(turtle);

        List<LegalActSummary> results = service.searchActs("25G00028", 10);

        assertEquals(1, results.size());
        assertEquals("25G00028", results.get(0).localId());
        assertTrue(service.findByLocalId("25G00028").isPresent());
        assertTrue(service.rdfForLocalId("25G00028").orElseThrow().contains("25G00006"));
    }

    @Test
    void exportsRdfForOneLegalAct() throws Exception {
        Path turtle = tempDir.resolve("gazzetta_metadata_delta.ttl");
        Files.writeString(turtle, """
                @prefix eli: <http://data.europa.eu/eli/ontology#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

                <http://www.gazzettaufficiale.it/eli/id/2026/06/15/26A02811/sg>
                  a eli:LegalResource ;
                  rdfs:label "Liquidazione coatta amministrativa" ;
                  eli:date_publication "2026-06-15"^^xsd:date ;
                  eli:id_local "26A02811" .
                """, StandardCharsets.UTF_8);

        LegalActQueryService service = service(turtle);

        String rdf = service.rdfForLocalId("26A02811").orElseThrow();

        assertTrue(rdf.contains("26A02811"));
        assertTrue(rdf.contains("Liquidazione coatta amministrativa"));
        assertTrue(service.rdfForLocalId("missing").isEmpty());
    }

    @Test
    void returnsLinkedDataResourceWithExpressionsManifestationsAndRelations() throws Exception {
        Path turtle = tempDir.resolve("linked_data.ttl");
        Files.writeString(turtle, """
                @prefix eli: <http://data.europa.eu/eli/ontology#> .
                @prefix ilg: <http://example.org/italian-legislation/ontology#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

                <http://www.gazzettaufficiale.it/eli/id/2026/06/12/26G00117/sg>
                  a eli:LegalResource ;
                  rdfs:label "Disposizioni per la prevenzione del melanoma" ;
                  eli:date_publication "2026-06-12"^^xsd:date ;
                  eli:id_local "26G00117" ;
                  eli:version <http://www.gazzettaufficiale.it/eli/tables/versions#ORIGINAL> ;
                  eli:is_realized_by <http://www.gazzettaufficiale.it/eli/id/2026/06/12/26G00117/sg/ita> ;
                  ilg:modifies <http://www.gazzettaufficiale.it/eli/id/2026/05/01/26G00099/sg> .

                <http://www.gazzettaufficiale.it/eli/id/2026/06/12/26G00117/sg/ita>
                  a eli:LegalExpression ;
                  eli:language <http://publications.europa.eu/resource/authority/language/ITA> ;
                  eli:is_embodied_by <http://www.gazzettaufficiale.it/eli/id/2026/06/12/26G00117/sg/ita/html> .

                <http://www.gazzettaufficiale.it/eli/id/2026/06/12/26G00117/sg/ita/html>
                  a eli:Format ;
                  eli:format <http://www.iana.org/assignments/media-types/text/html> .

                <http://www.gazzettaufficiale.it/eli/id/2026/05/01/26G00099/sg>
                  a eli:LegalResource ;
                  rdfs:label "Previous related act" ;
                  eli:id_local "26G00099" ;
                  ilg:modifiedBy <http://www.gazzettaufficiale.it/eli/id/2026/06/12/26G00117/sg> .
                """, StandardCharsets.UTF_8);

        LegalActQueryService service = service(turtle);

        LinkedDataResource resource = service.findLinkedDataResource("26G00117").orElseThrow();

        assertEquals("26G00117", resource.localId());
        assertEquals("Disposizioni per la prevenzione del melanoma", resource.title());
        assertEquals(1, resource.expressions().size());
        assertEquals(1, resource.manifestations().size());
        assertTrue(resource.expressions().get(0).version().endsWith("#ORIGINAL"));
        assertTrue(resource.manifestations().get(0).format().endsWith("text/html"));
        assertTrue(resource.outgoingRelations().stream()
                .anyMatch(relation -> "ilg:modifies".equals(relation.predicateLabel())
                        && "Modifies".equals(relation.displayLabel())
                        && relation.important()
                        && "26G00099".equals(relation.resourceLocalId())));
        assertTrue(resource.incomingRelations().stream()
                .anyMatch(relation -> "ilg:modifiedBy".equals(relation.predicateLabel())
                        && "Modified by".equals(relation.displayLabel())
                        && relation.important()
                        && "26G00099".equals(relation.resourceLocalId())));
    }

    @Test
    void loadsCommittedMultiVersionSample() throws Exception {
        LegalActQueryService service = service(Path.of("data", "rdf", "normattiva_multiversion_sample.ttl"));

        LinkedDataResource resource = service.findLinkedDataResource("005G0104").orElseThrow();

        assertEquals("005G0104", resource.localId());
        assertEquals("Codice dell'amministrazione digitale", resource.title());
        assertEquals(2, resource.expressions().size());
        assertEquals(2, resource.manifestations().size());
        assertTrue(resource.expressions().stream()
                .anyMatch(expression -> expression.version().endsWith("#ORIGINALE_V0")));
        assertTrue(resource.expressions().stream()
                .anyMatch(expression -> expression.version().endsWith("#VIGENZA_20250320_V52")));
    }

    private LegalActQueryService service(Path turtle) throws Exception {
        LegalActQueryService service = new LegalActQueryService(List.of(turtle));
        services.add(service);
        return service;
    }
}
