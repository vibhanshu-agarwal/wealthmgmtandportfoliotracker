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
independent of the runtime baseline above. This status is grounded on `main@743c9b971e857d76c659e0e2c40e339c6c2bf4a3`;
the pinned Spec A 9.14 plan-evidence baseline is
`main@66bbee0bf438706146ac9975bf5f0c923b3d43cb`.

**Program state:** Spec A checkpoints 9.1–9.13 are operationally complete. Checkpoint 9.11 persisted
`MARKET_DATA_JOB_RUNNER_ENABLED=true` through Terraform apply on `main@e7fad7cb` (source PR #164;
evidence [`SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md`](../runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md)).
Checkpoint 9.12 source merged via PRs #167, #169, #170, and #172; the first authorized enable apply ran but failed
to converge (startup transaction PostgreSQL read-only) and was rolled back. Provenance, connection-origin,
and statement-history probes remain on the record. The authorized 2026-08-30 retry at
`main@d29f67083109086de4ed00d38589267609e24265` succeeded: enable apply created
`portfolio-service--0000090` (159 holdings, one seed event); restoring apply created
`portfolio-service--0000091` with both flags `false`. Operational checkpoint verdict: PASS.
Historical RCA remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN` — evidence
[`SPEC_A_9_12_POOLED_READONLY_RCA.md`](../runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md).
Checkpoint 9.13 is live-green on `portfolio-service--0000092`, `market-data-service--0000079`,
and `insight-service--0000079` ([`SPEC_A_9_13_SCALE_RESTORE.md`](../runbooks/SPEC_A_9_13_SCALE_RESTORE.md)).
Checkpoint 9.14 source merged via PR #184 at `main@66bbee0`; the authorized read-only remote-plan
[33313072724](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33313072724)
passed the exact-scope 9.14 guard, while the apply job was skipped. Senior plan review returned
**ACCEPT** on 2026-08-31 against acceptance ids A1-A4, B1-B7, and C1-C3, with no apply blocker;
acceptance authorizes no write. Checkpoint 9.14 is now **complete**: apply
[33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603)
enabled ACA external ingress on the existing `api-gateway--0000077` revision, insecure connections
remain disabled, and the default ACA endpoint is healthy
([`SPEC_A_9_14_REOPEN_INGRESS.md`](../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md)). B1 Wave 2 /
R-A, Wave 3 / R-B (V20), and Wave 5 Tasks 5.2–5.3 / R-B2 (G2a) are complete; caller migration Tasks
**5.4–5.6 merged on `main@0b5d60d1`** (PR #161, source-only; no deploy); **G5/5.7 remains blocked**
after the separately authorized custom-domain restore — not by current host reachability, but pending
independent review of the live restoration evidence and then a separately authorized synthetic that
exercises all three callers. See [`B1_G5_INGRESS_BLOCKER.md`](../runbooks/B1_G5_INGRESS_BLOCKER.md);
later B1 waves remain gated.
B2 Wave 1 (Tasks 1.1-1.19) and Wave 2 Tasks 2.1-2.5 are merged source-only through PR #178 at `main@38e3d95`; they remain entirely mock-backed and disabled by default. Wave 3 presence source Tasks 3.1–3.6 merged source-only via PR #179 at `main@cc97a209`; Task 3.7 deploy/live proof remains open (not deployed, not activated, not live-probed). Wave 4 Tasks 4.1–4.4a merged source-only via PR #180 at `main@63fc058`; they are not deployed, routed, or user-visible. Task 4.5 and Waves 5–10 remain gated; Wave 2 Tasks 2.6–2.7 remain open.

**User-visible state:** there is no functional Asset Picker in the application today.

**Handoff state:** Spec A 9.12 is **operationally complete** and Spec A 9.13 is **complete** on
`portfolio-service--0000092`, `market-data-service--0000079`, and `insight-service--0000079`;
the demo still holds 159 holdings with both demo/diagnostic flags `false`, and historical setter
attribution remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN`. The guarded 9.14 source and its pinned
read-only remote-plan are green, senior plan review returned **ACCEPT** on 2026-08-31 with no apply
blocker, and the authorized apply completed. Spec A's production cutover checkpoints 9.1–9.14 are
now all complete. Gateway ingress is open on the default ACA endpoint only; the three catalog
consumers remain at `min_replicas=0`. B1 G5 is **not** unblocked — see the custom-domain binding
gap below.

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
| **A — Spec A catalog/data cutover** | Shared catalog, Postgres/Mongo repair, R4 rollout, enforcement, one reconciled controlled refresh, persisted refresh enablement, demo portfolio activation, and scale-to-zero restoration | **All 14 cutover checkpoints complete.** 9.13 is live-green on `portfolio-service--0000092`, `market-data-service--0000079`, and `insight-service--0000079`; 9.14 completed via apply [33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603), reopening ACA external ingress on `api-gateway--0000077` with `allowInsecure=false` ([`SPEC_A_9_14_REOPEN_INGRESS.md`](../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md)); the later custom-domain restore has independent `200` read-back, while its evidence remains under review ([`API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`](../runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md)); historical RCA remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN` | Spec A's production cutover is done. Remaining work is independent restoration-evidence review before any separately authorized B1 G5 synthetic, plus the four filed process follow-ups |
| **B — B1 portfolio composition backend** | Deployment prerequisites, fixture identity migration, legacy writer retirement, Wave 2 gateway provisioning **served (R-A/G2 green)**, Wave 3 V20 **served (R-B/G3 green)**, Wave 5 version-bearing read **served (R-B2/G2a green)** | **Wave 2 / R-A complete**; **Wave 3 / R-B complete**; **Wave 5 Tasks 5.2–5.3 / R-B2 complete** (Artifact 2a on `portfolio-service--0000081` / `sha256:d544649f…`; cut `f22e2ff`); **Wave 4a–4c tasks 4.1–4.21 merged on `main@2673f40`** (PR #153; composition mechanisms unexposed; no public `PUT`); Task 5.1 merged on `main@f22e2ff` (PR #155); Tasks **5.4–5.6 merged on `main@0b5d60d1`** (PR #161, source-only); **5.7/G5 blocked pending independent restoration-evidence review and a separately authorized three-caller synthetic** | Review the restoration evidence, then authorize and run G5 (or an authorized private-reachability test), safe desired-state writer activation, public `PUT` |
| **C — B2 Asset Picker product** | Requirements, design, task plan, five-screen visual mockup, Wave 1 / partial Wave 2 frontend source, Wave 3 presence source, Wave 4 demo-reset source | Wave 1 (1.1-1.19) + Wave 2 Tasks 2.1-2.5 merged source-only on `main@38e3d95` via PR #178; Wave 3 Tasks 3.1–3.6 merged source-only on `main@cc97a209` via PR #179 (Task 3.7 deploy/live proof open); Wave 4 Tasks 4.1–4.4a merged source-only via PR #180 at `main@63fc058` (not deployed/routed) | Task 3.7 deploy/live proof; Task 4.5+ and authorized deployment of Wave 4; reset gateway bundle, live integration, exposure remain separately gated |
| **D — Demo credibility** | Canonical prices refreshed and reconciled; demo initializer exists; authorized 9.12 retry activated the Active_Asset set | Demo portfolio still holds 159 holdings after the 9.13 scale restore on `portfolio-service--0000092`, with both flags `false`; historical pooled-session setter remains unidentified | 9.14 and the custom-domain restore are complete; keep B1 G5 gated pending evidence review and separately authorized caller proof, and do not treat operational 9.12 success as historical RCA closure |

### What is actually usable today

| Capability | Status |
|---|---|
| Canonical Active Asset catalog inside services | ✅ Shipped |
| Repaired and reconciled price data | ✅ Shipped and verified |
| Enforcement against unsupported holdings/events | ✅ Enabled |
| `GET /api/assets` serving catalog data | ✅ Wave 2 gateway `/api/assets/**` route served with R-A; Wave 4b controller served with R-B2 Artifact 2a (`portfolio-service--0000081`) |
| Version-bearing portfolio read | ✅ G2a/R-B2 green on `portfolio-service--0000081` / `sha256:d544649f…`; caller migration 5.4–5.6 on `main@0b5d60d1` (source-only, not deployed); **5.7/G5 blocked pending independent restoration-evidence review and separately authorized caller proof** |
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
| 9.12 | ✅ Operationally complete on `portfolio-service--0000091` | Authorized retry at `main@d29f670`: enable [33295859015](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33295859015) (`0000090`, 159 holdings, one seed event); restoring [33296204759](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33296204759) (`0000091`, both flags `false`); Neon tuple MD5 `6e436f24fa2b31d14aff77fe5d1a05c9`; historical RCA remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN` ([`SPEC_A_9_12_POOLED_READONLY_RCA.md`](../runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md)) |
| 9.13 | ✅ Complete | Guarded `spec-a-9.13-restore-scale` restored `min_replicas=0` on the three catalog consumers; live revisions `portfolio-service--0000092`, `market-data-service--0000079`, `insight-service--0000079`; evidence [`SPEC_A_9_13_SCALE_RESTORE.md`](../runbooks/SPEC_A_9_13_SCALE_RESTORE.md) |
| 9.14 | ✅ Complete | PR #184 merged the guarded `spec-a-9.14-reopen-ingress` / `spec-a-9.14-close-ingress` profiles at `main@66bbee0`; read-only remote-plan [33313072724](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33313072724) passed the exact-scope guard and skipped apply; senior review returned **ACCEPT** on 2026-08-31 (A1-A4, B1-B7, C1-C3; no apply blocker); authorized apply [33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603) at `main@743c9b97` enabled external ingress on the existing `api-gateway--0000077` revision with `allowInsecure=false` and a healthy default ACA endpoint ([`SPEC_A_9_14_REOPEN_INGRESS.md`](../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md)); does **not** unblock B1 G5 |

Additional unfinished Spec A implementation task: **8.8**, replacing remaining hard-coded
catalog-size assertions. Tasks 8.1–8.7, including the aggregate `assetPriceFreshness` contract,
are complete.

### Current production safety boundary

- Persisted refresh runner: `true` (checkpoint 9.11 complete; scheduled Job may run at `0 8 * * *`).
- Refresh retry limit: `0`.
- Gateway ingress: **open** on live `api-gateway--0000077` after checkpoint 9.14, with
  `allowInsecure=false`, `targetPort=8080`, `transport=Auto`, and a single 100% `latestRevision`
  traffic weight. No new revision was cut. Reversal profile `spec-a-9.14-close-ingress` is now
  usable.
- Custom domain: **restored.** `api.vibhanshu-ai-portfolio.dev` has the exact `SniEnabled` binding
  to the existing succeeded managed certificate. The guarded apply/bind workflow's immediate default
  health observation was non-`200`, but independent read-back then found both default and custom health
  endpoints at `200`. See [`API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`](../runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md).
  This does not unblock B1 G5: evidence review and a separately authorized three-caller synthetic are
  still required; backlog item
  [`api-gateway-custom-domain-binding`](../todos/backlog/api-gateway-custom-domain-binding/README.md).
- `SERVICE_VERSION` drift: `api-gateway` and `portfolio-service` advertise a `SERVICE_VERSION`
  that is not the image they run. Pre-existing, untouched by 9.14 — backlog item
  [`service-version-image-drift`](../todos/backlog/service-version-image-drift/README.md).
- `portfolio-service`, `market-data-service`, and `insight-service`: enforcement enabled,
  `min_replicas=0` after checkpoint 9.13.
- Controlled refresh: exactly one authorized one-off execution completed at 9.10; 9.11 did not start
  an additional execution.
- Demo portfolio activation: operationally complete on `portfolio-service--0000091`; production
  gate `APP_DEMO_SEED_ON_STARTUP` is `false`; demo holds 159 Active_Asset holdings; diagnostics
  flag also `false`.
- Checkpoint 9.12 is operationally complete; historical RCA remains
  `MECHANISM_REPRODUCED_SETTER_UNPROVEN`. Checkpoints 9.13 and 9.14 are live-green
  ([`SPEC_A_9_12_POOLED_READONLY_RCA.md`](../runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md)).
- B1 G5 remains blocked pending independent review of the executed custom-domain recovery evidence
  and a separately authorized three-caller synthetic — not by current ingress or hostname reachability.

Checkpoint 9.10 evidence:
[`docs/runbooks/SPEC_A_9_10_CONTROLLED_REFRESH.md`](../runbooks/SPEC_A_9_10_CONTROLLED_REFRESH.md).

Checkpoint 9.11 evidence:
[`docs/runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md`](../runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md).

Checkpoint 9.12 RCA evidence (operationally complete; historical setter unproven):
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
| 5 — version-bearing read | 🟡 Tasks 5.1–5.3 / R-B2 complete; **5.4–5.6 merged on `main@0b5d60d1`** (PR #161, source-only); **5.7/G5 blocked pending independent restoration-evidence review and separately authorized caller proof** | Task 5.1 on main@f22e2ff; G2a/R-B2 green on portfolio-service--0000081 / sha256:d544649f…; caller migration merged source-only; historical G5 runs 33046987880 / 33047168136 failed at TLS login before seed; the endpoint has since been restored with independent `200` read-back ([`B1_G5_INGRESS_BLOCKER.md`](../runbooks/B1_G5_INGRESS_BLOCKER.md)) |
| 6 — version-required seed | ⬜ Not started | Seeder delegates through the safe replacement service |
| 7 — activation | ⬜ Not started | Public `PUT /api/portfolio/holdings`, attested candidate, serving proof |

Spec A V17–V19 were applied at checkpoint 9.6; **V20 is applied under R-B** and unchanged by R-B2.
**R-A / G2**, **R-B / G3**, and **R-B2 / G2a** are complete. Wave 4 composition write mechanisms
remain unexposed; public `PUT` and candidate packaging (7.5/R-C) remain incomplete. Caller migration
source **Tasks 5.4–5.6 are on `main@0b5d60d1`** (PR #161; source-only, not deployed); **5.7/G5
remains incomplete** until the restoration evidence is independently reviewed and a separately
authorized public synthetic (or private-reachability test) exercises all three callers. Wave 6 / R-B3
stay gated. **Do not treat a current-`main` portfolio deploy as a
substitute for an authorized Artifact cut.**

### Active Spec A work

| Item | Current state | Required before relying on it |
|---|---|---|
| Checkpoint 9.11 | **Complete** — apply run [33091163222](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33091163222); live runner `true`; standard no-op [33093260896](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33093260896); evidence [`SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md`](../runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md) | 9.12 operationally complete; 9.13 live-green |
| 9.12 enable / rollback / retry | **Operationally complete** — first enable failed and rolled back; authorized 2026-08-30 retry succeeded on `portfolio-service--0000090`/`0000091`; historical RCA remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN` ([`SPEC_A_9_12_POOLED_READONLY_RCA.md`](../runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md)) | Do not treat retry success as named-setter attribution; keep `pg_stat_statements` installation separately gated |
| 9.13 restore scale | **Complete** — remote-plan [33306477527](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33306477527); apply [33306874697](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33306874697); live `portfolio-service--0000092`, `market-data-service--0000079`, `insight-service--0000079` ([`SPEC_A_9_13_SCALE_RESTORE.md`](../runbooks/SPEC_A_9_13_SCALE_RESTORE.md)) | Superseded by 9.14, which reopened gateway ingress |
| 9.14 reopen ingress | **Complete** — PR #184 / `main@66bbee0`; guarded read-only plan [33313072724](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33313072724) passed; apply skipped; reviewer orientation merged via [PR #187](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/187) | Plan review **ACCEPTed** 2026-08-31; apply [33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603) passed the `production` gate and all twelve assertions. Live: external ingress on `api-gateway--0000077`, `allowInsecure=false`, default ACA endpoint healthy. Does **not** unblock B1 G5; four process follow-ups filed in `docs/todos/backlog/` |

### Active B1 work

| Item | Current state | Required before relying on it |
|---|---|---|
| PR #131 / R-A serving | **Complete** — Wave 2 tasks 2.1–2.6; G2 green on `api-gateway--0000076` / `sha256:2da5b303…` ([run 32952197627](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32952197627); [`B1_R_A_G2_SERVING_PROOF.md`](../runbooks/B1_R_A_G2_SERVING_PROOF.md)) | Signup provisioning remains live on the serving gateway revision |
| `cursor/b1-wave3-v20-schema` / PR #152 + R-B | **Complete** — tasks 3.1–3.7 / R-B; V20 applied; G3 green ([`B1_R_B_G3_SERVING_PROOF.md`](../runbooks/B1_R_B_G3_SERVING_PROOF.md)); portfolio traffic superseded by R-B2 | Forward-only after V20; do not roll back migration or gateway |
| `cursor/b1-wave4a-composition-core` / PR #153 | **Merged** on `main@2673f40` – Wave 4a–4c tasks 4.1–4.21. Read-only catalog path is served via Artifact 2a; composition write mechanisms remain **unexposed**; no public `PUT` | Do not start Wave 6–7 or candidate attestation without separate authorization |
| `cursor/b1-wave5a-version-bearing-read` / PR #155 + R-B2 | **Complete for 5.1–5.3** — Task 5.1 on `main@f22e2ff`; Artifact 2a serving on `portfolio-service--0000081` / `sha256:d544649f…` ([run 32982880866](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32982880866); [`B1_R_B2_G2A_SERVING_PROOF.md`](../runbooks/B1_R_B2_G2A_SERVING_PROOF.md)); G2a green | Do not begin Tasks 5.4–5.7 / caller migration without separate authorization; any future portfolio rollout invalidates G2a |
| `cursor/b1-wave5b-seed-caller-migration` / PR #161 | **Merged source-only on `main@0b5d60d1`** — Tasks 5.4–5.6; **5.7/G5 incomplete** — historical runs 33046987880 / 33047168136 failed before the later custom-domain restore; current evidence is pending independent review ([`B1_G5_INGRESS_BLOCKER.md`](../runbooks/B1_G5_INGRESS_BLOCKER.md)) | No deploy; no G5 claim; Wave 6/R-B3 still gated; resume G5 only after evidence review and separately authorized caller proof |
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

All four artifacts are tracked. Wave 1 (1.1-1.19) and Wave 2 Tasks 2.1-2.5 merged source-only on `main@38e3d95` through PR #178 after two external review rounds and regression fixes. That frontend remains entirely mock-backed and disabled by default; the merge did not authorize live endpoint wiring, deployment, or production exposure. Wave 3 presence source Tasks 3.1–3.6 merged source-only via PR #179 at `main@cc97a209` with a configurable 150-second default TTL; Task 3.7 deploy/live probe remains a separate owner-gated production step (not deployed, not activated, not live-probed). Wave 4 Tasks 4.1–4.4a merged source-only via PR #180 at `main@63fc058`; they are not deployed or routed. Wave 8 Task 8.1 (the additive `updatedAt` read contract) merged source-only via PR #185 at `main@198c878d`; not deployed.

| Wave | Status | Dependency note |
|---|---|---|
| 1 — mock-backed picker shell | ✅ Source merged (1.1-1.19), mock-backed only; not deployed/live | Feature flags, modal, browse/draft/review/conflict UX, mocked save/freshness/presence; PR #178 / `main@38e3d95` |
| 2 — decimal adapter | 🟡 Tasks 2.1-2.5 source merged; 2.6-2.7 not started | PR #178 / `main@38e3d95`; rollout sequencing with B1 Wave 4/5 remains an explicit open coordination decision |
| 3 — Redis-backed presence | 🟡 Tasks 3.1–3.6 source merged via PR #179 / `main@cc97a209`; Task 3.7 open | Default TTL **150s** via `APP_DEMO_PRESENCE_TTL`; not deployed/live-verified |
| 4 — portfolio-service demo reset | 🟡 Tasks 4.1–4.4a source merged via PR #180 / `main@63fc058`; Task 4.5 open | B1 prerequisites verified on `main@cc97a209`; not deployed/routed |
| 5 — manual-reset gateway bundle | ⬜ Blocked on Wave 4 Task 4.5 / deployment proof | Route, authorization filter, read-only allowlist, identity providers |
| 6 — manual reset frontend | ⬜ Blocked on Wave 5 and B1 5.1 | Hidden control and versioned reset call |
| 7 — decimal rollout note | ℹ Informational | No independent release gate |
| 8 — login-orchestrated reset | 🟡 Task 8.1 (`updatedAt` read contract) source merged via [PR #185](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/185) / `main@198c878d`; not deployed. Tasks 8.2 and later not started | Requires B1/V20/version read (met, satisfied by 8.1); open idle/timeouts, Tasks 8.2 and later, the separate 5.1a/5.1b/8.2a prerequisites, and its own deployment evidence |
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
Track A: 9.12 complete -> 9.13 complete -> 9.14 complete (ingress reopened)
                                       (Spec A production cutover checkpoints all complete)

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

Spec A 9.14 is the last completed production checkpoint. Historical setter attribution remains
`MECHANISM_REPRODUCED_SETTER_UNPROVEN`. Production is on `portfolio-service--0000092`,
`market-data-service--0000079`, and `insight-service--0000079`; the demo still has 159 holdings and
both demo/diagnostic flags remain `false`. The three catalog consumers are restored to
`min_replicas=0`; gateway ingress is open after the 9.14 apply, and the
`api.vibhanshu-ai-portfolio.dev` custom-domain binding has since been restored with independent
public `200` read-back.

- historical setter remains unidentified; statement-history probe executed once on 2026-08-29 at
  `main@cdf23737` and returned `STATEMENT_HISTORY_PROBE_EXECUTED_HISTORY_UNAVAILABLE`;
- production demo and diagnostics gates remain `false` after the restoring apply;
- scale has been restored to `min_replicas=0`; ingress is reopened on the default ACA endpoint;
- 9.14 is complete and live-verified; **B1 G5 remains blocked** pending independent recovery-evidence
  review and separately authorized caller proof, and four process follow-ups are filed in
  `docs/todos/backlog/`; and
- B1/B2 implementation status is cleanly separable from the remaining production cutover.

### Next choices

1. **Operational lane:** Spec A's production cutover is **complete through 9.14**. The plan was
   reviewed and ACCEPTed (2026-08-31), and the authorized apply
   [33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603)
   reopened ACA external ingress on the existing `api-gateway--0000077` revision with insecure
   connections still disabled
    ([`SPEC_A_9_14_REOPEN_INGRESS.md`](../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md)). The separately
    authorized custom-domain plan and apply/bind have restored and independently read back
    `api.vibhanshu-ai-portfolio.dev`; the immediate workflow health observation was non-`200`, so
    its durable evidence is pending independent review
    ([`api-gateway-custom-domain-binding`](../todos/backlog/api-gateway-custom-domain-binding/README.md)).
    That review must complete before any separately authorized G5 synthetic. Three further process follow-ups are filed alongside it:
   [`service-version-image-drift`](../todos/backlog/service-version-image-drift/README.md),
   [`deployed-image-tags-json-validation`](../todos/backlog/deployed-image-tags-json-validation/README.md),
   and [`b5-image-equality-assurance-claim`](../todos/backlog/b5-image-equality-assurance-claim/README.md).
   Installing
   `pg_stat_statements`, claiming a named historical setter, B1 G5, or any other production action
   remains separately gated.
2. **Backend lane:** **R-A / G2**, **R-B / G3**, and **R-B2 / G2a** are complete (Artifact 2a
   `portfolio-service--0000081` / `sha256:d544649f…`, cut `f22e2ff`). Tasks **5.4–5.6 are merged
   source-only on `main@0b5d60d1`** (PR #161); **5.7/G5 is blocked** pending independent review of
   the executed recovery evidence and then a separately authorized synthetic that exercises all
   three real callers (or a separately authorized private-reachability test). Waves 6–7 and
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
