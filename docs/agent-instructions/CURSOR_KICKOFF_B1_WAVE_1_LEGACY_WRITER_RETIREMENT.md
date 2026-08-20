# Cursor Kickoff — Spec B1 Wave 1: legacy writer retirement

**Date:** 2026-08-20
**Prepared for:** Cursor (implementation)
**Baseline:** `origin/main`, currently `dd25e8b` or later — Wave 0 (fixture identity, PR #121) is merged
**Suggested branch:** `feat/b1-legacy-writer-retirement` off `main`
**Spec:** `.kiro/specs/portfolio-composition-contract/` — read from `main`, which is authoritative (`origin/spec/portfolio-composition-contract` is stale, per Wave 0's own kickoff note)

---

## 0. What this wave is, and what it is not

**Implementation is unblocked. The release is not — and merge *is* the deploy trigger, not a step before it.** `portfolio-service/**` sits in `deploy.yml`'s `push: paths:` filter (§7): the moment this PR merges to `main`, the production deployment starts automatically. There is no separate manual "deploy" action to gate afterward, which means "merge now, check with the owner before deploying" cannot actually be executed as a two-step process — by the time anyone could object, the pre-release control point is already gone. That is distinct from R-0 itself: R-0 is the candidate becoming **traffic-serving**, which is what task 1.4's G0a evidence confirms. A deploy that fails before the new revision receives traffic means R-0 did not happen, even though the merge did.

**The gate belongs before the merge, not between merge and deploy. Correct sequence:**

1. Implement and test tasks 1.1–1.3.
2. Open the PR.
3. **Get explicit release authorization from the owner before merging** — this is where design Revision 11's requirement (Spec A's production steady state must precede B1's *entire* release lane, not just the schema wave) actually has to be enforced, since it's the last point before R-0 becomes irreversible-by-omission.
4. Merge (this starts the deployment automatically — the pre-release control point is gone from here on).
5. Once the new revision is traffic-serving, R-0 has happened; collect G0a evidence (task 1.4) confirming that serving state. (If the deploy fails before traffic moves to the new revision, R-0 did not happen — there's nothing to confirm, and nothing to abort either.)
6. Raise 1.5's STOP/GO as a question to the owner — keep the now-serving candidate, or abort by redeploying the prior digest.

Tasks 1.4 and 1.5 are not parallel work alongside 1.1–1.3; they are gated on step 3 having happened and step 4 having actually occurred. Do not attempt either before that.

**Backend only, no UI risk.** Confirmed directly against source, not assumed: `PortfolioService.createPortfolio()` and `addHolding()` have **zero internal callers** anywhere else in `portfolio-service` (checked via grep across `src/main/java/`), and Requirement 8.3 states — and Wave 0 already acted on — that `frontend/tests/e2e/helpers/api.ts` was the only live consumer of these routes, migrated in Wave 0. **No production frontend code calls either endpoint.** This is why the whole wave is being handed to you rather than split — there's no UI surface for the reserved-for-Claude carve-out to apply to.

**Scope: tasks 1.1–1.5 only.** Not Wave 2, not the `/api/assets` route, nothing in the gateway.

## 1. The two routes being retired, and why retirement, not idempotence

Both live in `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioController.java`:

- **`POST /api/portfolio`** (`createPortfolio`, lines 52–56) — creates a portfolio unconditionally, backed by `PortfolioService.createPortfolio()` (`PortfolioService.java:54-58`).
- **`POST /api/portfolio/{portfolioId}/holdings`** (`addHolding`, lines 70–77) — adds/updates a holding without any version check, backed by `PortfolioService.addHolding()` (`PortfolioService.java:60-83`).

Requirement 8.6 is explicit about the choice: retirement, not idempotence, because "retirement and idempotence are observably different API contracts, and carrying an idempotent compatibility path serves no consumer." Don't reach for a 200-on-repeat compromise — the requirement already closed that door.

Requirement 8.9 / 1.25 states the *reason* this must happen before the unique constraint lands (Wave 3): a reachable duplicate-creation path plus a unique constraint means a raw database error escapes to the caller instead of a specified contract. That ordering is Wave 3's problem, not yours — it just explains why this wave exists at all and must complete before Wave 3 can safely proceed.

## 2. What "retired" means precisely — pin the response, don't hand-roll it

Requirement 8.8: the retired route's response "SHALL be pinned — normally `405 Method Not Allowed` on the surviving collection route — so that the outcome is a specified contract rather than a unique-constraint violation escaping the boundary as a database error."

**"Normally 405" is doing real work in that sentence — the two routes are not guaranteed to land on the same status, and they don't.** `POST /api/portfolio` and `GET /api/portfolio` (line 36, untouched) share the exact path `/api/portfolio`. Deleting only `createPortfolio`'s `@PostMapping` leaves that path still matched by `GET`, so Spring's normal "path matched, method didn't" case applies and a POST there genuinely 405s. But **nothing else in this controller, or anywhere else in `portfolio-service`, maps `/api/portfolio/{portfolioId}/holdings`** — `addHolding`'s `@PostMapping("/{portfolioId}/holdings")` is the only handler for that path pattern. Once it's deleted, a POST there matches **no handler at all**, which is Spring's "no mapping found" case, not "mapping found, wrong method" — and that case returns **404 Not Found**, not 405. Do not assume both routes 405 by symmetry with 1.1; **observe each one independently** (a quick standalone-`MockMvc` run against the post-deletion controller, before writing the pinned test, settles it) and pin whatever each actually returns.

**Delete the two `@PostMapping` methods and the `AddHoldingRequest` record** (`PortfolioController.java:79-80`, used only by `addHolding`) — do not leave them mapped-but-stubbed. `GlobalExceptionHandler.java` has no `@ExceptionHandler` for `HttpRequestMethodNotSupportedException` today (checked: only `MissingRequestHeaderException`, `UserNotFoundException`, `FxRateUnavailableException`, `UnsupportedAssetException` are handled) — so `POST /api/portfolio`'s 405 falls through to Spring Boot's default, and the holdings route's 404 likewise falls through with no custom handling. **Verify the actual response body each produces in this app** before writing the pinned-response tests, rather than assuming a shape; Requirement 8.8 asks for a *specified* contract, so whatever body actually comes back needs to be the one the test asserts, and if either is bare/unstructured compared to this app's other error responses, that's worth a one-line note in the PR, not silent inconsistency.

**Also delete `PortfolioService.createPortfolio()` and `addHolding()` themselves**, not just the controller wiring — confirmed zero other callers. Leaving dead service methods around after "retirement" is the same defect this project has flagged repeatedly this month (see the `evidence-oracle-mismatch` pattern in the repo's own retrospectives): code that looks retired but isn't actually gone.

## 3. Tasks

### 1.1 — Retire `POST /api/portfolio`
_Requirements: 8.5, 8.6, 8.8, 1.13_

Delete `createPortfolio` from `PortfolioController` and `PortfolioService`. `GET /api/portfolio` (the surviving collection route, lines 36-40) is untouched. Requirement 1.13 is a preservation note, not new work: "no product path creates a second portfolio and no path selects between portfolios" already holds and must keep holding.

### 1.2 — Retire the versionless holdings `POST`
_Requirements: 8.1, 8.2, 8.4_

Delete `addHolding` from `PortfolioController` and `PortfolioService`, and the `AddHoldingRequest` record. Requirement 8.4: after this, no holdings writer should exist "that accepts a mutation without a version check, except the paths explicitly exempted in 8.10" (Flyway migrations — out of scope here, don't touch migration files).

**This route's retired status is 404, not 405 — see §2.** No other mapping in `portfolio-service` covers `/api/portfolio/{portfolioId}/holdings`, so once `addHolding` is gone Spring has no handler to route to at all. Pin the 404 you actually observe; don't carry over the 405 assumption from 1.1.

**Deleting `addHolding` also breaks compilation elsewhere, not just in `PortfolioControllerTest`.** `PortfolioServiceHoldingValidationTest.java` (`addHolding_rejectsUnknownTickerWithoutPersisting`, line 58, and `addHolding_rejectsIncreaseOfDeprecatedPositionWithoutPersisting`, line 68) calls `service.addHolding(...)` directly — this file will fail to compile the moment the method is deleted. Delete this test class (both cases exist to prove `SupportedAssetValidator.requireHoldingWrite`'s behavior at the `addHolding` call site, which is gone). Note that `requireHoldingWrite` itself (`SupportedAssetValidator.java:42`) has no other caller in `portfolio-service/src/main` once `addHolding` is deleted — `PortfolioSeedService` calls the validator's separate `requireActive` method, not this one. That's out of scope to fix here, but flag it in the PR description rather than leaving a silently-orphaned method behind — this is the same "looks retired but isn't gone" pattern §2 already calls out for the controller/service methods, one layer deeper.

### 1.3 — Quantity_Domain reachability statement
_Requirements: 3.3_

Requirement 3.3: the versionless holdings POST "SHALL enforce the Quantity_Domain for as long as that path exists." If tasks 1.1 and 1.2 retire both routes in the same commit/deploy (the expected case — there's no reason to stagger them), there is no interval where the old path is reachable without the version check it's being retired *for*, so this requirement is vacuous by construction. **State that explicitly in the PR description** rather than skipping the check silently — the task text is deliberate about not letting a vacuous case look like an unaddressed one.

### 1.4 — G0a evidence
_Requirements: 8.9, 1.25_

"No traffic-serving portfolio digest exposes either route: revision → digest → traffic capture." Use the same evidence-collection shape as this repo's prior G-gates — see the "Production verification" table in [`docs/changes/CHANGES_SUPPORTED_ASSET_INTEGRITY_2026-08-19.md`](../changes/CHANGES_SUPPORTED_ASSET_INTEGRITY_2026-08-19.md) (Claim/Evidence rows, each backed by a linked workflow-run URL) for the pattern this project already uses: active revision → digest identity → a live probe proving the deployed code, not just the deploy record, reflects the retirement. Cite G0a the same way — a specific run URL or job ID and the commit SHA it ran against, not a date or a general claim.

Per §0, this task starts only **after** the owner has authorized and the merge has actually fired the deploy. It cannot be produced from a local build, and it cannot be attempted between "PR opened" and "merge authorized" — there's no deploy to observe yet at that point. Don't fabricate or simulate it; if the authorized merge hasn't happened yet when you reach this task, say so and stop here rather than inferring the gate is satisfied from the code being open in a PR.

### 1.5 — STOP/GO — R-0
_Requirements: 8.9, 1.25_

**Go:** G0a and G0b (Wave 0's task 0.7 — see §6 below) both green.
**Abort:** redeploy the prior portfolio-service digest, restoring both routes. Explicitly safe at this stage per the task text: no unique constraint exists yet (that's Wave 3), so a restored creator cannot produce a raw database error — the worst case is reverting to today's behavior, not a new failure mode.

**Do not execute this STOP/GO yourself, and do not merge on your own authority either — they're the same decision.** Per §0, merging *is* R-0's deploy trigger, and that deploy is gated the same way every B1 release is: on Spec A's production cutover, an operational decision outside this wave. Implement 1.1–1.3, open the PR, and stop — raise the merge/release decision as a question. Only once the owner authorizes it do you merge, collect 1.4, and then raise 1.5's keep-or-abort question with that evidence in hand.

## 4. Verified anchors (checked against `main` at `dd25e8b`)

- `PortfolioController.java` — `createPortfolio` lines 52-56, `addHolding` lines 70-77, `AddHoldingRequest` lines 79-80, both under `@RequestMapping("/api/portfolio")` (line 14)
- `PortfolioService.java` — `createPortfolio` lines 54-58, `addHolding` lines 60-83 (calls `supportedAssetValidator.requireHoldingWrite` at line 71). The `SupportedAssetValidator` class itself is not being removed — it has a separate live caller (`PortfolioSeedService.requireActive`) — but `requireHoldingWrite` specifically loses its only caller here; see task 1.2's note and §8.
- Zero other callers of either service method anywhere in `portfolio-service/src/main/java/` (verified by grep)
- `GlobalExceptionHandler.java` — four existing `@ExceptionHandler`s, none for `HttpRequestMethodNotSupportedException`
- `PortfolioControllerTest.java` — the **only** existing controller test touching either retiring route is `addHoldingUnsupportedAssetReturns422Contract` (lines 87-101), which posts to the holdings route and asserts a 422 from the (still-versionless) service call. This test's entire premise disappears once the route is retired — remove it. There is currently **no existing test at all** for `POST /api/portfolio`'s retirement — that case needs to be added fresh, not adapted from something that already existed.
- `PortfolioServiceHoldingValidationTest.java` — both tests (`addHolding_rejectsUnknownTickerWithoutPersisting`, line 58; `addHolding_rejectsIncreaseOfDeprecatedPositionWithoutPersisting`, line 68) call `service.addHolding(...)` directly. This file fails to **compile**, not just fails to pass, the moment `addHolding` is deleted — delete it as part of 1.2, not as an afterthought discovered by a build failure.
- **New tests go in a class matching `*LegacyWriterRetirementTest`** — Wave 3's task 7.5 (`.kiro/specs/portfolio-composition-contract/tasks.md:801`) already fixes this as the required report-class pattern for "Legacy route contract (both retirements)" in the candidate-proof manifest. Naming it anything else now means renaming it later to satisfy a gate that's already written. Assert the pinned status for each route independently: 405 for `POST /api/portfolio`, 404 for `POST /api/portfolio/{portfolioId}/holdings` — see §2 for why they differ, and confirm both empirically before asserting either.
- `portfolioEndpoints()` (`PortfolioControllerTest.java`, line 75-77) only lists `/api/portfolio` for the missing-header parameterized test, and that's the `GET` case — unaffected by this wave.

Re-verify before editing; line numbers shift.

## 5. Definition of done

- 1.1–1.3 implemented and tested pre-merge; PR opened and merge held until the owner authorizes release (§0). 1.4 collected only after that authorized merge has fired the deploy. 1.5 raised as a question, not executed.
- `POST /api/portfolio` returns 405 and `POST /api/portfolio/{portfolioId}/holdings` returns 404 (see §2 for why they differ) — each pinned to a body Cursor has actually observed (not assumed) and documented.
- `PortfolioService.createPortfolio()`, `addHolding()`, and `AddHoldingRequest` fully removed — not stubbed, not left reachable through any other path.
- `addHoldingUnsupportedAssetReturns422Contract` and `PortfolioServiceHoldingValidationTest.java` both removed; new tests proving the 405/404 pins added in a class named to match `*LegacyWriterRetirementTest` (§4).
- 1.3's vacuous-case statement is in the PR description.
- Full `portfolio-service` test suite green; `./gradlew :portfolio-service:test` (or the project's standard non-daemon invocation — see this repo's own notes on local Gradle daemon issues if `test` hangs; prefer `--no-daemon`).
- Spec checkboxes 1.1–1.3 ticked as part of this PR. **1.4 cannot be ticked in the same PR** — its evidence doesn't exist until after that PR has merged and deployed — so tick it in a small follow-up commit to `main` once G0a is collected. Leave 1.5 unticked until the owner's keep/abort decision is made.

## 6. Wave 0's own loose end — fix it here or flag it, don't ignore it

Wave 0's task **0.7** (G0b evidence) is unchecked in `tasks.md` despite the evidence already existing: `docker-build-verify` in `ci-verification.yml` starts a fresh `docker compose up -d` on a clean runner (no persisted volumes — genuinely a fresh disposable database) and runs both `golden-path.spec.ts` and `dashboard-data.spec.ts` against it. That job ran on PR #121's merge to `main` and **completed with conclusion `success`**, confirmed directly (not inferred from the PR having merged) against the live run:

- Run: [32399211853](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32399211853)
- Job: `docker-build-verify` ([96530029529](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32399211853/job/96530029529)) — conclusion `success`
- Commit: `dd25e8b64f90a51c4c9a9c73fe14e05cc1c29b97` (PR #121's merge commit)

Note this run was still `in_progress` (the E2E step hadn't finished) when this kickoff was first drafted — the original citation was written before the job had actually concluded. Re-confirm the run's conclusion is still `success` before ticking 0.7; don't carry this citation forward on trust once more time has passed. Tick 0.7 as part of this PR's spec-doc updates, citing the run/job/commit above — don't leave a stale unchecked box sitting next to the wave you're now building on top of.

## 7. Merge effects

`portfolio-service/**` is in `deploy.yml`'s `push: paths:` filter — merging starts a production deployment of `portfolio-service` **immediately and automatically**. Per §0, that means the owner's release authorization has to be obtained **before you merge**, not after — there is no later point at which the deploy can still be gated. Merging is not R-0 itself, though: R-0 is the candidate becoming traffic-serving, which happens only once the deployment completes and the new revision starts receiving traffic. Expect the deploy to complete cleanly, but don't conflate "the deploy completed" with "R-0 happened" — that's task 1.4's G0a evidence to confirm, from the live serving state, not inferred from the merge or the deploy job succeeding. (And if the deploy fails before traffic moves, R-0 simply didn't happen — nothing to confirm, nothing to roll back.)

**Merge alone**, matching every prior wave's guidance — `deploy.yml`/`deploy-azure.yml` have no `concurrency:` group (tracked in `docs/todos/TODOS_2026-04-07.md`).

## 8. Escalate rather than decide

- Any caller of `PortfolioService.createPortfolio()` or `addHolding()` this kickoff's grep missed — re-verify before deleting, don't trust this document's line numbers blindly.
- Any need to touch the gateway, Wave 2's `/api/assets` route, or anything in `db/migration` — out of scope for this wave.
- Whether task 1.5's STOP/GO can proceed — this is an operational/deploy-timing decision, not a code question. Raise it, don't answer it.
- If Spring's default 405 or 404 body turns out to carry something unexpected (a stack trace, an inconsistent shape versus this app's other error responses) — flag it rather than silently asserting on whatever comes out.
- Whether `SupportedAssetValidator.requireHoldingWrite` (now caller-less once `addHolding` is deleted, per §3's task 1.2 note) should be removed too — that's a judgment call about a method beyond this wave's stated scope, not something to decide unilaterally while retiring the routes.
- Do not reuse this kickoff's G0b citation (§6) past the point of re-confirming it yourself — it was written from a run that was still `in_progress` at draft time and only resolved to `success` minutes later. Treat any pre-written evidence citation in this repo the same way: re-check the live status before citing it onward.
