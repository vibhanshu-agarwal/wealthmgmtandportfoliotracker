# Responsive dashboard sidebar on narrow screens

**Status:** Resolved on `main` — PR [#297](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/297)
merged on 2026-09-20 at `4f288c4a0e8393e78efd7f7449c114e25db78818`.
**Priority:** Medium
**Implementation owner:** Claude (UI/layout); accepted head
`c2d3dc66a5995ea7aafff50c691df7c0f6889cd5`.
**Origin:** B2 Wave 6 review of [PR #214](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/214), source `ded1a0e1`.

## Resolution

Phase 2.1 implemented the self-contained icon-rail option identified below. Below `md`, the sidebar
is 64px wide, all five navigation links remain present with accessible names, link targets retain a
44px minimum height, and the formerly dead tooltip is enabled for mouse and keyboard interaction.
At `md` and above the labelled 240px sidebar remains unchanged.

The investigation also separated a second root cause from the original clipping report. Absolutely
positioned `sr-only` labels could extend the document scroll area, while `overflow-hidden` made the
shell programmatically scrollable through `scrollIntoView()`. The merged shell is therefore a
`relative` / `overflow-clip` boundary; `<main>` and the chat transcript remain the intentional
scrollers.

Accepted verification recorded in PR #297 includes 18 focused tests, 40 captures across Overview,
Portfolio, Market Data, and AI Insights at five viewports in both themes, and 130/130 browser checks
for extended-chat and navigation defect paths. PR-head CI passed, including 1,627 Vitest tests on
Linux. The merge did not deploy the frontend. Firefox, Safari, real touch devices, and Production
hosting remain untested; the browser harness and screenshots are not tracked in the repository.

This closure does not cover the separately observed Portfolio horizontal overflow at 320px and
375px or the Overview horizontal overflow at 320px. Those remain explicit Phase 2 slices in the
[demo preparation plan](../../../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md).

## Problem and evidence

Claude's local mock preview reported the manual-reset button clipped at a 375px viewport
because the dashboard sidebar does not collapse. Codex verified that
`frontend/src/components/layout/Sidebar.tsx` retains `w-60 shrink-0`, while
`DashboardLayout.tsx` constrains the remaining content with overflow handling and padding.
Both files are unchanged from the PR's base `06b35250`, establishing an existing shared
layout constraint.

**Visually confirmed (2026-09-02).** Claude captured a genuine 375×812 screenshot against the
local mock preview (not a description — a real rendered page): the sidebar claims its full
`w-60` (240px) unconditionally, leaving `<main>` roughly 135px wide; `ManualResetControl`'s own
label is visually clipped exactly as the existing `EditHoldingsButton` already is on every other
page through the same shell. Screenshot included in PR #214's evidence.

**Independent scoped-fix assessment (2026-09-02, 2-agent investigate+verify).** Root cause:
`Sidebar.tsx:70` (`w-60 shrink-0`) has zero breakpoint handling anywhere in the file, and neither
`Header.tsx` nor anywhere else in the shell has a hamburger/nav-toggle control (confirmed by
grep and by accessibility-tree search — the only "menu" match app-wide is `UserMenu.tsx`'s
unrelated account-menu button). The only existing responsive-hide pattern in this codebase
(`UserMenu.tsx:50`'s `hidden md:block`) was checked and explicitly rejected if applied to the
whole sidebar: it would remove all 5 nav links below 768px with no replacement — a functional
regression, not a fix. A genuine fix that preserves navigation (drawer, icon-rail, bottom tabs)
needs new work this codebase doesn't already have: no `Sheet`/`Drawer` primitive exists (only a
centered-modal `Dialog`, shape-wrong for a slide-in drawer), and `Header`/`Sidebar` are sibling
Client Components under a deliberately-Server-Component `DashboardLayout` with no shared state
between them today.

The independent verify pass surfaced one refinement worth recording for whoever picks this up: a
**self-contained collapsed icon-rail** (e.g. `w-16 md:w-60` on the `<aside>`, nav labels switched
to `hidden md:inline` reusing the exact `UserMenu.tsx` pattern) is cheaper than a full drawer —
it needs no new cross-component state and no new overlay primitive, staying entirely inside
`Sidebar.tsx`. It is still a real product/UX call (icon-only nav with no visible labels is a
discoverability trade-off), and shipping it responsibly also means fixing a latent, unrelated bug
found in the same pass: `NavLink`'s tooltip is hard-coded `className="hidden"` (dead code — it
never renders on hover today), which icon-only nav would need working to stay accessible. Treat
the icon-rail as the likely-cheaper implementation path when scoping this item, not a full
hamburger+drawer+new-Context rebuild by default.

Both the investigation and the independent verification agent concluded: no scoped, PR-local fix
exists for B2 Wave 6; this stays a backlog item, not something to fold into PR #214.

## Implemented scope

The shared dashboard navigation and shell were made usable on narrow screens with the
self-contained icon rail. No hamburger, drawer, overlay primitive, or shared Header/Sidebar state
was added. API calls, authentication, portfolio/reset behavior, and feature flags were unchanged.

## Acceptance — complete for this backlog item

- [x] At 375px and a smaller supported mobile width, navigation and primary page actions remain
  visible and operable without sidebar-induced clipping.
- [x] All icon-rail links retain accessible names and keyboard access; no navigation toggle or
  drawer was introduced.
- [x] Verify narrow, tablet, and desktop layouts in light and dark themes with saved screenshots.
- [x] Recheck reset/edit controls where enabled in a local mock preview and representative pages
  using the shared shell; no production reset is needed.

## Relationship to Asset Picker

The owner explicitly deferred this existing shell issue from B2 Wave 6 and it did not block that
source review. PR #297 later resolved it as Demo Preparation Phase 2.1 without reopening or changing
any Asset Picker delivery gate. The merged source is not a Production deployment or whole-Phase-2
acceptance.

Tracked from the [master plan](../../../plans/ASSET_PICKER_E2E_MASTER_PLAN.md) and
[B2 ledger](../../../../.kiro/specs/asset-picker-composition/tasks.md).
