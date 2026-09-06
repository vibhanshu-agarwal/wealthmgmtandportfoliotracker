# B2 Wave 8 source handoff and publication packet

**Owner approval callout (2026-09-07):** The owner approved push, PR creation, and publication/cloud actions. No production operation has occurred. Merge remains conditional on required PR checks and review; production dispatch remains blocked by the missing Task 5.6 owner GO, B1 R-C NO-GO/open status, and the exact Task 9/10 deployment/live-proof packets and authorizations.

Baseline: `318f28592da6ab2e3bd66bc738aa68d374b180fa`
Integrated head before this correction: `db90fe744d388a62c57151c02bc051bbf888ce6d`

Tasks 8.1–8.7a are source-complete and independently accepted. Task 8.8b source/tooling is complete with 48 offline tests and pinned actionlint v1.7.12 passing using SHA-256 `8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8`. Coordinator-supplied consolidated evidence covers gateway unit/integration/real-chain/root graphs, Python workflow/proof suites, propagation checks, and diff validation; this worker's Python launcher verification is pending coordinator rerun because of a PyManager permission error.

Tasks 8.8 and 8.9 remain open. No workflow run, Azure deployment, serving revision/digest read-back, manifest comparison, or live production proof exists. Task 5.6 owner GO and B1 Wave 7 prerequisites remain open where applicable. Both frontend flags remain off and Wave 10 exposure is not claimed.

## Exact PR body

Master-plan impact: updated — B2

Wave 8 source and local verification are complete through the reviewed coordinator head. This change records the owner-approved product decisions, login-orchestrated reset implementation and tests, Azure deployment-evidence tooling, and the offline live-proof verifier. It does not claim deployment, serving read-back, manifest comparison, or live production proof; both frontend flags remain disabled.

Verification: gateway unit/integration and real-chain suites passed locally; workflow/proof suites passed; pinned actionlint v1.7.12 passed with the required checksum; master-plan propagation and diff checks are coordinator verification items.

Open gates: Task 8.8 deployment evidence, Task 8.9 live serving proof, Task 5.6 owner GO where applicable, and B1 Wave 7 prerequisites remain separately authorized and open.
