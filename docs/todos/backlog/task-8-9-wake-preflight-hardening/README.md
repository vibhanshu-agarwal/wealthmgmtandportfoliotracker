# Backlog: Task 8.9 wake-preflight hardening follow-ups

**Status:** Open — non-blocking implementation hardening, recorded after PR #268 merged on
2026-09-13 at `main@80b881b5c461e1bebb210ebe1db4b911cf6a336d`.
**Updated:** 2026-09-15 with three exact-head review findings retained after PR #275 merged at
`main@8337d7e8b982245156088719d01f49b3ad1c1be9`.
**Owner:** unassigned
**Tracked in:** [PR #268](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/268)
and its [independent Fable review](../../../superpowers/plans/fable-review-task-8-9-postmerge-docs-20260913.md),
[PR #273](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/273), and
[PR #275](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/275).
All items are non-blocking; this backlog does not block Task 8.9 or authorize or block a future Run
A attempt. Task 8.9 remains independently blocked on its owner-authorized live serving proof.

---

## Scope

The governing activation policy supersedes PR #268's activation limits. The preflight-only
cold-start guard may issue no more than six fixed direct health probes, stops at the first exact
`200`, and invokes verifier mode `preflight` only. The accepted findings below do not change that
bound or authorize any live operation.

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
4. **Minor — non-blocking: make `$verifierArgs` extraction structurally robust.** The Python drift
   guard currently finds the PowerShell array with a non-greedy textual regex. A future multiline
   parenthesized element could terminate that match early. Existing timeout extraction would then
   fail closed, but the idle-override absence pin could inspect a truncated prefix and silently miss
   an override later in the real argument vector. Replace or harden the extraction so nested or
   multiline parentheses cannot truncate it, and add a negative fixture proving both the timeout
   literals and absence pins inspect the complete array or fail closed. See
   [`_wrapper_verifier_args_block_text`](../../../../scripts/tests/test_verify_demo_reset_azure.py#L2441).
5. **Minor — non-blocking: replace the stale `$verifierArgs` line citation.** Its docstring still
   cites wrapper lines `~L1007-1043`; at the merged PR #275 head the array begins at line 1051 and
   its invocation is at line 1088. Prefer symbolic anchors over another approximate range so later
   insertions cannot silently stale the explanation. See the
   [stale docstring](../../../../scripts/tests/test_verify_demo_reset_azure.py#L2443).
6. **Minor — non-blocking: correct the verifier comparison paraphrase.** The wrapper's ordinal-
   comparison rationale quotes the Python condition as `if observed != expected`, while the
   verifier actually compares `effective != expected`. The intended semantic contrast is correct,
   but the quoted identifier is not. Correct the comment without changing behavior. See the
   [wrapper rationale](../../../../scripts/run_task_8_9_preflight.ps1#L803) and
   [verifier comparison](../../../../scripts/verify_demo_reset_azure.py#L754).

## Acceptance boundary

Any future change belongs in a separately reviewed source/test/documentation change. It must retain
the maximum-six-probe cap, the exact `200` success rule, fail-closed behavior, and preflight-only
verifier mode. It does not confer authority for a Production wake, credentials, execute mode,
portfolio mutation, cleanup, flag change, deployment, publication, merge, or Task 8.9 completion.

## Non-claims

- This backlog entry does not claim that any Run A attempt passed or advanced Task 8.9.
- It does not authorize Run A attempt 4, which remains unstarted, or replace the separate owner
  approval required for any future bounded Production activation sequence.
- It does not alter the requirement for a real 30-minute threshold, a trace-correlated Production
  login, deliberately non-golden-to-golden serving proof, manifest, and cleanup before Task 8.9 can
  be complete.
