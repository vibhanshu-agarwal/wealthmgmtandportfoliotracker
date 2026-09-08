# B1 R-C Task 7.7 — pre-deploy serving evidence

> ## OWNER APPROVAL REQUIRED LATER — live collection remains separate
>
> **Blocked actions:** one controlled signup plus authenticated portfolio read on each serving
> gateway/portfolio digest, both retired legacy-route POST probes on each serving portfolio digest,
> and one frozen-version seed with the complete before/after oracle. The approved G4 protocol is
> zero-mutation, but its remaining runtime-identity/read-only collection is also not authorized by
> the documentation approval. If the later bundle is declined or deferred, Task 7.7 remains open
> and Task 7.8 must not be presented.

**Status:** READ-ONLY COLLECTION COMPLETE; G4 PROTOCOL AND WRITER MAP INDEPENDENTLY ACCEPTED;
TASK 7.7 OPEN.
Direct-revision G2a capability is observed, but the authenticated G2a predicate is not verified. G3
is currently green but out of sequence; G4 is partially green; G2, G0a, G2a, G2b and derived G6
remain unverified. The approved protocol and serving-source map are in
[`B1_R_C_TASK_7_7_G4_AND_WRITER_MAP.md`](B1_R_C_TASK_7_7_G4_AND_WRITER_MAP.md).

**Recorded:** 2026-09-08. **Baseline:** fetched `main` merge
`7e752b4183aa25f75adad6e7b363501cd5f23aa5` (PR #238), which is the parent baseline of this
docs-only evidence branch. **Machine-readable record:**
[`task-7-7-serving-gates-20260908.json`](../evidence/b1-r-c/task-7-7-serving-gates-20260908.json).

## 1. Scope and stop boundary

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

## 5. Evidence oracle and current result

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

## 7. Minimum completion bundle after separate authorization

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

## 8. Decision boundary

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
