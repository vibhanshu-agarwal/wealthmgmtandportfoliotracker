# Test Dependency Migration Audit — WireMock 4 & Pact (spring7)

**Date:** 2026-06-14
**Status:** IMPLEMENTED — PR [#73](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/73) (`feat/test-dependency-migration`). Versions pinned in root `build.gradle` `ext` (`wiremockVersion`, `wiremockSpringBootVersion`, `pactSpring7Version`).
**Scope:** Test-only dependency upgrades (`testImplementation`) needed for alignment with the Spring Boot 4.x line.
**Verification:** Findings were empirically validated by applying the changes, resolving the dependency tree, compiling test sources, and running the affected tests (`BUILD SUCCESSFUL` on branch, including `--rerun-tasks`).

---

## TL;DR — what needs to change

| Module | Dependency | From | To | Code changes? |
|---|---|---|---|---|
| `market-data-service` | WireMock | `org.wiremock:wiremock-standalone:3.12.1` | **modular set** (see §2) | None |
| `portfolio-service` | `org.wiremock.integrations:wiremock-spring-boot` | `3.2.0` | `4.2.1` | None |
| `portfolio-service` | `au.com.dius.pact.provider:spring7` | `4.7.0-beta.4` | `4.7.1` | None |
| `insight-service` | `au.com.dius.pact.provider:spring7` | `4.7.0-beta.4` | `4.7.1` | None |

> Note: `common-dto` has **no** pact or wiremock dependency — earlier migration notes that referenced `common-dto` for pact were a misattribution. The pact dependency lives only in `portfolio-service` and `insight-service`.

> **WireMock 4 beta:** `wiremockVersion` is pinned to `4.0.0-beta.36` (test scope only). This is acceptable for `testImplementation`, but revisit and drop the `-beta` qualifier when WireMock 4 reaches GA.

---

## 1. Pact `spring7` — `4.7.0-beta.4` → `4.7.1`

**Affected files**
- `portfolio-service/build.gradle`
- `insight-service/build.gradle`

**Change**
```groovy
testImplementation 'au.com.dius.pact.provider:spring7:4.7.1'
```

**Assessment:** Drop-in. The provider API used by the tests is unchanged:
- `au.com.dius.pact.provider.spring.spring7.Spring7MockMvcTestTarget`
- `au.com.dius.pact.provider.spring.spring7.PactVerificationSpring7Provider`
- `au.com.dius.pact.provider.junit5.PactVerificationContext`
- `au.com.dius.pact.provider.junitsupport.{Provider,State,loader.PactFolder}`

**Consumers (no edits needed)**
- `portfolio-service/src/test/java/com/wealth/portfolio/pact/PortfolioPactVerificationTest.java`
- `insight-service/src/test/java/com/wealth/insight/pact/InsightPactVerificationTest.java`

**Verified:** Both pact verification tests compile and pass on `4.7.1`.

---

## 2. `market-data-service` WireMock — modular migration (NOT a simple version bump)

WireMock 4.x is the correct line for the Spring Boot 4.x stack, but you **cannot** simply bump `wiremock-standalone` to `4.0.0-beta.36`.

**Why a straight bump fails:** WireMock 4 refactored the core to break out external libraries (incl. the HTTP client). The `wiremock-standalone` uber-jar, when used as a *library* (`testImplementation`), does not expose a discoverable `HttpClientFactory`. `new WireMockServer(...)` then dies at startup:

```
com.github.tomakehurst.wiremock.common.FatalStartupException:
  No suitable HttpClientFactory was found. Please ensure that the classpath
  includes a WireMock extension that provides an HttpClientFactory implementation.
```

This was reproduced: with `wiremock-standalone:4.0.0-beta.36` all 7 WireMock tests in `market-data-service` failed at startup.

**Fix — replace the standalone jar with the modular test artifacts** (per the WireMock v4 install docs):

```groovy
// remove:
// testImplementation 'org.wiremock:wiremock-standalone:3.12.1'

// add:
testImplementation 'org.wiremock:wiremock-core:4.0.0-beta.36'
testImplementation 'org.wiremock:wiremock-jetty:4.0.0-beta.36'
testImplementation 'org.wiremock:wiremock-httpclient-apache5:4.0.0-beta.36'
```

- `wiremock-httpclient-apache5` is the module that provides the `HttpClientFactory` (resolves the startup error).
- `wiremock-junit5` is **not** required here — the tests use plain `new WireMockServer(...)`, not the Jupiter `@WireMockTest` extension.
- **Beta pin:** `4.0.0-beta.36` is intentional for test scope; bump `wiremockVersion` in root `ext` when WireMock 4 GA is published.

**Consumers (no edits needed — `com.github.tomakehurst.wiremock.*` package is preserved in v4)**
- `market-data-service/src/test/java/com/wealth/market/ExternalMarketDataClientWireMockTest.java`
- `market-data-service/src/test/java/com/wealth/market/MarketDataRefreshJobWireMockTest.java`

**Verified:** After the modular swap, both test classes pass — `ExternalMarketDataClientWireMockTest` (3 tests) and `MarketDataRefreshJobWireMockTest` (4 tests), 0 failures / 0 errors.

---

## 3. `portfolio-service` `wiremock-spring-boot` — `3.2.0` → `4.2.1`

**Affected file:** `portfolio-service/build.gradle`

**Important:** Use `4.2.1`, **not** `4.2`. The bare `4.2` coordinate is not published — Gradle fails with `Could not find org.wiremock.integrations:wiremock-spring-boot:4.2`. The latest 4.x release is `4.2.1`.

**Change**
```groovy
testImplementation 'org.wiremock.integrations:wiremock-spring-boot:4.2.1'
```

**Assessment:** `wiremock-spring-boot:4.2.1` transitively brings a working HTTP client, so (unlike the standalone case in §2) no extra WireMock modules are needed. The portfolio tests use the raw `com.github.tomakehurst.wiremock.WireMockServer` API (not the `@EnableWireMock` annotations), which is unchanged.

**Consumers (no edits needed)**
- `portfolio-service/src/test/java/com/wealth/portfolio/fx/EcbFxRateProviderIntegrationTest.java` (`@Tag("integration")` — runs under the `integrationTest` task)
- `portfolio-service/src/test/java/com/wealth/portfolio/PortfolioSummaryAfterSeedIT.java` (Testcontainers-based)

**Verified:** `portfolio-service:integrationTest` passes for the WireMock-based `EcbFxRateProviderIntegrationTest` and the pact test.

---

## Breaking-change checks that came back clean

- **Jackson:** the root `build.gradle` `resolutionStrategy` force of `com.fasterxml.jackson.core:{jackson-core,jackson-databind}` to `2.18.2` does **not** break WireMock 4 or pact 4.7.1 at runtime — all affected tests pass.
- **Package paths:** `com.github.tomakehurst.wiremock.*` is preserved in WireMock 4 — no import changes anywhere.
- **WireMock 4 watch-outs (not currently triggered, but verify if tests are added/changed):**
  - Core data classes (`StubMapping`, `ResponseDefinition`, `RequestPattern`, `Metadata`, `Parameters`, `GlobalSettings`) are now **immutable** and use builders/`transform(...)`. Current tests use the fluent stubbing DSL, so they are unaffected.
  - Jetty 12.1: multiple `Content-Type` headers are now returned (v3 kept only the last), and `Content-Type` values are normalised to lowercase (e.g. `application/json;charset=utf-8`). Current tests assert JSON bodies, not headers — unaffected. Keep stubs to a single `Content-Type` header.
  - v4 removed several transitive Jetty deps (`jetty-webapp`, `jetty-client`, `jetty-proxy`, etc.). Not used by our tests.

---

## Out of scope (decided in this audit, recorded for context)

- **JUnit 6 / parallel test execution — PARKED.** Project stays on JUnit 5 (`junit-bom:5.12.2`, pinned in `common-dto`). The project was originally on JUnit 6 but an earlier agent reverted to JUnit 5, almost certainly because `net.jqwik:jqwik:1.9.2` targets the JUnit 5 Platform, not JUnit Platform 6. Parallel execution is a JUnit 5.3+ feature (not exclusive to JUnit 6) and was not requested for enablement now. Note: jqwik `1.10.0+` adds an anti-AI-usage clause; `1.9.2` is unaffected.
- **`docker-compose.yml` `<<: [ *anchor, ... ]` merge keys — NON-ISSUE.** JetBrains reports "Single value expected" because its YAML-1.2 JSON-schema validator does not model YAML-1.1 merge keys. Validated with `docker compose config` (v5.1.4): exit 0, no warnings, and the merged env vars render correctly for every service. IDE false positive.

---

## Implementation (completed in PR #73)

1. `insight-service`: pact `4.7.1` ✓
2. `portfolio-service`: pact `4.7.1` + `wiremock-spring-boot:4.2.1` ✓
3. `market-data-service`: WireMock modular swap (§2) ✓
4. Versions centralized in root `build.gradle` `ext` (`wiremockVersion`, `wiremockSpringBootVersion`, `pactSpring7Version`) ✓
5. Verification: `./gradlew :insight-service:test :portfolio-service:test :portfolio-service:integrationTest :market-data-service:test` — green ✓
