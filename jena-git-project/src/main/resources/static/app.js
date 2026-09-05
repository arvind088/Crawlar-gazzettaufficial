const { useCallback, useEffect, useMemo, useState } = React;
const e = React.createElement;
const SEARCH_PAGE_SIZE = 20;

const TABS = [
  ["search", "Legal Acts", "grid"],
  ["normattiva", "Relationships", "link"],
  ["sparql", "SPARQL", "code"],
  ["technical", "Status", "pulse"]
];

const SPARQL_EXAMPLES = [
  {
    id: "all",
    label: "All acts",
    query: `PREFIX eli: <http://data.europa.eu/eli/ontology#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?act ?title ?date ?type ?source WHERE {
  ?act a eli:LegalResource .
  OPTIONAL { ?act rdfs:label ?title . }
  OPTIONAL { ?act eli:date_publication ?date . }
  OPTIONAL { ?act eli:type_document ?type . }
  OPTIONAL { ?act <http://purl.org/dc/terms/source> ?source . }
}
ORDER BY DESC(?date)
LIMIT 20`
  },
  {
    id: "year",
    label: "Acts by year",
    query: `PREFIX eli: <http://data.europa.eu/eli/ontology#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?act ?title ?date ?type WHERE {
  ?act a eli:LegalResource .
  OPTIONAL { ?act rdfs:label ?title . }
  OPTIONAL { ?act eli:date_publication ?date . }
  OPTIONAL { ?act eli:type_document ?type . }
  FILTER(STRSTARTS(STR(?date), "2026"))
}
ORDER BY DESC(?date)
LIMIT 20`
  },
  {
    id: "type",
    label: "Acts by type",
    query: `PREFIX eli: <http://data.europa.eu/eli/ontology#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?act ?title ?date ?type WHERE {
  ?act a eli:LegalResource .
  OPTIONAL { ?act rdfs:label ?title . }
  OPTIONAL { ?act eli:date_publication ?date . }
  OPTIONAL { ?act eli:type_document ?type . }
  FILTER(CONTAINS(STR(?type), "DECRETO"))
}
ORDER BY DESC(?date)
LIMIT 20`
  },
  {
    id: "latest",
    label: "Latest acts",
    query: `PREFIX eli: <http://data.europa.eu/eli/ontology#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?act ?title ?date ?type WHERE {
  ?act a eli:LegalResource .
  OPTIONAL { ?act rdfs:label ?title . }
  OPTIONAL { ?act eli:date_publication ?date . }
  OPTIONAL { ?act eli:type_document ?type . }
}
ORDER BY DESC(?date)
LIMIT 10`
  },
  {
    id: "relations",
    label: "Acts with Normattiva relations",
    query: `PREFIX eli: <http://data.europa.eu/eli/ontology#>
PREFIX ilg: <http://example.org/italian-legislation/ontology#>

SELECT ?source ?relation ?target WHERE {
  ?source ?relation ?target .
  FILTER(?relation IN (ilg:modifies, ilg:modifiedBy, eli:commences, eli:commenced_by))
}
LIMIT 20`
  },
  {
    id: "eli-levels",
    label: "Validate ELI levels",
    query: `PREFIX eli: <http://data.europa.eu/eli/ontology#>

SELECT ?act ?expression ?manifestation ?version ?language ?format WHERE {
  ?act eli:id_local "005G0104" ;
       eli:is_realized_by ?expression .
  OPTIONAL { ?expression eli:version ?version . }
  OPTIONAL { ?expression eli:language ?language . }
  OPTIONAL { ?expression eli:is_embodied_by ?manifestation . }
  OPTIONAL { ?manifestation eli:format ?format . }
}
ORDER BY ?version`
  },
  {
    id: "conversion",
    label: "Validate conversion links",
    query: `PREFIX eli: <http://data.europa.eu/eli/ontology#>
PREFIX ilg: <http://example.org/italian-legislation/ontology#>

SELECT ?source ?predicate ?target WHERE {
  VALUES ?source { <http://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg> }
  ?source ?predicate ?target .
  FILTER(?predicate IN (ilg:modifies, eli:commences))
}
ORDER BY ?predicate ?target`
  },
  {
    id: "multi-version",
    label: "Find multi-version acts",
    query: `PREFIX eli: <http://data.europa.eu/eli/ontology#>

SELECT ?act (COUNT(DISTINCT ?expression) AS ?expressionCount) WHERE {
  ?act a eli:LegalResource ;
       eli:is_realized_by ?expression .
}
GROUP BY ?act
HAVING (COUNT(DISTINCT ?expression) > 1)
ORDER BY DESC(?expressionCount)
LIMIT 20`
  }
];

