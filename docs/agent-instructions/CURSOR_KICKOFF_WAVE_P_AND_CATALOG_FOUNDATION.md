# Cursor Kickoff — Wave P (deploy prerequisites) + Spec A catalog foundation

**Date:** 2026-08-18
**Prepared for:** Cursor (implementation)
**Baseline:** `origin/main` @ `4945649` (PR #104 merged)

---

## 1. What you are building

Two units of work, in this order:

1. **Wave P** — `deploy-azure.yml` service allowlist (P-A), then prebuilt-digest deploy path (P-B). Two ordered PRs.
2. **Spec A tasks 1–3** — the `common-catalog` module, manifest + packaging, and consumer adoption.

Nothing else. Do not start Spec A tasks 4+ or any B1 work without a new kickoff.

## 2. Why this order

Wave P is smaller (9 tasks) but goes first for a safety reason, not a scheduling one.

Spec A **task 6** is the Postgres repair — the first irreversible step, operating on live data. Wave P is what gives you scoped, single-service deploys with proven non-interference. That capability must be proven *before* migrations start shipping to Azure, not after.

Wave P is also fully independent: it blocks the B1 release lane but has no dependency on Spec A, so it cannot be invalidated by anything Spec A does.

## 3. Where the specs live — read this before looking for files

| Spec | Location | State |
|---|---|---|
| **Spec A** `supported-asset-integrity` | `.kiro/specs/supported-asset-integrity/` on `main` | requirements + design + tasks merged; **zero implementation** |
| **Spec B1** `portfolio-composition-contract` | branch `origin/spec/portfolio-composition-contract`, **unmerged** | requirements frozen Rev 6; design Rev 10; tasks Rev 7 |

**Wave P's task text lives in B1's `tasks.md`, on the unmerged branch.** To read it:

```bash
git show origin/spec/portfolio-composition-contract:.kiro/specs/portfolio-composition-contract/tasks.md
```

Wave P is the section headed `## Wave P — Deployment prerequisites`. Its `_Requirements:_` trailers (1.17, 1.19, 1.21–1.24, 9.7) refer to **B1's** requirements, in the same branch's `requirements.md`.

## 4. Hard constraints

- **Do not renumber Flyway migrations.** Spec A owns `V17`–`V19`; B1 owns `V20`. Two migrations with the same number do not merge badly — Flyway refuses to start. Neither number is in scope for this kickoff, but do not create any `V17+` file opportunistically.
- **Where a task and the design disagree, the design is normative.** Raise it; do not resolve it in code. Spec A design is frozen at Revision 9; B1 requirements are frozen at Revision 6.
- **Do not modify Spec A's frozen spec body.** A later B1 task pins it with a snapshot test.
- **Scope by task number, not wave number.** Spec A's prose overview counts waves from 0 while its task list numbers from 1, so "Wave 2" and "task 2" are different things. This kickoff uses **task numbers** throughout.
- **No frontend changes.** Both specs explicitly deliver none. The picker UI, draft/conflict UX, and presence banner are Spec B2, which does not exist yet.

## 5. Unit 1 — Wave P

Two PRs, strictly ordered. P-B depends on P-A. If P-B fails review or is reverted, P-A remains valid on its own.

### P-A — service selection

Suggested branch: `feat/deploy-service-allowlist`

- **P-A.1** Add a service allowlist and two explicit workflow modes. Scoping is a property of the **whole workflow**, not the backend matrix.
  - *full deploy* (no selection) — today's chain unchanged: four backends, frontend, seed, verify.
  - *scoped backend deploy* — selected backend only; `deploy-frontend`, `seed`, `verify` are **skipped**.
  - An unselected service receives **no `az containerapp update` at all** — not a re-deploy at its existing digest, which can still mutate revision state.
- **P-A.2** Prove non-interference across Container Apps **and** downstream jobs. For each scoped run, assert every unselected app's revision name, image digest and traffic weight are byte-identical before and after, **and** that `deploy-frontend`, `seed`, `verify` each report a conclusion of `skipped`. Read `needs.<job>.result` — GitHub reports a skipped job as a *successful* check, so a green workflow does not distinguish "did not run" from "ran". The seed is a production writer (`frontend/tests/e2e/global-setup.ts:191` POSTs to `/api/internal/portfolio/seed`), not an observation.
- **P-A.3** Prove **every** declared selection shape, not one. B1 needs `api-gateway`-only and `portfolio-service`-only; exercise both, and cover all four structurally — including that selecting `market-data-service` also updates `market-data-refresh-job`.
- **P-A.4** Prove the default path is unchanged: an ordinary dispatch with no allowlist still deploys all four backends, frontend, seed, verify exactly as today.
- **P-A.5 STOP/GO.** Go: P-A.2, P-A.3, P-A.4 green. Abort: revert the allowlist; release lane stays closed, implementation unaffected.

### P-B — digest deployment (based on P-A)

Suggested branch: `feat/deploy-prebuilt-digest`

- **P-B.1** Add a prebuilt-digest deploy path. Today the workflow rebuilds independently and deploys by tag. Add an input accepting `repository@sha256:...` and a skip-build branch that updates the Container App to **that exact manifest digest** — no build, no push, no retag.
- **P-B.2** Fail closed at the trust boundary, and accept **`portfolio-service` only**. Reject before any update when: the service is not `portfolio-service`; the selection is not exactly one service; the ACR repository does not equal the selected service; the reference is a tag rather than immutable `sha256:` syntax; the manifest does not resolve in the expected ACR; or a foreign registry/repository is named.
  - The narrowness is deliberate. A generic form would accept `market-data-service`, whose Container App would take the digest while `market-data-refresh-job` still moved by tag — breaking the exact-artifact invariant inside one logical deployment.
- **P-B.3** Prove the digest path actually works. P-A.2 only proves *unselected* apps are untouched — a workflow that ignores the digest and rebuilds would still pass it. Assert: no build or push step executed, the selected Container App resolves to the exact requested digest, and P-A.2's scoped-mode skips still hold.
- **P-B.4** Prove each rejection case fails **before any update**, and that the default full-deploy path still works with the digest input absent.
- **P-B.5 STOP/GO.** Go: P-B.3 and P-B.4 green. Abort: revert P-B only; P-A survives.

### Verified file anchors (checked against `4945649`)

`.github/workflows/deploy-azure.yml`, 379 lines. Jobs: `preflight` (45), `deploy` (117), `deploy-frontend` (228), `seed` (312), `verify` (351).

- L145 — the `docker build` step
- L161 — the `az containerapp update --image ...` tag-based deploy
- L163+ — `Update market-data-refresh Job image`, guarded by `if: matrix.service == 'market-data-service'`
- L230 — `needs: [preflight, deploy]` (deploy-frontend)
- L314 — `needs: [preflight, deploy, deploy-frontend]` (seed)
- L353 — `needs: [preflight, seed]` (verify)

Skip propagation needs no new mechanism: a skipped prerequisite skips its dependants unless a condition such as `always()` overrides it, and neither `seed` nor `verify` uses one. **Re-verify these anchors before editing** — they were confirmed at `4945649` and any intervening merge can move them.

## 6. Unit 2 — Spec A tasks 1–3

Suggested branch: `feat/supported-asset-catalog-module`

Source: `.kiro/specs/supported-asset-integrity/tasks.md`, tasks 1–3 (lines 50–129). All three are **behaviour-neutral** — no migrations, no enforcement, no user-visible change.

- **Task 1 — `common-catalog` module foundation.** New plain-Java Gradle module (no Spring), joining the `common-dto` / `common-observability` convention: add `include 'common-catalog'` to `settings.gradle`. Types `CatalogEntry`, `LifecycleStatus`, `SupportedCatalog`, `SeedCatalogView` (`basePrice` reachable **only** through `SeedCatalogView`). Integrity validation collecting **all** violations into one message, and asserting no fixed total or per-class count is enforced. `Catalog_Version` as SHA-256 over ticker, name, sorted aliases, assetClass, quoteCurrency, lifecycleStatus **and `basePrice`**, entries sorted by ticker, 16 hex chars. `CatalogLoadFailedException` with fail-closed loading — never empty, never partial, no cached fallback.
- **Task 2 — Manifest and packaging.** Add `lifecycleStatus` to all 160 entries **and** apply symbol corrections in the same commit (`MM.NS` → `M&M.NS` ACTIVE; `TATAMOTORS.NS` → DEPRECATED; `USDINR=X` untouched) — one commit by construction, since shipping validation before the field fails startup everywhere. Verify `&` URL-encoding for `M&M.NS` against the provider client. Non-mutating build packaging: `processResources` copies `config/seed-tickers.json` into `build/resources/main/catalog/` in each consumer; delete the three `copySeedTickers` tasks and the three tracked `src/main/resources/seed/seed-tickers.json` copies.
- **Task 3 — Consumers adopt the module.** Replace both `SeedTickerRegistry` implementations. `insight-service` delegates loading/integrity/versioning and its fail-open log-error-set-empty-continue path is deleted. Fail-to-start in all three services on all normal profiles, emitting structured `catalog_load_failed` before propagating. **Remove `@Async` from `MarketPriceProjectionService`** (design D10). Actuator `health,info` exposure for `portfolio-service` and `insight-service` — **never** a wildcard — plus the `catalog_loaded` startup log. Retain the delivered price-write invariant as a regression guard.

Note task 3.4 (`@Async` removal) is governed by **design D10**, not a requirement criterion — it ships here rather than later because checkpoint 9.4 drains Kafka before R3a.

### Verified file anchors (checked against `4945649`)

- `config/seed-tickers.json` — a JSON array of exactly **160** entries, matching task 2.1.
- `copySeedTickers` — three registrations to delete, each with a `dependsOn`: `insight-service/build.gradle:14`, `market-data-service/build.gradle:13`, `portfolio-service/build.gradle:15`.
- Three tracked manifest copies to delete: `insight-service`, `market-data-service`, `portfolio-service`, each at `src/main/resources/seed/seed-tickers.json`.
- `SeedTickerRegistry` — two implementations, as task 3.1 states: `market-data-service/src/main/java/com/wealth/market/seed/SeedTickerRegistry.java` and `portfolio-service/src/main/java/com/wealth/portfolio/seed/SeedTickerRegistry.java`.
- `@Async` — `portfolio-service/src/main/java/com/wealth/portfolio/MarketPriceProjectionService.java:43`.
- `settings.gradle` currently includes `api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`, `common-dto`, `common-observability`. No `V17`+ Flyway migration exists yet.

## 7. Definition of done

Per PR:

- All task checkboxes in the relevant section ticked, in the spec file, in the same PR.
- Tests green, including the explicit assertions each task names — several tasks specify a test whose *absence* is the defect, so a passing build is not sufficient evidence.
- For Wave P, the STOP/GO task's go-condition demonstrably met, with the evidence linked in the PR body.
- Spec reference check passes:

```bash
python scripts/check-spec-references.py .kiro/specs/supported-asset-integrity/tasks.md --against .kiro/specs/supported-asset-integrity/requirements.md --coverage --pairs
```

`--coverage` enforces by equality, so a stale "intentional gap" declaration fails the run.

## 8. Out of scope

`GET /api/assets`, the composition API, optimistic concurrency, portfolio versioning, `V20`, the picker modal, draft/conflict UX, freshness presentation, demo reset trigger, presence banner, and any change to `ReadOnlyEnforcementFilter`. These belong to B1 and B2.

Spec A's own requirements still describe a two-spec world and name `asset-picker-composition` as the owner of `GET /api/assets`, the composition API, and optimistic concurrency. That text is **stale but deliberately unmodified** — those three items moved to B1 when the split became three-way. Do not act on it, and do not "fix" it.

## 9. Escalate rather than decide

- Any task that appears to conflict with its design document.
- Any need for a Flyway migration number.
- Any change that would touch frontend production files.
- Any `deploy-azure.yml` anchor from §5 that no longer matches.
