# B2 Task 8.9 — Run A attempt 4, 2026-09-15 (preflight passed; no execute GO at this historical boundary)

> **SUPERSEDED AS A CURRENT-STATUS RECORD.** This document retains the historical narrative for the
> accepted read-only Run A preflight. The later Decision 2 execute sequence did run; its raw verifier
> result is `class_2b` / NON-GO, and the subsequently published manual recovery summary was rejected
> by independent review because it did not retain the raw query output required by Task 8.9. Current
> status is COMPLETE / GO on later Decision 2 evidence independently accepted at `8544d722`;
> the new documentation head's quick check remains pending. Current status is recorded in
> [`run-b-decision2-20260915.md`](run-b-decision2-20260915.md). This supersession does not alter any
> Run A evidence or turn this preflight into an execute proof.

This attempt ran `2026-09-14T21:33:45Z`–`2026-09-14T21:36:20Z` UTC (filenames use the local IST
date, `2026-09-15`). It follows Run A attempt 3, also run on 2026-09-14 UTC; in-repo traces include
the ratification addendum,
[`2026-09-14-azure-timeout-ratification.md`](2026-09-14-azure-timeout-ratification.md),
the Run A attempt 3 status in [the owning task plan](../../../.kiro/specs/asset-picker-composition/tasks.md),
and the corresponding status in [the master plan](../../plans/ASSET_PICKER_E2E_MASTER_PLAN.md) — no
attempt-3 record exists in this directory.

Companion records for
[`run-a-attempt4-preflight-20260915.json`](run-a-attempt4-preflight-20260915.json) and
[`run-a-attempt4-operator-transcript-20260915.txt`](run-a-attempt4-operator-transcript-20260915.txt)
(sanitized copy — see "Transcript sanitization" below).

**Historical Run A boundary: Task 8.9 was OPEN / NON-GO; preflight success was not completion.**
Current status is COMPLETE / GO on later Fable-accepted Decision 2 evidence at `8544d722`;
the new documentation head's quick check remains pending.
`go: null` is expected and is not a GO; the credential-using `execute` proof requires a separate
owner **Decision 2**; this record does not authorize it.

**†** marks a claim observed in the operating session (the assisting agent's tool output, not
captured to any file in this repository) but not captured in any committed file. Things visible in
the committed transcript or JSON are **not** †; they are cited by transcript line number or JSON
field instead.

The three files were prepared uncommitted in `claude/t89-run-a-attempt4-evidence`. After Fable's
three-round review and an independent Codex review accepted the preflight, the owner explicitly
approved their publication on 2026-09-15. That approval does not authorize merge, execute-mode
retries, feature exposure, or any other Production action.

## Authorization

A Codex-authored kickoff, "Task kickoff — B2 Task 8.9, Run A attempt 4" (held outside this
repository) **†**, required fresh explicit owner authorization for exactly one activation sequence
of at most six fixed `GET /actuator/health` Production probes, followed immediately by the
wrapper's read-only preflight — nothing beyond that.

Before the run, the assisting agent (Opus) asked the owner three structured questions; the owner's
answers **†**:

1. **Operator** — "Authorize; Claude runs it." This explicitly overrides readiness-packet §3 line
   337's "The wake and the run are **one operator sequence, performed by the authorized
   operator** — not split between people and **not performed by an agent**" for this attempt only.
2. **Post-run scope** — "Read-back + hygiene": a post-run read-only control-plane read-back
   (`containerapp show` ×2, revision list, one replica list ≥300 s later) plus `docker logout` of
   `wealthprodacr` and removal of only the two images this run pulled.
3. **Launch mechanism** — "Output-capturing version (Recommended)": the launch variant described
   below, chosen after a transcript-capture pre-test (see "Pre-run state").

The owner's fuller kickoff text arrived mid-session **after** the wrapper had already exited `0`
**†**. It adds: exit `0` ⇒ stop the Production sequence, preserve evidence, request Decision 2
separately; sanitize the transcript before sharing or committing; preserve the raw original
privately; do not commit any evidence until it is assessed and publication is separately approved.
The owner then approved both evidence publication and the separate Decision 2 execute proof on
2026-09-15. At this record's original publication boundary, the raw originals at `C:\t89\` remained
untouched and the execute proof had not yet run. The later execute sequence and its NON-GO result are
recorded separately in the superseding Decision 2 record linked above.

