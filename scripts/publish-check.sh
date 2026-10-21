#!/usr/bin/env bash
# Publish-safety check (heuristic): fails when files that must never be public are about to be committed or
# released. Used by CI on pull requests (changed files only) and before a release (--all).
#
#   scripts/publish-check.sh                 # files changed against origin/main (or $BASE_REF)
#   scripts/publish-check.sh --all           # every tracked file
#   scripts/publish-check.sh --files a b c   # explicit list
#
# Checks (see CONTRIBUTING.md "Legal model" for what is public and what stays local):
#   1. private/ content (ported output of non-open mods, decompiles, golden data)
#   2. mapping files: tiny/tsrg/srg/csrg/ProGuard (Mojang client.txt/server.txt)/MCP csv
#   3. decompiled code: decompiler banners (CFR, Fernflower, Procyon, JD), dense SRG/obfuscated member names,
#      CFR-style parameter names (var1, var2, ...), packages listed in scripts/publish-denylist.txt (mods that are not
#      open-licence)
#   4. binaries larger than 1 MB, and jars/classes
#   5. local absolute paths (C:\Users\..., /home/<user>/..., /c/Users/...) and e-mail addresses
#   6. names of the private reference mod (built-in list + $PUBLISH_FORBIDDEN_NAMES), in contents and in paths
#   7. development-process traces: assistant/vendor names, "orchestrator", co-authored-by trailers, session links
#
# Limits: these are heuristics. Hand-retyped or reformatted mod code, renamed packages, or code from a mod that is not
# on the denylist can pass; short legitimate snippets (rule tests that quote an SRG id) may need an allow entry in
# scripts/publish-allow.txt (one path glob per line). A green check is not legal advice; reviewers still decide.
set -uo pipefail
cd "$(dirname "$0")/.."

mode=changed
files=()
if [[ "${1:-}" == "--all" ]]; then
  mode=all
elif [[ "${1:-}" == "--files" ]]; then
  mode=files; shift; files=("$@")
fi
if [[ $mode == all ]]; then
  mapfile -t files < <(git ls-files)
elif [[ $mode == changed ]]; then
  base="${BASE_REF:-origin/main}"
  if git rev-parse --verify -q "$base" > /dev/null; then
    mapfile -t files < <(git diff --name-only --diff-filter=ACMR "$base"...HEAD)
  else
    mapfile -t files < <(git ls-files)
  fi
fi

allow=()
[[ -f scripts/publish-allow.txt ]] && mapfile -t allow < <(grep -v '^\s*#' scripts/publish-allow.txt | grep -v '^\s*$')
deny=()
for d in scripts/publish-denylist.txt scripts/publish-denylist.local.txt; do
  [[ -f "$d" ]] && mapfile -t -O "${#deny[@]}" deny < <(grep -v '^\s*#' "$d" | grep -v '^\s*$')
done
# Names of the private reference mod that must never appear in public files or paths (written so that this script does
# not match itself). Extend with PUBLISH_FORBIDDEN_NAMES='regex' for your own private ports.
forbidden='i[c]2|industrial[c]raft'
[[ -n "${PUBLISH_FORBIDDEN_NAMES:-}" ]] && forbidden="$forbidden|$PUBLISH_FORBIDDEN_NAMES"
# Traces of how the tree was produced (assistant and vendor names, orchestration vocabulary, commit trailers);
# written with bracketed letters so that this script does not match itself.
traces='cl[a]ude|anth[r]opic|orches[t]rator|co-auth[o]red-by'

allowed() {
  local f="$1" g
  for g in "${allow[@]}"; do
    # shellcheck disable=SC2053
    [[ "$f" == $g ]] && return 0
  done
  return 1
}

fail=0
problem() { echo "PUBLISH-CHECK: $1: $2"; fail=1; }

