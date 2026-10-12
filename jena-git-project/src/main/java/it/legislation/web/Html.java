package it.legislation.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

/**
 * The page frame shared by the three public views (Legal acts, Act details,
 * SPARQL) and the generic resource page: header with navigation, one style
 * sheet, escaping, and how acts are named.
 *
 * <p>Pages are plain server-rendered HTML with no script, so every act has a
 * real address that works in any browser and gives RDF to machines (content
 * negotiation in {@code EliResolutionController}).
 */
public final class Html {

    public static final String APP_NAME = "Italian Legislation Linked Data";

    private Html() {
    }

    /** A whole page. {@code active} is "acts" or "sparql" for the highlighted menu item. */
    public static String page(String title, String active, String head, String body) {
        return "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + escape(title) + " &middot; " + APP_NAME + "</title>\n"
                + (head == null ? "" : head)
                + "<style>" + CSS + "</style>\n</head>\n<body>\n<div class=\"shell\"><div class=\"layout\">\n"
                + "<header class=\"workspace-card\"><div class=\"hero\"><div class=\"hero-inner\">"
                + "<a class=\"brand-lockup\" href=\"/\"><span class=\"brand-mark\" aria-hidden=\"true\">" + SCALES + "</span>"
                + "<span><span class=\"eyebrow\">Linked open data &middot; ELI</span>"
                + "<span class=\"brand-title\">" + APP_NAME + "</span>"
                + "<span class=\"hero-text\">Laws, decree-laws and legislative decrees from Normattiva, "
                + "checked against Gazzetta Ufficiale.</span></span></a>"
                + "<a class=\"hero-status\" href=\"/api/health\"><span class=\"status-dot\"></span>Live triple store</a>"
                + "</div></div>"
                + "<nav class=\"tabs\"><a class=\"tab\" href=\"/\"" + current(active, "acts") + ">Legal acts</a>"
                + "<a class=\"tab\" href=\"/query\"" + current(active, "sparql") + ">SPARQL</a></nav>"
                + "</header>\n<main class=\"workspace-card content\">\n" + body + "</main>\n"
                + "<footer>Data: Normattiva OpenData (Akoma Ntoso), checked against Gazzetta Ufficiale. "
                + "Every page is read live from the triple store.</footer>\n</div></div>\n</body>\n</html>\n";
    }

    /** The scales of justice, as in the earlier interface's brand mark. */
    private static final String SCALES = "<svg width=\"26\" height=\"26\" viewBox=\"0 0 24 24\" fill=\"none\" "
            + "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">"
            + "<path d=\"M12 3v18M7 21h10M5 7h14M12 7l0 0\"/><path d=\"M5 7l-3 6a3 3 0 0 0 6 0z\"/>"
            + "<path d=\"M19 7l-3 6a3 3 0 0 0 6 0z\"/></svg>";

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

