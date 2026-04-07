# 🤖 Coding Agent Instructions: Phase 3 - AWS CDK Data & Compute

## 🎯 Context & Objective
You are an Expert AWS Cloud Architect. We have already initialized our CDK project and successfully defined our `NetworkStack` with **Zero NAT Gateways**. Our VPC has two subnet types: `PUBLIC` and `PRIVATE_ISOLATED`.

Your task is to implement the Data Layer (Step 3) and Compute Layer (Step 4), ensuring strict adherence to our cost-optimized Free Tier strategy.

## 🛠️ Execution Plan

### Step 3: Define the Data Layer (`infrastructure/lib/database-stack.ts`)
1. **PostgreSQL:** Define an Amazon Aurora Serverless v2 PostgreSQL cluster.
    - *Constraint:* Place this strictly in the `PRIVATE_ISOLATED` subnets. Set minimum ACU to 0.5. Use AWS Secrets Manager for the master password.
2. **NoSQL:** Define an **Amazon DynamoDB Table** with On-Demand billing. Do not deploy DocumentDB.
3. **Cache:** Define an ElastiCache Serverless Redis cluster (or a single t3.micro Redis node to save costs).
    - *Constraint:* Place this in the `PRIVATE_ISOLATED` subnets.

### Step 4: Define the Compute Layer (`infrastructure/lib/compute-stack.ts`)
1. Define an ECS Cluster using the VPC from Step 1.
2. Create Fargate Task Definitions for `portfolio-service`, `market-data-service`, and `api-gateway` (Min CPU: 256, Min Memory: 512).
3. **CRITICAL ROUTING RULES:** Because we have no NAT Gateways, you MUST configure the Fargate services to deploy into the `PUBLIC` subnets and set `assignPublicIp: true`. (This allows them to use the IGW to pull ECR images).
4. **Security Groups:** Even though Fargate is in a public subnet, restrict its Security Group to strictly DENY all inbound internet traffic. Only allow inbound HTTP traffic from the Application Load Balancer (ALB).
5. Set up an internet-facing ALB in the `PUBLIC` subnets to route external traffic ONLY to the `api-gateway` Fargate service.

### Step 5: Wire the Stacks (`infrastructure/bin/infrastructure.ts`)
1. Instantiate the `NetworkStack`, `DatabaseStack`, and `ComputeStack`.
2. Pass the VPC from the `NetworkStack` into the other two stacks.
3. Pass the database/cache connection strings and security groups from the `DatabaseStack` into the `ComputeStack` so the ECS tasks can access them.

---

## ⚠️ Agent Constraints (STRICT ENFORCEMENT)
- **Least Privilege IAM:** Grant exactly the required table/secret permissions (e.g., `table.grantWriteData(taskRole)`).
- **Environment Variables:** Inject the database URLs, DynamoDB table names, and Redis connection strings into the Fargate Task Definitions as environment variables.
- **Stop and wait:** Output the code for all three files and wait for my review. Do not run `cdk deploy`.