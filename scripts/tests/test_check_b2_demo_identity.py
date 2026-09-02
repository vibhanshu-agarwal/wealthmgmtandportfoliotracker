#!/usr/bin/env python3
"""Unit tests for scripts/check_b2_demo_identity.py."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
GUARD_PATH = REPO / "scripts" / "check_b2_demo_identity.py"

DEMO_UUID = "00000000-0000-0000-0000-0000000d3110"
OTHER_UUID = "00000000-0000-0000-0000-00000000dead"
DEV_UUID = "00000000-0000-0000-0000-000000000001"
E2E_UUID = "00000000-0000-0000-0000-000000000e2e"


def load_guard():
    spec = importlib.util.spec_from_file_location("check_b2_demo_identity", GUARD_PATH)
    mod = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(mod)
    return mod


def gateway_source(uuid: str = DEMO_UUID, *, extra: str = "") -> str:
    return f"""package com.wealth.gateway;

/**
 * Guards the manual demo reset. The UUID below is duplicated on purpose; this
 * guard is what keeps the copies aligned. Historic id {OTHER_UUID} is dead.
 */
public class DemoResetAuthorizationFilter {{

    // A neighbouring literal that must never be picked up: {OTHER_UUID}
    static final String DEMO_USER_ID = "{uuid}";
    private static final String RESET_PATH = "/api/portfolio/demo-reset";
{extra}
    boolean isDemo(String sub) {{
        return DEMO_USER_ID.equals(sub);
    }}
}}
"""


def initializer_source(uuid: str = DEMO_UUID, *, extra: str = "") -> str:
    return f"""package com.wealth.portfolio.seed;

public class DemoPortfolioInitializer {{

    /* Legacy id, retired: {OTHER_UUID} */
    public static final String DEMO_USER_ID = "{uuid}";
{extra}
}}
"""


def v15_source(
    demo_uuid: str = DEMO_UUID,
    *,
    credentials_uuid: str | None = None,
    demo_rows: int = 1,
    include_id_column: bool = True,
) -> str:
    credentials_uuid = credentials_uuid or demo_uuid
    columns = "id, email, name, read_only, created_at" if include_id_column else "email, name, read_only, created_at"
    values = (
        f"'{demo_uuid}', 'demo@wealthtracker.dev', 'Demo User', TRUE, now()"
        if include_id_column
        else "'demo@wealthtracker.dev', 'Demo User', TRUE, now()"
    )
    demo_block = "".join(
        f"""
INSERT INTO users ({columns})
VALUES ({values})
ON CONFLICT (id) DO NOTHING;
"""
        for _ in range(demo_rows)
    )
    return f"""-- V15: reconcile auth identities.
