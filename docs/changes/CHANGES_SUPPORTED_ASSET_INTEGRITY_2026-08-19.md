# Changes Summary — Supported Asset Integrity (Spec A)

**Date:** 2026-08-19
**Spec:** `.kiro/specs/supported-asset-integrity/` (requirements.md, design.md Rev 10, tasks.md)
**Branches merged:** `feat/deploy-service-allowlist`, `feat/deploy-prebuilt-digest`, `feat/supported-asset-catalog-module`, `feat/supported-asset-write-boundary`, `feat/supported-asset-refresh-set`, `feat/supported-asset-demo-initializer`, `feat/supported-asset-catalog-size-assertions`
**Branches held (deliberately unmerged):** `feat/supported-asset-postgres-repair`, `feat/supported-asset-mongo-repair`
**Scope:** `common-catalog` (new module), `portfolio-service`, `market-data-service`, `insight-service`, `api-gateway` (deploy workflow only), Terraform, frontend E2E test assertions.

---

## Summary

Spec A closes the write boundary for portfolio holdings ahead of the Asset Picker, which will be
the first mechanism letting arbitrary users create holdings. Before this work, `addHolding` accepted
the ticker field as an opaque string, persisted it, and returned 201 — no validation in any layer,
no foreign key, and cost-basis lookup that missed silently. That was harmless only because no
user-facing path had ever created a holding.

This changelog covers **implementation through wave 8**. The catalog is authoritative, every write
path validates against it, the refresh set is derived from it rather than from historical datastore
contents, and asset-price freshness has an authoritative observation timestamp. **Enforcement ships
dark** — both gates default `false` — and the two data repairs are written, tested, and deliberately
**not merged**, because executing them is the irreversible part of a cutover that has not been
scoped yet.

The production defect that motivated the spec is therefore **diagnosed and repairable but not yet
repaired**: the demo portfolio's `BTC` holding still reads a price frozen at `70,775.00` since
April 2026 (V2 seed), overstating the total by `$5,839.93` — 9.8% — while `partialValuation`
reports `false`. Requirement 7's migrations fix it; checkpoint 9.6 runs them.

---

## Changes by Area

### New shared module — `common-catalog`

Plain Java, no Spring, joining the `common-dto` / `common-observability` convention. Owns
`CatalogEntry`, `LifecycleStatus`, `SupportedCatalog`, `SeedCatalogView`, `CatalogLoadFailedException`.

Integrity validation collects **all** violations into one message rather than failing on the first,
and deliberately asserts no fixed total or per-class count — corporate actions change those
legitimately (Requirement 3.4). `Catalog_Version` is a SHA-256 over ticker, name, sorted aliases,
asset class, quote currency, lifecycle status **and** `basePrice`, truncated to 16 hex characters;
`basePrice` participates because it determines seeded cost bases, so two builds differing only there
are behaviourally different.

`basePrice` is reachable **only** through `SeedCatalogView`, and `SupportedCatalog` deliberately does
**not** implement that interface — an earlier iteration did, which put `basePrice()` on the general
catalog surface and violated Requirement 2.10. `seedView()` returns a distinct object instead.

### Manifest and packaging — `config/seed-tickers.json`

`lifecycleStatus` added to all 160 entries; `TATAMOTORS.NS` marked `DEPRECATED` (Tata Motors
demerged into `TMCV.NS` / `TMPV.NS`, so it resolves nowhere and a successor needs an allocation
rule, not a rename); `USDINR=X` untouched, having been verified transient.

`processResources` now copies the canonical manifest into `build/resources/main/catalog/` in each
consumer. The three `copySeedTickers` tasks and the three git-tracked
`src/main/resources/seed/seed-tickers.json` copies are deleted — the previous arrangement copied
generated content into tracked source directories, so a direct edit to a service copy was silently
reverted by the next build.

**`MM.NS` → `M&M.NS` is deliberately deferred** to task 6, per Requirement 4.8: the manifest rename
must be accompanied by Requirement 7's data migration, or it orphans every row referencing the old
symbol.

### Consumer adoption — fail-closed catalog loading

All three services load through `SupportedCatalog`. `insight-service`'s fail-open
log-error-set-empty-continue path is deleted. A bad or missing manifest now **fails startup** in
every normal profile, emitting structured `catalog_load_failed` before the failure propagates — the
event is the only artefact that will exist, since there is no healthy process left to query.

