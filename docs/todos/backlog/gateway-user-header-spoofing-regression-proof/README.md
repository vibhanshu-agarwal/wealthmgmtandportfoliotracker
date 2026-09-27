# Gateway user-header spoofing regression proof

> **Approval boundary:** the owner approved local implementation and offline verification.
> Code PR #331 is open; its merge and push/PR and merge of this status update need explicit approval. No live
> spoofing test, deployment, cloud access or Gate E audit is authorized by this record.

**Status:** OPEN — broader regression proof accepted at `c647b6f0` and published for review
as [#331](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/331); merge pending. Not a confirmed current bypass.
**Priority:** Medium (security regression coverage).
**Origin:** 2026-09-26 UTC architecture review against `main@8aa4035b`.
**Implementation:** partial coverage at `83607f5d`, merged through #327 (`9c733f6d`); the broader
test-only candidate is `c647b6f0` on `main@e03d6cc4`. No production code changes. This is local
acceptance, not delivery on main or exhaustive security certification.

## Accepted local proof — 2026-09-27 UTC

`GatewayUserIdHeaderIntegrationTest` has 88 cases using the production route list and actual
downstream captures: 15 protected-forwarding cases, 36 routed permit-all cases, 35 rejected-auth
cases, and two controller/actuator routing characterizations. Conflicting, duplicate, mixed-case
and comma-joined caller headers are covered. Protected routes forward exactly the verified
subject; service-health/internal routes forward no identity, with or without a valid token.
Rejected protected requests reach no downstream service.

There is no production gateway route for `/api/auth/**`: AuthController handles it directly.
Actuator is also not routed through GlobalFilters. The test pins those distinctions rather than
claiming routed auth/actuator sanitization. Protected-route sampling uses GET; the earlier
price-write test covers POST. CloudFront origin checks are disabled in this local test fixture.

Fable accepted with minors; Claude applied the scoped fixes. Codex reviewed the final source,
test-file hash and mutation evidence, then independently checked the fresh full-suite capture
against `c647b6f0605ce9c8286b4627a599194bbe46ee31` / tree
`3f83be5080e11603c0eeaac0bb2167c1e229d765`. The 04:57:43–05:02:28 UTC capture reports exit 0:
328/328 unit, 284/284 integration (including the 88 new cases), and 4/4 Wave 8 tests, no skips.
All three test tasks executed; the 17 up-to-date tasks were other build steps. All 74 XML hashes
verify and suite timestamps fall inside the run window. The `slim-image` gate is outside these
tasks and was not run. Codex inspected saved evidence, not a new Java execution or live request.

Mutation results: permit-all strip removal fails 24 cases; permit-all branch removal fails 36;
protected strip removal plus `set`→`add` fails 10; missing-identity fail-open fails 10; combined
filter/security fail-open fails 15. Removing protected stripping alone, changing `set` alone,
or weakening SecurityConfig alone survives because the remaining protection still enforces the
contract. The superseded harness output is not used as the acceptance basis.

Privately retained capture hashes (SHA-256): metadata
`5b4e648256a93fe2f672eb4978f4392583fa87b548629dc062af9510d9532213`;
console `4ad4d668fa907f83bfd48d0b4a797971c9c93e13369e7ed119ed51bb535c1d53`;
74-entry XML hash list `04f69e9b88f556897f676ef63804c400d424e16e42ef4e4d769bdffb0b662ef8`.
Publication must refresh the merge/status basis; this item stays OPEN until accepted delivery
is recorded. The remaining sections preserve the original gap and its closure contract.

## Original gap and coverage on main through #330

[JwtAuthenticationFilter](../../../../api-gateway/src/main/java/com/wealth/gateway/JwtAuthenticationFilter.java)
removes caller `X-User-Id` headers on protected and permit-all routed paths, and sets the verified
JWT subject on protected paths. This behavior is present in source.

The "Spoofing Prevention" cases in
[JwtFilterIntegrationTest](../../../../api-gateway/src/test/java/com/wealth/gateway/JwtFilterIntegrationTest.java)
send a spoofed header, but route to an absent upstream and only assert a non-401 status. That
does not inspect the forwarded identity or prove the spoofed value was removed. Similar
[preservation cases](../../../../api-gateway/src/test/java/com/wealth/gateway/CorsAndAuthPreservationPropertyTest.java)
also assert status rather than downstream headers.

There **is** an injection assertion in
[JwtAuthenticationFilterChainTest](../../../../api-gateway/src/test/java/com/wealth/gateway/JwtAuthenticationFilterChainTest.java):
it captures the forwarded user ID and checks the subject, but supplies no spoofed input. Do not
rewrite this gap as "no user-ID injection test" or as a live identity-spoofing exploit.
Reset-specific header tests are separate from this general filter's routed-path contract.

**Merged price-fix follow-up:** `MarketPriceWriteGatewayIntegrationTest` at `83607f5d` sends one
conflicting caller header on the protected market POST, captures the upstream request under the
production route list, and requires exactly the JWT subject. Anonymous/showcase cases must not
reach the stub. This is a direct single-route proof, unlike the older named status-only cases.
It does not cover duplicate caller headers, permit-all routed paths or general filter mutation
proof; it therefore does not close this item. Codex inspected the test, not a new live request.

## Closure contract

Use a local capturing chain/upstream to supply a conflicting caller header (including duplicate
values), then assert the complete forwarded header collection contains exactly the verified
subject on protected requests. On routed permit-all internal/health paths, assert it is absent
and no subject is injected. Check missing/invalid JWT protected requests fail closed without a
downstream call. Keep controller-only requests distinct from Gateway GlobalFilter routes.

Prove the new tests fail under the relevant removal/override regressions rather than merely
changing response status; account for `headers.set` already replacing values on protected paths.
Run the selected local tests and obtain independent review before closing this item. The partial
price-fix test was implemented separately; this documentation audit ran no Java or live tests.

See [test inventory](../../../architecture/IntegrationTestCases.md),
[gateway flow](../../../e2e-flows/api-gateway-service-e2e.md) and the
[backlog index](../README.md).
