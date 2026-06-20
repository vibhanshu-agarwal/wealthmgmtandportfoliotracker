# Implementation Plan - Azure Market-Data Feed Fix

Each task references the requirement(s) from `bugfix.md` and the design section from `design.md` it implements. Tasks are ordered so that dependencies precede dependents. Backend code (Sec 1-2) lands before infrastructure (Sec 3) and CI (Sec 4); tests (Sec 5) are written alongside each unit of work and finalized at the end.

---

## 1. Refresh logic extraction

- [x] 1.1 Create `MarketDataRefreshService` (`market-data-service/src/main/java/com/wealth/market/MarketDataRefreshService.java`)
  - Move the full body of `MarketDataRefreshJob.refreshAllTrackedTickers()` and `resolveTrackedTickers()` into a new `@Service`, preserving constructor deps (`AssetPriceRepository`, `ExternalMarketDataClient`, `BaselineTickerProperties`, `KafkaTemplate`, `MeterRegistry`).
  - Collect each `kafkaTemplate.send(...)` `CompletableFuture` into a list; call `kafkaTemplate.flush()` after the send loop, then `CompletableFuture.allOf(futures...).join()`.
  - Publish-failure semantics: a SYNCHRONOUS exception from `send(...)` must be recorded as a publish failure (e.g. add `CompletableFuture.failedFuture(e)` to the list or increment a dedicated `publishFailures` counter), NOT swallowed by the existing broad per-ticker catch into the generic `failed` tally. After the loop, if any publish failed (sync or async), throw so the caller/runner can exit non-zero. Per-ticker provider/DB skips that are not publish failures keep their existing non-fatal handling.
  - _Requirements: 2.1 - Design: Sec 1.1_

- [x] 1.2 Thin `MarketDataRefreshJob` to a `@Scheduled` adapter
  - Reduce the class to a constructor-injected delegate that calls `refreshService.refresh()` from the `@Scheduled` method. Keep the `@ConditionalOnProperty(prefix = "market-data.refresh", name = "enabled", matchIfMissing = true)` guard unchanged.
  - _Requirements: 2.1, 2.4 - Design: Sec 1.2_

- [x] 1.3 Create `MarketDataRefreshJobRunner` (`.../MarketDataRefreshJobRunner.java`)
  - Implement `CommandLineRunner`, gated by `@ConditionalOnProperty(prefix = "market-data.job-runner", name = "enabled", havingValue = "true")` (env `MARKET_DATA_JOB_RUNNER_ENABLED=true`; no CLI arg).
  - Track an `int exitCode` (0 success, 1 on caught exception). Route the exit call through an injectable seam - a package-private `IntConsumer exitHandler = System::exit` field - and in `finally` call `exitHandler.accept(SpringApplication.exit(context, () -> finalExitCode))`. This lets tests substitute a no-op handler so they do not terminate the Gradle JVM.
  - _Requirements: 2.1 - Design: Sec 1.3_

## 2. Seed gating, hydration removal, profile config

- [x] 2.1 Gate the seed endpoint out of prod/azure
  - Add `@ConditionalOnProperty(prefix = "market-data.seed", name = "enabled", havingValue = "true", matchIfMissing = true)` to both `MarketDataSeedController` and `MarketDataSeedService`.
  - _Requirements: 2.2, 3.5 - Design: Sec 2.1_

- [x] 2.2 Delete `StartupHydrationService`
  - Remove `market-data-service/src/main/java/com/wealth/market/StartupHydrationService.java` entirely (no local cache exists; its only action was the Kafka republish).
  - _Requirements: 2.3 - Design: Sec 2.2_

- [x] 2.3 Profile config updates
  - `application-azure.yml`: add `market-data.seed.enabled: false`, set `market-data.refresh.enabled: false`, and remove the now-dead `market-data.hydration.enabled: true`.
  - `application-prod.yml`: add `market-data.seed.enabled: false`; remove `market-data.hydration.enabled: true`.
  - `application.yml`: remove `market-data.hydration.enabled`.
  - _Requirements: 2.2, 2.3, 2.4, 3.5 - Design: Sec 2.1, 2.2, 2.3_

## 3. Infrastructure - ACA Job

- [x] 3.1 Add `azurerm_container_app_job.market_data_refresh` to `infrastructure/terraform/azure/main.tf`
  - Schedule trigger `0 8 * * *`, `parallelism = 1`, `replica_completion_count = 1`, `replica_retry_limit = 1`, `replica_timeout_in_seconds = 600`.
  - `identity { type = "SystemAssigned" }`; `registry` and `secret` blocks at resource level (not nested in `template`), mirroring the container-app module.
  - Image `${acr_login_server}/market-data-service:${var.image_tag}`; env: `SPRING_PROFILES_ACTIVE=prod,azure`, `SPRING_MAIN_WEB_APPLICATION_TYPE=none`, `MARKET_DATA_JOB_RUNNER_ENABLED=true`; secret env parity with `module.market_data_service` (Mongo URI, Kafka bootstrap/SASL user+pass, internal API key).
  - _Requirements: 2.1.1, 3.1, 3.4 - Design: Sec 3.1_

