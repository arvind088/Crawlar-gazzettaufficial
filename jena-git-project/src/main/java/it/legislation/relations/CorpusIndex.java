package it.legislation.relations;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.RDF;

/**
 * The acts already imported (ELI Works in {@code normattiva_akn.ttl}), with the
 * files of their original and latest versions, and an index from citation key
 * (type, date, number) to act.
 */
public class CorpusIndex {

    static final String ELI = "http://data.europa.eu/eli/ontology#";

    /** One imported act. File paths are relative to the raw Normattiva folder. */
    public record Act(String workUri, String codice, LocalDate publicationDate, ActKey key, String title,
                      String originalFile, String latestFile) {}

    private final Map<String, Act> byCodice = new LinkedHashMap<>();
    private final Map<ActKey, List<Act>> byKey = new HashMap<>();
    private final String baseUri;

    CorpusIndex(Collection<Act> acts, String baseUri) {
        for (Act act : acts) {
            byCodice.put(act.codice(), act);
            byKey.computeIfAbsent(act.key(), k -> new ArrayList<>()).add(act);
        }
        this.baseUri = baseUri;
    }

    public static CorpusIndex read(Path turtle) throws IOException {
        Model model = ModelFactory.createDefaultModel();
        try (InputStream in = Files.newInputStream(turtle)) {
            RDFDataMgr.read(model, in, Lang.TURTLE);
        }
        Property idLocal = model.createProperty(ELI, "id_local");
        Property datePublication = model.createProperty(ELI, "date_publication");
        Property dateDocument = model.createProperty(ELI, "date_document");
        Property typeDocument = model.createProperty(ELI, "type_document");
        Property number = model.createProperty(ELI, "number");
        Property title = model.createProperty(ELI, "title");
        Property realizedBy = model.createProperty(ELI, "is_realized_by");
        Property embodiedBy = model.createProperty(ELI, "is_embodied_by");
        Property versionDate = model.createProperty(ELI, "version_date");

        List<Act> acts = new ArrayList<>();
        String base = null;
        for (Resource work : model.listSubjectsWithProperty(RDF.type, model.createResource(ELI + "LegalResource")).toList()) {
            if (work.getProperty(idLocal) == null || work.getProperty(typeDocument) == null
                    || work.getProperty(dateDocument) == null || work.getProperty(number) == null) {
                continue;
            }
            String type = work.getPropertyResourceValue(typeDocument).getURI();
            ActKey key = new ActKey(type.substring(type.indexOf('#') + 1),
                    LocalDate.parse(work.getProperty(dateDocument).getString()),
                    it.legislation.mapping.AknActReader.actNumber(work.getProperty(number).getString()));
            String originalFile = null;
            String latestFile = null;
            LocalDate latest = null;
            for (Statement s : work.listProperties(realizedBy).toList()) {
                Resource expression = s.getResource();
                Resource format = expression.getPropertyResourceValue(embodiedBy);
                String file = format == null || format.getProperty(DCTerms.source) == null ? null
                        : format.getProperty(DCTerms.source).getString();
                if (file == null) {
                    continue;
                }
                if (expression.getURI().endsWith("/ita/original")) {
                    originalFile = file;
                }
                LocalDate date = expression.getProperty(versionDate) == null ? null
                        : LocalDate.parse(expression.getProperty(versionDate).getString());
                if (date != null && (latest == null || date.isAfter(latest))) {
                    latest = date;
                    latestFile = file;
                }
            }
            if (base == null && work.getURI().contains("/eli/id/")) {
                base = work.getURI().substring(0, work.getURI().indexOf("/eli/id/"));
            }
            acts.add(new Act(work.getURI(), work.getProperty(idLocal).getString(),
                    LocalDate.parse(work.getProperty(datePublication).getString()), key,
                    work.getProperty(title) == null ? "" : work.getProperty(title).getString(),
                    originalFile, latestFile == null ? originalFile : latestFile));
        }
        acts.sort(Comparator.comparing(Act::publicationDate).thenComparing(Act::codice));
        return new CorpusIndex(acts, base == null ? "https://example.org" : base);
    }

    public Collection<Act> acts() {
        return byCodice.values();
    }

    /** The act a citation points to, only when exactly one imported act has that key. */
    public Optional<Act> resolve(ActKey key) {
        List<Act> acts = key == null ? null : byKey.get(key);
        return acts != null && acts.size() == 1 ? Optional.of(acts.get(0)) : Optional.empty();
    }

    /** How many imported acts share this key (C4 requires exactly one). */
    public int count(ActKey key) {
        List<Act> acts = byKey.get(key);
        return acts == null ? 0 : acts.size();
    }

    /** Base URI of the imported Works, used to mint URIs for relations and acts outside the corpus. */
    public String baseUri() {
        return baseUri;
    }
}
