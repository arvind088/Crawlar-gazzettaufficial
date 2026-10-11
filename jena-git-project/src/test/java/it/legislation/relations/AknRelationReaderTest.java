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
    void repealsQuotedInNotesOrGuillemetsAreNotThisActs() throws IOException {
        AknRelationReader.Evidence e = read("", """
                <article><paragraph><content><p>Testo.<authorialNote><p>I decreti-legge 2 marzo 2020, n. 9, e 9 marzo
                2020, n. 14, sono abrogati.</p></authorialNote></p></content></paragraph>
                <paragraph><content><p>La legge 24 aprile 2020, n. 27, cosi' recita: «Art. 1. - 2. I decreti-legge 2 marzo
                2020, n. 9, 8 marzo 2020, n. 11, sono abrogati.»</p></content></paragraph></article>""");
        assertEquals(Set.of(), e.textRepeals(), "D.Lgs. 44/2020 quotes L. 27/2020 in its notes");
    }
}
