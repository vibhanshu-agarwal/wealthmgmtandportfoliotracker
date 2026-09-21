#!/usr/bin/env python3
"""CI wiring contract for the product Semantic Version.

The version contract is only as strong as the jobs that run it, so this pins the wiring rather
than trusting it:

  * required CI runs the validator in BRANCH mode inside `static-guard`, the job that is
    required and never skips;
  * a dedicated workflow runs it in TAG mode on `v*` pushes, with read-only permissions and
    nothing else attached -- no integration, Pact, Docker, publish or deploy step;
  * the tag name reaches the validator only through a step-local environment variable. An
    inline `${{ github.ref_name }}` in a shell script is a script-injection vector (a tag name
    is attacker-chosen text), and a hard-coded tag would satisfy a looser check;
  * every deployment or image-publishing workflow has EXACTLY its intended trigger shape.
    Checking only for forbidden `tags:` or `release:` text is insufficient: an added
    `push:` or `workflow_run:` trigger is just as much a second route to production;
  * all four Azure service Dockerfiles are really built, because each now copies VERSION.

Where a piece is a release gate -- the tag workflow and the two new static-guard steps -- it is
pinned by EXACT content rather than by a list of forbidden keys. A forbidden list has to
anticipate every neutering edit (`continue-on-error`, a step `if:`, `|| true` after the
checksum, a checkout `ref:`), and an earlier version of this file missed several of them; an
exact pin rejects all of them by construction, and a deliberate change simply updates the pin.

Triggers and filters are compared as parsed structure, by equality, and the parser fails CLOSED
on any line it cannot classify. Workflow comments are stripped first, because the existing
comments themselves mention trigger names such as `workflow_dispatch`.

Stdlib only (no PyYAML), matching the sibling contract tests, and reusing their helpers so the
two cannot drift apart. Runs in the required `static-guard` job.
"""

from __future__ import annotations

import importlib.util
import re
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORKFLOWS = REPO / ".github" / "workflows"
CI = WORKFLOWS / "ci-verification.yml"
TAG_WORKFLOW = WORKFLOWS / "release-tag-validation.yml"
SIBLING = REPO / "scripts" / "tests" / "test_deploy_frontend_only_mode.py"

ACTIONLINT_VERSION = "1.7.12"
ACTIONLINT_SHA256 = "8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8"

AZURE_SERVICES = {
    "api-gateway": "api-gateway/Dockerfile.azure",
    "portfolio-service": "portfolio-service/Dockerfile.azure",
    "market-data-service": "market-data-service/Dockerfile.azure",
    "insight-service": "insight-service/Dockerfile.azure",
}

# The complete tag workflow, comments and blank lines aside. Anything added -- a job-level or
# step-level `continue-on-error`, an `if:`, a checkout `ref:`, a `defaults:` block, another
# step or job, a permission -- is a difference from this list.
TAG_WORKFLOW_SHAPE = [
    "name: Release Tag Validation",
    "on:",
    "  push:",
    '    tags: ["v*"]',
    "permissions:",
    "  contents: read",
    "jobs:",
    "  product-version:",
    "    runs-on: ubuntu-latest",
    "    steps:",
    "      - uses: actions/checkout@v4",
    "      - uses: actions/setup-python@v5",
    "        with:",
    '          python-version: "3.12"',
    "      - name: Validate product release tag",
    "        env:",
    "          RELEASE_TAG: ${{ github.ref_name }}",
    "        run: |",
    "          python scripts/tests/test_validate_product_version.py -v",
    '          python scripts/validate_product_version.py --ref-type tag --ref-name "$RELEASE_TAG"',
]

