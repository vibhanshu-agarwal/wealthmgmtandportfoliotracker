# Review request: is `feat/supported-asset-postgres-repair` safe to rebase onto current `main` by dropping its `addHolding`/`requireHoldingWrite` code?

## Context (self-contained — no prior conversation assumed)

Repo: `wealthmgmtandportfoliotracker`. Spec `supported-asset-integrity` ("Spec A") has an irreversible
production cutover in progress (`.kiro/specs/supported-asset-integrity/tasks.md`, Task 9). Checkpoints
9.1–9.5 are done and verified against live Azure/Postgres/Kafka state: the market-data refresh Job is
suspended, gateway ingress is closed (site offline), Kafka consumer lag is zero. This is all reversible
and already applied.

Checkpoint 9.6 is the next step: **R3a, the Postgres repair — marked IRREVERSIBLE.** Its payload
(Flyway migrations V17–V19 plus supporting app code) exists only on an unmerged branch,
`feat/supported-asset-postgres-repair`, two commits:

- `76e39e8` "Spec A task 6: Postgres repair V17-V19 with startup integrity gate." — the actual
  migrations (`V17__Repair_Archive_And_Timestamp3.sql`, `V18__Migrate_Btc_To_BtcUsd.sql`,
  `V19__Migrate_MmNs_To_Mahindra.sql`) plus `PostMigrationIntegrityAssertion`.
- `ffd814b` "Spec A tasks 8.2-8.6: project observed_at and report asset-price freshness." — changes
  to `MarketPriceProjectionService`, `PortfolioService` (the freshness/valuation query), new DTOs.
  Commit message states explicitly: *"R3a is V17-V19 plus the tuple projection and freshness
  response; those must ship in the same deploy because Flyway runs on portfolio-service boot."*
  I.e. this bundling is deliberate — V17–V19 changes `market_price_history.observed_at`'s column
  type, so the app code reading/writing that table must match in the same deploy, not because these
  are unrelated features being smuggled in together.

**The problem:** this branch was created 2026-08-19 and has not been touched since. `main` has moved
on. `git merge-base --is-ancestor origin/main origin/feat/supported-asset-postgres-repair` returns
false (exit 1) — main is not an ancestor, i.e. the branch is missing 5 days of main history.

A three-way merge-tree check (`git merge-tree $(git merge-base origin/main origin/feat/supported-asset-postgres-repair) origin/main origin/feat/supported-asset-postgres-repair`) shows textual conflicts in
`portfolio-service/src/main/java/com/wealth/portfolio/PortfolioService.java`, all in the imports/
constructor-fields region where both sides added unrelated things around the same lines — mechanically
resolvable — **plus one substantive issue**, described below.

## The substantive issue

The repair branch's `PortfolioService.java` (inherited unchanged from its base, not touched by either
of the branch's own two commits — verified via `git diff 76e39e8 ffd814b -- .../PortfolioService.java`,
which shows zero lines touching `addHolding` or `requireHoldingWrite`) still contains:

```java
@Transactional
public PortfolioResponse addHolding(String userId, UUID portfolioId, String ticker, BigDecimal quantity) {
  ...
  supportedAssetValidator.requireHoldingWrite(ticker, currentQuantity, quantity);
  ...
}
```

Five days after this branch was cut, **`main` deleted this entire code path**:

1. `PR #125` ("B1 Wave 1... legacy writer retirement") deleted `PortfolioService.addHolding()`,
   `PortfolioController`'s corresponding endpoint, and their tests from `main` — confirmed via the
   fast-forward diff that merged B1 Wave 1 work (`PortfolioController.java | 42 -`,
   `PortfolioServiceHoldingValidationTest.java | 82 -`).
2. Commit `078cf2a` ("chore: remove orphaned SupportedAssetValidator.requireHoldingWrite") then
   removed `SupportedAssetValidator.requireHoldingWrite` itself from `main`, with the message:
   *"B1 Wave 1 (PR #125) deleted PortfolioService.addHolding(), which was the only caller of
   requireHoldingWrite anywhere in portfolio-service... Confirmed zero other callers before
   deleting. Full portfolio-service suite: 172/172 green."*

