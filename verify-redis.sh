#!/usr/bin/env bash
set -euo pipefail

UPSTASH_REDIS_REST_URL="${UPSTASH_REDIS_REST_URL:-}"
UPSTASH_REDIS_REST_TOKEN="${UPSTASH_REDIS_REST_TOKEN:-}"
REDIS_URL="${REDIS_URL:-}"

if [[ -z "$UPSTASH_REDIS_REST_URL" || -z "$UPSTASH_REDIS_REST_TOKEN" || -z "$REDIS_URL" ]]; then
  echo "Missing required env vars: UPSTASH_REDIS_REST_URL, UPSTASH_REDIS_REST_TOKEN, REDIS_URL"
  exit 1
fi

KEY="wmpt:redis:verify"
VALUE="ok-$(date +%s)"

echo "Step 1/3: Verify Upstash REST SET"
SET_RESPONSE="$(curl -sS -X POST \
  -H "Authorization: Bearer ${UPSTASH_REDIS_REST_TOKEN}" \
  "${UPSTASH_REDIS_REST_URL}/set/${KEY}/${VALUE}")"
echo "$SET_RESPONSE"
if [[ "$SET_RESPONSE" != *"\"result\":\"OK\""* ]]; then
  echo "FAIL: REST SET did not return OK."
  exit 2
fi

echo "Step 2/3: Verify Upstash REST GET"
GET_RESPONSE="$(curl -sS \
  -H "Authorization: Bearer ${UPSTASH_REDIS_REST_TOKEN}" \
  "${UPSTASH_REDIS_REST_URL}/get/${KEY}")"
echo "$GET_RESPONSE"
if [[ "$GET_RESPONSE" != *"\"result\":\"${VALUE}\""* ]]; then
  echo "FAIL: REST GET value mismatch."
  exit 3
fi

echo "Step 3/3: Verify Redis protocol over TLS (rediss://)"
PING_RESPONSE="$(docker run --rm redis:7-alpine redis-cli -u "${REDIS_URL}" --raw PING)"
echo "$PING_RESPONSE"
if [[ "$PING_RESPONSE" != "PONG" ]]; then
  echo "FAIL: Redis PING over TLS failed."
  exit 4
fi

echo "PASS: Upstash Redis connectivity verified via REST and TLS Redis protocol."
