# Runbook inventory and reconciliation

**Owner approval required before push/PR and merge:** this documentation reconciliation grants
neither publication nor operational authority. An unmerged branch copy is a candidate; the
reconciliation is filed for the project freeze when its reviewed carrying PR merges into `main`.

**Reconciled source:** 2026-09-26 UTC, application/workflow baseline `main@d515aa5b`, plus the
accepted backlog audit through `602f6bca`, filed by PR #324's merge at `6f1e5700`.
No Azure, database, Kafka, secret-store, endpoint or billing read was performed.

Start with [current operations](CURRENT_OPERATIONS.md). The
[demo preparation plan](../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md) is the owner's status
dashboard; this index is navigation and operational interpretation, not another gate ledger.
The [backlog inventory](../todos/backlog/README.md) identifies genuine residual work.

## What was reconciled

All **24 pre-existing runbooks** are classified below: **2 reusable procedures**, **3 legacy
helpers**, and **19 historical records/packets**. The two reusable procedures were corrected
against workflow/configuration source. The other 22 files remain unchanged, including their
historical commands, approvals, outcomes, limitations and byte bindings. This index and the
current-operations guide are two new files, not part of that 24-file count.

This is a source/status/navigation reconciliation, **not** a replay of every historical command
or a fresh verification of its output. No procedure has been live-tested by this audit.

Interpretation rules:

- A historical `GO`, `COMPLETE_LOCAL`, old serving revision or "not started" statement belongs
  to the recorded cut and date. It is neither current serving identity nor a reusable approval.
- Later completion is read from the owning ledger/dashboard, not by rewriting an older record.
  This matters where historical records are hashed by later proofs.
- Old rollback digests, feature-branch dispatches, ingress fences and seed/repair steps are
  **not executable current instructions**. Build a fresh bounded packet before any reuse.
- Accepted-for-demo, inconclusive and unverified findings remain qualified. This audit closes
  no product defect, changes no Spec A/B1/B2 completion box and establishes no new universal proof.

## Reusable procedures

| Runbook | Current use and reconciled boundary |
|---|---|
| [Azure secrets setup](AZURE_SECRETS_SETUP.md) | Setup/recovery only. Corrected dispatcher, OIDC subjects, structural versus live-state planning, backend-secret consumers and whole-file secret-sync scope. Do not recreate the existing environment or assume private keys are present. |
| [Observability](OBSERVABILITY.md) | Manual diagnostics and cost controls. Corrected main-pinned apply inputs and wake success/deadline rules; source defaults are not a fresh cloud/cost audit. The manual allowance-audit cadence is not proof that an audit is current. |

## Legacy helpers — revalidate before reuse

| Runbook | Disposition and replacement context |
|---|---|
| [R-C candidate verification](B1_R_C_CANDIDATE_VERIFICATION.md) | Retained development/release recipe with stale staged-readiness text and old workstation path. B1 tasks 7.3–7.6/7.5a subsequently completed at their accepted cut; see the [B1 ledger](../../.kiro/specs/portfolio-composition-contract/tasks.md). Before new candidate reliance, resolve the [VERSION source-envelope gap](../todos/backlog/b1-candidate-envelope-product-version-root/README.md) and revalidate scripts/policy for the new cut. No release candidate is authorized here. |
| [Kafka lag check](SPEC_A_KAFKA_LAG_CHECK.md) | Historical 9.9 helper, not today's offset baseline. A future approved diagnostic must resolve current group/topic/partition scope and private connection inputs; missing rows/groups are not lag-zero PASS. A point-in-time lag-zero result alone is not proof of writer quiescence. |
| [Mongo repair](SPEC_A_MONGO_REPAIR.md) | One-shot `MM.NS` repair completed at checkpoint 9.7, as recorded in the [Spec A ledger](../../.kiro/specs/supported-asset-integrity/tasks.md). Its direct deploy dispatch and provisioning commands predate today's dispatcher contract. Do not replay the repair, reset its lease/fences, or apply its old ingress/refresh preconditions to the current demo. Any different repair needs a new design, evidence and authorization. |

## Historical records and execution packets — retain, do not replay

