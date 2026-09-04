# B1 R-C Candidate Preparation — Session Handoff (Claude, session-to-session)

**No publication action pending and none requested.** Everything below is local: no push, PR,
merge, ACR access, or deployment has happened. This is a continuity handoff between Claude
sessions working the same assignment — read it, then continue directly; it is not a request for
new authorization and does not itself grant any.

**Assignment:** `docs/agent-instructions/CLAUDE_KICKOFF_B1_R_C_CANDIDATE_PREPARATION.md`, scoped by
`docs/superpowers/plans/2026-09-03-b1-r-c-candidate-architecture-review.md`. Task A's own handoff
(`2026-09-03-b1-rc-candidate-checkpoint-1-task-a-handoff.md`, same directory) has the detailed R1–R5
resolution table and is still accurate — this document picks up from there and covers Task B
(complete) and Task C (partial).

## Where things are

- Worktree: `D:\Projects\Development\Java\Spring\wealthmgmtandportfoliotracker-claude` (Claude's
  assigned worktree per `AGENTS.md`). Verify with `git rev-parse --show-toplevel` before any
  mutation, per that file's rule.
- Branch: `claude/b1-r-c-candidate-preparation`, currently at `ebb96f3a` (== `origin/main` — PR #221's
  kickoff-docs merge; nothing else has been pushed since).
- Working tree is **dirty** with this assignment's own uncommitted tooling (listed below) —
  expected; no one has asked for a commit or PR yet.
- Sibling worktrees exist for Codex, Cursor, and other in-flight Claude/Codex work (`git worktree
  list` shows them) — do not touch those without their owner's permission, per `AGENTS.md`.

### Uncommitted files (current `git status --short`)

```
 M .dockerignore
 M .gitignore
 M portfolio-service/build.gradle
?? docs/runbooks/B1_R_C_CANDIDATE_VERIFICATION.md
?? docs/superpowers/plans/2026-09-03-b1-rc-candidate-checkpoint-1-task-a-handoff.md
?? portfolio-service/Dockerfile.candidate
?? scripts/b1-candidate-policy.json
?? scripts/b1_candidate_evidence.py
?? scripts/check_b1_candidate_source.py
?? scripts/tests/test_b1_candidate_evidence.py
?? scripts/tests/test_check_b1_candidate_source.py
?? scripts/tests/test_verify_b1_candidate_image.py
?? scripts/verify_b1_candidate_image.py
```

94 unit tests across the three test files, all passing as of this handoff (39 + 34 + 21). Re-run
before trusting that:

```powershell
python -B -m unittest discover -s scripts/tests -p test_b1_candidate_evidence.py -v
python -B -m unittest discover -s scripts/tests -p test_verify_b1_candidate_image.py -v
python -B -m unittest discover -s scripts/tests -p test_check_b1_candidate_source.py -v
```

## Status by task

### Task A — Gradle verification graph, floor correction, discovery reconciliation

**Accepted by Codex** across 3 review rounds. Full detail in the Task A handoff doc. Headline: R2's
floor correction is 11 required class-pattern entries covering 10 conceptual suites (not 10 — a
precision correction from round 2 of that review); R4 pinned the B1-base commit
`95fcb68dc7a47f99465354ec6d7b84137851389d`; R3 (`repair_migrate_holdings`) stays explicitly
unresolved by design. Real Gradle run: 89 classes / 760 tests / 0 failures.

### Task B — Candidate image packaging (local portion)

**Accepted by Codex** across 3 review rounds, each round finding real bugs via independently
reproduced fixtures (not just review commentary) — expect the same rigor on Task C:

1. Round 1: status-text-based drift digest missed edits to already-dirty files; no bootJar/report
   freshness check; discovery dropped renamed files; `--base-sha` accepted an unpinned override.
2. Round 2: `candidateManifestValidation` (the pre-staging gate) did no freshness checking; the
   corrected regression test for round 1 still didn't actually exercise the bug it claimed to.
