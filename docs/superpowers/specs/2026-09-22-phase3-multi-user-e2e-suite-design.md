# Phase 3 multi-user browser E2E suite — design

**Date:** 2026-09-22 (revision 5, same day)
**Status:** Revision 2 answered the independent design review (R1 REJECT: 1 Critical, 10 Important,
10 Minor; see §12). Revision 3 answers the implementation review (R2 bounded REJECT: 0 Critical,
2 Important, 12 Minor; see §13). Revision 4 answers the scoped R3 diff review (bounded REJECT:
0 Critical, 1 Important, 3 Minor; see §14). Revision 5 answers the scoped R4 review (bounded
REJECT: 0 Critical, 1 Important, 5 Minor; see §15). This document authorizes nothing. The suite runs locally. It may run
against Production only after separate owner approval (§10).
**Governing plan:** [`ASSET_PICKER_DEMO_PREPARATION_PLAN.md` Phase 3](../../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md#phase-3-run-broad-production-browser-e2e)
**Base:** `main@26a07fe048d9950c5b7268b9763eaca543b7b7ef`, plus the React #418 fix on
`claude/phase3-hydration-and-prod-e2e`.
**Code:**
- `frontend/playwright.phase3.config.ts`
- `frontend/tests/phase3-e2e/`
- Offline unit tests in `frontend/tests/phase3-e2e/lib/__tests__/`, run by Vitest

## 1. Goal and non-goals

**Goal.** A reviewed, desktop-only Playwright suite covering every Phase 3 checklist item:
- 2-3 isolated users with materially different portfolios;
- signup, login, logout and re-login;
- empty portfolio, add, update, removal, review, save, cancellation, validation failure and
  persisted readback;
- optimistic-concurrency conflict;
- user and session isolation;
- Overview, Portfolio, Market Data, navigation and AI Insights.

It runs unchanged in two target modes: `local` (a docker-compose stack plus the static export served
on `localhost:3000`) and `production` (the two exact Production origins).

**Non-goals.**
- Narrow viewports: the owner fixed the demo contract at desktop.
- Server-side-only validation branches (400/422) that the UI cannot send. Contract and unit tests
  cover those.
- Exhausting rate limits in Production.
- Deleting accounts: no such endpoint exists.
- Reading Azure revisions from inside the suite: that is a separate owner-run step (§9).

## 2. Placement and isolation from existing suites

- **Config.** `frontend/playwright.phase3.config.ts` uses `testMatch: "phase3.spec.ts"`, so the Vitest
  files under `lib/__tests__` are never collected as Playwright specs.
- **No inheritance.** It inherits nothing from `tests/e2e`: no globalSetup, no storageState, and none
  of the synthetic projects.
- **Not wired in.** It is not added to any workflow. The default config's `testDir` is `tests/e2e`,
  so `npm run test:e2e` does not collect it.
- **Unit-tested modules.** Config/guards, pacing, verdict, failure classification, sanitization, value
  parsing and artifact handling are pure modules with Vitest tests.

## 3. Target modes and guards (`lib/config.ts`)

| Mode | Frontend origin | API origin |
|---|---|---|
| `local` | `http://localhost:3000` | `http://localhost:8080` |
| `production` | `https://vibhanshu-ai-portfolio.dev` | `https://api.vibhanshu-ai-portfolio.dev` |

**All modes.**
- The config refuses to load unless `P3_TARGET` is exactly `local` or `production`. Origins come only
  from the table; there is no override variable.
- `DEBUG` and `PWDEBUG` must be unset.
- `P3_WORK_DIR` must be an absolute path outside the repository.
- Every context blocks service workers, and Chromium runs with `--no-proxy-server`.
- A context-wide route guard aborts, and records as a violation, every request to any other origin.

**Production mode also refuses to start unless all of the following hold:**
- `P3_PRODUCTION_APPROVAL` equals `owner-approved-phase3-production-run`.
- `P3_IDENTITY_LIFECYCLE` is `retained`.
- The certification-account credentials are present.
- `P3_EMAIL_DOMAIN` is set explicitly.
- No negative control is set.
- `P3_AUTH_MIN_INTERVAL_MS` is not lowered below 13000.

**Run identity across processes.** Playwright re-evaluates the config in every worker, so the main
process pins the run id and generated credentials in its own environment before workers start
(`P3_RUN_ID`, `P3_RUN_DIR`, `P3_FRESH_PASSWORD`, plus the local certification credentials).

## 4. Identities and lifecycle (owner decision D1)

Three users, with **no ticker overlap**, so any cross-user leak is unambiguous:

| Id | Kind | Authentication | Declared baseline (retained end state) |
|---|---|---|---|
| `CERT_A` | retained certification account | API login only, token stored in the browser | AAPL 12, MSFT 4.5, NVDA 3, RELIANCE.NS 20 |
| `CERT_B` | retained certification account | API login only | TSLA 6, BTC-USD 0.35, ETH-USD 2.75, SOL-USD 40.125 |
| `FRESH` | created by this run through the signup UI | signup UI and login UI | empty → GOOGL 7, DOGE-USD 1500.5 |

The mix covers US equity, NSE (INR-quoted), crypto and fractional quantities. RELIANCE.NS is
unpriced locally, so `CERT_A` exercises partial valuation there.

**Credentials.**
- Retained passwords are sent only by an untraced Node-side login (`lib/api.ts`, Node `fetch`). They
  are never typed into the browser.
- `FRESH` gets a random per-run email and a random 24-character password. That password is the only
  one ever typed in the UI; it is held in process memory and the environment only, and the account is
  abandoned afterwards.

**Provisioning.**
- `local` mode signs the certification accounts up if login returns 401, using per-run emails, so no
  secret is needed.
- `production` mode never provisions. A 401 is a failure.

**Demo-account guard (review I4).**
- Every browser `POST /api/auth/login|signup` is inspected in memory before it leaves the browser.
- It is aborted, and fails the scenario, if the email is `demo@wealthtracker.dev` or is not one of
  the run's three identities.
- Before each UI submit, the scenario also asserts the email field's value. The Production login form
  is pre-filled with the demo credentials.
- The ledger records only the role, never the email.

**Lifecycle.**
- At S00 each certification account's prior state is recorded, then normalized to its baseline. S99
  restores the baseline at the end.
- A **global-teardown safety net** repeats the restoration whenever S99 did not confirm it, and writes
  `restoration-teardown.json` as `restored`, `not_needed` or `unconfirmed` (review I3).
- **Consequence:** every Production run permanently adds one `FRESH` account holding two holdings,
  because there is no deletion path.

## 5. Scenarios (`phase3.spec.ts`)

**Execution model.**
- One file, one worker, run in declaration order.
- **Not** serial mode. After a failure, Playwright restarts the worker and continues, so later
  scenarios and S99 still run.
- Each scenario re-establishes its preconditions through the public API (`ensureHoldings`: one
  version-bearing PUT, verified by readback, never retried).
- Sessions are acquired lazily through the pacer, so a restarted worker re-authenticates.
- Every browser context is closed at the end of its scenario, which stops refetch timers.

| Id | Scenario | Key oracles |
|---|---|---|
| S00 | Preflight | Served build ID read from `/login` (and compared with `P3_EXPECTED_BUILD_ID` if set); certification logins; prior state recorded; baselines normalized and read back; `provenance.json` |
| S01 | Signup validation | Each case keeps the other two fields valid. `a@b` → "Enter a valid email address."; an 11-character password → the password message; a whitespace-only name → "Name is required.". Zero signup requests; still on `/signup` |
| S02 | Signup success, empty portfolio | Exactly one signup, status 201. Lands on `/overview`; the stored session is `FRESH`'s; the header shows the name. Overview total `$0.00`. The Portfolio table has zero holding rows, and its copy is recorded as a UX finding: it reads "No holdings match your filter.", the filter-empty string (review I1). Market Data shows "No market data available.". Readback: version 0 with 0 holdings |
| S03 | Duplicate signup | Fresh context; the email in a different case → exactly one signup, status 409, "An account with this email already exists.", no stored session |
| S04 | Login, logout, re-login | A wrong password → 401 and "Invalid username or password.", no session. The correct password → `/overview`. **Sign out** → `/login`, storage cleared, and `/portfolio` redirects to `/login`. Re-login → the same user id; exactly 3 login requests. D7 observation: whether the pre-logout token is still accepted |
| S05 | Empty → add | Picker: select GOOGL and DOGE-USD, enter quantities. Review "Added — 2". One PUT, status 200, carrying the loaded `expectedVersion` and the typed strings. "Holdings saved."; the table shows both; readback exact with version +1; no automatic retry across a 31 s fast-forwarded clock window |
| S06 | Cancellation | Escape and the X button each discard edits; reopening shows the persisted values; zero PUTs; readback and version unchanged |
| S07 | Validation failure | `0`, `-1`, `abc`, `1.123456789`, `123456789012` and an empty value each give `aria-invalid="true"`, the validator's exact message as the accessible description, and a disabled "Review changes". A valid value clears the error. Zero PUTs; readback unchanged |
| S08 | Update, remove, add | Review: 1 added, 1 changed, 1 removed, 2 unchanged. One PUT, status 200, with the full desired set; readback exact with version +1; the table matches the readback |
| S09 | Conflict | Two contexts, each from its **own** API login. Both pickers are seeded (the Browse step has rendered) before context 1 saves a real change: TSLA 6→7, status 200. Context 2's stale save: exactly one PUT, status 409, "Your portfolio changed elsewhere", read-only draft containing its edit, no inputs, "Discard & close" offered. No second PUT across a fast-forwarded 31 s window. Readback holds only context 1's change. **Reload latest & start over** shows TSLA 7 and BTC-USD 0.35. `CERT_A` is unchanged |
| S10 | Isolation | See below |
| S11 | Pages | See below |
| S12 | Chat | One message → exactly one `POST /api/chat`, status 200; the reply text appears in the transcript; no rate-limit countdown. Limitation recorded: the local stack uses deterministic mocks, and in Production the reply content cannot distinguish the LLM path from its fallback (review M9) |
| S13 | Non-demo reset control | Records whether "Reset Demo Portfolio" is visible to a non-demo user, and never clicks it. If visible, the test attaches an **expected-defect** annotation, and the verdict becomes `PASS_WITH_EXPECTED_DEFECTS` instead of failing (review I7). Owner decision D5 converts it into a failing expectation or an accepted behaviour |
| S99 | Final state | Certification accounts restored and read back; `FRESH`'s retained state read back; `final-state.json`, `restoration.json`, and the auth-request count; then the post-run **secret scan** of all shareable artifacts |

**S10 — user and session isolation.**
- `CERT_A` and `CERT_B` are signed in concurrently. Portfolio and Market Data each show exactly the
  user's own tickers.
- An API call with `CERT_A`'s token plus a spoofed `X-User-Id` for `CERT_B` returns `CERT_A`'s
  portfolio.
- **Same-tab switch:** `CERT_A` signs out, then `FRESH` logs in through the UI.
  - An observer installed with `context.addInitScript` reports through `exposeBinding` any `CERT_A`
    ticker shown in `main`, the user-scoped region, while a different user's session is stored.
  - The header is excluded, for two reasons:
    - While a user's analytics loads, its ticker strip falls back to the **global** market summary,
      which returns every priced ticker for every user. A ticker name there cannot distinguish a
      leak from global data (observed locally as AAPL/MSFT in the header in every run after price
      seeding).
    - The client-cache leak path is closed by construction: the header's queries use per-user keys
      (`portfolioKeys.analytics(userId)`, `insightKeys.marketSummary(userId)`). A server-side
      analytics leak would still surface in `main`, through the Overview best/worst-performer cards.
  - Each report reaches Node immediately, so it survives a document swap, and every document sends a
    heartbeat (review I6).
  - The oracle is zero reports and at least two heartbeats: the switch loads two documents.

**S11 — pages, navigation, presentation.** Runs for each user at 1280×800, 1440×900 and 1920×1080.

- **Overview** (review I10):
  - The total equals the summary total to the cent.
  - The allocation card's "$X total" equals the analytics total, when that total is above 0.
  - The summary and analytics totals are compared. The Overview shows both numbers side by side.
    - `PortfolioAnalyticsService.getAnalytics` is `@Cacheable("portfolio-analytics", key =
      "#userId")`, with a 30 s Caffeine TTL on the local, default and azure profiles, and no holdings
      write evicts it. The summary is an uncached SQL read.
    - So for up to 30 s after a holdings write, analytics can return the pre-write result while the
      summary is current (finding F9, owner decision D10).
    - The run logs every successful holdings write in `holdings-writes.jsonl`: Node setup writes,
      and browser saves identified by the token's `sub`.
    - A write within the TTL before the page's analytics request only makes the cache a possible
      cause. `CERT_B`'s reads always follow S09's save by less than 30 s, so the window alone would
      excuse any `CERT_B` divergence (review R4 I-4).
    - So inside the window the suite waits until every pre-write cache entry has expired (at most
      31 s after the write), then re-reads both endpoints from Node. The disagreement is recorded as
      the known defect `analytics-cache-stale-after-holdings-write` only if analytics has converged
      on the summary total the page showed. Otherwise it FAILs.
    - With no write inside the window, a disagreement FAILs at once. Either way, an immediate Node
      re-read of both endpoints is ledgered.
    - A price or FX refresh changes valuation inputs without a write, so the same cache can lag it
      too. That case is not attributed and FAILs. It fails safe and is rare: the local price refresh
      runs hourly at :00, Production's `market-data-refresh-job` daily at 08:00 UTC, and the FX
      cache is evicted daily at 06:00. D10 covers scheduling the Production run away from them.
  - The 24h card equals the independently summed analytics `change24hAbsolute`.
  - The allocation legend has one slice per `displayAssetClass`, and its percentages sum to 100
    within rounding when the total is above 0.
  - The performance-coverage label is shown if and only if `performanceCoverage.partial` holds (review
    I2: this is the only "Partial" label in the UI).
  - No horizontal overflow.
- **Portfolio:**
  - Rows equal the readback tickers.
  - The freshness strip's summary equals the API state and counts ("All prices fresh" or
    "N holdings missing/stale/unknown").
  - The **Details** popover shows the API timestamp, formatted in the browser with the popover's
    documented format, plus the per-state counts (review I8).
  - No component reads `partialValuation`, so nothing in the UI signals it. The strip reports price
    freshness only. When the API reports partial valuation, S11 records the expected defect
    `partial-valuation-not-presented` (owner decision D9). A fix must replace that record with an
    assertion on its own presentation.
  - Each row's 24h cell matches analytics.
