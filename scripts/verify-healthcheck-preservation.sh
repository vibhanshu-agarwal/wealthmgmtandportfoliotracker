#!/usr/bin/env bash
# =============================================================================
# Preservation Property Tests for Docker Healthcheck Fix
# =============================================================================
# Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5
#
# These tests capture the baseline state of files that must NOT change during
# the healthcheck fix. They must pass both BEFORE and AFTER the fix.
#
# Usage: Run from the workspace root:
#   bash scripts/verify-healthcheck-preservation.sh
# =============================================================================

set -euo pipefail

FAILURES=0
TOTAL=0

pass() {
  TOTAL=$((TOTAL + 1))
  echo "  [PASS] $1"
}

fail() {
  TOTAL=$((TOTAL + 1))
  FAILURES=$((FAILURES + 1))
  echo "  [FAIL] $1"
}

echo "============================================="
echo " Preservation Property Tests"
echo "============================================="
echo ""

# ---------------------------------------------------------------
# 1. portfolio-service/build.gradle DOES contain actuator
#    (now in scope — standardized to use /actuator/health like other services)
# ---------------------------------------------------------------
echo "Check 1: portfolio-service/build.gradle DOES contain spring-boot-starter-actuator"
if grep -q "spring-boot-starter-actuator" portfolio-service/build.gradle 2>/dev/null; then
  pass "portfolio-service/build.gradle contains spring-boot-starter-actuator"
else
  fail "portfolio-service/build.gradle is missing spring-boot-starter-actuator"
fi

# ---------------------------------------------------------------
# 2. api-gateway/build.gradle DOES contain actuator (already correct)
# ---------------------------------------------------------------
echo "Check 2: api-gateway/build.gradle DOES contain spring-boot-starter-actuator"
if grep -q "spring-boot-starter-actuator" api-gateway/build.gradle 2>/dev/null; then
  pass "api-gateway/build.gradle contains spring-boot-starter-actuator"
else
  fail "api-gateway/build.gradle is missing spring-boot-starter-actuator"
fi

# ---------------------------------------------------------------
# 3. docker-compose.yml portfolio-service healthcheck uses /actuator/health
# ---------------------------------------------------------------
echo "Check 3: docker-compose.yml portfolio-service healthcheck URL contains /actuator/health on port 8081"
if grep "curl.*localhost:8081.*/actuator/health" docker-compose.yml > /dev/null 2>&1; then
  pass "docker-compose.yml contains /actuator/health for portfolio-service"
else
  fail "docker-compose.yml is missing /actuator/health for portfolio-service"
fi

# ---------------------------------------------------------------
# 4. docker-compose.yml api-gateway healthcheck uses /actuator/health on port 8080
# ---------------------------------------------------------------
echo "Check 4: docker-compose.yml api-gateway healthcheck URL contains /actuator/health on port 8080"
if grep -q "http://localhost:8080/actuator/health" docker-compose.yml 2>/dev/null; then
  pass "docker-compose.yml contains http://localhost:8080/actuator/health for api-gateway"
else
  fail "docker-compose.yml is missing http://localhost:8080/actuator/health for api-gateway"
fi

# ---------------------------------------------------------------
# 5. portfolio-service/Dockerfile contains AWS_LWA_READINESS_CHECK_PATH=/actuator/health
# ---------------------------------------------------------------
echo "Check 5: portfolio-service/Dockerfile contains AWS_LWA_READINESS_CHECK_PATH=/actuator/health"
if grep -q "AWS_LWA_READINESS_CHECK_PATH=/actuator/health" portfolio-service/Dockerfile 2>/dev/null; then
  pass "portfolio-service/Dockerfile contains AWS_LWA_READINESS_CHECK_PATH=/actuator/health"
else
  fail "portfolio-service/Dockerfile is missing AWS_LWA_READINESS_CHECK_PATH=/actuator/health"
fi

# ---------------------------------------------------------------
# 6. market-data-service/Dockerfile contains AWS_LWA_READINESS_CHECK_PATH=/actuator/health
# ---------------------------------------------------------------
echo "Check 6: market-data-service/Dockerfile contains AWS_LWA_READINESS_CHECK_PATH=/actuator/health"
if grep -q "AWS_LWA_READINESS_CHECK_PATH=/actuator/health" market-data-service/Dockerfile 2>/dev/null; then
  pass "market-data-service/Dockerfile contains AWS_LWA_READINESS_CHECK_PATH=/actuator/health"
