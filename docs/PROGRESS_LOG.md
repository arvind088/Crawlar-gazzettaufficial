# Progress Log

This file is append-only. New task notes should be added at the bottom instead of replacing previous entries.

## 2026-06-12 - Task Started: Connect Scraper To Registry And Raw Cache

Goal:

- Connect `GazzettaScraper` to the crawl registry.
- Cache raw Gazzetta HTML under `data/raw/gazzetta`.
- Compute a content hash for each act page.
- Skip unchanged acts in future runs.
- Continue generating RDF through `CleanLegalActRecord` and `RdfModelBuilder`.

Reason:

- This is the first real incremental-update step. It prepares the crawler for scheduled runs where only new or changed legislation should be processed.

Planned verification:

- Run `mvn -B test`.
- Confirm registry/cache logic is covered by unit tests where possible.

## 2026-06-12 - Task Completed: Registry And Raw Cache Integration

Implemented:

- Extended `CrawlRegistry` so it can read registry rows and upsert crawl results.
- Added registry statuses: `NEW`, `CHANGED`, and `UNCHANGED`.
- Updated `GazzettaScraper` to compute a SHA-256 hash for each fetched act HTML page.
- Updated `GazzettaScraper` to cache raw act HTML under `data/raw/gazzetta`.
- Updated `GazzettaScraper` to update `data/registry/crawl_registry.tsv`.
- Updated `GazzettaScraper` to skip RDF delta output for unchanged acts.
- Changed scraper RDF output to a delta file: `data/rdf/gazzetta_metadata_delta.ttl`.
- Added tests for registry status transitions, stable hashes, and raw cache file naming.

Verification:

- Ran `mvn -B test`.
- Result: 8 tests passed, 0 failures.

Notes:

- This does not yet avoid fetching the page from the web; it fetches, hashes, and then skips unchanged RDF output.
- A later optimization can add a staleness window, HTTP caching headers, or scheduled date filtering so older known acts are not fetched every run.

## 2026-06-12 - Task Started: Inspect Professor Sample TTL Files

Goal:

- Explicitly inspect the professor/context sample TTL files such as `20200325_20G00035.ttl`.
- Compare the simple Gazzetta-generated samples with the richer combined sample `LEGGE_35_2020_DL_19_2020.ttl`.
- Clarify what has been checked and what still needs to be implemented for Normattiva integration.

## 2026-06-12 - Task Completed: Inspect Professor Sample TTL Files

Checked files:

- `context/20200325_20G00035.ttl`
- `context/20200523_20G00057.ttl`
- `context/LEGGE_35_2020_DL_19_2020.ttl`
- `context/TSV_MOD_2025-aggiornamenti.tsv`

Findings:

- `20200325_20G00035.ttl` is a base Gazzetta ELI RDF file for Decreto-Legge 25 marzo 2020, n. 19.
- `20200523_20G00057.ttl` is a base Gazzetta ELI RDF file for Legge 22 maggio 2020, n. 35.
- These base files include `eli:LegalResource`, `eli:LegalExpression`, `eli:Format`, dates, local ID, type, version, language, publisher, and HTML manifestation.
- The richer `LEGGE_35_2020_DL_19_2020.ttl` adds Normattiva HTML manifestations, `schema.org` classes/properties, publication issue resources, `eli:in_force`, `eli:number`, work types, and the legal relation `eli:commences` / `eli:commenced_by`.
- `TSV_MOD_2025-aggiornamenti.tsv` contains the kind of Normattiva modification rows that still need a parser and RDF builder.

Conclusion:

- The current implementation matches the simpler Gazzetta base RDF shape.
- The next missing implementation layer is Normattiva modification parsing and enrichment so generated output can move toward the richer professor sample.

## 2026-06-12 - Task Started: Implement Normattiva Modification TSV Integration

Goal:

- Parse `context/TSV_MOD_2025-aggiornamenti.tsv`.
- Represent each Normattiva update row as a cleaned modification record.
- Generate RDF for modification relations between legal acts.
- Preserve the natural-language `modtext` as evidence.
- Add a first-pass classification such as amendment, abrogation, conversion, reference, or unknown.
- Write generated RDF under `data/rdf/normattiva_modifications.ttl`.

Reason:

- This is the next enrichment layer needed to move from simple Gazzetta RDF toward the richer professor sample that includes legal relations such as `eli:commences` / `eli:commenced_by` and other lifecycle links.

Planned verification:

- Add unit tests for TSV parsing, deduplication, classification, and RDF generation.
- Run `mvn -B test`.

## 2026-06-12 - Task Completed: Implement Normattiva Modification TSV Integration

Implemented:

- Added `ModificationType` for first-pass classification of Normattiva update text.
- Added `CleanModificationRecord` for cleaned Normattiva relation rows.
- Added `TsvModParser` for `eliSubject`, `eliObject`, and `modtext` TSV files.
- Added `ModificationRdfModelBuilder` to generate RDF modification events and direct relation triples.
- Added `NormattivaModTsvRunner` to generate RDF from `context/TSV_MOD_2025-aggiornamenti.tsv`.
- Added tests for TSV parsing, deduplication, classification, event RDF, direct relation RDF, and conversion-to-ELI commencement mapping.

Generated output:

- `jena-git-project/data/rdf/normattiva_modifications.ttl`

Current modeling:

- Each TSV row becomes an `ilg:ModificationEvent`.
- The modifying act links to the affected act with `ilg:modifies`.
- The affected act links back with `ilg:modifiedBy`.
- Original `modtext` is preserved with `ilg:modificationText`.
- First-pass `ModificationType` is stored with `ilg:modificationType`.
- Rows classified as `CONVERSION` also generate `eli:commences` and `eli:commenced_by`, matching the direction shown in the professor sample.

Verification:

- Ran `mvn -B test`.
- Result: 11 tests passed, 0 failures.
- Ran `NormattivaModTsvRunner`.
- Result: 36 Normattiva rows generated 243 RDF triples.

Limitations:

- This is a first-pass deterministic classifier based on keywords in `modtext`.
- Exact legal relation mapping should still be reviewed with the professors before final ontology decisions.

## 2026-06-12 - Task Started: Simplify Normattiva Graph For Professor Alignment

Decision:

- Professor prefers a simpler graph using actual legal act URIs directly.
- Remove project-generated `mod:` modification event nodes from the generated Normattiva TTL.
- Keep direct act-to-act triples as the primary output.

Target output style:

```ttl
<ACT_A> ilg:modifies <ACT_B> .
<ACT_B> ilg:modifiedBy <ACT_A> .
```

For conversion rows:

```ttl
<CONVERSION_LAW> eli:commences <DECREE_LAW> .
<DECREE_LAW> eli:commenced_by <CONVERSION_LAW> .
```

Reason:

- This better matches the professor sample, where legal relations are represented directly between actual Gazzetta ELI act URIs.

## 2026-06-12 - Task Completed: Simplify Normattiva Graph For Professor Alignment

Implemented:

- Removed `mod:` modification event nodes from generated Normattiva RDF.
- Removed event text/type triples from the public generated TTL.
- Kept only direct act-to-act relation triples.
- Kept `eli:commences` and `eli:commenced_by` for rows classified as conversion.
- Regenerated `data/rdf/normattiva_modifications.ttl`.

Current simplified output style:

```ttl
<ACT_A> ilg:modifies <ACT_B> .
<ACT_B> ilg:modifiedBy <ACT_A> .
<CONVERSION_LAW> eli:commences <DECREE_LAW> .
<DECREE_LAW> eli:commenced_by <CONVERSION_LAW> .
```

Verification:

- Ran `mvn -B test`.
- Result: 11 tests passed, 0 failures.
- Ran `NormattivaModTsvRunner`.
- Result: 36 Normattiva rows generated 27 simplified RDF triples.

Professor demo note:

- Show the direct act URI triples in `normattiva_modifications.ttl`.
- Do not show or discuss generated `mod:` event IDs because they have been removed from the simplified output.

## 2026-06-12 - Task Started: Review Professor Repository `atomm/fetchGU`

Goal:

- Inspect the professor/reference GitHub repository `https://github.com/atomm/fetchGU.git`.
- Check whether it uses GitHub Actions for automation.
- Understand whether its automation/query approach can guide our crawler pipeline and scheduled update design.

