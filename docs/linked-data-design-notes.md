# Linked Data design notes

How the platform satisfies the Linked Data principles it is judged against, and
the decisions that were not obvious. Covers FR-2.2, FR-3.1, FR-3.2, FR-4.1 to
FR-4.6 and NFR-3, NFR-4, NFR-6.

---

## 1. The four principles, and where each is implemented

| Principle | Implementation |
|---|---|
| Use URIs as names for things | Every resource is an ELI-pattern `http(s)` URI. No URNs. |
| Use HTTP URIs so they can be looked up | `EliResolutionController` serves `/eli/id/{y}/{m}/{d}/{id}/{type}` |
| Provide useful information using the standards | HTML for browsers, Turtle for `Accept: text/turtle`, plus a SPARQL 1.1 endpoint |
| Include links to other URIs | Every IRI-valued object on a page is a link |

The second principle is the one the project previously failed. Data was
well-formed and queryable, but no act had an address on this platform: the whole
UI lived at `/`, so a law could be clicked to but never linked to, bookmarked,
cited, or fetched by a machine.

## 2. Decision: mint on our own domain, keep `owl:sameAs` to the source

Harvested data carries `gazzettaufficiale.it` URIs. Publishing those unchanged
would mean every identifier in "our" graph dereferences to someone else's
service — the platform would be a mirror, not a node.

So identifiers are re-hosted:

```turtle
<https://our-domain/eli/id/2020/05/22/20G00057/sg>
    owl:sameAs     <http://www.gazzettaufficiale.it/eli/id/2020/05/22/20G00057/sg> ;
    dcterms:source <http://www.gazzettaufficiale.it/eli/id/2020/05/22/20G00057/sg> .
```

Two properties, deliberately: `owl:sameAs` states an identity claim — these
denote the same act — while `dcterms:source` records where the description came
from. Collapsing them would lose the distinction between *what a thing is* and
*where we learned about it*.

**Minting is reversible and off by default.** It is enabled by configuration
(`legal.eli.base-uri`), and `EliUriMigrationRunner` converts data already
collected, so the decision does not require re-crawling and can be undone.

### The resolver does not depend on the migration

`EliUriService.candidateUris()` returns both the local and the source identifier
for a path, and the resolver tries each. So `/eli/id/2005/05/16/005G0104/sg`
resolves whether the stored subject is ours or Gazzetta's.

This was worth the small extra complexity: it decouples "acts have addresses"
from "acts have *our* addresses", so the two can be adopted separately, and the
resolution route was demonstrable before any data was rewritten.

## 3. Decision: generic link rendering, enforced structurally

The requirement (FR-4.3) is that *any* triple with a URI object renders as a
link, for any predicate, including predicates that do not exist yet. The
temptation is a `switch` over known predicates; that satisfies a demo and fails
the requirement.

The rule is one line, in both layers:

```java
if (!value.iri()) { return literal(value); }   // otherwise: always a link
```

No predicate is inspected to decide whether something is navigable. The
predicate maps in `EliHtmlRenderer` and the priority list in
`LegalActQueryService` affect only *wording and ordering* — an unknown predicate
still renders, still links, and gets a readable label derived from its IRI.

**The test for this is architectural, not unit.** If adding a predicate to the
data requires a code change to make it clickable, the implementation is wrong.
The migration to our own domain exercised exactly this: `owl:sameAs` appeared in
the graph for the first time and rendered as a working link with no code change.

## 4. Decision: content negotiation, simplified

The gold standard for Linked Data resolution is the 303-redirect pattern,
separating the identifier for the act from the URL of a document describing it.
This platform serves both from one URI and negotiates on `Accept`:

```
GET /eli/id/2005/05/16/005G0104/sg
    Accept: text/html    → an HTML page
    Accept: text/turtle  → the same description as RDF
    ?format=ttl          → Turtle from a browser address bar
```

The simplification is recorded rather than hidden. It costs the formal
distinction between a resource and its description; it gains a scheme that is
demonstrable in a browser and does not require redirect handling from clients.
Whether 303 is required for this phase is an open question for the supervisors
(URS §8.8).

## 5. Decision: pages are queries, never caches

Every resource page runs `DESCRIBE` plus a `CONSTRUCT` for inbound statements,
at request time. Nothing is pre-rendered or memoised.

For a dataset this size that is simply correct, and it makes NFR-3 —
"any displayed fact must be traceable to the query that produced it" — true by
construction rather than by discipline. The page footer says so, and the Turtle
view returns the same model the page rendered.

If the corpus grows to the point where this hurts, the answer is a cache with an
explicit invalidation story, not pre-baked pages.

## 6. Decision: a real SPARQL endpoint for machine clients

FR-3.1 promises a public read-only SPARQL endpoint. What existed was
`POST /api/sparql` taking `{"query": …}` and returning bespoke JSON: fine for
the dashboard, unusable by any standard client, and restricted to `SELECT` —
which failed US-B1's requirement to accept `SELECT` *and* `DESCRIBE`.

`/sparql` now follows the SPARQL 1.1 Protocol: `GET ?query=`, form POST, and
`application/sparql-query` POST; all four query forms; SPARQL Results
JSON/XML/CSV for `SELECT` and `ASK`, and Turtle / RDF-XML / N-Triples / JSON-LD
for `CONSTRUCT` and `DESCRIBE`.

**Read-only by construction rather than by filter.** The endpoint parses with
`QueryFactory`, which accepts queries only — a SPARQL Update fails to parse and
is rejected before reaching the store. There is no update path to secure because
there is no update path.

`/api/sparql` is retained unchanged as the dashboard's own interface.

## 7. Decision: the store is the record

Recorded in full in `update-routine-rationale.md` §5. Its relevance here is that
persistence is what makes an identifier trustworthy. If dereferencing an act
depends on a Turtle file still being present on disk, the URI is a convention
rather than a commitment. TDB2 holding the data is what lets the platform
promise that an address keeps working.

## 8. What a reviewer can check in two minutes

```
/eli/id/2020/05/22/20G00057/sg              a Legge with two Expressions
/eli/id/2020/05/22/20G00057/sg?format=ttl   the same description as RDF
/eli/id/2020/03/25/20G00035/sg              the Decreto Legge it converts
/sparql                                     endpoint description
/sparql?query=DESCRIBE%20%3Chttp%3A%2F%2Fwww.gazzettaufficiale.it%2Feli%2Fid%2F2020%2F05%2F22%2F20G00057%2Fsg%3E
```

Clicking `eli:commences` on the Legge reaches the Decreto Legge; clicking
`eli:commenced_by` on the Decreto Legge comes back. Clicking `eli:is_about`
reaches a subject concept by the identical mechanism — which is the point of
TC-03, and the shortest demonstration that navigability is generic.
