package it.legislation.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.springframework.stereotype.Service;

import it.legislation.store.Tdb2DatasetService;

/**
 * What the Legal acts and Act details pages show, read with SPARQL from the
 * store on every request.
 *
 * <p>Results that change over time are dated assessments
 * ({@code ilg:ConversionCheck}, {@code ilg:GazzettaCheck},
 * {@code ilg:RelationAssessment}); the pages show the latest one and keep the
 * earlier ones as history.
 */
@Service
public class ActCatalog {

    static final String PREFIXES = """
            PREFIX eli: <http://data.europa.eu/eli/ontology#>
            PREFIX ilg: <http://example.org/italian-legislation/ontology#>
            PREFIX dcterms: <http://purl.org/dc/terms/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            """;
    static final String ILG = "http://example.org/italian-legislation/ontology#";
    static final String ELI = "http://data.europa.eu/eli/ontology#";

    private final Tdb2DatasetService store;

    public ActCatalog(Tdb2DatasetService store) {
        this.store = store;
    }

    // ------------------------------------------------------------------ records

    /** One row of the Legal acts list. */
    public record ActRow(String uri, String localId, String typeCode, String date, String number, String title,
                         int versions, String conversionStatus, String gazzettaStatus) {
        public String citation() {
            return Html.citation(typeCode, date, number);
        }

        public String year() {
            return date == null || date.length() < 4 ? "" : date.substring(0, 4);
        }
    }

    /** One dated assessment: its type (ConversionCheck, GazzettaCheck, RelationAssessment), date and values. */
    public record Assessment(String node, String type, String assessedOn, Map<String, List<String>> values) {
        public String value(String localName) {
            List<String> v = values.get(ILG + localName);
            return v == null || v.isEmpty() ? null : v.get(0);
        }
    }

    /** A version (ELI expression) with the file it was read from. */
    public record Version(String uri, String date, String label, String source, String sha256) {}

    /** The act at the other end of a relation. */
    public record OtherAct(String uri, String typeCode, String date, String number, String title, boolean inCorpus) {
        public String citation() {
            return typeCode == null || typeCode.isEmpty() ? title : Html.citation(typeCode, date, number);
        }
    }

    /** A relation with its evidence and its latest assessment. */
    public record Relation(String uri, boolean outgoing, String property, OtherAct other, String note, String source,
                           Assessment latest) {
        public String propertyName() {
            int hash = property.lastIndexOf('#');
            return hash >= 0 ? property.substring(hash + 1) : property;
        }
    }

    /** A decree-law that this law converts, with the decree's latest conversion assessment. */
    public record Converted(OtherAct decree, Assessment latest) {}

