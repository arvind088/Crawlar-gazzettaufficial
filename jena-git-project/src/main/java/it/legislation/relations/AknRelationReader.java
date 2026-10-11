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
import java.util.regex.Matcher;
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

    /**
     * One {@code textualMod}. Two formats exist in Normattiva files:
     * <ul>
     *   <li>older: destination is a URN ({@code urn:nir:…;18#2}), {@code type} is
     *       always "insertion", and the kind of change is in the editor's note;</li>
     *   <li>newer (from 2022): destination is an AKN reference
     *       ({@code /akn/it/act/decreto-legge/stato/2021-05-31/77/ita@…/!main/~art_14}),
     *       {@code type} is meaningful (substitution, insertion, repeal, split,
     *       join) and there is no note.</li>
     * </ul>
     *
     * @param wholeAct the destination names the act, not one of its partitions
     */
    public record TextualMod(String eId, String type, String destinationHref, ActKey destination,
                             boolean wholeAct, String note) {}

    /** Everything relation-related in one file. */
    public record Evidence(List<TextualMod> textualMods, Set<ActKey> article1Converts, Set<ActKey> lifecycleChangedBy,
                           Set<ActKey> textRepeals) {

        public Evidence(List<TextualMod> textualMods, Set<ActKey> article1Converts, Set<ActKey> lifecycleChangedBy) {
            this(textualMods, article1Converts, lifecycleChangedBy, Set.of());
        }
    }

    /**
     * "Il decreto-legge 11 novembre 2021, n. 157, e' abrogato." / "I decreti-legge 2 marzo 2020, n. 9, … sono abrogati."
     * The subject must be the act itself (no article, paragraph or letter before the date).
     */
    private static final Pattern WHOLE_REPEAL = Pattern.compile(
            "(?:^|[.;:]\\s+)(?:Il|I|La|Le)\\s+(decret[oi][- ]legge|decreti[- ]legge|decret[oi] legislativ[oi]|legg[ei])\\s+"
                    + "((?:(?!articol|comma|letter|allegat|[.;]\\s)[^:])*?)\\s*,?\\s+(?:e'|è|sono)\\s+abrogat[oi]\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_NUMBER = Pattern.compile(
            "(\\d{1,2})\\s+([a-z]+)\\s+(\\d{4}),?\\s+n\\.\\s?(\\d+)", Pattern.CASE_INSENSITIVE);

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
        return new Evidence(textualMods(root), article1Converts(root), lifecycle(root), textRepeals(root));
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
            String href = destination == null ? null : destination.getAttribute("href").trim();
            Element newElement = first(mod, "new");
            String note = newElement == null ? null : newElement.getTextContent().replaceAll("\\s+", " ").trim();
            ActKey key = ActKey.fromUrn(href).or(() -> ActKey.fromAknHref(href)).orElse(null);
            mods.add(new TextualMod(mod.getAttribute("eId"), mod.getAttribute("type"), href, key,
                    wholeAct(href), note == null || note.isEmpty() ? null : note));
        }
        return mods;
    }

    /** Decrees that the first paragraph of Article 1 declares "convertito in legge". */
    private static Set<ActKey> article1Converts(Element root) {
        Set<ActKey> decrees = new LinkedHashSet<>();
        Element body = first(root, "body");
        Element article = body == null ? null : first(body, "article");
        Element paragraph = article == null ? null : first(article, "paragraph");
        if (paragraph == null || !CONVERTED.matcher(ownText(paragraph)).find()) {
            return decrees;
        }
        NodeList refs = paragraph.getElementsByTagNameNS(AKN, "ref");
        for (int i = 0; i < refs.getLength(); i++) {
            ActKey.fromAknHref(((Element) refs.item(i)).getAttribute("href"))
                    .filter(k -> "DECRETO-LEGGE".equals(k.typeCode()))
                    .ifPresent(decrees::add);
        }
        // Many files have no <ref>: read "decreto-legge 3l luglio 2020, n. 86" from the text.
        String text = ownText(paragraph);
        if (text.toLowerCase(java.util.Locale.ROOT).matches("(?s).*decret[oi][- ]legge.*")) {
            decrees.addAll(names(text, "DECRETO-LEGGE"));
        }
        return decrees;
    }

    /** Acts this act repeals in full, read from sentences of its text (some acts have no textualMod at all). */
    private static Set<ActKey> textRepeals(Element root) {
        Set<ActKey> acts = new LinkedHashSet<>();
        Element body = first(root, "body");
        if (body == null) {
            return acts;
        }
        NodeList paragraphs = body.getElementsByTagNameNS(AKN, "p");
        for (int i = 0; i < paragraphs.getLength(); i++) {
            if (insideQuoteOrNote(paragraphs.item(i))) {
                continue;
            }
            String text = ownText(paragraphs.item(i));
            if (!text.contains("abrogat")) {
                continue;
            }
            // "n. 157" must not end the sentence: write it "n.~157" while matching.
            Matcher m = WHOLE_REPEAL.matcher(text.replaceAll("\\bn\\.\\s", "n.~"));
            while (m.find()) {
                String word = m.group(1).toLowerCase(java.util.Locale.ROOT);
                String type = word.contains("legge") && word.startsWith("decret") ? "DECRETO-LEGGE"
                        : word.contains("legislativ") ? "DECRETO_LEGISLATIVO" : "LEGGE";
                acts.addAll(names(m.group(2).replace("n.~", "n. "), type));
            }
        }
        return acts;
    }

    /** Elements whose text is not this act speaking: editorial notes and quotations of other acts. */
    private static final java.util.Set<String> NOT_OWN_TEXT = java.util.Set.of(
            "authorialNote", "quotedStructure", "quotedText", "mod", "embeddedStructure", "embeddedText");

    private static boolean insideQuoteOrNote(org.w3c.dom.Node node) {
        for (org.w3c.dom.Node n = node.getParentNode(); n != null; n = n.getParentNode()) {
            if (n instanceof Element e && NOT_OWN_TEXT.contains(e.getLocalName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The text the act itself states: without editorial notes and quoted text, and without passages
     * in «guillemets» (the notes of D.Lgs. 44/2020 quote L. 27/2020: «… I decreti-legge … sono abrogati»).
     */
    static String ownText(org.w3c.dom.Node node) {
        StringBuilder sb = new StringBuilder();
        collect(node, sb);
        return clean(sb.toString().replaceAll("«[^»]*»", " "));
    }

    private static void collect(org.w3c.dom.Node node, StringBuilder sb) {
        for (org.w3c.dom.Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element e) {
                if (!NOT_OWN_TEXT.contains(e.getLocalName())) {
                    collect(e, sb);
                }
            } else if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE) {
                sb.append(child.getNodeValue());
            }
        }
    }

    /** "17 marzo 2020, n. 18" (also the typo "3l luglio") as keys of the given type. */
    static List<ActKey> names(String text, String type) {
        List<ActKey> keys = new ArrayList<>();
        Matcher m = DATE_NUMBER.matcher(text);
        while (m.find()) {
            ModificationNote.namedAct("D.L. " + m.group())
                    .map(k -> new ActKey(type, k.date(), k.number()))
                    .ifPresent(keys::add);
        }
        return keys;
    }

    /** Collapses white space and repairs "3l" (letter l for digit 1) in day numbers. */
    static String clean(String text) {
        return text.replaceAll("[\\s\\u00a0\\u2000-\\u200b\\u202f]+", " ").replaceAll("\\b(\\d)l\\b", "$11").trim();
    }

    /** True when an href names the act rather than one of its partitions. */
    static boolean wholeAct(String href) {
        if (href == null) {
            return false;
        }
        if (href.startsWith("/akn/")) {
            return !href.contains("~");
        }
        int hash = href.indexOf('#');
        return hash < 0 || hash == href.length() - 1;
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