3. Round 3 (image-specific, `verify_b1_candidate_image.py`): the staged JAR's hash wasn't bound to
   Task A's own recorded evidence (a swapped file would pass); the base image was resolved *after*
   the build instead of before+pinned (TOCTOU); the built image's identity was re-read from the
   mutable tag instead of captured via `--iidfile` at build time; a real Docker regression test's
   cleanup missed the original (now-untagged) image. All fixed; `docker_build()` now takes an
   `iidfile` param and returns the image ID directly; `--skip-build` provenance is `"verified"` only
   when a build-record file this tool itself wrote still matches the tag's current image ID,
   otherwise `"unverified"` with fields nulled, never trusting caller-supplied claims.

Files: `portfolio-service/Dockerfile.candidate` (copy-only, Azure Mariner base, no in-container
Gradle), `scripts/verify_b1_candidate_image.py`, `scripts/tests/test_verify_b1_candidate_image.py`
(34 tests, 3 of them real Docker end-to-end). Real run confirmed: `provenance: "verified"`,
`platform` matches `requested_platform` (`linux/amd64`), hashes match, base digest resolved to
`mcr.microsoft.com/openjdk/jdk@sha256:e59e5d626eb216745bb1bb69a84adba78d7724a55e0132995dccb3483b10fac7`.

**Not done (explicitly deferred, owner-gated):** registry push, registry-manifest-digest resolution.
The runbook's "Release procedure" section documents the planned one-build/push sequence with an
explicit owner-authorization gate *before* the release-candidate build itself (not just before
push) — read it before ever executing any of those steps.

### Task C — Source governance (GC.5) + writer inventory: **done, NOT yet reviewed by Codex**

This is the one piece from this session that has not been through a Codex review round —
**treat it as provisional**, likely to need at least one fix round like Tasks A and B did.

Built and self-verified (21/21 tests, including a real-repo smoke test):