That "confirmed zero other callers" claim was true against what was merged into `main` at the time —
it could not have known about this unmerged branch. `SupportedAssetValidator` on `main` today has only
`requireActive(String ticker)` (single-arg, catalog-membership check only, gated by
`app.catalog.enforce-holding-invariant`) — the 3-arg `requireHoldingWrite(ticker, currentQuantity,
quantity)` the branch calls does not exist on `main` at all anymore.

**Checked independently just now: `main` currently has *no* holdings write endpoint at all.**
`PortfolioController.java` on `main` today has only `GET /health` and `GET` (list). No `PUT`/`POST` for
holdings exists. B1's *new* intended write path — `PUT /api/portfolio/holdings` via a
`CompositionController`, per `portfolio-composition-contract` Wave 7 — has not been built yet (no
`Composition*.java` file exists anywhere in `portfolio-service/src/main/java` on `main`). Wave 7 is
gated behind Waves 3/5/6, which are themselves gated behind *this same Spec A cutover* completing.

## What I think this means (verify, don't just trust this)

The repair branch isn't making a deliberate architectural choice to keep the legacy write path — it's
simply unaware that `main` retired it 5 days after the branch was cut. Since `ffd814b`'s own diff never
touches `addHolding`/`requireHoldingWrite`, and there is currently no live write path on `main` for that
validation to guard anyway (old one deleted, new one not built yet), my working hypothesis is:

**Rebasing this branch onto current `main` should drop `addHolding()`, its controller endpoint, and
`requireHoldingWrite` entirely — taking `main`'s side of that deletion — while keeping everything else
from both commits (the V17–V19 migrations, `PostMigrationIntegrityAssertion`, and the freshness/
projection changes in `MarketPriceProjectionService`/`PortfolioService`'s valuation query, which are
independent of `addHolding` and still needed for R3a per `ffd814b`'s own stated Flyway-boot-coupling
reason).**

Requirement 6 (write-boundary validation) would then simply have no code to attach to right now, which
is consistent with its own design — `SupportedAssetValidator.requireActive` already exists on `main`,
gated off, waiting for whichever write path eventually calls it (presumably B1 Wave 7's new
`CompositionController`, when that gets built — a later, separate task, not part of this cutover).

## What to actually check

