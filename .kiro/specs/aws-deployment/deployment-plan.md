## Deployment Plan: Scale-to-Zero AWS Serverless Architecture

### Overview

This document outlines the deployment plan to run the Spring Boot modular monolith backend and React SPA frontend on a **scale-to-zero, serverless-first** architecture in AWS.  
The plan is intentionally conservative on cost, favouring **free/serverless SaaS** for supporting services and **pay-per-use** primitives in AWS (Lambda, S3, CloudFront, ECR), with no long‑lived EC2 instances.

The rollout is structured into three phases:

1. **Phase 1: Serverless Infrastructure Provisioning** – manual setup of external SaaS dependencies and environment configuration.
2. **Phase 2: AWS Lambda Web Adapter Integration** – containerizing the Spring Boot app for Lambda with the AWS Lambda Web Adapter.
3. **Phase 3: CI/CD Pipeline (GitHub Actions)** – automated build and deployment of frontend and backend.

Current deployment target branch: **`architecture/cloud-native-extraction`**.

---

## Phase 1: Serverless Infrastructure Provisioning

### Goals

- Use **serverless / free-tier** managed services for PostgreSQL, Kafka, Redis, and LLM access to keep **monthly cost near zero** for low traffic.
- Standardize environment variables and Spring profiles so the same Docker images can be deployed to different environments (local, dev, prod) without rebuilds.

### 1.1 PostgreSQL (Neon.tech)

#### 1.1.1 Manual Setup Steps

- **Create Neon Project & Branch**
  - Sign up / log in to `https://neon.tech` with an account tied to the engineering team.
  - Create a new **Project** (e.g. `wealthmgmt-portfolio-db`) in a close geographic region to the primary AWS region.
  - Accept default **serverless** configuration (auto-suspend enabled, 0–X compute).
  - Create a **primary branch** (e.g. `main`) and a **database** (e.g. `wealthmgmt`).

- **Configure Autoscaling / Scale-to-Zero**
  - Ensure the project uses **serverless compute** with:
    - Auto‑suspend after short inactivity interval (5–10 minutes) to minimize cost.
    - Minimal storage tier appropriate for initial dataset.
  - Confirm Neon’s connection pooling is enabled if recommended by Neon for serverless usage.

- **Create Application Role & Credentials**
  - Create a dedicated **database user** (e.g. `app_user`) with limited privileges:
    - Permissions: `CONNECT`, `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `CREATE` on target schema.
  - Generate a **strong password**, to be stored only in:
    - AWS SSM Parameter Store or Secrets Manager (for prod).
    - GitHub Actions secrets (for CI/CD connectivity if needed).

#### 1.1.2 JDBC URL & Spring Configuration

- From Neon’s dashboard, copy the **Postgres connection string** (without any vendor-specific query params).
- Normalize to a JDBC URL of the form:

```text
jdbc:postgresql://<neon-host>:<port>/<database>
```

- Define the following **environment variables** for the `prod` profile:
  - `SPRING_DATASOURCE_URL=jdbc:postgresql://<neon-host>:<port>/<db-name>`
  - `SPRING_DATASOURCE_USERNAME=app_user`
  - `SPRING_DATASOURCE_PASSWORD=<strong-password>`
  - Optional: `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` tuned for Lambda concurrency (e.g. small pool).

- In `application-prod.yml` (referenced later), ensure:
  - `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password` are resolved from env vars.
  - `spring.jpa.hibernate.ddl-auto` is set appropriately (e.g. `validate` or `update` based on migration strategy).

---

### 1.2 Kafka (Upstash)

#### 1.2.1 Manual Setup Steps

- **Provision Serverless Kafka**
  - Sign up / log in to `https://upstash.com`.
  - Create a **Kafka cluster** (serverless plan; region aligned with AWS region where possible).
  - Add topics required by the system (e.g. `price-updates`, `portfolio-events`, etc.).

- **Configure Access & Security**
  - Create a dedicated **Kafka API key / secret** pair for the backend.
  - Verify protocol (typically **SASL/SSL** over a public endpoint).
  - Note the connection endpoint and any required `SASL_MECHANISM` / `SECURITY_PROTOCOL` values from Upstash’s dashboard.

