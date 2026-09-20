# Product Semantic Versioning Policy

**Status:** Active operational policy.
**Source design:** `docs/superpowers/specs/2026-09-20-product-semantic-versioning-design.md`.

This repository uses **one product Semantic Version** for the integrated application. The frontend,
API gateway, portfolio service, market-data service, and insight service are parts of a single
release set and do not carry independent SemVer lines.

Product SemVer answers "which product contract and capability set is this?" It does **not** identify
artifacts. Commit SHA, container image digest, frontend deployment identity, and serving revision
remain the evidence for "which exact bytes are running?"

The current product version is the content of root `VERSION`. `1.0.0` is reserved for the accepted
demo-ready release set and is gated by section 4 below.

---

## 1. Version domains

These domains are intentionally separate. Code must never use product SemVer to negotiate service,
API, event, database, or optimistic-lock compatibility.

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

`SERVICE_VERSION` is **not** a product version. It carries the deployed image tag in the current
infrastructure and has its own backlog item for its known drift. Do not rename, reinterpret, repair,
or read it as product SemVer.

## 2. Source of truth and consumers

### 2.1 Root `VERSION`

`VERSION` contains one SemVer value followed by one LF:

```text
0.9.0
```

It must be ASCII, contain no BOM and no CR, contain exactly one line, and end with exactly one LF.
`.gitattributes` pins `/VERSION text eol=lf` so a Windows checkout cannot introduce CRLF.

Build metadata (`+...`) is prohibited in this file; source SHA is recorded separately. Allowed values
are stable releases and prereleases such as `1.0.0-rc.1`. Numeric identifiers may not carry leading
zeroes.

### 2.2 Gradle

The root build reads `VERSION` once, and the root project and every subproject use that exact value.
The repository does not use `-SNAPSHOT`: it publishes no mutable development artifacts to a Maven
repository, and source SHA plus image digest already distinguish builds.

Because subprojects now inherit the product version, conventional archive names are versioned (for
example `common-dto-0.9.0.jar`). API Gateway remains explicitly pinned to `app.jar`; portfolio,
market-data, and insight services use Gradle's versioned default `bootJar` name, and their Docker
builder stages invoke only that service's `bootJar` before copying the single resulting `*.jar` to
runtime `app.jar`. Candidate and slim staging consume Gradle's `archiveFile` provider and rename the
staged file to a fixed name, so they are unaffected by version changes.

Every AWS and Azure service Dockerfile that copies root `build.gradle` must also copy root `VERSION`
before invoking Gradle, or the build fails while Gradle configures. `Dockerfile.candidate` and
`Dockerfile.slim-it` package an already staged JAR, do not evaluate root Gradle configuration, and
therefore do not copy `VERSION`.

### 2.3 Frontend

`frontend/package.json` and the root package records in `frontend/package-lock.json` mirror
`VERSION`. The frontend is private and no code reads `npm_package_version`; the alignment exists for
build and release metadata and for human clarity. `VERSION` remains authoritative, and the validator
rejects drift.

Update the mirror with:

```powershell
Set-Location frontend
npm version <version> --no-git-tag-version --allow-same-version
Set-Location ..
```

### 2.4 Explicit exclusions

`infrastructure/package.json` and `scripts/package.json` keep their own package versions. They are
tooling, not the product. The validator uses an allowlist of product consumers rather than scanning
and rewriting every `version` field in the repository.

## 3. Bump policy

