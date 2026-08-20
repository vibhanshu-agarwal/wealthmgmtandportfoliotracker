# Mocked-Chaos Assertion and Sanitizer Font Gap — Bugfix Design

## Overview

Two independent defects, documented together because one CI failure surfaced both. **Track A** extends the Playwright artifact sanitizer's existing authenticated-allowlist pattern to trace-internal binary resources at the outermost nesting depth, so a self-hosted web font no longer forces the whole sanitize step to fail closed. **Track B** quarantines an E2E test whose assertion measures ticker-batch cardinality while narrating itself as a retry/backoff test, with an accurate RCA comment in place of the wrong one. Neither fix depends on the other; they merge as separate PRs, Track A first (it is the security-relevant one).

**Revision note**: this design was reviewed twice and corrected before implementation began (Codex, 2026-08-20). First pass, implementation-blocking: the exact call chain (`handleFile` was missing from the original design entirely), the sentinel-vs-authentication ordering (untested, and the original design did not specify it precisely enough to test), the canary's design (an arbitrary unknown binary cannot pass a single job asserting one output, so it needed splitting), and Task 1's RED direction (it was written to assert the *current broken* outcome rather than the *desired* one). Second pass, implementation-blocking: the "split canary" fix from the first pass was itself wrong — the existing `sanitizer-canary` CI job's contract (exactly one report zip, both sanitize passes must succeed) is a sentinel-redaction test, not a font-authentication test, and cannot host either a positive or negative font scenario without being restructured; the test seam was unspecified (`inspectZipEntry` is not exported — `structuredScan` is, and is the real seam); and the manifest loader's failure contract was incomplete and internally contradictory ("authenticate nothing" vs "not silently ignore" were never reconciled).

## Glossary

- **Bug_Condition (C-A)**: A Playwright trace embeds a `resources/*` entry inside `trace.zip` (at nesting depth 0, i.e. directly inside the outermost trace archive) that is not valid UTF-8 text, and the call chain that processes it never has access to an allowlist that could authenticate it.
- **Bug_Condition (C-B)**: `mocked-chaos.spec.ts:30`'s assertion narrates itself as measuring retry/backoff attempts but actually measures `ceil(uniqueTickerCount / MARKET_PRICE_BATCH_SIZE)` — a mismatch between what the test claims and what it measures that existed from the moment the test was written, independent of whether the currently-authenticated identity's holdings count happens to make the assertion pass or fail.
- **isFailureManifestation (Track B)**: the currently-*visible* symptom of C-B — `ceil(uniqueTickers/25) > 3` is true today (7 > 3), so the test fails now. This is a subset of when C-B matters, not equivalent to it: C-B was equally true, and equally a defect, when the dev identity's 2 holdings produced 1 batch and the assertion happened to pass. Naming this separately from the Bug_Condition matters because the fix targets the semantic mismatch (C-B), not the arithmetic threshold (isFailureManifestation).
- **Property (P-A.1)**: A trace resource whose canonical path and SHA-256 digest match a reviewed entry in the new font-resource manifest, found at nesting depth 0, is treated as authenticated and does not trigger `UNINSPECTABLE_ENTRY`.
- **Property (P-A.2)**: Authentication never suppresses a sentinel match. An entry that is both authenticated (digest matches) and contains a sentinel returns `A/MATCH`, not `clean`.
- **Property (P-A.3)**: Any resource that does not match — wrong digest, unknown path, or depth > 0 — fails closed exactly as before.
- **Property (P-B)**: The 429 test either asserts a value that actually reflects its own mechanism, or is quarantined with a comment that correctly names that mechanism — never left asserting a number that is a coincidence of whichever identity happens to be authenticated.
- **Preservation**: Track A must not change report-level allowlist behavior, sentinel scanning, or any hostile-archive limit, and must not reorder any existing check (limits run before authentication, exactly as today). Track B must not change the other two tests in `mocked-chaos.spec.ts`, `QueryProvider.test.ts`'s retry-policy coverage, or `portfolio.batching.test.ts`'s batching coverage.

### Verified call chain (Track A)

Confirmed by direct reading, not assumed:

```
runSanitizeFromEnv (line 686)
  → handleFile(filePath, stagingDir, ctx)                          (line 624)
      → classify(filePath, stagingDir, {sentinels, allowlist, budget})   (line 222) — non-zip files; unaffected by Track A
      → structuredScan(filePath, { sentinels, budget })             (line 642) — ★ allowlist is NOT passed here today
          → entryCtx = { sentinels, depth, budget, seenNames, archiveUncompressed: 0 }   (line 490) — ★ no allowlist field
          → inspectZipEntry(zipfile, entry, entryCtx)                (line 343, called per-entry from the "entry" event handler)
              → on nested zip: structuredScan(tmpFile, { sentinels, depth: depth + 1, budget })   (line 450) — ★ recursive call also drops allowlist
      → rawArchiveScan(filePath, { sentinels, budget })              (line 643) — unaffected by Track A
```