else
  fail "market-data-service/Dockerfile is missing AWS_LWA_READINESS_CHECK_PATH=/actuator/health"
fi

# ---------------------------------------------------------------
# 7. insight-service/Dockerfile contains AWS_LWA_READINESS_CHECK_PATH=/actuator/health
# ---------------------------------------------------------------
echo "Check 7: insight-service/Dockerfile contains AWS_LWA_READINESS_CHECK_PATH=/actuator/health"
if grep -q "AWS_LWA_READINESS_CHECK_PATH=/actuator/health" insight-service/Dockerfile 2>/dev/null; then
  pass "insight-service/Dockerfile contains AWS_LWA_READINESS_CHECK_PATH=/actuator/health"
else
  fail "insight-service/Dockerfile is missing AWS_LWA_READINESS_CHECK_PATH=/actuator/health"
fi

# ---------------------------------------------------------------
# 8. portfolio-service/Dockerfile contains AWS_LWA_PORT=8081
# ---------------------------------------------------------------
echo "Check 8: portfolio-service/Dockerfile contains AWS_LWA_PORT=8081"
if grep -q "AWS_LWA_PORT=8081" portfolio-service/Dockerfile 2>/dev/null; then
  pass "portfolio-service/Dockerfile contains AWS_LWA_PORT=8081"
else
  fail "portfolio-service/Dockerfile is missing AWS_LWA_PORT=8081"
fi

# ---------------------------------------------------------------
# 9. market-data-service/Dockerfile contains AWS_LWA_PORT=8082
# ---------------------------------------------------------------
echo "Check 9: market-data-service/Dockerfile contains AWS_LWA_PORT=8082"
if grep -q "AWS_LWA_PORT=8082" market-data-service/Dockerfile 2>/dev/null; then
  pass "market-data-service/Dockerfile contains AWS_LWA_PORT=8082"
else
  fail "market-data-service/Dockerfile is missing AWS_LWA_PORT=8082"
fi

# ---------------------------------------------------------------
# 10. insight-service/Dockerfile contains AWS_LWA_PORT=8083
# ---------------------------------------------------------------
echo "Check 10: insight-service/Dockerfile contains AWS_LWA_PORT=8083"
if grep -q "AWS_LWA_PORT=8083" insight-service/Dockerfile 2>/dev/null; then
  pass "insight-service/Dockerfile contains AWS_LWA_PORT=8083"
else
  fail "insight-service/Dockerfile is missing AWS_LWA_PORT=8083"
fi

# ---------------------------------------------------------------
# 11. Docker Compose dependency chain preserved:
#    api-gateway depends on portfolio-service, market-data-service, insight-service
# ---------------------------------------------------------------
echo "Check 9: Docker Compose dependency chain — api-gateway depends on portfolio-service, market-data-service, insight-service"

# Extract the api-gateway service block using sed (from "api-gateway:" to the next
# top-level key or end of file). We match lines between "  api-gateway:" and either
# the next service at the same indent level or the "volumes:" section.
API_GW_BLOCK=$(sed -n '/^  api-gateway:/,/^[^ ]/p' docker-compose.yml)

if echo "$API_GW_BLOCK" | grep -q "portfolio-service"; then
  pass "api-gateway depends_on includes portfolio-service"
else
  fail "api-gateway depends_on is missing portfolio-service"
fi

if echo "$API_GW_BLOCK" | grep -q "market-data-service"; then
  pass "api-gateway depends_on includes market-data-service"
else
  fail "api-gateway depends_on is missing market-data-service"
fi

if echo "$API_GW_BLOCK" | grep -q "insight-service"; then
  pass "api-gateway depends_on includes insight-service"
else
  fail "api-gateway depends_on is missing insight-service"
fi

# ---------------------------------------------------------------
# Summary
# ---------------------------------------------------------------
echo ""
echo "============================================="
echo " Results: $((TOTAL - FAILURES))/$TOTAL checks passed"
if [ "$FAILURES" -gt 0 ]; then
  echo " FAILED: $FAILURES check(s) — regression detected!"
  echo "============================================="
  exit 1
else
  echo " All preservation checks PASSED"
  echo "============================================="
  exit 0
fi
