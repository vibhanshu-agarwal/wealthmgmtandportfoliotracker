#!/usr/bin/env python3
"""
Adversarial tests for spec_a_9_10_template.py.

Each test asserts that build() or verify() rejects a specific forbidden variant,
plus a golden test that confirms the exact two-path diff and the PASS code path.
"""
from __future__ import annotations

import contextlib
import copy
import io
import json
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import spec_a_9_10_template as sut  # noqa: E402

EXPECTED_TAG = sut.PINNED_TAG
EXPECTED_DIGEST = sut.PINNED_DIGEST

ACR = sut.ACR
REPOSITORY = sut.REPOSITORY
LIVE_IMAGE = f"{ACR}/{REPOSITORY}:{EXPECTED_TAG}"
OVERRIDE_IMAGE = f"{ACR}/{REPOSITORY}@{EXPECTED_DIGEST}"


def _live_env() -> list[dict]:
    """Return the canonical live env list."""
    return [
        {"name": "SPRING_PROFILES_ACTIVE", "value": "prod,azure"},
        {"name": "SPRING_MAIN_WEB_APPLICATION_TYPE", "value": "none"},
        {"name": "MARKET_DATA_JOB_RUNNER_ENABLED", "value": "false"},
        {"name": "MANAGEMENT_TRACING_EXPORT_ENABLED", "value": "true"},
        {"name": "MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT", "value": "grpc"},
        {"name": "MANAGEMENT_TRACING_SAMPLING_PROBABILITY", "value": "1.0"},
        {"name": "SERVICE_VERSION", "value": EXPECTED_TAG},
        {"name": "DEPLOYMENT_ENVIRONMENT_NAME", "value": "prod"},
        {"name": "OTEL_SERVICE_NAME", "value": "market-data-refresh-job"},
        {"name": "SPRING_DATA_MONGODB_URI", "secretRef": "spring-data-mongodb-uri"},
        {"name": "KAFKA_BOOTSTRAP_SERVERS", "secretRef": "kafka-bootstrap-servers"},
        {"name": "KAFKA_SASL_USERNAME", "secretRef": "kafka-sasl-username"},
        {"name": "KAFKA_SASL_PASSWORD", "secretRef": "kafka-sasl-password"},
        {"name": "INTERNAL_API_KEY", "secretRef": "internal-api-key"},
    ]


def _live_template() -> dict:
    return {
        "containers": [
            {
                "name": "market-data-refresh",
                "image": LIVE_IMAGE,
                "resources": {"cpu": 0.5, "memory": "1Gi", "ephemeralStorage": "2Gi"},
                "env": _live_env(),
            }
        ],
        "initContainers": None,
        "volumes": None,
    }


def _override_env() -> list[dict]:
    env = _live_env()
    for e in env:
        if e["name"] == "MARKET_DATA_JOB_RUNNER_ENABLED":
            e["value"] = "true"
    return env


def _valid_override() -> dict:
    return {
        "containers": [
            {
                "name": "market-data-refresh",
                "image": OVERRIDE_IMAGE,
                "resources": {"cpu": 0.5, "memory": "1Gi", "ephemeralStorage": "2Gi"},
                "env": _override_env(),
            }
        ],
        "initContainers": None,
        "volumes": None,
    }


def _assert_fails(test: unittest.TestCase, fn, *args, **kwargs) -> None:
    """Assert that fn raises SystemExit (i.e. calls _fail)."""
    with test.assertRaises(SystemExit):
        fn(*args, **kwargs)


