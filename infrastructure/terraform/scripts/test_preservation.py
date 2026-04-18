#!/usr/bin/env python3
"""
test_preservation.py — Preservation property tests for lambda-env-ownership bugfix.

Property 2: Preservation — Existing Lambda Configuration and Pipeline Behavior

These tests observe baseline behavior on UNFIXED code and assert structural invariants
that must remain unchanged after the fix is applied.

Run with: python3 -m pytest infrastructure/terraform/scripts/test_preservation.py -v

EXPECTED OUTCOME on unfixed code: ALL PASS (confirms baseline to preserve).
EXPECTED OUTCOME on fixed code:   ALL PASS (confirms no regressions introduced).
"""

import re
from pathlib import Path

import pytest
import yaml

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parents[3]


def read(relative_path: str) -> str:
    return (REPO_ROOT / relative_path).read_text(encoding="utf-8")


def load_yaml(relative_path: str) -> dict:
    return yaml.safe_load(read(relative_path))


# ---------------------------------------------------------------------------
# Preservation: Existing Lambda environment variables
# ---------------------------------------------------------------------------

class TestExistingLambdaEnvVarsPreserved:
    """
    Verify that existing Lambda environment variables are not dropped by the fix.
    These are the variables already in Terraform that must remain intact.
    """

    def test_api_gateway_has_auth_jwk_uri(self):
        """api-gateway Lambda must retain AUTH_JWK_URI for JWT validation."""
        content = read("infrastructure/terraform/modules/compute/main.tf")
        api_gw_block = re.search(
            r'resource\s+"aws_lambda_function"\s+"api_gateway"\s*\{.*?^}',
            content,
            re.DOTALL | re.MULTILINE,
        )
        assert api_gw_block is not None, "Could not find aws_lambda_function.api_gateway."
        assert "AUTH_JWK_URI" in api_gw_block.group(0), (
            "REGRESSION: AUTH_JWK_URI missing from api-gateway Lambda environment.\n"
            "JWT validation will fail without this variable."
        )

    def test_api_gateway_has_cloudfront_origin_secret(self):
        """api-gateway Lambda must retain CLOUDFRONT_ORIGIN_SECRET."""
        content = read("infrastructure/terraform/modules/compute/main.tf")
        api_gw_block = re.search(
            r'resource\s+"aws_lambda_function"\s+"api_gateway"\s*\{.*?^}',
            content,
            re.DOTALL | re.MULTILINE,
        )
        assert api_gw_block is not None, "Could not find aws_lambda_function.api_gateway."
        assert "CLOUDFRONT_ORIGIN_SECRET" in api_gw_block.group(0), (
            "REGRESSION: CLOUDFRONT_ORIGIN_SECRET missing from api-gateway Lambda environment.\n"
            "CloudFront origin security will fail without this variable."
        )

    def test_api_gateway_has_service_urls(self):
        """api-gateway Lambda must retain PORTFOLIO_SERVICE_URL, MARKET_DATA_SERVICE_URL, INSIGHT_SERVICE_URL."""
        content = read("infrastructure/terraform/modules/compute/main.tf")
        api_gw_block = re.search(
            r'resource\s+"aws_lambda_function"\s+"api_gateway"\s*\{.*?^}',
            content,
            re.DOTALL | re.MULTILINE,
        )
        assert api_gw_block is not None, "Could not find aws_lambda_function.api_gateway."
        block = api_gw_block.group(0)
        for url_var in ["PORTFOLIO_SERVICE_URL", "MARKET_DATA_SERVICE_URL", "INSIGHT_SERVICE_URL"]:
            assert url_var in block, (
                f"REGRESSION: {url_var} missing from api-gateway Lambda environment.\n"
                "Service-to-service routing will fail without this variable."
            )

    def test_api_gateway_has_server_port_and_port(self):
        """api-gateway Lambda must retain SERVER_PORT and PORT."""
        content = read("infrastructure/terraform/modules/compute/main.tf")
        api_gw_block = re.search(
            r'resource\s+"aws_lambda_function"\s+"api_gateway"\s*\{.*?^}',
            content,
            re.DOTALL | re.MULTILINE,
        )
        assert api_gw_block is not None, "Could not find aws_lambda_function.api_gateway."
        block = api_gw_block.group(0)
        assert "SERVER_PORT" in block, "REGRESSION: SERVER_PORT missing from api-gateway Lambda environment."
        assert "PORT" in block, "REGRESSION: PORT missing from api-gateway Lambda environment."

    def test_portfolio_has_spring_datasource_url(self):
        """portfolio Lambda must retain SPRING_DATASOURCE_URL for PostgreSQL connectivity."""
        content = read("infrastructure/terraform/modules/compute/main.tf")
        portfolio_block = re.search(
            r'resource\s+"aws_lambda_function"\s+"portfolio"\s*\{.*?^}',
            content,
            re.DOTALL | re.MULTILINE,
        )
        assert portfolio_block is not None, "Could not find aws_lambda_function.portfolio."
        assert "SPRING_DATASOURCE_URL" in portfolio_block.group(0), (
            "REGRESSION: SPRING_DATASOURCE_URL missing from portfolio Lambda environment.\n"
            "PostgreSQL connectivity will fail without this variable."
        )

    def test_market_data_has_spring_data_mongodb_uri(self):
        """market-data Lambda must retain SPRING_DATA_MONGODB_URI for MongoDB connectivity."""
        content = read("infrastructure/terraform/modules/compute/main.tf")
        market_data_block = re.search(
            r'resource\s+"aws_lambda_function"\s+"market_data"\s*\{.*?^}',
            content,
            re.DOTALL | re.MULTILINE,
        )
        assert market_data_block is not None, "Could not find aws_lambda_function.market_data."
        assert "SPRING_DATA_MONGODB_URI" in market_data_block.group(0), (
            "REGRESSION: SPRING_DATA_MONGODB_URI missing from market-data Lambda environment.\n"
            "MongoDB connectivity will fail without this variable."
        )


