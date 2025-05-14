package it.legislation.crawler;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.query.*;

public class FusekiUploader {
    public static void main(String[] args) {
        // Load the local Turtle file
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, "eli_metadata.ttl", RDFLanguages.TURTLE);

        // Define a SPARQL query
        String queryStr = "PREFIX eli: <http://data.europa.eu/eli/ontology#>\n" +
                "SELECT ?title ?date\n" +
                "WHERE {\n" +
                "  ?law eli:title ?title ;\n" +
                "       eli:date_publication ?date .\n" +
                "}";


        // Run the query
        Query query = QueryFactory.create(queryStr);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            ResultSetFormatter.out(System.out, results, query);
        }
    }
}
