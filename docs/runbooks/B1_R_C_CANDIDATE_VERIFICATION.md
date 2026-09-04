# B1 R-C Candidate Verification Runbook

**Scope today:** Task A (the Task 7.4/7.5 Gradle verification graph, floor acceptance, and discovery
reconciliation) plus Task B's **local** packaging portion (the copy-only `Dockerfile.candidate`
recipe and its extraction/hash-equality proof)
([tasks.md:1095-1190](../../.kiro/specs/portfolio-composition-contract/tasks.md)). Task B's registry
portion (ACR push, manifest-digest resolution) and Task C's exact-digest HTTP smoke harness are
**not implemented yet**. Task C's source-governance/writer-inventory guard IS implemented
(`scripts/check_b1_candidate_source.py`, contract `gc5-contract/3`) but is **not cleared**: it
reports findings that require reviewed dispositions, which no tool may grant itself. Implementation
status and evidence status are separate facts here — shipping the guard cleared nothing; the "Release procedure" section
below documents the planned steps without making them executable. Running the steps in this runbook
produces local candidate-graph and local-image evidence only — `candidate_ready` is hard-coded
`false` in every result today (see `candidate_ready_blocked_by` in the evidence JSON). No ACR
access or deployment happens anywhere in this document; a local Docker build against a
Microsoft-published base image is the only network access step 5 below performs.

