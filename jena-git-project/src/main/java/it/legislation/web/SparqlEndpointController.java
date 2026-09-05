package it.legislation.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryException;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A public, read-only SPARQL endpoint (FR-3.1, FR-3.2, US-B1).
 *
 * <p>Follows the SPARQL 1.1 Protocol closely enough that off-the-shelf clients
 * work against it without special handling:
 *
 * <pre>
 * GET  /sparql?query={urlencoded}
 * POST /sparql   Content-Type: application/x-www-form-urlencoded   query=...
 * POST /sparql   Content-Type: application/sparql-query            {query}
 * </pre>
 *
 * <p>All four query forms are supported. {@code SELECT} and {@code ASK} return
 * SPARQL Results (JSON, XML or CSV); {@code CONSTRUCT} and {@code DESCRIBE}
 * return RDF (Turtle, RDF/XML, N-Triples or JSON-LD). The response format is
 * chosen from the {@code Accept} header, with an {@code ?output=} override for
 * browser use.
 *
 * <p>The endpoint is read-only by construction: {@link QueryFactory} parses
 * queries only, so a SPARQL Update request fails to parse and is rejected with
 * 400 rather than being executed.
 */
@RestController
public class SparqlEndpointController {

    private static final String RESULTS_JSON = "application/sparql-results+json";
    private static final String RESULTS_XML = "application/sparql-results+xml";
    private static final String RESULTS_CSV = "text/csv";
    private static final String TURTLE = "text/turtle";
    private static final String RDF_XML = "application/rdf+xml";
    private static final String NTRIPLES = "application/n-triples";
    private static final String JSON_LD = "application/ld+json";

    private final Tdb2DatasetService datasetService;
    private final int timeoutSeconds;

    public SparqlEndpointController(
            Tdb2DatasetService datasetService,
            @Value("${legal.sparql.timeout-seconds:30}") int timeoutSeconds
    ) {
        this.datasetService = datasetService;
        this.timeoutSeconds = timeoutSeconds;
    }

    @GetMapping(value = "/sparql", params = "query")
    public ResponseEntity<byte[]> queryViaGet(
            @RequestParam("query") String query,
            @RequestParam(name = "output", required = false) String output,
            @RequestHeader(name = HttpHeaders.ACCEPT, required = false) String accept
    ) throws IOException {
        return execute(query, output, accept);
    }

