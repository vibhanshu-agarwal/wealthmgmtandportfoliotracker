# Design Document - Azure Market-Data Feed Fix

## Requirement -> Component Map

| Requirement | Component(s) Changed |
|---|---|
| 2.1 ACA Job as production refresh path | `MarketDataRefreshService` (new), `MarketDataRefreshJob` (thinned), `MarketDataRefreshJobRunner` (new) |
| 2.1.1 IaC auditability | `infrastructure/terraform/azure/main.tf` - new `azurerm_container_app_job` |
| 2.2 Gate seed endpoint in prod/azure | `MarketDataSeedController`, `MarketDataSeedService` - add `@ConditionalOnProperty` |
| 2.3 Remove hydration Kafka republish | `StartupHydrationService` - deleted |
| 2.4 Disable `@Scheduled` on azure | `application-azure.yml` - `market-data.refresh.enabled: false` |
| 2.5 + 2.5.1 Reconcile CI seed callers | `.github/workflows/deploy-azure.yml`, `synthetic-monitoring.yml`, `frontend/tests/e2e/azure-synthetic/api-live-smoke.spec.ts` |

---

## 1. Refresh Architecture

### 1.1 New: `MarketDataRefreshService`

**File:** `market-data-service/src/main/java/com/wealth/market/MarketDataRefreshService.java`

Extract the full body of `MarketDataRefreshJob.refreshAllTrackedTickers()` and `resolveTrackedTickers()` into a new `@Service`. Both the `@Scheduled` adapter (local) and the ACA Job runner (Azure) call this single method.

```java
@Service
public class MarketDataRefreshService {

    public void refresh() {
        // full body of current refreshAllTrackedTickers() - unchanged
    }

    List<String> resolveTrackedTickers() {
        // unchanged from current MarketDataRefreshJob.resolveTrackedTickers()
    }
}
```

The service retains the same constructor dependencies (`AssetPriceRepository`, `ExternalMarketDataClient`, `BaselineTickerProperties`, `KafkaTemplate`, `MeterRegistry`).

**Kafka flush requirement (P1 fix):** `MarketDataRefreshJob` currently uses fire-and-forget `kafkaTemplate.send(...)`. In a short-lived ACA Job process the JVM can exit before sends are flushed, and async send failures are silently swallowed. `MarketDataRefreshService.refresh()` MUST:

1. Collect the `CompletableFuture<SendResult<...>>` returned by each `kafkaTemplate.send(...)` call into a list. A **synchronous** exception from `send(...)` (e.g. serialization failure, producer fenced) MUST be treated as a publish failure too - record it (e.g. add a `CompletableFuture.failedFuture(e)` to the list or increment a dedicated `publishFailures` counter) rather than letting the existing broad per-ticker `catch` swallow it into the generic `failed` tally and continue as success.
2. Call `kafkaTemplate.flush()` after the send loop to force the producer to hand off buffered records to the broker.
3. Call `CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join()` after `flush()` to block until all sends complete and surface any async broker-side errors.
4. If **any** publish failed - synchronous (step 1) or asynchronous (step 3) - throw after the loop so the runner exits `1` and Azure retries via `replica_retry_limit`. Per-ticker provider/DB skips that are not publish failures keep their existing non-fatal handling.

Without steps 1 and 3, a synchronous send throw is counted as a non-fatal skip, or a broker rejection after `flush()` returns is never observed; either way the runner exits `0` and the failed refresh appears as success - defeating the retry policy.

### 1.2 Thinned: `MarketDataRefreshJob`

**File:** `market-data-service/src/main/java/com/wealth/market/MarketDataRefreshJob.java`

Reduce to a thin `@Scheduled` adapter that delegates to `MarketDataRefreshService`:

```java
@Component
@ConditionalOnProperty(prefix = "market-data.refresh", name = "enabled", havingValue = "true", matchIfMissing = true)
class MarketDataRefreshJob {

    private final MarketDataRefreshService refreshService;

    MarketDataRefreshJob(MarketDataRefreshService refreshService) {
        this.refreshService = refreshService;
    }

    @Scheduled(cron = "${market-data.refresh.cron:0 0 */1 * * *}")
    void refreshAllTrackedTickers() {
        refreshService.refresh();
    }
}
```

