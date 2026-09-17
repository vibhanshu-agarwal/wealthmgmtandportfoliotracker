# Wave 9 Lane — Wave 10.2 Step A Backend-Route Verification
# Sanitized Attempt Sheet (rev 10)

> **OWNER APPROVAL REQUIRED — two stages (see authorization section below).**
> Stage 1 (approve now): authorize authoring three deliverables —
> (1) Step A execute script + tests; (2) wrapper scrub-list edit + tests;
> (3) PS 5.1 launcher + offline secret test — planning and coding only; no
> production, cloud, or credential action.
> Stage 2 (future): once all three Stage 1 deliverables are authored,
> independently reviewed, merged, and the baseline commit re-pinned, fresh
> owner authorization naming "Wave 9 Step A" is required before any execution.
> Earlier Task 4.9, Task 8.9, deployment, documentation, or merge approvals
> are NOT reusable authority for either stage.

> **Rev 10 — 2026-09-17:** Fable eighth review of rev 9 returned REJECT (narrow
> — M1/M2 fully confirmed; 0 Critical, 0 Important, 1 required Minor).
> Changes applied:
> - M-R1 (required): STOP/GO deliverables-gate row — added "to the merge commit"
>   (was present in Stage 2 body and checklist but missing from the gate row)
> - R1 (recommended): "blocked until that script exists" → names all three
>   deliverables, merge, and re-pin
> - R2 (recommended): section header "(pending script availability)" →
>   "(pending all three Stage 1 deliverables)"
> - R3 (recommended): deliverable 1 "Phase 3 bounded sequence" →
>   "Phase 3-4 bounded sequence (execute, validate, cleanup)"
>
> **Rev 9 — 2026-09-17:** Fable seventh review of rev 8 returned REJECT (narrow
> — all round-6 items verified; 0 Critical, 0 Important, 3 required Minors).
> Changes applied (required only; no new content introduced):
> - M1: Docker precondition — attribute pulls to the verifier, not the wrapper
>   ("wrapper checks daemon OS; verifier pulls for ACR rehearsal")
> - M2: Stage 1 section header, consequence texts, Stage 2 blocked-until clause,
>   and STOP/GO Stage-1-auth row all updated from "script" to "deliverables"
> - M3: STOP/GO deliverables-gate row now includes "merged, and the baseline
>   commit re-pinned to the merge commit"
>
> **Rev 8 — 2026-09-17:** Fable sixth review of rev 7 returned REJECT (narrow
> — all round-5 items verified against source; 0 Critical, 1 Important, 2
> required Minors). Changes applied:
> - I1: updated authorization banner Stage 1 and Stage 2 text to name all
>   three deliverables and baseline re-pin
> - M1: launcher offline test spec strengthened with positive control (stub
>   child shows sentinel present), injection seams, and negative assertion
> - M2: "citing" → "extending" — adds new name to argv regex at :328 and a
>   third sentinel at :333-334
> - R1 (recommended): added checklist line for Stage 1 PR/merge + baseline re-pin
> - R3 (recommended): attributed image pulls to the verifier (not the wrapper)
> - R4 (recommended): renamed "stability read" → "baseline read" in cap row
> - R5 (recommended): added non-200/409 reset outcome classification
> - R6 (recommended): Phase 1 header "(before credentials or mutation)" →
>   "(before any credential use or mutation)"
>
> **Rev 7 — 2026-09-17:** Fable fifth review of rev 6 returned REJECT (narrow
> — all round-4 items verified against source; 0 Critical, 0 Important, 3
> required Minors). Changes applied:
> - M1: replaced stale "evidence row 401" self-reference with artifact name
> - M2: replaced "stable/advancing" consequence 3 with the operational test
>   (absent/null/non-integer OR ≥ 165 s); stated "no stability re-read";
>   added version-guard backstop
> - M3: Stage 1 scope now enumerates 3 deliverables (execute script; wrapper
>   scrub edit + tests; launcher + offline secret test); Stage 2 precondition,
>   STOP/GO row, and checklist updated accordingly; "defined in Stage 1" →
>   "must define and test in Stage 1"
> - R4 (recommended): Stage 2 precondition notes baseline re-pin
> - R5 (recommended): "(fail-open: ... deadline fired)" → "may have fired"
>
> **Rev 6 — 2026-09-17:** Fable fourth review of rev 5 returned REJECT (narrow
> — all round-3 items verified against source; 2 new Important introduced by
> credential-injection edit, 1 required Minor). Changes applied:
> - I1: removed unexecutable `env.ps1` example and argv/history hazard; replaced
>   with PS 5.1-valid mechanism constraints (capture before Phase 2 via
>   `Read-Host -AsSecureString`; inject at child-launch via
>   `ProcessStartInfo`; "never on a command line" as a hard rule)
> - I2: added wrapper scrub-list edit to Stage 1 authorization scope in both
>   the banner and the Stage 1 section (naming touch points: scrub 188-191,
>   restore 1092-1093, test coverage)
> - M1: defined "unstable or uncertain" operationally: version absent/null/
>   non-integer OR login round-trip ≥ 165 s; added backstop note (version guard
>   at HoldingReplacementService.java:165)
> - R1 (recommended): qualified login_timeout claim as "in preflight mode"
> - R2 (recommended): "renders unconditionally" → "for any authenticated
>   session (no flag gate)"
> - R3 (recommended): "picker/reset flags" → "`ENABLE_ASSET_PICKER` alone"
>
> **Rev 5 — 2026-09-17:** Fable third review of rev 4 returned REJECT (narrow
> — all round-2 corrections confirmed addressed; 2 new Important introduced by
> recommended edits, 2 required Minors). Changes applied:
> - Fixed lines 74-79: qualified "structurally impossible" claim — 9.1-9.4 are
>   behind picker/reset flags; Task 9.5 freshness wire
>   (`PortfolioPageContent.tsx:116`) renders unconditionally (no flag gate);
>   only a complete five-route Wave 9 Production E2E is impossible before Step B
> - Fixed line 311: removed the "adds at most 1 more" clause; cap ≤ 4 equals
>   the enumerated worst case; an unstable post-login version is a STOP
>   (no additional stability re-read allowed)
> - Fixed lines 287-290: credential must be injected only into the Step A child
>   process, not the shared wrapper shell (wrapper scrubs only
>   `TASK8_9_ACCESS_TOKEN` and `TASK8_9_DEMO_PASSWORD`); Stage 1 must add the
>   Step A var name to the wrapper's scrub list
> - Fixed line 373: replaced "225s confirmation included" with wrapper-literal
>   citation (`run_task_8_9_preflight.ps1:1086`, test pin line 314); the
>   verifier does not record login_timeout in its output
> - Fixed title and checklist: corrected revision number from "rev 3" to "rev 5";
>   added round-3 Fable review entry
>
> **Rev 4 — 2026-09-17:** Fable second review of rev 3 returned REJECT (narrow
> — all 10 round-1 corrections confirmed addressed; 2 new Important, 2 required
> Minor, 8 recommended Minor). Changes applied:
> - N-I1: raised Phase 3 read cap from ≤3 to ≤4 with enumerated read sequence;
>   added cleanup observation read row to Phase 4
> - N-I2: corrected post-reset version attribution — oracle defines holdings only;
>   rule is B1 contract `version + 1` (`HoldingReplacementService.java:163`)
> - N-M1: "back to `false` (explicitly `false`, not unset)" in rollback wording
> - N-M2: dropped "Container App reader role"; listed attempt-4 RBAC operations
>   explicitly (exec requires more than Reader)
> - N-M5 (recommended): ~300s idle-out cited as planning figure, not fact;
>   added single-launch + pre-pull recommendation
> - N-M7 (recommended): Wave 9 wires paragraph reworded — served-bundle state
>   not established here; PR #231/#232 dependency cited
> - N-M8 (recommended): consequences of approving/declining each stage added
> - N-M9 (recommended): cleanup on success path noted as no-op 200 per B1
>   contract; does not count as gate-earning reset 200
> - N-M10 (recommended): Step A GO bound to identities; must re-run if either
>   service redeploys before Step B
>
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
endpoints. Whether the currently served frontend bundle contains those wires is
not established here; it depends on when the last frontend deploy ran relative
to PRs #231/#232. Routes 9.1-9.4 are reachable only behind `ENABLE_ASSET_PICKER`
and are structurally unverifiable in a browser before Step B. Task 9.5's
freshness status wire (`PortfolioPageContent.tsx:116`) renders for any
authenticated session (no flag gate), so 9.5 alone is verifiable before Step B
— but verifying 9.5 in isolation cannot satisfy condition 5 for all five routes. A complete real-browser
Wave 9 Production E2E covering all five routes is therefore structurally
impossible until Step B. This creates a tension between:
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
**Execution is blocked until that script — with the other two Stage 1 deliverables — exists, is reviewed, and is merged with the baseline re-pinned (see Authorization, Stage 2).**
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

