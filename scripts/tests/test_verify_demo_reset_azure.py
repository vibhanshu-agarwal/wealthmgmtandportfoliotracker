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
USER_ID = "00000000-0000-0000-0000-0000000d3110"
GOLDEN = [
    {"assetTicker": "AAPL", "quantity": "37.00000000"},
    {"assetTicker": "BTC-USD", "quantity": "15.00000000"},
]


class ManifestAndKqlContractTest(unittest.TestCase):
    def manifest(self) -> dict:
        return {
            "api-gateway": DIGEST_A,
            "portfolio-service": DIGEST_B,
        }

    def repositories(self) -> dict[str, str]:
        return {
            "api-gateway": "wealthprodacr.azurecr.io/api-gateway",
            "portfolio-service": "wealthprodacr.azurecr.io/portfolio-service",
        }

    def test_manifest_requires_current_attempt_and_exact_service_set(self) -> None:
        parsed = verifier.validate_deployment_manifest(
            self.manifest(), expected_attempt="17", expected_repository_sha=COMMIT,
            repositories=self.repositories(),
        )
        self.assertEqual(parsed["services"]["api-gateway"]["digest"], DIGEST_A)
        self.assertEqual(parsed["runAttempt"], "17")

        extra = self.manifest()
        extra["insight-service"] = "sha256:" + "c" * 64
        with self.assertRaisesRegex(verifier.ProofError, "exactly"):
            verifier.validate_deployment_manifest(
                extra, expected_attempt="17", expected_repository_sha=COMMIT,
                repositories=self.repositories(),
            )

        with self.assertRaisesRegex(verifier.ProofError, "current run attempt"):
            verifier.validate_deployment_manifest(
                self.manifest(), expected_attempt="", expected_repository_sha=COMMIT,
                repositories=self.repositories(),
            )

    def test_manifest_rejects_uppercase_or_non_digest_identity(self) -> None:
        bad = self.manifest()
        bad["api-gateway"] = "sha256:" + "A" * 64
        with self.assertRaisesRegex(verifier.ProofError, "lowercase sha256"):
            verifier.validate_deployment_manifest(
                bad, expected_attempt="17", expected_repository_sha=COMMIT,
                repositories=self.repositories(),
            )

        with self.assertRaisesRegex(verifier.ProofError, "lowercase repository SHA"):
            verifier.validate_deployment_manifest(
                self.manifest(),
                expected_attempt="17",
                expected_repository_sha="A" * 40,
                repositories=self.repositories(),
            )

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


def deployment_manifest() -> dict:
    return ManifestAndKqlContractTest().manifest()


