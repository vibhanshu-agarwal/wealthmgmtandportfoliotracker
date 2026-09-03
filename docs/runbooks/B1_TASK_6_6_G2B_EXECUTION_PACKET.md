# B1 Task 6.6 — R-B3 deployment and G2b proof packet

**OWNER APPROVAL — recorded; credential prerequisite unresolved:** On 2026-09-03 the owner
replied “Please proceed” to the exact §8 bundle. That authorizes secure read-only preflight, one
cu4 digest deployment, one fixed-E2E seed, and the specified conditional pre-seed rollback.
The initial preflight stopped before deployment because all eight required process variables
were absent. Approval remains valid subject to the packet's drift checks; no repeat approval is
needed merely to resume. See §10. Publication and Task 6.7 closure remain separate owner decisions.

**Prepared:** 2026-09-03 by Codex, architecture/review owner.
**State:** Execution approved; preliminary cloud/repository checks passed; application/database
preflight and deployment await secure credentials. Seed attempts: 0; deployment dispatches: 0.
**Operator:** The owner-authorized release operator; no implementation-agent assignment or message
is implied by this document. Codex owns review and status reconciliation.
**Goal:** Bind the existing candidate to every serving portfolio revision, then demonstrate
identity preservation, the correct version outcome and unchanged global prices.
**Method:** Existing guarded digest deployment; authenticated HTTP for the fixed E2E account;
two independent, complete PostgreSQL read-only snapshots around one seed.
**Authority:** [B1 tasks 6.5–6.7](../../.kiro/specs/portfolio-composition-contract/tasks.md),
[B1 Requirement 8](../../.kiro/specs/portfolio-composition-contract/requirements.md),
[G2b / release design](../../.kiro/specs/portfolio-composition-contract/design.md),
[6.5 readiness and approved build](B1_TASK_6_5_PRE_DEPLOY_READINESS.md).

## 1. Exact release bindings

| Binding | Value |
|---|---|
| Candidate source | 6a171558a0f802eadd5d7ed5bf28545ca5c91905 |
| Candidate tree | 4df697ed7605104a304ad08651e21522e32d52db |
| ACR build | cu4, succeeded 2026-09-03; no rebuild proposed |
| Candidate image | wealthprodacr.azurecr.io/portfolio-service@sha256:2be727eaf4577699c783ae66073670d4984fe66c666af3e56422c934fdd0b023 |
| Local cut tag | b1-r-b3-cut-6a171558-20260903T032527Z; unpublished |
| Reviewed workflow main | 9c2ebc1233801253a3e54b6e930e28e1a00ebf3d; GitHub ref re-read during packet preparation |
| Deployment | .github/workflows/deploy.yml on main; deployment_mode=digest; services=portfolio-service |
| Subscription | ee625b3f-7cb1-4482-be3c-4363c5d76d23 |
| Resource group / registry | wealth-azure-prod-rg / wealthprodacr |
| Last observed serving revision | portfolio-service--0000093; 100% traffic; Single mode |
| Exact conditional rollback image | wealthprodacr.azurecr.io/portfolio-service@sha256:9a1d55335b83b97967e434d374c7f5f5ca79ea2adccad8f8e518b674e9a39f47 |
| HTTP base | https://api.vibhanshu-ai-portfolio.dev |
| Seed identity | 00000000-0000-0000-0000-000000000e2e |
| Unchanged demo fixture | 00000000-0000-0000-0000-0000000d3110 |

[Build evidence](../evidence/b1-task-6-5/candidate-build-20260903.json) binds the clean source,
single ACR run and manifest. Source tests are historical accepted evidence, not a test run against
this packaged image. The rollout includes B1's strict seed/shared replacement and B2 8.1's additive
updatedAt projection. It excludes the Wave 7 controller, frontend/gateway changes and new migrations.
A successful valid request does not independently test version omission: that part of G2b uses
the frozen source/controller tests bound to the exact serving artifact. Do not claim a live
missing-version or race test.

## 2. Offline reference and execution channel

### 2.1 E2E golden reference

Use [the frozen E2E reference](../evidence/b1-task-6-6/e2e-golden-reference-6a171558.json).
It is **offline expected data**, never a production observation. The existing
scripts/derive_demo_golden_state.py is fixed to the demo UUID; its cost-basis output is unsuitable
for this seed even though quantities agree.

