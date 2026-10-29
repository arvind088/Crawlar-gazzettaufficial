package it.legislation.ingest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import it.legislation.relations.ActKey;
import it.legislation.relations.AknRelationReader;
import it.legislation.relations.AknRelationReader.Evidence;
import it.legislation.relations.CorpusIndex;
import it.legislation.relations.CorpusIndex.Act;
import it.legislation.relations.RelationDetector;
import it.legislation.relations.RelationDetector.Conversion;
import it.legislation.relations.RelationDetector.ConversionStatus;
import it.legislation.relations.RelationDetector.Relation;
import it.legislation.relations.VersionHistory;

/**
 * Finds which act modifies which (R6). See {@link RelationDetector} for the rules
 * and {@link VersionHistory} for the link between a version and the act that produced it.
 *
 * <p>Reads the acts and versions from the two layers ({@code data/rdf/layer1_acts.ttl},
 * {@code data/rdf/layer2_versions.ttl}) and their Akoma Ntoso files from
 * {@code data/raw/normattiva}. Writes {@code data/rdf/relations.ttl}, ELI only:
 * <ul>
 *   <li>{@code X eli:amends | eli:repeals | eli:changes Y}: act X modifies act Y;</li>
 *   <li>{@code version eli:consolidates X}: this consolidated version of Y contains the changes made by X;</li>
 *   <li>acts outside the corpus that are named, as {@code eli:LegalResource} with type, date and number.</li>
 * </ul>
 * A conversion is the law's relation to its decree-law (its title says "Conversione in legge …").
 * Reports for checking and for the thesis: {@code data/clean/relations.tsv} (one line per relation,
 * with where it was found), {@code data/clean/conversions.tsv} (one line per decree-law) and
 * {@code data/clean/version_history.tsv} (one line per version link, with its evidence).
 *
 * <pre>
 * mvn -B compile exec:java "-Dexec.mainClass=it.legislation.ingest.RelationRunner"
 * </pre>
 *
 * Options: {@code --acts <layer1>,<layer2>}, {@code --in}, {@code --out}, {@code --conversions},
 * {@code --relations}, {@code --today yyyy-mm-dd} (reference date for the 60 days).
 */
public class RelationRunner {

    public static final Path DEFAULT_OUT = Path.of("data", "rdf", "relations.ttl");
    public static final Path DEFAULT_CONVERSIONS = Path.of("data", "clean", "conversions.tsv");
    public static final Path DEFAULT_RELATIONS = Path.of("data", "clean", "relations.tsv");

    static final String ELI = "http://data.europa.eu/eli/ontology#";
    static final String RESOURCE_TYPE = "http://www.gazzettaufficiale.it/eli/tables/resource-type#";

    public record Summary(int acts, int filesRead, List<String> unreadable, RelationDetector.Result result,
                          VersionHistory.Result history, boolean written) {}

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        List<Path> acts = options.containsKey("--acts")
                ? java.util.Arrays.stream(options.get("--acts").split(",")).map(String::trim).map(Path::of).toList()
                : AknImportRunner.LAYERS;
        Path in = Path.of(options.getOrDefault("--in", AknImportRunner.DEFAULT_IN.toString()));
        Path out = Path.of(options.getOrDefault("--out", DEFAULT_OUT.toString()));
        Path conversions = Path.of(options.getOrDefault("--conversions", DEFAULT_CONVERSIONS.toString()));
        Path relations = Path.of(options.getOrDefault("--relations", DEFAULT_RELATIONS.toString()));
        LocalDate today = LocalDate.parse(options.getOrDefault("--today", LocalDate.now().toString()));

        Summary summary = new RelationRunner().run(acts, in, out, conversions, relations, today);
        RelationDetector.Result r = summary.result();