## Source and pre-run state

**Every bullet in this section is †**, except the repo-head/cwd line, which is also recorded at
transcript line 21.

- Repo: `origin/main` = `1f922a89643f5bb406dcdf471e8dc07a229960d6` (PR #276 merge), confirmed to
  contain PR #275's merge `8337d7e8b982245156088719d01f49b3ad1c1be9`. Fresh detached worktree;
  `git status --porcelain` empty both before and after the run.
- Windows PowerShell `5.1.26100.9444`, PSEdition `Desktop` (also recorded at transcript lines 10–11).
  Docker server `linux/amd64`, engine `29.7.2`. `az` session "Azure subscription 1"; active
  subscription id equals `target.subscriptionId` in `rehearsal-20260911.json` (local compare, never
  printed). `TASK8_9_ACCESS_TOKEN` / `TASK8_9_DEMO_PASSWORD` both unset in the parent shell.
  `C:\t89` existed; both output paths (`run-a-attempt4-preflight-20260915.json`,
  `run-a-attempt4-20260915.transcript.txt`) were absent before launch — the launch script's own
  `Test-Path` guards would otherwise have thrown before `Start-Transcript`. `curl.exe` resolved to
  `C:\WINDOWS\system32\curl.exe`; `python` `3.14.4`.
- **Transcript-capture pre-test.** With a dummy child `powershell.exe` launched the kickoff's exact
  way from this background tool process, `Start-Transcript` captured 0 of 3 child lines (`Write-Host`,
  stdout, stderr) — only the parent's exit line. The variant
  `... 2>&1 | ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { "$_" } } | Out-Host`
  with `$ErrorActionPreference='Continue'` captured all three plus grandchild stderr, and
  `$LASTEXITCODE` still reported the child's exit `3`. Wrapper arguments are unchanged by the
  variant. This is why the launch command below pipes through `ForEach-Object … | Out-Host`.
- Local image inventory at `2026-09-14T21:34:19Z` (after launch, before the verifier's pulls):
  `api-gateway@sha256:090ad3ba…` **present**, `api-gateway@sha256:aee44edc…` present,
  `portfolio-service@sha256:1cf372a3…` **present**, `portfolio-service@sha256:551fa974…` (tag
  `b1-r-c-8f1e8a36f8ba-20260908`), `portfolio-service@sha256:fa060bf0…` (tag
  `b1-r-b3r-97b83e52-20260907t145508z`). The Docker config `auths` held a
  `wealthprodacr.azurecr.io` entry before the run. Both attested images were therefore already
  local; the verifier's two pulls (0.632 s, 0.937 s per operation `durationSeconds`, JSON
  `operations[10]`/`operations[11]`) were cache hits, not fresh downloads.

## Launch mechanism

Run once, as a background PowerShell 5.1 tool process, from a clean worktree already on the
required commit:

```powershell
$ErrorActionPreference = 'Continue'
$repo = 'C:\worktrees\wealthmgmtandportfoliotracker-worktrees\t89-run-a-attempt4'
$transcriptPath = 'C:\t89\run-a-attempt4-20260915.transcript.txt'
$evidencePath = 'C:\t89\run-a-attempt4-preflight-20260915.json'
if (Test-Path -LiteralPath $transcriptPath) { throw "Transcript path already exists: $transcriptPath" }
if (Test-Path -LiteralPath $evidencePath) { throw "Evidence path already exists: $evidencePath" }
Set-Location -LiteralPath $repo
$head = git rev-parse HEAD
if ($head -ne '1f922a89643f5bb406dcdf471e8dc07a229960d6') { throw "worktree HEAD is $head, not 1f922a89" }
if (git status --porcelain) { throw 'worktree is not clean' }
$wrapperExit = $null
Start-Transcript -LiteralPath $transcriptPath -NoClobber
try {
    Write-Host "operator-start-utc=$((Get-Date).ToUniversalTime().ToString('o'))"
    Write-Host "operator=Claude (Opus 5) under owner authorization 2026-09-15; repo-head=$head; cwd=$((Get-Location).ProviderPath)"
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\run_task_8_9_preflight.ps1 -EvidenceOutput $evidencePath 2>&1 | ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { "$_" } } | Out-Host
    $wrapperExit = $LASTEXITCODE
    Write-Host "operator-end-utc=$((Get-Date).ToUniversalTime().ToString('o'))"
    Write-Host "TASK_8_9_WRAPPER_EXIT=$wrapperExit"
}
finally {
    Stop-Transcript
}
Write-Host "TASK_8_9_WRAPPER_EXIT=$wrapperExit"
if (Test-Path -LiteralPath $evidencePath) {
    Get-FileHash -LiteralPath $evidencePath -Algorithm SHA256 | Format-List
}
exit 0
```

No `-ProvenancePath` or `-OperationTimeoutSeconds` override was passed, so the wrapper's own
defaults applied (`docs/evidence/b2-task-8-9/deployment-provenance-20260911.json` and `600`
seconds respectively — `scripts/run_task_8_9_preflight.ps1` lines 148 and 152). No
`--threshold-override` was passed at any layer.

The tool's stdout after the transcript stopped **†**: `TASK_8_9_WRAPPER_EXIT=0` and SHA256
`361390C68C6AC15D9E5AAA187D2B0AF5801A499261C430A958AC9F75CE05E201` for the evidence JSON — matching
the value independently recomputed against the preserved original below.

## What happened (from the transcript and JSON)

The raw transcript (`C:\t89\run-a-attempt4-20260915.transcript.txt`) is 14,982 bytes, 443 lines,
UTF-8 with BOM, CRLF throughout (443 CRLF, 0 lone LF, file ends `...\r\n`), SHA256
`4B05EA435384006168C180D2B58164A75DF4BA1F9ABFB2F685092C1A4B8579AE` — verified against the value
recorded in the task brief before any copy was made **†**. The evidence JSON
(`C:\t89\run-a-attempt4-preflight-20260915.json`) is 10,188 bytes, SHA256
`361390C68C6AC15D9E5AAA187D2B0AF5801A499261C430A958AC9F75CE05E201`, also verified. All line/field
citations below are to the raw transcript's line numbers (identical in the sanitized copy except
for the five redacted header lines — see "Transcript sanitization").

