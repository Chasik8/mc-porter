#!/usr/bin/env bash
# One-time setup of the 1.19.2 test-mod toolchain WITHOUT ForgeGradle (no decompile step, ~1 min, <1.5 GB heap).
#
#   1. Forge 1.19.2 dedicated server installed by the official installer (also used to run scenarios):
#        java -jar forge-1.19.2-43.5.2-installer.jar --installServer $FORGE_SERVER
#   2. mojmap -> srg member mapping from Mojang's client.txt + MCPConfig joined.tsrg (tools/MakeMojToSrg.java)
#   3. mojmap-named compile jar = SRG server jar + Forge binpatched classes, renamed with ForgeAutoRenamingTool --reverse
#
# Env (defaults = this machine, see testkit/README.md):
#   JAVA17         JDK 17 java
#   FORGE_SERVER   Forge server install dir (has libraries/)
#   CLIENT_TXT     Mojang 1.19.2 client mappings (client.txt)
#   JOINED_TSRG    MCPConfig 1.19.2-20220805.130853 config/joined.tsrg
#   COMPILE_DIR    output dir (moj2srg.tsrg, mc-forge-1.19.2-mojmap.jar)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
CACHE="${PORTER_CACHE:-$HOME/.cache/porter}"
JAVA17="${JAVA17:-java}"
FORGE_SERVER="${FORGE_SERVER:-$CACHE/forge-1.19.2-server}"
CLIENT_TXT="${CLIENT_TXT:-$CACHE/mappings-1.19.2/client_mappings/client.txt}"
JOINED_TSRG="${JOINED_TSRG:-$CACHE/mcpconfig-1.19.2/x/config/joined.tsrg}"
COMPILE_DIR="${COMPILE_DIR:-$CACHE/forge-1.19.2-compile}"
JAR="${JAR:-$(dirname "$JAVA17")/jar}"

L="$FORGE_SERVER/libraries"
SRG_JAR="$L/net/minecraft/server/1.19.2-20220805.130853/server-1.19.2-20220805.130853-srg.jar"
PATCHED="$L/net/minecraftforge/forge/1.19.2-43.5.2/forge-1.19.2-43.5.2-server.jar"
ART="$L/net/minecraftforge/ForgeAutoRenamingTool/0.1.22/ForgeAutoRenamingTool-0.1.22-all.jar"
for f in "$SRG_JAR" "$PATCHED" "$ART" "$CLIENT_TXT" "$JOINED_TSRG"; do
  [ -f "$f" ] || { echo "missing $f (install the Forge 1.19.2 server first, see README)"; exit 2; }
done
mkdir -p "$COMPILE_DIR"
ASM="$L/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar"
work="$COMPILE_DIR/merge"
rm -rf "$work" && mkdir -p "$work"
(cd "$work" && "$JAR" xf "$SRG_JAR" && "$JAR" xf "$PATCHED" && rm -rf META-INF)
rm -f "$COMPILE_DIR/mc-forge-1.19.2-srg.jar"
"$JAR" cf "$COMPILE_DIR/mc-forge-1.19.2-srg.jar" -C "$work" .
rm -rf "$work"
"$JAVA17" -Xmx1g -cp "$ASM" "$here/tools/MakeMojToSrg.java" "$CLIENT_TXT" "$JOINED_TSRG" "$COMPILE_DIR/moj2srg.tsrg" "$COMPILE_DIR/mc-forge-1.19.2-srg.jar"
"$JAVA17" -Xmx1200m -jar "$ART" --input "$COMPILE_DIR/mc-forge-1.19.2-srg.jar" --output "$COMPILE_DIR/mc-forge-1.19.2-mojmap.jar" \
  --names "$COMPILE_DIR/moj2srg.tsrg" --reverse --threads 2 --log "$COMPILE_DIR/art-reverse.log"
rm -f "$COMPILE_DIR/mc-forge-1.19.2-srg.jar"
echo "compile jar: $COMPILE_DIR/mc-forge-1.19.2-mojmap.jar"
