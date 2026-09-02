# B1 Task 5.7 / G5 — owner close-out complete

This is the durable, sanitized record of the authorized G5 attempts for Wave 5b Task **5.7**.
No secret values, JWTs, or passwords appear here.

## Decision — GO / complete (2026-09-02)

**B1 G5 / Task 5.7 is complete.** The owner explicitly requested, “Please do the G5 close out.”
This records the separately reserved owner decision and checks Task 5.7 in the owning ledger.
The decision uses the successful three-caller run below and its independently reviewed evidence
merged via [PR #197](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/197)
at `b6c0da3f98a4a59bd810dbb77f273a1751946220` on `2026-08-31T18:26:34Z`.
That earlier evidence merge deliberately left the owner gate open; today's decision closes it.

Codex reverified run `33411410271` and actual Azure job `99551610739` as successful, read the
three caller markers, holdings-only success, and 9 passing tests from the run logs, and compared
the evidence source `f66d7ab6` with current `main@48d0aba8`. The three callers, shared version
helper, synthetic/deploy workflow wiring, and focused tests have no source drift. The current
inventory guard passes with exactly three callers. No new live dispatch or cloud operation was
needed for this close-out.

G5's prerequisite for B1 Wave 6 is satisfied. In a later, separately approved source assignment,
Tasks 6.1–6.4 merged through PR #217 at `main@d66bb23d` (reconciled 2026-09-03). Task 6.5,
R-B3 deployment/serving proof, Wave 7 public `PUT`, Writer_Convergence, B2 Tasks 5.6/6.3,
and unrelated backlogs are not closed by the G5 decision or that source merge.

> **Historical correction (2026-08-31).** The original TLS reset was first attributed solely to the
> Spec A ingress fence. That attribution was incomplete: Spec A 9.14 reopened ingress, while the
> custom-domain binding was still absent. The later authorized recovery restored that binding. See
> [historical failure and post-restore state](#historical-failure-and-post-restore-state-2026-08-31).

Tasks **5.4–5.6** merged via PR #161 on `main@0b5d60d1`; Task **5.7 is now checked**.
Gateway-revision loopback alone remains insufficient for G5: this decision relies on the actual
GitHub-hosted execution of all three callers. The seed remains version-tolerant until B1 Wave 6;
this close-out makes no Writer_Convergence claim.

## Source merge record

| Field | Value |
|---|---|
| PR | [#161](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/161) |
| Merge SHA | `0b5d60d1ef1c0c1002be69698db4d45244843bfe` |
| Scope | Tasks 5.4–5.6 source-only |
| Deploy / ingress / G5 | **not** authorized by this merge |

## Historical serving identity at the pre-restore attempts (R-B2)

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
| Azure job ID | `99551610739` |
| Azure job execution | `2026-08-31T15:57:48Z`–`2026-08-31T16:02:58Z` |
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

Independent verification: all four pre-warm probes against
`https://api.vibhanshu-ai-portfolio.dev` (`/actuator/health`, `/api/portfolio/health`,
`/api/market/health`, `/api/insights/health`) printed `HTTP 000000`. That `000000` value is the
workflow’s timeout/transport output form (not an HTTP status code). Those observations are recorded
separately from failures and remain distinct from the later successful holdings-only seed and
Playwright suite on the same public host. No market-data writer was enabled or claimed.

#### Evidence scope and later decision

- **Executed live evidence:** one authorized public Azure synthetic from the SHA above.
- **PR #197:** independently reviewed documentation of that run; merged without checking Task 5.7.
- **2026-09-02 owner decision:** closes Task 5.7 using that reviewed evidence and the unchanged
  caller inventory. No further live execution or production change is included.

## Historical failure and post-restore state (2026-08-31)

Spec A checkpoints 9.11–9.14 are complete. Checkpoint 9.14 reopened ACA external ingress on
`api-gateway--0000077` with `allowInsecure=false`
([`SPEC_A_9_14_REOPEN_INGRESS.md`](SPEC_A_9_14_REOPEN_INGRESS.md)). The historical G5 failures
preceded the separately authorized custom-domain recovery:

- guarded remote plan [33379974571](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33379974571) passed;
- guarded apply/bind [33380356530](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33380356530) restored the existing managed certificate binding, but its final immediate default-host health observation was non-`200` and left the workflow red;
- independent read-back at `2026-08-31T10:09:24.5519025Z` then observed both public health endpoints at `200`.

Historical read-back at `2026-08-31T10:09:24.5519025Z`:

| Endpoint | Result |
|---|---|
| `api-gateway.lemonmoss-ecef29d7.centralindia.azurecontainerapps.io` (default ACA) | `200` — independent read-back after the guarded apply/bind |
| `api.vibhanshu-ai-portfolio.dev` (configured frontend / synthetic host) | `200` — independent read-back after the guarded apply/bind |

The restoration read-back found a CNAME alias onto the gateway's ACA hostname. Container Apps
reported the exact custom hostname with `SniEnabled` binding and existing managed certificate
`mc-wealth-prod-ac-api-vibhanshu-ai-5159`; that certificate was `Succeeded`, had the expected
subject, and was CNAME-validated. The guarded post-bind verifier completed its binding, certificate, ingress,
and TLS assertions before the final default-host health observation made run 33380356530 red.

`NEXT_PUBLIC_API_BASE_URL` for the frontend and synthetic workflows points at that host.

Spec A 9.14 and PR #194 restored reachability; they did not themselves satisfy G5. PR #197
merged the later three-caller evidence without taking the owner decision. The separately recorded
2026-09-02 owner decision above now closes Task 5.7. Historical recovery and RCA records retain
their own scope and acceptance criteria.

## Synthetic dispatch and schedule state at close-out

`synthetic-monitoring.yml` still has **no unattended schedule**; only `workflow_dispatch`
remains. Further manual dispatch or schedule restoration requires separate owner authorization.
The existing CI guard rejects reintroduction of a top-level schedule trigger. Closing G5 changes
neither the workflow nor those permissions.

## Close-out checklist

| Condition | Evidence / decision |
|---|---|
| Caller migration merged | PR #161, `0b5d60d1ef1c0c1002be69698db4d45244843bfe` |
| Every real caller observed sending a version | Run `33411410271`, all three `expectedVersion=0` markers above |
| Actual Azure execution succeeded | Job `99551610739`, holdings-only seed, 9 passed; AWS intentionally skipped |
| Durable evidence independently reviewed and merged | PR #197, `b6c0da3f98a4a59bd810dbb77f273a1751946220` |
| Evidence still applicable at close-out | No caller/helper/workflow/focused-test drift from `f66d7ab6` to `48d0aba8`; inventory guard passes |
| Separately reserved owner decision recorded | 2026-09-02: “Please do the G5 close out” — **GO / complete** |

## Explicit non-claims

- The G5 close-out itself implemented no source change. B1 Tasks 6.1–6.4 subsequently merged
  through PR #217 and are checked in the owning ledger. Tasks 6.5–6.7 and Wave 7 remain unchecked;
  R-B3 deployment/serving proof, public `PUT` activation, and Writer_Convergence remain open.
- B2 Tasks 5.6 and 6.3, UI exposure, and final placement retain their separate decisions.
- No synthetic retry, further manual dispatch, unattended schedule restoration, custom-domain
  operation, or deployment is authorized or performed by this close-out.
- No market-data writer path, historical RCA closure, or unrelated backlog completion is claimed.
