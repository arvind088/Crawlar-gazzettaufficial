const { useCallback, useEffect, useMemo, useState } = React;
const e = React.createElement;

const TABS = [
  ["search", "Dashboard", "grid"],
  ["normattiva", "Normattiva", "link"],
  ["sparql", "SPARQL", "code"],
  ["technical", "Technical Status", "pulse"]
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
    query: `PREFIX ilg: <http://example.org/italian-legislation/>

SELECT ?source ?relation ?target WHERE {
  ?source ?relation ?target .
  FILTER(?relation IN (ilg:modifies, ilg:modifiedBy))
}
LIMIT 20`
  }
];

function App() {
  const [activeTab, setActiveTab] = useState("search");
  const [status, setStatus] = useState(null);
  const [crawlStatus, setCrawlStatus] = useState(null);
  const [updateResult, setUpdateResult] = useState(null);
  const [updateRunning, setUpdateRunning] = useState(false);
  const [updateError, setUpdateError] = useState("");
  const [modifications, setModifications] = useState([]);
  const [rdfSources, setRdfSources] = useState([]);
  const [acts, setActs] = useState([]);
  const [selected, setSelected] = useState(null);
  const [search, setSearch] = useState("");
  const [yearFilter, setYearFilter] = useState("");
  const [typeFilter, setTypeFilter] = useState("");
  const [sourceFilter, setSourceFilter] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

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

  const loadModifications = useCallback(async () => {
    const response = await fetch("/api/normattiva/modifications?limit=20");
    if (!response.ok) {
      throw new Error("Normattiva modifications request failed");
    }
    const rows = await response.json();
    setModifications(Array.isArray(rows) ? rows : []);
  }, []);

  const loadRdfSources = useCallback(async () => {
    const response = await fetch("/api/rdf/sources");
    if (!response.ok) {
      throw new Error("RDF sources request failed");
    }
    const rows = await response.json();
    setRdfSources(Array.isArray(rows) ? rows : []);
  }, []);

  const runSearch = useCallback(async (query) => {
    setLoading(true);
    setError("");
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
      await loadRdfSources();
      await runSearch(search);
    } catch (err) {
      setUpdateError(err.message || "Update check failed");
    } finally {
      setUpdateRunning(false);
    }
  }, [loadCrawlStatus, loadRdfSources, loadStatus, runSearch, search]);

  useEffect(() => {
    loadStatus().catch((err) => setError(err.message || "Status request failed"));
    loadCrawlStatus().catch((err) => setError(err.message || "Crawler status request failed"));
    loadLatestUpdate().catch(() => {});
    loadModifications().catch((err) => setError(err.message || "Normattiva request failed"));
    loadRdfSources().catch((err) => setError(err.message || "RDF sources request failed"));
    runSearch("");
  }, [loadCrawlStatus, loadLatestUpdate, loadModifications, loadRdfSources, loadStatus, runSearch]);

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
    error,
    loading,
    modifications,
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
    onSelect: setSelected,
    onSourceFilterChange: setSourceFilter,
    onTypeFilterChange: setTypeFilter,
    onYearFilterChange: setYearFilter
  }) : activeTab === "normattiva" ? e(NormattivaPage, { modifications })
    : activeTab === "sparql" ? e(SparqlPage)
      : e(TechnicalPage, {
          crawlStatus,
          loadedFileCount,
          missingFileCount,
          rdfSources,
          status,
          updateError,
          updateResult,
          updateRunning,
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
                e("p", { className: "hero-text" }, "Search Gazzetta acts, inspect Normattiva relationships, and validate the RDF pipeline.")
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
  const totalActs = props.allActs.length;
  const currentYear = new Date().getFullYear().toString();
  const actsThisYear = props.allActs.filter((act) => String(act.publicationDate || "").startsWith(currentYear)).length;
  const lastUpdate = props.allActs.map((act) => act.publicationDate).filter(Boolean).sort().pop() || "unknown";

  return e(React.Fragment, null,
    e("section", { className: "metric-grid", "aria-label": "Search overview" },
      e(MetricTile, { icon: "file", label: "Total Acts", value: totalActs, note: "with searchable metadata" }),
      e(MetricTile, { icon: "calendar", label: "Acts This Year", value: actsThisYear, note: `Year ${currentYear}` }),
      e(MetricTile, { icon: "link", label: "Normattiva Links", value: props.modifications.length, note: "relationship rows" }),
      e(MetricTile, { icon: "clock", label: "Last Update", value: formatDisplayDate(lastUpdate), note: "latest publication date" })
    ),
    e("div", { className: "content-grid" },
      e(SearchPanel, props),
      e(DetailPanel, { act: props.selected, modifications: props.modifications })
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
  sourceFilter,
  sources,
  typeFilter,
  types,
  yearFilter,
  years,
  onSearchChange,
  onRunSearch,
  onSelect,
  onSourceFilterChange,
  onTypeFilterChange,
  onYearFilterChange
}) {
  return e("section", { className: "panel search-panel" },
    e("div", { className: "panel-heading" },
      e("div", { className: "heading-lockup" },
        e("span", { className: "heading-icon", "aria-hidden": "true" }, e(Icon, { name: "search" })),
        e("div", null,
          e("h2", null, "Search Legal Acts"),
          e("p", { className: "panel-subtitle" }, "Find acts by title, local ID, year, type, or source.")
        )
      ),
      e("span", { className: "result-count" }, loading ? "Loading" : `${acts.length} result${acts.length === 1 ? "" : "s"}`)
    ),
    e("form", {
      className: "filter-grid",
      onSubmit: (event) => {
        event.preventDefault();
        onRunSearch(search);
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
      e(SelectFilter, { label: "Year", value: yearFilter, options: years, onChange: onYearFilterChange }),
      e(SelectFilter, { label: "Type", value: typeFilter, options: types, onChange: onTypeFilterChange }),
      e(SelectFilter, { label: "Source", value: sourceFilter, options: sources, onChange: onSourceFilterChange }),
      e("button", { type: "submit", disabled: loading }, "Search")
    ),
    error ? e("p", { className: "error-message" }, error) : null,
    e("div", { className: "table-meta" },
      e("span", null, e(Icon, { name: "file" }), " ", acts.length, " results found")
    ),
    e(ResultTable, { acts, selected, onSelect })
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
    return e("div", { className: "empty-state" }, "No matching legal acts");
  }

  return e("div", { className: "table-wrap" },
    e("table", { className: "results-table" },
      e("thead", null,
        e("tr", null,
          e("th", null, "ID"),
          e("th", null, "Title"),
          e("th", null, "Publication Date"),
          e("th", null, "Type"),
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
            e("td", null, shortType(act.type) || ""),
            e("td", null, sourceLabel(act.source))
          )
        )
      )
    )
  );
}

function DetailPanel({ act, modifications }) {
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
    act ? e(DetailView, { act, modifications }) : e("div", { className: "empty-state" }, "Select a record from the results")
  );
}

function DetailView({ act, modifications }) {
  const fields = [
    ["Title", displayTitle(act)],
    ["Publication Date", act.publicationDate],
    ["Document Date", act.documentDate],
    ["Normattiva Status", normattivaStatus(act, modifications)],
    ["Type", shortType(act.type)],
    ["Source", sourceLabel(act.source)]
  ];

  return e("div", { className: "detail-grid" },
    e("div", { className: "act-pill" }, "ID ", e("strong", null, displayLocalId(act))),
    e("div", { className: "detail-item" },
      e("div", { className: "detail-label" }, "ELI URI"),
      e("div", { className: "uri-row" },
        e("code", { className: "uri-value" }, act.uri || "Missing data"),
        e("button", {
          className: "copy-button",
          disabled: !act.uri,
          onClick: () => copyText(act.uri),
          type: "button"
        }, "Copy")
      )
    ),
    fields.map(([label, value]) =>
      e("div", { key: label, className: "detail-item" },
        e("div", { className: "detail-label" }, label),
        e("div", { className: label === "Normattiva Status" ? "detail-value status-text" : "detail-value" }, value || "Missing data")
      )
    ),
    e("div", { className: "detail-actions" },
      e(LinkButton, { href: act.source || act.uri, icon: "external", label: "Open Gazzetta" }),
      e(LinkButton, { href: act.uri, icon: "link", label: "View RDF" }),
      e("button", { className: "secondary-button", type: "button" }, e(Icon, { name: "download" }), e("span", null, "Download TTL")),
      e("button", { className: "secondary-button", type: "button" }, e(Icon, { name: "code" }), e("span", null, "Run SPARQL"))
    )
  );
}

function LinkButton({ href, icon, label }) {
  return e("a", { className: "action-link", href, target: "_blank", rel: "noreferrer" },
    e(Icon, { name: icon || "external" }),
    e("span", null, label)
  );
}

function NormattivaPage({ modifications }) {
  return e("section", { className: "panel normattiva-panel" },
    e("div", { className: "panel-heading" },
      e("div", null,
        e("p", { className: "section-label" }, "Normattiva relations"),
        e("h2", null, "Modification relationships")
      ),
      e("span", { className: "result-count" }, `${modifications.length} link${modifications.length === 1 ? "" : "s"}`)
    ),
    modifications.length
      ? e("div", { className: "table-wrap" },
          e("table", { className: "results-table" },
            e("thead", null,
              e("tr", null,
                e("th", null, "Source Act"),
                e("th", null, "Relation"),
                e("th", null, "Target Act"),
                e("th", null, "Source URI"),
                e("th", null, "Target URI")
              )
            ),
            e("tbody", null,
              modifications.map((row) =>
                e("tr", { key: `${row.sourceUri}-${row.relationship}-${row.targetUri}` },
                  e("td", { className: "mono strong-cell" }, row.sourceLocalId),
                  e("td", null, relationshipLabel(row.relationship)),
                  e("td", { className: "mono strong-cell" }, row.targetLocalId),
                  e("td", { className: "mono small-uri" }, row.sourceUri),
                  e("td", { className: "mono small-uri" }, row.targetUri)
                )
              )
            )
          )
        )
      : e("div", { className: "empty-state" }, "No Normattiva relationships loaded")
  );
}

function SparqlPage() {
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
        e("p", { className: "section-label" }, "SPARQL Explorer"),
        e("h2", null, "Run queries on the RDF dataset."),
        e("p", { className: "panel-subtitle" }, "Run predefined or custom SPARQL queries against the RDF dataset loaded in Jena/Fuseki.")
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
        e("a", {
          className: "action-link secondary-link",
          href: "http://localhost:3030/",
          rel: "noreferrer",
          target: "_blank"
        }, e("span", null, "Open Fuseki Endpoint"), e(Icon, { name: "external" }))
      ),
      e(QueryResults, { result: queryResult })
    )
  );
}

