# Configuration Duplication Audit — 2026-04-23

**Scope:** Backend YAML configs, `docker-compose.yml`, Terraform (root + modules), frontend environment variables, CI workflow env blocks.

**Goal:** Identify duplicated variables and configuration that create drift risk, and propose minimal-change fixes that can be applied without major code restructuring.

**Method:** Static inspection of the current workspace. Each finding references exact file paths and line ranges where possible. Nothing in this document has been changed yet.

---

## How to read this document

Findings are grouped into three tiers:

- **Tier 1 — Fix first.** Pure pass-through duplication. Zero or near-zero behavioral risk. Highest drift-reduction per line changed.
- **Tier 2 — Fix next.** Touches more than one file or layer, but still low risk and self-contained.
- **Tier 3 — Cleanup / document-only.** Dead code or cosmetic deduplication.

Each finding states: **what's duplicated**, **where**, **drift risk**, and the **smallest safe fix**.

---

## Tier 1 — Fix first

### 1.1 Terraform: root and compute module declare the same variables twice

Every variable consumed by the compute module is declared in both places:

- `infrastructure/terraform/variables.tf` (root)
- `infrastructure/terraform/modules/compute/variables.tf`

`infrastructure/terraform/main.tf` then wires them through as pure pass-through (`var.X → var.X`). There is no abstraction boundary — only duplication.

**Duplicated pairs (root ↔ compute):**

| Variable | Divergence |
|---|---|
| `artifact_bucket_name` | Different descriptions |
| `s3_key_api_gateway` | Both say "unused"; present in both |
| `api_gateway_image_uri` | Redundant description + example |
| `portfolio_image_uri`, `market_data_image_uri`, `insight_image_uri` | Same in both |
| `api_gateway_memory`, `portfolio_memory_size`, `market_data_memory_size`, `insight_service_memory_size` | Root = `nullable, default=null`; module = non-nullable, no default. **Semantic drift.** |
| `postgres_connection_string`, `postgres_username`, `postgres_password` | Descriptions differ between files |
| `mongodb_connection_string` | Root has description, module has none |
| `auth_jwk_uri` | Root has description, module has none |
| `cloudfront_origin_secret` | Root has description, module has none |
| `redis_url`, `kafka_bootstrap_servers`, `kafka_sasl_username`, `kafka_sasl_password` | Descriptions differ |
| `internal_api_key` | Descriptions differ |
| `portfolio_function_url`, `market_data_function_url`, `insight_function_url` | Root has description, module has none |
| `enable_aws_managed_database` | Root has description, module has no description |
| `lambda_vpc_subnet_ids`, `lambda_vpc_security_group_ids` | Descriptions differ |
| `lambda_timeout` | Root: `nullable=true, default=null`. Module: `default=60`. **Semantic drift — two different "defaults" exist.** |
| `lambda_architecture` | Root: `nullable=true, default=null`. Module: `default="arm64"` + validation. **Semantic drift — validation lives only in module.** |
| `enable_provisioned_concurrency` | Descriptions differ |

**Drift risk:** High. `lambda_timeout` and `lambda_architecture` already have genuine semantic drift — the "real" default lives in the module, but the root variable suggests it lives in `locals.tf`. A reader cannot tell which is authoritative without tracing `main.tf`.

**Smallest safe fix (no structural change):**

1. Treat the **root** `variables.tf` as authoritative for description, validation, type, and `nullable`.
2. Make the **module** `variables.tf` minimal: `type` + (optionally) `sensitive`. No descriptions. No defaults on variables that are set from root.
3. For `lambda_timeout` and `lambda_architecture`: remove the module-level defaults (`60`, `"arm64"`). These are already resolved at root via `coalesce(var.X, local.lambda_defaults.X)`. Move the `validation {}` block for `lambda_architecture` to the root `variables.tf` so it applies at the true input boundary.

**Expected `terraform plan` diff:** none (these variables are never unset in practice).

---

### 1.2 Backend: identical `spring.kafka.*` block in three prod YAMLs

The **`spring.kafka.*` block** (not the entire file) is identical across all three prod YAMLs:

- `insight-service/src/main/resources/application-prod.yml` lines 12–26
- `portfolio-service/src/main/resources/application-prod.yml` lines 28–42
- `market-data-service/src/main/resources/application-prod.yml` lines 24–38

Duplicated keys: `spring.kafka.bootstrap-servers`, `spring.kafka.ssl.trust-store-location`, `spring.kafka.ssl.trust-store-password`, `spring.kafka.properties.security.protocol`, `spring.kafka.properties.sasl.mechanism`, `spring.kafka.properties.sasl.jaas.config`, `spring.kafka.admin.properties.request.timeout.ms`. The `server.port: 8080` header and the top comment banner are also identical but are trivial to leave as-is.

The rest of each prod file differs materially:
- insight-prod also configures `spring.data.redis` (with truststore) and `spring.cache.type: redis`.
- portfolio-prod also configures `spring.datasource`, `jpa`, `flyway`, `spring.data.redis` (no truststore), and `fx.base-currency`.
- market-prod also configures `spring.data.mongodb` and `market-data.*`.

So this is block-level duplication, not file-level duplication.

**Drift risk:** Moderate. Any change to SASL mechanism, truststore path, or admin timeout must be applied identically in three files.

**Smallest safe fix (no new Gradle module):**

1. Add a single shared YAML fragment at `common-dto/src/main/resources/config/application-prod-kafka.yml` containing only the `spring.kafka.*` block.
2. In each service's `application-prod.yml`, replace the duplicated block with:
   ```yaml
   spring:
     config:
       import: "classpath:config/application-prod-kafka.yml"
   ```
3. Spring Boot 4's `spring.config.import` resolves classpath imports natively. No code change, no new module.

**Prerequisite** (verified): all three services already depend on `common-dto` in their `build.gradle`, so the shared fragment is on every service's classpath without any Gradle change. Confirm this once before merging.

---

### 1.3 `docker-compose.yml`: `SPRING_MONGODB_URI` typo (bug fix — standalone)

**Verified bug:** `docker-compose.yml` line 103 sets `SPRING_MONGODB_URI`, but `market-data-service/src/main/resources/application.yml` line 8 reads `SPRING_DATA_MONGODB_URI`. The env var is silently ignored in Compose; the application falls back to its default `mongodb://localhost:27017/market_db`, which does not resolve inside the service container. Terraform (`modules/compute/main.tf` line 269) uses the correct name, so this is Compose-only.

**Scope:** one line in one file.

**Fix:** rename `SPRING_MONGODB_URI` → `SPRING_DATA_MONGODB_URI` on line 103.

**Expected behavior change:** `market-data-service` in Compose will now actually connect to the `mongodb` container instead of silently falling back to the (broken) localhost default. If anyone has been relying on the broken behavior (e.g., pointing at a host-machine Mongo via `host.docker.internal`), they'll notice.

This is kept deliberately separate from the cosmetic YAML-anchor cleanup (1.4) so the bug fix can ship on its own, with its own PR and its own verification.

---

### 1.4 `docker-compose.yml`: repeated env blocks (cosmetic dedup)

The same env vars are hardcoded in multiple service blocks:

- `SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092` — lines 78, 104, 135 (three services)
- `SPRING_DATA_REDIS_HOST: redis` + `SPRING_DATA_REDIS_PORT: 6379` — lines 136–137 and 172–173
- `INTERNAL_API_KEY: ${INTERNAL_API_KEY}` — lines 79, 105, 139, 177 (four services)

Postgres credentials (`wealth_user` / `wealth_pass` / `portfolio_db`) are hardcoded separately in both the `postgres` service and the `portfolio-service` env block.

**Drift risk:** Low. Values rarely change. This is pure cosmetic cleanup.

**Smallest safe fix:** introduce YAML anchors at the top of `docker-compose.yml`:

```yaml
x-kafka-env: &kafka-env
  SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
x-redis-env: &redis-env
  SPRING_DATA_REDIS_HOST: redis
  SPRING_DATA_REDIS_PORT: 6379
x-internal-key: &internal-key
  INTERNAL_API_KEY: ${INTERNAL_API_KEY}
```

Merge into each service's `environment:` via `<<: [*kafka-env, *internal-key]`.