`@Async` removed from `MarketPriceProjectionService` (design D10). Two consequences made it
unusable: the listener acknowledged records while the projection was still queued, so consumer lag
proved nothing about drainage — and the cutover's central evidence would have been measuring the
wrong thing; and a database failure inside an async task never reached Kafka's error handler.

Actuator `health,info` exposure added for `portfolio-service` and `insight-service` — never a
wildcard, which would publish `env` and `configprops` on services holding database credentials.

### Write-boundary validation — shipped dark

`SupportedAssetValidator` + `UnsupportedAssetException`: canonical ticker only, no alias
resolution, in-process, no cross-service call. `@RestControllerAdvice` returning HTTP 422
`unsupported_asset` + ticker + catalogVersion on **every** `Http_Entry_Point` including the internal
seed endpoints — `market-data-service` had no advice at all and one was created, not extended.

Deprecated-position rules: reduce and remove permitted, create and increase rejected, never
retroactive. Both enforcement properties gate at `false`:
`app.catalog.reject-unsupported-events`, `app.catalog.enforce-holding-invariant`.

**One change in this wave was not gated and did alter production:** both seed paths switched from
`registry.all()` to `SupportedCatalog.active()`, so `TATAMOTORS.NS` stopped being seeded and the
demo seed dropped from 160 holdings to 159. Without it the seeder would keep writing a deprecated
asset and roll back its own repair once enforcement is on.

### Refresh suspension machinery and runner matrix — `market-data-service`

A suspended-mode `CommandLineRunner`, mutually exclusive with the refresh runner: writes nothing,
emits `refresh_suspended`, flushes telemetry, exits `0`. `JobRunnerMatrixValidator` makes every
invalid property combination a **startup failure** rather than a silent precedence rule —
specifically `refresh=false` + `repair=true`, which would start both the repair runner and the
suspended runner, and the latter's `SpringApplication.exit(0)` could terminate the context out from
under an in-flight repair.

Terraform gained `ingress_enabled` on the `container-app` module (`dynamic "ingress"` omitted when
false), which is the maintenance-window mechanism, plus two Python plan assertions: that changing
`MARKET_DATA_JOB_RUNNER_ENABLED` is an in-place `update` rather than create/delete — in the pinned
`azurerm 4.81.0`, `schedule_trigger_config` is `ForceNew`, so this is what proves the suspension
mechanism avoids replacing the Job — and that `ingress_enabled = false` does not replace the gateway
app.

### Refresh set derived from the catalog (R2) — `market-data-service`

`resolveTrackedTickers()` returned `configured baseline (55) ∪ every ticker present in Mongo`. That
union — the spec's `Emergent_Tracked_Set` — is the structural reason `MM.NS` and `TATAMOTORS.NS`
kept refreshing despite appearing in no baseline: the datastore was defining product capability.

It now returns `SupportedCatalog.active()` and nothing else. Deprecated tickers are no longer
fetched, and their `market_prices` documents are **not** deleted (Requirement 5.7) — valuation of
existing deprecated positions still reads them.

`BaselineSeeder` and `BaselineTickerProperties` are retired along with the union. The seeder planted
shell Mongo documents for 55 baseline tickers at every startup and was the mechanism that grew the
set the union read; 54 of the 55 were in the catalog, `GOOG` was not (the catalog has `GOOGL`).

### Demo portfolio initializer — built, shipped gated off

`DemoPortfolioInitializer` converges the demo account's portfolio to a deterministic desired state
derived from the active catalog. `app.demo.seed-on-startup` defaults `false`; with the gate off it
returns before taking a lock, reading, or comparing.

The cost-basis anchor moved from `Instant.now().minus(25h)` to a fixed `app.demo.cost-basis-anchor`.
Without that the desired state differs on every boot, the comparison always fails, and the component
meant to make the demo portfolio survive CI would destructively reseed on every restart. This also
makes the E2E seed deterministic across runs, which strengthens Requirement 11's byte-identity
regression rather than weakening it.

`pg_advisory_xact_lock` is the **first statement** of the same transaction and JDBC connection that
compares and may call `seed()`, acquired via `Session.doWork` — a `JdbcTemplate` from the same
`DataSource` would have taken a second pooled connection and serialised nothing. Three replicas
therefore produce exactly one mutation and N−1 no-ops, each logging which branch it took, because
that log is checkpoint 9.12's evidence and cannot be reconstructed afterwards.

