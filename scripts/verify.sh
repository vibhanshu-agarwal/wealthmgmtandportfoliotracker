#!/usr/bin/env bash
# =============================================================================
# Local Verification Pipeline
#
# Builds all container images, starts the Docker Compose stack, runs Pact
# consumer + provider verification, and executes the Playwright E2E suite.
#
# Usage:
#   ./scripts/verify.sh          # full pipeline
#   ./scripts/verify.sh --skip-build   # skip Gradle bootJar + Docker build
#
# On failure the script stops at the failing stage, leaves containers running
# for debugging, and prints the cleanup command.
# =============================================================================

set -euo pipefail

# ── Colours ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Colour

# ── State tracking ───────────────────────────────────────────────────────────
declare -A RESULTS
STAGES=("build" "docker-build" "compose-up" "health-check" "pact-consumer" "pact-provider" "playwright-e2e")
SKIP_BUILD=false

for arg in "$@"; do
  case $arg in
    --skip-build) SKIP_BUILD=true ;;
  esac
done

# ── Helper functions ─────────────────────────────────────────────────────────
stage_start() {
  echo -e "\n${YELLOW}▶ Stage: $1${NC}"
}

stage_pass() {
  RESULTS["$1"]="PASS"
  echo -e "${GREEN}✅ $1 passed${NC}"
}

stage_fail() {
  RESULTS["$1"]="FAIL"
  echo -e "${RED}❌ $1 failed${NC}"
  print_summary
  echo -e "\n${YELLOW}Containers left running for debugging.${NC}"
  echo -e "Cleanup: ${YELLOW}docker compose down -v${NC}"
  exit 1
}

print_summary() {
  echo ""
  echo "╔══════════════════════════════════════╗"
  echo "║   Verification Pipeline Summary      ║"
  echo "╠══════════════════════════════════════╣"
  for stage in "${STAGES[@]}"; do
    status="${RESULTS[$stage]:-SKIP}"
    if [ "$status" = "PASS" ]; then
      printf "║ %-30s ✅ PASS ║\n" "$stage"
    elif [ "$status" = "FAIL" ]; then
      printf "║ %-30s ❌ FAIL ║\n" "$stage"
    else
      printf "║ %-30s ⏭  SKIP ║\n" "$stage"
    fi
  done
  echo "╠══════════════════════════════════════╣"
  # Check if any stage failed
  local overall="PASS"
  for stage in "${STAGES[@]}"; do
    if [ "${RESULTS[$stage]:-SKIP}" = "FAIL" ]; then
      overall="FAIL"
      break
    fi
  done
  if [ "$overall" = "PASS" ]; then
    echo "║ Overall: ✅ PASS                     ║"
  else
    echo "║ Overall: ❌ FAIL                     ║"
  fi
  echo "╚══════════════════════════════════════╝"
}

wait_for_health() {
  local name="$1"
  local url="$2"
  local timeout="${3:-120}"
  local interval=5
  local elapsed=0

  echo "  Waiting for $name at $url (timeout: ${timeout}s)..."
  while [ $elapsed -lt $timeout ]; do
    if curl -sf "$url" > /dev/null 2>&1; then
      echo "  ✓ $name is healthy (${elapsed}s)"
      return 0
    fi
    sleep $interval
    elapsed=$((elapsed + interval))
  done
  echo "  ✗ $name did not become healthy within ${timeout}s"
  return 1
}

# ── Stage 1: Gradle Build ───────────────────────────────────────────────────
if [ "$SKIP_BUILD" = true ]; then
  RESULTS["build"]="SKIP"
  RESULTS["docker-build"]="SKIP"
  echo -e "${YELLOW}⏭  Skipping build stages (--skip-build)${NC}"
else
  stage_start "build (bootJar)"
  if ./gradlew bootJar --no-daemon; then
    stage_pass "build"
  else
    stage_fail "build"
  fi

  # ── Stage 2: Docker Build ────────────────────────────────────────────────
  stage_start "docker-build"
  if docker compose build; then
    stage_pass "docker-build"
  else
    stage_fail "docker-build"
  fi
fi

# ── Stage 3: Docker Compose Up ─────────────────────────────────────────────
stage_start "compose-up"
if docker compose up -d; then
  stage_pass "compose-up"
else
  stage_fail "compose-up"
fi

# ── Stage 4: Health Checks ─────────────────────────────────────────────────
stage_start "health-check"
HEALTH_OK=true

wait_for_health "portfolio-service" "http://localhost:8081/api/portfolio/health" 120 || HEALTH_OK=false
wait_for_health "market-data-service" "http://localhost:8082/" 120 || HEALTH_OK=false
wait_for_health "insight-service" "http://localhost:8083/" 120 || HEALTH_OK=false
wait_for_health "api-gateway" "http://localhost:8080/actuator/health" 120 || HEALTH_OK=false

if [ "$HEALTH_OK" = true ]; then
  stage_pass "health-check"
else
  stage_fail "health-check"
fi

# ── Stage 5: Pact Consumer Tests ──────────────────────────────────────────
stage_start "pact-consumer"
if (cd frontend && npm run test:pact); then
  stage_pass "pact-consumer"
else
  stage_fail "pact-consumer"
fi

# ── Stage 6: Pact Provider Verification ───────────────────────────────────
stage_start "pact-provider"
if ./gradlew :portfolio-service:test --tests '*PactVerification*' :insight-service:test --tests '*PactVerification*' --no-daemon; then
  stage_pass "pact-provider"
else
  stage_fail "pact-provider"
fi

# ── Stage 7: Playwright E2E ──────────────────────────────────────────────
stage_start "playwright-e2e"
if (cd frontend && \
    npx playwright install --with-deps chromium && \
    npx playwright test tests/e2e/auth-jwt-health.spec.ts --project=chromium --reporter=list && \
    npx playwright test \
      tests/e2e/golden-path.spec.ts \
      tests/e2e/dashboard-data.spec.ts \
      tests/e2e/mocked-chaos.spec.ts \
      tests/e2e/live-contract.spec.ts \
      --project=chromium \
      --reporter=list); then
  stage_pass "playwright-e2e"
else
  stage_fail "playwright-e2e"
fi

# ── Summary ──────────────────────────────────────────────────────────────
print_summary

echo ""
echo -e "${GREEN}All stages passed.${NC}"
echo -e "Cleanup: ${YELLOW}docker compose down -v${NC}"
read -p "Run cleanup now? [y/N] " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
  docker compose down -v
  echo "Cleaned up."
fi