function App() {
  const [activeTab, setActiveTab] = useState("search");
  const [status, setStatus] = useState(null);
  const [crawlStatus, setCrawlStatus] = useState(null);
  const [automationStatus, setAutomationStatus] = useState(null);
  const [normattivaAutomationStatus, setNormattivaAutomationStatus] = useState(null);
  const [normattivaUpdateResult, setNormattivaUpdateResult] = useState(null);
  const [normattivaRunning, setNormattivaRunning] = useState(false);
  const [normattivaError, setNormattivaError] = useState("");
  const [normattivaDetailFetchResult, setNormattivaDetailFetchResult] = useState(null);
  const [normattivaDetailRunning, setNormattivaDetailRunning] = useState(false);
  const [normattivaDetailError, setNormattivaDetailError] = useState("");
  const [normattivaEvidenceScanResult, setNormattivaEvidenceScanResult] = useState(null);
  const [normattivaEvidenceRunning, setNormattivaEvidenceRunning] = useState(false);
  const [normattivaEvidenceError, setNormattivaEvidenceError] = useState("");
  const [normattivaImportResult, setNormattivaImportResult] = useState(null);
  const [normattivaImportRunning, setNormattivaImportRunning] = useState(false);
  const [normattivaImportError, setNormattivaImportError] = useState("");
  const [normattivaCandidateRunResult, setNormattivaCandidateRunResult] = useState(null);
  const [normattivaCandidateRunning, setNormattivaCandidateRunning] = useState(false);
  const [normattivaCandidateError, setNormattivaCandidateError] = useState("");
  const [updateResult, setUpdateResult] = useState(null);
  const [updateRunning, setUpdateRunning] = useState(false);
  const [updateError, setUpdateError] = useState("");
  const [archiveResult, setArchiveResult] = useState(null);
  const [archiveRunning, setArchiveRunning] = useState(false);
  const [archiveError, setArchiveError] = useState("");
  const [archiveStartDate, setArchiveStartDate] = useState("2026-06-01");
  const [archiveEndDate, setArchiveEndDate] = useState("2026-06-16");
  const [archiveLimit, setArchiveLimit] = useState("10");
  const [modifications, setModifications] = useState([]);
  const [normattivaUpdates, setNormattivaUpdates] = useState([]);
  const [normattivaDetails, setNormattivaDetails] = useState([]);
  const [normattivaEvidence, setNormattivaEvidence] = useState([]);
  const [normattivaRelationCandidates, setNormattivaRelationCandidates] = useState([]);
  const [rdfSources, setRdfSources] = useState([]);
  const [ingestionRuns, setIngestionRuns] = useState([]);
  const [acts, setActs] = useState([]);
  const [datasetActs, setDatasetActs] = useState([]);
  const [selected, setSelected] = useState(null);
  const [resourceDetail, setResourceDetail] = useState(null);
  const [resourceLoading, setResourceLoading] = useState(false);
  const [resourceError, setResourceError] = useState("");
  const [search, setSearch] = useState("");
  const [yearFilter, setYearFilter] = useState("");
  const [typeFilter, setTypeFilter] = useState("");
  const [sourceFilter, setSourceFilter] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [actSparqlQuery, setActSparqlQuery] = useState("");

  const loadIngestionRuns = useCallback(async () => {
    const response = await fetch("/api/runs?limit=20");
    if (!response.ok) {
      throw new Error("Run history request failed");
    }
    setIngestionRuns(await response.json());
  }, []);

  const loadStatus = useCallback(async () => {
    const response = await fetch("/api/health");
    if (!response.ok) {
      throw new Error("Status request failed");
    }
    setStatus(await response.json());
  }, []);

  const loadCrawlStatus = useCallback(async () => {
    const response = await fetch("/api/crawl/status");
    if (!response.ok) {
      throw new Error("Crawler status request failed");
    }
    setCrawlStatus(await response.json());
  }, []);

  const loadLatestUpdate = useCallback(async () => {
    const response = await fetch("/api/crawl/run/latest");
    if (response.status === 204) {
      return;
    }
    if (!response.ok) {
      throw new Error("Latest update result request failed");
    }
    setUpdateResult(await response.json());
  }, []);

  const loadAutomationStatus = useCallback(async () => {
    const response = await fetch("/api/crawl/automation");
    if (!response.ok) {
      throw new Error("Crawler automation request failed");
    }
    setAutomationStatus(await response.json());
  }, []);

  const loadLatestArchiveRun = useCallback(async () => {
    const response = await fetch("/api/archive/run/latest");
    if (response.status === 204) {
      return;
    }
    if (!response.ok) {
      throw new Error("Latest archive run request failed");
    }
    setArchiveResult(await response.json());
  }, []);

  const loadModifications = useCallback(async () => {
    const response = await fetch("/api/normattiva/modifications?limit=20");
    if (!response.ok) {
      throw new Error("Normattiva modifications request failed");
    }
    const rows = await response.json();
    setModifications(Array.isArray(rows) ? rows : []);
  }, []);

  const loadNormattivaUpdates = useCallback(async () => {
    const response = await fetch("/api/normattiva/updates?limit=20");
    if (!response.ok) {
      throw new Error("Normattiva updates request failed");
    }
    const rows = await response.json();
    setNormattivaUpdates(Array.isArray(rows) ? rows : []);
  }, []);

  const loadNormattivaDetails = useCallback(async () => {
    const response = await fetch("/api/normattiva/details?limit=20");
    if (!response.ok) {
      throw new Error("Normattiva details request failed");
    }
    const rows = await response.json();
    setNormattivaDetails(Array.isArray(rows) ? rows : []);
  }, []);

  const loadNormattivaEvidence = useCallback(async () => {
    const response = await fetch("/api/normattiva/evidence?limit=20");
    if (!response.ok) {
      throw new Error("Normattiva evidence request failed");
    }
    const rows = await response.json();
    setNormattivaEvidence(Array.isArray(rows) ? rows : []);
  }, []);

  const loadNormattivaRelationCandidates = useCallback(async () => {
    const response = await fetch("/api/normattiva/relation-candidates?limit=20");
    if (!response.ok) {
      throw new Error("Normattiva relation candidates request failed");
    }
    const rows = await response.json();
    setNormattivaRelationCandidates(Array.isArray(rows) ? rows : []);
  }, []);

  const loadNormattivaAutomationStatus = useCallback(async () => {
    const response = await fetch("/api/normattiva/automation");
    if (!response.ok) {
      throw new Error("Normattiva automation request failed");
    }
    setNormattivaAutomationStatus(await response.json());
  }, []);

  const loadLatestNormattivaUpdate = useCallback(async () => {
    const response = await fetch("/api/normattiva/run/latest");
    if (response.status === 204) {
      return;
    }
    if (!response.ok) {
      throw new Error("Latest Normattiva update request failed");
    }
    setNormattivaUpdateResult(await response.json());
  }, []);

  const loadRdfSources = useCallback(async () => {
    const response = await fetch("/api/rdf/sources");
    if (!response.ok) {
      throw new Error("RDF sources request failed");
    }
    const rows = await response.json();
    setRdfSources(Array.isArray(rows) ? rows : []);
  }, []);

  const loadResourceDetail = useCallback(async (identifier) => {
    if (!identifier) {
      setResourceDetail(null);
      setResourceError("");
      return null;
    }

    setResourceLoading(true);
    setResourceError("");
    try {
      const params = new URLSearchParams({ id: identifier });
      const response = await fetch(`/api/resources?${params.toString()}`);
      if (response.status === 404) {
        setResourceDetail(null);
        setResourceError("No linked data found for this resource.");
        return null;
      }
      if (!response.ok) {
        throw new Error("Linked-data request failed");
      }
      const detail = await response.json();
      setResourceDetail(detail);
      return detail;
    } catch (err) {
      setResourceDetail(null);
      setResourceError(err.message || "Linked-data request failed");
      return null;
    } finally {
      setResourceLoading(false);
    }
  }, []);

  const runSearch = useCallback(async (query, options = {}) => {
    setLoading(true);
    setError("");
    if (options.resetFilters) {
      setYearFilter("");
      setTypeFilter("");
      setSourceFilter("");
    }
    try {
      const params = new URLSearchParams({ search: query ?? search, limit: "100" });
      const response = await fetch(`/api/acts?${params.toString()}`);
      if (!response.ok) {
        throw new Error("Search request failed");
      }
      const data = await response.json();
      const rows = Array.isArray(data) ? data.filter(hasDisplayMetadata) : [];
      setActs(rows);
      setSelected(rows[0] ?? null);
      if (!(query ?? search)) {
        setDatasetActs(rows);
      }
    } catch (err) {
      setError(err.message || "Search failed");
      setActs([]);
      setSelected(null);
    } finally {
      setLoading(false);
    }
  }, [search]);

  const runCrawlerUpdate = useCallback(async () => {
    setUpdateRunning(true);
    setUpdateError("");
    try {
      const response = await fetch("/api/crawl/run?maxEntries=20", { method: "POST" });
      if (!response.ok) {
        throw new Error("Update check request failed");
      }
      const result = await response.json();
      setUpdateResult(result);
      if (result.state === "FAILED") {
        setUpdateError(result.message || "Update check failed");
      }
      await loadStatus();
      await loadCrawlStatus();
      await loadAutomationStatus();
      await loadRdfSources();
      await runSearch(search);
    } catch (err) {
      setUpdateError(err.message || "Update check failed");
    } finally {
      setUpdateRunning(false);
    }
  }, [loadAutomationStatus, loadCrawlStatus, loadRdfSources, loadStatus, runSearch, search]);

  const runArchiveDiscover = useCallback(async () => {
    setArchiveRunning(true);
    setArchiveError("");
    try {
      const params = new URLSearchParams({
        startDate: archiveStartDate,
        endDate: archiveEndDate
      });
      const response = await fetch(`/api/archive/discover?${params.toString()}`, { method: "POST" });
      if (!response.ok) {
        throw new Error("Archive discovery request failed");
      }
      const result = await response.json();
      setArchiveResult(result);
      if (result.state === "FAILED") {
        setArchiveError(result.message || "Archive discovery failed");
      }
      await loadCrawlStatus();
      await loadStatus();
    } catch (err) {
      setArchiveError(err.message || "Archive discovery failed");
    } finally {
      setArchiveRunning(false);
    }
  }, [archiveEndDate, archiveStartDate, loadCrawlStatus, loadStatus]);

  const runArchiveCrawl = useCallback(async () => {
    setArchiveRunning(true);
    setArchiveError("");
    try {
      const availableLinks = Number(archiveResult?.linksAvailable || 0);
      const requestedLimit = Number(archiveLimit || "10");
      const safeLimit = availableLinks > 0 && requestedLimit > availableLinks ? availableLinks : requestedLimit;
      if (String(safeLimit) !== String(archiveLimit)) {
        setArchiveLimit(String(safeLimit));
      }
      const params = new URLSearchParams({ limit: String(safeLimit) });
      const response = await fetch(`/api/archive/crawl?${params.toString()}`, { method: "POST" });
      if (!response.ok) {
        throw new Error("Archive crawl request failed");
      }
      const result = await response.json();
      setArchiveResult(result);
      if (result.state === "FAILED") {
        setArchiveError(result.message || "Archive crawl failed");
      }
      await loadStatus();
      await loadCrawlStatus();
      await loadRdfSources();
      await runSearch(search);
    } catch (err) {
      setArchiveError(err.message || "Archive crawl failed");
    } finally {
      setArchiveRunning(false);
    }
  }, [archiveLimit, archiveResult, loadCrawlStatus, loadRdfSources, loadStatus, runSearch, search]);

  const runNormattivaUpdate = useCallback(async () => {
    setNormattivaRunning(true);
    setNormattivaError("");
    try {
      const response = await fetch("/api/normattiva/run", { method: "POST" });
      if (!response.ok) {
        throw new Error("Normattiva update request failed");
      }
      const result = await response.json();
      setNormattivaUpdateResult(result);
      if (result.state === "FAILED") {
        setNormattivaError(result.message || "Normattiva update failed");
      }
      await loadStatus();
      await loadModifications();
      await loadNormattivaUpdates();
      await loadNormattivaDetails();
      await loadNormattivaEvidence();
      await loadNormattivaRelationCandidates();
      await loadRdfSources();
      await loadNormattivaAutomationStatus();
    } catch (err) {
      setNormattivaError(err.message || "Normattiva update failed");
    } finally {
      setNormattivaRunning(false);
    }
  }, [loadModifications, loadNormattivaAutomationStatus, loadNormattivaDetails, loadNormattivaEvidence, loadNormattivaRelationCandidates, loadNormattivaUpdates, loadRdfSources, loadStatus]);

  const runNormattivaDetailFetch = useCallback(async () => {
    setNormattivaDetailRunning(true);
    setNormattivaDetailError("");
    try {
      const response = await fetch("/api/normattiva/details/run?limit=20", { method: "POST" });
      if (!response.ok) {
        throw new Error("Normattiva detail fetch request failed");
      }
      const result = await response.json();
      setNormattivaDetailFetchResult(result);
      if (result.state === "FAILED") {
        setNormattivaDetailError(result.message || "Normattiva detail fetch failed");
      }
      await loadNormattivaDetails();
      await loadNormattivaEvidence();
      await loadNormattivaRelationCandidates();
    } catch (err) {
      setNormattivaDetailError(err.message || "Normattiva detail fetch failed");
    } finally {
      setNormattivaDetailRunning(false);
    }
  }, [loadNormattivaDetails, loadNormattivaEvidence, loadNormattivaRelationCandidates]);

  const runNormattivaEvidenceScan = useCallback(async () => {
    setNormattivaEvidenceRunning(true);
    setNormattivaEvidenceError("");
    try {
      const response = await fetch("/api/normattiva/evidence/run?limit=20", { method: "POST" });
      if (!response.ok) {
        throw new Error("Normattiva evidence scan request failed");
      }
      const result = await response.json();
      setNormattivaEvidenceScanResult(result);
      if (result.state === "FAILED") {
        setNormattivaEvidenceError(result.message || "Normattiva evidence scan failed");
      }
      await loadNormattivaEvidence();
      await loadNormattivaRelationCandidates();
    } catch (err) {
      setNormattivaEvidenceError(err.message || "Normattiva evidence scan failed");
    } finally {
      setNormattivaEvidenceRunning(false);
    }
  }, [loadNormattivaEvidence, loadNormattivaRelationCandidates]);

  const importNormattivaUpdates = useCallback(async () => {
    setNormattivaImportRunning(true);
    setNormattivaImportError("");
    try {
      const response = await fetch("/api/normattiva/import/updates", { method: "POST" });
      if (!response.ok) {
        throw new Error("Normattiva update import request failed");
      }
      const result = await response.json();
      setNormattivaImportResult(result);
      if (result.state === "FAILED") {
        setNormattivaImportError(result.message || "Normattiva update import failed");
      }
      await loadNormattivaUpdates();
    } catch (err) {
      setNormattivaImportError(err.message || "Normattiva update import failed");
    } finally {
      setNormattivaImportRunning(false);
    }
  }, [loadNormattivaUpdates]);

  const importNormattivaDetails = useCallback(async () => {
    setNormattivaImportRunning(true);
    setNormattivaImportError("");
    try {
      const response = await fetch("/api/normattiva/import/details", { method: "POST" });
      if (!response.ok) {
        throw new Error("Normattiva detail import request failed");
      }
      const result = await response.json();
      setNormattivaImportResult(result);
      if (result.state === "FAILED") {
        setNormattivaImportError(result.message || "Normattiva detail import failed");
      }
      await loadNormattivaDetails();
      await loadNormattivaEvidence();
      await loadNormattivaRelationCandidates();
    } catch (err) {
      setNormattivaImportError(err.message || "Normattiva detail import failed");
    } finally {
      setNormattivaImportRunning(false);
    }
  }, [loadNormattivaDetails, loadNormattivaEvidence, loadNormattivaRelationCandidates]);

  const runNormattivaRelationCandidates = useCallback(async () => {
    setNormattivaCandidateRunning(true);
    setNormattivaCandidateError("");
    try {
      const response = await fetch("/api/normattiva/relation-candidates/run?limit=20", { method: "POST" });
      if (!response.ok) {
        throw new Error("Normattiva relation candidate request failed");
      }
      const result = await response.json();
      setNormattivaCandidateRunResult(result);
      if (result.state === "FAILED") {
        setNormattivaCandidateError(result.message || "Normattiva relation candidate extraction failed");
      }
      await loadNormattivaRelationCandidates();
    } catch (err) {
      setNormattivaCandidateError(err.message || "Normattiva relation candidate extraction failed");
    } finally {
      setNormattivaCandidateRunning(false);
    }
  }, [loadNormattivaRelationCandidates]);

  const runSparqlForAct = useCallback((act) => {
    setActSparqlQuery(createActSparqlQuery(act));
    setActiveTab("sparql");
  }, []);

  const navigateResource = useCallback(async (identifier) => {
    const detail = await loadResourceDetail(identifier);
    if (detail) {
      setSelected({
        uri: detail.uri,
        title: detail.title,
        localId: detail.localId,
        publicationDate: null,
        documentDate: null,
        type: null,
        source: null
      });
    }
  }, [loadResourceDetail]);

  useEffect(() => {
    loadStatus().catch((err) => setError(err.message || "Status request failed"));
    loadCrawlStatus().catch((err) => setError(err.message || "Crawler status request failed"));
    loadAutomationStatus().catch((err) => setError(err.message || "Crawler automation request failed"));
    loadNormattivaAutomationStatus().catch((err) => setError(err.message || "Normattiva automation request failed"));
    loadLatestUpdate().catch(() => {});
    loadLatestNormattivaUpdate().catch(() => {});
    loadLatestArchiveRun().catch(() => {});
    loadModifications().catch((err) => setError(err.message || "Normattiva request failed"));
    loadNormattivaUpdates().catch((err) => setError(err.message || "Normattiva updates request failed"));
    loadNormattivaDetails().catch((err) => setError(err.message || "Normattiva details request failed"));
    loadNormattivaEvidence().catch((err) => setError(err.message || "Normattiva evidence request failed"));
    loadNormattivaRelationCandidates().catch((err) => setError(err.message || "Normattiva relation candidates request failed"));
    loadRdfSources().catch((err) => setError(err.message || "RDF sources request failed"));
    loadIngestionRuns().catch(() => {});
    runSearch("");
  }, [loadAutomationStatus, loadCrawlStatus, loadLatestArchiveRun, loadLatestNormattivaUpdate, loadLatestUpdate, loadModifications, loadNormattivaAutomationStatus, loadNormattivaDetails, loadNormattivaEvidence, loadNormattivaRelationCandidates, loadNormattivaUpdates, loadRdfSources, loadIngestionRuns, loadStatus, runSearch]);

  useEffect(() => {
    loadResourceDetail(selected?.uri || displayLocalId(selected));
  }, [loadResourceDetail, selected]);

  const filteredActs = useMemo(() => acts.filter((act) => {
    const yearMatch = !yearFilter || String(act.publicationDate || "").startsWith(yearFilter);
    const typeMatch = !typeFilter || shortType(act.type) === typeFilter;
    const sourceMatch = !sourceFilter || sourceLabel(act.source) === sourceFilter;
    return yearMatch && typeMatch && sourceMatch;
  }), [acts, sourceFilter, typeFilter, yearFilter]);

  const loadedFileCount = status?.loadedFiles?.length ?? 0;
  const missingFileCount = status?.missingFiles?.length ?? 0;
  const apiState = status ? (missingFileCount === 0 ? "Ready" : "Check files") : "Loading";
  const years = uniqueSorted(acts.map((act) => yearFromDate(act.publicationDate)).filter(Boolean));
  const types = uniqueSorted(acts.map((act) => shortType(act.type)).filter(Boolean));
  const sources = uniqueSorted(acts.map((act) => sourceLabel(act.source)).filter(Boolean));

  const page = activeTab === "search" ? e(SearchPage, {
    acts: filteredActs,
    allActs: acts,
    datasetActs: datasetActs.length ? datasetActs : acts,
    error,
    loading,
    modifications,
    resourceDetail,
    resourceError,
    resourceLoading,
    search,
    selected,
    sourceFilter,
    sources,
    typeFilter,
    types,
    yearFilter,
    years,
    onSearchChange: setSearch,
    onRunSearch: runSearch,
    onRunSparqlForAct: runSparqlForAct,
    onNavigateResource: navigateResource,
    onSelect: setSelected,
    onSourceFilterChange: setSourceFilter,
    onTypeFilterChange: setTypeFilter,
    onYearFilterChange: setYearFilter
  }) : activeTab === "normattiva" ? e(NormattivaPage, { acts: datasetActs.length ? datasetActs : acts, modifications, normattivaDetails, normattivaEvidence, normattivaRelationCandidates, normattivaUpdates })
    : activeTab === "sparql" ? e(SparqlPage, { initialQuery: actSparqlQuery })
      : e(TechnicalPage, {
          archiveEndDate,
          archiveError,
          archiveLimit,
          archiveResult,
          archiveRunning,
          archiveStartDate,
          crawlStatus,
          automationStatus,
          ingestionRuns,
          loadedFileCount,
          missingFileCount,
          normattivaAutomationStatus,
          normattivaDetails,
          normattivaDetailError,
          normattivaDetailFetchResult,
          normattivaDetailRunning,
          normattivaEvidence,
          normattivaEvidenceError,
          normattivaEvidenceRunning,
          normattivaEvidenceScanResult,
          normattivaCandidateError,
          normattivaCandidateRunResult,
          normattivaCandidateRunning,
          normattivaError,
          normattivaImportError,
          normattivaImportResult,
          normattivaImportRunning,
          normattivaRelationCandidates,
          normattivaRunning,
          normattivaUpdateResult,
          normattivaUpdates,
          rdfSources,
          status,
          updateError,
          updateResult,
          updateRunning,
          onArchiveEndDateChange: setArchiveEndDate,
          onArchiveLimitChange: setArchiveLimit,
          onArchiveStartDateChange: setArchiveStartDate,
          onRunArchiveCrawl: runArchiveCrawl,
          onRunArchiveDiscover: runArchiveDiscover,
          onImportNormattivaDetails: importNormattivaDetails,
          onImportNormattivaUpdates: importNormattivaUpdates,
          onRunNormattivaDetailFetch: runNormattivaDetailFetch,
          onRunNormattivaEvidenceScan: runNormattivaEvidenceScan,
          onRunNormattivaRelationCandidates: runNormattivaRelationCandidates,
          onRunNormattivaUpdate: runNormattivaUpdate,
          onRunUpdate: runCrawlerUpdate
        });

  return e("div", { className: "app-shell" },
    e("main", { className: "app-layout" },
      e("section", { className: "workspace-card" },
        e("header", { className: "hero" },
          e("div", { className: "hero-inner" },
            e("div", { className: "brand-lockup" },
              e("div", { className: "brand-mark", "aria-hidden": "true" }, e(Icon, { name: "building" })),
              e("div", { className: "hero-copy" },
                e("h1", null, "Legal RDF Explorer"),
                e("p", { className: "hero-text" }, "A lightweight explorer for Gazzetta metadata, Normattiva relationships, and RDF queries.")
              )
            ),
            e("div", { className: "hero-status", "aria-label": "API status" },
              e("span", { className: missingFileCount > 0 ? "status-dot warn" : "status-dot" }),
              e("span", null, "API ", apiState)
            )
          ),
          e("nav", { className: "tabs", "aria-label": "Application pages" },
            TABS.map(([id, label, icon]) =>
              e("button", {
                key: id,
                className: activeTab === id ? "tab-button active" : "tab-button",
                onClick: () => setActiveTab(id),
                type: "button"
              }, e(Icon, { name: icon }), e("span", null, label))
            )
          )
        ),
        e("div", { className: "dashboard" }, page)
      )
    )
  );
}