def service_repositories() -> dict[str, str]:
    return ManifestAndKqlContractTest().repositories()


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
            "APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT": "2s",
            "APP_DEMO_LOGIN_RESET_RESET_TIMEOUT": "2s",
            "APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT": "4s",
        }
        self.restore_readback: str | None = None
        self.fail_first_update_after_apply = False
        self.update_count = 0
        self.fail_command_containing: str | None = None
        self.query_count = {"success": 0, "skip": 0}
        self.revision_calls = {service: 0 for service in ("api-gateway", "portfolio-service")}

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
            return self._result([{"TimeGenerated": payload["timestamp"], "Log_s": line}])
        if command[:4] == ["az", "containerapp", "revision", "show"]:
            values = dict(self.decision_values)
            if self.restore_readback is not None and self.update_count >= 2:
                values["APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD"] = self.restore_readback
            return self._result(
                [{"name": name, "value": value} for name, value in values.items()]
                + [{"name": "CLOUD_PROVIDER", "value": "azure"}]
            )
        if command[:3] == ["az", "containerapp", "show"]:
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
        repository_sha=COMMIT,
        run_attempt="17",
        deployment_manifest=deployment_manifest(),
        service_repositories=service_repositories(),
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

    def test_preflight_rejects_subscription_and_repo_digest_disagreement_without_writes(self) -> None:
        commands = StatefulCommandRunner()
        commands.subscription = "some-other-subscription"
        http = StatefulHttpRunner(commands)
        result = verifier.run_proof(
            config(mode="preflight"), command_runner=commands, http_runner=http
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertEqual(http.requests, [])

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
        self.assertEqual(commands.query_count, {"success": 3, "skip": 3})
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
                    self.assertEqual(result.evidence["classification"], "class_2g")
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
            manifest_path = Path(directory) / "manifest.json"
            evidence_path = Path(directory) / "evidence.json"
            manifest_path.write_text(__import__("json").dumps(deployment_manifest()), encoding="utf-8")
            exit_code = verifier.main(
                [
                    "--mode", "execute", "--target", "production-azure",
                    "--subscription", "sub-approved",
                    "--resource-group", "wealth-azure-prod-rg",
                    "--gateway-app", "api-gateway", "--portfolio-app", "portfolio-service",
                    "--workspace", "wealth-prod-la", "--registry", "wealthprodacr",
                    "--gateway-url", "https://wealth.example.test",
                    "--repository-sha", COMMIT, "--run-attempt", "17",
                    "--deployment-manifest", str(manifest_path),
                    "--gateway-repository", "wealthprodacr.azurecr.io/api-gateway",
                    "--portfolio-repository", "wealthprodacr.azurecr.io/portfolio-service",
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
    def test_authoritative_aggregator_digest_map_is_consumed_directly(self) -> None:
        import importlib.util

        script = REPO / ".github/workflows/scripts/snapshot_container_apps.py"
        spec = importlib.util.spec_from_file_location("task7_snapshot_container_apps", script)
        aggregator = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = aggregator
        spec.loader.exec_module(aggregator)
        with tempfile.TemporaryDirectory() as directory:
            for service, digest in (("api-gateway", DIGEST_A), ("portfolio-service", DIGEST_B)):
                service_dir = Path(directory) / service
                service_dir.mkdir()
                (service_dir / "digest.txt").write_text(digest, encoding="utf-8")
            artifact = aggregator.aggregate_digests(
                directory, ["api-gateway", "portfolio-service"], None
            )
        parsed = verifier.validate_deployment_manifest(
            artifact,
            expected_attempt="17",
            expected_repository_sha=COMMIT,
            repositories=service_repositories(),
        )
        self.assertEqual(parsed["runAttempt"], "17")
        self.assertEqual(parsed["repositorySha"], COMMIT)
        self.assertEqual(
            parsed["services"]["portfolio-service"]["repository"],
            "wealthprodacr.azurecr.io/portfolio-service",
        )

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
            diagnostics={"reproductions": [{"replicaToken": "different"}],
                         "applicationBlockingEvidence": True},
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

    def test_non_go_skip_invokes_and_records_injected_diagnostic_operation(self) -> None:
        commands = StatefulCommandRunner(event_mode="skip")
        http = StatefulHttpRunner(commands)
        clock = Clock()
        seen: list[tuple[str, float]] = []

        def diagnose(operation, context, *, timeout_seconds):
            seen.append((operation, timeout_seconds))
            self.assertEqual(context["event"]["reason"], "eligibility_connection_failure")
            return {"available": True}

        result = verifier.run_proof(
            config(), command_runner=commands, http_runner=http,
            diagnostic_runner=diagnose, now=clock.now, monotonic=clock.monotonic,
            sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertNotEqual(result.exit_code, 0)
        self.assertEqual(seen[0][0], "diagnose_eligibility_connection_failure")
        self.assertTrue(result.evidence["diagnostics"]["available"])
        self.assertEqual(result.evidence["diagnostics"]["operations"][0]["name"], seen[0][0])

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

    def test_every_runner_call_honors_the_configured_operation_timeout_cap(self) -> None:
        cfg = config()
        cfg.operation_timeout_seconds = 7.0
        result, commands, http, _clock = run_case(event_mode="success", cfg=cfg)
        self.assertEqual(result.exit_code, 0)
        self.assertTrue(commands.timeouts)
        self.assertTrue(http.timeouts)
        self.assertTrue(all(
            timeout is not None and 0 < timeout <= cfg.operation_timeout_seconds
            for timeout in commands.timeouts + http.timeouts
        ))

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

    def test_key_diagnosis_runs_explicit_template_token_manual_and_presence_operations(self) -> None:
        commands = StatefulCommandRunner(event_mode="skip")
        commands.skip_reason = "reset_key_not_configured"
        http = StatefulHttpRunner(commands)
        clock = Clock()
        seen: list[str] = []
        results = {
            "compare_revision_template": {"templateReference": "intact"},
            "recover_replica_token": {"replicaTokenRecovered": True},
            "manual_reset_probe": {
                "manualResetStatus": 503, "manualResetEmitter": "gateway", "sameReplica": True,
            },
            "presence_probe": {"presence": "blank"},
        }

        def diagnose(operation, context, *, timeout_seconds):
            seen.append(operation)
            return results[operation]

        result = verifier.run_proof(
            config(), command_runner=commands, http_runner=http,
            diagnostic_runner=diagnose, now=clock.now, monotonic=clock.monotonic,
            sleep=clock.sleep,
            trace_factory=lambda: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        )
        self.assertEqual(
            seen,
            ["compare_revision_template", "recover_replica_token", "manual_reset_probe",
             "presence_probe"],
        )
        self.assertEqual(result.evidence["classification"], "class_2h")

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
        self.assertEqual(result.evidence["decisions"]["serving"]["overallTimeout"], "4s")

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


if __name__ == "__main__":
    unittest.main()
