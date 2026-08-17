# Bugfix Requirements Document

## Introduction

This spec covers two unrelated defects discovered together while triaging synthetic-monitoring
failures, bundled into one bug-fix SDD because both must clear before the same release gate:

- **Track A (security):** Playwright's failure-diagnostic capture (trace, HTML report,
  `test-results/`) can contain the real `E2E_TEST_USER_PASSWORD` credential, and multiple CI
  workflows upload that output to `actions/upload-artifact` on a **public** GitHub repository with
  no sanitization step. Two such artifacts have already leaked the populated credential and were
  deleted after the fact.
- **Track B (test correctness):** `ProductionRateLimitingIntegrationTest.burstAllowedThenThrottledWithDecrement`
  asserts an exact `X-RateLimit-Remaining` decrement sequence without first proving its observation
  window is free of token replenishment, and is confirmed non-deterministic (evidence in Bug
  Analysis below) — the mechanism behind that non-determinism is not established (see Confirmed
  Evidence vs. Hypotheses). The same test's post-burst check also does not currently verify the
  frozen requirement it is supposed to prove.

**Release gate:** this SDD produces **one root-cause/requirements document, one design document,
and one tasks document**, but **two independently reviewable implementation PRs** — Track A must
merge first; Track B branches from the resulting `main`, not from Track A's branch. **Both PRs must
merge and pass CI before the owner proceeds to Terraform Apply.** Deployment of unrelated work
(e.g. dispatching `deploy-azure.yml` for PR #101) is independently owner-authorized and is not
gated by this document. No deployment, Terraform operation, password rotation, or real-credential
use belongs to this documentation phase or to either implementation PR.

---

## Bug Analysis

### Track A — Playwright artifact secret exposure

#### Current Behavior (Defect)

- **A1.1** WHEN any of the following Playwright specs run, THE test fills the real
  `E2E_TEST_USER_PASSWORD` value (or a hardcoded literal fallback when the env var is unset) into a
  live `input[type="password"]` field:
  - `frontend/tests/e2e/aws-synthetic/login.spec.ts:11,24`
  - `frontend/tests/e2e/aws-synthetic/ai-insights.spec.ts:13-14`
  - `frontend/tests/e2e/aws-synthetic/aws-synthetic.spec.ts:15-16,42,48`
  - `frontend/tests/e2e/aws-synthetic/live-contract.spec.ts:18-19`
  - `frontend/tests/e2e/azure-synthetic/login.spec.ts:12,25`
  - `frontend/tests/e2e/azure-synthetic/ai-insights.spec.ts:26-27`
  - `frontend/tests/e2e/azure-synthetic/azure-synthetic.spec.ts:15,41,47`
  - `frontend/tests/e2e/azure-synthetic/live-contract.spec.ts:48-49`

  This is a wider inventory than checkpoint entry [1]'s "existing synthetic tests" bullet named —
  entry [1] cited only `login.spec.ts` per cloud; all eight files above independently duplicate the
  fill, confirmed by `rg -rl 'input\[type="password"\]' frontend/tests/e2e`.
- **A1.2** WHEN `frontend/tests/e2e/{aws,azure}-synthetic/api-live-smoke.spec.ts` runs, THE test
  sends the real `E2E_TEST_USER_PASSWORD` (via an `envOrSecret` fallback chain, lines 56-59/79-82)
  in a JSON request body through Playwright's `APIRequestContext` (`.post(...)` call and body, aws
  lines 76-77 / azure lines 247-248) — never through a DOM field. This is a **confirmed transmission path** for the same
  real credential as A1.1, distinct in mechanism from the DOM-fill path. Whether Playwright's trace
  capture records `APIRequestContext` bodies the same way it records `page`-driven network activity
  is **not** established by this document (see Confirmed Evidence vs. Hypotheses, and Investigation
  Gate A-IG.4).