function SearchPage(props) {
  const dataset = props.datasetActs || props.allActs;
  const totalActs = dataset.length;
  const lastUpdate = dataset.map((act) => act.publicationDate).filter(Boolean).sort().pop() || "unknown";

  return e(React.Fragment, null,
    e("section", { className: "metric-grid compact-metrics", "aria-label": "Dataset overview" },
      e(MetricTile, { icon: "file", label: "Legal acts", value: totalActs, note: "in the triple store" }),
      e(MetricTile, { icon: "link", label: "Act-to-act relations", value: props.modifications.length, note: "from Normattiva" }),
      e(MetricTile, { icon: "clock", label: "Most recent publication", value: formatDisplayDate(lastUpdate), note: "Gazzetta Ufficiale date" })
    ),
    e("div", { className: "content-grid" },
      e(SearchPanel, props),
      e(DetailPanel, {
        act: props.selected,
        modifications: props.modifications,
        resourceDetail: props.resourceDetail,
        resourceError: props.resourceError,
        resourceLoading: props.resourceLoading,
        onNavigateResource: props.onNavigateResource,
        onRunSparqlForAct: props.onRunSparqlForAct
      })
    )
  );
}

function MetricTile({ icon, label, value, note }) {
  return e("div", { className: "metric-tile" },
    e("div", { className: "metric-icon", "aria-hidden": "true" }, e(Icon, { name: icon || "file" })),
    e("div", { className: "metric-copy" },
      e("div", { className: "metric-label" }, label),
      e("div", { className: "metric-value" }, value),
      e("div", { className: "metric-note" }, note)
    )
  );
}

function SearchPanel({
  acts,
  error,
  loading,
  search,
  selected,
  onSearchChange,
  onRunSearch,
  onSelect
}) {
  const [page, setPage] = useState(1);
  const pageCount = Math.max(1, Math.ceil(acts.length / SEARCH_PAGE_SIZE));
  const safePage = Math.min(page, pageCount);
  const pageStart = acts.length ? (safePage - 1) * SEARCH_PAGE_SIZE : 0;
  const pageEnd = Math.min(pageStart + SEARCH_PAGE_SIZE, acts.length);
  const visibleActs = acts.slice(pageStart, pageEnd);

  useEffect(() => {
    setPage(1);
  }, [acts]);

  return e("section", { className: "panel search-panel" },
    e("div", { className: "panel-heading" },
      e("div", { className: "heading-lockup" },
        e("span", { className: "heading-icon", "aria-hidden": "true" }, e(Icon, { name: "search" })),
        e("div", null,
          e("h2", null, "Legal acts"),
          e("p", { className: "panel-subtitle" }, "Gazzetta metadata loaded from RDF.")
        )
      ),
      e("span", { className: "result-count" }, loading ? "Loading" : `${acts.length} result${acts.length === 1 ? "" : "s"}`)
    ),
    e("form", {
      className: "filter-grid",
      onSubmit: (event) => {
        event.preventDefault();
        onRunSearch(search, { resetFilters: true });
      }
    },
      e("label", null, "Keyword / Act ID",
        e("span", { className: "input-shell" },
          e("input", {
            "aria-label": "Keyword or act id",
            value: search,
            onChange: (event) => onSearchChange(event.target.value),
            placeholder: "Search by title, ID or URI..."
          }),
          e("span", { className: "input-icon", "aria-hidden": "true" }, e(Icon, { name: "search" }))
        )
      ),
      e("button", { type: "submit", disabled: loading }, "Search")
    ),
    error ? e("p", { className: "error-message" }, error) : null,
    e("div", { className: "table-meta" },
      e("span", null, e(Icon, { name: "file" }), " ",
        acts.length
          ? `Showing ${pageStart + 1}-${pageEnd} of ${acts.length}`
          : "0 results found"
      )
    ),
    e(ResultTable, { acts: visibleActs, selected, onSelect }),
    acts.length > SEARCH_PAGE_SIZE
      ? e("div", { className: "pagination-controls" },
          e("button", {
            className: "secondary-button",
            disabled: safePage <= 1,
            onClick: () => setPage((current) => Math.max(1, current - 1)),
            type: "button"
          }, "Previous"),
          e("span", { className: "page-range" }, `Page ${safePage} of ${pageCount}`),
          e("button", {
            className: "secondary-button",
            disabled: safePage >= pageCount,
            onClick: () => setPage((current) => Math.min(pageCount, current + 1)),
            type: "button"
          }, "Next")
        )
      : null
  );
}

