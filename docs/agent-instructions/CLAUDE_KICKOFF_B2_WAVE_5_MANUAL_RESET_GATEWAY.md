# B2 Wave 5 Manual-Reset Gateway Implementation Plan

> **Historical kickoff — source work completed (2026-09-02).**
> Claude implemented the six source tasks in [PR #212](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/212),
> merged at `main@d8fa499de05fa1370a0271c4822230a6ea113695`. Final-head CI passed on
> `01917e16`, and the merged tree is identical. Codex reconciled source completion in the
> [owning ledger](../../.kiro/specs/asset-picker-composition/tasks.md).
> Task 5.6's seven technical conditions are met; its separate owner GO decision is pending.
> The original instructions and checklists below are preserved as the execution scope, not a
> current to-do list or authorization to repeat implementation, merge, deploy, or expose the feature.

> **For agentic workers:** Use `superpowers:executing-plans`, if available, to execute this
> kickoff in sequence. Follow the repository instructions and frozen B2 contracts first.
> Claude owns implementation; Codex owns architecture review and durable status documentation.

**Goal:** Complete B2 Tasks 5.1–5.5 as one source change so an authenticated demo user can reach
the existing internal reset endpoint through the public gateway route.

**Architecture:** A narrowly scoped authorization filter validates the JWT subject and matched
route before replacing request credentials. A dedicated route rewrites only the path. The existing
read-only filter gains the exact method/path exceptions while preserving its configurable AI
exceptions. Both existing shared providers are reused.

**Tech Stack:** Java 21, Spring Cloud Gateway WebFlux, Spring Security resource server, Reactor,
JUnit/AssertJ, Gradle, Testcontainers, Python standard-library checks, and GitHub Actions.

**Spec:** [B2 requirements](../../.kiro/specs/asset-picker-composition/requirements.md),
[B2 design](../../.kiro/specs/asset-picker-composition/design.md), and
[B2 tasks](../../.kiro/specs/asset-picker-composition/tasks.md), especially requirements 5.1–5.2
and 7.3a, design D5/D6, GC.6/GC.7/GC.10, Tasks 5.1–5.5 and acceptance gate 5.6.

## 1. Assignment, complexity, and boundaries

- **Prepared:** 2026-09-02 by Codex.
- **Implementer:** Claude; **reviewer and durable documentation owner:** Codex.
- **Complexity assessment:** medium-high. The design is settled, but authorization, route selection,
  reactive execution, response-header ownership, profile replacement, and regression protection
  cross several gateway mechanisms. Keep one implementer responsible for the whole bundle.
- **Verified baseline:** `main@a2c402db1779e515ccc56c16a900ec172864a670`, PR #211 merged.
  No open PRs were present when the kickoff was prepared; recheck before editing.
- **Source implementation scope:** Tasks 5.1, 5.2, 5.3, 5.3a, 5.4, and 5.5, their tests, and the
  required demo-identity CI guard. Assess readiness against 5.6; do not silently close that gate.
- The owner selected this implementation lane by requesting the appropriate implementer's kickoff.
  Codex has prepared the work; delivery of this note does not mean implementation has run.
- Work in `D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-claude`.
  Read that worktree's `AGENTS.md` and `CLAUDE.md`; verify the top level before every mutation.
- Suggested branch: `claude/b2-wave-5-manual-reset-gateway`, created from freshly checked
  `origin/main`. Preserve existing branches and worktrees. Do not use the IntelliJ, Cursor, or
  Codex checkout for implementation; do not create a nested worktree.
- Local implementation, tests, normal PR CI, and an implementation PR for review are in scope.
  Stop before merge, auto-merge, production deployment, live reset probes, secret retrieval, or
  cloud configuration changes. No deployment workflow dispatch is authorized.
- No frontend changes, feature-flag enablement, Wave 8 orchestration, presence activation,
  portfolio-service implementation, migrations, Dockerfile/provider refactors, new dependencies,
  or broad CI optimization. The existing Task 5.1a/5.1b packaging and smoke contract must survive.
- Codex owns edits to the master plan, owning ledger, and lasting evidence documents. Claude
  returns implementation evidence for Codex to incorporate into the same implementation PR.

## 2. Predecessors and first read

Read the [master plan](../plans/ASSET_PICKER_E2E_MASTER_PLAN.md) first, then the owning spec sections
above and the [Task 4.5 evidence](../runbooks/B2_TASK_4_5_DEMO_RESET_STOP_GO.md).

The required predecessors exist:

| Predecessor | Evidence and meaning |
|---|---|
| Wave 4 / Task 4.5 | Historical cut `63fc0584` serves internally on `portfolio-service--0000093`, digest `sha256:9a1d55335b83b97967e434d374c7f5f5ca79ea2adccad8f8e518b674e9a39f47`; reviewed live GO |
| Task 5.1a | `InternalApiKeyProvider`, probe packaging, and Azure image smoke merged through PR #202 / `64761dc2` |
| Task 5.1b | `ReplicaTokenProvider`, formula/tool, packaging, and CI extension merged through PR #208 / `f954b5a7` |

Task 4.5 exercised a correct same-state no-op: exact 159/159 wire holdings and version 0 unchanged.
It did not prove a mutating live reset. The existing portfolio-service integration tests own that
persistence proof. Gateway stub tests in this task prove transport, authorization, and passthrough;
do not relabel them as real portfolio persistence tests.

Tasks 5.1a/5.1b/8.1/8.2a are merged source-only and are not part of that historical deployed cut.
B1 Task 5.7/G5 still awaits its separate owner completion decision; B1 Waves 6–7 remain blocked.
This does not block this B2 gateway source bundle.

Four product/operational decisions remain open: idle threshold, login self-call timeouts,
manual-reset control placement, and decimal-adapter deployment sequencing. None needs a new
decision for these gateway source tasks; do not resolve them by inventing defaults.

- [ ] Inspect worktree status, fetch current main, record exact base SHA, and check open PRs.
  Continue from a clean branch only; preserve unrelated work.
- [ ] Confirm the baseline is an ancestor of the chosen base and compare changed relevant paths.
  Docs-only evolution is routine; an overlapping filter/route/provider/contract change requires
  reconciliation before editing. Do not reject unrelated main changes automatically.
- [ ] If this kickoff is not yet on main, read the supplied Codex copy directly. Do not copy the
  whole Codex documentation branch or wait for a separate docs merge merely to read it.
- [ ] Confirm Task 5.1 is still absent and no other agent owns this bundle.

Commands, run from the assigned Claude worktree:

```powershell
git rev-parse --show-toplevel
git status --short --branch
git fetch origin main
git rev-parse origin/main
git merge-base --is-ancestor a2c402db1779e515ccc56c16a900ec172864a670 origin/main
gh pr list --state open --json number,title,headRefName
git switch -c claude/b2-wave-5-manual-reset-gateway origin/main
```

Run the branch command only after the preceding checks succeed.

## 3. File map and existing interfaces

Paths in this note are repository-relative.

| Action | File | Responsibility |
|---|---|---|
| Create | `api-gateway/src/main/java/com/wealth/gateway/DemoResetAuthorizationFilter.java` | Exact request scope, route/JWT authorization, credential replacement, authoritative replica response header |
| Modify | `api-gateway/src/main/java/com/wealth/gateway/ReadOnlyEnforcementFilter.java` | Add exact B2 method/path exceptions; preserve configurable AI exceptions |
| Modify | `api-gateway/src/main/resources/application.yml` | Local/profile-neutral reset route |
| Modify | `api-gateway/src/main/resources/application-prod.yml` | Independent production route with standard rate limiter |
| Create | `api-gateway/src/test/java/com/wealth/gateway/DemoResetAuthorizationFilterTest.java` | Isolated filter branch, order, and subscription tests |
| Create | `api-gateway/src/test/java/com/wealth/gateway/DemoResetRoutingIntegrationTest.java` | Real local-profile gateway chain and recording downstream stub |
| Create | `api-gateway/src/test/java/com/wealth/gateway/DemoResetProductionRoutingIntegrationTest.java` | Real prod/azure-profile route, limiter wiring, and response-header ownership |
| Modify | `api-gateway/src/test/java/com/wealth/gateway/ReadOnlyEnforcementFilterPropertyTest.java` | Exact exception matrix and existing overlapping AI pattern |
| Modify if needed | `api-gateway/src/test/java/com/wealth/gateway/ReadOnlyEnforcementFilterChainTest.java` | Preserve single-subscription behavior |
| Create | `scripts/check_b2_demo_identity.py` | Compare the actual three source identity literals |
| Create | `scripts/tests/test_check_b2_demo_identity.py` | Detect mismatches, missing/ambiguous definitions, and source-selection errors |
| Modify | `.github/workflows/ci-verification.yml` | Run identity checker and its tests in existing required static-guard |

Small test-only fixture helpers under the existing gateway test package are acceptable where the
two profile tests share setup. Avoid a generic filter or authorization framework.

Read-only source anchors:

- `InternalApiKeyProvider.java`: constructor-injected bean; package-visible `String value()`
  and `boolean isConfigured()`; null and all Java-blank values mean unavailable.
- `ReplicaTokenProvider.java`: constructor-injected bean; package-visible `String replicaToken()`.
  It returns the derived opaque token or the empty sentinel. The fixed synthetic vector
  `api-gateway--0000000-abcdefg` produces `95ca17821ade`.
- `JwtAuthenticationFilter.java`: reads the authenticated principal at order
  `HIGHEST_PRECEDENCE + 2`, injects `X-User-Id`, and preserves Authorization on the original
  public path. Its internal-path bypass does not apply before the rewrite.
- `ReadOnlyEnforcementFilter.java`: order `HIGHEST_PRECEDENCE + 3`; existing configuration is
  `app.read-only.ai-allowlist` with path-pattern defaults
  `/api/chat/**,/api/insights/generate/**`.
- `SecurityConfig.java`: ordinary `/api/**` stays JWT-authenticated. Do not add a public
  permitAll exception for this route.
- `JwtFilterIntegrationTest.java`, `ProductionRateLimitingIntegrationTest.java`, and
  `TestJwtFactory.java`: established Spring contexts, JWT fixtures, local HTTP stub, and container
  setup. Use a real successful stub response here; a mere “not 401” assertion is insufficient.
- `portfolio-service/src/main/java/com/wealth/portfolio/seed/DemoPortfolioInitializer.java` and
  `portfolio-service/src/main/resources/db/migration/V15__Reconcile_Auth_Seed_Users.sql`:
  read-only identity sources. The demo UUID is `00000000-0000-0000-0000-0000000d3110`.
- Root `build.gradle`: `@Tag("integration")` selects the integration task. Do not accidentally
  run a new routed test in neither suite.

## 4. Implementation sequence and acceptance

Build and test the pieces in sequence, but keep the filter, route, and allowlist in one final source
commit and one complete PR. The filter, route, and
allowlist entry must land together. Do not merge partial PRs for 5.1, 5.2, or 5.3.

### A. Authorization and response ownership — 5.1, 5.3a, 5.5

- [ ] Write focused failing tests for the branch matrix below; establish meaningful RED evidence.
- [ ] Implement the filter in the existing gateway package. Inject both existing provider beans;
  no raw-key constructor on the filter, new environment reader, secret in YAML, or provider copy.
- [ ] Match only `(PUT, /api/portfolio/demo-reset)`, using the original path before RewritePath.
  Every other request passes through without this filter adding the internal key or replica token.
- [ ] For that pair, validate the principal's JWT subject directly and require matched route id
  `demo-reset-manual` from `GATEWAY_ROUTE_ATTR`. Never use `GATEWAY_PREDICATE_ROUTE_ATTR`.
  Missing/wrong route or a non-demo subject fails closed with no downstream subscription.
- [ ] After successful authorization, obtain the provider value. Null, empty, or any
  `String.isBlank()` value yields the pinned 503; preserve non-blank key bytes exactly.
- [ ] Remove all Authorization and X-User-Id values; replace every caller-supplied
  X-Internal-Api-Key value with exactly one provider value.
- [ ] Return exactly the pinned JSON object shapes, with JSON content type:

  - 403: `{"error":"demo_reset_forbidden","message":"Only the demo account may reset the demo portfolio."}`
  - 503: `{"error":"internal_api_key_not_configured","message":"The demo reset feature is temporarily unavailable."}`

  Use the same fail-closed 403 for missing/wrong matched route. Do not invent a new diagnostic
  envelope or expose route/configuration details.
- [ ] Assert order equals `Ordered.HIGHEST_PRECEDENCE + 4`.
- [ ] Set X-Gateway-Replica-Token on every outcome produced by this filter, including route
  rejection, subject rejection, missing-key rejection, and the downstream response path.
  Use the injected provider's opaque token; never return the raw replica name.
- [ ] Make the response header authoritative at response commit, after downstream headers can
  have been copied. Register the callback before proceeding; replace all existing values.
  The real transport test must prove this ordering rather than assuming an early header set wins.
- [ ] Resolve authorization to a value before branching into Mono<Void>. Preserve exactly one
  downstream subscription for allowed requests and zero for rejections; do not reproduce the
  existing documented switchIfEmpty/Mono<Void> double-subscription pitfall.
- [ ] Rerun the focused tests and record GREEN evidence.

### B. Routes and read-only compatibility — 5.2, 5.3, GC.7

- [ ] Add failing tests for exact B2 exceptions and preservation of custom AI patterns.
- [ ] Add `demo-reset-manual` in both route lists: existing portfolio URL, explicit `order: -1`,
  exact Path predicate, `Method=PUT`, RewritePath to `/api/internal/portfolio/demo-reset`.
  The HTTP method remains PUT. Leave the request body and expectedVersion unchanged.
- [ ] In production, include the existing standard RequestRateLimiter with
  `userOrIpKeyResolver`, `standardRateLimiter`, and `retry-after-seconds: 1`.
  The production list replaces the base list; a base-only change cannot satisfy this task.
- [ ] Add exactly `(PUT, /api/portfolio/holdings)` and `(PUT, /api/portfolio/demo-reset)` as B2
  read-only exceptions. Retain the external AI configuration key and each existing configured
  pattern's behavior for POST, PUT, PATCH, and DELETE. No new exception for a path prefix.
- [ ] Preserve the intent of
  `aiAllowlistExemptionAppliesEvenWhenItOverlapsAProtectedPrefix`. Extend coverage to every
  mutating method for an overlapping configured AI pattern. Keep its non-overlapping negative case.
- [ ] Adjust property expectations only for the two specified PUT exceptions; keep the protected
  writes and ordinary reads covered. Include trailing-slash/child-path and wrong-method negatives.
- [ ] Verify composition PUT receives no internally injected key from the new filter, ordinary
  authenticated reads still pass, and other protected writes retain existing behavior.
- [ ] Do not parse/rebuild the reset body, issue a portfolio GET, add retries, convert PUT to POST,
  add a portfolio-service public controller, or implement the B1 composition write endpoint.

### C. Real routed proof — 5.4 and the complete filter contract

- [ ] Create real HTTP gateway tests under local and prod/azure profiles using the production
  route definitions. Override destination URLs and test credentials; do not replace the route
  definitions or the filters under test with a hand-built test chain.
- [ ] Use a recording HTTP portfolio stub, signed test JWTs, and provider doubles configured with
  a non-blank test key and token `95ca17821ade`. A helper double must satisfy the real provider
  methods the consumer invokes. No environment mutation or extra mocking dependency.
- [ ] Observe matched GATEWAY_ROUTE_ATTR through a test-only passive probe; prove the selected id
  is demo-reset-manual. The real rewrite and downstream capture must also succeed.
- [ ] Exercise the matrix below. Assert no downstream request on each rejected case and exact
  call counts on accepted cases. Assert the token's exact value and exactly one header value for
  filter-owned responses, including downstream conflicts/errors.

| Case | Required result |
|---|---|
| Demo JWT with ro=true, correct route, configured key | Stub reached once; no read-only 403 |
| Downstream capture | Exact internal path, PUT method, unchanged request bytes/expectedVersion, no Authorization/X-User-Id, one authoritative internal key |
| Duplicate malicious incoming internal-key values and spoofed X-User-Id | Neither reaches the stub; trusted replacement is singular |
| Authenticated non-demo subject, with either ro value | Pinned 403, zero downstream calls |
| Missing/invalid JWT through actual security chain | 401, zero downstream calls; token header is not required from a filter the request never reaches |
| Missing route attribute / wrong route id in isolated filter test | Rejected with 403, zero downstream calls, exact token |
| Null, empty, ASCII whitespace, Unicode-blank provider values | Exact two-field 503, zero downstream calls, exact token |
| Stub supplies conflicting/duplicate replica-token headers | Gateway token wins, exactly once, on 200 and non-200 responses |
| Stub returns 409 or 503 with a diagnostic body | Status/body preserved, one call, no retry or version read, authoritative token |
| Other method/path, including PUT holdings | This filter adds no internal key; existing authorization behavior remains |
| Blank replica-token provider sentinel | Preserve the blank contract; never expose/hash a raw name in this filter |

The two-field 503 is load-bearing for later Wave 8 diagnostics. A single-field upstream 503 must
pass through as upstream output; do not normalize it into the gateway's configuration-failure body.

- [ ] For prod/azure, verify the resolved reset route owns the standard limiter and retry metadata.
  Use the existing real-Redis test pattern to demonstrate the reset route is rate limited and
  internal routes remain exempt. Do not weaken existing rate-limit tests to accommodate the route.
- [ ] Record gateway transport evidence separately from the existing Task 4.4 persistence proof.
  No local or cloud reset against a shared demo account is necessary.

### D. Three-source identity alignment in required CI — 5.1

- [ ] Write checker tests against temporary fixture files. Require an unambiguous extraction from
  the new filter's DEMO_USER_ID literal, the initializer's DEMO_USER_ID literal, and V15's
  `INSERT INTO users` row for `demo@wealthtracker.dev`.
- [ ] The checker must compare those actual source values, not three constants inside its own
  code or the first UUID found anywhere in each file. Do not match a comment, credentials row,
  developer user, or E2E user instead of V15's demo users row.
- [ ] Test each independent mismatch, both Java copies drifting together away from V15, missing
  files/literals, ambiguous definitions, and unrelated UUIDs/comments. Fail clearly with nonzero
  exit. Keep this a small standard-library text checker, not a cross-module Java dependency.
- [ ] Run both the checker tests and checker itself in the existing required static-guard job:

```powershell
python scripts/tests/test_check_b2_demo_identity.py -v
python scripts/check_b2_demo_identity.py
```

- [ ] Keep the CI classifier, aggregate dependencies, and Azure probe smoke cases intact.
  Run the existing pinned Actionlint command for the changed workflow. Do not optimize the DAG.

## 5. Verification and review packet

- [ ] During development, run focused tests first and record meaningful RED/GREEN evidence.
  A setup failure does not prove the intended regression; explain any initial missing-class failure.
- [ ] Run complete gateway suites and the existing artifact builds from the implementation tree:

```powershell
.\gradlew.bat :api-gateway:test :api-gateway:integrationTest --no-daemon
.\gradlew.bat :api-gateway:bootJar :api-gateway:probeJar :api-gateway:replicaTokenJar --no-daemon
python scripts/tests/test_check_b2_demo_identity.py -v
python scripts/check_b2_demo_identity.py
python scripts/tests/test_master_plan_status_propagation.py -v
python scripts/tests/test_classify_changed_paths.py -v
git diff --check
git status --short
```

Use the Unix wrapper equivalent if running under that shell. Docker is needed for the container
tests. Inspect reports and actual counts; NO-SOURCE, zero tests, or skips do not prove acceptance.
Do not run full portfolio-service tests solely to re-prove unchanged persistence code.

- [ ] Open one draft implementation PR for Codex review, with exactly one impact declaration:

```text
Master-plan impact: updated — B2, process
```

  The required identity CI check is the process change. Codex supplies the matching master-plan
  and B2-ledger edits on the implementation branch before the PR is ready to merge. This PR is
  intentionally not merge-ready until those governed edits and the status-propagation guard pass.
  Give Codex explicit permission to make only those documentation edits in Claude's worktree.
- [ ] Keep Tasks 5.1–5.5 unchecked while unmerged; describe implementation as implemented but
  unmerged when evidence supports it. Codex records source completion after merge.
  Do not check 5.6 automatically or claim a production deployment from green source tests.
- [ ] Require final PR-event CI with `docs_only=false`, existing required checks successful,
  `azure-image-smoke-test=success`, and `ci-required=success`. The existing smoke must still
  cover blank/non-blank internal-key probe cases and the replica-token tool vector.
  Cite its actual run; a local jar build alone cannot satisfy the image gate.
- [ ] If relevant main/source changes arrive before review, reconcile and rerun affected checks.
  Stop for Codex if a frozen contract change, production action, provider rewrite, new dependency,
  or unrelated implementation is necessary. Report the exact issue and completed work.
- [ ] Return: base/branch/head SHA, PR URL, changed-file list, focused/full test counts and
  RED/GREEN evidence, profile-specific transport/limiter results, identity guard results, artifact
  build results, CI run/check status, and any remaining blocker.
- [ ] Stop at review with the complete source bundle available. No merge, deployment, feature
  exposure, Task 3.7 completion, B1 G5 completion, or Wave 8 work is implied.
