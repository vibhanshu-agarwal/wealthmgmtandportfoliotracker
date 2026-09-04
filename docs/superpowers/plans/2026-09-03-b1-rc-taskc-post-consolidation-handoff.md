# B1 R-C Task C (source-governance guard) — post-consolidation round handoff

> **OWNER APPROVAL CALLOUT (AGENTS.md "Owner Approval Callouts").** No owner decision is requested at
> this checkpoint. Local implementation fixes and local test/analysis runs continue without
> authorization. Everything below the local boundary stays **owner-gated and is NOT requested**: any
> commit, push, pull request or merge of these files; a release-candidate build or push; registry,
> live-database, deployment or ledger actions; authoring any envelope attestation, exception or
> disposition for the real repository; and the R3 operational check. **The registry-digest smoke run
> (`--registry-digest` + `--authorized-release-run`) is owner-gated and has NOT been performed.** If a
> reader wants any gated action, that is a separate, explicit owner decision — nothing in this document
> performs or presumes it.

Date: 2026-09-03 (round 5) through 2026-09-04 (round 15). Contract: `gc5-contract/3`, analyzer
`java-conservative/3`.

**Status: BOTH tooling checkpoints ACCEPTED by Codex on 2026-09-04** — the source-governance guard
(round 11) and the local HTTP smoke harness (round 14, after corrections in rounds 13-14). This
document is now the round-by-round technical record; the consolidated deliverable is the
[return packet](2026-09-04-b1-rc-candidate-preparation-return-packet.md), which carries the single
open owner decision.

Acceptance is of the **tooling**, not of candidate readiness: the real repo remains `BLOCKED` with 504
governance findings, `candidate_ready: false`, R3 unresolved, and the registry-digest smoke unrun.

## Round 15 (2026-09-04) — return-packet documentation corrections

Codex reviewed the consolidated return packet itself. **No implementation defect was found and no new
application-test run was requested**; the three corrections are documentation accuracy, and all three
are applied in the packet:

1. **Publication scope was understated.** The packet said "15 new files"; git shows **16 new plus 3
   modified tracked files (19)**, and the two governed-document edits make the proposed package
   **21**. Every path is now enumerated, the wildcard "4 ×" row is gone, and the three modified
   tracked files are named inside the approval request rather than only in prose.
2. **Results were bound to the wrong run.** The 760 application tests come from the **preserved
   2026-09-03 `LOCAL_DEV` graph** (marker epoch `1788422649.3166454` = `2026-09-03T08:04:09.316645Z`,
   HEAD `ebb96f3a`), which predates later tooling and documentation edits; it is valid historical
   development evidence, not fresh verification of the final package. The universal "every test has a
   measured prior RED" claim is replaced by the narrower, true one about the named regression
   controls. R1 is qualified as resolved for the local graph/staging/packaging implementation only.
3. **The evidence index was missing.** The packet now names each artifact's location, hash, run date
   and mode. Two corrections of substance came out of this: `.candidate-artifacts/gc5-writer-evidence.json`
   is a **legacy-format** bundle (`base_sha`/`head`/`path_guard`/`content_guard`/`writer_inventory`)
   and must not stand in for the current `gc5-contract/3` output, so the current output was written
   to a **new** file rather than over it; and the packet had blurred **two different development
   images** into one identity.

**Governed documents.** Codex approved the R2 carrier correction and supplied
`b1-rc-governed-status-reconciliation.patch` for the master plan and the B1 ledger. It was applied
here under the coordination permission recorded in the packet: preimages matched Codex's recorded
`e45003e6…` and `4fda5797…`, `git apply --check` passed, CRLF endings were preserved on both files
(no mixed endings introduced), and all **91** checkbox lines in `tasks.md` are byte-identical before
and after. **No completion box changed.**

## Round 14 (2026-09-04) — lossless decimals and an honest cleanup flag

Codex confirmed 62/62 and the real local smoke, and found two remaining defects.

1. **[P1] JSON decoding rounded before the comparison could run.** `json.loads` decodes a fractional
   JSON number to a binary float, and PostgreSQL's `row_to_json` emits NUMERIC columns as bare JSON
   numbers, so a real change to `avg_cost_basis NUMERIC(19,4)` from `999999999999999.0000` to
   `…0001` collapsed to the same value and A4-db passed; the same mechanism hid an HTTP quantity of
   `1.5000000000000001` against a submitted `1.5`. Round 13's decimal comparison was correct but was
   being handed already-rounded inputs. **Fix:** `json_loads` (`parse_float=Decimal`,
   `parse_constant` refusing non-finite values) is used at every decoding boundary — the HTTP body,
   `row_to_json` rows and the manifest — and `json_dumps` serializes `Decimal` as its exact decimal
   text so the written evidence round-trips without rounding. `_decimal` now also refuses a `float`
   outright, since one has already lost digits, and refuses non-finite values. Equivalent scales
   (`1.5` vs `1.5000`) still compare equal; no tolerance is used anywhere.
   *Fixture correction:* the DB fixtures previously quoted their numeric columns as JSON strings,
   which bypassed the very boundary at risk. `pg_row_json` now emits them as bare numbers exactly as
   `row_to_json` does, while the HTTP fixture keeps the string form the real API produces
   (`ToPlainStringSerializer`), so both boundaries are exercised as they actually behave.
2. **[P2] A failed cleanup still reported `cleanup_verified: true`.** The flag was derived from
   `not keep` before workdir cleanup could append its errors. It is now derived last, from both the
   retention mode and the final error list, so it is true only when cleanup actually completed. The
   overall FAIL downgrade is unchanged, and the genuine PASS→FAIL regression now asserts the flag —
   including a workdir-cleanup failure, whose error is appended after the Docker ones.

## Verification record — round 14 (2026-09-04)

