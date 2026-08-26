# B1 R-B / G3 — Artifact 2 V20 serving proof

This is the durable, sanitized record of B1 Wave 3 tasks **3.5–3.7** and release cut **R-B**.
No secret values, connection strings, JWTs, or passwords appear here. Local operational
captures used during execution are not committed.

## Decision

**GO — R-B.** Artifact 2 is serving on the sole active `portfolio-service` revision. Flyway V20 is
applied and successful. G3 is green (`violating_users = 0`). G2a/R-B2, Tasks 5.2–5.7, caller
migration, Waves 6–7, public composition `PUT`, UI work, ingress reopen, and any V21 remain
**unauthorized** and incomplete.

## Authorization

Owner authorization received **2026-08-26** to execute B1 R-B Tasks **3.5–3.7** only: build and push
the historical Artifact 2 candidate, digest-deploy it to `portfolio-service`, allow Flyway V20 to
run, collect sanitized gate evidence, and open this docs-only status PR.

## Artifact 2 candidate

| Field | Value |
|---|---|
| Source cut | `25aa730e4b0cac79532a3b5d2235719cda520f54` (PR #152 merge) |
| Composition | V20 migration + `Portfolio` `@Version`/`updatedAt`; Waves 4–7 and Task 5.1 boundary test **absent** |
| Candidate tag | `wealthprodacr.azurecr.io/portfolio-service:b1-r-b-25aa730e4b0c-20260826` |
| Immutable digest | `sha256:d111132f576780fa5fec67dfc26ada3324153794746d21fe84b93b6822be3535` |
| OCI revision label | `25aa730e4b0cac79532a3b5d2235719cda520f54` |
| Fresh verification | `:portfolio-service:test` and `:portfolio-service:integrationTest` green on detached cut (`--no-daemon --rerun-tasks`); worktree clean |

## Deploy

| Field | Value |
|---|---|
| Dispatch | [Deploy run 32969683640](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32969683640) |
| Mode | `deployment_mode=digest`, `services=portfolio-service` |
| `expected_main_sha` | `5cf39079a7bd92e0f1b9724ae3ffd55e3fd1c07f` |
| `prebuilt_digest` | `wealthprodacr.azurecr.io/portfolio-service@sha256:d111132f576780fa5fec67dfc26ada3324153794746d21fe84b93b6822be3535` |
| Jobs | `deploy (portfolio-service)` success; `Build Docker image` and `Push Docker image` **skipped**; `Prove digest path skipped build and push` success; `deploy-frontend`, `seed`, `verify` **skipped**; `assert-scoped-non-interference` success |
| Pre-V20 containment digest | `sha256:abbb9d133df23f3ac2f17baa608ac87bc8805aed30b3cfffaacf81338a1c929b` (`portfolio-service:9b2cf0d…`, revision `portfolio-service--0000079`) |

## Serving evidence (post-deploy)

| Field | Value |
|---|---|
| Revision | `portfolio-service--0000080` |
| Image | `wealthprodacr.azurecr.io/portfolio-service@sha256:d111132f576780fa5fec67dfc26ada3324153794746d21fe84b93b6822be3535` |
| Digest | `sha256:d111132f576780fa5fec67dfc26ada3324153794746d21fe84b93b6822be3535` (exact candidate) |
| Revisions mode | `Single` |
| Active revisions | exactly one (`portfolio-service--0000080`, `Running`, traffic `100`) |
| Provisioning | `Succeeded` |
| Ingress | unchanged (`external: false`; not reopened) |
| Health (in-revision loopback) | `GET http://127.0.0.1:8080/api/portfolio/health` → **200** `{"service":"portfolio-service","status":"UP"}` |
| Peer non-interference | `api-gateway`, `market-data-service`, and `insight-service` image/revision unchanged vs pre-deploy baselines |

## Preconditions (Task 3.5)

| Gate | Result |
|---|---|
| G0a (authenticated writer-retirement probe via gateway loopback) | `POST /api/portfolio` → **405** with `Allow: GET`; `POST /api/portfolio/00000000-0000-0000-0000-000000000000/holdings` → **404**. Serving portfolio digest descended from writer-retirement lineage `e27762c`. |
| G0b | [run 32399211853](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32399211853) job `docker-build-verify` ([96530029529](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32399211853/job/96530029529)) conclusion `success` (`golden-path` + `dashboard-data`); fresh Artifact 2 unit/integration suites green |
| G2 | Sole active gateway revision remains `api-gateway--0000076` / `sha256:2da5b303fd15772792167f2b26dc62250b2d9858270db315eab1d6d1a1554aec`; ingress still `null`. Durable evidence: [`B1_R_A_G2_SERVING_PROOF.md`](B1_R_A_G2_SERVING_PROOF.md) |
| Flyway preflight | V17, V18, V19 present and successful; **V20 absent**; failed Flyway rows `0` |
| Data safety preflight | duplicate user portfolio groups `0`; non-positive holdings `0` |
| Diagnostic (not a fail) | `users_missing_portfolio = 5` before V20 (backfill target) |

## V20 postconditions (Task 3.6 schema / preservation)

| Check | Result |
|---|---|
| Flyway V17–V20 | each present and `success = true` (V20 description `Portfolio Composition Contract`) |
| Named constraints | `uq_portfolios_user_id` and `chk_asset_holdings_quantity_positive` present and validated |
| `asset_holdings.quantity` default | `NULL` (dropped) |
| Null `version` / `updated_at` | `0` |
| Non-positive holdings | `0` |
| Holdings preservation | row count `162` and checksum `d6b344a1fca6ed11b59a146e5fb8825d` equal pre-migration |

## G3 (Task 3.6 / 3.7)

Relational invariant (never an equal-total proxy):

```sql
SELECT COUNT(*) AS violating_users
FROM (
    SELECT u.id
    FROM users u
    LEFT JOIN portfolios p ON p.user_id = u.id::text
    GROUP BY u.id
    HAVING COUNT(p.id) <> 1
) violations;
```

| Check | Result |
|---|---|
| `violating_users` | **0** |
| Distribution | only `portfolio_count = 1` (9 users / 9 portfolios after V20 backfill) |

## Forward-only boundary after V20

V20 has committed in `flyway_schema_history`. Database repair is **forward-only**. Do not delete the
V20 history row, run `flyway repair`, drop V20 constraints, reverse its DDL, or redeploy the gateway
below Artifact 1. The unused pre-V20 containment digest
`sha256:abbb9d133df23f3ac2f17baa608ac87bc8805aed30b3cfffaacf81338a1c929b` was the abort image **only
before** V20 committed; it is not an unconditional rollback target now.

## Explicit non-starts

This gate does **not** authorize:

- Deploying current `main` Wave 4/5 `portfolio-service` runtime
- G2a / R-B2 or Tasks 5.2–5.7 (caller migration)
- Waves 6–7, candidate packaging (7.5/R-C), or public `PUT /api/portfolio/holdings`
- Claiming `GET /api/assets` catalog data is live (Wave 4b controller remains undeployed)
- Frontend/UI work
- Spec A 9.11–9.14 (including ingress reopen)
- Any V21 or ad-hoc production data repair
