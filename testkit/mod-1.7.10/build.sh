#!/usr/bin/env bash
# Builds the 1.7.10 test mod: javac --release 8 against the MCP-named Minecraft+Forge jar (setup.sh), then
# reobfuscation MCP -> SRG with ForgeAutoRenamingTool (taken from the Forge 1.19.2 installer libraries).
# Output: build/portertestkit-1.7.10.jar (SRG names, for production servers/clients)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
CACHE="${PORTER_CACHE:-$HOME/.cache/porter}"
JAVA17="${JAVA17:-java}"
JDKBIN="$(dirname "$JAVA17")"
COMPILE_DIR="${COMPILE_DIR:-$CACHE/forge-1.7.10-compile}"
FORGE_SERVER="${FORGE_SERVER:-$CACHE/forge-1.7.10-server}"
ART="${ART:-$CACHE/forge-1.19.2-server/libraries/net/minecraftforge/ForgeAutoRenamingTool/0.1.22/ForgeAutoRenamingTool-0.1.22-all.jar}"
LWJGL="${LWJGL:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.lwjgl.lwjgl/lwjgl" -name 'lwjgl-2*.jar' | head -1)}"
MCP="$COMPILE_DIR/mc-forge-1.7.10-mcp.jar"
[ -f "$MCP" ] || { echo "run setup.sh first ($MCP missing)"; exit 2; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';'; wp() { cygpath -m "$1"; } ;; *) SEP=':'; wp() { printf '%s' "$1"; } ;; esac
cp_list="$(wp "$MCP")$SEP$(wp "$LWJGL")"
while IFS= read -r j; do cp_list="$cp_list$SEP$(wp "$j")"; done < <(find "$FORGE_SERVER/libraries" -name '*.jar' | sort)
cp_list="$cp_list$SEP$(wp "$FORGE_SERVER/minecraft_server.1.7.10.jar")"
out="$here/build"
rm -rf "$out/classes" && mkdir -p "$out/classes"
find "$here/src/main/java" "$here/../mod-common/src/main/java" -name '*.java' | sort | while IFS= read -r f; do wp "$f"; echo; done > "$out/sources.txt"
"$JDKBIN/javac" -J-Xmx768m --release 8 -encoding UTF-8 -Xlint:-options -proc:none -cp "$cp_list" -d "$(wp "$out/classes")" @"$(wp "$out/sources.txt")"
cp -r "$here/src/main/resources/." "$out/classes/"
rm -f "$out/portertestkit-1.7.10-mcp.jar" "$out/portertestkit-1.7.10.jar"
"$JDKBIN/jar" --create --file "$(wp "$out/portertestkit-1.7.10-mcp.jar")" --manifest "$(wp "$here/src/main/manifest/MANIFEST.MF")" --date=1980-01-01T00:00:02Z -C "$(wp "$out/classes")" .
"$JAVA17" -Xmx768m -jar "$(wp "$ART")" --input "$(wp "$out/portertestkit-1.7.10-mcp.jar")" --output "$(wp "$out/portertestkit-1.7.10.jar")" \
  --names "$(wp "$COMPILE_DIR/mcp2srg.tsrg")" --lib "$(wp "$MCP")" --threads 1 > "$out/art.log" 2>&1 || { cat "$out/art.log"; exit 1; }
echo "built $out/portertestkit-1.7.10.jar"
# sanity check: no MCP-named Minecraft member may survive reobfuscation (would be NoSuchMethodError in production)
CONF="${FORGE_CONF:-$HOME/.gradle/caches/minecraft/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/unpacked/conf}"
cut -d, -f2 "$CONF/methods.csv" "$CONF/fields.csv" | sort -u > "$out/mcp-names.txt"
classes=$(unzip -l "$out/portertestkit-1.7.10.jar" | grep -o 'dev/porter/[^ ]*\.class' | sed 's/\.class$//; s#/#.#g')
bad=$("$JDKBIN/javap" -c -p -cp "$(wp "$out/portertestkit-1.7.10.jar")" $classes | grep -oE "(Method|Field) net/minecraft/[A-Za-z/\$]+\.[A-Za-z_0-9]+" | sort -u \
  | awk -F. '{print $NF" "$0}' | while read -r n full; do grep -qx "$n" "$out/mcp-names.txt" && echo "$full"; done || true)
[ -z "$bad" ] || { echo "unmapped MCP references after reobf:"; echo "$bad"; exit 1; }
