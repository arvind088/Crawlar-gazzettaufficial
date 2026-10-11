package it.legislation.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssessmentHistoryTest {

    @TempDir
    Path temp;

    private Model run(Path file, String day, String status) throws IOException {
        AssessmentHistory history = AssessmentHistory.read(file);
        Model model = ModelFactory.createDefaultModel();
        model.add(history.previous());
        Resource decree = model.createResource("https://example.test/eli/id/2026/09/28/26G00186/sg");
        Property statusProperty = model.createProperty(AssessmentHistory.ILG, "conversionStatus");
        history.record(model, decree, "ConversionCheck", "https://example.test/conversion/26G00186",
                LocalDate.parse(day), new AssessmentHistory.Values().put(statusProperty, model.createLiteral(status)).build());
        try (var out = Files.newOutputStream(file)) {
            RDFDataMgr.write(out, model, RDFFormat.TURTLE_PRETTY);
        }
        return model;
    }

    @Test
    void writesANewAssessmentOnlyWhenTheResultChangesAndKeepsHistory() throws IOException {
        Path file = temp.resolve("checks.ttl");
        run(file, "2026-10-11", "PENDING");
        run(file, "2026-10-12", "PENDING");
        Model last = run(file, "2026-11-20", "CONVERTED");

        String ttl = Files.readString(file);
        assertTrue(ttl.contains("conversion/26G00186/2026-10-11"), "first assessment kept");
        assertFalse(ttl.contains("conversion/26G00186/2026-10-12"), "unchanged result: no new node");
        assertTrue(ttl.contains("conversion/26G00186/2026-11-20"), "changed result: new node");
        assertTrue(ttl.contains("\"PENDING\"") && ttl.contains("\"CONVERTED\""), "both statuses in the history");

        AssessmentHistory history = AssessmentHistory.read(file);
        assertEquals(java.util.Set.of(AssessmentHistory.ILG + "conversionStatus \"CONVERTED\""),
                history.latestValues(last.createResource("https://example.test/eli/id/2026/09/28/26G00186/sg"),
                        "ConversionCheck").orElseThrow());
    }
}