- Smoke suite: **73 tests OK, zero skips** (46 s), up from 62. New coverage: plain `json.loads` is
  shown to lose the digits (`999999999999999.0000` == `…0001`) while `json_loads` keeps them; the
  same change fails A4-db and fails the whole orchestration through the real decoding path; unchanged
  rows still pass; an HTTP quantity of `1.5000000000000001` fails against `1.5` while `1.5000` passes
  (driven through the real `http_request` with a mocked `urlopen`); the CLI writes and re-reads
  evidence with the exact digits intact; non-finite JSON and floats are refused; `cleanup_verified`
  is false for a Docker cleanup failure, for a workdir cleanup failure, and for `--keep`, and true
  only for a genuinely clean run.
- Real local smoke: **PASS**, all five assertions, `linux/amd64`, `cleanup_verified: true`, no
  cleanup errors, nothing retained. The written evidence carries exact persisted digits
  (`quantity 1.50000000`, `avg_cost_basis 212.5000`).
- Hygiene: no `b1smoke-*` containers or networks; `.candidate-artifacts/` back to its pre-run
  contents; `git status` unchanged at 18 entries.
- Hashes: smoke `0fe100cbb37d60738fa319e98740c7cb09f0da90969efd14f558281a87b43af9` (942 lines),
  its tests `f4024662562231f2138e598ba31fb476c168682bad8c3feb0c0c55a59787120d` (906 lines).
  Analyzer `dde3d215…`, policy `78bf8596…` and historical Task A/B evidence `be00a2e1…` /
  `624e5df5…` unchanged; per the review the shared-helper suites were not re-run, as no shared
  helper was touched.
- No registry access, release-candidate build, commit, push, PR, deployment, live-database action,
  disposition or ledger update.

## Round 13 (2026-09-04) — smoke-harness corrections (five review findings)

Codex ran the harness, confirmed 39/39 and the real image smoke, and reproduced five gaps. All are
closed; each is now a regression that fails if reintroduced.

1. **Release identity contract (P1).** A release run had no stated expectations, so a PASS described
   whatever was pulled. `resolve_image_reference` now enforces, **before any Docker or registry
   access**: the digest is in the pinned `APPROVED_RELEASE_REPOSITORY`
   (`wealthprodacr.azurecr.io/portfolio-service` — a foreign repository is refused even though its
   digest is equally immutable); `--expect-jar-sha256` and `--expect-platform` are mandatory; `--keep`
   is refused. `resolve_release_manifest` runs `docker manifest inspect` and, for an **index**,
   selects and records the child manifest for the expected platform (no match, or an ambiguous match,
   is a refusal with the available platforms listed); a single manifest records the digest itself.
   The pull is `--platform`-pinned and the resolved image's platform is compared afterwards. A local
   run may still omit the artifact expectation, but the evidence then names that unverified join.
2. **Complete persisted state (P1).** `read_persisted_state` used a hand-picked column list, so
   changes to parent timestamps, holding identities and every cost-basis column were invisible. It
   now snapshots **whole rows** via `row_to_json`, ordered by holding id, and compares them.
3. **Quantities are asserted (P1).** A3 compared only version and ticker set, so submitting
   `AAPL=1.5` and receiving `999` passed. The submitted ticker→quantity map is now passed into both
   A3 and A4-db and compared by **decimal value** (`1.5` == `1.5000`, `1.5` != `999`); missing, extra,
   null and non-decimal quantities all fail.
4. **Container ownership before start (P2).** `docker run` registered the id only on success, so a
   container that was created and then failed to launch leaked untracked. `run_container` now does
   `docker create` → record id → `docker start`, so a failed start is still owned and removed.
5. **`--keep` cannot be release evidence (P2).** It is refused outright in release mode, and in local
   mode the evidence records `cleanup_verified: false`, names the `retained_resources`, and adds both
   an `unverified_joins` note and a `candidate_ready_blocked_by` entry.

Accompanying cleanups Codex asked for: `test_cleanup_failure_downgrades_a_passing_run` was misleading
(it failed during image inspection, so it never tested the PASS→FAIL downgrade) and is replaced with a
full mocked orchestration that reaches all five passing assertions and then injects a cleanup error;
the release subcase of `test_evidence_is_never_candidate_ready` now mocks `_run`, so it cannot reach a
real `docker pull`; and this document's top status is corrected to say the source-governance
checkpoint is already accepted.

## Verification record — round 13 (2026-09-04)

- Smoke suite: **62 tests OK, zero skips** (40 s), up from 39. New coverage: the release identity
  contract (missing artifact/platform expectation, foreign repository, malformed digest/platform,
  `--keep`), index vs single manifest resolution and platform pinning, per-column state mutations
  (parent timestamps, holding identity, every cost-basis column), decimal quantity comparison in both
  the response and the persisted rows, container ownership across a failed start, and `--keep`
  evidence marking. Every reviewer false-PASS control is now a regression asserting **FAIL**.
- Real end-to-end CLI run with the stricter assertions (`--expect-jar-sha256`, `--expect-platform`):
  **PASS**, all five assertions, `linux/amd64`, image `sha256:0337502e…`.
- Hygiene: no `b1smoke-*` containers or networks; `.candidate-artifacts/` back to exactly its
  pre-run contents (the mocked runs now extract to a temp dir outside the repository, so even the
  `--keep` test leaves nothing behind); `git status` unchanged at 18 entries.
- Hashes: smoke `cc865b257632281fc291adbbb813bb790168932bf0b7d2524806aaa5e071a5e1` (906 lines),
  its tests `71bcda142ee057f6f3a35574f3d24668471e592a7c91066db657cf5f6c0bbe42` (767 lines).
  Analyzer, guard tests, policy and historical Task A/B evidence all unchanged
  (`dde3d215…`, `a14e4702…`, `78bf8596…`, `be00a2e1…`, `624e5df5…`).
