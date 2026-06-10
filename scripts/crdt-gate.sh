#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_LOCAL_INFRA_IT="${RUN_LOCAL_INFRA_IT:-false}"

SERVER_TESTS="ArchimeshServiceTest"
CLIENT_TESTS="org.gautelis.archimesh.client.OpMapperNotationInventoryTest,org.gautelis.archimesh.client.OpMapperConnectionBatchTest,org.gautelis.archimesh.client.CrdtEntityMergeTest,org.gautelis.archimesh.client.CrdtPropertyMergeTest,org.gautelis.archimesh.client.CrdtOrSetTest"

if [[ "$RUN_LOCAL_INFRA_IT" == "true" ]]; then
  SERVER_TESTS="$SERVER_TESTS,LocalInfraIntegrationTest"
fi

echo "[crdt-gate] Running hardening contract checks"
"$ROOT_DIR/scripts/check-crdt-hardening.sh"

echo "[crdt-gate] Preparing client target platform bundles"
"$ROOT_DIR/scripts/prepare-client-target-platform.sh"

echo "[crdt-gate] Installing client plugin artifact (required by Archimesh client-tests)"
(
  cd "$ROOT_DIR/client"
  mvn -q -pl org.gautelis.archimesh.plugin -am install -DskipTests
)

echo "[crdt-gate] Running client CRDT tests"
(
  cd "$ROOT_DIR/client/client-tests"
  mvn -q -Dtest="$CLIENT_TESTS" test
)

echo "[crdt-gate] Running server CRDT tests"
(
  cd "$ROOT_DIR/server"
  RUN_LOCAL_INFRA_IT="$RUN_LOCAL_INFRA_IT" mvn -q -Dtest="$SERVER_TESTS" test
)

echo "[crdt-gate] PASS"
