# B2 Task 2.7 — Historical Backend-Before-Adapter Containment Audit

**Status:** **ACCEPT — Task 2.7 historical audit complete**, independently reviewed by Astra on
2026-09-10. This is a repository-evidence disposition, not production-serving proof or
authorization.

## Scope and non-authorizations

This repository-evidence reconstruction covers the interval from the B1 decimal-string
read-side deployment through the B2 tolerant adapter's **source merge**. `fd42df7` is
not asserted to be a production-serving date. It used no cloud access and makes no
deployment, cache-purge, rollback, feature-enablement, workflow, or production-impact
claim.

## Evidence record

| Fact | Repository evidence | Result |
|---|---|---|
| String-producing backend source | `f22e2ffee78262f5526aec0bc8b4324076f30de7`; `PortfolioResponse.HoldingResponse.quantity` uses `ToPlainStringSerializer` | The candidate source serializes quantity as a JSON string. The retained G2a serving capture does not contain a nonempty holding, so it is not a direct wire capture of that field. |
| R-B2 deployment identity | [`B1_R_B2_G2A_SERVING_PROOF.md`](../../runbooks/B1_R_B2_G2A_SERVING_PROOF.md), Artifact 2a and Serving evidence; deploy run `32982880866` | The recorded R-B2 digest deployment placed the `f22e2ff`-labelled candidate at `portfolio-service--0000081` / `sha256:d544649f5b67baec8b563016882d239d3ecb9c5672399586e0bc656c78961d4f`, 100% of the sole active revision. |
| Other known portfolio revisions in the interval | [`SPEC_A_9_12_POOLED_READONLY_RCA.md`](../../runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md), Production timeline lines 18-27 and provenance-cycle lines 29-61 | `0000084` was a diagnostic-artifact deploy; `0000085` and `0000086` were diagnostics configuration revisions. The record associates that cycle with source `main@cb5af200` and digest `sha256:6026e906…`, but does not retain a separate source/image read-back for each revision. `0000087` was a flags-false baseline; `0000088` and `0000089` were provenance diagnostics configuration revisions. Their cycle has source baseline `0887a309…` and digest `sha256:d5693e29…`; the record directly observes that digest on `0000088` and after-checks it on `0000089`. |
| Frontend deployment effect of R-B2 only | [`B1_R_B2_G2A_SERVING_PROOF.md`](../../runbooks/B1_R_B2_G2A_SERVING_PROOF.md), Deploy table | `deploy-frontend` was skipped in run `32982880866`; that specific run introduced no new frontend artifact. This does not exclude a separate frontend deployment or rollback elsewhere in the interval. |
| Documented ACA reachability | [`B1_R_B2_G2A_SERVING_PROOF.md`](../../runbooks/B1_R_B2_G2A_SERVING_PROOF.md), Preconditions and Serving evidence | Gateway baseline ingress was `null`, portfolio ingress was `external: false`, and probes used in-revision loopback. This bounds the documented ACA path for the R-B2 deployment; it does not establish every frontend, proxy, cache, or rollback path. |
| Pre-adapter compatibility | `f22e2ff:frontend/src/lib/api/portfolio.ts` | `BackendHolding.quantity` was typed as `number`, and valuation used direct numeric multiplication. A frontend artifact that could reach the string-producing backend would have had a contract mismatch. |
| Adapter source-merge endpoint | `fd42df7a6dadb4c1e9317cd07736d144c607d6e2` / PR #178 | `parseWireQuantity` accepts `number | string`, preserves strings, and retains the numeric compatibility branch. This is the interval's source-merge endpoint, not verified evidence of a production-serving frontend artifact. |

## Frontend delivery-path evidence and limits

| Area | Known repository evidence | What remains unknown | Consequence |
|---|---|---|---|
| Static frontend topology and routing | `cloudfront-distributions.txt` at `f22e2ff` records an enabled static S3 CloudFront distribution and an `/api/*` behavior to an AWS Lambda origin; `.github/workflows/deploy-aws.yml` defines static-export upload and CloudFront invalidation mechanics. | No contemporaneous proof binds that configuration snapshot, its embedded API origin, or its route behavior to a user-served bundle during this interval. | The ACA ingress record cannot by itself close alternative documented or unrecorded frontend/proxy routes. |
| Frontend artifact | R-B2 run `32982880866` skipped `deploy-frontend`. | No retained immutable identity, build inputs, publication record, or serving read-back for the previously served static bundle. | The R-B2 run proves only that it did not introduce a frontend artifact. |
| Cache | CloudFront cache-policy identifiers and invalidation mechanisms are tracked in repository configuration/workflows. | No contemporaneous effective-policy/TTL record, cache-object inventory, invalidation completion, or browser/CDN observation is retained. | Cache persistence cannot be ruled out from repository evidence. |
| Rollback | The R-B2 runbook records a backend abort digest. | No identity or serving evidence is retained for a frontend rollback artifact or rollback execution. | Backend rollback evidence cannot establish frontend rollback state. |

## Accepted disposition

The R-B2 deployment is **contained only at its documented ACA path**: it changed
`portfolio-service`, skipped frontend deployment in that run, and retained unavailable
gateway/portfolio ingress. Later known portfolio revisions `0000084`–`0000089` occurred
while the RCA records gateway ingress closed, but their presence prevents a claim that
`0000081` was the interval's only deployment. The retained repository evidence supports
no verified public static-frontend-to-ACA route; it does not close every frontend, proxy,
cache, or rollback path.

The audit does **not** prove that no user ever observed an incompatible artifact. Although
the repository retains static-topology and cache-invalidation mechanisms, it lacks a
contemporaneous served-bundle identity, embedded origin, effective cache inventory/TTL and
invalidation completion, and frontend rollback identities. Recorded ACA ingress closure
therefore makes user-visible impact **unproven, not impossible**.

## Independent review and continuing gates

**Astra disposition (2026-09-10): ACCEPT.** Repository evidence reconstructs the recorded
backend deployments and documented frontend delivery paths, with containment only at the
recorded ACA boundary. User-visible impact remains **unproven, not impossible**. The
contemporaneous frontend artifact, embedded-origin, cache, and rollback identities remain
unresolved. `fd42df7` via PR #178 (`main@38e3d954`) is source provenance, not serving proof.

Task 2.6's numeric compatibility remains mandatory, and Wave 10.2 item 2 remains
unsatisfied. This acceptance does not authorize Task 8.9, production exposure, deployment,
feature enablement, cache purge, rollback, or any cloud/workflow operation.
