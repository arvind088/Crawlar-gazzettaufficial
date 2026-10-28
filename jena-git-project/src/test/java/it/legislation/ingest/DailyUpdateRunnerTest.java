package it.legislation.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.legislation.eli.EliUriService;
import it.legislation.source.normattiva.ExportRequest;

class DailyUpdateRunnerTest {

    @TempDir
    Path temp;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-11T04:15:00Z");

    private static JsonNode act(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fake Normattiva: D.L. 18/2020 changed; a 2018 act and a regolamento are outside the scope. */
    class FakeSource implements DailyUpdateRunner.Source {
        final List<ExportRequest> exports = new ArrayList<>();
        boolean failExports;
        /** L. 180/2026: Normattiva's all-versions export is empty; true when its other exports are empty too. */
        boolean noTextAnywhere;

        @Override
        public List<JsonNode> updatedActs(LocalDate from, LocalDate to) {
            return List.of(
                    act("{\"codiceRedazionale\":\"20G00034\",\"denominazioneAtto\":\"DECRETO-LEGGE\",\"numeroAtto\":\"18\","
                            + "\"dataEmanazione\":\"2020-03-17\",\"dataGU\":\"2020-03-17\"}"),
                    act("{\"codiceRedazionale\":\"18G00129\",\"denominazioneAtto\":\"DECRETO LEGISLATIVO\",\"numeroAtto\":\"101\","
                            + "\"dataEmanazione\":\"2018-08-10\",\"dataGU\":\"2018-09-04\"}"),
                    act("{\"codiceRedazionale\":\"25G00001\",\"denominazioneAtto\":\"REGOLAMENTO\",\"numeroAtto\":\"3\","
                            + "\"dataEmanazione\":\"2025-01-03\",\"dataGU\":\"2025-01-10\"}"));
        }

        @Override
        public List<JsonNode> listed(String type, int year) {
            if (!type.equals("LEGGE")) {
                return List.of();
            }
            return List.of(
                    act("{\"codiceRedazionale\":\"20G00045\",\"denominazioneAtto\":\"LEGGE\",\"numeroProvvedimento\":\"27\","
                            + "\"annoProvvedimento\":2020,\"meseProvvedimento\":4,\"giornoProvvedimento\":24}"),
                    act("{\"codiceRedazionale\":\"26G00201\",\"denominazioneAtto\":\"LEGGE\",\"numeroProvvedimento\":\"180\","
                            + "\"annoProvvedimento\":2026,\"meseProvvedimento\":10,\"giornoProvvedimento\":9,"
                            + "\"descrizioneAtto\":\"LEGGE 9 ottobre 2026, n. 180\"}"));
        }

        @Override
        public byte[] export(ExportRequest request) throws IOException {
            exports.add(request);
            if (failExports) {
                throw new IOException("HTTP 503");
            }
            if (request.number() == 180) {
                // Listed; the all-versions export is empty (as for recent acts in October 2026), but the
                // original text can be exported on its own, unless Normattiva has no text at all yet.
                boolean original = request.mode() == ExportRequest.Mode.ORIGINAL;
                return zip(original && !noTextAnywhere
                        ? Path.of("src/test/resources/akn-relations/legge-2020-27-relations.xml") : null);
            }
            return zip(Path.of("src/test/resources/akn-relations/decreto-legge-2020-18-relations.xml"));
        }
    }

    private static byte[] zip(Path file) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            if (file != null) {
                zip.putNextEntry(new ZipEntry("DECRETO-LEGGE_20200317_18/" + file.getFileName()));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private DailyUpdateRunner runner(FakeSource source, List<LocalDate> pipelineRuns) throws IOException {
        Path root = Files.createDirectories(temp.resolve("raw"));
        Path acts = temp.resolve("acts.ttl");
        if (!Files.exists(acts)) {
            Path sample = Files.createDirectories(temp.resolve("sample"));
            Files.copy(Path.of("src/test/resources/akn-relations/legge-2020-27-relations.xml"), sample.resolve("l27.xml"));
            new AknImportRunner(new EliUriService("https://example.test")).run(sample, acts, temp.resolve("versions.ttl"));
        }
        return new DailyUpdateRunner(source, today -> {
            pipelineRuns.add(today);
            return "pipeline ran";
        }, root, acts, new IngestionWatermark(temp.resolve("watermarks.tsv")),
                new IngestionRunLog(temp.resolve("runs.tsv")));
    }

    @Test
    void fetchesChangedAndMissingActsRunsThePipelineAndAdvancesTheWatermark() throws IOException {
        FakeSource source = new FakeSource();
        List<LocalDate> pipelineRuns = new ArrayList<>();

        DailyUpdateRunner.Summary s = runner(source, pipelineRuns).run(NOW, Duration.ofDays(7), false, line -> { });

        assertEquals(1, s.updated(), "D.L. 18/2020");
        assertEquals(2, s.outOfScope(), "a 2018 act and a regolamento");
        assertEquals(1, s.missing(), "L. 180/2026 is listed but not imported; L. 27/2020 is imported");
        assertEquals(2, s.fetched());
        assertEquals(1, s.fromSingleTextExports(), "L. 180/2026 from its original text");
        assertEquals(0, s.notExportedYet());
        assertTrue(s.failures().isEmpty(), s.failures().toString());
        assertEquals(List.of(LocalDate.of(2026, 10, 11)), pipelineRuns);
        assertTrue(Files.exists(temp.resolve("raw/updates/2026-10-11/DECRETO_LEGGE_2020-03-17_18_M_AKN.zip")));
        assertTrue(Files.exists(temp.resolve("raw/updates/2026-10-11/LEGGE_2026-10-09_180_O_AKN")),
                "the original text is unpacked where the import finds it");
        assertEquals(List.of("M", "O", "V"), source.exports.stream().filter(r -> r.number() == 180)
                .map(r -> r.mode().code()).toList(), "all versions first, then the original and current text");
        assertTrue(Files.readString(temp.resolve("raw/corpus/lists/LEGGE_2026_M_AKN.json")).contains("26G00201"));
        assertEquals(NOW, new IngestionWatermark(temp.resolve("watermarks.tsv"))
                .lastSuccessfulEnd(DailyUpdateRunner.SOURCE).orElseThrow());
        assertTrue(Files.readString(temp.resolve("runs.tsv")).contains("COMPLETED"));
    }

    @Test
    void anActWithoutAnyTextIsRetriedNextRun() throws IOException {
        FakeSource source = new FakeSource();
        source.noTextAnywhere = true;

        DailyUpdateRunner.Summary s = runner(source, new ArrayList<>()).run(NOW, Duration.ofDays(7), false, line -> { });

        assertEquals(1, s.fetched(), "only D.L. 18/2020");
        assertEquals(0, s.fromSingleTextExports());
        assertEquals(1, s.notExportedYet(), "L. 180/2026: no text in any export yet");
        assertTrue(s.failures().isEmpty(), "an empty export is not a failure");
    }

    @Test
    void aFailedDownloadKeepsTheWatermarkSoTheNextRunRetries() throws IOException {
        FakeSource source = new FakeSource();
        source.failExports = true;
        List<LocalDate> pipelineRuns = new ArrayList<>();
        DailyUpdateRunner runner = runner(source, pipelineRuns);

        DailyUpdateRunner.Summary first = runner.run(NOW, Duration.ofDays(7), false, line -> { });
        DailyUpdateRunner.Summary second = runner.run(NOW.plusDays(1), Duration.ofDays(7), false, line -> { });

        assertEquals(2, first.failures().size());
        assertTrue(new IngestionWatermark(temp.resolve("watermarks.tsv"))
                .lastSuccessfulEnd(DailyUpdateRunner.SOURCE).isEmpty(), "cursor not advanced");
        assertEquals(first.window().start(), NOW.minusDays(7));
        assertTrue(second.window().start().isBefore(NOW), "the second run asks again from the first run's start");
        assertTrue(Files.readString(temp.resolve("runs.tsv")).contains("COMPLETED_WITH_ERRORS"));
    }

    @Test
    void dryRunChangesNothing() throws IOException {
        FakeSource source = new FakeSource();
        List<LocalDate> pipelineRuns = new ArrayList<>();

        DailyUpdateRunner.Summary s = runner(source, pipelineRuns).run(NOW, Duration.ofDays(7), true, line -> { });

        assertEquals(1, s.updated());
        assertEquals(1, s.missing());
        assertTrue(source.exports.isEmpty());
        assertTrue(pipelineRuns.isEmpty());
        assertFalse(Files.exists(temp.resolve("watermarks.tsv")));
        assertFalse(Files.exists(temp.resolve("raw/corpus/lists")));
    }
}