# The two new required static-guard steps, exactly. For the actionlint step this is what makes
# the checksum load-bearing: `sha256sum -c - || true` or an inserted `set +e` would leave a
# presence check satisfied while a tampered binary ran inside a required job.
ACTIONLINT_STEP_SHAPE = [
    "      - name: Validate release-tag workflow schema",
    "        run: |",
    "          set -euo pipefail",
    "          curl --fail --location --show-error --silent \\",
    "            --retry 5 --retry-all-errors --retry-delay 2 \\",
    "            --connect-timeout 15 --max-time 120 \\",
    f"            --output actionlint_{ACTIONLINT_VERSION}_linux_amd64.tar.gz \\",
    "            https://github.com/rhysd/actionlint/releases/download/"
    f"v{ACTIONLINT_VERSION}/actionlint_{ACTIONLINT_VERSION}_linux_amd64.tar.gz",
    f'          echo "{ACTIONLINT_SHA256}  actionlint_{ACTIONLINT_VERSION}_linux_amd64.tar.gz"'
    " | sha256sum -c -",
    f"          tar -xzf actionlint_{ACTIONLINT_VERSION}_linux_amd64.tar.gz actionlint",
    "          ./actionlint -shellcheck= .github/workflows/release-tag-validation.yml",
]
PRODUCT_VERSION_STEP_SHAPE = [
    "      - name: Product version contract",
    "        run: |",
    "          set -euo pipefail",
    "          python scripts/tests/test_validate_product_version.py -v",
    "          python scripts/tests/test_product_version_ci_wiring.py -v",
    "          python scripts/validate_product_version.py --ref-type branch",
]

