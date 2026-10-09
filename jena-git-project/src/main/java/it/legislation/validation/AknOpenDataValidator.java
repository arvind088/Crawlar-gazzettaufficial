package it.legislation.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class AknOpenDataValidator {
    private static final String AKN = "http://docs.oasis-open.org/legaldocml/ns/akn/3.0";
    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    private AknOpenDataValidator() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 && System.getProperty("akn.input") != null) {
            String configuredOutput = System.getProperty("akn.output");
            args = configuredOutput == null
                    ? new String[] {System.getProperty("akn.input")}
                    : new String[] {System.getProperty("akn.input"), configuredOutput};
        }
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: validate-akn.cmd <xml-file-or-folder> [report.json]");
            System.exit(2);
        }

        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = args.length == 2
                ? Path.of(args[1]).toAbsolutePath().normalize()
                : Path.of("akn-validation-report.json").toAbsolutePath().normalize();
        if (!Files.exists(input)) {
            throw new IllegalArgumentException("Input does not exist: " + input);
        }

        List<Path> files;
        if (Files.isDirectory(input)) {
            try (Stream<Path> paths = Files.walk(input)) {
                files = paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".xml"))
                        .sorted()
                        .toList();
            }
        } else {
            files = List.of(input);
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No XML files found under: " + input);
        }

        List<FileResult> results = files.stream().map(AknOpenDataValidator::validate).toList();
        int queryable = (int) results.stream().filter(FileResult::queryable).count();
        int relations = results.stream().mapToInt(result -> result.relations().size()).sum();
        Report report = new Report(OffsetDateTime.now().toString(), input.toString(), results.size(),
                queryable, results.size() - queryable, relations, results);

        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(output.toFile(), report);
        print(report, output);
        if (report.rejected() > 0) System.exit(1);
    }

    static FileResult validate(Path file) {
        List<Issue> issues = new ArrayList<>();
        List<Relation> relations = new ArrayList<>();
        String hash;
        try {
            hash = sha256(file);
        } catch (Exception exception) {
            return rejected(file, "HASH_FAILED", exception.getMessage());
        }

        Document document;
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new AknNamespaces());
        try {
            document = parse(file);
        } catch (Exception exception) {
            return new FileResult(file.toString(), hash, "REJECTED", false, null,
                    new Counts(0, 0, 0, 0, 1), List.of(),
                    List.of(new Issue("ERROR", "XML_INVALID", exception.getMessage(), "")));
        }

        Element root = document.getDocumentElement();
        if (!"akomaNtoso".equals(root.getLocalName()) || !AKN.equals(root.getNamespaceURI())) {
            issues.add(new Issue("ERROR", "AKN_NAMESPACE_INVALID", "Root is not Akoma Ntoso 3.0.", ""));
        }

        Act act = new Act(
                value(xpath, document, "//akn:docType", null),
                value(xpath, document, "//akn:docNumber", null),
                value(xpath, document, "//akn:docDate", "date"),
                value(xpath, document, "//akn:docTitle", null),
                value(xpath, document, "//akn:publication", "date"),
                value(xpath, document, "//akn:publication", "number"),
                value(xpath, document, "//*[local-name()='span'][@property='eli:id_local']", "content"),
                value(xpath, document, "//akn:FRBRWork/akn:FRBRuri", "value"),
                value(xpath, document, "//akn:FRBRExpression/akn:FRBRuri", "value"),
                value(xpath, document, "//akn:FRBRExpression/akn:FRBRdate", "date"),
                value(xpath, document, "//akn:FRBRManifestation/akn:FRBRuri", "value"),
                value(xpath, document, "//akn:FRBRWork/akn:FRBRalias[@name='eli']", "value"),
                value(xpath, document, "//akn:FRBRWork/akn:FRBRalias[@name='urn:nir']", "value"));

        Map<String, String> required = new LinkedHashMap<>();
        required.put("ACT_TYPE", act.type()); required.put("ACT_NUMBER", act.number());
        required.put("DOCUMENT_DATE", act.documentDate()); required.put("TITLE", act.title());
        required.put("PUBLICATION_DATE", act.publicationDate()); required.put("WORK_URI", act.workUri());
        required.put("EXPRESSION_URI", act.expressionUri()); required.put("MANIFESTATION_URI", act.manifestationUri());
        required.forEach((name, value) -> {
            if (blank(value)) issues.add(new Issue("ERROR", "MISSING_" + name,
                    "Required field " + name + " is missing.", ""));
        });
        checkDate(issues, "documentDate", act.documentDate());
        checkDate(issues, "publicationDate", act.publicationDate());
        checkDate(issues, "expressionDate", act.expressionDate());
        if (!blank(act.urnAlias())) issues.add(new Issue("INFO", "URN_ALIAS_PRESENT",
                "Keep the URN only as source metadata, never as an RDF subject.", ""));

        if (contains(xpath, document,
                "//*[local-name()='Description']/*[local-name()='type'][@rdf:resource='eli:format']")) {
            issues.add(new Issue("WARNING", "ELI_FORMAT_CLASS_CASE",
                    "Normalize RDF class eli:format to eli:Format.", ""));
        }

        List<String> targets = new ArrayList<>();
        NodeList references = nodes(xpath, document, "//akn:ref");
        for (int i = 0; i < references.getLength(); i++) {
            Element reference = (Element) references.item(i);
            String href = reference.getAttribute("href");
            if (blank(href)) {
                issues.add(new Issue("WARNING", "REFERENCE_TARGET_MISSING", "Reference has no href.", ""));
            } else {
                targets.add(target(href));
                if (malformed(href)) issues.add(new Issue("WARNING", "REFERENCE_TARGET_MALFORMED",
                        "Malformed target: " + href, ""));
            }
        }

        String source = blank(act.eliAlias()) ? act.workUri() : act.eliAlias();
        NodeList articles = nodes(xpath, document, "//akn:article");
        for (int i = 0; i < articles.getLength(); i++) {
            Element article = (Element) articles.item(i);
            String heading = value(xpath, article, "./akn:heading", null);
            if (blank(heading) || !heading.toLowerCase().contains("modific")) continue;
            Node reference = node(xpath, article, ".//akn:ref[not(ancestor::akn:authorialNote)][1]");
            String relationTarget = reference instanceof Element element ? target(element.getAttribute("href")) : null;
            if (!blank(relationTarget) && relationTarget.startsWith("/akn/it/act/") && !malformed(relationTarget)) {
                relations.add(new Relation("VERIFIED", "AMENDS", source, relationTarget, heading,
                        article.getAttribute("eId"), "AMENDS_EXPLICIT_V1"));
            } else {
                issues.add(new Issue("WARNING", "RELATION_TARGET_MISSING",
                        "Modification heading found without a valid target.", heading));
            }
        }
        Map<String, Relation> uniqueRelations = new LinkedHashMap<>();
        relations.forEach(relation -> uniqueRelations.putIfAbsent(relation.type() + "|" + relation.target(), relation));
        relations = uniqueRelations.values().stream().sorted(Comparator.comparing(Relation::target)).toList();
        int errors = count(issues, "ERROR");
        int warnings = count(issues, "WARNING");
        boolean queryable = errors == 0;
        String status = !queryable ? "REJECTED" : warnings > 0 ? "VALID_WITH_WARNINGS" : "VALID";
        int uniqueTargets = (int) targets.stream().filter(target -> !blank(target)).distinct().count();
        return new FileResult(file.toString(), hash, status, queryable, act,
                new Counts(references.getLength(), uniqueTargets, relations.size(), warnings, errors),
                relations, issues);
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (InputStream input = Files.newInputStream(file)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static String value(XPath xpath, Object context, String expression, String attribute) {
        try {
            Node node = node(xpath, context, expression);
            if (node == null) return null;
            String result = attribute == null ? node.getTextContent() : ((Element) node).getAttribute(attribute);
            return blank(result) ? null : result.replaceAll("\\s+", " ").trim();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static Node node(XPath xpath, Object context, String expression) {
        try { return (Node) xpath.evaluate(expression, context, XPathConstants.NODE); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static NodeList nodes(XPath xpath, Object context, String expression) {
        try { return (NodeList) xpath.evaluate(expression, context, XPathConstants.NODESET); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static boolean contains(XPath xpath, Object context, String expression) {
        try { return (boolean) xpath.evaluate("boolean(" + expression + ")", context, XPathConstants.BOOLEAN); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static String target(String href) {
        if (blank(href)) return null;
        return href.split("#", 2)[0].replaceFirst("/!main$", "");
    }
    private static boolean malformed(String href) { return href != null && href.matches(".*?/act/[^/]*/{2,}.*"); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static int count(List<Issue> issues, String severity) {
        return (int) issues.stream().filter(issue -> severity.equals(issue.severity())).count();
    }
    private static void checkDate(List<Issue> issues, String name, String value) {
        if (blank(value)) return;
        try { LocalDate.parse(value); }
        catch (Exception exception) { issues.add(new Issue("ERROR", "DATE_INVALID", name + " is invalid: " + value, "")); }
    }
    private static FileResult rejected(Path file, String code, String message) {
        return new FileResult(file.toString(), null, "REJECTED", false, null,
                new Counts(0, 0, 0, 0, 1), List.of(), List.of(new Issue("ERROR", code, message, "")));
    }
    private static void print(Report report, Path output) {
        System.out.printf("%nAkoma Ntoso validation: %d checked, %d queryable, %d rejected%n",
                report.filesChecked(), report.queryable(), report.rejected());
        for (FileResult result : report.results()) {
            System.out.printf("[%s] %s%n", result.status(), Path.of(result.file()).getFileName());
            if (result.act() != null) System.out.printf("  %s %s, %s; refs=%d, relations=%d%n",
                    result.act().type(), result.act().number(), result.act().documentDate(),
                    result.counts().references(), result.counts().verifiedRelations());
            result.relations().forEach(relation -> System.out.printf("  VERIFIED %s -> %s%n", relation.type(), relation.target()));
            result.issues().stream().filter(issue -> !"INFO".equals(issue.severity()))
                    .forEach(issue -> System.out.printf("  %s %s: %s%n", issue.severity(), issue.code(), issue.message()));
        }
        System.out.println("\nJSON report: " + output);
    }

    private static final class AknNamespaces implements NamespaceContext {
        public String getNamespaceURI(String prefix) { return switch (prefix) { case "akn" -> AKN; case "rdf" -> RDF; default -> XMLConstants.NULL_NS_URI; }; }
        public String getPrefix(String uri) { return null; }
        public java.util.Iterator<String> getPrefixes(String uri) { return java.util.Collections.emptyIterator(); }
    }

    public record Act(String type, String number, String documentDate, String title, String publicationDate,
                      String gazetteNumber, String localId, String workUri, String expressionUri,
                      String expressionDate, String manifestationUri, String eliAlias, String urnAlias) {}
    public record Relation(String status, String type, String source, String target, String evidence,
                           String location, String ruleId) {}
    public record Issue(String severity, String code, String message, String location) {}
    public record Counts(int references, int uniqueReferenceTargets, int verifiedRelations, int warnings, int errors) {}
    public record FileResult(String file, String sha256, String status, boolean queryable, Act act,
                             Counts counts, List<Relation> relations, List<Issue> issues) {}
    public record Report(String generatedAt, String input, int filesChecked, int queryable, int rejected,
                         int verifiedRelations, List<FileResult> results) {}
}