The `@ConditionalOnProperty` guard remains unchanged. On local/non-azure profiles `market-data.refresh.enabled` defaults to `true` (via `matchIfMissing = true`), so the cron fires as before.

### 1.3 New: `MarketDataRefreshJobRunner`

**File:** `market-data-service/src/main/java/com/wealth/market/MarketDataRefreshJobRunner.java`

A `CommandLineRunner` activated by the env var `MARKET_DATA_JOB_RUNNER_ENABLED=true`. This is the entry point for the ACA Job container.

```java
@Component
@ConditionalOnProperty(prefix = "market-data.job-runner", name = "enabled", havingValue = "true")
public class MarketDataRefreshJobRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketDataRefreshJobRunner.class);

    private final MarketDataRefreshService refreshService;
    private final ConfigurableApplicationContext context;

    // Injectable exit seam - defaults to System::exit in production; tests override it
    // with a no-op that records the code, so the Gradle JVM is not terminated.
    IntConsumer exitHandler = System::exit;

    public MarketDataRefreshJobRunner(MarketDataRefreshService refreshService,
                                      ConfigurableApplicationContext context) {
        this.refreshService = refreshService;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        int exitCode = 0;
        try {
            log.info("MarketDataRefreshJobRunner: starting one-shot refresh");
            refreshService.refresh();   // flush() + future await called inside refresh() before return
            log.info("MarketDataRefreshJobRunner: refresh complete, shutting down");
        } catch (Exception e) {
            log.error("MarketDataRefreshJobRunner: refresh failed", e);
            exitCode = 1;
        } finally {
            // Java lambdas require effectively-final capture; copy exitCode before the lambda.
            // Exit goes through the injectable exitHandler (System::exit by default) so tests
            // can capture the code without terminating the JVM.
            final int finalExitCode = exitCode;
            exitHandler.accept(SpringApplication.exit(context, () -> finalExitCode));
        }
    }
}
```

**Shutdown semantics (P1 fix):** the `finally` block calls `exitHandler.accept(SpringApplication.exit(context, () -> finalExitCode))` (where `exitHandler` defaults to `System::exit`). This ensures two things: (1) `SpringApplication.exit` triggers `@PreDestroy` lifecycle hooks - giving the Kafka producer time to flush any remaining sends before the client closes - and (2) the return value of `SpringApplication.exit` is forwarded to the exit handler, so a failed refresh exits with code `1` and a successful one with `0`. If the return value were discarded (e.g. a bare `SpringApplication.exit(...)` with no `System.exit`), the process would always exit `0`, which defeats `replica_retry_limit = 1` - Azure would see every failed refresh as success and never retry. Combined with the `kafkaTemplate.flush()` inside `refresh()`, this closes all three gaps: premature exit before publish, hanging non-web threads, and silent failure masking. See the test-seam note below for why the exit goes through `exitHandler` rather than a direct `System.exit` call.

The runner is activated by setting `market-data.job-runner.enabled=true` in the ACA Job container environment, not via a command-line argument, so the existing `application-azure.yml` pattern for profile-scoped config applies.

`SPRING_MAIN_WEB_APPLICATION_TYPE=none` is set in the ACA Job env (see Section 3) to suppress the embedded server from starting.

**Test seam (testability fix):** calling `System.exit(...)` directly inside `run(...)` would terminate the Gradle test JVM when a unit/integration test exercises the runner. The exit call MUST go through an injectable seam so tests can substitute a no-op that records the code instead of killing the JVM. Implement as a package-private `IntConsumer exitHandler` field defaulting to `System::exit`:

```java
// default in production; overridden in tests to capture the code without exiting the JVM
IntConsumer exitHandler = System::exit;

// in the finally block:
final int finalExitCode = exitCode;
exitHandler.accept(SpringApplication.exit(context, () -> finalExitCode));
```

Unit tests set `runner.exitHandler = capturedCode::set` and assert the captured value (`0` on success, `1` on failure). The integration test (Section 5.2) that needs a true process exit runs the app in a forked JVM (Gradle `JavaExec` / a separate `@SpringBootTest` process) so a real `System.exit` does not abort the test runner.

---

## 2. Seed Gating, Hydration Removal, and Config

### 2.1 Gate `MarketDataSeedController` and `MarketDataSeedService` in prod/azure