- `scripts/check_b1_candidate_source.py` — three independent checks:
  - **Path guard**: `git diff --no-renames --name-only <B1-base>..HEAD` (451 files in the real
    interval) against `policy.gc5.always_allowed_globs` (test fixtures, docs, workflow files) and
    `policy.gc5.forbidden_paths` (10.1 frontend/src/**, 10.2 presence package, 10.3
    ReadOnlyEnforcementFilter.java, 10.6 FX/valuation/refresh-pipeline files). **A forbidden-path hit
    is evidence requiring an explicit reviewed disposition, not an automatic B1 violation** — the
    interval genuinely contains legitimate concurrent B2 work (real run: 83 hits, all confirmed as
    B2's asset-picker/presence feature work). `policy.gc5.reviewed_exceptions` is empty; this tool
    will never auto-populate it.
  - **Content guard**: independently scans the diff's *added* lines for forbidden symbols
    (reused from `api-gateway/.../presence/DemoPresenceSourceGuardTest.java`'s own list) across
    `.java`/`.sql`/`.ts`/`.tsx`/`.py` files only (excluding `.md` — a design/spec doc describing an
    unrelated track's own feature legitimately mentions the same strings; confirmed this was
    producing 162 false positives from `.kiro/specs/asset-picker-composition/*.md` before the fix).
    Also runs a structural (field-list, not string-match) check for 10.4's per-holding-freshness
    non-goal, scoped to the actual `HoldingResponse`/`AssetPriceFreshnessDto` record components via
    balanced-paren parsing. Real run: 139 symbol hits, all confirmed genuine — including the
    negative-fixture case itself (`JwtAuthenticationFilter.java` and several `src/test/**` files
    reference presence symbols despite being outside the forbidden path *and* inside the path
    guard's own allowlist — caught only because the two guards are independent). Freshness check:
    clean.
  - **Writer-inventory recheck**: greps all `src/main` Java repo-wide for write-like calls
    (`.save(`/`.delete(`/JdbcTemplate update-execute/raw `Statement.executeUpdate`) and requires
    every hit to already be classified in `policy.writer_inventory`. Real run: 10 hits, **0
    unclassified**. `unresolved` (R3) always keeps this `BLOCKED` regardless.

- `scripts/b1-candidate-policy.json` gained `gc5` and `writer_inventory` top-level sections. The
  writer-inventory research (6 parallel agents) found a genuinely new, previously-undocumented
  writer: **`api-gateway`'s `UserCredentialRepository.insertPortfolio()`**, called from
  `SignupService.provision()` on every signup — an independent raw INSERT into `portfolios`, outside
  `HoldingReplacementService`'s CAS entirely. Recorded as `PROPOSED_SAFE_PENDING_REVIEW` with full
  rationale (INSERT-only, new row, V20's `UNIQUE(user_id)` makes a conflict fail the transaction
  rather than bypass anyone's version) — **this needs Codex/owner confirmation, not silent
  acceptance.** Also recorded: the DB-level `ON DELETE CASCADE` FK (V1:22) as an ungated structural
  risk note (nothing currently triggers it, but nothing gates it either), and `Portfolio.
  replaceAllHoldings()` as dead code (zero call sites).

**Not started: the exact-digest HTTP smoke harness.** See the research findings preserved below so
the next session doesn't have to re-derive them.

## Smoke-harness design research (already done — do not re-research from scratch)

A 6-agent research pass (2026-09-03) already answered the conventions questions. Key facts:

- **Testcontainers Postgres**: `org.testcontainers.postgresql.PostgreSQLContainer`, image
  `postgres:18.4` (`TestContainerImages.POSTGRES`,
  `portfolio-service/src/test/java/com/wealth/portfolio/TestContainerImages.java:22` — pinned to
  match the Azure production Neon Postgres 18.4 baseline). DB `portfolio_db`, user/pass
  `wealth_user`/`wealth_pass` (matches `docker-compose.yml`'s postgres service).
- **Synthetic identities**: `E2E_USER_ID = 00000000-0000-0000-0000-000000000e2e`
  (`PortfolioSeedController.java:25`) and `DEMO_USER_ID = 00000000-0000-0000-0000-0000000d3110`
  (`DemoPortfolioInitializer.java:42`) are compiled-in, server-fixed — never caller-supplied, and
  both target production-scheduled jobs against the *real* database, so they are safe to reuse only
  because the smoke harness runs against its own disposable, empty-schema Postgres. For the
  composition PUT (not identity-fixed), the repo convention is `UUID.randomUUID()` per run
  (`ConcurrentCompositionIT.java:783-785`).
- **409 envelope, exact wire shape** (`ContractError.java`, `GlobalExceptionHandler.java:74-80`):
  ```json
  {"error":"portfolio_version_conflict","message":"<text>","currentVersion":<long>}
  ```
  (`ticker`/`tickers`/`catalogVersion` are `@JsonInclude(NON_NULL)`-omitted.) Test-confirmed shape:
  `CompositionErrorContractTest.java:195-209`.
- **Profile/port requirements are the hard part.** `Dockerfile.candidate`'s entrypoint sets no
  `SPRING_PROFILES_ACTIVE` — whatever the harness passes via `docker run -e` decides. The review
  explicitly wants prod/Azure profile differences recorded, and prod uses **port 8080** (not 8081,
  which is only a Dockerfile `EXPOSE` comment). Under `prod` profile
  (`application-prod.yml`): `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` are **required, no default**;
  `common-dto/.../application-prod-kafka.yml` requires `KAFKA_BOOTSTRAP_SERVERS`,
  `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` (**required, no default**, `security.protocol:
  SASL_SSL` by default) and `spring.kafka.admin.auto-create: true` (attempts real `CreateTopics`
  against the configured broker at startup — listener `auto-startup=false`, the trick every `*IT`
  test uses, stops listener containers but **not** this admin topic-creation attempt). Redis has a
  `localhost:6379` default so it's not strictly required to boot. **Decision needed:** either stand
  up a disposable Testcontainers Kafka (`TestContainerImages.KAFKA =
  confluentinc/cp-kafka:8.2.0`) with a `security.protocol=PLAINTEXT` override (documented as an
  explicit, intentional environment difference from real prod SASL_SSL), or find another way to
  satisfy the admin auto-create requirement without a real broker — recommend the disposable-Kafka
  route since it's what "any other required dependencies" in the kickoff's own Task C checklist
  implies, and document every override explicitly as the kickoff requires.
- **Boot/health-check pattern to model on**: `.github/workflows/ci-verification.yml`'s
  `docker-build-verify` job (lines ~447-543), **not** `azure-image-smoke-test` (which never boots
  the app at all — it overrides the entrypoint to run a standalone probe jar). The real pattern:
  `docker compose up -d`, then poll `docker inspect --format='{{.State.Health.Status}}'` per
  container every 10s up to 6 minutes, matching each service's own `healthcheck:` block in
  `docker-compose.yml` (portfolio-service's is `curl -sf
  http://localhost:8081/actuator/health/readiness` — note that's the *local*-profile port; under
  prod profile the equivalent probe would be port 8080). A new harness should build its own minimal
  Docker network + containers (Postgres, maybe Kafka, the candidate image) with `docker network
  create`/`docker run`, not necessarily full docker-compose, to stay scoped to exactly what Task C
  needs.

## Operating rules learned this session (do not relearn the hard way)

- **Freeze edits from `mark` through `evidence` capture.** The source-identity check hashes every
  changed/untracked path's content — even editing an unrelated scratch file mid-run registers as
  drift and invalidates that run's evidence. Established and now documented in the runbook itself.
- **Real Gradle runs take ~9 minutes; real Docker builds are fast once the base image is cached.**
  Don't edit `scripts/b1_candidate_evidence.py` (or anything a running Gradle invocation touches)
  while a background run is in flight — this session hit exactly that race once and had to redo a
  run.
- **Codex reviews with real, independently-reproduced fixtures, not just commentary** — every round
  found genuine bugs this way (encoding crashes, TOCTOU races, false positives from over-broad
  regexes, test fixtures that didn't actually exercise the bug they claimed to). Expect multiple
  rounds on Task C's still-unreviewed work; that is normal and productive, not a sign anything went
  wrong.
- **Claude implements, Codex/owner decides governance.** Ambiguous policy calls (R3, the api-gateway
  writer's disposition, any future GC.5 `reviewed_exceptions` entry) get proposed with full evidence
  and left for explicit confirmation — never resolved unilaterally, even when the evidence strongly
  suggests an answer.
- **Verify real command/tool behavior before documenting or relying on it.** Several real bugs this
  session were only found by actually running the tooling against real Docker/git, not by reasoning
  alone: BuildKit not registering a base image as locally inspectable until explicitly pulled, a
  Windows cp1252 decode crash on `subprocess.run(..., text=True)` without an explicit encoding, and
  regex-based checks over-matching until run against real source.
- **Clean up Docker images/containers after every real-Docker test run** — `docker images
  --filter "reference=b1-candidate-test-*"` / `docker ps -a --filter "name=b1-candidate"` before
  reporting done; the established `docker_rmi_all()` test helper removes by owned image ID (not just
  tag name, which leaves a retagged original dangling) and asserts cleanup didn't itself fail.

## Suggested next steps

1. Re-run all 94 tests plus `python -B scripts/check_b1_candidate_source.py --out
   .candidate-artifacts/gc5-writer-evidence.json` to confirm nothing drifted since this handoff.
2. Write up Task C's GC.5/writer-inventory checkpoint for Codex review (mirroring Task A/B's
   handoff-doc pattern) and expect a fix round.
3. Design and implement the HTTP smoke harness using the research above — likely
   `scripts/smoke_b1_candidate_image.py` or similar, following `verify_b1_candidate_image.py`'s
   established shape (pure assertion functions separated from Docker-orchestration functions, a
   handful of real end-to-end tests plus broader mocked negative-fixture coverage).
4. Once Task C's local preparation is fully accepted, the kickoff's "Verification and return
   packet" section (not yet started) is the final deliverable — a consolidated report of all of
   Checkpoints 1–3 for Codex, proposing (not applying) the governed-doc wording for
   `.kiro/specs/portfolio-composition-contract/tasks.md`/`docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`.
