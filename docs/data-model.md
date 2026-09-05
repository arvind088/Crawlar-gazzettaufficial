# Data model

How this platform represents Italian legislation in RDF, and why.

Scope note: the ELI ontology extension itself is owned by the project
supervisors (URS §1.2). This document records how the platform *consumes* the
standard and what it derives; where a decision would amount to extending the
ontology, that is called out rather than made.

---

## 1. Vocabularies

| Vocabulary | Namespace | Used for |
|---|---|---|
| ELI Ontology 1.5 | `http://data.europa.eu/eli/ontology#` | Everything structural: the Work/Expression/Manifestation layering, dates, identifiers, relations between acts |
| Dublin Core Terms | `http://purl.org/dc/terms/` | `dcterms:source`, pointing at the record an act was harvested from |
| OWL | `http://www.w3.org/2002/07/owl#` | `owl:sameAs`, linking a locally minted identifier to the source identifier |
| SKOS | `http://www.w3.org/2004/02/skos/core#` | Subject concepts and issuing authorities |
| schema.org | `http://schema.org/` | `schema:contentUrl` on a Manifestation |
| Local extension | `http://example.org/italian-legislation/ontology#` (`ilg:`) | Only where no standard term fits — see §6 |

## 2. FRBR layering

ELI's three levels are used as intended:

- **Work** — the act as an abstract thing. `Legge 22 maggio 2020, n. 35` is one
  Work regardless of how many times it is amended.
- **Expression** — the act as it read at a point in time. Gazzetta Ufficiale
  publishes exactly one (the *testo storico*); Normattiva tracks the amended
  series (*testo vigente*).
- **Manifestation** — a rendering of an Expression. Currently HTML only.

```
Work ──eli:is_realized_by──▶ Expression ──eli:is_embodied_by──▶ Manifestation
     ◀────eli:realizes─────            ◀────eli:is_embodied_in──
```

The practical consequence is that **status and dates belong to the Expression,
not the Work**. A Work is never "in force"; a particular text is. This is why
the UI derives an act's status from its current Expression rather than storing
it on the act.

## 3. Identifiers

Every resource is an `http(s)` URI in ELI form:

```
https://{domain}/eli/id/{year}/{month}/{day}/{natural-id}/{type}
```

The `natural-id` is the Gazzetta *codice redazionale* (e.g. `20G00043`), and
`{type}` is `sg` for the Serie Generale. Expressions extend the path with
language and version segments:

```
.../20G00057/sg/ita/original
.../20G00057/sg/ita/vigente/2021-07-31
```

**No URNs.** Normattiva exposes `urn:nir:` identifiers; they are never adopted as
resource identifiers. Where one appears in source text it is treated as a
literal. `EliUriService` enforces this: it will not mint a non-`http(s)` value,
and a test asserts a `urn:nir:` string passes through unchanged.

**Two hosts, one act.** Data harvested from Gazzetta carries
`gazzettaufficiale.it` URIs. The platform can re-host these on its own domain
(`EliUriMigrationRunner`), adding:

```turtle
<https://our-domain/eli/id/2020/05/22/20G00057/sg>
    owl:sameAs <http://www.gazzettaufficiale.it/eli/id/2020/05/22/20G00057/sg> .
```

`owl:sameAs` is the right predicate here because these are genuinely the same
act, not merely related records. Provenance of the *harvest* is separate, and
carried by `dcterms:source`.

## 4. Relations between acts

| Predicate | Meaning |
|---|---|
| `eli:commences` | A converting Legge → the Decreto Legge it converts |
| `eli:commenced_by` | The inverse |
| `eli:is_about` | Act → subject concept |
| `eli:passed_by` | Act → issuing authority |
| `ilg:modifies` / `ilg:modifiedBy` | Amendment relations from Normattiva review |

`eli:commences` is the backbone relation for the "significant queries" the
project is judged on: an Italian decreto-legge lapses unless converted within
sixty days, so the conversion link is what tells a reader whether a decree
survived.

Both directions are stored explicitly rather than relying on inference. This is
a deliberate trade: it costs one extra triple per relation and removes any need
for a reasoner in the query path, which keeps every page a single `DESCRIBE`.

## 5. In-force status

Three requirements (FR-4.5, US-A1, US-A3) need in-force status to be queryable.
It is modelled with standard ELI predicates and the EU authority table:

```turtle
<expression>
    eli:first_date_entry_in_force "2021-07-31"^^xsd:date ;
    eli:date_no_longer_in_force   "2021-07-30"^^xsd:date ;
    eli:in_force <http://publications.europa.eu/resource/authority/eli-in-force/IN_FORCE> .
```

Values are `IN_FORCE`, `NOT_IN_FORCE`, `PARTIALLY_IN_FORCE` from the Publications
Office table — not locally invented terms, so the decision stays inside the
standard and does not pre-empt the supervisors' extension work.

**How it is derived.** Where Normattiva returns force dates, those are used
directly. Where only a version label is available, status is inferred from the
label: a `VIGENZA_yyyyMMdd_Vn` text is the one in force and its date becomes
`eli:first_date_entry_in_force`; an `ORIGINALE`/`ORIGINAL` text is superseded
**only if a current text exists for the same Work**. An act with a single
published text is in force, which matches the reality that most Gazzetta acts
have only a *testo storico*.

The derivation is written to its own file (`data/rdf/in_force.ttl`) rather than
merged into the harvested files, so it is auditable and reversible: delete the
file and the inference is gone.

## 6. The local namespace

`ilg:` exists only where no standard term applies:

- `ilg:modifies` / `ilg:modifiedBy` — pending a supervisor decision on whether
  ELI's amendment predicates should be used instead.
- `ilg:authorityLabel`, `ilg:reference`, `ilg:referenceGU` — raw strings scraped
  from Gazzetta pages, kept so nothing harvested is discarded.
- `ilg:sourcePath`, `ilg:fileMarker`, `ilg:loadedAt` — ingestion provenance,
  stored in a separate named graph and never mixed with legal data.

Everything under `ilg:` should be read as a candidate for replacement by a
standard term, not as a considered extension.

## 7. Known gaps

- **Coverage.** Gazzetta gives one Expression per act. Multi-version data comes
  from Normattiva, whose API currently refuses requests from the development
  network (HTTP 409). Until that is resolved, most Works have a single
  Expression, so the version-listing feature is demonstrated by the seed acts
  and one hand-verified sample rather than at scale.
- **Subject concepts** are a small local SKOS vocabulary. Aligning them to
  EuroVoc is the obvious next step and needs verified EuroVoc identifiers.
- **Manifestations** are HTML only; PDF URLs are harvested but not yet modelled
  as separate Manifestations.