# ---------------------------------------------------------------------------
# Preservation: common_env structure
# ---------------------------------------------------------------------------

class TestCommonEnvStructurePreserved:
    """
    Verify that the common_env locals block retains all existing keys.
    The fix adds new variables but must not remove existing ones.
    """

    REQUIRED_COMMON_ENV_KEYS = [
        "JAVA_TOOL_OPTIONS",
        "AWS_LAMBDA_EXEC_WRAPPER",
        "PORT",
        "AWS_LWA_ASYNC_INIT",
        "AWS_LWA_READINESS_CHECK_PATH",
        "SPRING_PROFILES_ACTIVE",
    ]

    def test_common_env_retains_all_keys(self):
        """common_env must retain all existing keys after the fix."""
        content = read("infrastructure/terraform/modules/compute/main.tf")
        common_env_block = re.search(
            r'common_env\s*=\s*\{([^}]+)\}',
            content,
            re.DOTALL,
        )
        assert common_env_block is not None, "Could not find common_env block in compute/main.tf."
        block = common_env_block.group(1)
        missing = [k for k in self.REQUIRED_COMMON_ENV_KEYS if k not in block]
        assert not missing, (
            f"REGRESSION: common_env is missing keys: {missing}\n"
            "These keys must be preserved for all Zip-based Lambda functions."
        )

    def test_api_gateway_container_env_excludes_exec_wrapper(self):
        """api_gateway_container_env must NOT contain AWS_LAMBDA_EXEC_WRAPPER.

        api-gateway is an Image-based Lambda — the Dockerfile ENTRYPOINT handles
        the Lambda Web Adapter. Setting AWS_LAMBDA_EXEC_WRAPPER on an Image Lambda
        would conflict with the container's own entrypoint.
        """
        content = read("infrastructure/terraform/modules/compute/main.tf")
        container_env_block = re.search(
            r'api_gateway_container_env\s*=\s*\{([^}]+)\}',
            content,
            re.DOTALL,
        )
        assert container_env_block is not None, (
            "Could not find api_gateway_container_env block in compute/main.tf."
        )
        block = container_env_block.group(1)
        assert "AWS_LAMBDA_EXEC_WRAPPER" not in block, (
            "REGRESSION: api_gateway_container_env contains AWS_LAMBDA_EXEC_WRAPPER.\n"
            "Image-based Lambdas must NOT set this variable — the Dockerfile ENTRYPOINT\n"
            "handles the Lambda Web Adapter via the extension sidecar pattern."
        )


# ---------------------------------------------------------------------------
# Preservation: deploy.yml image build and deploy flow
# ---------------------------------------------------------------------------

