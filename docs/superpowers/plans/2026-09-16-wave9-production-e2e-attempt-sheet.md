# Wave 9 Lane — Wave 10.2 Step A Backend-Route Verification
# Sanitized Attempt Sheet (rev 3)

> **OWNER APPROVAL REQUIRED — two stages (see authorization section below).**
> Stage 1 (approve now): authorize authoring the Wave 9 Step A execute script
> (planning and coding only — no production, cloud, or credential action).
> Stage 2 (future): once the script is authored and independently reviewed,
> fresh owner authorization naming this "Wave 9 Step A" attempt is required
> before any execution. Earlier Task 4.9, Task 8.9, deployment, documentation,
> or merge approvals are NOT reusable authority for either stage.

> **Rev 3 — 2026-09-17:** Fable independent review of rev 2 returned REJECT
> (1 Critical, 3 Important, 5 required Minors). All required and recommended
> corrections applied:
> - C1: removed gate-map overclaim "Step A satisfies Wave 10.2 condition 5";
>   corrected to "Wave 10.2 Go-action Step A only"; surfaced the open owner
>   question about what Wave 9 condition 5 means pre-exposure
> - I1: added `--login-timeout-seconds 225` to the preflight block with the
>   > 165s guard rationale
> - I2: corrected login/self-call statement (login triggers the Wave 8
>   orchestration synchronously); added three Phase 3 consequences
> - I3: added Phase 2-to-Phase 3 handoff window (< ~300s before idle-out)
> - I4: split authorization into stage-1 (script authoring) and stage-2
>   (execution)
> - M1: fixed checklist — kickoff is a chat-supplied prompt, not a tracked file
> - M2: added Log Analytics workspace, Linux Docker daemon, and Windows
>   PowerShell 5.1 as operator preconditions
> - M3: noted wrapper writes evidence outside repo; sanitized copy imported
> - M4: dropped `TASK8_9_ACCESS_TOKEN`; Step A needs only the password; noted
>   new credential names for the Step A script
> - M5: fixed provenance chain citation (0079→0080→0081 supersession)
> - M6-M12 (recommended): Phase 5 as hand-issued az CLI; post-reset version
>   rule; cleanup NON_GO recovery path; "remain false" wording; E2E portfolio
>   reference; op-count cap note; op-count 0081 in Step A result

---

## Scope and gate claims — read carefully before citing this document

**"Wave 9" is a lane label only.** The production evidence this attempt
produces is for **Wave 10.2 Go-action Step A (pre-exposure backend-route
verification)**, not completion of Wave 9 or satisfaction of Wave 10.2
condition 5.

`tasks.md` 4382-4438 lists "Wave 9 actually completed" (condition 5) among
the Go *preconditions* that must already be satisfied before the Go action
(including Step A) runs. Step A is a step *inside* the Go action (4445-4453)
and therefore cannot itself discharge condition 5 under the current spec.

**Open owner/spec question:** What constitutes "Wave 9 actually completed"
pre-exposure? Wave 9 wires five frontend routes (9.1-9.5) to real backend
endpoints; those wires exist in the built source but are not deployed hidden or
served because the frontend build has not been run with the flags enabled. A
real-browser Production E2E is structurally impossible until Step B ships the
flag-bearing bundle. This creates a tension between:
- The spec's condition 5 ("actually completed, not merely unblocked")
- The structural impossibility of a browser-level Wave 9 Production E2E before
  Step B

This document surfaces that tension but does not resolve it. The owner must
decide whether (a) the backend-route proof (Step A) is accepted as satisfying
condition 5, (b) condition 5 is deferred to after Step B, or (c) another
interpretation applies. The evidence directory uses the `b2-wave-9/` lane
label and does not claim condition 5 satisfied.

**What Step A proves (independently of this question):**
The public backend API routes (`PUT /api/portfolio/holdings` and
`PUT /api/portfolio/demo-reset`) work correctly against the real deployed
gateway and return the expected golden state. This result is required as input
to Step B (the actual flag-set + deploy + browser smoke) regardless of how the
condition 5 question is resolved.

