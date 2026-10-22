#!/usr/bin/env bash
# Validates contracts/examples with the built CLI (every valid/ file must pass, every invalid/ file must fail),
# ports/catalog.yaml and every ports/*/*.port.yaml profile.
# Requires `./gradlew build` (or :cli:installDist) to have run.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -n "${JAVA_HOME:-}" ]]; then JAVA="$JAVA_HOME/bin/java"; else JAVA=java; fi
LIB=cli/build/install/porter/lib
[[ -d "$LIB" ]] || { echo "missing $LIB: run ./gradlew build first" >&2; exit 2; }
porter() { "$JAVA" -cp "$LIB/*" dev.porter.cli.PorterMain --reports-dir build/reports/porter "$@"; }

fail=0
echo "== valid examples (must pass)"
for dir in contracts/examples/*/valid; do
  porter validate --quiet "$dir" || { echo "FAIL: $dir contains invalid files"; fail=1; }
done

echo "== invalid examples (must fail)"
# one JVM per file: run PORTER_JOBS (default: cores) of them at a time, report in file order
JOBS="${PORTER_JOBS:-$(nproc 2>/dev/null || echo 2)}"
tmp="$(mktemp -d)"; i=0
for f in contracts/examples/*/invalid/*; do
  i=$((i+1))
  ( if porter validate --quiet "$f" > /dev/null 2>&1; then echo "FAIL: $f validated but must be invalid" > "$tmp/$i.fail"; fi
    echo "ok (rejected): $f" > "$tmp/$i.out" ) &
  while [[ $(jobs -rp | wc -l) -ge $JOBS ]]; do wait -n || true; done
done
wait
for n in $(seq 1 $i); do
  [[ -e "$tmp/$n.fail" ]] && { cat "$tmp/$n.fail"; fail=1; } || cat "$tmp/$n.out"
done
rm -rf "$tmp"

echo "== port catalog"
porter validate ports/catalog.yaml || { echo "FAIL: ports/catalog.yaml"; fail=1; }
for f in ports/*/*.port.yaml; do
  [[ -e "$f" ]] || continue
  porter validate "$f" || { echo "FAIL: $f"; fail=1; }
done

if [[ $fail -ne 0 ]]; then echo "example validation FAILED"; exit 1; fi
echo "example validation passed"