function SelectFilter({ label, value, options, onChange }) {
  return e("label", null, label,
    e("select", {
      value,
      onChange: (event) => onChange(event.target.value)
    },
      e("option", { value: "" }, "All"),
      options.map((option) => e("option", { key: option, value: option }, option))
    )
  );
}

function ResultTable({ acts, selected, onSelect }) {
  if (!acts.length) {
    return e("div", { className: "empty-state" }, "No legal acts match that search");
  }

  return e("div", { className: "table-wrap" },
    e("table", { className: "results-table" },
      e("thead", null,
        e("tr", null,
          e("th", null, "ID"),
          e("th", null, "Title"),
          e("th", null, "Date"),
          e("th", null, "Source")
        )
      ),
      e("tbody", null,
        acts.map((act) =>
          e("tr", {
            key: act.uri,
            className: selected?.uri === act.uri ? "selectable-row selected-row" : "selectable-row",
            onClick: () => onSelect(act),
            onKeyDown: (event) => {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                onSelect(act);
              }
            },
            tabIndex: 0,
            title: `Select ${displayLocalId(act)}`
          },
            e("td", { className: "mono strong-cell" }, displayLocalId(act)),
            e("td", null, e("span", { className: "table-title" }, displayTitle(act))),
            e("td", { className: "mono" }, act.publicationDate || ""),
            e("td", null, sourceLabel(act.source))
          )
        )
      )
    )
  );
}

function DetailPanel({
  act,
  modifications,
  resourceDetail,
  resourceError,
  resourceLoading,
  onNavigateResource,
  onRunSparqlForAct
}) {
  return e("aside", { className: "panel detail-panel" },
    e("div", { className: "panel-heading" },
      e("div", { className: "heading-lockup" },
        e("span", { className: "heading-icon", "aria-hidden": "true" }, e(Icon, { name: "file" })),
        e("div", null,
          e("p", { className: "section-label" }, "Selected act"),
          e("h2", null, act ? displayLocalId(act) : "No act selected")
        )
      )
    ),
    act ? e(DetailView, {
      act,
      modifications,
      resourceDetail,
      resourceError,
      resourceLoading,
      onNavigateResource,
      onRunSparqlForAct
    }) : e("div", { className: "empty-state" }, "Pick an act from the list to see its details")
  );
}

function DetailView({
  act,
  modifications,
  resourceDetail,
  resourceError,
  resourceLoading,
  onNavigateResource,
  onRunSparqlForAct
}) {
  const relations = relatedModifications(act, modifications);
  const detail = resourceDetail?.uri === act.uri ? resourceDetail : null;
  // A Work's status is that of its current text. Prefer an Expression that is
  // in force over one that has been superseded, otherwise an act with both
  // would report the status of whichever happened to be listed first.
  const expressionStates = (detail?.expressions || [])
    .map((node) => inForceState(node))
    .filter(Boolean);
  const actState = inForceState(act)
    || expressionStates.find((state) => state.tone === "current")
    || expressionStates[0];
  const fields = [
    ["Title", displayTitle(act)],
    ["Published", act.publicationDate],
    ["Document type", displayVocabularyTerm(act.type) || shortType(act.type)],
    ["Status", actState ? actState.label : "Not recorded"],
    ["Source", sourceLabel(act.source)]
  ];
  const permanentUrl = eliPageUrl(act);

  return e("div", { className: "detail-grid" },
    e("div", { className: "act-pill" }, "ID ", e("strong", null, displayLocalId(act))),
    e("div", { className: "detail-item" },
      e("div", { className: "detail-label" }, "Permanent link"),
      e("div", { className: "uri-row" },
        e("code", { className: "uri-value" }, permanentUrl || act.uri || "Not available"),
        e("button", {
          className: "copy-button",
          disabled: !(permanentUrl || act.uri),
          onClick: () => copyText(permanentUrl || act.uri),
          type: "button"
        }, "Copy")
      )
    ),
    fields.map(([label, value]) =>
      e("div", { key: label, className: "detail-item" },
        e("div", { className: "detail-label" }, label),
        e("div", { className: "detail-value" }, value || "Missing data")
      )
    ),
    e("div", { className: "detail-item" },
      e("div", { className: "detail-label" }, "Versions"),
      resourceLoading
        ? e("div", { className: "detail-value muted-line" }, "Loading versions\u2026")
        : e(LinkedNodeList, {
            empty: resourceError || "This act has no recorded versions",
            nodes: detail?.expressions || [],
            onNavigateResource
          })
    ),
    e("div", { className: "detail-item" },
      e("div", { className: "detail-label" }, "Formats"),
      resourceLoading
        ? e("div", { className: "detail-value muted-line" }, "Loading formats\u2026")
        : e(LinkedNodeList, {
            empty: resourceError || "This act has no recorded formats",
            nodes: detail?.manifestations || [],
            expressions: detail?.expressions || [],
            onNavigateResource
          })
    ),
    e("div", { className: "detail-item" },
      e("div", { className: "detail-label" }, "Related Resources"),
      resourceLoading
        ? e("div", { className: "detail-value muted-line" }, "Loading related acts\u2026")
        : e(LinkedRelationList, {
            empty: resourceError || "No links to other acts recorded yet",
            relations: userFacingRelations(detail),
            onNavigateResource
          })
    ),
    e("div", { className: "detail-item" },
      e("div", { className: "detail-label" }, "Normattiva Relations"),
      relations.length
        ? e("div", { className: "relation-list" },
            relations.map((row) =>
              e("div", { key: `${row.sourceUri}-${row.relationship}-${row.targetUri}`, className: "relation-chip" },
                e("span", null, relationSentence(act, row))
              )
            )
          )
        : e("div", { className: "detail-value muted-line" }, "No Normattiva relation recorded for this act")
    ),
    e("div", { className: "detail-actions" },
      permanentUrl ? e("a", { className: "action-link primary-link-inline", href: eliPagePath(act) },
        e(Icon, { name: "link" }),
        e("span", null, "Open act page")
      ) : null,
      e(LinkButton, { href: act.source || act.uri, icon: "external", label: "View on Gazzetta" }),
      displayLocalId(act) ? e(LinkButton, { href: actRdfUrl(act), icon: "link", label: "View RDF" }) : null,
      displayLocalId(act) ? e("a", { className: "action-link secondary-link-inline", href: `${actRdfUrl(act)}?download=true` },
        e(Icon, { name: "download" }),
        e("span", null, "Download TTL")
      ) : null,
      e("button", {
        className: "secondary-button",
        onClick: () => onRunSparqlForAct(act),
        type: "button"
      }, e(Icon, { name: "code" }), e("span", null, "Run SPARQL"))
    )
  );
}

function LinkedNodeList({ empty, expressions, nodes, onNavigateResource }) {
  const versionOf = (node) => {
    const parent = (expressions || []).find((expression) =>
      expression.uri && node.uri && String(node.uri).startsWith(`${expression.uri}/`));
    return parent ? displayVersionTerm(parent.version) || displayNodeTitle(parent) : "";
  };

  if (!nodes.length) {
    return e("div", { className: "detail-value muted-line" }, empty);
  }

  return e("div", { className: "resource-node-list" },
    nodes.map((node) =>
      e("button", {
        key: node.uri,
        className: "resource-link-button",
        onClick: () => onNavigateResource(node.uri),
        title: node.uri,
        type: "button"
      },
        e("span", { className: "resource-main" },
          displayFormatTerm(node.format) || displayNodeTitle(node),
          (() => {
            const state = inForceState(node);
            return state ? e("span", { className: `force-badge ${state.tone}` }, state.label) : null;
          })()
        ),
        e("span", { className: "resource-meta" },
          [displayVersionTerm(node.version) || versionOf(node), displayVocabularyTerm(node.language), node.format ? "" : displayFormatTerm(node.format)]
            .filter(Boolean)
            .join(" \u00b7 ") || compactUri(node.uri)
        )
      )
    )
  );
}

function LinkedRelationList({ empty, relations, onNavigateResource }) {
  if (!relations.length) {
    return e("div", { className: "detail-value muted-line" }, empty);
  }

  return e("div", { className: "linked-relation-list" },
    relations.map((relation) =>
      e("div", {
        key: `${relation.predicate}-${relation.resourceUri}`,
        className: relation.important ? "linked-relation-row key-relation" : "linked-relation-row"
      },
        e("span", { className: "relation-predicate" },
          e("span", { className: "relation-label" }, relation.displayLabel || relation.predicateLabel || compactRdfName(relation.predicate) || relation.predicate),
          e("span", { className: "relation-code" }, relation.predicateLabel || compactRdfName(relation.predicate) || relation.predicate),
          relation.important ? e("span", { className: "relation-importance" }, "Key") : null
        ),
        e("button", {
          className: "resource-link-button relation-resource",
          onClick: () => onNavigateResource(relation.resourceUri),
          title: relation.resourceUri,
          type: "button"
        },
          e("span", { className: "resource-main" }, relation.resourceLocalId || relation.resourceLabel || compactUri(relation.resourceUri)),
          e("span", { className: "resource-meta" }, relation.resourceLabel || compactUri(relation.resourceUri))
        )
      )
    )
  );
}

function userFacingRelations(detail) {
  const hiddenPredicates = new Set([
    "http://data.europa.eu/eli/ontology#is_realized_by",
    "http://data.europa.eu/eli/ontology#realizes",
    "http://data.europa.eu/eli/ontology#is_embodied_by",
    "http://data.europa.eu/eli/ontology#embodies",
    "http://purl.org/dc/terms/source",
    "http://data.europa.eu/eli/ontology#type_document",
    "http://data.europa.eu/eli/ontology#version",
    "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
  ]);
  return (detail?.outgoingRelations || [])
    .concat(detail?.incomingRelations || [])
    .filter((relation) => !hiddenPredicates.has(relation.predicate));
}

function LinkButton({ href, icon, label }) {
  return e("a", { className: "action-link", href, target: "_blank", rel: "noreferrer" },
    e(Icon, { name: icon || "external" }),
    e("span", null, label)
  );
}

