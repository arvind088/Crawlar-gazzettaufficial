# Project Context

## Repository

- GitHub: `https://github.com/arvind088/Crawlar-gazzettaufficial`
- Authoritative local repository: `C:\Users\HP\git\Crawlar-gazzettaufficial`
- Active branch: `tdb2-linked-data`
- Existing application module: `jena-git-project`
- Stack: Java 17, Spring Boot, Apache Jena, TDB2, HTML, CSS, and JavaScript.

Always work in the authoritative local repository. Do not use a Codex worktree unless the user explicitly reverses this decision.

## Goal

Create a simple, defensible Linked Data application for Italian legislation. It imports official data, validates it, maps it to ELI RDF, stores it in persistent Jena TDB2, and presents it through SPARQL-backed pages.

Correctness, traceability, and a clear thesis demonstration matter more than feature count.

## Permanent Principles

- Keep the application simple, minimal, and lightweight.
- Prefer one complete workflow over multiple weak pipelines.
- TDB2 is the source of truth for queryable RDF.
- Every displayed legal fact comes from TDB2 through SPARQL.
- Every published resource has an HTTP ELI identifier.
- Never use a URN as the RDF subject of a published resource.
- Never create final legal relationships from guesses.
- Preserve provenance and validation evidence.
- Prefer official data and keep the public UI focused on legal acts.

## Professor Requirements

1. Use persistent Jena TDB2 rather than an in-memory repository.
2. Serve frontend results from SPARQL queries to TDB2.
3. React to repository updates and navigate related resources.
4. Explain the Linked Data design rationale.
5. Include ELI Expression and Manifestation levels.
6. List all versions of a multi-version law.
7. Use ELI-style URLs on the application domain.
8. Do not use URNs as triple-store resource identifiers.
9. Prevent Gazzetta RSS updates from being lost between runs.
10. Redesign the weak `NormattivaUpdateRunner` critically.
11. Prefer the official Normattiva OpenData API, especially `/api/v1/ricerca/aggiornati`.
12. Provide SPARQL validation queries and expected true results.
13. Document the implementation, data model, decisions, and limitations.
14. If scope is reduced, prioritize the UI and robust update routines.
15. Consider the professor's server if persistent hosting is unavailable.

## Current Assessment

### Implemented

- Production code uses disk-backed TDB2.
- Search and resource services use SPARQL.
- A read-only SPARQL endpoint exists.
- ELI resolution and linked navigation exist.
- Work, Expression, and Manifestation structures exist.
- Validation SPARQL queries and automated tests exist.
- Scheduler, watermark, run log, and source registry components exist.
- The official Normattiva update endpoint is represented in code.
- The OpenData update path writes no final relationship triples without later evidence.

### Partial

- Multi-version support is demonstrated mainly with curated samples.
- ELI routes exist, but the base URI remains a placeholder and tracked RDF still uses source-domain identifiers.
- RSS ingestion avoids duplicates for entries it sees but cannot guarantee recovery after a long outage.
- Normattiva API code exists without a proven successful real end-to-end run from the development environment.
- Documentation still includes legacy crawler behavior that should not represent the final thesis design.

### Missing or unresolved

- `render.yaml` has no persistent disk.
- A real application domain is not configured.
- Real AKN XML is not yet imported into the trusted TDB2 graph through a complete validated flow.
- A real official multi-version act is not yet demonstrated end to end.
- Obsolete HTML scraping and positional relation inference remain in `NormattivaUpdateRunner`.
- There is no complete policy for correcting a previously imported false assertion.
- The latest full test run had 109 passing tests and two TDB2 disk-allocation errors because C: had zero free space. There were no assertion failures, but the build was not successful.

## Authoritative Data Decision

The first release uses manually downloaded official Normattiva OpenData AKN files. Manual download is acceptable for the bounded demonstration because the technical contribution is validation, semantic mapping, persistence, queryability, and Linked Data navigation.

The update API is a controlled extension:

```text
/api/v1/ricerca/aggiornati
  -> discover changed acts
  -> obtain official data
  -> run the same validator
  -> import only accepted records
```

An update response is discovery evidence, not automatically evidence of a legal relation.

## Trust Levels

- **Trusted graph:** validated resources and evidence-supported relations used by the public UI.
- **Review candidates:** possible relationships or incomplete observations awaiting human review.
- **Rejected imports:** validation reports for inputs that must not modify the trusted graph.

## Required ELI Structure

```text
Work
  eli:is_realized_by
Expression
  eli:is_embodied_by
Manifestation
```

- A Work is the abstract act.
- An Expression is one legal version or temporal text.
- A Manifestation is an XML, HTML, or other rendering.
- Version dates and in-force status belong to Expressions.
- File format and source location belong to Manifestations.

## Scope Boundaries

Do not add these without an explicit scope change:

- Another crawler or public dashboard.
- AI-based relationship extraction.
- Automatic legal conclusions from free text.
- User accounts or SPARQL Update.
- Another database beside TDB2.
- Large infrastructure frameworks.
- Decorative or marketing-style UI.

## UI Decision

The public application has three main views:

1. Legal Acts
2. Act Details
3. SPARQL

Import validation may be an administrative workflow. Crawler status, evidence candidates, schedules, and internal files must not dominate the public interface.

## Deployment Decision

Deployment is the last milestone. When deploying:

- Use persistent storage for TDB2 and ingestion state.
- Set the real `legal.eli.base-uri`.
- Keep sources, checksums, watermarks, and run logs on durable storage.
- Verify survival across restart and redeployment.
- Discuss the professor's server if Render persistence is unsuitable.

## Git Rules

- Do not commit, push, merge, or rewrite history without explicit approval.
- Do not revert user changes.
- Inspect status before and after each milestone.
- Keep TDB2 files, temporary imports, and build output out of Git.
- Do not add personal thesis documents or downloads unless requested.

## Immediate Next Work

1. Free disk space and obtain a clean baseline test run.
2. Remove or isolate obsolete relation inference.
3. Implement secure AKN inspection and import.
4. Import the two available real sample files.
5. Map application-owned ELI Work, Expression, and Manifestation resources.
6. Add golden SPARQL validation tests.
7. Simplify the UI to the agreed views.
8. Demonstrate one real multi-version act.
9. Configure persistent deployment last.

