# Portfolio advisor cross-user authorization (IDOR)

> **Approval boundary:** owner-authorized Gates A–D are complete. Publication of this closure
> and any further deployment, live operation or retained-code cleanup need separate authority.
> Push/PR and merge of this documentation also require explicit approval. None is granted here.

**Status:** CLOSED — fixed by route removal, deployed and live-validated on 2026-09-27 UTC.
**Priority:** High (security). **Origin:** 2026-09-26 UTC E2E guide review against `main@8aa4035b`.
**Implementation:** removal `435f61c6` plus `35779e2e` merged through
[#329](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/329) at
`6a82f3da32c8679c288d348f002fae7e4f378b6a` (2026-09-26 20:49:07 UTC).
**Closure scope:** removal of the unused public advisor route, not a non-blocking waiver or
broader security certification. The old handler's Azure exploitability remains unverified.

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
caller-selected ID. Deployment identity and the separately authorized Gate D result are below.

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
Main's four post-merge CI workflows subsequently passed at the pinned merge. Code merge and
deployment artifacts alone do not establish the live endpoint's behavior or close this item.

The deployment/probe instructions are accepted for preparation (reviewed packet `3508ff43…`,
with later header-only status revisions). All 25 handoff manifest entries verify at this review.
The unchanged probe `0f32ebd6…` and launcher `a721832b…` were reviewed; Codex reran 31 offline tests in that
packet review. The probe targets only the signed-in account's subject, distinguishes the default
unmapped-route 404 from the old business responses, and never retries the probe. Its summary
read is an endpoint/schema smoke check that accepts an empty map, not proof of populated data.

## Completed deployment and live closure

Publication, merge and scoped deployment (Gates A–C) are complete. The saved baseline is run 36092375156,
attempt 1, at `db51cf5b`, with insight revision `--0000081` and digest `dad55386…`; later saved
market-data deployment logs still show that identity. This is historical workflow evidence, not
a new Azure read or a guarantee against out-of-band changes.

Owner-authorized scoped deploy [36285996570](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/36285996570),
attempt 1, succeeded at `6a82f3da` on 2026-09-27 UTC. Saved workflow logs bind insight-service
revision `--0000082` to `sha256:aa1e9e3c2a7eaff8e208bf3164d0e38c061aa950fd4b9c350693f65fe7eec0be`
with 100% traffic. The before-snapshot matched the recorded insight baseline; the non-interference
check passed with `errors: []`, and other apps/the market refresh Job were unchanged within the
compared fields. Frontend, seed and verify were skipped as intended. Codex checked the saved logs,
their hash and GitHub run/attempt/commit; it made no new Azure read or live request. Matching
snapshots do not rule out intervening or subsequent out-of-band configuration changes.

The owner ran Gate D once at approximately 02:05 UTC on 2026-09-27. Claude transcribed the terminal
output: `REMOVED`, exit 0; two health GETs (no response, then 200), one login (200), one own-ID
analyze GET (404 classified as Spring's default error body for the exact path), and one summary
GET (200, 158 well-formed ticker summaries). The request budget was met, credentials were dropped,
and no rerun is recorded. The probe's body checks are hash-pinned; raw live bodies are not preserved
in this transcript, and Codex did not observe or repeat the live requests.

Saved binding checks at **02:03:23 UTC** and **02:09:47 UTC** agree: deploy run `36285996570`,
attempt 1, head `6a82f3da`, completed/successful and newest, with no newer run or attempt.
Codex rehashed the evidence and checked its consistency with the accepted probe and deploy record.
The closure condition is met. This is a single own-ID runtime sample, not a live cross-user exploit
test or an enumeration of all IDs; the route-wide conclusion also rests on the reviewed removed
mapping and regression test. The 158 summaries establish this response was populated and schema-valid,
not that every catalog ticker or quote was present, fresh or accurate. No chat/model proof follows.

**Evidence hashes (SHA-256):** terminal/binding transcript
`1e7a8c74c515ed2166c54277028b1b0b6f1f46b562aae0310e466a534623a296`;
deploy-log copy `cf978f907664b9cd5fa4e9a98fc4605723a89cdfb8fd5bd1913223367ff4ed43`;
25-entry manifest `c0219e6d1d3b6a03220b750b146156801ba08bba1315cd0d2d317a46788f4d7c`.
These bind privately retained evidence, not files published by this documentation update.

**Still open:** GitHub binding cannot exclude out-of-band Azure changes. The broader
[header-proof gap](../gateway-user-header-spoofing-regression-proof/README.md) remains OPEN.
Removing the retained advisor service/adapters/tests is separate **OPEN cleanup**, not required
for this route-removal closure and not implemented or authorized here. Historical exploitability,
other endpoints, peer-service trust and chat/model reliability are not certified. No further live
operation, rerun, deployment or cleanup is authorized by this record.

See the [insight flow](../../../e2e-flows/insight-service-e2e.md#5-separate-portfolio-advisor-path-and-limits),
[portfolio trust boundary](../../../e2e-flows/portfolio-service-e2e.md#2-endpoints-and-trust-boundary)
and [demo dashboard](../../../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md).
