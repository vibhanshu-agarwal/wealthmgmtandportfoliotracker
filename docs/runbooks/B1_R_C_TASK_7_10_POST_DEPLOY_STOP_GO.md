# B1 R-C Task 7.10 — post-deploy STOP/GO, 2026-09-09

> **OWNER APPROVAL REQUIRED — Task 7.10 STOP/GO.**
>
> **Decision requested:** Record **GO** or **ABORT** for Task 7.10. The technical packet is green and Sol recommends **GO**, but the owner decision is **PENDING**. Independent review found issues; correction and re-review are pending.
>
> **GO:** record the owner's GO, then locally close/update the task ledger. GO authorizes **no** production mutation, endpoint call, wake, traffic/configuration change, rollback, publication, or Task 7.11 action.
>
> **ABORT:** this does **not** authorize a rollback by itself. R-B3r portfolio-service--0000095 is the historical identity, but Task 7.9 records that Single mode purged its revision object. A separate explicit owner rollback authorization is required before deploying exactly sha256:fa060bf054b9c108b8b59d9e9b27845d6b707f40040a7dcba16411db7f0e8552. That deployment creates an actually named new revision, which must be verified as serving that exact digest. Never roll below the floor. The unused Task 7.9 contingency grants no continuing authority. Task 7.11 remains separate.

## Scope and provenance

This is a retained-evidence decision packet supplied by Sol and recorded by Terra. It does not perform or refresh cloud, registry, workflow, secret, Kafka, database, endpoint, or production queries. No application endpoint was called and no scale-to-zero service was woken for this packet. No raw Task 7.10 query/output transcript or stable external handoff artifact was retained. Observability, Azure Activity Log, Kafka, database, and cleanup results are collector-reported sanitized retained evidence; neither Terra nor Astra independently replayed or revalidated their query semantics or results. The machine-readable companion is [task-7-10-post-deploy-assessment-20260909.json](../evidence/b1-r-c/task-7-10-post-deploy-assessment-20260909.json).

The retained baseline is PR #242 merge commit 118a19083e596b94a5801d588f7396eb963197f7. The retained deploy workflow [34328692256](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/34328692256) completed successfully. Its retained head is abbreviated as 5fd1dac6 (not revalidated here); candidate source is 8f1e8a36f8baa594efa8079190f87b42139fcf10, candidate JAR SHA-256 is 441d252939d7333dca134b9cc1a5f6a0632cc274a53f410671212c543a85cda4, and the candidate image is wealthprodacr.azurecr.io/portfolio-service@sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126.

All four retained SHA-256 bindings are over checked-out file bytes in this Windows worktree, with CRLF where present. The Task 7.9 evidence, Task 7.9 runbook, task ledger, and master plan bindings are respectively 0afbc70b1f15662ae312bcf9b11fa123996c6f8ce87e4e8cd64badd0bb9a3b9c, 4a51525dc426612ecc15c0f96bd5d35d7c0666bb5320ccd5de615d6be8c64748, 6a90cc7411f7464e1b053d0b48ef5daa64fc991a739000508e237878a0268edf, and 291edf831c78aaafc8569cb4d3686db590e859c77658a6ad8280c8f199d5021d. The committed LF task-ledger blob companion SHA-256 is 4f6d7d41a098aa8d6e2fbe461c8f9bbac4a64f6227f24168895b7817abc9eaa5; it is not the Windows working-tree binding.

## Retained post-deploy assessment

At retained observation 2026-09-09T10:05:13.6479484Z, portfolio-service--0000096 was the sole active latest/latest-ready revision in Single mode, Healthy/Provisioned and Succeeded/Running, serving the exact candidate image at 100% traffic. It had zero replicas, the expected scale-to-zero condition; no wake was performed.

api-gateway--0000078, market-data-service--0000080, and insight-service--0000080 retained their recorded digests: 79a3f253…, ad61144b…, and f7db159d…. The refresh job was unchanged at the market digest, schedule 0 8 * * *, retry limit 0, and timeout 600 seconds. Its last three runs succeeded; market-data-refresh-job-29815680 ran 08:00:00–08:01:12Z.

