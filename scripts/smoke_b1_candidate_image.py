#!/usr/bin/env python3
"""B1 R-C candidate image HTTP contract smoke (Task C / tasks.md 7.5a).

Runs the PACKAGED ARTIFACT — an immutable image, by identity, using its shipped entrypoint — on a
private local Docker network with disposable dependencies, and asserts the composition contract over
real HTTP against a real PostgreSQL.

What this proves, and what it deliberately does not:

  * It exercises the container Container Apps would actually run: the Mariner runtime, the image's
    own ENTRYPOINT and the JAR inside it. No JAR is mounted, no test-class overlay is added, no
    entrypoint is overridden -- `java -jar` on the host would prove none of that (R5).
  * `--local-image sha256:<64hex>` is DEVELOPMENT feedback (`LOCAL_PREPARATION`). Only
    `--registry-digest <repo>@sha256:<64hex>`, which requires `--authorized-release-run`, is the
    Task 7.5a evidence, and that run is separately owner-authorized. A mutable tag is refused in both
    modes: a tag can be repointed between resolution and run, so it cannot identify what was tested.
  * `candidate_ready` is ALWAYS false here. This harness evidences one join (image -> HTTP contract);
    source governance, the registry join and R3 are established elsewhere.

Fails closed throughout. A missing dependency, an unready container, a wrong body, or a cleanup error
is a FAILURE with preserved evidence -- never a pass. Cleanup removes only what this run created,
identified by this run's own id, so a concurrent run or a pre-existing container is never touched.

Contract assertions (each recorded with its request, response and digest):

  A1 startup     the container runs the requested image identity, on the recorded platform, via the
                 image's own entrypoint, and serves its own HTTP contract within the deadline.
  A2 assets      `GET /api/assets` returns 200 with a non-empty catalog and an ETag.
  A3 composition `PUT /api/portfolio/holdings` with a nontrivial multi-holding body and the portfolio's
                 current version succeeds, and the persisted version advances.
  A4 conflict    replaying THE SAME expectedVersion returns 409 with the exact
                 `portfolio_version_conflict` envelope and the observed `currentVersion`, and the
                 parent row and child holdings are byte-for-byte unchanged afterwards.

A4 never refreshes `expectedVersion` and never retries: the stale value is the point of the test.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
from decimal import Decimal, InvalidOperation
import urllib.error
import urllib.request
import uuid
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "scripts"))

from b1_candidate_evidence import EvidenceError, sha256_file  # noqa: E402
from verify_b1_candidate_image import (  # noqa: E402
    CONTAINER_INTERNAL_JAR_PATH,
    docker_image_field,
    extract_file,
)

LOCAL_PREPARATION = "LOCAL_PREPARATION"
RELEASE_DIGEST = "RELEASE_DIGEST"

#: Synthetic identity owned by this harness. Deliberately distinct from the compiled-in E2E user
#: (...0e2e) and demo user (...0d3110) so a smoke run can never touch their fixture state.
SMOKE_USER_ID = "00000000-0000-0000-0000-00000000c0de"
X_USER_ID_HEADER = "X-User-Id"

APP_PORT = 8080  # application-prod.yml:8-9. Dockerfile EXPOSE 8081 does not set the app port.

#: Where `/app.jar` is extracted for re-hashing. Under the git-ignored staging directory so a run
#: never dirties the tree; module-level so a test can redirect it out of the repository entirely.
SMOKE_WORKDIR_BASE = REPO / ".candidate-artifacts" / "smoke-tmp"

_LOCAL_IMAGE_ID_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
#: `<host>[:port]/<path>@sha256:<64hex>`. A tag, with or without a digest suffix, is not a member of
#: this language.
_REGISTRY_DIGEST_RE = re.compile(
    r"^[a-z0-9]+(?:[._-][a-z0-9]+)*(?::[0-9]+)?(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*@sha256:[0-9a-f]{64}$")

#: The ONE repository a release run may pull from (R5 / tasks.md 7.5a). A digest alone is not an
#: identity: `docker.io/unrelated/app@sha256:...` is equally immutable and equally wrong, so the
#: repository is pinned here rather than accepted from the command line. Changing it is a reviewed
#: source change, not a run-time argument.
APPROVED_RELEASE_REPOSITORY = "wealthprodacr.azurecr.io/portfolio-service"

#: Every way this local environment differs from the deployed one. Recorded in the evidence rather
#: than implied, because a smoke that silently diverges from production proves less than it claims.
ENVIRONMENT_DIFFERENCES = [
    "PostgreSQL is a disposable local container, not the managed production database; it starts "
    "empty and Flyway migrates it from the image's own bundled migration history.",
    "Kafka is a disposable single-node KRaft broker reached over PLAINTEXT; production uses an "
    "external broker over SASL_SSL (config/application-prod-kafka.yml). No asserted behaviour "
    "depends on Kafka; it is present so startup resolves its real wiring rather than a dead endpoint.",
    "Redis is a disposable local container; production uses a managed endpoint.",
    "Synthetic KAFKA_SASL_USERNAME/PASSWORD placeholders are supplied because application-prod-kafka.yml "
    "interpolates them unconditionally; they are unused under PLAINTEXT and are not credentials.",
    "No gateway sits in front of the container: the X-User-Id header this harness sends is the one "
    "the gateway would inject, so the service boundary under test is identical.",
    "INTERNAL_API_KEY is unset, so /api/internal/** stays closed; no asserted endpoint uses it.",
    "Outbound FX refresh (06:00 UTC cron) does not fire during a smoke run.",
    "The identity under test is a synthetic user owned by this harness, not a production account.",
]


def _now() -> float:
    return time.monotonic()


def _digest_text(text: str) -> str:
    return "sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest()


def _reject_constant(name: str):
    raise EvidenceError("JSON carried the non-finite value " + name + ", which has no decimal value "
                        "and cannot take part in an exact comparison")


def json_loads(text: str):
    """`json.loads` that does NOT round.

    Python decodes a fractional JSON number to a binary float, so `999999999999999.0001` -- a legal
    value for `avg_cost_basis NUMERIC(19,4)` -- silently becomes `999999999999999.0` before any
    comparison sees it, and PostgreSQL's `row_to_json` emits numeric columns as bare JSON numbers.
    Decoding to `Decimal` keeps every digit the wire actually carried; `_decimal` then compares by
    value, so `1.5` and `1.5000` still match while `1.5` and `1.5000000000000001` do not.
    Non-finite constants are refused rather than compared."""
    return json.loads(text, parse_float=Decimal, parse_constant=_reject_constant)


def _json_default(obj):
    """Serialize `Decimal` losslessly, as its exact digits. It becomes a JSON string rather than a
    number, so the written evidence round-trips through `json_loads`/`_decimal` with no rounding;
    the verbatim wire form is retained separately in each exchange's `body_text`."""
    if isinstance(obj, Decimal):
        return str(obj)
    raise TypeError("cannot serialize " + type(obj).__name__ + " into evidence JSON")


