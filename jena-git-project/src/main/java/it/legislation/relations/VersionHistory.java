package it.legislation.relations;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.legislation.relations.AknRelationReader.Evidence;
import it.legislation.relations.AknRelationReader.LifecycleEvent;
import it.legislation.relations.CorpusIndex.Act;
import it.legislation.relations.CorpusIndex.Version;
import it.legislation.relations.RelationDetector.Relation;

/**
 * Links each consolidated version of an act to the act or acts that produced it
 * (the history rules found by the survey of the corpus).
 *
 * <ul>
 *   <li><b>H1</b>: the act's history ({@code lifecycle} of its latest file) lists events: on a date,
 *       an act changed it.</li>
 *   <li><b>H2</b>: each event belongs to the first consolidated version that starts on or after its
 *       date. (Survey: same day 15 %, next day 63 %.) A version can have several events, or none.</li>
 *   <li><b>H3</b>, evidence: the version starts on the day the changing act came into force (from its
 *       Gazzetta Ufficiale page), the usual case (75 % in the survey).</li>
 *   <li><b>H4</b>, evidence: the changing act's own change list ({@code activeModifications}) names this
 *       act, i.e. a relation with textual modifications exists.</li>
 *   <li><b>H5</b>: events without an act, events of the act on itself, events that cannot be true (a law
 *       older than the act, see R7) and events after the last version are not linked, only counted.</li>
 * </ul>
 */
public final class VersionHistory {

    /**
     * One link: {@code version} of {@code act} was produced by the act {@code changedBy} (in the corpus as
     * {@code modifier}, or outside it). {@code onEntryIntoForce} is null when the changing act's entry
     * into force is not known.
     */
    public record Link(Act act, Version version, ActKey changedBy, Act modifier, LocalDate eventDate,
                       long daysAfterEvent, Boolean onEntryIntoForce, boolean confirmedByChangeList) {}

    /** All links, with counts for the report. */
    public record Result(List<Link> links, int versions, int versionsWithCause, int events, int eventsWithoutAct,
                         int eventsNotPlausible, int eventsAfterLastVersion) {}

    private VersionHistory() {
    }

    /**
     * @param latest         evidence from each act's latest file, by redactional code
     * @param relations      relations found by {@link RelationDetector} (for H4)
     * @param entryIntoForce Work URI → entry into force (Gazzetta Ufficiale check), may be empty
     */
    public static Result link(CorpusIndex corpus, Map<String, Evidence> latest, List<Relation> relations,
                              Map<String, LocalDate> entryIntoForce) {
        Set<String> changeLists = new HashSet<>();
        for (Relation r : relations) {
            if (r.from() != null && r.to() != null && r.textualMods() > 0) {
                changeLists.add(r.from().codice() + "|" + r.to().codice());
            }
        }
        List<Link> links = new ArrayList<>();
        int versionCount = 0;
        int withCause = 0;
        int events = 0;
        int withoutAct = 0;
        int notPlausible = 0;
        int afterLast = 0;
        for (Act act : corpus.acts()) {
            List<Version> consolidated = corpus.versions(act.codice()).stream().filter(v -> !v.original()).toList();
            versionCount += consolidated.size();
            Evidence evidence = latest.get(act.codice());
            if (evidence == null || consolidated.isEmpty()) {
                continue;
            }
            Set<String> caused = new HashSet<>();
            Set<String> seen = new HashSet<>();
            for (LifecycleEvent event : evidence.events()) {
                events++;
                if (event.act().isEmpty()) {
                    withoutAct++;
                    continue;
                }
                ActKey key = event.act().get();
                if (key.equals(act.key())) {
                    continue;
                }
                if (RelationDetector.impossibleInHistory(key, act.key()) || event.date().isBefore(act.key().date())) {
                    notPlausible++;
                    continue;
                }
                Version version = consolidated.stream().filter(v -> !v.date().isBefore(event.date())).findFirst()
                        .orElse(null);
                if (version == null) {
                    afterLast++;
                    continue;
                }
                if (!seen.add(version.uri() + "|" + key)) {
                    continue; // the same act named twice for one version
                }
                Act modifier = corpus.resolve(key).orElse(null);
                LocalDate inForce = modifier == null ? null : entryIntoForce.get(modifier.workUri());
                links.add(new Link(act, version, key, modifier, event.date(),
                        ChronoUnit.DAYS.between(event.date(), version.date()),
                        inForce == null ? null : inForce.equals(version.date()),
                        modifier != null && changeLists.contains(modifier.codice() + "|" + act.codice())));
                caused.add(version.uri());
            }
            withCause += caused.size();
        }
        return new Result(links, versionCount, withCause, events, withoutAct, notPlausible, afterLast);
    }
}
