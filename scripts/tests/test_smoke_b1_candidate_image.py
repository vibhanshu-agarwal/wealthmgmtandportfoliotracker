#!/usr/bin/env python3
"""Regression corpus for the B1 R-C candidate image HTTP contract smoke.

The assertions are pure functions over recorded exchanges, so every acceptance rule is tested here
without Docker: a wrong status, a wrong error code, a missing or wrong `currentVersion`, a refreshed
`expectedVersion`, mutated persisted state, and an unauthorized registry run must each FAIL. The one
end-to-end test drives the real harness against a real image and is skipped when Docker or a staged
candidate image is unavailable -- it is never silently reported as a pass.

Run:  python -B -X utf8 -m unittest discover -s scripts/tests -p test_smoke_b1_candidate_image.py -v
"""

from __future__ import annotations

import contextlib
import copy
import json
import subprocess
import sys
import unittest
from decimal import Decimal
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))

import smoke_b1_candidate_image as smoke  # noqa: E402
from b1_candidate_evidence import EvidenceError  # noqa: E402

LOCAL_ID = "sha256:" + "a" * 64
REGISTRY = "wealthprodacr.azurecr.io/portfolio-service@sha256:" + "b" * 64


def exchange(status: int, body, headers: dict | None = None, method: str = "GET",
             url: str = "http://127.0.0.1:1/x", request_body=None) -> dict:
    text = "" if body is None else smoke.json_dumps(body)
    return {"method": method, "url": url, "status": status, "headers": headers or {},
            "body_text": text, "body": body, "body_sha256": smoke._digest_text(text),
            "request_body": request_body}


ASSETS_OK = exchange(200, {"catalogVersion": "v9", "assets": [
    {"ticker": "AAPL"}, {"ticker": "TSLA"}, {"ticker": "BTCUSD"}]}, {"ETag": '"v9"'})

ACR_DIGEST = smoke.APPROVED_RELEASE_REPOSITORY + "@sha256:" + "b" * 64
JAR_BYTES = b"candidate-jar-bytes"
JAR_SHA = __import__("hashlib").sha256(JAR_BYTES).hexdigest()

def pg_row_json(row: dict) -> str:
    """Serialize a row the way PostgreSQL's `row_to_json` does: NUMERIC columns are **bare JSON
    numbers**, not quoted strings. Quoting them in a fixture would bypass the decoding boundary
    where precision is actually at risk."""
    parts = []
    for key, value in row.items():
        rendered = str(value) if isinstance(value, Decimal) else json.dumps(value)
        parts.append(json.dumps(key) + ": " + rendered)
    return "{" + ", ".join(parts) + "}"


#: The rows a correct run persists: complete parent and child rows, exactly as `row_to_json` emits
#: them, including the identities, timestamps and cost-basis columns a projection would have dropped.
#: `avg_cost_basis` carries a value at the full width of `NUMERIC(19,4)` (V11), because that is where
#: a float round-trip loses digits.
PARENT_ROW = {"id": "217d4204-4919-4068-8b7f-065f622e3162",
              "user_id": smoke.SMOKE_USER_ID, "version": 1,
              "created_at": "2026-09-04T04:11:32.000258", "updated_at": "2026-09-04T04:11:32.026520"}
HOLDING_ROWS = [
    {"id": "7bc105a4-5ed1-4886-b606-167ef032467d", "portfolio_id": PARENT_ROW["id"],
     "asset_ticker": "AAPL", "quantity": Decimal("1.50000000"),
     "avg_cost_basis": Decimal("999999999999999.0000"),
     "cost_basis_source": "SEEDED", "cost_basis_as_of": "2020-01-01T00:00:00"},
    {"id": "9cd21500-1111-4886-b606-167ef0324680", "portfolio_id": PARENT_ROW["id"],
     "asset_ticker": "TSLA", "quantity": Decimal("2.00000000"),
     "avg_cost_basis": Decimal("200.0000"),
     "cost_basis_source": "SEEDED", "cost_basis_as_of": "2020-01-01T00:00:00"},
]
SUBMITTED = {"AAPL": "1.5", "TSLA": "2"}

#: The orchestration submits one holding per catalog ticker (ASSETS_OK offers three), so the
#: end-to-end fixtures carry three rows while the pure-assertion fixtures above carry two.
E2E_HOLDING_ROWS = HOLDING_ROWS + [
    {"id": "aa332211-2222-4886-b606-167ef0324681", "portfolio_id": PARENT_ROW["id"],
     "asset_ticker": "BTCUSD", "quantity": Decimal("0.25000000"),
     "avg_cost_basis": Decimal("30000.0000"),
     "cost_basis_source": "SEEDED", "cost_basis_as_of": "2020-01-01T00:00:00"}]
E2E_SUBMITTED = {"AAPL": "1.5", "TSLA": "2", "BTCUSD": "0.25"}


