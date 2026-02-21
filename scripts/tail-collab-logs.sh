#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

SERVER_LOG_DEFAULT="${ROOT_DIR}/.run/collab-server.log"
WS1_DEFAULT="${HOME}/Archi/workspace-1/.metadata/.log"
WS2_DEFAULT="${HOME}/Archi/workspace-2/.metadata/.log"

SERVER_LOG="${SERVER_LOG:-${SERVER_LOG_DEFAULT}}"
WS1_LOG="${WS1_LOG:-${WS1_DEFAULT}}"
WS2_LOG="${WS2_LOG:-${WS2_DEFAULT}}"
NO_SERVER=0
NO_WS2=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --server-log <path>   Server log file (default: ${SERVER_LOG_DEFAULT})
  --ws1-log <path>      Workspace 1 Archi log (default: ${WS1_DEFAULT})
  --ws2-log <path>      Workspace 2 Archi log (default: ${WS2_DEFAULT})
  --no-server           Do not tail server log
  --no-ws2              Do not tail workspace 2 log
  -h, --help            Show this help

Env overrides:
  SERVER_LOG, WS1_LOG, WS2_LOG

Notes:
  - This tails files with 'tail -F' (follows across log rotation/recreate).
  - For extra plugin debug logs, launch Archi with:
      ARCHI_COLLAB_DEBUG=true open -n -a "/Applications/Archi.app" --args ...
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --server-log)
      SERVER_LOG="$2"
      shift 2
      ;;
    --ws1-log)
      WS1_LOG="$2"
      shift 2
      ;;
    --ws2-log)
      WS2_LOG="$2"
      shift 2
      ;;
    --no-server)
      NO_SERVER=1
      shift
      ;;
    --no-ws2)
      NO_WS2=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

tail_with_prefix() {
  local label="$1"
  local file="$2"
  if [[ ! -e "${file}" ]]; then
    echo "[warn] ${label} log file not found yet: ${file}" >&2
  fi
  tail -n 0 -F "${file}" | sed -u "s/^/[${label}] /"
}

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do
    kill "${pid}" 2>/dev/null || true
  done
}
trap cleanup EXIT INT TERM

echo "[info] Tailing collaboration logs..."
echo "[info] WS1: ${WS1_LOG}"
if [[ "${NO_WS2}" -eq 0 ]]; then
  echo "[info] WS2: ${WS2_LOG}"
fi
if [[ "${NO_SERVER}" -eq 0 ]]; then
  echo "[info] SERVER: ${SERVER_LOG}"
fi

tail_with_prefix "WS1" "${WS1_LOG}" &
PIDS+=("$!")

if [[ "${NO_WS2}" -eq 0 ]]; then
  tail_with_prefix "WS2" "${WS2_LOG}" &
  PIDS+=("$!")
fi

if [[ "${NO_SERVER}" -eq 0 ]]; then
  tail_with_prefix "SERVER" "${SERVER_LOG}" &
  PIDS+=("$!")
fi

wait