for f in "${files[@]}"; do
  [[ -f "$f" ]] || continue
  [[ "$f" == gradle/wrapper/gradle-wrapper.jar || "$f" == scripts/publish-check.sh || "$f" == scripts/publish-denylist.txt || "$f" == scripts/publish-denylist.local.txt || "$f" == scripts/publish-allow.txt ]] && continue
  case "$f" in
    private/*|*/private/*) problem "$f" "private/ content must never be committed"; continue ;;
  esac
  case "${f,,}" in
    *.tiny|*.tsrg|*.tsrg2|*.srg|*.csrg|*.exc|*/fields.csv|*/methods.csv|*/params.csv|*client.txt|*server.txt|*.proguard)
      problem "$f" "looks like a mapping file (never redistribute mappings)"; continue ;;
    *.jar|*.class|*.zip)
      problem "$f" "binary archive/class file"; continue ;;
  esac
  if grep -Eiq "$forbidden" <<< "$f"; then problem "$f" "path contains a forbidden private-mod name"; fi
  if grep -Iq . "$f" 2>/dev/null && grep -Eiq "$forbidden" "$f"; then
    problem "$f" "forbidden private-mod name ($(grep -Eio "$forbidden" "$f" | head -1))"
  fi
  if grep -Eiq "$traces" <<< "$f"; then problem "$f" "path contains a development-process trace"; fi
  if grep -Iq . "$f" 2>/dev/null && grep -Eiq "$traces" "$f"; then
    problem "$f" "development-process trace ($(grep -Eio "$traces" "$f" | head -1))"
  fi
  allowed "$f" && continue
  size=$(wc -c < "$f")
  if (( size > 1048576 )); then problem "$f" "larger than 1 MB ($size bytes)"; fi
  # text checks only for text files
  if ! grep -Iq . "$f" 2>/dev/null; then continue; fi
  if grep -Eq '^\s*[a-zA-Z0-9_.$]+ -> [a-zA-Z0-9_$]+:$' "$f" && grep -Eq '^\s+[0-9]+:[0-9]+:' "$f"; then
    problem "$f" "ProGuard (Mojang) mapping content"
  fi
  case "$f" in
    *.java)
      if grep -Eiq 'Decompiled with CFR|Decompiled by Procyon|// Decompiled by Jad|fernflower|JD-Core' "$f"; then
        problem "$f" "decompiler banner"
      fi
      srg=$(grep -Eo '\b(m|f|func|field|p)_[0-9]+_[a-z]?\b' "$f" | wc -l)
      if (( srg > 20 )); then problem "$f" "$srg SRG/obfuscated member names (decompiled code?)"; fi
      cfr=$(grep -Eo '\bvar[0-9]+\b' "$f" | wc -l)
      if (( cfr > 20 )); then problem "$f" "$cfr CFR-style names var1, var2, ... (decompiled code?)"; fi
      for d in "${deny[@]}"; do
        if grep -Eq "^\s*package\s+${d//./\\.}" "$f"; then problem "$f" "package of a non-open mod ($d)"; fi
      done
      ;;
  esac
  if grep -Eq '[A-Za-z]:\\\\?Users\\\\?[A-Za-z0-9._-]+|/c/Users/[A-Za-z0-9._-]+|/home/[a-z][a-z0-9_-]+/|/Users/[A-Za-z][A-Za-z0-9._-]+/' "$f" \
      && ! grep -Eq '<you>|<user>|you/|/home/me/' <(grep -Eo '[A-Za-z]:\\\\?Users\\\\?[A-Za-z0-9<>._-]+|/c/Users/[A-Za-z0-9<>._-]+|/home/[a-z][a-z0-9_-]+/|/Users/[A-Za-z][A-Za-z0-9._-]+/' "$f" | head -1); then
    problem "$f" "local absolute path ($(grep -Eo '[A-Za-z]:\\\\?Users\\\\?[A-Za-z0-9._-]+|/c/Users/[A-Za-z0-9._-]+|/home/[a-z][a-z0-9_-]+/|/Users/[A-Za-z][A-Za-z0-9._-]+/' "$f" | head -1))"
  fi
  if grep -Eq '\b[A-Za-z]:[\\/]Project[\\/]' "$f"; then problem "$f" "local absolute path (drive:/Project)"; fi
  email=$(grep -Eo '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' "$f" | grep -Ev '^(git@github\.com|.*@example\.(org|com))$' | head -1)
  if [[ -n "$email" ]]; then problem "$f" "e-mail address ($email)"; fi
done

if (( fail )); then
  echo "publish-check FAILED (${#files[@]} file(s) checked). See the limits in the header of scripts/publish-check.sh."
  exit 1
fi
echo "publish-check passed (${#files[@]} file(s) checked)"
