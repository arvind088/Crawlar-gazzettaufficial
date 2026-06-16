package it.legislation.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.springframework.stereotype.Service;

import it.legislation.crawler.RdfModelBuilder;

@Service
public class NormattivaQueryService {

    private static final Path NORMATTIVA_MODIFICATIONS = Path.of("data", "rdf", "normattiva_modifications.ttl");

    private final Path modificationsPath;

    public NormattivaQueryService() {
        this(NORMATTIVA_MODIFICATIONS);
    }

    NormattivaQueryService(Path modificationsPath) {
        this.modificationsPath = modificationsPath;
    }

    public List<NormattivaModificationSummary> listModifications(int limit) throws IOException {
        if (!Files.exists(modificationsPath)) {
            return List.of();
        }

        Model model = loadModel();
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String queryText = """
                PREFIX eli: <%s>
                PREFIX ilg: <%s>

                SELECT ?source ?target ?isConversion
                WHERE {
                  ?source ilg:modifies ?target .
                  BIND(EXISTS { ?source eli:commences ?target } AS ?isConversion)
                }
                ORDER BY ?source ?target
                LIMIT %d
                """.formatted(RdfModelBuilder.ELI_NS, RdfModelBuilder.PROJECT_NS, safeLimit);

        List<NormattivaModificationSummary> rows = new ArrayList<>();
        try (QueryExecution execution = QueryExecutionFactory.create(queryText, model)) {
            ResultSet resultSet = execution.execSelect();
            while (resultSet.hasNext()) {
                rows.add(toSummary(resultSet.nextSolution()));
            }
        }
        return rows;
    }

    private Model loadModel() throws IOException {
        Model model = ModelFactory.createDefaultModel();
        try (InputStream inputStream = Files.newInputStream(modificationsPath)) {
            RDFDataMgr.read(model, inputStream, Lang.TURTLE);
        }
        return model;
    }

    private NormattivaModificationSummary toSummary(QuerySolution solution) {
        String sourceUri = solution.getResource("source").getURI();
        String targetUri = solution.getResource("target").getURI();
        boolean conversion = solution.getLiteral("isConversion").getBoolean();
        return new NormattivaModificationSummary(
                sourceUri,
                localId(sourceUri),
                conversion ? "conversion" : "modifies",
                targetUri,
                localId(targetUri)
        );
    }

    private String localId(String uri) {
        String[] parts = uri.split("/");
        if (parts.length >= 2 && "sg".equalsIgnoreCase(parts[parts.length - 1])) {
            return parts[parts.length - 2];
        }
        return parts.length == 0 ? uri : parts[parts.length - 1];
    }
}
