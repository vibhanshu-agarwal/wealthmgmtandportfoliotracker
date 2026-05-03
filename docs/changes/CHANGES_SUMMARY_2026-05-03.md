# Documentation & Terraform Refinement Summary — 2026-05-03

**Previous revision:** [CHANGES_CACHE_WARMING_2026-04-30.md](./CHANGES_CACHE_WARMING_2026-04-30.md) — Cost audit remediation (synthetic monitoring disabled, warming parked in CI).

**Commit range:** `cd6be1f` → `d04a1a7` (14 commits, all on 2026-04-30, after the cache-warming changelog)

---

## Summary

A documentation-only sweep covering the entire project: architecture docs, roadmap, E2E service flows, integration test strategy, risk mitigation plans, and Terraform module comments. No application code, infrastructure resources, CI pipelines, or runtime behaviour changed.

The goal was to bring written documentation in line with the current v2 architecture — Terraform serverless deployment (Lambda/Graviton2, CloudFront, API Gateway), Redis-backed rate limiting, Kafka DLT, and AWS Bedrock AI insights — after the preceding implementation phases.

---

## 1. Terraform Module Documentation

| Commit | File(s) | Change |
|---|---|---|
| `cd6be1f` | `CoreArchitecturalGuardrails_v2.md` | Clarified `schedule_cron` variable description (supports both `rate()` and `cron()` expressions); corrected `aws_account_id` description to reflect its IAM trust-policy role |
| `5754d10` | `modules/warming/main.tf`, `modules/warming/variables.tf` | Added inline comments explaining backward-compatibility rationale for the `aws_iam_role.scheduler` resource name and `schedule_cron` variable name |

---

## 2. Project-Level Documentation

| Commit | File(s) | Change |
|---|---|---|
| `e81d309` | `README.md` | Expanded project overview with Terraform serverless architecture, CloudFront/Lambda topology, and module details |
| `2a697b3` | `ROADMAP_AI_POWERED_WEALTH_TRACKER.md` | Updated roadmap to document completed phases (serverless migration, Redis rate limiting, Kafka DLT, Bedrock AI insights, Next.js frontend); deferred "Architect Demo" as optional |
| `852fbfa` | `ROADMAP.md` | Clarified Bedrock integration as implemented (not planned); documented Lambda deployment and `@Profile("bedrock")` configuration |
| `6441521` | `ROADMAP.md` | Added production rate-limiting strategy for AWS profile and multi-provider market data aggregation roadmap |

---

## 3. Architecture & Risk Documentation

| Commit | File(s) | Change |
|---|---|---|
| `afc228c` | `WealthManagementArchitectureDocumentation_v2.md` | Updated tech stack (React 19, Next.js 16, Spring multi-module Gradle), API Gateway logic, Kafka DLT, AI insights, Redis caching, and CI/CD workflows |
| `3cd7db0` | `RiskMitigationPlan.md`, `WealthManagementArchitectureOverview_v2.md` | Refined risk documentation for Terraform serverless infra, Lambda cold-start mitigation, Redis rate limiting, and Kafka DLT |
| `07f5b10` | `IntegrationTestCases.md` | Expanded integration test strategy with CI workflow references, Redis/Kafka/contract testing details, and Playwright E2E coverage for local and live environments |
| `3cfc140` | `lambda-stopgap-execution-plan.md`, `lambda-vs-lightsail-analysis.md` | Added historical context notes on the EventBridge Scheduler → Rules + API Destinations pivot, with links to RCA documents |

---

## 4. E2E Service Flow Documentation

All four service E2E flow documents were updated with consistent improvements: clarified local vs production API routing paths, CloudFront origin behaviour rules, Spring Cloud Gateway routing configurations, and updated flow diagrams.

| Commit | File | Service |
|---|---|---|
| `c31fd8b` | `api-gateway-service-e2e.md` | API Gateway — Lambda deployment config, CloudFront origin protection, cold-start mitigation |
| `c31fd8b` | `insight-service-e2e.md` | Insight Service — initial production topology additions |
| `c31fd8b` | `market-data-service-e2e.md` | Market Data — initial production topology additions |
| `c31fd8b` | `portfolio-service-e2e.md` | Portfolio Service — initial production topology additions |
| `14a4b3e` | `portfolio-service-e2e.md` | Portfolio Service — expanded API call paths, local/production routing, CloudFront behaviour |
| `49fa79a` | `market-data-service-e2e.md` | Market Data — expanded API paths, static export behaviour, CloudFront origin rules |
| `d04a1a7` | `insight-service-e2e.md` | Insight Service — expanded API routing, static export setup, authentication flow |

---

## Impact

- **Runtime:** None — no application code, infrastructure, or CI pipeline changes
- **Documentation accuracy:** All architecture, roadmap, E2E flow, and test strategy docs now reflect the current deployed state
- **Terraform:** Comment-only changes in the warming module for maintainability; no resource or variable behaviour changes

---

## Commits

| SHA | Message |
|---|---|
| `cd6be1f` | `terraform(warming): clarify schedule_cron description and improve IAM trust policy docs` |
| `e81d309` | `docs: update README with Terraform serverless architecture and module details` |
| `2a697b3` | `docs: update roadmap with implemented architecture, AI integration, and future plans` |
| `afc228c` | `docs: update Wealth Management Architecture doc with detailed v2 updates` |
| `3cd7db0` | `docs: update Architecture & Risk Mitigation Plans for Terraform migration and v2 enhancements` |
| `07f5b10` | `docs: expand Integration Test Cases with detailed pipelines, strategies, and workflows` |
| `c31fd8b` | `docs: update E2E flows with production deployment topology and routing details` |
| `3cfc140` | `docs: add historical context on EventBridge Scheduler pivot for cache warming` |
| `5754d10` | `docs: add naming rationale for IAM role and schedule_cron variable in cache-warming module` |
| `852fbfa` | `docs: update roadmap with AWS Bedrock integration details` |
| `6441521` | `docs: update roadmap with production rate limiting strategy and provider aggregation details` |
| `14a4b3e` | `docs: refine portfolio service E2E flow with updated API routing and deployment details` |
| `49fa79a` | `docs: revise market-data E2E flow with updated API routing, CloudFront logic, and production safeguards` |
| `d04a1a7` | `docs: update insight-service E2E flow with clarified API routing and CloudFront behavior` |