**What this attempt does not prove (Step B):** The deployed Asset Picker UI or
the manual-reset control render or function. Both `ENABLE_ASSET_PICKER` and
`ENABLE_DEMO_RESET_CONTROL` are compiled into the static frontend build (not
Azure-runtime-queryable). UI verification requires Step B: flag change +
new Azure frontend build/deploy + real-browser smoke, all separately
owner-gated.

---

## What this attempt does NOT use

**`verify_demo_reset_azure.py --mode execute` is NOT the Step A execute
mechanism.** That verifier is Task 8.9-scoped: it uses Azure Log Analytics
KQL queries and `classify_task8_9` to prove the login-orchestrated self-call
path event evidence in Azure Monitor. Step A proves the direct public endpoints
via HTTP response validation only — no KQL queries, no `classify_task8_9`,
no Azure Log Analytics dependency for the execute phase.

Note: the preflight phase (Phase 2) *does* use the Task 8.9 preflight wrapper
(`run_task_8_9_preflight.ps1`) and its RBAC rehearsal includes a KQL read, so
a readable Log Analytics workspace is still required as an operator
precondition (see Phase 2 preconditions below).

No Wave 9 task *targets* the login-orchestration self-call path, but Step A's
demo login synchronously triggers it (see `AuthController.java` 44-47:
`demoLoginReset.afterLogin(resp)...thenReturn(resp)` — the login response is
emitted only after the orchestration completes or the `overallTimeout` fires
fail-open). Consequences bound below under Phase 3.

A Wave 9-specific execute script (Step A verifier) implementing the Step A
sequence must be authored and independently reviewed before any execution.
**Execution is blocked until that script exists and is reviewed.**
The script must:
- Authenticate and use the JWT returned from login (not a pre-minted token)
- Make direct HTTP calls to `PUT /api/portfolio/holdings` and
  `PUT /api/portfolio/demo-reset`
- Use `scripts/derive_demo_golden_state.py` (Task 4.4a's oracle) for golden
  set derivation, non-golden composition setup, and post-reset validation
- Implement identity-checked `GET /api/portfolio` on every read
- Implement the bounded retry logic (≤ 3 total reset attempts, identity-checked
  re-observation on each `409`)
- Enforce `cleanup_max_attempts = 1` as a **literal constant in the script**,
  not a CLI default; cleanup fails closed on the first conflict
- Produce a sanitized JSON evidence file with operation ledger, version
  progression, validation results, and identity-checked reads, but no secret
  values, JWTs, `Authorization` headers, or `.env.secrets` content

---

## Authorization — two stages

### Stage 1 (approve to proceed): author the Wave 9 Step A execute script

Authorize Claude to write the Wave 9 Step A execute script, its tests, and the
corresponding attempt-sheet update. No production action, credential access,
cloud call, deployment, PR creation, or merge occurs in Stage 1.

### Stage 2 (separate future approval required): execution attempt

**Not requested here.** Once the Step A script is authored, independently
reviewed, and approved, a fresh owner authorization explicitly naming "Wave 9
Step A" is required before any execution. The staged breakdown below (Phases
1-5) describes what Stage 2 will do but cannot be approved until the Stage 1
script exists.

---

## Attempt parameters (Stage 2 — pending script availability)

| Field | Value |
|---|---|
| Proposed operator | Owner (owner-operated credential injection only) |
| Baseline commit | `main@ccbc12472d9860f05c0858c3e22b58bf5fd5da79` |
| UTC window | **TBD — owner to specify at Stage 2 approval** |
| Hard stop if window expires | Yes — stop before any in-progress mutation; restore if setup already ran |
| Phase 1-2 serving comparison / preflight | `scripts/run_task_8_9_preflight.ps1` + `verify_demo_reset_azure.py --mode preflight` with Azure-attested timing params and login timeout (see Phase 1-2 below) |
| Phase 3 execute script | **TBD — Wave 9 Step A verifier to be authored (Stage 1)** |
| Credential env vars for Phase 3 | Owner injects the demo password env var (name TBD in the Step A script; will NOT be `TASK8_9_DEMO_PASSWORD`); `TASK8_9_ACCESS_TOKEN` is not used — Step A authenticates first and uses the returned JWT; Claude never reads, prints, or records credential values |
| Demo email | `demo@wealthtracker.dev` (intentionally public, in `ci-verification.yml`) |
| Deployment provenance file | `docs/evidence/b2-task-8-9/deployment-provenance-20260911.json` |

