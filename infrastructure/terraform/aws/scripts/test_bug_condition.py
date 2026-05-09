#!/usr/bin/env python3
"""
test_bug_condition.py — Bug condition exploration tests for lambda-env-ownership bugfix.

Property 1: Bug Condition — Lambda Environment Variable Ownership Defects

These tests encode the EXPECTED (fixed) behavior. They are designed to:
  - FAIL on unfixed code  → confirms the bugs exist
  - PASS on fixed code    → confirms the bugs are resolved

Run with: python3 -m pytest infrastructure/terraform/scripts/test_bug_condition.py -v

DO NOT fix the tests when they fail on unfixed code.
DO NOT fix the production code until Task 3+ in the implementation plan.
"""

import re
import sys
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
# Path 1: deploy.yml must NOT call update-function-configuration
# ---------------------------------------------------------------------------

class TestDeployYmlNoEnvUpdate:
    """
    Bug path 1: deploy.yml's jq builder calls update-function-configuration with a
    full-replace payload that omits REDIS_URL, KAFKA_*, and SPRING_DATASOURCE_*.
    Every deploy wipes those variables from the live Lambda.

    Expected (fixed): deploy.yml is image-only — no update-function-configuration call.
    """

    def test_no_update_function_configuration_step(self):
        """deploy.yml must not contain an update-function-configuration AWS CLI call."""
        content = read(".github/workflows/deploy.yml")
        # Check for the actual AWS CLI invocation, not just the string in comments
        assert "aws lambda update-function-configuration" not in content, (
            "COUNTEREXAMPLE: deploy.yml still calls 'aws lambda update-function-configuration'.\n"
            "This is the full-replace wipe vector — every deploy run overwrites the entire\n"
            "Lambda Variables map, dropping REDIS_URL, KAFKA_*, and SPRING_DATASOURCE_*.\n"
            "Fix: remove the 'Update Lambda function configuration' step (Task 6.1)."
        )

    def test_no_jq_lambda_env_json_builder(self):
        """deploy.yml must not build a lambda-env.json via jq."""
        content = read(".github/workflows/deploy.yml")
        assert "lambda-env.json" not in content, (
            "COUNTEREXAMPLE: deploy.yml still builds lambda-env.json.\n"
            "The jq builder constructs an incomplete Variables map (11 keys) that\n"
            "silently drops REDIS_URL, KAFKA_*, and SPRING_DATASOURCE_* on every deploy.\n"
            "Fix: remove the 'Update Lambda function configuration' step (Task 6.1)."
        )

    def test_no_install_jq_step(self):
        """deploy.yml must not have an 'Install jq' step (no longer needed after fix)."""
        content = read(".github/workflows/deploy.yml")
        assert "Install jq" not in content, (
            "COUNTEREXAMPLE: deploy.yml still has an 'Install jq' step.\n"
            "jq is only needed for the lambda-env.json builder which must be removed.\n"
            "Fix: remove the 'Install jq' step (Task 6.1)."
        )

    def test_dual_branch_trigger(self):
        """deploy.yml must trigger on both main and architecture/cloud-native-extraction."""
        workflow = load_yaml(".github/workflows/deploy.yml")
        # PyYAML parses the YAML 'on:' key as boolean True
        on_block = workflow.get(True, workflow.get("on", {})) or {}
        branches = on_block.get("push", {}).get("branches", [])
        assert "main" in branches, (
            "COUNTEREXAMPLE: deploy.yml trigger does not include 'main'.\n"
            f"Current branches: {branches}\n"
            "After merging to main, pushes to main do not trigger a deploy,\n"
            "causing the two branches to diverge in deployed state.\n"
            "Fix: add 'main' to the push branches trigger (Task 6.2)."
        )
        assert "architecture/cloud-native-extraction" in branches, (
            "COUNTEREXAMPLE: deploy.yml trigger does not include 'architecture/cloud-native-extraction'.\n"
            f"Current branches: {branches}\n"
            "Fix: ensure both branches are in the push trigger (Task 6.2)."
        )


