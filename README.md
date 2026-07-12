# Enriched Italian Legislative Knowledge Graph

Lightweight demo application for exploring Italian legislative data as a knowledge graph.

## Problem Statement

Italian legislative information is available across different public sources. Gazzetta Ufficiale provides official publication metadata, while Normattiva provides legal relationships such as amendments and modifications. A researcher normally has to check both sources separately.

This project combines those sources into one simple searchable dashboard.

## What This Project Does

- Crawls or stores sample legal act data from Gazzetta Ufficiale.
- Stores modification relationship data from Normattiva.
- Converts the data into RDF triples.
- Loads the RDF into an Apache Jena model.
- Provides a Spring Boot API for search, relationships, status, and SPARQL.
- Shows the data in a simple web dashboard.

## Demo

Live demo:

https://italian-legislative-kg-demo.onrender.com/

## Main Flow

```text
Gazzetta data + Normattiva data
        |
        v
Clean structured records
        |
        v
RDF triples
        |
        v
Apache Jena knowledge graph
        |
        v
Spring Boot API
        |
        v
Simple web dashboard
```

## Dashboard Features

- Search legal acts.
- View publication metadata.
- View modification relationships.
- Check loaded RDF source files.
- Run simple SPARQL queries.

## Example SPARQL Query

```sparql
PREFIX ilg: <http://example.org/italian-legislation/ontology#>

SELECT ?source ?relation ?target
WHERE {
  ?source ?relation ?target .
  FILTER(?relation IN (ilg:modifies, ilg:modifiedBy))
}
LIMIT 20
```

## Run Locally

Requirements:

- Java 17
- Maven

Commands:

```bash
cd jena-git-project
mvn spring-boot:run
```

Open:

```text
http://localhost:8080/
```

## Project Structure

```text
jena-git-project/
  src/main/java/       Spring Boot backend and crawler code
  src/main/resources/  Static dashboard files
  data/rdf/            RDF files loaded into Apache Jena
  data/clean/          Cleaned intermediate data
docs/diagrams/         Project flow and architecture diagrams
render.yaml            Render deployment configuration
```

## Current Scope

This is a lightweight academic prototype. It focuses on showing the full idea clearly: data collection, RDF conversion, knowledge graph loading, API access, and a simple end-user dashboard.