class GoldenPathTest(unittest.TestCase):
    """build() produces exactly the two-path diff; verify() confirms PASS."""

    def test_build_produces_valid_override(self) -> None:
        live = _live_template()
        override = sut.build(live, EXPECTED_TAG, EXPECTED_DIGEST)
        self.assertEqual(override["containers"][0]["image"], OVERRIDE_IMAGE)
        env = {e["name"]: e for e in override["containers"][0]["env"]}
        self.assertEqual(env["MARKET_DATA_JOB_RUNNER_ENABLED"]["value"], "true")

    def test_verify_passes_on_valid_pair(self) -> None:
        live = _live_template()
        override = _valid_override()
        diff = sut.verify(live, override, EXPECTED_TAG, EXPECTED_DIGEST)
        self.assertIn("containers[0].image:", diff)
        self.assertIn(LIVE_IMAGE, diff)
        self.assertIn(OVERRIDE_IMAGE, diff)
        self.assertIn("containers[0].env[MARKET_DATA_JOB_RUNNER_ENABLED].value: false -> true", diff)

    def test_diff_contains_exactly_two_paths(self) -> None:
        live = _live_template()
        override = _valid_override()
        diff = sut.verify(live, override, EXPECTED_TAG, EXPECTED_DIGEST)
        lines = [line.strip() for line in diff.splitlines() if line.strip()]
        # Expect exactly: "containers[0].image:", the two image lines, the runner line
        self.assertEqual(len(lines), 4, f"Expected 4 diff lines; got:\n{diff}")

    def test_build_does_not_mutate_live(self) -> None:
        live = _live_template()
        live_copy = copy.deepcopy(live)
        sut.build(live, EXPECTED_TAG, EXPECTED_DIGEST)
        self.assertEqual(live, live_copy)


class PinnedIdentityTest(unittest.TestCase):
    """verify() and build() must reject any tag/digest not matching the pinned constants."""

    def test_verify_wrong_tag_rejected(self) -> None:
        _assert_fails(
            self, sut.verify, _live_template(), _valid_override(),
            "wrong-tag-value", EXPECTED_DIGEST,
        )

    def test_verify_wrong_digest_rejected(self) -> None:
        _assert_fails(
            self, sut.verify, _live_template(), _valid_override(),
            EXPECTED_TAG, "sha256:" + "00" * 32,
        )

    def test_build_wrong_tag_rejected(self) -> None:
        _assert_fails(self, sut.build, _live_template(), "wrong-tag-value", EXPECTED_DIGEST)

    def test_build_wrong_digest_rejected(self) -> None:
        _assert_fails(self, sut.build, _live_template(), EXPECTED_TAG, "sha256:" + "00" * 32)


class PartialTemplateTest(unittest.TestCase):
    def test_missing_containers_key(self) -> None:
        tmpl = {"initContainers": None, "volumes": None}
        _assert_fails(self, sut.verify, tmpl, _valid_override(), EXPECTED_TAG, EXPECTED_DIGEST)

    def test_empty_containers(self) -> None:
        live = _live_template()
        live["containers"] = []
        _assert_fails(self, sut.verify, live, _valid_override(), EXPECTED_TAG, EXPECTED_DIGEST)

    def test_missing_env(self) -> None:
        live = _live_template()
        del live["containers"][0]["env"]
        _assert_fails(self, sut.verify, live, _valid_override(), EXPECTED_TAG, EXPECTED_DIGEST)


class MultipleContainersTest(unittest.TestCase):
    def test_two_containers_in_live(self) -> None:
        live = _live_template()
        live["containers"].append(copy.deepcopy(live["containers"][0]))
        _assert_fails(self, sut.verify, live, _valid_override(), EXPECTED_TAG, EXPECTED_DIGEST)

    def test_two_containers_in_override(self) -> None:
        live = _live_template()
        override = _valid_override()
        override["containers"].append(copy.deepcopy(override["containers"][0]))
        _assert_fails(self, sut.verify, live, override, EXPECTED_TAG, EXPECTED_DIGEST)


class InitContainerAndVolumeTest(unittest.TestCase):
    def test_init_container_rejected(self) -> None:
        override = _valid_override()
        override["initContainers"] = [{"name": "init"}]
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)

    def test_volumes_rejected(self) -> None:
        override = _valid_override()
        override["volumes"] = [{"name": "vol"}]
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)


class WrongContainerNameTest(unittest.TestCase):
    def test_wrong_name_in_override(self) -> None:
        override = _valid_override()
        override["containers"][0]["name"] = "wrong-name"
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)


class WrongImageTest(unittest.TestCase):
    def test_wrong_registry_in_override(self) -> None:
        override = _valid_override()
        override["containers"][0]["image"] = (
            f"evil.example/{REPOSITORY}@{EXPECTED_DIGEST}"
        )
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)

    def test_wrong_repository_in_override(self) -> None:
        override = _valid_override()
        override["containers"][0]["image"] = (
            f"{ACR}/wrong-service@{EXPECTED_DIGEST}"
        )
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)

    def test_tag_not_digest_in_override(self) -> None:
        """Override must use @digest, not :tag."""
        override = _valid_override()
        override["containers"][0]["image"] = LIVE_IMAGE
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)

    def test_wrong_digest_in_override(self) -> None:
        override = _valid_override()
        override["containers"][0]["image"] = f"{ACR}/{REPOSITORY}@sha256:{'00' * 32}"
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)

    def test_wrong_tag_in_live(self) -> None:
        live = _live_template()
        live["containers"][0]["image"] = f"{ACR}/{REPOSITORY}:deadbeef"
        _assert_fails(self, sut.verify, live, _valid_override(), EXPECTED_TAG, EXPECTED_DIGEST)


