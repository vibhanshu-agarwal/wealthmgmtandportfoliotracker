#!/usr/bin/env python3
"""R-C candidate image packaging evidence (Task 7.4 steps 6-8 / Task B, local preparation only).

Builds (or accepts an already-built) LOCAL development image from
`portfolio-service/Dockerfile.candidate`, extracts `/app.jar` from it, and asserts its SHA-256
equals the staged candidate JAR's -- proving the copy-only recipe genuinely ships the
graph-verified artifact unmodified, rather than trusting the Dockerfile's text.

This tool never builds a Task 7.3 release candidate, never logs in to or pushes to ACR, and never
resolves a registry manifest digest. Every result is marked `LOCAL_PREPARATION`; a missing
registry/smoke join must prevent any result here from being read as candidate-ready or attested.

Contract:

  - The staged JAR's identity is bound to a specific, successful Task A evidence bundle, not to
    whatever bytes are currently on disk. `resolve_staged_jar` requires the Task A `evidence`
    bundle's own recorded `stage.sha256` and rejects a staged file whose current hash differs from
    it -- catching the case where something replaced or regenerated the staged artifact after Task
    A's evidence was captured, which a bare "hash what's there now" check cannot see.

  - Hash equality between the staged JAR and the extracted image content is the proof, not the
    Dockerfile text. A recipe that silently recompiled, substituted, or corrupted the artifact fails
    this check regardless of what the Dockerfile claims to do.

  - The runtime base is resolved to an immutable digest *before* the build, and the build uses that
    digest-pinned reference (`repo@sha256:...`), never the floating tag -- so nothing can move the
    tag between resolution and build and cause the image to be built from a different base than the
    one recorded. (Empirically confirmed: BuildKit does not register a base image as a separately
    inspectable local image on its own -- `docker image inspect <base_ref>` on a floating tag used in
    a `FROM` line reports "No such image" until it is explicitly `docker pull`ed -- so resolution
    always pulls first.) The target platform is likewise passed explicitly to the build
    (`docker build --platform ...`), never left to the daemon's default.

  - Every identity-sensitive operation after the build -- extraction, platform inspection -- uses the
    immutable image ID captured once, right after the build (or, with `--skip-build`, captured fresh
    from the current tag before extracting), never the mutable tag string again. A tag can be
    retagged to point at a different image between two operations; an image ID cannot.

  - `--skip-build` never reports build inputs (base ref/digest, platform, recipe) as verified facts
    unless a build record this tool itself wrote (`write_build_record`) still matches the tag's
    *current* image ID. Otherwise the result's `provenance` is `"unverified"` and those fields are
    `null` with an explanatory `provenance_note` -- the hash-equality check still fully applies
    either way; only the *build inputs* become unverified, not the artifact-identity proof itself.

  - Local image identity and a registry manifest digest are different things and must never be
    conflated. `local_image_id` is explicitly local-only. A future registry-resolution step (Task
    7.4 step 8, separately owner-authorized) must record `registry_manifest_digest` and, if that
    digest is an image *index* (observed here: even a single-platform local build with the
    containerd image store produces an index-shaped local image ID), the specific platform manifest
    actually selected -- never assume an index digest alone identifies what a single-platform
    deployment target will pull.

  - Cleanup removes only the container this run created, in a `finally` block, so a failure midway
    (e.g. a hash mismatch) still cleans up rather than leaking a stopped container.

  - Fails closed throughout: a missing/invalid Task A evidence bundle, a staged-JAR identity
    mismatch, a missing staged JAR, an unresolvable base digest, a failed build, a missing
    `/app.jar` inside the built image, or a hash mismatch are all hard failures reporting the actual
    paths/hashes/IDs involved, never a silent default.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "scripts"))

from b1_candidate_evidence import (  # noqa: E402
    DEFAULT_POLICY_PATH,
    EvidenceError,
    load_policy,
    sha256_file,
)

DEFAULT_DOCKERFILE = REPO / "portfolio-service" / "Dockerfile.candidate"
DEFAULT_TAG = "wealth-portfolio-service:candidate-local-dev"
DEFAULT_PLATFORM = "linux/amd64"
DEFAULT_TASK_A_EVIDENCE_PATH = REPO / ".candidate-artifacts" / "evidence.json"
DEFAULT_BUILD_RECORD_PATH = REPO / ".candidate-artifacts" / "image-build-record.json"
CONTAINER_INTERNAL_JAR_PATH = "/app.jar"


def _run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, check=True, **kwargs)
    except subprocess.CalledProcessError as exc:
        raise EvidenceError(
            f"command failed ({' '.join(cmd)}): exit {exc.returncode}\n{exc.stderr}"
        ) from exc
    except FileNotFoundError as exc:
        raise EvidenceError(f"command not found: {cmd[0]!r} ({exc})") from exc


# --------------------------------------------------------------------------------------
# Docker primitives
# --------------------------------------------------------------------------------------


def docker_build(
    dockerfile: Path, context: Path, tag: str,
    build_args: dict[str, str] | None = None, platform: str | None = None,
    iidfile: Path | None = None,
) -> str | None:
    """Runs `docker build`. When `iidfile` is given, passes `--iidfile <path>` so Docker writes the
    resulting image's own ID directly to that file as part of the build itself, and returns it --
    this is the build's *output* identity, captured with no separate lookup step and therefore no
    window in which a concurrent retag of `tag` could substitute a different image. Re-reading
    `docker image inspect <tag>` after the fact is exactly the gap this avoids: `tag` is a mutable
    pointer that something else could repoint between the build finishing and that lookup running.
    Returns None when no `iidfile` is given.
    """
    if not dockerfile.is_file():
        raise EvidenceError(f"Dockerfile not found: {dockerfile}")
    if iidfile is not None and iidfile.exists():
        iidfile.unlink()
    cmd = ["docker", "build", "-f", str(dockerfile), "-t", tag]
    if platform:
        cmd += ["--platform", platform]
    if iidfile is not None:
        cmd += ["--iidfile", str(iidfile)]
    for key, value in (build_args or {}).items():
        cmd += ["--build-arg", f"{key}={value}"]
    cmd.append(str(context))
    _run(cmd)
    if iidfile is None:
        return None
    if not iidfile.is_file():
        raise EvidenceError(f"docker build did not write an --iidfile to {iidfile}")
    image_id = iidfile.read_text(encoding="utf-8").strip()
    if not image_id:
        raise EvidenceError(f"docker build wrote an empty --iidfile at {iidfile}")
    return image_id


def docker_image_field(ref: str, go_format: str) -> str:
    """`ref` should be an immutable image ID/digest wherever the caller has one -- a mutable tag is
    only appropriate before any identity has been captured yet (e.g. right after a build)."""
    result = _run(["docker", "image", "inspect", ref, "--format", go_format])
    return result.stdout.strip()


def resolve_base_digest(base_ref: str) -> str | None:
    """Best-effort: the resolved `repo@sha256:...` digest reference of a base image, via an
    explicit `docker pull` (a cache hit if BuildKit already fetched the same layers) followed by
    inspection. Returns None only if the base genuinely cannot be pulled/inspected (e.g. a
    locally-built-only base with no registry digest) -- record that absence rather than guessing.
    """
    try:
        _run(["docker", "pull", base_ref])
        result = _run(["docker", "image", "inspect", base_ref, "--format", "{{join .RepoDigests \",\"}}"])
    except EvidenceError:
        return None
    digests = [d for d in result.stdout.strip().split(",") if d]
    return digests[0] if digests else None


def resolve_pinned_base(base_ref: str) -> str:
    """Resolve `base_ref` to an immutable `repo@sha256:...` reference *before* any build uses it.
    A build must never proceed against an unpinned floating tag, whose target could move between
    resolution and the build itself and silently change what actually gets built."""
    digest = resolve_base_digest(base_ref)
    if digest is None:
        raise EvidenceError(
            f"could not resolve an immutable digest for base image {base_ref!r}; refusing to build "
            "against an unpinned floating tag"
        )
    return digest


def extract_file(image_ref: str, container_path: str, out_path: Path) -> None:
    """Create a (never-started) container from `image_ref` -- pass an immutable image ID, not a
    mutable tag, so a retag between identity capture and extraction cannot substitute a different
    image -- copy `container_path` out, then always remove the container, even if the copy fails,
    so a failed run leaves nothing behind."""
    container_name = f"b1-candidate-extract-{int(time.time() * 1000)}"
    _run(["docker", "create", "--name", container_name, image_ref])
    try:
        try:
            _run(["docker", "cp", f"{container_name}:{container_path}", str(out_path)])
        except EvidenceError as exc:
            raise EvidenceError(
                f"could not extract {container_path!r} from image {image_ref!r} -- the built image "
                f"does not contain the expected artifact at that path: {exc}"
            ) from exc
    finally:
        _run(["docker", "rm", "-f", container_name])


# --------------------------------------------------------------------------------------
# Task A evidence binding
# --------------------------------------------------------------------------------------


def load_task_a_evidence(path: Path) -> dict:
    """Load and validate the Task A evidence bundle. Image packaging must be bound to a specific,
    successful Task A run, not to whatever bytes happen to be sitting in .candidate-artifacts/
    right now. A failed bundle has a different, minimal shape
    ({"graph_verification_status": "FAIL", ...}) with no "stage" section at all."""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise EvidenceError(
            f"cannot read Task A evidence bundle {path}: {exc}. Run 'python -B "
            "scripts/b1_candidate_evidence.py evidence --out <path>' before packaging evidence."
        ) from exc
    if data.get("graph_verification_status") != "PASS":
        raise EvidenceError(
            f"Task A evidence bundle {path} is not a successful PASS "
            f"(graph_verification_status={data.get('graph_verification_status')!r}); image "
            "packaging requires a verified graph, not a failed or partial bundle"
        )
    stage = data.get("stage") or {}
    if not stage.get("sha256"):
        raise EvidenceError(
            f"Task A evidence bundle {path} has no stage.sha256 -- was it really produced by a "
            "successful `evidence` run?"
        )
    return data


def resolve_staged_jar(repo: Path, policy: dict, task_a_evidence: dict) -> tuple[Path, str]:
    """The staged JAR's identity is bound to what Task A actually recorded, not to whatever is
    currently on disk. A file swapped in after Task A's evidence was captured -- by another
    process, a stale artifact, or a bug -- is rejected here rather than silently trusted."""
    staged_path = repo / policy["staging"]["staged_path"]
    if not staged_path.is_file():
        raise EvidenceError(
            f"staged candidate JAR not found: {staged_path}. Run the Task A candidateVerification "
            "graph (b1_candidate_evidence.py) before packaging evidence."
        )
    recorded_sha = task_a_evidence["stage"]["sha256"]
    current_sha = sha256_file(staged_path)
    if current_sha != recorded_sha:
        raise EvidenceError(
            f"staged JAR at {staged_path} does not match Task A's recorded evidence: Task A "
            f"recorded {recorded_sha}, the file on disk now hashes to {current_sha} -- it was "
            "replaced, corrupted, or regenerated after Task A's evidence was captured. Re-run the "
            "Task A candidateVerification graph and evidence capture, in that order, immediately "
            "before packaging."
        )
    return staged_path, current_sha


# --------------------------------------------------------------------------------------
# Build-record provenance (for --skip-build)
# --------------------------------------------------------------------------------------


def write_build_record(
    path: Path, *, tag: str, image_id: str, base_ref: str, base_digest: str,
    platform: str, dockerfile: Path,
) -> dict:
    record = {
        "tag": tag,
        "image_id": image_id,
        "base_ref": base_ref,
        "base_digest": base_digest,
        "platform": platform,
        "dockerfile_path": str(dockerfile),
        "dockerfile_sha256": sha256_file(dockerfile),
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    return record


def load_build_record(path: Path) -> dict | None:
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


# --------------------------------------------------------------------------------------
# Verification
# --------------------------------------------------------------------------------------


def assert_extracted_jar_matches(extracted_path: Path, expected_sha256: str) -> str:
    if not extracted_path.is_file():
        raise EvidenceError(f"extracted artifact not found on host: {extracted_path}")
    actual = sha256_file(extracted_path)
    if actual != expected_sha256:
        raise EvidenceError(
            f"extracted {CONTAINER_INTERNAL_JAR_PATH} SHA mismatch: image produced {actual}, "
            f"staged candidate JAR is {expected_sha256} -- the built image does not package the "
            "graph-verified artifact"
        )
    return actual


def assert_platform_matches(actual: str, expected: str | None) -> None:
    """`expected` is None only when no platform was requested/recorded to compare against (an
    unverified --skip-build with no matching build record) -- there is nothing to validate in that
    case. Whenever a platform WAS requested or recorded, the actual image must match it exactly;
    this must run before the caller treats the result as verified or writes a build record for it,
    not just be recorded alongside a mismatch for a reader to notice later."""
    if expected is not None and actual != expected:
        raise EvidenceError(
            f"platform mismatch: requested/recorded platform {expected!r}, but the image actually "
            f"inspects as {actual!r} -- refusing to accept packaging evidence for the wrong platform"
        )


def verify_candidate_image(
    repo: Path,
    policy: dict,
    task_a_evidence: dict,
    *,
    tag: str = DEFAULT_TAG,
    dockerfile: Path = DEFAULT_DOCKERFILE,
    base_ref: str = "mcr.microsoft.com/openjdk/jdk:21-mariner",
    platform: str = DEFAULT_PLATFORM,
    skip_build: bool = False,
    build_record_path: Path = DEFAULT_BUILD_RECORD_PATH,
    workdir: Path | None = None,
) -> dict:
    staged_path, staged_sha = resolve_staged_jar(repo, policy, task_a_evidence)

    tmp_dir = workdir or (repo / ".candidate-artifacts" / "image-verify-tmp")
    tmp_dir.mkdir(parents=True, exist_ok=True)

    provenance_note: str | None = None
    if not skip_build:
        pinned_base = resolve_pinned_base(base_ref)  # resolved BEFORE the build uses it
        # --iidfile captures the build's own output identity as part of the build itself -- no
        # separate `docker image inspect <tag>` lookup afterward, and therefore no window in which
        # a concurrent retag of `tag` could substitute a different image into what we think we just
        # built. A unique per-run filename avoids collisions with any other run using this tmp_dir.
        iidfile = tmp_dir / f"iid-{int(time.time() * 1000)}.txt"
        image_id = docker_build(
            dockerfile, repo, tag, build_args={"RUNTIME_BASE": pinned_base}, platform=platform,
            iidfile=iidfile,
        )
        actual_platform = docker_image_field(image_id, "{{.Os}}/{{.Architecture}}")
        assert_platform_matches(actual_platform, platform)  # before accepting/recording anything
        write_build_record(
            build_record_path, tag=tag, image_id=image_id, base_ref=base_ref,
            base_digest=pinned_base, platform=platform, dockerfile=dockerfile,
        )
        provenance = "verified"
        recorded_base_ref, recorded_base_digest, recorded_platform = base_ref, pinned_base, platform
        recipe_value = str(dockerfile.relative_to(repo)) if dockerfile.is_relative_to(repo) else str(dockerfile)
    else:
        image_id = docker_image_field(tag, "{{.Id}}")  # current identity behind the tag, right now
        actual_platform = docker_image_field(image_id, "{{.Os}}/{{.Architecture}}")
        record = load_build_record(build_record_path)
        if record is not None and record.get("tag") == tag and record.get("image_id") == image_id:
            assert_platform_matches(actual_platform, record["platform"])  # before "verified"
            provenance = "verified"
            recorded_base_ref = record["base_ref"]
            recorded_base_digest = record["base_digest"]
            recorded_platform = record["platform"]
            recipe_value = record["dockerfile_path"]
        else:
            provenance = "unverified"
            if record is None:
                provenance_note = (
                    f"no build record found at {build_record_path}; --skip-build was passed "
                    "without a prior recorded build of this tag by this tool"
                )
            else:
                provenance_note = (
                    f"tag {tag!r} currently resolves to image {image_id}, but the last recorded "
                    f"build for this tag was image {record.get('image_id')} -- it was rebuilt, "
                    "retagged, or the record is stale"
                )
            recorded_base_ref = recorded_base_digest = recorded_platform = recipe_value = None
            # No recorded_platform to compare against -- nothing to validate for an unverified run.

    # Every identity-sensitive step from here on uses the immutable image_id captured above (via
    # --iidfile when built here, or read once before any provenance decision when skipping the
    # build), never the tag again -- a retag in between cannot substitute a different image.
    extracted_path = tmp_dir / "extracted-app.jar"
    if extracted_path.exists():
        extracted_path.unlink()

    extract_file(image_id, CONTAINER_INTERNAL_JAR_PATH, extracted_path)
    extracted_sha = assert_extracted_jar_matches(extracted_path, staged_sha)

    result = {
        "label": "LOCAL_PREPARATION",
        "provenance": provenance,
        "recipe": recipe_value,
        "local_image_id": image_id,
        "platform": actual_platform,
        "requested_platform": recorded_platform,
        "runtime_base_ref": recorded_base_ref,
        "runtime_base_digest": recorded_base_digest,
        "task_a_evidence_head_sha": task_a_evidence.get("run", {}).get("head_sha"),
        "staged_jar_path": str(staged_path),
        "staged_jar_sha256": staged_sha,
        "extracted_jar_sha256": extracted_sha,
        "hashes_equal": True,
        # Deliberately absent/None until a separately owner-authorized registry push and pull
        # occurs -- this tool never resolves or fabricates these.
        "registry_manifest_digest": None,
        "registry_manifest_platform": None,
    }
    if provenance_note:
        result["provenance_note"] = provenance_note
    return result


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=str(REPO), help="repository root (default: this checkout)")
    parser.add_argument("--policy", default=str(DEFAULT_POLICY_PATH), help="path to b1-candidate-policy.json")
    parser.add_argument(
        "--task-a-evidence", default=str(DEFAULT_TASK_A_EVIDENCE_PATH),
        help="path to the successful Task A evidence bundle (b1_candidate_evidence.py evidence --out)",
    )
    parser.add_argument("--tag", default=DEFAULT_TAG, help="local image tag to build/inspect")
    parser.add_argument("--dockerfile", default=str(DEFAULT_DOCKERFILE), help="path to Dockerfile.candidate")
    parser.add_argument(
        "--runtime-base", default="mcr.microsoft.com/openjdk/jdk:21-mariner",
        help="base image reference to resolve to an immutable digest and build against",
    )
    parser.add_argument("--platform", default=DEFAULT_PLATFORM, help="explicit target platform for the build")
    parser.add_argument("--skip-build", action="store_true", help="assume --tag is already built")
    parser.add_argument(
        "--build-record", default=str(DEFAULT_BUILD_RECORD_PATH),
        help="path to read/write this tool's own build-provenance record",
    )
    parser.add_argument("--out", default=None, help="optional path to write the evidence JSON to")
    args = parser.parse_args(argv)

    repo = Path(args.repo).resolve()
    policy = load_policy(Path(args.policy))

    try:
        task_a_evidence = load_task_a_evidence(Path(args.task_a_evidence))
        evidence = verify_candidate_image(
            repo, policy, task_a_evidence,
            tag=args.tag,
            dockerfile=Path(args.dockerfile),
            base_ref=args.runtime_base,
            platform=args.platform,
            skip_build=args.skip_build,
            build_record_path=Path(args.build_record),
        )
    except EvidenceError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        if args.out:
            Path(args.out).write_text(
                json.dumps({"label": "LOCAL_PREPARATION", "hashes_equal": False, "error": str(exc)}, indent=2) + "\n",
                encoding="utf-8",
            )
        return 1

    if args.out:
        Path(args.out).write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        print(f"wrote image-packaging evidence to {args.out}")
    print(
        f"candidate image packaging: PASS (LOCAL_PREPARATION, provenance={evidence['provenance']}) "
        f"-- local_image_id={evidence['local_image_id']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