#### 1.2.2 Connection Credentials & Spring Configuration

- Extract from Upstash:
  - `BOOTSTRAP_SERVERS` – e.g. `lazy-grasshopper-12345.upstash.io:9092`
  - `SASL_USERNAME` – Upstash Kafka username / access key.
  - `SASL_PASSWORD` – Upstash Kafka password / secret.

- Define **environment variables** for the `prod` profile:
  - `KAFKA_BOOTSTRAP_SERVERS=<upstash-kafka-endpoint>`
  - `KAFKA_SASL_USERNAME=<upstash-username>`
  - `KAFKA_SASL_PASSWORD=<upstash-password>`
  - `KAFKA_SASL_MECHANISM=PLAIN` (or as specified by Upstash).
  - `KAFKA_SECURITY_PROTOCOL=SASL_SSL`.

- Spring Boot configuration (in `application-prod.yml`):
  - `spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}`
  - `spring.kafka.properties.sasl.jaas.config` constructed from username/password via env.
  - `spring.kafka.properties.security.protocol=${KAFKA_SECURITY_PROTOCOL}`
  - `spring.kafka.properties.sasl.mechanism=${KAFKA_SASL_MECHANISM}`

---

### 1.3 Redis (Upstash)

#### 1.3.1 Manual Setup Steps

- **Provision Serverless Redis**
  - In Upstash, create a **Redis database** (serverless / free-tier, region near AWS).
  - Confirm default **scale-to-zero** characteristics and daily/monthly request limits.

- **Configure Access**
  - Copy the **public Redis URL** and **token/password** from Upstash dashboard.
  - Verify TLS requirement and connection settings.

#### 1.3.2 Connection Credentials & Spring Configuration

- Extract from Upstash:
  - `UPSTASH_REDIS_URL` – e.g. `rediss://:<password>@us1-coiled-12345.upstash.io:6379`
  - `UPSTASH_REDIS_PASSWORD` – if Upstash separates password/token.

- Define **environment variables**:
  - `REDIS_URL=<upstash-redis-url>`
  - `REDIS_PASSWORD=<upstash-redis-password-or-token>` (if required separately).

- Spring Boot `prod` configuration:
  - `spring.data.redis.url=${REDIS_URL}` **or**:
    - `spring.data.redis.host`, `spring.data.redis.port`, `spring.data.redis.password` derived from env vars if parsed separately.
  - Ensure TLS is enabled if connecting over `rediss://`.

---

### 1.4 Amazon Bedrock (Claude 3 Haiku)

#### 1.4.1 AWS Account & Region

- Choose a **single AWS account** and **primary region** that:
  - Supports **Amazon Bedrock** and **Claude 3 Haiku**.
  - Aligns with other services (Lambda, ECR, S3, CloudFront).

- Confirm Bedrock availability for the chosen region and model (`Claude 3 Haiku`) in the AWS console.

#### 1.4.2 IAM Permissions

- Create an **IAM role** for the backend Lambda (e.g. `wealth-backend-lambda-role`) with:
  - **Trust policy**: `lambda.amazonaws.com`.
  - **Permissions policies** including:
    - `bedrock:InvokeModel` and/or `bedrock:InvokeModelWithResponseStream` for the specific region.
    - Resource ARNs scoped to the Claude 3 Haiku model(s) only, if possible.
    - CloudWatch Logs permissions for basic logging.
    - Optional: SSM Parameter Store / Secrets Manager read-only access for configuration secrets.

- If the application calls Bedrock via AWS SDK:
  - Ensure appropriate **region configuration** is set via env vars (e.g. `BEDROCK_REGION`).

#### 1.4.3 Model Access

- In AWS console → **Amazon Bedrock**:
  - Request and ensure **model access is granted** for:
    - `Claude 3 Haiku` in the chosen region.
  - Verify status is **“Enabled”** before deployment.

- Store any **model IDs** required by the application as environment variables:
  - `BEDROCK_MODEL_ID=<haiku-model-id>`

