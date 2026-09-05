package it.legislation.crawler;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;

/**
 * Promotes in-force status from something implied by a version label to
 * something queryable (FR-4.5, US-A1, US-A3).
 *
 * <p>Three requirements depend on in-force status — the UI must distinguish it,
 * a resource page must show it as one of four fields, and the currently-in-force
 * Expression must be marked among its siblings — but nothing in the graph
 * carried it. The information was present in two places and reachable from
 * neither SPARQL nor the UI: {@code force_start_date} / {@code force_end_date} in
 * the Normattiva detail rows, and the {@code VIGENZA_yyyyMMdd_Vnn} token in an
 * Expression's {@code eli:version}.
 *
 * <p>Only standard ELI 1.5 predicates are used, and the in-force values are the
 * EU Publications Office authority table rather than invented terms. The project
 * is therefore consuming the standard, not extending it — which matters because
 * ontology extension decisions rest with the supervisors.
 *
 * <pre>
 * &lt;expression&gt; eli:first_date_entry_in_force "2025-03-20"^^xsd:date ;
 *               eli:date_no_longer_in_force    "2026-01-31"^^xsd:date ;
 *               eli:in_force &lt;.../eli-in-force/IN_FORCE&gt; .
 * </pre>
 */
public class InForceRdfBuilder {

    public static final String ELI_NS = "http://data.europa.eu/eli/ontology#";
    public static final String IN_FORCE_AUTHORITY =
            "http://publications.europa.eu/resource/authority/eli-in-force/";

    /** Values from the EU in-force authority table. */
    public static final String IN_FORCE = IN_FORCE_AUTHORITY + "IN_FORCE";
    public static final String NOT_IN_FORCE = IN_FORCE_AUTHORITY + "NOT_IN_FORCE";
    public static final String PARTIALLY_IN_FORCE = IN_FORCE_AUTHORITY + "PARTIALLY_IN_FORCE";

    /** Normattiva labels a current text as VIGENZA_{yyyyMMdd}_V{n}. */
    private static final Pattern VIGENZA =
            Pattern.compile("VIGENZA_(\\d{4})(\\d{2})(\\d{2})(?:_V(\\d+))?", Pattern.CASE_INSENSITIVE);

    /**
     * ...and the first published text as ORIGINALE_V{n}. The Gazzetta crawler
     * writes the English spelling ORIGINAL for the same concept, so both are
     * accepted.
     */
    private static final Pattern ORIGINALE =
            Pattern.compile("ORIGINAL(?:E)?(?:_V(\\d+))?", Pattern.CASE_INSENSITIVE);

    public Model build(Iterable<ExpressionStatus> statuses) {
        Model model = createModel();
        for (ExpressionStatus status : statuses) {
            add(model, status);
        }
        return model;
    }

    public void add(Model model, ExpressionStatus status) {
        if (status == null || status.expressionUri() == null || status.expressionUri().isBlank()) {
            return;
        }

        Resource expression = model.createResource(status.expressionUri());
        Property firstDateEntryInForce = model.createProperty(ELI_NS, "first_date_entry_in_force");
        Property dateNoLongerInForce = model.createProperty(ELI_NS, "date_no_longer_in_force");
        Property inForce = model.createProperty(ELI_NS, "in_force");

        status.forceStart().ifPresent(date ->
                expression.addProperty(firstDateEntryInForce,
                        model.createTypedLiteral(date.toString(), XSDDatatype.XSDdate)));
        status.forceEnd().ifPresent(date ->
                expression.addProperty(dateNoLongerInForce,
                        model.createTypedLiteral(date.toString(), XSDDatatype.XSDdate)));

        expression.addProperty(inForce, model.createResource(status.inForceValue()));
    }

    private Model createModel() {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("eli", ELI_NS);
        model.setNsPrefix("eli-fc", IN_FORCE_AUTHORITY);
        model.setNsPrefix("xsd", XSDDatatype.XSD + "#");
        return model;
    }

    /**
     * Derives status from an Expression's version token, for data that has no
     * explicit force dates. A {@code VIGENZA_} text is the one in force; an
     * {@code ORIGINALE_} text has been superseded when a current text exists
     * alongside it, and is still in force when it stands alone.
     */
    public static ExpressionStatus fromVersionToken(
            String expressionUri,
            String versionUri,
            boolean aCurrentTextExistsForTheSameWork
    ) {
        String token = versionUri == null ? "" : localName(versionUri);

        Matcher vigenza = VIGENZA.matcher(token);
        if (vigenza.find()) {
            LocalDate from = date(vigenza.group(1), vigenza.group(2), vigenza.group(3));
            return new ExpressionStatus(expressionUri, Optional.ofNullable(from), Optional.empty(), IN_FORCE);
        }

        if (ORIGINALE.matcher(token).find()) {
            return new ExpressionStatus(
                    expressionUri,
                    Optional.empty(),
                    Optional.empty(),
                    aCurrentTextExistsForTheSameWork ? NOT_IN_FORCE : IN_FORCE
            );
        }

        return new ExpressionStatus(expressionUri, Optional.empty(), Optional.empty(), IN_FORCE);
    }

    /** Derives status from the force dates Normattiva returns for an act. */
    public static ExpressionStatus fromForceDates(
            String expressionUri,
            String forceStartDate,
            String forceEndDate,
            LocalDate today
    ) {
        Optional<LocalDate> start = parseDate(forceStartDate);
        Optional<LocalDate> end = parseDate(forceEndDate);

        String value;
        if (end.isPresent() && end.get().isBefore(today)) {
            value = NOT_IN_FORCE;
        } else if (start.isPresent() && start.get().isAfter(today)) {
            // Published, dated, but not yet applicable.
            value = NOT_IN_FORCE;
        } else {
            value = IN_FORCE;
        }

        return new ExpressionStatus(expressionUri, start, end, value);
    }

    private static LocalDate date(String year, String month, String day) {
        try {
            return LocalDate.parse(year + "-" + month + "-" + day);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static Optional<LocalDate> parseDate(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        try {
            return Optional.of(LocalDate.parse(trimmed));
        } catch (DateTimeParseException ignored) {
            // Normattiva also returns dd/MM/yyyy in places.
            Matcher slashed = Pattern.compile("(\\d{2})/(\\d{2})/(\\d{4})").matcher(trimmed);
            if (slashed.matches()) {
                LocalDate parsed = date(slashed.group(3), slashed.group(2), slashed.group(1));
                return Optional.ofNullable(parsed);
            }
            return Optional.empty();
        }
    }

    private static String localName(String uri) {
        int hash = uri.lastIndexOf('#');
        int slash = uri.lastIndexOf('/');
        int cut = Math.max(hash, slash);
        return cut >= 0 && cut < uri.length() - 1 ? uri.substring(cut + 1) : uri;
    }

    /** In-force facts about one Expression. */
    public record ExpressionStatus(
            String expressionUri,
            Optional<LocalDate> forceStart,
            Optional<LocalDate> forceEnd,
            String inForceValue
    ) {
        public boolean isInForce() {
            return IN_FORCE.equals(inForceValue);
        }
    }
}
