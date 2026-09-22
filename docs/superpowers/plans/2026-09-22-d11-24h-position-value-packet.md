# D11: position-level 24h value — execution packet

**Prepared by:** Claude (Opus 5), 2026-09-22
**Worktree:** `C:\worktrees\wealthmgmtandportfoliotracker-worktrees\wealthmgmtandportfoliotracker-claude-d11`
**Branch:** `claude/d11-24h-position-value`, **local only**, from
`main@9a4603c245529b93942d3a6b9b85e886be08b5e0` (the merge of PR #310)
**Final code head:** `4fb60e5f` (this packet is the only later commit, docs only)
**Design:** `docs/superpowers/specs/2026-09-22-d11-24h-position-value-design.md`
**Evidence:** `C:\worktrees\wealthmgmtandportfoliotracker-worktrees\_handoff\2026-09-22-d11-24h\`. Not
committed: the run folders hold account identifiers. `pw-output/`, which holds bearer tokens and the
typed `FRESH` password, was not copied.

## OWNER APPROVAL CALLOUT: read first

**Status.** Codex held B1 at `c4144070` on one Important finding: a subset 24h total had no coverage
metadata, so a partial value read as complete. That is corrected at `4fb60e5f` under the existing D11
authorization (Codex: "no new scope approval is required"). Codex confirmed the field names and
arithmetic (`change24hValueBase`, `totalChange24hBase`, `totalChange24hPercent`), which are unchanged.
Nothing has been pushed, published, merged, deployed or dispatched. No Production or cloud resource
was touched. **Return for Codex re-review at the exact local head.**

| # | Blocked action | Decision requested | If yes | If no |
|---|---|---|---|---|
| B1 | Push `claude/d11-24h-position-value` and open a PR against `main` | Authorize publication, after Codex re-review | CI runs; merge stays a separate decision | Branch stays local; F13 stays in `main` |
| B2 | Merge the D11 PR | Authorize merge after independent acceptance and green CI | `main` carries D11 | F13 stays in `main` |
| B3 | Deploy D11 | Part of A3 (D3): **portfolio-service first, or together with the frontend** | The 24h figures are correct in Production | Production keeps the wrong 24h figures |

**Two consequences to weigh before B3 and the Phase 3 Production run (A4):**
- A frontend-only deploy of D11 shows "—" for the Overview 24h card, the Portfolio footer and the row
  sub-lines until the D11 portfolio-service is serving: without the new fields, and now without
  `change24hCoverage`, the card fails closed. Row percentages are unaffected.
- Once D11 is on `main`, the Phase 3 suite fails S11 against any backend without the D11 fields
  (fail-closed by design). The owner-operated Production run must not start until the D11
  portfolio-service is serving in Production. The Phase 3 packet's A3 row still offers a frontend-only
  deploy; reconcile it when D11 merges.

Still closed: A3, A4, A5, workflow dispatch and all Production activity.

## 1. Commits

| Commit | Change |
|---|---|
| `cb141ed7` | Design |
| `3618b346` | Backend: `change24hValueBase`, `totalChange24hBase`, `totalChange24hPercent` |
| `9f01ccad` | Frontend: card, rows and footer use them; S11 oracle `lib/change24h.ts` |
| `29bbbda2` | Fable R1 follow-ups: S11 row sub-line and footer checks, deploy-ordering text |
| `3db6321e` | Fable R2 follow-ups: the footer must be present exactly once; tolerance wording |
| `c4144070` | Packet (superseded by this revision) |
| `5d3b37fd` | **Codex correction, backend:** `change24hCoverage` |
| `3d0740d8` | **Codex correction, frontend:** card and footer disclose partial coverage; S11 coverage checks; design |
| `4fb60e5f` | Fable R3 minor: the card fails closed on a malformed coverage object. **Final code head** |
| (this packet) | Docs only |

## 2. What changed

- **Backend** (portfolio-service, additive). Per holding, `change24hValueBase` = quantity × (current −
  reference) × the current FX rate, from unrounded prices, scale 4; null when the change or the rate
  is unavailable. At the top level, `totalChange24hBase` sums it over exactly the holdings counted in
  `totalValue`, and `totalChange24hPercent` divides by those holdings' value at the reference.
  `change24hAbsolute` keeps its per-unit meaning.
- **Coverage (Codex correction).** `change24hCoverage = { holdingsWithChange, countedHoldings,
  totalHoldings, partial }`, never null; `partial = holdingsWithChange < totalHoldings`.
  `totalHoldings` counts **every** holding, including those without a price or FX rate. Codex's
  wording was "the counted-holding total", but a holding without a price or FX rate is not counted in
  `totalValue`, so a counted-only comparison would call such a portfolio complete and break
  requirement 2. `countedHoldings` is reported as well. Missing history, a missing price, a missing FX
  rate or a cost-basis-FX exclusion therefore all mark the totals partial.
- **Frontend.** The Overview 24h card shows the totals only with well-formed coverage: absent or
  malformed coverage (an older backend) fails closed to "—" ("24h coverage unavailable"); a partial total carries
  "Partial: n of m holdings", worded apart from the Performance chart's "Partial (n/m holdings)",
  which an existing S11 locator matches. Portfolio rows show each position's change under the
  percent; the footer sums the visible rows and shows "Partial: n of m" when some, but not all,
  visible rows have a position change. Market Data keeps the per-unit price change.
- **Phase 3 suite (S11).** The card equals `totalChange24hBase`, which equals an independent
  recomputation from per-unit changes, values and prices. The payload's coverage must match a count
  made from its holdings; the card shows its partial label exactly when coverage is partial; each
  row's sub-line equals its `change24hValueBase`; the footer equals their sum and shows its label
  exactly when a visible row lacks a change.

## 3. Local validation

All local: a docker stack (`wmpt-phase3`) whose portfolio-service was rebuilt from this branch at
`3d0740d8` (the backend has not changed since `5d3b37fd`), and a flagged static export on
`localhost:3000`.

- **Failing first.** The 8 coverage tests failed on a null coverage stub; the frontend card and footer
  label tests failed before the labels existed; the 3 malformed-coverage tests failed (the card showed
  +$517.76) before the `4fb60e5f` guard. Earlier: the arithmetic tests failed on the old code
  with exactly the F13 figures (−$1,873.52 and −$1,871.27).
- **Backend at `3d0740d8`.** portfolio-service **565 unit and 212 integration tests pass**.
- **Backend mutations, asserted: 7 of 7 caught** by their declared tests (`mutations/mutations-coverage-run.log`
  and `mutations-coverage-run-M2-rerun.log`), including
  three on coverage: a counted-only total (a missing price looks complete), a partial flag against the
  counted holdings, and a partial flag hard-wired to false. M2's expected test name was stale after a
  rename, so the harness first reported it NOT CAUGHT; with the name fixed it was caught.
- **Frontend at `4fb60e5f`.** vitest **1,809 passed** plus the known unrelated Windows-only CRLF failure
  (`step-b-5b-config-and-static-guards.test.ts`); `tsc` clean; no new lint warnings.
- **Complete Phase 3 suite at `4fb60e5f`:** run `p3-20260922T141448Z-7f50` on build
  `obJ_lAJ9VrHi1HXfeuFtc`, clean tree, PASS_WITH_EXPECTED_DEFECTS, **535 checks, 0 failed**, the same
  three expected defects (F2, F9, F3), from 14:14:46Z to 14:16:16Z, clear of the hourly local price
  refresh. The run at `3d0740d8` (`p3-20260922T134805Z-28f1`, 535 checks, 0 failed) had identical
  coverage outcomes:
  - CERT_A (RELIANCE.NS has no FX rate): coverage 3 of 4, partial; card "Partial: 3 of 4 holdings";
    footer "Partial: 3 of 4".
  - CERT_B and FRESH: complete; no card label.
  - CERT_B at 1280 px: the footer read "Partial: 3 of 4" while its analytics said 4 of 4. That read
    fell in the known 30 s analytics cache after S09's save (F9): the stale analytics lacked the
    just-added ADA-USD, so the Portfolio row for ADA-USD had no position change, and the footer
    disclosed it. The check compares with the same payload the page used, so it passed correctly.
- **Negative controls: 6 of 6 bit, at `3d0740d8` and again at `4fb60e5f`.** Each requires its own run
  folder and a matching served build, and the run's first failed check (the suite stops at its first
  failure) must be the target oracle at the required account. Values below are from the `3d0740d8`
  runs; the `4fb60e5f` runs failed on the same checks at the same accounts, with prices moved by the
  14:00 refresh (for example AAPL 128.24 / 1,538.88):

  | Control | Injected fault | First and only failure (shown / expected) |
  |---|---|---|
  | NC-D11a | Card sums per-unit changes | CERT_A card −2,109.69 / −241.81 |
  | NC-D11b | Row sub-line shows the per-unit change | CERT_A AAPL 126.48 / 1,517.76 |
  | NC-D11c | Footer sums per-unit changes | CERT_A footer −2,109.69 / −241.81 |
  | NC-D11d | Card partial label suppressed | CERT_A: none / "Partial: 3 of 4 holdings" |
  | NC-D11e | Card label shown on a complete portfolio | CERT_B: "Partial: 4 of 4 holdings" / none |
  | NC-D11f | Footer partial label suppressed | CERT_A: none / "Partial: 3 of 4" |

  NC-D11d's first attempt did not build (the mutation broke TypeScript narrowing); the type-safe
  rerun bit. The older-backend failure of S11 was confirmed by review, not by a run.

## 4. Reviews

| Round | Reviewer | Scope | Verdict |
|---|---|---|---|
| R1 | Fable | `9a4603c2..9f01ccad` | **ACCEPT WITH MINORS** (0 Critical, 1 Important, 6 Minor) |
| R2 | Fable | `9f01ccad..29bbbda2` | **ACCEPT WITH MINORS** (0 Critical, 0 Important, 5 Minor) |
| C1 | Codex | `c4144070` | **B1 held:** 1 Important (no 24h coverage contract); field names and arithmetic confirmed |
| R3 | Fable | `c4144070..3d0740d8` (exact code head at the time) | **ACCEPT WITH MINORS** (0 Critical, 1 Important, 5 Minor) |

R1 and R2 dispositions are unchanged from the previous revision: the deploy ordering and Phase 3
dependency are in design §3 and above; S11 checks the row sub-lines and footer; the mutation harness
asserts its expected failures; the ledger records the recomputation's primitives; the footer must be
present exactly once.

**C1 disposition:** requirements 1–6 are implemented as described in §2 and §3; requirement 7 is this
revision plus R3.

**R3** confirmed Codex requirements 1–6 with file references, found the every-holding denominator
right (partial false implies every holding contributes; partial true is always a real gap), and
confirmed the CERT_B-1280 footer attribution to F9 from the ledger. Dispositions:
- Important-1: the packet had not been updated for the coverage round. This revision.
- M4: a malformed coverage object (`{}`, no or a non-boolean `partial`) showed the total unlabelled.
  Fixed in `4fb60e5f`, failing tests first. `4fb60e5f` changes only that guard and its tests; it was not
  re-reviewed by Fable and is part of Codex's exact-head re-review.
- M3: "exactly one failure" is structural (the suite stops at its first failure); the control wording
  above now says so.
- M5: both mutation logs are cited.
- M2: an S11 false-fail on a cost-basis-FX-excluded holding is deliberate and fail-closed; stated in
  §5.
- M1: the S11 coverage oracle counts from the payload's own holdings, so it could not see a backend
  that dropped a holding from both; unreachable today (the analytics query starts from
  `asset_holdings` with LEFT JOINs). Recorded, not changed.

**Cost.** Fable R1, R2 and R3 used about 193k, 151k and 179k subagent tokens (9, 8 and 10 minutes).

## 5. Known limits

- The 24h value uses the current FX rate for both ends, so FX movement over the window is excluded.
- A reference older than the 36 h window (`SINCE_PREVIOUS_SNAPSHOT`) is still counted, as before.
- The S11 recomputation, the S11 coverage count and the IT identity cannot see the backend's
  cost-basis-FX exclusion (it is not on the wire); such a holding would make them fail, never pass.
- The Portfolio footer's coverage is judged over the visible rows. It can differ from the backend's
  coverage when a filter hides rows, or while analytics is stale after a save (F9).
- Local seed data produces implausible 24h moves (for example GOOGL +99%); these are data artifacts
  like F12, not D11 behaviour.
- F14 ("$" on quote-currency prices) and F10 (sub-cent "$0.00") are unchanged.