function NormattivaPage({ acts, modifications }) {
  const titleFor = (localId, uri) => {
    const key = localId || localIdFromUri(uri);
    const match = (acts || []).find((act) => displayLocalId(act) === key);
    return match && match.title ? match.title : "";
  };

  return e(React.Fragment, null,
    e("section", { className: "panel normattiva-panel" },
      e("div", { className: "panel-heading" },
        e("div", null,
          e("p", { className: "section-label" }, "Linked Data"),
          e("h2", null, "Legal relationships"),
          e("p", { className: "panel-subtitle" }, "Verified act-to-act relations stored as RDF and served from the triple store.")
        ),
        e("span", { className: "result-count" }, `${modifications.length} link${modifications.length === 1 ? "" : "s"}`)
      ),
      modifications.length
        ? e("div", { className: "table-wrap" },
            e("table", { className: "results-table" },
              e("thead", null,
                e("tr", null,
                  e("th", null, "This act"),
                  e("th", null, "Relation"),
                  e("th", null, "Related act")
                )
              ),
              e("tbody", null,
                modifications.map((row) =>
                  e("tr", { key: `${row.sourceUri}-${row.relationship}-${row.targetUri}` },
                    e("td", { className: "relation-act-cell" },
                      e("a", { className: "act-ref-link mono", href: eliPagePath(row.sourceUri) || row.sourceUri },
                        displayRelationId(row.sourceLocalId, row.sourceUri)),
                      titleFor(row.sourceLocalId, row.sourceUri)
                        ? e("span", { className: "act-ref-title" }, titleFor(row.sourceLocalId, row.sourceUri))
                        : null
                    ),
                    e("td", null, e("span", { className: "relation-badge" }, relationshipLabel(row.relationship))),
                    e("td", { className: "relation-act-cell" },
                      e("a", { className: "act-ref-link mono", href: eliPagePath(row.targetUri) || row.targetUri },
                        displayRelationId(row.targetLocalId, row.targetUri)),
                      titleFor(row.targetLocalId, row.targetUri)
                        ? e("span", { className: "act-ref-title" }, titleFor(row.targetLocalId, row.targetUri))
                        : null
                    )
                  )
                )
              )
            )
          )
        : e("div", { className: "empty-state" }, "No act-to-act relations in the triple store yet")
    )
  );
}

function PipelineStatusItem({ count, label, state }) {
  return e("div", { className: "pipeline-status-item" },
    e("span", { className: "pipeline-status-count" }, count),
    e("span", { className: "pipeline-status-label" }, label),
    e("span", { className: "pipeline-status-note" }, state)
  );
}

function SparqlPage({ initialQuery }) {
  const [activeExample, setActiveExample] = useState(SPARQL_EXAMPLES[0].id);
  const [queryText, setQueryText] = useState(SPARQL_EXAMPLES[0].query);
  const [queryResult, setQueryResult] = useState(null);
  const [queryError, setQueryError] = useState("");
  const [queryRunning, setQueryRunning] = useState(false);

  const loadExample = (example) => {
    setActiveExample(example.id);
    setQueryText(example.query);
    setQueryError("");
  };

  useEffect(() => {
    if (initialQuery) {
      setActiveExample("");
      setQueryText(initialQuery);
      setQueryError("");
      setQueryResult(null);
    }
  }, [initialQuery]);

  const runQuery = async () => {
    setQueryRunning(true);
    setQueryError("");
    try {
      const response = await fetch("/api/sparql", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ query: queryText })
      });
      const result = await response.json();
      if (!response.ok || result.error) {
        throw new Error(result.error || "Query error: missing prefix or invalid syntax.");
      }
      setQueryResult(result);
    } catch (err) {
      setQueryResult(null);
      setQueryError(err.message || "Query error: missing prefix or invalid syntax.");
    } finally {
      setQueryRunning(false);
    }
  };

  return e("section", { className: "panel sparql-panel" },
    e("div", { className: "panel-heading sparql-heading" },
      e("div", null,
        e("p", { className: "section-label" }, "RDF query"),
        e("h2", null, "SPARQL queries"),
        e("p", { className: "panel-subtitle" }, "Queries run against the RDF files loaded in Jena.")
      )
    ),
    e("div", { className: "query-layout" },
      e("div", { className: "query-row" },
        e("span", { className: "query-label" }, "Example Queries"),
        e("div", { className: "query-buttons" },
          SPARQL_EXAMPLES.map((example) =>
            e("button", {
              key: example.id,
              className: activeExample === example.id ? "example-button active" : "example-button",
              onClick: () => loadExample(example),
              type: "button"
            }, example.label)
          )
        )
      ),
      e("label", { className: "query-editor-label" }, "SPARQL Query",
        e("textarea", {
          className: "query-editor",
          value: queryText,
          onChange: (event) => {
            setQueryText(event.target.value);
            setActiveExample("");
          },
          spellCheck: "false"
        })
      ),
      queryError ? e("div", { className: "query-error" }, queryError) : null,
      e("div", { className: "query-actions" },
        e("button", { disabled: queryRunning, onClick: runQuery, type: "button" },
          e(Icon, { name: "play" }),
          e("span", null, queryRunning ? "Running..." : "Run Query")
        ),
        e("button", {
          className: "secondary-button",
          onClick: () => {
            setQueryText("");
            setQueryResult(null);
            setQueryError("");
            setActiveExample("");
          },
          type: "button"
        }, e(Icon, { name: "refresh" }), e("span", null, "Clear")),
        e("button", {
          className: "secondary-button",
          onClick: () => copyText(queryText),
          type: "button"
        }, e(Icon, { name: "copy" }), e("span", null, "Copy Query")),
      ),
      e(QueryResults, { result: queryResult })
    )
  );
}

function TechnicalPage({
  archiveEndDate,
  archiveError,
  archiveLimit,
  archiveResult,
  archiveRunning,
  archiveStartDate,
  automationStatus,
  crawlStatus,
  ingestionRuns = [],
  loadedFileCount,
  missingFileCount,
  normattivaAutomationStatus,
  normattivaDetails,
  normattivaDetailError,
  normattivaDetailFetchResult,
  normattivaDetailRunning,
  normattivaEvidence,
  normattivaEvidenceError,
  normattivaEvidenceRunning,
  normattivaEvidenceScanResult,
  normattivaCandidateError,
  normattivaCandidateRunResult,
  normattivaCandidateRunning,
  normattivaError,
  normattivaImportError,
  normattivaImportResult,
  normattivaImportRunning,
  normattivaRelationCandidates,
  normattivaRunning,
  normattivaUpdateResult,
  normattivaUpdates,
  rdfSources,
  status,
  updateError,
  updateResult,
  updateRunning,
  onArchiveEndDateChange,
  onArchiveLimitChange,
  onArchiveStartDateChange,
  onImportNormattivaDetails,
  onImportNormattivaUpdates,
  onRunArchiveCrawl,
  onRunArchiveDiscover,
  onRunNormattivaDetailFetch,
  onRunNormattivaEvidenceScan,
  onRunNormattivaRelationCandidates,
  onRunNormattivaUpdate,
  onRunUpdate
}) {
  return e(React.Fragment, null,
    e("section", { className: "metric-grid compact-metrics", "aria-label": "System status" },
      e(MetricTile, { icon: "database", label: "Triples", value: status?.triples ?? "...", note: "legal RDF facts loaded in Jena" }),
      e(MetricTile, { icon: "file", label: "RDF Files", value: loadedFileCount, note: "active sources" }),
      e(MetricTile, { icon: "registry", label: "Registry", value: crawlStatus?.registryRecords ?? "...", note: "tracked acts" })
    ),
    e(CrawlerStatusPanel, { automationStatus, crawlStatus, updateError, updateResult, updateRunning, onRunUpdate }),
    e(NormattivaAutomationPanel, {
      normattivaAutomationStatus,
      normattivaDetails,
      normattivaDetailError,
      normattivaDetailFetchResult,
      normattivaDetailRunning,
      normattivaEvidence,
      normattivaEvidenceError,
      normattivaEvidenceRunning,
      normattivaEvidenceScanResult,
      normattivaCandidateError,
      normattivaCandidateRunResult,
      normattivaCandidateRunning,
      normattivaError,
      normattivaImportError,
      normattivaImportResult,
      normattivaImportRunning,
      normattivaRelationCandidates,
      normattivaRunning,
      normattivaUpdateResult,
      normattivaUpdates,
      onImportNormattivaDetails,
      onImportNormattivaUpdates,
      onRunNormattivaDetailFetch,
      onRunNormattivaEvidenceScan,
      onRunNormattivaRelationCandidates,
      onRunNormattivaUpdate
    }),
    e(SourcePanel, { rdfSources }),
    e(IngestionHistoryPanel, { runs: ingestionRuns })
  );
}

