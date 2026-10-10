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
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.OWL;
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

/**
 * Finds the relations between the imported acts (R6): which law converts
 * which decree-law, and which act amends, repeals or changes which other act,
 * each with its evidence and trust level. See {@link RelationDetector} for the rules.
 *
 * <p>Reads the imported acts from {@code data/rdf/normattiva_akn.ttl} and their
 * Akoma Ntoso files from {@code data/raw/normattiva}. Writes
 * {@code data/rdf/relations.ttl} (loaded into the store), and two reports:
 * {@code data/clean/conversions.tsv} (one line per decree-law) and
 * {@code data/clean/relations.tsv} (one line per relation).
 *
 * <pre>
 * mvn -B compile exec:java "-Dexec.mainClass=it.legislation.ingest.RelationRunner"
 * </pre>
 *
 * Options: {@code --acts}, {@code --in}, {@code --out}, {@code --conversions},
 * {@code --relations}, {@code --today yyyy-mm-dd} (reference date for the 60 days).
 */
public class RelationRunner {

    public static final Path DEFAULT_OUT = Path.of("data", "rdf", "relations.ttl");
    public static final Path DEFAULT_CONVERSIONS = Path.of("data", "clean", "conversions.tsv");
    public static final Path DEFAULT_RELATIONS = Path.of("data", "clean", "relations.tsv");

    static final String ELI = "http://data.europa.eu/eli/ontology#";
    static final String ILG = "http://example.org/italian-legislation/ontology#";
    static final String RESOURCE_TYPE = "http://www.gazzettaufficiale.it/eli/tables/resource-type#";

