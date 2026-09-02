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
layout constraint. The reported narrow screenshot still needs to be attached; its precise
clipping has not yet been visually reviewed by Codex.

## Scope when picked up

Make the shared dashboard navigation and content usable on narrow screens. Assess a
collapsible sidebar or accessible mobile drawer using existing design tokens and components;
choose the interaction during design review. Cover the Portfolio page and other pages using
the same shell, while preserving the desktop layout.

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
