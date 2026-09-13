# B2 Task 8.9 — governing activation timeout/retry policy

**Decision date:** 2026-09-13
**Owner approval:** D-1 (per-probe cap) and D-2 (probe ceiling), both recorded as answered on this
date — six probes, 90-second cap
**Applies to:** the Task 8.9 bounded activation sequence in `scripts/run_task_8_9_preflight.ps1`
(`Invoke-AuthorizedWake`) and any operator procedure describing it
**Supersedes:** the activation-timeout and retry instructions in
`docs/superpowers/plans/2026-09-12-b2-task-8-9-live-proof-readiness-packet.md` (see the
supersession annotation at the top of that file)
**Basis:** `phase-a-activation-policy-analysis.md` (Claude, Opus 5, offline repository analysis;
baseline `origin/main@94d5bba8`) — recommendation Option 1, with the probe ceiling raised from five
to six per the owner's D-2 answer

---

## Governing activation policy

A Task 8.9 activation sequence consists of **at most six** `GET` requests to
`https://api.vibhanshu-ai-portfolio.dev/actuator/health`, each bounded by `curl --max-time 90`,
issued five seconds apart. Only two outcomes continue the sequence: HTTP `503` with curl exit `0`,
or a curl timeout (exit `28` with status `000`). The first HTTP `200` ends the sequence
successfully. **Every other outcome stops the sequence immediately with exit 3.** Every request
issued is consumed; any request beyond the sequence is a fresh owner decision.

This replaces the 2026-09-12 packet's "set no client-side timeout" and "on any non-`200`, stop and
report — do not re-issue the request." Those instructions were written before any Production
attempt. Two subsequent attempts under that policy each consumed a wake and produced no verdict
(`run-a-attempt-20260912.md`, `run-a-attempt-20260913.md`), and the cold-start profile recorded in
`API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md` L47–50 shows a first contact that timed out before a later
`200`, which a single-request policy cannot reach.

A bounded cap is retained rather than removed because an unbounded request can hang indefinitely.
The cap is 90 s rather than 30 s because a 30 s cap truncates the slowest response yet observed on
this gateway (`503` after 56.374 s) into exit `28`/`000`, which is indistinguishable from a network
stall — and, because `28`/`000` continues the sequence while other HTTP errors stop it, truncation
can convert a mandatory stop into a continue.

## Why six probes, not five

The Phase A analysis flagged (D-2) that the five-probe budget had zero margin: the only observed
path to a `200` on this gateway consumed exactly five requests (timeout → `503` → `503` → `503` →
`200`), so a single additional `503` would have exhausted the prior budget with no verdict. Raising
the per-probe cap does not address this — E5's failures were fast `503`s, not timeouts — only a
larger ceiling does. The owner's D-2 answer raises the ceiling to six, giving the sequence one
probe of margin beyond the only recorded success path.

Worst-case activation duration at six probes, a 90 s cap, and the production 5 s interval:
`6 × 90 + 5 × 5 = 565` seconds (9 m 25 s); `690` seconds (11 m 30 s) at the largest accepted
interval (30 s). Both remain far inside the packet's 30-minute outer authorization window, so the
outer window continues not to discriminate between the raised and prior budgets.

## What is unchanged

- **`--operation-timeout-seconds` (600) is deliberately left as-is**, pending measurement of typical
  `az`/`docker` call durations (Finding T-1 in the Phase A analysis). This is a follow-up item, not
  part of this policy.
- The wake mechanism, target route (`GET /actuator/health`), retry classification (exact `503` or
  curl timeout only), and every other constraint in the 2026-09-12 packet not concerning the
  activation timeout/retry shape remain current — see that packet's supersession annotation for the
  precise boundary.
- The activation sequence's structure (one safety unit, fail-closed, no override) is unchanged;
  only the two literals — the per-probe cap and the probe ceiling — moved.

## What this does not determine

Per the Phase A analysis, the following remain open and are not resolved by this policy:

- Whether six probes at 90 s would in fact reach a `200` on the current gateway — no attempt has
  ever observed a `200` on this endpoint under any policy.
- The 300-second scale-to-zero cool-down assumption (E12) — a recorded planning assumption, not
  independently verified.
- Typical (as opposed to worst-case) `az` call durations, which the 110–135 s post-success timing
  estimate depends on.