# The probe cases whose invocations must survive the refactor. The function definitions alone
# would still satisfy the classifier's cmp-oracle pin with every invocation deleted.
PROBE_INVOCATIONS = [
    'run_case "blank case" "" $\'blank\\n\'',
    'run_case "nonblank case" "smoke-test-value" $\'nonblank\\n\'',
]


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise FileNotFoundError(path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


# Reused, not copied: the sibling's comment stripper, job splitter and step extractor are the
# ones every other workflow contract in this repository is built on.
_sibling = _load("deploy_frontend_only_contract", SIBLING)
_read = _sibling._read
_without_comments = _sibling._without_comments
_jobs = _sibling._jobs
_named_block = _sibling._named_block
_step_names = _sibling._step_names
_step_count = _sibling._step_count
_normalized = _sibling._normalized

# `types` is captured too: `pull_request: types: [closed]` changes when a workflow fires.
_FILTER_KEYS = (
    "branches", "branches-ignore", "tags", "tags-ignore", "paths", "paths-ignore", "types",
)


def _code(path: Path) -> str:
    return _without_comments(_read(path))


def _on_block(path: Path) -> str:
    code = _code(path)
    tops = re.findall(r"(?m)^on:", code)
    if len(tops) != 1:
        raise AssertionError(f"{path.name}: expected exactly one top-level on:, found {len(tops)}")
    # Stops at the next unindented key: only indented or blank lines belong to `on:`.
    match = re.search(r"(?m)^on:[ \t]*\n((?:(?:[ \t].*)?\n)*)", code + "\n")
    if match is None:
        raise AssertionError(f"{path.name}: `on:` must be a block mapping")
    return match.group(1)


def _flow_sequence(value: str) -> list[str] | None:
    value = value.strip()
    if not (value.startswith("[") and value.endswith("]")):
        return None
    inner = value[1:-1].strip()
    return [] if not inner else [item.strip().strip("\"'") for item in inner.split(",")]


def trigger_shape(path: Path) -> dict[str, dict[str, list[str]]]:
    """{trigger: {filter: [values]}} parsed from the `on:` block. Fails closed.

    Every line directly under `on:` (two-space indent) must be a plain `name:` trigger key;
    anything else there -- a quoted key, `push :`, an inline value, a tab -- is an error rather
    than a line to skip, because a line the parser skips is a trigger the equality check never
    sees. Branch/tag/path/type filters are captured as values; other nested keys (dispatch
    `inputs`, `workflow_call` `secrets`) shape the payload, not when the workflow fires, and are
    deliberately ignored.

    Failing closed has a known cost: some valid YAML that no workflow here currently uses is
    rejected too -- an indentless filter sequence (`    - main` directly under `branches:`),
    trigger settings nested at an odd indent, or a trailing comment on a trigger line. Each
    failure names the line, and the remedy is to reformat it in the house style. Loosening the
    parser to accept them would reopen the silent-skip route this exists to close.
    """
    shape: dict[str, dict[str, list[str]]] = {}
    trigger: str | None = None
    filter_key: str | None = None
    for line in _on_block(path).splitlines():
        if not line.strip():
            continue
        leading = line[: len(line) - len(line.lstrip())]
        if "\t" in leading:
            raise AssertionError(f"{path.name}: tab indentation in on: {line!r}")
        indent = len(leading)
        if indent == 2:
            top = re.fullmatch(r"  ([A-Za-z_][A-Za-z0-9_]*):[ \t]*", line)
            if top is None:
                raise AssertionError(f"{path.name}: unrecognised trigger line {line!r}")
            trigger = top.group(1)
            if trigger in shape:
                raise AssertionError(f"{path.name}: trigger {trigger!r} declared twice")
            shape[trigger] = {}
            filter_key = None
            continue
        if trigger is None or indent < 2 or indent in (3, 5):
            raise AssertionError(f"{path.name}: unrecognised line in on: {line!r}")
        if indent == 4:
            nested = re.fullmatch(r"    ([A-Za-z_][A-Za-z0-9_-]*):(.*)", line)
            if nested is None:
                raise AssertionError(f"{path.name}: unrecognised trigger setting {line!r}")
            key, rest = nested.group(1), nested.group(2)
            if key in _FILTER_KEYS:
                sequence = _flow_sequence(rest)
                shape[trigger][key] = [] if sequence is None else sequence
                filter_key = key if sequence is None else None
            else:
                filter_key = None
            continue
        item = re.fullmatch(r"      - (.*)", line)
        if item and filter_key is not None:
            shape[trigger][filter_key].append(item.group(1).strip().strip("\"'"))
    return shape


def _run_lines(step: str) -> list[str]:
    """The non-blank lines of a step's `run: |` block, stripped."""
    match = re.search(r"(?m)^        run: \|[ \t]*\n((?:(?:          .*)?\n)*)", step + "\n")
    if match is None:
        single = re.search(r"(?m)^        run: (.+?)[ \t]*$", step)
        if single is None:
            raise AssertionError(f"step has no run: command:\n{step}")
        return [single.group(1)]
    return [line.strip() for line in match.group(1).splitlines() if line.strip()]


def _mapping_keys(block: str, indent: int) -> list[str]:
    """Keys written at exactly `indent` spaces, in order. Fails closed.

    Every non-blank line at exactly that indent must be a plain `key:`. A quoted key
    (`"continue-on-error": true`) or a merge key (`<<: *x`) is valid YAML and would be invisible
    to a pattern that only finds plain keys -- so it is an error here, not a line to skip.
    """
    keys: list[str] = []
    for line in block.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if len(line) - len(line.lstrip(" ")) != indent:
            continue
        match = re.fullmatch(rf" {{{indent}}}([A-Za-z_][A-Za-z0-9_-]*):(?:[ \t].*)?", line)
        if match is None:
            raise AssertionError(f"non-plain key at indent {indent}: {line!r}")
        keys.append(match.group(1))
    return keys


def _sub_block(block: str, heading: str) -> str:
    """The lines under `heading` that are indented deeper than it."""
    lines = block.splitlines()
    for index, line in enumerate(lines):
        if line.rstrip() == heading:
            depth = len(heading) - len(heading.lstrip())
            body = []
            for following in lines[index + 1:]:
                if following.strip() and len(following) - len(following.lstrip()) <= depth:
                    break
                body.append(following)
            return "\n".join(body) + "\n"
    raise AssertionError(f"missing block {heading.strip()!r}")


def _matrix_include(job: str) -> list[dict[str, str]]:
    body = _sub_block(job, "        include:")
    entries: list[dict[str, str]] = []
    for line in body.splitlines():
        if not line.strip():
            continue
        first = re.fullmatch(r"          - ([A-Za-z_][A-Za-z0-9_-]*):[ \t]*(.+?)[ \t]*", line)
        rest = re.fullmatch(r"            ([A-Za-z_][A-Za-z0-9_-]*):[ \t]*(.+?)[ \t]*", line)
        if first:
            entries.append({first.group(1): first.group(2)})
        elif rest and entries:
            key = rest.group(1)
            if key in entries[-1]:
                raise AssertionError(f"matrix entry repeats key {key!r}")
            entries[-1][key] = rest.group(2)
        else:
            raise AssertionError(f"unrecognised matrix line: {line!r}")
    return entries


def _actionlint_pin(step: str) -> tuple[str, str]:
    """(version, sha256) from an actionlint install step, cross-checked within the step."""
    urls = re.findall(
        r"releases/download/v(\d+\.\d+\.\d+)/actionlint_(\d+\.\d+\.\d+)_linux_amd64\.tar\.gz",
        step,
    )
    if len(urls) != 1:
        raise AssertionError(f"expected exactly one actionlint download URL, found {urls}")
    tag_version, file_version = urls[0]
    if tag_version != file_version:
        raise AssertionError(f"URL tag v{tag_version} disagrees with file {file_version}")
    sums = re.findall(r"([0-9a-f]{64})\s+actionlint_(\d+\.\d+\.\d+)_linux_amd64\.tar\.gz", step)
    if len(sums) != 1:
        raise AssertionError(f"expected exactly one pinned actionlint checksum, found {sums}")
    digest, checked_version = sums[0]
    if checked_version != tag_version:
        raise AssertionError(f"checksum is for {checked_version}, download is {tag_version}")
    return tag_version, digest


class TriggerContractTest(unittest.TestCase):
    EXPECTED = {
        "deploy.yml": {"workflow_dispatch": {}},
        "deploy-azure.yml": {"workflow_call": {}},
        "deploy-azure-frontend.yml": {"workflow_call": {}},
        "deploy-aws.yml": {"workflow_call": {}},
        "frontend-cd.yml": {"workflow_dispatch": {}},
        "terraform.yml": {"workflow_dispatch": {}},
        "terraform-azure.yml": {
            "workflow_dispatch": {},
            "pull_request": {
                "paths": [
                    "infrastructure/terraform/azure/**",
                    ".github/workflows/terraform-azure.yml",
                ]
            },
        },
        "ci-verification.yml": {
            "push": {"branches": ["main", "architecture/**", "feature/**"]},
            "pull_request": {"branches": ["main", "architecture/**"]},
        },
    }

    def test_every_inspected_workflow_has_exactly_one_top_level_on(self):
        for name in (*self.EXPECTED, TAG_WORKFLOW.name):
            with self.subTest(workflow=name):
                self.assertEqual(1, len(re.findall(r"(?m)^on:", _code(WORKFLOWS / name))))

    def test_deployment_and_publishing_triggers_are_exactly_the_allowlist(self):
        # Equality, not a forbidden-text search: an added trigger of ANY kind is a new route in.
        for name, shape in self.EXPECTED.items():
            with self.subTest(workflow=name):
                self.assertEqual(shape, trigger_shape(WORKFLOWS / name))

    def test_no_deployment_or_publishing_workflow_listens_to_tags_or_releases(self):
        # Implied by the equality above; stated separately so the failure names the rule.
        for name in self.EXPECTED:
            shape = trigger_shape(WORKFLOWS / name)
            with self.subTest(workflow=name):
                self.assertTrue(shape, "an empty trigger shape would make this test vacuous")
                self.assertNotIn("release", shape)
                self.assertNotIn("workflow_run", shape)
                for trigger, filters in shape.items():
                    self.assertNotIn("tags", filters, f"{trigger} filters on tags")

    def test_terraform_azure_apply_stays_dispatch_and_action_gated(self):
        apply = _jobs(_code(WORKFLOWS / "terraform-azure.yml"), strict=False)["apply"]
        condition = re.search(r"(?m)^    if: (.+?)[ \t]*$", apply)
        self.assertIsNotNone(condition, "terraform-azure.yml apply job must keep its if:")
        self.assertEqual(
            "github.event_name == 'workflow_dispatch' && github.event.inputs.action == 'apply'",
            condition.group(1),
        )


class StaticGuardContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.jobs = _jobs(_code(CI), strict=False)
        cls.guard = cls.jobs["static-guard"]

    def test_product_version_contract_is_exactly_the_branch_mode_step(self):
        step = _named_block(self.guard, "Product version contract")
        self.assertEqual(PRODUCT_VERSION_STEP_SHAPE, _normalized(step))

    def test_actionlint_step_is_exactly_the_pinned_checksummed_retry_bounded_command(self):
        step = _named_block(self.guard, "Validate release-tag workflow schema")
        self.assertEqual(ACTIONLINT_STEP_SHAPE, _normalized(step))
        self.assertEqual((ACTIONLINT_VERSION, ACTIONLINT_SHA256), _actionlint_pin(step))

    def test_the_new_steps_run_after_python_setup(self):
        names = _step_names(self.guard)
        # Every step must be visible to the name-based checks; an unnamed step would not be.
        self.assertEqual(len(re.findall(r"(?m)^      - uses: ", self.guard)) + len(names),
                         _step_count(self.guard))
        python_setup = self.guard.index("uses: actions/setup-python@v5")
        for name in ("Validate release-tag workflow schema", "Product version contract"):
            with self.subTest(step=name):
                self.assertGreater(self.guard.index(f"- name: {name}"), python_setup)

    def test_actionlint_pins_match_the_deploy_workflow_contract_job(self):
        # Two copies of one pin drift silently. Equality here means a bump must touch both.
        advisory = _named_block(
            self.jobs["deploy-workflow-contract"],
            "Install pinned actionlint (GitHub Actions schema validation)",
        )
        required = _named_block(self.guard, "Validate release-tag workflow schema")
        self.assertEqual(_actionlint_pin(advisory), _actionlint_pin(required))

    def test_ci_verification_listens_to_no_tag(self):
        shape = trigger_shape(CI)
        self.assertTrue(shape, "an empty trigger shape would make this test vacuous")
        for trigger, filters in shape.items():
            with self.subTest(trigger=trigger):
                self.assertNotIn("tags", filters)


class ReleaseTagWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.code = _code(TAG_WORKFLOW)
        cls.jobs = _jobs(cls.code)

    def test_the_whole_workflow_is_exactly_the_validation_only_shape(self):
        self.assertEqual(TAG_WORKFLOW_SHAPE, _normalized(self.code))

    def test_every_top_level_line_is_a_plain_key(self):
        # A quoted key (`"defaults":`) is valid YAML and invisible to an unquoted-key scan.
        for line in self.code.splitlines():
            if line and not line[0].isspace():
                with self.subTest(line=line):
                    self.assertRegex(line, r"^[A-Za-z_][A-Za-z0-9_-]*:")

    def test_triggers_only_on_v_tag_pushes(self):
        self.assertEqual({"push": {"tags": ["v*"]}}, trigger_shape(TAG_WORKFLOW))

    def test_one_job_with_only_checkout_python_and_the_validation_step(self):
        self.assertEqual(["product-version"], list(self.jobs))
        job = self.jobs["product-version"]
        self.assertEqual({"actions/checkout@v4", "actions/setup-python@v5"},
                         set(re.findall(r"uses:\s*(\S+)", job)))
        self.assertEqual(["Validate product release tag"], _step_names(job))
        self.assertEqual(3, _step_count(job))

    def test_tag_name_reaches_the_validator_only_through_step_local_release_tag(self):
        step = _named_block(self.jobs["product-version"], "Validate product release tag")
        self.assertIn("          RELEASE_TAG: ${{ github.ref_name }}", _normalized(step))
        lines = _run_lines(step)
        self.assertIn(
            'python scripts/validate_product_version.py --ref-type tag --ref-name "$RELEASE_TAG"',
            lines,
        )
        # No expression may be interpolated into the script: the tag name is attacker text.
        for line in lines:
            self.assertNotIn("${{", line)


class AzureImageMatrixTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.jobs = _jobs(_code(CI), strict=False)
        cls.job = cls.jobs["azure-image-smoke-test"]
        cls.matrix = _matrix_include(cls.job)

    def test_job_level_keys_are_exactly_the_build_matrix_shape(self):
        # No `continue-on-error` above all: a failed job with it reports `success` through
        # `needs.<job>.result`, so ci-required would pass with every build broken. Pinning the
        # key list also excludes `if:`, `permissions:`, `environment:` and `services:`.
        self.assertEqual(["runs-on", "needs", "timeout-minutes", "strategy", "steps"],
                         _mapping_keys(self.job, 4))
        self.assertRegex(self.job, r"(?m)^    needs: unit-tests$")
        self.assertRegex(self.job, r"(?m)^    timeout-minutes: 45$")

    def test_include_is_the_only_matrix_dimension(self):
        # One added dimension (`os: [ubuntu-latest]`) makes GitHub merge every include entry
        # into that single combination, each overwriting the last: four builds become one, the
        # probe never runs, and the job still goes green.
        strategy = _sub_block(self.job, "    strategy:")
        self.assertEqual(["fail-fast", "matrix"], _mapping_keys(strategy, 6))
        self.assertRegex(strategy, r"(?m)^      fail-fast: false$")
        self.assertEqual(["include"], _mapping_keys(_sub_block(strategy, "      matrix:"), 8))

    def test_matrix_is_exactly_the_four_azure_service_dockerfiles(self):
        self.assertEqual(AZURE_SERVICES, {e["service"]: e["dockerfile"] for e in self.matrix})
        self.assertEqual(len(AZURE_SERVICES), len(self.matrix))
        for entry in self.matrix:
            with self.subTest(service=entry.get("service")):
                self.assertEqual({"service", "dockerfile", "image", "run_probe"}, set(entry))
        self.assertEqual(len(self.matrix), len({e["image"] for e in self.matrix}))

    def test_steps_are_exactly_checkout_build_and_probe(self):
        self.assertEqual(["Build Azure service image", "Run API Gateway probe smoke cases"],
                         _step_names(self.job))
        self.assertEqual(3, _step_count(self.job))
        self.assertEqual({"actions/checkout@v4"}, set(re.findall(r"uses:\s*(\S+)", self.job)))

    def test_every_entry_is_built_as_a_full_image_and_none_is_skipped(self):
        # Every entry builds: a conditional or stage-targeted build would leave a Dockerfile's
        # `COPY VERSION VERSION` route unmeasured while the job still went green.
        step = _named_block(self.job, "Build Azure service image")
        self.assertEqual(
            [
                "      - name: Build Azure service image",
                '        run: docker build -f "${{ matrix.dockerfile }}" -t "${{ matrix.image }}" .',
            ],
            _normalized(step),
        )

    def test_probe_cases_run_only_for_the_api_gateway_entry(self):
        probing = [e["service"] for e in self.matrix if e["run_probe"] == "true"]
        self.assertEqual(["api-gateway"], probing)
        self.assertEqual({"false"},
                         {e["run_probe"] for e in self.matrix if e["service"] != "api-gateway"})
        gateway = next(e for e in self.matrix if e["service"] == "api-gateway")
        # The probe script runs this exact tag; it must be the image the build step produced.
        self.assertEqual("probe-smoke-test", gateway["image"])
        probe = _named_block(self.job, "Run API Gateway probe smoke cases")
        self.assertEqual(["if", "run"], _mapping_keys(probe, 8))
        self.assertRegex(probe, r"(?m)^        if: matrix\.run_probe$")
        lines = _run_lines(probe)
        self.assertNotIn("docker build", "\n".join(lines))
        # Exactly one definition, ahead of every invocation: a no-op `run_case() { :; }`
        # redefined after the real one would leave the invocations "present" while testing
        # nothing. The probe's own content is deliberately NOT pinned here -- it belongs to the
        # probe's owners, and this contract only has to prove the refactor did not neuter it.
        definitions = [i for i, line in enumerate(lines) if re.match(r"run_case\s*\(\)", line)]
        self.assertEqual(1, len(definitions), "run_case must be defined exactly once")
        for invocation in PROBE_INVOCATIONS:
            self.assertGreater(lines.index(invocation), definitions[0])
        for invocation in PROBE_INVOCATIONS:
            with self.subTest(invocation=invocation):
                self.assertEqual(1, lines.count(invocation))
        self.assertEqual(len(PROBE_INVOCATIONS),
                         sum(1 for line in lines if line.startswith("run_case ")))
        self.assertIn("-jar /replica-token.jar api-gateway--0000000-abcdefg \\", lines)

    def test_is_build_only(self):
        for forbidden in (r"docker (?:login|push|tag)\b", r"--push\b", r"login-action",
                          r"build-push-action", r"\bsecrets\.", r"id-token", r"deploy"):
            with self.subTest(forbidden=forbidden):
                self.assertNotRegex(self.job, forbidden)

    def test_job_keeps_its_place_in_the_required_graph(self):
        required = self.jobs["ci-required"]
        self.assertRegex(required, r"(?m)^      - azure-image-smoke-test\s*$")
        self.assertRegex(required, r'(?m)^\s+"azure-image-smoke-test": \$chain,?\s*$')


if __name__ == "__main__":
    unittest.main()
