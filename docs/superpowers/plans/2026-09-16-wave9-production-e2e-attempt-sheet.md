# Wave 9 Production E2E — Sanitized Attempt Sheet (rev 2)

> **OWNER APPROVAL REQUIRED. This sheet is NOT executable without an explicit,
> fresh owner authorization that names this Wave 9 / Step A attempt. Earlier
> Task 4.9, Task 8.9, deployment, documentation, or merge approvals are NOT
> reusable authority here.**

> **Rev 2 — 2026-09-17:** Five corrections applied per owner review of rev 1:
> (1) removed `verify_demo_reset_azure.py --mode execute` — that verifier is
> Task 8.9-scoped (login-orchestrated self-call / KQL classification) and does
> not establish the required Step A direct-public-endpoint proof; (2) removed
> Azure CLI flag-state queries — the frontend flags are compiled into the static
> build and are not Azure-runtime-queryable; (3) added explicit
> `--cleanup-max-attempts 1` binding (or equivalent for the Wave 9 execute
> script) so the operator cannot accidentally run the verifier's three-attempt
> default; (4) added the Azure-attested timing parameters (120s/30s/165s) to
> the preflight invocation per the 2026-09-14 ratification; (5) added explicit
> scope statement that this proves the backend API (Step A) only, not the
> deployed Asset Picker UI (Step B).

---

## Authorization request

**Decision requested:** authorize one bounded Wave 9 Production E2E
attempt (Wave 10.2 Step A — backend-route verification only) per the scope
below. The owner must operate or inject credentials. Claude must not print,
persist, transmit, or place passwords, tokens, Authorization headers, or
secret values in tracked evidence.

**Consequences:**
- **Approved:** the sequence below may run in the specified window; both
  production feature flags remain disabled throughout; no deployment, PR,
  merge, flag change, browser smoke, frontend build, or scope expansion
  occurs. A successful Step A does not expose the Asset Picker UI — Step B
  (flag change + new frontend deploy + real-browser smoke) is separately
  owner-gated.
- **Not approved / scope differs:** stop before any cloud, credential, or
  production action; Wave 9 and Wave 10.2 remain closed.

---

## What this attempt proves and what it does not prove

**Proves (Step A):** the public backend API routes (`PUT /api/portfolio/holdings`
and `PUT /api/portfolio/demo-reset`) work correctly against the real, deployed
gateway, returning the expected golden state. Independent of any frontend flag.

**Does not prove (Step B):** the deployed Asset Picker UI or the manual-reset
control render or function. Both `ENABLE_ASSET_PICKER` and
`ENABLE_DEMO_RESET_CONTROL` remain `false` throughout this attempt and are
compiled into the static frontend build, not queryable at Azure runtime. UI
verification requires a separate Step B (flag change → new Azure frontend
build/deploy → real-browser smoke), which is independently owner-gated.

A successful Step A satisfies Wave 10.2 condition 5 (Wave 9 live integration
complete). Wave 10.2 remains closed on its other conditions and the separate
Step B exposure decision.

---

## What Step A does NOT use

**`verify_demo_reset_azure.py --mode execute` is NOT the Step A execute
mechanism.** That verifier is explicitly Task 8.9-scoped: it uses Azure Log
Analytics KQL queries and `classify_task8_9` to prove the login-orchestrated
self-call path. Step A proves the direct public endpoints via HTTP response
validation only — no KQL queries, no `classify_task8_9`, no Azure Log Analytics
dependency. No Wave 9 task calls the login-self-call path or depends on
`updatedAt`, the idle threshold, or the self-call timeouts.

A Wave 9-specific execute script (Step A verifier) that implements the Step A
sequence described below must be authored and independently reviewed before any
execution is authorized. That script must:
- Make direct authenticated HTTP calls to `PUT /api/portfolio/holdings` and
  `PUT /api/portfolio/demo-reset`
- Use Task 4.4a's oracle (`scripts/derive_demo_golden_state.py`) to derive the
  golden set for non-golden setup and post-reset validation
- Implement the bounded retry logic (≤ 3 total reset attempts, identity-checked
  re-observation on each)
