# B2 Task 3.7 — Azure presence STOP/GO evidence

**Status:** GO / complete on 2026-09-11.

## Provenance and configuration

- Serving revision: `api-gateway--0000081`.
- Serving digest: `sha256:090ad3ba4b7a…`.
- Source mapping: image tag source `006aa9e6`, with Wave 3 merge `cc97a209` as an ancestor.
- Resolved presence TTL: 150 seconds; no Container App override.
- Observed Redis-key expiry: 178–180 seconds, consistent with TTL plus 30-second cleanup slack.

## Controlled live proof

Under owner-confirmed traffic isolation and after natural key expiry, the sanitized re-verification
performed three logins and four presence reads with no retries:

| Step | Expected | Observed |
|---|---|---|
| Demo A login, then A presence read | `200` / `false` | `200` / `false` |
| Demo B login, then B presence read | `200` / `true` | `200` / `true` |
| Demo A recheck | `200` / `true` | `200` / `true` |
| Non-demo login, then non-demo presence read | `200` / `false` | `200` / `false` |

Both demo tokens had distinct `jti` claims; both subjects were the demo account. The non-demo token
had a different subject. The no-write property for non-demo callers is discharged by source review
and the deterministic `nonDemoCallerReturnsFalseAndLeavesRedisUntouched` integration test.

## Root-cause reconciliation

The formerly failing order performed two demo logins before A's first read. That was an invalid
oracle: `AuthController.login()` invokes `DemoLoginResetOrchestrator.afterLogin()` for the demo
account; its authenticated loopback `GET /api/portfolio` passes through `JwtAuthenticationFilter`
and registers the login's presence member. Hence two demo logins legitimately yield two members and
an A read of `true`.

The owner-authorized diagnostic verified one member (A only) and `false` after one login, then two
known members (A and B) after two logins, with no unknown members. The presence implementation is
therefore correct; no code change or deployment is required for Task 3.7.

## Boundary accounting

- No operator-issued portfolio write or reset; no Task 8.9 action.
- No deployment, flag, scale, ingress, KQL, or Actuator change.
- Redis diagnostic commands were read-only: `ZCARD`, `ZRANGE ... WITHSCORES`, and `TTL`.
- No credential, token, `jti`, Redis URL, or member hash was recorded.
- Sanitized run-2 runner SHA-256: `d0a1e3b1a5742063e512a46c843b5628c38b2b53a9d9da62bb677d0732e381bd`.

The integration test suite did not exercise login through the full gateway/Redis chain. Adding that
regression coverage is a non-blocking follow-up; it does not reopen Task 3.7.
