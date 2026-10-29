package it.legislation.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The page frame shared by the public views (Legal acts, Act details, About &amp; Help,
 * SPARQL) and the generic resource page: header with navigation, one style
 * sheet, escaping, labels, and how acts are named.
 *
 * <p>Colours and type follow the Italian public administration's design system
 * (Designers Italia / Bootstrap Italia): Italia blue for navigation and links,
 * green, amber and red only for meaning (confirmed, waiting, repealed).
 *
 * <p>Pages are plain server-rendered HTML with no script, so every act has a
 * real address that works in any browser and gives RDF to machines (content
 * negotiation in {@code EliResolutionController}).
 */
public final class Html {

    public static final String APP_NAME = "Italian Legislation Linked Data";

    private Html() {
    }

    /** A whole page. {@code active} is "acts", "sparql" or "about" for the highlighted menu item. */
    public static String page(String title, String active, String head, String body) {
        String lang = I18n.lang();
        return "<!doctype html>\n<html lang=\"" + lang + "\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + escape(title) + " &middot; " + APP_NAME + "</title>\n"
                + FONTS
                + (head == null ? "" : head)
                + "<style>" + CSS + "</style>\n</head>\n<body>\n"
                + "<a class=\"skip\" href=\"#main\">" + escape(I18n.t("skip")) + "</a>\n"
                + "<div class=\"topbar\"><div class=\"wrap\"><span>" + escape(I18n.t("topbar.eli")) + "</span>"
                + "<span class=\"topbar-right\">" + languageSwitch(lang)
                + "<a href=\"/api/health\"><span class=\"status-dot\"></span>" + escape(I18n.t("topbar.live")) + "</a>"
                + "</span></div></div>\n"
                + "<header class=\"site\"><div class=\"wrap\">"
                + "<a class=\"brand\" href=\"/\"><span class=\"brand-mark\" aria-hidden=\"true\">" + SCALES + "</span>"
                + "<span><span class=\"brand-title\">" + APP_NAME + "</span>"
                + "<span class=\"brand-sub\">" + escape(I18n.t("brand.sub")) + "</span></span></a>"
                + "<nav class=\"tabs\" aria-label=\"" + escape(I18n.t("nav.aria")) + "\"><a href=\"/\""
                + current(active, "acts") + ">" + escape(I18n.t("nav.acts")) + "</a>"
                + "<a href=\"/query\"" + current(active, "sparql") + ">" + escape(I18n.t("nav.sparql")) + "</a>"
                + "<a href=\"/about\"" + current(active, "about") + ">" + escape(I18n.t("nav.about")) + "</a></nav>"
                + "</div></header>\n<main id=\"main\" class=\"wrap\">\n" + body + "</main>\n"
                + "<footer class=\"site\"><div class=\"wrap\"><span>" + escape(I18n.t("footer.text")) + "</span>"
                + "<nav class=\"footer-links\" aria-label=\"" + escape(I18n.t("footer.aria")) + "\">"
                + "<a href=\"/about\">" + escape(I18n.t("nav.about")) + "</a>"
                + "<a href=\"/about#glossary\">" + escape(I18n.t("footer.glossary")) + "</a>"
                + "<a href=\"/about#sources\">" + escape(I18n.t("footer.sources")) + "</a>"
                + "<a href=\"/about#coverage\">" + escape(I18n.t("footer.updates")) + "</a>"
                + "<a href=\"/query\">SPARQL</a><a href=\"/api/health\">"
                + escape(I18n.t("footer.status")) + "</a></nav>"
                + "</div></footer>\n</body>\n</html>\n";
    }

    /** "Italiano · English": links to the same page in the other language. */
    private static String languageSwitch(String lang) {
        StringBuilder html = new StringBuilder("<nav class=\"lang\" aria-label=\"")
                .append(escape(I18n.t("lang.aria"))).append("\">");
        for (String[] option : new String[][] {{I18n.ITALIAN, "Italiano"}, {I18n.ENGLISH, "English"}}) {
            html.append("<a href=\"").append(escape(currentUrlWithLang(option[0]))).append("\" lang=\"")
                    .append(option[0]).append("\" hreflang=\"").append(option[0]).append("\"")
                    .append(option[0].equals(lang) ? " aria-current=\"true\"" : "").append(">")
                    .append(option[1]).append("</a>");
        }
        return html.append("</nav>").toString();
    }

