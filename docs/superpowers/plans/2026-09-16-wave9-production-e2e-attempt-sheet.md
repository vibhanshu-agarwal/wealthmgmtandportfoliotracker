# Wave 9 Production E2E — Sanitized Attempt Sheet

> **OWNER APPROVAL REQUIRED. This sheet is NOT executable without an explicit,
> fresh owner authorization that names this Wave 9 attempt. Earlier Task 4.9,
> Task 8.9, deployment, documentation, or merge approvals are NOT reusable
> authority here.**

## Authorization request

**Decision requested:** authorize one bounded Wave 9 Production E2E /
serving-proof attempt per the scope below. The owner must operate or inject
credentials. Claude must not print, persist, transmit, or place passwords,
tokens, Authorization headers, or secret values in tracked evidence.

**Consequences:**
- **Approved:** the sequence below may run in the specified window; both
  production feature flags remain disabled throughout; no deployment, PR,
  merge, flag change, browser smoke, or scope expansion occurs.
- **Not approved / scope differs:** stop before any cloud, credential, or
  production action; Wave 9 and Wave 10.2 remain closed.

---

## Attempt parameters

| Field | Value |
|---|---|
| Proposed operator | Owner (owner-operated credential injection only) |
| Baseline commit | `main@ccbc12472d9860f05c0858c3e22b58bf5fd5da79` |
| UTC window | **TBD — owner to specify** |
| Hard stop if window expires | Yes — stop before any in-progress mutation step; restore if setup already ran |
| Verifier executable | `scripts/verify_demo_reset_azure.py` |
| Preflight wrapper | `scripts/run_task_8_9_preflight.ps1` (activation + read-only preflight) |
| Credential env vars | Owner injects `TASK8_9_ACCESS_TOKEN` and `TASK8_9_DEMO_PASSWORD`; Claude never reads, prints, or records their values |
| Demo email | `demo@wealthtracker.dev` (intentionally public, in `ci-verification.yml`) |
| Deployment provenance file | `docs/evidence/b2-task-8-9/deployment-provenance-20260911.json` |

---

## Expected reference identities (from Task 8.9 accepted provenance)

| Service | Revision | Digest |
|---|---|---|
| API gateway | `api-gateway--0000081` | `sha256:090ad3ba4b7ada20bca71781d4a1ce3ac81029b8a23a8a1d180dce2cff540d09` |
| Portfolio service | `portfolio-service--0000096` | `sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126` |

Any mismatch between the live serving state and these identities is a **hard
stop** before any mutation.

---

## Operation cap

### Phase 1 — Read-only serving comparison (before any credential or mutation)

| Operation class | Cap |
|---|---|
| Azure CLI serving state queries (revision, traffic, health, digest, config, flag values) | ≤ 15 total non-mutating calls |
| Required result | Both identities match provenance; `ENABLE_ASSET_PICKER=false`; `ENABLE_DEMO_RESET_CONTROL=false`; no unexpected configuration drift |
| Stop if not met | Hard stop; do not proceed to activation or execution |

### Phase 2 — Activation sequence (run_task_8_9_preflight.ps1)

| Operation class | Cap |
|---|---|
| Gateway warm-up probes (GET health) | ≤ 6 probes, each ≤ 90 s, 5 s apart |
| Required first-200 | At least one probe returns HTTP `200`; any other outcome is exit `3`, stop |
| Preflight verifier (mode=preflight) | Exactly 1 invocation after the first `200` probe |
| Required result | Preflight passes; `go:null` or `preflight_passed` in output |
| Stop if not met | Hard stop; no execute mode run |

### Phase 3 — Execute sequence (mode=execute, owner-operated credentials)

| Operation class | Cap |
|---|---|
| Authentication (login) | Exactly 1 call; `HTTP 200`; token never printed or recorded |
| Non-golden composition write (`PUT /api/portfolio/holdings`) | Exactly 1 call; require `HTTP 200` and strictly advanced version; an already-golden or no-op result is a hard stop |
| Identity-checked portfolio read (`GET /api/portfolio`) | ≤ 3 calls total across setup and retries; exactly one match on `userId == DEMO_USER_ID` required on every read |
| Demo-reset call (`PUT /api/portfolio/demo-reset`) | ≤ 3 total attempts; each must use the exact observed version from the immediately preceding identity-checked read |
| Total mutating calls (auth + write + reset attempts) | ≤ 5 |
| Required reset result | At least one genuine `HTTP 200` within 3 attempts; a `409` may trigger one re-observe + retry within the cap |

### Phase 4 — Validation and mandatory cleanup

| Operation class | Cap |
|---|---|
| Validate reset response | Response holdings must equal Task 4.4a's independently derived exact golden set; version must have advanced past the non-golden write version |
| Post-reset identity-checked read | Exactly 1 `GET /api/portfolio` with userId identity check; confirms golden state persisted |
| Cleanup (restore golden state) | Armed before the non-golden write; executes on every path after setup; uses the same demo-reset endpoint with the then-current observed version |
| Post-cleanup identity-checked read | Exactly 1 `GET /api/portfolio` with userId identity check |
| Cleanup success requirement | `HTTP 200` on first cleanup attempt; no forgiven conflict; a `409` or failure is `NON_GO` and terminates the attempt |

