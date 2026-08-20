# Cursor Kickoff — Spec A tasks 4–5 (write-boundary validation, gated off + refresh suspension)

**Date:** 2026-08-18
**Prepared for:** Cursor (implementation)
**Baseline:** `origin/main` @ `84c0c97`
**Predecessor:** Spec A tasks 1–3 merged (`844855f`), catalog live in production — all three services log `catalog_loaded version=c3dcb95e4e09212a entries=160 active=159`, both enforcement flags `false`.

---

## 1. Scope

**Spec A tasks 4 and 5 only**, from `.kiro/specs/supported-asset-integrity/tasks.md`:

- **Task 4** — write-boundary validation and freshness code, **gated off**
- **Task 5** — refresh suspension machinery and Terraform plan safety

**Task 6 is NOT in scope.** It is the Postgres repair (`V17`–`V19`), the first irreversible wave, and it carries the deferred `MM.NS` → `M&M.NS` rename. It gets its own kickoff and its own decision point.

Suggested branch: `feat/supported-asset-write-boundary`

## 2. Why these two together

Task 4 builds the enforcement machinery but ships it **dark** — both properties default `false` (4.6). Task 5 builds the suspension machinery the Mongo repair will need, and its Terraform work is plan-level only. Neither turns anything on. That is what makes them one safe increment ahead of the irreversible wave.

## 3. Hard constraints

- **Do not create any Flyway migration.** `V17`–`V19` belong to task 6; B1 owns `V20`. Two migrations with the same number make Flyway refuse to start.
- **Do not reintroduce the `MM.NS` → `M&M.NS` rename.** It was deliberately backed out of tasks 1–3 per **Requirement 4.8**, which requires the manifest rename to be accompanied by Requirement 7's data migration. It lands with task 6. `MM.NS` stays `ACTIVE` until then, and that is correct — 4.7 only forbids that state once the refresh set is catalog-derived, which is task 8.
- **Where a task and the design disagree, the design is normative.** Spec A design is frozen at Revision 9. Raise it; do not resolve it in code.
- **Scope by task number.** The prose overview counts waves from 0 while the task list numbers from 1. This kickoff uses task numbers.
- **No frontend changes.**

## 4. Task 4 — write-boundary validation (gated off)

| Subtask | What |
|---|---|
| 4.1 | `SupportedAssetValidator` + `UnsupportedAssetException`. Canonical ticker only — **no alias resolution**, in-process, no cross-service call. |
| 4.2 | `@RestControllerAdvice` → HTTP 422 `unsupported_asset` + ticker + catalogVersion, on **every** `Http_Entry_Point` including the internal seed endpoints. |
| 4.3 | Typed failure and rollback for `Application_Operation` and `Direct_Caller` — identical detection regardless of caller, no partial mutation. |
| 4.4 | Deprecated-position rules: reduce/remove permitted, create/increase rejected, never retroactive. |
| 4.5 | Both seed paths enumerate `SupportedCatalog.active()` via `SeedCatalogView`. |
| 4.6 | Gate both enforcement properties, default `false`: `app.catalog.reject-unsupported-events`, `app.catalog.enforce-holding-invariant`. |

### Trap 1 — `market-data-service` has no `@RestControllerAdvice` at all

4.2 reads as if extending an existing handler. Two services have one; the third does not:

- `portfolio-service/src/main/java/com/wealth/portfolio/GlobalExceptionHandler.java` ✔
- `insight-service/src/main/java/com/wealth/insight/GlobalExceptionHandler.java` ✔
- `market-data-service` — **none**

But `market-data-service` does expose an `Http_Entry_Point`: `MarketDataSeedController` at `@RequestMapping("/api/internal/market-data")` + `@PostMapping("/seed")`. "Every `Http_Entry_Point`" therefore means **creating** an advice in `market-data-service`, not just editing two. Assert the 422 contract identically in all three.

### Trap 2 — 4.5 is NOT gated, and it changes production behaviour

4.6 gates the two *enforcement* properties. It does **not** gate 4.5. So while 4.1–4.4 ship dark, the seed-enumeration switch takes effect the moment this merges.

Concretely: the seeder currently enumerates `registry.all()`, which is why the last production seed wrote `holdings=160` including `TATAMOTORS.NS`. After 4.5 it enumerates `active()` — 159 entries, and `TATAMOTORS.NS` stops being seeded.

