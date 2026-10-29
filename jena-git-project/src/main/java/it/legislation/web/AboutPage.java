package it.legislation.web;

import static it.legislation.web.Html.escape;
import static it.legislation.web.I18n.t;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import it.legislation.web.ActCatalog.ActRow;

/**
 * About &amp; Help ({@code /about}): what the portal is, where the data comes from, how
 * to use it, how relations are found and their limits, how current and complete the
 * data is (read live from the store, the daily update and the Gazzetta check), and a
 * glossary that the short explanations on the other pages link to.
 */
@RestController
public class AboutPage {

    /** Glossary terms, in reading order; each has {@code glossary.<term>} and {@code glossary.<term>.text}. */
    static final List<String> TERMS = List.of("act", "decree-law", "version", "original", "consolidated", "relations",
            "amends", "repeals", "converts", "changes", "normattiva", "gazzetta", "eli", "rdf", "sparql");

    private static final List<String[]> SECTIONS = List.of(
            new String[] {"what", "about.what.title"},
            new String[] {"sources", "about.sources.title"},
            new String[] {"use", "about.use.title"},
            new String[] {"relations", "about.rel.title"},
            new String[] {"coverage", "about.cov.title"},
            new String[] {"glossary", "about.glossary.title"});

    private static final MediaType HTML = MediaType.parseMediaType("text/html;charset=UTF-8");

    private final ActCatalog catalog;
    private final DataFreshness freshness;
    private final GazzettaReport gazzetta;

    private final DiscoveryReport discovery;

    public AboutPage(ActCatalog catalog, DataFreshness freshness, GazzettaReport gazzetta, DiscoveryReport discovery) {
        this.discovery = discovery;
        this.catalog = catalog;
        this.freshness = freshness;
        this.gazzetta = gazzetta;
    }

    @GetMapping("/about")
    public ResponseEntity<String> about() throws IOException {
        StringBuilder body = new StringBuilder("<section class=\"intro\"><h1>").append(escape(t("about.title")))
                .append("</h1><p class=\"lead\">").append(escape(t("about.lead"))).append("</p>")
                .append("<nav class=\"toc\" aria-label=\"").append(escape(t("about.toc"))).append("\">");
        for (String[] section : SECTIONS) {
            body.append("<a href=\"#").append(section[0]).append("\">").append(escape(t(section[1]))).append("</a>");
        }
        body.append("</nav></section>\n");

        body.append(section("what", "about.what.title", p("about.what.p1") + p("about.what.p2")));
        body.append(section("sources", "about.sources.title",
                "<h3>Normattiva</h3>" + p("about.sources.normattiva")
                        + "<h3>Gazzetta Ufficiale</h3>" + p("about.sources.gazzetta")
                        + "<h3>" + escape(t("about.sources.update.title")) + "</h3>" + p("about.sources.update")));
        body.append(section("use", "about.use.title",
                list("ol", "about.use.1", "about.use.2", "about.use.3", "about.use.4", "about.use.5")));
        body.append(section("relations", "about.rel.title",
                p("about.rel.p1") + list("ul", "about.rel.history", "about.rel.changes", "about.rel.title.rule")
                        + p("about.rel.kinds")
                        + "<h3>" + escape(t("about.rel.limits")) + "</h3>"
                        + list("ul", "about.rel.limit.1", "about.rel.limit.2", "about.rel.limit.3", "about.rel.limit.4",
                                "about.rel.limit.5")
                        + p("about.rel.verify")));
        body.append(section("coverage", "about.cov.title", coverage()));
        body.append(section("glossary", "about.glossary.title", glossary()));
        return ResponseEntity.ok().contentType(HTML).body(Html.page(t("nav.about"), "about", null, body.toString()));
    }

