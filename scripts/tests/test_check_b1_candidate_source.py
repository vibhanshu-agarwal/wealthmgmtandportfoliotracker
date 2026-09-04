#!/usr/bin/env python3
"""Regression corpus for the GC.5 source-governance guard + writer-inventory re-check (contract v2).

Every test here corresponds to a probe that DEFEATED contract v1, grouped by the review finding it
closes, so a future regression names the finding it reopens rather than just a function.

Run:  python -B -m unittest discover -s scripts/tests -p test_check_b1_candidate_source.py -v
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import unittest
import uuid
from pathlib import Path
from tempfile import TemporaryDirectory

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))
sys.path.insert(0, str(REPO / "scripts" / "tests"))

import check_b1_candidate_source as gov  # noqa: E402
import b1_candidate_evidence as ev  # noqa: E402
import verify_b1_candidate_image as viv  # noqa: E402
from b1_candidate_evidence import EvidenceError  # noqa: E402
from test_b1_candidate_evidence import write_report  # noqa: E402


def _docker_daemon_available() -> bool:
    if not shutil.which("docker"):
        return False
    try:
        subprocess.run(["docker", "info"], capture_output=True, check=True, timeout=15)
        return True
    except Exception:
        return False


DOCKER_AVAILABLE = _docker_daemon_available()
NEEDS_DOCKER = unittest.skipUnless(
    DOCKER_AVAILABLE, "requires a running Docker daemon: evidence binding re-extracts /app.jar from "
    "the real image, so a positive control needs a real image")


class RealArtifacts:
    """A REAL staged JAR and a REAL local image carrying it as /app.jar, built `FROM scratch` (no
    network). Evidence validation re-reads the JAR bytes and re-extracts /app.jar from the image, so
    a positive control cannot be a hand-written digest string -- it needs artifacts that exist."""

    def __init__(self, content: bytes | None = None):
        self.tmp = TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.jar_path = self.dir / "portfolio-service.jar"
        self.jar_path.write_bytes(content or ("jar-" + uuid.uuid4().hex).encode())
        self.jar_sha = ev.sha256_file(self.jar_path)  # producers emit BARE hex
        (self.dir / "Dockerfile").write_text(
            'FROM scratch\nCOPY portfolio-service.jar /app.jar\nCMD ["/app.jar"]\n', encoding="utf-8")
        self.tag = "b1-guard-test-artifact:" + uuid.uuid4().hex[:12]
        self.image_id = viv.docker_build(self.dir / "Dockerfile", self.dir, self.tag,
                                         iidfile=self.dir / "iid.txt")
        self.platform = viv.docker_image_field(self.image_id, "{{.Os}}/{{.Architecture}}")

    def close(self) -> None:
        subprocess.run(["docker", "rmi", "-f", self.image_id], capture_output=True)
        self.tmp.cleanup()


_SHARED_ARTIFACTS: list[RealArtifacts] = []


def shared_artifacts() -> RealArtifacts:
    """One real JAR+image for the whole module (built lazily, removed at module teardown)."""
    if not _SHARED_ARTIFACTS:
        _SHARED_ARTIFACTS.append(RealArtifacts())
        unittest.addModuleCleanup(lambda: _SHARED_ARTIFACTS.pop().close())
    return _SHARED_ARTIFACTS[0]


def producer_shaped_task_a(cut: str, base: str, mode: str, jar_path: Path, jar_sha: str) -> dict:
    """Exactly the shape `b1_candidate_evidence.run_evidence` writes (bare-hex stage hash, absolute
    staged path, explicit empty problems), so schema tests exercise the accepted producer contract."""
    return {"run": {"marker_epoch": 1.0, "head_sha": cut, "mode": mode, "b1_base_sha": base},
            "manifest": [], "per_task_totals": {}, "b1_added_or_modified_test_files": [],
            "stage": {"bootjar_path": str(jar_path), "staged_path": str(jar_path), "sha256": jar_sha},
            "graph_verification_status": "PASS", "problems": [], "candidate_ready": False,
            "candidate_ready_blocked_by": []}


def producer_shaped_task_b(cut: str, jar_path: Path, jar_sha: str, image_id: str, platform: str) -> dict:
    """Exactly the shape `verify_b1_candidate_image.verify_candidate_image` returns."""
    return {"label": "LOCAL_PREPARATION", "provenance": "verified", "recipe": "Dockerfile",
            "local_image_id": image_id, "platform": platform, "requested_platform": platform,
            "runtime_base_ref": "scratch", "runtime_base_digest": "scratch",
            "task_a_evidence_head_sha": cut, "staged_jar_path": str(jar_path),
            "staged_jar_sha256": jar_sha, "extracted_jar_sha256": jar_sha, "hashes_equal": True,
            "registry_manifest_digest": None, "registry_manifest_platform": None}


def run_git(repo: Path, *args: str) -> str:
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True)
    if result.returncode != 0:
        raise AssertionError("git " + " ".join(args) + " failed: "
                             + result.stderr.decode("utf-8", "replace"))
    return result.stdout.decode("utf-8", "replace")


class TempGitRepo:
    """A real git repo with a base commit. Real git, never a mocked subprocess: the entire class of
    defects this suite covers lives in git's own output encoding, so mocking would test nothing."""

    def __enter__(self) -> Path:
        self._tmp = TemporaryDirectory()
        repo = Path(self._tmp.name)
        run_git(repo, "init", "-q")
        run_git(repo, "config", "user.email", "test@example.com")
        run_git(repo, "config", "user.name", "Test")
        run_git(repo, "config", "core.autocrlf", "false")
        (repo / "README.md").write_text("base\n", encoding="utf-8")
        run_git(repo, "add", ".")
        run_git(repo, "commit", "-q", "-m", "base")
        self.base_sha = run_git(repo, "rev-parse", "HEAD").strip()
        return repo

    def __exit__(self, *exc) -> None:
        self._tmp.cleanup()


def write_and_commit(repo: Path, rel_path: str, content: str, message: str = "change") -> None:
    path = repo / rel_path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    run_git(repo, "add", "-A")
    run_git(repo, "commit", "-q", "-m", message)


def root_sha(repo: Path) -> str:
    return run_git(repo, "rev-list", "--max-parents=0", "HEAD").strip()


def full_policy(base_sha: str, **gc5_overrides) -> dict:
    gc5 = {
        "always_allowed_globs": ["**/src/test/**", "docs/**", "**/*Test.java", "**/*IT.java"],
        "forbidden_paths": [
            {"non_goal": "10.2", "glob": "api-gateway/src/main/java/com/wealth/gateway/presence/**"},
            {"non_goal": "10.1", "glob": "frontend/src/**"},
        ],
        "forbidden_content_symbols": [{"non_goal": "10.2", "symbol": "/api/presence/demo"}],
        "content_scan_excluded_globs": ["docs/**", "**/*.md"],
        "per_holding_freshness_structural_check": {
            "types": [],
            "discovery_globs": ["portfolio-service/src/main/**/*.java"],
            "type_name_pattern": "Holding",
        },
        "reviewed_exceptions": [],
    }
    gc5.update(gc5_overrides)
    return {
        "b1_base_commit": {"sha": base_sha},
        "gc5": gc5,
        "writer_inventory": {
            "production_writers": [],
            "flagged_writers_outside_holding_replacement_service": [],
            "classified_non_writers": [],
            "excluded_from_recheck": [],
        },
        "unresolved": [],
    }


def operations_of(src: str) -> dict[str, dict]:
    """subject_id -> {facts, fingerprint, coverage, statement} for one compilation unit, without git.

    Mirrors writer_inventory's per-op resolution through the SAME shared function, so a fingerprint
    computed here is byte-identical to the one the tool computes."""
    tokens = gov.lex_java(src)
    contexts = gov.java_contexts(tokens)
    mapped = gov.mapped_collection_names(tokens, contexts)
    ops, _finals, by_method = gov.extract_operations("T.java", tokens, contexts, mapped)
    heads = gov.java_method_heads(tokens)
    types = gov.declared_types(tokens, contexts)
    entity_index = gov.build_entity_index({"T.java": (tokens, contexts)})
    store_by_type = dict(gov._DEFAULT_STORE_BY_TYPE)
    out = {}
    for op in ops:
        start_key = op.enclosing_type + "::" + op.enclosing_method
        receiver_type, store, entity, mapping_digest = gov.resolve_receiver(
            op, types, entity_index, store_by_type)
        facts = gov.operation_code_facts(op, tokens, by_method, heads.get(start_key, []),
                                         receiver_type, mapping_digest)
        out[op.subject_id] = {
            "facts": facts,
            "fingerprint": gov.canonical_fingerprint(facts),
            "coverage": op.coverage,
            "statement": op.statement,
        }
    return out


def fp_of(src: str, needle: str) -> str:
    ops = operations_of(src)
    for sid, v in ops.items():
        if needle in sid:
            return v["fingerprint"]
    raise AssertionError("no operation matching " + needle + " in " + str(list(ops)))


DIAGNOSTIC = '''
package p;
class Diag {
    static final String DML_PROBE_SQL = "DELETE FROM portfolios WHERE FALSE";
    void runDmlProbe(String boundary) {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(DML_PROBE_SQL);
        }
    }
}
'''

CAS_WRITER = '''
package p;
class Svc {
    @Transactional
    public Result replace(String userId, long expectedVersion) {
        return replaceAbsent(userId, expectedVersion);
    }
    private Result replaceAbsent(String userId, long expectedVersion) {
        if (expectedVersion != 0L) { throw new PortfolioVersionConflictException(0L); }
        int updated = jdbcTemplate.update("UPDATE portfolios SET version = version + 1 WHERE id = ? AND version = ?", portfolio.getId(), expectedVersion);
        if (updated != 1) { throw PortfolioVersionConflictException.unresolvedForPortfolio(portfolio.getId()); }
        applyChildren(portfolio, desired);
    }
}
'''


# ======================================================================================
# F1 -- one immutable snapshot
# ======================================================================================


class SnapshotIdentityTests(unittest.TestCase):
    def test_writer_deleted_from_working_tree_is_still_seen_at_the_cut(self):
        """The v1 probe: a writer present in the selected commit vanished from the inventory when
        its WORKING-TREE file was deleted, and the run still returned overall PASS."""
        with TempGitRepo() as repo:
            write_and_commit(repo, "svc/src/main/java/W.java",
                             "class W { void m() { repo.saveAndFlush(x); } }")
            cut = gov.resolve_commit(repo, "HEAD")
            (repo / "svc/src/main/java/W.java").unlink()  # working tree only, never committed

            tree = gov.tree_blobs(repo, cut)
            self.assertIn("svc/src/main/java/W.java", tree)
            findings, _ = gov.writer_inventory(tree, gov.BlobReader(repo), full_policy(cut))
            self.assertTrue(any("saveAndFlush" in f.subject_id for f in findings),
                            [f.subject_id for f in findings])

    def test_missing_base_object_is_blocking_and_never_substituted(self):
        with TempGitRepo() as repo:
            with self.assertRaises(EvidenceError) as ctx:
                gov.assert_commit_present(repo, "0" * 39 + "1", "B1-base commit")
            self.assertIn("shallow", str(ctx.exception))
            self.assertIn("will not substitute", str(ctx.exception))

    def test_annotated_tag_resolves_to_the_commit_not_the_tag_object(self):
        with TempGitRepo() as repo:
            run_git(repo, "tag", "-a", "v1", "-m", "tagged")
            head = run_git(repo, "rev-parse", "HEAD").strip()
            self.assertEqual(gov.resolve_commit(repo, "v1"), head)


# ======================================================================================
# F2 -- enumeration reaches forms v1 could not express
# ======================================================================================


class EnumerationTests(unittest.TestCase):
    def test_save_and_flush_is_detected(self):
        ops = operations_of("class W { void m() { portfolioRepository.saveAndFlush(new P(u)); } }")
        self.assertTrue(any("saveAndFlush" in s for s in ops), list(ops))

    def test_sql_migration_delete_is_detected(self):
        findings = gov.extract_sql_subjects("V9.sql", "DELETE FROM asset_holdings WHERE id = 1;")
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].evidence["target_tables"], ["asset_holdings"])

    def test_dollar_quoted_function_body_is_not_shredded_on_semicolons(self):
        sql = ("CREATE OR REPLACE FUNCTION f() RETURNS void AS $$\nBEGIN\n"
               "  DELETE FROM asset_holdings;\n  UPDATE portfolios SET x = 1;\nEND;\n$$ LANGUAGE plpgsql;\n")
        self.assertEqual(len(gov.split_sql_statements(sql)), 1)

    def test_persistent_function_is_distinguished_from_one_time_dml(self):
        f = gov.extract_sql_subjects(
            "V17.sql", "CREATE OR REPLACE FUNCTION repair() RETURNS void AS $$ BEGIN END; $$;")[0]
        self.assertTrue(f.evidence["persistent_reusable"])

    def test_dynamic_sql_in_a_function_body_is_unsupported_not_passed(self):
        f = gov.extract_sql_subjects(
            "V17.sql", "CREATE FUNCTION f() AS $$ BEGIN EXECUTE format('DELETE FROM %I', t); END; $$;")[0]
        self.assertEqual(f.kind, gov.UNSUPPORTED)

    def test_quoted_table_identifier_is_resolved(self):
        self.assertEqual(gov.sql_facts('INSERT INTO "ba_user" ("id") VALUES (1)')["target_tables"],
                         ["ba_user"])

    def test_do_update_set_is_not_reported_as_a_table(self):
        facts = gov.sql_facts("INSERT INTO market_prices (t) VALUES (1) ON CONFLICT (t) DO UPDATE SET p = 1")
        self.assertNotIn("set", facts["target_tables"])

    def test_callback_wrapper_is_not_a_false_coverage_gap(self):
        """`transactionTemplate.execute(status -> ...)` executes a callback, not a statement. Any
        real mutation inside it is still caught on its own line."""
        ops = operations_of("class A { void m() { transactionTemplate.execute(status -> { return 1; }); } }")
        self.assertEqual(ops, {})


# ======================================================================================
# F3 -- dispositions bind to an operation, its dependencies and its context
# ======================================================================================


class FingerprintBindingTests(unittest.TestCase):
    def test_where_false_to_where_true_invalidates_though_the_call_line_is_unchanged(self):
        """THE probe. The disposition rests entirely on the predicate, which lives at the constant
        declaration; the write's own line never changes."""
        before = list(operations_of(DIAGNOSTIC).items())[0]
        after = list(operations_of(DIAGNOSTIC.replace("WHERE FALSE", "WHERE TRUE")).items())[0]
        self.assertEqual(before[0], after[0], "subject identity must be stable")
        self.assertEqual(before[1]["statement"]["predicate"], "WHERE FALSE")
        self.assertEqual(after[1]["statement"]["predicate"], "WHERE TRUE")
        self.assertNotEqual(before[1]["fingerprint"], after[1]["fingerprint"])

    def test_where_one_equals_zero_also_invalidates(self):
        self.assertNotEqual(fp_of(DIAGNOSTIC, "executeUpdate"),
                            fp_of(DIAGNOSTIC.replace("WHERE FALSE", "WHERE 1=0"), "executeUpdate"))

    def test_reindenting_the_constant_declaration_preserves_the_fingerprint(self):
        after = DIAGNOSTIC.replace("    static final String DML_PROBE_SQL",
                                   "        static final String DML_PROBE_SQL")
        self.assertEqual(fp_of(DIAGNOSTIC, "executeUpdate"), fp_of(after, "executeUpdate"))

    def test_inserting_a_comment_preserves_the_fingerprint(self):
        after = DIAGNOSTIC.replace("    void runDmlProbe", "    // added note\n    void runDmlProbe")
        self.assertEqual(fp_of(DIAGNOSTIC, "executeUpdate"), fp_of(after, "executeUpdate"))

    def test_throw_replaced_by_log_and_continue_invalidates(self):
        after = CAS_WRITER.replace(
            "throw PortfolioVersionConflictException.unresolvedForPortfolio(portfolio.getId());",
            'log.warn("cas miss");')
        self.assertNotEqual(fp_of(CAS_WRITER, "replaceAbsent"), fp_of(after, "replaceAbsent"))

    def test_changed_jdbc_binding_invalidates(self):
        after = CAS_WRITER.replace("portfolio.getId(), expectedVersion",
                                   "portfolio.getId(), portfolio.getVersion()")
        self.assertNotEqual(fp_of(CAS_WRITER, "replaceAbsent"), fp_of(after, "replaceAbsent"))

    def test_changed_annotation_on_the_writes_own_method_invalidates(self):
        # Tier 0 folds the ENCLOSING method's head annotations, so changing @Transactional on the
        # method that contains the write is a genuine Tier 0 change. (A caller-only annotation is
        # NOT Tier 0 in v3 -- that is the envelope's job; see TransitiveDependencyTests.)
        src = ('class S {\n  @Transactional\n'
               '  void w() { jdbcTemplate.update("DELETE FROM portfolios WHERE id = ?"); }\n}')
        after = src.replace("@Transactional", "@Transactional(propagation = Propagation.REQUIRES_NEW)")
        self.assertNotEqual(fp_of(src, "jdbcTemplate.update"), fp_of(after, "jdbcTemplate.update"))

    def test_removing_the_row_count_assertion_invalidates(self):
        after = CAS_WRITER.replace(
            "        if (updated != 1) { throw PortfolioVersionConflictException.unresolvedForPortfolio(portfolio.getId()); }\n", "")
        self.assertNotEqual(fp_of(CAS_WRITER, "replaceAbsent"), fp_of(after, "replaceAbsent"))

    def test_reordering_children_before_the_cas_invalidates(self):
        after = CAS_WRITER.replace("        int updated = jdbcTemplate.update(",
                                   "        applyChildren(portfolio, desired);\n        int updated = jdbcTemplate.update(")
        self.assertNotEqual(fp_of(CAS_WRITER, "replaceAbsent"), fp_of(after, "replaceAbsent"))

    def test_removing_the_admission_guard_invalidates(self):
        after = CAS_WRITER.replace(
            "        if (expectedVersion != 0L) { throw new PortfolioVersionConflictException(0L); }\n", "")
        self.assertNotEqual(fp_of(CAS_WRITER, "replaceAbsent"), fp_of(after, "replaceAbsent"))

    def test_three_writes_in_one_file_are_three_independent_subjects(self):
        """The signup acceptance covers insertPortfolio only; a file-keyed scheme blessed all three
        and any fourth method added later."""
        src = ('class R {\n'
               '  void insertUser(UUID i) { String sql = "INSERT INTO users (id) VALUES (:id)"; jdbc.update(sql, p); }\n'
               '  void insertCredential(UUID i) { String sql = "INSERT INTO user_credentials (u) " + "VALUES (:u)"; jdbc.update(sql, p); }\n'
               '  void insertPortfolio(UUID i) { String sql = "INSERT INTO portfolios (id) VALUES (:id)"; jdbc.update(sql, p); }\n'
               '}')
        ops = operations_of(src)
        self.assertEqual(len(ops), 3, list(ops))
        self.assertEqual(sorted(v["statement"]["target_tables"][0] for v in ops.values()),
                         ["portfolios", "user_credentials", "users"])

    def test_concatenated_literal_local_folds_to_one_statement(self):
        ops = operations_of('class R { void m() { String sql = "INSERT INTO a (x) " + "VALUES (1)"; jdbc.update(sql, p); } }')
        self.assertEqual(list(ops.values())[0]["statement"]["sql"], "INSERT INTO a (x) VALUES (1)")

    def test_retargeting_a_table_invalidates(self):
        a = 'class R { void m() { String sql = "INSERT INTO portfolios (id) VALUES (1)"; jdbc.update(sql, p); } }'
        b = 'class R { void m() { String sql = "INSERT INTO asset_holdings (id) VALUES (1)"; jdbc.update(sql, p); } }'
        self.assertNotEqual(fp_of(a, "jdbc.update"), fp_of(b, "jdbc.update"))

    def test_reassigned_local_is_unsupported_not_partially_resolved(self):
        src = 'class R { void m() { String sql = "INSERT INTO a VALUES (1)"; sql = other; jdbc.update(sql, p); } }'
        self.assertEqual(list(operations_of(src).values())[0]["coverage"], gov.UNSUPPORTED)

    def test_concatenated_variable_is_unsupported(self):
        src = 'class R { void m() { jdbc.update("DELETE FROM " + table, p); } }'
        self.assertEqual(list(operations_of(src).values())[0]["coverage"], gov.UNSUPPORTED)

    def test_review_metadata_is_not_part_of_the_hashed_pre_image(self):
        """Approving a subject must not invalidate the fingerprint being approved, and inserting a
        comment must not move it via a line number."""
        facts = list(operations_of(DIAGNOSTIC).values())[0]["facts"]
        blob = json.dumps(facts)
        for forbidden in ("status", "rationale", "reviewer", "line_hint", "blob"):
            self.assertNotIn(forbidden, blob)


class DispositionStatusTests(unittest.TestCase):
    SRC = 'class R { void m() { String sql = "INSERT INTO portfolios (id) VALUES (1)"; jdbc.update(sql, p); } }'

    def _subject(self, repo, cut, obligation="writer-inventory"):
        findings, _ = gov.writer_inventory(gov.tree_blobs(repo, cut), gov.BlobReader(repo), full_policy(cut))
        return [f for f in findings if f.obligation == obligation][0]

    @staticmethod
    def _writer_disposition(d, status, fingerprint=None):
        """A schema-complete disposition for the JDBC writer subject, bound to the deployable's own
        envelope record, so the only property under test is the status / fingerprint."""
        res = d.run()
        f = subject_of(res, "jdbcTemplate.update")
        record = d.envelope_record(d.head)
        return f, [make_disposition(f, record, status=status, fingerprint=fingerprint)]

    def test_pending_disposition_blocks(self):
        d = Deployable(JDBC_WRITER)
        try:
            _f, disp = self._writer_disposition(d, "PROPOSED_SAFE_PENDING_REVIEW")
            res = d.run(d.policy(d.head, dispositions=disp))
            f = subject_of(res, "jdbcTemplate.update")
            self.assertEqual(f["kind"], gov.UNREVIEWED)
            self.assertIn("PROPOSED_SAFE_PENDING_REVIEW", f["detail"])
        finally:
            d.close()

    def test_accepted_disposition_with_matching_fingerprint_clears(self):
        d = Deployable(JDBC_WRITER)
        try:
            _f, disp = self._writer_disposition(d, gov.ACCEPTED)
            res = d.run(d.policy(d.head, dispositions=disp))
            self.assertIsNone(subject_of(res, "jdbcTemplate.update"))
            self.assertEqual(res["overall_status"], gov.PASS)
        finally:
            d.close()

    def test_stale_fingerprint_reopens_an_accepted_disposition(self):
        d = Deployable(JDBC_WRITER)
        try:
            _f, disp = self._writer_disposition(d, gov.ACCEPTED, fingerprint="sha256:" + "0" * 64)
            res = d.run(d.policy(d.head, dispositions=disp))
            f = subject_of(res, "jdbcTemplate.update")
            self.assertEqual(f["kind"], gov.UNREVIEWED)
            self.assertIn("fingerprint", f["detail"])
        finally:
            d.close()

    def test_human_acceptance_cannot_upgrade_unsupported_coverage(self):
        with TempGitRepo() as repo:
            write_and_commit(repo, "a/src/main/java/R.java",
                             'class R { void m() { jdbc.update("DELETE FROM " + t, p); } }')
            cut = gov.resolve_commit(repo, "HEAD")
            subject = self._subject(repo, cut, "writer-coverage")
            policy = full_policy(cut)
            policy["writer_inventory"]["production_writers"] = [{
                "path": subject.path, "subject_id": subject.subject_id,
                "code_fingerprint": "sha256:whatever", "status": gov.ACCEPTED}]
            still = [f for f in gov.writer_inventory(gov.tree_blobs(repo, cut), gov.BlobReader(repo), policy)[0]
                     if f.obligation == "writer-coverage"]
            self.assertEqual(len(still), 1)
            self.assertEqual(still[0].kind, gov.UNSUPPORTED)

    def test_policy_subject_absent_at_cut_is_missing_subject(self):
        with TempGitRepo() as repo:
            write_and_commit(repo, "a/src/main/java/R.java", "class R { }")
            cut = gov.resolve_commit(repo, "HEAD")
            policy = full_policy(cut)
            policy["writer_inventory"]["production_writers"] = [{
                "path": "a/src/main/java/R.java", "subject_id": "op:R::gone/0::x.save#0",
                "code_fingerprint": "sha256:x", "status": gov.ACCEPTED}]
            findings, _ = gov.writer_inventory(gov.tree_blobs(repo, cut), gov.BlobReader(repo), policy)
            self.assertTrue(any(f.kind == gov.MISSING_SUBJECT for f in findings))

    def test_unresolved_r3_always_blocks(self):
        with TempGitRepo() as repo:
            write_and_commit(repo, "a/src/main/java/R.java", "class R { }")
            cut = gov.resolve_commit(repo, "HEAD")
            policy = full_policy(cut)
            policy["unresolved"] = [{"id": "R3", "summary": "s", "status": "unresolved"}]
            findings, _ = gov.writer_inventory(gov.tree_blobs(repo, cut), gov.BlobReader(repo), policy)
            self.assertTrue(any(f.obligation == "unresolved" and "R3" in f.subject_id for f in findings))


