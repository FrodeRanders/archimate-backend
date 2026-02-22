#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

COMBINED_LOG=""
SERVER_LOG="${ROOT_DIR}/.run/collab-server.log"
WS1_LOG="${HOME}/Archi/workspace-1/.metadata/.log"
WS2_LOG="${HOME}/Archi/workspace-2/.metadata/.log"
TAIL_LINES=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --combined-log <path>  Analyze one combined log file (for example ./log.log)
  --server-log <path>    Analyze server log (default: ${SERVER_LOG})
  --ws1-log <path>       Analyze workspace-1 log (default: ${WS1_LOG})
  --ws2-log <path>       Analyze workspace-2 log (default: ${WS2_LOG})
  --tail-lines <n>       Analyze only the last n lines from each file (0 = all)
  -h, --help             Show this help

Examples:
  $(basename "$0") --combined-log ./log.log
  $(basename "$0") --server-log ./.run/collab-server.log
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --combined-log)
      COMBINED_LOG="$2"
      shift 2
      ;;
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
    --tail-lines)
      TAIL_LINES="$2"
      shift 2
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

log_sources=()
if [[ -n "${COMBINED_LOG}" ]]; then
  log_sources+=("${COMBINED_LOG}")
else
  log_sources+=("${SERVER_LOG}" "${WS1_LOG}" "${WS2_LOG}")
fi

existing_sources=()
for file in "${log_sources[@]}"; do
  if [[ -f "${file}" ]]; then
    existing_sources+=("${file}")
  else
    echo "[warn] log file not found: ${file}"
  fi
done

if [[ ${#existing_sources[@]} -eq 0 ]]; then
  echo "[error] no readable log files were found"
  exit 2
fi

tmp_file="$(mktemp)"
trap 'rm -f "${tmp_file}"' EXIT
if [[ "${TAIL_LINES}" -gt 0 ]]; then
  for file in "${existing_sources[@]}"; do
    tail -n "${TAIL_LINES}" "${file}" >> "${tmp_file}"
  done
else
  cat "${existing_sources[@]}" > "${tmp_file}"
fi

count_matches() {
  local pattern="$1"
  local out
  out="$(rg -n --no-heading -i "${pattern}" "${tmp_file}" || true)"
  if [[ -z "${out}" ]]; then
    echo "0"
  else
    printf "%s\n" "${out}" | wc -l | tr -d ' '
  fi
}

print_sample() {
  local pattern="$1"
  local max_lines="${2:-3}"
  rg -n --no-heading -i "${pattern}" "${tmp_file}" | head -n "${max_lines}" || true
}

critical_patterns=(
  "missing referenced source element"
  "missing referenced target element"
  "Unhandled event loop exception"
  "java\\.lang\\.NullPointerException"
  "Deferred remote op dropped after timeout: type=CreateRelationship"
  "Deferred remote op dropped after timeout: type=UpdateRelationship"
)

warning_patterns=(
  "Remote op ignored/failed"
  "Deferred remote op dropped after timeout"
  "PRECONDITION_FAILED"
  "REVISION_AHEAD"
  "LOCK_CONFLICT"
)

info_patterns=(
  "Applied CreateElement"
  "Applied UpdateElement"
  "Applied CreateViewObject"
  "Applied UpdateViewObjectOpaque"
  "Applied CreateRelationship"
  "Applied DeleteElement"
  "Applied DeleteRelationship"
)

critical_total=0
warning_total=0

echo "[info] checked files:"
for file in "${existing_sources[@]}"; do
  echo "  - ${file}"
done

echo
echo "[critical]"
for pattern in "${critical_patterns[@]}"; do
  count="$(count_matches "${pattern}")"
  printf "  %-65s %s\n" "${pattern}" "${count}"
  critical_total=$((critical_total + count))
done

echo
echo "[warnings]"
for pattern in "${warning_patterns[@]}"; do
  count="$(count_matches "${pattern}")"
  printf "  %-65s %s\n" "${pattern}" "${count}"
  warning_total=$((warning_total + count))
done

echo
echo "[signals]"
for pattern in "${info_patterns[@]}"; do
  count="$(count_matches "${pattern}")"
  printf "  %-65s %s\n" "${pattern}" "${count}"
done

if [[ "${critical_total}" -gt 0 ]]; then
  echo
  echo "[result] FAIL (${critical_total} critical finding(s))"
  echo "[sample critical lines]"
  for pattern in "${critical_patterns[@]}"; do
    if [[ "$(count_matches "${pattern}")" -gt 0 ]]; then
      echo "--- ${pattern}"
      print_sample "${pattern}" 2
    fi
  done
  exit 1
fi

if [[ "${warning_total}" -gt 0 ]]; then
  echo
  echo "[result] PASS with warnings (${warning_total} warning finding(s))"
  exit 0
fi

echo
echo "[result] PASS"
