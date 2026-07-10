# Crawler Pipeline

This diagram explains how source data becomes RDF.

```mermaid
flowchart TD
    START["Start Update"]

    RSS["Read Gazzetta RSS Feed"]
    LINKS["Extract Legal Act Links"]
    REGISTRY["Check Crawl Registry<br/>data/registry/crawl_registry.tsv"]
    FETCH["Fetch Gazzetta Act HTML"]
    CACHE["Save Raw HTML<br/>data/raw/gazzetta/*.html"]
    EXTRACT["Extract ELI Metadata<br/>title, publication date, local ID, type, source"]
    RECORD["Create CleanLegalActRecord"]
    GRDF["Build RDF with RdfModelBuilder"]
    GFILE["Write / Merge Turtle<br/>data/rdf/gazzetta_metadata_delta.ttl"]

    NSTART["Run Normattiva Update"]
    NHTML["Fetch Normattiva Update Page"]
    NEXTRACT["Extract Normattiva Act Links"]
    NINFER["Infer Modification Records<br/>modifier -> modified act"]
    NTSV["Write Clean TSV<br/>data/clean/normattiva_modifications_auto.tsv"]
    NRDF["Build RDF with ModificationRdfModelBuilder"]
    NFILE["Write Turtle<br/>data/rdf/normattiva_modifications_auto.ttl"]

    LOAD["Load RDF Files Together<br/>Apache Jena Model"]
    WEB["Search / Relationships / SPARQL<br/>Web Dashboard"]

    START --> RSS --> LINKS --> REGISTRY
    REGISTRY -->|new or changed| FETCH --> CACHE --> EXTRACT --> RECORD --> GRDF --> GFILE
    REGISTRY -->|already known| SKIP["Skip unchanged act"]

    NSTART --> NHTML --> NEXTRACT --> NINFER --> NTSV --> NRDF --> NFILE

    GFILE --> LOAD
    NFILE --> LOAD
    LOAD --> WEB
```

Short explanation:

```text
The crawler does not store data in a heavy database.
It saves raw source files, extracts clean legal metadata, and writes simple RDF Turtle files.
The backend later loads those Turtle files into Jena for search and SPARQL.
```
