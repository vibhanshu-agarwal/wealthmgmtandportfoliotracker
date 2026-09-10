# B2 Wave 8 product and operational decision record

**Decision date:** 2026-09-06
**Owner approval:** recorded in the Wave 8 kickoff session
**Applies to:** login-orchestrated demo reset and the Wave 10 exposure gate

> **Superseded in part on 2026-09-09.** Decision 2 below (timeouts) has been replaced by the
> 2026-09-09 owner decision: **eligibility 45 seconds, reset 10 seconds, overall 60 seconds**. The
> 30-minute idle threshold in decision 1 is unchanged, and decisions 3 and 4 are unaffected. The
> superseded `2s / 2s / 4s` values are retained below, struck through, so the change of position is
> auditable. See `docs/evidence/b2-task-8-2/owner-decision-20260909.json`.

## Approved decisions

1. **Idle threshold: 30 minutes.** Eligibility is computed from the persisted
   `PortfolioResponse.updatedAt` value for the single portfolio whose `userId` is the compiled-in
   demo UUID. The comparison is exact: `age > 30m` is eligible; below, equal, or future timestamps
   are ineligible. Viewing, an unsaved draft, and the 150-second presence TTL do not refresh or
   defer this persisted-write signal. A Wave 10-qualifying production proof waits until the real
   threshold is strictly exceeded; a threshold override is diagnostic only.
2. **Timeouts (revised 2026-09-09): eligibility 45 seconds, reset 10 seconds, overall 60 seconds.**
   This supersedes the 2026-09-06 values of ~~eligibility 2 seconds, reset 2 seconds, overall 4
   seconds~~, whose rationale accepted up to approximately four seconds of additional latency after
   successful demo authentication and accepted that cold or slow backends can cause a fail-open
   skip. Those values were set without engaging the recorded cold-start evidence. All four services
   are permitted to run at `min_replicas = 0` (`infrastructure/terraform/azure/variables.tf`,
   `modules/container-app/variables.tf`). When `portfolio-service` has scaled to zero and no earlier
   request wakes it, the login-orchestrated eligibility read encounters the condition for which this
   repository records an observed **~35-second** cold start
   (`docs/changes/CHANGES_NEW_USER_SIGNUP_PROFILE_2026-08-12.md`, corroborated by
   `docs/analysis/azure-container-migration-analysis.md` at a 20–35 second realistic worst case).
   No value near 2 or 4 seconds can absorb that, so the superseded budget would have caused the
   automatic reset to skip in precisely the case it exists for.

   The approved values are internally coherent and bounded by a verified ceiling:

   - **45 seconds eligibility** allows headroom over the observed ~35-second cold start for Flyway
     startup migrations and Neon compute wake, and fires *before* the gateway's own 55-second
     downstream `response-timeout` (`api-gateway/src/main/resources/application-prod.yml`), so a
     Wave 8 timeout stays attributable to Wave 8 rather than to the route. **The ~35s figure is one
     recorded field observation, not a distribution — no p95/p99 exists for this path, so a slower
     cold start will still fail open.**
   - **10 seconds reset** targets a `portfolio-service` the eligibility leg has already warmed.
   - **60 seconds overall** is greater than the nominal `45 + 10 = 55` leg sum, so it is intended as
     a backstop. **The margin is only 5 seconds and also absorbs target construction and orchestration
     overhead; if that overhead consumes the margin, the overall deadline can pre-empt a leg.**

   **The expected cost effect is negligible**, which makes the longer budgets compatible with the
   project's $5–10/month Azure target. This is an engineering expectation, not measured billing
   evidence: the eligibility read normally moves the `portfolio-service` wake a few seconds earlier
   than the UI's own post-login portfolio read rather than adding a wake, and holding one gateway
   request open longer is small against the Container Apps Consumption free grant. **The "moves
   rather than adds" part holds only when the viewer goes on to
   read the portfolio** — the ordinary path after a demo login; a login abandoned before any
    portfolio read does add a wake. The timeout values are expected to have negligible incremental
    cost because they principally change how long an already-open gateway request is held; this is
    an engineering expectation, not measured billing evidence.

   **The accepted cost is login duration, not money.** A first demo login after scale-to-zero may
   take approximately 60–95 seconds in total: the `api-gateway` cold start, which is paid before
   the login handler runs and is therefore outside these timers, plus up to 60 seconds of
   orchestration.
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
| `app.demo-login-reset.eligibility-timeout` | `APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT` | `45s` |
| `app.demo-login-reset.reset-timeout` | `APP_DEMO_LOGIN_RESET_RESET_TIMEOUT` | `10s` |
| `app.demo-login-reset.overall-timeout` | `APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT` | `60s` |

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

