# Phase 3 CI/CD + Production Dependency Verification — 2026-04-17 (v2)

**Previous revision:** [CHANGES_INFRA_SUMMARY_2026-04-17_v1.md](./CHANGES_INFRA_SUMMARY_2026-04-17_v1.md) — Phase 3 deploy workflow (`deploy.yml`), `jq`-based Lambda env injection, `verify-prod-deps.sh` documentation, and deployment-plan branch alignment.

Repository path (clone): `docs/changes/CHANGES_INFRA_SUMMARY_2026-04-17_v1.md`

---

## Summary (changes since v1)

After v1 shipped, CI/CD and container builds hit real-runner and AWS API constraints. This revision documents **corrective and hardening changes** to:

1. **Frontend → S3** — align with Next.js **`output: "standalone"`** (no `dist` / `build` / `out` tree).
2. **Backend build on Ubuntu** — ensure **`gradlew` is executable** in GitHub Actions and in Git.
3. **Lambda Web Adapter images** — pin to **`1.0.0`** on ECR Public (`:latest` invalid; `0.8.4` superseded for stability).
4. **Lambda environment variables** — **remove `AWS_REGION`** from the configurable map (AWS reserves it; runtime injects it).
5. **Docs/specs** — keep examples and deployment-verification narratives consistent with the adapter version and S3 behavior.
6. **Docker Compose / E2E** — remove baked-in **`SERVER_PORT`/`PORT=8080`** from **`portfolio-service`** and **`market-data-service`** runtime images so Spring honors **`application.yml`** (**`8081`** / **`8082`**); fixes **`service_healthy`** / **`insight-service`** startup in **`docker compose`** and **`.github/workflows/frontend-e2e-integration.yml`**.

Operational note (outside git): the **S3 bucket used for static assets** was switched to **allow public read** so anonymous clients can load `_next/static` and `public` objects; prefer **CloudFront + OAC + private bucket** when hardening.

---

## 1. GitHub Actions: `.github/workflows/deploy.yml` (delta since v1)

### 1.1 `deploy-frontend` — Next.js artifact path (replaces v1 “dist / build / out”)

**v1 behavior (superseded):** Resolve `frontend/dist`, `frontend/build`, or `frontend/out` and `aws s3 sync` the chosen directory to the bucket root.

**v2 behavior:**

- **`npm ci`** instead of `npm install` for reproducible CI installs.
- After **`npm run build`**, require **`frontend/.next/static`** (produced by **`output: "standalone"`** in `frontend/next.config.ts`).
- **`aws s3 sync`** in two passes:
  - `frontend/.next/static/` → `s3://${{ secrets.S3_BUCKET_NAME }}/_next/static/` (**`--delete`**)
  - `frontend/public/` → bucket root (if the directory exists), **`--delete`**
- Inline comments state that **HTML / SSR / API** are **not** uploaded here; the **Next.js server** (e.g. **`frontend-cd.yml`** / GHCR image) must serve those paths unless you introduce a full **`output: "export"`** flow (not compatible with current Better Auth API routes without further work).

**Rationale:** v1’s flexible resolver never matched this repo’s Next config, so the job failed with “no deployable frontend artifact directory”.

### 1.2 `deploy-backend` — Gradle wrapper permissions

- New step: **`chmod +x ./gradlew`** before **`./gradlew bootJar`**.
- **Repository:** `gradlew` committed with executable bit **`100755`** (`git update-index --chmod=+x gradlew`) so Linux checkouts are not mode `644` by default (common when the wrapper is edited on Windows).

**Rationale:** Ubuntu runners returned **`Permission denied`** (exit **126**) for `./gradlew`.

### 1.3 Lambda environment — reserved `AWS_REGION` (replaces v1 jq payload)

**v1 behavior:** `jq` `Variables` included **`AWS_REGION`** (and **`AWS_ACCOUNT_ID`**) from secrets.

**v2 behavior:**

- **`AWS_REGION` removed** from the Lambda **`Variables`** object and from the **`--arg`** list for that step.
- Workflow **comments** document that **`AWS_REGION`** and **`AWS_DEFAULT_REGION`** must **not** be set in Lambda’s configurable environment: Lambda **reserves** them and sets **`AWS_REGION`** to the function’s region. Spring and AWS SDK v2 read that value automatically (e.g. `${AWS_REGION:us-east-1}` in YAML still resolves).
- **`AWS_ACCOUNT_ID`** remains in **`Variables`** for applications that may read it from the environment (optional; not used by `portfolio-service` Java sources at time of writing).
- **`AWS_REGION`** secret is **still required** for **`configure-aws-credentials`**, **`amazon-ecr-login`**, and the **ECR image URI** construction in **`Update Lambda function image`** — only the **Lambda env map** was corrected.

**Rationale:** `aws lambda update-function-configuration` failed with **`InvalidParameterValueException`**: reserved key **`AWS_REGION`**.

### 1.4 Secrets checklist (delta)

Unchanged from v1 for **GitHub Secrets** themselves (`AWS_REGION` is still a repo secret for CI).

**Clarification for operators:**

| Secret / variable | In GitHub Actions job `env`? | In Lambda `Variables` JSON? |
| --- | --- | --- |
| `AWS_REGION` | Yes (AWS CLI, ECR URI) | **No** — reserved; set by Lambda |
| `AWS_ACCOUNT_ID` | Yes (ECR URI, optional in Lambda env) | Yes (v2 payload) |

