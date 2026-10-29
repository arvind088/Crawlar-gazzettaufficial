package it.legislation.relations;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import it.legislation.relations.AknRelationReader.Evidence;
import it.legislation.relations.AknRelationReader.TextualMod;
import it.legislation.relations.CorpusIndex.Act;
import it.legislation.relations.ModificationNote.Kind;

/**
 * Finds the relations between acts from the evidence in their Akoma Ntoso files.
 *
 * <p><b>Amends, repeals, changes.</b> Each {@code textualMod} of act X whose
 * destination is act Y gives "X changes Y". The editor's note decides which:
 * "l'abrogazione dell'intero provvedimento" → {@code eli:repeals}; any other
 * abrogazione, modifica, introduzione, soppressione or sostituzione →
 * {@code eli:amends}; a note that fits no rule → {@code eli:changes}. Notes
 * that describe the change as made through a third act ("la conversione del
 * D.L. …", "l'abrogazione del D.L. …") are not counted for Y. The history of
 * Y (lifecycle of its latest version) also names the acts that changed it: an
 * act named there with no change-list entry for Y gives "X changes Y". Each
 * relation records where it was found ({@link Relation#sources()}).
 *
 * <p><b>Conversion</b>: law L converts decree-law D when L's title says
 * "Conversione in legge[, con modificazioni,] del decreto-legge ⟨date⟩, n. ⟨number⟩"
 * and that decree-law is in the corpus. A decree-law without such a law is
 * pending for the 60 days the Constitution allows (art. 77), then not converted.
 */
public class RelationDetector {

    /** Relation property, as ELI names it. */
    public enum Property {
        AMENDS("amends"), REPEALS("repeals"), CHANGES("changes");

        public final String eliName;

        Property(String eliName) {
            this.eliName = eliName;
        }
    }

    /**
     * One relation between two acts. {@code to} is the imported act, or null
     * when the target is outside the corpus ({@code toKey} then identifies it).
     */
    public record Relation(Act from, ActKey fromKey, Property property, Act to, ActKey toKey,
                           int textualMods, boolean confirmedByLifecycle, Map<Kind, Integer> kinds,
                           List<String> exampleNotes, String sourceFile) {

        /** Where the relation was found: "change list + history", "change list" or "history". */
        public String sources() {
            return textualMods > 0 ? (confirmedByLifecycle ? "change list + history" : "change list") : "history";
        }
    }

    /** Outcome for one decree-law. */
    public enum ConversionStatus {
        /** A law's title says it converts this decree-law. */
        CONVERTED,
        /** No conversion law yet, and the 60 days are not over. */
        PENDING,
        /** No conversion law, and more than 60 days have passed. */
        NOT_CONVERTED
    }

    /**
     * Result for one decree-law. {@code law} is the converting law in the corpus, or null; {@code listedLaw}
     * describes a converting law Normattiva lists but whose text is not imported yet; {@code days} is the time
     * from the decree's publication to the law's (-1 when unknown).
     */
    public record Conversion(Act decree, ConversionStatus status, Act law, String listedLaw, long days) {}

    public record Result(List<Relation> relations, List<Conversion> conversions, int textualMods,
                         int indirectMods, int unresolvedDestinations) {}

    /**
     * "Conversione in legge[, con/senza modificazioni,] del decreto-legge 1° marzo 2022, n. 17". The day may
     * be written "1°", and one Gazzetta title has the typing error "3l" (letter l) for 31.
     */
    private static final Pattern TITLE_CONVERTS = Pattern.compile(
            "Conversione in legge(?:,? (?:con|senza) modificazioni,?)?(?:,? del decreto-legge| del D\\.L\\.)\\s+"
                    + "([0-9l]{1,2})°?\\s+([a-z]+)\\s+(\\d{4}),?\\s+n\\.\\s?(\\d+)",
            Pattern.CASE_INSENSITIVE);

    private final CorpusIndex corpus;
    private Map<ActKey, String> listed = Map.of();

    public RelationDetector(CorpusIndex corpus) {
        this.corpus = corpus;
    }

    /**
     * @param original evidence from each act's original text (textual modifications, Article 1)
     * @param latest   evidence from each act's latest version (lifecycle)
     * @param today    reference date for "60 days are over"
     */
    public Result detect(Map<String, Evidence> original, Map<String, Evidence> latest, LocalDate today) {
        return detect(original, latest, today, Map.of());
    }

