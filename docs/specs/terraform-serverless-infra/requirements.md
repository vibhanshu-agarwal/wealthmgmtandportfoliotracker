# Requirements Document

## Introduction

This feature replaces the existing AWS CDK v2 (TypeScript) infrastructure with a Terraform-based
serverless infrastructure for the Wealth Management & Portfolio Tracker project (Phase 3). The goal
is to deploy the Spring Boot microservices (api-gateway, portfolio-service, market-data-service,
insight-service) as AWS Lambda functions, eliminate all fixed-cost infrastructure (NAT Gateways,
ECS/Fargate, provisioned RDS), and structure the Terraform code in portable modules that can be
retargeted to Azure in a future phase. All infrastructure must remain within the AWS Always Free
Tier limits. External managed databases (MongoDB Atlas, Neon/Supabase PostgreSQL) are used instead
of provisioned AWS data stores.

---

## Glossary

- **Terraform_Root**: The top-level Terraform configuration at `infrastructure/terraform/` that
  wires together all child modules and declares the provider block.
- **Compute_Module**: The Terraform module at `infrastructure/terraform/modules/compute` responsible
  for Lambda function resources, IAM execution roles, and Function URLs.
- **Networking_Module**: The Terraform module at `infrastructure/terraform/modules/networking`
  responsible for CloudFront distributions, Route 53 records, and ACM certificates.
- **Lambda_Adapter**: The AWS Lambda Web Adapter or Spring Cloud Function shim that allows an
  existing Spring Boot JAR to run inside an AWS Lambda execution environment without rewriting
  application code.
- **Function_URL**: An AWS Lambda Function URL — an HTTPS endpoint attached directly to a Lambda
  function, used as the origin for CloudFront without requiring API Gateway.
- **LocalStack**: A local AWS cloud emulator (running at `localhost:4566`) used for integration
  testing of Terraform-provisioned resources without incurring real AWS costs.
- **Provider_Toggle**: A Terraform variable (`var.use_localstack`) that switches the AWS provider
  endpoint configuration between LocalStack and real AWS.
- **Free_Tier**: The AWS Always Free Tier limits: 1 million Lambda invocations/month, 400,000
  GB-seconds compute/month, 1 TB CloudFront data transfer/month, 25 RCU/WCU DynamoDB, 5 GB S3.
- **Remote_State_Backend**: An S3 bucket + DynamoDB lock table used as the Terraform remote state
  backend to prevent state corruption across team members and CI runs.
- **SnapStart**: An AWS Lambda feature that pre-initializes the JVM snapshot to reduce cold-start
  latency for Java Lambda functions.
- **Spring_Profile**: A Spring Boot profile (`aws`) activated via the `SPRING_PROFILES_ACTIVE`
  environment variable injected into each Lambda function's configuration.
- **Connection_String_Variable**: A Terraform input variable that accepts an externally managed
  database connection string (e.g., MongoDB Atlas URI, Neon PostgreSQL JDBC URL) rather than
  provisioning a data store inside Terraform.

---

## Requirements

---

### Requirement 1: Terraform Module Structure

**User Story:** As a platform engineer, I want the Terraform code organized into reusable modules,
so that the compute and networking logic can be retargeted to Azure without rewriting the
application layer.

#### Acceptance Criteria

1. THE Terraform_Root SHALL contain a `modules/compute` subdirectory and a `modules/networking`
   subdirectory, each with its own `main.tf`, `variables.tf`, and `outputs.tf` files.
2. THE Terraform_Root SHALL contain a root-level `main.tf` that instantiates the Compute_Module
   and Networking_Module, passing outputs between them.
3. THE Terraform_Root SHALL contain a `variables.tf` declaring all input variables and a
   `outputs.tf` exposing the public endpoint URL.
4. THE Terraform_Root SHALL contain a `terraform.tfvars.example` file documenting every required
   variable with placeholder values so a new contributor can onboard without reading source code.
5. WHEN a contributor runs `terraform validate` against the Terraform_Root, THE Terraform_Root
   SHALL produce zero errors and zero warnings.

---

### Requirement 2: Provider Toggle (LocalStack vs. Real AWS)