class PlaintextSecretTest(unittest.TestCase):
    """Any secret env entry carrying a plain 'value' must be rejected."""

    def _override_with_plaintext(self, name: str) -> dict:
        override = _valid_override()
        for entry in override["containers"][0]["env"]:
            if entry["name"] == name:
                del entry["secretRef"]
                entry["value"] = "supersecret"
                break
        return override

    def test_mongodb_uri_plaintext_rejected(self) -> None:
        _assert_fails(
            self, sut.verify, _live_template(),
            self._override_with_plaintext("SPRING_DATA_MONGODB_URI"),
            EXPECTED_TAG, EXPECTED_DIGEST,
        )

    def test_kafka_password_plaintext_rejected(self) -> None:
        _assert_fails(
            self, sut.verify, _live_template(),
            self._override_with_plaintext("KAFKA_SASL_PASSWORD"),
            EXPECTED_TAG, EXPECTED_DIGEST,
        )

    def test_internal_api_key_plaintext_rejected(self) -> None:
        _assert_fails(
            self, sut.verify, _live_template(),
            self._override_with_plaintext("INTERNAL_API_KEY"),
            EXPECTED_TAG, EXPECTED_DIGEST,
        )


class MissingEnvEntryTest(unittest.TestCase):
    def test_missing_runner_env(self) -> None:
        override = _valid_override()
        override["containers"][0]["env"] = [
            e for e in override["containers"][0]["env"]
            if e["name"] != "MARKET_DATA_JOB_RUNNER_ENABLED"
        ]
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)

    def test_missing_service_version(self) -> None:
        override = _valid_override()
        override["containers"][0]["env"] = [
            e for e in override["containers"][0]["env"]
            if e["name"] != "SERVICE_VERSION"
        ]
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)

    def test_missing_secret_entry(self) -> None:
        override = _valid_override()
        override["containers"][0]["env"] = [
            e for e in override["containers"][0]["env"]
            if e["name"] != "KAFKA_BOOTSTRAP_SERVERS"
        ]
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)


class ExtraEnvEntryTest(unittest.TestCase):
    def test_extra_plain_env_rejected(self) -> None:
        override = _valid_override()
        override["containers"][0]["env"].append(
            {"name": "EXTRA_FLAG", "value": "injected"}
        )
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)

    def test_extra_secret_env_rejected(self) -> None:
        override = _valid_override()
        override["containers"][0]["env"].append(
            {"name": "EXTRA_SECRET", "secretRef": "some-secret"}
        )
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)


class DuplicateEnvEntryTest(unittest.TestCase):
    def test_duplicate_runner_entry_rejected(self) -> None:
        override = _valid_override()
        override["containers"][0]["env"].append(
            {"name": "MARKET_DATA_JOB_RUNNER_ENABLED", "value": "true"}
        )
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)


class AlteredResourcesTest(unittest.TestCase):
    def test_altered_cpu_rejected(self) -> None:
        override = _valid_override()
        override["containers"][0]["resources"]["cpu"] = 1.0
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)

    def test_altered_memory_rejected(self) -> None:
        override = _valid_override()
        override["containers"][0]["resources"]["memory"] = "2Gi"
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)


class EphemeralStorageTest(unittest.TestCase):
    """ephemeralStorage must be exactly '2Gi' in both live and override."""

    def test_missing_ephemeral_storage_rejected(self) -> None:
        live = _live_template()
        del live["containers"][0]["resources"]["ephemeralStorage"]
        _assert_fails(self, sut.verify, live, _valid_override(), EXPECTED_TAG, EXPECTED_DIGEST)

    def test_wrong_ephemeral_storage_value_rejected(self) -> None:
        live = _live_template()
        live["containers"][0]["resources"]["ephemeralStorage"] = "4Gi"
        _assert_fails(self, sut.verify, live, _valid_override(), EXPECTED_TAG, EXPECTED_DIGEST)

    def test_ephemeral_altered_in_override_rejected(self) -> None:
        override = _valid_override()
        override["containers"][0]["resources"]["ephemeralStorage"] = "4Gi"
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)