| Record / packet | Scoped result and later context |
|---|---|
| [Custom-domain recovery](API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md) | Restore/read-back history, including the failed immediate probe and later healthy checks. G5 subsequently closed; the backlog audit separately records its fixed closure. It is not fresh certificate/TLS proof. |
| [G5 ingress close-out](B1_G5_INGRESS_BLOCKER.md) | Owner closed Task 5.7 on 2026-09-02 using reviewed three-caller evidence. No new G5 rerun or schedule-restoration approval follows. |
| [R-A / G2](B1_R_A_G2_SERVING_PROOF.md) | Signup provisioning proof for the exact old gateway cut. Later R-B/UI gates are not currently "not started" merely because this record predates them. |
| [R-B / G3](B1_R_B_G3_SERVING_PROOF.md) | V20/integrity proof for its old artifact; not the current Flyway ceiling or serving revision. |
| [R-B2 / G2a](B1_R_B2_G2A_SERVING_PROOF.md) | Version-bearing read proof for its exact artifact. Later G5/G2b/R-C work has separate records. |
| [Task 6.5 readiness](B1_TASK_6_5_PRE_DEPLOY_READINESS.md) | R-B3 readiness/packaging and owner decision at the recorded cut; not a new deployment clearance. |
| [Task 6.6 execution packet](B1_TASK_6_6_G2B_EXECUTION_PACKET.md) | Executed one-seed protocol with retained blocked/preflight checkpoints and later completion sections. No continuing credential or seed authority. |
| [Task 6.6 G2b proof](B1_TASK_6_6_G2B_SERVING_PROOF.md) | Accepted historical version-required seed proof and subsequent owner GO. |
| [Task 7.7 writer map](B1_R_C_TASK_7_7_G4_AND_WRITER_MAP.md) | Accepted map/G4 protocol, with separately disclosed retained-range recovery; not proof of pre-existing offsets. |
| [Task 7.7 serving evidence](B1_R_C_TASK_7_7_SERVING_EVIDENCE.md) | Final section supersedes earlier checkpoints within that record. Exact-cut technical predicates passed; no current serving-state refresh. |
| [Task 7.9 serving proof](B1_R_C_TASK_7_9_SERVING_PROOF.md) | Exact R-C activation and one no-op composition operation. Later decision/convergence records do not broaden that one operation. |
| [Task 7.10 STOP/GO](B1_R_C_TASK_7_10_POST_DEPLOY_STOP_GO.md) | Recorded owner GO; collector-reported telemetry/query evidence stays qualified. Its then-pending review/publication text is historical. |
| [Task 7.11 convergence/floor](B1_R_C_TASK_7_11_WRITER_CONVERGENCE_FLOOR.md) | P11g-1 lineage versus exact-artifact P11g-2, canonical bindings and rollback floor. Not a universal attestation of later #320 source. Adding writers/delete paths requires renewed review. |
| [B2 Task 4.5 reset STOP/GO](B2_TASK_4_5_DEMO_RESET_STOP_GO.md) | Accepted historical no-op reset; unchanged version was correct for that SAME_STATE operation. Later B2 delivery is recorded separately. |
| [Spec A 9.10 refresh](SPEC_A_9_10_CONTROLLED_REFRESH.md) | Completed controlled execution at its recorded digest/window. Its skipped-provider tickers and counts are not today's market availability. |
| [Spec A 9.11 refresh enablement](SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md) | Completed desired-state enable/apply; later activation, scale restoration and ingress reopening are not still pending. |
| [Spec A 9.12 RCA](SPEC_A_9_12_POOLED_READONLY_RCA.md) | Operational checkpoint complete; historical setter attribution remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN`. Do not revive diagnostic flags from this record. |
| [Spec A 9.13 scale restore](SPEC_A_9_13_SCALE_RESTORE.md) | Completed scale-to-zero configuration proof; its next-gate sentence predates completed 9.14. |
| [Spec A 9.14 ingress reopen](SPEC_A_9_14_REOPEN_INGRESS.md) | Completed ACA ingress proof, not custom-domain proof at that checkpoint. Recovery and G5 closure were later and separate. |

The owning [Spec A](../../.kiro/specs/supported-asset-integrity/tasks.md),
[B1](../../.kiro/specs/portfolio-composition-contract/tasks.md) and
[B2](../../.kiro/specs/asset-picker-composition/tasks.md) ledgers retain their accepted histories.
Current demo acceptance comes from the dashboard's separate final-build rehearsal, targeted
checks, post-#320 suite and cleanup—not from old runbook serving tables.

## Remaining freeze work

This reconciliation counts as filed only when its independently reviewed, owner-authorized
carrying PR merges into `main`; an unmerged branch copy remains a candidate.
The roadmap audit, broader README/architecture reconciliation, media brainstorming/package and
approved private-artifact/worktree cleanup remain separate work. Before a long pause, identify
the operator who owns the manual observability audit and approved secret/identity maintenance;
no schedule, rotation, resource shutdown or recurring monitor was created by this documentation.
