# Changelog

All notable product-release changes are recorded here. Historical implementation records remain
under `docs/changes/`.

## [Unreleased]

## [0.9.0] - Unreleased

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

### Status

- Pre-demo-certification release line. Phase 3 Production E2E and final `1.0.0` acceptance remain
  pending.
