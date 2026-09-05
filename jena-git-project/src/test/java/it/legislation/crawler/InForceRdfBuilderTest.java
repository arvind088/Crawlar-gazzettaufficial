package it.legislation.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

import it.legislation.crawler.InForceRdfBuilder.ExpressionStatus;

/**
 * FR-4.5, US-A1 and US-A3 all need in-force status to be queryable rather than
 * implied by a version label.
 */
class InForceRdfBuilderTest {

    private static final String BASE =
            "http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg";
    private static final String ORIGINAL = BASE + "/ita/original";
    private static final String CURRENT = BASE + "/ita/vigente/2025-03-20/v52";
    private static final String VERSIONS =
            "http://www.gazzettaufficiale.it/eli/tables/versions#";

    @Test
    void aCurrentTextIsInForceAndCarriesItsStartDate() {
        ExpressionStatus status = InForceRdfBuilder.fromVersionToken(
                CURRENT, VERSIONS + "VIGENZA_20250320_V52", true);

        assertTrue(status.isInForce());
        assertEquals(LocalDate.of(2025, 3, 20), status.forceStart().orElseThrow());
    }

    @Test
    void anOriginalTextIsSupersededOnlyWhenACurrentTextExists() {
        ExpressionStatus superseded = InForceRdfBuilder.fromVersionToken(
                ORIGINAL, VERSIONS + "ORIGINALE_V0", true);
        ExpressionStatus standsAlone = InForceRdfBuilder.fromVersionToken(
                ORIGINAL, VERSIONS + "ORIGINALE_V0", false);

        assertFalse(superseded.isInForce());
        assertTrue(standsAlone.isInForce(),
                "an act with only its first text is still the law in force");
    }

    /**
     * The Gazzetta crawler writes ORIGINAL where Normattiva writes ORIGINALE.
     * Missing this spelling would have left all 86 crawled acts unclassified.
     */
    @Test
    void theEnglishSpellingUsedByTheGazzettaCrawlerIsRecognised() {
        ExpressionStatus status = InForceRdfBuilder.fromVersionToken(
                ORIGINAL, VERSIONS + "ORIGINAL", false);

        assertTrue(status.isInForce());
    }

    @Test
    void forceDatesDecideStatusWhenTheyArePresent() {
        LocalDate today = LocalDate.of(2026, 9, 4);

        assertTrue(InForceRdfBuilder
                .fromForceDates(CURRENT, "2025-03-20", "", today).isInForce());
        assertFalse(InForceRdfBuilder
                .fromForceDates(CURRENT, "2020-01-01", "2024-12-31", today).isInForce());
        assertFalse(InForceRdfBuilder
                .fromForceDates(CURRENT, "2027-01-01", "", today).isInForce(),
                "an act that has not yet entered into force is not in force");
    }

    @Test
    void normattivaSlashedDatesAreAccepted() {
        ExpressionStatus status = InForceRdfBuilder.fromForceDates(
                CURRENT, "20/03/2025", "", LocalDate.of(2026, 9, 4));

        assertEquals(LocalDate.of(2025, 3, 20), status.forceStart().orElseThrow());
    }

    @Test
    void onlyStandardEliPredicatesAndAuthorityValuesAreWritten() {
        Model model = new InForceRdfBuilder().build(List.of(
                InForceRdfBuilder.fromVersionToken(CURRENT, VERSIONS + "VIGENZA_20250320_V52", true),
                InForceRdfBuilder.fromVersionToken(ORIGINAL, VERSIONS + "ORIGINALE_V0", true)
        ));

        assertTrue(model.contains(
                model.getResource(CURRENT),
                model.createProperty(InForceRdfBuilder.ELI_NS, "in_force"),
                model.getResource(InForceRdfBuilder.IN_FORCE)));
        assertTrue(model.contains(
                model.getResource(ORIGINAL),
                model.createProperty(InForceRdfBuilder.ELI_NS, "in_force"),
                model.getResource(InForceRdfBuilder.NOT_IN_FORCE)));

        // Every predicate must come from the ELI namespace: this class consumes
        // the standard, it does not extend it.
        model.listStatements().forEachRemaining(statement ->
                assertTrue(statement.getPredicate().getURI().startsWith(InForceRdfBuilder.ELI_NS),
                        "unexpected predicate " + statement.getPredicate()));
    }

    @Test
    void anEmptyBuildProducesAnEmptyModel() {
        Model model = new InForceRdfBuilder().build(List.of());

        assertTrue(model.isEmpty());
        assertEquals(ModelFactory.createDefaultModel().size(), model.size());
    }
}
