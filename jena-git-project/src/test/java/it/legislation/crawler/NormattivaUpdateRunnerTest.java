package it.legislation.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NormattivaUpdateRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsOfficialUpdatedActsEndpointAndRequestBody() {
        String endpoint = NormattivaUpdateRunner.updatedActsEndpoint("https://api.normattiva.it/t/normattiva.api/");
        String body = NormattivaUpdateRunner.updatedActsRequestBody(
                OffsetDateTime.parse("2026-08-01T00:00:00Z"),
                OffsetDateTime.parse("2026-08-02T00:00:00Z")
        );

        assertEquals("https://api.normattiva.it/t/normattiva.api/api/v1/ricerca/aggiornati", endpoint);
        assertTrue(body.contains("\"dataInizioAggiornamento\":\"2026-08-01T00:00Z\""));
        assertTrue(body.contains("\"dataFineAggiornamento\":\"2026-08-02T00:00Z\""));
    }

    @Test
    void buildsOfficialActDetailEndpointAndRequestBody() {
        String endpoint = NormattivaUpdateRunner.actDetailEndpoint("https://api.normattiva.it/t/normattiva.api/");
        String body = NormattivaUpdateRunner.actDetailRequestBody(new NormattivaUpdateRunner.NormattivaOpenDataUpdate(
                "005G0104",
                "2005-05-16",
                "DECRETO LEGISLATIVO",
                "82",
                "Codice dell'amministrazione digitale",
                "2005-03-07",
                "2025-03-20",
                "Versione 52"
        ));

        assertEquals("https://api.normattiva.it/t/normattiva.api/api/v1/atto/dettaglio-atto", endpoint);
        assertTrue(body.contains("\"dataGU\":\"2005-05-16\""));
        assertTrue(body.contains("\"codiceRedazionale\":\"005G0104\""));
    }

    @Test
    void includesResponseBodyInOpenDataFailureMessage() {
        String message = NormattivaUpdateRunner.openDataFailureMessage(
                409,
                """
                        {
                          "error": "Date range conflict"
                        }
                        """
        );

        assertTrue(message.contains("HTTP 409"));
        assertTrue(message.contains("Date range conflict"));
    }

    @Test
    void parsesOpenDataUpdatedActsResponse() throws Exception {
        String json = """
                {
                  "listaAtti": [
                    {
                      "codiceRedazionale": "005G0104",
                      "dataGU": "2005-05-16",
                      "denominazioneAtto": "DECRETO LEGISLATIVO",
                      "numeroAtto": "82",
                      "titoloAtto": "Codice dell'amministrazione digitale",
                      "dataEmanazione": "2005-03-07",
                      "dataUltimaModifica": "2025-03-20",
                      "ultimiAttiModificanti": "Versione 52"
                    }
                  ]
                }
                """;

        List<NormattivaUpdateRunner.NormattivaOpenDataUpdate> updates =
                NormattivaUpdateRunner.parseOpenDataUpdates(json);

        assertEquals(1, updates.size());
        assertEquals("005G0104", updates.get(0).codiceRedazionale());
        assertEquals("Codice dell'amministrazione digitale", updates.get(0).titoloAtto());
        assertEquals("2025-03-20", updates.get(0).dataUltimaModifica());
    }

    @Test
    void importsSavedOpenDataUpdateJson() throws Exception {
        Path input = tempDir.resolve("normattiva_updates.json");
        Path output = tempDir.resolve("normattiva_updates.tsv");
        Files.writeString(input, """
                {
                  "listaAtti": [
                    {
                      "codiceRedazionale": "005G0104",
                      "dataGU": "2005-05-16",
                      "denominazioneAtto": "DECRETO LEGISLATIVO",
                      "numeroAtto": "82",
                      "titoloAtto": "Codice dell'amministrazione digitale",
                      "dataEmanazione": "2005-03-07",
                      "dataUltimaModifica": "2025-03-20",
                      "ultimiAttiModificanti": "Versione 52"
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        NormattivaUpdateRunner.ImportResult result = NormattivaUpdateRunner.importOpenDataUpdates(input, output);

        String tsv = Files.readString(output, StandardCharsets.UTF_8);
        assertEquals(1, result.rowsWritten());
        assertTrue(tsv.contains("codice_redazionale"));
        assertTrue(tsv.contains("005G0104"));
        assertTrue(tsv.contains("Codice dell'amministrazione digitale"));
    }

    @Test
    void parsesActDetailResponse() throws Exception {
        String json = """
                {
                  "success": true,
                  "data": {
                    "atto": {
                      "titolo": "Codice dell'amministrazione digitale",
                      "sottoTitolo": "Testo vigente",
                      "tipoProvvedimentoDescrizione": "DECRETO LEGISLATIVO",
                      "tipoProvvedimentoCodice": "DLGS",
                      "annoProvvedimento": 2005,
                      "meseProvvedimento": 3,
                      "giornoProvvedimento": 7,
                      "numeroProvvedimento": 82,
                      "dataPubblicazioneInGazzetta": "2005-05-16",
                      "articoloDataInizioVigenza": "2025-03-20",
                      "testoInVigore": "vigente",
                      "articoloHtml": "<p>Articolo 1</p>"
                    }
                  }
                }
                """;

        List<NormattivaUpdateRunner.NormattivaActDetail> details =
                NormattivaUpdateRunner.parseActDetails(json);

        assertEquals(1, details.size());
        NormattivaUpdateRunner.NormattivaActDetail detail = details.get(0);
        assertEquals("Codice dell'amministrazione digitale", detail.title());
        assertEquals("DECRETO LEGISLATIVO", detail.actType());
        assertEquals("2005-03-07", detail.actDate());
        assertEquals("82", detail.actNumber());
        assertEquals("2025-03-20", detail.forceStartDate());
        assertTrue(detail.articleHtml().contains("Articolo 1"));
    }

    @Test
    void fetchesActDetailsWithoutWritingRdf() throws Exception {
        AtomicReference<String> requestedUrl = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        NormattivaUpdateRunner.NormattivaOpenDataUpdate update = new NormattivaUpdateRunner.NormattivaOpenDataUpdate(
                "005G0104",
                "2005-05-16",
                "DECRETO LEGISLATIVO",
                "82",
                "Codice dell'amministrazione digitale",
                "2005-03-07",
                "2025-03-20",
                "Versione 52"
        );

        List<NormattivaUpdateRunner.NormattivaActDetail> details = NormattivaUpdateRunner.fetchActDetails(
                "https://api.normattiva.it/t/normattiva.api",
                update,
                (url, jsonBody) -> {
                    requestedUrl.set(url);
                    requestBody.set(jsonBody);
                    return """
                            {
                              "data": {
                                "atto": {
                                  "titolo": "Codice dell'amministrazione digitale",
                                  "annoProvvedimento": 2005,
                                  "meseProvvedimento": 3,
                                  "giornoProvvedimento": 7,
                                  "numeroProvvedimento": 82
                                }
                              }
                            }
                            """;
                }
        );

        assertEquals("https://api.normattiva.it/t/normattiva.api/api/v1/atto/dettaglio-atto", requestedUrl.get());
        assertTrue(requestBody.get().contains("\"codiceRedazionale\":\"005G0104\""));
        assertEquals(1, details.size());
        assertEquals("Codice dell'amministrazione digitale", details.get(0).title());
    }

    @Test
    void importsSavedActDetailJsonWithCandidateMetadata() throws Exception {
        Path input = tempDir.resolve("normattiva_details.json");
        Path output = tempDir.resolve("normattiva_details.tsv");
        Files.writeString(input, """
                {
                  "details": [
                    {
                      "candidate": {
                        "codiceRedazionale": "005G0104",
                        "dataGU": "2005-05-16",
                        "denominazioneAtto": "DECRETO LEGISLATIVO",
                        "numeroAtto": "82",
                        "titoloAtto": "Codice dell'amministrazione digitale"
                      },
                      "response": {
                        "data": {
                          "atto": {
                            "titolo": "Codice dell'amministrazione digitale",
                            "tipoProvvedimentoDescrizione": "DECRETO LEGISLATIVO",
                            "annoProvvedimento": 2005,
                            "meseProvvedimento": 3,
                            "giornoProvvedimento": 7,
                            "numeroProvvedimento": 82,
                            "articoloHtml": "<p>Testo modificato.</p>"
                          }
                        }
                      }
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        NormattivaUpdateRunner.ImportResult result = NormattivaUpdateRunner.importActDetails(input, output);

        String tsv = Files.readString(output, StandardCharsets.UTF_8);
        assertEquals(1, result.rowsWritten());
        assertTrue(tsv.contains("005G0104"));
        assertTrue(tsv.contains("Codice dell'amministrazione digitale"));
        assertTrue(tsv.contains("Testo modificato."));
    }

    @Test
    void readsOpenDataUpdateCandidatesFromTsv() throws Exception {
        Path updatesPath = tempDir.resolve("normattiva_updates.tsv");
        Files.write(updatesPath, List.of(
                "codice_redazionale\tdata_gu\tdenominazione_atto\tnumero_atto\ttitolo_atto\tdata_emanazione\tdata_ultima_modifica\tultimi_atti_modificanti\tendpoint\tfetched_at",
                "005G0104\t2005-05-16\tDECRETO LEGISLATIVO\t82\tCodice dell'amministrazione digitale\t2005-03-07\t2025-03-20\tVersione 52\thttps://api.normattiva.it/t/normattiva.api/api/v1/ricerca/aggiornati\t2026-08-29T10:00:00Z"
        ), StandardCharsets.UTF_8);

        List<NormattivaUpdateRunner.NormattivaOpenDataUpdate> updates =
                NormattivaUpdateRunner.readOpenDataUpdates(updatesPath);

        assertEquals(1, updates.size());
        assertEquals("005G0104", updates.get(0).codiceRedazionale());
        assertEquals("2005-05-16", updates.get(0).dataGu());
        assertEquals("Versione 52", updates.get(0).ultimiAttiModificanti());
    }

    @Test
    void fetchesAndWritesActDetailsFromCandidateTsv() throws Exception {
        Path updatesPath = tempDir.resolve("normattiva_updates.tsv");
        Path detailsPath = tempDir.resolve("normattiva_details.tsv");
        AtomicReference<String> requestedUrl = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        Files.write(updatesPath, List.of(
                "codice_redazionale\tdata_gu\tdenominazione_atto\tnumero_atto\ttitolo_atto\tdata_emanazione\tdata_ultima_modifica\tultimi_atti_modificanti\tendpoint\tfetched_at",
                "005G0104\t2005-05-16\tDECRETO LEGISLATIVO\t82\tCodice dell'amministrazione digitale\t2005-03-07\t2025-03-20\tVersione 52\thttps://api.normattiva.it/t/normattiva.api/api/v1/ricerca/aggiornati\t2026-08-29T10:00:00Z"
        ), StandardCharsets.UTF_8);

        NormattivaUpdateRunner.DetailFetchResult result = NormattivaUpdateRunner.fetchAndWriteActDetails(
                "https://api.normattiva.it/t/normattiva.api",
                updatesPath,
                detailsPath,
                10,
                (url, jsonBody) -> {
                    requestedUrl.set(url);
                    requestBody.set(jsonBody);
                    return """
                            {
                              "data": {
                                "atto": {
                                  "titolo": "Codice dell'amministrazione digitale",
                                  "tipoProvvedimentoDescrizione": "DECRETO LEGISLATIVO",
                                  "annoProvvedimento": 2005,
                                  "meseProvvedimento": 3,
                                  "giornoProvvedimento": 7,
                                  "numeroProvvedimento": 82,
                                  "dataPubblicazioneInGazzetta": "2005-05-16",
                                  "articoloDataInizioVigenza": "2025-03-20"
                                }
                              }
                            }
                            """;
                }
        );

        String output = Files.readString(detailsPath, StandardCharsets.UTF_8);
        assertEquals("https://api.normattiva.it/t/normattiva.api/api/v1/atto/dettaglio-atto", requestedUrl.get());
        assertTrue(requestBody.get().contains("\"dataGU\":\"2005-05-16\""));
        assertEquals(1, result.candidatesRead());
        assertEquals(1, result.detailsWritten());
        assertTrue(output.contains("codice_redazionale"));
        assertTrue(output.contains("005G0104"));
        assertTrue(output.contains("Codice dell'amministrazione digitale"));
        assertTrue(output.contains("2025-03-20"));
    }

    @Test
    void scansRelationEvidenceFromDetailTsv() throws Exception {
        Path detailsPath = tempDir.resolve("normattiva_details.tsv");
        Files.write(detailsPath, List.of(
                "codice_redazionale\tdata_gu\ttitolo_atto\tdenominazione_atto\tnumero_atto\tdetail_title\tdetail_subtitle\tact_type\tact_type_code\tact_date\tact_number\tpublication_date\tforce_start_date\tforce_end_date\ttext_in_force\tarticle_html\tendpoint\tfetched_at",
                "005G0104\t2005-05-16\tCodice dell'amministrazione digitale\tDECRETO LEGISLATIVO\t82\tCodice dell'amministrazione digitale\tTesto vigente\tDECRETO LEGISLATIVO\tDLGS\t2005-03-07\t82\t2005-05-16\t2025-03-20\t\tvigente\t<p>Il decreto-legge e' convertito, con modificazioni, dalla legge.</p>\thttps://api.normattiva.it/t/normattiva.api/api/v1/atto/dettaglio-atto\t2026-08-29T10:00:00Z"
        ), StandardCharsets.UTF_8);

        List<NormattivaUpdateRunner.NormattivaRelationEvidence> evidence =
                NormattivaUpdateRunner.scanRelationEvidence(detailsPath, 10);

        assertEquals(1, evidence.size());
        assertEquals("005G0104", evidence.get(0).code());
        assertEquals("conversion", evidence.get(0).evidenceType());
        assertTrue(evidence.get(0).evidenceText().contains("convertito"));
    }

    @Test
    void writesRelationEvidenceReportWithoutWritingRdf() throws Exception {
        Path detailsPath = tempDir.resolve("normattiva_details.tsv");
        Path evidencePath = tempDir.resolve("normattiva_relation_evidence.tsv");
        Files.write(detailsPath, List.of(
                "codice_redazionale\tdata_gu\ttitolo_atto\tdenominazione_atto\tnumero_atto\tdetail_title\tdetail_subtitle\tact_type\tact_type_code\tact_date\tact_number\tpublication_date\tforce_start_date\tforce_end_date\ttext_in_force\tarticle_html\tendpoint\tfetched_at",
                "005G0104\t2005-05-16\tCodice dell'amministrazione digitale\tDECRETO LEGISLATIVO\t82\tCodice dell'amministrazione digitale\tTesto vigente\tDECRETO LEGISLATIVO\tDLGS\t2005-03-07\t82\t2005-05-16\t2025-03-20\t\tvigente\t<p>Testo modificato dalla legge successiva.</p>\thttps://api.normattiva.it/t/normattiva.api/api/v1/atto/dettaglio-atto\t2026-08-29T10:00:00Z",
                "25G00041\t2025-03-24\tDisposizioni urgenti\tDECRETO LEGGE\t32\tDisposizioni urgenti\tTesto vigente\tDECRETO LEGGE\tDL\t2025-03-11\t32\t2025-03-24\t2025-05-08\t\tvigente\t<p>Testo senza parole relazionali.</p>\thttps://api.normattiva.it/t/normattiva.api/api/v1/atto/dettaglio-atto\t2026-08-29T10:00:00Z"
        ), StandardCharsets.UTF_8);

        NormattivaUpdateRunner.EvidenceScanResult result =
                NormattivaUpdateRunner.runEvidenceScan(detailsPath, evidencePath, 10);

        String output = Files.readString(evidencePath, StandardCharsets.UTF_8);
        assertEquals(2, result.detailsRead());
        assertEquals(1, result.evidenceRows());
        assertTrue(output.contains("evidence_type"));
        assertTrue(output.contains("modification"));
        assertTrue(output.contains("005G0104"));
    }

    @Test
    void extractsRelationCandidatesOnlyFromEvidenceRowsWithEliHttpUris() throws Exception {
        Path evidencePath = tempDir.resolve("normattiva_relation_evidence.tsv");
        Files.write(evidencePath, List.of(
                "codice_redazionale\tdata_gu\ttitolo_atto\tdetail_title\tevidence_type\tevidence_text",
                "25G00041\t2025-03-24\tConversione\tConversione\tconversion\tIl decreto https://www.gazzettaufficiale.it/eli/id/2025/03/01/25G00028/sg e' convertito dalla legge https://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg",
                "005G0104\t2005-05-16\tCAD\tCAD\tmodification\tTesto con urn:nir:stato:legge:2025-01-01;1 ma senza ELI HTTP."
        ), StandardCharsets.UTF_8);

        List<NormattivaUpdateRunner.NormattivaRelationCandidate> candidates =
                NormattivaUpdateRunner.extractRelationCandidates(evidencePath, 10);

        assertEquals(1, candidates.size());
        NormattivaUpdateRunner.NormattivaRelationCandidate candidate = candidates.get(0);
        assertEquals("https://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg", candidate.sourceUri());
        assertEquals("https://www.gazzettaufficiale.it/eli/id/2025/03/01/25G00028/sg", candidate.targetUri());
        assertEquals("eli:commences", candidate.relationType());
        assertEquals("needs_review", candidate.reviewStatus());
    }

    @Test
    void runsOpenDataUpdateDiscoveryWithoutInferringRelations() throws Exception {
        Path updatesPath = tempDir.resolve("normattiva_updates.tsv");
        Path relationsPath = tempDir.resolve("normattiva_relations.tsv");
        Path rdfPath = tempDir.resolve("normattiva_auto.ttl");
        AtomicReference<String> requestedUrl = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        NormattivaUpdateRunner.Result result = NormattivaUpdateRunner.runOpenData(
                "https://api.normattiva.it/t/normattiva.api",
                OffsetDateTime.parse("2026-08-01T00:00:00Z"),
                OffsetDateTime.parse("2026-08-02T00:00:00Z"),
                updatesPath,
                relationsPath,
                rdfPath,
                (url, jsonBody) -> {
                    requestedUrl.set(url);
                    requestBody.set(jsonBody);
                    return """
                            {
                              "listaAtti": [
                                {
                                  "codiceRedazionale": "005G0104",
                                  "titoloAtto": "Codice dell'amministrazione digitale",
                                  "dataUltimaModifica": "2025-03-20"
                                }
                              ]
                            }
                            """;
                }
        );

        assertEquals("https://api.normattiva.it/t/normattiva.api/api/v1/ricerca/aggiornati", requestedUrl.get());
        assertTrue(requestBody.get().contains("dataInizioAggiornamento"));
        assertEquals(1, result.updatesRead());
        assertEquals(0, result.relationRows());
        assertTrue(Files.readString(updatesPath, StandardCharsets.UTF_8).contains("005G0104"));
        assertTrue(Files.readString(updatesPath, StandardCharsets.UTF_8).contains("Codice dell'amministrazione digitale"));
        assertTrue(Files.notExists(relationsPath));
        assertTrue(Files.notExists(rdfPath));
    }

    @Test
    void parsesNormattivaUpdateCardsAndInfersRelations() {
        String html = """
                <section>
                  <article>
                    <h3>"COMMISSARI STRAORDINARI E CONCESSIONI - CONVERSIONE IN LEGGE"</h3>
                    <p>La Banca Dati e' aggiornata in multivigenza con le modifiche apportate dal
                      <a href="/uri-res/N2Ls?urn:nir:stato:decreto.legge:2026-03-11;32">D.L. 32 del 2026</a>,
                      convertito, con modificazioni, dalla
                      <a href="/uri-res/N2Ls?urn:nir:stato:legge:2026-05-08;71">L. 71 del 2026</a>.
                    </p>
                    <time>5 giugno 2026</time>
                  </article>
                </section>
                """;

        List<NormattivaUpdateRunner.NormattivaUpdate> updates =
                NormattivaUpdateRunner.parseUpdates(html, "https://www.normattiva.it/");
        List<CleanModificationRecord> relations = NormattivaUpdateRunner.inferRelations(updates);

        assertEquals(1, updates.size());
        assertEquals(2, updates.get(0).normattivaLinks().size());
        assertEquals(1, relations.size());
        assertTrue(relations.get(0).getSubjectUri().contains("legge:2026-05-08;71"));
        assertTrue(relations.get(0).getObjectUri().contains("decreto.legge:2026-03-11;32"));
    }

    @Test
    void writesNormattivaUpdateOutputs() throws Exception {
        Path updatesPath = tempDir.resolve("normattiva_updates.tsv");
        Path relationsPath = tempDir.resolve("normattiva_relations.tsv");
        Path rdfPath = tempDir.resolve("normattiva_auto.ttl");
        CleanModificationRecord relation = CleanModificationRecord.of(
                "https://www.normattiva.it/uri-res/N2Ls?urn:nir:stato:legge:2026-05-08;71",
                "https://www.normattiva.it/uri-res/N2Ls?urn:nir:stato:decreto.legge:2026-03-11;32",
                "convertito, con modificazioni"
        );

        NormattivaUpdateRunner.writeUpdates(List.of(
                new NormattivaUpdateRunner.NormattivaUpdate(
                        "Conversione in legge",
                        "5 giugno 2026",
                        "La Banca Dati e' aggiornata",
                        List.of(relation.getSubjectUri(), relation.getObjectUri())
                )
        ), updatesPath, OffsetDateTime.parse("2026-06-19T10:00:00+02:00"));
        NormattivaUpdateRunner.writeRelations(List.of(relation), relationsPath);
        NormattivaUpdateRunner.writeRdf(List.of(relation), rdfPath);

        assertTrue(Files.readString(updatesPath, StandardCharsets.UTF_8).contains("Conversione in legge"));
        assertTrue(Files.readString(relationsPath, StandardCharsets.UTF_8).contains("convertito"));
        assertTrue(Files.readString(rdfPath, StandardCharsets.UTF_8).contains("modifies"));
    }
}
