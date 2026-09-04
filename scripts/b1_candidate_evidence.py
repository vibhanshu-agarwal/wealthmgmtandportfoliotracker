#!/usr/bin/env python3
"""B1 R-C candidate verification-graph evidence (Task 7.4/7.5, tasks.md:1095-1190).

Two entry points, matching the kickoff's own staging requirement ("Manifest validation must
succeed before staging; invoking staging alone must not bypass verification"). Both require the
same `mark`-produced run-start marker and share every pre-staging check (manifest/floor/discovery,
report and bootJar freshness, source-identity drift) via `_pre_stage_evidence` -- the gate does not
trust anything the final bundle alone verifies:

  - `manifest-check` runs *inside* the Gradle graph, as the `candidateManifestValidation` task
    between `candidateVerification` and `prepareCandidateArtifact` -- a nonzero exit here (a floor
    failure, an unreported suite, a stale report, or a bootJar Gradle left untouched) fails the
    build before any artifact is staged.
  - `evidence` runs *after* the whole `./gradlew :portfolio-service:candidateVerification
    :portfolio-service:prepareCandidateArtifact --rerun-tasks --no-build-cache` invocation, repeats
    those same checks, and additionally binds the staged copy's SHA to the bootJar's -- the one
    check that is only meaningful once staging has actually happened.

Contract:

  - The manifest is built from **all** classes present in both JUnit XML report sets (`test` and
    `integrationTest`), never from a `--tests` selection. A report directory that is missing or
    contains zero report files is a hard failure -- it looks exactly like a filtered, excluded, or
    NO-SOURCE task, and neither is "zero classes, evidence-empty".

  - Three floor-acceptance failure conditions (tasks.md:1178-1180): a task reporting zero tests; a
    required report-class pattern (scripts/b1-candidate-policy.json:candidate_floor) absent from the
    manifest; a required class present with **no non-skipped** test case.

  - Discovery reconciliation (tasks.md:1182-1186): every B1-added/modified `*Test.java`/`*IT.java`
    file under portfolio-service (`git diff --no-renames` against the pinned B1-base commit) must
    have a corresponding class in the manifest, unless explicitly named in the policy's
    `helper_class_allowlist`. A suite that was written and then excluded or mis-tagged surfaces here.
    `--no-renames` matters: `--name-only` never prints the old side of a detected rename, so a
    renamed test file would otherwise vanish from discovery under neither its old nor new path.

  - The B1-base commit (R4/GC.5) is a reviewed policy input, not a free-form argument: both entry
    points default to `b1-candidate-policy.json`'s pinned `b1_base_commit.sha`, and an explicit
    `--base-sha` is accepted only when it matches that pinned value exactly -- never a silent
    per-invocation override of the guard interval.

  - Freshness (`--since-marker`): every report file *and* the resolved bootJar archive must have
    been written at/after the recorded `mark` epoch -- a bootJar Gradle considered UP-TO-DATE (or a
    stale file dropped in by hand) fails even if the JUnit reports next to it are fresh, since
    SHA-equality between bootJar and its staged copy alone only proves the *copy* was faithful, not
    that this run produced the source file being copied.

  - Source identity is content-addressed, not status-text-addressed: `mark` requires a clean,
    fully-committed tree by default (tasks.md 7.4's "one immutable checkout"); `evidence` recomputes
    HEAD plus a content hash of every changed/untracked path and compares it to the marker. Hashing
    `git status` text alone would miss an edit to an *already-dirty* tracked file, because its status
    category (` M path`) reads identically before and after -- only the file's own bytes change. A
    separate, clearly-labelled `LOCAL_DEV` mode (`mark --allow-dirty`) exists for local iteration; it
    still gets the same content-addressed check, and its evidence is never `candidate_ready`.

  - Any failure or error anywhere in the parsed manifest fails closed, not just in floor suites.
    Gradle's optional merged-rerun XML can carry `<flakyFailure>`/`<rerunFailure>` elements inside an
    otherwise "passing" `<testcase>`; those are treated as failures here rather than hidden.

  - Suite root counters (`tests`/`skipped`/`failures`/`errors`) are reconciled against the actual
    `<testcase>` children rather than trusted blindly -- a mismatch is malformed-report evidence, not
    a class with an unusual shape.

Fails closed throughout: every ambiguous or missing input is an `EvidenceError`, never a default.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
DEFAULT_POLICY_PATH = REPO / "scripts" / "b1-candidate-policy.json"
# Under .candidate-artifacts/ (gitignored) so `mark` -> Gradle -> `evidence` share one path by
# default without the Gradle task having to know or pass it explicitly.
DEFAULT_MARKER_PATH = REPO / ".candidate-artifacts" / "run-start.marker"


class EvidenceError(ValueError):
    """Raised on any fail-closed check failure: malformed input, missing evidence, policy breach."""


# --------------------------------------------------------------------------------------
# Policy
# --------------------------------------------------------------------------------------


def load_policy(path: Path = DEFAULT_POLICY_PATH) -> dict:
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise EvidenceError(f"cannot read policy file {path}: {exc}") from exc
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        raise EvidenceError(f"malformed policy JSON {path}: {exc}") from exc


# --------------------------------------------------------------------------------------
# JUnit XML parsing
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class ClassResult:
    task: str
    classname: str
    report_file: str
    tests: int
    skipped: int
    failures: int
    errors: int
    report_sha256: str

    @property
    def non_skipped(self) -> int:
        return self.tests - self.skipped


def _int_attr(element: ET.Element, name: str, path: Path, default: str | None = None) -> int:
    raw = element.get(name, default)
    if raw is None:
        raise EvidenceError(f"{path}: <{element.tag}> missing required attribute '{name}'")
    try:
        return int(raw)
    except ValueError as exc:
        raise EvidenceError(f"{path}: <{element.tag}> attribute '{name}'={raw!r} is not an integer") from exc


def parse_junit_report(path: Path, task: str) -> ClassResult:
    """Parse one Gradle per-class JUnit XML report file, reconciling suite counters against the
    actual <testcase> children rather than trusting the root attributes on their own."""
    try:
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        tree = ET.parse(path)
    except ET.ParseError as exc:
        raise EvidenceError(f"malformed JUnit XML report {path}: {exc}") from exc
    except OSError as exc:
        raise EvidenceError(f"cannot read JUnit XML report {path}: {exc}") from exc

    root = tree.getroot()
    if root.tag != "testsuite":
        raise EvidenceError(f"{path}: expected root <testsuite>, found <{root.tag}>")
    classname = root.get("name")
    if not classname:
        raise EvidenceError(f"{path}: <testsuite> missing 'name' attribute")

    declared_tests = _int_attr(root, "tests", path)
    declared_skipped = _int_attr(root, "skipped", path, default="0")
    declared_failures = _int_attr(root, "failures", path, default="0")
    declared_errors = _int_attr(root, "errors", path, default="0")

    testcases = root.findall("testcase")
    actual_skipped = actual_failures = actual_errors = actual_flaky = 0
    for case in testcases:
        if case.find("skipped") is not None:
            actual_skipped += 1
        if case.find("failure") is not None:
            actual_failures += 1
        if case.find("error") is not None:
            actual_errors += 1
        # Gradle's merged-rerun XML can report an outwardly "passing" <testcase> that still
        # carries a <flakyFailure>/<rerunFailure> child -- treat that as a failure, not a pass.
        if case.find("flakyFailure") is not None or case.find("rerunFailure") is not None:
            actual_flaky += 1

    if len(testcases) != declared_tests:
        raise EvidenceError(
            f"{path}: <testsuite tests={declared_tests}> does not match "
            f"{len(testcases)} actual <testcase> element(s) -- malformed report"
        )
    if actual_skipped != declared_skipped:
        raise EvidenceError(
            f"{path}: <testsuite skipped={declared_skipped}> does not match "
            f"{actual_skipped} actual <testcase><skipped/> element(s)"
        )
    if actual_failures != declared_failures:
        raise EvidenceError(
            f"{path}: <testsuite failures={declared_failures}> does not match "
            f"{actual_failures} actual <testcase><failure/> element(s)"
        )
    if actual_errors != declared_errors:
        raise EvidenceError(
            f"{path}: <testsuite errors={declared_errors}> does not match "
            f"{actual_errors} actual <testcase><error/> element(s)"
        )

    return ClassResult(
        task=task,
        classname=classname,
        report_file=str(path),
        tests=declared_tests,
        skipped=declared_skipped,
        # Flaky/rerun evidence on an otherwise-passing case is folded into failures so it cannot
        # report green by construction.
        failures=declared_failures + actual_flaky,
        errors=declared_errors,
        report_sha256=digest,
    )


def collect_task_manifest(report_dir: Path, task: str) -> list[ClassResult]:
    if not report_dir.is_dir():
        raise EvidenceError(
            f"required report directory missing: {report_dir} "
            f"(task '{task}' did not produce reports -- did not run, or ran with NO-SOURCE)"
        )
    files = sorted(report_dir.glob("TEST-*.xml"))
    if not files:
        raise EvidenceError(
            f"no JUnit XML reports found under {report_dir} "
            f"(task '{task}' reported zero tests -- filtered, excluded, or NO-SOURCE)"
        )
    return [parse_junit_report(f, task) for f in files]


def build_manifest(report_dirs: dict[str, str], repo: Path) -> tuple[list[ClassResult], list[str]]:
    """Best-effort across tasks: one task's missing/empty report directory is recorded as a
    problem, not a hard stop, so a run that is broken in two independent ways (e.g. `test` fails a
    case AND `integrationTest` never ran) reports both instead of hiding the second behind the
    first `EvidenceError`."""
    manifest: list[ClassResult] = []
    problems: list[str] = []
    for task, rel_dir in report_dirs.items():
        try:
            manifest.extend(collect_task_manifest(repo / rel_dir, task))
        except EvidenceError as exc:
            problems.append(str(exc))
    return manifest, problems


# --------------------------------------------------------------------------------------
# Freshness / source-identity drift
# --------------------------------------------------------------------------------------


def git_head(repo: Path) -> str:
    try:
        return subprocess.run(
            ["git", "-C", str(repo), "rev-parse", "HEAD"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
    except subprocess.CalledProcessError as exc:
        raise EvidenceError(f"git rev-parse HEAD failed: {exc.stderr}") from exc


def _git_status_records(repo: Path) -> list[tuple[str, str]]:
    """(status_code, path) pairs from NUL-delimited `git status`.

    Porcelain v1's default text output quotes/escapes a path containing quotes, backslashes, or
    non-ASCII bytes -- e.g. `caf\xc3\xa9.txt` becomes the literal 12-character string
    `"caf\303\251.txt"`. A naive `.strip('"')` removes the surrounding quotes but leaves the octal
    escapes un-decoded, so `repo / raw_path` looks for a file that does not exist on disk and the
    entry silently resolves to a constant "ABSENT" placeholder regardless of the real file's
    content -- any edit to such a file then goes undetected. `-z` disables this quoting entirely
    and NUL-delimits fields instead, so every path is recovered byte-for-byte with no
    stripping or escape-decoding of our own.
    """
    try:
        raw = subprocess.run(
            ["git", "-C", str(repo), "status", "--porcelain=v1", "-z", "--untracked-files=all"],
            capture_output=True, check=True,
        ).stdout
    except subprocess.CalledProcessError as exc:
        raise EvidenceError(f"git status failed: {exc.stderr.decode('utf-8', 'replace')}") from exc

    fields = raw.split(b"\x00")
    records: list[tuple[str, str]] = []
    i = 0
    while i < len(fields):
        field = fields[i]
        i += 1
        if not field:
            continue
        # "XY PATH" -- X is index status, Y is worktree status, then a single space, then path.
        status = field[:2].decode("ascii", errors="replace")
        path = field[3:].decode("utf-8", errors="surrogateescape")
        records.append((status, path))
        if "R" in status or "C" in status:
            # Rename/copy records carry a second NUL-terminated field: the original path. The
            # working-tree content lives at the (already-captured) new path, but this field must
            # still be consumed so the next record does not get misaligned.
            i += 1
    return records


def is_clean(repo: Path) -> bool:
    return not _git_status_records(repo)


def content_identity_digest(repo: Path) -> str:
    """Content-addressed identity of HEAD plus every changed/untracked path.

    Hashing `git status` *text* is not enough: the porcelain status category for an
    already-dirty tracked file (` M path`) is identical before and after a further edit to that
    same file, since git status reports change *category*, not content. Editing an already-dirty
    file mid-run would therefore be invisible to a status-text digest. Hashing each reported
    path's actual current bytes alongside HEAD catches that: any content edit -- to a dirty file,
    a newly-touched file, or a deletion -- changes this digest.
    """
    head = git_head(repo)
    entries = []
    for status, path in _git_status_records(repo):
        full = repo / path
        content_id = sha256_file(full) if full.is_file() else "ABSENT"
        entries.append(f"{status}:{path}:{content_id}")
    entries.sort()
    combined = head + "\n" + "\n".join(entries)
    return hashlib.sha256(combined.encode("utf-8")).hexdigest()


def write_marker(repo: Path, out_path: Path, allow_dirty: bool = False) -> dict:
    """Record the pre-run source/time identity that `evidence` later checks against.

    Candidate-ready evidence requires one immutable, fully-committed checkout (tasks.md 7.4: "one
    immutable checkout"): by default this refuses to mark a dirty tree at all. `allow_dirty` exists
    only for clearly-labelled local development runs (never a release candidate) -- those still get
    the full content-addressed identity check, just starting from a dirty baseline instead of a
    clean one.
    """
    clean = is_clean(repo)
    if not clean and not allow_dirty:
        raise EvidenceError(
            "working tree is not clean; candidate-ready evidence requires one immutable, fully "
            "committed checkout. Commit or stash pending changes, or pass allow_dirty=True "
            "(mark --allow-dirty) for clearly-labelled LOCAL_DEV evidence only -- never a release "
            "candidate"
        )
    marker = {
        "epoch": time.time(),
        "head_sha": git_head(repo),
        "clean": clean,
        "mode": "CANDIDATE" if clean else "LOCAL_DEV",
        "content_digest": content_identity_digest(repo),
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(marker, indent=2) + "\n", encoding="utf-8")
    return marker


def check_files_fresh(paths: list[Path], since_epoch: float, label: str) -> list[str]:
    problems = []
    for path in paths:
        mtime = path.stat().st_mtime
        if mtime < since_epoch:
            problems.append(
                f"{path}: mtime {mtime} predates the run-start marker {since_epoch} "
                f"(stale {label} -- Gradle likely treated its producing task as UP-TO-DATE and did "
                "not rewrite it)"
            )
    return problems


def check_freshness_and_identity(
    manifest: list[ClassResult], marker: dict, repo: Path, extra_fresh_paths: list[Path] | None = None
) -> list[str]:
    since = marker.get("epoch")
    if since is None:
        raise EvidenceError("marker file missing required 'epoch' field")

    problems = check_files_fresh([Path(r.report_file) for r in manifest], since, "report")
    problems.extend(check_files_fresh(extra_fresh_paths or [], since, "artifact"))

    now_head = git_head(repo)
    if now_head != marker.get("head_sha"):
        problems.append(
            f"tracked HEAD moved during the run: marker recorded {marker.get('head_sha')}, now {now_head}"
        )
    now_digest = content_identity_digest(repo)
    if now_digest != marker.get("content_digest"):
        problems.append(
            "tracked/untracked working-tree content changed during the run "
            f"(content digest {marker.get('content_digest')} -> {now_digest}) -- source identity drift"
        )
    return problems


# --------------------------------------------------------------------------------------
# Floor validation
# --------------------------------------------------------------------------------------


def validate_floor(manifest: list[ClassResult], floor_entries: list[dict]) -> list[str]:
    failures: list[str] = []
    for entry in floor_entries:
        task = entry["task"]
        suffix = entry["report_class_suffix"]
        matches = [r for r in manifest if r.task == task and r.classname.endswith(suffix)]
        if not matches:
            failures.append(
                f"required pattern '*{suffix}' (task {task!r}, suite '{entry.get('suite', '')}') "
                "has no matching class in the generated manifest"
            )
            continue
        if not any(m.non_skipped > 0 for m in matches):
            names = ", ".join(sorted(m.classname for m in matches))
            failures.append(
                f"required pattern '*{suffix}' (task {task!r}) matched only fully-skipped "
                f"class(es): {names}"
            )
    return failures


def validate_no_failures(manifest: list[ClassResult]) -> list[str]:
    problems = []
    for r in manifest:
        if r.failures or r.errors:
            problems.append(
                f"{r.classname} (task {r.task!r}): {r.failures} failure(s), {r.errors} error(s) "
                f"-- {r.report_file}"
            )
    return problems


# --------------------------------------------------------------------------------------
# Discovery reconciliation
# --------------------------------------------------------------------------------------


def discover_b1_test_files(repo: Path, base_sha: str, globs: list[str], head: str = "HEAD") -> list[str]:
    """B1-added/modified test files since base_sha, treating a rename as delete-old + add-new
    (`--no-renames`) rather than a single R-status pair. `--diff-filter=AM` would otherwise drop a
    renamed test file from `--name-only` output entirely -- it appears under neither its old nor its
    new path -- since `--name-only` never prints the old side of a detected rename. `--no-renames`
    makes the new path surface as a plain addition, which the existing A/M filter already keeps;
    reusing this convention rather than parsing R-status pairs matches how
    check_master_plan_status_propagation.py's list_changed_files() handles the same class of gap.
    """
    cmd = [
        "git", "-C", str(repo), "diff", "--no-renames", "--name-only", "--diff-filter=AM",
        f"{base_sha}..{head}", "--", *globs,
    ]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout
    except subprocess.CalledProcessError as exc:
        raise EvidenceError(f"git diff failed while discovering B1 test files: {exc.stderr}") from exc
    return sorted(line.strip() for line in out.splitlines() if line.strip())


def resolve_base_sha(policy: dict, cli_value: str | None) -> str:
    """The B1-base commit is a reviewed policy input (R4/GC.5), not a per-invocation argument.

    Defaults to the pinned `b1_base_commit.sha` in policy. An explicit override is accepted only
    when it matches the pinned value byte-for-byte -- silently accepting a different base would let
    a single CLI invocation redefine GC.5's guard interval without going through the reviewed
    policy file.
    """
    pinned = policy.get("b1_base_commit", {}).get("sha")
    if not pinned:
        raise EvidenceError("policy is missing b1_base_commit.sha")
    if cli_value is None or cli_value == pinned:
        return pinned
    raise EvidenceError(
        f"--base-sha {cli_value!r} does not match the policy-pinned B1-base {pinned!r}. "
        "The base commit is a reviewed policy input (R4/GC.5); edit "
        "scripts/b1-candidate-policy.json with a reviewed rationale to change it, rather than "
        "overriding it per invocation."
    )


def file_to_fqcn(path: str) -> str:
    marker = "src/test/java/"
    idx = path.replace("\\", "/").find(marker)
    if idx == -1:
        raise EvidenceError(f"cannot derive a fully-qualified class name from {path!r}: no {marker!r} segment")
    rel = path.replace("\\", "/")[idx + len(marker):]
    if not rel.endswith(".java"):
        raise EvidenceError(f"cannot derive a fully-qualified class name from {path!r}: not a .java file")
    return rel[: -len(".java")].replace("/", ".")


def reconcile_discovery(
    manifest: list[ClassResult], b1_files: list[str], allowlist: list[str]
) -> list[str]:
    manifest_classes = {r.classname for r in manifest}
    allow = set(allowlist)
    missing = []
    for f in b1_files:
        fqcn = file_to_fqcn(f)
        if fqcn in allow:
            continue
        if fqcn not in manifest_classes:
            missing.append(
                f"{f} ({fqcn}) is a B1-added/modified test file with no corresponding class in "
                "the generated manifest -- written, then excluded or mis-tagged"
            )
    return missing


# --------------------------------------------------------------------------------------
# JAR / stage hash equality
# --------------------------------------------------------------------------------------


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


_SHA256_DIGEST_RE = re.compile(r"^(?:sha256:)?([0-9a-f]{64})$")


def normalize_sha256_digest(value) -> str | None:
    """The ONE digest parser shared by every producer and consumer in this evidence pipeline.

    `sha256_file` (Task A's `stage.sha256`, Task B's `staged_jar_sha256`/`extracted_jar_sha256`)
    emits bare lowercase hex; Docker image IDs carry the `sha256:` prefix. Both are the same
    identity. Returns the canonical `sha256:<64 lowercase hex>` form, or None for anything that is
    not a well-formed digest -- a consumer must treat None as malformed, never as "unknown but
    acceptable". Case is significant: git/Docker/hashlib all emit lowercase, and accepting a
    case-variant would let two spellings of one digest read as two identities.
    """
    if not isinstance(value, str):
        return None
    m = _SHA256_DIGEST_RE.match(value)
    return "sha256:" + m.group(1) if m else None


def resolve_bootjar(repo: Path, staging_policy: dict) -> Path:
    bootjar_dir = repo / staging_policy["bootjar_dir"]
    if not bootjar_dir.is_dir():
        raise EvidenceError(f"bootJar output directory missing: {bootjar_dir}")
    matches = sorted(bootjar_dir.glob(staging_policy["bootjar_glob"]))
    if not matches:
        raise EvidenceError(f"no bootJar archive found in {bootjar_dir} matching {staging_policy['bootjar_glob']!r}")
    if len(matches) > 1:
        raise EvidenceError(
            f"ambiguous bootJar selection in {bootjar_dir}: {[m.name for m in matches]} all match "
            f"{staging_policy['bootjar_glob']!r} -- refusing to guess"
        )
    return matches[0]


def check_jar_stage(repo: Path, staging_policy: dict) -> dict:
    bootjar_path = resolve_bootjar(repo, staging_policy)
    staged_path = repo / staging_policy["staged_path"]
    if not staged_path.is_file():
        raise EvidenceError(f"staged candidate artifact not found: {staged_path}")
    jar_sha = sha256_file(bootjar_path)
    staged_sha = sha256_file(staged_path)
    if jar_sha != staged_sha:
        raise EvidenceError(
            f"staged artifact SHA mismatch: bootJar {bootjar_path} = {jar_sha}, "
            f"staged {staged_path} = {staged_sha}"
        )
    return {"bootjar_path": str(bootjar_path), "staged_path": str(staged_path), "sha256": jar_sha}


# --------------------------------------------------------------------------------------
# Top-level evidence run
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class _PreStageResult:
    manifest: list[ClassResult]
    base_sha: str
    b1_files: list[str]
    bootjar_path: Path | None
    problems: list[str]


def _pre_stage_evidence(repo: Path, policy: dict, marker: dict, base_sha_override: str | None) -> _PreStageResult:
    """Everything that can and must be checked *before* an artifact is staged: manifest
    failures, floor acceptance, discovery reconciliation, and -- against the given run-start
    marker -- report/bootJar freshness and tracked-source identity drift. Shared by
    `run_manifest_check` (the Gradle-internal staging gate) and `run_evidence` (the full post-run
    bundle), so both apply identical freshness semantics rather than the gate trusting anything the
    final bundle actually verifies.
    """
    base_sha = resolve_base_sha(policy, base_sha_override)
    manifest, problems = build_manifest(policy["report_dirs"], repo)

    bootjar_path: Path | None = None
    try:
        bootjar_path = resolve_bootjar(repo, policy["staging"])
    except EvidenceError as exc:
        problems.append(str(exc))

    problems.extend(
        check_freshness_and_identity(
            manifest, marker, repo, extra_fresh_paths=[bootjar_path] if bootjar_path else []
        )
    )
    problems.extend(validate_no_failures(manifest))
    problems.extend(validate_floor(manifest, policy["candidate_floor"]["entries"]))

    b1_files = discover_b1_test_files(repo, base_sha, policy["discovery"]["test_file_globs"])
    problems.extend(
        reconcile_discovery(manifest, b1_files, policy["discovery"]["helper_class_allowlist"])
    )

    return _PreStageResult(manifest, base_sha, b1_files, bootjar_path, problems)


def run_manifest_check(repo: Path, policy: dict, marker: dict, base_sha_override: str | None = None) -> dict:
    """Gates staging: `candidateManifestValidation` runs this *inside* the Gradle invocation,
    before `prepareCandidateArtifact` -- tasks.md 7.4: "Manifest validation must succeed before
    staging; invoking staging alone must not bypass verification." It needs the same run-start
    marker as `run_evidence` -- without it, a stale report or a bootJar Gradle left untouched
    (UP-TO-DATE) would satisfy every content-only check and still get staged.
    """
    pre = _pre_stage_evidence(repo, policy, marker, base_sha_override)
    result = {
        "base_sha": pre.base_sha,
        "b1_added_or_modified_test_files": pre.b1_files,
        "status": "FAIL" if pre.problems else "PASS",
        "problems": pre.problems,
    }
    if pre.problems:
        raise EvidenceError(
            f"{len(pre.problems)} manifest-validation problem(s):\n- " + "\n- ".join(pre.problems)
        )
    return result


def run_evidence(repo: Path, policy: dict, marker: dict, base_sha_override: str | None = None) -> dict:
    """Raises EvidenceError with every failure reason joined, so a caller sees the whole picture
    rather than stopping at the first problem."""
    pre = _pre_stage_evidence(repo, policy, marker, base_sha_override)
    manifest, base_sha, b1_files, bootjar_path = pre.manifest, pre.base_sha, pre.b1_files, pre.bootjar_path
    problems: list[str] = list(pre.problems)

    stage = None
    if bootjar_path is not None:
        try:
            stage = check_jar_stage(repo, policy["staging"])
        except EvidenceError as exc:
            problems.append(str(exc))

    per_class = [
        {
            "task": r.task,
            "classname": r.classname,
            "tests": r.tests,
            "skipped": r.skipped,
            "non_skipped": r.non_skipped,
            "failures": r.failures,
            "errors": r.errors,
            "report_file": r.report_file,
            "report_sha256": r.report_sha256,
        }
        for r in manifest
    ]
    per_task_totals = {
        task: {
            "classes": sum(1 for r in manifest if r.task == task),
            "tests": sum(r.tests for r in manifest if r.task == task),
            "skipped": sum(r.skipped for r in manifest if r.task == task),
            "failures": sum(r.failures for r in manifest if r.task == task),
            "errors": sum(r.errors for r in manifest if r.task == task),
        }
        for task in policy["report_dirs"]
    }

    scope_status = "FAIL" if problems else "PASS"
    mode = marker.get("mode", "UNKNOWN")

    # This tool's scope ends at Task 7.4/7.5: the Gradle verification graph, floor acceptance,
    # discovery reconciliation, and jar/stage-hash binding. A PASS here is never a release verdict
    # -- it says nothing about the candidate image, its registry digest, the HTTP smoke, or R3's
    # SQL disposition, none of which this script evidences. `candidate_ready` is therefore always
    # False; `candidate_ready_blocked_by` names what is still outstanding so a reader never has to
    # infer it from the absence of a field.
    # Implementation status and evidence status are DIFFERENT facts, and conflating them is how a
    # bundle comes to imply that shipping a file cleared an obligation. Task C's guard now exists,
    # so "not implemented" would be false -- but its findings are unresolved, its smoke proof is
    # absent, and R3 stands, so the candidate is no less blocked than before. Historical bundles
    # already written are not rewritten: they describe their own run and stay byte-for-byte as
    # captured.
    blocked_by = [
        "Task B: candidate image build, registry-digest resolution, and extracted-JAR hash join "
        "not implemented by this tooling yet",
        "Task C source-governance/writer-inventory: IMPLEMENTED "
        "(scripts/check_b1_candidate_source.py, contract gc5-contract/2) but NOT CLEARED -- its "
        "findings require reviewed dispositions, which this tool does not grant and cannot infer",
        "Task C exact-digest HTTP smoke harness: not implemented by this tooling yet",
    ]
    unresolved_ids = [item.get("id", "?") for item in policy.get("unresolved", [])]
    if unresolved_ids:
        blocked_by.append(
            f"unresolved policy finding(s) {', '.join(unresolved_ids)} "
            "(scripts/b1-candidate-policy.json:unresolved)"
        )

    evidence = {
        "run": {
            "marker_epoch": marker.get("epoch"),
            "head_sha": marker.get("head_sha"),
            "mode": mode,
            "b1_base_sha": base_sha,
        },
        "manifest": per_class,
        "per_task_totals": per_task_totals,
        "b1_added_or_modified_test_files": b1_files,
        "stage": stage,
        # Scoped strictly to this script's own checks -- see the module docstring and the
        # candidate_ready fields below before treating this as a release verdict.
        "graph_verification_status": scope_status,
        "problems": problems,
        "candidate_ready": False,
        "candidate_ready_blocked_by": blocked_by,
    }

    if problems:
        raise EvidenceError(
            f"{len(problems)} candidate-verification problem(s):\n- " + "\n- ".join(problems)
        )
    return evidence


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------


def _load_marker(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise EvidenceError(
            f"cannot read run-start marker {path}: {exc}. Run 'mark' before the Gradle graph."
        ) from exc


def _cmd_mark(args: argparse.Namespace) -> int:
    repo = Path(args.repo).resolve()
    out_path = Path(args.out)
    try:
        marker = write_marker(repo, out_path, allow_dirty=args.allow_dirty)
    except EvidenceError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print(f"wrote run-start marker to {out_path}: {json.dumps(marker)}")
    return 0


def _cmd_manifest_check(args: argparse.Namespace) -> int:
    """Invoked from inside the Gradle graph (candidateManifestValidation), before staging."""
    repo = Path(args.repo).resolve()
    policy = load_policy(Path(args.policy))
    try:
        marker = _load_marker(Path(args.since_marker))
        result = run_manifest_check(repo, policy, marker, args.base_sha)
    except EvidenceError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print(f"manifest validation: {result['status']}")
    return 0


def _cmd_evidence(args: argparse.Namespace) -> int:
    repo = Path(args.repo).resolve()
    policy = load_policy(Path(args.policy))

    try:
        marker = _load_marker(Path(args.since_marker))
        evidence = run_evidence(repo, policy, marker, args.base_sha)
    except EvidenceError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        if args.out:
            # Still fail-closed on exit code, but persist what was learned for the return packet.
            partial = {"graph_verification_status": "FAIL", "candidate_ready": False, "error": str(exc)}
            Path(args.out).write_text(json.dumps(partial, indent=2) + "\n", encoding="utf-8")
        return 1

    if args.out:
        Path(args.out).write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        print(f"wrote evidence to {args.out}")
    print(
        f"graph verification: {evidence['graph_verification_status']} (mode={evidence['run']['mode']}); "
        f"candidate_ready={evidence['candidate_ready']} (see candidate_ready_blocked_by)"
    )
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=str(REPO), help="repository root (default: this checkout)")
    sub = parser.add_subparsers(dest="command", required=True)

    p_mark = sub.add_parser("mark", help="record the pre-run source/time marker")
    p_mark.add_argument("--out", default=str(DEFAULT_MARKER_PATH), help="path to write the marker JSON to")
    p_mark.add_argument(
        "--allow-dirty", action="store_true",
        help="allow marking a dirty tree, for LOCAL_DEV evidence only -- never a release candidate",
    )
    p_mark.set_defaults(func=_cmd_mark)

    p_mc = sub.add_parser(
        "manifest-check",
        help="Gradle-internal gate: floor/discovery/freshness validation before staging",
    )
    p_mc.add_argument(
        "--since-marker", default=str(DEFAULT_MARKER_PATH),
        help="marker file written by 'mark' before the graph ran",
    )
    p_mc.add_argument("--policy", default=str(DEFAULT_POLICY_PATH), help="path to b1-candidate-policy.json")
    p_mc.add_argument(
        "--base-sha", default=None,
        help="must equal the policy-pinned B1-base commit if given; defaults to it",
    )
    p_mc.set_defaults(func=_cmd_manifest_check)

    p_ev = sub.add_parser("evidence", help="validate the graph's output and emit the evidence bundle")
    p_ev.add_argument(
        "--since-marker", default=str(DEFAULT_MARKER_PATH),
        help="marker file written by 'mark' before the graph ran",
    )
    p_ev.add_argument(
        "--base-sha", default=None,
        help="must equal the policy-pinned B1-base commit if given; defaults to it",
    )
    p_ev.add_argument("--policy", default=str(DEFAULT_POLICY_PATH), help="path to b1-candidate-policy.json")
    p_ev.add_argument("--out", default=None, help="optional path to write the evidence JSON bundle to")
    p_ev.set_defaults(func=_cmd_evidence)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
