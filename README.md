# Italian Legislation Linked Data

A lightweight thesis application for validating official Italian legislation data, representing it with ELI RDF, storing it in Apache Jena TDB2, and exploring it through SPARQL-backed web pages.

## Thesis Flow

```text
Official Normattiva AKN XML
        -> validation
        -> ELI Work / Expression / Manifestation RDF
        -> persistent Jena TDB2
        -> read-only SPARQL
        -> navigable web interface
```

The first thesis release is intentionally narrow. It prioritizes trusted data, clear provenance, real version navigation, and repeatable validation over broad crawling or automatic legal interpretation.

## Current Capabilities

- Persistent, transaction-backed Jena TDB2 storage.
- SPARQL-based legal-act search and resource pages.
- Read-only SPARQL protocol endpoint.
- ELI resource resolution and content negotiation.
- Work, Expression, and Manifestation representation.
- Clickable navigation for RDF resource links.
- Scheduled ingestion components, watermarks, and run logs.
- Automated query and data-model tests.

## Current Priority

Complete a defensible import path for real Normattiva OpenData AKN files:

1. Inspect and validate the XML securely.
2. Preview validation errors and warnings.
3. Map accepted data to application-owned ELI URIs.
4. Commit RDF transactionally to TDB2.
5. Demonstrate a real act with multiple Expressions.
6. Verify the result with golden SPARQL queries and the UI.

Automatic relation extraction from ambiguous text is outside the trusted graph. A possible relation remains a review candidate until its source, target, predicate, and evidence are explicit.

## Run Locally

Requirements:

- Java 17
- Maven 3.9 or later

```powershell
cd jena-git-project
mvn -B test
mvn spring-boot:run
```

Use the single port configured by the application. Check `/api/health` before starting another process.

## Repository Guide

```text
CONTEXT.md                 Stable project decisions and professor feedback
PLAN.md                    Milestones and acceptance criteria
SKILL.md                   Rules for future implementation sessions
docs/                      Architecture, data model, rationale, and diagrams
docs/screenshots/          Historical UI verification images
docs/archive/              Superseded drafts retained for reference
jena-git-project/          The Spring Boot application
  data/                    Reviewed source, intermediate, and RDF data
  queries/                 Demonstration SPARQL queries
  scripts/                 Local validation helpers
  src/main/                Application code and static UI
  src/test/                Automated tests
render.yaml                Deployment definition
```

## Documentation

- [Implementation plan](PLAN.md)
- [Project context](CONTEXT.md)
- [Data model](docs/data-model.md)
- [Linked Data design](docs/linked-data-design-notes.md)
- [Update routine rationale](docs/update-routine-rationale.md)
- [SPARQL validation queries](jena-git-project/VALIDATION_QUERIES.md)
- [Manual UI tests](jena-git-project/MANUAL_UI_TESTS.md)

## Known Limits

- Real multi-version coverage is not yet demonstrated at useful scale.
- The public ELI base URI is still a placeholder until deployment is finalized.
- Render persistence is not configured; TDB2 data would not survive a redeploy on ephemeral storage.
- Gazzetta RSS alone cannot guarantee recovery after a long outage.
- Normattiva update discovery does not itself prove legal relationships.

These limits are part of the plan, not hidden assumptions.