- **Market Data:**
  - Rows equal the readback tickers.
  - Each 24h cell matches analytics **and** equals the Portfolio cell for the same ticker. This is the
    plan's cross-page 24h check against real data.
- **AI Insights:** the grid shows one card per ticker in the page's own market-summary payload.
- **Navigation:** at 1440 every sidebar link reaches its route.
- **Screenshots:** full-page, per user, viewport and page.
- **Data source for presentation oracles.** Each check compares the UI with the summary, analytics
  or market-summary payload that the page itself received. It waits for the response body and a
  render frame, and re-reads if a refetch lands in between. Separate reads can legitimately differ:
  analytics can lag a holdings write by up to 30 s (F9), and a price or FX refresh can land between
  reads.
  - Persisted state stays independent: exact API readback in S05, S08, S09 and S99.
  - S11's ticker sets come from an independent readback, and any movement between the independent
    read and the page's read is ledgered.
- **Finding (F2, D9):** partial valuation has no UI presentation anywhere. It is recorded as the
  expected defect `partial-valuation-not-presented` whenever the API reports partial valuation.

## 6. Common oracles (every context, every scenario)

- **Zero page errors.** This includes React hydration errors, so #418 stays fixed.
- **Zero console errors**, except one narrowly classified noise class: 404s for the dashboard's Next 16
  segment-prefetch files, `/<route>/__next.!KGRhc2hib2FyZCk.<route>[.__PAGE__].txt?_rsc=…`, on the
  frontend origin.
  - A console line counts as noise only when the same URL was also observed as an HTTP 404 (review
    M4).
  - Noise is counted in the ledger, never dropped.
