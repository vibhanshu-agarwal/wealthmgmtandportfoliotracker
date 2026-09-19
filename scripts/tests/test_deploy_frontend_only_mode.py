#!/usr/bin/env python3
"""Contract for the Wave 10.2 Step B ``frontend-only`` deploy mode.

Step B flips the Asset Picker exposure flags and needs a fresh static-export build. Until
this mode existed, the only Azure frontend deploy was ``deploy_mode == 'full'`` in
deploy-azure.yml, which also rebuilds and updates all four backends and the refresh job --
replacing the revisions the Wave 9 Step A / Task 8.9 production attestations name.

What must stay true for the mode to be safe, and where each property is pinned:

  * The validator gives ``frontend-only`` its own explicit input rule, and a mode added to
    ``VALID_MODES`` with no rule fails closed instead of validating by fall-through.
  * deploy.yml routes every valid mode to exactly one cloud job. The dangerous failure is a
    new mode reaching deploy-azure.yml, which forwards only ``services`` and
    ``prebuilt_digest``: empty ``services`` is inferred as a full deploy there.
  * The new reusable workflow has no Container App, registry, image or infrastructure
    write surface, and its frontend job holds no Azure token.
  * The frontend job builds and uploads exactly as deploy-azure.yml's ``deploy-frontend``
    does, so the two copies cannot drift apart silently.
  * A before/after Container App snapshot proves the backends were untouched, and it fails
    closed on anything it cannot prove.

Stdlib only (no PyYAML), matching the sibling contract tests. This file runs in the required
``static-guard`` job: the sibling deploy tests run in the advisory ``deploy-workflow-contract``
job, which cannot block a merge.
"""

from __future__ import annotations

import ast
import contextlib
import importlib.util
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
WORKFLOWS = REPO / ".github" / "workflows"
SCRIPTS = WORKFLOWS / "scripts"
DISPATCHER = WORKFLOWS / "deploy.yml"
DEPLOY_AZURE = WORKFLOWS / "deploy-azure.yml"
DEPLOY_AWS = WORKFLOWS / "deploy-aws.yml"
FRONTEND_ONLY = WORKFLOWS / "deploy-azure-frontend.yml"
CI_WORKFLOW = WORKFLOWS / "ci-verification.yml"

# The only secrets the frontend-only path may see: the read-only Azure snapshot credentials
# and the Static Web Apps upload token. deploy.yml maps exactly these by name and the
# reusable workflow declares exactly these; there is no `secrets: inherit` on this path.
AZURE_SNAPSHOT_SECRETS = ("AZURE_CLIENT_ID", "AZURE_TENANT_ID", "AZURE_SUBSCRIPTION_ID")
FRONTEND_ONLY_SECRETS = (*AZURE_SNAPSHOT_SECRETS, "SWA_DEPLOYMENT_TOKEN")

# The environment the snapshot script really runs in. The runtime tests use it so a branch on the
# resource group or on CI (an `az ... update`, or a fabricated identity, only when it looks like
# production) is executed by the tests instead of being skipped by a made-up name.
PROD_ENV = {"AZURE_RG": "wealth-azure-prod-rg", "GITHUB_ACTIONS": "true", "CI": "true"}

