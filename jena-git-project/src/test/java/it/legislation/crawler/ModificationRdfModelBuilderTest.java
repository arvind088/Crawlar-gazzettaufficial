package it.legislation.crawler;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.Test;

class ModificationRdfModelBuilderTest {

    @Test
    void buildsDirectActToActRelations() {
        CleanModificationRecord record = CleanModificationRecord.of(
                "http://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg",
                "http://www.gazzettaufficiale.it/eli/id/2025/01/30/25G00013/sg",
                "ha disposto l'abrogazione dell'intero provvedimento e la modifica dell'art. 1."
        );

        Model model = new ModificationRdfModelBuilder().build(java.util.List.of(record));
        Resource subject = model.getResource(record.getSubjectUri());
        Resource object = model.getResource(record.getObjectUri());
        Property modifies = model.createProperty(RdfModelBuilder.PROJECT_NS, "modifies");
        Property modifiedBy = model.createProperty(RdfModelBuilder.PROJECT_NS, "modifiedBy");

        assertTrue(model.contains(subject, modifies, object));
        assertTrue(model.contains(object, modifiedBy, subject));
    }

    @Test
    void conversionRowsAlsoGenerateEliCommencementRelations() {
        CleanModificationRecord record = CleanModificationRecord.of(
                "http://www.gazzettaufficiale.it/eli/id/2025/04/29/25G00068/sg",
                "http://www.gazzettaufficiale.it/eli/id/2025/02/28/25G00030/sg",
                "di conversione ha disposto la modifica dell'art. 1."
        );

        Model model = new ModificationRdfModelBuilder().build(java.util.List.of(record));
        Resource subject = model.getResource(record.getSubjectUri());
        Resource object = model.getResource(record.getObjectUri());
        Property commences = model.createProperty(RdfModelBuilder.ELI_NS, "commences");
        Property commencedBy = model.createProperty(RdfModelBuilder.ELI_NS, "commenced_by");

        assertTrue(model.contains(subject, commences, object));
        assertTrue(model.contains(object, commencedBy, subject));
    }
}
