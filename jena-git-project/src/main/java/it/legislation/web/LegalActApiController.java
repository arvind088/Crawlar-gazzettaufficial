package it.legislation.web;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
            ),
            "normattiva_modifications_auto.ttl",
            new RdfFileDefinition(
                    Path.of("data", "rdf", "normattiva_modifications_auto.ttl"),
                    "Automatically downloaded Normattiva update relationships"
            ),
            "normattiva_multiversion_sample.ttl",
            new RdfFileDefinition(
                    Path.of("data", "rdf", "normattiva_multiversion_sample.ttl"),
                    "Small Normattiva OpenData-inspired multi-version ELI sample"
            )
    );

    private final LegalActQueryService queryService;
    private final CrawlerStatusService crawlerStatusService;
    private final CrawlerUpdateService crawlerUpdateService;
    private final ArchiveCrawlerService archiveCrawlerService;
    private final ScheduledCrawlerUpdateJob scheduledCrawlerUpdateJob;
    private final ScheduledNormattivaUpdateJob scheduledNormattivaUpdateJob;
    private final NormattivaQueryService normattivaQueryService;
    private final NormattivaUpdateService normattivaUpdateService;

    public LegalActApiController(
            LegalActQueryService queryService,
            CrawlerStatusService crawlerStatusService,
            CrawlerUpdateService crawlerUpdateService,
            ArchiveCrawlerService archiveCrawlerService,
            ScheduledCrawlerUpdateJob scheduledCrawlerUpdateJob,
            ScheduledNormattivaUpdateJob scheduledNormattivaUpdateJob,
            NormattivaQueryService normattivaQueryService,
            NormattivaUpdateService normattivaUpdateService
    ) {
        this.queryService = queryService;
        this.crawlerStatusService = crawlerStatusService;
        this.crawlerUpdateService = crawlerUpdateService;
        this.archiveCrawlerService = archiveCrawlerService;
        this.scheduledCrawlerUpdateJob = scheduledCrawlerUpdateJob;
        this.scheduledNormattivaUpdateJob = scheduledNormattivaUpdateJob;
        this.normattivaQueryService = normattivaQueryService;
        this.normattivaUpdateService = normattivaUpdateService;
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

    @PostMapping("/archive/discover")
    public ArchiveCrawlerResult discoverArchiveLinks(
            @RequestParam(defaultValue = "2026-06-01") String startDate,
            @RequestParam(defaultValue = "2026-06-16") String endDate
    ) throws IOException {
        return archiveCrawlerService.discover(LocalDate.parse(startDate), LocalDate.parse(endDate));
    }

    @PostMapping("/archive/crawl")
    public ArchiveCrawlerResult crawlArchiveLinks(
            @RequestParam(defaultValue = "10") int limit
    ) throws IOException {
        return archiveCrawlerService.crawl(limit);
    }

    @GetMapping("/archive/run/latest")
    public ResponseEntity<ArchiveCrawlerResult> latestArchiveRun() {
        ArchiveCrawlerResult result = archiveCrawlerService.lastResult();
        if (result == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/normattiva/modifications")
    public List<NormattivaModificationSummary> normattivaModifications(
            @RequestParam(defaultValue = "20") int limit
    ) throws IOException {
        return normattivaQueryService.listModifications(limit);
    }

    @PostMapping("/normattiva/run")
    public NormattivaUpdateResult runNormattivaUpdate() throws IOException {
        return normattivaUpdateService.runUpdate();
    }

    @GetMapping("/normattiva/run/latest")
    public ResponseEntity<NormattivaUpdateResult> latestNormattivaUpdate() {
        NormattivaUpdateResult result = normattivaUpdateService.lastResult();
        if (result == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/normattiva/automation")
    public NormattivaAutomationStatus normattivaAutomation() {
        return scheduledNormattivaUpdateJob.status();
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

    @GetMapping("/resources")
    public ResponseEntity<LinkedDataResource> findLinkedDataResource(@RequestParam String id) throws IOException {
        return queryService.findLinkedDataResource(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/acts/{localId}/rdf")
    public ResponseEntity<String> rdfForAct(
            @PathVariable String localId,
            @RequestParam(defaultValue = "false") boolean download
    ) throws IOException {
        return queryService.rdfForLocalId(localId)
                .map(turtle -> {
                    ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType("text/turtle; charset=UTF-8"));
                    if (download) {
                        response.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename(localId + ".ttl")
                                .build()
                                .toString());
                    }
                    return response.body(turtle);
                })
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
        sorted.put("normattiva_modifications_auto.ttl", RDF_FILES.get("normattiva_modifications_auto.ttl"));
        sorted.put("normattiva_multiversion_sample.ttl", RDF_FILES.get("normattiva_multiversion_sample.ttl"));
        return sorted;
    }

    private Resource resourceFor(Path path) throws MalformedURLException {
        return new UrlResource(path.toAbsolutePath().normalize().toUri());
    }

    private record RdfFileDefinition(Path path, String description) {
    }
}
