#!/usr/bin/env python3
"""Fixtures for the R-C candidate image packaging/extraction evidence tool.

Covers the negative cases across two review rounds: a missing staged JAR, a missing Dockerfile, a
missing/wrong artifact inside the built image, a hash mismatch, a staged JAR silently replaced
after Task A's evidence was captured, an unresolvable/floating base digest, extraction keyed to a
mutable tag instead of an immutable image ID, and `--skip-build` provenance that must go
"unverified" rather than trusting caller-supplied claims -- plus (where Docker is available) real
end-to-end checks proving each fix against the actual docker CLI, not just mocks.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))

import verify_b1_candidate_image as viv  # noqa: E402
from b1_candidate_evidence import EvidenceError, sha256_file  # noqa: E402

DOCKER = shutil.which("docker")


def _docker_daemon_available() -> bool:
    if not DOCKER:
        return False
    try:
        subprocess.run(["docker", "info"], capture_output=True, check=True, timeout=10)
        return True
    except Exception:
        return False


DOCKER_AVAILABLE = _docker_daemon_available()


def docker_rmi_all(refs: list[str]) -> list[tuple[str, str]]:
    """Remove every ref (image ID or tag name), tolerating "already gone" but returning (ref,
    stderr) for any other failure -- so a caller can assert cleanup actually succeeded instead of
    silently swallowing it, while still attempting every owned ref even if one fails."""
    failures = []
    for ref in refs:
        result = subprocess.run(["docker", "rmi", "-f", ref], capture_output=True, text=True)
        if result.returncode != 0 and "No such image" not in result.stderr:
            failures.append((ref, result.stderr.strip()))
    return failures


def make_task_a_evidence(stage_sha256: str, *, status: str = "PASS", head_sha: str = "deadbeef" * 5) -> dict:
    return {
        "graph_verification_status": status,
        "run": {"head_sha": head_sha, "mode": "LOCAL_DEV"},
        "stage": {"sha256": stage_sha256, "bootjar_path": "x", "staged_path": "y"},
        "problems": [],
    }


class LoadTaskAEvidenceTests(unittest.TestCase):
    def test_missing_file_fails_closed(self) -> None:
        with TemporaryDirectory() as tmp:
            with self.assertRaises(EvidenceError) as ctx:
                viv.load_task_a_evidence(Path(tmp) / "does-not-exist.json")
            self.assertIn("cannot read", str(ctx.exception))

    def test_malformed_json_fails_closed(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "evidence.json"
            path.write_text("{not json", encoding="utf-8")
            with self.assertRaises(EvidenceError):
                viv.load_task_a_evidence(path)

    def test_failed_bundle_rejected(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "evidence.json"
            path.write_text(json.dumps({"graph_verification_status": "FAIL", "error": "x"}), encoding="utf-8")
            with self.assertRaises(EvidenceError) as ctx:
                viv.load_task_a_evidence(path)
            self.assertIn("not a successful PASS", str(ctx.exception))

    def test_missing_stage_sha_rejected(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "evidence.json"
            path.write_text(json.dumps({"graph_verification_status": "PASS", "stage": None}), encoding="utf-8")
            with self.assertRaises(EvidenceError) as ctx:
                viv.load_task_a_evidence(path)
            self.assertIn("stage.sha256", str(ctx.exception))

    def test_valid_bundle_loads(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "evidence.json"
            path.write_text(json.dumps(make_task_a_evidence("abc123")), encoding="utf-8")
            data = viv.load_task_a_evidence(path)
            self.assertEqual(data["stage"]["sha256"], "abc123")


class ResolveStagedJarTests(unittest.TestCase):
    def test_missing_staged_jar_fails_closed(self) -> None:
        with TemporaryDirectory() as tmp:
            repo = Path(tmp)
            policy = {"staging": {"staged_path": ".candidate-artifacts/portfolio-service.jar"}}
            task_a_evidence = make_task_a_evidence("irrelevant")
            with self.assertRaises(EvidenceError) as ctx:
                viv.resolve_staged_jar(repo, policy, task_a_evidence)
            self.assertIn("not found", str(ctx.exception))

    def test_matching_hash_passes(self) -> None:
        with TemporaryDirectory() as tmp:
            repo = Path(tmp)
            staged = repo / ".candidate-artifacts" / "portfolio-service.jar"
            staged.parent.mkdir(parents=True)
            staged.write_bytes(b"jar-bytes")
            policy = {"staging": {"staged_path": ".candidate-artifacts/portfolio-service.jar"}}
            task_a_evidence = make_task_a_evidence(sha256_file(staged))
            path, digest = viv.resolve_staged_jar(repo, policy, task_a_evidence)
            self.assertEqual(path, staged)
            self.assertEqual(digest, sha256_file(staged))

    def test_staged_file_replaced_after_task_a_is_rejected(self) -> None:
        """Reviewer-reported regression: an isolated fixture replaced the staged file after Task A
        recorded its evidence. resolve_staged_jar must bind to Task A's *recorded* hash, not
        whatever is on disk right now, and reject the mismatch."""
        with TemporaryDirectory() as tmp:
            repo = Path(tmp)
            staged = repo / ".candidate-artifacts" / "portfolio-service.jar"
            staged.parent.mkdir(parents=True)
            staged.write_bytes(b"original-task-a-verified-bytes")
            policy = {"staging": {"staged_path": ".candidate-artifacts/portfolio-service.jar"}}
            # Task A's evidence recorded the ORIGINAL content's hash.
            task_a_evidence = make_task_a_evidence(sha256_file(staged))
            # Something replaces the staged file afterward -- a stale artifact, a bug, tampering.
            staged.write_bytes(b"different-bytes-snuck-in-after-task-a")
            with self.assertRaises(EvidenceError) as ctx:
                viv.resolve_staged_jar(repo, policy, task_a_evidence)
            message = str(ctx.exception)
            self.assertIn("does not match Task A", message)


class AssertExtractedJarMatchesTests(unittest.TestCase):
    def test_missing_extracted_file_fails_closed(self) -> None:
        with TemporaryDirectory() as tmp:
            with self.assertRaises(EvidenceError) as ctx:
                viv.assert_extracted_jar_matches(Path(tmp) / "does-not-exist.jar", "deadbeef")
            self.assertIn("not found", str(ctx.exception))

    def test_hash_mismatch_fails_closed(self) -> None:
        with TemporaryDirectory() as tmp:
            extracted = Path(tmp) / "extracted.jar"
            extracted.write_bytes(b"wrong-content")
            with self.assertRaises(EvidenceError) as ctx:
                viv.assert_extracted_jar_matches(extracted, "0" * 64)
            self.assertIn("mismatch", str(ctx.exception))

    def test_matching_hash_passes(self) -> None:
        with TemporaryDirectory() as tmp:
            extracted = Path(tmp) / "extracted.jar"
            extracted.write_bytes(b"same-content")
            expected = sha256_file(extracted)
            actual = viv.assert_extracted_jar_matches(extracted, expected)
            self.assertEqual(actual, expected)


class DockerBuildTests(unittest.TestCase):
    def test_missing_dockerfile_fails_closed_without_invoking_docker(self) -> None:
        with TemporaryDirectory() as tmp:
            with mock.patch.object(viv, "_run") as run_mock:
                with self.assertRaises(EvidenceError) as ctx:
                    viv.docker_build(Path(tmp) / "Dockerfile.nope", Path(tmp), "some:tag")
                run_mock.assert_not_called()
            self.assertIn("Dockerfile not found", str(ctx.exception))

    def test_platform_flag_passed_when_given(self) -> None:
        with TemporaryDirectory() as tmp:
            dockerfile = Path(tmp) / "Dockerfile"
            dockerfile.write_text("FROM busybox\n", encoding="utf-8")
            with mock.patch.object(viv, "_run") as run_mock:
                viv.docker_build(dockerfile, Path(tmp), "some:tag", platform="linux/amd64")
                cmd = run_mock.call_args[0][0]
                self.assertIn("--platform", cmd)
                self.assertEqual(cmd[cmd.index("--platform") + 1], "linux/amd64")


class RunWrapperTests(unittest.TestCase):
    def test_called_process_error_wrapped(self) -> None:
        err = subprocess.CalledProcessError(returncode=7, cmd=["docker", "x"], stderr="boom")
        with mock.patch("subprocess.run", side_effect=err):
            with self.assertRaises(EvidenceError) as ctx:
                viv._run(["docker", "x"])
            self.assertIn("exit 7", str(ctx.exception))
            self.assertIn("boom", str(ctx.exception))

    def test_missing_executable_wrapped(self) -> None:
        with mock.patch("subprocess.run", side_effect=FileNotFoundError("no such file")):
            with self.assertRaises(EvidenceError) as ctx:
                viv._run(["docker", "x"])
            self.assertIn("not found", str(ctx.exception))


class ResolveBaseDigestTests(unittest.TestCase):
    def test_returns_none_when_inspect_fails(self) -> None:
        with mock.patch.object(viv, "_run", side_effect=EvidenceError("no such image")):
            self.assertIsNone(viv.resolve_base_digest("some/image:tag"))

    def test_returns_first_digest_when_present(self) -> None:
        fake_result = mock.Mock(stdout="repo@sha256:abc,repo@sha256:def\n")
        with mock.patch.object(viv, "_run", return_value=fake_result):
            self.assertEqual(viv.resolve_base_digest("some/image:tag"), "repo@sha256:abc")

    def test_returns_none_when_no_digests_recorded(self) -> None:
        fake_result = mock.Mock(stdout="\n")
        with mock.patch.object(viv, "_run", return_value=fake_result):
            self.assertIsNone(viv.resolve_base_digest("some/image:tag"))

    def test_pull_precedes_inspect(self) -> None:
        """The base must be pulled before it is inspected, on every call -- BuildKit does not
        register a base image as separately inspectable on its own."""
        calls = []

        def fake_run(cmd, **kwargs):
            calls.append(cmd)
            if cmd[1] == "pull":
                return mock.Mock(stdout="")
            return mock.Mock(stdout="repo@sha256:abc\n")

        with mock.patch.object(viv, "_run", side_effect=fake_run):
            viv.resolve_base_digest("some/image:tag")
        self.assertEqual(calls[0][:2], ["docker", "pull"])
        self.assertEqual(calls[1][:3], ["docker", "image", "inspect"])


class ResolvePinnedBaseTests(unittest.TestCase):
    def test_unresolvable_base_fails_closed(self) -> None:
        with mock.patch.object(viv, "resolve_base_digest", return_value=None):
            with self.assertRaises(EvidenceError) as ctx:
                viv.resolve_pinned_base("some/image:floating")
            self.assertIn("unpinned floating tag", str(ctx.exception))

    def test_resolved_digest_returned(self) -> None:
        with mock.patch.object(viv, "resolve_base_digest", return_value="repo@sha256:abc"):
            self.assertEqual(viv.resolve_pinned_base("some/image:tag"), "repo@sha256:abc")


class VerifyCandidateImageBuildOrderTests(unittest.TestCase):
    """Mocked: proves the base is resolved to an immutable digest *before* docker_build is called,
    and that docker_build receives the pinned digest -- not the floating tag -- as RUNTIME_BASE."""

    def test_base_resolved_before_build_and_build_uses_pinned_digest(self) -> None:
        call_order = []

        def fake_resolve_pinned_base(base_ref):
            call_order.append("resolve")
            return "repo@sha256:pinned"

        def fake_docker_build(dockerfile, context, tag, build_args=None, platform=None, iidfile=None):
            call_order.append("build")
            self.assertEqual(build_args["RUNTIME_BASE"], "repo@sha256:pinned")
            self.assertEqual(platform, "linux/amd64")
            self.assertIsNotNone(iidfile)  # must request --iidfile, not a later tag re-read
            return "sha256:builtimageid"

        def fake_docker_image_field(ref, fmt):
            self.assertEqual(ref, "sha256:builtimageid")  # never re-reads the mutable tag
            return "linux/amd64"

        with TemporaryDirectory() as tmp:
            repo = Path(tmp)
            staged = repo / ".candidate-artifacts" / "portfolio-service.jar"
            staged.parent.mkdir(parents=True)
            staged.write_bytes(b"jar-bytes")
            policy = {"staging": {"staged_path": ".candidate-artifacts/portfolio-service.jar"}}
            task_a_evidence = make_task_a_evidence(sha256_file(staged))
            dockerfile = repo / "Dockerfile.candidate"
            dockerfile.write_text("FROM busybox\n", encoding="utf-8")

            with mock.patch.object(viv, "resolve_pinned_base", side_effect=fake_resolve_pinned_base), \
                 mock.patch.object(viv, "docker_build", side_effect=fake_docker_build), \
                 mock.patch.object(viv, "docker_image_field", side_effect=fake_docker_image_field), \
                 mock.patch.object(viv, "write_build_record"), \
                 mock.patch.object(viv, "extract_file") as extract_mock:
                extracted = repo / ".candidate-artifacts" / "image-verify-tmp" / "extracted-app.jar"

                def fake_extract(image_ref, container_path, out_path):
                    self.assertEqual(image_ref, "sha256:builtimageid")  # immutable ID, not the tag
                    out_path.parent.mkdir(parents=True, exist_ok=True)
                    out_path.write_bytes(b"jar-bytes")

                extract_mock.side_effect = fake_extract
                evidence = viv.verify_candidate_image(repo, policy, task_a_evidence, dockerfile=dockerfile)

        self.assertEqual(call_order, ["resolve", "build"])  # resolve strictly before build
        self.assertEqual(evidence["runtime_base_digest"], "repo@sha256:pinned")
        self.assertEqual(evidence["provenance"], "verified")


class AssertPlatformMatchesTests(unittest.TestCase):
    def test_matching_platform_passes(self) -> None:
        viv.assert_platform_matches("linux/amd64", "linux/amd64")  # does not raise

    def test_no_expected_platform_is_never_validated(self) -> None:
        viv.assert_platform_matches("linux/arm64", None)  # nothing to compare against; does not raise

    def test_mismatch_fails_closed(self) -> None:
        with self.assertRaises(EvidenceError) as ctx:
            viv.assert_platform_matches("linux/arm64", "linux/amd64")
        self.assertIn("platform mismatch", str(ctx.exception))


class PlatformMismatchRejectionTests(unittest.TestCase):
    """Mocked: reproduces the reviewer's exact fixture (requested linux/amd64, image inspects as
    linux/arm64) and confirms it is now rejected before evidence is accepted or a build record is
    written -- not merely recorded alongside the mismatch."""

    def _repo_with_staged_jar(self, tmp: str):
        repo = Path(tmp)
        staged = repo / ".candidate-artifacts" / "portfolio-service.jar"
        staged.parent.mkdir(parents=True)
        staged.write_bytes(b"jar-bytes")
        policy = {"staging": {"staged_path": ".candidate-artifacts/portfolio-service.jar"}}
        return repo, policy, make_task_a_evidence(sha256_file(staged))

    def test_build_path_rejects_mismatch_before_build_record(self) -> None:
        with TemporaryDirectory() as tmp:
            repo, policy, task_a_evidence = self._repo_with_staged_jar(tmp)
            dockerfile = repo / "Dockerfile.candidate"
            dockerfile.write_text("FROM busybox\n", encoding="utf-8")

            def fake_docker_image_field(ref, fmt):
                return "linux/arm64" if fmt == "{{.Os}}/{{.Architecture}}" else "sha256:builtimageid"

            with mock.patch.object(viv, "resolve_pinned_base", return_value="repo@sha256:pinned"), \
                 mock.patch.object(viv, "docker_build", return_value="sha256:builtimageid"), \
                 mock.patch.object(viv, "docker_image_field", side_effect=fake_docker_image_field), \
                 mock.patch.object(viv, "write_build_record") as write_record_mock, \
                 mock.patch.object(viv, "extract_file") as extract_mock:
                with self.assertRaises(EvidenceError) as ctx:
                    viv.verify_candidate_image(
                        repo, policy, task_a_evidence, tag="some:tag", dockerfile=dockerfile,
                        platform="linux/amd64",
                    )
                self.assertIn("platform mismatch", str(ctx.exception))
                write_record_mock.assert_not_called()  # rejected before a build record is written
                extract_mock.assert_not_called()  # rejected before extraction/hash evidence too

    def test_skip_build_matching_record_rejects_mismatch(self) -> None:
        with TemporaryDirectory() as tmp:
            repo, policy, task_a_evidence = self._repo_with_staged_jar(tmp)
            record_path = repo / "record.json"
            record_path.write_text(json.dumps({
                "tag": "some:tag", "image_id": "sha256:current", "base_ref": "base:ref",
                "base_digest": "base@sha256:pinned", "platform": "linux/amd64",
                "dockerfile_path": "Dockerfile.candidate", "dockerfile_sha256": "irrelevant",
            }), encoding="utf-8")

            def fake_docker_image_field(ref, fmt):
                if fmt == "{{.Id}}":
                    return "sha256:current"
                return "linux/arm64"  # record says linux/amd64 -- mismatch

            with mock.patch.object(viv, "docker_image_field", side_effect=fake_docker_image_field), \
                 mock.patch.object(viv, "extract_file") as extract_mock:
                with self.assertRaises(EvidenceError) as ctx:
                    viv.verify_candidate_image(
                        repo, policy, task_a_evidence, tag="some:tag", skip_build=True,
                        build_record_path=record_path,
                    )
                self.assertIn("platform mismatch", str(ctx.exception))
                extract_mock.assert_not_called()


class BuildOutputIdentityImmuneToRetagTests(unittest.TestCase):
    """Mocked, deterministic version of the reviewer's fixture: builds A, then simulates `tag`
    being retagged to point at a different image B before any identity is re-read. Proves
    verify_candidate_image uses docker_build's own --iidfile-captured return value throughout
    (extraction, platform check, evidence) and never falls back to a fresh `docker image inspect
    <tag>` lookup that a retag could have poisoned."""

    def test_retag_after_build_does_not_change_the_captured_identity(self) -> None:
        with TemporaryDirectory() as tmp:
            repo = Path(tmp)
            staged = repo / ".candidate-artifacts" / "portfolio-service.jar"
            staged.parent.mkdir(parents=True)
            staged.write_bytes(b"jar-bytes")
            policy = {"staging": {"staged_path": ".candidate-artifacts/portfolio-service.jar"}}
            task_a_evidence = make_task_a_evidence(sha256_file(staged))
            dockerfile = repo / "Dockerfile.candidate"
            dockerfile.write_text("FROM busybox\n", encoding="utf-8")

            # docker_build returns image A's id (as --iidfile would). Simulate the tag being
            # retagged to image B *immediately afterward*, before any other identity lookup:
            # docker_image_field(tag, ...) would now report B if the code ever asked it again.
            def fake_docker_image_field(ref, fmt):
                if ref == "sha256:image-A":
                    return "linux/amd64" if fmt == "{{.Os}}/{{.Architecture}}" else "sha256:image-A"
                if ref == "some:tag":
                    # A stand-in for "the tag now resolves to image B" -- if the code under test
                    # reads this, the test fails via the extract/evidence assertions below.
                    return "linux/arm64" if fmt == "{{.Os}}/{{.Architecture}}" else "sha256:image-B"
                raise AssertionError(f"unexpected ref {ref!r}")

            with mock.patch.object(viv, "resolve_pinned_base", return_value="repo@sha256:pinned"), \
                 mock.patch.object(viv, "docker_build", return_value="sha256:image-A"), \
                 mock.patch.object(viv, "docker_image_field", side_effect=fake_docker_image_field), \
                 mock.patch.object(viv, "write_build_record"), \
                 mock.patch.object(viv, "extract_file") as extract_mock:
                def fake_extract(image_ref, container_path, out_path):
                    self.assertEqual(image_ref, "sha256:image-A")  # not "some:tag", not image-B
                    out_path.parent.mkdir(parents=True, exist_ok=True)
                    out_path.write_bytes(b"jar-bytes")
                extract_mock.side_effect = fake_extract

                evidence = viv.verify_candidate_image(
                    repo, policy, task_a_evidence, tag="some:tag", dockerfile=dockerfile,
                )

            self.assertEqual(evidence["local_image_id"], "sha256:image-A")
            self.assertEqual(evidence["platform"], "linux/amd64")
            self.assertEqual(evidence["provenance"], "verified")


class SkipBuildProvenanceTests(unittest.TestCase):
    """Mocked: --skip-build must not report caller-supplied build inputs as verified facts unless
    a build record this tool itself wrote still matches the tag's current image ID."""

    def _policy_and_evidence(self, repo: Path):
        staged = repo / ".candidate-artifacts" / "portfolio-service.jar"
        staged.parent.mkdir(parents=True)
        staged.write_bytes(b"jar-bytes")
        policy = {"staging": {"staged_path": ".candidate-artifacts/portfolio-service.jar"}}
        return policy, make_task_a_evidence(sha256_file(staged))

    def test_no_build_record_is_unverified(self) -> None:
        with TemporaryDirectory() as tmp:
            repo = Path(tmp)
            policy, task_a_evidence = self._policy_and_evidence(repo)
            with mock.patch.object(viv, "docker_image_field", return_value="sha256:current"), \
                 mock.patch.object(viv, "extract_file") as extract_mock:
                def fake_extract(image_ref, container_path, out_path):
                    self.assertEqual(image_ref, "sha256:current")
                    out_path.parent.mkdir(parents=True, exist_ok=True)
                    out_path.write_bytes(b"jar-bytes")
                extract_mock.side_effect = fake_extract
                evidence = viv.verify_candidate_image(
                    repo, policy, task_a_evidence, skip_build=True,
                    build_record_path=repo / "no-record-here.json",
                )
        self.assertEqual(evidence["provenance"], "unverified")
        self.assertIsNone(evidence["runtime_base_digest"])
        self.assertIsNone(evidence["recipe"])
        self.assertIn("provenance_note", evidence)

    def test_matching_build_record_is_verified(self) -> None:
        with TemporaryDirectory() as tmp:
            repo = Path(tmp)
            policy, task_a_evidence = self._policy_and_evidence(repo)
            record_path = repo / "record.json"
            record_path.write_text(json.dumps({
                "tag": "some:tag", "image_id": "sha256:current", "base_ref": "base:ref",
                "base_digest": "base@sha256:pinned", "platform": "linux/amd64",
                "dockerfile_path": "Dockerfile.candidate", "dockerfile_sha256": "irrelevant",
            }), encoding="utf-8")
            def fake_docker_image_field(ref, fmt):
                return "linux/amd64" if fmt == "{{.Os}}/{{.Architecture}}" else "sha256:current"

            with mock.patch.object(viv, "docker_image_field", side_effect=fake_docker_image_field), \
                 mock.patch.object(viv, "extract_file") as extract_mock:
                def fake_extract(image_ref, container_path, out_path):
                    out_path.parent.mkdir(parents=True, exist_ok=True)
                    out_path.write_bytes(b"jar-bytes")
                extract_mock.side_effect = fake_extract
                evidence = viv.verify_candidate_image(
                    repo, policy, task_a_evidence, tag="some:tag", skip_build=True,
                    build_record_path=record_path,
                )
        self.assertEqual(evidence["provenance"], "verified")
        self.assertEqual(evidence["runtime_base_digest"], "base@sha256:pinned")
        self.assertNotIn("provenance_note", evidence)

    def test_stale_build_record_is_unverified(self) -> None:
        """The tag was rebuilt/retagged since the record was written -- image ID no longer matches."""
        with TemporaryDirectory() as tmp:
            repo = Path(tmp)
            policy, task_a_evidence = self._policy_and_evidence(repo)
            record_path = repo / "record.json"
            record_path.write_text(json.dumps({
                "tag": "some:tag", "image_id": "sha256:OLD", "base_ref": "base:ref",
                "base_digest": "base@sha256:pinned", "platform": "linux/amd64",
                "dockerfile_path": "Dockerfile.candidate", "dockerfile_sha256": "irrelevant",
            }), encoding="utf-8")
            with mock.patch.object(viv, "docker_image_field", return_value="sha256:NEW"), \
                 mock.patch.object(viv, "extract_file") as extract_mock:
                def fake_extract(image_ref, container_path, out_path):
                    self.assertEqual(image_ref, "sha256:NEW")
                    out_path.parent.mkdir(parents=True, exist_ok=True)
                    out_path.write_bytes(b"jar-bytes")
                extract_mock.side_effect = fake_extract
                evidence = viv.verify_candidate_image(
                    repo, policy, task_a_evidence, tag="some:tag", skip_build=True,
                    build_record_path=record_path,
                )
        self.assertEqual(evidence["provenance"], "unverified")
        self.assertIn("rebuilt, retagged", evidence["provenance_note"])