Add `@ConditionalOnProperty` to both the controller and the service so they are not registered as beans when the active profile includes `prod` or `azure`.

**`MarketDataSeedController`** - add at class level:

```java
@ConditionalOnProperty(prefix = "market-data.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
```

**`MarketDataSeedService`** - add the same annotation at class level.

This reuses the existing `market.seed.enabled` / `market-data.seed.enabled` config key pattern already present in `application-azure.yml`. The controller and service are currently **not** guarded by that flag at the bean level; this change makes the flag effective at registration time rather than relying only on the `InternalApiKeyFilter`.

**`application-azure.yml`** - add/update the key to match the new `@ConditionalOnProperty` prefix:

```yaml
market-data:
  seed:
    enabled: false   # gates MarketDataSeedController + MarketDataSeedService out of prod/azure
```

**`application-prod.yml`** - add the same gate so the seeder is also off for any future cloud profile that activates `prod` without an explicit cloud overlay:

```yaml
market-data:
  seed:
    enabled: false
```

Local dev (`application.yml` or no override) defaults to `true` via `matchIfMissing = true`, preserving the existing seed workflow for local and Docker Compose.

### 2.2 Delete `StartupHydrationService`

**File to delete:** `market-data-service/src/main/java/com/wealth/market/StartupHydrationService.java`

`StartupHydrationService` holds no local in-memory cache. Its sole action is `kafkaTemplate.send(...)`, and the "caches" named in its Javadoc are downstream read-model consumers. Removing it leaves no in-process functionality behind.

**Config cleanup (P2 fix):** Remove the now-dead `market-data.hydration.enabled` property from all three files that set it:

| File | Key to remove |
|---|---|
| `market-data-service/src/main/resources/application.yml` | `market-data.hydration.enabled` |
| `market-data-service/src/main/resources/application-azure.yml` | `market-data.hydration.enabled: true` |
| `market-data-service/src/main/resources/application-prod.yml` | `market-data.hydration.enabled: true` |

### 2.3 Disable `@Scheduled` adapter on azure

**`application-azure.yml`** - add:

```yaml
market-data:
  refresh:
    enabled: false   # ACA Job is the sole production refresh path; @Scheduled adapter suppressed
```

The existing `cron` override in `application-azure.yml` becomes redundant once `enabled: false` suppresses `MarketDataRefreshJob`, but leave it commented for documentation clarity.

---

## 3. Infrastructure - ACA Job (`infrastructure/terraform/azure/main.tf`)

### 3.1 New: `azurerm_container_app_job` for production refresh

Add the following resource block to `main.tf` after the `module.market_data_service` block. The stale comment on `module.market_data_service` ("scheduled refresh is enabled on ACA (long-lived containers)") must also be corrected in the same change to read: "scheduled refresh runs via the `azurerm_container_app_job.market_data_refresh` Job - not via the long-lived container."

**ACR identity (P1 fix):** The existing container-app module uses each app's own `SystemAssigned` managed identity with an `AcrPull` role assignment for ACR access. The Job must follow the same pattern - it needs its own `identity { type = "SystemAssigned" }` block and a matching `azurerm_role_assignment`. Using `azurerm_container_app_environment.main.id` as the identity (as was in an earlier draft) is incorrect and will fail ACR pulls.