def json_dumps(value, **kwargs) -> str:
    return json.dumps(value, default=_json_default, **kwargs)


def resolve_image_reference(local_image: str | None, registry_digest: str | None,
                            authorized_release_run: bool, *, expect_jar_sha256: str | None = None,
                            expect_platform: str | None = None, keep: bool = False) -> tuple[str, str]:
    """(reference, label). Exactly one of the two inputs, each immutable by construction.

    A tag is refused rather than resolved: resolving a tag to an id here would still leave the
    evidence naming something that can later point elsewhere, which is the substitution this whole
    chain exists to prevent.

    A release run additionally carries a MANDATORY identity contract, checked here -- before any
    Docker or registry access -- because a release PASS that never named the artifact, the platform
    or the repository it expected would be a statement about whatever happened to be pulled:

      * the digest must be in the approved ACR repository;
      * `--expect-jar-sha256` must name the verified staged JAR the image has to carry;
      * `--expect-platform` must name the deployment platform;
      * `--keep` is refused, because retained resources mean cleanup was never verified.
    """
    if bool(local_image) == bool(registry_digest):
        raise EvidenceError("supply exactly one of --local-image (development feedback) or "
                            "--registry-digest (the Task 7.5a evidence run)")
    if local_image:
        if not _LOCAL_IMAGE_ID_RE.match(local_image):
            raise EvidenceError("--local-image must be an immutable image id `sha256:<64 hex>`, got "
                                + repr(local_image)[:120] + "; a tag is mutable and cannot identify "
                                "what was tested")
        return local_image, LOCAL_PREPARATION
    if not _REGISTRY_DIGEST_RE.match(registry_digest):
        raise EvidenceError("--registry-digest must be an immutable `<repository>@sha256:<64 hex>` "
                            "reference, got " + repr(registry_digest)[:120])
    repository = registry_digest.split("@", 1)[0]
    if repository != APPROVED_RELEASE_REPOSITORY:
        raise EvidenceError("--registry-digest names repository " + repr(repository) + ", not the "
                            "approved release repository " + repr(APPROVED_RELEASE_REPOSITORY)
                            + "; an immutable digest in the wrong repository is the wrong artifact")
    if not authorized_release_run:
        raise EvidenceError(
            "running the registry digest is the Task 7.5a evidence run and requires its own owner "
            "authorization: it pulls from the release registry. Re-run with --authorized-release-run "
            "only when that authorization has been given for this exact digest.")
    missing = []
    if not expect_jar_sha256:
        missing.append("--expect-jar-sha256 (the verified staged JAR the image must carry)")
    elif not re.fullmatch(r"(?:sha256:)?[0-9a-f]{64}", expect_jar_sha256):
        raise EvidenceError("--expect-jar-sha256 must be a sha256 digest, got "
                            + repr(expect_jar_sha256)[:120])
    if not expect_platform:
        missing.append("--expect-platform (the deployment platform, e.g. linux/amd64)")
    elif not re.fullmatch(r"[a-z0-9]+/[a-z0-9]+(?:/v[0-9]+)?", expect_platform):
        raise EvidenceError("--expect-platform must look like `os/arch`, got " + repr(expect_platform)[:80])
    if missing:
        raise EvidenceError("a release run must state what it expects before it pulls anything; "
                            "missing " + " and ".join(missing))
    if keep:
        raise EvidenceError("--keep retains this run's containers, so cleanup is never verified; it "
                            "cannot produce release evidence. Re-run without --keep.")
    return registry_digest, RELEASE_DIGEST


def resolve_release_manifest(reference: str, expect_platform: str) -> dict:
    """Which manifest the release digest actually names, and the platform-specific child when it is
    an index. The runbook requires the SELECTED platform manifest to be recorded: an index digest
    alone does not identify what a single-platform deployment will pull."""
    raw = _run(["docker", "manifest", "inspect", reference], timeout=300).stdout
    try:
        manifest = json_loads(raw)
    except ValueError as exc:
        raise EvidenceError("could not parse `docker manifest inspect` output: " + str(exc))
    media_type = manifest.get("mediaType", "")
    children = manifest.get("manifests")
    if not isinstance(children, list):
        return {"media_type": media_type, "is_index": False,
                "platform_manifest_digest": reference.split("@", 1)[1], "index_digest": None}
    want_os, want_arch = expect_platform.split("/")[:2]
    selected = [c for c in children
                if (c.get("platform") or {}).get("os") == want_os
                and (c.get("platform") or {}).get("architecture") == want_arch]
    if not selected:
        available = sorted({str((c.get("platform") or {}).get("os")) + "/"
                            + str((c.get("platform") or {}).get("architecture")) for c in children})
        raise EvidenceError("the release digest is an image INDEX with no manifest for the expected "
                            "platform " + expect_platform + "; it offers " + ", ".join(available)
                            + ". Record and run the platform manifest the deployment target pulls.")
    if len(selected) > 1:
        raise EvidenceError("the release index offers " + str(len(selected)) + " manifests for "
                            + expect_platform + "; the selected artifact is ambiguous")
    return {"media_type": media_type, "is_index": True,
            "platform_manifest_digest": selected[0].get("digest"),
            "index_digest": reference.split("@", 1)[1]}