SHA = "9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900"
OTHER_SHA = "db1db2f8ab4e9d2291864d20490177f100e10055"
MAIN_REF = "refs/heads/main"
PORTFOLIO_DIGEST = "wealthprodacr.azurecr.io/portfolio-service@sha256:" + "a" * 64


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise FileNotFoundError(path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _without_comments(text: str) -> str:
    return "\n".join(
        line for line in text.splitlines() if not line.lstrip().startswith("#")
    )


# A `::notice::` / `::error::` annotation line, matched for the one job whose message wording
# is deliberately not pinned. It must be exactly one double-quoted string whose only
# expansion is `${NAME}`, with nothing after the closing quote: anything looser (`.*`) lets
# `echo "::notice::x"; <any command>` through, in a job that runs after approval.
_ANNOTATION_LINE = re.compile(
    r'^(\s*)echo "::(?:notice|error)::(?:[^"$`\\]|\$\{[A-Za-z_][A-Za-z0-9_]*\})*"$'
)

_JOB_HEADING = re.compile(r"  ([A-Za-z_][A-Za-z0-9_-]*):[ \t]*")


_JOB_HEADING_LENIENT = re.compile(r"  ([A-Za-z_][A-Za-z0-9_-]*):[ \t]*(?:#.*)?")


def _jobs(text: str, strict: bool = True) -> dict[str, str]:
    """Split the top-level ``jobs:`` mapping into {job id: job block}.

    Strict (the default) on purpose. Every job and step inventory in this file is built on
    this, so a job it cannot see would be invisible to every pin: each entry directly under
    ``jobs:`` must be a plain job-id heading (any valid GitHub id, so `Rogue` and `_x`
    count), nothing but blank lines and comments may precede the first one, an id may not
    repeat, and anything else (a quoted id, odd indentation, a tab used as indentation, a
    flow value) is an error, not a skip.

    ``strict=False`` is for a file other people edit constantly (ci-verification.yml), where
    only a few named jobs matter: it finds the headings it can read (a trailing comment is
    fine), never raises on anything else, and leaves every other job as opaque text.
    """
    start = re.search(r"^jobs:[ \t]*$", text, re.MULTILINE)
    if start is None:
        raise AssertionError("missing top-level jobs: block")
    blocks: dict[str, list[str]] = {}
    current: str | None = None
    for line in text[start.end():].split("\n"):
        if line.strip() and not line.lstrip().startswith("#"):
            leading = line[: len(line) - len(line.lstrip())]
            spaces = len(line) - len(line.lstrip(" "))
            if not leading:
                break  # the next top-level key ends the jobs mapping
            heading = (_JOB_HEADING_LENIENT if not strict else _JOB_HEADING).fullmatch(line)
            if heading:
                if strict and heading.group(1) in blocks:
                    raise AssertionError(f"repeated job id {heading.group(1)!r}")
                current = heading.group(1)
                blocks[current] = [line]
                continue
            if strict:
                # A tab is legal deep inside block content; only one that could be
                # job-level indentation makes a line unreadable to the splitter.
                if "\t" in leading and spaces < 4:
                    raise AssertionError(f"tab indentation under jobs: {line!r}")
                if len(leading) < 4:
                    raise AssertionError(f"unrecognised entry directly under jobs: {line!r}")
                if current is None:
                    raise AssertionError(f"content before the first job heading: {line!r}")
        if current is not None:
            blocks[current].append(line)
    return {job_id: "\n".join(lines) + "\n" for job_id, lines in blocks.items()}


def _step_names(job: str) -> list[str]:
    """Names of the steps written as ``      - name: <name>``."""
    return re.findall(r"(?m)^      - name: (.+?)[ \t]*$", job)


def _step_count(job: str) -> int:
    """Every sequence entry at step level, however it is written: ``- name:``, a bare ``-``,
    ``- # comment`` or an indentless entry. Compared with `len(_step_names(job))` it exposes
    any step a name-based regex would not see."""
    return len(re.findall(r"(?m)^(?: {4}| {6})-(?:[ \t]|$)", job))


def _named_block(job: str, name: str) -> str:
    """One ``      - name: <name>`` step, stopping at the next step."""
    lines = job.splitlines(keepends=True)
    heading = f"      - name: {name}"
    start = next(
        (i for i, line in enumerate(lines) if line.rstrip("\r\n") == heading), None
    )
    if start is None:
        raise AssertionError(f"missing step: {name}")
    end = len(lines)
    for index in range(start + 1, len(lines)):
        if re.match(r"^      -(?:[ \t]|$)", lines[index]):
            end = index
            break
    return "".join(lines[start:end])


def _normalized(block: str) -> list[str]:
    """Comment- and blank-insensitive view of a block, for byte-level drift checks."""
    return [
        line.rstrip()
        for line in block.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def _run_lines(step: str) -> list[str]:
    """The command text of one step's ``run:`` (inline or block scalar), dedented."""
    lines = step.splitlines()
    for index, line in enumerate(lines):
        match = re.match(r"^        run:[ \t]*(.*)$", line)
        if not match:
            continue
        inline = match.group(1).strip()
        if inline and inline not in ("|", "|-", ">", ">-"):
            # A plain scalar may continue on the following, more indented lines.
            continued: list[str] = []
            for following in lines[index + 1:]:
                if not following.strip():
                    continue
                if len(following) - len(following.lstrip(" ")) <= 8:
                    break
                continued.append(following.strip())
            return [inline, *continued]
        body: list[str] = []
        for following in lines[index + 1:]:
            if following.strip() and not following.startswith("          "):
                break
            body.append(following[10:].rstrip() if following.strip() else "")
        while body and not body[-1]:
            body.pop()
        return body
    raise AssertionError("step has no run:")


def _assert_no_inline_comments(testcase: unittest.TestCase, text: str, label: str) -> None:
    """Whole-line comments are stripped before the pinned assertions run; an inline
    ``key: value  # ...`` comment would survive that and could satisfy an ``assertIn``."""
    jobs_at = re.search(r"^jobs:\s*$", text, re.MULTILINE)
    testcase.assertIsNotNone(jobs_at, f"{label}: missing jobs:")
    offenders = [
        line.strip()
        for line in text[jobs_at.start():].splitlines()
        if not line.lstrip().startswith("#") and re.search(r"\S\s+#", line)
    ]
    testcase.assertEqual(
        offenders,
        [],
        f"{label}: inline comments in the jobs section can satisfy a pinned assertion; "
        "use whole-line comments",
    )


_BLOCK_SCALAR = re.compile(r"[|>][-+0-9]*")
_QUOTED = re.compile(r"'(?:[^']|'')*'|\"(?:[^\"\\]|\\.)*\"")


def _clean_value(value: str) -> str | None:
    """A scalar/flow value without its trailing comment; None if a quote is left open."""
    value = value.strip()
    if not value:
        return ""
    if value[0] in "'\"":
        quote, index = value[0], 1
        while index < len(value):
            char = value[index]
            if quote == '"' and char == "\\":
                index += 2
                continue
            if char == quote:
                if quote == "'" and value[index + 1:index + 2] == "'":
                    index += 2
                    continue
                return value[: index + 1]
            index += 1
        return None
    if value[0] == "[":
        kept: list[str] = []
        quote, index = "", 0
        while index < len(value):
            char = value[index]
            if quote:
                kept.append(char)
                if quote == '"' and char == "\\":
                    kept.append(value[index + 1:index + 2])
                    index += 2
                    continue
                if char == quote:
                    quote = ""
            elif char in "'\"":
                quote = char
                kept.append(char)
            elif char == "#" and (index == 0 or value[index - 1] in " \t"):
                break
            else:
                kept.append(char)
            index += 1
        # A quote still open here continues on a later line, where it can hide structure.
        return None if quote else "".join(kept).strip()
    match = re.search(r"\s#", value)
    return (value[: match.start()] if match else value).strip()


def _split_key(rest: str) -> tuple[str, str] | None:
    """``key: value`` -> (key, value); None when the text is not a mapping entry."""
    if rest[0] in "'\"":
        quote, index = rest[0], 1
        while index < len(rest):
            if quote == '"' and rest[index] == "\\":
                index += 2
                continue
            if rest[index] == quote:
                if quote == "'" and rest[index + 1:index + 2] == "'":
                    index += 2
                    continue
                break
            index += 1
        else:
            return None
        after = rest[index + 1:]
        colon = re.match(r"\s*:(?:\s|$)", after)
        return (rest[: index + 1], after[colon.end():]) if colon else None
    colon = re.search(r":(?:\s|$)", rest)
    return (rest[: colon.start()], rest[colon.end():]) if colon else None


def _value_hazards(value: str) -> list[str]:
    head = value[:1]
    if head == "&":
        return ["anchor"]
    if head == "*":
        return ["alias"]
    if head in ("!", "{"):
        return ["unsupported"]
    if head != "[":
        return []
    # Quoted items cannot hide a hazard (or contain one), so blank them out first. A node
    # starts right after `[`, `,` or a `key:` colon; `[k: v]` is a single-pair implicit
    # mapping, whose keys and values this does not model, so it is reported as unsupported.
    inner = _QUOTED.sub("''", value)
    kinds: list[str] = []
    if "{" in inner or not inner.rstrip().endswith("]"):
        kinds.append("unsupported")
    if re.search(r":(?:\s|\]|,|$)", inner):
        kinds.append("unsupported")
    if re.search(r"(?:^|[\[,:]\s*)&", inner):
        kinds.append("anchor")
    if re.search(r"(?:^|[\[,:]\s*)\*", inner):
        kinds.append("alias")
    if re.search(r"(?:^|[\[,]\s*)<<\s*:", inner):
        kinds.append("merge key")
    if re.search(r"(?:^|[\[,:]\s*)!", inner):
        kinds.append("unsupported")
    return kinds


def _yaml_hazards(text: str) -> list[str]:
    """Anchors, aliases, merge keys and duplicate mapping keys in block-style YAML.

    Returns one ``line N: <kind>: <text>`` string per finding; kinds are ``anchor``,
    ``alias``, ``merge key``, ``duplicate key`` and ``unsupported``. ``unsupported`` is the
    fail-closed kind: flow mappings, tags, document markers, directives, complex keys, tab
    indentation and quoted or flow scalars that span lines are outside what this models, so
    a protected file that uses one fails instead of being scanned inaccurately.

    Text inside block scalars (``run: |``) and comments is not YAML structure and is
    skipped. A continuation line of a multi-line plain scalar that starts with ``*`` or ``&``
    is reported as an alias/anchor: over-reporting is the safe direction here.
    """
    hazards: list[str] = []
    frames: list[tuple[int, set[str]]] = []  # (key column, keys seen) per open mapping
    block_parent: int | None = None  # a block scalar runs until an indent <= this

    def flag(number: int, kind: str, line: str) -> None:
        hazards.append(f"line {number}: {kind}: {line.strip()[:80]}")

    for number, line in enumerate(text.splitlines(), 1):
        if block_parent is not None:
            if not line.strip() or len(line) - len(line.lstrip(" ")) > block_parent:
                continue
            block_parent = None
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        leading = line[: len(line) - len(line.lstrip())]
        if "\t" in leading:
            flag(number, "unsupported", line)
            continue
        if re.match(r"(?:---|\.\.\.)(?:\s|$)|%", line):
            flag(number, "unsupported", line)
            continue
        column, rest, dashes = len(leading), line[len(leading):], []
        while rest == "-" or rest.startswith("- "):
            dashes.append(column)
            body = rest[1:]
            column += 1 + len(body) - len(body.lstrip(" "))
            rest = body.lstrip(" ")
        for dash in dashes:
            while frames and frames[-1][0] > dash:
                frames.pop()
        if rest.startswith("#"):
            rest = ""
        if not rest:
            if dashes:
                # A bare `-` (or `- # note`): the entry is on the following lines, where a
                # regex looking for `- name:` / `- run:` never sees it.
                flag(number, "unsupported", line)
            continue
        head = rest[0]
        if head == "&":
            flag(number, "anchor", line)
            continue
        if head == "*":
            flag(number, "alias", line)
            continue
        if head in ("!", "{") or (head == "?" and rest[1:2] in ("", " ")):
            flag(number, "unsupported", line)
            continue

        # A node that starts with `[` is a flow sequence (an item, or a value on its own
        # line), never `key: value`: splitting it on its first colon would read `[<<` as a key.
        split = None if head == "[" else _split_key(rest)
        if split is None:
            cleaned = _clean_value(rest)
            if cleaned is None:
                flag(number, "unsupported", line)
                continue
            for kind in _value_hazards(cleaned):
                flag(number, kind, line)
            if _BLOCK_SCALAR.fullmatch(cleaned):
                block_parent = dashes[-1] if dashes else column - 1
            continue

        key, value = split
        name = key.strip()
        if len(name) >= 2 and name[0] in "'\"" and name[-1] == name[0]:
            # Same key to a YAML parser, but invisible to the plain-key regexes the pins use.
            flag(number, "unsupported", line)
            body = name[1:-1]
            # Quoted keys compare by their value: undo the quote escapes so `'a''b'` and
            # `"a'b"` (or `"a\"b"` and `'a"b'`) are seen as the same key.
            name = (
                body.replace("''", "'")
                if name[0] == "'"
                else body.replace('\\"', '"').replace("\\\\", "\\")
            )
        while frames and frames[-1][0] > column:
            frames.pop()
        if frames and frames[-1][0] == column:
            keys = frames[-1][1]
        else:
            keys = set()
            frames.append((column, keys))
        if name in keys:
            flag(number, "duplicate key", line)
        keys.add(name)
        if name == "<<":
            flag(number, "merge key", line)
        cleaned = _clean_value(value)
        if cleaned is None:
            flag(number, "unsupported", line)
            continue
        for kind in _value_hazards(cleaned):
            flag(number, kind, line)
        if _BLOCK_SCALAR.fullmatch(cleaned):
            block_parent = column
    return hazards


def _mapping_lines(block: str, key: str, indent: int) -> list[str]:
    """Lines of the mapping under ``<indent spaces><key>:`` (its children only)."""
    match = re.search(
        rf"^{' ' * indent}{re.escape(key)}:[ \t]*\n((?:{' ' * (indent + 2)}.+\n?)+)",
        block,
        re.MULTILINE,
    )
    if match is None:
        raise AssertionError(f"missing {key}: mapping at indent {indent}")
    return [line.strip() for line in match.group(1).splitlines() if line.strip()]


class TestValidatorFrontendOnly(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = _load("validate_deploy_dispatch_fo", SCRIPTS / "validate_deploy_dispatch.py")

    def _inputs(self, **overrides):
        base = dict(
            deployment_mode="frontend-only",
            services="",
            prebuilt_digest="",
            expected_main_sha=SHA,
            actual_sha=SHA,
            actual_ref=MAIN_REF,
            cloud_provider="azure",
        )
        base.update(overrides)
        return self.mod.DispatchInputs(**base)

    def test_frontend_only_is_a_valid_mode(self):
        self.assertIn("frontend-only", self.mod.VALID_MODES)

    def test_frontend_only_with_empty_inputs_on_azure_main_passes(self):
        self.mod.validate(self._inputs())

    def test_frontend_only_rejects_any_services_input(self):
        # Asserting the specific rule matters: before the mode existed these inputs were
        # also rejected, but only because "frontend-only" was not a valid mode at all.
        for services in ("portfolio-service", "api-gateway,insight-service"):
            with self.subTest(services=services):
                with self.assertRaises(self.mod.DispatchValidationError) as ctx:
                    self.mod.validate(self._inputs(services=services))
                self.assertIn(
                    "deployment_mode=frontend-only requires an empty services input",
                    str(ctx.exception),
                )

    def test_frontend_only_rejects_a_prebuilt_digest(self):
        with self.assertRaises(self.mod.DispatchValidationError) as ctx:
            self.mod.validate(self._inputs(prebuilt_digest=PORTFOLIO_DIGEST))
        self.assertIn(
            "deployment_mode=frontend-only requires an empty prebuilt_digest input",
            str(ctx.exception),
        )

    def test_frontend_only_is_rejected_for_aws(self):
        with self.assertRaises(self.mod.DispatchValidationError) as ctx:
            self.mod.validate(self._inputs(cloud_provider="aws"))
        self.assertIn("not supported for CLOUD_PROVIDER=aws", str(ctx.exception))

    def test_frontend_only_still_requires_the_matching_sha_and_main_ref(self):
        with self.assertRaises(self.mod.DispatchValidationError):
            self.mod.validate(self._inputs(expected_main_sha=OTHER_SHA))
        with self.assertRaises(self.mod.DispatchValidationError):
            self.mod.validate(self._inputs(actual_ref="refs/heads/feature"))

    def test_mode_names_are_matched_exactly(self):
        for mode in ("Frontend-Only", "FRONTEND-ONLY", "frontend_only", "frontendonly", "frontend"):
            with self.subTest(mode=mode):
                with self.assertRaises(self.mod.DispatchValidationError):
                    self.mod.validate(self._inputs(deployment_mode=mode))

    def test_a_valid_mode_with_no_input_rule_fails_closed(self):
        # A mode added to VALID_MODES without an explicit rule must not validate by falling
        # out of the if/elif chain -- that would let it reach deploy-azure.yml as an
        # unvalidated dispatch.
        with mock.patch.object(self.mod, "VALID_MODES", self.mod.VALID_MODES + ("mystery",)):
            with self.assertRaises(self.mod.DispatchValidationError) as ctx:
                self.mod.validate(self._inputs(deployment_mode="mystery"))
        self.assertIn("no input rule", str(ctx.exception))

    def test_existing_modes_keep_their_rules(self):
        self.mod.validate(self._inputs(deployment_mode="full"))
        self.mod.validate(self._inputs(deployment_mode="scoped", services="api-gateway"))
        self.mod.validate(
            self._inputs(deployment_mode="digest", prebuilt_digest=PORTFOLIO_DIGEST)
        )

    def _run_main(self, **env):
        base = {
            "DEPLOYMENT_MODE": "frontend-only",
            "SERVICES_INPUT": "",
            "PREBUILT_DIGEST": "",
            "EXPECTED_MAIN_SHA": SHA,
            "ACTUAL_SHA": SHA,
            "ACTUAL_REF": MAIN_REF,
            "CLOUD_PROVIDER": "azure",
        }
        base.update(env)
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, base, clear=True), contextlib.redirect_stdout(
            out
        ), contextlib.redirect_stderr(err):
            return self.mod.main(), out.getvalue(), err.getvalue()

    def test_cli_accepts_a_clean_frontend_only_dispatch(self):
        rc, out, _ = self._run_main()
        self.assertEqual(rc, 0)
        self.assertIn("mode=frontend-only", out)

    def test_cli_rejects_a_frontend_only_dispatch_that_carries_services(self):
        rc, _, err = self._run_main(SERVICES_INPUT="portfolio-service")
        self.assertEqual(rc, 1)
        self.assertIn("::error::", err)
        self.assertIn("requires an empty services input", err)


def _cloud_routing_grammar_check(expression: str) -> None:
    """The routing test evaluates deploy.yml `if:` expressions itself; keep it honest."""
    unsupported = re.search(r"[A-Za-z_]\w*\s*\(", expression)
    if unsupported:
        raise AssertionError(
            f"deploy.yml `if:` uses a function ({unsupported.group(0)!r}) the routing "
            "evaluator does not model; extend the evaluator deliberately, do not skip it."
        )
    if re.search(r"\b(?:github|env|vars|secrets|steps|job|runner|matrix)\.", expression):
        raise AssertionError(
            f"deploy.yml `if:` reads a context the routing evaluator does not model: {expression!r}"
        )


class _CaseInsensitive(str):
    """GitHub Actions compares strings case-insensitively with == and !=."""

    def __eq__(self, other):  # type: ignore[override]
        return isinstance(other, str) and self.lower() == other.lower()

    def __ne__(self, other):  # type: ignore[override]
        return not self.__eq__(other)

    __hash__ = str.__hash__


def _evaluate_if(expression: str, provider: str, mode: str) -> bool:
    _cloud_routing_grammar_check(expression)
    python = (
        expression.replace("needs.route.outputs.provider", "PROVIDER")
        .replace("inputs.deployment_mode", "MODE")
        .replace("&&", " and ")
        .replace("||", " or ")
    )
    leftovers = set(re.findall(r"\b[A-Za-z_]\w*\b", re.sub(r"'[^']*'", "", python)))
    if not leftovers <= {"PROVIDER", "MODE", "and", "or", "not"}:
        raise AssertionError(
            f"deploy.yml `if:` uses tokens the routing evaluator does not model: "
            f"{sorted(leftovers - {'PROVIDER', 'MODE', 'and', 'or', 'not'})} in {expression!r}"
        )
    return bool(
        eval(  # noqa: S307 - expression comes from the repo file under test, tokens allowlisted above
            python,
            {"__builtins__": {}},
            {"PROVIDER": _CaseInsensitive(provider), "MODE": _CaseInsensitive(mode)},
        )
    )


class TestDispatcherRouting(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = _read(DISPATCHER)
        cls.code = _without_comments(cls.text)
        cls.jobs = _jobs(cls.code)
        cls.validator = _load(
            "validate_deploy_dispatch_routing", SCRIPTS / "validate_deploy_dispatch.py"
        )
        cls.cloud_jobs = {
            name: block
            for name, block in cls.jobs.items()
            if re.search(r"^    uses:\s", block, re.MULTILINE)
        }

    def _job_if(self, name: str) -> str:
        block = self.cloud_jobs[name]
        match = re.search(r"^    if:[ \t]*(\S.*)$", block, re.MULTILINE)
        self.assertIsNotNone(match, f"{name} needs a single-line job-level if:")
        expression = match.group(1).strip()
        self.assertNotIn(expression[:1], (">", "|"), f"{name}: use a one-line if:")
        return expression

    # -- the dropdown and the validator cannot drift apart -------------------------------

    def test_dropdown_is_the_sentinel_followed_by_every_valid_mode(self):
        block = re.search(
            r"^      deployment_mode:\n((?:        .*\n|\n)+)", self.code, re.MULTILINE
        )
        self.assertIsNotNone(block, "missing workflow_dispatch input deployment_mode")
        options = re.search(r"options:\s*\n((?:\s+-\s+\S+\s*\n)+)", block.group(1))
        self.assertIsNotNone(options)
        listed = [line.strip().lstrip("- ").strip() for line in options.group(1).splitlines()]
        self.assertEqual(listed, ["select-deployment-mode", *self.validator.VALID_MODES])
        # An explicit `default:` pre-selects the dropdown, so "Run without touching it" could
        # become a validated deploy; the first option (the sentinel) must be the only default.
        self.assertNotRegex(block.group(1), r"(?m)^        default:")

    def test_the_dispatch_inputs_keep_their_declared_shape(self):
        inputs = re.search(r"(?m)^    inputs:\n((?:      .*\n|\n)+)", self.code)
        self.assertIsNotNone(inputs, "missing workflow_dispatch inputs")
        # Every line is accounted for except the free-text bodies of the `description: >`
        # blocks: an input heading must be plain, and nothing may sit between or after the
        # declared keys (a scalar-valued "input", a stray key, an extra option).
        body: dict[str, list[str]] = {}
        current = None
        in_description = False
        for line in inputs.group(1).split("\n"):
            if not line.strip():
                continue
            indent = len(line) - len(line.lstrip(" "))
            if indent == 6:
                self.assertRegex(line, r"^      [a-z_]+:$", "every input must be a plain heading")
                current = line.strip()[:-1]
                body[current] = []
                in_description = False
                continue
            self.assertGreaterEqual(indent, 8, line)
            if indent == 8:
                # The description is free text (one line or a folded block): its value is
                # not pinned, but its position is.
                in_description = line.strip().startswith("description:")
                if in_description:
                    body[current].append("        description:")
                    continue
            if in_description and indent >= 10:
                continue
            body[current].append(line)
        options = ["select-deployment-mode", *self.validator.VALID_MODES]
        string_input = [
            "        description:",
            "        required: false",
            "        type: string",
            '        default: ""',
        ]
        self.assertEqual(
            body,
            {
                "deployment_mode": [
                    "        description:",
                    "        required: true",
                    "        type: choice",
                    "        options:",
                    *[f"          - {option}" for option in options],
                ],
                "expected_main_sha": [
                    "        description:",
                    "        required: true",
                    "        type: string",
                ],
                "services": string_input,
                "prebuilt_digest": string_input,
            },
        )

    # -- every mode reaches exactly one place --------------------------------------------

    def test_the_cloud_calling_jobs_are_the_three_expected_ones(self):
        self.assertEqual(
            set(self.cloud_jobs),
            {"deploy-azure", "deploy-azure-frontend-only", "deploy-aws"},
        )

    def test_every_valid_mode_runs_exactly_the_intended_cloud_job(self):
        expected = {
            ("azure", "full"): {"deploy-azure"},
            ("azure", "scoped"): {"deploy-azure"},
            ("azure", "digest"): {"deploy-azure"},
            ("azure", "frontend-only"): {"deploy-azure-frontend-only"},
            ("aws", "full"): {"deploy-aws"},
        }
        for mode in self.validator.VALID_MODES:
            self.assertIn(
                ("azure", mode),
                expected,
                f"{mode!r} is a valid mode with no routing expectation; wire it in deploy.yml "
                "and add it here",
            )
        for provider in ("azure", "aws"):
            for mode in (*self.validator.VALID_MODES, "select-deployment-mode", "bogus", ""):
                with self.subTest(provider=provider, mode=mode):
                    running = {
                        name
                        for name in self.cloud_jobs
                        if _evaluate_if(self._job_if(name), provider, mode)
                    }
                    self.assertEqual(running, expected.get((provider, mode), set()))

    def test_a_mode_without_its_own_route_never_reaches_the_full_deploy_workflows(self):
        # deploy-azure.yml receives only `services` and `prebuilt_digest`, and treats empty
        # `services` as a full deploy. Anything that is not full/scoped/digest arriving there
        # would silently be a full deploy, so those workflows must be gated on a positive
        # allowlist of modes rather than on the provider alone.
        for provider in ("azure", "aws"):
            for mode in ("frontend-only", "select-deployment-mode", "bogus", ""):
                for name in ("deploy-azure", "deploy-aws"):
                    with self.subTest(provider=provider, mode=mode, job=name):
                        self.assertFalse(_evaluate_if(self._job_if(name), provider, mode))

    # -- the new job carries the same gates as its siblings ------------------------------

    def test_frontend_only_job_calls_only_the_new_reusable_workflow(self):
        block = self.cloud_jobs["deploy-azure-frontend-only"]
        self.assertRegex(
            block, r"(?m)^    uses:\s*\./\.github/workflows/deploy-azure-frontend\.yml\s*$"
        )
        self.assertNotRegex(block, r"(?m)^    with:", "the frontend-only path takes no inputs")

    def test_frontend_only_job_passes_exactly_the_four_named_secrets_and_never_inherits(self):
        block = self.cloud_jobs["deploy-azure-frontend-only"]
        self.assertNotIn("inherit", block)
        # Each secret is mapped to the same-named repository secret: no default, no
        # fallback expression, no rename, and nothing else (so no E2E user, no internal API
        # key, no Terraform variable can reach the new workflow).
        self.assertEqual(
            sorted(_mapping_lines(block, "secrets", 4)),
            sorted(f"{name}: ${{{{ secrets.{name} }}}}" for name in FRONTEND_ONLY_SECRETS),
        )

    def test_the_other_cloud_jobs_keep_inheriting(self):
        # Out of scope for this change: only the new path narrows its secrets.
        for name in ("deploy-azure", "deploy-aws"):
            self.assertRegex(self.cloud_jobs[name], r"(?m)^    secrets: inherit\s*$", name)

    def test_frontend_only_job_is_behind_route_and_the_production_environment(self):
        block = self.cloud_jobs["deploy-azure-frontend-only"]
        self.assertRegex(block, r"(?m)^    needs: route\s*$")
        self.assertNotIn("environment:", block)
        self.assertRegex(self.jobs["route"], r"(?m)^    needs: authorize-production\s*$")

    def test_deploy_azure_frontend_yml_is_called_only_from_deploy_yml(self):
        # Any spelling of the reference counts, including the `owner/repo/...@ref` form.
        pattern = re.compile(r"uses:\s*\S*deploy-azure-frontend\.yml")
        callers = {
            path.name
            for glob in ("*.yml", "*.yaml")
            for path in WORKFLOWS.glob(glob)
            if path.name != "deploy-azure-frontend.yml"
            and pattern.search(_without_comments(_read(path)))
        }
        self.assertEqual(callers, {"deploy.yml"})

    def test_the_dispatcher_jobs_have_no_inline_comments(self):
        _assert_no_inline_comments(self, self.text, "deploy.yml")

    def test_each_workflow_has_only_its_intended_trigger(self):
        # deploy.yml is the only way in: the cloud workflows may only be called. A trigger
        # added to one of them is a second entry to production that skips the dispatcher's
        # validation, approval and concurrency (the 2026-08-23 incident).
        expected = {
            DISPATCHER: "workflow_dispatch",
            DEPLOY_AZURE: "workflow_call",
            DEPLOY_AWS: "workflow_call",
            FRONTEND_ONLY: "workflow_call",
        }
        for path, trigger in expected.items():
            with self.subTest(workflow=path.name):
                on_block = re.search(
                    r"^on:\n((?:  .*\n|\n)*)", _without_comments(_read(path)) + "\n", re.MULTILINE
                )
                self.assertIsNotNone(on_block, f"{path.name} must use a block-style on:")
                self.assertEqual(re.findall(r"(?m)^  (\S+):", on_block.group(1)), [trigger])

    def test_the_dispatcher_defines_exactly_the_expected_jobs(self):
        # A plain job here would run with `id-token: write` and every secret, and could
        # reach Azure without going through any of the reusable workflows pinned below.
        self.assertEqual(
            list(self.jobs),
            [
                "validate",
                "authorize-production",
                "route",
                "deploy-azure",
                "deploy-azure-frontend-only",
                "deploy-aws",
            ],
        )
        # Case-insensitive on purpose: the canonical spelling of the org is `Azure/`.
        self.assertNotRegex(
            self.code,
            r"(?i)azure/|\baz\b|\bcurl\b|\bwget\b|https?://|management\.azure",
        )

    def test_the_dispatcher_uses_only_the_expected_actions_and_workflows(self):
        self.assertEqual(
            set(re.findall(r"uses:\s*(\S+)", self.code)),
            {
                "actions/checkout@v4",
                "actions/setup-python@v5",
                "./.github/workflows/deploy-azure.yml",
                "./.github/workflows/deploy-azure-frontend.yml",
                "./.github/workflows/deploy-aws.yml",
            },
        )

    def test_the_dispatcher_top_level_shape_is_pinned(self):
        self.assertEqual(
            re.findall(r"(?m)^([A-Za-z_][A-Za-z0-9_-]*):", self.code),
            ["name", "on", "concurrency", "permissions", "jobs"],
        )
        self.assertRegex(self.code, r"(?m)^name: Deploy[ \t]*$")
        # `id-token: write` is what lets the reusable workflows request an Azure token, and
        # it is the ceiling for what deploy-azure-frontend.yml's jobs may ask for.
        self.assertEqual(
            _mapping_lines(self.text, "permissions", 0), ["id-token: write", "contents: read"]
        )
        self.assertEqual(
            _mapping_lines(self.text, "concurrency", 0),
            ["group: production-deploy", "cancel-in-progress: false"],
        )

    def test_the_guard_jobs_are_exactly_what_the_hardening_contract_expects(self):
        # These two jobs are the gate: validation before approval, then the production
        # Environment. The hardening tests that also pin them run in an advisory CI job.
        self.assertEqual(
            _normalized(self.jobs["validate"]),
            [
                "  validate:",
                "    runs-on: ubuntu-latest",
                "    steps:",
                "      - uses: actions/checkout@v4",
                "      - uses: actions/setup-python@v5",
                "        with:",
                '          python-version: "3.12"',
                "      - name: Validate dispatch inputs",
                "        env:",
                "          DEPLOYMENT_MODE: ${{ inputs.deployment_mode }}",
                "          SERVICES_INPUT: ${{ inputs.services }}",
                "          PREBUILT_DIGEST: ${{ inputs.prebuilt_digest }}",
                "          EXPECTED_MAIN_SHA: ${{ inputs.expected_main_sha }}",
                "          ACTUAL_SHA: ${{ github.sha }}",
                "          ACTUAL_REF: ${{ github.ref }}",
                "          CLOUD_PROVIDER: ${{ vars.CLOUD_PROVIDER }}",
                "        run: python3 .github/workflows/scripts/validate_deploy_dispatch.py",
            ],
        )
        self.assertEqual(
            _normalized(self.jobs["authorize-production"]),
            [
                "  authorize-production:",
                "    needs: validate",
                "    runs-on: ubuntu-latest",
                "    environment: production",
                "    steps:",
                "      - name: Production deployment authorized",
                '        run: echo "Approved — proceeding to route and deploy."',
            ],
        )
        # Only the gate declares an environment.
        self.assertEqual(len(re.findall(r"(?m)^    environment:", self.code)), 1)

    def test_the_route_job_keeps_its_shape(self):
        route = self.jobs["route"]
        self.assertEqual(
            _normalized(route)[:5],
            [
                "  route:",
                "    needs: authorize-production",
                "    runs-on: ubuntu-latest",
                "    outputs:",
                "      provider: ${{ steps.check.outputs.provider }}",
            ],
        )
        self.assertEqual(_step_names(route), ["Determine cloud provider", "Log routing decision"])
        self.assertEqual(_step_count(route), 2, "every step must be named")
        # The routing brain, pinned structurally. The wording of the ::notice::/::error::
        # annotations is deliberately not pinned, so a message edit does not trip this.
        self.assertEqual(
            [_ANNOTATION_LINE.sub(r'\1echo "::annotation::"', line) for line in _normalized(route)],
            [
                "  route:",
                "    needs: authorize-production",
                "    runs-on: ubuntu-latest",
                "    outputs:",
                "      provider: ${{ steps.check.outputs.provider }}",
                "    steps:",
                "      - name: Determine cloud provider",
                "        id: check",
                "        run: |",
                '          PROVIDER="${{ vars.CLOUD_PROVIDER }}"',
                '          echo "provider=${PROVIDER}" >> "$GITHUB_OUTPUT"',
                '          if [ -z "$PROVIDER" ]; then',
                '            echo "::annotation::"',
                '            echo "::annotation::"',
                "            exit 1",
                "          fi",
                '          if [ "$PROVIDER" != "azure" ] && [ "$PROVIDER" != "aws" ]; then',
                '            echo "::annotation::"',
                "            exit 1",
                "          fi",
                '          echo "Cloud provider: ${PROVIDER}"',
                "      - name: Log routing decision",
                "        env:",
                "          MODE: ${{ inputs.deployment_mode }}",
                "        run: |",
                '          PROVIDER="${{ steps.check.outputs.provider }}"',
                '          if [ "$PROVIDER" = "azure" ] && [ "$MODE" = "frontend-only" ]; then',
                '            echo "::annotation::"',
                '          elif [ "$PROVIDER" = "azure" ]; then',
                '            echo "::annotation::"',
                '          elif [ "$PROVIDER" = "aws" ]; then',
                '            echo "::annotation::"',
                "          fi",
            ],
        )

    def test_the_cloud_calling_jobs_are_exactly_these(self):
        # Whole-job literals. `deploy-azure`'s `with:` decides what the reusable workflow
        # infers (empty `services` = full deploy), so it is pinned here, in the required
        # job, rather than only by the advisory hardening tests.
        expected = {
            "deploy-azure": [
                "  deploy-azure:",
                "    needs: route",
                "    if: needs.route.outputs.provider == 'azure' && (inputs.deployment_mode == 'full' "
                "|| inputs.deployment_mode == 'scoped' || inputs.deployment_mode == 'digest')",
                "    uses: ./.github/workflows/deploy-azure.yml",
                "    with:",
                "      services: ${{ inputs.services }}",
                "      prebuilt_digest: ${{ inputs.prebuilt_digest }}",
                "    secrets: inherit",
            ],
            "deploy-azure-frontend-only": [
                "  deploy-azure-frontend-only:",
                "    needs: route",
                "    if: needs.route.outputs.provider == 'azure' && inputs.deployment_mode == 'frontend-only'",
                "    uses: ./.github/workflows/deploy-azure-frontend.yml",
                "    secrets:",
                "      AZURE_CLIENT_ID: ${{ secrets.AZURE_CLIENT_ID }}",
                "      AZURE_TENANT_ID: ${{ secrets.AZURE_TENANT_ID }}",
                "      AZURE_SUBSCRIPTION_ID: ${{ secrets.AZURE_SUBSCRIPTION_ID }}",
                "      SWA_DEPLOYMENT_TOKEN: ${{ secrets.SWA_DEPLOYMENT_TOKEN }}",
            ],
            "deploy-aws": [
                "  deploy-aws:",
                "    needs: route",
                "    if: needs.route.outputs.provider == 'aws' && inputs.deployment_mode == 'full'",
                "    uses: ./.github/workflows/deploy-aws.yml",
                "    secrets: inherit",
            ],
        }
        for name, lines in expected.items():
            with self.subTest(job=name):
                self.assertEqual(_normalized(self.cloud_jobs[name]), lines)

    def test_route_log_names_the_frontend_only_workflow(self):
        # The routing notice must not tell an operator that a frontend-only dispatch is
        # going to deploy-azure.yml (the full backend deploy).
        self.assertIn("deploy-azure-frontend.yml", self.jobs["route"])


class TestFrontendOnlyWorkflow(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = _read(FRONTEND_ONLY)
        cls.code = _without_comments(cls.text)
        cls.jobs = _jobs(cls.code)
        cls.azure_jobs = _jobs(_read(DEPLOY_AZURE))

    def test_the_workflow_has_no_inline_comments(self):
        _assert_no_inline_comments(self, self.text, "deploy-azure-frontend.yml")

    # -- entry point ---------------------------------------------------------------------

    def test_is_workflow_call_only(self):
        on_block = re.search(r"^on:\s*\n(?:(?:  .*)?\n)*", self.text, re.MULTILINE)
        self.assertIsNotNone(on_block)
        triggers = re.findall(r"^  ([a-z_]+):", on_block.group(0), re.MULTILINE)
        self.assertEqual(triggers, ["workflow_call"])

    def test_declares_exactly_the_secrets_it_uses_and_requires_each(self):
        match = re.search(
            r"(?m)^on:\n  workflow_call:\n    secrets:\n"
            r"((?:      [A-Z][A-Z0-9_]*:\n        required: true\n)+)(?!      )",
            self.code + "\n",
        )
        self.assertIsNotNone(
            match,
            "on.workflow_call must declare its secrets explicitly, each `required: true` "
            "and nothing else",
        )
        declared = re.findall(r"(?m)^      ([A-Z][A-Z0-9_]*):$", match.group(1))
        self.assertEqual(sorted(declared), sorted(FRONTEND_ONLY_SECRETS))

    def test_each_job_can_read_only_the_secrets_it_needs(self):
        expected = {
            "snapshot-before": set(AZURE_SNAPSHOT_SECRETS),
            "deploy-frontend": {"SWA_DEPLOYMENT_TOKEN", "GITHUB_TOKEN"},
            "assert-backends-unchanged": set(AZURE_SNAPSHOT_SECRETS),
        }
        self.assertEqual(set(self.jobs), set(expected))
        for name, job in self.jobs.items():
            with self.subTest(job=name):
                self.assertEqual(set(re.findall(r"secrets\.([A-Za-z0-9_]+)", job)), expected[name])
        # `secrets['X']`, `toJSON(secrets)` and `secrets: inherit` would each read the
        # secret store without naming a secret. The only bare `secrets` allowed is the
        # `secrets:` interface key under on.workflow_call, whose shape is pinned above.
        self.assertNotRegex(self.code, r"\bsecrets\b(?!\.[A-Za-z_][A-Za-z0-9_]*|:\s*\n)")
        self.assertNotIn("inherit", self.code)
        self.assertEqual(len(re.findall(r"(?m)^\s+secrets:", self.code)), 1)

    def test_shares_the_azure_deploy_concurrency_group_without_cancelling(self):
        self.assertRegex(
            self.text,
            r"(?m)^concurrency:\s*\n\s+group:\s*wealth-production-azure-deploy\s*\n"
            r"\s+cancel-in-progress:\s*false\s*$",
        )

    def test_has_exactly_the_expected_jobs(self):
        self.assertEqual(
            list(self.jobs), ["snapshot-before", "deploy-frontend", "assert-backends-unchanged"]
        )

    # -- no backend write surface --------------------------------------------------------

    def test_has_no_azure_cli_docker_registry_or_terraform_commands(self):
        for pattern in (
            r"\baz\b",
            r"containerapp",
            r"\bdocker\b",
            r"buildx",
            r"\bacr\b",
            r"terraform",
            r"\bkubectl\b",
        ):
            self.assertNotRegex(
                self.code, pattern, f"{pattern!r} must not appear outside comments"
            )

    def test_uses_only_the_allowlisted_actions(self):
        used = set(re.findall(r"uses:\s*(\S+)", self.code))
        self.assertEqual(
            used,
            {
                "actions/checkout@v4",
                "azure/login@v2",
                "actions/setup-node@v4",
                "Azure/static-web-apps-deploy@v1",
            },
        )

    def test_no_job_can_seed_verify_or_write_data(self):
        self.assertNotRegex(self.code, r"global-setup|verify-azure-demo|INTERNAL_API_KEY|E2E_TEST_USER")

    def test_each_job_has_exactly_the_expected_steps(self):
        # The two jobs that hold an Azure token may not gain a step. Pinning the inventory
        # (and that every step is named) is what makes an extra `run:` in them visible.
        expected = {
            "snapshot-before": [
                "Checkout code",
                "Azure login (OIDC)",
                "Snapshot Container Apps and refresh Job (read-only)",
            ],
            "deploy-frontend": [
                "Checkout code",
                "Report and validate exposure flags",
                "Set up Node.js",
                "Install frontend dependencies",
                "Build Next.js static export",
                "Deploy to Azure Static Web Apps",
                "Deploy to Azure Static Web Apps (retry)",
            ],
            "assert-backends-unchanged": [
                "Checkout code",
                "Azure login (OIDC)",
                "Record the result vector and assert every backend is unchanged",
            ],
        }
        self.assertEqual(set(self.jobs), set(expected))
        for name, steps in expected.items():
            job = self.jobs[name]
            with self.subTest(job=name):
                self.assertEqual(_step_names(job), steps)
                self.assertEqual(_step_count(job), len(steps), "every step must be named")

    def test_every_python_command_runs_in_isolated_mode(self):
        # `python3 script.py` puts the script's directory first on sys.path, so a stdlib-named
        # module dropped beside the scripts (a `subprocess.py`) would be imported in place of
        # the standard library: the pins on the script's own text cannot see that. `-I` keeps
        # the script directory and PYTHON* variables (PYTHONPATH) out of sys.path.
        commands = re.findall(r"python3[ \t]+(?:-\S+[ \t]+)*\S+", self.code)
        self.assertEqual(len(commands), 3, commands)
        for command in commands:
            self.assertRegex(command, r"^python3 -I [.\w/-]+\.py$", command)

    def test_the_azure_facing_commands_are_exactly_these(self):
        snapshot = _named_block(
            self.jobs["snapshot-before"], "Snapshot Container Apps and refresh Job (read-only)"
        )
        self.assertEqual(
            _run_lines(snapshot),
            ["python3 -I .github/workflows/scripts/snapshot_container_apps.py snapshot --require-complete"],
        )
        self.assertRegex(snapshot, r"(?m)^        id: snapshot\s*$")

        assert_step = _named_block(
            self.jobs["assert-backends-unchanged"],
            "Record the result vector and assert every backend is unchanged",
        )
        self.assertEqual(
            _run_lines(assert_step),
            [
                "set -euo pipefail",
                "fail=0",
                'echo "snapshot-before.result=${SNAPSHOT_RESULT}"',
                'echo "deploy-frontend.result=${FRONTEND_RESULT}"',
                'if [ "$FRONTEND_RESULT" != "success" ]; then',
                "  echo \"::error::deploy-frontend conclusion was '${FRONTEND_RESULT}', expected 'success'\"",
                "  fail=1",
                "fi",
                "python3 -I .github/workflows/scripts/snapshot_container_apps.py assert-unchanged \\",
                '  --before "$BEFORE" || fail=1',
                'exit "$fail"',
            ],
        )

    CHECKOUT = ["      - name: Checkout code", "        uses: actions/checkout@v4"]
    LOGIN = [
        "      - name: Azure login (OIDC)",
        "        uses: azure/login@v2",
        "        with:",
        "          client-id: ${{ secrets.AZURE_CLIENT_ID }}",
        "          tenant-id: ${{ secrets.AZURE_TENANT_ID }}",
        "          subscription-id: ${{ secrets.AZURE_SUBSCRIPTION_ID }}",
    ]

    def test_the_workflow_top_level_shape_is_pinned(self):
        self.assertEqual(
            re.findall(r"(?m)^([A-Za-z_][A-Za-z0-9_-]*):", self.code),
            ["on", "concurrency", "env", "permissions", "jobs"],
        )
        self.assertEqual(_mapping_lines(self.text, "env", 0), ["AZURE_RG: wealth-azure-prod-rg"])
        self.assertEqual(
            _mapping_lines(self.text, "concurrency", 0),
            ["group: wealth-production-azure-deploy", "cancel-in-progress: false"],
        )

    def test_the_azure_token_holding_jobs_are_exactly_these(self):
        # Whole-job literals: anything else in these jobs (another checkout ref or repository,
        # a container, an env such as PYTHONPATH, continue-on-error, an extra `with:`) would
        # change what runs beside the OIDC token while every narrower pin still held.
        snapshot_job = [
            "  snapshot-before:",
            "    runs-on: ubuntu-latest",
            "    permissions:",
            "      id-token: write",
            "      contents: read",
            "    outputs:",
            "      snapshot: ${{ steps.snapshot.outputs.snapshot }}",
            "    steps:",
            *self.CHECKOUT,
            *self.LOGIN,
            "      - name: Snapshot Container Apps and refresh Job (read-only)",
            "        id: snapshot",
            "        run: python3 -I .github/workflows/scripts/snapshot_container_apps.py snapshot --require-complete",
        ]
        assert_job = [
            "  assert-backends-unchanged:",
            "    runs-on: ubuntu-latest",
            "    needs: [snapshot-before, deploy-frontend]",
            "    if: always() && needs.snapshot-before.result == 'success'",
            "    permissions:",
            "      id-token: write",
            "      contents: read",
            "    steps:",
            *self.CHECKOUT,
            *self.LOGIN,
            "      - name: Record the result vector and assert every backend is unchanged",
            "        env:",
            "          BEFORE: ${{ needs.snapshot-before.outputs.snapshot }}",
            "          SNAPSHOT_RESULT: ${{ needs.snapshot-before.result }}",
            "          FRONTEND_RESULT: ${{ needs.deploy-frontend.result }}",
            "        run: |",
            "          set -euo pipefail",
            "          fail=0",
            '          echo "snapshot-before.result=${SNAPSHOT_RESULT}"',
            '          echo "deploy-frontend.result=${FRONTEND_RESULT}"',
            '          if [ "$FRONTEND_RESULT" != "success" ]; then',
            "            echo \"::error::deploy-frontend conclusion was '${FRONTEND_RESULT}', expected 'success'\"",
            "            fail=1",
            "          fi",
            "          python3 -I .github/workflows/scripts/snapshot_container_apps.py assert-unchanged \\",
            '            --before "$BEFORE" || fail=1',
            '          exit "$fail"',
        ]
        self.assertEqual(_normalized(self.jobs["snapshot-before"]), snapshot_job)
        self.assertEqual(_normalized(self.jobs["assert-backends-unchanged"]), assert_job)

    def test_the_frontend_job_has_exactly_these_job_level_settings(self):
        # No `if:` (it must not run without the before-snapshot), no `continue-on-error:`
        # (it would report a failed upload to the assert job as success), no `environment:`,
        # `container:`, `env:`, `defaults:` or `timeout-minutes:`.
        job = _normalized(self.jobs["deploy-frontend"])
        # Through the first step's line: a key slipped in right after `steps:` is a job-level
        # key, and this is the only place it could be seen.
        self.assertEqual(
            job[: job.index("    steps:") + 2],
            [
                "  deploy-frontend:",
                "    runs-on: ubuntu-latest",
                "    needs: snapshot-before",
                "    permissions:",
                "      contents: read",
                "    steps:",
                "      - name: Checkout code",
            ],
        )
        self.assertEqual(
            _normalized(_named_block(self.jobs["deploy-frontend"], "Checkout code")),
            self.CHECKOUT,
        )
        self.assertEqual(
            _normalized(
                _named_block(self.jobs["deploy-frontend"], "Report and validate exposure flags")
            ),
            [
                "      - name: Report and validate exposure flags",
                "        env:",
                "          ENABLE_ASSET_PICKER: ${{ vars.ENABLE_ASSET_PICKER }}",
                "          ENABLE_DEMO_RESET_CONTROL: ${{ vars.ENABLE_DEMO_RESET_CONTROL }}",
                "        run: python3 -I .github/workflows/scripts/check_exposure_flags.py",
            ],
        )

    def test_the_azure_login_steps_are_exactly_the_repo_standard(self):
        for job in ("snapshot-before", "assert-backends-unchanged"):
            with self.subTest(job=job):
                self.assertEqual(
                    _normalized(_named_block(self.jobs[job], "Azure login (OIDC)")),
                    _normalized(_named_block(self.azure_jobs["deploy-frontend"], "Azure login (OIDC)")),
                )

    # -- least privilege -----------------------------------------------------------------

    def test_top_level_permissions_are_read_only(self):
        self.assertEqual(_mapping_lines(self.text, "permissions", 0), ["contents: read"])

    def test_frontend_job_holds_no_azure_token(self):
        job = self.jobs["deploy-frontend"]
        self.assertNotIn("azure/login", job)
        self.assertEqual(_mapping_lines(job, "permissions", 4), ["contents: read"])

    def test_only_the_snapshot_and_assert_jobs_log_in_to_azure_and_only_for_reads(self):
        for name, job in self.jobs.items():
            has_login = "azure/login@v2" in job
            self.assertEqual(
                has_login, name in ("snapshot-before", "assert-backends-unchanged"), name
            )
            if has_login:
                self.assertEqual(
                    sorted(_mapping_lines(job, "permissions", 4)),
                    ["contents: read", "id-token: write"],
                    name,
                )

    # -- ordering and the always() assertion --------------------------------------------

    def test_frontend_job_cannot_start_without_the_before_snapshot(self):
        self.assertRegex(self.jobs["deploy-frontend"], r"(?m)^    needs: snapshot-before\s*$")

    def test_before_snapshot_must_be_complete(self):
        job = self.jobs["snapshot-before"]
        self.assertIn("snapshot_container_apps.py snapshot --require-complete", job)
        self.assertRegex(job, r"(?m)^      snapshot: \$\{\{ steps\.snapshot\.outputs\.snapshot \}\}\s*$")

    def test_assert_job_always_runs_once_a_snapshot_exists_and_records_the_result_vector(self):
        job = self.jobs["assert-backends-unchanged"]
        self.assertRegex(job, r"(?m)^    needs: \[snapshot-before, deploy-frontend\]\s*$")
        condition = re.search(r"(?m)^    if:\s*(.+)$", job)
        self.assertIsNotNone(condition)
        # Exact: `always() || ...` would run it after a failed before-snapshot with no input.
        self.assertEqual(
            condition.group(1).strip(),
            "always() && needs.snapshot-before.result == 'success'",
        )
        self.assertRegex(job, r"(?m)^          FRONTEND_RESULT: \$\{\{ needs\.deploy-frontend\.result \}\}\s*$")
        self.assertRegex(job, r"(?m)^          BEFORE: \$\{\{ needs\.snapshot-before\.outputs\.snapshot \}\}\s*$")
        self.assertNotRegex(job, r"(?m)^\s+continue-on-error\s*:")

    # -- flag wiring ---------------------------------------------------------------------

    def test_exposure_flags_are_reported_before_the_build_and_read_from_repository_variables(self):
        job = self.jobs["deploy-frontend"]
        report = _named_block(job, "Report and validate exposure flags")
        build = _named_block(job, "Build Next.js static export")
        self.assertLess(job.index(report), job.index(build))
        self.assertEqual(
            _run_lines(report), ["python3 -I .github/workflows/scripts/check_exposure_flags.py"]
        )
        self.assertNotRegex(report, r"(?m)^\s+(?:if|continue-on-error)\s*:")
        # The whole line must be the bare variable expression: a `|| 'true'` default or a
        # second expression appended to it would enable a control while the variable is unset.
        for step in (report, build):
            self.assertRegex(
                step,
                r"(?m)^\s+(?:NEXT_PUBLIC_)?ENABLE_ASSET_PICKER: \$\{\{ vars\.ENABLE_ASSET_PICKER \}\}[ \t]*$",
            )
            self.assertRegex(
                step,
                r"(?m)^\s+(?:NEXT_PUBLIC_)?ENABLE_DEMO_RESET_CONTROL: "
                r"\$\{\{ vars\.ENABLE_DEMO_RESET_CONTROL \}\}[ \t]*$",
            )
        for variable in ("ENABLE_ASSET_PICKER", "ENABLE_DEMO_RESET_CONTROL"):
            self.assertEqual(
                len(re.findall(rf"vars\.{variable}\b", self.code)),
                2,
                f"vars.{variable} must be read by the report step and the build step only",
            )

    # -- the frontend job is the same build as full mode's -------------------------------

    def test_frontend_steps_match_deploy_azure_full_mode_exactly(self):
        full_mode = self.azure_jobs["deploy-frontend"]
        frontend_only = self.jobs["deploy-frontend"]
        for step in (
            "Checkout code",
            "Set up Node.js",
            "Install frontend dependencies",
            "Build Next.js static export",
            "Deploy to Azure Static Web Apps",
            "Deploy to Azure Static Web Apps (retry)",
        ):
            with self.subTest(step=step):
                self.assertEqual(
                    _normalized(_named_block(frontend_only, step)),
                    _normalized(_named_block(full_mode, step)),
                    f"{step!r} drifted from deploy-azure.yml deploy-frontend",
                )


# What every live single-revision backend app reports for ingress traffic (recorded for all
# four apps in docs/evidence/b1-task-6-5/preflight-20260903.json): a latest-revision entry
# with NO revisionName. A rule that demanded a revision name would block every real run.
LIVE_TRAFFIC = [{"latestRevision": True, "weight": 100}]


def _app(revision: str, image: str, traffic=LIVE_TRAFFIC) -> dict:
    return {"image": image, "revision": revision, "traffic": [dict(entry) for entry in traffic]}


def _snapshot() -> dict:
    registry = "wealthprodacr.azurecr.io"
    return {
        "api-gateway": _app("api-gateway--0000081", f"{registry}/api-gateway@sha256:" + "1" * 64),
        "portfolio-service": _app(
            "portfolio-service--0000096", f"{registry}/portfolio-service@sha256:" + "2" * 64
        ),
        "market-data-service": _app(
            "market-data-service--0000080", f"{registry}/market-data-service@sha256:" + "3" * 64
        ),
        "insight-service": _app(
            "insight-service--0000080", f"{registry}/insight-service@sha256:" + "4" * 64
        ),
        "market-data-refresh-job": {"image": f"{registry}/market-data-service@sha256:" + "3" * 64},
    }


class TestAssertUnchanged(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = _load("snapshot_container_apps_fo", SCRIPTS / "snapshot_container_apps.py")

    def test_identical_snapshots_pass(self):
        self.assertEqual(self.mod.compare_unchanged(_snapshot(), _snapshot()), [])

    def test_every_app_and_the_refresh_job_is_checked(self):
        names = (*self.mod.KNOWN_SERVICES, self.mod.REFRESH_JOB)
        for name in names:
            with self.subTest(name=name):
                after = _snapshot()
                after[name] = {"image": "wealthprodacr.azurecr.io/x@sha256:" + "9" * 64}
                errors = self.mod.compare_unchanged(_snapshot(), after)
                self.assertEqual(len(errors), 1, errors)
                self.assertIn(name, errors[0])

    def test_a_new_revision_with_the_same_image_is_a_change(self):
        after = _snapshot()
        after["api-gateway"] = _app("api-gateway--0000082", after["api-gateway"]["image"])
        self.assertTrue(self.mod.compare_unchanged(_snapshot(), after))

    def test_a_traffic_change_is_a_change(self):
        after = _snapshot()
        after["portfolio-service"]["traffic"] = [
            {"revisionName": "portfolio-service--0000095", "weight": 100}
        ]
        self.assertTrue(self.mod.compare_unchanged(_snapshot(), after))

    def test_missing_entries_on_either_side_fail_closed(self):
        broken = _snapshot()
        broken["insight-service"] = {"missing": True, "error": "ResourceNotFound"}
        self.assertTrue(self.mod.compare_unchanged(broken, _snapshot()))
        self.assertTrue(self.mod.compare_unchanged(_snapshot(), broken))
        # Both sides missing must NOT read as "unchanged": nothing was proven.
        errors = self.mod.compare_unchanged(broken, broken)
        self.assertTrue(any("insight-service" in error for error in errors))

    def test_absent_keys_on_either_side_fail_closed(self):
        partial = _snapshot()
        del partial["market-data-refresh-job"]
        self.assertTrue(self.mod.compare_unchanged(partial, partial))
        self.assertTrue(self.mod.compare_unchanged(_snapshot(), partial))
        self.assertTrue(self.mod.compare_unchanged({}, {}))

    def test_every_problem_is_reported(self):
        before, after = _snapshot(), _snapshot()
        after["api-gateway"] = _app("api-gateway--0000082", "x/y@sha256:" + "8" * 64)
        after["insight-service"] = {"missing": True, "error": "boom"}
        self.assertEqual(len(self.mod.compare_unchanged(before, after)), 2)

    def test_entries_without_a_usable_image_or_revision_are_not_proof(self):
        # If the `az ... --query` shape ever drifted, every field would come back null, and
        # two identical all-null snapshots would read as "unchanged": a hollow proof.
        hollow = {
            name: {"revision": None, "image": None, "traffic": None}
            for name in self.mod.KNOWN_SERVICES
        }
        hollow[self.mod.REFRESH_JOB] = {"image": None}
        self.assertTrue(self.mod.compare_unchanged(hollow, hollow))
        rc, _, err = self._main(["snapshot", "--require-complete"], hollow)
        self.assertEqual(rc, 1)
        self.assertIn("api-gateway", err)

        for name in (*self.mod.KNOWN_SERVICES, self.mod.REFRESH_JOB):
            required = ("image",) if name == self.mod.REFRESH_JOB else ("revision", "image")
            for field in required:
                for blank in (None, "", "   ", 7):
                    with self.subTest(target=name, field=field, blank=blank):
                        broken = _snapshot()
                        broken[name] = {**broken[name], field: blank}
                        errors = self.mod.compare_unchanged(broken, broken)
                        self.assertTrue(any(name in error for error in errors), errors)

    # -- traffic must be positively described for every app ------------------------------

    ABSENT = object()

    def _with_traffic(self, name: str, traffic) -> dict:
        snapshot = _snapshot()
        if traffic is self.ABSENT:
            del snapshot[name]["traffic"]
        else:
            snapshot[name]["traffic"] = traffic
        return snapshot

    def test_the_live_traffic_shapes_are_accepted(self):
        shapes = {
            "live single-revision app": LIVE_TRAFFIC,
            "explicit revision name": [{"revisionName": "api-gateway--0000081", "weight": 100}],
            "split across revisions": [
                {"revisionName": "api-gateway--0000081", "weight": 90},
                {"latestRevision": True, "weight": 10},
            ],
            "with a label": [{"revisionName": "api-gateway--0000081", "weight": 100, "label": "prod"}],
        }
        for label, traffic in shapes.items():
            for name in self.mod.KNOWN_SERVICES:
                with self.subTest(shape=label, app=name):
                    snapshot = self._with_traffic(name, traffic)
                    self.assertEqual(self.mod.compare_unchanged(snapshot, snapshot), [])
                    rc, _, _ = self._main(["snapshot", "--require-complete"], snapshot)
                    self.assertEqual(rc, 0)

    def test_traffic_that_is_not_positively_described_is_not_proof(self):
        # label -> (traffic, the reason an operator should be told)
        none_at_all, malformed = "no ingress traffic", "malformed traffic entry"
        weight, selects = "integer weight between 0 and 100", "selects no revision"
        total = "totalling"
        bad = {
            "absent": (self.ABSENT, none_at_all),
            "null": (None, none_at_all),
            "empty list": ([], none_at_all),
            "a string": ("100", none_at_all),
            "a mapping instead of a list": ({"latestRevision": True, "weight": 100}, none_at_all),
            "an entry that is not a mapping": (["100"], malformed),
            "no weight": ([{"latestRevision": True}], weight),
            "null weight": ([{"latestRevision": True, "weight": None}], weight),
            "string weight": ([{"latestRevision": True, "weight": "100"}], weight),
            "float weight": ([{"latestRevision": True, "weight": 100.0}], weight),
            "boolean weight": ([{"latestRevision": True, "weight": True}], weight),
            "negative weight": ([{"latestRevision": True, "weight": -1}], weight),
            "weight over 100": ([{"latestRevision": True, "weight": 101}], weight),
            "names no revision": ([{"weight": 100}], selects),
            "latestRevision false and unnamed": (
                [{"latestRevision": False, "weight": 100}],
                selects,
            ),
            "latestRevision as a string": ([{"latestRevision": "true", "weight": 100}], selects),
            "blank revisionName": ([{"revisionName": "  ", "weight": 100}], selects),
            "revisionName not a string": ([{"revisionName": 7, "weight": 100}], selects),
            "one bad entry among good ones": (
                [{"latestRevision": True, "weight": 50}, {"weight": 50}],
                selects,
            ),
            # Each of these still totals exactly 100, so only the per-entry check can
            # reject it; the sum check would let it through.
            "a boolean weight completing the total": (
                [
                    {"latestRevision": True, "weight": 99},
                    {"revisionName": "a--1", "weight": True},
                ],
                weight,
            ),
            "a junk entry beside a valid one": (
                [{"latestRevision": True, "weight": 100}, "junk"],
                malformed,
            ),
            "a null entry beside a valid one": (
                [{"latestRevision": True, "weight": 100}, None],
                malformed,
            ),
            "a float zero weight beside a valid entry": (
                [
                    {"latestRevision": True, "weight": 100},
                    {"revisionName": "a--1", "weight": 0.0},
                ],
                weight,
            ),
            "a string weight beside a valid entry": (
                [
                    {"latestRevision": True, "weight": 100},
                    {"revisionName": "a--1", "weight": "0"},
                ],
                weight,
            ),
            "a zero-weight entry that selects no revision": (
                [{"latestRevision": True, "weight": 100}, {"weight": 0}],
                selects,
            ),
            "a zero-weight entry that is latestRevision false": (
                [
                    {"latestRevision": True, "weight": 100},
                    {"latestRevision": False, "weight": 0},
                ],
                selects,
            ),
            "weights total 99": ([{"latestRevision": True, "weight": 99}], total),
            "weights total 110": (
                [
                    {"revisionName": "a--1", "weight": 60},
                    {"revisionName": "a--2", "weight": 50},
                ],
                total,
            ),
        }
        for label, (traffic, reason) in bad.items():
            for name in self.mod.KNOWN_SERVICES:
                with self.subTest(traffic=label, app=name):
                    broken = self._with_traffic(name, traffic)
                    # Both sides equally malformed must still fail: equal is not proven.
                    both = self.mod.compare_unchanged(broken, broken)
                    self.assertTrue(any(name in e and "traffic" in e for e in both), both)
                    # ...and the operator is told which way it is malformed.
                    self.assertTrue(any(name in e and reason in e for e in both), both)
                    self.assertTrue(self.mod.compare_unchanged(_snapshot(), broken))
                    self.assertTrue(self.mod.compare_unchanged(broken, _snapshot()))
                    rc, _, err = self._main(["snapshot", "--require-complete"], broken)
                    self.assertEqual(rc, 1)
                    self.assertIn(name, err)
                    rc, _, err = self._main(
                        ["assert-unchanged", "--before", json.dumps(_snapshot())], broken
                    )
                    self.assertEqual(rc, 1)
                    self.assertIn("traffic", err)
                    rc, _, _ = self._main(
                        ["assert-unchanged", "--before", json.dumps(broken)], _snapshot()
                    )
                    self.assertEqual(rc, 1)

    def test_the_refresh_job_needs_no_traffic(self):
        # The refresh Job has no ingress; only its image is described.
        snapshot = _snapshot()
        self.assertNotIn("traffic", snapshot[self.mod.REFRESH_JOB])
        self.assertEqual(self.mod.compare_unchanged(snapshot, snapshot), [])

    def test_the_scoped_compare_still_rejects_an_empty_selection(self):
        with self.assertRaises(ValueError):
            self.mod.compare(_snapshot(), _snapshot(), [])

    def test_scoped_mode_does_not_gain_the_traffic_requirement(self):
        # compare() and plain `snapshot` serve scoped/digest deploys and are unchanged.
        after = _snapshot()
        after["api-gateway"] = _app("api-gateway--0000082", "wealthprodacr.azurecr.io/api-gateway:new")
        after["portfolio-service"]["traffic"] = None
        before = _snapshot()
        before["portfolio-service"]["traffic"] = None
        self.assertEqual(
            self.mod.compare(before, after, ["api-gateway"], git_sha="new"),
            [],
        )
        rc, _, _ = self._main(["snapshot"], after)
        self.assertEqual(rc, 0)

    # -- the Azure CLI is reached in exactly one place, and only for reads --------------

    def test_the_five_targets_are_exactly_the_four_backends_and_the_refresh_job(self):
        # The other tests iterate the module's own lists; without this pin, dropping an app
        # from KNOWN_SERVICES would shrink the proof and every test would shrink with it.
        self.assertEqual(
            self.mod.KNOWN_SERVICES,
            ("api-gateway", "portfolio-service", "market-data-service", "insight-service"),
        )
        self.assertEqual(self.mod.REFRESH_JOB, "market-data-refresh-job")
        self.assertEqual(
            self.mod.ALL_TARGETS, (*self.mod.KNOWN_SERVICES, self.mod.REFRESH_JOB)
        )

    def test_capture_reads_each_target_by_name_in_the_given_resource_group(self):
        calls: list[list[str]] = []

        def fake_az(args: list[str]) -> subprocess.CompletedProcess:
            calls.append(list(args))
            return subprocess.CompletedProcess(args, 0, stdout='{"image":"x"}', stderr="")

        # Under the production resource group and CI environment, so a branch on either
        # (an update, or a fabricated identity, only when it looks like production) is run.
        with mock.patch.dict(os.environ, PROD_ENV):
            captured = self.mod.capture(PROD_ENV["AZURE_RG"], run_az=fake_az)
        self.assertEqual(
            captured,
            {name: {"image": "x"} for name in self.mod.ALL_TARGETS},
            "capture must return exactly what the show reads reported",
        )
        read = {args[args.index("--name") + 1]: args for args in calls}
        # The query is what makes "unchanged" mean something: a drift to another field (say
        # `revision:name`, which never changes) would let a same-image new revision through.
        app_query = (
            "{revision:properties.latestRevisionName,"
            "image:properties.template.containers[0].image,"
            "traffic:properties.configuration.ingress.traffic}"
        )
        job_query = "{image:properties.template.containers[0].image}"
        for name, args in read.items():
            with self.subTest(target=name):
                expected = job_query if name == "market-data-refresh-job" else app_query
                self.assertEqual(args[args.index("--query") + 1], expected)
                self.assertEqual(args[args.index("-o") + 1], "json")
        self.assertEqual(
            set(read),
            {
                "api-gateway",
                "portfolio-service",
                "market-data-service",
                "insight-service",
                "market-data-refresh-job",
            },
        )
        for name, args in read.items():
            self.assertEqual(args[args.index("--resource-group") + 1], PROD_ENV["AZURE_RG"], name)

    def test_the_azure_cli_is_called_from_exactly_one_place(self):
        tree = ast.parse(_read(SCRIPTS / "snapshot_container_apps.py"))
        hits: list[tuple[str, str]] = []
        obscured: list[str] = []
        imported: set[str] = set()

        def visit(node: ast.AST, function: str) -> None:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                function = node.name
            if isinstance(node, ast.Import):
                for alias in node.names:
                    imported.add(alias.name.split(".")[0])
                    if alias.asname:
                        obscured.append(f"import {alias.name} as {alias.asname}")
            if isinstance(node, ast.ImportFrom):
                imported.add((node.module or "").split(".")[0])
                if node.module in ("subprocess", "os"):
                    obscured.append(f"from {node.module} import ...")
            if isinstance(node, ast.Call):
                target = ast.unparse(node.func)
                if re.match(
                    r"(subprocess\.|os\.(system|popen|spawn|posix_spawn|exec)|Popen\b|pty\."
                    r"|asyncio\.create_subprocess|__import__\b|getattr\b|eval\b|exec\b|compile\b)",
                    target,
                ):
                    hits.append((function, target))
            for child in ast.iter_child_nodes(node):
                visit(child, function)

        visit(tree, "<module>")
        # Every read goes through `run_az`, from exactly the two identity helpers, with a
        # literal argv whose verb is `containerapp show` / `containerapp job show`.
        sites: list[tuple[str, tuple[str, ...]]] = []
        for function in ast.walk(tree):
            if not isinstance(function, (ast.FunctionDef, ast.AsyncFunctionDef)):
                continue
            for call in ast.walk(function):
                if (
                    isinstance(call, ast.Call)
                    and isinstance(call.func, ast.Name)
                    and call.func.id in ("run_az", "_run_az")
                ):
                    argv = call.args[0] if call.args else None
                    literal: list[str] = []
                    if isinstance(argv, ast.List):
                        for element in argv.elts:
                            if not (
                                isinstance(element, ast.Constant) and isinstance(element.value, str)
                            ):
                                break
                            literal.append(element.value)
                    verb = literal[: literal.index("show") + 1] if "show" in literal else literal
                    sites.append((function.name, tuple(verb)))
        self.assertEqual(
            sorted(sites),
            [
                ("_app_identity", ("containerapp", "show")),
                ("_job_identity", ("containerapp", "job", "show")),
            ],
        )
        # Dynamic dispatch reaches a callable by a name this pin cannot see; the script has
        # no use for it, so the names themselves are banned (not only calls to them).
        dynamic = {n.id for n in ast.walk(tree) if isinstance(n, ast.Name)} & {
            "__import__",
            "getattr",
            "setattr",
            "delattr",
            "eval",
            "exec",
            "compile",
            "globals",
            "locals",
            "vars",
        }
        self.assertEqual(dynamic, set(), "no dynamic dispatch in the Azure-facing script")
        # Reflection reaches process creation without any banned name: `os.__dict__['system']`,
        # `sys.modules['os'].system`. Dunder attributes and `.modules` have no use here.
        reflective = {
            n.attr
            for n in ast.walk(tree)
            if isinstance(n, ast.Attribute) and (n.attr.startswith("__") or n.attr == "modules")
        }
        self.assertEqual(reflective, set(), "no reflection in the Azure-facing script")
        self.assertEqual(obscured, [], "import subprocess/os plainly so calls stay visible")
        # An allowlist rather than a denylist: a new import (asyncio, ctypes, importlib,
        # shutil, ...) is a new way to reach outside the process and must be reviewed
        # against the no-backend-write property before this list is extended.
        self.assertEqual(
            imported,
            {"__future__", "argparse", "json", "os", "pathlib", "re", "subprocess", "sys", "typing"},
        )
        self.assertEqual(hits, [("_run_az", "subprocess.run")])

    def test_both_cli_commands_reach_azure_only_through_show_reads(self):
        payload = {
            "revision": "app--0000001",
            "image": "wealthprodacr.azurecr.io/app@sha256:" + "5" * 64,
            "traffic": [{"revisionName": "app--0000001", "weight": 100}],
        }
        argvs: list[list[str]] = []

        def fake_run(argv, **kwargs):
            argvs.append(list(argv))
            return subprocess.CompletedProcess(argv, 0, stdout=json.dumps(payload), stderr="")

        def refuse(*args, **kwargs):
            raise AssertionError("a process was started outside _run_az")

        # Every other way to start a process is made to fail, so reflection or an alias the
        # static pin cannot name still cannot run a command beside the show reads.
        blocked = [(subprocess, "Popen"), (os, "system"), (os, "popen")] + [
            (os, name)
            for name in dir(os)
            if name.startswith(("spawn", "exec", "posix_spawn"))
            or name in ("fork", "forkpty", "startfile")
        ]
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(self.mod.subprocess, "run", side_effect=fake_run))
            for module, name in blocked:
                stack.enter_context(mock.patch.object(module, name, side_effect=refuse))
            rc, out, _ = self._main(["snapshot", "--require-complete"])
            self.assertEqual(rc, 0)
            rc, _, _ = self._main(["assert-unchanged", "--before", json.dumps(json.loads(out))])
            self.assertEqual(rc, 0)
        self.assertEqual(len(argvs), 2 * (len(self.mod.KNOWN_SERVICES) + 1))
        for argv in argvs:
            self.assertEqual(argv[0], "az", argv)
            self.assertTrue(
                argv[1:3] == ["containerapp", "show"] or argv[1:4] == ["containerapp", "job", "show"],
                argv,
            )

    # -- CLI -------------------------------------------------------------------------------

    def _main(self, argv, capture_result=None, env=None, forbid_capture=False):
        env = dict(PROD_ENV) if env is None else env
        out, err = io.StringIO(), io.StringIO()
        patches = [
            mock.patch.dict(os.environ, env, clear=True),
            mock.patch("sys.argv", ["snapshot", *argv]),
            contextlib.redirect_stdout(out),
            contextlib.redirect_stderr(err),
        ]
        if forbid_capture:
            patches.append(
                mock.patch.object(
                    self.mod, "capture", side_effect=AssertionError("capture must not run")
                )
            )
        elif capture_result is not None:
            patches.append(mock.patch.object(self.mod, "capture", return_value=capture_result))
        with contextlib.ExitStack() as stack:
            for patch in patches:
                stack.enter_context(patch)
            return self.mod.main(), out.getvalue(), err.getvalue()

    def test_cli_assert_unchanged_returns_zero_when_nothing_changed(self):
        rc, out, _ = self._main(
            ["assert-unchanged", "--before", json.dumps(_snapshot())], _snapshot()
        )
        self.assertEqual(rc, 0)
        self.assertIn("Non-interference proof passed", out)

    def test_cli_assert_unchanged_returns_one_and_names_the_change(self):
        after = _snapshot()
        after["api-gateway"] = _app("api-gateway--0000082", "x/y@sha256:" + "8" * 64)
        rc, _, err = self._main(["assert-unchanged", "--before", json.dumps(_snapshot())], after)
        self.assertEqual(rc, 1)
        self.assertIn("::error::", err)
        self.assertIn("api-gateway", err)

    def test_cli_assert_unchanged_rejects_a_missing_or_malformed_before_snapshot(self):
        for before in ("", "not json", "[]", "null"):
            with self.subTest(before=before):
                # No live read may happen before the input it will be compared with is
                # known to be usable.
                rc, _, err = self._main(
                    ["assert-unchanged", "--before", before], forbid_capture=True
                )
                self.assertEqual(rc, 1)
                self.assertIn("::error::", err)

    def test_cli_assert_unchanged_requires_the_resource_group(self):
        rc, _, err = self._main(
            ["assert-unchanged", "--before", json.dumps(_snapshot())], _snapshot(), env={}
        )
        self.assertEqual(rc, 1)
        self.assertIn("AZURE_RG", err)

    def test_snapshot_require_complete_fails_on_a_missing_app(self):
        broken = _snapshot()
        broken["portfolio-service"] = {"missing": True, "error": "ResourceNotFound"}
        rc, _, err = self._main(["snapshot", "--require-complete"], broken)
        self.assertEqual(rc, 1)
        self.assertIn("portfolio-service", err)

    def test_snapshot_require_complete_fails_on_a_missing_refresh_job(self):
        broken = _snapshot()
        broken["market-data-refresh-job"] = {"missing": True, "error": "ResourceNotFound"}
        rc, _, _ = self._main(["snapshot", "--require-complete"], broken)
        self.assertEqual(rc, 1)

    def test_snapshot_writes_the_single_line_output_the_assert_job_reads(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "github_output"
            rc, _, _ = self._main(
                ["snapshot", "--require-complete"],
                _snapshot(),
                env={**PROD_ENV, "GITHUB_OUTPUT": str(output)},
            )
            self.assertEqual(rc, 0)
            lines = output.read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(lines), 1, "the job output must be a single line")
            name, _, value = lines[0].partition("=")
            self.assertEqual(name, "snapshot")
            self.assertEqual(json.loads(value), _snapshot())
            # The value published by snapshot-before is exactly what assert-unchanged takes.
            rc, _, _ = self._main(["assert-unchanged", "--before", value], _snapshot())
            self.assertEqual(rc, 0)

    def test_an_incomplete_snapshot_publishes_no_output(self):
        broken = _snapshot()
        broken["insight-service"] = {"missing": True, "error": "ResourceNotFound"}
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "github_output"
            rc, _, _ = self._main(
                ["snapshot", "--require-complete"],
                broken,
                env={**PROD_ENV, "GITHUB_OUTPUT": str(output)},
            )
            self.assertEqual(rc, 1)
            self.assertFalse(output.exists() and output.read_text(encoding="utf-8"))

    def test_snapshot_require_complete_passes_a_complete_snapshot(self):
        rc, _, _ = self._main(["snapshot", "--require-complete"], _snapshot())
        self.assertEqual(rc, 0)

    def test_plain_snapshot_keeps_scoped_mode_behaviour_when_something_is_missing(self):
        broken = _snapshot()
        broken["portfolio-service"] = {"missing": True, "error": "ResourceNotFound"}
        rc, _, _ = self._main(["snapshot"], broken)
        self.assertEqual(rc, 0)

    # -- the capture both modes share is read-only --------------------------------------

    def test_capture_issues_only_show_commands(self):
        calls: list[list[str]] = []

        def fake_az(args: list[str]) -> subprocess.CompletedProcess:
            calls.append(list(args))
            return subprocess.CompletedProcess(args, 0, stdout='{"image":"x"}', stderr="")

        self.mod.capture("rg", run_az=fake_az)
        self.assertEqual(len(calls), len(self.mod.KNOWN_SERVICES) + 1)
        for args in calls:
            self.assertTrue(
                args[:2] == ["containerapp", "show"] or args[:3] == ["containerapp", "job", "show"],
                args,
            )
            self.assertFalse(
                {"update", "create", "delete", "restart", "start", "stop", "set", "up", "copy"}
                & set(args),
                args,
            )


class TestNoStdlibShadowing(unittest.TestCase):
    """`python3 <dir>/script.py` (and `python <dir>/test_x.py`) puts <dir> first on sys.path.

    A file or package named like a standard-library module in either directory is imported
    in place of the standard library, in the scripts that run under an Azure session and in
    the tests that are meant to police them. Nothing in a script's or a test's own text
    shows it, so the directories themselves are checked."""

    def test_no_stdlib_named_module_sits_beside_the_scripts_or_the_tests(self):
        for directory in (SCRIPTS, REPO / "scripts" / "tests"):
            entries = {p.stem if p.is_file() else p.name for p in directory.iterdir()}
            self.assertEqual(
                sorted((entries - {"__pycache__"}) & set(sys.stdlib_module_names)),
                [],
                f"{directory} would shadow the standard library",
            )


class TestScriptSurfaces(unittest.TestCase):
    """The scripts other than the snapshot script that run on the deploy path.

    validate_deploy_dispatch.py runs before approval in a job that inherits the dispatcher's
    workflow-level `id-token: write`, and check_exposure_flags.py runs in the frontend job
    before the build. Neither has any business starting a process, opening a file, using the
    network or reaching anything but its own environment variables, so their whole surface is
    pinned rather than trusting their current logic."""

    DYNAMIC = {
        "__import__", "getattr", "setattr", "delattr", "eval", "exec", "compile",
        "globals", "locals", "vars", "open",
    }

    def _surface(self, name: str) -> dict:
        tree = ast.parse(_read(SCRIPTS / name))
        imports: set[str] = set()
        os_attrs: set[str] = set()
        names: set[str] = set()
        reflective: set[str] = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imports |= {alias.name.split(".")[0] for alias in node.names}
            elif isinstance(node, ast.ImportFrom):
                imports.add((node.module or "").split(".")[0])
            elif isinstance(node, ast.Name):
                names.add(node.id)
            elif isinstance(node, ast.Attribute):
                if isinstance(node.value, ast.Name) and node.value.id == "os":
                    os_attrs.add(node.attr)
                if node.attr.startswith("__") or node.attr == "modules":
                    reflective.add(node.attr)
        return {
            "imports": imports,
            "os_attrs": os_attrs,
            "dynamic": names & self.DYNAMIC,
            "reflective": reflective,
        }

    def test_the_dispatch_validator_only_reads_its_environment(self):
        surface = self._surface("validate_deploy_dispatch.py")
        self.assertEqual(surface["imports"], {"__future__", "dataclasses", "os", "sys"})
        self.assertEqual(surface["os_attrs"], {"environ"})
        self.assertEqual(surface["dynamic"], set())
        self.assertEqual(surface["reflective"], set())

    def test_the_flag_check_only_reads_its_environment(self):
        surface = self._surface("check_exposure_flags.py")
        self.assertEqual(surface["imports"], {"__future__", "os", "sys"})
        self.assertEqual(surface["os_attrs"], {"environ"})
        self.assertEqual(surface["dynamic"], set())
        self.assertEqual(surface["reflective"], set())


class TestExposureFlagCheck(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = _load("check_exposure_flags", SCRIPTS / "check_exposure_flags.py")

    def _run(self, **flags):
        env = {k: v for k, v in flags.items() if v is not None}
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, env, clear=True), contextlib.redirect_stdout(
            out
        ), contextlib.redirect_stderr(err):
            return self.mod.main(), out.getvalue(), err.getvalue()

    def test_every_unset_true_or_false_combination_is_accepted(self):
        values = (None, "", "true", "false")
        for picker in values:
            for reset in values:
                with self.subTest(picker=picker, reset=reset):
                    rc, _, _ = self._run(
                        ENABLE_ASSET_PICKER=picker, ENABLE_DEMO_RESET_CONTROL=reset
                    )
                    self.assertEqual(rc, 0)

    def test_ambiguous_values_are_rejected_for_either_flag(self):
        for bad in ("True", "TRUE", " true", "true ", "1", "yes", "on", "tru", "enabled", "0"):
            for name in ("ENABLE_ASSET_PICKER", "ENABLE_DEMO_RESET_CONTROL"):
                with self.subTest(name=name, bad=bad):
                    rc, _, err = self._run(**{name: bad})
                    self.assertEqual(rc, 1)
                    self.assertIn(name, err)
                    self.assertIn("::error::", err)

    def test_the_report_states_what_the_bundle_will_do_for_each_flag(self):
        rc, out, _ = self._run(ENABLE_ASSET_PICKER="true", ENABLE_DEMO_RESET_CONTROL="false")
        self.assertEqual(rc, 0)
        self.assertRegex(out, r"ENABLE_ASSET_PICKER='true'.*enabled")
        self.assertRegex(out, r"ENABLE_DEMO_RESET_CONTROL='false'.*disabled")

    def test_an_unset_flag_is_reported_as_disabled_and_unset(self):
        rc, out, _ = self._run()
        self.assertEqual(rc, 0)
        self.assertRegex(out, r"ENABLE_ASSET_PICKER=''.*disabled.*unset")
        self.assertRegex(out, r"ENABLE_DEMO_RESET_CONTROL=''.*disabled.*unset")

    def test_a_rejected_value_is_reported_for_both_flags_at_once(self):
        rc, _, err = self._run(ENABLE_ASSET_PICKER="True", ENABLE_DEMO_RESET_CONTROL="1")
        self.assertEqual(rc, 1)
        self.assertIn("ENABLE_ASSET_PICKER", err)
        self.assertIn("ENABLE_DEMO_RESET_CONTROL", err)

    def test_it_imports_nothing_that_could_reach_out(self):
        tree = ast.parse(_read(SCRIPTS / "check_exposure_flags.py"))
        imported = {
            alias.name.split(".")[0]
            for node in ast.walk(tree)
            if isinstance(node, ast.Import)
            for alias in node.names
        } | {
            node.module.split(".")[0]
            for node in ast.walk(tree)
            if isinstance(node, ast.ImportFrom) and node.module
        }
        self.assertEqual(imported, {"__future__", "os", "sys"})


class TestSplitHelpers(unittest.TestCase):
    """Every job and step inventory in this file goes through these splitters. A job or step
    they cannot see would be invisible to every pin, so what they cannot parse must fail
    loudly instead of vanishing (valid YAML the regex-based pins would otherwise skip:
    an uppercase or underscore-leading job id, a quoted id, text before the first job, a
    bare `-` step)."""

    def test_every_valid_github_job_id_is_a_job(self):
        text = "jobs:\n  Rogue:\n    runs-on: x\n  _second:\n    runs-on: y\n  ok-1:\n    runs-on: z\n"
        self.assertEqual(list(_jobs(text)), ["Rogue", "_second", "ok-1"])

    def test_a_job_id_the_splitter_cannot_read_is_an_error(self):
        for label, entry in {
            "double-quoted": '  "rogue":\n    runs-on: x\n',
            "single-quoted": "  'rogue':\n    runs-on: x\n",
            "indented one space": " rogue:\n    runs-on: x\n",
            "indented three spaces": "   rogue:\n    runs-on: x\n",
            "tab indented": "\trogue:\n    runs-on: x\n",
            "flow value": "  rogue: {runs-on: x}\n",
        }.items():
            with self.subTest(entry=label), self.assertRaises(AssertionError):
                _jobs("jobs:\n  first:\n    runs-on: x\n" + entry)

    def test_content_before_the_first_job_is_an_error(self):
        with self.assertRaises(AssertionError):
            _jobs('jobs:\n  "rogue":\n    runs-on: x\n  first:\n    runs-on: y\n')
        with self.assertRaises(AssertionError):
            _jobs("jobs:\n    stray: 1\n  first:\n    runs-on: y\n")

    def test_comments_and_blank_lines_are_fine_anywhere(self):
        text = "jobs:\n  # note\n\n  first:\n    runs-on: x\n  # between\n\n  second:\n    runs-on: y\n"
        self.assertEqual(list(_jobs(text)), ["first", "second"])

    def test_a_repeated_job_id_is_an_error(self):
        with self.assertRaises(AssertionError):
            _jobs("jobs:\n  a:\n    runs-on: x\n  a:\n    runs-on: y\n")

    def test_the_jobs_mapping_ends_at_the_next_top_level_key(self):
        text = "jobs:\n  first:\n    runs-on: x\nother:\n  not-a-job:\n    runs-on: y\n"
        self.assertEqual(list(_jobs(text)), ["first"])

    def test_a_missing_jobs_block_is_an_error(self):
        with self.assertRaises(AssertionError):
            _jobs("on: push\n")

    def test_a_tab_deep_inside_block_content_is_not_structure(self):
        # Tabs are legal inside a block scalar; only one that could be job-level indentation
        # is refused.
        text = "jobs:\n  a:\n    steps:\n      - run: |\n          x\t\ty\n\t\tz\n"
        self.assertEqual(list(_jobs(text.replace("\t\tz\n", ""))), ["a"])
        for shallow in ("\trogue:\n", "  \trogue:\n", "\t  rogue:\n"):
            with self.subTest(line=shallow), self.assertRaises(AssertionError):
                _jobs("jobs:\n  a:\n    runs-on: x\n" + shallow)

    def test_lenient_mode_leaves_unrelated_jobs_alone(self):
        # ci-verification.yml is edited by everyone; only the jobs this contract reads need
        # to be found, and a benign edit to any other job must not fail the required check.
        text = (
            "jobs:\n  unit-tests:  # gradle suite\n    runs-on: x\n"
            "  lint-docs: # markdown lint\n    steps:\n      - run: |\n          a\t\tb\n"
            '  "quoted":\n    runs-on: y\n  static-guard:\n    runs-on: z\n'
        )
        self.assertEqual(list(_jobs(text, strict=False)), ["unit-tests", "lint-docs", "static-guard"])
        with self.assertRaises(AssertionError):
            _jobs(text)

    def test_every_sequence_entry_counts_as_a_step(self):
        job = (
            "  j:\n    steps:\n      - name: a\n        run: x\n      -\n        run: y\n"
            "      - # note\n        run: z\n      - uses: q\n"
        )
        self.assertEqual(_step_count(job), 4)
        self.assertEqual(_step_names(job), ["a"])

    def test_steps_at_another_indent_do_not_hide_from_the_count(self):
        job = "  j:\n    steps:\n      - name: a\n    - run: rogue\n"
        self.assertEqual(_step_count(job), 2)

    def test_the_annotation_matcher_accepts_only_one_plain_quoted_string(self):
        # Wording of ::notice::/::error:: lines is not pinned, so what the matcher accepts is
        # the only thing standing between "a message" and "a command": it must be exactly one
        # double-quoted string, `${NAME}` the only expansion, and nothing after the quote.
        accepted = [
            'echo "::notice::Routing to deploy-azure.yml. deploy-aws.yml will NOT run."',
            'echo "::error::CLOUD_PROVIDER=\'${PROVIDER}\' is not recognized."',
            'echo "::error::Set it to \'azure\' or \'aws\' in Settings → Variables."',
            'echo "::notice::(CLOUD_PROVIDER=${PROVIDER}) ok"',
        ]
        rejected = [
            'echo "::notice::ok"; pip install azure-mgmt-appcontainers; python3 -c "x"',
            'echo "::notice::ok" && curl x',
            'echo "::notice::ok" | sh',
            'echo "::notice::ok" > /tmp/x',
            'echo "::notice::ok" 2>&1',
            'echo "::error::$(pip install x && python3 -c y)"',
            'echo "::error::`id`"',
            'echo "::error::${PROVIDER:-$(id)}"',
            'echo "::error::${PROVIDER}${OTHER"',
            'echo "::error::a\\"; id; echo \\"b"',
            'echo "::notice::ok',
            'echo "::notice::a" "b"',
            'echo "::warning::not an allowed annotation"',
            'echo "::notice::ok" # trailing',
        ]
        for line in accepted:
            with self.subTest(line=line):
                self.assertRegex(line, _ANNOTATION_LINE)
        for line in rejected:
            with self.subTest(line=line):
                self.assertNotRegex(line, _ANNOTATION_LINE)

    def test_a_continued_plain_run_value_is_not_hidden(self):
        # A plain scalar may continue on the next, more indented line; reading only the first
        # line would let extra arguments through an exact-match pin.
        step = "      - name: x\n        run: python a.py\n          --evil\n"
        self.assertEqual(_run_lines(step), ["python a.py", "--evil"])
        self.assertEqual(
            _run_lines("      - name: x\n        run: python a.py\n        if: true\n"),
            ["python a.py"],
        )

    def test_a_block_run_value_is_read_whole(self):
        step = "      - name: x\n        run: |\n          one\n            two\n\n          three\n"
        self.assertEqual(_run_lines(step), ["one", "  two", "", "three"])


def _hazard_kinds(text: str) -> set[str]:
    return {hazard.split(": ", 2)[1] for hazard in _yaml_hazards(text)}


class TestWorkflowYamlHazards(unittest.TestCase):
    """Every assertion in this file reads workflow YAML as text.

    Anchors, aliases, merge keys and duplicate mapping keys let the value a parser actually
    uses differ from the text a regex inspected (a second `permissions:` that wins, a `*alias`
    standing in for a pinned mapping, `<<:` pulling keys in from elsewhere), so the protected
    workflow sections must not use them. The scanner is deliberately strict: a YAML construct
    it does not understand is reported too, so the protected files have to stay inside the
    simple block-YAML subset it models.
    """

    HAZARDS = {
        "anchor": {
            "on a mapping value": "a: &x\n  b: c\n",
            "on a scalar value": "a: &x value\n",
            "on a sequence item": "a:\n  - &x value\n",
            "on a key": "&x a: b\n",
            "before a nested mapping": "a:\n  &x\n  b: c\n",
            "inside a flow sequence": "a: [&x b, c]\n",
            "after a comma in a flow sequence": "a: [b, &x c]\n",
            "inside a flow pair": "a: [k: &x v]\n",
            "inside a nested flow sequence": "a: [[&x b]]\n",
            "in a flow sequence item": "a:\n  - [b, &x c]\n",
            "in a flow sequence item pair": "a:\n  - [k: &x v]\n",
            "on a step's env": "steps:\n  - name: x\n    env: &e\n      A: b\n",
            "after a dash and a key": "steps:\n  - &s name: x\n",
        },
        "alias": {
            "as a value": "a: *x\n",
            "as a sequence item": "a:\n  - *x\n",
            "inside a flow sequence": "a: [*x, b]\n",
            "inside a flow pair": "a: [k: *x]\n",
            "after a comma in a flow sequence": "a: [b, *x]\n",
            "in a flow sequence item": "a:\n  - [b, *x]\n",
            "on its own line": "a:\n  *x\n",
        },
        "merge key": {
            "with an alias": "a:\n  <<: *x\n",
            "with a block mapping": "a:\n  <<:\n    b: c\n",
            "quoted": 'a:\n  "<<": c\n',
            "as a sequence item's key": "steps:\n  - <<: *x\n",
            "inside a flow sequence": "a: [<<: *x]\n",
            "after a comma in a flow sequence": "a: [b, <<: c]\n",
            # `- [<<: ...]` is a flow sequence item; read as `key: value` its key would be
            # `[<<` and the merge key would go unrecognised.
            "in a flow sequence item": "a:\n  - [<<: *x, p]\n",
            "in a flow sequence item beside a scalar": "a:\n  - [p, <<: c]\n",
        },
        "duplicate key": {
            "at the top level": "a: 1\nb: 2\na: 3\n",
            "in a nested mapping": "a:\n  b: 1\n  b: 2\n",
            "in a step mapping": "steps:\n  - name: x\n    run: a\n    run: b\n",
            "a repeated job id": "jobs:\n  a:\n    x: 1\n  a:\n    y: 2\n",
            "quoted and plain": 'a: 1\n"a": 2\n',
            "single-quote escapes": "'a''b': 1\n\"a'b\": 2\n",
            "double-quote escapes": "\"a\\\"b\": 1\n'a\"b': 2\n",
            "after a block scalar": "a: |\n  text\nb: 1\na: 2\n",
            "in a secrets mapping": "secrets:\n  A: 1\n  B: 2\n  A: 3\n",
            "a repeated permissions block": "job:\n  permissions:\n    contents: read\n  permissions:\n    id-token: write\n",
            "in an indentless sequence item": "steps:\n- name: a\n  run: x\n  run: y\n",
            "after leaving and re-entering a mapping": "a:\n  b: 1\nc:\n  d: 1\na: 2\n",
        },
        "unsupported": {
            "a flow mapping": "a: {b: c}\n",
            "a flow mapping item": "a:\n  - {b: c}\n",
            "a flow mapping inside a flow sequence": "a: [{b: c}]\n",
            "an implicit mapping inside a flow sequence": "a: [k: v]\n",
            "a null-valued implicit mapping in a flow sequence": "a: [k:]\n",
            "an implicit mapping in a nested flow sequence": "a: [[k: v]]\n",
            "a tag inside a flow sequence": "a: [!!str b]\n",
            "a tag after a flow pair colon": "a: [k: !!str v]\n",
            # A quote that opens inside a flow sequence and closes on a later line can hide
            # structure on that later line, so it is unsupported just like one that opens
            # at the start of a value.
            "an unterminated single quote inside a flow sequence": "b: [p, 'q]\n  r', &w 1]\n",
            "an unterminated double quote inside a flow sequence": 'b: [p, "q]\n  r", &w 1]\n',
            "an unterminated quote in a flow sequence item": "a:\n  - [x, 'y]\n    z', *w]\n",
            "an unterminated quote hiding a merge key": "b: [p, 'q]\n  r', <<: *w]\n",
            "an unterminated quote in a nested flow sequence": "a: [b, [c, 'd]]\n",
            "a tag": "a: !!str x\n",
            "a document marker": "---\na: 1\n",
            "a document end marker": "a: 1\n...\n",
            "tab indentation": "a:\n\tb: 1\n",
            "a complex key": "? a\n: b\n",
            "an unterminated quote": 'a: "b\n  c"\n',
            "a multi-line flow sequence": "a: [b,\n  c]\n",
            "a directive": "%YAML 1.2\na: 1\n",
            # A bare `-` puts the entry on the following lines, where a regex that looks for
            # `- name:` / `- run:` never sees it.
            "a bare sequence indicator": "steps:\n  -\n    run: x\n",
            "a comment-only sequence entry": "steps:\n  - # note\n    run: x\n",
            "a nested bare sequence indicator": "steps:\n  - -\n      run: x\n",
            # The pins read plain keys; a quoted key is the same key to a YAML parser but
            # invisible to them (`"permissions": write-all`), so the protected files may
            # not use one.
            "a double-quoted key": '"a": 1\n',
            "a single-quoted key": "'a': 1\n",
            "a quoted key in a step": 'steps:\n  - "run": x\n',
            "a quoted job-level key": 'jobs:\n  j:\n    "permissions": write-all\n',
        },
    }

    CLEAN = {
        "the same key in sibling sequence items": "steps:\n  - name: a\n    run: x\n  - name: b\n    run: y\n",
        "the same key under different parents": "a:\n  x: 1\nb:\n  x: 2\n",
        "the same nested shape under two jobs": "jobs:\n  a:\n    steps:\n      - name: a\n  b:\n    steps:\n      - name: a\n",
        "ampersands and stars inside values": "if: a && b\nrun: ls *.txt\nx: \"&a *b\"\ny: ${{ a && b }}\nz: 'x *y'\n",
        "shell text inside a block scalar": "run: |\n  echo &&\n  a: 1\n  a: 2\n  &x *y <<: z\nnext: 1\n",
        "folded text inside a block scalar": "if: >-\n  a &&\n  b: c\nnext: 1\n",
        "comments mentioning anchors": "# &x *y <<: z\na: 1\n",
        "keys differing only by case": "a: 1\nA: 2\n",
        "an indentless sequence": "steps:\n- name: a\n  run: x\n- name: b\n  run: y\n",
        "flow sequences of plain and quoted scalars": "needs: [a, b]\non: [push, 'pull_request']\nbranches: [\"main\", \"feature/**\"]\n",
        "flow sequences whose items contain colons": "urls: [http://x.dev, a:b, 10:30]\n",
        "flow sequences of quoted pair-like text": "a: ['k: v', \"x: &y\", 'q: *z']\n",
        "an empty flow sequence": "needs: []\n",
        "flow sequence items": "a:\n  - [b, c]\n  - ['k: v', d]\n  - []\n",
        "a flow sequence followed by a comment": "needs: [a, b]  # &x *y k: v\n",
        "quoted stars and ampersands as items": "branches:\n  - \"*\"\n  - '**'\n  - \"&\"\n",
        "block scalar with a chomping indicator and a comment": "run: |- # note\n  a: 1\n  a: 2\nnext: 1\n",
        "an empty value opening a nested mapping": "env:\n  A: 1\n  B: 2\nrun: x\n",
        "a plain multi-line continuation": "A: ${{ vars.X }}\n     ${{ 'y' }}\nB: 1\n",
        "values that merely contain a hash": "a: 'x # y'\nb: \"p # q\"\nc: x#y\n",
    }

    def test_each_hazard_is_reported_under_its_own_kind(self):
        for kind, cases in self.HAZARDS.items():
            for label, text in cases.items():
                with self.subTest(kind=kind, case=label):
                    self.assertIn(kind, _hazard_kinds(text), _yaml_hazards(text))

    def test_valid_yaml_that_only_looks_similar_is_not_reported(self):
        for label, text in self.CLEAN.items():
            with self.subTest(case=label):
                self.assertEqual(_yaml_hazards(text), [])

    def test_a_reported_hazard_names_its_line(self):
        hazards = _yaml_hazards("a: 1\nb: 2\nc: &x 3\n")
        self.assertEqual(len(hazards), 1)
        self.assertTrue(hazards[0].startswith("line 3: anchor: "), hazards)

    def test_every_hazard_in_a_file_is_reported_not_just_the_first(self):
        text = "a: &x 1\nb: *x\n<<: *x\nc: 1\nc: 2\n"
        self.assertEqual(
            _hazard_kinds(text), {"anchor", "alias", "merge key", "duplicate key"}
        )

    # -- the protected sections ----------------------------------------------------------

    def _protected(self):
        """Whole files for the dispatcher, the new workflow and the full-mode workflow it is
        compared against; the two CI jobs that wire this contract in."""
        for path in (DISPATCHER, FRONTEND_ONLY, DEPLOY_AZURE):
            yield path.name, _read(path)
        ci = _read(CI_WORKFLOW)
        for job in ("static-guard", "deploy-workflow-contract"):
            yield f"{CI_WORKFLOW.name}:{job}", _jobs(ci, strict=False)[job]

    def test_the_protected_scope_is_the_documented_one(self):
        # A scan that quietly stops covering a file protects nothing.
        self.assertEqual(
            [label for label, _ in self._protected()],
            [
                "deploy.yml",
                "deploy-azure-frontend.yml",
                "deploy-azure.yml",
                "ci-verification.yml:static-guard",
                "ci-verification.yml:deploy-workflow-contract",
            ],
        )

    def test_the_protected_sections_have_no_anchors_aliases_merge_keys_or_duplicates(self):
        for label, text in self._protected():
            with self.subTest(section=label):
                self.assertEqual(_yaml_hazards(text), [])

    def test_the_protected_ci_jobs_are_defined_exactly_once(self):
        # `_jobs` keeps the last definition of a repeated job id, and the section scan above
        # only sees the one it kept.
        ci = _read(CI_WORKFLOW)
        self.assertEqual(len(re.findall(r"(?m)^jobs:\s*$", ci)), 1)
        for job in ("static-guard", "deploy-workflow-contract"):
            self.assertEqual(len(re.findall(rf"(?m)^  {job}:\s*$", ci)), 1, job)

    def test_the_scanner_never_misses_what_a_real_yaml_parser_sees(self):
        """Differential check against PyYAML (skipped where it is not installed: the
        required CI job runs on bare Python). For every valid-YAML sample and every protected
        section, whatever PyYAML reports the scanner must report too."""
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML not installed")

        def parser_kinds(text: str) -> set[str]:
            kinds: set[str] = set()
            events = list(yaml.parse(text))
            if any(getattr(e, "anchor", None) for e in events):
                kinds.add("anchor")
            if any(isinstance(e, yaml.AliasEvent) for e in events):
                kinds.add("alias")
            if any(isinstance(e, yaml.ScalarEvent) and e.value == "<<" and e.style is None for e in events):
                kinds.add("merge key")

            def walk(node) -> None:
                if isinstance(node, yaml.MappingNode):
                    keys = [k.value for k, _ in node.value if isinstance(k, yaml.ScalarNode)]
                    if len(keys) != len(set(keys)):
                        kinds.add("duplicate key")
                    for k, v in node.value:
                        walk(k), walk(v)
                elif isinstance(node, yaml.SequenceNode):
                    for item in node.value:
                        walk(item)

            walk(yaml.compose(text))
            return kinds

        samples = [(f"protected {label}", text) for label, text in self._protected()]
        samples += [
            (f"{kind}/{label}", text)
            for kind, cases in self.HAZARDS.items()
            for label, text in cases.items()
        ]
        samples += [(f"clean/{label}", text) for label, text in self.CLEAN.items()]
        compared = 0
        for label, text in samples:
            with self.subTest(sample=label):
                try:
                    expected = parser_kinds(text)
                except yaml.YAMLError:
                    continue  # not valid YAML; nothing to compare
                compared += 1
                ours = _hazard_kinds(text)
                # An `unsupported` report rejects the file outright (fail closed), whatever
                # else the parser sees in it.
                if "unsupported" not in ours:
                    self.assertLessEqual(expected, ours, _yaml_hazards(text))
                if label.startswith(("clean/", "protected ")):
                    self.assertEqual(_yaml_hazards(text), [])
        self.assertGreater(compared, 40, "the differential check compared too few samples")


class TestCiWiring(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ci = _without_comments(_read(CI_WORKFLOW))

    def test_this_contract_runs_in_the_required_static_guard_job(self):
        static_guard = _jobs(self.ci, strict=False)["static-guard"]
        step = _named_block(static_guard, "Wave 10.2 frontend-only deploy mode contract tests")
        self.assertEqual(
            _run_lines(step), ["python scripts/tests/test_deploy_frontend_only_mode.py -v"]
        )
        self.assertNotRegex(step, r"(?m)^\s+(?:continue-on-error|if)\s*:")

    def test_the_ci_workflow_has_no_filter_that_could_skip_static_guard(self):
        # The header of ci-verification.yml says no top-level `paths:` filter may exist: a
        # workflow skipped by path filtering never runs static-guard, so a PR that edits only
        # deploy workflows would merge without this contract having run. That rule lived only
        # in a comment.
        on_block = re.search(r"^on:\n((?:  .*\n|\n)*)", self.ci + "\n", re.MULTILINE)
        self.assertIsNotNone(on_block, "ci-verification.yml must use a block-style on:")
        self.assertEqual(re.findall(r"(?m)^  (\S+):", on_block.group(1)), ["push", "pull_request"])
        self.assertEqual(re.findall(r"(?m)^    (\S+):", on_block.group(1)), ["branches", "branches"])
        self.assertNotRegex(on_block.group(1), r"(?i)paths|tags|types|ignore")

    def test_static_guard_is_a_required_check_that_cannot_be_skipped(self):
        # This contract runs in static-guard. If static-guard left ci-required, or ci-required
        # started accepting a skip for it, the contract would silently become advisory.
        jobs = _jobs(self.ci, strict=False)
        required = jobs["ci-required"]
        self.assertRegex(required, r"(?m)^      - static-guard\s*$")
        self.assertRegex(required, r'(?m)^\s+"static-guard": "success",\s*$')
        self.assertNotRegex(jobs["static-guard"], r"(?m)^    (?:if|needs|continue-on-error):")

    def test_the_new_workflow_is_schema_checked_by_actionlint(self):
        contract = _jobs(self.ci, strict=False)["deploy-workflow-contract"]
        step = _named_block(contract, "actionlint — deploy workflows")
        self.assertIn(
            ".github/workflows/deploy-azure-frontend.yml", " ".join(_run_lines(step)).split()
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
