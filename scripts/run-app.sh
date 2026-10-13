#!/usr/bin/env bash
# Starts the web application on http://localhost:8080.
# Run from anywhere: the script moves into the application folder.
set -euo pipefail
cd "$(dirname "$0")/../jena-git-project"
mvn -B spring-boot:run
