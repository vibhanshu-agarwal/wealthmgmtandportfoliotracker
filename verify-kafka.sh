#!/usr/bin/env bash
set -euo pipefail

KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-}"
KAFKA_SASL_USERNAME="${KAFKA_SASL_USERNAME:-}"
KAFKA_SASL_PASSWORD="${KAFKA_SASL_PASSWORD:-}"
KAFKA_SECURITY_PROTOCOL="${KAFKA_SECURITY_PROTOCOL:-SASL_SSL}"
KAFKA_SASL_MECHANISM="${KAFKA_SASL_MECHANISM:-PLAIN}"

if [[ -z "$KAFKA_BOOTSTRAP_SERVERS" || -z "$KAFKA_SASL_USERNAME" || -z "$KAFKA_SASL_PASSWORD" ]]; then
  echo "Missing required env vars: KAFKA_BOOTSTRAP_SERVERS, KAFKA_SASL_USERNAME, KAFKA_SASL_PASSWORD"
  exit 1
fi

echo "Verifying Kafka broker metadata via kcat..."
PRIMARY_BROKER="${KAFKA_BOOTSTRAP_SERVERS%%,*}"
BROKER_HOST="${PRIMARY_BROKER%%:*}"
BROKER_PORT="${PRIMARY_BROKER##*:}"
CA_FILE="$(mktemp)"
trap 'rm -f "$CA_FILE"' EXIT

echo "Fetching broker certificate chain from ${BROKER_HOST}:${BROKER_PORT}..."
echo | openssl s_client -showcerts -servername "$BROKER_HOST" -connect "${BROKER_HOST}:${BROKER_PORT}" 2>/dev/null \
  | awk '/BEGIN CERTIFICATE/,/END CERTIFICATE/' > "$CA_FILE"

docker run --rm -v "$CA_FILE:/tmp/aiven-ca.pem:ro" edenhill/kcat:1.7.1 \
  -b "$KAFKA_BOOTSTRAP_SERVERS" \
  -X "security.protocol=${KAFKA_SECURITY_PROTOCOL}" \
  -X "sasl.mechanisms=${KAFKA_SASL_MECHANISM}" \
  -X "sasl.username=${KAFKA_SASL_USERNAME}" \
  -X "sasl.password=${KAFKA_SASL_PASSWORD}" \
  -X "ssl.ca.location=/tmp/aiven-ca.pem" \
  -L

echo "PASS: Aiven Kafka TLS+SASL handshake and metadata retrieval succeeded."