| | |
|---|---|
| Operator start | `2026-09-14T21:33:45.0703877Z` (transcript line 20; local `2026-09-15T03:03:45`, hence the filenames' local date) |
| Repo head / cwd | `1f922a89643f5bb406dcdf471e8dc07a229960d6`; `C:\worktrees\...\t89-run-a-attempt4` (line 21) |
| Pre-wake checks | all passed: attestation `api-gateway--0000081` @ `sha256:090ad3ba…` (line 22); subscription resolved, length 36 (line 23); wake request authorized (line 24); `az` session / Docker `linux` (lines 25–28); gateway serving `--0000081` at the attested digest (lines 29–32, two `containerapp`-extension `WARNING` lines at 30–31); active subscription matches (line 33); `portfolio-service--0000096` at its attested digest (lines 34–37, two more `WARNING` lines at 35–36); workspace readable (lines 38–39); verifier starts (lines 40–41); 1 gateway revision, none newer (lines 42–43); serving timeouts / idle threshold / provider match the ratified attestation (lines 44–46) |
| Activation sequence | disclosed at line 47: at most 6 probes of `GET https://api.vibhanshu-ai-portfolio.dev/actuator/health`, each bounded by `--max-time 90`, no curl retry, no redirect, `-q` (curl config files skipped), `--noproxy '*'`; first `200` ends the sequence. The exact retry set is curl-exit `0` + HTTP `503`, or curl-exit `28` + status `000` (`run_task_8_9_preflight.ps1` lines 349 and 351); every other outcome stops the sequence. The configured inter-probe interval is `5` s (`-WakeProbeIntervalSeconds` default and required live-run value, script lines 147, 458, 467). Measured wall-clock gap: probe 1 ended at `21:34:15.9825053Z + 52.750547 s = 21:35:08.7330523Z`; probe 2 started `21:35:13.8778787Z`; gap `= 5.1448264 s` (≈5.14 s), consistent with the 5 s configured interval plus overhead |
| Probe 1/6 | line 48: started `2026-09-14T21:34:15.9825053Z`, curl-exit `0`, HTTP `503`, `52.750547 s`, `content-type: application/*+json`, 61 body bytes, body-sha256 `50957f5c…`, body-class `actuator-json`, actuator-status `OUT_OF_SERVICE` → line 49: retryable, another probe issued |
| Probe 2/6 | line 50: started `2026-09-14T21:35:13.8778787Z`, curl-exit `0`, HTTP `200`, `0.175918 s`, `content-type: application/*+json`, 49 body bytes, body-sha256 `f5d81755…`, body-class `actuator-json`, actuator-status `UP` → line 51: activation confirmed, no further probe |
| Replica wait | lines 52–54 (one more `WARNING` at 53): replica up, `api-gateway--0000081-6c469996fc-fj4pc` |
| Preflight | line 55 starts it; lines 56–434 are the evidence JSON printed to the transcript, byte-identical in content to the copied file (see below); line 435: verifier exit `0`; line 436: preflight passed; line 437: explicit reminder `preflight_passed` / `go=null` is **not** a GO |
| Operator end | `2026-09-14T21:36:20.8391952Z` (line 438) |
| Wrapper exit | `0` (line 439) |

Total health probes: **2** — one `503`, one `200`. `200` was reached on probe 2 of the allowed 6.

**Evidence JSON, field by field** (`run-a-attempt4-preflight-20260915.json`, mirrored at transcript
lines 56–434):

- `verdict`: `{go: null, status: "preflight_passed", errors: []}`.
- `preflight`: `{passed: true, rbacRehearsed: true}`.
- `operations`: 15 entries, **all** `mutating: false`, **all** `timeoutSeconds: 600.0` (the wrapper's
  unoverridden default) — `az account show`; `containerapp show` (ingress) for api-gateway;
  `revision list` for api-gateway and for portfolio-service; `log-analytics workspace show`;
  `containerapp replica list`; `containerapp exec … java -jar /probe.jar` (11.15 s); `acr manifest
  show-metadata` for both images (9.015 s, 7.883 s); `az acr login` (6.991 s); `docker pull` for
  both images (0.632 s, 0.937 s — cache hits per "Pre-run state" above); `log-analytics query`
  (`print task8_9_rbac_probe=1`); `containerapp revision show` (env); and the local oracle,
  `python … scripts\derive_demo_golden_state.py` (0.264 s).
- `requestCounts`: `{portfolioReads: 0, compositionWrites: 0, logins: 0, cleanupResets: 0}` — all
  zero.
- `cleanup`: `{armed: false, attempts: [], succeeded: false, conflictObserved: false,
  postCleanupGolden: false}`.
- `login: {}`; `observation: {}`; `events: {}`; `trace: {}`; `setup: {}`; `servingRevalidation: {}`.
- `decisions.overrideUsed: false`; `thresholdRestore: {attempted: false, required: false, verified:
  true}` — consistent with `--threshold-override` never being passed.
- `decisions.observedTimeouts`: eligibility `120s`, overall `165s`, reset `30s`, gateway response
  ceiling `150s`; `decisions.idleThreshold: "30m"`. `provider: "azure"`.
- `classification: "not_observed"`; `keyAlignment.originVerification: "not_applicable_on_azure"`,
  `internalApiKeyProven: false`, `proof: "not_observed"`.
- `serving` and `source.services` both show api-gateway `--0000081` @ `sha256:090ad3ba…` and
  portfolio-service `--0000096` @ `sha256:1cf372a3…`, matching the 2026-09-11 provenance
  (`source.services.api-gateway.evidencePath: "docs/evidence/b2-task-8-9/deployment-completion-20260911.json"`;
  portfolio-service's is carried forward from `docs/evidence/b1-r-c/task-7-9-serving-proof-20260909.json`,
  since that service has not redeployed since).
- `target.subscriptionId: "ee625b3f-7cb1-4482-be3c-4363c5d76d23"`,
  `target.workspaceCustomerId: "83a9c3a2-659e-40ca-9784-1ff871b9d5ea"` — see "Publication
  questions" for precedent.

**`decisions.wave10Eligible: true` — recorded verbatim, but this field does not authorize or
signal Wave 10 exposure.** Reading `scripts/verify_demo_reset_azure.py` (read-only): the field is
set once, at evidence initialization, before any probe or preflight check runs —
`"wave10Eligible": config.threshold_override is None` (line 352, alongside `"overrideUsed":
config.threshold_override is not None` at line 351). It is a pure echo of one CLI input — whether
`--threshold-override` was passed — not a computed readiness signal, not gated on
`preflight.passed`, and not gated on any GO decision. This run never passes
`--threshold-override` (per the "Next-run sequence" design), so this field is `true` on every
compliant Run A regardless of outcome; a `false` value would only ever appear on a threshold-
override run, which Run A's design forbids. It says nothing about whether Production is fit for
Wave 10 traffic.

**Interpretation caution on probe 1's `503`.** The wrapper's `actuator-json` classification and
`OUT_OF_SERVICE` status (line 48) are **consistent with** Spring Boot's actuator answering
`OUT_OF_SERVICE` during startup — i.e., the request reached the application and the application
itself was still coming up — **rather than** proof that the Container Apps ingress had no ready
upstream at all. Attempt 2's record could not distinguish these two causes because the wrapper
discarded the `503` body (`-o NUL`); this run's wrapper version captures the body and classifies
its shape, but a shape classification is not proof of origin. Both explanations remain consistent
with a cold-start scale-from-zero; this evidence does not, and does not claim to, distinguish them
further.

## Post-run read-backs

**Every row in this table is †**: assisting-agent tool output, not captured to any file in this
repository.

| Read (UTC) | Command (abridged) | Result |
|---|---|---|
| `21:37:33Z`–`21:37:44Z` | `az containerapp show -n api-gateway` — `latestReadyRevisionName`, `latestRevisionName`, `template.containers[0].image`, `template.scale.minReplicas` | `api-gateway--0000081`, `api-gateway--0000081`, `…/api-gateway@sha256:090ad3ba4b7ada20bca71781d4a1ce3ac81029b8a23a8a1d180dce2cff540d09`, `minReplicas` null; exit 0 |
| `21:37:33Z`–`21:37:44Z` | `az containerapp show -n portfolio-service` — `latestReadyRevisionName`, `template.containers[0].image` | `portfolio-service--0000096`, `…/portfolio-service@sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126`; exit 0 |
| `21:37:33Z`–`21:37:44Z` | `az containerapp revision list -n api-gateway --query [].name` | exit 0, count 1: `api-gateway--0000081` — no `--0000082` |
| `21:44:31.454Z`–`21:44:36.531Z` | `az containerapp replica list -n api-gateway --revision api-gateway--0000081 -o json` | exit 0, replica count **1**: `api-gateway--0000081-6c469996fc-fj4pc`, `properties.runningState: "Running"` |

The replica read was `491 s` after operator end (`21:36:20.839Z`) and `558 s` after probe 2 started
(`21:35:13.878Z`), i.e. past the 300 s planning figure from both reference points. The owner
authorized exactly **one** such read; it was not repeated.

**Return to zero replicas was not observed by this read.** Readiness-packet §1 item 3 ("The
replica count returns to zero after the 300-second scale-to-zero cool-down, confirmed by one later
live read") is therefore **not met** by this attempt — the gateway was still at one running replica
when read. The 300-second planning assumption (also flagged as an open question in the activation
policy's "What this does not determine", item E12) remains **unverified** by this run. This single
observation does not fit a strict "scaled to zero 300 s after the last probe" model, but the
evidence cannot exclude other causes — other inbound traffic to the public gateway hostname, or
platform scale-in lag beyond the 300 s planning figure — and this record does not speculate further
than that. No configuration was changed by this run (see "Credentials / no-change statements"
below), so scale-in remains fully automatic; confirming an eventual return to zero would require a
further, separately owner-authorized read. This is listed again as an open item in the checklist
mapping below.

**No pre-run check or post-run read-back issued ingress traffic (†).** The only operator-side
requests to `api.vibhanshu-ai-portfolio.dev` in this whole session were the 2 wrapper probes
recorded at transcript lines 48 and 50. The local transcript-capture pre-tests described under
"Pre-run state" used a dummy child `powershell.exe` process and issued no network request of any
kind; every pre-wake check and post-run read-back is an `az`/`docker` control-plane call, never an
HTTP request to the gateway itself.

## Hygiene

**Every bullet in this section is †.**

- `21:38:39Z`: no container, in any state, referenced either run image.
- `docker logout wealthprodacr.azurecr.io` → "Removing login credentials", exit 0; `auths`
  afterwards empty `[]`.
- `docker image rm` (no force) of `wealthprodacr.azurecr.io/api-gateway@sha256:090ad3ba…` and
  `.../portfolio-service@sha256:1cf372a3…` → both Untagged + Deleted, exit 0.
- Remaining `wealthprodacr` images, untouched: `api-gateway@aee44edc…`,
  `portfolio-service@551fa974…`, `portfolio-service@fa060bf0…`.
- Honest caveat: the two removed images, and the `wealthprodacr.azurecr.io` `auths` entry, both
  **pre-dated this run** (the images were cache hits per "Pre-run state"; the `auths` entry was
  already present before launch). The hygiene step is real but is not evidence that this run's own
  login/pull activity was fully reversed to a "nothing was ever here" baseline — that baseline
  never existed for this worktree/host.

## Credentials / no-change statements

- No application credentials were used: `TASK8_9_ACCESS_TOKEN` and `TASK8_9_DEMO_PASSWORD` were
  both unset in the parent shell before launch **†**; the evidence JSON's `login: {}` and
  `requestCounts: {portfolioReads: 0, compositionWrites: 0, logins: 0, cleanupResets: 0}` (all
  zero) confirm none were exercised.
- No feature flag, deployment, revision, or portfolio state change: every command issued was a
  health probe or a non-mutating read. This is two distinct sets of evidence: (a) the 15
  `operations[*]` entries recorded by the verifier, all `mutating: false` (listed in "Evidence
  JSON, field by field" above), and (b) the wrapper's own pre-wake checks and replica poll, which
  appear in the transcript only as `==>` step labels (lines 22–46, 52–54) and are **not** captured
  in `operations[]`. Set (b) is `scripts/run_task_8_9_preflight.ps1` at `1f922a89` (transcript line
  21) lines 579–710 (the pre-wake `az account show` / `containerapp show` / `revision list` /
  `log-analytics workspace show` / `containerapp revision show` reads, plus the Docker and verifier
  `--help` checks) and line 958 (the `containerapp replica list` poll inside the replica-wait loop)
  — every one of these is a non-mutating `show`/`list`/`version`/`--help` read, never a
  `create`/`update`/`delete`/`restart` call. The post-run revision-list read shows count 1,
  unchanged **†**. `requestCounts.compositionWrites: 0` and `cleanupResets: 0`. Scale configuration
  was not touched; activation was via inbound HTTP only (the health probes).

## Transcript sanitization

Sanitization scope, and nothing more: in the `Start-Transcript` header block, the **value** of
exactly five lines was replaced with `[REDACTED: host identity]`. Every other byte is identical —
same encoding, same line endings, no re-flowing.

- Encoding: UTF-8 with BOM in both the raw original and the sanitized copy (`EF BB BF` at byte 0).
- Line endings: CRLF throughout in both files (raw: 443 CRLF, 0 lone LF; sanitized: 443 CRLF, 0
  lone LF), file ends `...\r\n` in both.
- Byte count: raw 14,982 → sanitized 14,741 (fewer bytes because the five original values were
  longer than the fixed redaction string).
- Line count: 443 in both (line-for-line; no lines were added, removed, or reflowed).
- A full segment-by-segment comparison (split on `\r\n`) confirms **exactly 5 of 444 segments**
  differ (the 444th segment is the trailing empty string after the file's final CRLF, unchanged in
  both).

Lines changed (line numbers and field names only — the redacted values are not repeated here):

| Line | Field |
|---|---|
| 4 | `Username:` |
| 5 | `RunAs User:` |
| 7 | `Machine:` |
| 8 | `Host Application:` |
| 9 | `Process ID:` |

SHA-256 of the sanitized copy: `0F859BA5DE9311D28820965873701E81D2A1C53F80949C8741CAF230F259B7A1`.

Nothing else was touched: the embedded verifier JSON (transcript lines 56–434), the
`target.subscriptionId` / `workspaceCustomerId` GUIDs, and every local path (including the
`C:\Users\pc\AppData\Local\Python\...` interpreter path inside the oracle operation's `argv`) are
byte-identical to the raw original. This interpreter path was deliberately left untouched by
sanitization (see "Publication decision" below for why): it survives byte-for-byte at transcript
line 350 and standalone-JSON line 295, both verified directly against the files.

## Checklist mapping — readiness-packet §3 "Success looks like" and evidence checklist

**"Success looks like" (lines 389–391): all met.** `verdict.go: null`, `status: "preflight_passed"`,
`errors: []`; `preflight.passed: true`, `rbacRehearsed: true`; 15 operations, all `mutating: false`;
`requestCounts` all zero; `cleanup.armed: false`; `login: {}`. `go: null` is the expected,
non-GO result and is reported as such, not as a GO.

**Evidence checklist (lines 402–417):**

| Item | Status |
|---|---|
| `--deployment-provenance` pointed at the 2026-09-11 record, explicitly | **Met** — wrapper default `deployment-provenance-20260911.json` (script line 148) was not overridden; `source.services.api-gateway.evidencePath` in the resulting JSON cites `deployment-completion-20260911.json` |
| `--threshold-override` absent | **Met** — `decisions.overrideUsed: false`, `thresholdRestore.attempted: false` |
| wrapper `-OperationTimeoutSeconds` confirmed (default 600) | **Met** — not overridden; every `operations[*].timeoutSeconds` is `600.0` |
| Docker daemon confirmed running before the wake | **Met** — transcript lines 27–28 |
| wrapper invoked once; replica wait / verifier handoff automatic after the first 200 | **Met** — one invocation (line 21); probe 2 returns 200 (lines 50–51), replica wait (52–54) and preflight (55) follow automatically |
| live `-EvidenceOutput` unique and outside the repository; copied into `docs/evidence/` only after the wrapper exited | **Met** — `C:\t89\...json`, outside the worktree; launch script's own `Test-Path` guards enforced uniqueness; this copy was made in a separate, later session after exit 0 |
| evidence shows 15 operations, all `mutating: false` | **Met** |
| Docker logged out of the production registry and pulled images removed afterwards | **Met (†)** — see "Hygiene" |
| new evidence file carries `target.subscriptionId` / `workspaceCustomerId` GUIDs like `rehearsal-20260911.json` — confirm still intended | **Met** — precedent is confirmed and the owner approved publication after disclosure |
| serving revision/digest re-read live after the run, matching this packet | **Met (†)** — post-run read-back, both services |
| live revision-list read confirming no `--0000082` | **Met (†)** — count 1, `api-gateway--0000081` only |
| wake disclosed in the evidence: route and method, actor, timestamp, authorization | **Met, with a caveat** — route/method/timestamps are in the transcript (lines 47–51); the transcript's `operator=` line (21) names the actor and cites "owner authorization 2026-09-15"; the fuller three-question Decision detail lives only in the operating session (†), not inside the transcript itself |
| copied evidence file committed alongside the prose that cites it, hash matched to the preserved external original | **Met by this publication** — the JSON copy is byte-identical to the preserved external original and its SHA-256 is reported above |
| Task 8.9 checkbox still `- [ ]`; Wave 8 🟡; Wave 10 still gated | **Met at this historical attempt's boundary** — this Run A preflight did not complete Task 8.9 or change either flag. The later Decision 2 evidence was independently accepted by Fable at `8544d722`, making current Task 8.9 Azure live proof COMPLETE / GO and its checkbox checked. The new documentation head's quick check and separate merge decision remain pending; Wave 10 remains gated. See [the acceptance record](run-b-decision2-fable-acceptance-20260915.md), [the owning task plan](../../../.kiro/specs/asset-picker-composition/tasks.md), and [the master plan](../../plans/ASSET_PICKER_E2E_MASTER_PLAN.md). |
| independent review before any further authorization is acted on | **Met** — Fable's round-3 review returned ACCEPT with no findings of any severity, and Codex independently accepted the preflight before publication |

**§1 "Mandatory restoration and read-back" (lines 268–283), for completeness:**

| Item | Status |
|---|---|
| 1. Live read of serving revision/digest vs. the 2026-09-11 provenance | **Met (†)** |
| 2. Live revision-list read confirming no `--0000082` | **Met (†)** |
| 3. Replica count returns to zero after the 300 s cool-down, confirmed by one later live read | **Not met** — the one authorized read found 1 running replica, 491–558 s after the relevant timestamps; see "Post-run read-backs" |
| 4. Wake disclosed: route/method, mechanism, who, timestamp, owner-authorized | **Met** — transcript lines 21, 47–51 |

## Status statement at the Run A boundary

> Historical Run A boundary: Task 8.9 was OPEN / NON-GO. Preflight success was not completion.

The later Decision 2 supplemental evidence is COMPLETE / GO at independently accepted head
`8544d722`; this new documentation head still needs the quick independent check.

`go: null` is expected and is not a GO. The credential-using `execute` proof required a separate
owner **Decision 2**; this record did not authorize it. That decision was later granted and the
execute sequence ran, but its raw result was NON-GO; see the supersession callout. Item 3 of the
mandatory restoration read-back (replica returns to zero) was unverified by this attempt and is an
open item, not a failure of the preflight itself.

## Publication decision

The owner explicitly approved publication on 2026-09-15 after the following disclosures.

- **GUID precedent.** `rehearsal-20260911.json`, already committed on `main`, carries the identical
  `target.subscriptionId` (`ee625b3f-7cb1-4482-be3c-4363c5d76d23`) and `workspaceCustomerId`
  (`83a9c3a2-659e-40ca-9784-1ff871b9d5ea`) values (grep-verified). Precedent exists and matches
  exactly.
- **`C:\Users\` path precedent.** A grep of `docs/evidence/` for `C:\Users\` (case-insensitive,
  read-only) found matches **only** in the two new files created by this task
  (`run-a-attempt4-preflight-20260915.json`'s oracle operation `argv`, containing
  `C:\Users\pc\AppData\Local\Python\pythoncore-3.14-64\python.exe`, and its mirror inside the
  sanitized transcript). **No existing committed evidence file under `docs/evidence/` already
  carries a `C:\Users\<username>` path.** This would be new territory, not precedent-backed, if
  published as-is. Per the task brief, the JSON is not redacted here because its hash must continue
  to match the external original — but that means the publication decision must separately weigh
  this path disclosure (it names the local Windows account, `pc`, that ran the verifier). **An
  owner decision to withhold this path must also cover this .md itself**, not just the JSON and
  transcript: this record reproduces the identical path in "Transcript sanitization" above and in
  this bullet, and names the account `pc` directly. The owner approved publication with this
  disclosed path intact.
- **`.gitattributes` at commit time.** The repository's `.gitattributes` has no rule for
  `docs/evidence/b2-task-8-9/*.json` or `*.txt`; it only pins specific files elsewhere (e.g.
  `/docs/evidence/b1-r3-decision1/v21-r3-operational-*.{sql,json} text eol=lf`) plus generic
  `/gradlew`, `*.bat`, `*.jar` rules. This checkout has `core.autocrlf=true`. Both new evidence
  files are uniformly CRLF (JSON: 379 CRLF, 0 lone LF; transcript: 443 CRLF, 0 lone LF), so a naive
  `git add` under `autocrlf=true` would normalize them to LF in the blob and restore CRLF on
  checkout — for these two *specific*, uniformly-CRLF files that round-trip would likely reproduce
  the same bytes on this same configuration, but it is not guaranteed across a different clone's
  `core.autocrlf`/`core.eol` settings, and the whole point of recording these hashes is that they
  must hold regardless of who checks them out. A local, read-only check of the existing directory,
  `git ls-files --eol docs/evidence/b2-task-8-9/`, confirms this in-directory: every already-
  committed `*.json`/`*.md` file there — `2026-09-14-azure-timeout-ratification.md`,
  `deployment-completion-20260911.json`, both `deployment-provenance-*.json`, all three
  `rehearsal-*.json`, and both `run-a-attempt-*.md` — reports `i/lf w/crlf attr/` (stored LF-
  normalized, checked out CRLF, no explicit attribute), while
  `run-a2-operator-transcript-20260913.txt` alone reports `i/mixed w/mixed attr/` (stored and
  checked out exactly as its original mixed line endings, unnormalized — that record itself notes
  "line-ending conversion was disabled when staging it so they survive"). That existing exception is
  itself in-repo precedent for treating a transcript specially so its committed bytes keep matching
  a recorded hash. Following both that precedent and the existing `b1-r3-decision1` one, committing
  these files should add explicit `.gitattributes` entries (e.g.
  `/docs/evidence/b2-task-8-9/run-a-attempt4-preflight-20260915.json text eol=crlf` and the
  transcript path likewise, or `-text` to disable normalization entirely) **before** `git add`, not
  rely on `git add --renormalize` after the fact or on the ambient `autocrlf` setting. This
  publication adds exact `-text` entries for all three evidence files before staging them, so their
  committed blobs retain the reviewed hashes.
