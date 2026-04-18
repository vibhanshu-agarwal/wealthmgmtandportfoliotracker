#!/usr/bin/env bash
set -euo pipefail

IMAGE_NAME="${IMAGE_NAME:-portfolio-service:neon-verify}"
CONTAINER_NAME="${CONTAINER_NAME:-portfolio-service-neon-verify}"
STARTUP_TIMEOUT_SECONDS="${STARTUP_TIMEOUT_SECONDS:-240}"
ACTIVE_PROFILES="${SPRING_PROFILES_ACTIVE:-prod,aws}"

SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-}"
SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-}"
SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-}"

if [[ -z "$SPRING_DATASOURCE_URL" || -z "$SPRING_DATASOURCE_USERNAME" || -z "$SPRING_DATASOURCE_PASSWORD" ]]; then
  echo "Missing required env vars: SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD"
  exit 1
fi

cleanup() {
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Building Docker image: $IMAGE_NAME"
docker build -f portfolio-service/Dockerfile -t "$IMAGE_NAME" .

echo "Starting container: $CONTAINER_NAME"
docker run -d --name "$CONTAINER_NAME" \
  -e SPRING_PROFILES_ACTIVE="$ACTIVE_PROFILES" \
  -e SPRING_DATASOURCE_URL="$SPRING_DATASOURCE_URL" \
  -e SPRING_DATASOURCE_USERNAME="$SPRING_DATASOURCE_USERNAME" \
  -e SPRING_DATASOURCE_PASSWORD="$SPRING_DATASOURCE_PASSWORD" \
  -e SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT="${SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT:-60000}" \
  -e KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}" \
  -e REDIS_URL="${REDIS_URL:-redis://localhost:6379}" \
  -e SERVER_PORT=8080 \
  -p 8080:8080 \
  "$IMAGE_NAME" >/dev/null

echo "Waiting for startup logs (timeout: ${STARTUP_TIMEOUT_SECONDS}s)"
START_TS="$(date +%s)"
while true; do
  LOGS="$(docker logs "$CONTAINER_NAME" 2>&1 || true)"

  if [[ "$LOGS" == *"Started "* ]]; then
    echo "PASS: Spring Boot reached Started state."
    if [[ "$LOGS" == *"Flyway"* ]]; then
      echo "PASS: Flyway activity detected."
    else
      echo "WARN: Flyway keyword not found in logs."
    fi
    if [[ "$LOGS" == *"HikariPool"* || "$LOGS" == *"jdbc:postgresql"* ]]; then
      echo "PASS: DataSource connection activity detected."
    else
      echo "WARN: DataSource connection keyword not found in logs."
    fi
    echo "---- Container Logs ----"
    docker logs "$CONTAINER_NAME" 2>&1
    exit 0
  fi

  if [[ "$LOGS" == *"FATAL:"* || "$LOGS" == *"password authentication failed"* || "$LOGS" == *"SSL"* && "$LOGS" == *"error"* ]]; then
    echo "FAIL: Detected database/auth/SSL error."
    echo "---- Container Logs ----"
    docker logs "$CONTAINER_NAME" 2>&1
    exit 2
  fi

  NOW_TS="$(date +%s)"
  ELAPSED="$((NOW_TS - START_TS))"
  if (( ELAPSED >= STARTUP_TIMEOUT_SECONDS )); then
    echo "FAIL: Timed out waiting for app startup."
    echo "---- Container Logs ----"
    docker logs "$CONTAINER_NAME" 2>&1
    exit 3
  fi

  sleep 5
done
