package it.legislation.web;

import java.io.IOException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import it.legislation.mapping.AknActReader;
import it.legislation.relations.ActKey;
import it.legislation.relations.RelationDetector;
import it.legislation.store.Tdb2DatasetService;

/**
 * What the Legal acts and Act details pages show, read with SPARQL from the
 * store on every request. The data is ELI only: acts and their original text
 * (Layer 1), consolidated versions (Layer 2), and which act modifies which
 * ({@code eli:amends}, {@code eli:repeals}, {@code eli:changes}, and
 * {@code eli:consolidates} from a version to the act it contains).
 *
 * <p>Conversion is read from the titles: a law whose title says "Conversione in
 * legge … del decreto-legge ⟨date⟩, n. ⟨n⟩" converts that decree-law
 * ({@link RelationDetector#convertedDecree}). A decree-law with no such law is
 * pending for 60 days, then not converted.
 */
@Service
public class ActCatalog {

    static final String PREFIXES = """
            PREFIX eli: <http://data.europa.eu/eli/ontology#>
            PREFIX dcterms: <http://purl.org/dc/terms/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            PREFIX rt: <http://www.gazzettaufficiale.it/eli/tables/resource-type#>
            """;
    static final String ELI = "http://data.europa.eu/eli/ontology#";
    /** The relations between acts, as ELI names them. */
    static final List<String> KINDS = List.of("amends", "repeals", "changes");

    private final Tdb2DatasetService store;
    private final Supplier<LocalDate> today;

    @Autowired
    public ActCatalog(Tdb2DatasetService store) {
        this(store, LocalDate::now);
    }

    /** @param today the day that decides whether the 60 days for conversion are over */
    ActCatalog(Tdb2DatasetService store, Supplier<LocalDate> today) {
        this.store = store;
        this.today = today;
    }

    // ------------------------------------------------------------------ records

    /** One row of the Legal acts list; {@code conversionStatus} only for decree-laws. */
    public record ActRow(String uri, String localId, String typeCode, String date, String number, String title,
                         int versions, String conversionStatus) {
        public String citation() {
            return Html.citation(typeCode, date, number);
        }

        public String year() {
            return date == null || date.length() < 4 ? "" : date.substring(0, 4);
        }
    }

    /** A version (ELI expression) with the file it was read from and the acts it consolidates. */
    public record Version(String uri, String date, String label, String source, List<OtherAct> changedBy) {}

    /** The act at the other end of a relation. */
    public record OtherAct(String uri, String typeCode, String date, String number, String title, boolean inCorpus) {
        public String citation() {
            return typeCode == null || typeCode.isEmpty() ? title : Html.citation(typeCode, date, number);
        }
    }

    /**
     * A relation: {@code outgoing} when this act modifies the other one. {@code property} is the ELI property
     * (amends, repeals, changes), shown as "converts" when the pair is a conversion law and its decree-law.
     */
    public record Relation(boolean outgoing, String property, OtherAct other) {
        public String propertyName() {
            int hash = property.lastIndexOf('#');
            return hash >= 0 ? property.substring(hash + 1) : property;
        }
    }

    /** A decree-law's conversion: CONVERTED (with the law and the days), PENDING or NOT_CONVERTED. */
    public record Conversion(String status, OtherAct law, Long days) {}

    /** A decree-law this law converts, with the days from the decree to the law. */
    public record Converted(OtherAct decree, Long days) {}