The reference uses the LF-normalized catalog SHA-256
cfa5e6b7317e922c07452359b851e55ee0a2a5ae9014224665244f9c2264de8b:
160 total entries, 159 ACTIVE. These counts are derived from this catalog, not new constant floors.
The configured anchor must equal 2020-01-01T00:00:00.000Z. Check the runtime override by a narrowly
selected non-secret field, plus the frozen configuration; stop on another anchor or an unreviewed
configuration source. Do not regenerate expected data from whatever the live system returns.

Independent derivation: h(s) is Java String.hashCode with signed 32-bit overflow; floorMod is
non-negative for the positive divisors below. For each ACTIVE ticker t and fixed E2E UUID u:

- quantity = floorMod(h(t), 50) + 1, represented at scale 8;
- seedPrice = HALF_UP(basePrice × (1 + floorMod(h(t + ":" + u), 500) / 10000), scale 4);
- avgCostBasis = HALF_UP(seedPrice × (1 + (floorMod(h(t + ":" + u), 400) − 200) / 10000), scale 4);
- currency = catalog quoteCurrency; source = SEED; as-of = configured fixed anchor.

| Fixed E2E vector | Quantity | Average cost basis |
|---|---|---|
| AAPL | 37.00000000 | 200.7733 |
| BTC-USD | 15.00000000 | 68106.3200 |
| EURUSD=X | 32.00000000 | 1.1091 |

Compare every persisted tuple, including null-vs-value distinctions. Numerical decimal equality
and UTC timestamp normalization are appropriate for the reference comparison; do not use float
rounding. No-op byte comparisons below are stricter and preserve the complete captured rows.

### 2.2 Credentials and client

Use owner-supplied environment variables in the release operator's local process:

| Operation | Variables |
|---|---|
| E2E login | E2E_TEST_USER_EMAIL, E2E_TEST_USER_PASSWORD |
| Internal seed | INTERNAL_API_KEY |
| Existing production PostgreSQL connection | PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD |

Check presence only before authorization; do not print values, lengths, hashes or complete
environment blocks. The owner supplies these securely outside chat. This packet does not authorize
retrieval of secret values from Azure/GitHub, new database roles, grants or network rules.
No credential has been checked or consumed during packet preparation.

Chosen SQL client: the locally cached postgres:16-alpine image by **local image ID**
sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777.
Offline invocation with networking disabled confirmed psql 16.14. This image ID identifies the
local client; it is not the portfolio release digest. Use --pull never, --entrypoint psql,
--rm and no published ports. No PostgreSQL server starts.

For each capture, place the exact §4 SQL in a new private local directory as snapshot.sql.
The directory also receives the CSV outputs. After authorization, use:

~~~powershell
docker run --rm --pull never --entrypoint psql --workdir /capture --mount "type=bind,source=$captureDirectory,target=/capture" --env PGHOST --env PGPORT --env PGDATABASE --env PGUSER --env PGPASSWORD --env PGSSLMODE=verify-full --env PGSSLROOTCERT=/etc/ssl/certs/ca-certificates.crt --env PGCONNECT_TIMEOUT=10 --env PGAPPNAME=b1-g2b-proof --env "PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=45000 -c lock_timeout=5000" sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777 -X -w -q -v ON_ERROR_STOP=1 -f snapshot.sql
~~~

captureDirectory is the resolved absolute path of that new local directory, not a credential.
Require an empty output directory and exit 0; never overwrite or accept a partial prior capture.
The TLS certificate bundle must validate the supplied host. Do not downgrade TLS if it fails.
Keep raw captures private; commit only sanitized identities, counts, hashes and assertion results.

HTTP uses the same local operator process, credentials in memory, no request/response tracing
that records login bodies, JWTs or internal keys. Disable automatic retries and redirect following
for the seed request. Generate one traceparent and retain only its trace ID in published evidence.
If tooling cannot enforce the single-attempt rules and capture all fields, stop before deployment.

## 3. Execution preflight and deployment — after §8 approval

1. Verify assigned-worktree ownership for any local edits. Preserve the frozen build checkout,
   source SHA/tree and tag. Resolve main again. Compare the workflow, workflow scripts, caller
   inventory and relevant source against the reviewed commit in §1. Documentation-only drift
   permits a newly recorded expected_main_sha; any runtime/workflow/caller/config drift requires
   review. Never substitute moving-main source for the frozen candidate.