- **A1.3** WHEN the CI integration suite (`auth-jwt-health.spec.ts`, `dashboard-data.spec.ts`,
  `golden-path.spec.ts`, run by `frontend-e2e-integration.yml:116-120`) authenticates, it does so via
  `frontend/tests/e2e/helpers/browser-auth.ts:26-28` and `helpers/api.ts:14-19`, which POST a
  **hardcoded, already-public local-dev credential** (`local-dev-password-2026`, seeded by
  `V15__Reconcile_Auth_Seed_Users.sql`) through the same `APIRequestContext` mechanism as A1.2. This
  is lower severity than A1.1/A1.2 because the value is not a secret, but it exercises the identical
  unconfirmed capture question in A1.2.
- **A1.4** WHEN a test that reaches A1.1's `trace: "retain-on-failure"` (`frontend/playwright.config.ts:21`)
  fails, or its `html` reporter (`frontend/playwright.config.ts:18`) renders a failed run, THE
  Playwright runner persists the filled DOM value inside `frontend/test-results/**/trace.zip` and/or
  `frontend/playwright-report/`, with no sanitization of captured DOM/network state.
- **A1.5** Of the 14 `actions/upload-artifact` steps across `.github/workflows/*.yml` (confirmed by
  `rg -n "uses: actions/upload-artifact" .github/workflows`), **5** publish Playwright output and are
  in scope for this fix:
  - `synthetic-monitoring.yml:90-96` (AWS job, `playwright-report`) — `if: always()`
  - `synthetic-monitoring.yml:189-195` (Azure job, `playwright-report-azure`) — `if: always()`
  - `frontend-e2e-integration.yml:130-136` (`playwright-integration-report`) — `if: failure()`
  - `frontend-ci.yml:93-99` (`playwright-smoke-report`) — `if: failure()`
  - `ci-verification.yml:281-288` (`playwright-traces`, `frontend/test-results/`, explicitly
    including traces and error-context snapshots per the workflow's own comment at lines 277-280) —
    `if: failure()`

  The remaining **9** upload steps are confirmed **not** Playwright output and are out of this fix's
  scope unless a later inventory (Investigation Gate A-IG.5) finds otherwise: `ci-verification.yml`
  unit-test-results (:56-61), integration-test-results (:102-107), pact-contracts (:133-138),
  container-logs (:294-299); `ci.yml` unit/integration Test Results on Failure (:60-62, :120-122);
  `frontend-ci.yml` static export artifact (:45-49, Next.js build output, no credentials); `terraform.yml`
  Plan Artifact (:112-113) and Apply Artifact (:174-176, both out of scope per Non-Goal 4).
- **A1.6** THE `synthetic-monitoring.yml` "Upload Report" steps run `if: always()`, not
  `if: failure()` — a sanitizer cannot rely on this workflow's own failure gate as an implicit
  safety net, because the upload step is not gated on test outcome at all.
- **A1.7** WHEN any artifact in A1.5 is uploaded, THE artifact becomes downloadable by any GitHub
  user for the workflow's configured retention window (7 days for the four `playwright-report*`
  artifacts, 14 days for `playwright-traces`), because the repository is public. Two such artifacts
  have already been confirmed to contain the populated credential (both since deleted).
- **A1.8** No existing control sanitizes this path. `common-observability`'s `SanitizingSpanExporter`
  (`common-observability/src/main/java/com/wealth/observability/SanitizingSpanExporter.java`) is
  correct and active, but its scope is OpenTelemetry `SpanData` passed to a `SpanExporter` — it never
  sees files the Playwright runner writes to disk, and cannot reach them.

#### Expected Behavior (Correct) — mechanism-neutral

- **A2.1** THE system SHALL ensure no configured secret/sentinel value occurs in any Playwright
  artifact reaching `actions/upload-artifact`, including inside nested archives (e.g. `trace.zip`)
  and generated HTML/data files.
- **A2.2** THE system SHALL upload only a sanitized staging directory to `actions/upload-artifact`;
  the Playwright runner's original output directory SHALL NOT itself be the upload path.