- **Zero HTTP responses ≥ 400**, except failures the scenario **declares**: S03's 409, S04's 401 and
  S09's 409. Each declaration exempts exactly one occurrence, and an unobserved declaration also fails.
- **Zero non-target-origin requests.**
- **Every auth request used a run identity**, never the demo account.

## 7. Evidence and verdict

`P3_WORK_DIR/<runId>/` contains:

| Artifact | Contents | Sensitivity |
|---|---|---|
| `ledger.jsonl` | One line per assertion (scenario, check, pass, detail) or observation | Shareable |
| `network.jsonl` | Method, origin, path, query **keys**, status, resource type, scenario, role | Shareable |
| `final-state.json`, `restoration.json` | Per-user version and holdings; restoration status | Shareable |
| `provenance.json` | Target, run id, served build ID, `VERSION`, suite SHA and dirty flag, viewports, Node version, **allowlisted** environment values plus presence-only booleans for every secret-bearing variable (review C1) | Shareable |
| `auth-requests.jsonl` | Timestamp, scenario, source (api/browser/teardown), path | Shareable |
| `screenshots/` | Named checkpoints | Shows holdings and, in the header, the certification users' **names and emails** (D2) |
| `pw-output/` | A trace for every context in `traces/`, and failure videos in `videos/` (see below) | **Sensitive**: bearer tokens, and the `FRESH` password as typed. Never commit or publish; delete after review (D6) |
| `verdict.json` | Written by the reporter | Shareable |
| `restoration-teardown.json` | Only if the safety net ran | Shareable |

