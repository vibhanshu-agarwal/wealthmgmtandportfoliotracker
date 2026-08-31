# B1 Task 5.7 / G5 — public synthetic evidence pending

This is the durable, sanitized record of the authorized G5 attempts for Wave 5b Task **5.7**.
No secret values, JWTs, or passwords appear here.

## Decision

**G5 remains blocked — but not by current public reachability.** Two historical authorized Azure
synthetic dispatches from `cursor/b1-wave5b-seed-caller-migration` failed **before any seed
request** when public TLS to `api.vibhanshu-ai-portfolio.dev` reset at handshake. The custom-domain
binding has since been restored under separately authorized, guarded operations; no post-restore G5
synthetic has been authorized or run.

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

## Authorized attempts (exactly two; no further dispatch)

| Run | Branch | Outcome | Failure point |
|---|---|---|---|
| [33046987880](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33046987880) | `cursor/b1-wave5b-seed-caller-migration` | failure | `Re-seed E2E portfolio holdings` — `curl: (35) Recv failure: Connection reset by peer`; `login failed HTTP 000000` |
| [33047168136](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33047168136) | `cursor/b1-wave5b-seed-caller-migration` | failure | identical TLS reset / HTTP `000000` on login |

Neither run reached `POST /api/internal/portfolio/seed`. Neither produced a `409`. Local curl to
the public API host reproduced the same TLS handshake reset.

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

`NEXT_PUBLIC_API_BASE_URL` for the frontend and synthetic workflows points at that host. The restored
endpoint is healthy in the independent read-back, but reachability alone cannot prove the three callers
sent version-bearing requests. The restoration evidence remains open for independent review in backlog
item [`api-gateway-custom-domain-binding`](../todos/backlog/api-gateway-custom-domain-binding/README.md).

**Do not read Spec A 9.14 as unblocking G5.**

## Synthetic dispatch gate (2026-08-31)

PR [#194](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/194) independently
reviewed and merged the custom-domain restoration live read-back evidence at `main@98371587`. That
review does **not** satisfy Task 5.7 and does **not** authorize G5.

While G5 remains blocked, `synthetic-monitoring.yml` has **no unattended schedule** — only
`workflow_dispatch` remains. Any manual dispatch still requires separately recorded owner
authorization and must not be treated as G5 evidence from source alone. A CI guard rejects
reintroduction of a top-level schedule trigger.

## Resume conditions for Task 5.7

Resume G5 only after **one** of:

1. The independent review of the executed custom-domain recovery evidence is complete, **then** one
   separately authorized public Azure synthetic exercises all three callers. The binding, certificate,
   ingress, and independent `200` read-back are recorded above; neither that restoration nor the
   review alone satisfies Task 5.7; or
2. A separately designed and authorized private-reachability test that genuinely executes all three
   real GitHub-hosted callers (shell, global-setup, azure-api-smoke).

The recovery evidence in [`API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`](API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md)
does **not** satisfy either resume condition by itself. The independent review and a separately
authorized caller exercise remain mandatory.

## Explicit non-claims

- Does **not** claim G5 green or Writer_Convergence.
- Does **not** close Task 5.7, authorize Wave 6 / R-B3, public `PUT`, a retry, or any further
  custom-domain or deployment change.
- Does **not** authorize further synthetic dispatches until a resume condition above is met.
- Does **not** claim the custom-domain restore or a healthy steady-state host satisfies Task 5.7.
