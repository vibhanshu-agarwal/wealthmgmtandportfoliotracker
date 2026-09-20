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

    def valid_contract(self, version: str = "0.9.0", released: bool = False) -> None:
        self.write("VERSION", f"{version}\n".encode("ascii"))
        state = "2026-09-20" if released else "Unreleased"
        self.write("CHANGELOG.md", f"# Changelog\n\n## [{version}] - {state}\n".encode())

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
        self.write(
            "CHANGELOG.md",
            b"# Changelog\n\n## [Unreleased]\n\n## [0.9.0] - Unreleased\n\n"
            b"### Added\n\n- Thing. See `## [0.8.0] - 2026-01-01` for the old format.\n\n"
            b"```markdown\n## [0.8.0] - 2026-01-01\n```\n\n"
            b"## [0.8.0] - 2026-01-01\n\n[0.9.0]: https://example.invalid/compare/v0.8.0...HEAD\n",
        )
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
