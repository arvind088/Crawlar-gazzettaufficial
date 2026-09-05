package it.legislation.eli;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import it.legislation.eli.EliResolutionService.RelatedValue;
import it.legislation.eli.EliResolutionService.RelationGroup;
import it.legislation.eli.EliResolutionService.ResolvedResource;

/**
 * Renders a resolved resource as a self-contained HTML page.
 *
 * <p>The rendering is generic on purpose. Every object that is an IRI becomes a
 * link, whatever its predicate; the maps below only affect the wording of a
 * label, never whether something is rendered or navigable. Adding a new
 * predicate to the data requires no change in this class.
 */
@Component
public class EliHtmlRenderer {

    private static final Map<String, String> PREDICATE_LABELS = Map.ofEntries(
            Map.entry("http://data.europa.eu/eli/ontology#is_realized_by", "Versions"),
            Map.entry("http://data.europa.eu/eli/ontology#realizes", "Version of"),
            Map.entry("http://data.europa.eu/eli/ontology#is_embodied_by", "Formats"),
            Map.entry("http://data.europa.eu/eli/ontology#is_embodied_in", "Format of"),
            Map.entry("http://data.europa.eu/eli/ontology#commences", "Commences / converts"),
            Map.entry("http://data.europa.eu/eli/ontology#commenced_by", "Commenced / converted by"),
            Map.entry("http://data.europa.eu/eli/ontology#date_publication", "Published"),
            Map.entry("http://data.europa.eu/eli/ontology#in_force", "In force"),
            Map.entry("http://data.europa.eu/eli/ontology#first_date_entry_in_force", "In force from"),
            Map.entry("http://data.europa.eu/eli/ontology#date_no_longer_in_force", "In force until"),
            Map.entry("http://data.europa.eu/eli/ontology#date_document", "Document date"),
            Map.entry("http://data.europa.eu/eli/ontology#type_document", "Document type"),
            Map.entry("http://data.europa.eu/eli/ontology#id_local", "Local identifier"),
            Map.entry("http://data.europa.eu/eli/ontology#title", "Title"),
            Map.entry("http://data.europa.eu/eli/ontology#version", "Version"),
            Map.entry("http://data.europa.eu/eli/ontology#language", "Language"),
            Map.entry("http://data.europa.eu/eli/ontology#format", "Format"),
            Map.entry("http://data.europa.eu/eli/ontology#publisher", "Publisher"),
            Map.entry("http://www.w3.org/2000/01/rdf-schema#label", "Label"),
            Map.entry("http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "RDF class"),
            Map.entry("http://purl.org/dc/terms/source", "Source record"),
            Map.entry("http://www.w3.org/2002/07/owl#sameAs", "Same as"),
            Map.entry("http://example.org/italian-legislation/ontology#modifies", "Modifies"),
            Map.entry("http://example.org/italian-legislation/ontology#modifiedBy", "Modified by")
    );

    private final EliUriService uriService;

    public EliHtmlRenderer(EliUriService uriService) {
        this.uriService = uriService;
    }

    public String render(ResolvedResource resource) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(escape(resource.label())).append(" &middot; Legal RDF Explorer</title>\n")
                .append("<link rel=\"alternate\" type=\"text/turtle\" href=\"")
                .append(escape(resource.path())).append("?format=ttl\">\n")
                .append("<style>").append(styles()).append("</style>\n")
                .append("</head>\n<body>\n");

        html.append("<header class=\"page-head\">\n")
                .append("<a class=\"back\" href=\"/\">&larr; Legal RDF Explorer</a>\n")
                .append("<h1>").append(escape(resource.label())).append("</h1>\n")
                .append("<p class=\"uri\">").append(escape(resource.publishedUri())).append("</p>\n")
                .append("<p class=\"actions\">")
                .append("<a href=\"").append(escape(resource.path())).append("?format=ttl\">View as Turtle</a>");
        if (!resource.storedUri().equals(resource.publishedUri())) {
            html.append(" <a href=\"").append(escape(resource.storedUri()))
                    .append("\" rel=\"noreferrer\">View source record</a>");
        }
        html.append("</p>\n</header>\n<main>\n");

        html.append(section("Properties and links", resource.outgoing(), false));
        html.append(section("Referred to by", resource.incoming(), true));

        html.append("</main>\n<footer><p>Rendered live from a SPARQL query against the triple store.</p></footer>\n")
                .append("</body>\n</html>\n");
        return html.toString();
    }

    public String renderNotFound(String path) {
        return "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>Act not found &middot; Legal RDF Explorer</title>\n"
                + "<style>" + styles() + "</style>\n</head>\n<body>\n"
                + "<header class=\"page-head\">\n"
                + "<a class=\"back\" href=\"/\">&larr; Legal RDF Explorer</a>\n"
                + "<h1>No act at this address</h1>\n"
                + "<p class=\"uri\">" + escape(uriService.uriForPath(path)) + "</p>\n"
                + "<p>The triple store holds no resource with this ELI identifier. It may not have "
                + "been crawled yet, or the identifier may be mistyped.</p>\n"
                + "<p class=\"actions\"><a href=\"/\">Search the acts that are loaded</a></p>\n"
                + "</header>\n</body>\n</html>\n";
    }

