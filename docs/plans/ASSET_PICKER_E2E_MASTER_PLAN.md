# Asset Picker — E2E Master Plan to Production

**Last verified:** 2026-08-27

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
independent of the runtime baseline above.

**Program state:** Spec A checkpoint 9.10 is complete. Checkpoints 9.11–9.14 are pending and
unauthorized. B1 Wave 2 / R-A, Wave 3 / R-B (V20), and Wave 5 Tasks 5.2–5.3 / R-B2 (G2a) are
complete; caller migration 5.4–5.6 is implemented on branch cursor/b1-wave5b-seed-caller-migration (unmerged; G5/5.7 after green PR CI); later B1 waves remain gated. B2's implementation has not
started.

**User-visible state:** there is no functional Asset Picker in the application today.

**Handoff state:** this is the intentional Claude-to-Cursor cutoff. No checkpoint 9.11 action has
started. Cursor's self-contained entry point is
[`CURSOR_HANDOFF_ASSET_PICKER_POST_SPEC_A_9_10.md`](../agent-instructions/CURSOR_HANDOFF_ASSET_PICKER_POST_SPEC_A_9_10.md).

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
| **A — Spec A catalog/data cutover** | Shared catalog, Postgres/Mongo repair, R4 rollout, enforcement, and one reconciled controlled refresh | **10 of 14 cutover checkpoints complete**; 9.11 is the next unauthorized checkpoint | Persist refresh, activate demo portfolio, restore scale-to-zero, reopen ingress |
| **B — B1 portfolio composition backend** | Deployment prerequisites, fixture identity migration, legacy writer retirement, Wave 2 gateway provisioning **served (R-A/G2 green)**, Wave 3 V20 **served (R-B/G3 green)**, Wave 5 version-bearing read **served (R-B2/G2a green)** | **Wave 2 / R-A complete**; **Wave 3 / R-B complete**; **Wave 5 Tasks 5.2–5.3 / R-B2 complete** (Artifact 2a on `portfolio-service--0000081` / `sha256:d544649f…`; cut `f22e2ff`); **Wave 4a–4c tasks 4.1–4.21 merged on `main@2673f40`** (PR #153; composition mechanisms unexposed; no public `PUT`); Task 5.1 merged on `main@f22e2ff` (PR #155); caller migration (5.4–5.7) incomplete | Caller migration / G5, safe desired-state writer activation, public `PUT` |
| **C — B2 Asset Picker product** | Requirements, design, task plan, and five-screen visual mockup | **No implementation wave complete** | Picker UI, decimal adapter, presence/reset support, live integration, exposure |
| **D — Demo credibility** | Canonical prices refreshed and reconciled; demo initializer exists gated off | Demo activation has not run | Spec A 9.12 must seed and verify the complete Active Asset set without touching E2E data |

### What is actually usable today

| Capability | Status |
|---|---|
| Canonical Active Asset catalog inside services | ✅ Shipped |
| Repaired and reconciled price data | ✅ Shipped and verified |
| Enforcement against unsupported holdings/events | ✅ Enabled |
| `GET /api/assets` serving catalog data | ✅ Wave 2 gateway `/api/assets/**` route served with R-A; Wave 4b controller served with R-B2 Artifact 2a (`portfolio-service--0000081`) |
| Version-bearing portfolio read | ✅ G2a/R-B2 green on `portfolio-service--0000081` / `sha256:d544649f…`; caller migration (5.4–5.7) incomplete |
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
| 9.11 | ⏸ Pending authorization | Persist `MARKET_DATA_JOB_RUNNER_ENABLED=true` through Terraform |
| 9.12 | ⏸ Pending | Activate and verify the deterministic full demo portfolio while replicas remain running |
| 9.13 | ⏸ Pending | Restore `min_replicas=0` and verify configuration-level state |
| 9.14 | ⏸ Pending | Reopen ingress after 9.11–9.13 are green |

Additional unfinished Spec A implementation task: **8.8**, replacing remaining hard-coded
catalog-size assertions. Tasks 8.1–8.7, including the aggregate `assetPriceFreshness` contract,
are complete.

### Current production safety boundary

- Persisted refresh runner: `false`.
- Refresh retry limit: `0`.
- Gateway ingress: closed.
- `portfolio-service`, `market-data-service`, and `insight-service`: enforcement enabled,
  `min_replicas=1` for the verification window.
- Controlled refresh: exactly one execution completed; its override was not persisted.
- Demo portfolio activation: not run.
- Checkpoints 9.11–9.14: not authorized.

Checkpoint 9.10 evidence:
[`docs/runbooks/SPEC_A_9_10_CONTROLLED_REFRESH.md`](../runbooks/SPEC_A_9_10_CONTROLLED_REFRESH.md).

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
| 5 — version-bearing read | 🟡 Tasks 5.1–5.3 / R-B2 complete; 5.4–5.6 implemented unmerged; 5.7/G5 pending | Task 5.1 on main@f22e2ff; G2a/R-B2 green on portfolio-service--0000081 / sha256:d544649f…; caller migration on cursor/b1-wave5b-seed-caller-migration (review PR; G5 after green CI) |
| 6 — version-required seed | ⬜ Not started | Seeder delegates through the safe replacement service |
| 7 — activation | ⬜ Not started | Public `PUT /api/portfolio/holdings`, attested candidate, serving proof |

Spec A V17–V19 were applied at checkpoint 9.6; **V20 is applied under R-B** and unchanged by R-B2.
**R-A / G2**, **R-B / G3**, and **R-B2 / G2a** are complete. Wave 4 composition write mechanisms
remain unexposed; public `PUT` and candidate packaging (7.5/R-C) remain incomplete. Caller migration
(5.4–5.7) is the next gated Wave 5 work. **Do not treat a current-`main` portfolio deploy as a
substitute for an authorized Artifact cut.**

### Active B1 work

| Item | Current state | Required before relying on it |
|---|---|---|
| PR #131 / R-A serving | **Complete** — Wave 2 tasks 2.1–2.6; G2 green on `api-gateway--0000076` / `sha256:2da5b303…` ([run 32952197627](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32952197627); [`B1_R_A_G2_SERVING_PROOF.md`](../runbooks/B1_R_A_G2_SERVING_PROOF.md)) | Signup provisioning remains live on the serving gateway revision |
| `cursor/b1-wave3-v20-schema` / PR #152 + R-B | **Complete** — tasks 3.1–3.7 / R-B; V20 applied; G3 green ([`B1_R_B_G3_SERVING_PROOF.md`](../runbooks/B1_R_B_G3_SERVING_PROOF.md)); portfolio traffic superseded by R-B2 | Forward-only after V20; do not roll back migration or gateway |
| `cursor/b1-wave4a-composition-core` / PR #153 | **Merged** on `main@2673f40` – Wave 4a–4c tasks 4.1–4.21. Read-only catalog path is served via Artifact 2a; composition write mechanisms remain **unexposed**; no public `PUT` | Do not start Wave 6–7 or candidate attestation without separate authorization |
| `cursor/b1-wave5a-version-bearing-read` / PR #155 + R-B2 | **Complete for 5.1–5.3** — Task 5.1 on `main@f22e2ff`; Artifact 2a serving on `portfolio-service--0000081` / `sha256:d544649f…` ([run 32982880866](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32982880866); [`B1_R_B2_G2A_SERVING_PROOF.md`](../runbooks/B1_R_B2_G2A_SERVING_PROOF.md)); G2a green | Do not begin Tasks 5.4–5.7 / caller migration without separate authorization; any future portfolio rollout invalidates G2a |
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

All four artifacts are tracked. No B2 implementation task is complete on `main`.

| Wave | Status | Dependency note |
|---|---|---|
| 1 — mock-backed picker shell | ⬜ Not started; partially startable now | Feature flags, modal, browse/draft/review/conflict UX, mocked save/freshness/presence |
| 2 — decimal adapter | ⬜ Not started | Rollout sequencing with B1 Wave 4/5 remains an explicit open coordination decision |
| 3 — Redis-backed presence | ⬜ Not started | Independent B2 backend branch; exact TTL remains open |
| 4 — portfolio-service demo reset | ⬜ Blocked | Requires B1 Wave 4 tasks 4.1, 4.3, 4.7, 4.9, and 4.10 |
| 5 — manual-reset gateway bundle | ⬜ Blocked on Wave 4 | Route, authorization filter, read-only allowlist, identity providers |
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
3. Presence TTL; 150 seconds is provisional.
4. Login self-call timeouts; 2 seconds per leg and 4 seconds overall are provisional.
5. Decimal-adapter deployment sequencing relative to B1 Wave 4/5.

These do not block starting the mock-backed picker shell. They do block the affected reset/presence
behavior and final production exposure.

## 5. Dependency path to a production Asset Picker

```text
Track A: 9.11 -> 9.12 -> 9.13 -> 9.14
                    (production foundation closes)

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

The program is deliberately stopped after Spec A 9.10. This is a clean handoff point because:

- the controlled refresh has a GO decision and durable evidence;
- no temporary refresh override remains active;
- no 9.11 change has started;
- the demo portfolio is untouched;
- scale and ingress fences remain explicit; and
- B1/B2 implementation status is cleanly separable from the production cutover.

### Next choices

1. **Operational lane:** design/review and explicitly authorize Spec A 9.11, then continue through
   9.14 one checkpoint at a time.
2. **Backend lane:** **R-A / G2**, **R-B / G3**, and **R-B2 / G2a** are complete (Artifact 2a
   `portfolio-service--0000081` / `sha256:d544649f…`, cut `f22e2ff`). The next gated Wave 5 work is
   caller migration **Tasks 5.4–5.7 / G5** (5.4–5.6 on review branch; G5 after green CI). Waves 6–7
   and candidate packaging (7.5/R-C) remain separately gated. Do not claim Writer_Convergence while
   the old seed remains version-tolerant.
3. **Frontend lane:** begin the startable mock-backed subset of B2 Wave 1 against frozen contracts.
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
- the five unresolved B2 decisions without silently choosing values;
- the status-governance rule from §0; and
- an instruction to update this plan and the owning task ledger in every status-changing PR.

AWS-only work remains deferred while AWS production is disabled. Azure is the current delivery
target; shared behavior and cross-cloud contracts must not be weakened.
| cursor/b1-wave5b-seed-caller-migration | **Implemented unmerged** — Tasks 5.4–5.6 (three callers + deploy credential wiring + inventory guard); 5.7/G5 pending after required PR CI | Review PR only; no merge/deploy; one authorized Azure synthetic after green CI |