class TestDeployYmlImageFlowPreserved:
    """
    Verify that deploy.yml's image build, ECR push, and update-function-code
    flow is completely preserved after removing the config update step.
    """

    def test_deploy_backend_job_exists(self):
        """deploy.yml must still have a deploy-backend job."""
        workflow = load_yaml(".github/workflows/deploy.yml")
        assert "deploy-backend" in workflow.get("jobs", {}), (
            "REGRESSION: deploy-backend job missing from deploy.yml."
        )

    def test_docker_buildx_build_step_present(self):
        """deploy.yml must still build the Docker image via docker buildx build."""
        content = read(".github/workflows/deploy.yml")
        assert "docker buildx build" in content, (
            "REGRESSION: 'docker buildx build' step missing from deploy.yml.\n"
            "The api-gateway container image build must be preserved."
        )

    def test_ecr_push_step_present(self):
        """deploy.yml must still push to ECR."""
        content = read(".github/workflows/deploy.yml")
        assert "amazon-ecr-login" in content, (
            "REGRESSION: ECR login step missing from deploy.yml.\n"
            "The ECR push flow must be preserved."
        )

    def test_update_function_code_step_present(self):
        """deploy.yml must still call update-function-code to deploy the image."""
        content = read(".github/workflows/deploy.yml")
        assert "update-function-code" in content, (
            "REGRESSION: 'update-function-code' step missing from deploy.yml.\n"
            "The Lambda image deployment step must be preserved."
        )

    def test_last_update_status_check_present(self):
        """deploy.yml must still poll LastUpdateStatus before updating function code."""
        content = read(".github/workflows/deploy.yml")
        assert "LastUpdateStatus" in content, (
            "REGRESSION: LastUpdateStatus polling missing from deploy.yml.\n"
            "The status check prevents code updates while a prior update is in progress."
        )


# ---------------------------------------------------------------------------
# Preservation: deploy-frontend job
# ---------------------------------------------------------------------------

class TestDeployFrontendJobPreserved:
    """
    Verify that the deploy-frontend job is completely unchanged.
    The fix only touches the deploy-backend job.
    """

    def test_deploy_frontend_job_exists(self):
        """deploy.yml must still have a deploy-frontend job."""
        workflow = load_yaml(".github/workflows/deploy.yml")
        assert "deploy-frontend" in workflow.get("jobs", {}), (
            "REGRESSION: deploy-frontend job missing from deploy.yml."
        )

    def test_frontend_npm_build_present(self):
        """deploy.yml must still build the Next.js static export."""
        content = read(".github/workflows/deploy.yml")
        assert "npm run build" in content, (
            "REGRESSION: 'npm run build' step missing from deploy.yml.\n"
            "The Next.js static export build must be preserved."
        )

    def test_frontend_s3_sync_present(self):
        """deploy.yml must still sync the frontend to S3."""
        content = read(".github/workflows/deploy.yml")
        assert "aws s3 sync" in content, (
            "REGRESSION: 'aws s3 sync' step missing from deploy.yml.\n"
            "The S3 sync for the frontend static export must be preserved."
        )

    def test_frontend_cloudfront_invalidation_present(self):
        """deploy.yml must still invalidate CloudFront after S3 sync."""
        content = read(".github/workflows/deploy.yml")
        assert "create-invalidation" in content, (
            "REGRESSION: CloudFront invalidation step missing from deploy.yml.\n"
            "The CloudFront cache invalidation must be preserved."
        )


# ---------------------------------------------------------------------------
# Preservation: terraform.yml pipeline structure
# ---------------------------------------------------------------------------

class TestTerraformYmlPipelinePreserved:
    """
    Verify that the terraform.yml pipeline structure is unchanged.
    The fix only adds new TF_VAR_* entries to the env block.
    """

    def test_validate_job_exists(self):
        """terraform.yml must still have a validate job."""
        workflow = load_yaml(".github/workflows/terraform.yml")
        assert "validate" in workflow.get("jobs", {}), (
            "REGRESSION: validate job missing from terraform.yml."
        )

    def test_apply_job_exists(self):
        """terraform.yml must still have an apply job."""
        workflow = load_yaml(".github/workflows/terraform.yml")
        assert "apply" in workflow.get("jobs", {}), (
            "REGRESSION: apply job missing from terraform.yml."
        )

    def test_terraform_plan_step_present(self):
        """terraform.yml must still run terraform plan."""
        content = read(".github/workflows/terraform.yml")
        assert "terraform plan" in content, (
            "REGRESSION: 'terraform plan' step missing from terraform.yml."
        )

    def test_assert_plan_step_present(self):
        """terraform.yml must still run assert_plan.py."""
        content = read(".github/workflows/terraform.yml")
        assert "assert_plan.py" in content, (
            "REGRESSION: assert_plan.py step missing from terraform.yml.\n"
            "The correctness property checks must be preserved."
        )

    def test_terraform_apply_step_present(self):
        """terraform.yml must still run terraform apply."""
        content = read(".github/workflows/terraform.yml")
        assert "terraform apply" in content, (
            "REGRESSION: 'terraform apply' step missing from terraform.yml."
        )

    def test_existing_tf_var_mappings_preserved(self):
        """terraform.yml must retain all existing TF_VAR_* mappings."""
        content = read(".github/workflows/terraform.yml")
        existing_vars = [
            "TF_VAR_postgres_connection_string",
            "TF_VAR_mongodb_connection_string",
            "TF_VAR_auth_jwk_uri",
            "TF_VAR_cloudfront_origin_secret",
            "TF_VAR_db_username",
            "TF_VAR_db_password",
            "TF_VAR_api_gateway_image_uri",
        ]
        missing = [v for v in existing_vars if v not in content]
        assert not missing, (
            f"REGRESSION: terraform.yml is missing existing TF_VAR_* mappings: {missing}\n"
            "All existing secret mappings must be preserved."
        )


