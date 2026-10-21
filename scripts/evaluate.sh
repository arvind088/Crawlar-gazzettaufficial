#!/usr/bin/env bash
# Checks by hand. Precision: 'sample' draws data/eval/reference_set.tsv to fill in, 'evaluate' (default)
# computes precision. Recall: 'recall-sample' draws data/eval/recall_set.tsv, 'recall' computes recall.
# Run from anywhere: the script moves into the application folder.
set -euo pipefail
cd "$(dirname "$0")/../jena-git-project"
MODE="${1:-evaluate}"
case "$MODE" in
  recall*) RUNNER=RecallSetRunner ;;
  *) RUNNER=ReferenceSetRunner ;;
esac
mvn -B -q compile exec:java "-Dexec.mainClass=it.legislation.ingest.$RUNNER" "-Dexec.args=$MODE"