### Stage 1 (approve to proceed): author the three Stage 1 deliverables

Authorize Claude to write three deliverables (planning and coding only; no
production action, credential access, cloud call, deployment, PR creation, or
merge occurs in Stage 1):

1. **Step A execute script and its tests** — implements the Phase 3-4 bounded
   sequence (execute, validate, cleanup) and produces the sanitized evidence JSON.
2. **Wrapper scrub-block edit** — add the Step A credential variable name to
   `run_task_8_9_preflight.ps1`'s null-out block (lines 188-191) and restore
   block (1092-1093), with new offline test coverage extending the argv-name
   regex at `test_run_task_8_9_preflight.ps1:328` (add the new name to the
   `TASK8_9_ACCESS_TOKEN|TASK8_9_DEMO_PASSWORD` pattern) and adding a third
   sentinel at lines 333-334 (the existing sentinel assertion at 346-348 then
   covers it).
3. **Launcher** — the PS 5.1 script that captures the credential via
   `Read-Host -AsSecureString` before Phase 2 (outside the handoff window),
   then on wrapper exit 0 starts the Step A child via
   `[System.Diagnostics.ProcessStartInfo]` (`.EnvironmentVariables[<name>]`,
   `UseShellExecute = $false`); the launcher must expose test-only injection
   seams for the secret source and the child command (mirroring the wrapper's
   `-AzCommand`-style stubs, with live defaults of `Read-Host -AsSecureString`
   and the real Step A command); the offline test must (a) inject a sentinel
   value — not a real credential — via the seam, (b) verify the sentinel is
   present in the child's environment (stub child using the wrapper's
   `<tool>-env` pattern, `test_run_task_8_9_preflight.ps1:340-348`), and (c)
   verify the sentinel never appears in argv or the parent `$env:`.

