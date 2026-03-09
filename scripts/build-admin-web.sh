#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT_DIR/admin-web"
OUT_DIR="$ROOT_DIR/server/src/main/resources/META-INF/resources/admin-ui"

if ! command -v npm >/dev/null 2>&1; then
  echo "npm is required to build admin-web" >&2
  exit 1
fi

cd "$APP_DIR"
npm install
rm -rf "$OUT_DIR"
npm run build

echo "Admin web build succeeded: $OUT_DIR"
