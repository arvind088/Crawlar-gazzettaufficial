package it.legislation.crawler;

import java.time.LocalDate;
import java.util.Collection;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

public class RdfModelBuilder {

    public static final String ELI_NS = "http://data.europa.eu/eli/ontology#";
    public static final String DCTERMS_NS = "http://purl.org/dc/terms/";
    public static final String SCHEMA_NS = "http://schema.org/";
    public static final String GU_NS = "http://www.gazzettaufficiale.it/eli/";
    public static final String PROJECT_NS = "http://example.org/italian-legislation/ontology#";

    private final Resource legalResourceType;
    private final Resource legalExpressionType;
    private final Resource formatType;
    private final Property datePublication;
    private final Property dateDocument;
    private final Property typeDocument;
    private final Property idLocal;
    private final Property isRealizedBy;
    private final Property isEmbodiedBy;
    private final Property version;
    private final Property format;
    private final Property language;
    private final Property publisher;
    private final Property title;
    private final Property source;
    private final Property authorityLabel;
    private final Property reference;
    private final Property referenceGu;
    private final Property contentUrl;

    public RdfModelBuilder() {
        Model vocabularyModel = ModelFactory.createDefaultModel();
        this.legalResourceType = vocabularyModel.createResource(ELI_NS + "LegalResource");
        this.legalExpressionType = vocabularyModel.createResource(ELI_NS + "LegalExpression");
        this.formatType = vocabularyModel.createResource(ELI_NS + "Format");
        this.datePublication = vocabularyModel.createProperty(ELI_NS, "date_publication");
        this.dateDocument = vocabularyModel.createProperty(ELI_NS, "date_document");
        this.typeDocument = vocabularyModel.createProperty(ELI_NS, "type_document");
        this.idLocal = vocabularyModel.createProperty(ELI_NS, "id_local");
        this.isRealizedBy = vocabularyModel.createProperty(ELI_NS, "is_realized_by");
        this.isEmbodiedBy = vocabularyModel.createProperty(ELI_NS, "is_embodied_by");
        this.version = vocabularyModel.createProperty(ELI_NS, "version");
        this.format = vocabularyModel.createProperty(ELI_NS, "format");
        this.language = vocabularyModel.createProperty(ELI_NS, "language");
        this.publisher = vocabularyModel.createProperty(ELI_NS, "publisher");
        this.title = vocabularyModel.createProperty(ELI_NS, "title");
        this.source = vocabularyModel.createProperty(DCTERMS_NS, "source");
        this.authorityLabel = vocabularyModel.createProperty(PROJECT_NS, "authorityLabel");
        this.reference = vocabularyModel.createProperty(PROJECT_NS, "reference");
        this.referenceGu = vocabularyModel.createProperty(PROJECT_NS, "referenceGU");
        this.contentUrl = vocabularyModel.createProperty(SCHEMA_NS, "contentUrl");
    }

    public Model buildLegalActs(Collection<CleanLegalActRecord> records) {
        Model model = createModel();
        for (CleanLegalActRecord record : records) {
            addLegalAct(model, record);
        }
        return model;
    }

    public Model buildLegalAct(CleanLegalActRecord record) {
        Model model = createModel();
        addLegalAct(model, record);
        return model;
    }

    public void addLegalAct(Model model, CleanLegalActRecord record) {
        Resource legalAct = model.createResource(record.getEliUri());
        legalAct.addProperty(RDF.type, legalResourceType);

        record.getTitle().ifPresent(value -> {
            legalAct.addProperty(RDFS.label, value);
            legalAct.addProperty(title, value);
        });
        record.getPublicationDate().ifPresent(value -> legalAct.addLiteral(datePublication, dateLiteral(model, value)));
        record.getDocumentDate().ifPresent(value -> legalAct.addLiteral(dateDocument, dateLiteral(model, value)));
        record.getDocumentTypeUri().ifPresent(value -> legalAct.addProperty(typeDocument, model.createResource(value)));
        record.getLocalId().ifPresent(value -> legalAct.addProperty(idLocal, value));
        record.getRealizedByUri().ifPresent(value -> legalAct.addProperty(isRealizedBy, model.createResource(value)));
        record.getVersionUri().ifPresent(value -> legalAct.addProperty(version, model.createResource(value)));
        record.getAuthorityLabel().ifPresent(value -> legalAct.addProperty(authorityLabel, value));
        record.getReference().ifPresent(value -> legalAct.addProperty(reference, value));
        record.getReferenceGu().ifPresent(value -> legalAct.addProperty(referenceGu, value));
        record.getSourceUrl().ifPresent(value -> legalAct.addProperty(source, model.createResource(value)));

        record.getRealizedByUri().ifPresent(expressionUri -> addExpression(model, record, expressionUri));
        record.getEmbodiedByUri().ifPresent(formatUri -> addFormat(model, record, formatUri));
    }

    private void addExpression(Model model, CleanLegalActRecord record, String expressionUri) {
        Resource expression = model.createResource(expressionUri);
        expression.addProperty(RDF.type, legalExpressionType);
        expression.addProperty(model.createProperty(ELI_NS, "realizes"), model.createResource(record.getEliUri()));

        record.getTitle().ifPresent(value -> {
            expression.addProperty(RDFS.label, value);
            expression.addProperty(title, value);
        });
        record.getLanguageUri().ifPresent(value -> expression.addProperty(language, model.createResource(value)));
        record.getPublisherUri().ifPresent(value -> expression.addProperty(publisher, model.createResource(value)));
        record.getEmbodiedByUri().ifPresent(value -> expression.addProperty(isEmbodiedBy, model.createResource(value)));
    }

    private void addFormat(Model model, CleanLegalActRecord record, String manifestationUri) {
        Resource manifestation = model.createResource(manifestationUri);
        manifestation.addProperty(RDF.type, formatType);
        record.getFormatUri().ifPresent(value -> manifestation.addProperty(format, model.createResource(value)));
        record.getPdfGuUrl().ifPresent(value -> manifestation.addProperty(contentUrl, model.createResource(value)));
    }

    private Model createModel() {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("eli", ELI_NS);
        model.setNsPrefix("dcterms", DCTERMS_NS);
        model.setNsPrefix("schema", SCHEMA_NS);
        model.setNsPrefix("gu", GU_NS);
        model.setNsPrefix("ilg", PROJECT_NS);
        model.setNsPrefix("rdf", RDF.getURI());
        model.setNsPrefix("rdfs", RDFS.getURI());
        model.setNsPrefix("xsd", XSDDatatype.XSD + "#");
        return model;
    }

    private Literal dateLiteral(Model model, LocalDate date) {
        return model.createTypedLiteral(date.toString(), XSDDatatype.XSDdate);
    }
}
