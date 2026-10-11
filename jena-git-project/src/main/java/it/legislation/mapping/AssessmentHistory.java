package it.legislation.mapping;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;

/**
 * Dated assessments: how the store keeps results that change over time while
 * ingestion stays additive (CONTEXT.md, docs/update-routine-rationale.md).
 *
 * <p>Facts (an act, a version, "L amends D") are only ever added. Results that
 * can change between runs (a decree-law PENDING today and CONVERTED next month,
 * a Gazzetta page that failed today and matches tomorrow, a relation whose trust
 * level rises from B to A) are written as <em>assessment</em> nodes:
 *
 * <pre>
 * &lt;…/conversion/26G00167/2026-10-11&gt; a ilg:ConversionCheck ;
 *     ilg:assesses &lt;decree&gt; ; ilg:assessedOn "2026-10-11"^^xsd:date ;
 *     ilg:conversionStatus "PENDING" .
 * </pre>
 *
 * A run writes a new node only when the result differs from the latest earlier
 * one; otherwise the earlier node is kept as it is. Nothing is removed, so the
 * output file grows only when something changed, and the store holds the full
 * history of every status. The current status is the assessment with the
 * latest {@code ilg:assessedOn}.
 */
public final class AssessmentHistory {

    public static final String ILG = "http://example.org/italian-legislation/ontology#";

    private final Model previous;
    private final Property assesses;
    private final Property assessedOn;

    private AssessmentHistory(Model previous) {
        this.previous = previous;
        this.assesses = previous.createProperty(ILG, "assesses");
        this.assessedOn = previous.createProperty(ILG, "assessedOn");
    }

    /** The history in an earlier output file; empty when the file does not exist yet. */
    public static AssessmentHistory read(Path file) throws IOException {
        Model model = ModelFactory.createDefaultModel();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                RDFDataMgr.read(model, in, Lang.TURTLE);
            }
        }
        return new AssessmentHistory(model);
    }

    /** Everything written before: the new output starts from it, so no statement is ever dropped. */
    public Model previous() {
        return previous;
    }

    /**
     * Records an assessment of {@code subject} on {@code day}, unless the latest earlier assessment of
     * the same type already says exactly the same.
     *
     * @param nodeBase URI prefix of the assessment nodes; the date is appended
     * @return true when a new assessment node was added
     */
    public boolean record(Model target, Resource subject, String type, String nodeBase, LocalDate day,
                          Map<Property, List<RDFNode>> values) {
        if (latestValues(subject, type).map(v -> v.equals(signature(values))).orElse(false)) {
            return false;
        }
        Resource node = target.createResource(nodeBase + "/" + day);
        node.addProperty(RDF.type, target.createResource(ILG + type));
        node.addProperty(target.createProperty(ILG, "assesses"), subject);
        node.addLiteral(target.createProperty(ILG, "assessedOn"),
                target.createTypedLiteral(day.toString(), XSDDatatype.XSDdate));
        values.forEach((p, objects) -> objects.forEach(o -> node.addProperty(p, o)));
        return true;
    }

    /** Subjects that have at least one earlier assessment of this type. */
    public Set<String> assessedSubjects(String type) {
        Set<String> subjects = new TreeSet<>();
        previous.listSubjectsWithProperty(RDF.type, previous.createResource(ILG + type)).forEachRemaining(node -> {
            Statement s = node.getProperty(assesses);
            if (s != null && s.getObject().isURIResource()) {
                subjects.add(s.getResource().getURI());
            }
        });
        return subjects;
    }

    /** The values of the latest earlier assessment of {@code subject}, as a comparable signature. */
    Optional<Set<String>> latestValues(Resource subject, String type) {
        Resource typeResource = previous.createResource(ILG + type);
        return previous.listSubjectsWithProperty(assesses, previous.createResource(subject.getURI())).toList().stream()
                .filter(node -> node.hasProperty(RDF.type, typeResource) && node.hasProperty(assessedOn))
                .max(Comparator.comparing(node -> node.getProperty(assessedOn).getString()))
                .map(node -> {
                    Set<String> values = new TreeSet<>();
                    node.listProperties().forEachRemaining(s -> {
                        String p = s.getPredicate().getURI();
                        if (!p.equals(RDF.type.getURI()) && !p.equals(assesses.getURI()) && !p.equals(assessedOn.getURI())) {
                            values.add(p + " " + key(s.getObject()));
                        }
                    });
                    return values;
                });
    }

    private static Set<String> signature(Map<Property, List<RDFNode>> values) {
        Set<String> signature = new TreeSet<>();
        values.forEach((p, objects) -> objects.forEach(o -> signature.add(p.getURI() + " " + key(o))));
        return signature;
    }

    private static String key(RDFNode node) {
        if (node.isURIResource()) {
            return "<" + node.asResource().getURI() + ">";
        }
        if (node.isLiteral()) {
            return "\"" + node.asLiteral().getLexicalForm() + "\"";
        }
        return node.toString();
    }

    /** Small builder for the values of one assessment, in insertion order. */
    public static final class Values {
        private final Map<Property, List<RDFNode>> values = new LinkedHashMap<>();

        public Values put(Property property, RDFNode value) {
            if (value != null) {
                values.computeIfAbsent(property, p -> new java.util.ArrayList<>()).add(value);
            }
            return this;
        }

        public Map<Property, List<RDFNode>> build() {
            return values;
        }
    }
}
