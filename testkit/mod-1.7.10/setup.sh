#!/usr/bin/env bash
# One-time setup of the 1.7.10 test-mod toolchain without a second RetroFuturaGradle workspace:
# reuses the MCP-named, Forge-patched Minecraft that RFG already decompiled for any 1.7.10 RFG project
# (build/rfg/recompiled_minecraft-1.7.10.jar) and builds an MCP -> SRG mapping for reobfuscation.
#
# Env: JAVA17, RFG_RECOMPILED (recompiled_minecraft-1.7.10.jar), FORGE_CONF (forge 1614 unpacked/conf with
#      packaged.srg), MCP_CSV (fields.csv, methods.csv; default: the FML conf next to packaged.srg), COMPILE_DIR (output)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
CACHE="${PORTER_CACHE:-$HOME/.cache/porter}"
JAVA17="${JAVA17:-java}"
RFG_RECOMPILED="${RFG_RECOMPILED:?set RFG_RECOMPILED to an RFG build/rfg/recompiled_minecraft-1.7.10.jar}"
FORGE_CONF="${FORGE_CONF:-$HOME/.gradle/caches/minecraft/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/unpacked/conf}"
MCP_CSV="${MCP_CSV:-$FORGE_CONF}"  # FML conf csvs = the names RFG (remoteMappings) uses, not plain stable_12
COMPILE_DIR="${COMPILE_DIR:-$CACHE/forge-1.7.10-compile}"
mkdir -p "$COMPILE_DIR"
cp "$RFG_RECOMPILED" "$COMPILE_DIR/mc-forge-1.7.10-mcp.jar"
"$JAVA17" -Xmx512m "$here/tools/MakeMcpToSrg.java" "$FORGE_CONF/packaged.srg" "$MCP_CSV/fields.csv" "$MCP_CSV/methods.csv" "$COMPILE_DIR/mcp2srg.tsrg"
echo "compile dir: $COMPILE_DIR"
