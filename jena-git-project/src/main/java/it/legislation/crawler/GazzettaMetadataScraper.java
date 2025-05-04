package it.legislation.crawler;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.*;

public class GazzettaMetadataScraper {

    public static void main(String[] args) throws IOException {
        String archiveUrl = "https://www.gazzettaufficiale.it/ricercaArchivioCompleto/serie_generale/2024";

        System.out.println("\uD83D\uDD0D Crawling archive: " + archiveUrl);
        List<Issue> issues = getIssueLinks(archiveUrl);

        for (Issue issue : issues) {
            System.out.println("\uD83D\uDCC5 Issue: " + issue.publicationDate + " (n." + issue.number + ")");

            List<String> actUrls = getActLinks(issue);

            for (String actUrl : actUrls) {
                System.out.println("\uD83D\uDD17 Visiting act: " + actUrl);
                Map<String, String> meta = extractEliMetadata(actUrl);

                if (meta.isEmpty()) {
                    System.out.println("\u26A0\uFE0F  No metadata found.");
                } else {
                    System.out.println("\uD83D\uDCC4 Metadata:");
                    for (Map.Entry<String, String> entry : meta.entrySet()) {
                        System.out.println("   " + entry.getKey() + " = " + entry.getValue());
                    }
                }

                System.out.println("--------------------------------------------------");
            }
        }

        System.out.println("\u2705 Done.");
    }

    private static List<Issue> getIssueLinks(String archiveUrl) throws IOException {
        List<Issue> issues = new ArrayList<>();

        Document doc = Jsoup.connect(archiveUrl).userAgent("Mozilla/5.0").timeout(10000).get();
        Elements issueElements = doc.select("a[href*=/gazzetta/serie_generale/caricaDettaglio?]");

        for (Element el : issueElements) {
            String href = el.absUrl("href");
            String[] params = href.split("\\?");
            if (params.length < 2) continue;

            Map<String, String> query = parseQueryParams(params[1]);
            String date = query.get("dataPubblicazioneGazzetta");
            String number = query.get("numeroGazzetta");

            if (date != null && number != null) {
                issues.add(new Issue(date, number));
            }
        }

        return issues;
    }

    private static List<String> getActLinks(Issue issue) throws IOException {
        List<String> actUrls = new ArrayList<>();

        String issueUrl = "https://www.gazzettaufficiale.it/gazzetta/serie_generale/caricaDettaglio"
                + "?dataPubblicazioneGazzetta=" + issue.publicationDate
                + "&numeroGazzetta=" + issue.number;

        Document doc = Jsoup.connect(issueUrl).userAgent("Mozilla/5.0").timeout(10000).get();

        Elements links = doc.select("a[href*=/atto/serie_generale/caricaDettaglioAtto/originario?]");

        for (Element el : links) {
            String href = el.absUrl("href");
            String[] parts = href.split("\\?");
            if (parts.length < 2) continue;

            Map<String, String> query = parseQueryParams(parts[1]);
            String codice = query.get("atto.codiceRedazionale");

            if (codice != null) {
                String actUrl = "https://www.gazzettaufficiale.it/atto/serie_generale/caricaDettaglioAtto/originario"
                        + "?atto.dataPubblicazioneGazzetta=" + issue.publicationDate
                        + "&atto.codiceRedazionale=" + codice
                        + "&elenco30giorni=false";

                actUrls.add(actUrl);
            }
        }

        return actUrls;
    }

    private static Map<String, String> extractEliMetadata(String url) {
        Map<String, String> data = new HashMap<>();
        try {
            Document doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10000).get();
            Elements metaTags = doc.select("meta[property^=eli:], meta[name^=eli:]");

            for (Element meta : metaTags) {
                String property = meta.hasAttr("property") ? meta.attr("property") : meta.attr("name");
                String content = meta.hasAttr("content") ? meta.attr("content") : meta.attr("resource");
                data.put(property, content);
            }

            if (!data.containsKey("eli:title")) {
                String visibleTitle = doc.select("h1.titoloatto").text();
                if (!visibleTitle.isEmpty()) {
                    data.put("eli:title", visibleTitle);
                }
            }

            if (!data.containsKey("eli:id_local") && url.contains("codiceRedazionale=")) {
                String[] parts = url.split("codiceRedazionale=");
                if (parts.length > 1) {
                    data.put("eli:id_local", parts[1].split("&")[0]);
                }
            }

        } catch (IOException e) {
            System.err.println("\u274C Failed to extract metadata from: " + url);
        }

        return data;
    }

    private static Map<String, String> parseQueryParams(String queryString) {
        Map<String, String> map = new HashMap<>();
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            if (kv.length == 2) {
                map.put(kv[0], kv[1]);
            }
        }
        return map;
    }

    private static class Issue {
        String publicationDate;
        String number;

        public Issue(String publicationDate, String number) {
            this.publicationDate = publicationDate;
            this.number = number;
        }
    }
}
