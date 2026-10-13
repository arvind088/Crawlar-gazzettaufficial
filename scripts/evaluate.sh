#!/usr/bin/env bash
# Precision check by hand. 'sample' draws a reference set to fill in (data/eval/reference_set.tsv);
# 'evaluate' (default) computes precision with 95% intervals from the filled-in set.
# Run from anywhere: the script moves into the application folder.
set -euo pipefail
cd "$(dirname "$0")/../jena-git-project"
mvn -B -q compile exec:java "-Dexec.mainClass=it.legislation.ingest.ReferenceSetRunner" "-Dexec.args=${1:-evaluate}"
