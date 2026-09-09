# B1 R-C Task 7.11 — Writer-Convergence Property Proof

> [!IMPORTANT]
> ## LOCAL-ONLY EVIDENCE CONSOLIDATION — NO PRODUCTION OPERATION OR PUBLICATION
>
> This runbook consolidates committed, sanitized evidence locally.  It performs no new production
> operation and authorizes no deployment, rollback, push, pull request, merge, publication, cloud
> or secret access, endpoint operation, workflow action, application change, or broader backlog
> closure.  Its machine-readable companion is
> [`task-7-11-writer-convergence-floor-20260909.json`](../evidence/b1-r-c/task-7-11-writer-convergence-floor-20260909.json).

**Status:** `COMPLETE_LOCAL`
**Independent final-review verdict:** `ACCEPT` — Astra, for reviewed candidate `3501c53dc7159068781ed95e64fdcd0006e07157`; Critical 0, Important 0, Minor 0.
**Task 7.11 acceptance:** locally complete. The review record persisted/was observed locally at `2026-09-09T14:54:03.2693903Z`; this is not an externally attested review time. Astra's candidate verdict does not review the later ledger-closeout commit, which requires its own Luna task review and final whole-branch Astra review.

## 1. Decision boundary and property split

This is an immutable-evidence interpretation, not a fresh serving-state attestation.  Historical
serving observations remain time-scoped to the artifacts and revisions recorded below.  The local
comparison found no application-source change from candidate cut `8f1e8a36f8baa594efa8079190f87b42139fcf10`
to the evidence baseline under `portfolio-service` or `api-gateway`; only
`scripts/package-lock.json` and `scripts/package.json` differed.  That is a local
no-application-drift check, not a current runtime query.

P11g-1 and P11g-2 are separate properties.  Passing P11g-1 in the transitional R-B/R-B2 range
does **not** establish Writer_Convergence.

### P11g-1 — transitional rollback-floor lineage

| Verdict | Range | What the evidence establishes | What it expressly does not establish |
|---|---|---|---|
| `ACCEPT` | R-B through before R-C activation | P11g-1 is established: duplicate-creating public paths remain retired, and signup provisioning remains present. The R-B/R-B2 versionless seed is permitted transitional behavior. | Writer_Convergence / P11g-2. A versionless transitional seed means this range cannot be promoted to the universal writer property. |

The direct historical R-A G2 binding is deliberate: the sole serving revision
`api-gateway--0000076`, at
`sha256:2da5b303fd15772792167f2b26dc62250b2d9858270db315eab1d6d1a1554aec`, recorded a
loopback signup returning `201` and exactly one user plus one portfolio.  It is durable,
sanitized historical evidence—not a fresh gateway verification.  R-B G0a also recorded
`POST /api/portfolio = 405` with `Allow: GET` and versionless holdings `POST = 404`.

### P11g-2 — Writer_Convergence after exact R-C activation

| Verdict | Scope | Universal proof obligation | Boundary |
|---|---|---|---|
| `ACCEPT` | Activated exact R-C candidate at `portfolio-service--0000096`, and rollback artifacts at or above R-B3r only | Writer_Convergence / P11g-2 is established: every one of the six applicable reachable runtime `asset_holdings` writer paths participates in `Portfolio_Version` and preserves portfolio identity. | All 15 accepted candidates are dispositioned first; the other nine are explicitly excluded or exempt, never silently dropped. No validity is claimed below R-B3r. |

The universal claim is not a conjunction limited to G0a/G2a/G2b.  Fresh G0a, G2a and G2b form
derived G6 only when joined to the accepted, exhaustive writer inventory.  The six applicable
paths are `HoldingReplacementService`, `CompositionWriteService`, `PortfolioSeedService`,
`DemoResetService`, `DemoPortfolioInitializer`, and `CompositionController`; delegating paths
have no independent DML and converge on `HoldingReplacementService`.

Requirements [8.1 and 8.4](../../.kiro/specs/portfolio-composition-contract/requirements.md)
require each holdings writer to route through `Composition_Operation` or the same
`Portfolio_Version` mechanism and preserve portfolio identity.  Requirement 8.10 exempts Flyway
only because it is outside the application context; it does **not** exempt Flyway from
`Quantity_Domain` or Spec A `New_Write_Invariant` obligations.  Scheduled or apparently
non-concurrent seeding is not an exemption.

## 2. Immutable release and artifact chain