Owner approval note: nothing in this runbook requires owner authorization to run — it is entirely
local (source, Gradle, and this checkout's own git history). The *release* procedure stub below
requires its own separate owner authorization before any of it may be executed; see that section.

## Prerequisites

- Java 21 and the repository's Gradle wrapper (`./gradlew.bat` / `./gradlew`).
- Python on `PATH` as `python` (repository convention; used with `-B` to skip `.pyc` caching).
- A running, accessible Docker-compatible container runtime. `integrationTest` runs required suites
  (`*ConcurrentCompositionIT`, `*DecimalFidelityIT`, `*PortfolioSeedServiceIT`, `*V20MigrationIT`,
  and others) against real PostgreSQL Testcontainers — step 2 below fails outright without one. This
  is unrelated to Task B: **Task A does not build the candidate application image**; Testcontainers
  pulls its own disposable images for the test run only.

## Local verification procedure

Run every step from the repository root, in Claude's assigned worktree
(`D:\Projects\Development\Java\Spring\wealthmgmtandportfoliotracker-claude`) or an equivalent clean
checkout.

**Freeze edits from step 1 through step 3.** Do not edit, create, or delete *any* file in the
checkout — tracked or untracked — between running `mark` and finishing `evidence` capture. The
source-identity check hashes the content of every changed/untracked path it finds, so even an
edit to a file this runbook itself is unrelated to (a scratch note, a half-finished doc) registers
as drift and invalidates the run. If you must edit something mid-run, stop, let the run finish (or
abandon it), and start over from `mark` afterward. **Stop immediately on any nonzero exit** from
steps 1–3 below — do not proceed to the next step, and do not re-run a later step hoping it masks
an earlier failure. **Preserve the run-start marker, the full Gradle console log, and the evidence
bundle together** (e.g. all three under `.candidate-artifacts/` or an equivalent archive) — the
marker and Gradle log are what make the evidence bundle auditable; the JSON alone does not prove
which actual Gradle invocation produced it.

### 1. Record the run-start marker

```powershell
python -B scripts/b1_candidate_evidence.py mark --out .candidate-artifacts/run-start.marker
```

This requires a **clean, fully-committed working tree** by default — candidate-ready evidence
means one immutable checkout (tasks.md 7.4). Commit or stash first. For local iteration only, pass
`--allow-dirty`; the resulting evidence is labelled `LOCAL_DEV` and its `candidate_ready` can never
be `true` regardless of outcome.

The marker records the HEAD SHA, a content-addressed hash of every changed/untracked path (not just
`git status` text — see `scripts/b1_candidate_evidence.py`'s module docstring), and the current
epoch. Every later check in this run is relative to this marker.

### 2. Run the unfiltered candidate graph

```powershell
.\gradlew.bat --no-daemon `
  :portfolio-service:candidateVerification `
  :portfolio-service:candidateManifestValidation `
  :portfolio-service:prepareCandidateArtifact `
  --rerun-tasks --no-build-cache
```

- `candidateVerification` runs `test` and `integrationTest` unfiltered, then `bootJar`, in that
  order (`portfolio-service/build.gradle`).
- `candidateManifestValidation` parses the fresh JUnit XML, validates the floor and discovery
  reconciliation, and checks report/bootJar freshness and source-identity drift against the marker
  from step 1 — **before** anything is staged. On success it prints `manifest validation: PASS`. On
  any problem it prints `ERROR: <message>` to stderr, exits nonzero, and Gradle reports the task
  **FAILED** — it never prints `manifest validation: FAIL`; a failure is always the `ERROR:` form.
  `<message>` takes one of two shapes: for aggregated floor/discovery/freshness problems it is
  `<n> manifest-validation problem(s):` followed by every reason, one per line; for a structural
  failure (e.g. a missing or unreadable run-start marker, malformed policy JSON) it is a single
  free-form description instead. Stop here on failure; do not proceed to step 3. Re-run with
  `--info` for full Gradle output if the cause is unclear.
- `prepareCandidateArtifact` only runs if `candidateManifestValidation` passed; it copies the
  verified `bootJar` archive to `.candidate-artifacts/portfolio-service.jar` (git-ignored, kept out
  of `.dockerignore`).

Always pass `--rerun-tasks --no-build-cache` for candidate runs. Never pass `--tests` or `-x` — the
graph must run unfiltered (tasks.md 7.5: "no `--tests` selector participates in any invocation").

If the whole invocation exits nonzero for any reason, **stop** — do not proceed to step 3, and do
not re-invoke Gradle hoping a second attempt clears the failure without understanding it first.

### 3. Capture the full evidence bundle

```powershell
python -B scripts/b1_candidate_evidence.py evidence --out .candidate-artifacts/evidence.json
```

Repeats every pre-staging check from step 2 and additionally binds the staged copy's SHA-256 to the
`bootJar` archive's.

**On success** (exit 0), the command prints `graph verification: PASS ...` and, with `--out`, writes
the full evidence bundle:

- `graph_verification_status`: `"PASS"` — this script's own scope only (see below).
- `candidate_ready`: always `false` today; `candidate_ready_blocked_by` names exactly what is
  outstanding (Task B packaging/registry evidence, Task C's source-governance findings awaiting
  reviewed disposition, Task C's unimplemented HTTP smoke evidence, and any unresolved policy
  finding such as R3 — see `scripts/b1-candidate-policy.json`).
- `problems`: an empty list.
- `manifest` / `per_task_totals`: the complete generated manifest and per-task test counts.
- `stage`: the bootJar/staged paths and their shared SHA-256.

**On failure** (nonzero exit), the command prints `ERROR: <message>` to stderr — **stop here**, do
not proceed further. As with step 2, `<message>` is `<n> candidate-verification problem(s):`
followed by every reason for aggregated validation failures, or a single free-form description for
a structural failure (missing/unreadable marker, malformed policy JSON). With `--out`, it writes a
*minimal* bundle either way, not the shape above:

```json
{"graph_verification_status": "FAIL", "candidate_ready": false, "error": "<all problems, joined>"}
```

The failed bundle has a single `error` string (all problems joined into one message), not the
`problems` list array the successful bundle carries — do not write tooling that expects `problems`
to be present on a failed run.

### 4. Run the GC.5 source-governance guard + writer-inventory re-check

```powershell
python -B scripts/check_b1_candidate_source.py --repo . --mode LOCAL_PREPARATION --out gc5-evidence.json
```

Exits non-zero while any finding is open — that is the designed state, not a failure of the run.
Contract `gc5-contract/3`. Findings are keyed `(path, subject_id, obligation)` and fall into
independent kinds — `CONFIRMED_MATCH` (matched a forbidden path/symbol — a detected match, **not**
an adjudicated violation), `UNREVIEWED` (understood, no reviewed disposition), `UNSUPPORTED` (the
analyzer cannot model the construct; a human acceptance can never upgrade this), `UNRESOLVED` (a
write whose relevance the analyzer could not decide — blocks like a relevant one), `ENVELOPE_CHANGED`
/ `DISPOSITION_INVALID` / `POLICY_INVALID` / `EVIDENCE_BINDING_MISMATCH`, and `MISSING_SUBJECT`. All
block; none is a proven violation.

**This tool evidences SOURCE GOVERNANCE only; it never asserts release readiness.** `source_governance_status`
has exactly two values, `PASS` and `BLOCKED`, and drives the exit code. `candidate_ready` is **always
`false`** here — source governance is necessary but not sufficient for a candidate: this tool cannot
establish the exact-digest HTTP smoke or Task B's registry portion, and `candidate_ready_blocked_by`
names every outstanding piece. `PASS_EXCEPT_UNVERIFIED` is a diagnostic *substatus* only: it still
exits non-zero. Human review can never waive unsupported coverage into readiness; accepting residue
is an owner decision at 7.8 on the evidence this run prints, not a state the tool can enter. Never
infer readiness from an empty findings list.

**One validation path.** Every claim-bearing record — a writer disposition, a managed-entity setter
approval, a SQL clearance, an effect resolution, a governed path exception, and an envelope record —
passes the same gate: a real reviewer and status, a `reviewed_commit` that **exists in git and is an
ancestor** of the cut, a reviewed snapshot that **recomputes from git** to the recorded digests, and
the exact subject still matching at the cut. There is no lighter route: a status-only setter, a
"trust me" effect override, or a provenance-skipping SQL clearance is rejected. An UNSUPPORTED
coverage item stays blocking regardless of any assertion.

**Deployables are enumerated from the tree, not the policy** (fail-closed): a module with any
RELEVANT or UNRESOLVED writer is governed and must carry an envelope record even if the policy omits
it — deleting the `deployables` list cannot hide an obligation. Effects are **operation-specific**:
an `ON DELETE CASCADE` indirect target attaches only to a DELETE/TRUNCATE of the parent, never to an
INSERT. **Automatic effect clearance is inactive** unless its (separately approved) policy list is
non-empty; while inactive, an UNRELATED write inside a governed deployable still needs an explicit,
validated disposition — its table-scope classification is review evidence, not a waiver.

**A claim is validated against the code it reviewed.** Every disposition (writer, setter, SQL,
effect resolution) and every path/content exception carries a Tier-0 fingerprint (setters included —
never skipped), and the tool reconstructs the subject at the claim's own `reviewed_commit`: a claim
whose reviewed_commit predates the subject, or whose subject differs there, is rejected — a later
envelope cannot retroactively bless nonexistent code. The historical subject index is built by the
**same extractors** as the cut analysis (`subject_index`) and covers Java operations, entity setters
**and resolvable SQL subjects**, so a valid static-migration disposition reconstructs at its reviewed
commit exactly as a Java one does; dynamic SQL stays on the operational-record route only.

**Exceptions prove their transition inside the guard interval.** A `path-governance` or
`content-governance` exception requires an explicit `ACCEPTED` status, a reviewer, the change kind
and **both** blob ids, and a `reviewed_commit` that exists, is an ancestor of the cut, and lies
**strictly after the B1-base**. The pre-image at the base must equal `src_blob` and the tree state at
`reviewed_commit` must equal `dst_blob` (absent, for a deletion) — a commit predating a file's
addition also shows the path absent but reviewed nothing, and is rejected. A content exception
additionally scopes `symbols` and `non_goals`; it clears exactly those symbol hits on exactly that
blob and nothing else, and lapses when the file is edited again. A `REJECTED`/pending exception never
clears. **No exception, attestation or disposition for the real repository has been authored by
this tooling** — that remains an owner review act.

**Envelope renewal is a lifecycle**: a revision >1 names its `previous_envelope_record_id`, and its
`reviewed_delta` is validated against the *predecessor→this* review (R_old→R_new), while R_new→cut
must leave the envelope unchanged. `affected_claims` is an explicit list of `{path, subject_id}`
objects: every entry must name a subject that exists at one of the two reviews inside the envelope,
and every subject whose Tier-0 fingerprint changed between the two reviews **must** be listed (an
envelope-only change with an explicitly empty list stays valid). **Every declared record is
validated**, not only the latest — a claim may reference any record by id, so an unvalidated older
record was a hole; validation is memoised, rejects predecessor cycles, dangling links and two records
claiming one revision, and a claim bound to an invalid record is `DISPOSITION_INVALID`. The
attestation must be an **exact partition** of the reviewed membership (three lists, no duplicates,
pairwise disjoint, union equal to the members, no foreign paths). The content-addressed
`envelope_record_id` covers **all** normative fields — envelope id, reviewed commit, reviewer,
`reviewed_at`, revision, all four digests (`membership_digest` included), attestation, previous link,
delta, affected claims — so moving a member unsupported→analyzed changes the id and re-opens every
disposition on it. `roots` and `membership` are non-normative (derivable from the reviewed commit and
pinned by their digests).

**Run-evidence semantics.** Supplied Task A/B evidence is validated against the **accepted producers'
own schema** (`task_a_schema_problems` / `task_b_schema_problems`): digests go through the producers'
shared `normalize_sha256_digest` (bare hex from `sha256_file`, `sha256:`-prefixed from Docker — one
identity), and every semantic field is **required with its type** — Task A `PASS`, an explicit empty
`problems` list, exact cut, the policy-pinned B1-base, an eligible mode, a well-formed stage hash and
staged path; Task B `provenance: verified`, an image id, explicit `platform` **and**
`requested_platform` (equal), stage/extraction equality, the staged path, and the cross-bind to Task A
and the cut; a release portion additionally needs a matching registry manifest digest and platform. A
missing field is never "None == None". The **run mode is threaded in**: a CANDIDATE run accepts only a
Task A bundle whose `run.mode` is `CANDIDATE` (a clean, fully committed checkout); `LOCAL_DEV` input is
eligible for LOCAL_PREPARATION only. Task B's `label` stays `LOCAL_PREPARATION` by producer contract
until the registry portion exists (see the release procedure), so it is not what distinguishes
candidate input. What does: in CANDIDATE, `runtime_base_digest` must be an **immutable
`repository@sha256:<64 hex>` reference** (a floating tag, `scratch` or arbitrary text is refused), and
the run input must name the producer's **build record** (`task_b.build_record`, the
`image-build-record.json` that `verify_b1_candidate_image.py` writes). The record is validated whenever
supplied and required in CANDIDATE: its `image_id` must be Task B's `local_image_id`, its `base_digest`
Task B's `runtime_base_digest`, its `platform` the requested platform, the recorded Dockerfile must
exist and hash to `dockerfile_sha256`, and Task B's `recipe` must name that same file. Strings in the
Task B JSON are unrelated text until they agree with that record and with the recipe bytes on disk.

Schema is not enough on its own: the tool then **re-verifies the artifacts**. It re-hashes the staged
JAR at the producer-recorded path and compares it with both bundles, inspects the immutable image id
with Docker, checks the image platform, and **re-extracts `/app.jar`** from that image and hashes it —
so a plausible digest string naming no file and no image blocks (`evidence-binding`
`EVIDENCE_BINDING_MISMATCH`), as does a JAR replaced after capture or an image whose `/app.jar` is not
the staged JAR. A Docker failure is a binding failure, not a skip; `evidence.artifacts_verified` in the
output says whether this step passed. The tool records the actual SHA-256 of each evidence file and of
the run-input manifest, even when the caller omitted an expected hash. A clean, committed CANDIDATE run
is exercisable in a disposable repository — the guard's own suite drives the real CLI that way with
**producer-generated** Task A/B evidence (real JAR, real `FROM scratch` image) kept **outside** the
repository, on a `core.autocrlf=true` checkout re-checked-out as CRLF, with negatives for a later
clean HEAD, edited analyzer bytes, edited policy bytes and unverified provenance.

**Per-usage coverage, SQL-aware.** Each call on a persistence-capable receiver must be a recognized
write, a recognized statement-free read-only method (finders, transaction plumbing), or a
**SQL-bearing call classified by its resolved statement** — otherwise it is `UNSUPPORTED`. A resolved
`jdbcTemplate.update` never suppresses an unrecognized `jdbcTemplate.call` on the same bean, and a
recognized write never hides a `queryForObject("DELETE … RETURNING …")` on the same receiver: PostgreSQL
returns a result set for a data-modifying statement with `RETURNING`, so `queryForObject`,
`queryForList`, `query`, `sql`, `createQuery`, `createNativeQuery`, `prepareStatement`, `prepareCall`
and `executeQuery` resolve their SQL through the same S0/S1/S2 shapes as a write. A resolved read-only
statement (leads with SELECT/WITH/VALUES/EXPLAIN/SHOW, no DML/DDL, no CALL/DO/PERFORM/INTO/LOCK/sequence
construct, and — after a **lexical pass over the ORIGINAL resolved SQL** (`lex_sql`: line comments end
at their original line boundary, block comments nest, identifiers are consumed atomically, dollar
quotes are recognized only at token boundaries, and any span outside the supported subset such as a
non-ASCII identifier character or an `E'…'` literal is an explicit unsupported result, never an empty
call list) — every invoked routine identified by its **complete name**, qualifiers and quoting
included, inside the explicit supported subset: an
unqualified bare built-in from the allowlist, or the same built-in under `pg_catalog`. A routine the
migrations define, one nothing in source defines, a quoted-identifier routine (`"external_mutator"()`,
even `"lower"()`), or a routine in any other schema (`custom_schema.lower()` is not the built-in
`lower`) has effects the analyzer cannot establish and is **not** read-only; unsupported literal syntax
such as `E'...'` blocks rather than hiding calls) accounts for its receiver and is not a writer — but
**only once the receiver
itself has resolved to a relational store**. The read-only decision is one shared predicate taken
after receiver resolution; a `SELECT 1` on an unknown or undeclared receiver does not inherit JDBC
read semantics from the method name and blocks as `UNRESOLVED`. Anything else resolved is a **writer**
in the inventory; an unresolved statement is `UNSUPPORTED`. The assessment is **structured and
survives to the final decision**: a statement outside the lexical subset is `UNSUPPORTED` coverage
(a blocking `writer-coverage` finding that no disposition or effect resolution can reach), and a
statement invoking a routine whose effects cannot be established stays `UNRESOLVED` even when its
direct target table is known — `DELETE FROM market_prices WHERE external_mutator() RETURNING id` is
not a disjoint write. Verbs and target tables come from executable tokens only, so DML text inside a
comment or a data literal never invents a target (and never weakens or strengthens a verdict), while
procedural bodies are lexed as the code they are: a `DO` statement's body literal (dollar-quoted or
single-quoted, before or after `LANGUAGE`) and a `CREATE FUNCTION/PROCEDURE … AS <literal>` body. A
body in a language other than `plpgsql`/`sql`, or a `DO` with no body literal, is an UNSUPPORTED
subject, never inert data. Migration enumeration consumes the same structured assessment: a statement
yields a subject when it has DML/DDL verbs, defines a persistent object, fails lexically
(`sql:UNSUPPORTED`), invokes a routine with unknown effects (`sql:CALL`), or is a `DO` block at all
(`sql:DO`) — executable code and unresolved effects cannot disappear from the inventory. JPQL targets are entity names mapped through the entity index — an unmapped
entity is `UNRESOLVED`. `prepareStatement("<DML>")` is treated as the write site; a chained
`ps.execute()` carrying no statement of its own stays `UNSUPPORTED` rather than being "followed".

