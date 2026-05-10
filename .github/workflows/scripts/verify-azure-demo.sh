#!/usr/bin/env bash
# verify-azure-demo.sh — post-seed live-demo assertion.
# Asserts the four invariants from bugfix.md §2.5 against the live API gateway.
# Exits 0 on all-pass, exits 1 on any assertion failure (with a clear message).
#
# Required env vars:
#   API_BASE        — e.g. https://api.vibhanshu-ai-portfolio.dev
#   DEMO_EMAIL      — demo user email (from secrets.E2E_TEST_USER_EMAIL)
#   DEMO_PASSWORD   — demo user password (from secrets.E2E_TEST_USER_PASSWORD)
set -euo pipefail

: "${API_BASE:?API_BASE must be set}"
: "${DEMO_EMAIL:?DEMO_EMAIL must be set}"
: "${DEMO_PASSWORD:?DEMO_PASSWORD must be set}"

CURL_OPTS=(--silent --show-error --fail-with-body --retry 3 --retry-delay 5 --max-time 30)

# Assertion (a): /actuator/health returns 200
echo "::group::Assertion (a) — /actuator/health == 200"
HEALTH_STATUS=$(curl "${CURL_OPTS[@]}" -o /tmp/health.json -w '%{http_code}' "${API_BASE}/actuator/health")
echo "HTTP ${HEALTH_STATUS}"
cat /tmp/health.json
if [ "${HEALTH_STATUS}" != "200" ]; then
  echo "::error::Health check failed: expected 200, got ${HEALTH_STATUS}"
  exit 1
fi
echo "::endgroup::"

# Assertion (b): /api/auth/login returns 200 with non-empty JWT
echo "::group::Assertion (b) — /api/auth/login returns JWT"
LOGIN_BODY=$(jq -nc --arg email "${DEMO_EMAIL}" --arg pwd "${DEMO_PASSWORD}" '{email:$email,password:$pwd}')
LOGIN_STATUS=$(curl "${CURL_OPTS[@]}" -o /tmp/login.json -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -d "${LOGIN_BODY}" \
  "${API_BASE}/api/auth/login")
echo "HTTP ${LOGIN_STATUS}"
if [ "${LOGIN_STATUS}" != "200" ]; then
  echo "::error::Login failed: expected 200, got ${LOGIN_STATUS}"
  cat /tmp/login.json
  exit 1
fi
JWT=$(jq -r '.token // empty' /tmp/login.json)
if [ -z "${JWT}" ]; then
  echo "::error::Login response missing .token"
  cat /tmp/login.json
  exit 1
fi
echo "JWT length: ${#JWT}"
echo "::endgroup::"

# Assertion (c): /api/portfolio/summary total > 0
echo "::group::Assertion (c) — /api/portfolio/summary total > 0"
SUMMARY_STATUS=$(curl "${CURL_OPTS[@]}" -o /tmp/summary.json -w '%{http_code}' \
  -H "Authorization: Bearer ${JWT}" \
  "${API_BASE}/api/portfolio/summary")
echo "HTTP ${SUMMARY_STATUS}"
cat /tmp/summary.json
if [ "${SUMMARY_STATUS}" != "200" ]; then
  echo "::error::Summary failed: expected 200, got ${SUMMARY_STATUS}"
  exit 1
fi
# Portfolio summary's total field is `totalValue` (BigDecimal) in the current API;
# guard against schema drift by attempting multiple plausible field names.
TOTAL=$(jq -r '(.totalValue // .total // 0) | tonumber' /tmp/summary.json)
echo "Summary total: ${TOTAL}"
if ! awk -v t="${TOTAL}" 'BEGIN { exit !(t > 0) }'; then
  echo "::error::Summary total is not > 0: ${TOTAL}"
  exit 1
fi
echo "::endgroup::"

# Assertion (d): /api/portfolio returns at least one portfolio with non-empty holdings
echo "::group::Assertion (d) — /api/portfolio has non-empty holdings"
PORTFOLIO_STATUS=$(curl "${CURL_OPTS[@]}" -o /tmp/portfolio.json -w '%{http_code}' \
  -H "Authorization: Bearer ${JWT}" \
  "${API_BASE}/api/portfolio")
echo "HTTP ${PORTFOLIO_STATUS}"
if [ "${PORTFOLIO_STATUS}" != "200" ]; then
  echo "::error::Portfolio list failed: expected 200, got ${PORTFOLIO_STATUS}"
  cat /tmp/portfolio.json
  exit 1
fi
# Accept either a top-level array [ {holdings:[...]}, ... ] or a wrapper { portfolios: [...] }.
PORTFOLIO_COUNT=$(jq -r '(if type=="array" then . else (.portfolios // []) end) | length' /tmp/portfolio.json)
NONEMPTY_COUNT=$(jq -r '
  (if type=="array" then . else (.portfolios // []) end)
  | map(select((.holdings // []) | length > 0))
  | length' /tmp/portfolio.json)
echo "Portfolios returned: ${PORTFOLIO_COUNT}; with non-empty holdings: ${NONEMPTY_COUNT}"
if [ "${PORTFOLIO_COUNT}" = "0" ]; then
  echo "::error::No portfolios returned for demo user"
  exit 1
fi
if [ "${NONEMPTY_COUNT}" = "0" ]; then
  echo "::error::No portfolio with non-empty holdings found"
  exit 1
fi
echo "::endgroup::"

echo "All four verify assertions passed."
