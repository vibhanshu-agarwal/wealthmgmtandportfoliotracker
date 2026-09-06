# B2 Wave 8 Product Decisions and Login Reset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reconcile the merged Wave 9 result, resolve the remaining B2 product and operational decisions, complete Wave 8 source and local verification, prepare the Azure deployment-evidence foundation, and stop at explicit approval gates before any production deployment or live proof.

**Architecture:** Add an opportunistic, fail-open demo reset orchestration to the successful demo-login publisher in `api-gateway`. It performs two sequential nonblocking self-calls through the gateway's actual loopback port and route table, propagates trace context through the observation-enabled `WebClient.Builder`, submits the exact version observed by the eligibility read, and never changes ordinary login behavior. In parallel, harden Azure deployment evidence around immutable digests, exact per-service artifact aggregation, serialized production deployment, and a tested live-proof executable. Keep decision, source, deployment, and serving-proof gates separate.

**Tech Stack:** Java 21, Spring Boot 4.1, WebFlux/Reactor, Micrometer/OpenTelemetry, Gradle, Python 3, GitHub Actions, Docker Buildx, Azure Container Apps, KQL, Terraform, Next.js/React for the independent placement decision.

**Spec:** `.kiro/specs/asset-picker-composition/requirements.md` Requirement 7 and Open Items; `.kiro/specs/asset-picker-composition/design.md` D5; `.kiro/specs/asset-picker-composition/tasks.md` Tasks 8.1-8.9 and GC.6/GC.8/GC.9/GC.11; `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`.

## Global Constraints

- Start from `origin/main@318f28592da6ab2e3bd66bc738aa68d374b180fa`, the merge commit for PR #232. Verify the SHA before editing; if `origin/main` moved, record the new baseline and inspect the intervening changes.
- PR #232's reviewed head was `dba83c3a1cc28212c6aa115db803cbd15858e1e4`. Final CI run `34018608256` passed, including `docker-build-verify` and `ci-required`; the PR-body edit guard run `34020180243` also passed. This is assembled-stack evidence, not deployment or Production E2E.
- Do not reimplement Tasks 8.1, 5.1a, 5.1b, or 8.2a. Reconcile their source and runtime provenance before scheduling any prerequisite deployment.
- Task 8.2's product/operational choices were owner-approved on 2026-09-06 and independently accepted by Astra. They are frozen in the decision record: strict 30-minute persisted idle age, 2-second eligibility, 2-second reset, and 4-second overall timeouts, plus page-level manual-reset placement.
- “Idle” means the age of the persisted portfolio `updatedAt`; it does not mean browser inactivity and is unrelated to the 150-second presence TTL.
- Use strict TDD for implementation and workflow tooling: observe a discriminating RED, make the smallest change, observe GREEN, then mutation-check the critical oracle and restore byte-for-byte.
- The login reset is sequential, nonblocking, per subscription, and fail-open. Do not use `WebClient.create()`, `.block()`, `RestTemplate`, detached subscriptions, automatic retry, or singleton mutable progress state.
- Both self-calls target the gateway loopback using `local.server.port` read at call time. Tests must exercise the actual random-port gateway and route table, not inject the downstream stub directly into the new clients.
- Run the orchestration only after successful demo authentication and JWT minting. Failed authentication, signup, and ordinary-user login issue no eligibility or reset call.
- Keep synchronous construction failures inside `Mono.defer(...)` and inside the fail-open boundary so `AuthController`'s generic handler cannot turn an opportunistic reset failure into login `500`.
- Exactly one eligibility GET is allowed. The reset count is zero or one. Submit the exact observed version; do not reread and do not retry a real conflict.
- An overall timeout cancels undispatched work, but an already-dispatched reset may commit and emit `demo_reset_succeeded` while the gateway also emits a timeout skip event. Tests and live evidence must admit and classify this real dual-event outcome.
- Do not change manual-reset routes, authorization behavior, existing secret/replica providers, frontend feature flags, or the governed E2E seed inventory in the Wave 8 source lane.
- Azure is the target for this kickoff. AWS Task 8.8a remains open and is required only for an AWS Task 8.9 run.
- Preserve `deploy.yml` as the protected dispatch entry point and preserve `deploy-azure.yml` as `workflow_call`-only. Do not restore direct child-workflow dispatch.
- Source work, local tests, documentation, and local commits are authorized by this kickoff. Push, PR creation, merge, registry access, workflow dispatch, Azure/AWS writes, secret access, production probes, rollback, and feature exposure require separately recorded owner authorization.
- Deploying Wave 8 changes demo-login behavior and can mutate the shared demo portfolio even while both frontend flags are off. Treat Task 8.8 as a production change gate.
- Task 8.9 additionally requires Task 8.8b evidence, deployed Wave 5 routes/allowlist, and B1 Wave 7's public composition endpoint. B1 R-C's recorded NO-GO cannot be bypassed by deploying current `main` wholesale.
- Luna is the sole writer for the B2 ledger, master plan, and handoff; the coordinator reviews and integrates those edits only after evidence exists. A Wave 8 status-changing PR uses exactly one `Master-plan impact: updated — B2` declaration. A genuinely status-neutral PR uses a concrete same-line rationale, for example `Master-plan impact: none: documentation-only change with no program-status effect`.

