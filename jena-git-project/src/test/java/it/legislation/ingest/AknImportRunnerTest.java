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
        Path out = temp.resolve("out.ttl");
        AknImportRunner runner = new AknImportRunner(new EliUriService("https://example.test"));

        AknImportRunner.Summary first = runner.run(in, out);
        AknImportRunner.Summary second = runner.run(in, out);

        assertEquals(3, first.filesRead());
        assertEquals(2, first.acts());
        assertEquals(2, first.expressions());
        assertEquals(1, first.rejected().size());
        assertTrue(first.written());
        assertFalse(second.written(), "same input must not rewrite the file");
        Model model = RDFDataMgr.loadModel(out.toString());
        assertTrue(model.size() > 20);
    }
}