- **A2.3** THE guard SHALL fail closed: IF the sanitizer/scanner errors, encounters a format it
  cannot safely inspect, or detects a post-sanitize match, THEN the upload SHALL be prevented and
  the workflow SHALL remain red. The guard SHALL NOT convert a failed test run to green.
- **A2.4** THE fix's regression tests SHALL use dummy sentinel values only — never a real credential
  — and SHALL cover, at minimum: a plain-text match, a match nested inside an archive, an artifact
  with no match, and the fail-closed path.
- **A2.5** WHERE content cannot be proven safe, THE system SHALL prioritize security over retaining
  diagnostic value; diagnostic value SHALL be retained only once safety is established.
- **A2.6** THE sanitization/scanning control SHALL be centralized and reusable across every
  Playwright-uploading workflow identified in A1.5 (and any others A-IG.5 finds); duplicated
  per-workflow shell snippets SHALL NOT be an acceptable steady state.
- **A2.7** Password rotation SHALL remain owner-controlled and out of scope for this fix. Deletion of
  the two already-identified artifacts is already complete and SHALL NOT be treated as evidence that
  recurrence is prevented.

#### Unchanged Behavior (Regression Prevention)

- **A3.1** Diagnostic capture SHALL continue to be available for local/interactive debugging use —
  this is unaffected by CI upload behavior. In CI, retained diagnostic content SHALL be preserved
  only once it is proven safe by the sanitization/scan gate (A2.1-A2.3); THE fix MAY additionally
  suppress rich capture (trace/screenshots) at the point of generation, specifically on tests where
  credential entry is incidental rather than under test, as one layer of the recommended
  defense-in-depth approach (see Approach Comparison). A3.1 does not guarantee capture survives to
  the uploaded artifact for any given test — only that it is not deliberately discarded for tests
  where it can be proven safe.
- **A3.2** The `always()` / `failure()` step-level conditions identified in A1.5 SHALL remain the
  baseline test-result eligibility for upload. The upload step's own `if:` condition SHALL combine
  that baseline eligibility with a successful sanitization/scan gate (A2.3), so the
  `actions/upload-artifact` step itself does not run when sanitization fails, rather than running
  and declining to publish. This SHALL NOT alter the underlying test's own pass/fail result as
  reported by CI — only whether a downloadable artifact is produced.
- **A3.3** Non-sensitive diagnostic content (console logs, network timing, DOM state not containing a
  configured secret) SHALL CONTINUE to be visible in the resulting artifact wherever it can be proven
  safe.

### Track B — Rate-limit integration-test flake

#### Current Behavior (Defect)

- **B1.1** `ProductionRateLimitingIntegrationTest.burstAllowedThenThrottledWithDecrement`
  (`api-gateway/src/test/java/com/wealth/gateway/ProductionRateLimitingIntegrationTest.java:176-193`)
  fires `STANDARD_BURST` (3) requests in a tight loop with **no inter-request delay and no retry**,
  then asserts `X-RateLimit-Remaining` decrements `containsExactly("2","1","0")` (line 193) against
  a Spring Cloud Gateway `RedisRateLimiter` (`org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter`,
  instantiated in `GatewayRateLimitConfig.java:48-52`) configured to replenish 1 token/sec.
- **B1.2** This assertion is confirmed non-deterministic by direct reproduction, not inference: the
  exact command from checkpoint entry [1] —
  `.\gradlew.bat :api-gateway:integrationTest --tests "com.wealth.gateway.ProductionRateLimitingIntegrationTest.burstAllowedThenThrottledWithDecrement" --rerun-tasks --no-daemon`
  — reported `BUILD SUCCESSFUL` on one fresh invocation (checkpoint entry [1]) and, on an
  independent fresh invocation for this document, **failed** at line 193 with:
  ```
  Expecting actual:
    ["2", "2", "1"]
  to contain exactly (and in same order):
    ["2", "1", "0"]
  ```
  (full failure in `api-gateway/build/test-results/integrationTest/TEST-com.wealth.gateway.ProductionRateLimitingIntegrationTest.xml`,
  run at 2026-08-16T16:55). Same command, same code, two different outcomes on the same machine.
  **What this proves:** the assertion is non-deterministic. **What this does not prove:** which
  clock, precision, connection/JIT warm-up cost, or other mechanism produced the repeated `"2"` —
  see Confirmed Evidence vs. Hypotheses. The prior handoff's blanket claim that this test "fails on
  main" is corrected to: it is non-deterministic and does not fail on every run.