### Phase 5 — Final serving revalidation

| Operation class | Cap |
|---|---|
| Serving identity + flag re-check (identical query set as Phase 1) | ≤ 10 non-mutating Azure CLI calls |
| Required result | Same identities and disabled flags as Phase 1 |
| Stop if not met | Record as `NON_GO`; do not claim Wave 9 complete |

---

## Retry cap

| Retry type | Cap |
|---|---|
| Total demo-reset attempts (setup observation + reset call, all combined) | ≤ 3 |
| Cleanup retries | 0 — cleanup must succeed on the first attempt |
| Serving comparison retries | None beyond the stated call caps |

---

## Cleanup path

Cleanup is **armed before the non-golden composition write** and executes
unconditionally after setup on every path (pass, fail, or stop):

1. Obtain the current demo portfolio via identity-checked `GET /api/portfolio`.
2. Issue `PUT /api/portfolio/demo-reset` with that exact observed version and
   the real JWT.
3. Require `HTTP 200` on the first attempt.
4. Verify golden state with an identity-checked `GET /api/portfolio`.
5. Any cleanup failure is a `NON_GO` incident; record and stop — do not claim
   restoration or Wave 9 completion.

---

## Expected evidence paths

All evidence files go under `docs/evidence/b2-wave-9/` (new dated directory):

| Artifact | Content |
|---|---|
| `wave9-e2e-attempt-sheet-<date>.md` | This document (final owner-authorized form) |
| `wave9-e2e-raw-<date>.json` | Raw verifier output (sanitized; no secrets) |
| `wave9-e2e-operator-transcript-<date>.txt` | Sanitized operator transcript (host paths / identity redacted; no JWT, password, or token values) |
| `wave9-e2e-decision-<date>.md` | Evidence summary, serving comparison, operation ledger, gate map, and independent-review request |
| `wave9-e2e-preflight-<date>.json` | Preflight wrapper JSON output |

Hashes for binary-sensitive files go in the decision document. No secret
values, JWT-like patterns, `Authorization` header values, or `.env.secrets`
content may appear in any tracked artifact.

---

## STOP / GO matrix

| Condition | Required result |
|---|---|
| No fresh, exact owner authorization naming this Wave 9 attempt | **STOP** before any live action |
| Serving identity or flag mismatch vs Phase 1 reference | **STOP** before any mutation; no workaround or deployment |
| Activation — first 200 not achieved within 6 probes | **STOP** (exit 3); do not proceed to execute mode |
| Preflight fails (`preflight_passed` absent) | **STOP**; do not proceed to execute mode |
| Non-golden write does not advance version or returns non-200 | **STOP** as `NON_GO`; mandatory cleanup if any portfolio state was mutated |
| Portfolio identity selection returns zero or multiple demo-portfolio matches | **STOP** as `NON_GO`; mandatory cleanup if setup already ran |
| Reset never produces genuine `HTTP 200` within 3 total attempts | **STOP** as `NON_GO`; mandatory cleanup |
| Reset response or post-reset read does not match Task 4.4a golden state | **STOP** as `NON_GO`; mandatory cleanup |
| Cleanup returns non-200 or identity check fails | **STOP** as `NON_GO`; record incident; do not claim restoration or Wave 9 completion |
| Final serving comparison shows drift from Phase 1 | **STOP** as `NON_GO`; do not claim Wave 9 complete |
| All conditions pass and independent review accepts the packet | Wave 9 may be recorded as complete; Wave 10.2 remains closed pending a separate owner exposure decision |

---

## What this attempt does NOT authorize

- Enabling `ENABLE_ASSET_PICKER` or `ENABLE_DEMO_RESET_CONTROL`
- Any Azure frontend build or deployment
- Any browser exposure smoke test
- Any PR creation, push, or merge
- Any rollback of the deployed serving state
- Expansion to other user accounts, portfolios, or endpoints
- Any action against the B1/Spec-A E2E portfolio
  (`00000000-0000-0000-0000-000000000e2e`)
- Wave 10.2 exposure or any advance past the Wave 9 gate

Even a Wave 9 GO leaves Wave 10.2 closed. A subsequent owner decision must
separately authorize the pre-exposure backend-route verification, both flags
set together, a new flag-bearing Azure frontend build/deploy, and the
real-browser post-deploy smoke.

---

## Checklist (to be completed before owner authorization is requested)

- [x] Authoritative records read: master plan, tasks.md, Wave 10.2 audit,
      Task 4.9 evidence, Task 8.9 Decision 2 evidence, deployment provenance
- [x] Kickoff note (`2026-09-16-claude-wave9-production-e2e-serving-kickoff.md`)
      is not superseded; main is at `ccbc1247`
- [x] Assigned worktree is clean at `main@ccbc1247` on fresh branch
      `claude/wave9-production-e2e`
- [x] Attempt sheet prepared (this document); no production action taken
- [ ] Owner authorization obtained — name "Wave 9 Production E2E" explicitly
- [ ] Execution window and operator confirmed
- [ ] Evidence packet produced and independent review requested
- [ ] Status report returned: Wave 9 outcome, review, documentation, PR,
      merge, and exposure decision recorded separately