2. Re-read candidate and rollback ACR manifests, every active/traffic-addressable portfolio
   revision and replica, ingress/traffic/mode, both startup flags and peer app/job identities.
   Require the last observed portfolio digest/revision, both APP_DEMO_SEED_ON_STARTUP and
   APP_DEMO_TX_DIAGNOSTICS false, internal ingress and allowInsecure=false. Preserve actual
   minReplicas=null if returned; zero running replicas is a separate observation.
3. Reconfirm production Environment review and non-cancelling deployment concurrency. Require
   no overlapping deployment or seed execution. Read the refresh-job schedule and executions;
   choose a quiet interval away from the recorded 08:00 UTC daily refresh. Do not pause jobs or
   change schedules. If a price writer overlaps, the later price proof is inconclusive.
4. Verify the secure credential channel and SQL connectivity before rollout. Run one §4 capture
   as a rehearsal; require complete rows/schema, both fixture accounts each with exactly one
   portfolio, no errors and read-only transaction. This rehearsal is not the proof baseline.
5. Login using the E2E credentials; require HTTP 200, a token and the exact E2E userId. An
   authenticated GET /api/portfolio must return exactly one matching portfolio with numeric
   non-negative integral version <= 9007199254740991. Discard this version; it cannot become the
   later seed precondition. No internal endpoint is called here.
6. Use the seven existing deployment safeguard suites listed in readiness §7. The prior 90/90
   result applies only if all guarded files remain identical; rerun affected suites on drift.
   The source/caller path comparison and existing caller-inventory guard must pass.
7. Dispatch exactly once using these inputs, after recording the refreshed full main SHA:

~~~powershell
gh workflow run deploy.yml --repo vibhanshu-agarwal/wealthmgmtandportfoliotracker --ref main -f deployment_mode=digest -f "expected_main_sha=$approvedMainSha" -f services=portfolio-service -f prebuilt_digest=wealthprodacr.azurecr.io/portfolio-service@sha256:2be727eaf4577699c783ae66073670d4984fe66c666af3e56422c934fdd0b023
~~~

approvedMainSha is the reviewed full main SHA from step 1, never HEAD of the local documentation
branch. Capture the unique run URL, inputs, workflow head and dispatch time. A dispatch timeout
requires run discovery, not another dispatch. Let the owner approve the production Environment;
the operator must not self-approve or bypass it.

Require successful validation, routing, Azure deployment and assert-scoped-non-interference.
Build/push, frontend, seed, verify, AWS and unselected-service jobs must be skipped as applicable.
The scoped assertion is useful but insufficient by itself: independently capture every active
revision, every nonzero traffic destination and any revision-label route. Require one candidate
digest across the serving set, latest/latest-ready agreement and 100% main traffic in Single mode.
Stop on an old digest or unresolved tag. Record replica identities before and after the proof;
one successful request cannot establish homogeneous serving state.

Verify peer revisions/images/traffic, refresh-job image/schedule, ingress, scale and startup flags
against preflight. No direct containerapp update, secret/scale/traffic changes or rebuild is allowed.

### Read readiness after rollout

The B2 4.5 attempt showed that Azure health can precede authenticated read readiness.
Allow at most six authenticated read-readiness attempts, each with a 60-second timeout and
30 seconds between attempts. Only connectivity timeouts and HTTP 502/503/504 permit another
readiness attempt. HTTP 401/403, a wrong identity, malformed portfolio/version or schema mismatch
is an immediate stop. Login/JWT refresh is not a seed and must never trigger a helper/global setup.
Discard all readiness versions. No seed is sent until both read readiness and complete SQL capture
have succeeded. If readiness expires before the seed, use §7's conditional rollback rule.

## 4. Exact database capture

Run the identical SQL in separate fresh client invocations for rehearsal, BEFORE and AFTER.
The BEFORE transaction must end before the seed. AFTER must start after its response (or uncertain
transport outcome); keeping one repeatable-read snapshot across the write would hide changes.

