package it.legislation.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Builds and scores the hand-checked reference set used to measure how often
 * the relations found by {@link RelationRunner} are right (R6, evaluation).
 *
 * <p><b>sample</b>: draws a fixed random sample (seed {@value #SEED}) from
 * {@code data/clean/conversions.tsv} and {@code data/clean/relations.tsv},
 * stratified by kind and trust level, and writes
 * {@code data/eval/reference_set.tsv} with a Normattiva link and what to
 * check for each row. The file is never overwritten once it exists, so
 * answers already filled in are safe.
 *
 * <p><b>evaluate</b>: reads the file after a person has filled the
 * {@code correct} column with {@code yes}, {@code no} or {@code unsure},
 * and reports precision per stratum with a 95% Wilson interval. Rows marked
 * {@code unsure} or left empty are not counted.
 *
 * <pre>
 * mvn -B compile exec:java "-Dexec.mainClass=it.legislation.ingest.ReferenceSetRunner" "-Dexec.args=sample"
 * mvn -B compile exec:java "-Dexec.mainClass=it.legislation.ingest.ReferenceSetRunner" "-Dexec.args=evaluate"
 * </pre>
 */
public class ReferenceSetRunner {

    public static final long SEED = 20261011L;
    public static final Path DEFAULT_SET = Path.of("data", "eval", "reference_set.tsv");
    public static final Path DEFAULT_RESULTS = Path.of("data", "eval", "evaluation_results.tsv");

    static final String HEADER = "id\tstratum\tfrom\tclaim\tto\ttrust\thow_to_check\tnormattiva_from\tnormattiva_to\tcorrect\tnote";

    /** How many rows to draw from each stratum. */
    record Stratum(String name, int size, String howToCheck) {}

    /** One row of a TSV report, by column name. */
    record Row(Map<String, String> values) {
        String get(String column) {
            return values.getOrDefault(column, "");
        }
    }

    /** Precision for one stratum. */
    public record Score(String stratum, int yes, int no, int unchecked) {
        public double precision() {
            return yes + no == 0 ? Double.NaN : (double) yes / (yes + no);
        }

        /** 95% Wilson score interval, which behaves well for small samples and precision near 1. */
        public double[] wilson() {
            int n = yes + no;
            if (n == 0) {
                return new double[] {Double.NaN, Double.NaN};
            }
            double z = 1.959964;
            double p = (double) yes / n;
            double centre = (p + z * z / (2 * n)) / (1 + z * z / n);
            double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / (1 + z * z / n);
            return new double[] {Math.max(0, centre - half), Math.min(1, centre + half)};
        }
    }

    public static void main(String[] args) throws IOException {
        String mode = args.length == 0 ? "evaluate" : args[0];
        Path clean = Path.of("data", "clean");
        if (mode.equals("sample")) {
            boolean written = sample(clean.resolve("conversions.tsv"), clean.resolve("relations.tsv"), DEFAULT_SET);
            System.out.println(written ? "Wrote " + DEFAULT_SET + ": open it in Excel or LibreOffice, fill the column "
                    + "'correct' with yes / no / unsure, then run with \"evaluate\"."
                    : DEFAULT_SET + " already exists; not overwritten (delete it first to draw a new sample).");
            return;
        }
        List<Score> scores = evaluate(DEFAULT_SET);
        System.out.println(String.format(Locale.ROOT, "%-32s %4s %4s %6s %10s  %s", "stratum", "yes", "no", "open",
                "precision", "95% interval"));
        for (Score s : scores) {
            double[] w = s.wilson();
            System.out.println(String.format(Locale.ROOT, "%-32s %4d %4d %6d %10s  %s", s.stratum(), s.yes(), s.no(),
                    s.unchecked(), Double.isNaN(s.precision()) ? "-" : String.format(Locale.ROOT, "%.1f%%", 100 * s.precision()),
                    Double.isNaN(w[0]) ? "-" : String.format(Locale.ROOT, "%.1f%% - %.1f%%", 100 * w[0], 100 * w[1])));
        }
        Files.createDirectories(DEFAULT_RESULTS.getParent());
        Files.writeString(DEFAULT_RESULTS, resultsTsv(scores), StandardCharsets.UTF_8);
        System.out.println("Wrote " + DEFAULT_RESULTS);
    }

    /** Draws the sample. Returns false, and writes nothing, when the reference set already exists. */
    public static boolean sample(Path conversions, Path relations, Path out) throws IOException {
        if (Files.exists(out)) {
            return false;
        }
        List<Row> conv = read(conversions);
        List<Row> rel = read(relations);
        Map<String, String> keys = new LinkedHashMap<>();
        rel.forEach(r -> {
            keys.put(r.get("from_codice"), r.get("from"));
            keys.put(r.get("to_codice"), r.get("to"));
        });
        conv.forEach(c -> {
            keys.put(c.get("decree_codice"), c.get("decree"));
            keys.put(c.get("law_codice"), c.get("law"));
        });
        Random random = new Random(SEED);
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        int[] id = {1};

        String convCheck = "Open the law: Art. 1 says the decree-law \"e' convertito in legge\"";
        draw(conv, c -> c.get("status").equals("CONVERTED"), new Stratum("conversion A", 12, convCheck), random,
                c -> conversionLine(c, convCheck), lines, id);
        draw(conv, c -> c.get("status").equals("CONVERTED_ONE_SOURCE"), new Stratum("conversion B", 4, convCheck),
                random, c -> conversionLine(c, convCheck), lines, id);
        String repealedCheck = "Open the repealing act: its text says the decree-law \"e' abrogato\"; "
                + "and the decree-law was not converted";
        draw(conv, c -> c.get("status").equals("REPEALED"), new Stratum("decree repealed", 6, repealedCheck), random,
                c -> repealedLine(c, repealedCheck, keys), lines, id);
        String pendingCheck = "Check that no conversion law is published in the GU yet (or, for AWAITING, that the "
                + "named law converts it)";
        draw(conv, c -> c.get("status").equals("PENDING") || c.get("status").equals("AWAITING_LAW_TEXT"),
                new Stratum("decree pending", 3, pendingCheck), random, c -> pendingLine(c, pendingCheck), lines, id);

        String amendsCheck = "Open the target act, \"aggiornamenti all'atto\": the source act is listed as a change, "
                + "or the source act's text changes the target";
        String repealsCheck = "Open the source act: its text repeals the whole target act (not only an article)";
        String changesCheck = "Open the target act, \"aggiornamenti all'atto\": the source act is listed";
        draw(rel, r -> is(r, "amends", "A", true), new Stratum("amends A", 8, amendsCheck), random,
                r -> relationLine(r, amendsCheck), lines, id);
        draw(rel, r -> is(r, "amends", "B", false), new Stratum("amends B (target outside corpus)", 6, amendsCheck),
                random, r -> relationLine(r, amendsCheck), lines, id);
        draw(rel, r -> is(r, "repeals", "A", true), new Stratum("repeals A", 5, repealsCheck), random,
                r -> relationLine(r, repealsCheck), lines, id);
        draw(rel, r -> is(r, "repeals", "B", true), new Stratum("repeals B", 3, repealsCheck), random,
                r -> relationLine(r, repealsCheck), lines, id);
        draw(rel, r -> is(r, "repeals", "B", false), new Stratum("repeals B (target outside corpus)", 3, repealsCheck),
                random, r -> relationLine(r, repealsCheck), lines, id);
        draw(rel, r -> is(r, "changes", "B", true) && !r.get("from_codice").isEmpty(),
                new Stratum("changes B", 6, changesCheck), random, r -> relationLine(r, changesCheck), lines, id);

        Files.createDirectories(out.getParent());
        Files.write(out, lines, StandardCharsets.UTF_8);
        return true;
    }

    private static boolean is(Row r, String property, String trust, boolean targetInCorpus) {
        return r.get("property").equals(property) && r.get("trust").equals(trust)
                && r.get("to_codice").isEmpty() != targetInCorpus;
    }

    private static void draw(List<Row> rows, Predicate<Row> filter, Stratum stratum, Random random,
                             java.util.function.Function<Row, String[]> line, List<String> out, int[] id) {
        List<Row> pool = new ArrayList<>(rows.stream().filter(filter).toList());
        Collections.shuffle(pool, random);
        for (Row row : pool.subList(0, Math.min(stratum.size(), pool.size()))) {
            String[] cells = line.apply(row);
            out.add(String.join("\t", String.format("R%02d", id[0]++), stratum.name(), cells[0], cells[1], cells[2],
                    cells[3], cells[4], link(cells[0]), link(cells[2]), "", ""));
        }
    }

    private static String[] conversionLine(Row c, String check) {
        return new String[] {c.get("law"), "converts", c.get("decree"),
                c.get("status").equals("CONVERTED") ? "A" : "B", check};
    }

    private static String[] repealedLine(Row c, String check, Map<String, String> keys) {
        String first = c.get("repealed_by").split(" ")[0];
        return new String[] {keys.getOrDefault(first, first), "repeals (decree not converted)", c.get("decree"), "-", check};
    }

    private static String[] pendingLine(Row c, String check) {
        return new String[] {"-", c.get("status"), c.get("decree"), "-",
                check + (c.get("decree_note_names_law").isEmpty() ? "" : " [" + c.get("decree_note_names_law") + "]")};
    }

    private static String[] relationLine(Row r, String check) {
        return new String[] {r.get("from"), r.get("property"), r.get("to"), r.get("trust"), check};
    }

    /** Normattiva link for "DECRETO-LEGGE 2020-03-17 n. 18"; empty when the text is not an act key. */
    static String link(String act) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([A-Z_-]+) (\\d{4}-\\d{2}-\\d{2}) n\\. (\\S+)$").matcher(act.trim());
        if (!m.find()) {
            return "";
        }
        String type = switch (m.group(1)) {
            case "LEGGE" -> "legge";
            case "DECRETO-LEGGE" -> "decreto.legge";
            case "DECRETO_LEGISLATIVO" -> "decreto.legislativo";
            default -> m.group(1).toLowerCase(Locale.ROOT).replace('_', '.').replace('-', '.');
        };
        return "https://www.normattiva.it/uri-res/N2Ls?urn:nir:stato:" + type + ":" + m.group(2) + ";" + m.group(3);
    }

    /** Precision per stratum, plus totals for conversions, relations and everything. */
    public static List<Score> evaluate(Path set) throws IOException {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (Row row : read(set)) {
            String answer = row.get("correct").trim().toLowerCase(Locale.ROOT);
            String stratum = row.get("stratum");
            String group = stratum.startsWith("conversion") || stratum.startsWith("decree")
                    ? "ALL decree-law statuses" : "ALL relations";
            for (String key : List.of(stratum, group, "ALL")) {
                int[] c = counts.computeIfAbsent(key, k -> new int[3]);
                if (answer.equals("yes") || answer.equals("y") || answer.equals("si") || answer.equals("sì")) {
                    c[0]++;
                } else if (answer.equals("no") || answer.equals("n")) {
                    c[1]++;
                } else {
                    c[2]++;
                }
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
        StringBuilder sb = new StringBuilder("stratum\tyes\tno\tunchecked\tprecision\twilson_low\twilson_high\n");
        for (Score s : scores) {
            double[] w = s.wilson();
            sb.append(s.stratum()).append('\t').append(s.yes()).append('\t').append(s.no()).append('\t')
                    .append(s.unchecked()).append('\t')
                    .append(Double.isNaN(s.precision()) ? "" : String.format(Locale.ROOT, "%.4f", s.precision())).append('\t')
                    .append(Double.isNaN(w[0]) ? "" : String.format(Locale.ROOT, "%.4f", w[0])).append('\t')
                    .append(Double.isNaN(w[1]) ? "" : String.format(Locale.ROOT, "%.4f", w[1])).append('\n');
        }
        return sb.toString();
    }

    /** Reads a TSV with a header line. Accepts files saved by Excel (BOM, CRLF, quoted cells). */
    static List<Row> read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Row> rows = new ArrayList<>();
        if (lines.isEmpty()) {
            return rows;
        }
        String[] header = unquote(lines.get(0).replace("﻿", "").split("\t", -1));
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] cells = unquote(line.split("\t", -1));
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < header.length && i < cells.length; i++) {
                values.put(header[i].trim(), cells[i]);
            }
            rows.add(new Row(values));
        }
        return rows;
    }

    private static String[] unquote(String[] cells) {
        for (int i = 0; i < cells.length; i++) {
            String c = cells[i].trim();
            if (c.length() >= 2 && c.startsWith("\"") && c.endsWith("\"")) {
                c = c.substring(1, c.length() - 1).replace("\"\"", "\"");
            }
            cells[i] = c;
        }
        return cells;
    }
}
