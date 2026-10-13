#!/usr/bin/env bash
# Full first build: download every act (2020 to today) from Normattiva, import it,
# check it against Gazzetta Ufficiale and find the relations. Takes hours; safe to re-run
# (exports already downloaded are skipped).
# Run from anywhere: the script moves into the application folder.
set -euo pipefail
cd "$(dirname "$0")/../jena-git-project"
mvn -B -q compile exec:java "-Dexec.mainClass=it.legislation.source.normattiva.NormattivaCorpusRunner" "-Dexec.args=$*"
mvn -B -q compile exec:java "-Dexec.mainClass=it.legislation.ingest.AknImportRunner"
mvn -B -q compile exec:java "-Dexec.mainClass=it.legislation.ingest.GazzettaCheckRunner"
mvn -B -q compile exec:java "-Dexec.mainClass=it.legislation.ingest.RelationRunner"
