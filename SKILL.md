---
name: italian-legislation-thesis
description: Build and verify the minimal Italian legislation Linked Data thesis application using validated Normattiva AKN data, ELI RDF, persistent Jena TDB2, SPARQL, and a navigable UI.
---

# Italian Legislation Thesis Skill

## Start Here

Read `CONTEXT.md` before making decisions. Follow `PLAN.md` for sequence, scope, acceptance criteria, and definition of done.

## Repository Rules

- Work only in `C:\Users\HP\git\Crawlar-gazzettaufficial` unless explicitly redirected.
- Treat `jena-git-project` as the current application module.
- Inspect the branch and working tree before editing.
- Preserve unrelated and untracked user files.
- Never commit, push, merge, or rewrite history without an explicit request.
- Do not use a Codex worktree unless the user explicitly changes that decision.

## Product Rules

- Keep the app small, minimal, and easy to debug.
- Complete one end-to-end path before adding features.
- Do not create another app or duplicate dashboard unless requested.
- Keep the public UI focused on Legal Acts, Act Details, and SPARQL.
- Use one configured port and do not start duplicate local servers.

## Required Flow

```text
Official AKN XML
  -> secure validation
  -> normalized record
  -> ELI RDF
  -> persistent TDB2
  -> SPARQL
  -> navigable HTML UI
```

Every implementation task must strengthen this flow or a test that proves it.

## Data Safety

- Disable XML DTD and external entity processing.
- Validate before beginning a TDB2 write transaction.
- Use a transaction for every repository mutation.
- Abort on transformation or validation failure.
- Keep rejected data outside the trusted graph.
- Retain source checksum and provenance.
- Make imports idempotent.
- Treat conflicts as review cases, not silent replacements.
- Never clear the complete TDB2 dataset during normal startup or import.
- Do not make bootstrap Turtle files the runtime source of truth after ingestion.

## Semantic Rules

- Model Work, Expression, and Manifestation explicitly.
- Mint HTTP or HTTPS ELI identifiers on the configured application domain.
- Never use `urn:nir:` as an RDF subject.
- Keep source URNs only as aliases or literals where useful.
- Put version-specific dates and status on Expressions.
- Put format and source location on Manifestations.
- Prefer standard ELI predicates over local predicates.
- Document each required local ontology term.

## Relationship Rules

- A recently changed act is not automatically a legal relation.
- Text mentioning another act is not automatically verified evidence.
- Never infer source and target from link order, DOM proximity, or regex alone.
- Store a final relation only when source, target, predicate, and evidence are explicit.
- Otherwise create a review candidate outside the trusted graph.
- Keep candidate review details out of the public Legal Acts view.

## Query Rules

- Obtain all user-facing legal results from TDB2 through SPARQL.
- Parameterize user-supplied query values.
- Keep the public endpoint read-only.
- Reject SPARQL Update and remote `SERVICE` execution.
- Apply timeouts, input-size limits, and row limits.

## UI Rules

- Build a quiet utility interface, not a marketing page.
- Show human labels before technical URIs.
- Make internal resources clickable.
- Group versions under their Work.
- Separate verified legal relations from structural ELI links.
- Handle loading, empty, invalid, unavailable, and error states without blanking the page.
- Treat optional missing metadata as unavailable, not as a JavaScript error.
- Never render `null` or `undefined` as user-facing text.

## Testing Rules

- Run focused tests after each small change.
- Run `mvn -B test` before declaring a milestone complete.
- Do not report success when tests end with infrastructure errors.
- Use in-memory datasets when persistence is not under test.
- Use disk-backed TDB2 tests for restart and persistence behavior.
- Avoid creating a new TDB2 database for every validation query.
- Add golden SPARQL tests with explicit expected results.
- Test invalid XML, missing metadata, URN rejection, duplicate import, conflicts, version grouping, and rollback.

## Documentation Rules

- Record what was implemented, why, its limitations, and how it was verified.
- Clearly distinguish production behavior from sample data.
- Never claim full coverage from curated examples.
- Never claim continuous ingestion before observing durable scheduling across failures and restarts.
- Explain professor-facing decisions in plain language followed by technical evidence.

## Completion Checklist

1. Check `git diff` and confirm only intended files changed.
2. Run focused tests.
3. Run the full suite when the environment permits.
4. Verify the UI through the single configured localhost URL.
5. Query TDB2 to confirm expected resources.
6. Confirm no URN is an RDF subject.
7. Test restart persistence when relevant.
8. Report honestly any tests not run or environmental failures.

