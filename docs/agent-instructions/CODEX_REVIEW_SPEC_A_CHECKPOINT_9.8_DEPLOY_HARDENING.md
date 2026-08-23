# Review request: deploy-pipeline hardening after the checkpoint-9.8 incident — verify the design and the bootstrap plan

## Context (self-contained — no prior conversation assumed)

Repo: `wealthmgmtandportfoliotracker`. **This is a portfolio/demo project, not a production business**
— worth knowing up front for calibrating how much operational weight a finding should carry: real
engineering rigor is the point (this spec's design is deliberately production-grade), but there is no
real money, real customers, or real financial-loss exposure behind the data involved.

Spec `supported-asset-integrity` ("Spec A") Task 9 is an irreversible production cutover sequence.
Checkpoint 9.8 ("R4 deployed, still overridden") is complete, but it did not execute the way it was
designed. This PR is the fix for *why* it didn't, before checkpoint 9.9 proceeds.

## The incident (already resolved — this PR is prevention, not recovery)

9.8's design (reviewed on PR #137, and correct as far as it went): apply Terraform first to add a
belt-and-braces `false` override on all three catalog-consuming services, verify it landed, *then*
deploy the R4 image (whose artifact default is `true`) — so effective behaviour never changes, because
the override always exists before the artifact default that needs shadowing does.

What actually happened: merging PR #137 (which touched `portfolio-service/`, `market-data-service/`,
`insight-service/` source) **auto-triggered `deploy.yml`'s push-to-`main` deploy** — a mechanism nobody
checked when designing the 9.8 sequence. There was no gate asking "has the Terraform override this
image depends on actually landed yet." The R4 image deployed immediately on merge, with the override not
yet applied. A second direct push (an unrelated one-line docstring fix, still under `portfolio-service/`)
triggered a second, identical auto-deploy. Both runs were cancelled once discovered; recovery was a
manual Terraform apply that added the override with no image change (the container-app module ignores
image drift). Independently verified clean: zero rejected/error events, ingress closed throughout,
refresh job fenced throughout, no writes, during the ~29-minute exposure window. Full chronology is in
`.kiro/specs/supported-asset-integrity/tasks.md`, checkpoint 9.8.

**This PR's job is to make sure "merge code, production changes" can never happen silently again.**

## What this PR does

- **`deploy.yml`**: removes the `push:` trigger entirely — `workflow_dispatch` only. Adds required
  inputs `deployment_mode` (choice: full/scoped/digest, no default) and `expected_main_sha` (string). A
  new `validate` job runs `validate_deploy_dispatch.py` before the `route`/`deploy-azure`/`deploy-aws`
  jobs: fails closed if the actual dispatch SHA (`github.sha`) doesn't match `expected_main_sha`, or if
  `deployment_mode` disagrees with `services`/`prebuilt_digest` (e.g. `mode=full` with a non-empty
  `services` input).
- **`deploy-azure.yml` / `deploy-aws.yml`**: standalone `workflow_dispatch` removed — `workflow_call`
  only. `deploy.yml` is now the only way to reach either, so its guards cover every path.
- **Concurrency**: `deploy.yml` gets a top-level `concurrency: { group: production-deploy,
  cancel-in-progress: false }` — a second dispatch queues behind an in-flight one rather than cancelling
  it mid-mutation.
- **Environment gate**: `deploy-azure`/`deploy-aws` jobs in `deploy.yml` now carry `environment:
  production`. That Environment was created via the API (`gh api ... environments/production`) with a
  required-reviewer protection rule (the repo owner) and restricted to the `main` branch — it did not
  exist before this PR.
