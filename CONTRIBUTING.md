# Contributing

## Workflow

1. Start from `develop`: `git checkout develop && git pull`.
2. Make a branch: `feature/…`, `fix/…` or `refactor/…`.
3. Make the change with its tests, then run `mvn -B test` in `jena-git-project`.
4. Push the branch and open a pull request into `develop`. CI (`.github/workflows/ci.yml`) must pass.
5. `master` is updated from `develop` for releases.

Commit messages: one short line saying what changed and why, e.g.
`Gazzetta check: ignore "(Raccolta YYYY)" in act numbers`.

## Code

- Java 17, standard Maven layout, packages under `it.legislation` (see [ARCHITECTURE.md](ARCHITECTURE.md)).
- Every rule that reads Normattiva or Gazzetta data has a test with a trimmed **real** file in
  `src/test/resources/`. Add one when you add or change a rule.
- Tests must not use the network or leave files behind (use `@TempDir` or the in-memory store).
- Ingestion only **adds** to the store. A result that can change is written as a dated assessment
  (`AssessmentHistory`), never by deleting earlier statements.
- Read XML with the secure parser settings already used (`disallow-doctype-decl`, no external entities).

## Data

| In Git | Not in Git |
|---|---|
| `data/rdf/*.ttl`, `data/clean/*.tsv` | Raw Normattiva XML and ZIPs (`data/raw/normattiva/corpus`, `updates`) |
| `data/raw/normattiva/manifest.tsv` (checksums) | Cached Gazzetta pages (`data/raw/gazzetta/*.html`) |
| `data/registry/` (watermarks, run log) | The TDB2 store (`data/tdb2`) |
| `data/eval/reference_set.tsv` and results | Excel working copies (`data/eval/*.xlsx`) |

After a daily update, commit `manifest.tsv`, `data/registry/` and any changed `data/rdf` and
`data/clean` files together, in one commit.

## Example queries

SPARQL examples live in `jena-git-project/queries/*.rq`, one query per file, starting with a
`#` comment that says what it answers. They appear automatically on the `/query` page.
