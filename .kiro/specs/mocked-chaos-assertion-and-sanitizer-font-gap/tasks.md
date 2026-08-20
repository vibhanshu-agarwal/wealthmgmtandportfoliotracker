# Implementation Plan

Two independent tracks, merged as separate PRs. Track A first — it is the security-relevant one and Track B does not depend on it.

Requirement IDs are prefixed `A-`/`B-` (see `bugfix.md`) — never a bare `1.1`.

## Track A — Sanitizer font-resource allowlist gap

- [x] 1. Write bug condition exploration test against a committed, deterministic fixture, using the real test seam
  - **Property 1 (P-A.1): Bug Condition** — this test MUST assert the **desired** outcome (clean sanitization), not the current broken one. On unfixed code it MUST fail.
  - **DO NOT** write this test to assert `UNINSPECTABLE_ENTRY` as success — that describes today's bug, not the target behavior.
  - **DO NOT** depend on `frontend/test-results/**` (git-ignored, ephemeral) or on regenerating a live-stack trace as the test's fixture source.
  - **Test seam**: `inspectZipEntry` is NOT exported (confirmed against `module.exports`, line 733). `structuredScan` IS exported — it is the real seam: `structuredScan(zipPath, { sentinels, frontendTraceAllowlist: new Map([...]) })`. On unfixed code, `structuredScan`'s options destructuring (line 466-468) reads only `sentinels`/`depth`/`budget` — an extra `frontendTraceAllowlist` key is silently ignored, so the call behaves exactly as today: no scaffolding changes needed to observe the RED failure.
  - This project has **no pre-existing `test/fixtures/` convention** — the `hostile-archive*.test.js` suite builds fixture zips programmatically via `test/helpers/zip.js`'s `createZip()`. This task introduces a genuinely new element: commit one real WOFF2 file (byte-identical to one of the two production fonts, e.g. as `.github/actions/sanitize-playwright-artifacts/test/fixtures/geist-sample.woff2` — describe this as a new fixture directory, not a reuse of an existing one) because the whole point is that its digest must match production allowlist bytes, which a programmatically-generated fixture cannot provide.
  - Build the fixture zip: `createZip([{ name: "resources/0e04bb6e7b54057d64c421b959c8c22774ae632d.woff2", data: fs.readFileSync(fixtureFontPath) }])`.
  - **Use the same call, unchanged, for both the RED run now and the GREEN run after tasks 4.3-4.4** — do not write a version that omits the allowlist "to match today's shape"; that would still return `UNINSPECTABLE_ENTRY` after the fix, since nothing would ever pass the allowlist through:
    ```js
    const frontendTraceAllowlist = new Map([
      ["resources/0e04bb6e7b54057d64c421b959c8c22774ae632d.woff2",
       { sha256: "5f3d6ad60f29d6cb708414ec6887163d63bf197377ef5417d2483ff31ace6c3b" }],
    ]);
    const result = await structuredScan(fixtureZipPath, { frontendTraceAllowlist });
    ```
    Omit `sentinels` from the options object entirely — `structuredScan` already falls back to its own internal `defaultSentinelVariants()` (line 467: `options.sentinels || defaultSentinelVariants()`) when the key is absent, and that function is not exported, so do not attempt to call it directly from the test.
  - Assert `result.outcome !== "B"`.
  - **EXPECTED OUTCOME on unfixed code: test FAILS** — `result.outcome === "B"`, `result.reason === "UNINSPECTABLE_ENTRY"`, because unfixed `structuredScan` silently ignores the `frontendTraceAllowlist` key (it isn't in its options destructuring yet). Confirm this is the actual failure, not a fixture-construction error. **After task 4.3-4.4, this exact same call and assertion must pass** — that is what proves the fix, not a differently-shaped call.
  - _Requirements: A-1.1, A-1.2, A-1.3, A-1.4_

- [x] 2. Write preservation tests (BEFORE implementing the fix)
  - **Property 4 (P-A): Preservation** — report-level allowlist, sentinel scanning, and hostile-archive limits, unreordered
  - Observe on UNFIXED code and record baseline:
    - A known-good report-level asset (e.g. `trace/index.html`) still authenticates via `known-playwright-report-assets.json`
    - A sentinel-containing **text** entry is still caught (existing coverage — confirm it still exists, do not assume)
    - Each hostile-archive fixture (oversized entry, excess nesting, compression bomb, duplicate entry, symlink) still fails closed with its existing reason code, at its existing check, before any point where authentication would run
  - Write tests asserting these hold, verify they PASS on unfixed code (this is the baseline to preserve)
  - _Requirements: A-3.1, A-3.2, A-3.3_

- [x] 3. Prove the sentinel-vs-authentication ordering (Property 2, P-A.2) — the property the first draft of this plan left untested
  - **This is not optional and not deferrable — write and confirm it fails for the right reason before implementing, same as tasks 1-2**
  - Build `fixtureBytes = Buffer.concat([realFontBytes, sentinelBytes])` — real font bytes appended with a test sentinel literal (never the real `E2E_TEST_USER_PASSWORD` value, per existing test convention). Appending avoids needing to understand or preserve WOFF2's internal structure.
  - Compute `digest = sha256(fixtureBytes)`. Build a fixture zip with entry name `resources/test-sentinel-fixture.woff2` — obviously test-only, distinct from the two real production paths.
  - Call `structuredScan(fixtureZipPath, { sentinels, frontendTraceAllowlist: new Map([["resources/test-sentinel-fixture.woff2", { sha256: digest }]]) })` — a **test-local, in-memory `Map`**. **Do not add this entry to `known-frontend-trace-resources.json`** — the production manifest holds only the two real fonts, ever.
  - Assert `result.outcome === "A"` **and** `result.reason === "ENTRY_MATCH"` — authenticated AND sentinel-matched must still surface as a match, never `clean`. (The test seam is the public `structuredScan`, which reports `ENTRY_MATCH`; `inspectZipEntry`'s internal `MATCH` is not directly observable since that function is not exported. Asserting the exact reason catches a `clean` regression as sharply as a `B` one.)
  - **EXPECTED OUTCOME on unfixed code**: fails because no authentication path exists yet — confirm it fails for that structural reason (entry returns `B` before sentinel matching is ever consulted in the return logic), not a fixture bug.
  - _Requirements: A-2.1_

- [x] 4. Implement the font-resource allowlist
  - [x] 4.1 Create `.github/actions/sanitize-playwright-artifacts/known-frontend-trace-resources.json`
    - Exact contents (not placeholders — verified twice independently during investigation, re-confirm once more here since this is the file that authenticates production content):
      ```json
      {
        "boundToPackage": "next",
        "boundToVersion": "16.2.3",
        "assets": [
          { "path": "resources/0e04bb6e7b54057d64c421b959c8c22774ae632d.woff2", "sha256": "5f3d6ad60f29d6cb708414ec6887163d63bf197377ef5417d2483ff31ace6c3b" },
          { "path": "resources/9d846db84c501327431670fe23b1a8ea1d2a5349.woff2", "sha256": "9b6f5ff45b278c744b5f379a2c4ecbaf858a842b8eaf82ac8d21b699ca16c608" }
        ]
      }
      ```
    - Re-verify `boundToVersion` against `frontend/package-lock.json`'s currently-resolved `next` version before committing — do not assume 16.2.3 is still current
    - _Requirements: A-2.1_

  - [x] 4.2 Add `loadFrontendTraceResourceAllowlist(options = {})` to `sanitize.js`, parallel to `loadAllowlistMap()` (line 551) — path-injectable and exported, unlike it
    - Signature: `loadFrontendTraceResourceAllowlist({ manifestPath = path.join(__dirname, "known-frontend-trace-resources.json"), lockfilePath = path.join(__dirname, "../../../frontend/package-lock.json"), reportError = console.error } = {})` — the `../../../frontend/package-lock.json` relative path is verified: `sanitize.js` lives at `.github/actions/sanitize-playwright-artifacts/`, three levels up from repo root, and `path.join(__dirname, "../../../frontend/package-lock.json")` resolves to the real file (confirmed via `fs.existsSync`) — this is the seam task 4.9 needs; without it, testing thirteen malformed-file cases would mean corrupting production files during a test run
    - **Add `loadFrontendTraceResourceAllowlist` to `module.exports` (line 733)** — task 4.9 imports it directly with temp-file paths and a captured `reportError`; task 5's production run uses the no-argument defaults
    - Loads the manifest into `Map<zipInternalPath, {sha256}>`. Look up `next`'s resolved version the same way `sync-canary-playwright-version.js` already does for `@playwright/test`: `lock.packages?.["node_modules/next"]?.version` (verified against the real lockfile — `lockfileVersion: 3`, `packages["node_modules/next"].version === "16.2.3"`)
    - **Validation runs here, at sanitize-time, inside this function — not as a separate CI guard, not optional.** On any of the following thirteen cases, call `reportError` with a **category-only** diagnostic — `::error::<CATEGORY>`, optionally a safe integer `(asset index N)`, and **never a raw field value** (`boundToPackage`, an asset path, a version, a file path) — then return an **empty Map**. Raw values are attacker-influenceable and an invalid path may carry control bytes; interpolating them into a `::error::` annotation is the log-injection class this sanitizer prevents. Cases:
      1. manifest file missing or unreadable
      2. manifest content is not valid JSON
      3. manifest fails schema validation (not an object / `assets` not an array / entry missing `path` or `sha256`)
      4. `boundToPackage` field missing
      5. `boundToPackage` present but not exactly `"next"` (validate the value, not merely presence)
      6. `boundToVersion` field missing
      7. an entry's `path` fails `isValidTraceSegment` per-segment validation
      8. an entry's `sha256` is not exactly 64 lowercase hex characters
      9. two entries share the same `path` (invalidates the whole manifest, not just the duplicate — no partial trust)
      10. `frontend/package-lock.json` missing or unreadable
      11. `frontend/package-lock.json` content is not valid JSON
      12. lockfile has no `packages["node_modules/next"].version`
      13. `boundToVersion` does not equal the resolved `next` version from the lockfile
    - Do not throw past the point where the rest of the sanitizer would otherwise run — an empty map, not an exception, is the fail-closed state
    - _Bug_Condition: no loader exists for this manifest_
    - _Expected_Behavior: loader exists, is version-bound, fails closed (empty map, logged) on all thirteen cases_
    - _Preservation: `loadAllowlistMap()` for the existing report-asset manifest is untouched_
    - _Requirements: A-2.4_

  - [x] 4.3 Thread the new allowlist through the verified call chain — three hops, not one
    - `runSanitizeFromEnv` (line 686): load the new manifest, add to `ctx`
    - `handleFile` (line 624): pass `ctx.frontendTraceAllowlist` into `structuredScan`'s options — **this hop is currently missing entirely; `structuredScan(filePath, { sentinels, budget })` at line 642 does not pass an allowlist today**
    - `structuredScan` (line 466): accept `frontendTraceAllowlist` from `options`; include it in `entryCtx` (line 490) **only when `depth === 0`** — do not pass it into the recursive call at line 450 (`depth: depth + 1`), which is the entire enforcement mechanism for "authentication applies only at the outermost trace-archive depth"
    - _Bug_Condition: A-1.2 exactly as documented — allowlist dropped at handleFile, structuredScan, and the recursive call_
    - _Expected_Behavior: allowlist reaches entryCtx at depth 0 only_
    - _Requirements: A-2.1, A-2.2_

  - [x] 4.4 Implement the authenticated bypass in `inspectZipEntry` (line 343), preserving exact existing ordering
    - Add a SHA-256 hasher during the existing streaming read, computed only when `ctx.frontendTraceAllowlist` is present (mirror `classify()`'s conditional `hasher`, do not hash unconditionally)
    - Compute `canonicalPath = entry.fileName`, validated per-segment via the existing `isValidTraceSegment` (line 160)
    - Compute `authenticated` = manifest lookup by `canonicalPath` with digest equality
    - Change the existing `if (!decodeOk) return { outcome: "B", reason: "UNINSPECTABLE_ENTRY" }` (line 453) to `if (!decodeOk && !authenticated) return { outcome: "B", reason: "UNINSPECTABLE_ENTRY" }`
    - **Do not touch** the subsequent `if (metaMatch || contentMatch) return { outcome: "A", reason: "MATCH" }` (line 455) or the final `return { outcome: "clean" }` — an authenticated entry must fall through to exactly this same check, unmodified, which is what makes task 3's test pass once this lands
    - **Do not touch** anything above line 450 (CRC, archive-size, ratio, nesting, the recursive-zip check) — those run first, unmodified, exactly as documented in Preservation
    - _Bug_Condition: isBugCondition(entry, callChainReachesEntry) per design.md_
    - _Expected_Behavior: authenticated depth-0 entries classify as inspected; sentinel scanning still fires on them; everything else fails closed exactly as before_
    - _Preservation: CRC/size/ratio/nesting checks, sentinel scanning, and the `clean`/`A-MATCH` branches are byte-unchanged in their own logic — only the `UNINSPECTABLE_ENTRY` guard condition changes_
    - _Requirements: A-2.1, A-2.2, A-3.1, A-3.2, A-3.3, A-3.4_

  - [x] 4.5 Verify task 1's bug condition test now passes
    - Re-run the SAME test from task 1 — do not write a new one
    - **EXPECTED OUTCOME**: passes — the fixture now sanitizes cleanly
    - _Requirements: A-2.1_

  - [x] 4.6 Verify task 3's sentinel-ordering test now passes correctly
    - Re-run the SAME test from task 3
    - **EXPECTED OUTCOME** (through the public `structuredScan` seam): `{ outcome: "A", reason: "ENTRY_MATCH" }` — confirming authentication did not suppress the sentinel match. (`inspectZipEntry`'s internal `reason: "MATCH"` is wrapped to `ENTRY_MATCH` at the archive level.)
    - _Requirements: A-2.1_

  - [x] 4.7 Verify task 2's preservation tests still pass
    - Re-run the SAME tests from task 2, unchanged
    - _Requirements: A-3.1, A-3.2, A-3.3_

  - [x] 4.8 Prove a mutated, unknown, or too-deep binary still fails closed (Property 3, P-A.3)
    - Test: a WOFF2-shaped entry at a manifest path but with different bytes (wrong digest) → `UNINSPECTABLE_ENTRY`
    - Test: a binary entry not in the manifest at all → `UNINSPECTABLE_ENTRY`
    - Test: a manifest-matching path+digest entry constructed to appear at nesting depth 1 (inside a zip nested inside `trace.zip`) → `UNINSPECTABLE_ENTRY` — confirms the depth-0 enforcement from task 4.3 actually holds
    - _Requirements: A-2.2, A-3.4_

  - [x] 4.9 Prove manifest fail-closed validation for all thirteen cases from task 4.2, using the injectable seam
    - One test per case (manifest missing/unreadable, malformed JSON, schema-invalid, missing `boundToPackage`, wrong `boundToPackage` value, missing `boundToVersion`, bad entry path, bad digest, duplicate path, lockfile missing/unreadable, lockfile malformed JSON, lockfile missing next-version, version mismatch)
    - Each case: write a temporary manifest and/or lockfile file (via `fs.mkdtempSync`/`os.tmpdir()`, cleaned up after) exhibiting exactly that malformation, and a `reportError` spy/capture function
    - Call `loadFrontendTraceResourceAllowlist({ manifestPath: tempManifestPath, lockfilePath: tempLockfilePath, reportError: capturedReportError })` — never the production `known-frontend-trace-resources.json` or `frontend/package-lock.json`
    - Assert, on **every** case, that the diagnostic **exactly equals** `::error::<CATEGORY>` or `::error::<CATEGORY> (asset index N)` — exact-match inherently proves no field value leaked, and is the primary guarantee. Additionally, on the **value-bearing** cases (the fields the pre-fix code interpolated: `boundToPackage`, an asset path, a digest, version strings), inject a hostile marker (sentinel + forged `::error::` + newline + C0 control byte) into that field and assert the marker never appears — otherwise those cases' non-leakage claim would be vacuous. Cases with no interpolable field (missing/malformed file, missing key) have nothing to inject and rely on exact-match alone. Confirm the return is an empty `Map` and `sanitize.js` does not crash.
    - _Requirements: A-2.4_

- [x] 5. Add sanitizer-level regression coverage for the font-authentication path — do not restructure the `sanitizer-canary` job's assertions
  - **Do not restructure the `sanitizer-canary` job's sentinel-redaction assertions, and do not change `test/canary/fixtures/local-server.ts`.** That job (`ci-verification.yml` ~line 86) asserts exactly one report zip and that both `fallback-only` sanitize passes **succeed** — a sentinel-redaction test. A font-authentication positive case or a fail-closed negative case cannot live *inside* those assertions without producing a second zip or a nonzero sanitize exit. So the font coverage is plain `node --test` coverage (positive test below; negatives in task 4.8), not a new canary scenario.
  - **Positive regression test**: re-run task 1's committed fixture (real WOFF2 bytes) through `structuredScan` **with** the real production manifest (`known-frontend-trace-resources.json`, loaded via `loadFrontendTraceResourceAllowlist()`) and confirm it sanitizes cleanly — the fixed-code counterpart to task 1's RED test, proving the actual production manifest authenticates the real fixture.
  - **Negative regression coverage**: already satisfied by task 4.8 (mutated digest, unknown path, depth > 0) — no separate fixture needed; do not duplicate.
  - _Requirements: A-2.3, A-1.5_

- [x] 5.1 Wire the Node test suite into CI (it was never run before)
  - **Before this fix, no workflow ran `node --test` at all** — `unit-tests` is Gradle-only and `sanitizer-canary` only runs the Playwright canary + `sanitize.js`, never the `test/*.test.js` suite. So every existing sanitizer test (and all the new ones) had zero CI enforcement. This is itself an evidence-oracle mismatch and must be closed as part of Track A.
  - Add a step to the `sanitizer-canary` job **after** the canary run (so `test/canary/playwright-report/trace/` exists — `allowlist.test.js` reads it), working-directory the sanitizer action dir: `node --test test/*.test.js`.
  - This is a **legitimate** change to `ci-verification.yml` — it adds a test-runner step, it does not restructure the canary's sentinel-redaction assertions (task 5). Track A's diff therefore does include `ci-verification.yml`.
  - On the Linux runner GNU `/usr/bin/time -v` is present, so `hostile-archive-resource.test.js`'s RSS-bound test executes here — it is skipped on non-Linux dev machines (macOS has `/usr/bin/time`, but BSD `time` lacks the GNU `-v` flag; the binary is not "absent", the flag is), so this CI step is the only place it actually runs.
  - _Requirements: A-1.5_

- [x] 6. Checkpoint — Track A
  - Run the full sanitizer test suite (`structured-scan.test.js`, `classify.test.js`, `allowlist.test.js`, `hostile-archive*.test.js`, `sentinel-transport.test.js`, `state-machine.test.js`, plus every new test from tasks 1-5)
  - Re-run `sanitize.js` against the real trace captured during investigation (`frontend/test-results/mocked-chaos-Mocked-Chaos--79d53--backoff-and-limits-retries-chromium/trace.zip`, if still present, or regenerate) and confirm it now succeeds end-to-end
  - Confirm `known-playwright-report-assets.json` and `classify()` are byte-unmodified (`git diff` should show zero changes to that file or function)
  - Ask the user if questions arise before opening the PR

## Track B — `mocked-chaos.spec.ts` assertion quarantine

- [x] 7. Confirm the bug condition (C-B) against source, not just its current symptom
  - Re-count `ACTIVE` entries in `config/seed-tickers.json` (159 at time of writing — re-verify)
  - Re-confirm `MARKET_PRICE_BATCH_SIZE` in `frontend/src/lib/api/portfolio.ts` (25 at time of writing)
  - Re-confirm `defaultQueryRetry`'s 4xx/`RateLimitError` short-circuit in `QueryProvider.tsx` returns `false` before any retry-count logic runs (B-1.2)
  - Re-confirm `Promise.allSettled` in `loadMarketPrices` (`portfolio.ts:109`) and the "on rejection: continue" merge loop (`portfolio.ts:137`) — trace that `fetchPortfolio` never throws on a batch failure, so `defaultQueryRetry` is never consulted for this path at all (B-1.3)
  - Compute `ceil(activeCount / batchSize)` and confirm it currently exceeds the test's hardcoded bound of 3 (`isFailureManifestation()`, currently true — B-1.1)
  - Note explicitly, in the PR description or a code comment, that the mismatch (B-1.4) is not new to this identity change — B-1.5 only exposed it
  - _Requirements: B-1.1, B-1.2, B-1.3, B-1.4, B-1.5_

- [x] 8. Quarantine the test with an accurate RCA comment
  - In `frontend/tests/e2e/mocked-chaos.spec.ts`, change `test(...)` to `test.skip(...)` for "429 Too Many Requests handles exponential backoff and limits retries" (currently line 30)
  - Replace the misleading comment (`// Assert that TanStack Query stopped retrying...`) with an accurate one naming: `Promise.allSettled` absorbs every batch rejection so `defaultQueryRetry` is never consulted on this path (C-B); the count measured is ticker-batch cardinality (`ceil(uniqueTickers/25)`, currently 7 under the Golden-State identity), not retry attempts; this mismatch predates B1 Wave 0 and was only exposed by it; `QueryProvider.test.ts` already covers the retry policy directly
  - Match the existing skip style already used at line 63 ("502 Bad Gateway fallback")
  - Keep the test body intact, just skipped
  - _Requirements: B-2.1, B-2.2_

- [x] 8.1 File the follow-up backlog item for B-NG.1
  - Per `docs/todos/backlog/` convention (see `reference-repo-conventions`): add a short entry describing the permanent redesign — controlled fixed-holdings portfolio fixture, disjoint-batch-occurs-exactly-once assertion, no-duplicate-batch-after-observation-window assertion, graceful-degradation assertion — so B-NG.1 has a concrete tracking artifact rather than being left as a comment inside a skipped test
  - _Requirements: B-NG.1 (non-normative — this task exists so it is not silently dropped)_

- [x] 9. Verify preservation
  - Run `mocked-chaos.spec.ts` in full: confirm the 503 test still passes, the 502 test remains skipped (unchanged), the 429 test now reports skipped rather than failed
  - Run `QueryProvider.test.ts` and `portfolio.batching.test.ts`: confirm both pass, unchanged
  - Grep the E2E suite for any other spec depending on this file's pass/fail count; confirm none exists
  - _Requirements: B-3.1, B-3.2, B-3.3, B-3.4_

- [x] 9.1 Checkpoint — Track B
  - Run the full `mocked-chaos.spec.ts` file against a live stack once more; confirm: 1 passed, 2 skipped, 0 failed
  - Confirm no production code changed (`git diff` should show only the one test file, plus the backlog entry from task 8.1)
  - Ask the user if questions arise before opening the PR

## Final

- [x] 10. Confirm both tracks are independently mergeable
  - Track A's diff touches: `.github/actions/sanitize-playwright-artifacts/**` (the fix, fixture, and tests), `.github/workflows/ci-verification.yml` (the `node --test` step added in task 5.1 — no restructuring of the canary's assertions), and the shared spec docs under `.kiro/specs/mocked-chaos-assertion-and-sanitizer-font-gap/**`.
  - Track B's diff touches only `frontend/tests/e2e/mocked-chaos.spec.ts` and `docs/todos/backlog/**`.
  - No file appears in both diffs; either can merge without the other. The shared spec docs live on the Track A branch only, so they land when Track A (#122) merges — not with whichever merges first.
