# Phase 3 preparation and remediation: execution packet

**Prepared by:** Claude (Opus 5), 2026-09-22
**Worktree:** `C:\worktrees\wealthmgmtandportfoliotracker-worktrees\wealthmgmtandportfoliotracker-claude-phase3`
**Branch:** `claude/phase3-hydration-and-prod-e2e`, **local only**, created from verified
`origin/main@26a07fe048d9950c5b7268b9763eaca543b7b7ef`
**Evidence:** the folders named below (`final-run-*`, `negative-controls-*` and so on) are in the
local handoff folder `C:\worktrees\wealthmgmtandportfoliotracker-worktrees\_handoff\2026-09-22-phase3-prep\`.
They are not committed: they hold screenshots, network logs and account identifiers.

| Commit | Change |
|---|---|
| `88efc884` | React #418 fix plus hydration regression test |
| `f1dac2a2` | #418 review follow-ups |
| `0c9be0cd` | Phase 3 multi-user suite plus design |
| `c4a83c65` | Suite implementation-review (R2) fixes |
| `af800bed` | Diverging Overview totals recorded as a known defect (F9). Superseded: the root cause was wrong |
| `3849e47d` | F9 root cause corrected (analytics cache); deterministic classifier; NC10 |
| `8b2c3825` | R4 fixes: the cache classification is confirmed after the TTL expires; NC11 |
| `a451fa60` | R5 minors: 5 s wait slack; NC11 wording; residual limits documented |

## OWNER APPROVAL CALLOUT: read first

**Status.** Nothing in this packet has been pushed, published, deployed or run against Production.
No cloud resource, secret or Production account was touched. All testing ran on a local docker
stack and a locally served static export.

The actions below are blocked on your decisions. Everything that did not depend on them is done.

| # | Blocked action | Decision requested | If yes | If no |
|---|---|---|---|---|
| A1 | Push `claude/phase3-hydration-and-prod-e2e` and open a PR against `main` | Authorize publication | PR carries the #418 fix, the suite, the design and this packet; CI runs; merge remains a separate decision | Branch stays local; nothing reaches `main` |
| A2 | **Fix finding F1 before the Production run** (any unpriced holding crashes every dashboard page) | Expand this bundle to a bounded fix: null-guard `PortfolioTicker`, make `HoldingAnalyticsDTO.currentPrice` and `currentValueBase` nullable, failing regression test first, independent review | Crash fixed before demo and Production E2E. **Recommended:** a price-feed gap on any single holding takes down Overview, Portfolio, Market Data and AI Insights | F1 goes to Phase 4. The Production run may pass without exposing it if every certification holding happens to be priced |
| A3 | Deploy the Phase 2/3 candidate (Phase 2 plus the #418 fix; plus F1 if A2) | Frontend-only or full deploy (D3). The backend has small Java changes since its last build (Qodana cleanups in insight- and market-data-service, stated as behaviour-neutral) | Production serves the candidate; its build ID feeds `P3_EXPECTED_BUILD_ID` | Phase 3 cannot pass: Production still serves the pre-#418 frontend |
| A4 | Run the suite against Production (owner-operated, D8) | Decisions D1, D2, D4, D5, D6, D7, D9 and D10 below | The owner runs the runbook in §6 | Phase 3 stays open |
| A5 | Read-only Azure revision and digest capture before and after the run | Owner runs `az containerapp revision list/show` | The served revision is attested | Only the frontend build ID is attested |

**Decisions the Production run needs** (details in the design, §10):

| Id | Decision |
|---|---|
| D1 | Create retained `CERT_A` and `CERT_B` in Production (the suite never provisions there), and accept one permanent `FRESH` account per run (no deletion path exists) |
| D2 | Email domain and names. They appear in screenshots |
| D4 | One real chat request per run, or skip it (the verdict becomes INCOMPLETE) |
| D5 | Non-demo reset control: defect (recommended) or intended |
| D6 | Handling of `pw-output/`, which holds bearer tokens and the typed `FRESH` password. Never publish it |
| D7 | Logout leaves the JWT valid for up to 1 hour: accepted or Phase 4 defect |
| D8 | Operator: the owner, on the owner's machine |
| D9 | Partial valuation has no UI presentation: defect (recommended) or intended |
| D10 | Analytics is stale for up to 30 s after a holdings save (F9): defect (recommended: evict the user's analytics cache on write) or accepted. Either way, schedule the run away from 05:50–06:10 UTC (FX eviction) and from 07:50 UTC until that day's `market-data-refresh-job` has finished |

## 1. What was done, in the requested order

1. **Clean sibling worktree.** Created from `26a07fe0`, which was already `origin/main` locally, so no
   fetch was needed. Claude's assigned worktree, which holds the Qodana work, is untouched.
2. **React #418 fixed, failing test first.**
   - **Root cause.** `useAuthSession` read `localStorage` in its `useState` initializer. The static
     export prerenders with no session, so a signed-in first client render differed from the HTML.
     It dates from `1278a13b`.
   - **Fix.** Server and first client render both start in an explicit pending state; the existing
     layout effect resolves it before the first post-hydration paint. This keeps the `/login`
     redirect race fixed.
   - **Test.** A hydration test using the real `renderToString` and `hydrateRoot` was RED with the
     exact text-mismatch error and is GREEN after the fix.
   - **Mutation checks.** They prove the two guard tests catch the historical redirect race and a
     stuck skeleton. A Portfolio pending-gate test was added, and it is also mutation-checked.
   - **Browser proof** (`probe-418/`). The unfixed flagged build (`8f2c4cf7`, identical `frontend/src`)
     threw #418 on **12/12** signed-in hard loads; the fixed build threw it on **0/12**. The content
     and redirects are unchanged.
   - **Independent review.** Fable: **ACCEPT WITH MINORS** (0 Critical, 0 Important). All five minors
     are dispositioned: three fixed in `f1dac2a2`, one needing no action, and one evidence gap closed
     by re-running both probes identically.
3. **Desktop-only multi-user suite designed, reviewed and built.**
   - Design R1 was independently **REJECTED** (1 Critical, 10 Important, 10 Minor). Every finding is
     dispositioned in design §12 and implemented.
   - Code: `frontend/playwright.phase3.config.ts` and `frontend/tests/phase3-e2e/`, with 111 offline
     unit tests.
   - It is not wired into any workflow.
4. **Local, non-Production validation and independent review.** See §2 and §4.
5. **Stopped before every Production operation.**

## 2. Local validation results

**Environment.**
- Backend built from this branch: `docker compose -p wmpt-phase3`.
- Flagged static export on `localhost:3000`, with the API base set to `localhost:8080`.
- Market prices seeded locally, because only six fixture tickers are priced out of the box. This
  used a throwaway, locally generated internal key.

**Final uncontended run `p3-20260922T071432Z-272f`** (`final-run-a451fa60/`).
- Suite SHA `a451fa60` (the branch head), clean tree; served build `P1DI-PFaM5-R4jM_MjSz8`, matched
  with `P3_EXPECTED_BUILD_ID`. The frontend source is identical to `3849e47d`'s.
- **15/15 scenarios passed, 451 ledger checks, 0 failed.**
- Verdict **PASS_WITH_EXPECTED_DEFECTS**, with three expected defects:
  - `partial-valuation-not-presented` (F2);
  - `analytics-cache-stale-after-holdings-write` (F9);
  - `non-demo-reset-control-visible` (F3).
- **F9 detail.** S11 read `CERT_B`'s analytics 20,418 ms after S09's save. The page showed $42,115.87
  (summary) beside analytics built from the pre-save composition ($44,307.22), and an immediate Node
  re-read still returned the cached analytics. The suite then waited 14,107 ms for the cache entry to
  expire. Both endpoints then returned $42,115.87, so the known defect was recorded.
  `CERT_B`'s 1440 and 1920 reads agreed. The totals differ slightly from earlier runs because the
  07:00 local price refresh moved prices; they were constant within the run.
- Both certification accounts restored to their baselines; `FRESH` retained with GOOGL 7 and
  DOGE-USD 1500.5.

**Previous final runs**, both with the same verdict and 0 failed:
- `p3-20260922T065708Z-c19a` at `8b2c3825` (`final-run-8b2c3825/`): 451 checks, with a 1 s wait slack.
- `p3-20260922T063142Z-d14a` at `3849e47d` (`final-run-3849e47d/`): 450 checks. Its F9 record rested on
  the write window alone, which R4 showed was not enough.

**Earlier final-run attempt** `p3-20260922T060543Z-b774` at `c4a83c65` (`f9-divergence-run/`):
FAIL, because the new summary/analytics cross-check caught F9 for the first time.

**Negative controls: 11/11 bit on their target oracle.**
- NC1–NC9 ran at `3849e47d` (`negative-controls-3849e47d/`). `8b2c3825` does not touch their scenarios.
- NC10 and NC11 ran again at `a451fa60` (`negative-controls-a451fa60/`) and both bit. The NC11 run
  held the summary at 2,634.3899 and waited 33,931 ms; analytics then exceeded the summary by
  exactly the injected 1,000.
  - NC10 injects a disagreement with no write inside the cache TTL. It must fail at once.
  - NC11 sends an unchanged PUT, which returns 200 and is logged as a write. The server treats it
    as a no-op, so it is the suite's write log that opens the window. NC11 then injects a persistent
    divergence into the page and the Node re-reads. It must fail on the post-expiry convergence
    check.
- The earlier runs at `8b2c3825` are in `negative-controls-8b2c3825/`. NC11's first run there (`…065914Z-ab08`) failed as required, but it is contaminated. Its post-expiry
  re-read landed at 07:00:13Z, just after the local hourly price refresh (06:59:59.8Z–07:00:04Z in
  the market-data-service log), which moved GOOGL, so the summary moved too. That also shows a
  refresh inside the window fails safe. The clean re-run (`…070057Z-2ff6`) held the summary at
  2,634.3899 throughout, and analytics exceeded it by exactly the injected 1,000.

| Control | Oracle that failed as required |
|---|---|
| NC1 (injected page error) | Page errors |
| NC2 (fake save never forwarded) | Persisted readback |
| NC3 (another user's holdings under this user's id) | Concurrent isolation |
| NC4 (500) | Unexpected HTTP, with the console oracle also recorded |
| NC5 (filtered run) | Verdict INCOMPLETE |
| NC6 (export built from the unfixed hook) | Page errors: #418 caught on the signed-in load |
| NC7 (secret written past the registry) | Secret scan |
| NC8 (`CERT_A` ticker injected into `FRESH`'s page) | Leak observer |
| NC9 (second PUT injected) | No-retry counter |
| NC10 (unexplained summary/analytics disagreement) | Totals agreement: FAIL, not recorded as the known defect |
| NC11 (persistent divergence with a write inside the TTL) | Post-expiry convergence: FAIL, not recorded as the known defect |

## 3. Findings for Phase 3/4 triage

| Id | Severity | Finding | Evidence |
|---|---|---|---|
| F1 | **Critical** | Any holding without a current price crashes **every dashboard page** ("This page couldn't load"). Analytics returns `currentPrice: null`; `PortfolioTicker.formatValue(null)` calls `null.toFixed(2)`; `HoldingAnalyticsDTO` types the field as a non-null `number` | `run1-unpriced-crash/` (11/15 scenarios failed); `repro-crash.mjs`: priced-only renders, a single unpriced holding crashes, and seeding its price removes the crash |
| F2 | Medium | Partial valuation, such as a holding with no FX rate, is excluded from totals with **no UI indication** anywhere. The freshness strip correctly says "All prices fresh" | Final run, S11 observation; API `partialValuation: true` |
| F3 | Medium | "Reset Demo Portfolio" is shown to every signed-in user when its flag is on, and the gateway returns 403 to non-demo users | Final run, S13; source `PortfolioPageContent.tsx:123` |
| F4 | Low | A new user's empty Portfolio reads "No holdings match your filter." (the filter-empty string) | Final run, S02 observation |
| F5 | Decision | Sign-out is client-only; the pre-logout token is still accepted (HTTP 200) | S04 D7 observation |
| F6 | Low | The Next 16 static export writes dashboard segment-prefetch files as nested directories, while the client requests dot-joined names: 10 × 404 per dashboard load. Likely also in Production. Identical before and after #418 | Probe results; classified as noise only when corroborated |
| F7 | Positive | The frontend rejects a portfolio payload whose `userId` does not match the session (the first NC3 variant never rendered **Edit Holdings**) | NC3 development run |
| F8 | Note | The picker shows no error UI for save failures other than 409. Noted from source; not exercised | `AssetPicker.tsx` |
| F9 | Medium | **Analytics is stale for up to 30 s after any holdings save.** `PortfolioAnalyticsService.getAnalytics` is `@Cacheable` per user (Caffeine, 30 s, local and azure profiles), and no holdings write evicts it; the summary is uncached. For 30 s after a save, the allocation donut, 24h card, holding values and 24h cells, Market Data 24h and header ticker can show pre-save numbers beside a current Overview total. Observed: $42,147.14 (summary, current) against $44,349.79 (analytics, pre-save composition), 20–26 s after the writes, converging once the entry expired. A price refresh or the daily FX eviction opens the same window without a save. Likely fix: evict on write | `f9-divergence-run/`; `PortfolioAnalyticsService.java:188`, `CacheConfig.java` |

## 4. Harness notes and residual limits

- **Tracing.** Playwright's implicit tracing of `browser.newContext()` contexts hung at teardown on
  live dashboards: truncated `trace.zip` and a test timeout. The suite now traces every context
  explicitly and keeps videos only on failure.
- **Presentation oracles.** They compare the UI with the payload the page itself received. Separate
  reads can legitimately differ:
  - the observed case is the 30 s per-user analytics cache (F9), where an independent read
    moments earlier can return pre-save analytics;
  - price updates can also land between reads, as in Production's scheduled refresh.
  - The suite attributes a disagreement to F9 only after it has waited out the TTL and the endpoints
    agree again at the summary's value. A price or FX change inside that window fails the run, so
    it fails safe, and the runbook schedules the Production run away from both jobs.
  - Residual limit (R5 M3). An analytics-only divergence that follows a write within 30 s, heals
    once the entry expires and leaves the summary unchanged is still recorded as F9. A user cannot
    tell it from D10's accepted impact. An exact-equality tightening is described in design §5 but
    not implemented.
  - The summary-unchanged half of the convergence check has no dedicated negative control (R5 M4).

  An earlier note blamed the observed 24h-card mismatch on a price refresh; in hindsight it matches
  F9. Persisted state is still checked by independent API readback.
- **Leak observer.** It watches `main` only. The header ticker falls back to the global market
  summary, whose ticker names cannot distinguish a leak.
- **Rate limits.** The Production auth and strict buckets were not exercised locally, because the
  local profile has no auth limiter. Pacing is unit-tested, but its Production behaviour is unproven.
- **Chat.** It is mocked locally. In Production, reply content cannot distinguish the LLM path from
  its fallback.
- **Browsers and viewports.** Chromium only, desktop viewports only (1280×800, 1440×900, 1920×1080).

## 5. Independent reviews of the suite

| Round | Scope | Verdict | Resolution |
|---|---|---|---|
| R1 | Design | **REJECT** (1 Critical, 10 Important, 10 Minor) | All dispositioned in design §12 |
| R2 | Implementation plus local evidence | **Bounded REJECT** (0 Critical, 2 Important, 12 Minor) | All dispositioned in `c4a83c65` (design §13) |
| R3 | Scoped diff `0c9be0cd..af800bed` | **Bounded REJECT** (0 Critical, 1 Important, 3 Minor) | R2 items confirmed resolved. The Important (I-3): my F9 root cause was reversed. Corrected in `3849e47d` (design §14) |
| R4 | Scoped: I-3 only (`af800bed..3849e47d`) | **Bounded REJECT** (0 Critical, 1 Important, 5 Minor) | I-3 confirmed resolved. The Important (I-4): the classifier was disarmed for `CERT_B`. Fixed in `8b2c3825` (design §15) |
| R5 | Scoped: I-4 and the R4 minors (`3849e47d..8b2c3825`) | **ACCEPT WITH MINORS** (0 Critical, 0 Important, 5 Minor) | All R4 items confirmed resolved. Minors applied in `a451fa60` without a further round (design §15) |

**R2 detail.**
- The reviewer re-ran the full suite (15/15), negative controls NC3, NC4, NC8 and NC9, and the
  teardown safety net (`restored`).
- It confirmed that no path can mutate Production or provision there without the owner's approval
  phrase and gates.
- Its two Importants:
  - the design doc still claimed the freshness strip signals partial valuation, and D9 was
    missing;
  - the expected-defect mechanism was open-ended. It is now a closed list, and an unknown id fails
    the run.

**R3 detail.**
- My F9 root cause was wrong, and so was my own probe: it compared a fresh account holding the
  *baseline* composition, not `CERT_B`'s post-S09 holdings.
- The reviewer traced it from the run's own request timings to the per-user 30 s analytics cache,
  which has no eviction.
- It also flagged that the classifier accepted any disagreement.
- Both issues are fixed. The classifier is deterministic, proven by NC10 and by a natural positive
  case in the final run.

**R4 detail.**
- It reproduced the corrected root cause, the write log, the Node re-read and NC10, including one
  full run and one NC10 run of its own.
- Its Important (I-4): the classifier tested a necessary condition, a write within the TTL, not the
  cause. The suite's fixed schedule puts every one of `CERT_B`'s S11 reads inside the window after
  S09's save, so any real `CERT_B` divergence would have been excused on every run.
- Fix: inside the window the suite now waits until every pre-write cache entry has expired and
  requires analytics to converge on the unchanged summary total. NC11 proves a persistent
  divergence then fails.
- Minors: price and FX triggers documented, with the Production scheduling constraint (M-d); the
  unjustified 1 s attribution margin removed (M-e); S11 now times the exact response it compared
  (M-f); doc wording (M-g); `msSinceLastWrite` null only without a prior write (M-h).

**R5 detail.**
- It confirmed that the convergence check exists only when the write window is open, and that
  NC11's failure comes from that check: `evidence.verify` hard-asserts, so the run stops there. At
  the parent commit NC11 would have passed, so the control discriminates.
- It judged the decision not to re-run NC1–NC9 justified, because the diff does not touch their
  scenarios.
- Minors and what happened to them:
  - M1: an entry's TTL starts when the result is stored, so a slow analytics computation that read
    pre-write data could outlive the 1 s slack and false-FAIL. The slack is now 5 s.
  - M2: my claim that an unchanged PUT forces a version bump was wrong; the server returns a no-op
    first. Wording corrected. The control never depended on it.
  - M3 and M4: recorded as residual limits (§4).
  - M5: wording nits applied.
- The review loop is closed: five rounds on the suite, and every Critical and Important is
  resolved.

## 6. Production runbook (owner-operated; only after A3, A4 and the decisions above)

1. **Deploy** the approved candidate, and note the served build ID. It appears in `/login` as
   `"b":"<id>"`.
2. **Create** `CERT_A` and `CERT_B` through the Production signup page, using names and addresses
   per D2.
3. **Capture** the backend revisions and digests (read-only `az containerapp revision list/show`).
   **Schedule** the run outside 05:50–06:10 UTC (FX eviction), and not from 07:50 UTC until that
   day's `market-data-refresh-job` execution has finished (D10).
4. **Run**, in the owner's own PowerShell session on the owner's machine, from `frontend/`:

   ```powershell
   $env:P3_TARGET = "production"
   $env:P3_PRODUCTION_APPROVAL = "owner-approved-phase3-production-run"
   $env:P3_IDENTITY_LIFECYCLE = "retained"
   $env:P3_EMAIL_DOMAIN = "<domain per D2>"
   $env:P3_WORK_DIR = "C:\p3-evidence"
   $env:P3_EXPECTED_BUILD_ID = "<served build id>"
   $env:P3_CERT_A_EMAIL = Read-Host "CERT_A email"
   $env:P3_CERT_A_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR((Read-Host "CERT_A password" -AsSecureString)))
   $env:P3_CERT_B_EMAIL = Read-Host "CERT_B email"
   $env:P3_CERT_B_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR((Read-Host "CERT_B password" -AsSecureString)))
   npx playwright test -c playwright.phase3.config.ts
   ```

   Add `$env:P3_SKIP_CHAT = "1"` only if D4 says so.
5. **Afterwards:**
   - Read `C:\p3-evidence\<runId>\verdict.json`, and `restoration-teardown.json` if it exists.
   - Re-capture the backend revisions.
   - Share only the sanitized files: ledger, network, provenance, final state, verdict and
     screenshots.
   - Delete `pw-output/` after review (D6).
6. **Expected Production profile:**
   - 9 auth requests, paced at least 13 s apart;
   - strict-bucket requests paced at least 6.5 s apart per user;
   - a run time of a few minutes;
   - S13 recorded as an expected defect while the reset flag stays on;
   - F9 recorded as an expected defect only if S11 reads analytics within 30 s of S08's or S09's
     save and analytics converges once the entry expires; that adds one wait of up to about 35 s;
   - one new retained `FRESH` account.
