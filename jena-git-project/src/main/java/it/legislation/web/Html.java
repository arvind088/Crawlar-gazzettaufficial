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
                + "<style>" + CSS + "</style>\n</head>\n<body>\n"
                + "<header class=\"top\"><div class=\"wrap\">"
                + "<a class=\"brand\" href=\"/\">" + APP_NAME + "</a>"
                + "<nav><a href=\"/\"" + current(active, "acts") + ">Legal acts</a>"
                + "<a href=\"/query\"" + current(active, "sparql") + ">SPARQL</a></nav>"
                + "</div></header>\n<main class=\"wrap\">\n" + body + "</main>\n"
                + "<footer class=\"wrap\">Data: Normattiva OpenData (Akoma Ntoso), checked against Gazzetta Ufficiale. "
                + "Every page is read live from the triple store.</footer>\n</body>\n</html>\n";
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
            :root{--bg:#f4f6f8;--surface:#fff;--text:#14202e;--muted:#5d6b7a;--line:#dde3e9;
            --accent:#0b6e65;--good:#1d7a3e;--good-bg:#e5f4ea;--warn:#8a5a00;--warn-bg:#fdf2dc;
            --bad:#a42828;--bad-bg:#fbe7e7;--neutral-bg:#eceff3}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--text);font:15px/1.55 system-ui,-apple-system,"Segoe UI",Arial,sans-serif}
            .wrap{max-width:1100px;margin:0 auto;padding:0 20px}
            header.top{background:var(--surface);border-bottom:1px solid var(--line)}
            header.top .wrap{display:flex;align-items:center;justify-content:space-between;gap:16px;min-height:56px;flex-wrap:wrap}
            .brand{font-weight:700;color:var(--text);text-decoration:none}
            nav a{color:var(--muted);text-decoration:none;margin-left:20px;font-weight:600}
            nav a[aria-current]{color:var(--accent);border-bottom:2px solid var(--accent);padding-bottom:4px}
            main{padding-top:24px;padding-bottom:24px}
            h1{font-size:24px;line-height:1.3;margin:4px 0 6px}
            h2{font-size:17px;margin:0 0 10px}
            a{color:var(--accent)}
            .muted{color:var(--muted)}
            .mono{font-family:ui-monospace,Consolas,Menlo,monospace;font-size:12.5px;word-break:break-all}
            .card{background:var(--surface);border:1px solid var(--line);border-radius:10px;padding:16px 20px;margin:16px 0}
            .stats{display:flex;gap:28px;flex-wrap:wrap;margin:8px 0 4px}
            .stats b{display:block;font-size:20px}
            form.filters{display:flex;gap:10px;flex-wrap:wrap;align-items:end}
            form.filters label{display:flex;flex-direction:column;font-size:12.5px;color:var(--muted);gap:3px}
            input,select,textarea,button{font:inherit}
            input[type=search],select{padding:6px 8px;border:1px solid var(--line);border-radius:6px;background:#fff}
            input[type=search]{min-width:260px}
            button{padding:7px 14px;border:0;border-radius:6px;background:var(--accent);color:#fff;font-weight:600;cursor:pointer}
            table{width:100%;border-collapse:collapse;background:var(--surface)}
            th,td{text-align:left;padding:8px 10px;border-bottom:1px solid var(--line);vertical-align:top}
            th{font-size:12.5px;color:var(--muted);font-weight:600;background:#fafbfc}
            td.num{text-align:right;white-space:nowrap}
            td.nowrap,.nowrap{white-space:nowrap}
            details>.table-wrap{margin-top:10px}
            .table-wrap{overflow-x:auto;border:1px solid var(--line);border-radius:10px}
            .badge{display:inline-block;padding:1px 8px;border-radius:10px;font-size:12px;font-weight:600;white-space:nowrap}
            .badge.good{background:var(--good-bg);color:var(--good)}
            .badge.warn{background:var(--warn-bg);color:var(--warn)}
            .badge.bad{background:var(--bad-bg);color:var(--bad)}
            .badge.neutral{background:var(--neutral-bg);color:var(--muted)}
            dl.facts{display:grid;grid-template-columns:200px minmax(0,1fr);margin:0}
            dl.facts dt,dl.facts dd{margin:0;padding:6px 0;border-top:1px solid var(--line)}
            dl.facts dt{color:var(--muted)}
            .pager{display:flex;gap:12px;align-items:center;margin:12px 0}
            .checks span{margin-right:6px}
            details summary{cursor:pointer;color:var(--accent)}
            textarea{width:100%;min-height:220px;padding:10px;border:1px solid var(--line);border-radius:8px;
            font-family:ui-monospace,Consolas,Menlo,monospace;font-size:13px}
            pre{background:var(--surface);border:1px solid var(--line);border-radius:8px;padding:12px;overflow:auto;font-size:12.5px}
            .error{background:var(--bad-bg);color:var(--bad);border-radius:8px;padding:10px 14px}
            footer{color:var(--muted);font-size:12.5px;padding-bottom:32px}
            @media(max-width:640px){dl.facts{grid-template-columns:1fr}dl.facts dd{border-top:0;padding-top:0}
            input[type=search]{min-width:0;width:100%}nav a{margin-left:12px}}
            """;
}