class FakeEnvironment:
    """Mocked Docker + HTTP + psql, with the harness's real assertions and control flow intact.

    This is what lets a false PASS be a regression: every reviewer probe (wrong quantity in the
    response, wrong quantity persisted, a hidden column mutated across the rejected write, a leaked
    container, `--keep` in release mode) is expressed by tweaking one field here and asserting the
    run FAILS."""

    def __init__(self, *, image_id=LOCAL_ID, platform="linux/amd64", jar=JAR_BYTES,
                 compose_body=None, conflict_body=None, parent=None, holdings=None,
                 holdings_after=None, parent_after=None, manifest=None, start_fails=False):
        self.image_id = image_id
        self.platform = platform
        self.jar = jar
        self.compose_body = compose_body if compose_body is not None else {
            "id": PARENT_ROW["id"], "userId": smoke.SMOKE_USER_ID, "version": 1,
            # The real API serializes quantity as a plain decimal STRING (ToPlainStringSerializer),
            # while PostgreSQL emits the column as a bare JSON number; both boundaries are exercised.
            "holdings": [{"id": h["id"], "assetTicker": h["asset_ticker"],
                          "quantity": str(h["quantity"])} for h in E2E_HOLDING_ROWS]}
        self.conflict_body = conflict_body if conflict_body is not None else {
            "error": "portfolio_version_conflict",
            "message": "portfolio_version_conflict: currentVersion=1", "currentVersion": 1}
        self.parent = PARENT_ROW if parent is None else parent
        self.holdings = E2E_HOLDING_ROWS if holdings is None else holdings
        self.parent_after = self.parent if parent_after is None else parent_after
        self.holdings_after = self.holdings if holdings_after is None else holdings_after
        self.manifest = manifest
        self.start_fails = start_fails
        self.commands: list[list[str]] = []
        self._db_reads = 0

    # -- docker ---------------------------------------------------------------------------
    def run_cmd(self, cmd, timeout=None):
        self.commands.append(cmd)
        joined = " ".join(cmd)
        if cmd[:2] == ["docker", "create"]:
            return subprocess.CompletedProcess(cmd, 0, "cid-" + cmd[cmd.index("--name") + 1], "")
        if cmd[:2] == ["docker", "start"] and self.start_fails:
            raise EvidenceError("command failed: exit 127 (no such executable)")
        if "manifest" in joined and "inspect" in joined:
            return subprocess.CompletedProcess(cmd, 0, json.dumps(self.manifest or {}), "")
        if cmd[:3] == ["docker", "inspect", "-f"]:
            return subprocess.CompletedProcess(cmd, 0, "java", "")
        return subprocess.CompletedProcess(cmd, 0, "", "")

    def image_field(self, ref, fmt):
        if ".Id" in fmt:
            return self.image_id
        if ".Os" in fmt:
            return self.platform
        if "Entrypoint" in fmt:
            return json.dumps(["java", "-jar", "/app.jar"])
        if "RepoDigests" in fmt:
            return "[]"
        return ""

    def extract(self, image_ref, container_path, out_path):
        Path(out_path).write_bytes(self.jar)

    # -- http -----------------------------------------------------------------------------
    def http(self, url, method="GET", body=None, headers=None, timeout=None):
        if url.endswith("/api/assets"):
            return ASSETS_OK
        if url.endswith("/actuator/health"):
            return exchange(200, {"status": "UP"})
        if url.endswith("/api/portfolio/holdings"):
            self._writes = getattr(self, "_writes", 0) + 1
            self.last_request_body = body
            if self._writes == 1:  # the accepted composition
                return exchange(201, self.compose_body, method="PUT", request_body=body)
            return exchange(409, self.conflict_body, method="PUT", request_body=body)
        raise AssertionError("unexpected URL " + url)

    # -- psql -----------------------------------------------------------------------------
    def exec(self, container, args, timeout=30):
        sql = args[-1] if args else ""
        if "row_to_json(p)" in sql:
            rows = [self.parent] if self._db_reads == 0 else [self.parent_after]
            return subprocess.CompletedProcess(args, 0, "\n".join(pg_row_json(r) for r in rows if r), "")
        if "row_to_json(h)" in sql:
            rows = self.holdings if self._db_reads == 0 else self.holdings_after
            self._db_reads += 1
            return subprocess.CompletedProcess(args, 0, "\n".join(pg_row_json(r) for r in rows), "")
        return subprocess.CompletedProcess(args, 0, "PONG accepting connections", "")

    def install(self, stack):
        stack.enter_context(mock.patch.object(smoke, "_run", side_effect=self.run_cmd))
        stack.enter_context(mock.patch.object(smoke, "docker_image_field", side_effect=self.image_field))
        stack.enter_context(mock.patch.object(smoke, "extract_file", side_effect=self.extract))
        stack.enter_context(mock.patch.object(smoke, "http_request", side_effect=self.http))
        stack.enter_context(mock.patch.object(smoke.SmokeRun, "exec", autospec=True,
                                              side_effect=lambda s, c, a, timeout=30: self.exec(c, a)))
        stack.enter_context(mock.patch.object(smoke.SmokeRun, "is_running", return_value=True))
        stack.enter_context(mock.patch.object(smoke.SmokeRun, "host_port", return_value=18080))
        stack.enter_context(mock.patch.object(smoke.SmokeRun, "logs", return_value=""))
        return self


def mocked_smoke(env: FakeEnvironment, *, label=smoke.LOCAL_PREPARATION, reference=None,
                 expect_jar=None, expect_platform=None, keep=False) -> dict:
    """Drive the real orchestration with mocked external I/O. The extraction workdir is redirected
    outside the repository so a mocked run -- including a `keep=True` one, which deliberately skips
    cleanup -- never leaves anything in `.candidate-artifacts/`."""
    with contextlib.ExitStack() as stack:
        tmp = stack.enter_context(TemporaryDirectory())
        stack.enter_context(mock.patch.object(smoke, "SMOKE_WORKDIR_BASE", Path(tmp) / "smoke-tmp"))
        env.install(stack)
        return smoke.run_smoke(reference or env.image_id, label, postgres_image="p",
                               redis_image="r", kafka_image="k", expect_jar_sha256=expect_jar,
                               startup_deadline=5, keep=keep, expect_platform=expect_platform)


