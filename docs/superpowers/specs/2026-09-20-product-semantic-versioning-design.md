# Product Semantic Versioning Foundation — Design

**Status:** draft for owner review
**Date:** 2026-09-20
**Track:** release governance and build metadata
**Current product version selected by this design:** `0.9.0`
**First demo-ready product version:** `1.0.0`

---

## OWNER APPROVAL STATUS

The owner authorized drafting this design and its implementation plan. That authority does **not**
authorize implementation, a commit, push, pull request, merge, tag, GitHub Release, deployment,
Production probe, repository-ruleset change, or the start of Phase 3.

Those decisions remain separate:

1. approve the design and implementation plan;
2. authorize implementation;
3. accept the independently reviewed implementation;
4. authorize publication and merge;
5. authorize the `v0.9.0` tag and pre-release rehearsal; and
6. after the rehearsal is verified, authorize Phase 3.

No tag or GitHub Release may trigger deployment. Deployment remains a separately approved action
through the existing gated dispatch path.

## 1. Decision summary

The repository will use **one product Semantic Version** for the integrated application. The
frontend, API gateway, portfolio service, market-data service, and insight service are parts of one
release set and do not receive independent SemVer lines at this stage.

The product begins at `0.9.0`. The exact demo-ready release will be `1.0.0` after Phase 3,
remediation of its demo-blocking findings, final desktop acceptance, and release-set attestation.

SemVer answers, “Which product contract and capability set is this?” It does not replace artifact
identity. Commit SHA, container image digest, frontend deployment run/build identity, and serving
revision remain the evidence for “Which exact bytes are running?”

The source of truth is a root `VERSION` file. Gradle consumes it, the private frontend package is
kept aligned with it, and a required CI validator rejects invalid bytes, invalid SemVer, drift, an
unreleased changelog on a tag, or a tag that does not equal `v<VERSION>`.

## 2. Context and intent

The repository currently carries several unrelated version-like values:

- root Gradle version `0.0.1-SNAPSHOT`;
- private frontend package version `0.1.0`;
- infrastructure package version `0.1.0`;
- sanitizer/static-guard package version `1.0.0`;
- per-service source-SHA image tags and immutable image digests; and
- historical tag `v1.0-modular-monolith`.

None is an authoritative product release version. The result is ambiguity for release notes,
artifact promotion, and the future `1.0.0` milestone.

The owner has also narrowed the demonstration contract to desktop only. The known 320px/375px
Portfolio and Overview overflows therefore remain responsive-polish backlog items rather than
demo blockers. The next delivery sequence is:

1. establish and rehearse this SemVer foundation at `0.9.0`;
2. enter Phase 3 broad Production browser E2E at the agreed desktop viewport(s);
3. remediate or explicitly accept Phase 3 findings; and
4. promote the exact accepted release set to `1.0.0`.

## 3. Goals and success criteria

### 3.1 Goals

- Establish one unambiguous product version and bump policy.
- Make `0.9.0` the current pre-demo-certification product version.
- Make `1.0.0` a defined compatibility and acceptance boundary, not a marketing label.
- Prevent version drift between the root build and the frontend package.
- Prove that release tags run validation but never deployment.
- Preserve source SHA and image digest as the authoritative deployment identity.
- Keep internal tools, infrastructure packages, API versions, event versions, database migrations,
  and portfolio optimistic-lock versions distinct from the product version.

### 3.2 Success criteria

The foundation is source-complete when:

- root `VERSION` is exactly the ASCII bytes `0.9.0\n`;
- Gradle resolves the root project and every subproject to `0.9.0`;
- the frontend `package.json` and lockfile root package resolve to `0.9.0`;
- all Docker builder contexts that consume root `build.gradle` also contain `VERSION`;
- a stdlib-only validator and its tests cover bytes, SemVer, changelog, frontend drift, Docker
  context drift, branch builds, release tags, release candidates, and the historical tag;
- required CI runs the validator on every pull request, configured branch push, and `v*` tag push;
- no deploy workflow is tag- or release-triggered;
- the versioning policy and demo-preparation plan agree on the desktop-only and Phase 3 sequence;
  and
- implementation has independent review with no unresolved blocking finding.

The release mechanism is rehearsed only after a separately authorized annotated `v0.9.0` tag and
GitHub pre-release are created, validation is green, and no deployment workflow starts.

## 4. Scope

### 4.1 In scope

