# B2 Task 8.9 — Decision 2 Production execute proof, 2026-09-15 (OPEN; candidate GO evidence pending fresh independent acceptance)

> **FRESH INDEPENDENT REVIEW IS REQUIRED. MERGE AND FEATURE EXPOSURE ARE NOT AUTHORIZED.** The owner
> authorized the Production execute proof and evidence publication. It later authorized two retained
> read-only queries, then—after review found that replay's skip app filter wrong—exactly one
> byte-identical replay of the retained `api-gateway` skip query. The combined evidence supports a
> candidate GO, but both earlier review rounds were rejections. **Task 8.9 is OPEN; evidence
> classification is candidate GO / retain serving revisions pending fresh independent acceptance.**
> This record does not authorize merging PR
> #278, enabling either Production flag, dispatching a deployment, or advancing past another Wave 10
> prerequisite.

## Decision

**Task 8.9 is OPEN; evidence classification is candidate GO / retain serving revisions pending
fresh independent acceptance. Rollback is not authorized.**

The owner-injected credential sequence ran from clean
`main@1f922a89643f5bb406dcdf471e8dc07a229960d6`. It used the real, unmodified 30-minute threshold,
proved a deliberate non-golden write, performed the traced Production login, observed golden state
at version `6`, and restored/verified golden state during unconditional cleanup. Both after-age and
final serving checks retained the attested identities:

- `api-gateway--0000081` at
  `sha256:090ad3ba4b7ada20bca71781d4a1ce3ac81029b8a23a8a1d180dce2cff540d09`
- `portfolio-service--0000096` at
  `sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126`

The execute verifier itself exited `1` / `class_2b`. The initiating cause was its Windows command
transport: Python resolved `az` to `az.cmd` and passed multiline KQL through the shim's parenthesized
`%*` expansion. The first newline terminated the intended Azure CLI invocation, so only
`ContainerAppConsoleLogs_CL` reached that invocation; the predicates, `--timespan`, and `-o json`
did not. The later UTF-8 decoder exception and `NoneType` parse error were downstream symptoms, not
the root cause. The raw manifest remains unchanged and visibly NON-GO. Its mandated next action was
`retry_historical_query`, not a fresh login or mutation. The source defect is tracked in the
[Windows `az.cmd` multiline-query backlog](../../todos/backlog/task-8-9-windows-az-cmd-multiline-query/README.md).

The first manual recovery summary reported a successful line-break-normalized replay, but retained
only a row count and hand-copied fields. It omitted the raw rows, full `Log_s`, effective argv,
timespan, stdout, and replay timestamps required by Task 8.9, so independent review correctly
rejected it as insufficient.

After fresh owner authorization, one bounded read-only recovery invoked the same installed
`azure.cli` module directly through its bundled `python.exe`, bypassing the defective `.cmd`
transport. It issued two multiline KQL queries once each, with the exact workspace and raw ISO UTC
timespan; there was no login, mutation, wake, deployment, cleanup, or retry. Its results were:

- success query: exactly one `demo_reset_succeeded` row from
  `portfolio-service--0000096`, trace `45097abc3f131da71b27d1b248b3e3c9`, with the complete
  `Log_s` payload showing resulting version `6`;
- purported skip query: zero rows, but the launcher had replaced only the event name in the success
  query and therefore left `ContainerAppName_s == 'portfolio-service'`. That query was not the
  retained gateway-owned skip query and its zero-row result supplies no skip evidence.

Fresh independent review rejected that mismatch. The owner then authorized exactly one additional
read-only query. It read `events.queries.skip` from the immutable raw execute evidence, verified an
ordinal match to the retained `api-gateway` query, and invoked it once using the same workspace,
trace, raw UTC timespan, and direct transport. Raw process stdout/stderr were copied byte-for-byte
from their streams without `Tee-Object`; the query exited `0`, returned `[]`, and recorded zero
retries. Together with the accepted success row, this establishes `a_success_only`.

Cleanup also emitted a `demo_reset_succeeded version=6` event inside the retained UTC window. It is
not the captured login event because cleanup used a different trace; the trace-id predicate is the
exclusion, not time.