### Live catalog-size assertions — frontend E2E

`api-live-smoke.spec.ts` asserted `holdingsInserted >= 160`. Once both seed paths moved to
`active()` enumeration the seed produced 159, and the Azure synthetic monitor **failed on every
scheduled run from 2026-08-18** — exactly as Requirement 8.8 predicted it would *"as soon as
`TATAMOTORS.NS` is deprecated without a successor being added."*

New `frontend/tests/e2e/helpers/catalog.ts` derives `activeAssetCount()` and `activeTickers()` from
the canonical manifest; the assertion is now equality, not a floor. A lower bound is no better than
an exact literal, because it stops detecting a partial seed that still clears the floor.

The dormant `aws-synthetic` suite carried the same defect plus a worse one — it sampled
`TATAMOTORS.NS` by name, so it would have failed immediately if that provider were ever selected.
Its literals are derived and its samples are now asserted to be Active_Assets.

### Deploy pipeline — Wave P (prerequisite for the cutover)

`deploy-azure.yml` gained whole-workflow service selection: empty `services` input is a full deploy;
non-empty is a scoped backend deploy with `deploy-frontend`, `seed` and `verify` skipped. Scoping is
a property of the whole workflow, not just the backend matrix — an unselected service receives **no**
`az containerapp update` at all, because the matrix is built from the selection rather than
hardcoded.

A prebuilt-digest path accepts `repository@sha256:…` and updates `portfolio-service` to that exact
manifest digest without building, pushing or retagging. It is deliberately narrow: a generic form
would accept `market-data-service`, whose Container App would take the supplied digest while
`market-data-refresh-job` still moved by `${github.sha}` tag, breaking the exact-artifact invariant
inside one logical deployment.

---

## Tests Run

| Suite | Result |
|---|---|
| `./gradlew :common-catalog:test` | ✅ BUILD SUCCESSFUL |
| `./gradlew :portfolio-service:test` | ✅ 176/176 |
| `./gradlew :portfolio-service:integrationTest` | ✅ 84/84 |
| `./gradlew :market-data-service:test` | ✅ 108/108 |
| `./gradlew :market-data-service:integrationTest` | ✅ 22/22 |
| `./gradlew :insight-service:test` | ✅ BUILD SUCCESSFUL |
| Wave P deploy-workflow contract (Python, in `ci-verification.yml`) | ✅ 54/54 |
| Terraform plan assertions (`assert_job_runner_env_update.py`, `assert_ingress_enabled_plan.py`) | ✅ 9/9 |
| `npx tsc --noEmit` (frontend) | ✅ clean |
| Azure synthetic monitoring, run [32250934817](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32250934817) | ✅ 9/9 — ends a two-day failure streak |
| `scripts/check-spec-references.py --coverage --pairs` | ✅ 158/174 cited, declared gaps equal actual |

**On the held branches** (green locally, not merged): `PostgresRepairMigrationIT` 18/18 and
`MarketPriceProjectionTupleIT` 10/10 on `feat/supported-asset-postgres-repair`;
`MongoMmNsRepairIT` 15/15 on `feat/supported-asset-mongo-repair`.

### Production verification

| Claim | Evidence |
|---|---|
| Catalog loads fail-closed in all three services | `catalog_loaded version=c3dcb95e4e09212a entries=160 active=159 rejectUnsupportedEvents=false enforceHoldingInvariant=false`, identical across `portfolio-service`, `market-data-service`, `insight-service` — proving all three packaged byte-identical manifests, not merely that each started |
| Seed enumerates active assets only | `verify` reports `totalHoldings: 159` (was 160) — run [32171587619](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32171587619) |
| Scoped deploy touches one service | Exactly one `deploy (…)` matrix entry vs four in full mode; `assert-scoped-non-interference` green — runs [32099750088](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32099750088), [32100281322](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32100281322) |
| Digest deploy performs no build | Step-level `Build Docker image` and `Push Docker image` both `skipped`, plus a dedicated `Prove digest path skipped build and push` step — run [32123580730](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32123580730) |

---

## Known Gaps / Follow-ups

- **The Postgres and Mongo repairs are written and unmerged.** Executing them is checkpoints 9.6
  and 9.7, the two irreversible steps. Both branches are complete with green test matrices; neither
  has a PR, deliberately.