~~~sql
\set ON_ERROR_STOP on
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL timezone = 'UTC';
SET LOCAL datestyle = 'ISO, YMD';
SET LOCAL extra_float_digits = 3;
SET LOCAL statement_timeout = '45s';
SET LOCAL lock_timeout = '5s';
SELECT current_setting('transaction_read_only') AS transaction_read_only;
\copy (SELECT table_name, ordinal_position, column_name, data_type, udt_name, numeric_precision, numeric_scale, datetime_precision, is_nullable FROM information_schema.columns WHERE table_schema = 'public' AND table_name IN ('portfolios','asset_holdings','market_prices','market_price_history') ORDER BY table_name COLLATE "C", ordinal_position) TO 'schema.csv' WITH (FORMAT CSV, HEADER true, FORCE_QUOTE *)
\copy (SELECT * FROM public.portfolios WHERE user_id = '00000000-0000-0000-0000-000000000e2e' ORDER BY id) TO 'e2e-portfolio.csv' WITH (FORMAT CSV, HEADER true, FORCE_QUOTE *)
\copy (SELECT h.* FROM public.asset_holdings h JOIN public.portfolios p ON p.id = h.portfolio_id WHERE p.user_id = '00000000-0000-0000-0000-000000000e2e' ORDER BY h.asset_ticker COLLATE "C", h.id) TO 'e2e-holdings.csv' WITH (FORMAT CSV, HEADER true, FORCE_QUOTE *)
\copy (SELECT * FROM public.portfolios WHERE user_id = '00000000-0000-0000-0000-0000000d3110' ORDER BY id) TO 'demo-portfolio.csv' WITH (FORMAT CSV, HEADER true, FORCE_QUOTE *)
\copy (SELECT h.* FROM public.asset_holdings h JOIN public.portfolios p ON p.id = h.portfolio_id WHERE p.user_id = '00000000-0000-0000-0000-0000000d3110' ORDER BY h.asset_ticker COLLATE "C", h.id) TO 'demo-holdings.csv' WITH (FORMAT CSV, HEADER true, FORCE_QUOTE *)
\copy (SELECT * FROM public.market_prices ORDER BY ticker COLLATE "C") TO 'market-prices.csv' WITH (FORMAT CSV, HEADER true, FORCE_QUOTE *)
\copy (SELECT * FROM public.market_price_history ORDER BY id) TO 'market-price-history.csv' WITH (FORMAT CSV, HEADER true, FORCE_QUOTE *)
ROLLBACK;
~~~

Require transaction_read_only=on and all seven non-truncated CSV files. Preserve file bytes,
SHA-256, header column order, row count and UTC start/end timestamps for each capture.
Schema must match the frozen V1/V2/V5/V11/V17/V20 migrations; additional/different columns require
review. Price snapshots include **every column and every row**, including any non-catalog or
sentinel rows already present. No ticker filter, row limit, table count-only shortcut, inserted
production sentinel, SQL DML/DDL or lock that prevents normal writers is permitted.

For market-prices.csv, market-price-history.csv and schema.csv, require byte-for-byte equality
BEFORE versus AFTER, supported by SHA-256 equality and row counts. Use a binary comparison if
either file differs. A refresh overlap or partial capture means INCONCLUSIVE, never a waived price
regression. Historical PortfolioSeedServiceIT proves repeated-seed/sentinel scenarios locally;
this live protocol performs one seed only and does not recreate those fixtures.

## 5. One controlled seed

1. After all readiness checks, perform one new authenticated GET /api/portfolio. Record the
   E2E aggregate id, userId, createdAt, updatedAt, version N and holdings. This is the initiating
   eligibility observation. Freeze N; it is immutable throughout this attempt.
2. Capture BEFORE using §4. Require exactly one E2E parent; its id, user, created_at, updated_at
   and version must agree with the HTTP observation after UTC normalization. Require exactly one
   demo parent too. Verify all E2E persisted tuples against §2's reference; classify the expected
   outcome as SAME_STATE or TRANSITION **before** sending. If the DB version has moved or the
   captures disagree, stop without sending; never replace N with the newer value.
3. Mark attempt-started in the local evidence before transmission, then send exactly one
   POST https://api.vibhanshu-ai-portfolio.dev/api/internal/portfolio/seed with body
   {"expectedVersion": N}. Headers: Content-Type application/json, traceparent and
   X-Internal-Api-Key from memory. Send no identity, portfolio id, JWT, query parameters or
   alternate target. Set a 120-second timeout and zero retries.