**Executable-block selection is comment-invariant.** Whether a statement is a `DO` block, and whether
it performs dynamic execution, are **token-derived facts on the shared assessment**, never regexes over
normalized source. A leading line comment (LF, CRLF or CR) or a block/nested comment therefore cannot
hide an executable block, and `EXECUTE`-shaped text inside a data literal or comment cannot escalate an
ordinary statement's severity. Dynamic execution in a procedural context — an `EXECUTE` inside a `DO`
block or a function/procedure body, whatever form its command takes (quoted string, dollar string, or a
variable), and a top-level `EXECUTE <prepared-statement>` — is conservatively `UNSUPPORTED` coverage
before both migration subject selection and Java writer classification. This analyzer never evaluates
dynamic SQL; it only refuses to call it modeled. Verb-less unmodeled execution is labelled
`sql:UNSUPPORTED`, while a verb-bearing dynamic function keeps its verb label so existing subject ids
do not move.

**Two further resolution shapes join S0/S1/S2 (GC.5 coverage closure).** S3: a no-argument
`ps.execute()`/`.executeQuery()`/`.executeUpdate()` is assessed against the SQL its own receiver was
prepared with, when that receiver is declared exactly once in the SAME method from
`= <receiver>.prepareStatement(SQL)`/`.prepareCall(SQL)` and that SQL itself resolves — a bare
reassignment anywhere, an unresolved preparation, or none found at all leaves it `UNSUPPORTED`
exactly as before ("finite-literal helper-call propagation" does the same for a private helper's own
`String` parameter). S4: when a SQL-bearing call's argument is exactly the enclosing method's own
`String` parameter, or exactly one literal prefix concatenated with it (`"SHOW " + setting`), every
call to that method anywhere in the unit must supply a resolvable literal for the parameter, and
every resulting candidate must independently be read-only; any unresolved caller, zero callers, or
one non-read-only candidate leaves it `UNSUPPORTED`. Neither shape follows a call across files or
resolves an unbounded/recursive flow.