```hcl
# market-data-service refresh Job - runs daily at 08:00 UTC.
# Boots the JVM, calls MarketDataRefreshService.refresh(), then exits cleanly.
# This is the sole production refresh path; the @Scheduled adapter is disabled
# in application-azure.yml (market-data.refresh.enabled: false).
resource "azurerm_container_app_job" "market_data_refresh" {
  name                         = "market-data-refresh-job"
  resource_group_name          = azurerm_resource_group.main.name
  location                     = azurerm_resource_group.main.location
  container_app_environment_id = azurerm_container_app_environment.main.id

  # SystemAssigned identity - required for ACR pull (see role assignment below).
  identity {
    type = "SystemAssigned"
  }

  # Cron trigger: daily at 08:00 UTC.
  schedule_trigger_config {
    cron_expression          = "0 8 * * *"
    parallelism              = 1
    replica_completion_count = 1
  }

  # Single-concurrent-execution policy: retry once on failure, 10-minute timeout.
  replica_retry_limit        = 1
  replica_timeout_in_seconds = 600

  # Image rollout is owned by deploy-azure.yml (`az containerapp job update`), exactly as
  # the container-app module owns image rollout for the long-running services. Ignore the
  # image field so a later `terraform apply` does not revert the Job back to var.image_tag
  # and break the lockstep rollout in Section 3.2. Mirrors the module's lifecycle rule.
  lifecycle {
    ignore_changes = [
      template[0].container[0].image,
    ]
  }

  # Registry auth at resource level - mirrors the container-app module pattern.
  # SystemAssigned identity is granted AcrPull below; `identity = "system"` tells
  # the ACA runtime to use that identity when pulling from this registry.
  registry {
    server   = azurerm_container_registry.main.login_server
    identity = "system"
  }

  # Secrets at resource level - same pattern as the container-app module.
  secret {
    name  = "spring-data-mongodb-uri"
    value = var.mongodb_connection_string
  }
  secret {
    name  = "kafka-bootstrap-servers"
    value = var.kafka_bootstrap_servers
  }
  secret {
    name  = "kafka-sasl-username"
    value = var.kafka_sasl_username
  }
  secret {
    name  = "kafka-sasl-password"
    value = var.kafka_sasl_password
  }
  secret {
    name  = "internal-api-key"
    value = var.internal_api_key
  }

  template {
    container {
      name   = "market-data-refresh"
      image  = "${azurerm_container_registry.main.login_server}/market-data-service:${var.image_tag}"
      cpu    = 0.5
      memory = "1Gi"

      # Non-sensitive env - activates prod+azure profiles and the one-shot runner.
      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = "prod,azure"
      }
      env {
        name  = "SPRING_MAIN_WEB_APPLICATION_TYPE"
        value = "none"
      }
      env {
        name  = "MARKET_DATA_JOB_RUNNER_ENABLED"
        value = "true"
      }

      # Sensitive env - same secrets as the long-running market-data-service container.
      env {
        name        = "SPRING_DATA_MONGODB_URI"
        secret_name = "spring-data-mongodb-uri"
      }
      env {
        name        = "KAFKA_BOOTSTRAP_SERVERS"
        secret_name = "kafka-bootstrap-servers"
      }
      env {
        name        = "KAFKA_SASL_USERNAME"
        secret_name = "kafka-sasl-username"
      }
      env {
        name        = "KAFKA_SASL_PASSWORD"
        secret_name = "kafka-sasl-password"
      }
      env {
        name        = "INTERNAL_API_KEY"
        secret_name = "internal-api-key"
      }
    }
  }
}

# AcrPull role for the Job's SystemAssigned identity.
# Mirrors the pattern used by the container-app module for each long-running service.
resource "azurerm_role_assignment" "market_data_refresh_job_acr_pull" {
  scope                = azurerm_container_registry.main.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_container_app_job.market_data_refresh.identity[0].principal_id
}
```

### 3.2 Job image rollout on deploy (P1 fix)

The `deploy-azure.yml` workflow updates running Container Apps via `az containerapp update` using `${{ github.sha }}` as the image tag. The new Job is not in that matrix, so without an explicit update step it will keep whatever image tag Terraform last applied - drifting from the deployed service image.

Add a step to the `deploy` job in `.github/workflows/deploy-azure.yml` that runs after all matrix services are updated, targeting the Job specifically:

```yaml
- name: Update market-data-refresh Job image
  if: matrix.service == 'market-data-service'
  run: |
    az containerapp job update \
      --name market-data-refresh-job \
      --resource-group $AZURE_RG \
      --image $ACR_NAME.azurecr.io/market-data-service:${{ github.sha }}
```

This keeps the Job image in lockstep with the `market-data-service` container image on every deploy.

**Terraform image ownership (P2 fix):** Because `deploy-azure.yml` owns Job image rollout via `az containerapp job update`, the `azurerm_container_app_job` resource MUST mirror the container-app module's `lifecycle.ignore_changes` on the image field - otherwise the next `terraform apply` reverts the Job back to `var.image_tag`, undoing the deploy. Add to the Job resource:

```hcl
  lifecycle {
    ignore_changes = [
      template[0].container[0].image,
    ]
  }
```

This matches `infrastructure/terraform/azure/modules/container-app/main.tf`, where image updates are deferred to the deploy workflow while Terraform manages all other fields.


