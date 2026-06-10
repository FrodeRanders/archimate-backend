#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
FAILED=0

assert_file() {
  local rel="$1"
  if [[ ! -f "$ROOT_DIR/$rel" ]]; then
    echo "[crdt-hardening] missing file: $rel" >&2
    FAILED=1
  fi
}

assert_grep() {
  local pattern="$1"
  local rel="$2"
  if ! rg -q "$pattern" "$ROOT_DIR/$rel"; then
    echo "[crdt-hardening] expected pattern '$pattern' not found in $rel" >&2
    FAILED=1
  fi
}

# Core CRDT tests and local gate assets that should not silently disappear.
assert_file "scripts/crdt-gate.sh"
assert_file "scripts/check-crdt-hardening.sh"
assert_file "scripts/check-notation-parity.sh"
assert_file "scripts/validate-local.sh"
assert_file "client/client-tests/src/test/java/org/gautelis/archimesh/client/OpMapperNotationInventoryTest.java"
assert_file "client/client-tests/src/test/java/org/gautelis/archimesh/client/OpMapperConnectionBatchTest.java"
assert_file "client/client-tests/src/test/java/org/gautelis/archimesh/client/CrdtEntityMergeTest.java"
assert_file "client/client-tests/src/test/java/org/gautelis/archimesh/client/CrdtPropertyMergeTest.java"
assert_file "client/client-tests/src/test/java/org/gautelis/archimesh/client/CrdtOrSetTest.java"
assert_file "client/client-tests/src/test/java/org/gautelis/archimesh/client/ArchimeshAuthHintsTest.java"
assert_file "client/client-tests/src/test/java/org/gautelis/archimesh/client/ArchimeshSessionManagerOutboxTest.java"
assert_file "client/client-tests/src/test/java/org/gautelis/archimesh/client/FolderSyncClientTest.java"
assert_file "server/src/test/java/org/gautelis/archimesh/service/ArchimeshServiceTest.java"
assert_file "server/src/test/java/org/gautelis/archimesh/service/impl/LocalInfraIntegrationTest.java"
assert_file "server/src/test/java/org/gautelis/archimesh/service/impl/CrdtOrSetTest.java"

# Ensure the spec continues to reference the supported local gate assets.
assert_grep 'scripts/crdt-gate.sh' "crdt-spec.md"
assert_grep 'scripts/validate-local.sh' "README.md"

if [[ "$FAILED" -eq 0 ]]; then
  "$ROOT_DIR/scripts/check-notation-parity.sh"
fi

if [[ "$FAILED" -ne 0 ]]; then
  echo "[crdt-hardening] FAILED" >&2
  exit 1
fi

echo "[crdt-hardening] PASS"
