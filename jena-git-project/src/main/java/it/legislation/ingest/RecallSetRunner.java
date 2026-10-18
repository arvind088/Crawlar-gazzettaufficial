package it.legislation.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;

/**
 * Measures <b>recall</b>: how many of the real relations the pipeline finds.
 * The precision check ({@link ReferenceSetRunner}) shows that what we found is
 * right; this one shows what we missed.
 *
 * <p><b>recall-sample</b> draws a fixed random sample (seed {@value #SEED}) of acts
 * from the corpus and writes {@code data/eval/recall_set.tsv}, one row per act,
 * with the relations we found and what to check by hand:
 * <ul>
 *   <li><b>outgoing</b>: acts whose own text is read on the Gazzetta Ufficiale. The
 *       reviewer lists every act they amend or repeal. This ground truth does not
 *       use Normattiva's metadata at all. Split by how many acts we found (0–3,
 *       4–12); omnibus acts changing more than 12 acts are left out, as reading
 *       them by hand is not practical (a stated limit).</li>
 *   <li><b>incoming</b>: acts that have consolidated versions. The reviewer opens the
 *       act on normattiva.it, "Aggiornamenti all'atto", and lists the laws,
 *       decree-laws and legislative decrees from 2020 on that changed it (our scope).</li>
 * </ul>
 * The reviewer fills {@code actual_count} (how many acts in total) and
 * {@code missed} (those not in {@code we_found}, separated by ";"). The file is
 * never overwritten once it exists.
 *
 * <p><b>recall</b> reads the filled file and reports recall per group,
 * found / actual, with a 95% Wilson interval, into
 * {@code data/eval/recall_results.tsv}.
 */
public class RecallSetRunner {

    public static final long SEED = 20261018L;
    public static final Path DEFAULT_SET = Path.of("data", "eval", "recall_set.tsv");
    public static final Path DEFAULT_RESULTS = Path.of("data", "eval", "recall_results.tsv");
    static final int MAX_TARGETS = 12;

    static final String HEADER = "id\tgroup\tact\twe_found_count\twe_found\thow_to_check\tnormattiva\tgazzetta"
            + "\tactual_count\tmissed\tnote";

    static final String OUTGOING_CHECK = "Read the act's text on Gazzetta (link). Search for 'modific', 'abrog', "
            + "'sostitu', 'inserit', 'sopprim'. Count every act it amends or repeals (actual_count) and list in "
            + "'missed' those not in we_found.";
    static final String INCOMING_CHECK = "Open the act on normattiva.it, 'Aggiornamenti all'atto'. Count the "
            + "laws, decree-laws and legislative decrees from 2020 on that changed it (actual_count) and list in "
            + "'missed' those not in we_found.";

    /** Recall for one group. */
    public record Score(String group, int acts, int actual, int missed) {
        public int found() {
            return actual - missed;
        }

        public double recall() {
            return actual == 0 ? Double.NaN : (double) found() / actual;
        }

        public double[] wilson() {
            return ReferenceSetRunner.wilson(found(), actual);
        }
    }

    public static void main(String[] args) throws IOException {
        String mode = args.length == 0 ? "recall" : args[0];
        if (mode.equals("recall-sample")) {
            boolean written = sample(Path.of("data", "clean", "gazzetta_check.tsv"),
                    Path.of("data", "clean", "relations.tsv"), Path.of("data", "rdf", "normattiva_akn.ttl"), DEFAULT_SET);
            System.out.println(written ? "Wrote " + DEFAULT_SET + ": fill 'actual_count' and 'missed' for each row "
                    + "(see 'how_to_check'), then run with \"recall\"."
                    : DEFAULT_SET + " already exists; not overwritten (delete it first to draw a new sample).");
            return;
        }
        List<Score> scores = evaluate(DEFAULT_SET);
        System.out.println(String.format(Locale.ROOT, "%-28s %5s %7s %7s %8s  %s", "group", "acts", "actual",
                "missed", "recall", "95% interval"));
        for (Score s : scores) {
            double[] w = s.wilson();
            System.out.println(String.format(Locale.ROOT, "%-28s %5d %7d %7d %8s  %s", s.group(), s.acts(), s.actual(),
                    s.missed(), Double.isNaN(s.recall()) ? "-" : String.format(Locale.ROOT, "%.1f%%", 100 * s.recall()),
                    Double.isNaN(w[0]) ? "-" : String.format(Locale.ROOT, "%.1f%% - %.1f%%", 100 * w[0], 100 * w[1])));
        }
        Files.createDirectories(DEFAULT_RESULTS.getParent());
        Files.writeString(DEFAULT_RESULTS, resultsTsv(scores), StandardCharsets.UTF_8);
        System.out.println("Wrote " + DEFAULT_RESULTS);
    }