- **B1.3** The same test class already retries the *post-burst throttle* check
  (`awaitThrottledResponse`, lines 142-159) up to `MAX_THROTTLE_WAIT_ATTEMPTS` (30) times, with a
  comment (lines 123-134) naming "wall-clock replenishment drift under CI/slower-runner scheduling
  jitter" as the reason for *that* retry loop specifically. This comment documents the rationale for
  the throttle-reachability retry; it is not evidence for what caused the B1.2 counterexample, and
  is not treated as such here.
- **B1.4** `awaitThrottledResponse` (used at lines 198, 224, 234) and its analog in the sibling
  `RateLimitingIntegrationTest` (`MAX_EXTRA_THROTTLE_ATTEMPTS`, lines 122, 139-146) accept a `429`
  from **any** attempt within their bound, not specifically the first post-burst request. Frozen
  Requirement 7.3 (`.kiro/specs/production-rate-limiting/requirements.md:129`) requires "the first
  request exceeding available tokens receives HTTP status `429` and is not proxied downstream." As
  written, `ProductionRateLimitingIntegrationTest` — the `@Tag("integration")`, production-profile
  suite Requirement 7 actually governs — does not verify that literal requirement; it verifies
  "throttles within 30 attempts." **This is in scope for Track B** (checkpoint entry [3] ruling):
  the proven no-replenishment sample required by Expected Behavior below must cover the first excess
  request specifically, not just the burst requests.
- **B1.5** The local-profile sibling, `RateLimitingIntegrationTest.requestsExceedingBurstAreThrottled`,
  has the same eventual-`429` pattern but is **not** governed by frozen Requirement 7 — that
  requirement's suite is specifically the production-profile, `@Tag("integration")` class — and no
  requirement or evidence ties this sibling test to Track B's defect. It remains **out of scope**.
- **B1.6 [correction, supersedes Revision 1]** `docs/todos/TODOS_2026-04-07.md:13` describes
  `requestsExceedingBurstAreThrottled` as asserting "an exact `X-RateLimit-Remaining` decrement
  sequence... with no buffer for CI scheduling jitter." Full-file inspection of
  `RateLimitingIntegrationTest.java` (195 lines) shows no such assertion anywhere in the file: the
  named method only asserts `statuses.contains(429)` behind its own retry hedge, and no other method
  asserts an exact decrement sequence. `git log -S "containsExactly" -- api-gateway/src/test/java/com/wealth/gateway/RateLimitingIntegrationTest.java`
  returns **no commits** — there is no recorded history of this file ever containing that assertion.
  Revision 1 stated the file "appears to have already been hardened since 2026-04-07," implying a
  change that the history does not support; the corrected statement is that the TODOS description
  was inaccurate for this file when written, not that it was later fixed.

#### Expected Behavior (Correct) — mechanism-neutral

- **B2.1** THE fix SHALL make `burstAllowedThenThrottledWithDecrement`'s full observation window —
  every allowed burst request **and** the first excess (post-burst) request — provably occupy a
  single no-replenishment interval before asserting against it, preserving Requirement 7.2's exact
  decrement sequence and Requirement 7.3's exact "first excess request is `429`, not proxied"
  assertion without weakening either to "eventually throttles" or a type/format check on the header.
- **B2.2** A sample that cannot be shown to occupy one no-replenishment interval SHALL be discarded
  (using a fresh rate-limit key) and retried within a bounded attempt count, not asserted against.
