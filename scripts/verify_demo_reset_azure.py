#!/usr/bin/env python3
"""Fail-closed Azure Task 8.9 live-proof executable.

All external effects are behind injected command and HTTP runners so the proof
can be exhaustively verified offline before any separately authorized live run.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any, Callable


SERVICES = ("api-gateway", "portfolio-service")
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
REPOSITORY_SHA_RE = re.compile(r"^[0-9a-f]{40}$")
TRACEPARENT_RE = re.compile(
    r"^00-([0-9a-f]{32})-([0-9a-f]{16})-0[01]$"
)
REPO = Path(__file__).resolve().parents[1]
ORACLE = REPO / "scripts" / "derive_demo_golden_state.py"
REQUIRED_TARGET = "production-azure"
REQUIRED_RESOURCE_GROUP = "wealth-azure-prod-rg"
REQUIRED_WORKSPACE = "wealth-prod-la"
REQUIRED_REGISTRY = "wealthprodacr"
DEMO_EMAIL = "demo@wealthtracker.dev"


class ProofError(Exception):
    """The observation is insufficient for a Task 8.9 Go decision."""


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    stdout: str
    stderr: str


@dataclass(frozen=True)
class HttpResponse:
    status: int
    body: Any
    headers: dict[str, str]


@dataclass
class ProofConfig:
    mode: str
    target: str
    subscription_id: str
    resource_group: str
    gateway_app: str
    portfolio_app: str
    workspace_name: str
    registry_name: str
    gateway_url: str
    repository_sha: str
    run_attempt: str
    deployment_manifest: dict[str, Any]
    manifest_attempt_marker: str
    service_repositories: dict[str, str]
    access_token: str = ""
    demo_email: str = DEMO_EMAIL
    demo_password: str = ""
    expected_user_id: str = "00000000-0000-0000-0000-0000000d3110"
    idle_threshold: str = "30m"
    eligibility_timeout: str = "2s"
    reset_timeout: str = "2s"
    overall_timeout: str = "4s"
    threshold_override: str | None = None
    poll_interval_seconds: float = 5.0
    poll_deadline_seconds: float = 60.0
    cleanup_max_attempts: int = 3
    cleanup_deadline_seconds: float = 30.0
    operation_timeout_seconds: float = 15.0
    post_cleanup_verification_seconds: float = 5.0
    last_known_good_gateway_revision: str | None = None


@dataclass(frozen=True)
class ProofResult:
    exit_code: int
    evidence: dict[str, Any]


CommandRunner = Callable[..., CommandResult]
HttpRunner = Callable[..., HttpResponse]


def redact_evidence(value: Any, secrets_to_remove: list[str]) -> Any:
    """Return an evidence-safe copy with configured credentials removed everywhere."""
    secrets_to_remove = [secret for secret in secrets_to_remove if secret]
    if isinstance(value, dict):
        return {key: redact_evidence(item, secrets_to_remove) for key, item in value.items()}
    if isinstance(value, list):
        return [redact_evidence(item, secrets_to_remove) for item in value]
    if isinstance(value, tuple):
        return tuple(redact_evidence(item, secrets_to_remove) for item in value)
    if isinstance(value, str):
        for secret in secrets_to_remove:
            value = value.replace(secret, "[REDACTED]")
        return value
    return value


def validate_deployment_manifest(
    document: dict[str, Any], *, expected_attempt: str, expected_repository_sha: str,
    repositories: dict[str, str], manifest_attempt_marker: str | None,
) -> dict[str, Any]:
    if not str(expected_attempt):
        raise ProofError("deployment manifest requires the current run attempt provenance")
    if (not isinstance(manifest_attempt_marker, str)
            or manifest_attempt_marker.splitlines() != [str(expected_attempt)]):
        raise ProofError("deployment manifest attempt marker is missing, stale, or malformed")
    if not isinstance(expected_repository_sha, str) or not REPOSITORY_SHA_RE.fullmatch(
        expected_repository_sha
    ):
        raise ProofError("deployment manifest requires a lowercase repository SHA")
    if not isinstance(document, dict) or set(document) != set(SERVICES):
        raise ProofError("deployment manifest must contain exactly the two Task 8.9 services")
    if not isinstance(repositories, dict) or set(repositories) != set(SERVICES):
        raise ProofError("explicit repositories must contain exactly the two Task 8.9 services")
    normalized = {
        "runAttempt": str(expected_attempt),
        "repositorySha": expected_repository_sha,
        "services": {},
    }
    for service in SERVICES:
        repository = repositories.get(service)
        digest = document.get(service)
        if not isinstance(repository, str) or not repository or "@" in repository:
            raise ProofError(f"{service} manifest repository is invalid")
        if not isinstance(digest, str) or not DIGEST_RE.fullmatch(digest):
            raise ProofError(f"{service} manifest digest must be lowercase sha256")
        normalized["services"][service] = {"repository": repository, "digest": digest}
    return normalized


def _utc(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ProofError("query bounds must be timezone-aware")
    return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def _kql_literal(value: str) -> str:
    return value.replace("'", "''")


def build_event_query(
    *, app_name: str, event_name: str, trace_id: str, start: datetime, end: datetime
) -> str:
    if end <= start:
        raise ProofError("query end must be after query start")
    return "\n".join(
        (
            "ContainerAppConsoleLogs_CL",
            f"| where TimeGenerated between (datetime({_utc(start)}) .. datetime({_utc(end)}))",
            f"| where ContainerAppName_s == '{_kql_literal(app_name)}'",
            f"| where tostring(Log_s) contains '{_kql_literal(event_name)}'",
            f"| where tostring(Log_s) contains '{_kql_literal(trace_id)}'",
            "| project TimeGenerated, Log_s",
            "| order by TimeGenerated asc",
        )
    )


def _default_command_runner(
    command: list[str], *, timeout_seconds: float = 15.0
) -> CommandResult:
    try:
        completed = subprocess.run(
            command, check=False, capture_output=True, text=True, encoding="utf-8",
            timeout=timeout_seconds,
        )
        return CommandResult(completed.returncode, completed.stdout, completed.stderr)
    except subprocess.TimeoutExpired:
        return CommandResult(124, "", "operation timed out")


def _default_http_runner(
    *, method: str, url: str, headers: dict[str, str], json_body: Any = None,
    timeout_seconds: float = 15.0,
) -> HttpResponse:
    body = None if json_body is None else json.dumps(json_body).encode("utf-8")
    request_headers = dict(headers)
    if body is not None:
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(
        url, data=body, headers=request_headers, method=method
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            raw = response.read().decode("utf-8")
            return HttpResponse(
                response.status,
                json.loads(raw) if raw else None,
                dict(response.headers.items()),
            )
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8")
        try:
            parsed = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            parsed = {"error": "non_json_response"}
        return HttpResponse(error.code, parsed, dict(error.headers.items()))


def _new_traceparent() -> str:
    trace_id = secrets.token_hex(16)
    span_id = secrets.token_hex(8)
    return f"00-{trace_id}-{span_id}-01"


def new_traceparent() -> str:
    """Return a fresh, nonzero W3C traceparent for one proof login."""
    return _new_traceparent()


def _duration_seconds(value: str) -> float:
    match = re.fullmatch(r"([1-9][0-9]*)(ms|s|m|h)", value)
    if not match:
        raise ProofError(f"invalid positive duration: {value!r}")
    amount = int(match.group(1))
    return amount * {"ms": 0.001, "s": 1.0, "m": 60.0, "h": 3600.0}[match.group(2)]


def _initial_evidence(config: ProofConfig) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "target": {
            "name": config.target,
            "subscriptionId": config.subscription_id,
            "resourceGroup": config.resource_group,
            "gatewayApp": config.gateway_app,
            "portfolioApp": config.portfolio_app,
            "workspace": config.workspace_name,
            "registry": config.registry_name,
            "gatewayUrl": config.gateway_url,
        },
        "source": {
            "repositorySha": config.repository_sha,
            "runAttempt": config.run_attempt,
            "digestManifestShape": "task8.8b-service-digest-map",
        },
        "provider": "azure",
        "keyAlignment": {
            "internalApiKeyProven": False,
            "proof": "not_observed",
            "originVerification": "not_applicable_on_azure",
        },
        "serving": {},
        "servingRevalidation": {},
        "decisions": {
            "idleThreshold": config.idle_threshold,
            "eligibilityTimeout": config.eligibility_timeout,
            "resetTimeout": config.reset_timeout,
            "overallTimeout": config.overall_timeout,
            "overrideUsed": config.threshold_override is not None,
            "wave10Eligible": config.threshold_override is None,
        },
        "preflight": {"passed": False, "rbacRehearsed": False},
        "operations": [],
        "requestCounts": {
            "portfolioReads": 0,
            "compositionWrites": 0,
            "logins": 0,
            "cleanupResets": 0,
        },
        "setup": {},
        "trace": {},
        "login": {},
        "observation": {},
        "events": {},
        "classification": "not_observed",
        "classificationDetail": {},
        "diagnostics": {"operations": [], "available": False},
        "cleanup": {
            "armed": False,
            "attempts": [],
            "conflictObserved": False,
            "succeeded": False,
            "postCleanupGolden": False,
        },
        "thresholdRestore": {
            "required": config.threshold_override is not None,
            "attempted": False,
            "verified": config.threshold_override is None,
        },
        "verdict": {"go": False, "status": "not_started", "errors": []},
    }


def _validate_config(config: ProofConfig) -> None:
    exact = {
        "target": (config.target, REQUIRED_TARGET),
        "resource group": (config.resource_group, REQUIRED_RESOURCE_GROUP),
        "gateway app": (config.gateway_app, SERVICES[0]),
        "portfolio app": (config.portfolio_app, SERVICES[1]),
        "workspace": (config.workspace_name, REQUIRED_WORKSPACE),
        "registry": (config.registry_name, REQUIRED_REGISTRY),
    }
    for label, (actual, expected) in exact.items():
        if actual != expected:
            raise ProofError(f"explicit {label} must equal {expected!r}")
    if config.mode not in {"preflight", "rehearsal", "execute"}:
        raise ProofError("mode must be preflight, rehearsal, or execute")
    if not config.subscription_id:
        raise ProofError("an explicit approved subscription is required")
    if not config.gateway_url.startswith("https://"):
        raise ProofError("the production gateway URL must use https")
    if config.demo_email != DEMO_EMAIL:
        raise ProofError("the proof is restricted to the fixed demo account")
    if config.poll_interval_seconds <= 0 or config.poll_deadline_seconds <= 0:
        raise ProofError("poll interval and deadline must be positive")
    if config.cleanup_max_attempts <= 0 or config.cleanup_deadline_seconds <= 0:
        raise ProofError("cleanup bounds must be positive")
    if config.operation_timeout_seconds <= 0 or config.post_cleanup_verification_seconds <= 0:
        raise ProofError("operation and post-cleanup timeouts must be positive")
    if not isinstance(config.service_repositories, dict) or set(config.service_repositories) != set(SERVICES):
        raise ProofError("explicit service repositories are required")
    _duration_seconds(config.idle_threshold)
    if config.threshold_override is not None:
        _duration_seconds(config.threshold_override)


def _decode_json(result: CommandResult, label: str) -> Any:
    if result.returncode != 0:
        raise ProofError(f"{label} failed: {(result.stderr or result.stdout).strip()}")
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise ProofError(f"{label} returned malformed JSON") from error


def _record_command(
    evidence: dict[str, Any], runner: CommandRunner, command: list[str], *, label: str,
    mutating: bool = False, timeout_seconds: float = 15.0,
) -> CommandResult:
    evidence["operations"].append(
        {"kind": "azure_cli" if command[0] == "az" else "local_cli", "argv": command,
         "mutating": mutating, "timeoutSeconds": timeout_seconds}
    )
    result = runner(command, timeout_seconds=timeout_seconds)
    if result.returncode != 0:
        raise ProofError(f"{label} failed: {(result.stderr or result.stdout).strip()}")
    return result


def _record_http(
    evidence: dict[str, Any], runner: HttpRunner, *, method: str, url: str,
    headers: dict[str, str], json_body: Any = None, mutating: bool,
    timeout_seconds: float = 15.0,
) -> HttpResponse:
    safe: dict[str, Any] = {
        "kind": "http",
        "method": method,
        "url": url,
        "headerNames": sorted(headers),
        "mutating": mutating,
        "timeoutSeconds": timeout_seconds,
    }
    if isinstance(json_body, dict):
        safe["bodyKeys"] = sorted(json_body)
        if "expectedVersion" in json_body:
            safe["expectedVersion"] = json_body["expectedVersion"]
    evidence["operations"].append(safe)
    path = url.split("?", 1)[0]
    if method == "GET" and path.endswith("/api/portfolio"):
        evidence["requestCounts"]["portfolioReads"] += 1
    elif method == "PUT" and path.endswith("/api/portfolio/holdings"):
        evidence["requestCounts"]["compositionWrites"] += 1
    elif method == "POST" and path.endswith("/api/auth/login"):
        evidence["requestCounts"]["logins"] += 1
    elif method == "PUT" and path.endswith("/api/portfolio/demo-reset"):
        evidence["requestCounts"]["cleanupResets"] += 1
    return runner(
        method=method, url=url, headers=headers, json_body=json_body,
        timeout_seconds=timeout_seconds,
    )


def _load_oracle(
    evidence: dict[str, Any], runner: CommandRunner, expected_user_id: str, *,
    timeout_seconds: float,
) -> list[dict[str, str]]:
    result = _record_command(
        evidence, runner, [sys.executable, "-B", str(ORACLE)], label="Task 4.4a golden oracle",
        timeout_seconds=timeout_seconds,
    )
    document = _decode_json(result, "Task 4.4a golden oracle")
    if document.get("metadata", {}).get("demoUserId") != expected_user_id:
        raise ProofError("Task 4.4a oracle demo identity does not match the approved identity")
    holdings = document.get("wireHoldings")
    if not isinstance(holdings, list) or not holdings:
        raise ProofError("Task 4.4a oracle returned no wire holdings")
    normalized = []
    for row in holdings:
        if not isinstance(row, dict) or set(row) != {"assetTicker", "quantity"}:
            raise ProofError("Task 4.4a oracle returned a malformed wire holding")
        normalized.append({"assetTicker": str(row["assetTicker"]), "quantity": str(row["quantity"])})
    return normalized


def _acr_repository_name(repository: str, registry_name: str) -> str:
    prefix = registry_name + ".azurecr.io/"
    if not repository.startswith(prefix) or len(repository) == len(prefix):
        raise ProofError("manifest repository does not belong to the approved ACR")
    return repository[len(prefix):]


def _authoritative_yaml_defaults() -> dict[str, str]:
    path = REPO / "api-gateway" / "src" / "main" / "resources" / "application.yml"
    text = path.read_text(encoding="utf-8")
    mapping = {
        "APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD": "idle-threshold",
        "APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT": "eligibility-timeout",
        "APP_DEMO_LOGIN_RESET_RESET_TIMEOUT": "reset-timeout",
        "APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT": "overall-timeout",
    }
    defaults: dict[str, str] = {}
    for env_name, yaml_name in mapping.items():
        match = re.search(
            rf"^\s*{re.escape(yaml_name)}:\s*\$\{{{re.escape(env_name)}:([^}}]+)}}\s*$",
            text,
            re.MULTILINE,
        )
        if not match:
            raise ProofError(f"authoritative YAML default missing for {env_name}")
        defaults[env_name] = match.group(1)
    return defaults


def _preflight(config: ProofConfig, evidence: dict[str, Any], runner: CommandRunner) -> list[dict[str, str]]:
    manifest = validate_deployment_manifest(
        config.deployment_manifest,
        expected_attempt=config.run_attempt,
        expected_repository_sha=config.repository_sha,
        repositories=config.service_repositories,
        manifest_attempt_marker=config.manifest_attempt_marker,
    )
    account = _record_command(
        evidence, runner, ["az", "account", "show", "--query", "id", "-o", "tsv"],
        label="Azure subscription identity", timeout_seconds=config.operation_timeout_seconds,
    ).stdout.strip()
    if account != config.subscription_id:
        raise ProofError("Azure subscription does not equal the explicitly approved subscription")

    for service in SERVICES:
        command = [
            "az", "containerapp", "revision", "list", "--name", service,
            "--resource-group", config.resource_group,
            "--query", "[?properties.active && properties.trafficWeight == `100`].{name:name,image:properties.template.containers[0].image}",
            "-o", "json",
        ]
        revisions = _decode_json(
            _record_command(
                evidence, runner, command, label=f"{service} serving revisions",
                timeout_seconds=config.operation_timeout_seconds,
            ),
            f"{service} serving revisions",
        )
        if not isinstance(revisions, list) or len(revisions) != 1:
            count = len(revisions) if isinstance(revisions, list) else "non-list"
            raise ProofError(f"{service} must have exactly one serving revision; found {count}")
        revision = revisions[0]
        expected = manifest["services"][service]
        expected_image = expected["repository"] + "@" + expected["digest"]
        if not isinstance(revision, dict) or revision.get("image") != expected_image:
            raise ProofError(f"{service} serving repo@digest disagrees with current-attempt manifest")
        if not revision.get("name"):
            raise ProofError(f"{service} serving revision has no name")
        evidence["serving"][service] = {
            "revision": revision["name"], "image": expected_image,
            "repository": expected["repository"], "digest": expected["digest"],
        }

    workspace_id = _record_command(
        evidence, runner,
        ["az", "monitor", "log-analytics", "workspace", "show", "--workspace-name", config.workspace_name,
         "--resource-group", config.resource_group, "--query", "customerId", "-o", "tsv"],
        label="Log Analytics workspace identity",
        timeout_seconds=config.operation_timeout_seconds,
    ).stdout.strip()
    if not workspace_id:
        raise ProofError("Log Analytics workspace customerId is blank")
    evidence["target"]["workspaceCustomerId"] = workspace_id

    gateway_revision = evidence["serving"][config.gateway_app]["revision"]
    replicas = _decode_json(
        _record_command(
            evidence, runner,
            ["az", "containerapp", "replica", "list", "--name", config.gateway_app,
             "--resource-group", config.resource_group, "--revision", gateway_revision, "-o", "json"],
            label="gateway replica read permission",
            timeout_seconds=config.operation_timeout_seconds,
        ), "gateway replica read permission"
    )
    if not isinstance(replicas, list) or not replicas or not replicas[0].get("name"):
        raise ProofError("RBAC rehearsal could not resolve a gateway replica")
    _record_command(
        evidence, runner,
        ["az", "containerapp", "exec", "--name", config.gateway_app,
         "--resource-group", config.resource_group, "--revision", gateway_revision,
         "--replica", replicas[0]["name"], "--container", config.gateway_app,
         "--command", "java -jar /probe.jar"],
        label="non-disclosing presence probe RBAC rehearsal",
        timeout_seconds=config.operation_timeout_seconds,
    )
    for service in SERVICES:
        identity = evidence["serving"][service]
        _record_command(
            evidence, runner,
            ["az", "acr", "manifest", "show-metadata", "--registry", config.registry_name,
             "--name", _acr_repository_name(identity["repository"], config.registry_name)
             + "@" + identity["digest"], "-o", "json"],
            label=f"{service} ACR pull-access rehearsal",
            timeout_seconds=config.operation_timeout_seconds,
        )
    _record_command(
        evidence, runner,
        ["az", "acr", "login", "--name", config.registry_name],
        label="ACR authentication rehearsal",
        timeout_seconds=config.operation_timeout_seconds,
    )
    for service in SERVICES:
        _record_command(
            evidence, runner,
            ["docker", "pull", evidence["serving"][service]["image"]],
            label=f"{service} immutable image pull rehearsal",
            timeout_seconds=config.operation_timeout_seconds,
        )
    _record_command(
        evidence, runner,
        ["az", "monitor", "log-analytics", "query", "--workspace", workspace_id,
         "--analytics-query", "print task8_9_rbac_probe=1", "-o", "json"],
        label="Log Analytics query RBAC rehearsal",
        timeout_seconds=config.operation_timeout_seconds,
    )
    gateway_revision = evidence["serving"][config.gateway_app]["revision"]
    env_rows = _decode_json(
        _record_command(
            evidence, runner,
            ["az", "containerapp", "revision", "show", "--name", config.gateway_app,
             "--resource-group", config.resource_group,
             "--revision", gateway_revision,
             "--query", "properties.template.containers[0].env", "-o", "json"],
            label="Wave 8 decision readback",
            timeout_seconds=config.operation_timeout_seconds,
        ),
        "Wave 8 decision readback",
    )
    if not isinstance(env_rows, list):
        raise ProofError("Wave 8 decision readback is not an environment list")
    names: dict[str, Any] = {}
    for row in env_rows:
        if isinstance(row, dict) and isinstance(row.get("name"), str):
            if row["name"] in names:
                raise ProofError(f"duplicate serving environment value: {row['name']}")
            names[row["name"]] = row.get("value")
    approved = {
        "APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD": config.idle_threshold,
        "APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT": config.eligibility_timeout,
        "APP_DEMO_LOGIN_RESET_RESET_TIMEOUT": config.reset_timeout,
        "APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT": config.overall_timeout,
    }
    yaml_defaults = _authoritative_yaml_defaults()
    for name, expected in approved.items():
        effective = names.get(name, yaml_defaults[name])
        if effective != expected:
            raise ProofError(f"serving {name} does not equal the approved value")
        names[name] = effective
    provider = names.get("CLOUD_PROVIDER", "azure")
    if provider != "azure":
        raise ProofError("serving CLOUD_PROVIDER is not azure")
    evidence["provider"] = provider
    evidence["decisions"]["serving"] = {
        "idleThreshold": names["APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD"],
        "eligibilityTimeout": names["APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT"],
        "resetTimeout": names["APP_DEMO_LOGIN_RESET_RESET_TIMEOUT"],
        "overallTimeout": names["APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT"],
    }
    evidence["preflight"] = {"passed": True, "rbacRehearsed": True}
    return _load_oracle(
        evidence, runner, config.expected_user_id,
        timeout_seconds=config.operation_timeout_seconds,
    )


def _revalidate_serving(
    config: ProofConfig, evidence: dict[str, Any], runner: CommandRunner, *, stage: str,
    expected_idle: str, allow_revision_change: bool = False,
) -> None:
    snapshot: dict[str, Any] = {"matched": False, "services": {}}
    for service in SERVICES:
        revisions = _decode_json(
            _record_command(
                evidence, runner,
                ["az", "containerapp", "revision", "list", "--name", service,
                 "--resource-group", config.resource_group,
                 "--query", "[?properties.active && properties.trafficWeight == `100`].{name:name,image:properties.template.containers[0].image}",
                 "-o", "json"],
                label=f"{stage} {service} serving revalidation",
                timeout_seconds=config.operation_timeout_seconds,
            ),
            f"{stage} {service} serving revalidation",
        )
        if not isinstance(revisions, list) or len(revisions) != 1:
            raise ProofError(f"{stage}: {service} no longer has exactly one serving revision")
        current = revisions[0]
        recorded = evidence["serving"][service]
        if current.get("image") != recorded["image"]:
            raise ProofError(f"{stage}: {service} serving image changed during the proof")
        if not allow_revision_change and current.get("name") != recorded["revision"]:
            raise ProofError(f"{stage}: {service} serving revision changed during the proof")
        snapshot["services"][service] = {
            "revision": current.get("name"), "image": current.get("image"),
            "recordedRevision": recorded["revision"],
        }

    gateway_revision = snapshot["services"][config.gateway_app]["revision"]
    rows = _decode_json(
        _record_command(
            evidence, runner,
            ["az", "containerapp", "revision", "show", "--name", config.gateway_app,
             "--resource-group", config.resource_group, "--revision", gateway_revision,
             "--query", "properties.template.containers[0].env", "-o", "json"],
            label=f"{stage} serving decision revalidation",
            timeout_seconds=config.operation_timeout_seconds,
        ),
        f"{stage} serving decision revalidation",
    )
    if not isinstance(rows, list):
        raise ProofError(f"{stage}: serving decision readback is not a list")
    values = {row["name"]: row.get("value") for row in rows
              if isinstance(row, dict) and isinstance(row.get("name"), str)}
    defaults = _authoritative_yaml_defaults()
    expected = {
        "APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD": expected_idle,
        "APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT": config.eligibility_timeout,
        "APP_DEMO_LOGIN_RESET_RESET_TIMEOUT": config.reset_timeout,
        "APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT": config.overall_timeout,
    }
    effective = {name: values.get(name, defaults[name]) for name in expected}
    if effective != expected:
        raise ProofError(f"{stage}: serving decision values changed during the proof")
    provider = values.get("CLOUD_PROVIDER", "azure")
    if provider != "azure":
        raise ProofError(f"{stage}: CLOUD_PROVIDER changed during the proof")
    snapshot["decisions"] = effective
    snapshot["provider"] = provider
    snapshot["matched"] = True
    evidence["servingRevalidation"][stage] = snapshot


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": "Bearer " + token}


def _select_portfolio(payload: Any, expected_user_id: str) -> dict[str, Any]:
    rows = payload if isinstance(payload, list) else [payload] if isinstance(payload, dict) else []
    matches = [row for row in rows if isinstance(row, dict) and row.get("userId") == expected_user_id]
    if len(matches) != 1:
        raise ProofError(f"identity-checked portfolio read required exactly one match; found {len(matches)}")
    portfolio = matches[0]
    version = portfolio.get("version")
    if not isinstance(version, int) or isinstance(version, bool) or version < 0:
        raise ProofError("identity-checked portfolio has an invalid version")
    if not isinstance(portfolio.get("holdings"), list):
        raise ProofError("identity-checked portfolio has malformed holdings")
    return portfolio


def _wire_holdings(rows: list[dict[str, Any]]) -> list[dict[str, str]]:
    normalized = []
    seen = set()
    for row in rows:
        if not isinstance(row, dict):
            raise ProofError("holding is not an object")
        ticker = row.get("assetTicker", row.get("ticker"))
        quantity = row.get("quantity")
        if not isinstance(ticker, str) or not ticker or ticker in seen or not isinstance(quantity, str):
            raise ProofError("holding has invalid or duplicate identity/quantity")
        seen.add(ticker)
        normalized.append({"assetTicker": ticker, "quantity": quantity})
    return sorted(normalized, key=lambda row: row["assetTicker"])


def _is_golden(portfolio: dict[str, Any], golden: list[dict[str, str]]) -> bool:
    return _wire_holdings(portfolio["holdings"]) == _wire_holdings(golden)


def _non_golden_write(version: int, golden: list[dict[str, str]]) -> dict[str, Any]:
    holdings = []
    for index, row in enumerate(golden):
        quantity = row["quantity"]
        if index == 0:
            quantity = format(Decimal(quantity) + Decimal("1.00000000"), ".8f")
        holdings.append({"ticker": row["assetTicker"], "quantity": quantity})
    return {"expectedVersion": version, "holdings": holdings}


def _parse_instant(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or not value:
        raise ProofError(f"{label} must be an absolute timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ProofError(f"{label} must be an absolute timestamp") from error
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ProofError(f"{label} must be an absolute timestamp")
    return parsed.astimezone(timezone.utc)


def _read_portfolio(
    config: ProofConfig, evidence: dict[str, Any], runner: HttpRunner, token: str,
    *, timeout_seconds: float | None = None,
) -> dict[str, Any]:
    response = _record_http(
        evidence, runner, method="GET", url=config.gateway_url + "/api/portfolio",
        headers=_auth(token), mutating=False,
        timeout_seconds=timeout_seconds or config.operation_timeout_seconds,
    )
    if response.status != 200:
        raise ProofError(f"portfolio read returned HTTP {response.status}")
    return _select_portfolio(response.body, config.expected_user_id)


def _event_value(value: str) -> Any:
    if len(value) >= 2 and value[0] == value[-1] == '"':
        value = value[1:-1]
    if value == "true":
        return True
    if value == "false":
        return False
    if value == "null":
        return None
    if re.fullmatch(r"-?[0-9]+", value):
        return int(value)
    return value


def _event_emission_identity(event: dict[str, Any]) -> tuple[Any, Any]:
    """Identify one emitted log record, not merely its parsed business payload."""
    return event.get("timeGenerated"), event.get("rawLog")


def parse_event_rows(rows: Any, *, event: str, trace_id: str) -> dict[str, Any] | None:
    if not isinstance(rows, list):
        raise ProofError("log query result is not a list")
    matches = []
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("Log_s"), str):
            continue
        text = row["Log_s"]
        if trace_id not in text:
            continue
        payload: dict[str, Any]
        start = text.find("{")
        if start >= 0:
            try:
                decoded = json.loads(text[start:])
            except json.JSONDecodeError:
                decoded = None
            payload = dict(decoded) if isinstance(decoded, dict) else {}
        else:
            payload = {
                key: _event_value(value)
                for key, value in re.findall(
                    r'(?:^|\s)([A-Za-z][A-Za-z0-9]*)=("[^"]*"|\S+)', text
                )
            }
        if payload.get("event") == event:
            payload["traceId"] = trace_id
            payload.setdefault("timeGenerated", row.get("TimeGenerated"))
            payload["rawLog"] = text
            matches.append(payload)
    unique: dict[str, dict[str, Any]] = {}
    for match in matches:
        unique.setdefault(
            json.dumps(_event_emission_identity(match), default=str), match
        )
    if len(unique) > 1:
        raise ProofError(f"inconsistent multiple {event} events found for one trace")
    return next(iter(unique.values())) if unique else None


def _query_once(
    config: ProofConfig, evidence: dict[str, Any], runner: CommandRunner, *, query: str,
    label: str, timeout_seconds: float,
) -> tuple[Any | None, str | None]:
    command = [
        "az", "monitor", "log-analytics", "query", "--workspace",
        evidence["target"]["workspaceCustomerId"], "--analytics-query", query,
        "--timespan", evidence["trace"]["windowStart"] + "/" + evidence["trace"]["windowEnd"],
        "-o", "json",
    ]
    evidence["operations"].append({
        "kind": "azure_cli", "argv": command, "mutating": False,
        "timeoutSeconds": timeout_seconds,
    })
    try:
        result = runner(command, timeout_seconds=timeout_seconds)
    except Exception as error:
        return None, f"{label} query runner error: {error}"
    if result.returncode != 0:
        return None, (result.stderr or result.stdout).strip() or f"{label} query failed"
    try:
        return json.loads(result.stdout), None
    except json.JSONDecodeError:
        return None, f"{label} query returned malformed JSON"


def _poll_events(
    config: ProofConfig, evidence: dict[str, Any], runner: CommandRunner, *, trace_id: str,
    start: datetime, end: datetime, monotonic: Callable[[], float], sleep: Callable[[float], None]
) -> dict[str, Any]:
    queries = {
        "success": build_event_query(
            app_name=config.portfolio_app, event_name="demo_reset_succeeded",
            trace_id=trace_id, start=start, end=end,
        ),
        "skip": build_event_query(
            app_name=config.gateway_app, event_name="demo_reset_self_call_skipped",
            trace_id=trace_id, start=start, end=end,
        ),
    }
    found = {"success": None, "skip": None}
    errors: list[str] = []
    evidence["events"] = {
        "outcome": "polling", "success": None, "skip": None, "queryError": None,
        "queries": queries, "windowStart": _utc(start), "windowEnd": _utc(end),
        "pollIntervalSeconds": config.poll_interval_seconds,
        "pollDeadlineSeconds": config.poll_deadline_seconds,
    }
    began = monotonic()
    attempts = max(1, math.floor(config.poll_deadline_seconds / config.poll_interval_seconds) + 1)
    for attempt in range(attempts):
        remaining = config.poll_deadline_seconds - (monotonic() - began)
        if remaining <= 0:
            break
        pair_timeout = min(config.operation_timeout_seconds, max(0.001, remaining / 2))
        for kind in ("success", "skip"):
            rows, error = _query_once(
                config, evidence, runner, query=queries[kind], label=kind,
                timeout_seconds=pair_timeout,
            )
            if error:
                errors.append(error)
            else:
                event_name = "demo_reset_succeeded" if kind == "success" else "demo_reset_self_call_skipped"
                try:
                    observed = parse_event_rows(rows, event=event_name, trace_id=trace_id)
                    if observed is not None:
                        if found[kind] is None:
                            found[kind] = observed
                        else:
                            if (_event_emission_identity(found[kind])
                                    != _event_emission_identity(observed)):
                                errors.append(
                                    f"distinct later {kind} emission for one trace"
                                )
                except Exception as error:
                    errors.append(f"{kind} query parse error: {error}")
            evidence["events"][kind] = found[kind]
            evidence["events"]["queryError"] = "; ".join(errors) if errors else None
        if errors or (found["success"] is not None and found["skip"] is not None):
            break
        if attempt + 1 < attempts:
            remaining = config.poll_deadline_seconds - (monotonic() - began)
            if remaining <= 0:
                break
            sleep(min(config.poll_interval_seconds, remaining))
    if errors:
        outcome = "d_query_error"
    elif found["success"] is not None and found["skip"] is not None:
        outcome = "e_both"
    elif found["success"] is not None:
        outcome = "a_success_only"
    elif found["skip"] is not None:
        outcome = "b_skip_only"
    else:
        outcome = "c_neither"
    result = {
        "outcome": outcome,
        "success": found["success"],
        "skip": found["skip"],
        "queryError": "; ".join(errors) if errors else None,
        "queries": queries,
        "pollIntervalSeconds": config.poll_interval_seconds,
        "pollDeadlineSeconds": config.poll_deadline_seconds,
    }
    evidence["events"] = result
    return result


SKIP_BASE_FIELDS = {
    "event", "reason", "leg", "httpStatus", "timeoutScope", "attemptedTarget",
    "elapsedMillis", "replicaToken", "eligibilityDispatchAttempted",
    "resetDispatchAttempted", "internalApiKeyConfigured", "internalApiKeyAttached",
    "originVerifyRequired", "originVerifyHeaderAttached",
}
OVERALL_PHASES = {
    "eligibility_pre_dispatch": (False, False),
    "eligibility_in_flight": (True, False),
    "between_legs": (True, False),
    "reset_in_flight": (True, True),
    "reset_post_response": (True, True),
}


def validate_skip_event(event: dict[str, Any], *, success_present: bool) -> dict[str, Any]:
    if not isinstance(event, dict) or not SKIP_BASE_FIELDS.issubset(event):
        missing = sorted(SKIP_BASE_FIELDS - set(event) if isinstance(event, dict) else SKIP_BASE_FIELDS)
        raise ProofError("skip event is missing required diagnostic facts: " + ",".join(missing))
    if event.get("event") != "demo_reset_self_call_skipped":
        raise ProofError("skip event name is invalid")
    for field in (
        "eligibilityDispatchAttempted", "resetDispatchAttempted",
        "internalApiKeyConfigured", "originVerifyRequired",
    ):
        if not isinstance(event.get(field), bool):
            raise ProofError(f"skip event {field} must be boolean")
    for field in ("internalApiKeyAttached", "originVerifyHeaderAttached"):
        if event.get(field) is not None and not isinstance(event.get(field), bool):
            raise ProofError(f"skip event {field} must be boolean or null")
    if not isinstance(event.get("replicaToken"), str):
        raise ProofError("skip event replicaToken must be a string")
    if not event["resetDispatchAttempted"] and event["internalApiKeyAttached"] is not None:
        raise ProofError("reset attachment must be null when reset was not dispatched")
    if event["resetDispatchAttempted"] and not isinstance(event["internalApiKeyAttached"], bool):
        raise ProofError("reset attachment must be boolean when reset was dispatched")
    if (not event["eligibilityDispatchAttempted"] or not event["originVerifyRequired"]):
        if event["originVerifyHeaderAttached"] is not None:
            raise ProofError("origin attachment must be null when inapplicable")
    elif not isinstance(event["originVerifyHeaderAttached"], bool):
        raise ProofError("origin attachment must be boolean when required and dispatched")

    reason = event.get("reason")
    leg = event.get("leg")
    if reason == "overall_timeout":
        if leg != "overall" or event.get("timeoutScope") != "overall":
            raise ProofError("overall_timeout requires leg=overall and timeoutScope=overall")
        phase = event.get("overallTimeoutPhase")
        if phase not in OVERALL_PHASES:
            raise ProofError("overall_timeout has an invalid phase")
        pair = (event["eligibilityDispatchAttempted"], event["resetDispatchAttempted"])
        if pair != OVERALL_PHASES[phase]:
            raise ProofError("overall timeout phase contradicts its dispatch pair")
        expected_target = phase in {"eligibility_in_flight", "reset_in_flight"}
        if expected_target != isinstance(event.get("attemptedTarget"), str):
            raise ProofError("overall timeout attemptedTarget contradicts its phase")
        if phase == "reset_post_response":
            if not isinstance(event.get("httpStatus"), int):
                raise ProofError("reset_post_response must retain the reset HTTP status")
        elif event.get("httpStatus") is not None:
            raise ProofError("overall timeout HTTP status is only valid post-response")
        if not isinstance(event.get("elapsedMillis"), int) or event["elapsedMillis"] < 0:
            raise ProofError("overall_timeout requires elapsedMillis")
    elif reason in {"eligibility_timeout", "reset_timeout"}:
        expected_leg = reason.removesuffix("_timeout")
        if leg != expected_leg or event.get("timeoutScope") != "per-leg":
            raise ProofError("per-leg timeout reason/leg/scope disagree")
        if event.get("overallTimeoutPhase") is not None:
            raise ProofError("per-leg timeout must not carry an overall phase")
        if not isinstance(event.get("attemptedTarget"), str):
            raise ProofError("per-leg timeout requires attemptedTarget")
        if not isinstance(event.get("elapsedMillis"), int) or event["elapsedMillis"] < 0:
            raise ProofError("per-leg timeout requires elapsedMillis")
        if event.get("httpStatus") is not None:
            raise ProofError("per-leg timeout must not carry HTTP status")
    elif reason in {"eligibility_connection_failure", "reset_connection_failure"}:
        if leg != reason.removesuffix("_connection_failure"):
            raise ProofError("connection-failure reason and leg disagree")
        if not isinstance(event.get("attemptedTarget"), str):
            raise ProofError("connection failure requires attemptedTarget")
        if event.get("timeoutScope") is not None or event.get("elapsedMillis") is not None:
            raise ProofError("connection failure must not carry timeout diagnostics")
    elif reason in {"eligibility_non_2xx_status", "reset_non_2xx_status"}:
        if leg != reason.removesuffix("_non_2xx_status") or not isinstance(event.get("httpStatus"), int):
            raise ProofError("non-2xx reason/leg/status disagree")
        if event.get("timeoutScope") is not None or event.get("overallTimeoutPhase") is not None:
            raise ProofError("non-2xx event must not carry timeout scope/phase")
        if reason == "reset_non_2xx_status" and event["httpStatus"] == 409:
            required = {
                "observedVersion", "submittedExpectedVersion",
                "downstreamCurrentVersion", "selfCallCount",
            }
            if not required.issubset(event):
                raise ProofError("reset 409 requires version and self-call-count evidence")
            if any(not isinstance(event.get(name), int) for name in required):
                raise ProofError("reset 409 evidence must be integral")
    elif reason == "eligibility_shape_failure":
        if leg != "eligibility" or event.get("httpStatus") != 200:
            raise ProofError("eligibility shape failure requires eligibility HTTP 200")
    elif reason == "reset_key_not_configured":
        if leg != "reset" or event.get("httpStatus") is not None:
            raise ProofError("reset_key_not_configured requires reset leg and null status")
        if event["resetDispatchAttempted"] or event["internalApiKeyConfigured"]:
            raise ProofError("reset_key_not_configured contradicts reset dispatch/configuration")
    elif reason == "gateway_orchestration_error":
        if leg not in {"eligibility", "reset"} or not isinstance(event.get("exceptionClass"), str):
            raise ProofError("gateway_orchestration_error requires leg and safe exception category")
    else:
        raise ProofError("skip event has an unknown reason")

    if success_present and reason == "overall_timeout":
        phase = event["overallTimeoutPhase"]
        if phase in {"eligibility_pre_dispatch", "eligibility_in_flight", "between_legs"}:
            event = dict(event)
            event["impossibleWithSuccess"] = True
    return event


def _terminal(
    class_name: str, action: str, *, rollback: bool = False, resolved: bool = True,
    rationale: str,
) -> dict[str, Any]:
    return {
        "class": class_name,
        "action": action,
        "rollbackAuthorized": rollback,
        "resolved": resolved,
        "rationale": rationale,
    }


def _expected_loopback(leg: str, target: Any) -> bool:
    if not isinstance(target, str):
        return False
    path = "/api/portfolio" if leg == "eligibility" else "/api/internal/portfolio/demo-reset"
    return re.fullmatch(rf"http://localhost:[1-9][0-9]*{re.escape(path)}", target) is not None


def _has_same_replica_attribution(diagnostics: dict[str, Any], event_token: Any) -> bool:
    return bool(
        isinstance(event_token, str)
        and re.fullmatch(r"[0-9a-f]{12}", event_token)
        and diagnostics.get("replicaTokenRecovered") is True
        and diagnostics.get("emitterStillServing") is True
        and diagnostics.get("sameReplica") is True
        and diagnostics.get("emitterReplicaToken") == event_token
        and diagnostics.get("probeReplicaToken") == event_token
    )


def _has_application_blocking_attribution(diagnostics: dict[str, Any]) -> bool:
    reproductions = diagnostics.get("reproductions")
    if (not isinstance(reproductions, list) or not 1 <= len(reproductions) <= 2
            or any(not isinstance(item, dict) or not item.get("replicaToken")
                   or item.get("sameRevision") is not True for item in reproductions)):
        return False
    blocking = diagnostics.get("applicationBlockingEvidence")
    return bool(
        isinstance(blocking, dict)
        and blocking.get("kind") in {"thread_dump", "jfr", "async_profiler", "blockhound"}
        and isinstance(blocking.get("reproductionIndex"), int)
        and 0 <= blocking["reproductionIndex"] < len(reproductions)
        and isinstance(blocking.get("artifact"), str)
        and blocking["artifact"].strip()
    )


def classify_task8_9(
    *, events: dict[str, Any] | None, observation: dict[str, Any] | None,
    decisions: dict[str, Any], diagnostics: dict[str, Any] | None = None,
    setup_failed: bool = False,
) -> dict[str, Any]:
    diagnostics = diagnostics or {}
    if setup_failed:
        return _terminal("class_2a", "retry_fresh_end_to_end", rationale="setup failed before login")
    if not events:
        return _terminal("class_2b", "retry_historical_query", rationale="event evidence unavailable")
    outcome = events.get("outcome")
    if outcome in {"c_neither", "d_query_error"}:
        return _terminal("class_2b", "retry_historical_query", rationale="query ambiguous or failed")
    success = events.get("success") or {}
    skip = events.get("skip")
    if outcome == "a_success_only":
        read_version = (observation or {}).get("postLoginVersion")
        event_version = success.get("version")
        if (observation or {}).get("golden") is True and read_version == event_version:
            return _terminal("go", "retain_serving_revision", rationale="trace-correlated reset proven")
        if isinstance(read_version, int) and isinstance(event_version, int) and read_version > event_version:
            return _terminal("class_2c", "retry_low_traffic_window", rationale="later writer proven")
        return _terminal("class_2e", "investigate_persistence_or_read_consistency",
                         rationale="success event and non-golden state are not a later-write race")
    if not isinstance(skip, dict):
        return _terminal("class_2b", "retry_historical_query", rationale="skip payload missing")
    try:
        skip = validate_skip_event(skip, success_present=outcome == "e_both")
    except ProofError as error:
        return _terminal("class_2b", "repair_event_query_or_schema", rationale=str(error))
    reason = skip["reason"]
    if reason == "gateway_orchestration_error" or (
        outcome == "e_both" and reason == "reset_key_not_configured"
    ) or skip.get("impossibleWithSuccess"):
        return _terminal("class_1_immediate", "rollback_api_gateway", rollback=True,
                         rationale="trace evidence is impossible under correct gateway behavior")

    diagnosed: dict[str, Any]
    if reason.endswith("_connection_failure"):
        diagnosed = (
            _terminal("class_2d", "repair_downstream_or_network", rationale="loopback target is correct")
            if _expected_loopback(skip["leg"], skip["attemptedTarget"])
            else _terminal("class_1_diagnosed", "rollback_api_gateway", rollback=True,
                           rationale="attempted loopback target contradicts the fixed construction")
        )
    elif reason.endswith("_timeout"):
        phase = skip.get("overallTimeoutPhase")
        if reason == "overall_timeout" and phase in {
            "eligibility_pre_dispatch", "between_legs", "reset_post_response"
        }:
            reproductions = diagnostics.get("reproductions", [])
            if not isinstance(reproductions, list) or len(reproductions) > 2:
                diagnosed = _terminal(
                    "class_2d_unresolved", "reject_unbounded_reproduction_evidence",
                    resolved=False, rationale="gateway-local reproduction evidence exceeded its bound",
                )
            elif (_has_application_blocking_attribution(diagnostics)
                  or diagnostics.get("controlledIsolation") is True):
                diagnosed = _terminal("class_1_diagnosed", "rollback_api_gateway", rollback=True,
                                      rationale="gateway-local stall attributed to application code")
            else:
                diagnosed = _terminal("class_2d_unresolved", "collect_bounded_reproduction_evidence",
                                      resolved=False,
                                      rationale="gateway-local stall lacks code-specific evidence")
        else:
            budget_name = {
                "eligibility_timeout": "eligibilityTimeout",
                "reset_timeout": "resetTimeout",
                "overall_timeout": "overallTimeout",
            }[reason]
            try:
                budget_ms = _duration_seconds(str(decisions[budget_name])) * 1000
            except (KeyError, ProofError):
                return _terminal("class_2d_unresolved", "collect_serving_timeout_evidence",
                                 resolved=False, rationale="timeout budget unavailable")
            if skip["elapsedMillis"] >= 2 * budget_ms:
                diagnosed = _terminal("class_1_diagnosed", "rollback_api_gateway", rollback=True,
                                      rationale="timeout fired at least twice its serving budget")
            elif skip["elapsedMillis"] <= budget_ms + max(budget_ms * 0.1, 200):
                diagnosed = _terminal("class_2d", "resolve_downstream_latency",
                                      rationale="timeout fired within the explicit scheduling-jitter band")
            else:
                diagnosed = _terminal("class_2d", "collect_timeout_attribution_evidence",
                                      rationale="timeout elapsed value lies between attribution bands")
    elif reason == "reset_non_2xx_status" and skip["httpStatus"] == 409:
        if (skip["observedVersion"] != skip["submittedExpectedVersion"]
                or skip["selfCallCount"] > 1):
            diagnosed = _terminal("class_1_diagnosed", "rollback_api_gateway", rollback=True,
                                  rationale="reset 409 proves stale version capture or duplicate call")
        else:
            diagnosed = _terminal("class_2d", "resolve_concurrent_writer",
                                  rationale="single correctly versioned reset call raced legitimately")
    elif reason == "reset_non_2xx_status" and skip["httpStatus"] == 403:
        if skip["internalApiKeyConfigured"] and skip["internalApiKeyAttached"] is False:
            diagnosed = _terminal("class_1_diagnosed", "rollback_api_gateway", rollback=True,
                                  rationale="configured reset key was not attached")
        elif not skip["internalApiKeyConfigured"]:
            diagnosed = _terminal("class_1_diagnosed", "rollback_api_gateway", rollback=True,
                                  rationale="403 contradicts the no-dispatch unconfigured-key path")
        elif diagnostics.get("manualResetStatus") == 403:
            diagnosed = _terminal("class_2d", "repair_key_configuration",
                                  rationale="manual probe confirms environment key misalignment")
        elif diagnostics.get("manualResetStatus") in {200, 409}:
            diagnosed = _terminal("class_2d", "investigate_non_origin_403",
                                  rationale="fresh manual probe rules out current key misalignment")
        else:
            diagnosed = _terminal(
                "class_2d_unresolved", "collect_manual_reset_probe", resolved=False,
                rationale="required fresh manual reset observation is unavailable",
            )
    elif reason == "eligibility_non_2xx_status" and skip["httpStatus"] == 403:
        if skip["originVerifyRequired"] and skip["originVerifyHeaderAttached"] is False:
            diagnosed = _terminal("class_1_diagnosed", "rollback_api_gateway", rollback=True,
                                  rationale="required origin header was not attached")
        else:
            diagnosed = _terminal("class_2d", "investigate_non_origin_403",
                                  rationale="origin verification does not attribute this 403")
    elif reason == "reset_key_not_configured":
        if diagnostics.get("templateReference") == "regressed":
            diagnosed = _terminal("class_1_diagnosed", "rollback_api_gateway", rollback=True,
                                  rationale="serving revision regressed the key reference")
        elif diagnostics.get("templateReference") != "intact" or not diagnostics.get("replicaTokenRecovered"):
            diagnosed = _terminal("class_2d_unresolved", "collect_replica_correlated_probe",
                                  resolved=False, rationale="replica/template diagnosis unavailable")
        elif (diagnostics.get("manualResetStatus") == 503
              and diagnostics.get("manualResetEmitter") == "gateway"
              and diagnostics.get("sameReplica") is True):
            if diagnostics.get("presence") == "blank":
                diagnosed = _terminal("class_2h", "repair_key_value_or_restart_replica",
                                      rationale="same-replica independent presence probe corroborates blank")
            elif diagnostics.get("presence") == "nonblank":
                diagnosed = _terminal("class_1_diagnosed", "rollback_api_gateway", rollback=True,
                                      rationale="provider blank contradicts independent environment read")
            else:
                diagnosed = _terminal("class_2d_unresolved", "retry_presence_probe", resolved=False,
                                      rationale="presence probe did not yield a safe category")
        elif (diagnostics.get("manualResetStatus") in {200, 409}
              and _has_same_replica_attribution(diagnostics, skip.get("replicaToken"))):
            diagnosed = _terminal("class_1_diagnosed", "rollback_api_gateway", rollback=True,
                                  rationale="same-replica manual reset contradicts no-key event")
        elif diagnostics.get("manualResetStatus") in {200, 409}:
            diagnosed = _terminal(
                "class_2d_unresolved", "collect_replica_correlated_probe", resolved=False,
                rationale="manual reset did not prove same-replica emitter attribution",
            )
        else:
            diagnosed = _terminal("class_2d_unresolved", "collect_manual_reset_probe", resolved=False,
                                  rationale="manual reset diagnosis unavailable")
    elif reason == "eligibility_shape_failure" and diagnostics.get("requestMalformed"):
        diagnosed = _terminal("class_1_diagnosed", "rollback_api_gateway", rollback=True,
                              rationale="gateway emitted a malformed eligibility request")
    else:
        diagnosed = _terminal("class_2d", "resolve_diagnosed_operational_condition",
                              rationale="no revision-attributable evidence")

    if outcome == "e_both" and not diagnosed["rollbackAuthorized"]:
        if reason in {"overall_timeout", "reset_timeout"}:
            if reason == "overall_timeout" and skip.get("overallTimeoutPhase") == "reset_post_response":
                return diagnosed
            return _terminal("class_2f", "resolve_timeout_then_rerun",
                             rationale=diagnosed["rationale"])
        diagnosed = dict(diagnosed)
        diagnosed["dualEventTriage"] = "class_2g"
        return diagnosed
    return diagnosed


def _collect_diagnostics(
    config: ProofConfig, evidence: dict[str, Any], command_runner: CommandRunner,
    http_runner: HttpRunner, skip: dict[str, Any],
) -> dict[str, Any]:
    reason = str(skip.get("reason", "unknown"))
    combined: dict[str, Any] = {"available": True}
    evidence["diagnostics"].update(combined)
    try:
        if reason == "reset_key_not_configured":
            combined.update(_diagnose_unconfigured_key(
                config, evidence, command_runner, http_runner, skip
            ))
        elif (reason == "reset_non_2xx_status" and skip.get("httpStatus") == 403
              and skip.get("internalApiKeyConfigured") is True
              and skip.get("internalApiKeyAttached") is True):
            combined.update(_manual_reset_probe(config, evidence, http_runner))
        elif reason == "overall_timeout" and skip.get("overallTimeoutPhase") in {
            "eligibility_pre_dispatch", "between_legs", "reset_post_response",
        }:
            plan = {
                "maximumAttempts": 2,
                "eachAttempt": [
                    "fresh_identity_version_read", "deliberate_non_golden_write",
                    "strict_idle_aging", "fresh_trace_login", "both_event_queries",
                ],
            }
            combined.update({
                "available": False,
                "requiresSeparateApproval": True,
                "boundedReproductionPlan": plan,
                "unresolved": "bounded reproduction performs additional production writes",
            })
            evidence["diagnostics"]["operations"].append({
                "name": "bounded_reproduction",
                "executable": False,
                "requiresSeparateApproval": True,
                "mutating": True,
                "plan": plan,
            })
        else:
            combined["directEventDiagnosticsOnly"] = True
    except Exception as error:
        combined.update({
            "available": False,
            "unresolved": "concrete diagnostic observation failed",
            "errorCategory": type(error).__name__,
            "error": str(error),
        })
    evidence["diagnostics"].update(combined)
    return dict(evidence["diagnostics"])


def _diagnostic_step(evidence: dict[str, Any], name: str, **inputs: Any) -> None:
    evidence["diagnostics"]["operations"].append({"name": name, "inputs": inputs})


def _template_key_reference(
    config: ProofConfig, evidence: dict[str, Any], runner: CommandRunner, revision: str,
) -> str | None:
    _diagnostic_step(evidence, "compare_revision_template", revision=revision)
    rows = _decode_json(
        _record_command(
            evidence, runner,
            ["az", "containerapp", "revision", "show", "--name", config.gateway_app,
             "--resource-group", config.resource_group, "--revision", revision,
             "--query", "properties.template.containers[0].env", "-o", "json"],
            label=f"gateway environment variable identity for {revision}",
            timeout_seconds=config.operation_timeout_seconds,
        ),
        f"gateway environment variable identity for {revision}",
    )
    if not isinstance(rows, list):
        raise ProofError("gateway environment variable identity is not a list")
    matching = [row for row in rows if isinstance(row, dict)
                and row.get("name") == "INTERNAL_API_KEY"]
    if len(matching) > 1:
        raise ProofError("gateway template has duplicate INTERNAL_API_KEY references")
    if not matching:
        return None
    reference = matching[0].get("secretRef")
    return reference if isinstance(reference, str) and reference else None


def _recover_emitter_replica(
    config: ProofConfig, evidence: dict[str, Any], runner: CommandRunner,
    event_token: str,
) -> tuple[str | None, list[str], bool]:
    revision = evidence["serving"][config.gateway_app]["revision"]
    image = evidence["serving"][config.gateway_app]["image"]
    _diagnostic_step(evidence, "recover_replica_token", revision=revision,
                     emitterReplicaToken=event_token)
    rows = _decode_json(
        _record_command(
            evidence, runner,
            ["az", "containerapp", "replica", "list", "--name", config.gateway_app,
             "--resource-group", config.resource_group, "--revision", revision, "-o", "json"],
            label="diagnostic gateway replica list",
            timeout_seconds=config.operation_timeout_seconds,
        ),
        "diagnostic gateway replica list",
    )
    if not isinstance(rows, list) or any(not isinstance(row, dict)
                                         or not isinstance(row.get("name"), str) for row in rows):
        raise ProofError("diagnostic gateway replica list is malformed")
    names = [row["name"] for row in rows]
    matches: list[str] = []
    for name in names:
        command = [
            "docker", "run", "--rm", "--entrypoint", "java", image,
            "-jar", "/replica-token.jar", name,
        ]
        result = _record_command(
            evidence, runner, command, label=f"replica token tool for {name}",
            timeout_seconds=config.operation_timeout_seconds,
        )
        if result.stderr != "" or re.fullmatch(r"[0-9a-f]{12}\n", result.stdout) is None:
            raise ProofError(f"replica token tool contract failed for {name}")
        if result.stdout[:-1] == event_token:
            matches.append(name)
    if len(matches) != 1:
        raise ProofError(
            f"emitter replica token matched {len(matches)} live replicas; observed={names!r}"
        )
    return matches[0], names, True


def _header(headers: dict[str, str], name: str) -> str | None:
    matches = [value for key, value in headers.items() if key.lower() == name.lower()]
    return matches[0] if len(matches) == 1 and isinstance(matches[0], str) else None


def _manual_reset_probe(
    config: ProofConfig, evidence: dict[str, Any], runner: HttpRunner,
) -> dict[str, Any]:
    _diagnostic_step(evidence, "manual_reset_probe")
    portfolio = _read_portfolio(
        config, evidence, runner, config.access_token,
        timeout_seconds=config.operation_timeout_seconds,
    )
    response = _record_http(
        evidence, runner, method="PUT",
        url=config.gateway_url + "/api/portfolio/demo-reset",
        headers=_auth(config.access_token),
        json_body={"expectedVersion": portfolio["version"]}, mutating=True,
        timeout_seconds=config.operation_timeout_seconds,
    )
    body = response.body if isinstance(response.body, dict) else {}
    emitter = None
    if response.status == 503 and body.get("error") == "internal_api_key_not_configured":
        emitter = "gateway" if set(body) == {"error", "message"} else (
            "portfolio-service" if set(body) == {"error"} else None
        )
    return {
        "manualResetStatus": response.status,
        "manualResetEmitter": emitter,
        "manualResetExpectedVersion": portfolio["version"],
        "probeReplicaToken": _header(response.headers, "X-Gateway-Replica-Token"),
    }


def _diagnose_unconfigured_key(
    config: ProofConfig, evidence: dict[str, Any], command_runner: CommandRunner,
    http_runner: HttpRunner, skip: dict[str, Any],
) -> dict[str, Any]:
    last_good = config.last_known_good_gateway_revision
    if not isinstance(last_good, str) or not last_good:
        raise ProofError("last-known-good gateway revision is required for key diagnosis")
    current = evidence["serving"][config.gateway_app]["revision"]
    current_ref = _template_key_reference(config, evidence, command_runner, current)
    previous_ref = _template_key_reference(config, evidence, command_runner, last_good)
    if previous_ref is None:
        raise ProofError("last-known-good gateway template lacks an INTERNAL_API_KEY reference")
    if current_ref != previous_ref:
        result = {
            "templateReference": "regressed",
            "currentTemplateReference": current_ref,
            "lastGoodTemplateReference": previous_ref,
        }
        evidence["diagnostics"].update(result)
        return result
    event_token = skip.get("replicaToken")
    if not isinstance(event_token, str) or re.fullmatch(r"[0-9a-f]{12}", event_token) is None:
        raise ProofError("skip event has no valid emitter replica token")
    raw_name, fleet, recovered = _recover_emitter_replica(
        config, evidence, command_runner, event_token
    )
    result: dict[str, Any] = {
        "templateReference": "intact",
        "currentTemplateReference": current_ref,
        "lastGoodTemplateReference": previous_ref,
        "replicaTokenRecovered": recovered,
        "emitterReplicaToken": event_token,
        "emitterReplicaName": raw_name,
        "emitterStillServing": raw_name in fleet,
        "fleetReplicaNames": fleet,
    }
    evidence["diagnostics"].update(result)
    attempts: list[dict[str, Any]] = []
    for _ in range(max(1, min(6, 2 * len(fleet)))):
        probe = _manual_reset_probe(config, evidence, http_runner)
        attempts.append(dict(probe))
        result.update(probe)
        result["manualResetAttempts"] = list(attempts)
        evidence["diagnostics"].update(result)
        if probe.get("probeReplicaToken") == event_token:
            result["sameReplica"] = True
            evidence["diagnostics"].update(result)
            break
    else:
        result["sameReplica"] = False
        evidence["diagnostics"].update(result)
    result["manualResetAttempts"] = attempts
    evidence["diagnostics"].update(result)

    if (result.get("sameReplica") is True and raw_name is not None
            and result.get("manualResetStatus") == 503
            and result.get("manualResetEmitter") == "gateway"):
        _diagnostic_step(evidence, "presence_probe", replica=raw_name, revision=current)
        presence = _record_command(
            evidence, command_runner,
            ["az", "containerapp", "exec", "--name", config.gateway_app,
             "--resource-group", config.resource_group, "--revision", current,
             "--replica", raw_name, "--container", config.gateway_app,
             "--command", "java -jar /probe.jar"],
            label="same-replica internal key presence probe",
            timeout_seconds=config.operation_timeout_seconds,
        ).stdout
        if presence not in {"blank\n", "nonblank\n"}:
            raise ProofError("same-replica presence probe returned an invalid category")
        result["presence"] = presence.strip()
        evidence["diagnostics"].update(result)
    return result


def _set_threshold(
    config: ProofConfig, evidence: dict[str, Any], runner: CommandRunner, value: str
) -> None:
    _record_command(
        evidence, runner,
        ["az", "containerapp", "update", "--name", config.gateway_app,
         "--resource-group", config.resource_group, "--set-env-vars",
         "APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD=" + value, "-o", "json"],
        label="idle-threshold update", mutating=True,
        timeout_seconds=config.operation_timeout_seconds,
    )


def _read_threshold(config: ProofConfig, evidence: dict[str, Any], runner: CommandRunner) -> str:
    revisions = _decode_json(
        _record_command(
            evidence, runner,
            ["az", "containerapp", "revision", "list", "--name", config.gateway_app,
             "--resource-group", config.resource_group,
             "--query", "[?properties.active && properties.trafficWeight == `100`].{name:name,image:properties.template.containers[0].image}",
             "-o", "json"],
            label="idle-threshold serving revision",
            timeout_seconds=config.operation_timeout_seconds,
        ),
        "idle-threshold serving revision",
    )
    if not isinstance(revisions, list) or len(revisions) != 1 or not revisions[0].get("name"):
        raise ProofError("idle-threshold readback requires exactly one serving revision")
    rows = _decode_json(
        _record_command(
            evidence, runner,
            ["az", "containerapp", "revision", "show", "--name", config.gateway_app,
             "--resource-group", config.resource_group, "--revision", revisions[0]["name"],
             "--query", "properties.template.containers[0].env", "-o", "json"],
            label="idle-threshold verification",
            timeout_seconds=config.operation_timeout_seconds,
        ),
        "idle-threshold verification",
    )
    if not isinstance(rows, list):
        raise ProofError("idle-threshold serving readback is not an environment list")
    values = {row["name"]: row.get("value") for row in rows
              if isinstance(row, dict) and isinstance(row.get("name"), str)}
    return values.get(
        "APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD",
        _authoritative_yaml_defaults()["APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD"],
    )


def _cleanup(
    config: ProofConfig, evidence: dict[str, Any], runner: HttpRunner,
    golden: list[dict[str, str]], monotonic: Callable[[], float]
) -> None:
    cleanup = evidence["cleanup"]
    deadline = monotonic() + config.cleanup_deadline_seconds
    for number in range(1, config.cleanup_max_attempts + 1):
        remaining = deadline - monotonic()
        if remaining <= 0:
            cleanup["deadlineExceeded"] = True
            break
        attempt: dict[str, Any] = {"number": number}
        cleanup["attempts"].append(attempt)
        try:
            portfolio = _read_portfolio(
                config, evidence, runner, config.access_token,
                timeout_seconds=min(config.operation_timeout_seconds, remaining),
            )
            attempt["observedVersion"] = portfolio["version"]
            remaining = deadline - monotonic()
            if remaining <= 0:
                cleanup["deadlineExceeded"] = True
                attempt["error"] = "cleanup deadline exhausted after identity/version read"
                break
            response = _record_http(
                evidence, runner, method="PUT",
                url=config.gateway_url + "/api/portfolio/demo-reset",
                headers=_auth(config.access_token),
                json_body={"expectedVersion": portfolio["version"]}, mutating=True,
                timeout_seconds=min(config.operation_timeout_seconds, remaining),
            )
            attempt["status"] = response.status
            if monotonic() > deadline:
                cleanup["deadlineExceeded"] = True
                attempt["error"] = "cleanup deadline exceeded while reset was in flight"
                break
            if response.status == 409:
                cleanup["conflictObserved"] = True
                continue
            if response.status == 200:
                cleanup["succeeded"] = True
                break
        except Exception as error:  # cleanup records and keeps trying inside its global bound
            attempt["error"] = str(error)
    verification_deadline = monotonic() + config.post_cleanup_verification_seconds
    try:
        verification_remaining = verification_deadline - monotonic()
        if verification_remaining <= 0:
            raise ProofError("post-cleanup verification allowance exhausted before read")
        final_portfolio = _read_portfolio(
            config, evidence, runner, config.access_token,
            timeout_seconds=min(config.operation_timeout_seconds, verification_remaining),
        )
        if monotonic() > verification_deadline:
            raise ProofError("post-cleanup verification exceeded its bounded allowance")
        cleanup["postCleanupVersion"] = final_portfolio["version"]
        cleanup["postCleanupHoldings"] = _wire_holdings(final_portfolio["holdings"])
        cleanup["postCleanupGolden"] = _is_golden(final_portfolio, golden)
    except Exception as error:
        cleanup["postCleanupError"] = str(error)


def run_proof(
    config: ProofConfig, *, command_runner: CommandRunner = _default_command_runner,
    http_runner: HttpRunner = _default_http_runner,
    now: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
    monotonic: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
    trace_factory: Callable[[], str] = _new_traceparent,
) -> ProofResult:
    evidence = _initial_evidence(config)
    golden: list[dict[str, str]] = []
    override_applied = False
    candidate_go = False
    secrets_to_remove = [config.access_token, config.demo_password]
    try:
        _validate_config(config)
        golden = _preflight(config, evidence, command_runner)
        if config.mode in {"preflight", "rehearsal"}:
            evidence["verdict"] = {
                "go": None,
                "status": config.mode + "_passed",
                "errors": [],
            }
            return ProofResult(0, redact_evidence(evidence, secrets_to_remove))
        if not config.access_token or not config.demo_password:
            raise ProofError("execute mode requires injected setup token and demo password")

        before = _read_portfolio(
            config, evidence, http_runner, config.access_token,
            timeout_seconds=config.operation_timeout_seconds,
        )
        evidence["setup"]["beforeVersion"] = before["version"]
        evidence["cleanup"]["armed"] = True

        if config.threshold_override is not None:
            # Restoration is owed as soon as the mutating request is issued: a transport
            # failure may hide an update that committed successfully.
            override_applied = True
            _set_threshold(config, evidence, command_runner, config.threshold_override)
            observed_override = _read_threshold(config, evidence, command_runner)
            evidence["decisions"]["effectiveIdleThreshold"] = observed_override
            if observed_override != config.threshold_override:
                raise ProofError("threshold override readback did not match requested value")
        else:
            evidence["decisions"]["effectiveIdleThreshold"] = config.idle_threshold

        write = _record_http(
            evidence, http_runner, method="PUT",
            url=config.gateway_url + "/api/portfolio/holdings",
            headers=_auth(config.access_token),
            json_body=_non_golden_write(before["version"], golden), mutating=True,
            timeout_seconds=config.operation_timeout_seconds,
        )
        if write.status != 200:
            raise ProofError(f"deliberate non-golden write returned HTTP {write.status}")
        written = _select_portfolio(write.body, config.expected_user_id)
        evidence["setup"]["writeVersion"] = written["version"]
        evidence["setup"]["nonGoldenHoldings"] = _wire_holdings(written["holdings"])
        if written["version"] <= before["version"]:
            raise ProofError("deliberate non-golden write did not advance version")
        if _is_golden(written, golden):
            raise ProofError("deliberate setup write remained golden")

        persisted_updated_at = _parse_instant(written.get("updatedAt"), "persisted updatedAt")
        evidence["setup"]["persistedUpdatedAt"] = _utc(persisted_updated_at)

        age_seconds = _duration_seconds(
            config.threshold_override if config.threshold_override is not None else config.idle_threshold
        ) + 0.001
        evidence["setup"]["ageWaitSeconds"] = age_seconds
        sleep(age_seconds)
        aged_at = now().astimezone(timezone.utc)
        if (aged_at - persisted_updated_at).total_seconds() <= age_seconds - 0.001:
            raise ProofError("persisted updatedAt did not age strictly past the effective threshold")
        evidence["setup"]["agedAt"] = _utc(aged_at)
        _revalidate_serving(
            config, evidence, command_runner, stage="afterAge",
            expected_idle=(config.threshold_override or config.idle_threshold),
            allow_revision_change=config.threshold_override is not None,
        )

        traceparent = trace_factory()
        match = TRACEPARENT_RE.fullmatch(traceparent)
        if not match or set(match.group(1)) == {"0"} or set(match.group(2)) == {"0"}:
            raise ProofError("trace factory returned an invalid W3C traceparent")
        trace_id = match.group(1)
        window_start = now().astimezone(timezone.utc)
        evidence["trace"] = {"traceparent": traceparent, "traceId": trace_id}

        login_error = None
        login_response: HttpResponse | None = None
        try:
            login_response = _record_http(
                evidence, http_runner, method="POST",
                url=config.gateway_url + "/api/auth/login",
                headers={"traceparent": traceparent},
                json_body={"email": config.demo_email, "password": config.demo_password},
                mutating=True, timeout_seconds=config.operation_timeout_seconds,
            )
            evidence["login"]["status"] = login_response.status
        except Exception as error:
            login_error = str(error)
            evidence["login"]["error"] = login_error

        post: dict[str, Any] | None = None
        if login_response is not None and login_response.status == 200:
            body = login_response.body
            token = body.get("token") if isinstance(body, dict) else None
            if isinstance(token, str) and token:
                try:
                    post = _read_portfolio(
                        config, evidence, http_runner, token,
                        timeout_seconds=config.operation_timeout_seconds,
                    )
                    evidence["observation"] = {
                        "postLoginVersion": post["version"],
                        "holdings": _wire_holdings(post["holdings"]),
                        "golden": _is_golden(post, golden),
                        "identityVerified": True,
                    }
                except Exception as error:
                    evidence["observation"] = {
                        "golden": False, "identityVerified": False, "error": str(error)
                    }
            else:
                evidence["observation"] = {
                    "golden": False, "identityVerified": False,
                    "error": "login response did not contain a token",
                }
        else:
            evidence["observation"] = {
                "golden": False, "identityVerified": False,
                "error": login_error or "login did not return HTTP 200",
            }

        window_end = now().astimezone(timezone.utc) + timedelta(
            seconds=config.poll_deadline_seconds
        )
        if window_end <= window_start:
            raise ProofError("UTC clock did not advance across the login observation")
        evidence["trace"]["windowStart"] = _utc(window_start)
        evidence["trace"]["windowEnd"] = _utc(window_end)
        events = _poll_events(
            config, evidence, command_runner, trace_id=trace_id,
            start=window_start, end=window_end, monotonic=monotonic, sleep=sleep,
        )
        evidence["events"] = events
        diagnostics: dict[str, Any] = {}
        if isinstance(events.get("skip"), dict):
            diagnostics = _collect_diagnostics(
                config, evidence, command_runner, http_runner, events["skip"]
            )
        detail = classify_task8_9(
            events=events, observation=evidence["observation"],
            decisions={
                "eligibilityTimeout": config.eligibility_timeout,
                "resetTimeout": config.reset_timeout,
                "overallTimeout": config.overall_timeout,
            },
            diagnostics=diagnostics,
        )
        evidence["classificationDetail"] = detail
        evidence["classification"] = detail["class"]
        success = events.get("success") or {}
        evidence["keyAlignment"] = {
            "internalApiKeyProven": bool(success),
            "proof": "trace_correlated_demo_reset_succeeded" if success else "not_proven",
            "originVerification": "not_applicable_on_azure",
        }
        candidate_go = detail["class"] == "go"
    except Exception as error:
        evidence["verdict"]["errors"].append(str(error))
        if not evidence["classificationDetail"]:
            detail = classify_task8_9(
                events=evidence.get("events") if evidence.get("events") else None,
                observation=evidence.get("observation"), decisions={},
                setup_failed=evidence["requestCounts"]["logins"] == 0,
            )
            evidence["classificationDetail"] = detail
            evidence["classification"] = detail["class"]
    finally:
        if evidence["cleanup"]["armed"]:
            _cleanup(config, evidence, http_runner, golden, monotonic)
        if override_applied:
            evidence["thresholdRestore"]["attempted"] = True
            try:
                _set_threshold(config, evidence, command_runner, config.idle_threshold)
                restored = _read_threshold(config, evidence, command_runner)
                evidence["thresholdRestore"]["readBack"] = restored
                evidence["thresholdRestore"]["verified"] = restored == config.idle_threshold
            except Exception as error:
                evidence["thresholdRestore"]["error"] = str(error)
                evidence["thresholdRestore"]["verified"] = False
        if evidence["preflight"]["passed"] and evidence["cleanup"]["armed"]:
            try:
                _revalidate_serving(
                    config, evidence, command_runner, stage="final",
                    expected_idle=config.idle_threshold,
                    allow_revision_change=config.threshold_override is not None,
                )
            except Exception as error:
                evidence["servingRevalidation"]["final"] = {
                    "matched": False, "error": str(error)
                }
                evidence["verdict"]["errors"].append(str(error))

    cleanup_ok = (
        not evidence["cleanup"]["armed"]
        or (
            evidence["cleanup"]["succeeded"]
            and evidence["cleanup"]["postCleanupGolden"]
            and not evidence["cleanup"]["conflictObserved"]
        )
    )
    restore_ok = evidence["thresholdRestore"]["verified"]
    no_errors = not evidence["verdict"]["errors"]
    final_revalidated = evidence["servingRevalidation"].get("final", {}).get("matched") is True
    final_go = (
        candidate_go and cleanup_ok and restore_ok and no_errors
        and config.threshold_override is None and final_revalidated
        and evidence["provider"] == "azure"
        and evidence["keyAlignment"]["internalApiKeyProven"]
    )
    evidence["verdict"]["go"] = final_go
    evidence["verdict"]["status"] = "go" if final_go else (
        "diagnostic_only" if candidate_go and config.threshold_override is not None else "non_go"
    )
    evidence["verdict"]["exitCode"] = 0 if final_go else 1
    return ProofResult(0 if final_go else 1, redact_evidence(evidence, secrets_to_remove))


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", required=True, choices=("preflight", "rehearsal", "execute"))
    parser.add_argument("--target", required=True)
    parser.add_argument("--subscription", required=True)
    parser.add_argument("--resource-group", required=True)
    parser.add_argument("--gateway-app", required=True)
    parser.add_argument("--portfolio-app", required=True)
    parser.add_argument("--workspace", required=True)
    parser.add_argument("--registry", required=True)
    parser.add_argument("--gateway-url", required=True)
    parser.add_argument("--repository-sha", required=True)
    parser.add_argument("--run-attempt", required=True)
    parser.add_argument("--deployment-manifest", type=Path, required=True)
    parser.add_argument("--deployment-manifest-run-attempt", type=Path, required=True)
    parser.add_argument("--gateway-repository", required=True)
    parser.add_argument("--portfolio-repository", required=True)
    parser.add_argument("--evidence-output", type=Path)
    parser.add_argument("--access-token-env", default="TASK8_9_ACCESS_TOKEN")
    parser.add_argument("--demo-password-env", default="TASK8_9_DEMO_PASSWORD")
    parser.add_argument("--idle-threshold", default="30m")
    parser.add_argument("--eligibility-timeout", default="2s")
    parser.add_argument("--reset-timeout", default="2s")
    parser.add_argument("--overall-timeout", default="4s")
    parser.add_argument("--threshold-override")
    parser.add_argument("--poll-interval-seconds", type=float, default=5.0)
    parser.add_argument("--poll-deadline-seconds", type=float, default=60.0)
    parser.add_argument("--cleanup-max-attempts", type=int, default=3)
    parser.add_argument("--cleanup-deadline-seconds", type=float, default=30.0)
    parser.add_argument("--operation-timeout-seconds", type=float, default=15.0)
    parser.add_argument("--post-cleanup-verification-seconds", type=float, default=5.0)
    parser.add_argument("--last-known-good-gateway-revision")
    return parser


def main(
    argv: list[str] | None = None, *, command_runner: CommandRunner = _default_command_runner,
    http_runner: HttpRunner = _default_http_runner,
    environ: dict[str, str] | os._Environ[str] = os.environ,
    output: Callable[[str], None] = print,
    now: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
    monotonic: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
    trace_factory: Callable[[], str] = _new_traceparent,
) -> int:
    args = _parser().parse_args(argv)
    try:
        deployment_manifest = json.loads(args.deployment_manifest.read_text(encoding="utf-8"))
        manifest_attempt_marker = args.deployment_manifest_run_attempt.read_text(encoding="utf-8")
        if not isinstance(deployment_manifest, dict):
            raise ProofError("deployment manifest root must be an object")
        config = ProofConfig(
            mode=args.mode,
            target=args.target,
            subscription_id=args.subscription,
            resource_group=args.resource_group,
            gateway_app=args.gateway_app,
            portfolio_app=args.portfolio_app,
            workspace_name=args.workspace,
            registry_name=args.registry,
            gateway_url=args.gateway_url.rstrip("/"),
            repository_sha=args.repository_sha,
            run_attempt=args.run_attempt,
            deployment_manifest=deployment_manifest,
            manifest_attempt_marker=manifest_attempt_marker,
            service_repositories={
                "api-gateway": args.gateway_repository,
                "portfolio-service": args.portfolio_repository,
            },
            access_token=environ.get(args.access_token_env, ""),
            demo_password=environ.get(args.demo_password_env, ""),
            idle_threshold=args.idle_threshold,
            eligibility_timeout=args.eligibility_timeout,
            reset_timeout=args.reset_timeout,
            overall_timeout=args.overall_timeout,
            threshold_override=args.threshold_override,
            poll_interval_seconds=args.poll_interval_seconds,
            poll_deadline_seconds=args.poll_deadline_seconds,
            cleanup_max_attempts=args.cleanup_max_attempts,
            cleanup_deadline_seconds=args.cleanup_deadline_seconds,
            operation_timeout_seconds=args.operation_timeout_seconds,
            post_cleanup_verification_seconds=args.post_cleanup_verification_seconds,
            last_known_good_gateway_revision=args.last_known_good_gateway_revision,
        )
        result = run_proof(
            config, command_runner=command_runner, http_runner=http_runner,
            now=now, monotonic=monotonic, sleep=sleep, trace_factory=trace_factory,
        )
        document = result.evidence
        exit_code = result.exit_code
    except Exception as error:
        document = redact_evidence({
            "schemaVersion": 1,
            "verdict": {"go": False, "status": "invalid_invocation", "exitCode": 2,
                        "errors": [str(error)]},
        }, [environ.get(args.access_token_env, ""), environ.get(args.demo_password_env, "")])
        exit_code = 2
    rendered = json.dumps(document, sort_keys=True, indent=2)
    if args.evidence_output is not None:
        args.evidence_output.write_text(rendered + "\n", encoding="utf-8")
    output(rendered)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
