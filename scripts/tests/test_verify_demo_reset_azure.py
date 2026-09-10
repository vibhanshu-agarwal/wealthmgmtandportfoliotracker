#!/usr/bin/env python3
"""Offline contract tests for the Azure Task 8.9 live-proof executable."""

from __future__ import annotations

import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))

import verify_demo_reset_azure as verifier


DIGEST_A = "sha256:" + "a" * 64
DIGEST_B = "sha256:" + "b" * 64
COMMIT = "1" * 40
COMMIT_B = "2" * 40
USER_ID = "00000000-0000-0000-0000-0000000d3110"
GOLDEN = [
    {"assetTicker": "AAPL", "quantity": "37.00000000"},
    {"assetTicker": "BTC-USD", "quantity": "15.00000000"},
]


class ManifestAndKqlContractTest(unittest.TestCase):
    def test_windows_command_resolution_uses_the_executable_cmd_shim(self) -> None:
        resolved = verifier._resolve_command_executable(
            ["az", "account", "show"],
            platform_name="nt",
            which=lambda name: r"C:\Program Files\Azure CLI\az.CMD" if name == "az" else None,
        )

        self.assertEqual(resolved[0], r"C:\Program Files\Azure CLI\az.CMD")
        self.assertEqual(resolved[1:], ["account", "show"])

    def provenance(self) -> dict:
        return {
            "schemaVersion": 1,
            "services": {
                "api-gateway": {
                    "repository": "wealthprodacr.azurecr.io/api-gateway",
                    "digest": DIGEST_A,
                    "revision": "api-gateway--0000101",
                    "sourceSha": COMMIT,
                    "workflowRunId": 101,
                    "runAttempt": 3,
                    "evidencePath": "docs/evidence/gateway.json",
                },
                "portfolio-service": {
                    "repository": "wealthprodacr.azurecr.io/portfolio-service",
                    "digest": DIGEST_B,
                    "revision": "portfolio-service--0000202",
                    "sourceSha": COMMIT_B,
                    "workflowRunId": 202,
                    "runAttempt": 1,
                    "evidencePath": "docs/evidence/portfolio.json",
                },
            },
        }

    def test_provenance_preserves_independent_workflow_identity(self) -> None:
        parsed = verifier.validate_deployment_provenance(self.provenance())

        self.assertEqual(parsed["services"]["api-gateway"]["runAttempt"], 3)
        self.assertEqual(parsed["services"]["api-gateway"]["sourceSha"], COMMIT)
        self.assertEqual(parsed["services"]["portfolio-service"]["runAttempt"], 1)
        self.assertEqual(parsed["services"]["portfolio-service"]["sourceSha"], COMMIT_B)
        self.assertNotIn("runAttempt", parsed)
        self.assertNotIn("repositorySha", parsed)

    def test_provenance_rejects_missing_service_and_invalid_attempt(self) -> None:
        missing = self.provenance()
        del missing["services"]["portfolio-service"]
        with self.assertRaisesRegex(verifier.ProofError, "exactly"):
            verifier.validate_deployment_provenance(missing)

        stale = self.provenance()
        stale["services"]["api-gateway"]["runAttempt"] = 0
        with self.assertRaisesRegex(verifier.ProofError, "positive integer"):
            verifier.validate_deployment_provenance(stale)

    def test_checked_in_provenance_preserves_the_two_deployment_records(self) -> None:
        import json

        document = json.loads((
            REPO / "docs/evidence/b2-task-8-9/deployment-provenance-20260910.json"
        ).read_text(encoding="utf-8"))
        parsed = verifier.validate_deployment_provenance(document)

        gateway = parsed["services"]["api-gateway"]
        portfolio = parsed["services"]["portfolio-service"]
        self.assertEqual(gateway["workflowRunId"], 34433715705)
        self.assertEqual(gateway["revision"], "api-gateway--0000079")
        self.assertEqual(portfolio["workflowRunId"], 34328692256)
        self.assertEqual(portfolio["revision"], "portfolio-service--0000096")
        self.assertNotEqual(gateway["sourceSha"], portfolio["sourceSha"])

    def test_provenance_rejects_uppercase_or_non_digest_identity(self) -> None:
        bad = self.provenance()
        bad["services"]["api-gateway"]["digest"] = "sha256:" + "A" * 64
        with self.assertRaisesRegex(verifier.ProofError, "lowercase sha256"):
            verifier.validate_deployment_provenance(bad)

        bad = self.provenance()
        bad["services"]["api-gateway"]["sourceSha"] = "A" * 40
        with self.assertRaisesRegex(verifier.ProofError, "lowercase repository SHA"):
            verifier.validate_deployment_provenance(bad)

    def test_kql_uses_absolute_utc_bounds_and_escapes_literals(self) -> None:
        start = datetime(2026, 9, 6, 1, 2, 3, tzinfo=timezone.utc)
        end = datetime(2026, 9, 6, 1, 3, 4, tzinfo=timezone.utc)
        query = verifier.build_event_query(
            app_name="api-gateway",
            event_name="demo_reset_self_call_skipped",
            trace_id="ab'cd",
            start=start,
            end=end,
        )
        self.assertIn("ContainerAppConsoleLogs_CL", query)
        self.assertIn("datetime(2026-09-06T01:02:03.000000Z)", query)
        self.assertIn("datetime(2026-09-06T01:03:04.000000Z)", query)
        self.assertIn("contains 'ab''cd'", query)
        self.assertIn("contains 'demo_reset_self_call_skipped'", query)


def deployment_provenance() -> dict:
    return ManifestAndKqlContractTest().provenance()


class StatefulCommandRunner:
    """Read-only Azure/Task-4.4a boundary fake with stateful query/config results."""

    def __init__(self, *, event_mode: str = "success") -> None:
        self.commands: list[list[str]] = []
        self.timeouts: list[float | None] = []
        self.subscription = "sub-approved"
        self.revisions = {
            "api-gateway": [
                {
                    "name": "api-gateway--0000101",
                    "image": "wealthprodacr.azurecr.io/api-gateway@" + DIGEST_A,
                }
            ],
            "portfolio-service": [
                {
                    "name": "portfolio-service--0000202",
                    "image": "wealthprodacr.azurecr.io/portfolio-service@" + DIGEST_B,
                }
            ],
        }
        self.event_mode = event_mode
        self.skip_reason = "eligibility_connection_failure"
        self.threshold = "30m"
        self.decision_values = {
            "APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD": "30m",
            "APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT": "45s",
            "APP_DEMO_LOGIN_RESET_RESET_TIMEOUT": "10s",
            "APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT": "60s",
        }
        self.restore_readback: str | None = None
        self.fail_first_update_after_apply = False
        self.update_count = 0
        self.fail_command_containing: str | None = None
        self.query_count = {"success": 0, "skip": 0}
        self.revision_calls = {service: 0 for service in ("api-gateway", "portfolio-service")}
        self.ingress = {
            "external": True,
            "fqdn": "api-gateway.current.test",
            "customDomains": [{"name": "wealth.example.test", "bindingType": "SniEnabled"}],
        }

    def _result(self, payload="", *, code=0, error=""):
        if not isinstance(payload, str):
            import json

            payload = json.dumps(payload)
        return verifier.CommandResult(code, payload, error)

    @staticmethod
    def _value_after(command: list[str], flag: str) -> str:
        return command[command.index(flag) + 1]

    @staticmethod
    def _trace_from_query(query: str) -> str:
        import re

        matches = re.findall(r"contains '([0-9a-f]{32})'", query)
        if not matches:
            raise AssertionError(f"query did not contain a trace id: {query}")
        return matches[-1]

    def __call__(self, command: list[str], *, timeout_seconds: float | None = None):
        self.commands.append(list(command))
        self.timeouts.append(timeout_seconds)
        if self.fail_command_containing and self.fail_command_containing in " ".join(command):
            return self._result(code=1, error="RBAC operation denied")
        if command[:3] == [sys.executable, "-B", str(REPO / "scripts/derive_demo_golden_state.py")]:
            return self._result(
                {
                    "metadata": {"demoUserId": USER_ID},
                    "wireHoldings": GOLDEN,
                    "persistedHoldings": [],
                }
            )
        if command[:3] == ["az", "account", "show"]:
            return self._result(self.subscription + "\n")
        if command[:4] == ["az", "containerapp", "revision", "list"]:
            service = self._value_after(command, "--name")
            self.revision_calls[service] += 1
            return self._result(self.revisions[service])
        if command[:4] == ["az", "monitor", "log-analytics", "workspace"]:
            return self._result("workspace-customer-id\n")
        if command[:4] == ["az", "containerapp", "replica", "list"]:
            return self._result([{"name": "api-gateway--0000101-replica-a"}])
        if command[:3] == ["az", "containerapp", "exec"]:
            return self._result("nonblank\n")
        if command[:4] == ["az", "acr", "manifest", "show-metadata"]:
            return self._result({"digest": self._value_after(command, "--name").split("@", 1)[1]})
        if command[:3] == ["az", "acr", "login"]:
            return self._result("Login Succeeded\n")
        if command[:2] == ["docker", "pull"]:
            return self._result(command[2] + "\n")
        if command[:4] == ["az", "monitor", "log-analytics", "query"]:
            query = self._value_after(command, "--analytics-query")
            if query == "print task8_9_rbac_probe=1":
                return self._result([{"task8_9_rbac_probe": 1}])
            kind = "success" if "demo_reset_succeeded" in query else "skip"
            self.query_count[kind] += 1
            if self.event_mode == "query_error":
                return self._result(code=1, error="workspace query denied")
            present = self.event_mode == kind or self.event_mode == "both"
            if not present:
                return self._result([])
            trace_id = self._trace_from_query(query)
            payload = {
                "event": "demo_reset_succeeded" if kind == "success" else "demo_reset_self_call_skipped",
                "traceId": trace_id,
                "timestamp": "2026-09-06T01:02:04Z",
            }
            if kind == "success":
                payload["version"] = 3
            else:
                payload.update(
                    {
                        "reason": self.skip_reason,
                        "leg": "eligibility",
                        "attemptedTarget": "http://localhost:8080/api/portfolio",
                        "httpStatus": None,
                        "timeoutScope": None,
                        "elapsedMillis": None,
                        "replicaToken": "95ca17821ade",
                        "eligibilityDispatchAttempted": True,
                        "resetDispatchAttempted": False,
                        "internalApiKeyConfigured": True,
                        "internalApiKeyAttached": None,
                        "originVerifyRequired": False,
                        "originVerifyHeaderAttached": None,
                    }
                )
                if self.skip_reason == "overall_timeout":
                    payload.update(
                        {
                            "leg": "overall",
                            "attemptedTarget": "http://localhost:8080/api/internal/portfolio/demo-reset",
                            "timeoutScope": "overall",
                            "elapsedMillis": 4012,
                            "overallTimeoutPhase": "reset_in_flight",
                            "eligibilityDispatchAttempted": True,
                            "resetDispatchAttempted": True,
                            "internalApiKeyAttached": True,
                        }
                    )
                if self.skip_reason == "reset_key_not_configured":
                    payload.update(
                        {
                            "leg": "reset", "httpStatus": None, "timeoutScope": None,
                            "attemptedTarget": None, "elapsedMillis": None,
                            "eligibilityDispatchAttempted": True,
                            "resetDispatchAttempted": False,
                            "internalApiKeyConfigured": False,
                            "internalApiKeyAttached": None,
                        }
                    )
            import json

            fields = []
            for key, value in payload.items():
                if key in {"traceId", "timestamp"}:
                    continue
                rendered = (
                    "null" if value is None else
                    str(value).lower() if isinstance(value, bool) else str(value)
                )
                fields.append(f"{key}={rendered}")
            line = f"INFO [{trace_id}] " + " ".join(fields)
            service = "portfolio-service" if kind == "success" else "api-gateway"
            return self._result([{"TimeGenerated": payload["timestamp"], "Log_s": line,
                                 "ContainerAppName_s": service,
                                 "RevisionName_s": self.revisions[service][0]["name"]}])
        if command[:4] == ["az", "containerapp", "revision", "show"]:
            values = dict(self.decision_values)
            if self.restore_readback is not None and self.update_count >= 2:
                values["APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD"] = self.restore_readback
            return self._result(
                [{"name": name, "value": value} for name, value in values.items()]
                + [{"name": "CLOUD_PROVIDER", "value": "azure"}]
            )
        if command[:3] == ["az", "containerapp", "show"]:
            if self._value_after(command, "--query") == "properties.configuration.ingress":
                return self._result(self.ingress)
            if self._value_after(command, "--query") == "properties.template.containers[0].env":
                return self._result(
                    [{"name": name, "value": value} for name, value in self.decision_values.items()]
                )
            value = self.restore_readback if self.restore_readback is not None else self.threshold
            return self._result(value + "\n")
        if command[:3] == ["az", "containerapp", "update"]:
            assignment = self._value_after(command, "--set-env-vars")
            self.threshold = assignment.split("=", 1)[1]
            self.decision_values["APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD"] = self.threshold
            self.update_count += 1
            if self.fail_first_update_after_apply and self.update_count == 1:
                return self._result(code=1, error="response lost after update")
            return self._result({"properties": {"latestRevisionName": "api-gateway--updated"}})
        return self._result(code=1, error="unexpected command: " + " ".join(command))