Three hops (marked ★) currently drop the allowlist. All three need threading changes — `structuredScan()` is **not** unmodified, contrary to an earlier draft of this design; its `options` parameter and `entryCtx` construction are exactly where the manifest needs to flow through.

**`classify`**: `sanitize.js:222` — the analogous top-level function for files that are NOT inside a zip. Already implements the ordering pattern Track A reuses — critically, note its exact return shape (lines 320-323):
```js
if (allowlistEntry && allowlistEntry.sha256 === digest) {
  return { type: "TEXT", matched, authenticated: true, digest };
}
```
`matched` (the sentinel-match flag) is passed through **even when authenticated** — `classify()` never lets authentication hide a sentinel match. This is the precedent for P-A.2, and the reason the original design's silence on this ordering was a real gap, not a stylistic omission: the existing code already gets this right for the top-level path, and Track A's job is to replicate that exact discipline for the zip-internal path, not merely to add a bypass.

- **`toCanonicalTracePath`**: `sanitize.js:169` — requires the path's first segment to be literally `trace` (Playwright's own shipped trace-viewer bundle, `playwright-report/trace/**`). Does not and should not apply to paths internal to a `trace.zip` archive — different artifact, different trust root. The new manifest keys directly on `entry.fileName` (the zip-internal path, e.g. `resources/<sha1>.woff2`), validated per-segment via the existing `isValidTraceSegment` (line 160) but without the `trace`-prefix requirement.
- **`known-playwright-report-assets.json`**: The existing manifest `classify`'s allowlist reads (`loadAllowlistMap`, line 551). Scoped to Playwright's own shipped trace-viewer code, reviewed and pinned to the exact locked `@playwright/test` version. **Track A does not touch this file** — it is the wrong trust root for content this application generates itself.

## Bug Details — Track A

### Bug Condition

`frontend/src/app/layout.tsx` loads `Geist` and `Geist_Mono` via `next/font/google`. Geist is not a separate dependency (`frontend/package.json` has no `geist` entry) — it ships as part of `next` itself (`"next": "16.2.3"`), so the font's origin version is entirely tied to `next`'s resolved version. `next/font/google` self-hosts the resulting WOFF2 files rather than linking an external CDN. Any Playwright trace of a real page navigation therefore embeds these as binary entries in `trace.zip`'s `resources/` directory, at nesting depth 0.

**Formal Specification:**

```
FUNCTION isBugCondition(entry, callChainReachesEntry)
  INPUT: entry — a zip entry inside trace.zip (depth 0), with entry.fileName
         callChainReachesEntry — whether ctx.frontendTraceAllowlist survives handleFile → structuredScan → inspectZipEntry
  OUTPUT: boolean

  RETURN NOT isValidUtf8(entry.content)
         AND callChainReachesEntry == FALSE   // true today at all three ★ hops above
END FUNCTION

FUNCTION isAuthenticated(entry, ctx)   // the fix's own decision function, for reference
  INPUT: entry, ctx = { ..., allowlist, depth }
  OUTPUT: boolean

  IF ctx.depth != 0 THEN RETURN FALSE          // depth policy — see "Nesting depth decision" below
  IF ctx.frontendTraceAllowlist == NULL THEN RETURN FALSE
  path := entry.fileName
  IF NOT isValidTraceSegment(each segment of path) THEN RETURN FALSE
  manifestEntry := ctx.frontendTraceAllowlist.get(path)
  IF manifestEntry == NULL THEN RETURN FALSE
  RETURN sha256(entry.content) == manifestEntry.sha256
END FUNCTION
```

### Nesting depth decision

**Decision: authentication applies only at depth 0** (entries found directly inside the outermost trace archive). Real Playwright traces never nest a font inside a further zip — `resources/` entries are always flat, one level inside `trace.zip`. Enforcement is structural, not merely a runtime check: the recursive call at `inspectZipEntry` line 450 (`structuredScan(tmpFile, { sentinels, depth: depth + 1, budget })`) simply never receives the new allowlist, so a depth-1+ "font" cannot authenticate regardless of digest — there is no allowlist reference for it to check against, by construction, not by a conditional that could be gotten wrong. This is deliberately the smaller attack surface: a crafted nested zip masquerading as a font two levels deep fails closed exactly as any other unrecognized binary would.

