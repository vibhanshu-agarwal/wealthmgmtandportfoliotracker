# B1 R-C Task 7.7 — pre-deploy serving evidence

> ## OWNER APPROVAL RECORDED — FINAL TECHNICAL PREDICATES GREEN
>
> The owner authorized all remaining Task 7.7 work, including immutable-digest replacement revisions,
> transparent retained-range recovery for the missing `insight-group`, the bounded HTTP proofs,
> exactly one seed and final read-only checks. Technical predicates are green. Task 7.8, R-C candidate
> deployment, public exposure and Writer_Convergence were not performed.

**Status:** G2, latest-valid G3, G4, G0a, G2a, G2b and derived G6 **PASS**.
Astra independently ACCEPTed the final packet with no findings; Task 7.7 is complete. The map is in
[`B1_R_C_TASK_7_7_G4_AND_WRITER_MAP.md`](B1_R_C_TASK_7_7_G4_AND_WRITER_MAP.md).

**Final collection:** 2026-09-09. **Baseline:** `main@108addca6d079b08d9d0822d87bfe43812cd5699`.
**Machine-readable final record:**
[`task-7-7-completion-20260909.json`](../evidence/b1-r-c/task-7-7-completion-20260909.json).
Sections 1–10 retain the earlier phases; section 11 supersedes their gate verdicts.

## 1. Historical initial scope and stop boundary

The owner assigned Task 7.7 only and excluded Task 7.8, deployment, workflow dispatch, production
writes, traffic/configuration changes, rollback, public exposure and Writer_Convergence. The frozen
collection contract therefore permits only control-plane GETs, read-only log queries, read-only
container GET probes and `REPEATABLE READ READ ONLY` SQL. It permits zero signups, zero seed/reset
calls and zero legacy-route POST probes.

The first Azure query was denied by the task host. The owner then replied **“Approved”**, authorizing
the previously blocked read-only Azure, Log Analytics and Neon collection. The resumed collection
made no production write and changed no revision, traffic, configuration, schedule or workflow. The
database connection used secret values only in process memory and emitted or persisted none. Docker
downloaded the immutable PostgreSQL client image
`sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777` locally before the
read-only query; it did not alter the production environment.

## 2. Verified local and tracked bindings

The requested baseline fetch completed in Codex's assigned persistent checkout. `FETCH_HEAD` and
`origin/main` both resolved to `7e752b4183aa25f75adad6e7b363501cd5f23aa5`, merge subject
`Merge pull request #238 from vibhanshu-agarwal/codex/b1-rc-predeploy-evidence`. This isolated
evidence branch now differs from that parent only by its docs/evidence commits.

The merged checkpoint independently verifies the asserted R-C candidate cut
`8f1e8a36f8baa594efa8079190f87b42139fcf10` and deployable `linux/amd64` manifest
`wealthprodacr.azurecr.io/portfolio-service@sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126`.
That evidence ends at Task 7.6 and is not serving proof.

The latest tracked serving record is not the older cu4 runtime summarized in the master plan.
R-B3r deployed portfolio revision `portfolio-service--0000095` at digest
`sha256:fa060bf054b9c108b8b59d9e9b27845d6b707f40040a7dcba16411db7f0e8552` on 2026-09-07,
superseding `0000094` / `2be727ea…`. The accepted R3 remediation record is
[`v21-r3-operational-result.json`](../evidence/b1-r3-decision1/v21-r3-operational-result.json).

## 3. Current serving bindings

All four apps are in Single revision mode with exactly one active 100%-traffic target:

| App | Serving revision | Configured image / registry observation | Relevant state |
|---|---|---|---|
| api-gateway | `api-gateway--0000077` | tag `63fc0584…` resolved in ACR to `sha256:79a3f253…` at collection time; runtime pull unattested | external TLS ingress; custom domain bound; insecure disabled |
| portfolio-service | `portfolio-service--0000095` | immutable configured reference `sha256:fa060bf0…` | demo startup/diagnostic flags false; scale-to-zero restored |
| market-data-service | `market-data-service--0000079` | tag `9b2cf0d6…` resolved in ACR to `sha256:ad61144b…` at collection time; runtime pull unattested | catalog overrides absent; scale-to-zero restored |
| insight-service | `insight-service--0000079` | tag `9b2cf0d6…` resolved in ACR to `sha256:f7db159d…` at collection time; runtime pull unattested | catalog overrides absent; scale-to-zero restored |

