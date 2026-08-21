# Asset Picker — E2E Master Plan to Production

**Date:** 2026-08-21
**Status:** Living index document. Points at the real spec artifacts rather than duplicating them —
per project convention, feature content lives in `.kiro/specs/`, this file is the cross-cutting
program view tying Spec A, B1, B2, and known bugs together.
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
were already settled in a prior brainstorm but never formalized, then corrected across twenty-seven
review passes — twenty-five by Codex adversarial review, plus two internal parallel-agent audits
(Claude-run, not Codex). **Architecture shape is substantially settled — this is not fully
decision-complete.** *(Pass 6 correction: this line previously said "nothing here waits on a
design conversation anymore," which overstates it — `updatedAt` ownership on `PortfolioResponse`
is explicitly unassigned (Track C row 2's Needs column, below — pass 12 correction: an earlier
draft called this "item 6," but item 6 in that row's own seven-item build list is the
`INTERNAL_API_KEY` read; `updatedAt` is an unnumbered Needs-column dependency, not a numbered
build item) and three product decisions remain open (idle-reset threshold, manual-reset placement,
presence TTL). Both are tracked accurately further down; this summary line just hadn't matched
them.)* What's left is implementation, sequencing, the unassigned `updatedAt` ownership question,
and the open product calls (§4.3).

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
  2 (twenty-seven review passes as of 2026-08-22 — twenty-five Codex, two internal Claude audits), synthesized from decisions already settled in
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

### For Track C (B2) — four separate phases, not one start condition

Review correction: an earlier pass of this plan placed B2 both "after Wave 4" and "after Wave 7" in
different places. Those are two different phases, not a contradiction to resolve one way:

| Phase | Needs | Status |
|---|---|---|
| **UI development against frozen contracts/mocks** — build screens, wire local state, no live backend calls | `.kiro/specs/asset-picker-composition/{requirements,design}.md` + visual design reference (both ✅ produced this pass); Node/npm toolchain (✅ present) | **Startable now.** B1's contract shape (request/response fields) is fixed by its spec regardless of which release gate is open. |
| **B2-owned backend build** — work B2 itself must implement (B1/Spec A dependencies moved to the Needs column, pass 8 correction — they aren't B2's to build): (1) JWT `jti` claim added to session tokens, (2) Redis-backed presence tracking (one shared `presence:demo` sorted set, each session a member — `ZADD`/`ZREMRANGEBYSCORE`/`ZCARD`, not one key per session) plus the `GET /api/presence/demo` gateway endpoint, (3) `ReadOnlyEnforcementFilter` allowlist gains its second entry (`PUT /api/portfolio/demo-reset` alongside `PUT /api/portfolio/holdings`), plus a new sibling Gateway `GlobalFilter` — `DemoResetAuthorizationFilter` (design.md D5) — ordered at `Ordered.HIGHEST_PRECEDENCE + 4` (a unique value, after both `JwtAuthenticationFilter` at `+2` and `ReadOnlyEnforcementFilter` at `+3` — pass 16 correction: pass 15 said "after `JwtAuthenticationFilter` (+2) alongside `ReadOnlyEnforcementFilter` (+3)," which reads as two different, tied values and pins neither), checking the JWT subject against a gateway-local copy of the `DEMO_USER_ID` literal for that same `(PUT, /api/portfolio/demo-reset)` pair; a mismatch returns the pinned `403` with body `{ "error": "demo_reset_forbidden", "message": "Only the demo account may reset the demo portfolio." }` (pass 16 correction: earlier passes pinned only the machine code, or only `{"error":...}` with no `message`, despite claiming the same shape as `ReadOnlyEnforcementFilter.writeForbidden()`'s own two-field body) — a match instead (pass 17 correction, three sub-steps where there was one): confirms the exchange's matched route id is genuinely `demo-reset-manual` via `ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR` — never the similarly-named `GATEWAY_PREDICATE_ROUTE_ATTR`, which is always null by the time any filter runs (self-audit precision fix; full mechanism in design.md D5) — a fail-safe against a future routing regression, strips any caller-supplied `Authorization` and already-injected `X-User-Id` (pass 17 finding: this `GlobalFilter` runs before the route's own `RewritePath`, and the *pre-rewrite* path `/api/portfolio/demo-reset` does not match `JwtAuthenticationFilter`'s `/api/internal/**` bypass, so without this step a real JWT and a real `X-User-Id` would silently reach the internal endpoint), then removes any caller-supplied `X-Internal-Api-Key` and SETS (never appends) the authoritative one (item (6) below) before letting the request proceed, (4) `DemoResetService.reset(expectedVersion)` in portfolio-service, called directly from **exactly one place** — the internal endpoint, mapped to accept **both** `POST` and `PUT` (pass 17 correction: `RewritePath` changes only the request path, never the method — the resolved Spring Cloud Gateway 5.0.2 artifact has no method-mutation filter factory to invent a YAML fix from — so the manual trigger's rewritten request arrives as `PUT` while the login-orchestrated self-call, unconstrained, still issues `POST`; one mapping accepting both, not two endpoints) at `/api/internal/portfolio/demo-reset` (internal-key-protected via `InternalApiKeyFilter`, which gates every `/api/internal/**` request and passes every other path through **untouched**, verified directly — no JWT reaches this endpoint at all *for requests genuinely addressed to `/api/internal/**` when `JwtAuthenticationFilter` evaluates them*, true of the E2E seeder and the login self-call; the manual trigger's rewritten request only arrives identity-free because item (3)'s filter strips those headers itself, not because the prefix match ever applied to it; its authorization is otherwise the internal key plus a server-fixed `DEMO_USER_ID` target). `PUT /api/portfolio/demo-reset` is **not** a second portfolio-service handler — it is a dedicated Gateway route (`demo-reset-manual`, `Path=/api/portfolio/demo-reset` + `Method=PUT`, given an explicit `order` lower than the generic `portfolio-service` route — pass 17 correction: Gateway sorts routes by `Route.order`, which defaults to `0` for every route in this file today, including the generic one; list position only breaks that tie, it doesn't determine precedence) whose `RewritePath` filter forwards onto the internal endpoint above, once item (3)'s filter has verified the caller, confirmed the route, stripped the external-auth headers, and attached the internal key. *(Pass 16 correction, reverting pass 15's own new handler: pass 15 put the manual trigger's logic directly in portfolio-service at `/api/portfolio/demo-reset`, reasoning this was safe because item (3)'s filter gated it — but `InternalApiKeyFilter` (verified directly) only ever gates `/api/internal/**`; a plain `/api/portfolio/demo-reset` handler in portfolio-service had **no** protection at all against a direct call to the public AWS Function URL (`authorization_type = "NONE"`, `infrastructure/terraform/aws/modules/compute/main.tf` line 416) — worse than every other portfolio-service endpoint, which at minimum requires *some* `X-User-Id` value. Removing that handler and routing through the existing internal endpoint instead closes the gap: a direct caller now needs the internal key too, same as the login-orchestrated trigger always has. Pass 15's own correction of pass 14 — portfolio-service handles the actual reset, api-gateway only gates access to it — still holds; pass 16 only changes *which* portfolio-service endpoint the manual trigger reaches.)* **Mandatory release gate (pass 19 correction, corrected further pass 20 — items (3) and (4) are NOT one deployable unit; only item (3)'s pieces are). This is the authoritative sequence** — design.md D5 carries the same reasoning but defers to this copy as the release gate itself, and requirements.md cross-references it without duplicating it as a product acceptance criterion (pass 20 clarification: an earlier draft had requirements.md asserting its own `SHALL` for this sequencing while design.md called it "not a new acceptance criterion" — contradictory classifications for the same thing; release orchestration belongs here, not as user-visible product behavior). api-gateway and portfolio-service deploy as separate, non-atomic runtime artifacts — verified against both production workflows: AWS's `deploy-aws.yml` updates the api-gateway Lambda's live alias (lines 176-226) strictly *before* it even builds the portfolio-service image (line 232 onward), same job, sequential; Azure's `deploy-azure.yml` runs selected services through a genuine `strategy: matrix` (lines 202-204), so completion order between services is not guaranteed, and scoped single-service deploys are separately supported. **Stage 1 is not a mapping widening (pass 20 correction — a more consequential fix than it looks): neither `DemoResetService` nor any `/api/internal/portfolio/demo-reset` mapping exist in portfolio-service today** (verified directly — zero matches; the only existing controller under that path prefix is `PortfolioSeedController`, mapped solely to `POST /seed`, an unrelated endpoint). There is no `POST`-only predecessor to widen, so stage 1 is the full portfolio-service-side build: (1) **B1 prerequisite, not B2's to build:** B1 Wave 4 task 4.1, `HoldingReplacementService` — unchecked, no class exists — the primitive `DemoResetService` depends on; (2) build `DemoResetService` and a new internal controller mapping **both** `POST` and `PUT` from the start (not `POST` first, widened later), including its dual-verb MVC test; (3) deploy and verify this directly against the live environment — safe to take real time here, since nothing yet routes user traffic to it; (4) only then ship and deploy the **manual-reset gateway bundle** — item (3)'s filter, route, and allowlist entry, but NOT the login-orchestrated self-call (pass 21 correction: an earlier draft bundled the self-call in here too, which either blocks this otherwise-ready bundle on the unrelated, still-open `updated_at`/idle-threshold/timeout items below, or contradicts this row's own release-gate framing) — as one deployable unit; before stage 1-3 land, this bundle's route would forward to an endpoint that doesn't exist at all (not the `405` an earlier draft predicted, which presumes a real, verb-rejecting mapping); (5) deploy the frontend's manual-reset control **hidden, not exposed** (pass 26 correction: an earlier draft said "expose," contradicting the mandatory release gate's own requirement that login orchestration deploy before either picker control is user-visible) — `tasks.md`'s own build-time feature flag is what keeps it non-user-visible once deployed, decoupling "the backend capability exists and is verified" from "a user can trigger it" without prematurely exposing it ahead of (6); (6) ship the login-orchestrated self-call as its own later, independent gateway deployment, gated on `updated_at` landing on `PortfolioResponse` plus the idle-threshold and timeout decisions (below), needing no further portfolio-service change since stage 1 already accepts `POST` — neither (4) nor (5) waits on this stage's own open items, since (5)'s deploy is hidden regardless; (7) only once (6) is deployed does exposure happen — `tasks.md`'s Wave 10 production-exposure gate enables both controls' feature flags together, requiring (6) already deployed among its other conditions; (8) roll back in the *true* reverse order of this sequence (pass 26 correction of the prior rollback order, which assumed (5) was user-visible before (6) deployed): disable exposure first (both flags off, a new deploy), then roll back (6), then (5), then (4), then portfolio-service's endpoint (1)-(3). (5) login-path self-call orchestration — the **only** self-call in this feature; the manual trigger (items 3-4) is ordinary Gateway routing, not a self-call, and needs none of this machinery (pass 15 correction: pass 14 had briefly given the manual trigger a second self-call too, removed with the pass-14 revert above) — fail-open relative to login, on **both** the eligibility read and the reset call itself, with a per-leg AND an overall orchestration timeout, executed non-blocking (api-gateway's `/api/auth/login` is reactive WebFlux — `AuthController.java:40` returns `Mono<ResponseEntity<Object>>` — so the self-calls must use a non-blocking client; no `.block()`, no `RestTemplate`), (6) reading the already-deployed `INTERNAL_API_KEY` environment variable in api-gateway code — via `System.getenv("INTERNAL_API_KEY")` (mirroring `CloudFrontOriginVerifyFilter`'s own pattern), not the `@Value("${app.internal.api-key:}")` binding an earlier draft suggested (pass 9 correction: that mapping only exists in `portfolio-service`'s `application.yml` today; used as-is in api-gateway it resolves blank and the reset leg sends an empty key — `InternalApiKeyFilter` rejects that as `403 invalid_internal_api_key`, not `503`; `503 internal_api_key_not_configured` is reserved for portfolio-service's *own* secret being unconfigured, an unrelated, server-side failure mode, pass 18 correction) — **code only, not a deployment dependency**: pass 7 wrongly treated this as unprovisioned; verified against Terraform, the secret already reaches api-gateway's process on both AWS (`runtime_secrets` in `compute/main.tf`) and Azure (`secret_env_vars` in `main.tf`), and a live Azure query confirmed it's present on the deployed Container App today — nothing needs provisioning, only reading correctly — needed for the login-orchestrated reset call's self-call above, and, as of pass 16, also for `DemoResetAuthorizationFilter`'s header attachment on a successful manual-trigger check (item (3)) — the same env var, read in two places for two different transports, not two separate secrets (pass 15 had said the manual trigger carries no internal key at all; pass 16's route-based redesign means it now does, attached server-side by the gateway filter, never sent by the browser), (7) attaching `X-Origin-Verify` **conditionally** to the login-orchestrated eligibility self-call **only** — non-blank value on AWS, omitted entirely on Azure/local (pass 9 correction: Azure never provisions `CLOUDFRONT_ORIGIN_SECRET` to api-gateway at all, and `CloudFrontOriginVerifyFilter` no-ops when it's absent; an unconditional header assignment isn't cross-cloud safe). *(Pass 15 correction: pass 14 broadened this to also cover the manual trigger's now-reverted self-call to the internal reset endpoint — wrong on two counts, not just one made moot by the revert above: `CloudFrontOriginVerifyFilter` bypasses `/api/internal/**` entirely without stripping the header, so attaching `X-Origin-Verify` to a call reaching that prefix would forward the secret value into portfolio-service unchecked, the opposite of the filter's anti-leakage intent. `X-Origin-Verify` belongs solely on the loopback `GET /api/portfolio` eligibility read, which is not under `/api/internal/**` and is the only self-call that needs to pass that filter at all.)*
**Self-audit addition after pass 16, refined by pass 17, corrected by pass 19 (full detail in
design.md D5, and now folded into items (3)-(4) above):** item (3) alone — the api-gateway filter,
route, and D6's `ReadOnlyEnforcementFilter` allowlist entry — SHALL ship as one deployable unit;
`DemoResetAuthorizationFilter` without the allowlist entry 403s the demo user's own request before
the new filter ever runs. **Item (4)'s portfolio-service mapping is NOT part of that same unit (pass
19 correction of pass 16/17's own framing) — it is a prerequisite, deployed and verified separately,
first** — see the rollout note folded into item (4) above; api-gateway and portfolio-service are
separate, non-atomically-deployed artifacts on both cloud targets. `DemoResetAuthorizationFilter`
SHALL match the exact `(PUT, /api/portfolio/demo-reset)` pair, never method alone, to avoid attaching
`X-Internal-Api-Key` to unrelated `PUT` calls, and SHALL fail closed with `503
internal_api_key_not_configured` (no downstream call) if its own `System.getenv("INTERNAL_API_KEY")`
read is null/blank (pass 19 addition — an unconfigured gateway secret must never surface as a
misleading per-user `403` from portfolio-service instead). Pass 17 found two further concrete bugs in
this mechanism, both now fixed: `RewritePath` never changes the request method, so the manual
trigger's rewritten `PUT` was reaching a `POST`-only mapping (405) until the internal endpoint was
widened to accept both; and `JwtAuthenticationFilter` evaluates the pre-rewrite path, so a real
`Authorization` header and a real `X-User-Id` were reaching the internal endpoint unless
`DemoResetAuthorizationFilter` explicitly strips them, which it now does. The `demo-reset-manual`
route's precedence rests on an explicit `order`, not list position (Gateway sorts by `Route.order`,
defaulting to `0` for every route today). Verification is now two separate tests, not one (pass 19
correction: a single test against a stubbed portfolio-service can prove transport but not that both
verbs reach the same real call site) — a gateway routed-integration test (route, path, method,
stripped headers, single internal-key value) and a portfolio-service-side **Testcontainers
integration test exercising the real `DemoResetService → HoldingReplacementService →
GoldenStateTuplePreparer → Catalog_Module → persistence` chain** (pass 24 correction: an earlier
draft called this an "MVC test," which an MVC slice satisfies by mocking `DemoResetService` itself —
proving nothing about whether a reset actually restores the golden holdings; both verbs invoke the
same controller method/service call exactly once, the test itself supplying a configured, non-blank
internal key since `DemoResetAuthorizationFilter` is out of scope there, with the golden set
independently checked against the active catalog and both price tables asserted byte-identical
before/after — a thin, supplementary MVC slice may still cover just the dual-verb-routing shape for
fast feedback) — neither shipped on the strength of the design doc alone. | Redis instance reachable from the gateway; B1's `replace(userId, expectedVersion, intent, preparer)` primitive — **not yet built**, only designed: B1 Wave 4 task 4.1 (`HoldingReplacementService`) is unchecked and no matching class exists in `portfolio-service` today (verified by search); **`updated_at` exposed on `PortfolioResponse`** — an unassigned cross-spec gap, not covered by any current B1 task (B1 Wave 3/V20 adds only the DB column; Wave 5 task 5.1 exposes `version`, not `updated_at`) — without it the login-orchestrated idle-reset trigger cannot be built at all; **`assetPriceFreshness` landing on the portfolio-summary response** — Spec A `tasks.md` task 8.6, unchecked, blocks Requirement 3.2/3.4 independent of anything else in this row | **Not started on the build column, partly not startable at all.** Design is specified (`design.md` D4/D5/D6) but this is real implementation work with two real cross-spec dependencies (`updated_at`, `assetPriceFreshness`) that have no owner assigned. Tracked here so it can't be silently assumed done alongside the frontend, or assumed unblocked once B1 Wave 4 merges. |
| **Live integration** — the picker actually calling `GET /api/assets` / `PUT /api/portfolio/holdings` against a real backend | B1 Wave 7's `CompositionController` — B1 does not introduce the public endpoint before Wave 7, no matter how complete Wave 4's underlying orchestrator is — **and** the B2-owned backend build above, scoped to its manual-reset/demo-write pieces specifically, since the picker's demo-write path depends on those — **not** the login-orchestrated self-call item within that same row (pass 23 correction: an earlier draft's undifferentiated "the B2-owned backend build above" read as covering that item too; `tasks.md`'s own task breakdown makes the two independent — no live-integration task calls the login self-call path or needs `updated_at`, the idle threshold, or the self-call timeouts, all of which remain gated only at that build's own row and at production exposure) | Blocked until B1 Wave 7 **and** the manual-reset/demo-write portion of the B2 backend build lands. |
| **Production exposure** — real users reaching the picker | **All six of:** (1) B1/Spec A's activation gates (R-C, and everything R-C itself depends on); (2) the frontend decimal-adapter migration (`requirements.md` Requirement 8, `BackendHolding.quantity: number → string`) sequenced ahead of B1's read-contract change reaching production; (3) the **Live integration** row above actually completed, not merely unblocked — B1 Wave 7 landing does not by itself mean B2's own manual-reset/demo-write backend build (row above) is done; (4) the three still-undecided product calls in `requirements.md`'s Open items list (idle-reset threshold, manual-reset control placement, presence TTL) resolved — these are product decisions this table cannot mark "done" on B2's behalf; (5) the login self-call's per-leg (2s) and overall (4s) timeout values confirmed as operational defaults or explicitly decided otherwise — added pass 9, previously absent from this gate despite being tracked in `requirements.md`'s Open items since pass 8; (6) **the login-orchestrated self-call itself deployed to production, with `updated_at` actually landed on `PortfolioResponse`, not merely assigned an owner** — added pass 24: item (3)'s narrowing (pass 23, scoping Live Integration to exclude login-orchestration so it stops being blocked by an unrelated item) left this table with no remaining carrier for that deployment requirement at all, which `tasks.md`'s own Wave 10 gate still correctly retains. *(That list has seven Open-items entries total, not three — the other four, decimal-adapter sequencing, `updatedAt` ownership, `assetPriceFreshness`/Spec A task 8.6, and the self-call timeouts, are already this row's own item (2), the row-above's Needs-column dependencies (`updated_at` on `PortfolioResponse`, and `assetPriceFreshness`/Spec A task 8.6 — these are unnumbered dependency call-outs in that row's Needs column, not entries in its own seven-item build list; that list's actual item 6 is the `INTERNAL_API_KEY` read, and it has no item 8 at all), and this row's own items (5) and (6), not omitted, just not double-counted here. Pass 10 correction: an earlier draft of this note mislabeled the two Needs-column dependencies as build-list items "(6) and (8)".)* | Blocked until all six clear. *(Pass 5 correction: an earlier draft listed only items 1-2, which reads as sufficient while B2's own backend and its open product decisions remain incomplete — the exact gap this row exists to prevent. Pass 9 correction: item 5, the timeout decision, was tracked in requirements.md's Open items but never added to this gate. Pass 24 correction: item 6, login-orchestration's own deployment, was inherited transitively through item (3) until pass 23 narrowed it, and needed its own explicit carrier here.)* |

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
Track C: B2, four phases (see Prerequisites table — not one start condition)
  UI-dev-against-mocks: STARTABLE NOW (spec + design done)
  B2-owned backend build: NOT STARTED, and the updated_at-on-
    PortfolioResponse dependency (a Needs-column call-out above,
    not one of the seven numbered build items) has no owner yet —
    a real gate, not a formality, since the login-orchestrated
    reset trigger can't be built without it
  Live integration: needs B1 Wave 7 specifically (the public
    CompositionController) AND the manual-reset/demo-write portion
    of the B2 backend build above, not just Wave 4 — NOT the
    login-orchestrated self-call item, which stays independent
    (pass 23/24 correction: this line previously said "the B2
    backend build above" undifferentiated, re-coupling this phase
    to updated_at/the login self-call the line above gates)
  Production exposure: needs all six of: Wave 7's activation
    gates; the frontend decimal-adapter migration; Live integration
    above actually completing, not merely unblocking; the three
    outstanding product decisions (idle-reset threshold, manual-
    reset placement, presence TTL); the login self-call timeout
    values confirmed; AND the login-orchestrated self-call itself
    deployed to production, with updated_at actually landed, not
    merely owned (pass 24 addition — narrowing Live Integration's
    own dependency, pass 23, silently dropped this item's only
    remaining carrier; it does not get inherited transitively
    anymore) — see the Prerequisites table above

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