class UnexpectedKeyTest(unittest.TestCase):
    """Unexpected keys in the container or at the template level must be rejected."""

    def test_command_in_container_rejected(self) -> None:
        live = _live_template()
        live["containers"][0]["command"] = ["/bin/sh", "-c", "echo injected"]
        _assert_fails(self, sut.verify, live, _valid_override(), EXPECTED_TAG, EXPECTED_DIGEST)

    def test_args_in_container_rejected(self) -> None:
        override = _valid_override()
        override["containers"][0]["args"] = ["--inject"]
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)

    def test_extra_template_level_key_rejected(self) -> None:
        live = _live_template()
        live["terminationGracePeriodSeconds"] = 30
        _assert_fails(self, sut.verify, live, _valid_override(), EXPECTED_TAG, EXPECTED_DIGEST)


class RunnerAlreadyEnabledTest(unittest.TestCase):
    """build() must reject a live template where runner is already true."""

    def test_build_rejects_live_runner_true(self) -> None:
        live = _live_template()
        for entry in live["containers"][0]["env"]:
            if entry["name"] == "MARKET_DATA_JOB_RUNNER_ENABLED":
                entry["value"] = "true"
                break
        _assert_fails(self, sut.build, live, EXPECTED_TAG, EXPECTED_DIGEST)

    def test_verify_rejects_live_runner_true(self) -> None:
        """verify() rejects any live template where runner is already enabled."""
        live = _live_template()
        for entry in live["containers"][0]["env"]:
            if entry["name"] == "MARKET_DATA_JOB_RUNNER_ENABLED":
                entry["value"] = "true"
                break
        override = _valid_override()
        _assert_fails(self, sut.verify, live, override, EXPECTED_TAG, EXPECTED_DIGEST)


class AlteredServiceVersionTest(unittest.TestCase):
    def test_wrong_service_version_in_override(self) -> None:
        override = _valid_override()
        for entry in override["containers"][0]["env"]:
            if entry["name"] == "SERVICE_VERSION":
                entry["value"] = "deadbeef" * 5
                break
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)

    def test_wrong_service_version_in_live(self) -> None:
        live = _live_template()
        for entry in live["containers"][0]["env"]:
            if entry["name"] == "SERVICE_VERSION":
                entry["value"] = "wrong"
                break
        _assert_fails(self, sut.verify, live, _valid_override(), EXPECTED_TAG, EXPECTED_DIGEST)


class ExtraDiffPathTest(unittest.TestCase):
    """verify() rejects any change beyond image and runner, even if they otherwise pass."""

    def test_extra_env_value_change_rejected(self) -> None:
        override = _valid_override()
        for entry in override["containers"][0]["env"]:
            if entry["name"] == "OTEL_SERVICE_NAME":
                entry["value"] = "tampered-name"
                break
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)

    def test_extra_resource_key_change_rejected(self) -> None:
        """An unexpected resource key in the override is rejected by key enforcement and diff."""
        override = _valid_override()
        override["containers"][0]["resources"]["bandwidthMbps"] = 100
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)

    def test_wrong_secret_ref_change_rejected(self) -> None:
        override = _valid_override()
        for entry in override["containers"][0]["env"]:
            if entry["name"] == "KAFKA_SASL_USERNAME":
                entry["secretRef"] = "kafka-sasl-password"  # swapped
                break
        _assert_fails(self, sut.verify, _live_template(), override, EXPECTED_TAG, EXPECTED_DIGEST)


