package it.legislation.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Shapes found in the 2020-2026 corpus that the four sample files do not show. */
class AknRelationReaderTest {

    @TempDir
    Path temp;

    private static final String AKN = "http://docs.oasis-open.org/legaldocml/ns/akn/3.0";

    private AknRelationReader.Evidence read(String meta, String body) throws IOException {
        Path file = temp.resolve("act.xml");
        Files.writeString(file, "<akomaNtoso xmlns=\"" + AKN + "\"><act><meta>" + meta + "</meta><body>" + body
                + "</body></act></akomaNtoso>");
        return new AknRelationReader().read(file);
    }

    @Test
    void newerTextualModFormatWithAknDestinationAndRealType() throws IOException {
        AknRelationReader.Evidence e = read("""
                <analysis><activeModifications>
                  <textualMod eId="amod_84" type="repeal"><source href="#modNov_13"/>
                    <destination href="/akn/it/act/decreto-legge/stato/2013-06-21/69/ita@2022-01-27/!main/~art_22__para_2-ter"/></textualMod>
                  <textualMod eId="amod_85" type="repeal"><source href="#modNov_14"/>
                    <destination href="/akn/it/act/decreto-legge/stato/2026-04-03/42/ita@2026-05-22/!main"/></textualMod>
                </activeModifications></analysis>""", "<article><paragraph><content><p>x</p></content></paragraph></article>");

        List<AknRelationReader.TextualMod> mods = e.textualMods();
        assertEquals("repeal", mods.get(0).type());
        assertEquals(new ActKey("DECRETO-LEGGE", LocalDate.of(2013, 6, 21), "69"), mods.get(0).destination());
        assertFalse(mods.get(0).wholeAct(), "a paragraph of the act");
        assertTrue(mods.get(1).wholeAct(), "the act itself");
        assertEquals(null, mods.get(0).note());
    }

    @Test
    void conversionStatedInTextWithoutRefAndWithTypo() throws IOException {
        AknRelationReader.Evidence e = read("", """
                <article><paragraph><content><p>E' convertito in legge il decreto-legge 3l luglio 2020, n. 86,
                recante disposizioni urgenti.</p></content></paragraph></article>""");
        assertEquals(Set.of(new ActKey("DECRETO-LEGGE", LocalDate.of(2020, 7, 31), "86")), e.article1Converts(),
                "L. 98/2020 writes \"3l luglio\"");
    }

    @Test
    void wholeRepealStatedInTheText() throws IOException {
        AknRelationReader.Evidence e = read("", """
                <article><paragraph><content><p>Il decreto-legge 11 novembre 2021, n. 157, e' abrogato. Restano validi
                gli atti e i provvedimenti adottati.</p></content></paragraph>
                <paragraph><content><p>L'articolo 3 del decreto-legge 1 marzo 2021, n. 22, e' abrogato.</p></content></paragraph>
                <paragraph><content><p>I decreti-legge 2 marzo 2020, n. 9, 8 marzo 2020, n. 11, e 9 marzo 2020, n. 14,
                sono abrogati.</p></content></paragraph></article>""");
        assertEquals(Set.of(new ActKey("DECRETO-LEGGE", LocalDate.of(2021, 11, 11), "157"),
                new ActKey("DECRETO-LEGGE", LocalDate.of(2020, 3, 2), "9"),
                new ActKey("DECRETO-LEGGE", LocalDate.of(2020, 3, 8), "11"),
                new ActKey("DECRETO-LEGGE", LocalDate.of(2020, 3, 9), "14")), e.textRepeals(),
                "an article of a decree is not a repeal of the decree");
    }

    @Test
    void wholeRepealWithTheVerbFirstOrAfterAComma() throws IOException {
        // Real wordings: L. 38/2025, art. 4, and L. 199/2025, comma 409.
        AknRelationReader.Evidence e = read("", """
                <article><paragraph><content><p><mod eId="modNov_1">Dalla data di entrata in vigore della Convenzione, e'
                abrogata la legge 18 giugno 1973, n. 475 .</mod></p></content></paragraph>
                <paragraph><content><p>Dalla stessa data e' abrogata la legge 24 dicembre 2003, n. 363, ad eccezione
                dell'articolo 5. Salvo quanto stabilito nel comma 2, il decreto legislativo 14 dicembre 1992, n. 507, e'
                abrogato.</p></content></paragraph>
                <paragraph><content><p>A decorrere dalla data di entrata in vigore della presente legge, la legge 21 aprile
                1977, n. 164 , e la legge 22 dicembre 1980, n. 927 , sono abrogate.</p></content></paragraph>
                <paragraph><content><p>All'articolo 5 della legge 3 agosto 2007, n. 124, il comma 2 e' abrogato.</p></content></paragraph></article>""");
        assertEquals(Set.of(new ActKey("LEGGE", LocalDate.of(1977, 4, 21), "164"),
                new ActKey("LEGGE", LocalDate.of(1980, 12, 22), "927")), e.textRepeals(),
                "a paragraph of a law, or a repeal with exceptions, is not a repeal of the whole law");
        assertEquals(Set.of(new ActKey("LEGGE", LocalDate.of(1973, 6, 18), "475")), e.modTextRepeals(),
                "a sentence inside <mod> is kept apart: it only confirms the change list");
    }

    @Test
    void repealsQuotedInNotesOrGuillemetsAreNotThisActs() throws IOException {
        AknRelationReader.Evidence e = read("", """
                <article><paragraph><content><p>Testo.<authorialNote><p>I decreti-legge 2 marzo 2020, n. 9, e 9 marzo
                2020, n. 14, sono abrogati.</p></authorialNote></p></content></paragraph>
                <paragraph><content><p>La legge 24 aprile 2020, n. 27, cosi' recita: «Art. 1. - 2. I decreti-legge 2 marzo
                2020, n. 9, 8 marzo 2020, n. 11, sono abrogati.»</p></content></paragraph></article>""");
        assertEquals(Set.of(), e.textRepeals(), "D.Lgs. 44/2020 quotes L. 27/2020 in its notes");
    }

    @Test
    void historyEventsWithDatesFromARealLifecycle() throws IOException {
        // D.Lgs. 196/2003 (privacy code), version 58: 56 events, the first is its own publication.
        AknRelationReader.Evidence e = new AknRelationReader()
                .read(Path.of("src/test/resources/akn-relations/dlgs-2003-196-v58-lifecycle.xml"));
        List<AknRelationReader.LifecycleEvent> events = e.events();
        assertEquals(55, events.size(), "the act's own publication is not a change");
        assertEquals(LocalDate.of(2003, 12, 29), events.get(0).date());
        assertEquals(new ActKey("DECRETO-LEGGE", LocalDate.of(2003, 12, 24), "354"), events.get(0).act().orElseThrow());
        assertEquals(new ActKey("DECRETO-LEGGE", LocalDate.of(2026, 2, 19), "19"), events.get(54).act().orElseThrow());
        assertEquals(8L, events.stream().filter(ev -> ev.act().isEmpty()).count(),
                "Normattiva leaves some references empty; they are kept as events without an act");
        for (int i = 1; i < events.size(); i++) {
            assertFalse(events.get(i).date().isBefore(events.get(i - 1).date()), "oldest first");
        }
    }
}
