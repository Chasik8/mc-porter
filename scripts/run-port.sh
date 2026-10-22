#!/usr/bin/env bash
# Starts `porter run` for one port (Linux, macOS, Git Bash). Used by ports/<mod>/run.sh:
#   scripts/run-port.sh <port-dir> [porter run options, e.g. --input original=<jar>]
# Needs Java 21 to start porter. If there is none (JAVA_HOME, PATH), a Temurin 21 JDK is downloaded once into
# $PORTER_HOME/jdk-21 (about 200 MB, checksum-verified). Everything porter fetches or builds goes under $PORTER_HOME
# (default: .porter next to this distribution); delete that directory to remove it all.
set -euo pipefail

[ $# -ge 1 ] || { echo "usage: run-port.sh <port-dir> [porter run options]" >&2; exit 2; }
PORT_DIR="$(cd "$1" && pwd)"; shift
ROOT="$(cd "$PORT_DIR/../.." && pwd)"
export PORTER_HOME="${PORTER_HOME:-$ROOT/.porter}"

if [ -x "$ROOT/bin/porter" ]; then PORTER="$ROOT/bin/porter"
elif [ -x "$ROOT/cli/build/install/porter/bin/porter" ]; then PORTER="$ROOT/cli/build/install/porter/bin/porter"
else
  echo "porter is not built here. Download the release zip from https://github.com/Chasik8/mc-porter/releases," >&2
  echo "unpack it, and run ports/<mod>/run.sh from the unpacked folder." >&2
  exit 2
fi

java_major() { # prints the major version of the java in the JDK home $1, or 0
  local j="$1/bin/java" v
  [ -x "$j" ] || { echo 0; return; }
  v="$("$j" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
  echo "${v:-0}"
}

find_java() {
  if [ -n "${JAVA_HOME:-}" ] && [ "$(java_major "$JAVA_HOME")" -ge 21 ]; then echo "$JAVA_HOME"; return; fi
  if command -v java >/dev/null 2>&1; then
    local p; p="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
    if [ "$(java_major "$p")" -ge 21 ]; then echo "$p"; return; fi
  fi
  local d
  for d in "$PORTER_HOME"/jdk-21/*/; do
    [ -d "$d" ] || continue
    d="${d%/}"
    if [ -x "$d/Contents/Home/bin/java" ]; then d="$d/Contents/Home"; fi
    if [ "$(java_major "$d")" -ge 21 ]; then echo "$d"; return; fi
  done
  return 1
}

fetch_java() {
  local os arch url sha
  case "$(uname -s)" in Linux) os=linux;; Darwin) os=mac;; *) echo "Unsupported system $(uname -s): install a JDK 21 and set JAVA_HOME." >&2; exit 2;; esac
  case "$(uname -m)" in x86_64|amd64) arch=x64;; aarch64|arm64) arch=aarch64;; *) echo "Unsupported CPU $(uname -m): install a JDK 21 and set JAVA_HOME." >&2; exit 2;; esac
  local base=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1
  case "$os-$arch" in
    linux-x64)     url=$base/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz;      sha=ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94;;
    linux-aarch64) url=$base/OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.12.1_1.tar.gz;  sha=23e37e026f12f3e706f18938ff611db3032d075b09d0879a25d06718c773e223;;
    mac-x64)       url=$base/OpenJDK21U-jdk_x64_mac_hotspot_21.0.12.1_1.tar.gz;        sha=44db0f08196daf19a47f90d13388b0c943b67663cb537f998fe29e836fa842ce;;
    mac-aarch64)   url=$base/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.tar.gz;    sha=3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998;;
  esac
  mkdir -p "$PORTER_HOME/downloads" "$PORTER_HOME/jdk-21"
  local f="$PORTER_HOME/downloads/$(basename "$url")"
  echo "No Java 21 found. Downloading a JDK 21 (about 200 MB, once) ..."
  if command -v curl >/dev/null 2>&1; then curl -fSL --progress-bar --retry 3 -o "$f.part" "$url"
  elif command -v wget >/dev/null 2>&1; then wget -O "$f.part" "$url"
  else echo "Need curl or wget to download the JDK (or install a JDK 21 and set JAVA_HOME)." >&2; exit 2; fi
  local got
  if command -v sha256sum >/dev/null 2>&1; then got="$(sha256sum "$f.part" | cut -d' ' -f1)"; else got="$(shasum -a 256 "$f.part" | cut -d' ' -f1)"; fi
  if [ "$got" != "$sha" ]; then rm -f "$f.part"; echo "The downloaded JDK has a wrong checksum ($got); it was not used." >&2; exit 2; fi
  mv "$f.part" "$f"
  tar -xzf "$f" -C "$PORTER_HOME/jdk-21"
}

JH="$(find_java || true)"
if [ -z "$JH" ]; then fetch_java; JH="$(find_java)"; fi
export JAVA_HOME="$JH"
export PATH="$JH/bin:$PATH"
cd "$ROOT"
exec "$PORTER" run "$PORT_DIR" "$@"