---

### 1.5 Spring `prod` Profile & Environment Variables

#### 1.5.1 Profile Strategy

- Use a dedicated **`prod` Spring profile** for the AWS serverless deployment:
  - Active profile: `SPRING_PROFILES_ACTIVE=prod`.
  - `application-prod.yml` overrides:
    - Datasource (Neon).
    - Kafka (Upstash).
    - Redis (Upstash).
    - Bedrock integration.
    - Any production-only tuning (logging, thread pools, cache TTLs).

#### 1.5.2 Required Environment Variables (Backend Lambda)

At minimum, the Lambda’s container environment should define:

- **Spring / Core**
  - `SPRING_PROFILES_ACTIVE=prod`
  - `SERVER_PORT=8080` (aligned with Lambda Web Adapter expectations)

- **PostgreSQL / Neon**
  - `SPRING_DATASOURCE_URL`
  - `SPRING_DATASOURCE_USERNAME`
  - `SPRING_DATASOURCE_PASSWORD`

- **Kafka / Upstash**
  - `KAFKA_BOOTSTRAP_SERVERS`
  - `KAFKA_SASL_USERNAME`
  - `KAFKA_SASL_PASSWORD`
  - `KAFKA_SASL_MECHANISM`
  - `KAFKA_SECURITY_PROTOCOL`

- **Redis / Upstash**
  - `REDIS_URL`
  - `REDIS_PASSWORD` (if required)

- **Amazon Bedrock**
  - `BEDROCK_REGION`
  - `BEDROCK_MODEL_ID`

- **App-Specific**
  - Any existing secrets/tokens already used in non-prod (OAuth, API keys, etc.), provided securely via SSM/Secrets Manager or Lambda environment.

#### 1.5.3 `application-prod.yml` Responsibilities (Conceptual)

- Reference all external configuration using environment placeholders:
  - Example: `spring.datasource.url: ${SPRING_DATASOURCE_URL}`
  - Example: `spring.kafka.bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}`
  - Example: `spring.data.redis.url: ${REDIS_URL}`
- Avoid hard‑coding any credentials, hostnames, or ports for production.

---

## Phase 2: AWS Lambda Web Adapter Integration

### Goals

- Package the existing Spring Boot modular monolith into a **Lambda-compatible container image**.
- Integrate the **AWS Lambda Web Adapter** so HTTP requests received via API Gateway or Lambda URLs are transparently routed to the Spring Boot application.
- Maintain **local Docker run parity** with the AWS Lambda runtime for easier debugging.

### 2.1 Containerization Strategy

- Use the existing Dockerfile as a base and:
  - Continue to build a **JVM-based Spring Boot fat jar** or layered jar.
  - Run under the **Lambda Runtime Interface Emulator (RIE)** via the Lambda Web Adapter container.
  - Expose the application internally on **port 8080**; the adapter translates Lambda events to HTTP requests on that port.

### 2.2 Lambda Web Adapter Injection

#### 2.2.1 Image Layout

- The AWS Lambda Web Adapter is distributed as a **binary** stored in the public ECR repository:
  - `public.ecr.aws/awsguru/aws-lambda-adapter:<tag>`

- The container image must:
  - Copy the adapter binary into the image.
  - Set the `ENTRYPOINT` (or `CMD`) so the adapter process starts and launches the Spring Boot app.

#### 2.2.2 Required COPY Instruction (Conceptual)

- In the Dockerfile’s **final stage**, add an instruction equivalent to:

```dockerfile
COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0 /lambda-adapter /lambda-adapter
```

- Constraints:
  - This must appear in the final image stage where the Spring Boot application jar and runtime exist.
  - The path `/lambda-adapter` should be referenced later in the container’s `ENTRYPOINT`/`CMD` definition in the Dockerfile (not implemented yet, but planned).

### 2.3 Port and Runtime Configuration

- Ensure the Spring Boot application listens on **port 8080** inside the container:
  - `SERVER_PORT=8080` as an environment variable, or:
  - `server.port=8080` in a profile that is active in Lambda.

