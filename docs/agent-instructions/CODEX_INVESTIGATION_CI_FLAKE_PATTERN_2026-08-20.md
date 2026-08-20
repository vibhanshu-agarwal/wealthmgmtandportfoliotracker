# Codex Investigation — Recurring CI Check-Reliability Failures

**Date:** 2026-08-20
**Prepared for:** Codex (independent investigation)
**Requested by:** repo owner — "these flakes are appearing too often," wants a second opinion
  before anything is fixed, not confirmation of Claude's hypothesis
**Scope:** two live issues on `feat/b1-fixture-identity` (PR #121), plus the recurring pattern
  they belong to. **Do not just verify Claude's conclusions — re-derive them from evidence. Where
  you disagree, say so and show why.**

---

## How to read this note

Two issues are blocking PR #121 (`feat/b1-fixture-identity` → `main`) from merging. Both were found
today while chasing what looked, at first, like a single ordinary CI failure. It turned out to be
two unrelated defects layered on top of each other, and the first hypothesis about the first one
was wrong and had to be retracted. That reversal — and the fact that this is at least the fourth or
fifth distinct instance of a similarly-shaped defect on this project in recent weeks — is why an
independent investigation was requested instead of proceeding on Claude's read of the evidence.

Everything below marked **VERIFIED** was checked directly against the repo or a live CI run.
Everything marked **HYPOTHESIS** is Claude's working theory and needs your own verification, not
your endorsement.

---

## Issue 1 — `mocked-chaos.spec.ts:30` fails deterministically, caused by the Wave 0 identity switch

### The failure

`frontend/tests/e2e/mocked-chaos.spec.ts:30`, test `"429 Too Many Requests handles exponential
backoff and limits retries"`, asserts:

```ts
expect(requestCount).toBeLessThanOrEqual(3);
```

where `requestCount` counts calls into a mocked `page.route("**/api/market/**", ...)` handler that
always returns 429, over a fixed 5-second `page.waitForTimeout(5000)` window.

**VERIFIED** — On PR #121, run [32323542730](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32323542730),
this failed twice in a row (initial run and one rerun), both times with:

```
Expected: <= 3
Received:    7
```

Identical received value both times, ~6.0–6.1s duration both times. Not intermittent — reproduces
every run so far.

### First hypothesis, retracted

The first read was "pre-existing, unrelated, timing-based flake — `git diff origin/main` on the
spec file is empty, so this branch didn't touch it." **That was wrong**, and here is the evidence
that overturned it:

**VERIFIED** — `docker-build-verify` was green on `main` at `8d41428` (the commit this branch is
based on), immediately before this branch, with this exact test passing:

```
✓  13 [chromium] › tests/e2e/mocked-chaos.spec.ts:30:7 › ... (5.9s)
```
(main run [32320943212](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32320943212), job 96286936096)

So the test passed on `main` and fails deterministically on this branch, on a file the branch never
touched. The branch must be changing something the test depends on indirectly.

### Current hypothesis — batch count, not retry count

**VERIFIED** — `frontend/tests/e2e/helpers/browser-auth.ts`'s `installGatewaySessionInitScript`
(used in this spec's `beforeEach`) is the shared login helper PR #121 modifies as part of Wave 0.
The *mechanism* is unchanged (same `fetch` POST to `/api/auth/login`); the *identity* changed from
the old dev user (`dev@local` / `00000000-0000-0000-0000-000000000001`, small V3-seed portfolio) to
the Golden-State E2E user (`00000000-0000-0000-0000-000000000e2e`, ~159 holdings).

**VERIFIED** — `frontend/src/components/layout/QueryProvider.tsx`, `defaultQueryRetry` (line ~20):
returns `false` immediately for a `RateLimitError` (429) or any 4xx status match, before the
`failureCount < 2` check is even reached. **This codebase does not retry 429s at all.** So
`requestCount` in the test cannot be counting exponential-backoff retries — there are none.

**VERIFIED (call chain)** — `MarketDataTable` component
(`frontend/src/components/market/MarketDataPageContent.tsx`) calls `usePortfolio()`
(`frontend/src/lib/hooks/usePortfolio.ts:55`, `queryFn: () => fetchPortfolio(userId, token)`).
`fetchPortfolio` (`frontend/src/lib/api/portfolio.ts:179`) builds `tickers` from the authenticated
user's holdings (line 202) and calls `loadMarketPrices(tickers, token)` (line 89, called at line 203).
`loadMarketPrices` batches: `MARKET_PRICE_BATCH_SIZE = 25` (line 59), tickers sliced into chunks of
25 (lines 101–103), batches fetched **concurrently** via `Promise.allSettled` (lines 109–110), each
batch a separate HTTP call matching the test's `**/api/market/**` mock.

**HYPOTHESIS, arithmetic only, not independently re-verified against a live trace**:
`ceil(159 / 25) = 7` — exactly the observed `Received: 7`. If the Golden-State user's holdings count
is confirmed at 159 (stated in `docs/superpowers/specs/2026-08-19-b1-implementation-prerequisites.md`
line ~222, itself worth re-checking against the live seeded portfolio rather than trusting the doc),
this would fully explain the failure as **7 concurrent batch requests, each independently 429'd and
correctly not retried — not retry spam.**

**What Claude did NOT do**: pull the actual trace.zip or HAR from the failing run to directly count
and inspect the 7 requests' URLs/query strings. The batch-math explanation is inferred from
independently-verified code paths, not from directly observing the 7 requests. **Please verify this
directly** — the trace.zip should still be attached to the failed run (if the sanitizer's exit-1
didn't prevent upload — see Issue 2, this may not be recoverable from that run, in which case
reproduce locally: check out `feat/b1-fixture-identity`, run the Docker Compose stack, seed the
Golden-State user, and run `npx playwright test tests/e2e/mocked-chaos.spec.ts --project=chromium
--reporter=list --trace on` to capture a fresh trace, then inspect it directly.)

### Open question for Codex, not decided

If the batch-count hypothesis holds, the test's assertion is simply wrong for the new fixture
identity — it needs to assert something that reflects "N batches, no retries, UI survives," not a
hardcoded `<=3` that was only ever true because the old dev user had 3 holdings. Two options were
identified but **neither implemented, pending your read**:

1. Fix the assertion to derive the expected count from the actual batch size and holdings count
   (`Math.ceil(uniqueTickerCount / MARKET_PRICE_BATCH_SIZE)`) rather than hardcoding a number that
   is an accident of whichever identity happens to be authenticated.
2. Quarantine the test with an accurate RCA note (the file already has this pattern — see the
   `test.skip` at `mocked-chaos.spec.ts:63`, "502 Bad Gateway fallback") and let a follow-up
   properly redesign what it should assert, since it was never actually exercising retry/backoff
   behavior in the first place — the docblock and the code have been describing different things.

Do not assume either is right without checking whether other tests or production code depend on the
market-price batch size staying exactly 25, or on batching being concurrent (`Promise.allSettled`)
rather than sequential — changing test expectations without understanding those constraints could
paper over a real problem instead of fixing the test.

---

## Issue 2 — Playwright artifact sanitizer fails closed on non-screenshot binary content

This is a separately spawned/flagged task (task_id `task_aab16b9f`), reproduced here in full so you
have everything in one place.

### Background

`.github/actions/sanitize-playwright-artifacts/sanitize.js` is a fail-closed secret scanner for
Playwright CI artifacts, built from `.kiro/specs/rca-playwright-artifact-secret-exposure-and-rate-limit-flake/`.
When it cannot fully UTF-8-decode a zip entry inside a trace archive, it returns
`{ outcome: "B", reason: "UNINSPECTABLE_ENTRY" }` (`sanitize.js` ~line 455), which throws and aborts
the whole sanitize step. The calling workflows gate artifact upload on
`steps.sanitize.outcome == 'success'`, so this blocks upload entirely — by design: if the scanner
can't prove an entry is clean, it refuses to ship it. **Nothing leaks. The job just goes red.**

### What happened on PR #121

**VERIFIED** — Wave 0 wired `E2E_TEST_USER_PASSWORD` into two jobs
(`ci-verification.yml`'s `docker-build-verify`, `frontend-e2e-integration.yml`'s `e2e-full-stack`)
that previously had no real secret to protect. `static-guard`'s
`check-sanitizer-secret-wiring.js` correctly caught that both jobs' sanitizer steps were still
`mode: fallback-only` (which can only scrub known default passwords, not a real secret it's never
given) and failed the PR. This was fixed (commit `04fe519`, already pushed) by switching both to
`mode: live-secret` with the real secret expression, matching how `synthetic-monitoring.yml` already
does it. **This part is closed and correct — not part of what needs investigating.**

### The actual open defect

After that fix, `docker-build-verify`'s sanitizer step (now correctly in `live-secret` mode) hit
`UNINSPECTABLE_ENTRY` and aborted, triggered by the Issue-1 test failure producing a real
`trace.zip` for the sanitizer to scan (the step only runs `if: failure()`).

**VERIFIED** — `.kiro/specs/rca-playwright-artifact-secret-exposure-and-rate-limit-flake/design.md`
already documents fighting this exact class of problem once: *"any ZIP containing a screenshot entry
is Outcome B regardless of which test produced it"* (design.md ~line 254), fixed by setting
`trace: { mode: "retain-on-failure", screenshots: false }` globally in `frontend/playwright.config.ts`
(confirmed present, line 22, on this branch).

**HYPOTHESIS, not verified by directly inspecting a trace.zip's internal entries**: that fix
addresses trace *snapshot screenshots* specifically. Playwright traces also embed **network resource
bodies** (fonts, icons, images) captured during real page navigation when snapshot capture is on
(the default), independent of the `screenshots` flag. `mocked-chaos.spec.ts:30` calls
`page.goto("/market-data")` against a real Next.js page, which very plausibly loads at least one
binary resource (a favicon, a font file) during navigation — a second binary-content source the
existing fix doesn't reach.

**Please verify this directly** rather than accept the inference: pull the actual `trace.zip` from a
failing run (or reproduce locally per the repro command in Issue 1) and inspect its entries — either
via `unzip -l trace.zip` plus manual inspection of suspect entries, or by adding temporary logging to
`sanitize.js`'s decode-failure path to print which entry name/path triggered `UNINSPECTABLE_ENTRY`.
Do not guess between "it's a font," "it's an icon," "it's something else entirely" — find the actual
entry.

### What needs deciding, not just fixing

The existing sanitizer test suite (`.github/actions/sanitize-playwright-artifacts/test/`:
`structured-scan.test.js`, `classify.test.js`, others) has **no test case producing
`UNINSPECTABLE_ENTRY` from realistic trace content** — checked, zero hits. That is presumably why
this was never caught before now: the sanitizer-canary fixture apparently doesn't exercise a trace
with this kind of embedded resource. Whatever fix you land on needs a regression test that would
have caught this, not just a patch to the specific entry type found.

Read the full RCA `design.md` and `tasks.md` before proposing a fix — this project has already
rejected some approaches to sanitizer permissiveness for good reasons (documented in that spec), and
a fix that "just allowlist binary content" without understanding why the fail-closed design exists
could reopen the exact secret-exposure risk the RCA was written to close. The correct fix is more
likely something like content-sniffing known-safe binary formats (magic-byte checks for common font/
image formats) rather than decodability as the sole test — but that's a proposal, not a decision;
make your own call and show your reasoning.

---

## The pattern this fits — why a second opinion was requested

The repo owner's framing: "these flakes are appearing too often." For context, not as something to
re-litigate, here is what's been observed recently that fits the same shape — **a check reports
pass/fail without actually testing the thing it claims to test, or fails closed on a codepath nobody
exercised until now:**

1. **`JwtSecretAlignmentPropertyTest`** (closed, PR #119, merged `86047ca`) — asserted JWT
   secret-alignment by sending 10 requests through the full HTTP filter chain (Redis rate limiter,
   routing, a deliberately-absent downstream service) and accepting any status other than 401. Failed
   in CI on a **docs-only** PR (#116) with a 5.011s timeout on request 8 of 10 — nothing to do with
   JWT secrets. Root cause was never fully isolated (Redis latency vs. runner scheduling vs.
   something else), but the fix — asserting the decoder contract directly, with an explicit
   negative case (wrong secret must be rejected) — made the test's pass/fail actually track its
   claim. 12 tests, 3.3s, no container, versus the old 10 tests, 11.7s, Testcontainers Redis.

2. **`ProductionRateLimitingIntegrationTest`** — failed in CI (2026-08-14, run `31811572808`) on a
   **docs-only** commit to `main`. Fixed by `832ab0d`, "Prove the production rate-limit burst test
   occupies a single Redis second" — a timing-window defect in the same Redis-rate-limiter area as
   above.

3. **A fail-open shell guard**, caught before it shipped, documented in
   `docs/superpowers/specs/2026-08-19-b1-implementation-prerequisites.md` §1.2: a rebase-cleanliness
   check piped through `grep ... || true`, where `|| true` swallowed the *entire pipeline's* exit
   status, so a bad git ref produced `fatal: ambiguous argument`, left the diff variable empty, and
   the guard printed "clean" and exited 0 — on exactly the kind of error it existed to catch.

4. **The sanitizer's screenshot/uninspectable conflict** (Issue 2's predecessor) — already fought
   once in the RCA spec (design.md Revisions 11–16), where the first-round fix only covered part of
   the binary-content surface.

5. **Today's two issues** (this note) — both found by pulling on one CI failure that looked, at
   first glance, like something to just re-run.

The repo owner's own prior framing of this pattern (per this session's working context): *"a check
reports success while proving nothing"* — and that count was already at three distinct instances
during Spec A before today, not counting the above. Take that as background pressure explaining why
independent verification is being requested, not as a specific claim you need to chase down further.

---

## What is explicitly being asked of you

1. **Re-derive, don't just check, Issue 1's root cause.** Confirm or refute the batch-math
   hypothesis by direct evidence (a real trace or HAR showing 7 requests to specific batch URLs),
   not by re-reading the same code Claude read. If the real cause is different, say so.
2. **Recommend Issue 1's fix** — assertion rewrite vs. quarantine vs. something else — with
   reasoning, not just a preference.
3. **Directly identify Issue 2's actual uninspectable entry** (not just confirm it's plausible), and
   propose a fix consistent with the RCA spec's fail-closed philosophy, with a regression test.
4. **Independently flag anything about the recurring pattern** worth naming, if you see something
   Claude didn't — the reason for the second opinion is specifically to catch what one investigator
   alone might miss or rationalize past.

Do not implement fixes yet unless the repo owner has separately told you to — this note is scoped as
an investigation request. Report findings and recommendations back to the repo owner.