---

## Agent Topology and Coordination Contract

Use the same four-role model as the Wave 9 run:

| Role | Model | Responsibility |
|---|---|---|
| Coordinator / architect | `gpt-5.6-sol`, high reasoning | Baseline, decision packet, interface freeze, shared branch, integration, evidence, approval boundaries |
| Enterprise architect | `gpt-6-astra`, high or xhigh reasoning | Independent decision/design review, oracle review, cross-lane review, final ACCEPT/REVISE |
| Implementation lane | `gpt-5.6-terra`, medium reasoning | Gateway orchestration, authentication integration, timeout/cancellation seams, GC.6/GC.8/GC.9, Task 8.7a |
| Process/tooling lane | `gpt-5.6-luna`, low reasoning | Wave 9 reconciliation, Task 8.8b workflow/tooling, ledgers, exact status guards |

The coordinator creates isolated sibling worktrees from the pinned baseline. Agents never edit the same worktree. Freeze shared contracts before implementation. An author does not approve their own lane.

Shared-file ownership:

- Terra alone owns `AuthController.java` and the gateway orchestration production package.
- Coordinator authors and commits shared gateway configuration/build changes after the Task 1 contract freeze, including `application.yml`, tracing-test dependencies, the dedicated `wave8IntegrationTest` source set, and its integration-gate dependency. Terra consumes that commit and does not edit those shared files.
- Luna alone owns Azure workflow, digest CLI, live-proof CLI, and living-status documents.
- Astra is read-only unless the coordinator explicitly assigns a narrow corrective implementation after a rejected design finding.
- Coordinator serializes Docker/assembled runs, consolidation, mutation checks, publication, deployment packets, and live operations.

---

## Task 0: Baseline and Wave 9 Status Reconciliation

**Owner:** Luna, reviewed by coordinator.

**Files:**

- Modify: `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`
- Modify: `.kiro/specs/asset-picker-composition/tasks.md`
- Modify: `docs/superpowers/plans/2026-09-06-b2-wave9-assembled-e2e-handoff.md`

- [ ] **Step 1: Verify immutable facts**

Confirm PR #232 is merged at `318f28592da6ab2e3bd66bc738aa68d374b180fa`, its reviewed head is `dba83c3a1cc28212c6aa115db803cbd15858e1e4`, CI run `34018608256` passed `docker-build-verify` and `ci-required`, and body-edit run `34020180243` passed. Record the URLs and conclusions; do not infer CI from merge state.

- [ ] **Step 2: Correct stale Wave 9 wording**

Replace every “local only,” “GitHub CI has not run,” “rerun pending,” and “push/PR pending” statement that is false after PR #232. Preserve these true boundaries: no Wave 9 deployment, no Production E2E, both frontend flags remain off, Wave 10 remains blocked.

- [ ] **Step 3: Reconcile prerequisite provenance**

