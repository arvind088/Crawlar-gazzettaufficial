package it.legislation.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import it.legislation.relations.AknRelationReader.Evidence;
import it.legislation.relations.AknRelationReader.TextualMod;
import it.legislation.relations.CorpusIndex.Act;
import it.legislation.relations.RelationDetector.Conversion;
import it.legislation.relations.RelationDetector.ConversionStatus;

/** Decree-law statuses, on a small made-up corpus. */
class RelationDetectorTest {

    private static Act act(String codice, String type, String date, String number, String gu, String title) {
        return new Act("https://example.test/" + codice, codice, LocalDate.parse(gu),
                new ActKey(type, LocalDate.parse(date), number), title, codice + ".xml", codice + ".xml");
    }

    private static Evidence evidence(List<TextualMod> mods, Set<ActKey> article1, Set<ActKey> textRepeals) {
        return new Evidence(mods, article1, Set.of(), textRepeals);
    }

    @Test
    void everyStatus() {
        Act converted = act("D1", "DECRETO-LEGGE", "2025-04-22", "54", "2025-04-22", "Disposizioni urgenti");
        Act law = act("L1", "LEGGE", "2025-06-13", "83", "2025-06-16",
                "Conversione in legge del decreto-legge 22 aprile  2025, n. 54, recante disposizioni urgenti.");
        Act merged = act("D2", "DECRETO-LEGGE", "2021-11-11", "157", "2021-11-11", "Misure urgenti");
        Act budget = act("L2", "LEGGE", "2021-12-30", "234", "2021-12-31", "Bilancio di previsione");
        Act lapsed = act("D3", "DECRETO-LEGGE", "2024-01-10", "1", "2024-01-10", "Misure");
        Act recent = act("D4", "DECRETO-LEGGE", "2026-09-28", "167", "2026-09-28", "Misure");
        Act awaiting = act("D5", "DECRETO-LEGGE", "2026-08-07", "144", "2026-08-07", "Misure");
        CorpusIndex corpus = new CorpusIndex(List.of(converted, law, merged, budget, lapsed, recent, awaiting),
                "https://example.test");

        Map<String, Evidence> original = new LinkedHashMap<>();
        // The decree's own note names the law (C2); the law's Article 1 names the decree (C3).
        original.put("D1", evidence(List.of(new TextualMod("amod_1", "insertion", "urn:nir:stato:legge:2000-01-01;1#2",
                ActKey.fromUrn("urn:nir:stato:legge:2000-01-01;1").orElseThrow(), false,
                ", convertito con modificazioni dalla L. 13 giugno 2025, n. 83 (in G.U. 16/06/2025, n. 137), "
                        + "ha disposto (con l'art. 1, comma 1) la modifica dell'art. 2.")), Set.of(), Set.of()));
        original.put("L1", evidence(List.of(), Set.of(converted.key()), Set.of()));
        // The budget law repeals the decree in its text.
        original.put("L2", evidence(List.of(), Set.of(), Set.of(merged.key())));

        RelationDetector.Result result = new RelationDetector(corpus).detect(original, Map.of(),
                LocalDate.of(2026, 10, 11), Map.of(awaiting.key(), "LEGGE 5 ottobre 2026, n. 174"));

        Map<String, Conversion> byDecree = new LinkedHashMap<>();
        result.conversions().forEach(c -> byDecree.put(c.decree().codice(), c));
        assertEquals(ConversionStatus.CONVERTED, byDecree.get("D1").status());
        assertEquals("L1", byDecree.get("D1").law().codice());
        assertEquals(ConversionStatus.REPEALED, byDecree.get("D2").status());
        assertEquals(List.of("L2"), byDecree.get("D2").repealedBy());
        assertEquals(ConversionStatus.NOT_CONVERTED, byDecree.get("D3").status());
        assertEquals(ConversionStatus.PENDING, byDecree.get("D4").status());
        assertEquals(ConversionStatus.AWAITING_LAW_TEXT, byDecree.get("D5").status());
    }
}