Optional — skip if the noise-to-benefit ratio isn't worth it.

---

## Tier 2 — Fix next

### 2.1 api-gateway: service URL defaults repeated across routes

`api-gateway/src/main/resources/application.yml` declares the service-URL defaults multiple times — once per route. Verified counts:

- `${PORTFOLIO_SERVICE_URL:http://localhost:8081}` — lines 16, 35
- `${MARKET_DATA_SERVICE_URL:http://localhost:8082}` — lines 20, 39
- `${INSIGHT_SERVICE_URL:http://localhost:8083}` — lines 24, 28, 43

Changing the default port requires editing every occurrence.

**Smallest fix:** collapse to one property each, then reference via placeholder.

```yaml
app:
  routes:
    portfolio-url: ${PORTFOLIO_SERVICE_URL:http://localhost:8081}
    market-data-url: ${MARKET_DATA_SERVICE_URL:http://localhost:8082}
    insight-url: ${INSIGHT_SERVICE_URL:http://localhost:8083}
```

Each route uses `${app.routes.portfolio-url}`. Change is isolated to a single file.

**Related instance in `docker-compose.yml`:** `PORTFOLIO_SERVICE_URL: http://portfolio-service:8081` is set twice — line 138 (insight-service block) and line 174 (api-gateway block). Both values are identical today. This is another instance of the same duplication pattern but does not need fixing here; it naturally collapses if addressed as part of the 1.4 YAML-anchor cleanup (define one `x-service-urls` anchor with all three URLs).

---

### 2.2 Frontend: three env var names for the same value (gateway URL)

Three different env vars resolve to the gateway base URL:

- `NEXT_PUBLIC_API_BASE_URL` — consumed by `frontend/src/lib/config/api.ts` (canonical, runtime)
- `API_PROXY_TARGET` — set in CI (`frontend-e2e-integration.yml` line 23). **Verified dead**: `git grep "API_PROXY_TARGET"` returns only the one CI workflow plus spec/history docs. No Next.js, Playwright, or server code reads it.
- `GATEWAY_BASE_URL` — used broadly by the Playwright test suite, and in **live AWS** synthetic-monitoring CI

**Zero-risk first step (ship immediately, independent of the larger rename):** delete the `API_PROXY_TARGET: http://localhost:8080` line from `frontend-e2e-integration.yml` (line 23). One line, one file, no consumer.

**Full blast radius of `GATEWAY_BASE_URL`** (verified via `git grep`):

Frontend test code (all use `process.env.GATEWAY_BASE_URL ?? "http://localhost:8080"`):
- `frontend/playwright.config.ts` line 61 (propagates via `webServer.env`)
- `frontend/tests/e2e/global-setup.ts` line 19
- `frontend/tests/e2e/helpers/api.ts` line 4
- `frontend/tests/e2e/helpers/browser-auth.ts` line 3
- `frontend/tests/e2e/auth-jwt-health.spec.ts` line 3
- `frontend/tests/e2e/dashboard-data.spec.ts` line 23
- `frontend/tests/e2e/aws-synthetic/README.md` line 13 (documented override)

CI workflows:
- `.github/workflows/frontend-e2e-integration.yml` line 24 — `http://localhost:8080` (local stack)
- `.github/workflows/ci-verification.yml` line 284 — `https://vibhanshu-ai-portfolio.dev` (**live prod**)
- `.github/workflows/synthetic-monitoring.yml` line 60 — `https://vibhanshu-ai-portfolio.dev` (**live prod**)

**Why this matters:** `GATEWAY_BASE_URL` is not just an e2e-setup variable. In two production workflows it points at the live AWS deployment so synthetic checks and CI verification hit prod, not the local stack. A half-migration that updates `global-setup.ts` but leaves the helpers or prod workflows behind will silently break prod monitoring.

**Smallest fix (all-or-nothing):** pick `NEXT_PUBLIC_API_BASE_URL` as the single canonical name and migrate every consumer in one PR.

Files to update (all 6 test files above + `playwright.config.ts`): replace `process.env.GATEWAY_BASE_URL` with `process.env.NEXT_PUBLIC_API_BASE_URL` (keep the same `?? "http://localhost:8080"` fallback).