**Traces and videos.**
- Recorded explicitly per context: `context.tracing` plus `recordVideo`, in `finalizeContext`.
- The runner's own `trace` and `video` options are off. Its implicit tracing of
  `browser.newContext()` contexts hung at teardown whenever a context ended on a live dashboard:
  a truncated `trace.zip` and a test timeout, bisected locally.
- A failure to save a trace or video is ledgered, never silent.
- Contexts that S11 finalizes mid-test delete their video because the test has not failed yet. Only a
  failing context's video is kept.

**Controls on shareable files.**
- Every write to a shareable file passes a `SecretRegistry` check: registered passwords and tokens,
  JWT shapes and email shapes are all rejected.
- S99 then greps every text artifact outside `pw-output/` for every registered secret.
- Two files are appended directly without the registry check, because their content is constant or
  numeric: every line of `auth-requests.jsonl` (timestamp, scenario, source and path, from the suite
  and the global teardown alike), and `holdings-writes.jsonl` (epoch ms, user UUID, scenario id).
  The S99 scan still covers both.

**Verdict** (`lib/verdict.ts`, written by `lib/verdict-reporter.ts`):
- **FAIL** if any scenario failed or timed out, or if a scenario recorded an expected-defect id
  outside the known list (review R2 I-2).
- **INCOMPLETE** if any expected scenario (S00–S13, S99) was skipped, uncollected or interrupted, if
  an unexpected id appeared, or if any grep, project or shard filter was active.