**User Story:** As a developer, I want to toggle the Terraform AWS provider between LocalStack and
real AWS using a single variable, so that I can run integration tests locally without incurring
cloud costs.

#### Acceptance Criteria

1. THE Terraform_Root SHALL declare a boolean input variable named `use_localstack` with a default
   value of `false`.
2. WHEN `use_localstack` is `true`, THE Terraform_Root SHALL configure the AWS provider with
   `endpoints` pointing to `http://localhost:4566` for all services used (Lambda, S3, DynamoDB,
   CloudFront, IAM, ACM, Route 53).
3. WHEN `use_localstack` is `false`, THE Terraform_Root SHALL configure the AWS provider using
   standard AWS credential resolution (environment variables, shared credentials file, or IAM
   instance profile) with no custom endpoint overrides.
4. THE Terraform_Root SHALL accept `aws_region` as an input variable with a default of `us-east-1`
   so the target region can be changed without modifying source files.
5. IF `use_localstack` is `true` and the LocalStack container is unreachable, THEN THE
   Terraform_Root SHALL surface a provider connectivity error within 30 seconds rather than
   hanging indefinitely.

---

### Requirement 3: Remote State Backend

**User Story:** As a platform engineer, I want Terraform state stored remotely in S3 with DynamoDB
locking, so that concurrent CI runs and team members cannot corrupt the state file.

#### Acceptance Criteria

1. THE Terraform_Root SHALL declare an S3 backend configuration referencing a bucket name and
   DynamoDB table name supplied via backend configuration variables.
2. THE Remote_State_Backend S3 bucket SHALL have versioning enabled so previous state revisions
   can be recovered.
3. THE Remote_State_Backend DynamoDB table SHALL use `LockID` as the partition key and on-demand
   billing to remain within the AWS Always Free Tier.
4. THE Terraform_Root SHALL include a `bootstrap/` subdirectory containing a standalone Terraform
   configuration that provisions the Remote_State_Backend S3 bucket and DynamoDB table, so the
   backend can be created before the main configuration is initialized.
5. WHEN two `terraform apply` processes run concurrently against the same workspace, THE
   Remote_State_Backend SHALL allow only one process to hold the state lock at a time, causing the
   second to wait or fail with a lock-conflict error.

---

### Requirement 4: Lambda Compute — api-gateway Service

**User Story:** As a platform engineer, I want the api-gateway Spring Boot service deployed as an
AWS Lambda function, so that it handles all inbound HTTP routing without the fixed cost of
ECS/Fargate or an Application Load Balancer.

#### Acceptance Criteria

1. THE Compute_Module SHALL define an `aws_lambda_function` resource for the api-gateway service
   that references the compiled Spring Boot JAR via an S3 object key input variable.
2. THE Compute_Module SHALL configure the api-gateway Lambda with the Lambda_Adapter layer ARN so
   the existing Spring Boot HTTP handler is invoked without code changes.
3. THE Compute_Module SHALL attach an IAM execution role to the api-gateway Lambda granting only
   the permissions required: `logs:CreateLogGroup`, `logs:CreateLogStream`,
   `logs:PutLogEvents`, and `lambda:InvokeFunctionUrl`.
4. THE Compute_Module SHALL enable AWS Lambda SnapStart on the api-gateway function (using the
   `PublishedVersions` apply policy) to reduce JVM cold-start latency.
5. THE Compute_Module SHALL expose a Function_URL for the api-gateway Lambda with `AuthType` set
   to `NONE` (authentication delegated to the Spring Security JWT filter already in the
   application).
6. WHEN the api-gateway Lambda receives an HTTP request, THE Lambda_Adapter SHALL translate the
   Lambda invocation payload into a standard HTTP request and forward it to the Spring Boot
   embedded server on port 8080.
7. THE Compute_Module SHALL inject the following environment variables into the api-gateway Lambda:
   `SPRING_PROFILES_ACTIVE=aws`, `PORTFOLIO_SERVICE_URL`, `MARKET_DATA_SERVICE_URL`,
   `INSIGHT_SERVICE_URL`, and `AUTH_JWK_URI`.

---

### Requirement 5: Lambda Compute — portfolio-service

**User Story:** As a platform engineer, I want the portfolio-service deployed as an AWS Lambda
function, so that it processes portfolio requests without provisioned compute costs.

