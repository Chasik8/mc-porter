#!/usr/bin/env bash
# Compiles a `porter generate` output tree against its Forge, in a prepared harness project.
#   compile-gen.sh <1.7.10|1.19.2> <gen-dir> [log-file]
# Harnesses (outside the repo):
#   1.7.10: $PORTER_GEN_1710 (default $PORTER_CACHE/gen-1.7.10-template) — RFG/GTNH convention, copied
#           from an RFG 1.7.10 mod setup; the generated src/ is copied into it, gradle.properties gets modId/modGroup.
#           The NEI integration (<pkg>.nei) needs NotEnoughItems 1.0.5.x + CodeChickenCore + CodeChickenLib as
#           compileOnly(rfg.deobf(files(...))) in the harness dependencies.gradle; `gradlew neiCheck` there runs the
#           headless check (generate/scripts/nei/nei-check.gradle, -PneiCheckPackages=<pkg>.nei).
#   1.19.2: $PORTER_GEN_1192 (default $PORTER_CACHE/gen-1.19.2-template) — ForgeGradle 5.1.77,
#           Forge 1.19.2-43.4.0, official mappings; compiles <gen-dir>/src via -PporterGen.
# Gradle runs through $PORTER_HEAVY_RUN when set (a one-build-at-a-time queue); heaps are capped at 2 GB.
set -euo pipefail
version="$1"; gen="$(cd "$2" && pwd)"; log="${3:-$gen/compile.log}"
QUEUE=${PORTER_HEAVY_RUN:-}  # optional job-queue wrapper: <wrapper> <owner> <command...>
prop() { sed -n "s/^  \"$1\" : \"\(.*\)\",\?$/\1/p" "$gen/porter-generate.json" | head -1; }
case "$version" in
  1.7.10)
    T=${PORTER_GEN_1710:-$PORTER_CACHE/gen-1.7.10-template}
    rm -rf "$T/src"; mkdir -p "$T/src"; cp -r "$gen/src/." "$T/src/"
    modid=$(prop modId); pkg=$(prop package); name=$(prop modName)
    sed -i -e "s/^modId = .*/modId = $modid/" -e "s/^modGroup = .*/modGroup = $pkg/" -e "s/^modName = .*/modName = $name/" "$T/gradle.properties"
    cd "$T"
    JAVA_HOME=${JDK25:?set JDK25 to a JDK 21+ home} ${QUEUE:+"$QUEUE" generate} ./gradlew --no-daemon compileJava \
      -Dorg.gradle.jvmargs=-Xmx2g >"$log" 2>&1 && rc=0 || rc=$?
    ;;
  1.19.2)
    T=${PORTER_GEN_1192:-$PORTER_CACHE/gen-1.19.2-template}
    cd "$T"
    JAVA_HOME=${JDK17:?set JDK17 to a JDK 17 home} ${QUEUE:+"$QUEUE" generate} ./gradlew --no-daemon compileJava \
      "-PporterGen=$gen" -Dorg.gradle.jvmargs=-Xmx2g >"$log" 2>&1 && rc=0 || rc=$?
    ;;
  *) echo "unknown version $version" >&2; exit 2 ;;
esac
grep -E "error:|warning: \[|BUILD (SUCCESSFUL|FAILED)" "$log" | grep -v "^\[queue\]" | tail -40 || true
echo "compile-gen $version: exit $rc (log $log)"
exit $rc
