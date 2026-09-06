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


@dataclass(frozen=True)
class ProofResult:
    exit_code: int
    evidence: dict[str, Any]


CommandRunner = Callable[[list[str]], CommandResult]
HttpRunner = Callable[..., HttpResponse]


def validate_deployment_manifest(
    document: dict[str, Any], *, expected_attempt: str, expected_repository_sha: str
) -> dict[str, Any]:
    if str(document.get("runAttempt", "")) != str(expected_attempt):
        raise ProofError("deployment manifest is not from the current run attempt")
    repository_sha = document.get("repositorySha")
    if not isinstance(repository_sha, str) or not REPOSITORY_SHA_RE.fullmatch(repository_sha):
        raise ProofError("deployment manifest requires a lowercase repository SHA")
    if repository_sha != expected_repository_sha:
        raise ProofError("deployment manifest repository SHA does not match the approved commit")
    services = document.get("services")
    if not isinstance(services, dict) or set(services) != set(SERVICES):
        raise ProofError("deployment manifest must contain exactly the two Task 8.9 services")
    for service in SERVICES:
        identity = services.get(service)
        if not isinstance(identity, dict) or set(identity) != {"repository", "digest"}:
            raise ProofError(f"{service} manifest identity must contain repository and digest")
        repository = identity.get("repository")
        digest = identity.get("digest")
        if not isinstance(repository, str) or not repository or "@" in repository:
            raise ProofError(f"{service} manifest repository is invalid")
        if not isinstance(digest, str) or not DIGEST_RE.fullmatch(digest):
            raise ProofError(f"{service} manifest digest must be lowercase sha256")
    return document


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


def _default_command_runner(command: list[str]) -> CommandResult:
    completed = subprocess.run(
        command, check=False, capture_output=True, text=True, encoding="utf-8"
    )
    return CommandResult(completed.returncode, completed.stdout, completed.stderr)


def _default_http_runner(
    *, method: str, url: str, headers: dict[str, str], json_body: Any = None
) -> HttpResponse:
    body = None if json_body is None else json.dumps(json_body).encode("utf-8")
    request_headers = dict(headers)
    if body is not None:
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(
        url, data=body, headers=request_headers, method=method
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
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
        },
        "serving": {},
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
    mutating: bool = False
) -> CommandResult:
    evidence["operations"].append(
        {"kind": "azure_cli" if command[0] == "az" else "local_cli", "argv": command, "mutating": mutating}
    )
    result = runner(command)
    if result.returncode != 0:
        raise ProofError(f"{label} failed: {(result.stderr or result.stdout).strip()}")
    return result