#### Acceptance Criteria

1. THE Compute_Module SHALL define an `aws_lambda_function` resource for the portfolio-service
   referencing the compiled Spring Boot JAR via an S3 object key input variable.
2. THE Compute_Module SHALL configure the portfolio-service Lambda with the Lambda_Adapter layer
   ARN.
3. THE Compute_Module SHALL attach an IAM execution role to the portfolio-service Lambda granting
   only `logs:CreateLogGroup`, `logs:CreateLogStream`, `logs:PutLogEvents`.
4. THE Compute_Module SHALL enable SnapStart on the portfolio-service Lambda.
5. THE Compute_Module SHALL expose a Function_URL for the portfolio-service Lambda with `AuthType`
   set to `NONE` (access restricted to api-gateway via network policy or signed requests).
6. THE Compute_Module SHALL inject the following environment variables into the portfolio-service
   Lambda: `SPRING_PROFILES_ACTIVE=aws` and `SPRING_DATASOURCE_URL` (sourced from the
   Connection_String_Variable for PostgreSQL).

---

### Requirement 6: Lambda Compute — market-data-service and insight-service

**User Story:** As a platform engineer, I want market-data-service and insight-service deployed as
Lambda functions, so that all four microservices share a consistent serverless deployment model.

#### Acceptance Criteria

1. THE Compute_Module SHALL define `aws_lambda_function` resources for market-data-service and
   insight-service following the same pattern as Requirements 4 and 5.
2. THE Compute_Module SHALL inject `SPRING_PROFILES_ACTIVE=aws` and `SPRING_DATA_MONGODB_URI`
   (sourced from the Connection_String_Variable for MongoDB Atlas) into the market-data-service
   Lambda.
3. THE Compute_Module SHALL inject `SPRING_PROFILES_ACTIVE=aws` into the insight-service Lambda,
   plus any additional environment variables declared as input variables in the Compute_Module.
4. THE Compute_Module SHALL expose Function_URLs for market-data-service and insight-service with
   `AuthType` set to `NONE`.

---

### Requirement 7: External Database Connection Strings

**User Story:** As a platform engineer, I want Terraform to accept external database connection
strings as input variables rather than provisioning RDS or DocumentDB, so that I can use free-tier
managed services (Neon/Supabase PostgreSQL, MongoDB Atlas) without incurring AWS data store costs.

#### Acceptance Criteria

1. THE Terraform_Root SHALL declare a `postgres_connection_string` input variable of type `string`
   marked `sensitive = true` to hold the Neon or Supabase PostgreSQL JDBC URL.
2. THE Terraform_Root SHALL declare a `mongodb_connection_string` input variable of type `string`
   marked `sensitive = true` to hold the MongoDB Atlas connection URI.
3. THE Compute_Module SHALL accept both connection string variables as inputs and inject them as
   Lambda environment variables without logging or outputting their values.
4. WHEN `terraform plan` is executed, THE Terraform_Root SHALL not display the values of
   `postgres_connection_string` or `mongodb_connection_string` in the plan output (enforced by
   `sensitive = true`).
5. THE Terraform_Root SHALL NOT define any `aws_db_instance`, `aws_rds_cluster`,
   `aws_docdb_cluster`, or `aws_elasticache_cluster` resources, ensuring zero provisioned data
   store costs.

---

### Requirement 8: VPC-less Architecture

**User Story:** As a platform engineer, I want all Lambda functions deployed without a VPC
attachment, so that I avoid the $30+/month NAT Gateway cost required for VPC-attached Lambdas to
reach the internet.

#### Acceptance Criteria

1. THE Compute_Module SHALL NOT define any `aws_vpc`, `aws_subnet`, `aws_nat_gateway`, or
   `aws_internet_gateway` resources.
2. THE Compute_Module SHALL NOT attach any `vpc_config` block to any `aws_lambda_function`
   resource.
3. WHEN a Lambda function needs to reach an external service (MongoDB Atlas, Neon PostgreSQL,
   Bedrock), THE Lambda_Adapter SHALL connect directly over the public internet using the
   connection string injected via environment variable.
4. THE Networking_Module SHALL NOT define any `aws_nat_gateway` resource.