# ---------------------------------------------------------------------------
# Path 2: sync-secrets.sh must NOT have --lambda flag
# ---------------------------------------------------------------------------

class TestSyncSecretsNoLambdaFlag:
    """
    Bug path 2: sync-secrets.sh --lambda directly calls update-function-configuration,
    bypassing Terraform state and creating drift.

    Expected (fixed): sync-secrets.sh only syncs to GitHub Actions via gh secret set.
    """

    def test_no_lambda_flag_in_sync_script(self):
        """sync-secrets.sh must not accept a --lambda flag."""
        content = read("scripts/sync-secrets.sh")
        assert "--lambda" not in content, (
            "COUNTEREXAMPLE: scripts/sync-secrets.sh still contains '--lambda' flag.\n"
            "This allows direct Lambda env var updates outside Terraform state,\n"
            "creating drift that Terraform cannot detect or reconcile.\n"
            "Fix: remove the --lambda code path (Task 7.1)."
        )

    def test_no_update_function_configuration_in_sync_script(self):
        """sync-secrets.sh must not execute aws lambda update-function-configuration."""
        content = read("scripts/sync-secrets.sh")
        # Check for the actual executable invocation — not warning comments about it.
        # A real call would be on its own line starting with 'aws lambda update-function-configuration'
        # or preceded by whitespace (indented in a shell block).
        import re as _re
        # Match the CLI call as a shell command (not inside a comment line starting with #)
        non_comment_lines = [
            line for line in content.splitlines()
            if not line.lstrip().startswith("#")
        ]
        non_comment_text = "\n".join(non_comment_lines)
        assert "aws lambda update-function-configuration" not in non_comment_text, (
            "COUNTEREXAMPLE: scripts/sync-secrets.sh still executes 'aws lambda update-function-configuration'.\n"
            "Direct Lambda configuration updates bypass Terraform state and cause drift.\n"
            "Fix: remove the Lambda sync section (Task 7.1)."
        )

    def test_gh_secret_set_still_present(self):
        """sync-secrets.sh must still sync to GitHub Actions via gh secret set."""
        content = read("scripts/sync-secrets.sh")
        assert "gh secret set" in content, (
            "REGRESSION: scripts/sync-secrets.sh no longer calls 'gh secret set'.\n"
            "The GitHub Actions secret sync path must be preserved.\n"
            "Fix: ensure gh secret set -f path is intact (Task 7.1)."
        )


# ---------------------------------------------------------------------------
# Path 3: SPRING_PROFILES_ACTIVE must be "prod,aws" in Terraform
# ---------------------------------------------------------------------------