### Operational semantics of the 2026-09-09 timeouts

**Fail-open is unchanged and applies to every timeout**, per-leg or overall: login proceeds, no
error reaches the browser, and the gateway abandons waiting for a clean automatic-reset outcome.
The manual reset control remains the fallback when the portfolio is not reset.

**"Reset before the UI's first read" is claimed only for clean orchestration success.** On a
fail-open path the reset outcome is uncertain: the portfolio may remain un-reset, may already have
been reset before a later gateway failure, or may be changed by a reset that commits after the
deadline. Cancelling the gateway's publisher is not a downstream transaction rollback (see *Time
and cancellation*), so the UI's first read can observe any of those states and a late commit can
change it under the viewer. These are not defects in the orchestration; they are accepted
consequences of a best-effort, fail-open maintenance operation. Evidence
classification therefore never imposes an ordering between `demo_reset_succeeded` and
`demo_reset_self_call_skipped`.

**Per-leg durations must stay queryable so these values can be retuned from production evidence.**
The approved numbers are initial production values chosen against recorded cold-start evidence,
not measurements of this orchestration. Both directions are already covered on `main`, and
The successful path now records the complete leg explicitly rather than inferring it from request-level
client observations:

- **Timed-out legs** — `demo_reset_self_call_skipped` carries monotonic `elapsedMillis`, the exact
  `timeoutScope` (`per-leg` or `overall`), and `overallTimeoutPhase` for overall timeouts. `leg`,
  `reason`, and the inbound trace id are present on every skip outcome.
- **Non-timeout failures** — connection failure, non-2xx status, and response-shape failure carry
  **no** `elapsedMillis` by design; they are classified by `reason` rather than timed. "Elapsed time
  for every failure category" is not a claim this record makes.
- **Successful legs** — `demo_reset_self_call_completed` is emitted only after full response-body
  decoding or release. It carries `leg`, `httpStatus`, monotonic `elapsedMillis`, the inbound trace
  id, and replica token, with no URL, user identifier, or credential. Standard
  `http.client.requests` telemetry remains available, but it stops when the response is obtained and
  is not treated as complete body-processing time; `DemoLoginResetObservationTest` characterizes its
  safe low-cardinality tags and its unattributed `uri=none` value separately.

Timed-out and successful elapsed times are therefore captured separately for both legs, which is the
precondition for reducing these values later without changing the contract.

**Login feedback is sufficient as it stands.** `frontend/src/app/(auth)/login/page.tsx` already
disables the submit control and shows `Signing in…` while the request is pending. Demo-specific
copy explaining that the environment may be waking is a non-blocking enhancement, not part of this
decision.

## Authorization boundary

This record authorizes offline source and test work only. Push, pull-request creation, workflow
execution, deployment, cloud/registry/secret access, production probing, Task 8.8 deployment, Task
8.9 live proof, and Wave 10 exposure remain separately owner-gated. A gateway artifact containing
Wave 5 behavior also requires Task 5.6 GO before deployment.

### 2026-09-10 authorization addendum

The owner subsequently and explicitly approved both Task 5.6 GO and one Task 8.8 Azure production
deployment of the combined gateway artifact. The approved dispatch uses the protected
`.github/workflows/deploy.yml` entry point, targets `main`, sets `deployment_mode=scoped`, sets
`services=api-gateway`, leaves `prebuilt_digest` empty, and pins `expected_main_sha` to the full
`main` SHA containing the docs-only authorization record. The reviewed application-source baseline
is `37f3860062edf5cdf2c9494cc8669804c0d2e561`; the gateway source tree must remain identical after
the record merges. Push, PR creation, and merge of this docs-only record were also explicitly
approved. This addendum does not authorize Task 8.9's production login, portfolio mutation, KQL/log
queries, cleanup, any AWS action, frontend deployment, feature-flag enablement, traffic manipulation
outside the workflow's normal single-revision update, rollback, or Wave 10 exposure. See
[`docs/evidence/b2-task-8-8/owner-approval-20260910.json`](../../evidence/b2-task-8-8/owner-approval-20260910.json).