def _run(cmd: list[str], timeout: int = 120) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, check=True, timeout=timeout)
    except subprocess.CalledProcessError as exc:
        raise EvidenceError("command failed (" + " ".join(cmd[:4]) + " ...): exit "
                            + str(exc.returncode) + "\n" + (exc.stderr or "")[:2000]) from exc
    except subprocess.TimeoutExpired as exc:
        raise EvidenceError("command timed out (" + " ".join(cmd[:4]) + " ...)") from exc
    except FileNotFoundError as exc:
        raise EvidenceError("command not found: " + repr(cmd[0])) from exc


def http_request(url: str, method: str = "GET", body: dict | None = None,
                 headers: dict | None = None, timeout: float = 20.0) -> dict:
    """One HTTP exchange, recorded. A non-2xx status is DATA (the 409 is an assertion target), so it
    is returned rather than raised; only a transport failure raises."""
    payload = None if body is None else json_dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=payload, method=method)
    request.add_header("Accept", "application/json")
    if payload is not None:
        request.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", errors="replace")
            status, response_headers = response.status, dict(response.headers)
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        status, response_headers = exc.code, dict(exc.headers or {})
    except (urllib.error.URLError, OSError, TimeoutError) as exc:
        raise EvidenceError("HTTP " + method + " " + url + " failed at the transport layer: " + str(exc))
    try:
        parsed = json_loads(raw) if raw.strip() else None
    except ValueError:
        parsed = None
    return {"method": method, "url": url, "status": status, "headers": response_headers,
            "body_text": raw, "body": parsed, "body_sha256": _digest_text(raw),
            "request_body": body}


class SmokeRun:
    """Owns every Docker resource this run creates, and nothing else. Cleanup is by this run's own
    id, so it can never remove a concurrent run's containers or anything pre-existing."""

    def __init__(self, run_id: str | None = None, keep: bool = False) -> None:
        self.run_id = run_id or uuid.uuid4().hex[:12]
        self.keep = keep
        self.network = "b1smoke-" + self.run_id + "-net"
        self._containers: list[str] = []
        self._network_created = False

    # -- resource creation ---------------------------------------------------------------
    def create_network(self) -> str:
        _run(["docker", "network", "create", "--internal=false", self.network])
        self._network_created = True
        return self.network

    def run_container(self, role: str, image: str, *, env: dict | None = None,
                      publish: str | None = None, args: list[str] | None = None,
                      platform: str | None = None) -> str:
        """Create, RECORD, then start one container on this run's network.

        Ownership is established by `docker create` before anything is launched. `docker run` would
        register the id only on success, yet Docker can create a container and then fail to start its
        process (a bad entrypoint exits 127) -- the container exists, this run owns it, and nothing
        would have removed it. The artifact under test is never given an `--entrypoint`: it must run
        the one it ships."""
        name = "b1smoke-" + self.run_id + "-" + role
        cmd = ["docker", "create", "--name", name, "--network", self.network,
               "--network-alias", role]
        for key, value in (env or {}).items():
            cmd += ["-e", key + "=" + value]
        if publish:
            cmd += ["-p", publish]
        if platform:
            cmd += ["--platform", platform]
        cmd.append(image)
        cmd += args or []
        created = _run(cmd, timeout=300)
        container_id = created.stdout.strip()
        if not container_id:
            raise EvidenceError("docker create returned no container id for " + role)
        self._containers.append(container_id)  # owned from here on, however start goes
        _run(["docker", "start", container_id], timeout=180)
        return container_id

    def exec(self, container: str, args: list[str], timeout: int = 30) -> subprocess.CompletedProcess:
        return subprocess.run(["docker", "exec", container, *args],
                              capture_output=True, text=True, timeout=timeout)

    def logs(self, container: str, tail: int = 80) -> str:
        proc = subprocess.run(["docker", "logs", "--tail", str(tail), container],
                              capture_output=True, text=True)
        return ((proc.stdout or "") + (proc.stderr or ""))[-8000:]

    def host_port(self, container: str, port: int) -> int:
        mapping = _run(["docker", "port", container, str(port) + "/tcp"]).stdout.strip().splitlines()
        if not mapping:
            raise EvidenceError("container published no host port for " + str(port))
        return int(mapping[0].rsplit(":", 1)[1])

    # -- waiting -------------------------------------------------------------------------
    def wait_until(self, description: str, probe, deadline_s: float, container: str | None = None,
                   interval: float = 1.0) -> float:
        """Poll `probe()` until it returns True. A deadline is mandatory: an unbounded wait turns a
        broken dependency into a hang instead of a failure."""
        started = _now()
        end = started + deadline_s
        last_error = ""
        while _now() < end:
            if container is not None and not self.is_running(container):
                raise EvidenceError("while waiting for " + description + ": container exited early.\n"
                                    + self.logs(container))
            try:
                if probe():
                    return _now() - started
            except Exception as exc:  # a probe failure is just "not ready yet"
                last_error = str(exc)[:400]
            time.sleep(interval)
        detail = ("; last probe error: " + last_error) if last_error else ""
        logs = ("\n" + self.logs(container)) if container is not None else ""
        raise EvidenceError("timed out after " + str(int(deadline_s)) + "s waiting for "
                            + description + detail + logs)

    def is_running(self, container: str) -> bool:
        proc = subprocess.run(["docker", "inspect", "-f", "{{.State.Running}}", container],
                              capture_output=True, text=True)
        return proc.returncode == 0 and proc.stdout.strip() == "true"

    # -- cleanup -------------------------------------------------------------------------
    def retained(self) -> list[str]:
        """Resources deliberately left behind by `--keep`, named so the evidence can say what is
        still running instead of implying a clean environment."""
        return [c[:12] for c in self._containers] + ([self.network] if self._network_created else [])

    def cleanup(self) -> list[str]:
        """Remove only this run's resources. Returns the errors encountered; the caller reports them
        as failures -- a cleanup problem is never absorbed into a pass."""
        errors: list[str] = []
        if self.keep:
            return errors
        for container in reversed(self._containers):
            proc = subprocess.run(["docker", "rm", "-f", container], capture_output=True, text=True)
            if proc.returncode != 0 and "No such container" not in (proc.stderr or ""):
                errors.append("could not remove container " + container[:12] + ": "
                              + (proc.stderr or "").strip()[:300])
        if self._network_created:
            proc = subprocess.run(["docker", "network", "rm", self.network], capture_output=True, text=True)
            if proc.returncode != 0 and "not found" not in (proc.stderr or ""):
                errors.append("could not remove network " + self.network + ": "
                              + (proc.stderr or "").strip()[:300])
        return errors


