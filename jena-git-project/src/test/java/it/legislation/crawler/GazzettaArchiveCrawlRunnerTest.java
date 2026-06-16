package it.legislation.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GazzettaArchiveCrawlRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void readsActUrlsFromArchiveLinkTsv() throws Exception {
        Path input = tempDir.resolve("archive_links.tsv");
        Files.write(input, List.of(
                "publication_date\tissue_number\tlocal_id\tact_url\tdiscovered_at",
                "2026-06-15\t136\t26A02811\thttps://www.gazzettaufficiale.it/atto/one\t2026-06-17T12:00:00+02:00",
                "2026-06-15\t136\t26A02812\thttps://www.gazzettaufficiale.it/atto/two\t2026-06-17T12:00:00+02:00"
        ), StandardCharsets.UTF_8);

        List<String> urls = GazzettaArchiveCrawlRunner.readActUrls(input, 10);

        assertEquals(List.of(
                "https://www.gazzettaufficiale.it/atto/one",
                "https://www.gazzettaufficiale.it/atto/two"
        ), urls);
    }

    @Test
    void appliesBatchLimitAndRemovesDuplicates() throws Exception {
        Path input = tempDir.resolve("archive_links.tsv");
        Files.write(input, List.of(
                "publication_date\tissue_number\tlocal_id\tact_url\tdiscovered_at",
                "2026-06-15\t136\t26A02811\thttps://www.gazzettaufficiale.it/atto/one\t2026-06-17T12:00:00+02:00",
                "2026-06-15\t136\t26A02811\thttps://www.gazzettaufficiale.it/atto/one\t2026-06-17T12:00:00+02:00",
                "2026-06-15\t136\t26A02812\thttps://www.gazzettaufficiale.it/atto/two\t2026-06-17T12:00:00+02:00"
        ), StandardCharsets.UTF_8);

        List<String> urls = GazzettaArchiveCrawlRunner.readActUrls(input, 1);

        assertEquals(List.of("https://www.gazzettaufficiale.it/atto/one"), urls);
    }

    @Test
    void zeroLimitMeansReadAllUrls() throws Exception {
        Path input = tempDir.resolve("archive_links.tsv");
        Files.write(input, List.of(
                "publication_date\tissue_number\tlocal_id\tact_url\tdiscovered_at",
                "2026-06-15\t136\t26A02811\thttps://www.gazzettaufficiale.it/atto/one\t2026-06-17T12:00:00+02:00",
                "2026-06-15\t136\t26A02812\thttps://www.gazzettaufficiale.it/atto/two\t2026-06-17T12:00:00+02:00"
        ), StandardCharsets.UTF_8);

        List<String> urls = GazzettaArchiveCrawlRunner.readActUrls(input, 0);

        assertEquals(2, urls.size());
    }

    @Test
    void returnsEmptyListWhenInputIsMissing() throws Exception {
        List<String> urls = GazzettaArchiveCrawlRunner.readActUrls(tempDir.resolve("missing.tsv"), 10);

        assertEquals(List.of(), urls);
    }
}