**A cascade/orphanRemoval-mapped collection's owner controls its effect and fingerprint, never the
variable's name.** `mapped_collection_names` proves a field or its accessor carries an actual
`@OneToMany`/`@ManyToMany`/`@ElementCollection` annotation and records the exact class that carries
it; only THAT proven owner's `@Entity`/`@Table` mapping may force the receiver's store to `postgres`
and bind the mapping digest. An annotated field whose owner cannot be found as a real indexed
`@Entity` stays `UNRESOLVED`, never guessed into `postgres` merely because a receiver happens to be
named like a known mapped collection.

**Persistence-usage marker/call accounting is token- and type-exact, not raw substring.** A marker
type or `@Annotation` is real only when an actual code token equals it — `Session` no longer matches
inside `JwtSessionIdentity`, nor `JdbcTemplate` inside `NamedParameterJdbcTemplate` or
`DataSourceAutoConfiguration`. A `*Repository<...>` interface with no `@Query`/`@Modifying`/
`@Procedure` method and no `delete`/`remove`-prefixed derived method is a recognized, declaration-only
Spring Data shape; a transaction/session callback wrapper (`x.execute(status -> ...)`) and a small
named set of metadata/wrapper/lifecycle calls (`getAutoCommit`, `isWrapperFor`,
`isJoinedToTransaction`, `close`, the JDBC `PreparedStatement.setLong` parameter binder) account for
their receiver without becoming a generic method-name allowlist — an unknown `execute`, callback or
reflectively dispatched call, including one forwarded through the diagnostic JDBC proxies, is still
unaccounted. A `Class<T>.class` literal (bare, or as an array/varargs element) is recognized as a
literal, never misread as declaring a variable named `class`; a `try (A a = ...; B b = ...)` header's
first resource keeps its declared type instead of losing it to the header's own unbalanced `(`. Mongo
`BulkOperations`, constructed via `mongoTemplate.bulkOps(...)`, is a recognized non-SQL-bearing write
receiver (`STORE_MONGO`) rather than a "SQL call missing a string".