class StatefulHttpRunner:
    """Stateful public API fake; its state is the assertion surface, not its call count."""

    def __init__(self, command_runner: StatefulCommandRunner) -> None:
        self.command_runner = command_runner
        self.version = 1
        self.holdings = [dict(row) for row in GOLDEN]
        self.requests: list[dict] = []
        self.cleanup_statuses: list[int] = []
        self.login_error: Exception | None = None
        self.post_login_user_id = USER_ID
        self.advance_setup = True
        self.login_resets = True
        self.persist_cleanup = True
        self.updated_at = "2026-09-06T01:00:00Z"
        self.timeouts: list[float | None] = []

    def _portfolio(self) -> dict:
        return {
            "userId": self.post_login_user_id,
            "version": self.version,
            "updatedAt": self.updated_at,
            "holdings": [dict(row) for row in self.holdings],
        }

    def __call__(
        self, *, method: str, url: str, headers: dict[str, str], json_body=None,
        timeout_seconds: float | None = None,
    ):
        self.timeouts.append(timeout_seconds)
        self.requests.append(
            {"method": method, "url": url, "headers": dict(headers), "json": json_body}
        )
        if method == "GET" and url.endswith("/api/portfolio"):
            return verifier.HttpResponse(200, [self._portfolio()], {})
        if method == "PUT" and url.endswith("/api/portfolio/holdings"):
            if json_body["expectedVersion"] != self.version:
                return verifier.HttpResponse(409, {"currentVersion": self.version}, {})
            self.holdings = [
                {"assetTicker": row["ticker"], "quantity": row["quantity"]}
                for row in json_body["holdings"]
            ]
            if self.advance_setup:
                self.version += 1
            return verifier.HttpResponse(200, self._portfolio(), {})
        if method == "POST" and url.endswith("/api/auth/login"):
            if self.login_error:
                raise self.login_error
            if self.login_resets:
                self.holdings = [dict(row) for row in GOLDEN]
                self.version += 1
            return verifier.HttpResponse(200, {"token": "probe-token"}, {})
        if method == "PUT" and url.endswith("/api/portfolio/demo-reset"):
            status = self.cleanup_statuses.pop(0) if self.cleanup_statuses else 200
            if status == 409:
                return verifier.HttpResponse(409, {"currentVersion": self.version}, {})
            if status == 200:
                if json_body["expectedVersion"] != self.version:
                    return verifier.HttpResponse(409, {"currentVersion": self.version}, {})
                if self.persist_cleanup:
                    self.holdings = [dict(row) for row in GOLDEN]
                return verifier.HttpResponse(200, self._portfolio(), {})
            return verifier.HttpResponse(status, {"error": "cleanup_failed"}, {})
        return verifier.HttpResponse(404, {"error": "unexpected"}, {})


class Clock:
    def __init__(self) -> None:
        self.current = datetime(2026, 9, 6, 1, 2, 3, tzinfo=timezone.utc)
        self.monotonic_value = 0.0
        self.sleeps: list[float] = []

    def now(self) -> datetime:
        from datetime import timedelta

        value = self.current
        self.current += timedelta(milliseconds=100)
        return value

    def monotonic(self) -> float:
        return self.monotonic_value

    def sleep(self, seconds: float) -> None:
        from datetime import timedelta

        self.sleeps.append(seconds)
        self.monotonic_value += seconds
        self.current += timedelta(seconds=seconds)


def config(*, mode: str = "execute", override: str | None = None) -> "verifier.ProofConfig":
    return verifier.ProofConfig(
        mode=mode,
        target="production-azure",
        subscription_id="sub-approved",
        resource_group="wealth-azure-prod-rg",
        gateway_app="api-gateway",
        portfolio_app="portfolio-service",
        workspace_name="wealth-prod-la",
        registry_name="wealthprodacr",
        gateway_url="https://wealth.example.test",
        deployment_provenance=deployment_provenance(),
        access_token="setup-token",
        demo_email="demo@wealthtracker.dev",
        demo_password="not-recorded",
        expected_user_id=USER_ID,
        idle_threshold="30m",
        threshold_override=override,
        poll_interval_seconds=5,
        poll_deadline_seconds=10,
        cleanup_max_attempts=3,
        cleanup_deadline_seconds=20,
    )


def run_case(*, event_mode="success", cfg=None):
    commands = StatefulCommandRunner(event_mode=event_mode)
    http = StatefulHttpRunner(commands)
    clock = Clock()
    result = verifier.run_proof(
        cfg or config(),
        command_runner=commands,
        http_runner=http,
        now=clock.now,
        monotonic=clock.monotonic,
        sleep=clock.sleep,
        trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
    )
    return result, commands, http, clock


