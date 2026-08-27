#!/usr/bin/env python3
"""Exact inventory guard for B1 Wave 5b version-aware seed callers."""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

SHELL_SCRIPT = REPO / ".github/workflows/scripts/seed-portfolio-with-version.sh"
SYNTHETIC_WF = REPO / ".github/workflows/synthetic-monitoring.yml"
DEPLOY_AZURE_WF = REPO / ".github/workflows/deploy-azure.yml"
GLOBAL_SETUP = REPO / "frontend/tests/e2e/global-setup.ts"
API_SMOKE = REPO / "frontend/tests/e2e/azure-synthetic/api-live-smoke.spec.ts"

SEED_PATH_RE = re.compile(r"/api/internal/portfolio/seed")
EXPECTED_VERSION_RE = re.compile(r"expectedVersion")
TERMINAL_409_RE = re.compile(r"\b409\b")

REQUIRED_SHELL_ENV = (
    "API_BASE",
    "INTERNAL_API_KEY",
    "E2E_USER_ID",
    "E2E_TEST_USER_EMAIL",
    "E2E_TEST_USER_PASSWORD",
)

SKIP_DIR_PARTS = {
    "node_modules",
    ".git",
    "dist",
    "build",
    "out",
    "playwright-report",
    "test-results",
    "coverage",
    "__pycache__",
    "docs",
}


SEED_STEP_NAME = "Re-seed E2E portfolio holdings"


def _extract_workflow_step(body: str, step_name: str) -> str:
    lines = body.splitlines()
    start: int | None = None
    for index, line in enumerate(lines):
        if line.strip() == f"- name: {step_name}":
            start = index
            break
    if start is None:
        raise GuardError(f"synthetic-monitoring.yml: step {step_name!r} not found")
    block = [lines[start]]
    for line in lines[start + 1 :]:
        if line.startswith("      - name:"):
            break
        block.append(line)
    return "\n".join(block)


class GuardError(Exception):
    pass


def _read(path: Path) -> str:
    if not path.is_file():
        raise GuardError(f"missing required file: {path.relative_to(REPO).as_posix()}")
    return path.read_text(encoding="utf-8")


def _assert_contains(text: str, pattern: re.Pattern[str], label: str) -> None:
    if not pattern.search(text):
        raise GuardError(f"{label}: missing required pattern {pattern.pattern!r}")


def check_shell_caller(text: str | None = None) -> str:
    body = text if text is not None else _read(SHELL_SCRIPT)
    _assert_contains(body, SEED_PATH_RE, "shell caller")
    _assert_contains(body, EXPECTED_VERSION_RE, "shell caller")
    _assert_contains(body, TERMINAL_409_RE, "shell caller")
    for line in body.splitlines():
        stripped = line.strip()
        if stripped.startswith("#"):
            continue
        if "curl --retry" in stripped or "curl --retry-all-errors" in stripped:
            raise GuardError("shell caller: must not use curl --retry")
    for env_name in REQUIRED_SHELL_ENV:
        if env_name not in body:
            raise GuardError(f"shell caller: missing env contract {env_name}")
    return "synthetic-shell (.github/workflows/scripts/seed-portfolio-with-version.sh)"


def check_synthetic_workflow(text: str | None = None) -> None:
    body = text if text is not None else _read(SYNTHETIC_WF)
    step = _extract_workflow_step(body, SEED_STEP_NAME)
    if "seed-portfolio-with-version.sh" not in step:
        raise GuardError(
            "synthetic-monitoring.yml seed step: must invoke seed-portfolio-with-version.sh"
        )
    for env_name in REQUIRED_SHELL_ENV:
        if env_name == "API_BASE":
            if "API_BASE:" not in step:
                raise GuardError("synthetic-monitoring.yml seed step: missing API_BASE env")
            continue
        if f"{env_name}:" not in step:
            raise GuardError(f"synthetic-monitoring.yml seed step: missing env {env_name}")


def check_global_setup(text: str | None = None) -> str:
    body = text if text is not None else _read(GLOBAL_SETUP)
    _assert_contains(body, SEED_PATH_RE, "global-setup")
    _assert_contains(body, EXPECTED_VERSION_RE, "global-setup")
    _assert_contains(body, TERMINAL_409_RE, "global-setup")
    if "selectPortfolioVersion" not in body:
        raise GuardError("global-setup: must freeze version via selectPortfolioVersion")
    return "global-setup (frontend/tests/e2e/global-setup.ts)"