The tracked `classify_task8_9` function maps the captured `a_success_only` outcome plus the
identity-checked golden observation at the same version (`6`) to `class=go`,
`action=retain_serving_revision`, `rationale=trace-correlated reset proven`, with rollback false.
This is a candidate classification until fresh independent review accepts the corrected evidence.
The [supplemental recovery artifact](run-b-decision2-class2b-recovery-20260915.json) records the
inputs, hashes, full-payload result, classifier reconstruction, and pending-review gate.

## Preserved artifacts

| Artifact | SHA-256 | Role |
|---|---|---|
| [`run-b-decision2-preflight-20260915.json`](run-b-decision2-preflight-20260915.json) | `2f4a5fd5105506ef1701bb78afe4841df0e0dc602ce1940bdaaac062ea639b8b` | Same-sequence full preflight: exit `0`, 15 nonmutating operations, `preflight_passed`, `go:null` |
| [`run-b-decision2-execute-raw-20260915.json`](run-b-decision2-execute-raw-20260915.json) | `7f236f98c506aa652fd7cffeb43df2fa43e92afee42f890eb21f05bd7f3968be` | Unchanged raw execute output: exit `1`, Class 2b, cleanup golden; retained rather than rewritten |
| [`run-b-decision2-operator-transcript-20260915.txt`](run-b-decision2-operator-transcript-20260915.txt) | `f4aa2c895485c2b4f106c828c805cacc3b3f776acf1e2a0f5b892b96f26f4b83` | Raw operator transcript, including the decoder exception and local Docker hygiene |
| [`run-b-decision2-class2b-recovery-20260915.json`](run-b-decision2-class2b-recovery-20260915.json) | `7bf7b1ed71e341d15ef841d4ca73a0c50016d1152e0b71aa24be3b9d39725846` | Corrected recovery manifest and derived candidate adjudication; fresh independent acceptance pending |
| [`run-b-decision2-class2b-replay-operator-transcript-20260915.txt`](run-b-decision2-class2b-replay-operator-transcript-20260915.txt) | `674cd808ebb54824bf90dc45996b901e32e511332a1079deb7edc4b0ef383fd9` | Superseded initial replay transcript with original CRLF preserved: accepted success query, but skip query incorrectly targeted `portfolio-service`; two invocations and zero retries |
| [`run-b-decision2-class2b-replay-success-stdout-20260915.json`](run-b-decision2-class2b-replay-success-stdout-20260915.json) | `9ddcf5bfdad2c2c51e82851e86a905a778621977a56d4354d048ae76db4ea776` | Initial `Tee-Object` text capture of success-query stdout: one complete row including full `Log_s`; content accepted, not claimed byte-raw |
| [`run-b-decision2-class2b-replay-success-stderr-20260915.txt`](run-b-decision2-class2b-replay-success-stderr-20260915.txt) | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | Initial `Tee-Object` text capture of success-query stderr: empty; not claimed byte-raw |
| [`run-b-decision2-class2b-replay-skip-stdout-20260915.json`](run-b-decision2-class2b-replay-skip-stdout-20260915.json) | `a5338d955b09046ec0b16f3a9625b7955c763aae07dc722e474e6078745f932f` | Superseded wrong-app skip stdout: `[]`; not evidence about `api-gateway` |
| [`run-b-decision2-class2b-replay-skip-stderr-20260915.txt`](run-b-decision2-class2b-replay-skip-stderr-20260915.txt) | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | Superseded wrong-app skip stderr: empty |
| [`run-b-decision2-class2b-replay-r2-gateway-skip-operator-transcript-20260915.txt`](run-b-decision2-class2b-replay-r2-gateway-skip-operator-transcript-20260915.txt) | `af8aa122c1136bfa155d734f234c326ec0c94317bb7816a24d2464b0e353f297` | Sanitized correction transcript with CRLF preserved: retained query source, ordinal comparison, exact argv/timespan/timestamps, one invocation, zero retries, and raw-stream capture method |
| [`run-b-decision2-class2b-replay-r2-gateway-skip-stdout-20260915.json`](run-b-decision2-class2b-replay-r2-gateway-skip-stdout-20260915.json) | `a5338d955b09046ec0b16f3a9625b7955c763aae07dc722e474e6078745f932f` | Raw retained `api-gateway` skip-query stdout: `[]` |
| [`run-b-decision2-class2b-replay-r2-gateway-skip-stderr-20260915.txt`](run-b-decision2-class2b-replay-r2-gateway-skip-stderr-20260915.txt) | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | Raw retained `api-gateway` skip-query stderr: empty |
| [`run-b-decision2-owner-authorizations-20260915.md`](run-b-decision2-owner-authorizations-20260915.md) | `92cfdcbd761d261d51c0aea1a0f3ee1d21b395799acfdbd1494ca637ae1b6ce7` | Owner Decision 2, re-arms, both replay authorizations, exact correction quote/time, and continuing no-merge/no-exposure boundaries |

