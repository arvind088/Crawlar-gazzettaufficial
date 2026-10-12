package it.legislation.web;

import static it.legislation.web.Html.escape;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.legislation.eli.EliUriService;
import it.legislation.store.Tdb2DatasetService;

/**
 * The SPARQL view ({@code /query}): a form, the example queries from
 * {@code queries/*.rq}, and the results as a table. Machines use the protocol
 * endpoint {@code /sparql}; this page links to its JSON and CSV output.
 */
@RestController
public class SparqlPage {

    static final int MAX_ROWS = 1000;
    static final String DEFAULT_QUERY = ActCatalog.PREFIXES + """

            # Decree-laws and their current conversion status
            SELECT ?decree ?status ?on WHERE {
              ?check a ilg:ConversionCheck ; ilg:assesses ?decree ;
                     ilg:conversionStatus ?status ; ilg:assessedOn ?on .
            }
            ORDER BY DESC(?on) ?status
            LIMIT 20
            """;

    private final Tdb2DatasetService store;
    private final EliUriService uris;
    private final Path examples;
    private final int timeoutSeconds;

    public SparqlPage(Tdb2DatasetService store, EliUriService uris,
                      @Value("${legal.sparql.examples:queries}") String examples,
                      @Value("${legal.sparql.timeout-seconds:30}") int timeoutSeconds) {
        this.store = store;
        this.uris = uris;
        this.examples = Path.of(examples);
        this.timeoutSeconds = timeoutSeconds;
    }

    @GetMapping("/query")
    public ResponseEntity<String> page(@RequestParam(name = "query", required = false) String query,
                                       @RequestParam(name = "example", required = false) String example) {
        Map<String, String> exampleQueries = examples();
        String text = query;
        if ((text == null || text.isBlank()) && example != null) {
            text = exampleQueries.get(example);
        }
        boolean run = text != null && !text.isBlank();
        if (!run) {
            text = DEFAULT_QUERY;
        }

        StringBuilder body = new StringBuilder("<h1>SPARQL</h1>\n<p class=\"muted\">Ask the triple store directly. "
                + "Read-only; queries stop after " + timeoutSeconds + " seconds. Programs can use the endpoint "
                + "<span class=\"mono\">/sparql</span> (SPARQL 1.1 Protocol).</p>\n");
        if (!exampleQueries.isEmpty()) {
            body.append("<form class=\"filters card\" method=\"get\" action=\"/query\"><label>Example queries<select name=\"example\">");
            exampleQueries.keySet().forEach(name -> body.append("<option value=\"").append(escape(name)).append("\"")
                    .append(name.equals(example) ? " selected" : "").append(">").append(escape(name)).append("</option>"));
            body.append("</select></label><button type=\"submit\">Run example</button></form>\n");
        }
        body.append("<form class=\"card\" method=\"get\" action=\"/query\"><textarea name=\"query\" spellcheck=\"false\" "
                + "aria-label=\"SPARQL query\">").append(escape(text))
                .append("</textarea><p><button type=\"submit\">Run query</button></p></form>\n");
        if (run) {
            body.append(results(text));
        }
        return ResponseEntity.ok().contentType(ActPages.HTML).body(Html.page("SPARQL", "sparql", null, body.toString()));
    }

    /** {@code queries/*.rq}, by file name. */
    Map<String, String> examples() {
        Map<String, String> found = new LinkedHashMap<>();
        if (!Files.isDirectory(examples)) {
            return found;
        }
        try (Stream<Path> files = Files.list(examples)) {
            files.filter(f -> f.getFileName().toString().endsWith(".rq")).sorted().forEach(f -> {
                try {
                    found.put(f.getFileName().toString().replaceFirst("\\.rq$", ""), Files.readString(f));
                } catch (IOException ignored) {
                    // an unreadable example is left out
                }
            });
        } catch (IOException ignored) {
            // no examples
        }
        return found;
    }

    private String results(String text) {
        Query query;
        try {
            query = QueryFactory.create(text);
        } catch (RuntimeException e) {
            return "<p class=\"error\">The query is not valid SPARQL: " + escape(e.getMessage()) + "</p>\n";
        }
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
        try {
            return store.read(dataset -> {
                try (QueryExecution execution = QueryExecutionFactory.create(query, dataset)) {
                    execution.setTimeout(timeoutSeconds, TimeUnit.SECONDS);
                    if (query.isSelectType()) {
                        return table(execution.execSelect(), encoded);
                    }
                    if (query.isAskType()) {
                        return "<div class=\"card\"><b>" + (execution.execAsk() ? "true" : "false") + "</b></div>\n";
                    }
                    Model model = query.isConstructType() ? execution.execConstruct() : execution.execDescribe();
                    StringWriter turtle = new StringWriter();
                    RDFDataMgr.write(turtle, model, RDFFormat.TURTLE_PRETTY);
                    return "<p class=\"muted\">" + model.size() + " triples. <a href=\"/sparql?output=turtle&query="
                            + escape(encoded) + "\">Download Turtle</a></p><pre>" + escape(turtle.toString()) + "</pre>\n";
                }
            });
        } catch (IOException | RuntimeException e) {
            return "<p class=\"error\">The query failed: " + escape(e.getMessage()) + "</p>\n";
        }
    }

    private String table(ResultSet results, String encoded) {
        List<String> vars = results.getResultVars();
        List<QuerySolution> rows = new ArrayList<>();
        boolean more = false;
        while (results.hasNext()) {
            if (rows.size() == MAX_ROWS) {
                more = true;
                break;
            }
            rows.add(results.next());
        }
        StringBuilder html = new StringBuilder("<p class=\"muted\">").append(rows.size()).append(more ? "+" : "")
                .append(rows.size() == 1 ? " row" : " rows").append(more ? " (first " + MAX_ROWS + " shown)" : "")
                .append(". Download: <a href=\"/sparql?output=csv&query=").append(escape(encoded))
                .append("\">CSV</a> &middot; <a href=\"/sparql?output=json&query=").append(escape(encoded))
                .append("\">JSON</a></p>\n<div class=\"table-wrap\"><table><thead><tr>");
        vars.forEach(v -> html.append("<th>").append(escape(v)).append("</th>"));
        html.append("</tr></thead><tbody>");
        for (QuerySolution row : rows) {
            html.append("<tr>");
            for (String v : vars) {
                html.append("<td>").append(cell(row.get(v))).append("</td>");
            }
            html.append("</tr>");
        }
        return html.append("</tbody></table></div>\n").toString();
    }

    /** IRIs we host link to their page; other IRIs link out; literals are text. */
    private String cell(RDFNode node) {
        if (node == null) {
            return "";
        }
        if (node.isURIResource()) {
            String uri = node.asResource().getURI();
            // Our own resources show their path, which is short and works on any host.
            String href = uris.pathOf(uri).orElse(uri);
            return "<a class=\"mono nowrap\" title=\"" + escape(uri) + "\" href=\"" + escape(href) + "\">"
                    + escape(href) + "</a>";
        }
        if (node.isLiteral()) {
            return escape(node.asLiteral().getLexicalForm());
        }
        return "<span class=\"mono\">" + escape(node.toString()) + "</span>";
    }
}