    /** A coloured label for a status value. */
    public static String badge(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        String tone = switch (status) {
            case "CONVERTED", "MATCH", "FOUND", "A" -> "good";
            case "CONVERTED_ONE_SOURCE", "B", "PENDING", "AWAITING_LAW_TEXT" -> "warn";
            case "REPEALED", "NOT_CONVERTED", "MISMATCH", "NOT_FOUND", "ERROR", "REVIEW", "WITHDRAWN" -> "bad";
            default -> "neutral";
        };
        return "<span class=\"badge " + tone + "\">" + escape(status.replace('_', ' ')) + "</span>";
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
            :root{--bg:#eef4f3;--surface:#fff;--surface-soft:#f7fbfa;--text:#102033;--muted:#667586;--line:#dfe9e8;
            --accent:#008778;--accent-dark:#006c61;--accent-soft:#e6f5f2;--accent-pale:#f1fbf9;
            --good:#006c61;--good-bg:#e6f5f2;--warn:#a16207;--warn-bg:#fff5d6;--bad:#b42318;--bad-bg:#fdecea;
            --neutral-bg:#eef2f4;--focus:#00695f;
            --shadow:0 16px 45px rgba(15,43,54,.11);--shadow-soft:0 8px 24px rgba(15,43,54,.08)}
            *{box-sizing:border-box}
            body{margin:0;color:var(--text);font:16px/1.5 Arial,Helvetica,sans-serif;
            background:radial-gradient(circle at 12% 10%,rgba(0,135,120,.09),transparent 26%),
            linear-gradient(135deg,#f8fbfb 0%,var(--bg) 100%);min-height:100vh}
            .shell{padding:24px}
            .layout{display:grid;gap:22px;margin:0 auto;max-width:1280px}
            .workspace-card{background:rgba(255,255,255,.94);border:1px solid rgba(223,233,232,.95);
            border-radius:18px;box-shadow:var(--shadow);overflow:hidden}
            .hero{background:linear-gradient(180deg,#fff 0%,#fbfefe 100%);border-bottom:1px solid var(--line)}
            .hero-inner{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:24px;align-items:center;padding:26px 28px 16px}
            .brand-lockup{display:flex;align-items:center;gap:16px;min-width:0;color:inherit;text-decoration:none}
            .brand-lockup>span:last-child{display:flex;flex-direction:column;min-width:0}
            .brand-mark{display:inline-flex;align-items:center;justify-content:center;flex:0 0 auto;width:48px;height:48px;
            background:var(--accent-soft);border:1px solid #bfebe5;border-radius:12px;color:var(--accent-dark)}
            .eyebrow{color:var(--accent-dark);font-size:11px;font-weight:700;text-transform:uppercase;margin:0 0 6px}
            .brand-title{font-size:30px;font-weight:700;line-height:1.1}
            .hero-text{color:var(--muted);font-size:15px;line-height:1.45;margin-top:8px}
            .hero-status{display:inline-flex;align-items:center;gap:9px;min-height:40px;padding:0 14px;background:#fff;
            border:1px solid var(--line);border-radius:9px;box-shadow:0 8px 18px rgba(15,43,54,.06);
            font-size:13px;font-weight:700;color:var(--text);text-decoration:none;white-space:nowrap}
            .status-dot{width:10px;height:10px;border-radius:999px;background:var(--accent)}
            .tabs{display:flex;gap:24px;overflow-x:auto;padding:0 28px}
            .tab{display:inline-flex;align-items:center;min-height:52px;border-bottom:3px solid transparent;
            color:var(--muted);font-size:15px;font-weight:700;text-decoration:none;white-space:nowrap}
            .tab:hover,.tab[aria-current]{border-bottom-color:var(--accent);color:var(--accent-dark)}
            main.content{padding:24px 28px}
            h1{font-size:28px;line-height:1.2;margin:0 0 6px}
            h2{font-size:20px;line-height:1.25;margin:0 0 12px}
            a{color:var(--accent-dark)}
            .muted{color:var(--muted)}
            .mono{font-family:ui-monospace,Consolas,Menlo,monospace;font-size:12.5px;word-break:break-all}
            .card{background:var(--surface);border:1px solid #e6eeee;border-radius:12px;box-shadow:var(--shadow-soft);
            padding:18px 20px;margin:18px 0}
            .stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin:18px 0}
            .stats>div{background:var(--surface);border:1px solid #e6eeee;border-radius:12px;box-shadow:var(--shadow-soft);
            padding:18px;color:var(--muted);font-size:14px;font-weight:700}
            .stats b{display:block;color:var(--text);font-size:31px;line-height:1.1;margin-bottom:5px}
            form.filters{display:flex;gap:12px;flex-wrap:wrap;align-items:end}
            form.filters label{display:grid;gap:6px;color:var(--muted);font-size:12px;font-weight:700;text-transform:uppercase}
            input,select,textarea,button{font:inherit}
            input[type=search],select{min-height:40px;padding:0 10px;border:1px solid var(--line);border-radius:7px;
            background:#fff;color:var(--text)}
            input[type=search]{min-width:280px}
            button{display:inline-flex;align-items:center;min-height:40px;padding:0 16px;background:var(--accent);
            border:1px solid var(--accent);border-radius:7px;color:#fff;font-weight:700;cursor:pointer}
            button:hover{background:var(--accent-dark);border-color:var(--accent-dark)}
            input:focus,select:focus,button:focus,textarea:focus,a:focus-visible{outline:2px solid var(--focus);outline-offset:2px}
            .table-wrap{overflow-x:auto;background:var(--surface);border:1px solid #e6eeee;border-radius:12px;box-shadow:var(--shadow-soft)}
            table{width:100%;border-collapse:collapse}
            th,td{text-align:left;padding:13px 12px;border-top:1px solid var(--line);vertical-align:top;font-size:15px;line-height:1.35}
            thead th{border-top:0;background:#fbfdfd;color:var(--muted);font-size:13px;font-weight:700;text-transform:uppercase}
            tbody tr:hover td{background:var(--accent-pale)}
            td.num{text-align:right;white-space:nowrap}
            td.nowrap,.nowrap{white-space:nowrap}
            .card .table-wrap{box-shadow:none}
            details>.table-wrap{margin-top:10px}
            .badge{display:inline-flex;padding:3px 9px;border-radius:7px;font-size:12px;font-weight:700;white-space:nowrap;border:1px solid transparent}
            .badge.good{background:var(--good-bg);color:var(--good);border-color:#bfebe5}
            .badge.warn{background:var(--warn-bg);color:var(--warn);border-color:#f3dfa4}
            .badge.bad{background:var(--bad-bg);color:var(--bad);border-color:#f5c6c0}
            .badge.neutral{background:var(--neutral-bg);color:var(--muted)}
            dl.facts{display:grid;grid-template-columns:220px minmax(0,1fr);margin:0}
            dl.facts dt,dl.facts dd{margin:0;padding:10px 0;border-top:1px solid var(--line)}
            dl.facts dt{color:var(--muted);font-size:13px;font-weight:700;text-transform:uppercase;padding-top:12px}
            .pager{display:flex;gap:14px;align-items:center;margin:16px 0 0}
            details summary{cursor:pointer;color:var(--accent-dark);font-weight:700}
            textarea{width:100%;min-height:220px;padding:12px;border:1px solid var(--line);border-radius:9px;
            font-family:ui-monospace,Consolas,Menlo,monospace;font-size:13px}
            pre{background:var(--surface-soft);border:1px solid var(--line);border-radius:9px;padding:12px;overflow:auto;font-size:12.5px}
            .error{background:var(--bad-bg);color:var(--bad);border:1px solid #f5c6c0;border-radius:9px;padding:10px 14px}
            footer{color:var(--muted);font-size:13px;text-align:center;padding:0 0 12px}
            @media(max-width:760px){.shell{padding:12px}.hero-inner{grid-template-columns:1fr;padding:20px 18px 12px}
            .tabs{padding:0 18px}main.content{padding:18px}.brand-title{font-size:24px}
            .stats{grid-template-columns:1fr}dl.facts{grid-template-columns:1fr}dl.facts dd{border-top:0;padding-top:0}
            input[type=search]{min-width:0;width:100%}}
            """;
}
