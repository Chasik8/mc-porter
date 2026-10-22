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
#   6. names of the private reference mod (built-in list + $PUBLISH_FORBIDDEN_NAMES), in contents and in paths; the
#      only exceptions are ports/ic2c-steam/ (the public IC2 Classic profile: it names the mod openly, ships none of its code), release notes (docs/release/) and the
#      two catalog files that list it (ports/catalog.yaml, ports/README.md)
#   7. development-process traces: assistant/vendor names, "orchestrator", co-authored-by trailers, session links
#
# Limits: these are heuristics. Hand-retyped or reformatted mod code, renamed packages, or code from a mod that is not
# on the denylist can pass; short legitimate snippets (rule tests that quote an SRG id) may need an allow entry in
# scripts/publish-allow.txt (one path glob per line). A green check is not legal advice; reviewers still decide.
set -uo pipefail
cd "$(dirname "$0")/.."
set -uo pipefail
cd "$(dirname "$0")/.."
# One pass in Python (the per-file grep loop was minutes on Windows); same rules, see scripts/publish_check.py.
PY=$(command -v python3 || command -v python)
exec "$PY" scripts/publish_check.py "$@"
