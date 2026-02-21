#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

PID_FILE="${ROOT_DIR}/.run/collab-server.pid"

if [[ -f "${PID_FILE}" ]]; then
  PID="$(cat "${PID_FILE}")"
  if kill -0 "${PID}" >/dev/null 2>&1; then
    echo "Stopping collab server PID ${PID}"
    kill "${PID}" || true
  fi
  rm -f "${PID_FILE}"
fi

echo "Stopping containers"
docker compose -f "${ROOT_DIR}/docker-compose.yml" down
