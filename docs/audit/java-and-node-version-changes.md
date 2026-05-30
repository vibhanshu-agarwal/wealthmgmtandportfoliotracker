# Java and Node.js Version Changes Audit

## Executive Summary

This audit captures the impact of two deferred runtime/toolchain baseline changes:

1. Standardize Java on **Java 21 LTS** instead of Java 25.
2. Standardize Node.js on **Node.js 24 LTS** instead of the current mixed Node 20/22 usage.

The Java 21 change is mostly an alignment cleanup: the root Gradle toolchain and Azure Dockerfiles already target Java 21, while several GitHub Actions workflows and AWS Dockerfiles still reference Java 25. The main Java risk is the AWS Dockerfile `jlink`/`jdeps` path, because those files currently use Corretto 25-specific image tags, `--multi-release 25`, and Java 25 `cacerts` paths.

The Node.js 24 change is broader across the frontend build/test/deploy surface. It should be feasible, but it needs validation across Next.js, Playwright, Pact, Vitest, `npm ci`, and the frontend Docker image. Node 24 is preferable to Node 25 because Node 24 is the LTS line.

This work is intentionally deferred because it cuts across CI, Docker, deploy workflows, and documentation.

## Scope Reviewed

Reviewed areas included:

- Root Gradle configuration: `build.gradle`
- GitHub Actions workflows under `.github/workflows/`
- Java service Dockerfiles:
  - `api-gateway/Dockerfile`
  - `portfolio-service/Dockerfile`
  - `market-data-service/Dockerfile`
  - `insight-service/Dockerfile`
  - Azure variants: `*/Dockerfile.azure`
- Frontend Dockerfile: `frontend/Dockerfile`
- Frontend package metadata: `frontend/package.json`
- Infrastructure package metadata: `infrastructure/package.json`
- Existing Azure Container Apps spec notes under `.kiro/specs/azure-container-apps-deployment/`

## Current State

### Java

The repository is partially Java 21-aligned already.

| Area | Current observed baseline | Notes |
|---|---:|---|
| Root Gradle toolchain | Java 21 | `build.gradle` uses `JavaLanguageVersion.of(21)`. |
| Azure Dockerfiles | Java 21 | Azure service Dockerfiles use `mcr.microsoft.com/openjdk/jdk:21-mariner`. |
| CI workflows | Java 25 in several places | GitHub Actions still install Temurin 25 in Java jobs. |
| AWS Dockerfiles | Java 25 | AWS service Dockerfiles still use `amazoncorretto:25`, `--multi-release 25`, and Java 25 `cacerts` paths. |
| README badge | Java 25 | Documentation still advertises Java 25. |

### Node.js

The repository currently has mixed Node versions.

| Area | Current observed baseline | Notes |
|---|---:|---|
| Frontend CI workflow | Node 20 | `frontend-ci.yml` uses Node 20. |
| Deploy workflows | Node 20/22 | Frontend deploy uses Node 20; Azure seed uses Node 22. |
| Main CI verification | Node 22 | `ci-verification.yml` uses Node 22 for Pact and Playwright sections. |
| Synthetic monitoring | Node 20 | `synthetic-monitoring.yml` uses Node 20. |
| Frontend Dockerfile | Node 20 | `frontend/Dockerfile` uses `node:20-alpine`. |
| Frontend typings | Node 20 | `frontend/package.json` uses `@types/node: ^20`. |
| Infrastructure package | Node 24 typings | `infrastructure/package.json` already uses `@types/node: ^24.10.1`. |

## Finding 1: Java 21 is the correct cloud-compatible baseline

### Detail

Java 25 is a non-LTS, newer runtime that has already caused compatibility concerns in both AWS and Azure paths. Java 21 is the current stable LTS baseline and is already assumed by major parts of the repository:

- `build.gradle` toolchain is already Java 21.
- Azure Dockerfiles are already Java 21 Mariner-based.
- Existing Azure deployment spec notes explicitly document the Java 25 → Java 21 alignment rationale.

### Impact of standardizing on Java 21

Positive impact:

- Aligns local/CI Gradle toolchain with cloud runtime targets.
- Reduces risk from Java 25 runtime incompatibilities on AWS/Azure.
- Avoids Gradle toolchain auto-resolution mismatch where CI installs Java 25 while Gradle asks for Java 21.
- Aligns Azure Container Apps builds with Microsoft OpenJDK 21 Mariner images.
- Enables a clearer future path to AWS Lambda Java 21 managed runtime evaluation, although that is a separate architectural migration.

