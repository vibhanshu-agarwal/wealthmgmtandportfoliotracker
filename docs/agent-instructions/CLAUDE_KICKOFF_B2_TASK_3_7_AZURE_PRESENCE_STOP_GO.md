# B2 Task 3.7 — Azure presence completion record

- **Status:** complete / GO
- **Recorded:** 2026-09-11
- **Evidence operator:** Claude
- **Independent documentation review:** Codex
- **Scope:** Task 3.7 only; Task 8.9, feature flags, Wave 10, and Production E2E remain separate.

## Outcome

Task 3.7 is complete without an application change or deployment. The serving Azure gateway is
`api-gateway--0000081`; its immutable digest `sha256:090ad3ba4b7a…` maps to source `006aa9e6`,
which contains Wave 3 merge `cc97a209`. The configured presence TTL is 150 seconds with no
Container App override; observed key expiry of 178–180 seconds is consistent with its 30-second
cleanup slack.

The owner-controlled re-verification passed all four presence reads:

1. login demo A, then A reads `200` / `anotherSessionActive:false`;
2. login demo B, then B reads `200` / `true`;
3. recheck A, which reads `200` / `true`; and
4. login the non-demo account, then it reads `200` / `false`.

The two demo logins had distinct `jti` values. The non-demo no-write property is satisfied by the
reviewed source path and `nonDemoCallerReturnsFalseAndLeavesRedisUntouched` integration test.

## Corrected oracle

The superseded kickoff incorrectly took A's `false` baseline after two demo logins. A demo login
calls `DemoLoginResetOrchestrator.afterLogin`, which performs an authenticated loopback
`GET /api/portfolio` with the new token. That route is processed by `JwtAuthenticationFilter`, so
each demo login legitimately registers its own presence member before the client receives its login
response. Two demo logins therefore make A's first later presence read correctly return `true`.

The one-time, owner-authorized diagnostic observed one expected member after one demo login and
exactly A and B after two, with no unknown members. It established that the prior mismatch was an
invalid test order, not a presence defect, phantom Redis state, or a second writer.

## Evidence and boundaries

The sanitized run-2 runner hash is
`d0a1e3b1a5742063e512a46c843b5628c38b2b53a9d9da62bb677d0732e381bd`.
Credentials, tokens, `jti` values, Redis URLs, and member hashes were never recorded. The diagnostic
used only `ZCARD`, `ZRANGE ... WITHSCORES`, and `TTL`; it made no direct Redis write. No deployment,
feature-flag, scale, ingress, portfolio-write, reset, Task 8.9, KQL, or Actuator configuration action
occurred.

The earlier run-1 `INCONCLUSIVE_EXTERNAL_ACTIVITY` and run-2 mismatch artifacts remain historical
records; neither is overwritten. A future regression test that drives `POST /api/auth/login` through
the full gateway/Redis chain is a worthwhile coverage follow-up, but is not a Task 3.7 release gate.
