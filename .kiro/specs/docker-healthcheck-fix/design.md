# Docker Healthcheck Fix — Bugfix Design

## Overview

The CI/CD pipeline fails because `market-data-service` and `insight-service` Docker containers are marked unhealthy. The root cause is twofold: both services are missing the `spring-boot-starter-actuator` dependency (so `/actuator/health` does not exist), and the `docker-compose.yml` health checks for these services target the root path (`/`) which returns HTTP 404. The fix adds the actuator dependency to both services and updates the Docker Compose health check URLs to use `/actuator/health`. No Dockerfile changes are needed since they already correctly reference `/actuator/health` for the Lambda Web Adapter readiness path.

## Glossary

- **Bug_Condition (C)**: A Docker Compose service whose health check targets an endpoint that does not exist — specifically `market-data-service` and `insight-service` where the actuator dependency is missing and the health check URL is wrong
- **Property (P)**: After the fix, both services expose `/actuator/health` returning HTTP 200, and Docker Compose health checks target that endpoint so containers are marked healthy
- **Preservation**: Existing health checks for `portfolio-service` (custom `/api/portfolio/health`) and `api-gateway` (`/actuator/health`) must remain unchanged; existing API endpoints on all services must continue working
- **`spring-boot-starter-actuator`**: Spring Boot dependency that auto-configures production-ready endpoints including `/actuator/health`
- **`AWS_LWA_READINESS_CHECK_PATH`**: Environment variable in the Dockerfile that tells the Lambda Web Adapter which endpoint to poll for readiness; already set to `/actuator/health` in both affected services
- **Docker Compose `healthcheck`**: Configuration block that defines how Docker determines if a container is healthy; uses `curl` to hit an HTTP endpoint

## Bug Details

### Bug Condition

The bug manifests when `market-data-service` or `insight-service` containers start in Docker Compose. The health check commands (`curl -sf http://localhost:8082/` and `curl -sf http://localhost:8083/`) hit the root path, which has no controller mapping and returns HTTP 404. Additionally, neither service includes `spring-boot-starter-actuator`, so the standard `/actuator/health` endpoint does not exist. This causes both containers to be marked unhealthy, which cascades to block `insight-service` and `api-gateway` from starting due to `condition: service_healthy` dependencies.

**Formal Specification:**

```
FUNCTION isBugCondition(service)
  INPUT: service of type DockerComposeService
  OUTPUT: boolean

  RETURN service.name IN ['market-data-service', 'insight-service']
         AND NOT hasDependency(service, 'spring-boot-starter-actuator')
         AND (healthCheckUrl(service) = '/' OR healthCheckUrl(service) = '/actuator/health')
         AND httpGet(service, healthCheckUrl(service)) != 200
END FUNCTION
```

### Examples

- **market-data-service in Docker Compose**: Health check runs `curl -sf http://localhost:8082/` → receives HTTP 404 → container marked unhealthy after 12 retries → `insight-service` and `api-gateway` cannot start
- **insight-service in Docker Compose**: Health check runs `curl -sf http://localhost:8083/` → receives HTTP 404 → container marked unhealthy after 12 retries → `api-gateway` cannot start
- **Lambda deployment of market-data-service**: Lambda Web Adapter polls `AWS_LWA_READINESS_CHECK_PATH=/actuator/health` → endpoint does not exist → readiness check fails → Lambda invocation errors
- **Edge case — portfolio-service**: Uses custom `/api/portfolio/health` endpoint, has no actuator dependency, and its health check works correctly — this service is NOT affected by the bug

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**

- `portfolio-service` health check at `/api/portfolio/health` must continue to work exactly as before (custom controller endpoint, no actuator)
- `api-gateway` health check at `/actuator/health` must continue to work exactly as before (already has actuator dependency)
- All existing REST API endpoints on `market-data-service` (e.g., `/api/market-data/*`) must continue to handle requests correctly
- All existing REST API endpoints on `insight-service` (e.g., `/api/insights/*`) must continue to handle requests correctly
- Dockerfile `ENTRYPOINT`, `CMD`, `AWS_LWA_PORT`, and `AWS_LWA_READINESS_CHECK_PATH` values must remain unchanged in both affected services
- The Docker Compose dependency chain (`api-gateway` → `market-data-service` + `insight-service` → `portfolio-service`) must remain unchanged