- Configure the Lambda Web Adapter via environment variables (to be set on the Lambda function):
  - `PORT=8080` – port the adapter will proxy to inside the container.
  - `AWS_LAMBDA_EXEC_WRAPPER=/lambda-adapter` – path to the adapter binary within the container.
  - Any optional adapter configuration (e.g. timeouts, logging) can be added later as env vars.

### 2.4 Local Verification Workflow

Before publishing the image to ECR, verify locally:

1. **Build the Spring Boot Jar**
   - Use the existing Gradle/Maven build to generate the application jar (no changes required at this stage).

2. **Build the Docker Image**
   - Run the local `docker build` using the updated Dockerfile (once implemented).
   - Tag the image as something like `wealth-backend-lambda:local`.

3. **Run the Container Locally**
   - Start the container with env vars mirroring Lambda:
     - `SPRING_PROFILES_ACTIVE=prod` (or a dedicated `aws` profile if needed).
     - `SERVER_PORT=8080`.
     - All required external service env vars (Neon, Upstash, Bedrock) pointing to dev/sandbox resources.
   - Confirm:
     - The container starts successfully.
     - Health endpoints (e.g. `/actuator/health`) are reachable.
     - Basic API routes respond correctly.

4. **Simulate Lambda Invocation (Optional)**
   - Optionally use the **Lambda Runtime Interface Emulator** tooling to simulate Lambda event invocations against the image.
   - Validate that HTTP requests are correctly mapped by the adapter to Spring Boot routes.

5. **Baseline Performance Check**
   - Issue a small number of requests to:
     - Confirm application cold start time is acceptable.
     - Observe any startup logs relevant to DB, Kafka, and Redis connections.

Only after these checks pass should the image be considered ready for **ECR publishing** and **Lambda association** in later phases.

---

## Phase 3: CI/CD Pipeline (GitHub Actions)

### Goals

- Automate deployment to AWS so that:
  - Frontend artifacts are continuously built and published to **S3 + CloudFront**.
  - Backend container images are built, pushed to **Amazon ECR**, and rolled out to **AWS Lambda**.
- Ensure the pipeline is **idempotent and safe**, with clear separation between frontend and backend steps.

### 3.1 Workflow Overview

- A single GitHub Actions workflow file: `.github/workflows/deploy.yml`.
- Triggered on:
  - Push to the current deployment branch: `architecture/cloud-native-extraction`.
  - Manual dispatch (`workflow_dispatch`) for controlled deployments.
- Contains two primary jobs:
  - `frontend-deploy` – builds and deploys the React SPA.
  - `backend-deploy` – builds and deploys the Spring Boot Lambda container.
- **Job dependency**:
  - Option 1: Backend and frontend deploy in **parallel** (independent).
  - Option 2: Backend waits for frontend or vice versa, if we want staged rollouts. (Default assumption: parallel, with both depending on shared “prepare” steps if needed.)

---

### 3.2 Frontend Deploy Job (React SPA → S3 + CloudFront)

#### 3.2.1 Inputs & Secrets

The job will require GitHub Actions secrets for:

- `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`
- `FRONTEND_S3_BUCKET` – target S3 bucket for the SPA.
- `CLOUDFRONT_DISTRIBUTION_ID` – CloudFront distribution serving the SPA.

#### 3.2.2 High-Level Steps

1. **Checkout Source**
   - Use the standard checkout action to pull the repository.

2. **Setup Node.js**
   - Configure Node.js version compatible with the React project.

3. **Install Dependencies & Build**
   - Run package manager install (e.g. `npm ci` or `pnpm install`).
   - Build the SPA for production:
     - Output directory assumed to be `dist/` (adjustable if project uses `build/` or another path).

4. **Sync Built Assets to S3**
   - Use AWS CLI or an S3 sync action to:
     - Upload `dist/` contents to `$FRONTEND_S3_BUCKET`.
     - Delete objects in S3 that no longer exist locally (to avoid stale assets), if desired.
   - Ensure correct:
     - Cache headers (e.g. long cache for static assets, shorter for `index.html`).
     - Content-type detection for JS, CSS, HTML, images.

