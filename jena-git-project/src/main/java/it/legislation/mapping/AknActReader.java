package it.legislation.mapping;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import it.legislation.model.AknExpression;
import it.legislation.source.RawStore;

/**
 * Reads the identity and version of an act from one Normattiva Akoma Ntoso file.
 *
 * <p>Only metadata is read here: FRBR identifiers, the Gazzetta publication,
 * the preface (type, number, date, title). Relations ({@code textualMod}) are
 * read later by the relation module. The parser refuses DTDs and external
 * entities.
 */
public class AknActReader {

    static final String AKN = "http://docs.oasis-open.org/legaldocml/ns/akn/3.0";

    /** {@code eli/id/2020/04/29/20G00045/ORIGINAL} or {@code eli/id/2003/07/29/003G0218/CONSOLIDATED/20260220}. */
    private static final Pattern ELI_ALIAS = Pattern.compile(
            "eli/id/(\\d{4})/(\\d{2})/(\\d{2})/([0-9A-Z]+)/(ORIGINAL|CONSOLIDATED)(?:/(\\d{8}))?");

    /** Result of reading one file: either an expression or the reasons it was rejected. */
    public record Result(AknExpression expression, AknExpression.Rejected rejected) {
        public boolean ok() {
            return expression != null;
        }
    }

    public Result read(Path file, String sourceName) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        List<String> problems = new ArrayList<>();
        Document document;
        try (InputStream in = Files.newInputStream(file)) {
            document = secureFactory().newDocumentBuilder().parse(in);
        } catch (Exception exception) {
            return rejected(sourceName, "XML not readable: " + exception.getMessage());
        }
        Element root = document.getDocumentElement();
        if (!"akomaNtoso".equals(root.getLocalName()) || !AKN.equals(root.getNamespaceURI())) {
            return rejected(sourceName, "Root is not Akoma Ntoso 3.0");
        }

        Element work = first(root, "FRBRWork");
        Element expression = first(root, "FRBRExpression");
        String eliAlias = null;
        String urn = null;
        if (work != null) {
            for (Element alias : children(work, "FRBRalias")) {
                if ("eli".equals(alias.getAttribute("name"))) {
                    eliAlias = alias.getAttribute("value");
                } else if ("urn:nir".equals(alias.getAttribute("name"))) {
                    urn = alias.getAttribute("value");
                }
            }
        }

        String codice = null;
        LocalDate gazzettaDate = null;
        boolean original = true;
        LocalDate consolidatedDate = null;
        Matcher matcher = eliAlias == null ? null : ELI_ALIAS.matcher(eliAlias);
        if (matcher != null && matcher.find()) {
            gazzettaDate = date(matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3), "GU date", problems);
            codice = matcher.group(4);
            original = "ORIGINAL".equals(matcher.group(5));
            if (matcher.group(6) != null) {
                String d = matcher.group(6);
                consolidatedDate = date(d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6), "version date", problems);
            }
        } else {
            problems.add("ELI alias with GU date and codice redazionale missing or unrecognised: " + eliAlias);
        }

        Element publication = first(root, "publication");
        String gazzettaNumber = publication == null ? null : blankToNull(publication.getAttribute("number"));
        LocalDate expressionDate = null;
        Element expressionDateElement = expression == null ? null : first(expression, "FRBRdate");
        if (expressionDateElement != null) {
            expressionDate = date(expressionDateElement.getAttribute("date"), "expression date", problems);
        }

        String actType = text(first(root, "docType"));
        String number = actNumber(text(first(root, "docNumber")));
        Element docDate = first(root, "docDate");
        LocalDate documentDate = docDate == null ? null : date(docDate.getAttribute("date"), "document date", problems);
        String title = decodeEntities(text(first(root, "docTitle")));

        require(problems, codice, "codice redazionale");
        require(problems, gazzettaDate, "GU date");
        require(problems, actType, "act type (docType)");
        require(problems, number, "act number (docNumber)");
        require(problems, documentDate, "document date (docDate)");
        require(problems, title, "title (docTitle)");
        require(problems, expressionDate, "expression date (FRBRExpression/FRBRdate)");
        if (urn != null && !urn.startsWith("urn:nir:")) {
            problems.add("Unexpected URN alias: " + urn);
        }
        if (!original && consolidatedDate == null) {
            problems.add("Consolidated version without a date in its ELI alias");
        }
        if (!problems.isEmpty()) {
            return new Result(null, new AknExpression.Rejected(sourceName, List.copyOf(problems)));
        }

        LocalDate versionDate = original ? expressionDate : consolidatedDate;
        return new Result(new AknExpression(codice, gazzettaDate, gazzettaNumber, actType, documentDate, number,
                title, urn, original, versionDate, sourceName, RawStore.sha256(bytes)), null);
    }

    static DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static Result rejected(String source, String problem) {
        return new Result(null, new AknExpression.Rejected(source, List.of(problem)));
    }

    private static Element first(Element scope, String localName) {
        NodeList nodes = scope.getElementsByTagNameNS(AKN, localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static String text(Element element) {
        if (element == null) {
            return null;
        }
        return blankToNull(element.getTextContent().replaceAll("\\s+", " ").trim());
    }

    private static final java.util.regex.Pattern RACCOLTA = java.util.regex.Pattern.compile("\\s*\\(Raccolta\\s+\\d{4}\\)\\s*$");
    private static final java.util.regex.Pattern NUMERIC_ENTITY = java.util.regex.Pattern.compile("&#(x[0-9A-Fa-f]+|\\d+);");

    /**
     * The act number without the "(Raccolta 2020)" note. The first act of a
     * year is numbered "1 (Raccolta 2020)" in both sources; the number is 1,
     * the note only names the yearly collection it opens.
     */
    public static String actNumber(String number) {
        return number == null ? null : RACCOLTA.matcher(number).replaceFirst("");
    }

    /**
     * Replaces numeric character references left as text (for example
     * {@code &#x200a;}, a hair space, written escaped twice in some
     * Normattiva titles) with the character they stand for.
     */
    public static String decodeEntities(String text) {
        if (text == null || !text.contains("&#")) {
            return text;
        }
        java.util.regex.Matcher m = NUMERIC_ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String code = m.group(1);
            int cp = code.startsWith("x") ? Integer.parseInt(code.substring(1), 16) : Integer.parseInt(code);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static LocalDate date(String value, String field, List<String> problems) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            problems.add("Invalid " + field + ": " + value);
            return null;
        }
    }

    private static void require(List<String> problems, Object value, String field) {
        if (value == null) {
            problems.add("Missing " + field);
        }
    }
}
