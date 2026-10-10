package it.legislation.relations;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads the relation evidence in one Normattiva Akoma Ntoso file.
 *
 * <ul>
 *   <li>{@code analysis/activeModifications/textualMod}: changes this act makes
 *       to other acts (destination URN and the editor's note);</li>
 *   <li>Article 1, paragraph 1: "Il decreto-legge … è convertito in legge",
 *       with a {@code ref} to the decree;</li>
 *   <li>{@code lifecycle}: the acts that changed this act ({@code passiveRef}).</li>
 * </ul>
 */
public class AknRelationReader {

    static final String AKN = "http://docs.oasis-open.org/legaldocml/ns/akn/3.0";
    private static final Pattern CONVERTED = Pattern.compile("convertit[oi]\\s+in\\s+legge", Pattern.CASE_INSENSITIVE);

    /** One {@code textualMod}. */
    public record TextualMod(String eId, String destinationHref, ActKey destination, String note) {}

    /** Everything relation-related in one file. */
    public record Evidence(List<TextualMod> textualMods, Set<ActKey> article1Converts, Set<ActKey> lifecycleChangedBy) {}

    public Evidence read(Path file) throws IOException {
        Document document;
        try (InputStream in = Files.newInputStream(file)) {
            document = factory().newDocumentBuilder().parse(in);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("XML not readable: " + file + ": " + e.getMessage(), e);
        }
        Element root = document.getDocumentElement();
        return new Evidence(textualMods(root), article1Converts(root), lifecycle(root));
    }

    private static List<TextualMod> textualMods(Element root) {
        List<TextualMod> mods = new ArrayList<>();
        Element active = first(root, "activeModifications");
        if (active == null) {
            return mods;
        }
        NodeList list = active.getElementsByTagNameNS(AKN, "textualMod");
        for (int i = 0; i < list.getLength(); i++) {
            Element mod = (Element) list.item(i);
            Element destination = first(mod, "destination");
            String href = destination == null ? null : destination.getAttribute("href");
            Element newElement = first(mod, "new");
            String note = newElement == null ? null : newElement.getTextContent().replaceAll("\\s+", " ").trim();
            mods.add(new TextualMod(mod.getAttribute("eId"), href,
                    ActKey.fromUrn(href).orElse(null), note));
        }
        return mods;
    }

    /** Decrees that the first paragraph of Article 1 declares "convertito in legge". */
    private static Set<ActKey> article1Converts(Element root) {
        Set<ActKey> decrees = new LinkedHashSet<>();
        Element body = first(root, "body");
        Element article = body == null ? null : first(body, "article");
        Element paragraph = article == null ? null : first(article, "paragraph");
        if (paragraph == null || !CONVERTED.matcher(paragraph.getTextContent()).find()) {
            return decrees;
        }
        NodeList refs = paragraph.getElementsByTagNameNS(AKN, "ref");
        for (int i = 0; i < refs.getLength(); i++) {
            ActKey.fromAknHref(((Element) refs.item(i)).getAttribute("href"))
                    .filter(k -> "DECRETO-LEGGE".equals(k.typeCode()))
                    .ifPresent(decrees::add);
        }
        return decrees;
    }

    /** Acts named by the {@code passiveRef}s of the lifecycle events. */
    private static Set<ActKey> lifecycle(Element root) {
        Set<ActKey> acts = new LinkedHashSet<>();
        Element references = first(root, "references");
        if (references == null) {
            return acts;
        }
        Map<String, String> passive = new HashMap<>();
        NodeList refs = references.getElementsByTagNameNS(AKN, "passiveRef");
        for (int i = 0; i < refs.getLength(); i++) {
            Element ref = (Element) refs.item(i);
            passive.put(ref.getAttribute("eId"), ref.getAttribute("href"));
        }
        NodeList events = root.getElementsByTagNameNS(AKN, "eventRef");
        for (int i = 0; i < events.getLength(); i++) {
            String href = passive.get(((Element) events.item(i)).getAttribute("source"));
            ActKey.fromAknHref(href).ifPresent(acts::add);
        }
        return acts;
    }

    private static Element first(Element scope, String localName) {
        NodeList nodes = scope.getElementsByTagNameNS(AKN, localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static DocumentBuilderFactory factory() throws Exception {
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
}
