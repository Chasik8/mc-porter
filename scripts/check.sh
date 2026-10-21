#!/usr/bin/env bash
# Local equivalent of .github/workflows/ci.yml: build + tests + contract examples (port configs and catalog
# included) + ports/catalog.yaml + the distribution zip. Works on Linux and on Windows (Git Bash).
# Needs JDK 21: set JAVA_HOME, e.g.  JAVA_HOME=/path/to/jdk-21 scripts/check.sh
# Extra arguments go to Gradle (e.g. --no-daemon). The publish-safety scan is separate: scripts/publish-check.sh.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew build :cli:distZip --console=plain "$@"

scripts/validate-examples.sh