---

## 4. CI Reconciliation - Seed Callers

### 4.1 `deploy-azure.yml` seed job - `global-setup.ts`

The `seed` job in `.github/workflows/deploy-azure.yml` runs:

```yaml
run: npx ts-node --project tests/e2e/tsconfig.e2e-test.json tests/e2e/global-setup.ts
```

**The problem:** `global-setup.ts` `runSeeding()` calls `POST /api/internal/market-data/seed`. Once the endpoint is gated off under `prod,azure` (Section 2.1), this call will receive `404` or a bean-not-found error and fail the seed job.

(The workflow path itself needs no change: the step runs with `working-directory: frontend`, so `tests/e2e/global-setup.ts` already resolves to the real `frontend/tests/e2e/global-setup.ts`.)

**Fix:** Remove only the market-data seed step from `global-setup.ts` `runSeeding()` when the target is an Azure/prod environment. The portfolio seed and insight seed steps are unaffected and must remain everywhere. Per `bugfix.md` requirement 3.5, local and Docker Compose environments must continue to seed market-data via the deterministic seeder.

The correct approach is an environment-aware skip inside `runSeeding()` driven by an explicit flag, not a domain-name pattern (which would break if the custom domain ever changes, and would not cover any other future `prod`-profile deployment):

```typescript
// SKIP_MARKET_DATA_SEED=true in prod/azure CI environments - the ACA Job is the price source.
// Unset or false for local and non-prod workflows to preserve deterministic seeding (bugfix.md 3.5).
const SKIP_MARKET_DATA_SEED =
    (process.env.SKIP_MARKET_DATA_SEED ?? "").toLowerCase() === "true";

if (!SKIP_MARKET_DATA_SEED) {
    const marketResult = await seedFetch(
        "Market data seeding",
        `${GATEWAY_BASE}/api/internal/market-data/seed`,
        { userId: TEST_USER_ID },
    );
    await assertSeedOk("Market data seeding", marketResult);
    console.log(`[${timestamp()}] Market data seeded.`);
}
```

Set `SKIP_MARKET_DATA_SEED: "true"` in the `env` block of every CI step that runs against a prod-profile backend: the `deploy-azure.yml` seed job, the `synthetic-monitoring.yml` shell seeding step, and the Playwright "Run Synthetic Monitoring" steps (Azure and AWS) that invoke `global-setup.ts` via `playwright.config.ts`. Leave it unset (defaulting to `false`) for local, Docker Compose, and any non-prod E2E workflow. This satisfies both 2.5 (live Azure callers disabled) and 3.5 (local/demo seeding preserved) without coupling the skip logic to a specific domain name.

The corrected `deploy-azure.yml` seed step path and working directory:

```yaml
- name: Seed live Azure environment (vibhanshu-ai-portfolio.dev)
  working-directory: frontend
  env:
    NEXT_PUBLIC_API_BASE_URL: https://api.vibhanshu-ai-portfolio.dev
    SKIP_BACKEND_HEALTH_CHECK: "true"
    SKIP_MARKET_DATA_SEED: "true"   # endpoint gated off in prod/azure - ACA Job is the price source
    INTERNAL_API_KEY: ${{ secrets.TF_VAR_INTERNAL_API_KEY }}
    E2E_TEST_USER_ID: ${{ secrets.E2E_TEST_USER_ID || '00000000-0000-0000-0000-000000000e2e' }}
  run: npx ts-node --project tests/e2e/tsconfig.e2e-test.json tests/e2e/global-setup.ts
```

(This was already `working-directory: frontend`, so the path `tests/e2e/global-setup.ts` resolves correctly from that working directory. No path change needed in the workflow file itself - the path is correct relative to `frontend/`. The source of confusion was the `tests/e2e/` reference being read as repo-root-relative.)

### 4.2 `frontend/tests/e2e/azure-synthetic/api-live-smoke.spec.ts` (P1 fix)

`api-live-smoke.spec.ts` directly posts to `/api/internal/market-data/seed` as part of a live Azure smoke assertion (the call is not conditional on profile or environment flag). Once the endpoint is gated off in production, this test will fail on every Azure synthetic run.