-- The retired demo id was {OTHER_UUID}; do not resurrect it.
-- A stray uuid in a comment must never be selected: {E2E_UUID}
{demo_block}
INSERT INTO user_credentials (user_id, email, password_hash)
VALUES ('{credentials_uuid}', 'demo@wealthtracker.dev', '$2a$12$hash')
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO users (id, email, name, read_only, created_at)
VALUES ('{DEV_UUID}', 'dev@local', 'Dev User', FALSE, now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO users (id, email, name, read_only, created_at)
VALUES ('{E2E_UUID}', 'e2e-test-user@vibhanshu-ai-portfolio.dev', 'E2E Test User', FALSE, now())
ON CONFLICT (id) DO NOTHING;

UPDATE portfolios SET user_id = '{demo_uuid}' WHERE user_id = '{DEV_UUID}';
"""


class CheckB2DemoIdentityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.guard = load_guard()

    # ── helpers ──────────────────────────────────────────────────────────────

    def write_sources(
        self,
        *,
        gateway: str | None = None,
        initializer: str | None = None,
        v15: str | None = None,
        omit: str = "",
    ) -> dict:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        paths = {}
        for key, text, name in (
            ("gateway_path", gateway if gateway is not None else gateway_source(), "Filter.java"),
            (
                "initializer_path",
                initializer if initializer is not None else initializer_source(),
                "Initializer.java",
            ),
            ("v15_path", v15 if v15 is not None else v15_source(), "V15.sql"),
        ):
            path = root / name
            if key != omit:
                path.write_text(text, encoding="utf-8")
            paths[key] = path
        return paths

    def assert_fails(self, sources: dict, *needles: str) -> str:
        with self.assertRaises(self.guard.GuardError) as ctx:
            self.guard.run_guard(**sources)
        message = str(ctx.exception)
        for needle in needles:
            self.assertIn(needle, message)
        return message

    # ── the real repository ──────────────────────────────────────────────────

    def test_repository_sources_agree(self) -> None:
        message = self.guard.run_guard()
        self.assertIn(DEMO_UUID, message)
        self.assertIn("DemoResetAuthorizationFilter", message)
        self.assertIn("DemoPortfolioInitializer", message)
        self.assertIn("V15", message)

    def test_fixture_baseline_agrees(self) -> None:
        self.guard.run_guard(**self.write_sources())

    # ── independent drift ────────────────────────────────────────────────────

    def test_gateway_drift_fails(self) -> None:
        message = self.assert_fails(
            self.write_sources(gateway=gateway_source(OTHER_UUID)), "disagree", OTHER_UUID
        )
        self.assertIn("Filter.java", message)

    def test_initializer_drift_fails(self) -> None:
        self.assert_fails(
            self.write_sources(initializer=initializer_source(OTHER_UUID)),
            "disagree",
            OTHER_UUID,
        )

    def test_v15_drift_fails(self) -> None:
        self.assert_fails(
            self.write_sources(v15=v15_source(OTHER_UUID)), "disagree", OTHER_UUID
        )

    def test_both_java_copies_drifting_together_still_fails(self) -> None:
        """The database row is the identity of record; two agreeing Java copies do not outvote it."""
        self.assert_fails(
            self.write_sources(
                gateway=gateway_source(OTHER_UUID),
                initializer=initializer_source(OTHER_UUID),
            ),
            "disagree",
        )

    # ── missing files and literals ───────────────────────────────────────────

    def test_missing_gateway_file_fails(self) -> None:
        self.assert_fails(self.write_sources(omit="gateway_path"), "missing")

    def test_missing_v15_file_fails(self) -> None:
        self.assert_fails(self.write_sources(omit="v15_path"), "missing")

    def test_missing_java_literal_fails(self) -> None:
        renamed = gateway_source().replace("DEMO_USER_ID", "DEMO_ACCOUNT_ID")
        self.assert_fails(self.write_sources(gateway=renamed), "DEMO_USER_ID")

    def test_missing_demo_users_row_fails(self) -> None:
        self.assert_fails(
            self.write_sources(v15=v15_source(demo_rows=0)), "demo@wealthtracker.dev"
        )

    def test_users_row_without_id_column_fails(self) -> None:
        self.assert_fails(
            self.write_sources(v15=v15_source(include_id_column=False)), "id"
        )

    # ── ambiguity ────────────────────────────────────────────────────────────

    def test_two_java_declarations_are_ambiguous(self) -> None:
        duplicated = gateway_source(
            extra=f'    static final String DEMO_USER_ID = "{OTHER_UUID}";\n'
        )
        self.assert_fails(self.write_sources(gateway=duplicated), "ambiguous")

    def test_two_identical_java_declarations_are_still_ambiguous(self) -> None:
        duplicated = initializer_source(
            extra=f'    public static final String DEMO_USER_ID = "{DEMO_UUID}";\n'
        )
        self.assert_fails(self.write_sources(initializer=duplicated), "ambiguous")

    def test_two_demo_users_rows_are_ambiguous(self) -> None:
        self.assert_fails(self.write_sources(v15=v15_source(demo_rows=2)), "ambiguous")

    # ── source selection ─────────────────────────────────────────────────────

    def test_credentials_row_is_not_used_as_the_identity_source(self) -> None:
        """A drifted user_credentials row must not be read in place of the users row."""
        self.guard.run_guard(
            **self.write_sources(v15=v15_source(credentials_uuid=OTHER_UUID))
        )

    def test_credentials_row_cannot_mask_a_drifted_users_row(self) -> None:
        """The mirror case: a correct-looking credentials row must not rescue a bad users row."""
        self.assert_fails(
            self.write_sources(v15=v15_source(OTHER_UUID, credentials_uuid=DEMO_UUID)),
            "disagree",
        )

    def test_dev_and_e2e_rows_are_not_selected(self) -> None:
        v15 = v15_source()
        self.assertIn(DEV_UUID, v15)
        self.assertIn(E2E_UUID, v15)
        self.guard.run_guard(**self.write_sources(v15=v15))

    def test_comment_uuids_are_never_selected(self) -> None:
        sources = self.write_sources()
        self.assertIn(OTHER_UUID, sources["gateway_path"].read_text(encoding="utf-8"))
        self.assertIn(OTHER_UUID, sources["v15_path"].read_text(encoding="utf-8"))
        self.guard.run_guard(**sources)

    def test_declaration_inside_a_java_comment_is_ignored(self) -> None:
        commented = gateway_source(
            extra=f'    /* static final String DEMO_USER_ID = "{OTHER_UUID}"; */\n'
        )
        self.guard.run_guard(**self.write_sources(gateway=commented))

    def test_users_insert_inside_a_sql_comment_is_ignored(self) -> None:
        commented = v15_source() + (
            "\n-- INSERT INTO users (id, email, name, read_only, created_at)\n"
            f"-- VALUES ('{OTHER_UUID}', 'demo@wealthtracker.dev', 'Demo User', TRUE, now());\n"
        )
        self.guard.run_guard(**self.write_sources(v15=commented))

    def test_similarly_named_table_is_not_selected(self) -> None:
        extra = (
            "\nINSERT INTO users_archive (id, email, name, read_only, created_at)\n"
            f"VALUES ('{OTHER_UUID}', 'demo@wealthtracker.dev', 'Demo User', TRUE, now());\n"
        )
        self.guard.run_guard(**self.write_sources(v15=v15_source() + extra))

    def test_non_uuid_value_fails(self) -> None:
        self.assert_fails(self.write_sources(gateway=gateway_source("not-a-uuid")), "UUID")

    # ── CLI contract ─────────────────────────────────────────────────────────

    def test_main_returns_zero_for_the_repository(self) -> None:
        self.assertEqual(self.guard.main([]), 0)

    def test_main_returns_nonzero_on_failure(self) -> None:
        sources = self.write_sources(gateway=gateway_source(OTHER_UUID))
        argv = [
            "--gateway-filter", str(sources["gateway_path"]),
            "--initializer", str(sources["initializer_path"]),
            "--v15", str(sources["v15_path"]),
        ]
        self.assertNotEqual(self.guard.main(argv), 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