CI workflows to update:
- `frontend-e2e-integration.yml`: drop `API_PROXY_TARGET` + `GATEWAY_BASE_URL` lines; keep a single `NEXT_PUBLIC_API_BASE_URL`.
- `ci-verification.yml` and `synthetic-monitoring.yml`: rename the env key to `NEXT_PUBLIC_API_BASE_URL` (value unchanged — still the prod URL).

Docs to update: `frontend/tests/e2e/aws-synthetic/README.md` (export example).

**Alternative (less invasive):** keep `GATEWAY_BASE_URL` as the canonical test-time name and delete only `API_PROXY_TARGET` (which truly has no consumer). That's a one-line CI cleanup rather than a cross-cutting rename. Worth considering if you don't want to churn all 6 test files.

---

### 2.3 CI workflow: three aliases for the same auth secret

`.github/workflows/frontend-e2e-integration.yml` sets all three (lines 17–19):

```yaml
AUTH_SECRET: ${{ secrets.NEXTAUTH_SECRET }}
NEXTAUTH_SECRET: ${{ secrets.NEXTAUTH_SECRET }}
BETTER_AUTH_SECRET: ${{ secrets.NEXTAUTH_SECRET }}
```

`.github/workflows/ci-verification.yml` line 150 also sets `BETTER_AUTH_SECRET: ${{ secrets.NEXTAUTH_SECRET }}`.

**Consumer audit** (verified via `git grep`):

- `BETTER_AUTH_SECRET` — consumed by `frontend/src/lib/auth.ts`, `frontend/src/lib/auth/mintToken.ts` (fallback), `frontend/scripts/seed-dev-user.ts`, and tests. **Keep.**
- `AUTH_SECRET` — no non-doc consumer in the repo. **Dead.**
- `NEXTAUTH_SECRET` — not read by any `.ts` file, but **is passed as a Docker build-arg** in `.github/workflows/frontend-cd.yml` line 39. `frontend/Dockerfile` does **not** declare `ARG NEXTAUTH_SECRET`, so Docker discards it — but the workflow line should be removed in lockstep to avoid implying it's consumed. Also exported from `frontend-e2e-integration.yml` for test runs (dead there).

**Smallest fix:**

1. Delete `AUTH_SECRET` and `NEXTAUTH_SECRET` lines from `frontend-e2e-integration.yml`.
2. Delete the `NEXTAUTH_SECRET=...` build-arg line from `frontend-cd.yml`.
3. Keep `BETTER_AUTH_SECRET` in both `frontend-e2e-integration.yml` and `ci-verification.yml`.

Scope: 2 workflow files. The GitHub Actions secret itself (`secrets.NEXTAUTH_SECRET`) can remain under that name — this is a variable-name cleanup, not a secret rotation.

---

### 2.4 `APP_AUTH_USER_ID` default `user-001` hardcoded in four places

- `api-gateway/src/main/resources/application.yml` line 68 — `${APP_AUTH_USER_ID:user-001}`
- `api-gateway/src/main/java/com/wealth/gateway/AuthController.java` line 25 — `@Value("${app.auth.user-id:user-001}")`
- `docker-compose.yml` line 170 — `APP_AUTH_USER_ID: ${APP_AUTH_USER_ID:-user-001}`
- Flyway migration V3 (portfolios.user_id seed)

The same pattern exists for `email`, `password`, and `name` — each has a default in both the YAML and the Java `@Value`.

**How this resolves today:** when the YAML property is present (which it always is in practice), the `@Value` default is never exercised — the YAML value wins. The `@Value` default is only a fallback for the case where the YAML property is deleted or renamed. In that case Spring silently uses the Java default, which can drift from the YAML default without anyone noticing.

**Smallest fix:** remove the defaults from the `@Value` annotations in `AuthController.java`. YAML becomes the single source of the default.

**Behavioral change (explicit):** if the `app.auth.*` property is ever missing from the YAML (e.g., a typo during refactor or a deleted key), Spring will now fail-fast at startup with `IllegalArgumentException: Could not resolve placeholder ...` instead of silently falling back to the Java default. This is the right behavior for a security-adjacent property but should be consciously chosen — confirm before merging.

---