    /** Everything the Act details page shows. */
    public record ActDetails(String uri, Map<String, List<String>> facts, List<Version> versions,
                             List<Assessment> conversionHistory, List<Assessment> gazzettaHistory,
                             List<Relation> relations, List<Converted> converts) {
        public String fact(String property) {
            List<String> v = facts.get(property);
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        public String typeCode() {
            return Html.typeCode(fact(ELI + "type_document"));
        }

        public String citation() {
            return Html.citation(typeCode(), fact(ELI + "date_document"), fact(ELI + "number"));
        }

        public Optional<Assessment> conversion() {
            return conversionHistory.isEmpty() ? Optional.empty() : Optional.of(conversionHistory.get(0));
        }

        public Optional<Assessment> gazzetta() {
            return gazzettaHistory.isEmpty() ? Optional.empty() : Optional.of(gazzettaHistory.get(0));
        }
    }

    /** Totals for the head of the list page; relations by kind (amends, changes, repeals, converts). */
    public record Totals(long acts, long versions, long relations, Map<String, Long> relationsByKind) {
        public long relations(String kind) {
            return relationsByKind.getOrDefault(kind, 0L);
        }
    }

    // ------------------------------------------------------------------ queries

    /** The acts imported from Normattiva, newest first, with their current statuses. */
    public List<ActRow> acts() throws IOException {
        return store.read(dataset -> {
            Map<String, String[]> latest = latestStatuses(dataset);
            List<ActRow> rows = new ArrayList<>();
            String query = PREFIXES + """
                    SELECT ?act (SAMPLE(?id) AS ?localId) (SAMPLE(?type) AS ?t) (SAMPLE(?date) AS ?d)
                           (SAMPLE(?number) AS ?n) (SAMPLE(?title) AS ?ti) (COUNT(DISTINCT ?expression) AS ?versions)
                    WHERE {
                      ?act a eli:LegalResource ;
                           dcterms:source <https://dati.normattiva.it/> ;
                           eli:id_local ?id ;
                           eli:type_document ?type ;
                           eli:date_document ?date .
                      OPTIONAL { ?act eli:number ?number }
                      OPTIONAL { ?act eli:title ?title }
                      OPTIONAL { ?act eli:is_realized_by ?expression }
                    }
                    GROUP BY ?act
                    """;
            each(dataset, query, s -> {
                String uri = s.getResource("act").getURI();
                String[] status = latest.getOrDefault(uri, new String[4]);
                rows.add(new ActRow(uri, text(s, "localId"), Html.typeCode(text(s, "t")), text(s, "d"),
                        text(s, "n"), text(s, "ti"), s.getLiteral("versions").getInt(), status[1], status[3]));
            });
            rows.sort(Comparator.comparing(ActRow::date, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(ActRow::localId, Comparator.nullsLast(Comparator.reverseOrder())));
            return rows;
        });
    }

    /** Counts for the list page. */
    public Totals totals() throws IOException {
        return store.read(dataset -> {
            long[] t = new long[3];
            each(dataset, PREFIXES + """
                    SELECT (COUNT(DISTINCT ?act) AS ?acts) (COUNT(DISTINCT ?expression) AS ?versions)
                    WHERE { ?act a eli:LegalResource ; dcterms:source <https://dati.normattiva.it/> .
                            OPTIONAL { ?act eli:is_realized_by ?expression } }
                    """, s -> {
                t[0] = s.getLiteral("acts").getLong();
                t[1] = s.getLiteral("versions").getLong();
            });
            Map<String, Long> byKind = new LinkedHashMap<>();
            each(dataset, PREFIXES + """
                    SELECT ?property (COUNT(?r) AS ?n)
                    WHERE { ?r a ilg:Relation ; ilg:relationProperty ?property }
                    GROUP BY ?property ORDER BY DESC(?n)
                    """, s -> {
                String property = s.getResource("property").getURI();
                byKind.put(property.substring(Math.max(property.lastIndexOf('#'), property.lastIndexOf('/')) + 1),
                        s.getLiteral("n").getLong());
                t[2] += s.getLiteral("n").getLong();
            });
            return new Totals(t[0], t[1], t[2], byKind);
        });
    }

    /** Act URI → {conversion date, conversion status, gazzetta date, gazzetta status}, latest of each. */
    private Map<String, String[]> latestStatuses(Dataset dataset) {
        Map<String, String[]> latest = new HashMap<>();
        each(dataset, PREFIXES + """
                SELECT ?act ?on ?conversion ?gazzetta WHERE {
                  { ?check a ilg:ConversionCheck ; ilg:assesses ?act ; ilg:assessedOn ?on ;
                           ilg:conversionStatus ?conversion }
                  UNION
                  { ?check a ilg:GazzettaCheck ; ilg:assesses ?act ; ilg:assessedOn ?on ;
                           ilg:gazzettaCheck ?gazzetta }
                }
                """, s -> {
            String[] row = latest.computeIfAbsent(s.getResource("act").getURI(), k -> new String[4]);
            String on = text(s, "on");
            int slot = s.contains("conversion") ? 0 : 2;
            if (row[slot] == null || on.compareTo(row[slot]) > 0) {
                row[slot] = on;
                row[slot + 1] = text(s, slot == 0 ? "conversion" : "gazzetta");
            }
        });
        return latest;
    }

    /** True when the URI is an act (an {@code eli:LegalResource}). */
    public boolean isAct(String uri) throws IOException {
        return store.read(dataset -> {
            ParameterizedSparqlString ask = new ParameterizedSparqlString(PREFIXES + "ASK { ?act a eli:LegalResource }");
            ask.setIri("act", uri);
            try (QueryExecution execution = QueryExecutionFactory.create(ask.asQuery(), dataset)) {
                return execution.execAsk();
            }
        });
    }

    public Optional<ActDetails> details(String actUri) throws IOException {
        return store.read(dataset -> {
            Map<String, List<String>> facts = new LinkedHashMap<>();
            each(dataset, bind("SELECT ?p ?o WHERE { ?act ?p ?o }", actUri), s ->
                    facts.computeIfAbsent(s.getResource("p").getURI(), k -> new ArrayList<>()).add(value(s.get("o"))));
            if (facts.isEmpty()) {
                return Optional.empty();
            }

            List<Version> versions = new ArrayList<>();
            each(dataset, bind("""
                    SELECT ?e (SAMPLE(?d) AS ?date) (SAMPLE(?l) AS ?label) (SAMPLE(?src) AS ?source) (SAMPLE(?h) AS ?sha)
                    WHERE {
                      ?act eli:is_realized_by ?e .
                      OPTIONAL { ?e eli:version_date ?d }
                      OPTIONAL { ?e rdfs:label ?l }
                      OPTIONAL { ?e eli:is_embodied_by ?f . OPTIONAL { ?f dcterms:source ?src } OPTIONAL { ?f ilg:sha256 ?h } }
                    } GROUP BY ?e
                    """, actUri), s -> versions.add(new Version(s.getResource("e").getURI(), text(s, "date"),
                    text(s, "label"), text(s, "source"), text(s, "sha"))));
            // The original text first, then the consolidated versions by date.
            versions.sort(Comparator.comparing((Version v) -> !v.uri().endsWith("/original"))
                    .thenComparing(Version::date, Comparator.nullsFirst(Comparator.naturalOrder())));

            Map<String, List<Assessment>> byType = assessments(dataset, bind("""
                    SELECT ?node ?type ?on ?p ?o WHERE {
                      ?node ilg:assesses ?act ; a ?type ; ilg:assessedOn ?on ; ?p ?o .
                    }
                    """, actUri));

            return Optional.of(new ActDetails(actUri, facts, versions,
                    byType.getOrDefault(ILG + "ConversionCheck", List.of()),
                    byType.getOrDefault(ILG + "GazzettaCheck", List.of()),
                    relations(dataset, actUri), converts(dataset, actUri)));
        });
    }

    private List<Relation> relations(Dataset dataset, String actUri) {
        Map<String, String[]> base = new LinkedHashMap<>();
        each(dataset, bind("""
                SELECT ?r ?dir ?other ?property (SAMPLE(?n) AS ?note) (SAMPLE(?s) AS ?source) WHERE {
                  { ?r ilg:fromAct ?act ; ilg:toAct ?other . BIND("out" AS ?dir) }
                  UNION
                  { ?r ilg:toAct ?act ; ilg:fromAct ?other . BIND("in" AS ?dir) }
                  ?r a ilg:Relation ; ilg:relationProperty ?property .
                  OPTIONAL { ?r ilg:exampleNote ?n }
                  OPTIONAL { ?r dcterms:source ?s }
                } GROUP BY ?r ?dir ?other ?property
                """, actUri), s -> base.put(s.getResource("r").getURI(), new String[] {
                text(s, "dir"), s.getResource("other").getURI(), s.getResource("property").getURI(),
                text(s, "note"), text(s, "source")}));
        if (base.isEmpty()) {
            return List.of();
        }

        Map<String, List<Assessment>> history = new HashMap<>();
        assessmentsOf(dataset, bind("""
                SELECT ?subject ?node ?type ?on ?p ?o WHERE {
                  { ?subject ilg:fromAct ?act } UNION { ?subject ilg:toAct ?act }
                  ?node ilg:assesses ?subject ; a ?type ; ilg:assessedOn ?on ; ?p ?o .
                }
                """, actUri), history);

        Map<String, OtherAct> others = new HashMap<>();
        each(dataset, bind("""
                SELECT ?other (SAMPLE(?t) AS ?type) (SAMPLE(?d) AS ?date) (SAMPLE(?n) AS ?number)
                       (SAMPLE(?ti) AS ?title) (SAMPLE(?l) AS ?label) (SAMPLE(?c) AS ?corpus) WHERE {
                  { ?r ilg:fromAct ?act ; ilg:toAct ?other } UNION { ?r ilg:toAct ?act ; ilg:fromAct ?other }
                  OPTIONAL { ?other eli:type_document ?t }
                  OPTIONAL { ?other eli:date_document ?d }
                  OPTIONAL { ?other eli:number ?n }
                  OPTIONAL { ?other eli:title ?ti }
                  OPTIONAL { ?other rdfs:label ?l }
                  OPTIONAL { ?other eli:id_local ?c }
                } GROUP BY ?other
                """, actUri), s -> {
            String uri = s.getResource("other").getURI();
            String title = text(s, "title") != null ? text(s, "title") : text(s, "label");
            others.put(uri, new OtherAct(uri, Html.typeCode(text(s, "type")), text(s, "date"), text(s, "number"),
                    title == null ? uri : title, text(s, "corpus") != null));
        });

        List<Relation> relations = new ArrayList<>();
        base.forEach((uri, b) -> {
            List<Assessment> h = history.getOrDefault(uri, List.of());
            relations.add(new Relation(uri, "out".equals(b[0]), b[2],
                    others.getOrDefault(b[1], new OtherAct(b[1], "", null, null, b[1], false)),
                    b[3], b[4], h.isEmpty() ? null : h.get(0)));
        });
        List<String> order = List.of("converts", "repeals", "amends", "changes");
        relations.sort(Comparator.comparing((Relation r) -> !r.outgoing())
                .thenComparing(r -> order.indexOf(r.propertyName()) < 0 ? order.size() : order.indexOf(r.propertyName()))
                .thenComparing(r -> r.other().date() == null ? "" : r.other().date()));
        return relations;
    }

    /**
     * Decree-laws whose conversion check names this act as the converting law. A
     * decree is listed only while its latest check still names this law.
     */
    private List<Converted> converts(Dataset dataset, String actUri) {
        Map<String, List<Assessment>> history = new HashMap<>();
        assessmentsOf(dataset, bind("""
                SELECT ?subject ?node ?type ?on ?p ?o WHERE {
                  ?any a ilg:ConversionCheck ; ilg:law ?act ; ilg:assesses ?subject .
                  ?node a ilg:ConversionCheck ; ilg:assesses ?subject ; a ?type ; ilg:assessedOn ?on ; ?p ?o .
                }
                """, actUri), history);
        List<Converted> converted = new ArrayList<>();
        history.forEach((decree, checks) -> {
            Assessment latest = checks.get(0);
            if (actUri.equals(latest.value("law"))) {
                converted.add(new Converted(otherAct(dataset, decree), latest));
            }
        });
        converted.sort(Comparator.comparing(c -> c.decree().date() == null ? "" : c.decree().date()));
        return converted;
    }

    private static OtherAct otherAct(Dataset dataset, String uri) {
        ParameterizedSparqlString query = new ParameterizedSparqlString(PREFIXES + """
                SELECT (SAMPLE(?t) AS ?type) (SAMPLE(?d) AS ?date) (SAMPLE(?n) AS ?number) (SAMPLE(?ti) AS ?title)
                       (SAMPLE(?c) AS ?corpus) WHERE {
                  OPTIONAL { ?other eli:type_document ?t }
                  OPTIONAL { ?other eli:date_document ?d }
                  OPTIONAL { ?other eli:number ?n }
                  OPTIONAL { ?other eli:title ?ti }
                  OPTIONAL { ?other eli:id_local ?c }
                }
                """);
        query.setIri("other", uri);
        OtherAct[] found = {new OtherAct(uri, "", null, null, uri, false)};
        each(dataset, query.toString(), s -> {
            if (s.get("type") != null) {
                found[0] = new OtherAct(uri, Html.typeCode(text(s, "type")), text(s, "date"), text(s, "number"),
                        text(s, "title") == null ? uri : text(s, "title"), text(s, "corpus") != null);
            }
        });
        return found[0];
    }

    /** Assessments of one subject, grouped by type, newest first. */
    private Map<String, List<Assessment>> assessments(Dataset dataset, String query) {
        Map<String, Map<String, Assessment>> nodes = new LinkedHashMap<>();
        each(dataset, query, s -> collect(nodes, s));
        Map<String, List<Assessment>> byType = new HashMap<>();
        nodes.forEach((type, map) -> byType.put(type, newestFirst(map)));
        return byType;
    }

    /** Assessments of many subjects (?subject), newest first per subject. */
    private void assessmentsOf(Dataset dataset, String query, Map<String, List<Assessment>> into) {
        Map<String, Map<String, Assessment>> nodes = new HashMap<>();
        each(dataset, query, s -> collect(nodes, s, s.getResource("subject").getURI()));
        nodes.forEach((subject, map) -> into.put(subject, newestFirst(map)));
    }

    private static void collect(Map<String, Map<String, Assessment>> groups, QuerySolution s) {
        collect(groups, s, s.getResource("type").getURI());
    }

    private static void collect(Map<String, Map<String, Assessment>> groups, QuerySolution s, String groupKey) {
        String type = s.getResource("type").getURI();
        if (!type.startsWith(ILG)) {
            return;
        }
        String node = s.getResource("node").getURI();
        Assessment a = groups.computeIfAbsent(groupKey, k -> new LinkedHashMap<>())
                .computeIfAbsent(node, k -> new Assessment(node, type.substring(ILG.length()), text(s, "on"),
                        new TreeMap<>()));
        String p = s.getResource("p").getURI();
        String v = value(s.get("o"));
        List<String> values = a.values().computeIfAbsent(p, k -> new ArrayList<>());
        if (!values.contains(v)) {
            values.add(v);
        }
    }

    private static List<Assessment> newestFirst(Map<String, Assessment> nodes) {
        List<Assessment> list = new ArrayList<>(nodes.values());
        list.sort(Comparator.comparing(Assessment::assessedOn, Comparator.nullsLast(Comparator.reverseOrder())));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static String bind(String select, String actUri) {
        ParameterizedSparqlString query = new ParameterizedSparqlString(PREFIXES + select);
        query.setIri("act", actUri);
        return query.toString();
    }

    private static void each(Dataset dataset, String query, java.util.function.Consumer<QuerySolution> row) {
        try (QueryExecution execution = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = execution.execSelect();
            while (results.hasNext()) {
                row.accept(results.next());
            }
        }
    }

    private static String text(QuerySolution s, String var) {
        RDFNode node = s.get(var);
        return node == null ? null : value(node);
    }

    private static String value(RDFNode node) {
        if (node == null) {
            return null;
        }
        if (node.isURIResource()) {
            return node.asResource().getURI();
        }
        if (node.isLiteral()) {
            return node.asLiteral().getLexicalForm();
        }
        return node.toString();
    }
}
