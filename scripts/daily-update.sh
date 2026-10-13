#!/usr/bin/env bash
# Daily update from Normattiva: changed and missing acts, then import, Gazzetta check and relations.
# Options are passed on, e.g.  scripts/daily-update.sh --dry-run true
# Run from anywhere: the script moves into the application folder.
set -euo pipefail
cd "$(dirname "$0")/../jena-git-project"
mvn -B -q compile exec:java "-Dexec.mainClass=it.legislation.ingest.DailyUpdateRunner" "-Dexec.args=$*"
