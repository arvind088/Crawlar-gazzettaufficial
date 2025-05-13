package it.legislation.crawler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

public class GazzettaScraper {

    private static final String BASE_URL = "https://www.gazzettaufficiale.it";
    private static final String ISSUE_URL_TEMPLATE = BASE_URL + "/gazzetta/serie_generale/caricaDettaglio?dataPubblicazioneGazzetta=%s&numeroGazzetta=%s";
    private static final String ACT_URL_TEMPLATE = BASE_URL + "/atto/serie_generale/caricaDettaglioAtto/originario?atto.dataPubblicazioneGazzetta=%s&atto.codiceRedazionale=%s&elenco30giorni=false";
    private static final String TTL_FILE_NAME = "eli_metadata.ttl";

    private static final String[] ELI_PROPERTIES = {
            "eli:title", "eli:date_publication", "eli:date_document", "eli:type_document",
            "eli:id_local", "eli:is_realized_by", "eli:is_embodied_by", "eli:version",
            "eli:format", "eli:language", "eli:publisher"
    };

    public static void main(String[] args) {
        List<String[]> issues = Arrays.asList(
                new String[]{"2025-01-02", "1"},
                new String[]{"2025-01-03", "2"}
        );

        crawlGazzettaIssues(issues);
    }

    public static void crawlGazzettaIssues(List<String[]> issues) {
        for (String[] entry : issues) {
            String date = entry[0];
            String numero = entry[1];
            try {
                String issueUrl = String.format(ISSUE_URL_TEMPLATE, date, numero);
                Document doc = fetchDocument(issueUrl);

                Set<String> codes = extractCodiceRedazionale(doc);

                for (String codice : codes) {
                    String actUrl = String.format(ACT_URL_TEMPLATE, date, codice);
                    processAct(actUrl);
                }
            } catch (IOException e) {
                System.err.printf("Error accessing issue for %s (Gazzetta n. %s)%n", date, numero);
                e.printStackTrace();
            }
        }
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

    private static void processAct(String url) {
        try {
            Document doc = fetchDocument(url);
            Map<String, String> data = extractEliMetadata(doc, url);
            printToConsole(data);
            writeToTurtle(data, TTL_FILE_NAME);
        } catch (IOException e) {
            System.err.println("Error processing URL: " + url);
            e.printStackTrace();
        }
    }

    private static Document fetchDocument(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .referrer("https://www.google.com/")
                .timeout(15000)
                .followRedirects(true)
                .get();
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

    private static void printToConsole(Map<String, String> data) {
        System.out.println("\nExtracted ELI Metadata:");
        data.forEach((key, value) -> System.out.println(key + ": " + value));
    }

    private static void writeToTurtle(Map<String, String> data, String fileName) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(fileName, true))) {
            writer.println("@prefix eli: <http://data.europa.eu/eli/ontology#> .");
            writer.println("@prefix dct: <http://purl.org/dc/terms/> .");
            writer.println();

            String subject = "<" + data.get("Act URL") + ">";
            for (Map.Entry<String, String> entry : data.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key.equals("Act URL") || value.equals("NOT FOUND")) continue;

                String predicate = "eli:" + key.substring(4);
                if (value.startsWith("http://") || value.startsWith("https://")) {
                    writer.println(subject + " " + predicate + " <" + value + "> ;");
                } else {
                    writer.println(subject + " " + predicate + " \"" + escape(value) + "\" ;");
                }
            }
            writer.println(".");
            System.out.println("TTL file '" + fileName + "' updated successfully.");
        } catch (IOException e) {
            System.err.println("Error writing TTL file: " + fileName);
            e.printStackTrace();
        }
    }

    private static String escape(String value) {
        return value.replace("\"", "\\\"").replace("\n", "\\n");
    }
}