    public record Summary(int acts, int filesRead, List<String> unreadable, RelationDetector.Result result,
                          boolean written) {}

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        Path acts = Path.of(options.getOrDefault("--acts", AknImportRunner.DEFAULT_OUT.toString()));
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
        r.relations().forEach(rel -> byProperty.merge(rel.property().eliName + " level " + rel.trust()
                + (rel.to() == null ? " (target outside corpus)" : rel.from() == null ? " (source outside corpus)" : ""),
                1, Integer::sum));
        System.out.println("Relations between acts: " + r.relations().size());
        byProperty.forEach((k, v) -> System.out.println("  " + pad(k, 50) + v));
        Map<ConversionStatus, Integer> byStatus = new EnumMap<>(ConversionStatus.class);
        r.conversions().forEach(c -> byStatus.merge(c.status(), 1, Integer::sum));
        System.out.println("Decree-laws: " + r.conversions().size());
        byStatus.forEach((k, v) -> System.out.println("  " + pad(k.name(), 22) + v));
        r.conversions().stream().filter(c -> c.status() == ConversionStatus.REVIEW
                        || c.status() == ConversionStatus.CONVERTED_ONE_SOURCE)
                .forEach(c -> System.out.println("  " + pad(c.status().name(), 22) + c.decree().key()
                        + " -> " + (c.law() == null ? "-" : c.law().key()) + "  " + checks(c)));
        System.out.println(summary.written() ? "Wrote " + out + ", " + conversions + ", " + relations
                : "No change: " + out + " is already up to date");
    }

    public Summary run(Path acts, Path in, Path out, Path conversionsReport, Path relationsReport, LocalDate today)
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

        RelationDetector.Result result = new RelationDetector(corpus).detect(original, latest, today);
        Model model = toRdf(corpus, result);
        boolean written = writeIfChanged(out, turtle(model));
        written |= writeIfChanged(conversionsReport, conversionsTsv(result).getBytes(StandardCharsets.UTF_8));
        written |= writeIfChanged(relationsReport, relationsTsv(result).getBytes(StandardCharsets.UTF_8));
        return new Summary(corpus.acts().size(), files, unreadable, result, written);
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

    static Model toRdf(CorpusIndex corpus, RelationDetector.Result result) {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("eli", ELI);
        model.setNsPrefix("ilg", ILG);
        model.setNsPrefix("rdfs", RDFS.uri);
        model.setNsPrefix("owl", OWL.NS);
        model.setNsPrefix("dcterms", DCTerms.NS);
        model.setNsPrefix("xsd", XSDDatatype.XSD + "#");
        String base = corpus.baseUri();

        Property converts = model.createProperty(ILG, "converts");
        converts.addProperty(RDF.type, OWL.ObjectProperty);
        converts.addProperty(RDFS.subPropertyOf, model.createResource(ELI + "changes"));
        converts.addProperty(RDFS.label, "converts", "en");
        converts.addProperty(RDFS.comment, "A conversion law (legge di conversione, Constitution art. 77) converts a "
                + "decree-law into law. ELI has no property for this; it is a kind of eli:changes.", "en");

        Property from = model.createProperty(ILG, "fromAct");
        Property to = model.createProperty(ILG, "toAct");
        Property property = model.createProperty(ILG, "relationProperty");
        Property trust = model.createProperty(ILG, "trustLevel");
        Property modCount = model.createProperty(ILG, "textualModCount");
        Property lifecycle = model.createProperty(ILG, "confirmedByLifecycle");
        Property example = model.createProperty(ILG, "exampleNote");

        for (Relation r : result.relations()) {
            Resource source = r.from() != null ? model.createResource(r.from().workUri()) : external(model, base, r.fromKey());
            Resource target = r.to() != null ? model.createResource(r.to().workUri()) : external(model, base, r.toKey());
            Property p = model.createProperty(ELI, r.property().eliName);
            source.addProperty(p, target);
            Resource node = model.createResource(base + "/relation/" + id(r.from(), r.fromKey()) + "/"
                    + r.property().eliName + "/" + id(r.to(), r.toKey()));
            node.addProperty(RDF.type, model.createResource(ILG + "Relation"));
            node.addProperty(from, source);
            node.addProperty(to, target);
            node.addProperty(property, p);
            node.addProperty(trust, r.trust());
            node.addLiteral(modCount, model.createTypedLiteral(r.textualMods()));
            node.addLiteral(lifecycle, model.createTypedLiteral(r.confirmedByLifecycle()));
            r.exampleNotes().forEach(n -> node.addProperty(example, n, "it"));
            if (r.sourceFile() != null) {
                node.addProperty(DCTerms.source, r.sourceFile());
            }
        }

        Property status = model.createProperty(ILG, "conversionStatus");
        Property decree = model.createProperty(ILG, "decree");
        Property law = model.createProperty(ILG, "law");
        Property days = model.createProperty(ILG, "daysToConversion");
        for (Conversion c : result.conversions()) {
            Resource d = model.createResource(c.decree().workUri());
            d.addProperty(status, c.status().name());
            Resource node = model.createResource(base + "/conversion/" + c.decree().codice());
            node.addProperty(RDF.type, model.createResource(ILG + "ConversionCheck"));
            node.addProperty(decree, d);
            node.addProperty(status, c.status().name());
            if (c.law() != null) {
                Resource l = model.createResource(c.law().workUri());
                node.addProperty(law, l);
                node.addLiteral(days, model.createTypedLiteral(c.checks().days()));
                boolean[] values = {c.checks().c1(), c.checks().c2(), c.checks().c3(),
                        c.checks().c4(), c.checks().c5(), c.checks().c6()};
                for (int i = 0; i < values.length; i++) {
                    node.addLiteral(model.createProperty(ILG, "checkC" + (i + 1)), model.createTypedLiteral(values[i]));
                }
                if (c.status() == ConversionStatus.CONVERTED || c.status() == ConversionStatus.CONVERTED_ONE_SOURCE) {
                    l.addProperty(converts, d);
                    node.addProperty(trust, c.status() == ConversionStatus.CONVERTED ? "A" : "B");
                }
            }
        }
        return model;
    }

    /** URI for an act outside the corpus, from its citation key. */
    private static Resource external(Model model, String base, ActKey key) {
        Resource act = model.createResource(base + "/eli/ext/" + key.typeCode().toLowerCase(Locale.ROOT)
                + "/" + key.date() + "/" + key.number());
        if (!act.hasProperty(RDF.type)) {
            act.addProperty(RDF.type, model.createResource(ILG + "ExternalAct"));
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
        StringBuilder sb = new StringBuilder("decree_codice\tdecree\tdecree_gu\tstatus\tlaw_codice\tlaw\tlaw_gu\tdays"
                + "\tC1\tC2\tC3\tC4\tC5\tC6\tother_candidates\tdecree_note_names_law\trepealed_by\n");
        for (Conversion c : result.conversions()) {
            sb.append(c.decree().codice()).append('\t').append(c.decree().key()).append('\t')
                    .append(c.decree().publicationDate()).append('\t').append(c.status()).append('\t');
            if (c.law() != null) {
                sb.append(c.law().codice()).append('\t').append(c.law().key()).append('\t')
                        .append(c.law().publicationDate()).append('\t').append(c.checks().days()).append('\t')
                        .append(c.checks().c1()).append('\t').append(c.checks().c2()).append('\t')
                        .append(c.checks().c3()).append('\t').append(c.checks().c4()).append('\t')
                        .append(c.checks().c5()).append('\t').append(c.checks().c6()).append('\t');
            } else {
                sb.append("\t\t\t\t\t\t\t\t\t\t");
            }
            sb.append(c.otherCandidates()).append('\t')
                    .append(c.decreeNoteNamesLaw() == null ? "" : c.decreeNoteNamesLaw()).append('\t')
                    .append(String.join(" ", c.repealedBy())).append('\n');
        }
        return sb.toString();
    }

    static String relationsTsv(RelationDetector.Result result) {
        StringBuilder sb = new StringBuilder("from_codice\tfrom\tproperty\tto_codice\tto\ttrust\ttextual_mods"
                + "\tconfirmed_by_lifecycle\tkinds\texample_note\n");
        for (Relation r : result.relations()) {
            sb.append(r.from() == null ? "" : r.from().codice()).append('\t').append(r.fromKey()).append('\t')
                    .append(r.property().eliName).append('\t')
                    .append(r.to() == null ? "" : r.to().codice()).append('\t').append(r.toKey()).append('\t')
                    .append(r.trust()).append('\t').append(r.textualMods()).append('\t')
                    .append(r.confirmedByLifecycle()).append('\t').append(new TreeMap<>(r.kinds())).append('\t')
                    .append(r.exampleNotes().isEmpty() ? "" : r.exampleNotes().get(0).replace('\t', ' ')).append('\n');
        }
        return sb.toString();
    }

    private static String checks(Conversion c) {
        if (c.checks() == null) {
            return "";
        }
        return "C1=" + c.checks().c1() + " C2=" + c.checks().c2() + " C3=" + c.checks().c3() + " C4=" + c.checks().c4()
                + " C5=" + c.checks().c5() + " (" + c.checks().days() + " days) C6=" + c.checks().c6();
    }

    private static byte[] turtle(Model model) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RDFDataMgr.write(bytes, model, RDFFormat.TURTLE_PRETTY);
        return bytes.toByteArray();
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
