#!/usr/bin/env bash
# Builds build/bckinesisfix-1.0.0.jar with a JDK 8 (javac + jar, no Gradle, works offline).
# Compile classpath: an installed Forge 1.7.10-10.13.4.1614 server directory
# (forge-1.7.10-10.13.4.1614-1.7.10-universal.jar, minecraft_server.1.7.10.jar, libraries/).
#   FORGE_SERVER=<dir> JAVA_HOME=<jdk8> ./build.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
FORGE_SERVER="${FORGE_SERVER:-${PORTER_CACHE:-$HOME/.cache/porter}/forge-1.7.10-server}"
JDK="${JAVA_HOME:?set JAVA_HOME to a JDK 8}"
VERSION=1.0.0
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';'; wp() { cygpath -m "$1"; } ;; *) SEP=':'; wp() { printf '%s' "$1"; } ;; esac
cp_list="$(wp "$FORGE_SERVER/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar")"
cp_list="$cp_list$SEP$(wp "$FORGE_SERVER/minecraft_server.1.7.10.jar")"
for lib in net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar \
           com/google/guava/guava/17.0/guava-17.0.jar; do
  [ -f "$FORGE_SERVER/libraries/$lib" ] || { echo "missing $FORGE_SERVER/libraries/$lib" >&2; exit 2; }
  cp_list="$cp_list$SEP$(wp "$FORGE_SERVER/libraries/$lib")"
done
out="$here/build"
rm -rf "$out/classes" && mkdir -p "$out/classes"
find "$here/src/main/java" -name '*.java' | sort | while IFS= read -r f; do wp "$f"; echo; done > "$out/sources.txt"
"$JDK/bin/javac" -source 1.6 -target 1.6 -Xlint:-options -encoding UTF-8 -proc:none -cp "$cp_list" \
  -d "$(wp "$out/classes")" @"$(wp "$out/sources.txt")"
cp "$here/src/main/resources/mcmod.info" "$here/../../LICENSE" "$out/classes/"
jar="$out/bckinesisfix-$VERSION.jar"
rm -f "$jar"
(cd "$out/classes" && "$JDK/bin/jar" cfm "$(wp "$jar")" "$(wp "$here/src/main/resources/META-INF/MANIFEST.MF")" .)
echo "built $jar"
