package it.legislation.source.normattiva;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NormattivaCorpusRunnerTest {

    @TempDir
    Path root;

    @Test
    void countsExportsAndSkipsWhatIsAlreadyDownloaded() throws IOException {
        String list = "{\"numeroAttiTrovati\":2,\"listaAtti\":[{\"codiceRedazionale\":\"24G00001\"},{\"codiceRedazionale\":\"24G00002\"}]}";
        FakeTransport transport = new FakeTransport()
                .reply(200, "{\"numeroAttiTrovati\":2,\"listaAtti\":[]}")
                .reply(200, list)
                .reply(200, "tok-1")
                .reply(204, "")
                .reply(303, Map.of("x-ipzs-location", "collections/download/collection-asincrona/tok-1"), "")
                .replyBytes(200, zip("LEGGE_20240101_1/2024-01-02_24G00001_ORIGINALE_V0.xml", "<akomaNtoso/>"))
                // second run: count and list again, then the slice is skipped
                .reply(200, "{\"numeroAttiTrovati\":2,\"listaAtti\":[]}")
                .reply(200, list);
        NormattivaClient client = new NormattivaClient(transport, d -> { }, () -> 0L);

        new NormattivaCorpusRunner(client, root, Duration.ofMinutes(1), 100, false, false).run(List.of("LEGGE"), 2024, 2024);
        new NormattivaCorpusRunner(client, root, Duration.ofMinutes(1), 100, false, false).run(List.of("LEGGE"), 2024, 2024);

        Path zip = root.resolve("corpus/LEGGE/2024/LEGGE_2024_M_AKN.zip");
        assertTrue(Files.exists(zip));
        assertTrue(Files.exists(root.resolve("corpus/LEGGE/2024/LEGGE_2024_M_AKN/LEGGE_20240101_1/2024-01-02_24G00001_ORIGINALE_V0.xml")));
        assertEquals(8, transport.calls.size(), "second run must not export again");
        assertTrue(Files.readString(root.resolve("corpus/lists/LEGGE_2024_M_AKN.json")).contains("24G00002"));
        CorpusCounts counts = CorpusCounts.read(root.resolve("corpus/counts.tsv"));
        assertEquals(2, counts.all().get(new CorpusCounts.Key("LEGGE", 2024)));
    }

    @Test
    void largeYearsAreExportedMonthByMonth() throws IOException {
        FakeTransport transport = new FakeTransport()
                .reply(200, "{\"numeroAttiTrovati\":150}")
                .reply(200, "{\"numeroAttiTrovati\":150,\"listaAtti\":[]}");
        for (int month = 1; month <= 12; month++) {
            transport.reply(200, "{\"numeroAttiTrovati\":0}");
        }
        NormattivaClient client = new NormattivaClient(transport, d -> { }, () -> 0L);

        new NormattivaCorpusRunner(client, root, Duration.ofMinutes(1), 100, false, false).run(List.of("LEGGE"), 2024, 2024);

        assertEquals(14, transport.calls.size());
        assertTrue(transport.calls.get(2).body().contains("\"meseProvvedimento\":1"));
        assertTrue(transport.calls.get(13).body().contains("\"meseProvvedimento\":12"));
    }

    private static byte[] zip(String name, String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry(name));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }
}
