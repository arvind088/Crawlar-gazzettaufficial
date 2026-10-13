package it.legislation.web;

import static it.legislation.web.Html.badge;
import static it.legislation.web.Html.escape;

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
    private static final String ELI = ActCatalog.ELI;
    private static final String ILG = ActCatalog.ILG;

    /** What each conversion status means, as shown on the pages. */
    static final Map<String, String> CONVERSION_MEANING = Map.of(
            "CONVERTED", "Converted into law: all six checks pass and two sources agree (trust level A).",
            "CONVERTED_ONE_SOURCE", "Converted into law, confirmed by one source only (trust level B).",
            "REPEALED", "Repealed before conversion; its effects are usually kept by the repealing law.",
            "PENDING", "Within the 60 days the Constitution (art. 77) allows for conversion.",
            "AWAITING_LAW_TEXT", "Normattiva lists the conversion law, but its text is not available yet.",
            "NOT_CONVERTED", "Not converted within 60 days: the decree lost effect from the start.",
            "REVIEW", "The sources disagree; the case needs a manual check.");

    private static final Map<String, String> CHECKS = new LinkedHashMap<>();

    static {
        CHECKS.put("checkC1", "C1 law is a LEGGE, act is a DECRETO-LEGGE");
        CHECKS.put("checkC2", "C2 structured link in the Normattiva data");
        CHECKS.put("checkC3", "C3 article 1 or the title says \"converts\"");
        CHECKS.put("checkC4", "C4 the decree is identified uniquely");
        CHECKS.put("checkC5", "C5 converted within 60 days");
        CHECKS.put("checkC6", "C6 only one converting law");
    }

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
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);

        List<ActRow> shown = new ArrayList<>();
        for (ActRow row : all) {
            if (!blank(type) && !type.equals(row.typeCode())) {
                continue;
            }
            if (!blank(year) && !year.equals(row.year())) {
                continue;
            }
            if (!blank(status) && !status.equals(row.conversionStatus())) {
                continue;
            }
            if (!needle.isEmpty() && !matches(row, needle)) {
                continue;
            }
            shown.add(row);
        }
        int pages = Math.max(1, (shown.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 1), pages);
        List<ActRow> slice = shown.subList((current - 1) * PAGE_SIZE, Math.min(current * PAGE_SIZE, shown.size()));

        StringBuilder body = new StringBuilder();
        body.append("<h1>Legal acts</h1>\n<p class=\"muted\">Laws, decree-laws and legislative decrees from 2020, "
                + "with every consolidated version, imported from Normattiva.</p>\n")
                .append("<div class=\"stats\"><div><b>").append(String.format(Locale.ITALY, "%,d", totals.acts()))
                .append("</b>acts</div><div><b>").append(String.format(Locale.ITALY, "%,d", totals.versions()))
                .append("</b>versions</div><div><b>").append(String.format(Locale.ITALY, "%,d", totals.relations()))
                .append("</b>relations between acts</div></div>\n");

        TreeSet<String> years = new TreeSet<>(java.util.Comparator.reverseOrder());
        TreeSet<String> statuses = new TreeSet<>();
        for (ActRow row : all) {
            years.add(row.year());
            if (row.conversionStatus() != null) {
                statuses.add(row.conversionStatus());
            }
        }
        body.append("<form class=\"filters card\" method=\"get\" action=\"/\">")
                .append("<label>Search title, number or code<input type=\"search\" name=\"q\" value=\"")
                .append(escape(q)).append("\" placeholder=\"e.g. covid, 18, 20G00034\"></label>")
                .append(select("type", "Type", type, TYPES))
                .append(select("year", "Year", year, toMap(years)))
                .append(select("status", "Conversion status", status, toMap(statuses)))
                .append("<button type=\"submit\">Filter</button>")
                .append(blank(q) && blank(type) && blank(year) && blank(status) ? "" : " <a href=\"/\">Clear</a>")
                .append("</form>\n");

        body.append("<p class=\"muted\">").append(shown.size()).append(shown.size() == 1 ? " act" : " acts")
                .append(pages > 1 ? ", page " + current + " of " + pages : "").append("</p>\n");
        if (slice.isEmpty()) {
            body.append("<div class=\"card\">No act matches these filters.</div>\n");
        } else {
            body.append("<div class=\"table-wrap\"><table><thead><tr><th>Act</th><th>Title</th>"
                    + "<th class=\"num\">Versions</th><th>Conversion</th><th>Gazzetta</th></tr></thead><tbody>\n");
            for (ActRow row : slice) {
                body.append("<tr><td class=\"nowrap\"><a href=\"").append(escape(link(row.uri()))).append("\"><b>")
                        .append(escape(row.citation())).append("</b></a><div class=\"mono muted\">")
                        .append(escape(row.localId())).append("</div></td><td>").append(escape(row.title()))
                        .append("</td><td class=\"num\">").append(row.versions()).append("</td><td>")
                        .append(badge(row.conversionStatus())).append("</td><td>")
                        .append(badge(row.gazzettaStatus())).append("</td></tr>\n");
            }
            body.append("</tbody></table></div>\n");
        }
        body.append(pager(current, pages, q, type, year, status));
        return ResponseEntity.ok().contentType(HTML).body(Html.page("Legal acts", "acts", null, body.toString()));
    }

    private static boolean matches(ActRow row, String needle) {
        return contains(row.title(), needle) || contains(row.localId(), needle) || needle.equals(row.number())
                || contains(row.citation(), needle);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static Map<String, String> toMap(Iterable<String> values) {
        Map<String, String> map = new LinkedHashMap<>();
        values.forEach(v -> map.put(v, v.replace('_', ' ')));
        return map;
    }

    private static String select(String name, String label, String selected, Map<String, String> options) {
        StringBuilder html = new StringBuilder("<label>").append(label).append("<select name=\"").append(name)
                .append("\"><option value=\"\">All</option>");
        options.forEach((value, text) -> html.append(option(value, text, selected)));
        return html.append("</select></label>").toString();
    }

    private static String option(String value, String text, String selected) {
        return "<option value=\"" + escape(value) + "\"" + (value.equals(selected) ? " selected" : "") + ">"
                + escape(text) + "</option>";
    }

    private static String pager(int current, int pages, String q, String type, String year, String status) {
        if (pages <= 1) {
            return "";
        }
        StringBuilder html = new StringBuilder("<div class=\"pager\">");
        if (current > 1) {
            html.append("<a href=\"").append(escape(listUrl(current - 1, q, type, year, status))).append("\">&larr; Previous</a>");
        }
        html.append("<span class=\"muted\">Page ").append(current).append(" of ").append(pages).append("</span>");
        if (current < pages) {
            html.append("<a href=\"").append(escape(listUrl(current + 1, q, type, year, status))).append("\">Next &rarr;</a>");
        }
        return html.append("</div>\n").toString();
    }

    private static String listUrl(int page, String q, String type, String year, String status) {
        StringBuilder url = new StringBuilder("/?page=").append(page);
        param(url, "q", q);
        param(url, "type", type);
        param(url, "year", year);
        param(url, "status", status);
        return url.toString();
    }

    private static void param(StringBuilder url, String name, String value) {
        if (!blank(value)) {
            url.append('&').append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // ------------------------------------------------------------ Act details

    /** The Act details page; {@code path} is the ELI path the visitor asked for. */
    public String details(ActDetails act, String path) {
        String title = act.fact(ELI + "title");
        StringBuilder body = new StringBuilder();
        body.append("<p class=\"eyebrow\">").append(escape(Html.typeName(act.typeCode()))).append("</p>\n")
                .append("<h1>").append(escape(act.citation())).append("</h1>\n")
                .append("<p>").append(escape(title)).append("</p>\n")
                .append("<p class=\"mono muted\">").append(escape(uris.uriForPath(path))).append("</p>\n")
                .append("<p><a href=\"").append(escape(path)).append("?format=ttl\">View as RDF (Turtle)</a></p>\n");

        // Identity and publication
        body.append("<section class=\"card\"><h2>Act</h2><dl class=\"facts\">")
                .append(fact("Redactional code", act.fact(ELI + "id_local")))
                .append(fact("Date of the act", Html.italianDate(act.fact(ELI + "date_document"))))
                .append(fact("Published in Gazzetta", Html.italianDate(act.fact(ELI + "date_publication"))
                        + (act.fact(ILG + "guNumber") == null ? "" : ", GU n. " + act.fact(ILG + "guNumber"))))
                .append(fact("In force from", Html.italianDate(act.fact(ELI + "first_date_entry_in_force"))))
                .append(fact("URN", act.fact("http://purl.org/dc/terms/identifier")))
                .append(externalLink("Gazzetta Ufficiale page", act.fact(ILG + "identityConfirmedBy")))
                .append("</dl></section>\n");

        act.gazzetta().ifPresent(check -> body.append("<section class=\"card\"><h2>Check against Gazzetta Ufficiale "
                + badge(check.value("gazzettaCheck")) + "</h2><p class=\"muted\">")
                .append(gazzettaMeaning(check.value("gazzettaCheck"))).append(" Checked on ")
                .append(escape(Html.italianDate(check.assessedOn()))).append(".</p>")
                .append(history(act.gazzettaHistory(), "gazzettaCheck")).append("</section>\n"));

        Map<String, ActCatalog.OtherAct> related = new LinkedHashMap<>();
        act.relations().forEach(r -> related.putIfAbsent(r.other().uri(), r.other()));
        act.conversion().ifPresent(c -> body.append(conversion(c, act.conversionHistory(), related)));

        body.append(converts(act.converts()));
        body.append(versions(act.versions()));
        body.append(relations(act.relations()));
        return Html.page(act.citation(), "acts",
                "<link rel=\"alternate\" type=\"text/turtle\" href=\"" + escape(path) + "?format=ttl\">\n",
                body.toString());
    }

    private String conversion(Assessment c, List<Assessment> history, Map<String, ActCatalog.OtherAct> related) {
        String status = c.value("conversionStatus");
        StringBuilder html = new StringBuilder("<section class=\"card\"><h2>Conversion into law ")
                .append(badge(status)).append(c.value("trustLevel") == null ? "" : " " + badge(c.value("trustLevel")))
                .append("</h2><p class=\"muted\">").append(escape(CONVERSION_MEANING.getOrDefault(status, "")))
                .append(" Assessed on ").append(escape(Html.italianDate(c.assessedOn()))).append(".</p><dl class=\"facts\">");
        if (c.value("law") != null) {
            html.append("<dt>Converted by</dt><dd>").append(actLink(c.value("law"), related)).append("</dd>");
        }
        if (c.value("daysToConversion") != null) {
            html.append(fact("Days to conversion", c.value("daysToConversion") + " of 60"));
        }
        if (c.value("repealedBy") != null) {
            html.append("<dt>Repealed by</dt><dd>").append(actLink(c.value("repealedBy"), related)).append("</dd>");
        }
        StringBuilder checks = new StringBuilder();
        CHECKS.forEach((key, label) -> {
            String v = c.value(key);
            if (v != null) {
                checks.append("<div>").append("true".equals(v) ? "&#10003; " : "&#10007; ").append(escape(label))
                        .append("</div>");
            }
        });
        if (checks.length() > 0) {
            html.append("<dt>Checks</dt><dd>").append(checks).append("</dd>");
        }
        return html.append("</dl>").append(history(history, "conversionStatus")).append("</section>\n").toString();
    }

    /** Earlier assessments, when the result has changed over time. */
    private static String history(List<Assessment> history, String key) {
        if (history.size() < 2) {
            return "";
        }
        StringBuilder html = new StringBuilder("<details><summary>History (").append(history.size())
                .append(" assessments)</summary><ul>");
        for (Assessment a : history) {
            html.append("<li>").append(escape(a.assessedOn())).append(": ").append(badge(a.value(key))).append("</li>");
        }
        return html.append("</ul></details>").toString();
    }

    private static String gazzettaMeaning(String status) {
        return switch (status == null ? "" : status) {
            case "MATCH" -> "The Gazzetta page has the same title, number and date as Normattiva.";
            case "MISMATCH" -> "The Gazzetta page differs from Normattiva in title, number or date.";
            case "NOT_FOUND" -> "Gazzetta Ufficiale has no page for this act.";
            case "ERROR" -> "The Gazzetta page could not be read; the next update tries again.";
            default -> "";
        };
    }

    /** For a conversion law: the decree-laws it converts. */
    private String converts(List<ActCatalog.Converted> converted) {
        if (converted.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<section class=\"card\"><h2>Decree-laws this law converts</h2>"
                + "<div class=\"table-wrap\"><table><thead><tr><th>Decree-law</th><th>Status</th><th>Trust</th>"
                + "<th class=\"num\">Days</th></tr></thead><tbody>");
        for (ActCatalog.Converted c : converted) {
            html.append("<tr><td>").append(otherAct(c.decree())).append("</td><td>")
                    .append(badge(c.latest().value("conversionStatus"))).append("</td><td>")
                    .append(badge(c.latest().value("trustLevel"))).append("</td><td class=\"num\">")
                    .append(escape(c.latest().value("daysToConversion"))).append("</td></tr>");
        }
        return html.append("</tbody></table></div></section>\n").toString();
    }

    private String versions(List<Version> versions) {
        StringBuilder html = new StringBuilder("<section class=\"card\"><h2>Versions (")
                .append(versions.size()).append(")</h2>");
        if (versions.isEmpty()) {
            return html.append("<p class=\"muted\">No text imported yet.</p></section>\n").toString();
        }
        html.append("<p class=\"muted\">The text as published, then each consolidated text (\"vigente\") with the date "
                + "it applies from.</p>");
        int shown = versions.size() > 12 ? 10 : versions.size();
        html.append(versionTable(versions.subList(0, shown)));
        if (shown < versions.size()) {
            html.append("<details><summary>Show the other ").append(versions.size() - shown)
                    .append(" versions</summary>").append(versionTable(versions.subList(shown, versions.size())))
                    .append("</details>");
        }
        return html.append("</section>\n").toString();
    }

    private String versionTable(List<Version> versions) {
        StringBuilder html = new StringBuilder("<div class=\"table-wrap\"><table><thead><tr><th>Version</th>"
                + "<th>Valid from</th><th>Source file</th></tr></thead><tbody>");
        for (Version v : versions) {
            String name = v.uri().endsWith("/original") ? "Original text" : "Consolidated text";
            html.append("<tr><td><a href=\"").append(escape(link(v.uri()))).append("\">").append(name)
                    .append("</a></td><td>").append(escape(Html.italianDate(v.date()))).append("</td><td class=\"mono muted\">")
                    .append(escape(fileName(v.source()))).append("</td></tr>");
        }
        return html.append("</tbody></table></div>").toString();
    }

    private String relations(List<Relation> relations) {
        StringBuilder html = new StringBuilder();
        html.append(relationTable("What this act does to other acts", relations, true));
        html.append(relationTable("What other acts did to this act", relations, false));
        return html.toString();
    }

    private String relationTable(String heading, List<Relation> all, boolean outgoing) {
        List<Relation> rows = all.stream().filter(r -> r.outgoing() == outgoing).toList();
        StringBuilder html = new StringBuilder("<section class=\"card\"><h2>").append(heading).append(" (")
                .append(rows.size()).append(")</h2>");
        if (rows.isEmpty()) {
            return html.append("<p class=\"muted\">None found.</p></section>\n").toString();
        }
        html.append("<p class=\"muted\">Trust A: the modifying act's change list and the changed act's history agree. "
                + "Trust B: one of the two only.</p><div class=\"table-wrap\"><table><thead><tr><th>Relation</th>")
                .append("<th>").append(outgoing ? "Act changed" : "Changed by").append("</th>")
                .append("<th>Trust</th><th>Evidence</th></tr></thead><tbody>");
        for (Relation r : rows) {
            Assessment a = r.latest();
            String status = a == null ? null : a.value("status");
            html.append("<tr><td class=\"nowrap\">").append(escape(verb(r.propertyName(), outgoing)))
                    .append("WITHDRAWN".equals(status) ? " " + badge(status) : "")
                    .append("</td><td>").append(otherAct(r.other())).append("</td><td>")
                    .append(a == null ? "" : badge(a.value("trustLevel"))).append("</td><td>")
                    .append(evidence(r, a)).append("</td></tr>");
        }
        return html.append("</tbody></table></div></section>\n").toString();
    }

    private static String verb(String property, boolean outgoing) {
        return switch (property) {
            case "converts" -> outgoing ? "converts into law" : "converted into law by";
            case "amends" -> outgoing ? "amends" : "amended by";
            case "repeals" -> outgoing ? "repeals" : "repealed by";
            case "changes" -> outgoing ? "changes" : "changed by";
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
        return "<b>" + name + "</b><div class=\"muted\">outside the imported corpus</div>";
    }

    private static String evidence(Relation r, Assessment a) {
        StringBuilder html = new StringBuilder();
        if (a != null) {
            String mods = a.value("textualModCount");
            if (mods != null && !"0".equals(mods)) {
                html.append(mods).append(" textual modification").append("1".equals(mods) ? "" : "s");
            }
            if ("true".equals(a.value("confirmedByLifecycle"))) {
                html.append(html.length() > 0 ? "; " : "").append("confirmed by the changed act's history");
            }
        }
        if (r.note() != null) {
            html.append("<details><summary>Editor's note</summary><p>").append(escape(r.note())).append("</p></details>");
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
