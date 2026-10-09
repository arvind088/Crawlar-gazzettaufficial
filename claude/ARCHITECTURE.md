# Italian Legislation Linked Data Platform — Architecture & Context

Project context for a thesis building a Linked Data platform for Italian legislation,
based on the ELI (European Legislation Identifier) ontology, sourced from Gazzetta
Ufficiale and Normattiva. This document is written to be handed to a human
collaborator or pasted as context into a coding assistant.

## 1. Goal (one sentence)

A live website where every Italian law is a dereferenceable web page, generated
on demand from a real triple store, where every relation between legal resources
(versions, conversions, amendments) is a clickable link — plus a public SPARQL
endpoint serving the same data to machine agents.

## 2. Non-negotiable requirements (from supervisor feedback)

These came directly from thesis committee review and are the actual grading criteria.

1. **Persistent storage.** Apache Jena TDB2, not an in-memory model. The dataset
   must be able to grow to the full corpus of Italian legislation.
2. **Live UI.** Every page is rendered from a SPARQL query executed against the
   store at request time. No static/hardcoded pages, no pre-baked JSON dumps.
3. **Generic navigability.** Any object of a triple that is itself a URI must
   render as a clickable link to that resource's own page. This must work for
   *any* predicate (`eli:commences`, `eli:amends`, `eli:cites`, etc.) — not
   special-cased per relation type.
4. **FRBR levels surfaced.** A Work-level resource (a law) must show all its
   Expressions (versions over time) with links to each.
5. **ELI-compliant URIs, including on your own domain.**
   `https://yourdomain.example/eli/id/{year}/{month}/{day}/{natural-id}/{type}`
6. **No URNs as store identifiers.** Every resource is an HTTP(S) URI.
7. **Continuous ingestion.** Update jobs must run daily without gaps — a missed
   day between manual runs is permanently lost data, not just delayed data.
8. **Verifiable correctness.** A set of test SPARQL queries with manually
   verified expected results, covering at least one multi-version act.
9. **Documented rationale.** Every design decision (data model usage, update
   routine design) written up as you go — this becomes thesis chapters, not a
   final write-up exercise.

## 3. Data sources

| Source | Coverage | Update mechanism | Notes |
|---|---|---|---|
| Gazzetta Ufficiale (gazzettaufficiale.it) | Primary + secondary legislation, first version only ("testo storico") | RSS feed / crawl of daily publications | ELI metadata already embedded as `<meta>` tags in HTML; sparse coverage |
| Normattiva (normattiva.it) | Primary (numbered) legislation only | REST API — see below | Tracks amended versions over time (multiple Expressions per Work) |

**Normattiva Open Data API** — use this instead of the old scraping approach:
- Base: `dati.normattiva.it` API v1
- Key endpoint: `/api/v1/ricerca/aggiornati` — returns acts updated since a given
  point, which is what update polling should be built around.

## 4. Data model

- Core vocabulary: **ELI Ontology 1.5**.
- Supplementary vocabularies: **schema.org** (via the ELI–schema.org alignment
  statements), **SKOS** (for controlled vocabularies — document types,
  issuing authorities), **Dublin Core Terms**.
- FRBR-like layering used by ELI: **Work** (the law as an abstract act) →
  **Expression** (a specific version in time) → possibly **Manifestation**
  (a specific format/rendering).
- Key cross-document relation to support: `eli:commences` — links a
  Decreto Legge (decree-law) to the Legge (law) that converts it. This and
  similar relations are the backbone of "significant queries."
- The data model itself (ontology extensions, mapping decisions) is owned by
  the supervisors; this project consumes and documents it, and may propose
  extensions later.

## 5. System architecture

```
[Gazzetta Ufficiale]        [Normattiva API]
        |                          |
   continuous crawler        update runner
   (RSS-driven, daily,       (polls /ricerca/aggiornati,
    no-gap guarantee)         incremental)
        |                          |
        +------------+-------------+
                     |
              [Jena TDB2]  <-- persistent triple store
                     |
              [Fuseki SPARQL endpoint]  <-- public, read access for agents
                     |
              [Application server]
                 - resolves /eli/id/... URIs
                 - runs DESCRIBE/CONSTRUCT queries per request
                 - renders HTML with linked resources
                     |
              [Human users]  +  [Machine agents via raw SPARQL]
```