- Per the review, the unchanged Task A/B and source-governance suites were not re-run: no shared
  helper they use was touched (the changes are confined to the two new smoke files).
- No registry access, release-candidate build, commit, push, PR, deployment, live-database action,
  disposition or ledger update. The `--registry-digest` path was exercised only against mocked
  registry I/O.

## Round 12 (2026-09-04) — HTTP contract smoke harness (Task C, local portion)

Codex accepted the source-governance tooling checkpoint and cleared the already-scoped local smoke
work. New files: `scripts/smoke_b1_candidate_image.py` and `scripts/tests/test_smoke_b1_candidate_image.py`.

**What it runs.** The packaged artifact, by immutable identity, with its own shipped entrypoint — no
mounted JAR, no test-class overlay, no `--entrypoint` — on a private Docker network with disposable
PostgreSQL, Redis and Kafka, under `SPRING_PROFILES_ACTIVE=prod,azure` on the production port 8080
(`application-prod.yml:8-9`; the Dockerfile's `EXPOSE 8081` does not set the app port), as a synthetic
user this harness owns (`…00000000c0de`, deliberately distinct from the compiled-in E2E and demo users).

**Assertions**, each recorded with request, response and digest: A1 the container runs the requested
identity/platform via the image's own entrypoint within the deadline; A2 `GET /api/assets` → 200 with a
non-empty catalog and an ETag; A3 a nontrivial multi-holding composition succeeds and the version
advances; A4 replaying **the same** `expectedVersion` → 409 with the exact `portfolio_version_conflict`
envelope and the observed `currentVersion`; A4-db the persisted parent row and holdings are unchanged
afterwards **and** are the rows A3 reported writing. A4 never refreshes `expectedVersion` and never
retries. A4-db rejects two empty reads, so a mis-targeted query cannot pass vacuously.

**Release gating.** `--local-image sha256:<64hex>` is `LOCAL_PREPARATION` development feedback.
`--registry-digest <repo>@sha256:<64hex>` is the Task 7.5a evidence run: it pulls that digest and
re-extracts/re-hashes `/app.jar`, and it refuses to run without `--authorized-release-run`. **That run
has not been performed and is not requested here.** A mutable tag (including `tag@sha256:`) is refused
in both modes. `candidate_ready` is always false.

**Honesty properties.** `environment_differences` lists every divergence from production (disposable
dependencies, Kafka PLAINTEXT vs SASL_SSL, synthetic non-credential placeholders, no gateway, synthetic
identity). Only env var NAMES are recorded, never values. Cleanup removes only this run's containers,
network and workdir, matched by its own run id — never a prune or a name sweep — and a cleanup error
FAILS the run rather than being absorbed. Every wait has a deadline; a dependency that never becomes
ready is a failure, not a hang.

## Verification record — round 12 (2026-09-04)

- Smoke suite: **39 tests OK** (32 s), including the real end-to-end run against the locally built
  candidate image. Guard: **217 OK**. Task A: **39 OK**. Task B: **34 OK**. Total 329, zero skips.
- Real end-to-end smoke (`LOCAL_PREPARATION`, image `sha256:0337502e…`, platform `linux/amd64`,
  entrypoint `["java","-jar","/app.jar"]`, extracted `/app.jar` = the staged `4ee27c78…`): **PASS in
  27 s**, startup 9.2 s, actuator health UP (liveness+readiness). A2 saw a real 160-asset catalog
  (`catalogVersion a00b32ac0267e1a9`, ETag present). A3 returned **201** with version 0 → 1 and three
  holdings. A4 returned **409** with
  `{"error":"portfolio_version_conflict","message":"portfolio_version_conflict: currentVersion=1","currentVersion":1}`.
  A4-db read the real portfolio row and its three holdings from PostgreSQL, identical before and after
  the rejected write.
- Real repo at `ebb96f3a`: BLOCKED, `candidate_ready: false`, **504 findings — zero delta** from the
  accepted baseline (the new harness files are untracked and outside the analyzed cut).
- Cleanup verified: no `b1smoke-*` containers or networks, and `.candidate-artifacts/` returned to
  exactly its pre-run contents (`evidence.json`, `gc5-writer-evidence.json`, `image-build-record.json`,
  `image-evidence.json`, `image-verify-tmp`, `portfolio-service.jar`, `run-start.marker`).
- Hashes: smoke `5de500efc284f8f1d267fa7c6f558dac1acd8c9950706139c8f8bcac03872dc5` (691 lines),
  its tests `c589aa121cd58f1b5d1f5d7d2f1f8da3fda2694a846520130e6392aa3907fa9c` (388 lines); analyzer
  `dde3d215…`, guard tests `a14e4702…`, policy `78bf8596…` all unchanged; historical Task A/B evidence
  `be00a2e1…` / `624e5df5…` unchanged.
- No commit, push, PR, merge, release-candidate build, registry access, ACR login, live-database or
  ledger action. The `--registry-digest` / `--authorized-release-run` path was **not** exercised.

## Round 11 (2026-09-04) — comment-invariant executable-block selection

**Authorship:** Codex implemented this fix in its own worktree
(`…-codex`, branch `codex/taskc-sql-comment-guard`) while Claude was usage-limited, and handed over
`taskc-comment-fix.patch`. Claude reproduced all four defects in its own checkout first, confirmed the
patch applied cleanly to its exact preimage, applied the `scripts/` hunks, and independently verified
the result. Clean application establishes only that the patch CONTEXT was compatible; the evidence for
byte-identical transfer is that, after restoring LF line endings which `git apply` had converted to
CRLF, Claude's files hash to **exactly Codex's reported artifact** (`de9faed7…` / `f4c3169d…`).
Claude then extended it (below) and re-ran everything.

Defects, all reproduced on Claude's pre-patch analyzer `7fc13bef…`:

| Migration statement | Before | After |
|---|---|---|
| leading `--` comment (LF/CRLF/CR) + `DO` block | **no subject** | `sql:DO` UNREVIEWED |
| leading block / nested block comment + `DO` block | **no subject** | `sql:DO` UNREVIEWED |
| leading comment + dynamic `DO` | **no subject** | `sql:UNSUPPORTED` UNSUPPORTED |
| `DO $$ DECLARE c … BEGIN EXECUTE c; END; $$` | `sql:DO` UNREVIEWED | `sql:UNSUPPORTED` UNSUPPORTED |
| `INSERT … VALUES ('EXECUTE ''x''')` (data literal) | UNSUPPORTED (wrong) | UNREVIEWED |

Mechanism: `assess_sql` now carries token-derived `executable_block` and `dynamic_sql`; the migration
selector and the Java writer path consume those instead of `re.match(r"\s*DO\b")` and
`re.search(r"\bEXECUTE\s+format\(|\bEXECUTE '")` over normalized text. Unmodeled dynamic execution
becomes UNSUPPORTED coverage **before** subject selection, so it cannot be dropped by the no-verb exit.

**Claude's extension beyond the patch.** A top-level `EXECUTE <prepared-statement>` — outside any `DO`
block or routine body — still produced **no subject**, because the patch's procedural gate required a
`DO` block or a function/procedure definition. That is executable code whose command this analyzer never
saw, so it now counts as a procedural context and is UNSUPPORTED. Separately, verb-less unmodeled
execution is now labelled `sql:UNSUPPORTED` rather than `sql:DO`; verbs and persistent objects keep
their own labels first, so V17's dynamic functions retain their existing
`sql:CREATE OR REPLACE FUNCTION:` subject ids. Both are covered by a new regression asserting the
label, the kind and the id stability, plus controls proving `execute` as data or inside a comment is
not unmodeled execution.

## Verification record — round 11 (2026-09-04, in Claude's worktree)

- Guard suite: **217 tests OK, zero skips** (469 s, Docker present, `-X utf8`). Task A: 39 OK.
  Task B: 34 OK, zero skips — the artifact-dependent checks Codex had to skip run here because this
  worktree holds the original `.candidate-artifacts/`.
- Cross-check of the transfer: after restoring LF, the applied patch produced files hashing to Codex's
  reported `de9faed7…` / `f4c3169d…` exactly, before Claude's extension.
- Real repo at `ebb96f3a`: BLOCKED, `candidate_ready: false`, **504 findings — zero delta**, nothing
  added, removed or changed; all three V18/V19 `sql:CALL` subjects present. All 68 real migration
  statements assess as RESOLVED (none newly unsupported). Policy and historical evidence unchanged.
- Final hashes: analyzer `dde3d2158f75e477a1f4343d794a2b8046dce179fef65dee10581bee69cc9fb6`
  (4560 lines), tests `a14e4702abc38a666b0011c9a7bbc4062e3d7ee8de59dea343ba8d45e2219ae0`
  (3516 lines). Policy `78bf8596…`; Task A/B evidence `be00a2e1…` / `624e5df5…`.
- No commit, push, PR, merge, **release-candidate build**, registry, live-database or ledger action.
  (The suites do perform local, disposable Docker fixture builds — that is test scaffolding, not a
  candidate build.) No disposition, exception or attestation authored for the real repository. Docker
  left clean (no images/containers).

## Round 6 (2026-09-04) — two focused corrections

1. **Read-only gating (F12a).** `read_only_accounted(op, receiver_type, store)` is the ONE predicate,
   evaluated AFTER `resolve_receiver`, consumed by `writer_inventory` and `governed_modules`
   (persistence accounting derives from the same ops). A parsed SELECT on an unknown, undeclared or
   non-relational receiver is demoted to effect classification and blocks as UNRESOLVED with
   `basis.read_only_statement_demoted`. `sql_read_only_problem` replaces the denylist with an explicit
   allowlist (`READ_ONLY_SQL_BUILTINS`): any invoked routine not in it — including one merely absent
   from the tracked migrations, e.g. `SELECT external_mutator()` — is "a routine whose effects source
   analysis cannot establish" and the statement is a writer (UNRESOLVED/UNREVIEWED by table set).
   Positive retained: known `JdbcTemplate` receiver with `SELECT 1`, `count(*)`, `coalesce(max(...))`,
   `lower(...)`, `now() - interval`.
2. **CANDIDATE provenance (F12b).** `runtime_base_digest` must match `_PINNED_BASE_RE`
   (`repository[:port]/path@sha256:<64 hex>`; `scratch`, a floating tag and arbitrary text are
   rejected). The run input's `task_b.build_record` (the producer's `image-build-record.json`) is
   **required in CANDIDATE** and validated whenever supplied by `task_b_build_record_problems`:
   `image_id == local_image_id`, `base_digest == runtime_base_digest`, `platform == requested_platform`,
   the recorded Dockerfile exists, its bytes hash to `dockerfile_sha256`, and Task B's `recipe` names
   that same file. Its input hash is recorded; `evidence.task_b.build_record_bound` is reported. The
   CANDIDATE fixture now drives the producer's FULL build path (`verify_candidate_image` with
   `base_ref="busybox"`, resolved to `busybox@sha256:…` before the build; needs Docker and one cached
   pull of busybox) — the scratch image remains only for LOCAL_PREPARATION extraction/hash tests.
   Negatives, one property each: Codex's exact probe (arbitrary base + nonexistent recipe), floating
   tag, conflicting recorded base, missing recipe, changed recipe bytes, image identity mismatch in
   the record, missing build record.