class ImageReferenceTests(unittest.TestCase):
    """Only immutable identities are accepted, and the registry run is gated."""

    def test_local_image_id_is_local_preparation(self):
        self.assertEqual(smoke.resolve_image_reference(LOCAL_ID, None, False),
                         (LOCAL_ID, smoke.LOCAL_PREPARATION))

    def test_authorized_registry_digest_with_its_identity_contract_is_a_release_run(self):
        self.assertEqual(
            smoke.resolve_image_reference(None, REGISTRY, True, expect_jar_sha256=JAR_SHA,
                                          expect_platform="linux/amd64"),
            (REGISTRY, smoke.RELEASE_DIGEST))

    def test_a_release_run_must_state_its_expectations_before_pulling(self):
        for kwargs, needle in (
                ({}, "--expect-jar-sha256"),
                ({"expect_jar_sha256": JAR_SHA}, "--expect-platform"),
                ({"expect_platform": "linux/amd64"}, "--expect-jar-sha256"),
                ({"expect_jar_sha256": "not-a-digest", "expect_platform": "linux/amd64"}, "sha256"),
                ({"expect_jar_sha256": JAR_SHA, "expect_platform": "amd64"}, "os/arch"),
                ({"expect_jar_sha256": JAR_SHA, "expect_platform": "linux/amd64", "keep": True},
                 "--keep")):
            with self.subTest(kwargs=sorted(kwargs)):
                with self.assertRaises(EvidenceError) as ctx:
                    smoke.resolve_image_reference(None, REGISTRY, True, **kwargs)
                self.assertIn(needle, str(ctx.exception))

    def test_a_release_digest_in_a_foreign_repository_is_refused(self):
        # Immutable, and still the wrong artifact.
        for repository in ("docker.io/unrelated/app", "ghcr.io/someone/portfolio-service",
                           "wealthprodacr.azurecr.io/other-service"):
            with self.subTest(repository=repository):
                with self.assertRaises(EvidenceError) as ctx:
                    smoke.resolve_image_reference(None, repository + "@sha256:" + "b" * 64, True,
                                                  expect_jar_sha256=JAR_SHA,
                                                  expect_platform="linux/amd64")
                self.assertIn("approved release repository", str(ctx.exception))

    def test_local_runs_keep_their_looser_contract(self):
        # Development feedback may omit the artifact expectation and may retain resources; the
        # evidence says so (see OrchestrationFalsePassTests).
        self.assertEqual(smoke.resolve_image_reference(LOCAL_ID, None, False, keep=True),
                         (LOCAL_ID, smoke.LOCAL_PREPARATION))

    def test_registry_digest_without_authorization_is_refused(self):
        with self.assertRaises(EvidenceError) as ctx:
            smoke.resolve_image_reference(None, REGISTRY, False)
        self.assertIn("owner authorization", str(ctx.exception))

    def test_mutable_tags_are_refused_in_both_modes(self):
        for local, registry in (("wealth-portfolio-service:candidate-local-dev", None),
                                ("portfolio-service:latest", None),
                                (None, "wealthprodacr.azurecr.io/portfolio-service:r-c"),
                                (None, "wealthprodacr.azurecr.io/portfolio-service:r-c@sha256:" + "b" * 64),
                                (None, "sha256:" + "b" * 64)):
            with self.subTest(local=local, registry=registry):
                with self.assertRaises(EvidenceError):
                    smoke.resolve_image_reference(local, registry, True)

    def test_malformed_digests_are_refused(self):
        for local in ("sha256:" + "a" * 63, "sha256:" + "A" * 64, "", "sha256:zz"):
            with self.subTest(local=local):
                with self.assertRaises(EvidenceError):
                    smoke.resolve_image_reference(local, None, False)

    def test_exactly_one_reference_is_required(self):
        for local, registry in ((None, None), (LOCAL_ID, REGISTRY)):
            with self.subTest(local=local, registry=registry):
                with self.assertRaises(EvidenceError):
                    smoke.resolve_image_reference(local, registry, True)

    def test_cli_refuses_before_touching_docker(self):
        with mock.patch.object(smoke, "run_smoke") as run_smoke:
            self.assertEqual(smoke.main(["--registry-digest", REGISTRY]), 1)
            run_smoke.assert_not_called()


class AssetsAssertionTests(unittest.TestCase):
    def test_catalog_positive(self):
        result = smoke.assert_assets(ASSETS_OK)
        self.assertTrue(result["passed"], result["problems"])
        self.assertEqual(result["asset_count"], 3)
        self.assertEqual(result["tickers_sample"][:2], ["AAPL", "TSLA"])

    def test_non_200_fails(self):
        self.assertFalse(smoke.assert_assets(exchange(503, None))["passed"])

    def test_empty_catalog_fails(self):
        result = smoke.assert_assets(exchange(200, {"catalogVersion": "v9", "assets": []}, {"ETag": '"v9"'}))
        self.assertFalse(result["passed"])
        self.assertTrue(any("catalog is empty" in p for p in result["problems"]))

    def test_missing_etag_or_version_fails(self):
        self.assertFalse(smoke.assert_assets(
            exchange(200, {"catalogVersion": "v9", "assets": [{"ticker": "AAPL"}]}))["passed"])
        self.assertFalse(smoke.assert_assets(
            exchange(200, {"assets": [{"ticker": "AAPL"}]}, {"ETag": '"v9"'}))["passed"])


class CompositionAssertionTests(unittest.TestCase):
    BODY = {"version": 1, "holdings": [{"assetTicker": "AAPL", "quantity": "1.5"},
                                       {"assetTicker": "TSLA", "quantity": "2"}]}

    def test_accepted_composition_positive(self):
        for status in (200, 201):
            with self.subTest(status=status):
                result = smoke.assert_composition(exchange(status, self.BODY), 0, SUBMITTED)
                self.assertTrue(result["passed"], result["problems"])
                self.assertEqual(result["observed_version"], 1)

    def test_equivalent_decimal_representations_are_the_same_quantity(self):
        body = {"version": 1, "holdings": [{"assetTicker": "AAPL", "quantity": "1.50000000"},
                                           {"assetTicker": "TSLA", "quantity": "2.0000"}]}
        self.assertTrue(smoke.assert_composition(exchange(200, body), 0, SUBMITTED)["passed"])

    def test_a_wrong_quantity_fails(self):
        # The reviewer's control: submitted AAPL=1.5, response says 999.
        body = {"version": 1, "holdings": [{"assetTicker": "AAPL", "quantity": "999"},
                                           {"assetTicker": "TSLA", "quantity": "2"}]}
        result = smoke.assert_composition(exchange(200, body), 0, SUBMITTED)
        self.assertFalse(result["passed"])
        self.assertTrue(any("quantity for AAPL" in p for p in result["problems"]), result["problems"])

    def test_missing_extra_or_unparseable_quantities_fail(self):
        cases = {
            "missing holding": [{"assetTicker": "AAPL", "quantity": "1.5"}],
            "extra holding": [{"assetTicker": "AAPL", "quantity": "1.5"},
                              {"assetTicker": "TSLA", "quantity": "2"},
                              {"assetTicker": "NVDA", "quantity": "3"}],
            "null quantity": [{"assetTicker": "AAPL", "quantity": None},
                              {"assetTicker": "TSLA", "quantity": "2"}],
            "non-decimal quantity": [{"assetTicker": "AAPL", "quantity": "lots"},
                                     {"assetTicker": "TSLA", "quantity": "2"}],
            "wrong ticker": [{"assetTicker": "AAPL", "quantity": "1.5"},
                             {"assetTicker": "NVDA", "quantity": "2"}],
        }
        for name, holdings in cases.items():
            with self.subTest(case=name):
                result = smoke.assert_composition(
                    exchange(200, {"version": 1, "holdings": holdings}), 0, SUBMITTED)
                self.assertFalse(result["passed"], name)

    def test_version_that_does_not_advance_fails(self):
        result = smoke.assert_composition(exchange(200, dict(self.BODY, version=0)), 0, SUBMITTED)
        self.assertFalse(result["passed"])
        self.assertTrue(any("did not advance" in p for p in result["problems"]))

    def test_error_status_fails(self):
        self.assertFalse(smoke.assert_composition(exchange(409, {"error": "portfolio_version_conflict"}),
                                                  0, SUBMITTED)["passed"])


