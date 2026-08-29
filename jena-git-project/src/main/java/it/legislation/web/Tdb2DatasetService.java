package it.legislation.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PreDestroy;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.tdb2.TDB2Factory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class Tdb2DatasetService {

    static final Path GAZZETTA_DELTA = Path.of("data", "rdf", "gazzetta_metadata_delta.ttl");
    static final Path NORMATTIVA_MODIFICATIONS = Path.of("data", "rdf", "normattiva_modifications.ttl");
    static final Path NORMATTIVA_AUTO_MODIFICATIONS = Path.of("data", "rdf", "normattiva_modifications_auto.ttl");
    static final Path NORMATTIVA_MULTIVERSION_SAMPLE = Path.of("data", "rdf", "normattiva_multiversion_sample.ttl");

    private static final String GRAPH_BASE = "http://example.org/italian-legislation/graph/";

    private final Dataset dataset;
    private final List<RdfGraphSource> rdfSources;
    private final Map<Path, Long> loadedMarkers = new HashMap<>();
    private LoadStatus lastStatus = new LoadStatus(0L, List.of(), List.of());

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
                new RdfGraphSource(GRAPH_BASE + "normattiva/multiversion-sample", NORMATTIVA_MULTIVERSION_SAMPLE)
        );
    }

    public LoadStatus status() throws IOException {
        syncSources();
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

    public <T> T read(DatasetReader<T> reader) throws IOException {
        syncSources();
        dataset.begin(ReadWrite.READ);
        try {
            return reader.read(dataset);
        } finally {
            dataset.end();
        }
    }

    @PreDestroy
    public void close() {
        dataset.close();
    }

    private synchronized void syncSources() throws IOException {
        boolean changed = false;
        List<String> loadedFiles = new ArrayList<>();
        List<String> missingFiles = new ArrayList<>();

        dataset.begin(ReadWrite.WRITE);
        try {
            for (RdfGraphSource source : rdfSources) {
                Path path = source.path();
                Path normalizedPath = path.toAbsolutePath().normalize();
                long marker = marker(path);

                if (marker >= 0) {
                    loadedFiles.add(normalizedPath.toString());
                } else {
                    missingFiles.add(normalizedPath.toString());
                }

                if (loadedMarkers.getOrDefault(path, Long.MIN_VALUE) == marker) {
                    continue;
                }

                dataset.getNamedModel(source.graphUri()).removeAll();
                if (marker >= 0) {
                    try (InputStream inputStream = Files.newInputStream(path)) {
                        RDFDataMgr.read(dataset.getNamedModel(source.graphUri()), inputStream, Lang.TURTLE);
                    }
                }
                loadedMarkers.put(path, marker);
                changed = true;
            }

            if (changed) {
                rebuildDefaultModel();
            }
            lastStatus = new LoadStatus(dataset.getDefaultModel().size(), loadedFiles, missingFiles);
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private void rebuildDefaultModel() {
        dataset.getDefaultModel().removeAll();
        for (RdfGraphSource source : rdfSources) {
            dataset.getDefaultModel().add(dataset.getNamedModel(source.graphUri()));
        }
    }

    private long marker(Path path) throws IOException {
        if (!Files.exists(path)) {
            return -1L;
        }
        return Files.getLastModifiedTime(path).toMillis();
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
