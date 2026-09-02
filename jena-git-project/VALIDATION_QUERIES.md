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

## Multi-Version Sample Details

The sample resource is:

```text
http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg
```

This represents the legal resource/work level for:

```text
Codice dell'amministrazione digitale
Decreto legislativo 7 marzo 2005, n. 82
Published in Gazzetta Ufficiale on 2005-05-16
Redactional/local ID: 005G0104
```

The sample is intentionally small. It does not try to store the full legislative text. Its purpose is to validate the ELI linked-data structure that the UI must expose:

```text
LegalResource / work
  -> LegalExpression / version
      -> Format / manifestation
```

### Work / Legal Resource

The work-level URI is:

```text
http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg
```

Important triples:

```turtle
<http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg>
        rdf:type             eli:LegalResource ;
        rdfs:label           "Codice dell'amministrazione digitale" ;
        eli:date_document    "2005-03-07"^^xsd:date ;
        eli:date_publication "2005-05-16"^^xsd:date ;
        eli:id_local         "005G0104" ;
        eli:number           "82" ;
        eli:type_document    <http://www.gazzettaufficiale.it/eli/tables/resource-type#DECRETOLEGISLATIVO> ;
        eli:is_realized_by   <.../original> ,
                             <.../vigente/2025-03-20/v52> .
```

Meaning:

- `eli:LegalResource` is the abstract legal act.
- `eli:id_local` lets the UI find the resource by the familiar local ID `005G0104`.
- `eli:is_realized_by` connects the legal act to its expressions/versions.
- Having two `eli:is_realized_by` values is what makes this a multi-version demonstration.

### Expression 1: Original Version

The original expression URI is:

```text
http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg/ita/original
```

Important triples:

```turtle
<http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg/ita/original>
        rdf:type           eli:LegalExpression ;
        rdfs:label         "Codice dell'amministrazione digitale - testo originale" ;
        eli:language       <http://publications.europa.eu/resource/authority/language/ITA> ;
        eli:realizes       <http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg> ;
        eli:version        <http://www.gazzettaufficiale.it/eli/tables/versions#ORIGINALE_V0> ;
        eli:is_embodied_by <http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg/ita/original/html> .
```

Meaning:

- This is one expression/version of the legal act.
- `eli:version` records that this is the original version.
- `eli:realizes` links the expression back to the legal resource/work.
- `eli:is_embodied_by` links this expression to a concrete manifestation.

### Expression 2: Vigente Version

The later vigente expression URI is:

```text
http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg/ita/vigente/2025-03-20/v52
```

Important triples:

```turtle
<http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg/ita/vigente/2025-03-20/v52>
        rdf:type           eli:LegalExpression ;
        rdfs:label         "Codice dell'amministrazione digitale - testo vigente al 2025-03-20, versione 52" ;
        eli:language       <http://publications.europa.eu/resource/authority/language/ITA> ;
        eli:realizes       <http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg> ;
        eli:version        <http://www.gazzettaufficiale.it/eli/tables/versions#VIGENZA_20250320_V52> ;
        eli:is_embodied_by <http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg/ita/vigente/2025-03-20/v52/html> .
```

Meaning:

- This is another expression/version of the same legal act.
- The version name follows the Normattiva OpenData naming pattern:

```text
20050516_005G0104_VIGENZA_20250320_V52
```

- This lets the UI demonstrate that one legal act can expose multiple expressions.

### Manifestations

The two manifestation URIs are:

```text
http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg/ita/original/html
http://www.gazzettaufficiale.it/eli/id/2005/05/16/005G0104/sg/ita/vigente/2025-03-20/v52/html
```

Important triples:

```turtle
<.../original/html>
        rdf:type   eli:Format ;
        eli:format <http://www.iana.org/assignments/media-types/text/html> .

<.../vigente/2025-03-20/v52/html>
        rdf:type   eli:Format ;
        eli:format <http://www.iana.org/assignments/media-types/text/html> .
```

Meaning:

- A manifestation is the concrete representation of an expression.
- In this sample, both expressions have an HTML manifestation.
- The UI can therefore show both the expression level and the manifestation level.

### What This Proves

This sample proves four important points:

- The repository can store a legal resource with more than one expression.
- The UI can query TDB2 and display those expressions.
- The UI can query and display manifestations for each expression.
- The user can navigate the linked-data chain inside the interface.

The chain demonstrated by this sample is:

```text
005G0104 legal resource
  -> original expression
      -> original HTML manifestation
  -> vigente V52 expression
      -> vigente V52 HTML manifestation
```

### Current Limitation

This is a curated RDF sample, not yet the result of a fully automated Normattiva API ingestion routine.

That is acceptable for the current validation step because it makes the ELI model and UI behavior explicit and testable. The next data-engineering step is to replace or supplement this sample with records downloaded directly from the Normattiva OpenData APIs.

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

## Reviewed Relation Candidate Validation

Relation candidates produced from `data/clean/normattiva_relation_candidates.tsv` are not loaded into TDB2 and are not RDF yet. They are a review queue.

After a later reviewed RDF promotion step, validate the promoted relation with SPARQL before treating it as true graph data:

```sparql
PREFIX eli: <http://data.europa.eu/eli/ontology#>
PREFIX ilg: <http://example.org/italian-legislation/ontology#>

SELECT ?source ?predicate ?target WHERE {
  VALUES (?source ?predicate ?target) {
    (
      <http://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg>
      eli:commences
      <http://www.gazzettaufficiale.it/eli/id/2025/03/01/25G00028/sg>
    )
  }
  ?source ?predicate ?target .
}
```

Expected result before reviewed RDF promotion:

```text
0 rows
```

Expected result after reviewed RDF promotion:

```text
source = http://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg
predicate = http://data.europa.eu/eli/ontology#commences
target = http://www.gazzettaufficiale.it/eli/id/2025/03/01/25G00028/sg
```
