#!/usr/bin/env bash
# Measures the RF/t a kinesis line delivers into a BuildCraft quarry on a real Forge 1.7.10 dedicated server,
# once without and once with the fix jar. Needs BuildCraft 7.1.23 (not included; pass your own copy).
#   FORGE_SERVER=<installed forge 1.7.10-10.13.4.1614 server dir> JAVA_HOME=<jdk8> \
#   test/run-bench.sh <buildcraft-7.1.23.jar> [work dir]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
BC_JAR="${1:?path to buildcraft-7.1.23.jar}"
WORK="${2:-$here/../build/bench}"
FORGE_SERVER="${FORGE_SERVER:-${PORTER_CACHE:-$HOME/.cache/porter}/forge-1.7.10-server}"
JDK="${JAVA_HOME:?set JAVA_HOME to a JDK 8}"
TICKS="${BENCH_TICKS:-400}"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) wp() { cygpath -m "$1"; } ;; *) wp() { printf '%s' "$1"; } ;; esac
FIX_JAR="$here/../build/bckinesisfix-1.0.0.jar"
[ -f "$FIX_JAR" ] || { echo "build the fix first (build.sh)" >&2; exit 2; }

# bench mod (test only)
bdir="$here/../build/bench-mod"
rm -rf "$bdir" && mkdir -p "$bdir/classes"
find "$here/src" -name '*.java' | while IFS= read -r f; do wp "$f"; echo; done > "$bdir/sources.txt"
"$JDK/bin/javac" -source 1.6 -target 1.6 -Xlint:-options -proc:none -encoding UTF-8 \
  -cp "$(wp "$FORGE_SERVER/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar")" -d "$(wp "$bdir/classes")" @"$(wp "$bdir/sources.txt")"
(cd "$bdir/classes" && "$JDK/bin/jar" cf "$(wp "$bdir/bckinesisbench.jar")" .)

run() { # <name> <with fix: 0|1>
  local dir="$WORK/$1"
  rm -rf "$dir" && mkdir -p "$dir/mods"
  cp "$BC_JAR" "$bdir/bckinesisbench.jar" "$dir/mods/"
  [ "$2" = 1 ] && cp "$FIX_JAR" "$dir/mods/"
  echo "eula=true" > "$dir/eula.txt"
  printf 'level-type=FLAT\nonline-mode=false\nserver-port=25%03d\ngenerate-structures=false\nspawn-animals=false\nspawn-monsters=false\nview-distance=4\n' $((RANDOM % 1000)) > "$dir/server.properties"
  (cd "$dir" && timeout 600 "$JDK/bin/java" -Xmx1200m -Dbckinesis.bench=1 -Dbckinesis.ticks="$TICKS" -Dfml.queryResult=confirm \
     -jar "$(wp "$FORGE_SERVER/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar")" nogui < /dev/null > server.out 2>&1) || true
  echo "== $1"
  cat "$dir/bench-result.txt" 2>/dev/null || { echo "no result, see $dir/server.out"; tail -20 "$dir/server.out"; }
  grep -hE 'Patched TileBuildCraft|kinesis fix' "$dir/logs/fml-server-latest.log" 2>/dev/null | sed 's/^/   log: /' | head -3 || true
}
run without-fix 0
run with-fix 1
