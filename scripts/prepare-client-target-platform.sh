#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARCHI_DIR="${ARCHI_DIR:-$ROOT_DIR/../archi}"
TP_DIR="$ROOT_DIR/client/target-platform/plugins"

mkdir -p "$TP_DIR"

copy_bundle() {
  local src="$1"
  if [[ ! -f "$src" ]]; then
    echo "Missing required bundle: $src" >&2
    exit 1
  fi
  cp "$src" "$TP_DIR/"
}

# Core Archi bundles required by com.archimatetool.collab MANIFEST.MF
copy_bundle "$ARCHI_DIR/com.archimatetool.model/com.archimatetool.model.jar"
copy_bundle "$ARCHI_DIR/com.archimatetool.editor/com.archimatetool.editor.jar"

echo "Prepared local bundle pool in $TP_DIR"
