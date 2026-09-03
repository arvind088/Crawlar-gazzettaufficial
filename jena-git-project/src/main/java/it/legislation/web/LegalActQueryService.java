package it.legislation.web;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LegalActQueryService {

    private final Tdb2DatasetService datasetService;

    @Autowired
    public LegalActQueryService(Tdb2DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    LegalActQueryService(List<Path> rdfPaths) throws IOException {
        this(Tdb2DatasetService.inMemoryForRdfPaths(rdfPaths));
    }

    LegalActQueryService(Path datasetPath, List<Path> rdfPaths) throws IOException {
        this(Tdb2DatasetService.forRdfPaths(datasetPath, rdfPaths));
    }

    public DataStatus status() throws IOException {
        Tdb2DatasetService.LoadStatus loadResult = datasetService.status();
        return new DataStatus(
                loadResult.triples(),
                loadResult.loadedFiles(),
                loadResult.missingFiles()
        );
    }

    public List<LegalActSummary> searchActs(String search, int limit) throws IOException {
        int safeLimit = Math.max(1, Math.min(limit, 100));

        String queryText = """
                PREFIX eli: <http://data.europa.eu/eli/ontology#>
                PREFIX ilg: <http://example.org/italian-legislation/ontology#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX dcterms: <http://purl.org/dc/terms/>

                SELECT DISTINCT ?act ?label ?publicationDate ?documentDate ?type ?localId ?source
                WHERE {
                  {
                    ?act a eli:LegalResource .
                  } UNION {
                    ?act (ilg:modifies|ilg:modifiedBy|eli:commences|eli:commenced_by) ?related .
                  } UNION {
                    ?related (ilg:modifies|ilg:modifiedBy|eli:commences|eli:commenced_by) ?act .
                  }
                  OPTIONAL { ?act rdfs:label ?label . }
                  OPTIONAL { ?act eli:date_publication ?publicationDate . }
                  OPTIONAL { ?act eli:date_document ?documentDate . }
                  OPTIONAL { ?act eli:type_document ?type . }
                  OPTIONAL { ?act eli:id_local ?localId . }
                  OPTIONAL { ?act dcterms:source ?source . }
                  FILTER (
                    (?needle = "" && (BOUND(?label) || BOUND(?localId) || BOUND(?publicationDate) || BOUND(?source))) ||
                    (?needle != "" && (
                      (BOUND(?label) && CONTAINS(LCASE(STR(?label)), LCASE(STR(?needle)))) ||
                      (BOUND(?localId) && CONTAINS(LCASE(STR(?localId)), LCASE(STR(?needle)))) ||
                      CONTAINS(LCASE(STR(?act)), LCASE(STR(?needle)))
                    ))
                  )
                }
                ORDER BY DESC(?publicationDate) ?act
                LIMIT %d
                """.formatted(safeLimit);

        ParameterizedSparqlString query = new ParameterizedSparqlString(queryText);
        query.setLiteral("needle", normalizeSearch(search));

        return datasetService.read(dataset -> {
            List<LegalActSummary> results = new ArrayList<>();
            try (QueryExecution execution = QueryExecutionFactory.create(query.asQuery(), dataset)) {
                ResultSet resultSet = execution.execSelect();
                while (resultSet.hasNext()) {
                    results.add(toSummary(resultSet.nextSolution()));
                }
            }
            return results;
        });
    }

    public Optional<LegalActSummary> findByLocalId(String localId) throws IOException {
        if (localId == null || localId.isBlank()) {
            return Optional.empty();
        }

        String queryText = """
                PREFIX eli: <http://data.europa.eu/eli/ontology#>
                PREFIX ilg: <http://example.org/italian-legislation/ontology#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX dcterms: <http://purl.org/dc/terms/>

                SELECT ?act ?label ?publicationDate ?documentDate ?type ?localId ?source
                WHERE {
                  {
                    ?act a eli:LegalResource .
                  } UNION {
                    ?act (ilg:modifies|ilg:modifiedBy|eli:commences|eli:commenced_by) ?related .
                  } UNION {
                    ?related (ilg:modifies|ilg:modifiedBy|eli:commences|eli:commenced_by) ?act .
                  }
                  OPTIONAL { ?act eli:id_local ?localId . }
                  OPTIONAL { ?act rdfs:label ?label . }
                  OPTIONAL { ?act eli:date_publication ?publicationDate . }
                  OPTIONAL { ?act eli:date_document ?documentDate . }
                  OPTIONAL { ?act eli:type_document ?type . }
                  OPTIONAL { ?act dcterms:source ?source . }
                  FILTER (
                    (BOUND(?localId) && ?localId = ?requestedLocalId) ||
                    CONTAINS(LCASE(STR(?act)), LCASE(CONCAT("/", STR(?requestedLocalId), "/")))
                  )
                }
                LIMIT 1
                """;

        ParameterizedSparqlString query = new ParameterizedSparqlString(queryText);
        query.setLiteral("requestedLocalId", localId.trim());

        return datasetService.read(dataset -> {
            try (QueryExecution execution = QueryExecutionFactory.create(query.asQuery(), dataset)) {
                ResultSet resultSet = execution.execSelect();
                if (resultSet.hasNext()) {
                    return Optional.of(toSummary(resultSet.nextSolution()));
                }
            }
            return Optional.empty();
        });
    }

    public Optional<LinkedDataResource> findLinkedDataResource(String identifier) throws IOException {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }

        String requested = identifier.trim();
        Optional<LegalActSummary> summary = isHttpUri(requested) ? findByUri(requested) : findByLocalId(requested);
        String resourceUri = summary.map(LegalActSummary::uri)
                .filter(uri -> uri != null && !uri.isBlank())
                .orElse(isHttpUri(requested) ? requested : null);

        if (resourceUri == null || resourceUri.isBlank()) {
            return Optional.empty();
        }

        return datasetService.read(dataset -> {
            if (!resourceExists(dataset, resourceUri)) {
                return Optional.empty();
            }

            LegalActSummary resolved = summary.orElseGet(() -> summaryForUri(dataset, resourceUri));
            String localId = resolved.localId() == null || resolved.localId().isBlank()
                    ? localIdFromUri(resourceUri)
                    : resolved.localId();
            String title = resolved.title();

            return Optional.of(new LinkedDataResource(
                    resourceUri,
                    localId,
                    title,
                    expressionNodes(dataset, resourceUri),
                    manifestationNodes(dataset, resourceUri),
                    outgoingRelations(dataset, resourceUri),
                    incomingRelations(dataset, resourceUri)
            ));
        });
    }

    public SparqlQueryResult executeSelectQuery(String queryText) throws IOException {
        if (queryText == null || queryText.isBlank()) {
            return SparqlQueryResult.error("Query error: enter a SPARQL SELECT query.");
        }

        Query query = QueryFactory.create(queryText);
        if (!query.isSelectType()) {
            return SparqlQueryResult.error("Query error: only SELECT queries are supported in this explorer.");
        }

        return datasetService.read(dataset -> {
            List<Map<String, String>> rows = new ArrayList<>();
            List<String> columns;

            try (QueryExecution execution = QueryExecutionFactory.create(query, dataset)) {
                ResultSet resultSet = execution.execSelect();
                columns = new ArrayList<>(resultSet.getResultVars());
                while (resultSet.hasNext() && rows.size() < 100) {
                    QuerySolution solution = resultSet.nextSolution();
                    Map<String, String> row = new LinkedHashMap<>();
                    for (String column : columns) {
                        row.put(column, value(solution, column));
                    }
                    rows.add(row);
                }
            }

            return new SparqlQueryResult(columns, rows, null);
        });
    }

    public Optional<String> rdfForLocalId(String localId) throws IOException {
        Optional<LegalActSummary> maybeAct = findByLocalId(localId);
        if (maybeAct.isEmpty() || maybeAct.get().uri() == null || maybeAct.get().uri().isBlank()) {
            return Optional.empty();
        }

        Model selected = datasetService.read(dataset -> {
            Model source = dataset.getDefaultModel();
            Model model = ModelFactory.createDefaultModel();
            model.setNsPrefixes(source.getNsPrefixMap());

            Resource act = source.createResource(maybeAct.get().uri());
            source.listStatements(act, null, (RDFNode) null).forEachRemaining(statement -> {
                model.add(statement);
                if (statement.getObject().isResource()) {
                    Resource linkedResource = statement.getObject().asResource();
                    source.listStatements(linkedResource, null, (RDFNode) null).forEachRemaining(model::add);
                }
            });
            source.listStatements(null, null, act).forEachRemaining(model::add);
            return model;
        });

        if (selected.isEmpty()) {
            return Optional.empty();
        }

        StringWriter writer = new StringWriter();
        RDFDataMgr.write(writer, selected, RDFFormat.TURTLE_PRETTY);
        return Optional.of(writer.toString());
    }

    void closeForTests() {
        datasetService.close();
    }

    private String normalizeSearch(String search) {
        return search == null ? "" : search.trim();
    }

    private LegalActSummary toSummary(QuerySolution solution) {
        String uri = value(solution, "act");
        String localId = value(solution, "localId");
        return withEliFallbacks(new LegalActSummary(
                uri,
                value(solution, "label"),
                value(solution, "publicationDate"),
                value(solution, "documentDate"),
                value(solution, "type"),
                localId == null || localId.isBlank() ? localIdFromUri(uri) : localId,
                value(solution, "source")
        ));
    }

    private Optional<LegalActSummary> findByUri(String uri) throws IOException {
        return datasetService.read(dataset -> {
            LegalActSummary summary = summaryForUri(dataset, uri);
            return summary.uri() == null ? Optional.empty() : Optional.of(summary);
        });
    }

    private LegalActSummary summaryForUri(org.apache.jena.query.Dataset dataset, String uri) {
        String queryText = """
                PREFIX eli: <http://data.europa.eu/eli/ontology#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX dcterms: <http://purl.org/dc/terms/>

                SELECT ?label ?publicationDate ?documentDate ?type ?localId ?source
                WHERE {
                  OPTIONAL { ?resource rdfs:label ?label . }
                  OPTIONAL { ?resource eli:date_publication ?publicationDate . }
                  OPTIONAL { ?resource eli:date_document ?documentDate . }
                  OPTIONAL { ?resource eli:type_document ?type . }
                  OPTIONAL { ?resource eli:id_local ?localId . }
                  OPTIONAL { ?resource dcterms:source ?source . }
                }
                LIMIT 1
                """;

        ParameterizedSparqlString query = new ParameterizedSparqlString(queryText);
        query.setIri("resource", uri);

        try (QueryExecution execution = QueryExecutionFactory.create(query.asQuery(), dataset)) {
            ResultSet resultSet = execution.execSelect();
            if (resultSet.hasNext()) {
                QuerySolution solution = resultSet.nextSolution();
                String localId = value(solution, "localId");
                return withEliFallbacks(new LegalActSummary(
                        uri,
                        value(solution, "label"),
                        value(solution, "publicationDate"),
                        value(solution, "documentDate"),
                        value(solution, "type"),
                        localId == null || localId.isBlank() ? localIdFromUri(uri) : localId,
                        value(solution, "source")
                ));
            }
        }

        return withEliFallbacks(new LegalActSummary(uri, null, null, null, null, localIdFromUri(uri), null));
    }

    private LegalActSummary withEliFallbacks(LegalActSummary summary) {
        String uri = summary.uri();
        if (!isGazzettaEliIdUri(uri)) {
            return summary;
        }

        return new LegalActSummary(
                uri,
                summary.title(),
                hasText(summary.publicationDate()) ? summary.publicationDate() : publicationDateFromEliUri(uri),
                summary.documentDate(),
                summary.type(),
                hasText(summary.localId()) ? summary.localId() : localIdFromUri(uri),
                hasText(summary.source()) ? summary.source() : uri
        );
    }

    private boolean resourceExists(org.apache.jena.query.Dataset dataset, String uri) {
        String queryText = """
                ASK {
                  { ?resource ?predicate ?object . }
                  UNION
                  { ?subject ?predicate ?resource . }
                }
                """;

        ParameterizedSparqlString query = new ParameterizedSparqlString(queryText);
        query.setIri("resource", uri);

        try (QueryExecution execution = QueryExecutionFactory.create(query.asQuery(), dataset)) {
            return execution.execAsk();
        }
    }

    private List<LinkedDataNode> expressionNodes(org.apache.jena.query.Dataset dataset, String resourceUri) {
        String queryText = """
                PREFIX eli: <http://data.europa.eu/eli/ontology#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

                SELECT DISTINCT ?node ?label ?type (COALESCE(?expressionVersion, ?workVersion) AS ?version) ?language
                WHERE {
                  ?resource eli:is_realized_by ?node .
                  OPTIONAL { ?node rdfs:label ?label . }
                  OPTIONAL { ?node rdf:type ?type . }
                  OPTIONAL { ?node eli:version ?expressionVersion . }
                  OPTIONAL { ?resource eli:version ?workVersion . }
                  OPTIONAL { ?node eli:language ?language . }
                }
                ORDER BY ?node
                """;

        ParameterizedSparqlString query = new ParameterizedSparqlString(queryText);
        query.setIri("resource", resourceUri);
        return linkedNodes(dataset, query);
    }

    private List<LinkedDataNode> manifestationNodes(org.apache.jena.query.Dataset dataset, String resourceUri) {
        String queryText = """
                PREFIX eli: <http://data.europa.eu/eli/ontology#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

                SELECT DISTINCT ?node ?label ?type ?format ?language
                WHERE {
                  ?resource eli:is_realized_by ?expression .
                  ?expression eli:is_embodied_by ?node .
                  OPTIONAL { ?node rdfs:label ?label . }
                  OPTIONAL { ?node rdf:type ?type . }
                  OPTIONAL { ?node eli:format ?format . }
                  OPTIONAL { ?expression eli:language ?language . }
                }
                ORDER BY ?node
                """;

        ParameterizedSparqlString query = new ParameterizedSparqlString(queryText);
        query.setIri("resource", resourceUri);
        return linkedNodes(dataset, query);
    }

    private List<LinkedDataRelation> outgoingRelations(org.apache.jena.query.Dataset dataset, String resourceUri) {
        String queryText = """
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

                SELECT DISTINCT ?predicate ?target ?label
                WHERE {
                  ?resource ?predicate ?target .
                  FILTER(isIRI(?target))
                  OPTIONAL { ?target rdfs:label ?label . }
                }
                ORDER BY ?predicate ?target
                LIMIT 100
                """;

        ParameterizedSparqlString query = new ParameterizedSparqlString(queryText);
        query.setIri("resource", resourceUri);
        return relations(dataset, query, "target");
    }

    private List<LinkedDataRelation> incomingRelations(org.apache.jena.query.Dataset dataset, String resourceUri) {
        String queryText = """
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

                SELECT DISTINCT ?predicate ?source ?label
                WHERE {
                  ?source ?predicate ?resource .
                  FILTER(isIRI(?source))
                  OPTIONAL { ?source rdfs:label ?label . }
                }
                ORDER BY ?predicate ?source
                LIMIT 100
                """;

        ParameterizedSparqlString query = new ParameterizedSparqlString(queryText);
        query.setIri("resource", resourceUri);
        return relations(dataset, query, "source");
    }

    private List<LinkedDataNode> linkedNodes(org.apache.jena.query.Dataset dataset, ParameterizedSparqlString query) {
        List<LinkedDataNode> nodes = new ArrayList<>();
        try (QueryExecution execution = QueryExecutionFactory.create(query.asQuery(), dataset)) {
            ResultSet resultSet = execution.execSelect();
            while (resultSet.hasNext()) {
                QuerySolution solution = resultSet.nextSolution();
                String uri = value(solution, "node");
                nodes.add(new LinkedDataNode(
                        uri,
                        value(solution, "label"),
                        localIdFromUri(uri),
                        value(solution, "type"),
                        value(solution, "version"),
                        value(solution, "language"),
                        value(solution, "format")
                ));
            }
        }
        return nodes;
    }

    private List<LinkedDataRelation> relations(
            org.apache.jena.query.Dataset dataset,
            ParameterizedSparqlString query,
            String resourceColumn
    ) {
        List<LinkedDataRelation> relations = new ArrayList<>();
        try (QueryExecution execution = QueryExecutionFactory.create(query.asQuery(), dataset)) {
            ResultSet resultSet = execution.execSelect();
            while (resultSet.hasNext()) {
                QuerySolution solution = resultSet.nextSolution();
                String predicate = value(solution, "predicate");
                String resourceUri = value(solution, resourceColumn);
                String predicateLabel = compactPredicate(predicate);
                relations.add(new LinkedDataRelation(
                        predicate,
                        predicateLabel,
                        humanRelationLabel(predicateLabel),
                        isImportantRelation(predicateLabel),
                        resourceUri,
                        value(solution, "label"),
                        localIdFromUri(resourceUri)
                ));
            }
        }
        relations.sort(Comparator
                .comparingInt((LinkedDataRelation relation) -> relationPriority(relation.predicateLabel()))
                .thenComparing(LinkedDataRelation::predicateLabel, Comparator.nullsLast(String::compareTo))
                .thenComparing(LinkedDataRelation::resourceLocalId, Comparator.nullsLast(String::compareTo))
                .thenComparing(LinkedDataRelation::resourceUri, Comparator.nullsLast(String::compareTo)));
        return relations;
    }

    private int relationPriority(String predicateLabel) {
        if (predicateLabel == null) {
            return 100;
        }
        return switch (predicateLabel) {
            case "ilg:modifies", "ilg:modifiedBy", "eli:commences", "eli:commenced_by" -> 0;
            case "eli:is_realized_by", "eli:realizes", "eli:is_embodied_by", "eli:is_embodied_in" -> 1;
            case "eli:type_document", "eli:version", "dcterms:source" -> 2;
            case "rdf:type" -> 3;
            default -> 10;
        };
    }

    private boolean isImportantRelation(String predicateLabel) {
        return relationPriority(predicateLabel) <= 1;
    }

    private String humanRelationLabel(String predicateLabel) {
        if (predicateLabel == null) {
            return null;
        }
        return switch (predicateLabel) {
            case "ilg:modifies" -> "Modifies";
            case "ilg:modifiedBy" -> "Modified by";
            case "eli:commences" -> "Commences / converts";
            case "eli:commenced_by" -> "Commenced / converted by";
            case "eli:is_realized_by" -> "Has expression";
            case "eli:realizes" -> "Expression of";
            case "eli:is_embodied_by" -> "Has manifestation";
            case "eli:is_embodied_in" -> "Manifestation of";
            case "eli:type_document" -> "Document type";
            case "eli:version" -> "Version";
            case "dcterms:source" -> "Source";
            case "rdf:type" -> "RDF class";
            default -> predicateLabel;
        };
    }

    private String compactPredicate(String predicate) {
        if (predicate == null) {
            return null;
        }
        Map<String, String> namespaces = Map.of(
                "http://data.europa.eu/eli/ontology#", "eli:",
                "http://purl.org/dc/terms/", "dcterms:",
                "http://www.w3.org/2000/01/rdf-schema#", "rdfs:",
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:",
                "http://example.org/italian-legislation/ontology#", "ilg:"
        );
        for (Map.Entry<String, String> entry : namespaces.entrySet()) {
            if (predicate.startsWith(entry.getKey())) {
                return entry.getValue() + predicate.substring(entry.getKey().length());
            }
        }
        return predicate;
    }

    private boolean isHttpUri(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private boolean isGazzettaEliIdUri(String uri) {
        return uri != null && uri.contains("gazzettaufficiale.it/eli/id/");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String publicationDateFromEliUri(String uri) {
        if (uri == null) {
            return null;
        }

        String[] parts = uri.split("/");
        for (int index = 0; index + 3 < parts.length; index++) {
            if ("id".equals(parts[index])
                    && isFourDigitYear(parts[index + 1])
                    && isTwoDigitMonthOrDay(parts[index + 2])
                    && isTwoDigitMonthOrDay(parts[index + 3])) {
                return parts[index + 1] + "-" + parts[index + 2] + "-" + parts[index + 3];
            }
        }
        return null;
    }

    private boolean isFourDigitYear(String value) {
        return value != null && value.matches("\\d{4}");
    }

    private boolean isTwoDigitMonthOrDay(String value) {
        return value != null && value.matches("\\d{2}");
    }

    private String localIdFromUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        String[] parts = uri.split("/");
        if (parts.length >= 2 && "sg".equalsIgnoreCase(parts[parts.length - 1])) {
            return parts[parts.length - 2];
        }
        return null;
    }

    private String value(QuerySolution solution, String name) {
        if (!solution.contains(name)) {
            return null;
        }

        RDFNode node = solution.get(name);
        if (node == null) {
            return null;
        }
        if (node.isLiteral()) {
            return node.asLiteral().getLexicalForm();
        }
        return node.toString();
    }
}
