# Gateway user-header spoofing regression proof

> **Approval boundary:** this records a test-evidence gap, not a new live spoofing test or code
> change. Implementation, live access and documentation publication require their relevant approval.

**Status:** OPEN — one protected-route spoof assertion delivered locally; broader regression
coverage remains incomplete. Not a confirmed current bypass.
**Priority:** Medium (security regression coverage).
**Origin:** 2026-09-26 UTC architecture review against `main@8aa4035b`.
**Implementation:** partial local coverage at `83607f5d`, not merged/deployed; the broader
work below remains open. Existing source and accepted demo evidence are not invalidated by this
gap; neither is complete header-sanitization regression coverage established.

## Exact gap and existing coverage

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

**Local price-fix follow-up:** `MarketPriceWriteGatewayIntegrationTest` at `83607f5d` sends one
conflicting caller header on the protected market POST, captures the upstream request under the
production route list, and requires exactly the JWT subject. Anonymous/showcase cases must not
reach the stub. This is a direct single-route proof, unlike the older named status-only cases.
It does not cover duplicate caller headers, permit-all routed paths or general filter mutation
proof; it therefore does not close this item. Codex inspected the test, not a new live request.

## Future acceptance requirements

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
