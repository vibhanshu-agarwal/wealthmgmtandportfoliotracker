# Spec B2: Asset Picker Composition — Design

**Revision 2** — materially revised across twenty-three review passes, **twenty-two by Codex
adversarial review and one (pass 7) an internal parallel-agent audit** (2026-08-21/22; pass 7 is
Claude-run, not
Codex — labeled distinctly below so this isn't misread as a Codex round):
pass 1 found seven P1 + three P2; pass 2 found three further P1 + five P2; pass 3 found four
further P1 + three P2 — the demo-reset boundary's call target, its authorization, and the Conflict
screen's draft visibility; pass 4 found four further P1 + four P2 — the reset's `updated_at`
dependency, the service-boundary call path, the primitive's actual not-yet-built status, dialog
accessibility, and several master-plan overclaims; pass 5 found six further P1 + two P2 — mockup
tickers that don't exist in the real catalog, B1-ownership-vs-implementation-status conflation, the
login-time eligibility read's missing transport/timeout/list-shape/fail-open detail, the
`updatedAt` gap missing from both Open-items surfaces, a wrong preparer type and a missing
cost-basis anchor, and picker-control-level (not just modal-shell) accessibility; pass 6 found five
further P1 + three P2 — the mockup's draft cardinality contradicting its own Review-step diff, a
post-save freshness claim the write path cannot produce, the login self-call's undefined deployable
target and non-exhaustive fail-open list, a lingering decimal-fidelity overclaim, a disabled
checkbox contradicting its own removal affordance, plus citation and provenance corrections;
**pass 7 (internal audit, not Codex)** dispatched five parallel Claude agents to re-verify
everything against real source with fresh eyes — found a held asset (BTC-USD) shown unchecked in
the picker despite appearing in the portfolio's own holdings table, the mockup's holdings count
silently colliding with the real catalog's exact active-ticker ceiling, the login self-call missing
a second secret dependency for its internal-key-protected reset leg, a likely-incorrect filter-
ordering claim now qualified rather than asserted, a manual-trigger dependency misattributed to the
wrong B1 wave, a citation gap in "both directions" decimal fidelity, and the `assetPriceFreshness`
gap missing from both Open-items surfaces; **pass 8 (Codex)** found three further P1 + three P2 —
pass 7's own `INTERNAL_API_KEY` finding was itself wrong (the secret is already deployed to
api-gateway on both clouds; only the code to read it was missing), Conflict's draft summary still
didn't visibly represent the full 146-holding draft, the login self-call's timeout/non-blocking
contract was incomplete for two sequential legs, Track C mixed B2-owned work with external
dependencies in one column, "159 catalog assets" named an ambiguous population, and Success dropped
the Details affordance Main has; **pass 9 (Codex)** found five further P1 + one P2 — the
`X-Origin-Verify` fix from pass 6 was itself not cross-cloud safe (Azure never provisions
`CLOUDFRONT_ORIGIN_SECRET` to api-gateway), the `INTERNAL_API_KEY` binding pass 8 proposed
resolves blank in api-gateway as written, Conflict's draft summary still wasn't genuinely
scrollable/readable, the mockup's freshness example was arithmetically impossible under Spec A's
50-hour stale threshold, the timeout decision was tracked in Open items but dropped from the
production gate, and the Details affordance had no interaction contract — all addressed below);
**pass 10 (Codex)** found three further P1 + one P2 — the Conflict draft's scroll container had
`pointer-events:none` silently defeating wheel-scroll while claiming to show "all preserved
holdings" with only 11 of 146 rows actually rendered, the freshness-details button's
`aria-haspopup="true"` was the wrong WAI-ARIA popup type for what is really a focus-transferring
popover, the master plan mislabeled two Needs-column dependencies as build-list items "(6) and (8)"
of a seven-item list, and this document stated an AWS-only `CLOUDFRONT_ORIGIN_SECRET` access claim
without qualifying it as such, contradicting its own next sentence; **pass 11 (Codex)** found two
further P1 + two P2 — the pass-10 fix's 135 filler rows were a handwritten ticker list rather than
derived from `config/seed-tickers.json`, including 60 tickers B1's contract would reject as
`unsupported_asset` and 4 canonical-name drifts (the same mockup-catalog-fidelity defect class pass
5 already caught once), the draft container's `opacity:0.6` reduced informational asset-name text
below WCAG 2.2's 4.5:1 contrast minimum, this document's D1 "no tabindex at all" wording needed
narrowing to rows/controls only now that the scroll region itself carries one normatively
(requirements.md 4.3), and both spec headers' pass count hadn't yet been advanced past pass 9 — all
addressed below; **pass 12 (Codex)** found one further P1 + two P2 — the pass-11 catalog-derived
generator picked `DOGE-USD` as one of the 144 "unchanged" rows despite Review.dc.html's own diff
listing `DOGE-USD` under "Removed · 1" (catalog membership alone doesn't guarantee consistency with
the draft's own stated diff), both spec headers' pass count still undercounted by one even after the
pass-11 update (it must include the pass doing the counting, a mistake this line itself now repeats
the fix for), and the master plan's intro paragraph had one more stale `updatedAt`-is-"item 6"
cross-reference the earlier table/diagram fixes hadn't reached — all addressed below; **pass 13
(Codex)** found one further P1 — the pass-11/12 generator assigned `BTC-USD` a random integer
quantity (75) despite Main.dc.html's holdings table and Browse.dc.html's own curated row both
showing it held at 0.75, a 100x decimal-fidelity break (requirements.md 8.1) the
catalog-membership and diff-consistency checks from passes 11-12 didn't cover, since `BTC-USD` is a
real, currently-held ticker with an existing canonical quantity, not an unsupported or removed one;
self-audit for the same defect class also caught `MSFT`, `ETH-USD`, and `TCS.NS` appearing as held
rows despite Browse.dc.html showing all three unchecked — addressed below; **pass 14 (Codex)** found
two further P1 + one P2 — this document's manual-reset authorization boundary was unimplementable
as specified: it placed `PUT /api/portfolio/demo-reset` in portfolio-service, calling
`DemoResetService` directly, while requiring that handler to verify the caller's JWT subject;
portfolio-service has no JWT decoder or principal (only api-gateway's `X-User-Id` injection), and on
AWS the portfolio Lambda's Function URL is public with `authorization_type = "NONE"`, so the check
was not implementable there and a header-trust substitute would not have been equivalent — moved the
handler to api-gateway (D5 below), which already performs JWT verification for every authenticated
route, calling the internal reset endpoint over HTTP instead of in-process; the mockup's round-11 row
generator had also deleted the `EURUSD=X` row's closing `</div>` while splicing in its 135 rows (595
open vs 594 close, invisible to JSON/ticker-text checks — restored, now verified against a real DOM
parse: 148 direct children, 2 paragraphs + 146 rows); and Browse.dc.html's own comment overclaimed
~140 rows "further down the same list, off-screen" when only 7 actually render there — corrected to
say explicitly that Browse, unlike Conflict, is intentionally abbreviated; **pass 15 (Codex)** found
2 further P1 + 1 P2, all correcting pass 14's own fix in this document: pass 14's local api-gateway
controller for the manual `PUT /api/portfolio/demo-reset` trigger silently bypassed
`JwtAuthenticationFilter`, `ReadOnlyEnforcementFilter`, and `CloudFrontOriginVerifyFilter` — all
three are Spring Cloud Gateway `GlobalFilter`s that, by `JwtAuthenticationFilter`'s own javadoc, run
"before routing" and therefore never execute for a request api-gateway's own local controller
handles directly, only for requests Gateway proxies onward — and shadowed the existing
`/api/portfolio/**` route's rate limiter; reverted the handler to portfolio-service (matching its
pre-pass-14 shape) and moved the JWT-subject check to a new Gateway `GlobalFilter` instead, which
runs for the genuinely-routed request and keeps all four protections intact; separately, pass 14's
own broadening of `X-Origin-Verify` to the (now-reverted) manual-trigger self-call would have
forwarded that secret into portfolio-service unchecked, since `CloudFrontOriginVerifyFilter` bypasses
`/api/internal/**` without stripping it — reverted to login-eligibility-read-only scope; and pass
14's manual controller had introduced a third HTTP leg needing its own timeout contract, which the
revert above removes entirely, since the corrected design has no self-call for the manual trigger at
all; **pass 16 (Codex)** found 1 further P1 + 2 P2, all correcting pass 15's own fix in this document:
pass 15's reverted handler in portfolio-service at `/api/portfolio/demo-reset` had **no protection at
all** against a direct call to the public AWS Function URL — `InternalApiKeyFilter` only ever gates
`/api/internal/**`, and pass 15's comparison to "the same exposure every other portfolio-service
endpoint already has" didn't hold, since every other endpoint at least requires *some* `X-User-Id`
value while this one required no identity header whatsoever; removed that handler entirely and routed
the manual trigger through the existing internal-key-protected endpoint instead, via a Gateway route
(`RewritePath`) plus `DemoResetAuthorizationFilter` attaching the internal key server-side on a
JWT-subject match. Also pinned `DemoResetAuthorizationFilter`'s order unambiguously at
`HIGHEST_PRECEDENCE + 4`, and pinned one exact 403 envelope body everywhere it's referenced, matching
`ReadOnlyEnforcementFilter`'s own two-field shape exactly rather than merely resembling it; **pass 17
(Codex)** found 2 further P1 + 1 P2, all in pass 16's own transport mechanism, verified against the
resolved Spring Cloud Gateway 5.0.2 artifact and `JwtAuthenticationFilter.java`: `RewritePath`
changes only the request path, never the HTTP method — the manual trigger's rewritten `PUT` was
reaching the internal endpoint's `POST`-only mapping and would 405, fixed by mapping that endpoint to
accept both verbs, since no method-mutation filter factory exists to invent a YAML fix from;
`JwtAuthenticationFilter` evaluates the pre-rewrite path, which never matches its `/api/internal/**`
bypass, so a real `Authorization` header and a real `X-User-Id` were silently reaching an endpoint
whose documented trust model assumed neither ever arrived — fixed by having
`DemoResetAuthorizationFilter` strip both and replace, never append to, any caller-supplied
`X-Internal-Api-Key`; and route precedence rested on YAML list order rather than Gateway's actual
`Route.order` sort (defaulting to `0` for every route today) — fixed with an explicit `order` plus a
defensive check that the matched route id is genuinely `demo-reset-manual` before the filter attaches
the internal key at all; **pass 18 (Codex)** found 0 P1 + 3 P2 — the transport architecture itself
confirmed correct, only precision gaps left: the routed integration test description named only
path+key, not the other invariants pass 17 introduced — spelled out completely now, in one place, in
this section; this document's requirements.md counterpart claimed portfolio-service's controllers
"never" receive the raw JWT, which is false — `JwtAuthenticationFilter` never strips `Authorization`
on any route — corrected there to the real reason (no resource-server integration to parse it); and
this section mis-assigned `503` to a blank-supplied-key scenario that `InternalApiKeyFilter` actually
answers with `403`, `503` being reserved for portfolio-service's own secret being unconfigured; **pass
19 (Codex)** found 1 further P1 + 2 P2 — this section's own "D5 and D6 SHALL land in the same change"
note was read as also covering portfolio-service's mapping, but api-gateway and portfolio-service
deploy as separate, non-atomic artifacts on both cloud targets (verified against both production
workflows) — added an explicit cross-service rollout sequence distinct from the (still-correct)
within-api-gateway D5/D6 coupling; split the pass-18 "single integration test" into two, since a
stubbed portfolio-service can prove transport but not real call-site identity; and added the
gateway-side fail-closed `503` for a null/blank `INTERNAL_API_KEY` read, which previously left that
case implementation-defined and risked a misleading downstream `403`; **pass 20 (Codex)** found 1
further P1 + 2 P2, all in this document's own round-19 rollout note: it described stage 1 as
"widening" a `POST`-only mapping that never existed — neither `DemoResetService` nor the internal
demo-reset mapping exist in source, a fact stated correctly everywhere else in this section since
pass 5 — corrected to describe stage 1 as the full portfolio-service-side build, including its B1
`HoldingReplacementService` prerequisite; the fail-closed test specified stubbing `System.getenv`,
which ordinary Mockito can't do — fixed with a constructor-injection test seam; and this note's own
"not a new acceptance criterion" contradicted requirements.md's `SHALL` for the same sequencing —
resolved by making the master plan the authoritative release gate, this section deferring to it.
**pass 21 (Codex)** found 1 further P1: this document's own stage-4 rollout bundle grouped the
manual-reset gateway pieces with the login-orchestrated self-call as one deployable unit, which
either blocks the manual path — needing only `version`, already unaffected by the `updated_at`
gap — on unrelated, still-open work (`updated_at` ownership, the idle-reset threshold, the self-call
timeouts), or contradicts this section's own release-gate framing; split into a manual-reset gateway
bundle (stage 4, shippable once stage 1-3 land) and a separately-gated, later login-orchestration
deployment (stage 6, gated on the three open items above), with the frontend control (stage 5)
completing the manual path independent of stage 6.
**pass 22 (Codex, raised via a `tasks.md` review round rather than a review of this document
directly)** raised a P0 concern that `intent: []` in `DemoResetService.reset`'s call to `replace`
empties the demo portfolio instead of restoring it, citing `DemoPortfolioInitializer.java`'s current
`desiredHoldings()`-based mechanism as evidence a real desired list must be constructed. Verified
directly against B1's own `design.md` D3: `GoldenStateTuplePreparer` "supplies its own full tuple"
and "ignores current state" — deliberately contrasted there against `CompositionTuplePreparer`,
which does expand ticker/quantity from the supplied intent — so `intent: []` is correct for this
preparer, not a defect; `DemoPortfolioInitializer`'s current code predates B1's own planned
replacement and uses a different mechanism, already flagged as such six lines below this note.
Resolved by clarifying the call site in place (added above) rather than changing it, so a future
reader verifying against source doesn't reach the same reasonable-looking wrong conclusion.
**pass 23 (Codex, `tasks.md` review round)** found 4 further P1 + 1 P2, none touching this document
directly — a task-breakdown-level dependency mismatch (Wave 9 wrongly re-coupled to Wave 8's
login-orchestration in `tasks.md`'s own Overview and the master plan's Live Integration row), a test
rigor gap (an MVC-slice test could fabricate proof of Golden-State materialisation without exercising
the real chain), an E2E identity/fixture gap, and cleanup semantics relying on an endpoint that
cannot target the demo user — all fixed in `tasks.md` and the master plan; this document's own D5
text was not implicated.
Companion to
`requirements.md` Revision 2. A visual mockup of the five core screens (Portfolio entry point,
Browse/draft, Review/confirm, Success, and the 409 conflict state) exists two ways:

- **In the working tree** (not yet committed — see note below), openable in any browser with no
  dependency on artifact-sharing permissions or a live session, offline-openable with a
  system-font fallback: `.kiro/specs/asset-picker-composition/mockup/asset-picker-design.html`.
  **Not fully self-contained** — each screen links Geist from `fonts.googleapis.com` for visual
  fidelity with the real app's font stack; without network access it falls back to `system-ui`
  rather than failing to render. *(Pass 4 correction: an earlier draft called this file
  "self-contained" without qualification.)*
  Pass 2 found the previous revision linked only the hosted canvas below, which is **private by
  default** — a reviewer without access to it saw "Page not found," making the design
  unreviewable from a fresh checkout alone despite the link being present. This file is
  the fix within this session: durable and independently openable **once committed**. *(Pass 5
  correction: calling this "checked in" overstated its status — `git log --all` shows zero commits
  touching `.kiro/specs/asset-picker-composition/` on any branch; it is present in this working
  tree only, same as every other artifact from this review cycle, pending the standing
  commit-after-clean-review step.)*
- **Hosted, editable**: https://claude.ai/code/artifact/cd67255f-f482-4e3d-84c8-2ad41b2779cb — same
  content, pan/zoom canvas, not guaranteed accessible to every reviewer.

The repository currently has **no existing Dialog/Modal primitive** in `frontend/src/components/ui`
— D1's `AssetPickerModal` is new UI, not a reskin of something that already exists, and should be
built (or a Radix-based one added) rather than assumed present.

## D1 — Component boundaries

```
PortfolioPageContent (existing)
├── EditHoldingsButton (new) — opens the picker, passes current portfolio + version as initial state
├── FreshnessDetailsPopover (new, added pass 9) — anchored to the existing "Details" control,
│   present on both the pre-save and post-save Portfolio page per Requirement 3.4. Full interaction
│   contract (content, keyboard/focus, absent-timestamp case) is normative in requirements.md 3a,
│   not restated here — this is a small, self-contained popover, not new architectural surface.
└── AssetPickerModal (new, client component)
    ├── PresenceBanner (new) — queried once on mount, renders nothing on error/absence
    ├── BrowseStep
    │   ├── AssetSearchBar
    │   ├── AssetList — GET /api/assets, filtered client-side by search + active-only
    │   └── DraftRow (repeated) — one per drafted holding, including any RetainedDeprecatedRow variant
    ├── ReviewStep — pure derivation: diff(initialHoldings, draftHoldings) → added/changed/removed/unchanged
    └── ConflictPanel — rendered ALONGSIDE a read-only summary of the draft (not in place of
        it — pass 2 corrected this: "in place of the modal body" left it genuinely ambiguous
        whether the draft was still visible to the user or only alive in memory, and
        requirements.md 4.3 requires it visibly readable, not just retained as state), with
        all draft rows rendered as **non-interactive display elements, not disabled form
        controls** — no `role="checkbox"`/`tabindex`/`aria-disabled` on the individual rows or
        their controls, since there is nothing to operate in this state, rather than form
        controls marked disabled. *(Pass 6 audit note: an earlier draft said "controls
        disabled," which reads as literal `disabled`/`aria-disabled` attributes and could
        mislead an implementer into that pattern — the mockup's actual, cleaner approach
        removes interactive semantics entirely instead. Pass 11 narrowing: this "no tabindex"
        rule is about the rows, not the list itself — Codex round 11 read it as also forbidding
        the scroll region's own `tabindex="0"`, which requirements.md 4.3 requires normatively
        so the region is keyboard-reachable; the two aren't in tension, but the wording here
        needed to say so.)* Two exits, both a knowing discard: "reload and start
        over" (requirements.md 4.4), or closing the modal (requirements.md 1.5) — neither
        happens automatically on the 409 itself.
```

State lives in the modal, not in a global store: `draft: Map<ticker, { quantity: string, meta }>`,
seeded from the portfolio read that opened the picker. **The seed is the user's complete current
holding set — every held ticker starts present and checked in `draft`, not just an empty map the
user builds up from scratch** — because B1's `PUT /api/portfolio/holdings` is a full replace, not a
diff (B1 Requirement 6: "a complete desired holding set"; omission of a held ticker means deletion).
`ReviewStep`'s own `diff(initialHoldings, draftHoldings)` only makes sense against that premise: an
"unchanged" count assumes the unchanged tickers were present in `draft` all along, not absent from
it. *(Pass 6 addition: this was implicit but never stated as a SHALL, and the mockup's Browse
screen visibly assumed the opposite — a small, mostly-empty draft — until this pass.)* No draft
persistence across a closed modal — closing discards it (Requirement 1.5), including from the
conflicted state above.