Supersedes `2026-09-03-b1-rc-taskc-v3-consolidation-handoff.md` for state; that document's
architecture summary and hard rules still apply.

## Where the work lives

- Worktree: `D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-claude`
- Branch: `claude/b1-r-c-candidate-preparation`, cut `ebb96f3a6a22046ff5f3d449efcb146990b57ec9`,
  B1-base `95fcb68dc7a47f99465354ec6d7b84137851389d`.
- **Everything is UNTRACKED / local. Nothing is committed or pushed.**
- Files (all untracked): `scripts/check_b1_candidate_source.py` (3939 lines),
  `scripts/tests/test_check_b1_candidate_source.py` (2931 lines, **182 tests**),
  `scripts/b1-candidate-policy.json` (unchanged this round), `scripts/b1_candidate_evidence.py`
  (one additive helper, see below), `docs/runbooks/B1_R_C_CANDIDATE_VERIFICATION.md` (updated).
- Reviewer input for this round: `C:/Users/pc/.codex/visualizations/2026/09/03/01a065fd-9ffa-73a0-a909-91d88e16e5a2/B1_RC_TaskC_post_consolidation_review.md`.

Run (from the `-claude` worktree; the guard suite takes ~4 min, run it in the background):

```
python -B -m unittest discover -s scripts/tests -p test_check_b1_candidate_source.py
python -B -m unittest discover -s scripts/tests -p test_b1_candidate_evidence.py
python -B -m unittest discover -s scripts/tests -p test_verify_b1_candidate_image.py
```