class ConflictAssertionTests(unittest.TestCase):
    CONFLICT = {"error": "portfolio_version_conflict",
                "message": "portfolio_version_conflict: currentVersion=1", "currentVersion": 1}

    def test_exact_envelope_positive(self):
        result = smoke.assert_conflict(exchange(409, self.CONFLICT), 0, 1)
        self.assertTrue(result["passed"], result["problems"])

    def test_wrong_status_fails(self):
        self.assertFalse(smoke.assert_conflict(exchange(200, self.CONFLICT), 0, 1)["passed"])

    def test_wrong_error_code_fails(self):
        for code in ("invalid_version", "malformed_request", None):
            with self.subTest(code=code):
                result = smoke.assert_conflict(exchange(409, dict(self.CONFLICT, error=code)), 0, 1)
                self.assertFalse(result["passed"])
                self.assertTrue(any("error code" in p for p in result["problems"]))

    def test_wrong_or_missing_current_version_fails(self):
        for body in (dict(self.CONFLICT, currentVersion=7),
                     {"error": "portfolio_version_conflict", "message": "x"}):
            with self.subTest(body=body):
                result = smoke.assert_conflict(exchange(409, body), 0, 1)
                self.assertFalse(result["passed"])
                self.assertTrue(any("currentVersion" in p for p in result["problems"]))

    def test_a_refreshed_expected_version_is_not_a_conflict_test(self):
        # Guards the "do not retry by refreshing expectedVersion" rule: replaying the CURRENT version
        # is not a stale replay, and the harness must call that a fixture error rather than a pass.
        result = smoke.assert_conflict(exchange(409, self.CONFLICT), 1, 1)
        self.assertFalse(result["passed"])
        self.assertTrue(any("not stale" in p for p in result["problems"]))


class PersistedStateAssertionTests(unittest.TestCase):
    BEFORE = {"portfolio": PARENT_ROW, "holdings": HOLDING_ROWS, "holdings_count": 2}

    def _after(self, **changes) -> dict:
        # deepcopy, not a JSON round-trip: the fixtures carry Decimal values on purpose.
        state = copy.deepcopy(self.BEFORE)
        for key, value in changes.items():
            state[key] = value
        return state

    def test_unchanged_state_positive(self):
        result = smoke.assert_state_unchanged(self.BEFORE, self._after(), 1, SUBMITTED)
        self.assertTrue(result["passed"], result["problems"])

    def test_a_change_to_any_column_fails(self):
        """The columns a hand-picked projection would have dropped: parent timestamps, holding
        identities and every cost-basis field. Each must be caught."""
        cases = {}
        for column, value in (("updated_at", "2026-09-04T05:00:00"),
                              ("created_at", "2026-09-04T05:00:00"),
                              ("version", 2)):
            cases["parent." + column] = self._after(portfolio=dict(PARENT_ROW, **{column: value}))
        for column, value in (("id", "ffffffff-0000-0000-0000-000000000000"),
                              ("quantity", "999.00000000"),
                              ("avg_cost_basis", "1.00000000"),
                              ("cost_basis_source", "REWRITTEN"),
                              ("cost_basis_as_of", "2026-09-04T00:00:00"),
                              ("portfolio_id", "ffffffff-0000-0000-0000-000000000000")):
            mutated = [dict(HOLDING_ROWS[0], **{column: value}), HOLDING_ROWS[1]]
            cases["holding." + column] = self._after(holdings=mutated)
        cases["holding removed"] = self._after(holdings=[HOLDING_ROWS[0]], holdings_count=1)
        for name, after in cases.items():
            with self.subTest(case=name):
                result = smoke.assert_state_unchanged(self.BEFORE, after, 1, SUBMITTED)
                self.assertFalse(result["passed"], name)
                self.assertTrue(any("changed across the rejected write" in p or "quantity" in p
                                    for p in result["problems"]), (name, result["problems"]))

    def test_two_empty_reads_are_not_an_unchanged_state(self):
        # The vacuous pass: a mis-targeted query returns nothing twice, and "before == after" holds.
        empty = {"portfolio": {}, "holdings": [], "holdings_count": 0}
        result = smoke.assert_state_unchanged(empty, dict(empty), 1, SUBMITTED)
        self.assertFalse(result["passed"])
        self.assertTrue(any("vacuous" in p for p in result["problems"]), result["problems"])

    def test_state_must_be_the_state_the_accepted_write_reported(self):
        wrong_version = smoke.assert_state_unchanged(self.BEFORE, self._after(), 7, SUBMITTED)
        self.assertFalse(wrong_version["passed"])
        self.assertTrue(any("not the " in p and "version" in p for p in wrong_version["problems"]))
        wrong_tickers = smoke.assert_state_unchanged(self.BEFORE, self._after(), 1,
                                                     {"AAPL": "1.5", "NVDA": "2"})
        self.assertFalse(wrong_tickers["passed"])

    def test_persisted_quantities_must_be_the_submitted_quantities(self):
        # Codex's control: the write really persisted 999, then "unchanged" holds trivially.
        wrong = [dict(HOLDING_ROWS[0], quantity="999.00000000"), HOLDING_ROWS[1]]
        state = {"portfolio": PARENT_ROW, "holdings": wrong, "holdings_count": 2}
        result = smoke.assert_state_unchanged(state, copy.deepcopy(state), 1, SUBMITTED)
        self.assertFalse(result["passed"])
        self.assertTrue(any("persisted state quantity for AAPL" in p for p in result["problems"]),
                        result["problems"])

    def test_equivalent_decimal_scale_is_accepted(self):
        self.assertTrue(smoke.assert_state_unchanged(
            self.BEFORE, self._after(), 1, {"AAPL": "1.5000", "TSLA": "2.0"})["passed"])


