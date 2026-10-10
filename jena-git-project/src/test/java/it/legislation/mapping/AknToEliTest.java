package it.legislation.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import it.legislation.eli.EliUriService;
import it.legislation.model.AknExpression;

class AknToEliTest {

    private static final String BASE = "https://example.test";
    private static final String ELI = AknToEli.ELI;

    @Test
    void mapsWorkExpressionAndFormatOnOurDomain() throws IOException {
        AknExpression l27 = new AknActReader().read(AknActReaderTest.L27, "l27.xml").expression();

        Model model = new AknToEli(new EliUriService(BASE)).map(List.of(l27));

        Resource work = model.getResource(BASE + "/eli/id/2020/04/29/20G00045/sg");
        Resource expression = model.getResource(BASE + "/eli/id/2020/04/29/20G00045/sg/ita/original");
        Resource format = model.getResource(BASE + "/eli/id/2020/04/29/20G00045/sg/ita/original/akn");
        assertTrue(model.contains(work, RDF.type, model.getResource(ELI + "LegalResource")));
        assertTrue(model.contains(expression, RDF.type, model.getResource(ELI + "LegalExpression")));
        assertTrue(model.contains(format, RDF.type, model.getResource(ELI + "Format")));
        assertTrue(model.contains(work, p(model, "is_realized_by"), expression));
        assertTrue(model.contains(expression, p(model, "realizes"), work));
        assertTrue(model.contains(expression, p(model, "is_embodied_by"), format));
        assertTrue(model.contains(format, p(model, "embodies"), expression));
        assertTrue(model.contains(work, p(model, "type_document"),
                model.getResource("http://www.gazzettaufficiale.it/eli/tables/resource-type#LEGGE")));
        assertTrue(model.contains(work, OWL.sameAs,
                model.getResource("http://www.gazzettaufficiale.it/eli/id/2020/04/29/20G00045/sg")));
        assertEquals("2020-04-29", work.getProperty(p(model, "date_publication")).getString());
        assertFalse(work.getProperty(p(model, "title")).getString().endsWith("(20G00045)"));
    }

    @Test
    void keepsTheUrnOnlyAsALiteral() throws IOException {
        AknExpression l27 = new AknActReader().read(AknActReaderTest.L27, "l27.xml").expression();

        Model model = new AknToEli(new EliUriService(BASE)).map(List.of(l27));

        model.listSubjects().forEach(subject -> assertTrue(subject.getURI().startsWith("https://"), subject.toString()));
        model.listObjects().forEach(object -> assertFalse(object.isURIResource() && object.asResource().getURI().startsWith("urn:"),
                object.toString()));
        assertTrue(model.contains(null, DCTerms.identifier, "urn:nir:stato:legge:2020-04-24;27"));
    }

    @Test
    void laterVersionsGetTheirOwnExpression() throws IOException {
        AknExpression v58 = new AknActReader().read(AknActReaderTest.DLGS196_V58, "v58.xml").expression();

        Model model = new AknToEli(new EliUriService(BASE)).map(List.of(v58));

        Resource expression = model.getResource(BASE + "/eli/id/2003/07/29/003G0218/sg/ita/vigente/2026-02-20");
        assertTrue(model.contains(expression, RDF.type, model.getResource(ELI + "LegalExpression")));
        assertEquals("2026-02-20", expression.getProperty(p(model, "version_date")).getString());
        assertTrue(model.contains(model.getResource(BASE + "/eli/id/2003/07/29/003G0218/sg"), p(model, "type_document"),
                model.getResource("http://www.gazzettaufficiale.it/eli/tables/resource-type#DECRETO_LEGISLATIVO")));
    }

    private static Property p(Model model, String name) {
        return model.createProperty(ELI, name);
    }
}
