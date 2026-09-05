# Update routine — design and rationale

Why ingestion is built the way it is. Written as the decisions were made, per
NFR-5, and covering FR-1.1 to FR-1.5.

---

## 1. The problem

Two sources, with different shapes and different failure modes.

| | Gazzetta Ufficiale | Normattiva |
|---|---|---|
| Coverage | Primary and secondary legislation | Primary (numbered) legislation |
| Versions | First published text only (*testo storico*) | Amended series over time (*testo vigente*) |
| Update signal | RSS feed of daily publications | `POST /api/v1/ricerca/aggiornati` |
| Stability | HTML layout, changes occasionally | Documented JSON API |

Neither offers "everything that changed since token X". Both require the client
to define the window, which is where updates get lost.

## 2. Decision: scheduled processes, not manual scripts

Prior review called manual triggering a correctness bug rather than a style
preference, and it is right: a day missed between two manual runs is
permanently lost, because neither source lets you ask for the past.

Both jobs run under Spring's scheduler: Gazzetta at 06:15 and Normattiva at
06:45, Europe/Rome, configured in `application.properties` rather than in code.

**This is not sufficient on its own.** A schedule that runs on a host which
sleeps when idle, and whose filesystem resets on deploy, guarantees nothing. The
scheduler is necessary; durable hosting is the other half, and it is a
deployment decision rather than a code one (see §7).

## 3. Decision: Normattiva via the OpenData API, never scraping

An earlier version scraped the Normattiva homepage and inferred relations from
nearby links. That was abandoned because:

- the homepage is not a stable data contract;
- a layout change breaks the parser silently, producing zero results rather than
  an error;
- proximity between two links is not evidence that one act amends another.

The routine now uses two documented endpoints:

```
POST /api/v1/ricerca/aggiornati     { dataInizioAggiornamento, dataFineAggiornamento }
POST /api/v1/atto/dettaglio-atto    { dataGU, codiceRedazionale }
```

**Current status:** the API returns HTTP 409 — "blocked by Poligrafico
protection systems" — from the development network. This is an access problem,
not a code problem, and is why an import path from saved API responses exists
alongside the live path. The routine is complete; it needs a permitted network.

## 4. Decision: a persisted watermark, not a rolling window

The first implementation asked for `now − lookbackDays` on every run. That
window is fine while the job runs daily and silently lossy the moment it does
not: if the process is down for longer than the lookback, the updates published
in the gap are never requested again.

`IngestionWatermark` stores, per source, the end of the last **successful** run.
Each run then asks for `[last successful end − overlap, now]`.

Three properties matter:

1. **Failure does not advance the cursor.** A failed run records the attempt but
   leaves the watermark where it was, so the next run re-requests the same
   period. Combined with additive ingestion, re-requesting costs nothing.
2. **An overlap is subtracted** (one hour by default). Without it, an act
   published in the same second a run ended could fall between two windows.
3. **A maximum span caps the request** (90 days). A first run, or one after a
   long outage, does not attempt to fetch years of history in a single call.

Re-requesting the same period twice is harmless *because* ingestion is additive
— the two decisions depend on each other.

## 5. Decision: ingestion is additive, and the store is the record

An earlier design cleared every named graph and re-parsed the Turtle files on
each read. It had three faults: the store was a cache of files rather than the
record; the update strategy was destructive; and each read re-parsed the whole
corpus inside a write transaction.

Now the Turtle files are a **bootstrap** source. A file is read once, into TDB2,
and recorded in a provenance graph. After that the store owns the data; reads
take a read transaction and touch no files.

Additivity falls out of RDF itself: a graph is a set, so re-reading a file adds
only statements not already present and can never remove one. The corollary is
worth stating plainly — **a statement removed from a source file is not removed
from the store.** That is the correct reading of FR-1.4, and superseding an
outdated version is a deliberate, separate operation.

`Tdb2DatasetServiceTest` proves the property the requirement actually cares
about: ingest, shut down, **delete the source file**, restart, and the act is
still queryable.

## 6. Decision: an append-only run log

Run outcomes used to live in `volatile` fields, so the entire history vanished on
restart and the status view could only report the current process's activity.
FR-1.5 asks for auditability, which means the record must outlive the process.

`IngestionRunLog` appends one row per run to `data/registry/ingestion_runs.tsv`:
run id, source, trigger, start, finish, state, items fetched / transformed /
loaded / failed, window, message. A run that dropped some items is recorded as
`COMPLETED_WITH_ERRORS`, distinct from both `COMPLETED` and `FAILED`, which is
what makes partial failure visible instead of averaging away.

Append-only is the point: an audit log that rewrites itself is not an audit log.

## 7. Open: durable scheduling

The schedule is correct and the persistence is now correct, but the deployment
undermines both. On the current free-tier host the instance sleeps when idle —
so a 06:15 job may simply not fire — and the container filesystem resets on
deploy, discarding the store.

Two options, neither requiring application changes:

- a host with a persistent disk mounted at the store path; or
- invert the arrangement: run ingestion in scheduled CI, commit the outputs, and
  deploy from them. This is free, and the commit history becomes the evidence
  for the no-gap claim — an auditable per-day record rather than an assertion.

The second is implemented in `.github/workflows/scheduled-ingestion.yml`.

## 8. What is deliberately *not* automated

Relation extraction stops before RDF. The evidence chain — find changed acts,
fetch details, scan for wording that names another act, propose candidates —
produces `needs_review` rows and writes no triples.

This is a considered limit, not an unfinished feature. A false `eli:commences`
edge is worse than a missing one: it asserts that a decree survived when it may
have lapsed. Generating relations from text proximity was exactly the weakness
that made the previous scraping approach indefensible, and the discipline should
survive its removal.