### Examples

Verified empirically 2026-08-20 by reproducing `mocked-chaos.spec.ts:30` against a live stack and extracting the resulting `trace.zip` (28 entries total, independently generated — not the trace an earlier investigator had already deleted):

| entry | size | magic bytes | SHA-256 | UTF-8 decodable |
|---|---|---|---|---|
| `resources/0e04bb6e7b54057d64c421b959c8c22774ae632d.woff2` | 23,108 bytes | `wOF2` | `5f3d6ad60f29d6cb708414ec6887163d63bf197377ef5417d2483ff31ace6c3b` | No |
| `resources/9d846db84c501327431670fe23b1a8ea1d2a5349.woff2` | 29,288 bytes | `wOF2` | `9b6f5ff45b278c744b5f379a2c4ecbaf858a842b8eaf82ac8d21b699ca16c608` | No |
| all other 26 entries (`.trace`, `.network`, `.stacks`, `.html`, `.css`, `.json`, `.txt`) | — | — | — | Yes |

Both hashes and sizes match exactly what an independent investigation (Codex) reported from a separately-generated trace before it was deleted, and were independently re-derived a second time from this trace directly (`python3 -c "hashlib.sha256(...)"`) — three-way agreement: Codex's report, Claude's first trace, Claude's SHA-256 computation on that same trace. **Exactly two entries fail; nothing else in the trace does.** These are the real digests used in the manifest below — not placeholders.

