# Lambda MongoDB, CI, and Concurrency Bugfix Spec

Date: 2026-04-26
Branch: `fix/lambda-permission-import-drift`

## Summary

This bugfix stabilizes the Spring Boot Lambda backend and CI/CD workflows after the
infra drift fixes in PR #26. The immediate production symptom was
`market-data-service` connecting to `localhost:27017` in AWS even though Lambda had
the Atlas URI configured. The CI symptom was Docker Compose full-stack startup
failing because `portfolio-service` exited while attempting Flyway migrations.

The safe operational goal is to make all four Lambda services healthy under the
current `ap-south-1` account concurrency quota of `10`, then generate controlled
usage evidence for the AWS quota increase request without causing avoidable 5xxs.

## Observed Issues

### 1. AWS `market-data-service` MongoDB localhost fallback

Evidence from CloudWatch for `/aws/lambda/wealth-market-data-service` showed the
Mongo Java driver connecting to `localhost:27017`, followed by startup failure:

- `Exception in monitor thread while connecting to server localhost:27017`
- `java.net.ConnectException: Connection refused`
- `Timed out while waiting for a server that matches ReadPreferenceServerSelector`

At the same time, Lambda environment inspection confirmed the deployed function had
the expected MongoDB Atlas value and host:

- `SPRING_DATA_MONGODB_URI` host: `cluster0.hktwguj.mongodb.net`
- `localhost=False`

### 2. CI Docker Compose startup failure

The latest failed CI artifacts showed `portfolio-service` exiting during startup.
The relevant container log root cause was:

- Flyway failed to initialize because the PostgreSQL connection attempt failed.
- PostgreSQL driver root cause included `UnknownHostException: postgres`.

The Compose stack started Java services before database containers had a verified
ready state, making startup order nondeterministic on GitHub-hosted runners.

### 3. Synthetic monitoring cold-start/concurrency instability

Synthetic monitoring and CI pre-warm steps only warmed the portfolio path. Seeded
E2E flows can touch gateway, portfolio, market data, and insight services, causing
cold-start chains under CloudFront/API Gateway while Lambda concurrency is capped
at `10`.

### 4. Spring AOT risk in Lambda images

All service images ran with `-Dspring.aot.enabled=true`. Because AOT was produced
without explicit production/AWS build-time profile inputs, runtime environment
variables could be bypassed or partially frozen into local/default assumptions.

## Root Causes

1. `market-data-service` was configured with `spring.data.mongodb.uri`, but the
   Spring Boot 4 codebase/tests already use `spring.mongodb.uri`.
2. Terraform only injected the legacy `SPRING_DATA_MONGODB_URI`, not the Boot 4
   canonical `SPRING_MONGODB_URI`.
3. Docker Compose used simple `depends_on` startup ordering rather than database
   health-based readiness gates.
4. Gateway permit-all logic covered only `/api/portfolio/health`; market and
   insight had no equivalent warm-up endpoints.
5. Runtime Spring AOT was enabled before proving build-time and runtime profiles
   were identical.

## Implemented Fixes

### MongoDB binding and Lambda environment

- Updated `market-data-service` YAML to bind MongoDB under `spring.mongodb.uri`.
- Preserved backward compatibility with `SPRING_DATA_MONGODB_URI`.
- Added Terraform Lambda env var `SPRING_MONGODB_URI` while retaining the legacy
  `SPRING_DATA_MONGODB_URI` alias.

### Docker Compose determinism

- Added PostgreSQL and MongoDB healthchecks.
- Made Java services wait for healthy database containers.
- Pointed local service URLs at stable Compose container names:
  - Postgres: `portfolio-db`
  - MongoDB: `market-db`

### Lambda image runtime safety

- Removed `-Dspring.aot.enabled=true` from all four Docker entrypoints.
- Left a comment explaining that AOT can be reintroduced once prod/AWS build-time
  profile inputs match Lambda runtime inputs.

### Health endpoints and gateway bypass

- Added lightweight health endpoints:
  - `GET /api/market/health`
  - `GET /api/insights/health`
- Updated gateway security and custom JWT filter to permit these endpoints.
- Updated synthetic pre-warm workflows to sequentially hit:
  - `/actuator/health`
  - `/api/portfolio/health`
  - `/api/market/health`
  - `/api/insights/health`

Sequential warm-up is intentional to avoid exhausting the hard concurrency quota
of `10` while still warming all backend services.

## Validation Completed

Commands run successfully:

- `docker compose config --quiet`
- `terraform -chdir=infrastructure/terraform fmt -check -recursive`
- `git diff --check`
- `./gradlew :market-data-service:test --tests com.wealth.market.MarketPriceControllerTest :insight-service:test --tests com.wealth.insight.InsightControllerTest --no-daemon --console=plain`
- `./gradlew :api-gateway:integrationTest --tests com.wealth.gateway.PreservationPropertyTest --no-daemon`

One expected local warning remains: if `TF_VAR_internal_api_key` is not exported,
Compose reports it is defaulting to blank. That is not a syntax/config failure.

## Deployment / Handoff Notes

1. Commit and push only the related files from this bugfix.
2. Let GitHub Actions build and deploy the updated Lambda images.
3. Run/apply Terraform so `SPRING_MONGODB_URI` is present in the Lambda function
   environment.
4. Re-check CloudWatch for `wealth-market-data-service`; successful logs should no
   longer mention `localhost:27017` and should use Atlas domain
   `cluster0.hktwguj.mongodb.net`.
5. If services are healthy, run a controlled concurrency warm-up capped below the
   current quota, preferably `8`, to support the AWS quota increase request.

## Do Not Do

- Do not enable provisioned concurrency while the account quota is still `10`.
- Do not run unbounded load against Lambda; use capped concurrency and stop on 5xx
  or throttling.
- Do not remove the legacy `SPRING_DATA_MONGODB_URI` alias until all old images are
  guaranteed to be replaced.