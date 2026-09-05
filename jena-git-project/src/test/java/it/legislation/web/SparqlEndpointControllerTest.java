package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.junit.jupiter.api.Test;

/**
 * Content negotiation for the public SPARQL endpoint (FR-3.1, FR-3.2, US-B1).
 *
 * <p>US-B1 requires the endpoint to accept "a SELECT and a DESCRIBE query". The
 * two forms return different kinds of thing — a result set and a graph — so each
 * needs its own default serialisation.
 */
class SparqlEndpointControllerTest {

    private static final Query SELECT = QueryFactory.create("SELECT * WHERE { ?s ?p ?o }");
    private static final Query ASK = QueryFactory.create("ASK { ?s ?p ?o }");
    private static final Query DESCRIBE =
            QueryFactory.create("DESCRIBE <http://example.org/act>");
    private static final Query CONSTRUCT =
            QueryFactory.create("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }");

    @Test
    void selectDefaultsToSparqlResultsJson() {
        assertEquals("json", SparqlEndpointController.chooseFormat(SELECT, null, null));
        assertEquals("json", SparqlEndpointController.chooseFormat(ASK, null, null));
    }

    @Test
    void describeAndConstructDefaultToTurtle() {
        assertEquals("turtle", SparqlEndpointController.chooseFormat(DESCRIBE, null, null));
        assertEquals("turtle", SparqlEndpointController.chooseFormat(CONSTRUCT, null, null));
    }

    @Test
    void honoursTheAcceptHeaderForResultSets() {
        assertEquals("xml", SparqlEndpointController.chooseFormat(
                SELECT, null, "application/sparql-results+xml"));
        assertEquals("csv", SparqlEndpointController.chooseFormat(SELECT, null, "text/csv"));
    }

    @Test
    void honoursTheAcceptHeaderForGraphs() {
        assertEquals("rdfxml", SparqlEndpointController.chooseFormat(
                DESCRIBE, null, "application/rdf+xml"));
        assertEquals("ntriples", SparqlEndpointController.chooseFormat(
                DESCRIBE, null, "application/n-triples"));
        assertEquals("jsonld", SparqlEndpointController.chooseFormat(
                DESCRIBE, null, "application/ld+json"));
    }

    @Test
    void explicitOutputParameterWinsOverAccept() {
        assertEquals("csv", SparqlEndpointController.chooseFormat(
                SELECT, "csv", "application/sparql-results+json"));
        assertEquals("turtle", SparqlEndpointController.chooseFormat(
                DESCRIBE, "ttl", "application/rdf+xml"));
    }

    @Test
    void anOutputParameterThatMakesNoSenseForTheQueryFormIsIgnored() {
        // "csv" is not a graph serialisation; the DESCRIBE must still be valid RDF.
        assertEquals("turtle", SparqlEndpointController.chooseFormat(DESCRIBE, "csv", null));
        // ...and "turtle" is not a result-set serialisation.
        assertEquals("json", SparqlEndpointController.chooseFormat(SELECT, "turtle", null));
    }

    @Test
    void aBrowserAcceptHeaderStillGetsAUsableFormat() {
        String browser = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

        assertEquals("json", SparqlEndpointController.chooseFormat(SELECT, null, browser));
        assertEquals("turtle", SparqlEndpointController.chooseFormat(DESCRIBE, null, browser));
    }
}