class StartupAssertionTests(unittest.TestCase):
    ARGS = dict(image_id=LOCAL_ID, requested_reference=LOCAL_ID, label=smoke.LOCAL_PREPARATION,
                platform="linux/amd64", entrypoint=["java", "-jar", "/app.jar"],
                container_path="java", startup_seconds=9.2, deadline_s=240.0,
                health={"status": 200, "body": {"status": "UP"}})

    def test_startup_positive(self):
        result = smoke.assert_startup(**self.ARGS)
        self.assertTrue(result["passed"], result["problems"])

    def test_a_substituted_image_fails(self):
        result = smoke.assert_startup(**dict(self.ARGS, image_id="sha256:" + "c" * 64))
        self.assertFalse(result["passed"])
        self.assertTrue(any("is not the requested" in p for p in result["problems"]))

    def test_an_overridden_entrypoint_fails(self):
        # The whole point of running the packaged artifact: if the container was launched with a
        # replacement command, the run proves nothing about the shipped entrypoint.
        result = smoke.assert_startup(**dict(self.ARGS, container_path="/bin/sh"))
        self.assertFalse(result["passed"])
        self.assertTrue(any("not the shipped entrypoint" in p for p in result["problems"]))

    def test_missing_entrypoint_or_platform_fails(self):
        self.assertFalse(smoke.assert_startup(**dict(self.ARGS, entrypoint=[]))["passed"])
        self.assertFalse(smoke.assert_startup(**dict(self.ARGS, platform=""))["passed"])

    def test_startup_beyond_the_deadline_fails(self):
        result = smoke.assert_startup(**dict(self.ARGS, startup_seconds=999.0, deadline_s=240.0))
        self.assertFalse(result["passed"])
        self.assertTrue(any("deadline" in p for p in result["problems"]))

    def test_release_run_may_report_a_registry_reference(self):
        result = smoke.assert_startup(**dict(self.ARGS, label=smoke.RELEASE_DIGEST,
                                             requested_reference=REGISTRY,
                                             expect_platform="linux/amd64"))
        self.assertTrue(result["passed"], result["problems"])

    def test_a_release_run_without_an_expected_platform_fails(self):
        result = smoke.assert_startup(**dict(self.ARGS, label=smoke.RELEASE_DIGEST,
                                             requested_reference=REGISTRY))
        self.assertFalse(result["passed"])
        self.assertTrue(any("expected deployment platform" in p for p in result["problems"]))

    def test_a_platform_other_than_the_expected_one_fails(self):
        result = smoke.assert_startup(**dict(self.ARGS, platform="linux/arm64",
                                             expect_platform="linux/amd64"))
        self.assertFalse(result["passed"])
        self.assertTrue(any("linux/arm64" in p for p in result["problems"]))


class CleanupTests(unittest.TestCase):
    """Cleanup removes only this run's resources, and a cleanup failure is never absorbed."""

    def test_only_owned_resources_are_removed(self):
        run = smoke.SmokeRun(run_id="deadbeef")
        run._containers = ["cid-1", "cid-2"]
        run._network_created = True
        calls = []

        def fake_run(cmd, **kwargs):
            calls.append(cmd)
            return subprocess.CompletedProcess(cmd, 0, "", "")

        with mock.patch.object(subprocess, "run", side_effect=fake_run):
            self.assertEqual(run.cleanup(), [])
        removed = [c[-1] for c in calls]
        self.assertEqual(removed, ["cid-2", "cid-1", "b1smoke-deadbeef-net"])
        self.assertTrue(all("prune" not in " ".join(c) for c in calls), calls)

    def test_missing_resources_are_tolerated_but_real_errors_are_reported(self):
        run = smoke.SmokeRun(run_id="deadbeef")
        run._containers = ["gone", "busy"]
        run._network_created = False

        def fake_run(cmd, **kwargs):
            if cmd[-1] == "gone":
                return subprocess.CompletedProcess(cmd, 1, "", "Error: No such container: gone")
            return subprocess.CompletedProcess(cmd, 1, "", "Error: container busy is in use")

        with mock.patch.object(subprocess, "run", side_effect=fake_run):
            errors = run.cleanup()
        self.assertEqual(len(errors), 1)
        self.assertIn("busy", errors[0])

    def test_keep_flag_removes_nothing(self):
        run = smoke.SmokeRun(run_id="deadbeef", keep=True)
        run._containers = ["cid"]
        with mock.patch.object(subprocess, "run", side_effect=AssertionError("must not run")):
            self.assertEqual(run.cleanup(), [])

    def test_cleanup_failure_downgrades_an_OTHERWISE_PASSING_run(self):
        # A genuine PASS -> FAIL downgrade: all five assertions pass, then cleanup fails. A run whose
        # resources leaked is a FAILURE because the next run's environment is no longer known-clean.
        env = FakeEnvironment()
        with mock.patch.object(smoke.SmokeRun, "cleanup", return_value=["could not remove container abc"]):
            evidence = mocked_smoke(env, expect_jar=JAR_SHA)
        self.assertEqual([a["id"] for a in evidence["assertions"]], ["A1", "A2", "A3", "A4", "A4-db"])
        self.assertTrue(all(a["passed"] for a in evidence["assertions"]), evidence["assertions"])
        self.assertEqual(evidence["status"], "FAIL")
        self.assertIn("cleanup failed", evidence["error"])
        self.assertEqual(evidence["cleanup_errors"], ["could not remove container abc"])
        self.assertFalse(evidence["cleanup_verified"],
                         "a run whose cleanup failed must not also report cleanup as verified")

    def test_a_workdir_cleanup_failure_also_clears_the_verified_flag(self):
        # The workdir error is appended AFTER the container/network errors, so a flag computed any
        # earlier would report success over it.
        def explode(self, *args, **kwargs):
            raise OSError("directory busy")

        with mock.patch.object(Path, "rmdir", explode):
            evidence = mocked_smoke(FakeEnvironment(), expect_jar=JAR_SHA)
        self.assertTrue(any("could not remove" in e for e in evidence["cleanup_errors"]),
                        evidence["cleanup_errors"])
        self.assertTrue(evidence["cleanup_errors"], "the workdir failure must be recorded")
        self.assertFalse(evidence["cleanup_verified"])
        self.assertEqual(evidence["status"], "FAIL")

    def test_a_clean_run_reports_cleanup_verified(self):
        evidence = mocked_smoke(FakeEnvironment(), expect_jar=JAR_SHA)
        self.assertEqual(evidence["status"], "PASS", evidence.get("error"))
        self.assertTrue(evidence["cleanup_verified"])
        self.assertEqual(evidence["cleanup_errors"], [])


