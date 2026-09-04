package it.legislation.crawler;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class GazzettaScraper {

    private static final String SITE_BASE_URL = "https://www.gazzettaufficiale.it";
    private static final String ELI_BASE_URL = "http://www.gazzettaufficiale.it";
    private static final String ISSUE_URL_TEMPLATE = SITE_BASE_URL + "/gazzetta/serie_generale/caricaDettaglio?dataPubblicazioneGazzetta=%s&numeroGazzetta=%s";
    private static final String ACT_URL_TEMPLATE = SITE_BASE_URL + "/atto/serie_generale/caricaDettaglioAtto/originario?atto.dataPubblicazioneGazzetta=%s&atto.codiceRedazionale=%s&elenco30giorni=false";
    private static final Path TTL_OUTPUT_PATH = Path.of("data", "rdf", "gazzetta_metadata_delta.ttl");
    private static final Path RAW_GAZZETTA_DIR = Path.of("data", "raw", "gazzetta");
    private static final Path REGISTRY_FILE = Path.of("data", "registry", "crawl_registry.tsv");

    private static final String[] ELI_PROPERTIES = {
            "eli:title", "eli:date_publication", "eli:date_document", "eli:type_document",
            "eli:id_local", "eli:is_realized_by", "eli:is_embodied_by", "eli:version",
            "eli:format", "eli:language", "eli:publisher"
    };

    public static void main(String[] args) {
        try {
            List<String[]> issues = getIssuesList("2025");
            System.out.println("Total issues found: " + issues.size());

            crawlGazzettaIssues(issues);
        } catch (IOException e) {
            System.err.println("Fatal error:");
            e.printStackTrace();
        }
    }

    public static List<String[]> getIssuesList(String year) throws IOException {
        List<String[]> issues = new ArrayList<>();
        String archiveUrl = SITE_BASE_URL + "/ricercaArchivioCompleto/serie_generale/" + year;

        Document doc = Jsoup.connect(archiveUrl).userAgent("Mozilla").get();
        Elements items = doc.select("a:containsOwn(n°)");

        for (Element item : items) {
            String text = item.text(); // e.g., "n° 4 del 09-01-2025"

            if (text.contains("del")) {
                String numero = text.substring(2, text.indexOf("del")).replaceAll("\\D+", "").trim();
                String date = text.substring(text.indexOf("del") + 4).trim(); // dd-mm-yyyy

                String[] parts = date.split("-");
                if (parts.length == 3) {
                    String formattedDate = parts[2] + "-" + parts[1] + "-" + parts[0];
                    issues.add(new String[]{formattedDate, numero});
                }
            }
        }

        return issues;
    }

    public static void crawlGazzettaIssues(List<String[]> issues) {
        List<CleanLegalActRecord> records = new ArrayList<>();
        CrawlRegistry registry = new CrawlRegistry(REGISTRY_FILE);

        try {
            registry.ensureExists();
        } catch (IOException e) {
            System.err.println("Unable to initialize crawl registry: " + REGISTRY_FILE);
            e.printStackTrace();
            return;
        }

        for (String[] entry : issues) {
            String date = entry[0];
            String numero = entry[1];
            try {
                String issueUrl = String.format(ISSUE_URL_TEMPLATE, date, numero);
                Document doc = fetchDocument(issueUrl);

                Set<String> codes = extractCodiceRedazionale(doc);

                for (String codice : codes) {
                    String actUrl = String.format(ACT_URL_TEMPLATE, date, codice);
                    processAct(actUrl, registry).ifPresent(records::add);
                }
            } catch (IOException e) {
                System.err.printf("Error accessing issue for %s (Gazzetta n. %s)%n", date, numero);
                e.printStackTrace();
            }
        }

        writeRecordsToTurtle(records, TTL_OUTPUT_PATH);
    }

    public static int crawlGazzettaActUrls(List<String> actUrls) {
        List<CleanLegalActRecord> records = new ArrayList<>();
        CrawlRegistry registry = new CrawlRegistry(REGISTRY_FILE);

        try {
            registry.ensureExists();
        } catch (IOException e) {
            System.err.println("Unable to initialize crawl registry: " + REGISTRY_FILE);
            e.printStackTrace();
            return 0;
        }

        for (String actUrl : actUrls) {
            if (actUrl == null || actUrl.isBlank()) {
                continue;
            }
            processAct(actUrl.trim(), registry).ifPresent(records::add);
        }

        writeRecordsToTurtle(records, TTL_OUTPUT_PATH);
        return records.size();
    }

    private static String fetchHtml(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .referrer("https://www.google.com/")
                .timeout(15000)
                .followRedirects(true)
                .execute()
                .body();
    }

    private static Document fetchDocument(String url) throws IOException {
        return Jsoup.parse(fetchHtml(url), url);
    }

    private static Set<String> extractCodiceRedazionale(Document doc) {
        Elements links = doc.select("a[href*='atto.codiceRedazionale=']");
        Set<String> codes = new HashSet<>();

        for (Element link : links) {
            String href = link.attr("href");
            int start = href.indexOf("atto.codiceRedazionale=") + "atto.codiceRedazionale=".length();
            int end = href.indexOf("&", start);

            if (start > 0 && end > start) {
                codes.add(href.substring(start, end));
            }
        }
        return codes;
    }

    private static Optional<CleanLegalActRecord> processAct(String url, CrawlRegistry registry) {
        try {
            String html = fetchHtml(url);
            return processActHtml(url, html, registry, RAW_GAZZETTA_DIR);
        } catch (IOException e) {
            System.err.println("Error processing URL: " + url);
            e.printStackTrace();
            return Optional.empty();
        }
    }

    static Optional<CleanLegalActRecord> processActHtml(String url, String html, CrawlRegistry registry, Path rawRoot) throws IOException {
        Document doc = Jsoup.parse(html, url);
        Map<String, String> data = extractEliMetadata(doc, url);
        printToConsole(data);
        Optional<CleanLegalActRecord> maybeRecord = extractCleanLegalActRecord(doc, url);

        if (maybeRecord.isEmpty()) {
            System.out.println("Skipped act because no canonical ELI URI could be extracted: " + url);
            return Optional.empty();
        }

        CleanLegalActRecord record = maybeRecord.get();
        String contentHash = sha256(html);
        cacheRawHtml(record, html, rawRoot);

        CrawlRegistry.UpdateStatus status = registry.upsertSuccess(record, url, contentHash, OffsetDateTime.now());
        System.out.println("Registry status for " + record.getEliUri() + ": " + status);

        if (status == CrawlRegistry.UpdateStatus.UNCHANGED) {
            return Optional.empty();
        }

        return Optional.of(record);
    }

    private static Map<String, String> extractEliMetadata(Document doc, String url) {
        Map<String, String> extractedData = new LinkedHashMap<>();
        extractedData.put("Act URL", url);

        for (String property : ELI_PROPERTIES) {
            Element element = doc.selectFirst("[property=" + property + "]");
            String value = "NOT FOUND";
            if (element != null) {
                if (element.hasAttr("content")) {
                    value = element.attr("content");
                } else if (element.hasAttr("resource")) {
                    value = element.attr("resource");
                } else {
                    value = element.text();
                }
            }
            extractedData.put(property, value.trim());
        }

        return extractedData;
    }

    static Optional<CleanLegalActRecord> extractCleanLegalActRecord(Document doc, String sourceUrl) {
        Map<String, String> data = extractEliMetadata(doc, sourceUrl);
        String eliUri = findCanonicalEliUri(doc, sourceUrl, data);

        if (!CleanLegalActRecord.hasValue(eliUri)) {
            return Optional.empty();
        }

        CleanLegalActRecord.Builder builder = CleanLegalActRecord.builder(eliUri)
                .title(data.get("eli:title"))
                .publicationDate(parseDate(data.get("eli:date_publication")).orElse(null))
                .documentDate(parseDate(data.get("eli:date_document")).orElse(null))
                .documentTypeUri(normalizeResourceUri(data.get("eli:type_document")).orElse(null))
                .localId(data.get("eli:id_local"))
                .realizedByUri(normalizeResourceUri(data.get("eli:is_realized_by")).orElse(null))
                .embodiedByUri(normalizeResourceUri(data.get("eli:is_embodied_by")).orElse(null))
                .versionUri(normalizeResourceUri(data.get("eli:version")).orElse(null))
                .formatUri(normalizeFormatUri(data.get("eli:format")).orElse(null))
                .languageUri(normalizeResourceUri(data.get("eli:language")).orElse(null))
                .publisherUri(normalizeResourceUri(data.get("eli:publisher")).orElse(null))
                .sourceUrl(sourceUrl);

        return Optional.of(builder.build());
    }

    private static void printToConsole(Map<String, String> data) {
        System.out.println("\nExtracted ELI Metadata:");
        data.forEach((key, value) -> System.out.println(key + ": " + value));
    }

    static void writeRecordsToTurtle(List<CleanLegalActRecord> records, Path outputPath) {
        if (records.isEmpty()) {
            System.out.println("No new or changed legal act records; Turtle delta output was not written.");
            return;
        }

        try {
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Model model = ModelFactory.createDefaultModel();
            if (Files.exists(outputPath)) {
                try (var inputStream = Files.newInputStream(outputPath)) {
                    RDFDataMgr.read(model, inputStream, org.apache.jena.riot.Lang.TURTLE);
                }
            }

            Model newRecords = RdfModelBuilder.fromEnvironment().buildLegalActs(records);
            for (CleanLegalActRecord record : records) {
                Resource act = model.createResource(record.getEliUri());
                model.removeAll(act, null, null);
            }
            model.add(newRecords);

            try (OutputStream outputStream = Files.newOutputStream(outputPath)) {
                RDFDataMgr.write(outputStream, model, RDFFormat.TURTLE_PRETTY);
            }

            System.out.println("Merged " + records.size() + " new/changed legal act records into " + outputPath.toAbsolutePath().normalize());
        } catch (IOException e) {
            System.err.println("Error writing Turtle output: " + outputPath);
            e.printStackTrace();
        }
    }

    private static void cacheRawHtml(CleanLegalActRecord record, String html, Path rawRoot) throws IOException {
        Files.createDirectories(rawRoot);
        Files.writeString(cachePathForEliUri(rawRoot, record.getEliUri()), html, StandardCharsets.UTF_8);
    }

    static Path cachePathForEliUri(Path rawRoot, String eliUri) {
        String normalized = eliUri.replaceFirst("^https?://www\\.gazzettaufficiale\\.it/eli/id/", "");
        String fileName = normalized.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (fileName.isBlank()) {
            fileName = "unknown";
        }
        return rawRoot.resolve(fileName + ".html");
    }

    static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String findCanonicalEliUri(Document doc, String sourceUrl, Map<String, String> data) {
        Optional<String> legalResourceAbout = firstNormalizedAbout(doc, "[typeof=eli:LegalResource]");
        if (legalResourceAbout.isPresent()) {
            return legalResourceAbout.get();
        }

        Optional<String> datedResourceAbout = firstNormalizedAbout(doc, "[property=eli:date_publication]");
        if (datedResourceAbout.isPresent()) {
            return datedResourceAbout.get();
        }

        Optional<String> fromRealizedBy = deriveLegalResourceFromNestedUri(data.get("eli:is_realized_by"), "/ita");
        if (fromRealizedBy.isPresent()) {
            return fromRealizedBy.get();
        }

        Optional<String> fromEmbodiedBy = deriveLegalResourceFromNestedUri(data.get("eli:is_embodied_by"), "/ita/html");
        if (fromEmbodiedBy.isPresent()) {
            return fromEmbodiedBy.get();
        }

        return canonicalEliUriFromActUrl(sourceUrl, data).orElse(sourceUrl);
    }

    private static Optional<String> firstNormalizedAbout(Document doc, String selector) {
        Element element = doc.selectFirst(selector + "[about]");
        if (element == null) {
            return Optional.empty();
        }
        return normalizeResourceUri(element.attr("about"));
    }

    private static Optional<String> deriveLegalResourceFromNestedUri(String value, String suffix) {
        return normalizeResourceUri(value)
                .filter(uri -> uri.endsWith(suffix))
                .map(uri -> uri.substring(0, uri.length() - suffix.length()));
    }

    private static Optional<String> canonicalEliUriFromActUrl(String sourceUrl, Map<String, String> data) {
        Optional<LocalDate> publicationDate = parseDate(data.get("eli:date_publication"));
        String localId = data.get("eli:id_local");

        if (publicationDate.isEmpty() || !CleanLegalActRecord.hasValue(localId)) {
            Map<String, String> query = parseQuery(sourceUrl);
            publicationDate = parseDate(query.get("atto.dataPubblicazioneGazzetta"));
            localId = query.get("atto.codiceRedazionale");
        }

        if (publicationDate.isEmpty() || !CleanLegalActRecord.hasValue(localId)) {
            return Optional.empty();
        }

        LocalDate date = publicationDate.get();
        return Optional.of(String.format(
                "%s/eli/id/%04d/%02d/%02d/%s/sg",
                ELI_BASE_URL,
                date.getYear(),
                date.getMonthValue(),
                date.getDayOfMonth(),
                localId.trim()
        ));
    }

    private static Map<String, String> parseQuery(String sourceUrl) {
        Map<String, String> values = new HashMap<>();
        try {
            String query = URI.create(sourceUrl).getRawQuery();
            if (query == null) {
                return values;
            }

            for (String pair : query.split("&")) {
                int separator = pair.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = decode(pair.substring(0, separator));
                String value = decode(pair.substring(separator + 1));
                values.put(key, value);
            }
        } catch (IllegalArgumentException ignored) {
            return values;
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static Optional<LocalDate> parseDate(String value) {
        if (!CleanLegalActRecord.hasValue(value)) {
            return Optional.empty();
        }

        try {
            return Optional.of(LocalDate.parse(value.trim()));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> normalizeFormatUri(String value) {
        if ("text/html".equalsIgnoreCase(value == null ? "" : value.trim())) {
            return Optional.of("http://www.iana.org/assignments/media-types/text/html");
        }
        return normalizeResourceUri(value);
    }

    private static Optional<String> normalizeResourceUri(String value) {
        if (!CleanLegalActRecord.hasValue(value)) {
            return Optional.empty();
        }

        String trimmed = value.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return Optional.of(trimmed);
        }
        if (trimmed.startsWith("gu:")) {
            return Optional.of(RdfModelBuilder.GU_NS + trimmed.substring("gu:".length()));
        }

        return Optional.empty();
    }
}
