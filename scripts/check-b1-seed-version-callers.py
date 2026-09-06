#!/usr/bin/env python3
"""Exact inventory: three historical B1 Wave 5b callers plus B2 Task 9.7 cleanup.

The three G5 callers retain their frozen-version/terminal-conflict contracts.
Only the governed picker cleanup may retry 409 for hygiene, with a fresh fixed-E2E
observation on every bounded attempt and an eventual failure even after recovery.
"""

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
ASSET_PICKER = REPO / "frontend/tests/e2e/asset-picker.spec.ts"

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

SCHEDULE_TRIGGER_RE = re.compile(r"(?m)^  schedule\s*:")
MANUAL_TRIGGER_RE = re.compile(r"(?m)^  workflow_dispatch:\s*$")


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


def check_synthetic_dispatch_policy(body: str) -> None:
    if SCHEDULE_TRIGGER_RE.search(body):
        raise GuardError(
            "synthetic-monitoring.yml: unattended schedule is forbidden while B1 G5 is blocked"
        )
    if not MANUAL_TRIGGER_RE.search(body):
        raise GuardError(
            "synthetic-monitoring.yml: workflow_dispatch must remain available for separately authorized runs"
        )


def check_synthetic_workflow(text: str | None = None) -> None:
    body = text if text is not None else _read(SYNTHETIC_WF)
    check_synthetic_dispatch_policy(body)
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


def _picker_function(body: str, name: str) -> str:
    # This source guard recognizes the checked-in top-level function shape. Scope
    # checks to cleanup and its identity read: the picker save's own 200/409 must
    # never satisfy an internal-seed policy check elsewhere in the same file.
    match = re.search(rf"^async function {name}\(.*?^\}}", body, re.M | re.S)
    if match is None:
        raise GuardError(f"asset-picker-cleanup: missing function {name}")
    return match.group()


def _require_picker(body: str, pattern: str, policy: str) -> None:
    if re.search(pattern, body, re.S) is None:
        raise GuardError(f"asset-picker-cleanup: {policy}")


# Deliberately bounded source contract, not a general TypeScript control-flow
# parser. Executable changes to this cleanup/fixture section require review and
# an explicit canonical update. Trivia is ignored; literal contents are retained.
PICKER_CLEANUP_AND_FIXTURES = r'''
async function restoreGoldenState(
  request: APIRequestContext,
  session: E2eSession,
): Promise<void> {
  let observedConflict = false;
  for (let attempt = 1; attempt <= CLEANUP_MAX_ATTEMPTS; attempt += 1) {
    // Every attempt deliberately re-observes the identity-matched, current version.
    const observed = await observePortfolio(request, session);
    const response = await request.post(`${gatewayUrl()}/api/internal/portfolio/seed`, {
      headers: { "Content-Type": "application/json", "X-Internal-Api-Key": internalApiKey() },
      data: { expectedVersion: observed.version },
    });

    if (response.status() === 200) {
      if (observedConflict) {
        throw new Error(
          "[asset-picker-real] cleanup restored Golden State after an observed 409; the conflict still fails the test",
        );
      }
      return;
    }
    if (response.status() === 409) {
      observedConflict = true;
      continue;
    }
    throw new Error(
      `[asset-picker-real] version-bearing cleanup seed returned HTTP ${response.status()} on attempt ${attempt}`,
    );
  }
  throw new Error(
    `[asset-picker-real] version-bearing cleanup seed returned HTTP 409 on all ${CLEANUP_MAX_ATTEMPTS} attempts`,
  );
}

test.describe("Asset Picker — real composition save (Tasks 9.2, 9.7)", () => {
  let session: E2eSession | undefined;

  test.beforeEach(async ({ page, request }) => {
    session = await authenticateE2eSession(request);
    // Install before app timers are created; leave them running for UI reconciliation.
    await page.clock.install();
    await page.addInitScript(
      ({ key, value }: { key: string; value: E2eSession }) =>
        window.localStorage.setItem(key, JSON.stringify(value)),
      { key: AUTH_STORAGE_KEY, value: session },
    );
  });

  test.afterEach(async ({ request }) => {
    // Unconditional hygiene: runs after both a passing and a failing case.
    await restoreGoldenState(request, session ?? (await authenticateE2eSession(request)));
  });
'''

PICKER_TOKEN_RE = re.compile(
    r'''"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`'''
    r'|//[^\n]*|/\*[\s\S]*?\*/|[A-Za-z_$][\w$]*|[0-9]+|\S'
)


def _picker_source_tokens(body: str) -> list[str]:
    # Literals (including the reviewed, non-nested templates) stay atomic, so
    # their URL slashes and interpolation braces are never treated as trivia.
    return [
        token for token in PICKER_TOKEN_RE.findall(body)
        if not token.startswith(("//", "/*"))
    ]


