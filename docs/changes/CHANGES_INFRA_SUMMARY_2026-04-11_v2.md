# Infrastructure Changes — 2026-04-11 v2

## Seamless Output Bypass, Docker-based Terraform, IPv4 Proxy Fix

Continues from [v1](CHANGES_INFRA_SUMMARY_2026-04-11_v1.md).

---

## Changes Since v1

### Seamless Output Bypass (`is_local_dev`)

LocalStack Hobbyist does not include RDS or ElastiCache. Rather than removing the database module, we implemented a `count = 0` bypass:

- `modules/database/main.tf` — Added `count = var.is_local_dev ? 0 : 1` to both `aws_db_instance` and `aws_elasticache_cluster`
- `modules/database/variables.tf` — Added `is_local_dev` flag plus `local_postgres_host/port` and `local_redis_host/port` for configurability
- `modules/database/outputs.tf` — Conditional outputs: `localhost:5432` / `localhost:6379` when local, AWS resource attributes when not
- `modules/database/README.md` — Documents the bypass pattern
- `main.tf` (root) — Passes `is_local_dev = var.use_localstack` to the database module, piggybacking on the existing toggle

### S3 Path-Style Fix

- `main.tf` — Added `s3_use_path_style = var.use_localstack` to the AWS provider block. Fixes virtual-hosted-style bucket DNS failures (`bucket.localstack:4566`) inside Docker networks.

### Docker-based Terraform Workflow

- `backend-localstack.hcl` — New partial S3 backend config pointing to LocalStack. Uses `use_lockfile = true` (S3-native locking) instead of DynamoDB table.
- `variables.tf` — Added `localstack_endpoint` variable (default `http://localhost:4566`, overridable to `http://localstack:4566` for Docker networking)

### LocalStack Auth Token

- `docker-compose.localstack.yml` — Added `LOCALSTACK_AUTH_TOKEN: ${LOCALSTACK_AUTH_TOKEN:?...}` and `sqs` to SERVICES list
- `.env` (root) — Created with `LOCALSTACK_AUTH_TOKEN` placeholder (gitignored)

### IPv4 Proxy Fix

Node.js 18+ resolves `localhost` to IPv6 `::1`, which fails when the Spring Boot gateway only binds IPv4. Changed all proxy targets to explicit `127.0.0.1`:

- `frontend/next.config.ts` — Rewrite destination `http://localhost:8080` → `http://127.0.0.1:8080`
- `frontend/.env.local` — `NEXT_PUBLIC_API_BASE_URL` and `API_PROXY_TARGET` → `http://127.0.0.1:8080`

### Terraform Apply Results

| Resource                                               | Status                                |
| ------------------------------------------------------ | ------------------------------------- |
| S3 artifacts bucket + versioning + public access block | ✅ Created                            |
| IAM roles + policies (from prior apply)                | ✅ In state                           |
| Lambda functions                                       | ❌ Blocked (shared layers = Pro)      |
| CloudFront                                             | ❌ Blocked (depends on Lambda URLs)   |
| RDS + ElastiCache                                      | ✅ Skipped by design (`is_local_dev`) |
| Database outputs                                       | ✅ `localhost:5432` / `localhost`     |

### .gitignore Updates

- Added `infrastructure/terraform/.terraform/` and `terraform.tfstate*`
- Added `frontend/test-results/`

---

## Files Changed (Since v1)

| File                                                     | Change                                          |
| -------------------------------------------------------- | ----------------------------------------------- |
| `infrastructure/terraform/modules/database/main.tf`      | `count = 0` bypass                              |
| `infrastructure/terraform/modules/database/variables.tf` | `is_local_dev` + local endpoint vars            |
| `infrastructure/terraform/modules/database/outputs.tf`   | Conditional Docker/AWS outputs                  |
| `infrastructure/terraform/modules/database/README.md`    | Bypass documentation                            |
| `infrastructure/terraform/main.tf`                       | `is_local_dev` passthrough, `s3_use_path_style` |
| `infrastructure/terraform/backend-localstack.hcl`        | New — LocalStack S3 backend config              |
| `docker-compose.localstack.yml`                          | Auth token, `sqs` service                       |
| `.env`                                                   | New — LocalStack auth token placeholder         |
| `frontend/next.config.ts`                                | IPv4 proxy fix                                  |
| `frontend/.env.local`                                    | IPv4 proxy fix                                  |
| `.gitignore`                                             | Terraform + test-results exclusions             |