@unittest.skipUnless(DOCKER_AVAILABLE, "Docker daemon not available in this environment")
class RealDockerTests(unittest.TestCase):
    """Exercises the actual docker CLI. Each test builds/removes its own disposable image(s) and
    always cleans up any container it creates, even on failure."""

    def _staged_jar_path(self) -> Path:
        return REPO / ".candidate-artifacts" / "portfolio-service.jar"

    def test_extract_missing_artifact_fails_closed_and_cleans_up(self) -> None:
        """A real image with no /app.jar: extraction must fail closed, and the container this run
        created must not be left behind regardless."""
        with TemporaryDirectory() as tmp:
            dockerfile = Path(tmp) / "Dockerfile.no-jar"
            dockerfile.write_text("FROM busybox\n", encoding="utf-8")
            tag = "b1-candidate-test-no-jar:local"
            try:
                viv.docker_build(dockerfile, Path(tmp), tag)
                before = subprocess.run(
                    ["docker", "ps", "-a", "--filter", "name=b1-candidate-extract-", "--format", "{{.Names}}"],
                    capture_output=True, text=True, check=True,
                ).stdout
                with self.assertRaises(EvidenceError) as ctx:
                    viv.extract_file(tag, "/app.jar", Path(tmp) / "extracted.jar")
                self.assertIn("could not extract", str(ctx.exception))
                after = subprocess.run(
                    ["docker", "ps", "-a", "--filter", "name=b1-candidate-extract-", "--format", "{{.Names}}"],
                    capture_output=True, text=True, check=True,
                ).stdout
                self.assertEqual(before, after, "extract_file leaked a container after failure")
            finally:
                failures = docker_rmi_all([tag])
                self.assertEqual(failures, [], f"docker rmi cleanup failed for: {failures}")

    def test_iidfile_identity_immune_to_retag_before_the_next_read(self) -> None:
        """Reviewer-reported regression: the previous version captured a build's image ID by
        re-reading `docker image inspect <tag>` right after the build -- a window in which
        something could retag `tag` to a different image (B) before that read runs, so the
        capture would silently resolve to B instead of the image (A) actually just built. This
        fixture reproduces exactly that window: build A via --iidfile (capturing its ID with no
        separate lookup), retag `tag` -> B immediately afterward, *then* prove the already-captured
        ID for A is untouched and still extracts A's content -- unlike a fresh `docker image
        inspect tag` at that point, which would now report B.
        """
        with TemporaryDirectory() as tmp:
            tag = "b1-candidate-test-retag:local"
            other_tag = "b1-candidate-test-retag-b:local"
            owned_image_ids: list[str] = []
            try:
                (Path(tmp) / "marker.txt").write_text("A", encoding="utf-8")
                dockerfile_a = Path(tmp) / "Dockerfile.a"
                dockerfile_a.write_text("FROM busybox\nCOPY marker.txt /marker.txt\n", encoding="utf-8")
                iidfile = Path(tmp) / "iid-a.txt"
                # The --iidfile capture IS the "read" in this fixture -- nothing re-reads `tag`.
                image_a_id = viv.docker_build(dockerfile_a, Path(tmp), tag, iidfile=iidfile)
                owned_image_ids.append(image_a_id)

                # Retag `tag` -> a different image B *after* A's identity was already captured.
                (Path(tmp) / "marker.txt").write_text("B", encoding="utf-8")
                dockerfile_b = Path(tmp) / "Dockerfile.b"
                dockerfile_b.write_text("FROM busybox\nCOPY marker.txt /marker.txt\n", encoding="utf-8")
                iidfile_b = Path(tmp) / "iid-b.txt"
                image_b_id = viv.docker_build(dockerfile_b, Path(tmp), other_tag, iidfile=iidfile_b)
                owned_image_ids.append(image_b_id)
                subprocess.run(["docker", "tag", other_tag, tag], check=True)  # tag now -> image B
                self.assertNotEqual(image_a_id, image_b_id, "fixture did not actually retag to a different image")
                # Prove the race window is real: a fresh lookup of `tag` right now DOES return B.
                self.assertEqual(viv.docker_image_field(tag, "{{.Id}}"), image_b_id)

                # The value captured via --iidfile before the retag must still identify A.
                out = Path(tmp) / "extracted-marker.txt"
                viv.extract_file(image_a_id, "/marker.txt", out)
                self.assertEqual(out.read_text(encoding="utf-8"), "A")
            finally:
                # Removing the tag names alone leaves A dangling/untagged (a `docker tag` onto an
                # existing name repoints it, it does not delete the image it used to point to) --
                # clean up by the owned image IDs explicitly, and fail the test if cleanup itself
                # fails, rather than swallowing that silently.
                failures = docker_rmi_all([tag, other_tag, *owned_image_ids])
                self.assertEqual(failures, [], f"docker rmi cleanup failed for: {failures}")

    @unittest.skipUnless(
        (REPO / ".candidate-artifacts" / "portfolio-service.jar").is_file(),
        "requires a staged candidate JAR from a real Task A Gradle run "
        "(python -B scripts/b1_candidate_evidence.py mark/evidence after ./gradlew ... prepareCandidateArtifact)",
    )
    def test_real_candidate_dockerfile_produces_matching_jar(self) -> None:
        """End-to-end: build the actual Dockerfile.candidate, bound to a real Task A evidence
        bundle for the currently-staged JAR, and confirm the extracted /app.jar is byte-identical
        with the base resolved-then-pinned before the build."""
        staged_sha = sha256_file(self._staged_jar_path())
        task_a_evidence = make_task_a_evidence(staged_sha)
        tag = "b1-candidate-test-real:local"
        try:
            policy = {"staging": {"staged_path": ".candidate-artifacts/portfolio-service.jar"}}
            evidence = viv.verify_candidate_image(
                REPO, policy, task_a_evidence, tag=tag,
                build_record_path=REPO / ".candidate-artifacts" / "image-verify-tmp" / "test-build-record.json",
            )
            self.assertEqual(evidence["label"], "LOCAL_PREPARATION")
            self.assertEqual(evidence["provenance"], "verified")
            self.assertTrue(evidence["hashes_equal"])
            self.assertEqual(evidence["staged_jar_sha256"], staged_sha)
            self.assertEqual(evidence["extracted_jar_sha256"], staged_sha)
            self.assertEqual(evidence["platform"].split("/")[0], "linux")
            self.assertTrue(evidence["runtime_base_digest"].startswith("mcr.microsoft.com/openjdk/jdk@sha256:"))
            self.assertIsNone(evidence["registry_manifest_digest"])  # never fabricated
        finally:
            failures = docker_rmi_all([tag])
            self.assertEqual(failures, [], f"docker rmi cleanup failed for: {failures}")


if __name__ == "__main__":
    unittest.main()
