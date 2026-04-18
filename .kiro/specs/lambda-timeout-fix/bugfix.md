# Bugfix Requirements Document

## Introduction

All four Lambda functions (api-gateway, portfolio-service, market-data-service, insight-service) time out when deployed to AWS Lambda. The root causes are a combination of incorrect Docker ENTRYPOINT configuration for container-image Lambdas, a port mismatch in the insight-service Dockerfile, insufficient Lambda timeout values for Spring Boot cold starts, missing async init configuration for the Lambda Web Adapter, and a SnapStart misconfiguration with an invalid handler class on Zip-based Lambdas. Together, these issues prevent any Lambda function from successfully completing initialization and handling HTTP requests.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN api-gateway Lambda is invoked THEN the system times out because the Docker ENTRYPOINT is set to `/opt/extensions/aws-lambda-web-adapter` which runs the adapter binary as the main process instead of the Java application, preventing the Spring Boot app from starting

1.2 WHEN insight-service Lambda is invoked via its Docker image THEN the system times out because the ENTRYPOINT is set to `/opt/extensions/aws-lambda-web-adapter` (same issue as api-gateway) AND the Lambda Web Adapter polls port 8083 (`AWS_LWA_PORT=8083` in Dockerfile) while Terraform sets `PORT=8080` causing Spring Boot to listen on 8080, so the adapter never detects the app as ready

1.3 WHEN any of the four Lambda functions experiences a cold start THEN the system times out because the Lambda timeout is set to 30 seconds which is insufficient for Spring Boot 4 with AOT initialization, especially for functions with 1024 MB memory (market-data-service, insight-service) where JVM + framework init can take 10-20 seconds

1.4 WHEN any Lambda function's Spring Boot initialization exceeds 10 seconds (the Lambda init phase limit) THEN the remaining init time counts against the function timeout because `AWS_LWA_ASYNC_INIT` is not enabled, causing the function to time out before the application is ready to handle requests

1.5 WHEN portfolio-service, market-data-service, or insight-service Zip-based Lambdas are deployed with SnapStart enabled THEN SnapStart fails silently because the handler is set to `not.used` which is not a valid class, meaning every invocation performs a full cold start instead of restoring from a snapshot

1.6 WHEN api-gateway Lambda routes requests to downstream services THEN the system times out or returns errors because the `PORTFOLIO_SERVICE_URL`, `MARKET_DATA_SERVICE_URL`, and `INSIGHT_SERVICE_URL` environment variables in the Terraform `aws_lambda_function.api_gateway` resource are sourced from root variables that default to empty strings, requiring manual entry in AWS Console rather than being programmatically wired from the Terraform-managed Function URL outputs

1.7 WHEN the Lambda Web Adapter performs its readiness check on any Lambda function THEN the adapter polls the root path `/` (the default) instead of the Spring Boot Actuator health endpoint `/actuator/health`, causing the readiness check to fail or return unexpected responses because the root path may not return a 200 status on all services

### Expected Behavior (Correct)

2.1 WHEN api-gateway Lambda is invoked THEN the system SHALL start the Java application as the main process via ENTRYPOINT and let Lambda automatically load the web adapter extension from `/opt/extensions/`, allowing the Spring Boot app to start and handle requests successfully

2.2 WHEN insight-service Lambda is invoked via its Docker image THEN the system SHALL start the Java application as the main process via ENTRYPOINT, and the Lambda Web Adapter port (`AWS_LWA_PORT`) SHALL match the port Spring Boot actually listens on so the adapter's readiness check succeeds

2.3 WHEN any of the four Lambda functions experiences a cold start THEN the system SHALL have a timeout value of at least 60 seconds to accommodate Spring Boot 4 AOT initialization time, ensuring the function completes startup and handles the first request without timing out

2.4 WHEN any Lambda function's Spring Boot initialization takes longer than 10 seconds THEN the system SHALL have `AWS_LWA_ASYNC_INIT=true` configured so the Lambda Web Adapter reports a successful init to the Lambda runtime immediately, allowing the remaining Spring Boot startup to continue without counting against the function timeout

2.5 WHEN portfolio-service, market-data-service, or insight-service Zip-based Lambdas are deployed with SnapStart THEN the system SHALL use a valid Spring Boot Lambda handler class (or remove SnapStart if no valid handler exists for the Web Adapter pattern) so that cold start optimization works correctly

2.6 WHEN api-gateway Lambda is deployed via Terraform THEN the system SHALL programmatically wire the downstream service Function URLs (`PORTFOLIO_SERVICE_URL`, `MARKET_DATA_SERVICE_URL`, `INSIGHT_SERVICE_URL`) from the Terraform-managed `aws_lambda_function_url` outputs into the api-gateway Lambda environment variables, eliminating the need for manual entry or a two-phase apply pattern

2.7 WHEN any Lambda function starts up with the Lambda Web Adapter THEN the system SHALL have `AWS_LWA_READINESS_CHECK_PATH=/actuator/health` configured in the Lambda environment variables so the adapter polls the correct Spring Boot Actuator health endpoint for readiness instead of the default root path `/`

### Unchanged Behavior (Regression Prevention)

3.1 WHEN api-gateway Lambda receives HTTP requests after successful cold start THEN the system SHALL CONTINUE TO route requests to downstream services (portfolio-service, market-data-service, insight-service) via their Function URLs, now programmatically wired from Terraform outputs instead of manually entered

3.2 WHEN portfolio-service and market-data-service Zip-based Lambdas are invoked THEN the system SHALL CONTINUE TO use the correct ENTRYPOINT pattern (`java -jar`) already present in their Dockerfiles, with the Lambda Web Adapter loaded via the Lambda layer mechanism

3.3 WHEN Lambda functions are running in warm state (no cold start) THEN the system SHALL CONTINUE TO handle requests within normal response times without being affected by the timeout or async init changes

3.4 WHEN Lambda functions are deployed via the existing CI/CD pipeline (deploy.yml) THEN the system SHALL CONTINUE TO build, push, and update Lambda functions using the same workflow steps, with the Dockerfile changes being picked up automatically on next image build

3.5 WHEN Lambda functions run locally via Docker Compose or the Lambda Runtime Interface Emulator THEN the system SHALL CONTINUE TO function correctly for local development, with the ENTRYPOINT change not breaking local execution

3.6 WHEN Terraform applies infrastructure changes THEN the system SHALL CONTINUE TO manage IAM roles, Function URLs, aliases, VPC configuration, and all other existing compute module resources without disruption

3.7 WHEN the two-phase apply pattern variables (`portfolio_function_url`, `market_data_function_url`, `insight_function_url`) are provided via `TF_VAR_*` or `terraform.tfvars` THEN the system SHALL CONTINUE TO accept them as overrides, but they SHALL no longer be required since the programmatic wiring provides the values automatically