---

### Requirement 9: CloudFront Distribution as Public Entry Point

**User Story:** As a platform engineer, I want a CloudFront distribution fronting the api-gateway
Lambda Function URL, so that the application has a global HTTPS edge, custom domain support, and
remains within the Free Tier data transfer limits.

#### Acceptance Criteria

1. THE Networking_Module SHALL define an `aws_cloudfront_distribution` resource with the
   api-gateway Function_URL as its sole origin.
2. THE Networking_Module SHALL configure the CloudFront distribution to forward all HTTP methods
   (GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD) to the origin so REST API semantics are
   preserved.
3. THE Networking_Module SHALL configure the CloudFront distribution with HTTPS-only viewer
   protocol policy.
4. THE Networking_Module SHALL accept an optional `domain_name` input variable; WHEN `domain_name`
   is provided, THE Networking_Module SHALL associate an ACM certificate ARN (supplied via
   `acm_certificate_arn` input variable) with the distribution and add the domain as an alias.
5. WHEN `domain_name` is not provided, THE Networking_Module SHALL use the default
   `*.cloudfront.net` domain so the distribution is functional without a custom domain.
6. THE Networking_Module SHALL output the CloudFront distribution domain name so it can be used
   as the application's public URL.

---

### Requirement 10: Route 53 DNS Integration

**User Story:** As a platform engineer, I want an optional Route 53 alias record pointing to the
CloudFront distribution, so that the custom domain `vibhanshu-portfolio-ai.com` resolves to the
serverless entry point.

#### Acceptance Criteria

1. WHERE `domain_name` is provided, THE Networking_Module SHALL define an `aws_route53_record`
   resource of type `A` with an alias target pointing to the CloudFront distribution.
2. THE Networking_Module SHALL accept a `route53_zone_id` input variable and use it as the hosted
   zone for the DNS record.
3. WHERE `domain_name` is not provided, THE Networking_Module SHALL not create any Route 53
   resources, keeping the configuration valid for environments without a registered domain.

---

### Requirement 11: Artifact Storage (S3 Deployment Bucket)

**User Story:** As a platform engineer, I want Lambda deployment JARs stored in S3, so that
Terraform can reference them by key and Lambda can pull them during function creation or update.

#### Acceptance Criteria

1. THE Terraform_Root SHALL define an `aws_s3_bucket` resource for Lambda deployment artifacts,
   separate from the Remote_State_Backend bucket.
2. THE Terraform_Root SHALL enable versioning on the deployment artifact bucket so previous JAR
   versions can be redeployed without re-uploading.
3. THE Compute_Module SHALL reference each Lambda function's JAR via `s3_bucket` and `s3_key`
   input variables rather than embedding a local file path, so CI/CD pipelines can upload JARs
   independently of Terraform runs.
4. THE Terraform_Root SHALL block all public access on the deployment artifact bucket.

---

### Requirement 12: IAM Least-Privilege Execution Roles

**User Story:** As a security-conscious engineer, I want each Lambda function to have its own IAM
execution role with only the permissions it needs, so that a compromised function cannot escalate
privileges or access other services.

#### Acceptance Criteria

1. THE Compute_Module SHALL create a separate `aws_iam_role` for each Lambda function (api-gateway,
   portfolio-service, market-data-service, insight-service).
2. THE Compute_Module SHALL attach only the `AWSLambdaBasicExecutionRole` managed policy to each
   role as the baseline, granting CloudWatch Logs write access.
3. WHERE a Lambda function requires access to additional AWS services (e.g., Amazon Bedrock for
   insight-service), THE Compute_Module SHALL attach an additional inline policy granting only the
   specific actions required (e.g., `bedrock:InvokeModel`).
4. THE Compute_Module SHALL NOT attach `AdministratorAccess` or any wildcard resource (`*`) policy
   to any Lambda execution role.

---

### Requirement 13: Spring Profile and Cold-Start Configuration

**User Story:** As a developer, I want each Lambda function configured with the correct Spring
profile and cold-start mitigations, so that the application boots with AWS-specific settings and
starts within an acceptable latency budget.

#### Acceptance Criteria