function NormattivaAutomationPanel({
  normattivaAutomationStatus,
  normattivaDetails = [],
  normattivaDetailError,
  normattivaDetailFetchResult,
  normattivaDetailRunning,
  normattivaEvidence = [],
  normattivaEvidenceError,
  normattivaEvidenceRunning,
  normattivaEvidenceScanResult,
  normattivaCandidateError,
  normattivaCandidateRunResult,
  normattivaCandidateRunning,
  normattivaError,
  normattivaImportError,
  normattivaImportResult,
  normattivaImportRunning,
  normattivaRelationCandidates = [],
  normattivaRunning,
  normattivaUpdateResult,
  normattivaUpdates = [],
  onImportNormattivaDetails,
  onImportNormattivaUpdates,
  onRunNormattivaDetailFetch,
  onRunNormattivaEvidenceScan,
  onRunNormattivaRelationCandidates,
  onRunNormattivaUpdate
}) {
  return e("section", { className: "panel crawler-panel" },
    e("div", { className: "panel-heading" },
      e("div", null,
        e("p", { className: "section-label" }, "Normattiva"),
        e("h2", null, "Amendment discovery"),
        e("p", { className: "panel-subtitle" }, "Finds acts that Normattiva has amended. Each step feeds the next, and nothing is written to the graph until a relation has been reviewed.")
      )
    ),
    e("ol", { className: "pipeline-steps" },
      [
        {
          n: 1,
          title: "Find changed acts",
          note: "Asks the Normattiva OpenData API which acts changed in the update window.",
          count: normattivaUpdates.length,
          countLabel: "acts found",
          run: onRunNormattivaUpdate,
          running: normattivaRunning,
          runLabel: "Find changed acts",
          busyLabel: "Searching\u2026",
          alt: { run: onImportNormattivaUpdates, running: normattivaImportRunning, label: "Import from file", busy: "Importing\u2026" }
        },
        {
          n: 2,
          title: "Fetch act details",
          note: "Downloads the full record for each act found in step 1.",
          count: normattivaDetails.length,
          countLabel: "details fetched",
          run: onRunNormattivaDetailFetch,
          running: normattivaDetailRunning,
          runLabel: "Fetch details",
          busyLabel: "Fetching\u2026",
          alt: { run: onImportNormattivaDetails, running: normattivaImportRunning, label: "Import from file", busy: "Importing\u2026" }
        },
        {
          n: 3,
          title: "Scan for relation evidence",
          note: "Looks through the fetched text for wording that names another act.",
          count: normattivaEvidence.length,
          countLabel: "passages found",
          run: onRunNormattivaEvidenceScan,
          running: normattivaEvidenceRunning,
          runLabel: "Scan evidence",
          busyLabel: "Scanning\u2026"
        },
        {
          n: 4,
          title: "Propose relations for review",
          note: "Turns evidence into candidate links. These are not added to the graph until you approve them.",
          count: normattivaRelationCandidates.length,
          countLabel: "awaiting review",
          run: onRunNormattivaRelationCandidates,
          running: normattivaCandidateRunning,
          runLabel: "Propose relations",
          busyLabel: "Working\u2026"
        }
      ].map((step) =>
        e("li", { key: step.n, className: "pipeline-step" },
          e("span", { className: "pipeline-step-number", "aria-hidden": "true" }, step.n),
          e("div", { className: "pipeline-step-body" },
            e("h3", null, step.title),
            e("p", null, step.note),
            e("p", { className: "pipeline-step-count" },
              e("strong", null, step.count), " ", step.countLabel)
          ),
          e("div", { className: "pipeline-step-actions" },
            e("button", {
              className: "secondary-button",
              disabled: step.running,
              onClick: step.run,
              type: "button"
            }, step.running ? step.busyLabel : step.runLabel),
            step.alt ? e("button", {
              className: "tertiary-button",
              disabled: step.alt.running,
              onClick: step.alt.run,
              type: "button"
            }, step.alt.running ? step.alt.busy : step.alt.label) : null
          )
        )
      )
    ),
    e("div", { className: "automation-summary" },
      e("div", { className: normattivaAutomationStatus?.enabled ? "automation-badge" : "automation-badge muted" },
        normattivaAutomationStatus
          ? (normattivaAutomationStatus.enabled ? "Automatic Normattiva updates enabled" : "Automatic Normattiva updates disabled")
          : "Loading Normattiva schedule"
      ),
      e("div", { className: "automation-details" },
        e("span", null, "Runs ", e("strong", null, describeSchedule(normattivaAutomationStatus?.cron))),
        e("span", null, "Zone: ", e("strong", null, normattivaAutomationStatus?.zone || "...")),
        e("span", null, "Last scheduled run: ", e("strong", null, formatShortDate(normattivaAutomationStatus?.lastTriggeredAt) || "not run yet")),
        e("span", null, "State: ", e("strong", null, normattivaAutomationStatus?.lastState || "..."))
      )
    ),
    (function () {
      const rows = [
        ["Find changed acts", normattivaError, normattivaUpdateResult, (r) => [
          ["result", r.state], ["acts found", r.updatesRead], ["relations written", r.relationRows]]],
        ["Import from file", normattivaImportError, normattivaImportResult, (r) => [
          ["result", r.state], ["rows", r.rowsWritten], ["output", compactPath(r.outputPath)]]],
        ["Fetch act details", normattivaDetailError, normattivaDetailFetchResult, (r) => [
          ["result", r.state], ["candidates read", r.candidatesRead], ["details written", r.detailsWritten]]],
        ["Scan for evidence", normattivaEvidenceError, normattivaEvidenceScanResult, (r) => [
          ["result", r.state], ["details read", r.detailsRead], ["passages found", r.evidenceRows]]],
        ["Propose relations", normattivaCandidateError, normattivaCandidateRunResult, (r) => [
          ["result", r.state], ["evidence rows", r.evidenceRowsRead], ["candidates", r.candidatesWritten]]]
      ];
      const active = rows.filter(([, runError, result]) => runError || result);
      if (!active.length) {
        return e("div", { className: "run-log" },
          e("div", { className: "run-log-row" },
            e("span", { className: "muted-line" },
              "No step has been run in this session yet. Run results appear here.")));
      }
      return e("div", { className: "run-log" },
        active.map(([label, runError, result, describe]) =>
          e("div", { key: label, className: runError ? "run-log-row failed" : "run-log-row" },
            e("span", { className: "run-log-step" }, label),
            runError
              ? e("span", { className: "update-error" }, runError)
              : e("span", { className: "run-summary" },
                  describe(result).map(([name, value]) =>
                    e("span", { key: name }, name, ": ", e("strong", null, String(value ?? "\u2014")))))
          )
        )
      );
    })()
  );
}

function IngestionHistoryPanel({ runs }) {
  return e("section", { className: "panel crawler-panel" },
    e("div", { className: "panel-heading" },
      e("div", null,
        e("p", { className: "section-label" }, "Audit"),
        e("h2", null, "Ingestion history"),
        e("p", { className: "panel-subtitle" }, "Every scheduled run, recorded to disk so the record survives a restart.")
      ),
      e("span", { className: "result-count" }, `${runs.length} run${runs.length === 1 ? "" : "s"}`)
    ),
    runs.length
      ? e("div", { className: "table-wrap" },
          e("table", { className: "results-table" },
            e("thead", null,
              e("tr", null,
                e("th", null, "Started"),
                e("th", null, "Source"),
                e("th", null, "Outcome"),
                e("th", null, "Fetched"),
                e("th", null, "Loaded"),
                e("th", null, "Failed")
              )
            ),
            e("tbody", null,
              runs.map((run) =>
                e("tr", { key: run.runId },
                  e("td", { className: "mono" }, formatShortDate(run.startedAt) || "\u2014"),
                  e("td", null, run.source),
                  e("td", null, e("span", { className: `run-state ${runStateTone(run.state)}` },
                    humanizeToken(run.state))),
                  e("td", { className: "mono" }, run.itemsFetched),
                  e("td", { className: "mono" }, run.itemsLoaded),
                  e("td", { className: "mono" }, run.itemsFailed)
                )
              )
            )
          )
        )
      : e("div", { className: "empty-state" },
          "No scheduled run has been recorded yet. Runs appear here as they happen.")
  );
}

function runStateTone(state) {
  if (state === "COMPLETED") {
    return "ok";
  }
  if (state === "FAILED") {
    return "bad";
  }
  return "warn";
}

function WorkflowPanel({ status }) {
  const loadedFiles = status?.loadedFiles?.length ?? 0;
  const triples = status?.triples ?? "...";
  const steps = [
    ["rss", "Discover", "RSS/archive", "latest links"],
    ["globe", "Crawl", "Gazzetta pages", "HTML snapshots"],
    ["clean", "Clean", "metadata fields", "stable records"],
    ["file", "Generate RDF", "Turtle files", `${triples} triples`],
    ["cloud", "Explore", "Jena/API/SPARQL", `${loadedFiles} RDF files`]
  ];

  return e("section", { className: "panel workflow-panel" },
    e("div", { className: "panel-heading" },
      e("div", null,
        e("p", { className: "section-label" }, "Pipeline"),
        e("h2", null, "Discover to explore")
      )
    ),
    e("div", { className: "workflow-track" },
      steps.map(([icon, title, primary, secondary], index) =>
        e("div", { key: title, className: "workflow-step" },
          e("div", { className: "step-icon", "aria-hidden": "true" }, e(Icon, { name: icon })),
          e("div", null,
            e("h3", null, title),
            e("p", null, primary),
            e("span", null, secondary)
          ),
          index < steps.length - 1 ? e("div", { className: "step-arrow", "aria-hidden": "true" }, "->") : null
        )
      )
    )
  );
}

function CrawlerStatusPanel({ automationStatus, crawlStatus, updateError, updateResult, updateRunning, onRunUpdate }) {
  const statusCounts = crawlStatus?.registryStatusCounts || {};
  const countEntries = Object.entries(statusCounts);

  return e("section", { className: "panel crawler-panel" },
    e("div", { className: "panel-heading" },
      e("div", null,
        e("p", { className: "section-label" }, "Crawler"),
        e("h2", null, "Gazzetta crawler")
      ),
      e("button", {
        className: "secondary-button",
        disabled: updateRunning,
        onClick: onRunUpdate,
        type: "button"
      }, updateRunning ? "Running..." : "Run Update Check")
    ),
    e("div", { className: "crawler-grid" },
      e(StatusCard, { icon: "link", label: "RSS Links", value: crawlStatus?.rssLinkCount ?? "...", note: formatShortDate(crawlStatus?.rssFetchDate) || "latest feed rows" }),
      e(StatusCard, { icon: "file", label: "Raw Snapshots", value: crawlStatus?.rawSnapshotCount ?? "...", note: "saved HTML files" }),
      e(StatusCard, { icon: "database", label: "Delta RDF", value: formatBytes(crawlStatus?.rdfDeltaBytes), note: formatShortDate(crawlStatus?.rdfDeltaLastModified) || "generated file" }),
      e(StatusCard, { icon: "calendar", label: "Last Checked", value: formatShortDate(crawlStatus?.lastCheckedAt) || "...", note: crawlStatus?.latestPublicationDate || "publication date" })
    ),
    e("div", { className: "status-counts" },
      countEntries.length
        ? countEntries.map(([name, count]) =>
            e("span", { key: name, className: "count-chip" }, name, ": ", e("strong", null, count))
          )
        : e("span", { className: "muted-line" }, "No registry status counts available")
    ),
    e("div", { className: "automation-summary" },
      e("div", { className: automationStatus?.enabled ? "automation-badge" : "automation-badge muted" },
        automationStatus ? (automationStatus.enabled ? "Automatic updates enabled" : "Automatic updates disabled") : "Loading automation schedule"
      ),
      e("div", { className: "automation-details" },
        e("span", null, "Runs ", e("strong", null, describeSchedule(automationStatus?.cron))),
        e("span", null, "Zone: ", e("strong", null, automationStatus?.zone || "...")),
        e("span", null, "RSS entries: ", e("strong", null, automationStatus?.maxEntries ?? "...")),
        e("span", null, "Last scheduled run: ", e("strong", null, formatShortDate(automationStatus?.lastTriggeredAt) || "not run yet"))
      )
    ),
    e("div", { className: "update-result" },
      updateError
        ? e("span", { className: "update-error" }, updateError)
        : updateResult
          ? e("div", { className: "run-summary" },
              e("span", null, "Last run: ", e("strong", null, updateResult.state)),
              e("span", null, "RSS read: ", e("strong", null, updateResult.rssEntriesRead)),
              e("span", null, "added: ", e("strong", null, updateResult.rssEntriesAdded)),
              e("span", null, "crawled: ", e("strong", null, updateResult.linksCrawled)),
              e("span", null, "changed: ", e("strong", null, updateResult.changedRecords))
            )
          : e("span", { className: "muted-line" }, "No manual check run yet in this session")
    )
  );
}