The guard suite needs a running Docker daemon: evidence-binding positives build a real `FROM scratch`
image carrying a real JAR (no network) and the CANDIDATE end-to-end produces Task B evidence with the
accepted producer. Docker-dependent tests skip with an explicit message when the daemon is absent.

## What changed this round (one item per reviewed trust boundary)

1. **Evidence (F7).** `b1_candidate_evidence.normalize_sha256_digest` is the ONE digest parser (bare hex
   from `sha256_file`, `sha256:`-prefixed from Docker). `task_a_schema_problems` /
   `task_b_schema_problems` require every semantic field with its type (missing `problems`,
   `platform`, `requested_platform` block). Run mode is threaded in: a CANDIDATE run accepts only
   Task A `run.mode == CANDIDATE` and requires Task B `runtime_base_digest`/`recipe`.
   `verify_evidence_artifacts` re-hashes the staged JAR at the producer-recorded path (compared with
   both bundles), inspects the immutable image id, checks platform, re-extracts `/app.jar` and hashes
   it. Output carries `evidence.artifacts_verified` and `artifact_checks`.
   *Deliberate reading:* Task B's `label` stays `LOCAL_PREPARATION` by producer contract (runbook
   release procedure: "the field will keep reading LOCAL_PREPARATION until push/registry evidence
   exists"), so a CANDIDATE run does not demand a different label; the registry portion's absence is
   reported in `candidate_ready_blocked_by`. Codex may want to confirm this reading.
2. **Record graph (F8).** `validate_envelope_records` validates EVERY record (memoised, cycle-aware,
   duplicate-revision-aware) and annotates `_valid_problem`; a claim bound to an invalid record is
   `DISPOSITION_INVALID`; invalid non-latest records are reported as `envelope` findings.
   `_attestation_problem` enforces an exact partition. Renewals: `affected_claims` entries are
   `{path, subject_id}` objects that must exist at one of the two reviews inside the envelope, and
   every subject whose Tier-0 fingerprint changed R_old→R_new must be listed. Identity now includes
   `reviewed_at` and `membership_digest`; `revision` is raw (0 ≠ 1); `roots`/`membership` documented
   non-normative.
3. **Historical subject index (F9).** `analyze_tree` + `unit_operations` + `subject_index` are the one
   code path for identity/fingerprint at the cut and at any reviewed commit (`HistoricalIndex.at`);
   it covers Java operations, entity setters and SQL subjects, honours policy exclusions and
   `non_persistence_receiver_types` (the old `_analysis_at_commit` used defaults only — a latent
   divergence, now gone).
