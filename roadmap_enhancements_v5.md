# Roadmap Enhancements v5 — Deferred Features and Restart Context

**Reconciled:** 2026-09-26 UTC against source at `main@5d559478`, the filed backlog/runbooks,
and the accepted evidence linked from the [demo dashboard](docs/plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md).

**Owner approval required before publication or implementation:** push/PR and merge are separate
owner decisions. An unmerged branch copy is a candidate; this reconciliation is filed only when
its independently reviewed, owner-authorized carrying PR merges into `main`. It grants no live
access, implementation, deployment or cleanup authority.

This v5 supersedes [v4](roadmap_enhancements_v4.md) for current roadmap interpretation, not for
historical evidence. v1–v4 remain unchanged historical snapshots; v5 is the current planning queue.

The project is a portfolio/demo application. Feature development is intended to pause for months
or longer after the remaining freeze work. The three new owner requests below are deliberately
brief and unscheduled: enough context to restart after months or a year, not detailed designs.

## 1. What Is Already Delivered

| Item carried from v4 / later delivery | Current disposition and boundary |
|---|---|
| Gateway rate limiting | **DELIVERED.** Standard, AI and auth tiers, fail-open behavior and `Retry-After` handling. Redis-outage cost exposure remains; this is not a guarantee of downstream availability. |
| Signup and per-user authentication | **DELIVERED.** Gateway-owned bcrypt/JWT identity, transactional signup, empty portfolio and read-only demo enforcement; Better Auth retired. This does not include account settings or token revocation after logout. |
| Observability / Application Insights | **DELIVERED at accepted historical scope.** Shared sanitization, OTLP export and gateway/Kafka continuity. Daily caps and alerts are controls, not a monthly bill ceiling; manual maintenance and provider-preview exit criteria remain. |
| Supported catalog and asset picker | **DELIVERED.** `/api/assets`, catalog-backed selection, versioned composition writes, validation/conflicts, isolation and controlled demo reset. v4's "no component or endpoint" statement is obsolete. Provider coverage/freshness and corporate actions remain separate. |
| Portfolio analytics and demo-critical UI | **DELIVERED within the recorded scope.** Stored-data valuations, FX/coverage/freshness, performance and holdings views; #320 repairs were deployed and the final-build suite accepted as `PASS_WITH_EXPECTED_DEFECTS`. No clean-PASS or all-edge-case claim. |

`DELIVERED` means the named capability exists with its recorded acceptance; it does not mean
every related defect is closed. The [audited backlog](docs/todos/backlog/README.md) records
8 fixed/completed closures, 2 superseded closures and 20 open directory items. Its separate
inline TODO inventory is not added to those counts. The dashboard retains accepted demo debt
and inconclusive/unverified cases, including model provenance/resolution and FX/fallback edges.

## 2. New Owner Requests — Deferred, Not Started

No priority order, target date, implementation plan or detailed design is committed. Reassess
data sources, dependencies and product goals when the owner resumes the project.

### R1 — Per-user Sharpe Ratio and Sortino Ratio

Improve portfolio analytics with risk-adjusted performance measures for each user's portfolio:
Sharpe for total-volatility risk and Sortino for downside risk. First revisit return-history
quality, lookback/annualization, risk-free or target return, FX and cash-flow/holdings-history
conventions. Current valuation charts are not automatically a valid portfolio-return series;
insufficient history should be disclosed rather than turned into misleading ratios.

### R2 — More Intelligent Fundamental / Technical Analysis Chat

Move beyond a simple ticker snapshot to selectable **fundamental analysis (FA)** and
**technical analysis (TA)**, with explanatory follow-ups and portfolio context where supported.
At restart, decide which analysis options have trustworthy fundamentals/price-history data,
and distinguish computed facts/indicators from model interpretation, cached answers and fallback
output. No particular indicator set, provider, advisory capability or model is promised now.

### R3 — More Engaging Charts and Analysis

Explore a small, useful set of complementary views—for example drawdown, benchmark comparison,
risk/return, allocation/concentration and interactive time ranges. Choose based on user value
and available data rather than adding charts for their own sake. Historical positions, cash
flows and benchmark alignment may be prerequisites; examples are not committed deliverables.