def check_api_smoke(text: str | None = None) -> str:
    body = text if text is not None else _read(API_SMOKE)
    _assert_contains(body, SEED_PATH_RE, "azure-api-smoke")
    _assert_contains(body, EXPECTED_VERSION_RE, "azure-api-smoke")
    if "selectPortfolioVersion" not in body:
        raise GuardError(
            "azure-api-smoke: must freeze version via selectPortfolioVersion"
        )
    return "azure-api-smoke (frontend/tests/e2e/azure-synthetic/api-live-smoke.spec.ts)"


def check_deploy_azure_credentials(text: str | None = None) -> None:
    body = text if text is not None else _read(DEPLOY_AZURE_WF)
    seed_idx = body.find("Seed live Azure")
    if seed_idx < 0:
        raise GuardError("deploy-azure.yml: Seed live Azure step not found")
    fragment = body[seed_idx : seed_idx + 1800]
    if "E2E_TEST_USER_EMAIL:" not in fragment:
        raise GuardError("deploy-azure.yml seed step: missing E2E_TEST_USER_EMAIL")
    if "E2E_TEST_USER_PASSWORD:" not in fragment:
        raise GuardError("deploy-azure.yml seed step: missing E2E_TEST_USER_PASSWORD")
    if "secrets.E2E_TEST_USER_PASSWORD" not in fragment:
        raise GuardError(
            "deploy-azure.yml seed job: must reference secrets.E2E_TEST_USER_PASSWORD"
        )
    if "Sanitize Playwright artifacts" not in fragment:
        raise GuardError("deploy-azure.yml seed job: missing sanitizer step")


def _allowed_seed_paths() -> set[Path]:
    return {
        SHELL_SCRIPT.resolve(),
        GLOBAL_SETUP.resolve(),
        API_SMOKE.resolve(),
        (REPO / "frontend/tests/e2e/helpers/portfolio-seed-version.ts").resolve(),
        (
            REPO
            / "frontend/tests/e2e/helpers/__tests__/portfolio-seed-version.test.ts"
        ).resolve(),
        (
            REPO
            / "frontend/tests/e2e/helpers/__tests__/global-setup-seed-version.test.ts"
        ).resolve(),
        (REPO / "scripts/tests/test_seed_portfolio_with_version.py").resolve(),
        (REPO / "scripts/tests/test_check_b1_seed_version_callers.py").resolve(),
        (REPO / "scripts/check-b1-seed-version-callers.py").resolve(),
        SYNTHETIC_WF.resolve(),
        DEPLOY_AZURE_WF.resolve(),
    }


def discover_unexpected_seed_sites() -> list[str]:
    allowed = _allowed_seed_paths()
    unexpected: list[str] = []
    scan_roots = [
        REPO / ".github/workflows",
        REPO / "frontend/tests",
        REPO / "scripts",
    ]
    for root in scan_roots:
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            if path.suffix.lower() not in {".ts", ".js", ".sh", ".yml", ".yaml", ".py"}:
                continue
            if set(path.parts) & SKIP_DIR_PARTS:
                continue
            if path.resolve() in allowed:
                continue
            try:
                body = path.read_text(encoding="utf-8")
            except (UnicodeDecodeError, OSError):
                continue
            if SEED_PATH_RE.search(body):
                unexpected.append(path.relative_to(REPO).as_posix())
    return unexpected


def run_guard(
    *,
    shell_text: str | None = None,
    synthetic_text: str | None = None,
    global_setup_text: str | None = None,
    api_smoke_text: str | None = None,
    deploy_azure_text: str | None = None,
    skip_discovery: bool = False,
) -> str:
    callers = [
        check_shell_caller(shell_text),
        check_global_setup(global_setup_text),
        check_api_smoke(api_smoke_text),
    ]
    check_synthetic_workflow(synthetic_text)
    check_deploy_azure_credentials(deploy_azure_text)
    if not skip_discovery:
        unexpected = discover_unexpected_seed_sites()
        if unexpected:
            raise GuardError(
                "unexpected seed call site(s): " + ", ".join(unexpected)
            )
    lines = [
        "B1 seed-version caller inventory OK — exactly three callers:",
        *[f"  - {c}" for c in callers],
    ]
    return "\n".join(lines)


def main() -> int:
    try:
        print(run_guard())
        return 0
    except GuardError as exc:
        print(f"B1 seed-version caller inventory FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
