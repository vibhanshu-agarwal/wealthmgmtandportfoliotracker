# Runtime Baseline Standardization — Java 21 LTS & Node.js 24 LTS — 2026-05-31

## Summary

Standardized the two runtime/toolchain baselines that were previously inconsistent across the repository:

1. **Java → 21 LTS** (was Java 25, a non-LTS line) in CI workflows and AWS Dockerfiles.
2. **Node.js → 24 LTS** (was a mix of Node 20 and 22) across CI workflows, the frontend Docker image, and frontend package metadata.

This was an alignment cleanup rather than a feature change. The root Gradle toolchain (`JavaLanguageVersion.of(21)`) and the Azure Mariner Dockerfiles were already on Java 21, so the work was about removing the remaining Java 25 references and unifying the mixed Node versions onto the LTS line. The change set follows the audit at `docs/audit/java-and-node-version-changes.md`.

Shipped in commit `7db87b9` on `main`.

## Java 21 LTS Alignment

### GitHub Actions Workflows

`java-version` changed from `25` to `21` (Temurin), and "Set up JDK 25" step names renamed to "Set up JDK 21":

- `.github/workflows/ci.yml`
- `.github/workflows/ci-verification.yml`
- `.github/workflows/deploy-aws.yml`
- `.github/workflows/frontend-e2e-integration.yml`

### AWS Dockerfiles (All 4 Java Services)

For each AWS Dockerfile:

- `amazoncorretto:25` → `amazoncorretto:21` (builder, jre-builder, and gradle-dist stages)
- `jdeps --multi-release 25` → `--multi-release 21`
- cacerts copy path `/usr/lib/jvm/java-25-amazon-corretto/...` → `/usr/lib/jvm/java-21-amazon-corretto/...`
- Updated descriptive comments referencing the ambient JDK version

Files changed:

- `api-gateway/Dockerfile`
- `portfolio-service/Dockerfile`
- `market-data-service/Dockerfile`
- `insight-service/Dockerfile`

### Documentation & Steering

- `README.md` — Java badge `Java-25` → `Java-21` and prerequisites `Java 25+` → `Java 21+`
- `.kiro/steering/tech.md` — backend language baseline `Java 25` → `Java 21`

### Not Changed (Already Aligned)

- `build.gradle` — Gradle toolchain was already `JavaLanguageVersion.of(21)`
- Azure Dockerfiles (`*/Dockerfile.azure`) — already `mcr.microsoft.com/openjdk/jdk:21-mariner`

## Node.js 24 LTS Alignment

### GitHub Actions Workflows

All Node setups unified to `node-version: 24` (previously a mix of 20 and 22):

- `.github/workflows/ci-verification.yml` (was 22)
- `.github/workflows/deploy-aws.yml` (was 20)
- `.github/workflows/deploy-azure.yml` (was 20 and 22)
- `.github/workflows/frontend-ci.yml` (was 20)
- `.github/workflows/frontend-e2e-integration.yml` (was 20)
- `.github/workflows/synthetic-monitoring.yml` (was 20)

### Frontend Docker Image

- `frontend/Dockerfile` — `node:20-alpine` → `node:24-alpine` (both `deps` and `builder` stages)

### Frontend Package Metadata

- `frontend/package.json` — `@types/node` `^20` → `^24`; added `engines.node: ">=24"`
- `frontend/.nvmrc` — created with `24` so local dev matches CI
- `frontend/package-lock.json` — regenerated; only `@types/node` and its transitive `undici-types` changed
- `frontend/pacts/` — regenerated after `npm install`

## Validation

The change focused on the Node.js 24 baseline (the broader-risk surface). Validated locally on Node `v26.1.0` / npm `11.13.0` (satisfies `>=24`):

| Check                | Command                | Result                                    |
| -------------------- | ---------------------- | ----------------------------------------- |
| Dependency install   | `npm ci` / `npm install` | Pass — only `@types/node` + `undici-types` changed in lockfile |
| Lint                 | `npm run lint`         | Pass — 0 errors, 4 pre-existing warnings  |
| Unit tests           | `npm test`             | Pass — 88/88 across 14 test files         |
| Pact consumer tests  | `npm run test:pact`    | Pass — 2/2 contracts generated            |
| Production build     | `npm run build`        | Pass — Next.js 16.2.3 static export       |

No issues were attributable to the Node 24 baseline change. The Recharts `width(0)/height(0)` stderr lines during unit tests are pre-existing jsdom rendering noise, not failures.

## Notes

- During the push, a rebase against `origin/main` surfaced a half-completed upstream edit in `ci.yml` and `frontend-e2e-integration.yml`: the JDK step names had been renamed to "Set up JDK 21" while `java-version` still read `"25"`. The conflict resolution took the corrected values (`java-version: '21'`), completing that alignment.
- `engines.node` was set to `>=24` (minimum baseline) rather than the audit's suggested `>=24 <25`, to document the floor without artificially blocking newer Node majors that have not been shown to break.
- Historical records in `docs/specs/`, `docs/changes/`, and the source audit doc still reference Java 25 by design — they describe prior state and were intentionally left untouched.

## Open Follow-ups (from audit, not yet actioned)

- Decide whether to strictly enforce Node 24 via a tighter `engines.node` range.
- Decide whether disabled/manual-only workflows warrant the same treatment for full consistency.
- Validate Corretto 21 custom-JRE generation (`jlink`/`jdeps`) and `cacerts` copy by building all four AWS Dockerfiles in a real build environment.
- Validate Pact 16.3.0 and Playwright 1.54.2 on Node 24 in GitHub Actions runners.
