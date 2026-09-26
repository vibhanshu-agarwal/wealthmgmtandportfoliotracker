# Wealth Management & Portfolio Tracker — Architecture Overview (v2)

**Reconciled:** 2026-09-26 UTC against `main@8aa4035b`. This replaces the April AWS-only
description as the current overview. It is a source/accepted-evidence description, not a fresh
cloud read or availability claim. The [demo dashboard](../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md)
retains runtime acceptance and known limitations.

## Purpose and stack

A portfolio-demo application with a static Next.js frontend and four Spring Boot services.
It demonstrates catalog-backed holdings editing, valuations, historical analytics, stored
market data and ticker-oriented AI chat. It is not a trading/execution platform or a complete
financial-advice system.

[frontend/package.json](../../frontend/package.json) pins Next.js 16.2.3 and declares React
19.2.x; [next.config.ts](../../frontend/next.config.ts) uses static export, without a runtime
Next.js API proxy. [build.gradle](../../build.gradle) selects Java **21**, Spring Boot **4.1.0**,
Spring Cloud 2025.1.2 and Spring AI 2.0.0. Earlier Java 25 descriptions are historical.
The gateway is WebFlux; the domain HTTP services use Spring MVC.

## Modules and ownership

[settings.gradle](../../settings.gradle) declares seven Gradle modules, not five:

| Module | Responsibility |
|---|---|
| `api-gateway` | Signup/login, HS256 JWT, routing, user-header injection, distributed rate limits and guarded demo writes |
| `portfolio-service` | PostgreSQL holdings/version transactions, market-price projection/history, USD analytics and FX conversion |
| `market-data-service` | MongoDB stored prices, Yahoo provider adapter, refresh runner and Kafka price publication |
| `insight-service` | Redis market cache, stored-window summaries, ticker resolution and configured sentiment/chat adapters |
| `common-dto` | Shared event/wire contracts and truststore extraction utilities |
| `common-catalog` | Shared catalog model/loading/validation |
| `common-observability` | Shared observation/trace sanitization and route templating |

The frontend is a separate Node project, not an eighth Gradle module.

## Accepted demo target versus retained alternatives

The accepted demo target is **Azure Static Web Apps + Azure Container Apps**, provisioned from
[Azure Terraform](../../infrastructure/terraform/azure/main.tf). The frontend and API use
separate origins. Only the gateway has external service ingress; the three downstream service
ingresses are internal to the Container Apps environment. Internal ingress is not proof of
per-endpoint caller authorization.

A separate Container Apps Job runs market refresh at `0 8 * * *` (08:00 UTC); the market API
does not rely on a timer while scaled to zero. Azure OpenAI is the selected AI integration.
External PostgreSQL, MongoDB, Kafka and Redis connections are injected, not provisioned as
RDS/ElastiCache by the Azure stack.

AWS Lambda/CloudFront Terraform and Lambda Web Adapter Dockerfiles remain in the repo.
They are an alternative/historical path, **not an established ready rollback**. Local Docker
Compose runs four services with PostgreSQL, MongoDB, Kafka and Redis; LocalStack is optional
legacy infrastructure experimentation, not a prerequisite for normal application E2E.

## Request and event boundaries

Browser HTTP calls go through the gateway. Signup creates credentials and an empty portfolio.
Holdings replacement is a complete desired-set transaction with an expected version; conflicts
are rejected rather than silently retried. Market reads serve MongoDB data, not live Yahoo calls.

The refresh runner and approved manual market writes publish `PriceUpdatedEvent` on
`market-prices`, keyed by ticker. Portfolio projects prices/history to PostgreSQL; insight
maintains Redis observations and summaries. These stores are eventually consistent. There is
no established MongoDB-to-Kafka transactional outbox or cross-store exactly-once guarantee.

Bulk insights do not invoke AI per ticker. Chat combines stored market facts with optional
model sentiment; its source label can describe cached output and is not proof of a new model
call. The separate path-ID portfolio advisor is not the ticker-chat pipeline and has a
source-confirmed [cross-user authorization flaw](../todos/backlog/portfolio-advisor-cross-user-authorization/README.md).
Azure source omits its portfolio URL, so reachability is expected to fail absent another override;
that is unverified live and is not an authorization control. The defect remains OPEN.

## Read next

- [Detailed architecture](WealthManagementArchitectureDocumentation_v2.md) and [diagram source](architecture.puml).
- [Service E2E guides](../e2e-flows/) for request/event details and caveats.
- [Current operations](../runbooks/CURRENT_OPERATIONS.md), [test inventory](IntegrationTestCases.md)
  and [risk register](RiskMitigationPlan.md).
- [Roadmap v5](../../roadmap_enhancements_v5.md) for deferred per-user Sharpe/Sortino, richer
  FA/TA chat and more engaging charts; none is delivered by this documentation update.