- **B2.3** THE fix SHALL NOT use an arbitrary long sleep, a timeout increase, `@RepeatedTest`-until-green,
  or any weakening of the asserted values to achieve a stable pass.
- **B2.4** THE fix SHALL preserve all existing rate-limit production behavior, headers, route
  mapping, and fail-open behavior; no production source file SHALL change unless a **separately
  demonstrated** product defect (not merely this test's flakiness) justifies it.
- **B2.5** `RateLimitingIntegrationTest.requestsExceedingBurstAreThrottled` (B1.5) is explicitly out
  of scope: no change to `RateLimitingIntegrationTest` is required or authorized by this document.

#### Unchanged Behavior (Regression Prevention)

- **B3.1** `GatewayRateLimitConfig`'s `standardRateLimiter` / `strictRateLimiter` / `authRateLimiter`
  beans, their replenish/burst/token configuration, and `RateLimitDenialResponseCustomizer` SHALL
  CONTINUE to behave exactly as today.
- **B3.2** Every `@Test`-annotated method in `ProductionRateLimitingIntegrationTest` other than
  `burstAllowedThenThrottledWithDecrement` SHALL CONTINUE to pass unmodified unless evidence specific
  to one of them independently justifies a change. (Stated this way, rather than as a hardcoded list,
  because Revision 1's hand-enumerated list under-counted the class's own test methods.)
- **B3.3** `RateLimitingIntegrationTest` SHALL CONTINUE to run under its existing `local` profile and
  assertions unmodified, per B1.5/B2.5.

---

## Approach Comparison

### Track A

1. **Capture prevention only** (API/storage-state auth in place of DOM fills; disable rich capture
   on credential-entry tests) — **reject as sole mechanism.** It removes the secret from capture on
   tests it is applied to, but provides no protection for a future test that fills a password
   without adopting the convention, and does nothing for A1.2/A1.3's `APIRequestContext` paths
   unless those are separately touched. Not fail-closed: protection depends on every future test
   author remembering to opt in.
2. **Post-processing only** (sanitize/scan runner output before upload; capture stays as-is) —
   **viable only if the fail-closed guarantee (A2.3) is airtight.** Defends every current and future
   test uniformly from one central point (satisfies A2.6) without per-test opt-in, but its safety is
   entirely a function of the scanner's format coverage — any output shape it cannot parse must be
   treated as unsafe (A2.3), which trades leak risk for diagnostic-availability risk on formats it
   doesn't yet handle.
3. **Defense in depth (recommended for design review):** suppress rich capture specifically on tests
   where credential entry is not the behavior under test (shrinking what must be scanned and
   removing a class of false-negative risk for those tests), retain full capture where the
   authentication flow itself is under test, and post-process/scan the sanitized staging directory
   before every upload regardless of which tests ran. Capture prevention is an additional
   risk-reduction layer where it applies; it does not relax the sanitizer's obligations under
   A2.1-A2.3. The staging scanner SHALL remain complete for every supported format and fail-closed
   (A2.3) for every unsupported one, independent of how much capture-prevention coverage exists —
   for tests where credential entry is itself the behavior under test (e.g. `login.spec.ts`) and
   capture cannot be suppressed, the scanner is the only layer and must hold on its own.

Do not select a specific sanitization mechanism (e.g. in-place ZIP rewrite vs. regenerate-and-replace
vs. pattern-based redaction) before Investigation Gates A-IG.1-A-IG.5 establish what must be
rewritten, removed, or replaced, and confirm the upload boundary the mechanism must cover.

### Track B

1. **Weaken or remove the exact assertion** — **reject.** Contradicts frozen Requirement 7.2/7.3,
   which checkpoint entry [1] explicitly preserves, and would mask real regressions in exact
   decrement/throttle behavior.
2. **Sleeps or retries without proving the window** — **reject.** This is the pattern already present
   in `awaitThrottledResponse` (B1.4): accept any `429` within N attempts. Applying the same pattern
   to the decrement-sequence assertion, or leaving B1.4's gap unaddressed, would launder the flake
   rather than fix it — and B1.4 is now in scope specifically to remove this pattern from the parts
   of Requirement 7 it currently weakens.
3. **Prove the no-replenishment precondition, then assert the exact sequence (recommended for design
   review):** instrument monotonic elapsed time and Redis server time/window state alongside the
   response sequence (Investigation Gate B-IG.2); accept a sample only once the full window — every
   burst request and the first excess request (B2.1) — is proven to occupy one no-replenishment
   interval; discard and retry with a fresh key within a bounded attempt count otherwise. This keeps
   the exact assertion's strength and controls for the timing variable B1.2 exposed, rather than
   hiding it.

---

## Investigation Gates

These gate mechanism selection in `design.md`. None may be skipped by inference from this document
alone; each requires its own evidence.

### Track A (A-IG)

1. **A-IG.1** Reproduce the leak using a unique dummy sentinel (never the real password) in an
   intentionally failing local/mocked Playwright fixture, covering at least one DOM-fill path
   (A1.1) and one `APIRequestContext` path (A1.2/A1.3).
2. **A-IG.2** Generate every relevant output shape from that fixture: `playwright-report/`
   (HTML + data files) and `test-results/` (`trace.zip`, error-context snapshots, other attachments).
3. **A-IG.3** Recursively inspect every plain file and every archive entry produced by A-IG.2 and
   record the exact paths/encodings where the sentinel appears. Any format that cannot be safely
   inspected SHALL be named explicitly and treated as unsafe-by-default (blocked), not silently
   passed.
4. **A-IG.4** Confirm or refute, using the A-IG.1 sentinel fixture, whether Playwright trace capture
   records `APIRequestContext` request bodies made via `.post()` outside `page`-driven navigation —
   resolving A1.2/A1.3's open question for both the real-secret path (`api-live-smoke.spec.ts`,
   both clouds) and the local-dev-credential path (`helpers/browser-auth.ts`, `helpers/api.ts`).
