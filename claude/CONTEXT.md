# CONTEXT.md — build the Italian Legislation Linked Data Platform

You are building this project from an empty repository. This file is the complete context
you need. Read it fully before writing any code. Do not ask the user to re-explain scope —
everything needed to start is here. If something is genuinely undecided, it is flagged
explicitly in "Open questions" at the end; treat everything else as fixed.

## 0. One-sentence goal

A web app where every Italian law is a dereferenceable page, rendered live from a SPARQL
query against a persistent triple store, where every relation to another law is a clickable
link — plus a public SPARQL endpoint serving the same data to machine clients.

## 1. Hard constraints — do not violate these

These come from prior review feedback on an earlier version of this project. Violating any
of them is a correctness bug, not a style choice.

1. **Persistent storage only.** Use Apache Jena TDB2 (disk-backed). Never load data into an
   in-memory `Model` as the system of record. Data must survive a process restart.
2. **No URNs as resource identifiers.** Every resource is an `http(s)://` URI. Never mint or
   store a `urn:` identifier.
3. **ELI-pattern URIs on our own domain**, not only on the source's domain:
   `https://{our-domain}/eli/id/{year}/{month}/{day}/{natural-id}/{type}`
   Example: `https://osservatorio-eli.example.it/eli/id/2020/04/24/20G00043/sg`
4. **Pages are rendered live from SPARQL, not cached/hardcoded.** Every resource page issues
   a real query (e.g. `DESCRIBE`) against the store at request time.
5. **Generic link rendering.** Any triple whose object is a URI must render as a clickable
   link to that URI's own page. This must be implemented as one generic code path, not a
   switch/if-chain per predicate name (`eli:commences`, `eli:is_about`, `eli:passed_by`, ...
   all use the same rendering logic).
6. **Expression/version listing.** If a resource is a Work with multiple Expressions, list
   them all, each independently clickable, with dates, and mark the current one.
7. **Continuous, gap-free ingestion.** Scheduled jobs, not manually triggered scripts. Persist
   a watermark/cursor per source so a missed run does not permanently lose that day's updates.
8. **Normattiva updates via API, not scraping.** Use `/api/v1/ricerca/aggiornati` (Normattiva
   Open Data API) to detect changed acts. Do not scrape Normattiva HTML for change detection.
9. **Test SPARQL queries with hand-verified expected results** must exist as a checked-in
   artifact (not just ad hoc queries run once), covering at least one multi-version act and
   one conversion relation (`eli:commences`).

## 2. Tech stack (fixed unless "Open questions" says otherwise)

| Layer | Choice |
|---|---|
| Triple store | Apache Jena TDB2 |
| SPARQL server | Apache Jena Fuseki (read endpoint public, write endpoint restricted to ingestion jobs) |
| Backend/web app language | Java or Python — pick one and be consistent; Java gives native Jena API access, Python requires an RDF library (`rdflib`) or HTTP calls to Fuseki. Default to Java + Jena if no constraint is given. |
| Ingestion jobs | Scheduled processes (cron, or a job scheduler library in the chosen language) — must run unattended on a server, not only interactively. |
| Frontend | Server-rendered HTML is sufficient; no SPA framework required. Keep it simple: the point being demonstrated is Linked Data navigation, not frontend sophistication. |
| Vocabularies | ELI Ontology 1.5 (core), schema.org (via ELI alignment), SKOS (authorities/types), Dublin Core Terms |

## 3. Data model

- **Work** — the abstract legislative act (e.g. "Legge 27/2020" as a concept).
- **Expression** — a specific version of a Work at a point in time (e.g. testo originale,
  testo vigente).
- **Manifestation** — a specific rendering/format of an Expression (optional for this phase).
- Key relation: `eli:commences` links a converting Legge to the Decreto Legge it converts.
  Model this as a real, queryable predicate, not a string field.