Reason:

- The repository may show the professor's intended automation pattern, especially if it already has GitHub Actions workflows for fetching Gazzetta data or running queries.

Planned verification:

- Inspect repository structure.
- Inspect `.github/workflows` if present.
- Summarize useful patterns for our project.

## 2026-06-12 - Task Completed: Review Professor Repository `atomm/fetchGU`

Checked repository:

- `https://github.com/atomm/fetchGU`

Files inspected:

- `.github/workflows/rss-to-csv.yml`
- `.github/scripts/rss_to_csv.py`
- `README.md`
- `data/gazzetta-update.csv`

What the professor/reference repository does:

- Uses GitHub Actions.
- Runs automatically every day at midnight UTC.
- Also supports manual execution with `workflow_dispatch`.
- Uses Python 3.10.
- Installs `feedparser` and `pandas`.
- Reads the official Gazzetta RSS feed:

```text
https://www.gazzettaufficiale.it/rss/SG
```

- Writes discovered feed entries to:

```text
data/gazzetta-update.csv
```

- Avoids duplicate CSV rows by checking existing `link` values.
- Commits and pushes the updated CSV back to the repository.

Important finding:

- The professor repository is a lightweight update detector, not a full RDF crawler.
- It is useful as an automation pattern for our project:
  daily schedule -> discover new Gazzetta links -> update local data -> commit generated output.

How this should influence our project:

- We should keep our Java/Jena RDF pipeline.
- We can add a GitHub Actions workflow similar to `fetchGU`.
- Instead of only appending RSS rows to CSV, our workflow should later:
  - read Gazzetta RSS,
  - crawl only new or changed legal acts,
  - update the crawl registry,
  - regenerate RDF delta files,
  - run SPARQL/demo checks,
  - commit updated data outputs.

Thesis/professor explanation:

- `fetchGU` proves a simple scheduled GitHub Action can maintain an update file from the official Gazzetta feed.
- Our project extends that idea by converting official legal metadata into RDF triples suitable for SPARQL querying and future Fuseki loading.

## 2026-06-13 - Task Started: Check User GitHub Repository

Repository provided:

- `https://github.com/arvind088/Crawlar-gazzettaufficial.git`

Goal:

- Verify the user's GitHub repository location.
- Check default branch and repository accessibility.
- Use this repository as the target for future GitHub Actions automation work.

Reason:

- The next automation step should be designed for the actual project repository, not only the local folder or the professor reference repository.

## 2026-06-13 - Task Started: Add GitHub Actions Automation For Project Repository

Goal:

- Add a first GitHub Actions workflow for `arvind088/Crawlar-gazzettaufficial`.
- Follow the professor `fetchGU` pattern: scheduled run plus manual run.
- Keep the workflow aligned with our Java/Maven/Jena project.

Implementation plan:

- Add a Java RSS update runner that reads the official Gazzetta RSS feed.
- Store discovered RSS entries in a stable TSV file under `data/clean`.
- Avoid duplicate entries by link.
- Add tests for RSS parsing and duplicate handling.
- Add a GitHub Actions workflow that runs Maven tests, RSS update, Normattiva RDF generation, and SPARQL smoke queries.

Reason:

- This creates the bridge between the professor's simple RSS automation and our semantic-web RDF pipeline.

## 2026-06-13 - Task Completed: Add GitHub Actions Automation For Project Repository

Implemented:

- Added `.github/workflows/legal-data-pipeline.yml`.
- Added `GazzettaRssUpdateRunner.java`.
- Added `GazzettaRssUpdateRunnerTest.java`.
- Updated `docs/PIPELINE_AUTOMATION.md`.

Repository verification:

- User repository: `https://github.com/arvind088/Crawlar-gazzettaufficial.git`
- GitHub default branch: `master`
- Local `origin`: `https://github.com/arvind088/Crawlar-gazzettaufficial.git`
- Local branch: `master`

GitHub Actions behavior:

- Runs daily at `00:30 UTC`.
- Can also be run manually from the GitHub Actions tab.
- Uses Java 17.
- Runs `mvn -B test`.
- Runs `GazzettaRssUpdateRunner`.
- Runs `NormattivaModTsvRunner`.
- Runs `SampleRdfQueryRunner`.
- Commits generated `jena-git-project/data` changes back to GitHub.

RSS runner behavior:

- Reads the official Gazzetta RSS feed:

```text
https://www.gazzettaufficiale.it/rss/SG
```

- Writes output to:

```text
data/clean/gazzetta_rss_updates.tsv
```

- Stores title, link, published date, description, and fetch date.
- Avoids duplicate rows by checking the `link` value.

Verification:

- Ran `mvn -B test`.
- Result: 13 tests passed, 0 failures.
- Ran `GazzettaRssUpdateRunner` against the live Gazzetta RSS feed.
- Result: 16 RSS entries read and 16 new rows added.
- Ran `NormattivaModTsvRunner`.
- Result: 36 Normattiva rows generated 27 simplified RDF triples.
- Ran `SampleRdfQueryRunner`.
- Result: loaded 140 triples and executed all 5 SPARQL demo queries successfully.

Important limitation:

- The workflow currently discovers and records new Gazzetta RSS links.
- The next round should connect RSS links to full act-page crawling and RDF delta generation.

## 2026-06-13 - Task Started: Commit And Push Project Updates To GitHub

Goal:

- Commit the completed crawler, RDF, automation, tests, context, and documentation work.
- Push the project updates to the user's GitHub repository:

```text
https://github.com/arvind088/Crawlar-gazzettaufficial.git
```

Planned commit scope:

- GitHub Actions workflow.
- Thesis/professor documentation.
- Context sample data used for demo and query verification.
- Java crawler/RDF/Normattiva/RSS pipeline classes.
- SPARQL demo queries.
- Tests.
- Generated RSS and RDF outputs needed for demo.

Files intentionally not staged:

- Eclipse workspace metadata changes unless needed later.
- Unrelated generated-output deletion unless the user explicitly wants cleanup.

## 2026-06-13 - Task Started: Connect Gazzetta RSS Discovery To RDF Delta Generation

Goal:

- Move beyond only saving RSS links.
- Use discovered Gazzetta RSS links as crawler input.
- Fetch each discovered legal act page.
- Reuse the crawl registry to mark acts as `NEW`, `CHANGED`, or `UNCHANGED`.
- Generate RDF Turtle only for new or changed legal acts.

Reason:

- This is the next bridge from the professor-style RSS update detector to our own semantic-web pipeline.
- It will make automation more meaningful because new RSS entries can become RDF triples automatically.

## 2026-06-13 - Task Completed: Connect Gazzetta RSS Discovery To RDF Delta Generation

Implemented:

- Added `GazzettaRssCrawlerRunner.java`.
- Added `GazzettaRssUpdateRunner.readLinks(...)`.
- Added `GazzettaScraper.crawlGazzettaActUrls(...)`.
- Refactored `GazzettaScraper` so act HTML processing can be tested without live network access.
- Added tests for reading RSS update links.
- Added tests for registry-backed act processing and unchanged detection.
- Added cleanup for common Gazzetta quote mojibake in extracted titles.

New command:

```powershell
mvn -B "-Dexec.mainClass=it.legislation.crawler.GazzettaRssCrawlerRunner" exec:java
```

What it does:

- Reads:

```text
data/clean/gazzetta_rss_updates.tsv
```

- Extracts discovered Gazzetta act links.
- Fetches each legal act page.
- Caches raw HTML under:

```text
data/raw/gazzetta
```

- Updates:

```text
data/registry/crawl_registry.tsv
```

- Writes RDF delta output to:

```text
data/rdf/gazzetta_metadata_delta.ttl
```

Verification:

- Ran `mvn -B test`.
- Result: 16 tests passed, 0 failures.
- Ran `GazzettaRssCrawlerRunner`.
- Result: 16 RSS links read, 16 new Gazzetta legal act records crawled, and `gazzetta_metadata_delta.ttl` generated.

Important note:

- The registry correctly prevents unchanged pages from being emitted again on later runs.
- If a clean full regeneration is needed for demo data, use a fresh registry or add a future explicit `force` mode.

## 2026-06-13 - Task Started: Build One-Command Review Demo Flow