---

## Expected reference identities

| Service | Revision | Digest | Provenance chain |
|---|---|---|---|
| API gateway | `api-gateway--0000081` | `sha256:090ad3ba4b7ada20bca71781d4a1ce3ac81029b8a23a8a1d180dce2cff540d09` | Supersession chain 0079 (Task 8.8b deployment, 2026-09-10) → 0080 → 0081 (2026-09-11 recovery attestation, `deployment-completion-20260911.json`); 0000081 is the current-serving identity |
| Portfolio service | `portfolio-service--0000096` | `sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126` | Attested independently via run `34328692256`; unchanged since Task 7.9 |

Step A must **consume** (not redefine) the current provenance; it reads and
records these values from the live deployment and asserts they match the
provenance document. Any revision or digest mismatch is a hard stop.
The Step A result must also record these values in its own evidence output
(per `tasks.md` 4491-4492).

Note: `docs/evidence/b2-task-8-8/deployment-completion-20260910.json` attests
`api-gateway--0000079`, not 0000081. The authoritative current-serving record
is `docs/evidence/b2-task-8-9/deployment-completion-20260911.json` (0000081).

---

## Operator preconditions (must be satisfied before Phase 1)

- **Windows PowerShell 5.1 exactly** — the wrapper (`run_task_8_9_preflight.ps1`)
  refuses any other edition or major version (exits 2)
- **Linux Docker daemon running and accessible** — the wrapper pulls
  digest-qualified images for the non-interference proof
- **Azure session active with required RBAC** — subscription, resource group
  `wealth-azure-prod-rg`, workspace `wealth-prod-la`, registry `wealthprodacr`,
  and the Container App reader role needed for the preflight's `az containerapp`
  calls
- **Log Analytics workspace readable** — the preflight RBAC rehearsal issues
  a KQL probe; this requires read access to workspace `wealth-prod-la`
- **Network access to `--noproxy '*'`** — probes use `--noproxy '*'`
- **Both `TASK8_9_*` env vars cleared from the parent shell** — the wrapper
  reads and scrubs them from its children; do not leave stale values

---

## Operation cap

### Phase 1 — Read-only serving comparison (before credentials or mutation)

| Operation class | Cap |
|---|---|
| Azure CLI serving-state queries (revision, traffic, health, digest, provider, Azure-attested configuration values) | ≤ 15 non-mutating calls; note the wrapper's replica polling (up to 18 `az containerapp replica list` calls) and preflight verifier operations (~14-15) are in addition to this cap and are documented in the wrapper |
| Required result | Both identities match provenance; gateway Azure-attested timeout overrides (120s/30s/165s) confirmed; route ceiling (150s) check passes; no configuration drift |
| **Flag state NOT checked** | `ENABLE_ASSET_PICKER` and `ENABLE_DEMO_RESET_CONTROL` are compiled into the static frontend build and are not Azure-runtime-queryable; flag verification belongs to Step B (build provenance); this attempt neither reads nor changes either flag |
| Stop if mismatch | Hard stop; do not proceed to activation or execution |

