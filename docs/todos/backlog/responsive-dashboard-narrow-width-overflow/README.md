# Responsive dashboard: Portfolio and Overview horizontal overflow at narrow widths

**Status:** Open — explicitly accepted for the desktop-only demo on 2026-09-20.
**Priority:** Medium
**Implementation owner:** unassigned; not started.
**Origin:** Phase 2 of the [demo preparation plan](../../../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md),
observed during the Phase 2.1 responsive-shell proof (PR #297, merge commit `4f288c4a`).

## Accepted, not fixed

These two defects are **not repaired**. The owner narrowed the demonstration contract to desktop
viewports, which takes them off the demo-critical path. That changes their priority, not their
status: the pages still overflow at these widths, and nothing in the product Semantic Versioning
slice changed the components involved.

Any future decision to support mobile or tablet widths **reactivates both items**, and they must be
closed before that support is released.

## The defects, as recorded

| Page | Width | Recorded attribution | Source file |
|---|---|---|---|
| Portfolio | 320px and 375px | the holdings action row | `frontend/src/components/portfolio/HoldingsTable.tsx` |
| Overview | 320px | the performance range badges | `frontend/src/components/charts/PerformanceChart.tsx` |

Both are **page-level** horizontal overflow: the page itself gains a horizontal scroll, as opposed
to a component that scrolls inside its own container.

## Evidence provenance — read before starting

- The attribution above is the one recorded by the Phase 2.1 proof. That proof's browser harness
  and screenshots were not tracked in the repository, so no line-level root cause is on record and
  none is claimed here. Confirm the offending element by measurement before changing anything.
- Neither defect was re-measured when this entry was written.
- Reproduce with representative holdings data. The totals footer renders only when holdings are
  present, so an empty session cannot exercise every candidate element — which of them causes the
  overflow is exactly what is not yet known. The mocked Playwright fixtures under
  `frontend/tests/e2e/` are a starting point.

## Acceptance criteria when picked up

- No page-level horizontal overflow on Portfolio at 320px and 375px, or on Overview at 320px.
- Intentional component-level scrolling is preserved. The holdings table may still scroll
  horizontally inside its own container; only page and shell overflow is the defect.
- Verified against a real rendered page with the widths and measurements recorded — for example
  `document.scrollingElement.scrollWidth` against `clientWidth` — not by a screenshot alone.
  Headless Chromium hides scrollbars, so a clean-looking capture is not evidence of no overflow.
- When measuring after a viewport change, let a frame paint first, or disable transitions. A
  transition can sit at its starting value until the first paint and report a transient layout
  that is not the settled one.
- No regression to the desktop layout at the agreed demo viewport(s), and the Phase 2.1
  shared-shell behaviour is unchanged.

## Related

- [`responsive-dashboard-sidebar`](../responsive-dashboard-sidebar/README.md) — the earlier
  narrow-screen navigation item, closed by Phase 2.1. Its closure note explicitly excludes these
  two overflows; neither item is fixed by the other.
