# Data

Outputs of the pipeline (see ../../ARCHITECTURE.md). Everything here is in Git except the
large raw files and the store, which are rebuilt.

| Folder / file | Made by | Contents |
|---|---|---|
| `raw/normattiva/manifest.tsv` | downloads | Every downloaded file with its SHA-256 (the files themselves are not in Git) |
| `rdf/normattiva_akn.ttl` | `AknImportRunner` | Acts, versions and files as ELI RDF |
| `rdf/gazzetta_check.ttl`, `clean/gazzetta_check.tsv` | `GazzettaCheckRunner` | Check of each act against its Gazzetta Ufficiale page |
| `rdf/relations.ttl`, `clean/relations.tsv`, `clean/conversions.tsv` | `RelationRunner` | Relations between acts and decree-law conversions, with dated assessments |
| `registry/ingestion_watermarks.tsv`, `registry/ingestion_runs.tsv` | daily update | Last successful period per source, and a log of every run |
| `eval/reference_set.tsv`, `eval/evaluation_results.tsv` | `ReferenceSetRunner` | Hand-checked sample and precision |
| `clean/gazzetta_rss_updates.tsv` | `GazzettaRssUpdateRunner` | Gazzetta Ufficiale RSS entries (scheduled GitHub workflow) |
| `rdf/in_force.ttl` | `InForceEnrichmentRunner` | In-force status (scheduled GitHub workflow) |
| `rdf/seed_acts.ttl` | by hand | Four sample acts used by `ValidationQueriesTest` |
| `rdf/gazzetta_metadata_delta.ttl`, `rdf/normattiva_modifications*.ttl`, `rdf/normattiva_multiversion_sample.ttl`, `registry/crawl_registry.tsv`, `clean/gazzetta_archive_links.tsv`, `clean/normattiva_*.tsv` | earlier versions of the crawler | Kept for reference; not used by the pages or relations |
| `tdb2/` (not in Git) | the app | The triple store |
