package it.legislation.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import it.legislation.eli.EliUriService;
import it.legislation.relations.RelationDetector.Conversion;
import it.legislation.relations.RelationDetector.ConversionStatus;
import it.legislation.relations.RelationDetector.Property;
import it.legislation.relations.RelationDetector.Relation;

/**
 * End to end on four real Normattiva files, trimmed: L. 27/2020 (conversion law),
 * D.L. 18/2020 (the decree it converts), D.Lgs. 101/2018 (modifier) and the
 * 2026 version of D.Lgs. 196/2003 (modified, with its lifecycle).
 */
class RelationRunnerTest {

    @TempDir
    Path temp;

    private RelationRunner.Summary run(List<Path> acts, Path raw) throws IOException {
        return new RelationRunner().run(acts, raw, temp.resolve("relations.ttl"), temp.resolve("conversions.tsv"),
                temp.resolve("relations.tsv"), LocalDate.of(2026, 10, 11));
    }

    private List<Path> importLayers(Path raw, String name) throws IOException {
        List<Path> layers = List.of(temp.resolve(name + "-layer1.ttl"), temp.resolve(name + "-layer2.ttl"));
        new AknImportRunner(new EliUriService("https://example.test")).run(raw, layers.get(0), layers.get(1));
        return layers;
    }

    @Test
    void findsConversionAmendmentsAndRepeals() throws IOException {
        Path raw = Path.of("src/test/resources/akn-relations");
        List<Path> acts = importLayers(raw, "acts");

        RelationRunner.Summary first = run(acts, raw);
        RelationRunner.Summary second = run(acts, raw);

        List<Conversion> conversions = first.result().conversions();
        assertEquals(1, conversions.size());
        Conversion dl18 = conversions.get(0);
        assertEquals("20G00034", dl18.decree().codice());
        assertEquals(ConversionStatus.CONVERTED, dl18.status(), "L. 27/2020's title says it converts it");
        assertEquals("20G00045", dl18.law().codice());
        assertEquals(43L, dl18.days(), "17 March to 29 April 2020");

        Relation lawAmendsDecree = find(first, "20G00045", "DECRETO-LEGGE 2020-03-17 n. 18");
        assertEquals(Property.AMENDS, lawAmendsDecree.property());

        Relation repeal = find(first, "20G00045", "DECRETO-LEGGE 2020-03-02 n. 9");
        assertEquals(Property.REPEALS, repeal.property(), "\"l'abrogazione dell'intero provvedimento\"");
        assertEquals(null, repeal.to(), "D.L. 9/2020 is not among the test files");

        Relation confirmed = find(first, "18G00129", "DECRETO_LEGISLATIVO 2003-06-30 n. 196");
        assertEquals(Property.AMENDS, confirmed.property());
        assertEquals("change list + history", confirmed.sources(), "D.Lgs. 196/2003's lifecycle names D.Lgs. 101/2018");
        assertTrue(confirmed.confirmedByLifecycle());

        assertEquals(2, first.result().indirectMods(), "notes about D.L. 18 and D.L. 9 on other destinations");

        String ttl = Files.readString(temp.resolve("relations.ttl"));
        assertTrue(ttl.contains("eli:amends") && ttl.contains("eli:repeals"), ttl);
        assertTrue(ttl.contains("eli:consolidates"), "a version linked to the act it contains");
        assertFalse(ttl.contains("example.org"), "ELI only, no custom vocabulary");
        assertTrue(Files.readString(temp.resolve("conversions.tsv"))
                .contains("20G00034\tDECRETO-LEGGE 2020-03-17 n. 18\t2020-03-17\tCONVERTED\t20G00045"));
        assertTrue(Files.readString(temp.resolve("relations.tsv")).startsWith("from_codice\tfrom\tproperty\tto_codice\tto\tsources"));
        assertTrue(first.written());
        assertFalse(second.written(), "same input must not rewrite the files");
    }

    private static Relation find(RelationRunner.Summary summary, String fromCodice, String to) {
        return summary.result().relations().stream()
                .filter(r -> r.from() != null && r.from().codice().equals(fromCodice) && r.toKey().toString().equals(to))
                .findFirst().orElseThrow(() -> new AssertionError("no relation " + fromCodice + " -> " + to));
    }

    @Test
    void aDecreeIsPendingUntilItsConversionLawArrives() throws IOException {
        // Day 1: the conversion law is not imported yet; day 2: it is.
        Path rawDay1 = Files.createDirectories(temp.resolve("raw1"));
        Path rawDay2 = Path.of("src/test/resources/akn-relations");
        try (var files = Files.list(rawDay2)) {
            for (Path f : files.filter(p -> !p.getFileName().toString().startsWith("legge")).toList()) {
                Files.copy(f, rawDay1.resolve(f.getFileName()));
            }
        }
        List<Path> acts1 = importLayers(rawDay1, "day1");
        List<Path> acts2 = importLayers(rawDay2, "day2");
        Path out = temp.resolve("relations.ttl");

        RelationRunner.Summary day1 = new RelationRunner().run(acts1, rawDay1, out, temp.resolve("c.tsv"),
                temp.resolve("r.tsv"), LocalDate.of(2020, 4, 1));
        RelationRunner.Summary day2 = new RelationRunner().run(acts2, rawDay2, out, temp.resolve("c.tsv"),
                temp.resolve("r.tsv"), LocalDate.of(2020, 5, 1));

        assertEquals(ConversionStatus.PENDING, day1.result().conversions().get(0).status(),
                "17 March + 15 days: still within 60 days");
        assertEquals(ConversionStatus.CONVERTED, day2.result().conversions().get(0).status());
        assertTrue(Files.readString(temp.resolve("c.tsv")).contains("\tCONVERTED\t"));
    }
}
