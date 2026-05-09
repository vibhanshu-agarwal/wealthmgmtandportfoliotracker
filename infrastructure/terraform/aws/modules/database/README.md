# Database Module

Provisions RDS PostgreSQL and ElastiCache Redis for the Wealth Management platform.

## Local Dev Bypass (`is_local_dev = true`)

LocalStack Hobbyist does not include RDS or ElastiCache emulation. When
`is_local_dev = true`:

- `aws_db_instance` and `aws_elasticache_cluster` are skipped (`count = 0`)
- Outputs return hardcoded Docker container endpoints (`localhost:5432`,
  `localhost:6379`) so the Terraform graph stays valid
- The actual database layer is provided by vanilla Docker containers via
  `docker-compose.yml`

This keeps the module interface consistent — consumers always read
`module.database.rds_endpoint` regardless of environment.

## Inputs

| Name                    | Type               | Default          | Description                                 |
| ----------------------- | ------------------ | ---------------- | ------------------------------------------- |
| `project_name`          | string             | —                | Resource name prefix                        |
| `is_local_dev`          | bool               | `false`          | Skip AWS resources, output Docker endpoints |
| `db_username`           | string             | —                | RDS master username                         |
| `db_password`           | string (sensitive) | —                | RDS master password                         |
| `rds_instance_class`    | string             | `db.t3.micro`    | RDS instance class                          |
| `rds_allocated_storage` | number             | `20`             | Storage in GB                               |
| `elasticache_node_type` | string             | `cache.t3.micro` | ElastiCache node type                       |
| `local_postgres_host`   | string             | `localhost`      | Postgres host (local dev)                   |
| `local_postgres_port`   | number             | `5432`           | Postgres port (local dev)                   |
| `local_redis_host`      | string             | `localhost`      | Redis host (local dev)                      |
| `local_redis_port`      | number             | `6379`           | Redis port (local dev)                      |

## Outputs

| Name                   | Description                |
| ---------------------- | -------------------------- |
| `rds_endpoint`         | `host:port` for PostgreSQL |
| `rds_address`          | PostgreSQL hostname only   |
| `rds_port`             | PostgreSQL port            |
| `rds_db_name`          | Database name              |
| `elasticache_endpoint` | Redis hostname             |
| `elasticache_port`     | Redis port                 |
