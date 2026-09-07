# B2 Wave 8 source handoff and publication packet

**Owner approval callout (2026-09-07):** The owner approved push, PR creation, and publication/cloud actions. No production operation has occurred. Merge remains conditional on required PR checks and review; production dispatch remains blocked by the missing Task 5.6 owner GO, B1 R-C NO-GO/open status, and the exact Task 9/10 deployment/live-proof packets and authorizations.

Baseline: `318f28592da6ab2e3bd66bc738aa68d374b180fa`
Reviewed source candidate: `caf0f648395c92f73682f0ba0f26cca7fbcc7104` (not merged or deployed). Subsequent publication-packet commits change only this handoff; the publication branch includes those documentation updates.

Tasks 8.1–8.7a are source-complete and independently accepted. Task 8.8b source/tooling is complete with 48 offline tests and pinned actionlint v1.7.12 passing using SHA-256 `8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8`. Coordinator evidence covers gateway unit/integration/real-chain/root graphs, Python workflow/proof suites, propagation checks, and diff validation; the worker launcher was blocked, but coordinator subsequently passed all six suites/checks.

Tasks 8.8 and 8.9 remain open. No workflow run, Azure deployment, serving revision/digest read-back, manifest comparison, or live production proof exists. Task 5.6 owner GO and B1 Wave 7 prerequisites remain open where applicable. Both frontend flags remain off and Wave 10 exposure is not claimed.

## Exact PR body

Master-plan impact: updated — B2

Wave 8 source and local verification are complete through the reviewed coordinator head. This change records the owner-approved product decisions, login-orchestrated reset implementation and tests, Azure deployment-evidence tooling, and the offline live-proof verifier. It does not claim deployment, serving read-back, manifest comparison, or live production proof; both frontend flags remain disabled.

Verification: gateway unit/integration and real-chain suites passed locally; 73 verifier and 178 combined Python tests passed; pinned actionlint v1.7.12 passed with the required checksum; master-plan propagation and diff checks passed locally.

Open gates: Task 8.8 deployment evidence, Task 8.9 live serving proof, Task 5.6 owner GO where applicable, and B1 Wave 7 prerequisites remain separately authorized and open.

## Exact review evidence

RED/GREEN/mutation evidence is preserved in the lane reviews and coordinator ledger: gateway transport/orchestration RED cases were converted to GREEN; strict digest/job equality mutation was killed and restored; workflow artifact/mode mutations were killed; verifier diagnostic, deadline, correlation, and current-attempt mutations were killed across exactly three fix rounds. Historical fresh execution at `db90fe74` passed 316 gateway unit, 193 gateway integration, 4 real-chain, 37 insight, 37 market-data, and 208 portfolio tests, with root `integrationTest` exit 0. At the reviewed source candidate, Gradle verification commands exited 0 with all tasks UP-TO-DATE. Coordinator checks passed: Python suites 24+14+10+73+24+33 (178 total), identity guard PASS, 36-path propagation guard PASS, actionlint v1.7.12 with checksum/invocation PASS, and `git diff --check` PASS.

The publication body above contains exactly one `Master-plan impact: updated — B2` declaration. These are source/local observations only; no workflow/deployment/serving/live proof is claimed.

## Commit map (Tasks 0–8)

| Task | Commits |
|---|---|
| 0 | `6396f7aa..157df39b` |
| 1 | `bea208c0`, `a23ccdc8` |
| 2 | `00601c20..bc18e92e` |
| 3 | `0b2779be`, `1e34a35a` |
| 4 | `c03b208d`, `7d6305b1`, `db90fe74` |
| 5 | `45784950..eadbb15d` |
| 6 | `b59cf26c` |
| 7 | `f21671eb..2bfddf87` |
| 8 | `c17bb0a8`, `778c1369`, `ad180d69`, cleanup `7e6593b`, `400b0e0`, `cfdec6c9`, `35e4584d`, final-review fix `2d73cbd0`, nullable follow-up `caf0f648` |

## RED/GREEN, mutation, and review rounds

Task 0: 33/33 propagation tests and exact guard/diff pass after five review-fix rounds; this was documentation reconciliation with no mutation claim. Task 1: shared topology was 4/4 structural RED then 4/4 GREEN; the Gradle dry-run collected `portfolio-service -> compileWave8IntegrationTestJava -> wave8IntegrationTest -> integrationTest`; real compilation passed; removing the `integrationTest` dependency failed the intended oracle and exact restoration returned GREEN; Astra accepted after three review rounds. Task 2: mutations selecting the first portfolio, changing strict `>` to `>=`, rereading before reset, and deriving attachment evidence from provider configuration were each killed/restored; transport and presence evidence were green and independently accepted. Task 3: review found body aggregation/decoding misclassified as rollback-bearing orchestration error; the fix made it `eligibility_shape_failure`; seven mutations covering provenance/auth/fail-open boundaries were killed/restored; 41 focused and 316 full unit tests passed in one fix round. Task 4: each of four named real-chain cases was individually mutated and failed the root carrier; removing the observation registry and using `server.port` instead of `local.server.port` failed; injecting `X-Origin-Verify` caused all four wire assertions to fail while diagnostics stayed false/null; all were restored after one fix round; 316/193/4 plus root counts passed. Task 5: weakened refresh-Job equality was killed by `test_market_data_job_must_equal_app_expected_image`; restored blob `d35819282eddb1294ff0934433a3bd4eba9a07af` and 24/24 GREEN; independently accepted. Task 6: seven in-memory workflow mutations covering attempt markers, artifact namespaces/required files, mode gates, digest validation, and immutable App/Job deployment were killed/restored; 48 focused tests passed and were independently accepted. Task 7: exactly three fix rounds; critical fail-closed outcomes included current-attempt marker mismatch, serving-revision drift, query-pair/event discrimination, cleanup deadline/409 permanence, credential redaction, and later distinct duplicate emissions; 62 verifier + 24 identity tests and identity guard PASS. Final review found three Important verifier defects; `2d73cbd0` addressed revision identity/ingress binding, absolute HTTP deadline, minted-token redaction, and two docs fixes. Scoped rereview found the real nullable `customDomains` regression; owner authorized a narrow TDD cycle and `caf0f648` normalized null/missing/empty values while rejecting malformed non-lists. Fresh independent review returned SPEC PASS / QUALITY APPROVED / READY TO PUBLISH with no Critical/Important findings. Current checks are actionlint/checksum PASS, 73 verifier and 178 combined Python tests, identity guard PASS, 36-path propagation guard PASS, exact-head Gradle exit 0/UP-TO-DATE, and diff check PASS.
