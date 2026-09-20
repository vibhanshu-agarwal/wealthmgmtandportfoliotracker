# Asset Picker Step B Production E2E — Claude Kickoff

> **Status (2026-09-20): COMPLETED.** The bounded Step B run completed from
> `main@91f40bd0126f15fc87a6d6beb2ffa6bcd01e76d4` through frontend-only deploy run
> `35489160653`. The accepted verifier returned `GO` with all L0-L9 legs passed, cleanup confirmed,
> and no rollback required. See the
> [production evidence record](../../evidence/b2-wave-10-2/WAVE_10_2_STEP_B_5B_PRODUCTION_E2E_GO_2026-09-20.md).

> **For Claude:** This is a single, bounded production-completion task. It authorizes no repository change, CI/CD redesign, or unrelated investigation. Stop and report any issue that directly prevents the Asset Picker Step B run; do not attempt to fix it unless the owner separately authorizes the fix.

**Goal:** Complete the Asset Picker's production proof: expose the already-implemented frontend once, run the accepted real-browser Wave 9 proof, confirm cleanup, and either accept the evidence or complete the defined rollback.

**Operating model:** The existing `Deploy` workflow's `frontend-only` mode is the only deploy path. It rebuilds and uploads the static frontend only; it must not route to a backend, image, or infrastructure deployment. The owner performs credential-bearing actions, repository-variable changes, and the production Environment approval. Claude may perform only the read-only checks and coordination that this note names.

**Authority and limits:** This kickoff is not an authorization to mutate production. Before Task 2, the owner must explicitly authorize one run by naming the exact current `main` SHA, a wall-clock bound of at least 300 seconds, and the person who will perform the credential-bearing steps. That authorization must cover setting both repository variables, dispatching the frontend-only deployment, approving the production Environment gate, and owner-operated verifier execution. The decision-time serving snapshot below is a separate bounded read-only authorization unless the owner supplies its sanitized values. A failed precondition is a stop, not an invitation to repair CI/CD. A failed Step B run requires the exact rollback below.

**Normative references:**

- `.kiro/specs/asset-picker-composition/tasks.md` — Wave 10.2 condition 5, Step B, and rollback semantics.
- `docs/superpowers/plans/2026-09-18-wave10-2-step-b-5b-verifier-spec.md` — verifier inputs, mechanism, evidence schema, and exit semantics.
- `.github/workflows/deploy.yml` — validated `frontend-only` dispatch and production Environment gate.

## Absolute scope boundary

Do not work on any of the following:

- CI wiring for `scripts/tests/test_verify_step_b_5b.py`, pytest dependency management, pinning, or a no-network CI plugin.
- Workflow refactoring, workflow permissions, branch protection, static guards, GitHub Actions optimization, or any unrelated failing/skipped CI job.
- Asset Picker feature code, test code, documentation reconciliation, PR creation, or a merge.
- Backend, Container App, image, Terraform, database, or infrastructure deployment.
- Any secret, token, demo password, or internal API key. Claude must not request, receive, paste, log, or handle one.

The only CI fact in scope is the explicit Step B precondition: for the exact SHA selected below, its push-to-`main` CI run must show `docker-build-verify` as `success`, not `skipped`. If that fact is absent or failed, report the SHA, run ID, and conclusion, then stop. Do not investigate or repair the pipeline.

## Required acceptance facts

Asset Picker is complete only when one evidence artifact, bound to the Step B frontend deployment, establishes all of the following:

1. The deployed page renders both `EditHoldingsButton` and `Reset Demo Portfolio` in a fresh uncached browser session.
2. The five Wave 9 routes pass in the prescribed order: summary freshness, catalog read, presence read, picker draft-price read, and non-golden save followed by reset.
3. The save returns `200`, advances the version, and its edited holdings persist on an independent read.
4. The reset returns the shared demo portfolio to the golden set, confirmed independently.
5. The artifact binds the served frontend revision, the serving `api-gateway` and `portfolio-service` revisions, deploy run ID, selected source SHA, and owner-named time bound.
6. Independent cleanup is confirmed. A deploy succeeded but an incomplete, unbound, failed, or late artifact is not a pass.

## Task 0: Obtain authority and confirm decision-time exposure gates

**Required input before Task 0 can close:** Conditions 3, 4, and 6 require one decision-time serving snapshot before either flag changes. The owner must either supply sanitized output from `scripts/run_task_8_9_preflight.ps1` together with `scripts/verify_demo_reset_azure.py`, or separately authorize Claude to obtain that one bounded read-only snapshot. The authorized scope is limited to the scripts' serving, serving-revalidation, key-alignment, and decision fields for the `api-gateway`, `portfolio-service`, `market-data-service`, and `insight-service`; it excludes variable reads or writes, deployment, workflow dispatch, secrets, and any remediation.

**Owner action:** Supply the sanitized snapshot or authorize its bounded read-only capture. The full Step B authorization is deliberately not due until this task's gate snapshot is green.

**Claude actions (read-only):**

1. Present a compact yes/no snapshot from the governing evidence for each remaining exposure condition before variables move:
   - condition 1: B1/Spec A activation gates;
   - condition 3: the current serving/hidden state for Waves 3–6;
   - condition 4: a decision-time comparison between Task 8.9's approved manifest and the actually serving revisions, digests, and configuration;
   - condition 6: the approved manual-reset placement, timeout, and TTL configuration as part of that same current-serving comparison.
