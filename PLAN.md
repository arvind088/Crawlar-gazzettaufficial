# Thesis Application Plan

## Objective

Build a small, reliable application that imports official Italian legislation data, validates it, maps it to ELI RDF, stores it in persistent Apache Jena TDB2, and exposes it through SPARQL-driven web pages.

The thesis demonstrates one correct end-to-end Linked Data flow. It does not attempt to reproduce the whole Normattiva or Gazzetta Ufficiale platform.

## Scope

### Included

- Import manually downloaded Normattiva OpenData Akoma Ntoso XML files.
- Validate every file before importing it.
- Model ELI Work, Expression, and Manifestation resources.
- Store accepted RDF in persistent Jena TDB2.
- Serve every legal result from SPARQL queries.
- Search legal acts and display all available versions.
- Navigate verified links between resources.
- Provide a read-only SPARQL interface.
- Preserve source, checksum, import time, and validation evidence.
- Supply validation queries with expected results.

### Excluded from the first release

- General web crawling.
- Automatic legal conclusions from unstructured text.
- Relationships inferred from link order or text proximity.
- AI-based legal interpretation.
- Complete coverage of Italian legislation.
- User accounts and RDF editing through the public UI.
- Production deployment before local verification is complete.

## Architecture

```text
Official Normattiva AKN XML
            |
            v
Secure XML parser and validator
            |
       accepted/rejected
            |
            v
Normalized legal-act record
            |
            v
ELI Work -> Expression -> Manifestation
            |
            v
Persistent Apache Jena TDB2
            |
            v
Read-only SPARQL services
            |
            v
Legal Acts | Act Details | SPARQL
```

## Validation Rules

Before a file can modify TDB2, check:

- The file is readable and within the configured size limit.
- XML is well formed, with DTD and external entities disabled.
- The root uses the Akoma Ntoso 3.0 namespace.
- Exactly one supported legal act is present.
- Identifier, type, title, document date, publication date, language, and version are present and valid.
- RDF subjects use HTTP or HTTPS ELI identifiers.
- `urn:nir:` values are retained only as source aliases or literals.
- Every Expression realizes one Work.
- Every Manifestation embodies one Expression.
- Original and later versions have distinct identifiers.
- Reimporting the same checksum is idempotent.
- Conflicting content for an existing version is rejected for review.
- A legal relation has explicit source, target, predicate, and evidence.

Invalid inputs produce a report and do not change the trusted graph.

## Data Model

- **Work:** the abstract legal act and stable metadata.
- **Expression:** one original or later version of the Work.
- **Manifestation:** an XML, HTML, or other representation of an Expression.

```text
https://our-domain/eli/id/2026/01/07/26G00010
https://our-domain/eli/id/2026/01/07/26G00010/ita/original
https://our-domain/eli/id/2026/01/07/26G00010/ita/original/xml
```

## Minimal UI

### Legal Acts

- Search by title or local identifier.
- Show type, publication date, and version count.
- Open acts using application ELI URLs.

### Act Details

- Show Work metadata.
- List Expressions in date order.
- List each Expression's Manifestations.
- Show verified relations separately from structural ELI links.
- Make internal resources navigable.
- Link to provenance and validation evidence.

### SPARQL

- Offer selected example queries.
- Accept read-only `SELECT`, `ASK`, `CONSTRUCT`, and `DESCRIBE`.
- Reject SPARQL Update and remote `SERVICE` execution.
- Apply query timeout and result limits.

## Milestones

### 0. Clean baseline

- Free sufficient disk space for Maven and TDB2.
- Run the complete test suite and record the result.
- Keep unrelated crawler work outside the new import flow.

Acceptance: a clean checkout builds and tests successfully.

### 1. Simplify ingestion

- Make official AKN XML the primary thesis input.
- Remove or isolate legacy HTML scraping and positional relation inference.
- Separate update discovery from legal-data transformation.

Acceptance: no production path creates a final relation from guessed link order.

### 2. Validated AKN import

- Import the two available real sample files.
- Produce a validation preview before committing.
- Retain accepted sources by checksum.
- Reject invalid and conflicting files transactionally.

Acceptance: valid files import, rejected files add no triples, and duplicates are harmless.

### 3. Complete ELI mapping

- Mint application-owned Work, Expression, and Manifestation URIs.
- Link to official source identifiers as provenance.
- Ensure no URN is an RDF subject.
- Configure a real base URI per environment.

Acceptance: SPARQL integrity checks return zero invalid resources.

### 4. Demonstrate real versioning

- Import at least one official multi-version Normattiva act.
- Display the original and later Expressions.
- Display the Manifestations belonging to each Expression.

Acceptance: one real Work has at least two queryable Expressions generated from official data.

### 5. Finish the minimal UI

- Keep Legal Acts, Act Details, and SPARQL as the primary views.
- Remove internal pipeline controls from the public experience.
- Handle loading, empty, error, and populated states.
- Resolve every internal resource link inside the application.

Acceptance: the complete navigation flow works without blank or unstable screens.

### 6. Validation evidence

- Add golden SPARQL queries and expected results.
- Validate identifiers, metadata, hierarchy, versions, and reviewed relations.
- Execute these queries during Maven tests.
- Maintain a short manual UI checklist.

Acceptance: automated and manual checks agree on the reviewed sample dataset.

### 7. Controlled updates

- Use `/api/v1/ricerca/aggiornati` only to discover changed Normattiva acts.
- Persist a last-successful watermark.
- Advance it only after successful validation and storage.
- Use an overlap window and idempotent imports.
- Support saved API responses or exported AKN files as a reproducible fallback.

Acceptance: a failed run loses no date range and rerunning a range creates no duplicates.

### 8. Deployment

- Deploy only after local acceptance passes.
- Attach persistent storage for TDB2, sources, watermarks, and run logs.
- Configure the real public ELI base URI.
- Back up the repository and retained sources.
- Test restart and redeployment persistence.

Acceptance: imported data and ELI URLs remain available after redeployment.

## Final Demonstration

1. Inspect an official AKN file and show its validation result.
2. Import the accepted file and search for it in the UI.
3. Open its Work through an ELI URL.
4. Navigate its Expressions and Manifestations.
5. Run a SPARQL query returning the same facts.
6. Reimport it and show that no duplicate data appears.
7. Restart the application and show that TDB2 retained the data.

## Definition of Done

- Real official files, not only hand-written RDF, are imported.
- TDB2 is the runtime source of truth.
- The UI contains no hard-coded legal results.
- At least one real multi-version act is demonstrated.
- Published resources use application-owned ELI HTTP identifiers.
- No URN is an RDF subject.
- Only evidence-supported relations enter the trusted graph.
- Validation queries have expected results and pass automatically.
- Documentation explains the model, validation, update strategy, limitations, and deployment.

