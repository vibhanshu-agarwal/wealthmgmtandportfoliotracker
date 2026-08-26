#!/usr/bin/env bash
# Seed the fixed E2E portfolio with a frozen expectedVersion (B1 Wave 5b / Task 5.4).
#
# Contract (environment-only):
#   API_BASE
#   INTERNAL_API_KEY
#   E2E_USER_ID
#   E2E_TEST_USER_EMAIL
#   E2E_TEST_USER_PASSWORD
#
# Sequence: login → GET /api/portfolio once → POST /api/internal/portfolio/seed once.
# A 409 is terminal (log body once, exit nonzero). No curl --retry / loops / recursion.
set -euo pipefail

: "${API_BASE:?API_BASE is required}"
: "${INTERNAL_API_KEY:?INTERNAL_API_KEY is required}"
: "${E2E_USER_ID:?E2E_USER_ID is required}"
: "${E2E_TEST_USER_EMAIL:?E2E_TEST_USER_EMAIL is required}"
: "${E2E_TEST_USER_PASSWORD:?E2E_TEST_USER_PASSWORD is required}"

API_BASE="${API_BASE%/}"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT

login_body="$(jq -nc \
  --arg email "${E2E_TEST_USER_EMAIL}" \
  --arg password "${E2E_TEST_USER_PASSWORD}" \
  '{email:$email, password:$password}')"

login_status="$(curl -sS -o "${WORKDIR}/login.json" -w "%{http_code}" \
  -X POST "${API_BASE}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "${login_body}" \
  --max-time 60 || echo "000")"

if [[ "${login_status}" != "200" ]]; then
  echo "seed-portfolio-with-version: login failed HTTP ${login_status}" >&2
  exit 1
fi

token="$(jq -r '.token // empty' "${WORKDIR}/login.json")"
login_user_id="$(jq -r '.userId // empty' "${WORKDIR}/login.json")"
if [[ -z "${token}" ]]; then
  echo "seed-portfolio-with-version: login returned empty token" >&2
  exit 1
fi
if [[ "${login_user_id}" != "${E2E_USER_ID}" ]]; then
  echo "seed-portfolio-with-version: login userId mismatch (expected fixed E2E identity)" >&2
  exit 1
fi

portfolio_status="$(curl -sS -o "${WORKDIR}/portfolio.json" -w "%{http_code}" \
  -X GET "${API_BASE}/api/portfolio" \
  -H "Authorization: Bearer ${token}" \
  --max-time 60 || echo "000")"

if [[ "${portfolio_status}" != "200" ]]; then
  echo "seed-portfolio-with-version: portfolio read failed HTTP ${portfolio_status}" >&2
  exit 1
fi

# Exactly one matching portfolio with a non-negative safe-integer version.
expected_version="$(jq -e --arg uid "${E2E_USER_ID}" '
  if (type != "array") then error("portfolio payload must be an array") else . end
  | map(select(.userId == $uid))
  | if length != 1 then error("expected exactly one portfolio for fixed E2E user") else .[0].version end
  | if ((type != "number") or (. != floor) or (. < 0) or (. > 9007199254740991))
      then error("version must be a non-negative safe integer")
      else floor end
' "${WORKDIR}/portfolio.json")"

seed_body="$(jq -nc --argjson expectedVersion "${expected_version}" '{expectedVersion:$expectedVersion}')"

echo "[b1-g5][synthetic-shell] expectedVersion=${expected_version}"

seed_status="$(curl -sS -o "${WORKDIR}/seed.json" -w "%{http_code}" \
  -X POST "${API_BASE}/api/internal/portfolio/seed" \
  -H "Content-Type: application/json" \
  -H "X-Internal-Api-Key: ${INTERNAL_API_KEY}" \
  -d "${seed_body}" \
  --max-time 120 || echo "000")"

if [[ "${seed_status}" == "409" ]]; then
  echo "seed-portfolio-with-version: terminal HTTP 409" >&2
  cat "${WORKDIR}/seed.json" >&2 || true
  echo >&2
  exit 1
fi

if [[ "${seed_status}" != "200" ]]; then
  echo "seed-portfolio-with-version: seed failed HTTP ${seed_status}" >&2
  cat "${WORKDIR}/seed.json" >&2 || true
  echo >&2
  exit 1
fi

echo "seed-portfolio-with-version: seed succeeded (holdings only; no market-data write path)."
