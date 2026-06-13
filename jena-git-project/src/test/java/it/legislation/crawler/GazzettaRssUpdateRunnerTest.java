package it.legislation.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GazzettaRssUpdateRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesRssEntriesWithLimit() throws Exception {
        String rss = """
                <rss>
                  <channel>
                    <item>
                      <title>First title</title>
                      <link>https://www.gazzettaufficiale.it/eli/id/2026/01/01/26G00001/sg</link>
                      <pubDate>Thu, 01 Jan 2026 10:00:00 GMT</pubDate>
                      <description>First description</description>
                    </item>
                    <item>
                      <title>Second title</title>
                      <link>https://www.gazzettaufficiale.it/eli/id/2026/01/02/26G00002/sg</link>
                    </item>
                  </channel>
                </rss>
                """;

        List<GazzettaRssUpdateRunner.RssEntry> entries = GazzettaRssUpdateRunner.parseEntries(
                new ByteArrayInputStream(rss.getBytes(StandardCharsets.UTF_8)),
                1
        );

        assertEquals(1, entries.size());
        assertEquals("First title", entries.get(0).title());
        assertEquals("https://www.gazzettaufficiale.it/eli/id/2026/01/01/26G00001/sg", entries.get(0).link());
        assertEquals("Thu, 01 Jan 2026 10:00:00 GMT", entries.get(0).published());
    }

    @Test
    void appendsOnlyNewLinks() throws Exception {
        Path output = tempDir.resolve("gazzetta_rss_updates.tsv");
        OffsetDateTime fetchDate = OffsetDateTime.parse("2026-06-13T10:15:30+02:00");

        List<GazzettaRssUpdateRunner.RssEntry> firstRun = List.of(
                new GazzettaRssUpdateRunner.RssEntry("First title", "https://example.test/one", "published", "description")
        );

        List<GazzettaRssUpdateRunner.RssEntry> secondRun = List.of(
                new GazzettaRssUpdateRunner.RssEntry("First title changed", "https://example.test/one", "published", "description"),
                new GazzettaRssUpdateRunner.RssEntry("Second title", "https://example.test/two", "published", "description")
        );

        assertEquals(1, GazzettaRssUpdateRunner.appendNewEntries(firstRun, output, fetchDate));
        assertEquals(1, GazzettaRssUpdateRunner.appendNewEntries(secondRun, output, fetchDate));

        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("title\tlink\tpublished"));
        assertTrue(lines.get(1).contains("https://example.test/one"));
        assertTrue(lines.get(2).contains("https://example.test/two"));
    }

    @Test
    void readsLinksFromUpdateFile() throws Exception {
        Path output = tempDir.resolve("gazzetta_rss_updates.tsv");
        OffsetDateTime fetchDate = OffsetDateTime.parse("2026-06-13T10:15:30+02:00");

        GazzettaRssUpdateRunner.appendNewEntries(List.of(
                new GazzettaRssUpdateRunner.RssEntry("First title", "https://example.test/one", "published", "description"),
                new GazzettaRssUpdateRunner.RssEntry("Second title", "https://example.test/two", "published", "description")
        ), output, fetchDate);

        assertEquals(List.of("https://example.test/one", "https://example.test/two"), GazzettaRssUpdateRunner.readLinks(output));
    }
}
