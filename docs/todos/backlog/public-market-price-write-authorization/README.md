# Public market-price write lacks operator authorization

> **Owner decision required:** approve a bounded code fix and, separately, its deployment/live
> validation before either starts. This entry authorizes documentation only; withholding approval
> leaves the defect OPEN. Documentation push/PR and merge also require explicit approval.

**Status:** OPEN — source-confirmed authorization defect; deployed exploitability not live-tested.
**Priority:** High, urgent security treatment before project freeze; ahead of the advisor IDOR.
**Origin:** 2026-09-26 UTC architecture review against `main@8aa4035b`.
**Implementation:** not started. **Demo disposition:** owner decision pending, not waived by the
previous `PASS_WITH_EXPECTED_DEFECTS` suite or its accepted limitations.

## Source finding and shared-data impact

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

## Bounded future treatment and closure evidence

Prefer removing the unused public write, or move the required operator capability under a
key-gated internal path. The old public path must be removed/denied, not left as an alias.
Moving it under `/api/internal/**` inherits publicly routed, shared-key-only protection, not
private ingress or JWT protection; review that threat boundary and price/catalog validation.

Require local gateway/service regressions proving unauthenticated, normal signup and restricted
showcase callers cannot mutate prices through the public path. If an internal write is retained,
test missing/wrong keys and blank configured key fail closed, valid-key behavior uses isolated
fixtures, and normal price reads/refresh still work. Verify neither Mongo nor Kafka changes on
denial. Independently review the patch and obtain separate deployment/validation authority.
These are proposed acceptance requirements, not code/tests delivered by this documentation entry.

See [market flow](../../../e2e-flows/market-data-service-e2e.md),
[risk register](../../../architecture/RiskMitigationPlan.md) and
[demo dashboard](../../../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md).
