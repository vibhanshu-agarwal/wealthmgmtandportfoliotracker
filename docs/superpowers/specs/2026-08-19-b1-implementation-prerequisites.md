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

**Blocks: Wave 3 (release R-B), with a database-corrupting failure mode. Does not block *implementation* in Waves 0, 1, 2, 4.**

**It is not the only thing gating the release lane.** D9 and the tasks dependency graph already make Spec A's production steady state the predecessor of the **entire** release lane, for reasons independent of migration ordering. R-0 (Wave 1) and R-A (Wave 2) therefore wait on Spec A's cutover as well — see §5, which separates each wave's implementation from its release.

This is the finding that motivated design Revision 11. The *ordering mechanism* was not in the spec; the whole-lane release constraint already was. Revision 11 adds the mechanism and a check beneath that constraint, and does not relax it.

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

**Consequence for planning.** Spec A's R3a is the first irreversible step of a cutover that is itself blocked on proving the Neon restore path. The whole release lane already waited on that cutover; what this finding adds is that at **Wave 3** the consequence of ignoring it is not a policy violation but a database that will not start. R-B's precondition is that R3a has **already passed its own gates** — R-B does not repeat them. The verified-backup checkpoint belongs to R3a, because R3a is the irreversible step; R-B's own verification is the narrower one below. **Implementation** in Waves 0, 1, 2 and 4 is unaffected — 41 of the 81 remaining tasks, just over half — and can proceed today; the releases those waves end in cannot.

**Verification, when the time comes:** read `flyway_schema_history` and confirm `V17`, `V18`, `V19` present and successful. Do **not** infer it from a merge or a green deploy.

### 1.2 Task 2.2 cannot be written from `main`

**Blocks Wave 2 from `main`; resolved by Option A — but Option A supplies only half the fixture. See §1.3.**

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

`reason` is `required: true` on that workflow, so a run without `-f reason=…` is rejected before it starts:

```bash
gh workflow run frontend-e2e-integration.yml --ref feature/b1-wave-2-provisioning -f reason='Wave 2 stacked-branch verification'
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

### 1.3 Option A supplies `V19`, but not `V20` — task 3.1 must be written before task 2.2

**Blocks: task 2.2. Resolved inside the implementation lane; needs no production action.**

Task 2.2 requires **both** endpoints of the boundary — *"the insert runs against a database at V19 and one at V20"*. Option A only fixes the lower one:

| schema | where it exists today |
|---|---|
| `V16` | `main` — the baseline task 2.2 explicitly rejects |
| `V17`–`V19` | `feat/supported-asset-postgres-repair` — supplied by Option A |
| **`V20`** | **nowhere. It is authored by task 3.1, in Wave 3.** |

So Option A alone leaves task 2.2 unbuildable, and Wave 2 still blocked at its proof.

**Resolution: task 3.1's *authoring* is an explicit predecessor of task 2.2.** This is consistent with §1.1 rather than in tension with it — §1.1 constrains only when `V20` is **applied to production**, not when the file is written. Writing the migration is ordinary implementation work under requirement 9.2.

Concretely:

- [ ] Write `V20` per task 3.1 on the Wave 2 stacked branch, in **one** commit, so the Testcontainers fixture can migrate to it.
- [ ] **Its production application stays gated at task 3.5**, unchanged — the STOP/GO that reads `flyway_schema_history`. Authoring it early grants no permission to run it.

**`V20` must not ride into Wave 2's merge.** This is the same hazard §1.2 guards for `V17`–`V19`, and it applies to B1's own migration for the same reason: Wave 2 ends at R-A, and if `V20` lands on `main` with it, the next production deploy applies the migration before R-B exists and before 3.5 has gated anything. Authoring early is safe; merging early is the defect this document was written to prevent.

Therefore:

- [ ] **Do not widen §1.2's `ALLOWED` to admit any `db/migration` path** — including `V20`. The allowlist rejecting `portfolio-service/**` already produces the right answer, and the "never widen it to admit a `portfolio-service` path" rule stands exactly as written. If the guard trips on `V20` at rebase time, that is the guard working.
- [ ] **Carry the authoring commit forward into Wave 3's branch** rather than re-typing the migration there. One authored `V20`, moved; never two copies free to drift — the failure mode that ruled out Option C.
- [ ] `V20` reaches `main` **only** through Wave 3's PR.

Order within the stack: task 3.1 (author only) → task 2.1 → task 2.2. Waves 0, 1 and 4 are untouched by this.

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

**Implementation and release are separately gated, and the distinction is the whole point of this section.** Requirement 9.2 lets B1 be built and tested in full today; D9 makes Spec A's production steady state the predecessor of every B1 release. A wave marked "start now" means write and test the code — not ship it.

| Wave | Implementation | Release | Notes |
|---|---|---|---|
| **0** — fixture identity | **Start now** | *no release* | kickoff written |
| **1** — legacy writer retirement | **Start now** | **R-0 blocked** | §1.1 — whole release lane waits on Spec A's cutover |
| **2** — gateway provisioning + `/api/assets` | **Start now** | **R-A blocked** | §1.2 Option A + §1.3 `V20` predecessor; same lane block as R-0 |
| **3** — schema (`V20`) | **3.1 required early** | **R-B blocked** | §1.3 authors `V20`; §1.1 gates applying it, at task 3.5 |
| **4** — contract implementation (23 tasks) | **Start now** | *no release* | requirement 9.2 permits full implementation and testing |
| **5–7** — release waves | downstream of Wave 4 | **blocked** | downstream of R-B |

Waves 0, 1, 2 and 4 hold `7 + 5 + 6 + 23 = 41` of B1's 81 remaining tasks — just over half — and need nothing that does not already exist.

---

## Open items

No open question remains for B1's implementation start. §1.2 is decided (Option A), and §1.3 resolves the `V20` half of it inside the implementation lane. Waves 0, 1, 2 and 4 may all be **built and tested** now.

The one thing still outstanding is **outside B1**: every B1 release — R-0 onward, not only R-B — waits on Spec A's R3a reaching production, which waits on the Neon restore path being proven. That is an operational prerequisite owned by the operator, not an implementation question, and it blocks no implementation work above.
