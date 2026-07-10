package it.legislation.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.jena.query.Query;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.springframework.stereotype.Service;

@Service
public class LegalActQueryService {

    private static final Path GAZZETTA_DELTA = Path.of("data", "rdf", "gazzetta_metadata_delta.ttl");
    private static final Path NORMATTIVA_MODIFICATIONS = Path.of("data", "rdf", "normattiva_modifications.ttl");
    private static final Path NORMATTIVA_AUTO_MODIFICATIONS = Path.of("data", "rdf", "normattiva_modifications_auto.ttl");

    private final List<Path> rdfPaths;

    public LegalActQueryService() {
        this(List.of(GAZZETTA_DELTA, NORMATTIVA_MODIFICATIONS, NORMATTIVA_AUTO_MODIFICATIONS));
    }

    LegalActQueryService(List<Path> rdfPaths) {
        this.rdfPaths = List.copyOf(rdfPaths);
    }

    public DataStatus status() throws IOException {
        LoadResult loadResult = loadData();
        return new DataStatus(
                loadResult.model().size(),
                loadResult.loadedFiles(),
                loadResult.missingFiles()
        );
    }

    public List<LegalActSummary> searchActs(String search, int limit) throws IOException {
        Model model = loadData().model();
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

        List<LegalActSummary> results = new ArrayList<>();
        try (QueryExecution execution = QueryExecutionFactory.create(query.asQuery(), model)) {
            ResultSet resultSet = execution.execSelect();
            while (resultSet.hasNext()) {
                results.add(toSummary(resultSet.nextSolution()));
            }
        }
        return results;
    }

    public Optional<LegalActSummary> findByLocalId(String localId) throws IOException {
        if (localId == null || localId.isBlank()) {
            return Optional.empty();
        }

        Model model = loadData().model();
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

        try (QueryExecution execution = QueryExecutionFactory.create(query.asQuery(), model)) {
            ResultSet resultSet = execution.execSelect();
            if (resultSet.hasNext()) {
                return Optional.of(toSummary(resultSet.nextSolution()));
            }
        }
        return Optional.empty();
    }

    public SparqlQueryResult executeSelectQuery(String queryText) throws IOException {
        if (queryText == null || queryText.isBlank()) {
            return SparqlQueryResult.error("Query error: enter a SPARQL SELECT query.");
        }

        Query query = QueryFactory.create(queryText);
        if (!query.isSelectType()) {
            return SparqlQueryResult.error("Query error: only SELECT queries are supported in this explorer.");
        }

        Model model = loadData().model();
        List<Map<String, String>> rows = new ArrayList<>();
        List<String> columns;

        try (QueryExecution execution = QueryExecutionFactory.create(query, model)) {
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
    }

    public Optional<String> rdfForLocalId(String localId) throws IOException {
        Optional<LegalActSummary> maybeAct = findByLocalId(localId);
        if (maybeAct.isEmpty() || maybeAct.get().uri() == null || maybeAct.get().uri().isBlank()) {
            return Optional.empty();
        }

        Model source = loadData().model();
        Model selected = ModelFactory.createDefaultModel();
        selected.setNsPrefixes(source.getNsPrefixMap());

        Resource act = source.createResource(maybeAct.get().uri());
        source.listStatements(act, null, (RDFNode) null).forEachRemaining(statement -> {
            selected.add(statement);
            if (statement.getObject().isResource()) {
                Resource linkedResource = statement.getObject().asResource();
                source.listStatements(linkedResource, null, (RDFNode) null).forEachRemaining(selected::add);
            }
        });
        source.listStatements(null, null, act).forEachRemaining(selected::add);

        if (selected.isEmpty()) {
            return Optional.empty();
        }

        StringWriter writer = new StringWriter();
        RDFDataMgr.write(writer, selected, RDFFormat.TURTLE_PRETTY);
        return Optional.of(writer.toString());
    }

    private LoadResult loadData() throws IOException {
        Model model = ModelFactory.createDefaultModel();
        List<String> loadedFiles = new ArrayList<>();
        List<String> missingFiles = new ArrayList<>();

        for (Path rdfPath : rdfPaths) {
            if (Files.exists(rdfPath)) {
                try (InputStream inputStream = Files.newInputStream(rdfPath)) {
                    RDFDataMgr.read(model, inputStream, Lang.TURTLE);
                }
                loadedFiles.add(rdfPath.toAbsolutePath().normalize().toString());
            } else {
                missingFiles.add(rdfPath.toAbsolutePath().normalize().toString());
            }
        }

        return new LoadResult(model, loadedFiles, missingFiles);
    }

    private String normalizeSearch(String search) {
        return search == null ? "" : search.trim();
    }

    private LegalActSummary toSummary(QuerySolution solution) {
        String uri = value(solution, "act");
        String localId = value(solution, "localId");
        return new LegalActSummary(
                uri,
                value(solution, "label"),
                value(solution, "publicationDate"),
                value(solution, "documentDate"),
                value(solution, "type"),
                localId == null || localId.isBlank() ? localIdFromUri(uri) : localId,
                value(solution, "source")
        );
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

    private record LoadResult(
            Model model,
            List<String> loadedFiles,
            List<String> missingFiles
    ) {
    }
}
