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

**Owner priority update:** 2026-09-27 UTC. The prioritization matrix is restored for restart
planning: per-user Sharpe/Sortino is **High** priority; every other open roadmap goal is
**Medium**. Priorities do not authorize implementation or commit delivery dates.

## Prioritization Matrix — Resume Here

Closed capabilities remain visible so they are not implemented again. Priority applies only to
remaining work. These are lightweight planning judgments, not measured scores or delivery
estimates. Existing v4 ratings are retained. **Classification consensus — 2026-09-27 UTC:**
Claude confirmed the revised matrix against local commit `28b7f16d`; Codex and Claude agree
on the ratings below. R1 is High importance / High usability / Medium ease, with High priority,
including charts/graphs, explanations and AI-assisted improvement guidance. The agreed changes
also make row 11 usability Low and row 12 importance Low. This was a quick classification pass,
not a detailed design or live assessment. Reassess assumptions at restart.

- **Importance:** impact on analytical usefulness, reliability or the project's goals.
- **Usability:** direct benefit to an end user, not how easy the feature is to implement.
- **Ease:** ease of delivery; **High = easier**, **Medium = moderate**, **Low = harder**.
- **Priority:** the owner's chosen work priority, independent of the other ratings. Medium rows
  are not a committed implementation sequence; a High importance rating does not override them.

| # | Feature | Source | Importance | Usability | Ease | Priority | Status |
|---|---|---|---|---|---|---|---|
| 1 | Production Rate-Limiting | v4 | High | Low | Medium | — | **CLOSED** — delivered; accepted residuals remain separate |
| 2 | New User Signup / Per-user Authentication | v4 | High | High | Medium–High | — | **CLOSED** — authentication delivered; personalization is row 10 |
| 3 | Observability & Application Insights | v4 Item A | Medium | Low | Medium | — | **CLOSED** — accepted historical delivery scope |
| 4 | **Asset Picker (curated universe)** | v4 Item B / Spec A–B1–B2 | High | High | Low | — | **CLOSED** — delivered, deployed and accepted; provider/corporate-action residuals are separate |
| 5 | Portfolio Analytics / Demo-critical UI | Later delivery / #320 | High | High | Medium | — | **CLOSED within recorded scope** — not a clean PASS or risk-ratio delivery |
| 6 | **Per-user Sharpe Ratio and Sortino Ratio with charts/graphs and AI-assisted guidance** | New R1 | High | High | Medium | **High** | **OPEN / DEFERRED — NOT STARTED** |
| 7 | More Intelligent FA/TA Chatbot | New R2 | High | High | Low | **Medium** | **OPEN / DEFERRED — NOT STARTED** |
| 8 | More Engaging Charts and Analysis | New R3 | Medium | High | Medium | **Medium** | **OPEN / DEFERRED — NOT STARTED** |
| 9 | Custom Asset & Portfolio Management | v4 Item C / retained goal | Medium | High | Low | **Medium** | **OPEN / UNSCHEDULED** |
| 10 | User Settings / Personalization | v4 / retained goal | Medium | Medium | Medium | **Medium** | **OPEN / UNSCHEDULED** |
| 11 | Multi-provider Market Data | Retained goal | High | Low | Low | **Medium** | **OPEN / UNSCHEDULED** |
| 12 | AI Service Contract Evolution | Retained goal | Low | Low | Medium | **Medium** | **OPEN / UNSCHEDULED** |
| 13 | Advanced Agent Workflows | Retained goal | Medium | High | Low | **Medium** | **OPEN / EXPLORATORY** |
| 14 | Database Least Privilege | Retained goal | High | Low | Medium | **Medium** | **OPEN — future hardening** |
| 15 | AWS Reactivation | Retained goal | Low | Low | Low | **Medium** | **PARKED** — not a current defect or ready rollback |

### Classification Assumptions — Quick Restart Notes

These notes explain the rough ratings without committing a design, budget or schedule. Rows
1–4 and 9–10 retain v4's ratings; row 5's ratings describe the delivered scope, not future
risk-ratio work. The classification consensus is recorded above; it does not authorize
implementation, publication or a delivery schedule.

| Row | Why this classification / assumption to revisit |
|---|---|
| 5 | Existing analytics and UI have strong user value; moderate delivery complexity within the already accepted scope. No additional implementation is implied. |
| 6 | High user value assumes ratios, explanatory charts/graphs and AI-assisted improvement guidance together. Medium ease assumes a bounded first version using current holdings, validated daily history, stated return conventions and general, explanatory guidance grounded in computed results. A retrospective current-holdings scenario must be labeled, not presented as actual historical return; accurate deposit/withdrawal and holdings-change accounting would be Low ease. Specific buy/sell or rebalancing recommendations are harder, overlap row 13 and need appropriate financial-advice safeguards; settle that distinction at design time. Reassess without silently dropping visuals or guidance. |
| 7 | Strong user-facing improvement; harder because FA needs fundamentals and TA needs suitable history, with grounded explanations, provenance and fallback behavior. |
| 8 | Visible engagement benefit; moderate ease for a small set of views using existing data. History-heavy or benchmark-dependent views would be harder. |
| 11 | Important for coverage/reliability, with mostly indirect user benefit; harder due to provider licensing, symbol/currency normalization, reconciliation and failover. |
| 12 | Low importance until a richer-analysis requirement justifies it; little immediate UI benefit and moderate ease for one bounded contract change. Do not assume an agent-platform migration is equally easy. |
| 13 | Potentially strong user value but exploratory importance; harder due to multi-step orchestration, trustworthy inputs and financial/operational guardrails. Assumes simulations, not live trade execution. |
| 14 | Important security hygiene with little visible UI value; moderate ease assuming scoped application roles and permission tests, not a wholesale identity redesign. |
| 15 | Low current benefit while Azure is the demo path; harder because the parked AWS environment needs identity, infrastructure, model-access and cost revalidation. |

