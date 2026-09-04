package it.legislation.eli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class EliUriServiceTest {

    private static final String GAZZETTA_ACT =
            "http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg";
    private static final String GAZZETTA_EXPRESSION =
            "http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg/ita/vigente/2025-03-20/v52";

    private final EliUriService service = new EliUriService("https://legislazione.example.it");

    @Test
    void mintsActOnOurOwnDomain() {
        assertEquals(
                "https://legislazione.example.it/eli/id/2005/05/16/005G0104/sg",
                service.mint(GAZZETTA_ACT)
        );
    }

    @Test
    void mintsExpressionKeepingTheFullPath() {
        assertEquals(
                "https://legislazione.example.it/eli/id/2005/05/16/005G0104/sg/ita/vigente/2025-03-20/v52",
                service.mint(GAZZETTA_EXPRESSION)
        );
    }

    @Test
    void leavesNonEliUrisUntouched() {
        String vocabularyTerm = "http://www.gazzettaufficiale.it/eli/tables/resource-type#DECRETOLEGISLATIVO";
        String languageAuthority = "http://publications.europa.eu/resource/authority/language/ITA";

        assertEquals(vocabularyTerm, service.mint(vocabularyTerm));
        assertEquals(languageAuthority, service.mint(languageAuthority));
    }

    @Test
    void mintingIsIdempotent() {
        String minted = service.mint(GAZZETTA_ACT);

        assertEquals(minted, service.mint(minted));
        assertTrue(service.isOurs(minted));
    }

    @Test
    void extractsThePathFromEitherDomain() {
        String expected = "/eli/id/2005/05/16/005G0104/sg";

        assertEquals(expected, service.pathOf(GAZZETTA_ACT).orElseThrow());
        assertEquals(expected, service.pathOf(service.mint(GAZZETTA_ACT)).orElseThrow());
    }

    @Test
    void neverMintsAUrn() {
        String urn = "urn:nir:stato:decreto.legislativo:2005-03-07;82";

        assertEquals(urn, service.mint(urn));
        assertFalse(service.isEliIdentifier(urn));
        assertTrue(service.baseUri().startsWith("https://"));
    }

    @Test
    void offersBothOurUriAndTheSourceUriAsResolutionCandidates() {
        List<String> candidates = service.candidateUris("/eli/id/2005/05/16/005G0104/sg");

        assertEquals("https://legislazione.example.it/eli/id/2005/05/16/005G0104/sg", candidates.get(0));
        assertTrue(candidates.contains(GAZZETTA_ACT));
    }

    @Test
    void readsTheLocalIdAndPublicationDateOutOfThePath() {
        assertEquals("005G0104", service.localIdOf(GAZZETTA_ACT).orElseThrow());
        assertEquals("2005-05-16", service.publicationDateOf(GAZZETTA_ACT).orElseThrow());
    }

    @Test
    void addsHttpsToABaseGivenWithoutAScheme() {
        EliUriService bare = new EliUriService("legislazione.example.it/");

        assertEquals("https://legislazione.example.it", bare.baseUri());
    }
}
