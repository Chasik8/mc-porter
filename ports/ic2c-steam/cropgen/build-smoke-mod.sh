#!/usr/bin/env bash
# Builds a standalone 1.7.10 test mod from cropgen output, so the generated crops can be tested (testkit scenarios)
# without a port: javac against the MCP-named Minecraft+Forge jar and the host IC2 Classic jar, a tiny @Mod class
# that calls <package>.MoreCrops.register() in postInit, then reobfuscation MCP -> SRG (same tool as the testkit mod).
#
#   build-smoke-mod.sh <cropgen out dir> <host IC2 Classic jar> <package> <out jar>
#
# Env: PORTER_CACHE (holds forge-1.7.10-compile and forge-1.19.2-server), JAVA17, JAVAC8 (a JDK 8 javac).
set -euo pipefail
gen="$1"; host="$2"; pkg="$3"; out="$4"
CACHE="${PORTER_CACHE:-$HOME/.cache/porter}"
JAVA17="${JAVA17:-java}"
JAVAC8="${JAVAC8:-javac}"
COMPILE_DIR="$CACHE/forge-1.7.10-compile"
ART="${ART:-$CACHE/forge-1.19.2-server/libraries/net/minecraftforge/ForgeAutoRenamingTool/0.1.22/ForgeAutoRenamingTool-0.1.22-all.jar}"
MCP="$COMPILE_DIR/mc-forge-1.7.10-mcp.jar"
JDKBIN="$(dirname "$JAVA17")"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';'; wp() { cygpath -m "$1"; } ;; *) SEP=':'; wp() { printf '%s' "$1"; } ;; esac
work="$(dirname "$out")/smoke-work"
rm -rf "$work" && mkdir -p "$work/classes" "$work/src/cropgen/smoke"
cat > "$work/src/cropgen/smoke/SmokeMod.java" <<EOF
package cropgen.smoke;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;

@Mod(modid = "cropgensmoke", name = "cropgen smoke", version = "1", dependencies = "after:IC2")
public class SmokeMod {
    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent e) {
        ${pkg}.MoreCrops.register();
    }
}
EOF
find "$gen/overlay" -name '*.java' | sort | while IFS= read -r f; do wp "$f"; done > "$work/sources.txt"
wp "$work/src/cropgen/smoke/SmokeMod.java" >> "$work/sources.txt"
"$JAVAC8" -nowarn -encoding UTF-8 -cp "$(wp "$MCP")$SEP$(wp "$host")" -d "$(wp "$work/classes")" @"$(wp "$work/sources.txt")"
[ -d "$gen/resources" ] && cp -r "$gen/resources/." "$work/classes/"
cat > "$work/classes/mcmod.info" <<EOF
[{"modid": "cropgensmoke", "name": "cropgen smoke", "version": "1", "description": "generated crops smoke mod"}]
EOF
mkdir -p "$(dirname "$out")"
"$JDKBIN/jar" --create --file "$(wp "$work/smoke-mcp.jar")" --date=1980-01-01T00:00:02Z -C "$(wp "$work/classes")" .
"$JAVA17" -Xmx768m -jar "$(wp "$ART")" --input "$(wp "$work/smoke-mcp.jar")" --output "$(wp "$out")" \
  --names "$(wp "$COMPILE_DIR/mcp2srg.tsrg")" --lib "$(wp "$MCP")" --lib "$(wp "$host")" --threads 1 > "$work/art.log" 2>&1 || { cat "$work/art.log"; exit 1; }
echo "built $out"