### 5.1 Storage layer
- Apache Jena TDB2 as the persistent dataset.
- Apache Jena Fuseki as the SPARQL server in front of it (read endpoint public;
  write/update endpoint restricted to the ingestion jobs).
- Alternative considered: Virtuoso. Fuseki is the simpler default; document
  why you picked it either way.

### 5.2 Ingestion layer
- Two independent, idempotent jobs (one per source), each:
  - Fetches new/updated items since last successful run (persist a
    watermark/cursor — do not rely on wall-clock scheduling alone to avoid gaps).
  - Transforms to RDF (align to ELI + extensions).
  - Loads via SPARQL UPDATE / Fuseki's data endpoint into TDB2, additive only.
  - Logs what was ingested, for auditability and thesis documentation.
- Gazzetta Ufficiale job should be a long-running/scheduled process (cron or
  equivalent), not a manually triggered script — this was explicitly flagged
  as a correctness bug, not a style preference.
- Normattiva job rebuilt around `/api/v1/ricerca/aggiornati` rather than
  ad hoc scraping.

### 5.3 Resolution / UI layer
- URI pattern to implement: `/eli/id/{year}/{month}/{day}/{id}/{type}`.
- Content negotiation optional but nice: HTML for browsers, RDF (Turtle/JSON-LD)
  for `Accept: application/rdf+xml` or similar, consistent with Linked Data
  best practice ("303 redirect" pattern is the gold standard if time allows;
  document your simplified choice if you skip it).
- Page rendering logic (generic, not per-relation-type):
  1. `DESCRIBE <resource-uri>` (or a curated `CONSTRUCT`) against Fuseki.
  2. For every triple where the object is a URI, render it as a link to that
     URI's own resolution route.
  3. If the resource is `eli:LegalResource`/Work-typed, run a second query for
     all `eli:realizes`/expression relations and list them as linked versions.
- No caching layer required for the thesis scope, but note it as a future
  improvement if data volume grows.

### 5.4 Agent-facing layer
- Fuseki's SPARQL endpoint exposed directly (read-only) — this *is* the agent
  interface, no separate API needed.

## 6. Suggested repository structure

```
/ingestion
  /gazzetta_crawler/       # continuous fetch + RDF transform + load
  /normattiva_updater/     # polls /ricerca/aggiornati, RDF transform + load
/store
  /fuseki-config/          # Fuseki dataset + service config for TDB2
/webapp
  /routes/eli/             # resource resolution routes
  /templates/              # HTML rendering (resource page, expression list)
  /queries/                # named, reusable SPARQL query templates
/tests
  /sparql/                 # test queries + hand-verified expected results
/docs
  /data-model.md
  /update-routine-rationale.md
  /linked-data-design-notes.md
```

## 7. Milestones

1. TDB2 + Fuseki running locally, loaded with the existing ELI EU dump as test data.
2. URI scheme finalized; no URNs used anywhere going forward.
3. Single generic resource page: `DESCRIBE` query → HTML with all URI objects
   as links. This alone addresses most of the prior UI criticism.
4. Expression/version listing added for Work-typed resources.
5. Real ingestion jobs (Gazzetta continuous crawler, Normattiva updater on
   `/ricerca/aggiornati`), writing into the same live TDB2 the UI reads from.
6. Test SPARQL query suite with expected results, including a multi-version case.
7. Documentation written alongside each milestone (not deferred to the end).

## 8. Open questions to raise with supervisors

- Exact ELI property set / extensions to instantiate (owned by supervisors).
- Whether content negotiation / 303-redirect pattern is in scope or a
  simplified same-URI-serves-HTML approach is acceptable for the thesis.
- Server/hosting access for the public-facing deployment.
