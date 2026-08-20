# Cursor Kickoff — Spec A task 8.1: refresh set derived from the catalog (R2)

**Date:** 2026-08-19
**Prepared for:** Cursor (implementation)
**Baseline:** `origin/main` @ `10224eb`
**Suggested branch:** `feat/supported-asset-refresh-set` off `main`
**Service:** `market-data-service` only

---

## 0. Resting state — this one is different again

Task 8.1 is release **R2** — "narrow the producer." It is **not** part of R3a (the task-6 branch) or R3b (the Mongo Job). It is its own artifact, it executes nothing irreversible, and it is the **first** thing the cutover deploys after R1.

So unlike tasks 6, 7, and 8.2–8.6, this branch **can be PR'd and merged normally** once green, in a quiet window, under the usual discipline. Merging fires the full `deploy.yml` path (the `market-data-service/**` filter matches), which deploys the narrowed producer and re-seeds the demo.

**Two caveats on that:**

1. Checkpoint 9.2's go condition is *"refresh desired set is Active_Assets; **no execution of a prior Job revision is running**."* Merging R2 satisfies the first half. The second half is a cutover check, not a merge check — it doesn't block merge, but note it: after R2 deploys, the next scheduled 08:00 UTC fire is the first one that emits only catalog tickers.
2. Read §3 before deciding whether this PR can actually merge independently. There is a hazard that may require it to be sequenced with R3b.

Design is frozen at Revision 10. Where a task and the design disagree, the design is normative.

## 1. Scope

**Task 8.1 only:** `resolveTrackedTickers()` returns `Active_Assets`; retire the Mongo union.
_Requirements: 5.1, 5.2, 5.3, 5.6, 5.7_

Out of scope: 8.7, 8.8, anything in `portfolio-service`, anything touching the repair Job.

## 2. What changes

### The method, today

`MarketDataRefreshService.resolveTrackedTickers()` (`~L144`) returns `configured baseline (55) ∪ every ticker present in Mongo`. That union is the `Emergent_Tracked_Set` — the spec's term for "what happened to be seeded in the past," and the structural reason `MM.NS` and `TATAMOTORS.NS` kept refreshing despite appearing in no baseline.

### The method, after

Return `SupportedCatalog.active()` tickers. Nothing else. No baseline, no Mongo scan.

- **5.1** — desired set **equals** the active set. Not a superset.
- **5.2** — the Mongo union is **retired**, not narrowed.
- **5.3** — no Deprecated_Asset is fetched. `TATAMOTORS.NS` drops out of the refresh set on merge.
- **5.6** — a catalog symbol with no Mongo document is a **normal fetch candidate**, not an error. The refresh loop already handles "no existing document" via `findById → orElse(new AssetPrice)`; confirm and test it rather than assume.
- **5.7** — **do not delete** `market_prices` documents for deprecated tickers. Valuation of existing `TATAMOTORS.NS` positions reads them. The refresh simply stops *updating* them.
- **5.4 / 5.5** — unchanged: skip-and-keep-last-price on provider miss, no throw, daily cron, ACA Job as sole path, in-service scheduler stays disabled.

`SupportedCatalog` is already a bean in `market-data-service` (`MarketDataCatalogConfiguration`). Inject it into `MarketDataRefreshService` and drop the `BaselineTickerProperties` dependency from that class.

**Expected production effect:** the refresh set goes from `55 ∪ Mongo` (currently 161-ish including shells) to **159** (`active()`). The next scheduled run's log should show a ticker count of 159 and **zero** skips for `TATAMOTORS.NS` / `MM.NS`. State the expected count in the PR body so the first post-merge run can be checked against it — the same discipline as the 159-holdings prediction in #111.

## 3. The trap the spec doesn't name: `BaselineSeeder`

The spec's requirements and design never mention `BaselineSeeder`. It exists, and it matters here in two ways.

### What it is

`BaselineSeeder` (`ApplicationRunner`, `@ConditionalOnProperty(prefix = "market-data.baseline-seed", name = "enabled", havingValue = "true", **matchIfMissing = true**)`) runs at **every** `market-data-service` startup and inserts a shell `AssetPrice` document into Mongo for each of the 55 baseline tickers not already present. It is the mechanism that *grew* the Mongo set the union reads.

### Problem A — it is live in production and seeds an off-catalog ticker

Verified against `config/seed-tickers.json`: 54 of the 55 baseline tickers are in the catalog. **`GOOG` is not** — the catalog has `GOOGL`. So today `BaselineSeeder` maintains a `GOOG` shell in prod Mongo, the union picks it up, and the refresh fetches it every day. After 8.1 retires the union, the shell still exists and the seeder still re-inserts it on every boot, but nothing reads it.

That is not a data-integrity defect — it's an orphaned shell, harmless to valuation — but it is exactly the "datastore defines capability" pattern Requirement 5 exists to end, and leaving a seeder that plants off-catalog documents contradicts the spirit of 5.2 even if the union is gone.

