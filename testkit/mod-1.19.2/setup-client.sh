#!/usr/bin/env bash
# One-time setup of a Forge 1.19.2 CLIENT for GUI scenarios (side: client) and of the client compile jar.
#
#   1. launcher-style install dir: versions/1.19.2 (vanilla json + client jar, from the cache when present),
#      vanilla libraries (tools/FetchClientLibs.java: sha1-checked, reused from the server install when equal),
#      Forge via the official installer --installClient (patched client, forge libraries).
#      No assets are installed: the game runs without sounds and non-English languages (en_us is in the jar).
#   2. client compile jar: SRG client + Forge client patches renamed to mojmap with ForgeAutoRenamingTool --reverse
#      (same recipe as setup.sh, client side), plus a client-side moj2srg mapping for reobfuscation.
#
# Run through the heavy-job queue (installer processors and ART take ~1-2 min, < 1.5 GB heap).
# Env: JAVA17, PORTER_CACHE, CLIENT_DIR (default $CACHE/forge-1.19.2-client), COMPILE_DIR, MOJANG (vanilla cache dir
# holding 1.19.2.json + client.jar, optional), CLIENT_TXT, JOINED_TSRG.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
CACHE="${PORTER_CACHE:-$HOME/.cache/porter}"
JAVA17="${JAVA17:-java}"
JAR="${JAR:-$(dirname "$JAVA17")/jar}"
CLIENT_DIR="${CLIENT_DIR:-$CACHE/forge-1.19.2-client}"
COMPILE_DIR="${COMPILE_DIR:-$CACHE/forge-1.19.2-compile}"
MOJANG="${MOJANG:-$CACHE/mojang-1.19.2}"
INSTALLER="${INSTALLER:-$CACHE/forge-1.19.2-installer/forge-1.19.2-43.5.2-installer.jar}"
FORGE_SERVER="${FORGE_SERVER:-$CACHE/forge-1.19.2-server}"
CLIENT_TXT="${CLIENT_TXT:-$CACHE/mappings-1.19.2/client_mappings/client.txt}"
JOINED_TSRG="${JOINED_TSRG:-$CACHE/mcpconfig-1.19.2/x/config/joined.tsrg}"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) wp() { cygpath -m "$1"; } ;; *) wp() { printf '%s' "$1"; } ;; esac

mkdir -p "$CLIENT_DIR/versions/1.19.2"
[ -f "$CLIENT_DIR/launcher_profiles.json" ] || echo '{"profiles":{}}' > "$CLIENT_DIR/launcher_profiles.json"
V="$CLIENT_DIR/versions/1.19.2"
if [ ! -f "$V/1.19.2.jar" ]; then
  if [ -f "$MOJANG/client.jar" ]; then cp "$MOJANG/1.19.2.json" "$V/1.19.2.json"; cp "$MOJANG/client.jar" "$V/1.19.2.jar"
  else echo "put the vanilla 1.19.2.json + client jar into $V (or set MOJANG)"; exit 2; fi
fi
"$JAVA17" "$(wp "$here/tools/FetchClientLibs.java")" "$(wp "$V/1.19.2.json")" "$(wp "$CLIENT_DIR/libraries")" "$(wp "$FORGE_SERVER/libraries")"
if [ ! -f "$CLIENT_DIR/versions/1.19.2-forge-43.5.2/1.19.2-forge-43.5.2.json" ]; then
  (cd "$CLIENT_DIR" && "$JAVA17" -Xmx1g -jar "$(wp "$INSTALLER")" --installClient "$(wp "$CLIENT_DIR")" > install-client.log 2>&1) \
    || { tail -30 "$CLIENT_DIR/install-client.log"; exit 1; }
fi

L="$CLIENT_DIR/libraries"
SRG_JAR="$L/net/minecraft/client/1.19.2-20220805.130853/client-1.19.2-20220805.130853-srg.jar"
PATCHED="$L/net/minecraftforge/forge/1.19.2-43.5.2/forge-1.19.2-43.5.2-client.jar"
ART="$FORGE_SERVER/libraries/net/minecraftforge/ForgeAutoRenamingTool/0.1.22/ForgeAutoRenamingTool-0.1.22-all.jar"
ASM="$FORGE_SERVER/libraries/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar"
for f in "$SRG_JAR" "$PATCHED" "$ART" "$CLIENT_TXT" "$JOINED_TSRG"; do [ -f "$f" ] || { echo "missing $f"; exit 2; }; done
OUT="$COMPILE_DIR/mc-forge-1.19.2-client-mojmap.jar"
if [ ! -f "$OUT" ]; then
  work="$COMPILE_DIR/merge-client"
  rm -rf "$work" && mkdir -p "$work"
  (cd "$work" && "$JAR" xf "$(wp "$SRG_JAR")" && "$JAR" xf "$(wp "$PATCHED")" && rm -rf META-INF)
  rm -f "$COMPILE_DIR/mc-forge-1.19.2-client-srg.jar"
  "$JAR" cf "$(wp "$COMPILE_DIR/mc-forge-1.19.2-client-srg.jar")" -C "$(wp "$work")" .
  rm -rf "$work"
  "$JAVA17" -Xmx1g -cp "$(wp "$ASM")" "$(wp "$here/tools/MakeMojToSrg.java")" "$(wp "$CLIENT_TXT")" "$(wp "$JOINED_TSRG")" \
    "$(wp "$COMPILE_DIR/moj2srg-client.tsrg")" "$(wp "$COMPILE_DIR/mc-forge-1.19.2-client-srg.jar")"
  "$JAVA17" -Xmx1200m -jar "$(wp "$ART")" --input "$(wp "$COMPILE_DIR/mc-forge-1.19.2-client-srg.jar")" --output "$(wp "$OUT")" \
    --names "$(wp "$COMPILE_DIR/moj2srg-client.tsrg")" --reverse --threads 2 --log "$(wp "$COMPILE_DIR/art-reverse-client.log")"
  rm -f "$COMPILE_DIR/mc-forge-1.19.2-client-srg.jar"
fi
echo "client install: $CLIENT_DIR"
echo "client compile jar: $OUT"
