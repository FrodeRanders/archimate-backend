#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

NEO4J_USER="${NEO4J_USER:-neo4j}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-devpassword}"
QUARKUS_DEBUG="${QUARKUS_DEBUG:-false}"
RUN_MODE="${1:-foreground}"

if [[ "${RUN_MODE}" != "foreground" && "${RUN_MODE}" != "background" ]]; then
  echo "Usage: $(basename "$0") [foreground|background]" >&2
  exit 1
fi

echo "[1/4] Starting Neo4j + Kafka containers"
docker compose -f "${ROOT_DIR}/docker-compose.yml" up -d

echo "[2/4] Waiting for Neo4j to become ready"
for i in {1..30}; do
  if docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T neo4j cypher-shell -u "${NEO4J_USER}" -p "${NEO4J_PASSWORD}" "RETURN 1" >/dev/null 2>&1; then
    break
  fi
  sleep 2
  if [[ "$i" -eq 30 ]]; then
    echo "Neo4j did not become ready in time" >&2
    exit 1
  fi
done

echo "[3/4] Applying Neo4j schema"
docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T neo4j \
  cypher-shell -u "${NEO4J_USER}" -p "${NEO4J_PASSWORD}" < "${ROOT_DIR}/neo4j/schema.cypher"

echo "[4/4] Starting collaboration server"
MAVEN_CMD=(mvn -Ddebug="${QUARKUS_DEBUG}" quarkus:dev -f "${ROOT_DIR}/server/pom.xml")
mkdir -p "${ROOT_DIR}/.run"
if [[ "${RUN_MODE}" == "background" ]]; then
  nohup "${MAVEN_CMD[@]}" > "${ROOT_DIR}/.run/collab-server.log" 2>&1 &
  echo $! > "${ROOT_DIR}/.run/collab-server.pid"
  echo "background" > "${ROOT_DIR}/.run/collab-server.mode"
  echo "Collab server started in background"
  echo "PID: $(cat "${ROOT_DIR}/.run/collab-server.pid")"
  echo "Logs: ${ROOT_DIR}/.run/collab-server.log"
else
  # Track the foreground process as well so scripts/dev-down.sh can stop it from another terminal.
  echo $$ > "${ROOT_DIR}/.run/collab-server.pid"
  echo "foreground" > "${ROOT_DIR}/.run/collab-server.mode"
  exec "${MAVEN_CMD[@]}"
fi
