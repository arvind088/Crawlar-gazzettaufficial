# SPARQL Validation Queries

These queries validate the current lightweight TDB2-backed demo dataset.

Run them from the browser SPARQL tab or through:

```bash
curl -X POST http://localhost:8082/api/sparql \
  -H "Content-Type: application/json" \
  -d "{\"query\":\"SELECT * WHERE { ?s ?p ?o } LIMIT 1\"}"
```

Current local verification date: 2026-08-29.

Current dataset status from `GET /api/health`:

```text
triples: 1633
loaded files: 3
missing files: 0
```

## 1. Count Loaded Legal Resources

Purpose:

- Confirms that Gazzetta legal resources are loaded into the TDB2-backed repository.

Query:

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>

SELECT (COUNT(DISTINCT ?act) AS ?legalResources) WHERE {
  ?act a eli:LegalResource .
}
```

Expected true result:

```text
legalResources = 99
```

## 2. Validate Work, Expression, And Manifestation

Purpose:

- Confirms that the UI can show the ELI work/resource level, expression level, and manifestation level for one loaded Gazzetta act.

Query:

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>

SELECT ?act ?expression ?manifestation ?version ?language ?format WHERE {
  ?act eli:id_local "26A03275" ;
       eli:is_realized_by ?expression .
  OPTIONAL { ?act eli:version ?version . }
  OPTIONAL { ?expression eli:language ?language . }
  OPTIONAL { ?expression eli:is_embodied_by ?manifestation . }
  OPTIONAL { ?manifestation eli:format ?format . }
}
```

Expected true result:

```text
act = http://www.gazzettaufficiale.it/eli/id/2026/07/09/26A03275/sg
expression = http://www.gazzettaufficiale.it/eli/id/2026/07/09/26A03275/sg/ita
manifestation = http://www.gazzettaufficiale.it/eli/id/2026/07/09/26A03275/sg/ita/html
version = http://www.gazzettaufficiale.it/eli/tables/versions#ORIGINAL
language = http://publications.europa.eu/resource/authority/language/ITA
format = http://www.iana.org/assignments/media-types/text/html
```

## 3. Validate Outgoing Normattiva Relations

Purpose:

- Confirms that a loaded modifying act has outgoing legal relations.
- This is a good demo case for relation navigation in the UI.

Query:

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>
PREFIX ilg: <http://example.org/italian-legislation/ontology#>

SELECT ?source ?predicate ?target WHERE {
  VALUES ?source { <http://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg> }
  ?source ?predicate ?target .
  FILTER(?predicate IN (ilg:modifies, eli:commences))
}
ORDER BY ?predicate ?target
```

Expected true results:

```text
source = http://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg
predicate = http://data.europa.eu/eli/ontology#commences
target = http://www.gazzettaufficiale.it/eli/id/2025/01/24/25G00010/sg

source = http://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg
predicate = http://example.org/italian-legislation/ontology#modifies
target = http://www.gazzettaufficiale.it/eli/id/2025/01/24/25G00010/sg

source = http://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg
predicate = http://example.org/italian-legislation/ontology#modifies
target = http://www.gazzettaufficiale.it/eli/id/2025/01/30/25G00013/sg
```

## 4. Validate Incoming Reciprocal Relations

Purpose:

- Confirms that the graph can be navigated in the reverse direction.
- This validates the linked-data UI requirement that resources can be reached through relations among them.

Query:

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>
PREFIX ilg: <http://example.org/italian-legislation/ontology#>

SELECT ?source ?predicate ?target WHERE {
  VALUES ?target { <http://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg> }
  ?source ?predicate ?target .
  FILTER(?predicate IN (ilg:modifiedBy, eli:commenced_by))
}
ORDER BY ?predicate ?source
```

Expected true results:

```text
source = http://www.gazzettaufficiale.it/eli/id/2025/01/24/25G00010/sg
predicate = http://data.europa.eu/eli/ontology#commenced_by
target = http://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg

source = http://www.gazzettaufficiale.it/eli/id/2025/01/24/25G00010/sg
predicate = http://example.org/italian-legislation/ontology#modifiedBy
target = http://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg

source = http://www.gazzettaufficiale.it/eli/id/2025/01/30/25G00013/sg
predicate = http://example.org/italian-legislation/ontology#modifiedBy
target = http://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg
```

## 5. Detect Acts With Multiple Expressions

Purpose:

- Checks whether the current repository contains legal acts with more than one loaded expression/version.
- This is the validation query required for the professor's multi-version requirement.

Query:

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>

SELECT ?act (COUNT(DISTINCT ?expression) AS ?expressionCount) WHERE {
  ?act a eli:LegalResource ;
       eli:is_realized_by ?expression .
}
GROUP BY ?act
HAVING (COUNT(DISTINCT ?expression) > 1)
ORDER BY DESC(?expressionCount)
LIMIT 20
```

Expected true result for the current demo dataset:

```text
0 rows
```

Interpretation:

- The current demo validates the ELI work/expression/manifestation chain.
- It does not yet contain a real legislative act with multiple expressions.
- To fully satisfy the professor's multi-version requirement, the next data task must load a Normattiva/OpenData example where one act has multiple versions/expressions, then this query should return that act and its expression count.

## UI Validation Flow

Use the local UI:

```text
http://localhost:8082
```

Recommended checks:

```text
Search 26A03275
Confirm Expression Level has 1 item
Confirm Manifestation Level has 1 item
Click the expression and manifestation resources
```

```text
Search 25G00041
Confirm outgoing relations show Commences / converts and Modifies
Confirm incoming relations show Commenced / converted by and Modified by
Click related resources and confirm the panel navigates inside the app
```
