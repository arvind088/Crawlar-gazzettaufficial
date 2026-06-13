# Data Directory

This directory stores local pipeline output. It separates raw acquisition, cleaned tabular data, generated RDF, registry state, and run reports.

## Layout

- `raw/gazzetta/`: cached Gazzetta HTML or source files.
- `raw/normattiva/`: cached Normattiva HTML, TSV, or source files.
- `clean/`: normalized TSV/CSV files produced after extraction and cleaning.
- `rdf/`: generated Turtle files ready for Fuseki loading.
- `registry/`: crawler state used for incremental updates.
- `reports/`: text reports produced by scheduled or manual pipeline runs.

## Registry

`registry/crawl_registry.tsv` is the first simple implementation of the incremental update memory. Later it can be replaced by SQLite if the update logic becomes more complex.

