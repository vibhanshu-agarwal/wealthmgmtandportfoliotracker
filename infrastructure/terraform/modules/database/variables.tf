variable "db_password" {
  type        = string
  sensitive   = true
  description = "Master password for the RDS PostgreSQL instance"
}

variable "db_username" {
  type        = string
  description = "Master username for the RDS PostgreSQL instance"
}

variable "elasticache_node_type" {
  type        = string
  default     = "cache.t3.micro"
  description = "ElastiCache node type (Free Tier: cache.t3.micro)"
}

variable "is_local_dev" {
  type        = bool
  default     = false
  description = "If true, skips RDS/ElastiCache creation and outputs local Docker endpoints (LocalStack hack)"
}

variable "local_postgres_host" {
  type        = string
  default     = "localhost"
  description = "Postgres host for local dev (used when is_local_dev = true)"
}

variable "local_postgres_port" {
  type        = number
  default     = 5432
  description = "Postgres port for local dev (used when is_local_dev = true)"
}

variable "local_redis_host" {
  type        = string
  default     = "localhost"
  description = "Redis host for local dev (used when is_local_dev = true)"
}

variable "local_redis_port" {
  type        = number
  default     = 6379
  description = "Redis port for local dev (used when is_local_dev = true)"
}

variable "project_name" {
  type        = string
  description = "Project name prefix for resource naming"
}

variable "rds_allocated_storage" {
  type        = number
  default     = 20
  description = "Allocated storage in GB for the RDS instance"
}

variable "rds_instance_class" {
  type        = string
  default     = "db.t3.micro"
  description = "RDS instance class (Free Tier: db.t3.micro)"
}
