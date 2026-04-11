# LocalStack + Terraform Migration Proposal

**Date:** 2026-04-11
**Status:** Proposal — awaiting review
**Author:** Kiro

---

## Top 3 Architectural Rules from the Terraform Skill

1. **Pin versions with pessimistic constraints (`~>`)** — Use `~> 5.0` or `~> 6.0` for providers and exact versions for modules. Never use open-ended `>=` without an upper bound. The current `versions.tf` uses `>= 5.0` which violates this rule.

2. **Standard file structure** — `main.tf` for resources, `variables.tf` for inputs (alphabetical), `outputs.tf` for outputs, `versions.tf` for version constraints. Once `main.tf` exceeds 15 resources, split into domain-specific files (e.g., `rds.tf`, `elasticache.tf`). The existing structure follows this correctly.

3. **Never hardcode secrets** — Use `sensitive = true` on variables, access secrets from external secret management, use environment variables for provider credentials. The existing `localstack.tfvars` correctly marks connection strings as sensitive in `variables.tf` and uses stub values for local testing.

---

## Current Infrastructure Review

### Docker Compose (docker-compose.yml)

| Service  | Image          | Port      | Purpose                       |
| -------- | -------------- | --------- | ----------------------------- |
| postgres | postgres:16    | 5432      | Portfolio DB (Flyway-managed) |
| mongodb  | mongo:7.0      | 27017     | Market data store             |
| kafka    | cp-kafka:7.6.0 | 9092/9094 | Event streaming               |
| redis    | redis:alpine   | 6379      | API Gateway rate limiting     |

### LocalStack Overlay (docker-compose.localstack.yml)

Currently emulates: `lambda, s3, dynamodb, cloudfront, iam, acm, route53`

Missing: `rds, elasticache` — these are the services we need to add.

### Existing Terraform (infrastructure/terraform/)

The Terraform config is well-structured with:

- A `use_localstack` toggle that conditionally configures the AWS provider endpoints
- Modules for `compute` (Lambda functions) and `networking` (CloudFront + Route 53)
- An S3 artifacts bucket for Lambda JARs
- A `localstack.tfvars` for local testing

**Issues identified:**

1. **Version constraint too loose** — `versions.tf` uses `>= 5.0` instead of `~> 5.0` or `~> 6.0`
2. **Missing RDS and ElastiCache endpoints** — The provider's `endpoints` block only lists `lambda, s3, dynamodb, cloudfront, iam, acm, route53`. RDS and ElastiCache endpoints are not configured for LocalStack.
3. **No database module** — Postgres and Redis are Docker containers, not Terraform-managed. There's no `database` module in `infrastructure/terraform/modules/`.
4. **LocalStack services list incomplete** — `docker-compose.localstack.yml` doesn't include `rds` or `elasticache` in the `SERVICES` env var.

---

## Proposed Architectural Changes

### 1. Update LocalStack Services

Add `rds` and `elasticache` to the `SERVICES` list in `docker-compose.localstack.yml`:

```yaml
services:
  localstack:
    image: localstack/localstack:latest
    ports:
      - "4566:4566"
    environment:
      SERVICES: lambda,s3,dynamodb,cloudfront,iam,acm,route53,rds,elasticache
      DEFAULT_REGION: us-east-1
      LAMBDA_EXECUTOR: local
    volumes:
      - "/var/run/docker.sock:/var/run/docker.sock"
```

### 2. Add RDS and ElastiCache Endpoints to Provider

Update `main.tf` to include `rds` and `elasticache` in the dynamic endpoints block:

```hcl
dynamic "endpoints" {
  for_each = var.use_localstack ? [1] : []
  content {
    lambda       = local.localstack_endpoint
    s3           = local.localstack_endpoint
    dynamodb     = local.localstack_endpoint
    cloudfront   = local.localstack_endpoint
    iam          = local.localstack_endpoint
    acm          = local.localstack_endpoint
    route53      = local.localstack_endpoint
    rds          = local.localstack_endpoint  # NEW
    elasticache  = local.localstack_endpoint  # NEW
  }
}
```

### 3. Fix Version Constraint

Update `versions.tf`:

```hcl
terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"  # Pessimistic constraint per best practices
    }
  }
}
```

### 4. Create Database Module

Create `infrastructure/terraform/modules/database/` with:

```
modules/database/
├── main.tf          # RDS instance + ElastiCache cluster
├── variables.tf     # Inputs
├── outputs.tf       # Connection strings
└── README.md        # Docs
```

**`modules/database/main.tf`** — Key resources:

```hcl
# RDS PostgreSQL instance (replaces Docker postgres container)
resource "aws_db_instance" "portfolio" {
  identifier           = "${var.project_name}-portfolio-db"
  engine               = "postgres"
  engine_version       = "16"
  instance_class       = var.rds_instance_class
  allocated_storage    = var.rds_allocated_storage
  db_name              = "portfolio_db"
  username             = var.db_username
  password             = var.db_password
  skip_final_snapshot  = true  # LocalStack doesn't support snapshots

  tags = local.common_tags
}

# ElastiCache Redis cluster (replaces Docker redis container)
resource "aws_elasticache_cluster" "gateway_cache" {
  cluster_id           = "${var.project_name}-gateway-cache"
  engine               = "redis"
  engine_version       = "7.0"
  node_type            = var.elasticache_node_type
  num_cache_nodes      = 1
  port                 = 6379

  tags = local.common_tags
}
```

