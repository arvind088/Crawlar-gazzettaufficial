package it.legislation.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PreDestroy;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.tdb2.TDB2Factory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The persistent triple store, and the sole source of truth for the platform.
 *
 * <p>Design note (FR-2.1, FR-1.4, FR-2.3). An earlier version of this class
 * treated TDB2 as a mirror of the Turtle files: it cleared every named graph and
 * re-parsed the files on <em>every read request</em>, inside a write
 * transaction. That had three consequences worth recording, because they are the
 * reasons for the present design:
 *
 * <ol>
 *   <li>The store was not the record. Removing the {@code .ttl} files emptied the
 *       graph, so persistence was a property of the filesystem, not of TDB2.</li>
 *   <li>The update strategy was destructive, contradicting the requirement that
 *       ingestion be additive.</li>
 *   <li>Every read serialised through a write transaction and re-parsed the whole
 *       corpus, which cannot scale past a demo dataset.</li>
 * </ol>
 *
 * <p>Now the Turtle files are treated as a <em>bootstrap</em> source, not as the
 * record. A file is read once, into the store; after that the store owns the
 * data. Reads take a read transaction and touch no files. Because an RDF graph
 * is a set, re-reading a changed file adds only its new statements and can never
 * remove anything already ingested — additive by construction.
 */
@Service
public class Tdb2DatasetService {

    static final Path GAZZETTA_DELTA = Path.of("data", "rdf", "gazzetta_metadata_delta.ttl");
    static final Path NORMATTIVA_MODIFICATIONS = Path.of("data", "rdf", "normattiva_modifications.ttl");
    static final Path NORMATTIVA_AUTO_MODIFICATIONS = Path.of("data", "rdf", "normattiva_modifications_auto.ttl");
    static final Path NORMATTIVA_MULTIVERSION_SAMPLE = Path.of("data", "rdf", "normattiva_multiversion_sample.ttl");
    static final Path IN_FORCE = Path.of("data", "rdf", "in_force.ttl");
    static final Path SEED_ACTS = Path.of("data", "rdf", "seed_acts.ttl");

    private static final String GRAPH_BASE = "http://example.org/italian-legislation/graph/";
    private static final String PROVENANCE_GRAPH = GRAPH_BASE + "provenance";
    private static final String ILG_NS = "http://example.org/italian-legislation/ontology#";

    private final Dataset dataset;
    private final List<RdfGraphSource> rdfSources;

    /** Marker (file mtime) of each source at the time it was last ingested. */
    private final Map<Path, Long> loadedMarkers = new ConcurrentHashMap<>();

    private volatile boolean bootstrapped;
    private volatile LoadStatus lastStatus = new LoadStatus(0L, List.of(), List.of());

    @Autowired
    public Tdb2DatasetService(@Value("${legal.tdb2.path:data/tdb2}") String datasetPath) throws IOException {
        this(Path.of(datasetPath), defaultSources());
    }

    Tdb2DatasetService(Path datasetPath, List<RdfGraphSource> rdfSources) throws IOException {
        Path normalizedPath = datasetPath.toAbsolutePath().normalize();
        Files.createDirectories(normalizedPath);
        this.dataset = TDB2Factory.connectDataset(normalizedPath.toString());
        this.rdfSources = List.copyOf(rdfSources);
    }

    private Tdb2DatasetService(Dataset dataset, List<RdfGraphSource> rdfSources) {
        this.dataset = dataset;
        this.rdfSources = List.copyOf(rdfSources);
    }

    static Tdb2DatasetService forRdfPaths(Path datasetPath, List<Path> rdfPaths) throws IOException {
        return new Tdb2DatasetService(datasetPath, graphSources(rdfPaths));
    }

    static Tdb2DatasetService inMemoryForRdfPaths(List<Path> rdfPaths) {
        return new Tdb2DatasetService(DatasetFactory.createTxnMem(), graphSources(rdfPaths));
    }