# --------------------------------------------------------------------------------------
# Assertions -- pure functions over recorded exchanges, so each is unit-testable without Docker
# --------------------------------------------------------------------------------------


def assert_assets(exchange: dict) -> dict:
    problems: list[str] = []
    if exchange["status"] != 200:
        problems.append("GET /api/assets returned " + str(exchange["status"]) + ", expected 200")
    body = exchange["body"]
    if not isinstance(body, dict):
        problems.append("GET /api/assets body is not a JSON object")
        body = {}
    assets = body.get("assets")
    if not isinstance(assets, list) or not assets:
        problems.append("catalog is empty or malformed; a packaged catalog must ship with the image")
        assets = []
    if not body.get("catalogVersion"):
        problems.append("catalogVersion is missing")
    etag = exchange["headers"].get("ETag") or exchange["headers"].get("Etag")
    if not etag:
        problems.append("ETag header is missing")
    tickers = [a.get("ticker") for a in assets if isinstance(a, dict) and a.get("ticker")]
    return {"id": "A2", "name": "GET /api/assets", "passed": not problems, "problems": problems,
            "catalog_version": body.get("catalogVersion"), "asset_count": len(assets),
            "etag": etag, "tickers_sample": tickers[:8], "exchange": exchange}


def _decimal(value) -> Decimal | None:
    """Exact decimal value, or None when the wire form is not a finite plain decimal. `1.5` and
    `1.5000` are the same quantity; `1.5` and `999` are not. Values reach here as `Decimal` (from
    `json_loads`) or as text; a float would already have been rounded, so it is refused."""
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, float):
        return None
    try:
        parsed = Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None
    return parsed if parsed.is_finite() else None


def compare_quantities(submitted: dict, observed: dict, where: str) -> list[str]:
    """Ticker -> quantity, compared by decimal value. Missing, extra and wrong quantities are all
    reported: a response that echoes the right tickers with the wrong numbers is a failed write, and
    replaying it afterwards would only prove the wrong state stayed put."""
    problems: list[str] = []
    for ticker in sorted(set(submitted) | set(observed)):
        want, got = submitted.get(ticker), observed.get(ticker)
        if ticker not in observed:
            problems.append(where + " is missing submitted holding " + ticker)
            continue
        if ticker not in submitted:
            problems.append(where + " carries unsubmitted holding " + ticker)
            continue
        want_d, got_d = _decimal(want), _decimal(got)
        if got_d is None:
            problems.append(where + " quantity for " + ticker + " is not a decimal: " + repr(got)[:60])
        elif want_d is None or want_d != got_d:
            problems.append(where + " quantity for " + ticker + " is " + repr(got)
                            + ", submitted " + repr(want))
    return problems


def assert_composition(exchange: dict, expected_version: int, submitted_holdings: dict) -> dict:
    """A3. `submitted_holdings` is ticker -> quantity exactly as sent, so the response is checked
    against what was asked for rather than against itself."""
    problems: list[str] = []
    if exchange["status"] not in (200, 201):
        problems.append("composition returned " + str(exchange["status"]) + ", expected 200 or 201")
    body = exchange["body"]
    version = None
    observed: dict = {}
    if not isinstance(body, dict):
        problems.append("composition body is not a JSON object")
    else:
        version = body.get("version")
        if not isinstance(version, int) or isinstance(version, bool):
            problems.append("composition response carries no integer version")
        elif version <= expected_version:
            problems.append("persisted version " + str(version) + " did not advance past the "
                            "submitted expectedVersion " + str(expected_version))
        holdings = body.get("holdings")
        if not isinstance(holdings, list):
            problems.append("composition response carries no holdings list")
        else:
            for entry in holdings:
                if not isinstance(entry, dict) or not entry.get("assetTicker"):
                    problems.append("response holding is malformed: " + repr(entry)[:80])
                    continue
                observed[entry["assetTicker"]] = entry.get("quantity")
            if len(observed) != len(holdings):
                problems.append("response repeats a ticker across holdings")
            problems.extend(compare_quantities(submitted_holdings, observed, "response"))
    return {"id": "A3", "name": "PUT /api/portfolio/holdings (nontrivial composition)",
            "passed": not problems, "problems": problems, "submitted_expected_version": expected_version,
            "submitted_holdings": submitted_holdings, "observed_holdings": observed,
            "observed_version": version, "exchange": exchange}


