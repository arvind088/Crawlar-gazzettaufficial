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
        Act firstOfMonth = act("D6", "DECRETO-LEGGE", "2022-03-01", "17", "2022-03-01", "Misure urgenti");
        Act lawFirst = act("L3", "LEGGE", "2022-04-27", "34", "2022-04-28",
                "Conversione in legge, con modificazioni, del decreto-legge 1° marzo 2022, n. 17, recante misure.");
        Act typo = act("D7", "DECRETO-LEGGE", "2020-07-31", "86", "2020-07-31", "Disposizioni urgenti");
        Act lawTypo = act("L4", "LEGGE", "2020-08-07", "98", "2020-08-08",
                "Conversione in legge del decreto-legge 3l luglio 2020, n. 86, recante disposizioni urgenti.");
        Act merged = act("D2", "DECRETO-LEGGE", "2021-11-11", "157", "2021-11-11", "Misure urgenti");
        Act budget = act("L2", "LEGGE", "2021-12-30", "234", "2021-12-31", "Bilancio di previsione");
        Act recent = act("D4", "DECRETO-LEGGE", "2026-09-28", "167", "2026-09-28", "Misure");
        Act awaiting = act("D5", "DECRETO-LEGGE", "2026-08-07", "144", "2026-08-07", "Misure");
        CorpusIndex corpus = new CorpusIndex(List.of(converted, law, firstOfMonth, lawFirst, typo, lawTypo, merged,
                budget, recent, awaiting), "https://example.test");

        RelationDetector.Result result = new RelationDetector(corpus).detect(Map.of(), Map.of(),
                LocalDate.of(2026, 10, 11), Map.of(awaiting.key(), "LEGGE 5 ottobre 2026, n. 174"));

        Map<String, Conversion> byDecree = new LinkedHashMap<>();
        result.conversions().forEach(c -> byDecree.put(c.decree().codice(), c));
        assertEquals(ConversionStatus.CONVERTED, byDecree.get("D1").status(), "the law's title names it");
        assertEquals("L1", byDecree.get("D1").law().codice());
        assertEquals(55L, byDecree.get("D1").days());
        assertEquals("L3", byDecree.get("D6").law().codice(), "\"1° marzo\" in the title");
        assertEquals("L4", byDecree.get("D7").law().codice(), "\"3l luglio\": a typing error in the real title");
        assertEquals(ConversionStatus.NOT_CONVERTED, byDecree.get("D2").status(), "no law converts it");
        assertEquals(ConversionStatus.PENDING, byDecree.get("D4").status(), "13 days old");
        assertEquals(ConversionStatus.CONVERTED, byDecree.get("D5").status(), "a listed law whose text is not imported");
        assertEquals(null, byDecree.get("D5").law());
        assertEquals("LEGGE 5 ottobre 2026, n. 174", byDecree.get("D5").listedLaw());
    }

    @Test
    void relationsSayWhereTheyWereFound() {
        Act decree = act("D1", "DECRETO-LEGGE", "2020-03-17", "18", "2020-03-17", "Misure");
        Act law = act("L1", "LEGGE", "2020-04-24", "27", "2020-04-29", "Conversione in legge");
        Act later = act("D2", "DECRETO-LEGGE", "2020-05-19", "34", "2020-05-19", "Misure");
        CorpusIndex corpus = new CorpusIndex(List.of(decree, law, later), "https://example.test");
        Map<String, Evidence> original = Map.of("L1", evidence(List.of(new TextualMod("amod_1", "insertion",
                "urn:nir:stato:decreto.legge:2020-03-17;18#2", decree.key(), false,
                "ha disposto (con l'art. 1, comma 1) la modifica dell'art. 2.")), Set.of(), Set.of()));
        Map<String, Evidence> latest = Map.of("D1",
                new Evidence(List.of(), Set.of(), Set.of(law.key(), later.key()), Set.of()));

        RelationDetector.Result result = new RelationDetector(corpus).detect(original, latest, LocalDate.of(2026, 10, 11));

        Map<String, String> sources = new LinkedHashMap<>();
        result.relations().forEach(r -> sources.put(r.from().codice() + " " + r.property().eliName, r.sources()));
        assertEquals(Map.of("L1 amends", "change list + history", "D2 changes", "history"), sources);
    }

    @Test
    void historyNamingAnOlderLawIsNotARelation() {
        // Real case: D.L. 179/2022's history names L. 21 gennaio 2022, n. 6 (right number, wrong year).
        Act decree = act("22G00192", "DECRETO-LEGGE", "2022-11-23", "179", "2022-11-23", "Misure urgenti");
        Act oldLaw = act("22G00009", "LEGGE", "2022-01-21", "6", "2022-02-07", "Legge");
        Act olderDecree = act("22G00002", "DECRETO-LEGGE", "2022-01-07", "1", "2022-01-07", "Misure");
        Act laterLaw = act("23G00010", "LEGGE", "2023-01-17", "6", "2023-01-18", "Legge");
        CorpusIndex corpus = new CorpusIndex(List.of(decree, oldLaw, olderDecree, laterLaw), "https://example.test");

        Map<String, Evidence> latest = Map.of("22G00192",
                new Evidence(List.of(), Set.of(), Set.of(oldLaw.key(), olderDecree.key(), laterLaw.key()), Set.of()));
        RelationDetector.Result result = new RelationDetector(corpus).detect(Map.of(), latest, LocalDate.of(2026, 10, 21));

        List<String> from = result.relations().stream().map(r -> r.fromKey().number() + "/" + r.fromKey().date().getYear())
                .sorted().toList();
        // The older law is dropped; an older decree-law (changes through its conversion) and a later law stay.
        assertEquals(List.of("1/2022", "6/2023"), from);
        assertEquals(true, RelationDetector.impossibleInHistory(oldLaw.key(), decree.key()));
        assertEquals(false, RelationDetector.impossibleInHistory(olderDecree.key(), decree.key()));
    }
}
