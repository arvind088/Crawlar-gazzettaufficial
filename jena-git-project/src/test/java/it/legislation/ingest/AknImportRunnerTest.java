package it.legislation.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import it.legislation.eli.EliUriService;

class AknImportRunnerTest {

    @TempDir
    Path temp;

    @Test
    void importsVersionsRejectsBadFilesAndIsIdempotent() throws IOException {
        Path in = Files.createDirectories(temp.resolve("raw"));
        Files.copy(Path.of("src/test/resources/akn/legge-2020-27-original.xml"), in.resolve("a.xml"));
        Files.copy(Path.of("src/test/resources/akn/dlgs-2003-196-vigente-2026-02-20.xml"), in.resolve("b.xml"));
        Files.writeString(in.resolve("broken.xml"), "<notAkn/>");
        Path layer1 = temp.resolve("layer1.ttl");
        Path layer2 = temp.resolve("layer2.ttl");
        AknImportRunner runner = new AknImportRunner(new EliUriService("https://example.test"));

        AknImportRunner.Summary first = runner.run(in, layer1, layer2);
        AknImportRunner.Summary second = runner.run(in, layer1, layer2);

        assertEquals(3, first.filesRead());
        assertEquals(2, first.acts());
        assertEquals(2, first.expressions());
        assertEquals(1, first.rejected().size());
        assertTrue(first.written());
        assertFalse(second.written(), "same input must not rewrite the files");
        Model acts = RDFDataMgr.loadModel(layer1.toString());
        Model versions = RDFDataMgr.loadModel(layer2.toString());
        assertTrue(acts.size() > 20);
        // L. 27/2020 is an original text: Layer 1. D.Lgs. 196/2003 here is a consolidated version: its
        // version and file go to Layer 2, the act itself to Layer 1.
        org.apache.jena.rdf.model.Property versionDate =
                acts.createProperty("http://data.europa.eu/eli/ontology#version_date");
        assertTrue(acts.listSubjectsWithProperty(versionDate).toList().stream()
                .allMatch(v -> v.getURI().endsWith("/ita/original")), "Layer 1 holds only original versions");
        assertEquals(1, versions.listSubjectsWithProperty(versionDate).toList().size());
        assertTrue(versions.listSubjectsWithProperty(versionDate).next().getURI().contains("/ita/vigente/2026-02-20"));
        assertFalse(acts.listStatements().toList().stream()
                .anyMatch(st -> st.getPredicate().getNameSpace().startsWith("http://example.org/")),
                "ELI and standard vocabularies only");
    }

    @Test
    void comparesImportedActsWithNormattivaCounts() throws IOException {
        Path in = Files.createDirectories(temp.resolve("raw"));
        Files.copy(Path.of("src/test/resources/akn/legge-2020-27-original.xml"), in.resolve("a.xml"));
        Files.createDirectories(in.resolve("corpus"));
        Files.writeString(in.resolve("corpus/counts.tsv"),
                "act_type\tyear\tcount\tcounted_at\nLEGGE\t2020\t1\t\nDECRETO-LEGGE\t2020\t3\t\n");

        AknImportRunner.Summary summary = new AknImportRunner(new EliUriService("https://example.test"))
                .run(in, temp.resolve("l1.ttl"), temp.resolve("l2.ttl"));

        assertEquals(2, summary.countChecks().size());
        AknImportRunner.CountCheck dl = summary.countChecks().get(0);
        AknImportRunner.CountCheck legge = summary.countChecks().get(1);
        assertEquals("DECRETO-LEGGE", dl.actType());
        assertFalse(dl.ok());
        assertEquals(0, dl.imported());
        assertTrue(legge.ok());
        assertTrue(legge.missing().isEmpty());
    }

    @Test
    void namesTheActsMissingFromTheExport() throws IOException {
        Path in = Files.createDirectories(temp.resolve("raw2"));
        Files.copy(Path.of("src/test/resources/akn/legge-2020-27-original.xml"), in.resolve("a.xml"));
        Files.createDirectories(in.resolve("corpus/lists"));
        Files.writeString(in.resolve("corpus/counts.tsv"), "act_type\tyear\tcount\tcounted_at\nLEGGE\t2020\t2\t\n");
        Files.writeString(in.resolve("corpus/lists/LEGGE_2020_M_AKN.json"),
                "[{\"codiceRedazionale\":\"20G00045\"},{\"codiceRedazionale\":\"20G00999\",\"dataGU\":\"2020-12-31\",\"descrizioneAtto\":\"LEGGE 30 dicembre 2020, n. 999\"}]");

        AknImportRunner.Summary summary = new AknImportRunner(new EliUriService("https://example.test"))
                .run(in, temp.resolve("l1b.ttl"), temp.resolve("l2b.ttl"));

        AknImportRunner.CountCheck legge = summary.countChecks().get(0);
        assertFalse(legge.ok());
        assertEquals(1, legge.missing().size());
        assertTrue(legge.missing().get(0).startsWith("20G00999"), legge.missing().get(0));
    }
}
