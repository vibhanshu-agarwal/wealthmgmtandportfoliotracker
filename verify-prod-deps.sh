#!/usr/bin/env bash
set -euo pipefail

echo "=== Pre-flight: PostgreSQL (Neon) ==="
./verify-db.sh

echo "=== Pre-flight: Redis (Upstash) ==="
./verify-redis.sh

echo "=== Pre-flight: Kafka (Aiven) ==="
./verify-kafka.sh

echo "PASS: All production dependencies are healthy (Neon + Upstash Redis + Aiven Kafka)."