**Decision, since the design is silent:** retire `BaselineSeeder` along with the union. Once the refresh set comes from the catalog, shell pre-seeding serves no purpose — 5.6 already requires a catalog symbol with no document to be a normal fetch candidate. Remove the class, its properties binding, and the `market.baseline.tickers` list from `application.yml`. Keep `BaselineTickerProperties` only if something else depends on it (grep first; as of `main` only `BaselineSeeder` and `MarketDataRefreshService` use it).

**Escalate if** you find any other reader of the baseline list, or any test fixture that relies on the shells existing. Do not delete the existing `GOOG` document from prod Mongo — 5.7's spirit applies (nothing depends on it, but the task doesn't touch data), and it's a harmless orphan.

### Problem B — it would run inside the repair Job and race the fence

This is the one that matters for the cutover. The repair Job (task 7, `market-data-repair-job`) uses the **same `market-data-service` image** with `SPRING_MAIN_WEB_APPLICATION_TYPE=none` and `MARKET_DATA_REPAIR_ENABLED=true`. `ApplicationRunner`s still run in a non-web context. The repair Job's Terraform sets **no** `MARKET_DATA_BASELINE_SEED_ENABLED`, and `matchIfMissing = true` — so **`BaselineSeeder` runs at the start of every repair Job execution.**

The repair migrates `MM.NS` → `M&M.NS`. Neither is in the 55-ticker baseline (verified), so the seeder would not insert either one directly. But its `findAll()` + `save()` per missing ticker is an unfenced Mongo writer running inside the repair's own process before the repair claims its lease — precisely the class of writer Requirement 7.21 says must be quiesced or fenced during the Mongo repair. Today it happens not to touch the affected documents. That is luck, not design.

**Retiring `BaselineSeeder` in 8.1 closes this.** If for any reason it survives, the repair Job's Terraform must set `MARKET_DATA_BASELINE_SEED_ENABLED=false` explicitly, and `assert_mongo_repair_job_plan.py` must assert it — raise that as a task-7 follow-up rather than folding it in here.

**Sequencing consequence:** if `BaselineSeeder` is retired here, R2 (this PR) should merge **before** the repair Job is ever executed at checkpoint 9.7 — which it will, since R2 precedes R3b in the cutover anyway. Note it in the PR body so the ordering is recorded rather than incidental.

### Correction recorded after implementation (2026-08-19)

Verified during test execution: Azure runs `SPRING_PROFILES_ACTIVE=prod,azure`, and `application-azure.yml` already sets `market-data.baseline-seed.enabled: false` with a comment that seeding is handled outside the app lifecycle. The `azure` overlay wins over `prod`. So **in Azure production, `BaselineSeeder` was already disabled** — on the service *and* on the repair Job, which runs the same profiles. Problems A and B above overstated the live exposure: the seeder was active only under `prod`-alone and `aws` profiles, and the "luck, not design" characterisation was unfair to whoever disabled it deliberately.

The decision to retire it stands on the remaining grounds — it fed the Mongo union that 5.2 retires, it was live in non-Azure profiles, and removing it ends the dependency on profile ordering for a repair-safety property. But the record should say what was true, not what I feared.

## 4. What NOT to change

- `ExternalMarketDataClient.getLatestPrices(tickers)` and the per-ticker loop — unchanged.
- `MarketPriceService.updatePrice` / the Kafka publish path — unchanged.
- `market-data-refresh-job` Terraform — unchanged (the cron, the image, the runner property).
- The runner matrix (`JobRunnerMatrixValidator`) — unchanged.
- `LocalMarketDataSeeder` — that's the local/dev seeder, profile-gated, not this.

## 5. Definition of done

- 8.1 ticked in `tasks.md`.
- `resolveTrackedTickers()` has unit tests proving: returns exactly `active()`; excludes deprecated; does **not** call `AssetPriceRepository.findAll()` (assert no interaction — a Mockito `verifyNoInteractions` or equivalent, so the union can't silently come back); returns a catalog ticker that has no Mongo document (5.6).
- An integration test (Testcontainers Mongo, tagged `integration`) proving a refresh run against a Mongo that contains an off-catalog document (`GOOG` or a synthetic one) **does not fetch it and does not delete it** (5.2 + 5.7 together).
- `BaselineSeeder` removed, or — if escalated and retained — disabled in the repair Job with a plan assertion.
- Full `:market-data-service:test` and `:market-data-service:integrationTest` green — the 15 task-7 repair scenarios and the task-5 runner matrix must not regress. They share the service; run both.
- Spec reference check:

```bash
python scripts/check-spec-references.py .kiro/specs/supported-asset-integrity/tasks.md --against .kiro/specs/supported-asset-integrity/requirements.md --coverage --pairs
```

- PR body states: expected post-merge refresh count (159), the `BaselineSeeder` retirement and why, and the R2-before-R3b ordering note.

## 6. Escalate rather than decide

- Any other reader of `market.baseline.tickers` or `BaselineTickerProperties`.
- Any test that depends on shell documents existing.
- Any reason to keep `BaselineSeeder` — if so, the repair-Job disable + plan assertion becomes mandatory.
- Any temptation to make the refresh set a superset of `active()` "for safety." 5.1 says equals.