| Release / artifact | Source commit | Serving revision / immutable digest | Property role | Retained evidence |
|---|---|---|---|---|
| R-0 retirement | release lineage | — | P11g-1 prerequisite: legacy public writers retired | [design](../../.kiro/specs/portfolio-composition-contract/design.md); R-B G0a record |
| R-A provisioning | release lineage | `api-gateway--0000076`; `sha256:2da5b303fd15772792167f2b26dc62250b2d9858270db315eab1d6d1a1554aec` | P11g-1 signup predicate; direct historical G2 | [R-A G2 proof](B1_R_A_G2_SERVING_PROOF.md) |
| R-B | `25aa730e4b0cac79532a3b5d2235719cda520f54` | `portfolio-service--0000080`; `sha256:d111132f576780fa5fec67dfc26ada3324153794746d21fe84b93b6822be3535` | P11g-1 G0a/G0b/G2 then G3; transitional seed allowed | [R-B G3 proof](B1_R_B_G3_SERVING_PROOF.md) |
| R-B2 | `f22e2ffee78262f5526aec0bc8b4324076f30de7` | `portfolio-service--0000081`; `sha256:d544649f5b67baec8b563016882d239d3ecb9c5672399586e0bc656c78961d4f` | P11g-1 continuation; numeric version on serving revisions | [R-B2 G2a proof](B1_R_B2_G2A_SERVING_PROOF.md) |
| **R-B3r raised floor** | `97b83e52bcbfface4377cefd69ebb111270cb21e` | historical `portfolio-service--0000095`; `sha256:fa060bf054b9c108b8b59d9e9b27845d6b707f40040a7dcba16411db7f0e8552` | Safe pre-activation floor; version-required seed/G2b; minimum rollback identity | [Task 6.6 G2b proof](B1_TASK_6_6_G2B_SERVING_PROOF.md), [Task 7.7 map](B1_R_C_TASK_7_7_G4_AND_WRITER_MAP.md) |
| exact R-C candidate | `8f1e8a36f8baa594efa8079190f87b42139fcf10`; JAR `441d252939d7333dca134b9cc1a5f6a0632cc274a53f410671212c543a85cda4` | `wealthprodacr.azurecr.io/portfolio-service@sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126` | P11g-2 candidate; Task 7.6 PASS, findings `0`, unverified coverage `0` | [candidate checkpoint](../evidence/b1-r-c/candidate-evidence-checkpoint-20260908.json) |
| R-C activation | exact candidate source above | `portfolio-service--0000096`; exact candidate digest; 100% latest-ready traffic | P11g-2 exact-artifact activation; one authenticated SAME_STATE composition operation | [Task 7.9 proof](B1_R_C_TASK_7_9_SERVING_PROOF.md) |
| post-deploy decision | exact candidate retained | same `0000096`; no rollback | Owner GO/no rollback is retained evidence, not a Task 7.11 acceptance | [Task 7.10 stop/go](B1_R_C_TASK_7_10_POST_DEPLOY_STOP_GO.md) |

The `0000095` revision name is historical and was purged by Single revision mode.  It cannot
itself be targeted for rollback.  The exact R-B3r digest is the rollback artifact identity; a
future rollback requires separate explicit owner authorization and a new named revision proven to
serve exactly that digest.  Rollback below R-B3r is prohibited for this property.

## 3. Complete accepted writer inventory and dispositions

The accepted map contains exactly 15 candidates.  It is the anti-omission proof: every candidate
is classified before the P11g-2 quantifier is applied.  Six are applicable reachable runtime
holdings-writer paths; nine are excluded or exempt on the stated basis.

| # | Accepted candidate | P11g-2 disposition | Result |
|---:|---|---|---|
| 1 | `HoldingReplacementService` | **Applicable** effective runtime holdings DML mechanism | Parent-version CAS, then same-transaction child replacement; portfolio identity preserved. |
| 2 | `CompositionWriteService` | **Applicable** activated R-C delegating path | No independent DML; delegates completely to `HoldingReplacementService`; absent from R-B3r. |
| 3 | `CompositionHoldingsRequest` | Excluded: validation-only DTO | No runtime holdings DML; null-element validation does not create a writer. |
| 4 | `PortfolioSeedService` | **Applicable** runtime delegating path | Passes caller `expectedVersion` unchanged to `HoldingReplacementService`. |
| 5 | `DemoResetService` | **Applicable** runtime delegating path | Passes `expectedVersion`, fixed demo identity and catalog-derived desired state. |
| 6 | `DemoPortfolioInitializer` | **Applicable** runtime delegating path | Freezes observed version then calls seed; diagnostic probe rolls back and never seeds. |
| 7 | `CompositionController` | **Applicable** activated R-C runtime intent path | No independent DML; delegates through `CompositionWriteService`; absent from R-B3r. |
| 8 | `Portfolio.replaceAllHoldings` | Excluded: unreachable dead path | Zero call sites; inventory reopens if a caller appears. |
| 9 | V3/V7/V15/V18/V19/V20 statements | Exempt: historical Flyway outside runtime quantifier | One-time schema/data transitions, not a runtime CAS path; domain/invariant duties remain. |
| 10 | V17 repair functions | Exempt: historical Flyway outside runtime quantifier | Retired by V21; retained database evidence records global absence. |
| 11 | V21 | Exempt: historical Flyway outside runtime quantifier | Forward-only retirement; drops functions and performs no `asset_holdings` DML. |
| 12 | `ON DELETE CASCADE` from `portfolios` to `asset_holdings` | Excluded conditional structural risk | No `Portfolio_Version` gate of its own; conditionally safe only while no reachable portfolio-delete caller exists. |
| 13 | diagnostics `DELETE FROM portfolios WHERE FALSE` | Excluded: non-writer diagnostic path | Matches zero rows and the enclosing probe rolls back. |
| 14 | API-gateway `UserCredentialRepository` portfolio insert | Excluded: parent-only, outside holdings quantifier | Creates a version-0 parent during signup; never writes `asset_holdings`; governed separately by G2/G3. |
| 15 | market-price, market-data and insight matches | Excluded: other table/store outside portfolio holdings | None for portfolio holdings; excluded only after explicit classification. |

