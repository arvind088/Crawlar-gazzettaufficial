package it.legislation.eli;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.springframework.stereotype.Service;

import it.legislation.web.Tdb2DatasetService;

/**
 * Resolves an ELI path to the resource it identifies, by running SPARQL against
 * the triple store at request time.
 *
 * <p>Nothing here is cached or pre-rendered: CONTEXT.md constraint 4 requires
 * every page to be produced from a live query.
 */
@Service
public class EliResolutionService {

    private final Tdb2DatasetService datasetService;
    private final EliUriService uriService;

    public EliResolutionService(Tdb2DatasetService datasetService, EliUriService uriService) {
        this.datasetService = datasetService;
        this.uriService = uriService;
    }

    public EliUriService uriService() {
        return uriService;
    }

    /**
     * Finds the resource for an ELI path and collects everything the store says
     * about it, in both directions.
     */
    public Optional<ResolvedResource> resolve(String path) throws IOException {
        List<String> candidates = uriService.candidateUris(path);

        return datasetService.read(dataset -> {
            Model source = dataset.getDefaultModel();

            String storedUri = null;
            for (String candidate : candidates) {
                if (exists(dataset, candidate)) {
                    storedUri = candidate;
                    break;
                }
            }
            if (storedUri == null) {
                return Optional.empty();
            }

            Model description = describe(dataset, storedUri);
            Model incoming = incoming(dataset, storedUri);

            Model combined = ModelFactory.createDefaultModel();
            combined.setNsPrefixes(source.getNsPrefixMap());
            combined.add(description);
            combined.add(incoming);

            return Optional.of(new ResolvedResource(
                    uriService.uriForPath(path),
                    storedUri,
                    path,
                    label(description, storedUri),
                    groupByPredicate(description.listStatements(
                            description.getResource(storedUri), null, (RDFNode) null).toList(), false),
                    groupByPredicate(incoming.listStatements(
                            null, null, incoming.getResource(storedUri)).toList(), true),
                    combined
            ));
        });
    }

    /** The same description, serialised as Turtle for machine clients. */
    public String toTurtle(ResolvedResource resource) {
        StringWriter writer = new StringWriter();
        RDFDataMgr.write(writer, resource.model(), RDFFormat.TURTLE_PRETTY);
        return writer.toString();
    }

    private boolean exists(org.apache.jena.query.Dataset dataset, String uri) {
        ParameterizedSparqlString ask = new ParameterizedSparqlString("""
                ASK {
                  { ?resource ?predicate ?object . }
                  UNION
                  { ?subject ?predicate ?resource . }
                }
                """);
        ask.setIri("resource", uri);
        try (QueryExecution execution = QueryExecutionFactory.create(ask.asQuery(), dataset)) {
            return execution.execAsk();
        }
    }

    private Model describe(org.apache.jena.query.Dataset dataset, String uri) {
        ParameterizedSparqlString describe = new ParameterizedSparqlString("DESCRIBE ?resource");
        describe.setIri("resource", uri);
        try (QueryExecution execution = QueryExecutionFactory.create(describe.asQuery(), dataset)) {
            return execution.execDescribe();
        }
    }

    private Model incoming(org.apache.jena.query.Dataset dataset, String uri) {
        ParameterizedSparqlString construct = new ParameterizedSparqlString("""
                CONSTRUCT { ?subject ?predicate ?resource }
                WHERE {
                  ?subject ?predicate ?resource .
                  FILTER(isIRI(?subject))
                }
                """);
        construct.setIri("resource", uri);
        try (QueryExecution execution = QueryExecutionFactory.create(construct.asQuery(), dataset)) {
            return execution.execConstruct();
        }
    }

    private String label(Model model, String uri) {
        org.apache.jena.rdf.model.Resource resource = model.getResource(uri);
        for (String labelProperty : List.of(
                "http://www.w3.org/2000/01/rdf-schema#label",
                "http://data.europa.eu/eli/ontology#title",
                "http://purl.org/dc/terms/title")) {
            Statement statement = resource.getProperty(model.createProperty(labelProperty));
            if (statement != null && statement.getObject().isLiteral()) {
                return statement.getObject().asLiteral().getLexicalForm();
            }
        }
        return uriService.localIdOf(uri).orElse(uri);
    }

    /**
     * Groups statements by predicate without special-casing any of them.
     * Constraint 5 requires one generic path: a new predicate becomes navigable
     * with no code change here.
     */
    private List<RelationGroup> groupByPredicate(List<Statement> statements, boolean inbound) {
        Map<String, List<RelatedValue>> grouped = new LinkedHashMap<>();
        for (Statement statement : statements) {
            String predicate = statement.getPredicate().getURI();
            RDFNode node = inbound ? statement.getSubject() : statement.getObject();
            RelatedValue value = node.isURIResource()
                    ? RelatedValue.iri(node.asResource().getURI())
                    : RelatedValue.literal(node.isLiteral()
                            ? node.asLiteral().getLexicalForm()
                            : node.toString());
            grouped.computeIfAbsent(predicate, key -> new ArrayList<>()).add(value);
        }

        List<RelationGroup> groups = new ArrayList<>();
        grouped.forEach((predicate, values) -> groups.add(new RelationGroup(predicate, values)));
        groups.sort((left, right) -> left.predicate().compareTo(right.predicate()));
        return groups;
    }

    public record ResolvedResource(
            String publishedUri,
            String storedUri,
            String path,
            String label,
            List<RelationGroup> outgoing,
            List<RelationGroup> incoming,
            Model model
    ) {
    }

    public record RelationGroup(String predicate, List<RelatedValue> values) {
    }

    public record RelatedValue(String value, boolean iri) {
        public static RelatedValue iri(String value) {
            return new RelatedValue(value, true);
        }

        public static RelatedValue literal(String value) {
            return new RelatedValue(value, false);
        }
    }
}
