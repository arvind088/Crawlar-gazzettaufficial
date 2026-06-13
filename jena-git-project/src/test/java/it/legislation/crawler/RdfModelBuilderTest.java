package it.legislation.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

class RdfModelBuilderTest {

    private static final String ACT_URI = "http://www.gazzettaufficiale.it/eli/id/2025/01/02/24A06897/sg";
    private static final String EXPRESSION_URI = ACT_URI + "/ita";
    private static final String FORMAT_URI = ACT_URI + "/ita/html";

    @Test
    void buildsLegalResourceExpressionAndFormatTriples() {
        CleanLegalActRecord record = CleanLegalActRecord.builder(ACT_URI)
                .title("  Riclassificazione   del medicinale  ")
                .publicationDate(LocalDate.of(2025, 1, 2))
                .documentDate(LocalDate.of(2024, 12, 9))
                .documentTypeUri("http://www.gazzettaufficiale.it/eli/tables/resource-type#DETERMINA")
                .localId("24A06897")
                .realizedByUri(EXPRESSION_URI)
                .embodiedByUri(FORMAT_URI)
                .versionUri("http://www.gazzettaufficiale.it/eli/tables/versions#ORIGINAL")
                .formatUri("http://www.iana.org/assignments/media-types/text/html")
                .languageUri("http://publications.europa.eu/resource/authority/language/ITA")
                .publisherUri("http://www.ipzs.it")
                .sourceUrl("https://www.gazzettaufficiale.it/atto/example")
                .authorityLabel("NOT FOUND")
                .build();

        Model model = new RdfModelBuilder().buildLegalAct(record);

        Resource legalAct = model.getResource(ACT_URI);
        Resource expression = model.getResource(EXPRESSION_URI);
        Resource manifestation = model.getResource(FORMAT_URI);

        assertTrue(model.contains(legalAct, RDF.type, model.getResource(RdfModelBuilder.ELI_NS + "LegalResource")));
        assertTrue(model.contains(expression, RDF.type, model.getResource(RdfModelBuilder.ELI_NS + "LegalExpression")));
        assertTrue(model.contains(manifestation, RDF.type, model.getResource(RdfModelBuilder.ELI_NS + "Format")));
        assertTrue(model.contains(legalAct, RDFS.label, "Riclassificazione del medicinale"));

        Property datePublication = model.createProperty(RdfModelBuilder.ELI_NS, "date_publication");
        Literal expectedDate = model.createTypedLiteral("2025-01-02", XSDDatatype.XSDdate);
        assertTrue(model.contains(legalAct, datePublication, expectedDate));

        Property isRealizedBy = model.createProperty(RdfModelBuilder.ELI_NS, "is_realized_by");
        assertTrue(model.contains(legalAct, isRealizedBy, expression));

        Property isEmbodiedBy = model.createProperty(RdfModelBuilder.ELI_NS, "is_embodied_by");
        assertTrue(model.contains(expression, isEmbodiedBy, manifestation));

        assertFalse(model.contains(null, null, "NOT FOUND"));
    }

    @Test
    void normalizesBlankAndMissingTextValues() {
        CleanLegalActRecord record = CleanLegalActRecord.builder(ACT_URI)
                .title("  Test   title  ")
                .localId("   ")
                .reference("NOT FOUND")
                .build();

        assertEquals("Test title", record.getTitle().orElseThrow());
        assertTrue(record.getLocalId().isEmpty());
        assertTrue(record.getReference().isEmpty());
    }
}
