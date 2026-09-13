# Backlog: Task 8.9 wake-preflight hardening follow-ups

**Status:** Open — non-blocking implementation hardening, recorded after PR #268 merged on
2026-09-13 at `main@80b881b5c461e1bebb210ebe1db4b911cf6a336d`.
**Owner:** unassigned
**Tracked in:** [PR #268](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/268)
and the [independent Fable review](../../../superpowers/plans/fable-review-task-8-9-postmerge-docs-20260913.md).
All three items are non-blocking; this backlog does not block Task 8.9, Run A attempt 3, or Wave
10.2. Task 8.9 remains independently blocked on its owner-authorized live serving proof.

---

## Scope

PR #268's merged wrapper is a preflight-only cold-start guard. It may issue no more than five
fixed direct health probes, stops at the first exact `200`, and invokes verifier mode `preflight`
only. The accepted findings below do not change that bound or authorize any live operation.

## Follow-ups

1. **Minor — non-blocking: make the curl stub accept a temporary-folder path ending in two trailing
   backslashes.** The
   current test stub refuses that spelling. This affects the stub's argument handling only; the
   production wrapper fails closed and no live wrapper defect was found. See
   [`stub_curl.cmd` lines 85–86](../../../../scripts/tests/stub_curl.cmd#L85-L86).
2. **Minor — non-blocking: add a positive structural allowlist for network-capable string-constant
   command heads.** The current checks already positively allowlist variable-headed commands, but
   string-constant command heads are governed only by the denylist and do not enumerate every
   permitted native command path or module-qualified cmdlet. The accepted head contains none of
   those additional paths; the follow-up should make that invariant explicit. See the existing
   [`$allowedHeads` allowlist at line 1124](../../../../scripts/tests/test_run_task_8_9_preflight.ps1#L1124),
   its [variable-head enforcement at line 1127](../../../../scripts/tests/test_run_task_8_9_preflight.ps1#L1127),
   and the [string-constant denylist check at line 1131](../../../../scripts/tests/test_run_task_8_9_preflight.ps1#L1131).
3. **Minor — non-blocking: correct the literal-asterisk comment.** One comment inaccurately attributes preservation of
   literal `'*'` to PowerShell quoting. Correct the explanation without changing invocation
   behavior. See
   [`run_task_8_9_preflight.ps1` lines 188–189](../../../../scripts/run_task_8_9_preflight.ps1#L188-L189).

## Acceptance boundary

Any future change belongs in a separately reviewed source/test/documentation change. It must retain
the maximum-five-probe cap, the exact `200` success rule, fail-closed behavior, and preflight-only
verifier mode. It does not confer authority for a Production wake, credentials, execute mode,
portfolio mutation, cleanup, flag change, deployment, publication, merge, or Task 8.9 completion.

## Non-claims

- This backlog entry does not claim that Run A has run or passed.
- It does not replace the separate owner approval required for the one bounded Production activation
  sequence.
- It does not alter the requirement for a real 30-minute threshold, a trace-correlated Production
  login, deliberately non-golden-to-golden serving proof, manifest, and cleanup before Task 8.9 can
  be complete.
