package it.legislation.ingest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;

import it.legislation.eli.EliUriService;
import it.legislation.mapping.AknActReader;
import it.legislation.mapping.AknToEli;
import it.legislation.model.AknExpression;
import it.legislation.source.normattiva.CorpusCounts;

/**
 * Turns the Normattiva Akoma Ntoso files in the raw store into ELI RDF.
 *
 * <p>Every XML file is read and checked by {@link AknActReader}; accepted
 * versions are grouped by act (codice redazionale) and mapped by
 * {@link AknToEli}. The result is written in two layers, both loaded by the
 * triple store at start-up:
 * <ul>
 *   <li><b>Layer 1</b> ({@code data/rdf/layer1_acts.ttl}): one {@code eli:LegalResource} per act, built from
 *       its original text (type, date, number, codice, title, Gazzetta ELI), and the original version;</li>
 *   <li><b>Layer 2</b> ({@code data/rdf/layer2_versions.ttl}): every consolidated version
 *       ({@code eli:LegalExpression} with its {@code eli:version_date}) and the Akoma Ntoso file it came from.</li>
 * </ul>
 * A rejected file never reaches the store; its reasons are printed. Running
 * twice on the same files gives the same output, and a file is not rewritten
 * when nothing changed.
 *
 * <pre>
 * mvn -B compile exec:java "-Dexec.mainClass=it.legislation.ingest.AknImportRunner"
 * </pre>
 *
 * Options: {@code --in <dir>} (default {@code data/raw/normattiva}),
 * {@code --layer1 <file>} (default {@code data/rdf/layer1_acts.ttl}),
 * {@code --layer2 <file>} (default {@code data/rdf/layer2_versions.ttl}),
 * {@code --base <uri>} (default {@code LEGAL_ELI_BASE_URI} or the application default).
 */
public class AknImportRunner {

    public static final Path DEFAULT_IN = Path.of("data", "raw", "normattiva");
    /** Layer 1: the acts, from their original text. */
    public static final Path LAYER1 = Path.of("data", "rdf", "layer1_acts.ttl");
    /** Layer 2: the consolidated versions of each act. */
    public static final Path LAYER2 = Path.of("data", "rdf", "layer2_versions.ttl");
    /** Both layers, which together describe every act and every version. */
    public static final List<Path> LAYERS = List.of(LAYER1, LAYER2);

    /** What one run did. */
    public record Summary(int filesRead, int acts, int expressions, int duplicates,
                          List<AknExpression.Rejected> rejected, boolean written, List<CountCheck> countChecks) {}

    /** Acts imported for one act type and year, against the number Normattiva reported. */
    public record CountCheck(String actType, int year, int expected, int imported, List<String> missing) {
        public boolean ok() {
            return expected == imported;
        }
    }

    private final AknActReader reader = new AknActReader();
    private final AknToEli mapper;

    public AknImportRunner(EliUriService uris) {
        this.mapper = new AknToEli(uris);
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        Path in = Path.of(options.getOrDefault("--in", DEFAULT_IN.toString()));
        Path layer1 = Path.of(options.getOrDefault("--layer1", LAYER1.toString()));
        Path layer2 = Path.of(options.getOrDefault("--layer2", LAYER2.toString()));
        String base = options.getOrDefault("--base",
                System.getenv().getOrDefault("LEGAL_ELI_BASE_URI", new EliUriService().baseUri()));

        Summary summary = new AknImportRunner(new EliUriService(base)).run(in, layer1, layer2);
        System.out.println("Files read:   " + summary.filesRead());
        System.out.println("Acts:         " + summary.acts());
        System.out.println("Versions:     " + summary.expressions());
        System.out.println("Duplicates:   " + summary.duplicates() + " (same act and version in more than one file)");
        System.out.println("Rejected:     " + summary.rejected().size());
        summary.rejected().forEach(r -> System.out.println("  REJECTED " + r.sourceFile() + "  " + r.problems()));
        System.out.println(summary.written() ? "Wrote " + layer1 + " and " + layer2
                : "No change: " + layer1 + " and " + layer2 + " are already up to date");
        if (!summary.countChecks().isEmpty()) {
            System.out.println("Corpus check (acts in Normattiva vs acts imported):");
            summary.countChecks().forEach(c -> {
                System.out.println("  " + (c.ok() ? "OK      " : "MISSING ")
                        + c.actType() + " " + c.year() + ": " + c.expected() + " vs " + c.imported());
                c.missing().forEach(m -> System.out.println("           not in export: " + m));
            });
            long bad = summary.countChecks().stream().filter(c -> !c.ok()).count();
            System.out.println(bad == 0 ? "Corpus complete." : bad + " slice(s) do not match: fetch again, then re-import.");
        }
    }

