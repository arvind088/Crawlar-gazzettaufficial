package it.legislation.crawler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

public class SampleRdfQueryRunner {

    private static final String DEFAULT_TTL_PATH = "../context/LEGGE_35_2020_DL_19_2020.ttl";
    private static final String DEFAULT_QUERY_DIR = "queries";

    public static void main(String[] args) throws IOException {
        Path ttlPath = args.length > 0 ? Paths.get(args[0]) : Paths.get(DEFAULT_TTL_PATH);
        Path queryDir = args.length > 1 ? Paths.get(args[1]) : Paths.get(DEFAULT_QUERY_DIR);

        Model model = ModelFactory.createDefaultModel();
        try (InputStream inputStream = Files.newInputStream(ttlPath)) {
            RDFDataMgr.read(model, inputStream, Lang.TURTLE);
        }

        System.out.println("Loaded RDF file: " + ttlPath.toAbsolutePath().normalize());
        System.out.println("Total triples: " + model.size());
        System.out.println();

        for (Path queryPath : findQueryFiles(queryDir)) {
            runQuery(model, queryPath);
        }
    }

    private static List<Path> findQueryFiles(Path queryDir) throws IOException {
        List<Path> queryFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(queryDir, "*.rq")) {
            for (Path path : stream) {
                queryFiles.add(path);
            }
        }
        Collections.sort(queryFiles);
        return queryFiles;
    }

    private static void runQuery(Model model, Path queryPath) throws IOException {
        String queryText = new String(Files.readAllBytes(queryPath), StandardCharsets.UTF_8);
        Query query = QueryFactory.create(queryText);

        System.out.println("============================================================");
        System.out.println("Query: " + queryPath.getFileName());
        System.out.println("============================================================");

        try (QueryExecution queryExecution = QueryExecutionFactory.create(query, model)) {
            if (query.isSelectType()) {
                ResultSet results = queryExecution.execSelect();
                ResultSetFormatter.out(System.out, results, query);
            } else if (query.isAskType()) {
                System.out.println(queryExecution.execAsk());
            } else if (query.isConstructType()) {
                queryExecution.execConstruct().write(System.out, "TURTLE");
            } else if (query.isDescribeType()) {
                queryExecution.execDescribe().write(System.out, "TURTLE");
            } else {
                System.out.println("Unsupported query type: " + queryPath);
            }
        }

        System.out.println();
    }
}
