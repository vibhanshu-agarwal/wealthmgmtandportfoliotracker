# Asset Picker — E2E Master Plan to Production

**Review-accounting note (Azure-first consolidation, 2026-08-22):** historical pass counts below
are provenance only. They are no longer synchronized or incremented as acceptance metadata; git
history plus the current scope-specific authority and executable gates are authoritative.

**Date:** 2026-08-21
**Status:** Living index document. Points at the real spec artifacts rather than duplicating them —
per project convention, feature content lives in `.kiro/specs/`, this file is the cross-cutting
program view tying Spec A, B1, B2, and known bugs together.
**Authority is scope-specific, not blanket:** B2 `requirements.md` owns product behavior;
`design.md` owns architecture and component boundaries; this master plan owns cross-spec dependencies
and release ordering; `tasks.md` owns executable task/test mechanics. A conflict is a freeze blocker
to reconcile across the affected artifacts, never permission for one file to silently override an
unrelated scope.
**Current delivery scope is Azure first.** AWS-only CloudFront/Lambda work is explicitly deferred
while AWS production is disabled and does not block the Azure Asset Picker release. Shared code and
cross-cloud contracts remain in scope; deferred AWS gates must be re-audited before AWS is re-enabled.
**Verification level, precisely** (softened on review — an earlier draft's blanket "everything
verified directly" overstated this): specific checkpoints (9.3, 9.5), file contents, and git
branches were checked with live calls, cited inline where they were. Others were **not** checked
this pass — 9.1/9.2, and every "credential present in `.env.secrets`" row in the Prerequisites
table, which is presence, not a tested connection. Anything not explicitly marked as live-verified
below should be treated as unconfirmed, not assumed true — same standard §1.6 asks of spec-doc
checkboxes.

---

## 0. Where things actually stand

"Asset Picker" spans two specs by deliberate design (not an oversight — see §1.3): **B1**
(`portfolio-composition-contract`, backend contract) and **B2** (`asset-picker-composition`,
frontend plus a real slice of its own backend — not just "one touch": a demo-presence subsystem
(JWT `jti` claim, Redis session tracking, a new gateway endpoint) and two new demo-reset
endpoints, in addition to the `ReadOnlyEnforcementFilter` allowlist change). B1 is mid-flight.
**B2 now has a Revision 2 spec and a visual design** — both produced this pass from decisions that
were already settled in a prior brainstorm but never formalized, then corrected across twenty-eight
review passes — twenty-six by Codex adversarial review, plus two internal parallel-agent audits
(Claude-run, not Codex). **Architecture shape is substantially settled — this is not fully
decision-complete.** `updatedAt` exposure is now explicitly owned by B2 Task 8.1 after B1's V20
column and version-bearing read land; it is no longer an ownerless cross-spec gap. Three product
decisions remain open (idle-reset threshold, manual-reset placement, presence TTL). What's left is
implementation, dependency-aware sequencing, and those open product calls (§4.3).

---

## 1. Ground truth, verified today

### 1.1 Infrastructure prerequisite — Spec A cutover (`supported-asset-integrity`, task 9)

Checked against **live Azure state**, not the spec's checkboxes:

| Checkpoint | Requires | Actual state | How verified |
|---|---|---|---|
| 9.1 | R1 deployed, inert | Not independently re-verified this pass | — |
| 9.2 | Refresh producer narrowed | Not independently re-verified this pass | — |
| 9.3 | `MARKET_DATA_JOB_RUNNER_ENABLED=false`, refresh suspended | **`=true`**, job ran normally on its `0 8 * * *` cron yesterday (2026-08-20), succeeded | `az containerapp job show` / `execution list` |
| 9.4 | Kafka consumer lag zero | Not yet checked — credentials available in `.env.secrets` | — |
| 9.5 | `api_gateway_ingress_enabled=false` | **`external: true`**, 100% traffic on the live revision — full site is live | `az containerapp show` |
| 9.6 | Postgres repair — **IRREVERSIBLE** | Blocked: needs 9.3-9.5 green + a verified post-9.5 backup. Code exists, tested green, on unmerged `feat/supported-asset-postgres-repair` | branch diff, tasks.md |
| 9.7 | Mongo repair — **IRREVERSIBLE** | Blocked: needs 9.6 done. Code exists, tested green, on unmerged `feat/supported-asset-mongo-repair` (no Flyway migrations — Mongo-only) | branch diff |
| 9.8-9.9+ | R4 deployed, catalog identity confirmed | Not reached | — |

**This blocks B1 Waves 3, 5, 6, 7** and, per design.md, arguably the release of Wave 2's remaining
tasks too — see §2. **Prerequisites for 9.3-9.5 are ready now** (§ Prerequisites below) — nothing
stops starting this today.

### 1.2 B1 backend (`portfolio-composition-contract`) — 9 waves, P through 7

| Wave | What | Status |
|---|---|---|
| P | Deployment prerequisites | ✅ Done, live |
| 0 | Fixture identity migration | ✅ Done (PR #121) |
| 1 | Legacy writer retirement (R-0) | ✅ Done — deployed, kept (GO recorded) |
| 2 | Gateway provisioning + `/api/assets` route (R-A) | **2.1/2.3 done**, verified, [PR #131](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/131) open as a **draft**. 2.2/2.4/2.5/2.6 blocked on §1.1. |
| 3 | Schema — V20 migration | Blocked on Spec A (§1.1) |
| 4 | Contract implementation (orchestrator, error envelope, decimal fidelity, `GET /api/assets` controller) | **Not blocked — startable now.** 23 tasks (4.1-4.21, plus 4.20a/4.20b — inserted by a later B1 review pass, same nesting level as their neighbors, not sub-bullets), zero started. *(Pass 5 correction: previously counted 21, missing the two lettered insertions.)* |
| 5 | Version-bearing read (R-B2) | Blocked on Wave 3 |
| 6 | Version-required seed (R-B3) | Blocked on Wave 5 |
| 7 | Activation — `PUT /api/portfolio/holdings` goes live (R-C) | Blocked on Wave 6 |

### 1.3 B2 frontend (`asset-picker-composition`) — now has a Revision 2 spec + visual design

- **`.kiro/specs/asset-picker-composition/requirements.md` and `design.md`** now exist — Revision
  2 (twenty-eight review passes as of 2026-08-22 — twenty-six Codex, two internal Claude audits), synthesized from decisions already settled in
  `docs/superpowers/brainstorm/2026-08-16-spec-b1-and-auth-ratelimit-hotfix.md` entries [0], [4]-[6]
  (a Claude↔Codex Q&A that resolved the picker's shape, presence mechanism, reset trigger, and
  decimal-fidelity handling in detail — it was just never turned into a spec document or a visual
  design until this pass).
- **A visual design exists**: five screens (Portfolio page with the entry point, Browse/draft,
  Review/confirm, post-save success, and the 409 conflict state), **built from the app's real
  design tokens** (shadcn/ui, emerald/slate palette, existing Card/Table/Badge anatomy) — not
  claimed as pixel-exact, since that hasn't been independently verified against the running
  frontend. Present in the working tree at
  `.kiro/specs/asset-picker-composition/mockup/asset-picker-design.html` (opens in any browser
  offline, with a system-font fallback; **not fully self-contained**, since each screen links Geist
  from `fonts.googleapis.com` for visual fidelity and falls back to `system-ui` without network
  access) and also published as an editable design canvas (private by default, not guaranteed
  reachable by every reviewer). **Since committed (pass 26 correction — this note is now stale and
  historical, kept for the record rather than deleted).** Pass 5's audit originally found the whole
  spec directory, `.kiro/specs/asset-picker-composition/`, untracked (`git ls-files` returned
  nothing for it, `git log --all` showed zero commits touching it on any branch), unlike B1's spec
  directory, which was tracked from the start; that gap was the reason this section existed. As of
  the spec owner's explicit freeze-and-commit direction after round 21, `requirements.md`,
  `design.md`, the mockup, and this master plan document itself are all tracked and committed
  (`docs/b2-asset-picker-composition-spec`, commit `1639565` and later amendments) — verified
  directly via `git ls-files`, all four now return a match. **`tasks.md` (the implementation task
  breakdown) is the one artifact in this spec family still uncommitted**, by the same deliberate
  "review before freezing" pattern the other four went through first.
- Why B2 is a separate spec at all, stated plainly: B1's own Requirement 10 (non-goals) says *"THIS
  spec SHALL deliver no frontend change... belongs to B2"* — a deliberate split, reasoned from Spec
  A's own experience that mixing persistence/API/frontend review in one spec caused repeated
  rework. Not an oversight; a decision now correctly followed through on.
- Genuinely open (not resolved by this pass, see §4.3): the demo reset idle threshold's exact
  value, where the manual reset control lives in the UI, and the presence TTL's exact value. (A
  quantity upper bound was listed here in an earlier pass — removed on review: B1 already freezes
  it at `99999999999.99999999`, cited in B2's own `requirements.md` Requirement 2.5.)
- Also found on review, not resolved yet: B2's spec had two real design bugs that would have
  shipped wrong behavior if implemented as first drafted — the `PUT` used the wrong field name
  (`version` instead of B1's actual `expectedVersion`), and the demo-reset design duplicated
  B1-owned persistence instead of delegating to it. Both are now fixed in
  `.kiro/specs/asset-picker-composition/design.md`; noted here so the correction isn't lost from
  the program-level view.

### 1.4 "Profile changes" — still completely unscoped

- `frontend/src/app/(dashboard)/settings/page.tsx` is an 8-line placeholder.
- No spec anywhere named profile/settings; referenced only in passing in one backlog item.
- Still need your input on what this should contain.

### 1.5 Known bugs already blocking a credible demo, independent of B1/B2

**`demo-portfolio-and-ticker-integrity`** (open since 2026-08-15, unfixed): demo account shows 3
holdings instead of ~160 (wrong seeded user, from `V15`/PR #85); BTC priced two different ways on
two pages ($70,775 stale seed price on Portfolio vs. $0.00 on Market Data). Root-caused, nothing
fixed yet.

**`e2e-coverage-audit-post-asset-picker`** (open, *deliberately* deferred until B1+B2+Profile land)
— noted so it isn't lost, not something to start now.

### 1.6 Checkbox/spec hygiene — confirmed as a real, recurring problem

`new-user-signup-profile` — a fully shipped, working-in-production feature — shows 0 of 11 tasks
ticked. An unticked box has meant both "not done" (Spec A's task 9, confirmed live this pass) and
"done, not recorded" (B1 Wave 0's task 0.7, earlier this session) — genuinely ambiguous.
**Proposed fix (§4.2):** a CI check failing any PR that touches a spec's implementation files
without also updating that spec's `tasks.md` in the same diff.

---

## Prerequisites — everything required upfront, by track

Compiled so nothing below is a surprise mid-execution blocker.

### For Track A (Spec A cutover, checkpoints 9.3-9.9)

| Requirement | Status |
|---|---|
| Azure CLI (`az`) authenticated to tenant `3b7c1239-b414-4fd6-9b91-176b4cfba1b4`, subscription `ee625b3f-...` | ✅ Confirmed working this session — a live `az account show`/`az containerapp` round-trip, not just credential presence |
| Azure MCP Server tool access | ❌ **Broken as observed this session** — authenticated to the wrong tenant (`f8cdef31-...`), 401 on every call. Worked around via `az` CLI directly for every check. Recorded as prior-session history; a later session should re-confirm rather than assume it's still broken, since no Azure MCP tool was exposed to re-test this in the review pass. |
| Kafka access (`KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD`) | ⚠️ Keys present in `.env.secrets` — **connectivity not yet exercised.** Presence of a credential is not proof it's current or that consumer-lag reads (9.4) actually succeed with it. |
| Neon Postgres connection (`POSTGRES_CONNECTION_STRING`) | ⚠️ Key present in `.env.secrets` — connectivity/authorization not yet exercised. **Hard gate, not a one-line follow-up:** 9.6 is Spec A's own irreversible checkpoint, and its stated abort condition is "do not proceed without a verified backup taken after 9.5." Before 9.6 can run, this needs an actual decision — which Neon mechanism (point-in-time restore vs. an explicit branch snapshot) counts as "verified" here — confirmed and exercised, not assumed. |
| MongoDB connection (`MONGODB_CONNECTION_STRING`) | ⚠️ Key present in `.env.secrets` — connectivity not yet exercised. Needed for 9.7. |
| GitHub Actions access to run/monitor `feat/supported-asset-postgres-repair` and `feat/supported-asset-mongo-repair` branches' CI, and to merge them | ✅ Same `gh` CLI access exercised repeatedly and successfully throughout this session |
| Explicit owner sign-off that downtime (gateway ingress disabled, §1.1 9.5) is acceptable now | ✅ **Given** — "Downtime is not an issue" |

### For Track B (B1 Wave 4)

| Requirement | Status |
|---|---|
| Local Java 21 / Gradle build, Docker (Testcontainers for integration tests) | ✅ Already used successfully throughout this session |
| No external access needed — pure code against `main` | ✅ |

### For Track C (B2) — a dependency DAG, not one start condition or a total order

The portfolio-service reset endpoint is the common predecessor of the two reset consumers. Presence
and the flag-hidden frontend are independent branches; the manual-reset gateway bundle and the
login-orchestration gateway work may advance after the endpoint on their own prerequisites, without
being serialized behind each other. Live integration waits on
B1 Wave 7 plus the relevant B2 manual/demo-write pieces. Production exposure is the convergence
gate and waits for every required branch and its live evidence.

| Phase | Needs | Status |
|---|---|---|
| **UI development against frozen contracts/mocks** — build screens, wire local state, no live backend calls | `.kiro/specs/asset-picker-composition/{requirements,design}.md` + visual design reference (both ✅ produced this pass); Node/npm toolchain (✅ present) | **Startable now.** B1's contract shape (request/response fields) is fixed by its spec regardless of which release gate is open. |
| **B2-owned backend build (DAG branches)** — (a) JWT `jti` plus Redis presence and the authenticated gateway-local presence endpoint; (b) portfolio-service `DemoResetService` and the dual-verb internal controller; (c) the manual-reset gateway route/filter/allowlist bundle; (d) B2 Task 8.1 `updatedAt` exposure plus the separately-gated login orchestration; and (e) Azure deployment-evidence foundation Task 8.8b. Presence and the flag-hidden frontend are independent; the portfolio endpoint is the common predecessor only for its manual and login reset consumers, which do not depend on each other. The real-chain reset proof is Task 4.4's Testcontainers integration through `DemoResetService → HoldingReplacementService → GoldenStateTuplePreparer → catalog → persistence`; a thin MVC slice is optional and proves only both verbs map to the same call site. | Full B1 Wave-4 prerequisite cluster **4.1, 4.3, 4.7, 4.9, and 4.10**, plus B1 **5.1** before version-bearing live probes; Redis; Spec A Task 8.6 for `assetPriceFreshness` (owned but unfinished). B2 Task 8.1 owns `updatedAt` after V20/B1 5.1; it is not ownerless. | **Not started.** UI/mock work and independent backend branches are startable where their own Needs are green. Login orchestration does not block the manual bundle or hidden frontend; all branches converge at Production exposure. |
| **Live integration** — picker save and manual reset against the assembled backend | B1 Wave 7 `CompositionController`; B2 manual/demo-write pieces; Task 9.5 waits on owned-but-unfinished Spec A Task 8.6 for freshness. Login orchestration is not a live-integration prerequisite. | Blocked until those specific dependencies land; then Tasks 9.1-9.9 verify catalog caching, price/freshness, deterministic save `200`/`409`, manual reset, and cleanup. |
| **Production exposure** — Azure users can see both controls | **All six:** (1) B1/Spec A activation gates; (2) decimal adapter live before B1's string read contract; (3) Live integration complete; (4) idle threshold, manual-control placement, and presence TTL resolved; (5) login self-call timeout values resolved; (6) presence Task 3.7, portfolio endpoint 4.5, manual bundle 5.6, hidden frontend 6.3/9.8, login deployment 8.8, Azure evidence foundation 8.8b, and trace-correlated live proof 8.9 all green against the revisions currently serving. AWS-only Task 8.8a is deferred while AWS is disabled. | **Blocked until all six clear.** Exposure is one Azure frontend rebuild with both flags enabled; rollback is another rebuild with both disabled, verified from a fresh client. |

Outstanding product decisions (idle-reset threshold, manual-reset placement, presence TTL) —
`requirements.md`'s Open items — don't block UI-development-phase work, but do block finalizing the
reset/presence screens' actual behavior and copy, **and now explicitly block production exposure
above too, not just polish.**

### For Track D (demo bug fix)

| Requirement | Status |
|---|---|
| Read access to production Postgres to confirm the root cause against live data | ⚠️ Connection string present in `.env.secrets`; connectivity/authorization not yet exercised |
| Understanding of `V15`'s reassignment and the seed-price staleness mechanism | ✅ Already written up in the backlog item |
| No infra/external blocker **once Postgres connectivity above is confirmed** | Not yet claimed — the row above is the actual open item; this isn't a separate green light |

### Cross-cutting

| Requirement | Status |
|---|---|
| `.env.secrets` present at repo root, with non-empty AWS/Azure/Kafka/Postgres/MongoDB/Terraform-var keys | ⚠️ Presence confirmed this session; **currency/validity of the values themselves not tested** — a key existing is not proof it still works |
| A working Azure access path, given the MCP tool was observed broken earlier this session | ✅ `az` CLI directly, exercised successfully and repeatedly. Whether the MCP tool is *still* broken is unconfirmed as of this pass (§ above) — `az` CLI is the proven path regardless, worth keeping as the standing default rather than re-litigating each time |

---

## 2. Why merging PR #131 alone wouldn't have "finished" anything visible

Even with 2.2/2.4 cleared and #131 merged: R-A activates a `portfolio` insert at signup (invisible)
and a gateway route to a controller that doesn't exist until Wave 4/7 ships. Nothing changes on
screen yet — noted here once so it's never reported as a surprise blocker again.

---

## 3. The path to production — four tracks, three startable immediately

```
Track A: Spec A cutover (infra)         Track B: B1 backend Wave 4 (code)
  9.3 → 9.4 → 9.5 → [backup] → 9.6         4.1-4.21, no external dependency
  → 9.7 → 9.8 → 9.9                        STARTABLE NOW
  STARTABLE NOW (prereqs ready,                   │
  downtime accepted)                              │
        │                                         │
        └──────────────┬──────────────────────────┘
                        ▼
              B1 Wave 3 (V20 applied) — needs Track A done
                        │
                        ▼
              B1 Waves 5 → 6 → 7 (R-B2 → R-B3 → R-C)
              PUT /api/portfolio/holdings goes live
                        │
                        ▼
Track C: B2 dependency DAG (Azure first)
  UI shell/mocks ───────────────────────────────┐
  Presence (3.1-3.7) ──────────────────────────┤
  Hidden frontend/reset control (Wave 6) ─────────┤
  Portfolio reset endpoint (Wave 4) ─┬─ manual gateway bundle (Wave 5)
                                      └─ login orchestration (Wave 8)
                                           ├─ B2-owned updatedAt (8.1)
                                           ├─ threshold/timeouts (8.2)
                                           └─ Azure evidence (8.8b → 8.9)
  B1 Wave 7 + relevant B2 manual/demo-write pieces → Live integration (Wave 9)
  All branches + live evidence + open decisions ───→ Production exposure (Wave 10)
  AWS-only 8.8a: DEFERRED while AWS production is disabled
Track D: demo-portfolio-and-ticker-integrity bug fix
  STARTABLE NOW — independent of everything above,
  and arguably most visible fix available today.
```

---

## 4. Concrete next actions

### 4.1 Starting now, no further gating

- **Track D**: fix `demo-portfolio-and-ticker-integrity`.
- **Track A**: exercise the Kafka/Postgres/MongoDB credentials to confirm they actually work (not
  just that they're present), verify 9.4 (Kafka lag), then execute 9.3 → 9.5 in order, confirm
  which Neon mechanism satisfies 9.6's "verified backup" requirement and exercise it, then 9.6,
  then 9.7 — each its own checkpoint, reported as it completes.
- **Track B**: B1 Wave 4 (4.1 onward).
- **Track C, UI-development phase only**: buildable now against the spec/design, with no live
  backend — not gated behind Track B or A.

### 4.2 Process fix

- CI check: a PR touching a spec's implementation files must also update that spec's `tasks.md` in
  the same diff.

### 4.3 Still needs your input — narrow, not blocking Tracks A/B/D

- **Demo reset idle threshold** — provisionally 30 minutes in the B2 design; confirm or adjust.
- **Manual reset control placement** — not yet decided where in the UI.
- **Presence TTL** — provisionally 150 seconds, explicitly marked provisional in the original
  brainstorm; confirm or adjust.
- **Profile changes scope** (§1.4) — completely unscoped beyond the name.
- **Azure MCP tool's broken tenant auth** — worth fixing properly, or is `az` CLI directly an
  acceptable standing workaround? (Unconfirmed whether it's still broken — no Azure MCP tool was
  available to re-test during the review pass; re-check before relying on this.)