Goal:

- Create a clean local demo command for review and thesis explanation.
- Show the full Gazzetta path in one flow:
  RSS discovery -> RSS TSV -> crawler -> registry -> RDF delta -> SPARQL check.

Reason:

- The demo should be easy to run without remembering many separate commands.
- This will also help explain the thesis pipeline step by step.

## 2026-06-13 - Task Update: Use Neutral Demo Naming

Decision:

- Use neutral terms such as `demo`, `review`, and `thesis` in new commands and class names.
- Rename the one-command demo class to `DemoPipelineRunner`.

## 2026-06-13 - Task Completed: Build One-Command Review Demo Flow

Implemented:

- Added `DemoPipelineRunner.java`.
- Added generated-Gazzetta demo SPARQL queries under:

```text
queries/demo-gazzetta
```

Demo command:

```powershell
mvn -B "-Dexec.mainClass=it.legislation.crawler.DemoPipelineRunner" exec:java
```

What the demo command does:

- Reads the official Gazzetta RSS feed.
- Updates `data/clean/gazzetta_rss_updates.tsv`.
- Crawls discovered Gazzetta legal act links.
- Updates `data/registry/crawl_registry.tsv`.
- Generates or reuses `data/rdf/gazzetta_metadata_delta.ttl`.
- Runs SPARQL queries against the generated Gazzetta RDF delta.

Verification:

- Local whitespace/diff check passed.
- Maven verification was not rerun because permission for dependency/plugin resolution was not granted in this step.

## 2026-06-14 - Task Started: Verify GitHub Push And Continue Backend Web API

Goal:

- Check whether the latest code push is visible in the GitHub repository.
- Verify that the RSS-to-RDF demo runner and demo query files were pushed.
- If the push is present, continue with the next implementation step: a simple Spring Boot API layer for the generated RDF data.

Reason:

- The web layer should start only after the latest crawler/demo code is safely available in the project repository.

## 2026-06-14 - Task Completed: Verify GitHub Push And Continue Backend Web API

GitHub verification:

- Confirmed `GazzettaRssCrawlerRunner.java` is visible on GitHub.
- Confirmed `DemoPipelineRunner.java` is visible on GitHub.
- Confirmed `queries/demo-gazzetta/01-latest-gazzetta-acts.rq` is visible on GitHub.
- Confirmed the Gazzetta text-cleaning update is visible on GitHub.

Implemented:

- Added Spring Boot web application entry point:

```text
it.legislation.LegalDataWebApplication
```

- Added REST API package:

```text
it.legislation.web
```

- Added endpoints:

```text
GET /api/health
GET /api/acts?search=...&limit=...
GET /api/acts/{localId}
```

- Added `LegalActQueryService` to read generated Turtle files with Apache Jena and return JSON-friendly records.
- Added unit test for the query service.
- Switched Spring Boot to `2.7.18` because it is compatible with the current Apache Jena 4.10 / SLF4J 1.7 stack.

Verification:

- Ran `mvn -B test`.
- Result: 17 tests passed, 0 failures.

## 2026-06-22 - Repository cleanup pass

Goal:

- Analyze the repository and remove unwanted local files that should not be part of the project source.

What was cleaned:

- Removed accidental command/output file `h origin master`.
- Removed old local planning/context artifacts `IMPLEMENTATION_PLAN.md` and `context/` contents where possible.
- Removed Eclipse-generated local metadata from `jena-git-project/.classpath` and `jena-git-project/.settings/`.
- Removed generated scratch RDF files `jena-git-project/eli_metadata.ttl` and `jena-git-project/src/eli_metadata_generated.ttl`.
- Removed duplicate local report output under `jena-git-project/data/registry/reports/`.
- Updated `.gitignore` so generated report folders stay out of Git.

What was intentionally kept:

- Runtime crawler data under `jena-git-project/data/clean/`, `jena-git-project/data/raw/`, `jena-git-project/data/rdf/`, and `jena-git-project/data/registry/`.
- These files support the local demo and are ignored by Git, so they will not be pushed unless explicitly forced.

Notes:

- The empty `context/` directory may remain locally if Windows keeps a lock on it, but it is ignored and does not affect the repository.

## 2026-06-22 - Removed local preview image files

Goal:

- Remove unwanted page/mockup image files from the local repository folder.

What was cleaned:

- Removed generated PNG preview screenshots from `jena-git-project/target/`.
- Verified that no `.png`, `.jpg`, `.jpeg`, `.gif`, `.webp`, or `.svg` files remain in the repository folder.

Notes:

- These screenshots were local build/demo output only.
- No source UI files or application assets were removed.

## 2026-06-22 - Strict repository junk cleanup

Goal:

- Remove files that were not important for the real project source and made the repository folder feel messy.

What was removed:

- Deleted old generated Turtle file `jena-git-project/eli_metadata_cleaned.ttl`.
- Deleted old one-off helper classes tied to obsolete `eli_metadata.ttl` files:
  - `FusekiUploader.java`
  - `TurtleFileCleaner.java`
- Deleted old sample/unrelated Java file `Person.java`.
- Deleted Eclipse metadata files:
  - `jena-git-project/.project`
  - `jena-git-project/.gitignore`
- Deleted Maven/build/browser output folder `jena-git-project/target/`.
- Deleted generated local crawler output:
  - raw Gazzetta HTML snapshots
  - generated crawler TSV files
  - generated crawler RDF delta files
  - generated crawl registry file
- Deleted older ignored notes that were replaced by the project manual.

What remains:

- Real source code, tests, UI files, workflow, `PROJECT_MANUAL.md`, and the small tracked sample data files.
- `docs/PROGRESS_LOG.md` remains as the local work history.

Notes:

- Local demo data can be regenerated by running the crawler/update flow again.

## 2026-06-19 - Dashboard Search for Normattiva Relation IDs

Problem found:

- Normattiva relation IDs such as `25G00028` appeared in the Normattiva relationships page.
- The same ID did not appear when searched from the main Dashboard.

Cause:

- Dashboard search was focused on acts with Gazzetta metadata fields such as title, publication date, local ID, or source.
- Some Normattiva relationship rows only had RDF relationship triples and an act URI.
- Example: `25G00028` existed in `normattiva_modifications.ttl`, but did not have full title/date metadata in `gazzetta_metadata_delta.ttl`.

Fix:

- Updated `LegalActQueryService` so search can also find legal resources that participate in Normattiva relationships.
- Added URI-based ID matching, so an act can be found even when `eli:id_local` is missing.
- Updated `findByLocalId` so relation-only acts can still open in selected-act details and RDF export.
- Added a regression test for `25G00028`.

Verification:

- Ran `mvn -B test`.
- Result: 36 tests passed, 0 failures.
- Restarted Spring Boot on port `8080`.
- Checked `GET http://localhost:8080/api/acts?search=25G00028&limit=10`.
- Result: API returned one record with local ID `25G00028`.
- Checked `GET http://localhost:8080/api/acts/25G00028/rdf`.
- Result: RDF export returned the `25G00028 -> modifies -> 25G00006` relationship.

Follow-up:

- Checked `GET http://localhost:8080/api/acts?search=25G00006&limit=10`.
- Result: API returned one record with local ID `25G00006`.
- Checked `GET http://localhost:8080/api/acts/25G00006/rdf`.
- Result: RDF endpoint returned HTTP 200.
- Updated the Dashboard search form so pressing Search resets Year, Type, and Source filters to `All`.
- Reason: relation-only acts can be hidden if an old filter such as Source = `Gazzetta Ufficiale` remains selected.
- Renamed relation-only search rows from `Local RDF` to `Relation RDF` in the UI.
- Copied updated static resources with `mvn -B resources:resources`.

## 2026-06-19 - Backfill Metadata for Gazzetta Acts Found Through Normattiva

Problem found:

- IDs such as `25G00006` were searchable after the relation-search fix, but showed `Missing title`.
- The reason was that `25G00006` existed only in `normattiva_modifications.ttl` as a relationship node.
- The full Gazzetta metadata page for that act had not been crawled into `gazzetta_metadata_delta.ttl`.

Fix:

- Updated `GazzettaScraper` so new crawled records are merged into `gazzetta_metadata_delta.ttl` instead of replacing the whole file.
- Added `NormattivaGazzettaMetadataBackfillRunner`.
- The runner scans Normattiva relationship RDF files, extracts Gazzetta ELI URIs, crawls those Gazzetta pages, and merges title/date/type/source metadata into the Gazzetta RDF file.
- Added the backfill runner to `.github/workflows/legal-data-pipeline.yml`.

Verification:

- Ran `mvn -B test`.
- Result: 38 tests passed, 0 failures.
- Ran `NormattivaGazzettaMetadataBackfillRunner`.
- Result: 11 Gazzetta relation acts were crawled and merged into `gazzetta_metadata_delta.ttl`.
- Checked `GET http://localhost:8080/api/acts?search=25G00006&limit=10`.
- Result: `25G00006` now returns title, publication date, document date, type, and Gazzetta source.
- Checked `GET http://localhost:8080/api/acts?search=25G00028&limit=10`.
- Result: `25G00028` also returns full Gazzetta metadata.

Important note:

- This backfill enriches Gazzetta ELI URLs such as `http://www.gazzettaufficiale.it/eli/id/.../sg`.
- Normattiva `N2Ls` URLs are a different source and need a separate Normattiva metadata enrichment step if we want full title/date/type for those rows too.

## 2026-06-19 - Table Layout and SPARQL Result UI Cleanup

Problem found:

- Tables were too narrow for long RDF values.
- SPARQL query results overlapped when property/value cells contained long URIs or long titles.
- Normattiva and RDF source tables needed better horizontal handling.

Fix:

- Increased the main application layout width from `1360px` to `1680px`.
- Made the Dashboard content grid wider.
- Added safer wrapping rules for table cells.
- Added specific widths and wrapping behavior for Normattiva, RDF source, and SPARQL result tables.
- Changed SPARQL result display so RDF predicate URIs are shortened into readable names such as `eli:title`, `rdfs:label`, `dcterms:source`, and `rdf:type`.
- Copied updated static resources into the running app target folder.

Verification:

- Checked served `styles.css` and confirmed the wider layout and table rules are available from `http://localhost:8080/styles.css`.
- Checked served `app.js` and confirmed the SPARQL compact RDF name formatter is available from `http://localhost:8080/app.js`.

Follow-up:

- Removed old global table no-wrap behavior that could still force long cells to overlap.
- Forced table cells and nested table text to wrap with `overflow-wrap: anywhere` and `word-break: break-word`.
- Changed Normattiva relation ID cells so long `N2Ls?...urn:nir:stato:...` identifiers are displayed as shorter values such as `legge:2026-05-13;79`.
- Copied updated `styles.css` and `app.js` into the running app target folder.

## 2026-06-17 - Task Completed: Add Historical Archive Discovery Runner

Goal:

Add the first safe step for collecting older Gazzetta acts, so the project is not limited only to the latest RSS/update feed.

What was added:

- Added `GazzettaArchiveDiscoveryRunner`.
- The runner reads the official Gazzetta yearly archive pages.
- It discovers issue/detail links for a date range.
- It extracts legal act links and local IDs from issue pages.
- It writes discovered links to:

```text
data/clean/gazzetta_archive_links.tsv
```

Important design choice:

- This step only discovers and records archive links.
- It does not yet crawl every historical act page.
- This keeps the next phase controlled, because we can first verify that the discovered IDs and URLs are correct.

Default date range:

```text
2026-06-01 to 2026-06-16
```

Configurable environment variables:

```text
GAZZETTA_ARCHIVE_START
GAZZETTA_ARCHIVE_END
GAZZETTA_ARCHIVE_OUTPUT
```

New test coverage:

- Archive issue links are parsed from archive HTML.
- Act links are parsed from issue detail HTML.
- TSV output is written with publication date, issue number, local ID, act URL, and discovery timestamp.

Verification:

- Ran `mvn -B test`.
- Result: 24 tests passed, 0 failures.

## 2026-06-17 - Task Completed: Connect Historical Archive Links to Crawler

Goal:

Add the next step after archive discovery, so discovered historical Gazzetta links can be crawled through the same registry, raw snapshot, and RDF delta pipeline used by the latest-update crawler.

What was added:

- Added `GazzettaArchiveCrawlRunner`.
- The runner reads:

```text
data/clean/gazzetta_archive_links.tsv
```

- It extracts the `act_url` column.
- It removes duplicate URLs.
- It skips empty or invalid rows.
- It applies a configurable batch limit.
- It sends selected URLs into the existing `GazzettaScraper.crawlGazzettaActUrls(...)` method.

Why this matters:

- Historical archive collection now connects to the existing crawler path.
- The same registry prevents unnecessary repeat crawling.
- The same raw HTML snapshot folder is reused.
- The same RDF delta file is generated when records are new or changed.

Configurable environment variables:

```text
GAZZETTA_ARCHIVE_LINKS
GAZZETTA_ARCHIVE_CRAWL_LIMIT
```

Limit behavior:

- Default limit is `10`, useful for safe demos.
- Set `GAZZETTA_ARCHIVE_CRAWL_LIMIT=0` to process all discovered archive links.

New test coverage:

- Reads act URLs from the archive TSV.
- Removes duplicate URLs.
- Applies the batch limit.
- Treats limit `0` as read all.
- Returns an empty list when the TSV file is missing.

Verification:

- Ran `mvn -B test`.
- Result: 28 tests passed, 0 failures.

## 2026-06-17 - Task Completed: Add Web Controls for Historical Archive Backfill

Goal:

Make the historical archive workflow available from the web app, not only from terminal commands.

What was added:

- Added `ArchiveCrawlerService`.
- Added `ArchiveCrawlerResult`.
- Added API endpoint to discover historical archive links:

```text
POST /api/archive/discover?startDate=2026-06-01&endDate=2026-06-16
```

- Added API endpoint to crawl a selected archive batch:

```text
POST /api/archive/crawl?limit=10
```

- Added API endpoint to view the latest archive action result:

```text
GET /api/archive/run/latest
```

Web UI changes:

- Added a Historical Archive panel on the Technical Status page.
- Added date inputs for archive discovery.
- Added a Discover Archive Links button.
- Added a batch limit input.
- Added a Crawl Archive Batch button.
- Added a result summary showing action, state, discovered links, available links, crawled links, and changed RDF records.

Pipeline meaning:

```text
Discover archive links -> Save TSV -> Crawl selected URLs -> Update registry/raw snapshots/RDF delta -> Explore in UI/SPARQL
```

Safety behavior:

- Archive discovery and archive crawling are separate actions.
- Archive crawl uses a batch limit by default.
- Limit `0` can be used when the full discovered archive file should be processed.

New test coverage:

- Archive discovery service writes discovered links.
- Archive crawl service reads available links, selects a limited batch, and sends it to the existing act crawler.

Verification:

- Ran `mvn -B test`.
- Result: 30 tests passed, 0 failures.

## 2026-06-17 - Task Completed: Improve Archive Batch Limit Behavior

Goal:

Make archive batch crawling safer and clearer when the requested batch limit is bigger than the available pending archive links.

What changed:

- Increased the archive crawl safety cap from `100` to `500`.
- Archive crawl now derives the canonical ELI URI from archive URLs.
- Archive crawl compares those canonical ELI URIs with the crawl registry.
- Links already present in the registry are skipped before selecting the next batch.
- The effective batch limit is now clamped to the number of available pending archive links.
- The web UI Batch Limit input now uses the available pending link count as its maximum value.
- The web UI shows a hint:

```text
Available pending links: N
```

Why this matters:

- If the user enters `282` but only `192` pending links are available, the system uses `192`.
- Repeated archive crawls now move forward through pending links instead of repeatedly checking the same first batch.

Verification:

- Ran `mvn -B test`.
- Result: 31 tests passed, 0 failures.

## 2026-06-17 - Task Completed: Finalize Demo Actions and GitHub CI

Goal:

Finish the main demo-facing gaps and make the repository ready for GitHub validation.

Selected act actions completed:

- Added per-act RDF export endpoint:

```text
GET /api/acts/{localId}/rdf
GET /api/acts/{localId}/rdf?download=true
```