---

## 2. Docker: AWS Lambda Web Adapter image tags

| Service / context | v1 or prior | v2 |
| --- | --- | --- |
| `market-data-service/Dockerfile` | `public.ecr.aws/awsguru/aws-lambda-adapter:latest` (**not found** on ECR Public) | **`…:1.0.0`** |
| `portfolio-service/Dockerfile` | **`0.8.4`** (stability concerns) | **`1.0.0`** |
| `api-gateway`, `insight-service` | Already **`1.0.0`** | Unchanged |

**Rationale:** ECR Public does not publish a usable **`latest`** tag for this repository; **`1.0.0`** is pinned consistently across services that bundle the adapter binary.

### 2.1 Spring `server.port` vs baked `SERVER_PORT` / `PORT` (Compose + E2E)

**Problem:** The **`portfolio-service`** and **`market-data-service`** runtime stages set **`ENV SERVER_PORT=8080`** and **`ENV PORT=8080`** for Lambda alignment. Spring Boot treats those as **`server.port`**, which **overrides** `application.yml` (**`8081`** for portfolio, **`8082`** for market-data). **`docker-compose.yml`** still mapped ports, **`PORTFOLIO_SERVICE_URL`**, and **`curl`-based `healthcheck`s** to **8081** / **8082**, so the JVM listened on **8080** while health checks hit **8081** / **8082** → **`unhealthy`** → dependent services (`insight-service`, `api-gateway`) failed with *dependency failed to start: container portfolio-service is unhealthy* (and similar for full-stack E2E on GitHub Actions).

**Fix:** Remove **`ENV SERVER_PORT`** and **`ENV PORT`** from those Dockerfiles; document that **local / Compose** use YAML ports, while **AWS Lambda** continues to set **`SERVER_PORT=8080`** (and **`PORT`**) via **`deploy.yml`** `jq` / function environment so production behavior is unchanged. **`EXPOSE`** metadata updated to **`8081`** / **`8082`** respectively.

**Git:** `176437d` — `fix(docker): remove baked SERVER_PORT so compose healthchecks match Spring`.

---

## 3. Documentation and specs (delta)

- **`.kiro/specs/aws-deployment/deployment-plan.md`** — example `COPY` line updated from **`latest`** to **`1.0.0`** (avoid repeating the ECR metadata failure).
- **`docs/specs/deployment-verification-pipeline/`** and **`.kiro/specs/deployment-verification-pipeline/`** — Stage 3 / failure-matrix text updated so the documented adapter tag is **`1.0.0`** (replacing stale **`0.8.4`** in “current state” sentences). Historical bullets that say “upgraded from 0.8.4 → 1.0.0” were left where they read as a changelog.

**Unchanged from v1 in this delta (still accurate):**

- `verify-prod-deps.sh` narrative (Neon / Upstash / Aiven verification).
- Branch targeting **`architecture/cloud-native-extraction`** for Phase 3 automation.
- **`jq`** + **`apt-get install jq`** approach for Lambda environment JSON (v1); v2 only **shrinks** the set of keys sent to AWS.

---

## 4. Key decisions (v2 additions and supersessions)

| Decision | Rationale |
| --- | --- |
| Sync **`_next/static`** + **`public`** to S3 instead of a single `dist`/`out` folder | Matches **Next standalone** build output; fixes CI and matches how hashed chunks are usually CDN-hosted. |
| Do not set **`AWS_REGION`** in Lambda `Variables` | **Reserved** by AWS; runtime provides it; duplicate configuration breaks `update-function-configuration`. |
| Pin Lambda Web Adapter to **`1.0.0`** everywhere in Dockerfiles | **`latest`** missing on ECR Public; **`1.0.0`** aligns services and avoids **`0.8.4`** adapter issues. |
| `chmod +x gradlew` in CI + executable bit in Git | Eliminates **126 / Permission denied** on Ubuntu runners. |
| Do not bake **`SERVER_PORT`/`PORT`** into service images used by **Compose** | Spring must use **`application.yml`** ports for **healthchecks** and **inter-service URLs**; Lambda overrides via **managed environment** only. |

**Supersedes (v1 table, for traceability):**

| v1 decision | Status in v2 |
| --- | --- |
| “Keep frontend artifact resolution flexible (`dist` / `build` / `out`)” | **Superseded** — replaced by explicit **`.next/static` + `public`** sync for this repository’s Next configuration. |

---

## 5. Verification commands (v1 baseline + v2 additions)

- `./verify-prod-deps.sh` (or individual `./verify-db.sh`, `./verify-redis.sh`, `./verify-kafka.sh` as documented in v1).

CI verification for the deploy workflow itself:

- `push` to **`architecture/cloud-native-extraction`** triggers **`.github/workflows/deploy.yml`**; confirm **`deploy-frontend`** and **`deploy-backend`** both green after the above fixes.

Full-stack Compose / E2E (after **§2.1** image fix):

- **`docker compose up -d --build`** locally, or **`.github/workflows/frontend-e2e-integration.yml`** on pushes to **`main`** or **`architecture/**`** (per workflow `branches`) — confirm **`portfolio-service`** and **`market-data-service`** reach **`healthy`** before **`insight-service`** / **`api-gateway`** start.
