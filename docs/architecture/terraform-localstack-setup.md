# Terraform + LocalStack Setup Guide

**Date:** 2026-04-11
**Status:** Active

---

## Overview

Terraform runs inside a Docker container (`hashicorp/terraform:latest`) against LocalStack for local AWS emulation. No local Terraform CLI installation is required.

---

## Prerequisites

- Docker Desktop running
- LocalStack auth token in `.env` at project root (gitignored)
- LocalStack container healthy on `wealthmgmtandportfoliotracker_default` network

---

## Quick Start

### 1. Configure auth token

Edit `.env` in the project root:

```
LOCALSTACK_AUTH_TOKEN=ls-your-actual-token-here
```

### 2. Start LocalStack

```bash
docker compose -f docker-compose.localstack.yml up -d
```

Verify it's healthy:

```bash
docker ps --filter "name=localstack" --format "table {{.Names}}\t{{.Status}}"
```

### 3. Create the S3 state bucket (first time only)

```bash
docker run --rm --network wealthmgmtandportfoliotracker_default \
  -e AWS_ACCESS_KEY_ID=test \
  -e AWS_SECRET_ACCESS_KEY=test \
  -e AWS_DEFAULT_REGION=us-east-1 \
  amazon/aws-cli:latest \
  --endpoint-url http://localstack:4566 s3 mb s3://wealth-tf-state-local
```

### 4. Run Terraform (init + plan)

```bash
docker run --rm \
  -v "D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker/infrastructure/terraform:/workspace" \
  -w /workspace \
  --network wealthmgmtandportfoliotracker_default \
  --entrypoint sh hashicorp/terraform:latest \
  -c "rm -rf .terraform && terraform init -reconfigure -backend-config=backend-localstack.hcl && terraform plan -var-file=localstack.tfvars -var=localstack_endpoint=http://localstack:4566"
```

### 5. Apply (when ready)

Same as above but replace `terraform plan` with `terraform apply -auto-approve`:

```bash
docker run --rm \
  -v "D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker/infrastructure/terraform:/workspace" \
  -w /workspace \
  --network wealthmgmtandportfoliotracker_default \
  --entrypoint sh hashicorp/terraform:latest \
  -c "rm -rf .terraform && terraform init -reconfigure -backend-config=backend-localstack.hcl && terraform apply -var-file=localstack.tfvars -var=localstack_endpoint=http://localstack:4566 -auto-approve"
```

---

## Key Files

| File                                              | Purpose                                                |
| ------------------------------------------------- | ------------------------------------------------------ |
| `docker-compose.localstack.yml`                   | LocalStack container with SERVICES list and auth token |
| `.env`                                            | `LOCALSTACK_AUTH_TOKEN` (gitignored, never committed)  |
| `infrastructure/terraform/backend-localstack.hcl` | S3 backend config pointing to LocalStack               |
| `infrastructure/terraform/localstack.tfvars`      | Variable values for local testing                      |

---

## Known Issues & Solutions

### 1. "Backend type changed from local to s3"

**Cause:** Stale `.terraform/` directory from a previous init with a different backend config (e.g., `-backend=false` creates a local backend, then subsequent runs see the S3 backend in `main.tf`).

**Fix:** Always include `rm -rf .terraform` before `terraform init` in the Docker command, or delete it from the host:

```powershell
Remove-Item -Recurse -Force infrastructure/terraform/.terraform
```

**Root cause detail:** `terraform init -backend=false` tells Terraform to skip backend configuration entirely. It caches this decision in `.terraform/terraform.tfstate` (a metadata file, not your actual state). When you later run `terraform plan`, Terraform reads the cached backend type ("local"/none) but sees `backend "s3"` in `main.tf` — mismatch triggers the error. The `-reconfigure` flag with `backend-localstack.hcl` avoids this by properly configuring the S3 backend against LocalStack.

**Why `-backend=false` doesn't work for plan/apply:** It's designed for `terraform validate` only. For any operation that reads or writes state (plan, apply, destroy), the backend must be fully initialized.

### 2. "S3 bucket does not exist"

**Cause:** The S3 state bucket hasn't been created in LocalStack yet. LocalStack containers are ephemeral — the bucket is lost when the container is recreated.

**Fix:** Create the bucket before running Terraform (see Quick Start step 3). This must be repeated each time LocalStack is recreated from scratch.

### 3. "DynamoDB table not found" (state locking)

**Cause:** The backend config referenced a `dynamodb_table` for state locking, but the table doesn't exist in LocalStack.

**Fix:** Use `use_lockfile = true` instead of `dynamodb_table` in `backend-localstack.hcl`. This uses S3-native file locking instead of DynamoDB, eliminating the dependency.

### 4. Volume mount path errors on Windows

**Cause:** `${PWD}` in PowerShell doesn't expand correctly inside Docker `-v` flags. Docker on Windows requires forward slashes.

**Fix:** Use the absolute path with forward slashes:

```bash
-v "D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker/infrastructure/terraform:/workspace"
```

Do NOT use `${PWD}` or `$(pwd)` in PowerShell for Docker volume mounts.

### 5. "dial tcp: lookup localstack: no such host"

**Cause:** The Terraform container isn't on the same Docker network as LocalStack.

**Fix:** Ensure `--network wealthmgmtandportfoliotracker_default` is in the `docker run` command. Verify the network name:

```bash
docker network ls --format "{{.Name}}" | Select-String "wealth"
```

### 6. Lock file checksum mismatch

**Cause:** `.terraform.lock.hcl` was generated on Windows (amd64) but the Terraform Docker image runs on Linux. Different platform checksums.

**Fix:** Run init with `-upgrade` once to regenerate the lock file with Linux checksums:

```bash
terraform init -reconfigure -backend-config=backend-localstack.hcl -upgrade
```

### 7. LocalStack exits with code 55

**Cause:** Missing or invalid `LOCALSTACK_AUTH_TOKEN`. As of LocalStack 2026.3.x, all usage requires an auth token.

**Fix:** Ensure `.env` contains a valid token and LocalStack reads it:

```bash
docker logs wealthmgmtandportfoliotracker-localstack-1 --tail 10
```

---

## Architecture Notes

- The `localstack_endpoint` variable in `variables.tf` defaults to `http://localhost:4566` (for native Terraform CLI). When running Terraform inside Docker, override it with `-var=localstack_endpoint=http://localstack:4566` so the provider reaches LocalStack via Docker DNS.
- The `backend-localstack.hcl` file is separate from `localstack.tfvars` because backend config and provider config are initialized at different stages of the Terraform lifecycle.
- Spring Boot services continue using Docker postgres + redis containers for local dev. The Terraform database module (RDS + ElastiCache) is for AWS deployment validation.
