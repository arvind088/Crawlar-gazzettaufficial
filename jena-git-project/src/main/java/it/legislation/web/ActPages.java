package it.legislation.web;

import static it.legislation.web.Html.badge;
import static it.legislation.web.Html.escape;
import static it.legislation.web.I18n.t;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.legislation.eli.EliUriService;
import it.legislation.web.ActCatalog.ActDetails;
import it.legislation.web.ActCatalog.ActRow;
import it.legislation.web.ActCatalog.Relation;
import it.legislation.web.ActCatalog.Version;

/**
 * The Legal acts view ({@code /}) and the Act details view (served at each
 * act's own ELI address by {@code EliResolutionController}).
 */
@RestController
public class ActPages {

    static final MediaType HTML = MediaType.parseMediaType("text/html;charset=UTF-8");
    private static final int PAGE_SIZE = 50;
    /** Relations shown before "Show the other N". */
    private static final int RELATIONS_SHOWN = 15;
    private static final String ELI = ActCatalog.ELI;
    private static final String OWL_SAME_AS = "http://www.w3.org/2002/07/owl#sameAs";

    private static final Map<String, String> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("LEGGE", "Legge");
        TYPES.put("DECRETO-LEGGE", "Decreto-legge");
        TYPES.put("DECRETO_LEGISLATIVO", "Decreto legislativo");
    }

    private final ActCatalog catalog;
    private final EliUriService uris;

    private final DataFreshness freshness;

    public ActPages(ActCatalog catalog, EliUriService uris) {
        this(catalog, uris, new DataFreshness());
    }

    @Autowired
    public ActPages(ActCatalog catalog, EliUriService uris, DataFreshness freshness) {
        this.catalog = catalog;
        this.freshness = freshness;
        this.uris = uris;
    }

    // ------------------------------------------------------------ Legal acts

    @GetMapping({"/", "/acts"})
    public ResponseEntity<String> list(@RequestParam(name = "q", required = false) String q,
                                       @RequestParam(name = "type", required = false) String type,
                                       @RequestParam(name = "year", required = false) String year,
                                       @RequestParam(name = "status", required = false) String status,
                                       @RequestParam(name = "page", defaultValue = "1") int page) throws IOException {
        List<ActRow> all = catalog.acts();
        ActCatalog.Totals totals = catalog.totals();
        List<ActRow> shown = filter(all, q, type, year, status);
        int pages = Math.max(1, (shown.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 1), pages);
        List<ActRow> slice = shown.subList((current - 1) * PAGE_SIZE, Math.min(current * PAGE_SIZE, shown.size()));

        StringBuilder body = new StringBuilder();
        body.append("<h1 class=\"sr-title\">").append(escape(t("nav.acts"))).append("</h1>\n");

        // Search first: it is what most visitors come for.
        body.append("<form class=\"card search\" method=\"get\" action=\"/\" role=\"search\">")
                .append("<label for=\"q\">").append(escape(t("search.label"))).append("</label><div class=\"row\">")
                .append("<input id=\"q\" type=\"search\" name=\"q\" value=\"").append(escape(q))
                .append("\" placeholder=\"").append(escape(t("search.placeholder"))).append("\">")
                .append(hidden("type", type)).append(hidden("year", year)).append(hidden("status", status))
                .append("<button type=\"submit\">").append(escape(t("search.button"))).append("</button></div></form>\n");

        body.append(scope(all)).append(tiles(all, totals));

        TreeSet<String> years = new TreeSet<>(java.util.Comparator.reverseOrder());
        TreeSet<String> statuses = new TreeSet<>();
        for (ActRow row : all) {
            years.add(row.year());
            if (row.conversionStatus() != null) {
                statuses.add(row.conversionStatus());
            }
        }
        boolean filtered = !(blank(q) && blank(type) && blank(year) && blank(status));
        body.append("<div class=\"split\">\n<aside class=\"side\"><section class=\"card filters\" aria-label=\"")
                .append(escape(t("filters"))).append("\"><h2>").append(escape(t("filters")))
                .append("</h2><form method=\"get\" action=\"/\">").append(hidden("q", q))
                .append(select("type", t("filter.type"), type, TYPES))
                .append(select("year", t("filter.year"), year, toMap(years, false)))
                .append(select("status", t("filter.status"), status, toMap(statuses, true)))
                .append("<button type=\"submit\">").append(escape(t("filter.apply"))).append("</button>")
                .append(filtered ? "<a class=\"button secondary\" href=\"/\">" + escape(t("filter.clear")) + "</a>" : "")
                .append("</form></section></aside>\n");

        body.append("<section class=\"mainc\" aria-label=\"").append(escape(t("results.aria")))
                .append("\"><div class=\"results-head\"><h2>")
                .append(I18n.count("results.count", shown.size(), number(shown.size()))).append("</h2>")
                .append("<span class=\"muted\">").append(pages > 1 ? t("results.page", current, pages) + " &middot; " : "")
                .append(escape(t("results.order"))).append(" &middot; <a href=\"")
                .append(escape(listUrl("/acts.csv", 0, q, type, year, status)))
                .append("\">").append(escape(t("results.csv"))).append("</a></span></div>\n");
        if (slice.isEmpty()) {
            body.append("<div class=\"card\">").append(escape(t("results.none"))).append("</div>\n");
        }
        for (ActRow row : slice) {
            body.append("<article class=\"act-card\"><div class=\"act-head\"><span class=\"type\">")
                    .append(escape(Html.typeName(row.typeCode()))).append("</span><a class=\"cite\" href=\"")
                    .append(escape(link(row.uri()))).append("\">").append(escape(row.citation())).append("</a></div>")
                    .append(row.title() == null ? "" : "<p>" + escape(shorten(row.title(), 320)) + "</p>")
                    .append("<div class=\"labels\">").append(badge(row.conversionStatus()))
                    .append("<span class=\"muted\">").append(I18n.count("versions.count", row.versions(), number(row.versions())))
                    .append("</span><span class=\"mono muted\">").append(escape(row.localId())).append("</span></div></article>\n");
        }
        body.append(pager(current, pages, q, type, year, status)).append("</section>\n</div>\n");
        return ResponseEntity.ok().contentType(HTML).body(Html.page(t("nav.acts"), "acts", null, body.toString()));
    }

    /** The same list as CSV, with the same filters. */
    @GetMapping("/acts.csv")
    public ResponseEntity<String> csv(@RequestParam(name = "q", required = false) String q,
                                      @RequestParam(name = "type", required = false) String type,
                                      @RequestParam(name = "year", required = false) String year,
                                      @RequestParam(name = "status", required = false) String status) throws IOException {
        StringBuilder csv = new StringBuilder("eli,code,type,date,number,citation,title,versions,conversion\r\n");
        for (ActRow row : filter(catalog.acts(), q, type, year, status)) {
            csv.append(String.join(",", cell(row.uri()), cell(row.localId()), cell(row.typeCode()), cell(row.date()),
                    cell(row.number()), cell(row.citation()), cell(row.title()), String.valueOf(row.versions()),
                    cell(row.conversionStatus()))).append("\r\n");
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"legal-acts.csv\"").body(csv.toString());
    }

    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        return value.matches(".*[\",\r\n].*") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static List<ActRow> filter(List<ActRow> all, String q, String type, String year, String status) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<ActRow> shown = new ArrayList<>();
        for (ActRow row : all) {
            if ((!blank(type) && !type.equals(row.typeCode()))
                    || (!blank(year) && !year.equals(row.year()))
                    || (!blank(status) && !status.equals(row.conversionStatus()))
                    || (!needle.isEmpty() && !matches(row, needle))) {
                continue;
            }
            shown.add(row);
        }
        return shown;
    }

    /**
     * The line above the numbers: they cover the whole dataset (searching does not
     * change them), how recent it is, and from which year the acts start.
     */
    private String scope(List<ActRow> all) {
        StringBuilder line = new StringBuilder(escape(t("tiles.scope")));
        freshness.updatedTo().ifPresent(day -> line.append(" &middot; ").append(escape(t("tiles.updated", longDate(day)))));
        all.stream().map(ActRow::year).filter(y -> y != null && !y.isBlank()).min(String::compareTo)
                .ifPresent(year -> line.append(" &middot; ").append(escape(t("tiles.from", year))));
        return "<p class=\"scope muted\">" + line + "</p>\n";
    }

    private static String longDate(LocalDate day) {
        return day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", I18n.italian() ? Locale.ITALIAN : Locale.ENGLISH));
    }

    /** The four numbers at the head of the list: acts, versions, relations, decree-laws. */
    private static String tiles(List<ActRow> all, ActCatalog.Totals totals) {
        long decrees = all.stream().filter(r -> "DECRETO-LEGGE".equals(r.typeCode())).count();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        all.stream().filter(r -> "DECRETO-LEGGE".equals(r.typeCode()) && r.conversionStatus() != null)
                .forEach(r -> byStatus.merge(r.conversionStatus(), 1L, Long::sum));

        StringBuilder html = new StringBuilder("<section class=\"tiles\" aria-label=\"")
                .append(escape(t("tiles.aria"))).append("\">");
        html.append(tile(t("tile.acts"), number(totals.acts()), "", escape(t("tile.acts.sub"))));
        html.append(tile(t("tile.versions.title"), number(totals.versions()), "", escape(t("tile.versions.sub"))));

        StringBuilder bar = new StringBuilder("<div class=\"bar\" role=\"img\" aria-label=\"");
        StringBuilder parts = new StringBuilder();
        StringBuilder spans = new StringBuilder();
        for (String kind : List.of("amends", "changes", "repeals")) {
            long n = totals.relations(kind);
            if (n > 0) {
                parts.append(parts.length() > 0 ? " &middot; " : "").append(escape(t("kind." + kind))).append(' ')
                        .append(number(n));
                spans.append("<span class=\"").append(kind).append("\" style=\"flex:").append(n).append("\"></span>");
            }
        }
        bar.append(parts.toString().replace(" &middot; ", ", ")).append("\">").append(spans).append("</div>");
        html.append("<div class=\"tile\"><span class=\"k\">").append(escape(t("tile.relations")))
                .append("</span><span class=\"v\">")
                .append(number(totals.relations())).append("</span>").append(bar)
                .append("<span class=\"s\">").append(parts).append("</span></div>");

        html.append(tile(t("tile.decrees"), number(decrees), "", escape(t("tile.decrees.sub",
                number(byStatus.getOrDefault("CONVERTED", 0L)), number(byStatus.getOrDefault("NOT_CONVERTED", 0L)),
                number(byStatus.getOrDefault("PENDING", 0L))))));
        return html.append("</section>\n").toString();
    }

    private static String tile(String label, String value, String tone, String sub) {
        return "<div class=\"tile\"><span class=\"k\">" + escape(label) + "</span><span class=\"v" + (tone.isEmpty() ? "" : " " + tone)
                + "\">" + value + "</span><span class=\"s\">" + sub + "</span></div>";
    }

    private static String number(long n) {
        return I18n.number(n);
    }

    private static String hidden(String name, String value) {
        return blank(value) ? "" : "<input type=\"hidden\" name=\"" + name + "\" value=\"" + escape(value) + "\">";
    }

    private static boolean matches(ActRow row, String needle) {
        return contains(row.title(), needle) || contains(row.localId(), needle) || needle.equals(row.number())
                || contains(row.citation(), needle);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static Map<String, String> toMap(Iterable<String> values, boolean statuses) {
        Map<String, String> map = new LinkedHashMap<>();
        values.forEach(v -> map.put(v, statuses ? Html.statusWords(v) : v));
        return map;
    }

    private static String select(String name, String label, String selected, Map<String, String> options) {
        StringBuilder html = new StringBuilder("<label>").append(label).append("<select name=\"").append(name)
                .append("\"><option value=\"\">").append(escape(t("filter.all"))).append("</option>");
        options.forEach((value, text) -> html.append(option(value, text, selected)));
        return html.append("</select></label>").toString();
    }

    private static String option(String value, String text, String selected) {
        return "<option value=\"" + escape(value) + "\"" + (value.equals(selected) ? " selected" : "") + ">"
                + escape(text) + "</option>";
    }

    /** Page numbers: first, last, and two either side of the current page. */
    private static String pager(int current, int pages, String q, String type, String year, String status) {
        if (pages <= 1) {
            return "";
        }
        StringBuilder html = new StringBuilder("<nav class=\"pager\" aria-label=\"").append(escape(t("pager.aria"))).append("\">");
        if (current > 1) {
            html.append("<a href=\"").append(escape(listUrl("/", current - 1, q, type, year, status))).append("\">")
                    .append(escape(t("pager.prev"))).append("</a>");
        }
        int last = 0;
        for (int p = 1; p <= pages; p++) {
            if (p != 1 && p != pages && Math.abs(p - current) > 2) {
                continue;
            }
            if (last > 0 && p > last + 1) {
                html.append("<span class=\"gap\">&hellip;</span>");
            }
            html.append("<a href=\"").append(escape(listUrl("/", p, q, type, year, status))).append("\"")
                    .append(p == current ? " aria-current=\"page\"" : "").append(">").append(p).append("</a>");
            last = p;
        }
        if (current < pages) {
            html.append("<a href=\"").append(escape(listUrl("/", current + 1, q, type, year, status))).append("\">")
                    .append(escape(t("pager.next"))).append("</a>");
        }
        return html.append("</nav>\n").toString();
    }

    private static String listUrl(String path, int page, String q, String type, String year, String status) {
        StringBuilder url = new StringBuilder(path);
        if (page > 0) {
            url.append("?page=").append(page);
        }
        param(url, "q", q);
        param(url, "type", type);
        param(url, "year", year);
        param(url, "status", status);
        return url.toString();
    }

    private static void param(StringBuilder url, String name, String value) {
        if (!blank(value)) {
            url.append(url.indexOf("?") < 0 ? '?' : '&').append(name).append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // ------------------------------------------------------------ Act details

    /** The Act details page; {@code path} is the ELI path the visitor asked for. */
    public String details(ActDetails act, String path) {
        String title = act.fact(ELI + "title");
        String date = act.fact(ELI + "date_document");
        // The act's identifier is its Gazzetta Ufficiale ELI (older data: owl:sameAs to it).
        String gazzettaPage = (act.uri().contains("gazzettaufficiale.it/eli/id/") ? java.util.stream.Stream.of(act.uri())
                : act.facts(OWL_SAME_AS).stream().filter(u -> u.contains("gazzettaufficiale.it/eli/id/")))
                .findFirst().map(u -> u.replaceFirst("^http://", "https://")).orElse(null);
        StringBuilder body = new StringBuilder();
        body.append("<nav class=\"crumbs\" aria-label=\"").append(escape(t("crumbs.aria"))).append("\"><a href=\"/\">")
                .append(escape(t("nav.acts"))).append("</a> &rsaquo; ")
                .append(escape(Html.typeName(act.typeCode())))
                .append(date == null || date.length() < 4 ? "" : " &rsaquo; " + escape(date.substring(0, 4)))
                .append("</nav>\n");

        // The answer first: what the act is, and its current statuses.
        body.append("<section class=\"card\"><div class=\"labels\"><span class=\"type\">")
                .append(escape(Html.typeName(act.typeCode()))).append("</span>")
                .append(act.conversion().map(c -> badge(c.status())).orElse(""))
                .append("</div><h1>").append(escape(act.citation())).append("</h1>")
                .append(title == null ? "" : "<p class=\"lead\">" + escape(title) + "</p>")
                .append("<div class=\"eli-box\"><span class=\"k\">ELI</span><code class=\"mono\">")
                .append(escape(act.uri())).append("</code><span class=\"links\"><a href=\"")
                .append(escape(path)).append("?format=ttl\">").append(escape(t("eli.turtle"))).append("</a>")
                .append(blank(gazzettaPage) ? "" : "<a href=\"" + escape(gazzettaPage) + "\" rel=\"noreferrer\">Gazzetta Ufficiale</a>")
                .append("</span></div></section>\n");

        body.append("<div class=\"split\">\n<div class=\"side\">");
        body.append("<section class=\"card\"><h2>").append(escape(t("facts"))).append("</h2><dl class=\"facts\">")
                .append(fact(t("fact.date"), Html.italianDate(date)))
                .append(fact(t("fact.number"), act.fact(ELI + "number")))
                .append(fact(t("fact.gu"), Html.italianDate(act.fact(ELI + "date_publication"))))
                .append(fact(t("fact.inforce"), Html.italianDate(act.fact(ELI + "first_date_entry_in_force"))))
                .append(fact(t("fact.code"), act.fact(ELI + "id_local")))
                .append(fact(t("fact.urn"), act.fact("http://purl.org/dc/terms/identifier")))
                .append("</dl></section>\n");
        act.conversion().ifPresent(c -> body.append(conversion(c, act.fact(ELI + "date_publication"))));
        body.append(converts(act.converts()));
        body.append("</div>\n<div class=\"mainc\">");
        body.append(versions(act.versions()));
        body.append(relationTable(t("rel.in"), act.relations(), false));
        body.append(relationTable(t("rel.out"), act.relations(), true));
        body.append("</div>\n</div>\n");
        return Html.page(act.citation(), "acts",
                "<link rel=\"alternate\" type=\"text/turtle\" href=\"" + escape(path) + "?format=ttl\">\n",
                body.toString());
    }

    /** The answer: converted by which law and in how many days, or the deadline, or not converted. */
    private String conversion(ActCatalog.Conversion c, String published) {
        String status = c.status();
        String tone = switch (status == null ? "" : status) {
            case "CONVERTED" -> "good";
            case "PENDING" -> "warn";
            case "NOT_CONVERTED" -> "bad";
            default -> "";
        };
        StringBuilder html = new StringBuilder("<section class=\"card conversion ").append(tone)
                .append("\"><h2>").append(escape(t("conv.title"))).append("</h2><div class=\"labels\">").append(badge(status))
                .append("</div>");
        if (c.law() != null) {
            html.append("<p class=\"answer\">").append(escape(t("conv.by"))).append(' ').append(actName(c.law()))
                    .append(c.days() == null ? "" : " " + escape(t("conv.in")) + " <b>"
                            + escape(t("conv.days", c.days())) + "</b> (" + escape(t("conv.of60", c.days())) + ")")
                    .append(".</p>");
        }
        if ("PENDING".equals(status) && published != null) {
            try {
                html.append("<p class=\"answer\">").append(escape(t("conv.deadline"))).append(" <b>")
                        .append(escape(Html.italianDate(java.time.LocalDate.parse(published.substring(0, 10)).plusDays(60)
                                .toString()))).append("</b> ").append(escape(t("conv.deadline.note"))).append(".</p>");
            } catch (RuntimeException ignored) {
                // no deadline without a readable publication date
            }
        }
        return html.append("<p class=\"muted\">").append(status == null ? "" : escape(t("meaning." + status)))
                .append("</p></section>\n").toString();
    }

    /** For a conversion law: the decree-laws it converts. */
    private String converts(List<ActCatalog.Converted> converted) {
        if (converted.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<section class=\"card\"><h2>" + escape(t("converts.title")) + "</h2>"
                + "<div class=\"table-wrap\"><table><thead><tr><th>" + escape(t("col.decree")) + "</th><th class=\"num\">"
                + escape(t("col.days")) + "</th></tr></thead><tbody>");
        for (ActCatalog.Converted c : converted) {
            html.append("<tr><td>").append(otherAct(c.decree())).append("</td><td class=\"num\">")
                    .append(c.days() == null ? "" : escape(String.valueOf(c.days()))).append("</td></tr>");
        }
        return html.append("</tbody></table></div></section>\n").toString();
    }

    /**
     * The versions as a timeline, newest first: the text in force today is what most
     * visitors want. The original text is one click away in the heading, and at the
     * end of the full history.
     */
    private String versions(List<Version> oldestFirst) {
        StringBuilder html = new StringBuilder("<section class=\"card\"><div class=\"results-head\"><h2>")
                .append(escape(t("versions.title"))).append("</h2><span class=\"muted\">")
                .append(I18n.count("versions.count", oldestFirst.size(), number(oldestFirst.size())));
        if (oldestFirst.isEmpty()) {
            return html.append("</span></div><p class=\"muted\">").append(escape(t("versions.none")))
                    .append("</p></section>\n").toString();
        }
        html.append(" &middot; ").append(escape(t("versions.order")));
        oldestFirst.stream().filter(v -> v.uri().endsWith("/original")).findFirst()
                .ifPresent(original -> html.append(" &middot; <a href=\"").append(escape(link(original.uri())))
                        .append("\">").append(escape(t("version.original"))).append("</a>"));
        html.append("</span></div>");
        List<Version> versions = new ArrayList<>(oldestFirst);
        Collections.reverse(versions);
        int shown = versions.size() > 12 ? 10 : versions.size();
        html.append(timeline(versions.subList(0, shown)));
        if (shown < versions.size()) {
            html.append("<details><summary>").append(escape(t("versions.more", versions.size() - shown)))
                    .append("</summary>").append(timeline(versions.subList(shown, versions.size())))
                    .append("</details>");
        }
        return html.append("</section>\n").toString();
    }

    private String timeline(List<Version> versions) {
        StringBuilder html = new StringBuilder("<ol class=\"timeline\">");
        for (Version v : versions) {
            boolean original = v.uri().endsWith("/original");
            html.append("<li><span class=\"tl-mark\"><span class=\"tl-dot").append(original ? " first" : "")
                    .append("\"></span><span class=\"tl-line\"></span></span><span class=\"tl-body\"><a href=\"")
                    .append(escape(link(v.uri()))).append("\"><b>").append(escape(Html.italianDate(v.date())))
                    .append("</b> &middot; ").append(escape(t(original ? "version.original" : "version.inforce")))
                    .append("</a>");
            if (!v.changedBy().isEmpty()) {
                html.append("<span class=\"tl-by\">").append(escape(t("version.changedBy"))).append(' ');
                for (int i = 0; i < v.changedBy().size(); i++) {
                    ActCatalog.OtherAct by = v.changedBy().get(i);
                    html.append(i == 0 ? "" : ", ");
                    if (by.inCorpus() && uris.pathOf(by.uri()).isPresent()) {
                        html.append("<a href=\"").append(escape(link(by.uri()))).append("\">")
                                .append(escape(by.citation())).append("</a>");
                    } else {
                        html.append(escape(by.citation()));
                    }
                }
                html.append("</span>");
            }
            html.append("<span class=\"mono muted\">").append(escape(fileName(v.source()))).append("</span></span></li>");
        }
        return html.append("</ol>").toString();
    }

    private String relationTable(String heading, List<Relation> all, boolean outgoing) {
        List<Relation> rows = all.stream().filter(r -> r.outgoing() == outgoing).toList();
        StringBuilder html = new StringBuilder("<section class=\"card\"><h2>").append(heading).append(" (")
                .append(rows.size()).append(")</h2>");
        if (rows.isEmpty()) {
            return html.append("<p class=\"muted\">").append(escape(t("rel.none"))).append("</p></section>\n").toString();
        }
        int shown = rows.size() > RELATIONS_SHOWN + 5 ? RELATIONS_SHOWN : rows.size();
        html.append(relationRows(rows.subList(0, shown), outgoing));
        if (shown < rows.size()) {
            html.append("<details><summary>").append(escape(t("rel.more", rows.size() - shown))).append("</summary>")
                    .append(relationRows(rows.subList(shown, rows.size()), outgoing)).append("</details>");
        }
        return html.append("</section>\n").toString();
    }

    private String relationRows(List<Relation> rows, boolean outgoing) {
        StringBuilder html = new StringBuilder("<div class=\"table-wrap\"><table><thead><tr><th>" + escape(t("col.act"))
                + "</th><th>" + escape(t("col.relation")) + "</th></tr></thead><tbody>");
        for (Relation r : rows) {
            html.append("<tr><td>").append(otherAct(r.other())).append("</td><td>")
                    .append(Html.relation(r.propertyName(), verb(r.propertyName(), outgoing))).append("</td></tr>");
        }
        return html.append("</tbody></table></div>").toString();
    }

    private static String verb(String property, boolean outgoing) {
        return switch (property) {
            case "converts", "amends", "repeals", "changes" -> t("verb." + property + (outgoing ? ".out" : ".in"));
            default -> property;
        };
    }

    private String otherAct(ActCatalog.OtherAct other) {
        String name = escape(other.citation());
        if (other.inCorpus() && uris.pathOf(other.uri()).isPresent()) {
            return "<a href=\"" + escape(link(other.uri())) + "\"><b>" + name + "</b></a><div class=\"muted\">"
                    + escape(shorten(other.title(), 140)) + "</div>";
        }
        // Acts outside the corpus (before 2020, or other types) have no page here.
        return "<b>" + name + "</b><div class=\"muted\">" + escape(t("outside")) + "</div>";
    }

    /** The act's citation, linked when the act has a page here. */
    private String actName(ActCatalog.OtherAct act) {
        String name = escape(act.citation());
        return act.inCorpus() ? uris.pathOf(act.uri()).map(p -> "<a href=\"" + escape(p) + "\">" + name + "</a>").orElse(name)
                : name;
    }

    private static String fact(String label, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return "<dt>" + escape(label) + "</dt><dd>" + escape(value) + "</dd>";
    }

    private static String externalLink(String label, String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return "<dt>" + escape(label) + "</dt><dd><a href=\"" + escape(url) + "\" rel=\"noreferrer\">"
                + escape(url) + "</a></dd>";
    }

    /** A local link for a resource we host, so navigation works on any host (localhost included). */
    private String link(String uri) {
        return uris.pathOf(uri).orElse(uri);
    }

    private static String fileName(String path) {
        if (path == null) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String shorten(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1).trim() + "…";
    }
}
