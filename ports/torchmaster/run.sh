#!/usr/bin/env bash
# Ports Torchmaster to Minecraft 1.7.10 with one command. See README.md in this folder.
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$here/../../scripts/run-port.sh" "$here" "$@"
