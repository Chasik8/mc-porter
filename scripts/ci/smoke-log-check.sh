#!/usr/bin/env bash
# Smoke check of the server logs of a scenario run: no exceptions caused by the mod.
#   scripts/ci/smoke-log-check.sh <work-dir> <mod-package-prefix>...
# <work-dir> is the testkit --work directory; every console.log below it is scanned. A log fails when it has
#  (a) a linkage/class-loading error (NoSuchMethodError, NoClassDefFoundError, AbstractMethodError, ...), a mod-loading
#      failure or "Exception in thread", anywhere, or
#  (b) a stack trace line "at <prefix>..." or an exception line that mentions one of the mod's package prefixes.
# Known harmless noise of the vanilla/Forge servers is listed in scripts/ci/smoke-log-allow.txt (one ERE per line).
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="${1:?work dir}"; shift
prefixes=("$@")
fatal='NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|AbstractMethodError|IncompatibleClassChangeError|ClassNotFoundException|VerifyError|LinkageError|ClassCastException|ExceptionInInitializerError|Exception in thread|has failed to load|Mod .* was not loaded|Caught exception from|Encountered an unexpected exception|Unable to load|NullPointerException|IllegalAccessError|UnsatisfiedLinkError'
status=0
mapfile -t logs < <(find "$work" -name console.log 2>/dev/null | sort)
if [ "${#logs[@]}" -eq 0 ]; then echo "smoke-log-check: no console.log under $work (the server did not run?)"; exit 1; fi
for log in "${logs[@]}"; do
  hits="$(grep -E "$fatal" "$log" | grep -Evf "$here/smoke-log-allow.txt" || true)"
  for p in "${prefixes[@]}"; do
    [ -n "$p" ] || continue
    pp="${p//./\\.}"
    hits+=$'\n'"$(grep -E "^\s+at ${pp}|(Exception|Error).*${pp}" "$log" | grep -Evf "$here/smoke-log-allow.txt" || true)"
  done
  hits="$(printf '%s\n' "$hits" | sed '/^$/d' | head -20)"
  if [ -n "$hits" ]; then
    echo "::error::exceptions in the server log $log"
    printf '%s\n' "$hits" | sed 's/^/    /'
    status=1
  else
    echo "ok: no exceptions in $log"
  fi
done
exit $status