| Proposed GO predicate | Retained result |
|---|---|
| Task 7.9 exact-digest deployment | Green — workflow 34328692256 completed successfully. |
| Serving revision and candidate identity | Green — 0000096 sole active/latest-ready, 100% traffic, exact digest. |
| Peer and refresh-job non-interference | Green — retained peer digests and refresh settings unchanged. |
| Bounded failure classification | Green — empty classified marker results and no portfolio Failed/Unhealthy/BackOff/Crash/Killing reasons. |
| Kafka | Green — portfolio and insight groups each 26760/26760, lag 0; DLT stayed at 80. |
| Database integrity | Green — 12 users, 12 portfolios, 318 holdings, zero violations/bad rows/null state/legacy rows, V17–V21 successful, V21 once/checksum 385711525. |
| Control plane | Green — no writes/deletes in the retained final Activity Log window. |

## Bounded evidence detail

The collector-reported console window was 2026-09-09T08:38:31.9730193Z through 2026-09-09T10:05:16.7396363Z, covering gateway 0000078, portfolio 0000096, market 0000080, and insight 0000080. Classified [INSIGHT-DLQ], processing error, Redis failure, retry exhausted, catalog_load_failed, post_migration_integrity_failed, unsupported_asset_event_rejected, would_reject_unsupported_event, generic ERROR, and Exception markers were empty. In the collector-reported portfolio system window from 08:25:59Z to the same end, no Failed, Unhealthy, BackOff, Crash, or Killing reason occurred.

The collector-reported Azure Activity Log window was 2026-09-09T08:40:00Z through 2026-09-09T10:05:16.0447220Z. It contains no writes or deletes. The only recorded actions were Microsoft.App/containerApps/listSecrets/action start/success pairs around 10:00:14/15Z and 10:03:53/54Z, attributable to the expressly owner-approved Kafka/database read workflow; the earlier read through 09:46 was empty.

In the active Codex session, after Sol paraphrastically disclosed potential credential exfiltration and transient Docker environment retention, no secret printing, and container destruction, the owner replied exactly “Approve.” No external artifact/session ID is asserted, and Sol's disclosure is not represented as an exact quote. That approval covered the local-container risk for the pinned Kafka client confluentinc/cp-kafka@sha256:acbbf674f2ed40e5d0a8ca51beb0f00692c866fc22b5ce06f8cadbdc54cd4436 and the pinned PostgreSQL client postgres@sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636. Collector-reported Kafka collection (10:00:14.8617913Z–10:03:53.6933444Z) was metadata-only: no payload consumption, producer, group join, offset commit/reset/delete. The Docker client/container was reported removed afterward. Collector-reported database collection used REPEATABLE READ READ ONLY, began at 10:03:04.6950233Z, observed results at 10:03:07.497187Z, printed BEGIN and COMMIT, and reported its container removed afterward.

## Explicit limits and tooling record

The evidence is historical retained evidence, not a fresh attestation at decision time. It excludes secrets, credential-bearing connection strings, tokens, raw customer payloads, and Kafka payloads. The known tooling limitations are preserved: PowerShell native-command marshalling broke a multiline KQL attempt, while the final one-line query worked; first/last aliases were changed to firstSeen/lastSeen; Level_s was absent and verified Reason_s was used. A first Kafka secret resolution omitted --show-values and stopped before Docker. A first secret-container request was blocked before secret retrieval until explicit owner-risk acceptance. A database orchestration string had a JavaScript delimiter syntax error and never launched. Three controller patch attempts failed before a usable patch (template/backtick collision, UTF-8 PATCH argument error, malformed End Patch); the controller stopped rather than bypass the patch-only rule, and the worktree remained clean.

## Decision record and next boundary

Current assessment: all proposed GO predicates are green; rollback is unused and unauthorized; Task 7.10 remains incomplete; owner decision is pending. Independent review found issues, and correction/re-review are pending. Sol recommends GO. Neither this packet nor a future GO changes Task 7.11, Writer_Convergence, publication, or any production state. Do not check Task 7.10 or update the ledger until the owner records GO.