function ArchiveStatusPanel({
  archiveEndDate,
  archiveError,
  archiveLimit,
  archiveResult,
  archiveRunning,
  archiveStartDate,
  onArchiveEndDateChange,
  onArchiveLimitChange,
  onArchiveStartDateChange,
  onRunArchiveCrawl,
  onRunArchiveDiscover
}) {
  const availableLinks = Number(archiveResult?.linksAvailable || 0);
  const limitHint = availableLinks > 0 ? `Available pending links: ${availableLinks}` : "Run discovery to load archive links.";
  const updateArchiveLimit = (value) => {
    const numericValue = Number(value);
    if (!Number.isFinite(numericValue)) {
      onArchiveLimitChange(value);
      return;
    }
    if (availableLinks > 0 && numericValue > availableLinks) {
      onArchiveLimitChange(String(availableLinks));
      return;
    }
    onArchiveLimitChange(value);
  };

  return e("section", { className: "panel archive-panel" },
    e("div", { className: "panel-heading" },
      e("div", null,
        e("p", { className: "section-label" }, "Historical archive"),
        e("h2", null, "Backfill older Gazzetta acts"),
        e("p", { className: "panel-subtitle" }, "Discover archive links first, then crawl a controlled batch into the existing RDF pipeline.")
      )
    ),
    e("div", { className: "archive-controls" },
      e("label", null, "Start Date",
        e("input", {
          type: "date",
          value: archiveStartDate,
          onChange: (event) => onArchiveStartDateChange(event.target.value)
        })
      ),
      e("label", null, "End Date",
        e("input", {
          type: "date",
          value: archiveEndDate,
          onChange: (event) => onArchiveEndDateChange(event.target.value)
        })
      ),
      e("button", {
        disabled: archiveRunning,
        onClick: onRunArchiveDiscover,
        type: "button"
      }, e(Icon, { name: "search" }), e("span", null, archiveRunning ? "Running..." : "Discover Archive Links")),
      e("label", null, "Batch Limit",
        e("input", {
          max: availableLinks > 0 ? String(availableLinks) : undefined,
          min: "0",
          type: "number",
          value: archiveLimit,
          onChange: (event) => updateArchiveLimit(event.target.value)
        }),
        e("span", { className: "field-hint" }, limitHint)
      ),
      e("button", {
        className: "secondary-button",
        disabled: archiveRunning,
        onClick: onRunArchiveCrawl,
        type: "button"
      }, e(Icon, { name: "cloud" }), e("span", null, archiveRunning ? "Running..." : "Crawl Archive Batch"))
    ),
    e("div", { className: "archive-result" },
      archiveError
        ? e("span", { className: "update-error" }, archiveError)
        : archiveResult
          ? e("div", { className: "run-summary" },
              e("span", null, "Last action: ", e("strong", null, archiveResult.action)),
              e("span", null, "state: ", e("strong", null, archiveResult.state)),
              archiveResult.startDate ? e("span", null, "range: ", e("strong", null, `${archiveResult.startDate} to ${archiveResult.endDate}`)) : null,
              e("span", null, "discovered: ", e("strong", null, archiveResult.discoveredLinks)),
              e("span", null, "available: ", e("strong", null, archiveResult.linksAvailable)),
              e("span", null, "crawled: ", e("strong", null, archiveResult.linksCrawled)),
              e("span", null, "changed: ", e("strong", null, archiveResult.changedRecords))
            )
          : e("span", { className: "muted-line" }, "No archive action has been run from this screen yet.")
    )
  );
}

function StatusCard({ icon, label, value, note }) {
  return e("div", { className: "status-card" },
    e("div", { className: "status-icon", "aria-hidden": "true" }, e(Icon, { name: icon || "file" })),
    e("div", null,
      e("div", { className: "metric-label" }, label),
      e("div", { className: "status-card-value" }, value),
      e("div", { className: "metric-note" }, note)
    )
  );
}

function SourcePanel({ rdfSources }) {
  return e("section", { className: "panel" },
    e("div", { className: "panel-heading" },
      e("div", null,
        e("p", { className: "section-label" }, "RDF sources"),
        e("h2", null, "Loaded RDF files")
      )
    ),
    rdfSources?.length
      ? e("div", { className: "table-wrap" },
          e("table", { className: "results-table source-table" },
            e("thead", null,
              e("tr", null,
                e("th", null, "Status"),
                e("th", null, "File Name"),
                e("th", null, "Description"),
                e("th", null, "Last Modified"),
                e("th", null, "Size"),
                e("th", null, "Action")
              )
            ),
            e("tbody", null,
              rdfSources.map((row) =>
                e("tr", { key: row.fileName },
                  e("td", null, e("span", { className: row.status === "Loaded" ? "source-state loaded" : "source-state missing" }, row.status)),
                  e("td", { className: "mono strong-cell" }, row.fileName),
                  e("td", null, row.description),
                  e("td", null, formatShortDate(row.lastModified) || "Missing data"),
                  e("td", null, formatBytes(row.sizeBytes)),
                  e("td", null,
                    row.status === "Loaded"
                      ? e("a", {
                          className: "icon-button",
                          href: row.downloadUrl,
                          title: `Download ${row.fileName}`
                        }, e(Icon, { name: "download" }))
                      : e("span", { className: "muted-line" }, "Unavailable")
                  )
                )
              )
            )
          )
        )
      : e("div", { className: "empty-state" }, "No source files reported")
  );
}

function QueryResults({ result }) {
  const rows = result?.rows || [];
  const columns = result?.columns || [];

  return e("section", { className: "query-results" },
    e("div", { className: "query-results-heading" },
      e("h3", null, "Query Results", rows.length ? ` (${rows.length})` : ""),
      e("span", { className: "result-count" }, rows.length ? "Results format: Table" : "No query has been run yet")
    ),
    rows.length
      ? e("div", { className: "table-wrap" },
          e("table", { className: "results-table sparql-results-table" },
            e("thead", null,
              e("tr", null, columns.map((column) => e("th", { key: column }, column)))
            ),
            e("tbody", null,
              rows.map((row, index) =>
                e("tr", { key: index },
                  columns.map((column) =>
                    e("td", { key: column, className: isLikelyUri(row[column]) ? "mono small-uri" : "" },
                      formatSparqlCell(row[column])
                    )
                  )
                )
              )
            )
          )
        )
      : e("div", { className: "empty-state query-empty" }, "Run a predefined or custom query to see results here."),
    e("div", { className: "query-tip" },
      e("strong", null, "Tip: "),
      "Use the example queries above or write your own SPARQL query to explore the dataset."
    )
  );
}

function hasDisplayMetadata(act) {
  return Boolean(displayLocalId(act) || act.title || act.publicationDate || act.source);
}

function displayLocalId(act) {
  if (!act) {
    return "";
  }
  return act.localId || localIdFromUri(act.uri) || "Unknown ID";
}

function displayTitle(act) {
  if (!act) {
    return "Missing title";
  }
  return act.title || "No title in the published metadata";
}

function displayRelationId(localId, uri) {
  const text = localId || uri || "";
  const urnMarker = "urn:nir:stato:";
  const urnIndex = text.indexOf(urnMarker);
  if (urnIndex >= 0) {
    return text.slice(urnIndex + urnMarker.length);
  }
  return text;
}

function displayVocabularyTerm(uri) {
  if (!uri) {
    return "";
  }
  const raw = String(uri);
  const token = raw.includes("#") ? raw.slice(raw.lastIndexOf("#") + 1) : raw.slice(raw.lastIndexOf("/") + 1);
  return humanizeToken(token) || raw;
}

const IN_FORCE_AUTHORITY = "http://publications.europa.eu/resource/authority/eli-in-force/";

function inForceState(node) {
  const value = node && (node.inForce || node.in_force);
  if (!value) {
    return null;
  }
  const token = String(value).slice(String(value).lastIndexOf("/") + 1).toUpperCase();
  if (token === "IN_FORCE") {
    return { label: "In force", tone: "current" };
  }
  if (token === "NOT_IN_FORCE") {
    return { label: "No longer in force", tone: "superseded" };
  }
  if (token === "PARTIALLY_IN_FORCE") {
    return { label: "Partly in force", tone: "partial" };
  }
  return { label: humanizeToken(token), tone: "partial" };
}

function displayVersionTerm(uri) {
  if (!uri) {
    return "";
  }
  const raw = String(uri);
  const token = raw.includes("#") ? raw.slice(raw.lastIndexOf("#") + 1) : raw.slice(raw.lastIndexOf("/") + 1);
  const vigenza = token.match(/^VIGENZA_(\d{4})(\d{2})(\d{2})_V(\d+)$/i);
  if (vigenza) {
    return `In force from ${vigenza[1]}-${vigenza[2]}-${vigenza[3]} \u00b7 version ${vigenza[4]}`;
  }
  const original = token.match(/^ORIGINALE_V(\d+)$/i);
  if (original) {
    return "Original text as published";
  }
  return humanizeToken(token) || raw;
}

function displayFormatTerm(uri) {
  if (!uri) {
    return "";
  }
  const raw = String(uri);
  const media = raw.match(/media-types\/([\w.+-]+)\/([\w.+-]+)$/);
  if (media) {
    return `${media[2].toUpperCase()} document`;
  }
  return displayVocabularyTerm(raw);
}