**A script's `#` comment or its module/class/function docstring** — two proven-non-executable Python
shapes, using only `tokenize`/`ast` — is not reported as script DML; the identical clause text used as
a real sink argument, a variable, or any non-Python script is untouched and still blocks. A third
shape (a string that is the whole or joined argument of a `raise Name(...)`) was exempted here in an
earlier revision, gated on proving `Name`'s constructor inert; three successive review rounds each
found a way to satisfy that proof while `Name` still executed the argument at raise time (a
lambda-assigned `__init__`, class-name rebinding, a class decorator, parameter shadowing, base-name
rebinding, a post-definition `__init__` attribute write). Soundly ruling out every way a Python name
can be rebound is an open-ended problem, so that exemption was removed rather than patched again: a
`raise Name(...)` argument is never exempted, regardless of what `Name` is or appears to be.
`check_b2_demo_identity.py`'s GuardError diagnostic (GC5-0486) is consequently a documented, retained
`writer-inventory` `UNSUPPORTED` coverage residual, not a clearance.

**Task A / Task B evidence is a per-run input, never committed in the policy** (committing a
post-build hash would force a build→commit→rebuild cycle). Supply it with `--run-input <manifest>`
(`{"task_a": {"evidence_file": …}, "task_b": {"evidence_file": …, "release_portion": false}}`; paths may
be absolute so the bundles can live outside the checkout). Non-JSON evidence, a malformed run-input
manifest (a clean `ERROR:`, never a traceback), or an invented JAR/image identity blocks.