The cascade condition is material.  Adding, changing, or making reachable any portfolio-delete
caller reopens this inventory and invalidates P11g-2 until its `Portfolio_Version` and identity
preservation disposition is independently proved.

## 4. Canonical input-byte bindings

Each input is bound by its last reachable path-changing commit, the resolved `commit:path` blob,
and SHA-256 over raw Git blob bytes.  This avoids checkout newline conversion.  The companion
record contains the same 15 bindings.

| # | Input | Commit | Blob | SHA-256 raw blob bytes |
|---:|---|---|---|---|
| 1 | [design](../../.kiro/specs/portfolio-composition-contract/design.md) | `17f3ef61353fa41c9a6acdce56fc77847edd3482` | `8156324f05fbcee5aadb464de0ca06c784290b94` | `78075b196049bba0973d99ee045a159bf8812af2afb852cc986c7d71c166adaa` |
| 2 | [requirements](../../.kiro/specs/portfolio-composition-contract/requirements.md) | `e94ebe2281a3307c8792109c245ab9c86c5fe74c` | `cbba0b38741bf2358f6605ca21f5fa8912f2e2b1` | `a1ac5c0576c7bdb1016d3438d7dea8e4e5bf541fe34447bbc1aba6087c8fd6c6` |
| 3 | [tasks](../../.kiro/specs/portfolio-composition-contract/tasks.md) | `62dfe938652372a35f0b87f940a892fba229fd37` | `21959f31c12c9b93ca2d768c46ca6eb366fb115c` | `6e78a627f84ec987c4d2793fd309f6d9b26cd3453c19c19795f89e60fa553972` |
| 4 | [candidate checkpoint](../evidence/b1-r-c/candidate-evidence-checkpoint-20260908.json) | `805f5195c4afbdf93094226d5c8761e537e63d62` | `4fd760c95fa1f4c9b9213365fbc846adbbb02750` | `d64dc24d7d4c3685297e6661f7481621781e7982b6d73f0336ba427833ed85b0` |
| 5 | [Task 7.7 JSON](../evidence/b1-r-c/task-7-7-completion-20260909.json) | `d2fe7fc9c4e9f6bdce07d930312b4fff174d2ab9` | `3bd7208a06e81e4e53523f65982e8d805e6a470d` | `0962c1d36f768efe6aeab1d3bfb9c7ad84a85a8e2489ff5e0de457ec4acc45be` |
| 6 | [Task 7.9 JSON](../evidence/b1-r-c/task-7-9-serving-proof-20260909.json) | `11ef5b7d8306f8edc322b6248319e9c8d0b2e87e` | `45db1071da0f966aa9d2416a9565dd53c29afbbe` | `8b9b67420ca996ae92cca39cfa4aae3de17b628116453dfdd4de546ca436ccf3` |
| 7 | [Task 7.10 JSON](../evidence/b1-r-c/task-7-10-post-deploy-assessment-20260909.json) | `62dfe938652372a35f0b87f940a892fba229fd37` | `dd9ec0eca9f140071f8ac0dc93ccf85383a43a72` | `60a098bcbe77af0aab40b83cf41803c30e21bf9d5bb971f1a88f8cabd202d0ca` |
| 8 | [R-B G3](B1_R_B_G3_SERVING_PROOF.md) | `6c083d6b4cf16f09003508870305c370d20d21b1` | `18841571230a7b24cf26baff54ab49ecfaf005d1` | `68a8e2a9997e0b7caf9c78def70af7695e133199294e57f2a56e0dcb3f0cd57b` |
| 9 | [R-A G2](B1_R_A_G2_SERVING_PROOF.md) | `7eec475a1029e686c0d27fee5effc5c9908b0ae5` | `795cf04636cadcb064af6e7674c97a7ebf308679` | `89000fe418c1b30aab9c336a0678af89db2ff82d75fbd6b20c2b8898517d7dbe` |
| 10 | [R-B2 G2a](B1_R_B2_G2A_SERVING_PROOF.md) | `1e367f73c0a8b1ef34b30f9e7ae1efa78de26820` | `e9a78defcc4f15df8ea55efb10baf9f4abad101f` | `7fc367a0c7963935d858a26630e5477cb95d94db1d88f1182ddc121481497338` |
| 11 | [Task 6.6 G2b](B1_TASK_6_6_G2B_SERVING_PROOF.md) | `7dfa9ee9db9554ec6585d9469aa1b21c863d91a4` | `2e9690c2de0485c28dea3719bb15e079a172bdd6` | `6385ec4092474e0eae7f7e2c9effa3e238eb4694329adbe61aaede75464ae58f` |
| 12 | [Task 7.7 writer map](B1_R_C_TASK_7_7_G4_AND_WRITER_MAP.md) | `d2fe7fc9c4e9f6bdce07d930312b4fff174d2ab9` | `2352e42cd026afebb6146384945d0035a684e3c3` | `7b38fc6a07fce4179dfdc888c108abae06f68f134b4d2ccbfbe60dafc647cfad` |
| 13 | [Task 7.7 serving evidence](B1_R_C_TASK_7_7_SERVING_EVIDENCE.md) | `d2fe7fc9c4e9f6bdce07d930312b4fff174d2ab9` | `11fceb91cd7a788bddcde4716bc0493c22fa14ee` | `99fe1b8b33e86560432c45bd807945e0aadea943387b8b2cf2413251c6b723a0` |
| 14 | [Task 7.9 proof](B1_R_C_TASK_7_9_SERVING_PROOF.md) | `11ef5b7d8306f8edc322b6248319e9c8d0b2e87e` | `01334f7a7134115429370df7b59c9c1a8c3c67f6` | `27ba26a5b31862b7f355b23af16c05a441ff930caf08e51c7ea953d119dcd57b` |
| 15 | [Task 7.10 stop/go](B1_R_C_TASK_7_10_POST_DEPLOY_STOP_GO.md) | `62dfe938652372a35f0b87f940a892fba229fd37` | `e01ccd373272a9574959b652663d6e9c7a083bf2` | `52f9e507c063a234993530696f82cad03bdb7a46efec495b1604278ceab6edac` |

