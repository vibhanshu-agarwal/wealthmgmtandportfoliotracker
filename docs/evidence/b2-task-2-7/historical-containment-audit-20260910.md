# B2 Task 2.7 — Historical Backend-Before-Adapter Containment Audit

**Status:** Draft evidence — independent B1 review required; Task 2.7 remains open.

## Scope and non-authorizations

This repository-evidence reconstruction covers the interval between the B1 decimal-string read-side deployment and the B2 tolerant frontend adapter. It used no cloud access and makes no deployment, cache-purge, rollback, feature-enablement, workflow, or production-impact claim.

## Evidence record

| Fact | Repository evidence | Result |
|---|---|---|
| String-producing backend | `f22e2ffee78262f5526aec0bc8b4324076f30de7`; `PortfolioResponse.HoldingResponse.quantity` uses `ToPlainStringSerializer` | The deployed portfolio response emitted quantity as a JSON string. |
| Backend deployment | `docs/runbooks/B1_R_B2_G2A_SERVING_PROOF.md`; Deploy run `32982880866` | Only `portfolio-service` was digest-deployed as `portfolio-service--0000081` / `sha256:d544649f5b67baec8b563016882d239d3ecb9c5672399586e0bc656c78961d4f`. |
| Frontend deployment effect | Same runbook's Deploy table | `deploy-frontend` was skipped, so this event introduced no new frontend artifact. |
| Public reachability | Same runbook's Serving evidence | Gateway ingress was unchanged (`null`) and portfolio ingress was `external: false`. The documented path from a public static frontend to this backend was therefore unavailable for this deployment. |
| Pre-adapter compatibility | `f22e2ff:frontend/src/lib/api/portfolio.ts` | `BackendHolding.quantity` was typed as `number`, and valuation used direct numeric multiplication. A frontend artifact that could reach the string-producing backend would have had a contract mismatch. |
| Adapter arrival | `fd42df7a6dadb4c1e9317cd07736d144c607d6e2` / PR #178 | `parseWireQuantity` accepts `number | string`, preserves strings, and retains the numeric compatibility branch. |

## Draft disposition

The deployment interval is **contained at the documented public ingress boundary**: the only recorded deployment changed the internal `portfolio-service`; it skipped frontend deployment and retained unavailable gateway ingress. This supports no evidenced public frontend-to-backend route during the interval.

The audit does **not** prove that no user ever observed an incompatible artifact. The repository has no retained identity for the previously served static frontend bundle, cache inventory, cache lifetime, or frontend rollback artifact. Recorded ingress closure therefore makes user-visible impact **unproven**, not categorically impossible.

## Required independent B1 review

The reviewer must confirm that the runbook accurately bounds the production deployment, verify the historical frontend contract at both commits, and accept or reject this containment/impact disposition. Until then:

- Task 2.7 remains unchecked.
- Task 2.6's numeric compatibility remains in place.
- Wave 10.2 item 2 remains unsatisfied.
- No production exposure or Task 8.9 execution is authorized by this record.