- Bind `--cleanup-max-attempts 1` (or enforce zero retries) so cleanup fails
  closed on the first conflict rather than silently retrying
- Produce a sanitized JSON evidence file with operation ledger and validation
  results, but no secret values

**[Execution blocked until Wave 9 execute script is authored and reviewed.]**

---

## Attempt parameters

| Field | Value |
|---|---|
| Proposed operator | Owner (owner-operated credential injection only) |
| Baseline commit | `main@ccbc12472d9860f05c0858c3e22b58bf5fd5da79` |
| UTC window | **TBD — owner to specify** |
| Hard stop if window expires | Yes — stop before any in-progress mutation; restore if setup already ran |
| Phase 1 serving comparison | `scripts/run_task_8_9_preflight.ps1` + `verify_demo_reset_azure.py --mode preflight` with Azure-attested timing params (see Phase 1 below) |
| Phase 3 execute script | **TBD — Wave 9 Step A verifier to be authored** (see above) |
| Credential env vars | Owner injects `TASK8_9_ACCESS_TOKEN` and `TASK8_9_DEMO_PASSWORD`; Claude never reads, prints, or records their values |
| Demo email | `demo@wealthtracker.dev` (intentionally public, in `ci-verification.yml`) |
| Deployment provenance file | `docs/evidence/b2-task-8-9/deployment-provenance-20260911.json` |

---

## Expected reference identities (from Task 8.8b / Task 8.9 accepted provenance)

| Service | Revision | Digest |
|---|---|---|
| API gateway | `api-gateway--0000081` | `sha256:090ad3ba4b7ada20bca71781d4a1ce3ac81029b8a23a8a1d180dce2cff540d09` |
| Portfolio service | `portfolio-service--0000096` | `sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126` |

Step A must consume (not redefine) Task 8.8b's Azure deployment evidence. Any
revision or digest mismatch between live serving and these identities is a hard
stop before any mutation.

---

## Operation cap

### Phase 1 — Read-only serving comparison (before credentials or mutation)

