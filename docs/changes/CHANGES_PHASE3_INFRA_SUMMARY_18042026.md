# Phase 3 Infrastructure — 2026-04-18 (since infra v2)

**Previous revision:** [CHANGES_INFRA_SUMMARY_2026-04-17_v2.md](./CHANGES_INFRA_SUMMARY_2026-04-17_v2.md) — Phase 3 **`deploy.yml`** hardening (standalone **`.next/static`**, Gradle **`+x`**, Lambda reserved keys, Web Adapter **1.0.0**), Compose port parity (**`176437d`**), and v2 errata (**`22624bd`**).

**Repository path (clone):** `docs/changes/CHANGES_INFRA_SUMMARY_2026-04-17_v2.md`

---

## Summary (changes since infra v2)

This note picks up **after** [CHANGES_INFRA_SUMMARY_2026-04-17_v2.md](./CHANGES_INFRA_SUMMARY_2026-04-17_v2.md). The v2 document already narrates **`176437d`** / **`22624bd`**; everything below is **additional** infrastructure and platform work through **`1278a13`** on **`architecture/cloud-native-extraction`**.

1. **`deploy.yml` — CloudFront and static hosting** — Invalidate **`CLOUDFRONT_DISTRIBUTION_ID`** after S3 uploads; align **`deploy-frontend`** with **Next static export** (**`frontend/out/`** → bucket root), which **supersedes** v2 §1.1’s **`.next/static` + `public`** two-pass for this pipeline.
2. **`deploy.yml` — Lambda** — Poll **`LastUpdateStatus`** before **`update-function-code`** to avoid **`ResourceConflictException`** when configuration updates are still in flight.
3. **Terraform** — CloudFront routing hardening, outputs for buckets/distribution identifiers, remote-state bootstrap runbook, **`terraform.yml`** reliability (concurrency, non-interactive runs, lock timeouts, RDS-related **`TF_VAR_*`**, explicit **`enable_aws_managed_database=false`** in CI plans), and a root variable to **gate** paid **RDS** / **ElastiCache** when you opt in.
4. **CI/CD from `main` (merge `da46d48` and related)** — Qodana workflow + **`qodana.yaml`**; **`architecture/**`** triggers on shared **`ci.yml`**, **`cd.yml`**, **`frontend-ci.yml`**, **`frontend-cd.yml`**.
5. **Frontend CI and full-stack E2E** — **`frontend-ci.yml`** aligned with static export; **`frontend-e2e-integration.yml`** env for gateway base URL, **`chmod +x ./gradlew`**, single combined Playwright invocation (stable **`webServer`** / **`storageState`** lifecycle).
6. **Docker Compose** — Default **`APP_AUTH_USER_ID=user-001`** on **`api-gateway`** so stub login matches Flyway demo data in local and CI stacks.
7. **README** — Terraform CLI version prerequisite (**`~> 1.6`**) called out next to workflow pins.

**Companion (application scope):** [CHANGES_PHASE3_SUMMARY_2026-04-18.md](./CHANGES_PHASE3_SUMMARY_2026-04-18.md)

---

## 1. GitHub Actions

### 1.1 **`deploy.yml`** (delta since infra v2)

| Topic | Detail |
| --- | --- |
| CloudFront | **`aws cloudfront create-invalidation --paths "/*"`** after S3 upload, using **`CLOUDFRONT_DISTRIBUTION_ID`** (**`6f4129a`**). |
| Static export | **`baae86e`** switches **`deploy-frontend`** to **`npm run build`** → require **`frontend/out`**, then **`aws s3 sync frontend/out/ s3://${{ secrets.S3_BUCKET_NAME }}/` --delete** (full static tree at bucket root). |
| Lambda ordering | **`1bfa6ba`** waits (bounded loop) until **`get-function-configuration`** reports **`LastUpdateStatus=Successful`** before **`update-function-code`**. |
| Scripts | **`6f4129a`** updates **`verify-prod-deps.sh`** comments/examples alongside deploy header comments. |

**Secrets checklist delta:** **`CLOUDFRONT_DISTRIBUTION_ID`** is required for **`deploy-frontend`** invalidation.

### 1.2 **`frontend-ci.yml`**

**`62abb67`** — CI path aligned with **static export** (workflow + frontend tooling where the same commit touched them). Product-facing details of the migration are summarized in the companion Phase 3 application changelog.

### 1.3 **`frontend-e2e-integration.yml`**

**`1278a13`** — Adds **`GATEWAY_BASE_URL`** (alongside **`NEXT_PUBLIC_API_BASE_URL`** / **`API_PROXY_TARGET`**); **`chmod +x ./gradlew`** before **`./gradlew build`**; runs **one** **`npx playwright test`** over auth + dashboard + golden-path specs so CI matches a single Playwright process and server lifecycle.