    /** Coverage and freshness, from the data itself so the page stays true after every update. */
    private String coverage() throws IOException {
        List<ActRow> acts = catalog.acts();
        ActCatalog.Totals totals = catalog.totals();
        Map<String, Long> byType = new TreeMap<>();
        acts.forEach(a -> byType.merge(a.typeCode(), 1L, Long::sum));
        String from = acts.stream().map(ActRow::year).filter(y -> !y.isBlank()).min(String::compareTo).orElse("");
        String to = acts.stream().map(ActRow::year).filter(y -> !y.isBlank()).max(String::compareTo).orElse("");

        StringBuilder html = new StringBuilder();
        html.append("<p>").append(escape(t("about.cov.size", I18n.number(totals.acts()), from, to,
                I18n.number(totals.versions()), I18n.number(totals.relations())))).append("</p><ul>");
        for (Map.Entry<String, Long> type : byType.entrySet()) {
            html.append("<li>").append(escape(Html.typeName(type.getKey()))).append(": ")
                    .append(I18n.number(type.getValue())).append("</li>");
        }
        html.append("</ul>");

        html.append("<h3>").append(escape(t("about.cov.update.title"))).append("</h3><p>")
                .append(escape(freshness.updatedTo()
                        .map(day -> t("about.cov.updated", day.format(DateTimeFormatter.ofPattern("d MMMM yyyy",
                                I18n.italian() ? Locale.ITALIAN : Locale.ENGLISH))))
                        .orElse(t("about.cov.never"))))
                .append("</p>");

        html.append("<h3>").append(escape(t("about.cov.missing.title"))).append("</h3><ul>");
        OptionalInt withoutText = freshness.withoutText();
        if (withoutText.isPresent() && withoutText.getAsInt() > 0) {
            html.append("<li>").append(escape(t("about.cov.notext", withoutText.getAsInt()))).append("</li>");
        }
        List<DiscoveryReport.Row> announced = discovery.all();
        if (!announced.isEmpty()) {
            List<DiscoveryReport.Row> waiting = discovery.waiting();
            html.append("<li>").append(escape(waiting.isEmpty() ? t("about.cov.waiting.none", I18n.number(announced.size()))
                    : t("about.cov.waiting", I18n.number(waiting.size()), I18n.number(announced.size()))));
            if (!waiting.isEmpty()) {
                html.append("<ul>");
                for (DiscoveryReport.Row row : waiting) {
                    html.append("<li>").append(escape(Html.citation(row.type(), row.dateOfAct(), row.number())))
                            .append(" (<span class=\"mono\">").append(escape(row.codice())).append("</span>), GU ")
                            .append(escape(Html.italianDate(row.guDate())))
                            .append(row.url().isBlank() ? "" : " <a href=\"" + escape(row.url()) + "\" rel=\"noreferrer\">"
                                    + escape(t("gz.open")) + "</a>")
                            .append("</li>");
                }
                html.append("</ul>");
            }
            html.append("</li>");
        }
        List<GazzettaReport.Row> checked = gazzetta.all();
        List<GazzettaReport.Row> unconfirmed = checked.stream().filter(r -> !r.matched()).toList();
        if (!checked.isEmpty()) {
            html.append("<li>").append(escape(t("about.cov.gazzetta", I18n.number(checked.size() - unconfirmed.size()),
                    I18n.number(checked.size()))));
            if (!unconfirmed.isEmpty()) {
                html.append("<ul>");
                for (GazzettaReport.Row row : unconfirmed) {
                    html.append("<li>").append(escape(Html.citation(row.type(), row.dateOfAct(), row.number())))
                            .append(" (<span class=\"mono\">").append(escape(row.codice())).append("</span>): ")
                            .append(escape(t("gz.meaning." + (row.status().isBlank() ? "ERROR" : row.status()))))
                            .append(row.url().isBlank() ? "" : " <a href=\"" + escape(row.url()) + "\" rel=\"noreferrer\">"
                                    + escape(t("gz.open")) + "</a>")
                            .append("</li>");
                }
                html.append("</ul>");
            }
            html.append("</li>");
        }
        html.append("<li>").append(escape(t("about.cov.excluded"))).append("</li>")
                .append("<li>").append(escape(t("about.cov.outside"))).append("</li></ul>");
        return html.toString();
    }

    private static String glossary() {
        StringBuilder html = new StringBuilder(p("about.glossary.lead")).append("<dl class=\"glossary\">");
        for (String term : TERMS) {
            html.append("<dt id=\"g-").append(term).append("\">").append(escape(t("glossary." + term)))
                    .append("</dt><dd>").append(escape(t("glossary." + term + ".text"))).append("</dd>");
        }
        return html.append("</dl>").toString();
    }

    private static String section(String id, String titleKey, String content) {
        return "<section class=\"card about\" id=\"" + id + "\" aria-labelledby=\"" + id + "-h\"><h2 id=\"" + id + "-h\">"
                + escape(t(titleKey)) + "</h2>" + content + "</section>\n";
    }

    private static String p(String key) {
        return "<p>" + escape(t(key)) + "</p>";
    }

    private static String list(String tag, String... keys) {
        StringBuilder html = new StringBuilder("<").append(tag).append(">");
        for (String key : keys) {
            html.append("<li>").append(escape(t(key))).append("</li>");
        }
        return html.append("</").append(tag).append(">").toString();
    }
}
