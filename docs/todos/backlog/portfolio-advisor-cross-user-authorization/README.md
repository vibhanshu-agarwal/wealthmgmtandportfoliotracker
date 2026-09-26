# Portfolio advisor cross-user authorization (IDOR)

> **Approval boundary:** this entry records a source finding only. Application changes, live
> security tests, deployment and any cloud/configuration change need the relevant owner approval.
> Push/PR and merge of this documentation also require explicit approval. None is granted here.

**Status:** OPEN — source-confirmed authorization defect; live Azure reachability unverified.
**Priority:** High (security). **Origin:** 2026-09-26 UTC E2E guide review against `main@8aa4035b`.
**Implementation:** not started. **Demo disposition:** owner decision pending; not accepted as
non-blocking by the earlier demo findings or closed by their multi-user suite.

## Finding and impact

The gateway requires a valid login for `GET /api/insights/{userId}/analyze`, including the restricted
showcase login. However, [InsightController](../../../../insight-service/src/main/java/com/wealth/insight/InsightController.java)
uses the caller-selected path ID and [InsightService](../../../../insight-service/src/main/java/com/wealth/insight/InsightService.java)
forwards it as `X-User-Id` to `GET /api/portfolio`. Neither compares it with the authenticated
subject injected by the gateway. Portfolio-service trusts that service header.

When portfolio/advisor dependencies work, a logged-in caller who knows a victim's ID can request
their derived risk score, concentration warnings and rebalancing suggestions. Success/not-found
outcomes can also disclose portfolio existence. The result is derived analysis, not a direct raw
holdings response. Random signup UUIDs do not enforce ownership; a showcase ID is already in source.
The current frontend does not call this advisor endpoint. Accepted isolation tests on the ordinary
portfolio/session paths do not cover this exception.

## Environment qualification — source, not live evidence

| Environment | Source wiring and remaining uncertainty |
|---|---|
| Azure demo | [Terraform's insight-service environment](../../../../infrastructure/terraform/azure/main.tf) omits `PORTFOLIO_SERVICE_URL`. [Application configuration](../../../../insight-service/src/main/resources/application.yml) defaults to `http://localhost:8081`; absent another override, the fetch is expected to fail. No deployed environment/revision or exploit was checked. |
| Local Compose | [Compose](../../../../docker-compose.yml) supplies the portfolio-service URL. The source authorization flaw is reachable when the stack and dependencies run; no fresh exploit test was performed. |
| Retained AWS | [Compute configuration](../../../../infrastructure/terraform/aws/modules/compute/main.tf) supplies the portfolio-service URL. This is retained source wiring, not proof that the parked stack is currently running/exploitable. |

Missing Azure wiring is an accidental reachability limitation, **not an authorization control**.
Resolve authorization before adding that URL or otherwise making the advisor reachable. The
previous `PASS_WITH_EXPECTED_DEFECTS` remains historical acceptance of its tested scope, not an
assessment or waiver of this newly recorded endpoint defect.

## Bounded future fix and acceptance proposal

Choose and independently review one of:

- Remove the unused public advisor endpoint; or
- Bind the target to the gateway-authenticated subject, deriving the target from that trusted
  identity (or rejecting a mismatched path ID). Do not trust a browser-supplied replacement header
  or treat downstream internal ingress as a substitute for subject binding.

Before closure, test locally that user A cannot obtain user B's analysis or existence through this
route; missing identity fails closed; the restricted showcase has no cross-user exception; and
any retained caller-owned path still works. Verify peer-service/header trust for the chosen design.
If removing the route, assert its absence and check that no supported caller depends on it.
Add regressions, independent review and separately authorized deployment/validation evidence for
any live exposure claim. These are future requirements, **not tests performed by this audit**.

See the [insight flow](../../../e2e-flows/insight-service-e2e.md#5-separate-portfolio-advisor-path-and-limits),
[portfolio trust boundary](../../../e2e-flows/portfolio-service-e2e.md#2-endpoints-and-trust-boundary)
and [demo dashboard](../../../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md).
