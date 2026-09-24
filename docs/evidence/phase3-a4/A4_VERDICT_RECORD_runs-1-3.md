# A4 Production desktop E2E: verdict record for runs 1, 2 and 3 and cleanup

> **Published copy (sanitized).** Account e-mail addresses are replaced by roles, per-user IDs of the
> deleted test accounts are omitted, and local paths are described rather than given. Run IDs,
> hashes, verdicts and counts are unchanged. The private original is kept locally with the run
> evidence; `pw-output/` was never read and is not published.

Drafted by Claude. Fable accepted it with minors (section 10 and the note after it), and Codex
accepted it on 2026-09-24 with four corrections, which are applied (see the end). It supersedes the
run-1-only draft
`A4_RUN_VERDICT_p3-20260923T174742Z-ee73.md`, which Fable rejected and which is kept unchanged as
history. It applies Fable's twelve findings on the run-1 draft (section 9) and its thirteen findings on this record (section 10).

Each run is judged only on its own evidence. Runs 1 and 2 remain **failed historical evidence**,
and their checks are never combined with each other or with run 3 into one passing run. Nothing
is relabelled: `PASS_WITH_EXPECTED_DEFECTS` is not `PASS`, and `FAIL` is not softened.

## 1. Outcome

| Run | Run ID | Suite | Suite verdict | Failed / unrun | Binding |
|---|---|---|---|---|---|
| 1 | `p3-20260923T174742Z-ee73` | `23acbd6b` | **FAIL** | `S05` / `S06`–`S13`, `S99` | `BOUND` |
| 2 | `p3-20260923T182221Z-92cc` | `23acbd6b` | **FAIL** | `S12` / `S13`, `S99` | `BOUND` |
| 3 | `p3-20260924T032033Z-52f1` | `1c3a132f` | **PASS_WITH_EXPECTED_DEFECTS** | none / none | `BOUND` |

**Codex decision (2026-09-24):** A4 is satisfied by run 3 as `PASS_WITH_EXPECTED_DEFECTS`, with
one observed expected defect (`non-demo-reset-control-visible`). Runs 1 and 2 remain separate FAIL
history. The decision does not relabel run 3 as a clean `PASS`, and it does not close any recorded
defect. The five temporary A4 users were deleted afterwards (section 7).

## 2. Identity common to all three runs