**Scope:**
All services and configurations that do NOT involve the `market-data-service` or `insight-service` health check mechanism should be completely unaffected by this fix. This includes:

- `portfolio-service` build.gradle, Dockerfile, and docker-compose health check
- `api-gateway` build.gradle, Dockerfile, and docker-compose health check
- All Kafka, MongoDB, Redis, and PostgreSQL infrastructure service configurations
- Frontend application and CDK infrastructure code

## Hypothesized Root Cause

Based on the bug description and codebase analysis, the root causes are:

1. **Missing Actuator Dependency**: `market-data-service/build.gradle` and `insight-service/build.gradle` do not include `spring-boot-starter-actuator`. Without this dependency, Spring Boot does not auto-configure the `/actuator/health` endpoint. The `api-gateway` already has this dependency and works correctly — this is the pattern to follow.

2. **Incorrect Health Check URL in docker-compose.yml**: The health checks for both affected services use the root path (`/`) instead of `/actuator/health`:
   - `market-data-service`: `curl -sf http://localhost:8082/ || exit 1`
   - `insight-service`: `curl -sf http://localhost:8083/ || exit 1`

   Neither service has a controller mapped to `/`, so these always return 404 regardless of whether actuator is present.

3. **No Root Cause in Dockerfiles**: The Dockerfiles already correctly set `AWS_LWA_READINESS_CHECK_PATH=/actuator/health`. Once the actuator dependency is added, the Lambda readiness check will work without any Dockerfile changes.

4. **No Root Cause in application.yml**: Spring Boot auto-configures the actuator health endpoint at `/actuator/health` by default. No `application.yml` changes are needed — the default configuration is sufficient.

## Correctness Properties

Property 1: Bug Condition — Health Check Endpoint Availability

_For any_ service in `{market-data-service, insight-service}` that has `spring-boot-starter-actuator` added to its `build.gradle`, the service SHALL expose an `/actuator/health` endpoint that returns HTTP 200 with a JSON body containing `"status": "UP"` when the application context has started successfully.

**Validates: Requirements 2.1, 2.2, 2.5**

Property 2: Preservation — Existing Service Behavior Unchanged

_For any_ service or configuration that is NOT `market-data-service/build.gradle`, `insight-service/build.gradle`, or the health check entries for these two services in `docker-compose.yml`, the fix SHALL produce no changes, preserving all existing health checks, API endpoints, Dockerfile configurations, and dependency chains exactly as they were before the fix.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5**

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct:

**File**: `market-data-service/build.gradle`

**Change**: Add actuator dependency

**Specific Changes**:

1. **Add `spring-boot-starter-actuator`**: Add `implementation 'org.springframework.boot:spring-boot-starter-actuator'` to the dependencies block, following the same pattern used in `api-gateway/build.gradle`

**File**: `insight-service/build.gradle`

**Change**: Add actuator dependency

**Specific Changes**:

1. **Add `spring-boot-starter-actuator`**: Add `implementation 'org.springframework.boot:spring-boot-starter-actuator'` to the dependencies block, following the same pattern used in `api-gateway/build.gradle`

**File**: `docker-compose.yml`

**Change**: Update health check URLs for both affected services

**Specific Changes**:

1. **Update market-data-service health check**: Change `test: ["CMD-SHELL", "curl -sf http://localhost:8082/ || exit 1"]` to `test: ["CMD-SHELL", "curl -sf http://localhost:8082/actuator/health || exit 1"]`
2. **Update insight-service health check**: Change `test: ["CMD-SHELL", "curl -sf http://localhost:8083/ || exit 1"]` to `test: ["CMD-SHELL", "curl -sf http://localhost:8083/actuator/health || exit 1"]`

**Files NOT changed** (confirming no changes needed):

- `market-data-service/Dockerfile` — already has `AWS_LWA_READINESS_CHECK_PATH=/actuator/health`
- `insight-service/Dockerfile` — already has `AWS_LWA_READINESS_CHECK_PATH=/actuator/health`
- `market-data-service/src/main/resources/application.yml` — Spring Boot auto-configures actuator
- `insight-service/src/main/resources/application.yml` — Spring Boot auto-configures actuator
- `portfolio-service/*` — uses custom health endpoint, unrelated
- `api-gateway/*` — already has actuator, unrelated

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bug on unfixed code, then verify the fix works correctly and preserves existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Inspect the build.gradle files for the presence of `spring-boot-starter-actuator` and the docker-compose.yml health check URLs. Attempt to start the services and observe health check failures.