    private static List<RdfGraphSource> graphSources(List<Path> rdfPaths) {
        List<RdfGraphSource> sources = new ArrayList<>();
        for (int index = 0; index < rdfPaths.size(); index++) {
            sources.add(new RdfGraphSource(GRAPH_BASE + "test/source-" + index, rdfPaths.get(index)));
        }
        return sources;
    }

    static List<RdfGraphSource> defaultSources() {
        return List.of(
                new RdfGraphSource(GRAPH_BASE + "gazzetta", GAZZETTA_DELTA),
                new RdfGraphSource(GRAPH_BASE + "normattiva/manual", NORMATTIVA_MODIFICATIONS),
                new RdfGraphSource(GRAPH_BASE + "normattiva/auto", NORMATTIVA_AUTO_MODIFICATIONS),
                new RdfGraphSource(GRAPH_BASE + "normattiva/multiversion-sample", NORMATTIVA_MULTIVERSION_SAMPLE),
                new RdfGraphSource(GRAPH_BASE + "in-force", IN_FORCE),
                new RdfGraphSource(GRAPH_BASE + "seed", SEED_ACTS)
        );
    }

    public LoadStatus status() throws IOException {
        ensureBootstrapped();
        dataset.begin(ReadWrite.READ);
        try {
            return new LoadStatus(
                    dataset.getDefaultModel().size(),
                    lastStatus.loadedFiles(),
                    lastStatus.missingFiles()
            );
        } finally {
            dataset.end();
        }
    }

    /**
     * Runs a reader inside a read transaction. No file is touched and no write
     * transaction is taken once the store has been bootstrapped.
     */
    public <T> T read(DatasetReader<T> reader) throws IOException {
        ensureBootstrapped();
        dataset.begin(ReadWrite.READ);
        try {
            return reader.read(dataset);
        } finally {
            dataset.end();
        }
    }

    /**
     * Adds triples to the store additively (FR-1.4). Existing statements are
     * never removed; because a graph is a set, re-adding a statement is a no-op.
     * This is the entry point ingestion jobs should use to write into the store.
     *
     * @return how many statements the store gained
     */
    public long add(Model triples) {
        if (triples == null || triples.isEmpty()) {
            return 0L;
        }
        dataset.begin(ReadWrite.WRITE);
        try {
            Model target = dataset.getDefaultModel();
            long before = target.size();
            target.add(triples);
            long gained = target.size() - before;
            dataset.commit();
            return gained;
        } finally {
            dataset.end();
        }
    }

    /** Statements currently in the store. */
    public long size() throws IOException {
        ensureBootstrapped();
        dataset.begin(ReadWrite.READ);
        try {
            return dataset.getDefaultModel().size();
        } finally {
            dataset.end();
        }
    }

    @PreDestroy
    public void close() {
        dataset.close();
    }

    // ---------------------------------------------------------------- bootstrap

    private void ensureBootstrapped() throws IOException {
        if (bootstrapped && pendingSources().isEmpty()) {
            return;
        }
        synchronized (this) {
            if (bootstrapped && pendingSources().isEmpty()) {
                return;
            }
            if (!bootstrapped) {
                readProvenance();
            }
            ingestPending();
            bootstrapped = true;
        }
    }

    /**
     * Sources whose file has never been ingested, or has changed since it was.
     * Comparing markers in memory keeps the common case — nothing to do — free
     * of any transaction.
     */
    private List<RdfGraphSource> pendingSources() throws IOException {
        List<RdfGraphSource> pending = new ArrayList<>();
        for (RdfGraphSource source : rdfSources) {
            long marker = marker(source.path());
            if (marker < 0) {
                continue;
            }
            Long known = loadedMarkers.get(source.path());
            if (known == null || known != marker) {
                pending.add(source);
            }
        }
        return pending;
    }

