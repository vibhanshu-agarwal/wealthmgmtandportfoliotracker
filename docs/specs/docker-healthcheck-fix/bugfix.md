# Bugfix Requirements Document

## Introduction

The CI/CD pipeline (`e2e-full-stack#95`) fails because `market-data-service` and `insight-service` Docker containers are marked unhealthy. Both services lack the `spring-boot-starter-actuator` dependency, so the `/actuator/health` endpoint does not exist. Additionally, the `docker-compose.yml` health checks for these services hit the root path (`/`) which returns 404 since there is no mapping for it. The Dockerfiles also set `AWS_LWA_READINESS_CHECK_PATH=/actuator/health`, which references the same non-existent endpoint. This cascading failure prevents `insight-service` and `api-gateway` from starting because they depend on upstream services being healthy.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN `market-data-service` container starts in Docker Compose THEN the system health check (`curl -sf http://localhost:8082/`) returns HTTP 404 because no controller is mapped to the root path, causing the container to be marked unhealthy after retries are exhausted

1.2 WHEN `insight-service` container starts in Docker Compose THEN the system health check (`curl -sf http://localhost:8083/`) returns HTTP 404 because no controller is mapped to the root path, causing the container to be marked unhealthy after retries are exhausted

1.3 WHEN `insight-service` depends on `market-data-service` with `condition: service_healthy` THEN the system fails to start `insight-service` with error "dependency failed to start: container market-data-service is unhealthy"

1.4 WHEN `api-gateway` depends on `market-data-service` and `insight-service` with `condition: service_healthy` THEN the system fails to start `api-gateway` because its upstream dependencies are unhealthy

1.5 WHEN `market-data-service` or `insight-service` is deployed to AWS Lambda THEN the Lambda Web Adapter readiness check (`AWS_LWA_READINESS_CHECK_PATH=/actuator/health`) fails because the `spring-boot-starter-actuator` dependency is missing and the `/actuator/health` endpoint does not exist

### Expected Behavior (Correct)

2.1 WHEN `market-data-service` container starts in Docker Compose THEN the system SHALL expose an `/actuator/health` endpoint that returns HTTP 200 with status UP, and the Docker Compose health check SHALL target this endpoint so the container is marked healthy

2.2 WHEN `insight-service` container starts in Docker Compose THEN the system SHALL expose an `/actuator/health` endpoint that returns HTTP 200 with status UP, and the Docker Compose health check SHALL target this endpoint so the container is marked healthy

2.3 WHEN `insight-service` depends on `market-data-service` with `condition: service_healthy` THEN the system SHALL start `insight-service` successfully because `market-data-service` passes its health check

2.4 WHEN `api-gateway` depends on `market-data-service` and `insight-service` with `condition: service_healthy` THEN the system SHALL start `api-gateway` successfully because all upstream dependencies pass their health checks

2.5 WHEN `market-data-service` or `insight-service` is deployed to AWS Lambda THEN the Lambda Web Adapter readiness check (`AWS_LWA_READINESS_CHECK_PATH=/actuator/health`) SHALL succeed because the actuator health endpoint exists

### Unchanged Behavior (Regression Prevention)

3.1 WHEN `portfolio-service` container starts in Docker Compose THEN the system SHALL CONTINUE TO pass its health check at `/api/portfolio/health` and be marked healthy

3.2 WHEN `api-gateway` container starts in Docker Compose THEN the system SHALL CONTINUE TO pass its health check at `/actuator/health` and be marked healthy

3.3 WHEN `market-data-service` receives API requests on its existing endpoints (e.g., `/api/market-data/*`) THEN the system SHALL CONTINUE TO handle those requests correctly without interference from the actuator dependency

3.4 WHEN `insight-service` receives API requests on its existing endpoints (e.g., `/api/insights/*`) THEN the system SHALL CONTINUE TO handle those requests correctly without interference from the actuator dependency

3.5 WHEN any service Dockerfile is used for AWS Lambda deployment THEN the system SHALL CONTINUE TO use the Lambda Web Adapter entrypoint and the same `AWS_LWA_PORT` configuration