    private String section(String heading, List<RelationGroup> groups, boolean inbound) {
        if (groups.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<section>\n<h2>")
                .append(escape(heading)).append("</h2>\n<dl>\n");
        for (RelationGroup group : groups) {
            html.append("<dt><span class=\"label\">").append(escape(label(group.predicate())))
                    .append("</span><span class=\"predicate\">").append(escape(compact(group.predicate())))
                    .append("</span></dt>\n<dd><ul>\n");
            for (RelatedValue value : group.values()) {
                html.append("<li>").append(renderValue(value, inbound)).append("</li>\n");
            }
            html.append("</ul></dd>\n");
        }
        return html.append("</dl>\n</section>\n").toString();
    }

    /**
     * The single generic rule: an IRI is always a link. If we can host it, the
     * link points at our own resolution route; otherwise it points at the IRI
     * itself. No predicate is inspected to make this decision.
     */
    private String renderValue(RelatedValue value, boolean inbound) {
        if (!value.iri()) {
            return "<span class=\"literal\">" + escape(value.value()) + "</span>";
        }
        String target = uriService.pathOf(value.value())
                .orElse(value.value());
        String text = linkText(value.value());
        String rel = target.startsWith("/") ? "" : " rel=\"noreferrer\"";
        return "<a class=\"resource" + (inbound ? " inbound" : "") + "\" href=\"" + escape(target) + "\""
                + rel + ">" + escape(text) + "</a>"
                + "<span class=\"full-uri\">" + escape(value.value()) + "</span>";
    }

    /**
     * Link text for an IRI. Two expressions of the same act share a local id,
     * so the segments that distinguish them are appended; without this every
     * version of an act would render as the same word.
     */
    private String linkText(String uri) {
        return uriService.pathOf(uri)
                .map(path -> {
                    String[] segments = path.split("/");
                    // /eli/id/{y}/{m}/{d}/{naturalId}/{type}[/...]
                    if (segments.length <= 8) {
                        return segments[segments.length - 2];
                    }
                    String localId = segments[6];
                    StringBuilder tail = new StringBuilder();
                    for (int index = 8; index < segments.length; index++) {
                        if (tail.length() > 0) {
                            tail.append(' ');
                        }
                        tail.append(segments[index]);
                    }
                    return localId + " \u00b7 " + tail;
                })
                .orElseGet(() -> compact(uri));
    }

    private String label(String predicate) {
        String known = PREDICATE_LABELS.get(predicate);
        if (known != null) {
            return known;
        }
        // Unknown predicates still render, with a readable name derived from the IRI.
        String local = predicate.contains("#")
                ? predicate.substring(predicate.lastIndexOf('#') + 1)
                : predicate.substring(predicate.lastIndexOf('/') + 1);
        String spaced = local.replace('_', ' ').replaceAll("([a-z\\d])([A-Z])", "$1 $2").trim();
        if (spaced.isEmpty()) {
            return predicate;
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1).toLowerCase();
    }

    private String compact(String uri) {
        Map<String, String> prefixes = Map.of(
                "http://data.europa.eu/eli/ontology#", "eli:",
                "http://purl.org/dc/terms/", "dcterms:",
                "http://www.w3.org/2000/01/rdf-schema#", "rdfs:",
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:",
                "http://www.w3.org/2002/07/owl#", "owl:",
                "http://schema.org/", "schema:",
                "http://example.org/italian-legislation/ontology#", "ilg:"
        );
        for (Map.Entry<String, String> entry : prefixes.entrySet()) {
            if (uri.startsWith(entry.getKey())) {
                return entry.getValue() + uri.substring(entry.getKey().length());
            }
        }
        int hash = uri.lastIndexOf('#');
        int slash = uri.lastIndexOf('/');
        int cut = Math.max(hash, slash);
        return cut > 0 && cut < uri.length() - 1 ? uri.substring(cut + 1) : uri;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String styles() {
        return """
                :root{--bg:#eef4f3;--surface:#fff;--text:#102033;--muted:#667586;
                --line:#dfe9e8;--accent:#008778;--accent-dark:#006c61}
                *{box-sizing:border-box}
                body{margin:0;background:var(--bg);color:var(--text);
                font-family:Arial,Helvetica,sans-serif;line-height:1.6}
                .page-head,main,footer{max-width:860px;margin:0 auto;padding:0 24px}
                .page-head{padding-top:40px}
                .back{color:var(--accent-dark);font-size:14px;text-decoration:none}
                .back:hover{text-decoration:underline}
                h1{font-size:28px;line-height:1.25;margin:14px 0 8px}
                .uri{color:var(--muted);font-family:ui-monospace,Menlo,monospace;
                font-size:13px;margin:0 0 14px;word-break:break-all}
                .actions a{color:var(--accent-dark);font-size:14px;margin-right:16px}
                section{background:var(--surface);border:1px solid var(--line);
                border-radius:14px;margin:24px 0;padding:8px 24px 20px}
                h2{font-size:17px;margin:20px 0 4px}
                dl{display:grid;grid-template-columns:230px minmax(0,1fr);gap:0;margin:0}
                dt,dd{border-top:1px solid var(--line);margin:0;padding:12px 0}
                dt{padding-right:16px}
                .label{display:block;font-size:14px;font-weight:700}
                .predicate{color:var(--muted);font-family:ui-monospace,Menlo,monospace;font-size:12px}
                dd ul{list-style:none;margin:0;padding:0;display:flex;
                flex-direction:column;gap:10px}
                a.resource{color:var(--accent-dark);font-weight:700;text-decoration:none}
                a.resource:hover{text-decoration:underline}
                .full-uri{color:var(--muted);display:block;
                font-family:ui-monospace,Menlo,monospace;font-size:11.5px;word-break:break-all}
                .literal{word-break:break-word}
                footer{color:var(--muted);font-size:13px;padding-bottom:48px}
                a:focus-visible,.resource:focus-visible{outline:2px solid var(--accent-dark);
                outline-offset:2px}
                @media(max-width:620px){dl{grid-template-columns:1fr}
                dd{border-top:0;padding-top:0}h1{font-size:23px}}
                """;
    }
}