class TestSpringProfilesActive:
    """
    Bug path 3: Terraform sets SPRING_PROFILES_ACTIVE = "aws" (missing "prod").
    Without the prod profile, application-prod.yml never loads, so Spring Boot
    cannot resolve ${REDIS_URL}, ${KAFKA_BOOTSTRAP_SERVERS}, etc.

    Expected (fixed): SPRING_PROFILES_ACTIVE = "prod,aws" in both locals blocks.
    """

    def test_common_env_spring_profiles_active_is_prod_aws(self):
        """common_env in compute/main.tf must set SPRING_PROFILES_ACTIVE to 'prod,aws'."""
        content = read("infrastructure/terraform/modules/compute/main.tf")

        # Find the common_env block and check the value
        # Match: SPRING_PROFILES_ACTIVE = "aws" (the bug) vs "prod,aws" (the fix)
        common_env_match = re.search(
            r'common_env\s*=\s*\{[^}]*SPRING_PROFILES_ACTIVE\s*=\s*"([^"]+)"',
            content,
            re.DOTALL,
        )
        assert common_env_match is not None, (
            "Could not find SPRING_PROFILES_ACTIVE in common_env block.\n"
            "Check infrastructure/terraform/modules/compute/main.tf."
        )
        value = common_env_match.group(1)
        assert value == "prod,aws", (
            f"COUNTEREXAMPLE: common_env.SPRING_PROFILES_ACTIVE = '{value}' (expected 'prod,aws').\n"
            "Without 'prod' in the profile list, application-prod.yml never loads.\n"
            "Spring Boot cannot resolve ${{REDIS_URL}}, ${{KAFKA_BOOTSTRAP_SERVERS}}, etc.\n"
            "Fix: change SPRING_PROFILES_ACTIVE to 'prod,aws' in common_env (Task 3.4)."
        )

    def test_no_standalone_aws_profile_value(self):
        """compute/main.tf must not set SPRING_PROFILES_ACTIVE to bare 'aws'."""
        content = read("infrastructure/terraform/modules/compute/main.tf")
        # Check for the bug pattern: = "aws" (not "prod,aws")
        bug_pattern = re.findall(r'SPRING_PROFILES_ACTIVE\s*=\s*"aws"(?!\s*#)', content)
        assert len(bug_pattern) == 0, (
            f"COUNTEREXAMPLE: Found {len(bug_pattern)} occurrence(s) of "
            f"SPRING_PROFILES_ACTIVE = \"aws\" (bare, without 'prod').\n"
            "This prevents application-prod.yml from loading on Lambda.\n"
            "Fix: change all occurrences to 'prod,aws' (Task 3.4)."
        )


# ---------------------------------------------------------------------------
# Path 4: Terraform must declare and wire REDIS_URL and KAFKA_* variables
# ---------------------------------------------------------------------------

