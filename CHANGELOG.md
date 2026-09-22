# Changelog

All notable product-release changes are recorded here. Historical implementation records remain
under `docs/changes/`.

## [Unreleased]

## [0.9.0] - 2026-09-22

### Added

- Established the governed product Semantic Versioning foundation. Root `VERSION` is the single
  product-version authority; the rules are in `docs/release/SEMANTIC_VERSIONING_POLICY.md`.
- Required CI validates the version contract on every pull request targeting `main` or
  `architecture/**` and on each configured branch push, and a dedicated validation-only workflow
  checks `v*` release tags. Tag validation is detective, not
  preventive, and a tag or GitHub Release never deploys anything.

### Changed

- Gradle builds the root project and every subproject at the product version, and every
  Gradle-evaluating service Docker build receives `VERSION` in its build context.
- Subproject build archives are now versioned (for example `common-dto-0.9.0.jar`). No deployment
  path depends on the old names, and API Gateway's pinned archive names are unchanged.
- The private frontend package and its lockfile mirror the product version.

### Removed

- The sidebar footer's hard-coded "Phase 1 · v0.1.0" label, which contradicted the product version
  once it moved to `0.9.0`.

### Fixed

- The Overview asset-allocation donut no longer paints a slice in another asset class's colour when
  the chart re-draws; every slice now matches its legend.
- AI Insights summary pills end long text with an ellipsis instead of cutting it off mid-word, and
  the full summary is available on hover.
- Market Data's 24h Change column shows each holding's 24-hour change from portfolio analytics
  instead of always showing "—". It still shows "—" when no 24-hour reference exists.
- The Overview performance chart highlights one period badge, the smallest period that covers the
  series, instead of both 30D and 50D.

### Status

- Pre-demo-certification release line. Phase 3 Production E2E and final `1.0.0` acceptance remain
  pending.