- **PASS_WITH_EXPECTED_DEFECTS** if everything passed and the only recorded expected defects are the
  known ids (`KNOWN_EXPECTED_DEFECTS` in `lib/verdict.ts`):
  - `non-demo-reset-control-visible` (S13, D5);
  - `partial-valuation-not-presented` (S11, D9);
  - `analytics-cache-stale-after-holdings-write` (S11, D10; only when the run's own write log places
    the analytics read inside the cache TTL and analytics converges on the summary total once the
    TTL has passed).
- **PASS** otherwise.

Skips never count as passes, and an expected defect never softens FAIL or INCOMPLETE.

## 8. Production rate limits

| Bucket | Keyed by | Limits | Pacing |
|---|---|---|---|
| Auth (login + signup) | IP | burst 60, 12 tokens per request, refill 1/s, which is about 5 attempts then 1 per 12 s | Every auth request, browser and Node, passes one file-backed pacer, ≥13 s apart in Production. Browser requests are held in the route handler until the pacer allows them; UI waits for auth responses allow that hold plus 20 s |
| Strict (insights, chat, and the header's market summary on every dashboard load) | user (JWT `sub`) | burst 30, 6 tokens per request, refill 1/s | Route-level pacing keyed by the bearer token's `sub`, as Production keys it, so it follows the signed-in user even across S10's switch. ≥6.5 s apart in Production (review I5). Contexts close at scenario end |
| Standard (portfolio, assets, market) | user | 10/s, burst 20 | Headroom is ample at one user action at a time |

- **Auth requests in an uninterrupted Production run: 9.** They are:
  - S00: 2 logins;
  - S02: 1 signup;
  - S03: 1 duplicate signup;
  - S04: 3 logins;
  - S09: 1 second login;
  - S10: 1 UI login.
- Each worker restart after a failure adds up to 3 logins; the teardown safety net adds 2, logged
  with source `teardown`. S99 ledgers the count.
- A full Production run should take well under the 1-hour JWT lifetime. If a token expired mid-run, it
  would surface as a failure, not a silent re-login.

## 9. What the suite cannot see

- The Azure revisions and digests of the backend apps.
- For Production, the owner runs read-only `az containerapp revision list` / `show` commands before
  and after, and attaches them to the evidence.
- The suite records only what the browser can observe: the served frontend build ID, plus `VERSION`
  and the suite SHA.

## 10. Owner decisions required before any Production run

- **D1 — Identity lifecycle.**
  - Approve retained `CERT_A` and `CERT_B`, created by the owner, since the suite never provisions in
    Production.
  - Accept one permanent `FRESH` account per run, or require a cleanup mechanism first.
- **D2 — Email domain and account names.** Choose non-personal addresses; they appear in screenshots.
- **D3 — Deploy the candidate first.** Phase 2, the #418 fix, and backend changes since the last
  backend build are not deployed. Choose a frontend-only or a full candidate deploy.
- **D4 — One real chat request per run (S12)**, or skip it. Skipping makes the verdict INCOMPLETE.
- **D5 — Non-demo reset control.** Treat it as a defect (the recommendation) or accept it as intended.
- **D6 — Handling of `pw-output/`** (tokens and the typed `FRESH` password).
- **D7 — Logout does not revoke the JWT** (1-hour expiry). Treat it as accepted stateless-JWT behaviour
  or as a Phase 4 defect.
- **D8 — Operator.** The Production run is owner-operated on the owner's machine (review I9).
  - The owner enters the certification credentials into their own shell session, for example with a
    masked `Read-Host` in PowerShell.
  - The implementer never sees them.
- **D9 — Partial valuation has no UI presentation.** When a holding cannot be valued (for example, no
  FX rate), totals silently exclude it. Treat it as a Phase 4 defect (the recommendation) or accept it
  as intended.
- **D10 — Analytics can be stale for up to 30 s after a holdings save.** portfolio-service caches
  analytics per user for 30 s, and no holdings write evicts it; the summary is uncached.
  - For up to 30 s after any save, analytics-driven views can show pre-save numbers beside a current
    Overview total card. Those views are the allocation donut, the 24h card, the Portfolio holding
    values and 24h cells, Market Data's 24h column and the header ticker.
  - Observed locally: $42,147.14 (summary, current) against $44,349.79 (analytics, pre-save).
  - Any valuation-input change opens the same window: a price refresh or the FX cache eviction as
    well as a save. The suite classifies only the save case; the others FAIL.
  - Treat it as a Phase 4 defect (the recommendation: evict the user's analytics cache on a holdings
    write) or accept it.
  - Either way, schedule the Production run away from the refresh jobs: not between 05:50 and 06:10
    UTC (FX eviction), and not from 07:50 UTC until that day's `market-data-refresh-job` execution
    has finished.

## 11. Local validation plan

1. `docker compose -p wmpt-phase3 up` builds the backend from this branch, with
   `INTERNAL_API_KEY` and `TF_VAR_internal_api_key` unset in the invoking shell (their presence is
   recorded).
2. Build a flagged static export (`NEXT_PUBLIC_ENABLE_ASSET_PICKER=true`,
   `NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL=true`, `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080`).
   The config's local-only `webServer` serves it on `localhost:3000`.
3. Run the full suite with `P3_TARGET=local`, uncontended.
4. **Negative controls.** Each must make its oracle fail. NC1–NC4 and NC7–NC11 are set with
   `P3_NEGATIVE_CONTROL`, local only; NC5 and NC6 are run-level.

   | Control | Fault | Oracle that must fail |
   |---|---|---|
   | NC1 | Init script throws in S02 | Page-error oracle |
   | NC2 | S05's PUT fulfilled with a fake 200 and never forwarded | Readback oracle |
   | NC3 | `CERT_B`'s GET `/api/portfolio` answered with `CERT_A`'s holdings labelled with `CERT_B`'s user id, in S10 | Isolation oracle |
   | NC4 | First summary GET in S06 answered 500 | Unexpected-HTTP oracle |
   | NC5 | A `--grep`-filtered run | Verdict INCOMPLETE |
   | NC6 | Served against the unfixed `8f2c4cf7` export | Page-error oracle, with #418 |
   | NC7 | A registered secret written to a shareable file, bypassing the registry, in S99 | Secret-scan oracle |
   | NC8 | A `CERT_A` ticker injected into `FRESH`'s page in S10 | Leak observer |
   | NC9 | A second PUT injected from context 2 in S09 | No-retry counter |
   | NC10 | `FRESH`'s analytics total raised by 1000 in S11, with no holdings write inside the cache TTL (the control waits the TTL out first) | Summary/analytics agreement (must FAIL, not be recorded as the known defect) |
   | NC11 | A real, unchanged holdings write for `FRESH` just before S11, so the cache window is open; `FRESH`'s analytics total raised by 1000 in the page and in the Node re-reads | Post-expiry convergence (must FAIL, not be recorded as the known defect) |

   An NC3 variant that returned `CERT_A`'s body unchanged never rendered **Edit Holdings**: the
   frontend rejects a portfolio whose `userId` does not match the session. That is a real
   client-side isolation guard. The control therefore simulates the leak that would pass it.
5. Independent review of the suite and the local evidence.

## 12. Revision 2 — disposition of the design review

| Finding | Disposition |
|---|---|
| C1 provenance environment leak | Allowlisted values plus presence booleans; per-write registry; post-run secret scan; NC7 |
| I1 wrong S02 empty-state string | S02 asserts zero rows and records the filter-empty copy as a UX finding |
| I2 non-existent partial-valuation label | Coverage label tied to `performanceCoverage.partial`. No UI signals `partialValuation` (the freshness strip does not), so it is recorded as the expected defect `partial-valuation-not-presented`, pending owner decision D9 |
| I3 restoration skipped on failure | Non-serial execution, so S99 still runs, plus the global-teardown safety net with a restoration status |
| I4 demo account unguarded | Route-level identity guard, email-field assertions before every UI submit (S02, S03, S04, S10), role-only ledger |
| I5 strict bucket unbudgeted | Per-user strict pacing, contexts closed per scenario, exact auth count 9 |
| I6 DOM observer lying-harness risk | `addInitScript` plus `exposeBinding` reports and heartbeats; NC8 positive control; NC9 for the no-retry counter |
| I7 S13 makes PASS impossible | Expected-defect annotation and the `PASS_WITH_EXPECTED_DEFECTS` verdict |
| I8 freshness equality unimplementable | Details popover timestamp and counts compared exactly |
| I9 operator unstated | D8 |
| I10 Overview visibility-only | Total, 24h, allocation and coverage oracles |
| M1–M10 | Addressed in §5, §3, §2, §6, §8, §7, §10, §11 and S12 respectively |

## 13. Revision 3 — disposition of the implementation review (R2)

| Finding | Disposition |
|---|---|
| I-1 doc claimed the strip signals partial valuation; no D9 | §5 and §12 corrected; D9 added to §10; defect ids listed in §7 |
| I-2 expected-defect escape hatch open-ended | `KNOWN_EXPECTED_DEFECTS`; any other id makes the verdict FAIL (`unknownDefects`); unit-tested |
| M1 traces "always on" vs `trace: "off"` | §7 documents the explicit mechanism, the bisected hang and S11's mid-test video deletion |
| M2 swallowed trace/video failures | Ledgered as observations |
| M3 context leak on setup failure | Setup wrapped; the context is closed if tracing, the init script or the monitor fails |
| M4 `readAgainstLatest` DOM-lag race | Waits for `response.finished()` and two animation frames before reading |
| M5 speculative partial-valuation copy detection | Removed; `signalled: false` recorded until a fix provides its own presentation |
| M6 S13 ambiguous when the flag is off | Observation carries an explicit caveat that "not visible" may mean the flag is off |
| M7 email-field assertion missing in S02/S03 | Added |
| M8 stale NC3 wording | §11 updated, including the observed client-side userId guard |
| M9 header exclusion half-explained | Second reason added in §5 |
| M10 auth waits shorter than the pacer hold | Auth-related waits use the pacer interval plus 20 s |
| M11 teardown auth requests unlogged | Logged with source `teardown` |
| M12 strict-key mis-keying; weak oracles | Strict pacing keyed by JWT `sub`; heartbeats ≥ 2; AI Insights cards compared with the page's payload; summary/analytics and allocation totals cross-checked |
| Post-R2 | The new summary/analytics cross-check caught a real divergence in the final run (F9). The root cause first recorded here was wrong; R3 corrected it (see §14) |

## 14. Revision 4 — disposition of the scoped R3 review

| Finding | Disposition |
|---|---|
| I-3 F9/D10 root cause reversed | Corrected. Analytics is `@Cacheable` for 30 s with no eviction on holdings writes, so analytics was stale (pre-write $44,349.79) and the summary was current ($42,147.14). The failed run's S08/S09 writes were 20–26 s before the S11 read. Id renamed `analytics-cache-stale-after-holdings-write`. §5, §7, §10 D10 and the spec comment rewritten |
| I-3 non-deterministic classifier | A disagreement is recorded as the known defect only when `holdings-writes.jsonl` shows a write for that user within the TTL before the page's analytics request. Otherwise it FAILs. Node re-reads of both endpoints are ledgered. NC10 proves an unexplained disagreement fails. `lib/cache-window.ts` is unit-tested |
| M-a known-id acceptance untested | Unit test accepts all three known ids |
| M-b teardown append outside the registry | Documented in §7: constant content, covered by the S99 scan |
| M-c allocation total with an all-unpriced portfolio | Guarded on analytics total above 0 |

## 15. Revision 5 — disposition of the scoped R4 review

| Finding | Disposition |
|---|---|
| I-4 the classifier is disarmed for `CERT_B` on every full run | Fixed with the review's option (a), which keeps D10 observable. Inside the window the suite waits until every pre-write entry has expired, re-reads both endpoints from Node, and records the known defect only if analytics converges on the summary total the page showed. It also requires the summary to be unchanged, so a transiently wrong summary is not excused. NC11 opens the window with a real write and injects the divergence into both the page and the Node re-reads; it must FAIL on the convergence check |
| M-d price- and FX-triggered staleness FAILs | Documented in §5 and D10, with the refresh schedules. D10 adds the Production scheduling constraint |
| M-e `CLOCK_MARGIN_MS` covers no clock | Removed. The attribution window is the bare TTL, with the reasoning in `lib/cache-window.ts`. A 1 s slack remains on waits only, where longer is safe |
| M-f the analytics read time came from a possibly newer response | `readAgainstLatest` returns the response whose body was compared, and S11 times that one |
| M-g every `auth-requests.jsonl` line bypasses the registry | §7 wording corrected |
| M-h `msSinceLastWrite` was `null` when every write postdated the read | `msSinceLastWriteBefore` returns `null` only when no write precedes the read, and is unit-tested |
| Rationale | The presentation-oracle note in §5 and the S11 spec comment no longer blame a background price refresh. The observed cause was the analytics cache |