class FailClosedTests(unittest.TestCase):
    def test_a_docker_failure_is_a_failure_not_a_pass(self):
        with mock.patch.object(smoke, "SmokeRun") as run_cls, \
             mock.patch.object(smoke, "docker_image_field", side_effect=EvidenceError("no such image")):
            run_cls.return_value.run_id = "deadbeef"
            run_cls.return_value.cleanup.return_value = []
            evidence = smoke.run_smoke(LOCAL_ID, smoke.LOCAL_PREPARATION, postgres_image="p",
                                       redis_image="r", kafka_image="k", expect_jar_sha256=None,
                                       startup_deadline=1)
        self.assertEqual(evidence["status"], "FAIL")
        self.assertIn("no such image", evidence["error"])
        self.assertFalse(evidence["candidate_ready"])

    def test_evidence_is_never_candidate_ready(self):
        # `_run` is mocked too, so the RELEASE subcase cannot reach a real `docker pull`.
        with mock.patch.object(smoke, "SmokeRun") as run_cls, \
             mock.patch.object(smoke, "_run", side_effect=EvidenceError("registry access blocked in tests")), \
             mock.patch.object(smoke, "docker_image_field", side_effect=EvidenceError("x")):
            run_cls.return_value.run_id = "d"
            run_cls.return_value.cleanup.return_value = []
            run_cls.return_value.retained.return_value = []
            for label in (smoke.LOCAL_PREPARATION, smoke.RELEASE_DIGEST):
                with self.subTest(label=label):
                    evidence = smoke.run_smoke(LOCAL_ID, label, postgres_image="p", redis_image="r",
                                               kafka_image="k", expect_jar_sha256=JAR_SHA,
                                               startup_deadline=1, expect_platform="linux/amd64")
                    self.assertFalse(evidence["candidate_ready"])
                    self.assertTrue(evidence["candidate_ready_blocked_by"])
                    self.assertEqual(evidence["status"], "FAIL")

    def test_wait_until_has_a_deadline(self):
        run = smoke.SmokeRun(run_id="deadbeef")
        with self.assertRaises(EvidenceError) as ctx:
            run.wait_until("something that never happens", lambda: False, 0.3, interval=0.1)
        self.assertIn("timed out", str(ctx.exception))

    def test_environment_differences_are_recorded(self):
        # The list is evidence, not decoration: a reader must be able to see every way this run
        # differs from production.
        self.assertTrue(len(smoke.ENVIRONMENT_DIFFERENCES) >= 6)
        joined = " ".join(smoke.ENVIRONMENT_DIFFERENCES).lower()
        for topic in ("postgresql", "kafka", "redis", "gateway", "synthetic"):
            self.assertIn(topic, joined)


class OrchestrationFalsePassTests(unittest.TestCase):
    """The reviewer's end-to-end false-PASS controls, as regressions. Each mutates ONE thing in an
    otherwise-passing mocked run and requires the overall result to be FAIL."""

    def test_correct_run_is_the_positive_control(self):
        evidence = mocked_smoke(FakeEnvironment(), expect_jar=JAR_SHA, expect_platform="linux/amd64")
        self.assertEqual(evidence["status"], "PASS", evidence.get("error"))
        self.assertEqual([a["id"] for a in evidence["assertions"]], ["A1", "A2", "A3", "A4", "A4-db"])
        self.assertTrue(evidence["cleanup_verified"])
        self.assertEqual(evidence["retained_resources"], [])

    def test_wrong_quantity_in_the_response_fails_the_run(self):
        body = {"version": 1, "holdings": [{"assetTicker": "AAPL", "quantity": "999"},
                                           {"assetTicker": "TSLA", "quantity": "2"},
                                           {"assetTicker": "BTCUSD", "quantity": "0.25"}]}
        evidence = mocked_smoke(FakeEnvironment(compose_body=body), expect_jar=JAR_SHA)
        self.assertEqual(evidence["status"], "FAIL")
        self.assertIn("A3", evidence["error"])

    def test_wrong_quantity_persisted_fails_the_run(self):
        # Response echoes the submitted numbers, but PostgreSQL holds 999 -- replaying that state
        # would otherwise "prove" the wrong state stayed unchanged.
        wrong = [dict(E2E_HOLDING_ROWS[0], quantity="999.00000000")] + E2E_HOLDING_ROWS[1:]
        evidence = mocked_smoke(FakeEnvironment(holdings=wrong, holdings_after=wrong),
                                expect_jar=JAR_SHA)
        self.assertEqual(evidence["status"], "FAIL")
        self.assertIn("A4", evidence["error"])

    def test_a_hidden_column_mutation_fails_the_run(self):
        for column, value in (("updated_at", "2026-09-04T09:00:00"),):
            with self.subTest(column=column):
                after = dict(PARENT_ROW, **{column: value})
                evidence = mocked_smoke(FakeEnvironment(parent_after=after), expect_jar=JAR_SHA)
                self.assertEqual(evidence["status"], "FAIL")
        rotated = ([dict(E2E_HOLDING_ROWS[0], id="ffffffff-0000-0000-0000-000000000000")]
                   + E2E_HOLDING_ROWS[1:])
        evidence = mocked_smoke(FakeEnvironment(holdings_after=rotated), expect_jar=JAR_SHA)
        self.assertEqual(evidence["status"], "FAIL")

    def test_a_wrong_conflict_envelope_fails_the_run(self):
        for body in ({"error": "invalid_version", "currentVersion": 1},
                     {"error": "portfolio_version_conflict", "currentVersion": 7},
                     {"error": "portfolio_version_conflict"}):
            with self.subTest(body=body):
                evidence = mocked_smoke(FakeEnvironment(conflict_body=body), expect_jar=JAR_SHA)
                self.assertEqual(evidence["status"], "FAIL")

    def test_unrelated_jar_bytes_fail_when_an_artifact_is_expected(self):
        evidence = mocked_smoke(FakeEnvironment(jar=b"unrelated-bytes"), expect_jar=JAR_SHA)
        self.assertEqual(evidence["status"], "FAIL")
        self.assertIn("not the expected", evidence["error"])

    def test_a_local_run_without_an_expected_artifact_says_which_join_is_unverified(self):
        evidence = mocked_smoke(FakeEnvironment(jar=b"anything"), expect_jar=None)
        self.assertEqual(evidence["status"], "PASS", evidence.get("error"))
        self.assertTrue(any("not compared to a verified artifact" in n
                            for n in evidence["unverified_joins"]), evidence.get("unverified_joins"))

    def test_wrong_platform_fails_the_run(self):
        evidence = mocked_smoke(FakeEnvironment(platform="linux/arm64"), expect_jar=JAR_SHA,
                                expect_platform="linux/amd64")
        self.assertEqual(evidence["status"], "FAIL")
        self.assertIn("linux/arm64", evidence["error"])

    def test_release_run_pins_the_platform_and_records_the_manifest(self):
        index = {"mediaType": "application/vnd.oci.image.index.v1+json", "manifests": [
            {"digest": "sha256:" + "1" * 64, "platform": {"os": "linux", "architecture": "amd64"}},
            {"digest": "sha256:" + "2" * 64, "platform": {"os": "linux", "architecture": "arm64"}}]}
        env = FakeEnvironment(manifest=index)
        evidence = mocked_smoke(env, label=smoke.RELEASE_DIGEST, reference=ACR_DIGEST,
                                expect_jar=JAR_SHA, expect_platform="linux/amd64")
        self.assertEqual(evidence["status"], "PASS", evidence.get("error"))
        self.assertEqual(evidence["registry_platform_manifest_digest"], "sha256:" + "1" * 64)
        self.assertTrue(evidence["registry_manifest"]["is_index"])
        pull = [c for c in env.commands if c[:2] == ["docker", "pull"]][0]
        self.assertIn("--platform", pull)
        self.assertEqual(pull[pull.index("--platform") + 1], "linux/amd64")

    def test_release_index_without_the_expected_platform_is_refused(self):
        index = {"mediaType": "application/vnd.oci.image.index.v1+json", "manifests": [
            {"digest": "sha256:" + "2" * 64, "platform": {"os": "linux", "architecture": "arm64"}}]}
        evidence = mocked_smoke(FakeEnvironment(manifest=index), label=smoke.RELEASE_DIGEST,
                                reference=ACR_DIGEST, expect_jar=JAR_SHA, expect_platform="linux/amd64")
        self.assertEqual(evidence["status"], "FAIL")
        self.assertIn("image INDEX", evidence["error"])

    def test_release_single_manifest_records_the_digest_itself(self):
        env = FakeEnvironment(manifest={"mediaType": "application/vnd.oci.image.manifest.v1+json",
                                        "config": {}})
        evidence = mocked_smoke(env, label=smoke.RELEASE_DIGEST, reference=ACR_DIGEST,
                                expect_jar=JAR_SHA, expect_platform="linux/amd64")
        self.assertEqual(evidence["status"], "PASS", evidence.get("error"))
        self.assertFalse(evidence["registry_manifest"]["is_index"])
        self.assertEqual(evidence["registry_platform_manifest_digest"], "sha256:" + "b" * 64)

    def test_keep_marks_the_run_as_unverified_cleanup_and_names_what_is_retained(self):
        evidence = mocked_smoke(FakeEnvironment(), expect_jar=JAR_SHA, keep=True)
        self.assertEqual(evidence["status"], "PASS", evidence.get("error"))
        self.assertFalse(evidence["cleanup_verified"])
        self.assertTrue(evidence["retained_resources"])
        self.assertTrue(any("cleanup was never verified" in n for n in evidence["unverified_joins"]))
        self.assertTrue(any("--keep" in b for b in evidence["candidate_ready_blocked_by"]))