- Root `VERSION` and root `CHANGELOG.md`.
- Product SemVer policy under `docs/release/`.
- Root Gradle and subproject version alignment.
- Frontend package and lockfile version alignment.
- Docker build-context compatibility for the root `VERSION` dependency.
- A tested repository version validator.
- Required CI and tag-validation wiring.
- A manual, governed `v0.9.0` release rehearsal.
- Reconciliation of the Asset Picker demo-preparation plan.

### 4.2 Out of scope

- Independent versions for individual services.
- API-route, Kafka-topic, event-schema, database-migration, or portfolio aggregate versioning.
- Changing or repairing `SERVICE_VERSION`.
- Terraform changes or adding `PRODUCT_VERSION` to deployed environment variables.
- Deploying by SemVer, creating mutable `latest` behavior, or replacing SHA/digest pinning.
- Publishing SemVer aliases to ACR, ECR, or GHCR.
- Automated bump inference, Conventional Commits enforcement, release-please, or semantic-release.
- Automatic deployment from a tag or GitHub Release.
- Phase 3 execution, Production mutation, or Production verification.
- Fixing the deferred mobile-width Portfolio and Overview overflows.

## 5. Version domains

The following domains are intentionally separate:

| Domain | Example | Authority | Purpose |
|---|---|---|---|
| Product SemVer | `0.9.0`, `1.0.0-rc.1`, `1.0.0` | root `VERSION` | Product capability and compatibility line |
| Source identity | 40-character Git SHA | Git and build provenance | Exact source tree |
| Container identity | `sha256:<64 hex>` | OCI registry | Exact backend image bytes |
| Frontend deployment identity | source SHA + workflow run/attempt + build ID | deployment evidence | Exact static frontend build |
| Service runtime revision | Azure revision or Lambda version/alias | cloud platform | Exact serving unit |
| HTTP API contract | route/payload contract | controllers, DTOs, Pact/tests | Consumer compatibility |
| Event contract | topic/schema version | topic/schema governance | Producer-consumer compatibility |
| Database migration | Flyway sequence such as `V20` | migration history | Persisted-schema evolution |
| Portfolio version | integer optimistic-lock version | portfolio aggregate | Concurrent write protection |
| Internal tool version | sanitizer `1.0.0` | its own package | Tool-local dependency lifecycle |

Code must never use product SemVer to negotiate service, API, event, database, or optimistic-lock
compatibility.

## 6. Source of truth and consumers

### 6.1 Root `VERSION`

`VERSION` contains one SemVer value followed by one LF:

```text
0.9.0
```

It must be ASCII, contain no BOM or CR, contain exactly one line, and end with exactly one LF.
Build metadata (`+...`) is intentionally prohibited in this file. Source SHA is recorded separately.

Allowed values are stable releases and prereleases such as `1.0.0-rc.1`. Numeric identifiers may
not have leading zeroes.

### 6.2 Gradle

The root build reads `VERSION` once. The root project and every subproject use that exact value.
The repository drops `-SNAPSHOT` because it does not publish mutable development artifacts to a
Maven repository; source SHA and image digest already distinguish builds.

Every AWS and Azure service Dockerfile that copies root `build.gradle` must also copy root
`VERSION` before invoking Gradle. Candidate/slim Dockerfiles that package an already staged JAR do
not read Gradle configuration and therefore do not copy `VERSION`.

### 6.3 Frontend

`frontend/package.json` and the root package records in `frontend/package-lock.json` mirror
`VERSION`. The frontend is private and no code currently reads `npm_package_version`; alignment is
for build/release metadata and human clarity. `VERSION` remains authoritative, and the validator
rejects drift.

### 6.4 Explicit exclusions

`infrastructure/package.json` and `scripts/package.json` keep their own package versions. They are
tooling, not the product. The validator uses an allowlist of product consumers rather than scanning
and rewriting every `version` field in the repository.

## 7. Bump policy

