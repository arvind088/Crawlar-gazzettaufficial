package it.legislation.crawler;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

public class GazzettaScraper {

    // Metadata fields to extract
    private static final String[] ELI_PROPERTIES = {
            "eli:title",
            "eli:date_publication",
            "eli:date_document",
            "eli:type_document",
            "eli:id_local",
            "eli:is_realized_by",
            "eli:is_embodied_by",
            "eli:version",
            "eli:format",
            "eli:language",
            "eli:publisher"
    };

    public static void main(String[] args) {
        String url = "https://www.gazzettaufficiale.it/atto/serie_generale/caricaDettaglioAtto/originario?atto.dataPubblicazioneGazzetta=2024-01-02&atto.codiceRedazionale=23A07021&elenco30giorni=false";
        processAct(url);
    }

    public static void processAct(String url) {
        try {
            Document doc = fetchDocument(url);
            Map<String, String> data = extractEliMetadata(doc, url);
            printToConsole(data);
            writeToCsv(data, "eli_metadata.ttl");
        } catch (IOException e) {
            System.err.println("Error processing URL: " + url);
            e.printStackTrace();
        }
    }

    public static Document fetchDocument(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .referrer("https://www.google.com/")
                .timeout(15 * 1000)
                .followRedirects(true)
                .get();
    }

    public static Map<String, String> extractEliMetadata(Document doc, String url) {
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

    public static void printToConsole(Map<String, String> data) {
        System.out.println("\nExtracted ELI Metadata:");
        data.forEach((key, value) -> System.out.println(key + ": " + value));
    }

    // This now writes TTL instead of CSV
    public static void writeToCsv(Map<String, String> data, String fileName) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(fileName))) {
            writer.println("@prefix eli: <http://data.europa.eu/eli/ontology#> .");
            writer.println("@prefix dct: <http://purl.org/dc/terms/> .");
            writer.println();

            String subject = "<" + data.get("Act URL") + ">";
            for (Map.Entry<String, String> entry : data.entrySet()) {
                String key = entry.getKey();
                if (key.equals("Act URL")) continue;
                String value = entry.getValue();
                if (value.equals("NOT FOUND")) continue;

                if (value.startsWith("http://") || value.startsWith("https://")) {
                    writer.println(subject + " eli:" + key.substring(4) + " <" + value + "> ;");
                } else {
                    writer.println(subject + " eli:" + key.substring(4) + " \"" + escape(value) + "\" ;");
                }
            }

            writer.println(".");
            System.out.println("TTL file '" + fileName + "' created successfully.");
        } catch (IOException e) {
            System.err.println("Error writing TTL file: " + fileName);
            e.printStackTrace();
        }
    }

    private static String escape(String value) {
        return value.replace("\"", "\\\"").replace("\n", "\\n");
    }
}
