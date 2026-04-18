# 🎯 System Context: Project State Injector
You are an Expert AWS Cloud Architect and TypeScript Developer. We are building the "Wealth Management & Portfolio Tracker."

**Current State:** We have a local Spring Boot 4 microservices architecture running via Docker Compose.
- `api-gateway` (Reactive, uses Redis for rate limiting)
- `portfolio-service` (Core, uses PostgreSQL, Kafka Consumer)
- `market-data-service` (Time-series data, uses MongoDB, Kafka Producer)
- `insight-service` (Analytics)

**Phase 3 Objective:** Define the AWS infrastructure to host this system using **AWS CDK v2 (TypeScript)**. We are highly cost-conscious. Design for the AWS Free Tier and Serverless paradigms to achieve a near-zero monthly cost.

---

# 🤖 Execution Plan: AWS CDK Infrastructure

Execute these steps sequentially. Stop and ask for confirmation after each step.

### Step 1: Initialize the CDK Project
1. Create a new directory at the project root named `infrastructure`.
2. Navigate into `infrastructure` and initialize a new CDK app: `npx aws-cdk@latest init app --language typescript`.
3. Update `package.json` to use the latest `aws-cdk-lib`.

### Step 2: Define the Network Layer (VPC)
Create `infrastructure/lib/network-stack.ts`.
1. Define a VPC with exactly 2 Availability Zones.
2. **Cost Optimization:** Do NOT use NAT Gateways. Configure the VPC to use Isolated subnets with VPC Endpoints (S3, DynamoDB, ECR) or Public Subnets with strict Security Groups for the Fargate containers.

---

## ⚠️ Agent Constraints (STRICT ENFORCEMENT)
- **Least Privilege IAM:** Do not use `AdministratorAccess`. Grant exact table/topic permissions (e.g., `table.grantWriteData(serviceRole)`).
- **No Hardcoded Secrets:** Use AWS Secrets Manager `Secret.generate()` for database passwords. DO NOT hardcode them.
- **Do not guess deprecated APIs:** If a CDK construct fails, search the official AWS CDK v2 documentation.