    /**
     * @param listedConversionLaws decree-law → description of a law Normattiva lists as converting it
     *                             but that was not imported (its text is not exported yet)
     */
    public Result detect(Map<String, Evidence> original, Map<String, Evidence> latest, LocalDate today,
                         Map<ActKey, String> listedConversionLaws) {
        this.listed = listedConversionLaws;
        // Lifecycle: which acts each imported act says changed it.
        Map<String, Set<ActKey>> changedBy = new LinkedHashMap<>();
        latest.forEach((codice, evidence) -> changedBy.put(codice, evidence.lifecycleChangedBy()));

        Map<String, Builder> builders = new LinkedHashMap<>();
        int total = 0;
        int indirect = 0;
        int unresolved = 0;
        for (Act from : corpus.acts()) {
            Evidence evidence = original.get(from.codice());
            if (evidence == null) {
                continue;
            }
            for (TextualMod mod : changeList(evidence, latest.get(from.codice()))) {
                total++;
                if (mod.destination() == null) {
                    unresolved++;
                    continue;
                }
                if (mod.destination().equals(from.key())) {
                    continue; // an act changing itself
                }
                Kind kind;
                boolean whole;
                if (mod.note() == null) {
                    // Newer format: no note, the type attribute is meaningful.
                    kind = switch (mod.type() == null ? "" : mod.type()) {
                        case "repeal" -> Kind.REPEAL;
                        case "insertion" -> Kind.INSERTION;
                        case "substitution", "split", "join" -> Kind.REPLACEMENT;
                        default -> Kind.OTHER;
                    };
                    whole = mod.wholeAct();
                } else {
                    ModificationNote note = ModificationNote.parse(mod.note());
                    if (note.indirect() && !note.namedAct().equals(mod.destination())) {
                        indirect++;
                        continue;
                    }
                    if (note.kind() == Kind.CONVERSION) {
                        continue; // handled by the conversion rules
                    }
                    kind = note.kind();
                    // "l'abrogazione del D.L. …" naming the destination itself repeals the whole act.
                    whole = note.wholeAct() || note.indirect();
                }
                Property property = kind == Kind.REPEAL && whole ? Property.REPEALS
                        : kind == Kind.OTHER ? Property.CHANGES : Property.AMENDS;
                Act to = corpus.resolve(mod.destination()).orElse(null);
                String id = from.codice() + "|" + mod.destination();
                Builder b = builders.computeIfAbsent(id, k -> new Builder(from, from.key(), to, mod.destination(),
                        from.originalFile()));
                b.add(property, kind, mod.note());
            }
            // Whole repeals stated only in the text ("Il decreto-legge … e' abrogato").
            for (ActKey repealed : evidence.textRepeals()) {
                if (repealed.equals(from.key())) {
                    continue;
                }
                Act to = corpus.resolve(repealed).orElse(null);
                Builder b = builders.computeIfAbsent(from.codice() + "|" + repealed,
                        k -> new Builder(from, from.key(), to, repealed, from.originalFile()));
                b.add(Property.REPEALS, Kind.REPEAL, "testo: " + repealed + " è abrogato");
            }
        }

        List<Relation> relations = new ArrayList<>();
        Set<String> fromTextual = new LinkedHashSet<>();
        for (Builder b : builders.values()) {
            boolean confirmed = b.to != null && changedBy.getOrDefault(b.to.codice(), Set.of()).contains(b.fromKey);
            Relation relation = b.build(confirmed);
            relations.add(relation);
            fromTextual.add(b.fromKey + "|" + b.toKey);
        }
        // Lifecycle only: Y says X changed it, X's text has no textualMod for Y (or X is not imported).
        for (Act to : corpus.acts()) {
            for (ActKey fromKey : changedBy.getOrDefault(to.codice(), Set.of())) {
                if (fromKey.equals(to.key()) || fromTextual.contains(fromKey + "|" + to.key())
                        || impossibleInHistory(fromKey, to.key())) {
                    continue;
                }
                Act from = corpus.resolve(fromKey).orElse(null);
                relations.add(new Relation(from, fromKey, Property.CHANGES, to, to.key(), 0, true,
                        Map.of(), List.of(), to.latestFile()));
            }
        }

        List<Conversion> conversions = conversions(today);
        return new Result(relations, conversions, total, indirect, unresolved);
    }

    /**
     * The act's list of changes: the original's, plus the entries that appear only in the latest
     * version. A decree-law's conversion law can add articles to it ("art. 2-bis") that change
     * other acts; those entries are legally the decree's but are not in its original text.
     * Entries are the same when they name the same destination with the same note.
     */
    static List<TextualMod> changeList(Evidence original, Evidence latest) {
        List<TextualMod> mods = new ArrayList<>(original.textualMods());
        if (latest == null || latest == original) {
            return mods;
        }
        Set<String> seen = new java.util.HashSet<>();
        mods.forEach(m -> seen.add(m.destinationHref() + "|" + m.note()));
        for (TextualMod m : latest.textualMods()) {
            if (seen.add(m.destinationHref() + "|" + m.note())) {
                mods.add(m);
            }
        }
        return mods;
    }