- `View RDF` now opens Turtle RDF for the selected act.
- `Download TTL` now downloads a `{localId}.ttl` file.
- `Run SPARQL` now opens the SPARQL page with an act-specific query.

GitHub Actions completed:

- Stopped ignoring `.github/` in `.gitignore`.
- Added automatic CI workflow:

```text
.github/workflows/ci.yml
```

- CI runs `mvn -B test` on push, pull request, and manual dispatch.
- Cleaned the legal data pipeline workflow:

```text
.github/workflows/legal-data-pipeline.yml
```

- The data pipeline is manual-only.
- It runs tests, RSS discovery, archive discovery, and optional RDF/SPARQL smoke steps when the needed local inputs are available.

Verification:

- Ran `mvn -B test`.
- Result: 32 tests passed, 0 failures.
- Restarted Spring Boot on port `8080`.
- Verified the app page returns HTTP `200`.
- Verified per-act RDF endpoint returns `200 text/turtle`.
- Verified per-act TTL download returns:

```text
attachment; filename="{localId}.ttl"
```

## 2026-06-18 - Task Completed: Add Project Manual

Goal:

Create a practical manual for running and using the project, separate from the thesis/report document.

What was added:

- Added root-level manual:

```text
PROJECT_MANUAL.md
```

Manual contents:

- Project purpose.
- Technology stack.
- Folder structure.
- Requirements.
- Clone and run instructions.
- Web app page guide.
- Demo flow.
- Latest RSS update flow.
- Historical archive flow.
- Automation explanation.
- Useful API endpoints.
- Example SPARQL queries.
- Validation checklist.
- Troubleshooting.
- Future work.
- Quick commands.

Reason:

- This manual is intended for using and demonstrating the project.
- It is not written as a thesis report.

## 2026-06-19 - Task Completed: Add Automatic Normattiva Update Downloader

Goal:

Close the project gap where Normattiva relationships were only coming from a prepared local TSV file.

What was added:

- Added `NormattivaUpdateRunner`.
- The runner downloads update content from:

```text
https://www.normattiva.it/
```

- It extracts Normattiva act links from update/news items.
- It writes downloaded update records to:

```text
data/clean/normattiva_updates.tsv
```

- It infers simple relationship rows when a Normattiva update item links multiple acts.
- It writes inferred relations to:

```text
data/clean/normattiva_modifications_auto.tsv
```

- It generates automatic Normattiva RDF at:

```text
data/rdf/normattiva_modifications_auto.ttl
```

App integration:

- `LegalActQueryService` now loads the automatic Normattiva RDF file when it exists.
- `NormattivaQueryService` now reads both:

```text
data/rdf/normattiva_modifications.ttl
data/rdf/normattiva_modifications_auto.ttl
```

- The Technical Status RDF source list can show/download:

```text
normattiva_modifications_auto.ttl
```

Automation integration:

- GitHub manual data pipeline now runs `NormattivaUpdateRunner`.
- Generated Normattiva update files are ignored by Git as runtime outputs.

Live validation:

- Ran `NormattivaUpdateRunner` against the live Normattiva website.
- Result:

```text
Downloaded Normattiva updates: 8
Inferred Normattiva relation rows: 3
```

Verification:

- Ran `mvn -B test`.
- Result: 34 tests passed, 0 failures.

Current limitation:

- The automatic runner discovers Normattiva update links and infers simple relationships from update cards.
- Deep article-level modification extraction from full Normattiva act pages remains future work.

## 2026-06-19 - Task Completed: Add Scheduled Normattiva Automation

Goal:

Make Normattiva automation work like the Gazzetta scheduled crawler while the Spring Boot app is running.

What was added:

- Added `NormattivaUpdateService`.
- Added `ScheduledNormattivaUpdateJob`.
- Added `NormattivaAutomationStatus`.
- Added `NormattivaUpdateResult`.

New API endpoints:

```text
POST /api/normattiva/run
GET  /api/normattiva/run/latest
GET  /api/normattiva/automation
```

Default schedule:

```text
0 45 6 * * *
Europe/Rome
```

Meaning:

```text
Every day at 06:45 Europe/Rome
```

UI changes:

- Added a Normattiva automation panel on Technical Status.
- The panel shows schedule, zone, last scheduled run, state, updates read, and relation rows.
- Added `Run Normattiva Update` manual button.

Project explanation:

- Gazzetta latest updates run through the Gazzetta scheduler.
- Normattiva update discovery now runs through the Normattiva scheduler.
- Both schedulers work only while the Spring Boot app is running.

## 2026-06-16 - Task Started: Reorganize Web UI Into User Pages

Goal:

- Make the browser UI easier to explain and demo.
- Keep the same working crawler, RDF, Jena, search, Normattiva, and SPARQL features.
- Separate normal user features from technical monitoring.

Planned page structure:

- Dashboard / Search
- Act Details
- Normattiva Relations
- SPARQL
- Technical Status

## 2026-06-16 - Task Completed: Reorganize Web UI Into User Pages

Implemented:

- Reworked the UI into clear tabs:

```text
Dashboard
Normattiva
SPARQL
Technical Status
```

- Kept the main Dashboard focused on normal user tasks:

```text
Total Acts
Acts This Year
Normattiva Links
Last Update
Search Legal Acts
Search Results
Selected Act
```

- Moved technical monitoring into the Technical Status tab:

```text
Pipeline flow
Crawler update status
Registry records
RSS links
Raw snapshots
Delta RDF size
RDF source files
Run Update Check
```

- Added a simple Normattiva Relations page with:

```text
Source Act
Relation
Target Act
```

- Added a SPARQL page with example queries and a query preview area.
- Changed user-facing type display from full URI to short labels such as:

```text
DECRETO
ACCORDO
COMUNICATO
```

- Changed user-facing source display to:

```text
Gazzetta Ufficiale
```

- Added selected-act action buttons:

```text
Open Gazzetta
View RDF
Download TTL
Run SPARQL
```

- Removed confusing empty search rows by filtering out relation-only RDF resources from the search API.
- Improved local ID fallback so records can still display a useful ID when RDF metadata is incomplete.
- Fixed the results table layout so act IDs stay on one line.

Changed files:

```text
src/main/java/it/legislation/web/LegalActQueryService.java
src/main/resources/static/index.html
src/main/resources/static/app.js
src/main/resources/static/styles.css
```

Verification:

- Ran `mvn -B test`.
- Result: 20 tests passed, 0 failures.
- Ran `mvn -B compile`.
- Result: build success and 3 static resources copied.
- Checked `GET http://localhost:8080/`.
- Result: HTTP 200.
- Checked `GET http://localhost:8080/api/acts?search=&limit=5`.
- Result: latest records show valid IDs, titles, dates, types, and source values.
- Checked the rendered browser UI.
- Result: tabs are visible, dashboard is clean, latest records show `2026-06-15`, type labels are shortened, and no `No ID / No title` row appears.

## 2026-06-16 - Task Completed: Make Search Rows Selectable

Problem:

- The selected act changed only when the small `View` button was clicked.
- In the table layout, that made it feel like the list itself could not be selected.

Implemented:

- Made each search result row clickable.
- Added keyboard selection with `Enter` and `Space`.
- Kept the `View` button working.
- Added hover and focus styling so rows look selectable.

Changed files:

```text
src/main/resources/static/app.js
src/main/resources/static/styles.css
```

Verification:

- Ran `mvn -B compile`.
- Result: build success and 3 static resources copied.
- Checked the browser UI.
- Result: clicking the second result row changed the selected act from `26A02811` to `26A02812`.

## 2026-06-16 - Task Completed: Polish Dashboard Visual Design

Goal:

- Make the dashboard look closer to the supplied UI reference.
- Keep the existing data flow and API behavior unchanged.

Implemented:

- Added a polished app shell with a main workspace card and a right-side guide card.
- Added visual icons for:

```text
brand
navigation tabs
metric cards
search heading
selected act heading
action buttons
guide panel
```

- Improved dashboard styling:

```text
rounded cards
soft shadows
teal accent hierarchy
larger headings
cleaner spacing
compact result rows
```

- Added a right-side guide panel explaining the UI improvements.
- Improved metric cards so they look like dashboard summary cards.
- Removed the separate `View` result column because rows are clickable.
- Kept the result table focused on:

```text
ID
Title
Publication Date
Type
Source
```