### 5. Wire Database Module into Root

Add to `main.tf`:

```hcl
module "database" {
  source = "./modules/database"

  project_name         = "wealth"
  db_username          = var.db_username
  db_password          = var.db_password
  rds_instance_class   = var.use_localstack ? "db.t3.micro" : "db.t3.micro"
  elasticache_node_type = var.use_localstack ? "cache.t3.micro" : "cache.t3.micro"
}
```

### 6. Update localstack.tfvars

Add new variables:

```hcl
db_username = "wealth_user"
db_password = "wealth_pass"
```

### 7. Update Spring Boot Connection Strings

After `terraform apply`, the Spring Boot services need to connect to the LocalStack-managed RDS and ElastiCache instead of the Docker containers. The connection strings change from:

| Service           | Current (Docker)                                | New (LocalStack RDS)                                                        |
| ----------------- | ----------------------------------------------- | --------------------------------------------------------------------------- |
| portfolio-service | `jdbc:postgresql://localhost:5432/portfolio_db` | `jdbc:postgresql://localhost:4510/portfolio_db` (LocalStack RDS proxy port) |
| api-gateway       | `redis://localhost:6379`                        | `redis://localhost:4510` (LocalStack ElastiCache proxy port)                |

Note: LocalStack's RDS emulation uses a different port. The exact port depends on the LocalStack version and configuration. The Terraform output should expose the connection endpoint.

---

## Migration Strategy

### Phase 1: Infrastructure Code (no runtime changes)

1. Update `docker-compose.localstack.yml` with `rds,elasticache` services
2. Fix `versions.tf` version constraint
3. Add `rds` and `elasticache` endpoints to provider block
4. Create `modules/database/` with RDS + ElastiCache resources
5. Wire module into root `main.tf`
6. Add new variables to `variables.tf` and `localstack.tfvars`

### Phase 2: Validate with LocalStack

1. `docker compose -f docker-compose.yml -f docker-compose.localstack.yml up -d localstack`
2. `cd infrastructure/terraform && terraform init`
3. `terraform plan -var-file=localstack.tfvars` — verify the plan creates RDS + ElastiCache
4. `terraform apply -var-file=localstack.tfvars` — apply against LocalStack
5. Verify resources exist: `aws --endpoint-url=http://localhost:4566 rds describe-db-instances`

### Phase 3: Application Wiring

1. Update Spring Boot `application-local.yml` to use Terraform output connection strings
2. Run Flyway migrations against the LocalStack RDS instance
3. Verify portfolio-service connects to LocalStack RDS
4. Verify api-gateway connects to LocalStack ElastiCache

### Phase 4: Remove Docker Containers

1. Remove `postgres` and `redis` services from `docker-compose.yml`
2. Update all documentation referencing `docker compose up -d postgres redis`
3. Keep `mongodb` and `kafka` as Docker containers (no AWS equivalent in current scope)

---

## Files to Create/Modify

| File                                                     | Action                                                |
| -------------------------------------------------------- | ----------------------------------------------------- |
| `docker-compose.localstack.yml`                          | Add `rds,elasticache` to SERVICES                     |
| `infrastructure/terraform/versions.tf`                   | Fix version constraint to `~> 5.0`                    |
| `infrastructure/terraform/main.tf`                       | Add `rds`, `elasticache` endpoints + database module  |
| `infrastructure/terraform/variables.tf`                  | Add `db_username`, `db_password`, instance class vars |
| `infrastructure/terraform/outputs.tf`                    | Add RDS endpoint, ElastiCache endpoint outputs        |
| `infrastructure/terraform/localstack.tfvars`             | Add DB credential stubs                               |
| `infrastructure/terraform/modules/database/main.tf`      | NEW — RDS + ElastiCache resources                     |
| `infrastructure/terraform/modules/database/variables.tf` | NEW — Module inputs                                   |
| `infrastructure/terraform/modules/database/outputs.tf`   | NEW — Connection strings                              |
| `infrastructure/terraform/modules/database/README.md`    | NEW — Module docs                                     |

---

## Risks and Mitigations

| Risk                                                             | Mitigation                                                                 |
| ---------------------------------------------------------------- | -------------------------------------------------------------------------- |
| LocalStack RDS emulation is incomplete                           | Use `skip_final_snapshot = true`, avoid advanced RDS features              |
| Flyway migrations may behave differently on LocalStack RDS       | Run integration tests against LocalStack before removing Docker postgres   |
| ElastiCache emulation may not support all Redis commands         | The api-gateway only uses basic GET/SET for rate limiting — should be fine |
| Port conflicts between Docker postgres (5432) and LocalStack RDS | Phase 4 removes Docker postgres; during transition, use different ports    |
