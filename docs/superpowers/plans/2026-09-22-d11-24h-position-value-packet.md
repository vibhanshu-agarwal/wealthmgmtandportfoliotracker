# D11: position-level 24h value — execution packet

**Prepared by:** Claude (Opus 5), 2026-09-22
**Worktree:** `C:\worktrees\wealthmgmtandportfoliotracker-worktrees\wealthmgmtandportfoliotracker-claude-d11`
**Branch:** `claude/d11-24h-position-value`, **local only**, from
`main@9a4603c245529b93942d3a6b9b85e886be08b5e0` (the merge of PR #310)
**Final code head:** `3db6321e` (this packet is the only later commit, docs only)
**Design:** `docs/superpowers/specs/2026-09-22-d11-24h-position-value-design.md`
**Evidence:** `C:\worktrees\wealthmgmtandportfoliotracker-worktrees\_handoff\2026-09-22-d11-24h\`. Not
committed: the run folders hold account identifiers. `pw-output/`, which holds bearer tokens and the
typed `FRESH` password, was not copied.

## OWNER APPROVAL CALLOUT: read first

**Status.** D11 is implemented, locally validated and independently reviewed: two Fable rounds, 0
Critical, and the one Important is resolved. Nothing has been pushed, published, merged, deployed or
dispatched. No Production or cloud resource was touched.

| # | Blocked action | Decision requested | If yes | If no |
|---|---|---|---|---|
| B1 | Push `claude/d11-24h-position-value` and open a PR against `main` | Authorize publication | CI runs; merge stays a separate decision | Branch stays local; F13 stays in `main` |
| B2 | Merge the D11 PR | Authorize merge after independent acceptance and green CI | `main` carries D11 | F13 stays in `main` |
| B3 | Deploy D11 | Part of A3 (D3): **portfolio-service first, or together with the frontend** | The 24h figures are correct in Production | Production keeps the wrong 24h figures |

**Two consequences to weigh before B3 and the Phase 3 Production run (A4):**
- A frontend-only deploy of D11 shows "—" for the Overview 24h card, the Portfolio footer and the row
  sub-lines until the D11 portfolio-service is serving. Row percentages are unaffected.
- Once D11 is on `main`, the Phase 3 suite fails S11 against any backend without the D11 fields. That
  is deliberate (fail-closed), but the owner-operated Production run must not start until the D11
  portfolio-service is serving in Production. The Phase 3 packet's A3 row still offers a frontend-only
  deploy; reconcile it when D11 merges (review R2 M1).

Still closed: A3, A4, A5, workflow dispatch and all Production activity.

**For Codex.** The owner's approval refers to a contract "described in the reconciliation", which is
on an unpublished Codex branch this work did not read. Please confirm the contract below matches it:
`change24hValueBase` (per holding), `totalChange24hBase` and `totalChange24hPercent` (top level).

## 1. Commits

| Commit | Change |
|---|---|
| `cb141ed7` | Design |
| `3618b346` | Backend: `change24hValueBase`, `totalChange24hBase`, `totalChange24hPercent` |
| `9f01ccad` | Frontend: card, rows and footer use them; S11 oracle `lib/change24h.ts` |
| `29bbbda2` | R1 follow-ups: S11 row sub-line and footer checks, deploy-ordering text, documentation fixes |
| `3db6321e` | R2 follow-ups: the footer must be present exactly once; tolerance wording. **Final code head** |
| (this packet) | Docs only |

## 2. What changed

- **Backend** (portfolio-service, additive). Per holding, `change24hValueBase` = quantity × (current −
  reference) × the current FX rate, from unrounded prices, scale 4; null when the change or the rate
  is unavailable. At the top level, `totalChange24hBase` sums it over exactly the holdings counted in
  `totalValue`, and `totalChange24hPercent` divides by those holdings' value at the reference. A
  holding without a reference contributes to neither, so an unknown change is never treated as zero.
  `change24hAbsolute` keeps its per-unit meaning.
- **Frontend.** The Overview 24h card and percent show the backend totals; Portfolio rows show each
  position's change under the percent (the percent alone when the value is unavailable); the footer
  sums the rows. The new fields are optional, so an older backend gives "—", not a wrong figure. Market
  Data keeps the per-unit price change.
- **Phase 3 suite (S11).** The card must equal `totalChange24hBase`, which must equal an independent
  recomputation from per-unit changes, values and prices within the per-unit wire rounding. Each row
  sub-line must equal its `change24hValueBase`, and the footer their sum. The ledger records the
  primitives, so the recomputation can be re-derived from it.

## 3. Local validation

All local: a docker stack (`wmpt-phase3`) whose portfolio-service was rebuilt from this branch, and a
flagged static export on `localhost:3000`.

- **Failing first.** 7 of the 10 new backend tests failed on a null stub for the stated reason; the
  other 3 pin the null cases. The frontend card and table tests failed on the old code with exactly
  the F13 figures (−$1,873.52 and −$1,871.27, the per-unit sums).
- **Backend.** portfolio-service 562 unit and 212 integration tests pass (at `3618b346`; later commits
  change no production Java, only one IT comment).
- **Backend mutations, asserted** (`mutations/`): 4 of 4 caught by their declared tests, including the
  real-Postgres IT for "position value = per-unit change" (the F13 defect itself). The first harness
  run falsely reported survivals because `gradlew.bat` was never found; that was caught before it was
  trusted.
- **Frontend at `3db6321e`.** vitest 1,797 passed plus the known unrelated Windows-only CRLF failure
  (`step-b-5b-config-and-static-guards.test.ts`); `tsc` clean; no new lint warnings (two pre-existing
  unused imports in `HoldingsTable.tsx` remain).
- **Complete Phase 3 suite at `3db6321e`:** run `p3-20260922T124422Z-633b` on build
  `kK6WW-yTsXv6C2POsTtHs`, clean tree, PASS_WITH_EXPECTED_DEFECTS, **508 checks, 0 failed**, with the
  same three expected defects as before (F2, F9, F3). It ended at 12:45:50Z, clear of the hourly
  local price refresh. Every account and viewport passed the card, recomputation, row sub-line and
  footer checks; for example CERT_A's AAPL row shows +$1,517.76 (12 × 126.48), the F13 example.
  Earlier runs: `…120657Z-61bf` at `9f01ccad` (460 checks) and `…122827Z-f152` at `29bbbda2` (499), both
  0 failed.
- **Negative controls at `3db6321e`: 3 of 3 bit.** Each reinjects the per-unit sum into one surface; the
  script requires its own run folder, a matching served build, and exactly one failure, on the target
  oracle:

  | Control | Reinjected into | First and only failure (shown / expected) |
  |---|---|---|
  | NC-D11a | Overview card | card total −2,109.69 / −241.81 |
  | NC-D11b | Portfolio row sub-line | AAPL 126.48 / 1,517.76 |
  | NC-D11c | Portfolio footer | footer −2,109.69 / −241.81 |

  Each aborted run's accounts were restored ("confirmed by S99"). The older-backend failure of S11 was
  confirmed by review, not by a run (it would need a pre-D11 backend image).

## 4. Independent reviews (Fable)

| Round | Scope | Verdict |
|---|---|---|
| R1 | `9a4603c2..9f01ccad` (the whole change) | **ACCEPT WITH MINORS** (0 Critical, 1 Important, 6 Minor) |
| R2 | `9f01ccad..29bbbda2` (the follow-ups) | **ACCEPT WITH MINORS** (0 Critical, 0 Important, 5 Minor) |

**R1 dispositions** (in `29bbbda2`):
- I1 (Important): the deploy-ordering and Phase 3 dependency were not in the owner-facing text. Fixed
  in design §3 and in this packet's callout.
- M1: no browser check of the Portfolio footer or row sub-line. Added, with negative controls.
- M2: the mutation harness did not encode its claim. It now declares and asserts the expected failing
  tests, and its log is kept.
- M3–M5: tolerance note, IT/design wording, and the pre-existing zero-reference percent: documented.
- M6: the ledger now records the recomputation's primitives. R2 re-derived CERT_A's total from them.

**R2 dispositions:**
- M2 and M4 applied in `3db6321e` (footer present exactly once; tolerance wording).
- M3 applied to the negative-control script (handoff only): its own run folder, exactly one failure,
  and a matching served build are now required.
- M1 (Phase 3 packet A3 row) recorded above for reconciliation at merge.
- M5 (an unparseable sub-line throws rather than logging a check) accepted: it fails closed.

**Cost.** About 193k and 151k subagent tokens (9 and 8 minutes).

## 5. Known limits

- The 24h value uses the current FX rate for both ends, so FX movement over the window is excluded.
- A reference older than the 36 h window (`SINCE_PREVIOUS_SNAPSHOT`) is still counted, as before.
- The S11 recomputation and the IT identity cannot see the backend's cost-basis-FX exclusion (not on
  the wire); such a holding would make them fail, never pass.
- Local seed data produces implausible 24h moves (for example GOOGL +99%); these are data artifacts
  like F12, not D11 behaviour.
- F14 ("$" on quote-currency prices) and F10 (sub-cent "$0.00") are unchanged.