4. **SQL-aware reads (F10).** `SQL_QUERY_METHODS` resolve their statement; `sql_is_read_only` (leads
   SELECT/WITH/VALUES/EXPLAIN/SHOW, no DML/DDL, no CALL/DO/PERFORM/INTO/LOCK/sequence, no persistent
   function from the tree's migrations); read-only → `Operation.access == "read"` accounts for the
   receiver; DML → writer; unresolved → UNSUPPORTED. JPQL entity targets mapped via the entity index
   (`classify_effect(..., entity_index)`), unmapped → UNRESOLVED. `READ_ONLY_DATA_METHODS` trimmed to
   statement-free methods.
5. **Exceptions and CANDIDATE (F11).** `content_guard` consults `content-governance` exceptions scoped
   by `symbols`+`non_goals` (blob-bound). `_exception_provenance_problem` requires reviewed_commit
   strictly inside (base, cut], pre-image at base == `src_blob`, post-image at reviewed_commit ==
   `dst_blob` (absent for deletion). `path_guard`/`content_guard` take `(repo, base_sha, cut_sha)`.
   CANDIDATE requires `HEAD == cut` and compares `worktree_object_id` (`git hash-object --path
   --stdin`) with the blob id. Fixture: `core.autocrlf=true`, files re-checked-out as CRLF, evidence
   outside the repo, producer-generated Task A (`run_evidence`) and Task B (`verify_candidate_image`
   with `skip_build` + a build record, real scratch image).
   *Deliberate reading:* the transition proof is over `base..reviewed_commit` for the path (pre-image
   at the base, post-image at the reviewed commit) rather than `reviewed_commit^..reviewed_commit`,
   because the guard's finding is the cumulative base→cut change and a multi-hop history has no
   single first-parent step equal to it. Codex asked for "parent/pre-image must equal src_blob";
   this is the interval-cumulative form of that requirement and rejects the measured pre-add probe.
6. **P2.** Run-input JSON is loaded inside the `EvidenceError` handler (clean `ERROR:`); the
   duplicate `__main__` block is gone.

## Round 7 (2026-09-04) — complete SQL routine identity

Codex reproduced two false PASSes in the round-6 allowlist scan: `SELECT "external_mutator"()` (the
quoted routine was never seen as a call) and `SELECT custom_schema.lower('value')` (the qualifier was
discarded and `lower` accepted as the built-in). Correction, confined to the shared SQL-call
classification: `scrub_sql_literals_and_comments` replaces standard and dollar-quoted literals with `''`
and drops comments while keeping double-quoted identifiers verbatim, and returns None for literal
syntax the analyzer does not model (`E'...'`, `B'...'`, `X'...'`, unterminated literal/comment) — which
`sql_read_only_problem` reports as blocking rather than as an absence of calls. `sql_call_names`
tokenizes the scrubbed SQL and reports every call as its COMPLETE name (bare or quoted segments,
whitespace allowed around dots). Supported read subset: an unqualified bare allowlisted built-in, or the
same built-in qualified by `pg_catalog` (`READ_ONLY_BUILTIN_SCHEMAS`). Any quoted segment, any other
schema qualifier, a migration-defined routine, or an unknown bare routine blocks with a reason naming
the full call. Regression gate: the two measured forms, a fully quoted qualified name, whitespace/comment
around the qualifier, `"lower"()`, `pg_catalog.external_mutator()`, `other.pg_catalog.lower()`; positives
for `lower`, `count`, `pg_catalog.lower`, literal/comment text shaped like calls, and plain grouping
parentheses; fixture-level runs of both measured queries block on the `db.queryForObject` subject.

## Round 8 (2026-09-04) — lexical boundaries on the original SQL

Codex reproduced five false PASSes in the round-7 scan: `normalize_sql` ran BEFORE comment
recognition, so a `--` comment swallowed the call on the next line; nested block comments were closed
at the first `*/`; and the token regex split `élower` into `é` + `lower`, dropped `函数` as
non-identifier noise, and read the `$tag$` inside `evil$tag$body$tag$` as a dollar quote. Correction
(one lexical contract, `lex_sql`): input is the ORIGINAL resolved Java string (`extract_operations`
passes `value`, and `sql_read_only_problem` raises TypeError on normalized facts); every span is a
token, literal, comment or whitespace, or `SqlLexError` (reported as "does not model", blocking);
line comments end at the original LF/CR; block comments nest by depth; bare identifiers are consumed
atomically and rejected if they continue into a non-ASCII character or start with one; `$` inside an
identifier belongs to it, and dollar quoting / `$n` params are recognized only at token boundaries;
prefixed literals (`E'`, `B'`, `X'`, `N'`, `U&`) and trailing junk after a number are rejected. Lead
verb and non-read constructs are decided on tokens (`NON_READ_TOKENS`, so `nextval_like` is a routine,
not `NEXTVAL`), and the call scan is the round-7 complete-name rule over tokens. Regression matrix:
the five measured fixtures block on `db.queryForObject`; comments at token boundaries (LF/CRLF/CR,
leading/middle/trailing, nested) never turn an unknown routine into a read; comment/literal text shaped
like calls never becomes one; extending a built-in spelling (`lowerx`, `lower$x`, `lower1`, `élower`,
`loweré`) never keeps clearance; unsupported forms (`E'`, `B'`, `X'`, `N'`, `U&`, unterminated
literal/comment/dollar-quote, `1abc`, `""`, curly quotes, empty) produce a blocking problem.

## Round 9 (2026-09-04) — the SQL assessment survives into the final classification

Codex reproduced that a lexer rejection or an unknown routine was flattened to a boolean and the
operation then became an ordinary `RESOLVED` write whose regex-derived tables (from comment/literal
text) could clear it: `SELECT external_mutator() /* UPDATE market_prices SET x=1 */` returned PASS.
Correction (F13): `assess_sql` is the ONE structured assessment — `coverage` (RESOLVED/UNSUPPORTED
with `lexical_error`), `facts`, `unknown_routines` (complete names + reasons, assessed for writes too),
`read_only`. `extract_operations` sets `coverage=UNSUPPORTED` on a lexical failure (blocking
`writer-coverage` finding carrying the reason; dispositions and effect resolutions never reach it) and
carries `unknown_routines` inside `statement`; `classify_effect` returns UNRESOLVED for any lexical
error or unknown routine regardless of known direct targets. `sql_facts` now derives verbs/target
tables from executable TOKENS (`_executable_verbs_and_tables`) — comment/literal DML is data — while
`sql` keeps the normalized text (literals included) so fingerprint identity is unchanged in nature.
Procedural dollar-quoted bodies (`DO $$…$$`, `CREATE FUNCTION … AS $$…$$`) are CODE: they are lexed
recursively and their verbs/tables/calls count (`_expand_procedural_bodies`); a dollar string anywhere
else stays data. `INSERT INTO t (cols)` / `CREATE TABLE t (` / `REFERENCES t (` are table references,
not calls (`_TABLE_INTRO_KEYWORDS`). `extract_sql_subjects` passes the ORIGINAL statement to
`sql_facts`; a migration statement outside the lexical subset becomes an UNSUPPORTED subject
(`sql:UNSUPPORTED:<digest>`) instead of being skipped. All 68 real migration statements lex.
Regression gate: Codex's five rows plus relevant/unrelated incidental spellings; DML-shaped
comment/literal text yields no verbs/targets while staying in `sql`; a lexical failure survives a
correctly fingerprinted disposition AND an effect resolution; a known direct target plus an unknown
routine stays UNRESOLVED while the same plain disjoint write is listed non-blocking.

## Round 10 (2026-09-04) — executable migration blocks always produce a subject

Codex reproduced PASS with zero SQL subjects for `DO 'BEGIN DELETE FROM portfolios; END;'`,
`DO LANGUAGE plpgsql $$…$$` and `DO $$ BEGIN PERFORM external_mutator(); END; $$`. Two causes, both
closed (F14): `_expand_procedural_bodies` now treats a `DO` statement's FIRST string literal (dollar
or single-quoted, before or after `LANGUAGE`) and a `CREATE FUNCTION/PROCEDURE … AS <literal>` as the
executable body; a `DO` with no body literal, or any `LANGUAGE` outside `SUPPORTED_BODY_LANGUAGES`
(`plpgsql`, `sql`), raises SqlLexError so the statement is an UNSUPPORTED subject rather than inert
data. `extract_sql_subjects` consumes `assess_sql` (the same structured assessment as the Java path):
a statement produces a subject when it has verbs, a persistent object, a lexical error, an unknown
routine call, or is a `DO` block at all — labels `sql:<VERBS>`, `sql:PERSISTENT`, `sql:CALL`,
`sql:DO`, `sql:UNSUPPORTED`. Ordinary literals in normal statements (`INSERT … VALUES ($$…$$)`,
`SELECT $$…$$`) remain data. Regression gate: the three measured rows, the direct-DML control,
`LANGUAGE` before/after the body, single-quoted `AS` body, unsupported language (`plpython3u`, `c`),
body-less `DO`, a RAISE-only `DO` (subject `sql:DO`), top-level unknown call, and a data-literal
positive — asserting subject label, kind, detail and overall BLOCKED/PASS.

