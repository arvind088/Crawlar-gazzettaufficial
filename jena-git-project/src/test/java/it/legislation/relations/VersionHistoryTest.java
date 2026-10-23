package it.legislation.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import it.legislation.relations.AknRelationReader.Evidence;
import it.legislation.relations.AknRelationReader.LifecycleEvent;
import it.legislation.relations.CorpusIndex.Act;
import it.legislation.relations.CorpusIndex.Version;
import it.legislation.relations.RelationDetector.Property;
import it.legislation.relations.RelationDetector.Relation;

/** Linking versions to the acts that produced them, on the real history of D.L. 172/2020. */
class VersionHistoryTest {

    private static Act act(String codice, String type, String date, String number) {
        return new Act("https://example.test/" + codice, codice, LocalDate.parse(date),
                new ActKey(type, LocalDate.parse(date), number), "", codice + ".xml", codice + ".xml");
    }

    private static LifecycleEvent event(String date, ActKey act) {
        return new LifecycleEvent(LocalDate.parse(date), Optional.ofNullable(act), act == null ? "" : act.toString());
    }

    @Test
    void eachVersionIsLinkedToTheActsThatProducedIt() {
        Act decree = act("20G00196", "DECRETO-LEGGE", "2020-12-18", "172");
        Act law = act("21G00008", "LEGGE", "2021-01-29", "6");          // its conversion law
        Act later = act("21G00056", "DECRETO-LEGGE", "2021-04-01", "44");
        Act oldLaw = act("19G00001", "LEGGE", "2019-01-10", "1");      // cannot change a later act (R7)
        CorpusIndex corpus = new CorpusIndex(List.of(decree, law, later, oldLaw), "https://example.test");
        String v = "https://example.test/20G00196/ita/";
        corpus.putVersions("20G00196", List.of(new Version(v + "original", LocalDate.of(2020, 12, 19), true),
                new Version(v + "vigente/2021-01-31", LocalDate.of(2021, 1, 31), false),
                new Version(v + "vigente/2021-04-01", LocalDate.of(2021, 4, 1), false)));

        Evidence history = new Evidence(List.of(), Set.of(), Set.of(), Set.of(), List.of(
                event("2021-01-30", law.key()),       // published 30 Jan, in force 31 Jan
                event("2021-04-01", later.key()),
                event("2021-02-15", null),            // empty reference in Normattiva's history
                event("2021-03-01", oldLaw.key()),
                event("2021-06-01", later.key())));   // after the last version
        List<Relation> relations = List.of(new Relation(law, law.key(), Property.AMENDS, decree, decree.key(), "A", 9,
                true, Map.of(), List.of(), "x.xml"));
        Map<String, LocalDate> inForce = Map.of(law.workUri(), LocalDate.of(2021, 1, 31));

        VersionHistory.Result r = VersionHistory.link(corpus, Map.of("20G00196", history), relations, inForce);

        assertEquals(2, r.versions(), "the original text is not a change");
        assertEquals(2, r.versionsWithCause());
        assertEquals(2, r.links().size());
        VersionHistory.Link first = r.links().get(0);
        assertEquals(v + "vigente/2021-01-31", first.version().uri());
        assertEquals(law, first.modifier());
        assertEquals(1L, first.daysAfterEvent());
        assertEquals(Boolean.TRUE, first.onEntryIntoForce(), "starts on the law's entry into force");
        assertTrue(first.confirmedByChangeList(), "the law's change list names the decree");
        VersionHistory.Link second = r.links().get(1);
        assertEquals(v + "vigente/2021-04-01", second.version().uri());
        assertEquals(0L, second.daysAfterEvent());
        assertEquals(null, second.onEntryIntoForce(), "entry into force not known");
        assertEquals(false, second.confirmedByChangeList());

        assertEquals(5, r.events());
        assertEquals(1, r.eventsWithoutAct());
        assertEquals(1, r.eventsNotPlausible());
        assertEquals(1, r.eventsAfterLastVersion());
    }
}
