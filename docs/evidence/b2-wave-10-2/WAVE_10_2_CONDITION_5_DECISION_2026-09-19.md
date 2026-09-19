# B2 Wave 10.2 — condition 5 decision record (owner approval 2026-09-19)

**Decision:** **Condition 5 is split into 5a (Go precondition) and 5b (Step B exit criterion).** Owner
approval: 2026-09-19. Scope of that approval: final review and PR preparation only; no production action.
This is a specification-governance change, not a GO. It does not mark Wave 10.2 complete, satisfy either
part of condition 5, authorize Step B, change either feature flag, deploy anything, or perform any live
read. Wave 10.2 remains `[ ]`, Wave 9 Step A remains API-only gate-credit evidence, and Task 8.9 remains
COMPLETE / GO and is not reopened.

## The approval

Owner approval: **2026-09-19**. Scope: **final review and PR preparation only; no production action.**

The amendment and verifier under review implement the choices below. They were put forward on 2026-09-18
("My recommendation is", in reply to the condition-5 decision memo, snapshot `main@2bec6ac0`, post-PR
#290); that message is their source, not the approval.

```text
1 approve, 2 all five routes, 3a yes, 3b yes
```

| # | Question | Choice |
|---|---|---|
| 1 | Split of Wave 10.2 condition 5 and the coordinated `tasks.md` + master-plan amendment | **Approve** |
| 2 | Coverage of 5b | **All five Wave 9 routes (9.1–9.5)**, including the 9.2 browser save followed by a reset from that non-golden state, read-only legs first |
| 3a | Draft the amendment | **Yes** (drafting only) |
| 3b | Author and independently review the 5b verifier, with no production contact | **Yes** (authoring only) |

Not authorized by this approval: merge; workflow changes or dispatch; repository or organization
variable changes; deployment; any live read (Azure, GitHub variables, CI status); running the 5b
verifier against production; Step B. Commit, push, and pull-request publication were not authorized
either and remain separate owner approvals (push, pull-request creation and merge are on the `AGENTS.md`
owner-authorization list).

## What condition 5 now says

- **5a — Go precondition.** Tasks 9.1–9.9 are `[x]`; `docker-build-verify` concluded success (not
  skipped) in the push-to-main CI run for the exact SHA the Step B dispatch names as
  `expected_main_sha`; and the 5b verifier is merged and independently ACCEPTed. This is source and
  disposable-stack evidence, not Production E2E. Whether it holds at the Step B SHA is checked at
  decision time and is not asserted by this record.
- **5b — Step B exit criterion.** Wave 9's real-browser Production E2E, defined once under Step B in
  `tasks.md` 10.2, which owns the gate semantics; the verifier that produces its evidence is specified in
  the [5b verifier spec](../../superpowers/plans/2026-09-18-wave10-2-step-b-5b-verifier-spec.md), which
  owns the mechanism only. Wave 10.2 SHALL NOT be checked until one accepted, owner-operated run records
  every item passed and independent cleanup confirmed to the golden set. Any failed item, inability to
  finish, no complete artifact within the owner-named time bound, an artifact bound to another build, or
  unconfirmed cleanup is a Step B failure and takes the existing rollback.
- Step A is API-only and is credited to neither part.

## Why (short)

Condition 5 (`tasks.md` 10.2, "Wave 9 (Live integration) actually completed, not merely unblocked") is one
of the "Go, all of" preconditions that must hold before the Go action. Real-browser production proof of
Wave 9's picker routes can exist only after Step B, which is inside the Go action: routes 9.1, 9.2, 9.4
and the picker's draft-price wire (9.3) exist in a browser only behind the compile-time
`NEXT_PUBLIC_ENABLE_ASSET_PICKER`, only Step B flips it, and the repository has no staging tier. Read as a
production browser proof, the condition could not hold before the action that produces its evidence.
Step B as previously written (render two controls, click reset once) also never opened the picker, so it
did not prove routes 9.1, 9.2, 9.4 or the picker's 9.3 wire.

The pre-exposure floor is unchanged (5a is source and disposable-stack completion). 5b adds the first
defined production browser proof. Pre-exposure assurance therefore stays CI-level, Wave 9 defects can be
found only after the picker is public, and exposure lasts until 5b passes or a rollback completes.

## Requirements and design review

`requirements.md` and `design.md` were reviewed and need no revision: neither contains Wave 9
completion, Production E2E, or Step A/B text, and `design.md` delegates release ordering to the master
plan and `tasks.md` (`design.md` D5 Stage 7 and its release-DAG paragraph). The `tasks.md` rule that a
Go condition changes only after an approved `requirements.md` / `design.md` / master-plan revision is
scoped to Wave 8 and Requirement 7; it is applied here by analogy. The master-plan plus `tasks.md`
amendment becomes the approved revision only when the owner approves it for merge; the 2026-09-19
approval covers final review and PR preparation only.

## Amended documents

- `.kiro/specs/asset-picker-composition/tasks.md`: Overview item 4; the 10.2 recorded-status wording;
  Go condition 5; the Step B exit-criterion-5b block; the Step B rollback trigger.
- `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`: the Wave 9 and Wave 10 track rows; the Task 10.1 status
  paragraph; the B2 status paragraph; the remaining-gates pointer; the dependency-path note; the B2
  position paragraph.
- `docs/superpowers/plans/2026-09-16-wave9-production-e2e-attempt-sheet.md`: append-only dated pointers
  only; its history is unchanged and its `tasks.md` line citations are stale.
- New: this record and the [5b verifier spec](../../superpowers/plans/2026-09-18-wave10-2-step-b-5b-verifier-spec.md).

## Superseded and unchanged

- Row 5 and the closing "next lane" paragraph of the
  [2026-09-16 remaining-gates audit](WAVE_10_2_REMAINING_GATES_AUDIT_2026-09-16.md) are superseded by the
  amended `tasks.md` 10.2 and this record. The audit itself is an immutable dated record and is not edited.
- The Step A gate-credit evidence and the out-of-repository Step A result artifacts are unchanged. The
  result JSON's `condition_5_status: open_owner_question` label is an accurate record of what that run
  decided. The same literal is hard-coded in `scripts/verify_wave9_step_a.py` and pinned by
  `scripts/tests/test_verify_wave9_step_a.py`; any Step A rerun will re-emit it, and changing it is a
  separate code change that this decision does not authorize.
- Task 8.9's COMPLETE / GO record is not touched.

## Constraints this decision leaves open

1. **Deploy path (constraint stated on 2026-09-18 and checked against the workflow text).** The only Azure frontend deploy path is the `deploy-frontend`
   job, which runs only in `full` deployment mode: all four backend services are rebuilt with
   `--no-cache --pull` and updated, and the `seed` and `verify` jobs then run against production. Scoped
   and digest modes skip the frontend, and digest mode covers `portfolio-service` only. Same-commit
   rebuilds have previously produced distinct image digests, and superseded revisions are purged. A
   full-mode Step B would therefore replace the `api-gateway--0000081` / `portfolio-service--0000096`
   revisions that Step A and Task 8.9 attest, could not be re-attested before exposure, and would have a
   rollback that is another full redeploy. This requires decision-time revalidation before exposure and a
   separate owner decision on the deploy path (any frontend-only deploy mode would be a workflow change
   with its own review and authorization). The workflow text is verified; that a future full run replaces
   those exact revisions is inferred, not observed. No decision on this is recorded here.

   **Post-decision status (PR #294, 2026-09-19).** The subsequently authorized and reviewed workflow
   change merged the `frontend-only` dispatcher mode at reviewed head `f6556b54` via PR
   [#294](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/294), merge commit
   `main@e15d34ad9dfb7d4698776db0d1d7b33b69c191f1`. It supplies the dedicated frontend deployment path
   contemplated above, so the deploy path is no longer an open design decision. This merge is not a
   deployment, flag change, live read, or Step B authorization. Any production use of `frontend-only`
   still requires a separately bounded owner authorization and must meet 5a's exact-`expected_main_sha`
   evidence rule.
2. **5b verifier.** It must be authored (no production contact), independently reviewed, and ACCEPTed
   before 5a can hold. Its contract is the linked spec.
3. **Decision-time verification.** Before any Step B authorization request: serving revisions and digests
   against the Task 8.9 manifest and Step A's binding; timeout and TTL configuration; both `ENABLE_*`
   repository variables unset or false at repository and organization level; the `docker-build-verify` run
   for 5a; page-level control placement at the Step B SHA. Every live read needs explicit owner
   authorization. The tracked Step A record observes only the gateway identity; the portfolio-service
   identity is asserted only in the out-of-repository preflight JSON.
4. **Time bound.** The bound within which a complete 5b artifact must exist after deploy completion is
   named in the owner's Step B authorization, not fixed here.

## Deliberately not bundled

`tasks.md` Step A sentence spliced mid-sentence (the "Consume, do not redefine" paragraph), `tasks.md`
line 142, and the "Wave 10.2 item 2 remains unsatisfied" wording in `requirements.md` and `design.md` are
independent hygiene items and are left for separate, labelled text-only changes.
