# B1 Wave 7 (7.1–7.2) review evidence

> **OWNER APPROVAL REQUIRED:** Push / PR publication remain gated. This packet does **not**
> authorize push, PR open, merge, deployment, or public `PUT` activation. Owner approval is a
> separate decision from Codex ACCEPT of local source.

> **R-B3 MERGE HOLD:** R-B3 remains pinned to `6a171558a0f802eadd5d7ed5bf28545ca5c91905`.
> The Wave 7 controller must **not** enter the R-B3 source/image cut. Do not merge or
> cherry-pick this branch into R-B3 materials. G2b/R-B3 serving proof remains a separate gate.

## Isolation
- Worktree: D:\Projects\Development\Java\Spring\wealthmgmtandportfoliotracker-cursor-b1-wave7
- Branch: cursor/b1-wave-7-public-composition
- Base: origin/main @ 9c2ebc1233801253a3e54b6e930e28e1a00ebf3d
- R-B3 pin untouched (docs still cite 6a171558); controller not in any R-B3 cut materials
- Tasks 7.1/7.2 checkboxes left unchecked pending Codex re-review after P2 corrections
- Task 6.5 remains in its separate thread — not modified here
- No PR / push / merge / deploy

## RED / GREEN
- RED: **unavailable as a captured failure log.** The initial RED was a compile failure
  (`CompositionControllerTest` missing production `CompositionController` /
  `CompositionWriteService`). No dedicated RED stdout/stderr artifact was retained in
  `audit/b1-wave7/`. Do not treat the GREEN logs below as RED evidence.
- GREEN: CompositionControllerTest, CompositionWriteServiceTest, CompositionControllerIT,
  CompositionWriteServiceIT — see refreshed logs after P2 corrections

## Fresh suite counts (portfolio-service)
- P2 focused unit (`*Composition*Test` + `*PortfolioControllerTest`): tests=88 failures=0 errors=0 skipped=0
  — see `08-p2-composition-unit-tests.txt`
- P2 focused IT (`CompositionControllerIT` + `CompositionWriteServiceIT`): tests=19 failures=0 errors=0 skipped=0
  — see `09-p2-composition-integration-tests.txt` (was 12 CompositionControllerIT cases; +6 HTTP
  cases +1 projection-rollback IT)
- Prior full-suite reference (pre-P2 packet): unit=548 / IT=201 / gateway filter chain=5 with zero fails;
  full re-count not re-run in this correction pass — expect IT total ≥ 208 if re-run
- bootJar: SUCCESS (prior packet)
- Gateway JwtAuthenticationFilterChainTest: SUCCESS (service-boundary vs gateway-boundary kept distinct)
- Seed caller inventory + 9 self-tests + 33 master-plan status tests: OK (prior packet)
- git diff --check: clean — `07-git-diff-check.txt`

## Identity / status / error matrix (service boundary)
| Case | HTTP | Notes |
|---|---|---|
| existing replace | 200 | created=false; response timestamps match persisted |
| first creation (empty/nonempty) | 201 | created=true only |
| existing empty no-op | 200 | emptiness ≠ creation |
| exact no-op | 200 | version/updatedAt/holding ids preserved |
| missing X-User-Id | 400 | no adapter call |
| body/query spoof | header wins | other user unchanged (IT) |
| /api/portfolio/{id}/holdings | 404 | no replace |
| missing/invalid version tokens | 400 | missing_version / invalid_version |
| malformed/null body | 400 | malformed_request |
| null holding element (isolated/mixed) | 400 | malformed_request before adapter |
| quantity JSON number | 400 | quantity_not_string |
| missing/null quantity (current version) | 400 | quantity_out_of_domain after version check |
| missing quantity + stale version | 409 | version precondition wins |
| version conflict | 409 | currentVersion |
| quantity/duplicate | 400 | complete deterministic tickers |
| unsupported/lifecycle | 422 | catalogVersion + tickers |
| projection disagreement | rollback | version/timestamps/holding ids/cost basis |

## Logs
- 00-baseline-focused-tests.txt
- 01-composition-unit-tests.txt
- 02-full-portfolio-suite.txt
- 03-gateway-identity-chain.txt
- 04-seed-version-callers.txt
- 05-seed-version-selftests.txt
- 06-master-plan-status.txt
- 07-git-diff-check.txt — regenerated for this P2 correction packet (`git diff --check` clean)
- 08-p2-composition-unit-tests.txt — focused unit GREEN after P2
- 09-p2-composition-integration-tests.txt — focused IT GREEN after P2
- RED compile log — **unavailable** (not captured in the original packet; explicitly marked)
