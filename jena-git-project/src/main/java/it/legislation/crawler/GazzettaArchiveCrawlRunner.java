package it.legislation.crawler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class GazzettaArchiveCrawlRunner {

    private static final Path DEFAULT_INPUT = Path.of("data", "clean", "gazzetta_archive_links.tsv");
    private static final int DEFAULT_LIMIT = 10;

    public static void main(String[] args) throws IOException {
        Path input = Path.of(valueOrDefault("GAZZETTA_ARCHIVE_LINKS", DEFAULT_INPUT.toString()));
        int limit = parseLimit(valueOrDefault("GAZZETTA_ARCHIVE_CRAWL_LIMIT", Integer.toString(DEFAULT_LIMIT)));

        List<String> actUrls = readActUrls(input, limit);
        if (actUrls.isEmpty()) {
            System.out.println("No archive act URLs found in: " + input.toAbsolutePath().normalize());
            return;
        }

        int changedRecords = GazzettaScraper.crawlGazzettaActUrls(actUrls);

        System.out.println("Archive act URLs selected: " + actUrls.size());
        System.out.println("New or changed records written to RDF delta: " + changedRecords);
        System.out.println("Archive link file: " + input.toAbsolutePath().normalize());
    }

    static List<String> readActUrls(Path input, int limit) throws IOException {
        if (!Files.exists(input) || Files.size(input) == 0) {
            return List.of();
        }

        List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);
        if (lines.size() <= 1) {
            return List.of();
        }

        int actUrlColumn = findColumn(lines.get(0), "act_url");
        if (actUrlColumn < 0) {
            return List.of();
        }

        Set<String> urls = new LinkedHashSet<>();
        int effectiveLimit = Math.max(0, limit);
        for (int i = 1; i < lines.size(); i++) {
            String[] columns = lines.get(i).split("\t", -1);
            if (columns.length <= actUrlColumn) {
                continue;
            }

            String url = columns[actUrlColumn].trim();
            if (url.isBlank()) {
                continue;
            }

            urls.add(url);
            if (effectiveLimit > 0 && urls.size() >= effectiveLimit) {
                break;
            }
        }

        return new ArrayList<>(urls);
    }

    private static int findColumn(String header, String expectedName) {
        String[] columns = header.split("\t", -1);
        for (int i = 0; i < columns.length; i++) {
            if (expectedName.equals(columns[i].trim())) {
                return i;
            }
        }
        return -1;
    }

    private static String valueOrDefault(String environmentName, String defaultValue) {
        String value = System.getenv(environmentName);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private static int parseLimit(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }
}