Start with R1 when feature development resumes. Its data-history prerequisites still need
assessment. Sections 2–3 below preserve the brief scope and restart context for every open row;
the [engineering backlog](docs/todos/backlog/README.md) retains separate defect/proof priorities.
This matrix does not downgrade security/backlog work or turn accepted limitations into fixes.

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
11 fixed/completed closures, 2 superseded closures and 20 open directory items at `main@6e968c20`.
These 33 engineering directories are not the 15 roadmap matrix rows. Its separate
inline TODO inventory is not added to those counts. The dashboard retains accepted demo debt
and inconclusive/unverified cases, including model provenance/resolution and FX/fallback edges.

## 2. New Owner Requests — Deferred, Not Started

Owner-set priorities are **High for R1** and **Medium for R2/R3**. No target date,
implementation plan or detailed design is committed. Reassess
data sources, dependencies and product goals when the owner resumes the project.

### R1 — Per-user Sharpe Ratio and Sortino Ratio

Improve portfolio analytics with risk-adjusted performance measures for each user's portfolio:
Sharpe for total-volatility risk and Sortino for downside risk. **Charts/graphs and plain-language
interpretation are required parts of this feature, not optional work deferred to R3.** Standalone
ratio numbers do not satisfy the owner's request. **AI-assisted user guidance on how to improve
the ratios is also in scope**, alongside explaining what drives them; this belongs within R1,
not solely within the separate FA/TA chatbot request. Guidance should be grounded in portfolio
data, with assumptions and uncertainty disclosed, not promises of improved returns. Detailed
design is intentionally deferred until after the freeze. The visuals must help users understand the
returns and total-volatility/downside-risk context behind the ratios; exact chart choices remain
for later design. First revisit return-history
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
risk/return, allocation/concentration and interactive time ranges. This is additional exploration
beyond the explanatory charts/graphs already required by R1; it does not move those visuals out
of the High-priority ratio feature. Choose based on user value
and available data rather than adding charts for their own sake. Historical positions, cash
flows and benchmark alignment may be prerequisites; examples are not committed deliverables.

These are the same R1–R3 entries in [ROADMAP.md](ROADMAP.md), not duplicate engineering backlog
items or permission to start work. Formal risk-adjusted metrics are distinct from the former
portfolio advisor's generated risk score/concentration commentary; that unused implementation
was removed from source through #332, not replaced by Sharpe/Sortino analytics.

## 3. Existing Future Goals Retained, Not Silently Closed

| Goal | Priority | Restart context |
|---|---|---|
| User settings/personalization | **Medium** | **OPEN / UNSCHEDULED.** Settings remains a placeholder. Persisted identity exists; per-user preferences/base currency/risk tolerance need product/schema design. |
| Custom assets / expanded portfolio management | **Medium** | **OPEN / UNSCHEDULED.** Different from the delivered supported-catalog picker. Define ownership, valuation and provider/catalog coverage before accepting assets outside that universe. |
| Multi-provider market data | **Medium** | **OPEN / UNSCHEDULED.** Yahoo remains an unofficial provider dependency. Symbol repairs mitigate symptoms, not provider risk; revisit supported providers, licensing, cost, coverage and failover. |
| AI service contract evolution | **Medium** | **OPEN / UNSCHEDULED.** `insight-service` already exists. gRPC or a managed agent boundary is an optional evolution, not an undelivered service; justify it against richer analysis requirements. |
| Advanced agent workflows | **Medium** | **OPEN / EXPLORATORY.** Rebalancing simulations, streaming news/sentiment and tax-aware ideas remain larger future directions, not delivered autonomous financial advice. Reassess data, risk/jurisdiction and operational guardrails. |
| Database least privilege | **Medium** | **OPEN future hardening goal.** Revisit a scoped application role rather than owner credentials. No new role/credential inventory or migration was performed here. |
| AWS reactivation | **Medium** | **PARKED, not a current defect or ready rollback.** Source remains, but identity/resources/DNS/model access/cost and current-build compatibility require a new approved assessment. |

All retained goals have Medium roadmap priority; their relative implementation order is not
committed. Exact residuals—such as provider
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
