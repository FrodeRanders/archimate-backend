#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARCHI_DIR="${ARCHI_DIR:-$ROOT_DIR/../archi}"
SRC_DIR="$ROOT_DIR/client/com.archimatetool.collab"
DST_DIR="$ARCHI_DIR/com.archimatetool.collab"

usage() {
  cat <<USAGE
Usage: $0 <to-archi|from-archi>

  to-archi    Sync client source from this repo to ../archi
  from-archi  Sync client source from ../archi back to this repo

Optional env:
  ARCHI_DIR=/custom/path/to/archi
USAGE
}

if [[ $# -ne 1 ]]; then
  usage
  exit 1
fi

if [[ ! -d "$ARCHI_DIR" ]]; then
  echo "Archi directory not found: $ARCHI_DIR" >&2
  exit 1
fi

case "$1" in
  to-archi)
    mkdir -p "$DST_DIR"
    rsync -a --delete "$SRC_DIR/" "$DST_DIR/"
    echo "Synced to Archi: $SRC_DIR -> $DST_DIR"
    ;;
  from-archi)
    mkdir -p "$SRC_DIR"
    rsync -a --delete "$DST_DIR/" "$SRC_DIR/"
    echo "Synced from Archi: $DST_DIR -> $SRC_DIR"
    ;;
  *)
    usage
    exit 1
    ;;
esac
