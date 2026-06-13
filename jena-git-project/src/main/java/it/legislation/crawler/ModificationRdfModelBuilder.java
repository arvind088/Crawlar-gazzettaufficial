package it.legislation.crawler;

import java.util.Collection;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

public class ModificationRdfModelBuilder {

    private final Resource legalResourceType;
    private final Property modifies;
    private final Property modifiedBy;
    private final Property commences;
    private final Property commencedBy;

    public ModificationRdfModelBuilder() {
        Model vocabularyModel = ModelFactory.createDefaultModel();
        this.legalResourceType = vocabularyModel.createResource(RdfModelBuilder.ELI_NS + "LegalResource");
        this.modifies = vocabularyModel.createProperty(RdfModelBuilder.PROJECT_NS, "modifies");
        this.modifiedBy = vocabularyModel.createProperty(RdfModelBuilder.PROJECT_NS, "modifiedBy");
        this.commences = vocabularyModel.createProperty(RdfModelBuilder.ELI_NS, "commences");
        this.commencedBy = vocabularyModel.createProperty(RdfModelBuilder.ELI_NS, "commenced_by");
    }

    public Model build(Collection<CleanModificationRecord> records) {
        Model model = createModel();
        for (CleanModificationRecord record : records) {
            addModification(model, record);
        }
        return model;
    }

    public void addModification(Model model, CleanModificationRecord record) {
        Resource subject = model.createResource(record.getSubjectUri());
        Resource object = model.createResource(record.getObjectUri());

        subject.addProperty(RDF.type, legalResourceType);
        object.addProperty(RDF.type, legalResourceType);

        subject.addProperty(modifies, object);
        object.addProperty(modifiedBy, subject);

        if (record.getModificationType() == ModificationType.CONVERSION) {
            subject.addProperty(commences, object);
            object.addProperty(commencedBy, subject);
        }
    }

    private Model createModel() {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("eli", RdfModelBuilder.ELI_NS);
        model.setNsPrefix("ilg", RdfModelBuilder.PROJECT_NS);
        model.setNsPrefix("rdf", RDF.getURI());
        model.setNsPrefix("rdfs", RDFS.getURI());
        model.setNsPrefix("xsd", XSDDatatype.XSD + "#");
        return model;
    }
}
