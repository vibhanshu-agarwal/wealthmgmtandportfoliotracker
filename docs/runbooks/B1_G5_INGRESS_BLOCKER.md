# B1 Task 5.7 / G5 — public synthetic evidence pending independent review

This is the durable, sanitized record of the authorized G5 attempts for Wave 5b Task **5.7**.
No secret values, JWTs, or passwords appear here.

## Decision

**G5 / Task 5.7 remain open pending independent review** of the executed three-caller public Azure
synthetic evidence below. The custom-domain binding was restored earlier under separately authorized,
guarded operations (PR #194 reviewed that restoration evidence). A separately authorized public
Azure synthetic has now run from `main@f66d7ab6a4db1a327fd030ba9897bfc431104945` and produced the
three required version-bearing caller markers. That live run is **executed evidence**; this document
update is **source-only**. Neither automatically closes Task 5.7, unblocks Wave 6 / R-B3, public
`PUT`, or the custom-domain backlog.

> **Historical correction (2026-08-31).** The original TLS reset was first attributed solely to the
> Spec A ingress fence. That attribution was incomplete: Spec A 9.14 reopened ingress, while the
> custom-domain binding was still absent. The later authorized recovery restored that binding. See
> [historical failure and post-restore state](#historical-failure-and-post-restore-state-2026-08-31).

Tasks **5.4–5.6** remain implemented (merged source-only on `main@0b5d60d1`, PR #161). Task **5.7
remains unchecked**. Wave 6 / R-B3 and Writer_Convergence remain gated. Gateway-revision loopback is
**not** an adequate G5 substitute: it can prove API behavior, not that all three GitHub-hosted
callers send version-bearing requests.

## Source merge record

| Field | Value |
|---|---|
| PR | [#161](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/161) |
| Merge SHA | `0b5d60d1ef1c0c1002be69698db4d45244843bfe` |
| Scope | Tasks 5.4–5.6 source-only |
| Deploy / ingress / G5 | **not** authorized by this merge |

## Serving identity at attempt time (unchanged from R-B2)

| Field | Value |
|---|---|
| Portfolio revision | `portfolio-service--0000081` |
| Portfolio digest | `sha256:d544649f5b67baec8b563016882d239d3ecb9c5672399586e0bc656c78961d4f` |
| Gateway revision | `api-gateway--0000076` / `sha256:2da5b303…` |
| Gateway ingress | disabled (`external` / `fqdn` / `targetPort` all null) |
| V20 / G3 | unchanged; no portfolio rollout since R-B2 |

## Authorized attempts

Exactly **two historical failures** plus **one authorized post-restore three-caller success** (no
automatic retry). Further dispatches still require separately recorded owner authorization.

### Historical failures (pre-restore)

| Run | Branch | Outcome | Failure point |
|---|---|---|---|
| [33046987880](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33046987880) | `cursor/b1-wave5b-seed-caller-migration` | failure | `Re-seed E2E portfolio holdings` — `curl: (35) Recv failure: Connection reset by peer`; `login failed HTTP 000000` |
| [33047168136](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33047168136) | `cursor/b1-wave5b-seed-caller-migration` | failure | identical TLS reset / HTTP `000000` on login |

Neither historical run reached `POST /api/internal/portfolio/seed`. Neither produced a `409`. Local
curl to the public API host reproduced the same TLS handshake reset.

### Post-restore three-caller evidence run (2026-08-31)

| Field | Value |
|---|---|
| Workflow | `Synthetic Monitoring` (`.github/workflows/synthetic-monitoring.yml`) |
| Event | `workflow_dispatch` (exactly one dispatch; no retry) |
| Ref / SHA | `main` @ `f66d7ab6a4db1a327fd030ba9897bfc431104945` |
| Run ID | `33411410271` |
| Run URL | https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271 |
| Overall conclusion | **success** |
| Azure job | `Run Azure Synthetic Suite` — **success** (not skipped) |
| AWS job | `Run AWS Synthetic Suite` — skipped (`CLOUD_PROVIDER=azure`) |
| Re-seed step | **success** — holdings only; log: `seed succeeded (holdings only; no market-data write path)` |
| Playwright | **9 passed** |
| Market-data writer | **not** enabled; `SKIP_MARKET_DATA_SEED=true`; seed path claims holdings-only |

Required caller markers observed in sanitized logs (all three real GitHub-hosted callers):

```
[b1-g5][synthetic-shell] expectedVersion=0
[b1-g5][global-setup] expectedVersion=0
[b1-g5][azure-api-smoke] expectedVersion=0
```

Caller mapping:

| Marker | Caller source |
|---|---|
| `synthetic-shell` | `.github/workflows/scripts/seed-portfolio-with-version.sh` |
| `global-setup` | `frontend/tests/e2e/global-setup.ts` |
| `azure-api-smoke` | `frontend/tests/e2e/azure-synthetic/api-live-smoke.spec.ts` |

#### Cold-start observations (not treated as run failure)

Pre-warm against `https://api.vibhanshu-ai-portfolio.dev` recorded transport timeouts on all four
health paths (`/actuator/health`, `/api/portfolio/health`, `/api/market/health`,
`/api/insights/health`) as HTTP `000` / `000000`. Those observations are recorded separately from
failures. After pre-warm, login, version read, seed, and the Playwright suite completed successfully
on the same public host. No market-data writer was enabled or claimed.

#### Explicit scope of this evidence

- **Executed live evidence:** one authorized public Azure synthetic from the SHA above.
- **This doc / evidence PR:** source-only documentation of that run for independent review.
- Does **not** check `- [ ] **5.7 G5 evidence.**`, close G5, unblock Wave 6 / R-B3, public `PUT`,
  or close the custom-domain backlog.

## Historical failure and post-restore state (2026-08-31)

Spec A checkpoints 9.11–9.14 are complete. Checkpoint 9.14 reopened ACA external ingress on
`api-gateway--0000077` with `allowInsecure=false`
([`SPEC_A_9_14_REOPEN_INGRESS.md`](SPEC_A_9_14_REOPEN_INGRESS.md)). The historical G5 failures
preceded the separately authorized custom-domain recovery:

- guarded remote plan [33379974571](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33379974571) passed;
- guarded apply/bind [33380356530](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33380356530) restored the existing managed certificate binding, but its final immediate default-host health observation was non-`200` and left the workflow red;
- independent read-back at `2026-08-31T10:09:24.5519025Z` then observed both public health endpoints at `200`.

Current read-back:

| Endpoint | Result |
|---|---|
| `api-gateway.lemonmoss-ecef29d7.centralindia.azurecontainerapps.io` (default ACA) | `200` — independent read-back after the guarded apply/bind |
| `api.vibhanshu-ai-portfolio.dev` (configured frontend / synthetic host) | `200` — independent read-back after the guarded apply/bind |

DNS remains a CNAME alias onto the gateway's ACA hostname. Current Container Apps control-plane
read-back reports the exact custom hostname with `SniEnabled` binding and existing managed certificate
`mc-wealth-prod-ac-api-vibhanshu-ai-5159`; that certificate is `Succeeded`, has the expected subject,
and remains CNAME-validated. The guarded post-bind verifier completed its binding, certificate, ingress,
and TLS assertions before the final default-host health observation made run 33380356530 red.

`NEXT_PUBLIC_API_BASE_URL` for the frontend and synthetic workflows points at that host.

**Do not read Spec A 9.14 or the three-caller run alone as closing Task 5.7.** Independent review of
the executed evidence remains required before checking the task or advancing Wave 6 / R-B3.

## Synthetic dispatch gate (2026-08-31)

PR [#194](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/194) independently
reviewed and merged the custom-domain restoration live read-back evidence at `main@98371587`. That
review does **not** satisfy Task 5.7 and does **not** authorize G5 by itself.

While G5 / Task 5.7 remain open for independent review, `synthetic-monitoring.yml` has **no
unattended schedule** — only `workflow_dispatch` remains. Any further manual dispatch still requires
separately recorded owner authorization. A CI guard rejects reintroduction of a top-level schedule
trigger.

## Resume / close conditions for Task 5.7

Task **5.7 remains unchecked** until independent review accepts the executed three-caller evidence
(run `33411410271`) — or a separately designed and authorized private-reachability test that
genuinely executes all three real GitHub-hosted callers (shell, global-setup, azure-api-smoke).

The recovery evidence in [`API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`](API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md)
and PR #194 do **not** close Task 5.7 by themselves.

## Explicit non-claims

- Does **not** claim Task 5.7 complete, G5 closed, or Writer_Convergence.
- Does **not** authorize Wave 6 / R-B3, public `PUT`, a retry, or any further custom-domain or
  deployment change.
- Does **not** claim this source-only evidence PR merges or closes the task; review is mandatory.
- Does **not** enable or claim any market-data writer path.
