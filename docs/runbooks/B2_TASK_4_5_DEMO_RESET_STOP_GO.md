# B2 Task 4.5 demo-reset STOP/GO evidence

**Verdict: GO — successful B1-conformant no-op.**

- **Executed:** 2026-09-01
- **Reviewed by:** Codex, senior architect/reviewer
- **Operator:** Claude
- **Kickoff:**
  [`CLAUDE_KICKOFF_B2_TASK_4_5_DEMO_RESET_STOP_GO.md`](../agent-instructions/CLAUDE_KICKOFF_B2_TASK_4_5_DEMO_RESET_STOP_GO.md)
- **Deployment run:**
  [33524223884](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33524223884)

## Scope and provenance

Task 4.5 deployed only the historical B2 Wave 4 portfolio-service cut. It did not deploy the later
Task 5.1a, 5.1b, 8.1, or 8.2a runtime changes, any frontend, or any other service.

| Evidence | Value |
|---|---|
| Kickoff commit | `19f6b053e5f7ad201ffd7495a6dfc7120722d0cc` |
| Deployment workflow commit | `67e55cf2c3b90d60149a79b084686d348ab9ba5e` |
| Candidate source cut | `63fc0584ad307af7f50e9500f4911ac5999d6b76` |
| Candidate worktree | Clean, detached, and pinned to the candidate source cut |
| Risk-path drift after kickoff | None before execution |
| Prior portfolio-service digest | `sha256:d5693e29c68fd3665366fbd83586b9e8b8a266b993cdd66a761ea17d9312092a` |

## Local and structural verification

| Check | Result |
|---|---|
| `:portfolio-service:test` | 486 tests, 0 failures/errors |
| `:portfolio-service:integrationTest` | 175 tests, 0 failures/errors |
| Combined portfolio-service verification | 661 tests, 0 failures/errors; build successful |
| Independent golden-state oracle tests | 12 passed |
| Candidate structural assertions | 7/7 passed |
| Deployment workflow-contract tests | 90/90 passed |

The portfolio-service tests include the mutating reset path against deliberately non-golden
fixtures. The live probe below encountered the separate, equally valid same-state no-op path.

## Immutable candidate build

ACR build run `cu3` succeeded without rebuilding after failure or retagging another manifest.

| Field | Value |
|---|---|
| Tag | `b2-task-4-5-63fc0584-20260901T144653Z` |
| Digest | `sha256:9a1d55335b83b97967e434d374c7f5f5ca79ea2adccad8f8e518b674e9a39f47` |
| Created | `2026-09-01T14:58:15.7942093Z` |
| Platform | Linux / amd64 |
| Manifest size | 314,499,296 bytes |

## Digest deployment

Run 33524223884 dispatched the current `main` workflow with `deployment_mode=digest`, selected only
`portfolio-service`, and supplied the immutable digest above. `validate`, production authorization,
routing, Azure preflight, `deploy (portfolio-service)`, and `assert-scoped-non-interference` all
succeeded. ACR login, Docker build, and Docker push were skipped and the digest-path assertion
succeeded. The frontend, seed, verify, AWS deployment, and all unselected service jobs were skipped
or preserved as required.

The resulting sole serving revision was `portfolio-service--0000093`, using the exact requested
digest with 100% traffic in Single revision mode. The gateway, market-data service, insight service,
and refresh job were unchanged.

## Controlled live probe

The first entry into B4 stopped before the reset write because the new revision's control-plane
health became green before its authenticated read path was ready. No reset was attempted in that
entry. This records a runbook timing gap: after revision cutover, B4 needs an explicit read-readiness
gate rather than treating control-plane health alone as sufficient.

After the revision was read-ready, the repository owner authorized one fresh B4 attempt. Exactly
one `PUT /api/internal/portfolio/demo-reset` was sent. It returned `200`.

The earlier read-only A7 preflight had already shown the same 159/159 wire-visible golden holdings,
so a same-state result was predictable before B4. Its version was discarded as required and was
not reused for the reset; B4 captured a fresh version from its own single pre-reset read.

| Assertion | Result |
|---|---|
| Pre-reset authenticated `GET /api/portfolio` | `200`; exactly one demo portfolio; observed version `0` |
| Reset attempts | Exactly 1; never retried |
| Reset trace id | `d43c8c380acb3f0006e918ac040d0203` |
| Portfolio id and `createdAt` | Preserved |
| `userId` | `00000000-0000-0000-0000-0000000d3110` |
| Quantity wire type | JSON string for every holding |
| Independent golden holdings | Exact match, 159/159 |
| Resulting version | `0`, unchanged |
| Post-reset authenticated read | Matched the reset response |
| Data mutation | None; the same-state path returned before parent transition or child DML |
| Rollback | Not performed and not required |
| Secrets read from Azure control plane | No |
| Secret values exposed | No |

## Corrected version interpretation

The kickoff originally required `expectedVersion + 1` for every successful reset. That contradicted
B1's frozen contract. The correct rule is:

- when the replacement changes the persisted tuple, return `200` with `version = expectedVersion + 1`;
- when the expected version matches and the persisted tuple is already golden, return `200`
  idempotently with the version and `updated_at` unchanged.

The live portfolio was already in the golden state, so the observed unchanged version is the
required no-op outcome, not a failed mutation. No wire-level `noOp` field is needed: the caller
already knows the submitted expected version, and the unchanged result expresses the no-op.

## Final state and boundaries

At probe time the candidate revision was Healthy/Running with one replica. A later independent
read-back found the same sole active revision and digest Healthy at 100% traffic and normally
`ScaledToZero`, consistent with its configured idle scaling. `api-gateway--0000077`,
`market-data-service--0000079`, and `insight-service--0000079` remained the active peer revisions.
The prior portfolio-service digest remains available in ACR but is not serving.

This GO closes only B2 Task 4.5 and satisfies Wave 5's Wave 4 prerequisite. It does not authorize
Wave 5 implementation or deployment, expose the internal reset endpoint to users, complete Wave 3
Task 3.7, or claim that any unrelated B2 source is deployed.
