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

    private RelationRunner.Summary run(Path acts, Path raw) throws IOException {
        return new RelationRunner().run(acts, raw, temp.resolve("relations.ttl"), temp.resolve("conversions.tsv"),
                temp.resolve("relations.tsv"), LocalDate.of(2026, 10, 11));
    }

    @Test
    void findsConversionAmendmentsAndRepeals() throws IOException {
        Path raw = Path.of("src/test/resources/akn-relations");
        Path acts = temp.resolve("acts.ttl");
        new AknImportRunner(new EliUriService("https://example.test")).run(raw, acts);

        RelationRunner.Summary first = run(acts, raw);
        RelationRunner.Summary second = run(acts, raw);

        List<Conversion> conversions = first.result().conversions();
        assertEquals(1, conversions.size());
        Conversion dl18 = conversions.get(0);
        assertEquals("20G00034", dl18.decree().codice());
        assertEquals(ConversionStatus.CONVERTED, dl18.status());
        assertEquals("20G00045", dl18.law().codice());
        assertTrue(dl18.checks().c1() && dl18.checks().c2() && dl18.checks().c3()
                && dl18.checks().c4() && dl18.checks().c5() && dl18.checks().c6());
        assertEquals(43, dl18.checks().days(), "17 March to 29 April 2020");
        assertEquals("LEGGE 2020-04-24 n. 27", dl18.decreeNoteNamesLaw(), "the decree's own note agrees");

        Relation lawAmendsDecree = find(first, "20G00045", "DECRETO-LEGGE 2020-03-17 n. 18");
        assertEquals(Property.AMENDS, lawAmendsDecree.property());

        Relation repeal = find(first, "20G00045", "DECRETO-LEGGE 2020-03-02 n. 9");
        assertEquals(Property.REPEALS, repeal.property(), "\"l'abrogazione dell'intero provvedimento\"");
        assertEquals(null, repeal.to(), "D.L. 9/2020 is not among the test files");

        Relation confirmed = find(first, "18G00129", "DECRETO_LEGISLATIVO 2003-06-30 n. 196");
        assertEquals(Property.AMENDS, confirmed.property());
        assertEquals("A", confirmed.trust(), "D.Lgs. 196/2003's lifecycle names D.Lgs. 101/2018");
        assertTrue(confirmed.confirmedByLifecycle());

        assertEquals(2, first.result().indirectMods(), "notes about D.L. 18 and D.L. 9 on other destinations");

        String ttl = Files.readString(temp.resolve("relations.ttl"));
        assertTrue(ttl.contains("ilg:converts"), ttl);
        assertTrue(Files.readString(temp.resolve("conversions.tsv")).contains("20G00034\tDECRETO-LEGGE 2020-03-17 n. 18\t2020-03-17\tCONVERTED\t20G00045"));
        assertTrue(first.written());
        assertFalse(second.written(), "same input must not rewrite the files");
    }

    private static Relation find(RelationRunner.Summary summary, String fromCodice, String to) {
        return summary.result().relations().stream()
                .filter(r -> r.from() != null && r.from().codice().equals(fromCodice) && r.toKey().toString().equals(to))
                .findFirst().orElseThrow(() -> new AssertionError("no relation " + fromCodice + " -> " + to));
    }

    @Test
    void laterRunAddsDatedAssessmentsAndNeverRemoves() throws IOException {
        // Day 1: the conversion law is not imported yet; day 2: it is.
        Path rawDay1 = Files.createDirectories(temp.resolve("raw1"));
        Path rawDay2 = Path.of("src/test/resources/akn-relations");
        try (var files = Files.list(rawDay2)) {
            for (Path f : files.filter(p -> !p.getFileName().toString().startsWith("legge")).toList()) {
                Files.copy(f, rawDay1.resolve(f.getFileName()));
            }
        }
        Path acts1 = temp.resolve("acts1.ttl");
        Path acts2 = temp.resolve("acts2.ttl");
        new AknImportRunner(new EliUriService("https://example.test")).run(rawDay1, acts1);
        new AknImportRunner(new EliUriService("https://example.test")).run(rawDay2, acts2);
        Path out = temp.resolve("relations.ttl");

        new RelationRunner().run(acts1, rawDay1, out, temp.resolve("c.tsv"), temp.resolve("r.tsv"), LocalDate.of(2020, 4, 1));
        String day1 = Files.readString(out);
        new RelationRunner().run(acts2, rawDay2, out, temp.resolve("c.tsv"), temp.resolve("r.tsv"), LocalDate.of(2020, 5, 1));
        String day2 = Files.readString(out);

        assertTrue(day1.contains("conversion/20G00034/2020-04-01"), day1);
        assertTrue(day1.contains("\"PENDING\""), "17 March + 15 days: still within 60 days");
        assertTrue(day2.contains("conversion/20G00034/2020-04-01"), "the earlier assessment is kept");
        assertTrue(day2.contains("conversion/20G00034/2020-05-01"), "a new one records the change");
        assertTrue(day2.contains("\"CONVERTED\""));
        assertTrue(day2.contains("\"PENDING\""), "nothing removed");
        for (String line : day1.split("\\R")) {
            if (line.contains("eli:amends") || line.contains("eli:repeals")) {
                assertTrue(day2.contains(line.trim()), "fact kept: " + line);
            }
        }
    }
}