Also: the corresponding attempt-sheet update with the exact execute invocation.

**Consequence of approving Stage 1:** the deliverables are authored and reviewed;
the attempt sheet is updated with the exact execute invocation; Stage 2 can then
be separately requested.
**Consequence of declining Stage 1:** the deliverables are not written; the attempt
sheet remains in "execution blocked" state; Wave 10.2 Step A cannot proceed
under any path until all three deliverables are otherwise provided.

### Stage 2 (separate future approval required): execution attempt

**Not requested here.** Once all three Stage 1 deliverables (Step A execute
script, wrapper scrub edit, and launcher) are authored, independently reviewed,
this sheet is updated with the exact invocation, and the baseline commit is
re-pinned to the post-Stage-1 merged commit, a fresh owner authorization
explicitly naming "Wave 9 Step A" is required before any execution.

**Consequence of approving Stage 2:** the operator runs the bounded sequence in
the specified UTC window; Wave 10.2 Step A evidence is collected; the
condition-5 question is surfaced for owner decision.
**Consequence of declining Stage 2:** no production action occurs; Step A
evidence is not collected; Wave 10.2 remains closed on Step A. The staged breakdown below (Phases
1-5) describes what Stage 2 will do but cannot be approved until all three Stage 1
deliverables exist.

---

## Attempt parameters (Stage 2 — pending all three Stage 1 deliverables)

