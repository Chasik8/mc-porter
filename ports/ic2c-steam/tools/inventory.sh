#!/usr/bin/env bash
# LEGAL-INVENTORY.md of this profile, generated: porter inventory over the profile files, overlap measured against the
# player's own decompiles of IC2 Classic (the derive/setup output of a profile run).
# Usage: tools/inventory.sh <run dir of a profile run> [profile dir (default: this one)] [out.md (default: <profile>/LEGAL-INVENTORY.md)]
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
RUN=$1; PROFILE=${2:-$here}; OUT=${3:-$PROFILE/LEGAL-INVENTORY.md}
porter=${PORTER:-porter}
"$porter" inventory --root "$PROFILE" --exclude run/ --exclude tools/ \
  --ref "$RUN/derived/ic2c-1192/java" --ref "$RUN/derived/ic2c-1710/java" --lang-ref "$RUN/derived/ic2c-1192/res/assets/ic2/lang" \
  --relocate ic2.=ic2cextras.ported. --names '\bic2\b|\bIC2\w*|\bic2\.|"ic2:' \
  --category 'patches/=C adapted (blind patches: new lines only)' \
  --category 'rules/=A configuration / rules / data referencing IC2C names' --category 'edits/=A configuration / rules / data referencing IC2C names' \
  --category 'select/=A configuration / rules / data referencing IC2C names' --category 'scenarios/=A configuration / rules / data referencing IC2C names' \
  --category 'ic2c-steam.port.yaml=A configuration / rules / data referencing IC2C names' \
  --category 'extract-pack/=B hand-written interop code' --category 'overlays/=B hand-written interop code' \
  --category 'project/=B hand-written project files' \
  --out "${OUT%.md}.tsv" --markdown "$OUT" --head "$here/tools/inventory-head.md"
