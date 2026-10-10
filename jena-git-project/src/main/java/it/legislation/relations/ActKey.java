package it.legislation.relations;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies an act by type, date of the act and number: the way acts cite
 * each other ("D.L. 17 marzo 2020, n. 18"). Codice redazionale and GU date
 * are not available in a citation, so relations are resolved with this key.
 *
 * @param typeCode Gazzetta resource-type code: {@code LEGGE}, {@code DECRETO-LEGGE},
 *                 {@code DECRETO_LEGISLATIVO}, or another code for acts outside the corpus
 * @param date     date of the act
 * @param number   act number as written, e.g. {@code 18}
 */
public record ActKey(String typeCode, LocalDate date, String number) {

    /** {@code urn:nir:stato:decreto.legge:2020-03-17;18#2} (the part after {@code #} is ignored). */
    private static final Pattern URN = Pattern.compile(
            "^urn:nir:[^:]+:([a-z.]+):(\\d{4}-\\d{2}-\\d{2});([0-9]+[A-Za-z\\-]*)");
    /** {@code /akn/it/act/decretoLegge/stato/2020-03-17/18/!main}. */
    private static final Pattern AKN = Pattern.compile(
            "/akn/it/act/([^/]+)/[^/]+/(\\d{4}-\\d{2}-\\d{2})/([0-9]+[A-Za-z\\-]*)");

    private static final Map<String, String> CODES = Map.of(
            "LEGGE", "LEGGE",
            "DECRETOLEGGE", "DECRETO-LEGGE",
            "DECRETOLEGISLATIVO", "DECRETO_LEGISLATIVO");

    /** Normalises the spellings seen in Normattiva ({@code decreto.legge}, {@code decretoLegge}, {@code DECRETO-LEGGE}). */
    public static String typeCode(String spelling) {
        String letters = spelling.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        return CODES.getOrDefault(letters, spelling.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_"));
    }

    public static Optional<ActKey> fromUrn(String urn) {
        return match(URN, urn);
    }

    public static Optional<ActKey> fromAknHref(String href) {
        return match(AKN, href);
    }

    private static Optional<ActKey> match(Pattern pattern, String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher m = pattern.matcher(value.trim());
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ActKey(typeCode(m.group(1)), LocalDate.parse(m.group(2)), m.group(3)));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Short form used in reports, e.g. {@code DECRETO-LEGGE 2020-03-17 n. 18}. */
    @Override
    public String toString() {
        return typeCode + " " + date + " n. " + number;
    }
}