- **CI manifestation**: `docker-build-verify` (`ci-verification.yml`) runs the sanitizer in `mode: live-secret` (correctly, since that job now carries the real `E2E_TEST_USER_PASSWORD` — PR #121 commit `04fe519`). Any test failure in that job that navigates a real page — not only the Track B test — produces a trace the sanitizer cannot upload.
- **Edge case — report-level assets**: `playwright-report/trace/index.html` and its bundled JS/CSS ARE authenticated today, via `classify()` and `known-playwright-report-assets.json`. This bug is specific to resources embedded inside the recorded `trace.zip` itself.
- **Edge case — canary blind spot**: `local-server.ts` responds only `{"ok": true, "bytes": N}` — no HTML, no assets. No existing canary fixture could have caught this.
- **Edge case — sentinel-in-authenticated-binary**: an adversarial or pathological case where a manifest-matched entry's bytes also happen to contain a sentinel value must still return `A/MATCH`. This is untested by the two real fonts (neither contains the sentinel), so it needs a synthetic fixture (task 3) — the manifest match answers "is this a reviewed binary", not "is this safe to skip scanning". Constructed as `Buffer.concat([realFontBytes, sentinelBytes])`: real font bytes so the content is genuinely undecodable as UTF-8 (matching the real-world shape this property has to hold for), with the sentinel appended rather than spliced into the font's internal structure (no need to find "unused padding" inside a WOFF2 — appending extra bytes after a valid font payload is simpler and does not require understanding WOFF2's internal layout). The resulting digest is computed once and used **only** as a test-local, in-memory allowlist entry (`new Map([[testOnlyPath, {sha256: computedDigest}]])`) passed directly to `structuredScan()` — never written into `known-frontend-trace-resources.json`, and keyed to a path that is obviously test-only (e.g. `resources/test-sentinel-fixture.woff2`), not one of the two real production paths.

## Expected Behavior — Track A

### Preservation Requirements

**Unchanged Behaviors:**
- `known-playwright-report-assets.json` and `toCanonicalTracePath`'s `trace/`-prefix rule: untouched.
- Sentinel (secret) scanning runs on every entry regardless of authentication, and its computed result (`contentMatch`/`metaMatch`) is checked **after** authentication decides only the `UNINSPECTABLE_ENTRY` branch — never bypassed.
- All hostile-archive limits (`CRC_MISMATCH`, `PER_ARCHIVE_UNCOMPRESSED_LIMIT`, `COMPRESSION_RATIO`, `MAX_ZIP_NESTING`, duplicate/symlink rejection) run at their existing point in `inspectZipEntry`, **before** any authentication check — Track A adds a check between the existing nested-zip-recursion branch and the existing `!decodeOk` branch; it does not move or relax anything that runs earlier.

**Scope:** Everything in `sanitize.js` outside the three ★ hops in the verified call chain above, plus `inspectZipEntry`'s tail (lines ~450-458), is unaffected.

## Hypothesized Root Cause — Track A

1. **The allowlist never reaches the zip-internal path, at three separate hops**: `handleFile` doesn't pass it to `structuredScan`; `structuredScan` doesn't put it in `entryCtx`; the recursive nested-zip call doesn't forward it either (which — see the depth decision above — becomes a feature, not a bug, once intentional).
2. **The existing allowlist is the wrong trust root even if wired up**: `known-playwright-report-assets.json` is reviewed and pinned against `@playwright/test`'s version — it has no way to represent "this app's own font build," and should not be extended to try, since that would blur two different trust models into one manifest.
3. **No regression coverage**: the canary's fixture app never served a real page, so the gap had no test surface to be caught by.

## Correctness Properties — Track A

Property 1 (P-A.1): Bug Condition — Authenticated Font Resources Pass

_For any_ zip entry at nesting depth 0 whose canonical zip-internal path (`entry.fileName`) and SHA-256 digest exactly match a reviewed entry in `known-frontend-trace-resources.json`, `inspectZipEntry` SHALL classify it as inspected (not `UNINSPECTABLE_ENTRY`).

**Validates: Requirements A-2.1**

Property 2 (P-A.2): Ordering — Authentication Never Suppresses a Sentinel Match

_For any_ entry that is both authenticated (digest matches) AND contains a sentinel (`contentMatch` or `metaMatch` true), the result SHALL be `A/MATCH`, never `clean`. Authentication changes only whether `UNINSPECTABLE_ENTRY` fires; it never changes whether a sentinel match fires.

**Validates: Requirements A-2.1**

Property 3 (P-A.3): Bug Condition — Unauthenticated or Deep Binary Still Fails Closed

_For any_ zip entry that is not UTF-8-decodable AND (its path is not in the manifest, OR its digest does not match, OR its nesting depth is greater than 0), `inspectZipEntry` SHALL continue to return `UNINSPECTABLE_ENTRY`.

**Validates: Requirements A-2.2, A-3.4**

Property 4 (P-A): Preservation — Existing Checks Unreordered and Unrelaxed

_For any_ input, every check that exists today (CRC, archive-size, compression-ratio, nesting, symlink, duplicate-entry, report-level allowlist, sentinel scanning) SHALL run at its existing point in the existing order, with an unchanged result, for any input that does not touch the new authentication branch.

**Validates: Requirements A-3.1, A-3.2, A-3.3**

Property 5 (P-A): Manifest Validation — Fail Closed, Diagnosed, on Any Malformed or Stale State

_For any_ of the thirteen cases enumerated in A-2.4 (manifest missing/unreadable/malformed JSON/schema-invalid/wrong-or-missing `boundToPackage`/missing `boundToVersion`/bad entry path/bad digest/duplicate path, or lockfile missing/unreadable/malformed/missing-next-version/version-mismatched), `loadFrontendTraceResourceAllowlist()` SHALL return an empty `Map` (authenticate nothing) AND emit exactly one `::error::`-annotated, secret-free diagnostic line identifying which case fired — resolving "fail closed" and "not silently ignored" as the same requirement, not competing ones: silence would mean nobody notices the fix is inactive; a thrown exception would abort sanitization for a reason unrelated to actual content risk. Neither is acceptable; a logged, empty-map return is.

**Validates: Requirements A-2.4**

## Fix Implementation — Track A

**File**: `.github/actions/sanitize-playwright-artifacts/known-frontend-trace-resources.json` (new)

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

Real digests, verified twice independently (see Examples above) — not placeholders. `boundToVersion` re-verify against `frontend/package-lock.json` at implementation time; 16.2.3 was current as of this investigation but is not this design's authority.

**File**: `.github/actions/sanitize-playwright-artifacts/sanitize.js`

**Specific changes, matching the verified call chain exactly**:

1. Add `loadFrontendTraceResourceAllowlist(options = {})`, parallel to `loadAllowlistMap()` (line 551) but — unlike it — **path-injectable and exported**, since its thirteen validation cases need to be tested against deliberately malformed temporary files, not the real production manifest and lockfile:
   ```js
   function loadFrontendTraceResourceAllowlist({
     manifestPath = path.join(__dirname, "known-frontend-trace-resources.json"),
     lockfilePath = path.join(__dirname, "../../../frontend/package-lock.json"), // verified: resolves to the real file from sanitize.js's directory
     reportError = console.error,
   } = {}) { ... }
   ```
   `reportError` defaults to `console.error` in production (matching the existing `::error::`-annotation convention) but lets task 4.9's tests capture exactly which diagnostic category fired without mocking the global console. Add `loadFrontendTraceResourceAllowlist` to `module.exports` (line 733) — task 4.9 needs to import it directly; task 5's production run uses the no-argument defaults.

   Follows the existing `frontend/package-lock.json` lookup pattern already established by `sync-canary-playwright-version.js` (`lock.packages?.["node_modules/next"]?.version`, verified against the real lockfile: `lockfileVersion: 3`, `packages["node_modules/next"].version === "16.2.3"`). Loads the manifest into `Map<zipInternalPath, {sha256}>`. **Validation runs here, at sanitize-time, inside this function — not as a separate CI guard.** On any of the thirteen failure modes enumerated in A-2.4, calls `reportError("::error::<CATEGORY>: <detail, no secret values>")` and returns an empty `Map` — authenticates nothing, but the failure is never silent: CI logs (or, in tests, the captured `reportError` calls) always show which category fired. Any single malformed manifest entry (bad path, bad digest, duplicate path) invalidates the entire manifest for that run, not just that one entry — no partial trust.
2. `runSanitizeFromEnv` (line 686): load the new manifest alongside the existing one; add it to `ctx`.
3. `handleFile` (line 624): pass `ctx.frontendTraceAllowlist` into `structuredScan`'s options alongside the existing `{ sentinels, budget }` — this is the first ★ hop that currently drops it.
4. `structuredScan` (line 466): accept `frontendTraceAllowlist` from `options`; include it in `entryCtx` (line 490) **only when `depth === 0`** — i.e. the top-level call from `handleFile` includes it, but the recursive call at line 450 (nested zip, `depth: depth + 1`) does not pass it forward. This is the depth-0 enforcement mechanism — structural, not a conditional inside `inspectZipEntry` that could be bypassed.
5. `inspectZipEntry` (line 343): 
   - Add a SHA-256 hasher during the existing streaming read, computed only when `ctx.frontendTraceAllowlist` is present (mirroring `classify()`'s conditional `hasher`).
   - Before the existing `if (!decodeOk) return { outcome: "B", reason: "UNINSPECTABLE_ENTRY" }` (line 453), compute `authenticated` per the `isAuthenticated` pseudocode above, and change the condition to `if (!decodeOk && !authenticated)`.
   - Leave the subsequent `if (metaMatch || contentMatch) return { outcome: "A", reason: "MATCH" }` (line 455) and `return { outcome: "clean" }` completely unchanged — this is what makes P-A.2 hold: an authenticated entry falls through to exactly the same sentinel check as any decodable entry would.

**Files NOT changed** (confirming no changes needed):
- `known-playwright-report-assets.json`, `classify()`, `loadAllowlistMap()` — different trust root, untouched.
- `toCanonicalTracePath`, `validateManifestTracePath` — filesystem-relative report-path logic, unrelated to zip-internal paths.
- CRC/size/ratio/nesting checks (lines 375-447) — run before the new authentication check, unmodified.

**Regression coverage does not touch the `sanitizer-canary` CI job or `local-server.ts`.** That job's existing contract (`ci-verification.yml` ~line 86) requires: the canary Playwright test deliberately fails, exactly one nested report zip is produced (`zip_count -eq 1`), and both `fallback-only`-mode sanitize passes over `playwright-report`/`test-results` **succeed** (dirty-then-clean sentinel redaction, not `UNINSPECTABLE_ENTRY`). A font-authentication positive case and a fail-closed negative case cannot both fit inside that one job without either producing two report zips (breaking the count assertion) or a nonzero sanitize exit code (breaking the must-succeed assertion) — and restructuring that job to accommodate them would put a CI workflow file into Track A's diff for a property this job was never designed to test. Instead, per Codex's second-round review, this fix adds direct sanitizer-level test coverage (positive: task in "Regression Tests" below; negative: already covered by task 4.8's mutated/unknown/too-deep tests) — no workflow changes, no live browser required to prove the sanitizer's own logic is correct.

## Testing Strategy — Track A

### Test Seam

`inspectZipEntry` is **not exported** (confirmed against `module.exports`, line 733 — it lists `classify`, `structuredScan`, `rawArchiveScan`, `runSanitizeFromEnv`, `handleFile`, and others, but not `inspectZipEntry`). `structuredScan` **is** exported and is the real, intended seam for these tests:

```js
structuredScan(zipPath, { sentinels, frontendTraceAllowlist: new Map([[canonicalPath, { sha256: digest }]]) })
```

On **unfixed** code, `structuredScan`'s current options destructuring (line 466-468) reads only `options.sentinels`, `options.depth`, `options.budget` — an extra `frontendTraceAllowlist` key is silently ignored (JavaScript does not error on unread object keys), so this call behaves exactly as it does today: the fixture entry is undecodable, no allowlist reaches `inspectZipEntry`, result is `UNINSPECTABLE_ENTRY`. This is precisely the RED behavior task 1 needs, with no scaffolding or exports changes required to observe it. On **fixed** code (task 4.3-4.4), the same call threads `frontendTraceAllowlist` into `entryCtx` at depth 0 and reaches the new authentication check.

### Exploratory Bug Condition Checking

**Test Plan**: Confirm the bug reproduces before fixing, against a **committed, deterministic fixture** — not the git-ignored `test-results/` directory, which will not exist for the next person (or CI) to run this against. This project has no pre-existing `test/fixtures/` convention (the `hostile-archive*.test.js` suite builds fixture zips programmatically via `test/helpers/zip.js`'s `createZip()`); a committed **binary** fixture is new for this reason specifically — the whole point is that its digest must match production allowlist bytes, which an in-memory-generated fixture cannot provide.

**Test Cases**:
1. Commit one real WOFF2 file (byte-identical to one of the two production fonts — reuse the exact source bytes, do not invent synthetic content with a fabricated hash) as a new binary test asset, e.g. `.github/actions/sanitize-playwright-artifacts/test/fixtures/geist-sample.woff2` (new directory, described as such — not framed as reusing an existing convention).
2. Build a fixture zip with `createZip([{ name: "resources/0e04bb6e7b54057d64c421b959c8c22774ae632d.woff2", data: fs.readFileSync(fixtureFontPath) }])` from `test/helpers/zip.js`.
3. **Use one call, unchanged, for both RED (now) and GREEN (after tasks 4.3-4.4)** — do not write a version that omits `frontendTraceAllowlist` "to match today's shape"; that call would still return `UNINSPECTABLE_ENTRY` after the fix, since no allowlist would ever reach `entryCtx`:
   ```js
   const frontendTraceAllowlist = new Map([
     ["resources/0e04bb6e7b54057d64c421b959c8c22774ae632d.woff2",
      { sha256: "5f3d6ad60f29d6cb708414ec6887163d63bf197377ef5417d2483ff31ace6c3b" }],
   ]);
   const result = await structuredScan(fixtureZipPath, { frontendTraceAllowlist });
   ```
   `sentinels` is omitted from the options object entirely — `structuredScan` line 467 already falls back to its own unexported `defaultSentinelVariants()` when the key is absent; the test must not attempt to call that function directly.
4. Assert the **desired** outcome: `result.outcome !== "B"`.
5. **EXPECTED OUTCOME on unfixed code: the test FAILS** — `result.outcome === "B"`, `result.reason === "UNINSPECTABLE_ENTRY"`, because unfixed `structuredScan`'s options destructuring does not read `frontendTraceAllowlist` at all yet (see Test Seam above). **The same call must pass once tasks 4.3-4.4 land** — a differently-shaped passing call does not prove the fix.

### Sentinel-Ordering Checking (Property 2, P-A.2)

**Test Plan**: Prove authentication cannot be used to smuggle a sentinel past scanning, without touching the production manifest.

**Test Case**:
1. Build `fixtureBytes = Buffer.concat([realFontBytes, sentinelBytes])` — real font bytes (so the content is genuinely UTF-8-undecodable, matching the real-world shape) with a sentinel literal appended (per existing test convention, a test sentinel, never the real `E2E_TEST_USER_PASSWORD` value).
2. Compute `digest = sha256(fixtureBytes)` and build `createZip([{ name: "resources/test-sentinel-fixture.woff2", data: fixtureBytes }])` — a path that is obviously test-only, distinct from the two real production paths.
3. Call `structuredScan(fixtureZipPath, { sentinels, frontendTraceAllowlist: new Map([["resources/test-sentinel-fixture.woff2", { sha256: digest }]]) })` — a test-local, in-memory `Map`, never a write to `known-frontend-trace-resources.json`.
4. Assert `result.outcome === "A"` — authenticated **and** sentinel-matched must still surface as a match, never silently pass as `clean`.
5. **EXPECTED OUTCOME on unfixed code**: fails for the *structural* reason that no authentication path exists yet (the entry returns `B`/`UNINSPECTABLE_ENTRY` before sentinel matching is ever consulted in the return logic) — confirm it fails for this reason, not a fixture-construction bug, before implementing task 4.

### Fix Checking

```
FOR ALL entry IN {fixture trace, real captured trace} WHERE isBugCondition(entry, callChainReachesEntry=false) DO
  result := inspectZipEntry_fixed(zipfile, entry, entryCtxWithManifest)
  IF (entry.fileName, sha256(entry.content)) IN manifest AND entryCtx.depth == 0 THEN
    IF contentMatch OR metaMatch THEN
      ASSERT result.outcome == "A" AND result.reason == "MATCH"
    ELSE
      ASSERT result.outcome != "B"
    END IF
  ELSE
    ASSERT result.outcome == "B" AND result.reason == "UNINSPECTABLE_ENTRY"
  END IF
END FOR
```

### Preservation Checking

```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT sanitize_fixed(input) == sanitize_original(input)
END FOR
FOR ALL check IN {CRC, archive-size, compression-ratio, nesting, symlink, duplicate} DO
  ASSERT check fires at its existing line, before authentication is evaluated
END FOR
```

### Unit Tests
- `inspectZipEntry` classifies a manifest-matched, depth-0 WOFF2 entry as authenticated, not `UNINSPECTABLE_ENTRY`.
- `inspectZipEntry` returns `UNINSPECTABLE_ENTRY` for a WOFF2 entry whose digest does not match any manifest entry (mutated content).
- `inspectZipEntry` returns `UNINSPECTABLE_ENTRY` for a WOFF2 entry whose path is not in the manifest at all.
- `inspectZipEntry` returns `UNINSPECTABLE_ENTRY` for a manifest-matching entry found at depth > 0 (nested inside a zip inside `trace.zip`).
- **`inspectZipEntry` returns `A/MATCH`, not `clean`, for an entry that is both authenticated and sentinel-matched** (Property 2, P-A.2 — the test Codex's review specifically required).
- `loadFrontendTraceResourceAllowlist()` returns an empty map (authenticates nothing) for each failure mode in Property 5 (P-A): malformed JSON, missing fields, non-canonical path, malformed digest, duplicate path, version mismatch against `package-lock.json`.

### Regression Tests (sanitizer-level, not the `sanitizer-canary` CI job — see Fix Implementation)
- Positive: the committed real-WOFF2 fixture (task 1's fixture, now run through `structuredScan` **with** the real production manifest) sanitizes cleanly end-to-end.
- Negative: already covered by task 4.8's mutated-digest, unknown-path, and depth>0 cases — no separate "negative canary" needed, since these are the same property (A-3.4) exercised the same way.
- Re-run the sanitizer against the real trace captured during this investigation (if still present) and confirm it now succeeds.

### Integration Tests
- `docker-build-verify` (`ci-verification.yml`) succeeds end-to-end on a run where an unrelated test fails and produces a real-page trace.

---

## Bug Details — Track B

### Bug Condition

`mocked-chaos.spec.ts:10` installs a shared session via `installGatewaySessionInitScript` (`beforeEach`), which B1 Wave 0 (PR #121) changed from the dev identity (2 holdings) to the Golden-State E2E identity (159 active catalog entries — `config/seed-tickers.json`, counted directly: 159 `ACTIVE` of 160 total). The 429 test (`mocked-chaos.spec.ts:30`) mocks `**/api/market/**` to always 429 and asserts `requestCount <= 3`, narrated as proving TanStack Query "stopped retrying."

**Formal Specification:**

```
FUNCTION isBugCondition()   // C-B: the semantic defect — always true, independent of current holdings count
  OUTPUT: boolean
  RETURN assertionMechanism(mocked-chaos.spec.ts:30) != claimedMechanism(mocked-chaos.spec.ts:30)
         // claimed: "exponential backoff and limits retries"
         // actual: batch cardinality via Promise.allSettled, defaultQueryRetry never consulted
END FUNCTION

FUNCTION isFailureManifestation()   // the currently-visible symptom, a SUBSET of when C-B matters
  OUTPUT: boolean
  uniqueTickers := count(ACTIVE entries in config/seed-tickers.json)   // 159, verified from source
  batches := ceil(uniqueTickers / MARKET_PRICE_BATCH_SIZE)             // MARKET_PRICE_BATCH_SIZE = 25 (portfolio.ts:59)
  RETURN batches > 3   // the test's hardcoded bound; true today, was false under the pre-Wave-0 dev identity
END FUNCTION
```

`isBugCondition()` (C-B) was true the entire time this test existed, including when `isFailureManifestation()` was false. Conflating the two — treating "the test currently fails" as the definition of the bug — would miss that the defect is the narration/mechanism mismatch, not the number 7.

`159 / 25 = 6.36 → ceil = 7`, confirmed by three independent live reproductions (`Received: 7`, `5.7`–`6.1s`, no variance).

### Examples

- **Edge case — the test passed historically for the wrong reason**: before B1 Wave 0, the authenticated identity held 2 tickers → 1 batch → `1 <= 3` → green, while `isBugCondition()` (C-B) was already true.
- **Edge case — the other two tests in this file are unaffected**: the 503 test and the (already-skipped) 502 test don't touch `/api/market/**` batching.

## Expected Behavior — Track B

### Preservation Requirements

**Unchanged Behaviors:**
- `mocked-chaos.spec.ts`'s 503 and 502 tests: unaffected.
- `QueryProvider.test.ts`: already correctly tests `defaultQueryRetry` directly; untouched.
- `portfolio.batching.test.ts`: already correctly tests batch cardinality and partial-failure merging; untouched.
- `loadMarketPrices`, `MARKET_PRICE_BATCH_SIZE`, and `Promise.allSettled` usage in `portfolio.ts`: no production code changes — this is a test-only defect.

**Scope**: Only the 429 test's body and skip status change. No other spec file, no production code.

## Hypothesized Root Cause — Track B

1. **Wrong invariant asserted from the start**: the test encoded an implicit assumption (few holdings → few batches, mistaken for "backoff capped retries") rather than an explicit one, so it silently held by coincidence until B1 Wave 0 changed the identity's holdings count as an intended, documented part of that wave's own scope — not a regression it introduced.
2. **No coverage of what the test claims to cover**: since 429 responses are never retried in this codebase, no test in the current suite actually exercises retry/backoff limiting for that status — the gap this test claimed to fill was never filled, before or after Wave 0.

## Correctness Properties — Track B

Property 1 (P-B): Bug Condition — Assertion Narration Does Not Match Its Mechanism

_The_ semantic defect (C-B) holds regardless of the currently-authenticated identity's holdings count; `isFailureManifestation()` is a symptom of C-B under current conditions, not its definition.

**Validates: Requirements B-1.2, B-1.3, B-1.4, B-1.5**

Property 2 (P-B): Expected Behavior — Quarantine, Not a Coincidental Rewrite

_The_ fixed test file SHALL NOT assert a re-hardcoded number chosen to make the current identity pass; it SHALL be quarantined with a comment naming the real mechanism (C-B), consistent with the file's existing skip pattern.

**Validates: Requirements B-2.1, B-2.2**

## Fix Implementation — Track B

**File**: `frontend/tests/e2e/mocked-chaos.spec.ts`

**Specific changes**:
1. Change `test(...)` to `test.skip(...)` for "429 Too Many Requests handles exponential backoff and limits retries" (currently line 30).
2. Replace the misleading inline comment (`// Assert that TanStack Query stopped retrying (e.g., max 2 or 3 requests total)`) with an accurate RCA comment naming: the real mechanism (`Promise.allSettled`-absorbed batch requests, not retries — C-B), the current count and why (`ceil(uniqueTickers/25)`, 7 under the Golden-State identity), that this mismatch predates B1 Wave 0 and was only exposed by it, and that `defaultQueryRetry` already has direct, correct, unaffected coverage in `QueryProvider.test.ts`.
3. Do not delete the test body — keep it as documentation, consistent with the existing 502 skip.

**Files NOT changed**: `portfolio.ts`, `QueryProvider.tsx` (no production defect), `QueryProvider.test.ts`, `portfolio.batching.test.ts` (already correct).

**Follow-up (B-NG.1, out of scope, tracked via task 8.1)**: a permanent redesign using a controlled, fixed-holdings portfolio fixture, asserting disjoint-batch-occurs-exactly-once and graceful-degradation invariants.

## Testing Strategy — Track B

### Exploratory Bug Condition Checking

1. Run `mocked-chaos.spec.ts:30` on unfixed code against the live Golden-State identity. **Expected**: fails with `Received: 7`.
2. Count `ACTIVE` entries in `config/seed-tickers.json`, confirm `ceil(count/25) == 7`.
3. Re-verify `defaultQueryRetry`'s 4xx short-circuit and `Promise.allSettled`'s rejection-absorption directly against source (B-1.2, B-1.3) — these are the mechanism, not just the arithmetic.

### Fix Checking

```
run mocked-chaos.spec.ts --grep "429 Too Many"
ASSERT overall suite reports the test as skipped, not failed
ASSERT the skip comment names batch cardinality and Promise.allSettled, not retry count
```

### Preservation Checking

```
FOR test IN {503 test, 502 test (already skipped)} DO
  ASSERT status_after(test) == status_before(test)
END FOR
run QueryProvider.test.ts AND portfolio.batching.test.ts
ASSERT both pass, unchanged
```

### Integration Tests
- `docker-build-verify` E2E run: `mocked-chaos.spec.ts` reports 1 skipped (429), 1 skipped (502, pre-existing), 1 passed (503).