- Full ontology extension decisions belong to the project supervisors and may arrive later —
  build the ingestion/rendering pipeline generically enough that new predicates need no code
  changes to become clickable (this falls directly out of constraint #5 above).

### 3.1 Example triples to seed test data with

Use these two real-world acts (already referenced in project source material) as your first
seed data — do not invent placeholder data, use these:

```turtle
@prefix eli: <http://data.europa.eu/eli/ontology#> .
@prefix dct: <http://purl.org/dc/terms/> .

<https://osservatorio-eli.example.it/eli/id/2020/03/17/20G00034/sg>
  a eli:LegalResource ;
  dct:title "Decreto-legge 17 marzo 2020, n. 18" ;
  eli:date_publication "2020-03-17"^^<http://www.w3.org/2001/XMLSchema#date> ;
  eli:is_converted_by <https://osservatorio-eli.example.it/eli/id/2020/04/24/20G00043/sg> .

<https://osservatorio-eli.example.it/eli/id/2020/04/24/20G00043/sg>
  a eli:LegalResource ;
  dct:title "Legge 22 aprile 2020, n. 27" ;
  eli:date_publication "2020-04-29"^^<http://www.w3.org/2001/XMLSchema#date> ;
  eli:commences <https://osservatorio-eli.example.it/eli/id/2020/03/17/20G00034/sg> .
```

A second real pair for multi-version testing: Decreto-legge 25 marzo 2020, n. 19
(`20G00035`, published 2020-03-25) converted by Legge 22 maggio 2020, n. 35 (`20G00057`,
published 2020-05-23). Give the Legge at least two Expressions (testo originale and testo
vigente) with different dates to exercise constraint #6.

## 4. Repository structure to create

```
/ingestion
  /gazzetta_crawler/       # scheduled fetch of Gazzetta Ufficiale RSS/HTML, RDF transform, load
  /normattiva_updater/     # scheduled poll of /ricerca/aggiornati, RDF transform, load
  /common/                 # shared RDF transform + Fuseki-load utilities
/store
  /fuseki-config/          # Fuseki dataset + service config pointing at TDB2 directory
  /seed-data/              # the example .ttl files from section 3.1
/webapp
  /routes/                 # /eli/id/... route handling
  /templates/              # resource page, expression list, generic-link rendering
  /queries/                # named, reusable .sparql query template files
/tests
  /sparql/                 # test queries + a checked-in expected-results file (see section 6)
/docs
  /data-model.md
  /update-routine-rationale.md
  /linked-data-design-notes.md
README.md
```

## 5. Build order (do this in sequence, each step should be independently demoable)

1. **Store.** Stand up Fuseki + TDB2 locally. Load the seed `.ttl` from section 3.1. Confirm
   with a manual `curl` or Fuseki UI query that both acts are retrievable.
2. **Generic resource page.** Implement `/eli/id/...` route: run `DESCRIBE <uri>` against
   Fuseki, render every triple; every URI-valued object becomes `<a href>` to that URI's own
   route (constraint #5). Prove it by clicking from the Decreto Legge to the Legge and back.
3. **Expressions.** Add a second query (`SELECT` over `eli:realizes`/inverse or your chosen
   Work→Expression predicate) and render results as a linked list when present.
4. **Raw RDF view.** Add a toggle/route that shows the Turtle for the current resource,
   fetched via a `CONSTRUCT`/`DESCRIBE` query — must match what the rendered page shows.
5. **Ingestion — Gazzetta.** Build the scheduled crawler. Persist a watermark. Write triples
   additively into TDB2 (not by replacing the dataset).
6. **Ingestion — Normattiva.** Build the updater against `/api/v1/ricerca/aggiornati`.
   Same additive-write, same watermark discipline.
7. **Test suite.** Write the SPARQL validation queries and their expected results (section 6).
8. **Docs.** Write up `data-model.md`, `update-routine-rationale.md`, and
   `linked-data-design-notes.md` as you go — do not defer to the end.

Do not proceed to step N+1 until step N is independently demoable against the live Fuseki
instance — this project has previously been criticized for shipping components that only
worked against fake/in-memory data.

## 6. Required test queries (implement all of these, with expected results checked in)

Write each as a `.sparql` file in `/tests/sparql/`, plus one `expected-results.md` (or JSON)
file recording, for each query, the manually-verified correct answer against the seed data
from section 3.1.

1. **All acts** — list every `eli:LegalResource` with title and date.
2. **Acts by year** — parameterized by publication year.
3. **Acts by type** — filter by Decreto Legge vs Legge.
4. **Latest acts** — most recently published N acts.
5. **Conversion-link validation** — for every `eli:commences` triple, confirm the subject
   is typed Legge and the object is typed Decreto Legge (or your chosen type predicates).
6. **ELI-level validation** — confirm every resource has at minimum a title and a
   publication date (flag resources missing them, matching known sparse-metadata reality).
7. **Multi-version acts** — return every Work with more than one Expression, and their dates.
   Must return the Legge 27/2020 or Legge 35/2020 seed example correctly once step 3
   (Expressions) is implemented.

## 7. Non-functional expectations

- Restart the server process mid-development and confirm data survived (TDB2, not memory).
- Keep ingestion additive — never wipe and reload the whole dataset as your update strategy.
- Every new predicate introduced later must work with existing rendering code without a
  code change — if it doesn't, the generic-link implementation (constraint #5) is wrong.

## 8. Open questions (ask the user, don't assume)

- Java or Python for the web app / ingestion jobs?
- Deployment target (local only for now, or a specific server/host already available)?
- Whether HTTP content negotiation (HTML vs RDF via `Accept` header) is in scope now or later
  — default to HTML-only with an explicit "view raw RDF" link if not specified.
- Exact predicate names for Work→Expression linkage, if the supervisors' ELI extension has
  already been decided — otherwise use `eli:realizes` / its inverse as a reasonable default.

## 9. Definition of done for a first working version

- [ ] TDB2 + Fuseki running, data survives restart.
- [ ] Seed data from section 3.1 loaded.
- [ ] Opening a Decreto Legge page and clicking its conversion relation navigates to the
      correct Legge page, and vice versa.
- [ ] The Legge page lists at least two Expressions, both independently clickable.
- [ ] Raw RDF view available and consistent with the rendered page.
- [ ] Gazzetta crawler and Normattiva updater both run on a schedule without manual triggering.
- [ ] All 7 test queries from section 6 exist, run successfully, and match checked-in
      expected results.
- [ ] `/docs` contains the three write-ups listed in step 8 of the build order.
