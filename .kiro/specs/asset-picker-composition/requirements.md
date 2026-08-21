# Spec B2: Asset Picker Composition — Requirements

**Revision 2** — materially revised across twenty-five review passes, **twenty-three by Codex
adversarial review and two (passes 7 and 25) internal parallel-agent audits** (2026-08-21/22; passes
7 and 25 are Claude-run, not
Codex — labeled distinctly so this isn't misread as a Codex round):
pass 1 found seven P1 + three P2; pass 2 found three further P1 + five P2; pass 3 found four
further P1 + three P2; pass 4 found four further P1 + four P2 — the reset's `updated_at`
dependency, the service-boundary call path, the primitive's actual not-yet-built status, and
dialog accessibility; pass 5 found six further P1 + two P2 — mockup catalog-data fidelity,
B1-ownership-vs-implementation-status conflation, the login-time eligibility read's transport and
fail-open completeness, the `updatedAt` gap's visibility, the reset preparer's exact type and
anchor, and control-level accessibility; pass 6 found five further P1 + three P2 — draft
cardinality, post-save freshness, the login self-call's deployable target and fail-open exhaustion,
a remaining decimal-fidelity overclaim, and a disabled-checkbox/citation cleanup; **pass 7
(internal audit, not Codex)** dispatched five parallel Claude agents with fresh eyes and found: a
held asset shown unchecked in the picker despite the same portfolio's own holdings table listing
it, a holdings-count that silently equaled the real catalog's entire active-ticker ceiling leaving
no room for browsable examples, a second undefined secret dependency on the login self-call's
internal-key-protected leg, a wave-number misattribution, a decimal-fidelity citation gap, and the
`assetPriceFreshness` gap missing from both Open-items surfaces; **pass 8 (Codex)** found three
further P1 + three P2 — pass 7's own `INTERNAL_API_KEY` finding was itself wrong (already deployed
to api-gateway on both clouds; only the reading code was missing), Conflict's draft summary still
didn't visibly represent the full 146-holding draft, the login self-call's timeout/non-blocking
contract was incomplete for two sequential legs, Track C mixed B2-owned work with external
dependencies, "159 catalog assets" named an ambiguous population, and Success dropped the Details
affordance Main has; **pass 9 (Codex)** found five further P1 + one P2 — pass 6's own
`X-Origin-Verify` fix wasn't cross-cloud safe (Azure never provisions `CLOUDFRONT_ORIGIN_SECRET`),
pass 8's `INTERNAL_API_KEY` binding resolves blank in api-gateway as written, Conflict's draft
still wasn't genuinely scrollable/readable, the mockup's freshness example was impossible under
Spec A's 50-hour stale threshold, the timeout decision was dropped from the production gate, and
the Details affordance had no interaction contract, all addressed below); **pass 10 (Codex)** found
three further P1 + one P2 — the Conflict draft's scroll container had `pointer-events:none`
silently defeating wheel-scroll while claiming to show "all preserved holdings" with only 11 of 146
rows actually rendered, the freshness-details button's `aria-haspopup="true"` was the wrong
WAI-ARIA popup type for what is really a focus-transferring popover, the master plan mislabeled two
Needs-column dependencies as build-list items "(6) and (8)" of a seven-item list, and design.md
stated an AWS-only `CLOUDFRONT_ORIGIN_SECRET` access claim without qualifying it as such,
contradicting its own next sentence; **pass 11 (Codex)** found two further P1 + two P2 — the
pass-10 fix's 135 filler rows were a handwritten ticker list rather than derived from
`config/seed-tickers.json`, including 60 tickers B1's contract would reject as `unsupported_asset`
and 4 canonical-name drifts (the same mockup-catalog-fidelity defect class pass 5 already caught
once), the draft container's `opacity:0.6` reduced informational asset-name text below WCAG 2.2's
4.5:1 contrast minimum, design.md D1's "no tabindex at all" needed narrowing to rows/controls only
now that the scroll region itself carries one normatively (this requirements.md revision), and both
spec headers' pass count hadn't yet been advanced past pass 9 — all addressed below; **pass 12
(Codex)** found one further P1 + two P2 — the pass-11 catalog-derived generator picked `DOGE-USD`
as one of the 144 "unchanged" rows despite Review.dc.html's own diff listing `DOGE-USD` under
"Removed · 1" (catalog membership alone doesn't guarantee consistency with the draft's own stated
diff), both spec headers' pass count still undercounted by one even after the pass-11 update (it
must include the pass doing the counting, a mistake this line itself now repeats the fix for), and
the master plan's intro paragraph had one more stale `updatedAt`-is-"item 6" cross-reference the
earlier table/diagram fixes hadn't reached — all addressed below; **pass 13 (Codex)** found one
further P1 — the pass-11/12 generator assigned `BTC-USD` a random integer quantity (75) despite
Main.dc.html's holdings table and Browse.dc.html's own curated row both showing it held at 0.75, a
100x decimal-fidelity break (Requirement 8.1) the catalog-membership and diff-consistency checks
from passes 11-12 didn't cover, since `BTC-USD` is a real, currently-held ticker with an existing
canonical quantity, not an unsupported or removed one; self-audit for the same defect class also
caught `MSFT`, `ETH-USD`, and `TCS.NS` appearing as held rows despite Browse.dc.html showing all
three unchecked — addressed below; **pass 14 (Codex)** found two further P1 + one P2 — 7.3a's
`PUT /api/portfolio/demo-reset` handler was specified as portfolio-service-hosted, calling
`DemoResetService` directly, while requiring it to verify the caller's JWT subject; portfolio-service
has no JWT decoder or principal (only api-gateway's `X-User-Id` injection), and on AWS the portfolio
Lambda's Function URL is public with `authorization_type = "NONE"`, so that verification was not
implementable as specified — moved the handler to api-gateway, which already has JWT verification,
calling the internal reset endpoint over HTTP instead of in-process; the mockup's round-11 row
generator had also deleted the `EURUSD=X` row's closing `</div>` while splicing in its 135 rows (595
open vs 594 close, invisible to JSON/ticker-text checks — restored, now verified against a real DOM
parse: 148 direct children, 2 paragraphs + 146 rows); and Browse.dc.html's own comment overclaimed
~140 rows "further down the same list, off-screen" when only 7 actually render there — corrected to
say explicitly that Browse, unlike Conflict, is intentionally abbreviated; **pass 15 (Codex)** found
2 further P1 + 1 P2, all correcting pass 14's own fix: pass 14's local api-gateway controller for the
manual `PUT /api/portfolio/demo-reset` trigger silently bypassed `JwtAuthenticationFilter`,
`ReadOnlyEnforcementFilter`, and `CloudFrontOriginVerifyFilter` — all three are Spring Cloud Gateway
`GlobalFilter`s that, by `JwtAuthenticationFilter`'s own javadoc, run "before routing" and therefore
never execute for a request api-gateway's own local controller handles directly, only for requests
Gateway proxies onward — and shadowed the existing `/api/portfolio/**` route's rate limiter; reverted
the handler to portfolio-service (matching its pre-pass-14 shape) and moved the JWT-subject check to
a new Gateway `GlobalFilter` instead, which runs for the genuinely-routed request and keeps all four
protections intact; separately, pass 14's own broadening of `X-Origin-Verify` to the (now-reverted)
manual-trigger self-call would have forwarded that secret into portfolio-service unchecked, since
`CloudFrontOriginVerifyFilter` bypasses `/api/internal/**` without stripping it — reverted to
login-eligibility-read-only scope; and pass 14's manual controller had introduced a third HTTP leg
needing its own timeout contract, which the revert above removes entirely, since the corrected design
has no self-call for the manual trigger at all; **pass 16 (Codex)** found 1 further P1 + 2 P2, all
correcting pass 15's own fix: pass 15's reverted handler in portfolio-service at
`/api/portfolio/demo-reset` had **no protection at all** against a direct call to the public AWS
Function URL — `InternalApiKeyFilter` only ever gates `/api/internal/**`, and pass 15's comparison to
"the same exposure every other portfolio-service endpoint already has" didn't hold, since every other
endpoint at least requires *some* `X-User-Id` value while this one required no identity header
whatsoever; removed that handler entirely and routed the manual trigger through the existing
internal-key-protected endpoint instead, via a Gateway route (`RewritePath`) plus
`DemoResetAuthorizationFilter` attaching the internal key server-side on a JWT-subject match, so a
direct caller now needs the same key the login-orchestrated trigger has always needed. Also pinned
`DemoResetAuthorizationFilter`'s order unambiguously at `HIGHEST_PRECEDENCE + 4` (previously
described as simultaneously "after `JwtAuthenticationFilter` (+2)" and "alongside
`ReadOnlyEnforcementFilter` (+3)," two different tied values), and pinned one exact 403 envelope body
(`{"error":"demo_reset_forbidden","message":"Only the demo account may reset the demo portfolio."}`)
everywhere it's referenced, correcting a body that had drifted between a bare `{"error":...}` and a
claimed-but-not-actual match to `ReadOnlyEnforcementFilter`'s own two-field shape; **pass 17
(Codex)** found 2 further P1 + 1 P2, all in pass 16's own transport mechanism: `RewritePath` changes
only the request path, never the HTTP method, so the manual trigger's rewritten `PUT` was reaching a
`POST`-only internal mapping and would 405 — fixed by mapping the internal endpoint to accept both
verbs, since the resolved Spring Cloud Gateway 5.0.2 artifact has no method-mutation filter factory
to invent a YAML fix from; `JwtAuthenticationFilter` evaluates the pre-rewrite path (which doesn't
match its `/api/internal/**` bypass), so a real `Authorization` header and a real `X-User-Id` were
silently reaching an endpoint whose entire documented trust model assumed neither ever arrived —
fixed by having `DemoResetAuthorizationFilter` strip both, and replace rather than append to any
caller-supplied `X-Internal-Api-Key`; and route precedence was specified as YAML list order when
Gateway actually sorts by `Route.order` (defaulting to `0` for every route today) — fixed with an
explicit `order` plus a defensive route-id check in the filter itself, so a future precedence
regression can't silently attach the internal key to a request now headed somewhere else; **pass 18
(Codex)** found 0 P1 + 3 P2 — the transport architecture itself confirmed correct, only precision gaps
left: the routed integration test description named only path+key, not the other invariants pass 17
introduced (method preserved, `Authorization`/`X-User-Id` absent, exactly one internal-key value,
both verbs reaching the same call, correct route selected) — spelled out completely now, in one place;
this document claimed portfolio-service's controllers "never" receive the raw JWT, which is false —
`JwtAuthenticationFilter` never strips `Authorization` on any route — corrected to the real reason
(no resource-server integration to parse it, whether or not it arrives); and design.md mis-assigned
`503` to a blank-supplied-key scenario that `InternalApiKeyFilter` actually answers with `403`, `503`
being reserved for portfolio-service's own secret being unconfigured, an unrelated failure mode;
**pass 19 (Codex)** found 1 further P1 + 2 P2 — the master plan's "(3) and (4) ship as one deployable
unit" claim was wrong: api-gateway and portfolio-service are separate runtime artifacts deployed
non-atomically on both AWS (gateway's Lambda alias updates strictly before portfolio-service's build
even starts, same workflow run) and Azure (a genuine parallel job matrix with no guaranteed
completion order), so a live window could exist where the new route forwards to a method
portfolio-service doesn't accept yet — fixed with an explicit staged rollout (portfolio-service's
mapping first and verified, then the gateway bundle, then the frontend control, rollback in reverse);
the consolidated "single integration test" from pass 18 crossed an impossible boundary — a stubbed
portfolio-service can prove transport but not that two verbs reach the same real controller call site
— split into a gateway-side test and a portfolio-service-side test, each scoped to what it can
actually observe; and `DemoResetAuthorizationFilter` never specified what happens when its own
`INTERNAL_API_KEY` read is null/blank, which would have surfaced as a misleading downstream `403` for
what's actually a gateway configuration failure — fixed with an explicit gateway-side fail-closed
`503`, before any downstream call; **pass 20 (Codex)** found 1 further P1 + 2 P2 — the round-19 rollout
note described its first stage as "widening" portfolio-service's demo-reset mapping from `POST` to
`POST`+`PUT`, as if a `POST`-only endpoint already existed there — it doesn't: neither
`DemoResetService` nor any `/api/internal/portfolio/demo-reset` mapping exist in source at all, a fact
this document has stated correctly everywhere else since pass 5, just not in the rollout note, which
drifted into describing a predecessor that was never real — corrected to describe stage 1 as the full
portfolio-service-side build (including its own B1 `HoldingReplacementService` prerequisite,
unchecked, task 4.1); the fail-closed unit test specified stubbing `System.getenv`, which ordinary
Mockito cannot do without extra tooling this codebase doesn't use — fixed with a proper test seam
(constructor takes the resolved value, mirroring how `CloudFrontOriginVerifyFilter` already resolves
its own secret once, just one step more testable); and the rollout sequencing was simultaneously a
`SHALL` here and "not an acceptance criterion" in design.md — resolved by making it a mandatory
release gate owned by the master plan, cross-referenced here without its own normative claim;
**pass 21 (Codex)** found 1 further P1 — design.md's stage-4 rollout bundle grouped the manual-reset
gateway pieces with the login-orchestrated self-call as one deployable unit, blocking the
otherwise-ready manual path (needs only `version`, unaffected by the `updated_at` gap) on unrelated,
still-open work — split into a manual-reset gateway bundle and a separately-gated, later
login-orchestration deployment; this document's own text was not itself in error, since it already
deferred to design.md/the master plan for the full sequence rather than duplicating it.
**pass 22 (Codex, raised via a `tasks.md` review round)** raised a P0 concern that `intent: []` in
`DemoResetService.reset`'s call to B1's `replace` empties the demo portfolio instead of restoring
it — resolved as a clarification, not a defect: B1 `design.md` D3 states `GoldenStateTuplePreparer`
derives its own full desired-holdings tuple internally from the active catalog for a deliberately
empty, validation-passing intent, contrasted there against `CompositionTuplePreparer`, which does
expand ticker/quantity from a supplied intent; the evidence cited for the P0 (the current, pre-B1
demo initializer) reflects a mechanism B1 Wave 4/6 replaces, not the target one this call site is
written against. This document does not itself describe `intent`/`GoldenStateTuplePreparer`
construction at all — design.md D5 carries the clarifying detail (pass 22 note there); nothing here
needed correction.
**pass 23 (Codex, `tasks.md` review round)** found 4 further P1 + 1 P2 — a Wave 9/Wave 8 dependency
mismatch, a test-rigor gap in proving the Golden-State reset chain, an E2E identity/fixture gap, and
cleanup semantics that relied on an endpoint unable to target the demo user — all task-breakdown and
master-plan level, fixed in `tasks.md` and the master plan; this document again needed no
correction.
**pass 24 (Codex, `tasks.md` review round)** found 5 further P1 + 3 P2 — a master-plan production
gate that lost Wave 8's deployment requirement when pass 23 correctly narrowed a different
dependency, an executability/rigor gap in the Golden-State reset proof, a genuine dependency cycle
between two E2E tasks, an unvalidated production probe, and cleanup/rollback verification gaps — all
fixed in `tasks.md`, `design.md`, and the master plan; this document again needed no correction.
**pass 25 (internal, five-agent parallel audit, not Codex)** — dispatched at the spec owner's
request after nine rounds, each agent given a distinct real-source-grounded category rather than a
generic re-read. Found a stale requirements citation, an uncompileable cross-module test, an
unassigned check, and a missing B1 dependency (B1's `GET /api/assets` controller isn't includable
until B1's R-B2 release, not merely Wave 2) — all `tasks.md`-level; this document needed no
correction. Unlike
B1's
requirements.md
(13 revisions), this started as a first synthesis of decisions settled in
`docs/superpowers/brainstorm/2026-08-16-spec-b1-and-auth-ratelimit-hotfix.md` entries [0], [4]-[6]
(Q1-Q10 — **not** Q11/Q12, which entries [4]-[6] left open for Codex and were only resolved in
later entries of that same brainstorm; this spec does not draw on those later entries and should
not imply it does). **Most, not all, SHALL statements trace to that brainstorm — a handful are
B2's own new product/UX decisions, explicitly labeled as such where they occur, not misattributed
as previously settled.** Items still genuinely open are marked **OPEN**. Where this spec's own
citation of B1 has drifted from B1's *current* requirements/design/tasks (B1 has moved since
2026-08-16), the current B1 document wins — cited directly, not through the brainstorm's
now-superseded framing.

