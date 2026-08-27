# B1 Task 5.7 / G5 — public synthetic blocked by Spec A ingress fence

This is the durable, sanitized record of the authorized G5 attempts for Wave 5b Task **5.7**.
No secret values, JWTs, or passwords appear here.

## Decision

**G5 is blocked — not by Wave 5b callers.** Two authorized Azure synthetic dispatches from
`cursor/b1-wave5b-seed-caller-migration` failed **before any seed request**. Public TLS to
`api.vibhanshu-ai-portfolio.dev` resets at handshake because `api-gateway` ingress is fully
disabled under the intentional Spec A production fence (checkpoints 9.11–9.14 still unauthorized).

Tasks **5.4–5.6** remain implemented (CI-green on PR #161). Task **5.7 remains unchecked**.
Wave 6 / R-B3 and Writer_Convergence remain gated. Gateway-revision loopback is **not** an
adequate G5 substitute: it can prove API behavior, not that all three GitHub-hosted callers send
version-bearing requests.

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

## Resume conditions for Task 5.7

Resume G5 only after **one** of:

1. Spec A checkpoints **9.11–9.14** complete in order and gateway ingress is reopened; then one
   authorized public Azure synthetic that exercises all three callers; or
2. A separately designed and authorized private-reachability test that genuinely executes all three
   real GitHub-hosted callers (shell, global-setup, azure-api-smoke).

## Explicit non-claims

- Does **not** claim G5 green or Writer_Convergence.
- Does **not** authorize Wave 6 / R-B3, public `PUT`, ingress reopen, or Spec A 9.11+.
- Does **not** authorize further synthetic dispatches until a resume condition above is met.