The copied preflight, raw execute output, original operator transcript, and all replay streams are
protected with `.gitattributes -text`. Both original replay transcripts and their raw streams are
preserved privately at `C:\t89`. Each published transcript changes only PowerShell header host
identity and user-specific host paths; both published transcripts preserve the originals' CRLF line
endings. Query argv, timestamps, stdout, stderr hashes, and results are unchanged.

## Production sequence facts

| Gate | Evidence |
|---|---|
| Authorization | Owner approved Decision 2 execute proof and evidence publication, later re-armed the bounded sequence after local pre-request harness stops, authorized two read-only replay queries, and after their wrong-app skip was rejected explicitly authorized exactly one retained `api-gateway` correction query; see the [authorization chronology](run-b-decision2-owner-authorizations-20260915.md) |
| Activation | Two probes: `503/OUT_OF_SERVICE`, then exact `200/UP`; one ready replica resolved automatically |
| Preflight | Passed; subscription, workspace, RBAC, exact revisions/digests, timeouts, idle threshold, provider, ACR and oracle checks matched |
| Threshold | No override; `ageWaitSeconds=1800.001` |
| Setup | Before version `4`; deliberate non-golden write advanced to version `5` |
| Traced login | HTTP `200`; trace `45097abc3f131da71b27d1b248b3e3c9` |
| Observation | Identity verified; golden; post-login version `6` |
| Trace recovery | Initial replay: two invocations and zero retries; its success query is accepted, its wrong-app skip is rejected. Correction replay: one byte-identical retained `api-gateway` query, one invocation, zero retries, exit `0`, raw stdout `[]`, raw stderr empty. Combined result: one full-payload success event at version `6` and zero gateway skip events |
| Cleanup | Armed; first reset returned `200`; no conflict; independent post-cleanup read golden at version `6`. Cleanup was inside the query window but excluded from login evidence by its different trace ID |
| Serving | After-age and final revalidation matched both attested revisions/digests; provider remained Azure |
| Classification | Candidate `go` / `retain_serving_revision`; rollback false; binding Task 8.9 status remains OPEN pending fresh independent acceptance |

## Earlier stopped starts in the same operating session

Three earlier starts remain non-claims and do not supply the GO:

1. The first execute wrapper stopped after preflight and one token-mint login because an extra local
   guard incorrectly rejected the expected `ro:true` demo claim. No execute verifier started.
2. The first re-arm stopped before any Production request when Windows PowerShell treated a missing
   local Docker image as a terminating error.
3. The next re-arm performed read-only Azure checks but stopped before the gateway wake when Windows
   PowerShell promoted a normal Azure extension warning to a terminating outer error.

The owner explicitly authorized the corrected re-arms; the exact chronology is now published. None
of these stopped starts supplies the candidate decision. It rests only on the final complete
Production sequence and the separately authorized, fully captured Class 2b historical-query replay.

## Security and hygiene

- The demo password and both minted tokens were never printed or written to evidence.
- A scan found no JWT-like value, bearer literal, authorization value, or password in either replay
  transcript or any raw stream.
- `Authorization` and `password` appear only as redacted request field/header names in the verifier's
  operation metadata; no value is present.
- The server cleared `TASK8_9_ACCESS_TOKEN` and `TASK8_9_DEMO_PASSWORD`, logged out of ACR, and
  removed only the two exact digest-qualified images that were absent before the sequence.
- The verifier's unconditional cleanup restored and independently verified the exact golden state.

## Remaining boundary

Task 8.9 is **OPEN; evidence classification is candidate GO / retain serving revisions pending fresh
independent acceptance**, and it continues to block Demo readiness until a fresh reviewer accepts
the corrected evidence at the exact PR head. If accepted, the candidate decision becomes GO without
another login or mutation. Wave 10 remains closed on its other explicit prerequisites, owner exposure
decision, new flag-bearing build/deployment, and Production E2E. This publication changes neither
feature flag and does not authorize merge.
