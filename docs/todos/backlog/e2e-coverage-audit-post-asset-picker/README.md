# Backlog: Full E2E Coverage Audit After Asset Picker and Profile Changes

**Status:** Open — 2026-08-19. Deliberately gated; not to be started yet.
**Owner:** unassigned
**Tracked in:** [Changelog — Spec A supported-asset-integrity](../../../changes/CHANGES_SUPPORTED_ASSET_INTEGRITY_2026-08-19.md)

---

## Status & Decision

**Open and intentionally deferred.** The trigger is completion of the **Asset Picker**
(Spec B1 `portfolio-composition-contract` + Spec B2 `asset-picker-composition`) and the
**Profile changes**. Doing it before then would audit a surface that is about to change:
B1 introduces `GET /api/assets`, portfolio versioning and a desired-state write, B2 adds the
picker modal, draft/conflict UX and a presence banner, and Profile changes alter the account
surface. Each adds new paths the audit would have to redo.

What this item records now is **why** the audit is warranted, with evidence gathered during
Spec A, so the case doesn't have to be rebuilt later.

---

## Why This Is Warranted (evidence, not speculation)

Three separate defects during Spec A shared one root cause: **a check reported success while
proving nothing.** That pattern was caught three times by deliberate scrutiny, never by the
check itself.

### 1. A live monitor asserted the wrong thing for months, then failed for two days unnoticed

`api-live-smoke.spec.ts` asserted `holdingsInserted >= 160`. A lower bound cannot detect a
partial seed that still clears the floor, and Spec A Requirement 8.8 had predicted in writing
that it *"would fail as soon as `TATAMOTORS.NS` is deprecated without a successor being added."*
It did exactly that on **2026-08-18 and 2026-08-19** (runs
[32117347583](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32117347583),
[32233589893](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32233589893)),
and the daily failures were not noticed until someone went looking for an unrelated reason.
Fixed in PR #115.

### 2. A test named for a cold-start path has never observed a cold start

`api-live-smoke.spec.ts` contains *"health: GET /actuator/health responds 200 **during
scale-from-zero**"*. It completed in **655 ms** on 2026-08-19 and **870 ms** on 2026-08-17.
A genuine Azure Container Apps cold start is 10–30 seconds.

The cause is structural, not flaky: `global-setup.ts` polls `/api/portfolio/health`,
`/api/market/health` and `/api/insights/health` as a warming step **before any test runs**, so
all three apps are awake by the time the test executes. The test cannot observe the condition
it is named for, on any run, cron or dispatched. It is exercising a warm path under a cold-path
name — the same class of defect as #1, in a different disguise.

### 3. Deploy-workflow checks that pass by skipping

GitHub reports a **skipped** job as a *successful* check, so a green workflow does not
distinguish "did not run" from "ran and passed". Wave P had to add an explicit
`assert-scoped-non-interference` job that records `needs.<job>.result` values, and Wave P-B had
to add a dedicated *"Prove digest path skipped build and push"* step, precisely because the
absence of work is not observable from a green summary. Both exist only because the gap was
reasoned about in advance.

### Additional weakening, unresolved

`SKIP_BACKEND_HEALTH_CHECK=true` is set on the **Azure** synthetic job, which skips
`global-setup.ts`'s post-seed deep health poll. The code's own log message warns: *"Use this
only for stack-less smoke; full E2E must wait for the gateway."* The flag has a clear
justification on AWS (Lambda cold starts exceed the setup timeout; `/actuator/health` returns
403 through CloudFront). On Azure that justification does not obviously transfer, and no record
explains why it was set there.

---

## Current Surface (as of 2026-08-19)

18 Playwright spec files. The `azure-synthetic` project — the only one that runs, since
`CLOUD_PROVIDER == 'azure'` — executes **9 tests** covering login, dashboard hydration,
AI-insights page load, portfolio hydration, UI scaling, an API smoke covering health/seed/login,
and a Front Door latency check.

That is a liveness signal, and it is described as one. It is **not** functional coverage: it
asserts almost nothing about correctness of values, and nothing at all about the write paths the
Asset Picker is about to introduce.

