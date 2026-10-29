package it.legislation.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import it.legislation.source.gazzetta.GazzettaActPage;
import it.legislation.source.gazzetta.HttpPageFetcher;
import it.legislation.source.gazzetta.PageFetcher;

/**
 * Gazzetta Ufficiale discovery: the daily RSS feed drives the act-page crawler.
 *
 * <p>The RSS update ({@code GazzettaRssUpdateRunner}) logs every publication of the
 * Gazzetta in {@code data/clean/gazzetta_rss_updates.tsv}. This runner takes from it the
 * laws, decree-laws and legislative decrees, opens each act's Gazzetta page (once: pages
 * are saved in {@code data/raw/gazzetta}, the same place the Gazzetta check reads), reads
 * the act's official details (type, number from the heading, date of the act, GU issue,
 * entry into force), and checks whether Normattiva has the act yet (Layer 1).
 *
 * <p>The result, {@code data/clean/gazzetta_discovery.tsv}, lists each act with its status:
 * {@code IN_NORMATTIVA}, or {@code WAITING} when the Gazzetta has published it and
 * Normattiva has not delivered its text yet. The page's details are kept in the report, so
 * a run that cannot keep the HTML (the scheduled job on GitHub) does not fetch it again.
 *
 * <pre>
 *   mvn -q exec:java -Dexec.mainClass=it.legislation.ingest.GazzettaDiscoveryRunner [-Dexec.args="--offline"]
 * </pre>
 */
public class GazzettaDiscoveryRunner {

    public static final Path DEFAULT_RSS = Path.of("data", "clean", "gazzetta_rss_updates.tsv");
    public static final Path DEFAULT_OUT = Path.of("data", "clean", "gazzetta_discovery.tsv");

    /** "LEGGE 19 giugno 2026, n.103", "DECRETO-LEGGE 1° ottobre 2026, n. 171", "DECRETO LEGISLATIVO 3 …". */
    static final Pattern TITLE = Pattern.compile(
            "^(LEGGE|DECRETO-LEGGE|DECRETO LEGISLATIVO)\\s+(\\d{1,2})°?\\s+([a-zà]+)\\s+(\\d{4}),\\s*n\\.\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
    /** {@code …/eli/id/2026/06/20/26G00123/SG} → GU date and codice. */
    static final Pattern LINK = Pattern.compile("/eli/id/(\\d{4})/(\\d{2})/(\\d{2})/([0-9A-Za-z]+)/sg",
            Pattern.CASE_INSENSITIVE);
    /**
     * Acts have a "G" codice (26G00123). An "A" codice (26A05611) is a notice about an act, such as an
     * errata corrige or a coordinated text, whose title also starts with "LEGGE …".
     */
    static final Pattern ACT_CODE = Pattern.compile("\\d{2}G\\d{5}", Pattern.CASE_INSENSITIVE);
    private static final List<String> MONTHS = List.of("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
            "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre");
    private static final List<String> HEADER = List.of("codice", "gu_date", "type", "number", "date_of_act", "title",
            "status", "page", "gu_number", "entry_into_force", "gazzetta_url");

    /** One act announced by the RSS feed. */
    public record Found(String codice, LocalDate guDate, String type, String number, LocalDate dateOfAct, String title,
                        String status, String page, String guNumber, String entryIntoForce, String url) {
        boolean pageRead() {
            return "read".equals(page);
        }
    }

    /** What one run did. */
    public record Summary(List<Found> acts, int fetched, int waiting) {}

    private final PageFetcher fetcher;
    private final Path cache;
    private final boolean offline;

    public GazzettaDiscoveryRunner(PageFetcher fetcher, Path cache, boolean offline) {
        this.fetcher = fetcher;
        this.cache = cache;
        this.offline = offline;
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        boolean offline = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--offline")) {
                offline = true;
            } else if (i + 1 < args.length) {
                options.put(args[i], args[++i]);
            }
        }
        Path rss = Path.of(options.getOrDefault("--rss", DEFAULT_RSS.toString()));
        Path layer1 = Path.of(options.getOrDefault("--layer1", AknImportRunner.LAYER1.toString()));
        Path out = Path.of(options.getOrDefault("--out", DEFAULT_OUT.toString()));
        Path cache = Path.of(options.getOrDefault("--cache", GazzettaCheckRunner.DEFAULT_CACHE.toString()));
        long pause = Long.parseLong(options.getOrDefault("--pause-ms", "1000"));

