package it.legislation.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import it.legislation.relations.ModificationNote.Kind;

/** Notes copied from Normattiva files (L. 27/2020, D.L. 18/2020, D.Lgs. 101/2018, D.Lgs. 196/2003). */
class ModificationNoteTest {

    @Test
    void directChanges() {
        ModificationNote amend = ModificationNote.parse(
                ", di conversione, ha disposto (con l'art. 1, comma 1) la modifica dell'art. 1, commi 1, 2 e 3.");
        assertEquals(Kind.AMENDMENT, amend.kind());
        assertFalse(amend.indirect());

        assertEquals(Kind.INSERTION, ModificationNote.parse(
                "ha disposto (con l'art. 2, comma 1, lettera e)) l'introduzione dell'art. 2-bis.").kind());
        assertEquals(Kind.DELETION, ModificationNote.parse(
                ", di conversione, ha disposto (con l'art. 1, comma 1) la soppressione, nel Titolo V, della partizione").kind());
        assertEquals(Kind.REPLACEMENT, ModificationNote.parse(
                "ha disposto (con l'art. 2, comma 1, lettera a)) che la rubrica del titolo I e' sostituita dalla seguente").kind());
    }

    @Test
    void repealOfTheWholeActAndOfAPart() {
        ModificationNote whole = ModificationNote.parse(
                "ha disposto (con l'art. 1, comma 2) l'abrogazione dell'intero provvedimento e la modifica dell'art. 1.");
        assertEquals(Kind.REPEAL, whole.kind());
        assertTrue(whole.wholeAct());

        ModificationNote part = ModificationNote.parse("ha disposto (con l'art. 173, comma 1 lettera d)) l'abrogazione dell'art. 12.");
        assertEquals(Kind.REPEAL, part.kind());
        assertFalse(part.wholeAct());

        assertTrue(ModificationNote.parse("ha disposto (con l'art. 1, comma 2) l'abrogazione e la modifica dell'art. 1.")
                .wholeAct(), "\"l'abrogazione\" with no object: D.L. 79/2023 repealed by L. 95/2023");

        assertEquals(Kind.REPEAL, ModificationNote.parse(
                "ha disposto (con l'art. 183 comma 1 lettera i) l'abrogazionme dell'art. 9 comma 4.").kind(),
                "unbalanced parenthesis and the misspelling found in D.Lgs. 196/2003");
    }

    @Test
    void notesThatNameAThirdAct() {
        ModificationNote conversion = ModificationNote.parse(
                "ha disposto (con l'art. 1, comma 1) la conversione, con modificazioni, del D.L. 17 marzo 2020, n. 18 (in G.U. 17/03/2020, n. 70).");
        assertEquals(Kind.CONVERSION, conversion.kind());
        assertEquals(new ActKey("DECRETO-LEGGE", LocalDate.of(2020, 3, 17), "18"), conversion.namedAct());

        ModificationNote repealVia = ModificationNote.parse(
                "ha disposto (con l'art. 1, comma 2) l'abrogazione del D.L. 2 marzo 2020, n. 9 (in G.U. 02/03/2020, n. 53).");
        assertEquals(Kind.REPEAL, repealVia.kind());
        assertTrue(repealVia.indirect());
        assertEquals(LocalDate.of(2020, 3, 2), repealVia.namedAct().date());
    }

    @Test
    void unclassifiedNote() {
        ModificationNote other = ModificationNote.parse(
                "ha disposto (con l'art. 1, comma 2) che \"Restano validi gli atti ed i provvedimenti adottati\"");
        assertEquals(Kind.OTHER, other.kind());
        assertFalse(other.indirect());
    }
}
