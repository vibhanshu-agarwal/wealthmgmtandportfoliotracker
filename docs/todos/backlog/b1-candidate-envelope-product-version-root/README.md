# B1 candidate envelope must include the product-version root

**Status:** Open — required before the next `VERSION` change or any new B1 candidate-envelope
attestation.
**Priority:** High
**Implementation owner:** unassigned; not started.
**Origin:** post-implementation reconciliation of the `0.9.0` product Semantic Versioning
foundation at local candidate `19fb82d5`.

## Problem

**Audit (2026-09-26 UTC):** still OPEN. `_UNIVERSAL_ROOTS` and all four policy-envelope
root/membership sets still omit `VERSION`, although root Gradle and service Dockerfiles consume it.
A read-only in-memory check confirmed that changing only `VERSION` leaves the derived envelope
digests unchanged. This is not repaired by the historical GC.5 queue closure. The sequencing
and independently reviewed re-attestation below remain mandatory.

The SemVer foundation makes root `VERSION` an input to the root Gradle build. That value becomes the
Gradle project version and is written into packaged JAR metadata. Every Gradle-building Docker
context also copies the file.

The B1 candidate source-envelope guard derives common build-graph roots from `_UNIVERSAL_ROOTS` in
`scripts/check_b1_candidate_source.py`. That tuple includes root Gradle settings, wrappers, and
build-logic paths, but not `VERSION`. A future version-only commit can therefore change deployable
artifact bytes while the envelope omits the changed input. A green check under that condition would
not prove the source-to-artifact boundary it claims.

The initial `0.9.0` implementation also changes already-covered build inputs, so this omission does
not make the SemVer implementation diff invisible as a whole. It does make the next version-only
transition unsafe to attest with the current root set. Existing B1 R-C evidence remains historical;
this item neither rewrites nor invalidates it.

## Required sequencing

This item must land and receive independent review:

1. before the next change to root `VERSION`, including the eventual `1.0.0` transition;
2. before generating or relying on a new B1 candidate source-envelope attestation; and
3. in a separate change from the `1.0.0` stable-version transition.

The separation in item 3 is mandatory. The governed stable transition may change exactly
`VERSION`, `CHANGELOG.md`, `frontend/package.json`, and `frontend/package-lock.json`; adding a guard,
test, or policy-record change to that transition would invalidate the accepted Phase 3 evidence and
require re-entry.

## Expected implementation scope

- Add exact root `VERSION` to `_UNIVERSAL_ROOTS` in `scripts/check_b1_candidate_source.py`.
- Add focused tests proving that a `VERSION`-only tree change alters the derived envelope evidence
  and that omission is rejected.
- Recompute and deliberately review the four affected `roots_digest` / membership / envelope record
  sets in `scripts/b1-candidate-policy.json`; do not hand-edit only the digest strings.
- Run the complete B1 candidate-source test and mutation suites with no skipped portion called PASS.
- Obtain independent review of both the root derivation and the re-attested policy records.
- Reconcile this backlog item, the B1 task ledger, and the E2E master plan in the same status-changing
  pull request.

## Non-scope

- Do not change product SemVer policy, the selected `0.9.0` value, deployment behavior, registry
  tags, or `SERVICE_VERSION`.
- Do not create a product tag, GitHub Release, deployment, or Production probe.
- Do not modify existing sealed evidence merely to make an old cut appear newly attested.