1. Is the "zero callers, safe to drop" read above correct — does anything else on the repair branch,
   or anything the migrations/assertions depend on, actually need `addHolding`/`requireHoldingWrite`
   to exist? (`git grep -l "addHolding"` on the branch also hits `Portfolio.java` and
   `PortfolioSeedService.java` — confirm those are the unrelated domain-entity method
   `Portfolio.addHolding(AssetHolding)` and the seeder's own call to it, not the deleted
   service-level method, before concluding they're unaffected.)
2. Does `PostMigrationIntegrityAssertion` (new in `76e39e8`) or its test
   (`PostMigrationIntegrityAssertionTest`) assume anything about the write-boundary validator that
   would break if `requireHoldingWrite` doesn't exist?
3. Is there a reason Requirement 6's validation *should* be wired somewhere on `main` today, ahead of
   B1 Wave 7, that I'm missing — i.e. is dropping it from this deploy actually a regression rather
   than a correct no-op?
4. After the drop, does the branch's remaining content (migrations + freshness/projection code) still
   compile and pass its own test suite against current `main`'s actual schema/code — not assumed from
   the branch's 5-day-old "tested green" state?

Report back: confirm or refute the hypothesis above, and flag anything else the merge-tree conflict
check might have missed (it only found conflicts in one file; verify that's actually complete against
current `main`, not an artifact of the specific merge-base used).

---

## Resolution — CONFIRMED (Codex review, then independently reproduced 2026-08-22/23)

Codex confirmed the hypothesis and found the merge-tree check's "one file" conflict list was
incomplete — the actual conflict set spans four files, not one. Cherry-picking both commits onto
current `main` (`76e39e8` then `ffd814b`) reproduces this exactly:

**`76e39e8` (V17–V19 migrations) rebases cleanly onto current `main` — no conflicts.**

**`ffd814b` (freshness/projection) conflicts in four files, all resolved by taking `main`'s side of
the legacy-writer deletion and `ffd814b`'s side of everything else:**

1. **`PortfolioService.java`** — four narrow conflict regions (import block, field declaration,
   constructor signature, constructor-body assignment), none touching `addHolding` itself (the
   3-way merge already dropped that automatically, no conflict marker around it — confirming it
   really was untouched by `ffd814b`, exactly as this brief's hypothesis required). Resolved: drop
   the `SupportedAssetValidator` import/field/constructor-param/assignment entirely; keep
   `AssetPriceFreshnessDto` import and `AssetPriceFreshnessProperties freshnessProperties`
   field/param/assignment.
2. **`application.yml`** — `app.demo` (main's) and `app.asset-price-freshness` (ffd814b's) are
   sibling keys under `app:`, not overlapping. Resolved: keep both blocks.
3. **`PortfolioServiceFxTest.java`** — constructor call conflict. Resolved: drop the `mock()`
   (obsolete `SupportedAssetValidator` arg), keep `AssetPriceFreshnessProperties.defaults()`.
4. **`PortfolioServiceHoldingValidationTest.java`** — modify/delete conflict (deleted on `main` by
   PR #125, modified by `ffd814b`). Resolved: `git rm`, preserving `main`'s deletion.

**Two additional stale-test issues found by Codex, not visible from the merge-tree check at all**
(both in `PortfolioServiceFreshnessValuationTest.java`, new in `ffd814b`, so no conflict — the file
just silently carried latent bugs forward):

5. **Obsolete `mock()` argument** in the test's own `PortfolioService` constructor call — same shape
   as fix #3, just in a different file the merge-tree diff never flagged since it wasn't a textual
   conflict (only `ffd814b` ever touched this file).
6. **Hardcoded past timestamp as a "fresh" fixture.** `NOWISH = Instant.parse("2026-08-19T08:00:00Z")`
   was used both to construct a deliberately-STALE fixture (`NOWISH.minusSeconds(51*3600)`) and a
   deliberately-FRESH one (`NOWISH.minusSeconds(3600)`) — but `PortfolioService#getSummary` evaluates
   freshness against real `Instant.now()`, not the test's `NOWISH`. Once real time passed the 50h
   threshold from 2026-08-19 (i.e. by 2026-08-21), the "fresh" fixture silently started evaluating as
   `STALE`, failing `unavailableFx_stillExcludesAndSetsPartialValuation_withoutChangingFreshnessMeaning`
   — a test-suite time bomb with no code change required to trigger it, only the passage of time.
   Fixed at the root: `NOWISH` changed from a hardcoded literal to `Instant.now()` (evaluated once at
   class-load/test-run time), so both derived fixtures stay correctly relative to whenever the suite
   actually runs.

**Independently reproduced and verified** (not just relayed from Codex's report): rebased both
commits onto current `main` in a real local branch (`infra/9.6-postgres-repair`), applied all six
fixes above, then ran `./gradlew.bat :portfolio-service:test :portfolio-service:integrationTest
--no-daemon --rerun-tasks` myself. `BUILD SUCCESSFUL`. Summed the actual JUnit XML reports rather
than trusting the console tail: **193 unit tests / 0 failures, 118 integration tests / 0 failures** —
exact match to Codex's independently-run numbers. `grep -rn "requireHoldingWrite\|supportedAssetValidator\b"`
across `portfolio-service/src/main` and `src/test` confirms zero remaining references outside
`PortfolioSeedService`'s unrelated, untouched `requireActive` usage.

Branch `infra/9.6-postgres-repair` now holds this resolved state, not yet pushed or merged.
