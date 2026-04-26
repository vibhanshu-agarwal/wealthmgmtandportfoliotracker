# Config Deduplication — Change Summary (2026-04-26)

---

## Handoff — Current State & Next Steps

**Workspace:** `D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-deduplicate-config`
**Repository:** `https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker`
**As of:** 2026-04-26

### All 11 PRs are open. None have been merged yet.

| # | Branch | PR | Merge order note |
|---|---|---|---|
| 1 | `config/dedup-01-mongodb-uri-fix` | [#12](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/12) | Merge last among trivial items — behavior change |
| 2 | `config/dedup-02-dead-api-proxy-target` | [#13](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/13) | Trivial — merge any time |
| 3 | `config/dedup-03-dead-lambda-java-runtime` | [#14](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/14) | Trivial — merge any time |
| 4 | `config/dedup-04-dead-auth-secret-aliases` | [#15](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/15) | Trivial — merge any time |
| 5 | `config/dedup-05-dead-lambda-adapter-layer-arn` | [#16](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/16) | Trivial — merge any time |
| 6 | `config/dedup-06-terraform-variable-dedup` | [#17](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/17) | Merge before deleting the `S3_KEY_API_GATEWAY` Actions secret |
| 7 | `config/dedup-07-api-gateway-route-url-defaults` | [#18](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/18) | Trivial — merge any time |
| 8 | `config/dedup-08-gateway-base-url-rename` | [#19](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/19) | Touches 2 live prod-monitoring workflows — verify CI before merging |
| 9 | `config/dedup-09-kafka-prod-fragment` | [#20](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/20) | Merge any time |
| 10 | `config/dedup-10-compose-yaml-anchors` | [#21](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/21) | Trivial — merge any time |
| 11 | `config/dedup-11-authcontroller-value-defaults` | [#22](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/22) | Behavioral (fail-fast) — merge last, after verifying CI |

### Pending manual action after PR #17 merges
Delete the `S3_KEY_API_GATEWAY` secret from **GitHub → Settings → Secrets and variables → Actions**. It is no longer referenced by any workflow. Deleting it before the PR merges will break `terraform plan` in CI.

### Nothing left to implement
All 11 audit items are coded and pushed. The only remaining work is PR review and merge.

---


**Source of truth:** `docs/audit/2026-04-23-config-duplication-audit.md`  
**Strategy:** one independent PR per item; each branch off `main` so items can merge in any order.  
**Scope:** Spring Boot YAML configs, Terraform variables, Docker Compose, frontend env vars, CI workflows.

---

## Item 1 — Fix `SPRING_MONGODB_URI` typo in `docker-compose.yml` · [PR #12](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/12)

**Audit ref:** §1.3  
**Files:** `docker-compose.yml` (1 line)  
**Risk:** Low — behavior change

`docker-compose.yml` set `SPRING_MONGODB_URI` but the application reads `SPRING_DATA_MONGODB_URI`. The env var was silently ignored; `market-data-service` fell back to `mongodb://localhost:27017/market_db`, which does not resolve inside the container. Renamed the key to the correct name so the service actually connects to the `mongodb` container.

---

## Item 2 — Delete dead `API_PROXY_TARGET` from CI · [PR #13](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/13)

**Audit ref:** §2.2 (first step)  
**Files:** `.github/workflows/frontend-e2e-integration.yml` (1 line)  
**Risk:** Trivial

`API_PROXY_TARGET: http://localhost:8080` was set in the e2e CI workflow but had no consumer anywhere in the repository (verified via `git grep`). Deleted the line.

---

## Item 3 — Delete dead `lambda_java_runtime` variable · [PR #14](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/14)

**Audit ref:** §3.1a  
**Files:** `infrastructure/terraform/variables.tf` (1 block)  
**Risk:** Trivial

`lambda_java_runtime` was declared but never referenced in any module, tfvars file, or `-var=` flag. Deleted the variable block. `terraform plan` diff: none.

---

## Item 4 — Delete dead `AUTH_SECRET` / `NEXTAUTH_SECRET` CI aliases · [PR #15](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/15)

**Audit ref:** §2.3  
**Files:** `.github/workflows/frontend-e2e-integration.yml`, `.github/workflows/frontend-cd.yml`  
**Risk:** Trivial

Three aliases pointed at the same `secrets.NEXTAUTH_SECRET`:
- `AUTH_SECRET` — no consumer in any `.ts` file; deleted.
- `NEXTAUTH_SECRET` — not read by any application code; passed as a Docker build-arg in `frontend-cd.yml` but `frontend/Dockerfile` does not declare `ARG NEXTAUTH_SECRET` so Docker silently discarded it; deleted from both files.
- `BETTER_AUTH_SECRET` — the live consumer used by `frontend/src/lib/auth.ts`; **kept**.

---

## Item 5 — Delete dead `lambda_adapter_layer_arn` variable · [PR #16](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/16)

**Audit ref:** §3.1b  
**Files:** `infrastructure/terraform/variables.tf`, `localstack.tfvars`, `terraform.tfvars.example`  
**Risk:** Trivial

`lambda_adapter_layer_arn` was declared in root `variables.tf` and assigned in two tfvars files but never passed through to any Terraform resource. Deleted all three references. `terraform plan` diff: none.

---

## Item 6 — Collapse Terraform root ↔ compute variable duplication + delete `s3_key_api_gateway` · [PR #17](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/17)

**Audit ref:** §1.1 + §3.1c  
**Files:** 9 files (`variables.tf` ×2, `main.tf`, `terraform.yml`, 2 PowerShell scripts, 1 shell script, 2 tfvars files)  
**Risk:** Low but coordinated

Every variable consumed by the compute module was declared identically in both `infrastructure/terraform/variables.tf` (root) and `infrastructure/terraform/modules/compute/variables.tf`, then wired through `main.tf` as pure pass-through. Additionally, `lambda_timeout` and `lambda_architecture` had genuine semantic drift (different defaults, validation living only in the module).

Changes:
- Root `variables.tf` is now the single source of truth for type, description, validation, and defaults.
- Module `variables.tf` is now minimal: `type` and `sensitive` only — no descriptions or defaults.
- `lambda_architecture` validation (`contains(["arm64","x86_64"])`) moved to root.
- `s3_key_api_gateway` removed from both variable files, `main.tf`, all four `-var=` flag sites (1 CI workflow, 2 PowerShell scripts, 1 shell script), and both tfvars files simultaneously (omitting any one site would have caused `terraform plan` to fail with "value for undeclared variable").

**Post-merge:** the `S3_KEY_API_GATEWAY` GitHub Actions repository secret can be deleted from Settings — it is no longer referenced.

**Bug fixed during CI:** Terraform does not short-circuit `||` in `validation` blocks; `contains(list, null)` threw "argument must not be null" when `lambda_architecture` was omitted. Fixed by wrapping with `try()`:
```hcl
condition = try(contains(["arm64", "x86_64"], var.lambda_architecture), var.lambda_architecture == null)
```

---

## Item 7 — Centralise api-gateway service URL defaults into `app.routes.*` · [PR #18](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/18)

**Audit ref:** §2.1  
**Files:** `api-gateway/src/main/resources/application.yml`  
**Risk:** Low

`${PORTFOLIO_SERVICE_URL:http://localhost:8081}`, `${MARKET_DATA_SERVICE_URL:http://localhost:8082}`, and `${INSIGHT_SERVICE_URL:http://localhost:8083}` were embedded inline in every Spring Cloud Gateway route definition — `INSIGHT_SERVICE_URL` appeared three times (two routes). A port change required editing every occurrence.

Extracted into a single `app.routes.*` block:
```yaml
app:
  routes:
    portfolio-url:    ${PORTFOLIO_SERVICE_URL:http://localhost:8081}
    market-data-url:  ${MARKET_DATA_SERVICE_URL:http://localhost:8082}
    insight-url:      ${INSIGHT_SERVICE_URL:http://localhost:8083}
```
Each route now references `${app.routes.*-url}`. One edit point per URL.

---

## Item 8 — Rename `GATEWAY_BASE_URL` → `NEXT_PUBLIC_API_BASE_URL` · [PR #19](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/19)

**Audit ref:** §2.2 (remainder)  
**Files:** 10 files (3 CI workflows, 6 Playwright test files, 1 README)  
**Risk:** Medium — cross-cutting rename including two live prod-monitoring workflows

`GATEWAY_BASE_URL` was used in 6 Playwright test helpers and two production CI workflows (`ci-verification.yml` and `synthetic-monitoring.yml`) that hit the live deployment. The canonical frontend variable is `NEXT_PUBLIC_API_BASE_URL` (enforced Next.js convention for browser-accessible vars). All consumers renamed in a single atomic PR to prevent a half-migration from silently breaking prod monitoring.

---

## Item 9 — Extract shared `spring.kafka.*` prod block into `common-dto` fragment · [PR #20](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/20)

**Audit ref:** §1.2  
**Files:** 4 files (1 new shared fragment + 3 `application-prod.yml` files)  
**Risk:** Low

An identical 15-line `spring.kafka.*` block (bootstrap servers, SSL truststore, SASL, admin timeout) was copy-pasted verbatim into all three backend service `application-prod.yml` files. Any change to SASL mechanism or truststore path required three identical edits.

Created `common-dto/src/main/resources/config/application-prod-kafka.yml` as the single source of truth. Each service `application-prod.yml` now imports it with one line:
```yaml
spring:
  config:
    import: "classpath:config/application-prod-kafka.yml"
```
No Gradle changes required — all three services already declare `implementation(project(':common-dto'))`.

---

## Item 10 — YAML anchors for repeated env vars in `docker-compose.yml` · [PR #21](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/21)

**Audit ref:** §1.4  
**Files:** `docker-compose.yml`  
**Risk:** Trivial — cosmetic; behaviorally equivalent

Three env var values were hardcoded in multiple service `environment:` blocks:
- `SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092` — 3 services
- `SPRING_DATA_REDIS_HOST: redis` + `SPRING_DATA_REDIS_PORT: 6379` — 2 services
- `INTERNAL_API_KEY: ${TF_VAR_internal_api_key}` — 4 services

Introduced three Docker Compose extension fields (`x-*`) as YAML anchors at the top of the file, then merged them into each service block via `<<:`. Docker Compose expands anchors before starting containers; the resolved environment is identical to what was there before.

---

## Item 11 — Remove `@Value` inline defaults from `AuthController` · [PR #22](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/22)

**Audit ref:** §2.4  
**Files:** `api-gateway/src/main/java/com/wealth/gateway/AuthController.java`  
**Risk:** Behavioral — fail-fast (intentional, explicitly approved)

`AuthController` had hardcoded fallback strings inside four `@Value` annotations (e.g., `@Value("${app.auth.email:dev@localhost.local}")`). The same values were already declared in `application.yml` with proper env-var overrides. The Java-level defaults were therefore invisible to operators reading the YAML, and a misconfigured (blank) YAML would silently bind the hardcoded dev credentials instead of failing at startup.

Removed the inline defaults. `application.yml` is now the single source of truth:
```yaml
app:
  auth:
    email:    ${APP_AUTH_EMAIL:dev@localhost.local}
    password: ${APP_AUTH_PASSWORD:password}
    user-id:  ${APP_AUTH_USER_ID:user-001}
    name:     ${APP_AUTH_NAME:Development User}
```
If `app.auth.*` is ever absent from the YAML, Spring throws `BeanCreationException` at startup instead of silently falling back. `@SpringBootTest` tests load `application.yml` and resolve identically to before.

---

## Items explicitly left out of scope

- `us-east-1` appearing in both `application-aws.yml` and `application-bedrock.yml` — **intentional layering**; the `aws` profile hard-pins the region for Lambda, `bedrock` provides a local default. Do not deduplicate.
- Kafka `trusted.packages: "*"` in three locations — addressed as part of a separate shared-Kafka-`@Configuration` refactor.
- Port-number harmonization across Compose, YAML, scripts, and tests — too diffuse; better handled with a documented port map.
- Shared Kafka `@Configuration` / Boot 4 auto-configuration module — out of scope per the audit brief.
