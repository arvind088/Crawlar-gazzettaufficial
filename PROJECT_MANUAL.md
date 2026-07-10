# Legal RDF Explorer - Project Manual

This manual explains how to run, use, validate, and troubleshoot the Legal RDF Explorer project.

It is a practical user/developer manual, not a thesis report.

## 1. Project Purpose

Legal RDF Explorer is a lightweight academic prototype for exploring Italian legislative data as RDF.

It collects selected metadata from Gazzetta Ufficiale, adds relationship data from Normattiva, writes simple RDF/Turtle files, loads those files with Apache Jena in memory, and provides a clear web interface for searching and demonstrating the result.

The project intentionally avoids heavy production architecture such as TDB2/Fuseki deployment, authentication, or a large graph visualization layer. The goal is to make the data integration and RDF workflow easy to understand and defend.

The project supports:

- Gazzetta latest RSS discovery.
- Historical archive discovery.
- Controlled archive batch crawling.
- Raw HTML snapshot storage.
- Crawl registry tracking.
- RDF/Turtle generation.
- Jena in-memory search and SPARQL queries.
- Normattiva relationship preview.
- Lightweight status page.
- GitHub Actions CI.

## 2. Technology Stack

- Java 17
- Maven
- Spring Boot
- Apache Jena
- JSoup
- React loaded from static resources
- GitHub Actions

## 3. Folder Structure

```text
Crawlar-gazzettaufficial/
  docs/
    diagrams/
  .github/workflows/
    ci.yml
    legal-data-pipeline.yml
  jena-git-project/
    pom.xml
    src/main/java/it/legislation/
    src/main/resources/static/
    src/test/java/
    data/
      clean/
      raw/
      registry/
      rdf/
    queries/
```

Important runtime files:

```text
data/clean/gazzetta_rss_updates.tsv
data/clean/gazzetta_archive_links.tsv
data/registry/crawl_registry.tsv
data/raw/gazzetta/
data/rdf/gazzetta_metadata_delta.ttl
data/rdf/normattiva_modifications.ttl
data/rdf/normattiva_modifications_auto.ttl
```

Some generated runtime files are intentionally ignored by Git because they are produced locally by the crawler.

## 4. Requirements

Install:

```text
Java 17
Maven
Git
```

Check installation:

```powershell
java -version
mvn -version
git --version
```

Java should show version 17.

## 5. Clone and Run

Clone the repository:

```powershell
git clone https://github.com/arvind088/Crawlar-gazzettaufficial.git
cd Crawlar-gazzettaufficial
cd jena-git-project
```

Run tests:

```powershell
mvn -B test
```

Start the web application:

```powershell
mvn -B spring-boot:run
```

Open the app:

```text
http://localhost:8080/
```

## 6. Web App Pages

The UI is intentionally small and demo-friendly.

### Legal Acts

Use this page for the main legal data exploration demo.

Main features:

- Lightweight summary cards.
- Keyword / act ID search.
- Paginated results, 20 rows per page.
- Select an act from the table.
- View selected act details.
- View Normattiva relations for the selected act.
- Open Gazzetta source page.
- View RDF for one act.
- Download TTL for one act.
- Send the selected act to SPARQL.

### Relationships

Use this page to view loaded Normattiva legal relationships.

It shows:

```text
Source Act | Relation | Target Act
```

This page demonstrates how Gazzetta acts can be connected with Normattiva-style modification relationships.

### SPARQL

Use this page for simple RDF query demonstration.

Features:

- Editable SPARQL query box.
- Example query buttons.
- Run Query.
- Clear.
- Copy Query.
- Query results table.

### Status

Use this page for lightweight validation.

It shows:

- RDF triples loaded.
- RDF files loaded.
- Registry records.
- Latest crawler status.
- RDF source files and downloads.

## 7. Main Demo Flow

Use this sequence for a clean project demo:

```text
1. Open http://localhost:8080/
2. Go to Legal Acts.
3. Search for a legal act by keyword or local ID.
4. Select an act from the paginated result table.
5. Show title, date, source, ELI URI, and Normattiva Relations.
6. Click Open Gazzetta to show the original public source.
7. Click View RDF to show the generated Turtle for the selected act.
8. Click Download TTL if a file export is needed.
9. Click Run SPARQL to open an act-specific query.
10. Go to Relationships and show Source Act -> Relation -> Target Act.
11. Go to Status and show triples, RDF files, registry records, and source files.
```