5. **CloudFront Cache Invalidation**

   - After the S3 sync completes, invalidate CloudFront cache:
     - Minimal invalidation pattern: `/*` for simplicity, or:
     - More targeted invalidation (e.g. `/index.html`) as an optimization.
   - Wait for the invalidation request to be accepted (completion can be async; we only need a successful request).

#### 3.2.3 Success Criteria

- `dist/` build completes with no errors.
- All assets present in S3 bucket and retrievable via CloudFront URL.
- CloudFront returns updated SPA version after invalidation.

---

### 3.3 Backend Deploy Job (Spring Boot → Lambda via ECR)

#### 3.3.1 Inputs & Secrets

The job will require:

- `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`
- `ECR_REPOSITORY` – name of the ECR repository (e.g. `wealth-backend-lambda`).
- `LAMBDA_FUNCTION_NAME` – target Lambda function to update.

Optional:

- `IMAGE_TAG` override or derived from git SHA (default: use commit SHA or `main`).

#### 3.3.2 High-Level Steps

1. **Checkout Source**
   - Fetch the backend code and Dockerfile.

2. **Set Up JDK & Build Backend**
   - Configure Java toolchain (e.g. Java 21 / 25 as used by the project).
   - Run `./gradlew build` or equivalent to produce the Spring Boot jar.

3. **Authenticate to ECR**
   - Use AWS CLI or an ECR login action to obtain a Docker login token for the target AWS account and region.

4. **Build Docker Image**
   - Build the Lambda-ready image using the existing Dockerfile (enhanced with Lambda Web Adapter in Phase 2).
   - Tag the image with:
     - A unique tag (e.g. git SHA) and optionally `latest`.
     - Full ECR URI: `<account-id>.dkr.ecr.<region>.amazonaws.com/<ECR_REPOSITORY>:<tag>`.

5. **Push Image to ECR**
   - Push the newly built image to the ECR repository.

6. **Update Lambda Function to Use New Image**
   - Use AWS CLI (or dedicated action) to update:
     - `LAMBDA_FUNCTION_NAME` to reference the new image URI.
   - Confirm configuration includes:
     - `SPRING_PROFILES_ACTIVE=prod`.
     - All required env vars from Phase 1 (DB, Kafka, Redis, Bedrock).
     - `AWS_LAMBDA_EXEC_WRAPPER=/lambda-adapter` and `PORT=8080`.

7. **Post-Deployment Verification (Basic)**
   - Optionally, as part of the CI job:
     - Invoke a non-destructive health endpoint using `aws lambda invoke` or API Gateway URL.
     - Check that the function returns a healthy status code (e.g. HTTP 200).

#### 3.3.3 Job Dependencies and Ordering

- Default assumption: `backend-deploy` can run **independently** of `frontend-deploy`.
- If we want stronger guarantees:
  - Make `backend-deploy` depend on `frontend-deploy` completion for synchronized releases.

#### 3.3.4 Rollback Considerations (Conceptual)

- Keep multiple previous image tags in ECR.
- In case of a bad deployment:
  - Re-run the workflow with a known‑good image tag, or:
  - Manually update the Lambda function to a prior image URI via AWS console/CLI.

---

### 3.4 Non-Functional & Operational Considerations

- **Secrets Management**
  - Long‑lived secrets (DB password, Kafka/Redis tokens, Bedrock settings) should live in:
    - AWS SSM Parameter Store or Secrets Manager for runtime.
    - GitHub Actions only holds the minimal credentials needed to deploy.

- **Cost Control**
  - Ensure all third-party services (Neon, Upstash Kafka/Redis) are on **free or low‑tier serverless plans**.
  - Set explicit region and quotas in AWS (Lambda concurrency, CloudFront data transfer awareness).

- **Monitoring & Observability**
  - Use CloudWatch Logs for Lambda.
  - Add minimal health checks / smoke tests into the CI pipeline to validate post-deploy status.

- **Future Automation**
  - Phase 1 steps can later be codified via Terraform or AWS CDK once the manual process is stable.