# ---------------------------------------------------------------------------
# Preservation: sync-secrets.sh gh secret set path
# ---------------------------------------------------------------------------

class TestSyncSecretsGhSecretSetPreserved:
    """
    Verify that the gh secret set path in sync-secrets.sh is preserved.
    The fix removes the --lambda path but must not touch the GitHub sync path.
    """

    def test_gh_secret_set_f_present(self):
        """sync-secrets.sh must still call 'gh secret set -f'."""
        content = read("scripts/sync-secrets.sh")
        assert "gh secret set -f" in content, (
            "REGRESSION: 'gh secret set -f' missing from scripts/sync-secrets.sh.\n"
            "The GitHub Actions secret sync path must be preserved."
        )

    def test_env_file_argument_handling_present(self):
        """sync-secrets.sh must still accept an .env file as the first argument."""
        content = read("scripts/sync-secrets.sh")
        assert "ENV_FILE" in content, (
            "REGRESSION: ENV_FILE argument handling missing from scripts/sync-secrets.sh.\n"
            "The script must still accept an .env file as input."
        )


# ---------------------------------------------------------------------------
# Preservation: assert_plan.py checks
# ---------------------------------------------------------------------------

class TestAssertPlanChecksPreserved:
    """
    Verify that all existing assert_plan.py assertion functions are intact.
    The fix may optionally enhance assert_spring_profiles_active but must not
    remove or break any existing checks.
    """

    REQUIRED_FUNCTIONS = [
        "assert_no_prohibited_resources",
        "assert_all_lambda_functions_present",
        "assert_lambda_concurrency_cap",
        "assert_spring_profiles_active",
        "assert_cloudfront_price_class",
        "assert_route53_record_type",
    ]

    def test_all_assertion_functions_present(self):
        """assert_plan.py must retain all existing assertion functions."""
        content = read("infrastructure/terraform/scripts/assert_plan.py")
        missing = [f for f in self.REQUIRED_FUNCTIONS if f"def {f}" not in content]
        assert not missing, (
            f"REGRESSION: assert_plan.py is missing assertion functions: {missing}\n"
            "All existing correctness property checks must be preserved."
        )

    def test_prohibited_resource_types_set_intact(self):
        """assert_plan.py must retain the PROHIBITED_RESOURCE_TYPES set."""
        content = read("infrastructure/terraform/scripts/assert_plan.py")
        assert "PROHIBITED_RESOURCE_TYPES" in content, (
            "REGRESSION: PROHIBITED_RESOURCE_TYPES set missing from assert_plan.py."
        )
        # Spot-check a few critical entries
        for resource_type in ["aws_nat_gateway", "aws_db_instance", "aws_ecs_cluster"]:
            assert resource_type in content, (
                f"REGRESSION: '{resource_type}' missing from PROHIBITED_RESOURCE_TYPES.\n"
                "Free-tier guardrails must be preserved."
            )

    def test_required_lambda_functions_set_intact(self):
        """assert_plan.py must retain the REQUIRED_LAMBDA_FUNCTIONS set."""
        content = read("infrastructure/terraform/scripts/assert_plan.py")
        assert "REQUIRED_LAMBDA_FUNCTIONS" in content, (
            "REGRESSION: REQUIRED_LAMBDA_FUNCTIONS set missing from assert_plan.py."
        )
        for fn in ["wealth-api-gateway", "wealth-portfolio-service",
                   "wealth-market-data-service", "wealth-insight-service"]:
            assert fn in content, (
                f"REGRESSION: '{fn}' missing from REQUIRED_LAMBDA_FUNCTIONS.\n"
                "All four Lambda functions must be validated in the plan."
            )