class LosslessDecimalTests(unittest.TestCase):
    """Precision must survive both JSON decoding boundaries and the evidence file.

    `json.loads` turns a fractional JSON number into a binary float, so `999999999999999.0001` --
    a legal `NUMERIC(19,4)` value -- becomes `999999999999999.0` before any comparison sees it."""

    BIG_A = "999999999999999.0000"
    BIG_B = "999999999999999.0001"

    def test_plain_json_loads_would_lose_the_digits(self):
        # The defect being guarded against, stated as a fact about the standard decoder.
        self.assertEqual(json.loads(self.BIG_A), json.loads(self.BIG_B))
        self.assertNotEqual(smoke.json_loads(self.BIG_A), smoke.json_loads(self.BIG_B))
        self.assertEqual(smoke.json_loads(self.BIG_B), Decimal(self.BIG_B))

    def test_non_finite_values_are_refused(self):
        for text in ('{"x": NaN}', '{"x": Infinity}', '{"x": -Infinity}'):
            with self.subTest(text=text):
                with self.assertRaises(EvidenceError):
                    smoke.json_loads(text)
        self.assertIsNone(smoke._decimal(Decimal("NaN")))
        self.assertIsNone(smoke._decimal(1.5), "a float has already been rounded and is refused")

    def test_evidence_json_round_trips_losslessly(self):
        evidence = {"rows": [{"avg_cost_basis": Decimal(self.BIG_B), "quantity": Decimal("1.50000000")}]}
        written = smoke.json_dumps(evidence, indent=2)
        reread = smoke.json_loads(written)
        value = reread["rows"][0]["avg_cost_basis"]
        self.assertEqual(smoke._decimal(value), Decimal(self.BIG_B))
        self.assertEqual(smoke._decimal(reread["rows"][0]["quantity"]), Decimal("1.5"))

    def test_unserializable_values_are_refused_rather_than_stringified(self):
        with self.assertRaises(TypeError):
            smoke.json_dumps({"x": object()})

    def test_a_numeric_column_change_below_float_precision_fails_a4db(self):
        before = {"portfolio": PARENT_ROW, "holdings": HOLDING_ROWS, "holdings_count": 2}
        after_rows = [dict(HOLDING_ROWS[0], avg_cost_basis=Decimal(self.BIG_B)), HOLDING_ROWS[1]]
        after = {"portfolio": PARENT_ROW, "holdings": after_rows, "holdings_count": 2}
        result = smoke.assert_state_unchanged(before, after, 1, SUBMITTED)
        self.assertFalse(result["passed"])
        self.assertTrue(any("changed across the rejected write" in p for p in result["problems"]))

    def test_the_same_change_through_the_real_decoding_path_fails_the_run(self):
        # The reviewer's measurement, end to end: bare JSON numbers from `row_to_json`, one column
        # changed in the last digit of NUMERIC(19,4).
        after_rows = ([dict(E2E_HOLDING_ROWS[0], avg_cost_basis=Decimal(self.BIG_B))]
                      + E2E_HOLDING_ROWS[1:])
        evidence = mocked_smoke(FakeEnvironment(holdings_after=after_rows), expect_jar=JAR_SHA)
        self.assertEqual(evidence["status"], "FAIL")
        self.assertIn("A4", evidence["error"])

    def test_unchanged_numeric_rows_still_pass_through_the_real_decoding_path(self):
        evidence = mocked_smoke(FakeEnvironment(), expect_jar=JAR_SHA)
        self.assertEqual(evidence["status"], "PASS", evidence.get("error"))

    def test_http_quantities_are_compared_at_full_precision(self):
        """The HTTP boundary: `http_request` must not round the response body before A3 sees it."""
        def respond(raw: str):
            response = mock.MagicMock()
            response.read.return_value = raw.encode("utf-8")
            response.status = 200
            response.headers = {}
            ctx = mock.MagicMock()
            ctx.__enter__ = mock.Mock(return_value=response)
            ctx.__exit__ = mock.Mock(return_value=False)
            with mock.patch("urllib.request.urlopen", return_value=ctx):
                return smoke.http_request("http://127.0.0.1:1/api/portfolio/holdings")

        submitted = {"AAPL": "1.5"}
        rounded = respond('{"version": 1, "holdings": [{"assetTicker": "AAPL", '
                          '"quantity": 1.5000000000000001}]}')
        result = smoke.assert_composition(rounded, 0, submitted)
        self.assertFalse(result["passed"], "a quantity that differs below float precision must fail")
        self.assertTrue(any("quantity for AAPL" in p for p in result["problems"]))

        equivalent = respond('{"version": 1, "holdings": [{"assetTicker": "AAPL", "quantity": 1.5000}]}')
        self.assertTrue(smoke.assert_composition(equivalent, 0, submitted)["passed"])

    def test_the_cli_writes_and_rereads_evidence_losslessly(self):
        env = FakeEnvironment()
        with TemporaryDirectory() as tmp:
            out = Path(tmp) / "evidence.json"
            with contextlib.ExitStack() as stack:
                stack.enter_context(mock.patch.object(smoke, "SMOKE_WORKDIR_BASE", Path(tmp) / "wd"))
                env.install(stack)
                code = smoke.main(["--local-image", LOCAL_ID, "--expect-jar-sha256", JAR_SHA,
                                   "--out", str(out)])
            self.assertEqual(code, 0)
            reread = smoke.json_loads(out.read_text(encoding="utf-8"))
        a4db = next(a for a in reread["assertions"] if a["id"] == "A4-db")
        basis = a4db["before"]["holdings"][0]["avg_cost_basis"]
        self.assertEqual(smoke._decimal(basis), Decimal(self.BIG_A),
                         "the evidence file must carry the exact persisted digits")