    /** Everything the Act details page shows. */
    public record ActDetails(String uri, Map<String, List<String>> facts, List<Version> versions,
                             Optional<Conversion> conversion, List<Relation> relations, List<Converted> converts) {
        public String fact(String property) {
            List<String> v = facts.get(property);
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        /** Every value of a property (e.g. all {@code owl:sameAs}). */
        public List<String> facts(String property) {
            return facts.getOrDefault(property, List.of());
        }

        public String typeCode() {
            return Html.typeCode(fact(ELI + "type_document"));
        }

        public String citation() {
            return Html.citation(typeCode(), fact(ELI + "date_document"), fact(ELI + "number"));
        }
    }

    /** Totals for the head of the list page; relations by kind (amends, repeals, changes). */
    public record Totals(long acts, long versions, long relations, Map<String, Long> relationsByKind) {
        public long relations(String kind) {
            return relationsByKind.getOrDefault(kind, 0L);
        }
    }

    // ------------------------------------------------------------------ queries

    /** The acts imported from Normattiva, newest first; decree-laws with their conversion status. */
    public List<ActRow> acts() throws IOException {
        return store.read(dataset -> {
            Map<String, ConversionFacts> conversions = conversions(dataset);
            List<ActRow> rows = new ArrayList<>();
            String query = PREFIXES + """
                    SELECT ?act (SAMPLE(?id) AS ?localId) (SAMPLE(?type) AS ?t) (SAMPLE(?date) AS ?d)
                           (SAMPLE(?number) AS ?n) (SAMPLE(?title) AS ?ti) (SAMPLE(?pub) AS ?p)
                           (COUNT(DISTINCT ?expression) AS ?versions)
                    WHERE {
                      ?act a eli:LegalResource ;
                           dcterms:source <https://dati.normattiva.it/> ;
                           eli:id_local ?id ;
                           eli:type_document ?type ;
                           eli:date_document ?date .
                      OPTIONAL { ?act eli:number ?number }
                      OPTIONAL { ?act eli:title ?title }
                      OPTIONAL { ?act eli:date_publication ?pub }
                      OPTIONAL { ?act eli:is_realized_by ?expression }
                    }
                    GROUP BY ?act
                    """;
            each(dataset, query, s -> {
                String uri = s.getResource("act").getURI();
                String type = Html.typeCode(text(s, "t"));
                rows.add(new ActRow(uri, text(s, "localId"), type, text(s, "d"), text(s, "n"), text(s, "ti"),
                        s.getLiteral("versions").getInt(),
                        "DECRETO-LEGGE".equals(type) ? status(conversions.get(uri), text(s, "p")) : null));
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
                    SELECT ?property (COUNT(*) AS ?n)
                    WHERE { ?a ?property ?b . FILTER(?property IN (eli:amends, eli:repeals, eli:changes)) }
                    GROUP BY ?property ORDER BY DESC(?n)
                    """, s -> {
                String property = s.getResource("property").getURI();
                byKind.put(property.substring(property.lastIndexOf('#') + 1), s.getLiteral("n").getLong());
                t[2] += s.getLiteral("n").getLong();
            });
            return new Totals(t[0], t[1], t[2], byKind);
        });
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
                    SELECT ?e (SAMPLE(?d) AS ?date) (SAMPLE(?l) AS ?label) (SAMPLE(?src) AS ?source)
                    WHERE {
                      ?act eli:is_realized_by ?e .
                      OPTIONAL { ?e eli:version_date ?d }
                      OPTIONAL { ?e rdfs:label ?l }
                      OPTIONAL { ?e eli:is_embodied_by ?f . ?f dcterms:source ?src }
                    } GROUP BY ?e
                    """, actUri), s -> versions.add(new Version(s.getResource("e").getURI(), text(s, "date"),
                    text(s, "label"), text(s, "source"), new ArrayList<>())));
            // Which act(s) each version consolidates.
            Map<String, Version> byUri = new HashMap<>();
            versions.forEach(v -> byUri.put(v.uri(), v));
            each(dataset, bind("""
                    SELECT ?e ?by (SAMPLE(?t) AS ?type) (SAMPLE(?d) AS ?date) (SAMPLE(?n) AS ?number)
                           (SAMPLE(?ti) AS ?title) (SAMPLE(?c) AS ?corpus) WHERE {
                      ?act eli:is_realized_by ?e . ?e eli:consolidates ?by .
                      OPTIONAL { ?by eli:type_document ?t }
                      OPTIONAL { ?by eli:date_document ?d }
                      OPTIONAL { ?by eli:number ?n }
                      OPTIONAL { ?by eli:title ?ti }
                      OPTIONAL { ?by eli:id_local ?c }
                    } GROUP BY ?e ?by ORDER BY ?date
                    """, actUri), s -> {
                Version v = byUri.get(s.getResource("e").getURI());
                if (v != null) {
                    String by = s.getResource("by").getURI();
                    v.changedBy().add(new OtherAct(by, Html.typeCode(text(s, "type")), text(s, "date"),
                            text(s, "number"), text(s, "title") == null ? by : text(s, "title"), text(s, "corpus") != null));
                }
            });
            // The original text first, then the consolidated versions by date.
            versions.sort(Comparator.comparing((Version v) -> !v.uri().endsWith("/original"))
                    .thenComparing(Version::date, Comparator.nullsFirst(Comparator.naturalOrder())));

            Map<String, ConversionFacts> conversions = conversions(dataset);
            Optional<Conversion> conversion = Optional.empty();
            if ("DECRETO-LEGGE".equals(Html.typeCode(first(facts, ELI + "type_document")))) {
                ConversionFacts c = conversions.get(actUri);
                conversion = Optional.of(new Conversion(status(c, first(facts, ELI + "date_publication")),
                        c == null ? null : otherAct(dataset, c.law()), c == null ? null : c.days()));
            }
            List<Converted> converts = new ArrayList<>();
            conversions.forEach((decree, c) -> {
                if (actUri.equals(c.law())) {
                    converts.add(new Converted(otherAct(dataset, decree), c.days()));
                }
            });
            converts.sort(Comparator.comparing(c -> c.decree().date() == null ? "" : c.decree().date()));
            return Optional.of(new ActDetails(actUri, facts, versions, conversion,
                    relations(dataset, actUri, conversions), converts));
        });
    }

    private List<Relation> relations(Dataset dataset, String actUri, Map<String, ConversionFacts> conversions) {
        List<String[]> base = new ArrayList<>();
        each(dataset, bind("""
                SELECT DISTINCT ?dir ?other ?property WHERE {
                  { ?act ?property ?other . BIND("out" AS ?dir) }
                  UNION
                  { ?other ?property ?act . BIND("in" AS ?dir) }
                  FILTER(?property IN (eli:amends, eli:repeals, eli:changes))
                }
                """, actUri), s -> base.add(new String[] {text(s, "dir"), s.getResource("other").getURI(),
                s.getResource("property").getURI()}));
        if (base.isEmpty()) {
            return List.of();
        }

        Map<String, OtherAct> others = new HashMap<>();
        each(dataset, bind("""
                SELECT ?other (SAMPLE(?t) AS ?type) (SAMPLE(?d) AS ?date) (SAMPLE(?n) AS ?number)
                       (SAMPLE(?ti) AS ?title) (SAMPLE(?l) AS ?label) (SAMPLE(?c) AS ?corpus) WHERE {
                  { ?act ?p ?other } UNION { ?other ?p ?act }
                  FILTER(?p IN (eli:amends, eli:repeals, eli:changes))
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
        for (String[] b : base) {
            boolean outgoing = "out".equals(b[0]);
            String law = outgoing ? actUri : b[1];
            String decree = outgoing ? b[1] : actUri;
            ConversionFacts c = conversions.get(decree);
            String property = c != null && law.equals(c.law()) ? "converts" : b[2];
            relations.add(new Relation(outgoing, property,
                    others.getOrDefault(b[1], new OtherAct(b[1], "", null, null, b[1], false))));
        }
        List<String> order = List.of("converts", "repeals", "amends", "changes");
        relations.sort(Comparator.comparing((Relation r) -> !r.outgoing())
                .thenComparing(r -> order.indexOf(r.propertyName()) < 0 ? order.size() : order.indexOf(r.propertyName()))
                .thenComparing(r -> r.other().date() == null ? "" : r.other().date()));
        return relations;
    }

    /** A decree-law's converting law and the days between their publications. */
    record ConversionFacts(String law, Long days) {}

    /**
     * Decree-law URI → its converting law, from the laws' titles ("Conversione in legge … del decreto-legge …").
     * When two laws name the same decree-law, the earlier one.
     */
    private static Map<String, ConversionFacts> conversions(Dataset dataset) {
        Map<ActKey, String[]> decrees = new HashMap<>();
        each(dataset, PREFIXES + """
                SELECT ?act ?d ?n ?pub WHERE {
                  ?act eli:type_document rt:DECRETO-LEGGE ; dcterms:source <https://dati.normattiva.it/> ;
                       eli:date_document ?d ; eli:number ?n ; eli:date_publication ?pub .
                }
                """, s -> key("DECRETO-LEGGE", text(s, "d"), text(s, "n"))
                .ifPresent(k -> decrees.put(k, new String[] {s.getResource("act").getURI(), text(s, "pub")})));
        Map<String, ConversionFacts> result = new HashMap<>();
        Map<String, String> lawDate = new HashMap<>();
        each(dataset, PREFIXES + """
                SELECT ?law ?title ?pub WHERE {
                  ?law eli:type_document rt:LEGGE ; dcterms:source <https://dati.normattiva.it/> ;
                       eli:title ?title ; eli:date_publication ?pub .
                  FILTER(CONTAINS(LCASE(STR(?title)), "conversione in legge"))
                }
                """, s -> RelationDetector.convertedDecree(text(s, "title")).map(decrees::get).ifPresent(d -> {
            String law = s.getResource("law").getURI();
            String pub = text(s, "pub");
            String earlier = lawDate.get(d[0]);
            if (earlier == null || pub.compareTo(earlier) < 0) {
                lawDate.put(d[0], pub);
                result.put(d[0], new ConversionFacts(law, days(d[1], pub)));
            }
        }));
        return result;
    }

    /** CONVERTED, or PENDING within 60 days of publication, else NOT_CONVERTED. */
    private String status(ConversionFacts conversion, String published) {
        if (conversion != null) {
            return "CONVERTED";
        }
        Long age = days(published, today.get().toString());
        return age != null && age <= 60 ? "PENDING" : "NOT_CONVERTED";
    }

    private static Optional<ActKey> key(String type, String date, String number) {
        try {
            return Optional.of(new ActKey(type, LocalDate.parse(date.substring(0, 10)), AknActReader.actNumber(number)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Long days(String from, String to) {
        try {
            return ChronoUnit.DAYS.between(LocalDate.parse(from.substring(0, 10)), LocalDate.parse(to.substring(0, 10)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String first(Map<String, List<String>> facts, String property) {
        List<String> v = facts.get(property);
        return v == null || v.isEmpty() ? null : v.get(0);
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
