# Asset Picker — E2E Master Plan to Production

**Last verified:** 2026-08-30

**Program-state code baseline (runtime):** `main@e221662b6c891639a56894289e150ee01fb537f6`.
This is the last SHA that changed Asset Picker program runtime/application behavior (catalog,
enforcement, controlled refresh). Process-control and documentation merges do not advance it.
B1 R-A additionally serves Wave 2 gateway provisioning at revision `api-gateway--0000076` /
digest `sha256:2da5b303…` (image tag `18693d2…`). B1 R-B applied Artifact 2 / V20 (cut `25aa730`).
B1 R-B2 additionally serves Artifact 2a `portfolio-service` at revision
`portfolio-service--0000081` / digest `sha256:d544649f…` (cut `f22e2ff`); that deploy serves the
version-bearing read and read-only asset catalog without caller migration, public `PUT`, or Spec A
fence changes.

**Authoritative documentation revision:** advances when this file or related program docs change;
independent of the runtime baseline above. Current Spec A 9.12 evidence baseline:
`main@cdf2373776ad98457f07caf63d0e426c0e2fe988`.

**Program state:** Spec A checkpoints 9.1–9.11 are complete. Checkpoint 9.11 persisted
`MARKET_DATA_JOB_RUNNER_ENABLED=true` through Terraform apply on `main@e7fad7cb` (source PR #164;
evidence [`SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md`](../runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md)).
Checkpoint 9.12 source merged via PRs #167, #169, #170, and #172; authorized enable apply ran but failed
to converge (startup transaction PostgreSQL read-only) and was rolled back. The production
setter-provenance cycle completed on revisions `0000087`–`0000089` and observed `FIRST_OBSERVED_ON`
on `0000088` (session already on/on before first wrapper checkout). Production is on
`portfolio-service--0000089` with both demo and diagnostics flags `false`; demo remains at 3 holdings.
Connection-origin probe source merged through PR #174 at `main@4ac26405`. Its separately authorized
live matrix returned `NOT_REPRODUCED_IN_MANUAL_MATRIX`: pooled and direct paths both observed writable
off/off defaults with source `DEFAULT` and no catalog overrides; before/after data hashes, revision,
and flags were unchanged. The top-level RCA verdict remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN` — evidence
[`SPEC_A_9_12_POOLED_READONLY_RCA.md`](../runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md). The 9.12
checkbox remains open. Statement-history probe executed once on 2026-08-29 at `main@cdf23737` (PR #176
merge); one authorized live run reached JDBC; `pg_stat_statements` was absent so history was unavailable
(`STATEMENT_HISTORY_PROBE_EXECUTED_HISTORY_UNAVAILABLE` / `STATEMENT_HISTORY_UNAVAILABLE`); canonical
zero output is not absence evidence. Next gate: evidence-reconciliation review/merge, followed only if
separately authorized by a future-observability decision.
Checkpoints 9.13–9.14 remain pending and unauthorized. B1 Wave 2 /
R-A, Wave 3 / R-B (V20), and Wave 5 Tasks 5.2–5.3 / R-B2 (G2a) are complete; caller migration Tasks
**5.4–5.6 merged on `main@0b5d60d1`** (PR #161, source-only; no deploy); **G5/5.7 remains blocked**
by Spec A closed gateway ingress — see
[`B1_G5_INGRESS_BLOCKER.md`](../runbooks/B1_G5_INGRESS_BLOCKER.md); later B1 waves remain gated.
B2 Wave 1 (Tasks 1.1-1.19) and Wave 2 Tasks 2.1-2.5 are merged source-only through PR #178 at `main@38e3d95`; they remain entirely mock-backed and disabled by default. Wave 3 presence source Tasks 3.1–3.6 merged source-only via PR #179 at `main@cc97a209`; Task 3.7 deploy/live proof remains open (not deployed, not activated, not live-probed). Wave 4 Tasks 4.1–4.4a merged source-only via PR #180 at `main@63fc058`; they are not deployed, routed, or user-visible. Task 4.5 and Waves 5–10 remain gated; Wave 2 Tasks 2.6–2.7 remain open.

**User-visible state:** there is no functional Asset Picker in the application today.

**Handoff state:** Spec A 9.11 is **complete** (runner `true` live; safety tuple unchanged; follow-up
`standard` remote-plan had no changes). Spec A 9.12 enable apply **ran and was rolled back**;
production setter-provenance observed `FIRST_OBSERVED_ON` and returned to
`portfolio-service--0000089` with demo seed `false`. The later authorized connection-origin matrix did
not reproduce the state on pooled or direct sessions. Statement-history probe executed once on
2026-08-29 at `main@cdf23737`; verdict `STATEMENT_HISTORY_PROBE_EXECUTED_HISTORY_UNAVAILABLE`
(`pg_stat_statements` absent; canonical zeroes are not absence evidence). Any remedy, extension
installation, repeat probe, or 9.12 retry remains separately gated. Production fences are unchanged.

This is the living, human-facing status document for the Asset Picker program. It is not a
historical snapshot. Detailed requirements, designs, task mechanics, and operational evidence live
in the linked owning documents; this file summarizes their current state, dependencies, blockers,
active work, and next decisions.

## 0. Status governance

### 0.1 Authority

- This master plan owns cross-spec status, dependency ordering, active work, blockers, and the next
  decision requiring owner authorization.
- Each spec's `requirements.md` owns behavior, `design.md` owns architecture, and `tasks.md` owns
  detailed implementation and verification mechanics.
- Runbooks own durable operational evidence. They are linked from task records rather than copied
  here.
- Git history preserves chronology. Historical status paragraphs do not remain in the living view
  after they become false.

### 0.2 Required update rule

Every pull request must include **exactly one** canonical declaration in the PR body:

1. `Master-plan impact: updated — <tracks>` where `<tracks>` is a comma-separated list from
   `{Spec A, B1, B2, process}`; or
2. `Master-plan impact: none: <same-line rationale>` explaining why program status, dependencies,
   blockers, and next actions are unchanged.

For `updated`, the same change must update this master plan and every owning `tasks.md` ledger for
the declared Spec A/B1/B2 tracks (`process` has no ledger). Declared tracks must also cover every
Spec A/B1/B2 specification directory the PR touches; `process` cannot substitute for an inferred
spec track. For `none`, the rationale must be on the same line, must not be an HTML
placeholder/checklist/stub, and must not accompany edits to this master plan or an owning ledger
(that is a conflict — use `updated` instead).

A checkbox is marked complete only when its owning acceptance evidence exists. Work implemented on
an unmerged branch is described as **implemented but unmerged**, never as complete on `main`.

**Process-control enforcement:**

- Contract tests run in required `static-guard` (`.github/workflows/ci-verification.yml`).
- The live PR-body check runs in the dedicated lightweight workflow
  `.github/workflows/master-plan-status-propagation.yml` on `opened` / `synchronize` /
  `reopened` / `edited`, so body edits are revalidated without folding `edited` into the heavy CI
  chain. Script: `scripts/check_master_plan_status_propagation.py`.
- When those paths exist in the revision being read, the guard is part of that revision's process
  controls. Runtime/application Asset Picker capability is unaffected.

### 0.3 Update checklist

At every meaningful merge or live checkpoint:

- update `Last verified` and the program-state code baseline;
- update the program snapshot and affected track row;
- update the active PR/branch table;
- update blockers and the next authorization boundary;
- update the owning `tasks.md` evidence;
- remove or rewrite statements that have become false; and
- keep secrets and raw operational artifacts out of tracked documentation.

## 1. Executive program snapshot

| Track | Delivered | Current position | Remaining outcome |
|---|---|---|---|
| **A — Spec A catalog/data cutover** | Shared catalog, Postgres/Mongo repair, R4 rollout, enforcement, one reconciled controlled refresh, and persisted refresh enablement | **11 of 14 cutover checkpoints complete**; 9.12 enable failed and was rolled back; provenance observed `FIRST_OBSERVED_ON`; connection-origin live matrix returned `NOT_REPRODUCED_IN_MANUAL_MATRIX`; statement-history live run returned `STATEMENT_HISTORY_PROBE_EXECUTED_HISTORY_UNAVAILABLE`; production remains on `portfolio-service--0000089` with both flags `false`; RCA `MECHANISM_REPRODUCED_SETTER_UNPROVEN` at `main@cdf23737` | Senior architecture review and merge of this evidence-only reconciliation; any future `pg_stat_statements` installation, repeat probe, remedy, or 9.12 retry remains separately gated |
| **B — B1 portfolio composition backend** | Deployment prerequisites, fixture identity migration, legacy writer retirement, Wave 2 gateway provisioning **served (R-A/G2 green)**, Wave 3 V20 **served (R-B/G3 green)**, Wave 5 version-bearing read **served (R-B2/G2a green)** | **Wave 2 / R-A complete**; **Wave 3 / R-B complete**; **Wave 5 Tasks 5.2–5.3 / R-B2 complete** (Artifact 2a on `portfolio-service--0000081` / `sha256:d544649f…`; cut `f22e2ff`); **Wave 4a–4c tasks 4.1–4.21 merged on `main@2673f40`** (PR #153; composition mechanisms unexposed; no public `PUT`); Task 5.1 merged on `main@f22e2ff` (PR #155); Tasks **5.4–5.6 merged on `main@0b5d60d1`** (PR #161, source-only); **5.7/G5 blocked by Spec A closed ingress** | Caller migration G5 (after ingress reopen or authorized private-reachability), safe desired-state writer activation, public `PUT` |
| **C — B2 Asset Picker product** | Requirements, design, task plan, five-screen visual mockup, Wave 1 / partial Wave 2 frontend source, Wave 3 presence source, Wave 4 demo-reset source | Wave 1 (1.1-1.19) + Wave 2 Tasks 2.1-2.5 merged source-only on `main@38e3d95` via PR #178; Wave 3 Tasks 3.1–3.6 merged source-only on `main@cc97a209` via PR #179 (Task 3.7 deploy/live proof open); Wave 4 Tasks 4.1–4.4a merged source-only via PR #180 at `main@63fc058` (not deployed/routed) | Task 3.7 deploy/live proof; Task 4.5+ and authorized deployment of Wave 4; reset gateway bundle, live integration, exposure remain separately gated |
| **D — Demo credibility** | Canonical prices refreshed and reconciled; demo initializer exists gated off | Demo activation failed and was rolled back; demo still 3 holdings; current manual connection paths are clean while the historical startup session remains unexplained | Spec A 9.12 requires stronger historical/startup-path evidence before remedy design and re-attempting activation without touching E2E data |

### What is actually usable today

| Capability | Status |
|---|---|
| Canonical Active Asset catalog inside services | ✅ Shipped |
| Repaired and reconciled price data | ✅ Shipped and verified |
| Enforcement against unsupported holdings/events | ✅ Enabled |
| `GET /api/assets` serving catalog data | ✅ Wave 2 gateway `/api/assets/**` route served with R-A; Wave 4b controller served with R-B2 Artifact 2a (`portfolio-service--0000081`) |
| Version-bearing portfolio read | ✅ G2a/R-B2 green on `portfolio-service--0000081` / `sha256:d544649f…`; caller migration 5.4–5.6 on `main@0b5d60d1` (source-only, not deployed); **5.7/G5 blocked by Spec A closed ingress** |
| `PUT /api/portfolio/holdings` safe composition write | ❌ Not implemented |
| Asset Picker button/modal/browse/review/conflict UI | ❌ Not implemented |
| Asset Picker full-stack E2E proof | ❌ Not implemented |
| Asset Picker exposed to production users | ❌ Not implemented |

The recent flakiness fixes were incidental blockers. The main delivered work was production data,
catalog, enforcement, and deployment safety. That foundation is necessary, but it is not the
user-facing picker.

## 2. Track A — Spec A catalog/data foundation

Authority: [`.kiro/specs/supported-asset-integrity/tasks.md`](../../.kiro/specs/supported-asset-integrity/tasks.md)

| Checkpoint | Status | Durable outcome |
|---|---|---|
| 9.1–9.5 | ✅ Complete | R1/R2 deployed, refresh fenced, Kafka drained, writes/ingress quiesced |
| 9.6 | ✅ Complete | V17–V19 Postgres repair applied; integrity assertion and repair audit verified |
| 9.7 | ✅ Complete | Mongo repair Job completed and verified |
| 9.8 | ✅ Complete | R4 deployed with catalog identity confirmed; actual chronology recorded |
| 9.9 | ✅ Complete | Catalog enforcement enabled; three services held at `min_replicas=1` |
| 9.10 | ✅ Complete | One controlled refresh succeeded and was reconciled across Kafka, Mongo, and Postgres |
| 9.11 | ✅ Complete | Persisted `MARKET_DATA_JOB_RUNNER_ENABLED=true` via Terraform; live read-back and standard no-op plan green ([`SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md`](../runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md)) |
| 9.12 | ⏸ Enable failed; rolled back; provenance, connection-origin, and statement-history live probes complete (`portfolio-service--0000089`) | Production provenance observed `FIRST_OBSERVED_ON`; pooled/direct matrix returned `NOT_REPRODUCED_IN_MANUAL_MATRIX`; statement-history live run returned `STATEMENT_HISTORY_PROBE_EXECUTED_HISTORY_UNAVAILABLE` (`pg_stat_statements` absent; canonical zeroes not absence evidence); RCA remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN` at `main@cdf23737` | Evidence-reconciliation review/merge; any future extension installation, repeat probe, remedy, or 9.12 retry remains separately gated |
| 9.13 | ⏸ Pending | Restore `min_replicas=0` and verify configuration-level state |
| 9.14 | ⏸ Pending | Reopen ingress after 9.11–9.13 are green |

Additional unfinished Spec A implementation task: **8.8**, replacing remaining hard-coded
catalog-size assertions. Tasks 8.1–8.7, including the aggregate `assetPriceFreshness` contract,
are complete.

### Current production safety boundary

- Persisted refresh runner: `true` (checkpoint 9.11 complete; scheduled Job may run at `0 8 * * *`).
- Refresh retry limit: `0`.
- Gateway ingress: closed.
- `portfolio-service`, `market-data-service`, and `insight-service`: enforcement enabled,
  `min_replicas=1` for the verification window.
- Controlled refresh: exactly one authorized one-off execution completed at 9.10; 9.11 did not start
  an additional execution.
- Demo portfolio activation: enable apply ran and was rolled back; production gate
  `APP_DEMO_SEED_ON_STARTUP` is `false` on `portfolio-service--0000089`; demo remains at 3 holdings;
  diagnostics flag also `false`.
- Checkpoints 9.12–9.14: not complete; the current connection-origin matrix is clean, while 9.12
  awaits stronger historical/startup-path evidence before remedy design and re-attempting authorized enable
  ([`SPEC_A_9_12_POOLED_READONLY_RCA.md`](../runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md)).
- B1 G5 remains blocked by closed ingress.

Checkpoint 9.10 evidence:
[`docs/runbooks/SPEC_A_9_10_CONTROLLED_REFRESH.md`](../runbooks/SPEC_A_9_10_CONTROLLED_REFRESH.md).

Checkpoint 9.11 evidence:
[`docs/runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md`](../runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md).

Checkpoint 9.12 RCA evidence (checkpoint incomplete):
[`docs/runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md`](../runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md).

## 3. Track B — B1 portfolio composition backend

Authority: [`.kiro/specs/portfolio-composition-contract/tasks.md`](../../.kiro/specs/portfolio-composition-contract/tasks.md)

| Wave | Status | Meaning |
|---|---|---|
| P — deployment prerequisites | ✅ Complete | Scoped service deployment and immutable portfolio digest path live |
| 0 — fixture identity migration | ✅ Complete | E2E fixture paths moved to the correct identity |
| 1 — legacy writer retirement | ✅ Complete | Old portfolio creator and versionless holding writer removed and kept retired |
| 2 — gateway provisioning + asset route | ✅ R-A complete (G2 served) | PR #131 tasks 2.1–2.6 complete; serving revision `api-gateway--0000076`, digest `sha256:2da5b303…`; evidence [`B1_R_A_G2_SERVING_PROOF.md`](../runbooks/B1_R_A_G2_SERVING_PROOF.md) |
| 3 – V20 schema | ✅ R-B complete (G3 served) | Tasks 3.1–3.7 complete; Artifact 2 cut `25aa730` applied V20; prior serving evidence [`B1_R_B_G3_SERVING_PROOF.md`](../runbooks/B1_R_B_G3_SERVING_PROOF.md); superseded for portfolio traffic by R-B2 |
| 4 – contract implementation | Source on Artifact 2a serving cut; mechanisms unexposed | Wave 4a–4c (4.1–4.21) merged on `main@2673f40` (PR #153) and included in Artifact 2a serving digest. Public `PUT` still Wave 7. Replacement orchestrator + preparers remain unexposed; `GET /api/assets` controller is now served with R-B2; candidate packaging (7.5/R-C) still pending |
| 5 — version-bearing read | 🟡 Tasks 5.1–5.3 / R-B2 complete; **5.4–5.6 merged on `main@0b5d60d1`** (PR #161, source-only); **5.7/G5 blocked** by Spec A closed ingress | Task 5.1 on main@f22e2ff; G2a/R-B2 green on portfolio-service--0000081 / sha256:d544649f…; caller migration merged source-only; G5 runs 33046987880 / 33047168136 failed at TLS login before seed ([`B1_G5_INGRESS_BLOCKER.md`](../runbooks/B1_G5_INGRESS_BLOCKER.md)) |
| 6 — version-required seed | ⬜ Not started | Seeder delegates through the safe replacement service |
| 7 — activation | ⬜ Not started | Public `PUT /api/portfolio/holdings`, attested candidate, serving proof |

Spec A V17–V19 were applied at checkpoint 9.6; **V20 is applied under R-B** and unchanged by R-B2.
**R-A / G2**, **R-B / G3**, and **R-B2 / G2a** are complete. Wave 4 composition write mechanisms
remain unexposed; public `PUT` and candidate packaging (7.5/R-C) remain incomplete. Caller migration
source **Tasks 5.4–5.6 are on `main@0b5d60d1`** (PR #161; source-only, not deployed); **5.7/G5
remains incomplete** until Spec A reopens ingress or a separately authorized private-reachability
test runs. Wave 6 / R-B3 stay gated. **Do not treat a current-`main` portfolio deploy as a
substitute for an authorized Artifact cut.**

### Active Spec A work

| Item | Current state | Required before relying on it |
|---|---|---|
| Checkpoint 9.11 | **Complete** — apply run [33091163222](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33091163222); live runner `true`; standard no-op [33093260896](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33093260896); evidence [`SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md`](../runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md) | 9.12 provenance, connection-origin, and statement-history live probes complete; any later remedy or extension installation remains separately gated |
| 9.12 enable / rollback / diagnostics | **Enable failed and rolled back; provenance, connection-origin, and statement-history live probes completed** — enable [33150399420](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33150399420); rollback [33151372186](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33151372186); provenance disable on `portfolio-service--0000089` [33242076369](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33242076369); observed `FIRST_OBSERVED_ON`; live matrix `NOT_REPRODUCED_IN_MANUAL_MATRIX`; statement-history live run `STATEMENT_HISTORY_PROBE_EXECUTED_HISTORY_UNAVAILABLE` at `main@cdf23737`; RCA [`SPEC_A_9_12_POOLED_READONLY_RCA.md`](../runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md) | Evidence-reconciliation review/merge; any future extension installation, repeat probe, remedy, or 9.12 retry remains separately gated |

### Active B1 work

| Item | Current state | Required before relying on it |
|---|---|---|
| PR #131 / R-A serving | **Complete** — Wave 2 tasks 2.1–2.6; G2 green on `api-gateway--0000076` / `sha256:2da5b303…` ([run 32952197627](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32952197627); [`B1_R_A_G2_SERVING_PROOF.md`](../runbooks/B1_R_A_G2_SERVING_PROOF.md)) | Signup provisioning remains live on the serving gateway revision |
| `cursor/b1-wave3-v20-schema` / PR #152 + R-B | **Complete** — tasks 3.1–3.7 / R-B; V20 applied; G3 green ([`B1_R_B_G3_SERVING_PROOF.md`](../runbooks/B1_R_B_G3_SERVING_PROOF.md)); portfolio traffic superseded by R-B2 | Forward-only after V20; do not roll back migration or gateway |
| `cursor/b1-wave4a-composition-core` / PR #153 | **Merged** on `main@2673f40` – Wave 4a–4c tasks 4.1–4.21. Read-only catalog path is served via Artifact 2a; composition write mechanisms remain **unexposed**; no public `PUT` | Do not start Wave 6–7 or candidate attestation without separate authorization |
| `cursor/b1-wave5a-version-bearing-read` / PR #155 + R-B2 | **Complete for 5.1–5.3** — Task 5.1 on `main@f22e2ff`; Artifact 2a serving on `portfolio-service--0000081` / `sha256:d544649f…` ([run 32982880866](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32982880866); [`B1_R_B2_G2A_SERVING_PROOF.md`](../runbooks/B1_R_B2_G2A_SERVING_PROOF.md)); G2a green | Do not begin Tasks 5.4–5.7 / caller migration without separate authorization; any future portfolio rollout invalidates G2a |
| `cursor/b1-wave5b-seed-caller-migration` / PR #161 | **Merged source-only on `main@0b5d60d1`** — Tasks 5.4–5.6; **5.7/G5 incomplete** — blocked by Spec A closed gateway ingress ([`B1_G5_INGRESS_BLOCKER.md`](../runbooks/B1_G5_INGRESS_BLOCKER.md); runs 33046987880 / 33047168136) | No deploy; no G5 claim; Wave 6/R-B3 still gated; resume G5 only after valid reachability |
| [`proof/b1-wave-2-g1-v20@e6a98c5`](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/tree/proof/b1-wave-2-g1-v20) | Historical dependent dual-schema proof branch; superseded for Wave 3 delivery by `cursor/b1-wave3-v20-schema` | Remain unmerged; no release action |

### Active process work

| Item | Current state | Required before relying on it |
|---|---|---|
| Status-propagation CI guard | Contract tests in `static-guard`; live PR-body check in dedicated `master-plan-status-propagation` workflow (`opened`/`synchronize`/`reopened`/`edited`) | Process-control only; does not advance the runtime baseline or create user-facing Asset Picker capability |

The temporary product state is intentional but incomplete: the unsafe legacy writer is gone, while
the safe versioned replacement has not yet been built. A frontend picker cannot save holdings until
B1 Wave 7 activates the new endpoint.

## 4. Track C — B2 Asset Picker product

Authorities:

- [requirements](../../.kiro/specs/asset-picker-composition/requirements.md)
- [design](../../.kiro/specs/asset-picker-composition/design.md)
- [implementation tasks](../../.kiro/specs/asset-picker-composition/tasks.md)
- [visual mockup](../../.kiro/specs/asset-picker-composition/mockup/asset-picker-design.html)

All four artifacts are tracked. Wave 1 (1.1-1.19) and Wave 2 Tasks 2.1-2.5 merged source-only on `main@38e3d95` through PR #178 after two external review rounds and regression fixes. That frontend remains entirely mock-backed and disabled by default; the merge did not authorize live endpoint wiring, deployment, or production exposure. Wave 3 presence source Tasks 3.1–3.6 merged source-only via PR #179 at `main@cc97a209` with a configurable 150-second default TTL; Task 3.7 deploy/live probe remains a separate owner-gated production step (not deployed, not activated, not live-probed). Wave 4 Tasks 4.1–4.4a merged source-only via PR #180 at `main@63fc058`; they are not deployed or routed.

| Wave | Status | Dependency note |
|---|---|---|
| 1 — mock-backed picker shell | ✅ Source merged (1.1-1.19), mock-backed only; not deployed/live | Feature flags, modal, browse/draft/review/conflict UX, mocked save/freshness/presence; PR #178 / `main@38e3d95` |
| 2 — decimal adapter | 🟡 Tasks 2.1-2.5 source merged; 2.6-2.7 not started | PR #178 / `main@38e3d95`; rollout sequencing with B1 Wave 4/5 remains an explicit open coordination decision |
| 3 — Redis-backed presence | 🟡 Tasks 3.1–3.6 source merged via PR #179 / `main@cc97a209`; Task 3.7 open | Default TTL **150s** via `APP_DEMO_PRESENCE_TTL`; not deployed/live-verified |
| 4 — portfolio-service demo reset | 🟡 Tasks 4.1–4.4a source merged via PR #180 / `main@63fc058`; Task 4.5 open | B1 prerequisites verified on `main@cc97a209`; not deployed/routed |
| 5 — manual-reset gateway bundle | ⬜ Blocked on Wave 4 Task 4.5 / deployment proof | Route, authorization filter, read-only allowlist, identity providers |
| 6 — manual reset frontend | ⬜ Blocked on Wave 5 and B1 5.1 | Hidden control and versioned reset call |
| 7 — decimal rollout note | ℹ Informational | No independent release gate |
| 8 — login-orchestrated reset | ⬜ Not started/partly blocked | Requires B1/V20/version read, open idle/timeouts, and its own deployment evidence |
| 9 — live integration | ⬜ Blocked | Requires B1 catalog/read/write endpoints and relevant B2 Waves 1–6 |
| 10 — production exposure | ⬜ Blocked | Convergence gate after all required live evidence and open decisions close |

The aggregate `assetPriceFreshness` backend dependency is now **closed**: Spec A task 8.6 is
complete and `PortfolioSummaryDto.assetPriceFreshness` exists. B2 still must implement its frontend
adapter and UI wiring in Waves 1 and 9.

### Open B2 decisions

1. Demo reset idle threshold; 30 minutes is provisional.
2. Manual reset control placement in the UI.
3. Login self-call timeouts; 2 seconds per leg and 4 seconds overall are provisional.
4. Decimal-adapter deployment sequencing relative to B1 Wave 4/5.

These do not block starting the mock-backed picker shell. They do block the affected reset/presence
behavior and final production exposure.

## 5. Dependency path to a production Asset Picker

```text
Track A: 9.12 -> 9.13 -> 9.14
                    (9.11 complete; production foundation remaining)

Track B: Wave 2 -> Wave 3 -> Wave 5 -> Wave 6 -> Wave 7
                    ^          ^                   |
                    |          |                   v
              Wave 4 implementation --------> real read/write APIs

Track C: Wave 1 mock UI + Wave 2 adapter + independent presence work
                    |                              |
                    +---- relevant Waves 3-6 ------+
                                                   v
                                    Wave 9 live integration
                                                   v
                                    Wave 10 production exposure
```

Track A operational work, B1 Wave 4 implementation, and the startable portion of B2 Wave 1 do not
need to be serialized. Production transitions retain their individual approval gates.

## 6. Next meaningful work and authorization boundary

### Current cutoff

Spec A 9.11 is the last completed production checkpoint. Checkpoint 9.12 enable apply **ran and was
rolled back** after a PostgreSQL read-only startup transaction. Production setter-provenance observed
`FIRST_OBSERVED_ON` on `0000088` and returned production to `portfolio-service--0000089` with both
demo and diagnostics flags `false`. The later authorized pooled/direct matrix returned
`NOT_REPRODUCED_IN_MANUAL_MATRIX` (`main@4ac26405`). RCA verdict
`MECHANISM_REPRODUCED_SETTER_UNPROVEN` remains. Before re-attempting 9.12:

- the historical source remains unidentified; statement-history probe executed once on 2026-08-29 at
  `main@cdf23737` and returned `STATEMENT_HISTORY_PROBE_EXECUTED_HISTORY_UNAVAILABLE`
  (`pg_stat_statements` absent; canonical zeroes are not absence evidence);
- production gate remains `false`;
- demo portfolio remains at 3 holdings; E2E data unchanged;
- scale and ingress fences remain explicit (`min_replicas=1`, ingress closed);
- 9.13–9.14 and B1 G5 remain pending; and
- B1/B2 implementation status is cleanly separable from the remaining production cutover.

### Next choices

1. **Operational lane:** senior architecture review and merge of this evidence-only
   reconciliation. The single authorized live run returned `STATEMENT_HISTORY_UNAVAILABLE` because
   `pg_stat_statements` was not installed; canonical zero formatter output is not absence evidence.
   Neon supports `pg_stat_statements` as a future observation mechanism, but installation is
   production DDL and is not authorized by this documentation task; it begins a future statistics
   window and cannot recover pre-install history. Extension installation alone does not authorize a
   reset, repeat probe, 9.12 retry, remedy, deployment, or flag change. Each production action
   requires explicit authorization from Vibhanshu/the repository owner, plus any separately named
   platform approval. Only after stronger evidence exists should architects review a narrow remedy
   with explicit blast-radius analysis and separately authorize a repeat of the 9.12 enable +
   restoring rollouts and live verification gates. Continue 9.13–9.14 only after 9.12 succeeds.
2. **Backend lane:** **R-A / G2**, **R-B / G3**, and **R-B2 / G2a** are complete (Artifact 2a
   `portfolio-service--0000081` / `sha256:d544649f…`, cut `f22e2ff`). Tasks **5.4–5.6 are merged
   source-only on `main@0b5d60d1`** (PR #161); **5.7/G5 is blocked** by Spec A closed gateway
   ingress (not by Wave 5b). Resume G5 only after Spec A 9.12–9.14 reopen ingress, or a separately
   authorized private-reachability test that executes all three real callers. Waves 6–7 and
   candidate packaging (7.5/R-C) remain separately gated. Do not claim Writer_Convergence while the
   old seed remains version-tolerant.
3. **Frontend lane:** B2 Wave 1 (1.1-1.19) and Wave 2 Tasks 2.1-2.5 are merged source-only through
   PR #178 on `main@38e3d95`, with two rounds of external review findings fixed. The implementation
   remains mock-backed and disabled by default; Tasks 2.6-2.7, live wiring, deployment, and exposure
   remain separate work and gates.
4. **Process lane:** keep the status-propagation CI guard healthy in required `static-guard`; it is
   process-control only and does not advance the runtime baseline.

No item above is authorized merely by being listed. The Cursor handoff must name the chosen first
task, its exact scope, predecessor evidence, stop condition, and whether it is documentation,
implementation, or a production operation.

## 7. Handoff requirements

The Cursor handoff created after this documentation PR merges must be self-contained and anchored to
the resulting `main` SHA. It must include:

- this master plan as the first-read program dashboard;
- the exact owning requirements/design/tasks documents for the chosen task;
- current production fences and explicit non-authorizations;
- active/draft PRs and whether their code is on `main`;
- commands and tests required to verify the chosen task;
- known stale-branch/rebase hazards;
- the four unresolved B2 decisions without silently choosing values;
- the status-governance rule from §0; and
- an instruction to update this plan and the owning task ledger in every status-changing PR.

AWS-only work remains deferred while AWS production is disabled. Azure is the current delivery
target; shared behavior and cross-cloud contracts must not be weakened.