These are the same R1–R3 entries in [ROADMAP.md](ROADMAP.md), not duplicate engineering backlog
items or permission to start work. Formal risk-adjusted metrics are distinct from the existing
AI advisor's generated risk score/concentration commentary.

## 3. Existing Future Goals Retained, Not Silently Closed

| Goal | Restart context |
|---|---|
| User settings/personalization | **OPEN / UNSCHEDULED.** Settings remains a placeholder. Persisted identity exists; per-user preferences/base currency/risk tolerance need product/schema design. |
| Custom assets / expanded portfolio management | **OPEN / UNSCHEDULED.** Different from the delivered supported-catalog picker. Define ownership, valuation and provider/catalog coverage before accepting assets outside that universe. |
| Multi-provider market data | **OPEN / UNSCHEDULED.** Yahoo remains an unofficial provider dependency. Symbol repairs mitigate symptoms, not provider risk; revisit supported providers, licensing, cost, coverage and failover. |
| AI service contract evolution | **OPEN / UNSCHEDULED.** `insight-service` already exists. gRPC or a managed agent boundary is an optional evolution, not an undelivered service; justify it against richer analysis requirements. |
| Advanced agent workflows | **OPEN / EXPLORATORY.** Rebalancing simulations, streaming news/sentiment and tax-aware ideas remain larger future directions, not delivered autonomous financial advice. Reassess data, risk/jurisdiction and operational guardrails. |
| Database least privilege | **OPEN future hardening goal.** Revisit a scoped application role rather than owner credentials. No new role/credential inventory or migration was performed here. |
| AWS reactivation | **PARKED, not a current defect or ready rollback.** Source remains, but identity/resources/DNS/model access/cost and current-build compatibility require a new approved assessment. |

No automatic implementation order is assigned to this table. Exact residuals—such as provider
risk, Kafka idle wake policy, Tata corporate-action allocation, narrow-width overflow and
verification-tool gaps—stay in their existing backlog entries; do not recreate repaired work.

## 4. Freeze Work Versus Later Features

- **Filed:** technical demo evidence/status through #323, backlog reconciliation through #324
  (`6f1e5700`), and runbook reconciliation through #325 (`5d559478`). Evidence remains scoped;
  documentation merges did not rerun or deploy the app.
- **Root-document reconciliation:** README, roadmap and v5. It counts as filed only when
  its independently reviewed, owner-authorized carrying PR merges into `main`.
- **Still separate:** wider README/architecture/release-document consistency review; the
  owner/Claude/Codex brainstorm before drafting LinkedIn, resume, PPT and video material;
  maintenance/cost/identity ownership; private evidence/worktree inventory and approved cleanup.
- **Not a freeze blocker:** implementing R1–R3 or the other deferred feature goals. Recording
  them preserves intent; it does not extend the demo feature scope or require another live run.

## 5. Restart Checklist

1. Read the dashboard, this v5, the backlog and [current operations](docs/runbooks/CURRENT_OPERATIONS.md).
   Confirm what is delivered, accepted debt, inconclusive or genuinely open before choosing work.
2. Under approved scope, re-establish the actual serving/configuration baseline, dependency/model
   availability, costs and data-history suitability. A dated roadmap is not a current cloud read.
3. Select a small feature slice with the owner, write/review its design and acceptance criteria,
   then assign implementation to the implementing agent. No detailed design is approved here.
4. Do not replay historical repair/seed packets, reuse deleted certification users or treat
   historical revision labels as current identity. Reassess evidence after material changes.

## Source Pointers

- [Root module list](settings.gradle), [backend build](build.gradle),
  [frontend package](frontend/package.json) and [canonical catalog](config/seed-tickers.json).
- [Composition master plan](docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md) and
  [supported-asset task ledger](.kiro/specs/supported-asset-integrity/tasks.md).
- [Analytics response contract](portfolio-service/src/main/java/com/wealth/portfolio/dto/PortfolioAnalyticsDto.java),
  [chat controller](insight-service/src/main/java/com/wealth/insight/ChatController.java) and
  [Settings placeholder](<frontend/src/app/(dashboard)/settings/page.tsx>).
- [Runbook inventory](docs/runbooks/README.md), [backlog inventory](docs/todos/backlog/README.md)
  and [demo dashboard](docs/plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md) are the status/evidence
  starting points; no fresh live, cloud, secret or billing evidence was collected for v5.