This policy implements [Semantic Versioning 2.0.0](https://semver.org/). The repository narrows that
standard by prohibiting build metadata in root `VERSION`; source SHA is recorded in its own field.
SemVer requires a declared public API, treats `0.y.z` as initial development, and makes `1.0.0` the
point at which the public API is defined. This design declares the applicable product contract in
§8 rather than treating the version as a purely numeric label.

### 7.1 Before `1.0.0`

The major version remains `0` until the `1.0.0` gate passes.

- **PATCH** (`0.9.0` to `0.9.1`): backward-compatible defect fixes, security hardening,
  performance corrections, dependency corrections, and operational fixes that do not add a
  product capability.
- **MINOR** (`0.9.1` to `0.10.0`): a new user-visible capability, a material milestone, or any
  intentional incompatible product-contract change.
- Every incompatible pre-1.0 change requires a `BREAKING` subsection in the changelog. Before
  1.0, the project makes no general compatibility guarantee.

Documentation-only, evidence-only, test-only, refactoring-only, and governance-only changes do not
change the product version unless they correct a published product/release claim. A redeployment of
unchanged artifacts does not change the product version.

### 7.2 At and after `1.0.0`

- **PATCH:** backward-compatible fixes, security hardening, performance improvements, or compatible
  operational corrections.
- **MINOR:** backward-compatible features, additive optional API/event fields, additive endpoints,
  or new optional configuration with safe defaults.
- **MAJOR:** removal or incompatible change to a supported contract without a documented,
  supported compatibility/migration path.

An irreversible or non-rolling-compatible data/configuration change is MAJOR after 1.0. A schema
change that is backward-compatible and rollback-safe takes the version of the capability or fix it
supports. Pixel-level UI changes are not a compatibility contract; removal or incompatible change
of a documented user workflow is.

When a release contains changes from more than one category, the highest category wins.

## 8. The `1.0.0` compatibility and acceptance boundary

`1.0.0` is cut only when all of the following are true for one commit and one release set:

1. Phase 3 broad Production E2E has run under explicit owner authorization at the agreed desktop
   viewport(s), with all required scenarios collected and unskipped.
2. All Critical and High demo findings are closed. Every remaining finding has a written backlog
   disposition and explicit owner acceptance.
3. Final owner demo acceptance has been performed against the exact candidate artifacts.
4. Required CI is green at the exact candidate commit.
5. The supported 1.0 contract surface is recorded: HTTP contracts, event contracts, persisted-data
   migration/rollback expectations, required configuration, deployment/upgrade expectations, and
   documented user workflows.
6. `VERSION` and the changelog carry `1.0.0`.
7. A release manifest records the frontend source SHA/deployment identity and every backend image
   digest/revision in the accepted set.
8. The final `VERSION=1.0.0` commit is built and its exact artifacts receive final acceptance before
   the stable tag is created. The annotated `v1.0.0` tag then points to that same commit, and the
   GitHub Release names those same accepted artifacts. Tagging performs no rebuild.
9. A prerelease such as `1.0.0-rc.1` is optional. When used, it is a distinct version and artifact
   set; changing it to `1.0.0` requires rebuilding and final acceptance of the stable artifacts.
   Never claim that RC and stable artifacts are byte-identical after a version-changing rebuild.
10. The owner separately authorizes tag creation and GitHub Release publication. Neither action
    deploys anything.

Feature completeness, zero known low-severity defects, mobile-width support, and an arbitrary
coverage percentage are not implicit 1.0 requirements unless the owner later adds them explicitly.

## 9. Tags, historical markers, and releases

Product release tags are exactly:

```text
v<MAJOR>.<MINOR>.<PATCH>
v<MAJOR>.<MINOR>.<PATCH>-<PRERELEASE>
```

Examples: `v0.9.0`, `v1.0.0-rc.1`, and `v1.0.0`.

The historical annotated tag `v1.0-modular-monolith` is retained. It is an architecture marker,
not a product SemVer release. It lacks the required patch component and must never be selected by
release tooling. Validators parse exact SemVer; they do not use `v*` as proof that a tag is a
release. A `v*` workflow trigger may be used only to route candidate tags into the strict validator.

`v0.9.0` is published as a GitHub **pre-release**, clearly labelled “pre-demo-certification.” It
rehearses the release mechanism before `1.0.0`. Repository rules should restrict creation of `v*`
tags to the owner/release role; that settings change is a separate owner action.

## 10. Changelog contract

Root `CHANGELOG.md` follows a small Keep-a-Changelog-style structure. The current entry begins as:

```markdown
## [0.9.0] - Unreleased
```

Before a release tag is created, its entry must use the release date in `YYYY-MM-DD` form. Branch
and pull-request validation accepts `Unreleased`; tag validation does not.

Every release records user-visible changes, relevant security/operational corrections, and a
`BREAKING` subsection when required. Historical change documents remain under `docs/changes/` and
need not be rewritten into the new changelog.

## 11. CI and failure behavior

The validator is stdlib-only and runs in the required, non-skipping `static-guard` job. It checks:

- exact `VERSION` bytes and the approved SemVer subset;
- matching changelog entry;
- frontend `package.json` and lockfile alignment;
- `VERSION` availability in each Dockerfile that evaluates root Gradle configuration; and
- on a tag event, exact equality between the tag and `v<VERSION>` plus a dated changelog entry.

The CI workflow also listens to `v*` pushes so invalid release-looking tags fail visibly. This is a
validation route, not a deployment route. Contract tests verify that deploy workflows remain
`workflow_call`/manual-gate only and are not triggered by tags or GitHub Releases.

Validation fails closed on missing files, invalid encoding, malformed JSON, unknown ref type during
a tag check, version drift, missing changelog entry, or an unmatched tag. Error messages name the
file and expected value without printing secrets.

## 12. Artifact and deployment identity

The product version labels a release set; it does not uniquely identify a process while services
can be deployed independently. Release evidence therefore records:

```text
productVersion + sourceSha + frontend deployment identity + backend image digests/revisions
```

The existing `SERVICE_VERSION` remains unchanged and continues to carry the deployed image tag in
the current infrastructure. Its known drift is handled by its existing backlog item. This SemVer
slice does not rename it, reinterpret it, repair it, or use it as a product version.

No SemVer image aliases are created in this slice. A later proposal may add them only after registry
immutability is proven; even then, deployments remain digest-pinned.

## 13. Release and deployment governance

A release PR, merge, tag, GitHub Release, deployment, and Production acceptance are separate
decisions.

The governed flow is:

1. Release PR changes version/changelog only as required and passes CI.
2. Owner separately approves merge.
3. When release artifacts must be deployed for final acceptance, the owner separately authorizes
   that gated deployment and acceptance run before tagging.
4. Owner separately approves annotated tag and GitHub Release creation for the accepted commit.
5. The tag validator passes and the operator verifies no deploy workflow ran because of the tag.

A tag or Release is not evidence that an artifact is deployed. A deployment is not evidence that
the product release was accepted.

## 14. Alternatives considered

### 14.1 Independent service SemVer

Rejected for now. It adds coordinated compatibility matrices and release overhead without an
external consumer or independent-service release requirement. SHA/digest already identifies each
component. Revisit when a service is published or supported independently.

### 14.2 Tags without a root version file

Rejected. Build metadata would remain inconsistent, local builds would not know the intended
product line, and invalid/historical tags could become accidental release inputs.

### 14.3 Automatic version inference from commit messages

Deferred. Existing commit history was not written as a formal bump protocol, and a governed
release should make the bump decision explicit. Automation can be proposed after the manual
`0.9.0` and `1.0.0` paths have been proven.

### 14.4 Reusing `SERVICE_VERSION`

Rejected. It currently carries image-tag/source identity and has known drift. Reinterpreting it as
product SemVer would turn one ambiguous field into a misleading one.

## 15. Validation matrix

| Case | Expected result |
|---|---|
| `VERSION` is `0.9.0\n` | Pass |
| `VERSION` is `1.0.0-rc.1\n` | Pass |
| CRLF, UTF-8 BOM, no final LF, extra line, non-ASCII | Fail |
| `01.0.0`, `1.00.0`, `1.0`, `1.0.0+sha` | Fail |
| Frontend package or lockfile differs | Fail |
| Gradle-consuming Dockerfile omits `VERSION` | Fail |
| Branch/PR with `0.9.0 - Unreleased` | Pass |
| Tag `v0.9.0` with `0.9.0 - Unreleased` | Fail |
| Tag `v0.9.0` with dated `0.9.0` entry | Pass |
| Tag `v1.0-modular-monolith` | Fail as a product release tag |
| Product tag pushed | Validation CI runs; no deploy workflow runs |

## 16. Rollback

Before any release tag exists, rollback is one source revert: restore the previous Gradle/frontend
values and remove the new validator, policy, changelog, and root `VERSION` dependency.

After `v0.9.0` is published, do not delete or move the tag. Correct the implementation forward with
`0.9.1` or, if the release itself is invalid and unused, publish a clearly documented superseding
pre-release under explicit owner direction. Already deployed artifacts continue to be identified by
their SHA/digest and are unaffected by product-version documentation.

## 17. Implementation boundary

The accompanying implementation plan may implement only the source, build, validation, and
documentation scope described here. It must stop for owner approval before push, PR creation,
merge, tag creation, GitHub Release creation, repository-ruleset changes, Phase 3, deployment, or
Production contact.
