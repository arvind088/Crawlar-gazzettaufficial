# Manual UI Test Cases

Use these checks before committing or before showing the demo.

Local app:

```text
http://localhost:8082
```

## 1. Basic Health

Steps:

1. Open `http://localhost:8082`.
2. Confirm the app loads without a browser error.
3. Open `http://localhost:8082/api/health`.

Expected:

```text
triples > 0
loadedFiles includes 4 RDF files
missingFiles is empty
```

## 2. Linked Data Resource Navigation

Steps:

1. Search `005G0104`.
2. Open the result.
3. Confirm the selected resource panel shows Expression Level and Manifestation Level.
4. Click an expression or manifestation link.

Expected:

```text
Expression Level has 2 rows
Manifestation Level has 2 rows
Clicked resources resolve inside the UI
```

## 3. Existing Reviewed RDF Relations

Steps:

1. Search `25G00041`.
2. Open the result.
3. Inspect outgoing and incoming linked-data relations.
4. Click one related resource.

Expected:

```text
Important relation labels appear first
Commences / converts is visible
Modifies is visible
Navigation stays inside the UI
```

## 4. Normattiva Import Fallback: Missing File

Steps:

1. Open the technical/status tab.
2. Click `Import Updates` without placing a file in `data/import`.

Expected:

```text
The import result shows FAILED
The message names the missing import path
The app does not crash
```

## 5. Normattiva Import Fallback: Saved Updates

Setup:

Place one saved official update response at:

```text
data/import/normattiva_updates.json
```

Accepted fallback:

```text
data/import/normattiva_updates.tsv
```

Steps:

1. Open the technical/status tab.
2. Click `Import Updates`.
3. Open the Normattiva tab.

Expected:

```text
Import result shows COMPLETED
rows > 0
Update candidates table shows imported rows
Rows include code and GU date when the source file contains codiceRedazionale and dataGU
```

## 6. Normattiva Detail Import

Setup:

Place saved detail data at:

```text
data/import/normattiva_details.json
```

Accepted fallback:

```text
data/import/normattiva_details.tsv
```

Steps:

1. Open the technical/status tab.
2. Click `Import Details`.
3. Open the Normattiva tab.

Expected:

```text
Import result shows COMPLETED
Detail evidence table shows imported rows
No RDF relationship is created by this import
```

## 7. Evidence Scan

Steps:

1. Open the technical/status tab.
2. Click `Scan Evidence`.
3. Open the Normattiva tab.

Expected:

```text
Evidence scan shows COMPLETED
Relation evidence table refreshes
Evidence rows appear only when detail text contains relation terms
Legal relationships table is unchanged
```

## 8. Relation Candidate Extraction

Precondition:

Relation evidence rows must contain at least two ELI HTTP resource URLs such as:

```text
https://www.gazzettaufficiale.it/eli/id/2025/03/01/25G00028/sg
https://www.gazzettaufficiale.it/eli/id/2025/03/24/25G00041/sg
```

Steps:

1. Open the technical/status tab.
2. Click `Extract Candidates`.
3. Open the Normattiva tab.

Expected:

```text
Candidate extraction shows COMPLETED
Relation candidates table shows source, relation, target, and needs_review
Rows are review candidates only
No RDF is generated
URN-only evidence does not create candidates
```

## 9. SPARQL Validation

Steps:

1. Open the SPARQL tab.
2. Run the validation queries from `VALIDATION_QUERIES.md`.

Expected:

```text
005G0104 returns two expressions
25G00041 returns existing reviewed RDF relations
No new RDF appears from imported evidence unless a later reviewed RDF generation step is explicitly added
```