4. Capture status, sanitized response and trace ID. The successful seed response has userId,
   portfolioId and holdingsInserted; **it has no version or updatedAt field**. Require userId
   equals E2E, portfolioId equals the BEFORE id and holdingsInserted equals the ACTIVE reference
   count. Never infer resulting version from this response or interpret holdingsInserted as
   proof of DML.
5. Run AFTER in a fresh database transaction and one authenticated post-read. This read is
   verification only and cannot feed another write. Apply §6. On a timeout or any non-200, still
   collect read-only aftermath when possible, mark the outcome uncertain/terminal as appropriate,
   and send no further seed. No hidden retry through the shell helper, Playwright global setup,
   synthetic workflow or demo-reset endpoint is allowed.
6. Reconfirm serving set/digest and peer non-interference after capture. Release credentials/JWTs
   from the operator process and retain only private raw captures plus sanitized review evidence.

The scoped attempt does not create a missing E2E aggregate, change a fixture to force a mutation,
run a malformed/missing/stale-version probe or deliberately race another writer.

## 6. Outcome rules

| Observation | Required result and interpretation |
|---|---|
| SAME_STATE + HTTP 200 | Parent id/created_at preserved; version N and updated_at unchanged; complete E2E parent and child CSV bytes unchanged; tuples equal reference |
| TRANSITION + HTTP 200 | Same parent id/created_at; version exactly N+1; updated_at strictly greater; every desired tuple equals reference; child row UUIDs may change |
| Either successful case | One parent; exact ACTIVE ticker set, no extras/duplicates; quantity and all cost-basis fields match; global price/schema bytes and demo parent/holdings bytes unchanged; authenticated post-read agrees with DB |
| HTTP 409 | error=portfolio_version_conflict, message string, numeric currentVersion; terminal conflict, no retry; inspect aftermath without claiming external concurrent changes were caused by the seed |
| Timeout / transport ambiguity | At most one attempt sent; commit status unknown until evidence resolves it; no resend even if no response arrived |
| HTTP 200 with mismatch | ABORT_POST_COMMIT_MISMATCH; preserve evidence; image rollback does not undo data |
| Price/schema/peer drift or mixed serving images | INCONCLUSIVE or FAIL as evidence warrants; no G2b closure |

Only a fully observed successful row, complete price regression and homogeneous serving binding
can support Task 6.6 ACCEPT. A no-op is a valid success under Requirements 8.17/8.18; do not call it
live mutation or race evidence. Task 6.7 remains the owner's separate R-B3 decision.
Do not claim Writer_Convergence or Wave 7 activation from this isolated proof.

## 7. Stop and conditional rollback

Before any seed attempt, if the candidate is unhealthy, the guarded rollout fails, or
authenticated read readiness regresses/expires, first capture actual serving state. If the old
image still serves unchanged, stop; another deployment is unnecessary. If the candidate took over
and §8 explicitly authorized rollback, issue at most one guarded digest-mode rollback to §1's
exact 9a1d5533 image, recording a fresh reviewed workflow SHA and obtaining the production
Environment approval. Validate its healthy/read-ready serving state and peer non-interference;
then stop with ABORT_ROLLED_BACK. No direct Azure update or automatic retry of rollback.

This restores the version-tolerant seed and removes updatedAt; it preserves the existing B2
internal demo-reset. Before rollback, verify no intervening consumer release has taken a new
dependency. Do not use the older R-B2 0000081/d544649f image, which predates the currently served
B2 contract. No migration or data restoration is part of rollback.

Once the seed attempt has started, conditional rollback authority ends. A successful seed may
have committed, and a timeout does not prove it failed. Record the aftermath and request the
owner's explicit next decision; no second seed, compensation, direct data repair or automatic
image rollback. After future R-C activation, rolling below R-B3 is prohibited.

## 8. Approval and handoff

The reviewable execution request is:

> Approve read-only Azure/ACR, E2E application and PostgreSQL preflight through the secure local
> channel in §2; one guarded portfolio-service deployment of cu4's exact 2be727ea digest; one
> fixed-E2E seed using its frozen observed version with complete before/after evidence; and at
> most one guarded rollback to the exact 9a1d5533 digest only for a pre-seed rollout/readiness
> failure. All steps stop on drift or failed prerequisites, and the operation stops after the
> proof report. No publication or Task 6.7 closure is included.

