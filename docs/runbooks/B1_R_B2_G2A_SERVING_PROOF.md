# B1 R-B2 / G2a — Artifact 2a version-bearing read serving proof

This is the durable, sanitized record of B1 Wave 5 tasks **5.2 (G2a)** and **5.3 (R-B2)**.
No secret values, connection strings, JWTs, or passwords appear here. Local operational
captures used during execution are not committed.

## Decision

**GO — R-B2.** Artifact 2a is serving on the sole active `portfolio-service` revision. Every
serving digest returns a numeric Portfolio_Version on the authenticated read and on the direct
revision probe. G3 remains green (`violating_users = 0`). V20 remains the highest successful
Flyway version. Tasks **5.4–5.7** (caller migration / G5), Waves 6–7, public composition `PUT`,
Writer_Convergence/G2b, UI/B2 work, and Spec A 9.11–9.14 remain **unauthorized** and incomplete.

## Authorization

Owner authorization received **2026-08-26** to execute B1 R-B2 Tasks **5.2–5.3** in production:
build and push the historical Artifact 2a candidate, digest-deploy it to `portfolio-service`,
collect G2a serving evidence from every serving revision, and open this docs-only status PR.

## Artifact 2a candidate

| Field | Value |
|---|---|
| Source cut | `f22e2ffee78262f5526aec0bc8b4324076f30de7` (Wave 4 + Task 5.1 on V20 lineage) |
| Composition | Numeric `PortfolioResponse.version`; Task 5.1 MVC proof; read-only `AssetCatalogController`; unexposed `HoldingReplacementService`; **no** `CompositionController` / public `PUT`; **no** Wave 6 `expectedVersion` seed switch; **no** V21+ |
| Candidate tag | `wealthprodacr.azurecr.io/portfolio-service:b1-r-b2-f22e2ffee782-20260826` |
| Immutable digest | `sha256:d544649f5b67baec8b563016882d239d3ecb9c5672399586e0bc656c78961d4f` |
| OCI revision label | `f22e2ffee78262f5526aec0bc8b4324076f30de7` |
| Fresh verification | `:portfolio-service:test` and `:portfolio-service:integrationTest` green on detached cut (`--no-daemon --rerun-tasks`); worktree clean |

## Deploy

| Field | Value |
|---|---|
| Dispatch | [Deploy run 32982880866](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32982880866) |
| Mode | `deployment_mode=digest`, `services=portfolio-service` |
| `expected_main_sha` | `b5d54c3860771a81cf33962ca3dd32e4d218aea9` |
| `prebuilt_digest` | `wealthprodacr.azurecr.io/portfolio-service@sha256:d544649f5b67baec8b563016882d239d3ecb9c5672399586e0bc656c78961d4f` |
| Jobs | `deploy (portfolio-service)` success; `Build Docker image` and `Push Docker image` **skipped**; `Prove digest path skipped build and push` success; `deploy-frontend`, `seed`, `verify` **skipped**; `assert-scoped-non-interference` success |
| Prior R-B abort digest | `sha256:d111132f576780fa5fec67dfc26ada3324153794746d21fe84b93b6822be3535` (`portfolio-service--0000080`) — not used |

## Serving evidence (post-deploy)

| Field | Value |
|---|---|
| Revision | `portfolio-service--0000081` |
| Image | `wealthprodacr.azurecr.io/portfolio-service@sha256:d544649f5b67baec8b563016882d239d3ecb9c5672399586e0bc656c78961d4f` |
| Digest | `sha256:d544649f5b67baec8b563016882d239d3ecb9c5672399586e0bc656c78961d4f` (exact candidate) |
| Revisions mode | `Single` |
| Active revisions | exactly one (`portfolio-service--0000081`, `Running`, traffic `100`) |
| Provisioning | `Succeeded` |
| Ingress | unchanged (`external: false`; not reopened) |
| Peer non-interference | `api-gateway` remains `api-gateway--0000076` / `sha256:2da5b303…`; `market-data-service--0000078` and `insight-service--0000078` image/revision unchanged vs pre-deploy baselines |

## Preconditions (Task 5.2 pre-deploy)

| Gate | Result |
|---|---|
| R-B portfolio baseline | Sole serving digest `sha256:d111132f…` on `portfolio-service--0000080` |
| G2 gateway baseline | `api-gateway--0000076` / `sha256:2da5b303fd15772792167f2b26dc62250b2d9858270db315eab1d6d1a1554aec`; ingress `null` |
| Flyway V17–V20 | each present and `success = true`; V20 highest by `installed_rank` |
| G3 | `violating_users = 0`; distribution only `portfolio_count = 1` (9 users before probe) |
| Scoped cut↔main drift | empty for portfolio/common/`config`/deploy workflows |

## G2a serving proof (Task 5.2)

Ingress remained closed, so the authenticated probe used in-revision gateway loopback
(`api-gateway--0000076`) and the direct revision probe used portfolio loopback
(`portfolio-service--0000081`).

### Authenticated gateway path

| Check | Result |
|---|---|
| `POST /api/auth/signup` | **HTTP 201** |
| Probe email pattern | `b1-r-b2-g2a-<yyyyMMddHHmmss>@example.com` |
| Recorded probe identity | `b1-r-b2-g2a-20260826152726@example.com` |
| Returned `userId` | `ec2b6b61-cf1a-4940-aaa0-d449e7777f38` |
| `GET /api/portfolio` | **HTTP 200**; body includes unquoted numeric `"version":0` |
| Portfolio id | `01e830d4-9e43-4570-8542-bafd47588867` |
| `GET /api/assets` (sanity) | **HTTP 200**, non-empty body, `ETag` present |

### Direct serving revision

| Revision | Digest | Traffic | Direct `GET /api/portfolio` (`X-User-Id`) | Numeric version |
|---|---|---|---|---|
| `portfolio-service--0000081` | `sha256:d544649f…` | `100` | **HTTP 200** | `0` (matches gateway + DB) |

Serving set size was **1** under `Single` mode. One revision pair is therefore the complete G2a set.

### Database match and post-probe G3

| Check | Result |
|---|---|
| Users by probe email | **1** |
| Portfolios for probe `user_id` | **1** |
| DB `portfolios.version` | **0** (equals JSON number) |
| Post-probe `violating_users` | **0** |
| Post-probe distribution | only `portfolio_count = 1` (10 users / 10 portfolios) |
| Flyway after deploy | V20 still highest; no V21; all rows successful |

## Explicit non-starts

This gate does **not** authorize:

- Tasks **5.4–5.7** caller migration, seed credentials, `409` workflow changes, or G5
- Invoking `POST /api/internal/portfolio/seed` or claiming Writer_Convergence / G2b
- Waves 6–7, candidate packaging (7.5/R-C), or public `PUT /api/portfolio/holdings`
- Frontend/UI / B2 work
- Spec A 9.11–9.14 (including ingress reopen)
- Any V21 or ad-hoc production data repair
- Rolling back V20 or the R-A gateway

Any future portfolio rollout invalidates this G2a binding until re-proven.

## Abort path (not used)

On G2a failure the R-B digest would have been restored with digest-mode deploy of:

```text
wealthprodacr.azurecr.io/portfolio-service@sha256:d111132f576780fa5fec67dfc26ada3324153794746d21fe84b93b6822be3535
```

Abort was not required. V20 and the R-A gateway remain in place either way.