- [x] 3.2 Add `lifecycle { ignore_changes = [template[0].container[0].image] }` to the Job resource
  - Mirror the container-app module so a later `terraform apply` does not revert the Job image back to `var.image_tag` and break the deploy-time lockstep rollout (3.4). Without this rule the lockstep model in design Sec 3.2 is defeated.
  - _Requirements: 2.1.1 - Design: Sec 3.1_

- [x] 3.3 Add `azurerm_role_assignment` granting the Job's identity `AcrPull` on the ACR
  - `principal_id = azurerm_container_app_job.market_data_refresh.identity[0].principal_id`.
  - _Requirements: 2.1.1 - Design: Sec 3.1_

- [x] 3.4 Correct the stale Terraform comment on `module.market_data_service`
  - Change "scheduled refresh is enabled on ACA (long-lived containers)" to point at the Job as the refresh path.
  - _Requirements: 2.1.1 - Design: Sec 3.1_

- [x] 3.5 Add Job image rollout step to `.github/workflows/deploy-azure.yml`
  - In the `deploy` job, add an `az containerapp job update --name market-data-refresh-job --image ...:${{ github.sha }}` step guarded by `if: matrix.service == 'market-data-service'`, so the Job image stays in lockstep with the service image.
  - _Requirements: 2.1.1 - Design: Sec 3.2_

## 4. CI seed-caller reconciliation

- [x] 4.1 Environment-aware market-data seed skip in `frontend/tests/e2e/global-setup.ts`
  - Read `SKIP_MARKET_DATA_SEED`; wrap the market-data seed call in `if (!SKIP_MARKET_DATA_SEED)`. Leave portfolio + insight seeding unconditional. (No workflow path change needed - the step runs with `working-directory: frontend`.)
  - _Requirements: 2.5, 3.5 - Design: Sec 4.1_

- [x] 4.2 Set `SKIP_MARKET_DATA_SEED: "true"` in prod-profile CI steps
  - Add to the `env` block of the `deploy-azure.yml` seed job and the `synthetic-monitoring.yml` Playwright/seeding steps that target the live stack. Leave unset for local/non-prod.
  - _Requirements: 2.5, 3.5 - Design: Sec 4.1_

- [x] 4.3 Remove the market-data seed call from `.github/workflows/synthetic-monitoring.yml`
  - Delete the `seed_endpoint "market-data" "/api/internal/market-data/seed"` line (~line 166); keep the portfolio seed call.
  - _Requirements: 2.5 - Design: Sec 4.3_

- [x] 4.4 Reconcile `frontend/tests/e2e/azure-synthetic/api-live-smoke.spec.ts`
  - Remove the direct `POST /api/internal/market-data/seed` and its assertions (or assert the endpoint is not reachable under the Azure profile). Leave portfolio/insight assertions intact.
  - _Requirements: 2.5 - Design: Sec 4.2_

## 5. Tests

- [x] 5.1 Unit tests
  - `MarketDataRefreshService`: assert `flush()` then future-await happens; a failed send future (async) AND a synchronous `send(...)` throw each cause `refresh()` to throw.
  - `MarketDataRefreshJob`: delegates to the service, contains no fetch/publish logic.
  - `MarketDataRefreshJobRunner`: inject a no-op `exitHandler` capturing the code; assert `refresh()` then exit invoked in `finally` (also on exception), code `0` on success and `1` on failure. Does not call the real `System.exit`.
  - `MarketDataSeedController`/`MarketDataSeedService`: beans absent when `market-data.seed.enabled=false`.
  - _Requirements: 2.1, 2.2 - Design: Sec 5.1_

- [x] 5.2 Integration tests (`@Tag("integration")`, Testcontainers)
  - Refresh end-to-end: `refresh()` publishes `PriceUpdatedEvent` to `market-prices` and updates `AssetPrice` in MongoDB.
  - Job runner exit: run the app in a FORKED JVM (Gradle `JavaExec` / separate process) with `SPRING_MAIN_WEB_APPLICATION_TYPE=none` + `market-data.job-runner.enabled=true`; assert real process exit `0` after events published, and exit `1` when a publish failure is injected. Forking is required so a real `System.exit` does not abort the Gradle test runner.
  - Seed gating: with `market-data.seed.enabled=false`, `POST /api/internal/market-data/seed` returns `404`.
  - _Requirements: 2.1, 2.2 - Design: Sec 5.2_

- [x] 5.3 Architecture test
  - ArchUnit rule: `MarketDataRefreshJobRunner` imports no Azure-specific SDK classes (multi-cloud guardrail).
  - _Requirements: 3.4 - Design: Sec 5.3_

- [x] 5.4 Full verification
  - Run `./gradlew test` then `./gradlew integrationTest`; run `terraform validate` / `terraform fmt -check` in `infrastructure/terraform/azure`; lint the touched workflow YAML and `global-setup.ts`.
  - _Requirements: all - Design: Sec 5_
