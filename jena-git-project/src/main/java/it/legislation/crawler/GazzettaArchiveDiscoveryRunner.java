package it.legislation.crawler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GazzettaArchiveDiscoveryRunner {

    private static final String BASE_URL = "https://www.gazzettaufficiale.it";
    private static final Path DEFAULT_OUTPUT = Path.of("data", "clean", "gazzetta_archive_links.tsv");
    private static final List<String> HEADER = List.of(
            "publication_date",
            "issue_number",
            "local_id",
            "act_url",
            "discovered_at"
    );
    private static final Pattern ISSUE_LINK = Pattern.compile(
            "href\\s*=\\s*['\"]([^'\"]*caricaDettaglio\\?dataPubblicazioneGazzetta=(\\d{4}-\\d{2}-\\d{2})&numeroGazzetta=(\\d+)[^'\"]*)['\"]",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ACT_LINK = Pattern.compile(
            "href\\s*=\\s*['\"]([^'\"]*codiceRedazionale=([A-Z0-9]+)[^'\"]*)['\"]",
            Pattern.CASE_INSENSITIVE
    );

    public static void main(String[] args) throws IOException {
        LocalDate startDate = LocalDate.parse(valueOrDefault("GAZZETTA_ARCHIVE_START", "2026-06-01"));
        LocalDate endDate = LocalDate.parse(valueOrDefault("GAZZETTA_ARCHIVE_END", "2026-06-16"));
        Path output = Path.of(valueOrDefault("GAZZETTA_ARCHIVE_OUTPUT", DEFAULT_OUTPUT.toString()));

        List<ArchiveActLink> links = discover(startDate, endDate);
        writeLinks(links, output, OffsetDateTime.now());

        System.out.println("Discovered archive act links: " + links.size());
        System.out.println("Archive link file: " + output.toAbsolutePath().normalize());
    }

    public static List<ArchiveActLink> discover(LocalDate startDate, LocalDate endDate) throws IOException {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date must not be before start date.");
        }

        Map<String, ArchiveActLink> discovered = new LinkedHashMap<>();
        for (int year = startDate.getYear(); year <= endDate.getYear(); year++) {
            String archiveHtml = fetch(yearArchiveUrl(year));
            List<IssueLink> issues = parseIssueLinks(archiveHtml).stream()
                    .filter(issue -> !issue.publicationDate().isBefore(startDate))
                    .filter(issue -> !issue.publicationDate().isAfter(endDate))
                    .sorted(Comparator.comparing(IssueLink::publicationDate))
                    .toList();

            for (IssueLink issue : issues) {
                String issueHtml = fetch(issue.url());
                for (ArchiveActLink actLink : parseActLinks(issue, issueHtml)) {
                    discovered.putIfAbsent(actLink.actUrl(), actLink);
                }
            }
        }
        return List.copyOf(discovered.values());
    }

    static List<IssueLink> parseIssueLinks(String html) {
        String normalized = normalizeHtml(html);
        Matcher matcher = ISSUE_LINK.matcher(normalized);
        Map<String, IssueLink> links = new LinkedHashMap<>();
        while (matcher.find()) {
            String url = absoluteUrl(matcher.group(1));
            LocalDate publicationDate = LocalDate.parse(matcher.group(2));
            String issueNumber = matcher.group(3);
            links.putIfAbsent(publicationDate + "#" + issueNumber, new IssueLink(publicationDate, issueNumber, url));
        }
        return List.copyOf(links.values());
    }

    static List<ArchiveActLink> parseActLinks(IssueLink issue, String html) {
        String normalized = normalizeHtml(html);
        Matcher matcher = ACT_LINK.matcher(normalized);
        Map<String, ArchiveActLink> links = new LinkedHashMap<>();
        while (matcher.find()) {
            String url = absoluteUrl(matcher.group(1));
            String localId = matcher.group(2);
            links.putIfAbsent(localId, new ArchiveActLink(issue.publicationDate(), issue.issueNumber(), localId, url));
        }
        return List.copyOf(links.values());
    }

    static void writeLinks(List<ArchiveActLink> links, Path output, OffsetDateTime discoveredAt) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        List<String> lines = new ArrayList<>();
        lines.add(String.join("\t", HEADER));
        for (ArchiveActLink link : links) {
            lines.add(String.join("\t",
                    link.publicationDate().toString(),
                    cleanField(link.issueNumber()),
                    cleanField(link.localId()),
                    cleanField(link.actUrl()),
                    discoveredAt.toString()
            ));
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    private static String fetch(String url) throws IOException {
        try (InputStream inputStream = URI.create(url).toURL().openStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String yearArchiveUrl(int year) {
        return BASE_URL + "/ricercaArchivioCompleto/serie_generale/" + year;
    }

    private static String normalizeHtml(String html) {
        return html == null ? "" : html.replace("&amp;", "&");
    }

    private static String absoluteUrl(String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        if (href.startsWith("/")) {
            return BASE_URL + href;
        }
        return BASE_URL + "/" + href;
    }

    private static String cleanField(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String valueOrDefault(String environmentName, String defaultValue) {
        String value = System.getenv(environmentName);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    public record IssueLink(LocalDate publicationDate, String issueNumber, String url) {
    }

    public record ArchiveActLink(LocalDate publicationDate, String issueNumber, String localId, String actUrl) {
    }
}
