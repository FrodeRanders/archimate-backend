#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

FAILED=0

extract_java_set() {
  local file="$1"
  local anchor="$2"
  awk -v anchor="$anchor" '
    index($0, anchor) > 0 { in_set=1; next }
    in_set {
      line = $0
      while(match(line, /"([^"]+)"/)) {
        token = substr(line, RSTART + 1, RLENGTH - 2)
        print token
        line = substr(line, RSTART + RLENGTH)
      }
      if($0 ~ /\);/) {
        exit
      }
    }
  ' "$file" | sort -u
}

extract_client_expected_set() {
  local file="$1"
  local method="$2"
  awk -v method="$method" '
    $0 ~ ("void " method "\\(") { in_method=1 }
    in_method && $0 ~ /Set<String> expected = Set\.of\(/ { in_set=1; next }
    in_set {
      line = $0
      while(match(line, /"([^"]+)"/)) {
        token = substr(line, RSTART + 1, RLENGTH - 2)
        print token
        line = substr(line, RSTART + RLENGTH)
      }
      if($0 ~ /\);/) {
        exit
      }
    }
  ' "$file" | sort -u
}

extract_schema_keys() {
  local definition="$1"
  jq -r ".definitions.${definition}.properties | keys[]" "$ROOT_DIR/schemas/ops.json" | sort -u
}

compare_sets() {
  local left_label="$1"
  local left_file="$2"
  local right_label="$3"
  local right_file="$4"
  local context="$5"

  local only_left="$TMP_DIR/only-left.txt"
  local only_right="$TMP_DIR/only-right.txt"

  comm -23 "$left_file" "$right_file" > "$only_left"
  comm -13 "$left_file" "$right_file" > "$only_right"

  if [[ -s "$only_left" || -s "$only_right" ]]; then
    echo "[notation-parity] mismatch: $context ($left_label vs $right_label)" >&2
    if [[ -s "$only_left" ]]; then
      echo "[notation-parity]   only in $left_label:" >&2
      sed 's/^/[notation-parity]     - /' "$only_left" >&2
    fi
    if [[ -s "$only_right" ]]; then
      echo "[notation-parity]   only in $right_label:" >&2
      sed 's/^/[notation-parity]     - /' "$only_right" >&2
    fi
    FAILED=1
  fi
}

SERVER_FILE="$ROOT_DIR/server/src/main/java/io/archi/collab/service/NotationMetadata.java"
CLIENT_TEST_FILE="$ROOT_DIR/client/collab-client-tests/src/test/java/io/archi/collab/client/OpMapperNotationInventoryTest.java"

SERVER_VIEW_KEYS="$TMP_DIR/server-view.txt"
SERVER_CONN_KEYS="$TMP_DIR/server-conn.txt"
CLIENT_VIEW_KEYS="$TMP_DIR/client-view.txt"
CLIENT_CONN_KEYS="$TMP_DIR/client-conn.txt"
SCHEMA_VIEW_KEYS="$TMP_DIR/schema-view.txt"
SCHEMA_CONN_KEYS="$TMP_DIR/schema-conn.txt"

extract_java_set "$SERVER_FILE" 'VIEW_OBJECT_FIELDS = Set.of(' > "$SERVER_VIEW_KEYS"
extract_java_set "$SERVER_FILE" 'CONNECTION_FIELDS = Set.of(' > "$SERVER_CONN_KEYS"
extract_client_expected_set "$CLIENT_TEST_FILE" 'updateViewObjectOpaqueEmitsExactlyWhitelistedNotationKeys' > "$CLIENT_VIEW_KEYS"
extract_client_expected_set "$CLIENT_TEST_FILE" 'updateConnectionOpaqueEmitsExactlyWhitelistedNotationKeys' > "$CLIENT_CONN_KEYS"
extract_schema_keys "ViewObjectNotationJson" > "$SCHEMA_VIEW_KEYS"
extract_schema_keys "ConnectionNotationJson" > "$SCHEMA_CONN_KEYS"

compare_sets "schema" "$SCHEMA_VIEW_KEYS" "server" "$SERVER_VIEW_KEYS" "ViewObjectNotationJson keys"
compare_sets "schema" "$SCHEMA_VIEW_KEYS" "client-test" "$CLIENT_VIEW_KEYS" "ViewObjectNotationJson keys"
compare_sets "server" "$SERVER_VIEW_KEYS" "client-test" "$CLIENT_VIEW_KEYS" "ViewObjectNotationJson keys"

compare_sets "schema" "$SCHEMA_CONN_KEYS" "server" "$SERVER_CONN_KEYS" "ConnectionNotationJson keys"
compare_sets "schema" "$SCHEMA_CONN_KEYS" "client-test" "$CLIENT_CONN_KEYS" "ConnectionNotationJson keys"
compare_sets "server" "$SERVER_CONN_KEYS" "client-test" "$CLIENT_CONN_KEYS" "ConnectionNotationJson keys"

if [[ "$FAILED" -ne 0 ]]; then
  echo "[notation-parity] FAILED" >&2
  exit 1
fi

echo "[notation-parity] PASS"