def assert_conflict(exchange: dict, replayed_expected_version: int, current_version: int) -> dict:
    """The stale-version replay. `replayed_expected_version` MUST be the value already consumed by
    A3 -- refreshing it would test a successful write a second time, not the conflict."""
    problems: list[str] = []
    if exchange["status"] != 409:
        problems.append("stale replay returned " + str(exchange["status"]) + ", expected 409")
    body = exchange["body"]
    if not isinstance(body, dict):
        problems.append("conflict body is not a JSON object")
        body = {}
    if body.get("error") != "portfolio_version_conflict":
        problems.append("error code is " + repr(body.get("error"))
                        + ", expected 'portfolio_version_conflict'")
    if body.get("currentVersion") != current_version:
        problems.append("currentVersion is " + repr(body.get("currentVersion")) + ", expected "
                        + str(current_version) + " (the version observed after the accepted write)")
    if replayed_expected_version >= current_version:
        problems.append("fixture error: the replayed expectedVersion " + str(replayed_expected_version)
                        + " is not stale relative to currentVersion " + str(current_version))
    return {"id": "A4", "name": "stale expectedVersion replay -> portfolio_version_conflict",
            "passed": not problems, "problems": problems,
            "replayed_expected_version": replayed_expected_version,
            "expected_current_version": current_version, "exchange": exchange}


def assert_state_unchanged(before: dict, after: dict, expected_version: int,
                           submitted_holdings: dict) -> dict:
    """A4-db. COMPLETE parent and child rows identical across the rejected write.

    Two things are required, and neither implies the other:

      * the before-state must be the state A3 said it wrote -- a real portfolio row at the observed
        version carrying exactly the submitted tickers AND quantities. Without this, a mis-targeted
        query (two empty reads) or a wrong-quantity write would satisfy "unchanged" vacuously.
      * every column of every row must be identical afterwards. The snapshot is whole rows, so a
        change to a holding's identity, its cost-basis fields, or the parent's timestamps is caught;
        comparing a hand-picked column list would silently permit exactly those mutations.
    """
    problems: list[str] = []
    parent = before.get("portfolio")
    if not isinstance(parent, dict) or not parent:
        problems.append("no portfolio row was found for the synthetic user; the persisted-state read "
                        "returned nothing, so 'unchanged' would be vacuous")
    else:
        if _decimal(parent.get("version")) != _decimal(expected_version):
            problems.append("persisted portfolio version is " + repr(parent.get("version"))
                            + ", not the " + str(expected_version) + " the accepted write reported")
        for column in ("id", "user_id"):
            if not parent.get(column):
                problems.append("persisted portfolio row has no " + column)
    holdings = before.get("holdings")
    if not isinstance(holdings, list) or not holdings:
        problems.append("no holdings rows were found; 'unchanged' would be vacuous")
    else:
        observed = {}
        for row in holdings:
            if not isinstance(row, dict) or not row.get("asset_ticker"):
                problems.append("persisted holding row is malformed: " + repr(row)[:80])
                continue
            observed[row["asset_ticker"]] = row.get("quantity")
            if not row.get("id"):
                problems.append("persisted holding " + str(row.get("asset_ticker")) + " has no id")
        problems.extend(compare_quantities(submitted_holdings, observed, "persisted state"))
    if before != after:
        for key in sorted(set(before) | set(after)):
            if before.get(key) != after.get(key):
                problems.append(key + " changed across the rejected write: "
                                + json_dumps(before.get(key), sort_keys=True)[:300] + " -> "
                                + json_dumps(after.get(key), sort_keys=True)[:300])
    return {"id": "A4-db", "name": "complete persisted parent/holdings rows unchanged after the "
                                   "rejected write",
            "passed": not problems, "problems": problems, "before": before, "after": after,
            "expected_version": expected_version, "submitted_holdings": submitted_holdings}


def assert_startup(image_id: str, requested_reference: str, label: str, platform: str,
                   entrypoint: list[str], container_path: str, startup_seconds: float,
                   deadline_s: float, health: dict | None, expect_platform: str | None = None) -> dict:
    """A1. A real check, not a marker: the container must be running the requested identity, on a
    resolved platform, via the image's OWN entrypoint, within the deadline."""
    problems: list[str] = []
    if not _LOCAL_IMAGE_ID_RE.match(image_id or ""):
        problems.append("resolved image id " + repr(image_id)[:80] + " is not an immutable id")
    if label == LOCAL_PREPARATION and image_id != requested_reference:
        problems.append("running image " + str(image_id)[:19] + "... is not the requested "
                        + str(requested_reference)[:19] + "...")
    if not platform or "/" not in platform:
        problems.append("platform did not resolve (" + repr(platform) + ")")
    elif expect_platform and platform != expect_platform:
        problems.append("running platform " + platform + " is not the expected deployment platform "
                        + expect_platform)
    elif label == RELEASE_DIGEST and not expect_platform:
        problems.append("a release run must state its expected deployment platform")
    if not entrypoint:
        problems.append("image declares no ENTRYPOINT")
    elif container_path != entrypoint[0]:
        problems.append("container runs " + repr(container_path) + ", not the shipped entrypoint "
                        + repr(entrypoint[0]))
    if startup_seconds > deadline_s:
        problems.append("startup took " + str(startup_seconds) + "s, beyond the " + str(deadline_s) + "s deadline")
    return {"id": "A1", "name": "startup on the shipped entrypoint", "passed": not problems,
            "problems": problems, "startup_seconds": round(startup_seconds, 1),
            "entrypoint": entrypoint, "container_path": container_path, "image_id": image_id,
            "platform": platform, "expected_platform": expect_platform,
            "actuator_health_status": (health or {}).get("status"),
            "actuator_health_body": (health or {}).get("body")}


_UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def read_persisted_state(run: SmokeRun, pg_container: str, db: str, user: str, user_id: str) -> dict:
    """COMPLETE parent and child rows, read directly from PostgreSQL as JSON.

    `row_to_json` rather than a column list: the snapshot must include every column the schema has --
    the parent's timestamps, each holding's identity, and the cost-basis fields -- because a
    hand-picked projection can only detect the mutations someone thought of in advance. Ordering is
    by holding id so the sequence is deterministic across reads."""
    if not _UUID_RE.match(user_id):
        raise EvidenceError("refusing to query with a non-UUID user id: " + repr(user_id)[:80])

    def psql_json(sql: str) -> list[dict]:
        proc = run.exec(pg_container, ["psql", "-U", user, "-d", db, "-tAqc", sql])
        if proc.returncode != 0:
            raise EvidenceError("psql failed: " + (proc.stderr or "").strip()[:500])
        rows = []
        for line in proc.stdout.splitlines():
            line = line.strip()
            if line:
                rows.append(json_loads(line))
        return rows

    parents = psql_json("SELECT row_to_json(p) FROM portfolios p WHERE p.user_id = '" + user_id + "'")
    if len(parents) > 1:
        raise EvidenceError("the synthetic user has " + str(len(parents)) + " portfolio rows")
    holdings = psql_json(
        "SELECT row_to_json(h) FROM asset_holdings h JOIN portfolios p ON h.portfolio_id = p.id "
        "WHERE p.user_id = '" + user_id + "' ORDER BY h.id")
    return {"portfolio": parents[0] if parents else {}, "holdings": holdings,
            "holdings_count": len(holdings)}


# --------------------------------------------------------------------------------------
# Orchestration
# --------------------------------------------------------------------------------------

DB_NAME, DB_USER, DB_PASSWORD = "portfolio_db", "wealth_user", "wealth_pass"


