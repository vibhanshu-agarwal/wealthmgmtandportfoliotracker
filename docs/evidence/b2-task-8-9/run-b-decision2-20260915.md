# B2 Task 8.9 — Decision 2 Production execute proof, 2026-09-15 (GO after Class 2b recovery)

> **MERGE AND FEATURE EXPOSURE ARE NOT AUTHORIZED.** The owner authorized the Production execute
> proof and evidence publication. This record closes Task 8.9 technically; it does not authorize
> merging PR #278, enabling either Production flag, dispatching a deployment, or advancing past any
> other Wave 10 prerequisite.

## Decision

**Task 8.9 is GO and complete. Retain the serving revisions; rollback is not authorized.**

The owner-injected credential sequence ran from clean
`main@1f922a89643f5bb406dcdf471e8dc07a229960d6`. It used the real, unmodified 30-minute threshold,
proved a deliberate non-golden write, performed the traced Production login, observed golden state
at version `6`, and restored/verified golden state during unconditional cleanup. Both after-age and
final serving checks retained the attested identities:

- `api-gateway--0000081` at
  `sha256:090ad3ba4b7ada20bca71781d4a1ce3ac81029b8a23a8a1d180dce2cff540d09`
- `portfolio-service--0000096` at
  `sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126`

The execute verifier itself exited `1` / `class_2b` because a background reader encountered a
Windows-encoded byte while decoding an Azure CLI child stream as UTF-8. The raw manifest therefore
remains unchanged and visibly NON-GO. Its mandated next action was `retry_historical_query`, not a
fresh login or mutation.

That read-only recovery replayed the retained trace and UTC window. Because Windows resolves `az`
to `az.cmd`, the retained multiline KQL was transported with line breaks replaced by spaces; all
non-whitespace query content, predicates, trace ID, event names, projection, ordering, workspace,
and raw ISO UTC timespan remained unchanged. The service-side results were:

- success query: exactly one `demo_reset_succeeded` row from
  `portfolio-service--0000096`, trace `45097abc3f131da71b27d1b248b3e3c9`, resulting version `6`;
- skip query: zero `demo_reset_self_call_skipped` rows.

The tracked `classify_task8_9` function maps that recovered `a_success_only` outcome plus the
identity-checked golden observation at the same version (`6`) to `class=go`,
`action=retain_serving_revision`, `rationale=trace-correlated reset proven`, with rollback false.
The [supplemental recovery artifact](run-b-decision2-class2b-recovery-20260915.json) records the
inputs, hashes, recovery results, classifier output, and final-gate evaluation.

## Preserved artifacts

| Artifact | SHA-256 | Role |
|---|---|---|
| [`run-b-decision2-preflight-20260915.json`](run-b-decision2-preflight-20260915.json) | `2f4a5fd5105506ef1701bb78afe4841df0e0dc602ce1940bdaaac062ea639b8b` | Same-sequence full preflight: exit `0`, 15 nonmutating operations, `preflight_passed`, `go:null` |
| [`run-b-decision2-execute-raw-20260915.json`](run-b-decision2-execute-raw-20260915.json) | `7f236f98c506aa652fd7cffeb43df2fa43e92afee42f890eb21f05bd7f3968be` | Unchanged raw execute output: exit `1`, Class 2b, cleanup golden; retained rather than rewritten |
| [`run-b-decision2-operator-transcript-20260915.txt`](run-b-decision2-operator-transcript-20260915.txt) | `f4aa2c895485c2b4f106c828c805cacc3b3f776acf1e2a0f5b892b96f26f4b83` | Raw operator transcript, including the decoder exception and local Docker hygiene |
| [`run-b-decision2-class2b-recovery-20260915.json`](run-b-decision2-class2b-recovery-20260915.json) | `69eb63480ce7c00ba2fe87fce50ee52671b9623153c2617a81e0b739a5b19742` | Supplemental historical-query recovery and final adjudication |

The copied preflight, raw execute output, and transcript are protected with `.gitattributes -text`
and were byte-hash checked against their preserved `C:\t89` originals before staging.

## Production sequence facts

| Gate | Evidence |
|---|---|
| Authorization | Owner approved Decision 2 execute proof and evidence publication; later re-armed the bounded sequence after local pre-request harness stops |
| Activation | Two probes: `503/OUT_OF_SERVICE`, then exact `200/UP`; one ready replica resolved automatically |
| Preflight | Passed; subscription, workspace, RBAC, exact revisions/digests, timeouts, idle threshold, provider, ACR and oracle checks matched |
| Threshold | No override; `ageWaitSeconds=1800.001` |
| Setup | Before version `4`; deliberate non-golden write advanced to version `5` |
| Traced login | HTTP `200`; trace `45097abc3f131da71b27d1b248b3e3c9` |
| Observation | Identity verified; golden; post-login version `6` |
| Trace recovery | One success event at version `6`; zero skip events |
| Cleanup | Armed; first reset returned `200`; no conflict; independent post-cleanup read golden at version `6` |
| Serving | After-age and final revalidation matched both attested revisions/digests; provider remained Azure |
| Classification | `go` / `retain_serving_revision`; rollback false |

## Earlier stopped starts in the same operating session

Three earlier starts remain non-claims and do not supply the GO:

1. The first execute wrapper stopped after preflight and one token-mint login because an extra local
   guard incorrectly rejected the expected `ro:true` demo claim. No execute verifier started.
2. The first re-arm stopped before any Production request when Windows PowerShell treated a missing
   local Docker image as a terminating error.
3. The next re-arm performed read-only Azure checks but stopped before the gateway wake when Windows
   PowerShell promoted a normal Azure extension warning to a terminating outer error.

The owner explicitly authorized the corrected retry/re-arms. None of these starts is presented as
Task 8.9 progress. The GO rests only on the final complete Production sequence and its retained
Class 2b historical-query recovery.

## Security and hygiene

- The demo password and both minted tokens were never printed or written to evidence.
- A scan found no JWT-like value and no bearer literal in the three raw artifacts.
- `Authorization` and `password` appear only as redacted request field/header names in the verifier's
  operation metadata; no value is present.
- The server cleared `TASK8_9_ACCESS_TOKEN` and `TASK8_9_DEMO_PASSWORD`, logged out of ACR, and
  removed only the two exact digest-qualified images that were absent before the sequence.
- The verifier's unconditional cleanup restored and independently verified the exact golden state.

## Remaining boundary

Task 8.9 no longer blocks Demo readiness. Wave 10 remains closed on its other explicit prerequisites,
owner exposure decision, new flag-bearing build/deployment, and Production E2E. This publication does
not change either feature flag and does not authorize merge.