    /**
     * R7, plausibility of a history-only relation: a law or legislative decree cannot change an act
     * published after it. (A decree-law can: its conversion law adds changes that are legally the
     * decree's, "D.L. … convertito con modificazioni dalla L. …".) Normattiva's history sometimes
     * names an act of the right number but the wrong year, e.g. D.L. 23 novembre 2022, n. 179 lists
     * L. 21 gennaio 2022, n. 6 with an event dated before the decree existed.
     */
    static boolean impossibleInHistory(ActKey from, ActKey to) {
        return from.date() != null && to.date() != null && from.date().isBefore(to.date())
                && !"DECRETO-LEGGE".equals(from.typeCode());
    }

    /**
     * Conversion by title: each law whose title says "Conversione in legge … del decreto-legge ⟨date⟩, n. ⟨n⟩"
     * converts that decree-law, when it is in the corpus; Normattiva's list of laws whose text is not exported
     * yet is used the same way. The other decree-laws are pending for 60 days, then not converted.
     */
    private List<Conversion> conversions(LocalDate today) {
        Map<String, Act> lawOf = new LinkedHashMap<>();
        for (Act law : corpus.acts()) {
            if (!"LEGGE".equals(law.key().typeCode())) {
                continue;
            }
            convertedDecree(law.title()).flatMap(corpus::resolve).ifPresent(decree ->
                    lawOf.merge(decree.codice(), law, (a, b) -> a.publicationDate().isBefore(b.publicationDate()) ? a : b));
        }
        List<Conversion> result = new ArrayList<>();
        for (Act decree : corpus.acts()) {
            if (!"DECRETO-LEGGE".equals(decree.key().typeCode())) {
                continue;
            }
            Act law = lawOf.get(decree.codice());
            if (law != null) {
                result.add(new Conversion(decree, ConversionStatus.CONVERTED, law, null,
                        ChronoUnit.DAYS.between(decree.publicationDate(), law.publicationDate())));
            } else if (listed.containsKey(decree.key())) {
                result.add(new Conversion(decree, ConversionStatus.CONVERTED, null, listed.get(decree.key()), -1));
            } else {
                long age = ChronoUnit.DAYS.between(decree.publicationDate(), today);
                result.add(new Conversion(decree, age <= 60 ? ConversionStatus.PENDING : ConversionStatus.NOT_CONVERTED,
                        null, null, -1));
            }
        }
        return result;
    }

    /**
     * The decree-law a law's title says it converts: "Conversione in legge[, con modificazioni,] del
     * decreto-legge 17 marzo 2020, n. 18" → DECRETO-LEGGE 2020-03-17 n. 18.
     */
    public static Optional<ActKey> convertedDecree(String title) {
        if (title == null) {
            return Optional.empty();
        }
        Matcher m = TITLE_CONVERTS.matcher(AknRelationReader.clean(title));
        if (!m.find()) {
            return Optional.empty();
        }
        String day = m.group(1).replace('l', '1').replace('L', '1');
        return ModificationNote.namedAct("D.L. " + day + " " + m.group(2) + " " + m.group(3) + ", n. " + m.group(4));
    }

    /** Collects the textual modifications of one (from, to) pair. */
    private static final class Builder {
        final Act from;
        final ActKey fromKey;
        final Act to;
        final ActKey toKey;
        final String sourceFile;
        final Map<Kind, Integer> kinds = new EnumMap<>(Kind.class);
        final List<String> examples = new ArrayList<>();
        boolean repeals;
        boolean amends;
        int count;

        Builder(Act from, ActKey fromKey, Act to, ActKey toKey, String sourceFile) {
            this.from = from;
            this.fromKey = fromKey;
            this.to = to;
            this.toKey = toKey;
            this.sourceFile = sourceFile;
        }

        void add(Property property, Kind kind, String note) {
            count++;
            kinds.merge(kind, 1, Integer::sum);
            repeals |= property == Property.REPEALS;
            amends |= property == Property.AMENDS;
            // Every distinct editor's note (up to 30), so the pages can say what changed.
            if (examples.size() < 30 && note != null && !note.isBlank()) {
                String kept = note.length() > 600 ? note.substring(0, 600) + "…" : note;
                if (examples.stream().noneMatch(kept::equals)) {
                    examples.add(kept);
                }
            }
        }

        Relation build(boolean confirmed) {
            // The strongest statement wins: a repeal of the whole act includes its amendments.
            Property property = repeals ? Property.REPEALS : amends ? Property.AMENDS : Property.CHANGES;
            return new Relation(from, fromKey, property, to, toKey, count, confirmed,
                    Map.copyOf(kinds), List.copyOf(examples), sourceFile);
        }
    }
}