    /** Compares imported acts per type and year with {@code corpus/counts.tsv}, when that file exists. */
    static List<CountCheck> countChecks(Path in, Map<String, Map<String, AknExpression>> byAct) throws IOException {
        CorpusCounts expected = CorpusCounts.read(in.resolve("corpus").resolve(CorpusCounts.FILE));
        Map<CorpusCounts.Key, Integer> imported = new TreeMap<>();
        for (Map<String, AknExpression> versions : byAct.values()) {
            AknExpression any = versions.values().iterator().next();
            imported.merge(new CorpusCounts.Key(any.actType(), any.documentDate().getYear()), 1, Integer::sum);
        }
        List<CountCheck> checks = new ArrayList<>();
        for (Map.Entry<CorpusCounts.Key, Integer> entry : expected.all().entrySet()) {
            CorpusCounts.Key key = entry.getKey();
            int got = imported.getOrDefault(key, 0);
            List<String> missing = got == entry.getValue() ? List.of() : missingActs(in, key, byAct.keySet());
            checks.add(new CountCheck(key.actType(), key.year(), entry.getValue(), got, missing));
        }
        return checks;
    }

    /** Acts Normattiva lists for a slice (corpus/lists/*.json) whose codice redazionale was not imported. */
    static List<String> missingActs(Path in, CorpusCounts.Key key, java.util.Set<String> importedCodes) throws IOException {
        String stem = key.actType().replaceAll("[^A-Za-z0-9]+", "_") + "_" + String.format("%04d", key.year()) + "_M_AKN";
        Path list = in.resolve("corpus").resolve("lists").resolve(stem + ".json");
        if (!Files.exists(list)) {
            return List.of("(no list saved for this slice: run NormattivaCorpusRunner again to name the missing acts)");
        }
        List<String> missing = new ArrayList<>();
        com.fasterxml.jackson.databind.JsonNode acts = new com.fasterxml.jackson.databind.ObjectMapper().readTree(list.toFile());
        for (com.fasterxml.jackson.databind.JsonNode act : acts) {
            String code = act.path("codiceRedazionale").asText();
            if (!importedCodes.contains(code)) {
                missing.add(code + "  GU " + act.path("dataGU").asText() + "  " + act.path("descrizioneAtto").asText());
            }
        }
        return missing;
    }

    public Summary run(Path in, Path layer1, Path layer2) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(in)) {
            files = walk.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
        }

        Map<String, Map<String, AknExpression>> byAct = new TreeMap<>();
        List<AknExpression.Rejected> rejected = new ArrayList<>();
        int duplicates = 0;
        for (Path file : files) {
            String name = in.relativize(file).toString().replace('\\', '/');
            AknActReader.Result result = reader.read(file, name);
            if (!result.ok()) {
                rejected.add(result.rejected());
                continue;
            }
            AknExpression version = result.expression();
            String key = (version.original() ? "original" : "vigente/" + version.versionDate());
            Map<String, AknExpression> versions = byAct.computeIfAbsent(version.codiceRedazionale(), k -> new TreeMap<>());
            if (versions.putIfAbsent(key, version) != null) {
                duplicates++;
            }
        }

        Model acts = ModelFactory.createDefaultModel();
        Model consolidated = ModelFactory.createDefaultModel();
        int expressions = 0;
        for (Map<String, AknExpression> versions : byAct.values()) {
            Model act = mapper.map(new ArrayList<>(versions.values()));
            acts.setNsPrefixes(act.getNsPrefixMap());
            consolidated.setNsPrefixes(act.getNsPrefixMap());
            split(act, acts, consolidated);
            expressions += versions.size();
        }
        boolean written = write(layer1, acts);
        written |= write(layer2, consolidated);
        return new Summary(files.size(), byAct.size(), expressions, duplicates, List.copyOf(rejected), written,
                countChecks(in, byAct));
    }

    /**
     * Layer 2 gets every statement about a consolidated version or its file (subject or object under
     * {@code /ita/vigente/}); Layer 1 gets the rest: the act and its original version.
     */
    static void split(Model act, Model layer1, Model layer2) {
        act.listStatements().forEachRemaining(statement -> {
            boolean version = isConsolidated(statement.getSubject())
                    || (statement.getObject().isURIResource() && isConsolidated(statement.getObject().asResource()));
            (version ? layer2 : layer1).add(statement);
        });
    }

    private static boolean isConsolidated(org.apache.jena.rdf.model.Resource resource) {
        return resource.isURIResource() && resource.getURI().contains("/ita/vigente/");
    }

    /** Writes the Turtle unless the file already holds exactly these bytes. */
    private static boolean write(Path out, Model model) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        RDFDataMgr.write(buffer, model, RDFFormat.TURTLE_PRETTY);
        byte[] turtle = buffer.toByteArray();
        if (Files.exists(out) && Arrays.equals(Files.readAllBytes(out), turtle)) {
            return false;
        }
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.write(out, turtle);
        return true;
    }
}