This policy implements [Semantic Versioning 2.0.0](https://semver.org/), narrowed to prohibit build
metadata in root `VERSION`. The applicable product contract surface is declared in section 4, item 8.

### 3.1 Before `1.0.0`

The major version stays `0` until the `1.0.0` gate passes.

| Category | Example | Applies to |
|---|---|---|
| PATCH | `0.9.0` to `0.9.1` | Backward-compatible defect fixes, security hardening, performance corrections, dependency corrections, and operational fixes that add no product capability |
| MINOR | `0.9.1` to `0.10.0` | A new user-visible capability, a material milestone, or any intentional incompatible product-contract change |

Every incompatible pre-1.0 change requires a `BREAKING` subsection in the changelog. Before 1.0 the
project makes no general compatibility guarantee.

### 3.2 At and after `1.0.0`

| Category | Applies to |
|---|---|
| PATCH | Backward-compatible fixes, security hardening, performance improvements, compatible operational corrections |
| MINOR | Backward-compatible features, additive optional API/event fields, additive endpoints, new optional configuration with safe defaults |
| MAJOR | Removal of, or incompatible change to, a supported contract without a documented, supported compatibility/migration path |

An irreversible or non-rolling-compatible data/configuration change is MAJOR after 1.0. A schema
change that is backward-compatible and rollback-safe takes the version of the capability or fix it
supports. Pixel-level UI changes are not a compatibility contract; removal or incompatible change of
a documented user workflow is.

### 3.3 Changes that do not bump

Documentation-only, evidence-only, test-only, refactoring-only, and governance-only changes do not
change the product version unless they correct a published product or release claim. Redeploying
unchanged artifacts does not change the product version.

When a release contains changes from more than one category, the highest category wins.

## 4. The `1.0.0` compatibility and acceptance boundary

`1.0.0` is cut only when **all** of the following hold for one final stable commit and one release
set.

1. Phase 3 broad Production E2E has run under explicit owner authorization at the agreed desktop
   viewport(s), with all required scenarios collected and unskipped, against a functionally complete
   `0.y.z` candidate.
2. All Critical and High demo findings are closed. Every remaining finding has a written backlog
   disposition and explicit owner acceptance before the stable-version transition.
3. The stable-version transition changes exactly `VERSION`, `CHANGELOG.md`, `frontend/package.json`,
   and `frontend/package-lock.json`: the product version becomes `1.0.0`, the changelog entry is
   dated, and the frontend mirror is updated. No application, infrastructure, dependency, workflow,
   or other behavioral file may change in that transition.
4. A machine-checked diff proves that exact four-file delta. If any other file changes, the Phase 3
   evidence cannot be carried forward; the candidate returns to remediation and broad E2E.
5. Phase 3 scenario evidence and finding dispositions may be carried forward across that proven
   metadata-only transition. Required CI, the version contract, all eight Docker builds, artifact
   provenance/digest capture, and the final desktop acceptance smoke must rerun against the exact
   `VERSION=1.0.0` commit and its rebuilt artifacts.
6. Final owner demo acceptance has been performed against those exact stable artifacts. A failure in
   any rerun invalidates the carry-forward and returns the release to remediation.
7. Required CI is green at the exact stable commit.
8. The supported 1.0 contract surface is recorded: HTTP contracts, event contracts, persisted-data
   migration/rollback expectations, required configuration, deployment/upgrade expectations, and
   documented user workflows.
9. `VERSION` and the changelog carry `1.0.0`.
10. A release manifest records the frontend source SHA/deployment identity and every backend image
    digest/revision in the accepted set.
11. The final `VERSION=1.0.0` commit is built and its exact artifacts receive final acceptance before
    the stable tag is created. The annotated `v1.0.0` tag then points to that same commit, and the
    GitHub Release names those same accepted artifacts. Tagging performs no rebuild.
12. A prerelease such as `1.0.0-rc.1` is optional. When used it is a distinct version and artifact
    set; changing it to `1.0.0` requires rebuilding and final acceptance of the stable artifacts.
    Never claim RC and stable artifacts are byte-identical after a version-changing rebuild.
13. The owner separately authorizes tag creation and GitHub Release publication. Neither action
    deploys anything.

Feature completeness, zero known low-severity defects, mobile-width support, and an arbitrary
coverage percentage are **not** implicit 1.0 requirements unless the owner later adds them
explicitly.

## 5. Tags, historical markers, and releases

Product release tags are exactly:

```text
v<MAJOR>.<MINOR>.<PATCH>
v<MAJOR>.<MINOR>.<PATCH>-<PRERELEASE>
```

Examples: `v0.9.0`, `v1.0.0-rc.1`, `v1.0.0`.

The historical annotated tag `v1.0-modular-monolith` is retained. It is an architecture marker, not
a product SemVer release. It lacks the required patch component and must never be selected by
release tooling. Validators parse exact SemVer; `v*` is never proof that a tag is a release. A `v*`
workflow trigger may be used only to route candidate tags into the strict validator.

`v0.9.0` is published as a GitHub **pre-release**, labelled "pre-demo-certification". It rehearses
the release mechanism before `1.0.0`.

Tag validation is **detective, not preventive** — unconditionally, and not merely until some later
control lands. An invalid tag already exists by the time the tag workflow rejects it, and published
tags are never deleted or moved.

Prevention comes only from a repository ruleset restricting creation of `v*` tags to the
owner/release role. That settings change is a separate owner action. Until that ruleset is active,
release evidence must state that invalid tag creation remained possible. Green tag validation is
never proof of prevention.

## 6. Changelog contract

Root `CHANGELOG.md` follows a small Keep-a-Changelog-style structure. A version entry begins as:

```markdown
## [0.9.0] - Unreleased
```

Before a release tag is created, its entry must use the release date in `YYYY-MM-DD` form. Branch and
pull-request validation accepts `Unreleased`; tag validation does not.

Every release records user-visible changes, relevant security/operational corrections, and a
`BREAKING` subsection when required. Historical change documents remain under `docs/changes/` and are
not rewritten into this changelog.

## 7. Validation and failure behavior

`scripts/validate_product_version.py` is stdlib-only and runs in the required, non-skipping
`static-guard` job. It checks:

- exact `VERSION` bytes and the approved SemVer subset;
- a matching changelog entry;
- `frontend/package.json` and lockfile alignment;
- `VERSION` availability in each Dockerfile that evaluates root Gradle configuration; and
- on a tag event, exact equality between the tag and `v<VERSION>` plus a dated changelog entry.

Every CLI call supplies `--ref-type branch` or `--ref-type tag` explicitly. There is no
environment-derived default. Tag CI maps the GitHub ref name to a step-local `RELEASE_TAG` and
supplies `--ref-name "$RELEASE_TAG"`.

Empty or unknown ref type, a missing tag name in tag mode, missing files, invalid encoding,
malformed JSON, version drift, a missing changelog entry, or an unmatched tag all fail closed. Error
messages name the file and expected value and never print secrets.

The required branch/PR pipeline does not listen to tags.
`.github/workflows/release-tag-validation.yml` listens to `v*` pushes and runs only the validator and
its focused tests with read-only contents permission, so release operators get a tag-specific signal
without routing a tag through unrelated integration, Pact, Docker Compose, Playwright, or publish
jobs.

Any `skipped` line in a validation suite means that portion is **UNRUN**, not passing. Record it as
missing evidence regardless of why or where the skip occurred.

## 8. Artifact and deployment identity

The product version labels a release set; it does not uniquely identify a process while services can
be deployed independently. Release evidence therefore records:

```text
productVersion + sourceSha + frontend deployment identity + backend image digests/revisions
```

For `1.0.0` and later, that evidence is a JSON GitHub Release asset named
`wealthmgmtandportfoliotracker-v<version>-release-manifest.json`. It is generated after the exact
stable artifacts are built and accepted, attached without changing the tagged commit, and its SHA-256
is written into the GitHub Release notes. It is **not** committed into the tagged source tree, which
would create a manifest-causes-rebuild cycle. Schema version 1 is:

```json
{
  "schemaVersion": 1,
  "productVersion": "1.0.0",
  "releaseTag": "v1.0.0",
  "sourceSha": "<40 lowercase hex>",
  "frontend": {
    "sourceSha": "<same 40 lowercase hex>",
    "workflowRunId": 1234567890,
    "runAttempt": 1,
    "buildId": "<provider build/deployment identifier>"
  },
  "services": {
    "api-gateway": {"image": "<registry/repository>@sha256:<64 hex>", "revision": "<serving revision>"},
    "portfolio-service": {"image": "<registry/repository>@sha256:<64 hex>", "revision": "<serving revision>"},
    "market-data-service": {"image": "<registry/repository>@sha256:<64 hex>", "revision": "<serving revision>"},
    "insight-service": {"image": "<registry/repository>@sha256:<64 hex>", "revision": "<serving revision>"}
  },
  "marketDataRefreshJob": {"image": "<registry/repository>@sha256:<64 hex>", "revision": "<serving revision>"},
  "marketDataRepairJob": {"image": "<registry/repository>@sha256:<64 hex>", "revision": "<serving revision>"}
}
```

Every named component is required. `productVersion` must equal `VERSION`, `releaseTag` must equal
`v<productVersion>`, every source SHA must equal the tagged commit, and every image reference must be
digest-pinned. `workflowRunId` and `runAttempt` are positive JSON integers. Both
`marketDataRefreshJob.image` and `marketDataRepairJob.image` must equal
`services["market-data-service"].image`, because both Jobs reuse that service image; the two entries
record separate **serving identities**, not fifth and sixth image builds.

The source-only `v0.9.0` pre-demo rehearsal has no accepted deployed release set and therefore
publishes no manifest. Its Release notes state that explicitly.

No SemVer image aliases are created. A later proposal may add them only after registry immutability
is proven, and even then deployments remain digest-pinned.

## 9. Release and deployment governance

**A tag or Release is not evidence that an artifact is deployed. A deployment is not evidence that
the product release was accepted.**

No tag and no GitHub Release may trigger deployment. Deployment happens only through the existing
gated dispatch path, under its own owner approval.

A release PR, merge, tag, GitHub Release, deployment, and Production acceptance are **separate owner
decisions**. The governed flow is:

1. A stable release PR changes only the permitted version-metadata set defined in section 4, item 3,
   and passes CI.
2. The owner separately approves the merge.
3. When release artifacts must be deployed for final acceptance, the owner separately authorizes that
   gated deployment and acceptance run before tagging.
4. The owner separately approves annotated tag and GitHub Release creation for the accepted commit.
5. The tag validator passes, and the operator verifies that no deploy workflow ran because of the tag.
