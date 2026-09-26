# Architecture documentation index

**Source reconciliation:** 2026-09-26 UTC against `main@8aa4035b`. This folder describes the
portfolio-demo application, not a commercial production SLA. Resource/profile names containing
"Production" do not change that purpose. No fresh cloud inventory, deployment, model call or
application test was performed for this audit.

Use the [demo dashboard](../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md) for accepted runtime
status (`PASS_WITH_EXPECTED_DEFECTS`) and remaining freeze work. Source/configuration below is
not proof of today's serving state. Publication requires independent review and owner approval;
an unmerged branch copy is a candidate, filed only when its carrying PR merges into main.

## Current reference set

| File | Purpose |
|---|---|
| [Overview v2](WealthManagementArchitectureOverview_v2.md) | Short system, module and deployment overview |
| [Detailed architecture v2](WealthManagementArchitectureDocumentation_v2.md) | Data ownership, request/event boundaries and evidence limits |
| [Guardrails v2](CoreArchitecturalGuardrails_v2.md) | Current design/operational constraints, not a new execution approval |
| [Integration test cases](IntegrationTestCases.md) | Source-linked test inventory and workflow scope; not a passing test report |
| [Risk and mitigation plan](RiskMitigationPlan.md) | Implemented controls versus residual risks; no new deadlines or defect closures |
| [PlantUML source](architecture.puml) | Logical current-demo topology; not a cloud resource inventory or rendered image |

The six reusable references above were corrected in this audit. Detailed source paths are in
the four [service E2E guides](../e2e-flows/); their companion reconciliation is also a local
candidate until reviewed and merged. Operator instructions are in
[Current operations](../runbooks/CURRENT_OPERATIONS.md).

## Historical records — do not execute as current plans

| File | Classification / reason |
|---|---|
| [Original overview](WealthManagementArchitectureOverview.md) | Earlier architecture snapshot; v2 is the current reference |
| [Original detailed architecture](WealthManagementArchitectureDocumentation.md) | Earlier snapshot; module/runtime/data claims have changed |
| [LocalStack migration proposal](localstack-terraform-migration-proposal.md) | April design proposal, not a current RDS/ElastiCache migration request |
| [LocalStack setup](terraform-localstack-setup.md) | April procedure with old paths/state/apply commands; not a safe current runbook |
| [Phase 3 app wiring](phase3-app-wiring-status.md) | April LocalStack blocker record; not Demo Preparation Phase 3 status |
| [Market-data expansion specification](market-data-expansion-spec.md) | Earlier requirements/design; startup replay is not the current implementation |
| [Lambda/Lightsail analysis](lambda-vs-lightsail-analysis.md) | April cost/design exploration; prices, quotas and feasibility not refreshed |
| [Lambda stopgap plan](lambda-stopgap-execution-plan.md) | April execution history, including abandoned Scheduler sketches; no renewed authority |

Their bodies remain historical, with new classification banners. Do not resume their TODOs,
reuse their approvals, reset state/offsets, run auto-approve commands or trust their resource
counts as current. Reassess against the current reference set and approved operation packet.

[Guardrails v1](CoreArchitecturalGuardrails_v1.md) was an empty file at the audit baseline.
It is explicitly marked as an empty historical placeholder, not reconstructed as a past policy.

## Future work and restart

[ROADMAP](../../ROADMAP.md), [enhancements v5](../../roadmap_enhancements_v5.md) and the
[backlog index](../todos/backlog/README.md) govern deferred work. Sharpe/Sortino, richer FA/TA
chat and additional charts remain unscheduled. This audit neither implements them nor changes
backlog dispositions. Before later reactivation, refresh source/serving identity and private
prerequisites under the applicable approval; historical documents are context, not new tasks.