**Test Cases**:

1. **Dependency Audit — market-data-service**: Verify `market-data-service/build.gradle` does NOT contain `spring-boot-starter-actuator` (will confirm bug condition on unfixed code)
2. **Dependency Audit — insight-service**: Verify `insight-service/build.gradle` does NOT contain `spring-boot-starter-actuator` (will confirm bug condition on unfixed code)
3. **Health Check URL Audit — market-data-service**: Verify docker-compose.yml health check for market-data-service targets `/` not `/actuator/health` (will confirm bug condition on unfixed code)
4. **Health Check URL Audit — insight-service**: Verify docker-compose.yml health check for insight-service targets `/` not `/actuator/health` (will confirm bug condition on unfixed code)
5. **Gradle Build Verification**: Run `./gradlew :market-data-service:dependencies` and `./gradlew :insight-service:dependencies` to confirm actuator is not in the dependency tree (will confirm on unfixed code)

**Expected Counterexamples**:

- `build.gradle` files for both services lack the actuator dependency
- Docker Compose health check URLs target `/` instead of `/actuator/health`
- Possible causes confirmed: missing dependency + wrong URL (both contribute)

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**

```
FOR ALL service IN {market-data-service, insight-service} WHERE isBugCondition(service) DO
  result := startService_fixed(service)
  ASSERT httpGet(service, '/actuator/health').status = 200
  ASSERT httpGet(service, '/actuator/health').body.status = 'UP'
  ASSERT dockerHealthCheck(service) = 'healthy'
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed function produces the same result as the original function.

**Pseudocode:**

```
FOR ALL service WHERE NOT isBugCondition(service) DO
  ASSERT config_original(service) = config_fixed(service)
  ASSERT healthCheck_original(service) = healthCheck_fixed(service)
  ASSERT endpoints_original(service) = endpoints_fixed(service)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:

- It can generate many configuration permutations to verify no unintended changes
- It catches edge cases where adding a dependency might conflict with existing endpoints
- It provides strong guarantees that behavior is unchanged for all non-affected services

**Test Plan**: Observe behavior on UNFIXED code first for portfolio-service and api-gateway health checks, then write tests capturing that behavior to verify it continues after the fix.

**Test Cases**:

1. **portfolio-service Health Preservation**: Verify `/api/portfolio/health` continues to return 200 after the fix is applied to other services
2. **api-gateway Health Preservation**: Verify `/actuator/health` on api-gateway continues to return 200 after the fix
3. **Existing Endpoint Preservation — market-data-service**: Verify existing API endpoints (e.g., `/api/market-data/*`) continue to respond correctly after adding actuator
4. **Existing Endpoint Preservation — insight-service**: Verify existing API endpoints (e.g., `/api/insights/*`) continue to respond correctly after adding actuator
5. **Dockerfile Preservation**: Verify no Dockerfile content has changed for any service

### Unit Tests

- Verify `market-data-service` Spring context loads successfully with actuator dependency added
- Verify `insight-service` Spring context loads successfully with actuator dependency added
- Verify `/actuator/health` endpoint returns HTTP 200 with `{"status": "UP"}` for both services
- Verify existing controller endpoints are still mapped and accessible after adding actuator

### Property-Based Tests

- Generate random HTTP paths for market-data-service and verify that only `/actuator/health` and existing mapped endpoints return non-404 responses (no unexpected endpoint exposure)
- Generate random HTTP paths for insight-service and verify the same constraint
- Verify that for any service in the compose file NOT named `market-data-service` or `insight-service`, the health check configuration is byte-identical before and after the fix

### Integration Tests

- Start the full Docker Compose stack and verify all four services reach `healthy` status
- Verify the dependency chain resolves: `portfolio-service` → `market-data-service` + `insight-service` → `api-gateway` all start successfully
- Verify end-to-end API calls through `api-gateway` to `market-data-service` and `insight-service` return expected responses