**Envelopes (Tier 1).** Each deployable has a source envelope — the sorted `(path, blob oid)` list
of every tracked path under its build-graph-derived roots. Roots are *derived* from the Gradle graph
(module `src/main`, its `build.gradle`, root `build.gradle`/`settings.gradle`, each `project(':…')`
dependency's sources and build file, and packaged resources such as `config/seed-tickers.json`); a
policy that declares roots omitting a derived one is `POLICY_INVALID`. A disposition references an
envelope revision and digest; any change inside the envelope re-opens every disposition on it with
`ENVELOPE_CHANGED`, listing the exact added/removed/modified paths. Envelope validation is global —
a missing or changed envelope record blocks even when there are zero dispositions. Renewal requires a
new record carrying the reviewed delta and affected claims; replacing a digest alone is refused.

**Tier 0** (the disposition fingerprint) is the normalized operation statement, the enclosing-method
digest, the resolved receiver persistence type, and the relevant entity mapping digest. Same-file
closure and caller lists are recorded only as `review_aids` and are **not** validity inputs —
transitive effects (a transaction boundary hops away, a helper body change) are caught by the
envelope instead. Envelope records are **content-addressed** (`envelope_record_id`): a disposition
references an exact revision by that id, so mutating a record's digest while keeping its old
`reviewed_commit` produces a different id and the rebind is rejected.

`--mode LOCAL_PREPARATION` permits an uncommitted analyzer/policy to evaluate a committed cut, and
records their hashes under `evaluator`, separately from `target`. Before candidate verification,
commit the tooling and policy, **check out the cut itself**, and re-run with `--mode CANDIDATE`, which
requires the checkout `HEAD` to **be** the cut (a later commit that happens to be clean is refused —
evidence must come from the frozen cut), verifies the executing analyzer and policy are identical to
the versions committed in the cut using git's **clean-filter-aware object id** (`git hash-object
--path <rel> --stdin`, so this repository's `core.autocrlf=true` CRLF checkout compares equal to its
LF blob while any real edit does not), and requires a clean working tree. Keep run inputs (evidence
bundles, `--out`) **outside** the checkout or under an ignored path; committing them would move HEAD
off the cut.

**CI is deliberately out of this bundle** (see the kickoff scope table). Recorded here for a later
CI proposal: the guard needs `actions/checkout` with `fetch-depth: 0`. The `static-guard` job in
`.github/workflows/ci-verification.yml` currently checks out shallow, and against a depth-1 clone
the B1-base commit is not present — the guard then fails closed with a blocking error rather than
substituting a reachable base.

### 5. Run the tooling's own unit tests

```powershell
python -B -m unittest discover -s scripts/tests -p test_b1_candidate_evidence.py -v
python -X utf8 -B -m unittest discover -s scripts/tests -p test_check_b1_candidate_source.py -v
python -B -m unittest discover -s scripts/tests -p test_verify_b1_candidate_image.py -v
python -X utf8 -B -m unittest discover -s scripts/tests -p test_smoke_b1_candidate_image.py -v
```

`-X utf8` is required for the guard suite (non-ASCII SQL identifier fixtures) and harmless elsewhere.

This validates `b1_candidate_evidence.py` itself against synthetic fixtures (missing/empty report
dirs, malformed XML, stale reports, a stale bootJar, an already-dirty file edited again, a
quoted/non-ASCII filename, a renamed test file, a mismatched `--base-sha` override, a dirty tree
without `--allow-dirty`, and more) faster and more exhaustively than any single real Gradle run can.
Run it before trusting a real run's result, not only after.

### 5. Build and verify the local candidate image (Task B, local portion)

Requires step 2's `prepareCandidateArtifact` to have already staged
`.candidate-artifacts/portfolio-service.jar`, and a running Docker daemon.

```powershell
python -B scripts/verify_b1_candidate_image.py --out .candidate-artifacts/image-evidence.json
```

Builds `portfolio-service/Dockerfile.candidate` (copy-only: no Gradle, no recompilation) as a local,
disposable, tagged image (`wealth-portfolio-service:candidate-local-dev` by default — never pushed
anywhere), extracts `/app.jar` from it, and asserts its SHA-256 equals the staged JAR's. On success,
prints `candidate image packaging: PASS (LOCAL_PREPARATION) ...` and, with `--out`, writes:

- `label: "LOCAL_PREPARATION"` — always; this tool never produces a release-candidate result.
- `local_image_id`, `platform` — from `docker image inspect`; **not** a registry manifest digest.
- `runtime_base_ref` / `runtime_base_digest` — the resolved digest of the Mariner base actually
  used, obtained by explicitly `docker pull`-ing the base reference before inspecting it.
  BuildKit does not register a base image as a separately inspectable local image on its own —
  empirically confirmed: right after a successful build, `docker image inspect` on the same base
  reference used in the `FROM` line reports "No such image" until it is pulled directly.
- `staged_jar_sha256` / `extracted_jar_sha256` / `hashes_equal` — the actual proof.
- `registry_manifest_digest` / `registry_manifest_platform` — always `null` here; only a
  separately owner-authorized push (see "Release procedure" below) can populate these.

On failure, prints `ERROR: <message>` and exits nonzero; with `--out`, writes a minimal
`{"label": "LOCAL_PREPARATION", "hashes_equal": false, "error": "<message>"}` bundle instead.

This step **pulls a Microsoft-published base image over the network** (cached after the first run)
but performs no ACR access, no push, and no registry authentication of any kind.

Clean up the disposable local image afterward if you do not need it (`docker rmi
wealth-portfolio-service:candidate-local-dev`) — it is local development evidence, not an artifact
to retain.

### 7. Run the HTTP contract smoke against the packaged image (Task C harness, local portion)

Requires step 6's local image and a running Docker daemon. Takes about half a minute.

```powershell
python -X utf8 -B scripts/smoke_b1_candidate_image.py --local-image sha256:<image-id> --expect-jar-sha256 <staged-jar-sha> --out .candidate-artifacts/smoke-evidence.json
```

The harness creates a **private network** and disposable PostgreSQL, Redis and Kafka containers, then
runs the image under test **by immutable id, with its own shipped entrypoint** — no mounted JAR, no
test-class overlay, no entrypoint override — under `SPRING_PROFILES_ACTIVE=prod,azure` on the
production port 8080, as a synthetic user this harness owns. It asserts, recording each request and
response:

| id | assertion |
|---|---|
| A1 | the container runs the requested image identity and platform via the image's own entrypoint, and serves its own contract inside the deadline |
| A2 | `GET /api/assets` → 200 with a non-empty catalog and an ETag |
| A3 | `PUT /api/portfolio/holdings` with a nontrivial multi-holding body succeeds and the version advances |
| A4 | replaying **the same** `expectedVersion` → 409 with the exact `portfolio_version_conflict` envelope and the observed `currentVersion` |
| A4-db | the **complete** persisted parent and holding rows are unchanged after the rejected write, **and** are the rows A3 reported writing |

A4 never refreshes `expectedVersion` and never retries — the stale value is the test. Quantities are
compared by decimal value in both A3 and A4-db (`1.5` and `1.5000` are the same quantity; `1.5` and
`999` are not), so a response or a write that used the wrong numbers fails rather than being replayed
as "unchanged". A4-db snapshots whole rows (`row_to_json`, ordered by holding id), so a change to a
holding's identity, its cost-basis columns or the parent's timestamps is caught, and it requires the
before-state to be substantive so two empty reads cannot pass as "unchanged".

**`--local-image` is development feedback, labelled `LOCAL_PREPARATION`.** A mutable tag is refused in
both modes. `--registry-digest <repository>@sha256:<64 hex>` is the Task 7.5a evidence run, and it
carries a **mandatory identity contract checked before any Docker or registry access**: the digest must
be in the approved ACR repository (`wealthprodacr.azurecr.io/portfolio-service` — a foreign repository
is refused however immutable its digest), `--expect-jar-sha256` and `--expect-platform` must be given,
and `--keep` is refused. The run resolves the manifest first: an **image index** must contain a
manifest for the expected platform, and that child digest is recorded as
`registry_platform_manifest_digest` — an index digest alone does not identify what a single-platform
target pulls. The pull is `--platform`-pinned and the resolved platform is compared afterwards, and
`/app.jar` is re-extracted and re-hashed against the expected artifact. It **refuses to run without
`--authorized-release-run`**, which asserts owner authorization for that specific digest. That run has
not been performed. `candidate_ready` is always `false` in this harness either way.

A local run may omit `--expect-jar-sha256`; the evidence then lists that unverified join explicitly
under `unverified_joins`. `--keep` is debugging only: it records `cleanup_verified: false` and names
the retained resources, so a retained environment can never read as a verified one.
`cleanup_verified` is derived after every container, network and workdir removal has finished, so a
cleanup failure clears it as well as failing the run.

**Decimal values are compared losslessly.** Both JSON decoding boundaries — the HTTP response and
PostgreSQL's `row_to_json`, which emits NUMERIC columns as bare JSON numbers — decode through
`json_loads`, which parses fractional numbers to `Decimal` rather than to a binary float. A change to
`avg_cost_basis NUMERIC(19,4)` in its last digit (`999999999999999.0000` → `…0001`) survives to the
comparison instead of rounding away, and a response quantity of `1.5000000000000001` fails against a
submitted `1.5` while `1.5000` still passes. Written evidence keeps those exact digits (a `Decimal`
serializes as its full decimal text), and each exchange additionally retains the verbatim wire form in
`body_text`. Non-finite JSON values are refused rather than compared.

Every way this environment differs from production is listed in the evidence under
`environment_differences` (disposable dependencies, Kafka over PLAINTEXT rather than SASL_SSL,
synthetic placeholders that are not credentials, no gateway in front, synthetic identity). Only
environment variable **names** are recorded, never their values. Cleanup removes only the containers
and network this run created, matched by its own run id; a cleanup error **fails** the run rather than
being absorbed, because leaked resources leave the next run's environment unknown.

## What this procedure does NOT prove

- **Not a release verdict.** `graph_verification_status: PASS` and a `LOCAL_PREPARATION`
  image-packaging `PASS` together mean: the graph/floor/discovery checks passed, and a local build of
  `Dockerfile.candidate` genuinely packages the staged JAR unmodified. Neither says anything about a
  pushed registry artifact (no push has happened) or an exact-digest HTTP smoke (not implemented).
  Nor do they say anything about source governance: the GC.5 guard runs as its own step and is
  currently BLOCKED. **The existence of the Task C guard files clears nothing** — not its open GC.5
  findings, not the missing smoke proof, and not R3.
- **R3 (`repair_migrate_holdings`) is unresolved by design.** See
  `scripts/b1-candidate-policy.json`'s `unresolved` array. A source-only review cannot establish live
  database privileges or prove the function unreachable; that is a separate owner-authorized
  live/operational decision, not something this runbook can close.
- **No packaged-image *smoke*.** Step 5 proves the image contains the right bytes; it never starts
  the application, opens a port, or exercises an endpoint. The exact-digest HTTP smoke harness
  (startup, `GET /api/assets`, a composition, the `409` envelope) is Task C scope.
- **No registry digest.** `registry_manifest_digest`/`registry_manifest_platform` are always `null`
  from this runbook. Only a separately owner-authorized ACR push can populate them (see "Release
  procedure" below).

## Floor reference (Task 7.5, corrected per R2)

The floor table in `tasks.md:1161-1172` names **10 conceptual suites**; two of its literal report
patterns (`*AssetDiscoveryContractTest`, `*PortfolioVersionReadTest`) do not exist anywhere in
source. R2's accepted correction replaces them with concrete carriers — and splits the "Version
read" row into **two** required class entries, because no single existing class covers both the
controller-level and service-mapping evidence the row's citation depends on. The result is **11
required class-pattern entries covering the same 10 conceptual suites** — see
`scripts/b1-candidate-policy.json`'s `candidate_floor.entries` for the authoritative, reviewed
mapping (do not hand-copy the table below; it is a summary for orientation only).

| # | Conceptual suite (tasks.md row) | Gradle task | Required report-class pattern |
|---|---|---|---|
| 1 | Legacy route contract | `test` | `*LegacyWriterRetirementTest` |
| 2 | Asset discovery contract | `test` | `*AssetCatalogControllerTest` (R2) |
| 3 | Composition controller HTTP contract | `test` | `*CompositionControllerTest` |
| 4 | Composition service + four-case matrix | `test` | `*HoldingReplacementServiceTest` |
| 5 | Error envelope and precedence | `test` | `*ErrorContractTest` |
| 6a | Version read (controller) | `test` | `*PortfolioControllerTest` (R2) |
| 6b | Version read (service mapping) | `test` | `*PortfolioServiceVersionMappingTest` (R2) |
| 7 | Concurrency | `integrationTest` | `*ConcurrentCompositionIT` |
| 8 | Decimal fidelity and no-op equality | `integrationTest` | `*DecimalFidelityIT` |
| 9 | Seed delegation, identity, price regression | `integrationTest` | `*PortfolioSeedServiceIT` |
| 10 | Migration and repository | `integrationTest` | `*V20MigrationIT` |

## Known-good local result (Task A, 2026-09-03)

Recorded for orientation only — re-run the procedure above rather than trusting this snapshot for
any decision.

- Source: `ebb96f3a6a22046ff5f3d449efcb146990b57ec9` (branch `claude/b1-r-c-candidate-preparation`),
  worktree dirty with Task A's own uncommitted tooling → `mode: LOCAL_DEV`.
- 89 manifest classes (53 `test`, 36 `integrationTest`); 552 + 208 = 760 tests; 0 skipped, 0
  failures, 0 errors.
- 69 B1-added/modified test files (against pinned B1-base `95fcb68dc7a47f99465354ec6d7b84137851389d`)
  reconciled with zero discovery gaps.
- bootJar/staged SHA-256: `4ee27c78c55da5c9edb34bbf926c3595249d82a42b9b95c369f125605c724dc6`.
- `graph_verification_status: PASS`, `problems: []`, `candidate_ready: false`.
- 39/39 unit tests pass (`scripts/tests/test_b1_candidate_evidence.py`).
- Image packaging: `label: LOCAL_PREPARATION`, `hashes_equal: true`, `runtime_base_digest:
  mcr.microsoft.com/openjdk/jdk@sha256:e59e5d626eb216745bb1bb69a84adba78d7724a55e0132995dccb3483b10fac7`,
  `platform: linux/amd64`. 13/13 unit tests pass
  (`scripts/tests/test_verify_b1_candidate_image.py`), including two real Docker end-to-end checks.
- Evidence binding (Task C, 2026-09-03, later the same day): the historical local image
  `sha256:983cf5…` had been removed from the daemon, so a fresh local image was produced with the same
  producer (`verify_candidate_image`, tag `wealth-portfolio-service:candidate-local-dev-rebuilt-20260903`,
  id `sha256:0337502e…`, same base digest, same staged JAR `4ee27c78…`). Feeding the preserved Task A
  bundle plus that fresh Task B bundle to the guard (`--mode LOCAL_PREPARATION --run-input …`) gave
  `task_a bound, task_b bound, artifacts verified` with zero `evidence-binding` findings; the run
  stayed `BLOCKED` on source governance, as designed. Historical bundles were not touched.

## Release procedure — owner authorization required *before* the release-candidate build

**Owner authorization is required before step 4 (the release-candidate build itself), not only
before the push in step 6.** This is the load-bearing distinction from the main local-verification
procedure above: step 5 there builds a `LOCAL_PREPARATION` development image, which the kickoff
explicitly permits without additional authorization ("clearly-labelled development image builds
are allowed; they are not Task 7.3 release candidates"). The moment a build's output is intended to
*become* cut-C's release candidate — the one artifact Task 7.3/AM.1's whole attestation chain binds
to — the "build it once" constraint applies, and authorization must precede that specific build, not
just the later push. None of the steps below may be executed by an agent on its own judgment; each
requires its own explicit owner authorization per
`docs/agent-instructions/CLAUDE_KICKOFF_B1_R_C_CANDIDATE_PREPARATION.md` and `AGENTS.md`'s Owner
Approval Callouts, and steps 6–10 additionally require Task 7.8's pre-deploy STOP/GO where
applicable. This section documents the planned sequence so a future authorized run has one place to
follow — it is not itself an authorization, and nothing in this checkout can execute it today.

Task 7.3 is explicit: "Build the R-C portfolio image once; capture its immutable digest. Everything
below binds to this digest." There is exactly **one** release-candidate build, in step 4. Every step
after it — local verification, tagging, pushing, re-extraction — operates on that same already-built
image; none of them rebuild it.

1. **Freeze the source cut** (no additional authorization beyond normal local work — this is
   read-only). Confirm a clean checkout at the exact commit to release; propose it as `cut-C` to the
   owner.
2. **Re-run Task A's graph** (steps 1–4 above) against that exact commit and confirm
   `graph_verification_status: PASS`. (No additional authorization — the same local verification
   graph as always.)
3. **Resolve the runtime-base digest** to build against — `docker pull
   mcr.microsoft.com/openjdk/jdk:21-mariner && docker image inspect ... --format '{{index
   .RepoDigests 0}}'` — without yet building anything. (No additional authorization — resolving a
   digest performs no build.)
