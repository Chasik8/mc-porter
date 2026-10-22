#!/usr/bin/env bash
# Builds the IC2 Classic Extras addon from your own two IC2 Classic jars. See README.md in this folder.
# Usage: run.sh IC2Classic-1.19.2-2.1.3.4.jar IC2Classic-1.7.10-1.2.6.jar
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ $# -eq 2 ]; then
  exec "$here/../../scripts/run-port.sh" "$here" --input "ic2c-1192=$(readlink -f "$1")" --input "ic2c-1710=$(readlink -f "$2")"
fi
exec "$here/../../scripts/run-port.sh" "$here" "$@"