Mutable app tags were resolved against ACR manifests, but that observation does not attest which
bytes an already-running revision pulled at creation time. Only portfolio-service is configured by
immutable digest. The stale `SERVICE_VERSION` environment value was not used as artifact identity.

## 4. Read-only observations

- Public `GET /api/portfolio/health` returned 200 at `2026-09-08T17:05:49Z`.
- A direct localhost GET inside sole serving portfolio revision `0000095`, using
  `X-User-Id: 00000000-0000-0000-0000-000000000e2e`, returned 200: one portfolio, 159 holdings,
  numeric version 0. The 15,281-byte response SHA-256 was
  `18a6be3aedd65d88a79805376dd41254bc2f67d02a93e36d2bd2566faaf3dbf2`. This proves direct
  backend capability only; it is not the authenticated gateway read required by G2a.
- The `REPEATABLE READ READ ONLY` Neon snapshot at `2026-09-08T17:12:31.364Z` returned
  `violating_users=0`; all 10 users had exactly one portfolio. V21 was the successful Flyway head
  exactly once with checksum `385711525`; all four transient repair routines were absent across
  schemas. Both named constraints were validated, quantity had no default, no version/timestamp or
  quantity-domain violation existed, and all 318 holdings used the active 159-ticker catalog.
  Both repair-audit rows named active tickers; BTC/MM.NS had zero rows across holdings, current prices
  and history.
- All three current consumer revisions logged the same startup tuple:
  `version=a00b32ac0267e1a9 entries=160 active=159 rejectUnsupportedEvents=true enforceHoldingInvariant=true`.
  The bounded revision-lifetime query found no `catalog_load_failed`,
  `post_migration_integrity_failed`, `unsupported_asset_event_rejected` or shadow-rejection marker.
  Three old insight-service broken-pipe warnings were client disconnects after response commit.
- The scheduled refresh job remains `0 8 * * *`, parallelism/completion 1, retries 0, timeout 600,
  and runner enabled. Its latest five executions succeeded; the latest ran
  `2026-09-08T08:00:00Z`–`08:01:12Z`. Postgres prices/history both reached observed-at
  `2026-09-08T08:01:00.756Z`.

A public `GET /api/assets` returned 401 and was not retried through login because login was outside
the frozen read-only proof shape. One cold-start health request timed out at 30 seconds before the
successful retry. Neither event changed application data.

The commands wrote results to the live task transcript only; no separate raw-output capture or exact
expanded-SQL file was retained. The machine-readable record is a sanitized consolidation, not a
raw capture. The final log checks were repeated with fixed end-exclusive bound
`2026-09-08T17:50:18.132Z`; their exact KQL and UTF-8 query hashes are in
[`task-7-7-log-queries-20260908.kql`](../evidence/b1-r-c/task-7-7-log-queries-20260908.kql).
G3 used the relational predicate in
[`B1_R_B_G3_SERVING_PROOF.md`](B1_R_B_G3_SERVING_PROOF.md), and the V21/routine query shape is
tracked in [`v21-r3-operational-query.sql`](../evidence/b1-r3-decision1/v21-r3-operational-query.sql).
This provenance limit is another reason not to promote the partial observations into a complete
Task 7.7 acceptance.

## 5. Historical 2026-09-08 evidence oracle and pre-authorization result

This table is the snapshot reviewed before the later authorization. Section 10 supersedes its
authorization, startup-coverage, DLT and Kafka observations; the gates remain open for the current
reasons recorded there.

