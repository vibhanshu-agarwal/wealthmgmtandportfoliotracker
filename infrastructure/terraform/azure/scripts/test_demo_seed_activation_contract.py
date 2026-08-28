#!/usr/bin/env python3
"""
Structural tests for Spec A checkpoint 9.12 demo-seed activation source wiring.

Asserts that the persisted HCL encodes the five invariants required before the
9.12 workflow profiles may override demo seeding at deploy time:

  - variable "demo_seed_on_startup" exists, type bool, default false
  - only module "portfolio_service" wires APP_DEMO_SEED_ON_STARTUP
  - no wiring to other services, jobs, or secret maps
  - application.yml still defaults app.demo.seed-on-startup to false
  - portfolio-service replica and ingress settings remain unchanged

These tests run against the source HCL files, not a plan fixture.
"""
from __future__ import annotations

import re
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
AZURE_TF_DIR = SCRIPTS_DIR.parent
MAIN_TF = AZURE_TF_DIR / "main.tf"
VARIABLES_TF = AZURE_TF_DIR / "variables.tf"
APPLICATION_YML = (
    SCRIPTS_DIR.parents[3]
    / "portfolio-service"
    / "src"
    / "main"
    / "resources"
    / "application.yml"
)

_WIRE_PATTERN = re.compile(
    r"APP_DEMO_SEED_ON_STARTUP\s*=\s*tostring\(var\.demo_seed_on_startup\)"
)
_TX_DIAG_WIRE_PATTERN = re.compile(
    r"APP_DEMO_TX_DIAGNOSTICS\s*=\s*tostring\(var\.demo_tx_diagnostics\)"
)
_MODULE_HEADER = re.compile(r'module\s+"([^"]+)"\s*\{')


def _extract_block(text: str, header_pattern: re.Pattern[str]) -> str:
    """Return the inner content of the first HCL block matching header_pattern."""
    m = header_pattern.search(text)
    if m is None:
        raise ValueError(f"Block not found: {header_pattern.pattern}")
    start = m.end()
    depth = 1
    pos = start
    while pos < len(text) and depth > 0:
        ch = text[pos]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
        pos += 1
    return text[start : pos - 1]


def _extract_module_block(text: str, module_name: str) -> str:
    pattern = re.compile(rf'module\s+"{re.escape(module_name)}"\s*\{{')
    return _extract_block(text, pattern)


def _extract_resource_block(text: str, resource_type: str, resource_name: str) -> str:
    pattern = re.compile(
        rf'resource\s+"{re.escape(resource_type)}"\s+"{re.escape(resource_name)}"\s*\{{'
    )
    return _extract_block(text, pattern)


def _int_attr(block: str, name: str) -> int:
    m = re.search(rf"^\s*{re.escape(name)}\s*=\s*(\d+)", block, re.MULTILINE)
    if m is None:
        raise ValueError(f"Attribute '{name}' not found in block")
    return int(m.group(1))


def _bool_attr(block: str, name: str) -> bool:
    m = re.search(rf"^\s*{re.escape(name)}\s*=\s*(true|false)", block, re.MULTILINE)
    if m is None:
        raise ValueError(f"Attribute '{name}' not found in block")
    return m.group(1) == "true"


class DemoSeedActivationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        for path in (MAIN_TF, VARIABLES_TF, APPLICATION_YML):
            if not path.exists():
                raise FileNotFoundError(f"Required file not found: {path}")
        cls.main_tf = MAIN_TF.read_text(encoding="utf-8")
        cls.variables_tf = VARIABLES_TF.read_text(encoding="utf-8")
        cls.application_yml = APPLICATION_YML.read_text(encoding="utf-8")
        cls.portfolio_block = _extract_module_block(cls.main_tf, "portfolio_service")

    def test_demo_seed_variable_exists_with_bool_default_false(self) -> None:
        """variable demo_seed_on_startup must exist with type bool and default false."""
        block = _extract_block(
            self.variables_tf,
            re.compile(r'variable\s+"demo_seed_on_startup"\s*\{'),
        )
        self.assertRegex(block, r'\btype\s*=\s*bool\b', "demo_seed_on_startup must have type bool")
        self.assertRegex(
            block,
            r'\bdefault\s*=\s*false\b',
            "demo_seed_on_startup must default to false",
        )

    def test_portfolio_service_wires_demo_seed_env(self) -> None:
        """portfolio_service env_vars must wire APP_DEMO_SEED_ON_STARTUP from the variable."""
        self.assertRegex(
            self.portfolio_block,
            _WIRE_PATTERN,
            msg="portfolio_service must wire APP_DEMO_SEED_ON_STARTUP = tostring(var.demo_seed_on_startup)",
        )

    def test_only_portfolio_service_wires_demo_seed_env(self) -> None:
        """The demo-seed wire line must appear only inside module portfolio_service."""
        occurrences = [m.start() for m in _WIRE_PATTERN.finditer(self.main_tf)]
        self.assertEqual(
            len(occurrences),
            1,
            "Expected exactly one APP_DEMO_SEED_ON_STARTUP = tostring(var.demo_seed_on_startup) in main.tf",
        )
        portfolio_start = self.main_tf.find('module "portfolio_service"')
        portfolio_block = _extract_module_block(self.main_tf, "portfolio_service")
        portfolio_end = portfolio_start + len('module "portfolio_service"') + len(portfolio_block) + 2
        self.assertTrue(
            portfolio_start <= occurrences[0] < portfolio_end,
            "APP_DEMO_SEED_ON_STARTUP wiring must live inside module portfolio_service",
        )

    def test_demo_seed_not_wired_to_other_services(self) -> None:
        """Other container-app modules must not reference demo_seed_on_startup or APP_DEMO_SEED."""
        for module_name in ("api_gateway", "market_data_service", "insight_service"):
            block = _extract_module_block(self.main_tf, module_name)
            self.assertNotIn(
                "demo_seed_on_startup",
                block,
                f"module {module_name} must not reference demo_seed_on_startup",
            )
            self.assertNotIn(
                "APP_DEMO_SEED_ON_STARTUP",
                block,
                f"module {module_name} must not wire APP_DEMO_SEED_ON_STARTUP",
            )

    def test_demo_seed_not_wired_to_jobs(self) -> None:
        """Neither ACA Job must reference demo_seed_on_startup or APP_DEMO_SEED_ON_STARTUP."""
        for job_name in ("market_data_refresh", "market_data_repair"):
            block = _extract_resource_block(
                self.main_tf, "azurerm_container_app_job", job_name
            )
            self.assertNotIn(
                "demo_seed_on_startup",
                block,
                f"job {job_name} must not reference demo_seed_on_startup",
            )
            self.assertNotIn(
                "APP_DEMO_SEED_ON_STARTUP",
                block,
                f"job {job_name} must not wire APP_DEMO_SEED_ON_STARTUP",
            )

    def test_demo_seed_not_in_secret_maps(self) -> None:
        """demo_seed_on_startup must not appear in any secret_env_vars or secrets map."""
        for module_name in ("api_gateway", "portfolio_service", "market_data_service", "insight_service"):
            block = _extract_module_block(self.main_tf, module_name)
            for map_name in ("secret_env_vars", "secrets"):
                map_match = re.search(
                    rf"{re.escape(map_name)}\s*=\s*\{{(.*?)\n\s*\}}",
                    block,
                    re.DOTALL,
                )
                if map_match is None:
                    continue
                map_body = map_match.group(1)
                self.assertNotIn(
                    "demo_seed_on_startup",
                    map_body,
                    f"module {module_name}.{map_name} must not reference demo_seed_on_startup",
                )
                self.assertNotIn(
                    "APP_DEMO_SEED_ON_STARTUP",
                    map_body,
                    f"module {module_name}.{map_name} must not wire APP_DEMO_SEED_ON_STARTUP",
                )

    def test_application_yml_defaults_seed_on_startup_false(self) -> None:
        """application.yml must still default app.demo.seed-on-startup to false."""
        demo_section = re.search(
            r"^\s*demo:\s*\n(?:^\s+.+\n)*",
            self.application_yml,
            re.MULTILINE,
        )
        self.assertIsNotNone(demo_section, "app.demo section not found in application.yml")
        section = demo_section.group(0)
        self.assertRegex(
            section,
            r"(?m)^\s*seed-on-startup:\s*false\s*$",
            msg="app.demo.seed-on-startup must visibly default to false in application.yml",
        )

    def test_portfolio_replica_and_ingress_unchanged(self) -> None:
        """portfolio-service must keep min_replicas=1, max_replicas=3, external_ingress=false."""
        self.assertEqual(_int_attr(self.portfolio_block, "min_replicas"), 1)
        self.assertEqual(_int_attr(self.portfolio_block, "max_replicas"), 3)
        self.assertFalse(
            _bool_attr(self.portfolio_block, "external_ingress"),
            "portfolio-service external_ingress must remain false (internal ingress)",
        )

    def test_demo_tx_diagnostics_variable_exists_with_bool_default_false(self) -> None:
        block = _extract_block(
            self.variables_tf,
            re.compile(r'variable\s+"demo_tx_diagnostics"\s*\{'),
        )
        self.assertRegex(block, r'\btype\s*=\s*bool\b')
        self.assertRegex(block, r'\bdefault\s*=\s*false\b')

    def test_portfolio_service_wires_demo_tx_diagnostics_env(self) -> None:
        self.assertRegex(
            self.portfolio_block,
            _TX_DIAG_WIRE_PATTERN,
            msg="portfolio_service must wire APP_DEMO_TX_DIAGNOSTICS = tostring(var.demo_tx_diagnostics)",
        )

    def test_only_portfolio_service_wires_demo_tx_diagnostics_env(self) -> None:
        occurrences = [m.start() for m in _TX_DIAG_WIRE_PATTERN.finditer(self.main_tf)]
        self.assertEqual(len(occurrences), 1)
        portfolio_start = self.main_tf.find('module "portfolio_service"')
        portfolio_block = _extract_module_block(self.main_tf, "portfolio_service")
        portfolio_end = portfolio_start + len('module "portfolio_service"') + len(portfolio_block) + 2
        self.assertTrue(portfolio_start <= occurrences[0] < portfolio_end)

    def test_demo_tx_diagnostics_not_wired_to_other_services(self) -> None:
        for module_name in ("api_gateway", "market_data_service", "insight_service"):
            block = _extract_module_block(self.main_tf, module_name)
            self.assertNotIn("demo_tx_diagnostics", block)
            self.assertNotIn("APP_DEMO_TX_DIAGNOSTICS", block)

    def test_demo_tx_diagnostics_not_wired_to_jobs(self) -> None:
        for job_name in ("market_data_refresh", "market_data_repair"):
            block = _extract_resource_block(
                self.main_tf, "azurerm_container_app_job", job_name
            )
            self.assertNotIn("demo_tx_diagnostics", block)
            self.assertNotIn("APP_DEMO_TX_DIAGNOSTICS", block)


if __name__ == "__main__":
    unittest.main()
