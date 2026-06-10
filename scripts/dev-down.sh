#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

PID_FILE="${ROOT_DIR}/.run/archimesh-server.pid"
MODE_FILE="${ROOT_DIR}/.run/archimesh-server.mode"

stop_pid() {
  local pid="$1"
  if [[ -z "${pid}" ]]; then
    return
  fi
  if kill -0 "${pid}" >/dev/null 2>&1; then
    echo "Stopping Archimesh server PID ${pid}"
    kill "${pid}" >/dev/null 2>&1 || true
    sleep 1
    if kill -0 "${pid}" >/dev/null 2>&1; then
      echo "Force stopping Archimesh server PID ${pid}"
      kill -9 "${pid}" >/dev/null 2>&1 || true
    fi
  fi
}

if [[ -f "${PID_FILE}" ]]; then
  PID="$(cat "${PID_FILE}")"
  stop_pid "${PID}"
fi

echo "Stopping any remaining Archimesh server dev processes"
mapfile -t MATCHED_PIDS < <(
  pgrep -f "archimesh-server-dev\\.jar|quarkus:dev.*server/pom\\.xml" || true
)
for pid in "${MATCHED_PIDS[@]}"; do
  stop_pid "${pid}"
done

rm -f "${PID_FILE}" "${MODE_FILE}"

echo "Stopping containers"
docker compose -f "${ROOT_DIR}/docker-compose.yml" down
