# B1 Implementation Prerequisites — `portfolio-composition-contract`

**Date:** 2026-08-19
**Spec:** `.kiro/specs/portfolio-composition-contract/` — requirements Rev 6 (frozen), design Rev 11, tasks Rev 8
**Purpose:** everything B1's implementation needs decided, provided, or known **before** work starts, so implementation does not stall on clarifications that belonged in planning.
**Audience:** Cursor (implementation), Sol/Codex and Fable (review).

---

## How to read this

B1 was frozen on 2026-08-16. Since then eleven PRs merged and Spec A's implementation completed, so parts of B1's spec describe a codebase three days older than the one it will be built against. Every concrete claim below was **verified against `main` at `25120cc`** on 2026-08-19, not taken from the spec text.

Four categories:

- **§1 Blocking** — work cannot correctly proceed past a named wave until resolved.
- **§2 Provisioning** — cheap, but must exist before the wave that needs it.
- **§3 Decided** — already settled; listed so nobody reopens them mid-implementation.
- **§4 Drift** — what the spec says versus what the tree now holds.

---

## §1 Blocking prerequisites

### 1.1 Spec A's `V17`–`V19` must be applied to production before B1's `V20`

**Blocks: Wave 3 (release R-B). Does not block Waves 0, 1, 2, 4.**

This is the finding that motivated design Revision 11. It was not in the spec.

The tasks file anticipated a migration-number *collision* — *"two migrations numbered 17 do not merge badly — Flyway refuses to start"* — which remains true and is a different failure. This one is **ordering**.