    @PostMapping(value = "/sparql", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<byte[]> queryViaForm(
            @RequestParam("query") String query,
            @RequestParam(name = "output", required = false) String output,
            @RequestHeader(name = HttpHeaders.ACCEPT, required = false) String accept
    ) throws IOException {
        return execute(query, output, accept);
    }

    @PostMapping(value = "/sparql", consumes = "application/sparql-query")
    public ResponseEntity<byte[]> queryViaBody(
            @RequestBody String query,
            @RequestParam(name = "output", required = false) String output,
            @RequestHeader(name = HttpHeaders.ACCEPT, required = false) String accept
    ) throws IOException {
        return execute(query, output, accept);
    }

    /**
     * A bare GET with no query returns a short service description, so that
     * opening the endpoint in a browser explains itself instead of erroring.
     */
    @GetMapping(value = "/sparql")
    public ResponseEntity<byte[]> serviceDescription() {
        String body = """
                Italian Legislation Linked Data Platform - SPARQL endpoint (read-only)

                  GET  /sparql?query={urlencoded SPARQL}
                  POST /sparql   application/x-www-form-urlencoded   query=...
                  POST /sparql   application/sparql-query            {query}

                Query forms: SELECT, ASK, CONSTRUCT, DESCRIBE.

                Response formats, by Accept header (or ?output=):
                  SELECT / ASK           application/sparql-results+json  (json)
                                         application/sparql-results+xml   (xml)
                                         text/csv                         (csv)
                  CONSTRUCT / DESCRIBE   text/turtle                      (turtle)
                                         application/rdf+xml              (rdfxml)
                                         application/n-triples            (ntriples)
                                         application/ld+json              (jsonld)

                Example:
                  curl -H 'Accept: application/sparql-results+json' \\
                    --data-urlencode 'query=SELECT (COUNT(*) AS ?n) WHERE { ?s ?p ?o }' \\
                    /sparql

                Updates are not accepted: this endpoint parses queries only.
                """;
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<byte[]> execute(String queryText, String output, String accept) throws IOException {
        if (queryText == null || queryText.isBlank()) {
            return problem(HttpStatus.BAD_REQUEST, "No query supplied. Use ?query= or a request body.");
        }

        Query query;
        try {
            query = QueryFactory.create(queryText);
        } catch (QueryException exception) {
            // Also the path a SPARQL Update takes: it is not a query, so it
            // never reaches the store.
            return problem(HttpStatus.BAD_REQUEST, "Malformed query: " + exception.getMessage());
        }

        String format = chooseFormat(query, output, accept);

        try {
            Rendered rendered = datasetService.read(dataset -> {
                try (QueryExecution execution = QueryExecutionFactory.create(query, dataset)) {
                    execution.setTimeout(timeoutSeconds, TimeUnit.SECONDS);
                    return render(execution, query, format);
                }
            });
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, rendered.contentType())
                    .header(HttpHeaders.VARY, HttpHeaders.ACCEPT)
                    .body(rendered.body());
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            return problem(HttpStatus.BAD_REQUEST, "Query failed: " + message);
        }
    }

    /**
     * Serialises inside the read transaction. A {@link ResultSet} is lazy over
     * the store, so it must be consumed before the transaction ends.
     */
    private Rendered render(QueryExecution execution, Query query, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        if (query.isSelectType()) {
            ResultSet results = execution.execSelect();
            writeResults(out, results, format);
            return new Rendered(out.toByteArray(), resultsContentType(format));
        }
        if (query.isAskType()) {
            boolean answer = execution.execAsk();
            writeAsk(out, answer, format);
            return new Rendered(out.toByteArray(), resultsContentType(format));
        }
        Model model = query.isConstructType() ? execution.execConstruct() : execution.execDescribe();
        RDFDataMgr.write(out, model, rdfFormat(format));
        return new Rendered(out.toByteArray(), rdfContentType(format));
    }

    private void writeResults(OutputStream out, ResultSet results, String format) {
        switch (format) {
            case "xml" -> ResultSetFormatter.outputAsXML(out, results);
            case "csv" -> ResultSetFormatter.outputAsCSV(out, results);
            default -> ResultSetFormatter.outputAsJSON(out, results);
        }
    }

    private void writeAsk(OutputStream out, boolean answer, String format) {
        switch (format) {
            case "xml" -> ResultSetFormatter.outputAsXML(out, answer);
            case "csv" -> writeAskAsCsv(out, answer);
            default -> ResultSetFormatter.outputAsJSON(out, answer);
        }
    }

    private void writeAskAsCsv(OutputStream out, boolean answer) {
        try {
            out.write(("_askResult\n" + answer + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write ASK result", exception);
        }
    }

    /**
     * Picks a serialisation. An explicit {@code ?output=} wins so the endpoint is
     * usable from a browser address bar; otherwise the Accept header decides, and
     * each query form falls back to its own sensible default.
     */
    static String chooseFormat(Query query, String output, String accept) {
        boolean graphResult = query.isConstructType() || query.isDescribeType();

        if (output != null && !output.isBlank()) {
            String requested = output.trim().toLowerCase(Locale.ROOT);
            List<String> known = graphResult
                    ? List.of("turtle", "ttl", "rdfxml", "ntriples", "nt", "jsonld")
                    : List.of("json", "xml", "csv");
            if (known.contains(requested)) {
                return normalizeAlias(requested);
            }
        }

        String header = accept == null ? "" : accept.toLowerCase(Locale.ROOT);
        if (graphResult) {
            if (header.contains(RDF_XML)) {
                return "rdfxml";
            }
            if (header.contains(NTRIPLES)) {
                return "ntriples";
            }
            if (header.contains(JSON_LD)) {
                return "jsonld";
            }
            return "turtle";
        }
        if (header.contains(RESULTS_XML)) {
            return "xml";
        }
        if (header.contains(RESULTS_CSV)) {
            return "csv";
        }
        return "json";
    }

    private static String normalizeAlias(String requested) {
        return switch (requested) {
            case "ttl" -> "turtle";
            case "nt" -> "ntriples";
            default -> requested;
        };
    }

    private static String resultsContentType(String format) {
        return switch (format) {
            case "xml" -> RESULTS_XML + ";charset=UTF-8";
            case "csv" -> RESULTS_CSV + ";charset=UTF-8";
            default -> RESULTS_JSON + ";charset=UTF-8";
        };
    }

    private static String rdfContentType(String format) {
        return switch (format) {
            case "rdfxml" -> RDF_XML + ";charset=UTF-8";
            case "ntriples" -> NTRIPLES + ";charset=UTF-8";
            case "jsonld" -> JSON_LD + ";charset=UTF-8";
            default -> TURTLE + ";charset=UTF-8";
        };
    }

    private static RDFFormat rdfFormat(String format) {
        return switch (format) {
            case "rdfxml" -> RDFFormat.RDFXML_PRETTY;
            case "ntriples" -> RDFFormat.NTRIPLES;
            case "jsonld" -> RDFFormat.JSONLD;
            default -> RDFFormat.TURTLE_PRETTY;
        };
    }

    private ResponseEntity<byte[]> problem(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_PLAIN)
                .body((message + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private record Rendered(byte[] body, String contentType) {
    }
}