**Fix:** Remove the market-data seed `POST` and its assertions from `api-live-smoke.spec.ts`. The test should assert that the endpoint is **not reachable** (returns `404` or `503`) under the Azure profile, or the step should be omitted entirely from the Azure-synthetic test suite. The portfolio and insight seed assertions in the same test are unaffected.

### 4.3 `synthetic-monitoring.yml` - remove market-data seed call (P1 fix)

Contrary to the earlier conclusion, `synthetic-monitoring.yml` does call `seed_endpoint "market-data" "/api/internal/market-data/seed"` at line 166. Once the endpoint is gated off in production this step will fail with a non-200 status and abort the monitoring run.

**Fix:** Remove the `seed_endpoint "market-data" ...` line from the shell seed block in `synthetic-monitoring.yml`. The portfolio seed call in the same block is unaffected.

Additionally, the Playwright "Run Azure Synthetic Monitoring" step (and any AWS synthetic equivalent) runs `globalSetup` from `playwright.config.ts`, which executes `global-setup.ts` `runSeeding()` against the prod-profile backend. Those steps MUST also set `SKIP_MARKET_DATA_SEED: "true"` in their `env` block, or `runSeeding()` will hit the disabled endpoint and fail. Removing only the shell `seed_endpoint` line is not sufficient - the Playwright global-setup path is a second, independent caller.

---

## 5. Testing

### 5.1 Unit tests

| Class | Test |
|---|---|
| `MarketDataRefreshService` | Verify `flush()` is called on `kafkaTemplate` after the send loop. Mock `kafkaTemplate.send()` to return a `CompletableFuture`; assert `flush()` is invoked exactly once after all tickers are processed. |
| `MarketDataRefreshService` (async failure) | Mock one `kafkaTemplate.send()` to return a future completed exceptionally. Assert that `refresh()` throws (via `CompletableFuture.allOf(...).join()`) rather than returning normally, so a broker-side send failure is not silently swallowed. |
| `MarketDataRefreshService` (synchronous failure) | Mock one `kafkaTemplate.send()` to throw synchronously (e.g. serialization error). Assert the throw is recorded as a publish failure and `refresh()` throws at the end rather than counting it as a non-fatal skip. |
| `MarketDataRefreshJobRunner` (exit on failure) | Inject a no-op `exitHandler` that captures the code. When `refreshService.refresh()` throws, assert the captured code is `1`. The real `System.exit` is never called. Confirms the failed-refresh -> non-zero-exit -> `replica_retry_limit` retry path. |
| `MarketDataRefreshJobRunner` (exit on success) | Inject a capturing `exitHandler`; on a clean `refresh()` assert the captured code is `0` and that `refresh()` was invoked. Verify the exit handler is called from the `finally` block (also on exception). |
| `MarketDataRefreshJob` | Verify it delegates to `MarketDataRefreshService.refresh()` and does not contain any fetch/publish logic directly. |
| `MarketDataSeedController` / `MarketDataSeedService` | Verify beans are absent from the application context when `market-data.seed.enabled=false`. Use `@SpringBootTest` with a test properties override. |

### 5.2 Integration tests (`@Tag("integration")`)

- **Refresh end-to-end:** Start `market-data-service` with a Testcontainers Kafka and MongoDB. Invoke `MarketDataRefreshService.refresh()` directly. Assert that `PriceUpdatedEvent` messages appear on the `market-prices` Kafka topic and that `AssetPrice` documents are updated in MongoDB.
- **Job runner exit (forked JVM):** Because the runner calls a real `System.exit` via its default `exitHandler`, this test MUST run the app in a forked JVM (Gradle `JavaExec` or a separate process), not in the Gradle test JVM. Launch with `SPRING_MAIN_WEB_APPLICATION_TYPE=none` and `market-data.job-runner.enabled=true`, and assert: (a) on a healthy run the process exits `0` and events were published before exit; (b) with an injected publish failure the process exits `1` (the `replica_retry_limit` retry path). Forking is what makes asserting the real exit code safe.
- **Seed gating:** Start with `market-data.seed.enabled=false`. Assert `POST /api/internal/market-data/seed` returns `404`.

### 5.3 Architecture tests

Add an ArchUnit rule asserting that `MarketDataRefreshJobRunner` does not import any Azure-specific SDK classes (enforces multi-cloud portability guardrail 3.4).

