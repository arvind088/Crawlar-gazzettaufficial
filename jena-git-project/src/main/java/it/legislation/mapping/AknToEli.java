package it.legislation.mapping;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import it.legislation.eli.EliUriService;
import it.legislation.model.AknExpression;

/**
 * Maps the versions of one act, read from Normattiva Akoma Ntoso, to ELI RDF.
 *
 * <pre>
 * Work           {base}/eli/id/{GU yyyy/mm/dd}/{codice}/sg
 *   Expression   .../sg/ita/original              (the text as first published)
 *   Expression   .../sg/ita/vigente/{yyyy-mm-dd}  (each later version)
 *     Format     .../akn                          (the Akoma Ntoso file)
 * </pre>
 *
 * The Work URI follows the Gazzetta Ufficiale ELI pattern on the application's
 * own domain, so a Gazzetta record of the same act lands on the same Work
 * (linked with {@code owl:sameAs}). The Normattiva URN is kept only as a
 * literal {@code dcterms:identifier}.
 */
public class AknToEli {

    public static final String ELI = "http://data.europa.eu/eli/ontology#";
    public static final String ILG = "http://example.org/italian-legislation/ontology#";
    static final String RESOURCE_TYPE = "http://www.gazzettaufficiale.it/eli/tables/resource-type#";
    static final String ITALIAN = "http://publications.europa.eu/resource/authority/language/ITA";
    static final String AKN_FORMAT = "https://www.iana.org/assignments/media-types/application/akn+xml";
    static final String NORMATTIVA_OPEN_DATA = "https://dati.normattiva.it/";
    private static final DateTimeFormatter LABEL_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN);

    private final EliUriService uris;

    public AknToEli(EliUriService uris) {
        this.uris = uris;
    }

    /** Builds the RDF for one act. All expressions must share the same codice redazionale. */
    public Model map(List<AknExpression> versions) {
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("No versions to map");
        }
        AknExpression first = versions.stream()
                .sorted(Comparator.comparing(AknExpression::versionDate))
                .findFirst().orElseThrow();
        for (AknExpression version : versions) {
            if (!version.codiceRedazionale().equals(first.codiceRedazionale())) {
                throw new IllegalArgumentException("Mixed acts: " + first.codiceRedazionale()
                        + " and " + version.codiceRedazionale());
            }
        }

        String title = cleanTitle(first.title(), first.codiceRedazionale());
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("eli", ELI);
        model.setNsPrefix("ilg", ILG);
        model.setNsPrefix("dcterms", DCTerms.NS);
        model.setNsPrefix("owl", OWL.NS);
        model.setNsPrefix("rdfs", RDFS.uri);
        model.setNsPrefix("xsd", XSDDatatype.XSD + "#");

        String workPath = workPath(first);
        Resource work = model.createResource(uris.uriForPath(workPath));
        work.addProperty(RDF.type, eli(model, "LegalResource"));
        work.addProperty(RDFS.label, title, "it");
        work.addProperty(eliProperty(model, "title"), title, "it");
        work.addProperty(eliProperty(model, "type_document"), model.createResource(RESOURCE_TYPE + typeCode(first.actType())));
        work.addLiteral(eliProperty(model, "date_document"), model.createTypedLiteral(first.documentDate().toString(), XSDDatatype.XSDdate));
        work.addLiteral(eliProperty(model, "date_publication"), model.createTypedLiteral(first.gazzettaDate().toString(), XSDDatatype.XSDdate));
        work.addProperty(eliProperty(model, "id_local"), first.codiceRedazionale());
        work.addProperty(eliProperty(model, "number"), first.number());
        if (first.urnNir() != null) {
            work.addProperty(DCTerms.identifier, first.urnNir());
        }
        work.addProperty(DCTerms.source, model.createResource(NORMATTIVA_OPEN_DATA));
        uris.gazzettaUriForPath(workPath).ifPresent(gazzetta -> work.addProperty(OWL.sameAs, model.createResource(gazzetta)));

        for (AknExpression version : versions) {
            String expressionPath = workPath + (version.original()
                    ? "/ita/original"
                    : "/ita/vigente/" + version.versionDate());
            Resource expression = model.createResource(uris.uriForPath(expressionPath));
            expression.addProperty(RDF.type, eli(model, "LegalExpression"));
            expression.addProperty(eliProperty(model, "realizes"), work);
            work.addProperty(eliProperty(model, "is_realized_by"), expression);
            String versionTitle = cleanTitle(version.title(), version.codiceRedazionale());
            expression.addProperty(RDFS.label, versionTitle + (version.original()
                    ? " (testo originale)"
                    : " (testo vigente dal " + LABEL_DATE.format(version.versionDate()) + ")"), "it");
            expression.addProperty(eliProperty(model, "title"), versionTitle, "it");
            expression.addProperty(eliProperty(model, "language"), model.createResource(ITALIAN));
            expression.addLiteral(eliProperty(model, "version_date"),
                    model.createTypedLiteral(version.versionDate().toString(), XSDDatatype.XSDdate));

            Resource format = model.createResource(uris.uriForPath(expressionPath + "/akn"));
            format.addProperty(RDF.type, eli(model, "Format"));
            format.addProperty(eliProperty(model, "format"), model.createResource(AKN_FORMAT));
            format.addProperty(eliProperty(model, "embodies"), expression);
            expression.addProperty(eliProperty(model, "is_embodied_by"), format);
            format.addProperty(DCTerms.source, version.sourceFile());
            format.addProperty(model.createProperty(ILG, "sha256"), version.sha256());
        }
        return model;
    }

    /** ELI path of the Work: GU date and codice redazionale, Serie Generale. */
    public String workPath(AknExpression act) {
        return uris.path(
                String.format("%04d", act.gazzettaDate().getYear()),
                String.format("%02d", act.gazzettaDate().getMonthValue()),
                String.format("%02d", act.gazzettaDate().getDayOfMonth()),
                act.codiceRedazionale(), "sg", null);
    }

    /** Removes the trailing "(codice redazionale)" that Gazzetta appends to titles. */
    static String cleanTitle(String title, String codice) {
        String trimmed = title.trim();
        String suffix = "(" + codice + ")";
        return trimmed.endsWith(suffix) ? trimmed.substring(0, trimmed.length() - suffix.length()).trim() : trimmed;
    }

    /** Gazzetta resource-type code: upper case, spaces as underscores ({@code DECRETO LEGISLATIVO} → {@code DECRETO_LEGISLATIVO}). */
    static String typeCode(String actType) {
        return actType.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    private static Resource eli(Model model, String localName) {
        return model.createResource(ELI + localName);
    }

    private static Property eliProperty(Model model, String localName) {
        return model.createProperty(ELI, localName);
    }
}