def _record_http(
    evidence: dict[str, Any], runner: HttpRunner, *, method: str, url: str,
    headers: dict[str, str], json_body: Any = None, mutating: bool
) -> HttpResponse:
    safe: dict[str, Any] = {
        "kind": "http",
        "method": method,
        "url": url,
        "headerNames": sorted(headers),
        "mutating": mutating,
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
    return runner(method=method, url=url, headers=headers, json_body=json_body)


def _load_oracle(evidence: dict[str, Any], runner: CommandRunner, expected_user_id: str) -> list[dict[str, str]]:
    result = _record_command(
        evidence, runner, [sys.executable, "-B", str(ORACLE)], label="Task 4.4a golden oracle"
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


def _preflight(config: ProofConfig, evidence: dict[str, Any], runner: CommandRunner) -> list[dict[str, str]]:
    manifest = validate_deployment_manifest(
        config.deployment_manifest,
        expected_attempt=config.run_attempt,
        expected_repository_sha=config.repository_sha,
    )
    account = _record_command(
        evidence, runner, ["az", "account", "show", "--query", "id", "-o", "tsv"],
        label="Azure subscription identity",
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
            _record_command(evidence, runner, command, label=f"{service} serving revisions"),
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
    )
    for service in SERVICES:
        identity = evidence["serving"][service]
        _record_command(
            evidence, runner,
            ["az", "acr", "manifest", "show-metadata", "--registry", config.registry_name,
             "--name", _acr_repository_name(identity["repository"], config.registry_name)
             + "@" + identity["digest"], "-o", "json"],
            label=f"{service} ACR pull-access rehearsal",
        )
    _record_command(
        evidence, runner,
        ["az", "acr", "login", "--name", config.registry_name],
        label="ACR authentication rehearsal",
    )
    for service in SERVICES:
        _record_command(
            evidence, runner,
            ["docker", "pull", evidence["serving"][service]["image"]],
            label=f"{service} immutable image pull rehearsal",
        )
    _record_command(
        evidence, runner,
        ["az", "monitor", "log-analytics", "query", "--workspace", workspace_id,
         "--analytics-query", "print task8_9_rbac_probe=1", "-o", "json"],
        label="Log Analytics query RBAC rehearsal",
    )
    env_rows = _decode_json(
        _record_command(
            evidence, runner,
            ["az", "containerapp", "show", "--name", config.gateway_app,
             "--resource-group", config.resource_group,
             "--query", "properties.template.containers[0].env", "-o", "json"],
            label="Wave 8 decision readback",
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
    for name, expected in approved.items():
        if names.get(name) != expected:
            raise ProofError(f"serving {name} does not equal the approved value")
    evidence["decisions"]["serving"] = {
        "idleThreshold": names["APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD"],
        "eligibilityTimeout": names["APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT"],
        "resetTimeout": names["APP_DEMO_LOGIN_RESET_RESET_TIMEOUT"],
        "overallTimeout": names["APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT"],
    }
    evidence["preflight"] = {"passed": True, "rbacRehearsed": True}
    return _load_oracle(evidence, runner, config.expected_user_id)


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
    config: ProofConfig, evidence: dict[str, Any], runner: HttpRunner, token: str
) -> dict[str, Any]:
    response = _record_http(
        evidence, runner, method="GET", url=config.gateway_url + "/api/portfolio",
        headers=_auth(token), mutating=False,
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
    if len(matches) > 1:
        raise ProofError(f"multiple {event} events found for one trace")
    return matches[0] if matches else None


def _query_once(
    config: ProofConfig, evidence: dict[str, Any], runner: CommandRunner, *, query: str,
    label: str
) -> tuple[Any | None, str | None]:
    command = [
        "az", "monitor", "log-analytics", "query", "--workspace",
        evidence["target"]["workspaceCustomerId"], "--analytics-query", query,
        "--timespan", evidence["trace"]["windowStart"] + "/" + evidence["trace"]["windowEnd"],
        "-o", "json",
    ]
    evidence["operations"].append({"kind": "azure_cli", "argv": command, "mutating": False})
    result = runner(command)
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
    began = monotonic()
    attempts = max(1, math.floor(config.poll_deadline_seconds / config.poll_interval_seconds) + 1)
    for attempt in range(attempts):
        for kind in ("success", "skip"):
            rows, error = _query_once(config, evidence, runner, query=queries[kind], label=kind)
            if error:
                errors.append(error)
            elif found[kind] is None:
                event_name = "demo_reset_succeeded" if kind == "success" else "demo_reset_self_call_skipped"
                found[kind] = parse_event_rows(rows, event=event_name, trace_id=trace_id)
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
    return {
        "outcome": outcome,
        "success": found["success"],
        "skip": found["skip"],
        "queryError": "; ".join(errors) if errors else None,
        "queries": queries,
        "pollIntervalSeconds": config.poll_interval_seconds,
        "pollDeadlineSeconds": config.poll_deadline_seconds,
    }


def _classification(events: dict[str, Any]) -> str:
    outcome = events["outcome"]
    skip = events.get("skip") or {}
    reason = skip.get("reason")
    if outcome == "a_success_only":
        return "go_candidate"
    if outcome in {"c_neither", "d_query_error"}:
        return "class_2b"
    if reason == "gateway_orchestration_error":
        return "class_1_immediate"
    if outcome == "e_both":
        if reason == "reset_key_not_configured":
            return "class_1_immediate"
        if reason in {"overall_timeout", "reset_timeout"}:
            phase = skip.get("overallTimeoutPhase")
            if reason == "overall_timeout" and phase in {
                "eligibility_pre_dispatch", "eligibility_in_flight", "between_legs"
            }:
                return "class_1_immediate"
            return "class_2f"
        return "class_2g"
    return "diagnosis_required"


def _set_threshold(
    config: ProofConfig, evidence: dict[str, Any], runner: CommandRunner, value: str
) -> None:
    _record_command(
        evidence, runner,
        ["az", "containerapp", "update", "--name", config.gateway_app,
         "--resource-group", config.resource_group, "--set-env-vars",
         "APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD=" + value, "-o", "json"],
        label="idle-threshold update", mutating=True,
    )


def _read_threshold(config: ProofConfig, evidence: dict[str, Any], runner: CommandRunner) -> str:
    return _record_command(
        evidence, runner,
        ["az", "containerapp", "show", "--name", config.gateway_app,
         "--resource-group", config.resource_group,
         "--query", "properties.template.containers[0].env[?name=='APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD'].value | [0]",
         "-o", "tsv"],
        label="idle-threshold verification",
    ).stdout.strip()


def _cleanup(
    config: ProofConfig, evidence: dict[str, Any], runner: HttpRunner,
    golden: list[dict[str, str]], monotonic: Callable[[], float]
) -> None:
    cleanup = evidence["cleanup"]
    deadline = monotonic() + config.cleanup_deadline_seconds
    for number in range(1, config.cleanup_max_attempts + 1):
        if monotonic() > deadline:
            cleanup["deadlineExceeded"] = True
            break
        attempt: dict[str, Any] = {"number": number}
        cleanup["attempts"].append(attempt)
        try:
            portfolio = _read_portfolio(config, evidence, runner, config.access_token)
            attempt["observedVersion"] = portfolio["version"]
            response = _record_http(
                evidence, runner, method="PUT",
                url=config.gateway_url + "/api/portfolio/demo-reset",
                headers=_auth(config.access_token),
                json_body={"expectedVersion": portfolio["version"]}, mutating=True,
            )
            attempt["status"] = response.status
            if response.status == 409:
                cleanup["conflictObserved"] = True
                continue
            if response.status == 200:
                cleanup["succeeded"] = True
                break
        except Exception as error:  # cleanup records and keeps trying inside its global bound
            attempt["error"] = str(error)
    try:
        final_portfolio = _read_portfolio(config, evidence, runner, config.access_token)
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
    try:
        _validate_config(config)
        golden = _preflight(config, evidence, command_runner)
        if config.mode in {"preflight", "rehearsal"}:
            evidence["verdict"] = {
                "go": None,
                "status": config.mode + "_passed",
                "errors": [],
            }
            return ProofResult(0, evidence)
        if not config.access_token or not config.demo_password:
            raise ProofError("execute mode requires injected setup token and demo password")

        before = _read_portfolio(config, evidence, http_runner, config.access_token)
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
                mutating=True,
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
                    post = _read_portfolio(config, evidence, http_runner, token)
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
        evidence["classification"] = _classification(events)
        success = events.get("success") or {}
        success_version = success.get("version")
        candidate_go = bool(
            events["outcome"] == "a_success_only"
            and login_response is not None
            and login_response.status == 200
            and post is not None
            and evidence["observation"].get("golden") is True
            and isinstance(success_version, int)
            and success_version == post["version"]
        )
        if events["outcome"] == "a_success_only" and not candidate_go:
            evidence["classification"] = "class_2e_or_failed_observation"
    except Exception as error:
        evidence["verdict"]["errors"].append(str(error))
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
    final_go = candidate_go and cleanup_ok and restore_ok and no_errors and config.threshold_override is None
    evidence["verdict"]["go"] = final_go
    evidence["verdict"]["status"] = "go" if final_go else (
        "diagnostic_only" if candidate_go and config.threshold_override is not None else "non_go"
    )
    evidence["verdict"]["exitCode"] = 0 if final_go else 1
    return ProofResult(0 if final_go else 1, evidence)


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
        )
        result = run_proof(
            config, command_runner=command_runner, http_runner=http_runner,
            now=now, monotonic=monotonic, sleep=sleep, trace_factory=trace_factory,
        )
        document = result.evidence
        exit_code = result.exit_code
    except Exception as error:
        document = {
            "schemaVersion": 1,
            "verdict": {"go": False, "status": "invalid_invocation", "exitCode": 2,
                        "errors": [str(error)]},
        }
        exit_code = 2
    rendered = json.dumps(document, sort_keys=True, indent=2)
    if args.evidence_output is not None:
        args.evidence_output.write_text(rendered + "\n", encoding="utf-8")
    output(rendered)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