- **Requirement 8.9 is unsatisfied.** Catalog version has no HTTP surface: `/actuator/info` returns
  `200` with body `{}`. The design states it *"still carries the same values for interactive
  debugging"*, but no `InfoContributor` was implemented. Cross-service catalog identity is verified
  at checkpoint 9.9 by structured startup log instead. Closing 8.9 needs contributors on all three
  consumers plus gateway routes to per-service actuators.
- **The Neon restore path is unproven and unowned.** Checkpoint 9.6's precondition is a *verified*
  Postgres backup; rolling back past R3a is a database restore, not a revert, and a Postgres backup
  does not restore the Mongo collection R3b rewrites. This is the largest open risk in the cutover.
- **`deploy.yml` has no `concurrency:` group.** Two merges landing close together start concurrent
  production deploys, each running `seed` — a data-plane writer. Not a blocker today; relevant
  during a cutover where several things merge in sequence.
- **No CI check enforces the Docker COPY-or-trim invariant.** `common-catalog` was the first
  partially-used shared module, and each Dockerfile must either `COPY` a module or delete its
  `include` line from `settings.gradle`. Currently maintained by hand; the failure mode is a
  Gradle 9 error at image-build time that no local suite catches.
- **`TATAMOTORS.NS` Corporate_Action_Migration remains an open decision** (Requirements 4.4, 4.5).
  A demerger splits one instrument into two and needs an allocation rule; no successor is
  substituted silently.

---

## Guardrails Respected

- **Enforcement never activated.** Both gates remain `false` in every deployed profile; the
  cutover's R4 step is what flips them, and Terraform overrides are removed only after both repairs
  are verified.
- **No Flyway migration shipped.** `V17`–`V19` exist only on the held repair branch; B1 owns `V20`.
  Two migrations with the same number make Flyway refuse to start, so the allocation is tracked
  rather than assumed.
- **No frontend production change.** Spec A delivers no interactive feature; the only frontend edits
  are E2E test assertions.
- **Price-write invariant preserved.** `portfolio-service`'s seed path writes portfolios and holdings
  only, in every profile — retained as a regression boundary (Requirement 11), including the
  full-table byte-identity check across repeated seeds.
- **Deprecated positions retained, not repaired away.** Any persisted `TATAMOTORS.NS` holding is left
  byte-unchanged: not deleted, not reassigned (Requirement 7.28), pending the corporate-action
  decision.
- **No fixed catalog size asserted anywhere against the live or canonical catalog** (Requirement
  8.10). Fixture-based tests may still assert their own known size.

---

## Design Corrections Made During Implementation

Two errata were raised against a frozen design rather than resolved in code.

**Revision 10 (merged, PR #112)** — four bounded corrections to Section 12, found in
pre-implementation review of task 6. The material one: V17's history `ALTER` was specified without a
`USING` clause, so Postgres's default cast to `TIMESTAMP(3)` would **round**, while the preflight and
the live history writer both **truncate**. The conversion would have collided on a different key
than the preflight checked, and given converted rows a different identity than live-written ones —
the dual-identity defect D9 exists to remove. Fixed to
`USING date_trunc('milliseconds', observed_at)`. Also: a corrected preflight rationale (the unique
index makes post-`ALTER` discovery a loud transactional rollback, not a silent merge), a
deterministic collapse survivor (lowest original `id`), and two `repair_audit` references corrected
to `repair_archive`, which is the only table that can structurally hold a discarded price row.

**Kickoff-level correction, recorded but not a design change** — the task 8.1 analysis claimed
`BaselineSeeder` ran unfenced inside the Mongo repair Job's process. `application-azure.yml` already
disabled it and Azure runs `prod,azure` with the `azure` overlay winning, so in production it was
already off on both the service and the Job. Retirement still stands on the remaining grounds, but
it was deliberate prior work rather than luck.

---

## What Ships Next

Spec A's implementation is complete through wave 8. The remaining work is a cutover
(task 9), which is a sequence of stop/go checkpoints rather than code: suspend the refresh Job and
verify the suspension, drain Kafka to lag-zero on every partition, disable `api-gateway` ingress via
Terraform, take a **verified** Postgres backup, then run R3a and R3b in order.

That sequence cannot be scoped until the Neon restore path is proven. Everything it consumes now
exists.