Record that source `6a171558` contains `PortfolioResponse.updatedAt` and decimal string serialization, and that the tracked cu4 build evidence binds that source to digest `sha256:2be727eaf4577699c783ae66073670d4984fe66c666af3e56422c934fdd0b023`, recorded as serving revision `0000094`. State that this is provenance, not a fresh runtime read-back; do not schedule a duplicate 8.1 deployment from stale prose.

- [ ] **Step 4: Validate status changes**

Run:

```powershell
python -B scripts/tests/test_master_plan_status_propagation.py -v
python -B scripts/check_master_plan_status_propagation.py --base 318f28592da6ab2e3bd66bc738aa68d374b180fa --head HEAD --pr-body-file .wave8-reconciliation-pr-body.md
git diff --check
```

Create `.wave8-reconciliation-pr-body.md` with the exact intended PR-body declaration before running the guard, then remove the temporary file. Do not mark any open Wave 8 task complete in this reconciliation commit.

---

## Task 1: Resolve and Freeze the Decision Packet

**Owner:** Coordinator; Astra reviews; owner supplies final product/operational choices.

**Files:**

- Modify after decisions: `.kiro/specs/asset-picker-composition/requirements.md`
- Modify after decisions: `.kiro/specs/asset-picker-composition/design.md`
- Modify after decisions: `.kiro/specs/asset-picker-composition/tasks.md`
- Modify after decisions: `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`
- Create: `docs/superpowers/plans/2026-09-06-b2-wave8-decision-record.md`

- [x] **Step 1: Prepare the idle-threshold decision**

Present evidence and a recommendation for the exact duration. The record must define the source (`PortfolioResponse.updatedAt`), strict comparison boundary (below and equal are ineligible; strictly above is eligible), impact on shared-demo restoration, and how a production-threshold live proof earns eligibility without conflating it with presence TTL.

- [x] **Step 2: Prepare the timeout decision**

Present evidence and a recommendation for the eligibility-leg timeout, reset-leg timeout, and overall deadline. The record must state acceptable added login latency, expected cold/warm backend behavior, cancellation semantics, and the fact that every non-clean outcome remains fail-open. The provisional 2s/2s/4s values are candidates, not defaults.

- [x] **Step 3: Prepare the manual-reset placement decision**

Compare the existing page-level host with an in-picker control. Page-level placement preserves draft-free conflict presentation. In-picker placement requires the frozen-draft `ConflictPanel`, accessibility coverage, and E2E locator changes. This decision does not block Wave 8 source work but remains a Wave 10 exposure gate.

- [x] **Step 4: Disposition decimal sequencing**

Treat this as a historical compatibility audit. Compare actual frontend/backend deployment provenance, coordinate any violated ordering with B1 ownership, and preserve Task 2.6's numeric compatibility branch until a separate retirement decision. Do not declare the issue closed because PR #232 merged or because current backend serialization is correct.

- [x] **Step 5: Freeze implementation contracts**

After owner decisions, freeze configuration property names, the orchestration entry point, event fields, clock seam, loopback target seam, reset-target construction seam, and test topology. Recommended entry point:

```java
Mono<Void> afterLogin(com.wealth.gateway.auth.LoginResponse response);
```

It completes without changing the successful login response. Ordinary users complete without either self-call.

- [x] **Step 6: Obtain Astra ACCEPT**

Astra checks the four decision records against Requirement 7, D5, Task 8.2, Task 2.6, and Wave 10. No production behavior is committed until the threshold and timeout decisions are accepted and recorded.

- [x] **Step 7: Commit the shared configuration and test topology**

The coordinator alone adds the accepted configuration values, `spring.reactor.context-propagation: auto`, `org.springframework.boot:spring-boot-micrometer-tracing-test`, the dedicated `wave8IntegrationTest` source set, and `integrationTest` dependency wiring. Terra rebases onto this reviewed commit before transport or trace tests rely on it.

---

## Task 2: Implement Eligibility and Reset Transport

**Owner:** Terra.

**Files:**