## Tier 3 — Cleanup / document-only

### 3.1 Dead Terraform variables — three variables, two different scopes

All three variables carry descriptions saying "no longer used" but have very different removal complexity. Treating them as a single unit overstates the effort for two of them.

**3.1a — `lambda_java_runtime` (trivial, standalone)**

Declared only in `infrastructure/terraform/variables.tf` line 58. No `-var=` flag, no tfvars entry, no module reference. Verified via `git grep` — only appears in the declaration and a historical changelog doc.

**Fix:** delete the one variable block. One file, one PR, zero coordination needed.

**3.1b — `lambda_adapter_layer_arn` (moderate)**

- Declaration: `infrastructure/terraform/variables.tf` line 47
- tfvars files: `localstack.tfvars` line 19, `terraform.tfvars.example` line 44
- No `-var=` flag in CI or scripts.

**Fix:** delete declaration + the 2 tfvars assignments. Three-file PR, no CI coordination.

**3.1c — `s3_key_api_gateway` (coordinated multi-file change)**

Declared twice (root + module), passed through in `main.tf`, **and actively supplied at every plan/apply**:

- Declarations: root `variables.tf` line 142 + `modules/compute/variables.tf` line 10
- Module wiring: `main.tf` line 52
- `-var=` flags:
  - `.github/workflows/terraform.yml` line 95 — `-var="s3_key_api_gateway=${{ secrets.S3_KEY_API_GATEWAY }}"` (CI)
  - `infrastructure/terraform/tf-apply-partial.sh` line 38
  - `infrastructure/terraform/tf-import.ps1` line 39
  - `infrastructure/terraform/create-ecr-ap-south-1.ps1` line 35
- tfvars: `localstack.tfvars` line 30, `terraform.tfvars.example` line 65
- GitHub Actions secret: `secrets.S3_KEY_API_GATEWAY` (referenced in `terraform.yml`)

**Why it matters:** deleting the variable declarations without also dropping the `-var=` flags causes `terraform plan` to fail with `Error: Value for undeclared variable`. This must land as a single PR.

**Coordinated fix (one PR):**

1. Delete declarations from root `variables.tf` + `modules/compute/variables.tf`.
2. Delete the pass-through line in `main.tf`.
3. Delete the 4 `-var=` flags (1 CI workflow + 2 PowerShell scripts + 1 shell script).
4. Delete the 2 tfvars assignments.
5. Optionally: delete the `S3_KEY_API_GATEWAY` GitHub Actions secret.

Spec/design docs under `.kiro/specs/` and `docs/specs/` that reference these variables are historical records and should be left as-is.

**Sequencing note:** 3.1a and 3.1b can ship as tiny independent PRs immediately. 3.1c is the only one that needs coordination.

---

### 3.2 Kafka `trusted.packages: "*"` in three places

Appears in:

- `portfolio-service/src/main/resources/application.yml` line 29
- `insight-service/src/main/resources/application.yml` line 30
- Java code in `PortfolioKafkaConfig.java` and `InsightKafkaConfig.java` (via `JacksonJsonDeserializer.TRUSTED_PACKAGES`)

Not a pure duplicate (two layers, and the Java version wins for programmatic factories), but worth narrowing to `com.wealth.market.events` in all three locations. Better addressed as part of the larger shared-Kafka-config refactor discussed separately; noted here for completeness.

---

## Recommended sequence

Each step is independently mergeable and reversible.

