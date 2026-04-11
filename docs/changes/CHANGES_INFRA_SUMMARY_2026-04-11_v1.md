# Infrastructure Changes — LocalStack + Terraform Database Module

**Date:** 2026-04-11
**Scope:** LocalStack service expansion, Terraform database module, Docker-based Terraform workflow, Seamless Output Bypass hack

---

## Overview

Added a Terraform `database` module for RDS PostgreSQL and ElastiCache Redis, expanded LocalStack services, established a Docker-based Terraform workflow (no local CLI install), and implemented a `is_local_dev` bypass that skips paid AWS resource creation while outputting Docker container endpoints. The result is a hybrid local dev environment where Terraform manages the infrastructure definition and Spring Boot services connect to vanilla Docker containers.

---

## New Files

### Database Module (`infrastructure/terraform/modules/database/`)

- `main.tf` — `aws_db_instance` (Postgres 16, `db.t3.micro`, `skip_final_snapshot = true`) and `aws_elasticache_cluster` (Redis 7.0, `cache.t3.micro`, single node). Both gated by `count = var.is_local_dev ? 0 : 1`.
- `variables.tf` — `is_local_dev`, `project_name`, `db_username`, `db_password` (sensitive), `rds_instance_class`, `rds_allocated_storage`, `elasticache_node_type`, `local_postgres_host/port`, `local_redis_host/port`.
- `outputs.tf` — Conditional outputs: when `is_local_dev = true`, returns hardcoded Docker endpoints (`localhost:5432`, `localhost:6379`); otherwise reads from `[0]`-indexed AWS resources.
- `README.md` — Documents the bypass pattern, all inputs, and all outputs.

### Backend Config

- `infrastructure/terraform/backend-localstack.hcl` — Partial S3 backend config pointing to LocalStack. Uses `use_lockfile = true` (S3-native locking) instead of DynamoDB table.

### Root-Level

- `.env` — `LOCALSTACK_AUTH_TOKEN` placeholder (gitignored). Required by LocalStack 2026.3.x+.

### Documentation

- `docs/architecture/terraform-localstack-setup.md` — Full setup guide: prerequisites, quick start, known issues with root causes and fixes (backend mismatch, missing S3 bucket, DynamoDB lock table, Windows volume paths, Docker network DNS, lock file checksums, auth token requirement).
- `docs/architecture/phase3-app-wiring-status.md` — Phase 3 status record documenting the LocalStack license limitation and confirming Spring Boot services remain on Docker containers.

---

## Modified Files

### `docker-compose.localstack.yml`

- Added `LOCALSTACK_AUTH_TOKEN: ${LOCALSTACK_AUTH_TOKEN:?...}` (reads from `.env`)
- Expanded `SERVICES` to include `rds,elasticache,sqs`

### `infrastructure/terraform/versions.tf`

- Fixed provider constraint from `>= 5.0` (open-ended) to `~> 5.0` (pessimistic)

### `infrastructure/terraform/main.tf`

- Extracted `localstack_endpoint` from hardcoded local to `var.localstack_endpoint` (configurable for Docker-based Terraform)
- Added `s3_use_path_style = var.use_localstack` to provider block (fixes S3 virtual-hosted-style DNS failure in LocalStack)
- Added `rds` and `elasticache` to the dynamic `endpoints` block
- Wired `module "database"` with `is_local_dev = var.use_localstack` (piggybacks on existing toggle)

### `infrastructure/terraform/variables.tf`

- Added `localstack_endpoint` (default `http://localhost:4566`, overridable for Docker networking)
- Added `db_username`, `db_password` (sensitive), `rds_instance_class`, `rds_allocated_storage`, `elasticache_node_type`

### `infrastructure/terraform/outputs.tf`

- Added `rds_endpoint` and `elasticache_endpoint` outputs from database module

### `infrastructure/terraform/localstack.tfvars`

- Added database module stubs: `db_username`, `db_password`, `rds_instance_class`, `rds_allocated_storage`, `elasticache_node_type`

---

## Key Design Decisions

**Seamless Output Bypass** — LocalStack Hobbyist does not include RDS or ElastiCache. Rather than removing the database module, `is_local_dev = true` sets `count = 0` on AWS resources and outputs hardcoded Docker endpoints. Consumers always read `module.database.rds_endpoint` regardless of environment. When deploying to real AWS, `is_local_dev = false` provisions actual resources.

**Piggybacking `is_local_dev` on `use_localstack`** — No separate root variable. The root module passes `is_local_dev = var.use_localstack` to the database module, preventing configuration drift and keeping the `.tfvars` interface clean.

**Docker-based Terraform workflow** — `hashicorp/terraform:latest` runs on the same Docker network as LocalStack (`wealthmgmtandportfoliotracker_default`), reaching it via `http://localstack:4566`. No local Terraform CLI installation required. The `localstack_endpoint` variable allows overriding `localhost` with the Docker service name.

**S3 path-style fix** — `s3_use_path_style = true` prevents the AWS provider from using virtual-hosted-style bucket URLs (`bucket.localstack:4566`) which fail DNS resolution inside Docker networks.

**S3-native locking** — `backend-localstack.hcl` uses `use_lockfile = true` instead of `dynamodb_table`, eliminating the need to pre-create a DynamoDB lock table in LocalStack.

---

## Terraform Apply Results (LocalStack)

| Resource Category                                        | Count | Status                                                             |
| -------------------------------------------------------- | ----- | ------------------------------------------------------------------ |
| S3 (artifacts bucket + versioning + public access block) | 3     | ✅ Created                                                         |
| IAM (roles + policy attachments + inline policies)       | 9     | ✅ Created (from prior apply, in state)                            |
| Lambda (functions + aliases + URLs)                      | 12    | ❌ Blocked — shared layers are Pro-tier                            |
| CloudFront                                               | 1     | ❌ Blocked — depends on Lambda function URLs                       |
| RDS + ElastiCache                                        | 0     | ✅ Skipped by design (`is_local_dev = true`)                       |
| Database outputs                                         | 2     | ✅ `rds_endpoint=localhost:5432`, `elasticache_endpoint=localhost` |

---

## E2E Verification

| Check                                                  | Result                                                                                 |
| ------------------------------------------------------ | -------------------------------------------------------------------------------------- |
| Flyway migrations against `localhost:5432`             | ✅ Schema validated, portfolio-service started                                         |
| Redis connection at `localhost:6379`                   | ✅ api-gateway started, rate limiter loaded                                            |
| Kafka consumers (`market-prices`, `market-prices.DLT`) | ✅ Connected                                                                           |
| Playwright E2E suite                                   | 8/10 passed; 2 failures are pre-existing UI issue (`total-value` test ID not rendered) |

---

## Related Documentation

- [LocalStack + Terraform Migration Proposal](../architecture/localstack-terraform-migration-proposal.md) — Original proposal with architecture review and migration phases
- [Terraform + LocalStack Setup Guide](../architecture/terraform-localstack-setup.md) — Troubleshooting guide for all known init/apply issues
- [Phase 3 App Wiring Status](../architecture/phase3-app-wiring-status.md) — Decision record confirming Docker containers for local dev