2. Cite the governing evidence for each conclusion. A historical GO is not a current confirmation when the condition requires a decision-time comparison.
3. Report `GATE SNAPSHOT GO` only when all four conditions are affirmatively confirmed. Otherwise report the exact missing condition and stop.
4. Do not repair a missing gate, alter a flag, change a workflow, or investigate unrelated CI while producing this snapshot.

**Stop condition:** Missing decision-time snapshot or any condition 1, 3, 4, or 6 that is not affirmatively confirmed at the exposure decision.

## Task 1: Select and prove the exact Step B source state

**Owner action:** After Task 0 reports `GATE SNAPSHOT GO`, explicitly authorize one Step B run. Name the exact current `main` 40-character SHA, the Step B time bound, and the credential-bearing operator; authorize the two repository-variable changes, frontend-only deployment dispatch, production Environment approval, and owner-operated verifier execution. Do not select a moving branch label as the evidence identity.

**Claude actions:**

1. Confirm that the named SHA is the selected `main` source state.
2. Locate its push-to-`main` CI run and record the run ID and the terminal conclusion of `docker-build-verify`.
3. Continue only if that conclusion is `success`; a skipped, pending, cancelled, or failed conclusion is a direct blocker.
4. Confirm the accepted 5b verifier remains present at that SHA. Do not wire it into CI or modify it.
5. Send the owner a compact go/no-go line containing: SHA, CI run ID, `docker-build-verify` conclusion, and whether Step B may be dispatched.
6. Keep `main` frozen between this conclusion and the production dispatch. If its tip changes, repeat only this exact-SHA check for the new tip; do not diagnose or repair CI.

**Stop condition:** Any result other than a successful `docker-build-verify` for the exact selected SHA.

## Task 2: Owner-operated frontend-only Step B deployment

**Owner actions:**

1. Capture the live site's pre-deploy Next.js `buildId` before either variable changes.
2. Set both repository-scoped variables in one change: `ENABLE_ASSET_PICKER=true` and `ENABLE_DEMO_RESET_CONTROL=true`.
3. Dispatch `Deploy` against the selected `main` SHA with `deployment_mode=frontend-only`, the exact selected SHA as `expected_main_sha`, and empty `services` and `prebuilt_digest` inputs.
4. Approve the `production` Environment gate.
5. Capture the deployment run ID and completion UTC time after the frontend deployment finishes.
6. Capture the required post-deploy, pre-verifier backend attestation for the serving `api-gateway` and `portfolio-service` revisions.

The pre-variable decision-time snapshot and post-deploy attestation must not be collapsed into one observation. The first satisfies conditions 4 and 6 at the exposure decision; the second is a mandatory verifier input that binds the completed frontend deployment to the servers observed immediately before the browser proof.

**Claude actions:**

1. Observe only the validated dispatch inputs and routing conclusion: `frontend-only` must route to `deploy-azure-frontend.yml` and must not route to a backend deploy workflow.
2. Record the frontend deploy run ID, completion time, chosen SHA, and observed frontend binding information for the evidence packet.
3. Do not enter or view secrets, variables, credentials, or the production Environment approval.

**Stop condition:** Any deployment route other than frontend-only, failure of the deployment, or inability to establish the required frontend/backend bindings.

## Task 3: Owner-operated production browser proof and cleanup

**Owner actions:**

1. From a clean checkout at the selected SHA, run `scripts/verify_step_b_5b.py` using the verifier spec's required inputs: an absolute external evidence path, baseline commit, deploy-completed UTC, named time bound, pre-deploy build ID, pre-run backend attestation, deploy run ID, and source SHA.
2. Supply the demo password only through the verifier's masked interactive prompt. Do not pass it through arguments, environment variables, chat, or logs.
3. Retain the verifier's evidence artifact outside the repository.

**Claude actions:**

1. Check the completed artifact only for the required acceptance facts above: all legs passed, correct deployment bindings, within the named bound, and independent cleanup confirmed.
2. If the artifact is a complete GO, prepare a concise final acceptance packet for one Fable 5.1 evidence review.
3. If it is not a complete GO, immediately invoke the rollback path below. Do not retry Step B, alter test code, or start a CI/CD investigation.

## Failure and rollback

For a Step B deployment, smoke, verifier, binding, timing, or cleanup failure:

1. The owner sets both repository-scoped variables back to `false` in the same change.
2. The owner dispatches another `frontend-only` deployment.
3. Wait for that rollback deployment to complete.
4. In a fresh uncached browser session, verify that both Asset Picker and manual-reset controls are absent from the served page.
5. Record the served rollback revision and report the failed Step B as non-GO.

Do not call a dispatched rollback complete before the rollback frontend is served and the controls are confirmed absent.

## Single final review

Fable 5.1 is used once, after a complete Step B evidence artifact exists. Its review scope is limited to whether the artifact establishes the six required acceptance facts and whether a failure correctly produced the documented rollback. It must not reopen CI-wiring, dependency, or design discussions.

## Completion report

Return only:

- selected SHA and qualifying CI run ID;
- frontend-only deploy run ID and completion UTC;
- Step B artifact path and GO/non-GO conclusion;
- final Fable acceptance or rejection;
- if non-GO, rollback deployment run ID and fresh-browser confirmation that both controls are absent.

Anything outside those facts is out of scope unless it directly blocks this production proof.