5. **A-IG.5** Enumerate every `actions/upload-artifact` step repository-wide via `rg` — not by
   assuming this document's inventory is exhaustive — and classify each as Playwright-output or not.
   The inventory SHALL be treated as incomplete if any discovered site is left unclassified.
   (Evidence snapshot as of this revision, per A1.5: 14 sites found, 5 classified Playwright-output,
   9 not — recorded for traceability, not as a target count design.md must reproduce; a new or
   changed workflow file changes these numbers without changing the gate itself.)
6. **A-IG.6** Do not select a specific sanitization mechanism until A-IG.1-A-IG.5 establish what must
   be rewritten, removed, or replaced, and which upload sites the mechanism must cover.

### Track B (B-IG)

1. **B-IG.1** Resolve the exact bundled `RedisRateLimiter` Lua/source (Spring Cloud Gateway
   `org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter`, `GatewayRateLimitConfig.java:48-52`)
   to establish which clock and time precision govern token replenishment.
2. **B-IG.2** Instrument a counterexample run with the full response sequence, monotonic elapsed
   time between requests, and Redis server time/window state at each request — not header values
   alone.
3. **B-IG.3** Pre-warm JIT/HTTP/Redis connection costs on a distinct rate-limit key before the
   measured burst, so first-request warm-up cost is not conflated with replenishment timing.
4. **B-IG.4 [Revision 4]** Characterize failure frequency via a bounded (not unbounded)
   repeat/stress run, and report the result honestly — including a result of zero additional
   failures, which is a valid characterization ("rare under fast execution"), not a failed
   attempt. An additional reproduced failing counterexample is preferred but not mandatory once
   B-IG.1 already establishes causation from source with certainty: at that point, the run's job
   is to characterize frequency, not to re-prove causation. Do not manufacture a failure (e.g. a
   contrived sleep timed to a second boundary) solely to force a second counterexample — that
   would substitute a constructed scenario for a naturally-occurring one without adding
   confidence B-IG.1 hasn't already provided, and spends bounded budget for no evidentiary gain.
