package it.legislation.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceSetRunnerTest {

    @TempDir
    Path temp;

    @Test
    void scoresAnswersPerStratumAndIgnoresUnsure() throws IOException {
        Path set = temp.resolve("set.tsv");
        Files.writeString(set, "﻿" + ReferenceSetRunner.HEADER + "\r\n"
                + "R01\tconversion A\tL\tconverts\tD\tA\tx\t\t\tyes\t\r\n"
                + "R02\tconversion A\tL\tconverts\tD\tA\tx\t\t\tYes\t\r\n"
                + "R03\tamends A\tL\tamends\tD\tA\tx\t\t\tno\twrong act\r\n"
                + "R04\tamends A\tL\tamends\tD\tA\tx\t\t\tunsure\t\r\n"
                + "R05\tamends A\tL\tamends\tD\tA\tx\t\t\t\t\r\n");

        List<ReferenceSetRunner.Score> scores = ReferenceSetRunner.evaluate(set);
        ReferenceSetRunner.Score conversions = find(scores, "conversion A");
        assertEquals(2, conversions.yes());
        assertEquals(1.0, conversions.precision());
        ReferenceSetRunner.Score amends = find(scores, "amends A");
        assertEquals(0, amends.yes());
        assertEquals(1, amends.no());
        assertEquals(2, amends.unchecked());
        ReferenceSetRunner.Score all = find(scores, "ALL");
        assertEquals(2, all.yes());
        assertEquals(1, all.no());
    }

    @Test
    void wilsonIntervalForAPerfectSmallSample() {
        double[] w = new ReferenceSetRunner.Score("x", 10, 0, 0).wilson();
        assertEquals(0.722, w[0], 0.001, "10 of 10 correct still only proves precision above about 72%");
        assertEquals(1.0, w[1], 1e-9);
    }

    @Test
    void neverOverwritesAFilledReferenceSet() throws IOException {
        Path conv = temp.resolve("conversions.tsv");
        Path rel = temp.resolve("relations.tsv");
        Files.writeString(conv, "decree_codice\tdecree\tdecree_gu\tstatus\tlaw_codice\tlaw\tlaw_gu\tdays\tC1\tC2\tC3\tC4\tC5\tC6"
                + "\tother_candidates\tdecree_note_names_law\trepealed_by\n"
                + "20G00034\tDECRETO-LEGGE 2020-03-17 n. 18\t2020-03-17\tCONVERTED\t20G00045\tLEGGE 2020-04-24 n. 27"
                + "\t2020-04-29\t43\ttrue\ttrue\ttrue\ttrue\ttrue\ttrue\t0\t\t\n");
        Files.writeString(rel, "from_codice\tfrom\tproperty\tto_codice\tto\ttrust\ttextual_mods\tconfirmed_by_lifecycle"
                + "\tkinds\texample_note\n");
        Path set = temp.resolve("eval/reference_set.tsv");

        assertTrue(ReferenceSetRunner.sample(conv, rel, set));
        String first = Files.readString(set);
        assertTrue(first.contains("LEGGE 2020-04-24 n. 27\tconverts\tDECRETO-LEGGE 2020-03-17 n. 18"), first);
        assertTrue(first.contains("https://www.normattiva.it/uri-res/N2Ls?urn:nir:stato:decreto.legge:2020-03-17;18"));
        assertFalse(ReferenceSetRunner.sample(conv, rel, set), "second call must not overwrite");
    }

    private static ReferenceSetRunner.Score find(List<ReferenceSetRunner.Score> scores, String stratum) {
        return scores.stream().filter(s -> s.stratum().equals(stratum)).findFirst().orElseThrow();
    }
}
