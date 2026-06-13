package it.legislation.crawler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class GazzettaRssCrawlerRunner {

    private static final Path DEFAULT_RSS_UPDATES = Path.of("data", "clean", "gazzetta_rss_updates.tsv");

    public static void main(String[] args) throws IOException {
        Path input = args.length > 0 ? Path.of(args[0]) : DEFAULT_RSS_UPDATES;
        List<String> actUrls = GazzettaRssUpdateRunner.readLinks(input);

        if (actUrls.isEmpty()) {
            System.out.println("No Gazzetta RSS links found in: " + input.toAbsolutePath().normalize());
            return;
        }

        System.out.println("Read Gazzetta RSS links: " + actUrls.size());
        int changedRecords = GazzettaScraper.crawlGazzettaActUrls(actUrls);
        System.out.println("New or changed Gazzetta act records: " + changedRecords);
    }
}