5. **B-IG.5 [Revision 4]** Prove the first excess (post-burst) request is governed by the same
   replenishment mechanism as the burst requests (B1.4/B2.1). This gate is satisfied by *either*
   (a) extending B-IG.2's empirical instrumentation to the first excess request directly, *or*
   (b) an explicit source-level applicability proof, citing the specific evidence from B-IG.1,
   that the underlying mechanism (the Lua script and its Java caller) treats every rate-limit
   check identically regardless of burst-vs-excess position — i.e. that nothing in the mechanism
   distinguishes "request N" from "request N+1" against the same bucket key. A bare assertion of
   applicability without citing that evidence does not satisfy this gate.
6. **B-IG.6** Do not select a specific mechanism (retry/backoff shape, key-rotation strategy) until
   B-IG.1-B-IG.5 establish the actual timing behavior.

---

## Confirmed Evidence vs. Hypotheses / Open Evidence

### Track A

**Confirmed** (re-verified against `main` at `d7b5b8d` for this revision, not carried over
unverified): A1.1, A1.4-A1.8 in full. A1.2/A1.3's credential **transmission** via
`APIRequestContext` is confirmed by direct code reading; whether that transmission is **captured**
by Playwright tracing is not (Investigation Gate A-IG.4).

**Open — required before a mechanism can be chosen (design.md):** all six Investigation Gates
above (A-IG.1-A-IG.6) are unstarted as of this document.

### Track B

**Confirmed:** B1.1, B1.2 (dual pass/fail outcome, direct reproduction), B1.3 (scoped to what the
comment actually documents), B1.4 (code-read fact: `awaitThrottledResponse` accepts any attempt
within its bound), B1.5 (sibling test out of scope), B1.6 (TODOS correction, backed by `git log -S`).

**Open — required before a mechanism can be chosen (design.md):** all six Investigation Gates above
(B-IG.1-B-IG.6). In particular: the mechanism behind B1.2's `["2","2","1"]` counterexample —
replenishment tick, connection/JIT warm-up, clock skew, or something else — is **not** established
by this document. No candidate is asserted as root cause.

---

## Security / Threat Boundary for Public GitHub Artifacts

`wealthmgmtandportfoliotracker` is a public GitHub repository. Any artifact reaching
`actions/upload-artifact` is downloadable by any GitHub user — no repository-write access or
collaborator status required — for that artifact's configured retention window (A1.7). Deleting an
artifact after the fact removes it from future download but does not undo any download that
occurred during its exposure window — this is why A2.7 treats the two already-deleted artifacts as
remediated evidence, not as a closed exposure.

Two confirmed transmission vectors carry the real `E2E_TEST_USER_PASSWORD` secret for a seeded
account on the live production site: the DOM-fill path (A1.1, eight files) and the
`APIRequestContext` path (A1.2, `api-live-smoke.spec.ts` for both clouds). The DOM-fill path's
capture into an uploaded artifact is confirmed — Playwright's documented `retain-on-failure` trace
behavior (A1.4) plus the two already-leaked artifacts (A1.7) demonstrate it directly. The
`APIRequestContext` path's *transmission* of the real secret is equally confirmed by direct code
reading, but whether that transmission is *captured* into an artifact is open pending A-IG.4. Until
A-IG.4 resolves it, treat the `APIRequestContext` path as a **potential** artifact exposure carrying
a **confirmed** high-sensitivity transmission — not as a demonstrated leak on the same footing as the
DOM path. A third, lower-severity vector (A1.3) carries a non-secret hardcoded local-dev credential
through the same `APIRequestContext` mechanism, with the same open capture question.

For the DOM-fill path specifically, the exposure is not limited to the eight named tests: per
prior-session carry-forward state, any current or future Playwright test that fills a password field
and then fails can re-create the same exposure, because the capture-and-upload pipeline has no
per-field awareness of what it is capturing. The fix must therefore close the pipeline's upload
boundary (A2.1-A2.2) for the confirmed DOM path now, and extend the same closure to the
`APIRequestContext` path once A-IG.4 confirms (or rules out) capture.

