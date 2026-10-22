#!/usr/bin/env bash
# Local equivalent of .github/workflows/ci.yml: build + tests + contract examples (port configs and catalog
# included) + ports/catalog.yaml + the distribution zip. Works on Linux and on Windows (Git Bash).
# Needs JDK 21: set JAVA_HOME, e.g.  JAVA_HOME=/path/to/jdk-21 scripts/check.sh
# Extra arguments go to Gradle (e.g. --no-daemon). The publish-safety scan is separate: scripts/publish-check.sh.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew build :cli:distZip --console=plain "$@"

scripts/validate-examples.sh

# the table in ports/README.md must match ports/catalog.yaml
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$JAVA_BIN" -cp "cli/build/install/porter/lib/*" dev.porter.cli.PorterMain --reports-dir build/reports/porter catalog --check

# every ports/<mod>/ has a catalog entry; smokeOnly exactly when it has no scenario files (needs python3 + PyYAML)
if python3 -c 'import yaml' 2>/dev/null; then python3 scripts/ci/ports.py catalog-check
elif python -c 'import yaml' 2>/dev/null; then python scripts/ci/ports.py catalog-check
else echo "skipped scripts/ci/ports.py catalog-check (PyYAML missing: pip install pyyaml); CI runs it"; fi