# ======================================================================================
# F4 -- path handling, with no patch text anywhere
# ======================================================================================


class PathEncodingTests(unittest.TestCase):
    def test_unicode_path_is_decoded_and_matched(self):
        with TempGitRepo() as repo:
            write_and_commit(repo, "frontend/src/café.tsx", "const u = '/api/presence/demo';\n")
            cut = gov.resolve_commit(repo, "HEAD")
            entries = gov.changed_entries(repo, root_sha(repo), cut)
            paths = [e.path for e in entries]
            self.assertIn("frontend/src/café.tsx", paths)
            self.assertFalse(any(p.startswith('"') for p in paths), paths)
            hit = [f for f in gov.path_guard(entries, full_policy(cut)["gc5"])
                   if f.path == "frontend/src/café.tsx"]
            self.assertEqual(hit[0].kind, gov.CONFIRMED_MATCH)

    def test_unicode_path_content_is_scanned(self):
        with TempGitRepo() as repo:
            write_and_commit(repo, "svc/café.lua", "redis.call('SET', '/api/presence/demo')\n")
            cut = gov.resolve_commit(repo, "HEAD")
            findings = gov.content_guard(gov.changed_entries(repo, root_sha(repo), cut),
                                         gov.BlobReader(repo), full_policy(cut)["gc5"])
            self.assertTrue(any(f.kind == gov.CONFIRMED_MATCH for f in findings), findings)

    def test_space_bearing_path_content_is_scanned(self):
        """LIVE bypass in v1: git appends a TAB after an unquoted space-bearing path in the `+++`
        header, so the computed suffix became `.tsx\\t` and the whole file was skipped. Four such
        paths are tracked in the real repository today."""
        with TempGitRepo() as repo:
            write_and_commit(repo, "plans/429 502 rca.tsx", "const u = '/api/presence/demo';\n")
            cut = gov.resolve_commit(repo, "HEAD")
            findings = gov.content_guard(gov.changed_entries(repo, root_sha(repo), cut),
                                         gov.BlobReader(repo), full_policy(cut)["gc5"])
            self.assertTrue(any(f.path == "plans/429 502 rca.tsx" for f in findings), findings)

    def test_content_beginning_with_double_plus_cannot_spoof_a_file_header(self):
        """v1 parsed patch text; a source line starting `++ b/<path>` became a real `+++ ` line and
        re-attributed every following added line to a file the commit never touched."""
        with TempGitRepo() as repo:
            write_and_commit(repo, "notes/mis.txt", "++ b/frontend/src/clean.tsx\n/api/presence/demo\n")
            write_and_commit(repo, "frontend/src/clean.tsx", "const ok = 1;\n")
            cut = gov.resolve_commit(repo, "HEAD")
            findings = gov.content_guard(gov.changed_entries(repo, root_sha(repo), cut),
                                         gov.BlobReader(repo), full_policy(cut)["gc5"])
            self.assertEqual([f for f in findings if f.path == "frontend/src/clean.tsx"], [],
                             "symbol must not be attributed to an untouched file")
            self.assertTrue(any(f.path == "notes/mis.txt" for f in findings),
                            "and must still be reported against the file that really carries it")

    def test_leading_and_trailing_whitespace_in_a_path_is_preserved(self):
        with TempGitRepo() as repo:
            write_and_commit(repo, "frontend/src/ pad .tsx", "x\n")
            cut = gov.resolve_commit(repo, "HEAD")
            self.assertIn("frontend/src/ pad .tsx",
                          [e.path for e in gov.changed_entries(repo, root_sha(repo), cut)])

    def test_both_blob_ids_are_captured_for_a_deletion(self):
        with TempGitRepo() as repo:
            write_and_commit(repo, "frontend/src/gone.tsx", "x\n")
            mid = gov.resolve_commit(repo, "HEAD")
            (repo / "frontend/src/gone.tsx").unlink()
            run_git(repo, "add", "-A")
            run_git(repo, "commit", "-q", "-m", "delete")
            cut = gov.resolve_commit(repo, "HEAD")
            entry = [e for e in gov.changed_entries(repo, mid, cut) if e.path.endswith("gone.tsx")][0]
            self.assertTrue(entry.is_deletion)
            self.assertNotEqual(entry.src_blob, gov.ZERO_OID)
            self.assertEqual(entry.dst_blob, gov.ZERO_OID)


# ======================================================================================
# F5 -- path policy: precedence, globstar, exception binding
# ======================================================================================


class PathPolicyTests(unittest.TestCase):
    def test_production_class_named_like_a_test_is_not_exempted(self):
        entry = gov.ChangeEntry(
            "api-gateway/src/main/java/com/wealth/gateway/presence/PresenceSelfTest.java",
            "M", "a" * 40, "b" * 40)
        self.assertEqual(gov.path_guard([entry], full_policy("x")["gc5"])[0].kind, gov.CONFIRMED_MATCH)

    def test_globstar_does_not_cross_a_separator(self):
        self.assertTrue(gov.glob_match("a/b/FooTest.java", "**/*Test.java"))
        self.assertFalse(gov.glob_match("a/b/FooTest.java", "*Test.java"))

    def test_glob_matching_is_case_sensitive_on_every_platform(self):
        self.assertFalse(gov.glob_match("Frontend/src/a.tsx", "frontend/src/**"))

    def test_unknown_production_path_is_unreviewed_not_silently_counted(self):
        entry = gov.ChangeEntry("portfolio-service/src/main/java/New.java", "A", gov.ZERO_OID, "b" * 40)
        self.assertEqual(gov.path_guard([entry], full_policy("x")["gc5"])[0].kind, gov.UNREVIEWED)

    def test_exception_requires_both_blob_ids_and_change_kind(self):
        gc5 = full_policy("x")["gc5"]
        gc5["reviewed_exceptions"] = [{
            "path": "frontend/src/a.tsx", "obligation": "path-governance", "change_kind": "M",
            "src_blob": "a" * 40, "dst_blob": "b" * 40, "reviewed_commit": "c" * 40, "reviewer": "codex", "status": gov.ACCEPTED}]
        self.assertEqual(gov.path_guard([gov.ChangeEntry("frontend/src/a.tsx", "M", "a" * 40, "b" * 40)], gc5), [])

    def test_exception_lapses_when_the_file_is_edited_again(self):
        gc5 = full_policy("x")["gc5"]
        gc5["reviewed_exceptions"] = [{
            "path": "frontend/src/a.tsx", "obligation": "path-governance", "change_kind": "M",
            "src_blob": "a" * 40, "dst_blob": "b" * 40, "reviewed_commit": "c" * 40, "reviewer": "codex", "status": gov.ACCEPTED}]
        later = gov.ChangeEntry("frontend/src/a.tsx", "M", "a" * 40, "d" * 40)
        self.assertEqual(gov.path_guard([later], gc5)[0].kind, gov.CONFIRMED_MATCH)

    def test_destination_only_exception_cannot_describe_a_deletion(self):
        gc5 = full_policy("x")["gc5"]
        gc5["reviewed_exceptions"] = [{
            "path": "frontend/src/a.tsx", "obligation": "path-governance", "dst_blob": gov.ZERO_OID,
            "reviewed_commit": "c" * 40, "reviewer": "codex", "status": gov.ACCEPTED}]
        deletion = gov.ChangeEntry("frontend/src/a.tsx", "D", "a" * 40, gov.ZERO_OID)
        kinds = {(f.obligation, f.kind) for f in gov.path_guard([deletion], gc5)}
        # Two INDEPENDENT findings: the exception is unusable, AND the path is still forbidden.
        self.assertIn(("path-governance", gov.CONFIRMED_MATCH), kinds)
        self.assertIn(("exception-provenance", gov.UNREVIEWED), kinds)


# ======================================================================================
# F6 -- content coverage is deny-by-default; per-holding state is discovered
# ======================================================================================


class ContentCoverageTests(unittest.TestCase):
    def _scan(self, repo, rel, body, gc5=None):
        write_and_commit(repo, rel, body)
        cut = gov.resolve_commit(repo, "HEAD")
        return gov.content_guard(gov.changed_entries(repo, root_sha(repo), cut),
                                 gov.BlobReader(repo), gc5 or full_policy(cut)["gc5"])

    def test_lua_runtime_file_is_scanned(self):
        with TempGitRepo() as repo:
            findings = self._scan(repo, "svc/presence.lua", "redis.call('SET', '/api/presence/demo', 1)\n")
            self.assertTrue(any(f.path == "svc/presence.lua" for f in findings))

    def test_yaml_runtime_config_is_scanned(self):
        with TempGitRepo() as repo:
            findings = self._scan(repo, "svc/application.yml", "url: /api/presence/demo\n")
            self.assertTrue(any(f.path == "svc/application.yml" for f in findings))

    def test_documentation_stays_excluded_by_an_explicit_justified_denylist(self):
        with TempGitRepo() as repo:
            self.assertEqual(self._scan(repo, "docs/spec.md", "mentions /api/presence/demo\n"), [])

    def test_field_added_to_an_entity_class_is_detected(self):
        """The reviewer probe. v1 could only read `record X(...)` headers, so a new field on the
        AssetHolding CLASS was undetectable by construction."""
        with TempGitRepo() as repo:
            write_and_commit(repo, "portfolio-service/src/main/java/AssetHolding.java",
                             "@Entity class AssetHolding { @Id private UUID id;"
                             " private BigDecimal quantity; private String freshnessState; }")
            cut = gov.resolve_commit(repo, "HEAD")
            gc5 = full_policy(cut)["gc5"]
            gc5["per_holding_freshness_structural_check"]["types"] = [{
                "file": "portfolio-service/src/main/java/AssetHolding.java",
                "record_name": "AssetHolding", "baseline_fields": ["id", "quantity"]}]
            findings = gov.per_holding_state(gov.tree_blobs(repo, cut), gov.BlobReader(repo), gc5)
            self.assertEqual(findings[0].kind, gov.UNREVIEWED)
            self.assertIn("freshnessState", findings[0].evidence["extra"])

    def test_unlisted_per_holding_type_fails_closed(self):
        with TempGitRepo() as repo:
            write_and_commit(repo, "portfolio-service/src/main/java/H.java", "record HoldingNew(String a) {}")
            cut = gov.resolve_commit(repo, "HEAD")
            findings = gov.per_holding_state(gov.tree_blobs(repo, cut), gov.BlobReader(repo),
                                             full_policy(cut)["gc5"])
            self.assertTrue(any(f.subject_id == "type:HoldingNew" for f in findings))

    def test_baseline_type_missing_at_the_cut_is_missing_subject(self):
        with TempGitRepo() as repo:
            write_and_commit(repo, "portfolio-service/src/main/java/Other.java", "class Other {}")
            cut = gov.resolve_commit(repo, "HEAD")
            gc5 = full_policy(cut)["gc5"]
            gc5["per_holding_freshness_structural_check"]["types"] = [{
                "file": "portfolio-service/src/main/java/Gone.java",
                "record_name": "HoldingGone", "baseline_fields": ["a"]}]
            findings = gov.per_holding_state(gov.tree_blobs(repo, cut), gov.BlobReader(repo), gc5)
            self.assertEqual(findings[0].kind, gov.MISSING_SUBJECT)


# ======================================================================================
# Lexer -- rejects rather than partially fingerprints
# ======================================================================================


class LexerTests(unittest.TestCase):
    def test_unterminated_string_is_rejected(self):
        with self.assertRaises(gov.LexError):
            gov.lex_java('class A { String s = "oops; }')

    def test_unterminated_block_comment_is_rejected(self):
        with self.assertRaises(gov.LexError):
            gov.lex_java("class A { /* oops }")

    def test_unterminated_text_block_is_rejected(self):
        with self.assertRaises(gov.LexError):
            gov.lex_java('class A { String s = """ oops; }')

    def test_unicode_escape_is_preprocessed_before_lexing(self):
        self.assertEqual(gov.preprocess_unicode_escapes("// x \\u00a7 y"), "// x § y")

    def test_escaped_backslash_before_u_is_not_an_escape(self):
        self.assertEqual(gov.preprocess_unicode_escapes("\\\\u0041"), "\\\\u0041")

    def test_malformed_unicode_escape_is_rejected(self):
        with self.assertRaises(gov.LexError):
            gov.preprocess_unicode_escapes("class A { int \\u00zz; }")

    def test_braces_inside_literals_do_not_mis_nest_scopes(self):
        ops = operations_of('class A { void m() { String s = "}{"; repo.save(x); } }')
        self.assertTrue(any("A::m/0" in s for s in ops), list(ops))

    def test_class_literal_is_not_a_type_declaration(self):
        contexts = gov.java_contexts(gov.lex_java(
            "class A { void m() { em.unwrap(Session.class).doWork(c -> { st.executeUpdate(SQL); }); } }"))
        self.assertTrue(all(")" not in t for t, _ in contexts), {t for t, _ in contexts})

    def test_try_with_resources_is_not_a_method(self):
        contexts = gov.java_contexts(gov.lex_java(
            "class A { void m() { try (Statement s = c.createStatement()) { s.executeUpdate(Q); } } }"))
        self.assertNotIn("try/1", {m for _, m in contexts})

    def test_text_block_dedent_matches_jls_incidental_whitespace(self):
        tb = [t for t in gov.lex_java('class A { String s = """\n    UPDATE t\n     SET x = 1\n    """; }')
              if t.kind == "text_block"][0]
        self.assertEqual(gov.literal_value(tb), "UPDATE t\n SET x = 1\n")


# ======================================================================================
# Findings model + evaluator/target separation
# ======================================================================================


class FindingsModelTests(unittest.TestCase):
    def test_findings_are_keyed_by_path_subject_and_obligation(self):
        a = gov.Finding("p", "op:x#0", "writer-inventory", gov.UNREVIEWED, "")
        b = gov.Finding("p", "op:x#0", "writer-coverage", gov.UNSUPPORTED, "")
        self.assertNotEqual(a.key(), b.key())

    def test_candidate_mode_refuses_an_uncommitted_analyzer(self):
        with TempGitRepo() as repo:
            write_and_commit(repo, "a.txt", "x\n")
            cut = gov.resolve_commit(repo, "HEAD")
            with self.assertRaises(EvidenceError) as ctx:
                gov.run_all(repo, full_policy(cut), None, "HEAD", gov.CANDIDATE, None)
            self.assertIn("CANDIDATE mode requires", str(ctx.exception))

    def test_local_preparation_records_evaluator_identity_separately_from_target(self):
        with TempGitRepo() as repo:
            write_and_commit(repo, "a.txt", "x\n")
            cut = gov.resolve_commit(repo, "HEAD")
            result = gov.run_all(repo, full_policy(cut), None, "HEAD", gov.LOCAL_PREPARATION, None)
            self.assertEqual(result["evaluator"]["mode"], gov.LOCAL_PREPARATION)
            self.assertTrue(result["evaluator"]["analyzer_sha256"].startswith("sha256:"))
            self.assertEqual(result["target"]["cut_sha"], cut)
            self.assertNotIn("analyzer_sha256", result["target"])


class RealRepoSmokeTests(unittest.TestCase):
    """Against the actual repository, so the checks run on real source, not only fixtures."""

    def test_real_repo_run_is_blocked_and_reproduces_the_stored_counts(self):
        policy_path = REPO / "scripts" / "b1-candidate-policy.json"
        result = gov.run_all(REPO, json.loads(policy_path.read_text(encoding="utf-8")), None,
                             "HEAD", gov.LOCAL_PREPARATION, policy_path)
        self.assertEqual(result["overall_status"], "BLOCKED")
        by_ob = result["summary"]["by_obligation"]
        self.assertEqual(result["target"]["changed_paths"], 451)
        self.assertEqual(by_ob["content-governance"][gov.CONFIRMED_MATCH], 139)
        self.assertEqual(by_ob["path-governance"][gov.CONFIRMED_MATCH], 84)
        self.assertEqual(by_ob["path-governance"][gov.UNREVIEWED], 131)

    def test_every_tracked_java_file_lexes(self):
        cut = gov.resolve_commit(REPO, "HEAD")
        reader = gov.BlobReader(REPO)
        failures = []
        for path, blob in gov.tree_blobs(REPO, cut).items():
            if not path.endswith(".java"):
                continue
            text = reader.text(blob)
            if text is None:
                continue
            try:
                gov.lex_java(text)
            except gov.LexError as exc:
                failures.append((path, str(exc)))
        self.assertEqual(failures, [])


# ======================================================================================
# Round-2 probes: mutations that still returned PASS, and clearances that were too weak
# ======================================================================================


VALID_EXC = {"change_kind": "M", "src_blob": "a" * 40, "dst_blob": "b" * 40,
             "reviewed_commit": "c" * 40, "reviewer": "codex", "status": gov.ACCEPTED}


class UnenumeratedMutationTests(unittest.TestCase):
    """P1: a committed Python DELETE, a mapped-collection clear() and a managed-entity setter all
    produced zero findings. COLLECTION_MUTATORS was declared but never consulted."""

    def test_python_delete_is_reported(self):
        findings = gov.script_dml_subjects(
            "scripts/wipe.py",
            'cur.execute("DELETE FROM asset_holdings WHERE portfolio_id = %s", (pid,))\n')
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].kind, gov.UNSUPPORTED)

    def test_script_detector_ignores_non_sql_update_calls(self):
        for line in ('h.createHash("sha256").update(buf)',
                     "ACTIVE_ACTIONS = {'create', 'update'}",
                     "[ValidateSet('search', 'install', 'update')]"):
            self.assertEqual(gov.script_dml_subjects("a.js", line), [], line)

    def test_mapped_collection_clear_is_a_subject(self):
        src = ('@Entity class Portfolio {\n'
               '  @OneToMany(mappedBy = "portfolio", cascade = CascadeType.ALL, orphanRemoval = true)\n'
               '  private Set<AssetHolding> holdings = new HashSet<>();\n'
               '  void wipe() { holdings.clear(); }\n}')
        ops = operations_of(src)
        self.assertTrue(any(".clear#" in s for s in ops), list(ops))

    def test_unmapped_local_collection_clear_is_not_a_subject(self):
        src = "class A { void m() { java.util.Map<String,String> local = new HashMap<>(); local.clear(); } }"
        self.assertEqual(operations_of(src), {})

    def test_mapped_collection_is_recognised_across_compilation_units(self):
        """The @OneToMany is on the entity; the cascade-triggering mutation is in a service."""
        with TempGitRepo() as repo:
            write_and_commit(repo, "p/src/main/java/Portfolio.java",
                             '@Entity class Portfolio {\n'
                             '  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)\n'
                             '  private Set<AssetHolding> holdings;\n'
                             '  Set<AssetHolding> getHoldings() { return holdings; }\n}')
            write_and_commit(repo, "p/src/main/java/Svc.java",
                             "class Svc { void apply(Portfolio p) { p.getHoldings().clear(); } }")
            cut = gov.resolve_commit(repo, "HEAD")
            findings, _ = gov.writer_inventory(gov.tree_blobs(repo, cut), gov.BlobReader(repo),
                                               full_policy(cut))
            self.assertTrue(any("Svc::apply" in f.subject_id and ".clear#" in f.subject_id
                                for f in findings), [f.subject_id for f in findings])

    def test_entity_manager_remove_survives_the_collection_gate(self):
        """remove() is BOTH a JPA write and a collection mutator. Gating it on a mapped receiver
        silently deleted every .remove( subject, a JPA entity delete included."""
        ops = operations_of("class A { void m() { entityManager.remove(portfolio); } }")
        self.assertTrue(any(".remove#" in s for s in ops), list(ops))

    def test_managed_entity_setter_is_a_subject(self):
        with TempGitRepo() as repo:
            write_and_commit(repo, "p/src/main/java/AssetHolding.java",
                             "@Entity class AssetHolding { private BigDecimal quantity;"
                             " public void setQuantity(BigDecimal q) { this.quantity = q; } }")
            cut = gov.resolve_commit(repo, "HEAD")
            findings, _ = gov.writer_inventory(gov.tree_blobs(repo, cut), gov.BlobReader(repo),
                                               full_policy(cut))
            self.assertTrue(any(f.subject_id.startswith("set:") and "setQuantity" in f.subject_id
                                for f in findings), [f.subject_id for f in findings])


class TransitiveDependencyTests(unittest.TestCase):
    """Correction 6: the transaction boundary two hops up and the gate helper one hop down are NOT
    Tier 0 inputs. They are caught conservatively by the ENVELOPE (Tier 1) -- any change to any
    file in the deployable re-opens every disposition on it -- so the fingerprint stays a pure,
    location-free property of the operation's own method while nothing safe is missed."""

    def test_tier0_does_not_change_for_a_caller_only_annotation(self):
        # @Transactional is on `replace`; the write is in `replaceAbsent`. In v3 this is NOT a Tier 0
        # change (that is the envelope's job, proven by AddendumFixtureTests.test_E3).
        self.assertEqual(fp_of(CAS_WRITER, "replaceAbsent"),
                         fp_of(CAS_WRITER.replace("    @Transactional\n", ""), "replaceAbsent"))

    def test_same_method_annotation_change_does_change_tier0(self):
        # An annotation on the write's OWN method is inside the enclosing-method digest, so it is a
        # genuine Tier 0 change.
        src = ('class S {\n  @Transactional\n'
               '  void w() { jdbcTemplate.update("DELETE FROM portfolios WHERE id = ?"); }\n}')
        after = src.replace("@Transactional", "@Transactional(propagation = REQUIRES_NEW)")
        self.assertNotEqual(fp_of(src, "jdbcTemplate.update"), fp_of(after, "jdbcTemplate.update"))

    def test_same_file_helper_body_change_is_caught_by_the_envelope(self):
        d = Deployable("class Diag {\n"
                       "  private Statement st;\n"
                       "  static final String SQL = \"DELETE FROM portfolios WHERE FALSE\";\n"
                       "  boolean enabled() { return sysGate(); }\n"
                       "  boolean sysGate() { return false; }\n"
                       "  void probe() { if (!enabled()) { return; } st.executeUpdate(SQL); }\n}\n")
        try:
            res = d.run()
            f = subject_of(res, "st.executeUpdate")
            record = d.envelope_record(d.head)
            disp = [make_disposition(f, record)]
            d.write("svc/src/main/java/Writer.java",
                    d.repo.joinpath("svc/src/main/java/Writer.java").read_text(encoding="utf-8")
                    .replace("return false;", "return true;"))
            d.commit("gate helper body")
            # Carry the R-era record: the change makes it stale and the disposition re-opens.
            res2 = d.run(d.policy(d.head, dispositions=disp, envelopes=[record]))
            self.assertEqual(subject_of(res2, "st.executeUpdate")["kind"], gov.ENVELOPE_CHANGED)
        finally:
            d.close()

    def test_annotations_are_captured_from_the_declaration_head(self):
        heads = gov.java_method_heads(gov.lex_java(CAS_WRITER))
        key = [k for k in heads if k.startswith("Svc::replace/")][0]
        self.assertEqual(gov.annotations_from_head(heads[key]), ["@ Transactional"])


