# Bugfix Requirements Document

## Introduction

Two independent, unrelated defects surfaced from one CI failure on `feat/b1-fixture-identity` (PR #121) and were separately confirmed by an independent investigation (Codex, 2026-08-20) and by direct reproduction against a live stack (Claude, 2026-08-20). They are documented together because they were found together and share one root evidence trail, but they are fixed and merged as **two independent tracks**, matching the precedent set by `.kiro/specs/rca-playwright-artifact-secret-exposure-and-rate-limit-flake/`. Requirement IDs are prefixed `A-` and `B-` per track — the two tracks are numbered independently and a bare `1.1` is never unambiguous on its own.

- **Track A (sanitizer, security-adjacent)** — the Playwright artifact sanitizer (`.github/actions/sanitize-playwright-artifacts/sanitize.js`) fails closed on any trace that embeds a self-hosted binary web-font resource, which every real-page navigation in this app now does. This blocks CI artifact upload — safely, since nothing leaks — but it blocks it on *any* unrelated test failure that produces such a trace, not only ones actually containing a secret.
- **Track B (test correctness)** — `frontend/tests/e2e/mocked-chaos.spec.ts:30` asserts a request count it narrates as proving retry/backoff behavior, but this codebase never retries a 4xx/429 response at all. The number it actually measures is unrelated ticker-batch cardinality, and it broke — correctly, for the wrong reason understood only after investigation — when B1 Wave 0 changed the authenticated identity's holdings count.

Both were confirmed by direct evidence, not inference: three independent live reproductions of the `429` test (`Received: 7` every time), and a freshly-generated trace whose 28 entries were individually classified, finding exactly the same two binary font entries an independent investigator found in a separate, already-deleted trace — including exact SHA-256 digest agreement, independently computed twice.

## Bug Analysis — Track A: Sanitizer fails closed on self-hosted font resources

### Current Behavior (Defect)

A-1.1 WHEN a Playwright trace records a real navigation to any page under the app's root layout (`frontend/src/app/layout.tsx`, which loads `Geist` and `Geist_Mono` via `next/font/google`) THEN the trace's `resources/` directory SHALL contain at least one binary WOFF2 font entry that is not valid UTF-8 text

A-1.2 WHEN `sanitize.js`'s `handleFile` (line 624) scans a file that opens as a zip archive THEN it calls `structuredScan(filePath, { sentinels, budget })` (line 642) — **no allowlist is passed**, so `structuredScan`'s own `entryCtx` (line 490: `{ sentinels, depth, budget, seenNames, archiveUncompressed: 0 }`) never carries an allowlist reference into `inspectZipEntry` (line 343) at all. This is not a case of the allowlist being in scope and unread — it is never threaded into this call chain in the first place, at any of its three hops (`handleFile → structuredScan → inspectZipEntry`)

A-1.3 WHEN `inspectZipEntry` processes a binary, non-UTF-8-decodable entry (`!decodeOk`) THEN it unconditionally returns `{ outcome: "B", reason: "UNINSPECTABLE_ENTRY" }` (line 453) — there is no digest-based bypass for this path, unlike the top-level `classify()` function (line 222) which already has one for non-zip files

A-1.4 WHEN `handleFile` or `structuredScan` receives an `outcome: "B"` result from any entry THEN it propagates that as a thrown error up through `runSanitizeFromEnv` (line 686), which aborts the entire sanitize step — the calling workflow's artifact-upload step (`if: steps.sanitize.outcome == 'success'`) SHALL NOT run, so the CI job fails and no diagnostic artifact is available, even though the trace contains no `E2E_TEST_USER_PASSWORD` value

A-1.5 WHEN the existing sanitizer test suite is run THEN general binary-entry coverage already exists (`structured-scan.test.js:62-78` proves `Outcome B` for a sentinel-carrying and a clean JPEG fixture) — but no test in it exercises a *reviewed real WOFF2 path+digest through the zip-internal frontend allowlist*, so the specific wiring gap this fix closes (the allowlist never reaching `inspectZipEntry`) has no existing regression coverage, even though "binary entries return B" in general does

**Investigation context, not part of what this fix remediates**: the sanitizer's own live-browser canary fixture (`.github/actions/sanitize-playwright-artifacts/test/canary/fixtures/local-server.ts`) also cannot exercise this path — it serves only a bare JSON response, no HTML page, no static assets. This is true and was the original prompt for A-1.5, but the canary's own contract (sentinel-redaction proof, not `UNINSPECTABLE_ENTRY` coverage — see A-2.3) is deliberately left unchanged by this fix. The canary's blindness to binary resources remains a fact about the canary; it is not a requirement task 5 targets by extending the canary itself.

### Expected Behavior (Correct)

A-2.1 WHEN a trace resource's canonical zip-internal path (`entry.fileName`) and SHA-256 digest match a reviewed entry in a font-resource allowlist manifest, scoped to this application's own build output (not Playwright's shipped assets), AND that entry is found directly inside the outermost trace archive (nesting depth 0) THEN the system SHALL treat the entry as authenticated for the purpose of the `UNINSPECTABLE_ENTRY` check only — authentication SHALL NOT suppress a sentinel match; an entry that is both authenticated and sentinel-matched SHALL still return `A/MATCH`

A-2.2 WHEN a trace resource's path is not present in the allowlist, OR its digest does not match the allowlist entry for that path, OR it is found at any nesting depth greater than 0 THEN the system SHALL continue to fail closed with `UNINSPECTABLE_ENTRY` exactly as today — the allowlist authenticates specific reviewed bytes at a specific depth, never a content-type or file-extension exemption

A-2.3 WHEN regression coverage for this fix is added THEN it SHALL include (a) a positive test proving that a committed, real WOFF2 fixture (byte-identical to one of the two allowlisted fonts, built into a zip via the existing `test/helpers/zip.js` — this project has no pre-existing `test/fixtures/` convention, so the committed binary itself is new) authenticates and sanitizes cleanly, and (b) a negative test (already covered by task 4.8's mutated/unknown/too-deep cases) proving a non-matching binary still fails closed. **This is sanitizer-level test coverage, not a change to the live-browser `sanitizer-canary` CI job** — that job's existing contract (`ci-verification.yml` ~line 86: exactly one report zip, the canary Playwright test must fail, both sanitize passes over `playwright-report`/`test-results` must succeed) is about sentinel redaction and is unrelated to `UNINSPECTABLE_ENTRY`; retrofitting a second, deliberately-failing sanitize scenario into that job would break its one-zip and must-succeed assertions. Track A's diff does not touch `ci-verification.yml` or the `sanitizer-canary` job.

A-2.4 WHEN the allowlist manifest or its version binding cannot be established cleanly THEN the system SHALL fail closed (authenticate nothing, return an empty map) on every one of the following, each logged as its own `::error::`-annotated, secret-free diagnostic line naming which case fired — "fail closed" and "not silently ignored" both hold, because the empty map is returned only after a diagnostic is emitted:
  - manifest file missing or unreadable
  - manifest content is not valid JSON
  - manifest fails schema validation (not an object, `assets` not an array, an entry missing `path` or `sha256`)
  - `boundToPackage` field missing
  - `boundToPackage` present but not exactly `"next"` (validated by value, not merely by presence)
  - `boundToVersion` field missing
  - an entry's `path` is not a canonical, path-traversal-free zip-internal path
  - an entry's `sha256` is not exactly 64 lowercase hex characters
  - two entries share the same `path` (any single malformed entry invalidates the whole manifest for that run — no partial trust)
  - `frontend/package-lock.json` missing or unreadable
  - `frontend/package-lock.json` content is not valid JSON
  - `frontend/package-lock.json` has no `packages["node_modules/next"].version`
  - `boundToVersion` does not equal the resolved `next` version from the lockfile

This validation runs at sanitize-time, inside `sanitize.js` itself (`loadFrontendTraceResourceAllowlist()`), never as a separate optional CI guard that could be skipped.

### Unchanged Behavior (Regression Prevention)

A-3.1 WHEN the sanitizer processes report-level assets under `trace/**` (Playwright's own shipped trace-viewer bundle) THEN the existing `known-playwright-report-assets.json` allowlist, its canonical-path rule (`toCanonicalTracePath`), and its `@playwright/test`-version binding SHALL remain byte-identical and untouched

A-3.2 WHEN the sanitizer scans any entry for the `E2E_TEST_USER_PASSWORD` sentinel or any other configured secret literal THEN sentinel matching (`contentMatch`/`metaMatch`, computed unconditionally during the existing streaming read regardless of decodability) SHALL continue to run on every entry, authenticated or not — per A-2.1, authentication only changes the `UNINSPECTABLE_ENTRY` outcome, never the sentinel-match outcome

A-3.3 WHEN the sanitizer encounters a hostile archive (oversized entry, excessive nesting beyond `MAX_ZIP_NESTING`, compression-ratio bomb, duplicate entry, symlink, CRC mismatch) THEN all existing fail-closed limits SHALL continue to apply unchanged and BEFORE any authentication check runs — none of the existing limit checks (lines 436-447) are reordered or relaxed

A-3.4 WHEN a trace contains a binary resource that is NOT one of the two reviewed fonts in the new allowlist (e.g., a different font, an image, or any other binary the allowlist does not name) THEN the system SHALL continue to report `UNINSPECTABLE_ENTRY` and fail closed — this includes the canary's own negative fixture (A-2.3b)

## Bug Analysis — Track B: `mocked-chaos.spec.ts` measures batch count while claiming to measure retry backoff

### Current Behavior (Defect)

B-1.1 WHEN `mocked-chaos.spec.ts:30` ("429 Too Many Requests handles exponential backoff and limits retries") runs against the Golden-State E2E identity (159 active catalog tickers) THEN `loadMarketPrices` (`frontend/src/lib/api/portfolio.ts:89`) batches unique tickers at `MARKET_PRICE_BATCH_SIZE = 25` (line 59), producing `ceil(159/25) = 7` concurrent HTTP requests to the mocked `**/api/market/**` route — confirmed identically across three independent live reproductions on 2026-08-20

B-1.2 WHEN any of those 7 batch requests receives the mocked 429 THEN `defaultQueryRetry` (`frontend/src/components/layout/QueryProvider.tsx` ~line 20) returns `false` immediately for a `RateLimitError`/4xx match, so TanStack Query never retries it — this codebase does not retry 429s under any configuration, and did not before B1 Wave 0 either

B-1.3 WHEN a batch request rejects THEN `Promise.allSettled` (`portfolio.ts` line 109) absorbs the rejection and the merge loop (`portfolio.ts` line 137, "on rejection: continue") omits it from the price map without re-throwing, so the outer `fetchPortfolio` call never fails and `defaultQueryRetry` is never consulted for this code path at all, regardless of how many batches exist

B-1.4 WHEN the test's docblock and inline comments describe the assertion as proving retry/backoff behavior THEN the actual mechanism they measure (batch cardinality, driven by `MARKET_PRICE_BATCH_SIZE` and the authenticated identity's holdings count) has no relationship to retry count — this mismatch between narration and mechanism existed **before** B1 Wave 0 changed the identity, when the dev identity's 2 holdings happened to produce 1 batch and the assertion happened to hold; the defect is the mismatch itself, not any particular request count

B-1.5 WHEN B1 Wave 0 (PR #121) changed the shared login helper (`frontend/tests/e2e/helpers/browser-auth.ts`) to authenticate as the Golden-State identity THEN the pre-existing mismatch from B-1.4 became visible: the test began failing deterministically (`Received: 7` against `Expected: <=3`) on every run, despite the test file itself being unmodified by that change

### Expected Behavior (Correct)

B-2.1 WHEN this test (or its replacement) runs THEN it SHALL NOT assert a hardcoded request-count bound that is an artifact of whichever identity happens to be authenticated

B-2.2 WHEN this test is quarantined pending redesign THEN the skip SHALL carry an accurate RCA comment — matching the existing pattern already used in this file at line 63 for "502 Bad Gateway fallback" — naming the actual mechanism (batch cardinality via `Promise.allSettled`, not retry count) so a future reader does not need to re-derive this investigation

### Non-Goals

B-NG.1 A permanent redesign of retry/backoff coverage for this error path — using a controlled, fixed-holdings portfolio fixture instead of the mutable live Golden-State portfolio, asserting disjoint-batch-occurs-exactly-once and graceful-degradation invariants — is **explicitly out of scope for this bugfix**. It is tracked as a follow-up backlog item (task 8.1), not a requirement this spec implements.

### Unchanged Behavior (Regression Prevention)

B-3.1 WHEN `mocked-chaos.spec.ts`'s other tests (503 graceful degradation, 502 Bad Gateway fallback) run THEN their behavior SHALL be unaffected by this fix — only the 429 test's assertion/skip status changes

B-3.2 WHEN `QueryProvider.test.ts` (`frontend/src/components/layout/QueryProvider.test.ts:6`) asserts the retry policy directly THEN it SHALL continue to pass unchanged — retry policy is already correctly covered there and this fix does not touch it

B-3.3 WHEN `portfolio.batching.test.ts` (`frontend/src/lib/api/portfolio.batching.test.ts:109`) asserts batching cardinality and partial-failure behavior THEN it SHALL continue to pass unchanged — this fix does not touch `loadMarketPrices` or its batch size

B-3.4 WHEN any other E2E spec depends on `mocked-chaos.spec.ts`'s current pass/fail status (none identified, but verify) THEN quarantining the 429 test SHALL NOT change the pass/fail outcome of any other spec file