| Gate | Required Task 7.7 evidence | Reusable evidence | Result |
|---|---|---|---|
| G2 | Complete current gateway serving set, immutable digest/traffic binding and provisioning proof | Current set/configured tag, registry resolution plus four provisioning-critical blobs identical to historical G2 cut | **UNVERIFIED** — no runtime-digest attestation or controlled signup on current revision `0000077`; lineage is not serving behavior |
| latest-valid G3 | Relational `users LEFT JOIN portfolios ... HAVING COUNT(p.id) <> 1` snapshot after latest valid G2 | Current read-only snapshot | **OBSERVED GREEN, SEQUENCE UNSATISFIED** — zero violations, but current G2 is not valid |
| G4 | Current Spec A artifact/catalog identity, effective enforcement on all three consumers, portfolio behavioral enforcement, V17–V21/repair state, refresh steady state and bounded error/consumer observations | Current control-plane, logs, SQL and job observations plus the approved zero-mutation evidence-equivalence protocol | **PARTIAL, OBSERVATIONS GREEN** — already-running market-data and insight bytes remain unattested; retained `arg_max` proves only each revision's latest tuple; and all-partition lag-zero plus bounded `market-prices.DLT` non-growth are unverified |
| G0a | Every current serving portfolio revision/digest lacks both retired writers | Sole current revision/digest; R-B3r is cu4 runtime plus V21 only | **UNVERIFIED** — both current legacy-route POST probes remain unauthorized |
| G2a | Numeric Portfolio_Version from every current serving portfolio digest on the authenticated read | Current complete set plus direct in-revision GET | **PARTIAL, DIRECT CAPABILITY ONLY** — sole 100%-traffic revision returned numeric version 0, but no authenticated gateway read was authorized |
| G2b | Every serving portfolio digest requires version and delegates; controlled proof preserves identity and the complete Spec A price/schema state | R-B3r history and V21-only runtime lineage | **UNVERIFIED** — no current controlled seed with the complete before/after oracle |
| G6 | Fresh serving G0a + G2a + G2b, joined to exhaustive writer coverage for the serving and candidate cuts | Task 7.6 candidate inventory plus the explicit R-B3r serving-source map | **UNVERIFIED** — the source map is complete, but G0a/G2a/G2b are incomplete; mapping alone cannot establish the derived gate |

G3 must be collected after the latest valid G2 evidence and after any other authorized production
writes. One successful load-balanced request cannot prove a universal serving gate: collection must
enumerate every active revision, nonzero traffic destination, revision-label route and otherwise
addressable serving revision, with pre/post drift checks.

## 6. Historical evidence that may be reused

- Candidate Task 7.3–7.6 identifiers, hashes, exact-digest smoke and exhaustive candidate writer
  inventory from the September 8 checkpoint.
- The accepted R-B3r V21 record for its exact source, digest, revision, one successful migration,
  absence of the four transient repair routines, ordinary version-bearing read, one SAME_STATE seed
  outcome and retired public composition-route result.
- Earlier G2/G3/G0a/G2a/G2b runbooks as protocol and historical observations only.

Reuse requires exact source/artifact/configuration bindings and current invalidator checks. Historical
checkboxes, mutable tags, `SERVICE_VERSION`, old G3 counts and old approvals are not current proof.

## 7. Historical minimum completion bundle — authorization later recorded; execution stopped in §10

1. Run one controlled signup against every serving gateway digest, verify its one-user/one-
   portfolio provisioning transaction, and use its token for the authenticated G2a read.
2. Run both retired legacy-route POST probes against every serving portfolio digest:
   `POST /api/portfolio` must return 405 with `Allow: GET`; the versionless
   `POST /api/portfolio/{portfolioId}/holdings` must return 404.
3. Run one frozen-version seed with separate complete BEFORE and AFTER tuple, price, history and
   schema snapshots, no retry and no inherited rollback authority.
4. Preserve the independently accepted zero-mutation G4 evidence-equivalence protocol, then collect
   only its remaining immutable-runtime-identity, fixed-window all-startup-line/replica, all-partition
   lag-zero and bounded DLT non-growth predicates if separately approved. Do
   not invent a live negative holding probe: R-B3r exposes no applicable arbitrary-holdings input.
5. Preserve the completed Task 7.6 inventory-to-R-B3r serving-cut mapping and re-open it on any
   relevant source or serving-cut drift.
6. Recollect final G3 after all authorized writes, derive G6 and obtain independent whole-packet
   review. Any serving drift invalidates the affected observation and stops the bundle.

## 8. Historical decision boundary — current result in §11

Task 7.7 remains unchecked. Task 7.8 cannot be presented to the owner from this packet. The R-C
candidate remains undeployed. Current serving state is bound and its read-only evidence is recorded,
but the write-bearing gates above are still open. No Writer_Convergence or public-exposure claim is
made.

## 9. Independent future development work — unstarted

The following source/process tasks are independent of Task 7.7 but require separate owner approval
before assignment. None was started here:

- **Terra:** Spec A Task 8.8, replace remaining hard-coded catalog-size assertions with the
  Active_Asset cardinality while retaining explicit catalog-version checks.