Risk:

- AWS service Dockerfiles currently use Corretto 25 throughout their builder and custom JRE stages.
- The `jdeps` command uses `--multi-release 25`.
- The runtime copies `cacerts` from `/usr/lib/jvm/java-25-amazon-corretto/...`.
- Changing these requires building and booting all four AWS service containers, because `jlink`/`jdeps` custom runtime generation is sensitive to JDK image layout and module detection.

### Recommended Java 21 change set

Update GitHub Actions Java setup from 25 to 21 in:

- `.github/workflows/ci-verification.yml`
- `.github/workflows/ci.yml`
- `.github/workflows/deploy-aws.yml`
- `.github/workflows/frontend-e2e-integration.yml`

Update AWS Dockerfiles from Java 25 to Java 21 in:

- `api-gateway/Dockerfile`
- `portfolio-service/Dockerfile`
- `market-data-service/Dockerfile`
- `insight-service/Dockerfile`

For each AWS Dockerfile:

- `amazoncorretto:25` → `amazoncorretto:21`
- `--multi-release 25` → `--multi-release 21`
- `/usr/lib/jvm/java-25-amazon-corretto/lib/security/cacerts` → `/usr/lib/jvm/java-21-amazon-corretto/lib/security/cacerts`
- Update comments that describe the ambient JDK version.

Documentation updates:

- `README.md` Java badge should change from Java 25 to Java 21.
- Any non-historical spec or operational documentation that says Java 25 is the baseline should be updated or annotated.
- Historical changelogs do not need retroactive edits unless they are used as active runbooks.

### Java validation plan

Minimum validation before merge:

- Run `./gradlew clean test --no-daemon`.
- Run `./gradlew check --no-daemon` if integration tests are expected to be part of the verification pass.
- Build all service Docker images through `docker compose build`.
- Build all Azure Dockerfiles directly or through the Azure deploy workflow path.
- Build all AWS Dockerfiles directly or through the AWS deploy workflow path.
- Boot the local Compose stack and verify all four Java services become healthy.

Additional validation if time allows:

- Run Pact provider verification.
- Run local Playwright E2E tests against Docker Compose.
- Run a non-production AWS Lambda container smoke test if an AWS validation environment is available.
- Run a non-production Azure Container Apps deploy smoke if an Azure validation environment is available.

## Finding 2: Node.js 24 LTS is the right baseline, but the change has broader frontend risk

### Detail

Node.js 20 is deprecated/EOL for this project’s forward-looking baseline, and Node.js 22/20 mixed usage makes CI and deployment behavior less deterministic. Node.js 24 is the preferred target because it is the LTS line. Node.js 25 should not be used as the baseline because it is non-LTS and short-lived.

The current frontend stack is modern enough that Node 24 should be feasible:

- Next.js 16.2.3
- React 19.2.4
- Playwright 1.54.2
- Vitest 3.2.4
- TypeScript 5.x
- Pact 16.3.0

However, the Node ecosystem has more native/toolchain-sensitive packages than the Java service runtime path, so validation is important.

### Impact of standardizing on Node.js 24

Positive impact:

- Replaces Node 20 with an active LTS baseline.
- Removes mixed Node 20/22 behavior across workflows.
- Aligns frontend and infrastructure TypeScript tooling more closely.
- Makes CI, deploy, synthetic monitoring, and Docker frontend builds use the same Node major.
- Reduces surprises from using different npm versions across jobs.

Risk:

- Node 24 ships with a newer npm than Node 20/22; `npm ci` should work, but `npm install` may rewrite lockfile metadata.
- Next.js/SWC native binaries must be validated on Node 24.
- Pact may depend on native binaries or platform-specific packaging that should be tested on Node 24.
- Playwright browser installation and test execution should be verified under Node 24.
- `frontend/Dockerfile` currently uses `node:20-alpine`; moving to `node:24-alpine` changes the Alpine/runtime build environment for frontend static export builds.
- `frontend/package.json` still uses `@types/node: ^20`; TypeScript should be aligned to Node 24 types if Node 24 is the baseline.

### Recommended Node 24 change set

Update GitHub Actions Node setup to 24 in:

- `.github/workflows/ci-verification.yml`
- `.github/workflows/deploy-aws.yml`
- `.github/workflows/deploy-azure.yml`
- `.github/workflows/frontend-ci.yml`
- `.github/workflows/frontend-e2e-integration.yml`
- `.github/workflows/synthetic-monitoring.yml`

