package it.legislation.eli;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import it.legislation.web.Html;
import it.legislation.web.I18n;

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
            Map.entry("http://example.org/italian-legislation/ontology#converts", "Converts into law"),
            Map.entry("http://data.europa.eu/eli/ontology#amends", "Amends"),
            Map.entry("http://data.europa.eu/eli/ontology#repeals", "Repeals"),
            Map.entry("http://data.europa.eu/eli/ontology#changes", "Changes"),
            Map.entry("http://example.org/italian-legislation/ontology#modifies", "Modifies"),
            Map.entry("http://example.org/italian-legislation/ontology#modifiedBy", "Modified by")
    );

    /** The same labels in Italian; a predicate missing here keeps its English label. */
    private static final Map<String, String> PREDICATE_LABELS_IT = Map.ofEntries(
            Map.entry("http://data.europa.eu/eli/ontology#is_realized_by", "Versioni"),
            Map.entry("http://data.europa.eu/eli/ontology#realizes", "Versione di"),
            Map.entry("http://data.europa.eu/eli/ontology#is_embodied_by", "Formati"),
            Map.entry("http://data.europa.eu/eli/ontology#is_embodied_in", "Formato di"),
            Map.entry("http://data.europa.eu/eli/ontology#date_publication", "Pubblicato"),
            Map.entry("http://data.europa.eu/eli/ontology#in_force", "In vigore"),
            Map.entry("http://data.europa.eu/eli/ontology#first_date_entry_in_force", "In vigore dal"),
            Map.entry("http://data.europa.eu/eli/ontology#date_no_longer_in_force", "In vigore fino al"),
            Map.entry("http://data.europa.eu/eli/ontology#date_document", "Data dell'atto"),
            Map.entry("http://data.europa.eu/eli/ontology#type_document", "Tipo di atto"),
            Map.entry("http://data.europa.eu/eli/ontology#id_local", "Identificativo locale"),
            Map.entry("http://data.europa.eu/eli/ontology#title", "Titolo"),
            Map.entry("http://data.europa.eu/eli/ontology#version", "Versione"),
            Map.entry("http://data.europa.eu/eli/ontology#version_date", "Data della versione"),
            Map.entry("http://data.europa.eu/eli/ontology#language", "Lingua"),
            Map.entry("http://data.europa.eu/eli/ontology#format", "Formato"),
            Map.entry("http://data.europa.eu/eli/ontology#publisher", "Editore"),
            Map.entry("http://www.w3.org/2000/01/rdf-schema#label", "Etichetta"),
            Map.entry("http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "Classe RDF"),
            Map.entry("http://purl.org/dc/terms/source", "Fonte"),
            Map.entry("http://www.w3.org/2002/07/owl#sameAs", "Coincide con"),
            Map.entry("http://example.org/italian-legislation/ontology#converts", "Converte in legge"),
            Map.entry("http://data.europa.eu/eli/ontology#amends", "Modifica"),
            Map.entry("http://data.europa.eu/eli/ontology#repeals", "Abroga"),
            Map.entry("http://data.europa.eu/eli/ontology#changes", "Incide su")
    );

    private final EliUriService uriService;

    public EliHtmlRenderer(EliUriService uriService) {
        this.uriService = uriService;
    }

    public String render(ResolvedResource resource) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(escape(resource.label())).append("</h1>\n")
                .append("<p class=\"mono muted\">").append(escape(resource.publishedUri())).append("</p>\n")
                .append("<p><a href=\"").append(escape(resource.path())).append("?format=ttl\">")
                .append(escape(I18n.t("resource.turtle"))).append("</a>");
        String act = actPath(resource.path());
        if (act != null) {
            body.append(" &middot; <a href=\"").append(escape(act)).append("\">").append(escape(I18n.t("resource.act")))
                    .append("</a>");
        }
        body.append("</p>\n");
        body.append(section(I18n.t("resource.properties"), resource.outgoing(), false));
        body.append(section(I18n.t("resource.inbound"), resource.incoming(), true));
        return Html.page(resource.label(), "acts",
                "<link rel=\"alternate\" type=\"text/turtle\" href=\"" + escape(resource.path()) + "?format=ttl\">\n"
                        + "<style>" + styles() + "</style>\n",
                body.toString());
    }

    public String renderNotFound(String path) {
        return Html.page(I18n.t("notfound.title"), "acts", null,
                "<h1>" + escape(I18n.t("notfound.title")) + "</h1>\n<p class=\"mono muted\">"
                        + escape(uriService.uriForPath(path)) + "</p>\n<p>" + escape(I18n.t("notfound.text"))
                        + "</p>\n<p><a href=\"/\">" + escape(I18n.t("notfound.search")) + "</a></p>\n");
    }

    /** The act a version or format belongs to: the first seven segments of its ELI path. */
    private static String actPath(String path) {
        String[] segments = path.split("/");
        if (segments.length <= 8) {
            return null;
        }
        return String.join("/", java.util.Arrays.copyOf(segments, 8));
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
        String known = I18n.italian() && PREDICATE_LABELS_IT.containsKey(predicate)
                ? PREDICATE_LABELS_IT.get(predicate) : PREDICATE_LABELS.get(predicate);
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
        return Html.escape(value);
    }

    private String styles() {
        return """
                section{background:#fff;border-radius:8px;box-shadow:var(--shadow);margin:0;padding:6px 22px 16px}
                section h2{margin:14px 0 4px}
                section dl{display:grid;grid-template-columns:230px minmax(0,1fr);margin:0}
                section dt,section dd{border-top:1px solid var(--line-soft);margin:0;padding:10px 0}
                section dt{padding-right:16px}
                .label{display:block;font-weight:700}
                .predicate,.full-uri{color:var(--slate);font-family:var(--mono);font-size:12.5px}
                .full-uri{display:block;word-break:break-all}
                section dd ul{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:8px}
                a.resource{font-weight:700;text-decoration:none}
                @media(max-width:640px){section dl{grid-template-columns:1fr}section dd{border-top:0;padding-top:0}}
                """;
    }
}
