package it.legislation.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The recall check: a sample to fill in by hand, and recall computed from it. */
class RecallSetRunnerTest {

    @TempDir
    Path temp;

    @Test
    void samplesActsWithWhatWeFoundAndNeverOverwrites() throws IOException {
        Path acts = temp.resolve("gazzetta_check.tsv");
        Files.writeString(acts, String.join("\n",
                "codice\tgu_date\ttype\tnumber\tdate_of_act\tstatus\tdifferences\ttitle_differs\tgu_number\tentry_into_force\tgazzetta_url",
                "20G00045\t2020-04-29\tLEGGE\t27\t2020-04-24\tMATCH\t\t\t110\t2020-04-30\thttps://gu/l27",
                "20G00034\t2020-03-17\tDECRETO-LEGGE\t18\t2020-03-17\tMATCH\t\t\t70\t2020-03-17\thttps://gu/dl18",
                "003G0218\t2003-07-29\tDECRETO_LEGISLATIVO\t196\t2003-06-30\tMATCH\t\t\t174\t2004-01-01\thttps://gu/old"),
                StandardCharsets.UTF_8);
        Path relations = temp.resolve("relations.tsv");
        Files.writeString(relations, String.join("\n",
                "from_codice\tfrom\tproperty\tto_codice\tto\ttrust\ttextual_mods\tconfirmed_by_lifecycle\tkinds\texample_note",
                "20G00045\tLEGGE 2020-04-24 n. 27\tamends\t20G00034\tDECRETO-LEGGE 2020-03-17 n. 18\tA\t155\ttrue\t{}\t",
                "20G00034\tDECRETO-LEGGE 2020-03-17 n. 18\tchanges\t\tDECRETO_LEGISLATIVO 2001-03-30 n. 165\tB\t1\tfalse\t{}\t",
                "\tDECRETO-LEGGE 2003-12-24 n. 354\tchanges\t20G00034\tDECRETO-LEGGE 2020-03-17 n. 18\tB\t0\ttrue\t{}\t"),
                StandardCharsets.UTF_8);
        Path akn = temp.resolve("akn.ttl");
        Files.writeString(akn, """
                @prefix eli: <http://data.europa.eu/eli/ontology#> .
                <https://x/dl18> eli:id_local "20G00034" ; eli:is_realized_by <https://x/dl18/o> , <https://x/dl18/v> .
                <https://x/l27> eli:id_local "20G00045" ; eli:is_realized_by <https://x/l27/o> .
                """, StandardCharsets.UTF_8);
        Path out = temp.resolve("eval/recall_set.tsv");

        assertTrue(RecallSetRunner.sample(acts, relations, List.of(akn), out));
        List<String> lines = Files.readAllLines(out);
        assertTrue(lines.get(0).startsWith("id\tgroup\tact\twe_found_count\twe_found"));
        String text = String.join("\n", lines);
        assertFalse(text.contains("n. 196"), "acts before 2020 are not sampled");
        assertTrue(text.contains("outgoing (0-3 found)\tLEGGE 2020-04-24 n. 27\t1\tDECRETO-LEGGE 2020-03-17 n. 18"));
        assertTrue(text.contains("incoming\tDECRETO-LEGGE 2020-03-17 n. 18\t1\tLEGGE 2020-04-24 n. 27"),
                "only the decree has a second version, and the 2003 modifier is outside the scope");
        assertTrue(text.contains("https://gu/dl18"));

        Files.writeString(out, "changed by hand");
        assertFalse(RecallSetRunner.sample(acts, relations, List.of(akn), out));
        assertEquals("changed by hand", Files.readString(out), "answers are never overwritten");
    }

    @Test
    void recallPerGroupFromTheFilledSet() throws IOException {
        Path set = temp.resolve("recall_set.tsv");
        Files.writeString(set, String.join("\n", RecallSetRunner.HEADER,
                "C01\toutgoing (0-3 found)\tA\t2\tx; y\tcheck\t\t\t3\tz\t",
                "C02\toutgoing (4-12 found)\tB\t4\t...\tcheck\t\t\t4\t-\t",
                "C03\tincoming\tC\t5\t...\tcheck\t\t\t6\tp; q\t",
                "C04\tincoming\tD\t1\t...\tcheck\t\t\t\t\tnot checked yet"), StandardCharsets.UTF_8);

        List<RecallSetRunner.Score> scores = RecallSetRunner.evaluate(set);
        RecallSetRunner.Score all = scores.stream().filter(s -> s.group().equals("ALL")).findFirst().orElseThrow();
        assertEquals(3, all.acts(), "a row without actual_count is not counted");
        assertEquals(13, all.actual());
        assertEquals(3, all.missed());
        assertEquals(10.0 / 13, all.recall(), 1e-9);
        RecallSetRunner.Score incoming = scores.stream().filter(s -> s.group().equals("ALL incoming")).findFirst().orElseThrow();
        assertEquals(4.0 / 6, incoming.recall(), 1e-9);
        assertTrue(RecallSetRunner.resultsTsv(scores).startsWith("group\tacts\tactual\tfound\tmissed\trecall"));
    }
}
