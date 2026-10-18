package it.legislation.web;

import static it.legislation.web.Html.escape;
import static it.legislation.web.I18n.t;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.jena.query.Dataset;
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
 * The SPARQL view ({@code /query}): the example questions from
 * {@code queries/*.rq} as a list, a form, and the results as a table. Machines
 * use the protocol endpoint {@code /sparql}; this page links to its JSON and
 * CSV output.
 *
 * <p>Result cells are made readable: an act shows its citation ("D.L. 17 marzo
 * 2020, n. 18") and links to its page, a document type shows its name
 * ("Decreto-legge"), and vocabulary terms show their short name
 * ({@code eli:amends}). The full IRI stays in the tooltip and in the downloads.
 */
@RestController
public class SparqlPage {

    static final int MAX_ROWS = 1000;
    static final String DEFAULT_QUERY = ActCatalog.PREFIXES + """

            # The 20 most recent acts
            SELECT ?act ?type ?date ?title WHERE {
              ?act a eli:LegalResource ; dcterms:source <https://dati.normattiva.it/> ;
                   eli:type_document ?type ; eli:date_document ?date ; eli:title ?title .
            }
            ORDER BY DESC(?date)
            LIMIT 20
            """;

    /**
     * One example: file name, title and description in English and Italian (from its
     * leading comments; Italian lines start with "it:"), and the query.
     */
    record Example(String name, String titleEn, String descriptionEn, String titleIt, String descriptionIt,
                   String query) {
        /** The title in the current language (English when there is no Italian one). */
        String title() {
            return I18n.italian() && !titleIt.isEmpty() ? titleIt : titleEn;
        }

        String description() {
            return I18n.italian() && !descriptionIt.isEmpty() ? descriptionIt : descriptionEn;
        }
    }

    private static final Pattern SAFE_IRI = Pattern.compile("[^<>\"\\s{}|\\\\^`]+");

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
        Map<String, Example> exampleQueries = examples();
        Example chosen = example == null ? null : exampleQueries.get(example);
        String text = query;
        if ((text == null || text.isBlank()) && chosen != null) {
            text = chosen.query();
        }
        boolean run = text != null && !text.isBlank();
        if (!run) {
            chosen = exampleQueries.values().stream().findFirst().orElse(null);
            text = chosen == null ? DEFAULT_QUERY : chosen.query();
        } else if (chosen == null) {
            String typed = text;
            chosen = exampleQueries.values().stream().filter(e -> e.query().strip().equals(typed.strip()))
                    .findFirst().orElse(null);
        }

        StringBuilder body = new StringBuilder("<div><h1>" + escape(t("sparql.title")) + "</h1><p class=\"muted\">"
                + t("sparql.intro", timeoutSeconds, "<span class=\"mono\">/sparql</span>")
                + "</p></div>\n<div class=\"split\">\n");
        if (!exampleQueries.isEmpty()) {
            String current = chosen == null ? null : chosen.name();
            body.append("<aside class=\"side\"><section class=\"card\"><h2>").append(escape(t("sparql.questions")))
                    .append("</h2><nav class=\"examples\" aria-label=\"").append(escape(t("sparql.questions.aria")))
                    .append("\">");
            int n = 1;
            for (Example e : exampleQueries.values()) {
                body.append("<a href=\"/query?example=").append(escape(URLEncoder.encode(e.name(), StandardCharsets.UTF_8)))
                        .append("\"").append(e.name().equals(current) ? " aria-current=\"page\"" : "").append(">")
                        .append(n++).append(" &middot; ").append(escape(e.title())).append("</a>");
            }
            body.append("</nav></section></aside>\n");
        }
        body.append("<div class=\"mainc\"><form class=\"card\" method=\"get\" action=\"/query\">")
                .append("<h2><label for=\"query\">").append(escape(chosen == null ? t("sparql.yours") : chosen.title()))
                .append("</label></h2>")
                .append(chosen == null || chosen.description().isEmpty() ? ""
                        : "<p class=\"muted\">" + escape(chosen.description()) + "</p>")
                .append("<textarea id=\"query\" name=\"query\" spellcheck=\"false\">").append(escape(text))
                .append("</textarea><div class=\"actions\"><button type=\"submit\">").append(escape(t("sparql.run")))
                .append("</button><a class=\"button secondary\" href=\"/query\">").append(escape(t("sparql.reset")))
                .append("</a><span class=\"muted\">").append(escape(t("sparql.limit", timeoutSeconds))).append("</span>")
                .append("</div></form>\n");
        if (run) {
            body.append(results(text));
        }
        body.append("</div>\n</div>\n");
        return ResponseEntity.ok().contentType(ActPages.HTML).body(Html.page(t("nav.sparql"), "sparql", null, body.toString()));
    }

    /**
     * {@code queries/*.rq}, by file name. The first comment line is the title,
     * the following comment lines the description.
     */
    Map<String, Example> examples() {
        Map<String, Example> found = new LinkedHashMap<>();
        if (!Files.isDirectory(examples)) {
            return found;
        }
        try (Stream<Path> files = Files.list(examples)) {
            files.filter(f -> f.getFileName().toString().endsWith(".rq")).sorted().forEach(f -> {
                try {
                    String name = f.getFileName().toString().replaceFirst("\\.rq$", "");
                    String query = Files.readString(f);
                    List<String> english = new ArrayList<>();
                    List<String> italian = new ArrayList<>();
                    for (String line : query.split("\\R")) {
                        if (!line.startsWith("#")) {
                            break;
                        }
                        String text = line.replaceFirst("^#+\\s?", "").trim();
                        if (text.startsWith("it:")) {
                            italian.add(text.substring(3).trim());
                        } else {
                            english.add(text);
                        }
                    }
                    found.put(name, new Example(name, title(english, name), description(english),
                            title(italian, ""), description(italian), query));
                } catch (IOException ignored) {
                    // an unreadable example is left out
                }
            });
        } catch (IOException ignored) {
            // no examples
        }
        return found;
    }

    private static String title(List<String> lines, String fallback) {
        return lines.isEmpty() ? fallback : lines.get(0).replaceFirst("\\.$", "");
    }

    private static String description(List<String> lines) {
        return lines.size() < 2 ? "" : String.join(" ", lines.subList(1, lines.size()));
    }

    private String results(String text) {
        Query query;
        try {
            query = QueryFactory.create(text);
        } catch (RuntimeException e) {
            return "<p class=\"error\">" + escape(t("sparql.invalid")) + " " + escape(e.getMessage()) + "</p>\n";
        }
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
        try {
            return store.read(dataset -> {
                try (QueryExecution execution = QueryExecutionFactory.create(query, dataset)) {
                    execution.setTimeout(timeoutSeconds, TimeUnit.SECONDS);
                    if (query.isSelectType()) {
                        return table(dataset, execution.execSelect(), encoded);
                    }
                    if (query.isAskType()) {
                        return "<div class=\"card\"><b>" + (execution.execAsk() ? "true" : "false") + "</b></div>\n";
                    }
                    Model model = query.isConstructType() ? execution.execConstruct() : execution.execDescribe();
                    StringWriter turtle = new StringWriter();
                    RDFDataMgr.write(turtle, model, RDFFormat.TURTLE_PRETTY);
                    return "<section class=\"card\"><p class=\"muted\">" + escape(t("sparql.triples", model.size()))
                            + " <a href=\"/sparql?output=turtle&amp;query=" + escape(encoded)
                            + "\">" + escape(t("sparql.turtle")) + "</a></p><pre>" + escape(turtle.toString())
                            + "</pre></section>\n";
                }
            });
        } catch (IOException | RuntimeException e) {
            return "<p class=\"error\">" + escape(t("sparql.failed")) + " " + escape(e.getMessage()) + "</p>\n";
        }
    }

    private String table(Dataset dataset, ResultSet results, String encoded) {
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
        Set<String> iris = new LinkedHashSet<>();
        for (QuerySolution row : rows) {
            for (String v : vars) {
                RDFNode node = row.get(v);
                if (node != null && node.isURIResource()) {
                    iris.add(node.asResource().getURI());
                }
            }
        }
        Map<String, String> citations = citations(dataset, iris);

        StringBuilder html = new StringBuilder("<section class=\"card\"><div class=\"results-head\"><h2>")
                .append(escape(t("sparql.result"))).append(" <span class=\"muted\">&middot; ")
                .append(escape(I18n.count("sparql.rows", rows.size(), I18n.number(rows.size()) + (more ? "+" : ""))))
                .append(more ? " " + escape(t("sparql.first", I18n.number(MAX_ROWS))) : "")
                .append("</span></h2><span class=\"muted\">").append(escape(t("sparql.download")))
                .append(" <a href=\"/sparql?output=csv&amp;query=")
                .append(escape(encoded)).append("\">CSV</a> &middot; <a href=\"/sparql?output=json&amp;query=")
                .append(escape(encoded)).append("\">JSON</a></span></div>\n<div class=\"table-wrap\"><table><thead><tr>");
        vars.forEach(v -> html.append("<th>").append(escape(v)).append("</th>"));
        html.append("</tr></thead><tbody>");
        for (QuerySolution row : rows) {
            html.append("<tr>");
            for (String v : vars) {
                html.append("<td>").append(cell(row.get(v), citations)).append("</td>");
            }
            html.append("</tr>");
        }
        return html.append("</tbody></table></div></section>\n").toString();
    }

    /** Citations ("D.L. 17 marzo 2020, n. 18") for the acts among the result IRIs. */
    private static Map<String, String> citations(Dataset dataset, Set<String> iris) {
        Map<String, String> found = new HashMap<>();
        List<String> safe = iris.stream().filter(i -> SAFE_IRI.matcher(i).matches()).limit(5000).toList();
        if (safe.isEmpty()) {
            return found;
        }
        StringBuilder query = new StringBuilder(ActCatalog.PREFIXES)
                .append("SELECT ?s (SAMPLE(?t) AS ?type) (SAMPLE(?d) AS ?date) (SAMPLE(?n) AS ?number) WHERE { VALUES ?s {");
        safe.forEach(i -> query.append(" <").append(i).append('>'));
        query.append(" } ?s eli:type_document ?t . OPTIONAL { ?s eli:date_document ?d } OPTIONAL { ?s eli:number ?n } } "
                + "GROUP BY ?s");
        try (QueryExecution execution = QueryExecutionFactory.create(query.toString(), dataset)) {
            ResultSet rs = execution.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                String type = Html.typeCode(s.getResource("type").getURI());
                String date = lexical(s.get("date"));
                String number = lexical(s.get("number"));
                found.put(s.getResource("s").getURI(), Html.citation(type, date, number));
            }
        } catch (RuntimeException ignored) {
            // labels are a convenience: without them the cells show paths
        }
        return found;
    }

    private static String lexical(RDFNode node) {
        if (node == null) {
            return null;
        }
        return node.isLiteral() ? node.asLiteral().getLexicalForm() : node.toString();
    }

    /**
     * Acts show their citation and link to their page; document types show their
     * name; vocabulary terms their short name; other IRIs we host their path;
     * literals their text. The full IRI is in the tooltip.
     */
    private String cell(RDFNode node, Map<String, String> citations) {
        if (node == null) {
            return "";
        }
        if (node.isLiteral()) {
            return escape(node.asLiteral().getLexicalForm());
        }
        if (!node.isURIResource()) {
            return "<span class=\"mono\">" + escape(node.toString()) + "</span>";
        }
        String uri = node.asResource().getURI();
        Optional<String> path = uris.pathOf(uri);
        String citation = citations.get(uri);
        if (citation != null) {
            return path.map(p -> "<a class=\"nowrap\" title=\"" + escape(uri) + "\" href=\"" + escape(p) + "\"><b>"
                    + escape(citation) + "</b></a>")
                    .orElse("<span class=\"nowrap\" title=\"" + escape(uri) + "\">" + escape(citation) + "</span>");
        }
        if (uri.contains("/resource-type#")) {
            return "<span title=\"" + escape(uri) + "\">" + escape(Html.typeName(Html.typeCode(uri))) + "</span>";
        }
        if (uri.startsWith(ActCatalog.ELI)) {
            return "<span class=\"mono\" title=\"" + escape(uri) + "\">eli:" + escape(uri.substring(ActCatalog.ELI.length()))
                    + "</span>";
        }
        if (uri.startsWith(ActCatalog.ILG)) {
            return "<span class=\"mono\" title=\"" + escape(uri) + "\">ilg:" + escape(uri.substring(ActCatalog.ILG.length()))
                    + "</span>";
        }
        String href = path.orElse(uri);
        return "<a class=\"mono\" title=\"" + escape(uri) + "\" href=\"" + escape(href) + "\""
                + (path.isPresent() ? "" : " rel=\"noreferrer\"") + ">" + escape(href) + "</a>";
    }
}