Also in this round: a name followed by `(` after `INTO` / `TABLE` / `REFERENCES` / `ONLY` — walking
back over `IF NOT EXISTS` — or after `ON` inside a `CREATE INDEX` statement is a table with a column
list, not a call (`FROM f()` and `JOIN t ON f(x)` remain calls); `gen_random_uuid`,
`uuid_generate_v4`, `random`, `md5` joined the side-effect-free built-ins.

## Verification record — round 10 (2026-09-04)

- Guard suite: **212 tests OK** (483 s, Docker-backed, `-X utf8`). Task A: 39 OK. Task B: 34 OK.
- Codex's three rows each yield exactly one SQL subject and BLOCKED: `DO 'BEGIN DELETE …'` and
  `DO LANGUAGE plpgsql $$…$$` → `sql:DELETE FROM` UNREVIEWED targeting `portfolios`;
  `DO $$ … PERFORM external_mutator() …$$` → `sql:CALL` UNREVIEWED naming the routine. Unsupported
  language / body-less `DO` → `sql:UNSUPPORTED`; RAISE-only `DO` → `sql:DO`; data literals stay data.
- Real repo at `ebb96f3a`: BLOCKED, `candidate_ready: false`, **504 findings** (was 501). Delta: exactly
  three added, nothing removed — the V18 and V19 `DO` blocks invoking `repair_migrate_holdings`,
  `repair_migrate_market_prices` and `repair_migrate_history` now surface as `sql:CALL` UNREVIEWED
  subjects (these are the R3 call sites). All 68 real migration statements lex. Policy and
  historical evidence unchanged.
- Hashes: analyzer `7fc13befb01986b7b30d191e81f37c3d0f5740fc59743d6fb30a5a1fcba3ab87` (4536 lines),
  tests `4667eaf46df90fa6771a9f7c26e6b05bf70c9f6345e189ee126ca1585f3936c1` (3432 lines).

## Verification record — round 9 (2026-09-04)

- Guard suite: **208 tests OK** (501 s, Docker-backed, `-X utf8`). Task A: 39 OK. Task B: 34 OK.
- Codex's five rows block on the `db.queryForObject` subject: lexical failures as `writer-coverage`
  UNSUPPORTED ("does not model"), unknown routines as `writer-inventory` UNRESOLVED naming the call;
  relevant/unrelated incidental spellings give identical verdicts; a lexical failure survives a
  fingerprinted disposition + effect resolution; `DELETE FROM market_prices WHERE external_mutator()`
  stays UNRESOLVED while the routine-free version is listed non-blocking.
- Real repo at `ebb96f3a`: BLOCKED, `candidate_ready: false`, **501 findings** (was 502). Delta:
  one V17 SQL subject relabelled (`sql:DELETE\n FROM,INSERT INTO:d7e5343b` → `sql:DELETE FROM,INSERT
  INTO:d7e5343b`, same digest) and one V17 false positive removed (`sql:TRUNCATE:fa2fcd76` — the
  word "truncate" occurred only in a header comment of a `CREATE TABLE`). All 68 real migration
  statements lex; `read_only_statements` 10 → 10. Policy and historical evidence unchanged.
- Hashes: analyzer `648f54ae765e19e0ef6a61b08cd89d24159d50542712e68e2c2709e516089525` (4463 lines),
  tests `6fe5eb86c540ce0514d66847d0d3e569f8dccd8a701d0f80b266c996333ee1d7` (3357 lines).

## Verification record — round 8 (2026-09-04)

- Guard suite: **204 tests OK** (417 s, Docker-backed; run with `-X utf8`). Task A: 39 OK. Task B: 34 OK.
- All five measured lexical false PASSes block on the `db.queryForObject` subject as UNRESOLVED;
  the invariant matrix (comment boundaries, extended built-in spellings, literal/comment-shaped text,
  unsupported forms) passes.
- Real repo at `ebb96f3a`: BLOCKED, `candidate_ready: false`, **502 findings — zero delta** from round 7
  (`read_only_statements` 10 → 10). Policy and historical evidence unchanged.
- Hashes: analyzer `95ab63436133781a98b440c557d7fa8dfc2e21168b623bdeb8e461326b9cc069` (4293 lines),
  tests `007261bdec9789c08a542a0fc404c6fc62d5b551469aaaed7f3bbeb795c91925` (3257 lines).

