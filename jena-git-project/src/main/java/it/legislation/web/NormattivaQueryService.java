package it.legislation.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import it.legislation.crawler.RdfModelBuilder;

@Service
public class NormattivaQueryService {

    private final Tdb2DatasetService datasetService;

    @Autowired
    public NormattivaQueryService(Tdb2DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    NormattivaQueryService(Path modificationsPath) throws IOException {
        this(List.of(modificationsPath));
    }

    NormattivaQueryService(List<Path> modificationPaths) throws IOException {
        this(Tdb2DatasetService.inMemoryForRdfPaths(modificationPaths));
    }

    NormattivaQueryService(Path datasetPath, List<Path> modificationPaths) throws IOException {
        this(Tdb2DatasetService.forRdfPaths(datasetPath, modificationPaths));
    }

    public List<NormattivaModificationSummary> listModifications(int limit) throws IOException {
        if (datasetService.status().loadedFiles().isEmpty()) {
            return List.of();
        }

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

        return datasetService.read(dataset -> {
            List<NormattivaModificationSummary> rows = new ArrayList<>();
            try (QueryExecution execution = QueryExecutionFactory.create(queryText, dataset)) {
                ResultSet resultSet = execution.execSelect();
                while (resultSet.hasNext()) {
                    rows.add(toSummary(resultSet.nextSolution()));
                }
            }
            return rows;
        });
    }

    void closeForTests() {
        datasetService.close();
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