Before dispatch, the operator must have reviewed this packet/reference, the secure variables and
client connectivity, the current workflow binding, rollback availability and the live scope.
If a prerequisite cannot be established, stop before deployment with its exact reason.
A document approval, source ACCEPT or production Environment click does not replace the owner's
execution-bundle approval. The approval remains applicable across documentation-only workflow
head changes after the explicit comparison; other drift returns for review.

Return one sanitized report containing: approved scope/time; packet/reference hashes; source/tree,
build/run/image and workflow SHA; pre/post revisions/replicas/traffic; deployment run and skipped
steps; credential presence only; catalog/anchor binding; snapshot timestamps, schema, per-file row
counts and SHA-256; E2E parent identity; frozen N and expected SAME_STATE/TRANSITION; actual status,
response and trace ID; resulting version/timestamps; complete tuple/price/demo comparisons;
concurrent-writer observations; seed-attempt count; rollback use or non-use; limitations and verdict.

The offline packet alone checks no ledger box. Codex reviews execution evidence before proposing
6.6 completion and the separate 6.7 decision. Keep raw operational captures outside Git. The
Cursor 7.1–7.2 review remains in its separate task; this packet neither assigns nor activates it.

## 9. Completed offline review

On 2026-09-03 Codex re-read GitHub main at the full §1 SHA. Relevant deployment/caller/source
paths have no delta from the frozen cut through this documentation branch. No Azure resource or
application/database credential was accessed during this packet-preparation step.

All 159 reference tuples were independently recalculated using a polynomial hash and exact
rational rounding, separate from the generating decimal calculation. Catalog digest, active set,
identity, quantity, currency, source and anchor matched. This validates expected data, not live data.

The exact §4 SQL ran twice in independent READ ONLY transactions against a disposable local
PostgreSQL 16 fixture with networking disabled. Both reported transaction_read_only=on, emitted
all seven CSV files, included the non-catalog price/history sentinel, and produced identical bytes.
The fixture used migration-derived columns and distinct E2E/demo cost bases. The temporary server
was removed. This validates query syntax and serialization; production schema, credentials, TLS
and connectivity remain live preflight predicates. No application code or operational helper was
implemented, and the full application test suites were not rerun.

## 10. Approved preflight — credential prerequisite

The owner replied “Please proceed” to the exact execution request after review packet commit
1bd9314d3e7a2903747b8a6838556ec6a4e6d4a2. This approval is recorded; it is not being requested again.
The [sanitized preliminary result](../evidence/b1-task-6-6/approved-preflight-20260903.json)
records checks captured around 2026-09-03 04:28:56 UTC and hashes the approved packet/reference.

GitHub main remained 9c2ebc1233801253a3e54b6e930e28e1a00ebf3d. Relevant source/workflow/caller
paths had no delta from the frozen cut; the caller guard still found exactly three callers, and
the local cut tag resolved to the frozen source. Both candidate and rollback manifests remained
readable with the expected digests, tags, platforms and sizes. Portfolio revision 0000093 remained
sole active, Healthy/Provisioned/ScaledToZero, at 100% internal traffic in Single mode. Both flags
were false, minReplicas remained null, and the named cost-basis-anchor override was absent. This
limited check does not attest every possible configuration source or replace application readiness.

Presence-only checks found all required variables absent from this operator process:
E2E_TEST_USER_EMAIL, E2E_TEST_USER_PASSWORD, INTERNAL_API_KEY, PGHOST, PGPORT, PGDATABASE,
PGUSER and PGPASSWORD. No credential value was retrieved; no login, application read, database
connection, deployment dispatch, seed attempt or rollback occurred. This is
PREFLIGHT_BLOCKED_CREDENTIALS, not completed preflight or a failed serving proof.

Resume after the owner securely supplies the specified process variables or explicitly identifies
an authorized local credential source to load into memory. Do not search for secrets or retrieve
them from Azure/GitHub. Recheck time-sensitive release state, peer/replica/job/concurrency and
Environment controls, then complete the original packet under the existing approval. Source or
operational drift still invokes the original stop/review rule. Tasks 6.6/6.7 remain unchecked.