`spring.flyway.out-of-order` is unset in both `portfolio-service/src/main/resources/application.yml` and `application-prod.yml`, so it defaults `false`. Spring Boot runs `validateOnMigrate=true`. Applying `V20` while `V17`–`V19` are unmerged therefore does not defer them: **under this configuration they are left unapplied and startup validation fails**. The gap is recoverable only by explicitly enabling [`outOfOrder`](https://documentation.red-gate.com/fd/flyway-out-of-order-setting-277579015.html) — a deliberate configuration change with its own review, not a property of the current setup and not something to reach for under cutover pressure.

Verified empirically against PostgreSQL 18.6 with Flyway 11.20.3 — apply `V16` then `V20`, then introduce `V17`–`V19` and re-run `migrate`:

```
ERROR: Validate failed: Migrations have failed validation
Detected resolved migration not applied to database: 17.
Detected resolved migration not applied to database: 18.
Detected resolved migration not applied to database: 19.
```

Exit 1. In a running service this surfaces as **`portfolio-service` failing to start**.

**Why no requirement covers it.** Requirement 9.1 gates the Composition_Operation's *user-reachability* on Spec A's steady state, which binds at **R-C**. The ordering constraint binds at **R-B**, three release transitions earlier (R-B → R-B2 → R-B3 → R-C). The stated gates therefore permit a sequence that breaks the database. Requirement 9.2 is unaffected — B1 may be implemented and tested in full meanwhile; only *applying* V20 is constrained.

**Consequence for planning.** Spec A's R3a is the first irreversible step of a cutover that is itself blocked on proving the Neon restore path. So **B1's release lane inherits that blocker at Wave 3**, sooner than reading the spec would suggest. R-B's precondition is that R3a has **already passed its own gates** — R-B does not repeat them. The verified-backup checkpoint belongs to R3a, because R3a is the irreversible step; R-B's own verification is the narrower one below. Waves 0–2 and 4 are unaffected and represent substantial work — roughly half the wave count — that can proceed today.

**Verification, when the time comes:** read `flyway_schema_history` and confirm `V17`, `V18`, `V19` present and successful. Do **not** infer it from a merge or a green deploy.

### 1.2 Task 2.2 cannot be written from `main`

**Blocks: Wave 2.**

Task 2.2 requires the gateway provisioning insert be proven against *"a database at V19 and one at V20"*, and states explicitly that *"a run from today's V16 or an unspecified baseline does not satisfy this."*

`V17`–`V19` exist only on the unmerged `feat/supported-asset-postgres-repair` branch. The task is therefore unbuildable as written from `main`.

**Options considered. Option A is the decision** (owner, 2026-08-19); B and C are recorded for why they were not taken:

- **A.** Branch B1's Wave 2 work from `feat/supported-asset-postgres-repair` instead of `main`, so the migration files are present for test fixtures only. Keeps `main` clean; means Wave 2's branch carries unmerged migrations it does not own.
- **B.** Merge Spec A's R3a first. Removes the problem entirely and also clears §1.1 — but R3a's merge *executes* the migrations in production, which is checkpoint 9.6 and needs the cutover.
- **C.** Copy `V17`–`V19` into a test-only fixture directory used by the Testcontainers harness, never into `db/migration`. Cheapest; carries a drift risk if Spec A's migrations change before merging.

**Decision: A**, under the hard conditions below — they are not optional. It is honest about the dependency, needs no production action, and Wave 2's PR can rebase onto `main` once R3a lands. C invites two copies of an irreversible migration to disagree.

#### Option A is a *local development* arrangement only — mandatory conditions

A stacked branch carries `V17`–`V19` in `db/migration`. Merging or deploying it would execute Spec A's irreversible repair outside the cutover, at an arbitrary moment, with no maintenance window, no verified backup, and no `Post_Migration_Integrity_Assertion` gate. That is a worse outcome than the ordering defect this document exists to prevent.

Therefore, while Wave 2 is stacked on `feat/supported-asset-postgres-repair`:

- [ ] **Do not open a PR** from the stacked branch.
- [ ] **Do not merge it** to `main` under any circumstance.
- [ ] **Do not run any deploy-capable or production-mutating workflow from it** — specifically `deploy.yml`, `deploy-azure.yml`, `deploy-aws.yml`, `terraform-azure.yml` with `action=apply`, and `synthetic-monitoring.yml` (its global setup POSTs to the production seed endpoint).
- [ ] **Build-and-test CI is explicitly permitted and encouraged** — `ci-verification.yml`, `frontend-ci.yml`, and any local Gradle or Playwright run. These build and test only; none deploys or writes to production.

**Name the stacked branch `feature/…`, not `feat/…`.** This is not cosmetic. `ci-verification.yml` and `frontend-ci.yml` trigger on push for `main`, `architecture/**` and **`feature/**`** — they do **not** match the repository's usual `feat/**` convention. And Option A forbids opening a PR, so the `pull_request` trigger is unavailable too. A branch named `feat/b1-wave-2` would therefore receive **no CI whatsoever** while stacked.

Use e.g. `feature/b1-wave-2-provisioning`. `frontend-e2e-integration.yml` is `workflow_dispatch:` only and must be invoked manually:

```bash
gh workflow run frontend-e2e-integration.yml --ref feature/b1-wave-2-provisioning
```

**After R3a lands on `main`:**

- [ ] **Rebase** the Wave 2 branch onto `main`.
- [ ] **Prove nothing outside Wave 2's own surface survived the rebase**, before opening a PR.

  **A migration-only check is not sufficient.** The repair branch is R3a — Spec A's task 6 *and* tasks 8.2–8.6 — and none of it has merged. Measured against `main`, it carries **38 changed files: 3 migrations and 35 others**, including `MarketPriceProjectionService`, `PortfolioService`, `PostMigrationIntegrityAssertion`, the freshness DTOs and contracts, `config/seed-tickers.json`, and `portfolio-service/src/main/resources/application.yml`. A guard that only inspects `db/migration` would pass while stale or conflicted Spec A production code rode along.

  Use an **allowlist of the paths Wave 2 legitimately touches**, and fail on anything else:

```bash
set -e
git fetch origin
git rebase origin/main

# Wave 2 changes the gateway (provisioning insert + /api/assets route) and its own spec ticks.
# Anything outside this set is a stack remnant from the repair branch.
ALLOWED='^(api-gateway/src/(main|test)/|api-gateway/build\.gradle$|\.kiro/specs/portfolio-composition-contract/tasks\.md$)'

# Capture the diff as its own command, so `set -e` still catches a failing `git diff`.
# Do NOT inline this into the pipeline below: `$(git diff … | grep … || true)` makes the
# `|| true` cover the whole pipeline, so a bad ref prints `fatal: ambiguous argument`,
# leaves STRAY empty, and the guard reports "clean" and exits 0. `set -o pipefail` does
# not help — `|| true` swallows the pipeline's status either way.
CHANGED=$(git diff --name-only origin/main...HEAD)

STRAY=$(printf '%s\n' "$CHANGED" | grep -vE "$ALLOWED" || true)
if [ -n "$STRAY" ]; then
  echo "STOP: files outside Wave 2's surface remain after rebase —"
  echo "$STRAY"
  exit 1
fi

echo "clean — diff is confined to Wave 2's own paths"
```

  Widen `ALLOWED` only for a path Wave 2 genuinely owns, and say why in the PR. Never widen it to admit a `portfolio-service` path: Wave 2 changes no service code.

- [ ] Only then open the PR.

If the guard trips, the branch still carries files it does not own — stop and re-derive the rebase rather than merging past it.

---

## §2 Provisioning prerequisites

### 2.1 E2E credentials in two workflows

**Needed by: Wave 0 (tasks 0.5, 0.6).**

| Workflow | `INTERNAL_API_KEY` | `E2E_TEST_USER_EMAIL` / `_PASSWORD` |
|---|---|---|
| `ci-verification.yml` | ✅ present (~L266–268) | ❌ absent |
| `frontend-e2e-integration.yml` | ❌ absent | ❌ absent |

Both GitHub secrets already exist — `synthetic-monitoring.yml` consumes them. This is wiring, not procurement. `frontend-e2e-integration.yml` runs the affected suites today with neither, so it is a known-red manual workflow until wired.

### 2.2 Work from `main`, not the spec branch

`origin/spec/portfolio-composition-contract` is **stale**: it still shows Wave P unticked. `main` carries the spec files *and* the Wave P completion from PRs #107 and #110. Anyone reading the branch would conclude Wave P is unstarted and redo ten finished tasks.

Tick checkboxes in `main`'s copy.

### 2.3 `deploy.yml` has no `concurrency:` group

B1 has **six sequential releases**. Neither `deploy.yml` nor `deploy-azure.yml` declares a concurrency group, so two merges landing close together start concurrent production deploys, each running `seed` — a data-plane writer that deletes and recreates the E2E portfolio.

Not strictly blocking, but B1 is where it is most likely to bite. Tracked in `docs/todos/TODOS_2026-04-07.md`. Suggested `cancel-in-progress: false` — queue rather than cancel, since a half-finished deploy is worse than a delayed one.

---

## §3 Already decided — do not reopen

These are the questions most likely to generate a clarification request. All are settled in the frozen spec.

| Topic | Decision | Where |
|---|---|---|
| Provisioning-gate mechanism | **Staged gateway-first**, with signup quiescence as a proven fallback. Requirement 1.21 left the choice to design; design chose. | D9 |
| `portfolios.user_id` type | Stays `VARCHAR(255)` while `users.id` is `UUID`; bridged with `::text` casts. **Deliberately deferred** — converting a live identifier column is migration risk unrelated to B1, on a table already gating a production cutover. Note `V7__Fix_Portfolio_User_Id_To_UUID.sql` does not do this despite its name; it updates a value. | O3 |
| Authenticated read for seed state | **Closed.** No separate version endpoint — prohibited by a frozen requirement. The Azure job already holds `E2E_TEST_USER_EMAIL`/`_PASSWORD`; the seed step logs in and reads the full `PortfolioResponse`, which carries the version. | O1, D8 |
| `CompositionResult.noOp` | **Closed as internal-only.** No wire field; an unchanged version already expresses the result. | O2 |
| Backfilled portfolio's `created_at` | **Closed with `now()`.** The portfolio is genuinely created by the backfill; reusing the user's `created_at` would claim the aggregate existed when it did not. | O4 |
| Concurrency model | **Optimistic.** On conflict the draft is lost — no reapply, which would be last-write-wins with extra steps. | B1 requirements |
| `ReadOnlyEnforcementFilter` | **B2's, not B1's.** Requirement 10.3 forbids B1 touching it. Its `decide(ro, method, path)` already takes the method; it is the *allowlist* that is path-only and must become method-plus-path before the demo account can reach the composition write. | Req 10.3, D14 |

---

## §4 Codebase drift since the freeze

Verified against `main` @ `25120cc`.

**Classes B1 creates (correctly absent today):** `HoldingReplacementService`, `CompositionController`, `UnsupportedAssetsException`, `CompositionTuplePreparer`, `GoldenStateTuplePreparer`, `ContractError`.

**Classes B1 modifies (present):** `PortfolioResponse`, `SignupService` (`api-gateway/.../auth/`), `PortfolioController`.

**Legacy writers Wave 1 retires (present, as the spec describes):** `POST /api/portfolio` at `PortfolioController:52`, versionless `POST /api/portfolio/{portfolioId}/holdings` at `:70`.

**Gateway route pattern for task 2.3:** routes are declared in `api-gateway/src/main/resources/application-prod.yml` as `predicates: - Path=/api/…/**`, alongside `/api/portfolio/**`, `/api/market/**`, `/api/insights/**`, `/api/chat/**`. `/api/assets/**` follows that shape.

**Changed under B1's feet by Spec A — Wave 0 must account for it:**

- Both seed paths now enumerate `SupportedCatalog.active()`, so the Golden-State set is **159 holdings, not 160**, and `TATAMOTORS.NS` is no longer seeded.
- `golden-path.spec.ts` asserts `BTC` at lines 67 and 88; the Golden-State set now carries **`BTC-USD`**. Task 0.4 already calls this out.
- `PortfolioSeedService` now writes a **fixed** `cost_basis_as_of` from `app.demo.cost-basis-anchor`, not `now() − 25h`. Any B1 fixture asserting a recent cost-basis date will need updating.
- `MarketPriceProjectionService` is no longer `@Async`.

---

## §5 What can start today

| Wave | Blocked? | By |
|---|---|---|
| **0** — fixture identity | **No** | — (kickoff written) |
| **1** — legacy writer retirement | **No** | — |
| **2** — gateway provisioning + `/api/assets` | **No** | §1.2 decided — stack on the repair branch under Option A's mandatory conditions |
| **3** — schema (`V20`) | **Yes** | §1.1 — Spec A R3a must be applied to production first |
| **4** — contract implementation (23 tasks) | **No** | Requirement 9.2 permits full implementation and testing |
| **5–7** — release waves | **Yes** | downstream of Wave 3 |

Waves 0, 1 and 4 are roughly half of B1's remaining 81 tasks and need nothing that does not already exist.

---

## Open items

No open question remains for B1's implementation start. §1.2 is decided (Option A). Waves 0, 1, 2 and 4 may all proceed.

The one thing still outstanding is **outside B1**: Wave 3 and everything downstream wait on Spec A's R3a reaching production, which waits on the Neon restore path being proven. That is an operational prerequisite owned by the operator, not an implementation question, and it does not block the four waves above.
