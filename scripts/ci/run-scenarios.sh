#!/usr/bin/env bash
# CI: the scenario checks of one port on dedicated servers (batched: one Minecraft launch per version).
#   scripts/ci/run-scenarios.sh <port-dir> <built-jar> <run-dir> [<original-jar>]
# 1. smoke (always): generated scenario, `porter test run` on the 1.7.10 server with the port's jar: the server starts,
#    every block/item of the IR is registered, and smoke-log-check.sh finds no exceptions in the server log.
# 2. the port's own scenarios (if any; client/GUI scenarios are skipped, CI has no display): `porter test diff`
#    1.19.2 original vs the port when the original jar is given, else `porter test run` on the port alone.
# Env: PORTER (path of bin/porter), PORTER_TESTKIT_ENV (scripts/ci/testkit-setup.sh), TK_WORK (default $RUNNER_TEMP/tk).
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
port="${1:?port dir}"; jar="${2:?built jar}"; run_dir="${3:?run dir}"; orig="${4:-}"
PORTER="${PORTER:-$repo/cli/build/install/porter/bin/porter}"
: "${PORTER_TESTKIT_ENV:?run scripts/ci/testkit-setup.sh first}"
name="$(basename "$port")"
work="${TK_WORK:-${RUNNER_TEMP:-/tmp}/tk}/$name"
ir="$run_dir/project/build/porter-ir"
rm -rf "$work"; mkdir -p "$work"
rc=0
ir_args=(); [ -d "$ir" ] && ir_args=(--ir "$ir")
cd "$repo"

echo "::group::smoke scenario ($name)"
python3 "$here/gen-smoke.py" "$port" "$ir" "$work/smoke/smoke-$name.scenario.yaml" || exit 1
"$PORTER" validate "$work/smoke" || { echo "::error::generated smoke scenario is invalid"; exit 1; }
"$PORTER" test run --version forge-1.7.10 --scenario "$work/smoke" --mod "$jar" "${ir_args[@]}" \
  --work "$work/smoke-run" --out-dir "$work/smoke-out" --timeout 900 --retries 1
smoke_rc=$?
echo "::endgroup::"
if [ $smoke_rc -ne 0 ]; then echo "::error::smoke scenario FAILED for $name (server start / registration)"; rc=1; fi
prefixes="$(python3 - "$port" <<'PY'
import sys, yaml
from pathlib import Path
cfg = yaml.safe_load(next(Path(sys.argv[1]).glob("*.port.yaml")).read_text(encoding="utf-8"))
print(" ".join((cfg.get("closure") or {}).get("prefixes", [])))
PY
)"
# shellcheck disable=SC2086
"$here/smoke-log-check.sh" "$work/smoke-run" $prefixes || rc=1

# the port's own scenarios without client/GUI ones
mkdir -p "$work/scn"
n=0
for f in "$port"/scenarios/*.scenario.yaml; do
  [ -e "$f" ] || continue
  if grep -Eq '^\s*side:\s*client' "$f"; then echo "skipped (client scenario, needs a display): $f"; continue; fi
  cp "$f" "$work/scn/"; n=$((n+1))
done
if [ "$n" -eq 0 ]; then
  echo "no server scenarios in $port: verified by the smoke pass only"
else
  echo "::group::$n scenarios ($name)"
  if [ -n "$orig" ]; then
    "$PORTER" test diff --scenario "$work/scn" --a "$orig" --b "$jar" "${ir_args[@]}" \
      --work "$work/scn-run" --out-dir "$work/scn-out" --timeout 900 --retries 1
  else
    "$PORTER" test run --version forge-1.7.10 --scenario "$work/scn" --mod "$jar" "${ir_args[@]}" \
      --work "$work/scn-run" --out-dir "$work/scn-out" --timeout 900 --retries 1
  fi
  [ $? -eq 0 ] || { echo "::error::scenarios FAILED for $name"; rc=1; }
  echo "::endgroup::"
fi
exit $rc