| Item | Value | Source |
|---|---|---|
| Target and origins | `production`, `https://vibhanshu-ai-portfolio.dev`, `https://api.vibhanshu-ai-portfolio.dev` | each run's `provenance.json` |
| B3 candidate SHA (from the accepted record) | `cdc51df6643b51fb92dc747a20ac3ca9be4ac2b1`. The runs did not observe this SHA live; the live check established only the served build ID (next rows) | B3 accepted record: plan blob `705d993b` at commit `23acbd6b` |
| B3 runs | backend `35860682429` attempt 1 (`portfolio-service--0000097`, `sha256:19a64b25…086b`); frontend `35862375385` attempt 1 | accepted record; not re-observed (no A5) |
| Build ID expected and served | `R5P3Iw1SRc9jlIX2Wwee8` in all three runs | S00 ledger and `provenance.json` of each run |
| Binding checker | Schema v1.2.1. All three scripts invoke `a4-bind.mjs`. Runs 1 and 2 were bound by `a4-bind.mjs` as it then stood, with expected suite SHA `23acbd6b`: each binding records `expectedGitSha: 23acbd6b`. The kit keeps that version as `a4-bind.v1.2.1-suite-23acbd6b.mjs` (`c6aa61fc`). Its identity rests on the file timestamp (the re-pin was made at 18:36Z, after run 2's check at 18:27:39Z) and on a diff that shows only the SHA and a comment. Run 3 was bound by the Codex-accepted re-pin to `1c3a132f` (`be0226f5`). The B3 record stays at commit `23acbd6b`, blob `705d993b` | kit, each `a4-binding.json` |
| Operator | the owner, in the owner's own PowerShell session; Claude prepared the scripts and never operated a run; Codex reviewed and did not operate | owner decision D8 |
| Viewports | 1280×800, 1440×900, 1920×1080 declared; see each run for which ran | `provenance.json` |

The `checkExit=0 (…)` line in each run's `summary.txt` is a legend for the exit code, not counts
of incomplete or mismatched checks. Run 1's file reads `(0 BOUND, 3 INCOMPLETE, 4 MISMATCH)`; the
files for runs 2 and 3 read `(exit code: 0 BOUND, 3 INCOMPLETE, 4 MISMATCH)`.

## 3. Run 1: `p3-20260923T174742Z-ee73`, FAIL at S05

Script `a4-owner-run.ps1` (`b8321926`). The marker `.a4-invoked` records `started 17:35:15Z` and
`restarted 17:43:35Z (suite had not run)`. The suite ran 17:47–17:51Z.

| Scenario | Result | Notes |
|---|---|---|
| S00 preflight | pass | served build matched; CERT_A and CERT_B normalised with one PUT each. **~79 s stall**, see 3.2 |
| S01 signup validation | pass | no request sent |
| S02 signup | pass | run 1's FRESH user created (201) |
| S03 duplicate signup | pass | 409 |
| S04 login, logout, re-login | pass | D7 observed: the pre-logout token is still accepted after sign-out (accepted, Phase 4) |
| **S05 first save** | **FAIL** | `expect(dialog).toBeHidden()` failed after 20 s, see 3.1 |
| S06–S13, S99 | **unrun** | stop at the first failure (`maxFailures: 1`) |

### 3.1 S05: cause best-supported by code path and timing, not directly observed

What the evidence shows:

1. `PUT /api/portfolio/holdings` answered **200** at 17:50:20.327Z with the loaded version and the
   typed quantities (4 of the S05 checks passed before the failing assertion). Persistence was
   **not independently read back** in the run: the readback comes after the failed assertion.
   Later and only indirectly, the cleanup preview's holdings count (14) is consistent with this
   FRESH user holding its two saved rows (section 7). That is a count, not a content readback.
2. The Edit Holdings dialog stayed open for the 20 s bound.
3. The save success path (`AssetPicker.tsx:151-179`) awaits `buildPortfolioResponseFromWireHoldings`
   → `loadMarketPrices` → `GET /api/market/prices` (`portfolio.ts:285`), which has **no timeout**,
   before it closes the dialog.
4. `network.jsonl` has no response and no `requestfailed` for `/api/market/prices` in the run. The
   monitor (`lib/evidence.ts:212-256`) records only responses and failures, so a request that
   never settles leaves **no trace**. The log therefore cannot tell "GET pending" from "GET never
   sent". Console errors for S05 were never flushed, because `assertClean("S05")` did not run.
5. Owner-authorized read-only Azure system log for `market-data-service` (excerpt
   `evidence-market-data-system-logs-20260923T1755Z.jsonl`, `8c6b37f9…`): a replica was **scheduled
   at 17:50:21Z**, about 1 s after the PUT's response. Its containers started 17:50:32–42Z, and the
   startup probe then failed (connection refused) every second from 17:50:43Z until at least
   17:51:06Z, where the excerpt ends. The log names revision `--0000080`. B3 deployed
   portfolio-service and the frontend only; the record makes no claim about when `--0000080` was
   deployed. Readiness was **not observed**: the service was not ready ≥ 45 s after scheduling,
   and its readiness time is unknown.

Why this mechanism is the best-supported one:

- (a) `portfolio.ts:193-222` uses `Promise.allSettled`, so a 4xx, 5xx or network failure would
  have let the save path continue and **close** the dialog. Only a request that never settles
  keeps it open.
- (b) The gateway holds a request for up to its 150 s response timeout (`main.tf:255`), so no 504
  could arrive inside the 20 s window.
- (c) The market-data replica was never ready at any moment of the wait. It was woken by a request
  routed to market-data-service, presumably the enrichment GET. The PUT went to
  portfolio-service, so it did not wake market-data-service.
- (d) portfolio-service has no HTTP dependency on market-data-service, and the awaited cache
  invalidation is reached only after enrichment settles, so neither is the hang.

**Not excluded by the shared evidence:** `saveComposition` throwing after the 200
(`assetPickerSave.ts:72`, `response.json()`). That would also leave the dialog open silently,
because `AssetPicker.tsx:137-182` has no `onError`.

**Direct confirmation (not done):** only the owner could provide it, from
`pw-output/traces/S05-*-FRESH.zip`, which Claude does not read, by checking whether
`GET /api/market/prices` is pending at close. The gateway access log could also show it.

**Best-supported cause, in one sentence:** a cold `market-data-service` (scale to zero) kept the
unbounded post-save price enrichment waiting past the test's 20 s bound. This is supported by
code path and timing. The pending GET itself is unobserved in the shared evidence, and the
alternative above (`saveComposition` throwing after the 200) is not excluded.

### 3.2 S00: an earlier ~79 s stall, most consistent with a portfolio-service cold start

CERT_A's login, GET, PUT and GET took 81.9 s (`auth-requests.jsonl` line 1 at 17:47:44.831Z to
`ledger.jsonl` line 3 at 17:49:06.690Z). CERT_B's identical four calls then took 2.9 s. The
auth pacer spaces logins by only 13 s, and `/api/auth/*` is served by the already-warm gateway.
About 79 s therefore sits in CERT_A's portfolio-service calls, which is consistent with
portfolio-service's `min_replicas = 0` (`main.tf:326`). No portfolio-service log was queried, so
this is not confirmed.