## 5. Invalidation and recollection rules

Recollect the complete Task 7.11 proof and obtain fresh independent review if any of the following
occurs:

- application source changes under `portfolio-service` or `api-gateway` from the candidate cut;
- an `asset_holdings` writer, route, controller, caller, seed/reset/initializer behavior, or its
  `Portfolio_Version`/identity-preservation disposition is added, changed, or made reachable;
- R-A signup provisioning behavior or its exact artifact identity changes;
- candidate source, JAR, manifest digest, serving revision, traffic, or exact artifact identity
  changes without fresh proof;
- a portfolio-delete caller changes or becomes reachable, or a database schema, foreign-key,
  cascade, or equivalent deletion-semantics change can affect `asset_holdings` deletion behavior,
  reopening the ungated cascade risk;
- a rollback below R-B3r is proposed, or the raised-floor policy/digest identity changes; or
- any referenced commit/blob stops resolving to its recorded canonical bytes.

## 6. Limitations and review-ready verdict

No fresh live query was run for Task 7.11.  Task 7.10 telemetry remains collector-reported,
historical evidence; its query semantics and results were not replayed here.  Task 7.9's single
authenticated SAME_STATE operation is not new write or race proof.  This proof makes no guarantee
for future artifacts, drift, routes/callers, or any rollback below R-B3r.

The independent final review accepts the exact reviewed candidate
`3501c53dc7159068781ed95e64fdcd0006e07157`: P11g-1 is established only for its transitional
lineage, while Writer_Convergence / P11g-2 is established as an exact-artifact, six-path universal
claim after all 15 accepted candidates have been dispositioned. Task 7.11 is locally complete.
Publication remains separate until explicitly authorized; no additional production operation or
rollback occurred during Task 7.11. This later ledger-closeout commit is not covered by Astra's
candidate verdict and requires its own Luna task review and final whole-branch Astra review.
