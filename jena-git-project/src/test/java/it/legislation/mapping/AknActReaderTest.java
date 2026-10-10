package it.legislation.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import it.legislation.model.AknExpression;

class AknActReaderTest {

    static final Path L27 = Path.of("src/test/resources/akn/legge-2020-27-original.xml");
    static final Path DLGS196_V58 = Path.of("src/test/resources/akn/dlgs-2003-196-vigente-2026-02-20.xml");

    @TempDir
    Path temp;

    private final AknActReader reader = new AknActReader();

    @Test
    void readsIdentityOfAnOriginalText() throws IOException {
        AknActReader.Result result = reader.read(L27, "l27.xml");

        assertTrue(result.ok(), () -> String.valueOf(result.rejected()));
        AknExpression act = result.expression();
        assertEquals("20G00045", act.codiceRedazionale());
        assertEquals(LocalDate.of(2020, 4, 29), act.gazzettaDate());
        assertEquals("110", act.gazzettaNumber());
        assertEquals("LEGGE", act.actType());
        assertEquals(LocalDate.of(2020, 4, 24), act.documentDate());
        assertEquals("27", act.number());
        assertEquals("urn:nir:stato:legge:2020-04-24;27", act.urnNir());
        assertTrue(act.original());
        assertEquals(LocalDate.of(2020, 4, 30), act.versionDate());
        assertTrue(act.title().startsWith("Conversione in legge, con modificazioni"));
        assertEquals(64, act.sha256().length());
    }

    @Test
    void readsALaterVersionWithItsDate() throws IOException {
        AknActReader.Result result = reader.read(DLGS196_V58, "v58.xml");

        assertTrue(result.ok(), () -> String.valueOf(result.rejected()));
        AknExpression act = result.expression();
        assertEquals("003G0218", act.codiceRedazionale());
        assertEquals("DECRETO LEGISLATIVO", act.actType());
        assertFalse(act.original());
        assertEquals(LocalDate.of(2026, 2, 20), act.versionDate());
    }

    @Test
    void rejectsAFileWithADoctype() throws IOException {
        Path file = temp.resolve("evil.xml");
        Files.writeString(file, "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<akomaNtoso xmlns=\"http://docs.oasis-open.org/legaldocml/ns/akn/3.0\">&e;</akomaNtoso>");

        AknActReader.Result result = reader.read(file, "evil.xml");

        assertFalse(result.ok());
        assertTrue(result.rejected().problems().get(0).startsWith("XML not readable"));
    }

    @Test
    void rejectsAFileWithoutGazzettaIdentity() throws IOException {
        Path file = temp.resolve("no-alias.xml");
        Files.writeString(file, Files.readString(L27).replace("eli/id/2020/04/29/20G00045/ORIGINAL", "missing"));

        AknActReader.Result result = reader.read(file, "no-alias.xml");

        assertFalse(result.ok());
        assertTrue(result.rejected().problems().stream().anyMatch(p -> p.contains("codice redazionale")),
                () -> result.rejected().problems().toString());
    }
}
