package it.legislation.crawler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;

import it.legislation.crawler.InForceRdfBuilder.ExpressionStatus;

/**
 * Derives in-force triples for every Expression already in the dataset and
 * writes them to their own Turtle file (FR-4.5, US-A1, US-A3).
 *
 * <p>Kept as a separate file rather than edited into the existing ones so the
 * derivation is auditable and reversible: delete {@code data/rdf/in_force.ttl}
 * and the enrichment is gone. It is additive with respect to everything else.
 *
 * <pre>
 * mvn -B "-Dexec.mainClass=it.legislation.crawler.InForceEnrichmentRunner" exec:java
 * </pre>
 */
public final class InForceEnrichmentRunner {

    private static final List<Path> DEFAULT_INPUTS = List.of(
            Path.of("data", "rdf", "gazzetta_metadata_delta.ttl"),
            Path.of("data", "rdf", "normattiva_modifications.ttl"),
            Path.of("data", "rdf", "normattiva_modifications_auto.ttl"),
            Path.of("data", "rdf", "normattiva_multiversion_sample.ttl")
    );

    private static final Path DEFAULT_OUTPUT = Path.of("data", "rdf", "in_force.ttl");

    private static final String EXPRESSIONS_QUERY = """
            PREFIX eli: <http://data.europa.eu/eli/ontology#>

            SELECT DISTINCT ?work ?expression
                   (COALESCE(?expressionVersion, ?workVersion) AS ?version)
            WHERE {
              ?work eli:is_realized_by ?expression .
              OPTIONAL { ?expression eli:version ?expressionVersion . }
              OPTIONAL { ?work eli:version ?workVersion . }
            }
            ORDER BY ?work ?expression
            """;

    private InForceEnrichmentRunner() {
    }

    public static void main(String[] args) throws IOException {
        List<Path> inputs = new ArrayList<>();
        Path output = DEFAULT_OUTPUT;

        for (String argument : args) {
            if (!argument.startsWith("--")) {
                inputs.add(Path.of(argument));
            }
        }
        if (inputs.isEmpty()) {
            inputs.addAll(DEFAULT_INPUTS);
        }

        Model source = ModelFactory.createDefaultModel();
        int read = 0;
        for (Path input : inputs) {
            if (!Files.exists(input)) {
                System.out.println("Skipped (missing): " + input);
                continue;
            }
            try (InputStream inputStream = Files.newInputStream(input)) {
                RDFDataMgr.read(source, inputStream, Lang.TURTLE);
            }
            read++;
        }

        Result result = derive(source);

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream outputStream = Files.newOutputStream(output)) {
            RDFDataMgr.write(outputStream, result.model(), RDFFormat.TURTLE_PRETTY);
        }

        System.out.println("Files read: " + read);
        System.out.println("Expressions found: " + result.expressions());
        System.out.println("Marked in force: " + result.inForce());
        System.out.println("Marked no longer in force: " + result.notInForce());
        System.out.println("Output: " + output.toAbsolutePath().normalize());
    }

    /**
     * The Gazzetta crawler records {@code eli:version} on the Work rather than on
     * the Expression, so both positions are read.
     *
     * <p>An Expression is superseded only if a sibling Expression of the same Work
     * carries a current-text version. A Work with a single Expression is treated
     * as in force, which matches Gazzetta's "testo storico" being the only text
     * that exists for most acts.
     */
    static Result derive(Model source) {
        Map<String, List<String[]>> byWork = new LinkedHashMap<>();

        try (QueryExecution execution =
                     QueryExecutionFactory.create(QueryFactory.create(EXPRESSIONS_QUERY), source)) {
            ResultSet results = execution.execSelect();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                String work = text(solution, "work");
                String expression = text(solution, "expression");
                String version = text(solution, "version");
                if (work == null || expression == null) {
                    continue;
                }
                byWork.computeIfAbsent(work, key -> new ArrayList<>())
                        .add(new String[]{expression, version});
            }
        }

        InForceRdfBuilder builder = new InForceRdfBuilder();
        Model output = builder.build(List.of());
        Set<String> seen = new LinkedHashSet<>();
        int inForce = 0;
        int notInForce = 0;

        for (Map.Entry<String, List<String[]>> entry : byWork.entrySet()) {
            List<String[]> expressions = entry.getValue();
            boolean hasCurrentText = expressions.stream()
                    .anyMatch(pair -> pair[1] != null && pair[1].toUpperCase().contains("VIGENZA"));

            for (String[] pair : expressions) {
                if (!seen.add(pair[0])) {
                    continue;
                }
                ExpressionStatus status =
                        InForceRdfBuilder.fromVersionToken(pair[0], pair[1], hasCurrentText);
                builder.add(output, status);
                if (status.isInForce()) {
                    inForce++;
                } else {
                    notInForce++;
                }
            }
        }

        return new Result(output, seen.size(), inForce, notInForce);
    }

    private static String text(QuerySolution solution, String name) {
        if (!solution.contains(name)) {
            return null;
        }
        var node = solution.get(name);
        if (node == null) {
            return null;
        }
        return node.isLiteral() ? node.asLiteral().getLexicalForm() : node.toString();
    }

    record Result(Model model, int expressions, int inForce, int notInForce) {
    }
}