    /** The address of the current page with {@code lang} set; just "?lang=…" outside a request. */
    static String currentUrlWithLang(String lang) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return "?lang=" + lang;
        }
        HttpServletRequest request = servlet.getRequest();
        StringBuilder url = new StringBuilder(request.getRequestURI()).append("?");
        String query = request.getQueryString();
        if (query != null) {
            for (String part : query.split("&")) {
                if (!part.isEmpty() && !part.startsWith("lang=")) {
                    url.append(part).append('&');
                }
            }
        }
        return url.append("lang=").append(lang).toString();
    }

    /**
     * Titillium Web is the typeface of the Italian public administration's design
     * system (Designers Italia); the pages fall back to system fonts offline.
     */
    private static final String FONTS = "<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>\n"
            + "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Titillium+Web:wght@400;600;700"
            + "&amp;family=Roboto+Mono:wght@400;500&amp;display=swap\">\n";

    /** The scales of justice, the brand mark. */
    private static final String SCALES = "<svg width=\"30\" height=\"30\" viewBox=\"0 0 24 24\" fill=\"none\" "
            + "stroke=\"currentColor\" stroke-width=\"1.7\" stroke-linecap=\"round\" stroke-linejoin=\"round\">"
            + "<path d=\"M12 3v18M7 21h10M5 7h14\"/><path d=\"M5 7l-3 6a3 3 0 0 0 6 0z\"/>"
            + "<path d=\"M19 7l-3 6a3 3 0 0 0 6 0z\"/></svg>";

    /**
     * A short plain-language explanation under a heading, with a link to the term in the
     * glossary of the About &amp; Help page. {@code text} is plain text (escaped here).
     */
    public static String hint(String text, String term) {
        return "<p class=\"hint\">" + escape(text) + " <a class=\"q\" href=\"/about#" + escape(term) + "\" title=\""
                + escape(I18n.t("hint.more")) + "\" aria-label=\"" + escape(I18n.t("hint.more")) + "\">?</a></p>";
    }

    private static String current(String active, String item) {
        return item.equals(active) ? " aria-current=\"page\"" : "";
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * A coloured label for a status value. One meaning per colour on every page:
     * green = converted, amber = waiting (pending), red = not converted.
     */
    public static String badge(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        String tone = switch (status) {
            case "CONVERTED" -> "good";
            case "PENDING" -> "warn";
            case "NOT_CONVERTED" -> "bad";
            default -> "neutral";
        };
        return "<span class=\"badge " + tone + "\">" + escape(statusWords(status)) + "</span>";
    }

    /** A relation kind as a coloured label: converts, amends, repeals, changes. */
    public static String relation(String property, String text) {
        String kind = switch (property == null ? "" : property) {
            case "converts", "amends", "repeals" -> property;
            default -> "changes";
        };
        return "<span class=\"rel " + kind + "\">" + escape(text) + "</span>";
    }

    /** A status in the current language: "AWAITING_LAW_TEXT" → "Awaiting law text" / "In attesa del testo della legge". */
    public static String statusWords(String code) {
        String key = "status." + code;
        String text = I18n.t(key);
        return text.equals(key) ? words(code) : text;
    }

    /** "AWAITING_LAW_TEXT" → "Awaiting law text". */
    public static String words(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String spaced = code.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static final Map<String, String> SHORT_TYPES = Map.ofEntries(
            Map.entry("LEGGE", "L."),
            Map.entry("DECRETO-LEGGE", "D.L."),
            Map.entry("DECRETO_LEGISLATIVO", "D.Lgs."),
            Map.entry("DECRETO_DEL_PRESIDENTE_DELLA_REPUBBLICA", "D.P.R."),
            Map.entry("DECRETO_DEL_PRESIDENTE_DEL_CONSIGLIO_DEI_MINISTRI", "D.P.C.M."),
            Map.entry("REGIO_DECRETO", "R.D."),
            Map.entry("REGIO_DECRETO_LEGGE", "R.D.L."),
            Map.entry("REGIO_DECRETO_LEGISLATIVO", "R.D.Lgs."),
            Map.entry("DECRETO_LEGISLATIVO_DEL_CAPO_PROVVISORIO_DELLO_STATO", "D.Lgs.C.P.S."),
            Map.entry("DECRETO", "D."),
            Map.entry("CIRCOLARE", "Circ.")
    );

    private static final Map<String, String> TYPE_NAMES = Map.of(
            "LEGGE", "Legge",
            "DECRETO-LEGGE", "Decreto-legge",
            "DECRETO_LEGISLATIVO", "Decreto legislativo");

    private static final String[] MONTHS = {"gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
            "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"};

    /** The type code at the end of an {@code eli:type_document} IRI ({@code …#DECRETO-LEGGE}). */
    public static String typeCode(String typeUri) {
        if (typeUri == null) {
            return "";
        }
        int hash = typeUri.lastIndexOf('#');
        return hash >= 0 ? typeUri.substring(hash + 1) : typeUri;
    }

    /** Full type name, e.g. "Decreto-legge". */
    public static String typeName(String code) {
        String known = TYPE_NAMES.get(code);
        if (known != null) {
            return known;
        }
        String spaced = code.replace('_', ' ').toLowerCase(Locale.ROOT);
        return spaced.isEmpty() ? "" : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** How Italian lawyers cite an act: "D.L. 8 aprile 2020, n. 23". */
    public static String citation(String code, String date, String number) {
        String type = SHORT_TYPES.getOrDefault(code, typeName(code));
        String when = italianDate(date);
        StringBuilder text = new StringBuilder(type);
        if (!when.isEmpty()) {
            text.append(' ').append(when);
        }
        if (number != null && !number.isBlank()) {
            text.append(", n. ").append(number);
        }
        return text.toString().trim();
    }

    /** "2020-04-08" → "8 aprile 2020"; anything unparsable is returned as it is. */
    public static String italianDate(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) {
            return "";
        }
        try {
            LocalDate date = LocalDate.parse(isoDate.length() > 10 ? isoDate.substring(0, 10) : isoDate);
            return date.getDayOfMonth() + " " + MONTHS[date.getMonthValue() - 1] + " " + date.getYear();
        } catch (DateTimeParseException e) {
            return isoDate;
        }
    }

    static final String CSS = """
            :root{--blue:#0066cc;--blue-dark:#004d99;--ink:#17324d;--slate:#5c6f82;--tint:#e5f0fa;--ground:#f5f7fb;
            --line:#c5d3e0;--line-soft:#e3e9f0;--surface:#fff;
            --green:#008055;--green-text:#006644;--green-bg:#e6f4ee;--amber:#995c00;--amber-bg:#fff4e0;
            --red:#cc334d;--red-text:#b0263e;--red-bg:#fbeaed;--grey-bg:#e3e9f0;
            --font:"Titillium Web",system-ui,-apple-system,"Segoe UI",sans-serif;
            --mono:"Roboto Mono",ui-monospace,Consolas,Menlo,monospace;--shadow:0 1px 3px rgba(23,50,77,.12)}
            *{box-sizing:border-box}
            body{margin:0;background:var(--ground);color:var(--ink);font:17px/1.5 var(--font)}
            a{color:var(--blue)}a:hover{color:var(--blue-dark)}
            .wrap{max-width:1200px;margin:0 auto;padding:0 24px}
            .skip{position:absolute;left:-9999px}.skip:focus{left:16px;top:8px;background:#fff;padding:8px 12px;z-index:9}
            .topbar{background:var(--blue-dark);color:#fff;font-size:14px}
            .topbar .wrap{display:flex;flex-wrap:wrap;justify-content:space-between;gap:8px;padding-top:7px;padding-bottom:7px}
            .topbar a{color:#fff;text-decoration:none;display:inline-flex;align-items:center;gap:8px;font-weight:600}
            .topbar-right{display:flex;flex-wrap:wrap;align-items:center;gap:18px}
            .lang{display:flex;gap:4px}.topbar .lang a{padding:2px 8px;border-radius:4px;font-weight:600}
            .topbar .lang a[aria-current]{background:#fff;color:var(--blue-dark)}
            .status-dot{width:9px;height:9px;border-radius:50%;background:#7ee2b8}
            header.site{background:var(--blue);color:#fff}
            header.site .wrap{display:flex;flex-direction:column;gap:14px;padding-top:22px}
            .brand{display:flex;align-items:center;gap:14px;color:#fff;text-decoration:none}
            .brand:hover{color:#fff}
            .brand>span:last-child{display:flex;flex-direction:column}
            .brand-mark{display:inline-flex;align-items:center;justify-content:center;width:48px;height:48px;
            border:2px solid rgba(255,255,255,.6);border-radius:10px}
            .brand-title{font-size:28px;font-weight:700;line-height:1.1}
            .brand-sub{font-size:15px;opacity:.92}
            .tabs{display:flex;flex-wrap:wrap;gap:4px}
            .tabs a{color:#fff;text-decoration:none;font-weight:600;padding:10px 18px;border-bottom:4px solid transparent}
            .tabs a:hover{border-bottom-color:rgba(255,255,255,.5);color:#fff}
            .tabs a[aria-current]{border-bottom-color:#fff;font-weight:700}
            main.wrap{padding-top:28px;padding-bottom:56px;display:flex;flex-direction:column;gap:22px}
            footer.site{background:var(--ink);color:#fff;font-size:15px}
            footer.site .wrap{display:flex;flex-wrap:wrap;justify-content:space-between;gap:12px;padding-top:24px;padding-bottom:24px}
            footer.site a{color:#fff}.footer-links{display:flex;flex-wrap:wrap;gap:6px 18px}
            .hint{font-size:15px;color:var(--slate);max-width:900px}
            .q{display:inline-flex;align-items:center;justify-content:center;width:20px;height:20px;border-radius:50%;
            border:1.5px solid var(--blue);font-size:13px;font-weight:700;text-decoration:none;line-height:1;vertical-align:1px}
            .q:hover{background:var(--blue);color:#fff}
            .intro{display:flex;flex-direction:column;gap:8px}
            .intro h1{font-size:30px}
            .search-hint{font-size:15px;color:var(--slate)}
            .about h2{font-size:23px}.about h3{font-size:18px;margin:6px 0 0}
            .about ul,.about ol{margin:0;padding-left:22px;display:flex;flex-direction:column;gap:6px}
            .about p,.about li{max-width:900px}
            .toc{display:flex;flex-wrap:wrap;gap:8px 18px;font-weight:600}
            dl.glossary{display:grid;grid-template-columns:minmax(140px,220px) 1fr;gap:10px 22px;margin:0}
            dl.glossary dt{font-weight:700}dl.glossary dd{margin:0}
            dl.glossary :target{background:var(--amber-bg)}
            @media (max-width:640px){dl.glossary{grid-template-columns:1fr;gap:2px}dl.glossary dd{margin-bottom:10px}}
            h1{font-size:34px;line-height:1.15;margin:0}
            h2{font-size:21px;line-height:1.25;margin:0}
            p{margin:0}
            .muted{color:var(--slate)}
            .sr-title{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}
            .mono{font-family:var(--mono);font-size:13.5px;overflow-wrap:anywhere}
            .card{background:var(--surface);border-radius:8px;box-shadow:var(--shadow);padding:22px 24px;
            display:flex;flex-direction:column;gap:12px;min-width:0}
            .lead{font-size:19px;line-height:1.55;max-width:900px}
            input,select,textarea,button{font:inherit;color:var(--ink)}
            input[type=search],select{min-height:46px;padding:0 12px;border:2px solid var(--slate);border-radius:6px;background:#fff}
            button,.button{display:inline-flex;align-items:center;justify-content:center;min-height:46px;padding:0 24px;
            background:var(--blue);border:2px solid var(--blue);border-radius:6px;color:#fff;font-weight:700;cursor:pointer;
            text-decoration:none}
            button:hover,.button:hover{background:var(--blue-dark);border-color:var(--blue-dark);color:#fff}
            .button.secondary{background:#fff;color:var(--blue)}.button.secondary:hover{background:var(--tint);color:var(--blue-dark)}
            :focus-visible{outline:3px solid #ff9900;outline-offset:2px}
            .search label{font-size:19px;font-weight:700}
            .search .row{display:flex;flex-wrap:wrap;gap:12px}
            .search input[type=search]{flex:1 1 420px;min-width:0;font-size:18px}
            .scope{margin:0 0 8px;font-size:15px}
            .tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:16px}
            .tile{background:var(--surface);border-radius:8px;box-shadow:var(--shadow);padding:18px 20px;display:flex;flex-direction:column;gap:4px}
            .tile .k{font-size:13.5px;font-weight:700;color:var(--slate);text-transform:uppercase;letter-spacing:.04em}
            .tile .v{font-size:36px;font-weight:700;line-height:1.1}
            .tile .v.good{color:var(--green)}
            .tile .s{font-size:14.5px;color:var(--slate)}
            .bar{display:flex;height:8px;border-radius:4px;overflow:hidden;background:var(--line-soft);margin:4px 0}
            .bar span{display:block}.bar .amends{background:var(--blue)}.bar .changes{background:#8a9bad}
            .bar .repeals{background:var(--red)}.bar .converts{background:var(--green)}
            .split{display:flex;flex-wrap:wrap;gap:22px;align-items:flex-start}
            .side{flex:1 1 280px;max-width:100%;display:flex;flex-direction:column;gap:22px}
            .mainc{flex:999 1 560px;min-width:0;display:flex;flex-direction:column;gap:22px}
            .filters form{display:flex;flex-direction:column;gap:16px}
            .filters label{display:flex;flex-direction:column;gap:6px;font-weight:700}
            .results-head{display:flex;flex-wrap:wrap;justify-content:space-between;align-items:baseline;gap:8px}
            .act-card{background:var(--surface);border-radius:8px;box-shadow:var(--shadow);padding:18px 22px;
            display:flex;flex-direction:column;gap:8px}
            .act-head{display:flex;flex-wrap:wrap;align-items:center;gap:10px}
            .cite{font-size:20px;font-weight:700;text-decoration:none}
            .cite:hover{text-decoration:underline}
            .labels{display:flex;flex-wrap:wrap;align-items:center;gap:8px;font-size:14.5px}
            .type{display:inline-flex;font-size:13px;font-weight:700;color:var(--blue-dark);background:var(--tint);
            padding:2px 10px;border-radius:12px;text-transform:uppercase;letter-spacing:.03em}
            .badge{display:inline-flex;align-items:center;padding:1px 10px;border-radius:12px;font-size:13.5px;
            font-weight:700;white-space:nowrap;border:1px solid currentColor}
            .badge.good{color:var(--green-text);background:var(--green-bg)}
            .badge.warn{color:var(--amber);background:var(--amber-bg)}
            .badge.bad{color:var(--red-text);background:var(--red-bg)}
            .badge.neutral{color:var(--slate);background:var(--grey-bg)}
            .trust{display:inline-flex;align-items:center;justify-content:center;min-width:26px;height:24px;padding:0 6px;
            border-radius:4px;font-size:13.5px;font-weight:700;border:2px solid var(--ink)}
            .trust.a{background:var(--ink);color:#fff}.trust.b{background:#fff;color:var(--ink)}
            .rel{display:inline-flex;padding:2px 10px;border-radius:12px;font-size:13.5px;font-weight:700;white-space:nowrap}
            .rel.amends{background:var(--blue);color:#fff}.rel.repeals{background:var(--red);color:#fff}
            .rel.converts{background:var(--green);color:#fff}.rel.changes{background:var(--grey-bg);color:var(--ink)}
            .pager{display:flex;flex-wrap:wrap;gap:8px;justify-content:center;padding-top:6px}
            .pager a,.pager span{min-width:44px;min-height:44px;display:inline-flex;align-items:center;justify-content:center;
            padding:0 12px;border-radius:6px;background:#fff;text-decoration:none;font-weight:600;box-shadow:var(--shadow)}
            .pager [aria-current]{background:var(--blue);color:#fff}
            .pager .gap{background:none;box-shadow:none}
            .crumbs{font-size:15px;color:var(--slate)}
            .eli-box{display:flex;flex-wrap:wrap;gap:10px 14px;align-items:center;background:var(--ground);border-radius:6px;padding:10px 14px}
            .eli-box .k{font-size:13.5px;font-weight:700;color:var(--slate)}
            .eli-box code{flex:1 1 320px;min-width:0}
            .eli-box .links{display:flex;flex-wrap:wrap;gap:14px;font-weight:600;font-size:15px}
            dl.facts{display:grid;grid-template-columns:minmax(120px,auto) minmax(0,1fr);gap:8px 16px;margin:0;font-size:15.5px}
            dl.facts dt{color:var(--slate)}dl.facts dd{margin:0;font-weight:600;overflow-wrap:anywhere}
            .side dl.facts{grid-template-columns:1fr;gap:0}.side dl.facts dd{margin-bottom:8px}
            .tl-body a{text-decoration:none}.tl-body a:hover{text-decoration:underline}
            .answer{font-size:17px}
            .conversion{border-top:6px solid var(--line)}
            .conversion.good{border-top-color:var(--green)}.conversion.warn{border-top-color:var(--amber)}
            .conversion.bad{border-top-color:var(--red)}
            .checks{list-style:none;margin:10px 0 0;padding:0;display:flex;flex-direction:column;gap:6px;font-size:15px}
            .checks li{display:flex;gap:10px;align-items:baseline}
            .checks .ok{color:var(--green-text);font-weight:700}.checks .no{color:var(--red-text);font-weight:700}
            .checks .code{font-family:var(--mono);font-size:13px;color:var(--slate)}
            details summary{cursor:pointer;color:var(--blue);font-weight:700}
            details ul{margin:8px 0 0;padding-left:20px}
            ol.timeline{list-style:none;margin:0;padding:0}
            ol.timeline li{display:flex;gap:14px}
            .tl-mark{display:flex;flex-direction:column;align-items:center;flex:0 0 16px}
            .tl-dot{width:14px;height:14px;border-radius:50%;background:var(--blue);margin-top:5px}
            .tl-dot.first{background:var(--ink)}
            .tl-by{font-size:15px}
            .tl-line{flex:1;width:2px;background:var(--line)}
            ol.timeline li:last-child .tl-line{display:none}
            .tl-body{padding-bottom:14px;display:flex;flex-direction:column;min-width:0}
            .table-wrap{overflow-x:auto}
            table{width:100%;border-collapse:collapse;font-size:15.5px}
            th,td{text-align:left;padding:10px 8px;border-bottom:1px solid var(--line-soft);vertical-align:top}
            thead th{color:var(--slate);font-size:13px;font-weight:700;text-transform:uppercase;letter-spacing:.04em;
            border-bottom:2px solid var(--line)}
            tbody tr:hover td{background:#f8fbfe}
            td.num,th.num{text-align:right;white-space:nowrap}
            .nowrap{white-space:nowrap}
            .examples{display:flex;flex-direction:column;gap:4px}
            .examples a{display:block;padding:9px 12px;border-radius:6px;text-decoration:none;font-weight:600}
            .examples a:hover{background:var(--tint)}
            .examples a[aria-current]{background:var(--blue);color:#fff}
            textarea{width:100%;min-height:240px;padding:14px;border:2px solid var(--line);border-radius:6px;
            background:var(--ground);font-family:var(--mono);font-size:14px;line-height:1.6;resize:vertical}
            .actions{display:flex;flex-wrap:wrap;gap:12px;align-items:center}
            .actions .muted{margin-left:auto;font-size:14px}
            pre{background:var(--ground);border-radius:6px;padding:12px;overflow:auto;font-family:var(--mono);font-size:13px}
            .error{background:var(--red-bg);color:var(--red-text);border:1px solid var(--red);border-radius:6px;padding:10px 14px}
            @media(max-width:640px){.wrap{padding:0 16px}h1{font-size:28px}.brand-title{font-size:22px}
            .brand-sub{display:none}.tile .v{font-size:30px}dl.facts{grid-template-columns:1fr}dl.facts dd{margin-bottom:6px}}
            """;
}