- Create: `api-gateway/src/main/java/com/wealth/gateway/DemoLoginResetProperties.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/DemoLoginResetConfiguration.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/GatewayLoopbackTargetProvider.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/DemoLoginPortfolioObservation.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/DemoLoginResetClient.java`
- Test: `api-gateway/src/test/java/com/wealth/gateway/DemoLoginResetClientTest.java`

**Interfaces:**

- Consumes the existing `InternalApiKeyProvider`, `CloudFrontOriginSecretProvider`, `ReplicaTokenProvider`, observation-enabled `WebClient.Builder`, and resolved Task 8.2 values.
- Produces one eligibility observation containing the exact demo portfolio identity, persisted `updatedAt`, and version, plus a reset operation that submits that exact version once.

- [ ] **Step 1: Write transport RED tests**

Cover call-time `local.server.port`, bearer authorization, conditional origin header, exact single-demo selection, zero/multiple matches, malformed timestamp/shape, strict threshold boundaries, exact version POST, blank internal key with zero dispatch, every non-2xx family including redirect, connection failures, and per-leg timeouts.

- [ ] **Step 2: Implement the smallest nonblocking transport**

Build both clients from the injected observation-enabled builder. Read the loopback port at subscription/call time. Put synchronous URL/request construction in `Mono.defer(...)`. Keep the origin-header attachment observation at the finalized `ClientRequest` boundary because the existing gateway filter strips the header before downstream forwarding.

- [ ] **Step 3: Prove exact cardinality and call counts**

Every successful demo-authentication flow that reaches eligibility dispatch asserts exactly one eligibility GET. Ordinary-user, failed-authentication, and `eligibility_pre_dispatch` timeout flows assert zero eligibility GETs. Ineligible or failed eligibility produces zero reset POSTs. Eligible clean flow produces one reset POST using the same observed version. No reread and no retry are permitted.

- [ ] **Step 4: Mutation-check**

Temporarily select the first portfolio, change `>` to `>=`, reread before reset, or derive header evidence from provider configuration. Confirm the intended test fails, restore byte-for-byte, and rerun GREEN.

---

## Task 3: Implement Orchestration, Diagnostics, and Login Integration

**Owner:** Terra; `AuthController.java` has one owner.

**Files:**

- Create: `api-gateway/src/main/java/com/wealth/gateway/DemoLoginResetOrchestrator.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/DemoLoginResetDiagnostics.java`
- Modify: `api-gateway/src/main/java/com/wealth/gateway/AuthController.java`
- Inspect: `api-gateway/src/main/resources/application.yml` (coordinator-owned configuration commit from Task 1)
- Modify: `api-gateway/src/test/java/com/wealth/gateway/AuthControllerUniformErrorTest.java`
- Test: `api-gateway/src/test/java/com/wealth/gateway/DemoLoginResetOrchestratorTest.java`
- Test: `api-gateway/src/test/java/com/wealth/gateway/DemoLoginResetArchitectureTest.java`
- Test: `api-gateway/src/test/java/com/wealth/gateway/DemoLoginResetIntegrationTest.java`

- [ ] **Step 1: Write orchestration RED tests**

Cover successful demo login, ordinary user, failed authentication, eligibility failure, ineligible observation, reset conflict, reset failure, synchronous construction failures, both per-leg timeouts, and all five overall-timeout phases. Assert the original login response and uniform authentication errors remain unchanged.

- [ ] **Step 2: Add deterministic time measurement**

Inject a monotonic clock seam defaulting to `System::nanoTime`. Tests provide readings deliberately different from configured durations so emitting a constant timeout value cannot satisfy `elapsedMillis` assertions.

- [ ] **Step 3: Implement sequential fail-open composition**

Compose eligibility, strict idle check, and reset within the successful demo-login publisher. Apply per-leg timeouts and the overall deadline. Map all non-clean outcomes into skip diagnostics and then complete without changing login success.

- [ ] **Step 4: Emit the exact diagnostic contract**

Emit one trace-correlated `demo_reset_self_call_skipped` event for each non-clean skip, with the stable coarse reason vocabulary and the diagnostic fields required by Task 8.7. Clean idle-ineligible completion and clean success emit no failure event. Every induced failure branch asserts the exact inbound trace id plus its exact reason, request count, and branch-specific diagnostic fields. Keep configured/required, dispatched, and attached fields distinct. Preserve nullable fields when a request was never finalized.

