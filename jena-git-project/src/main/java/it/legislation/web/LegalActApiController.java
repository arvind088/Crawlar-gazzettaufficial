package it.legislation.web;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class LegalActApiController {

    private static final Map<String, RdfFileDefinition> RDF_FILES = Map.of(
            "gazzetta_metadata_delta.ttl",
            new RdfFileDefinition(
                    Path.of("data", "rdf", "gazzetta_metadata_delta.ttl"),
                    "Metadata and ELI data from Gazzetta Ufficiale"
            ),
            "normattiva_modifications.ttl",
            new RdfFileDefinition(
                    Path.of("data", "rdf", "normattiva_modifications.ttl"),
                    "Normattiva modification and relationship data"
            )
    );

    private final LegalActQueryService queryService;
    private final CrawlerStatusService crawlerStatusService;
    private final CrawlerUpdateService crawlerUpdateService;
    private final ScheduledCrawlerUpdateJob scheduledCrawlerUpdateJob;
    private final NormattivaQueryService normattivaQueryService;

    public LegalActApiController(
            LegalActQueryService queryService,
            CrawlerStatusService crawlerStatusService,
            CrawlerUpdateService crawlerUpdateService,
            ScheduledCrawlerUpdateJob scheduledCrawlerUpdateJob,
            NormattivaQueryService normattivaQueryService
    ) {
        this.queryService = queryService;
        this.crawlerStatusService = crawlerStatusService;
        this.crawlerUpdateService = crawlerUpdateService;
        this.scheduledCrawlerUpdateJob = scheduledCrawlerUpdateJob;
        this.normattivaQueryService = normattivaQueryService;
    }

    @GetMapping("/health")
    public DataStatus health() throws IOException {
        return queryService.status();
    }

    @GetMapping("/crawl/status")
    public CrawlerStatus crawlStatus() throws IOException {
        return crawlerStatusService.status();
    }

    @PostMapping("/crawl/run")
    public CrawlerUpdateResult runCrawlUpdate(
            @RequestParam(defaultValue = "20") int maxEntries,
            @RequestParam(defaultValue = "0") int maxLinks
    ) throws IOException {
        return crawlerUpdateService.runUpdate(maxEntries, maxLinks);
    }

    @GetMapping("/crawl/run/latest")
    public ResponseEntity<CrawlerUpdateResult> latestCrawlUpdate() {
        CrawlerUpdateResult result = crawlerUpdateService.lastResult();
        if (result == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/crawl/automation")
    public CrawlerAutomationStatus crawlAutomation() {
        return scheduledCrawlerUpdateJob.status();
    }

    @GetMapping("/normattiva/modifications")
    public List<NormattivaModificationSummary> normattivaModifications(
            @RequestParam(defaultValue = "20") int limit
    ) throws IOException {
        return normattivaQueryService.listModifications(limit);
    }

    @GetMapping("/acts")
    public List<LegalActSummary> searchActs(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "50") int limit
    ) throws IOException {
        return queryService.searchActs(search, limit);
    }

    @GetMapping("/acts/{localId}")
    public ResponseEntity<LegalActSummary> findByLocalId(@PathVariable String localId) throws IOException {
        return queryService.findByLocalId(localId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/rdf/sources")
    public List<RdfSourceFile> rdfSources() throws IOException {
        List<RdfSourceFile> sources = new ArrayList<>();
        for (Map.Entry<String, RdfFileDefinition> entry : sortedRdfFiles().entrySet()) {
            String fileName = entry.getKey();
            RdfFileDefinition definition = entry.getValue();
            Path path = definition.path();
            boolean exists = Files.exists(path);
            sources.add(new RdfSourceFile(
                    exists ? "Loaded" : "Missing",
                    fileName,
                    definition.description(),
                    exists ? Files.getLastModifiedTime(path).toInstant().toString() : null,
                    exists ? Files.size(path) : 0L,
                    "/api/rdf/files/" + fileName
            ));
        }
        return sources;
    }

    @GetMapping("/rdf/files/{fileName}")
    public ResponseEntity<Resource> downloadRdfFile(@PathVariable String fileName) throws IOException {
        RdfFileDefinition definition = RDF_FILES.get(fileName);
        if (definition == null || !Files.exists(definition.path())) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = resourceFor(definition.path());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/turtle"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName)
                        .build()
                        .toString())
                .body(resource);
    }

    @PostMapping("/sparql")
    public ResponseEntity<SparqlQueryResult> runSparql(@RequestBody SparqlQueryRequest request) throws IOException {
        try {
            SparqlQueryResult result = queryService.executeSelectQuery(request.query());
            if (result.error() != null) {
                return ResponseEntity.badRequest().body(result);
            }
            return ResponseEntity.ok(result);
        } catch (RuntimeException exception) {
            return ResponseEntity.badRequest().body(SparqlQueryResult.error(
                    "Query error: missing prefix or invalid syntax."
            ));
        }
    }

    private Map<String, RdfFileDefinition> sortedRdfFiles() {
        Map<String, RdfFileDefinition> sorted = new LinkedHashMap<>();
        sorted.put("gazzetta_metadata_delta.ttl", RDF_FILES.get("gazzetta_metadata_delta.ttl"));
        sorted.put("normattiva_modifications.ttl", RDF_FILES.get("normattiva_modifications.ttl"));
        return sorted;
    }

    private Resource resourceFor(Path path) throws MalformedURLException {
        return new UrlResource(path.toAbsolutePath().normalize().toUri());
    }

    private record RdfFileDefinition(Path path, String description) {
    }
}