**Depends on B1** (`portfolio-composition-contract`) for `GET /api/assets`, `PUT
/api/portfolio/holdings`, optimistic concurrency, and the quantity/catalog validation boundary. B2
adds **no new durable domain persistence** — it does not touch `portfolios`/`asset_holdings` or any
new database table. It does add: ephemeral Redis presence state (Requirement 6, ttl'd, disposable),
the `jti`-in-JWT claim and its gateway-side handling, the `ReadOnlyEnforcementFilter` allowlist
change (Requirement 5 — an authorization rule, not persistence), and a new demo-reset
orchestration path (Requirement 7) that calls into B1's own persistence, not a separate store of
B2's own.

---

## Requirement 1: The picker modal and draft lifecycle

**User Story:** As a user, I want to build a complete desired holding set in one place before
committing it, so that I can review everything I'm about to change before it takes effect.

### Acceptance Criteria

1. THE system SHALL present the picker as a modal. *(Settled, entry [0]: "Draft-and-save in a
   modal".)* Entered from a single primary action on the Portfolio page, labeled **"Edit
   Holdings"** — the modal mechanism is settled; this specific label and its placement as the
   entry point are **B2's own new UX decision**, not sourced from the brainstorm.
2. THE modal SHALL hold an in-progress **draft** — a local, unsaved copy of the desired holding
   set — distinct from the server's last-known state until the user explicitly saves.
2a. **THE draft SHALL be seeded, at open time, with the user's complete current holding set — every
   held ticker present and selected from the start, not an empty set the user builds up.** *(Added
   pass 7 — this was implicit but never stated as a SHALL, and the mockup's Browse screen visibly
   assumed the opposite until pass 6 caught it, since B1's `PUT /api/portfolio/holdings` is a full
   replace, not a diff (B1 Requirement 6.1: "a complete desired holding set"; B1 Requirement 6.4:
   omission of a held ticker means deletion). Requirement 4's own review-step diff —
   added/changed/removed/unchanged —
   only makes sense against this premise: an "unchanged" bucket assumes those tickers were present
   in the draft all along, not absent from it.)*
3. Deselecting a held asset in the draft SHALL mean removing it from the desired set entirely,
   with no separate "remove" affordance beyond deselection. *(Settled, entry [0]: "deselect means
   delete".)*
4. THE system SHALL require exactly **one explicit confirmation step** between building the draft
   and it taking effect — a review step naming what will change, not a second identical browse
   view. *(Settled, entry [0]: "one confirmation at save".)*
5. Canceling or closing the modal SHALL discard the draft with no server-side effect. This applies
   uniformly, **including while a `409` conflict is displayed** (Requirement 4.3-4.4): closing at
   that point is the user's own knowing discard, exactly like the "reload and start over" action —
   the two are both user-initiated, neither is automatic. What Requirement 4.3 forbids is the
   *system* discarding the draft the instant the `409` arrives, before the user has done either.
   *(B2's own new UX decision for the general case, not sourced from the brainstorm; reconciled
   with the brainstorm-settled post-409 behavior in Requirement 4 on review — an earlier revision
   left these two criteria appearing to contradict each other.)*
6. THE empty desired set SHALL be a valid draft, submittable like any other, and SHALL mean
   "remove every holding" — matching B1 Requirement 6.13.
7. **THE modal SHALL implement the WAI-ARIA APG Dialog (Modal) pattern** — added on pass 4 review,
   which found neither spec nor mockup specified any dialog semantics despite `design.md` D1
   correctly noting no existing Dialog primitive is being reused:
   - THE modal root SHALL carry `role="dialog"` and `aria-modal="true"`, labelled via
     `aria-labelledby` pointing at its visible heading, not a redundant `aria-label`.
   - Focus SHALL move into the modal on open and SHALL be trapped within it — Tab/Shift+Tab SHALL
     cycle only through the modal's own focusable elements.
   - Focus SHALL return to the triggering `EditHoldingsButton` on close, by whichever path closes
     it — close button, `Escape`, or a successful save.
   - `Escape` SHALL close the modal with the same discard semantics as 1.5, including from the
     conflicted state (Requirement 4).
   - Every interactive element SHALL be reachable and operable by keyboard alone.
   THIS criterion SHALL be verified by an automated accessibility check (e.g. axe, or a
   Testing-Library focus-order assertion) as part of implementation, not by visual review of the
   mockup alone — the mockup's static markup can only carry the `role`/`aria-*` attributes as a
   structural reference; focus trapping, restoration, and keyboard operability are runtime
   behavior it cannot demonstrate.
8. **"Keyboard operable" (1.7) governs the modal shell; the controls inside it need their own
   semantics, named explicitly on pass 5 review** after the modal-shell fix alone left them
   unnamed and non-semantic:
   - Each asset row's selection control SHALL be a native `<input type="checkbox">` or an element
     carrying `role="checkbox"` with a live `aria-checked` state and an `aria-label` naming the
     asset (e.g. "Select AAPL") — a plain unlabelled `<div>`, however it looks, exposes no control
     to assistive technology at all.
   - Each quantity input SHALL carry an `aria-label` tying it to its asset (e.g. "AAPL quantity"),
     not rely on visual proximity to the row's ticker alone.
   - A `Retained_Deprecated_Position`'s **selection control** (its checkbox) SHALL remain fully
     operable — checked, focusable, and togglable — since deselecting it is exactly how the picker
     removes it (2.4, 1.3); it SHALL NOT carry `aria-disabled` or any other state that tells
     assistive technology the control cannot be activated. The **reduce-or-remove-only, never
     increase** constraint (2.4) applies to its *quantity input*, not the checkbox, and SHALL be
     communicated there via `aria-describedby`-linked explanatory text plus the input's `max`
     attribute as a supplementary hint — **not primary enforcement**: since the input carries
     `inputmode="decimal"` on a text-type control (Requirement 8 treats quantity as a string, never
     a parsed number), native browser `max` validation does not fire on it. Real enforcement is a
     client-side validation check before submission (already required by 2.4), not the HTML
     attribute alone. *(Pass 6 correction: an earlier draft put `aria-disabled="true"` on the
     checkbox itself, which told assistive technology the removal action 1.3/2.4 requires stay
     available was unavailable, and treated `max` as sufficient enforcement on its own.)*
   - Any client-side validation rejection (2.4's increase-rejection, or a malformed quantity) SHALL
     surface as text associated with its input via `aria-describedby`, not a color change or a
     tooltip with no programmatic association.
   - The step indicator (Browse/Review/Save) SHALL mark its current step with `aria-current="step"`
     — the fill-color/font-weight distinction used visually is not exposed to assistive technology
     on its own.
   - The draft-count summary (e.g. "N in draft") SHALL be a polite live region (`aria-live="polite"`)
     so a screen-reader user is told when it changes, since it updates without a page navigation.
   - The post-save confirmation (Requirement 3's toast/banner) SHALL be a `role="status"` (or
     equivalent `aria-live="polite"`) region, since it too appears without a page navigation.
   *(Scope note, pass 5: this criterion governs the picker's own new UI. It does not extend to the
   existing Portfolio page's sidebar navigation or holdings table, which B2 does not own or modify
   — any accessibility gaps found there in the mockup were fixed as incidental reference hygiene,
   not because this spec requires B2 to redesign pre-existing components.)*

## Requirement 2: Browsing and selecting assets

**User Story:** As a user, I want to find and select from the full set of assets I'm allowed to
hold, including ones I already hold that are no longer actively offered, so that I can manage my
real position accurately.

### Acceptance Criteria

1. THE browse view SHALL list catalog entries fetched from `GET /api/assets` (B1). Search by
   ticker or name is **B2's own new UX decision** — the brainstorm settled the response shape
   (Requirement 2.2) but not a search affordance over it.
2. THE browse (selectable-for-addition) list SHALL show only `Active_Asset` entries. A
   `Deprecated_Asset` not already held SHALL NOT be offered for selection. *(Settled, entry [5]
   Q2/Q7: active-only offering in the browse pane, full catalog available for resolving an
   existing deprecated position.)*
3. IF the starting state (the portfolio as last read) already holds a `Retained_Deprecated_Position`,
   THE draft SHALL include it, rendered distinctly from active selections, labeled with its
   catalog metadata (name, asset class). *(Settled, entry [5] point 1: a picker showing only
   active assets cannot distinguish a legitimate retained position from an unknown ticker.)*
4. A `Retained_Deprecated_Position` in the draft SHALL be reducible or removable, and SHALL NOT be
   increasable — an attempt to increase it SHALL be rejected client-side before submission, not
   only by the B1 server boundary. *(Matches B1 Requirement 6.9/6.10; client-side rejection is B2's
   own UX obligation so a doomed request never reaches the server.)*
5. THE quantity for a selected or draft-held asset SHALL be entered as free text and validated
   client-side against B1's Quantity_Domain (required, strictly positive, at most 11 integer and 8
   fractional digits) before it can be added to the draft.
6. THE draft SHALL reject a duplicate ticker, matching B1 Requirement 6.6. **B2's own new UX
   decision**, not sourced from the brainstorm: making this structurally impossible in the browse
   UI (selecting an already-drafted ticker edits its existing draft row, it does not add a second
   one) rather than surfacing it as a save-time error.

## Requirement 3: Prices and freshness

**User Story:** As a user, I want to see what my selections are worth as I build the draft, and to
know when the portfolio's overall pricing might be stale, without the browse experience being
slowed down by pricing every catalog entry.

### Acceptance Criteria

1. THE system SHALL fetch and display prices only for assets currently in the draft (selected),
   via the existing `/api/market/prices?tickers=` endpoint — never for the full browse list.
   *(Settled, entry [0]/[5] Q2: "Prices shown for selected assets only, not the browse list";
   avoids pricing ~160 assets to render a scrollable list.)*
2. THE Portfolio page SHALL show one compact freshness status at the **portfolio level** —
   reflecting B1/Spec A's aggregate `assetPriceFreshness` — not a per-holding badge.
   *(Settled, entry [5] Q6: a row badge would require the client to duplicate backend
   freshness-precedence logic and would drift; if per-holding badges are wanted later, that is a
   backend addition specified separately — the client must not derive it independently.)*
   **Status, not assumption (pass 5 cross-audit finding, citation corrected pass 6):**
   `assetPriceFreshness` is a Spec A (`supported-asset-integrity`) *design* commitment, not a field
   the running backend returns today. The portfolio-level rationale (why one aggregate signal, not
   a per-holding one) is B1's own `requirements.md` D13 ("Freshness is portfolio-level, and B1 adds
   nothing for it") — *not* B1 `design.md`, which has its own, separate D-lettered decisions running
   only D1 through D11 and has no D13 at all. (B1 `requirements.md`'s own D-series runs further, to
   at least D14 — the two documents number their "Recorded Decisions" independently; a bare "D13"
   is ambiguous between them and this criterion previously resolved it to the wrong one.) The actual
   response contract — the `assetPriceFreshness`
   JSON shape itself — lives in **Spec A's `design.md`** (`{ "state": ..., "staleHoldings": ... }`
   on the portfolio-summary response), not in either of B1's documents at all. Verified directly:
   the field appears nowhere in `portfolio-service` or frontend source, and Spec A's own
   `tasks.md` task 8.6 (the freshness summary contract that would produce it) is unchecked.
   This criterion is unaffected in shape (B2 still consumes the aggregate, never derives it
   independently), but is gated on that Spec A task landing, same as every other backend field this
   spec depends on and hasn't previously flagged as pending.
3. THE freshness status SHALL disclose counts by state (stale / unknown / missing) in a tooltip or
   detail view, not only a single aggregate word.
3a. **The "Details" affordance's interaction contract, written out — added pass 9, since a button
   alone doesn't close criterion 3.** THE Details control (both on the Portfolio page and, per
   criterion 4, unchanged on the post-save page) SHALL open a compact popover anchored to the
   button — not a full-page navigation, not a modal — dismissed by `Escape`, an outside click, or
   re-activating the button. Content, exhaustively:
   - One line per non-`FRESH` state present: `Stale: N`, `Unknown: N`, `Missing: N` — omitting a
     state entirely when its count is zero, never showing "0" rows.
   - IF every count is zero (portfolio-level state is `FRESH`), the popover SHALL say so in one
     line rather than opening empty.
   - The oldest-known-observation timestamp (already shown inline as "Prices as of ___"), restated
     here as an absolute date-time, not only the relative "3 days ago" the inline banner uses —
     relative time is a summary, not the disclosure this criterion requires.
   - **Absent-timestamp case**: IF `oldestKnownAssetPriceObservationTimestamp` is itself absent
     (Spec A's contract allows this — an empty portfolio, or a portfolio entirely in `MISSING`
     state, has no timestamp to report), the popover SHALL say so explicitly ("No price
     observation on record") rather than rendering a blank or a parse error.
   Keyboard/focus: THE button SHALL be a real `<button>` with `aria-haspopup="dialog"` (pass 10
   fix: not `aria-haspopup="true"`, which WAI-ARIA defines as equivalent to `menu` — the wrong
   popup type for a panel that is neither a menu nor a disclosure), `aria-expanded` reflecting open
   state, and `aria-controls` referencing the popover. THE popover container SHALL carry
   `role="dialog"` and an accessible name (`aria-label` or `aria-labelledby`) — required because
   this is the focus-transferring popup pattern, not the disclosure pattern: opening SHALL move
   focus into the popover (to its first focusable element, or the popover container itself if
   none); closing SHALL return focus to the button. (The disclosure pattern is not used here
   precisely because focus moves off the button; a disclosure would instead keep focus on the
   button, use only `aria-expanded`, and omit `aria-haspopup` entirely.) This is the same
   interaction pattern already required of the picker modal (Requirement 1.7) — a smaller-scoped
   instance of it, not a new one invented here.
4. **A successful composition save SHALL NOT be assumed to make prices fresh (added pass 6).**
   `PUT /api/portfolio/holdings` writes holdings only — it does not touch price observations, which
   a separate, independently-scheduled market-data process refreshes. The post-save Portfolio page
   SHALL re-read and render the actual current `assetPriceFreshness` (criterion 2), which MAY still
   show stale holdings identical to what was shown before the save; it SHALL NOT display a
   save-triggered "just now" timestamp or an inferred fully-fresh state that the write path has no
   mechanism to produce.

## Requirement 4: Saving and version conflicts

**User Story:** As a user, I want my save to either fully apply or be clearly rejected with a
reason I can act on, never silently merged or silently lost.

### Acceptance Criteria

1. THE system SHALL submit the draft as one atomic `PUT /api/portfolio/holdings` call (B1),
   carrying the field named `expectedVersion` (not `version` — B1's actual wire contract, see
   `design.md` D2) read when the modal opened (or when the draft was last reconciled with the
   server). An absent `expectedVersion` produces B1's `400 missing_version`, so this field is
   never optional on the client side.
2. WHEN the save succeeds, THE system SHALL replace the visible portfolio state with the response
   body's actual holdings and version — never with the client's own draft — so the UI reflects
   exactly what the server persisted.
3. WHEN the save returns `409 portfolio_version_conflict`, THE system SHALL NOT automatically
   reapply, merge, resubmit, or discard the draft. THE draft SHALL remain visible, read-only, with
   further edits and resubmission disabled, until the user takes the explicit recovery action in
   4.4. *(Settled, entry [5] Q4, verbatim: "it does not automatically reload or discard the
   browser draft. The user-facing reload action is what discards it" — matching B1's own D7,
   "the user-facing reload action is what discards the draft, and the user takes it knowingly."
   An earlier revision of this criterion said the system discards the draft "entirely" on the
   409 itself, which contradicted this and 4.4 below — corrected.)* Because the draft can hold the
   user's full holding set, THE read-only draft list SHALL be presented in a labelled,
   keyboard-focusable scroll region — `role="region"`, an `aria-label` naming it, and
   `tabindex="0"` on the region itself, so a keyboard user can reach and scroll it (pass 11
   addition: this is the region's own contract, distinct from and not in tension with design.md
   D1's rule that the *rows* inside carry no `tabindex`/`role="checkbox"`/`aria-disabled` — the
   region is the one focusable stop, the rows are never individually focusable). Verification
   SHALL include a keyboard-only scroll test (tab to the region, then Page Down/arrow keys
   advance it), not only a pointer-driven one — round 11 caught a container whose CSS silently
   blocked pointer-driven scrolling entirely, and a keyboard-only check would have caught it
   independent of the CSS bug.
4. ON a `409`, THE system SHALL present a plain-language explanation (something else changed the
   portfolio; the draft cannot be safely reapplied) and one explicit recovery action — "reload and
   start over" — that reloads current state and discards the draft. Closing the modal instead
   (Requirement 1.5) also discards it, without reloading. Either way, discard happens only by the
   user's own action, never automatically the instant the `409` arrives.
5. THE system SHALL treat a first-time save (no existing portfolio) the same as an update from the
   caller's perspective — B1 Requirement 6.22/6.23 auto-provisions on expected version `0`. THE
   picker SHALL be openable by a brand-new user with no portfolio yet.
   *(Settled, entry [6] Q8: one product-level portfolio per user, no id on the wire, provisioned
   on first write.)*

## Requirement 5: Demo account write access

**User Story:** As a demo visitor, I want to try the picker on the seeded demo portfolio, so that
I can evaluate the product without creating an account — without being able to mutate anything
else the read-only demo account is restricted from.

### Acceptance Criteria

1. `ReadOnlyEnforcementFilter`'s allowlist SHALL become **method-plus-path**, not path-only, and
   SHALL allowlist specifically `PUT /api/portfolio/holdings` (composition) and
   `PUT /api/portfolio/demo-reset` (Requirement 7's manual reset trigger — added on review; an
   earlier revision listed only the composition route, before D5's reset boundary was corrected to
   need its own browser-reachable endpoint) for the demo account.
   *(Settled, entry [5]: "Its current allowlist is path-only; adding the holdings path there would
   silently allow every mutating method on that path, including future ones." Confirmed directly
   against current source, not just the brainstorm's claim: `aiAllowlistPatterns` is a
   `List<String>` matched only by `matcher.match(pattern, path)` — `ReadOnlyEnforcementFilter.java`
   — no method component exists today.)*
2. No other mutating method or path SHALL become reachable for the demo account as a side effect
   of this change.

## Requirement 6: Presence (advisory only)

**User Story:** As a user editing the shared demo portfolio, I want a hint that someone else might
be editing it too, so I'm not surprised by a conflict — without the system pretending to coordinate
or lock anything on my behalf.

### Acceptance Criteria

1. THE system SHALL derive a presence signal from a random `jti` claim added to each issued JWT,
   hashed one-way as the session identifier. Two tabs under one login SHALL count as one session;
   two independent logins SHALL count as two. *(Settled, entry [5]: a client-supplied identity is
   unnecessary and spoofable.)*
2. THE gateway SHALL refresh a demo session's presence entry best-effort on authenticated demo
   traffic, with a TTL set against the existing 60-second polling cadence. **The 150-second value
   is OPEN** — entry [5] proposed it explicitly as provisional ("a provisional 150-second TTL"),
   and this revision had incorrectly promoted it to a flat commitment. Treat it as a starting
   configuration value pending confirmation, not a frozen requirement.
3. THE picker SHALL query presence **once**, on open, via an authenticated `GET` request to a
   gateway-owned endpoint (`design.md` D4 names it explicitly) — never by querying Redis or any
   other store directly from the browser. No polling, no acquire/release semantics, no write
   blocking on presence state.
4. WHEN another active demo session is detected, THE modal SHALL show one persistent advisory
   banner: *"Another demo session is active — your changes may not save."* It SHALL NOT block,
   delay, or alter the underlying request in any way.
5. IF the presence check errors or the backing store (Redis) is unavailable, THE system SHALL show
   no banner and SHALL NOT delay or fail the underlying request. Presence is strictly best-effort.
6. Presence SHALL NOT be consulted by, or have any effect on, the demo reset mechanism
   (Requirement 7). *(Settled, entry [6] Q9: ruled out explicitly — presence staying advisory means
   it cannot quietly become a reset lease.)*

## Requirement 7: Demo reset

**User Story:** As a recruiter or evaluator visiting the demo, I want to see a reasonably complete,
sensible portfolio most of the time, even if a previous visitor left it edited or emptied.

### Acceptance Criteria

1. THE demo reset SHALL use the holdings-only seed path and SHALL NOT write either price table.
   *(Spec A's D20, inherited unchanged.)*
2. **B1 delivers the Identity_Preserving_Reset as an internal service-layer mechanism** — the
   actual in-place holdings replacement that preserves the portfolio's `id` and advances its
   version without deleting and recreating the row (current B1 Requirement 8.11-8.12) —
   **not as an HTTP endpoint B2 can call for the demo user.** Verified directly: B1's own seed
   endpoint (`PortfolioSeedController`) hard-codes its target to the compiled-in E2E test user
   (`E2E_USER_ID`), accepts no caller-supplied target or portfolio id (B1 `design.md` D8: *"the
   target remains server-fixed... takes no parameters... the body carries `expectedVersion`
   only"*), and is protected by `X-Internal-Api-Key`, a credential that cannot be exposed to the
   browser. **B2 cannot invoke this endpoint for the demo user — it is architecturally fixed to a
   different user entirely.** An earlier revision of this requirement pseudo-called
   `B1.identityPreservingReset(demoPortfolioId, expectedVersion, targetState)` as if that were an
   existing contract; it is not.
3. **B2 SHALL define its own demo-scoped reset boundary**, reusing B1's underlying primitive
   (`HoldingReplacementService` plus a target-state preparer, the same internal architecture B1's
   own seed controller uses — see B1 `design.md` D8) rather than duplicating it, but exposed
   through B2's own entry points, each fixed to the demo user specifically:
   - A **server-side path**, invoked during api-gateway's login flow when the demo user logs in
     and the idle check (7.4) finds the reset eligible — backend-to-backend, so it can use the
     same internal-trust boundary the seed path uses without ever reaching the browser.
   - A **browser-facing path** for the manual trigger (7.5) — an ordinary authenticated endpoint
     reachable by the demo user's own JWT, allowlisted in `ReadOnlyEnforcementFilter` the same way
     as the composition `PUT` (Requirement 5). *(Pass 16 correction: earlier revisions of this bullet
     said "not the internal API key" — true of what the *browser* sends, but as of pass 16 the
     internal key IS involved one hop later: api-gateway attaches it, server-side, only after the
     JWT-subject check passes, then forwards to the internal endpoint described in 3 above (pass 20
     wording fix: "existing" here and elsewhere in this criterion means "the same one already
     described in this document," not "already deployed" — neither the internal endpoint nor
     `DemoResetService` exist in source yet; see design.md D5's rollout note). The browser still
     never sees or sends it. Pass 14 briefly said this endpoint's handler must be hosted in
     api-gateway; pass 15 found that broke Gateway's own filter-chain protections and reverted the
     handler to portfolio-service, but that reverted handler turned out to have no protection against
     the public AWS Function URL at all; pass 16 removes that handler entirely and reaches the
     original internal endpoint via a Gateway route rewrite instead — see 7.3a's note.)*
   Both paths SHALL carry the exact Portfolio_Version observed at the moment eligibility was
   decided into the underlying reset call — never a version re-read inside the call itself.
   *(Current B1 Requirement 8.32-8.36: the eligibility decision and the write precondition must be
   the same observation, or a user's commit between the two gets silently overwritten; no retry on
   conflict, for the same reason Requirement 4.3 forbids the picker from retrying its own
   conflicts.)* **That observation SHALL come from B1's existing `GET /api/portfolio` response**
   (which is *specified* to carry `version` — B1 Requirement 5.10 — but does not yet: B1 tasks 4.10
   and 5.1 are both unchecked and `PortfolioResponse` carries no version field in source today,
   verified directly. Whether it carries `updated_at` is a separate, currently **unassigned** gap —
   see 3d below, not a Wave-3 given. *(Pass 5 cross-audit finding: this criterion had reverted to
   the pre-pass-5 "already carries" framing design.md's D5 was corrected out of; both now agree.)*),
   never from a dedicated version-read endpoint, which B1 permanently prohibits (`requirements.md`
   5.11: *"THE
   system SHALL NOT expose the Portfolio_Version through a separate endpoint, because a read-then-
   read sequence reintroduces the race the version exists to close"*). An earlier revision of this
   requirement left the manual trigger's endpoint bodyless, re-reading "current" version inside the
   reset handler itself — that is exactly what B1 Requirement 8.33 forbids, and has been corrected:
   the manual trigger's request body carries the `expectedVersion` the browser already observed
   from its own prior `GET /api/portfolio` call, the same discipline Requirement 4.1 already
   requires of the composition `PUT`.
3a. **THE GATEWAY SHALL independently verify the caller is the demo account** before any request
   reaches `DemoResetService` (JWT subject equals the compiled-in demo user id) and SHALL return a
   pinned `403` for any other authenticated caller. *(Pass 16 wording correction: this criterion said
   "THE `PUT .../demo-reset` handler SHALL..." through pass 15, which no longer describes where the
   check actually runs — see the note below; there is no handler at that path to bind the check to
   any more.)* *(Added on review: `ReadOnlyEnforcementFilter`'s allowlist only
   ever gates a `ro=true` principal — verified directly against its `decide()` method, which
   returns `false` immediately whenever `ro` is false. Allowlisting this path makes it reachable
   *despite* `ro=true`; it does not restrict the path *to* the demo account. Without this
   criterion, any authenticated user could reset the shared demo portfolio.)* **This criterion
   binds the `PUT` path only** (self-audit wording fix: earlier said "handler," which the pass-16
   note two sentences above already retired — there is no handler at that path any more, only a
   Gateway route and filter). The server-side (`POST /api/internal/...`) trigger has no JWT to
   check at all — `/api/internal/**` bypasses JWT entirely at the gateway and its caller-supplied
   `X-User-Id` is stripped, not forwarded (`JwtAuthenticationFilter.java` lines 39-51,
   `SecurityConfig.java` line 40, verified directly) — so its authorization is exclusively the
   `X-Internal-Api-Key` header plus its server-fixed target; a JWT-subject check there would have
   nothing to check against. *(Pass 4 correction: the master plan had read this criterion as
   applying to both endpoints.)* **Where this check runs (pass 14 addition, corrected pass 15,
   corrected again pass 16, corrected again pass 18):** portfolio-service has no JWT decoder or
   principal — it depends on no Spring Security OAuth2 resource-server integration at all — so it
   cannot perform this criterion's "verify the caller's JWT subject" itself, **even though the raw
   `Authorization` header does normally reach it.** *(Pass 18 correction: an earlier draft said
   portfolio-service's controllers "only ever receive api-gateway's own `X-User-Id` header injection,
   never the token itself" — false as a general claim: `JwtAuthenticationFilter` never strips
   `Authorization` on any route, on any branch (verified directly, `JwtAuthenticationFilter.java`) —
   it forwards the bearer token unexamined alongside the `X-User-Id` it injects. The reason
   portfolio-service can't check the subject isn't that the token never arrives; it's that nothing in
   portfolio-service is wired to parse or validate it if it does. `DemoResetAuthorizationFilter`
   (design.md D5) strips `Authorization` specifically for the demo-reset path as its own, separate
   hygiene measure — not because the token would otherwise be unreachable, but because there's no
   reason for it to reach an endpoint that was never going to use it.)* Pass 14 concluded from
   this that a `PUT` **handler** had to move into api-gateway (wrong — a local api-gateway controller
   silently loses `JwtAuthenticationFilter`/`CloudFrontOriginVerifyFilter`/`ReadOnlyEnforcementFilter`,
   all of which are Gateway `GlobalFilter`s that only run for requests Gateway *routes*, never for a
   local controller's own handling — its own javadoc says "before routing"). Pass 15 reverted the
   handler to portfolio-service but gave it a *second* controller at `/api/portfolio/demo-reset` with
   no protection at all against a direct call to the public AWS Function URL — `InternalApiKeyFilter`
   only gates `/api/internal/**` and passes every other path through untouched, so that handler needed
   no identity header whatsoever, worse than every other portfolio-service endpoint. **The corrected
   design (design.md D5): there is no handler at `/api/portfolio/demo-reset` in portfolio-service at
   all.** `PUT /api/portfolio/demo-reset` is a dedicated api-gateway route (`demo-reset-manual`,
   given an explicit `order` lower than the generic route — pass 17 correction: list position alone
   does not determine Gateway route precedence) that a new Gateway `GlobalFilter` —
   `DemoResetAuthorizationFilter`, ordered at `Ordered.HIGHEST_PRECEDENCE + 4`, after both
   `JwtAuthenticationFilter` (+2) and `ReadOnlyEnforcementFilter` (+3) — gates: it confirms the
   matched route is genuinely `demo-reset-manual` — via
   `ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR`, never the similarly-named but always-null-by-dispatch-
   time `GATEWAY_PREDICATE_ROUTE_ATTR` (self-audit precision fix; full mechanism and rationale in
   design.md D5) — checks the JWT subject, and
   only on a match strips any caller-supplied `Authorization`/`X-User-Id`/`X-Internal-Api-Key` and
   sets the authoritative internal-key value (pass 17 correction — see design.md D5 for why: this
   filter runs before the JWT-subject-satisfying route's own `RewritePath`, so without this step a
   real `Authorization` header and a real `X-User-Id` would otherwise reach the internal endpoint,
   which the rest of this criterion assumes never happens) before the route's `RewritePath` filter
   forwards the request to the internal endpoint described above, the same one the login-orchestrated
   trigger also reaches — mapped to accept `PUT` as well as `POST` from the start, not widened from a
   `POST`-only predecessor (pass 20 correction: nothing here exists in source yet, so there is no
   predecessor to widen — see design.md D5's rollout note) (pass 17 correction:
   `RewritePath` changes only the path, never the method, so the rewritten request arrives as `PUT`,
   not `POST`). A mismatch returns the pinned `403` (exact body: 3a above) before the route
   is ever reached. The manual trigger and the login-orchestrated trigger now reach
   `DemoResetService` through the exact same protected entry point, just by two different transports.
   **Implementation constraints, self-audited after pass 16 and detailed further in design.md D5, not
   repeated here in full:** `DemoResetAuthorizationFilter` and D6's `ReadOnlyEnforcementFilter`
   allowlist entry SHALL ship as one deployable unit — shipping the filter without the allowlist entry
   403s the demo user's own click before the filter ever runs. The filter SHALL match the exact
   `(PUT, /api/portfolio/demo-reset)` pair, method AND path together — matching on method alone would
   attach `X-Internal-Api-Key` to every `PUT` the demo user makes, including the ordinary composition
   save. Because api-gateway and portfolio-service deploy as separate, non-atomic artifacts (pass 19
   finding — verified against both production deploy workflows in design.md D5), this filter/route
   bundle is NOT one deployable unit with portfolio-service's own internal endpoint — building and
   deploying that endpoint is a **release-gate prerequisite, not a requirements-level acceptance
   criterion** (pass 20 clarification, resolving a contradiction: an earlier draft used `SHALL` here
   for the exact same sequencing design.md called "not a new acceptance criterion" — this is release
   orchestration, not user-visible product behavior, so it belongs in the master plan's task
   breakdown as a mandatory gate; this document defers to it rather than duplicating a second,
   inconsistent classification). Full sequence and the gate itself: design.md D5 and the master plan.
   **IF `System.getenv("INTERNAL_API_KEY")` resolves null or blank at api-gateway
   (pass 19 addition), THE filter SHALL fail closed with `503`
   (`{ "error": "internal_api_key_not_configured", "message": "The demo reset feature is temporarily
   unavailable." }`) and make no downstream call** — never forward a blank/absent key and let
   portfolio-service's own `403 invalid_internal_api_key` stand in for it, which would misreport a
   gateway configuration fault as if the authenticated, correctly-identified demo user had somehow
   been rejected.
3b. ON success, THE manual trigger SHALL return `200` with the fresh portfolio state, same shape as
   a successful composition `PUT`. ON a stale `expectedVersion`, it SHALL return B1's `409
   portfolio_version_conflict` envelope with the same no-retry, explicit re-observe-and-retry-left-
   to-the-user semantics as Requirement 4, **regardless of where 7.6 ultimately places the manual
   control.** *(Narrowed on pass 4 review: an earlier draft said the picker's `ConflictPanel`
   handles this `409`, which presumes the picker is open — true only if 7.6 places the control
   inside the picker. If the control instead lives outside the picker, e.g. a standalone
   page-level button, there is no open draft to protect and no `ConflictPanel` to show it in; that
   placement's `409` SHALL instead surface as a simple, draft-free error notice — same envelope
   and no-retry contract, placement-appropriate presentation.)* The envelope and no-retry rule are
   settled now; only the presentation is conditional on 7.6's still-open placement decision.
3c. THE login-orchestrated trigger SHALL be **fail-open relative to login on both sides of the
   call**: if its reset call loses the version race (a `409`, meaning something else wrote to the
   demo portfolio between the eligibility read and this call), login SHALL proceed unaffected and
   the conflict SHALL NOT be surfaced to the user — an opportunistic reset losing its race is an
   expected outcome, not a login failure. **Equally, IF the eligibility read itself
   (`GET /api/portfolio`, needed for `version` and `updated_at`) fails, times out, or its backing
   store is unavailable, THE login flow SHALL skip the reset attempt entirely and proceed** — the
   same fail-open principle one step earlier, not only at the reset call's own conflict. *(Pass 4
   addition: the previous draft covered only the reset call's `409`, leaving the earlier read's
   failure mode unspecified.)* **The fail-open condition is "any outcome other than a clean
   success," not an enumerated list of codes** — any status outside 2xx (`403`, `429`, `5xx`,
   or otherwise) on either the eligibility read or the reset call SHALL be treated identically to a
   timeout: skip, proceed, no user-visible error. *(Pass 6 addition: `design.md` D5 identifies two
   concretely reachable 4xx cases — an `X-Origin-Verify` mismatch on the gateway self-call, and the
   portfolio route's own rate limiter — that an enumerated "timeout, 5xx" list would have missed.)*
3d. **`updated_at` exposure on `PortfolioResponse` is an unassigned cross-spec gap, not a
   Wave-3 given.** Verified against B1's actual tasks: B1 Wave 3/V20 adds the `updated_at`
   **column** to the `portfolios` table (B1 `requirements.md` 5.14-5.15), but no B1 task exposes it
   on the wire — Wave 5 task 5.1 exposes only `version` on `GET /api/portfolio`
   (`portfolio-composition-contract/tasks.md:688`). Until either B1 gains a task exposing
   `updatedAt` there (the natural sibling of 5.1) or B2 is explicitly told to own that read-contract
   addition itself, criterion 4's idle-reset trigger below is not implementable. *(Pass 4 finding:
   an earlier draft of criterion 3 assumed Wave 3 alone would put `updated_at` on the response.)*
4. THE reset trigger SHALL be: on demo login, reset if and only if the demo portfolio has been
   idle longer than a threshold (provisionally **30 minutes** — OPEN). *(Settled, entry [6] Q9:
   durable, no scheduler, no lease, no delayed-job infrastructure; rejects reset-on-logout as
   unreliable and a fixed schedule as capable of erasing a visible session.)* **Correction to the
   idle signal itself:** entry [6] proposed reading `portfolios.updated_at` and asserted the
   column already exists. It does not — B1's own `requirements.md` D12 is an explicit correction of
   that same claim *(pass 5 cross-audit fix: previously misattributed to B1's `design.md`, which
   has no D12 section — B1's design.md runs D1 through D11)*: *"Entry [6] answered Q9 by proposing
   an idle guard on `portfolios.updated_at`
   and asserted the column already exists, citing `V1__Initial_Schema.sql:32`. That line is
   `market_prices.updated_at`. The `portfolios` table is `(id, user_id, created_at)` and no later
   migration adds to it."* `updated_at` is added to `portfolios` by B1's own V20 migration (Wave
   3) — but, per 3d, the column alone is not enough. **This reset trigger is therefore not
   implementable until `updated_at` is exposed on `PortfolioResponse`** — later and narrower than
   "B1 Wave 3 lands," which this spec previously stated.
5. THE reset SHALL also be triggerable manually via an explicit control, independent of the
   login-time idle check. *(Settled, entry [6] Q9: "manual button, plus reset-on-demo-login".)*
6. **OPEN — not yet decided, needs a product call:** the exact idle threshold (30 minutes is a
   starting value, not a commitment) and where the manual reset control lives in the UI.

## Requirement 8: Decimal fidelity end to end

**User Story:** As a user, I want the exact quantity I typed to be the exact quantity that gets
saved, with no floating-point drift introduced by the trip through the browser.

### Acceptance Criteria

**Correction to this requirement's original premise:** entry [6] E1 identified a real hazard as of
2026-08-16 (the read side emitted quantity as a JSON number). **B1 owns and has designed the fix,
but has not yet implemented it — ownership is settled, implementation is not** *(pass 6 correction:
this criterion still said "B1 has since closed it entirely" and "task 4.9 implements both
directions," the exact overclaim `design.md` D3 was already corrected out of; the two documents
must agree, and now do)*: current B1 Requirement 4.1-4.7 requires decimal strings both directions
(4.1 is the write-direction mandate; 4.2 the read-direction one; 4.3-4.7 supporting/enforcement
criteria — pass 7 correction: an earlier citation of "4.2-4.7" omitted 4.1, the one criterion that
actually states the write-side requirement)
and B1's own `design.md` D6 specifies a `ToPlainStringSerializer` on `HoldingResponse.quantity`, but
B1 task 4.9 is unchecked and `PortfolioResponse.HoldingResponse` still declares an unannotated
`BigDecimal quantity` in source today, verified directly
(`portfolio-service/.../PortfolioResponse.java:33-36`). There is no B1 *design* gap for B2 to flag
— there is real B1 *implementation* work still pending before B2 can build against this live.

**The actual, still-open gap is different: B1's backend contract change and the existing frontend
are on a collision course.** `frontend/src/lib/api/portfolio.ts` declares `interface
BackendHolding { quantity: number }` today — the live frontend already assumes a JSON number, and
nothing in B1's scope touches the frontend (B1 Requirement 10.1: "no frontend change"). If B1's
string-quantity read contract reaches production before this adapter is migrated, the existing
Portfolio page (which B1 does not touch) breaks.

1. THE client SHALL treat every quantity — on read (`PortfolioResponse`/`HoldingResponse`) and on
   write (the `PUT` body) — as a plain-decimal string, never as a parsed JavaScript `number`, from
   the moment it is read until the moment it is either displayed (formatted for humans) or
   submitted (sent verbatim).
2. THIS spec SHALL migrate `BackendHolding.quantity` (and any type deriving from it) from `number`
   to `string`, and SHALL audit every consumer of that field for a place that does arithmetic on
   it directly rather than through an explicit, intentional string→number conversion at a display
   boundary.
3. THE rollout of B1's string-quantity read contract SHALL NOT reach production ahead of this
   adapter migration — B1 Wave 4/5's deploy and this criterion's frontend change need sequencing,
   not independent timing, since B1 itself carries no obligation to protect a frontend it isn't
   allowed to touch.
4. Draft quantity edits SHALL operate on the string representation (e.g. append/replace digits),
   converting to a number only for display-only calculations (estimated value), never for the
   value that gets submitted.

---

## Non-goals (carried from B1's own Requirement 10, restated for B2's own scope)

1. THIS spec SHALL NOT introduce multi-portfolio selection or a portfolio identifier anywhere in
   the picker's UI or wire calls.
2. THIS spec SHALL NOT introduce a trade ledger, transaction history, or weighted-average cost
   inference — the picker edits a snapshot; it does not record a transaction the user didn't
   supply (matches B1 Requirement 6.17).
3. THIS spec SHALL NOT add per-holding freshness state to the backend as a prerequisite — it
   consumes the aggregate signal Requirement 3 depends on (pending Spec A task 8.6, per that
   requirement's pass-5 note — "existing" overstated this before). A future per-holding freshness
   feature is a backend addition specified elsewhere, not assumed here.
4. THIS spec SHALL NOT implement "Profile changes" (account settings) — a separate, currently
   unscoped initiative.

## Open items, explicitly not resolved by this revision

- **Q9's idle threshold** (Requirement 7.6) — needs a product decision on the exact minute value.
- **Manual reset control's location in the UI** (Requirement 7.6) — needs a product decision.
- **Presence TTL's exact value** (Requirement 6.2) — 150 seconds is entry [5]'s own provisional
  figure, not a commitment.
- **Login-orchestrated self-call timeouts** (`design.md` D5) — 2 seconds per leg (eligibility read,
  reset call) and 4 seconds overall are starting values, not commitments, same provisional-value
  treatment as the presence TTL and idle threshold above. *(Added pass 8: previously marked "OPEN"
  only inline in D5, with no entry in this list — the same gap already caught for `updatedAt` and
  `assetPriceFreshness`.)*
- **The frontend decimal-adapter migration's rollout sequencing** (Requirement 8.3) — needs
  explicit coordination with B1's Wave 4/5 deploy timing, not just a statement that it must happen
  first.
- **`updatedAt` exposure on `PortfolioResponse` has no owner** (Requirement 7.3d) — **blocking**,
  not a nice-to-have: without it, criterion 4's idle-reset trigger cannot be built at all. Added on
  pass 5 review, which found this gap acknowledged in 7.3d but absent from this list and from
  `design.md` D7 — the two places a reader would actually look for open items. The contract this
  needs, once assigned: wire field name **`updatedAt`** (camelCase, not the database column's
  `updated_at`), type **ISO-8601 timestamp string** (matching `createdAt`'s existing encoding on
  the same response, not a new convention), positioned on each element of the `List<PortfolioResponse>`
  `GET /api/portfolio` already returns (see `design.md` D5's list-shape note), gated on whichever
  B1 wave takes the task, with its own contract test analogous to B1's existing `version` tests.
  Until assigned, this OPEN item and 7.4's implementability gate are the same fact stated twice —
  closing one closes the other.
- **`assetPriceFreshness` has not landed yet** (Requirement 3.2, and now 3.4 too) — Spec A
  (`supported-asset-integrity`) `tasks.md` task 8.6, the freshness summary contract that would
  produce this field, is unchecked; the field appears nowhere in `portfolio-service` or frontend
  source today. *(Added pass 7: this gap was already stated inline in Requirement 3.2's own text,
  but — same failure mode as the `updatedAt` gap above — was never added to this list or to
  `design.md` D7, the two places a reader checks for open items. Requirement 3.4 (post-save
  freshness display) depends on the same field and widens this dependency's blast radius without
  having been cross-referenced here until now.)* Not a product decision to make — a backend
  implementation dependency to track, same class as `updatedAt` above.

**Closed, removed from this list on review (pass 1):** a quantity upper bound was previously
listed as open. It is not — B1 Requirement 3.1 already freezes the domain at
`99999999999.99999999`, and this spec's own Requirement 2.5 already cites it.

**Reclassified, removed from this list on review (pass 2):** row-level freshness badges were
listed here as an open decision. They are not a decision blocking this revision — Non-goals 3
already states this correctly as out of scope for B2 (a future, backend-first addition specified
elsewhere if ever wanted). Listing it as "open" implied B2 needs to decide it to be complete; it
doesn't.