        System.out.println("Acts:                 " + summary.acts() + "  (files read: " + summary.filesRead() + ")");
        summary.unreadable().forEach(u -> System.out.println("  UNREADABLE " + u));
        System.out.println("Textual modifications: " + r.textualMods()
                + "  (through a third act, not counted: " + r.indirectMods()
                + "; destination not understood: " + r.unresolvedDestinations() + ")");
        Map<String, Integer> byProperty = new TreeMap<>();
        r.relations().forEach(rel -> byProperty.merge(rel.property().eliName + " (" + rel.sources() + ")"
                + (rel.to() == null ? " (target outside corpus)" : rel.from() == null ? " (source outside corpus)" : ""),
                1, Integer::sum));
        System.out.println("Relations between acts: " + r.relations().size());
        byProperty.forEach((k, v) -> System.out.println("  " + pad(k, 50) + v));
        Map<ConversionStatus, Integer> byStatus = new EnumMap<>(ConversionStatus.class);
        r.conversions().forEach(c -> byStatus.merge(c.status(), 1, Integer::sum));
        System.out.println("Decree-laws: " + r.conversions().size());
        byStatus.forEach((k, v) -> System.out.println("  " + pad(k.name(), 22) + v));
        r.conversions().stream().filter(c -> c.law() == null && c.listedLaw() != null)
                .forEach(c -> System.out.println("  converted by a law not imported yet: " + c.decree().key()
                        + " -> " + c.listedLaw()));
        VersionHistory.Result h = summary.history();
        long inForce = h.links().stream().filter(l -> Boolean.TRUE.equals(l.onEntryIntoForce())).count();
        long inForceKnown = h.links().stream().filter(l -> l.onEntryIntoForce() != null).count();
        long confirmed = h.links().stream().filter(VersionHistory.Link::confirmedByChangeList).count();
        long inCorpus = h.links().stream().filter(l -> l.modifier() != null).count();
        System.out.println("Version history: " + h.versionsWithCause() + " of " + h.versions()
                + " consolidated versions linked to the act(s) that produced them (" + h.links().size() + " links)");
        System.out.println("  starts on the changing act's entry into force: " + inForce + " of " + inForceKnown
                + "; confirmed by its change list: " + confirmed + " of " + inCorpus + " (changing act in corpus)");
        System.out.println("  events not linked: " + h.eventsWithoutAct() + " without an act, " + h.eventsNotPlausible()
                + " not plausible, " + h.eventsAfterLastVersion() + " after the last version (of " + h.events() + ")");
        System.out.println(summary.written() ? "Wrote " + out + ", " + conversions + ", " + relations
                : "No change: " + out + " is already up to date");
    }

    public Summary run(List<Path> acts, Path in, Path out, Path conversionsReport, Path relationsReport, LocalDate today)
            throws IOException {
        CorpusIndex corpus = CorpusIndex.read(acts);
        AknRelationReader reader = new AknRelationReader();
        Map<String, Evidence> original = new LinkedHashMap<>();
        Map<String, Evidence> latest = new LinkedHashMap<>();
        List<String> unreadable = new java.util.ArrayList<>();
        int files = 0;
        for (Act act : corpus.acts()) {
            String originalFile = act.originalFile() != null ? act.originalFile() : act.latestFile();
            Evidence first = read(reader, in, originalFile, unreadable);
            files++;
            if (first != null) {
                original.put(act.codice(), first);
            }
            Evidence last = first;
            if (act.latestFile() != null && !act.latestFile().equals(originalFile)) {
                last = read(reader, in, act.latestFile(), unreadable);
                files++;
            }
            if (last != null) {
                latest.put(act.codice(), last);
            }
        }

        RelationDetector.Result result = new RelationDetector(corpus)
                .detect(original, latest, today, listedConversionLaws(in, corpus));
        VersionHistory.Result history = VersionHistory.link(corpus, latest, result.relations(),
                entryIntoForce(acts.get(0).resolveSibling(GazzettaCheckRunner.DEFAULT_OUT.getFileName().toString())));
        boolean written = writeModelIfChanged(out, toRdf(corpus, result, history));
        written |= writeIfChanged(conversionsReport, conversionsTsv(result).getBytes(StandardCharsets.UTF_8));
        written |= writeIfChanged(relationsReport, relationsTsv(result).getBytes(StandardCharsets.UTF_8));
        written |= writeIfChanged(relationsReport.resolveSibling("version_history.tsv"),
                versionHistoryTsv(history).getBytes(StandardCharsets.UTF_8));
        return new Summary(corpus.acts().size(), files, unreadable, result, history, written);
    }

    /**
     * Laws that Normattiva lists (corpus/lists/*.json, saved by NormattivaCorpusRunner) but whose text was
     * not exported, so they were not imported; when such a law's title says it converts a decree-law, the
     * decree is converted even though the law is not in the store yet.
     */
    static Map<ActKey, String> listedConversionLaws(Path in, CorpusIndex corpus) throws IOException {
        Map<ActKey, String> laws = new LinkedHashMap<>();
        Path lists = in.resolve("corpus").resolve("lists");
        if (!Files.isDirectory(lists)) {
            return laws;
        }
        java.util.Set<String> imported = new java.util.HashSet<>();
        corpus.acts().forEach(a -> imported.add(a.codice()));
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (java.util.stream.Stream<Path> files = Files.list(lists)) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith("LEGGE_")).sorted().toList()) {
                for (com.fasterxml.jackson.databind.JsonNode act : json.readTree(file.toFile())) {
                    String codice = act.path("codiceRedazionale").asText();
                    if (imported.contains(codice)) {
                        continue;
                    }
                    String title = act.path("titoloAtto").asText().replaceAll("^\\[|\\]$", "");
                    RelationDetector.convertedDecree(title).ifPresent(decree -> laws.put(decree,
                            act.path("descrizioneAtto").asText() + " (" + codice + ", GU " + act.path("dataGU").asText()
                                    + ", listed by Normattiva, text not exported yet)"));
                }
            }
        }
        return laws;
    }

    private static Evidence read(AknRelationReader reader, Path in, String file, List<String> unreadable) {
        if (file == null) {
            return null;
        }
        try {
            return reader.read(in.resolve(file));
        } catch (IOException e) {
            unreadable.add(file + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * The relations as RDF, ELI only: {@code X eli:amends|eli:repeals|eli:changes Y} for each relation,
     * {@code version eli:consolidates X} for each version link, and the acts outside the corpus that are named
     * (type, date, number). A converting law always has a relation to its decree-law: when nothing else links
     * them (conversion "senza modificazioni"), {@code eli:changes}.
     */
    static Model toRdf(CorpusIndex corpus, RelationDetector.Result result, VersionHistory.Result history) {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("eli", ELI);
        model.setNsPrefix("rdfs", RDFS.uri);
        model.setNsPrefix("xsd", XSDDatatype.XSD + "#");
        String base = corpus.baseUri();
        java.util.Set<String> linked = new java.util.HashSet<>();
        for (Relation r : result.relations()) {
            Resource source = r.from() != null ? model.createResource(r.from().workUri()) : external(model, base, r.fromKey());
            Resource target = r.to() != null ? model.createResource(r.to().workUri()) : external(model, base, r.toKey());
            source.addProperty(model.createProperty(ELI, r.property().eliName), target);
            linked.add(source.getURI() + " " + target.getURI());
        }
        for (Conversion c : result.conversions()) {
            if (c.law() != null && linked.add(c.law().workUri() + " " + c.decree().workUri())) {
                model.createResource(c.law().workUri()).addProperty(model.createProperty(ELI, "changes"),
                        model.createResource(c.decree().workUri()));
            }
        }
        Property consolidates = model.createProperty(ELI, "consolidates");
        for (VersionHistory.Link l : history.links()) {
            Resource changer = l.modifier() != null ? model.createResource(l.modifier().workUri())
                    : external(model, base, l.changedBy());
            model.createResource(l.version().uri()).addProperty(consolidates, changer);
        }
        return model;
    }

    /** Work URI → entry into force, from the Gazzetta Ufficiale check; empty when the file is missing. */
    static Map<String, LocalDate> entryIntoForce(Path gazzettaCheck) throws IOException {
        Map<String, LocalDate> dates = new java.util.HashMap<>();
        if (!Files.exists(gazzettaCheck)) {
            return dates;
        }
        Model model = ModelFactory.createDefaultModel();
        try (java.io.InputStream in = Files.newInputStream(gazzettaCheck)) {
            RDFDataMgr.read(model, in, org.apache.jena.riot.Lang.TURTLE);
        }
        model.listStatements(null, model.createProperty(ELI, "first_date_entry_in_force"), (org.apache.jena.rdf.model.RDFNode) null)
                .forEachRemaining(st -> {
                    if (st.getSubject().isURIResource() && st.getObject().isLiteral()) {
                        try {
                            dates.put(st.getSubject().getURI(), LocalDate.parse(st.getString()));
                        } catch (java.time.format.DateTimeParseException ignored) {
                            // a date that cannot be read is left out
                        }
                    }
                });
        return dates;
    }

    static String versionHistoryTsv(VersionHistory.Result history) {
        StringBuilder sb = new StringBuilder("act_codice\tact\tversion_date\tchanged_by_codice\tchanged_by\tevent_date"
                + "\tdays_after_event\tstarts_on_entry_into_force\tconfirmed_by_change_list\n");
        for (VersionHistory.Link l : history.links()) {
            sb.append(l.act().codice()).append('\t').append(l.act().key()).append('\t').append(l.version().date())
                    .append('\t').append(l.modifier() == null ? "" : l.modifier().codice()).append('\t')
                    .append(l.changedBy()).append('\t').append(l.eventDate()).append('\t').append(l.daysAfterEvent())
                    .append('\t').append(l.onEntryIntoForce() == null ? "" : l.onEntryIntoForce()).append('\t')
                    .append(l.confirmedByChangeList()).append('\n');
        }
        return sb.toString();
    }

    /** URI for an act outside the corpus, from its citation key (type, date, number). */
    private static Resource external(Model model, String base, ActKey key) {
        Resource act = model.createResource(base + "/eli/ext/" + key.typeCode().toLowerCase(Locale.ROOT)
                + "/" + key.date() + "/" + key.number());
        if (!act.hasProperty(RDF.type)) {
            act.addProperty(RDF.type, model.createResource(ELI + "LegalResource"));
            act.addProperty(RDFS.label, key.toString());
            act.addProperty(model.createProperty(ELI, "type_document"), model.createResource(RESOURCE_TYPE + key.typeCode()));
            act.addLiteral(model.createProperty(ELI, "date_document"),
                    model.createTypedLiteral(key.date().toString(), XSDDatatype.XSDdate));
            act.addProperty(model.createProperty(ELI, "number"), key.number());
        }
        return act;
    }

    private static String id(Act act, ActKey key) {
        return act != null ? act.codice()
                : key.typeCode().toLowerCase(Locale.ROOT) + "-" + key.date() + "-" + key.number();
    }

    static String conversionsTsv(RelationDetector.Result result) {
        StringBuilder sb = new StringBuilder("decree_codice\tdecree\tdecree_gu\tstatus\tlaw_codice\tlaw\tlaw_gu\tdays\n");
        for (Conversion c : result.conversions()) {
            sb.append(c.decree().codice()).append('\t').append(c.decree().key()).append('\t')
                    .append(c.decree().publicationDate()).append('\t').append(c.status()).append('\t');
            if (c.law() != null) {
                sb.append(c.law().codice()).append('\t').append(c.law().key()).append('\t')
                        .append(c.law().publicationDate()).append('\t').append(c.days());
            } else {
                sb.append('\t').append(c.listedLaw() == null ? "" : c.listedLaw()).append("\t\t");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Between the editor's notes in the {@code notes} column of the relations report. */
    public static final String NOTE_SEPARATOR = " ¶ ";

    static String relationsTsv(RelationDetector.Result result) {
        StringBuilder sb = new StringBuilder("from_codice\tfrom\tproperty\tto_codice\tto\tsources\ttextual_mods"
                + "\tconfirmed_by_lifecycle\tkinds\texample_note\tnotes\n");
        for (Relation r : result.relations()) {
            sb.append(r.from() == null ? "" : r.from().codice()).append('\t').append(r.fromKey()).append('\t')
                    .append(r.property().eliName).append('\t')
                    .append(r.to() == null ? "" : r.to().codice()).append('\t').append(r.toKey()).append('\t')
                    .append(r.sources()).append('\t').append(r.textualMods()).append('\t')
                    .append(r.confirmedByLifecycle()).append('\t').append(new TreeMap<>(r.kinds())).append('\t')
                    .append(r.exampleNotes().isEmpty() ? "" : r.exampleNotes().get(0).replace('\t', ' ')).append('\t')
                    .append(String.join(NOTE_SEPARATOR, r.exampleNotes().stream()
                            .map(n -> n.replace('\t', ' ').replace('\n', ' ')).toList())).append('\n');
        }
        return sb.toString();
    }

    private static byte[] turtle(Model model) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RDFDataMgr.write(bytes, model, RDFFormat.TURTLE_PRETTY);
        return bytes.toByteArray();
    }

    /**
     * Writes the model unless the file already holds the same graph. Compared as RDF, not as bytes:
     * a graph read back from a file can be serialised in a different order than the one it was built in.
     */
    static boolean writeModelIfChanged(Path file, Model model) throws IOException {
        if (Files.exists(file)) {
            Model existing = ModelFactory.createDefaultModel();
            try (java.io.InputStream in = Files.newInputStream(file)) {
                RDFDataMgr.read(existing, in, org.apache.jena.riot.Lang.TURTLE);
            }
            if (existing.isIsomorphicWith(model)) {
                return false;
            }
        }
        return writeIfChanged(file, turtle(model));
    }

    private static boolean writeIfChanged(Path file, byte[] content) throws IOException {
        if (Files.exists(file) && Arrays.equals(Files.readAllBytes(file), content)) {
            return false;
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, content);
        return true;
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }
}