- **Luna:** B2 Task 2.7, reconstruct and document the historical backend-before-adapter frontend
  artifact, routing/cache and rollback-containment window; no fresh cloud access is included.
- **Terra:** B2 Task 10.1 source-only CI/CD flag wiring with both repository variables remaining
  unset. Creating variables, enabling either flag, deploying or exposing production remains outside
  that development assignment.

## 10. Authorized completion attempt — hard stop before writes

The owner replied **“Authorized. Please complete the remaining work on task 7.7.”** on 2026-09-09.
That authorization covered the remaining read-only predicates and, only after they were green, the
minimum signup/read, legacy-route and one-seed proof bundle. It did not authorize repair,
configuration, deployment, traffic, rollback, Task 7.8 or production exposure.

Pre- and post-collection control-plane reads matched: every app remained in `Single` mode with the
same sole active 100%-traffic revision recorded above. Exact-revision system logs and OCI metadata
provided corroborative pull-reference and byte-count observations only. Every observed pull used
the configured reference and an exact byte count equal to raw manifest + config + layer
bytes for the corresponding immutable ACR digest: gateway `291792374` → `79a3f253…`, market-data
`301484607` → `ad61144b…`, and insight `366233644` → `f7db159d…`; portfolio remained configured
directly as `fa060bf0…`. Each tag's ACR creation and last-update timestamps are identical and
precede revision creation. The tags remain write-enabled, and independent review rejected this
pull-size/timestamp join as immutable content attestation: equal byte length is not content identity.
Runtime identity for gateway, market-data and insight therefore remains **UNVERIFIED**, independent
of the Kafka failure. Only portfolio is configured by immutable digest.

The fixed end-exclusive window at `2026-09-08T19:05:50.938Z` closed the startup-line gap. All nine
replicas observed through `ImagePulled` were covered: five portfolio, two market-data and two
insight replicas. Every replica had exactly one `catalog_loaded` line, `TupleCount=1`, and the sole
tuple `a00b32ac0267e1a9|160|159|true|true`. The retained query uses no `arg_max`. Bounded marker
results remained green; only the three previously classified insight client-disconnect warnings
matched generic `Exception` text. Exact KQL is retained in
[`task-7-7-authorized-execution-20260908.kql`](../evidence/b1-r-c/task-7-7-authorized-execution-20260908.kql).
The nine per-replica result rows were summarized from the live transcript rather than retained as a
sanitized local table; the checked-in query and aggregate are reproducible, but local review cannot
independently replay each raw row from this packet alone.

Kafka metadata established one partition for `market-prices` and one for `market-prices.DLT`.
Across the bounded window `2026-09-08T19:10:02.3389923Z` through
`2026-09-08T19:20:09.3690188Z`, DLT partition 0 stayed at end offset `80`, proving non-growth.
`portfolio-group` partition 0 was green at committed/log-end `26602/26602`, lag `0`, with no active
member. After initial coordinator timeouts, a bounded 120-second description of `insight-group`
returned `GroupIdNotFoundException: Group insight-group not found`. This is a definitive failed
predicate, not lag zero and not a waiver candidate.

The protocol forbids joining a group, consuming payloads, committing/resetting offsets or producing
a record. Waking or recreating `insight-group` would therefore manufacture new state rather than
prove the required pre-existing consumer position. Execution stopped immediately. Signup attempts,
authenticated reads, legacy-route POSTs, seeds, database mutations, Kafka payload reads/joins/
commits/resets/produces, workflow dispatches, deployments, traffic/config changes and rollbacks all
remain zero. Final G3 was not recollected because no latest-valid G2 was created and no authorized
write was reached.

Accordingly G4 is open on both unverified mutable-tag runtime identity and failed consumer steady
state; G2, G0a, authenticated G2a, G2b and derived G6 remain open. Task 7.7 remains unchecked and
Task 7.8 remains unavailable. Cryptographic runtime attestation (or immutable-digest replacement
revisions) and recovery of the missing group each need a separate owner-reviewed design. Group
recovery must not misrepresent recreated offsets as historical lag evidence. Only after both paths
are accepted and executed may a fresh complete read-only precondition packet be collected.

## 11. Final authorized completion — independently accepted PASS

### 11.1 Immutable serving set