    /** Draws the sample. Returns false, and writes nothing, when the set already exists. */
    public static boolean sample(Path acts, Path relations, Path akn, Path out) throws IOException {
        if (Files.exists(out)) {
            return false;
        }
        List<ReferenceSetRunner.Row> corpus = ReferenceSetRunner.read(acts).stream()
                .filter(a -> a.get("date_of_act").compareTo("2020") >= 0).toList();
        List<ReferenceSetRunner.Row> rel = ReferenceSetRunner.read(relations);
        Map<String, Integer> versions = versions(akn);

        Map<String, Set<String>> outgoing = new HashMap<>();
        Map<String, Set<String>> incoming = new HashMap<>();
        for (ReferenceSetRunner.Row r : rel) {
            if (!r.get("from_codice").isEmpty()) {
                outgoing.computeIfAbsent(r.get("from_codice"), k -> new TreeSet<>()).add(r.get("to"));
            }
            if (!r.get("to_codice").isEmpty() && inScope(r.get("from"))) {
                incoming.computeIfAbsent(r.get("to_codice"), k -> new TreeSet<>()).add(r.get("from"));
            }
        }

        Random random = new Random(SEED);
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        int[] id = {1};
        draw(corpus, a -> size(outgoing, a) <= 3, 6, "outgoing (0-3 found)", outgoing, OUTGOING_CHECK, random, lines, id);
        draw(corpus, a -> size(outgoing, a) >= 4 && size(outgoing, a) <= MAX_TARGETS, 6, "outgoing (4-12 found)",
                outgoing, OUTGOING_CHECK, random, lines, id);
        draw(corpus, a -> versions.getOrDefault(a.get("codice"), 0) >= 2, 12, "incoming", incoming, INCOMING_CHECK,
                random, lines, id);
        Files.createDirectories(out.getParent());
        Files.write(out, lines, StandardCharsets.UTF_8);
        return true;
    }

    private static int size(Map<String, Set<String>> found, ReferenceSetRunner.Row act) {
        return found.getOrDefault(act.get("codice"), Set.of()).size();
    }

    private static void draw(List<ReferenceSetRunner.Row> corpus, java.util.function.Predicate<ReferenceSetRunner.Row> filter,
                             int size, String group, Map<String, Set<String>> found, String check, Random random,
                             List<String> out, int[] id) {
        List<ReferenceSetRunner.Row> pool = new ArrayList<>(corpus.stream().filter(filter).toList());
        Collections.shuffle(pool, random);
        for (ReferenceSetRunner.Row act : pool.subList(0, Math.min(size, pool.size()))) {
            String key = act.get("type") + " " + act.get("date_of_act") + " n. " + act.get("number");
            Set<String> list = found.getOrDefault(act.get("codice"), Set.of());
            out.add(String.join("\t", String.format("C%02d", id[0]++), group, key, String.valueOf(list.size()),
                    String.join("; ", list), check, ReferenceSetRunner.link(key), act.get("gazzetta_url"), "", "", ""));
        }
    }

    /** Laws, decree-laws and legislative decrees from 2020 on: what the corpus covers. */
    static boolean inScope(String act) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(LEGGE|DECRETO-LEGGE|DECRETO_LEGISLATIVO) (\\d{4})")
                .matcher(act.trim());
        return m.find() && Integer.parseInt(m.group(2)) >= 2020;
    }

    /** Redactional code → number of versions (ELI expressions). */
    private static Map<String, Integer> versions(Path akn) {
        Map<String, Integer> count = new HashMap<>();
        Model model = RDFDataMgr.loadModel(akn.toString());
        String query = "PREFIX eli: <http://data.europa.eu/eli/ontology#> SELECT ?id (COUNT(DISTINCT ?e) AS ?n) "
                + "WHERE { ?a eli:id_local ?id ; eli:is_realized_by ?e } GROUP BY ?id";
        try (QueryExecution execution = QueryExecutionFactory.create(query, model)) {
            ResultSet rs = execution.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                count.put(s.getLiteral("id").getLexicalForm(), s.getLiteral("n").getInt());
            }
        }
        return count;
    }

    /** Recall per group and overall. Rows without actual_count are not counted. */
    public static List<Score> evaluate(Path set) throws IOException {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (ReferenceSetRunner.Row row : ReferenceSetRunner.read(set)) {
            String actual = row.get("actual_count").trim();
            if (actual.isEmpty()) {
                continue;
            }
            int a = Integer.parseInt(actual);
            int m = 0;
            for (String missed : row.get("missed").split(";")) {
                if (!missed.isBlank() && !missed.trim().equals("-")) {
                    m++;
                }
            }
            String group = row.get("group").startsWith("outgoing") ? "ALL outgoing" : "ALL incoming";
            for (String key : List.of(row.get("group"), group, "ALL")) {
                int[] c = counts.computeIfAbsent(key, k -> new int[3]);
                c[0]++;
                c[1] += a;
                c[2] += m;
            }
        }
        List<Score> scores = new ArrayList<>();
        counts.forEach((k, c) -> {
            if (!k.startsWith("ALL")) {
                scores.add(new Score(k, c[0], c[1], c[2]));
            }
        });
        counts.forEach((k, c) -> {
            if (k.startsWith("ALL")) {
                scores.add(new Score(k, c[0], c[1], c[2]));
            }
        });
        return scores;
    }

    static String resultsTsv(List<Score> scores) {
        StringBuilder sb = new StringBuilder("group\tacts\tactual\tfound\tmissed\trecall\twilson_low\twilson_high\n");
        for (Score s : scores) {
            double[] w = s.wilson();
            sb.append(s.group()).append('\t').append(s.acts()).append('\t').append(s.actual()).append('\t')
                    .append(s.found()).append('\t').append(s.missed()).append('\t')
                    .append(Double.isNaN(s.recall()) ? "" : String.format(Locale.ROOT, "%.4f", s.recall())).append('\t')
                    .append(Double.isNaN(w[0]) ? "" : String.format(Locale.ROOT, "%.4f", w[0])).append('\t')
                    .append(Double.isNaN(w[1]) ? "" : String.format(Locale.ROOT, "%.4f", w[1])).append('\n');
        }
        return sb.toString();
    }
}