| # | Change | Files touched | Risk |
|---|---|---|---|
| 1 | Fix `SPRING_MONGODB_URI` → `SPRING_DATA_MONGODB_URI` in Compose (1.3) | `docker-compose.yml` (1 line) | Low — behavior change: `market-data-service` actually connects to the `mongodb` container |
| 2 | Delete dead `API_PROXY_TARGET` line from CI (2.2, first step) | `frontend-e2e-integration.yml` (1 line) | Trivial — verified no consumer |
| 3 | Delete dead `lambda_java_runtime` variable (3.1a) | root `variables.tf` (1 block) | Trivial — standalone |
| 4 | Delete unused `AUTH_SECRET` / `NEXTAUTH_SECRET` aliases in CI (2.3) | `frontend-e2e-integration.yml`, `frontend-cd.yml` | Trivial |
| 5 | Delete dead `lambda_adapter_layer_arn` variable (3.1b) | root `variables.tf`, 2 tfvars files | Trivial |
| 6 | Collapse Terraform root ↔ compute variable duplication (1.1) + dead `s3_key_api_gateway` (3.1c) | 2 variables files, `main.tf`, 1 CI workflow, 2 PowerShell scripts, 1 shell script, 2 tfvars files | Low but multi-file — all must land together |
| 7 | Consolidate api-gateway service URL defaults into `app.routes.*` (2.1) | `api-gateway/src/main/resources/application.yml` | Low |
| 8 | Unify frontend gateway URL env var — remaining scope (2.2) | 6 test files + `playwright.config.ts` + 2 prod CI workflows + 1 README | Medium — cross-cutting rename incl. prod monitoring workflows |
| 9 | Extract shared `spring.kafka.*` prod block via `spring.config.import` (1.2) | 3 prod YAMLs + 1 new shared fragment in `common-dto` | Low |
| 10 | YAML anchors in `docker-compose.yml` (1.4) | `docker-compose.yml` | Trivial — cosmetic; optional |
| 11 | Remove `@Value` defaults in `AuthController` (2.4) | one Java file | **Behavioral**: silent fallback → fail-fast at startup. Verify intent before merging. |

**Ordering rationale:**
- Items 1–5 are single-file or near-single-file PRs with trivial blast radius; each can land in isolation.
- Item 6 combines 1.1 (variable-duplication collapse) with 3.1c (dead `s3_key_api_gateway` removal). They touch the same Terraform files and the reviewer context overlaps heavily — merging them into one PR cuts one round-trip without increasing risk.
- Item 8 is deliberately mid-pack: it's the only change that touches live prod-monitoring workflows, so it should land with intent, not as part of a cleanup sweep.
- Items 9–11 are small refactors with mild trade-offs to decide on.

---

## Explicitly out of scope for this audit

- Extracting shared Kafka `@Configuration` / Boot 4 auto-configuration into a dedicated module. Discussed separately; larger change, higher value, but does not fit the "minimize drift without major changes" brief.
- Normalizing the overlap between `application.yml` Kafka properties (YAML) and the programmatic `ConsumerFactory` beans (Java) in `PortfolioKafkaConfig.java` / `InsightKafkaConfig.java`. Belongs with the shared-Kafka-config refactor.
- Port-number harmonization across Compose, YAML, shell scripts, and frontend tests. Too diffuse to dedupe cleanly; better addressed with a single documented port map.

---

## Review checklist for the maintainer

- [ ] **1.1** Confirm via `terraform plan` that removing module-level defaults for `lambda_timeout` / `lambda_architecture` produces no diff.
- [ ] **1.2 / item 9** Confirm the canonical location for the shared Kafka YAML fragment: `common-dto/src/main/resources/config/application-prod-kafka.yml`. Prerequisite — all three services must depend on `common-dto` on Gradle (verified: they do).
- [ ] **1.3** Confirm no developer workflow is relying on the broken `SPRING_MONGODB_URI` (e.g., pointing at host-machine Mongo). If any is, they'll need to switch to the correct var name or use `host.docker.internal` explicitly.
- [ ] **2.2 first step (item 2)** Deleting the `API_PROXY_TARGET` line is zero-risk — no consumer anywhere in the repo (verified via `git grep`).
- [ ] **2.2 remainder (item 8)** Decide between the full rename to `NEXT_PUBLIC_API_BASE_URL` (6 test files + 2 prod workflows) and the lighter alternative (keep `GATEWAY_BASE_URL` as canonical, no rename).
- [ ] **2.3** Confirm `ARG NEXTAUTH_SECRET` is not silently added to `frontend/Dockerfile` in a branch you have open. Current `main` does not declare it.
- [ ] **2.4 / item 11** Confirm fail-fast-on-missing-property is the intended behavior before removing `@Value` defaults in `AuthController`.
- [ ] **3.1c** Confirm the `S3_KEY_API_GATEWAY` GitHub Actions secret is not used by any workflow other than `terraform.yml` before deleting it (optional step).
