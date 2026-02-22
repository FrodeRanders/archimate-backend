#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARCHI_DIR="${ARCHI_DIR:-$ROOT_DIR/../archi}"

"$ROOT_DIR/scripts/sync-collab-client.sh" to-archi

(
  cd "$ARCHI_DIR"
  mvn -q -pl com.archimatetool.collab -DskipTests compile
)

echo "Build succeeded in: $ARCHI_DIR/com.archimatetool.collab/target"