- [ ] **Step 5: Prove cancellation and dual-event behavior**

After an overall deadline, release the between-leg construction seam and assert no late reset dispatch. Separately prove an already-dispatched reset may commit and coexist with the gateway timeout event; do not make event ordering part of the oracle.

- [ ] **Step 6: Mutation-check login isolation**

Temporarily move orchestration before authentication success or outside the fail-open boundary. Confirm tests catch ordinary-user calls or login `500`, restore, and rerun GREEN.

---

## Task 4: Implement Task 8.7a and Real-Chain Race Evidence

**Owner:** Terra implements; coordinator owns test classpath changes.

**Files:**

- Create: `api-gateway/src/test/java/com/wealth/gateway/DemoLoginResetTracePropagationIT.java`
- Create: `api-gateway/src/wave8IntegrationTest/java/com/wealth/gateway/DemoLoginResetRealChainIT.java`
- Reference: `portfolio-service/src/test/java/com/wealth/portfolio/demo/DemoResetIntegrationTest.java`
- Modify only through coordinator: `api-gateway/build.gradle`

- [ ] **Step 1: Implement real random-port trace propagation**

Start the actual gateway on `RANDOM_PORT` with `@AutoConfigureTracing(export = false)`, send a real login request with a unique `traceparent`, and point `app.routes.portfolio-url` at an outer-boundary HTTP stub. Capture both downstream requests and assert each carries the inbound trace id through the real loopback and gateway route table. The coordinator-owned build commit supplies `org.springframework.boot:spring-boot-micrometer-tracing-test`.

- [ ] **Step 2: Isolate and wire real-chain dependencies**

Use the dedicated `wave8IntegrationTest` source set/task whose classpath alone contains `project(':portfolio-service')`. Do not add MVC/JPA, a second application class, or duplicate resources to the ordinary gateway test classpath. Use explicit reactive/servlet bootstraps and narrow scans. Make the existing `:api-gateway:integrationTest` depend on `:api-gateway:wave8IntegrationTest`, so the root `integrationTest` command used by CI necessarily executes the real-chain task.

- [ ] **Step 3: Prove the real persistence and post-response races**

Use the portfolio-service integration discipline to prove four named cases: a committed reset whose response exceeds the reset-leg timeout; a committed reset whose response exceeds the overall deadline in `reset_in_flight`; a committed reset whose response is received before the overall deadline fires in `reset_post_response`; and a successful reset followed by a gateway-handler failure. Require real persisted version advancement and the real `demo_reset_succeeded` event alongside the gateway skip event where applicable. Assert the post-response timeout preserves the received HTTP status. An HTTP status stub cannot satisfy these cases.

- [ ] **Step 4: Prove the CI graph discriminates**

After every named real-chain case passes, temporarily make each one fail in turn and run the same root `integrationTest` graph used by CI. Require each mutation to fail in `:api-gateway:wave8IntegrationTest`, record collection evidence naming every test method, then restore the test byte-for-byte and rerun GREEN. A direct `wave8IntegrationTest` pass alone is insufficient.

- [ ] **Step 5: Run focused and complete gateway verification**

```powershell
.\gradlew.bat :api-gateway:test --tests "com.wealth.gateway.DemoLoginReset*" -x :api-gateway:jacocoTestReport --no-daemon
.\gradlew.bat :api-gateway:integrationTest --tests "com.wealth.gateway.DemoLoginReset*" -x :api-gateway:jacocoTestReport --no-daemon
.\gradlew.bat :api-gateway:wave8IntegrationTest --tests "com.wealth.gateway.DemoLoginResetRealChainIT" --no-daemon
.\gradlew.bat :api-gateway:test :api-gateway:integrationTest --no-daemon
.\gradlew.bat integrationTest --no-daemon
```

Tag acceptance tests `integration`; root Gradle collection is tag-based. The last command is the required CI-carrier verification.

---