**Preflight invocation must explicitly pass all four Azure-attested parameters**
per the 2026-09-14 ratification
(`docs/evidence/b2-task-8-9/2026-09-14-azure-timeout-ratification.md`)
and the companion changes to `run_task_8_9_preflight.ps1` (landed PR #273):
```
--eligibility-timeout 120s
--reset-timeout 30s
--overall-timeout 165s
--login-timeout-seconds 225
```
`--login-timeout-seconds 225` is required because the verifier's config
validation raises an error when `login_timeout` is not strictly greater than
`overall_timeout` (165s); the wrapper passes 225s for exactly this reason.
Using the application-wide generic defaults (45s/10s/60s) would fail the
preflight even when the deployment is correct. The wrapper already carries
these values; the sheet lists them explicitly so an operator cannot accidentally
run the unmodified verifier binary with its generic defaults.

Evidence output note: the preflight wrapper refuses an in-repo path for
`-EvidenceOutput` (exits 2). The operator must write the output to a path
outside the repo and import a sanitized copy into `docs/evidence/b2-wave-9/`
after the run.

### Phase 2 — Activation sequence (run_task_8_9_preflight.ps1)

| Operation class | Cap |
|---|---|
| Gateway warm-up probes (GET health) | ≤ 6 probes, each ≤ 90 s, 5 s apart |
| Required first-200 | At least one probe returns HTTP `200`; any other outcome is exit `3`, stop |
| Preflight verifier (mode=preflight, all four timing params above) | Exactly 1 invocation after the first `200` probe; requires `preflight_passed` |
| Stop if not met | Hard stop; do not proceed to Phase 3 |

### Phase 2 → Phase 3 handoff window

The gateway replica idles out approximately 300 seconds after the last inbound
request. Phase 3 (the execute sequence) must begin within approximately 300
seconds of Phase 2's first `200` probe. If the execute invocation cannot start
within this window, the attempt must stop and treat the warm replica as
consumed; a fresh Phase 2 activation requires a new owner decision.

### Phase 3 — Execute sequence (Wave 9 Step A verifier — to be authored in Stage 1)

**Before writing any operation — login consequences to bind:**
1. The demo login synchronously triggers the Wave 8 login-reset orchestration
   (`overallTimeout = 165s` on Azure). The Step A script's login HTTP timeout
   must exceed 165s + headroom; bind at least 225s.
2. If the demo portfolio was idle ≥ 30 minutes at login time, the
   login-triggered orchestration may reset the portfolio and advance `version`
   before the login response returns. The pre-write identity-checked read must
   be the first portfolio read issued **after login completes**, not before.
3. After login and before the non-golden write, confirm a stable version from
   the identity-checked `GET /api/portfolio` result. Do not write if the version
   is advancing or uncertain.

| Operation class | Cap |
|---|---|
| Authentication (demo login) | Exactly 1 call; require `HTTP 200`; login HTTP timeout ≥ 225s; JWT returned — never printed or recorded |
| Non-golden composition write (`PUT /api/portfolio/holdings`) | Exactly 1 call using Task 4.4a's oracle-derived non-golden composition; require `HTTP 200` and strictly advanced version; an already-golden or no-op result is a hard stop |
| Identity-checked portfolio read (`GET /api/portfolio`) | ≤ 3 total across all reads (post-login, pre-write, and 409-triggered re-observations); exactly one match on `userId == DEMO_USER_ID` required on every single read, including retry re-observations; zero or multiple matches fail the attempt immediately |
| Demo-reset call (`PUT /api/portfolio/demo-reset`) | ≤ 3 total attempts; each uses the exact `version` from the immediately preceding identity-checked read; a genuine `HTTP 200` is required — a `409` result does not satisfy the gate |
| **Cleanup max attempts = 1 (script literal, not a CLI flag)** | The script MUST enforce `cleanup_max_attempts = 1` as a hardcoded constant; a cleanup conflict (`409`) is `NON_GO` and must not silently retry |
| Total mutating calls (login + composition write + reset attempts + cleanup) | ≤ 6 |

### Phase 4 — Validation and mandatory cleanup

| Operation class | Cap |
|---|---|
| Validate reset response | Response holdings must equal Task 4.4a's independently derived exact golden set; response `version` must be strictly greater than the non-golden write's version (the exact post-reset value is defined by Task 4.4a's oracle and must be stated in the Step A script before execution) |
| Post-reset identity-checked read | Exactly 1 `GET /api/portfolio` with userId identity check; confirms golden state persisted; a failure here is `NON_GO` |
| Cleanup | Armed before the non-golden composition write; executes unconditionally on every path after setup; uses `PUT /api/portfolio/demo-reset` with the then-current identity-checked observed version and real JWT; `cleanup_max_attempts = 1` (script literal) |
| Post-cleanup identity-checked read | Exactly 1 `GET /api/portfolio` with userId identity check |
| Cleanup success requirement | `HTTP 200` on first attempt; a `409` or failure is `NON_GO`; do not claim restoration or Step A completion |
| Cleanup NON_GO recovery | If cleanup returns `409` or fails, the demo portfolio is left non-golden. Recovery: the Wave 8 login-reset orchestration restores it on the next idle demo login (after ≥ 30 min idle), or the owner may run a manual reset. Record the incident precisely and do not claim restoration. |

### Phase 5 — Final serving revalidation (hand-issued az CLI, no wrapper/wake)

| Operation class | Cap |
|---|---|
| Serving identity + configuration re-check | ≤ 10 hand-issued `az containerapp show` / `az containerapp revision show` calls; no wrapper invocation, no activation probe (a second wake would consume new owner authority) |
| Required result | Same identities, revisions, digests, and Azure-attested configuration as Phase 1 |
| Stop if drift detected | Record as `NON_GO`; do not claim Step A complete |

---

## Retry cap

| Retry type | Cap |
|---|---|
| Total demo-reset attempts (including 409-triggered re-observe + retry cycles) | ≤ 3 |
| Cleanup attempts | **1 — script literal; cleanup fails closed** |
| Serving comparison retries | None beyond the stated call caps |

---

## Cleanup path (recap)

Cleanup is **armed before the non-golden composition write** and runs
unconditionally after setup on every path (pass, fail, or stop):

1. Obtain the current demo portfolio via identity-checked `GET /api/portfolio`.
2. Issue `PUT /api/portfolio/demo-reset` with that exact observed version and
   the real JWT; `cleanup_max_attempts = 1` (script literal).
3. Require `HTTP 200` on the first attempt.
4. Verify golden state with an identity-checked `GET /api/portfolio`.
5. A `409`, non-200, or identity mismatch is `NON_GO`; record and stop; see
   the recovery path above.

---

## Expected evidence paths

All evidence files go under `docs/evidence/b2-wave-9/` (lane label; gate claim
is Step A, not condition 5):

| Artifact | Content |
|---|---|
| `wave9-step-a-attempt-sheet-<date>.md` | This document (final owner-authorized form) |
| `wave9-step-a-raw-<date>.json` | Raw Step A execute output (sanitized; no secrets, JWTs, Authorization values) |
| `wave9-step-a-operator-transcript-<date>.txt` | Sanitized operator transcript (host paths/identity redacted; no credential values) |
| `wave9-step-a-preflight-<date>.json` | Sanitized copy of preflight output imported from out-of-repo path; 120s/30s/165s/225s confirmation included |
| `wave9-step-a-decision-<date>.md` | Evidence summary, serving comparison with provenance chain, operation ledger (identity checks, status codes, version progression, bounded retry count, golden-state assertions, cleanup result), gate map (Step A stated as Wave 10.2 Go-action Step A; condition-5 question surfaced but not resolved), and independent-review request |

No secret values, JWT-like patterns, `Authorization` header values, or
`.env.secrets` content may appear in any tracked artifact.

---

## STOP / GO matrix

| Condition | Required result |
|---|---|
| No fresh, exact owner authorization for Stage 1 (script authoring) | **STOP** — do not author the script |
| Wave 9 Step A execute script not yet authored, tested, and independently reviewed | **STOP** — execution (Stage 2) is blocked |
| No fresh, exact owner authorization for Stage 2 naming "Wave 9 Step A" | **STOP** before any live action |
| Operator preconditions not met (PS 5.1, Docker, Azure session, Log Analytics, network) | **STOP** before Phase 1 |
| Serving identity or configuration mismatch vs provenance (including 120s/30s/165s timeout values) | **STOP** before any mutation; no workaround or deployment |
| Activation — first 200 not achieved within 6 probes | **STOP** (exit 3); do not proceed to Phase 3 |
| Preflight fails or Azure-attested timeout values do not match | **STOP**; do not proceed to Phase 3 |
| Phase 2 → Phase 3 handoff exceeds ~300s idle-out window | **STOP**; treat warm replica as consumed; fresh Phase 2 requires new owner decision |
| Post-login version unstable or uncertain before composition write | **STOP**; do not write |
| Non-golden composition write does not advance version or returns non-200 | **STOP** as `NON_GO`; mandatory cleanup if portfolio state was mutated |
| Identity selection returns zero or multiple matches on any read (including 409-triggered re-observations) | **STOP** as `NON_GO`; mandatory cleanup if setup already ran |
| Reset never produces genuine `HTTP 200` within 3 total attempts | **STOP** as `NON_GO`; mandatory cleanup |
| Reset response or post-reset identity-checked read does not match Task 4.4a golden state | **STOP** as `NON_GO`; mandatory cleanup |
| Cleanup returns non-200 or post-cleanup identity check fails | **STOP** as `NON_GO`; record incident; see cleanup NON_GO recovery path |
| Final serving comparison shows drift from Phase 1 | **STOP** as `NON_GO`; do not claim Step A complete |
| All conditions pass and independent review accepts the packet | Wave 10.2 Go-action Step A complete; condition-5 gate effect is a separate owner decision; Wave 10.2 remains closed; Step B is a separately owner-gated decision |

---

## What this attempt does NOT authorize

- Enabling `ENABLE_ASSET_PICKER` or `ENABLE_DEMO_RESET_CONTROL` (they are
  repository variables, set to unset/false currently — do not set `true`)
- Any Azure frontend build or deployment
- Any real-browser smoke test (Step B)
- Any PR creation, push, or merge
- Any rollback of the deployed serving state
- Expansion to other user accounts, portfolios, or endpoints
- Any action against the B1/Spec-A E2E subject ID
  (`00000000-0000-0000-0000-000000000e2e` is the fixed E2E subject/user ID used
  in the decimal-fidelity proof; it is not a portfolio ID)
- Step B or any advance past the Step A gate

A Step A GO does not expose the picker. Step B requires a separate owner
decision: pre-Step B backend-route confirmation, both repository flags set to
`true` together in one change, a new Azure frontend build/deploy, and the
real-browser post-deploy smoke. If the Step B smoke fails, rollback is both
flags back to unset/false, another complete frontend build/deploy, and fresh
uncached browser proof that both controls are absent.

---

## Checklist

- [x] Authoritative records read: master plan, tasks.md Wave 9/10 sections
      (4079-4527), Wave 10.2 audit, Task 4.9 evidence, Task 8.9 Decision 2,
      deployment provenance (0079→0080→0081 chain), Azure timeout ratification
- [x] Kickoff note is a chat-supplied prompt (Codex-worktree, intentionally
      uncommitted); it is not a tracked file; no tracked supersession check applies
- [x] Rev 1 reviewed by owner; five correction points received
- [x] Rev 2 reviewed by Fable; REJECT — 1 Critical, 3 Important, 5 required
      Minors, 7 recommended Minors
- [x] Rev 3 prepared (this document); all required and recommended Fable
      corrections applied; no production action taken
- [ ] **Stage 1: Owner authorization to author the Wave 9 Step A execute script**
- [ ] Wave 9 Step A execute script authored, tested, independently reviewed
- [ ] This attempt sheet updated with exact execute invocation from the script
- [ ] **Stage 2: Owner authorization naming "Wave 9 Step A" execution**
- [ ] Execution window and operator confirmed
- [ ] Evidence packet produced under `docs/evidence/b2-wave-9/`
- [ ] Independent technical review of evidence packet requested and accepted
- [ ] Status report: Step A outcome, condition-5 question surfaced to owner,
      documentation, PR, merge, and Step B each recorded as separate decisions
