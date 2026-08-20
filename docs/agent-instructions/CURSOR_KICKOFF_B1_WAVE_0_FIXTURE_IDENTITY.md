# Cursor Kickoff — Spec B1 Wave 0: fixture identity migration

**Date:** 2026-08-19
**Prepared for:** Cursor (implementation)
**Baseline:** `origin/main` (currently `25120cc` or later)
**Suggested branch:** `feat/b1-fixture-identity` off `main`
**Spec:** `.kiro/specs/portfolio-composition-contract/` — **read it from `main`, not from the branch** (see §0)

---

## 0. Read the spec from `main`

`.kiro/specs/portfolio-composition-contract/` exists in two places and they disagree.

- **`main`** — authoritative. Has the spec files *and* Wave P ticked (PRs #107, #110).
- **`origin/spec/portfolio-composition-contract`** — **stale**. Still shows Wave P unticked. It was the drafting branch; it has not moved since the spec merged.

Work from `main`. Tick checkboxes in `main`'s copy.

## 1. What this wave is, and what it is not

**Production-neutral.** No production code changes at all — this is E2E fixtures, two workflow files, and test expectations. It merges under the normal discipline once green.

It comes first because **Wave 1 deletes the endpoints these fixtures currently use.** `helpers/api.ts` creates a portfolio via `POST /api/portfolio` and adds holdings via the versionless `POST /api/portfolio/{id}/holdings`; Wave 1 retires both. Migrating the fixtures afterwards would mean a window where the suite is red for a reason unrelated to the change that broke it.

Scope: tasks **0.1 – 0.7** only. Not Wave 1, not the `/api/assets` route (Wave 2), nothing in `portfolio-service`.

## 2. The trap this wave exists to avoid — read before writing code

Task 0.2's own text states it, and it is the reason this wave is seven tasks rather than two:

> The second, independent identity path — `global.setup.ts` and `golden-path.spec.ts` install the browser session immediately before the API helper runs. **Migrating only the API helper yields a green suite proving nothing:** API assertions pass against the E2E portfolio while the page renders dev's empty one.

There are **two** identities in play and they are established by different code:

| Path | File | Identity today |
|---|---|---|
| API / request context | `frontend/tests/e2e/helpers/api.ts` | logs in as `dev@local`, falls back to `user-001` |
| Browser session | `frontend/tests/e2e/helpers/browser-auth.ts` | logs in as `dev@local` / `local-dev-password-2026` |

Migrate **both, together**. A half-migration is worse than none: it produces a green run in which the API layer talks to one user's data and the rendered page shows another's. That is precisely the "check reports success while proving nothing" pattern that has bitten this project three times during Spec A, and it is now a tracked backlog concern — do not add a fourth instance.

The target identity is the E2E user the Golden-State seeder owns: `00000000-0000-0000-0000-000000000e2e`, credentials from `E2E_TEST_USER_EMAIL` / `E2E_TEST_USER_PASSWORD`.

**Note the fallback.** `api.ts` currently logs *"Could not resolve login userId — falling back to user-001"* and proceeds. After migration a failed login must **fail the test**, not silently fall back — a fallback identity reintroduces the same two-identity split by another route.

## 3. Tasks

### 0.1 — `helpers/api.ts` to the E2E identity
_Requirements: 8.3, 8.7_

Requirement 8.3 notes this is *"the only live consumer, plus one already-skipped spec"* of the endpoint Wave 1 retires, and that **no production frontend code calls it** — so this migration cannot affect the app. The already-skipped spec is the one covered under §0.3; do not treat "only live consumer" as meaning there is exactly one caller.

### 0.2 — `helpers/browser-auth.ts` to the E2E identity
_Requirements: 8.3, 8.7_

The second identity path. See §2. Its docblock still references credentials (`dev@localhost.local` / `password`) that no longer exist — Flyway `V15` seeds `dev@local` — so correct the comment as well as the code.

### 0.3 — `ensurePortfolioWithHoldings` becomes read-and-assert
_Requirements: 8.3, 8.7, 8.13_

Today it **creates** a portfolio via `POST /api/portfolio` and adds holdings via the versionless `POST` whenever the portfolio list is empty. It must instead **assert** the Golden-State setup is present and **fail hard when seeding was skipped — never repair silently.**

This is the substantive change in the wave. A fixture that repairs its own preconditions cannot distinguish "seeding worked" from "seeding was skipped and I papered over it," and it does so through exactly the write path B1 is retiring.

**Four call sites, and one of them is dormant:**

| Call site | State |
|---|---|
| `golden-path.spec.ts:63` | live |
| `golden-path.spec.ts:76` | live |
| `dashboard-data.spec.ts:120` | **inside a `test.skip`** — see below |
| `dashboard-data.spec.ts:198` | live |

`dashboard-data.spec.ts:113` quarantines its test with: *"Backend `POST /api/portfolio/{id}/holdings` returned 500 (2026-04-19 CI run) … quarantined until the holdings endpoint is confirmed healthy."* That is the endpoint Wave 1 retires.

Two consequences. **A green suite does not exercise that call site**, so it cannot serve as verification that the conversion is complete — convert it by reading, not by running. And once 0.3 lands, the quarantine reason no longer exists: the helper will not call the broken endpoint at all. Do not silently leave it skipped against a condition that can never be met again — either un-skip it and report the result, or raise retiring it. This is the "already-skipped spec" that Requirement 8.3 refers to when it says *"the only live consumer, plus one already-skipped spec"*; §0.1 below quotes only the first half of that sentence.

The failure message should name what was expected and what was found, so a future red run is diagnosable without re-reading the helper.

### 0.4 — Canonical ticker expectations
_Requirements: 6.7_

`golden-path.spec.ts` asserts `BTC` at lines 67 and 88. After Spec A the Golden-State set carries **`BTC-USD`** — `BTC` was the un-migrated V3 holding and is exactly what Spec A's V18 repair exists to fix. Update both.

**There is a third occurrence: the test title at line 87**, *"holdings table contains AAPL and BTC tickers"*. Update it too. A test named for `BTC` while asserting `BTC-USD` is the same stale-symbol drift this task exists to remove, and it is what a future reader greps for.

Also update the header comment at line 11, which still names the V3 seed (*"seeds user-001 with AAPL, TSLA, BTC"*) as the fixture source. It isn't — the Golden-State seeder is.

### 0.5 — Wire E2E credentials into `ci-verification.yml`
_Requirements: 8.3_

It currently exports `INTERNAL_API_KEY` and `TF_VAR_internal_api_key` (lines ~266–268) and **neither credential**. Add `E2E_TEST_USER_EMAIL` and `E2E_TEST_USER_PASSWORD` from the same secrets the synthetic workflow uses.

### 0.6 — Wire `frontend-e2e-integration.yml`
_Requirements: 8.3_

It has **neither** the internal key nor the credentials, and still runs the affected suites — so leaving it unwired leaves a known-red manual workflow. Wire both.

### 0.7 — G0b evidence
_Requirements: 8.3, 8.7_

`golden-path` and `dashboard-data` pass against a **fresh disposable database** in one hermetic `ci-verification.yml` run, on the migrated identity.

The task text notes this *"Requires Spec A's implementation, not its production cutover"* — that implementation is merged and live, so this is unblocked. Fresh-database matters: the point is proving the fixtures work from Golden-State seeding alone, with no residue from a previous run.

## 4. Verified anchors (checked against `main`)

- `frontend/tests/e2e/helpers/api.ts` — `email: "dev@local"` (~L18), `resolveUserId()` defined (~L11), `user-001` fallback (~L32–33), `mintJwt` imported (~L2) and called (~L51), `ensurePortfolioWithHoldings` (~L45)
- `frontend/tests/e2e/helpers/browser-auth.ts` — `email: "dev@local"` / `password: "local-dev-password-2026"` (~L31–32), stale credential comment (~L25)
- `ensurePortfolioWithHoldings` — defined in `helpers/api.ts`; imported by `golden-path.spec.ts:19` and `dashboard-data.spec.ts:17`
- `golden-path.spec.ts` — `BTC` at L67, **L87 (test title)** and L88; stale V3 comment at L11; `ensurePortfolioWithHoldings` called at L63 **and L76**
- `dashboard-data.spec.ts` — `ensurePortfolioWithHoldings` at L120 (inside the `test.skip` opening at L113) and L198
- `.github/workflows/ci-verification.yml` — `INTERNAL_API_KEY` at ~L266–268, no E2E credentials
- `.github/workflows/frontend-e2e-integration.yml` — neither

Re-verify before editing; these have moved once already this month.

## 5. Definition of done

- 0.1–0.7 ticked in `main`'s `.kiro/specs/portfolio-composition-contract/tasks.md`.
- **Both** identity paths migrated in the same PR — do not split them across two.
- `ensurePortfolioWithHoldings` fails hard on missing Golden State, with a diagnosable message, and no longer calls `POST /api/portfolio` or the versionless holdings `POST`. Grep the whole `frontend/tests` tree to confirm no other caller of either endpoint survives.
- A test proving the failure path: with seeding skipped, the helper **fails** rather than repairing.
- All **four** call sites converted, including `golden-path.spec.ts:76` and the dormant `dashboard-data.spec.ts:120`.
- The `dashboard-data.spec.ts:113` quarantine explicitly resolved — un-skipped with the result reported, or retirement raised. Its stated reason (the versionless holdings `POST` returning 500) no longer applies once 0.3 lands, so leaving it skipped and unexplained is not an outcome.
- `npx tsc --noEmit` clean; `npx playwright test --list` loads every spec.
- G0b: `golden-path` and `dashboard-data` green against a fresh database in one `ci-verification.yml` run.
- Spec reference check:

```bash
python scripts/check-spec-references.py .kiro/specs/portfolio-composition-contract/tasks.md --against .kiro/specs/portfolio-composition-contract/requirements.md --coverage --pairs
```

## 6. Merge effects

`frontend/**` is in `deploy.yml`'s `push: paths:` filter, so merging fires a full production deploy and re-seeds the demo — despite this being test-only. Expect `totalHoldings: 159`, unchanged.

**This is not one of the releases blocked on Spec A's cutover.** Design Revision 11 makes Spec A's production completion the predecessor of B1's whole *release* lane — R-0 onward, not only R-B. Wave 0 has no artifact and no release: it ships no production code, and the deploy this merge triggers rebuilds identical application code and re-runs the existing seed. It is unaffected by that ordering, and it is the reason Wave 0 is the wave to start with. Do **not** generalise from this to Wave 1: Wave 1 may be implemented now, but R-0 waits.

**Merge alone**, and let the deploy finish before merging anything else: neither `deploy.yml` nor `deploy-azure.yml` has a `concurrency:` group, so two close merges start concurrent deploys that both run `seed`, a data-plane writer. Tracked in `docs/todos/TODOS_2026-04-07.md`.

## 7. Escalate rather than decide

- Any second identity path beyond the two named in §2 — the wave's whole premise is that there are exactly two.
- Any temptation to keep a fallback identity "for robustness" — that is the defect being removed.
- Any caller of `POST /api/portfolio` or the versionless holdings `POST` outside `frontend/tests`.
- Any need to touch `portfolio-service`, the gateway, or a migration — Wave 0 is production-neutral.