def _check_picker_canonical_structure(body: str) -> None:
    tokens = _picker_source_tokens(body)
    expected = _picker_source_tokens(PICKER_CLEANUP_AND_FIXTURES)
    marker = ["async", "function", "restoreGoldenState", "("]
    starts = [
        index for index in range(len(tokens) - len(marker) + 1)
        if tokens[index:index + len(marker)] == marker
    ]
    if len(starts) != 1:
        raise GuardError("asset-picker-cleanup: canonical cleanup must occur exactly once")
    start = starts[0]
    # The reviewed module prefix has no regex literals with brace characters.
    # This delimiter check rejects wrapping the section in a conditional/block.
    prefix = tokens[:start]
    if prefix.count("{") != prefix.count("}") or tokens[start:start + len(expected)] != expected:
        raise GuardError(
            "asset-picker-cleanup: canonical cleanup and top-level fixture structure changed; "
            "review the complete executable section before updating its source contract"
        )


def check_asset_picker_cleanup(text: str | None = None) -> str:
    source = text if text is not None else _read(ASSET_PICKER)
    # These scoped fragment checks provide specific policy diagnostics. The
    # canonical check below also rejects extra statements and disabled fixtures.
    body = re.sub(r"(?m)^\s*//[^\n]*", "", source)
    if len(SEED_PATH_RE.findall(body)) != 1:
        raise GuardError("asset-picker-cleanup: exactly one seed call site is allowed")
    cleanup = _picker_function(body, "restoreGoldenState")
    observation = _picker_function(body, "observePortfolio")
    login = _picker_function(body, "authenticateE2eSession")
    _require_picker(body,
        r'import\s*\{\s*FIXED_E2E_USER_ID\s*\}\s*from\s*"\./helpers/portfolio-seed-version"',
        "fixed E2E identity must come from the shared version helper")
    _require_picker(login,
        r'if\s*\(body\.userId\s*!==\s*FIXED_E2E_USER_ID\)\s*\{\s*throw new Error\(',
        "login must reject a different E2E identity")
    _require_picker(observation,
        r'await request\.get\(`\$\{gatewayUrl\(\)\}/api/portfolio`,\s*\{\s*headers:\s*bearer\(session\.token\)\s*\}\)',
        "identity/version must come from an authenticated portfolio read")
    _require_picker(observation,
        r'return selectExactPortfolio\(await response\.json\(\),\s*FIXED_E2E_USER_ID\);',
        "read must select exactly the fixed E2E identity")
    _require_picker(observation, r'\.toBe\(200\)', "identity read must require HTTP 200")
    _require_picker(body, r'const CLEANUP_MAX_ATTEMPTS\s*=\s*3\s*;',
        "cleanup must remain bounded to three attempts")
    loop = r'for\s*\(let attempt = 1; attempt <= CLEANUP_MAX_ATTEMPTS; attempt \+= 1\)\s*\{'
    _require_picker(cleanup, loop, "cleanup must use the bounded attempt counter")
    _require_picker(cleanup,
        loop + r'\s*const observed = await observePortfolio\(request, session\);\s*const response = await request\.post\(',
        "each seed attempt must begin with a fresh identity-checked observation")
    _require_picker(cleanup,
        r'const response = await request\.post\(`\$\{gatewayUrl\(\)\}/api/internal/portfolio/seed`,\s*\{',
        "the governed call must POST the internal seed endpoint")
    _require_picker(cleanup, r'"X-Internal-Api-Key":\s*internalApiKey\(\)',
        "the seed request must carry the internal key")
    _require_picker(cleanup, r'data:\s*\{\s*expectedVersion:\s*observed\.version\s*\}',
        "expectedVersion must be the fresh observation's version")
    _require_picker(cleanup, r'if\s*\(response\.status\(\) === 200\)',
        "cleanup must require HTTP 200")
    _require_picker(cleanup, r'let observedConflict = false;',
        "cleanup must remember any 409 across attempts")
    _require_picker(cleanup,
        r'if\s*\(response\.status\(\) === 200\)\s*\{\s*'
        r'if\s*\(observedConflict\)\s*\{\s*throw new Error\(.*?\);\s*\}\s*return;\s*\}',
        "any observed 409 must fail even after HTTP 200 restores hygiene")
    _require_picker(cleanup,
        r'if\s*\(response\.status\(\) === 409\)\s*\{\s*observedConflict = true;\s*continue;\s*\}'
        r'\s*throw new Error\(.*?\);\s*\}\s*throw new Error\(.*?\);\s*\}\s*$',
        "409 must retry freshly, while other failures and exhausted retries still fail")
    _require_picker(body,
        r'test\.afterEach\(async\s*\(\{ request \}\)\s*=>\s*\{\s*'
        r'await restoreGoldenState\(request, session \?\? \(await authenticateE2eSession\(request\)\)\);\s*\}\);',
        "afterEach must restore unconditionally, including failed session setup")
    _check_picker_canonical_structure(source)
    return "asset-picker-cleanup (frontend/tests/e2e/asset-picker.spec.ts; B2 Task 9.7)"


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
        ASSET_PICKER.resolve(),
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
    asset_picker_text: str | None = None,
    deploy_azure_text: str | None = None,
    skip_discovery: bool = False,
) -> str:
    callers = [
        check_shell_caller(shell_text),
        check_global_setup(global_setup_text),
        check_api_smoke(api_smoke_text),
        check_asset_picker_cleanup(asset_picker_text),
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
        "B1/B2 seed-version caller inventory OK — exactly four governed callers:",
        "  (three historical B1 Wave 5b callers plus B2 Task 9.7 cleanup)",
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