- **Contract tests**: `scripts/tests/test_deploy_pipeline_hardening.py` (new) statically asserts all of
  the above from the YAML text (no PyYAML, matching the sibling contract tests' convention). Two
  pre-existing tests in `test_deploy_azure_service_allowlist.py` and `test_deploy_azure_prebuilt_digest.py`
  encoded the *old* contract (`push to main stays full/tag-based`, `dispatcher does not pass services
  through`) — both updated to assert the new, correct behaviour rather than deleted.
- **`validate_deploy_dispatch.py`** (new): the actual validation logic, unit-tested independently
  (`test_validate_deploy_dispatch.py`, 15 cases) — SHA mismatch, empty SHA, and all nine
  mode/services/digest consistency combinations.

## One design point I want checked, not just trusted

I confirmed via `docs.github.com` (not assumed) that `environment:` on a job that also has `uses:`
(a reusable-workflow call) correctly gates that call behind the named Environment's protection rules —
this is documented GitHub behaviour, not something I inferred from testing it live (I have not yet
actually dispatched this workflow against the real gate — see the bootstrap plan below). Please sanity
check this reading; if it's wrong, the "production" gate is decorative, and that's exactly the kind of
gap that caused the original incident.

**Update: this reading was wrong** — see "Review resolution" below. `environment:` is not a supported
keyword on a job with `uses:` at all; confirmed with `actionlint`, not another docs read.

## The bootstrap problem — and why it's not solved by this PR alone

The **old** `deploy.yml`, still on `main` until this merges, matches its own push-trigger path filter on
`.github/workflows/deploy.yml` and `.github/workflows/deploy-azure.yml` — both of which this PR touches.
Merging this PR normally would therefore itself trigger one more auto-deploy on the way out, running
under the *old* rules. (GitHub Actions is generally documented to evaluate a push event's trigger config
from the workflow file version present in that push — meaning the merge commit's own *new* file content,
which has no `push:` trigger, might mean this never fires. I am deliberately not relying on that
subtlety, for the same reason as the point above: today's incident came from trusting exactly this kind
of unverified platform-behaviour assumption.)

**Planned, not yet executed** — the 11-step version below, per the first review round:
1. Confirm no queued/running Deploy runs.
2. Snapshot all app/job images, revisions, overrides, ingress, and refresh fence.
3. `gh workflow disable deploy.yml`; verify it reports disabled.
4. Merge the corrected, fully-green PR.
5. Confirm no merge-triggered Deploy run exists and the production snapshot is unchanged.
6. Re-read the merged workflows and Environment policy from `main` (not the branch).
7. `gh workflow enable deploy.yml`.
8. Dispatch a wrong-SHA run; verify validation fails and routing/deploy jobs never start.
9. Dispatch a valid run, let it stop at Environment approval, then reject it. Verify no reusable
   deployment job or Azure mutation started.
10. Optionally dispatch two valid runs while the first awaits approval, to prove the second queues under
    concurrency; reject/cancel both.
11. Recompare the production snapshot.

## Review resolution (Codex, first round on PR #139)

All five findings addressed, independently re-verified before pushing (not just re-read):

- **[P1] `environment:` invalid on a `uses:` job** — confirmed independently with a pinned, checksum-
  verified `actionlint` binary (v1.7.7) run locally against the pre-fix files: it reproduced the exact
  rejection Codex described. My earlier WebSearch-based "confirmation" was wrong — a lesson in why this
  round leans on `actionlint` instead of another docs read. Fixed: the gate now lives on a new
  `authorize-production` job (plain job, no `uses:`) that `validate` feeds into and that
  `route`/`deploy-azure`/`deploy-aws` all transitively depend on via the `needs:` chain.
- **[P1] tests encoded the invalid syntax** — added the pinned `actionlint` step to
  `deploy-workflow-contract` (checksum-verified download, not a floating Action tag), plus rewrote the
  affected assertions for the new `authorize-production` pattern.
- **[P2] `expected_main_sha` didn't prove the ref** — `validate_deploy_dispatch.py` now also requires
  `github.ref == refs/heads/main`, passed through as `ACTUAL_REF`.
- **[P2] AWS silently dropped scoped/digest intent** — the validator now rejects any
  `deployment_mode != full` when `CLOUD_PROVIDER=aws`, reading the same repo variable directly.
- **[P2] `full` as the first/default choice** — `deployment_mode`'s options now start with a sentinel
  (`select-deployment-mode`) that the validator explicitly rejects with a clear message.
- **`can_admins_bypass`** — confirmed against `docs.github.com`'s environments API reference that this
  is not a settable request-body field on `PUT .../environments/{name}`. Not fixable via API; flagging as
  a platform limitation rather than silently leaving it unaddressed.

Local verification before this push: 89 tests across the full deploy contract suite (`test_validate_deploy_dispatch`
now 23, `test_deploy_pipeline_hardening` now 12, plus the five untouched/updated sibling files), and
`actionlint` reporting zero errors on all four edited workflow files.

## What NOT to re-litigate

- Whether checkpoint 9.8 itself (the enforcement-flag flip, the Terraform override mechanism) was
  designed correctly — that was reviewed on PR #137 and held up; the incident was a pipeline gap outside
  that diff, not a flaw in the reviewed design.
- Whether the recovery Terraform apply was correct — independently verified, already closed, documented
  in tasks.md.
- Whether checkpoint 9.9 should proceed yet — it explicitly should not, until this PR merges and its
  bootstrap + gate verification is complete.

## What's being asked

1. Does the `deploy.yml` / `deploy-azure.yml` / `deploy-aws.yml` design actually close the gap — is there
   any remaining path to a production mutation that skips `validate`, the concurrency group, or the
   `production` Environment approval?
2. Is the bootstrap plan (disable → merge → verify → enable → failure-path-only dispatch) sufficient, or
   is there a step missing given this is the second time in one day a plausible-sounding execution order
   turned out to have a gap the reviewed design didn't anticipate?
3. Anything in `validate_deploy_dispatch.py`'s logic that's wrong or incomplete — it's the one piece of
   new application logic in this PR, everything else is workflow YAML restructuring.
