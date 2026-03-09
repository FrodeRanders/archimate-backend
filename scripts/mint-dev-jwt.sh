#!/bin/zsh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PRIVATE_KEY="${DEV_JWT_PRIVATE_KEY:-${REPO_ROOT}/server/src/test/resources/jwt/privateKey.pem}"
ISSUER="${DEV_JWT_ISSUER:-https://collab.dev}"
EXPIRES_IN_SECONDS="${DEV_JWT_EXPIRES_IN:-3600}"
AUDIENCE="${DEV_JWT_AUDIENCE:-}"
USER_ID=""
SUBJECT=""
UPN=""
ROLE_CSV=""
PRINT_PAYLOAD=0

usage() {
    cat <<'EOF'
Usage:
  scripts/mint-dev-jwt.sh --user USER [--roles admin,model_writer] [options]

Options:
  --user USER                Required. Used as the default subject, upn, and preferred_username.
  --roles CSV                Optional comma-separated role/group list for the JWT groups claim.
  --issuer ISSUER            JWT issuer. Default: https://collab.dev
  --audience AUD             Optional aud claim.
  --expires-in SECONDS       Token lifetime in seconds. Default: 3600
  --private-key PATH         PEM private key for RS256 signing.
  --subject SUBJECT          Optional explicit sub claim. Defaults to --user.
  --upn UPN                  Optional explicit upn claim. Defaults to --user.
  --print-payload            Print the signed payload JSON to stderr before the token.
  --help                     Show this help.

Examples:
  scripts/mint-dev-jwt.sh --user alice --roles admin,model_writer,model_reader
  scripts/mint-dev-jwt.sh --user bob --roles model_writer --expires-in 600 --print-payload

Environment overrides:
  DEV_JWT_PRIVATE_KEY
  DEV_JWT_ISSUER
  DEV_JWT_EXPIRES_IN
  DEV_JWT_AUDIENCE
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --user)
            USER_ID="${2:-}"
            shift 2
            ;;
        --roles)
            ROLE_CSV="${2:-}"
            shift 2
            ;;
        --issuer)
            ISSUER="${2:-}"
            shift 2
            ;;
        --audience)
            AUDIENCE="${2:-}"
            shift 2
            ;;
        --expires-in)
            EXPIRES_IN_SECONDS="${2:-}"
            shift 2
            ;;
        --private-key)
            PRIVATE_KEY="${2:-}"
            shift 2
            ;;
        --subject)
            SUBJECT="${2:-}"
            shift 2
            ;;
        --upn)
            UPN="${2:-}"
            shift 2
            ;;
        --print-payload)
            PRINT_PAYLOAD=1
            shift
            ;;
        --help|-h)
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

if [[ -z "${USER_ID}" ]]; then
    echo "--user is required" >&2
    usage >&2
    exit 1
fi

if ! [[ "${EXPIRES_IN_SECONDS}" =~ ^[0-9]+$ ]] || [[ "${EXPIRES_IN_SECONDS}" -le 0 ]]; then
    echo "--expires-in must be a positive integer" >&2
    exit 1
fi

if [[ ! -f "${PRIVATE_KEY}" ]]; then
    echo "Private key not found: ${PRIVATE_KEY}" >&2
    exit 1
fi

SUBJECT="${SUBJECT:-${USER_ID}}"
UPN="${UPN:-${USER_ID}}"

python_b64url='import base64,sys; print(base64.urlsafe_b64encode(sys.stdin.buffer.read()).rstrip(b"=").decode("ascii"))'

HEADER_JSON='{"alg":"RS256","typ":"JWT"}'
PAYLOAD_JSON="$(python3 - "${USER_ID}" "${SUBJECT}" "${UPN}" "${ISSUER}" "${EXPIRES_IN_SECONDS}" "${ROLE_CSV}" "${AUDIENCE}" <<'PY'
import json
import sys
import time

user_id, subject, upn, issuer, expires_in_seconds, role_csv, audience = sys.argv[1:]
now = int(time.time())
roles = [role.strip() for role in role_csv.split(",") if role.strip()]
payload = {
    "iss": issuer,
    "sub": subject,
    "upn": upn,
    "preferred_username": user_id,
    "iat": now,
    "exp": now + int(expires_in_seconds),
}
if roles:
    payload["groups"] = roles
if audience:
    payload["aud"] = audience
print(json.dumps(payload, separators=(",", ":"), sort_keys=True))
PY
)"

if [[ "${PRINT_PAYLOAD}" -eq 1 ]]; then
    printf '%s\n' "${PAYLOAD_JSON}" | python3 -m json.tool >&2
fi

HEADER_B64="$(printf '%s' "${HEADER_JSON}" | python3 -c "${python_b64url}")"
PAYLOAD_B64="$(printf '%s' "${PAYLOAD_JSON}" | python3 -c "${python_b64url}")"
SIGNING_INPUT="${HEADER_B64}.${PAYLOAD_B64}"
SIGNATURE_B64="$(
    printf '%s' "${SIGNING_INPUT}" \
        | openssl dgst -binary -sha256 -sign "${PRIVATE_KEY}" \
        | python3 -c "${python_b64url}"
)"

printf '%s.%s\n' "${SIGNING_INPUT}" "${SIGNATURE_B64}"
