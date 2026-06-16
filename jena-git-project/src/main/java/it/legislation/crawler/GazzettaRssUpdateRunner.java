package it.legislation.crawler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class GazzettaRssUpdateRunner {

    private static final String DEFAULT_RSS_URL = "https://www.gazzettaufficiale.it/rss/SG";
    private static final Path DEFAULT_OUTPUT = Path.of("data", "clean", "gazzetta_rss_updates.tsv");
    private static final int DEFAULT_MAX_ENTRIES = 100;
    private static final List<String> HEADER = List.of("title", "link", "published", "description", "fetchDate");

    public static void main(String[] args) throws IOException {
        String rssUrl = valueOrDefault("GAZZETTA_RSS_URL", DEFAULT_RSS_URL);
        Path output = Path.of(valueOrDefault("GAZZETTA_RSS_OUTPUT", DEFAULT_OUTPUT.toString()));
        int maxEntries = parseMaxEntries(valueOrDefault("GAZZETTA_RSS_MAX_ENTRIES", Integer.toString(DEFAULT_MAX_ENTRIES)));

        List<RssEntry> entries = fetchEntries(rssUrl, maxEntries);
        int added = appendNewEntries(entries, output, OffsetDateTime.now());

        System.out.println("Read RSS entries: " + entries.size());
        System.out.println("Added new RSS entries: " + added);
        System.out.println("RSS update file: " + output.toAbsolutePath().normalize());
    }

    public static List<RssEntry> fetchEntries(String rssUrl, int maxEntries) throws IOException {
        URI uri = URI.create(rssUrl);
        try (InputStream inputStream = uri.toURL().openStream()) {
            return parseEntries(inputStream, maxEntries);
        }
    }

    public static List<RssEntry> parseEntries(InputStream inputStream, int maxEntries) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);

            Document document = factory.newDocumentBuilder().parse(inputStream);
            NodeList items = document.getElementsByTagName("item");
            List<RssEntry> entries = new ArrayList<>();
            int limit = Math.max(0, maxEntries);

            for (int i = 0; i < items.getLength() && entries.size() < limit; i++) {
                Node node = items.item(i);
                if (node instanceof Element item) {
                    entries.add(new RssEntry(
                            childText(item, "title"),
                            childText(item, "link"),
                            childText(item, "pubDate"),
                            childText(item, "description")
                    ));
                }
            }

            return entries;
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Unable to parse Gazzetta RSS feed", e);
        }
    }

    public static int appendNewEntries(List<RssEntry> entries, Path output, OffsetDateTime fetchDate) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        boolean fileExists = Files.exists(output) && Files.size(output) > 0;
        Set<String> existingLinks = fileExists ? readExistingLinks(output) : new HashSet<>();
        List<String> linesToAppend = new ArrayList<>();

        if (!fileExists) {
            linesToAppend.add(String.join("\t", HEADER));
        }

        for (RssEntry entry : entries) {
            if (entry.link().isBlank() || existingLinks.contains(entry.link())) {
                continue;
            }

            linesToAppend.add(String.join("\t",
                    cleanField(entry.title()),
                    cleanField(entry.link()),
                    cleanField(entry.published()),
                    cleanField(entry.description()),
                    fetchDate.toString()
            ));
            existingLinks.add(entry.link());
        }

        if (!linesToAppend.isEmpty()) {
            Files.write(output, linesToAppend, StandardCharsets.UTF_8,
                    fileExists ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
        }

        int headerOffset = fileExists ? 0 : 1;
        return Math.max(0, linesToAppend.size() - headerOffset);
    }

    public static List<String> readLinks(Path output) throws IOException {
        if (!Files.exists(output) || Files.size(output) == 0) {
            return List.of();
        }

        List<String> links = new ArrayList<>();
        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);

        for (int i = 1; i < lines.size(); i++) {
            String[] values = lines.get(i).split("\t", -1);
            if (values.length > 1 && !values[1].isBlank()) {
                links.add(values[1].trim());
            }
        }

        return links;
    }

    private static Set<String> readExistingLinks(Path output) throws IOException {
        Set<String> links = new HashSet<>();
        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);

        for (int i = 1; i < lines.size(); i++) {
            String[] values = lines.get(i).split("\t", -1);
            if (values.length > 1 && !values[1].isBlank()) {
                links.add(values[1]);
            }
        }

        return links;
    }

    private static String childText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return "";
        }
        return nodes.item(0).getTextContent().trim();
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

    private static int parseMaxEntries(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_ENTRIES;
        }
    }

    public record RssEntry(String title, String link, String published, String description) {
    }
}