## Verification record — round 7 (2026-09-04)

- Guard suite: **199 tests OK** (397 s, Docker-backed). Task A: 39 OK. Task B: 34 OK.
- Both measured queries (`SELECT "external_mutator"()`, `SELECT custom_schema.lower('value')`) now
  block on the `db.queryForObject` subject as UNRESOLVED with the full call named in the reason;
  `lower`, `count`, `pg_catalog.lower` and literal/comment text shaped like calls stay positives.
- Real repo at `ebb96f3a`: BLOCKED, `candidate_ready: false`, **502 findings — zero delta** from round 6
  (no finding added or removed; `read_only_statements` 10 → 10). Policy unchanged.
- Historical evidence unchanged (`be00a2e1…`, `624e5df5…`).
- Hashes: analyzer `a716a6b29ad0c477c364ab76dc247e1310b9b68894de8f945277f3359c44eaa6` (4206 lines),
  tests `01b9c839dd4ecfd6d1bf53eea05e7c01f4dd8f246953ccf50bd3985c2f1c4ab0` (3167 lines).

## Verification record — round 6 (2026-09-04)

- Guard suite: **196 tests OK** (388 s, Docker-backed). Task A: 39 OK. Task B: 34 OK.
- Codex's four read-only probes: known `JdbcTemplate` + `SELECT 1` stays PASS; `MysteryDb db`,
  undeclared `db`, and `SELECT external_mutator()` each block with the `db.queryForObject` subject
  UNRESOLVED (`receiver type could not be resolved` / routine effects cannot be established).
- Codex's CLI probe (arbitrary base + nonexistent recipe on the clean CRLF CANDIDATE fixture): now
  BLOCKED, exit 1, `evidence.task_b` null; plus six single-property negatives and a positive that binds
  the producer's build record (`busybox@sha256:…` pinned base).
- Real repo at `ebb96f3a`: BLOCKED, `candidate_ready: false`, **502 findings** (was 499). Exactly three
  added, all writer-inventory UNRESOLVED demoted reads whose receiver never resolved:
  `PostMigrationIntegrityAssertion::assertAuditTickersAreActive/2::jdbc.queryForList`,
  `…::assertReferentialInvariant/2::jdbc.queryForList`, and
  `DemoPortfolioInitializer::pgAdvisoryXactLock/1::connection.prepareStatement`
  (`SELECT pg_advisory_xact_lock(?)`). Nothing removed; `read_only_statements` 13 → 10. No policy tuning.
- Producer-backed LOCAL_PREPARATION binding (preserved Task A + rebuilt Task B): still bound and verified.
- Historical evidence unchanged (`be00a2e1…`, `624e5df5…`); policy unchanged (`78bf8596…`).
- Hashes: analyzer `755832dea201492cecf2a1d5a5ac3df2a961260f03cd671f50599b5e3777412c` (4083 lines),
  tests `b6da6f92d7d6ccd538c33925626f72ffe92eaa3285cd5754e1115e266a8dd7cd` (3117 lines).

## Verification record — round 5 (2026-09-03)

- Guard suite: 182 tests OK (see the session report for timing). Task A: 39 OK. Task B: 34 OK.
- Positive controls were added first and confirmed failing for the measured reasons (e.g. the SQL
  disposition failed with exactly "subject did not exist at reviewed_commit …; the claim predates the
  code it approves"; the query-path DELETE was hidden by a dispositioned update; the pre-add deletion
  exception passed).
- Real repo at `ebb96f3a`: `BLOCKED`, `candidate_ready: false`, 499 findings (was 498). Delta:
  persistence-usage UNSUPPORTED 37 → 33 (four files' `JdbcTemplate` marker is now accounted for by a
  resolved read-only SELECT); writer-coverage UNSUPPORTED 2 → 7 (five query-style calls with an
  unresolvable statement: `PostMigrationIntegrityAssertion::count/2::jdbc.queryForObject`, four
  `SpecA912StartupTransactionDiagnostics::*::statement.executeQuery`). Every other bucket identical
  (content 139; path 84/131; envelopes 4; per-holding 9; writer-inventory 23/67/1; R3 1). No policy
  tuning.
- Producer-backed LOCAL_PREPARATION binding: preserved Task A bundle + a FRESH Task B bundle produced
  by `verify_b1_candidate_image.verify_candidate_image` (the historical local image
  `sha256:983cf5…` no longer exists in the daemon), image
  `sha256:0337502e94c0d66ada8741e8567ee8189ba23b6fbd615c511a831cd8b1924ff2`, tag
  `wealth-portfolio-service:candidate-local-dev-rebuilt-20260903` (kept locally so the run can be
  reproduced) → `task_a bound, task_b bound, artifacts verified`, zero evidence-binding findings; the
  run itself stays BLOCKED on source governance. The fresh bundle and run outputs live under the
  session scratch directory, not in the repo.
- Historical evidence unchanged: `evidence.json` `be00a2e1…5e2bb582`, `image-evidence.json`
  `624e5df5…4de58bc`.
- Hashes: see the session report (analyzer `e4d43357…`, tests `ee592378…`, policy unchanged `78bf8596…`).

## Hard rules (unchanged)

No envelope attestations, exceptions or dispositions authored for the real repository; historical
Task A/B evidence byte-for-byte unchanged; R3 blocking; no commits/pushes; live-DB/registry/deploy/PR/
merge unauthorized; the three tracked `M` files are not ours. Bash heredocs corrupt backslashes — this
round used Write-to-scratch + a marker-based Python splice for the analyzer.

## Next

Both tooling checkpoints are accepted and the packet's documentation corrections are applied. The
only open item is the owner's decision on publishing the enumerated package; nothing further is
implemented or run until that decision, and every gate listed in the callout above stays closed.
