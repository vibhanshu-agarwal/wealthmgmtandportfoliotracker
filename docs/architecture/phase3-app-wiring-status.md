# Phase 3: App Wiring — Status & Decision Record

**Date:** 2026-04-11
**Status:** Blocked — LocalStack license tier insufficient

---

## Action 1: Retrieve Infrastructure Endpoints

### Terraform Apply Result

`terraform apply` was executed against LocalStack. The compute module (IAM roles, policy attachments) succeeded, but the following resources **failed**:

| Resource                                | Error                                                    | Root Cause                                                    |
| --------------------------------------- | -------------------------------------------------------- | ------------------------------------------------------------- |
| `aws_db_instance.portfolio`             | `501 — rds service not included in license`              | RDS is a Pro-tier feature                                     |
| `aws_elasticache_cluster.gateway_cache` | `501 — elasticache service not included in license`      | ElastiCache is a Pro-tier feature                             |
| `aws_s3_bucket.artifacts`               | DNS lookup failure (`wealth-artifacts-local.localstack`) | S3 path-style vs virtual-hosted-style issue in Docker network |
| `aws_lambda_function.*` (all 4)         | `501 — Fetching shared layers from AWS is a pro feature` | Lambda layer ARN references external AWS layers               |

### Conclusion

**No RDS or ElastiCache endpoints were created.** The `rds_endpoint` and `elasticache_endpoint` Terraform outputs are empty because the resources could not be provisioned under the current LocalStack license.

---

## Action 2: Backend Configuration — Current State (No Changes Needed)

Since LocalStack cannot provision RDS or ElastiCache, the Spring Boot services continue using the Docker containers. Here is the current wiring:

### portfolio-service (PostgreSQL)

**Config file:** `portfolio-service/src/main/resources/application.yml`

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/portfolio_db?options=-c%20timezone=Asia/Kolkata}
    username: ${SPRING_DATASOURCE_USERNAME:wealth_user}
    password: ${SPRING_DATASOURCE_PASSWORD:wealth_pass}
```

- **Local dev (bootRun):** Connects to `localhost:5432` (Docker postgres container, port-mapped)
- **Docker Compose:** Overridden via env var to `postgres:5432` (Docker DNS)
- **Credentials:** Already match `localstack.tfvars` stubs (`wealth_user` / `wealth_pass`)

### api-gateway (Redis)

**Config file:** `api-gateway/src/main/resources/application-local.yml`

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

- **Local dev (bootRun):** Connects to `localhost:6379` (Docker redis container, port-mapped)
- **Docker Compose:** Overridden via env vars `SPRING_DATA_REDIS_HOST=redis`, `SPRING_DATA_REDIS_PORT=6379`

### What Would Change With LocalStack RDS/ElastiCache (Future)

If the license is upgraded, the connection strings would change to:

| Service           | Current                                         | With LocalStack RDS/ElastiCache                             |
| ----------------- | ----------------------------------------------- | ----------------------------------------------------------- |
| portfolio-service | `jdbc:postgresql://localhost:5432/portfolio_db` | `jdbc:postgresql://localhost:{TF_OUTPUT_PORT}/portfolio_db` |
| api-gateway       | `redis://localhost:6379`                        | `redis://localhost:{TF_OUTPUT_PORT}`                        |

The exact ports depend on LocalStack's RDS proxy allocation (typically 4510+). These would come from `terraform output rds_port` and `terraform output elasticache_port`.

---

## Action 3: Flyway Migration Strategy

**Current status:** Flyway runs automatically on startup. No changes needed.

**Config in `portfolio-service/src/main/resources/application.yml`:**

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
  jpa:
    hibernate:
      ddl-auto: validate
```

- Flyway runs migrations from `classpath:db/migration` on every startup
- JPA validates the schema matches entities (`ddl-auto: validate`)
- Flyway uses the same `spring.datasource.*` connection — no separate config needed
- When/if we switch to LocalStack RDS, Flyway will automatically run against the new endpoint since it inherits the datasource URL

---

## Decision: Option 3 Confirmed

Per the earlier discussion, we are proceeding with **Option 3**:

- **Local dev:** Docker containers for postgres (5432) and redis (6379) — no changes
- **Terraform database module:** Validated via `terraform plan` — ready for real AWS deployment
- **Spring Boot configs:** Unchanged — already use env var overrides that work for both Docker and future AWS/LocalStack

### What's Working

- `terraform plan` succeeds with all 27 resources (compute, database, networking)
- `terraform validate` passes
- Database module correctly defines RDS PostgreSQL 16 + ElastiCache Redis 7.0
- Spring Boot services connect to Docker containers for local dev
- Flyway migrations run automatically against whatever datasource is configured

### What's Blocked

- `terraform apply` for RDS/ElastiCache requires LocalStack Pro license upgrade
- Lambda shared layers require LocalStack Pro
- S3 virtual-hosted-style bucket creation needs `s3_force_path_style` provider config

### Path to Unblock

1. **Upgrade LocalStack license** to include RDS + ElastiCache
2. Add `s3_force_path_style = true` to the AWS provider block for LocalStack
3. Re-run `terraform apply`
4. Extract endpoints from `terraform output`
5. Create `application-localstack.yml` profile with the new endpoints
6. Test Flyway migrations against LocalStack RDS