## 8. Diagrams

Project diagrams are stored here:

```text
docs/diagrams/
```

Available diagrams:

```text
docs/diagrams/overall-system-architecture.md
docs/diagrams/crawler-pipeline.md
docs/diagrams/knowledge-graph-data-model.md
```

Use them to explain:

```text
Overall System Architecture
Crawler Pipeline
Knowledge Graph / Data Model
```

## 9. Latest RSS Update

The latest update flow reads the Gazzetta RSS feed, stores discovered links, checks the registry, crawls new or changed acts, and generates RDF delta output.

From the web app:

```text
Status -> Run Update Check
```

From terminal:

```powershell
cd C:\Users\HP\git\Crawlar-gazzettaufficial\jena-git-project
mvn -B "-Dexec.mainClass=it.legislation.crawler.GazzettaRssUpdateRunner" exec:java
```

## 10. Historical Archive Flow

The archive flow has two steps.

First, discover archive links:

```text
Status -> Discover Archive Links
```

Then crawl a controlled batch:

```text
Status -> Crawl Archive Batch
```

The batch limit cannot exceed the available pending links.

Terminal discovery:

```powershell
cd C:\Users\HP\git\Crawlar-gazzettaufficial\jena-git-project
$env:GAZZETTA_ARCHIVE_START="2026-06-16"
$env:GAZZETTA_ARCHIVE_END="2026-06-16"
mvn -B "-Dexec.mainClass=it.legislation.crawler.GazzettaArchiveDiscoveryRunner" exec:java
```

Terminal crawl:

```powershell
$env:GAZZETTA_ARCHIVE_CRAWL_LIMIT="10"
mvn -B "-Dexec.mainClass=it.legislation.crawler.GazzettaArchiveCrawlRunner" exec:java
```

## 11. Normattiva Update Flow

The project can download update information from Normattiva and generate an automatic RDF file for inferred update relationships.

The automatic Normattiva flow is:

```text
Download Normattiva homepage/update content
        |
Extract Normattiva act links
        |
Write normattiva_updates.tsv
        |
Infer simple relation pairs when an update links multiple acts
        |
Write normattiva_modifications_auto.tsv
        |
Generate normattiva_modifications_auto.ttl
        |
Load in Jena / UI / SPARQL
```

Run from terminal:

```powershell
cd C:\Users\HP\git\Crawlar-gazzettaufficial\jena-git-project
mvn -B "-Dexec.mainClass=it.legislation.crawler.NormattivaUpdateRunner" exec:java
```

Generated files:

```text
data/clean/normattiva_updates.tsv
data/clean/normattiva_modifications_auto.tsv
data/rdf/normattiva_modifications_auto.ttl
```

### Normattiva Relation Metadata Backfill

Some Normattiva relationship RDF rows contain only this information:

```text
source act URI -> relation -> target act URI
```

At that stage, the act may be searchable but may still show missing title, publication date, document date, type, and source.

For Gazzetta ELI URIs, run this backfill:

```powershell
cd C:\Users\HP\git\Crawlar-gazzettaufficial\jena-git-project
mvn -B "-Dexec.mainClass=it.legislation.crawler.NormattivaGazzettaMetadataBackfillRunner" exec:java
```

This runner:

```text
Reads normattiva_modifications.ttl and normattiva_modifications_auto.ttl
Finds Gazzetta ELI URLs
Crawls those Gazzetta pages
Extracts title/date/type/source metadata
Merges the metadata into data/rdf/gazzetta_metadata_delta.ttl
```

Example:

```text
Before backfill:
25G00006 -> searchable, but title/date/type missing

After backfill:
25G00006 -> title/date/type/source available from Gazzetta metadata
```

Important:

```text
This automatic flow discovers update links from Normattiva.
It can infer simple relationships when the same update item links multiple acts.
More advanced article-level modification extraction remains future work.
```

## 12. Automation

The project has two automation mechanisms.

### Spring Boot Scheduler

This is active when the web application is running.

It runs both:

```text
Gazzetta latest RSS update
Normattiva update discovery
```

Default Gazzetta schedule:

```text
Every day at 06:15 Europe/Rome
```

Default Normattiva schedule:

```text
Every day at 06:45 Europe/Rome
```

Important:

```text
The schedulers run only while the Spring Boot app is running.
```