class SecretSafeErrorTest(unittest.TestCase):
    """Failure messages must never leak plaintext secret values to stderr."""

    def _capture_stderr(self, fn, *args, **kwargs) -> str:
        buf = io.StringIO()
        with self.assertRaises(SystemExit):
            with contextlib.redirect_stderr(buf):
                fn(*args, **kwargs)
        return buf.getvalue()

    def test_plaintext_secret_not_in_stderr(self) -> None:
        sentinel = "SENTINEL_SECRET_XK9j3_DO_NOT_LOG"
        live = _live_template()
        for entry in live["containers"][0]["env"]:
            if entry["name"] == "SPRING_DATA_MONGODB_URI":
                del entry["secretRef"]
                entry["value"] = sentinel
                break
        err = self._capture_stderr(
            sut.verify, live, _valid_override(), EXPECTED_TAG, EXPECTED_DIGEST
        )
        self.assertNotIn(sentinel, err, "Secret sentinel must never appear in error output")

    def test_container_count_error_does_not_print_contents(self) -> None:
        """A wrong-container-count error must not dump container dict contents."""
        sentinel = "SENTINEL_SECRET_YQ7r2_DO_NOT_LOG"
        live = _live_template()
        # Embed a recognizable value; add second container to trigger the count error
        live["containers"][0]["env"][0]["value"] = sentinel
        live["containers"].append(copy.deepcopy(live["containers"][0]))
        err = self._capture_stderr(
            sut.verify, live, _valid_override(), EXPECTED_TAG, EXPECTED_DIGEST
        )
        self.assertNotIn(
            sentinel, err, "Container object contents must not appear in error output"
        )


class CLITest(unittest.TestCase):
    """CLI-level integration tests for cmd_build and main()."""

    def setUp(self) -> None:
        self.tmpdir = tempfile.mkdtemp()

    def tearDown(self) -> None:
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def _live_path(self) -> str:
        p = os.path.join(self.tmpdir, "live.json")
        with open(p, "w", encoding="utf-8") as fh:
            json.dump(_live_template(), fh)
        return p

    def _build_argv(self, live: str, out: str) -> list[str]:
        return [
            "build",
            "--live-template", live,
            "--output", out,
            "--expected-tag", EXPECTED_TAG,
            "--expected-digest", EXPECTED_DIGEST,
        ]

    def test_build_writes_output_file(self) -> None:
        out_path = os.path.join(self.tmpdir, "override.json")
        sut.main(self._build_argv(self._live_path(), out_path))
        self.assertTrue(os.path.exists(out_path))
        with open(out_path, encoding="utf-8") as fh:
            override = json.load(fh)
        self.assertEqual(override["containers"][0]["image"], OVERRIDE_IMAGE)
        env = {e["name"]: e for e in override["containers"][0]["env"]}
        self.assertEqual(env["MARKET_DATA_JOB_RUNNER_ENABLED"]["value"], "true")

    def test_build_checksum_is_deterministic(self) -> None:
        out1 = os.path.join(self.tmpdir, "override1.json")
        out2 = os.path.join(self.tmpdir, "override2.json")
        sut.main(self._build_argv(self._live_path(), out1))
        sut.main(self._build_argv(self._live_path(), out2))
        with open(out1, "rb") as f1, open(out2, "rb") as f2:
            self.assertEqual(f1.read(), f2.read(), "Build output must be deterministic")

    def test_malformed_json_fails_gracefully(self) -> None:
        bad_path = os.path.join(self.tmpdir, "bad.json")
        with open(bad_path, "w") as fh:
            fh.write("{not valid json}")
        out_path = os.path.join(self.tmpdir, "override.json")
        with self.assertRaises(SystemExit):
            sut.main(self._build_argv(bad_path, out_path))

    def test_same_input_output_path_rejected(self) -> None:
        live_path = self._live_path()
        with self.assertRaises(SystemExit):
            sut.main(self._build_argv(live_path, live_path))

    def test_secret_never_in_stderr(self) -> None:
        """Even on error, a plaintext secret in the live template must not appear in stderr."""
        sentinel = "SENTINEL_CLI_SECRET_ZM4p8_DO_NOT_LOG"
        live = _live_template()
        for entry in live["containers"][0]["env"]:
            if entry["name"] == "SPRING_DATA_MONGODB_URI":
                del entry["secretRef"]
                entry["value"] = sentinel
                break
        bad_path = os.path.join(self.tmpdir, "bad_live.json")
        with open(bad_path, "w", encoding="utf-8") as fh:
            json.dump(live, fh)
        out_path = os.path.join(self.tmpdir, "override.json")
        buf = io.StringIO()
        with self.assertRaises(SystemExit):
            with contextlib.redirect_stderr(buf):
                sut.main(self._build_argv(bad_path, out_path))
        self.assertNotIn(
            sentinel, buf.getvalue(), "Secret sentinel must never appear in stderr"
        )


if __name__ == "__main__":
    unittest.main()