## Task 5: Implement the Azure Digest Manifest CLI

**Owner:** Luna; independent of gateway source work.

**Files:**

- Modify: `.github/workflows/scripts/snapshot_container_apps.py`
- Modify: `scripts/tests/test_snapshot_container_apps.py`

- [ ] **Step 1: Write CLI RED tests**

Cover `aggregate-digests` parser wiring, no Azure environment requirement, exact selected-service coverage, one `digest.txt` per service directory, duplicate/missing/extra services, lowercase `sha256:[0-9a-f]{64}`, output writing, and missing selected refresh Job failure.

- [ ] **Step 2: Implement `aggregate-digests`**

Handle aggregation before Azure environment validation. Never call Azure capture in this command. Produce the exact manifest consumed by `compare --digest-manifest`.

- [ ] **Step 3: Tighten comparison**

Bind selected Container Apps and the selected market-data refresh Job to exact repository digests. A selected market-data deployment with a missing refresh Job fails rather than warning and skipping.

- [ ] **Step 4: Mutation-check artifact validation**

Temporarily accept uppercase/malformed digest, an extra service, or a missing Job. Confirm the focused test fails, restore, and rerun GREEN.

---

## Task 6: Implement Task 8.8b Workflow Evidence

**Owner:** Luna.

**Files:**

- Modify: `.github/workflows/deploy-azure.yml`
- Modify: `.github/workflows/ci-verification.yml`
- Modify: `.github/workflows/scripts/snapshot_container_apps.py` (normalization/aggregation helper)
- Modify: `scripts/tests/test_deploy_azure_service_allowlist.py`
- Modify: `scripts/tests/test_deploy_azure_prebuilt_digest.py`

- [ ] **Step 1: Write workflow graph RED tests**

Require workflow concurrency group `wealth-production-azure-deploy` with `cancel-in-progress: false`; one Buildx build/push step with a metadata file; strict digest validation; exact `service-digest-*` producer artifacts; an `aggregate-digests` job with `needs: [preflight, deploy]`; named `digest-manifest` consumer artifact; exact mode gates; current-attempt-only retrieval; missing-artifact failure; and “Re-run all jobs” guidance for unsupported partial reruns.

- [ ] **Step 2: Preserve protected invocation**

Keep `deploy-azure.yml` reusable-only. The parent `deploy.yml` retains `expected_main_sha`, Environment approval, and the production deployment lock. Add child workflow concurrency without reintroducing direct dispatch.

- [ ] **Step 3: Implement immutable normal-build deployment**

Replace separate normal build/push operations with Buildx `--push --no-cache --pull --metadata-file`. Validate `containerimage.digest`, expose it as an output, and update every selected App and the paired refresh Job by `repository@digest`. Preserve and test the prebuilt-digest branch and its proof that normal build/push was skipped.

- [ ] **Step 4: Implement disjoint artifact namespaces**

Each selected scoped-normal service uploads `service-digest-${{ matrix.service }}` containing `digest.txt` and `run-attempt.txt`, with overwrite and missing-file failure. Aggregation downloads the current run's `service-digest-*` artifacts without merge, runs `normalize-artifacts --digest-root <download> --staging-root <distinct-stage> --selected <json> --run-attempt <current>`, then runs `aggregate-digests --digest-root <distinct-stage> --selected <json> --output $RUNNER_TEMP/digest-manifest.json`. It uploads `digest-manifest` with its run-attempt marker and missing-file failure. Scoped comparison validates that marker and passes the runner-temp manifest to `compare --digest-manifest`; prebuilt mode uses `--requested-digest`; full mode keeps normal build/digest validation but runs neither scoped aggregation nor comparison.

- [ ] **Step 5: Update actionlint once**

Upgrade the existing active-CI actionlint installation from 1.7.7 to 1.7.12 and verify Linux-amd64 archive SHA-256 `8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8`. Do not add a second installation.

- [ ] **Step 6: Run workflow verification**

```powershell
python -B scripts/tests/test_snapshot_container_apps.py -v
python -B scripts/tests/test_deploy_azure_service_allowlist.py -v
python -B scripts/tests/test_deploy_azure_prebuilt_digest.py -v
```

