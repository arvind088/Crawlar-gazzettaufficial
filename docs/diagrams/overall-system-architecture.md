# Overall System Architecture

This diagram shows the full lightweight architecture of the project.

```mermaid
flowchart TD
    GU["Gazzetta Ufficiale<br/>Website + RSS Feed"]
    NA["Normattiva<br/>Website / Update Data"]

    GCR["Gazzetta Crawler<br/>Java + JSoup"]
    NCR["Normattiva Updater<br/>Java + JSoup"]

    RAW["Raw HTML Cache<br/>data/raw/gazzetta/"]
    GTTL["Gazzetta RDF File<br/>data/rdf/gazzetta_metadata_delta.ttl"]
    NTTL["Normattiva RDF File<br/>data/rdf/normattiva_modifications_auto.ttl"]
    MTTL["Manual Relationship RDF<br/>data/rdf/normattiva_modifications.ttl"]

    JENA["Apache Jena<br/>In-Memory RDF Model"]
    API["Spring Boot Backend API"]
    UI["Lightweight Web Dashboard"]
    USER["End User / Demo User"]

    GU --> GCR
    GCR --> RAW
    GCR --> GTTL

    NA --> NCR
    NCR --> NTTL
    MTTL --> JENA

    GTTL --> JENA
    NTTL --> JENA

    JENA --> API

    API --> ACTS["Search Legal Acts<br/>/api/acts"]
    API --> DETAILS["Selected Act Details<br/>/api/acts/{id}"]
    API --> RDF["View / Download RDF<br/>/api/acts/{id}/rdf"]
    API --> REL["Relationships<br/>/api/normattiva/modifications"]
    API --> SPARQL["SPARQL Query<br/>/api/sparql"]
    API --> STATUS["System Status<br/>/api/health"]

    ACTS --> UI
    DETAILS --> UI
    RDF --> UI
    REL --> UI
    SPARQL --> UI
    STATUS --> UI

    UI --> USER
```

Short explanation:

```text
Gazzetta provides publication metadata.
Normattiva provides legal relationship data.
Both are converted into RDF Turtle files.
Apache Jena loads the files together in memory.
Spring Boot exposes the data to a simple dashboard.
```
