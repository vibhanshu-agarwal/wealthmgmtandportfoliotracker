# Responsive dashboard sidebar on narrow screens

**Status:** Open — deferred to the backlog by the owner on 2026-09-02.
**Priority:** Medium
**Implementation owner:** Claude (UI/layout); not started.
**Origin:** B2 Wave 6 review of [PR #214](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/214), source `ded1a0e1`.

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

## Scope when picked up

Make the shared dashboard navigation and content usable on narrow screens. A self-contained
collapsed icon-rail (`Sidebar.tsx` only, reusing the existing `hidden md:*` pattern, no new
cross-component state or overlay primitive) is the likely-cheaper path versus a full hamburger
+ slide-in drawer + new shared Header/Sidebar state; choose the interaction during design
review — either preserves live navigation at every width, which a bare "hide the sidebar below
`md`" does not. Cover the Portfolio page and other pages using the same shell, while preserving
the desktop layout. If icon-rail is chosen, also fix `NavLink`'s currently-dead
`className="hidden"` tooltip so icon-only links stay accessible.

Keep API calls, authentication, portfolio/reset behavior, and feature flags outside this
layout task.

## Acceptance

- At 375px and a smaller supported mobile width, navigation and primary page actions remain
  visible and operable without sidebar-induced clipping.
- Any navigation toggle has an accessible name, visible keyboard focus, and correct expanded
  state; if a drawer is used, verify keyboard dismissal and focus restoration.
- Verify narrow, tablet, and desktop layouts in light and dark themes with saved screenshots.
- Recheck reset/edit controls where enabled in a local mock preview and representative pages
  using the shared shell; no production reset is needed.

## Relationship to Asset Picker

The owner explicitly deferred this existing shell issue to a separate backlog item. Its fix
does not block B2 Wave 6 source review. Wave 6 still needs its own actual UI-state screenshots;
backlogging this issue does not claim that narrow-screen behavior passed or close other gates.

Tracked from the [master plan](../../../plans/ASSET_PICKER_E2E_MASTER_PLAN.md) and
[B2 ledger](../../../../.kiro/specs/asset-picker-composition/tasks.md).