Update Docker:

- `frontend/Dockerfile`: `node:20-alpine` → `node:24-alpine`

Update frontend package metadata:

- `frontend/package.json`: `@types/node: ^20` → `^24`
- Consider adding `engines.node` to document the supported baseline, for example `>=24 <25` or `^24` depending on the desired strictness.
- Consider adding `.nvmrc` and/or `.node-version` with `24` so local development matches CI.

Review `package-lock.json` after updating `@types/node` and running `npm install` with Node 24/npm 11. The lockfile should be committed only if dependency metadata changes are intentional and reproducible.

### Node validation plan

Minimum validation before merge:

From `frontend/`:

- Run `npm ci`.
- Run `npm run lint`.
- Run `npm test`.
- Run `npm run test:pact`.
- Run `npm run build`.
- Run `npx playwright test --list`.
- Run `npx playwright test --project=chromium --reporter=list` where the local Docker stack is available.
- Run `npx playwright test --project=azure-synthetic --list` to ensure project discovery works.

Docker validation:

- Build `frontend/Dockerfile` with Node 24.
- If feasible, serve the resulting static export container and smoke test the root page.

From `infrastructure/`:

- Run `npm ci`.
- Run `npm run build`.
- Run `npm test` if tests are configured for that package.

CI/deploy validation:

- Validate `ci-verification.yml` on a PR branch.
- Validate `frontend-ci.yml` if it remains active.
- Validate `synthetic-monitoring.yml` manually after the Node change.
- Validate `deploy-azure.yml` frontend build and seed jobs.
- Validate `deploy-aws.yml` frontend build if AWS remains callable.

## Finding 3: Do not combine runtime baseline cleanup with unrelated feature fixes unless necessary

### Detail

The Java/Node changes cut across CI, deploy, Docker images, frontend dependencies, and documentation. They are broader than the Azure demo readiness Phase 2 work.

Combining these changes with synthetic monitoring/workflow cleanup makes failures harder to triage. For example, a failing Playwright job could be caused by:

- a Node 24 toolchain issue,
- a synthetic test logic issue,
- a live Azure environment issue,
- a deploy workflow environment variable issue,
- or a browser dependency issue in the GitHub Actions runner.

### Recommendation

Address the version baseline work in a separate PR or at least separate commits:

1. Java 21 alignment commit:
   - GitHub Actions Java setup.
   - AWS Dockerfile Java 21 conversion.
   - README/doc updates.
   - Java/Docker validation.

2. Node 24 alignment commit:
   - GitHub Actions Node setup.
   - `frontend/Dockerfile`.
   - frontend `@types/node` and optional engines/local version files.
   - Node/frontend validation.

This keeps Azure demo readiness fixes easier to review and reduces rollback complexity.

## Recommended Target Baseline

| Component | Recommended baseline |
|---|---:|
| Java language/runtime | Java 21 LTS |
| Gradle Java toolchain | Java 21 |
| GitHub Actions Java | Temurin 21 |
| Azure backend Dockerfiles | Microsoft OpenJDK 21 Mariner |
| AWS backend Dockerfiles | Amazon Corretto 21 |
| Node.js | Node.js 24 LTS |
| GitHub Actions Node | Node 24 |
| Frontend Dockerfile | `node:24-alpine` |
| Frontend Node typings | `@types/node: ^24` |

## Open Follow-up Items

- Decide whether to strictly enforce Node 24 via `engines.node` or document it only via `.nvmrc` / `.node-version`.
- Decide whether disabled/manual-only workflows should also be updated for Java 21 and Node 24 for consistency.
- Decide whether to update historical `.kiro` spec references to Java 25 or leave them as historical context.
- Validate whether all four AWS Dockerfiles work cleanly with Corretto 21, especially the custom JRE generation and `cacerts` copy path.
- Validate whether Pact 16.3.0 and Playwright 1.54.2 behave cleanly on Node 24 in GitHub Actions.

## Conclusion

Standardizing on Java 21 and Node.js 24 LTS is the right direction.

Java 21 has low functional risk because the Gradle toolchain and Azure Dockerfiles are already aligned to Java 21. The main work is removing the remaining Java 25 references from CI and AWS Dockerfiles.

Node.js 24 has moderate validation risk because it affects the frontend toolchain, package metadata, Docker build image, synthetic monitoring, and Pact/Playwright execution. The change is still advisable, but it should be done as a dedicated runtime baseline cleanup rather than mixed into unrelated feature work.