Run the repository's pinned actionlint command against the changed workflows.

---

## Task 7: Implement the Azure Live-Proof Executable Offline

**Owner:** Luna; source and tests only until separate approval.

**Files:**

- Create: `scripts/verify_demo_reset_azure.py`
- Create: `scripts/tests/test_verify_demo_reset_azure.py`

- [ ] **Step 1: Write CLI RED tests**

Cover subscription/resource identity, exactly one serving revision per service, manifest agreement, escaped bounded KQL, unique trace generation, all five Task 8.9 event-query outcomes, and nonzero exit for every non-Go result. Assert zero writes, including cleanup, when target/identity preflight fails and throughout rehearsal. After mutation is armed, require exact version-bearing cleanup for every success, failure, timeout, and uncertain-response exit. Cleanup uses bounded retries, and every retry first performs a fresh identity/version read. Any cleanup `409` permanently preserves a failed run classification even if later recovery succeeds. A separate post-cleanup GET must prove exact persisted holdings against Task 4.4a's independent golden oracle. Tests must discriminate each condition and verify restoration of any threshold override.

- [ ] **Step 2: Implement a single fail-closed executable**

The program constructs and validates the Azure CLI/KQL operations. It must not print secrets. It records the immutable target, serving revisions/digests, effective decision values, trace window, request/version counts, event classifications, cleanup attempts, post-cleanup persisted holdings, and threshold-override restoration.

- [ ] **Step 3: Resolve the environment contradiction**

There is no separate staging tier. Define an explicit `--target` contract and a nonmutating CLI/RBAC rehearsal mode. Preflight and rehearsal perform zero writes and never invoke cleanup. Arm unconditional cleanup immediately before the first potentially mutating request, so an uncertain mutation outcome is still cleaned up. Test both the never-armed and armed boundaries. The source can be completed offline; production execution waits for an owner-approved target and scope.

- [ ] **Step 4: Verify offline**

```powershell
python -B scripts/tests/test_verify_demo_reset_azure.py -v
python -B scripts/tests/test_check_b2_demo_identity.py -v
python -B scripts/check_b2_demo_identity.py
```

---

## Task 8: Consolidate and Commission Independent Review

**Owner:** Coordinator.

- [ ] **Step 1: Review lane commits before integration**

Reject any defaulted open decision, blocking call, detached subscription, second GET, retry, first-element identity selection, provider-derived attachment assertion, singleton timing state, direct-stub shortcut, workflow substring-only assertion, digest tag comparison, optional artifact, or self-approved evidence.

- [ ] **Step 2: Integrate reviewed commits serially**

Cherry-pick Wave 9 reconciliation, accepted decision records, Terra gateway commits, and Luna tooling commits onto the coordinator branch. Resolve shared config/build changes through the coordinator only. Rerun each focused suite after its integration.

- [ ] **Step 3: Run consolidated verification**

Run the complete gateway unit/integration graph, the dedicated Wave 8 source set if created, all four Python workflow/proof suites, pinned actionlint, master-plan propagation tests, `git diff --check`, and any root test gate affected by build-graph changes. Do not run concurrent Gradle graphs against shared Docker resources.

- [ ] **Step 4: Require fresh Astra review**

Astra reviews frozen decisions, spec compliance, source architecture, fail-open scope, trace causality, timeout discrimination, real-chain races, workflow graph, digest/artifact exactness, proof CLI, ledger truth, and change scope. Route each finding back to its owning lane and repeat until ACCEPT or a concrete blocker exists.

- [ ] **Step 5: Update ledgers to the evidence actually earned**

Mark Tasks 8.2 and source tasks complete only after their decisions/tests/review exist. Task 8.8b source may be complete while its deployment Go remains open. Do not mark 8.8 or 8.9 complete from local tests. Preserve Wave 10 and production flags-off boundaries.

- [ ] **Step 6: Prepare the publication packet**

Write a handoff with baseline/head, commit map, exact RED/GREEN/mutation evidence, test counts, review rounds, open dependencies, and exact PR body. Run:

```powershell
python -B scripts/tests/test_master_plan_status_propagation.py -v
python -B scripts/check_master_plan_status_propagation.py --base 318f28592da6ab2e3bd66bc738aa68d374b180fa --head HEAD --pr-body-file .wave8-pr-body.md
git diff --check
```

Stop for owner approval before push or PR creation.

---

## Task 9: Task 8.8 Production Deployment STOP/GO

**Owner:** Coordinator prepares; owner authorizes; Astra reviews evidence.

- [ ] **Step 1: Build the concrete deployment packet**

Include the exact merged main SHA, selected service set, immutable digests, decision values, workflow inputs, protected Environment gate, concurrency behavior, rollback target, expected revision changes, and post-deploy read-back commands. Inventory every behavior in the selected gateway artifact. Because current merged `main` already contains Wave 5's public reset/allowlist bundle, require the outstanding Task 5.6 owner GO for a combined gateway deployment, or prepare an explicitly reviewed artifact that excludes that bundle. Complete all preparation before requesting approval.

- [ ] **Step 2: Request explicit deployment authorization**

PR merge or source acceptance does not authorize registry access, workflow dispatch, Azure changes, demo-login mutation, or shipment of the merged Wave 5 gateway behavior. Record both the Task 8.8 authorization and, when the artifact contains it, the Task 5.6 authorization before dispatch.

- [ ] **Step 3: If authorized, run one scoped deployment**

Require the selected Apps and selected refresh Job to be digest-qualified, the manifest comparison to pass, and exactly one serving revision per service. Record the workflow run, attempts, images, revisions, effective threshold/timeouts, and rollback target.

- [ ] **Step 4: Apply the Go/Abort rule**

A green source branch or workflow graph is insufficient. If immutable digest/revision evidence or comparison fails, stop before Task 8.9 and prepare the diagnosed repair/rollback packet.

---

## Task 10: Task 8.9 Live Serving Proof STOP/GO

**Owner:** Coordinator prepares; owner authorizes; Astra independently reviews.

- [ ] **Step 1: Check external prerequisites**

Require Task 8.8b deployment evidence, deployed Wave 5 routes/allowlist/manual reset cleanup, and B1 Wave 7's public composition endpoint. If B1 R-C remains NO-GO, stop with a readiness packet; do not deploy current `main` around the gate.

- [ ] **Step 2: Build the exact live-proof packet**

Specify the approved Azure target, nonmutating rehearsal result, public/API endpoints, exact serving digests/revisions, real production threshold, unique trace, bounded KQL window, deliberately non-golden setup with version advancement, expected success/skip events, independent golden oracle, and unconditional cleanup.

- [ ] **Step 3: Request separate live-proof authorization**

The Task 8.8 deployment approval does not authorize Task 8.9's production API writes, login, log queries, or cleanup.

- [ ] **Step 4: If authorized, execute and classify**

Run the tested executable, preserve raw bounded evidence without secrets, query for both success and skip events, verify exact identity/version/call counts, and run unconditional version-bearing cleanup. An override-backed threshold run earns diagnostic evidence only and cannot satisfy Wave 10 exposure.

- [ ] **Step 5: Update status only after independent review**

Astra verifies the evidence against Task 8.9's classification and Go/Abort rules. Only then update Task 8.9. Production frontend flags remain off; Wave 10 exposure requires separate work and approval.

---

## Completion Definition for This New Session

The session is successful when it has:

1. Reconciled Wave 9's merged/CI-green truth without claiming deployment.
2. Produced owner-approved threshold and timeout values plus reviewed placement and decimal dispositions.
3. Completed and independently reviewed Tasks 8.3-8.7a source and tests.
4. Completed and independently reviewed Task 8.8b source/tooling and the offline Task 8.9 verifier.
5. Prepared concrete Task 8.8 and 8.9 packets, executing either only after its own explicit authorization.
6. Left Wave 8 truthfully partial if B1 R-C or deployed Wave 5 prerequisites still block live serving proof.
7. Left both frontend flags off and made no Wave 10 exposure claim.