function TechnicalPage({ crawlStatus, loadedFileCount, missingFileCount, rdfSources, status, updateError, updateResult, updateRunning, onRunUpdate }) {
  return e(React.Fragment, null,
    e("section", { className: "metric-grid", "aria-label": "Technical status" },
      e(MetricTile, { icon: "database", label: "Triples", value: status?.triples ?? "...", note: "legal RDF facts loaded in Jena" }),
      e(MetricTile, { icon: "file", label: "RDF Files", value: loadedFileCount, note: "active sources" }),
      e(MetricTile, { icon: "alert", label: "Missing Data", value: missingFileCount, note: "unavailable files" }),
      e(MetricTile, { icon: "registry", label: "Registry Records", value: crawlStatus?.registryRecords ?? "...", note: "tracked acts" })
    ),
    e(WorkflowPanel, { status }),
    e(CrawlerStatusPanel, { crawlStatus, updateError, updateResult, updateRunning, onRunUpdate }),
    e(SourcePanel, { rdfSources })
  );
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

function CrawlerStatusPanel({ crawlStatus, updateError, updateResult, updateRunning, onRunUpdate }) {
  const statusCounts = crawlStatus?.registryStatusCounts || {};
  const countEntries = Object.entries(statusCounts);

  return e("section", { className: "panel crawler-panel" },
    e("div", { className: "panel-heading" },
      e("div", null,
        e("p", { className: "section-label" }, "Crawler"),
        e("h2", null, "Update status")
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
          : e("span", { className: "muted-line" }, "Manual update check has not been run from this screen yet.")
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
        e("h2", null, "Files used by the API")
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
  return act.localId || localIdFromUri(act.uri) || "Unknown ID";
}

function displayTitle(act) {
  return act.title || "Missing title";
}

function localIdFromUri(uri) {
  if (!uri) {
    return "";
  }
  const parts = String(uri).split("/");
  return parts.length >= 2 && parts[parts.length - 1].toLowerCase() === "sg" ? parts[parts.length - 2] : "";
}

function sourceLabel(source) {
  return source ? "Gazzetta Ufficiale" : "Local RDF";
}

function relationshipLabel(value) {
  return value === "conversion" ? "conversion" : "modifies";
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

ReactDOM.createRoot(document.getElementById("root")).render(e(App));