class CandidateIdentityTests(unittest.TestCase):
    """P3: CANDIDATE mode checked that a FILENAME existed in the tree, not what was executing."""

    def test_committed_and_executing_bytes_are_compared(self):
        import hashlib
        with TempGitRepo() as repo:
            (repo / "scripts").mkdir(parents=True, exist_ok=True)
            (repo / "scripts" / "check_b1_candidate_source.py").write_text("# committed\n", encoding="utf-8")
            run_git(repo, "add", "-A")
            run_git(repo, "commit", "-q", "-m", "tooling")
            cut = gov.resolve_commit(repo, "HEAD")
            tree, reader = gov.tree_blobs(repo, cut), gov.BlobReader(repo)
            (repo / "scripts" / "check_b1_candidate_source.py").write_text("# MODIFIED\n", encoding="utf-8")
            committed = hashlib.sha256(reader.raw(tree["scripts/check_b1_candidate_source.py"])).hexdigest()
            executing = hashlib.sha256((repo / "scripts" / "check_b1_candidate_source.py").read_bytes()).hexdigest()
            self.assertNotEqual(committed, executing,
                                "a modified analyzer must not hash equal to its committed version")

    def test_candidate_mode_rejects_untracked_tooling(self):
        with TempGitRepo() as repo:
            write_and_commit(repo, "a.txt", "x\n")
            cut = gov.resolve_commit(repo, "HEAD")
            with self.assertRaises(EvidenceError) as ctx:
                gov.run_all(repo, full_policy(cut), None, "HEAD", gov.CANDIDATE, None)
            self.assertIn("reviewed versions committed in the cut", str(ctx.exception))


class SqlLiteralIntegrityTests(unittest.TestCase):
    """P4: whitespace collapsing reached inside SQL literals, so two different payloads shared one
    digest and an accepted disposition survived the change."""

    def test_literal_interior_whitespace_is_preserved(self):
        self.assertNotEqual(gov.sql_facts("INSERT INTO t VALUES ('a  b')")["sql"],
                            gov.sql_facts("INSERT INTO t VALUES ('a b')")["sql"])

    def test_code_whitespace_is_still_collapsed(self):
        self.assertEqual(gov.normalize_sql("INSERT   INTO   t"), "INSERT INTO t")

    def test_dollar_quoted_body_is_preserved_verbatim(self):
        self.assertIn("BEGIN  x", gov.normalize_sql("DO $$ BEGIN  x := 1; END; $$"))

    def test_sql_subjects_carry_a_fingerprint_that_changes_with_the_literal(self):
        a = gov.extract_sql_subjects("V.sql", "INSERT INTO t VALUES ('a  b');")[0]
        b = gov.extract_sql_subjects("V.sql", "INSERT INTO t VALUES ('a b');")[0]
        self.assertNotEqual(a.evidence["code_fingerprint"], b.evidence["code_fingerprint"])

    def test_sql_clearance_requires_a_matching_fingerprint(self):
        with TempGitRepo() as repo:
            write_and_commit(repo, "db/V1.sql", "DELETE FROM asset_holdings WHERE id = 1;")
            cut = gov.resolve_commit(repo, "HEAD")
            tree, reader = gov.tree_blobs(repo, cut), gov.BlobReader(repo)
            subject = [f for f in gov.writer_inventory(tree, reader, full_policy(cut))[0]
                       if f.subject_id.startswith("sql:")][0]
            policy = full_policy(cut)
            policy["writer_inventory"]["sql_writers"] = [{
                "path": subject.path, "subject_id": subject.subject_id,
                "code_fingerprint": "sha256:" + "0" * 64, "status": gov.ACCEPTED}]
            still = [f for f in gov.writer_inventory(tree, reader, policy)[0]
                     if f.subject_id.startswith("sql:")]
            self.assertEqual(len(still), 1, "status alone must not clear a SQL subject")


class ExceptionProvenanceTests(unittest.TestCase):
    """P5/P6: provenance fields were checked for presence only, and unknown paths had no
    disposition route at all."""

    def _gc5(self, exc):
        gc5 = full_policy("x")["gc5"]
        gc5["reviewed_exceptions"] = [dict(exc, path="frontend/src/a.tsx",
                                           obligation="path-governance")]
        return gc5

    def test_placeholder_reviewed_commit_is_rejected(self):
        gc5 = self._gc5(dict(VALID_EXC, reviewed_commit="not-a-commit"))
        entry = gov.ChangeEntry("frontend/src/a.tsx", "M", "a" * 40, "b" * 40)
        kinds = {(f.obligation, f.kind) for f in gov.path_guard([entry], gc5)}
        self.assertIn(("path-governance", gov.CONFIRMED_MATCH), kinds)
        self.assertIn(("exception-provenance", gov.UNREVIEWED), kinds)

    def test_empty_reviewer_is_rejected(self):
        gc5 = self._gc5(dict(VALID_EXC, reviewer="   "))
        entry = gov.ChangeEntry("frontend/src/a.tsx", "M", "a" * 40, "b" * 40)
        self.assertTrue(any(f.kind == gov.CONFIRMED_MATCH for f in gov.path_guard([entry], gc5)))

    def test_reviewed_commit_must_exist_when_a_repo_is_supplied(self):
        with TempGitRepo() as repo:
            gc5 = self._gc5(VALID_EXC)  # well-formed, but not a real commit in this repo
            entry = gov.ChangeEntry("frontend/src/a.tsx", "M", "a" * 40, "b" * 40)
            problems = [f for f in gov.path_guard([entry], gc5, repo)
                        if f.obligation == "exception-provenance"]
            self.assertEqual(len(problems), 1)
            self.assertIn("does not exist", problems[0].detail)

    def test_unknown_path_can_be_cleared_by_an_exact_exception(self):
        gc5 = full_policy("x")["gc5"]
        gc5["reviewed_exceptions"] = [dict(VALID_EXC, path="portfolio-service/src/main/java/New.java",
                                           obligation="path-governance")]
        entry = gov.ChangeEntry("portfolio-service/src/main/java/New.java", "M", "a" * 40, "b" * 40)
        self.assertEqual(gov.path_guard([entry], gc5), [])

    def test_clearing_an_unknown_path_does_not_clear_its_neighbours(self):
        gc5 = full_policy("x")["gc5"]
        gc5["reviewed_exceptions"] = [dict(VALID_EXC, path="portfolio-service/src/main/java/New.java",
                                           obligation="path-governance")]
        sibling = gov.ChangeEntry("portfolio-service/src/main/java/Other.java", "M", "a" * 40, "b" * 40)
        self.assertEqual(gov.path_guard([sibling], gc5)[0].kind, gov.UNREVIEWED)


# ======================================================================================
# Contract v3: addendum fixtures E1-E17 and the seven normative regressions
# ======================================================================================


def sha256_of(path: Path) -> str:
    import hashlib
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


class Deployable:
    """A minimal but COMPLETE deployable: build graph, entity, migration, evidence artifacts.

    Complete matters. A fixture that omits the build files cannot exercise the envelope at all,
    which is precisely how `config/seed-tickers.json` stayed invisible in the proposed roots."""

    def __init__(self, subject_src: str | None = None, extra: dict | None = None,
                 cascade: bool = False):
        self.tmp = TemporaryDirectory()
        self.repo = Path(self.tmp.name)
        r = self.repo
        run_git(r, "init", "-q")
        run_git(r, "config", "user.email", "t@e.com")
        run_git(r, "config", "user.name", "T")
        run_git(r, "config", "core.autocrlf", "false")
        self.write("settings.gradle", "include 'svc'\ninclude 'common'\n")
        self.write("build.gradle", "// root\n")
        self.write("svc/build.gradle",
                   "processResources { from(rootProject.file('config/seed.json')) { into 'catalog' } }\n"
                   "dependencies { implementation project(':common') }\n")
        self.write("common/build.gradle", "// common\n")
        self.write("common/src/main/java/Util.java", "class Util { }\n")
        self.write("config/seed.json", '{"tickers": []}\n')
        # Default migration is a CLEAN baseline: plain CREATE TABLEs produce no SQL subject, so a
        # fixture that adds nothing else runs green. cascade=True adds the FK ON DELETE CASCADE
        # (a persistent mechanism -> one SQL subject) only where a test needs the indirect target.
        holdings_fk = ("portfolio_id uuid REFERENCES portfolios(id) ON DELETE CASCADE"
                       if cascade else "portfolio_id uuid")
        self.write("svc/src/main/resources/db/migration/V1__init.sql",
                   "CREATE TABLE portfolios (id uuid PRIMARY KEY);\n"
                   "CREATE TABLE asset_holdings (id uuid, " + holdings_fk + ");\n")
        self.write("svc/src/main/java/Portfolio.java",
                   "@Entity\n@Table(name = \"portfolios\")\nclass Portfolio {\n"
                   "  @OptimisticLock(excluded = true)\n"
                   "  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)\n"
                   "  private Set<AssetHolding> holdings;\n}\n")
        if subject_src is not None:
            self.write("svc/src/main/java/Writer.java", subject_src)
        for rel, body in (extra or {}).items():
            self.write(rel, body)
        self.write("evidence/taskA.json", '{"task":"A"}\n')
        self.write("evidence/taskB.json", '{"task":"B"}\n')
        self.commit("base")

    def write(self, rel: str, body: str) -> None:
        p = self.repo / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body, encoding="utf-8")

    def commit(self, msg: str) -> str:
        run_git(self.repo, "add", "-A")
        run_git(self.repo, "commit", "-q", "-m", msg)
        self.head = run_git(self.repo, "rev-parse", "HEAD").strip()
        return self.head

    def close(self) -> None:
        self.tmp.cleanup()

    # -- policy construction -------------------------------------------------------------
    def envelope(self, cut: str | None = None) -> dict:
        cut = cut or self.head
        tree = gov.tree_blobs(self.repo, cut)
        reader = gov.BlobReader(self.repo)
        roots = gov.derive_envelope_roots(tree, reader, "svc")
        membership = gov.envelope_membership(tree, roots)
        return {"roots": roots, "membership": membership, **gov.envelope_digests(roots, membership)}

    def envelope_record(self, cut: str | None = None, revision: int = 1, **over) -> dict:
        cut = cut or self.head
        env = self.envelope(cut)
        rec = {"envelope_id": "svc", "revision": revision, "roots": env["roots"],
               "membership": env["membership"],
               "roots_digest": env["roots_digest"], "envelope_digest": env["envelope_digest"],
               "membership_digest": env["membership_digest"],
               "migration_subset_digest": env["migration_subset_digest"],
               "reviewed_commit": cut, "reviewer": "codex", "reviewed_at": "2026-09-03",
               "attestation": {"analyzed": [p for p, _ in env["membership"]],
                               "non_runtime": [], "unsupported": []}}
        rec.update(over)
        return rec

    def policy(self, cut: str | None = None, envelopes=None, dispositions=None, **over) -> dict:
        cut = cut or self.head
        pol = {
            "b1_base_commit": {"sha": cut},
            "gc5": {"always_allowed_globs": ["**"], "forbidden_paths": [],
                    "forbidden_content_symbols": [], "content_scan_excluded_globs": ["**"],
                    "per_holding_freshness_structural_check": {
                        "types": [], "discovery_globs": [], "type_name_pattern": "ZZZNOMATCH"},
                    "reviewed_exceptions": []},
            "writer_inventory": {"production_writers": [], "classified_non_writers": [],
                                 "flagged_writers_outside_holding_replacement_service": [],
                                 "excluded_from_recheck": []},
            "deployables": [{"envelope_id": "svc", "module": "svc"}],
            "envelopes": self.envelope_record(cut) if envelopes is None else envelopes,
            "dispositions": dispositions or [],
            "operational_records": [], "unverified_coverage_reviews": [], "scan_exclusions": [],
            "relevant_tables": ["asset_holdings", "portfolios"],
            "non_persistence_receiver_types": [],
            "merge_grouping": [], "automatic_b1_scope_clearance": [],
            "effect_based_automatic_clearance": [],
            "unresolved": [],
        }
        if isinstance(pol["envelopes"], dict):
            pol["envelopes"] = [pol["envelopes"]]
        pol.update(over)
        return pol

    def run(self, policy=None, cut: str | None = None, run_input=None) -> dict:
        cut = cut or self.head
        return gov.run_all(self.repo, policy or self.policy(cut), None, cut,
                           gov.LOCAL_PREPARATION, None, run_input)

    def artifact(self, rel: str, content: str) -> str:
        """Write a real file and return its sha256 (for operational-record artifacts)."""
        import hashlib
        self.write(rel, content)
        return "sha256:" + hashlib.sha256((self.repo / rel).read_bytes()).hexdigest()

    def write_task_evidence(self, mode="LOCAL_DEV", tamper=None, artifacts: RealArtifacts | None = None):
        """Write producer-shaped Task A / Task B evidence bundles, bound to this cut, as working-tree
        run inputs (never committed). They name a REAL staged JAR and a REAL local image (the shared
        module artifacts unless given), because validation re-reads the JAR and re-extracts /app.jar.
        A `tamper(task_a, task_b)` callback breaks exactly one property for a negative case."""
        art = artifacts or shared_artifacts()
        task_a = producer_shaped_task_a(self.head, self.head, mode, art.jar_path, art.jar_sha)
        task_b = producer_shaped_task_b(self.head, art.jar_path, art.jar_sha, art.image_id, art.platform)
        if tamper:
            tamper(task_a, task_b)
        self.write("evidence/taskA.json", json.dumps(task_a))
        self.write("evidence/taskB.json", json.dumps(task_b))

    def run_input_manifest(self, release_portion=False):
        return {"task_a": {"evidence_file": "evidence/taskA.json",
                           "evidence_sha256": sha256_of(self.repo / "evidence/taskA.json")},
                "task_b": {"evidence_file": "evidence/taskB.json",
                           "evidence_sha256": sha256_of(self.repo / "evidence/taskB.json"),
                           "release_portion": release_portion}}


def subject_of(result: dict, needle: str) -> dict | None:
    for f in result["findings"]:
        if needle in f["subject_id"] or needle in f["path"]:
            return f
    return None


def kinds_for(result: dict, obligation: str) -> set:
    return {f["kind"] for f in result["findings"] if f["obligation"] == obligation}


def make_disposition(finding, record, status=gov.ACCEPTED, fingerprint=None,
                     obligation="writer-inventory", **over):
    """A schema-complete, provenance-bound disposition referencing an exact envelope RECORD by its
    content-addressed id. Negative tests then alter exactly one safety property."""
    d = {"obligation": obligation, "path": finding["path"], "subject_id": finding["subject_id"],
         "envelope_id": record["envelope_id"],
         "envelope_record_id": gov.envelope_record_identity(record),
         "status": status,
         "code_fingerprint": fingerprint if fingerprint is not None else finding["evidence"].get("code_fingerprint"),
         "reviewer": record["reviewer"], "reviewed_commit": record["reviewed_commit"],
         "reviewed_at": "2026-09-03", "claims": []}
    d.update(over)
    return d


JDBC_WRITER = (
    "class Writer {\n"
    "  private JdbcTemplate jdbcTemplate;\n"
    "  @Transactional\n"
    "  void outer() { middle(); }\n"
    "  void middle() { inner(); }\n"
    "  void inner() { jdbcTemplate.update(\"DELETE FROM portfolios WHERE id = ?\"); }\n"
    "}\n"
)


class AddendumFixtureTests(unittest.TestCase):
    """E1-E17 from the evidence-contract addendum, section 8."""

    def _reviewed(self, d: Deployable, cut=None):
        """Returns (disposition, envelope_record) reviewed at `cut`.

        The envelope record is captured AT THE REVIEWED COMMIT and carried unchanged into the later
        run, so a code change at C2 makes the recorded envelope stale and the tool computes the real
        R..C2 delta -- rather than the fixture silently refreshing the record to the new cut."""
        cut = cut or d.head
        res = d.run(d.policy(cut), cut)
        f = subject_of(res, "jdbcTemplate.update")
        self.assertIsNotNone(f, [x["subject_id"] for x in res["findings"]])
        record = d.envelope_record(cut)
        disp = [make_disposition(
            f, record, claims=[{"kind": "VERSION_PARTICIPATION", "value": "cas", "basis": "REVIEWED_ASSERTION"}],
            rationale="reviewed")]
        return disp, record

    def test_E1_reviewed_op_cleared_at_policy_only_commit(self):
        d = Deployable(JDBC_WRITER)
        try:
            disp, _ = self._reviewed(d)
            res = d.run(d.policy(d.head, dispositions=disp))
            self.assertEqual(res["findings"], [], [f["detail"] for f in res["findings"]])
            self.assertEqual(res["source_governance_status"], gov.PASS)
            # This tool never asserts candidate readiness -- it evidences source governance only.
            self.assertFalse(res["candidate_ready"])
        finally:
            d.close()

    def test_E2_helper_body_change_in_another_envelope_file(self):
        d = Deployable(JDBC_WRITER)
        try:
            disp, rec = self._reviewed(d)
            d.write("common/src/main/java/Util.java", "class Util { void h() { int x = 2; } }\n")
            d.commit("helper body")
            res = d.run(d.policy(d.head, dispositions=disp, envelopes=[rec]))
            f = subject_of(res, "jdbcTemplate.update")
            self.assertEqual(f["kind"], gov.ENVELOPE_CHANGED)
            self.assertIn("common/src/main/java/Util.java", f["evidence"].get("changed_paths", []))
        finally:
            d.close()

    def test_E3_transaction_argument_change_three_hops_up(self):
        d = Deployable(JDBC_WRITER)
        try:
            disp, rec = self._reviewed(d)
            d.write("svc/src/main/java/Writer.java",
                    JDBC_WRITER.replace("@Transactional", "@Transactional(propagation = REQUIRES_NEW)"))
            d.commit("tx arg")
            res = d.run(d.policy(d.head, dispositions=disp, envelopes=[rec]))
            f = subject_of(res, "jdbcTemplate.update")
            self.assertIn(f["kind"], (gov.ENVELOPE_CHANGED, gov.UNREVIEWED))
        finally:
            d.close()

    def test_E4_new_file_calling_through_a_functional_interface(self):
        d = Deployable(JDBC_WRITER)
        try:
            disp, rec = self._reviewed(d)
            d.write("svc/src/main/java/Batch.java",
                    "class Batch { void go(Runnable r) { r.run(); } }\n")
            d.commit("new caller")
            res = d.run(d.policy(d.head, dispositions=disp, envelopes=[rec]))
            self.assertEqual(subject_of(res, "jdbcTemplate.update")["kind"], gov.ENVELOPE_CHANGED)
        finally:
            d.close()

    def test_E5_comment_in_the_subjects_own_file(self):
        d = Deployable(JDBC_WRITER)
        try:
            disp, rec = self._reviewed(d)
            d.write("svc/src/main/java/Writer.java", "// note\n" + JDBC_WRITER)
            d.commit("comment")
            res = d.run(d.policy(d.head, dispositions=disp, envelopes=[rec]))
            f = subject_of(res, "jdbcTemplate.update")
            # Tier 0 is unchanged by a comment, so the envelope is what catches this.
            self.assertEqual(f["kind"], gov.ENVELOPE_CHANGED)
        finally:
            d.close()

    def test_E6_optimistic_lock_removal_changes_tier0(self):
        d = Deployable(JDBC_WRITER)
        try:
            before = gov.tree_blobs(d.repo, d.head)
            reader = gov.BlobReader(d.repo)
            src = reader.text(before["svc/src/main/java/Portfolio.java"])
            tk = gov.lex_java(src)
            md_before = gov.entity_mapping_digest(tk, gov.java_contexts(tk), "Portfolio")
            stripped = src.replace("  @OptimisticLock(excluded = true)\n", "")
            tk2 = gov.lex_java(stripped)
            md_after = gov.entity_mapping_digest(tk2, gov.java_contexts(tk2), "Portfolio")
            self.assertNotEqual(md_before, md_after)
        finally:
            d.close()

    def test_E7_unsupported_shapes_and_ineffective_assertion(self):
        for src in ('class W { void m() { jdbc.update(String.join(" ", parts)); } }',
                    'class W { void m(String sql) { jdbc.update(sql); } }',
                    'class W { void m() { PreparedStatement ps = c.prepareStatement("DELETE FROM portfolios"); ps.execute(); } }'):
            ops = operations_of(src)
            self.assertTrue(any(v["coverage"] == gov.UNSUPPORTED for v in ops.values()) or not ops,
                            src)

    def test_E8_pure_literal_fragments_fold(self):
        ops = operations_of('class W { void m() { jdbc.update("DEL" + "ETE FROM asset_holdings"); } }')
        stmt = list(ops.values())[0]["statement"]
        self.assertEqual(stmt["sql"], "DELETE FROM asset_holdings")
        self.assertEqual(stmt["target_tables"], ["asset_holdings"])

    def test_E9_resource_sql_is_enumerated(self):
        d = Deployable(None, extra={"svc/src/main/resources/cleanup.sql":
                                    "DELETE FROM asset_holdings WHERE id = 1;\n"})
        try:
            res = d.run()
            f = subject_of(res, "cleanup.sql")
            self.assertIsNotNone(f)
            self.assertIn(f["kind"], (gov.UNREVIEWED, gov.UNSUPPORTED))
        finally:
            d.close()

    def test_E10_persistence_reference_with_no_recognised_form(self):
        d = Deployable("class W { private DatabaseClient client; void m() { client.sql(q); } }\n")
        try:
            res = d.run()
            f = [x for x in res["findings"] if x["obligation"] == "persistence-usage"]
            self.assertTrue(f, [x["obligation"] for x in res["findings"]])
            self.assertEqual(f[0]["kind"], gov.UNSUPPORTED)
        finally:
            d.close()

    def test_E11_fk_cascade_yields_indirect_target(self):
        d = Deployable(JDBC_WRITER, cascade=True)
        try:
            res = d.run()
            f = subject_of(res, "jdbcTemplate.update")
            basis = f["evidence"]["basis"]
            self.assertIn("portfolios", basis["direct_tables"])
            self.assertIn("asset_holdings", basis["indirect_tables"])
        finally:
            d.close()

    def test_E12_mongo_receiver_is_unrelated(self):
        d = Deployable("class W { private MongoTemplate mongoTemplate;"
                       " void m() { mongoTemplate.save(doc); } }\n")
        try:
            res = d.run()
            self.assertIsNone(subject_of(res, "mongoTemplate.save"))
            self.assertEqual(res["coverage"]["effects"].get(gov.UNRELATED), 1,
                             res["coverage"]["effects"])
        finally:
            d.close()

    def test_E13_map_merge_is_not_a_relevant_db_write(self):
        # `c.merge(...)` on an in-file-declared Map is a memory-store receiver: UNRELATED, listed
        # with basis, non-blocking. The counter is a diagnostic only.
        d = Deployable("class W { void m() { java.util.Map<String,Integer> c = new HashMap<>();"
                       " c.merge(k, 1, Integer::sum); } }\n")
        try:
            res = d.run()
            self.assertIsNone(subject_of(res, "c.merge"))
            self.assertEqual(res["coverage"]["effects"].get(gov.UNRELATED), 1,
                             res["coverage"]["effects"])
            self.assertEqual(res["overall_status"], gov.PASS)
        finally:
            d.close()

    def test_E14_untyped_receiver_is_unresolved_and_blocks(self):
        d = Deployable("class W { void m() { mystery.save(x); } }\n")
        try:
            res = d.run()
            f = subject_of(res, "mystery.save")
            self.assertIsNotNone(f, [x["subject_id"] for x in res["findings"]])
            self.assertEqual(f["kind"], gov.UNRESOLVED)
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_E15_operational_record_then_new_migration(self):
        d = Deployable(None, extra={"svc/src/main/resources/db/migration/V2__fn.sql":
                                    "CREATE FUNCTION f() AS $$ BEGIN EXECUTE format('DELETE FROM %I', t); END; $$;\n"})
        try:
            res = d.run()
            sql_f = [x for x in res["findings"] if x["subject_id"].startswith("sql:")
                     and x["kind"] == gov.UNSUPPORTED]
            self.assertTrue(sql_f, [x["subject_id"] for x in res["findings"]])
            env = d.envelope()
            q_hash = d.artifact("evidence/op-query.sql", "DROP FUNCTION f();\n")
            r_hash = d.artifact("evidence/op-result.txt", "DROP FUNCTION\n")
            rec = {"subject_ref": {"path": sql_f[0]["path"], "subject_id": sql_f[0]["subject_id"]},
                   "environment_identity": "prod-db-1",
                   "query_artifact": {"path": "evidence/op-query.sql", "sha256": q_hash},
                   "result_artifact": {"path": "evidence/op-result.txt", "sha256": r_hash},
                   "operator": "owner", "recorded_at": "2026-09-03",
                   "reviewed_commit": d.head, "migration_subset_digest": env["migration_subset_digest"]}
            pol = d.policy(d.head, operational_records=[rec],
                           operational_target_environment="prod-db-1")
            res2 = gov.run_all(d.repo, pol, None, d.head, gov.LOCAL_PREPARATION, None)
            self.assertTrue(res2["unverified_coverage"], res2["summary"])
            # Correction 1: unverified residue is a DIAGNOSTIC substatus and still blocks.
            self.assertEqual(res2["readiness_substatus"], gov.PASS_EXCEPT_UNVERIFIED)
            self.assertEqual(res2["overall_status"], gov.BLOCKED)
            self.assertFalse(res2["candidate_ready"])
        finally:
            d.close()

    def test_E16_disposition_without_envelope_id_is_invalid(self):
        d = Deployable(JDBC_WRITER)
        try:
            disp, _ = self._reviewed(d)
            del disp[0]["envelope_id"]
            res = d.run(d.policy(d.head, dispositions=disp))
            self.assertEqual(subject_of(res, "jdbcTemplate.update")["kind"], gov.DISPOSITION_INVALID)
        finally:
            d.close()

    def test_E17_exclusion_naming_a_production_path_is_ignored(self):
        d = Deployable(JDBC_WRITER)
        try:
            pol = d.policy(d.head, scan_exclusions=[
                {"glob": "svc/src/main/**", "reason": "nope", "kind": "TEST_CORPUS"}])
            res = gov.run_all(d.repo, pol, None, d.head, gov.LOCAL_PREPARATION, None)
            self.assertIn(gov.POLICY_INVALID, kinds_for(res, "policy-validity"))
            # glob ignored: the subject is still enumerated
            self.assertIsNotNone(subject_of(res, "jdbcTemplate.update"))
        finally:
            d.close()


