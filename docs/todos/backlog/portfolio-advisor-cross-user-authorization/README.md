# Portfolio advisor cross-user authorization (IDOR)

> **Approval boundary:** the code fix is published and merged through #329. Scoped deployment,
> live validation and cloud/configuration changes still need the relevant owner approval;
> technical acceptance and the completed merge do not grant that authority.
> Push/PR and merge of this documentation also require explicit approval. None is granted here.

**Status:** OPEN — route removal merged through #329; not deployed or live-validated.
**Priority:** High (security). **Origin:** 2026-09-26 UTC E2E guide review against `main@8aa4035b`.
**Implementation:** removal `435f61c6` plus `35779e2e` merged through
[#329](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/329) at
`6a82f3da32c8679c288d348f002fae7e4f378b6a` (2026-09-26 20:49:07 UTC).
**Demo disposition:** no non-blocking waiver; earlier demo findings and multi-user suites do not
close this item. Live Azure reachability of the old route remains unverified.

## Finding and impact

At the audited baseline (before removal), the gateway requires a valid login for
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

## Pre-fix environment qualification — source, not live evidence

The table describes the old handler before #329, not its presence in current main. Removing
the route in source does not establish that a deployed environment has received the fix.

| Environment | Source wiring and remaining uncertainty |
|---|---|
| Azure demo | [Terraform's insight-service environment](../../../../infrastructure/terraform/azure/main.tf) omits `PORTFOLIO_SERVICE_URL`. [Application configuration](../../../../insight-service/src/main/resources/application.yml) defaults to `http://localhost:8081`; absent another override, the fetch is expected to fail. No deployed environment/revision or exploit was checked. |
| Local Compose | [Compose](../../../../docker-compose.yml) supplies the portfolio-service URL. The pre-fix handler was reachable when that older stack and its dependencies ran; current main removes it. |
| Retained AWS | [Compute configuration](../../../../infrastructure/terraform/aws/modules/compute/main.tf) supplies the portfolio-service URL. This is retained source wiring, not proof that the parked stack is currently running/exploitable. |

Missing Azure wiring is an accidental reachability limitation, **not an authorization control**.
Resolve authorization before adding that URL or otherwise making the advisor reachable. The
previous `PASS_WITH_EXPECTED_DEFECTS` remains historical acceptance of its tested scope, not an
assessment or waiver of this newly recorded endpoint defect.

## Merged remediation and evidence limits

The chosen treatment removes the unused public endpoint, with no replacement or alias. The
controller no longer injects `InsightService`; its remaining health/summary routes and chat are
unchanged. The retained advisor service is not called by another production HTTP handler. Any
future exposure must derive the target from the trusted gateway-authenticated subject, not a
caller-selected ID. This is merged source remediation, not a claim about the deployed service.

`AdvisorAnalyzeRemovalIT` uses a recording fake portfolio-service and a positive control call.
Its original-route checks cover missing, ordinary and showcase identity fixtures; its structural
guard checks handler paths and direct advisor-service fields. Claude's saved XML shows RED before
removal (2 tests, 2 failures) and GREEN on the reviewed head (2 tests, no failures). The saved
mutant summary reports four re-exposure variants caught, including aliases. This protects against
accidental reintroduction, not arbitrary indirect access or a deliberate workaround, and is not
a gateway-authentication test.

The recorded full insight-service results are **1,848 unit tests total: 1,839 passed, 9 live/opt-in
skips**, and **39/39 integration tests passed**. Fable independently reproduced the key RED/GREEN
test and accepted with minors; the code minors were addressed in `35779e2e`. Codex checked the
final source diff, all eight original handoff checksums, saved XML and local report aggregates;
that source review did not rerun Java tests or make live/Azure reads. The old source finding was
published through #328. Codex subsequently verified #329's merged tree equals `35779e2e`, both
cited fix commits remain ancestors, and the PR's required checks and four image-smoke jobs passed.
Main's post-merge CI is a separate pre-deploy condition. No backlog totals or deployment
acceptance change merely because the code merged.

The deployment/probe instructions are accepted for preparation (reviewed packet `3508ff43…`,
header-only successor `0a8b52ce…`). All 23 current handoff manifest entries verify. The unchanged
probe `0f32ebd6…` and launcher `a721832b…` were reviewed; Codex reran 31 offline tests in that
packet review. The probe targets only the signed-in account's subject, distinguishes the default
unmapped-route 404 from the old business responses, and never retries the probe. Its summary
read is an endpoint/schema smoke check that accepts an empty map, not proof of populated data.

## Remaining delivery and closure steps

Publication and merge (Gates A/B) are complete. Before Gate C, require green main CI on the pinned
merge and refresh the GitHub deployment/input checks. The saved baseline is run 36092375156,
attempt 1, at `db51cf5b`, with insight revision `--0000081` and digest `dad55386…`; later saved
market-data deployment logs still show that identity. This is historical workflow evidence, not
a new Azure read or a guarantee against out-of-band changes.

Gate C authorizes only insight-service deployment; Gate D separately authorizes one live probe.
A mismatched before-snapshot blocks Gate D and closure; the workflow itself does not enforce that
historical comparison before updating the app. Bind the saved digest/revision evidence and both
probe-adjacent GitHub checks to the same run ID, attempt and head SHA. These checks do not exclude
direct Azure changes. Any failed or ambiguous probe/read is inconclusive: stop, no automatic
rerun or rollback. Closure requires the authorized deployment and the bound REMOVED result with
the limited summary smoke check passing; it does not certify chat/model reliability.
No live exploit, deployment or cleanup of the retained advisor implementation is authorized here.

See the [insight flow](../../../e2e-flows/insight-service-e2e.md#5-separate-portfolio-advisor-path-and-limits),
[portfolio trust boundary](../../../e2e-flows/portfolio-service-e2e.md#2-endpoints-and-trust-boundary)
and [demo dashboard](../../../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md).
