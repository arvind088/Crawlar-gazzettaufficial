package it.legislation.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ActKeyTest {

    private static final ActKey DL_18 = new ActKey("DECRETO-LEGGE", LocalDate.of(2020, 3, 17), "18");

    @Test
    void readsUrnsWithArticleAndAllTypeSpellings() {
        assertEquals(DL_18, ActKey.fromUrn("urn:nir:stato:decreto.legge:2020-03-17;18#2 bis").orElseThrow());
        assertEquals(DL_18, ActKey.fromAknHref("/akn/it/act/decretoLegge/stato/2020-03-17/18/!main").orElseThrow());
        assertEquals(DL_18, ActKey.fromAknHref("/akn/it/act/DECRETO-LEGGE/stato/2020-03-17/18/!main").orElseThrow());
        assertEquals("DECRETO_LEGISLATIVO",
                ActKey.fromUrn("urn:nir:stato:decreto.legislativo:2003-06-30;196").orElseThrow().typeCode());
        assertEquals("REGIO_DECRETO",
                ActKey.fromUrn("urn:nir:stato:regio.decreto:1934-07-27;1265#Testo unico-art. 100").orElseThrow().typeCode());
    }

    @Test
    void rejectsWhatIsNotACitation() {
        assertTrue(ActKey.fromUrn("#").isEmpty());
        assertTrue(ActKey.fromAknHref("").isEmpty());
        assertTrue(ActKey.fromUrn(null).isEmpty());
    }
}
