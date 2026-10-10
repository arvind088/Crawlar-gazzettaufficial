package it.legislation.source.gazzetta;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * What the Gazzetta Ufficiale act page ({@code /eli/id/yyyy/mm/dd/CODICE/sg})
 * says about one act.
 *
 * <p>Identity fields come from the page's ELI RDFa ({@code eli:id_local},
 * {@code eli:type_document}, {@code eli:date_document},
 * {@code eli:date_publication}, {@code eli:title}). The act number is read
 * from the heading ("LEGGE 19 giugno 2026, n. 103"), the GU issue from the
 * "GU Serie Generale n.141 del 20-06-2026" link, and the date of entry into
 * force from the note under the title.
 *
 * @param codice           codice redazionale ({@code eli:id_local})
 * @param publicationDate  GU publication date
 * @param typeCode         Gazzetta resource-type code, e.g. {@code LEGGE}, {@code DECRETO-LEGGE}
 * @param documentDate     date of the act
 * @param number           act number, null when the heading has none
 * @param title            title without the trailing "(codice)"
 * @param guNumber         Serie Generale issue number, null when absent
 * @param guIssueUri       ELI of the GU issue, null when absent
 * @param entryIntoForce   "Entrata in vigore del provvedimento", null when absent
 */
public record GazzettaActPage(String codice, LocalDate publicationDate, String typeCode, LocalDate documentDate,
                              String number, String title, String guNumber, String guIssueUri,
                              LocalDate entryIntoForce) {

    private static final Pattern NUMBER = Pattern.compile(
            "n\\.\\s*(\\d+[A-Za-z\\-]*)\\s*(?:\\(Raccolta\\s+\\d{4}\\))?\\s*$");
    private static final Pattern GU_ISSUE = Pattern.compile("Serie\\s+Generale\\s+n\\.\\s*(\\d+)");
    private static final Pattern ENTRY_INTO_FORCE = Pattern.compile(
            "Entrata in vigore del provvedimento:\\s*(\\d{2}/\\d{2}/\\d{4})");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Reads an act page. Returns empty when the page carries no act
     * ({@code eli:id_local} missing), which is how Gazzetta answers an
     * identifier it does not know.
     */
    public static Optional<GazzettaActPage> parse(String html, String url) {
        Document doc = Jsoup.parse(html, url);
        String codice = value(doc, "eli:id_local");
        if (codice == null) {
            return Optional.empty();
        }
        String type = value(doc, "eli:type_document");
        String typeCode = type == null ? null : type.substring(type.indexOf('#') + 1);

        String number = null;
        Element heading = doc.selectFirst("#testa_atto h2");
        if (heading != null) {
            Matcher m = NUMBER.matcher(clean(heading.text()));
            if (m.find()) {
                number = m.group(1);
            }
        }

        String guNumber = null;
        String guIssueUri = null;
        Element issue = doc.selectFirst("span.link_gazzetta a");
        if (issue != null) {
            Matcher m = GU_ISSUE.matcher(issue.text());
            if (m.find()) {
                guNumber = m.group(1);
            }
            String href = issue.attr("href");
            if (!href.isBlank()) {
                guIssueUri = href.replaceFirst("^https://", "http://").replaceFirst("/pdf$", "");
            }
        }

        LocalDate entryIntoForce = null;
        Element note = doc.selectFirst("#testa_atto h4.note");
        if (note != null) {
            Matcher m = ENTRY_INTO_FORCE.matcher(clean(note.text()));
            if (m.find()) {
                entryIntoForce = dmy(m.group(1));
            }
        }

        return Optional.of(new GazzettaActPage(codice, iso(value(doc, "eli:date_publication")), typeCode,
                iso(value(doc, "eli:date_document")), number, cleanTitle(value(doc, "eli:title"), codice),
                guNumber, guIssueUri, entryIntoForce));
    }

    /** Collapses white space (including non-breaking spaces) and trims. */
    static String clean(String text) {
        return text == null ? null : text.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    /** Title without the trailing "(codice redazionale)", white space collapsed. */
    public static String cleanTitle(String title, String codice) {
        if (title == null) {
            return null;
        }
        String t = clean(title);
        String suffix = "(" + codice + ")";
        return t.endsWith(suffix) ? t.substring(0, t.length() - suffix.length()).trim() : t;
    }

    private static String value(Document doc, String property) {
        Element e = doc.selectFirst("[property=" + property + "]");
        if (e == null) {
            return null;
        }
        String v = e.hasAttr("content") ? e.attr("content") : e.hasAttr("resource") ? e.attr("resource") : e.text();
        v = clean(v);
        return v == null || v.isEmpty() ? null : v;
    }

    private static LocalDate iso(String value) {
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate dmy(String value) {
        try {
            return LocalDate.parse(value, DMY);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
