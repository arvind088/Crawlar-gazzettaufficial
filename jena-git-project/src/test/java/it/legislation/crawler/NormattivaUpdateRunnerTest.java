package it.legislation.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NormattivaUpdateRunnerTest {

    @TempDir
    Path tempDir;

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