4. **STOP. Obtain explicit owner authorization for the release-candidate build itself**, naming the
   exact `cut-C` commit and the resolved base digest from step 3. Only after that authorization,
   run **the one release-candidate build**:
   `python -B scripts/verify_b1_candidate_image.py --runtime-base
   mcr.microsoft.com/openjdk/jdk@sha256:<resolved-digest> --tag <release-candidate-tag>` (never a
   floating tag). This is the same tool as local-prep step 5, run once, against the authorized
   inputs — its output stops being `LOCAL_PREPARATION`-only in spirit from this point, even though
   the field will keep reading `LOCAL_PREPARATION` until push/registry evidence exists.
5. **Verify local extraction equality** (already part of step 4's run) against the release build's
   image and the `cut-C` staged JAR, using its immutable image ID — never a mutable tag.
6. **Tag that exact same image** (`docker tag <image-id> <repository>:<tag>` — no rebuild) for the
   target ACR repository.
7. **Owner separately authorizes the push.** Even when step 4's authorization already named the
   target repository, obtain an explicit go-ahead for the push itself unless that authorization
   expressly covered both the build and the push in one grant. **Push** (`docker push`) the exact
   image tagged in step 6 — still no rebuild.
8. **Resolve the registry manifest digest**, not the local image ID:
   `docker buildx imagetools inspect <repository>:<pushed-tag>` (or `docker manifest inspect`).
   If the result is an image *index* (multiple platform manifests — observed as the default local
   build shape even for a single target platform, see the note in step 5 of the local-verification
   procedure above), record the **specific platform manifest digest** selected for the deployment
   target, not the index digest alone; a platform mismatch between what was verified and what gets
   pulled would silently break the chain.
9. **Extract and re-hash from the pushed image** (`docker pull
   <repository>@sha256:<manifest-digest>`, then the same extraction as step 5) to confirm the
   pushed artifact is still byte-identical to the staged JAR — a push must not be trusted to be
   lossless without checking. Still no rebuild anywhere in this chain.
10. **Store the attestation**: `staged_jar_sha256 → registry_manifest_digest → cut-C commit SHA`,
    alongside the Task A evidence bundle from the same run. This is the record Task 7.4 step 9 and
    AM.1 require.
11. **Task C's exact-digest HTTP smoke** (once implemented) runs next, pulling the same
    `repository@sha256:<manifest-digest>` — never a mutable tag, never a local dev image from the
    main local-verification procedure.

Do not perform step 4 onward by improvising from this list without the applicable authorization for
each numbered step — a single grant covers only what it explicitly named.
