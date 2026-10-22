#!/usr/bin/env bash
# CI: installs what the server scenarios need and writes the testkit environment file.
#   scripts/ci/testkit-setup.sh <port-run-dir>
# Run it AFTER `porter run` of any port: that run leaves the pieces this reuses ($PORTER_CACHE: Mojang mappings,
# MCPConfig, the Forge 1.19.2 client install; <port-run-dir>/project: the RFG-decompiled 1.7.10 Minecraft).
# Result (cached by the workflow under $PORTER_HOME/testkit, keyed by the hash of testkit/ + this script):
#   $PORTER_HOME/testkit/forge-1.19.2-server, forge-1.7.10-server, the two test-mod jars, env.properties
# Needs: JAVA_HOME_8_X64, JAVA_HOME_17_X64 (actions/setup-java with java-version 8, 17, 21), curl, sha1sum.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
run_dir="${1:?port run dir (\$PORTER_HOME/runs/<name>)}"
PORTER_HOME="${PORTER_HOME:-$HOME/.porter}"
export PORTER_CACHE="${PORTER_CACHE:-$PORTER_HOME/cache}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PORTER_HOME/gradle}"
T="$PORTER_HOME/testkit"
JAVA8="${JAVA_HOME_8_X64:?JAVA_HOME_8_X64 (setup-java 8)}/bin/java"
JAVA17="${JAVA_HOME_17_X64:?JAVA_HOME_17_X64 (setup-java 17)}/bin/java"
mkdir -p "$T/downloads"

fetch() { # url sha1 file
  local f="$T/downloads/$3"
  if [ ! -f "$f" ] || [ "$(sha1sum "$f" | cut -d' ' -f1)" != "$2" ]; then
    curl -fL --retry 3 -o "$f.part" "$1"
    [ "$(sha1sum "$f.part" | cut -d' ' -f1)" = "$2" ] || { echo "::error::checksum mismatch for $1"; exit 1; }
    mv "$f.part" "$f"
  fi
}

# --- servers (pinned: sha1 as published next to the files on maven.minecraftforge.net / launcher.mojang.com)
if [ ! -f "$T/stamps/servers" ]; then
  fetch https://maven.minecraftforge.net/net/minecraftforge/forge/1.19.2-43.5.2/forge-1.19.2-43.5.2-installer.jar \
        d242b6786039d4acb9ea7579624772b6809bda91 forge-1.19.2-43.5.2-installer.jar
  fetch https://maven.minecraftforge.net/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10-installer.jar \
        fccafccf8ad4ce6d9f008e786b48ff53172bf9de forge-1.7.10-10.13.4.1614-1.7.10-installer.jar
  fetch https://launcher.mojang.com/v1/objects/952438ac4e01b4d115c5fc38f891710c4941df29/server.jar \
        952438ac4e01b4d115c5fc38f891710c4941df29 minecraft_server.1.7.10.jar
  rm -rf "$T/forge-1.19.2-server" "$T/forge-1.7.10-server"
  mkdir -p "$T/forge-1.19.2-server" "$T/forge-1.7.10-server"
  "$JAVA17" -jar "$T/downloads/forge-1.19.2-43.5.2-installer.jar" --installServer "$T/forge-1.19.2-server" > "$T/install-1.19.2.log" 2>&1 \
    || { tail -30 "$T/install-1.19.2.log"; exit 1; }
  cp "$T/downloads/minecraft_server.1.7.10.jar" "$T/forge-1.7.10-server/minecraft_server.1.7.10.jar"
  (cd "$T/forge-1.7.10-server" && "$JAVA8" -jar "$T/downloads/forge-1.7.10-10.13.4.1614-1.7.10-installer.jar" --installServer) \
    > "$T/install-1.7.10.log" 2>&1 || { tail -30 "$T/install-1.7.10.log"; exit 1; }
  [ -f "$T/forge-1.7.10-server/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar" ] || { tail -30 "$T/install-1.7.10.log"; echo "::error::1.7.10 server install incomplete"; exit 1; }
  mkdir -p "$T/stamps" && touch "$T/stamps/servers"
fi

# --- test mods
if [ ! -f "$T/stamps/testmods" ]; then
  export JAVA17 FORGE_SERVER="$T/forge-1.19.2-server"
  # the porter setup already left the mappings and MCPConfig in $PORTER_CACHE
  export CLIENT_TXT="$PORTER_CACHE/mappings-1.19.2/client_mappings/client.txt"
  export JOINED_TSRG="$PORTER_CACHE/mcpconfig-1.19.2/x/config/joined.tsrg"
  export COMPILE_DIR="$T/compile-1.19.2"
  "$repo/testkit/mod-1.19.2/setup.sh"
  COMPILE_DIR="$T/compile-1.19.2" "$repo/testkit/mod-1.19.2/build.sh"
  rfg="$(find "$run_dir/project/build" -name 'recompiled_minecraft-1.7.10.jar' | head -1)"
  [ -n "$rfg" ] || { echo "::error::no recompiled_minecraft-1.7.10.jar under $run_dir/project/build (did porter run finish its setup?)"; exit 1; }
  conf="$(dirname "$(find "$GRADLE_USER_HOME" "$run_dir/project" -name packaged.srg 2>/dev/null | head -1)")"
  [ -d "$conf" ] || { echo "::error::Forge 1.7.10 conf (packaged.srg) not found under $GRADLE_USER_HOME"; exit 1; }
  export RFG_RECOMPILED="$rfg" FORGE_CONF="$conf" MCP_CSV="$conf" COMPILE_DIR="$T/compile-1.7.10"
  export FORGE_SERVER="$T/forge-1.7.10-server" ART="$T/forge-1.19.2-server/libraries/net/minecraftforge/ForgeAutoRenamingTool/0.1.22/ForgeAutoRenamingTool-0.1.22-all.jar"
  export LWJGL="$(find "$GRADLE_USER_HOME" "$HOME/.gradle" -name 'lwjgl-2*.jar' ! -name '*natives*' 2>/dev/null | head -1)"
  "$repo/testkit/mod-1.7.10/setup.sh"
  "$repo/testkit/mod-1.7.10/build.sh"
  mkdir -p "$T/mods"
  cp "$repo/testkit/mod-1.19.2/build/portertestkit-1.19.2.jar" "$repo/testkit/mod-1.7.10/build/portertestkit-1.7.10.jar" "$T/mods/"
  touch "$T/stamps/testmods"
fi

cat > "$T/env.properties" <<EOF
forge-1.19.2.java=$JAVA17
forge-1.19.2.server=$T/forge-1.19.2-server
forge-1.19.2.testmod=$T/mods/portertestkit-1.19.2.jar
forge-1.19.2.heap=1500m
forge-1.7.10.java=$JAVA8
forge-1.7.10.server=$T/forge-1.7.10-server
forge-1.7.10.testmod=$T/mods/portertestkit-1.7.10.jar
forge-1.7.10.heap=1200m
EOF
echo "testkit ready: PORTER_TESTKIT_ENV=$T/env.properties"
