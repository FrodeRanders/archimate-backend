#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-gate}"

case "$MODE" in
  fast)
    exec "$ROOT_DIR/scripts/check-crdt-hardening.sh"
    ;;
  gate)
    exec "$ROOT_DIR/scripts/crdt-gate.sh"
    ;;
  infra)
    exec env RUN_LOCAL_INFRA_IT=true "$ROOT_DIR/scripts/crdt-gate.sh"
    ;;
  ws)
    exec env RUN_LOCAL_INFRA_IT=true RUN_WS_E2E_IT=true "$ROOT_DIR/scripts/crdt-gate.sh"
    ;;
  *)
    cat >&2 <<'EOF'
usage: scripts/validate-local.sh [fast|gate|infra|ws]

  fast   hardening and notation parity checks only
  gate   default client + server CRDT gate
  infra  gate plus local Kafka/Neo4j-backed integration tests
  ws     gate plus local Kafka/Neo4j integration and websocket E2E tests
EOF
    exit 2
    ;;
esac