That is the intended behaviour and the reason 4.5 exists (without it the seeder re-adds `TATAMOTORS.NS` and rolls back task 6's own repair). But it means **this merge is not behaviour-neutral**, unlike tasks 1–3. Treat the merge as a production data change and take a window.

State the expected post-merge seed count in the PR body so the deploy can be checked against it rather than eyeballed.

## 5. Task 5 — refresh suspension and Terraform plan safety

| Subtask | What |
|---|---|
| 5.1 | Suspended-mode runner: second `CommandLineRunner`, mutually exclusive with `MarketDataRefreshJobRunner`. Writes nothing — no Mongo, no Kafka, no Postgres. Emits `refresh_suspended` with the Job execution identity, flushes telemetry, calls `SpringApplication.exit(0)` — a suspended run is a **success**. |
| 5.2 | Test the **full runner matrix** (below). |
| 5.3 | Terraform `ingress_enabled` input on the `container-app` module; `dynamic "ingress"` omitted when false; gateway wired to it. |
| 5.4 | Plan assertion: changing the runner env var must show `update`, not `create`/`delete`. |
| 5.5 | Plan assertion: `ingress_enabled = false` does not replace the gateway app. |
| 5.6 | Global-constraints review checkpoint — walk the list, confirm waves 0–5 violated none. Review, not code. |

### Trap 3 — the runner matrix must fail startup, not resolve by precedence

This is 5.2's whole point and the subtlest thing in the wave. `MARKET_DATA_JOB_RUNNER_ENABLED=false` activates the **suspended** runner, which calls `SpringApplication.exit(0)`. A repair Job configured as "repair enabled, refresh disabled" would start **both** the repair runner and the suspended runner — and the suspended one could exit the context out from under an in-flight repair.

| context | refresh property | repair property | active runner |
|---|---|---|---|
| long-running service | absent | absent | **neither** |
| scheduled Job, normal | `true` | absent | refresh only |
| scheduled Job, suspended | `false` | absent | suspended only |
| repair Job | **absent** | `true` | repair only |
| any other combination | — | — | **fail startup** |

Assert exactly one runner in every valid row, and a **startup failure** for every invalid one. A silent precedence rule that happens to pick the right runner is not acceptable — it would pass tests and still permit the dangerous combination to be configured.

Also assert the suspended runner terminates with exit 0 rather than running to replica timeout: a hung no-op produces a failed execution and no signal, which is worse than useless.

### Trap 4 — 5.4 is the load-bearing plan assertion

In the pinned `azurerm 4.81.0`, `schedule_trigger_config` is `ForceNew`. Editing it would **replace** the Job rather than update it. 5.4's Python plan test is what proves the chosen mechanism avoids that, and what will fail loudly on a provider upgrade. It is not a formality — it is the evidence for the whole suspension design.

## 6. Verified anchors (checked against `84c0c97`)

**Java**

- `market-data-service/src/main/java/com/wealth/market/MarketDataRefreshJobRunner.java` — the runner 5.1 must be mutually exclusive with
- `portfolio-service/.../seed/PortfolioSeedService.java`, `.../seed/SeedTickerRegistry.java`
- `market-data-service/.../seed/MarketDataSeedService.java`, `.../seed/SeedTickerRegistry.java`, `.../LocalMarketDataSeeder.java`
- Seed entry points: `PortfolioSeedController` (`/api/internal/portfolio` + `/seed`), `MarketDataSeedController` (`/api/internal/market-data` + `/seed`), each guarded by an `InternalApiKeyFilter`
- Both `SeedTickerRegistry` classes already take `(SupportedCatalog, SeedCatalogView)` after tasks 1–3 — 4.5 changes which enumeration they read, not their wiring

**Terraform**

- `infrastructure/terraform/azure/main.tf:393` — `resource "azurerm_container_app_job" "market_data_refresh"`
- `infrastructure/terraform/azure/main.tf:484` — `MARKET_DATA_JOB_RUNNER_ENABLED`
- `infrastructure/terraform/azure/modules/container-app` — the module 5.3 extends
- Plan-assertion convention: `infrastructure/terraform/azure/scripts/assert_plan.py`, `assert_observability_plan.py`, `assert_recovery_plan.py`, `assert_job_identity_migration.py`, each with a `test_*.py` sibling. Match that shape — a new assertion script plus its own unit test.

Re-verify before editing; anchors have moved twice already this wave.

## 7. Definition of done

- Task 4 and 5 checkboxes ticked in `.kiro/specs/supported-asset-integrity/tasks.md`, same PR.
- Full suites green — `:common-catalog:test`, `:portfolio-service:test`, `:market-data-service:test`, `:insight-service:test`. Not filtered runs: the Spring context changes in 4.2 and 5.1 are exactly what a `--tests` filter hides.
- New Terraform plan assertions have unit tests, per the existing convention.
- Both enforcement properties verifiably default `false` — assert it, don't assume it.
- Expected post-merge seed count stated in the PR body.
- Spec reference check passes:

```bash
python scripts/check-spec-references.py .kiro/specs/supported-asset-integrity/tasks.md --against .kiro/specs/supported-asset-integrity/requirements.md --coverage --pairs
```

## 8. Merge effects

Tasks 4–5 touch files under `portfolio-service/**`, `market-data-service/**`, and `insight-service/**`, all in `deploy.yml`'s `paths:` filter. **Merging fires a full production deploy and re-seeds the demo** — and per Trap 2 that seed will differ from the last one. Take a window.

## 9. Escalate rather than decide

- Any anchor in §6 that no longer matches.
- Any need for a Flyway migration number.
- Any pressure to enable either enforcement property in this wave.
- Any runner-matrix combination that cannot be made to fail startup cleanly.
- Any task that appears to conflict with design Revision 9.
