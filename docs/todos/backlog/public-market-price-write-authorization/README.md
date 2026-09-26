# Public market-price write lacks operator authorization

> **Owner approval still required:** deployment, live validation and any historical-data read
> are separate decisions. Code publication/merge completed through #327; those actions grant no
> operational approval. Documentation publication also requires explicit approval. Until deployment
> and serving validation, this item remains OPEN.

**Status:** OPEN — source-confirmed authorization defect; deployed exploitability not live-tested.
**Priority:** High, urgent security treatment before project freeze; ahead of the advisor IDOR.
**Origin:** 2026-09-26 UTC architecture review against `main@8aa4035b`.
**Implementation:** removal at `83607f5d852f1a1c4d6dc8e055780fa0b3b33976`, independently
reviewed ACCEPT WITH MINORS (0 Critical, 0 Important), merged through
[#327](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/327)
as `9c733f6df966c1acff80cdc4b9ddf35285512398`; not deployed/live-validated.
**Demo disposition:** no waiver; the previous `PASS_WITH_EXPECTED_DEFECTS` suite did not cover it.

## Source finding and shared-data impact

The following describes the audited `main@8aa4035b` baseline, not the merged fix. No fresh
serving-revision read or live exploit was performed during this documentation reconciliation.

[SecurityConfig](../../../../api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java)
requires authentication, not an operator role, for `/api/market/**`.
[SignupService](../../../../api-gateway/src/main/java/com/wealth/gateway/auth/SignupService.java)
offers public signup and issues a normal `ro=false` JWT.
[ReadOnlyEnforcementFilter](../../../../api-gateway/src/main/java/com/wealth/gateway/ReadOnlyEnforcementFilter.java)
blocks this mutation for `ro=true`, not for ordinary signed-in users. The route has the standard
rate limiter, which is not operator authorization.

[MarketPriceController](../../../../market-data-service/src/main/java/com/wealth/market/MarketPriceController.java)
accepts `POST /api/market/prices/{ticker}` without a role/key check. The service's
[InternalApiKeyFilter](../../../../market-data-service/src/main/java/com/wealth/market/seed/InternalApiKeyFilter.java)
guards `/api/internal/**` only and therefore does not cover this public path.
[MarketPriceService](../../../../market-data-service/src/main/java/com/wealth/market/MarketPriceService.java)
writes the supplied decimal to shared Mongo data with a new observation time, then submits a
Kafka event. The controller/service do not impose catalog or positive-price validation here.

A self-signed-up account can therefore alter shared stored prices, including supported tickers
held by the showcase. Mongo-backed pages can see the altered price immediately; successfully
delivered/accepted events can also affect every user's PostgreSQL valuations and Redis summaries.
This is not a user-owned holding edit. A 200 is not proof the asynchronous send reached Kafka,
but the Mongo write precedes it. The current frontend has no caller for this endpoint.

Azure [gateway configuration](../../../../infrastructure/terraform/azure/main.tf) supplies the
market-service URL, unlike the advisor's missing portfolio URL. Source wiring contains no similar
reachability barrier; no live mutation, revision read or exploit was performed. Do not call it
safe merely because deployed exploitability is untested. The 08:00 UTC refresh only rewrites
tickers for which provider data is returned and is not a reliable security recovery mechanism;
history/reference data may also need reviewed reconciliation after any actual tampering.

## Merged remediation and reviewed evidence — 2026-09-26 UTC

Claude removed the public POST and the controller's service dependency, without an alias or
replacement route. The service write API, scheduled refresh, local seeding and key-gated internal
seed are unchanged. The commit changes one production file and three test files (+416/−17).

`MarketPriceWriteRemovalIT` uses real Mongo/Kafka containers. It checks POST/PUT/PATCH/DELETE
against three public paths and three forwarded-identity fixtures, unchanged raw price documents,
and no Kafka event except a required observable control write. A handler-mapping assertion
rejects public market write methods and method-less aliases. These direct-service identities
are not authenticated signups; the gateway test separately characterizes authentication/forwarding.

Codex inspected source and the recorded JUnit XML: before-fix 2/2 failed, after-fix 2/2 passed,
and restored-endpoint 2/2 failed. Both failing runs record accepted writes, Mongo changes and extra
Kafka events. The alias-mutant text is a reconstructed evidence summary, not raw JUnit output.
Claude reports full market-data unit 120/120, integration 39/39 and gateway 3/3, none skipped;
the independent reviewer reports rerunning the key tests. Codex did not rerun those Java suites.
`MarketPriceWriteGatewayIntegrationTest` also adds one direct spoofed-header assertion; credit
that partial coverage in the [separate backlog item](../gateway-user-header-spoofing-regression-proof/README.md).

The local patch is acceptable on the inspected source/recorded evidence. This is not deployed
closure. Existing B1 candidate-envelope pins include the old controller blob; reattestation is
separate, not an automatic policy repin or B1 completion.

Codex verified #327 merged at 2026-09-26 17:07:50 UTC with exactly the four reviewed files and
a tree identical to `83607f5d`. Heavy PR CI jobs (unit/integration, Pact, Docker build and all
four Azure-image smoke cases) passed rather than taking the docs-only path. Post-merge CI was
still running at reconciliation. The latest GitHub Deploy record is at `db51cf5b`, not the fix;
this check excludes neither Azure changes outside GitHub nor untested deployed exploitability.

## Remaining delivery and closure evidence

Removal is the chosen local treatment; the earlier removal/key-gating options below are retained
as design context, not a request to add another route. Publication/merge are complete. Obtain
scoped market-data deployment approval, then separate live-validation approval and bind the
result to the serving candidate.

Codex accepted the bounded packet and probe after 19 offline tests and verification of 13
manifest entries; their hashes are recorded in the dashboard. The old/fixed 400/404 pair was
established offline with the exact non-numeric body. Use a valid ordinary writable account:
anonymous/showcase 401/403 can
occur on either version and do not distinguish the fix. Never use a numeric body, including a
nonexistent ticker. Rate limits, timeouts and 5xx are inconclusive, not removal evidence.

Removing the endpoint does not undo earlier writes. A separately approved read-only audit may
triage off-catalog IDs and unexpected observation/reference values, with explicit legitimate
seed/repair/catalog exceptions. It cannot prove past non-use or attribute an anomaly; normal
catalog tickers can be tampered with and later overwritten. Mongo alone also cannot establish
clean PostgreSQL history or Redis projections. No data read or repair is authorized here.

## Original treatment requirements

Prefer removing the unused public write, or move the required operator capability under a
key-gated internal path. The old public path must be removed/denied, not left as an alias.
Moving it under `/api/internal/**` inherits publicly routed, shared-key-only protection, not
private ingress or JWT protection; review that threat boundary and price/catalog validation.

Require local gateway/service regressions proving unauthenticated, normal signup and restricted
showcase callers cannot mutate prices through the public path. If an internal write is retained,
test missing/wrong keys and blank configured key fail closed, valid-key behavior uses isolated
fixtures, and normal price reads/refresh still work. Verify neither Mongo nor Kafka changes on
denial. Independently review the patch and obtain separate deployment/validation authority.
The local evidence above covers the selected removal path; deployment and live acceptance remain
outstanding. This documentation entry itself implements no application code or live tests.

See [market flow](../../../e2e-flows/market-data-service-e2e.md),
[risk register](../../../architecture/RiskMitigationPlan.md) and
[demo dashboard](../../../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md).
