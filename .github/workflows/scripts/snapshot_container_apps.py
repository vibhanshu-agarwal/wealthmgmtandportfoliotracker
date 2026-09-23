#!/usr/bin/env python3
"""Snapshot and compare Azure Container App identity for Wave P non-interference.

Captures revision name, image reference and ingress traffic for each backend
app, plus the market-data-refresh-job image. Unselected targets must be
byte-identical before and after a scoped deploy; selected targets must be
updated to the committing SHA.

A frontend-only deploy (Wave 10.2 Step B) selects no backend at all, so every target is
unselected: ``snapshot --require-complete`` refuses a before-snapshot that does not
positively describe all five targets, and ``assert-unchanged`` requires all five to be
byte-identical afterwards. Both only ever issue ``containerapp [job] show`` reads.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import re
from pathlib import Path
from typing import Any, Callable

KNOWN_SERVICES = (
    "api-gateway",
    "portfolio-service",
    "market-data-service",
    "insight-service",
)
REFRESH_JOB = "market-data-refresh-job"
ALL_TARGETS = (*KNOWN_SERVICES, REFRESH_JOB)
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")

def _validate_selected(selected: list[str]) -> None:
    if not selected or len(selected) != len(set(selected)) or not set(selected).issubset(set(KNOWN_SERVICES)):
        raise ValueError("selected services must be nonempty, unique, and known")

def _repository(image: str) -> str:
    ref = image.split("@", 1)[0]
    slash = ref.rfind("/")
    colon = ref.rfind(":")
    return ref[:colon] if colon > slash else ref

RunAz = Callable[[list[str]], subprocess.CompletedProcess[str]]


def _run_az(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["az", *args],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )


def _canonical(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: _canonical(value[key]) for key in sorted(value)}
    if isinstance(value, list):
        return [_canonical(item) for item in value]
    return value


def _app_identity(name: str, resource_group: str, run_az: RunAz) -> dict[str, Any]:
    result = run_az(
        [
            "containerapp",
            "show",
            "--name",
            name,
            "--resource-group",
            resource_group,
            "--query",
            "{revision:properties.latestRevisionName,image:properties.template.containers[0].image,traffic:properties.configuration.ingress.traffic}",
            "-o",
            "json",
        ]
    )
    if result.returncode != 0:
        return {"missing": True, "error": (result.stderr or result.stdout).strip()}
    payload = json.loads(result.stdout)
    return _canonical(payload)


def _job_identity(resource_group: str, run_az: RunAz) -> dict[str, Any]:
    result = run_az(
        [
            "containerapp",
            "job",
            "show",
            "--name",
            REFRESH_JOB,
            "--resource-group",
            resource_group,
            "--query",
            "{image:properties.template.containers[0].image}",
            "-o",
            "json",
        ]
    )
    if result.returncode != 0:
        return {"missing": True, "error": (result.stderr or result.stdout).strip()}
    return _canonical(json.loads(result.stdout))


def capture(resource_group: str, run_az: RunAz = _run_az) -> dict[str, Any]:
    snapshot: dict[str, Any] = {
        name: _app_identity(name, resource_group, run_az) for name in KNOWN_SERVICES
    }
    snapshot[REFRESH_JOB] = _job_identity(resource_group, run_az)
    return snapshot


def expected_images(
    before: dict[str, Any],
    selected: list[str],
    git_sha: str | None = None,
    requested_digest: str | None = None,
    digest_manifest: dict[str, str] | None = None,
) -> dict[str, str]:
    """{service: image reference this run is expected to have deployed}.

    Empty when the caller supplied neither a digest nor a git SHA — there is then nothing
    to check the deployed image against, which ``compare`` reports as an error and the
    revision-binding proof treats as "not applicable" rather than inventing an expectation.
    """
    digest = (requested_digest or "").strip() or None
    sha = (git_sha or "").strip() or None
    mode = (
        "manifest" if digest_manifest is not None
        else "digest" if digest
        else "git-sha" if sha
        else None
    )
    if not mode:
        return {}
    images: dict[str, str] = {}
    for name in selected:
        repository = _repository(str(before.get(name, {}).get("image", "")))
        if mode == "manifest":
            images[name] = repository + "@" + digest_manifest[name]
        elif mode == "digest":
            images[name] = repository + "@" + digest
        else:
            images[name] = repository + ":" + sha
    return images


def compare(
    before: dict[str, Any],
    after: dict[str, Any],
    selected: list[str],
    git_sha: str | None = None,
    requested_digest: str | None = None,
    digest_manifest: dict[str, str] | None = None,
) -> list[str]:
    _validate_selected(selected)
    errors: list[str] = []
    selected_set = set(selected)
    expected = expected_images(before, selected, git_sha, requested_digest, digest_manifest)
    mode = bool(expected)
    for name in KNOWN_SERVICES:
        if name in selected_set:
            image = str(after.get(name, {}).get("image", ""))
            if not mode:
                errors.append(
                    f"selected {name} image {image!r} cannot be checked: "
                    "neither digest nor git sha was provided"
                )
            elif image != expected[name]:
                errors.append(
                    f"selected {name} image {image!r} does not equal expected digest/image {expected[name]!r}"
                )
        elif before.get(name) != after.get(name):
            errors.append(
                f"unselected {name} changed: {json.dumps(before.get(name))} -> {json.dumps(after.get(name))}"
            )

    if "market-data-service" in selected_set:
        after_job = after.get(REFRESH_JOB) or {}
        if after_job.get("missing"):
            errors.append(f"selected {REFRESH_JOB} is missing")
        elif not mode:
            errors.append(f"selected {REFRESH_JOB} cannot be verified without an expected image")
        elif str(after_job.get("image", "")) != expected["market-data-service"]:
            errors.append(f"selected {REFRESH_JOB} image does not equal expected {expected['market-data-service']!r}")
    elif before.get(REFRESH_JOB) != after.get(REFRESH_JOB):
        errors.append(
            f"unselected {REFRESH_JOB} changed: {json.dumps(before.get(REFRESH_JOB))} -> {json.dumps(after.get(REFRESH_JOB))}"
        )
    return errors


def _traffic_problem(traffic: Any) -> str | None:
    """Why an app's ingress traffic is not positively described, or None if it is.

    Live single-revision apps report ``[{"latestRevision": true, "weight": 100}]`` with no
    revision name, so an entry may select its revision either way. What is required is a
    non-empty list of well-formed entries whose weights total exactly 100: anything absent,
    null, malformed or partial says nothing about where requests are routed.
    """
    if not isinstance(traffic, list) or not traffic:
        return "has no ingress traffic"
    total = 0
    for entry in traffic:
        if not isinstance(entry, dict):
            return "has a malformed traffic entry"
        weight = entry.get("weight")
        if isinstance(weight, bool) or not isinstance(weight, int) or not 0 <= weight <= 100:
            return "has a traffic entry without an integer weight between 0 and 100"
        revision = entry.get("revisionName")
        names_revision = isinstance(revision, str) and bool(revision.strip())
        if entry.get("latestRevision") is not True and not names_revision:
            return "has a traffic entry that selects no revision"
        total += weight
    if total != 100:
        return f"has traffic weights totalling {total}, not 100"
    return None


def _unproven_targets(snapshot: Any) -> dict[str, str]:
    """{target: reason} for every target the snapshot does not positively describe.

    An absent key, a ``missing`` payload (the app or job could not be read), a payload with
    no image/revision (every field null, e.g. if the ``--query`` shape drifted) or an app
    whose ingress traffic is not positively described proves nothing. It must not count as
    "unchanged" merely because both sides are equally blank, which is what
    ``before.get(name) != after.get(name)`` would conclude.
    """
    if not isinstance(snapshot, dict):
        return {name: "snapshot is not a JSON object" for name in ALL_TARGETS}
    problems: dict[str, str] = {}
    for name in ALL_TARGETS:
        entry = snapshot.get(name)
        if not isinstance(entry, dict) or not entry:
            problems[name] = "has no entry"
        elif entry.get("missing"):
            problems[name] = f"could not be read: {entry.get('error') or 'unknown error'}"
        else:
            # The refresh Job has no ingress and its query returns only its image; the apps
            # also carry a revision and ingress traffic.
            is_job = name == REFRESH_JOB
            required = ("image",) if is_job else ("revision", "image")
            reasons: list[str] = []
            blank = [
                field
                for field in required
                if not (isinstance(entry.get(field), str) and entry[field].strip())
            ]
            if blank:
                reasons.append(f"has no usable {' or '.join(blank)}")
            traffic_problem = None if is_job else _traffic_problem(entry.get("traffic"))
            if traffic_problem:
                reasons.append(traffic_problem)
            if reasons:
                problems[name] = "; ".join(reasons)
    return problems


def compare_unchanged(before: dict[str, Any], after: dict[str, Any]) -> list[str]:
    """Every backend app and the refresh job must be byte-identical before and after."""
    unproven = {"before": _unproven_targets(before), "after": _unproven_targets(after)}
    errors = [
        f"{label} snapshot: {name} {reason}"
        for label, problems in unproven.items()
        for name, reason in problems.items()
    ]
    unreadable = set(unproven["before"]) | set(unproven["after"])
    for name in ALL_TARGETS:
        if name not in unreadable and before[name] != after[name]:
            errors.append(
                f"{name} changed during a frontend-only deploy: "
                f"{json.dumps(before[name])} -> {json.dumps(after[name])}"
            )
    return errors


def _ready_revision_binding(name: str, resource_group: str, run_az: RunAz) -> dict[str, Any]:
    """Read the app's newest *ready* revision, then that revision's own detail.

    Two reads, because they live on different resources: ``latestReadyRevisionName`` is a
    containerApp property, while the image, active flag, provisioning state and traffic
    weight are properties of the revision. Both are ``show`` reads and mutate nothing.
    """
    app = run_az(
        [
            "containerapp",
            "show",
            "--name",
            name,
            "--resource-group",
            resource_group,
            "--query",
            "{readyRevision:properties.latestReadyRevisionName}",
            "-o",
            "json",
        ]
    )
    if app.returncode != 0:
        return {"missing": True, "error": (app.stderr or app.stdout).strip()}
    ready = json.loads(app.stdout).get("readyRevision")
    ready = ready.strip() if isinstance(ready, str) else ""
    if not ready:
        # No revision has become ready. Left for revision_binding_errors to report, so the
        # failure reads the same whether the field was absent, null or blank.
        return {"readyRevision": ""}
    revision = run_az(
        [
            "containerapp",
            "revision",
            "show",
            "--name",
            name,
            "--resource-group",
            resource_group,
            "--revision",
            ready,
            "--query",
            "{revisionImage:properties.template.containers[0].image,"
            "active:properties.active,"
            "provisioningState:properties.provisioningState,"
            "trafficWeight:properties.trafficWeight}",
            "-o",
            "json",
        ]
    )
    if revision.returncode != 0:
        return {"missing": True, "error": (revision.stderr or revision.stdout).strip()}
    return _canonical({"readyRevision": ready, **json.loads(revision.stdout)})


def capture_bindings(
    resource_group: str, selected: list[str], run_az: RunAz = _run_az
) -> dict[str, Any]:
    """Revision bindings for the selected apps only. Unselected apps are never read."""
    _validate_selected(selected)
    return {
        name: _ready_revision_binding(name, resource_group, run_az) for name in selected
    }


def _traffic_targets_revision(traffic: Any, ready_revision: str, latest_revision: str) -> bool:
    """Whether the ingress traffic map sends all 100% to ``ready_revision``.

    Stricter than :func:`_traffic_problem`, which only asks whether the map is well formed.
    Entries carrying zero weight route nothing and are ignored. An entry either names a
    revision outright, or sets ``latestRevision``, which resolves to the app's
    ``latestRevisionName`` — the newest revision, which is not necessarily the newest
    *ready* one. When those differ, ``latestRevision`` traffic is not proven to reach the
    revision this run made ready, so it fails closed.
    """
    if not isinstance(traffic, list) or not traffic:
        return False
    total = 0
    for entry in traffic:
        if not isinstance(entry, dict):
            return False
        weight = entry.get("weight")
        if isinstance(weight, bool) or not isinstance(weight, int) or not 0 <= weight <= 100:
            return False
        if weight == 0:
            continue
        revision = entry.get("revisionName")
        names_revision = isinstance(revision, str) and bool(revision.strip())
        latest = entry.get("latestRevision") is True
        if names_revision and latest:
            # `revisionName` and `latestRevision` are distinct routing forms. An entry
            # setting both states two things at once, and the one that happens to be read
            # first must not decide the outcome: reject the contradiction itself, whether
            # or not the two readings currently agree.
            return False
        if names_revision:
            if revision.strip() != ready_revision:
                return False
        elif latest:
            if latest_revision != ready_revision:
                return False
        else:
            return False
        total += weight
    return total == 100


def revision_binding_errors(
    before: dict[str, Any],
    after: dict[str, Any],
    bindings: dict[str, Any],
    selected: list[str],
    expected_images: dict[str, str],
) -> list[str]:
    """Bind this run's digest to the revision that actually holds traffic.

    ``compare`` checks the app's *template* image, and ``--require-complete`` checks that
    the traffic map is well formed. Neither shows that the revision serving requests is the
    one this run made ready. Azure keeps traffic on the previous revision until the new one
    is ready, and with ``min_replicas = 0`` a healthy app sits idle with no replicas, so
    ``replicas`` and ``runningState`` are deliberately *not* asserted: requiring either
    would fail a correct scale-to-zero deploy.

    The revision under test is identified from this run's own evidence — the
    ``latestReadyRevisionName`` read after the deploy, checked against the
    ``latestRevisionName`` read before it — never from a caller-supplied name.
    """
    _validate_selected(selected)
    errors: list[str] = []
    for name in selected:
        binding = bindings.get(name)
        if not isinstance(binding, dict) or not binding:
            errors.append(f"{name} has no revision binding")
            continue
        if binding.get("missing"):
            errors.append(
                f"{name} revision binding could not be read: "
                f"{binding.get('error') or 'unknown error'}"
            )
            continue
        ready = binding.get("readyRevision")
        ready = ready.strip() if isinstance(ready, str) else ""
        if not ready:
            errors.append(f"{name} has no latestReadyRevisionName")
            continue

        expected = str(expected_images.get(name, "")).strip()
        before_entry = after_entry = None
        before_entry = before.get(name) if isinstance(before.get(name), dict) else {}
        after_entry = after.get(name) if isinstance(after.get(name), dict) else {}
        before_revision = str(before_entry.get("revision", "") or "").strip()
        before_image = str(before_entry.get("image", "") or "").strip()
        if not before_revision:
            errors.append(
                f"{name} before snapshot does not name a prior revision, so this run's "
                "before/after evidence cannot show that a new revision became ready"
            )
            continue
        if ready == before_revision and before_image != expected:
            errors.append(
                f"{name} did not produce a new ready revision: {ready!r} was already the "
                f"latest revision before the deploy, and its image {before_image!r} is not "
                f"the expected {expected!r}"
            )
            continue

        latest_revision = str(after_entry.get("revision", "") or "").strip()
        if not _traffic_targets_revision(after_entry.get("traffic"), ready, latest_revision):
            errors.append(
                f"{name} does not route 100% of traffic to ready revision {ready!r}: "
                f"{json.dumps(after_entry.get('traffic'))}"
            )
            continue

        image = str(binding.get("revisionImage", "") or "").strip()
        if image != expected:
            errors.append(
                f"{name} ready revision image {image!r} does not equal expected {expected!r}"
            )
        if binding.get("active") is not True:
            errors.append(f"{name} ready revision is not active: {binding.get('active')!r}")
        state = binding.get("provisioningState")
        if state != "Provisioned":
            errors.append(
                f"{name} ready revision provisioning state is {state!r}, not 'Provisioned'"
            )
        weight = binding.get("trafficWeight")
        if isinstance(weight, bool) or not isinstance(weight, int) or weight != 100:
            errors.append(f"{name} ready revision traffic weight is {weight!r}, not 100")
    return errors


def aggregate_digests(digest_root: str, selected: list[str], output: str | None) -> dict[str, str]:
    root = os.path.abspath(digest_root)
    selected_set = set(selected)
    if len(selected_set) != len(selected) or not selected_set:
        raise ValueError("selected services must be non-empty and unique")
    if not selected_set.issubset(set(KNOWN_SERVICES)):
        raise ValueError("selected services contain unknown entries")
    expected = selected_set
    actual = {entry.name for entry in os.scandir(root) if entry.is_dir()}
    if actual != expected:
        raise ValueError(f"digest service set mismatch: expected {sorted(expected)}, found {sorted(actual)}")
    manifest: dict[str, str] = {}
    for service in selected:
        files = [entry for entry in os.scandir(os.path.join(root, service)) if entry.is_file()]
        if len(files) != 1 or files[0].name != "digest.txt":
            raise ValueError(f"{service} must contain exactly one digest.txt")
        with open(files[0].path, encoding="utf-8") as handle:
            digest = handle.read().strip()
        if not DIGEST_RE.fullmatch(digest):
            raise ValueError(f"invalid lowercase digest for {service}")
        manifest[service] = digest
    if output:
        with open(output, "w", encoding="utf-8") as handle:
            json.dump(manifest, handle, sort_keys=True, indent=2)
            handle.write("\n")
    return manifest

def normalize_digest_artifacts(download_root: str, staging_root: str, selected: list[str], attempt: str) -> None:
    _validate_selected(selected)
    source = os.path.abspath(download_root)
    stage = os.path.abspath(staging_root)
    if source == stage:
        raise ValueError("download and staging roots must be distinct")
    expected = {"service-digest-" + service for service in selected}
    actual = {entry.name for entry in os.scandir(source) if entry.is_dir()}
    if actual != expected:
        raise ValueError("digest artifact set mismatch; Re-run all jobs")
    os.makedirs(stage, exist_ok=True)
    for service in selected:
        directory = os.path.join(source, "service-digest-" + service)
        digest_file = os.path.join(directory, "digest.txt")
        marker_file = os.path.join(directory, "run-attempt.txt")
        if not os.path.isfile(digest_file) or not os.path.isfile(marker_file):
            raise ValueError("missing digest artifact marker; Re-run all jobs")
        if Path(marker_file).read_text(encoding="utf-8").strip() != str(attempt):
            raise ValueError("stale digest artifact; Re-run all jobs")
        files = {entry.name for entry in os.scandir(directory) if entry.is_file()}
        if files != {"digest.txt", "run-attempt.txt"}:
            raise ValueError("invalid digest artifact contents; Re-run all jobs")
        digest = Path(digest_file).read_text(encoding="utf-8").strip()
        if not DIGEST_RE.fullmatch(digest):
            raise ValueError("invalid digest artifact; Re-run all jobs")
        target = os.path.join(stage, service)
        os.makedirs(target, exist_ok=True)
        with open(os.path.join(target, "digest.txt"), "w", encoding="utf-8") as handle:
            handle.write(digest + "\n")


def validate_manifest(manifest: dict[str, str], selected: list[str]) -> dict[str, str]:
    _validate_selected(selected)
    if set(manifest) != set(selected):
        raise ValueError("manifest keys must exactly equal unique selected services")
    for service, digest in manifest.items():
        if not DIGEST_RE.fullmatch(digest):
            raise ValueError(f"invalid digest for {service}")
    return manifest


def load_manifest_text(text: str, selected: list[str]) -> dict[str, str]:
    def reject_duplicates(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate manifest key")
            result[key] = value
        return result
    return validate_manifest(json.loads(text, object_pairs_hook=reject_duplicates), selected)


def _write_output(name: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        if "\n" in value:
            handle.write(f"{name}<<EOF\n{value}\nEOF\n")
        else:
            handle.write(f"{name}={value}\n")


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "command",
        choices=("snapshot", "compare", "assert-unchanged", "aggregate-digests", "normalize-artifacts"),
    )
    parser.add_argument("--before", default="")
    # snapshot only: fail unless all five targets were read. Off by default so scoped
    # mode's existing snapshot step behaves exactly as before.
    parser.add_argument("--require-complete", action="store_true")
    parser.add_argument("--selected", default="[]")
    # Empty default on purpose: do not inherit GITHUB_SHA from the environment.
    # Digest mode omits --git-sha; falling back to the commit SHA would let a
    # rebuild pass the selected-app assertion.
    parser.add_argument("--git-sha", default="")
    parser.add_argument("--requested-digest", default="")
    parser.add_argument("--digest-root", default="")
    parser.add_argument("--output", default="")
    parser.add_argument("--digest-manifest", default="")
    parser.add_argument("--staging-root", default="")
    parser.add_argument("--run-attempt", default="")
    return parser


def main() -> int:
    args = _parser().parse_args()

    if args.command == "aggregate-digests":
        selected = json.loads(args.selected)
        manifest = aggregate_digests(args.digest_root, selected, args.output or None)
        print(json.dumps(manifest, indent=2, sort_keys=True))
        return 0
    if args.command == "normalize-artifacts":
        normalize_digest_artifacts(args.digest_root, args.staging_root, json.loads(args.selected), args.run_attempt)
        return 0

    resource_group = os.environ.get("AZURE_RG", "")
    if not resource_group:
        print("::error::AZURE_RG is required", file=sys.stderr)
        return 1

    if args.command == "snapshot":
        snapshot = capture(resource_group)
        print(json.dumps(snapshot, indent=2))
        if args.require_complete:
            unproven = _unproven_targets(snapshot)
            if unproven:
                for name, reason in unproven.items():
                    print(f"::error::snapshot: {name} {reason}", file=sys.stderr)
                return 1
        _write_output("snapshot", json.dumps(snapshot, separators=(",", ":")))
        return 0

    if args.command == "assert-unchanged":
        try:
            before_snapshot = json.loads(args.before)
        except json.JSONDecodeError as exc:
            print(f"::error::--before is not valid JSON: {exc}", file=sys.stderr)
            return 1
        if not isinstance(before_snapshot, dict):
            print("::error::--before must be a JSON object snapshot", file=sys.stderr)
            return 1
        after_snapshot = capture(resource_group)
        errors = compare_unchanged(before_snapshot, after_snapshot)
        print(json.dumps({"after": after_snapshot, "errors": errors}, indent=2))
        if errors:
            for error in errors:
                print(f"::error::{error}", file=sys.stderr)
            return 1
        print("Non-interference proof passed.")
        return 0

    before = json.loads(args.before)
    selected = json.loads(args.selected)
    _validate_selected(selected)
    manifest = None
    if args.digest_manifest:
        with open(args.digest_manifest, encoding="utf-8") as handle:
            manifest = load_manifest_text(handle.read(), selected)
    after = capture(resource_group)
    errors = compare(
        before,
        after,
        selected,
        git_sha=args.git_sha or None,
        requested_digest=args.requested_digest or None,
        digest_manifest=manifest,
    )
    # Bind this run's digest to the revision actually serving requests. `compare` above
    # only reads the app's template, which names an image without showing that the
    # revision carrying it holds the traffic.
    expected = expected_images(
        before,
        selected,
        args.git_sha or None,
        args.requested_digest or None,
        manifest,
    )
    bindings: dict[str, Any] = {}
    if expected:
        bindings = capture_bindings(resource_group, selected)
        errors = errors + revision_binding_errors(
            before, after, bindings, selected, expected
        )
    print(json.dumps({"after": after, "bindings": bindings, "errors": errors}, indent=2))
    if errors:
        for error in errors:
            print(f"::error::{error}", file=sys.stderr)
        return 1
    print("Non-interference proof passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