Manual buttons are also available through backend endpoints and can be shown on the Status page if needed:

```text
Run Update Check
Run Normattiva Update
```

### GitHub Actions

The project includes:

```text
.github/workflows/ci.yml
.github/workflows/legal-data-pipeline.yml
```

CI workflow:

```text
Runs mvn -B test on push, pull request, and manual dispatch.
```

Legal data pipeline workflow:

```text
Manual workflow for validating crawler and RDF pipeline steps on GitHub.
```

## 13. Useful API Endpoints

Health:

```text
GET /api/health
```

Search acts:

```text
GET /api/acts?search=26A02919&limit=10
```

One act:

```text
GET /api/acts/{localId}
```

One act RDF:

```text
GET /api/acts/{localId}/rdf
```

Download one act TTL:

```text
GET /api/acts/{localId}/rdf?download=true
```

Run SPARQL:

```text
POST /api/sparql
```

Crawler status:

```text
GET /api/crawl/status
```

Run latest update:

```text
POST /api/crawl/run?maxEntries=20
```

Discover archive links:

```text
POST /api/archive/discover?startDate=2026-06-16&endDate=2026-06-16
```

Crawl archive batch:

```text
POST /api/archive/crawl?limit=10
```

RDF source files:

```text
GET /api/rdf/sources
```

Normattiva relationships:

```text
GET /api/normattiva/modifications
```

## 14. Example SPARQL Queries

List latest acts:

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?act ?title ?date ?type WHERE {
  ?act a eli:LegalResource .
  OPTIONAL { ?act rdfs:label ?title . }
  OPTIONAL { ?act eli:date_publication ?date . }
  OPTIONAL { ?act eli:type_document ?type . }
}
ORDER BY DESC(?date)
LIMIT 10
```

Find acts by local ID:

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?act ?title ?date WHERE {
  ?act eli:id_local "26A02919" .
  OPTIONAL { ?act rdfs:label ?title . }
  OPTIONAL { ?act eli:date_publication ?date . }
}
LIMIT 10
```

Show relationships:

```sparql
PREFIX ilg: <http://example.org/italian-legislation/ontology#>

SELECT ?source ?relation ?target WHERE {
  ?source ?relation ?target .
  FILTER(?relation IN (ilg:modifies, ilg:modifiedBy))
}
LIMIT 20
```

## 15. Validation Checklist

Before showing the project, check:

```text
mvn -B test
```

Expected:

```text
BUILD SUCCESS
```

Check app:

```text
http://localhost:8080/
```

Check API:

```text
http://localhost:8080/api/health
```

Expected:

```text
triples > 0
missingFiles = []
```

Check selected act:

```text
View RDF works
Download TTL works
Run SPARQL opens query page
```

Check Status:

```text
RDF files loaded
Registry records visible
Crawler status visible
```

## 16. Troubleshooting

### Port 8080 already in use

Find process:

```powershell
netstat -ano | Select-String ":8080"
```

Stop process:

```powershell
Stop-Process -Id <PID> -Force
```

Start again:

```powershell
mvn -B spring-boot:run
```

### No data appears in Legal Acts

Run one of these:

```text
Status -> Run Update Check
```

or:

```text
Status -> Discover Archive Links
Status -> Crawl Archive Batch
```

Then refresh Legal Acts.

### Archive crawl has zero available links

Run discovery first:

```text
Status -> Discover Archive Links
```

Then crawl:

```text
Status -> Crawl Archive Batch
```

### GitHub Action does not appear

Make sure `.github/workflows/ci.yml` is committed and pushed.

Check:

```powershell
git status
git log --oneline -5
```

Then open:

```text
GitHub repository -> Actions tab
```

## 17. What Is Future Work

These items are intentionally left for later:

- Long-term storage decision for many years of data, such as Jena TDB2, Fuseki, PostgreSQL, or another persistent database.
- More advanced automatic Normattiva extraction.
- More detailed user roles and authentication.

## 18. Quick Commands

Run tests:

```powershell
cd C:\Users\HP\git\Crawlar-gazzettaufficial\jena-git-project
mvn -B test
```

Start app:

```powershell
mvn -B spring-boot:run
```

Open app:

```text
http://localhost:8080/
```

Check health:

```powershell
Invoke-WebRequest http://localhost:8080/api/health -UseBasicParsing
```

Check Git:

```powershell
git status
git log --oneline -5
```