---

## Implementation Verification Gates

Carried forward from checkpoint entry [1] as numbered, testable obligations for the later
implementation PRs (not satisfied by this document):

1. **IV.1** Track A's PR SHALL demonstrate, using only a dummy sentinel (never a real credential):
   (a) a pre-fix run where the sentinel leaks into an uploaded artifact, (b) a post-fix run where it
   does not, and (c) an end-to-end upload/download scan of a dummy artifact through the actual
   `actions/upload-artifact` / `actions/download-artifact` pair.
2. **IV.2** Track B's PR SHALL retain at least one unmodified-`main` counterexample reproducing
   B1.2's failure against its own base commit, and SHALL include two distinct negative proofs, not
   one: (a) a deliberately crossed or otherwise unprovable observation window (e.g. an injected
   replenishment tick or delay spanning the interval) SHALL exercise the discard-and-fresh-key path
   (B2.2) — the fixed test SHALL NOT report this as an assertion failure; and (b) a deliberate defect
   injected *within* a still-proven-valid window (e.g. a wrong decrement value, wrong status code, or
   incorrect proxying decision) SHALL make the exact Requirement 7.2/7.3 assertion fail, proving the
   fixed assertion still catches a genuine behavioral regression rather than only timing artifacts.
3. **IV.3** Both tracks' verification SHALL run the exact `integrationTest` Gradle task (not
   `test`), and any test-selection command SHALL assert a non-zero selected-test count; a run that
   silently selects zero tests SHALL be treated as a failure, not a pass.
4. **IV.4** Final CI for both PRs SHALL cover, in proportion to changed surfaces: unit tests,
   integration tests (`integrationTest` task), workflow/static validation of changed `.yml` files,
   Playwright sentinel tests (Track A), and container build verification.
5. **IV.5** Neither PR SHALL contain `.env.secrets`, `CHECKPOINT.md`, or the two pre-existing
   untracked junk files (`=2.8`, `=25,`). Track B's PR branches from the post-Track-A `main`, not
   from Track A's feature branch.

---

## Non-Goals

1. Rotating `E2E_TEST_USER_PASSWORD` — owner-controlled, tracked separately.
2. Deleting additional old artifacts — the two known instances are already deleted; this is not a
   substitute for the fix (A2.7).
3. Dispatching `deploy-azure.yml` / deploying PR #101 (`d7b5b8d`) to production.
4. Any Terraform change or `terraform apply`.
5. Redesigning or modifying production rate-limiter behavior — any production source change requires
   a separately demonstrated product defect, not this test's flakiness alone (B2.4).
6. Implementing Spec B1 (`portfolio-composition-contract`).
7. `RateLimitingIntegrationTest.requestsExceedingBurstAreThrottled` (B1.5) — the local-profile
   sibling test remains untouched; this document authorizes no change to it.

---

## Recorded Decisions

1. One bug-fix SDD at `.kiro/specs/rca-playwright-artifact-secret-exposure-and-rate-limit-flake/`
   (`bugfix.md`, `design.md`, `tasks.md`) covers both tracks; they are not split into separate SDDs
   because both gate the same pre-Terraform-Apply checkpoint.
2. Two independent, separately reviewable implementation PRs. Track A merges first; Track B branches
   from the post-Track-A `main`. See Implementation Verification Gate IV.5 for exclusions.
3. Three sequential approval gates, each reviewed by Codex, before any implementation:
   `bugfix.md` (root cause/requirements, this document) → `design.md` (architecture/security) →
   `tasks.md` (traceability, executable commands, red/green evidence, PR boundaries, stop/go
   checkpoints). The owner approves all three before implementation begins.
4. Claude implements; Codex remains senior architect/reviewer and does not author production code.
5. Final stop/go: both PRs must merge and pass CI before the owner proceeds to Terraform Apply.
   Deployment and Terraform remain separately authorized operational steps after delivery.
