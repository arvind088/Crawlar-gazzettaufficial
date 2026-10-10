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
 * {@link AknToEli}. The result is written to one Turtle file, which the
 * triple store loads at start-up. A rejected file never reaches the store;
 * its reasons are printed. Running twice on the same files gives the same
 * output, and the file is not rewritten when nothing changed.
 *
 * <pre>
 * mvn -B compile exec:java "-Dexec.mainClass=it.legislation.ingest.AknImportRunner"
 * </pre>
 *
 * Options: {@code --in <dir>} (default {@code data/raw/normattiva}),
 * {@code --out <file>} (default {@code data/rdf/normattiva_akn.ttl}),
 * {@code --base <uri>} (default {@code LEGAL_ELI_BASE_URI} or the application default).
 */
public class AknImportRunner {

    public static final Path DEFAULT_IN = Path.of("data", "raw", "normattiva");
    public static final Path DEFAULT_OUT = Path.of("data", "rdf", "normattiva_akn.ttl");

    /** What one run did. */
    public record Summary(int filesRead, int acts, int expressions, int duplicates,
                          List<AknExpression.Rejected> rejected, boolean written, List<CountCheck> countChecks) {}

    /** Acts imported for one act type and year, against the number Normattiva reported. */
    public record CountCheck(String actType, int year, int expected, int imported) {
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
        Path out = Path.of(options.getOrDefault("--out", DEFAULT_OUT.toString()));
        String base = options.getOrDefault("--base",
                System.getenv().getOrDefault("LEGAL_ELI_BASE_URI", new EliUriService().baseUri()));

        Summary summary = new AknImportRunner(new EliUriService(base)).run(in, out);
        System.out.println("Files read:   " + summary.filesRead());
        System.out.println("Acts:         " + summary.acts());
        System.out.println("Versions:     " + summary.expressions());
        System.out.println("Duplicates:   " + summary.duplicates() + " (same act and version in more than one file)");
        System.out.println("Rejected:     " + summary.rejected().size());
        summary.rejected().forEach(r -> System.out.println("  REJECTED " + r.sourceFile() + "  " + r.problems()));
        System.out.println(summary.written() ? "Wrote " + out : "No change: " + out + " is already up to date");
        if (!summary.countChecks().isEmpty()) {
            System.out.println("Corpus check (acts in Normattiva vs acts imported):");
            summary.countChecks().forEach(c -> System.out.println("  " + (c.ok() ? "OK      " : "MISSING ")
                    + c.actType() + " " + c.year() + ": " + c.expected() + " vs " + c.imported()));
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
        expected.all().forEach((key, count) -> checks.add(
                new CountCheck(key.actType(), key.year(), count, imported.getOrDefault(key, 0))));
        return checks;
    }

    public Summary run(Path in, Path out) throws IOException {
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

        Model all = ModelFactory.createDefaultModel();
        int expressions = 0;
        for (Map<String, AknExpression> versions : byAct.values()) {
            Model act = mapper.map(new ArrayList<>(versions.values()));
            all.setNsPrefixes(act.getNsPrefixMap());
            all.add(act);
            expressions += versions.size();
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        RDFDataMgr.write(buffer, all, RDFFormat.TURTLE_PRETTY);
        byte[] turtle = buffer.toByteArray();
        boolean written = false;
        if (!Files.exists(out) || !Arrays.equals(Files.readAllBytes(out), turtle)) {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.write(out, turtle);
            written = true;
        }
        return new Summary(files.size(), byAct.size(), expressions, duplicates, List.copyOf(rejected), written,
                countChecks(in, byAct));
    }
}