function eliPagePath(uriOrAct) {
  const uri = typeof uriOrAct === "string" ? uriOrAct : uriOrAct?.uri;
  if (!uri) {
    return "";
  }
  const match = String(uri).match(/\/eli\/(id|ontology)?\/?(\d{4})\/(\d{2})\/(\d{2})\/([^/]+)\/([^/?#]+)(\/[^?#]*)?/);
  if (!match) {
    return "";
  }
  const tail = match[7] ? match[7].replace(/\/+$/, "") : "";
  return `/eli/id/${match[2]}/${match[3]}/${match[4]}/${match[5]}/${match[6]}${tail}`;
}

function eliPageUrl(uriOrAct) {
  const path = eliPagePath(uriOrAct);
  if (!path) {
    return "";
  }
  return `${window.location.origin}${path}`;
}

function localIdFromUri(uri) {
  if (!uri) {
    return "";
  }
  const parts = String(uri).split("/");
  return parts.length >= 2 && parts[parts.length - 1].toLowerCase() === "sg" ? parts[parts.length - 2] : "";
}

function sourceLabel(source) {
  return source ? "Gazzetta Ufficiale" : "Relation RDF";
}

const RELATION_LABELS = {
  "eli:commences": "Commences / converts",
  "eli:commenced_by": "Commenced / converted by",
  "eli:amends": "Amends",
  "eli:amended_by": "Amended by",
  "eli:repeals": "Repeals",
  "eli:repealed_by": "Repealed by",
  "eli:cites": "Cites",
  "eli:is_realized_by": "Has version",
  "eli:realizes": "Version of",
  "eli:is_embodied_by": "Has format",
  "eli:is_embodied_in": "Format of",
  "ilg:modifies": "Modifies",
  "ilg:modifiedBy": "Modified by",
  "conversion": "Commences / converts"
};

function relationshipLabel(value) {
  if (!value) {
    return "related to";
  }
  const key = String(value).trim();
  if (RELATION_LABELS[key]) {
    return RELATION_LABELS[key];
  }
  const local = key.includes("#") ? key.slice(key.lastIndexOf("#") + 1) : key.slice(key.lastIndexOf("/") + 1);
  const compact = local.includes(":") ? local.slice(local.indexOf(":") + 1) : local;
  return humanizeToken(compact);
}

function humanizeToken(value) {
  if (!value) {
    return "";
  }
  const spaced = String(value)
    .replace(/[_-]+/g, " ")
    .replace(/([a-z\d])([A-Z])/g, "$1 $2")
    .trim();
  if (!spaced) {
    return "";
  }
  return spaced.charAt(0).toUpperCase() + spaced.slice(1).toLowerCase();
}

function compactPath(value) {
  if (!value) {
    return "-";
  }
  const parts = String(value).replaceAll("\\", "/").split("/");
  return parts.slice(-3).join("/");
}

function describeSchedule(cron) {
  if (!cron) {
    return "on a schedule";
  }
  const parts = String(cron).trim().split(/\s+/);
  if (parts.length === 6 && /^\d+$/.test(parts[1]) && /^\d+$/.test(parts[2]) && parts[3] === "*" && parts[5] === "*") {
    const hh = String(parts[2]).padStart(2, "0");
    const mm = String(parts[1]).padStart(2, "0");
    return `every day at ${hh}:${mm}`;
  }
  return `on schedule ${cron}`;
}

function formatBytes(bytes) {
  if (bytes === null || bytes === undefined) {
    return "...";
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  return `${Math.round(bytes / 1024)} KB`;
}

function formatShortDate(value) {
  if (!value) {
    return "";
  }
  return String(value).replace("T", " ").slice(0, 16);
}

function formatDisplayDate(value) {
  if (!value || value === "unknown") {
    return "unknown";
  }
  const date = new Date(`${value}T00:00:00`);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
}

function relatedModifications(act, modifications) {
  if (!act) {
    return [];
  }
  const localId = displayLocalId(act);
  const uri = act.uri;
  return (modifications || []).filter((row) =>
    row.sourceLocalId === localId ||
    row.targetLocalId === localId ||
    row.sourceUri === uri ||
    row.targetUri === uri
  );
}

function relationSentence(act, row) {
  const localId = displayLocalId(act);
  const isSource = row.sourceLocalId === localId || row.sourceUri === act.uri;
  const other = isSource
    ? displayRelationId(row.targetLocalId, row.targetUri)
    : displayRelationId(row.sourceLocalId, row.sourceUri);
  return isSource ? `Modifies ${other}` : `Modified by ${other}`;
}

function normattivaStatus(act, modifications) {
  const localId = displayLocalId(act);
  const uri = act.uri;
  const linked = (modifications || []).some((row) =>
    row.sourceLocalId === localId ||
    row.targetLocalId === localId ||
    row.sourceUri === uri ||
    row.targetUri === uri
  );
  return linked ? "Linked in loaded Normattiva relations" : "No loaded Normattiva relation";
}

function displayNodeTitle(node) {
  return node?.localId || node?.label || compactUri(node?.uri) || "Resource";
}

function compactUri(uri) {
  if (!uri) {
    return "";
  }
  const compact = compactRdfName(uri);
  if (compact) {
    return compact;
  }
  const text = String(uri).replace(/\/$/, "");
  const hash = text.lastIndexOf("#");
  if (hash >= 0) {
    return text.slice(hash + 1);
  }
  const slash = text.lastIndexOf("/");
  return slash >= 0 ? text.slice(slash + 1) : text;
}

function actRdfUrl(act) {
  return `/api/acts/${encodeURIComponent(displayLocalId(act))}/rdf`;
}

function createActSparqlQuery(act) {
  const uri = act?.uri || "";
  const localId = displayLocalId(act);
  if (uri) {
    return `PREFIX eli: <http://data.europa.eu/eli/ontology#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX dcterms: <http://purl.org/dc/terms/>

SELECT ?property ?value WHERE {
  <${uri}> ?property ?value .
}
LIMIT 50`;
  }
  return `PREFIX eli: <http://data.europa.eu/eli/ontology#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?act ?property ?value WHERE {
  ?act eli:id_local "${escapeQuery(localId)}" ;
       ?property ?value .
}
LIMIT 50`;
}

function copyText(value) {
  if (!value || !navigator.clipboard) {
    return;
  }
  navigator.clipboard.writeText(value).catch(() => {});
}

function shortType(type) {
  if (!type) {
    return "";
  }
  const hash = type.lastIndexOf("#");
  if (hash >= 0) {
    return type.slice(hash + 1);
  }
  const slash = type.lastIndexOf("/");
  return slash >= 0 ? type.slice(slash + 1) : type;
}

function yearFromDate(date) {
  return date ? String(date).slice(0, 4) : "";
}

function uniqueSorted(values) {
  return Array.from(new Set(values)).sort().reverse();
}

function fileName(path) {
  const parts = String(path).split(/[\\/]/);
  return parts[parts.length - 1] || path;
}

function formatSparqlCell(value) {
  if (!value) {
    return "";
  }
  const text = String(value);
  const compact = compactRdfName(text);
  if (compact) {
    return compact;
  }
  if (text.includes("gazzettaufficiale.it/eli/id/")) {
    return localIdFromUri(text) || text;
  }
  if (text.includes("resource-type#")) {
    return shortType(text);
  }
  if (text.includes("dcterms/source")) {
    return "source";
  }
  return text;
}

function compactRdfName(text) {
  if (!text) {
    return "";
  }
  const value = String(text);
  const namespaces = [
    ["http://data.europa.eu/eli/ontology#", "eli:"],
    ["http://purl.org/dc/terms/", "dcterms:"],
    ["http://www.w3.org/2000/01/rdf-schema#", "rdfs:"],
    ["http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:"],
    ["http://schema.org/", "schema:"],
    ["http://example.org/italian-legislation/ontology#", "ilg:"],
    ["http://www.gazzettaufficiale.it/eli/tables/resource-type#", ""],
    ["http://www.gazzettaufficiale.it/eli/tables/versions#", ""]
  ];
  const match = namespaces.find(([namespace]) => value.startsWith(namespace));
  return match ? `${match[1]}${value.slice(match[0].length)}` : "";
}

function isLikelyUri(value) {
  return String(value || "").startsWith("http://") || String(value || "").startsWith("https://");
}

function escapeQuery(value) {
  return String(value || "").replace(/\\/g, "\\\\").replace(/"/g, "\\\"");
}

function Icon({ name }) {
  const paths = {
    building: ["M3 10h18", "M5 10v9", "M9 10v9", "M15 10v9", "M19 10v9", "M4 19h16", "M12 4 4 8h16z"],
    grid: ["M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h6v6h-6z"],
    link: ["M10 13a5 5 0 0 0 7.1 0l2-2a5 5 0 0 0-7.1-7.1l-1.2 1.2", "M14 11a5 5 0 0 0-7.1 0l-2 2a5 5 0 0 0 7.1 7.1l1.2-1.2"],
    code: ["M8 9 4 12l4 3", "M16 9l4 3-4 3", "M14 5l-4 14"],
    pulse: ["M3 12h4l2-6 4 12 2-6h6"],
    file: ["M6 3h8l4 4v14H6z", "M14 3v5h5", "M9 13h6", "M9 17h6"],
    calendar: ["M7 3v4", "M17 3v4", "M4 8h16", "M5 5h14v16H5z"],
    clock: ["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z", "M12 6v6l4 2"],
    database: ["M4 6c0 2 16 2 16 0", "M4 6c0-2 16-2 16 0v12c0 2-16 2-16 0z", "M4 12c0 2 16 2 16 0"],
    alert: ["M12 9v4", "M12 17h.01", "M10.3 3.9 2 18h20L13.7 3.9a2 2 0 0 0-3.4 0z"],
    registry: ["M4 5h16v14H4z", "M8 9h8", "M8 13h8", "M8 17h4"],
    rss: ["M5 19h.01", "M5 12a7 7 0 0 1 7 7", "M5 5a14 14 0 0 1 14 14"],
    globe: ["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z", "M2 12h20", "M12 2c3 3 3 17 0 20", "M12 2c-3 3-3 17 0 20"],
    clean: ["M4 14l6 6L20 6", "M15 6l3-3 3 3"],
    cloud: ["M7 18h10a4 4 0 0 0 0-8 6 6 0 0 0-11.3 2A3 3 0 0 0 7 18z", "M12 13v6", "M9 16l3 3 3-3"],
    search: ["M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z", "M21 21l-4.3-4.3"],
    external: ["M14 3h7v7", "M21 3l-9 9", "M10 5H5v14h14v-5"],
    download: ["M12 3v12", "M8 11l4 4 4-4", "M5 21h14"],
    play: ["M8 5v14l11-7z"],
    refresh: ["M21 12a9 9 0 0 1-15.4 6.4L3 16", "M3 21v-5h5", "M3 12A9 9 0 0 1 18.4 5.6L21 8", "M21 3v5h-5"],
    copy: ["M8 8h12v12H8z", "M4 4h12v12"],
    tag: ["M20 13 13 20 4 11V4h7l9 9z", "M7.5 7.5h.01"],
    bulb: ["M9 18h6", "M10 22h4", "M8 14a6 6 0 1 1 8 0c-1 1-1.5 2-1.5 4h-5c0-2-.5-3-1.5-4z"]
  };

  return e("svg", {
    className: "icon",
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeLinecap: "round",
    strokeLinejoin: "round",
    strokeWidth: "2",
    "aria-hidden": "true"
  }, (paths[name] || paths.file).map((d) => e("path", { key: d, d })));
}

class AppErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error) {
    console.error(error);
  }

  render() {
    if (this.state.error) {
      return e("main", { className: "app-error" },
        e("section", { className: "panel app-error-panel" },
          e("p", { className: "section-label" }, "Interface"),
          e("h1", null, "The UI hit a rendering problem"),
          e("p", null, this.state.error.message || "Refresh the page after the latest changes are loaded.")
        )
      );
    }
    return this.props.children;
  }
}

ReactDOM.createRoot(document.getElementById("root")).render(e(AppErrorBoundary, null, e(App)));
