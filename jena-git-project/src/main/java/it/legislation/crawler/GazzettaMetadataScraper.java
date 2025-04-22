package it.legislation.crawler;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

public class GazzettaMetadataScraper {

    public static void main(String[] args) throws IOException {
        List<LocalDate> allDates = getAllDatesIn2024();
        FileWriter csvWriter = new FileWriter("output/gazzetta_2024_metadata.csv");
        csvWriter.append("URL,eli:title,eli:date_publication,eli:type_document,eli:id_local\n");

        for (LocalDate date : allDates) {
            String dateStr = date.toString();  // yyyy-MM-dd
            String formatted = dateStr.replace("-", "/");  // yyyy/MM/dd
            System.out.println("Processing date: " + formatted);

            try {
                List<String> links = getEliLinksForDate(formatted);

                for (String link : links) {
                	Map<String, String> meta = extractEliMetadata(link);
                    csvWriter.append(link).append(",");
                    csvWriter.append(meta.getOrDefault("eli:title", "")).append(",");
                    csvWriter.append(meta.getOrDefault("eli:date_publication", "")).append(",");
                    csvWriter.append(meta.getOrDefault("eli:type_document", "")).append(",");
                    csvWriter.append(meta.getOrDefault("eli:id_local", "")).append("\n");
                }
            } catch (Exception e) {
                System.err.println("Error processing " + formatted + ": " + e.getMessage());
            }
        }

        csvWriter.flush();
        csvWriter.close();
        System.out.println("Scraping completed!");
    }

    private static List<LocalDate> getAllDatesIn2024() {
        LocalDate start = LocalDate.of(2024, 1, 1);
        LocalDate end = LocalDate.of(2024, 12, 31);
        List<LocalDate> dates = new ArrayList<>();
        while (!start.isAfter(end)) {
            dates.add(start);
            start = start.plusDays(1);
        }
        return dates;
    }

    private static List<String> getEliLinksForDate(String formattedDate) throws IOException {
        String baseUrl = "https://www.gazzettaufficiale.it/eli/gu/" + formattedDate + "/1/sg/html";
        Document doc = Jsoup.connect(baseUrl).userAgent("Mozilla/5.0").get();

        List<String> links = new ArrayList<>();
        for (Element aTag : doc.select("a[href*=/eli/id/]")) {
            String href = aTag.absUrl("href");
            if (href.contains("/eli/id/")) {
                links.add(href);
            }
        }
        return links;
    }

    private static Map<String, String> extractEliMetadata(String url) {
        Map<String, String> data = new HashMap<>();
        try {
            Document doc = Jsoup.connect(url).userAgent("Mozilla/5.0").get();
            Elements metas = doc.select("meta[property^=eli:]");
            for (Element meta : metas) {
                data.put(meta.attr("property"), meta.attr("content"));
            }
        } catch (IOException e) {
            System.err.println("Failed to fetch metadata from: " + url);
        }
        return data;
    }
}
