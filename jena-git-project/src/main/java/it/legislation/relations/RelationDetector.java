package it.legislation.relations;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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
 * D.L. …", "l'abrogazione del D.L. …") are not counted for Y. A relation is
 * level A when Y is imported and Y's own lifecycle names X as one of the acts
 * that changed it (two independent statements); otherwise level B.
 *
 * <p><b>Conversion</b> of decree-law D by law L, rules C1–C6 (docs/akn-analysis.md):
 * C1 types, C2 a note of L says "la conversione … del D.L. ⟨D⟩", C3 Article 1 of
 * L says D "è convertito in legge" (or L's title says so), C4 D resolves to
 * exactly one imported act, C5 L published within 60 days of D (Constitution,
 * art. 77), C6 no other law passes for D.
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
    public record Relation(Act from, ActKey fromKey, Property property, Act to, ActKey toKey, String trust,
                           int textualMods, boolean confirmedByLifecycle, Map<Kind, Integer> kinds,
                           List<String> exampleNotes, String sourceFile) {}

    /** Outcome for one decree-law. */
    public enum ConversionStatus {
        /** C1–C6 pass. */
        CONVERTED,
        /** C1, C4, C5, C6 pass and only one of C2 / C3. */
        CONVERTED_ONE_SOURCE,
        /** A candidate law exists but a check fails. */
        REVIEW,
        /** No conversion law, and the 60 days are not over yet. */
        PENDING,
        /** No conversion law, and more than 60 days have passed. */
        NOT_CONVERTED
    }

    /** The checks for one (law, decree) pair. */
    public record Checks(boolean c1, boolean c2, boolean c3, boolean c4, boolean c5, boolean c6, long days) {
        boolean core() {
            return c1 && c4 && c5;
        }
    }

    /** Result for one decree-law. {@code law}/{@code checks} are null when no candidate law was found. */
    public record Conversion(Act decree, ConversionStatus status, Act law, Checks checks,
                             int otherCandidates, String decreeNoteNamesLaw, List<String> repealedBy) {}

    public record Result(List<Relation> relations, List<Conversion> conversions, int textualMods,
                         int indirectMods, int unresolvedDestinations) {}

    private static final Pattern TITLE_CONVERTS = Pattern.compile(
            "Conversione in legge(?:,? con modificazioni,?)?(?:,? del decreto-legge| del D\\.L\\.)\\s+(\\d{1,2}\\s+[a-z]+\\s+\\d{4},?\\s+n\\.\\s?\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DECREE_NOTE = Pattern.compile(
            "convertit[oa]\\s+(?:con|senza)\\s+modificazioni\\s+dalla\\s+(L\\.\\s+\\d{1,2}\\s+[a-z]+\\s+\\d{4},?\\s+n\\.\\s?\\d+)",
            Pattern.CASE_INSENSITIVE);

    private final CorpusIndex corpus;

    public RelationDetector(CorpusIndex corpus) {
        this.corpus = corpus;
    }

    /**
     * @param original evidence from each act's original text (textual modifications, Article 1)
     * @param latest   evidence from each act's latest version (lifecycle)
     * @param today    reference date for "60 days are over"
     */
    public Result detect(Map<String, Evidence> original, Map<String, Evidence> latest, LocalDate today) {
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
            for (TextualMod mod : evidence.textualMods()) {
                total++;
                if (mod.destination() == null) {
                    unresolved++;
                    continue;
                }
                if (mod.destination().equals(from.key())) {
                    continue; // an act changing itself
                }
                ModificationNote note = ModificationNote.parse(mod.note());
                if (note.indirect() && !note.namedAct().equals(mod.destination())) {
                    indirect++;
                    continue;
                }
                if (note.kind() == Kind.CONVERSION) {
                    continue; // handled by the conversion rules
                }
                // "l'abrogazione del D.L. …" naming the destination itself repeals the whole act.
                boolean whole = note.wholeAct() || note.indirect();
                Property property = note.kind() == Kind.REPEAL && whole ? Property.REPEALS
                        : note.kind() == Kind.OTHER ? Property.CHANGES : Property.AMENDS;
                Act to = corpus.resolve(mod.destination()).orElse(null);
                String id = from.codice() + "|" + mod.destination();
                Builder b = builders.computeIfAbsent(id, k -> new Builder(from, from.key(), to, mod.destination(),
                        from.originalFile()));
                b.add(property, note.kind(), mod.note());
            }
        }

        List<Relation> relations = new ArrayList<>();
        Set<String> fromTextual = new LinkedHashSet<>();
        for (Builder b : builders.values()) {
            boolean confirmed = b.to != null && changedBy.getOrDefault(b.to.codice(), Set.of()).contains(b.fromKey);
            Relation relation = b.build(confirmed ? "A" : "B", confirmed);
            relations.add(relation);
            fromTextual.add(b.fromKey + "|" + b.toKey);
        }
        // Lifecycle only: Y says X changed it, X's text has no textualMod for Y (or X is not imported).
        for (Act to : corpus.acts()) {
            for (ActKey fromKey : changedBy.getOrDefault(to.codice(), Set.of())) {
                if (fromKey.equals(to.key()) || fromTextual.contains(fromKey + "|" + to.key())) {
                    continue;
                }
                Act from = corpus.resolve(fromKey).orElse(null);
                relations.add(new Relation(from, fromKey, Property.CHANGES, to, to.key(), "B", 0, true,
                        Map.of(), List.of(), to.latestFile()));
            }
        }

        List<Conversion> conversions = conversions(original, today, relations);
        return new Result(relations, conversions, total, indirect, unresolved);
    }

    private List<Conversion> conversions(Map<String, Evidence> original, LocalDate today, List<Relation> relations) {
        // C2 and C3 statements of each law.
        Map<String, Set<ActKey>> c2 = new LinkedHashMap<>();
        Map<String, Set<ActKey>> c3 = new LinkedHashMap<>();
        for (Act law : corpus.acts()) {
            if (!"LEGGE".equals(law.key().typeCode())) {
                continue;
            }
            Evidence evidence = original.get(law.codice());
            Set<ActKey> notes = new LinkedHashSet<>();
            Set<ActKey> text = new LinkedHashSet<>();
            if (evidence != null) {
                for (TextualMod mod : evidence.textualMods()) {
                    ModificationNote note = ModificationNote.parse(mod.note());
                    if (note.kind() == Kind.CONVERSION) {
                        if (note.namedAct() != null) {
                            notes.add(note.namedAct());
                        } else if (mod.destination() != null && "DECRETO-LEGGE".equals(mod.destination().typeCode())) {
                            notes.add(mod.destination()); // "la conversione" of the destination itself
                        }
                    }
                }
                text.addAll(evidence.article1Converts());
            }
            Matcher title = TITLE_CONVERTS.matcher(law.title());
            if (title.find()) {
                ModificationNote.namedAct("D.L. " + title.group(1)).ifPresent(text::add);
            }
            c2.put(law.codice(), notes);
            c3.put(law.codice(), text);
        }

        Map<String, List<String>> repealedBy = new LinkedHashMap<>();
        for (Relation r : relations) {
            if (r.property() == Property.REPEALS && r.to() != null && r.from() != null) {
                repealedBy.computeIfAbsent(r.to().codice(), k -> new ArrayList<>()).add(r.from().codice());
            }
        }

        List<Conversion> result = new ArrayList<>();
        for (Act decree : corpus.acts()) {
            if (!"DECRETO-LEGGE".equals(decree.key().typeCode())) {
                continue;
            }
            Map<Act, Checks> candidates = new LinkedHashMap<>();
            for (Act law : corpus.acts()) {
                boolean inC2 = c2.getOrDefault(law.codice(), Set.of()).contains(decree.key());
                boolean inC3 = c3.getOrDefault(law.codice(), Set.of()).contains(decree.key());
                if (!inC2 && !inC3) {
                    continue;
                }
                long days = ChronoUnit.DAYS.between(decree.publicationDate(), law.publicationDate());
                candidates.put(law, new Checks("LEGGE".equals(law.key().typeCode()), inC2, inC3,
                        corpus.count(decree.key()) == 1, days > 0 && days <= 60, false, days));
            }
            long passing = candidates.values().stream().filter(c -> c.core() && (c.c2() || c.c3())).count();
            String decreeNote = decreeNoteNamesLaw(original.get(decree.codice()));
            List<String> repealers = repealedBy.getOrDefault(decree.codice(), List.of());

            if (candidates.isEmpty()) {
                long age = ChronoUnit.DAYS.between(decree.publicationDate(), today);
                result.add(new Conversion(decree, age <= 60 ? ConversionStatus.PENDING : ConversionStatus.NOT_CONVERTED,
                        null, null, 0, decreeNote, repealers));
                continue;
            }
            Map.Entry<Act, Checks> best = candidates.entrySet().stream()
                    .max(Comparator.comparingInt((Map.Entry<Act, Checks> e) -> score(e.getValue()))
                            .thenComparing(e -> -e.getValue().days()))
                    .orElseThrow();
            Checks c = best.getValue();
            Checks checks = new Checks(c.c1(), c.c2(), c.c3(), c.c4(), c.c5(), passing == 1, c.days());
            ConversionStatus status = !checks.core() || !checks.c6() ? ConversionStatus.REVIEW
                    : checks.c2() && checks.c3() ? ConversionStatus.CONVERTED
                    : ConversionStatus.CONVERTED_ONE_SOURCE;
            result.add(new Conversion(decree, status, best.getKey(), checks, candidates.size() - 1, decreeNote, repealers));
        }
        return result;
    }

    private static int score(Checks c) {
        return (c.c1() ? 1 : 0) + (c.c2() ? 1 : 0) + (c.c3() ? 1 : 0) + (c.c4() ? 1 : 0) + (c.c5() ? 1 : 0);
    }

    /** The law a decree's own notes say converted it ("convertito con modificazioni dalla L. …"), if any. */
    private static String decreeNoteNamesLaw(Evidence evidence) {
        if (evidence == null) {
            return null;
        }
        for (TextualMod mod : evidence.textualMods()) {
            if (mod.note() == null) {
                continue;
            }
            Matcher m = DECREE_NOTE.matcher(mod.note());
            if (m.find()) {
                Optional<ActKey> law = ModificationNote.namedAct(m.group(1));
                if (law.isPresent()) {
                    return law.get().toString();
                }
            }
        }
        return null;
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
            if (examples.size() < 2 && note != null && examples.stream().noneMatch(note::equals)) {
                examples.add(note.length() > 300 ? note.substring(0, 300) + "…" : note);
            }
        }

        Relation build(String trust, boolean confirmed) {
            // The strongest statement wins: a repeal of the whole act includes its amendments.
            Property property = repeals ? Property.REPEALS : amends ? Property.AMENDS : Property.CHANGES;
            return new Relation(from, fromKey, property, to, toKey, trust, count, confirmed,
                    Map.copyOf(kinds), List.copyOf(examples), sourceFile);
        }
    }
}
