package it.legislation.relations;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the Normattiva editor's note of one {@code textualMod} says.
 *
 * <p>The note follows a formula: "ha disposto (con l'art. 1, comma 1) la
 * modifica dell'art. 2". The {@code type} attribute of {@code textualMod} is
 * always "insertion", so the kind of change is read from the note. The note
 * either describes a change to the destination act itself ("la modifica
 * dell'art. 2", "l'abrogazione dell'intero provvedimento"), or names a third
 * act ("la conversione del D.L. 17 marzo 2020, n. 18", "l'abrogazione del
 * D.L. 2 marzo 2020, n. 9"): then the destination was only affected
 * indirectly, through that act.
 *
 * @param kind      the change the note describes
 * @param wholeAct  "dell'intero provvedimento": the whole destination act
 * @param namedAct  the third act the note is about, when it names one
 */
public record ModificationNote(Kind kind, boolean wholeAct, ActKey namedAct) {

    /** Kinds of change, from the verb after "ha disposto (con l'art. …)". */
    public enum Kind {
        CONVERSION, REPEAL, AMENDMENT, INSERTION, DELETION, REPLACEMENT, OTHER
    }

    private static final Pattern VERB = Pattern.compile(
            "ha disposto\\s*\\((?:[^()]|\\([^()]*\\))*\\)\\)?\\s*(?:la |l'|l |il |lo |le )?(conversione|abrogazion\\w*|modifica|introduzione|soppressione|sostituzione)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REPLACED = Pattern.compile("[eè]'?\\s+sostituit[aoie]", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_MODIFICA = Pattern.compile("\\bmodific", Pattern.CASE_INSENSITIVE);

    /** "del D.L. 17 marzo 2020, n. 18", "della L. 24 aprile 2020, n. 27", "del D.Lgs. 30 giugno 2003, n. 196". */
    static final Pattern NAMED_ACT = Pattern.compile(
            "(D\\.\\s?L\\.|D\\.\\s?Lgs\\.?|L\\.)\\s+(\\d{1,2})\\s+([a-z]+)\\s+(\\d{4}),?\\s+n\\.\\s?(\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VERB_THEN_ACT = Pattern.compile(
            "(conversione|abrogazione)(?:,?\\s+(?:con|senza)\\s+modificazioni,?)?\\s+(?:del|della|dello)\\s+(D\\.\\s?L\\.|D\\.\\s?Lgs\\.?|L\\.)\\s",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("gennaio", 1), Map.entry("febbraio", 2), Map.entry("marzo", 3), Map.entry("aprile", 4),
            Map.entry("maggio", 5), Map.entry("giugno", 6), Map.entry("luglio", 7), Map.entry("agosto", 8),
            Map.entry("settembre", 9), Map.entry("ottobre", 10), Map.entry("novembre", 11), Map.entry("dicembre", 12));

    public static ModificationNote parse(String note) {
        if (note == null) {
            return new ModificationNote(Kind.OTHER, false, null);
        }
        String text = note.replaceAll("\\s+", " ");
        Matcher verb = VERB.matcher(text);
        Kind kind;
        int afterVerb;
        if (verb.find()) {
            kind = switch (verb.group(1).toLowerCase(Locale.ROOT)) {
                case "conversione" -> Kind.CONVERSION;
                case "modifica" -> Kind.AMENDMENT;
                case "introduzione" -> Kind.INSERTION;
                case "soppressione" -> Kind.DELETION;
                case "sostituzione" -> Kind.REPLACEMENT;
                default -> Kind.REPEAL; // "abrogazione", also the misspelling "abrogazionme"
            };
            afterVerb = verb.start(1);
        } else if (REPLACED.matcher(text).find()) {
            kind = Kind.REPLACEMENT;
            afterVerb = -1;
        } else if (ANY_MODIFICA.matcher(text).find()) {
            kind = Kind.AMENDMENT;
            afterVerb = -1;
        } else {
            kind = Kind.OTHER;
            afterVerb = -1;
        }

        ActKey named = null;
        if (afterVerb >= 0) {
            Matcher third = VERB_THEN_ACT.matcher(text);
            if (third.find(afterVerb) && third.start() == afterVerb) {
                named = namedAct(text.substring(third.start(2))).orElse(null);
            }
        }
        boolean whole = afterVerb >= 0 && text.substring(afterVerb).matches("(?is)^\\w+\\s+dell'intero provvedimento.*");
        return new ModificationNote(kind, whole, named);
    }

    /** First act cited as "D.L. 17 marzo 2020, n. 18" in a text. */
    public static Optional<ActKey> namedAct(String text) {
        Matcher m = NAMED_ACT.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        Integer month = MONTHS.get(m.group(3).toLowerCase(Locale.ROOT));
        if (month == null) {
            return Optional.empty();
        }
        String abbreviation = m.group(1).replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        String type = abbreviation.startsWith("D.LGS") ? "DECRETO_LEGISLATIVO"
                : abbreviation.startsWith("D.L") ? "DECRETO-LEGGE" : "LEGGE";
        try {
            return Optional.of(new ActKey(type,
                    LocalDate.of(Integer.parseInt(m.group(4)), month, Integer.parseInt(m.group(2))), m.group(5)));
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }
    }

    /** True when the change was made through another act named in the note, not directly. */
    public boolean indirect() {
        return namedAct != null;
    }
}