The `aws-synthetic` project is dormant (`CLOUD_PROVIDER == 'aws'` is false). It was found during
Spec A to be asserting `TATAMOTORS.NS` by name — a deprecated asset that is no longer seeded —
so it would have failed immediately if that provider were ever selected. Corrected in PR #115,
but it is a reminder that a dormant suite decays silently.

---

## Scope When Started

Both layers, deliberately — the point is that neither alone caught the defects above.

- [ ] **Integration tests (Testcontainers).** Every service, every write path. Particular
  attention to paths where a check can pass without exercising anything: conditional skips,
  `matchIfMissing` defaults, gated behaviour asserted only in its "off" state.
- [ ] **Live browser tests.** Real user journeys end to end, asserting **values** and not only
  that a page rendered: signup → login → view portfolio → add holding via the picker → observe
  price and freshness → conflict handling → demo reset → profile changes.
- [ ] **First, establish that every test intended to run actually runs.** Owner's hypothesis
  (2026-08-19), recorded because it is distinct from assertion-vacuity and cheap to check: a
  narrowing may have been introduced deliberately as a temporary measure — to unblock something,
  to work around a flake — and then simply stuck, possibly months ago and possibly by someone who
  is no longer expecting it. Check `playwright.config.ts` `testIgnore` / `testMatch` / project
  filters, any `grep` or `--project` narrowing in `synthetic-monitoring.yml` and
  `ci-verification.yml`, `test.skip` / `test.fixme` / `describe.only`, and Gradle `--tests` filters
  or JUnit `@Tag` exclusions. For each exclusion found: was it intentional, is the reason recorded,
  and does it still hold? The `azure-synthetic` project runs **5 of the 18** spec files; that split
  is plausibly by design (the rest need a local stack) but has never been confirmed in writing.
  Do this before auditing assertions — there is no point hardening a test that does not execute.
- [ ] **Audit every existing assertion for vacuity.** For each: *can this pass while the
  behaviour it names is broken?* `>= N`, `toContainText` substring matches (a literal `"GOOG"`
  matched `GOOGL` during Spec A), and any test whose named condition is established by setup
  before the test runs.
- [ ] **Cold-start coverage.** Either make the scale-from-zero test genuinely observe a cold
  start (ordering it before the warming step, or on a service the warm-up does not touch), or
  rename it to state what it actually checks. It must not keep claiming both.
- [ ] **Decide `SKIP_BACKEND_HEALTH_CHECK` on Azure** — justify it in a comment or remove it.
- [ ] **Freshness and valuation correctness**, once the repairs land: a stale holding is included
  in `totalValue` at last known price, a missing one is excluded and sets `partialValuation`,
  and the `assetPriceFreshness` precedence `MISSING > UNKNOWN > STALE > FRESH` holds end to end.
- [ ] **Failure-mode coverage**, not only happy paths: what the user sees when a price is stale,
  when a write is rejected with 422 `unsupported_asset`, when an optimistic-concurrency conflict
  loses a draft.

---

## Explicit Non-Goal

This is **not** a request to add more tests for their own count. Spec A's suites are large and
were still wrong in the three ways above. The audit's product is a defensible answer to *"what
would have to break for this suite to stay green?"* — for each area of the code. A smaller suite
that cannot pass vacuously is worth more than a larger one that can.

---

## Related

- [`demo-portfolio-and-ticker-integrity`](../demo-portfolio-and-ticker-integrity/README.md) — the
  data-integrity backlog that became Spec A.
- [`total-value-e2e-hydration`](../total-value-e2e-hydration/README.md) and
  [`total-value-skeleton-e2e`](../total-value-skeleton-e2e/README.md) — existing E2E gaps that
  this audit should absorb rather than duplicate.
- Spec A Requirement 8.9 remains unsatisfied: catalog version has no HTTP surface
  (`/actuator/info` returns `200` with body `{}`), so cross-service catalog identity is verified
  only at cutover checkpoint 9.9 by startup log. If the audit wants that assertable from a live
  test, an `InfoContributor` on each catalog consumer plus gateway routes to per-service
  actuators is the prerequisite.