def run_smoke(reference: str, label: str, *, postgres_image: str, redis_image: str,
              kafka_image: str, expect_jar_sha256: str | None, startup_deadline: int,
              keep: bool = False, expect_platform: str | None = None) -> dict:
    run = SmokeRun(keep=keep)
    started_at = time.time()
    evidence: dict = {
        "label": label,
        "run_id": run.run_id,
        "started_at_epoch": started_at,
        "image_reference": reference,
        "environment_differences": ENVIRONMENT_DIFFERENCES,
        "synthetic_user_id": SMOKE_USER_ID,
        "dependency_images": {"postgres": postgres_image, "redis": redis_image, "kafka": kafka_image},
        "assertions": [],
        "cleanup_errors": [],
        "candidate_ready": False,
        "candidate_ready_blocked_by": [
            "this harness evidences the image -> HTTP contract join only",
            "source governance (GC.5) is evidenced by check_b1_candidate_source.py and is BLOCKED",
            "R3 (repair_migrate_holdings) requires a separate owner-authorized operational check",
        ],
    }
    if label == LOCAL_PREPARATION:
        evidence["candidate_ready_blocked_by"].insert(
            0, "run against a LOCAL image id; Task 7.5a requires the authorized registry digest run")
    else:
        evidence["candidate_ready_blocked_by"].insert(
            0, "registry-digest run recorded; 7.5a acceptance is an owner decision on this evidence")

    try:
        # --- image identity -------------------------------------------------------------
        if label == RELEASE_DIGEST:
            # The runbook requires the SELECTED platform manifest, not merely the reference given:
            # an index digest does not identify what a single-platform target pulls.
            manifest = resolve_release_manifest(reference, expect_platform)
            evidence["registry_manifest"] = manifest
            evidence["registry_platform_manifest_digest"] = manifest["platform_manifest_digest"]
            _run(["docker", "pull", "--platform", expect_platform, reference], timeout=1800)
        image_id = docker_image_field(reference, "{{.Id}}")
        if label == LOCAL_PREPARATION and image_id != reference:
            raise EvidenceError("requested local image " + reference[:19] + "... resolves to "
                                + image_id[:19] + "...")
        platform = docker_image_field(image_id, "{{.Os}}/{{.Architecture}}")
        if expect_platform and platform != expect_platform:
            raise EvidenceError("the pulled image runs " + platform + ", not the expected deployment "
                                "platform " + expect_platform)
        entrypoint = json.loads(docker_image_field(image_id, "{{json .Config.Entrypoint}}"))
        if not entrypoint:
            raise EvidenceError("image declares no ENTRYPOINT; this harness runs the shipped "
                                "entrypoint and will not supply one")
        repo_digests = json.loads(docker_image_field(image_id, "{{json .RepoDigests}}") or "[]")
        evidence.update({"local_image_id": image_id, "platform": platform,
                         "shipped_entrypoint": entrypoint, "repo_digests": repo_digests,
                         "registry_manifest_digest": reference if label == RELEASE_DIGEST else None})

        # --- extracted JAR recheck ------------------------------------------------------
        workdir = SMOKE_WORKDIR_BASE / run.run_id
        workdir.mkdir(parents=True, exist_ok=True)
        extracted = workdir / "app.jar"
        extract_file(image_id, CONTAINER_INTERNAL_JAR_PATH, extracted)
        extracted_sha = sha256_file(extracted)
        evidence["extracted_jar_sha256"] = extracted_sha
        evidence["expected_jar_sha256"] = expect_jar_sha256
        if expect_jar_sha256:
            if extracted_sha != expect_jar_sha256.removeprefix("sha256:"):
                raise EvidenceError("extracted " + CONTAINER_INTERNAL_JAR_PATH + " hashes to "
                                    + extracted_sha + ", not the expected " + expect_jar_sha256)
        elif label == RELEASE_DIGEST:
            # Unreachable via the CLI (release requires it) -- belt and braces for a direct caller.
            raise EvidenceError("a release run must state the staged JAR hash the image has to carry")
        else:
            evidence.setdefault("unverified_joins", []).append(
                "no --expect-jar-sha256 was supplied: the extracted /app.jar was recorded but not "
                "compared to a verified artifact")
        extracted.unlink(missing_ok=True)

        # --- private network + disposable dependencies ----------------------------------
        run.create_network()
        pg = run.run_container("postgres", postgres_image, env={
            "POSTGRES_DB": DB_NAME, "POSTGRES_USER": DB_USER, "POSTGRES_PASSWORD": DB_PASSWORD})
        run.wait_until("PostgreSQL to accept connections",
                       lambda: run.exec(pg, ["pg_isready", "-U", DB_USER, "-d", DB_NAME]).returncode == 0,
                       120, container=pg)

        redis = run.run_container("redis", redis_image)
        run.wait_until("Redis to answer PING",
                       lambda: "PONG" in run.exec(redis, ["redis-cli", "ping"]).stdout,
                       60, container=redis)

        kafka = run.run_container("kafka", kafka_image, env={
            "KAFKA_NODE_ID": "1",
            "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT",
            "KAFKA_ADVERTISED_LISTENERS": "PLAINTEXT://kafka:9092",
            "KAFKA_PROCESS_ROLES": "broker,controller",
            "KAFKA_CONTROLLER_QUORUM_VOTERS": "1@kafka:9093",
            "KAFKA_LISTENERS": "PLAINTEXT://:9092,CONTROLLER://:9093",
            "KAFKA_INTER_BROKER_LISTENER_NAME": "PLAINTEXT",
            "KAFKA_CONTROLLER_LISTENER_NAMES": "CONTROLLER",
            "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR": "1",
            "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS": "0",
            "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR": "1",
            "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR": "1",
            "CLUSTER_ID": "NjY0NmE0NjY0NmE0NjY0Ng=="})
        run.wait_until("Kafka broker to serve API versions",
                       lambda: run.exec(kafka, ["kafka-broker-api-versions", "--bootstrap-server",
                                                "localhost:9092"], timeout=25).returncode == 0,
                       180, container=kafka, interval=3.0)

        # --- the artifact under test ----------------------------------------------------
        app_env = {
            "SPRING_PROFILES_ACTIVE": "prod,azure",
            "SPRING_DATASOURCE_URL": "jdbc:postgresql://postgres:5432/" + DB_NAME,
            "SPRING_DATASOURCE_USERNAME": DB_USER,
            "SPRING_DATASOURCE_PASSWORD": DB_PASSWORD,
            "KAFKA_BOOTSTRAP_SERVERS": "kafka:9092",
            "KAFKA_SECURITY_PROTOCOL": "PLAINTEXT",
            "KAFKA_SASL_USERNAME": "smoke-unused",
            "KAFKA_SASL_PASSWORD": "smoke-unused",
            "REDIS_URL": "redis://redis:6379",
        }
        app = run.run_container("app", image_id, env=app_env,
                                publish="127.0.0.1::" + str(APP_PORT), platform=expect_platform)
        evidence["app_env_keys"] = sorted(app_env)  # keys only: values may carry local credentials
        evidence["app_container_id"] = app

        # Prove the container really launched the image's own entrypoint rather than an override.
        actual_path = _run(["docker", "inspect", "-f", "{{.Path}}", app]).stdout.strip()
        if actual_path != entrypoint[0]:
            raise EvidenceError("container is not running the shipped entrypoint (" + actual_path
                                + " != " + entrypoint[0] + ")")

        base = "http://127.0.0.1:" + str(run.host_port(app, APP_PORT))
        headers = {X_USER_ID_HEADER: SMOKE_USER_ID}

        # A1 startup: the application's OWN contract answering is the readiness signal.
        def ready() -> bool:
            return http_request(base + "/api/assets", headers=headers, timeout=5.0)["status"] == 200

        startup_s = run.wait_until("the application to serve GET /api/assets",
                                   ready, startup_deadline, container=app, interval=2.0)
        health = None
        try:
            health = http_request(base + "/actuator/health", headers=headers, timeout=10.0)
        except EvidenceError:
            pass  # recorded as evidence only; the contract endpoint above is the gate
        a1 = assert_startup(image_id, reference, label, platform, entrypoint, actual_path,
                            startup_s, startup_deadline, health, expect_platform)
        evidence["assertions"].append(a1)
        if not a1["passed"]:
            raise EvidenceError("A1 failed: " + "; ".join(a1["problems"]))

        # A2 assets
        assets_exchange = http_request(base + "/api/assets", headers=headers)
        a2 = assert_assets(assets_exchange)
        evidence["assertions"].append(a2)
        if not a2["passed"]:
            raise EvidenceError("A2 failed: " + "; ".join(a2["problems"]))

        # A3 nontrivial composition. State is read ONCE here; the value is reused verbatim by A4.
        tickers = a2["tickers_sample"][:3]
        if len(tickers) < 2:
            raise EvidenceError("catalog supplied fewer than two tickers; a nontrivial composition "
                                "needs at least two holdings")
        # Distinct quantities, so a response that swapped or defaulted them cannot still match.
        submitted_holdings = dict(zip(tickers, ["1.5", "2", "0.25"][:len(tickers)]))
        submitted_version = 0  # a fresh synthetic user has no portfolio row yet
        request_body = {"expectedVersion": submitted_version,
                        "holdings": [{"ticker": t, "quantity": q} for t, q in submitted_holdings.items()]}
        compose_exchange = http_request(base + "/api/portfolio/holdings", method="PUT",
                                        body=request_body, headers=headers)
        a3 = assert_composition(compose_exchange, submitted_version, submitted_holdings)
        evidence["assertions"].append(a3)
        if not a3["passed"]:
            raise EvidenceError("A3 failed: " + "; ".join(a3["problems"]))
        current_version = a3["observed_version"]

        state_before = read_persisted_state(run, pg, DB_NAME, DB_USER, SMOKE_USER_ID)

        # A4 replay the SAME stale expectedVersion. No refresh, no retry.
        conflict_exchange = http_request(base + "/api/portfolio/holdings", method="PUT",
                                         body=request_body, headers=headers)
        a4 = assert_conflict(conflict_exchange, submitted_version, current_version)
        evidence["assertions"].append(a4)

        state_after = read_persisted_state(run, pg, DB_NAME, DB_USER, SMOKE_USER_ID)
        a4db = assert_state_unchanged(state_before, state_after, current_version, submitted_holdings)
        evidence["assertions"].append(a4db)
        if not (a4["passed"] and a4db["passed"]):
            raise EvidenceError("A4 failed: " + "; ".join(a4["problems"] + a4db["problems"]))

        evidence["app_log_tail"] = run.logs(app, tail=40)
        evidence["status"] = "PASS"
    except EvidenceError as exc:
        evidence["status"] = "FAIL"
        evidence["error"] = str(exc)[:4000]
    finally:
        evidence["cleanup_errors"] = run.cleanup()
        # `--keep` is debugging, not evidence: say plainly that cleanup was skipped and name what is
        # still running, so no reader can mistake a retained environment for a verified one.
        evidence["retained_resources"] = run.retained() if keep else []
        if keep:
            evidence.setdefault("unverified_joins", []).append(
                "--keep was used: this run's containers and network were left running and cleanup "
                "was never verified, so this result does not satisfy the evidence cleanup gate")
            evidence["candidate_ready_blocked_by"].append(
                "run used --keep (debugging retention); cleanup unverified")
        # The extraction workdir is this run's too, and is removed with the same "only what we
        # created" rule: one directory named by this run's id, never a broader sweep.
        if not keep:
            workdir = SMOKE_WORKDIR_BASE / run.run_id
            try:
                if workdir.is_dir():
                    for leftover in workdir.iterdir():
                        leftover.unlink()
                    workdir.rmdir()
                # ...and the shared parent, but only while it is empty: a concurrent run's directory
                # inside it is not ours to remove, and OSError here simply means someone else is using it.
                try:
                    workdir.parent.rmdir()
                except OSError:
                    pass
            except OSError as exc:
                evidence["cleanup_errors"].append("could not remove " + str(workdir) + ": " + str(exc))
        # Derived LAST, from the final error list: the workdir errors above are appended after the
        # container/network ones, so computing this any earlier would report success over a failure.
        evidence["cleanup_verified"] = not keep and not evidence["cleanup_errors"]
        evidence["finished_at_epoch"] = time.time()
        evidence["duration_seconds"] = round(evidence["finished_at_epoch"] - started_at, 1)

    # A cleanup failure is a run failure: leaked resources mean the next run's environment is unknown.
    if evidence["cleanup_errors"] and evidence["status"] == "PASS":
        evidence["status"] = "FAIL"
        evidence["error"] = "run assertions passed but cleanup failed: " + "; ".join(evidence["cleanup_errors"])
    failed = [a for a in evidence["assertions"] if not a.get("passed")]
    if failed and evidence["status"] == "PASS":  # defensive: never report PASS over a failed assertion
        evidence["status"] = "FAIL"
        evidence["error"] = "assertion(s) failed: " + ", ".join(a["id"] for a in failed)
    return evidence


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--local-image", default=None,
                        help="immutable local image id `sha256:<64 hex>` (LOCAL_PREPARATION)")
    parser.add_argument("--registry-digest", default=None,
                        help="immutable `<repository>@sha256:<64 hex>` (Task 7.5a; owner-authorized)")
    parser.add_argument("--authorized-release-run", action="store_true",
                        help="assert that the registry-digest run is owner-authorized for this digest")
    parser.add_argument("--expect-jar-sha256", default=None,
                        help="staged JAR sha256 the image must still carry at /app.jar (required for a "
                             "release run)")
    parser.add_argument("--expect-platform", default=None,
                        help="deployment platform the image must run, e.g. linux/amd64 (required for "
                             "a release run; pins the pull and is compared after it)")
    parser.add_argument("--postgres-image", default="postgres:18.4")
    parser.add_argument("--redis-image", default="redis:7-alpine")
    parser.add_argument("--kafka-image", default="confluentinc/cp-kafka:8.1.3")
    parser.add_argument("--startup-deadline", type=int, default=240)
    parser.add_argument("--keep", action="store_true",
                        help="leave this run's containers up for debugging (never for evidence)")
    parser.add_argument("--out", default=None)
    args = parser.parse_args(argv)

    try:
        reference, label = resolve_image_reference(
            args.local_image, args.registry_digest, args.authorized_release_run,
            expect_jar_sha256=args.expect_jar_sha256, expect_platform=args.expect_platform,
            keep=args.keep)
    except EvidenceError as exc:
        print("ERROR: " + str(exc), file=sys.stderr)
        return 1

    evidence = run_smoke(reference, label, postgres_image=args.postgres_image,
                         redis_image=args.redis_image, kafka_image=args.kafka_image,
                         expect_jar_sha256=args.expect_jar_sha256,
                         startup_deadline=args.startup_deadline, keep=args.keep,
                         expect_platform=args.expect_platform)
    if args.out:
        Path(args.out).write_text(json_dumps(evidence, indent=2) + "\n", encoding="utf-8")
        print("wrote smoke evidence to " + args.out)
    print("candidate image smoke: " + evidence["status"] + " [" + evidence["label"] + "] "
          + "image=" + str(evidence.get("local_image_id", ""))[:19] + "... "
          + "platform=" + str(evidence.get("platform")))
    for assertion in evidence["assertions"]:
        print("  " + assertion["id"] + " " + ("PASS" if assertion.get("passed") else "FAIL")
              + " -- " + assertion["name"]
              + ("" if assertion.get("passed") else ": " + "; ".join(assertion["problems"])[:300]))
    for note in evidence.get("unverified_joins", []):
        print("  UNVERIFIED: " + note)
    if evidence.get("retained_resources"):
        print("  retained (cleanup skipped): " + ", ".join(evidence["retained_resources"]))
    if evidence.get("error"):
        print("  error: " + evidence["error"][:600], file=sys.stderr)
    for problem in evidence["cleanup_errors"]:
        print("  cleanup: " + problem, file=sys.stderr)
    print("  candidate_ready: false  blocked_by: " + "; ".join(evidence["candidate_ready_blocked_by"]))
    return 0 if evidence["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
