# B1 Task 5.7 / G5 — public synthetic blocked at the public API host

This is the durable, sanitized record of the authorized G5 attempts for Wave 5b Task **5.7**.
No secret values, JWTs, or passwords appear here.

## Decision

**G5 is blocked — not by Wave 5b callers.** Two authorized Azure synthetic dispatches from
`cursor/b1-wave5b-seed-caller-migration` failed **before any seed request**. Public TLS to
`api.vibhanshu-ai-portfolio.dev` reset at handshake.

> **Correction (2026-08-31).** This record originally attributed that TLS reset solely to the
> Spec A ingress fence. That attribution was incomplete. Spec A checkpoint **9.14 has since
> reopened gateway ingress** — the default ACA endpoint now serves healthy traffic — and TLS to
> `api.vibhanshu-ai-portfolio.dev` **still fails**. There were two independent causes; 9.14
> cleared one. See [Post-9.14 status](#post-914-status-2026-08-31).

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

## Post-9.14 status (2026-08-31)

Spec A checkpoints 9.11–9.14 are complete. Checkpoint 9.14 reopened ACA external ingress on
`api-gateway--0000077` with `allowInsecure=false`
([`SPEC_A_9_14_REOPEN_INGRESS.md`](SPEC_A_9_14_REOPEN_INGRESS.md)). Live read-back:

| Endpoint | Result |
|---|---|
| `api-gateway.lemonmoss-ecef29d7.centralindia.azurecontainerapps.io` (default ACA) | `200` — `{"groups":["liveness","readiness"],"status":"UP"}` |
| `api.vibhanshu-ai-portfolio.dev` (configured frontend / synthetic host) | **TLS handshake failure** — `schannel: failed to receive handshake, SSL/TLS connection failed` |

DNS is not the problem: `api.vibhanshu-ai-portfolio.dev` resolves as a CNAME alias onto the
gateway's ACA hostname. The Container App reports `properties.configuration.ingress.customDomains
= null` — **no custom domain, and therefore no certificate, is bound to the app**, so the gateway
does not serve that hostname. This proves nothing is bound; it does **not** establish whether a
managed-certificate resource exists in the Container Apps environment. Certificate inventory was
not separately verified.

`NEXT_PUBLIC_API_BASE_URL` for the frontend and synthetic workflows points at that host, so the
G5 callers still cannot reach the API. Tracked as backlog item
[`api-gateway-custom-domain-binding`](../todos/backlog/api-gateway-custom-domain-binding/README.md).

**Do not read Spec A 9.14 as unblocking G5.**

## Resume conditions for Task 5.7

Resume G5 only after **one** of:

1. The `api.vibhanshu-ai-portfolio.dev` custom-domain binding and a bound certificate are
   restored and verified — `customDomains` non-null, and
   `curl https://api.vibhanshu-ai-portfolio.dev/actuator/health` returning `200` with a verifying
   certificate — **in addition to** the now-satisfied condition that Spec A 9.11–9.14 are complete
   and gateway ingress is reopened; then one authorized public Azure synthetic that exercises all
   three callers; or
2. A separately designed and authorized private-reachability test that genuinely executes all three
   real GitHub-hosted callers (shell, global-setup, azure-api-smoke).

Condition 1's Spec A clause alone is **not** sufficient and never was — that is the correction
recorded above.

## Explicit non-claims

- Does **not** claim G5 green or Writer_Convergence.
- Does **not** authorize Wave 6 / R-B3, public `PUT`, a custom-domain change, or any deployment.
- Does **not** authorize further synthetic dispatches until a resume condition above is met.