The three tag-configured services were replaced by image-only revisions pinned to exact ACR digests
already joined to source/build provenance. Portfolio remained unchanged. Final serving state is
`Single`, one healthy/latest-ready revision and 100% traffic for each app:

| App | Final revision | Exact configured digest |
|---|---|---|
| api-gateway | `api-gateway--0000078` | `sha256:79a3f253aaab1f3db58146612e8f2d05a70df6180482ace16efa1baef58469f3` |
| portfolio-service | `portfolio-service--0000095` | `sha256:fa060bf054b9c108b8b59d9e9b27845d6b707f40040a7dcba16411db7f0e8552` |
| market-data-service | `market-data-service--0000080` | `sha256:ad61144b2e747a5dd1b1fc9f5b5a091916559adf7c30117beae3563123aa2256` |
| insight-service | `insight-service--0000080` | `sha256:f7db159d5b07085e471d784d50456a1eb384abe178a8fbcf7f4b5b17e916a46b` |

Ingress, identity, environment/secret references, resources, scale, command/args and revision mode
were preserved. The refresh job changed image only and retained schedule `0 8 * * *`, retry limit 0
and timeout 600 seconds.

### 11.2 Transparent retained-range recovery and G4

Before recovery, `insight-group` was absent. The topic retained offsets 25808 through 26602 and the
broker offset-retention setting was 10,080 minutes. Starting the immutable insight revision created
current group state and replayed the retained range. This establishes bounded recovery and current
steady state; it does **not** claim historical committed offsets or replay before the retention floor.

At `02:00:53Z`, `02:03:11Z` and `04:14:21Z`, both required groups were at `26602/26602`, lag zero,
while `market-prices.DLT` stayed at end offset 80. The insight revision had zero DLQ, processing-error,
Redis-failure or retry-exhaustion markers. One generic broken-pipe warning was a client disconnect
after a timed-out aggregate-summary request, not a replay/data failure.

A direct TLS read-only Redis/PostgreSQL comparison covered all 159 active tickers. It found no
missing, extra-fresh or null Redis entry, zero price mismatches after HALF_UP normalization to
PostgreSQL `NUMERIC(19,4)`, and zero timestamp mismatches at epoch-millisecond precision. The final
fixed-bound startup query covered all 11 observed replicas (7 portfolio, 2 market-data, 2 insight);
each emitted the sole tuple `a00b32ac0267e1a9|160|159|true|true`. G4 is PASS. Exact queries are in
[`task-7-7-completion-20260909.kql`](../evidence/b1-r-c/task-7-7-completion-20260909.kql).

### 11.3 G2, G0a and G2a

The required signup returned 201 and created exactly one user, credential and portfolio. Its token
could not be retained after the same-process authenticated read returned 504. With explicit owner
approval, one recovery signup returned 201 in one attempt and created exactly one additional user,
credential and version-0 empty portfolio. That token's bounded authenticated GET returned 504 then
200. No third signup or cleanup occurred.

Using that identity, `POST /api/portfolio` returned 405 with `Allow: GET`, and versionless
`POST /api/portfolio/{portfolioId}/holdings` returned 404. G2, G0a and G2a are PASS.

### 11.4 Exactly-one seed, final G3 and G6

The E2E portfolio started at frozen version 0 with 159 holdings. Complete BEFORE snapshots preceded
transcript marker `TRACE_ID=2aa3d19ae8724fc999c4e00141742c69`; one POST was sent with zero retries
and redirects. It returned 200 with identities preserved and `holdingsInserted=159`. Complete AFTER
snapshots were byte-identical across schema, E2E/demo portfolios and holdings, market prices and
market history. G2b is PASS with SAME_STATE.

The post-write read-only snapshot found 12 users and 12 portfolios, every user at exactly one
portfolio, `violating_users=0`, V21 successful once with checksum `385711525`, all four repair
routines absent, both constraints validated, and zero integrity violations. Latest-valid G3 is PASS.
Fresh G0a/G2a/G2b joined to the accepted exhaustive Task 7.6 writer map makes G6 PASS.

### 11.5 Boundary

All Task 7.7 technical predicates are green. Astra independently issued ACCEPT with no Critical,
Important or Minor findings. Task 7.7 is complete and Task 7.8 is the next owner STOP/GO. No R-C
candidate deployment, public composition exposure or Writer_Convergence claim occurred.
