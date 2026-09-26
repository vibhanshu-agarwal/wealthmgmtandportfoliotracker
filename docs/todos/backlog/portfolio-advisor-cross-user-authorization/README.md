# Portfolio advisor cross-user authorization (IDOR)

> **Approval boundary:** this entry records the source finding and a reviewed local removal.
> Publication, merge, live security tests, deployment and cloud/configuration changes still need
> the relevant owner approval; technical acceptance does not grant that authority.
> Push/PR and merge of this documentation also require explicit approval. None is granted here.

**Status:** OPEN — route removal implemented and reviewed locally; not merged, deployed or live-validated.
**Priority:** High (security). **Origin:** 2026-09-26 UTC E2E guide review against `main@8aa4035b`.
**Implementation:** removal selected; local commits `435f61c6` and `35779e2e`, based on `598bdf17`.
**Demo disposition:** no non-blocking waiver; earlier demo findings and multi-user suites do not
close this item. Live Azure reachability of the old route remains unverified.

## Finding and impact

At the audited baseline (before the local removal), the gateway requires a valid login for
`GET /api/insights/{userId}/analyze`, including the restricted
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

## Reviewed local remediation and evidence limits

The chosen treatment removes the unused public endpoint, with no replacement or alias. The
controller no longer injects `InsightService`; its remaining health/summary routes and chat are
unchanged. The retained advisor service is not called by another production HTTP handler. Any
future exposure must derive the target from the trusted gateway-authenticated subject, not a
caller-selected ID. This is local source remediation, not a claim about the deployed service.

`AdvisorAnalyzeRemovalIT` uses a recording fake portfolio-service and a positive control call.
Its original-route checks cover missing, ordinary and showcase identity fixtures; its structural
guard checks handler paths and direct advisor-service fields. Claude's saved XML shows RED before
removal (2 tests, 2 failures) and GREEN on the final local head (2 tests, no failures). The saved
mutant summary reports four re-exposure variants caught, including aliases. This protects against
accidental reintroduction, not arbitrary indirect access or a deliberate workaround, and is not
a gateway-authentication test.

The recorded full insight-service results are **1,848 unit tests total: 1,839 passed, 9 live/opt-in
skips**, and **39/39 integration tests passed**. Fable independently reproduced the key RED/GREEN
test and accepted with minors; the code minors were addressed in `35779e2e`. Codex checked the
final source diff, all eight handoff checksums, saved XML and local report aggregates; it did not
rerun Java tests or make live/cloud reads. The old source finding was published through #328;
this local follow-up does not change the backlog totals or close deployment acceptance.

## Remaining delivery and closure steps

Obtain owner authorization for push/PR and merge, then a separately authorized scoped
insight-service deployment and bounded live validation. Prepare/review that operational packet
before use. An arbitrary 404 alone is insufficient: the old handler can also report a missing
portfolio, so validation must distinguish an unmapped route from that business response and bind
the result to the deployed artifact. Confirm supported summary/chat paths remain usable.
No live exploit, deployment or cleanup of the retained advisor implementation is authorized here.

See the [insight flow](../../../e2e-flows/insight-service-e2e.md#5-separate-portfolio-advisor-path-and-limits),
[portfolio trust boundary](../../../e2e-flows/portfolio-service-e2e.md#2-endpoints-and-trust-boundary)
and [demo dashboard](../../../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md).