class ContainerOwnershipTests(unittest.TestCase):
    """A container that is created and then fails to start is still ours to remove."""

    def test_a_failed_start_leaves_no_untracked_container(self):
        env = FakeEnvironment(start_fails=True)
        with contextlib.ExitStack() as stack:
            env.install(stack)
            run = smoke.SmokeRun(run_id="deadbeef")
            with self.assertRaises(EvidenceError):
                run.run_container("app", LOCAL_ID)
            self.assertEqual(run._containers, ["cid-b1smoke-deadbeef-app"],
                             "ownership must be recorded by `docker create`, before `docker start`")
        removed = []
        with mock.patch.object(subprocess, "run",
                               side_effect=lambda cmd, **kw: removed.append(cmd) or
                               subprocess.CompletedProcess(cmd, 0, "", "")):
            self.assertEqual(run.cleanup(), [])
        self.assertIn(["docker", "rm", "-f", "cid-b1smoke-deadbeef-app"], removed)

    def test_create_precedes_start_and_the_artifact_gets_no_entrypoint_override(self):
        env = FakeEnvironment()
        with contextlib.ExitStack() as stack:
            env.install(stack)
            run = smoke.SmokeRun(run_id="deadbeef")
            run.run_container("app", LOCAL_ID, env={"A": "b"}, platform="linux/amd64")
        kinds = [c[1] for c in env.commands if c[0] == "docker"]
        self.assertEqual(kinds, ["create", "start"])
        create = env.commands[0]
        self.assertNotIn("--entrypoint", create)
        self.assertIn("--platform", create)


def _docker_available() -> bool:
    try:
        subprocess.run(["docker", "info"], capture_output=True, check=True, timeout=15)
        return True
    except Exception:
        return False


CANDIDATE_IMAGE_ID = None
if _docker_available():
    _probe = subprocess.run(
        ["docker", "images", "--filter", "reference=wealth-portfolio-service:candidate-local-dev*",
         "--format", "{{.ID}}"], capture_output=True, text=True)
    _first = (_probe.stdout or "").strip().splitlines()
    if _first:
        _full = subprocess.run(["docker", "image", "inspect", _first[0], "--format", "{{.Id}}"],
                               capture_output=True, text=True)
        CANDIDATE_IMAGE_ID = (_full.stdout or "").strip() or None


class RealImageSmokeTests(unittest.TestCase):
    """The whole harness against a real candidate image. Slow (dependency + Spring Boot startup);
    skipped without Docker or a locally built candidate image, never faked."""

    @unittest.skipUnless(CANDIDATE_IMAGE_ID, "requires a locally built candidate image "
                                             "(scripts/verify_b1_candidate_image.py)")
    def test_end_to_end_contract_smoke_passes_and_cleans_up(self):
        evidence = smoke.run_smoke(CANDIDATE_IMAGE_ID, smoke.LOCAL_PREPARATION,
                                   postgres_image="postgres:18.4", redis_image="redis:7-alpine",
                                   kafka_image="confluentinc/cp-kafka:8.1.3",
                                   expect_jar_sha256=None, startup_deadline=300)
        self.assertEqual(evidence["status"], "PASS", evidence.get("error"))
        self.assertEqual(evidence["cleanup_errors"], [])
        self.assertEqual(evidence["local_image_id"], CANDIDATE_IMAGE_ID)
        ids = [a["id"] for a in evidence["assertions"] if a["passed"]]
        self.assertEqual(ids, ["A1", "A2", "A3", "A4", "A4-db"])
        self.assertFalse(evidence["candidate_ready"])
        conflict = next(a for a in evidence["assertions"] if a["id"] == "A4")
        self.assertEqual(conflict["exchange"]["body"]["error"], "portfolio_version_conflict")
        # ...and nothing this run created is left behind.
        leftovers = subprocess.run(["docker", "ps", "-a", "--filter",
                                    "name=b1smoke-" + evidence["run_id"], "--format", "{{.Names}}"],
                                   capture_output=True, text=True).stdout.strip()
        self.assertEqual(leftovers, "")


if __name__ == "__main__":
    unittest.main()
