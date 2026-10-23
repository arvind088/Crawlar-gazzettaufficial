package it.legislation.web;

import static it.legislation.web.Html.badge;
import static it.legislation.web.Html.escape;
import static it.legislation.web.I18n.t;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.legislation.eli.EliUriService;
import it.legislation.web.ActCatalog.ActDetails;
import it.legislation.web.ActCatalog.ActRow;
import it.legislation.web.ActCatalog.Assessment;
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
    private static final String ILG = ActCatalog.ILG;

    private static final Map<String, String> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("LEGGE", "Legge");
        TYPES.put("DECRETO-LEGGE", "Decreto-legge");
        TYPES.put("DECRETO_LEGISLATIVO", "Decreto legislativo");
    }

    private final ActCatalog catalog;
    private final EliUriService uris;

    public ActPages(ActCatalog catalog, EliUriService uris) {
        this.catalog = catalog;
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

        body.append(tiles(all, totals));

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
                    .append(Html.gazzettaBadge(row.gazzettaStatus()))
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
        StringBuilder csv = new StringBuilder("eli,code,type,date,number,citation,title,versions,conversion,gazzetta\r\n");
        for (ActRow row : filter(catalog.acts(), q, type, year, status)) {
            csv.append(String.join(",", cell(row.uri()), cell(row.localId()), cell(row.typeCode()), cell(row.date()),
                    cell(row.number()), cell(row.citation()), cell(row.title()), String.valueOf(row.versions()),
                    cell(row.conversionStatus()), cell(row.gazzettaStatus()))).append("\r\n");
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

    /** The four numbers at the head of the list: acts, Gazzetta check, relations, decree-laws. */
    private static String tiles(List<ActRow> all, ActCatalog.Totals totals) {
        long match = all.stream().filter(r -> "MATCH".equals(r.gazzettaStatus())).count();
        long checked = all.stream().filter(r -> r.gazzettaStatus() != null).count();
        long decrees = all.stream().filter(r -> "DECRETO-LEGGE".equals(r.typeCode())).count();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        all.stream().filter(r -> "DECRETO-LEGGE".equals(r.typeCode()) && r.conversionStatus() != null)
                .forEach(r -> byStatus.merge(r.conversionStatus(), 1L, Long::sum));
        long converted = byStatus.getOrDefault("CONVERTED", 0L) + byStatus.getOrDefault("CONVERTED_ONE_SOURCE", 0L);
        long waiting = byStatus.getOrDefault("PENDING", 0L) + byStatus.getOrDefault("AWAITING_LAW_TEXT", 0L);

        StringBuilder html = new StringBuilder("<section class=\"tiles\" aria-label=\"")
                .append(escape(t("tiles.aria"))).append("\">");
        html.append(tile(t("tile.acts"), number(totals.acts()), "", t("tile.versions", number(totals.versions()))));
        html.append(tile(t("tile.gazzetta"), number(match), "good", t("tile.gazzetta.of", number(totals.acts()))
                + (checked > match ? " &middot; " + t("tile.gazzetta.retry", number(checked - match)) : "")));

        StringBuilder bar = new StringBuilder("<div class=\"bar\" role=\"img\" aria-label=\"");
        StringBuilder parts = new StringBuilder();
        StringBuilder spans = new StringBuilder();
        for (String kind : List.of("amends", "changes", "repeals", "converts")) {
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

        html.append(tile(t("tile.decrees"), number(decrees), "", escape(t("tile.decrees.sub", number(converted),
                number(byStatus.getOrDefault("REPEALED", 0L)), number(waiting)))));
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
        String gazzettaPage = act.fact(ILG + "identityConfirmedBy");
        StringBuilder body = new StringBuilder();
        body.append("<nav class=\"crumbs\" aria-label=\"").append(escape(t("crumbs.aria"))).append("\"><a href=\"/\">")
                .append(escape(t("nav.acts"))).append("</a> &rsaquo; ")
                .append(escape(Html.typeName(act.typeCode())))
                .append(date == null || date.length() < 4 ? "" : " &rsaquo; " + escape(date.substring(0, 4)))
                .append("</nav>\n");

        // The answer first: what the act is, and its current statuses.
        body.append("<section class=\"card\"><div class=\"labels\"><span class=\"type\">")
                .append(escape(Html.typeName(act.typeCode()))).append("</span>")
                .append(act.conversion().map(c -> badge(c.value("conversionStatus"))).orElse(""))
                .append(act.gazzetta().map(g -> Html.gazzettaBadge(g.value("gazzettaCheck"))).orElse(""))
                .append("</div><h1>").append(escape(act.citation())).append("</h1>")
                .append(title == null ? "" : "<p class=\"lead\">" + escape(title) + "</p>")
                .append("<div class=\"eli-box\"><span class=\"k\">ELI</span><code class=\"mono\">")
                .append(escape(uris.uriForPath(path))).append("</code><span class=\"links\"><a href=\"")
                .append(escape(path)).append("?format=ttl\">").append(escape(t("eli.turtle"))).append("</a>")
                .append(blank(gazzettaPage) ? "" : "<a href=\"" + escape(gazzettaPage) + "\" rel=\"noreferrer\">Gazzetta Ufficiale</a>")
                .append("</span></div></section>\n");

        Map<String, ActCatalog.OtherAct> related = new LinkedHashMap<>();
        act.relations().forEach(r -> related.putIfAbsent(r.other().uri(), r.other()));

        body.append("<div class=\"split\">\n<div class=\"side\">");
        body.append("<section class=\"card\"><h2>").append(escape(t("facts"))).append("</h2><dl class=\"facts\">")
                .append(fact(t("fact.date"), Html.italianDate(date)))
                .append(fact(t("fact.number"), act.fact(ELI + "number")))
                .append(fact(t("fact.gu"), Html.italianDate(act.fact(ELI + "date_publication"))
                        + (act.fact(ILG + "guNumber") == null ? "" : ", GU n. " + act.fact(ILG + "guNumber"))))
                .append(fact(t("fact.inforce"), Html.italianDate(act.fact(ELI + "first_date_entry_in_force"))))
                .append(fact(t("fact.code"), act.fact(ELI + "id_local")))
                .append(fact(t("fact.urn"), act.fact("http://purl.org/dc/terms/identifier")))
                .append("</dl></section>\n");
        act.conversion().ifPresent(c -> body.append(conversion(c, act.conversionHistory(), related,
                act.fact(ELI + "date_publication"))));
        act.gazzetta().ifPresent(check -> body.append("<section class=\"card\"><h2>").append(escape(t("gz.title")))
                .append("</h2><div class=\"labels\">").append(Html.gazzettaBadge(check.value("gazzettaCheck")))
                .append("<span class=\"muted\">").append(escape(t("gz.checked", Html.italianDate(check.assessedOn()))))
                .append("</span></div><p class=\"muted\">").append(gazzettaMeaning(check.value("gazzettaCheck")))
                .append("</p>").append(history(act.gazzettaHistory(), "gazzettaCheck")).append("</section>\n"));
        body.append(converts(act.converts()));
        body.append("</div>\n<div class=\"mainc\">");
        body.append(versions(act.versions()));
        body.append(relationTable(t("rel.in"), act.relations(), false));
        body.append(relationTable(t("rel.out"), act.relations(), true));
        body.append("<p class=\"muted\">").append(Html.trust("A")).append(' ').append(escape(t("trust.legend.a")))
                .append(" &middot; ").append(Html.trust("B")).append(' ').append(escape(t("trust.legend.b"))).append("</p>");
        body.append("</div>\n</div>\n");
        return Html.page(act.citation(), "acts",
                "<link rel=\"alternate\" type=\"text/turtle\" href=\"" + escape(path) + "?format=ttl\">\n",
                body.toString());
    }

    /** The six conversion checks, in order; their plain words are i18n keys check.C1 … check.C6. */
    private static final List<String> CHECK_KEYS = List.of("checkC1", "checkC2", "checkC3", "checkC4", "checkC5", "checkC6");

    /**
     * The answer first (converted by which law, in how many days); the six checks
     * behind "How was this verified?", open when the result is not a clean CONVERTED.
     */
    private String conversion(Assessment c, List<Assessment> history, Map<String, ActCatalog.OtherAct> related,
                              String published) {
        String status = c.value("conversionStatus");
        String tone = switch (status == null ? "" : status) {
            case "CONVERTED", "CONVERTED_ONE_SOURCE" -> "good";
            case "PENDING", "AWAITING_LAW_TEXT", "REVIEW" -> "warn";
            case "REPEALED", "NOT_CONVERTED" -> "bad";
            default -> "";
        };
        StringBuilder html = new StringBuilder("<section class=\"card conversion ").append(tone)
                .append("\"><h2>").append(escape(t("conv.title"))).append("</h2><div class=\"labels\">").append(badge(status))
                .append(Html.trust(c.value("trustLevel"))).append("</div>");
        if (c.value("law") != null) {
            String days = c.value("daysToConversion");
            html.append("<p class=\"answer\">").append(escape(t("conv.by"))).append(' ').append(actLink(c.value("law"), related))
                    .append(days == null ? "" : " " + escape(t("conv.in")) + " <b>" + escape(t("conv.days", days))
                            + "</b> (" + escape(t("conv.of60", days)) + ")")
                    .append(".</p>");
        }
        if (c.value("repealedBy") != null) {
            html.append("<p class=\"answer\">").append(escape(t("conv.repealedBy"))).append(' ')
                    .append(actLink(c.value("repealedBy"), related)).append(".</p>");
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
        html.append("<p class=\"muted\">").append(status == null ? "" : escape(t("meaning." + status)))
                .append(' ').append(escape(t("conv.assessed", Html.italianDate(c.assessedOn())))).append("</p>");
        StringBuilder checks = new StringBuilder();
        CHECK_KEYS.forEach(key -> {
            String words = t("check." + key.substring("check".length()));
            String v = c.value(key);
            if (v != null) {
                boolean ok = "true".equals(v);
                checks.append("<li><span class=\"").append(ok ? "ok\">&#10003;" : "no\">&#10007;").append("</span><span>")
                        .append(escape(words)).append(" <span class=\"code\">")
                        .append(key.substring("check".length())).append("</span></span></li>");
            }
        });
        if (checks.length() > 0) {
            html.append("<details").append("CONVERTED".equals(status) ? "" : " open")
                    .append("><summary>").append(escape(t("conv.verified"))).append("</summary><ul class=\"checks\">").append(checks)
                    .append("</ul></details>");
        }
        return html.append(history(history, "conversionStatus")).append("</section>\n").toString();
    }

    /** Earlier assessments, when the result has changed over time. */
    private static String history(List<Assessment> history, String key) {
        if (history.size() < 2) {
            return "";
        }
        StringBuilder html = new StringBuilder("<details><summary>").append(escape(t("history", history.size())))
                .append("</summary><ul>");
        for (Assessment a : history) {
            html.append("<li>").append(escape(Html.italianDate(a.assessedOn()))).append(": ")
                    .append(badge(a.value(key))).append("</li>");
        }
        return html.append("</ul></details>").toString();
    }

    private static String gazzettaMeaning(String status) {
        return switch (status == null ? "" : status) {
            case "MATCH", "MISMATCH", "NOT_FOUND", "ERROR" -> escape(t("gz.meaning." + status));
            default -> "";
        };
    }

    /** For a conversion law: the decree-laws it converts. */
    private String converts(List<ActCatalog.Converted> converted) {
        if (converted.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<section class=\"card\"><h2>" + escape(t("converts.title")) + "</h2>"
                + "<div class=\"table-wrap\"><table><thead><tr><th>" + escape(t("col.decree")) + "</th><th>"
                + escape(t("col.status")) + "</th><th>" + escape(t("col.trust")) + "</th><th class=\"num\">"
                + escape(t("col.days")) + "</th></tr></thead><tbody>");
        for (ActCatalog.Converted c : converted) {
            html.append("<tr><td>").append(otherAct(c.decree())).append("</td><td>")
                    .append(badge(c.latest().value("conversionStatus"))).append("</td><td>")
                    .append(Html.trust(c.latest().value("trustLevel"))).append("</td><td class=\"num\">")
                    .append(escape(c.latest().value("daysToConversion"))).append("</td></tr>");
        }
        return html.append("</tbody></table></div></section>\n").toString();
    }

    /** The versions as a timeline: the original text, then each consolidated text by date. */
    private String versions(List<Version> versions) {
        StringBuilder html = new StringBuilder("<section class=\"card\"><div class=\"results-head\"><h2>")
                .append(escape(t("versions.title"))).append("</h2><span class=\"muted\">")
                .append(I18n.count("versions.count", versions.size(), number(versions.size()))).append("</span></div>");
        if (versions.isEmpty()) {
            return html.append("<p class=\"muted\">").append(escape(t("versions.none"))).append("</p></section>\n").toString();
        }
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
                + "</th><th>" + escape(t("col.relation")) + "</th><th>" + escape(t("col.trust")) + "</th><th>"
                + escape(t("col.evidence")) + "</th></tr></thead><tbody>");
        for (Relation r : rows) {
            Assessment a = r.latest();
            String status = a == null ? null : a.value("status");
            html.append("<tr><td>").append(otherAct(r.other())).append("</td><td>")
                    .append(Html.relation(r.propertyName(), verb(r.propertyName(), outgoing)))
                    .append("WITHDRAWN".equals(status) ? " " + badge(status) : "")
                    .append("</td><td>").append(a == null ? "" : Html.trust(a.value("trustLevel"))).append("</td><td>")
                    .append(evidence(r, a)).append("</td></tr>");
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

    private static String evidence(Relation r, Assessment a) {
        StringBuilder html = new StringBuilder();
        if (a != null) {
            String mods = a.value("textualModCount");
            if (mods != null && !"0".equals(mods)) {
                html.append(escape(I18n.count("evidence.mods", "1".equals(mods) ? 1 : 2, mods)));
            }
            if ("true".equals(a.value("confirmedByLifecycle"))) {
                html.append(html.length() > 0 ? "; " : "").append(escape(t("evidence.lifecycle")));
            }
        }
        if (r.note() != null) {
            html.append("<details><summary>").append(escape(t("evidence.note"))).append("</summary><p>").append(escape(r.note())).append("</p></details>");
        }
        if (r.source() != null) {
            html.append("<div class=\"mono muted\">").append(escape(fileName(r.source()))).append("</div>");
        }
        return html.toString();
    }

    private String actLink(String uri, Map<String, ActCatalog.OtherAct> related) {
        ActCatalog.OtherAct other = related.get(uri);
        String name = other != null ? other.citation() : uris.localIdOf(uri).orElse(uri);
        return uris.pathOf(uri).map(p -> "<a href=\"" + escape(p) + "\">" + escape(name) + "</a>").orElse(escape(name));
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
