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
triples: 1659
loaded files: 4
missing files: 0
```

The multi-version example is a small RDF sample derived from the official Normattiva OpenData download documentation. That documentation gives the Codice dell'amministrazione digitale as an example with:

```text
DECRETOLEGISLATIVO_20050307_82
20050516_005G0104_ORIGINALE_V0
20050516_005G0104_VIGENZA_20250320_V52
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
legalResources = 100
```

## 2. Validate Work, Expression, And Manifestation

Purpose:

- Confirms that the UI can show the ELI work/resource level, expression level, and manifestation level.
- The selected example has two expressions: original text and a later vigente version.

Query:

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>

SELECT ?act ?expression ?manifestation ?version ?language ?format WHERE {
  ?act eli:id_local "005G0104" ;
       eli:is_realized_by ?expression .
  OPTIONAL { ?expression eli:version ?version . }
  OPTIONAL { ?expression eli:language ?language . }
  OPTIONAL { ?expression eli:is_embodied_by ?manifestation . }
  OPTIONAL { ?manifestation eli:format ?format . }
}
ORDER BY ?version
```

Expected true results:

```text
act = http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg
expression = http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg/ita/original
manifestation = http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg/ita/original/html
version = http://www.gazzettaufficiale.it/eli/tables/versions#ORIGINALE_V0
language = http://publications.europa.eu/resource/authority/language/ITA
format = http://www.iana.org/assignments/media-types/text/html

act = http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg
expression = http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg/ita/vigente/2025-03-20/v52
manifestation = http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg/ita/vigente/2025-03-20/v52/html
version = http://www.gazzettaufficiale.it/eli/tables/versions#VIGENZA_20250320_V52
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
act = http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg
expressionCount = 2
```

Interpretation:

- The current demo now validates the ELI work/expression/manifestation chain.
- It also contains one concrete multi-version example based on Normattiva OpenData naming.
- The next data task is to replace or supplement this curated sample with data downloaded directly from the Normattiva OpenData API.

## UI Validation Flow

Use the local UI:

```text
http://localhost:8082
```

Recommended checks:

```text
Search 005G0104
Confirm Expression Level has 2 items
Confirm Manifestation Level has 2 items
Click the expression and manifestation resources
```

```text
Search 25G00041
Confirm outgoing relations show Commences / converts and Modifies
Confirm incoming relations show Commenced / converted by and Modified by
Click related resources and confirm the panel navigates inside the app
```