S00 absorbed the stall only because Node `fetch` (`lib/api.ts:83`) has no timeout and S00's test
timeout is 240 s. The same stall in the browser (expect 20 s, navigation 45 s) fails a scenario.
In runs 2 and 3, after the warm-up, S00's ledger spans only 14.7 s (sections 4 and 5).

### 3.3 Not covered by run 1 (nothing below is claimed from it)

- S05's own remaining checks: the independent FRESH readback, the table rows, the no-retry window,
  and S05's common checks (console errors, unexpected HTTP ≥ 400, non-target origins).
- S06–S13 in full: cancellation, invalid quantities, the combined save, the conflict (409),
  isolation, all of S11 (Overview, Portfolio, Market Data, AI Insights, 24h figures, coverage,
  navigation), chat (S12), and the reset-control finding (S13).
- S99: baseline restoration and the run-wide `scanArtifactsForSecrets` (`spec.ts:1283-1284`).
  Restoration was done by the global-teardown safety net (`restoration-teardown.json`:
  `not_needed` for both CERT users). The only secret guard applied to the shared files was the
  per-write `SecretRegistry.assertClean` (`lib/evidence.ts:25,30`), which is not the same as the
  monitor's `assertClean("S05")` in 3.1. The screenshots were not scanned; they show the FRESH address, which is
  not a registered secret.
- Viewports 1280×800 and 1920×1080. Only 1440×900 ran.
- The three expected defects D5, D9 and D10. `expectedDefects: []` means they were **neither
  observed nor refuted**, not that they are absent.

### 3.4 Other run-1 notes

