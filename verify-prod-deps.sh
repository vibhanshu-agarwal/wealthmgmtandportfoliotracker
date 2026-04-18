#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Pre-flight: PostgreSQL (Neon) ==="
./verify-db.sh

echo "=== Pre-flight: Redis (Upstash) ==="
./verify-redis.sh

echo "=== Pre-flight: Kafka (Aiven) ==="
./verify-kafka.sh

echo "PASS: All production dependencies are healthy (Neon + Upstash Redis + Aiven Kafka)."