class TestTerraformMissingVariables:
    """
    Bug path 4: Terraform has no redis_url or kafka_* variables.
    These were never added because deploy.yml and sync-secrets.sh --lambda
    were the assumed delivery mechanism. With those removed, Terraform must
    own these variables.

    Expected (fixed): All four variables declared in compute module variables.tf
    AND wired into the appropriate Lambda resource blocks in compute/main.tf.
    """

    REQUIRED_VARS = [
        "redis_url",
        "kafka_bootstrap_servers",
        "kafka_sasl_username",
        "kafka_sasl_password",
    ]

    def test_compute_module_variables_declared(self):
        """All four new variables must be declared in compute/variables.tf."""
        content = read("infrastructure/terraform/modules/compute/variables.tf")
        missing = []
        for var in self.REQUIRED_VARS:
            if f'variable "{var}"' not in content:
                missing.append(var)
        assert not missing, (
            f"COUNTEREXAMPLE: Missing variable declarations in compute/variables.tf: {missing}\n"
            "These variables are needed to pass REDIS_URL and KAFKA_* to Lambda functions.\n"
            "Fix: add variable declarations to compute/variables.tf (Task 3.3)."
        )

    def test_root_variables_declared(self):
        """All four new variables must be declared in root variables.tf."""
        content = read("infrastructure/terraform/variables.tf")
        missing = []
        for var in self.REQUIRED_VARS:
            if f'variable "{var}"' not in content:
                missing.append(var)
        assert not missing, (
            f"COUNTEREXAMPLE: Missing variable declarations in root variables.tf: {missing}\n"
            "Root variables are needed for TF_VAR_* injection from GitHub Secrets.\n"
            "Fix: add variable declarations to root variables.tf (Task 3.1)."
        )

    def test_redis_url_in_api_gateway_lambda_env(self):
        """REDIS_URL must be wired into the api-gateway Lambda environment (via runtime_secrets merge)."""
        content = read("infrastructure/terraform/modules/compute/main.tf")
        api_gw_block = re.search(
            r'resource\s+"aws_lambda_function"\s+"api_gateway"\s*\{.*?^}',
            content,
            re.DOTALL | re.MULTILINE,
        )
        assert api_gw_block is not None, "Could not find aws_lambda_function.api_gateway resource block."
        block = api_gw_block.group(0)
        # REDIS_URL is delivered via local.runtime_secrets merged into the environment block
        assert "local.runtime_secrets" in block, (
            "COUNTEREXAMPLE: local.runtime_secrets is not merged into aws_lambda_function.api_gateway environment.\n"
            "REDIS_URL (and KAFKA_*) are delivered via the runtime_secrets local — it must be in the merge().\n"
            "Fix: add local.runtime_secrets to api-gateway Lambda env merge (Task 3.4)."
        )

    def test_redis_url_in_portfolio_lambda_env(self):
        """REDIS_URL must be wired into the portfolio Lambda environment (via runtime_secrets merge)."""
        content = read("infrastructure/terraform/modules/compute/main.tf")
        portfolio_block = re.search(
            r'resource\s+"aws_lambda_function"\s+"portfolio"\s*\{.*?^}',
            content,
            re.DOTALL | re.MULTILINE,
        )
        assert portfolio_block is not None, "Could not find aws_lambda_function.portfolio resource block."
        assert "local.runtime_secrets" in portfolio_block.group(0), (
            "COUNTEREXAMPLE: local.runtime_secrets is not merged into aws_lambda_function.portfolio environment.\n"
            "REDIS_URL and KAFKA_* are delivered via runtime_secrets — it must be in the merge().\n"
            "Fix: add local.runtime_secrets to portfolio Lambda env merge (Task 3.4)."
        )

    def test_redis_url_in_insight_lambda_env(self):
        """REDIS_URL must be wired into the insight Lambda environment (via runtime_secrets merge)."""
        content = read("infrastructure/terraform/modules/compute/main.tf")
        insight_block = re.search(
            r'resource\s+"aws_lambda_function"\s+"insight"\s*\{.*?^}',
            content,
            re.DOTALL | re.MULTILINE,
        )
        assert insight_block is not None, "Could not find aws_lambda_function.insight resource block."
        assert "local.runtime_secrets" in insight_block.group(0), (
            "COUNTEREXAMPLE: local.runtime_secrets is not merged into aws_lambda_function.insight environment.\n"
            "REDIS_URL and KAFKA_* are delivered via runtime_secrets — it must be in the merge().\n"
            "Fix: add local.runtime_secrets to insight Lambda env merge (Task 3.4)."
        )

    def test_kafka_vars_in_portfolio_lambda_env(self):
        """KAFKA_* vars must be wired into the portfolio Lambda environment (via runtime_secrets merge)."""
        content = read("infrastructure/terraform/modules/compute/main.tf")
        portfolio_block = re.search(
            r'resource\s+"aws_lambda_function"\s+"portfolio"\s*\{.*?^}',
            content,
            re.DOTALL | re.MULTILINE,
        )
        assert portfolio_block is not None, "Could not find aws_lambda_function.portfolio resource block."
        assert "local.runtime_secrets" in portfolio_block.group(0), (
            "COUNTEREXAMPLE: local.runtime_secrets is not merged into aws_lambda_function.portfolio environment.\n"
            "KAFKA_BOOTSTRAP_SERVERS/USERNAME/PASSWORD are in runtime_secrets — it must be in the merge().\n"
            "Fix: add local.runtime_secrets to portfolio Lambda env merge (Task 3.4)."
        )

    def test_kafka_vars_in_market_data_lambda_env(self):
        """KAFKA_* vars must be wired into the market-data Lambda environment (via runtime_secrets merge)."""
        content = read("infrastructure/terraform/modules/compute/main.tf")
        market_data_block = re.search(
            r'resource\s+"aws_lambda_function"\s+"market_data"\s*\{.*?^}',
            content,
            re.DOTALL | re.MULTILINE,
        )
        assert market_data_block is not None, "Could not find aws_lambda_function.market_data resource block."
        assert "local.runtime_secrets" in market_data_block.group(0), (
            "COUNTEREXAMPLE: local.runtime_secrets is not merged into aws_lambda_function.market_data environment.\n"
            "KAFKA_BOOTSTRAP_SERVERS/USERNAME/PASSWORD are in runtime_secrets — it must be in the merge().\n"
            "Fix: add local.runtime_secrets to market-data Lambda env merge (Task 3.4)."
        )

    def test_kafka_vars_in_insight_lambda_env(self):
        """KAFKA_* vars must be wired into the insight Lambda environment (via runtime_secrets merge)."""
        content = read("infrastructure/terraform/modules/compute/main.tf")
        insight_block = re.search(
            r'resource\s+"aws_lambda_function"\s+"insight"\s*\{.*?^}',
            content,
            re.DOTALL | re.MULTILINE,
        )
        assert insight_block is not None, "Could not find aws_lambda_function.insight resource block."
        assert "local.runtime_secrets" in insight_block.group(0), (
            "COUNTEREXAMPLE: local.runtime_secrets is not merged into aws_lambda_function.insight environment.\n"
            "KAFKA_BOOTSTRAP_SERVERS/USERNAME/PASSWORD are in runtime_secrets — it must be in the merge().\n"
            "Fix: add local.runtime_secrets to insight Lambda env merge (Task 3.4)."
        )

    def test_runtime_secrets_local_defined_with_all_keys(self):
        """runtime_secrets local in compute/main.tf must define all four secret keys."""
        content = read("infrastructure/terraform/modules/compute/main.tf")
        runtime_secrets_block = re.search(
            r'runtime_secrets\s*=\s*\{([^}]+)\}',
            content,
            re.DOTALL,
        )
        assert runtime_secrets_block is not None, (
            "COUNTEREXAMPLE: runtime_secrets local block not found in compute/main.tf.\n"
            "Fix: add local.runtime_secrets with REDIS_URL and KAFKA_* keys (Task 3.4)."
        )
        block = runtime_secrets_block.group(1)
        for key in ["REDIS_URL", "KAFKA_BOOTSTRAP_SERVERS", "KAFKA_SASL_USERNAME", "KAFKA_SASL_PASSWORD"]:
            assert key in block, (
                f"COUNTEREXAMPLE: '{key}' not found in runtime_secrets local block.\n"
                f"Fix: add {key} = var.{key.lower()} to runtime_secrets (Task 3.4)."
            )

    def test_terraform_yml_has_redis_url_tf_var(self):
        """terraform.yml must map secrets.REDIS_URL to TF_VAR_redis_url."""
        content = read(".github/workflows/terraform.yml")
        assert "TF_VAR_redis_url" in content, (
            "COUNTEREXAMPLE: terraform.yml does not map secrets.REDIS_URL to TF_VAR_redis_url.\n"
            "Without this mapping, Terraform plan/apply receives no value for redis_url,\n"
            "and the Lambda environment will have an empty or missing REDIS_URL.\n"
            "Fix: add TF_VAR_redis_url to terraform.yml env block (Task 4.1)."
        )

    def test_terraform_yml_has_kafka_tf_vars(self):
        """terraform.yml must map Kafka secrets to TF_VAR_kafka_* variables."""
        content = read(".github/workflows/terraform.yml")
        for tf_var in [
            "TF_VAR_kafka_bootstrap_servers",
            "TF_VAR_kafka_sasl_username",
            "TF_VAR_kafka_sasl_password",
        ]:
            assert tf_var in content, (
                f"COUNTEREXAMPLE: terraform.yml does not contain '{tf_var}'.\n"
                "Without this mapping, Terraform plan/apply receives no Kafka credentials,\n"
                "and Lambda functions will have empty KAFKA_* environment variables.\n"
                f"Fix: add {tf_var} to terraform.yml env block (Task 4.1)."
            )
