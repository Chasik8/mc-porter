#!/usr/bin/env bash
# Builds the 1.19.2 test mod without ForgeGradle: javac against the mojmap compile jar made by setup.sh, then
# reobfuscation mojmap -> SRG with ForgeAutoRenamingTool (the same tool the Forge installer uses).
# Output: build/portertestkit-1.19.2.jar  (run through the heavy-job queue, see testkit/README.md)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
CACHE="${PORTER_CACHE:-$HOME/.cache/porter}"
JAVA17="${JAVA17:-java}"
JDKBIN="$(dirname "$JAVA17")"
FORGE_SERVER="${FORGE_SERVER:-$CACHE/forge-1.19.2-server}"
COMPILE_DIR="${COMPILE_DIR:-$CACHE/forge-1.19.2-compile}"
MOJ="$COMPILE_DIR/mc-forge-1.19.2-mojmap.jar"
NAMES="$COMPILE_DIR/moj2srg.tsrg"
CLIENT_DIR="${CLIENT_DIR:-$CACHE/forge-1.19.2-client}"
# with the client toolchain (setup-client.sh) the mod also gets its client part (GUI scenarios)
if [ -f "$COMPILE_DIR/mc-forge-1.19.2-client-mojmap.jar" ]; then
  MOJ="$COMPILE_DIR/mc-forge-1.19.2-client-mojmap.jar"; NAMES="$COMPILE_DIR/moj2srg-client.tsrg"
fi
ART="$FORGE_SERVER/libraries/net/minecraftforge/ForgeAutoRenamingTool/0.1.22/ForgeAutoRenamingTool-0.1.22-all.jar"
[ -f "$MOJ" ] || { echo "run setup.sh first ($MOJ missing)"; exit 2; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';'; wp() { cygpath -m "$1"; } ;; *) SEP=':'; wp() { printf '%s' "$1"; } ;; esac
cp_list="$(wp "$MOJ")"
lib_args="--lib $(wp "$MOJ")"
while IFS= read -r j; do
  case "$j" in */net/minecraft/server/*|*/forge-1.19.2-43.5.2-server.jar|*/ForgeAutoRenamingTool/*|*/installertools/*|*/jarsplitter/*|*/binarypatcher/*) continue ;; esac
  cp_list="$cp_list$SEP$(wp "$j")"
  # Forge classes (FakePlayer, ...) inherit Minecraft members: ART needs them for the hierarchy
  lib_args="$lib_args --lib $(wp "$j")"
done < <(find "$FORGE_SERVER/libraries" -name '*.jar' | sort; [ -d "$CLIENT_DIR/libraries/org/lwjgl" ] && find "$CLIENT_DIR/libraries/org/lwjgl" -name 'lwjgl*-3.3.1.jar' | sort)
out="$here/build"
rm -rf "$out/classes" && mkdir -p "$out/classes"
find "$here/src/main/java" "$here/../mod-common/src/main/java" -name '*.java' | { if [ "$NAMES" = "$COMPILE_DIR/moj2srg.tsrg" ]; then grep -v ClientDriver1192; else cat; fi; } | sort | while IFS= read -r f; do wp "$f"; echo; done > "$out/sources.txt"
"$JDKBIN/javac" -J-Xmx768m --release 17 -encoding UTF-8 -Xlint:-options -proc:none -cp "$cp_list" -d "$(wp "$out/classes")" @"$(wp "$out/sources.txt")"
cp -r "$here/src/main/resources/." "$out/classes/"
rm -f "$out/portertestkit-1.19.2-mojmap.jar" "$out/portertestkit-1.19.2.jar"
"$JDKBIN/jar" --create --file "$(wp "$out/portertestkit-1.19.2-mojmap.jar")" --date=1980-01-01T00:00:02Z -C "$(wp "$out/classes")" .
# shellcheck disable=SC2086
"$JAVA17" -Xmx768m -jar "$(wp "$ART")" --input "$(wp "$out/portertestkit-1.19.2-mojmap.jar")" --output "$(wp "$out/portertestkit-1.19.2.jar")" \
  --names "$(wp "$NAMES")" $lib_args --threads 1 > "$out/art.log" 2>&1 || { cat "$out/art.log"; exit 1; }
echo "built $out/portertestkit-1.19.2.jar"
# sanity check: no mojmap-named Minecraft member may survive reobfuscation (NoSuchMethodError in production).
# Exact owner.name:desc match against the mapping (subclass owners such as FakePlayer are checked by name only).
awk '/^[^\t]/ {owner=$1; next} NF==3 {print owner "." $1 ":" $2} NF==2 {print owner "." $1}' \
  "$NAMES" | sort -u > "$out/moj-members.txt"
classes=$(unzip -l "$out/portertestkit-1.19.2.jar" | grep -o 'dev/porter/[^ ]*\.class' | sed 's/\.class$//; s#/#.#g')
refs=$("$JDKBIN/javap" -c -p -cp "$(wp "$out/portertestkit-1.19.2.jar")" $classes \
  | grep -oE '// (Method|Field|InterfaceMethod) net/minecraft[^ ]*' | sed -E 's#^// [A-Za-z]+ ##; s#:\(#:(#' | sort -u)
bad=""
for r in $refs; do
  case "$r" in *Field*) ;; esac
  m="$r"; f="${r%%:*}"
  if grep -qxF "$m" "$out/moj-members.txt" || grep -qxF "$f" "$out/moj-members.txt"; then bad="$bad $r"; fi
done
[ -z "$bad" ] || { echo "unmapped mojmap references after reobf:$bad"; exit 1; }
echo "reobf check ok"
