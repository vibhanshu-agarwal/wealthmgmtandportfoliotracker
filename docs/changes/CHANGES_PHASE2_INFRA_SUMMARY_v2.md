# Phase 2 Infrastructure Summary (v2)

This file supersedes `CHANGES_PHASE2_INFRA_SUMMARY_v1.md`.
It includes all items from v1 plus the ECS task execution IAM policy hotfix.

## Scope
This document summarizes all infrastructure work completed under the `infrastructure/` folder for Phase 2/3 CDK setup and iterative corrections.

## New Infrastructure Project
- Initialized a dedicated AWS CDK v2 TypeScript project at `infrastructure/`.
- Added core CDK project files:
  - `cdk.json`, `package.json`, `package-lock.json`, `tsconfig.json`, `jest.config.js`, `.gitignore`, `.npmignore`, `README.md`.
- Updated dependencies to current CDK v2 compatible versions (`aws-cdk-lib`, `constructs`, TypeScript toolchain).

## Stack Structure and Wiring
- Implemented and wired three primary stacks through `bin/infrastructure.ts`:
  - `NetworkStack`
  - `DatabaseStack`
  - `ComputeStack`
- Removed obsolete empty stack scaffold (`lib/infrastructure-stack.ts`).
- Standardized deployment env wiring to use `CDK_DEFAULT_ACCOUNT` and `CDK_DEFAULT_REGION`.

## Network Layer (`lib/network-stack.ts`)
- Created cost-optimized VPC design:
  - `maxAzs: 2`
  - `natGateways: 0`
  - Public subnets for ALB/Fargate runtime traffic.
  - Private isolated subnets for data layer resources.
- Added gateway VPC endpoints:
  - S3
  - DynamoDB

## Data Layer (`lib/database-stack.ts`)
### PostgreSQL (Free Tier-aligned)
- Replaced Aurora Serverless with standard RDS instance:
  - `rds.DatabaseInstance` (PostgreSQL 15)
  - `t3.micro`
  - `20 GB` allocated storage
  - private isolated subnet placement
  - generated credentials via AWS Secrets Manager
- Exported JDBC connection string for compute services.

### DynamoDB
- Added `MarketDataTable` with:
  - partition key: `symbol`
  - sort key: `timestamp`
  - billing mode: `PAY_PER_REQUEST`

### Redis (Free Tier-aligned)
- Replaced ElastiCache Serverless with standard ElastiCache Redis cluster:
  - `elasticache.CfnCacheCluster`
  - `cache.t3.micro`
  - `numCacheNodes: 1`
- Added Redis subnet group in private isolated subnets.
- Exported Redis endpoint host and port.

### Kafka Cost Corrections
- Removed all managed Kafka constructs and lookups from database stack:
  - removed MSK Serverless cluster creation
  - removed bootstrap broker custom resource lookup
  - removed Kafka-related exported properties

### Security Group Model
- Moved ingress rules into data stack to avoid cross-stack dependency cycles:
  - PostgreSQL `5432` allowed from VPC CIDR
  - Redis `6379` allowed from VPC CIDR

## Compute Layer (`lib/compute-stack.ts`)
- Created ECS cluster with Cloud Map namespace:
  - `wealthmgmt.local`
- Defined Fargate task definitions for:
  - `portfolio-service` (CPU 256 / Memory 512)
  - `market-data-service` (CPU 256 / Memory 512)
  - `api-gateway` (CPU 256 / Memory 512)
- Configured services in public subnets with `assignPublicIp: true` (no NAT architecture).
- Added Cloud Map service registrations:
  - `portfolio-service`
  - `market-data-service`
- Configured internet-facing ALB to route only to `api-gateway`.
- Injected runtime configuration through ECS environment/secrets:
  - Postgres URL and DB credentials (secret) for portfolio service
  - DynamoDB table name for market-data service
  - Redis host/port for api-gateway
- Applied least-privilege grants:
  - `databaseSecret.grantRead(...)`
  - `marketDataTable.grantReadWriteData(...)`
- Removed Kafka env wiring from tasks after managed Kafka de-scope.

## Additional in v2: ECS Execution Role IAM Hotfix (2026-04-07)
- Addressed ECS Fargate startup failure caused by missing ECR auth/logging execution permissions.
- Updated `lib/compute-stack.ts` to import IAM module:
  - `import * as iam from 'aws-cdk-lib/aws-iam';`
- Explicitly attached AWS managed execution policy to all three task definition execution roles:
  - `service-role/AmazonECSTaskExecutionRolePolicy`
  - Applied to:
    - `apiGatewayTaskDefinition.executionRole`
    - `portfolioTaskDefinition.executionRole`
    - `marketDataTaskDefinition.executionRole`

## Build/Validation State
- TypeScript/CDK project compiles successfully with `npm run build` after the final Free Tier and dependency-cycle corrections.
- IAM hotfix was applied at CDK source level in `compute-stack.ts` (deployment/synth verification not re-run as part of this summary update).

## Files Added/Updated (Infrastructure)
- `infrastructure/bin/infrastructure.ts`
- `infrastructure/lib/network-stack.ts`
- `infrastructure/lib/database-stack.ts`
- `infrastructure/lib/compute-stack.ts`
- `infrastructure/cdk.json`
- `infrastructure/package.json`
- `infrastructure/package-lock.json`
- `infrastructure/tsconfig.json`
- `infrastructure/jest.config.js`
- `infrastructure/.gitignore`
- `infrastructure/.npmignore`
- `infrastructure/README.md`
- Removed: `infrastructure/lib/infrastructure-stack.ts`

## Summary File Revision
- Added: `docs/changes/CHANGES_PHASE2_INFRA_SUMMARY_v2.md`
- Retained: `docs/changes/CHANGES_PHASE2_INFRA_SUMMARY_v1.md` as historical snapshot.