        Summary s = new GazzettaDiscoveryRunner(new HttpPageFetcher(pause, 2), cache, offline)
                .run(rss, layer1, out, System.out::println);
        System.out.println();
        System.out.println("Acts announced by the Gazzetta RSS feed: " + s.acts().size()
                + " (pages fetched this run: " + s.fetched() + ")");
        System.out.println("Waiting for Normattiva: " + s.waiting());
        s.acts().stream().filter(f -> "WAITING".equals(f.status())).forEach(f ->
                System.out.println("  " + f.codice() + "  " + f.type() + " n. " + f.number() + "  GU " + f.guDate()
                        + "  " + f.url()));
        System.out.println("Wrote " + out);
    }

    public Summary run(Path rss, Path layer1, Path out, Consumer<String> progress) throws IOException {
        Set<String> inNormattiva = new HashSet<>();
        if (Files.exists(layer1)) {
            GazzettaCheckRunner.readActs(layer1).forEach(a -> inNormattiva.add(a.codice()));
        }
        Map<String, Found> known = read(out);
        List<Found> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int fetched = 0;
        for (String[] entry : rssEntries(rss)) {
            Optional<Found> announced = announced(entry[0], entry[1]);
            if (announced.isEmpty() || !seen.add(announced.get().codice())) {
                continue;
            }
            Found f = announced.get();
            Found before = known.get(f.codice());
            Found withPage;
            if (before != null && before.pageRead()) {
                withPage = before; // details already read on an earlier run
            } else {
                PageResult page = page(f);
                fetched += page.fetched ? 1 : 0;
                withPage = page.read.map(p -> new Found(f.codice(), f.guDate(), p.typeCode() == null ? f.type() : p.typeCode(),
                                p.number() == null ? f.number() : p.number(),
                                p.documentDate() == null ? f.dateOfAct() : p.documentDate(), f.title(), f.status(), "read",
                                p.guNumber() == null ? "" : p.guNumber(),
                                p.entryIntoForce() == null ? "" : p.entryIntoForce().toString(), f.url()))
                        .orElse(new Found(f.codice(), f.guDate(), f.type(), f.number(), f.dateOfAct(), f.title(), f.status(),
                                page.state, "", "", f.url()));
            }
            String status = inNormattiva.contains(f.codice()) ? "IN_NORMATTIVA" : "WAITING";
            Found row = new Found(withPage.codice(), withPage.guDate(), withPage.type(), withPage.number(),
                    withPage.dateOfAct(), withPage.title(), status, withPage.page(), withPage.guNumber(),
                    withPage.entryIntoForce(), withPage.url());
            found.add(row);
            if ("WAITING".equals(status)) {
                progress.accept("WAITING  " + row.codice() + "  " + row.type() + " n. " + row.number() + "  GU " + row.guDate());
            }
        }
        found.sort(java.util.Comparator.comparing(Found::guDate).reversed().thenComparing(Found::codice));
        write(out, found);
        int waiting = (int) found.stream().filter(f -> "WAITING".equals(f.status())).count();
        return new Summary(found, fetched, waiting);
    }

    /** A law, decree-law or legislative decree in an RSS entry, from its title and link; empty for anything else. */
    static Optional<Found> announced(String title, String link) {
        Matcher t = TITLE.matcher(title == null ? "" : title.trim());
        Matcher l = LINK.matcher(link == null ? "" : link);
        if (!t.find() || !l.find() || !ACT_CODE.matcher(l.group(4)).matches()) {
            return Optional.empty();
        }
        int month = MONTHS.indexOf(t.group(3).toLowerCase(Locale.ROOT)) + 1;
        if (month == 0) {
            return Optional.empty();
        }
        String type = switch (t.group(1).toUpperCase(Locale.ROOT)) {
            case "DECRETO-LEGGE" -> "DECRETO-LEGGE";
            case "DECRETO LEGISLATIVO" -> "DECRETO_LEGISLATIVO";
            default -> "LEGGE";
        };
        LocalDate guDate = LocalDate.of(Integer.parseInt(l.group(1)), Integer.parseInt(l.group(2)), Integer.parseInt(l.group(3)));
        LocalDate dateOfAct = LocalDate.of(Integer.parseInt(t.group(4)), month, Integer.parseInt(t.group(2)));
        String codice = l.group(4).toUpperCase(Locale.ROOT);
        String url = String.format("https://www.gazzettaufficiale.it/eli/id/%04d/%02d/%02d/%s/sg",
                guDate.getYear(), guDate.getMonthValue(), guDate.getDayOfMonth(), codice);
        return Optional.of(new Found(codice, guDate, type, t.group(5), dateOfAct, title.trim(), "", "", "", "", url));
    }

    private record PageResult(Optional<GazzettaActPage> read, String state, boolean fetched) {}

    /** The act's page: from the saved copy, else fetched and saved (unless offline). */
    private PageResult page(Found f) {
        Path file = cache.resolve(String.format("%04d_%02d_%02d_%s_sg.html", f.guDate().getYear(),
                f.guDate().getMonthValue(), f.guDate().getDayOfMonth(), f.codice()));
        try {
            if (Files.exists(file)) {
                return new PageResult(GazzettaActPage.parse(Files.readString(file, StandardCharsets.UTF_8), f.url()),
                        "read", false);
            }
            if (offline) {
                return new PageResult(Optional.empty(), "not fetched (offline)", false);
            }
            PageFetcher.Page page = fetcher.fetch(f.url());
            if (page.status() != 200) {
                return new PageResult(Optional.empty(), "HTTP " + page.status(), true);
            }
            Optional<GazzettaActPage> parsed = GazzettaActPage.parse(page.body(), f.url());
            if (parsed.isPresent()) {
                Files.createDirectories(cache);
                Files.writeString(file, page.body(), StandardCharsets.UTF_8);
            }
            return new PageResult(parsed, parsed.isPresent() ? "read" : "unreadable", true);
        } catch (IOException | RuntimeException e) {
            return new PageResult(Optional.empty(), "error: " + e.getMessage(), true);
        }
    }

    /** Title and link of each RSS entry. */
    private static List<String[]> rssEntries(Path rss) throws IOException {
        List<String[]> entries = new ArrayList<>();
        if (!Files.exists(rss)) {
            return entries;
        }
        List<String> lines = Files.readAllLines(rss, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return entries;
        }
        List<String> header = List.of(lines.get(0).split("\t", -1));
        int title = header.indexOf("title");
        int link = header.indexOf("link");
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split("\t", -1);
            if (title >= 0 && link >= 0 && cells.length > Math.max(title, link)) {
                entries.add(new String[] {cells[title], cells[link]});
            }
        }
        return entries;
    }

    /** The previous report, by codice, so details already read are not fetched again. */
    static Map<String, Found> read(Path out) throws IOException {
        Map<String, Found> rows = new LinkedHashMap<>();
        if (!Files.exists(out)) {
            return rows;
        }
        List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
        for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
            String[] c = line.split("\t", -1);
            if (c.length < HEADER.size()) {
                continue;
            }
            try {
                rows.put(c[0], new Found(c[0], LocalDate.parse(c[1]), c[2], c[3],
                        c[4].isBlank() ? null : LocalDate.parse(c[4]), c[5], c[6], c[7], c[8], c[9], c[10]));
            } catch (RuntimeException e) {
                // a malformed line is read again from the feed
            }
        }
        return rows;
    }

    private static void write(Path out, List<Found> rows) throws IOException {
        StringBuilder sb = new StringBuilder(String.join("\t", HEADER)).append('\n');
        for (Found f : rows) {
            sb.append(String.join("\t", f.codice(), f.guDate().toString(), f.type(), f.number(),
                    f.dateOfAct() == null ? "" : f.dateOfAct().toString(), f.title().replace('\t', ' '), f.status(),
                    f.page(), f.guNumber(), f.entryIntoForce(), f.url())).append('\n');
        }
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }
}