- Added compact two-line title previews in the result table.
- Kept full title and URI values in the selected-act details.
- Added static asset versioning so the browser loads the newest CSS and JavaScript.

Changed files:

```text
src/main/resources/static/index.html
src/main/resources/static/app.js
src/main/resources/static/styles.css
```

Verification:

- Ran `mvn -B compile`.
- Result: build success and 3 static resources copied.
- Ran `git diff --check` for the changed static files.
- Result: no whitespace errors.
- Checked the browser UI at `http://localhost:8080/`.
- Result: new visual layout loads, the guide panel appears on wide screens, the table uses the clean five-column layout, and row selection still works.

## 2026-06-16 - Task Completed: Clean Dashboard For Real App Use

Goal:

- Remove mockup-only UI elements from the real application dashboard.
- Keep the Dashboard focused on summary cards, search, results, and selected act details.

Implemented:

- Removed the right-side `How this UI helps` panel from the Dashboard.
- Renamed the search section from `Dashboard` to:

```text
Search Legal Acts
Find acts by title, local ID, year, type, or source.
```

- Fixed the search form layout so the `Search` button is fully visible.
- Kept the results table focused on:

```text
ID
Title
Publication Date
Type
Source
```

- Improved selected act details:

```text
clean ELI URI display
copy button for ELI URI
Normattiva Status field
more readable detail spacing
```

- Kept crawler and RDF technical monitoring on the Technical Status page.

Changed files:

```text
src/main/resources/static/index.html
src/main/resources/static/app.js
src/main/resources/static/styles.css
```

Verification:

- Ran `mvn -B compile`.
- Result: build success and 3 static resources copied.
- Ran `git diff --check` for changed static files.
- Result: no whitespace errors.
- Checked the browser UI at `http://localhost:8080/`.
- Result: guide panel removed, Search button fully visible, ELI URI displays on one line with a Copy button, Normattiva Status appears in selected act details, and row selection still works.

## 2026-06-16 - Task Completed: Build Interactive SPARQL Explorer

Goal:

- Make the SPARQL page usable instead of showing only a static query preview.
- Allow predefined and custom SPARQL SELECT queries to run against the loaded RDF model.

Implemented backend:

- Added `POST /api/sparql`.
- Added request and response models:

```text
SparqlQueryRequest
SparqlQueryResult
```

- Added Jena SELECT query execution in `LegalActQueryService`.
- Limited SPARQL explorer execution to SELECT queries.
- Added controlled query error response for invalid syntax.

Implemented frontend:

- Added SPARQL Explorer page structure:

```text
Example Queries
SPARQL Query editor
Run Query
Clear
Copy Query
Open Fuseki Endpoint
Query Results
Error message area
```

- Made the SPARQL query box editable with a textarea.
- Added active green highlight for the selected example query.
- Added predefined queries:

```text
All acts
Acts by year
Acts by type
Latest acts
Acts with Normattiva relations
```

- Added query results table.
- Added invalid-query error display.

Changed files:

```text
src/main/java/it/legislation/web/LegalActApiController.java
src/main/java/it/legislation/web/LegalActQueryService.java
src/main/java/it/legislation/web/SparqlQueryRequest.java
src/main/java/it/legislation/web/SparqlQueryResult.java
src/main/resources/static/index.html
src/main/resources/static/app.js
src/main/resources/static/styles.css
src/test/java/it/legislation/web/LegalActQueryServiceTest.java
```

Verification:

- Ran `mvn -B test`.
- Result: 21 tests passed, 0 failures.
- Checked `POST http://localhost:8080/api/sparql`.
- Result: endpoint returns SPARQL result columns and rows.
- Checked browser SPARQL tab.
- Result: `Run Query` returns 20 rows for `All acts`.
- Checked invalid query behavior.
- Result: UI shows `Query error: missing prefix or invalid syntax.`

## 2026-06-16 - Task Completed: Improve Technical Status Page

Goal:

- Make the Technical Status page clearly explain the crawler/RDF/Jena pipeline.
- Show loaded RDF source files with metadata and working download actions.

Implemented backend:

- Added RDF source metadata model:

```text
RdfSourceFile
```

- Added endpoint:

```text
GET /api/rdf/sources
```

- Added file download endpoint:

```text
GET /api/rdf/files/{fileName}
```

- Restricted downloads to known Turtle files:

```text
gazzetta_metadata_delta.ttl
normattiva_modifications.ttl
```

Implemented frontend:

- Kept the four system summary cards:

```text
Triples
RDF Files
Missing Data
Registry Records
```

- Improved the pipeline flow:

```text
Discover -> Crawl -> Clean -> Generate RDF -> Explore
```

- Improved crawler update status cards:

```text
RSS Links
Raw Snapshots
Delta RDF
Last Checked
```

- Kept `Run Update Check` for manual demo updates.
- Replaced the simple RDF source list with a table:

```text
Status
File Name
Description
Last Modified
Size
Action
```

- Added working download links for loaded RDF Turtle files.

Changed files:

```text
src/main/java/it/legislation/web/LegalActApiController.java
src/main/java/it/legislation/web/RdfSourceFile.java
src/main/resources/static/index.html
src/main/resources/static/app.js
src/main/resources/static/styles.css
```

Verification:

- Ran `mvn -B test`.
- Result: 21 tests passed, 0 failures.
- Checked `GET http://localhost:8080/api/rdf/sources`.
- Result: both RDF files return status, description, last modified date, size, and download URL.
- Checked `GET http://localhost:8080/api/rdf/files/gazzetta_metadata_delta.ttl`.
- Result: returns attachment download header.
- Checked browser Technical Status tab.
- Result: summary cards, pipeline, crawler cards, registry counts, RDF table, and two download buttons are visible.

## 2026-06-16 - Task Completed: Add Scheduled Crawler Automation

Goal:

- Make the crawler update process automatic, not only manual through the UI button.
- Keep the manual `Run Update Check` button for demos and immediate validation.

Implemented backend:

- Enabled Spring scheduling in the web application.
- Added scheduled crawler job:

```text
ScheduledCrawlerUpdateJob
```

- Added automation status model:

```text
CrawlerAutomationStatus
```

- Added endpoint:

```text
GET /api/crawl/automation
```

- Default schedule:

```text
enabled: true
cron: 0 15 6 * * *
zone: Europe/Rome
max RSS entries: 50
max links: 0
```

Meaning:

- The app checks Gazzetta RSS automatically every day at 06:15 Europe/Rome time.
- It reuses the same update service as the manual button.
- It only crawls links that are not already in the registry.

Implemented frontend:

- Added automation summary in Technical Status:

```text
Automatic updates enabled
Schedule
Zone
RSS entries
Last scheduled run
```

- Kept `Run Update Check` for manual runs.

Changed files:

```text
src/main/java/it/legislation/LegalDataWebApplication.java
src/main/java/it/legislation/web/CrawlerAutomationStatus.java
src/main/java/it/legislation/web/ScheduledCrawlerUpdateJob.java
src/main/java/it/legislation/web/LegalActApiController.java
src/main/resources/static/index.html
src/main/resources/static/app.js
src/main/resources/static/styles.css
```

Verification:

- Ran `mvn -B test`.
- Result: 21 tests passed, 0 failures.
- Checked `GET http://localhost:8080/api/crawl/automation`.
- Result: schedule status returns enabled, cron, zone, max entries, and last run status.
- Checked browser Technical Status tab.
- Result: automation summary displays `Automatic updates enabled`, schedule `0 15 6 * * *`, zone `Europe/Rome`, and `not run yet`.

## 2026-06-17 - Task Completed: Demo Validation Pass

Goal:

- Validate the full local demo flow before adding more features.
- Confirm the API, RDF loading, crawler status, automation status, Normattiva relations, SPARQL query execution, and RDF downloads are working.

Checked endpoints:

```text
GET /api/health
GET /api/acts?search=&limit=3
GET /api/crawl/status
GET /api/crawl/automation
GET /api/normattiva/modifications?limit=10
POST /api/sparql
GET /api/rdf/sources
GET /api/rdf/files/gazzetta_metadata_delta.ttl
```

Validation results:

```text
Triples loaded: 326
RDF files loaded: 2
Missing files: 0
Registry records: 32
RSS links: 32
Raw snapshots: 32
RDF delta size: 37 KB
Latest publication date: 2026-06-15
Registry status counts: NEW 27, UNCHANGED 5
Automation: enabled
Automation cron: 0 15 6 * * *
Automation zone: Europe/Rome
Normattiva relations returned: 6
SPARQL query returned rows: yes
RDF download endpoint: returns attachment
```

Conclusion:

- The local demo flow is ready to show:

```text
Dashboard -> Search -> Selected Act -> Normattiva -> SPARQL -> Technical Status -> RDF Download -> Automation Status
```

## 2026-06-17 - Task Completed: Historical Crawl Discovery Research

Goal:

- Decide the safest discovery method for crawling more than latest RSS data.
- Avoid guessing ELI URLs for historical data.

Checked official Gazzetta pages:

```text
https://www.gazzettaufficiale.it/home
https://www.gazzettaufficiale.it/archivioCompleto
https://www.gazzettaufficiale.it/ricercaArchivioCompleto/serie_generale/2026
https://www.gazzettaufficiale.it/gazzetta/serie_generale/caricaDettaglio?dataPubblicazioneGazzetta=2026-06-15&numeroGazzetta=136
```

Findings:

- The home page exposes:

```text
Ultime Gazzette Pubblicate
Elenco delle Gazzette Ufficiali pubblicate negli ultimi 30 giorni
Archivio completo
```

- The full archive page exposes yearly lists for each series.
- For Serie Generale, the year archive pattern is:

```text
/ricercaArchivioCompleto/serie_generale/{year}
```

- A year archive page lists issue numbers and publication dates.
- An issue detail page pattern is:

```text
/gazzetta/serie_generale/caricaDettaglio?dataPubblicazioneGazzetta=YYYY-MM-DD&numeroGazzetta=N
```

- The issue detail page contains act links with `codiceRedazionale`, for example:

```text
/atto/serie_generale/caricaDettaglioAtto/originario?atto.codiceRedazionale=26A02811&atto.dataPubblicazioneGazzetta=2026-06-15&elenco30giorni=false
```

Recommended historical crawl design:

```text
1. Open year archive page.
2. Parse issue date + issue number links.
3. Filter issues by requested date range.
4. Open each issue detail page.
5. Extract act detail links and codiceRedazionale IDs.
6. Convert or store each act source URL.
7. Skip acts already known in the registry.
8. Crawl only missing acts.
9. Save raw HTML snapshots.
10. Generate RDF and update registry.
```

Conclusion:

- Best discovery method is archive-driven crawling, not URL guessing.
- Start with a small range such as:

```text
2026-06-01 to 2026-06-16
```

## 2026-06-17 - Task Completed: Add Git Ignore Rules For Local Artifacts

Goal:

- Reduce noisy Git status output before continuing with more project work.
- Keep generated crawler snapshots, local notes, IDE metadata, and temporary artifacts out of future commits.

Implemented:

- Added root `.gitignore`.
- Ignored local build output:

```text
target/
*.class
```

- Ignored IDE metadata:

```text
.classpath
.project
.settings/
.idea/
*.iml
```

- Ignored local notes and planning folders:

```text
docs/
context/
IMPLEMENTATION_PLAN.md
.github/
```

- Ignored generated crawler/runtime files:

```text
jena-git-project/data/raw/**/*.html
jena-git-project/data/rdf/gazzetta_metadata_delta.ttl
jena-git-project/src/eli_metadata_generated.ttl
jena-git-project/output/
```

Verification:

- Ran `git status --short`.
- Result: large untracked generated raw HTML list is hidden.
- Ran `git check-ignore`.
- Result: docs, context, accidental command artifact, raw HTML snapshots, and generated RDF delta are ignored.

Note:

- Some noisy files are already tracked by Git, so `.gitignore` cannot hide those existing tracked modifications by itself.

## 2026-06-14 - Task Started: Add Crawler Update Status To Web Demo

Goal:

- Add a read-only crawler/update status endpoint.
- Show current crawler data health in the web UI.
- Use existing generated files instead of triggering a new crawl from the browser.

Planned backend endpoint:

```text
GET /api/crawl/status
```

Planned UI information:

- Registry record count.
- Registry status counts such as NEW/CHANGED/UNCHANGED.
- RSS discovered link count.
- Latest RSS fetch time.
- Generated RDF delta file size and modified time.
- Raw HTML snapshot count.

## 2026-06-14 - Task Completed: Add Crawler Update Status To Web Demo

Implemented backend:

- Added `CrawlerStatus` response record.
- Added `CrawlerStatusService`.
- Added endpoint:

```text
GET /api/crawl/status
```

- The endpoint reads:

```text
data/registry/crawl_registry.tsv
data/clean/gazzetta_rss_updates.tsv
data/rdf/gazzetta_metadata_delta.ttl
data/raw/gazzetta/*.html
```

Implemented UI:

- Added a `Crawler Update Status` panel to the project mockup.
- The panel shows:

```text
registry records
RSS links
raw HTML snapshots
delta RDF size
registry status counts
latest publication date
last checked time
```

Current local status:

```text
registry records: 16
RSS links: 16
raw snapshots: 16
delta RDF: 33752 bytes
status counts: NEW = 16
missing files: 0
```

Verification:

- Ran `mvn -B test`.
- Result: 18 tests passed, 0 failures.
- Restarted the local Spring Boot app.
- Checked `GET http://localhost:8080/api/crawl/status`.
- Result: endpoint returned crawler status JSON.
- Checked `GET http://localhost:8080/`.
- Result: HTTP 200.
- Checked legal act search endpoint.
- Result: search still returned local id `26G00117`.
- Captured browser preview:

```text
target/crawler-status-mockup-preview.png
```

## 2026-06-14 - Task Started: Add Manual Run Update Check

Goal:

- Add a controlled web action to run the update pipeline manually.
- Keep the action limited so it does not crawl too many links from the browser.
- Show the result in the dashboard after the run finishes.

Planned backend:

```text
POST /api/crawl/run?maxEntries=20&maxLinks=5
```

Planned behavior:

- Fetch latest RSS entries.
- Append only new RSS links to the update TSV.
- Crawl a limited number of links.
- Update the crawl registry.
- Refresh the crawler status panel.
- Prevent two update checks from running at the same time.

## 2026-06-14 - Task Completed: Add Manual Run Update Check

Implemented backend:

- Added `CrawlerUpdateResult`.
- Added `CrawlerUpdateService`.
- Added endpoint:

```text
POST /api/crawl/run?maxEntries=20&maxLinks=5
```

- Added endpoint:

```text
GET /api/crawl/run/latest
```

- Reused existing crawler code:

```text
GazzettaRssUpdateRunner
GazzettaScraper
```

- Added protection so only one update check can run at a time.
- Added limits so the browser action does not crawl too many links:

```text
default max RSS entries: 20
default max crawl links: 5
hard max RSS entries: 100
hard max crawl links: 25
```

Implemented UI:

- Added `Run Update Check` button inside the crawler status panel.
- Added update result summary:

```text
last run status
RSS entries read
RSS entries added
links crawled
changed records
```

Testing:

- Added `CrawlerUpdateServiceTest`.
- The test uses fake RSS and fake crawler functions, so it does not call the real network.
- Ran `mvn -B test`.
- Result: 19 tests passed, 0 failures.

Local app verification:

- Restarted Spring Boot.
- Checked `GET http://localhost:8080/`.
- Result: HTTP 200.
- Checked `GET http://localhost:8080/api/crawl/status`.
- Result: endpoint returned crawler status JSON.
- Checked `GET http://localhost:8080/api/crawl/run/latest`.
- Result: HTTP 204 before any manual run.
- Captured browser preview:

```text
target/manual-update-button-preview.png
```

Note:

- The live `Run Update Check` button was not clicked during verification, to avoid changing local data without an explicit manual action.

## 2026-06-14 - Task Started: Add Normattiva Relationship View

Goal:

- Make Normattiva modification relationships visible in the web demo.
- Query the generated Normattiva Turtle file directly.
- Show simple act-to-act links using actual legal URIs.

Planned backend endpoint:

```text
GET /api/normattiva/modifications?limit=20
```

Planned UI:

- Add a Normattiva relationships panel.
- Show source act, relationship type, and target act.
- Keep it read-only for now.

## 2026-06-16 - Task Completed: Add Normattiva Relationship View

Implemented backend:

- Added `NormattivaModificationSummary`.
- Added `NormattivaQueryService`.
- Added endpoint:

```text
GET /api/normattiva/modifications?limit=20
```

- The endpoint reads:

```text
data/rdf/normattiva_modifications.ttl
```

- It returns simple rows:

```text
source URI
source local id
relationship type
target URI
target local id
```

Implemented UI:

- Added a `Normattiva Relationships` panel to the dashboard.
- Shows modification graph rows as:

```text
Source act -> modifies/conversion -> Target act
```

- Uses actual legal act URIs from the generated RDF graph.

Verification:

- Ran `mvn -B test`.
- Result: 20 tests passed, 0 failures.
- Restarted the local Spring Boot app.
- Checked `GET http://localhost:8080/`.
- Result: HTTP 200.
- Checked `GET http://localhost:8080/api/normattiva/modifications?limit=5`.
- Result: endpoint returned real Normattiva relationship rows.
- Captured browser preview:

```text
target/normattiva-panel-preview.png
```

## 2026-06-16 - Task Started: Crawl Only New RSS Links

Goal:

- Remove the unsafe "first 5 links" behavior from the manual update flow.
- Use the crawl registry as the validation source.
- Crawl RSS links only when their canonical ELI URI is not already present in `crawl_registry.tsv`.

Reason:

- The RSS TSV stores older links first and newer links later.
- A fixed first-5 crawl can miss the newest publication date.
- If 15 new acts appear in RSS, the update flow should be able to process all 15 unseen candidates.

## 2026-06-16 - Task Completed: Crawl Only New RSS Links

Implemented:

- Updated `CrawlerUpdateService`.
- The update flow now:

```text
fetches latest RSS entries
appends new RSS rows to gazzetta_rss_updates.tsv
normalizes each RSS link into a canonical ELI URI
reads existing ELI URIs from crawl_registry.tsv
crawls only RSS links missing from the registry
```

- Removed the old fixed first-5 link behavior from the UI request.
- `maxLinks=0` now means no fixed link limit for unseen RSS candidates.
- The dashboard button now calls:

```text
POST /api/crawl/run?maxEntries=20
```

Validation logic:

```text
RSS candidate is found
canonical ELI URI is checked against registry
if registry already has it, skip
if registry does not have it, crawl
```

Testing:

- Updated `CrawlerUpdateServiceTest`.
- Test verifies that a known registry URI is skipped.
- Test verifies that unseen RSS links are crawled.
- Ran `mvn -B test`.
- Result: 20 tests passed, 0 failures.

Local app:

- Restarted Spring Boot.
- Checked `GET http://localhost:8080/`.
- Result: HTTP 200.
- Checked `GET http://localhost:8080/api/crawl/status`.
- Result: endpoint returned current crawler status.

Note:

- The live update button was not clicked during this verification.
- Current local data still shows latest crawled publication date `2026-06-12`.
- The next manual update run should target unseen RSS links such as the `2026-06-15` candidates.

## 2026-06-16 - Task Started: Reorganize Web UI Into User Pages

Goal:

- Replace the single long dashboard with clear application pages.
- Keep all existing features, but separate user-facing search from technical monitoring.
- Remove confusing search rows such as `No ID` / `No title`.

Planned pages:

```text
Dashboard / Search
Normattiva Relations
SPARQL
Technical Status
```

Planned improvements:

- Main page focuses on legal act search.
- Technical pipeline status moves to Technical Status.
- Normattiva relationships move to their own page.
- Raw type URIs are displayed as short labels such as `DECRETO` or `LEGGE`.
- Source is displayed as `Gazzetta Ufficiale` instead of a long URI.
- Search results require useful metadata and relationship-only nodes are hidden from the main table.
- Started the Spring Boot API locally on port `8080`.
- Checked `GET http://localhost:8080/api/health`.
- Result: loaded 327 triples from the generated Gazzetta and Normattiva RDF files.
- Checked `GET http://localhost:8080/api/acts?search=26G00117&limit=3`.
- Result: returned the Gazzetta legal act with local id `26G00117`.
- Checked `GET http://localhost:8080/api/acts/26G00117`.
- Result: returned a single JSON legal act record.

Current API status:

- Running locally at:

```text
http://localhost:8080
```

Next step:

- Add a simple React UI that calls these endpoints.

## 2026-06-14 - Task Started: Add Simple React UI For RDF API

Goal:

- Add a simple browser UI for the existing Spring Boot REST API.
- Let the user search legal acts by title, local id, or URI.
- Show API/RDF status and result details.

Reason:

- This is the first web-facing layer over the generated Gazzetta and Normattiva RDF data.
- The UI should stay small and easy to explain before adding a larger frontend build system.

## 2026-06-14 - Task Completed: Add Simple React UI For RDF API

Implemented:

- Added a static React UI served by Spring Boot from:

```text
src/main/resources/static/
```

- Added files:

```text
index.html
styles.css
app.js
```

- The UI calls the existing REST endpoints:

```text
GET /api/health
GET /api/acts?search=...&limit=...
GET /api/acts/{localId}
```

- The first page now shows:

```text
RDF/API status
loaded RDF files
triple count
search box
legal act result table
selected legal act details
simple pipeline flow
```

Verification:

- Ran Maven compile so static files were copied into the Spring Boot target output.
- Restarted the Spring Boot server.
- Checked `GET http://localhost:8080/`.
- Result: page returned HTTP 200.
- Checked `GET http://localhost:8080/app.js`.
- Result: JavaScript returned HTTP 200.
- Checked `GET http://localhost:8080/styles.css`.
- Result: stylesheet returned HTTP 200.
- Rechecked `GET http://localhost:8080/api/acts?search=26G00117&limit=3`.
- Result: API still returned the expected legal act record.

Current demo URL:

```text
http://localhost:8080/
```

Important note:

- The UI currently uses React from a CDN, so the browser needs internet access to load the React scripts.
- This was chosen to keep the first web demo simple and avoid adding a full Node/Vite build step yet.

## 2026-06-14 - Verification: Web API And UI Test Checkpoint

Command:

```text
mvn -B test
```

Result:

```text
17 tests passed
0 failures
0 errors
```

Checked locally:

```text
GET http://localhost:8080/api/health
```

Result:

```text
327 triples loaded
Gazzetta RDF loaded
Normattiva RDF loaded
0 missing RDF files
```

Meaning:

- The crawler/RDF layer, simplified Normattiva graph, Spring Boot API, and first browser UI are now connected enough for a local thesis/demo walkthrough.

## 2026-06-14 - Task Started: Build Project Mockup Dashboard

Goal:

- Improve the first browser UI into a clearer project mockup.
- Show the project flow from source discovery to RDF and API search.
- Keep the mockup connected to the real local API and generated RDF files.

Planned UI areas:

- Project status summary.
- Data flow pipeline.
- Legal act search.
- Selected legal act details.
- RDF source file status.
- Demo query preview.

## 2026-06-14 - Task Completed: Build Project Mockup Dashboard

Implemented:

- Updated the browser UI into a project mockup dashboard.
- Kept the UI connected to the real Spring Boot API.
- Added top status summary for:

```text
triples
RDF files
missing files
current search results
```

- Added project flow section:

```text
Discover -> Crawl -> Clean -> Publish -> Explore
```

- Reworked legal act search into a cleaner result-list layout.
- Reworked selected legal act details into a clearer record panel.
- Added RDF source status panel.
- Added demo SPARQL query preview.

Changed files:

```text
src/main/resources/static/index.html
src/main/resources/static/app.js
src/main/resources/static/styles.css
```

Verification:

- Ran `mvn -B compile`.
- Result: build success and 3 static resources copied.
- Checked `GET http://localhost:8080/`.
- Result: HTTP 200.
- Checked `GET http://localhost:8080/api/acts?search=26G00117&limit=3`.
- Result: API returned the expected legal act.
- Captured browser preview:

```text
target/project-mockup-preview-loaded.png
```

- Ran `mvn -B test`.
- Result: 17 tests passed, 0 failures.
