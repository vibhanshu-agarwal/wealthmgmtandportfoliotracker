# B2 Task 8.9 — Fable acceptance and authorized record reconciliation

> **QUICK INDEPENDENT CHECK OF THE NEW DOCUMENTATION HEAD IS REQUIRED BEFORE A MERGE DECISION.**
> Fable accepted the Production-proof evidence at `8544d722433108f0b71a164675271d2f81c7a6a8`.
> Task 8.9's Azure live proof is COMPLETE / GO on that accepted evidence. This record does not
> represent acceptance of its own new publication head. Merge, flags, exposure, deployment,
> rollback, and further Production access remain unauthorized.

## Acceptance provenance

The owner relayed a fresh independent Fable review in this task at session response-item timestamp
`2026-09-15T06:29:29.491Z`: **ACCEPT; 0 Critical, 0 Important, 8 Minor**, against exact PR #278
head `8544d722433108f0b71a164675271d2f81c7a6a8`. The owner reported that the reviewer worked
read-only without Production access and that the orchestrator independently spot-checked the
retained gateway query's exact argv.

This is an owner-relayed acceptance record, not a verbatim reviewer transcript. The named full
handoff, `handoff-codex-pr278-fable-review-r3-8544d722-ACCEPT.md`, was not available in the
publication worktree; the owner's eight-item summary is the source for this bounded reconciliation.
Session timestamps are retained local message metadata, not independently attested server-side
chat send/receive times.

The owner then authorized this documentation/evidence commit and push with the exact message
**“Authorized. Please go ahead.”**, recorded at `2026-09-15T06:31:17.563Z`. That approval covers
recording acceptance, status reconciliation, the eight Minor tidy-ups, and publication to the
existing PR. It does not authorize a Production operation or merge.

## Accepted result

- The accepted success query preserves the retained portfolio-service KQL and returns one full
  `demo_reset_succeeded version=6` row for login trace `45097abc3f131da71b27d1b248b3e3c9`.
- The corrected skip argv preserves the retained api-gateway KQL, workspace, original UTC bounds,
  and `-o json`; its query SHA-256 is
  `458a34237a22d2f860eba9afae7b209279580d1781460ccbfe70cf5337fdcf40` and its result is `[]`.
- The owner reported independent confirmation that the gateway's skip log includes the trace in
  a query-matchable form, both services use the same workspace, and the success row supplies the
  positive control.
- The tracked parser/classifier reproduce `a_success_only`, `go` /
  `retain_serving_revision`, resolved true, rollback false.
- The raw execute manifest remains unchanged Class 2b / NON-GO. GO is the independently accepted
  supplemental adjudication, not a rewrite of that original verifier output.

## Eight Minor corrections

| Minor | Record correction |
|---|---|
| Launchers absent/unhashed | The two exact executed launcher sources are archived as `.ps1.txt` evidence, linked and SHA-256 bound below and in the recovery manifest. Neither is executed by this reconciliation. |
| First replay approval lacked quote/time | The chronology quotes “Approve, please go ahead.” and records session timestamp `2026-09-15T02:31:57.317Z`, distinct from its first invocation at `02:38:48.9658764Z`. |
| Gateway approval time was launcher clock | The owner-message metadata is `2026-09-15T03:50:59.326Z`; the transcript's `03:54:50.0510297Z` label is explained as launcher-clock authority recording, not approval receipt. The transcript/source remain unchanged. |
| Two gate substitutions unnamed | The derived reconstruction names `verdict.errors` transport-error supersession and `keyAlignment.internalApiKeyProven` derivation from the accepted traced success. No raw field is modified. |
| Attempt-4 stale OPEN / NON-GO | The preflight record preserves its historical non-GO scope but identifies the later accepted Decision 2 COMPLETE / GO status at `8544d722`. |
| Two uncaptured queries missing from total | The seven-command execute/recovery ledger below includes both uncaptured manual reads and separates the two accepted causal inputs from rejected/non-probative operations. |
| Query-content flag too broad | Preservation flags apply only to the accepted success query and corrected gateway skip query. The initial wrong-app skip explicitly changed the retained query content. |
| First transcript original hash uncited | The recovery manifest binds the published first transcript and its retained `C:\t89` original separately; their four header redactions and preserved CRLF remain explicit. |

## Query-accounting scope

This ledger counts Log Analytics `query` invocations in the final Decision 2 **execute and
subsequent recovery** only. Separate Run A, Decision 2 preflight/rehearsal queries and workspace
metadata reads are outside this scope.

| Stage | Invocations | Accepted causal input? |
|---|---:|---|
| Execute RBAC `print task8_9_rbac_probe=1` | 1 | No; access probe only |
| Execute malformed multiline success query | 1 | No; predicates/timespan/output arguments lost; skip never dispatched after TypeError |
| Earlier uncaptured manual success/skip reads | 2 | No; insufficient raw capture; rejected recovery summary |
| First captured replay: retained success and wrong-app skip | 2 | Success only; wrong-app skip non-probative |
| Corrected retained gateway skip replay | 1 | Yes; zero gateway rows |
| **Total execute/recovery invocations** | **7** | **Two accepted causal queries** |

Recovery-only count is **5** (2 uncaptured + 2 initial captured + 1 correction). Captured recovery
count is **3**, including the rejected wrong-app operation. The outcome uses **2** accepted queries,
not the rejected/uncaptured results. Each authorized captured batch records zero retries; the
separately authorized correction is not relabeled as a retry of the earlier batch. No new query
was issued for this reconciliation.

## Archived source and original bindings

| Artifact | SHA-256 | Scope |
|---|---|---|
| [Initial replay launcher](run-b-decision2-initial-replay-launcher-20260915.ps1.txt) | `1a3077feba7b52a4d71ceff88f56f6c99b114431551cd2f99dea3af2d3791c1b` | Byte-identical retained `task89_capture_replay_simple.ps1`; includes the rejected wrong-app construction and original Tee-Object capture |
| [Gateway correction launcher](run-b-decision2-gateway-skip-replay-launcher-20260915.ps1.txt) | `a812dafb003dd919bc0f5d23c9b0df939e02e67335427038bfa19c3b40bc5ef7` | Byte-identical retained `task89_gateway_skip_replay.ps1`; retained-query check and direct BaseStream capture |
| [Published first replay transcript](run-b-decision2-class2b-replay-operator-transcript-20260915.txt) | `674cd808ebb54824bf90dc45996b901e32e511332a1079deb7edc4b0ef383fd9` | Four host-header redactions, original CRLF preserved |
| `C:\t89\run-b-decision2-class2b-replay-operator-20260915.txt` | `3ff181a1374fbdc58ed1b78a2a6564873bfaa88efbdb04572ac9f83108aa1756` | Private unredacted original; not newly published |

The archived launchers are historical evidence, not production-ready tooling, and confer no
execution authority. Their original defects, labels, and lack of a process timeout are preserved,
not silently repaired. Runtime/verifier implementation backlogs remain open.

## Current boundary

Task 8.9 Azure live proof is **COMPLETE / GO at accepted evidence head `8544d722`**; retain
`api-gateway--0000081` and `portfolio-service--0000096`, with no rollback. This new
documentation-only head still requires the quick independent check. PR #278 remains unmerged.
Wave 10 still requires its other prerequisites, owner exposure approval, flag-bearing build/deploy,
and Production E2E. Task 8.8a remains AWS-only. Both Production flags remain off.