### 1.4 **Shared CI / CD and Qodana (brought in via `main`)**

| Workflow / artifact | Change |
| --- | --- |
| **`ci.yml`**, **`cd.yml`**, **`frontend-ci.yml`**, **`frontend-cd.yml`** | Triggers extended to **`main`** and **`architecture/**`** where appropriate. |
| **`qodana_code_quality.yml`**, **`qodana.yaml`** | Qodana static analysis job and config. |

---

## 2. Terraform (`infrastructure/terraform`)

### 2.1 Networking / CloudFront

**`05fc8e5`** — Hardened static routing / rewriting in **`modules/networking/main.tf`** (addresses **403**-style failures on static paths behind CloudFront). Specs under **`.kiro/specs/cloudfront-static-route-403-fix/`**.

### 2.2 Outputs and variables

**`4efc123`** — Root **`outputs.tf`**, **`variables.tf`**, **`terraform.tfvars.example`**, **`localstack.tfvars`**, and networking module **outputs/variables** wired so operators (and **`terraform.yml`**) can read **artifact bucket** and **CloudFront distribution id** values consistently.

### 2.3 Remote state bootstrap

**`c702ad0`** — Expands **`docs/infrastructure/bootstrap.md`** and related **`main.tf`** guidance for first-time **S3 + DynamoDB** backend provisioning.

### 2.4 **`terraform.yml`**

**`568f09f`**, **`46ac14d`** — Workflow runs on **`main`** for **`infrastructure/terraform/**`** pushes; **`workflow_dispatch`** enabled for manual runs on feature branches.

**`bc0553d`**, **`b181f4a`** — **Concurrency** group **`terraform-state-${{ github.repository }}`** with **`cancel-in-progress: false`**; per-job **timeouts**; **`terraform_wrapper: false`**; **`TF_INPUT=false`**; **`terraform init` / `plan` / `apply`** use **`-input=false -lock-timeout=10m`**; **`TF_VAR_db_username`** / **`TF_VAR_db_password`** from **`RDS_*`** secrets; **`terraform plan`** passes **`-var="enable_aws_managed_database=false"`** so default CI plans stay on **external** Postgres/cache unless you change it deliberately.

**`enable_aws_managed_database`** (root **`variables.tf`**, **`terraform.tfvars.example`**, database module) — when **`true`** and not **`is_local_dev`**, the database module may provision **RDS PostgreSQL** and **ElastiCache** (paid). Default remains **`false`** for Neon / Upstash / external Kafka style deployments.

The **`apply`** job continues to **invalidate CloudFront** using **`terraform output -raw cloudfront_distribution_id`**.

---

## 3. Docker Compose (local / E2E platform)

**`81899fb`** — Under **`api-gateway` → `environment`**, sets **`APP_AUTH_USER_ID: ${APP_AUTH_USER_ID:-user-001}`** with an inline comment tying the default to Flyway **V3** seed **`user-001`**, so gateway stub login and seeded portfolio rows stay aligned in Compose-backed runs.

---

## 4. Key decisions (since infra v2)

| Decision | Rationale |
| --- | --- |
| **`aws s3 sync frontend/out/`** in **`deploy.yml`** | Matches **static export** after auth moved off Next route handlers; **replaces** v2’s **`.next/static` + `public`** deploy story for **this** workflow. |
| **CloudFront invalidation after deploy** | Avoids long-lived stale **`HTML`** and **`_next/static`** entries at the edge. |
| **Poll Lambda `LastUpdateStatus` before `update-function-code`** | Removes a class of flaky **`ResourceConflictException`** failures between env and image updates. |
| **`enable_aws_managed_database=false` in CI `plan`** | PR and branch automation stay predictable and non-surprising for cost. |
| **Terraform concurrency group** | Reduces DynamoDB state-lock collisions when multiple jobs target the same remote backend. |

**Supersedes (infra v2 §1.1 for `deploy.yml` artifact layout):**

| Infra v2 §1.1 | Status |
| --- | --- |
| **Two-pass** sync of **`.next/static`** and **`public`** | **Superseded** for **`deploy.yml`** by a **single** **`frontend/out/`** sync after the static-export migration. **Note:** **`frontend-e2e-integration.yml`** still builds a **standalone** server for Playwright against **`docker compose`**; that is intentional and differs from the **S3** deploy path. |

---

## 5. Verification

- **Deploy:** push **`architecture/cloud-native-extraction`**; confirm **`deploy-frontend`** (**`out/`** present, S3 sync, invalidation) and **`deploy-backend`** (env JSON + ECR image + Lambda) are green.
- **Terraform:** PR touching **`infrastructure/terraform/**`** → **`validate`** / **`plan`** / **`assert_plan.py`**; **`main`** push → **`apply`** and post-apply invalidation.
- **Compose / E2E:** **`docker compose up -d --build`**; optional **`npx playwright test`** in **`frontend`** mirroring **`frontend-e2e-integration.yml`**.

