#!/usr/bin/env python3
"""Contract for the root product Semantic Version.

`VERSION` is the single product-version authority (design
`docs/superpowers/specs/2026-09-20-product-semantic-versioning-design.md`). These cases pin the
parts a reader cannot verify by inspection:

  * the exact bytes. Windows writes CRLF and editors add BOMs; `.gitattributes` keeps the
    checkout at LF, but only a byte-level check proves the file that Gradle and Docker read is
    the file the policy describes.
  * the canonical SemVer subset. `01.0.0`, `1.0`, and `1.0.0+sha` all look like versions and
    would silently become a release tag that no tooling can parse back.
  * tag mode. A release tag must equal `v<VERSION>` exactly and its changelog entry must be
    dated: `v1.0-modular-monolith` is a retained architecture marker, not a product release.

Stdlib only, matching the sibling contract tests. Loaded by path because `scripts/` is not a
package.
"""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "validate_product_version.py"


def load_validator():
    spec = importlib.util.spec_from_file_location("validate_product_version", SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class ProductVersionCoreTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator = load_validator()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, relative: str, content: bytes) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)

    # A conforming Gradle-evaluating build context: VERSION arrives after build.gradle and
    # before Gradle ever runs. `validate_repository` composes every sub-check, so the fixture
    # has to be a complete minimal repository -- the alternative, letting the sub-checks pass
    # when their files are absent, would make the whole validator fail open.
    GOOD_DOCKERFILE = (
        "FROM eclipse-temurin:21-jdk AS builder\n"
        "WORKDIR /workspace\n"
        "COPY gradlew gradlew\n"
        "COPY gradle/ gradle/\n"
        "COPY build.gradle build.gradle\n"
        "COPY VERSION VERSION\n"
        "COPY settings.gradle settings.gradle\n"
        "RUN chmod +x gradlew \\\n"
        "    && ./gradlew :svc:bootJar --no-daemon\n"
    )

    def write_dockerfiles(self, body: str | None = None) -> None:
        for relative in self.validator.GRADLE_DOCKERFILES:
            self.write(relative, (body or self.GOOD_DOCKERFILE).encode("utf-8"))

    def write_frontend(
        self,
        package_version: str = "0.9.0",
        lock_version: str | None = None,
        lock_root_version: str | None = None,
    ) -> None:
        self.write(
            "frontend/package.json",
            json.dumps(
                {"name": "wealth-mgmt-frontend", "version": package_version, "private": True}
            ).encode("utf-8"),
        )
        self.write(
            "frontend/package-lock.json",
            json.dumps(
                {
                    "name": "wealth-mgmt-frontend",
                    "version": lock_version or package_version,
                    "lockfileVersion": 3,
                    "packages": {
                        "": {
                            "name": "wealth-mgmt-frontend",
                            "version": lock_root_version or package_version,
                        }
                    },
                }
            ).encode("utf-8"),
        )

    def valid_contract(self, version: str = "0.9.0", released: bool = False) -> None:
        self.write("VERSION", f"{version}\n".encode("ascii"))
        state = "2026-09-20" if released else "Unreleased"
        self.write("CHANGELOG.md", f"# Changelog\n\n## [{version}] - {state}\n".encode())
        self.write_dockerfiles()
        self.write_frontend(version)

    def test_accepts_canonical_release(self):
        self.valid_contract()
        self.assertEqual("0.9.0", self.validator.validate_repository(self.root, "branch"))

    def test_accepts_release_candidate(self):
        self.valid_contract("1.0.0-rc.1")
        self.assertEqual(
            "1.0.0-rc.1", self.validator.validate_repository(self.root, "branch")
        )

    def test_rejects_crlf_bom_missing_lf_and_extra_line(self):
        # Each case asserts the *named* defect. A bare `assertRaises(ContractError)` would
        # still pass if every byte check collapsed into one generic message -- that is the
        # difference between measuring the defect and measuring that something went wrong.
        bad_values = (
            (b"0.9.0\r\n", "CR is forbidden"),
            (b"\xef\xbb\xbf0.9.0\n", "UTF-8 BOM is forbidden"),
            (b"0.9.0", "exactly one line ending in one LF"),
            (b"0.9.0\nextra\n", "exactly one line ending in one LF"),
            (b"", "exactly one line ending in one LF"),
            (b"0.9.0\xc2\xa0\n", "must be ASCII"),
        )
        for value, expected in bad_values:
            with self.subTest(value=value):
                self.write("VERSION", value)
                self.write("CHANGELOG.md", b"# Changelog\n\n## [0.9.0] - Unreleased\n")
                self.write_dockerfiles()
                self.write_frontend()
                with self.assertRaisesRegex(self.validator.ContractError, expected):
                    self.validator.validate_repository(self.root, "branch")

    def test_rejects_missing_version_and_missing_changelog(self):
        with self.assertRaisesRegex(self.validator.ContractError, "cannot read VERSION"):
            self.validator.validate_repository(self.root, "branch")
        self.write("VERSION", b"0.9.0\n")
        with self.assertRaisesRegex(self.validator.ContractError, "cannot read changelog"):
            self.validator.validate_repository(self.root, "branch")

    def test_rejects_non_utf8_changelog(self):
        self.write("VERSION", b"0.9.0\n")
        self.write("CHANGELOG.md", b"# Changelog\n\n## [0.9.0] - Unreleased\n\xff\n")
        with self.assertRaisesRegex(self.validator.ContractError, "must be UTF-8"):
            self.validator.validate_repository(self.root, "branch")

    def test_rejects_a_stale_duplicate_heading_for_the_same_version(self):
        # A first-match search would read the dated duplicate and call the release dated
        # while the real entry still says Unreleased. Duplicates are ambiguous either way,
        # so both orderings fail in both modes rather than one of them being guessed at.
        duplicates = (
            "# Changelog\n\n## [0.9.0] - 2026-09-20\n\n## [0.9.0] - Unreleased\n",
            "# Changelog\n\n## [0.9.0] - Unreleased\n\n## [0.9.0] - 2026-09-20\n",
        )
        self.write("VERSION", b"0.9.0\n")
        for index, text in enumerate(duplicates):
            with self.subTest(order=index):
                self.write("CHANGELOG.md", text.encode("utf-8"))
                for ref_type, ref_name in (("tag", "v0.9.0"), ("branch", "")):
                    with self.assertRaisesRegex(
                        self.validator.ContractError, "exactly one release heading"
                    ):
                        self.validator.validate_repository(self.root, ref_type, ref_name)

    def test_rejects_a_well_formed_heading_date_that_is_not_a_real_date(self):
        self.write("VERSION", b"0.9.0\n")
        self.write("CHANGELOG.md", b"# Changelog\n\n## [0.9.0] - 2026-13-45\n")
        for ref_type, ref_name in (("branch", ""), ("tag", "v0.9.0")):
            with self.subTest(ref_type=ref_type), self.assertRaisesRegex(
                self.validator.ContractError, "not a real calendar date"
            ):
                self.validator.validate_repository(self.root, ref_type, ref_name)

    def test_rejects_a_decoy_heading_when_no_real_entry_exists(self):
        # The decoy is the only thing that looks dated. Without stripping fenced blocks it
        # would be accepted as the release entry -- uniqueness alone does not catch this,
        # because there is exactly one match.
        self.write("VERSION", b"0.9.0\n")
        for index, text in enumerate(
            (
                "# Changelog\n\n```markdown\n## [0.9.0] - 2026-09-20\n```\n",
                "# Changelog\n\n<!--\n## [0.9.0] - 2026-09-20\n-->\n",
            )
        ):
            with self.subTest(decoy=index):
                self.write("CHANGELOG.md", text.encode("utf-8"))
                for ref_type, ref_name in (("tag", "v0.9.0"), ("branch", "")):
                    with self.assertRaisesRegex(
                        self.validator.ContractError, "missing release heading"
                    ):
                        self.validator.validate_repository(self.root, ref_type, ref_name)

    def test_reports_a_present_but_malformed_heading_as_malformed_not_missing(self):
        # Each of these is visibly present. Calling them "missing" would send a release
        # operator looking for a heading that is on screen in front of them.
        self.write("VERSION", b"0.9.0\n")
        malformed = (
            "# Changelog\n\n## [0.9.0] - Unreleased \n",
            "# Changelog\n\n### [0.9.0] - Unreleased\n",
            "# Changelog\n\n ## [0.9.0] - Unreleased\n",
            "# Changelog\n\n## [0.9.0] - TBD\n",
            "# Changelog\n\n```markdown\n## [0.9.0] - 2026-09-20\n```\n\n"
            "## [0.9.0] - Unreleased \n",
        )
        for index, text in enumerate(malformed):
            with self.subTest(case=index):
                self.write("CHANGELOG.md", text.encode("utf-8"))
                # Both modes: a guard that regressed to tag-only would let branch mode fall
                # back to the misleading "missing" message, and a tag-only test cannot see it.
                for ref_type, ref_name in (("tag", "v0.9.0"), ("branch", "")):
                    with self.assertRaisesRegex(
                        self.validator.ContractError, "malformed release heading"
                    ):
                        self.validator.validate_repository(self.root, ref_type, ref_name)

    def test_a_dated_decoy_beside_a_real_unreleased_entry_does_not_date_the_release(self):
        # The decoy must be ignored outright, not merely make the file ambiguous: branch
        # mode still passes on the real `Unreleased` entry, and tag mode still refuses it
        # for the right reason. This is the case the round-1 defect got wrong.
        self.write("VERSION", b"0.9.0\n")
        self.write_dockerfiles()
        self.write_frontend()
        decoys = (
            "# Changelog\n\nFormat:\n\n```markdown\n## [0.9.0] - 2026-09-20\n```\n\n"
            "## [0.9.0] - Unreleased\n",
            "# Changelog\n\n<!--\n## [0.9.0] - 2026-09-20\n-->\n\n## [0.9.0] - Unreleased\n",
        )
        for index, text in enumerate(decoys):
            with self.subTest(decoy=index):
                self.write("CHANGELOG.md", text.encode("utf-8"))
                self.assertEqual(
                    "0.9.0", self.validator.validate_repository(self.root, "branch")
                )
                with self.assertRaisesRegex(
                    self.validator.ContractError, "must be dated before tagging"
                ):
                    self.validator.validate_repository(self.root, "tag", "v0.9.0")

    def test_fence_handling_follows_commonmark_and_fails_closed(self):
        # Each case pairs a stale dated duplicate with a fence whose closer a naive regex
        # recognises later than CommonMark does. If the strip swallows the real entry, the
        # dated duplicate is left as the only match and an undated release tags green.
        self.write("VERSION", b"0.9.0\n")
        divergent = (
            # indented closer
            "# Changelog\n\n## [0.9.0] - 2026-09-20\n\n```\nx\n   ```\n\n"
            "## [0.9.0] - Unreleased\n",
            # longer closer
            "# Changelog\n\n## [0.9.0] - 2026-09-20\n\n```\nx\n````\n\n"
            "## [0.9.0] - Unreleased\n",
            # tilde fence
            "# Changelog\n\n## [0.9.0] - 2026-09-20\n\n~~~\nx\n~~~\n\n"
            "## [0.9.0] - Unreleased\n",
        )
        for index, text in enumerate(divergent):
            with self.subTest(fence=index):
                self.write("CHANGELOG.md", text.encode("utf-8"))
                with self.assertRaisesRegex(
                    self.validator.ContractError, "exactly one release heading"
                ):
                    self.validator.validate_repository(self.root, "tag", "v0.9.0")

    def test_a_decoy_inside_a_tilde_or_indented_fence_is_also_stripped(self):
        self.write("VERSION", b"0.9.0\n")
        for index, text in enumerate(
            (
                "# Changelog\n\n~~~markdown\n## [0.9.0] - 2026-09-20\n~~~\n",
                "# Changelog\n\n   ```\n## [0.9.0] - 2026-09-20\n   ```\n",
            )
        ):
            with self.subTest(fence=index):
                self.write("CHANGELOG.md", text.encode("utf-8"))
                with self.assertRaisesRegex(
                    self.validator.ContractError, "missing release heading"
                ):
                    self.validator.validate_repository(self.root, "tag", "v0.9.0")

    def test_rejects_an_unterminated_code_fence(self):
        # CommonMark runs an unterminated fence to end of document, which silently puts the
        # real entry inside a code block. Left tolerated, that is a bypass: a stale dated
        # duplicate above the fence becomes the only visible heading and tags green.
        self.write("VERSION", b"0.9.0\n")
        unterminated = (
            "# Changelog\n\n```\n## [0.9.0] - 2026-09-20\n",
            "# Changelog\n\n## [0.9.0] - 2026-09-20\n\n```a`b\nx\n```\n\n"
            "## [0.9.0] - Unreleased\n",
        )
        for index, text in enumerate(unterminated):
            with self.subTest(case=index):
                self.write("CHANGELOG.md", text.encode("utf-8"))
                for ref_type, ref_name in (("tag", "v0.9.0"), ("branch", "")):
                    with self.assertRaisesRegex(
                        self.validator.ContractError, "unterminated code fence"
                    ):
                        self.validator.validate_repository(self.root, ref_type, ref_name)

    def test_a_multi_version_changelog_still_validates(self):
        # Guards the other direction: the strictness above must not reject a normal file.
        self.write("VERSION", b"0.9.0\n")
        self.write_dockerfiles()
        self.write_frontend()
        self.write(
            "CHANGELOG.md",
            b"# Changelog\n\n## [Unreleased]\n\n## [0.9.0] - Unreleased\n\n"
            b"### Added\n\n- Thing. See `## [0.8.0] - 2026-01-01` for the old format.\n\n"
            b"```markdown\n## [0.8.0] - 2026-01-01\n```\n\n"
            b"## [0.8.0] - 2026-01-01\n\n[0.9.0]: https://example.invalid/compare/v0.8.0...HEAD\n",
        )
        self.assertEqual("0.9.0", self.validator.validate_repository(self.root, "branch"))

    def test_every_gradle_building_dockerfile_in_this_repository_carries_version(self):
        # Measured against the real tree, not a fixture: the point of the allowlist is that
        # these exact eight images evaluate root build.gradle, so VERSION must reach their
        # build contexts or Docker fails while Gradle is still configuring.
        self.assertEqual(8, len(self.validator.GRADLE_DOCKERFILES))
        self.validator.validate_gradle_docker_contexts(REPO)

    def test_rejects_a_dockerfile_that_omits_the_version_copy(self):
        self.valid_contract()
        self.write_dockerfiles(self.GOOD_DOCKERFILE.replace("COPY VERSION VERSION\n", ""))
        with self.assertRaisesRegex(self.validator.ContractError, "missing 'COPY VERSION"):
            self.validator.validate_repository(self.root, "branch")

    def test_rejects_version_copied_after_gradle_has_already_run(self):
        # Ordering is the whole contract. A COPY that lands after the build step is present
        # in the file and useless: Gradle has already configured without it. A check that
        # only grepped for the line would call this correct.
        self.valid_contract()
        late = (
            "FROM eclipse-temurin:21-jdk AS builder\n"
            "WORKDIR /workspace\n"
            "COPY build.gradle build.gradle\n"
            "RUN chmod +x gradlew \\\n"
            "    && ./gradlew :svc:bootJar --no-daemon\n"
            "COPY VERSION VERSION\n"
        )
        self.write_dockerfiles(late)
        with self.assertRaisesRegex(self.validator.ContractError, "after the .*gradlew"):
            self.validator.validate_repository(self.root, "branch")

    def test_rejects_version_copied_before_build_gradle(self):
        self.valid_contract()
        early = self.GOOD_DOCKERFILE.replace(
            "COPY build.gradle build.gradle\nCOPY VERSION VERSION\n",
            "COPY VERSION VERSION\nCOPY build.gradle build.gradle\n",
        )
        self.write_dockerfiles(early)
        with self.assertRaisesRegex(self.validator.ContractError, "precedes"):
            self.validator.validate_repository(self.root, "branch")

    def test_names_every_offending_dockerfile_not_just_the_first(self):
        self.valid_contract()
        self.write_dockerfiles(self.GOOD_DOCKERFILE.replace("COPY VERSION VERSION\n", ""))
        with self.assertRaises(self.validator.ContractError) as caught:
            self.validator.validate_repository(self.root, "branch")
        for relative in self.validator.GRADLE_DOCKERFILES:
            self.assertIn(relative, str(caught.exception))

    def test_rejects_a_missing_dockerfile_rather_than_skipping_it(self):
        self.valid_contract()
        (self.root / self.validator.GRADLE_DOCKERFILES[0]).unlink()
        with self.assertRaisesRegex(self.validator.ContractError, "cannot read"):
            self.validator.validate_repository(self.root, "branch")

    def test_rejects_a_later_stage_that_runs_gradle_without_version(self):
        # The file-level version of this check passed: stage 1 copies VERSION, so the line is
        # present somewhere. Stage 2 is a fresh base that actually builds, and configures
        # without VERSION. "Appears in the file" is not the property being claimed.
        self.valid_contract()
        self.write_dockerfiles(
            "FROM base AS builder1\n"
            "COPY build.gradle build.gradle\n"
            "COPY VERSION VERSION\n"
            "RUN ./gradlew test --no-daemon\n"
            "\n"
            "FROM base AS builder2\n"
            "COPY build.gradle build.gradle\n"
            "COPY settings.gradle settings.gradle\n"
            "RUN ./gradlew :svc:bootJar --no-daemon\n"
        )
        with self.assertRaisesRegex(self.validator.ContractError, r"stage 2.*missing"):
            self.validator.validate_repository(self.root, "branch")

    def test_rejects_a_commented_out_version_copy(self):
        self.valid_contract()
        self.write_dockerfiles(
            self.GOOD_DOCKERFILE.replace(
                "COPY VERSION VERSION\n", "# COPY VERSION VERSION\n"
            )
        )
        with self.assertRaisesRegex(self.validator.ContractError, "missing 'COPY VERSION"):
            self.validator.validate_repository(self.root, "branch")

    def test_rejects_a_dockerfile_with_no_gradlew_run_at_all(self):
        self.valid_contract()
        self.write_dockerfiles("FROM base\nCOPY build.gradle build.gradle\nCOPY VERSION VERSION\n")
        with self.assertRaisesRegex(self.validator.ContractError, "no RUN block invoking"):
            self.validator.validate_repository(self.root, "branch")

    def test_rejects_a_stage_that_runs_gradle_without_copying_build_gradle(self):
        self.valid_contract()
        self.write_dockerfiles(
            "FROM base AS builder\n"
            "COPY --from=other /workspace /workspace\n"
            "RUN ./gradlew :svc:bootJar --no-daemon\n"
        )
        with self.assertRaisesRegex(
            self.validator.ContractError, "without 'COPY build.gradle"
        ):
            self.validator.validate_repository(self.root, "branch")

    def test_rejects_a_non_utf8_dockerfile(self):
        self.valid_contract()
        self.write(self.validator.GRADLE_DOCKERFILES[0], b"FROM base\n# \xff\n")
        with self.assertRaisesRegex(self.validator.ContractError, "must be UTF-8"):
            self.validator.validate_repository(self.root, "branch")

    def test_a_non_building_stage_without_version_is_not_flagged(self):
        # Guards the other direction: only stages that actually configure Gradle are subject
        # to the rule, so a runtime stage is not required to carry VERSION.
        self.valid_contract()
        self.write_dockerfiles(
            self.GOOD_DOCKERFILE + "\nFROM base AS runtime\nCOPY --from=builder /x /x\n"
        )
        self.assertEqual("0.9.0", self.validator.validate_repository(self.root, "branch"))

    def test_rejects_an_indented_stage_that_skips_version(self):
        # Docker accepts indented instructions. Anchoring at column 0 made the whole stage
        # invisible to the validator while Docker still ran Gradle in it.
        self.valid_contract()
        self.write_dockerfiles(
            self.GOOD_DOCKERFILE
            + "\n  FROM base AS builder2\n"
            "  COPY build.gradle build.gradle\n"
            "  RUN ./gradlew :svc:bootJar --no-daemon\n"
        )
        with self.assertRaisesRegex(self.validator.ContractError, r"stage 2.*missing"):
            self.validator.validate_repository(self.root, "branch")

    def test_instruction_keyword_case_is_free_but_argument_case_is_not(self):
        self.valid_contract()
        # Lowercase keywords are valid Docker and must validate.
        self.write_dockerfiles(
            self.GOOD_DOCKERFILE.replace("COPY", "copy")
            .replace("FROM", "from")
            .replace("RUN", "run")
        )
        self.assertEqual("0.9.0", self.validator.validate_repository(self.root, "branch"))
        # A miscased ARGUMENT names a different file and must not satisfy the contract.
        self.write_dockerfiles(
            self.GOOD_DOCKERFILE.replace("COPY VERSION VERSION", "COPY version version")
        )
        with self.assertRaisesRegex(self.validator.ContractError, "missing 'COPY VERSION"):
            self.validator.validate_repository(self.root, "branch")

    def test_a_standalone_chmod_is_not_mistaken_for_the_build_step(self):
        # `chmod +x gradlew` mentions gradlew and configures nothing. Treated as the build
        # step it moves the ordering boundary and rejects a correct Dockerfile.
        self.valid_contract()
        self.write_dockerfiles(
            "FROM base AS builder\n"
            "COPY gradlew gradlew\n"
            "RUN chmod +x gradlew\n"
            "COPY build.gradle build.gradle\n"
            "COPY VERSION VERSION\n"
            "RUN ./gradlew :svc:bootJar --no-daemon\n"
        )
        self.assertEqual("0.9.0", self.validator.validate_repository(self.root, "branch"))

    def test_recognises_other_gradlew_invocation_spellings(self):
        self.valid_contract()
        for invocation in (
            "RUN bash gradlew :svc:bootJar\n",
            "RUN sh -c './gradlew :svc:bootJar'\n",
            "RUN /workspace/gradlew :svc:bootJar\n",
        ):
            with self.subTest(invocation=invocation.strip()):
                self.write_dockerfiles(
                    "FROM base AS builder\nCOPY build.gradle build.gradle\n" + invocation
                )
                with self.assertRaisesRegex(
                    self.validator.ContractError, "missing 'COPY VERSION"
                ):
                    self.validator.validate_repository(self.root, "branch")

    def test_a_commented_gradlew_line_inside_a_run_continuation_is_not_the_build_step(self):
        # Docker strips comment lines even inside a backslash continuation. If the validator
        # does not, this earlier RUN reads as the Gradle step, the ordering boundary moves
        # above the COPYs, and a correct Dockerfile is rejected. A leading `# RUN ...` line
        # would NOT exercise this -- the RUN anchor already skips it -- so the comment has to
        # sit inside the continuation to measure anything.
        self.valid_contract()
        self.write_dockerfiles(
            "FROM base AS builder\n"
            "COPY gradlew gradlew\n"
            "RUN chmod +x gradlew \\\n"
            "    # && ./gradlew warmup \\\n"
            "    && echo prepared\n"
            "COPY build.gradle build.gradle\n"
            "COPY VERSION VERSION\n"
            "RUN ./gradlew :svc:bootJar --no-daemon\n"
        )
        self.assertEqual("0.9.0", self.validator.validate_repository(self.root, "branch"))

    def test_rejects_build_gradle_copied_after_the_gradle_run(self):
        self.valid_contract()
        self.write_dockerfiles(
            "FROM base AS builder\n"
            "COPY VERSION VERSION\n"
            "RUN ./gradlew :svc:bootJar --no-daemon\n"
            "COPY build.gradle build.gradle\n"
        )
        with self.assertRaisesRegex(
            self.validator.ContractError, "without 'COPY build.gradle"
        ):
            self.validator.validate_repository(self.root, "branch")

    def test_this_repository_frontend_mirrors_the_root_version(self):
        # Derived from VERSION, never pinned to a literal: a permanent consistency test that
        # hard-coded 0.9.0 would start failing the first time someone bumps the product
        # correctly, and would teach the next person to edit the test instead of the mirror.
        version = self.validator.read_product_version(REPO)
        self.validator.validate_frontend_versions(REPO, version)

    def test_rejects_package_json_drift(self):
        self.valid_contract()
        self.write_frontend(package_version="0.1.0")
        with self.assertRaisesRegex(self.validator.ContractError, "package.json"):
            self.validator.validate_repository(self.root, "branch")

    def test_rejects_lockfile_top_level_drift(self):
        self.valid_contract()
        self.write_frontend(lock_version="0.1.0")
        with self.assertRaisesRegex(
            self.validator.ContractError, "top-level version"
        ):
            self.validator.validate_repository(self.root, "branch")

    def test_rejects_lockfile_root_package_drift(self):
        self.valid_contract()
        self.write_frontend(lock_root_version="0.1.0")
        with self.assertRaisesRegex(self.validator.ContractError, "packages"):
            self.validator.validate_repository(self.root, "branch")

    def test_names_the_exact_drifted_field(self):
        # Three fields can drift independently; "frontend version drift" would leave the
        # reader to find which one.
        self.valid_contract()
        for setter, expected in (
            ({"package_version": "0.1.0"}, "package.json"),
            ({"lock_version": "0.1.0"}, "top-level version"),
            ({"lock_root_version": "0.1.0"}, "packages"),
        ):
            with self.subTest(field=expected):
                self.write_frontend(**setter)
                with self.assertRaises(self.validator.ContractError) as caught:
                    self.validator.validate_repository(self.root, "branch")
                message = str(caught.exception)
                self.assertIn(expected, message)
                self.assertIn("0.1.0", message)
                self.assertIn("0.9.0", message)

    def test_rejects_malformed_or_incomplete_frontend_json(self):
        self.valid_contract()
        cases = (
            ("frontend/package.json", b"{not json", "is not valid JSON"),
            ("frontend/package-lock.json", b"{not json", "is not valid JSON"),
            (
                "frontend/package-lock.json",
                b'{"name": "x", "version": "0.9.0", "packages": {}}',
                "root package record",
            ),
            ("frontend/package.json", b'{"name": "x"}', "missing a version field"),
            ("frontend/package.json", b"[]", "expected a JSON object"),
            (
                "frontend/package-lock.json",
                b'{"name": "x", "packages": {"": {"version": "0.9.0"}}}',
                "missing a top-level version field",
            ),
            (
                "frontend/package-lock.json",
                b'{"name": "x", "version": "0.9.0", "packages": {"": null}}',
                "has no version field",
            ),
            (
                "frontend/package-lock.json",
                b'{"name": "x", "version": "0.9.0", "packages": {"": {"name": "x"}}}',
                "has no version field",
            ),
        )
        for relative, body, expected in cases:
            with self.subTest(relative=relative, body=body):
                self.write_frontend()
                self.write(relative, body)
                with self.assertRaisesRegex(self.validator.ContractError, expected):
                    self.validator.validate_repository(self.root, "branch")

    def test_rejects_a_missing_frontend_package_rather_than_skipping_it(self):
        self.valid_contract()
        (self.root / "frontend" / "package.json").unlink()
        with self.assertRaisesRegex(self.validator.ContractError, "cannot read"):
            self.validator.validate_repository(self.root, "branch")

    def test_version_comparison_is_exact_not_trimmed(self):
        # A stray space is drift, not a formatting nicety: it means something wrote the field
        # by hand rather than through npm.
        self.valid_contract()
        self.write_frontend(package_version="0.9.0 ")
        with self.assertRaisesRegex(self.validator.ContractError, "package.json"):
            self.validator.validate_repository(self.root, "branch")

    def test_reports_every_drifted_field_in_one_run(self):
        # The motivating case is a merge that resolved part of a bump. Being told about one
        # field per run turns one fix into three round trips.
        self.valid_contract()
        self.write_frontend(
            package_version="0.1.0", lock_version="0.2.0", lock_root_version="0.3.0"
        )
        with self.assertRaises(self.validator.ContractError) as caught:
            self.validator.validate_repository(self.root, "branch")
        message = str(caught.exception)
        for fragment in ("0.1.0", "0.2.0", "0.3.0"):
            self.assertIn(fragment, message)

    def test_frontend_messages_are_repo_relative(self):
        # CI and a developer's absolute --root must produce the same message.
        self.valid_contract()
        self.write_frontend(package_version="0.1.0")
        with self.assertRaises(self.validator.ContractError) as caught:
            self.validator.validate_repository(self.root, "branch")
        message = str(caught.exception)
        self.assertIn("frontend/package.json", message)
        self.assertNotIn(str(self.root), message)

    def test_does_not_inspect_tooling_package_versions(self):
        # infrastructure/ and scripts/ are tools with their own lifecycles. An allowlist,
        # not a repo-wide scan: a scan would rewrite or reject versions the product does not
        # own.
        self.valid_contract()
        self.write("infrastructure/package.json", b'{"name": "infra", "version": "0.1.0"}')
        self.write("scripts/package.json", b'{"name": "scripts", "version": "1.0.0"}')
        self.assertEqual("0.9.0", self.validator.validate_repository(self.root, "branch"))

    def test_accepts_a_real_dated_entry_in_branch_mode(self):
        self.valid_contract(released=True)
        self.assertEqual("0.9.0", self.validator.validate_repository(self.root, "branch"))

    def test_rejects_noncanonical_versions_and_build_metadata(self):
        for version in ("01.0.0", "1.00.0", "1.0", "1.0.0+sha", "1.0.0-01"):
            with self.subTest(version=version):
                self.valid_contract(version)
                with self.assertRaises(self.validator.ContractError):
                    self.validator.validate_repository(self.root, "branch")

    def test_tag_requires_exact_version_and_released_changelog(self):
        self.valid_contract(released=True)
        self.assertEqual(
            "0.9.0",
            self.validator.validate_repository(self.root, "tag", "v0.9.0"),
        )
        for tag in ("v0.9.1", "0.9.0", "v1.0-modular-monolith"):
            with self.subTest(tag=tag), self.assertRaises(self.validator.ContractError):
                self.validator.validate_repository(self.root, "tag", tag)

    def test_tag_rejects_unreleased_changelog(self):
        self.valid_contract(released=False)
        with self.assertRaises(self.validator.ContractError):
            self.validator.validate_repository(self.root, "tag", "v0.9.0")

    def test_rejects_empty_or_unknown_ref_type_and_missing_tag_name(self):
        self.valid_contract(released=True)
        for ref_type in ("", "release", "TAG"):
            with self.subTest(ref_type=ref_type), self.assertRaises(
                self.validator.ContractError
            ):
                self.validator.validate_repository(self.root, ref_type)
        with self.assertRaises(self.validator.ContractError):
            self.validator.validate_repository(self.root, "tag")


if __name__ == "__main__":
    unittest.main()
