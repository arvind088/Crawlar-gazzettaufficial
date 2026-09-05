# SPARQL validation queries

Hand-verifiable checks on the dataset, covering FR-5.1, FR-5.2, FR-5.3, US-B2,
US-D1 and manual test case TC-07.

Run them from the SPARQL tab in the dashboard, or against the public endpoint:

```bash
curl -H 'Accept: application/sparql-results+json' \
  --data-urlencode 'query=SELECT (COUNT(*) AS ?triples) WHERE { ?s ?p ?o }' \
  http://localhost:8082/sparql
```

---

## How expected results are recorded

An earlier version of this document recorded absolute figures — "triples: 1659",
"loaded files: 4". Every one of them went stale the moment data was added, and a
stale expected result is worse than none: it invites a reviewer to conclude the
platform is broken when only the document is.

So expectations here are written as **invariants that survive ingestion**:

- properties that must hold no matter how much data arrives ("every conversion
  runs Legge → Decreto Legge");
- exact counts only over the **seed dataset**, which is fixed by
  `CONTEXT.md §3.1` and does not grow.

Whole-dataset totals are not written down. Read them from `GET /api/health`,
which reports the live figure.

**These are enforced, not just documented.** Every expectation below is asserted
in `src/test/java/it/legislation/web/ValidationQueriesTest.java` and runs on every
`mvn test`. If the data model changes underneath them, the build fails — the
failure mode this document previously could not catch.

---

## Dataset under test

| File | Contents |
|---|---|
| `data/rdf/seed_acts.ttl` | The four acts required by CONTEXT.md §3.1, with subject concepts and issuing authorities |
| `data/rdf/gazzetta_metadata_delta.ttl` | Acts harvested from Gazzetta Ufficiale |
| `data/rdf/normattiva_modifications.ttl` | Reviewed act-to-act relations |
| `data/rdf/normattiva_modifications_auto.ttl` | Relations from the automated route |
| `data/rdf/normattiva_multiversion_sample.ttl` | Multi-version sample (Codice dell'amministrazione digitale) |
| `data/rdf/in_force.ttl` | Derived in-force status per Expression |

The seed acts, which the counted queries below refer to:

| Local ID | Act | Published | Type |
|---|---|---|---|
| `20G00034` | Decreto-legge 17 marzo 2020, n. 18 | 2020-03-17 | Decreto Legge |
| `20G00043` | Legge 24 aprile 2020, n. 27 | 2020-04-29 | Legge |
| `20G00035` | Decreto-legge 25 marzo 2020, n. 19 | 2020-03-25 | Decreto Legge |
| `20G00057` | Legge 22 maggio 2020, n. 35 | 2020-05-23 | Legge |

Two conversion pairs: 27/2020 converts 18/2020, and 35/2020 converts 19/2020.
Legge 35/2020 carries two Expressions — *testo originale* and *testo vigente al
2021-07-31* — which is the multi-version case FR-5.2 requires.

---

## 1. All acts

```sparql
PREFIX eli:  <http://data.europa.eu/eli/ontology#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?act ?label ?date WHERE {
  ?act a eli:LegalResource ; rdfs:label ?label ; eli:date_publication ?date .
} ORDER BY ?date
```

**Expected** — over the seed data alone, exactly 4 rows, earliest first
(`20G00034`). Over the full dataset the count is larger; every row must have both
a label and a date, which query 6 checks.

## 2. Acts by year

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>

SELECT ?act WHERE {
  ?act a eli:LegalResource ; eli:date_publication ?date .
  FILTER(YEAR(?date) = 2020)
}
```

**Expected** — all 4 seed acts for 2020; 0 rows for 1999. A year filter that
returns rows outside its range indicates a date-typing problem.

## 3. Acts by type

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>

SELECT ?act WHERE {
  ?act eli:type_document
    <http://www.gazzettaufficiale.it/eli/tables/resource-type#DECRETOLEGGE> .
}
```

**Expected** — 2 seed acts as `DECRETOLEGGE`, 2 as `LEGGE`.

## 4. Latest acts

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>

SELECT ?act ?date WHERE {
  ?act a eli:LegalResource ; eli:date_publication ?date .
} ORDER BY DESC(?date) LIMIT 2
```

**Expected** — among the seed acts, Legge 35/2020 (2020-05-23) first, then Legge
27/2020 (2020-04-29).

## 5. Conversion-link validation — FR-5.3

The important one. An Italian decreto-legge lapses unless converted within sixty
days, so a conversion edge pointing the wrong way asserts something false about
whether a decree survived.

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>

SELECT ?legge ?decreto WHERE {
  ?legge eli:commences ?decreto .
  FILTER NOT EXISTS {
    ?legge   eli:type_document
      <http://www.gazzettaufficiale.it/eli/tables/resource-type#LEGGE> .
    ?decreto eli:type_document
      <http://www.gazzettaufficiale.it/eli/tables/resource-type#DECRETOLEGGE> .
  }
}
```

**Expected — zero rows, always.** Any row is a modelling error. This is an
invariant, not a count: it must hold however much data is ingested.

Listing the conversions themselves over the seed data gives exactly 2 pairs:
`20G00043 → 20G00034` and `20G00057 → 20G00035`.

## 6. ELI-level validation

```sparql
PREFIX eli:  <http://data.europa.eu/eli/ontology#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?act WHERE {
  ?act a eli:LegalResource .
  FILTER (NOT EXISTS { ?act rdfs:label ?label } ||
          NOT EXISTS { ?act eli:date_publication ?date })
}
```

**Expected** — zero rows over the seed data.

Over the full dataset this query is a **report, not a pass/fail**: Gazzetta
metadata is genuinely sparse, and acts published without a usable title do exist.
Rows here are data-quality findings to triage, which is what FR-5.1 means by
"flag resources missing them, matching known sparse-metadata reality".

## 7. Multi-version acts — FR-5.2

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>

SELECT ?work (COUNT(?expression) AS ?versions) WHERE {
  ?work eli:is_realized_by ?expression .
} GROUP BY ?work HAVING (COUNT(?expression) > 1)
```

**Expected** — over the seed data, exactly 1 row: Legge 35/2020 with 2
Expressions. Over the full dataset, also the Codice dell'amministrazione digitale
sample (`005G0104`, 2 Expressions).

That the number is small is a **data-coverage limitation, not a defect**:
Gazzetta publishes only the first text of an act, and the Normattiva OpenData API
that supplies amended versions currently returns HTTP 409 from the development
network. See `docs/update-routine-rationale.md` §3.

## 8. In-force status — FR-4.5, US-A3

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>

SELECT ?expression WHERE {
  <http://www.gazzettaufficiale.it/eli/id/2020/05/22/20G00057/sg>
      eli:is_realized_by ?expression .
  ?expression eli:in_force
    <http://publications.europa.eu/resource/authority/eli-in-force/IN_FORCE> .
}
```

**Expected** — exactly 1 row, and it must be the `vigente` Expression, not the
original. A Work with several Expressions has exactly one current text.

## 9. Non-conversion relations — TC-03

TC-03 exists to prove that navigability is generic across predicates rather than
special-cased for conversions. It was previously impossible to execute, because
no such predicate existed in any dataset.

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>

SELECT ?act ?topic WHERE { ?act eli:is_about ?topic . }
```

**Expected** — at least one row; likewise for `eli:passed_by`. Clicking either on
a resource page must navigate exactly as `eli:commences` does.

---

## Machine access — US-B1

The endpoint accepts all four query forms. `DESCRIBE` in particular is required
by US-B1's acceptance criteria:

```bash
curl -H 'Accept: text/turtle' \
  --data-urlencode 'query=DESCRIBE <http://www.gazzettaufficiale.it/eli/id/2020/05/22/20G00057/sg>' \
  http://localhost:8082/sparql
```

**Expected** — Turtle describing Legge 35/2020, matching what
`/eli/id/2020/05/22/20G00057/sg` renders as HTML. Consistency between the two is
NFR-3.

An update (`INSERT`/`DELETE`) must be rejected with 400: the endpoint parses
queries only and has no write path.