- **Aborted requests:** most `net::ERR_ABORTED` entries are Next.js HEAD prefetches during
  navigation. Two are API calls cut off by the sign-out navigation: `POST /api/auth/login`
  (`network.jsonl` #161, 17:50:02.429Z) and `GET /api/insights/market-summary` (#162). The suite
  counted exactly three login requests (ledger line 52), so #161 is the already-answered second
  login (200 at 17:50:00.693Z), not a fourth login. Harmless.
- **"1 error was not a part of any test"** appeared in the owner's Playwright output. The
  sanitized files don't identify it; it can be recovered only from that console output. It does
  not affect the binding: `verdict.json` was written at 17:50:56.992Z and teardown restoration was
  `not_needed`. One mechanism worth checking is a strict-path request held in `paceStrict`
  (`evidence.ts:183-185`) when the S05 context was closed. **Unresolved.**
- **Observed by the operator, not in the evidence set:**
  - the preflight at about 16:00Z: one GitHub check (no deployment or deploy run after
    `35862375385`) and one refresh-job check (`market-data-refresh-job-29835840` Succeeded
    08:01:18Z);
  - the first attempt at 17:35Z, which timed out on the cold gateway before any suite activity;
  - the restart's CERT_A 409 followed by the 200 login proof.

  Only the restart itself is corroborated, by the `.a4-invoked` marker.

## 4. Run 2: `p3-20260923T182221Z-92cc`, FAIL at S12

Script `a4-owner-rerun.ps1` (`52ddc81b`). It added the owner-accepted cold-start workaround:
a gateway wake (`/actuator/health`), a health warm-up of the portfolio, market-data and insight
services, and a keep-alive of all four health paths every 60 s during the suite. The keep-alive
logged 24 GETs, all 200 (`run2-keepalive.log`). The marker
`.a4-invoked-run2` records a start at 18:17:36Z. The suite started at 18:22:38Z, and
`verdict.json` was written at 18:27:38.340Z.

- **S00–S11 passed:** 517 ledger checks, 0 failed, including 366 in S11 across all three
  viewports. S00's ledger spans 14.7 s (18:22:23.456Z to 18:22:38.154Z); the owner's console
  output shows 16.0 s for the scenario.
- **S12 failed.** The suite's call capture saw exactly one chat request, since its first poll
  passed. No response arrived within the 20 s bound: `lib/ui.ts:53`, "Timeout 20000ms exceeded
  while waiting on the predicate", from the owner's console output.
  - `network.jsonl` has no `/api/chat` record for S12. That is consistent with a request that had
    not settled when the context closed, since the monitor records only responses and failures.
  - The reply latency is therefore unknown, and **the cause is not established**. The insight
    health path answered 200 until 18:26:58Z, the last keep-alive sample before the chat request.
    No sample covers the chat wait itself: the next round never ran before the stop at 18:27:38Z.
- S13 and S99 were **unrun**. The teardown safety net restored CERT_B (`restoration-teardown.json`:
  CERT_A `not_needed`, CERT_B `restored`).
- **Observed:** D7 (the token is still accepted after logout) and `empty-portfolio-shows-filter-copy`.
  "1 error was not a part of any test" appeared again in the console output; it is unresolved as in
  run 1.
- **Expected defects:**
  - D5 was not tested, because S13 was unrun.
  - D9 was not recorded. The suite records it only when the summary API's `partialValuation` flag
    is true (`phase3.spec.ts:873,917`, annotation at `:1190-1195`). `verdict.json.expectedDefects`
    lacks it, and the ledger has no "partial valuation presentation" observation (run 2 has 4
    observations, none of them D9).
  - D10 was not triggered. The suite records it only when all three conditions hold: the Overview
    totals disagree, the analytics read falls inside the post-write cache window, and analytics
    converges on the summary after the TTL (`phase3.spec.ts:951-976`). All 9 "summary and analytics
    endpoints agree on the total" checks passed and no convergence check ran, so that branch was
    never entered.
  - None of these means a defect is absent.
- **Not covered:** S12's remaining checks, S13, S99 and its run-wide secret scan.

**Change adopted after run 2 (Codex-accepted):** suite commit `1c3a132f` = `23acbd6b` plus one
change in `phase3.spec.ts` S12, `singleResponse(chats, 150_000)` (+4/−1). It lets the chat reply
take up to 150 s, as an accepted demo limitation. No other suite file changed.

## 5. Run 3: `p3-20260924T032033Z-52f1`, PASS_WITH_EXPECTED_DEFECTS

Script `a4-owner-run3-20260924.ps1` (`faed0213`), cleared by Codex. By owner decision, it has no
clock-time gate, no refresh-window check and no Azure call; its only time gate is a start on
2026-09-24 UTC.

It started 03:15:48Z (marker `.a4-invoked-run3`). The GitHub currency check was clear: no
deployment or deploy run since B3, and the run listing reached back past B3. The script's control
flow corroborates this, because the marker is written only after that check passes. The suite ran
03:20:30–03:25:46Z (`summary.txt`).

From the owner's console output only: the wake answered 503 then 200, and all three services
answered 200 on the first warm-up attempt. The script corroborates only that a 200 arrived before
the suite started, since it stops otherwise.

- **All 15 scenarios passed**, none unrun or unexpected, and `filtered: false`. The ledger holds
  535 checks, 0 failed, including 366 in S11 across all three viewports. S00's ledger spans
  14.7 s (03:20:34.709Z to 03:20:49.441Z).
- **S05:** the save closed the dialog, and the independent readback equalled the saved draft with
  the version incremented.
- **S12:** exactly one chat request, answered 200 at 03:25:27.581Z with a non-empty rendered
  reply. The send time is not recorded. However, S12's first network record is the login page at
  03:25:08.356Z, and the chat could not be sent before the page existed. The reply therefore took
  **at most 19.2 s**, or at most 17.2 s if it was sent after the `/api/portfolio/analytics` read at
  03:25:10.398Z.
  - It would have fitted a 20 s bound measured from the send.
  - The 150 s wait adopted after run 2 was not exercised in this run.
- **Screenshots (inventory only):** `screenshots\` holds 53 non-empty PNG files and no
  subfolders. By filename prefix: S01 3, S02 3, S03 1, S04 1, S05 2, S07 1, S08 2, S09 1, S10 1,
  S11 36, S12 1, S13 1. Claude and Codex counted them; **neither reviewed their visual content**,
  and no claim in this record rests on what they show.
- **S99:** baselines were restored and confirmed (`restoration.json`: confirmed by S99). The final
  state is CERT_A 4 holdings, CERT_B 4 holdings (restored) and FRESH 2.
- **Binding:** `BOUND`, with mismatch `[]` and incomplete `[]`. Every content and semantic check
  is true, and the owner scan is clean. The sidecar hash matches. Codex independently recomputed
  the binding hashes.
- **Refresh overlap:** the suite window does not overlap the refresh job's schedule,
  `cron_expression = "0 8 * * *"` (`main.tf:461`, "daily at 08:00 UTC" at `:429`). This is inferred
  from the configured schedule; no Azure read was made.
- **Expected defects:**
  - **Observed:** `non-demo-reset-control-visible` (D5, S13).
  - **Not recorded:** `partial-valuation-not-presented` (D9). It is recorded only from the summary
    API's `partialValuation` flag (`phase3.spec.ts:873,917,1190-1195`). `verdict.json.expectedDefects`
    lists D5 only, and none of the 7 ledger observations is a "partial valuation presentation".
  - **Not triggered:** `analytics-cache-stale-after-holdings-write` (D10). Its three-condition
    branch (section 4) was never entered: all 9 totals-agree checks passed and no convergence
    check ran.
  - Neither non-trigger means a fix.
- **Also observed:**
  - D7, the token accepted after logout (accepted, Phase 4).
  - `empty-portfolio-shows-filter-copy`, a UX finding.
  - The S12 limitation: the reply can't show whether it came from the LLM or the deterministic
    fallback.
  - "1 error was not a part of any test" did not appear in this run's console output.

## 6. Owner decisions that shape this record

- **Cold-start slowness is an accepted cost trade-off.** The owner does not want `min_replicas = 1`
  and did not ask for a fix. The run-1 draft's remediation proposal is therefore **withdrawn**.
  The demo rehearsal works around the slowness, as in runs 2 and 3: a gateway wake, a health
  warm-up of the three backends, and a keep-alive of all four health paths.
- **Recorded as latent, no fix requested:** the post-save price enrichment and the
  page-load price read (`loadMarketPrices`) have no time bound. The existing `catch` fallback at
  `AssetPicker.tsx:158-163` is unreachable for price failures, because `allSettled` never throws.
  An awaited invalidation would re-enter the same unbounded read. Any future fix would bound
  `loadMarketPrices` itself. Even with such a fix, a rehearsal without the warm-up can still fail
  S05 on a late non-2xx.
- **Run 3 timing:** run 3 was moved to 2026-09-24 UTC at any time. The 08:10Z start, the "today's
  refresh succeeded" check and the midnight margin were agent-chosen restrictions and were removed.
- **S12 chat wait:** the 150 s wait was accepted as a demo limitation (Codex-accepted `1c3a132f`).

## 7. Cleanup (owner-run after Codex cleared `a4-owner-cleanup.ps1` `eccabfcb`)

The cleanup facts below come from three distinct sources:

- **The local cleanup record**, `a4-cleanup.txt` in the local evidence folder, written by the script: `at=2026-09-24T05:18:46Z`,
  the three runs, the five users, `cleanup=COMMITTED`, `verify=0 users, 0 credentials, 0 orphaned
  holdings`, and both logins 401. It also shows the local state: the credential file is absent and
  all three run folders remain.
- **The owner's terminal output**, pasted into the session: the timestamps, the preview and commit
  row counts, the verify table, and the per-login lines. Items from this source are marked
  "(console)".
- **Cross-checks against the run evidence**, made by Claude read-only: the target addresses were
  derived from the bound runs (`network.jsonl` S02 FRESH signups returning 201). The user IDs
  printed in the preview were matched against each run's `holdings-writes.jsonl`. These
  cross-checks corroborate the preview's identities; they are not database reads.

- **03:48:34Z (console):** refused as "too early" (the token window ended 04:25Z). Nothing was
  touched.
- **05:15:02Z, preview, rolled back (console):** exactly the five evidence-derived targets, with
  5 users, 5 credentials, 5 portfolios, 14 holdings and 0 repair-audit rows to remove, and an
  orphan-portfolio baseline of 0.
  - The user IDs match the runs' `holdings-writes.jsonl` files: CERT_A's in run 1's file
    (the S00 normalisation write); CERT_B's in all three; and each FRESH user's in its own run's file.
  - 14 holdings = 4 (CERT_A) + 4 (CERT_B) + 2 per FRESH user.
- **05:17:10Z (console):** the owner typed `DELETE`; the script reported `COMMITTED`.
- **05:17:12Z, read-only verify (console):** 0 users, 0 credentials, 0 orphaned holdings, and 0 in
  a fourth column, orphaned portfolios.
  - The script asserts only the first three (`a4-owner-cleanup.ps1`, the verify regex), and
    `a4-cleanup.txt` records only those three.
  - The orphaned-portfolios zero is owner-pasted and unasserted.
- **05:18:30Z and 05:18:46Z (console log lines):** the CERT_A and CERT_B logins were both rejected with 401. The second 401 is also bounded by `a4-cleanup.txt` `at=2026-09-24T05:18:46.85Z`, which the script writes straight after the login loop.
  The script then removed the credential file and wrote `a4-cleanup.txt`; it does both only when
  both 401s were proven.
- **Basis of these counts:** the database figures come from the cleanup script's own queries, run
  from the owner's session. **Neither Claude nor Codex queried the database independently.**
  Claude and Codex checked the owner's output and the local record: the record is present, the
  credential file is absent, and all three run folders remain.
- **Kept:** all run evidence, the markers, the summaries and the keep-alive logs. `pw-output/`
  stays private and local; deleting it needs separate owner approval.

## 8. Evidence hashes (SHA-256, sanitized files; `pw-output/` not read, not shared)

Run 1 (local evidence folder `p3-20260923T174742Z-ee73`) (unchanged from the run-1 draft, recomputed by
Fable): `a4-binding.json` `3da09972…`, `a4-binding.safety-check.json` `692cf64e…`, sidecar
`904ff956…`, `ledger.jsonl` `990503e2…`, `network.jsonl` `7d0728d2…`, `provenance.json`
`d4678438…`, `verdict.json` `e3964f90…`, `restoration-teardown.json` `a60a7a44…`. The Azure log
excerpt is `8c6b37f9…`.

Run 2 (local evidence folder `p3-20260923T182221Z-92cc`):

```
e2548448c70f64fe8e4312da2a2abecb2f60f99100b1ab2a644220d0c428649c  a4-binding.json
8b7ff6d387413437445b635f012c2977190a3a5deb7841fda172cfd88252c30c  a4-binding.safety-check.json
93c63fd45988cb0d6ce215a766dcf7819a936154803930aca52d4cd9a65edba9  a4-binding.safety-check.json.sha256
9791bf289dc092d34692348d5120d7ddb93cb005370e91ecc7b24968cb0de071  ledger.jsonl
0e0959a7966fdaa4b9df0df32c15519dbd5e414af72bc66e7867ed9d5f9c1c4f  network.jsonl
00c98bfb2941895c4fcdf53d8b391e5fc3294d14bfcefdf71496208030e1572e  provenance.json
40b4b75f9f8bc5496fc49ccb50a9974f617b805d4799c635694b6f342d9fd243  restoration-teardown.json
bcb4f4ffd85df86e7d42b260e66a8f2d395dc5353f2c98df5bf91be134f52e65  verdict.json
```

Run 3 (local evidence folder `p3-20260924T032033Z-52f1`):

```
849500589f8b98eec9fc8bee52bb28aa014281d30fadf41545f1aa7faafb47c5  a4-binding.json
7c8c0b576d024d955432727ac048648c92e00d1e37665abe28156fcaa3ec9613  a4-binding.safety-check.json
af4a5727672132958ea57ca3a36c31f36d9f92a66464bd158bc92ac72b116b3e  a4-binding.safety-check.json.sha256
37353d4caaa53197c0a2ec9d7020f8b448f49084823a59abe2390766b140fe06  ledger.jsonl
c1f3ecdd2ff301580f99edf9c26db3daa4232e563d9270d61ade8576ed7b5828  network.jsonl
0b1e2fd4d723d0bb4857c9877afb8b86fa6ba261600056f07fca6102c307d743  provenance.json
664e59571a15d8e7ddb27be019f75dff32345240b19ca21f493923e1b77a1c2a  verdict.json
eb5332200e80212bf570d1fd50ce866c22e94e440413eb913453af2b25adf976  final-state.json
0bb81290c4120202aadba4e1990a4166d4d4f660af1804304a9c31fa44094f7d  restoration.json
```

## 9. How Fable's twelve findings on the run-1 draft were applied

| # | Finding | Applied in |
|---|---|---|
| 1 | "confirmed" overstated; the blocking request was never observed | 3.1: "best-supported by code path and timing" (not "established"; see round 2 #3), reasons (a)–(d), what is not excluded, and how it could be confirmed |
| 2 | unreported ~79 s S00 stall | 3.2 |
| 3 | remediation could re-hang; its fallback is unreachable | 6: proposal withdrawn by owner decision; the latent issue recorded accurately |
| 4 | "not covered" incomplete | 3.3 |
| 5 | warm-up covered the wrong set | 4 and 5: runs 2 and 3 woke the gateway and warmed all three backends, with a keep-alive |
| 6 | aborted requests mis-described | 3.4 |
| 7 | wrong request named as the market-data wake | 3.1 item 5 and (c) |
| 8 | "the save succeeded" rested on the 200 alone | 3.1 item 1 |
| 9 | claims with no evidence in the shared set | 3.1 item 5 (revision claim removed) and 3.4 (operator-observed items) |
| 10 | "1 error not part of any test" | 3.4, 4 and 5 |
| 11 | timing wording; the `checkExit` legend | 3.1 item 5 and section 2 |
| 12 | binding verified | no change needed |

## 10. Fable round 2 on this record (REJECT, bounded) and how each finding was applied

| # | Severity | Finding | Applied in |
|---|---|---|---|
| 1 | Important | D9 "not triggered" rested on the 24h-coverage checks, a different signal | 4 and 5: now based on the `partialValuation` flag rule, `verdict.json.expectedDefects` and the absence of a D9 observation; "RELIANCE.NS was priced this time" removed |
| 2 | Minor | console-only facts not marked | 5 (wake and warm-up statuses), 4 and 5 (S00 durations: ledger span 14.7 s), 7 (timestamps; the orphaned-portfolios zero is owner-pasted and unasserted) |
| 3 | Minor | "Established cause" contradicted "not excluded" | 3.1: "Best-supported cause" |
| 4 | Minor | run-3 S12 latency understated | 5: at most 19.2 s (at most 17.2 s); would fit a 20 s bound from the send; the 150 s wait was not exercised |
| 5 | Minor | checker attribution | 2: `a4-bind.mjs` as it then stood, with `expectedGitSha: 23acbd6b`; identity of the kept copy by timestamp and a diff |
| 6 | Minor | "insight health 200 throughout" | 4: until 18:26:58Z; no sample covers the chat wait |
| 7 | Minor | refresh overlap cited an operator observation | 5: `main.tf:461` cron, `:429` comment |
| 8 | Minor | broken fence swallowed the first run-3 hash | 8: fixed |
| 9 | Minor | `checkExit` quotation not verbatim for run 1 | 2: both forms quoted |
| 10 | Minor | user-ID mapping overstated | 7: CERT_A in run 1's file only; CERT_B in all three; each FRESH in its own run |
| 11 | Minor | D10 description missing a condition | 4: all three conditions; the branch was never entered |
| 12 | Minor | ambiguous `assertClean` | 3.3: `SecretRegistry.assertClean` named, distinguished from the monitor's |
| 13 | Minor | "warm-up of all four services" was inconsistent | 4 and 6: gateway wake + three backend warm-ups + keep-alive of all four |

Fable round 3 (scoped re-check): **ACCEPT WITH MINORS**. Its three Minors were applied: the 3.1 heading now reads "best-supported"; section 7 cites both the console lines and the `a4-cleanup.txt` `at=` time for the second 401; and the introduction now references sections 9 and 10.

Codex acceptance (2026-09-24), with four bounded corrections applied:

1. The candidate row is renamed "B3 candidate SHA (from the accepted record)", and the served build ID is identified as the only live-established identity.
2. Section 7 now separates the local cleanup record, the owner's terminal output, and the run-evidence ID cross-checks.
3. Section 9, row 1, now reads "best-supported", matching 3.1.
4. Section 5 records the run-3 screenshot inventory (53 non-empty PNGs), and states that their content was not visually reviewed.
