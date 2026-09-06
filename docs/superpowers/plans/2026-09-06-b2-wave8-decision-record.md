# B2 Wave 8 product and operational decision record

**Decision date:** 2026-09-06  
**Owner approval:** recorded in the Wave 8 kickoff session  
**Applies to:** login-orchestrated demo reset and the Wave 10 exposure gate

## Approved decisions

1. **Idle threshold: 30 minutes.** Eligibility is computed from the persisted
   `PortfolioResponse.updatedAt` value for the single portfolio whose `userId` is the compiled-in
   demo UUID. The comparison is exact: `age > 30m` is eligible; below, equal, or future timestamps
   are ineligible. Viewing, an unsaved draft, and the 150-second presence TTL do not refresh or
   defer this persisted-write signal. A Wave 10-qualifying production proof waits until the real
   threshold is strictly exceeded; a threshold override is diagnostic only.
2. **Timeouts: eligibility 2 seconds, reset 2 seconds, overall 4 seconds.** The owner accepts up to
   approximately four seconds of additional latency after successful demo authentication and
   accepts that cold or slow backends can cause a fail-open skip. There is no inspected warm/cold
   p95 or p99 supporting a tighter promise; Azure permits scale-to-zero and prior serving evidence
   includes an initial `504` followed by a successful read.
3. **Manual reset placement: page level for this release.** Keep the existing
   `PortfolioPageContent` host. A `409` is shown as a draft-free notice and requires explicit fresh
   observation before another attempt. Moving the control into the picker would be a separate
   change requiring frozen-draft conflict behavior, accessibility/focus work, quantity-fidelity
   recovery, success disposition, and updated E2E locators.
4. **Decimal sequencing: compatibility retained; historical exception assigned to B1 audit.**
   Task 2.6's numeric compatibility and fidelity gate remain. String-producing backend source
   `f22e2ff` served on 2026-08-26 as revision `0000081`, digest
   `sha256:d544649f5b67baec8b563016882d239d3ecb9c5672399586e0bc656c78961d4f`, while the
   frontend job was skipped. Adapter source `fd42df7a` / PR #178 followed on 2026-08-29. Recorded
   ingress closure makes user-visible impact unproven, not disproved. B1 owns reconstruction of
   the historical frontend artifact, routing, cache, rollback, and containment evidence. Task 2.7
   and Wave 10.2 item 2 remain open until reviewed disposition.

## Frozen implementation contract

### Configuration

Use one validated `DemoLoginResetProperties` binding with no duplicate constructor fallbacks:

| Property | Environment override | Value |
|---|---|---|
| `app.demo-login-reset.idle-threshold` | `APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD` | `30m` |
| `app.demo-login-reset.eligibility-timeout` | `APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT` | `2s` |
| `app.demo-login-reset.reset-timeout` | `APP_DEMO_LOGIN_RESET_RESET_TIMEOUT` | `2s` |
| `app.demo-login-reset.overall-timeout` | `APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT` | `4s` |

All durations are positive. Invalid binding fails startup rather than silently substituting an
unapproved value.

### Entry point and calls

The entry point is:

```java
Mono<Void> afterLogin(com.wealth.gateway.auth.LoginResponse response);
```

It is invoked only after successful authentication and JWT minting and returns the original login
response unchanged. Publisher construction and subscription failures are inside the fail-open
boundary. Ordinary users and failed authentication issue zero self-calls. Every successful demo
login that reaches eligibility dispatch performs exactly one `GET /api/portfolio`; a clean
ineligible result performs zero reset calls and emits no failure event. An eligible result performs
at most one reset `POST` with the exact observed integral version. There is no reread, retry,
detached subscription, blocking client, or first-element identity selection.

Both calls use the injected observation-enabled `WebClient.Builder` and the real gateway route.
`GatewayLoopbackTargetProvider` resolves `local.server.port` at subscription time and constructs a
loopback URI. Target construction is a deferred, nonblocking, cancellable publisher so cancellation
before dispatch cannot produce a late request. Eligibility carries the fresh bearer token and the
conditional origin header observed at finalized `ClientRequest`; reset carries the shared internal
key or does not dispatch when it is blank.

### Time and cancellation

Use an injected UTC `Clock` for idle age and a separate monotonic `LongSupplier`/nano-clock,
defaulting to `System.nanoTime`, for elapsed diagnostics. The overall deadline begins before
eligibility target construction. Its five observable phases are:

- `eligibility_pre_dispatch`
- `eligibility_in_flight`
- `between_legs`
- `reset_in_flight`
- `reset_post_response`

Phase and dispatch facts must agree. `attemptedTarget` is null when no call is in flight.
The exact dispatch pairs are: `eligibility_pre_dispatch=false/false`;
`eligibility_in_flight=true/false`; `between_legs=true/false`;
`reset_in_flight=true/true`; and `reset_post_response=true/true`. A post-response overall timeout
preserves the reset response's received HTTP status even though `attemptedTarget` is null.
Cancellation before reset dispatch prevents it permanently, including after a delayed construction
publisher is released. Cancellation after dispatch is not transaction rollback: a downstream
commit may coexist with either `reset_timeout` or `overall_timeout`.

### Diagnostics and verification

`demo_reset_self_call_skipped` is emitted only for non-clean outcomes, never ordinary ineligibility.
It retains Task 8.7's exact reason vocabulary and both legs' configured/required, dispatch, and
tri-state attachment facts; actual status, target, monotonic elapsed time, timeout scope, phase,
replica token, sanitized exception category, and reset-`409` version quartet are populated according
to the branch. The inbound trace is preserved. Credentials, raw replica names, exception messages,
stack traces, and response bodies are never logged.

Verification has three levels: unit tests; a real RANDOM_PORT gateway whose self-calls traverse its
actual routes to an outer HTTP stub; and a dedicated `wave8IntegrationTest` source set using real
portfolio persistence. The real-chain suite covers committed reset plus reset-leg timeout,
committed reset plus `reset_in_flight` overall timeout, committed reset plus
`reset_post_response` overall timeout with preserved status, and post-success gateway-handler
failure. Root `integrationTest` must collect that source set.

## Authorization boundary

This record authorizes offline source and test work only. Push, pull-request creation, workflow
execution, deployment, cloud/registry/secret access, production probing, Task 8.8 deployment, Task
8.9 live proof, and Wave 10 exposure remain separately owner-gated. A gateway artifact containing
Wave 5 behavior also requires Task 5.6 GO before deployment.