class ProofStateMachineTest(unittest.TestCase):
    def test_plain_console_event_parser_uses_raw_trace_and_key_value_payload(self) -> None:
        trace = "0123456789abcdef0123456789abcdef"
        rows = [{
            "TimeGenerated": "2026-09-06T01:02:04Z",
            "Log_s": (
                f"INFO [{trace}] event=demo_reset_self_call_skipped "
                "reason=overall_timeout overallTimeoutPhase=reset_in_flight "
                "elapsedMillis=4012 eligibilityDispatchAttempted=true "
                "resetDispatchAttempted=true"
            ),
        }]
        event = verifier.parse_event_rows(
            rows, event="demo_reset_self_call_skipped", trace_id=trace
        )
        self.assertEqual(event["traceId"], trace)
        self.assertEqual(event["reason"], "overall_timeout")
        self.assertEqual(event["overallTimeoutPhase"], "reset_in_flight")
        self.assertEqual(event["elapsedMillis"], 4012)
        self.assertIs(event["resetDispatchAttempted"], True)

    def test_wrong_explicit_target_fails_before_any_runner_or_cleanup(self) -> None:
        cfg = config(mode="preflight")
        cfg.target = "staging"
        commands = StatefulCommandRunner()
        http = StatefulHttpRunner(commands)
        result = verifier.run_proof(cfg, command_runner=commands, http_runner=http)
        self.assertNotEqual(result.exit_code, 0)
        self.assertEqual(commands.commands, [])
        self.assertEqual(http.requests, [])
        self.assertFalse(result.evidence["cleanup"]["armed"])
        self.assertEqual(result.evidence["classificationDetail"]["class"], "class_2a")

    def test_preflight_fails_closed_on_zero_or_multiple_serving_revisions(self) -> None:
        for revisions in ([], [
            {"name": "r1", "image": "wealthprodacr.azurecr.io/api-gateway@" + DIGEST_A},
            {"name": "r2", "image": "wealthprodacr.azurecr.io/api-gateway@" + DIGEST_A},
        ]):
            with self.subTest(count=len(revisions)):
                commands = StatefulCommandRunner()
                commands.revisions["api-gateway"] = revisions
                http = StatefulHttpRunner(commands)
                result = verifier.run_proof(
                    config(mode="preflight"), command_runner=commands, http_runner=http
                )
                self.assertNotEqual(result.exit_code, 0)
                self.assertEqual(http.requests, [])
                self.assertFalse(result.evidence["cleanup"]["armed"])

    def test_preflight_rejects_subscription_disagreement_without_writes(self) -> None:
        commands = StatefulCommandRunner()
        commands.subscription = "some-other-subscription"
        http = StatefulHttpRunner(commands)
        result = verifier.run_proof(
            config(mode="preflight"), command_runner=commands, http_runner=http
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertEqual(http.requests, [])

    def test_preflight_rejects_live_revision_that_differs_from_attestation(self) -> None:
        cfg = config(mode="preflight")
        cfg.deployment_provenance = deployment_provenance()
        commands = StatefulCommandRunner()
        commands.revisions["api-gateway"][0]["name"] = "api-gateway--stale"
        http = StatefulHttpRunner(commands)

        result = verifier.run_proof(cfg, command_runner=commands, http_runner=http)

        self.assertNotEqual(result.exit_code, 0)
        self.assertEqual(http.requests, [])
        self.assertIn("revision", " ".join(result.evidence["verdict"]["errors"]))

    def test_preflight_rejects_repo_digest_disagreement_without_writes(self) -> None:
        commands = StatefulCommandRunner()
        commands.revisions["portfolio-service"][0]["image"] = (
            "wealthprodacr.azurecr.io/portfolio-service@sha256:" + "c" * 64
        )
        http = StatefulHttpRunner(commands)
        result = verifier.run_proof(
            config(mode="preflight"), command_runner=commands, http_runner=http
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertEqual(http.requests, [])

    def test_rehearsal_executes_read_only_rbac_plan_and_never_arms_cleanup(self) -> None:
        result, commands, http, _ = run_case(cfg=config(mode="rehearsal"))
        self.assertEqual(result.exit_code, 0)
        self.assertEqual(http.requests, [])
        self.assertFalse(result.evidence["cleanup"]["armed"])
        flattened = [" ".join(command) for command in commands.commands]
        self.assertTrue(any("containerapp exec" in command for command in flattened))
        self.assertTrue(any("acr manifest show-metadata" in command for command in flattened))
        self.assertIn(
            "az acr manifest show-metadata --registry wealthprodacr --name api-gateway@"
            + DIGEST_A + " -o json",
            flattened,
        )
        self.assertTrue(any(command == "az acr login --name wealthprodacr" for command in flattened))
        self.assertIn(
            "docker pull wealthprodacr.azurecr.io/api-gateway@" + DIGEST_A,
            flattened,
        )
        self.assertIn(
            "docker pull wealthprodacr.azurecr.io/portfolio-service@" + DIGEST_B,
            flattened,
        )
        self.assertTrue(any("log-analytics query" in command for command in flattened))
        self.assertFalse(any("containerapp update" in command for command in flattened))

    def test_preflight_rejects_any_serving_timeout_decision_drift(self) -> None:
        commands = StatefulCommandRunner()
        commands.decision_values["APP_DEMO_LOGIN_RESET_RESET_TIMEOUT"] = "3s"
        http = StatefulHttpRunner(commands)
        result = verifier.run_proof(
            config(mode="preflight"), command_runner=commands, http_runner=http
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertEqual(http.requests, [])
        self.assertFalse(result.evidence["cleanup"]["armed"])

    def test_rbac_rehearsal_failure_is_read_only_and_never_arms_cleanup(self) -> None:
        commands = StatefulCommandRunner()
        commands.fail_command_containing = "containerapp exec"
        http = StatefulHttpRunner(commands)
        result = verifier.run_proof(
            config(mode="rehearsal"), command_runner=commands, http_runner=http
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertEqual(http.requests, [])
        self.assertFalse(result.evidence["cleanup"]["armed"])
        self.assertFalse(any(operation["mutating"] for operation in result.evidence["operations"]))

    def test_success_only_is_go_and_records_real_operations_without_secrets(self) -> None:
        result, commands, http, clock = run_case(event_mode="success")
        self.assertEqual(result.exit_code, 0)
        self.assertEqual(result.evidence["events"]["outcome"], "a_success_only")
        self.assertTrue(result.evidence["verdict"]["go"])
        self.assertEqual(result.evidence["setup"]["beforeVersion"], 1)
        self.assertEqual(result.evidence["setup"]["writeVersion"], 2)
        self.assertEqual(result.evidence["observation"]["postLoginVersion"], 3)
        self.assertTrue(result.evidence["observation"]["golden"])
        self.assertGreaterEqual(clock.sleeps[0], 1800)
        setup = next(r for r in http.requests if r["url"].endswith("/api/portfolio/holdings"))
        self.assertEqual(setup["json"]["expectedVersion"], 1)
        self.assertNotEqual(setup["json"]["holdings"], [
            {"ticker": "AAPL", "quantity": "37.00000000"},
            {"ticker": "BTC-USD", "quantity": "15.00000000"},
        ])
        login = next(r for r in http.requests if r["url"].endswith("/api/auth/login"))
        self.assertEqual(
            login["headers"]["traceparent"],
            "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        cleanup = [r for r in http.requests if r["url"].endswith("/api/portfolio/demo-reset")]
        self.assertEqual(cleanup[-1]["json"], {"expectedVersion": 3})
        rendered = __import__("json").dumps(result.evidence, sort_keys=True)
        self.assertNotIn("not-recorded", rendered)
        self.assertNotIn("setup-token", rendered)
        self.assertEqual(commands.query_count, {"success": 2, "skip": 2})
        self.assertEqual(
            result.evidence["requestCounts"],
            {"portfolioReads": 4, "compositionWrites": 1, "logins": 1, "cleanupResets": 1},
        )

    def test_fixed_query_window_covers_the_entire_bounded_poll_deadline(self) -> None:
        result, _commands, _http, _clock = run_case(event_mode="success")

        start = datetime.fromisoformat(
            result.evidence["trace"]["windowStart"].replace("Z", "+00:00")
        )
        end = datetime.fromisoformat(
            result.evidence["trace"]["windowEnd"].replace("Z", "+00:00")
        )
        self.assertGreaterEqual((end - start).total_seconds(), 10)

    def test_all_five_event_query_outcomes_are_discriminated_and_non_go_is_nonzero(self) -> None:
        expected = {
            "success": ("a_success_only", 0),
            "skip": ("b_skip_only", 1),
            "none": ("c_neither", 1),
            "query_error": ("d_query_error", 1),
            "both": ("e_both", 1),
        }
        for mode, (outcome, expected_code) in expected.items():
            with self.subTest(mode=mode):
                result, commands, _, _ = run_case(event_mode=mode)
                self.assertEqual(result.evidence["events"]["outcome"], outcome)
                self.assertEqual(result.exit_code, expected_code)
                self.assertGreaterEqual(commands.query_count["success"], 1)
                self.assertGreaterEqual(commands.query_count["skip"], 1)
                if mode in {"skip", "both"}:
                    self.assertEqual(
                        result.evidence["events"]["skip"]["reason"],
                        "eligibility_connection_failure",
                    )
                if mode == "both":
                    self.assertEqual(result.evidence["classification"], "class_2d")
                    self.assertEqual(
                        result.evidence["classificationDetail"]["action"],
                        "repair_downstream_or_network",
                    )
                if mode == "query_error":
                    self.assertIn("workspace query denied", result.evidence["events"]["queryError"])

    def test_armed_setup_failure_still_performs_version_bearing_cleanup_and_post_get(self) -> None:
        commands = StatefulCommandRunner()
        http = StatefulHttpRunner(commands)
        http.advance_setup = False
        clock = Clock()
        result = verifier.run_proof(
            config(), command_runner=commands, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertTrue(result.evidence["cleanup"]["armed"])
        reset = [r for r in http.requests if r["url"].endswith("/api/portfolio/demo-reset")]
        self.assertEqual(reset[0]["json"], {"expectedVersion": 1})
        self.assertTrue(result.evidence["cleanup"]["postCleanupGolden"])
        self.assertEqual(result.evidence["classificationDetail"]["class"], "class_2a")

    def test_setup_requires_persisted_absolute_utc_updated_at_before_aging(self) -> None:
        commands = StatefulCommandRunner()
        http = StatefulHttpRunner(commands)
        http.updated_at = "not-a-timestamp"
        clock = Clock()
        result = verifier.run_proof(
            config(), command_runner=commands, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertIn("updatedAt", " ".join(result.evidence["verdict"]["errors"]))
        self.assertTrue(result.evidence["cleanup"]["armed"])

    def test_cleanup_retry_loop_stops_at_one_global_deadline(self) -> None:
        commands = StatefulCommandRunner()
        http = StatefulHttpRunner(commands)
        http.advance_setup = False
        clock = Clock()
        calls = 0

        def jumping_monotonic() -> float:
            nonlocal calls
            calls += 1
            return 0.0 if calls == 1 else 25.0

        result = verifier.run_proof(
            config(), command_runner=commands, http_runner=http,
            now=clock.now, monotonic=jumping_monotonic, sleep=clock.sleep,
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertTrue(result.evidence["cleanup"]["deadlineExceeded"])
        resets = [r for r in http.requests if r["url"].endswith("/api/portfolio/demo-reset")]
        self.assertEqual(resets, [])

    def test_uncertain_login_timeout_still_cleans_up(self) -> None:
        commands = StatefulCommandRunner()
        http = StatefulHttpRunner(commands)
        http.login_error = TimeoutError("response uncertain")
        clock = Clock()
        result = verifier.run_proof(
            config(), command_runner=commands, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertTrue(any(r["url"].endswith("/api/portfolio/demo-reset") for r in http.requests))
        self.assertTrue(result.evidence["cleanup"]["postCleanupGolden"])

    def test_every_cleanup_retry_uses_fresh_identity_version_and_any_409_is_permanent_failure(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        http = StatefulHttpRunner(commands)
        http.cleanup_statuses = [409, 200]
        clock = Clock()
        result = verifier.run_proof(
            config(), command_runner=commands, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertTrue(result.evidence["cleanup"]["conflictObserved"])
        resets = [i for i, r in enumerate(http.requests) if r["url"].endswith("/api/portfolio/demo-reset")]
        self.assertEqual(len(resets), 2)
        for index in resets:
            self.assertEqual(http.requests[index - 1]["method"], "GET")
            self.assertTrue(http.requests[index - 1]["url"].endswith("/api/portfolio"))
        self.assertTrue(result.evidence["cleanup"]["postCleanupGolden"])

    def test_post_login_identity_mismatch_is_non_go_but_cleanup_uses_identity_checked_reads(self) -> None:
        commands = StatefulCommandRunner()
        http = StatefulHttpRunner(commands)
        original_call = http.__call__
        login_seen = False

        def boundary(**kwargs):
            nonlocal login_seen
            response = original_call(**kwargs)
            if kwargs["url"].endswith("/api/auth/login"):
                login_seen = True
                http.post_login_user_id = "00000000-0000-0000-0000-000000000099"
            if login_seen and kwargs["url"].endswith("/api/portfolio/demo-reset"):
                self.fail("cleanup reset must not use a portfolio whose identity does not match")
            return response

        clock = Clock()
        result = verifier.run_proof(
            config(), command_runner=commands, http_runner=boundary,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertFalse(result.evidence["cleanup"]["postCleanupGolden"])

    def test_threshold_override_is_diagnostic_only_and_restored_with_verified_readback(self) -> None:
        result, commands, _, clock = run_case(
            event_mode="success", cfg=config(override="1s")
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertTrue(result.evidence["decisions"]["overrideUsed"])
        self.assertFalse(result.evidence["decisions"]["wave10Eligible"])
        self.assertTrue(result.evidence["thresholdRestore"]["verified"])
        self.assertEqual(commands.threshold, "30m")
        updates = [c for c in commands.commands if c[:3] == ["az", "containerapp", "update"]]
        self.assertEqual(len(updates), 2)
        self.assertLess(clock.sleeps[0], 1800)

    def test_failed_threshold_restore_forces_nonzero_even_after_success_event(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        commands.restore_readback = "still-1s"
        http = StatefulHttpRunner(commands)
        clock = Clock()
        result = verifier.run_proof(
            config(override="1s"), command_runner=commands, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertFalse(result.evidence["thresholdRestore"]["verified"])

    def test_uncertain_override_update_is_still_restored_and_cleaned_up(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        commands.fail_first_update_after_apply = True
        http = StatefulHttpRunner(commands)
        clock = Clock()
        result = verifier.run_proof(
            config(override="1s"), command_runner=commands, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertTrue(result.evidence["cleanup"]["armed"])
        self.assertTrue(result.evidence["thresholdRestore"]["attempted"])
        self.assertTrue(result.evidence["thresholdRestore"]["verified"])
        self.assertEqual(commands.threshold, "30m")

    def test_cleanup_200_without_exact_persistence_is_non_go(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        http = StatefulHttpRunner(commands)
        http.persist_cleanup = False
        http.login_resets = False
        clock = Clock()
        result = verifier.run_proof(
            config(), command_runner=commands, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertTrue(result.evidence["cleanup"]["succeeded"])
        self.assertFalse(result.evidence["cleanup"]["postCleanupGolden"])

    def test_dual_event_classification_preserves_reason_phase_and_version(self) -> None:
        commands = StatefulCommandRunner(event_mode="both")
        commands.skip_reason = "overall_timeout"
        http = StatefulHttpRunner(commands)
        clock = Clock()

        result = verifier.run_proof(
            config(), command_runner=commands, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertEqual(result.evidence["events"]["outcome"], "e_both")
        self.assertEqual(result.evidence["classification"], "class_2f")
        self.assertEqual(result.evidence["events"]["success"]["version"], 3)
        self.assertEqual(
            result.evidence["events"]["skip"]["overallTimeoutPhase"], "reset_in_flight"
        )

    def test_generated_traceparents_are_valid_nonzero_and_unique(self) -> None:
        first = verifier.new_traceparent()
        second = verifier.new_traceparent()
        self.assertRegex(first, verifier.TRACEPARENT_RE)
        self.assertRegex(second, verifier.TRACEPARENT_RE)
        self.assertNotEqual(first, second)
        self.assertNotIn("-" + "0" * 32 + "-", first)

    def test_cli_reads_required_manifest_and_secrets_from_env_but_emits_no_secret(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        http = StatefulHttpRunner(commands)
        clock = Clock()
        emitted: list[str] = []
        with tempfile.TemporaryDirectory() as directory:
            provenance_path = Path(directory) / "deployment-provenance.json"
            evidence_path = Path(directory) / "evidence.json"
            provenance_path.write_text(
                __import__("json").dumps(deployment_provenance()), encoding="utf-8"
            )
            exit_code = verifier.main(
                [
                    "--mode", "execute", "--target", "production-azure",
                    "--subscription", "sub-approved",
                    "--resource-group", "wealth-azure-prod-rg",
                    "--gateway-app", "api-gateway", "--portfolio-app", "portfolio-service",
                    "--workspace", "wealth-prod-la", "--registry", "wealthprodacr",
                    "--gateway-url", "https://wealth.example.test",
                    "--deployment-provenance", str(provenance_path),
                    "--evidence-output", str(evidence_path),
                    "--poll-interval-seconds", "5", "--poll-deadline-seconds", "10",
                ],
                command_runner=commands, http_runner=http,
                environ={"TASK8_9_ACCESS_TOKEN": "setup-token", "TASK8_9_DEMO_PASSWORD": "not-recorded"},
                output=emitted.append, now=clock.now, monotonic=clock.monotonic,
                sleep=clock.sleep,
                trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
            )
            self.assertEqual(exit_code, 0)
            saved = evidence_path.read_text(encoding="utf-8")
            self.assertNotIn("setup-token", saved)
            self.assertNotIn("not-recorded", saved)
            self.assertEqual(__import__("json").loads(saved)["events"]["outcome"], "a_success_only")
            self.assertEqual(len(emitted), 1)

    def test_trace_factory_must_return_a_valid_unique_w3c_traceparent(self) -> None:
        result, _, _, _ = run_case(event_mode="success")
        self.assertEqual(result.evidence["trace"]["traceId"], "0123456789abcdef0123456789abcdef")
        bad, commands, http, _ = run_case(event_mode="success")
        clock = Clock()
        bad = verifier.run_proof(
            config(), command_runner=StatefulCommandRunner(), http_runner=StatefulHttpRunner(commands),
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-00000000000000000000000000000000-0000000000000000-01",
        )
        self.assertNotEqual(bad.exit_code, 0)


def skip_event(**overrides) -> dict:
    event = {
        "event": "demo_reset_self_call_skipped",
        "reason": "overall_timeout",
        "leg": "overall",
        "httpStatus": None,
        "timeoutScope": "overall",
        "attemptedTarget": "http://localhost:8080/api/portfolio",
        "elapsedMillis": 4010,
        "replicaToken": "95ca17821ade",
        "eligibilityDispatchAttempted": True,
        "resetDispatchAttempted": False,
        "internalApiKeyConfigured": True,
        "internalApiKeyAttached": None,
        "originVerifyRequired": False,
        "originVerifyHeaderAttached": None,
        "overallTimeoutPhase": "eligibility_in_flight",
    }
    event.update(overrides)
    return event


class ReviewFixContractTest(unittest.TestCase):
    def _key_diagnostic_boundaries(self, *, manual_status: int, presence_fails: bool):
        cfg = config()
        cfg.last_known_good_gateway_revision = "api-gateway--0000100"
        evidence = verifier._initial_evidence(cfg)
        evidence["serving"]["api-gateway"] = {
            "revision": "api-gateway--0000101",
            "image": "wealthprodacr.azurecr.io/api-gateway@" + DIGEST_A,
        }
        commands: list[list[str]] = []

        def command_runner(command, *, timeout_seconds=None):
            commands.append(list(command))
            if command[:4] == ["az", "containerapp", "revision", "show"]:
                return verifier.CommandResult(0, __import__("json").dumps([
                    {"name": "INTERNAL_API_KEY", "secretRef": "internal-api-key"}
                ]), "")
            if command[:4] == ["az", "containerapp", "replica", "list"]:
                return verifier.CommandResult(0, __import__("json").dumps([
                    {"name": "api-gateway--0000101-replica-a"}
                ]), "")
            if command[:2] == ["docker", "run"]:
                return verifier.CommandResult(0, "95ca17821ade\n", "")
            if command[:3] == ["az", "containerapp", "exec"]:
                return verifier.CommandResult(
                    1 if presence_fails else 0,
                    "" if presence_fails else "blank\n",
                    "presence unavailable" if presence_fails else "",
                )
            return verifier.CommandResult(1, "", "unexpected command")

        def http_runner(*, method, url, headers, json_body=None, timeout_seconds=None):
            if method == "GET" and url.endswith("/api/portfolio"):
                return verifier.HttpResponse(200, [{
                    "userId": USER_ID, "version": 7,
                    "updatedAt": "2026-09-06T01:00:00Z", "holdings": GOLDEN,
                }], {})
            if method == "PUT" and url.endswith("/api/portfolio/demo-reset"):
                body = ({"error": "internal_api_key_not_configured", "message": "unavailable"}
                        if manual_status == 503 else {"version": 8})
                return verifier.HttpResponse(
                    manual_status, body,
                    {"X-Gateway-Replica-Token": "95ca17821ade"},
                )
            return verifier.HttpResponse(404, {}, {})

        event = skip_event(
            reason="reset_key_not_configured", leg="reset", httpStatus=None,
            timeoutScope=None, overallTimeoutPhase=None, attemptedTarget=None,
            elapsedMillis=None, eligibilityDispatchAttempted=True,
            resetDispatchAttempted=False, internalApiKeyConfigured=False,
            internalApiKeyAttached=None,
        )
        return cfg, evidence, command_runner, http_runner, commands, event

    def test_public_execution_path_has_no_symbolic_diagnostic_callback(self) -> None:
        import inspect

        self.assertNotIn("diagnostic_runner", inspect.signature(verifier.run_proof).parameters)
        self.assertNotIn("diagnostic_runner", inspect.signature(verifier.main).parameters)

    def test_legacy_joint_manifest_cannot_replace_per_service_provenance(self) -> None:
        legacy = {"api-gateway": DIGEST_A, "portfolio-service": DIGEST_B}
        with self.assertRaisesRegex(verifier.ProofError, "exactly schemaVersion and services"):
            verifier.validate_deployment_provenance(legacy)

    def test_scoped_gateway_artifact_does_not_invent_portfolio_workflow_identity(self) -> None:
        import importlib.util

        script = REPO / ".github/workflows/scripts/snapshot_container_apps.py"
        spec = importlib.util.spec_from_file_location("task7_snapshot_container_apps", script)
        aggregator = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = aggregator
        spec.loader.exec_module(aggregator)
        with tempfile.TemporaryDirectory() as directory:
            service_dir = Path(directory) / "api-gateway"
            service_dir.mkdir()
            (service_dir / "digest.txt").write_text(DIGEST_A, encoding="utf-8")
            artifact = aggregator.aggregate_digests(directory, ["api-gateway"], None)
        provenance = deployment_provenance()
        provenance["services"]["api-gateway"]["digest"] = artifact["api-gateway"]
        parsed = verifier.validate_deployment_provenance(provenance)
        self.assertEqual(parsed["services"]["api-gateway"]["workflowRunId"], 101)
        self.assertEqual(parsed["services"]["portfolio-service"]["workflowRunId"], 202)
        self.assertEqual(parsed["services"]["portfolio-service"]["sourceSha"], COMMIT_B)

    def test_all_overall_timeout_phases_require_the_exact_dispatch_pair(self) -> None:
        matrix = {
            "eligibility_pre_dispatch": (False, False, None),
            "eligibility_in_flight": (True, False, "http://localhost:8080/api/portfolio"),
            "between_legs": (True, False, None),
            "reset_in_flight": (
                True, True, "http://localhost:8080/api/internal/portfolio/demo-reset",
            ),
            "reset_post_response": (True, True, None),
        }
        for phase, (eligibility, reset, target) in matrix.items():
            with self.subTest(phase=phase):
                event = skip_event(
                    overallTimeoutPhase=phase,
                    eligibilityDispatchAttempted=eligibility,
                    resetDispatchAttempted=reset,
                    internalApiKeyAttached=True if reset else None,
                    attemptedTarget=target,
                    httpStatus=200 if phase == "reset_post_response" else None,
                )
                verifier.validate_skip_event(event, success_present=False)
                event["resetDispatchAttempted"] = not reset
                event["internalApiKeyAttached"] = (
                    True if event["resetDispatchAttempted"] else None
                )
                with self.assertRaisesRegex(verifier.ProofError, "dispatch pair"):
                    verifier.validate_skip_event(event, success_present=False)

    def test_skip_reason_leg_scope_and_required_diagnostics_are_validated(self) -> None:
        invalid = [
            skip_event(reason="reset_timeout", leg="overall", timeoutScope="per-leg",
                       overallTimeoutPhase=None),
            skip_event(reason="eligibility_connection_failure", leg="eligibility",
                       timeoutScope=None, attemptedTarget=None, elapsedMillis=None,
                       overallTimeoutPhase=None),
            skip_event(reason="reset_non_2xx_status", leg="reset", httpStatus=409,
                       timeoutScope=None, attemptedTarget=None, elapsedMillis=None,
                       overallTimeoutPhase=None, eligibilityDispatchAttempted=True,
                       resetDispatchAttempted=True, internalApiKeyAttached=True),
        ]
        for event in invalid:
            with self.subTest(reason=event["reason"]):
                with self.assertRaises(verifier.ProofError):
                    verifier.validate_skip_event(event, success_present=False)

    def test_setup_and_success_non_golden_version_splits_have_terminal_actions(self) -> None:
        setup = verifier.classify_task8_9(
            events=None, observation=None, decisions={}, setup_failed=True
        )
        self.assertEqual((setup["class"], setup["action"]),
                         ("class_2a", "retry_fresh_end_to_end"))

        for read_version, expected in ((4, "class_2c"), (3, "class_2e"), (2, "class_2e")):
            with self.subTest(read_version=read_version):
                result = verifier.classify_task8_9(
                    events={
                        "outcome": "a_success_only",
                        "success": {"version": 3},
                        "skip": None,
                        "queryError": None,
                    },
                    observation={"golden": False, "postLoginVersion": read_version},
                    decisions={},
                )
                self.assertEqual(result["class"], expected)
                self.assertFalse(result["rollbackAuthorized"])

    def test_diagnosis_tree_handles_targets_timeouts_409_and_attach_pairs(self) -> None:
        cases = [
            (
                skip_event(reason="eligibility_connection_failure", leg="eligibility",
                           timeoutScope=None, overallTimeoutPhase=None, elapsedMillis=None,
                           attemptedTarget="http://wrong:8080/api/portfolio"),
                {}, "class_1_diagnosed",
            ),
            (
                skip_event(reason="eligibility_timeout", leg="eligibility",
                           timeoutScope="per-leg", overallTimeoutPhase=None,
                           elapsedMillis=4000, attemptedTarget="http://localhost:8080/api/portfolio"),
                {}, "class_1_diagnosed",
            ),
            (
                skip_event(reason="reset_non_2xx_status", leg="reset", httpStatus=409,
                           timeoutScope=None, overallTimeoutPhase=None, attemptedTarget=None,
                           elapsedMillis=None, eligibilityDispatchAttempted=True,
                           resetDispatchAttempted=True, internalApiKeyAttached=True,
                           observedVersion=8, submittedExpectedVersion=7,
                           downstreamCurrentVersion=9, selfCallCount=1),
                {}, "class_1_diagnosed",
            ),
            (
                skip_event(reason="reset_non_2xx_status", leg="reset", httpStatus=403,
                           timeoutScope=None, overallTimeoutPhase=None, attemptedTarget=None,
                           elapsedMillis=None, eligibilityDispatchAttempted=True,
                           resetDispatchAttempted=True, internalApiKeyConfigured=True,
                           internalApiKeyAttached=False),
                {}, "class_1_diagnosed",
            ),
            (
                skip_event(reason="eligibility_non_2xx_status", leg="eligibility", httpStatus=403,
                           timeoutScope=None, overallTimeoutPhase=None, attemptedTarget=None,
                           elapsedMillis=None, originVerifyRequired=True,
                           originVerifyHeaderAttached=False),
                {}, "class_1_diagnosed",
            ),
        ]
        for event, diagnostics, expected in cases:
            with self.subTest(reason=event["reason"], status=event["httpStatus"]):
                result = verifier.classify_task8_9(
                    events={"outcome": "b_skip_only", "success": None, "skip": event,
                            "queryError": None},
                    observation={"golden": False},
                    decisions={"eligibilityTimeout": "2s", "resetTimeout": "2s",
                               "overallTimeout": "4s"},
                    diagnostics=diagnostics,
                )
                self.assertEqual(result["class"], expected)
                self.assertTrue(result["rollbackAuthorized"])

    def test_key_template_probe_replica_and_reproduction_diagnostics_are_terminal(self) -> None:
        key_event = skip_event(
            reason="reset_key_not_configured", leg="reset", httpStatus=None,
            timeoutScope=None, overallTimeoutPhase=None, attemptedTarget=None,
            elapsedMillis=None, eligibilityDispatchAttempted=True,
            resetDispatchAttempted=False, internalApiKeyConfigured=False,
            internalApiKeyAttached=None,
        )
        diagnostic_cases = [
            ({"templateReference": "regressed"}, "class_1_diagnosed", True),
            ({"templateReference": "intact", "replicaTokenRecovered": True,
              "manualResetStatus": 503, "manualResetEmitter": "gateway",
              "sameReplica": True, "presence": "blank"}, "class_2h", False),
            ({"templateReference": "intact", "replicaTokenRecovered": True,
              "manualResetStatus": 503, "manualResetEmitter": "gateway",
              "sameReplica": True, "presence": "nonblank"}, "class_1_diagnosed", True),
            ({"templateReference": "intact", "replicaTokenRecovered": False},
             "class_2d_unresolved", False),
        ]
        for diagnostics, expected, rollback in diagnostic_cases:
            with self.subTest(diagnostics=diagnostics):
                result = verifier.classify_task8_9(
                    events={"outcome": "b_skip_only", "success": None, "skip": key_event,
                            "queryError": None},
                    observation={"golden": False}, decisions={}, diagnostics=diagnostics,
                )
                self.assertEqual(result["class"], expected)
                self.assertEqual(result["rollbackAuthorized"], rollback)

        local_stall = skip_event(
            overallTimeoutPhase="reset_post_response", eligibilityDispatchAttempted=True,
            resetDispatchAttempted=True, attemptedTarget=None, httpStatus=200,
            internalApiKeyAttached=True,
        )
        unresolved = verifier.classify_task8_9(
            events={"outcome": "b_skip_only", "success": None, "skip": local_stall,
                    "queryError": None}, observation={"golden": False}, decisions={},
            diagnostics={"reproductions": [{"replicaToken": "different"}]},
        )
        self.assertEqual(unresolved["class"], "class_2d_unresolved")
        attributed = verifier.classify_task8_9(
            events={"outcome": "b_skip_only", "success": None, "skip": local_stall,
                    "queryError": None}, observation={"golden": False}, decisions={},
            diagnostics={
                "reproductions": [{"replicaToken": "different", "sameRevision": True}],
                "applicationBlockingEvidence": {
                    "kind": "blockhound", "reproductionIndex": 0,
                    "artifact": "evidence/blockhound-run-1.json",
                },
            },
        )
        self.assertEqual(attributed["class"], "class_1_diagnosed")

        over_bound = verifier.classify_task8_9(
            events={"outcome": "b_skip_only", "success": None, "skip": local_stall,
                    "queryError": None}, observation={"golden": False}, decisions={},
            diagnostics={
                "reproductions": [{"replicaToken": str(index)} for index in range(3)],
                "applicationBlockingEvidence": True,
            },
        )
        self.assertEqual(over_bound["class"], "class_2d_unresolved")
        self.assertEqual(over_bound["action"], "reject_unbounded_reproduction_evidence")
        self.assertFalse(over_bound["rollbackAuthorized"])

    def test_manual_probe_and_blocking_evidence_require_validated_attribution(self) -> None:
        key_event = skip_event(
            reason="reset_key_not_configured", leg="reset", httpStatus=None,
            timeoutScope=None, overallTimeoutPhase=None, attemptedTarget=None,
            elapsedMillis=None, eligibilityDispatchAttempted=True,
            resetDispatchAttempted=False, internalApiKeyConfigured=False,
            internalApiKeyAttached=None,
        )
        base = {
            "templateReference": "intact",
            "replicaTokenRecovered": True,
            "manualResetStatus": 200,
        }
        for missing_correlation in (
            {},
            {"sameReplica": True},
            {"sameReplica": True, "emitterReplicaToken": key_event["replicaToken"]},
            {
                "sameReplica": True,
                "emitterReplicaToken": key_event["replicaToken"],
                "probeReplicaToken": key_event["replicaToken"],
                "emitterStillServing": False,
            },
        ):
            with self.subTest(missing_correlation=missing_correlation):
                result = verifier.classify_task8_9(
                    events={"outcome": "b_skip_only", "success": None, "skip": key_event,
                            "queryError": None},
                    observation={"golden": False}, decisions={},
                    diagnostics=base | missing_correlation,
                )
                self.assertFalse(result["rollbackAuthorized"])
                self.assertFalse(result["resolved"])

        attributed = verifier.classify_task8_9(
            events={"outcome": "b_skip_only", "success": None, "skip": key_event,
                    "queryError": None},
            observation={"golden": False}, decisions={},
            diagnostics=base | {
                "sameReplica": True,
                "emitterReplicaToken": key_event["replicaToken"],
                "probeReplicaToken": key_event["replicaToken"],
                "emitterStillServing": True,
            },
        )
        self.assertTrue(attributed["rollbackAuthorized"])

        local_stall = skip_event(
            overallTimeoutPhase="between_legs", eligibilityDispatchAttempted=True,
            resetDispatchAttempted=False, attemptedTarget=None,
            internalApiKeyAttached=None,
        )
        weak = verifier.classify_task8_9(
            events={"outcome": "b_skip_only", "success": None, "skip": local_stall,
                    "queryError": None},
            observation={"golden": False}, decisions={},
            diagnostics={"applicationBlockingEvidence": True, "reproductions": []},
        )
        self.assertFalse(weak["rollbackAuthorized"])
        self.assertFalse(weak["resolved"])

        strong = verifier.classify_task8_9(
            events={"outcome": "b_skip_only", "success": None, "skip": local_stall,
                    "queryError": None},
            observation={"golden": False}, decisions={},
            diagnostics={
                "reproductions": [{"replicaToken": "token-b", "sameRevision": True}],
                "applicationBlockingEvidence": {
                    "kind": "blockhound", "reproductionIndex": 0,
                    "artifact": "evidence/blockhound-run-1.json",
                },
            },
        )
        self.assertTrue(strong["rollbackAuthorized"])

    def test_non_go_skip_uses_event_facts_without_symbolic_diagnostic_operation(self) -> None:
        commands = StatefulCommandRunner(event_mode="skip")
        http = StatefulHttpRunner(commands)
        clock = Clock()

        result = verifier.run_proof(
            config(), command_runner=commands, http_runner=http,
            now=clock.now, monotonic=clock.monotonic,
            sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertTrue(result.evidence["diagnostics"]["available"])
        self.assertTrue(result.evidence["diagnostics"]["directEventDiagnosticsOnly"])
        self.assertEqual(result.evidence["diagnostics"]["operations"], [])

    def test_query_exception_still_runs_both_queries_and_retains_partial_observation(self) -> None:
        commands = StatefulCommandRunner(event_mode="skip")
        http = StatefulHttpRunner(commands)
        clock = Clock()
        seen: list[str] = []

        def raising_runner(command, *, timeout_seconds=None):
            joined = " ".join(command)
            if "demo_reset_succeeded" in joined:
                seen.append("success")
                raise RuntimeError("query exploded")
            if "demo_reset_self_call_skipped" in joined:
                seen.append("skip")
            return commands(command, timeout_seconds=timeout_seconds)

        result = verifier.run_proof(
            config(), command_runner=raising_runner, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertEqual(result.evidence["events"]["outcome"], "d_query_error")
        self.assertIn("success", seen)
        self.assertIn("skip", seen)
        self.assertIsNotNone(result.evidence["events"]["skip"])
        self.assertEqual(result.evidence["classificationDetail"]["class"], "class_2b")

    def test_later_inconsistent_duplicate_event_can_never_preserve_go(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        http = StatefulHttpRunner(commands)
        clock = Clock()
        success_calls = 0

        def duplicate_runner(command, *, timeout_seconds=None):
            nonlocal success_calls
            result = commands(command, timeout_seconds=timeout_seconds)
            if "demo_reset_succeeded" in " ".join(command) and result.returncode == 0:
                success_calls += 1
                if success_calls >= 2:
                    import json
                    rows = json.loads(result.stdout)
                    if rows:
                        duplicate = dict(rows[0])
                        duplicate["Log_s"] = duplicate["Log_s"].replace("version=3", "version=4")
                        rows.append(duplicate)
                        return verifier.CommandResult(0, json.dumps(rows), "")
            return result

        result = verifier.run_proof(
            config(), command_runner=duplicate_runner, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertEqual(result.evidence["events"]["outcome"], "d_query_error")
        self.assertIn("inconsistent", result.evidence["events"]["queryError"])

    def test_later_distinct_emission_with_same_payload_can_never_preserve_go(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        http = StatefulHttpRunner(commands)
        clock = Clock()
        success_calls = 0

        def distinct_emission(command, *, timeout_seconds=None):
            nonlocal success_calls
            result = commands(command, timeout_seconds=timeout_seconds)
            if "demo_reset_succeeded" in " ".join(command) and result.returncode == 0:
                success_calls += 1
                if success_calls == 2:
                    rows = __import__("json").loads(result.stdout)
                    rows[0]["TimeGenerated"] = "2026-09-06T01:02:05Z"
                    return verifier.CommandResult(0, __import__("json").dumps(rows), "")
            return result

        result = verifier.run_proof(
            config(), command_runner=distinct_emission, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertEqual(result.evidence["events"]["outcome"], "d_query_error")
        self.assertIn("distinct later success emission", result.evidence["events"]["queryError"])

    def test_runner_deadlines_cover_commands_slow_cleanup_and_post_cleanup_read(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        http = StatefulHttpRunner(commands)
        clock = Clock()
        read_count = 0

        def slow_http(*, method, url, headers, json_body=None, timeout_seconds=None):
            nonlocal read_count
            if method == "GET" and url.endswith("/api/portfolio"):
                read_count += 1
                if read_count == 3:
                    clock.sleep(25)
            return http(method=method, url=url, headers=headers, json_body=json_body,
                        timeout_seconds=timeout_seconds)

        result = verifier.run_proof(
            config(), command_runner=commands, http_runner=slow_http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertTrue(all(timeout is not None and timeout > 0 for timeout in commands.timeouts))
        self.assertTrue(all(
            operation.get("timeoutSeconds", 0) > 0
            for operation in result.evidence["operations"]
        ))
        cleanup_puts = [r for r in http.requests if r["url"].endswith("/api/portfolio/demo-reset")]
        self.assertEqual(cleanup_puts, [])
        self.assertTrue(result.evidence["cleanup"]["deadlineExceeded"])

    def test_cleanup_put_returning_after_absolute_deadline_cannot_preserve_go(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        http = StatefulHttpRunner(commands)
        clock = Clock()

        def slow_cleanup_put(*, method, url, headers, json_body=None, timeout_seconds=None):
            response = http(
                method=method, url=url, headers=headers, json_body=json_body,
                timeout_seconds=timeout_seconds,
            )
            if method == "PUT" and url.endswith("/api/portfolio/demo-reset"):
                clock.sleep(21)
            return response

        result = verifier.run_proof(
            config(), command_runner=commands, http_runner=slow_cleanup_put,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertTrue(result.evidence["cleanup"]["deadlineExceeded"])
        self.assertFalse(result.evidence["cleanup"]["succeeded"])

    def test_every_runner_call_honors_the_configured_operation_timeout_cap(self) -> None:
        cfg = config()
        cfg.operation_timeout_seconds = 7.0
        result, commands, http, _clock = run_case(event_mode="success", cfg=cfg)
        self.assertEqual(result.exit_code, 0)
        self.assertTrue(commands.timeouts)
        self.assertTrue(http.timeouts)
        # Every call stays bounded. Control-plane calls honour the short operation cap; the demo
        # login alone gets the longer login budget, because api-gateway's cold start is paid before
        # the login handler runs and the approved orchestration deadline is 60s on top of that.
        self.assertTrue(all(
            timeout is not None and 0 < timeout
            for timeout in commands.timeouts + http.timeouts
        ))
        self.assertTrue(all(
            timeout <= cfg.operation_timeout_seconds for timeout in commands.timeouts
        ))
        login_budget = [t for t in http.timeouts if t == cfg.login_timeout_seconds]
        self.assertEqual(len(login_budget), 1, "exactly one call may use the login budget")
        self.assertTrue(all(
            timeout <= cfg.operation_timeout_seconds
            for timeout in http.timeouts if timeout != cfg.login_timeout_seconds
        ))

    def test_login_budget_must_exceed_the_approved_overall_orchestration_deadline(self) -> None:
        cfg = config()
        cfg.login_timeout_seconds = 60.0  # equal to the approved 60s overall deadline, not greater
        result, _commands, _http, _clock = run_case(event_mode="success", cfg=cfg)
        self.assertNotEqual(result.exit_code, 0)

    def test_login_budget_must_be_positive(self) -> None:
        cfg = config()
        cfg.login_timeout_seconds = 0.0
        result, _commands, _http, _clock = run_case(event_mode="success", cfg=cfg)
        self.assertNotEqual(result.exit_code, 0)

    def test_serving_revision_config_is_revalidated_after_aging_and_before_go(self) -> None:
        result, commands, _http, _clock = run_case(event_mode="success")
        self.assertEqual(result.exit_code, 0)
        self.assertGreaterEqual(commands.revision_calls["api-gateway"], 3)
        self.assertGreaterEqual(commands.revision_calls["portfolio-service"], 3)
        env_reads = [command for command in commands.commands if
                     command[:4] == ["az", "containerapp", "revision", "show"]]
        self.assertGreaterEqual(len(env_reads), 3)
        self.assertTrue(all("--revision" in command for command in env_reads))
        self.assertEqual(result.evidence["provider"], "azure")
        self.assertTrue(result.evidence["keyAlignment"]["internalApiKeyProven"])
        self.assertTrue(result.evidence["servingRevalidation"]["final"]["matched"])

    def test_threshold_override_readback_is_pinned_to_the_serving_revision(self) -> None:
        result, commands, _http, _clock = run_case(event_mode="success", cfg=config(override="1s"))
        self.assertNotEqual(result.exit_code, 0)
        revision_env_reads = [
            command for command in commands.commands
            if command[:4] == ["az", "containerapp", "revision", "show"]
            and "--query" in command
        ]
        self.assertGreaterEqual(len(revision_env_reads), 5)
        self.assertTrue(all("--revision" in command for command in revision_env_reads))
        self.assertFalse(any(
            command[:3] == ["az", "containerapp", "show"]
            and "APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD" in " ".join(command)
            for command in commands.commands
        ))

    def test_key_diagnosis_uses_concrete_command_and_http_orchestration_by_default(self) -> None:
        commands = StatefulCommandRunner(event_mode="skip")
        commands.skip_reason = "reset_key_not_configured"
        original_command = commands.__call__
        exec_count = 0

        def diagnostic_commands(command, *, timeout_seconds=None):
            nonlocal exec_count
            if command[:4] == ["az", "containerapp", "revision", "show"]:
                revision = commands._value_after(command, "--revision")
                if "environment variable identity" in " ".join(command):
                    raise AssertionError("labels must not leak into argv")
                if revision in {"api-gateway--0000101", "api-gateway--0000100"}:
                    return verifier.CommandResult(
                        0,
                        __import__("json").dumps(
                            [{"name": "INTERNAL_API_KEY", "secretRef": "internal-api-key"}]
                        ),
                        "",
                    )
            if command[:2] == ["docker", "run"]:
                self.assertIn(commands.revisions["api-gateway"][0]["image"], command)
                self.assertEqual(command[-1], "api-gateway--0000101-replica-a")
                return verifier.CommandResult(0, "95ca17821ade\n", "")
            if command[:3] == ["az", "containerapp", "exec"]:
                exec_count += 1
                if exec_count >= 2:
                    self.assertEqual(commands._value_after(command, "--replica"),
                                     "api-gateway--0000101-replica-a")
                    return verifier.CommandResult(0, "blank\n", "")
            return original_command(command, timeout_seconds=timeout_seconds)

        http = StatefulHttpRunner(commands)
        original_http = http.__call__
        diagnostic_reset_count = 0

        def diagnostic_http(*, method, url, headers, json_body=None, timeout_seconds=None):
            nonlocal diagnostic_reset_count
            if method == "PUT" and url.endswith("/api/portfolio/demo-reset"):
                diagnostic_reset_count += 1
                if diagnostic_reset_count == 1:
                    self.assertEqual(json_body, {"expectedVersion": http.version})
                    return verifier.HttpResponse(
                        503,
                        {"error": "internal_api_key_not_configured", "message": "unavailable"},
                        {"X-Gateway-Replica-Token": "95ca17821ade"},
                    )
            return original_http(
                method=method, url=url, headers=headers, json_body=json_body,
                timeout_seconds=timeout_seconds,
            )

        cfg = config()
        cfg.last_known_good_gateway_revision = "api-gateway--0000100"
        clock = Clock()
        result = verifier.run_proof(
            cfg, command_runner=diagnostic_commands, http_runner=diagnostic_http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertEqual(result.evidence["classification"], "class_2h")
        self.assertEqual(result.evidence["diagnostics"]["templateReference"], "intact")
        self.assertTrue(result.evidence["diagnostics"]["replicaTokenRecovered"])
        self.assertTrue(result.evidence["diagnostics"]["sameReplica"])
        self.assertEqual(result.evidence["diagnostics"]["presence"], "blank")

    def test_same_replica_manual_200_is_conclusive_without_presence_probe(self) -> None:
        cfg, evidence, command_runner, http_runner, commands, event = (
            self._key_diagnostic_boundaries(manual_status=200, presence_fails=True)
        )
        try:
            facts = verifier._diagnose_unconfigured_key(
                cfg, evidence, command_runner, http_runner, event
            )
        except verifier.ProofError as error:
            self.fail(f"conclusive manual 200 must not invoke presence: {error}")
        self.assertEqual(facts["manualResetStatus"], 200)
        self.assertTrue(facts["sameReplica"])
        self.assertFalse(any(command[:3] == ["az", "containerapp", "exec"]
                             for command in commands))
        self.assertNotIn("presence", facts)

    def test_gateway_503_requires_presence_and_retains_prior_facts_if_it_fails(self) -> None:
        cfg, evidence, command_runner, http_runner, commands, event = (
            self._key_diagnostic_boundaries(manual_status=503, presence_fails=True)
        )
        facts = verifier._collect_diagnostics(
            cfg, evidence, command_runner, http_runner, event
        )
        self.assertTrue(any(command[:3] == ["az", "containerapp", "exec"]
                            for command in commands))
        self.assertEqual(facts.get("templateReference"), "intact")
        self.assertTrue(facts.get("replicaTokenRecovered"))
        self.assertEqual(facts.get("manualResetStatus"), 503)
        self.assertEqual(facts.get("manualResetEmitter"), "gateway")
        self.assertTrue(facts.get("sameReplica"))
        self.assertFalse(facts["available"])
        detail = verifier.classify_task8_9(
            events={"outcome": "b_skip_only", "success": None, "skip": event,
                    "queryError": None},
            observation={"golden": False}, decisions={}, diagnostics=facts,
        )
        self.assertEqual(detail["action"], "retry_presence_probe")
        self.assertFalse(detail["resolved"])
        self.assertFalse(detail["rollbackAuthorized"])

    def test_missing_last_good_template_reference_cannot_authorize_rollback(self) -> None:
        cfg = config()
        cfg.last_known_good_gateway_revision = "api-gateway--0000100"
        evidence = verifier._initial_evidence(cfg)
        evidence["serving"]["api-gateway"] = {
            "revision": "api-gateway--0000101",
            "image": "wealthprodacr.azurecr.io/api-gateway@" + DIGEST_A,
        }

        def template_runner(command, *, timeout_seconds=None):
            revision = command[command.index("--revision") + 1]
            rows = ([{"name": "INTERNAL_API_KEY", "secretRef": "internal-api-key"}]
                    if revision == "api-gateway--0000101" else [])
            return verifier.CommandResult(0, __import__("json").dumps(rows), "")

        with self.assertRaisesRegex(verifier.ProofError, "last-known-good.*reference"):
            verifier._diagnose_unconfigured_key(
                cfg, evidence, template_runner,
                lambda **_kwargs: verifier.HttpResponse(500, {}, {}),
                skip_event(
                    reason="reset_key_not_configured", leg="reset", httpStatus=None,
                    timeoutScope=None, overallTimeoutPhase=None, attemptedTarget=None,
                    elapsedMillis=None, eligibilityDispatchAttempted=True,
                    resetDispatchAttempted=False, internalApiKeyConfigured=False,
                    internalApiKeyAttached=None,
                ),
            )

    def test_gateway_local_timeout_records_gated_bounded_reproduction_plan(self) -> None:
        commands = StatefulCommandRunner(event_mode="skip")
        commands.skip_reason = "overall_timeout"
        original_command = commands.__call__

        def between_legs(command, *, timeout_seconds=None):
            result = original_command(command, timeout_seconds=timeout_seconds)
            if (result.returncode == 0
                    and "demo_reset_self_call_skipped" in " ".join(command)):
                rows = __import__("json").loads(result.stdout)
                for row in rows:
                    row["Log_s"] = (row["Log_s"]
                        .replace("attemptedTarget=http://localhost:8080/api/internal/portfolio/demo-reset",
                                 "attemptedTarget=null")
                        .replace("overallTimeoutPhase=reset_in_flight",
                                 "overallTimeoutPhase=between_legs")
                        .replace("resetDispatchAttempted=true", "resetDispatchAttempted=false")
                        .replace("internalApiKeyAttached=true", "internalApiKeyAttached=null"))
                return verifier.CommandResult(0, __import__("json").dumps(rows), "")
            return result

        http = StatefulHttpRunner(commands)
        clock = Clock()
        result = verifier.run_proof(
            config(), command_runner=between_legs, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertEqual(result.evidence["classification"], "class_2d_unresolved")
        self.assertFalse(result.evidence["classificationDetail"]["rollbackAuthorized"])
        self.assertIn("requiresSeparateApproval", result.evidence["diagnostics"])
        self.assertTrue(result.evidence["diagnostics"]["requiresSeparateApproval"])
        self.assertEqual(result.evidence["diagnostics"]["boundedReproductionPlan"], {
            "maximumAttempts": 2,
            "eachAttempt": [
                "fresh_identity_version_read", "deliberate_non_golden_write",
                "strict_idle_aging", "fresh_trace_login", "both_event_queries",
            ],
        })
        login_requests = [request for request in http.requests
                          if request["url"].endswith("/api/auth/login")]
        self.assertEqual(len(login_requests), 1)

    def test_serving_drift_after_long_age_is_non_go_before_login(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        original = commands.__call__

        def drift(command, *, timeout_seconds=None):
            if command[:4] == ["az", "containerapp", "revision", "list"]:
                service = commands._value_after(command, "--name")
                if commands.revision_calls[service] >= 1:
                    commands.revisions[service][0]["image"] = (
                        commands.revisions[service][0]["image"].replace(DIGEST_A, "sha256:" + "c" * 64)
                        if service == "api-gateway" else commands.revisions[service][0]["image"]
                    )
            return original(command, timeout_seconds=timeout_seconds)

        http = StatefulHttpRunner(commands)
        clock = Clock()
        result = verifier.run_proof(
            config(), command_runner=drift, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertFalse(any(r["url"].endswith("/api/auth/login") for r in http.requests))

    def test_every_retained_surface_redacts_both_configured_credentials(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        commands.fail_command_containing = "account show"
        original = commands.__call__

        def leaking_command(command, *, timeout_seconds=None):
            result = original(command, timeout_seconds=timeout_seconds)
            if result.returncode:
                return verifier.CommandResult(1, "", "setup-token not-recorded")
            return result

        result = verifier.run_proof(config(), command_runner=leaking_command)
        rendered = __import__("json").dumps(result.evidence, sort_keys=True)
        self.assertNotIn("setup-token", rendered)
        self.assertNotIn("not-recorded", rendered)
        self.assertIn("[REDACTED]", rendered)

    def test_timeout_bands_reset_duplicate_call_and_manual_probe_are_discriminated(self) -> None:
        for elapsed, action in (
            (2200, "resolve_downstream_latency"),
            (3000, "collect_timeout_attribution_evidence"),
        ):
            with self.subTest(elapsed=elapsed):
                event = skip_event(
                    reason="eligibility_timeout", leg="eligibility", timeoutScope="per-leg",
                    overallTimeoutPhase=None, elapsedMillis=elapsed,
                    attemptedTarget="http://localhost:8080/api/portfolio",
                )
                result = verifier.classify_task8_9(
                    events={"outcome": "b_skip_only", "success": None, "skip": event,
                            "queryError": None},
                    observation={"golden": False}, decisions={"eligibilityTimeout": "2s"},
                )
                self.assertEqual(result["class"], "class_2d")
                self.assertFalse(result["rollbackAuthorized"])
                self.assertEqual(result["action"], action)

        duplicate = skip_event(
            reason="reset_non_2xx_status", leg="reset", httpStatus=409,
            timeoutScope=None, overallTimeoutPhase=None, attemptedTarget=None,
            elapsedMillis=None, eligibilityDispatchAttempted=True,
            resetDispatchAttempted=True, internalApiKeyAttached=True,
            observedVersion=7, submittedExpectedVersion=7, selfCallCount=2,
            downstreamCurrentVersion=8,
        )
        duplicate_result = verifier.classify_task8_9(
            events={"outcome": "b_skip_only", "success": None, "skip": duplicate,
                    "queryError": None}, observation={"golden": False}, decisions={},
        )
        self.assertEqual(duplicate_result["class"], "class_1_diagnosed")

        attached_403 = skip_event(
            reason="reset_non_2xx_status", leg="reset", httpStatus=403,
            timeoutScope=None, overallTimeoutPhase=None, attemptedTarget=None,
            elapsedMillis=None, eligibilityDispatchAttempted=True,
            resetDispatchAttempted=True, internalApiKeyAttached=True,
        )
        probe_result = verifier.classify_task8_9(
            events={"outcome": "b_skip_only", "success": None, "skip": attached_403,
                    "queryError": None}, observation={"golden": False}, decisions={},
            diagnostics={"manualResetStatus": 403},
        )
        self.assertEqual(probe_result["class"], "class_2d")
        self.assertEqual(probe_result["action"], "repair_key_configuration")

        missing_probe = verifier.classify_task8_9(
            events={"outcome": "b_skip_only", "success": None, "skip": attached_403,
                    "queryError": None}, observation={"golden": False}, decisions={},
            diagnostics={},
        )
        self.assertFalse(missing_probe["resolved"])
        self.assertFalse(missing_probe["rollbackAuthorized"])
        self.assertEqual(missing_probe["action"], "collect_manual_reset_probe")

    def test_reset_409_requires_the_full_four_field_evidence_quartet(self) -> None:
        missing_downstream = skip_event(
            reason="reset_non_2xx_status", leg="reset", httpStatus=409,
            timeoutScope=None, overallTimeoutPhase=None, attemptedTarget=None,
            elapsedMillis=None, eligibilityDispatchAttempted=True,
            resetDispatchAttempted=True, internalApiKeyAttached=True,
            observedVersion=7, submittedExpectedVersion=7, selfCallCount=1,
        )
        with self.assertRaisesRegex(verifier.ProofError, "409.*evidence"):
            verifier.validate_skip_event(missing_downstream, success_present=False)

    def test_skip_query_exception_retains_success_and_still_attempts_both_queries(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        http = StatefulHttpRunner(commands)
        clock = Clock()
        seen: list[str] = []

        def raising_runner(command, *, timeout_seconds=None):
            joined = " ".join(command)
            if "demo_reset_succeeded" in joined:
                seen.append("success")
            if "demo_reset_self_call_skipped" in joined:
                seen.append("skip")
                raise RuntimeError("skip query exploded")
            return commands(command, timeout_seconds=timeout_seconds)

        result = verifier.run_proof(
            config(), command_runner=raising_runner, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertEqual(result.evidence["events"]["outcome"], "d_query_error")
        self.assertIsNotNone(result.evidence["events"]["success"])
        self.assertEqual(seen, ["success", "skip"])

    def test_polling_never_starts_a_pair_after_budget_but_finishes_a_started_pair(self) -> None:
        trace_id = "0123456789abcdef0123456789abcdef"
        start = datetime(2026, 9, 6, 1, 2, 3, tzinfo=timezone.utc)
        end = datetime(2026, 9, 6, 1, 3, 3, tzinfo=timezone.utc)

        for deadline, interval in ((0.5, 0.5), (0.15, 1.0)):
            with self.subTest(deadline=deadline):
                cfg = config()
                cfg.poll_deadline_seconds = deadline
                cfg.poll_interval_seconds = interval
                clock = Clock()
                calls: list[str] = []

                def timed_empty_query(command, *, timeout_seconds=None):
                    calls.append("success" if "demo_reset_succeeded" in " ".join(command)
                                 else "skip")
                    clock.sleep(0.1)
                    return verifier.CommandResult(0, "[]", "")

                evidence = verifier._initial_evidence(cfg)
                evidence["servingRevalidation"]["afterAge"] = {"services": {
                    "api-gateway": {"revision": "api-gateway--0000101"},
                    "portfolio-service": {"revision": "portfolio-service--0000202"},
                }}
                evidence["target"]["workspaceCustomerId"] = "workspace-customer-id"
                evidence["trace"] = {
                    "windowStart": verifier._utc(start),
                    "windowEnd": verifier._utc(end),
                }
                result = verifier._poll_events(
                    cfg, evidence, timed_empty_query, trace_id=trace_id,
                    start=start, end=end, monotonic=clock.monotonic, sleep=clock.sleep,
                )
                self.assertEqual(calls, ["success", "skip"])
                self.assertEqual(result["outcome"], "c_neither")

    def test_identical_repeated_events_are_deduplicated_without_masking_go(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        http = StatefulHttpRunner(commands)
        clock = Clock()

        def repeated_runner(command, *, timeout_seconds=None):
            result = commands(command, timeout_seconds=timeout_seconds)
            if "demo_reset_succeeded" in " ".join(command) and result.returncode == 0:
                import json
                rows = json.loads(result.stdout)
                if rows:
                    rows.append(dict(rows[0]))
                    return verifier.CommandResult(0, json.dumps(rows), "")
            return result

        result = verifier.run_proof(
            config(), command_runner=repeated_runner, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertEqual(result.exit_code, 0)

    def test_separate_emissions_with_same_payload_fail_closed(self) -> None:
        trace_id = "0123456789abcdef0123456789abcdef"
        raw = f"INFO [{trace_id}] event=demo_reset_succeeded version=3"
        rows = [
            {"TimeGenerated": "2026-09-06T01:02:04Z", "Log_s": raw},
            {"TimeGenerated": "2026-09-06T01:02:05Z", "Log_s": raw},
        ]
        with self.assertRaisesRegex(verifier.ProofError, "multiple"):
            verifier.parse_event_rows(
                rows, event="demo_reset_succeeded", trace_id=trace_id
            )

        rows[1] = {
            "TimeGenerated": rows[0]["TimeGenerated"],
            "Log_s": raw + " emitterSequence=2",
        }
        with self.assertRaisesRegex(verifier.ProofError, "multiple"):
            verifier.parse_event_rows(
                rows, event="demo_reset_succeeded", trace_id=trace_id
            )

    def test_yaml_defaults_are_authoritative_when_revision_env_entries_are_absent(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        commands.decision_values = {}
        http = StatefulHttpRunner(commands)
        clock = Clock()
        result = verifier.run_proof(
            config(), command_runner=commands, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertEqual(result.exit_code, 0)
        self.assertEqual(result.evidence["decisions"]["serving"]["overallTimeout"], "60s")

    def test_final_serving_revision_drift_after_cleanup_rejects_go(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        original = commands.__call__

        def final_drift(command, *, timeout_seconds=None):
            if command[:4] == ["az", "containerapp", "revision", "list"]:
                service = commands._value_after(command, "--name")
                if service == "api-gateway" and commands.revision_calls[service] >= 2:
                    commands.revisions[service][0]["name"] = "api-gateway--stale-final"
            return original(command, timeout_seconds=timeout_seconds)

        http = StatefulHttpRunner(commands)
        clock = Clock()
        result = verifier.run_proof(
            config(), command_runner=final_drift, http_runner=http,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertFalse(result.evidence["servingRevalidation"]["final"]["matched"])

    def test_post_cleanup_read_exceeding_its_own_allowance_is_rejected(self) -> None:
        commands = StatefulCommandRunner(event_mode="success")
        http = StatefulHttpRunner(commands)
        clock = Clock()
        read_count = 0

        def slow_final_read(*, method, url, headers, json_body=None, timeout_seconds=None):
            nonlocal read_count
            response = http(method=method, url=url, headers=headers, json_body=json_body,
                            timeout_seconds=timeout_seconds)
            if method == "GET" and url.endswith("/api/portfolio"):
                read_count += 1
                if read_count == 4:
                    clock.sleep(6)
            return response

        result = verifier.run_proof(
            config(), command_runner=commands, http_runner=slow_final_read,
            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertFalse(result.evidence["cleanup"]["postCleanupGolden"])
        self.assertIn("bounded allowance", result.evidence["cleanup"]["postCleanupError"])

    def test_recursive_redaction_covers_query_http_cleanup_restore_and_diagnostics(self) -> None:
        leaked = {
            "queryError": "setup-token",
            "http": {"payload": "not-recorded"},
            "cleanup": [{"error": "prefix setup-token suffix"}],
            "thresholdRestore": {"error": "not-recorded"},
            "diagnostics": {"payload": ["setup-token", "not-recorded"]},
            "invocation": "setup-token:not-recorded",
        }
        rendered = __import__("json").dumps(
            verifier.redact_evidence(leaked, ["setup-token", "not-recorded"]), sort_keys=True
        )
        self.assertNotIn("setup-token", rendered)
        self.assertNotIn("not-recorded", rendered)
        self.assertGreaterEqual(rendered.count("[REDACTED]"), 7)


class FinalReviewRegressionTest(unittest.TestCase):
    def test_http_redirect_cannot_escape_the_recorded_target(self):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        import threading

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", "/outside-proof-target")
                    self.end_headers()
                else:
                    self.server.outside_requests += 1
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(b'{"unexpected":"target"}')

        with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            server.outside_requests = 0
            threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01},
                             daemon=True).start()
            try:
                response = verifier._default_http_runner(
                    method="GET", url=f"http://127.0.0.1:{server.server_port}/redirect",
                    headers={"Authorization": "Bearer test-only-token"}, timeout_seconds=2)
                self.assertEqual(response.status, 302)
                self.assertEqual(server.outside_requests, 0)
            finally:
                server.shutdown()

    def test_stale_revision_url_is_rejected_before_http_or_mutation(self):
        for url in ("https://api-gateway--old.current.test", "https://unapproved.test",
                    "https://wealth.example.test:444", "https://wealth.example.test/old",
                    "https://user@wealth.example.test"):
            with self.subTest(url=url):
                cfg = config()
                cfg.gateway_url = url
                result, _, http, _ = run_case(cfg=cfg)
                self.assertEqual(result.exit_code, 1)
                self.assertFalse(result.evidence["verdict"]["go"])
                self.assertFalse(result.evidence["cleanup"]["armed"])
                self.assertEqual(http.requests, [])

    def test_current_ingress_fqdn_and_bound_domain_are_accepted(self):
        for url in ("https://api-gateway.current.test", "https://wealth.example.test"):
            cfg = config()
            cfg.gateway_url = url
            result, _, _, _ = run_case(cfg=cfg)
            self.assertEqual(result.exit_code, 0)

    def test_preflight_accepts_current_fqdn_when_custom_domains_are_null_missing_or_empty(self):
        for label, custom_domains in (("null", None), ("missing", ...), ("empty", [])):
            with self.subTest(shape=label):
                commands = StatefulCommandRunner()
                commands.ingress = {"external": True, "fqdn": "api-gateway.current.test"}
                if custom_domains is not ...:
                    commands.ingress["customDomains"] = custom_domains
                cfg = config(mode="preflight")
                cfg.gateway_url = "https://api-gateway.current.test"
                http = StatefulHttpRunner(commands)

                result = verifier.run_proof(cfg, command_runner=commands, http_runner=http)

                self.assertEqual(result.exit_code, 0)
                self.assertEqual(http.requests, [])
                self.assertFalse(result.evidence["cleanup"]["armed"])
                self.assertFalse(any(command[:3] == ["az", "containerapp", "update"]
                                     for command in commands.commands))

    def test_preflight_rejects_malformed_or_unbound_gateway_custom_domains(self):
        cases = (
            ("malformed", {"name": "wealth.example.test"}, "https://api-gateway.current.test",
             "gateway customDomains is not a list"),
            ("unbound", [], "https://wealth.example.test",
             "gateway URL is outside the approved app's current ingress/domain binding"),
        )
        for label, custom_domains, url, expected_error in cases:
            with self.subTest(shape=label):
                commands = StatefulCommandRunner()
                commands.ingress = {
                    "external": True,
                    "fqdn": "api-gateway.current.test",
                    "customDomains": custom_domains,
                }
                cfg = config(mode="preflight")
                cfg.gateway_url = url
                http = StatefulHttpRunner(commands)

                result = verifier.run_proof(cfg, command_runner=commands, http_runner=http)

                self.assertEqual(result.exit_code, 1)
                self.assertEqual(result.evidence["verdict"]["errors"], [expected_error])
                self.assertEqual(http.requests, [])
                self.assertFalse(result.evidence["cleanup"]["armed"])

    def test_ingress_binding_drift_fails_before_login_and_still_cleans_up(self):
        commands = StatefulCommandRunner()
        http = StatefulHttpRunner(commands)
        clock = Clock()
        binding_reads = 0

        def drift(command, *, timeout_seconds=None):
            nonlocal binding_reads
            if "properties.configuration.ingress" in command:
                binding_reads += 1
                if binding_reads > 1:
                    return commands._result({"external": True, "fqdn": "api-gateway.current.test",
                                             "customDomains": []})
            return commands(command, timeout_seconds=timeout_seconds)

        result = verifier.run_proof(config(), command_runner=drift, http_runner=http,
                                    now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep)
        self.assertEqual(result.exit_code, 1)
        self.assertEqual(result.evidence["requestCounts"]["logins"], 0)
        self.assertTrue(result.evidence["cleanup"]["postCleanupGolden"])

    def test_event_from_another_app_is_rejected_even_with_matching_revision(self):
        import json
        commands = StatefulCommandRunner()
        http = StatefulHttpRunner(commands)
        clock = Clock()

        def other_app(command, *, timeout_seconds=None):
            response = commands(command, timeout_seconds=timeout_seconds)
            if command[:4] == ["az", "monitor", "log-analytics", "query"]:
                rows = json.loads(response.stdout)
                for row in rows:
                    if "Log_s" in row:
                        row["ContainerAppName_s"] = "different-app"
                return commands._result(rows)
            return response

        result = verifier.run_proof(config(), command_runner=other_app, http_runner=http,
                                    now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep)
        self.assertEqual(result.evidence["events"]["outcome"], "d_query_error")
        self.assertEqual(result.exit_code, 1)

    def test_cross_revision_events_fail_closed_for_each_service(self):
        import json
        for kind in ("success", "skip"):
            for identity in ("other--revision", None):
                with self.subTest(kind=kind, identity=identity):
                    commands = StatefulCommandRunner(event_mode=kind)
                    http = StatefulHttpRunner(commands)
                    clock = Clock()

                    def mismatched(command, *, timeout_seconds=None):
                        response = commands(command, timeout_seconds=timeout_seconds)
                        if command[:4] == ["az", "monitor", "log-analytics", "query"]:
                            rows = json.loads(response.stdout)
                            for row in rows:
                                if "Log_s" in row:
                                    row["RevisionName_s"] = identity
                            return commands._result(rows)
                        return response

                    result = verifier.run_proof(config(), command_runner=mismatched,
                                                http_runner=http, now=clock.now,
                                                monotonic=clock.monotonic, sleep=clock.sleep)
                    self.assertEqual(result.evidence["events"]["outcome"], "d_query_error")
                    self.assertIn("revision", result.evidence["events"]["queryError"])
                    self.assertEqual(result.exit_code, 1)
                    self.assertTrue(result.evidence["cleanup"]["postCleanupGolden"])

    def test_service_specific_revision_identity_is_retained_and_queried(self):
        result, _, _, _ = run_case(event_mode="both")
        for kind, app, revision in (("success", "portfolio-service", "portfolio-service--0000202"),
                                    ("skip", "api-gateway", "api-gateway--0000101")):
            event = result.evidence["events"][kind]
            self.assertEqual(event.get("revisionName"), revision)
            self.assertEqual(event.get("containerAppName"), app)
            self.assertIn("RevisionName_s", result.evidence["events"]["queries"][kind])

    def test_real_slow_stream_total_deadline_and_armed_cleanup(self):
        import socketserver
        import threading
        import time
        from unittest.mock import patch

        class Drip(socketserver.BaseRequestHandler):
            def handle(self):
                self.request.recv(65536)
                self.server.started.set()
                status = self.server.status
                head = f"HTTP/1.1 {status} Slow\r\nContent-Length: 42\r\nConnection: close\r\n\r\n".encode()
                try:
                    if self.server.slow_headers:
                        self.request.sendall(head[:8])
                    else:
                        self.request.sendall(head + b'"a')
                    self.server.partial_response_sent.set()
                    # Hold the real socket open until the deadline kills the disposable HTTP
                    # worker. Blocking on peer close is condition-driven and avoids scheduler-
                    # dependent byte-drip sleeps while preserving the real transport boundary.
                    while self.request.recv(65536):
                        pass
                except OSError:
                    pass
                finally:
                    self.server.disconnected.set()

        class Server(socketserver.ThreadingTCPServer):
            daemon_threads = True

        with Server(("127.0.0.1", 0), Drip) as server:
            server.started = threading.Event()
            server.partial_response_sent = threading.Event()
            server.disconnected = threading.Event()
            threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01},
                             daemon=True).start()
            url = f"http://127.0.0.1:{server.server_address[1]}"
            real_popen = verifier.subprocess.Popen
            workers = []

            def recording_popen(*args, **kwargs):
                worker = real_popen(*args, **kwargs)
                workers.append(worker)
                return worker

            try:
                with patch.object(verifier.subprocess, "Popen", side_effect=recording_popen):
                    for status, slow_headers in ((200, False), (500, False), (200, True)):
                        with self.subTest(status=status, slow_headers=slow_headers):
                            server.status, server.slow_headers = status, slow_headers
                            server.started.clear()
                            server.partial_response_sent.clear()
                            server.disconnected.clear()
                            began = time.monotonic()
                            with self.assertRaisesRegex(verifier.ProofError, "deadline"):
                                verifier._default_http_runner(method="GET", url=url, headers={},
                                                              timeout_seconds=1.5)
                            self.assertLess(time.monotonic() - began, 2.5)
                            self.assertTrue(server.started.is_set(), "must exercise real transport")
                            self.assertTrue(server.partial_response_sent.is_set(),
                                            "deadline must interrupt a partial real response")
                            self.assertTrue(server.disconnected.wait(1.0),
                                            "timed-out transport must stop")
                            self.assertNotEqual(
                                workers[-1].returncode,
                                0,
                                "parent deadline must forcibly terminate the live HTTP worker",
                            )

                server.status, server.slow_headers = 200, False
                commands = StatefulCommandRunner()
                http = StatefulHttpRunner(commands)
                clock = Clock()
                cfg = config(override="1s")
                cfg.operation_timeout_seconds = 1.5

                def interrupted_write(**kwargs):
                    response = http(**kwargs)
                    if kwargs["url"].endswith("/holdings"):
                        return verifier._default_http_runner(method="GET", url=url, headers={},
                                                             timeout_seconds=kwargs["timeout_seconds"])
                    return response

                result = verifier.run_proof(cfg, command_runner=commands, http_runner=interrupted_write,
                                            now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep)
                self.assertEqual(result.exit_code, 1)
                self.assertIn("deadline", " ".join(result.evidence["verdict"]["errors"]))
                self.assertTrue(result.evidence["cleanup"]["postCleanupGolden"])
                self.assertTrue(result.evidence["thresholdRestore"]["verified"])
                self.assertEqual(commands.threshold, "30m")
                resets = [r for r in http.requests if r["url"].endswith("/demo-reset")]
                self.assertEqual(resets[0]["json"]["expectedVersion"], 2)
            finally:
                server.shutdown()

    def test_minted_token_redacted_from_retained_emitted_saved_and_cleanup_evidence(self):
        import json
        from unittest.mock import patch
        commands = StatefulCommandRunner()
        http = StatefulHttpRunner(commands)
        clock = Clock()
        cleanup_failed_once = False

        def leaking_http(**kwargs):
            nonlocal cleanup_failed_once
            if kwargs["headers"].get("Authorization") == "Bearer probe-token":
                raise RuntimeError("post-login Bearer probe-token setup-token not-recorded")
            if kwargs["url"].endswith("/demo-reset") and not cleanup_failed_once:
                cleanup_failed_once = True
                raise RuntimeError("cleanup Bearer probe-token")
            return http(**kwargs)

        result = verifier.run_proof(config(), command_runner=commands, http_runner=leaking_http,
                                    now=clock.now, monotonic=clock.monotonic, sleep=clock.sleep)
        retained = json.dumps(result.evidence)
        self.assertTrue(result.evidence["cleanup"]["postCleanupGolden"])
        self.assertIn("[REDACTED]", result.evidence["observation"]["error"])
        self.assertIn("[REDACTED]", result.evidence["cleanup"]["attempts"][0]["error"])
        with tempfile.TemporaryDirectory() as directory:
            provenance = Path(directory) / "deployment-provenance.json"
            saved = Path(directory) / "evidence.json"
            provenance.write_text(json.dumps(deployment_provenance()), encoding="utf-8")
            args = ["--mode", "execute", "--target", "production-azure", "--subscription", "sub-approved",
                    "--resource-group", "wealth-azure-prod-rg", "--gateway-app", "api-gateway",
                    "--portfolio-app", "portfolio-service", "--workspace", "wealth-prod-la",
                    "--registry", "wealthprodacr", "--gateway-url", "https://wealth.example.test",
                    "--deployment-provenance", str(provenance), "--evidence-output", str(saved)]
            emitted = []
            with patch.object(verifier, "run_proof", return_value=result):
                verifier.main(args, output=emitted.append)
            for surface in (retained, emitted[0], saved.read_text(encoding="utf-8")):
                for secret in ("probe-token", "setup-token", "not-recorded"):
                    self.assertNotIn(secret, surface)


if __name__ == "__main__":
    unittest.main()