**Accessibility contract (added on pass 4 — none existed before).** Since no existing Dialog
primitive is being reused (this section's opening line), `AssetPickerModal` SHALL implement the
WAI-ARIA APG Dialog (Modal) pattern, not merely visual chrome overlaying the page:
- The modal root SHALL carry `role="dialog"` and `aria-modal="true"`, labelled via
  `aria-labelledby` pointing at its visible heading (`"Edit Holdings"` / `"Review changes"`), not a
  redundant `aria-label` duplicating visible text.
- Focus SHALL move into the modal on open and SHALL be trapped within it — Tab/Shift+Tab cycles
  only through the modal's own focusable elements, never escaping to the dimmed page behind it.
- Focus SHALL return to `EditHoldingsButton` on close, by whichever path closes it — the close
  button, `Escape`, or a successful save.
- `Escape` SHALL close the modal with the same discard semantics as the close button (Requirement
  1.5), including from the conflicted state above.
- Every interactive element (search, quantity inputs, step controls, close button, both
  `ConflictPanel` actions) SHALL be reachable and operable by keyboard alone.
This is normative, not mockup-only: the mockup's five screens carry `role`/`aria-*` as a structural
reference, but focus trapping, restoration, and keyboard operability are runtime behavior no static
markup can demonstrate — implementation SHALL be verified by an automated a11y check (e.g. axe, or
a Testing-Library focus-order assertion), not by visual review alone. *(Pass 6 correction: "the
checked-in `.dc.html` files" was wrong on two counts — the five screens are artboards embedded as
inline fragments inside one seeded HTML artifact, `mockup/asset-picker-design.html`, not standalone
`.dc.html` files in their own right; and per the header above, that artifact is not actually
checked into git yet at all, contradicting "checked-in" outright.)*

**Pass 5 found the fix above stopped at the modal shell** — the mockup's dialogs gained correct
`role="dialog"`/`aria-modal`/labelled headings, but `BrowseStep`'s own controls remained a plain
unlabelled `<div>` per checkbox and unlabelled quantity `<input>`s, exposing no semantics to
assistive technology regardless of how the surrounding dialog is marked up. `AssetSearchBar` and
`DraftRow` SHALL therefore additionally implement: native `<input type="checkbox">` or
`role="checkbox"` + live `aria-checked` + `aria-label` naming the asset for each selection control;
`aria-label` on each quantity `<input>` tying it to its asset. A `RetainedDeprecatedRow`'s
**checkbox stays fully operable** (checked, focusable, no `aria-disabled`) since deselecting it is
how it's removed (D1 above); the reduce-only constraint applies to its **quantity input**, surfaced
via `aria-describedby`-linked text plus `max` as a supplementary hint, not primary enforcement —
`max` is inert on a text-mode `inputmode="decimal"` control (Requirement 8: quantity is a string,
never a parsed number), so the real check is client-side validation before submission. *(Pass 6
correction: an earlier draft put `aria-disabled` on the checkbox itself — contradicting removal
staying available — and treated `max` as sufficient on its own.)* The same `aria-describedby`
pattern covers any client-side validation rejection generally, not color alone. The mockup's
`Browse.dc.html` screen now carries all of this as its own structural reference, mirroring the
modal-shell fix's pattern.

**Also new UI, also in scope:** the step indicator SHALL expose its current step via
`aria-current="step"`; the draft-count summary and the post-save confirmation (D1's `PresenceBanner`
sibling, requirements.md Requirement 3) SHALL be live regions (`aria-live="polite"` /
`role="status"`) since both change without a page navigation. **Out of scope:** the existing
Portfolio page's sidebar navigation and holdings table — pass 5's mockup audit found real
accessibility gaps there too (unlabelled nav items, missing `<th scope="col">`), but B2 neither
owns nor modifies those components; the mockup was fixed as reference hygiene, not because this
design requires B2 to redesign them.

## D2 — Wire contracts consumed (owned by B1, referenced here for B2's implementation)

`GET /api/assets`:
```json
{
  "catalogVersion": "<hash>",
  "assets": [
    { "ticker": "TATAMOTORS.NS", "name": "Tata Motors", "aliases": ["Tata Motors", "TATAMOTORS"],
      "assetClass": "NSE", "quoteCurrency": "INR", "lifecycleStatus": "DEPRECATED" }
  ]
}
```
`ETag` on `catalogVersion`; client conditionally revalidates, no second persistent cache
(Requirement 2.1, matching B1 Requirement 2.9-2.12).

`PUT /api/portfolio/holdings`:
```json
{ "expectedVersion": 7, "holdings": [ { "ticker": "AAPL", "quantity": "10" }, /* ...every other
  currently-desired holding, not just this one... */ ] }
```
**This `holdings` array is truncated for brevity — it is not an example of what a real request
looks like on its own.** Per D1's seeding note above and B1 Requirement 6.1 ("a complete desired
holding set"), it SHALL contain **every** ticker the user wants held after the save, not only the
ones that changed. *(Added pass 7: this is the single most consequential place for "draft = diff"
confusion to recur — a reader skimming only D2 could plausibly take a one-item array as a valid
partial/diff payload, exactly the misconception pass 6 corrected elsewhere.)*
**Corrected field name** — Revision 1's first draft used `"version"`. B1's actual contract requires
`expectedVersion`; a missing or misnamed field produces B1's `400 missing_version` before the
request ever reaches composition logic (B1 `design.md`, the boxed-`Long`/`@NotNull` discussion; B1
`requirements.md` 7.12-7.13). Response: full `PortfolioResponse` including the new `version`. `409`
body carries `{ "error": "portfolio_version_conflict", "message": "...", "currentVersion": 8 }`
(B1's envelope, not redefined here — B1 `design.md` D7 confirms all three fields; the earlier
draft's "exact envelope" example dropped `message`, fixed here).

Prices for the draft's tickers only: existing `/api/market/prices?tickers=A,B,C`.

## D3 — Decimal handling (Requirement 8)

**Corrected from Revision 1's first draft**, which claimed this was a gap in B1's scope. It is not:
B1 already specifies decimal-string quantities both directions (B1 Requirement 4.1-4.7 — 4.1 the
write-direction mandate, 4.2 the read-direction one; pass 7 correction: previously cited as
"4.2-4.7," omitting 4.1) and already
designs the fix (B1 `design.md` D6, a `ToPlainStringSerializer` on `HoldingResponse.quantity`).
**Ownership is settled; implementation is not** — verified directly against current source, not
assumed from the task list alone: B1 task 4.9 is unchecked, and `PortfolioResponse.HoldingResponse`
still declares `BigDecimal quantity` today (`portfolio-service/.../PortfolioResponse.java:33-36`),
not the decimal-string wire type B1's design commits to. *(Pass 5 correction: an earlier draft said
B1 "already implements it," which is a rollout-status claim this codebase does not yet support —
distinct from the "B1 owns this, B2 doesn't need to ask" ownership claim, which does hold.)* There
is nothing for B2 to ask B1 to *design* here; there is real B1 implementation work still pending
before B2 can build against it live.

**The real, still-open item is a frontend migration B1 has no obligation to perform**, since B1
Requirement 10.1 forbids B1 from touching the frontend at all: `frontend/src/lib/api/portfolio.ts`
declares `interface BackendHolding { quantity: number }` today. B2 owns migrating that interface
(and every type/consumer derived from it) to `string`, and owns sequencing so B1's string-quantity
read contract does not go live in production ahead of that migration — otherwise the *existing*
Portfolio page (unrelated to the picker) silently breaks the moment `HoldingResponse.quantity`
stops being a JSON number. B2's own `DraftRow` state stores quantity as the string the input holds;
a derived, memoized numeric value is computed only for the estimated-value display, never fed back
into the draft or the submit payload.

## D4 — Presence

**The picker-facing contract, restored** — pass 1 fixed the Redis storage shape but pass 2 found
the actual callable surface had dropped out in the process; requirements.md 6.3 says "query
presence once" but named no endpoint. The browser never touches Redis directly:

```
GET /api/presence/demo   (gateway-owned, authenticated — same JWT as every other /api/* route)
  → 200 { "anotherSessionActive": boolean }
```

Authorization: **open to any authenticated caller, not identity-restricted** — unlike the
demo-reset endpoints in D5, this route performs no JWT-subject check and never returns `403`. It
discloses no sensitive data (only a boolean), so restricting it would add complexity without a
security benefit. It is, however, functionally meaningless outside the demo flow: the handler
SHOULD short-circuit to `anotherSessionActive: false` for a non-demo caller's JWT subject rather
than doing real Redis work for them — a cheap-path optimization, not an authorization boundary.
Self-exclusion (the caller's own session must not count itself as "another" session) and fail-open
behavior (Requirement 6.5 — a Redis error yields `false`, never a failed request) are the handler's
responsibility, backed by the storage shape below.

```
JWT claims gain: "jti": "<random>"
Gateway: sessionKey = sha256(jti)
```

**Corrected storage shape** — Revision 1's first draft wrote independent `SETEX presence:<key> 150`
entries and assumed a live-session count could be read back, without saying how. Independent keys
need a `SCAN`/`KEYS` sweep (expensive, and `KEYS` is a known production footgun) or a separate
index; neither was specified. Concrete shape:

```
On authenticated demo traffic:
  ZADD presence:demo <now_epoch_seconds> <sessionKey>   (sorted set, score = last-seen time)
  EXPIRE presence:demo <TTL + slack>                     (whole-set safety net if traffic stops entirely)

`GET /api/presence/demo` handler:
  ZREMRANGEBYSCORE presence:demo -inf (<now - TTL>       (evict stale members first)
  count = ZCARD presence:demo
  anotherSessionActive = (count - <1 if this caller's own sessionKey is a member, else 0>) > 0
```

One sorted set per demo user (there is exactly one demo account, so `presence:demo` is sufficient;
a multi-tenant future would key it `presence:demo:<userId>`), score = last-seen epoch seconds,
membership implicitly expires by the read-time `ZREMRANGEBYSCORE` sweep rather than per-key TTLs
(simpler than tracking N independent key TTLs, and self-cleaning on every read regardless of
traffic pattern). **TTL value is OPEN** (requirements.md 6.2) — entry [5]'s 150 seconds was
proposed as provisional, not committed.

No websocket, no polling loop, no lock. A Redis error on either path is swallowed; the picker
proceeds with `active: false` rather than surfacing an error banner for an unrelated failure.

## D5 — Demo reset

**Corrected twice.** Pass 1 fixed the missing version parameter but still assumed a
`B1.identityPreservingReset(...)` call B2 could simply invoke. Pass 2 found that call has no
referent: B1's actual seed endpoint (`PortfolioSeedController`) is hard-coded to
`E2E_USER_ID` — the compiled-in E2E test user, not the demo account — accepts no caller-supplied
target (B1 `design.md` D8: *"the target remains server-fixed... takes no parameters... the body
carries `expectedVersion` only"*), and sits behind `X-Internal-Api-Key`, which cannot be handed to
a browser. **B2 cannot call this endpoint for the demo user; it is fixed to a different user by
design, on purpose (D8's whole point is refusing to widen a destructive, daily-invoked endpoint to
arbitrary targets).**

What B1 actually delivers reusably is the **service-layer primitive underneath** that endpoint —
`CompositionResult replace(String userId, long expectedVersion, List<RawIntent> intent,
TuplePreparer preparer)` (B1 `design.md`, the exact current signature — **`userId: String`, not a
portfolio id**; an earlier revision of this design passed `demoPortfolioId`, which doesn't match
what the primitive actually accepts) plus a target-state preparer (the same pattern B1's own
`GoldenStateTuplePreparer` uses for the E2E seed, per B1 `design.md` D8) — not an HTTP contract.
**B2 must define its own demo-scoped HTTP boundary** that calls that same internal primitive with
its own fixed target and its own auth, in parallel with B1's seed controller rather than through
it.

**Third correction — the observation source, and why the manual trigger was still broken.** B1
permanently prohibits a dedicated version-read endpoint (`requirements.md` 5.11: *"THE system
SHALL NOT expose the Portfolio_Version through a separate endpoint, because a read-then-read
sequence reintroduces the race the version exists to close"*; B1 `design.md`: *"A separate version
endpoint remains prohibited in all three"* — referring to B1's three existing seed call sites
(`synthetic-monitoring.yml`, `global-setup.ts`, `api-live-smoke.spec.ts`), not to "all revisions" of
any document. *(Pass 5 cross-audit correction: an earlier draft's bracketed `[revisions]` silently
replaced B1's actual referent — "three" — with a fabricated one, changing what the quote asserts.
The underlying point — a separate version endpoint stays prohibited, full stop — still holds; only
the misquote is fixed.)*). An earlier revision of this design had the manual-trigger endpoint take **no
body** and re-read the current version **inside** the reset call — that is exactly what B1
Requirement 8.33 forbids (*"THE reset SHALL NOT obtain its expected version by reading current
state inside the reset operation"*), for precisely the reason 8.34 gives: it would let the reset
win a race it never had a precondition for. The fix is not a new version endpoint — it's the
**existing** one, once B1 rolls the field onto it. **Precisely, not "already":** B1 `design.md`
commits to `version` on `PortfolioResponse` (Requirement 5.10) and to exposing it on
`GET /api/portfolio` (Wave 5 task 5.1), but neither is implemented yet — B1 task 4.10 (add
`version` to `PortfolioResponse`) and task 5.1 are both unchecked, and the current
`PortfolioResponse` record carries no version field at all (verified directly against
`PortfolioResponse.java`). *(Pass 5 correction: an earlier draft said this endpoint "already
returns `version` today," conflating B1's committed design with its current, unimplemented state —
already correctly gated behind B1 Wave 7 for live integration elsewhere in this program, but stated
imprecisely here.)* Once implemented, that single read is the eligibility observation for the
manual trigger below — never a second, reset-time re-read.

**`updated_at` is a separate, unresolved dependency — caught on pass 4, not assumed away.**
Verified against B1's actual tasks, not its schema intent: B1 Wave 3/V20 adds the `updated_at`
**column** to the `portfolios` table (`portfolio-composition-contract/requirements.md` 5.14-5.15),
but no B1 task puts it on the wire — Wave 5 task 5.1 exposes only `version` on
`GET /api/portfolio` (`portfolio-composition-contract/tasks.md:688`). Nothing in B1's current
scope exposes `updatedAt` on `PortfolioResponse`. **This is an unassigned gap, not a Wave-3
side-effect:** either B1 gains a task exposing `updatedAt` there (the natural sibling of Wave 5's
5.1 — same row, same response), or B2 is explicitly told to own that read-contract addition itself.
Until one of those happens, **the login-orchestrated idle-reset trigger (requirements.md 7.4),
which needs `updated_at` to decide eligibility, is not implementable** — a narrower and later gate
than "blocked on Wave 3" alone. The manual trigger below needs only `version` — specified but, per
the correction above, not yet implemented — and is unaffected by the `updated_at` gap specifically.
*(Pass 5 cross-audit correction: this sentence still said "already exposed," reintroducing the
exact framing corrected 20 lines above.)*

```
New, B2-owned. `POST /api/internal/portfolio/demo-reset` lives in portfolio-service, unchanged since
pass 15, mirroring `PortfolioSeedController`'s shape. The manual trigger's
`PUT /api/portfolio/demo-reset` is **not** a second portfolio-service handler (pass 16 correction of
pass 15 — see the authorization note below: verified directly against `InternalApiKeyFilter.java`,
it gates only `/api/internal/**` and passes every other path through untouched, so a
portfolio-service handler at `/api/portfolio/demo-reset` — pass 15's design — had *zero* protection
against a direct call to the public AWS Function URL: no identity header required at all, worse than
every other portfolio-service endpoint, which at least requires a caller to supply *some*
`X-User-Id`). Instead `PUT /api/portfolio/demo-reset` is a dedicated api-gateway **route**, not a
handler anywhere in application code, that rewrites onto the existing internal endpoint once the
JWT-subject check passes — the manual trigger and the login-orchestrated trigger both end up calling
the exact same portfolio-service handler through the exact same `X-Internal-Api-Key` boundary, just
by two different transports (a Gateway route rewrite here; a `WebClient` self-call for the
login-orchestrated trigger, described further below). `DemoResetService.reset` itself is called from
**exactly one place** — the internal endpoint — never duplicated:

  DemoResetService.reset(long expectedVersion)                              (portfolio-service)
    → replace(DEMO_USER_ID, expectedVersion, intent: [],
        preparer: new GoldenStateTuplePreparer(app.demo.cost-basis-anchor))
    → **`intent: []` is correct, not a placeholder that silently empties the portfolio — made
      explicit here (pass 22 addition) after a tasks.md review round raised this as a P0 concern,
      citing `DemoPortfolioInitializer.java`'s current `desiredHoldings(DEMO_USER_ID)` call as
      evidence that a real desired-holdings list must be constructed and passed as `intent`.** That
      citation is accurate about *today's* source but not about the target mechanism this call site
      is written against: `DemoPortfolioInitializer` currently uses the pre-B1 `PortfolioSeedService`
      path (no `TuplePreparer` involved at all, per the correction six lines below), which is exactly
      why it needs a separately-constructed desired list. B1's own `design.md` D3 states the
      `HoldingReplacementService`/`TuplePreparer` contract differently and explicitly for the
      golden-state case: `GoldenStateTuplePreparer` "**supplies its own full tuple**" and "**ignores
      current state**" — contrasted deliberately, in the same sentence, against
      `CompositionTuplePreparer`, which *does* expand ticker/quantity **from the supplied intent**.
      B1 `design.md`'s own description of `GoldenStateSeedService` (its future replacement for
      today's `DemoPortfolioInitializer`) confirms the same split: it "builds the desired set from
      the Catalog_Module's active entries... **inside a `GoldenStateTuplePreparer`**" — the
      catalog-derivation logic lives inside the preparer's own `materialise()` implementation, not in
      a list the caller assembles beforehand. **Precisely, not "regardless of `intent`" (tightened
      per review): `intent` still passes through D2's semantic/catalog validation steps (3-4)
      unconditionally** — an arbitrary or malformed intent would still fail there, before
      materialisation ever runs; this call site's deliberately **empty** list is simply vacuously
      valid at that stage. Given this specific, valid, empty raw-intent list, step 5's
      materialisation — not steps 3-4's validation — is what actually produces the golden-state
      holdings, and `GoldenStateTuplePreparer` derives that full tuple internally rather than reading
      it from `intent`. This entry exists specifically so a future reader verifying "directly against
      source" doesn't
      make the same, reasonable-looking mistake of treating the current, pre-B1 demo initializer as
      the target architecture this call site is already written against.
    → target is server-fixed to DEMO_USER_ID (a compiled-in constant, mirroring B1's E2E_USER_ID —
      not a caller-supplied id of any kind), matching B1 D8's own reasoning for refusing one
    → the preparer is B1's exact `GoldenStateTuplePreparer` (not a distinct "GoldenStatePreparer" —
      pass 5 correction: an earlier draft named a type that doesn't exist), constructed with the
      **fixed** `app.demo.cost-basis-anchor` instant as its caller-supplied anchor — never
      `Instant.now().minus(25h)`, which is the *scheduled E2E seed's* anchor, not the demo path's.
      B1 `design.md` (~line 1052) is explicit that the anchor is caller-supplied precisely so the
      demo path can pin it; passing the wrong one would silently reintroduce the moving-timestamp
      defect Spec A already fixed once. Same class, same construction pattern **B1's own design**
      specifies for its demo initializer once Wave 4/6 replaces the current implementation —
      DemoResetService does not invent a new one. *(Pass 5 cross-audit correction: "already uses
      elsewhere" overclaimed present-tense fact. Verified directly: `GoldenStateTuplePreparer`,
      `HoldingReplacementService`, `TuplePreparer`, `CompositionResult`, and `RawIntent` exist
      nowhere in `portfolio-service` source today — zero matches. The current demo initializer
      (`DemoPortfolioInitializer.convergeInTransaction()`) calls `PortfolioSeedService.seed(...)`,
      which still opens with `deleteAll`+`flush` (B1 tasks 6.1/6.2, unchecked) and reads
      `demoProperties.costBasisAnchor()` inline, with no `TuplePreparer` abstraction anywhere in the
      running code. B2 is aligning with B1's *planned* architecture, not an existing one — same
      ownership-vs-implementation-status distinction this document applies everywhere else.)*

  POST or PUT /api/internal/portfolio/demo-reset   (portfolio-service; internal-key-protected via
    { "expectedVersion": <long> }                    InternalApiKeyFilter — HIGHEST_PRECEDENCE,
                                                       gates every /api/internal/** request and
                                                       passes every other path through untouched,
                                                       verified directly — same trust boundary B1's
                                                       seed endpoint uses, never reachable from the
                                                       browser without this header. Mapped to BOTH
                                                       verbs — pass 17 correction: `RewritePath`
                                                       changes only the request path, never the
                                                       method (verified against the resolved Spring
                                                       Cloud Gateway 5.0.2 artifact:
                                                       `RewritePathGatewayFilterFactory` mutates the
                                                       path exchange attribute only, and no
                                                       method-mutation filter factory exists in that
                                                       artifact to invent a YAML fix from) — so a
                                                       `PUT` arriving at the public route stays a
                                                       `PUT` all the way to this mapping. The
                                                       login-orchestrated self-call (below) issues
                                                       `POST` by its own free choice; the manual
                                                       trigger's rewritten request arrives as `PUT`.
                                                       One controller method, `@RequestMapping(method
                                                       = {POST, PUT})` or equivalent, not two)
    → calls DemoResetService.reset(expectedVersion) directly — the only call site, whichever verb
      reached it

  PUT /api/portfolio/demo-reset                     (api-gateway route, not a portfolio-service
    { "expectedVersion": <long> }                     handler — see the authorization note below;
                                                        reachable from the browser for the manual
                                                        trigger, allowlisted in
                                                        ReadOnlyEnforcementFilter method+path)
    → a dedicated Gateway route (`demo-reset-manual`, given an explicit lower `order` than the
      generic `portfolio-service` route so it matches first — pass 17 correction, below) rewrites
      the request path to /api/internal/portfolio/demo-reset via a standard, declarative
      `RewritePath` GatewayFilter — no secret involved in that step, and (pass 17 correction) this
      changes only the path, never the method: the request that reaches portfolio-service is still
      a `PUT`, which is why the internal mapping above now accepts both verbs
    → DemoResetAuthorizationFilter (below), a GlobalFilter running *before* RewritePath — confirmed
      against the resolved artifact: `FilteringWebHandler` merges global and route filters into one
      chain sorted by order; `RouteDefinitionRouteLocator.loadGatewayFilters` assigns route-level
      filters their order by list index, `1`-based (`RewritePath`, the sole filter on this route, at
      index `0`, gets order `1`), and `DemoResetAuthorizationFilter`'s
      `HIGHEST_PRECEDENCE + 4` is a deeply negative int, well below `1` either way — independently
      verifies the caller's JWT subject and, only on a match: (a) confirms the exchange's matched
      route id is exactly `demo-reset-manual` before doing anything else — pass 17 addition, a
      fail-safe against a future routing regression silently sending this same method+path pair
      somewhere else while the filter still attaches the key to it. **The exact mechanism, named
      precisely (self-audit correction — an earlier draft of this item asserted the check without
      naming how, the one claim in this whole section without a source citation, right where one
      matters most):** read
      `org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR` off the
      exchange (`exchange.getAttribute(GATEWAY_ROUTE_ATTR)`), cast to
      `org.springframework.cloud.gateway.route.Route`, call `.getId()`, compare to the literal
      `"demo-reset-manual"`. **Do NOT use `GATEWAY_PREDICATE_ROUTE_ATTR`** — a similarly-named
      attribute in the same class that holds a route id only transiently during predicate matching;
      `RoutePredicateHandlerMapping` explicitly removes it the moment the real route resolves
      (verified directly against the resolved artifact), so by the time any `GlobalFilter` runs it is
      always `null` — reaching for it here would either 403 every legitimate call (a permanently
      broken feature) or, if the null case were mishandled as "proceed," silently defeat the whole
      check. `GATEWAY_ROUTE_ATTR` (no `_PREDICATE_`) is resolved once, early, and is present and
      populated for the entire filter chain — confirmed by reading `FilteringWebHandler.handle()`,
      which itself requires this exact attribute to be present before running any filter at all.
      Given how easy this is to get wrong from the name alone, this SHALL be covered by a unit test
      asserting the filter rejects when a stubbed exchange carries no `GATEWAY_ROUTE_ATTR` (or the
      wrong route id), in addition to — not instead of — Test 1 (the gateway routed integration test)
      specified later in this section (self-audit fix: this note previously said "above," pointing at
      a test description that in fact comes later in the document); (b) REMOVES the
      caller's own `Authorization` header and any already-injected `X-User-Id` — pass 17 correction:
      because this GlobalFilter runs before RewritePath, and
      because the *original* incoming path is `/api/portfolio/demo-reset` (not `/api/internal/**`),
      `JwtAuthenticationFilter` (which evaluates the pre-rewrite path) does NOT take its
      `/api/internal/**` branch here — it takes its normal branch instead, which preserves
      `Authorization` and injects a real `X-User-Id`. Left alone, both headers would reach
      portfolio-service's internal endpoint, silently violating that endpoint's own documented
      invariant ("no JWT, no `X-User-Id`, by the time a request reaches the internal controller" —
      true only for requests that actually originated under `/api/internal/**`, not for this
      rewritten one) — `DemoResetAuthorizationFilter` restores that invariant explicitly rather than
      leaving it accidentally true; (c) reads the internal key — **through a test seam, not a bare
      `System.getenv` call inline (pass 20 addition): the production `@Component` constructor calls
      `System.getenv("INTERNAL_API_KEY")` once and passes the resolved (possibly null/blank) value
      into a package-visible constructor that takes the raw `String` (or a `Supplier<String>`)
      directly — the same pattern `CloudFrontOriginVerifyFilter` already uses for its own secret**
      (that class resolves `CLOUDFRONT_ORIGIN_SECRET` once in its constructor, not per-request; this
      filter mirrors it, one level more testable). `System.getenv` reads a static, effectively
      immutable JVM environment snapshot that ordinary Mockito cannot stub without extra tooling this
      codebase doesn't otherwise depend on (`mockito-inline` static mocking, PowerMock) — routing the
      read through a constructor argument means a test can simply construct the filter directly with
      `null`, `""`, or a real value, no environment mutation or static mocking required. Whatever
      value construction resolved is what the filter checks per request; **if that
      value is null or blank, fails closed right here — pass 19 addition, a gap the design left open
      through pass 18: returns `503` with body `{ "error": "internal_api_key_not_configured",
      "message": "The demo reset feature is temporarily unavailable." }` and makes NO downstream
      call at all** (the same machine code portfolio-service's own `InternalApiKeyFilter` uses for
      its analogous own-secret-unconfigured case, deliberately reused rather than inventing a second
      one for the same underlying condition at a different layer). Without this branch, a null/blank
      value flowing into the header-mutation step below is implementation-defined — it could throw
      (most header APIs reject a null value outright) or, if guarded naively, forward an empty
      string, which portfolio-service's `InternalApiKeyFilter` would reject as `403
      invalid_internal_api_key` (this document, above) — telling an authenticated, correctly-identified
      demo user they're "forbidden," when the real fault is a gateway-side configuration regression
      that has nothing to do with who they are. THE check-then-fail-closed step SHALL run *before* any
      header mutation or the JWT-subject check's downstream consequences, so a misconfigured secret
      never produces a misleading per-user error; (d) only once (c) has confirmed a real value exists,
      removes any caller-supplied `X-Internal-Api-Key` first, then SETS (never appends to) that
      authoritative value — pass 17 correction: a caller-supplied duplicate of this header must not
      survive alongside the real one, however Spring resolves multiple values for a header lookup.
      This new failure mode SHALL be covered by its own filter-level unit test — constructing
      `DemoResetAuthorizationFilter` directly via the package-visible, value-accepting constructor
      above with `null` and with `""`, asserting `503` and zero downstream calls for each (pass 20
      correction: an earlier draft said "stub `System.getenv`," which ordinary Mockito cannot do
      without extra tooling this codebase doesn't otherwise pull in — the seam above exists precisely
      so this test doesn't need to) — a distinct case from the JWT-subject-mismatch `403`, and
      orthogonal to Tests 1 and 2 below, which both construct the filter with a real value and assume
      the key is actually configured. All of (a)-(d) use the same exchange-mutation technique
      `JwtAuthenticationFilter` and `CloudFrontOriginVerifyFilter` already use for header changes, not
      a new mechanism; a mismatch on (a) or the JWT-subject check short-circuits with the pinned 403
      before the route is ever reached
    → the CLIENT supplies expectedVersion, from the same GET /api/portfolio response the browser
      already holds (the version it last observed) — nothing in this path re-reads "current"
      version. If that version is stale, DemoResetService's underlying compare-and-set rejects the
      call as a 409, exactly like a composition PUT losing a race (Requirement 4) — same UX pattern
      reused, not a new one invented for reset.
    → the rewritten, re-headered request reaches portfolio-service's existing internal endpoint,
      indistinguishable there from the login-orchestrated trigger's own call to it — same 200/409
      response relayed back to the browser by Gateway's normal proxying, same standard Gateway
      httpclient timeout/failure handling as every other route (pass 15's "no new timeout contract
      needed" conclusion still holds, now for the right reason: this is genuinely ordinary Gateway
      routing, not a self-call, since RewritePath operates within the already-matched route rather
      than requiring api-gateway's own code to make a second HTTP call). *(Pass 4/14/15/16/17
      history: pass 4 said "not an HTTP contract, a shared method"; pass 14 moved the handler to
      api-gateway as a local controller (wrong — loses Gateway's filter-chain protections); pass 15
      reverted the handler to portfolio-service but added it as a *second* controller there with
      zero protection against the public AWS Function URL (also wrong — `InternalApiKeyFilter` never
      gates it); pass 16 removes that second handler entirely and reaches the *original, only*
      handler — the internal endpoint — via a Gateway-level route rewrite instead, so
      `DemoResetService` is called from exactly one place under every trigger; pass 17 found two
      transport-level bugs in pass 16's own mechanism — `RewritePath` never changes the request
      method, so the rewritten `PUT` was reaching a `POST`-only mapping and would 405, and
      `JwtAuthenticationFilter` evaluates the pre-rewrite path, so the rewritten request was silently
      carrying a real `Authorization` header and a real `X-User-Id` into an endpoint whose entire
      trust model assumes neither ever arrives — both fixed above, without reopening pass 16's core
      decision that this is a Gateway route, not a self-call or a portfolio-service handler.)*
```

**The login-orchestrated eligibility read — transport, list shape, and timeouts, all previously
unspecified (pass 5), with two further gaps closed and one softened on pass 7.** The idle-reset
trigger runs *inside* api-gateway's `/api/auth/login` handler, at the point the demo user's
credentials have just been verified and a JWT has just been minted for the response — but that JWT
has not yet reached the browser, and nothing has called back into the gateway with it yet. **None
of this orchestration exists in source today, and this paragraph describes a proposed mechanism,
not an integration being corrected** — verified directly: `AuthController.login()` (the real,
already-existing `/api/auth/login` handler) does credential verification and JWT signing only, and
a repo-wide search for `WebClient`/`RestTemplate`/`HttpClient` across `api-gateway/src/main/java`
returns zero matches — there is no HTTP client of any kind in that process today, let alone this
specific self-call. *(Pass 7 correction: earlier drafts described this in present-tense,
verified-against-source integration language — "the login handler makes an outbound HTTP call...
verified directly against `CloudFrontOriginVerifyFilter.java`" — which is accurate about the
*filter's* behavior but could read as describing an existing call site. There is no call site yet;
only the target filter behavior it will need to satisfy is verified.)* Two transports were
possible; this design picks one explicitly:

- **Chosen: a gateway self-call, pinned to the loopback target — not the public CloudFront URL
  (pass 6 correction: the deployable target was previously left unstated).** The login handler
  SHALL make an outbound HTTP call to `http://localhost:${server.port}/api/portfolio` (this
  gateway's own process — `server.port` is `8080` in every profile, verified across all five
  `application*.yml` files; on AWS this is the same Lambda invocation via the AWS Lambda Web
  Adapter's `AWS_LWA_PORT=8080`, on Azure Container Apps the same pod — "same process" holds on
  both targets, "same pod" pass 6's wording only fit one of them), attaching the freshly-minted JWT
  as that call's `Authorization` header. This is deliberately **not** a call to the public,
  CloudFront-fronted URL: `CloudFrontOriginVerifyFilter.java` rejects any request lacking the
  correct `X-Origin-Verify` header with `403` (lines 66-72), and its own javadoc states it is
  intended to run ahead of JWT authentication. **Pass 7 qualifier:** that ordering claim — echoed
  in an earlier draft of this paragraph as settled fact — describes the filters' own stated intent
  (`CloudFrontOriginVerifyFilter`'s and `JwtAuthenticationFilter`'s javadocs both assert it), but
  has not been independently confirmed here against Spring WebFlux/Gateway's actual filter-chain
  wiring (`GlobalFilter` ordering governs sequencing among Gateway's own filters; whether that
  chain truly runs before or after Spring Security's outer `WebFilter`, where JWT signature
  validation actually happens, is a runtime question this document doesn't resolve). **This
  self-call's own correctness does not depend on the answer**: it supplies a validly-signed JWT
  *and* the correct `X-Origin-Verify` value together, so it passes whichever filter runs first —
  the ordering question only matters for hardening the filters' behavior toward *other* traffic,
  which is out of this spec's scope, not for this mechanism working. Routing the self-call out
  through CloudFront and back would add a real network round-trip for no benefit and still require
  solving the same origin-verification problem. Instead, since the login handler runs inside
  api-gateway — the same process as the filter, on both clouds — it reads from the same
  environment the filter does. **On AWS**, where `CLOUDFRONT_ORIGIN_SECRET` is actually
  provisioned (pass 10 qualifier: an earlier draft of this sentence said the self-call "already
  has access to the same `CLOUDFRONT_ORIGIN_SECRET` environment variable" without qualifying that
  as AWS-only, which read as a cross-cloud premise directly contradicted two sentences later, where
  this same paragraph establishes Azure never provisions the variable at all), this means the
  self-call already has access to the identical value the filter reads — **the self-call SHALL set
  `X-Origin-Verify` to that value only when it is non-blank, and SHALL omit the header entirely
  otherwise (pass 9 correction: an earlier draft said "SHALL set... directly," unconditionally,
  which is not cross-cloud safe).** Verified
  directly: `CloudFrontOriginVerifyFilter.java` reads this variable once at construction
  (`System.getenv("CLOUDFRONT_ORIGIN_SECRET")`) and treats a null/blank value as "no-op — accept
  everything" (line 39-41, then the early `if (expectedSecret == null) return chain.filter(...)`
  check) — this is AWS-only behavior. Azure's `secret_env_vars` for api-gateway
  (`infrastructure/terraform/azure/main.tf` line 237-249) never provisions
  `CLOUDFRONT_ORIGIN_SECRET` at all, so on Azure (and local dev) the variable is always absent and
  the filter is permanently a no-op. An unconditional `X-Origin-Verify` assignment on a null value
  would either throw (most HTTP clients reject a null header value outright) or, if guarded
  naively, send an empty string that means nothing to a filter that isn't checking it anyway — both
  are the wrong shape for a design meant to work identically across deployment targets. THE correct
  behavior mirrors the filter's own logic: read `CLOUDFRONT_ORIGIN_SECRET` once, attach the header
  only if it's present and non-blank (AWS), and skip attaching it at all when it isn't (Azure,
  local) — the self-call succeeds either way, because the filter it's satisfying behaves
  identically. **Precisely, not "reuses" (pass 7 correction):** JWT signature/expiry validation
  is performed by Spring Security's `NimbusReactiveJwtDecoder` (`JwtDecoderConfig.java`), a
  separate component from `JwtAuthenticationFilter` — that filter only reads the `Principal` Spring
  Security has already placed on the exchange and extracts `sub`/injects `X-User-Id` from it (its
  own comment: "populated by Spring Security's WebFilter"). The self-call correctly relies on both
  components doing their normal job unchanged; no new internal-trust boundary is introduced for a
  *read*. **Also noted (pass 6):** the production `/api/portfolio` route carries a
  `RequestRateLimiter` keyed by `userOrIpKeyResolver` (`application-prod.yml` line 69-79) — the
  self-call consumes the demo user's own rate-limit budget, like any other request attributed to
  that user's JWT subject. At the login-gated frequency this trigger runs (once per idle-eligible
  login, not per request), this is not expected to matter in practice, but a `429` here is a real,
  reachable outcome and is covered by the fail-open rule below like any other non-success response.
  **Rejected alternative:** a direct trusted downstream call from api-gateway straight to
  portfolio-service (mirroring the internal-key trust boundary `/api/internal/**` uses for the
  reset *write*). Rejected because no such internal *read* path exists today, and inventing one
  duplicates `GET /api/portfolio`'s authorization logic in a second place rather than reusing it.
- **The reset-call self-call needs `INTERNAL_API_KEY` — a code gap, not a deployment gap (pass 7's
  original framing was wrong, corrected pass 8).** If the eligibility read finds the reset eligible,
  the login handler makes a *second* self-call — to `POST /api/internal/portfolio/demo-reset`,
  gated by `X-Internal-Api-Key` (D5 above), not by JWT. Pass 7 claimed this secret was missing from
  api-gateway's environment entirely and treated provisioning it as new deployment work. **That
  claim was checked against the wrong layer and was false.** Verified directly against
  infrastructure-as-code, not application config: AWS's `runtime_secrets` map
  (`infrastructure/terraform/aws/modules/compute/main.tf` lines 53-62) already merges
  `INTERNAL_API_KEY` into every Lambda, api-gateway included — the module's own comment says so
  explicitly ("Merged into all four Lambdas; the api-gateway receives it as pass-through"). Azure's
  `secret_env_vars` for api-gateway (`infrastructure/terraform/azure/main.tf` line 247) includes it
  too, and a live Azure query confirmed the deployed api-gateway Container App already carries an
  `INTERNAL_API_KEY` environment entry. The variable reaches api-gateway's process today; nothing
  currently *reads* it there, because no code has needed to — every existing `/api/internal/**`
  caller (the E2E seeder's CI/synthetic-monitoring callers) supplies its own key as the original
  caller, and api-gateway just proxies it through unread ("a dumb router for `/api/internal/**`"
  per its own route comments). The login-orchestrated reset call is the first caller *originating
  inside* api-gateway, so it's the first code that needs to bind this already-present environment
  variable. **Precisely, not `@Value("${app.internal.api-key:}")` (pass 9 correction — an earlier
  draft's suggested binding was itself wrong).** That placeholder resolves against Spring's
  `Environment`, which does expose raw OS environment variables by their literal name, but the
  YAML property path `app.internal.api-key` only maps to `INTERNAL_API_KEY` because
  `portfolio-service`'s own `application.yml` declares that exact indirection —
  `internal.api-key: ${INTERNAL_API_KEY:}` under its `app:` root
  (`portfolio-service/src/main/resources/application.yml:44-48`, feeding `InternalApiKeyFilter`).
  api-gateway's `application*.yml` files declare no such mapping, so `@Value("${app.internal.api-key:}")`
  inside api-gateway would resolve to the property's own default (blank) — never falling through to
  the real `INTERNAL_API_KEY` env var — and the reset leg would send an empty (or absent) key.
  `InternalApiKeyFilter` is fail-closed either way, but the two misconfigurations it distinguishes
  produce different statuses (pass 18 correction: an earlier draft collapsed both into a single `503`
  claim, which is only correct for the first case): **`503 internal_api_key_not_configured`** only
  when `portfolio-service`'s *own* configured secret (`app.internal.api-key`, fed by *its own*
  `INTERNAL_API_KEY`) is itself blank or absent — a `portfolio-service`-side deployment gap,
  independent of what any caller sends (verified directly, `InternalApiKeyFilter.java` lines 59-62).
  **`403 invalid_internal_api_key`** whenever `portfolio-service`'s own secret *is* configured but the
  caller's header is missing, blank, or simply wrong (lines 64-73) — this is the case an api-gateway
  binding bug of the kind above would actually produce, assuming `portfolio-service` itself is
  configured normally: a real caller, but with the wrong (empty) credential, not an unconfigured
  server. THE self-call code
  SHALL instead read the environment variable directly — `System.getenv("INTERNAL_API_KEY")`,
  mirroring how `CloudFrontOriginVerifyFilter` already reads its own secret in this same process —
  or, if a Spring-managed binding is preferred, api-gateway's own `application*.yml` SHALL first
  gain the equivalent mapping (`app.internal.api-key: ${INTERNAL_API_KEY:}`) before any code
  references `${app.internal.api-key}`. **No new secret provisioning, Terraform change, or
  deployment gate is required** — this is ordinary application code (or, at most, one new YAML
  line) reading an environment variable that has been deployed to this process all along.
- **Response shape:** `GET /api/portfolio` returns **`List<PortfolioResponse>`**, not a single
  object — verified directly against `PortfolioController.java:34-38`
  (`ResponseEntity<List<PortfolioResponse>>`). *(Pass 5 finding: this section's own earlier prose —
  "the same `GET /api/portfolio` response the browser already holds," a few paragraphs above — had
  been treating that response as one portfolio object rather than a list. D2 never described
  `GET /api/portfolio` at all, so nothing there needed correcting; a pass-5 note claiming otherwise
  was itself inaccurate and has been fixed here.)* The login orchestrator SHALL select the single
  entry whose `userId` equals
  `DEMO_USER_ID` — by B1's Primary_Portfolio invariant there is exactly one. An empty list (no
  demo portfolio provisioned) or more than one matching entry SHALL both be treated as an
  eligibility-read failure (see fail-open below), never as a `NullPointerException` or an
  arbitrary-element pick. The picker's own initial read (opening the modal) faces the same
  list-vs-object shape and SHALL apply the same selection rule, scoped to the logged-in user's own
  `userId` rather than `DEMO_USER_ID`.
- **Bounded timeouts, per leg and overall — not "the self-call" singular (pass 8 correction: an
  earlier draft assigned one timeout to one self-call, but D5 now describes two — the eligibility
  `GET` and the reset `POST` — leaving the second's latency contract, and the orchestration's total
  budget, unstated).** EACH of the two self-calls SHALL carry its own explicit timeout,
  provisionally **2 seconds per leg** — OPEN, the same provisional-value treatment as the presence
  TTL (D4) and the idle threshold (requirements.md 7.4): a starting value pending confirmation, not
  a frozen requirement. THE login handler SHALL additionally enforce an **overall orchestration
  deadline** across both legs combined, provisionally **4 seconds** (also OPEN) — bounding the
  worst case where the eligibility read consumes most of its own timeout budget before the reset
  call even starts, so the fail-open rule below has a hard ceiling to trigger against rather than
  relying on per-leg timeouts alone to compose into a bounded total. On any timeout — per-leg or
  overall — treat it exactly like any other eligibility-read or reset-call failure below: skip,
  proceed, no user-visible error.
- **Non-blocking execution, mandatory — not an implementation detail (pass 8 addition).**
  api-gateway's `/api/auth/login` handler is reactive WebFlux (`AuthController.login()` returns
  `Mono<ResponseEntity<Object>>`, verified directly against `AuthController.java:40`). Both
  self-calls SHALL be issued through a non-blocking HTTP client (e.g. Spring's `WebClient`) composed
  into the same reactive chain as the rest of the login flow. `.block()` and `RestTemplate` (a
  blocking client) SHALL NOT be used anywhere in this path — either would tie up one of the
  event-loop threads WebFlux uses to serve every concurrent request on this gateway for the
  duration of the call, turning a single slow eligibility read into a gateway-wide latency problem
  rather than a contained, per-request one. The two self-calls compose sequentially (eligibility
  read, then — only if eligible — the reset call), never in parallel, since the reset call's
  decision depends on the read's outcome.
- **The 2-second-per-leg and 4-second-overall values are OPEN, and now tracked as such** — added to
  requirements.md's Open items and this section's own cross-reference below, alongside the presence
  TTL and idle threshold this design already treats the same way. *(Pass 8 correction: previously
  marked "OPEN" only inline in this paragraph, with no entry in either Open-items surface — the
  exact failure mode `updatedAt` and `assetPriceFreshness` were already caught for.)*
- **Fail-open, defined by outcome class, not by enumeration.** THE rule is: **any eligibility-read
  or reset-call outcome other than a clean success SHALL skip the reset and let login proceed
  unaffected** — timeout, connection failure, and **every HTTP status outside 2xx**, without
  distinguishing which one. *(Pass 6 correction: an earlier draft enumerated "timeout, 5xx,
  response-shape failures," which reads as exhaustive but silently excludes anything in the 4xx
  range — concretely reachable here: `403` if the self-call's `X-Origin-Verify` header is ever
  wrong or the shared secret rotates out of sync, and `429` from the route's own rate limiter,
  above. Naming specific codes invites exactly this gap; "not a clean success" does not.)* This
  covers, without needing to list them individually: eligibility-read timeout, eligibility-read
  connection failure, eligibility-read `403`/`429`/`5xx`, eligibility-read returning zero or
  multiple demo-portfolio entries (a shape failure, not a status-code failure, but the same rule),
  reset-call timeout, reset-call `403`/`429`/`5xx`, reset-call rejection by the internal-key check,
  and the reset call's `409` (requirements.md 3c already covered this last one specifically). None
  of these SHALL be logged as user-facing errors or surfaced to the browser — they are operational
  signals only.

**Authorization — the missing rule, added on review.** `ReadOnlyEnforcementFilter` allowlisting a
path only controls whether a **read-only** (`ro=true`) principal may reach it — verified directly:
its `decide()` method returns `false` immediately whenever `ro` is false, so it does not gate
*non*-demo authenticated users at all. Allowlisting `PUT /api/portfolio/demo-reset` there makes it
reachable by the demo account despite `ro=true`; it does **not** restrict the endpoint to the demo
account. Without an independent check, any authenticated user — demo or not — could reset the
shared demo portfolio. THE GATEWAY SHALL therefore verify the caller's JWT subject equals
`DEMO_USER_ID` before the request ever reaches portfolio-service, and SHALL return a pinned `403`
with the exact body **`{ "error": "demo_reset_forbidden", "message": "Only the demo account may
reset the demo portfolio." }`** for anyone else — distinct from `read_only_account`, matching
`ReadOnlyEnforcementFilter`'s own two-field envelope shape exactly rather than merely resembling it
(pass 16 correction: an earlier draft pinned only `{ "error": "demo_reset_forbidden" }`, one field
short of what "same envelope shape as `ReadOnlyEnforcementFilter.writeForbidden()`" actually
requires — that method's own body carries both `error` and `message`, `ReadOnlyEnforcementFilter.java`
line 32), independent of and in addition to the allowlist entry, requiring its own contract test
alongside B1's existing envelope-error suite. *(Pass 5 correction: an earlier draft said only "a
distinct code," naming none.)*

**This check runs in api-gateway, as a new `GlobalFilter` — not inside a handler (pass 15 correction
of pass 14, refined pass 16).** "Verify the caller's JWT subject" needs a JWT decoder and a JWT
principal — portfolio-service has neither, so the check cannot run there; that much of pass 14's
reasoning holds. Pass 14's mistake was concluding the *handler* had to move to api-gateway to get
one. It doesn't: Spring Security's own resource-server `WebFilter` (`SecurityConfig.java`,
`oauth2ResourceServer(oauth2 -> oauth2.jwt(...))`) validates every JWT and populates
`exchange.getPrincipal()` for **every** request api-gateway receives, proxied or not — but
`JwtAuthenticationFilter`, the Gateway `GlobalFilter` that reads that principal, extracts `sub`, and
injects `X-User-Id`, only runs for requests Gateway *routes* onward (its own javadoc: "before
routing"). A new Gateway `GlobalFilter` — **`DemoResetAuthorizationFilter`** — reads the principal the
same way `ReadOnlyEnforcementFilter` already does: `exchange.getPrincipal()` cast to
`JwtAuthenticationToken`, `sub` from `getToken().getClaimAsString("sub")`. **Order, pinned
unambiguously (pass 16 correction — pass 15 left this as "after `JwtAuthenticationFilter` (+2)
alongside `ReadOnlyEnforcementFilter` (+3)," which reads as two different, tied values and specifies
neither cleanly): `Ordered.HIGHEST_PRECEDENCE + 4`** — after both `JwtAuthenticationFilter` (+2) and
`ReadOnlyEnforcementFilter` (+3). Reading the principal directly means this filter does not actually
depend on `JwtAuthenticationFilter` having run first for correctness (unlike `X-User-Id` injection,
which does) — but a unique, later value keeps behavior deterministic rather than relying on Spring's
bean-registration tie-breaking for two filters sharing an order value, and a routed integration test
plus an order-value assertion belong in the task breakdown, per Codex's own recommendation. It runs
only for `(PUT, "/api/portfolio/demo-reset")`; every other path passes through unchanged.

**Closing the gap for real, not declaring it out of scope (pass 16 correction of pass 15):** pass 15
called the public AWS Function URL's exposure pre-existing and out of scope, reasoning that
`DemoResetService.reset(...)`'s hardcoded target made a stray caller no worse off than any other
portfolio-service endpoint's `X-User-Id` trust. That comparison doesn't hold: every *other*
portfolio-service endpoint still requires a caller to supply *some* `X-User-Id` value, however
forgeable — pass 15's own new handler required no identity header at all, and
`InternalApiKeyFilter` (verified directly, `InternalApiKeyFilter.java` lines 53-57) passes every
non-`/api/internal/**` path through **untouched**, meaning that handler had strictly *less*
protection than the baseline it was compared against, not the same amount. The fix (this document,
above): there is no longer a portfolio-service handler at `/api/portfolio/demo-reset` for a stray
caller to reach at all. `DemoResetAuthorizationFilter`, on a successful subject match, mutates the
request to attach `X-Internal-Api-Key` — the same exchange-mutation pattern `JwtAuthenticationFilter`
already uses for `X-User-Id` — and the dedicated `demo-reset-manual` Gateway route rewrites the path
to `/api/internal/portfolio/demo-reset` before forwarding, so the manual trigger reaches
`InternalApiKeyFilter`'s real protection just like the login-orchestrated trigger already does. A
caller hitting portfolio-service's raw Function URL directly now needs the internal key, exactly like
every other route into `DemoResetService` — this is not "as protected as the rest of
portfolio-service," it's the same protection the internal endpoint has always had, reached through
one more hop. **New implementation detail:** api-gateway cannot import portfolio-service's
`DemoPortfolioInitializer.DEMO_USER_ID` across the process boundary, so `DemoResetAuthorizationFilter`
needs its own literal copy of the same UUID (`00000000-0000-0000-0000-0000000d3110`) — flagged here
so it doesn't drift silently between the two services; a contract test comparing them (or a single
source both build-time-inject from) belongs in the task breakdown. **What remains genuinely
out-of-scope:** the Function URL's `authorization_type = "NONE"` itself
(`infrastructure/terraform/aws/modules/compute/main.tf` line 416) still exposes every *other*
portfolio-service endpoint (e.g. `PUT /api/portfolio/holdings`) to the same direct-call risk this
section just closed for demo-reset specifically — that's a pre-existing, system-wide gap unrelated to
this feature, and restricting the Lambda Function URL itself is a B1/infrastructure change, not
B2's — same category of out-of-scope runtime question as pass 7's filter-ordering caveat, above.

**The `demo-reset-manual` route itself is a small, declarative addition — no secret in YAML.**
Both `application.yml` and `application-prod.yml` (which redefines the whole route list per its own
header comment, not merges it) need a new route entry — **shown here for each file exactly, not
just described in prose (self-audit note: an earlier draft showed only the `application.yml` shape
and described the `application-prod.yml` difference in a parenthetical, which a reader could copy
literally and ship the production route with no rate limiter at all)**:
```yaml
# application.yml
- id: demo-reset-manual
  uri: ${app.routes.portfolio-url}
  order: -1
  predicates:
    - Path=/api/portfolio/demo-reset
    - Method=PUT
  filters:
    - RewritePath=/api/portfolio/demo-reset, /api/internal/portfolio/demo-reset
```
```yaml
# application-prod.yml — same route, PLUS the same RequestRateLimiter every other route in this
# file already carries, so the manual trigger stays rate-limited like every sibling route
- id: demo-reset-manual
  uri: ${app.routes.portfolio-url}
  order: -1
  predicates:
    - Path=/api/portfolio/demo-reset
    - Method=PUT
  metadata:
    retry-after-seconds: 1
  filters:
    - RewritePath=/api/portfolio/demo-reset, /api/internal/portfolio/demo-reset
    - name: RequestRateLimiter
      args:
        key-resolver: "#{@userOrIpKeyResolver}"
        rate-limiter: "#{@standardRateLimiter}"
```
**It SHALL carry an explicit, lower `order` than the generic `portfolio-service` route — pass 17
correction, not merely list position.** An earlier draft of this note called list-first matching
"standard, documented Gateway behavior," which is imprecise: the resolved source shows
`CachingRouteLocator` sorts routes by `Route.order`, and `RouteDefinition.order` defaults to `0` when
unset — every route in both YAML files today, including the generic `portfolio-service` route, is
unset and therefore tied at `0`. List position only happens to break that tie today; it stops
mattering, in either direction, the moment any route in the file gains an explicit order for an
unrelated reason. `order: -1` above makes precedence explicit and independent of list position or any
future tie (lower `order` sorts first) — this route now always wins against the generic one,
regardless of where either is positioned in the file. **This exact combination — a purpose-built
`order` value plus an otherwise-overlapping predicate pair — has no precedent anywhere in this
codebase today** (every existing route pair has non-overlapping predicates), so it SHALL still be
covered by Test 1 below, spelled out completely here (pass 19 correction: now two tests, not one —
see below; the route-selection assertion belongs to Test 1 specifically, the gateway-side one)
(self-audit correction — earlier drafts scattered "SHALL be covered by a routed integration test"
across several paragraphs, each naming only the one or two assertions that paragraph itself
introduced, so the accumulated list was never stated in one place and a reader could easily implement
only the last-mentioned pair). **Two tests, not one — pass 19 correction: consolidating everything
into "one test" (pass 18) crossed a test boundary a single test can't actually cross.** A gateway
routed-integration test can observe transport (what api-gateway sends downstream) against a *stubbed*
portfolio-service; it cannot observe that a stub's handler is the *same Java call site* the real
`POST` path also reaches — a stub has no real call site to compare against. Proving "both verbs
converge on one controller method / one `DemoResetService.reset(...)` call" is portfolio-service's own
claim about its own code, provable only inside portfolio-service's own test suite, against its real
Spring context.

**Test 1 — Gateway routed integration test** (api-gateway's own test suite, request sent through the
full filter chain to a *stubbed* portfolio-service). Sending one real `PUT /api/portfolio/demo-reset`
request, this test SHALL assert all of the following — every one of them was a real P1 defect this
document's own review history found, not a hypothetical:
- The request reaches route `demo-reset-manual`, not the generic `portfolio-service` route.
- The downstream request's path is `/api/internal/portfolio/demo-reset`.
- The downstream request's **method is still `PUT`** (not silently coerced to `POST` — `RewritePath`
  never touches the method, pass 17).
- `Authorization` is **absent** on the downstream request.
- `X-User-Id` is **absent** on the downstream request.
- Exactly **one** `X-Internal-Api-Key` value arrives, equal to `INTERNAL_API_KEY`, even when the test
  sends a caller-supplied duplicate of that header on the original request (proves replace, not
  append — pass 17).

**Test 2 — a real Testcontainers integration test through the actual chain, not merely an MVC
slice (pass 24 correction: an MVC slice can mock `DemoResetService` itself and return a
hand-fabricated golden-looking response without ever touching `HoldingReplacementService`,
`GoldenStateTuplePreparer`, the catalog, or persistence — none of the assertions below distinguish
that from a real reset)** — portfolio-service's own test suite, no gateway involved at all (a
direct, in-process call to the internal endpoint), exercising the genuine
`DemoResetService → HoldingReplacementService → GoldenStateTuplePreparer → Catalog_Module →
persistence` chain end to end, with no component in that chain mocked. SHALL assert that an
authenticated `POST` to `/api/internal/portfolio/demo-reset` and an authenticated `PUT` to the same
path each invoke the same controller method and the same `DemoResetService.reset(...)` call exactly
once (a spy, not a stub, if call-count verification needs one) — proving the dual-verb mapping is
one shared entry point, not two independent ones that could drift (pass 17), this time provable
because the test runs against portfolio-service's real code, not a stub. **The test itself SHALL
configure a non-blank `app.internal.api-key` test property and supply the matching
`X-Internal-Api-Key` explicitly on both requests** — `InternalApiKeyFilter`'s default is blank,
which fails every `/api/internal/**` request with `503` before the controller is ever reached
(`InternalApiKeyFilter.java:41-46`), so a test that supplies the header without also configuring a
non-blank expected value never exercises anything past the filter — `DemoResetAuthorizationFilter`
never runs in this test either way (there is no gateway in scope), and the explicit header is the
*only* thing that ever attaches this value for the `PUT` path; portfolio-service's internal endpoint
otherwise receives no key from anywhere, by design, for either verb. **The golden set assertion
SHALL be checked against an independently-derived oracle, not the catalog's ticker membership
alone** — querying `Catalog_Module` proves the ticker *set* matches, not that each holding's
quantity, cost basis, currency/source, and the fixed `app.demo.cost-basis-anchor` were computed
correctly; the oracle SHALL compute the complete expected tuple from raw catalog data, the demo
UUID, and the anchor using B1's own frozen deterministic formulas ("computeDeterministicCostBasis
and the quantity derivation are unchanged," above) applied directly in the test, never by invoking
`GoldenStateTuplePreparer` or `DemoResetService` to produce the expected side of the comparison.
**Price-table non-mutation SHALL be a full-table, sentinel-backed byte-identity snapshot
before/after, matching B1's own `P10` regression discipline (`portfolio-composition-contract/tasks.md`
task 6.4)** — "writes no row" is weaker than what B1 already requires of the same class of
assertion elsewhere in this program.

`RewritePath` carries no secret and is
Spring Cloud Gateway's standard, first-party path-rewrite filter — deliberately not a raw secret
value in YAML (`AddRequestHeader` with an interpolated `${INTERNAL_API_KEY}`), since this app exposes
all actuator endpoints (`management.endpoints.web.exposure.include: "*"`, `application.yml`) and a
resolved route definition is the kind of thing an actuator endpoint can surface; the actual key
attachment happens in `DemoResetAuthorizationFilter`'s Java code (`System.getenv`, above), matching
how this codebase already avoids putting `INTERNAL_API_KEY` in `@Value`-bound YAML properties. **This
specific combination — a `GlobalFilter` mutating request headers on an exchange a route-level
`RewritePath` filter subsequently rewrites — also has no direct precedent in this codebase** (the
mutation pattern itself is proven: `JwtAuthenticationFilter` and `CloudFrontOriginVerifyFilter`
already mutate headers on exchanges Gateway then proxies) — already covered by the single integration
test spelled out above, which was written to assert every invariant this combination depends on
together, not path and headers as separate, independently-implementable checks.

**Deployment sequencing within api-gateway (self-audit addition): D5 and D6 SHALL land in the same
change, never one without the other.** `DemoResetAuthorizationFilter` (this section) only ever runs
for `(PUT, /api/portfolio/demo-reset)` — but `ReadOnlyEnforcementFilter` (D6, `+3`, running *before*
`DemoResetAuthorizationFilter` at `+4`) independently blocks any `ro=true` (read-only/demo) principal
from reaching a mutating `/api/portfolio/**` path **unless that exact `(method, path)` pair is on its
own allowlist**. If `DemoResetAuthorizationFilter` ships before D6's allowlist entry is added, the
demo user's own manual-reset click gets a `403 read_only_account` from `ReadOnlyEnforcementFilter`
before `DemoResetAuthorizationFilter` ever runs — the feature would appear completely broken for the
one account it's built for, not merely unprotected. Task breakdown SHALL treat D5 and D6 as one
deployable unit — both live inside api-gateway, so "same change" here really does mean one commit,
one deploy.

**Cross-service rollout sequencing (pass 19 correction — a different, larger claim than the one
above, and wrong to conflate with it): the api-gateway bundle above and portfolio-service's internal
endpoint are NOT one deployable unit, because api-gateway and portfolio-service are separate runtime
artifacts deployed non-atomically.** An earlier draft's "D5 and D6 SHALL land in the same change" was
read (reasonably, given how it was placed) as covering portfolio-service's endpoint too — it never
did, but the surrounding text didn't say so explicitly, and the master plan's own "(3)-(4) ship as one
deployable unit" repeated the same over-broad claim. Verified directly against both production deploy
workflows: on AWS, `deploy-aws.yml`'s `deploy-backend` job updates the api-gateway Lambda's `live`
alias (lines 176-226) **before** it even builds the portfolio-service image (line 232 onward) — the
job is strictly sequential, gateway first, same workflow run, no parallelism between them. On Azure,
`deploy-azure.yml`'s `deploy` job runs services through a `strategy: matrix` (line 202-204) — a
genuine parallel job matrix, so completion order between services is not guaranteed, and scoped
single-service deploys are separately supported (`reference-repo-conventions` memory: `gh workflow run
deploy-azure.yml --ref main -f services=api-gateway`).

**Stage 1 is not a mapping widening — pass 20 correction, and a more consequential one than it looks.**
An earlier draft of this note said Stage 1 "widens" portfolio-service's demo-reset mapping from `POST`
to `POST`+`PUT`, describing it as if a `POST`-only endpoint already exists there. **None of it exists.**
Verified directly: `portfolio-service` has no `DemoResetService` class and no
`/api/internal/portfolio/demo-reset` mapping anywhere in source today — grep for both returns zero
matches. The only existing controller under `/api/internal/portfolio` is `PortfolioSeedController`,
mapped solely to `POST /seed` (a different path entirely, for the E2E seeder). This document has
actually said so consistently everywhere else, since pass 5 ("`GoldenStateTuplePreparer`,
`HoldingReplacementService`, `TuplePreparer`, `CompositionResult`, and `RawIntent` exist nowhere in
`portfolio-service` source today") — the rollout note alone drifted into describing an "old, `POST`-only
portfolio-service" that was never real. **Stage 1 is therefore the full portfolio-service-side build,
not an incremental widening:**
1. **Prerequisite, owned by B1, not B2:** B1 Wave 4 task 4.1, `HoldingReplacementService` — the
   `replace(userId, expectedVersion, intent, preparer)` primitive `DemoResetService` depends on.
   Verified directly: unchecked in B1's `tasks.md` (line 581), no matching class exists. This spec
   cannot build `DemoResetService` before this lands, regardless of anything else here.
2. Build and deploy `DemoResetService` itself (this document, above) and a **new** internal controller
   mapping `/api/internal/portfolio/demo-reset` to **both** `POST` and `PUT` from the start — there is
   no "old" mapping to widen, so both verbs ship together, not `POST` first with `PUT` added later.
   Include Test 2 (the portfolio-service MVC test, below) as part of this same stage, not a follow-up.
3. Verify directly against the deployed environment (an authenticated `PUT`, with the internal key,
   against the live internal endpoint) that it actually works before proceeding — this is the point
   where "purely additive, safe standalone" genuinely applies: once this stage is live, nothing yet
   routes real user traffic to it (api-gateway hasn't shipped its side), so there is no live-traffic
   risk in taking the time to verify properly here.
4. Only then ship and deploy the **manual-reset gateway bundle** — the `demo-reset-manual` route,
   `RewritePath`, `DemoResetAuthorizationFilter`, and D6's allowlist entry — as the one deployable
   unit described above (**pass 21 correction: this unit no longer includes the login-orchestrated
   self-call machinery — see step 6 below for why**). Without stage 1 already live, this bundle's new
   route would forward `PUT` requests to an internal endpoint that doesn't exist at all (a `404` or
   connection failure at the proxy target, not the `405` an earlier draft of this note predicted —
   `405` presumes a real mapping that merely rejects the verb; nothing here is real yet at that point).
5. Expose the frontend's manual-reset control, as its own follow-up change — decouples "the backend
   capability exists and is verified" from "a user can trigger it," so a gap between earlier stages
   landing is never user-visible even if it takes more than one deploy cycle. **The manual-reset path
   is now complete end to end**, independent of step 6 below.
6. **The login-orchestrated self-call is a separate, later gateway deployment — not part of step 4's
   bundle (pass 21 correction of the pass 19/20 text, which bundled it in).** Bundling it there either
   blocks the manual path, which needs nothing beyond `version` and is unaffected by the `updated_at`
   gap (above), on unrelated unresolved work, or silently contradicts this document's own "mandatory
   release gate" framing two paragraphs below, which exists precisely to keep unresolved items from
   riding along with ready ones. Stage 1's internal endpoint already accepts `POST` from the moment it
   ships — nothing further is needed there for this trigger — so this step is purely gateway-side: the
   self-call code, `DemoResetAuthorizationFilter`'s and D6's coverage already being in place from step
   4. It ships only once three independent, currently-open items clear: `updated_at` gains an owner
   and lands on `PortfolioResponse` (a missing implementation commitment, D7 above — the trigger
   cannot be *built*, let alone deployed, without it), and the idle-reset threshold and the
   login self-call's per-leg/overall timeouts are decided (D7's open product/operational items). None
   of these gate step 4 or step 5.
7. Roll back in the reverse order, per stage actually deployed: disable/remove the frontend control,
   then the login-orchestration self-call (if shipped), then the manual-reset gateway bundle, then
   portfolio-service's endpoint (this last step actually does need care once real — by then it's live
   production surface, not the pre-traffic case stage 3 describes).

**This is a mandatory release gate, not a product acceptance criterion (pass 20 clarification —
resolving a real inconsistency: an earlier draft of this note said exactly that, "not a new acceptance
criterion," while requirements.md's own copy of this same sequencing used normative `SHALL` language,
which reads as exactly the opposite classification).** The gate itself — stage order, and that each
stage is verified live before the next starts — is release orchestration, not user-visible product
behavior, so it's tracked authoritatively in the master plan's task breakdown, not stated as a
requirements.md acceptance criterion; requirements.md cross-references it without asserting its own
`SHALL` for the sequencing itself.

**Filter scoping, made explicit against a real leak risk (self-audit addition):**
`DemoResetAuthorizationFilter` SHALL match on the exact pair `(HttpMethod.PUT,
"/api/portfolio/demo-reset")` — **method AND path together, never method alone.** The risk this
guards against: if implemented by checking only `exchange.getRequest().getMethod() == PUT` (loosely
mirroring `ReadOnlyEnforcementFilter`'s broader pattern-matching style) without also requiring the
exact path, the filter would attach `X-Internal-Api-Key` to *any* `PUT` the demo user makes —
including `PUT /api/portfolio/holdings`, the ordinary composition save — forwarding the internal key
to a non-internal route on every save the demo account performs. A contract test asserting the header
is **absent** on `PUT /api/portfolio/holdings` (not just present on the demo-reset path) belongs in
the task breakdown alongside Test 1 above.

**This check applies to the `PUT` (manual) endpoint only — the `POST /api/internal/...` endpoint
cannot perform it, and does not try to.** Verified directly against the gateway:
`SecurityConfig.pathMatchers("/api/internal/**").permitAll()` (`SecurityConfig.java:40`) and
`JwtAuthenticationFilter` skips JWT processing entirely for that prefix, stripping any
caller-supplied `X-User-Id` rather than trusting or forwarding one (`JwtAuthenticationFilter.java`
lines 39-51) — there is no JWT, no `sub` claim, and no caller identity of any kind by the time a
request reaches the internal controller, **for a request whose path is genuinely `/api/internal/**`
at the point `JwtAuthenticationFilter` evaluates it** — true for the E2E seeder's own direct calls
and for the login-orchestrated self-call (which targets that path directly, not via a rewrite). **The
manual trigger's request is NOT naturally in that category** — pass 17 finding: it arrives at
`JwtAuthenticationFilter` still addressed to `/api/portfolio/demo-reset`, so the filter takes its
normal branch (preserves `Authorization`, injects `X-User-Id`) rather than its `/api/internal/**`
one; the request only *ends up* identity-free by the time it reaches portfolio-service because
`DemoResetAuthorizationFilter` explicitly strips both headers itself (above), not because the
`/api/internal/**` prefix match ever applied to it. Its authorization is entirely the
`X-Internal-Api-Key` header plus the server-fixed `DEMO_USER_ID` target hardcoded into
`DemoResetService.reset(...)` — the same model B1's `PortfolioSeedController` already uses for
`E2E_USER_ID` (B1 `design.md` D8). A "demo-identity check" on the internal endpoint would be a check
against nothing: there is no caller-supplied identity there to compare, for either of its two
callers, by construction rather than by accident. *(Pass 4 correction: this section previously implied,
and the master plan's Track C table explicitly stated, that both reset endpoints perform a
JWT-subject check — only `PUT` can, since only it is ever reached with a JWT at all.)*

**Success/conflict semantics, made explicit (previously unspecified):**
- Manual trigger: `200` with the fresh `PortfolioResponse` on success; `409
  portfolio_version_conflict` on a stale `expectedVersion`, handled by the same UI pattern as
  Requirement 4 (no retry, user-visible, explicit re-observe-and-retry left to the user).
- Login-orchestrated trigger: fail-open relative to login, exhaustively — see "The login-orchestrated
  eligibility read" above for the full list of failure modes covered (both the eligibility read's
  and the reset call's), not just the reset call's `409`.
- **Manual trigger transport failure (pass 15 addition, still holding as of pass 16 — a pass-14 loose
  end closed by never introducing a self-call for this trigger, not by adding a new contract):** pass
  14 briefly introduced a self-call needing its own timeout/failure mapping; pass 15's revert removed
  it. Pass 16 changed *how* the manual trigger is routed (a dedicated `demo-reset-manual` route with a
  `RewritePath` filter, not the generic `/api/portfolio/**` route) but not *that* it's ordinary Gateway
  routing rather than a self-call — `spring.cloud.gateway.server.webflux.httpclient.connect-timeout`
  (5s) and `response-timeout` (55s, `application-prod.yml`) are process-wide settings, not per-route,
  so both routes share the same bound and the same standard `502`/`504` behavior on connect/response
  failure. No new timeout value or failure contract is needed for this endpoint specifically.

**Sequencing, restated precisely.** The manual trigger (needs only `version`) is implementable once
B1 Wave 5 task 5.1 (which exposes `version` on `GET /api/portfolio`) and the boundary above land —
**not Wave 7** *(pass 7 correction: an earlier draft said "Wave 7," which is B1's unrelated
composition-`PUT` activation gate; nothing in Wave 6 or 7 is a stated dependency of this trigger
anywhere else in this section, and the surrounding "Precisely, not 'already'" correction above
already ties it to Wave 5/task 5.1 specifically)*. The login-orchestrated idle-reset trigger additionally needs
`updated_at` on `PortfolioResponse` — the unassigned gap described above, not merely "B1 Wave 3,"
which adds only the column. `portfolios.updated_at` does not exist in the current schema at all
yet; Wave 3/V20 adds it, but exposing it on the wire is the separate, currently-unowned step this
design flags.

## D6 — `ReadOnlyEnforcementFilter` allowlist change

Current: `aiAllowlistPatterns` is `List<String>`, matched only by `matcher.match(pattern, path)` —
verified directly against `ReadOnlyEnforcementFilter.java`, not only against the brainstorm's claim
about it. No method component exists today. Change: allowlist entries become `(HttpMethod, String
pathPattern)` pairs; add exactly **two** entries — `(PUT, "/api/portfolio/holdings")` for
composition (Requirement 5) and `(PUT, "/api/portfolio/demo-reset")` for the manual reset trigger
(D5) — the second entry added on review, missed when D5 still assumed B2 could reuse an
internal-key-protected B1 endpoint that the browser was never going to reach anyway. No other
existing allowlist behavior changes. **Distinct from, and not a substitute for, `DemoResetAuthorizationFilter`
(D5, pass 15):** this allowlist only ever answers "may a read-only principal reach this path at all"
— it says nothing about *which* authenticated user is calling. The new filter answers the second
question (JWT subject must equal `DEMO_USER_ID`); both run for the same request, checking unrelated
conditions.

## D7 — What this design does not (yet) specify

Per `requirements.md`'s open items: the idle-reset threshold, the manual reset control's placement,
the presence TTL's exact value, and — added pass 8 — the login self-call timeouts (D5: 2 seconds
per leg, 4 seconds overall, both provisional). These are product/operational decisions, not
implementation unknowns — do not resolve them by picking a default in code without raising them.
(A quantity upper bound was listed here in Revision 1's first draft; it is not open — B1
Requirement 3.1 already freezes it at `99999999999.99999999` — removed from this list on review.)

**The frontend decimal-adapter migration's rollout sequencing (requirements.md Requirement 8.3) —
missing from this list until now, added on pass 5's cross-document audit.** D3 above states the
sequencing obligation (`BackendHolding.quantity: number → string` must not go live ahead of B1's
string-quantity read contract) as a settled `SHALL`, but *when* — coordinated against which of B1's
Wave 4/5 deploys, by whom — is not decided, matching requirements.md's own framing of this item as
still open. This section exists specifically to catch open items D3 states as settled-in-shape but
unsettled-in-timing; it had not, until this pass.

**`updatedAt` exposure on `PortfolioResponse` — blocking, added on pass 5 review.** D5 above flags
this in detail; it belongs here too, since this is the section a reader checks for what's
unresolved. Wire contract once assigned: field `updatedAt` (camelCase; the database column is
`updated_at`, the wire field is not), ISO-8601 timestamp string matching `createdAt`'s existing
encoding, one per element of the `List<PortfolioResponse>` `GET /api/portfolio` returns, gated on
whichever B1 wave takes the task, with a contract test. Without an owner assigned, the
login-orchestrated idle-reset trigger (requirements.md 7.4) cannot be built — this is not a
decision B2 can pick a default for; unlike the four items above (pass 9 correction: this said
"three" after the timeout item was added above it as a fourth), it is a missing implementation
commitment, not a missing product call.

**`assetPriceFreshness` has not landed yet either — added pass 7, same failure mode as
`updatedAt`.** Requirement 3.2 (and 3.4, new this pass) depend on this field; Spec A
(`supported-asset-integrity`) `tasks.md` task 8.6 — the freshness summary contract that would
produce it — is unchecked, and the field appears nowhere in `portfolio-service` or frontend source
today. This was stated inline in requirements.md 3.2 but, like `updatedAt` before it, never added
to either Open-items surface until this pass. Not a product decision — a backend implementation
dependency, same class as `updatedAt` above, now doubly load-bearing since Requirement 3.4 depends
on it too.