1. THE Compute_Module SHALL set `SPRING_PROFILES_ACTIVE=aws` as an environment variable on every
   Lambda function so `application-aws.yml` is loaded instead of `application-local.yml`.
2. THE Compute_Module SHALL set the Lambda runtime to `java21` (or `java25` when available as a
   managed runtime) and configure `JAVA_TOOL_OPTIONS` to include
   `-XX:+TieredCompilation -XX:TieredStopAtLevel=1` to reduce JVM warm-up time.
3. THE Compute_Module SHALL set a minimum memory allocation of 512 MB and a timeout of 30 seconds
   for each Lambda function to accommodate Spring Boot initialization.
4. WHEN SnapStart is enabled on a Lambda function, THE Compute_Module SHALL publish a new Lambda
   version on each Terraform apply so SnapStart snapshots are refreshed after code updates.

---

### Requirement 14: LocalStack Integration Testing

**User Story:** As a developer, I want to run `terraform apply` against LocalStack to validate the
Terraform configuration locally, so that infrastructure changes are verified before touching real
AWS.

#### Acceptance Criteria

1. THE Terraform_Root SHALL include a `localstack.tfvars` file that sets `use_localstack=true`,
   `aws_region=us-east-1`, and stub values for all sensitive variables so a developer can run
   `terraform apply -var-file=localstack.tfvars` without providing real credentials.
2. WHEN `terraform apply -var-file=localstack.tfvars` is executed against a running LocalStack
   container, THE Terraform_Root SHALL successfully create all Lambda, S3, IAM, and CloudFront
   resources without errors.
3. THE Terraform_Root SHALL include a `README.md` documenting the LocalStack setup steps,
   including the Docker Compose snippet required to start LocalStack alongside the existing
   development services.
4. WHEN `terraform destroy -var-file=localstack.tfvars` is executed, THE Terraform_Root SHALL
   remove all resources created in the LocalStack environment without errors.

---

### Requirement 15: Free Tier Compliance Guardrails

**User Story:** As a cost-conscious engineer, I want the Terraform configuration to structurally
prevent provisioning of paid AWS resources, so that the monthly bill stays within the AWS Always
Free Tier.

#### Acceptance Criteria

1. THE Terraform_Root SHALL NOT define any of the following resource types:
   `aws_ecs_cluster`, `aws_ecs_service`, `aws_ecs_task_definition`, `aws_lb`,
   `aws_lb_listener`, `aws_nat_gateway`, `aws_db_instance`, `aws_rds_cluster`,
   `aws_docdb_cluster`, `aws_elasticache_cluster`, `aws_elasticache_replication_group`.
2. THE Compute_Module SHALL configure all Lambda functions with `reserved_concurrent_executions`
   set to a value no greater than 10 to prevent runaway invocation costs during development.
3. THE Networking_Module SHALL configure the CloudFront distribution with a `price_class` of
   `PriceClass_100` (North America and Europe edge locations only) to minimize data transfer costs.
4. WHEN `terraform plan` is executed, THE Terraform_Root SHALL produce a plan containing only
   resource types that are eligible for the AWS Always Free Tier or have zero fixed monthly cost
   (Lambda, CloudFront, S3, DynamoDB, IAM, ACM, Route 53 records).

---

### Requirement 16: CI/CD Integration Points

**User Story:** As a developer, I want the Terraform configuration to integrate cleanly with the
existing GitHub Actions CI pipeline, so that infrastructure changes are validated automatically on
pull requests and applied on merge to main.

#### Acceptance Criteria

1. THE Terraform_Root SHALL be structured so that `terraform init`, `terraform validate`, and
   `terraform plan` can be executed in a GitHub Actions job using only environment variable
   credentials (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`).
2. THE Terraform_Root SHALL include a `.terraform.lock.hcl` file committed to version control so
   provider versions are pinned and reproducible across CI runs.
3. THE Terraform_Root SHALL declare a minimum required Terraform version of `>= 1.6.0` and a
   minimum AWS provider version of `>= 5.0` in the `required_providers` block.
4. THE Terraform_Root SHALL NOT require any manual steps (e.g., clicking in the AWS console)
   before `terraform apply` can succeed in a fresh AWS account, given that the Remote_State_Backend
   has been bootstrapped.