    /**
     * Restores the ingestion markers recorded in the store, so a restart does not
     * re-read files the store already holds. This is what makes TDB2 the record
     * rather than a cache.
     */
    private void readProvenance() {
        dataset.begin(ReadWrite.READ);
        try {
            Model provenance = dataset.getNamedModel(PROVENANCE_GRAPH);
            Property sourcePath = provenance.createProperty(ILG_NS, "sourcePath");
            Property fileMarker = provenance.createProperty(ILG_NS, "fileMarker");

            ResIterator records = provenance.listSubjectsWithProperty(sourcePath);
            while (records.hasNext()) {
                Resource record = records.nextResource();
                String path = record.getProperty(sourcePath).getString();
                if (record.hasProperty(fileMarker)) {
                    loadedMarkers.put(Path.of(path), record.getProperty(fileMarker).getLong());
                }
            }
        } finally {
            dataset.end();
        }
    }

    private void ingestPending() throws IOException {
        List<RdfGraphSource> pending = pendingSources();
        List<String> loadedFiles = new ArrayList<>();
        List<String> missingFiles = new ArrayList<>();

        for (RdfGraphSource source : rdfSources) {
            Path normalized = source.path().toAbsolutePath().normalize();
            if (marker(source.path()) >= 0 || loadedMarkers.containsKey(source.path())) {
                loadedFiles.add(normalized.toString());
            } else {
                missingFiles.add(normalized.toString());
            }
        }

        if (!pending.isEmpty()) {
            Path failedOn = null;
            dataset.begin(ReadWrite.WRITE);
            try {
                Model target = dataset.getDefaultModel();
                Model provenance = dataset.getNamedModel(PROVENANCE_GRAPH);
                for (RdfGraphSource source : pending) {
                    failedOn = source.path();
                    long marker = marker(source.path());
                    try (InputStream inputStream = Files.newInputStream(source.path())) {
                        // Additive: a graph is a set, so statements already held
                        // are unaffected and nothing is ever removed.
                        RDFDataMgr.read(target, inputStream, Lang.TURTLE);
                    }
                    recordProvenance(provenance, source, marker);
                    loadedMarkers.put(source.path(), marker);
                }
                dataset.commit();
            } catch (RuntimeException | IOException failure) {
                // Abort explicitly and name the file. Without this, the abort
                // that end() performs raises "Write transaction with no commit()"
                // from the finally block, which replaces the real cause — so a
                // malformed Turtle file reports as a transaction error and the
                // parser's line number is lost.
                dataset.abort();
                throw new IOException(
                        "Failed to ingest " + failedOn + " into the triple store: "
                                + failure.getMessage(), failure);
            } finally {
                dataset.end();
            }
        }

        dataset.begin(ReadWrite.READ);
        try {
            lastStatus = new LoadStatus(dataset.getDefaultModel().size(), loadedFiles, missingFiles);
        } finally {
            dataset.end();
        }
    }

    private void recordProvenance(Model provenance, RdfGraphSource source, long marker) {
        Property sourcePath = provenance.createProperty(ILG_NS, "sourcePath");
        Property fileMarker = provenance.createProperty(ILG_NS, "fileMarker");
        Property loadedAt = provenance.createProperty(ILG_NS, "loadedAt");

        Resource record = provenance.createResource(source.graphUri());
        provenance.removeAll(record, sourcePath, null);
        provenance.removeAll(record, fileMarker, null);
        provenance.removeAll(record, loadedAt, null);

        record.addProperty(sourcePath, source.path().toString());
        record.addLiteral(fileMarker, marker);
        record.addProperty(loadedAt, OffsetDateTime.now().toString());
    }

    private long marker(Path path) throws IOException {
        if (!Files.exists(path)) {
            return -1L;
        }
        return Files.getLastModifiedTime(path).toMillis();
    }

    /** Per-source ingestion state, for the status view. */
    public Map<String, Long> ingestedSources() {
        Map<String, Long> summary = new LinkedHashMap<>();
        loadedMarkers.forEach((path, marker) -> summary.put(path.toString(), marker));
        return Map.copyOf(summary);
    }

    @FunctionalInterface
    public interface DatasetReader<T> {
        T read(Dataset dataset) throws IOException;
    }

    record RdfGraphSource(String graphUri, Path path) {
    }

    public record LoadStatus(long triples, List<String> loadedFiles, List<String> missingFiles) {
    }
}
