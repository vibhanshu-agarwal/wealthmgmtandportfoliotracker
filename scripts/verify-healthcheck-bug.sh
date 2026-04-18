#!/bin/bash
# verify-healthcheck-bug.sh
# Bug Condition Exploration Test for docker-healthcheck-fix
# This script verifies the expected state of health check configuration.
# On UNFIXED code, this script will EXIT 1 (failures expected — proves bug exists).
# On FIXED code, this script will EXIT 0 (all checks pass — confirms fix).

FAILURES=0

echo "Running Bug Condition Exploration Tests..."
echo "---------------------------------------"

# 1. Check market-data-service/build.gradle for actuator dependency
if grep -q "spring-boot-starter-actuator" market-data-service/build.gradle; then
  echo "✓ PASS: market-data-service has actuator dependency"
else
  echo "✗ FAIL: market-data-service MISSING actuator dependency"
  ((FAILURES++))
fi

# 2. Check insight-service/build.gradle for actuator dependency
if grep -q "spring-boot-starter-actuator" insight-service/build.gradle; then
  echo "✓ PASS: insight-service has actuator dependency"
else
  echo "✗ FAIL: insight-service MISSING actuator dependency"
  ((FAILURES++))
fi

# 3. Check portfolio-service/build.gradle for actuator dependency
if grep -q "spring-boot-starter-actuator" portfolio-service/build.gradle; then
  echo "✓ PASS: portfolio-service has actuator dependency"
else
  echo "✗ FAIL: portfolio-service MISSING actuator dependency"
  ((FAILURES++))
fi

# 4. Check docker-compose.yml for portfolio-service health check URL
if grep "curl.*localhost:8081.*/actuator/health" docker-compose.yml > /dev/null 2>&1; then
  echo "✓ PASS: portfolio-service health check URL is correct"
else
  echo "✗ FAIL: portfolio-service health check URL is WRONG (targets /api/portfolio/health)"
  ((FAILURES++))
fi

# 5. Check docker-compose.yml for market-data-service health check URL
# Extract the healthcheck test line for market-data-service (port 8082)
if grep "curl.*localhost:8082.*/actuator/health" docker-compose.yml > /dev/null 2>&1; then
  echo "✓ PASS: market-data-service health check URL is correct"
else
  echo "✗ FAIL: market-data-service health check URL is WRONG (targets root /)"
  ((FAILURES++))
fi

# 6. Check docker-compose.yml for insight-service health check URL
# Extract the healthcheck test line for insight-service (port 8083)
if grep "curl.*localhost:8083.*/actuator/health" docker-compose.yml > /dev/null 2>&1; then
  echo "✓ PASS: insight-service health check URL is correct"
else
  echo "✗ FAIL: insight-service health check URL is WRONG (targets root /)"
  ((FAILURES++))
fi

echo "---------------------------------------"
echo "Total Failures: $FAILURES"

if [ $FAILURES -eq 0 ]; then
  echo "All tests passed. The bug is fixed."
  exit 0
else
  echo "Tests failed. This is EXPECTED on unfixed code."
  exit 1
fi