---

## 6. Git record

- **Branch:** `architecture/cloud-native-extraction`
- **Infra-focused commits (illustrative, since v2 doc `58f2acb`):** `6f4129a`, `1bfa6ba`, `baae86e` (workflow slice), `62abb67`, `05fc8e5`, `b4c7d04`, `568f09f`, `46ac14d`, `c702ad0`, `4efc123`, `bc0553d`, `b181f4a`, `81899fb` (Compose slice), `1278a13` (E2E workflow slice), plus CI/CD/Qodana commits merged via `da46d48` (`9b95c43` … `174a31a`, `3852e72`). **Later same-day revisions:** `c944ea8`, `6d14b0e`, `9f1d0f0` (see §7 addendum).
- **Remote:** [github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker)

---

## 7. Addendum — 2026-04-18 (after `1278a13`)

The sections above were written through **`1278a13`**. The following landed on **`architecture/cloud-native-extraction`** the same day.

### 7.1 CI and E2E integrity — **`c944ea8`**

| Area | Detail |
| --- | --- |
| Playwright / gateway auth | **`installGatewaySessionInitScript`** no longer mints a local JWT when **`SKIP_BACKEND_HEALTH_CHECK`** is set; flows **POST** to **`/api/auth/login`** so E2E matches real gateway sessions. |
| Projects | **`static-smoke`** Playwright project for dashboard smoke only; **Chromium** ignores that spec file so the same file is not executed twice. |
| **`frontend-ci.yml`** | **`e2e-smoke`** job reduced to **`static-smoke`** only; unused Postgres / Better Auth seed steps removed. |
| **`global-setup.ts`** | Log text clarified when the gateway readiness poll is skipped. |
| **`frontend-e2e-integration.yml`**, **`ci-verification.yml`** | **`AUTH_JWT_SECRET`** wiring uses a documented fallback for CI. |

**Companion doc:** [CHANGES_PHASE3_SUMMARY_2026-04-18.md](./CHANGES_PHASE3_SUMMARY_2026-04-18.md) — Phase 3 **application** addendum for the same commit.

### 7.2 This infrastructure summary — **`6d14b0e`**

Initial publication of **`CHANGES_PHASE3_INFRA_SUMMARY_18042026.md`** (the body through §6 above), linking back to infra v2 and the Phase 3 application changelog.

### 7.3 Terraform and bootstrap — **`9f1d0f0`**

| Topic | Detail |
| --- | --- |
| **CDN module** | New **`infrastructure/terraform/modules/cdn`**: CloudFront distribution, **S3** static origin with **origin access control (OAC)**, **viewer-request CloudFront Function** for extensionless static-export paths, **`/api/*`** cache behavior to the **Lambda Function URL** origin, optional **ACM** viewer certificate and **Route53** alias when a custom **`domain_name`** is set; root **`main.tf`** / **`variables.tf`** / **`terraform.tfvars.example`** wired for optional CDN deployment. |
| **Compute** | **`modules/compute`** passes **`CLOUDFRONT_ORIGIN_SECRET`** into the **api-gateway** Lambda environment and adds **`cloudfront_origin_secret`** as a sensitive module variable (for verifying CloudFront-originated traffic to the API). |
| **Bootstrap docs** | **`docs/infrastructure/bootstrap.md`** expanded for operator steps. |
| **Local plans** | **`.gitignore`** lists **`infrastructure/terraform/backend_override.tf`** so a **local** backend file used for **`terraform plan`** without S3 credentials is not committed by mistake. |
| **Lockfile / formatting** | **`.terraform.lock.hcl`** refreshed; **`main.tf`** **`terraform fmt`**; **`skills-lock.json`** updated for tooling pins. |

### 7.4 Secrets template — **`.env.secrets.example`**

Checked-in **example only** (no real secrets). Documents environment variables for:

- Terraform remote-state bootstrap (**`TF_STATE_BUCKET`**, **`TF_LOCK_TABLE`**),
- application integration (**`POSTGRES_CONNECTION_STRING`**, **`MONGODB_CONNECTION_STRING`**, **`AUTH_JWK_URI`**, **`CLOUDFRONT_ORIGIN_SECRET`**),
- RDS values expected by Terraform / GitHub Actions (**`RDS_MASTER_USERNAME`**, **`RDS_MASTER_PASSWORD`**),
- optional explicit AWS keys when not using OIDC / instance credentials.

Operators copy to **`.env.secrets`** (kept local and gitignored); variable names align with workflow and Terraform usage described in §2 and **`bootstrap.md`**.