class NormativeRegressionTests(unittest.TestCase):
    """The seven regressions named in the architecture decision."""

    def test_zero_dispositions_still_blocks_on_envelope_change(self):
        d = Deployable(None)
        try:
            pol = d.policy(d.head)
            self.assertEqual(gov.run_all(d.repo, pol, None, d.head, gov.LOCAL_PREPARATION, None)[
                "overall_status"], gov.PASS)
            d.write("common/src/main/java/Util.java", "class Util { int z = 1; }\n")
            d.commit("touch")
            res = gov.run_all(d.repo, pol, None, d.head, gov.LOCAL_PREPARATION, None)
            self.assertIn(gov.ENVELOPE_CHANGED, kinds_for(res, "envelope"))
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_seed_resource_and_dependency_build_file_are_in_the_envelope(self):
        d = Deployable(None)
        try:
            pol = d.policy(d.head)
            members = {p for p, _ in d.envelope()["membership"]}
            self.assertIn("config/seed.json", members)
            self.assertIn("common/build.gradle", members)
            for rel, body in (("config/seed.json", '{"tickers": ["X"]}\n'),
                              ("common/build.gradle", "// changed\n")):
                d.write(rel, body)
                d.commit("change " + rel)
                res = gov.run_all(d.repo, pol, None, d.head, gov.LOCAL_PREPARATION, None)
                self.assertIn(gov.ENVELOPE_CHANGED, kinds_for(res, "envelope"), rel)
        finally:
            d.close()

    def test_unresolved_subject_with_valid_disposition_stays_blocked(self):
        d = Deployable("class W { void m() { mystery.save(x); } }\n")
        try:
            res = d.run()
            f = subject_of(res, "mystery.save")
            disp = [make_disposition(f, d.envelope_record(d.head))]
            res2 = gov.run_all(d.repo, d.policy(d.head, dispositions=disp), None, d.head,
                               gov.LOCAL_PREPARATION, None)
            still = subject_of(res2, "mystery.save")
            self.assertEqual(still["kind"], gov.UNRESOLVED,
                             "a writer disposition must never clear UNRESOLVED")
            self.assertEqual(res2["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_unverified_coverage_exits_nonzero(self):
        d = Deployable(None, extra={"svc/src/main/resources/db/migration/V2__fn.sql":
                                    "CREATE FUNCTION f() AS $$ BEGIN EXECUTE format('X'); END; $$;\n"})
        try:
            res = d.run()
            self.assertEqual(res["overall_status"], gov.BLOCKED)
            self.assertFalse(res["candidate_ready"])
        finally:
            d.close()

    def test_mutating_an_envelope_record_cannot_revalidate_old_dispositions(self):
        d = Deployable(JDBC_WRITER)
        try:
            res = d.run()
            f = subject_of(res, "jdbcTemplate.update")
            old_record = d.envelope_record(d.head)
            disp = [make_disposition(f, old_record)]  # references the OLD record's content id
            d.write("common/src/main/java/Util.java", "class Util { int q = 9; }\n")
            d.commit("drift")
            # Mutate the record in place to carry the NEW cut's digests while keeping the OLD
            # reviewed_commit. Content-addressed identity means this mutated record has a DIFFERENT
            # id, so the disposition's reference no longer resolves -- the rebind is rejected.
            mutated = d.envelope_record(d.head, revision=1, reviewed_commit=old_record["reviewed_commit"])
            res2 = gov.run_all(d.repo, d.policy(d.head, envelopes=[mutated], dispositions=disp),
                               None, d.head, gov.LOCAL_PREPARATION, None)
            self.assertIn(subject_of(res2, "jdbcTemplate.update")["kind"],
                          (gov.DISPOSITION_INVALID, gov.ENVELOPE_CHANGED))
        finally:
            d.close()

    def test_renewal_without_previous_reference_is_rejected(self):
        d = Deployable(None)
        try:
            d.write("common/src/main/java/Util.java", "class Util { int q = 1; }\n")
            d.commit("drift")
            rec = d.envelope_record(d.head, revision=2)  # no previous_envelope_record_id
            res = gov.run_all(d.repo, d.policy(d.head, envelopes=[rec]), None, d.head,
                              gov.LOCAL_PREPARATION, None)
            env_f = [f for f in res["findings"] if f["obligation"] == "envelope"]
            self.assertTrue(env_f)
            self.assertIn("previous_envelope_record_id", env_f[0]["detail"])
        finally:
            d.close()

    def test_valid_run_input_evidence_validates(self):
        # Positive control: schema-valid, cut-bound evidence supplied as a run input passes evidence
        # binding (source governance may still block for other reasons, but not on evidence).
        d = Deployable(None)
        try:
            d.write_task_evidence()
            res = d.run(run_input=d.run_input_manifest())
            self.assertEqual(kinds_for(res, "evidence-binding"), set())
            self.assertEqual(res["evidence"]["task_a"]["head_sha"], d.head)
        finally:
            d.close()

    def test_non_json_evidence_is_rejected(self):
        d = Deployable(None)
        try:
            d.write("evidence/taskA.json", "this is not json")
            d.write_task_evidence()  # writes a valid task_b; overwrite task_a with garbage next
            d.write("evidence/taskA.json", "not json at all")
            res = d.run(run_input=d.run_input_manifest())
            self.assertIn(gov.EVIDENCE_BINDING_MISMATCH, kinds_for(res, "evidence-binding"))
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_invented_jar_and_image_identity_is_rejected(self):
        # The measured false-PASS: invented staged_jar / image identities that cross-checks catch.
        for tamper in (lambda a, b: b.__setitem__("task_a_evidence_head_sha", "sha256:" + "0" * 64),
                       lambda a, b: b.__setitem__("staged_jar_sha256", "invented-jar"),
                       lambda a, b: a.__setitem__("graph_verification_status", "FAIL")):
            d = Deployable(None)
            try:
                d.write_task_evidence(tamper=tamper)
                res = d.run(run_input=d.run_input_manifest())
                self.assertIn(gov.EVIDENCE_BINDING_MISMATCH, kinds_for(res, "evidence-binding"))
                self.assertEqual(res["overall_status"], gov.BLOCKED)
            finally:
                d.close()

    def test_local_preparation_is_never_candidate_ready_even_with_valid_evidence(self):
        d = Deployable(None)
        try:
            d.write_task_evidence()
            res = d.run(run_input=d.run_input_manifest())
            self.assertFalse(res["candidate_ready"])
            self.assertTrue(any("LOCAL_PREPARATION" in x for x in res["candidate_ready_blocked_by"]))
        finally:
            d.close()

    def test_operational_record_for_the_wrong_database_does_not_clear(self):
        d = Deployable(None, extra={"svc/src/main/resources/db/migration/V2__fn.sql":
                                    "CREATE FUNCTION f() AS $$ BEGIN EXECUTE format('X'); END; $$;\n"})
        try:
            res = d.run()
            sql_f = [x for x in res["findings"] if x["subject_id"].startswith("sql:")
                     and x["kind"] == gov.UNSUPPORTED][0]
            env = d.envelope()
            # Real artifacts with matching hashes, so the ONLY broken property is the environment.
            q_hash = d.artifact("evidence/q.sql", "DROP FUNCTION f();\n")
            r_hash = d.artifact("evidence/r.txt", "ok\n")
            rec = {"subject_ref": {"path": sql_f["path"], "subject_id": sql_f["subject_id"]},
                   "environment_identity": "staging-db",
                   "query_artifact": {"path": "evidence/q.sql", "sha256": q_hash},
                   "result_artifact": {"path": "evidence/r.txt", "sha256": r_hash},
                   "operator": "owner", "recorded_at": "2026-09-03", "reviewed_commit": d.head,
                   "migration_subset_digest": env["migration_subset_digest"]}
            pol = d.policy(d.head, operational_records=[rec],
                           operational_target_environment="prod-db-1")
            res2 = gov.run_all(d.repo, pol, None, d.head, gov.LOCAL_PREPARATION, None)
            opf = [f for f in res2["findings"] if f["obligation"] == "operational-record"]
            self.assertTrue(opf, [f["obligation"] for f in res2["findings"]])
            self.assertIn("staging-db", opf[0]["detail"])
            self.assertEqual(res2["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_inactive_features_block_when_configured(self):
        d = Deployable(None)
        try:
            for feature in gov.INACTIVE_POLICY_FEATURES:
                pol = d.policy(d.head)
                pol[feature] = [{"anything": True}]
                res = gov.run_all(d.repo, pol, None, d.head, gov.LOCAL_PREPARATION, None)
                self.assertIn(gov.POLICY_INVALID, kinds_for(res, "policy-validity"), feature)
                self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_narrowed_envelope_roots_cannot_validate_themselves(self):
        d = Deployable(None)
        try:
            pol = d.policy(d.head)
            pol["deployables"] = [{"envelope_id": "svc", "module": "svc",
                                   "roots": ["svc/src/main/"]}]  # omits build files and seed.json
            res = gov.run_all(d.repo, pol, None, d.head, gov.LOCAL_PREPARATION, None)
            env_f = [f for f in res["findings"] if f["obligation"] == "envelope"]
            self.assertTrue(any(f["kind"] == gov.POLICY_INVALID for f in env_f), env_f)
        finally:
            d.close()


class IndependentReviewNegatives(unittest.TestCase):
    """The probes from the v3 independent review. Each proves a former false-pass now BLOCKS."""

    def test_setter_status_only_approval_is_rejected(self):
        # F3: a setter disposition with only path/subject_id/status must not clear.
        d = Deployable("class W {\n  private JdbcTemplate jdbcTemplate;\n"
                       "  void rel() { jdbcTemplate.update(\"DELETE FROM portfolios WHERE id = ?\"); }\n}\n",
                       extra={"svc/src/main/java/Acct.java":
                              "@Entity class Acct { private BigDecimal q;"
                              " public void setQ(BigDecimal v) { this.q = v; } }\n"})
        try:
            res = d.run()
            setter = subject_of(res, "setQ")
            self.assertIsNotNone(setter, [x["subject_id"] for x in res["findings"]])
            pol = d.policy(d.head, dispositions=[{"path": setter["path"],
                                                  "subject_id": setter["subject_id"],
                                                  "status": gov.ACCEPTED}])
            res2 = d.run(pol)
            still = subject_of(res2, "setQ")
            self.assertIsNotNone(still, "status-only setter approval must not clear the setter")
            self.assertEqual(res2["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_effect_resolution_trust_me_is_rejected(self):
        # F3: an unresolved op reclassified with "trust me" evidence and no provenance must not clear.
        d = Deployable("class W { void m() { mystery.save(x); } }\n")
        try:
            res = d.run()
            f = subject_of(res, "mystery.save")
            self.assertEqual(f["kind"], gov.UNRESOLVED)
            pol = d.policy(d.head, effect_resolutions=[{"path": f["path"], "subject_id": f["subject_id"],
                                                        "resolved_effect": gov.UNRELATED,
                                                        "evidence": "trust me"}])
            res2 = d.run(pol)
            still = subject_of(res2, "mystery.save")
            self.assertIn(still["kind"], (gov.UNRESOLVED, gov.DISPOSITION_INVALID))
            self.assertEqual(res2["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_nonexistent_reviewed_commit_on_envelope_is_rejected(self):
        # F4: an all-zero reviewed_commit must not pass envelope validation.
        d = Deployable(JDBC_WRITER)
        try:
            rec = d.envelope_record(d.head, reviewed_commit="0" * 40)
            res = gov.run_all(d.repo, d.policy(d.head, envelopes=[rec]), None, d.head,
                              gov.LOCAL_PREPARATION, None)
            env_f = [f for f in res["findings"] if f["obligation"] == "envelope"]
            self.assertTrue(env_f)
            self.assertTrue(any("does not exist" in f["detail"] for f in env_f), [f["detail"] for f in env_f])
        finally:
            d.close()

    def test_all_unsupported_attestation_blocks(self):
        # F4: moving every member to `unsupported` must generate blocking unverified-coverage.
        d = Deployable(JDBC_WRITER)
        try:
            env = d.envelope(d.head)
            members = [p for p, _ in env["membership"]]
            rec = d.envelope_record(d.head, attestation={"analyzed": [], "non_runtime": [],
                                                         "unsupported": members})
            res = gov.run_all(d.repo, d.policy(d.head, envelopes=[rec]), None, d.head,
                              gov.LOCAL_PREPARATION, None)
            uc = [f for f in res["findings"] if f["obligation"] == "unverified-coverage"]
            self.assertTrue(uc, "an all-unsupported attestation must not read as covered")
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_persistence_chain_hidden_behind_a_harmless_op_is_reported(self):
        # F5: a Map.merge does not account for an unresolved DatabaseClient reactive chain.
        d = Deployable("class W {\n  private java.util.Map<String,Integer> c = new HashMap<>();\n"
                       "  private DatabaseClient client;\n"
                       "  void m() { c.merge(k, 1, Integer::sum);"
                       " client.sql(q).fetch().rowsUpdated().block(); }\n}\n")
        try:
            res = d.run()
            pu = [f for f in res["findings"] if f["obligation"] == "persistence-usage"]
            self.assertTrue(pu, "unaccounted DatabaseClient usage must be reported")
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_multiline_script_dml_blocks(self):
        d = Deployable(None, extra={"tools/cleanup.py":
                                    "import psycopg\ncur.execute(\"\"\"\nDELETE\nFROM portfolios\n\"\"\")\n"})
        try:
            res = d.run()
            sc = [f for f in res["findings"] if f["subject_id"].startswith("script:")]
            self.assertTrue(sc, "a triple-quoted multiline DELETE must be detected")
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_empty_deployables_does_not_hide_a_governed_module(self):
        # F5: deleting the policy deployables list must not suppress the envelope obligation.
        d = Deployable(JDBC_WRITER)
        try:
            pol = d.policy(d.head)
            pol["deployables"] = []
            pol["envelopes"] = []
            res = gov.run_all(d.repo, pol, None, d.head, gov.LOCAL_PREPARATION, None)
            env_f = [f for f in res["findings"] if f["obligation"] == "envelope"]
            self.assertTrue(any("svc" in f["path"] or f["subject_id"] == "envelope:svc" for f in env_f),
                            [f["subject_id"] for f in env_f])
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_unrelated_write_in_governed_module_blocks_while_autoclear_inactive(self):
        # F6: automatic effect clearance is inactive, so an UNRELATED write in a governed deployable
        # still needs explicit review.
        d = Deployable("class W {\n  private JdbcTemplate jdbcTemplate;\n"
                       "  void rel() { jdbcTemplate.update(\"DELETE FROM portfolios WHERE id = ?\"); }\n"
                       "  void unrel() { jdbcTemplate.update(\"INSERT INTO users (id) VALUES (?)\"); }\n}\n")
        try:
            res = d.run()
            unrel = next(f for f in res["findings"] if "unrel" in f["subject_id"])
            self.assertEqual(unrel["kind"], gov.UNREVIEWED)
            self.assertIn("automatic effect clearance is inactive", unrel["detail"])
        finally:
            d.close()

    def test_cascade_is_not_propagated_to_an_insert(self):
        # F6: an INSERT INTO portfolios does not trigger ON DELETE CASCADE into asset_holdings.
        d = Deployable("class W {\n  private JdbcTemplate jdbcTemplate;\n"
                       "  void m() { jdbcTemplate.update(\"INSERT INTO portfolios (id) VALUES (?)\"); }\n}\n",
                       cascade=True)
        try:
            res = d.run()
            f = subject_of(res, "jdbcTemplate.update")
            self.assertEqual(f["evidence"]["basis"].get("indirect_tables", []), [],
                             "an INSERT must not inherit an ON DELETE cascade target")
        finally:
            d.close()

    def test_effect_based_auto_clearance_active_requires_approval(self):
        # F6/F10: configuring the inactive feature is itself a blocking policy finding.
        d = Deployable(None)
        try:
            pol = d.policy(d.head)
            pol["effect_based_automatic_clearance"] = [{"module": "svc"}]
            res = gov.run_all(d.repo, pol, None, d.head, gov.LOCAL_PREPARATION, None)
            self.assertIn(gov.POLICY_INVALID, kinds_for(res, "policy-validity"))
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()


class ConsolidationReviewNegatives(unittest.TestCase):
    """The five consolidation-review probes: a former false-pass for each now blocks, with a
    matching schema-valid positive control."""

    # ---- item 1: envelope renewal lifecycle -------------------------------------------
    def _renewal(self, d, r_old, r_new):
        old_rec = d.envelope_record(r_old, revision=1)
        old_id = gov.envelope_record_identity(old_rec)
        delta = gov.diff_envelope(d.envelope(r_old)["membership"], d.envelope(r_new)["membership"])
        new_rec = d.envelope_record(r_new, revision=2, previous_envelope_record_id=old_id,
                                    reviewed_delta=delta, affected_claims=[])
        return old_rec, new_rec, delta

    def test_successful_renewal_then_policy_only_commit(self):
        d = Deployable(None)
        try:
            r_old = d.head
            d.write("common/src/main/java/Util.java", "class Util { int q = 1; }\n")
            d.commit("dependency change reviewed at R_new")
            r_new = d.head
            old_rec, new_rec, _delta = self._renewal(d, r_old, r_new)
            res = gov.run_all(d.repo, d.policy(r_new, envelopes=[old_rec, new_rec]), None, r_new,
                              gov.LOCAL_PREPARATION, None)
            env_f = [f for f in res["findings"] if f["obligation"] == "envelope"]
            self.assertEqual(env_f, [], [f["detail"] for f in env_f])
            self.assertEqual(res["overall_status"], gov.PASS)
        finally:
            d.close()

    def test_renewal_with_forged_delta_is_rejected(self):
        d = Deployable(None)
        try:
            r_old = d.head
            d.write("common/src/main/java/Util.java", "class Util { int q = 1; }\n")
            d.commit("change")
            r_new = d.head
            old_rec, new_rec, _delta = self._renewal(d, r_old, r_new)
            new_rec["reviewed_delta"] = {"added": ["forged/path.java"], "removed": [], "modified": []}
            res = gov.run_all(d.repo, d.policy(r_new, envelopes=[old_rec, new_rec]), None, r_new,
                              gov.LOCAL_PREPARATION, None)
            env_f = [f for f in res["findings"] if f["obligation"] == "envelope"]
            self.assertTrue(any("reviewed_delta" in f["detail"] for f in env_f), [f["detail"] for f in env_f])
        finally:
            d.close()

    def test_renewal_with_invalid_previous_reference_is_rejected(self):
        d = Deployable(None)
        try:
            r_old = d.head
            d.write("common/src/main/java/Util.java", "class Util { int q = 1; }\n")
            d.commit("change")
            r_new = d.head
            _old, new_rec, _delta = self._renewal(d, r_old, r_new)
            new_rec["previous_envelope_record_id"] = "sha256:" + "0" * 64  # dangling
            res = gov.run_all(d.repo, d.policy(r_new, envelopes=[new_rec]), None, r_new,
                              gov.LOCAL_PREPARATION, None)
            env_f = [f for f in res["findings"] if f["obligation"] == "envelope"]
            self.assertTrue(any("previous_envelope_record_id" in f["detail"] for f in env_f))
        finally:
            d.close()

    # ---- item 2: run-evidence semantics -----------------------------------------------
    def test_unverified_provenance_is_rejected(self):
        d = Deployable(None)
        try:
            d.write_task_evidence(tamper=lambda a, b: b.__setitem__("provenance", "unverified"))
            res = d.run(run_input=d.run_input_manifest())
            self.assertIn(gov.EVIDENCE_BINDING_MISMATCH, kinds_for(res, "evidence-binding"))
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_platform_mismatch_is_rejected(self):
        d = Deployable(None)
        try:
            d.write_task_evidence(tamper=lambda a, b: b.__setitem__("platform", "linux/arm64"))
            res = d.run(run_input=d.run_input_manifest())
            self.assertTrue(any("platform" in f["detail"] for f in res["findings"]
                                if f["obligation"] == "evidence-binding"))
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_mutually_matching_invented_hashes_and_image_id_are_rejected(self):
        # The corrected version of the mis-named earlier test: mutate the image id AND make the
        # invalid JAR hashes mutually consistent, so only cross-checks against Task A / cut catch it.
        def tamper(a, b):
            a["stage"]["sha256"] = "invented-jar"
            b["staged_jar_sha256"] = "invented-jar"
            b["extracted_jar_sha256"] = "invented-jar"
            b["local_image_id"] = "invented-image"
        d = Deployable(None)
        try:
            d.write_task_evidence(tamper=tamper)
            res = d.run(run_input=d.run_input_manifest())
            details = [f["detail"] for f in res["findings"] if f["obligation"] == "evidence-binding"]
            self.assertTrue(any("stage.sha256" in x for x in details), details)
            self.assertTrue(any("local_image_id" in x for x in details), details)
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_input_evidence_hashes_are_recorded_even_without_expected_hash(self):
        d = Deployable(None)
        try:
            d.write_task_evidence()
            manifest = d.run_input_manifest()
            del manifest["task_a"]["evidence_sha256"]  # caller omits the expected hash
            res = d.run(run_input=manifest)
            self.assertIn("evidence/taskA.json", res["evidence"]["input_hashes"])
        finally:
            d.close()

    # ---- item 3: claim reviewed against its subject -----------------------------------
    def test_claim_reviewed_commit_predating_the_subject_is_rejected(self):
        d = Deployable(None)  # no writer at base commit R
        try:
            r_old = d.head
            d.write("svc/src/main/java/Writer.java", JDBC_WRITER)
            d.commit("add writer after R")
            cut = d.head
            res = d.run(cut=cut)
            f = subject_of(res, "jdbcTemplate.update")
            record = d.envelope_record(cut)
            # Approve the correct current fingerprint/envelope, but claim it was reviewed at R_old,
            # where the writer did not exist.
            disp = [make_disposition(f, record, reviewed_commit=r_old)]
            res2 = d.run(d.policy(cut, dispositions=disp), cut=cut)
            still = subject_of(res2, "jdbcTemplate.update")
            self.assertIsNotNone(still, "a claim predating its subject must not clear")
            self.assertIn(still["kind"], (gov.DISPOSITION_INVALID, gov.UNREVIEWED))
        finally:
            d.close()

    def test_rejected_path_exception_does_not_clear(self):
        d = Deployable(None)
        try:
            base = gov.resolve_commit(d.repo, "HEAD")
            d.write("frontend/src/app.tsx", "export const x = 1;\n")
            d.commit("forbidden path added after R")
            cut = d.head
            entries = gov.changed_entries(d.repo, base, cut)
            entry = [e for e in entries if e.path == "frontend/src/app.tsx"][0]
            gc5 = d.policy(cut)["gc5"]
            gc5["forbidden_paths"] = [{"non_goal": "10.1", "glob": "frontend/src/**"}]
            gc5["reviewed_exceptions"] = [{"path": "frontend/src/app.tsx", "obligation": "path-governance",
                                           "status": "REJECTED", "change_kind": entry.status,
                                           "src_blob": entry.src_blob, "dst_blob": entry.dst_blob,
                                           "reviewed_commit": base, "reviewer": "codex"}]
            findings = gov.path_guard(entries, gc5, d.repo)
            hit = [f for f in findings if f.path == "frontend/src/app.tsx"]
            self.assertTrue(any(f.kind == gov.CONFIRMED_MATCH for f in hit),
                            "a REJECTED exception must not clear a forbidden path")
        finally:
            d.close()

    def test_setter_disposition_uses_a_mandatory_fingerprint(self):
        d = Deployable("class W {\n  private JdbcTemplate jdbcTemplate;\n"
                       "  void rel() { jdbcTemplate.update(\"DELETE FROM portfolios WHERE id=?\"); }\n}\n",
                       extra={"svc/src/main/java/Acct.java":
                              "@Entity class Acct { private BigDecimal q;"
                              " public void setQ(BigDecimal v) { this.q = v; } }\n"})
        try:
            res = d.run()
            setter = subject_of(res, "setQ")
            record = d.envelope_record(d.head)
            # A disposition with the WRONG fingerprint must not clear the setter.
            disp = [make_disposition(setter, record, fingerprint="sha256:" + "0" * 64)]
            res2 = d.run(d.policy(d.head, dispositions=disp))
            self.assertIsNotNone(subject_of(res2, "setQ"))
            # And the correct fingerprint clears it.
            disp_ok = [make_disposition(setter, record,
                                        fingerprint=setter["evidence"]["code_fingerprint"])]
            res3 = d.run(d.policy(d.head, dispositions=disp_ok))
            self.assertIsNone(subject_of(res3, "setQ"))
        finally:
            d.close()

    # ---- item 4: record identity covers all assertions --------------------------------
    def test_attestation_flip_changes_record_identity_and_breaks_dispositions(self):
        d = Deployable(JDBC_WRITER)
        try:
            res = d.run()
            f = subject_of(res, "jdbcTemplate.update")
            members = [p for p, _ in d.envelope(d.head)["membership"]]
            # Record that attests every member unsupported, and a disposition bound to its id.
            rec = d.envelope_record(d.head, attestation={"analyzed": [], "non_runtime": [],
                                                         "unsupported": members})
            disp = [make_disposition(f, rec)]
            # Flip the SAME record's attestation to analyzed WITHOUT changing reviewer/commit/revision.
            flipped = d.envelope_record(d.head, attestation={"analyzed": members, "non_runtime": [],
                                                             "unsupported": []})
            self.assertNotEqual(gov.envelope_record_identity(rec), gov.envelope_record_identity(flipped),
                                "an attestation change must change the record identity")
            res2 = gov.run_all(d.repo, d.policy(d.head, envelopes=[flipped], dispositions=disp),
                               None, d.head, gov.LOCAL_PREPARATION, None)
            # The disposition still references the OLD id, which no longer exists.
            self.assertEqual(subject_of(res2, "jdbcTemplate.update")["kind"], gov.DISPOSITION_INVALID)
        finally:
            d.close()

    # ---- item 5: per-usage coverage ---------------------------------------------------
    def test_recognized_call_does_not_hide_an_unknown_call_on_the_same_receiver(self):
        d = Deployable("class W {\n  private JdbcTemplate jdbcTemplate;\n"
                       "  void m() { jdbcTemplate.update(\"DELETE FROM portfolios WHERE id=?\");"
                       " jdbcTemplate.call(con -> con.prepareCall(\"{call mutate_holdings()}\"),"
                       " java.util.List.of()); }\n}\n")
        try:
            res = d.run()
            pu = [f for f in res["findings"] if f["obligation"] == "persistence-usage"
                  and ".call" in f["subject_id"]]
            self.assertTrue(pu, "an unrecognized call on a persistence receiver must be reported")
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_read_only_query_on_a_jdbc_receiver_is_not_flagged(self):
        # Positive control: a recognized read-only method is accounted for, not reported.
        d = Deployable("class W {\n  private JdbcTemplate jdbcTemplate;\n"
                       "  Object m() { return jdbcTemplate.queryForObject(\"SELECT 1\", Integer.class); }\n}\n")
        try:
            res = d.run()
            pu = [f for f in res["findings"] if f["obligation"] == "persistence-usage"
                  and "queryForObject" in f["subject_id"]]
            self.assertEqual(pu, [], "a recognized read-only method must not be reported as unaccounted")
        finally:
            d.close()


class PostConsolidationEvidenceTests(unittest.TestCase):
    """Post-consolidation review, item 1: the evidence validator rejected the accepted producers'
    real schema while accepting fabricated valid-looking artifacts."""

    REAL_A = REPO / ".candidate-artifacts" / "evidence.json"
    REAL_B = REPO / ".candidate-artifacts" / "image-evidence.json"

    def test_bare_hex_and_prefixed_digests_share_one_parser(self):
        hexd = "a" * 64
        self.assertEqual(ev.normalize_sha256_digest(hexd), "sha256:" + hexd)
        self.assertEqual(ev.normalize_sha256_digest("sha256:" + hexd), "sha256:" + hexd)
        for bad in ("invented-jar", "sha256:" + "a" * 63, "SHA256:" + hexd, None, 12, ""):
            self.assertIsNone(ev.normalize_sha256_digest(bad), bad)

    @unittest.skipUnless(REAL_A.is_file() and REAL_B.is_file(), "preserved real Task A/B bundles absent")
    def test_preserved_real_bundles_parse_under_local_preparation_but_not_candidate(self):
        a = json.loads(self.REAL_A.read_text(encoding="utf-8"))
        b = json.loads(self.REAL_B.read_text(encoding="utf-8"))
        cut, base = a["run"]["head_sha"], a["run"]["b1_base_sha"]
        self.assertEqual(gov.task_a_schema_problems(a, cut, base, gov.LOCAL_PREPARATION), [])
        self.assertEqual(gov.task_b_schema_problems(b, cut, gov.LOCAL_PREPARATION, False), [])
        # The real Task A run was LOCAL_DEV (dirty tree): never eligible as CANDIDATE input.
        cand = gov.task_a_schema_problems(a, cut, base, gov.CANDIDATE)
        self.assertTrue(any("mode" in p for p in cand), cand)

    @unittest.skipUnless(REAL_A.is_file() and REAL_B.is_file(), "preserved real Task A/B bundles absent")
    def test_preserved_real_bundles_are_not_rejected_as_malformed(self):
        # The MEASURED defect: feeding the actual preserved bundles produced
        # "Task A stage.sha256 is missing or malformed" / "Task B staged_jar_sha256 is missing or
        # malformed". Whatever else binding may report (the local image may since have been removed),
        # the producers' own schema must not read as malformed.
        a = json.loads(self.REAL_A.read_text(encoding="utf-8"))
        run_input = {"task_a": {"evidence_file": ".candidate-artifacts/evidence.json"},
                     "task_b": {"evidence_file": ".candidate-artifacts/image-evidence.json"}}
        _summary, findings = gov.validate_run_input_evidence(
            run_input, REPO, a["run"]["head_sha"], None, gov.LOCAL_PREPARATION, a["run"]["b1_base_sha"])
        details = [f.detail for f in findings]
        self.assertFalse(any("malformed" in d for d in details), details)
        self.assertFalse(any("not an accepted mode" in d or "mode" in d and "not" in d for d in details), details)

    def test_required_fields_are_required_with_their_types(self):
        art_jar = Path("C:/nonexistent/x.jar")
        a = producer_shaped_task_a("c" * 40, "b" * 40, "CANDIDATE", art_jar, "a" * 64)
        b = producer_shaped_task_b("c" * 40, art_jar, "a" * 64, "sha256:" + "b" * 64, "linux/amd64")
        b["runtime_base_digest"] = "mcr.microsoft.com/openjdk/jdk@sha256:" + "e" * 64  # pinned, as CANDIDATE requires
        self.assertEqual(gov.task_a_schema_problems(a, "c" * 40, "b" * 40, gov.CANDIDATE), [])
        self.assertEqual(gov.task_b_schema_problems(b, "c" * 40, gov.CANDIDATE, False), [])
        # Missing `problems` is NOT an empty problems list.
        a1 = dict(a); del a1["problems"]
        self.assertTrue(any("problems" in p for p in gov.task_a_schema_problems(a1, "c" * 40, "b" * 40, gov.CANDIDATE)))
        # Missing platform / requested_platform is not "None == None".
        for key in ("platform", "requested_platform"):
            b1 = dict(b); del b1[key]
            self.assertTrue(any(key in p for p in gov.task_b_schema_problems(b1, "c" * 40, gov.CANDIDATE, False)), key)
        # Wrong types block.
        a2 = dict(a); a2["problems"] = "none"
        self.assertTrue(gov.task_a_schema_problems(a2, "c" * 40, "b" * 40, gov.CANDIDATE))
        b2 = dict(b); b2["hashes_equal"] = "true"
        self.assertTrue(gov.task_b_schema_problems(b2, "c" * 40, gov.CANDIDATE, False))
        b3 = dict(b); b3["staged_jar_path"] = 7
        self.assertTrue(gov.task_b_schema_problems(b3, "c" * 40, gov.CANDIDATE, False))

    def test_local_modes_are_ineligible_for_a_candidate_run(self):
        art_jar = Path("C:/nonexistent/x.jar")
        for mode in ("LOCAL_DEV", "LOCAL_PREPARATION"):
            a = producer_shaped_task_a("c" * 40, "b" * 40, mode, art_jar, "a" * 64)
            self.assertEqual(gov.task_a_schema_problems(a, "c" * 40, "b" * 40, gov.LOCAL_PREPARATION), [], mode)
            probs = gov.task_a_schema_problems(a, "c" * 40, "b" * 40, gov.CANDIDATE)
            self.assertTrue(any("mode" in p for p in probs), (mode, probs))
        b = producer_shaped_task_b("c" * 40, art_jar, "a" * 64, "sha256:" + "b" * 64, "linux/amd64")
        b["label"] = "SOMETHING_ELSE"
        self.assertTrue(gov.task_b_schema_problems(b, gov.LOCAL_PREPARATION, gov.LOCAL_PREPARATION, False))
        b2 = producer_shaped_task_b("c" * 40, art_jar, "a" * 64, "sha256:" + "b" * 64, "linux/amd64")
        b2["provenance"] = "unverified"
        self.assertTrue(gov.task_b_schema_problems(b2, "c" * 40, gov.CANDIDATE, False))

    def test_base_sha_is_cross_checked(self):
        art_jar = Path("C:/nonexistent/x.jar")
        a = producer_shaped_task_a("c" * 40, "b" * 40, "CANDIDATE", art_jar, "a" * 64)
        probs = gov.task_a_schema_problems(a, "c" * 40, "d" * 40, gov.CANDIDATE)
        self.assertTrue(any("b1_base_sha" in p for p in probs), probs)

    @NEEDS_DOCKER
    def test_real_jar_and_real_image_pass_evidence_binding(self):
        d = Deployable(None)
        try:
            d.write_task_evidence()
            res = d.run(run_input=d.run_input_manifest())
            self.assertEqual(kinds_for(res, "evidence-binding"), set(),
                             [f["detail"] for f in res["findings"] if f["obligation"] == "evidence-binding"])
            self.assertEqual(res["evidence"]["task_a"]["staged_jar_sha256"], "sha256:" + shared_artifacts().jar_sha)
            self.assertEqual(res["evidence"]["task_b"]["image_identity"], shared_artifacts().image_id)
            self.assertTrue(res["evidence"]["artifacts_verified"])
        finally:
            d.close()

    @NEEDS_DOCKER
    def test_jar_changed_after_evidence_capture_blocks(self):
        art = RealArtifacts()
        d = Deployable(None)
        try:
            d.write_task_evidence(artifacts=art)
            art.jar_path.write_bytes(b"replaced after capture")
            res = d.run(run_input=d.run_input_manifest())
            details = [f["detail"] for f in res["findings"] if f["obligation"] == "evidence-binding"]
            self.assertTrue(any("staged JAR" in x and "bytes" in x for x in details), details)
            self.assertEqual(res["overall_status"], gov.BLOCKED)
            self.assertIsNone(res["evidence"]["task_a"])
        finally:
            d.close()
            art.close()

    @NEEDS_DOCKER
    def test_valid_looking_but_nonexistent_jar_and_image_block(self):
        # The MEASURED false PASS: the fixture wrote `a...a` / `b...b` identities that named no
        # file and no image, and the real CLI returned PASS.
        d = Deployable(None)
        try:
            ghost = "sha256:" + "b" * 64
            def tamper(a, b):
                b["local_image_id"] = ghost
            d.write_task_evidence(tamper=tamper)
            res = d.run(run_input=d.run_input_manifest())
            details = [f["detail"] for f in res["findings"] if f["obligation"] == "evidence-binding"]
            self.assertTrue(any("image" in x for x in details), details)
            self.assertEqual(res["overall_status"], gov.BLOCKED)

            def tamper2(a, b):
                a["stage"]["staged_path"] = str(d.repo / "nope" / "ghost.jar")
                b["staged_jar_path"] = str(d.repo / "nope" / "ghost.jar")
            d.write_task_evidence(tamper=tamper2)
            res2 = d.run(run_input=d.run_input_manifest())
            details2 = [f["detail"] for f in res2["findings"] if f["obligation"] == "evidence-binding"]
            self.assertTrue(any("staged JAR" in x for x in details2), details2)
            self.assertEqual(res2["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    @NEEDS_DOCKER
    def test_image_whose_app_jar_differs_from_the_staged_jar_blocks(self):
        # Two real images; evidence names image B while the staged JAR is A's.
        other = RealArtifacts()
        d = Deployable(None)
        try:
            d.write_task_evidence(tamper=lambda a, b: b.__setitem__("local_image_id", other.image_id))
            res = d.run(run_input=d.run_input_manifest())
            details = [f["detail"] for f in res["findings"] if f["obligation"] == "evidence-binding"]
            self.assertTrue(any("/app.jar" in x for x in details), details)
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()
            other.close()

    def test_local_dev_task_a_supplied_to_a_candidate_run_blocks(self):
        # Schema-level: the run mode is threaded into evidence validation.
        d = Deployable(None)
        try:
            art_jar = Path("C:/nonexistent/x.jar")
            a = producer_shaped_task_a(d.head, d.head, "LOCAL_DEV", art_jar, "a" * 64)
            b = producer_shaped_task_b(d.head, art_jar, "a" * 64, "sha256:" + "b" * 64, "linux/amd64")
            d.write("evidence/taskA.json", json.dumps(a))
            d.write("evidence/taskB.json", json.dumps(b))
            _s, findings = gov.validate_run_input_evidence(
                d.run_input_manifest(), d.repo, d.head, None, gov.CANDIDATE, d.head)
            self.assertTrue(any("mode" in f.detail for f in findings), [f.detail for f in findings])
        finally:
            d.close()

    def test_malformed_run_input_json_is_a_clean_error_not_a_traceback(self):
        d = Deployable(None)
        try:
            d.write("scripts/policy.json", json.dumps(d.policy(d.head)))
            d.write("bad-run-input.json", "{ not json")
            proc = subprocess.run(
                [sys.executable, "-B", str(REPO / "scripts" / "check_b1_candidate_source.py"),
                 "--repo", str(d.repo), "--policy", str(d.repo / "scripts" / "policy.json"),
                 "--head", d.head, "--run-input", str(d.repo / "bad-run-input.json")],
                capture_output=True, text=True)
            self.assertEqual(proc.returncode, 1)
            self.assertIn("ERROR:", proc.stderr)
            self.assertNotIn("Traceback", proc.stderr)
        finally:
            d.close()


class PostConsolidationRecordGraphTests(unittest.TestCase):
    """Item 2: every record reachable from a latest record, a predecessor link or a claim reference
    is validated; the attestation is an exact partition; affected_claims name real claims and cover
    every claim whose Tier-0 fingerprint changed; identity covers every normative field."""

    def test_claim_referencing_an_invalid_older_record_is_rejected(self):
        # MEASURED: a valid revision 1 is latest; a revision-0 record with an empty reviewer and
        # the same digest was left unvalidated, and a disposition referencing ITS id passed.
        d = Deployable(JDBC_WRITER)
        try:
            res = d.run()
            f = subject_of(res, "jdbcTemplate.update")
            good = d.envelope_record(d.head, revision=1)
            bad = d.envelope_record(d.head, revision=0, reviewer="")
            disp = [make_disposition(f, bad, reviewer="codex")]  # the CLAIM is well-formed; the RECORD is not
            res2 = gov.run_all(d.repo, d.policy(d.head, envelopes=[good, bad], dispositions=disp),
                               None, d.head, gov.LOCAL_PREPARATION, None)
            still = subject_of(res2, "jdbcTemplate.update")
            self.assertIsNotNone(still, "a claim bound to an invalid record must not clear")
            self.assertEqual(still["kind"], gov.DISPOSITION_INVALID)
            self.assertEqual(res2["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_attestation_must_be_an_exact_partition(self):
        d = Deployable(JDBC_WRITER)
        try:
            members = [p for p, _ in d.envelope(d.head)["membership"]]
            cases = {
                "member in two buckets": {"analyzed": members, "non_runtime": members[:1], "unsupported": []},
                "duplicate inside a bucket": {"analyzed": members + members[:1], "non_runtime": [], "unsupported": []},
                "extra path not in envelope": {"analyzed": members + ["ghost/File.java"], "non_runtime": [], "unsupported": []},
                "bucket not a list": {"analyzed": members, "non_runtime": "none", "unsupported": []},
            }
            for label, att in cases.items():
                rec = d.envelope_record(d.head, attestation=att)
                res = gov.run_all(d.repo, d.policy(d.head, envelopes=[rec]), None, d.head,
                                  gov.LOCAL_PREPARATION, None)
                env_f = [x for x in res["findings"] if x["obligation"] == "envelope"]
                self.assertTrue(env_f, label)
                self.assertTrue(any("attestation" in x["detail"] for x in env_f), (label, [x["detail"] for x in env_f]))
        finally:
            d.close()

    def _renewal(self, d, r_old, r_new, affected):
        old_rec = d.envelope_record(r_old, revision=1)
        delta = gov.diff_envelope(d.envelope(r_old)["membership"], d.envelope(r_new)["membership"])
        new_rec = d.envelope_record(r_new, revision=2,
                                    previous_envelope_record_id=gov.envelope_record_identity(old_rec),
                                    reviewed_delta=delta, affected_claims=affected)
        return old_rec, new_rec

    def test_invented_affected_claim_is_rejected(self):
        d = Deployable(None)
        try:
            r_old = d.head
            d.write("common/src/main/java/Util.java", "class Util { int q = 1; }\n")
            d.commit("change")
            r_new = d.head
            old_rec, new_rec = self._renewal(d, r_old, r_new,
                                             [{"path": "svc/src/main/java/Ghost.java", "subject_id": "op:Ghost::m/0::x.save#0"}])
            res = gov.run_all(d.repo, d.policy(r_new, envelopes=[old_rec, new_rec]), None, r_new,
                              gov.LOCAL_PREPARATION, None)
            env_f = [x for x in res["findings"] if x["obligation"] == "envelope"]
            self.assertTrue(any("affected_claims" in x["detail"] for x in env_f), [x["detail"] for x in env_f])
        finally:
            d.close()

    def test_renewal_must_name_every_claim_whose_fingerprint_changed(self):
        d = Deployable(JDBC_WRITER)
        try:
            r_old = d.head
            d.write("svc/src/main/java/Writer.java",
                    JDBC_WRITER.replace("DELETE FROM portfolios WHERE id = ?", "DELETE FROM portfolios WHERE id = ? AND version = ?"))
            d.commit("the write itself changed")
            r_new = d.head
            # Empty affected set while the writer's Tier-0 fingerprint moved: rejected.
            old_rec, new_rec = self._renewal(d, r_old, r_new, [])
            res = gov.run_all(d.repo, d.policy(r_new, envelopes=[old_rec, new_rec]), None, r_new,
                              gov.LOCAL_PREPARATION, None)
            env_f = [x for x in res["findings"] if x["obligation"] == "envelope"]
            self.assertTrue(any("affected_claims" in x["detail"] for x in env_f), [x["detail"] for x in env_f])
            # Naming the changed claim: the envelope validates (the writer itself is still UNREVIEWED).
            res_probe = d.run(cut=r_new)
            wf = subject_of(res_probe, "jdbcTemplate.update")
            old_rec2, new_rec2 = self._renewal(d, r_old, r_new, [{"path": wf["path"], "subject_id": wf["subject_id"]}])
            res2 = gov.run_all(d.repo, d.policy(r_new, envelopes=[old_rec2, new_rec2]), None, r_new,
                               gov.LOCAL_PREPARATION, None)
            self.assertEqual([x for x in res2["findings"] if x["obligation"] == "envelope"], [],
                             [x["detail"] for x in res2["findings"]])
        finally:
            d.close()

    def test_envelope_only_change_with_explicit_empty_affected_claims_stays_valid(self):
        d = Deployable(JDBC_WRITER)
        try:
            r_old = d.head
            d.write("common/src/main/java/Util.java", "class Util { int q = 1; }\n")
            d.commit("dependency-only change")
            r_new = d.head
            old_rec, new_rec = self._renewal(d, r_old, r_new, [])
            res = gov.run_all(d.repo, d.policy(r_new, envelopes=[old_rec, new_rec]), None, r_new,
                              gov.LOCAL_PREPARATION, None)
            self.assertEqual([x for x in res["findings"] if x["obligation"] == "envelope"], [],
                             [x["detail"] for x in res["findings"]])
        finally:
            d.close()

    def test_predecessor_cycle_and_duplicate_revision_are_rejected(self):
        d = Deployable(None)
        try:
            r_old = d.head
            d.write("common/src/main/java/Util.java", "class Util { int q = 1; }\n")
            d.commit("change")
            r_new = d.head
            # Two distinct records claiming the same revision of the same envelope.
            a = d.envelope_record(r_new, revision=1, reviewer="alice")
            b = d.envelope_record(r_new, revision=1, reviewer="bob")
            res = gov.run_all(d.repo, d.policy(r_new, envelopes=[a, b]), None, r_new,
                              gov.LOCAL_PREPARATION, None)
            env_f = [x for x in res["findings"] if x["obligation"] == "envelope"]
            self.assertTrue(any("revision" in x["detail"] for x in env_f), [x["detail"] for x in env_f])
            # A renewal whose predecessor link points at itself.
            delta = gov.diff_envelope(d.envelope(r_old)["membership"], d.envelope(r_new)["membership"])
            rec = d.envelope_record(r_new, revision=2, reviewed_delta=delta, affected_claims=[])
            rec["previous_envelope_record_id"] = gov.envelope_record_identity(rec)
            res2 = gov.run_all(d.repo, d.policy(r_new, envelopes=[rec]), None, r_new,
                               gov.LOCAL_PREPARATION, None)
            env_f2 = [x for x in res2["findings"] if x["obligation"] == "envelope"]
            self.assertTrue(env_f2, "a self-referencing predecessor must not validate")
        finally:
            d.close()

    def test_reviewed_at_and_membership_digest_are_normative(self):
        d = Deployable(None)
        try:
            base = d.envelope_record(d.head)
            self.assertNotEqual(gov.envelope_record_identity(base),
                                gov.envelope_record_identity(dict(base, reviewed_at="2026-09-04")))
            self.assertNotEqual(gov.envelope_record_identity(base),
                                gov.envelope_record_identity(dict(base, membership_digest="sha256:" + "0" * 64)))
            # Non-normative, derivable fields do NOT move the identity.
            self.assertEqual(gov.envelope_record_identity(base),
                             gov.envelope_record_identity({k: v for k, v in base.items()
                                                           if k not in ("roots", "membership")}))
        finally:
            d.close()


SQL_MIGRATION_WRITER = "UPDATE portfolios SET version = version + 1 WHERE id = '00000000-0000-0000-0000-000000000001';\n"


class PostConsolidationHistoricalSubjectTests(unittest.TestCase):
    """Item 3: one historical subject index, built by the same extractors as the cut analysis,
    covering Java operations, entity setters AND resolvable SQL subjects."""

    def _sql_subject(self, res):
        return next(f for f in res["findings"] if f["subject_id"].startswith("sql:UPDATE"))

    def test_valid_static_sql_disposition_clears_and_an_altered_statement_reopens(self):
        d = Deployable(None, extra={"svc/src/main/resources/db/migration/V2__bump.sql": SQL_MIGRATION_WRITER})
        try:
            res = d.run()
            f = self._sql_subject(res)
            self.assertEqual(f["kind"], gov.UNREVIEWED)
            record = d.envelope_record(d.head)
            disp = [make_disposition(f, record)]
            # MEASURED: this valid SQL disposition at the same reviewed commit returned
            # "subject did not exist at reviewed_commit ...; the claim predates the code it approves".
            res2 = d.run(d.policy(d.head, dispositions=disp))
            self.assertEqual(res2["findings"], [], [x["detail"] for x in res2["findings"]])
            self.assertEqual(res2["overall_status"], gov.PASS)
            # Alter verb / table / predicate: the reviewed subject disappears (MISSING_SUBJECT) and a
            # new, unreviewed one appears -- the disposition does not carry over.
            for altered in (SQL_MIGRATION_WRITER.replace("UPDATE portfolios", "DELETE FROM portfolios").replace("SET version = version + 1 ", ""),
                            SQL_MIGRATION_WRITER.replace("portfolios", "asset_holdings"),
                            SQL_MIGRATION_WRITER.replace("WHERE id =", "WHERE id <>")):
                d.write("svc/src/main/resources/db/migration/V2__bump.sql", altered)
                d.commit("alter")
                res3 = gov.run_all(d.repo, d.policy(d.head, dispositions=disp), None, d.head,
                                   gov.LOCAL_PREPARATION, None)
                kinds = {x["kind"] for x in res3["findings"] if x["obligation"] == "writer-inventory"}
                self.assertIn(gov.MISSING_SUBJECT, kinds, altered)
                self.assertTrue(kinds & {gov.UNREVIEWED, gov.UNSUPPORTED}, altered)
                self.assertEqual(res3["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_sql_claim_predating_its_subject_is_rejected(self):
        d = Deployable(None)
        try:
            r_old = d.head
            d.write("svc/src/main/resources/db/migration/V2__bump.sql", SQL_MIGRATION_WRITER)
            d.commit("migration added after R")
            cut = d.head
            res = d.run(cut=cut)
            f = self._sql_subject(res)
            disp = [make_disposition(f, d.envelope_record(cut), reviewed_commit=r_old)]
            res2 = d.run(d.policy(cut, dispositions=disp), cut=cut)
            still = self._sql_subject(res2)
            self.assertEqual(still["kind"], gov.DISPOSITION_INVALID)
            self.assertIn("predates", still["detail"])
        finally:
            d.close()

    def test_java_claim_predating_its_subject_is_still_rejected(self):
        d = Deployable(None)
        try:
            r_old = d.head
            d.write("svc/src/main/java/Writer.java", JDBC_WRITER)
            d.commit("writer added after R")
            cut = d.head
            res = d.run(cut=cut)
            f = subject_of(res, "jdbcTemplate.update")
            disp = [make_disposition(f, d.envelope_record(cut), reviewed_commit=r_old)]
            still = subject_of(d.run(d.policy(cut, dispositions=disp), cut=cut), "jdbcTemplate.update")
            self.assertEqual(still["kind"], gov.DISPOSITION_INVALID)
        finally:
            d.close()

    def test_cut_and_historical_index_are_the_same_code_path(self):
        d = Deployable(JDBC_WRITER, extra={"svc/src/main/resources/db/migration/V2__bump.sql": SQL_MIGRATION_WRITER,
                                           "svc/src/main/java/Acct.java":
                                           "@Entity class Acct { private int q; public void setQ(int v) { this.q = v; } }\n"})
        try:
            res = d.run()
            at_cut = {f["path"] + "|" + f["subject_id"]: f["evidence"].get("code_fingerprint")
                      for f in res["findings"] if f["evidence"].get("code_fingerprint")}
            history = gov.HistoricalIndex(d.repo, gov.BlobReader(d.repo), d.policy(d.head))
            hist = history.at(d.head)
            self.assertTrue(any(k.split("|")[1].startswith("sql:") for k in hist), list(hist))
            self.assertTrue(any(k.split("|")[1].startswith("set:") for k in hist), list(hist))
            self.assertTrue(any("jdbcTemplate.update" in k for k in hist), list(hist))
            for key, fp in at_cut.items():
                self.assertEqual(hist.get(key), fp, key)
        finally:
            d.close()


class PostConsolidationSqlAwareReadTests(unittest.TestCase):
    """Item 4: SQL-bearing calls (JdbcTemplate.queryForObject, ...) classify by their resolved SQL,
    never by method name alone."""

    def test_select_one_is_read_only_and_accounts_for_its_receiver(self):
        d = Deployable("class W {\n  private JdbcTemplate jdbcTemplate;\n"
                       "  Object m() { return jdbcTemplate.queryForObject(\"SELECT 1\", Integer.class); }\n}\n")
        try:
            res = d.run()
            self.assertEqual(res["findings"], [], [f["detail"] for f in res["findings"]])
            self.assertEqual(res["overall_status"], gov.PASS)
        finally:
            d.close()

    def test_delete_returning_through_query_for_object_is_a_writer(self):
        d = Deployable("class W {\n  private JdbcTemplate jdbcTemplate;\n"
                       "  Object m() { return jdbcTemplate.queryForObject("
                       "\"DELETE FROM asset_holdings WHERE id = ? RETURNING id\", UUID.class, id); }\n}\n")
        try:
            res = d.run()
            f = subject_of(res, "jdbcTemplate.queryForObject")
            self.assertIsNotNone(f, [x["subject_id"] for x in res["findings"]])
            self.assertEqual(f["obligation"], "writer-inventory")
            self.assertEqual(f["kind"], gov.UNREVIEWED)
            self.assertIn("asset_holdings", f["evidence"]["basis"]["direct_tables"])
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_recognized_write_on_the_same_receiver_cannot_hide_a_query_writer(self):
        # MEASURED: with a dispositioned `update` on the same receiver, the DELETE...RETURNING
        # through queryForObject was skipped by name and the run returned PASS with zero findings.
        d = Deployable("class W {\n  private JdbcTemplate jdbcTemplate;\n"
                       "  void w() { jdbcTemplate.update(\"UPDATE portfolios SET version = version + 1 WHERE id = ?\"); }\n"
                       "  Object q() { return jdbcTemplate.queryForObject("
                       "\"DELETE FROM asset_holdings WHERE id = ? RETURNING id\", UUID.class, id); }\n}\n")
        try:
            res = d.run()
            upd = subject_of(res, "jdbcTemplate.update")
            disp = [make_disposition(upd, d.envelope_record(d.head))]
            res2 = d.run(d.policy(d.head, dispositions=disp))
            self.assertIsNone(subject_of(res2, "jdbcTemplate.update"))
            q = subject_of(res2, "jdbcTemplate.queryForObject")
            self.assertIsNotNone(q, "the query-path DELETE must survive a disposition on the update")
            self.assertEqual(res2["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_unresolved_query_sql_blocks_as_unsupported(self):
        d = Deployable("class W {\n  private JdbcTemplate jdbcTemplate;\n"
                       "  Object m(String sql) { return jdbcTemplate.queryForObject(sql, Integer.class); }\n}\n")
        try:
            res = d.run()
            f = subject_of(res, "jdbcTemplate.queryForObject")
            self.assertIsNotNone(f, [x["subject_id"] for x in res["findings"]])
            self.assertEqual(f["kind"], gov.UNSUPPORTED)
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_select_invoking_a_persistent_mutating_function_is_not_read_only(self):
        d = Deployable("class W {\n  private JdbcTemplate jdbcTemplate;\n"
                       "  Object m() { return jdbcTemplate.queryForObject(\"SELECT repair_migrate_holdings('a','b','c')\", Integer.class); }\n}\n",
                       extra={"svc/src/main/resources/db/migration/V2__fn.sql":
                              "CREATE OR REPLACE FUNCTION repair_migrate_holdings(a text, b text, c text) RETURNS void AS $$\n"
                              "BEGIN UPDATE asset_holdings SET ticker = b WHERE ticker = a; END; $$ LANGUAGE plpgsql;\n"})
        try:
            res = d.run()
            f = subject_of(res, "jdbcTemplate.queryForObject")
            self.assertIsNotNone(f, [x["subject_id"] for x in res["findings"]])
            self.assertEqual(res["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    # ---- follow-up review: read-only is decided AFTER receiver/routine resolution ---------------
    def _demoted_read(self, src):
        d = Deployable(src)
        try:
            res = d.run()
            f = subject_of(res, "db.queryForObject")
            self.assertIsNotNone(f, [x["subject_id"] for x in res["findings"]])
            self.assertEqual(res["overall_status"], gov.BLOCKED)
            return f
        finally:
            d.close()

    def test_known_jdbc_receiver_with_builtin_read_expressions_stays_a_positive(self):
        d = Deployable("class W {\n  private JdbcTemplate db;\n"
                       "  Object a() { return db.queryForObject(\"SELECT 1\", Integer.class); }\n"
                       "  Object b() { return db.queryForObject(\"SELECT count(*) FROM portfolios WHERE id = ?\", Long.class, id); }\n"
                       "  Object c() { return db.queryForObject(\"SELECT coalesce(max(version), 0) FROM portfolios\", Long.class); }\n"
                       "  Object d() { return db.queryForList(\"SELECT id, lower(ticker) FROM asset_holdings WHERE created_at > now() - interval '1 day'\"); }\n}\n")
        try:
            res = d.run()
            self.assertEqual(res["findings"], [], [f["detail"] for f in res["findings"]])
            self.assertEqual(res["overall_status"], gov.PASS)
            self.assertEqual(res["coverage"]["read_only_statements"], 4)
        finally:
            d.close()

    def test_unknown_receiver_type_cannot_inherit_jdbc_read_semantics(self):
        # MEASURED: `MysteryDb db` + SELECT 1 returned PASS with zero findings.
        f = self._demoted_read('class W { private MysteryDb db; Object m() { return db.queryForObject("SELECT 1", Integer.class); } }')
        self.assertEqual(f["kind"], gov.UNRESOLVED)
        self.assertIn("receiver type could not be resolved", f["evidence"]["basis"]["reason"])
        self.assertIn("read_only_statement_demoted", f["evidence"]["basis"])

    def test_undeclared_receiver_cannot_inherit_jdbc_read_semantics(self):
        # MEASURED: no declaration for `db` + SELECT 1 returned PASS with zero findings.
        f = self._demoted_read('class W { Object m() { return db.queryForObject("SELECT 1", Integer.class); } }')
        self.assertEqual(f["kind"], gov.UNRESOLVED)
        self.assertIn("receiver type could not be resolved", f["evidence"]["basis"]["reason"])

    def test_routine_absent_from_migrations_does_not_inherit_purity(self):
        # MEASURED: SELECT external_mutator() with no definition in the tree returned PASS.
        d = Deployable('class W { private JdbcTemplate db; Object m() { return db.queryForObject("SELECT external_mutator()", Integer.class); } }')
        try:
            res = d.run()
            f = subject_of(res, "db.queryForObject")
            self.assertIsNotNone(f, [x["subject_id"] for x in res["findings"]])
            self.assertEqual(f["obligation"], "writer-inventory")
            # Not read-only -> classified as a writer; with no resolvable table set it is UNRESOLVED.
            self.assertEqual(f["kind"], gov.UNRESOLVED)
            self.assertEqual(res["overall_status"], gov.BLOCKED)
            problem = gov.sql_read_only_problem(("SELECT external_mutator()"), set())
            self.assertIn("external_mutator()", problem)
            self.assertIn("cannot establish", problem)
        finally:
            d.close()

    def test_read_only_problem_reasons(self):
        rop = gov.sql_read_only_problem
        self.assertIsNone(rop(("SELECT count(*) FROM t WHERE name = 'f(x)'")))
        self.assertIn("lead", rop(("DELETE FROM t WHERE id = 1 RETURNING id")))
        self.assertIn("DELETE", rop(("WITH d AS (DELETE FROM t WHERE id = 1 RETURNING id) SELECT count(*) FROM d")))
        self.assertIn("persistent routine", rop(("SELECT repair_migrate_holdings('a','b','c')"), {"repair_migrate_holdings"}))
        self.assertIn("cannot establish", rop(("SELECT nextval_like()")))
        self.assertIn("non-read construct", rop(("SELECT 1 INTO t")))
        self.assertIn("lead", rop(("CALL p()")))

    # ---- round 6 review: routine identity is the COMPLETE name -------------------------------
    UNKNOWN_ROUTINE_FORMS = (
        ('SELECT "external_mutator"()', "quoted-identifier routine"),                 # MEASURED false PASS
        ("SELECT custom_schema.lower('value')", "routine in schema custom_schema"),    # MEASURED false PASS
        ('SELECT "custom_schema"."lower"(\'v\')', "quoted-identifier routine"),
        ("SELECT custom_schema . lower /* qualified */ ('v')", "routine in schema custom_schema"),
        ("SELECT custom_schema.\n  lower('v') -- trailing note\n", "routine in schema custom_schema"),
        ('SELECT "lower"(\'v\')', "quoted-identifier routine"),
        ("SELECT pg_catalog.external_mutator()", "cannot establish"),
        ("SELECT other.pg_catalog.lower('v')", "routine in schema other.pg_catalog"),
    )

    def test_quoted_and_qualified_routine_names_are_complete_names(self):
        rop = gov.sql_read_only_problem
        for sql, needle in self.UNKNOWN_ROUTINE_FORMS:
            problem = rop((sql))
            self.assertIsNotNone(problem, sql)
            self.assertIn(needle, problem, (sql, problem))
        # Supported forms: unqualified bare built-ins, and the same built-ins under pg_catalog.
        for sql in ("SELECT lower('value')", "SELECT pg_catalog.lower('value')",
                    "SELECT count(*) FROM portfolios", "SELECT PG_CATALOG.COUNT(*) FROM t",
                    "SELECT count(*) FROM t WHERE name = 'f(x)' -- note(x)",
                    "SELECT count(*) FROM t WHERE q = $$ mutate() $$ /* mutate() */",
                    "SELECT (1 + 2) * 3", "SELECT id FROM t WHERE (a = 1) AND (b = 2)"):
            self.assertIsNone(rop((sql)), sql)
        # Unsupported literal/comment syntax blocks rather than looking like an absence of calls.
        for sql in ("SELECT E'\\'' || external_mutator()", "SELECT 'unterminated", "SELECT 1 /* open"):
            problem = rop((sql))
            self.assertIsNotNone(problem, sql)
            self.assertIn("does not model", problem, (sql, problem))

    def test_sql_call_names_preserve_qualifiers_and_quoting(self):
        names = gov.sql_call_names(gov.lex_sql(
            'SELECT a.b.c(1), "Q"(2), lower(x), (y), count(*) FROM t WHERE s = \'z(\''))
        self.assertEqual(names, [[("a", False), ("b", False), ("c", False)], [("Q", True)],
                                 [("lower", False)], [("count", False)]])

    def test_measured_quoted_and_qualified_reads_block_on_their_subject(self):
        for sql in ('SELECT "external_mutator"()', "SELECT custom_schema.lower('value')"):
            java_literal = '"' + sql.replace("\\", "\\\\").replace('"', '\\"') + '"'  # valid Java source
            d = Deployable('class W { private JdbcTemplate db; Object m() { return db.queryForObject(' + java_literal + ', Integer.class); } }')
            try:
                res = d.run()
                f = subject_of(res, "db.queryForObject")
                self.assertIsNotNone(f, (sql, [x["subject_id"] for x in res["findings"]]))
                self.assertEqual(f["kind"], gov.UNRESOLVED, sql)
                self.assertEqual(res["overall_status"], gov.BLOCKED, sql)
            finally:
                d.close()

    # ---- round 7 review: lexical boundaries on the ORIGINAL SQL -------------------------------
    @staticmethod
    def _java_source(sql: str) -> str:
        # json.dumps with ensure_ascii=False gives a valid Java string literal: newlines become \n
        # escapes and non-ASCII identifier characters reach the analyzer intact.
        return ('class W { private JdbcTemplate db; Object m() { return db.queryForObject('
                + json.dumps(sql, ensure_ascii=False) + ', Object.class); } }')

    # (sql, reason needle, expected final kind, expected obligation). Lexical failures are UNSUPPORTED
    # COVERAGE (writer-coverage) with their reason; unknown routines are UNRESOLVED effects.
    LEXICAL_FALSE_PASSES = (
        ("SELECT 1 -- ordinary note\n + external_mutator()", "external_mutator()", gov.UNRESOLVED, "writer-inventory"),
        ("SELECT 1 /* outer /* inner */ -- still inside outer */ + external_mutator()", "external_mutator()", gov.UNRESOLVED, "writer-inventory"),
        ("SELECT élower('value')", "does not model", gov.UNSUPPORTED, "writer-coverage"),
        ("SELECT 函数()", "does not model", gov.UNSUPPORTED, "writer-coverage"),
        ("SELECT evil$tag$body$tag$()", "evil$tag$body$tag$()", gov.UNRESOLVED, "writer-inventory"),
    )

    def _run_sql_fixture(self, sql, policy_extra=None):
        d = Deployable(self._java_source(sql))
        try:
            res = d.run(d.policy(d.head, **(policy_extra or {})) if policy_extra else None)
            f = subject_of(res, "db.queryForObject")
            return res, f, d
        except Exception:
            d.close()
            raise

    def test_measured_lexical_false_passes_block_on_their_subject(self):
        for sql, needle, kind, obligation in self.LEXICAL_FALSE_PASSES:
            problem = gov.sql_read_only_problem(sql)
            self.assertIsNotNone(problem, sql)
            self.assertIn(needle, problem, (sql, problem))
            res, f, d = self._run_sql_fixture(sql)
            try:
                self.assertIsNotNone(f, (sql, [x["subject_id"] for x in res["findings"]]))
                self.assertEqual(f["kind"], kind, (sql, f))
                self.assertEqual(f["obligation"], obligation, (sql, f))
                self.assertIn(needle, f["detail"] if obligation == "writer-coverage" else f["evidence"]["basis"]["reason"], (sql, f))
                self.assertEqual(res["overall_status"], gov.BLOCKED, sql)
            finally:
                d.close()

    # ---- round 8 review: the assessment must survive into the final classification ----------
    ROUND8_ROWS = (
        # (sql, expected kind, expected obligation, needle in the reason)
        ("SELECT external_mutator()", gov.UNRESOLVED, "writer-inventory", "external_mutator()"),
        ("SELECT external_mutator() /* UPDATE market_prices SET x=1 */", gov.UNRESOLVED, "writer-inventory", "external_mutator()"),
        ("SELECT 函数() /* UPDATE market_prices SET x=1 */", gov.UNSUPPORTED, "writer-coverage", "does not model"),
        ("SELECT external_mutator(), 'UPDATE market_prices SET x=1'", gov.UNRESOLVED, "writer-inventory", "external_mutator()"),
        ("DELETE FROM market_prices WHERE external_mutator() RETURNING id", gov.UNRESOLVED, "writer-inventory", "external_mutator()"),
        # relevant-table spellings in the incidental text change nothing about the severity
        ("SELECT external_mutator() /* UPDATE portfolios SET x=1 */", gov.UNRESOLVED, "writer-inventory", "external_mutator()"),
        ("SELECT external_mutator(), 'DELETE FROM asset_holdings'", gov.UNRESOLVED, "writer-inventory", "external_mutator()"),
        ("SELECT 函数() /* DELETE FROM asset_holdings */", gov.UNSUPPORTED, "writer-coverage", "does not model"),
    )

    def test_sql_assessment_survives_into_the_final_classification(self):
        for sql, kind, obligation, needle in self.ROUND8_ROWS:
            res, f, d = self._run_sql_fixture(sql)
            try:
                self.assertIsNotNone(f, (sql, [x["subject_id"] for x in res["findings"]]))
                self.assertEqual(f["kind"], kind, (sql, f))
                self.assertEqual(f["obligation"], obligation, (sql, f))
                reason = f["detail"] if obligation == "writer-coverage" else f["evidence"]["basis"]["reason"]
                self.assertIn(needle, reason, (sql, f))
                self.assertEqual(res["overall_status"], gov.BLOCKED, sql)
            finally:
                d.close()

    def test_dml_shaped_comment_or_literal_text_is_not_a_target(self):
        for sql in ("SELECT external_mutator() /* UPDATE market_prices SET x=1 */",
                    "SELECT external_mutator(), 'UPDATE market_prices SET x=1'",
                    "SELECT 1 -- DELETE FROM asset_holdings"):
            facts = gov.sql_facts(sql)
            self.assertEqual(facts["verbs"], [], sql)
            self.assertEqual(facts["target_tables"], [], sql)
            self.assertIn("market_prices" if "market" in sql else "asset_holdings", facts["sql"],
                          "literal/comment text stays in the fingerprint identity")
        facts = gov.sql_facts("SELECT 函数() /* UPDATE market_prices SET x=1 */")
        self.assertIn("lexical_error", facts)
        self.assertEqual(facts["target_tables"], [])
        real = gov.assess_sql("DELETE FROM market_prices WHERE external_mutator() RETURNING id")
        self.assertEqual(real["coverage"], gov.RESOLVED)
        self.assertEqual(real["facts"]["target_tables"], ["market_prices"])
        self.assertEqual(real["unknown_routines"], ["external_mutator()"])
        self.assertEqual(real["facts"]["unknown_routines"], ["external_mutator()"])
        self.assertEqual(gov.assess_sql("SELECT 函数()")["coverage"], gov.UNSUPPORTED)
        self.assertEqual(gov.sql_facts("INSERT INTO t (a) VALUES (1) ON CONFLICT (a) DO UPDATE SET a = 2")["target_tables"], ["t"])
        self.assertEqual(gov.sql_facts("SELECT id FROM t FOR UPDATE")["verbs"], [])

    def test_lexical_failure_cannot_be_cleared_by_a_disposition_or_effect_resolution(self):
        sql = "SELECT 函数() /* UPDATE market_prices SET x=1 */"
        res, f, d = self._run_sql_fixture(sql)
        try:
            self.assertEqual(f["kind"], gov.UNSUPPORTED)
            record = d.envelope_record(d.head)
            # A schema-complete, correctly fingerprinted disposition AND an effect resolution.
            ops = operations_of(d.repo.joinpath("svc/src/main/java/Writer.java").read_text(encoding="utf-8"))
            real_fp = next(v["fingerprint"] for k, v in ops.items() if "db.queryForObject" in k)
            disp = [make_disposition(f, record, fingerprint=real_fp)]
            eff = [dict(make_disposition(f, record, fingerprint=real_fp, obligation="effect-resolution"),
                        resolved_effect=gov.UNRELATED)]
            res2 = d.run(d.policy(d.head, dispositions=disp, effect_resolutions=eff))
            still = subject_of(res2, "db.queryForObject")
            self.assertIsNotNone(still, "a lexical failure must stay a blocking coverage finding")
            self.assertEqual(still["kind"], gov.UNSUPPORTED)
            self.assertEqual(still["obligation"], "writer-coverage")
            self.assertEqual(res2["overall_status"], gov.BLOCKED)
        finally:
            d.close()

    def test_known_direct_target_plus_unknown_routine_stays_unresolved_but_plain_disjoint_write_lists(self):
        # Unknown routine with a real, unrelated direct target: UNRESOLVED (effects not all disjoint).
        res, f, d = self._run_sql_fixture("DELETE FROM market_prices WHERE external_mutator() RETURNING id")
        try:
            self.assertEqual(f["kind"], gov.UNRESOLVED)
            self.assertEqual(f["evidence"]["basis"]["unknown_routines"], ["external_mutator()"])
            self.assertEqual(gov.sql_facts("DELETE FROM market_prices WHERE external_mutator() RETURNING id")["target_tables"],
                             ["market_prices"], "the direct target IS known; the routine still keeps it unresolved")
        finally:
            d.close()
        # The same write WITHOUT the routine is a plain disjoint write: listed, not blocking.
        d2 = Deployable('class W { private JdbcTemplate db; void m() { db.update("DELETE FROM market_prices WHERE id = ?"); } }')
        try:
            res2 = d2.run()
            self.assertIsNone(subject_of(res2, "db.update"))
            self.assertEqual(res2["overall_status"], gov.PASS)
            self.assertTrue(any("db.update" in u["subject_id"] for u in res2["unrelated_inventory"]))
        finally:
            d2.close()

    def test_comment_at_a_token_boundary_never_turns_an_unknown_routine_into_a_read(self):
        rop = gov.sql_read_only_problem
        base = "SELECT external_mutator()"
        self.assertIn("external_mutator()", rop(base))
        variants = [
            "SELECT 1 -- note\n + external_mutator()", "SELECT 1 -- note\r\n + external_mutator()",
            "SELECT 1 -- note\r + external_mutator()",
            "SELECT /* c */ external_mutator()", "SELECT external_mutator /* c */ ()",
            "SELECT external_mutator() -- trailing", "SELECT external_mutator() /* trailing */",
            "SELECT /* a /* nested */ b */ external_mutator()",
            "SELECT 1 /* x */ + /* y */ external_mutator() -- z",
            "-- leading\nSELECT external_mutator()",
        ]
        for sql in variants:
            problem = rop(sql)
            self.assertIsNotNone(problem, sql)
            self.assertIn("external_mutator()", problem, (sql, problem))
        # ...and comments/literals shaped like calls never BECOME calls (positives retained).
        for sql in ("SELECT 1 -- external_mutator()", "SELECT 1 /* external_mutator() */",
                    "SELECT 1 /* a /* external_mutator() */ b */", "SELECT 'external_mutator()'",
                    "SELECT $$external_mutator()$$", "SELECT $t$ mutate() $t$",
                    "SELECT count(*) FROM t WHERE name = 'f(x)' -- note(x)",
                    "SELECT 1 -- note\n + 2", "SELECT (1 + 2) * 3", "SELECT $1 + 1"):
            self.assertIsNone(rop(sql), (sql, rop(sql)))

    def test_extending_a_builtin_spelling_never_keeps_its_clearance(self):
        rop = gov.sql_read_only_problem
        self.assertIsNone(rop("SELECT lower('v')"))
        for sql in ("SELECT lowerx('v')", "SELECT lower_('v')", "SELECT lower$x('v')", "SELECT lower$('v')",
                    "SELECT evil$tag$body$tag$()", "SELECT x$1()", "SELECT lower1('v')"):
            problem = rop(sql)
            self.assertIsNotNone(problem, sql)
            self.assertIn("cannot establish", problem, (sql, problem))
        for sql in ("SELECT élower('v')", "SELECT loweré('v')", "SELECT 函数()",
                    "SELECT lowér('v')", "SELECT 1é()"):
            problem = rop(sql)
            self.assertIsNotNone(problem, sql)
            self.assertIn("does not model", problem, (sql, problem))

    def test_unsupported_lexical_forms_block_instead_of_scanning_empty(self):
        rop = gov.sql_read_only_problem
        for sql in ("SELECT 1 /* open", "SELECT 'open", 'SELECT "open', "SELECT $t$ open", "SELECT $x",
                    "SELECT E'\\'' || f()", "SELECT B'01'", "SELECT X'ff'", "SELECT N'x'", "SELECT U&'x'",
                    'SELECT U&"x"()', "SELECT 1abc()", 'SELECT ""()', "SELECT ‘x’", ""):
            problem = rop(sql)
            self.assertIsNotNone(problem, sql)
            self.assertTrue("does not model" in problem or "empty" in problem, (sql, problem))
        with self.assertRaises(TypeError):
            gov.sql_read_only_problem(gov.sql_facts("SELECT 1"))  # normalized facts are the wrong input by construction

    def test_lex_sql_accounts_for_every_span(self):
        toks = gov.lex_sql("SELECT a.b(1), \"Q\"('x'), $1, 1.5e3, $$body$$ -- c\n/* d */ FROM t")
        self.assertEqual(toks, [("ident", "select"), ("ident", "a"), ("punct", "."), ("ident", "b"),
                                ("punct", "("), ("number", "1"), ("punct", ")"), ("punct", ","),
                                ("qident", "Q"), ("punct", "("), ("literal", "'x'"), ("punct", ")"),
                                ("punct", ","), ("param", "$1"), ("punct", ","), ("number", "1.5e3"),
                                ("punct", ","), ("literal", "$$body$$"), ("ident", "from"), ("ident", "t")])

    # ---- round 9 review: executable migration blocks always produce a subject -------------------
    MIGRATION_BLOCKS = (
        # (migration SQL, expected subject label prefix, expected kind, needle in detail)
        ("DO $$ BEGIN DELETE FROM portfolios; END; $$;", "sql:DELETE FROM", gov.UNREVIEWED, "mutation site"),  # control
        ("DO 'BEGIN DELETE FROM portfolios; END;';", "sql:DELETE FROM", gov.UNREVIEWED, "mutation site"),          # MEASURED
        ("DO LANGUAGE plpgsql $$ BEGIN DELETE FROM portfolios; END; $$;", "sql:DELETE FROM", gov.UNREVIEWED, "mutation site"),  # MEASURED
        ("DO $$ BEGIN DELETE FROM portfolios; END; $$ LANGUAGE plpgsql;", "sql:DELETE FROM", gov.UNREVIEWED, "mutation site"),
        ("DO $$ BEGIN PERFORM external_mutator(); END; $$;", "sql:CALL", gov.UNREVIEWED, "external_mutator()"),  # MEASURED
        ("DO $$ BEGIN RAISE NOTICE 'hello'; END; $$;", "sql:DO", gov.UNREVIEWED, "executable DO block"),
        ("DO LANGUAGE plpython3u $$ import os $$;", "sql:UNSUPPORTED", gov.UNSUPPORTED, "plpython3u"),
        ("DO LANGUAGE plpgsql;", "sql:UNSUPPORTED", gov.UNSUPPORTED, "no body literal"),
        ("CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql AS 'BEGIN DELETE FROM portfolios; END;';",
         "sql:CREATE FUNCTION,DELETE FROM", gov.UNREVIEWED, "mutation site"),
        ("CREATE FUNCTION f() RETURNS void AS 'sym', 'lib' LANGUAGE c;", "sql:UNSUPPORTED", gov.UNSUPPORTED, "'c'"),
        ("SELECT external_mutator();", "sql:CALL", gov.UNREVIEWED, "external_mutator()"),
    )

    def test_executable_migration_blocks_always_produce_a_subject(self):
        for sql, label_prefix, kind, needle in self.MIGRATION_BLOCKS:
            d = Deployable(None, extra={"svc/src/main/resources/db/migration/V2__block.sql": sql + "\n"})
            try:
                res = d.run()
                subjects = [f for f in res["findings"] if f["subject_id"].startswith("sql:")]
                self.assertEqual(len(subjects), 1, (sql, [f["subject_id"] for f in res["findings"]]))
                f = subjects[0]
                self.assertTrue(f["subject_id"].startswith(label_prefix), (sql, f["subject_id"]))
                self.assertEqual(f["kind"], kind, (sql, f))
                self.assertIn(needle, f["detail"], (sql, f["detail"]))
                self.assertEqual(res["overall_status"], gov.BLOCKED, sql)
            finally:
                d.close()

    def test_ordinary_literals_in_migrations_stay_data(self):
        # A dollar literal / single-quoted literal in a normal statement is data: one INSERT subject
        # targeting `notes`, no invented DELETE, no CALL subject.
        d = Deployable(None, extra={"svc/src/main/resources/db/migration/V2__data.sql":
                                    "INSERT INTO notes (body) VALUES ($$DELETE FROM portfolios$$), ('external_mutator()');\n"
                                    "SELECT 1;\n"})
        try:
            res = d.run()
            subjects = [f for f in res["findings"] if f["subject_id"].startswith("sql:")]
            self.assertEqual([f["subject_id"].split(":")[1] for f in subjects], ["INSERT INTO"])
            self.assertEqual(subjects[0]["evidence"]["target_tables"], ["notes"])
        finally:
            d.close()

    def test_table_references_with_column_lists_are_not_calls(self):
        names = lambda s: [".".join(seg for seg, _q in c) for c in gov.sql_call_names(gov.lex_sql(s))]  # noqa: E731
        for sql in ("CREATE TABLE IF NOT EXISTS market_price_history (id uuid DEFAULT gen_random_uuid())",
                    'CREATE TABLE "ba_user" (id text PRIMARY KEY)',
                    "CREATE INDEX ix ON portfolios (user_id)",
                    "CREATE UNIQUE INDEX IF NOT EXISTS ix ON ONLY portfolios (user_id)",
                    "INSERT INTO users (id) VALUES (1)",
                    "ALTER TABLE ONLY t ADD CONSTRAINT c FOREIGN KEY (a) REFERENCES u (id)"):
            self.assertEqual([n for n in names(sql) if n not in gov.READ_ONLY_SQL_BUILTINS], [], sql)
        # ...while a routine after ON in a join condition, or in FROM, is still a call.
        self.assertIn("external_mutator", names("SELECT * FROM a JOIN b ON external_mutator(a.id)"))
        self.assertIn("external_mutator", names("SELECT * FROM external_mutator(1)"))
        self.assertIsNone(gov.sql_read_only_problem("SELECT gen_random_uuid()"))

    def test_body_expansion_recognizes_do_and_as_bodies_only(self):
        ex = lambda s: gov._expand_procedural_bodies(gov.lex_sql(s))  # noqa: E731
        self.assertIn(("ident", "delete"), ex("DO 'BEGIN DELETE FROM t; END;'"))
        self.assertIn(("ident", "delete"), ex("DO LANGUAGE plpgsql $$ BEGIN DELETE FROM t; END; $$"))
        self.assertIn(("ident", "delete"), ex("DO $$ BEGIN DELETE FROM t; END; $$ LANGUAGE plpgsql"))
        self.assertIn(("ident", "delete"), ex("CREATE OR REPLACE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$ BEGIN DELETE FROM t; END; $$"))
        self.assertNotIn(("ident", "delete"), ex("SELECT $$DELETE FROM t$$"))
        self.assertNotIn(("ident", "delete"), ex("INSERT INTO n (b) VALUES ('DELETE FROM t')"))
        with self.assertRaises(gov.SqlLexError):
            ex("DO LANGUAGE plperl $$ 1 $$")
        with self.assertRaises(gov.SqlLexError):
            ex("DO LANGUAGE plpgsql")
        self.assertEqual(gov.assess_sql("DO $$ BEGIN PERFORM external_mutator(); END; $$")["unknown_routines"],
                         ["external_mutator()"])

    def test_prepared_statement_with_dml_is_a_writer_and_chained_execute_stays_unsupported(self):
        ops = operations_of('class W { void m() { PreparedStatement ps = c.prepareStatement("DELETE FROM portfolios"); ps.execute(); } }')
        prep = [v for k, v in ops.items() if "prepareStatement" in k]
        self.assertTrue(prep, list(ops))
        self.assertEqual(prep[0]["coverage"], gov.RESOLVED)
        self.assertEqual(prep[0]["statement"]["target_tables"], ["portfolios"])
        self.assertTrue(any(v["coverage"] == gov.UNSUPPORTED for k, v in ops.items() if "ps.execute" in k), list(ops))

    def test_jpql_write_with_unmapped_entity_is_unresolved(self):
        d = Deployable("class W {\n  private EntityManager em;\n"
                       "  void m() { em.createQuery(\"UPDATE Mystery m SET m.x = 1\").executeUpdate(); }\n}\n")
        try:
            res = d.run()
            f = subject_of(res, "em.createQuery")
            self.assertIsNotNone(f, [x["subject_id"] for x in res["findings"]])
            self.assertEqual(f["kind"], gov.UNRESOLVED)
        finally:
            d.close()


class SqlExecutableCommentRegressionTests(unittest.TestCase):
    def test_leading_comments_cannot_hide_executable_migration_blocks(self):
        prefixes = ("", "-- migration note\n", "-- migration note\r\n",
                    "-- migration note\r", "/* migration note */ ",
                    "/* outer /* nested */ comment */ ")
        bodies = (("DO $$ BEGIN EXECUTE 'DELETE FROM portfolios'; END; $$;", gov.UNSUPPORTED),
                  ("DO $$ BEGIN RAISE NOTICE 'hello'; END; $$;", gov.UNREVIEWED))
        for prefix in prefixes:
            for body, expected_kind in bodies:
                with self.subTest(prefix=prefix, body=body):
                    d = Deployable(None, extra={"svc/src/main/resources/db/migration/V2__block.sql":
                                                prefix + body})
                    try:
                        result = d.run()
                        subjects = [f for f in result["findings"] if f["subject_id"].startswith("sql:")]
                        self.assertEqual(len(subjects), 1, result["findings"])
                        self.assertEqual(subjects[0]["kind"], expected_kind)
                        self.assertEqual(result["overall_status"], gov.BLOCKED)
                    finally:
                        d.close()

    def test_dynamic_execution_is_unsupported_before_subject_selection(self):
        bodies = ("DO $$ BEGIN EXECUTE /* command */ 'DELETE FROM portfolios'; END; $$;",
                  "DO $body$ BEGIN EXECUTE $$DELETE FROM portfolios$$; END; $body$;",
                  "DO $$ DECLARE cmd text := 'DELETE FROM portfolios'; BEGIN EXECUTE cmd; END; $$;",
                  # Beyond the transferred patch: a top-level EXECUTE runs a command this analyzer
                  # never saw, so it is unmodeled execution rather than an absent statement.
                  "EXECUTE some_prepared_stmt;",
                  "EXECUTE stmt USING 'x';")
        for body in bodies:
            with self.subTest(body=body):
                assessment = gov.assess_sql(body)
                self.assertEqual(assessment["coverage"], gov.UNSUPPORTED)
                self.assertIn("dynamic SQL", assessment["read_only_problem"])
                subjects = gov.extract_sql_subjects("svc/src/main/resources/db/migration/V2__block.sql", body)
                self.assertEqual(len(subjects), 1)
                self.assertEqual(subjects[0].kind, gov.UNSUPPORTED)

    def test_unsupported_execution_labels_do_not_move_existing_subject_ids(self):
        # A verb-less unmodeled statement is labelled UNSUPPORTED, while a verb-bearing dynamic
        # function keeps its verb label so V17's existing subject ids do not move.
        dyn_do = gov.extract_sql_subjects("V2.sql", "DO $$ BEGIN EXECUTE 'DELETE FROM t'; END; $$;")[0]
        self.assertTrue(dyn_do.subject_id.startswith("sql:UNSUPPORTED:"), dyn_do.subject_id)
        top = gov.extract_sql_subjects("V2.sql", "EXECUTE some_prepared_stmt;")[0]
        self.assertTrue(top.subject_id.startswith("sql:UNSUPPORTED:"), top.subject_id)
        fn = gov.extract_sql_subjects(
            "V2.sql", "CREATE OR REPLACE FUNCTION f() RETURNS void AS $$ BEGIN EXECUTE format('x'); "
                      "END; $$ LANGUAGE plpgsql;")[0]
        self.assertTrue(fn.subject_id.startswith("sql:CREATE OR REPLACE FUNCTION:"), fn.subject_id)
        self.assertEqual(fn.kind, gov.UNSUPPORTED)
        # `execute` as data or as part of a routine name is not unmodeled execution.
        self.assertEqual(gov.assess_sql("SELECT 1 -- EXECUTE stmt")["coverage"], gov.RESOLVED)
        self.assertEqual(gov.assess_sql("INSERT INTO n (b) VALUES ('EXECUTE stmt')")["coverage"], gov.RESOLVED)

    def test_dynamic_execution_words_in_data_do_not_change_subject_severity(self):
        cases = (("SELECT $$EXECUTE 'DELETE FROM portfolios'$$;", []),
                 ("/* EXECUTE 'DELETE FROM portfolios' */ SELECT 1;", []),
                 ("INSERT INTO notes (body) VALUES ($$EXECUTE 'DELETE FROM portfolios'$$);",
                  [gov.UNREVIEWED]))
        for sql, expected_kinds in cases:
            with self.subTest(sql=sql):
                subjects = gov.extract_sql_subjects("svc/src/main/resources/db/migration/V2__data.sql", sql)
                self.assertEqual([s.kind for s in subjects], expected_kinds)
                if subjects:
                    self.assertEqual(subjects[0].evidence["verbs"], ["INSERT INTO"])
                    self.assertEqual(subjects[0].evidence["target_tables"], ["notes"])

    def test_dynamic_sql_unsupported_coverage_reaches_the_java_writer(self):
        d = Deployable('class W {\n private JdbcTemplate db;\n void m() {\n'
                       ' db.execute("DO $$ BEGIN EXECUTE \'DELETE FROM portfolios\'; END; $$;");\n }\n}\n')
        try:
            result = d.run()
            writers = [f for f in result["findings"] if "db.execute" in f["subject_id"]]
            self.assertEqual(len(writers), 1, result["findings"])
            self.assertEqual(writers[0]["obligation"], "writer-coverage")
            self.assertEqual(writers[0]["kind"], gov.UNSUPPORTED)
            self.assertIn("dynamic SQL", writers[0]["detail"])
            self.assertEqual(result["overall_status"], gov.BLOCKED)
        finally:
            d.close()




class PostConsolidationExceptionTests(unittest.TestCase):
    """Item 5a/5b: content findings have a blob-bound clearance route; deletion/addition/
    modification exceptions prove the reviewed transition inside the guard interval."""

    def _entry(self, d, base, cut, path):
        return next(e for e in gov.changed_entries(d.repo, base, cut) if e.path == path)

    def test_content_exception_clears_exactly_the_reviewed_symbol_on_the_reviewed_blob(self):
        d = Deployable(None)
        try:
            base = d.head
            d.write("svc/src/main/java/Route.java",
                    'class Route { String a = "/api/presence/demo"; String b = "DemoPresenceService"; }\n')
            d.commit("route")
            cut = d.head
            entry = self._entry(d, base, cut, "svc/src/main/java/Route.java")
            gc5 = {"forbidden_content_symbols": [{"non_goal": "10.2", "symbol": "/api/presence/demo"},
                                                 {"non_goal": "10.2", "symbol": "DemoPresenceService"}],
                   "content_scan_excluded_globs": [], "reviewed_exceptions": []}
            reader = gov.BlobReader(d.repo)
            entries = gov.changed_entries(d.repo, base, cut)
            before = gov.content_guard(entries, reader, gc5, d.repo, base, cut)
            self.assertEqual(len([f for f in before if f.kind == gov.CONFIRMED_MATCH]), 2)
            exc = {"path": "svc/src/main/java/Route.java", "obligation": "content-governance",
                   "change_kind": entry.status, "src_blob": entry.src_blob, "dst_blob": entry.dst_blob,
                   "symbols": ["/api/presence/demo"], "non_goals": ["10.2"],
                   "status": gov.ACCEPTED, "reviewer": "codex", "reviewed_commit": cut}
            gc5["reviewed_exceptions"] = [exc]
            # MEASURED: an accepted, exact-blob, correct-commit content exception left the finding BLOCKED.
            after = gov.content_guard(entries, reader, gc5, d.repo, base, cut)
            symbols_left = {f.evidence["symbol"] for f in after if f.kind == gov.CONFIRMED_MATCH}
            self.assertEqual(symbols_left, {"DemoPresenceService"}, [f.detail for f in after])
            # Re-editing the file lapses the exception (blob-bound).
            d.write("svc/src/main/java/Route.java",
                    'class Route { String a = "/api/presence/demo"; String b = "DemoPresenceService"; int z; }\n')
            d.commit("edit again")
            cut2 = d.head
            entries2 = gov.changed_entries(d.repo, base, cut2)
            again = gov.content_guard(entries2, reader, gc5, d.repo, base, cut2)
            self.assertEqual({f.evidence["symbol"] for f in again if f.kind == gov.CONFIRMED_MATCH},
                             {"/api/presence/demo", "DemoPresenceService"})
            # A REJECTED content exception never clears.
            gc5["reviewed_exceptions"] = [dict(exc, status="REJECTED")]
            rej = gov.content_guard(entries, reader, gc5, d.repo, base, cut)
            self.assertEqual({f.evidence["symbol"] for f in rej if f.kind == gov.CONFIRMED_MATCH},
                             {"/api/presence/demo", "DemoPresenceService"})
            # An exception without a symbol scope is unusable.
            gc5["reviewed_exceptions"] = [{k: v for k, v in exc.items() if k != "symbols"}]
            noscope = gov.content_guard(entries, reader, gc5, d.repo, base, cut)
            self.assertEqual(len([f for f in noscope if f.kind == gov.CONFIRMED_MATCH]), 2)
            self.assertTrue(any(f.obligation == "exception-provenance" for f in noscope))
        finally:
            d.close()

    def test_deletion_exception_at_a_pre_add_commit_is_rejected_and_at_the_deleting_commit_accepted(self):
        d = Deployable(None)
        try:
            pre_add = d.head
            d.write("frontend/src/gone.tsx", "export const x = 1;\n")
            d.commit("add at the guard base")
            base = d.head
            os.remove(d.repo / "frontend/src/gone.tsx")
            d.commit("delete at the cut")
            cut = d.head
            entry = self._entry(d, base, cut, "frontend/src/gone.tsx")
            self.assertTrue(entry.is_deletion)
            gc5 = {"forbidden_paths": [{"non_goal": "10.1", "glob": "frontend/src/**"}],
                   "always_allowed_globs": [], "reviewed_exceptions": []}
            exc = {"path": "frontend/src/gone.tsx", "obligation": "path-governance",
                   "change_kind": entry.status, "src_blob": entry.src_blob, "dst_blob": entry.dst_blob,
                   "status": gov.ACCEPTED, "reviewer": "codex", "reviewed_commit": pre_add}
            # MEASURED: approving the deletion with the PRE-ADD commit returned PASS.
            gc5["reviewed_exceptions"] = [exc]
            findings = gov.path_guard([entry], gc5, d.repo, base, cut)
            kinds = {(f.obligation, f.kind) for f in findings}
            self.assertIn(("path-governance", gov.CONFIRMED_MATCH), kinds, [f.detail for f in findings])
            self.assertIn(("exception-provenance", gov.UNREVIEWED), kinds)
            # The commit that actually made the deletion is accepted.
            gc5["reviewed_exceptions"] = [dict(exc, reviewed_commit=cut)]
            self.assertEqual(gov.path_guard([entry], gc5, d.repo, base, cut), [])
            # The base itself reviewed nothing of the interval.
            gc5["reviewed_exceptions"] = [dict(exc, reviewed_commit=base)]
            self.assertTrue(gov.path_guard([entry], gc5, d.repo, base, cut))
        finally:
            d.close()

    def test_modification_exception_requires_the_reviewed_post_image_inside_the_interval(self):
        d = Deployable(None)
        try:
            d.write("frontend/src/app.tsx", "v0\n")
            d.commit("v0")
            base = d.head
            d.write("frontend/src/app.tsx", "v1\n")
            d.commit("v1")
            mid = d.head
            d.write("frontend/src/app.tsx", "v2\n")
            d.commit("v2")
            cut = d.head
            entry = self._entry(d, base, cut, "frontend/src/app.tsx")
            gc5 = {"forbidden_paths": [{"non_goal": "10.1", "glob": "frontend/src/**"}],
                   "always_allowed_globs": [], "reviewed_exceptions": []}
            exc = {"path": "frontend/src/app.tsx", "obligation": "path-governance",
                   "change_kind": "M", "src_blob": entry.src_blob, "dst_blob": entry.dst_blob,
                   "status": gov.ACCEPTED, "reviewer": "codex", "reviewed_commit": mid}
            # `mid` does not carry the reviewed post-image (v1 != v2): rejected.
            gc5["reviewed_exceptions"] = [exc]
            self.assertTrue(any(f.kind == gov.CONFIRMED_MATCH for f in gov.path_guard([entry], gc5, d.repo, base, cut)))
            # The cut carries it: accepted; the pre-image is proven at the base by construction.
            gc5["reviewed_exceptions"] = [dict(exc, reviewed_commit=cut)]
            self.assertEqual(gov.path_guard([entry], gc5, d.repo, base, cut), [])
            # A commit outside the interval's descendant line cannot review it.
            d.write("frontend/src/app.tsx", "v3\n")
            d.commit("after the cut")
            later = d.head
            gc5["reviewed_exceptions"] = [dict(exc, reviewed_commit=later)]
            self.assertTrue(any(f.kind == gov.CONFIRMED_MATCH for f in gov.path_guard([entry], gc5, d.repo, base, cut)))
        finally:
            d.close()

    def test_addition_exception_requires_the_added_blob_at_the_reviewed_commit(self):
        d = Deployable(None)
        try:
            base = d.head
            d.write("frontend/src/new.tsx", "a\n")
            d.commit("add")
            cut = d.head
            entry = self._entry(d, base, cut, "frontend/src/new.tsx")
            gc5 = {"forbidden_paths": [{"non_goal": "10.1", "glob": "frontend/src/**"}],
                   "always_allowed_globs": [], "reviewed_exceptions": []}
            exc = {"path": "frontend/src/new.tsx", "obligation": "path-governance",
                   "change_kind": "A", "src_blob": entry.src_blob, "dst_blob": entry.dst_blob,
                   "status": gov.ACCEPTED, "reviewer": "codex", "reviewed_commit": cut}
            gc5["reviewed_exceptions"] = [exc]
            self.assertEqual(gov.path_guard([entry], gc5, d.repo, base, cut), [])
            gc5["reviewed_exceptions"] = [dict(exc, reviewed_commit=base)]
            self.assertTrue(gov.path_guard([entry], gc5, d.repo, base, cut))
        finally:
            d.close()


class CandidateEndToEndTests(unittest.TestCase):
    """A clean, committed CANDIDATE run in a disposable repository on WINDOWS-NORMALIZED content
    (core.autocrlf=true, files re-checked-out as CRLF), driving the ACTUAL CLI by subprocess so
    `__file__` is the committed analyzer. Evidence is PRODUCER-GENERATED (b1_candidate_evidence.run_evidence
    and verify_b1_candidate_image.verify_candidate_image against a real JAR and a real image) and lives
    OUTSIDE the repository, so the checkout stays the frozen cut. Negatives break exactly one property."""

    ANALYZER = REPO / "scripts" / "check_b1_candidate_source.py"
    EVIDENCE_MOD = REPO / "scripts" / "b1_candidate_evidence.py"
    IMAGE_MOD = REPO / "scripts" / "verify_b1_candidate_image.py"

    def _build(self, autocrlf: str = "true"):
        tmp = TemporaryDirectory()
        root = Path(tmp.name)
        repo, ext = root / "repo", root / "external"
        repo.mkdir()
        ext.mkdir()
        run_git(repo, "init", "-q")
        run_git(repo, "config", "user.email", "t@e.com")
        run_git(repo, "config", "user.name", "T")
        run_git(repo, "config", "core.autocrlf", autocrlf)
        (repo / ".gitignore").write_text("build/\n.candidate-artifacts/\n", encoding="utf-8")
        (repo / "settings.gradle").write_text("include 'svc'\n", encoding="utf-8")
        (repo / "build.gradle").write_text("// root\n", encoding="utf-8")
        (repo / "svc").mkdir()
        (repo / "svc" / "build.gradle").write_text("// svc\n", encoding="utf-8")
        # The copy-only recipe, shaped like portfolio-service/Dockerfile.candidate: ARG before FROM so
        # the producer can pass the digest-PINNED base it resolved, never the floating tag.
        (repo / "svc" / "Dockerfile.candidate").write_text(
            "ARG RUNTIME_BASE=busybox\nFROM ${RUNTIME_BASE}\n"
            "COPY .candidate-artifacts/portfolio-service.jar /app.jar\nCMD [\"/app.jar\"]\n", encoding="utf-8")
        src_dir = repo / "svc" / "src" / "main" / "java"
        src_dir.mkdir(parents=True)
        (src_dir / "Writer.java").write_text(
            "class Writer {\n  private JdbcTemplate jdbcTemplate;\n"
            "  void inner() { jdbcTemplate.update(\"DELETE FROM portfolios WHERE id = ?\"); }\n}\n",
            encoding="utf-8")
        mig = repo / "svc" / "src" / "main" / "resources" / "db" / "migration"
        mig.mkdir(parents=True)
        (mig / "V1__init.sql").write_text("CREATE TABLE portfolios (id uuid PRIMARY KEY);\n", encoding="utf-8")
        run_git(repo, "add", "-A")
        run_git(repo, "commit", "-q", "-m", "reviewed source R")
        r_sha = run_git(repo, "rev-parse", "HEAD").strip()

        reader = gov.BlobReader(repo)
        tree = gov.tree_blobs(repo, r_sha)
        roots = gov.derive_envelope_roots(tree, reader, "svc")
        membership = gov.envelope_membership(tree, roots)
        digs = gov.envelope_digests(roots, membership)
        record = {"envelope_id": "svc", "revision": 1, **digs, "reviewed_commit": r_sha,
                  "reviewer": "codex", "reviewed_at": "2026-09-03",
                  "attestation": {"analyzed": [p for p, _ in membership], "non_runtime": [], "unsupported": []}}
        pre = gov.run_all(repo, self._policy(r_sha, [record], []), None, r_sha, gov.LOCAL_PREPARATION, None)
        wf = subject_of(pre, "jdbcTemplate.update")
        disp = {"obligation": "writer-inventory", "path": wf["path"], "subject_id": wf["subject_id"],
                "envelope_id": "svc", "envelope_record_id": gov.envelope_record_identity(record),
                "status": gov.ACCEPTED, "code_fingerprint": wf["evidence"]["code_fingerprint"],
                "reviewer": "codex", "reviewed_commit": r_sha, "reviewed_at": "2026-09-03", "claims": []}
        (repo / "scripts").mkdir()
        for src in (self.ANALYZER, self.EVIDENCE_MOD, self.IMAGE_MOD):
            shutil.copy(src, repo / "scripts" / src.name)
        return tmp, repo, ext, r_sha, record, disp

    def _policy(self, r_sha, envelopes, dispositions):
        return {
            "b1_base_commit": {"sha": r_sha},
            "gc5": {"always_allowed_globs": ["**"], "forbidden_paths": [],
                    "forbidden_content_symbols": [], "content_scan_excluded_globs": ["**"],
                    "per_holding_freshness_structural_check": {"types": [], "discovery_globs": [],
                                                               "type_name_pattern": "ZZZNONE"},
                    "reviewed_exceptions": []},
            "writer_inventory": {"production_writers": [], "classified_non_writers": [],
                                 "flagged_writers_outside_holding_replacement_service": [],
                                 "excluded_from_recheck": []},
            "deployables": [{"envelope_id": "svc", "module": "svc"}],
            "envelopes": envelopes, "dispositions": dispositions,
            "operational_records": [], "unverified_coverage_reviews": [],
            "scan_exclusions": [{"glob": "scripts/**", "reason": "verifier tooling", "kind": "TEST_CORPUS"}],
            "relevant_tables": ["asset_holdings", "portfolios"], "non_persistence_receiver_types": [],
            "merge_grouping": [], "automatic_b1_scope_clearance": [],
            "effect_based_automatic_clearance": [], "unresolved": [],
            # Task A producer inputs (b1_candidate_evidence.run_evidence).
            "report_dirs": {"test": "svc/build/test-results/test",
                            "integrationTest": "svc/build/test-results/integrationTest"},
            "candidate_floor": {"entries": [{"task": "test", "report_class_suffix": "WriterTest"},
                                            {"task": "integrationTest", "report_class_suffix": "WriterIT"}]},
            "discovery": {"test_file_globs": ["svc/src/test/java/**/*Test.java"], "helper_class_allowlist": []},
            "staging": {"bootjar_dir": "svc/build/libs", "bootjar_glob": "*.jar",
                        "staged_path": ".candidate-artifacts/portfolio-service.jar"},
        }

    def _commit_cut(self, repo, r_sha, record, disp) -> str:
        (repo / "scripts" / "policy.json").write_text(json.dumps(self._policy(r_sha, [record], [disp])), encoding="utf-8")
        run_git(repo, "add", "-A")
        run_git(repo, "commit", "-q", "-m", "verifier + policy at C (the cut)")
        cut = run_git(repo, "rev-parse", "HEAD").strip()
        # Force a REAL re-checkout so text files take the platform's line endings (CRLF here).
        for rel in run_git(repo, "ls-files", "-z").split("\0"):
            if rel:
                (repo / rel).unlink()
        run_git(repo, "checkout", "--", ".")
        return cut

    def _produce_evidence(self, repo, ext, cut, tamper=None, record_tamper=None):
        """Producer-generated Task A + Task B evidence, written OUTSIDE the repo. Task B goes through
        the producer's FULL build path: the base (`busybox`, pulled once and then cached) is resolved to
        an immutable `repository@sha256:` digest BEFORE the build, the build uses that pinned reference,
        and the producer writes its build record. `tamper(a, b)` mutates the bundles and
        `record_tamper(record)` mutates the build record, one property per negative. Returns
        (run_input_path, image_id) -- the caller removes the image."""
        policy = json.loads((repo / "scripts" / "policy.json").read_text(encoding="utf-8"))
        marker = ev.write_marker(repo, repo / ".candidate-artifacts" / "run-start.marker")
        assert marker["mode"] == "CANDIDATE" and marker["head_sha"] == cut, marker
        write_report(repo / "svc/build/test-results/test/TEST-WriterTest.xml", "p.WriterTest", tests=1)
        write_report(repo / "svc/build/test-results/integrationTest/TEST-WriterIT.xml", "p.WriterIT", tests=1)
        libs = repo / "svc" / "build" / "libs"
        libs.mkdir(parents=True)
        jar_bytes = ("jar-" + uuid.uuid4().hex).encode()
        (libs / "svc.jar").write_bytes(jar_bytes)
        (repo / ".candidate-artifacts" / "portfolio-service.jar").write_bytes(jar_bytes)
        task_a = ev.run_evidence(repo, policy, marker)
        assert task_a["graph_verification_status"] == "PASS" and task_a["run"]["mode"] == "CANDIDATE"

        tag = "b1-guard-e2e:" + uuid.uuid4().hex[:12]
        record_path = ext / "image-build-record.json"
        task_b = viv.verify_candidate_image(repo, policy, task_a, tag=tag,
                                            dockerfile=repo / "svc" / "Dockerfile.candidate",
                                            base_ref="busybox", build_record_path=record_path,
                                            workdir=ext / "tmp")
        image_id = task_b["local_image_id"]
        assert task_b["provenance"] == "verified" and task_b["hashes_equal"], task_b
        assert task_b["runtime_base_digest"].startswith("busybox@sha256:"), task_b["runtime_base_digest"]
        if tamper:
            tamper(task_a, task_b)
        if record_tamper:
            record = json.loads(record_path.read_text(encoding="utf-8"))
            record_tamper(record)
            record_path.write_text(json.dumps(record, indent=2), encoding="utf-8")
        (ext / "taskA.json").write_text(json.dumps(task_a), encoding="utf-8")
        (ext / "taskB.json").write_text(json.dumps(task_b), encoding="utf-8")
        run_input = ext / "run-input.json"
        run_input.write_text(json.dumps({"task_a": {"evidence_file": str(ext / "taskA.json")},
                                         "task_b": {"evidence_file": str(ext / "taskB.json"),
                                                    "build_record": str(record_path)}}), encoding="utf-8")
        return run_input, image_id

    def _run_cli(self, repo, ext, base_sha, cut, run_input):
        out = ext / "out.json"
        proc = subprocess.run(
            [sys.executable, "-B", str(repo / "scripts" / "check_b1_candidate_source.py"),
             "--repo", str(repo), "--policy", str(repo / "scripts" / "policy.json"),
             "--base-sha", base_sha, "--head", cut, "--mode", "CANDIDATE",
             "--run-input", str(run_input), "--out", str(out)],
            capture_output=True, text=True)
        result = json.loads(out.read_text(encoding="utf-8")) if out.is_file() else None
        return proc.returncode, result, proc.stderr

    @NEEDS_DOCKER
    def test_clean_candidate_at_exact_head_on_crlf_checkout_passes(self):
        tmp, repo, ext, r_sha, record, disp = self._build()
        image_id = None
        try:
            cut = self._commit_cut(repo, r_sha, record, disp)
            analyzer_bytes = (repo / "scripts" / "check_b1_candidate_source.py").read_bytes()
            self.assertIn(b"\r\n", analyzer_bytes, "fixture must exercise the Windows CRLF checkout")
            self.assertTrue(gov.is_clean(repo))
            run_input, image_id = self._produce_evidence(repo, ext, cut)
            self.assertTrue(gov.is_clean(repo), "producer output must not dirty the frozen cut")
            code, res, err = self._run_cli(repo, ext, r_sha, cut, run_input)
            self.assertIsNotNone(res, err)
            self.assertEqual(res["evaluator"]["mode"], "CANDIDATE")
            self.assertTrue(res["evaluator"].get("identity_verified_against_cut"), err)
            self.assertEqual(res["evaluator"]["checkout_head"], cut)
            self.assertEqual(res["source_governance_status"], "PASS", [f["detail"] for f in res["findings"]])
            self.assertEqual(res["evidence"]["task_a"]["mode"], "CANDIDATE")
            self.assertEqual(res["evidence"]["task_b"]["image_identity"], image_id)
            self.assertTrue(res["evidence"]["artifacts_verified"])
            self.assertFalse(res["candidate_ready"])
            self.assertEqual(code, 0, err)
        finally:
            if image_id:
                subprocess.run(["docker", "rmi", "-f", image_id], capture_output=True)
            tmp.cleanup()

    def _candidate_negative(self, tamper=None, record_tamper=None, must_mention=()):
        tmp, repo, ext, r_sha, record, disp = self._build()
        image_id = None
        try:
            cut = self._commit_cut(repo, r_sha, record, disp)
            run_input, image_id = self._produce_evidence(repo, ext, cut, tamper=tamper, record_tamper=record_tamper)
            code, res, err = self._run_cli(repo, ext, r_sha, cut, run_input)
            self.assertIsNotNone(res, err)
            details = [f["detail"] for f in res["findings"] if f["obligation"] == "evidence-binding"]
            self.assertTrue(details, "expected an evidence-binding failure")
            for needle in must_mention:
                self.assertTrue(any(needle in x for x in details), (needle, details))
            self.assertIsNone(res["evidence"]["task_b"], "invalid Task B evidence must never be summarized as accepted")
            self.assertFalse(res["evidence"]["artifacts_verified"])
            self.assertEqual(res["source_governance_status"], "BLOCKED")
            self.assertEqual(code, 1)
            return details
        finally:
            if image_id:
                subprocess.run(["docker", "rmi", "-f", image_id], capture_output=True)
            tmp.cleanup()

    @NEEDS_DOCKER
    def test_candidate_positive_binds_producer_build_record(self):
        tmp, repo, ext, r_sha, record, disp = self._build()
        image_id = None
        try:
            cut = self._commit_cut(repo, r_sha, record, disp)
            run_input, image_id = self._produce_evidence(repo, ext, cut)
            code, res, err = self._run_cli(repo, ext, r_sha, cut, run_input)
            self.assertIsNotNone(res, err)
            self.assertEqual(res["source_governance_status"], "PASS", [f["detail"] for f in res["findings"]])
            tb = res["evidence"]["task_b"]
            self.assertTrue(tb["build_record_bound"])
            self.assertTrue(tb["runtime_base_digest"].startswith("busybox@sha256:"))
            self.assertIn(str(ext / "image-build-record.json"), res["evidence"]["input_hashes"])
        finally:
            if image_id:
                subprocess.run(["docker", "rmi", "-f", image_id], capture_output=True)
            tmp.cleanup()

    @NEEDS_DOCKER
    def test_candidate_rejects_arbitrary_base_and_nonexistent_recipe(self):
        # MEASURED through the actual CLI: these two mutations returned PASS, exit 0, zero findings.
        def tamper(a, b):
            b["runtime_base_digest"] = "definitely-not-a-digest"
            b["recipe"] = "C:/nonexistent/file-that-does-not-exist.Dockerfile"
        self._candidate_negative(tamper=tamper, must_mention=("runtime_base_digest", "immutable"))

    @NEEDS_DOCKER
    def test_candidate_rejects_floating_tag_base(self):
        self._candidate_negative(tamper=lambda a, b: b.__setitem__("runtime_base_digest", "busybox:latest"),
                                 must_mention=("immutable",))

    @NEEDS_DOCKER
    def test_candidate_rejects_conflicting_recorded_base(self):
        self._candidate_negative(record_tamper=lambda r: r.__setitem__("base_digest", "busybox@sha256:" + "0" * 64),
                                 must_mention=("base_digest does not match",))

    @NEEDS_DOCKER
    def test_candidate_rejects_missing_recipe(self):
        self._candidate_negative(tamper=lambda a, b: b.__setitem__("recipe", "svc/Dockerfile.that-does-not-exist"),
                                 must_mention=("do not name the same file",))

    @NEEDS_DOCKER
    def test_candidate_rejects_changed_recipe_bytes(self):
        self._candidate_negative(record_tamper=lambda r: r.__setitem__("dockerfile_sha256", "0" * 64),
                                 must_mention=("recipe changed after the image was built",))

    @NEEDS_DOCKER
    def test_candidate_rejects_image_identity_mismatch_in_record(self):
        self._candidate_negative(record_tamper=lambda r: r.__setitem__("image_id", "sha256:" + "1" * 64),
                                 must_mention=("describes a different image",))

    @NEEDS_DOCKER
    def test_candidate_requires_the_build_record(self):
        tmp, repo, ext, r_sha, record, disp = self._build()
        image_id = None
        try:
            cut = self._commit_cut(repo, r_sha, record, disp)
            run_input, image_id = self._produce_evidence(repo, ext, cut)
            manifest = json.loads(run_input.read_text(encoding="utf-8"))
            del manifest["task_b"]["build_record"]
            run_input.write_text(json.dumps(manifest), encoding="utf-8")
            code, res, err = self._run_cli(repo, ext, r_sha, cut, run_input)
            self.assertIsNotNone(res, err)
            details = [f["detail"] for f in res["findings"] if f["obligation"] == "evidence-binding"]
            self.assertTrue(any("build record" in x and "required in CANDIDATE" in x for x in details), details)
            self.assertIsNone(res["evidence"]["task_b"])
            self.assertEqual(code, 1)
        finally:
            if image_id:
                subprocess.run(["docker", "rmi", "-f", image_id], capture_output=True)
            tmp.cleanup()

    def test_pinned_base_format(self):
        ok = ("mcr.microsoft.com/openjdk/jdk@sha256:" + "a" * 64, "busybox@sha256:" + "0" * 64,
              "localhost:5000/team/app@sha256:" + "f" * 64)
        bad = ("scratch", "busybox", "busybox:latest", "definitely-not-a-digest", "sha256:" + "a" * 64,
               "Busybox@sha256:" + "a" * 64, "busybox@sha256:" + "a" * 63, "")
        for v in ok:
            self.assertIsNotNone(gov._PINNED_BASE_RE.match(v), v)
        for v in bad:
            self.assertIsNone(gov._PINNED_BASE_RE.match(v), v)
        b = producer_shaped_task_b("c" * 40, Path("C:/x.jar"), "a" * 64, "sha256:" + "b" * 64, "linux/amd64")
        # LOCAL_PREPARATION tolerates the scratch fixture; CANDIDATE does not.
        self.assertEqual(gov.task_b_schema_problems(b, "c" * 40, gov.LOCAL_PREPARATION, False), [])
        self.assertTrue(any("immutable" in p for p in gov.task_b_schema_problems(b, "c" * 40, gov.CANDIDATE, False)))

    @NEEDS_DOCKER
    def test_candidate_with_unverified_provenance_blocks(self):
        tmp, repo, ext, r_sha, record, disp = self._build()
        image_id = None
        try:
            cut = self._commit_cut(repo, r_sha, record, disp)
            run_input, image_id = self._produce_evidence(
                repo, ext, cut, tamper=lambda a, b: b.__setitem__("provenance", "unverified"))
            code, res, err = self._run_cli(repo, ext, r_sha, cut, run_input)
            self.assertIsNotNone(res, err)
            self.assertIn(gov.EVIDENCE_BINDING_MISMATCH, {f["kind"] for f in res["findings"]
                                                          if f["obligation"] == "evidence-binding"})
            self.assertEqual(res["source_governance_status"], "BLOCKED")
            self.assertEqual(code, 1)
        finally:
            if image_id:
                subprocess.run(["docker", "rmi", "-f", image_id], capture_output=True)
            tmp.cleanup()

    @NEEDS_DOCKER
    def test_later_clean_head_is_not_the_frozen_cut(self):
        # MEASURED: the previous fixture committed evidence at E after the cut C and ran `--head C`
        # from a checkout at E; is_clean passed because E was clean.
        tmp, repo, ext, r_sha, record, disp = self._build()
        image_id = None
        try:
            cut = self._commit_cut(repo, r_sha, record, disp)
            run_input, image_id = self._produce_evidence(repo, ext, cut)
            (repo / "NOTES.md").write_text("later\n", encoding="utf-8")
            run_git(repo, "add", "-A")
            run_git(repo, "commit", "-q", "-m", "a later, clean commit E")
            self.assertTrue(gov.is_clean(repo))
            code, res, err = self._run_cli(repo, ext, r_sha, cut, run_input)
            self.assertIsNone(res, "no evidence may be produced from a checkout that is not the cut")
            self.assertEqual(code, 1)
            self.assertIn("HEAD", err)
        finally:
            if image_id:
                subprocess.run(["docker", "rmi", "-f", image_id], capture_output=True)
            tmp.cleanup()

    def test_edited_analyzer_or_policy_bytes_are_refused(self):
        tmp, repo, ext, r_sha, record, disp = self._build()
        try:
            cut = self._commit_cut(repo, r_sha, record, disp)
            run_input = ext / "run-input.json"
            run_input.write_text("{}", encoding="utf-8")
            # A comment for the analyzer; trailing whitespace for the JSON policy (still valid JSON, so
            # the refusal comes from the identity check, not from the JSON parser).
            for rel, label, suffix in (("scripts/check_b1_candidate_source.py", "analyzer", b"\r\n# edited\r\n"),
                                       ("scripts/policy.json", "policy", b"\r\n \r\n")):
                original = (repo / rel).read_bytes()
                (repo / rel).write_bytes(original + suffix)
                code, res, err = self._run_cli(repo, ext, r_sha, cut, run_input)
                self.assertIsNone(res, label)
                self.assertEqual(code, 1, label)
                self.assertIn(label + " differs", err, label)
                (repo / rel).write_bytes(original)
            # Restored: the CRLF checkout still hashes equal to the committed LF blob.
            self.assertTrue(gov.is_clean(repo))
            code, res, err = self._run_cli(repo, ext, r_sha, cut, run_input)
            self.assertIsNotNone(res, err)
            self.assertTrue(res["evaluator"].get("identity_verified_against_cut"))
        finally:
            tmp.cleanup()

    def test_worktree_object_id_is_clean_filter_aware(self):
        tmp, repo, ext, r_sha, record, disp = self._build()
        try:
            cut = self._commit_cut(repo, r_sha, record, disp)
            tree = gov.tree_blobs(repo, cut)
            rel = "scripts/check_b1_candidate_source.py"
            raw = (repo / rel).read_bytes()
            self.assertIn(b"\r\n", raw)
            self.assertNotEqual(hashlib.sha256(raw).hexdigest(),
                                hashlib.sha256(gov.BlobReader(repo).raw(tree[rel])).hexdigest(),
                                "raw CRLF bytes must NOT equal the LF blob -- that is the platform case")
            self.assertEqual(gov.worktree_object_id(repo, rel, raw), tree[rel])
        finally:
            tmp.cleanup()


if __name__ == "__main__":
    unittest.main()