| Field | Value |
|---|---|
| Proposed operator | Owner (owner-operated credential injection only) |
| Baseline commit | `main@e9801aef6565ce6fa9dc81b24506b13b419e003f` (PR #283 merge commit, 2026-09-17) |
| UTC window | **TBD — owner to specify at Stage 2 approval** |
| Hard stop if window expires | Yes — stop before any in-progress mutation; restore if setup already ran |
| Phase 1-2 serving comparison / preflight | `scripts/run_task_8_9_preflight.ps1` + `verify_demo_reset_azure.py --mode preflight` with Azure-attested timing params and login timeout (see Phase 1-2 below) |
| Phase 3 execute script | `scripts/verify_wave9_step_a.py` (merged via PR #283 `e9801aef`; baseline re-pinned) |
| Phase 3 launcher (exact invocation) | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\launch_wave9_step_a.ps1` — prompts `Read-Host -AsSecureString`, runs `run_task_8_9_preflight.ps1`, then launches `verify_wave9_step_a.py` via `ProcessStartInfo` with the credential injected only into the child's environment; evidence JSON written to `docs/evidence/b2-wave-9/` |
| Credential env vars for Phase 3 | Owner supplies the demo password interactively via the launcher's `Read-Host` prompt; the credential is stored as `WAVE9_STEP_A_PASSWORD` **in the child process env only** (never in `$env:` of the launcher or wrapper); `TASK8_9_ACCESS_TOKEN` is not used — Step A authenticates and uses the returned JWT; Claude never reads, prints, or records credential values |
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
- **Linux Docker daemon running and accessible** — the wrapper checks the daemon
  OS (`docker version`); the preflight verifier then pulls both attested
  digest-qualified images as an ACR pull-access rehearsal (`operations[10]/[11]`
  in attempt 4)
- **Azure session active with required RBAC** — subscription, resource group
  `wealth-azure-prod-rg`, workspace `wealth-prod-la`, registry `wealthprodacr`,
  and the RBAC required to run the 2026-09-14 attempt-4 preflight operations:
  `az account show`, `az containerapp show` / `revision show` / `revision list` /
  `replica list`, `az containerapp exec` (exec runs post-wake on a running
  replica — an identity provisioned only as Reader passes all pre-wake checks
  but fails at exec), `az acr manifest show-metadata`, `az acr login`,
  `az monitor log-analytics workspace show` / `query`
- **Log Analytics workspace readable** — the preflight RBAC rehearsal issues
  a KQL probe; this requires read access to workspace `wealth-prod-la`
- **Network access to `--noproxy '*'`** — probes use `--noproxy '*'`
- **Both `TASK8_9_*` env vars cleared from the parent shell** — the wrapper
  reads and scrubs them from its children; do not leave stale values

---

## Operation cap

### Phase 1 — Read-only serving comparison (before any credential use or mutation)

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

The gateway replica idles out after the last inbound request. The wrapper
DESCRIPTION records approximately 300 seconds (unverified planning figure;
`run-a-attempt-20260915.md` found the replica still Running 558 s after probe 2).
Phase 3 (the execute sequence) must begin within that window from Phase 2's
first `200` probe. If the execute invocation cannot start within the window,
the attempt must stop and treat the warm replica as consumed; a fresh Phase 2
activation requires a new owner decision.

**Strongly recommended:** pre-pull both attested digest-qualified images before
Phase 2 so the verifier's image-pull operations (`operations[10]`/`[11]` in
attempt 4) do not consume time inside the window. (The wrapper itself runs only
`docker version --format '{{.Server.Os}}'`; the pulls are the verifier's.)
Structure the sequence so the wrapper's exit 0 triggers the Step A script
launch. The Step A credential env var must be injected **only** into the Step A
child process's environment. **Hard rule: never place the secret value on a
command line** — argv is visible via `Win32_Process.CommandLine`, is persisted
to PSReadLine history, and would be captured by the operator transcript
(the `wave9-step-a-operator-transcript-<date>.txt` evidence artifact); the wrapper test at line 325-329 fails even a credential
name in argv. Never put the value in the parent shell's `$env:` before the
wrapper runs. The PS 5.1-valid mechanism must be defined and tested in Stage 1 (launcher deliverable); requirements
are: capture the secret before Phase 2 begins (outside the handoff window, via
`Read-Host -AsSecureString` or equivalent owner-operated path); inject at
child-launch time via `[System.Diagnostics.ProcessStartInfo]` (set
`.EnvironmentVariables[<name>]`, `UseShellExecute = $false`). The wrapper
scrubs only `TASK8_9_ACCESS_TOKEN` and `TASK8_9_DEMO_PASSWORD` (lines 188-191,
restore 1092-1093); Stage 1 must add the Step A variable name to that scrub
block. Avoid the manual hand-paste pattern that caused Run A attempt 1's
failure. Record both the first-200-probe
UTC timestamp and the login-start UTC timestamp in the evidence ledger.

### Phase 3 — Execute sequence (Wave 9 Step A verifier — to be authored in Stage 1)

**Before writing any operation — login consequences to bind:**
1. The demo login synchronously triggers the Wave 8 login-reset orchestration
   (`overallTimeout = 165s` on Azure). The Step A script's login HTTP timeout
   must exceed 165s + headroom; bind at least 225s.
2. If the demo portfolio was idle ≥ 30 minutes at login time, the
   login-triggered orchestration may reset the portfolio and advance `version`
   before the login response returns. The pre-write identity-checked read must
   be the first portfolio read issued **after login completes**, not before.
3. After login and before the non-golden write, verify the identity-checked
   `GET /api/portfolio` result yields a non-null integer `version`. Stop (do
   not write) if the version is absent, null, or non-integer, OR if the login
   round-trip took ≥ 165 s — no stability re-read is permitted; the
   composition write's version guard (`HoldingReplacementService.java:165`,
   `WHERE id = ? AND version = ?`) closes any late-landing login reset.

| Operation class | Cap |
|---|---|
| Authentication (demo login) | Exactly 1 call; require `HTTP 200`; login HTTP timeout ≥ 225s; JWT returned — never printed or recorded |
| Non-golden composition write (`PUT /api/portfolio/holdings`) | Exactly 1 call using Task 4.4a's oracle-derived non-golden composition; require `HTTP 200` and strictly advanced version; an already-golden or no-op result is a hard stop |
| Identity-checked portfolio read (`GET /api/portfolio`) — Phase 3 only | ≤ 4 total: (1) post-login pre-write baseline read (required after login completion per I2 consequence 2; establishes version after any login-triggered orchestration), (2) pre-reset read after the composition write (required — the post-login read is stale after the write; each reset attempt must use the version from the immediately preceding read), (3) re-observation before reset attempt 2 (on `409`), (4) re-observation before reset attempt 3 (on `409`); if the post-login version is absent, null, or non-integer, OR the login round-trip took ≥ 165 s (fail-open: the overall deadline may have fired; the portfolio may still be in-flight), the attempt stops (STOP/GO matrix) — no stability re-read is permitted; the composition write's version guard (`HoldingReplacementService.java:165`, `WHERE id = ? AND version = ?`) closes any late-landing login reset; exactly one match on `userId == DEMO_USER_ID` required on every single read including re-observations; zero or multiple matches fail the attempt immediately |
| Demo-reset call (`PUT /api/portfolio/demo-reset`) | ≤ 3 total attempts; each uses the exact `version` from the immediately preceding identity-checked read; a genuine `HTTP 200` is required — a `409` result does not satisfy the gate; any other outcome (429, 5xx, timeout) stops the attempt immediately as `NON_GO` with mandatory cleanup |
| **Cleanup max attempts = 1 (script literal, not a CLI flag)** | The script MUST enforce `cleanup_max_attempts = 1` as a hardcoded constant; a cleanup conflict (`409`) is `NON_GO` and must not silently retry |
| Total mutating calls (login + composition write + reset attempts + cleanup) | ≤ 6 |

### Phase 4 — Validation and mandatory cleanup

| Operation class | Cap |
|---|---|
| Validate reset response | Response holdings must equal Task 4.4a's independently derived exact golden set (the oracle defines the holdings set only, not the version); response `version` must equal pre-reset observed `version` + 1 per B1's contract (`HoldingReplacementService.java:163` `SET version = version + 1`; Task 4.5: a changed-tuple reset returns `version + 1`; an already-golden no-op `200` returns `version` unchanged); the Step A script must assert this exact computed expected value |
| Post-reset identity-checked read | Exactly 1 `GET /api/portfolio` with userId identity check; confirms golden state persisted; a failure here is `NON_GO` |
| Cleanup observation read (`GET /api/portfolio`) | Exactly 1 identity-checked read before the cleanup reset call; counted separately from Phase 3 reads; must yield exactly one match on `userId == DEMO_USER_ID` |
| Cleanup reset call | Armed before the non-golden composition write; executes unconditionally on every path after setup; uses `PUT /api/portfolio/demo-reset` with the then-current identity-checked observed version and real JWT; `cleanup_max_attempts = 1` (script literal); on the success path (portfolio already golden from the validated reset), this call returns `200` as a no-op (`version` unchanged per B1's contract — already-golden matching-version is a no-op); this no-op `200` does NOT count as the gate-earning genuine reset `200` |
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
| `wave9-step-a-preflight-<date>.json` | Sanitized copy of preflight output imported from out-of-repo path; timing params confirmed via wrapper literal `run_task_8_9_preflight.ps1:1086` (passes `--login-timeout-seconds 225`) and test pin `scripts/tests/test_run_task_8_9_preflight.ps1:314` at the recorded commit; note: in preflight mode (no login op is issued) the verifier does not record login_timeout; in execute mode it would appear as `timeoutSeconds` on the login entry |
| `wave9-step-a-decision-<date>.md` | Evidence summary, serving comparison with provenance chain, operation ledger (identity checks, status codes, version progression, bounded retry count, golden-state assertions, cleanup result), gate map (Step A stated as Wave 10.2 Go-action Step A; condition-5 question surfaced but not resolved), and independent-review request |

No secret values, JWT-like patterns, `Authorization` header values, or
`.env.secrets` content may appear in any tracked artifact.

---

## STOP / GO matrix

| Condition | Required result |
|---|---|
| No fresh, exact owner authorization for Stage 1 (authoring three deliverables) | **STOP** — do not author the deliverables |
| Stage 1 deliverables (Step A execute script, wrapper scrub edit, launcher) not all authored, tested, independently reviewed, merged, and the baseline commit re-pinned to the merge commit | **STOP** — execution (Stage 2) is blocked |
| No fresh, exact owner authorization for Stage 2 naming "Wave 9 Step A" | **STOP** before any live action |
| Operator preconditions not met (PS 5.1, Docker, Azure session, Log Analytics, network) | **STOP** before Phase 1 |
| Serving identity or configuration mismatch vs provenance (including 120s/30s/165s timeout values) | **STOP** before any mutation; no workaround or deployment |
| Activation — first 200 not achieved within 6 probes | **STOP** (exit 3); do not proceed to Phase 3 |
| Preflight fails or Azure-attested timeout values do not match | **STOP**; do not proceed to Phase 3 |
| Phase 2 → Phase 3 handoff exceeds ~300s idle-out window | **STOP**; treat warm replica as consumed; fresh Phase 2 requires new owner decision |
| Post-login version absent, null, or non-integer; OR login round-trip ≥ 165 s (fail-open: the overall deadline may have fired) | **STOP** before composition write; do not write |
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

A Step A GO does not expose the picker. **Step A GO is also bound to the
recorded serving identities (0081/0096): if either service is redeployed before
Step B runs, Step A must be re-run against the new identities before Step B
may proceed.** Step B requires a separate owner decision: pre-Step-B
backend-route confirmation, both repository flags set to `true` together in one
change, a new Azure frontend build/deploy, and the real-browser post-deploy
smoke. If the Step B smoke fails, rollback is both
flags back to `false` (explicitly `false`, not unset — so an organization-level
variable cannot become the effective value), another complete frontend
build/deploy, and fresh uncached browser proof that both controls are absent.

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
- [x] Rev 3 prepared; all required and recommended Fable round-1 corrections
      applied; no production action taken
- [x] Rev 3 reviewed by Fable; REJECT (narrow) — all round-1 corrections
      confirmed; 2 new Important, 2 required Minors, 8 recommended Minors
- [x] Rev 4 prepared; all required round-2 corrections applied; no production
      action taken
- [x] Rev 4 reviewed by Fable; REJECT (narrow) — all round-2 corrections
      confirmed; 2 new Important (introduced by recommended edits), 2 required
      Minors
- [x] Rev 5 prepared; all required round-3 corrections applied; no production
      action taken
- [x] Rev 5 reviewed by Fable; REJECT (narrow) — all round-3 items verified
      against source; 2 new Important (I1: argv/history hazard in credential
      example; I2: wrapper scrub edit not in Stage 1 scope), 1 required Minor
- [x] Rev 6 prepared; all required round-4 corrections applied; no production
      action taken
- [x] Rev 6 reviewed by Fable; REJECT (narrow) — all round-4 items verified;
      3 required Minors (M1: stale line-ref; M2: consequence 3 unstable wording;
      M3: Stage 1 scope missing launcher deliverable)
- [x] Rev 7 prepared; all required round-5 corrections applied; no production
      action taken
- [x] Rev 7 reviewed by Fable; REJECT (narrow) — all round-5 items verified;
      0 Critical, 1 Important (banner still missing launcher), 2 required Minors
- [x] Rev 8 prepared; all required round-6 corrections applied; no production
      action taken
- [x] Rev 8 reviewed by Fable; REJECT (narrow) — all round-6 items verified;
      0 Critical, 0 Important, 3 required Minors (M1: Docker precondition
      misattributed; M2: Stage 1 header/consequences still said "script";
      M3: STOP/GO deliverables gate missing merge + baseline re-pin)
- [x] Rev 9 prepared; all required round-7 corrections applied; no production
      action taken
- [x] Rev 9 reviewed by Fable; REJECT (narrow) — M1/M2 fully confirmed;
      1 required Minor (M-R1: STOP/GO row missing "to the merge commit")
- [x] Rev 10 prepared (this document); all corrections applied; no production
      action taken
- [x] **Stage 1: Owner authorization (names all three deliverables)**
      — authorized 2026-09-17 (chat prompt names all three deliverables,
      no production/cloud/credential/deployment/PR/merge)
- [x] All three Stage 1 deliverables authored, tested, independently reviewed:
      (1) Step A execute script (`scripts/verify_wave9_step_a.py`) — authored;
      (2) wrapper scrub edit + tests (`run_task_8_9_preflight.ps1`, stubs,
          `test_run_task_8_9_preflight.ps1`) — authored;
      (3) launcher + offline secret test (`scripts/launch_wave9_step_a.ps1`,
          `scripts/tests/test_launch_wave9_step_a.ps1`) — authored;
      Fable ACCEPT at `2041579c` (Rd 5); two subsequent CI-fix commits
      (`40e9f7d` preflight scrub reverted then `c4e08568` both re-added
      with allowlist fixes) plus one doc commit (`e8283fea`) are on the
      same PR. Independent merge review bound to exact head `95363bd0`
      (0C/0I/5M); substantive code analysis focused on the
      `40e9f7d → c4e08568` scrub/allowlist delta. CI green at `95363bd0`.
- [x] Stage 1 deliverables PR'd and merged — PR #283 merged to main at
      `e9801aef6565ce6fa9dc81b24506b13b419e003f` (2026-09-17); Fable ACCEPT
      bound to `95363bd0` (0C/0I/5M); baseline re-pinned to merge commit
- [x] This attempt sheet updated with exact execute invocation
      (see "Phase 3 launcher" row in Attempt parameters table)
- [ ] **Stage 2: Owner authorization naming "Wave 9 Step A" execution**
- [ ] Execution window and operator confirmed
- [ ] Evidence packet produced under `docs/evidence/b2-wave-9/`
- [ ] Independent technical review of evidence packet requested and accepted
- [ ] Status report: Step A outcome, condition-5 question surfaced to owner,
      documentation, PR, merge, and Step B each recorded as separate decisions