| Operation class | Cap |
|---|---|
| Azure CLI serving-state queries (revision, traffic, health, digest, provider, and configuration values including the gateway's Azure-attested timeout overrides) | ≤ 15 total non-mutating calls |
| Required result | Both identities match provenance; gateway Azure-attested timeout overrides (120s/30s/165s) match deployment; no configuration drift; route ceiling (150s) check passes |
| **Flag state NOT checked here** | `ENABLE_ASSET_PICKER` and `ENABLE_DEMO_RESET_CONTROL` are compiled into the static frontend build and are not Azure-runtime-queryable; flag verification belongs to Step B (build provenance comparison); this attempt neither reads nor changes either flag |
| Stop if identity or configuration mismatch | Hard stop; do not proceed to activation or execution |

**Preflight invocation must explicitly pass the Azure-attested timing
parameters** per the 2026-09-14 ratification
(`docs/evidence/b2-task-8-9/2026-09-14-azure-timeout-ratification.md`):
```
--eligibility-timeout 120s
--reset-timeout 30s
--overall-timeout 165s
```
Using the stale application-wide generic defaults (45s/10s/60s) would fail the
preflight even when the deployment is correct. The wrapper
(`run_task_8_9_preflight.ps1`) already carries these values (landed with PR #273);
the attempt sheet records them explicitly so an operator cannot accidentally run
the unmodified verifier with its generic defaults.

### Phase 2 — Activation sequence (run_task_8_9_preflight.ps1)

| Operation class | Cap |
|---|---|
| Gateway warm-up probes (GET health) | ≤ 6 probes, each ≤ 90 s, 5 s apart |
| Required first-200 | At least one probe returns HTTP `200`; any other outcome is exit `3`, stop |
| Preflight verifier (mode=preflight, with 120s/30s/165s timing params) | Exactly 1 invocation after the first `200` probe |
| Required result | `preflight_passed`; Azure-attested timeout values confirmed; no configuration drift |
| Stop if not met | Hard stop; no execute mode run |

### Phase 3 — Execute sequence (Wave 9 Step A verifier — to be authored)

| Operation class | Cap |
|---|---|
| Authentication (login) | Exactly 1 call; require `HTTP 200`; token never printed or recorded |
| Non-golden composition write (`PUT /api/portfolio/holdings`) | Exactly 1 call using Task 4.4a's oracle-derived non-golden composition; require `HTTP 200` and strictly advanced version; an already-golden or no-op result is a hard stop |
| Identity-checked portfolio read (`GET /api/portfolio`) | ≤ 3 calls total across setup and retries; exactly one match on `userId == DEMO_USER_ID` required on every read; zero or multiple matches fail the attempt immediately |
| Demo-reset call (`PUT /api/portfolio/demo-reset`) | ≤ 3 total attempts; each uses the exact `version` from the immediately preceding identity-checked read; a genuine `HTTP 200` is required — accepting `409` in lieu of `200` is not sufficient |
| **`--cleanup-max-attempts 1` (or equivalent)** | The execute script MUST be invoked with cleanup limited to one attempt; the verifier's default of three is not acceptable; a cleanup conflict is `NON_GO`, not forgiven |
| Total mutating calls (auth + composition write + reset attempts + cleanup) | ≤ 6 |
| Required reset result | At least one genuine `HTTP 200` within 3 attempts; a `409` may trigger one re-observe (identity check) + retry within the cap |

### Phase 4 — Validation and mandatory cleanup

| Operation class | Cap |
|---|---|
| Validate reset response | Response holdings must equal Task 4.4a's independently derived exact golden set; response `version` must be the expected post-reset value |
| Post-reset identity-checked read | Exactly 1 `GET /api/portfolio` with userId identity check; confirms golden state persisted; failing this is `NON_GO` |
| Cleanup (restore golden state) | Armed before the non-golden composition write; executes unconditionally on every path after setup; uses the same demo-reset endpoint with the then-current identity-checked observed version; `--cleanup-max-attempts 1` |
| Post-cleanup identity-checked read | Exactly 1 `GET /api/portfolio` with userId identity check |
| Cleanup success requirement | `HTTP 200` on first cleanup attempt; a `409` or failure is `NON_GO`; do not claim restoration or Wave 9 completion |

### Phase 5 — Final serving revalidation

| Operation class | Cap |
|---|---|
| Serving identity re-check (same query set as Phase 1, with 120s/30s/165s timing params) | ≤ 10 non-mutating Azure CLI calls |
| Required result | Same identities and configuration as Phase 1 |
| Stop if drift detected | Record as `NON_GO`; do not claim Wave 9 complete |

---

## Retry cap

| Retry type | Cap |
|---|---|
| Total demo-reset attempts (including any 409-triggered re-observe + retry cycles) | ≤ 3 |
| Cleanup attempts | **1 — no retries; cleanup fails closed** |
| Serving comparison retries | None beyond the stated call caps |

---

## Cleanup path

Cleanup is **armed before the non-golden composition write** and executes
unconditionally after setup on every path (pass, fail, or stop):

1. Obtain the current demo portfolio via identity-checked `GET /api/portfolio`.
2. Issue `PUT /api/portfolio/demo-reset` with that exact observed version and
   the real JWT; `--cleanup-max-attempts 1`.
3. Require `HTTP 200` on the first attempt.
4. Verify golden state with an identity-checked `GET /api/portfolio`.
5. Any cleanup failure or conflict is a `NON_GO` incident; record and stop —
   do not claim restoration or Wave 9 completion.

---

## Expected evidence paths

All evidence files go under `docs/evidence/b2-wave-9/` (new dated directory):

| Artifact | Content |
|---|---|
| `wave9-step-a-attempt-sheet-<date>.md` | This document (final owner-authorized form) |
| `wave9-step-a-raw-<date>.json` | Raw Wave 9 Step A execute output (sanitized; no secrets, no JWTs, no token values) |
| `wave9-step-a-operator-transcript-<date>.txt` | Sanitized operator transcript (host paths / identity redacted; no JWT, password, or token values) |
| `wave9-step-a-preflight-<date>.json` | Preflight wrapper JSON output (with 120s/30s/165s confirmation) |
| `wave9-step-a-decision-<date>.md` | Evidence summary, serving comparison with provenance link, operation ledger (identity checks, status codes, version progression, bounded retry count, golden-state assertions, cleanup result), gate map (precisely which Wave 9 / Wave 10.2 criterion satisfied), and independent-review request |

No secret values, JWT-like patterns, `Authorization` header values, or `.env.secrets`
content may appear in any tracked artifact.

---

## STOP / GO matrix

| Condition | Required result |
|---|---|
| No fresh, exact owner authorization naming this Wave 9 / Step A attempt | **STOP** before any live action |
| Wave 9 execute script not yet authored and independently reviewed | **STOP** — execution is blocked until the script exists and is reviewed |
| Serving identity or configuration mismatch vs Phase 1 reference (including Azure-attested timeout values) | **STOP** before any mutation; no workaround or deployment |
| Activation — first 200 not achieved within 6 probes | **STOP** (exit 3); do not proceed to execute mode |
| Preflight fails (`preflight_passed` absent or timeout mismatch) | **STOP**; do not proceed to execute mode |
| Non-golden write does not advance version or returns non-200 | **STOP** as `NON_GO`; mandatory cleanup if any portfolio state was mutated |
| Portfolio identity selection returns zero or multiple demo-portfolio matches (on any read, including retry re-observations) | **STOP** as `NON_GO`; mandatory cleanup if setup already ran |
| Reset never produces genuine `HTTP 200` within 3 total attempts | **STOP** as `NON_GO`; mandatory cleanup |
| Reset response or post-reset identity-checked read does not match Task 4.4a golden state | **STOP** as `NON_GO`; mandatory cleanup |
| Cleanup returns non-200 or identity check fails | **STOP** as `NON_GO`; record incident; do not claim restoration or Wave 9 completion |
| Final serving comparison shows drift from Phase 1 | **STOP** as `NON_GO`; do not claim Wave 9 complete |
| All conditions pass and independent review accepts the packet | Wave 9 / Step A may be recorded as complete; Wave 10.2 remains closed; Step B (flag change + frontend deploy + browser smoke) is a separate owner decision |

---

## What this attempt does NOT authorize

- Enabling `ENABLE_ASSET_PICKER` or `ENABLE_DEMO_RESET_CONTROL`
- Any Azure frontend build or deployment
- Any real-browser smoke test (Step B)
- Any PR creation, push, or merge
- Any rollback of the deployed serving state
- Expansion to other user accounts, portfolios, or endpoints
- Any action against the B1/Spec-A E2E portfolio (`00000000-0000-0000-0000-000000000e2e`)
- Wave 10.2 Step B or any advance past the Step A gate

Even a Wave 9 / Step A GO leaves Wave 10.2 closed. A subsequent separate owner
decision must authorize the pre-Step-B backend-route verification confirmation,
both flags set together, a new flag-bearing Azure frontend build/deploy, and the
real-browser post-deploy smoke.

---

## Checklist

- [x] Authoritative records read: master plan, tasks.md Wave 9/10 sections, Wave
      10.2 audit, Task 4.9 evidence, Task 8.9 Decision 2 evidence, deployment
      provenance, Azure timeout ratification
- [x] Kickoff note (`2026-09-16-claude-wave9-production-e2e-serving-kickoff.md`)
      not superseded; `main@ccbc1247` confirmed
- [x] Assigned worktree clean at `main@ccbc1247` on branch `claude/wave9-production-e2e`
- [x] Rev 1 attempt sheet reviewed by owner; five correction points received
- [x] Rev 2 attempt sheet prepared (this document); no production action taken
- [ ] Wave 9 Step A execute script authored and independently reviewed
- [ ] Owner authorization obtained — must name "Wave 9 Step A" or "Wave 9
      Production E2E" explicitly
- [ ] Execution window and operator confirmed
- [ ] Evidence packet produced under `docs/evidence/b2-wave-9/`
- [ ] Independent technical review of evidence packet requested
- [ ] Status report returned: Wave 9 outcome, review, documentation, PR, merge,
      and Step B exposure decision recorded separately
