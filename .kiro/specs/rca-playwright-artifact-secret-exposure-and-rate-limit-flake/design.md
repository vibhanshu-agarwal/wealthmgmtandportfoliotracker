# Design Document

Revision 16 — corrects one non-executable regression fixture in Revision 15's manifest-path
validation, per checkpoint entry [43]. Entry [43] **accepted Revision 15's raw-vs-resolved security
fix outright** (`validateManifestTracePath` correctly rejects `trace/./evil.bin`,
`trace/a/../evil.bin`, and every other hostile raw form on both POSIX and Windows path semantics,
independently re-verified) — the only remaining issue was methodological, not a security gap: the
regression list proposed defeating the raw-vs-canonical equality check with "a contrived symlinked
`stagingDirRoot`," which cannot work, because `path.resolve`/`path.relative` are purely lexical
string operations that never touch the filesystem or dereference a symlink — confirmed by direct
execution that a symlink-shaped root produces an identical result to any other root spelling. That
equality check's mismatch branch is therefore unreachable by any real fixture given the current
algorithm, not untested by omission. **Fixed** by removing the impossible symlink fixture and
replacing it with an honest description of the equality check as a defense-in-depth invariant, plus
a property/table test asserting equality for every accepted raw path and rejection (before the
equality check is ever reached) for every hostile one — Codex's own preferred, smaller of the two
offered options, since the design does not otherwise need a test seam for a lexical invariant. See
the corrected paragraph in the `validateManifestTracePath` regression fixtures list (Content
classification section). No other passage changed: `toCanonicalTracePath`, `validateManifestTracePath`
itself, the decoder flush, the lockfile-bound `playwrightTestVersion`, the canary-derived exhaustive
equality check, and every prior entry's accepted findings ([9], [17], [19], [21], [23], [25], [35],
[37], [39], [41]) are carried forward unchanged and are **not** reopened. Built on `bugfix.md`
Revision 4 (`10bdb3d8bb4ae3b2b68628726f20143ccb52b8f1`, frozen, approved, not reopened; this
amendment requires no change to bugfix.md). No `tasks.md`, no production/test implementation, no
deployment, Terraform, or credential action was performed to produce this document — the only
executed code was a direct Node probe confirming `path.resolve`/`path.relative` do not dereference a
symlink-shaped root, run outside this repository and discarded immediately after use.

---

## Investigation Gates — Evidence (unchanged, accepted per entry [15])

### Track A: A-IG.1/A-IG.2 — dummy-sentinel reproduction, output shapes generated

A standalone Playwright fixture, entirely outside this repository, mirroring the real project's
capture settings (`trace: "retain-on-failure"`, `html` reporter —
`frontend/playwright.config.ts:18,21`). A local `file://` page with `input[type="password"]`
supplied the DOM path; a throwaway local Node `http` server supplied the `APIRequestContext`
target. Dummy sentinels only (`DOM-SENTINEL-4f8a1c9e-...`, `API-SENTINEL-7b2d5f31-...`); the whole
fixture deleted after use. Output shapes generated: `playwright-report/index.html`,
`playwright-report/data/*.md`, `playwright-report/data/*.zip` (a nested archive),
`test-results/<test>/error-context.md`, `test-results/<test>/trace.zip`.

### Track A: A-IG.3 — recursive inspection, including the encoding-transformation extension

| Location | Format | Sentinel(s) found | Notes |
|---|---|---|---|
| `test-results/<test>/error-context.md` | plain markdown | both | "Test source" section echoes the spec file verbatim — does not generalize to the real secret, which is only referenced symbolically (`process.env.E2E_TEST_USER_PASSWORD`) in real spec source |
| `trace.zip` → `1-trace.trace` | JSONL action log | DOM | Twice per fill: raw `fill` action `params.value`, and a DOM-snapshot serialization |
| `trace.zip` → `0-trace.trace` | JSONL network log | API | |
| `trace.zip` → `resources/<hash>.json` | plain JSON | API | Full, verbatim `APIRequestContext` POST body |
| `playwright-report/data/<hash>.zip` | **nested zip** | both | Complete duplicate of the trace bundle, re-embedded inside `playwright-report/` |
| `playwright-report/data/<hash>.md` | plain markdown | both | Same content as `error-context.md` |
| `resources/page@*.jpeg` (inside `trace.zip`) | binary image | not text-greppable either way | Uninspectable — see Layer 2 |

**Encoding extension:** a value containing a stable ASCII anchor plus a double quote, backslash,
angle brackets, an ampersand, and Unicode (`ANCHOR"back\slash<tag>&amp;unicode-ü中文`) was sent via
DOM fill, DOM `textContent`, an `APIRequestContext` JSON body, and a URL query parameter. A raw
byte-exact substring search matched **zero** of 17 generated files; the anchor alone matched 7.
Two transformations confirmed present: **JSON-string escaping** (`"`→`\"`, `\`→`\\`; `<`,`>`,`&`,
Unicode literal) in action logs/snapshots/JSON bodies; **URL percent-encoding** (every
non-alphanumeric byte, Unicode as UTF-8 percent-encoded) in raw request URLs. **HTML-entity
encoding** was explicitly tested for and **not found anywhere** in this pipeline (a tested
negative) — the scanner below still checks it defensively.

### Track A: A-IG.4 — resolved

Playwright's trace capture **does** record `APIRequestContext` request bodies, via the same
`resources/<hash>.json` mechanism it uses for `page`-driven network activity.

### Track A: A-IG.5 — upload-boundary inventory

`rg -c "uses: actions/upload-artifact" .github/workflows/*.yml`: 14 total sites; **5** publish
Playwright output (`synthetic-monitoring.yml` ×2, `frontend-e2e-integration.yml`, `frontend-ci.yml`,
`ci-verification.yml`'s `playwright-traces` step); 9 are Java test reports, Pact contracts,
container logs, a Next.js static export, and Terraform artifacts — out of scope.

**Which of the 5 sites transmit the real secret** (drives the least-privilege design below):
`grep -rn "E2E_TEST_USER_PASSWORD" .github/workflows/*.yml` shows only `synthetic-monitoring.yml`'s
two jobs (lines 87, 186) reference it. `frontend-ci.yml`'s `static-smoke` authenticates nothing.
`frontend-e2e-integration.yml` uses the hardcoded local-dev credential
(`helpers/browser-auth.ts`/`api.ts`). `ci-verification.yml` runs `auth-jwt-health.spec.ts` under
the `chromium` project's own `setup`-project login flow — also not the real secret; no reference
exists anywhere in that file.

### Track B: B-IG.1 — source-level causal proof

The cached `spring-cloud-gateway-server-webflux:5.0.2` Lua script
(`META-INF/scripts/request_rate_limiter.lua`): `local now = tonumber(ARGV[3]) or redis.call('TIME')[1]`.
The Java caller (`RedisRateLimiter.isAllowed()`) passes an empty string as the third script
argument (`scriptArgs = Arrays.asList(replenishRate + "", burstCapacity + "", "", requestedTokens + "")`).
`tonumber("")` is `nil`, so `now` always falls back to the **Redis server's** `TIME` command,
whole-seconds only. Read directly from the code that runs — not inferred.

### Track B: B-IG.2/B-IG.3 — instrumented measurement with Redis-server time

A throwaway `@Test`, temporarily added to `ProductionRateLimitingIntegrationTest.java` and
**reverted via `git checkout` immediately after use — never committed**: one dedicated pre-warm
request on a distinct key (excluded from the sample), then 20 measured attempts (fresh key per
attempt), each capturing `redis.execInContainer("redis-cli", "TIME")` immediately before every one
of the 3 burst requests plus once after, plus `System.nanoTime()` per-request deltas and the
observed sequence.

**B-IG.3:** pre-warm (distinct key, excluded) took 462ms.

**B-IG.2, 20 measured attempts:**
```
attempt=3  redisTimesBeforeEach=[805,805,806] redisTimeAfter=806 remaining=[2,1,1] crossedServerSecond=true  matched=false
attempt=10 redisTimesBeforeEach=[807,807,808] redisTimeAfter=808 remaining=[2,2,1] crossedServerSecond=true  matched=false
attempt=17 redisTimesBeforeEach=[809,810,810] redisTimeAfter=810 remaining=[2,2,1] crossedServerSecond=true  matched=false
attempt=13 redisTimesBeforeEach=[808,808,808] redisTimeAfter=809 remaining=[2,1,0] crossedServerSecond=true  matched=true
attempt=20 redisTimesBeforeEach=[810,810,810] redisTimeAfter=811 remaining=[2,1,0] crossedServerSecond=true  matched=true
(remaining 15 attempts: no crossing detected, remaining=[2,1,0], matched=true)
```
**3/20 (15%)** attempts show a Redis-server-second change directly bracketing a request that broke
the decrement sequence — a measured correlation with the server clock, not an inference from
response data alone. **2/20** more show a crossing between the last burst request and the
post-burst check that did not alter the sequence (crossing landed after the 3rd request's own Lua
call completed) — evidence that the window-proof must cover the first-excess request, not only the
burst. Per-request timing fell from ~25ms to ~12–13ms as the JVM warmed across the run, plausibly
explaining why this back-to-back rate (15–25%) exceeds an earlier isolated-fresh-context round
(0/30). Measurement-precision caveat: the JVM-side "before" read can be one request position stale
relative to the actual server-side event (e.g. attempt 10's crossing is bracketed before request 3
but visibly affects request 2) — the *correlation* is unambiguous across all three visible cases;
the precise attributed request is approximate.

### Track B: B-IG.4/B-IG.5 — restated under `bugfix.md` Revision 4's cleared wording

- **B-IG.4:** the B-IG.2 table — 3/20 (15%) visible, 5/20 (25%) detected — is the honest
  characterization; B-IG.1 independently establishes causation.
- **B-IG.5:** satisfied via source-level applicability (the Lua/Java mechanism treats every
  `isAllowed()` call identically), corroborated by attempts 13/20 showing the same mechanism
  operating in the post-burst boundary region.

### Track B: B-IG.6

The Track B mechanism below was selected only after B-IG.1–B-IG.5 evidence existed.

### Track A: A-IG.7 — real HTML-reporter output re-examined a third time, at the actual locked
version AND the actual production path shape, with the complete classifier oracle (entry [37]
follow-up)

**Part 1 — the two prior fail-opens (entry [35]), independently re-confirmed fixed.** `zlib.gzipSync
(Buffer.from("TestPassword123!"))` (36 bytes, matches none of the four sentinel variants or six ZIP
signatures, but fails strict UTF-8 decode) still correctly routes to Outcome B under Revision
12/13's restored fallback. Entry [9]'s "no image exception" ruling remains reasserted: a ZIP entry
that is not strictly-valid UTF-8 text still makes the whole containing archive Outcome B. Neither of
these is reopened by the fixes below.

**Part 2 — the manifest re-verified as self-certifying (entry [37] finding 1), and why.** Revision
12's only check was a `playwrightTestVersion` string comparison against
`frontend/package-lock.json`. Traced through by hand: nothing prevented a manifest edit from adding
an arbitrary `{path, sha256}` pair while leaving that version string untouched — the check would
pass because it only ever validated *its own declared metadata*, never the manifest's actual
content against the real package. This is the identical self-certification shape already found and
fixed twice in the static secret-wiring guard (entries [19], [21]) — missed here because the
allowlist was designed as a new mechanism rather than checked against that established pattern.

**Part 3 — the path identity re-derived against real production roots, not an aggregate (entry [37]
finding 2).** Re-read the actual upload steps: four of the five real Playwright-upload sites
(`synthetic-monitoring.yml` ×2, `frontend-e2e-integration.yml`, `frontend-ci.yml`) pass
`path: frontend/playwright-report/` directly — not a wrapper directory containing it — so once
`sanitize.js` copies `source-dir`'s contents into `staging-dir` (per the existing, unchanged copy
semantics), an asset's canonical path is `staging-dir/trace/<asset>`. Revision 12's
`"playwright-report/trace/"` prefix check assumed an aggregate root (matching only the *canary's*
combined `test-results/`+`playwright-report/` directory, per Revision 3's `tasks.md`) and would
never have matched real production input. Re-ran the investigation fixture with `playwright-report/`
configured as its own root (matching production exactly, not an aggregate) at the exact locked
`1.59.0`: confirmed the real, on-disk shape is `playwright-report/trace/<asset>` one level above
`sanitize.js`'s `source-dir`, becoming `trace/<asset>` after the copy — settling the correct
manifest-key convention as `trace/`-relative to `staging-dir`, never `playwright-report/`-prefixed.

**Part 4 — the complete classifier oracle re-run, not decodability alone (entry [37] finding 3b).**
The prior evidence checked only `TextDecoder("utf-8", {fatal:true})`. Re-run against the same 16
real `trace/` files with **both** the decode check and the C0/DEL control-byte check the production
classifier actually applies:

| File | Size | SHA-256 | Result under the complete oracle |
|---|---|---|---|
| `trace/codicon.DCmgc-ay.ttf` | 80,340 B | `0f1d5219934e96e83b8db162d60b4d8c09b5de1e7d38031cbafe4a3c0f2889c9` | `UNINSPECTABLE` — fatal decode error |
| `trace/assets/defaultSettingsView-GTWI-W_B.js` | 643,623 B | `9c48649a4dfc1ba799a687c4ac96a6a9ada57696116044c227c67c51ebbfdce7` | `UNINSPECTABLE` — control byte `0x02` at index 575,993 |
| `trace/uiMode.Vipi55dB.js` | 38,156 B | `fb1005d82811458ffd6ae041d79cc7ebabcb6073f7fe03b5f7694c093433ba7b` | `UNINSPECTABLE` — control byte `0x1e` at index 10,056 |

All other 13 files under `trace/` pass the complete oracle cleanly (decode succeeds, no disallowed
control byte). These three, and only these three, are the allowlist's actual required membership at
`1.59.0` — the number and identity are unchanged from the earlier (decode-only) check, but this is
now confirmed against the real production rule, not a subset of it.

**The corrected mechanism (all three entry [37] findings closed):**

1. **Manifest schema validation, before any lookup.** `known-playwright-report-assets.json` must be
   a JSON object `{ playwrightTestVersion: string, assets: [{ path, sha256 }, ...] }` where: `path`
   is a canonical forward-slash relative path matching `^trace\/[^/\\]+(?:\/[^/\\]+)*$` (confined to
   `trace/`, no `..`, no backslash, no leading slash — reusing the same canonicalization discipline
   already applied to workflow paths elsewhere in this design); `sha256` is exactly 64 lowercase hex
   characters; `path` values are unique (no duplicates); no unexpected fields. Any violation fails
   the guard closed before it is ever used for a lookup.
2. **Exhaustive, canary-derived comparison — not a version-string check.** The generated-Playwright
   canary (already required by A2.1/IV.4) is extended: after producing a real report at the exact
   locked `@playwright/test`/`playwright`/`playwright-core` version (`1.59.0`, per
   `frontend/package-lock.json`, all three resolving identically), a script walks the real,
   generated `playwright-report/trace/` directory, computes the canonical `{path, sha256}` set
   directly from that output, and requires **exact set equality** with the checked-in manifest — no
   missing entry, no extra entry, no digest mismatch. Any discrepancy, or any error generating the
   report/computing the set, fails the CI check closed. This makes the manifest independently
   derivable and continuously re-verified against the real producer, not self-certifying: an edited
   manifest entry that doesn't match the real package's actual output is caught here, not trusted at
   scan time.
3. **One frozen canonical path identity, one single-pass read (closes the TOCTOU seam).** Allowlist
   membership is tested against `trace/<asset>`, computed relative to the sanitize step's own
   `staging-dir` root — matching real production's shape (Part 3), never the aggregate-root shape a
   caller might otherwise construct. A path is rejected (never even attempted against the allowlist)
   unless it is already in this canonical form. For a `trace/`-prefixed candidate, digest
   computation, the ZIP-signature check, the decode/control-byte flags, and the sentinel-variant
   search are all computed from **one** bounded streamed read — never two separate reads of the same
   file — with the digest computed incrementally (`crypto.createHash("sha256").update(chunk)`)
   alongside the existing sentinel search; the decode/control-byte outcome is recorded as a flag
   rather than aborting the stream early, since it is only relevant if the digest turns out **not**
   to match an allowlist entry (an authenticated file is exempt from that requirement, never from
   the sentinel scan or the ZIP-signature/budget checks, which still abort immediately as before).
   Any read/stat/open error during this pass is Outcome B, per the existing scanner-internal-error
   rule. Bytes read this way count against the same global/per-file budget as every other path.
4. **Layer 1's `screenshots: false` extension is unchanged from Revision 12** — still verified to
   leave a real trace.zip with zero binary entries (re-confirmed this revision against the
   production-shaped fixture) — but see the corrected Layer 1 wording below for what it actually
   means for the eight DOM-login tests.

Four of the five real Playwright-upload sites carry this allowlist requirement (they upload
`playwright-report/` in full); the fifth (`ci-verification.yml`'s `test-results/`-only upload) never
contains a `trace/` path at all, so the allowlist is simply never consulted there.

---

## Track A Design — Playwright artifact secret exposure

### Layer 1 — capture suppression (narrow, additive, accepted)

Full capture (trace, action logs, network logs, DOM snapshots) retained on all eight DOM-login
files (each asserts its own login redirect). The one clean candidate,
`helpers/browser-auth.ts:26-28`/`helpers/api.ts:14-19` (pure fixture setup, only asserts
`res.ok()`), uses an untraced plain `fetch()` instead of the traced `request.post()`.

**Extension, Revision 12/13 (A-IG.7, corrected wording per entry [37] finding 3a):**
`frontend/playwright.config.ts`'s `use.trace` changes from the shorthand `"retain-on-failure"` to
the equivalent object form with screenshots disabled: `{ mode: "retain-on-failure", screenshots:
false }` (documented, valid Playwright syntax; `screenshots` defaults to `true`). **This is a
global `use` change — it applies to every project, including the eight DOM-login tests, not only
the one untraced-fetch candidate above.** Trace screenshots are therefore suppressed everywhere,
under A3.1's explicit permitted-scope wording ("THE fix MAY additionally suppress rich capture
(trace/screenshots) at the point of generation... as one layer of the recommended defense-in-depth
approach" — bugfix.md:126-128) — corrected from Revision 12's inaccurate framing, which implied the
eight DOM-login tests retain screenshot capture too. They do not, and global suppression is the
right call, not merely a permitted one: under Layer 2's restored rule (A-IG.7 Part 1, reasserting
entry [9]), **any** ZIP containing a screenshot entry is Outcome B regardless of which test produced
it, so selectively preserving screenshots on the eight DOM-login tests would only ever result in
their traces being unconditionally blocked on capture, never a clean upload — global suppression is
what makes any successful trace upload possible for them at all, not a reduction in what they can
safely publish. **What the eight DOM-login tests do retain:** trace/action/network/DOM-snapshot/
source diagnostics, exactly as before — only the screenshot layer is gone, for every test.

Applied identically in the generated-Playwright canary's own config, so the canary continues to
exercise the exact real capture configuration.

Both Layer 1 changes are additive only — never a substitute for Layer 2, which remains
independently fail-closed for any future binary attachment or resource, on any test, whether or not
capture-suppression happens to apply to it.

### Layer 2 — centralized sanitize-and-verify gate

#### State machine — corrected to match frozen A2.3 exactly (finding 1)

**A2.3's literal text:** "IF the sanitizer/scanner errors, encounters a format it cannot safely
inspect, or detects a post-sanitize match, THEN the upload SHALL be prevented... SHALL NOT convert
a failed test run to green." Revision 4 conflated two different outcomes — a confirmed sentinel
match in content that *was* successfully parsed, versus an error/uninspectable/hostile-archive
condition — treating both the same way (placeholder-and-continue) and additionally, incorrectly,
letting the *second* pass mutate content. **Corrected: these are two structurally different
outcomes, and only pass 1 ever mutates anything.**

**Outcome A — confirmed match in successfully-parsed content.** A plain file that decodes as valid
UTF-8 text and matches a sentinel variant, or a ZIP that `yauzl` fully opens and validates (no
parser/CRC/limit/path/symlink violation) but which contains a match somewhere inside (entry
content, raw or decoded name, raw or decoded comment, entry extra fields, archive comment, or the
raw whole-file byte scan — see finding 2 below) — **may be withheld and replaced** with the generic
placeholder. The rest of `staging-dir` is otherwise unaffected.

**Outcome B — error, or a format/archive that cannot be proven safe.** Any of: a scanner internal
error; a top-level (or nested-non-ZIP) file that is neither strictly-valid UTF-8 text nor an exact
path+digest match against the authenticated package-asset allowlist (**Correction, Revision 12** —
reasserting entry [9]/entry [17]'s original rule after Revision 11's raw-scan-only loosening was
falsified by a trivial gzip counterexample, entry [35]; see A-IG.7), or that exceeds its byte
budget; a ZIP entry that is neither strictly-valid UTF-8 text nor itself a nested valid ZIP —
**making the whole containing archive Outcome B**, not just that entry (**Correction, Revision
12** — reasserting entry [9]'s explicit "no image exception" ruling, which Revision 10's raw-byte-
scan-coverage framing had silently overridden); a ZIP candidate that `yauzl` fails to open/parse
(corrupt, truncated, encrypted, unsupported compression); any hostile-archive rule violated (path
traversal, duplicate entries, nesting depth, entry count, per-entry/aggregate/global size,
compression ratio, CRC mismatch, a symlink anywhere including during the initial source copy); a
dependency-install/audit failure. **None of these is placeholder-replaced.** Each
**immediately exits non-zero and aborts the entire sanitize step** —
blocking the *entire* upload, not just the offending file. A symlink encountered during the initial
copy of `source-dir` is **not** silently skipped (Revision 4's error, now corrected) — it is
Outcome B: abort immediately.

**Pass 2 (post-sanitize verification) is read-only.** It re-walks the tree pass 1 produced and
re-runs the identical classification/scan logic, but **it never deletes, replaces, or otherwise
mutates anything.** If it finds anything at all — a remaining match (Outcome A shape) or any
Outcome B condition — it fails the step non-zero. It cannot "fix" its own finding; a clean result
from pass 2 is the only path to a successful sanitize step.

```
PASS 1 (sanitize — the only pass that mutates):
  validate source-dir ⊂ GITHUB_WORKSPACE, staging-dir is a fresh non-symlink child of RUNNER_TEMP
    (violation → Outcome B, abort)
  copy source-dir → staging-dir
    (any symlink encountered → Outcome B, abort — never skipped silently)
    (source-dir absent → staging-dir created empty, proceed — nothing to scan is not an error)
  walk staging-dir, classify every file (see finding 2's decision tree):
    TEXT, no match           → leave as-is
    TEXT, matches a variant  → Outcome A: delete, replace with placeholder
    ZIP, fully validates, every entry is either strictly-valid UTF-8 text or itself a nested valid
      ZIP (recursively, same rule), no match anywhere (entries/names/comments/extra-fields/
      archive-comment/raw-byte scan)           → leave as-is
    ZIP, fully validates, every entry inspectable per the rule above, match found anywhere
                                              → Outcome A: delete whole archive, replace with placeholder
    ZIP, fully validates, but at least one entry is neither strictly-valid UTF-8 text nor a nested
      valid ZIP (Revision 12 — reasserts entry [9]: an uninspected entry, e.g. a binary image, does
      NOT make the archive "already scanned" — a raw byte-pattern hit search over its content is not
      proof of safety) → Outcome B: abort entire step, non-zero
    ZIP candidate, fails to fully validate (parse error, CRC mismatch, any limit/path/duplicate
                                              violation) → Outcome B: abort entire step, non-zero
    non-ZIP file, matches the authenticated package-asset allowlist by exact path AND digest
      (Revision 12 — see A-IG.7), sentinel scan still run and clean → leave as-is
    non-ZIP file, strictly-valid UTF-8 text, sentinel scan clean
                                              → leave as-is
    non-ZIP file, strictly-valid UTF-8 text, sentinel scan matches
                                              → Outcome A: delete, replace with placeholder
    non-ZIP file, neither an allowlist match nor strictly-valid UTF-8 text
                                              → Outcome B: abort entire step, non-zero (Revision 12 —
                                                reverts Revision 11's "byte-scan and pass" rule,
                                                falsified by a gzip counterexample, entry [35])
    global byte budget exceeded              → Outcome B: abort entire step, non-zero
    any scanner internal error               → Outcome B: abort entire step, non-zero

PASS 2 (verify — read-only, no mutation):
  re-walk staging-dir with the identical classify/scan logic
    anything found (match, or any Outcome-B-shaped condition) → fail non-zero, block entire upload
    fully clean                                                → succeed; staging-dir is upload-eligible
```

#### Content classification — deferred to the real parser, not a hand-rolled signature check (finding 2)

**Corrected: a leading-4-bytes `PK` check is not a complete ZIP classifier.** Valid ZIP data can be
preceded by an arbitrary payload (self-extracting archives prepend a native stub before the zip
stream), and `yauzl` itself locates the End-Of-Central-Directory record by scanning, not by
trusting a local file header at offset 0. A hand-rolled leading-byte pre-filter can therefore both
misclassify a legitimately-prepended ZIP and does not reflect how the actual parser works. Fixed:
**always attempt `yauzl.open()` first; let it decide.**

**Entry [17]'s counter-example, and why a bare "`yauzl` rejected it → try text" fallback fails
open:** the byte sequence `50 4b 03 04 61 62 63` (`"PK\x03\x04abc"` — a ZIP local-file-header
signature immediately followed by three garbage bytes, with no central directory or End-Of-Central-
Directory record at all) is correctly rejected by `yauzl` — but it is *also* valid, fatally-strict
UTF-8 (control bytes `\x03`/`\x04` are legal Unicode code points). Under a bare "rejected → attempt
text decode" rule, this truncated ZIP fragment would decode successfully and be classified as
ordinary TEXT, found to contain no sentinel match, and uploaded — exactly the fail-open case A2.3
forbids. A parser failure does not, by itself, prove the input was ever intended as text.

**Fixed: after `yauzl` rejects a file, an intermediate fail-closed gate runs before text is ever
considered — either signal below routes to Outcome B, never to TEXT.**

**Corrected again this revision (entry [19] finding 2): the fallback gate itself must not load the
whole file into memory before its checks apply.** The prior pseudocode opened with
`bytes = read(filePath)` — an unbounded whole-file read for *every* file that reaches this branch,
ahead of any size limit, exactly the memory-exhaustion shape already fixed for the raw-archive scan
but left unfixed here. `yauzl.open()` itself manages its own I/O internally and is unaffected; only
the post-rejection fallback path needed this correction. Fixed: **one bounded streaming pass**,
combining the signature check, the top-level-file byte budget, the incremental strict decode, the
control-byte check, and the sentinel-variant search into a single chunked read — plus, ahead of that
pass, a bounded allowlist digest lookup for `trace/`-relative candidates (Revision 13/14, below) —
never a whole `Buffer` held at once for the streamed scan itself.

**Two functions for two different input representations (Revision 15, entry [41]) — not one
function applied to both, which Revision 14 incorrectly claimed.** `toCanonicalTracePath` operates
on an already-resolved, real filesystem path — appropriate at scan time, where `filePath` names a
file that actually exists on disk, so nothing is lost by resolving it. A raw manifest `path`
**string** is different: it never touches the filesystem, and `path.resolve()`/`path.relative()`
**silently collapse `.` and `..` segments as part of normalization** — confirmed by direct
execution: `path.resolve(root, "trace/./evil.bin")` and `path.resolve(root, "trace/a/../evil.bin")`
both produce the identical resolved path a legitimate `trace/evil.bin` entry would, so running the
scan-time function on an already-resolved manifest path (Revision 14's mistake) silently accepts
both hostile raw strings as if they were the canonical key — the dot segments are gone *before*
`toCanonicalTracePath`'s own segment checks ever see them. Manifest paths must therefore be
validated as **raw strings first**, before any resolution.

A shared segment predicate, used by both functions so the rejection rule is defined once:

```
isValidTraceSegment(segment):
  → segment !== "" and segment !== "." and segment !== ".."
    and segment does not contain "\\", a NUL byte, or any other C0 control byte
```

**Scan-time (filesystem paths — unchanged from Revision 14 except reusing the shared predicate):**

```
toCanonicalTracePath(filePath, stagingDirRoot):
  # Both arguments must already be resolved, absolute paths (path.resolve at the call site).
  rel = path.relative(stagingDirRoot, filePath)     # (from=root, to=file) — the order that actually
                                                       # produces "trace/x", not "../.."
  if rel === "" or path.isAbsolute(rel) or rel starts with "..":
    → null                                            # IS the root, outside the root, or escapes it
  normalized = rel.split(path.sep).join("/")          # forward slashes regardless of platform
  segments = normalized.split("/")
  for each segment in segments:
    if NOT isValidTraceSegment(segment): → null
  if segments[0] !== "trace":
    → null
  → normalized                                        # e.g. "trace/codicon.DCmgc-ay.ttf"
```

**Manifest-time (raw declared strings — new, entry [41]):**

```
validateManifestTracePath(raw, stagingDirRoot):
  # Validate the RAW string's own segments before any resolution touches it — resolution is what
  # erases the evidence a hostile raw string would otherwise be rejected for.
  if raw === "" or raw contains "\\" or raw contains a NUL/C0 control byte or path.isAbsolute(raw):
    → null
  rawSegments = raw.split("/")
  for each segment in rawSegments:
    if NOT isValidTraceSegment(segment): → null        # rejects "trace/./evil.bin" and
                                                          # "trace/a/../evil.bin" HERE, on the raw
                                                          # string, before resolution can collapse
                                                          # either into "trace/evil.bin"
  if rawSegments[0] !== "trace":
    → null

  # Defense in depth: resolve and re-derive via the scan-time function, requiring the two to agree
  # exactly. Given the raw check above already passed, this should be a no-op equality — a
  # mismatch here means the raw check missed something and both must independently agree.
  resolvedFilePath = path.resolve(stagingDirRoot, raw)
  canonical = toCanonicalTracePath(resolvedFilePath, stagingDirRoot)
  if canonical !== raw:
    → null
  → raw
```

`validateManifestTracePath` is run against every manifest-declared `path` before the lookup set is
constructed (schema validation, below); `toCanonicalTracePath` is run against every scan-time
`filePath` (`classify()`, above). They share `isValidTraceSegment` but are not the same function —
one representation is a real filesystem path, the other is untrusted input that must be checked in
its own raw form first.

```
classify(filePath, stagingDirRoot):
  try:
    zipFile = yauzl.open(filePath, { validateEntrySizes: true, strictFileNames: true, lazyEntries: true })
    → ZIP-CANDIDATE-CONFIRMED
  catch (open error — yauzl could not locate/validate a EOCD record):
    # Correction, Revision 12 (entry [35]): strict UTF-8 decodability is required by default for a
    # non-ZIP file to be treated as scannable. The narrow, provable exception is the allowlist below.
    canonicalPath = toCanonicalTracePath(path.resolve(filePath), path.resolve(stagingDirRoot))
    isAllowlistCandidate = (canonicalPath !== null)

    stream = fs.createReadStream(filePath, { highWaterMark: 1 MiB })
    decoder = new TextDecoder("utf-8", { fatal: true })    # fatal even across streamed .decode() calls
    hasher = isAllowlistCandidate ? crypto.createHash("sha256") : null
    bytesRead = 0
    overlapTail = Buffer.alloc(0)                          # last (L-1) bytes of the previous chunk
    matched = false
    decodeOk = true                                        # only consulted for allowlist candidates

    for chunk of stream:
      bytesRead += chunk.length
      if bytesRead > TOP_LEVEL_FILE_BYTE_LIMIT or globalBudget.exceededBy(chunk.length):
        stream.destroy(); → UNINSPECTABLE (Outcome B)        # unconditional, same shared budget

      if hasher: hasher.update(chunk)                        # incremental digest, same read as below

      searchable = concat(overlapTail, chunk)
      if searchable contains, anywhere, any standard ZIP record signature
         (PK\x03\x04, PK\x01\x02, PK\x05\x06, PK\x06\x06/PK\x06\x07, PK\x07\x08):
        stream.destroy(); → UNINSPECTABLE (Outcome B)          # unconditional, even for a candidate

      # Revision 14, finding 2's "guard" fix: the control-byte check now lives INSIDE the same try
      # block as the decode call, so a decode failure can never leave `decodedChunk` stale or
      # undefined for this check to read — one exception type, one handler, below.
      try:
        decodedChunk = decoder.decode(chunk, { stream: true })  # incremental; fatal on invalid bytes
        if decodedChunk contains any C0 control byte outside {TAB, LF, CR}, or DEL (\x7F):
          throw new ControlByteViolation()
      catch (decode error OR ControlByteViolation):
        if isAllowlistCandidate: decodeOk = false              # keep streaming — the digest may
                                                                 # still authenticate this file
        else: stream.destroy(); → UNINSPECTABLE (Outcome B)     # non-candidate: abort immediately

      if searchable matches any of the 4 sentinel variants: matched = true
      overlapTail = last (L-1) bytes of chunk

    # Stream ended without a ZIP-signature/budget abort. Revision 14, finding 2's core fix: flush
    # the decoder UNCONDITIONALLY — for every file, not only non-candidates (Revision 13's bug: an
    # allowlist candidate skipped this entirely, so a file ending in an incomplete multi-byte
    # sequence, e.g. a lone 0xE2, silently decoded to "" mid-stream and never got caught — confirmed
    # with a direct Node execution before writing this fix).
    try:
      flushedTail = decoder.decode()                          # flush; throws on an incomplete
                                                                 # trailing multi-byte sequence
      if flushedTail contains any C0 control byte outside {TAB, LF, CR}, or DEL (\x7F):
        throw new ControlByteViolation()
    catch (flush error OR ControlByteViolation):
      decodeOk = false                                        # safe unconditionally: for a
                                                                 # non-candidate this is checked
                                                                 # immediately below anyway

    if not isAllowlistCandidate:
      → decodeOk ? (matched ? Outcome A (delete + placeholder) : clean, leave as-is) : UNINSPECTABLE (Outcome B)

    # Allowlist candidate: the digest is now complete.
    digest = hasher.digest("hex")
    allowlistEntry = KNOWN_PLAYWRIGHT_REPORT_ASSETS[canonicalPath]
    if allowlistEntry exists and allowlistEntry.sha256 === digest:
      → matched ? Outcome A (delete + placeholder) : clean, leave as-is   # authenticated: decodeOk
                                                                             # is irrelevant here
    else:
      # Canonical/candidate-shaped path, but no digest match (tampered, extra file, or a Playwright
      # bump the manifest wasn't regenerated for — the CI-enforced exhaustive check, below, is what
      # should have caught this before merge). Falls through: only decodeOk saves it now.
      → decodeOk ? (matched ? Outcome A (delete + placeholder) : clean, leave as-is) : UNINSPECTABLE (Outcome B)
```

`TOP_LEVEL_FILE_BYTE_LIMIT` is a frozen threshold (below). Applied to the entry [17] example:
`"PK\x03\x04abc"` matches the local-file-header signature in its very first (and only) chunk → the
stream is destroyed and it routes to Outcome B on the first iteration, for every candidate,
allowlisted or not. This still correctly classifies: a renamed valid ZIP (`yauzl` opens it
regardless of extension); ordinary UTF-8 text containing ZIP-like bytes mid-file but no full
ZIP-record signature and no disallowed control bytes (correctly TEXT); a mislabeled binary that is
not an authenticated allowlist match (uninspectable via signature, decode-error, or control-byte
checks — Revision 12's restoration, unchanged); a genuine `trace/`-relative package asset whose
canonical path and digest are authenticated (clean, scanned, exempt only from the decodability
requirement — Revision 13's corrected path convention); and a successfully parsed prepended/
preamble ZIP (handled entirely within the `yauzl.open()` success branch, never reaching this
fallback at all).

**The authenticated package-asset allowlist.** `known-playwright-report-assets.json` (checked in,
alongside `sanitize.js`) records `{ playwrightTestVersion, assets: [{ path, sha256 }, ...] }`, where
every `path` is validated by `validateManifestTracePath` (above — the raw-string validator, not
`toCanonicalTracePath` directly; Revision 14 incorrectly described the latter as sufficient for
manifest input, which entry [41] disproved) before the lookup set is even constructed, and `sha256`
is exactly 64 lowercase hex characters, unique per path, with no unexpected fields. Generated by a
script run against the resolved `node_modules/@playwright/test` install at the version
`frontend/package-lock.json` currently locks (`1.59.0` at time of writing — 16 files under `trace/`,
3 requiring the allowlist, per A-IG.7 Part 4).

**Independently authenticated, not self-certifying (entry [37] finding 1):** a CI-enforced check
extends the generated-Playwright canary — it produces a real report at the exact locked producer
version, derives the canonical `{path, sha256}` set directly from that real output, and requires
**exact set equality** against the checked-in manifest (no missing, extra, duplicate, or drifted
entry). This replaces Revision 12's version-string-only check, which a manifest edit could satisfy
while adding an arbitrary, unauthenticated entry.

**`playwrightTestVersion` bound to the lockfile, not decorative (Revision 14, entry [39] finding
3).** The same CI check additionally requires `manifest.playwrightTestVersion ===` the exact
version `frontend/package-lock.json` resolves for `@playwright/test`, **and** that `playwright` and
`playwright-core` resolve to that identical version too (confirmed this revision, by reading
`frontend/package-lock.json` directly: all three already agree at `1.59.0`) — the "one producer
version, one fixed asset set" assumption the whole mechanism depends on is meaningless if the three
packages could diverge, so divergence fails the guard closed rather than trusting whichever one the
manifest happens to declare. Any generation, schema, comparison, or version-binding error fails the
guard closed.

**New frozen threshold:**

| Limit | Value | Justification |
|---|---|---|
| Top-level (non-archive) file byte limit | 50 MB | Matches the existing per-entry limit — a standalone file is not fundamentally different in nature from a single archive entry, and real top-level files observed (`error-context.md`, `index.html`, report `.md` attachments) are KB-scale; one named constant reused in both places, not a second unexplained number |

**Complete metadata and raw-byte coverage (finding 2), once a file is `ZIP-CANDIDATE-CONFIRMED`:**

`yauzl` 3.4.0's own documentation, fetched this revision, states precisely what it exposes and what
it explicitly ignores:

| Exposed (must be scanned) | Explicitly NOT read by `yauzl` (must be covered another way) |
|---|---|
| `Entry.fileName`/`fileNameRaw` (decoded/raw) | Local File Header contents ("yauzl ignores the content of the Local File Header") |
| `Entry.fileComment`/`fileCommentRaw` (decoded/raw) | Data Descriptors ("This library provides no support for finding or interpreting them") |
| `Entry.extraFieldRaw` (raw `Buffer`) and `Entry.extraFields` (parsed known types: ZIP64, Info-ZIP Unicode Path, Info-ZIP timestamp, NTFS) | Archive Extra Data Record ("no support for finding or interpreting it") |
| `ZipFile.comment` (archive-level, CP437-decoded, or raw `Buffer` if opened with `decodeStrings: false`) | ZIP64 Extensible Data Sector ("ignored by this library") |
| Entry content (streamed, decompressed) | CRC-32 ("not used for anything in this library" — computed independently, see below) |

**Design: two complementary scans, not one.** (1) The **structured scan** — every value in the
left column above, decoded and raw where both exist — is searched against all four sentinel
variants, giving precise per-entry/per-field attribution and complete coverage of what `yauzl`
parses. (2) **Additionally**, the **raw archive file, streamed** (corrected this revision — see
below), is searched for all four variants — this is not decompression-aware (compressed entry
content is not recoverable this way without inflating it, which the structured per-entry scan
already handles), but it gives complete, format-agnostic coverage of every region in the right
column that `yauzl` never parses or exposes at all (Local File Headers, Data Descriptors, Archive
Extra Data Record, ZIP64 Extensible Data Sector) — regions a configured secret could theoretically
occupy without the structured scan ever seeing them. A match via **either** scan is Outcome A (the
archive is fully covered between the two methods, so this remains "a match in a
successfully-inspected format," not an uninspectable-format case).

**Correction, Revision 12 (entry [35]/entry [9]): scanning entry content is not the same as
proving it inspectable.** The two complementary scans above give complete *coverage* of every byte
`yauzl` exposes, but a raw byte-pattern search finding nothing is not proof a binary entry's
*decoded meaning* (a rendered image, an embedded font, a compressed stream) doesn't carry the
secret in some form other than a literal byte match — exactly the gap entry [9] identified
("no image exception is acceptable") and Revision 10/11 lost by treating "scanned" and "provably
safe" as equivalent. **Entry content must therefore also pass the same strict-UTF-8-decodability
test `classify()`'s top-level fallback applies** before the "no match found → leave as-is" outcome
is available to it. An entry that decompresses to strictly-valid UTF-8 text is scanned as above and
either matched (Outcome A) or clean (leave as-is, that entry only). An entry that does **not**
decode as strict UTF-8 — a screenshot, a font, any other binary payload — makes the **whole
containing archive** Outcome B, regardless of what the raw-byte scan found in it, unless the entry
is itself a nested valid ZIP (recursed into, same rule applied one level deeper, up to the existing
5-level cap). This is why the Layer 1 extension above (`screenshots: false`) matters in practice:
without it, every real trace.zip's screenshot entry would make every real trace.zip Outcome B.

**Corrected: the raw scan must stream, not load the whole archive into memory (entry [17]
finding 4).** Reading the entire archive file in one `readFileSync`-style call before scanning
would allocate up to the full per-archive/global size limit in one shot, *before* any of the
streamed hostile-input controls elsewhere in this design get a chance to abort early — defeating
their purpose for this one code path. Fixed: the raw scan reads the archive via
`fs.createReadStream(path, { highWaterMark: 1 MiB })`, searching each chunk as it arrives, and:

- **Shares the same per-file/per-run byte budget** as the structured per-entry streaming scan —
  every byte read by the raw scan counts against the identical frozen limits (50 MB per-entry-scale
  unit is not directly applicable here since this is whole-file, so the raw scan is bounded by the
  same 200 MB per-archive / 1 GB global ceiling that already governs the archive as a whole; reading
  past either aborts the scan exactly as a structured-scan violation would, per the state machine
  above).
- **Retains a fixed overlap between consecutive chunks** so a sentinel variant split across a chunk
  boundary is never missed: before searching chunk *N+1*, the last `(L − 1)` bytes of chunk *N* are
  prepended to it, where `L` is the length of the *longest* configured variant being searched for
  (the URL-percent-encoded form of a multi-byte-Unicode-containing secret is typically the longest
  of the four). This is a standard sliding-window technique — searching only within each chunk
  independently would silently miss a match whose bytes straddle the boundary.
- **A raw-scan match is Outcome A** (same disposition as a structured-scan match — see above); a
  raw-scan stream error (truncated read, filesystem error) is Outcome B, consistent with every
  other scanner-error case in the state machine.

**Regression fixtures:** a renamed valid ZIP; ordinary UTF-8 text containing ZIP-like bytes mid-file
but no full ZIP-record signature and no disallowed control bytes (must classify as TEXT); **the
entry [17] truncated-local-header fixture** (`50 4b 03 04 61 62 63` — must be Outcome B, whole-
upload failure, never a clean text pass); **a top-level mislabeled binary with no sentinel match and
no ZIP-signature evidence, that is not an allowlist match** (e.g. a JPEG renamed `.json` — must be
Outcome B, **restored in Revision 12** after Revision 11 incorrectly passed this case clean); **a
gzip-compressed sentinel** (entry [35]'s counterexample — `zlib.gzipSync` of the sentinel, 36 bytes,
matches none of the raw/JSON/URL-encoded needles and no ZIP signature — must be Outcome B via the
restored strict-UTF-8-decode-failure path, proving the fail-open Revision 11 introduced is closed);
a self-extracting-style ZIP with a prepended non-ZIP payload (must still classify as ZIP via
`yauzl`'s EOCD scan); a sentinel present only in the archive-level `ZipFile.comment`; a sentinel
present only in an entry's `extraFieldRaw`; a sentinel present only in a region `yauzl` never parses
(simulated by embedding it in a hand-crafted Local File Header or Data Descriptor region, caught
only by the raw streamed scan, on an otherwise fully-text/inspectable archive); **a sentinel
deliberately split across a raw-archive-scan chunk boundary** and, separately, **a sentinel and a
ZIP-record signature each deliberately split across a top-level-classifier chunk boundary**; **an
ordinary UTF-8 top-level file exceeding `TOP_LEVEL_FILE_BYTE_LIMIT`** (must abort mid-stream as
Outcome B); unsupported-compression and encrypted ZIPs; **a ZIP containing one binary (non-UTF-8-
decodable), non-allowlisted entry alongside otherwise-clean text entries — including the case where
the binary entry is the one carrying a raw-byte-detectable match, and the case where it carries no
detectable match at all — both must be Outcome B** (**Revision 12: restored** — reasserts entry
[9]'s "no image exception" ruling; Revision 10/11 treated the no-match case as a clean pass, which
entry [9] never authorized); a top-level file whose bytes contain ZIP-record-signature evidence
without being a fully-parseable ZIP (still Outcome B — the entry [17] fix, unaffected); **the two
real, `1.59.0`-locked `@playwright/test` HTML-report assets from A-IG.7 (`trace/codicon.DCmgc-
ay.ttf`, `trace/assets/defaultSettingsView-GTWI-W_B.js`) presented WITH a correct allowlist entry
(path+digest match) — must classify as clean, scanned but passing** (Revision 12's provable
exception); **the same two files presented WITHOUT a matching allowlist entry (simulating a
Playwright version bump the manifest wasn't regenerated for), and, separately, WITH a matching path
but a deliberately wrong digest (simulating tampering) — both must be Outcome B**, proving the
allowlist fails closed on drift rather than trusting path alone; **`toCanonicalTracePath` fixtures
(scan-time, resolved filesystem paths — Revision 14, entry [39] finding 1):** positives —
`trace/codicon.DCmgc-ay.ttf` and a nested `trace/assets/x.js` both resolve to themselves; negatives
— `trace/../evil.bin` (resolves outside `trace/`, must return `null`),
`playwright-report/trace/codicon.DCmgc-ay.ttf` (the Revision-12 aggregate-root shape), an absolute
path, a path with a literal backslash segment, and a sibling-root escape
(`../other-staging-dir/trace/x`) — every negative must return `null` and must never reach the
allowlist lookup; a dedicated fixture asserts the *argument order* itself — the real production
example (`stagingDirRoot=/staging`, `filePath=/staging/trace/x`) resolves to `"trace/x"`, not
`"../.."`; **`validateManifestTracePath` fixtures (raw manifest strings — Revision 15, entry [41],
the residual gap Revision 14 missed):** the exact two strings a fresh Node execution proved silently
alias a legitimate entry once resolved — **`trace/./evil.bin`** and **`trace/a/../evil.bin`** — both
resolve to the identical filesystem path a genuine `trace/evil.bin` manifest entry would, so both
must be rejected by the raw-segment check *before* resolution ever runs, and a fixture asserts this
directly: `path.resolve(stagingDirRoot, "trace/./evil.bin") ===
path.resolve(stagingDirRoot, "trace/evil.bin")` (proving the aliasing is real, not hypothetical),
while `validateManifestTracePath("trace/./evil.bin", stagingDirRoot)` must still return `null`, not
the aliased canonical value; also a raw absolute path, a raw backslash segment, and a raw NUL/control
byte — each must also return `null`. **The raw-vs-canonical equality check is a defense-in-depth
invariant, not independently exercised by a dedicated mismatch fixture (corrected, entry [43]):**
`path.resolve`/`path.relative` are purely lexical string operations — confirmed by direct execution
that a symlink-shaped `stagingDirRoot` produces an identical result to any other root spelling, since
neither function touches the filesystem or dereferences anything — so no fixture can make a raw
value that already passed the segment checks disagree with its own resolved-and-canonicalized form;
that branch is unreachable given the current algorithm, not untested by omission. Coverage instead
comes from a **property/table test**: for every accepted raw path in the positive fixture set above,
assert `validateManifestTracePath(raw, stagingDirRoot) === raw` (the equality check passes, as it
always will); for every rejected raw path in the negative fixture set, assert the function returns
`null` *before* reaching the equality check at all — exhaustively covering the function's real
decision surface without asserting a state the algorithm cannot produce; a real trace.zip generated
with `screenshots: false` (A-IG.7) — must classify as fully clean with **zero** entries requiring the
allowlist or triggering Outcome B; **manifest schema fixtures (entry [37] finding 1, path validation
now delegated to `validateManifestTracePath`, not `toCanonicalTracePath`):** a manifest entry with a
non-canonical path, a malformed digest (wrong length, uppercase, non-hex), and a duplicate path —
each must fail the schema-validation guard before any lookup is attempted; **exhaustive-manifest
fixtures:** a checked-in manifest with one entry added beyond what the real, locked-version producer
actually ships, and, separately, one real producer asset omitted from the checked-in manifest — both
must fail the CI-enforced canary-derived exact-equality check (entry [37] finding 1); **version-
binding fixtures (entry [39] finding 3):** a manifest whose `playwrightTestVersion` doesn't match
`frontend/package-lock.json`'s locked `@playwright/test` resolution, and, separately, a simulated
lockfile state where `playwright`/`playwright-core` resolve to a different version than
`@playwright/test` — both must fail the CI check closed, proving the version field is enforced, not
decorative; **incomplete-trailing-sequence fixture (entry [39] finding 2):** a canonical
`trace/incomplete.txt` with no matching manifest digest, whose content ends in a lone `0xE2` byte
(the exact byte a fresh Node execution proved decodes to `""` mid-stream and only throws on the
omitted flush) — must be Outcome B, proving the decoder is now flushed unconditionally rather than
only for non-candidates; **single-pass fixture (entry [37] finding 2, still enforced under the
Revision 14 restructuring):** an instrumented test double confirms `classify()` opens exactly one
read stream per allowlist-candidate file; the fail-closed path (pass 2 finds anything → fails, never
"fixes" it); the **generated-Playwright canary** — the actual A-IG.1/A-IG.3/A-IG.7 dummy fixtures
with `screenshots: false`, invoked as two **separate** sanitizer runs against the canary's own
generated `playwright-report/` and `test-results/` roots (matching real production's per-site
`source-dir`, never an aggregate root), re-run as a standing regression so a future Playwright
version change is caught by CI.

#### Hostile-archive limits (streamed, CRC-checked, globally budgeted, symlink-strict — unchanged from Revision 4, accepted)

1. **Streamed per-entry limits**, enforced live against `yauzl`'s decompressed byte stream
   (aborted mid-read on violation), not read from central-directory metadata alone.
   `validateEntrySizes` additionally catches a declared-vs-actual mismatch before streaming starts.
2. **CRC-32**, computed via Node's built-in `zlib.crc32()` (confirmed this revision: it accepts an
   optional prior value, `zlib.crc32(chunk, previousCrc)`, so it can be applied incrementally as
   each streamed chunk arrives) against `entry.crc32`; end-of-stream length is also validated.
3. **Global per-invocation budget**, shared across both the sanitize and verify passes, across
   every archive and file, enforced against bytes actually written during nested-archive
   extraction.
4. **All symlinks rejected unconditionally** — including ones resolving inside the source tree,
   and during the initial source copy (Outcome B, per the state machine above — not skipped).
5. **Path traversal, duplicate entries** rejected; **nesting capped at 5 levels; entry count capped
   at 5,000 per archive.**

**Frozen numeric thresholds:**

| Limit | Value | Justification |
|---|---|---|
| Per-entry uncompressed size | 50 MB | Real entries observed: ~13 KB max; ~4,000× headroom |
| Per-archive total uncompressed size | 200 MB | Real archives observed: ~50–60 KB; ~3,500× headroom |
| Global per-invocation budget | 1 GB | A conservative fixed ceiling from observed sizes alone — not a claim about free disk space on the runner |
| Compression ratio | 100:1 | Standard zip-bomb heuristic; realistic content rarely exceeds 10–20:1 |
| Nesting depth | 5 levels | Real nesting observed: 1 level |
| Entry count per archive | 5,000 | Real entry counts observed: ~15 |
| Top-level (non-archive) file byte limit | 50 MB | Same value and reasoning as per-entry, above — see the classifier's streaming fallback gate |

Frozen; `tasks.md` implements these numbers, does not re-open them.

#### Path and scratch-space safety (unchanged from Revision 4, accepted)

`source-dir` must canonicalize inside `$GITHUB_WORKSPACE`; `staging-dir` must be a fresh
non-symlink child of `$RUNNER_TEMP`, distinct from source and workspace, validated before any
copy/delete; nested-archive scratch lives beneath a freshly created, uniquely-named
`$RUNNER_TEMP` subdirectory. Missing `source-dir` → empty `staging-dir`, trivially clean; there is
no code path that ever uploads anything other than `staging-dir`.

#### The composite action — complete, executable step sequence (finding 3)

**Corrected: Revision 4 showed only a single `node` invocation step, without the setup-node,
install, or audit steps it claimed elsewhere in prose.** Full sequence, with an explicit
action-local working directory so `npm ci` never touches the repository root (which has no
top-level `package.json` — `frontend/` and each Gradle module have their own):

```yaml
# .github/actions/sanitize-playwright-artifacts/action.yml
inputs:
  source-dir: { required: true }
  staging-dir: { required: true }
  mode: { required: true }        # 'live-secret' or 'fallback-only'
  e2e-password: { required: false }
runs:
  using: "composite"
  steps:
    - name: Set up Node for the sanitizer
      uses: actions/setup-node@v4
      with:
        node-version: "22"        # zlib.crc32() requires >= 20.15/22.2

    - name: Install sanitizer dependencies
      shell: bash
      working-directory: ${{ github.action_path }}
      run: npm ci --ignore-scripts

    - name: Audit sanitizer dependencies
      shell: bash
      working-directory: ${{ github.action_path }}
      run: npm audit --omit=dev --audit-level=low

    - name: Run sanitizer
      shell: bash
      working-directory: ${{ github.action_path }}
      env:
        SANITIZE_SOURCE_DIR: ${{ inputs.source-dir }}
        SANITIZE_STAGING_DIR: ${{ inputs.staging-dir }}
        SANITIZE_MODE: ${{ inputs.mode }}
        SANITIZE_E2E_PASSWORD: ${{ inputs.e2e-password }}
      run: node "${{ github.action_path }}/sanitize.js"
```

None of these four steps sets `continue-on-error` — a non-zero exit from **any** of them (Node
setup failure, `npm ci` network/integrity failure, an audit finding at or above the configured
threshold, or the sanitizer itself detecting Outcome B) fails the composite action as a whole,
which the calling workflow observes as `steps.sanitize.outcome != 'success'` — the same gate used
everywhere else in this design. `sanitize.js` resolves `SANITIZE_SOURCE_DIR`/`SANITIZE_STAGING_DIR`
relative to `process.env.GITHUB_WORKSPACE` explicitly (not relative to its own `process.cwd()`,
which is `${{ github.action_path }}` per the `working-directory` above) — so a caller-supplied
relative path like `frontend/playwright-report` resolves against the checkout root regardless of
where the sanitizer script itself runs from. `package.json`/`package-lock.json` (pinning
`yauzl@3.4.0`) live in `.github/actions/sanitize-playwright-artifacts/` alongside `sanitize.js` and
`action.yml`.

**Audit threshold — `--audit-level=low`, corrected from `high` (entry [17] finding 2).** `npm
audit --audit-level=X` only fails on findings at or above `X`; `high` would not have caught the
moderate-severity `js-yaml` prototype-pollution advisory that motivated this correction in the
first place. `moderate` is the minimum that would have caught both advisories cited against this
design, but this sanitizer's entire purpose is preventing a credential leak — the fail-closed
dependency policy this document adopts is the strictest defensible one: **any** advisory against a
production dependency (`--omit=dev` already excludes dev-only tooling) blocks the step. `low` is
that policy's unambiguous expression, not merely "high enough to have caught this one incident."

#### Sentinel transport — action-owned, mode-split (unchanged from Revision 4, accepted)

```js
// sanitize.js — action-owned, not caller-declared
const KNOWN_NON_SECRET_LITERALS = [
  "local-dev-password-2026",  // helpers/browser-auth.ts:27, helpers/api.ts:17
  "TestPassword123!",          // ai-insights.spec.ts fallback literal
  "e2e-test-password-2026",    // login.spec.ts / aws-synthetic.spec.ts / live-contract.spec.ts fallback
];
const mode = process.env.SANITIZE_MODE;
const e2ePassword = process.env.SANITIZE_E2E_PASSWORD || "";
if (mode === "live-secret") {
  if (e2ePassword.trim() === "") { fail("mode=live-secret requires a non-empty e2e-password"); }
} else if (mode === "fallback-only") {
  if (e2ePassword.trim() !== "") { fail("mode=fallback-only must not receive e2e-password"); }
} else {
  fail(`mode must be 'live-secret' or 'fallback-only', got: ${mode}`);
}
const sentinelValues = [...(e2ePassword ? [e2ePassword] : []), ...KNOWN_NON_SECRET_LITERALS];
```
`fail(msg)` logs `::error::${msg}` and `process.exit(1)` — an Outcome B condition. Callers:
```yaml
# synthetic-monitoring.yml (both jobs)
with: { mode: live-secret, e2e-password: ${{ secrets.E2E_TEST_USER_PASSWORD }}, ... }
# frontend-ci.yml, frontend-e2e-integration.yml, ci-verification.yml
with: { mode: fallback-only, ... }   # e2e-password intentionally omitted
```
No sentinel value is ever a CLI argument, a `$GITHUB_OUTPUT`/`$GITHUB_STEP_SUMMARY`/filename
value, or embedded in a placeholder message; `sanitize.js` reads `process.env` only; `set -x` is
never used.

#### Independent static guard — pinned safe parser, complete non-self-certifying, fail-closed, identity-bound validation (entry [15] finding 4; entry [17] findings 2/3; entry [19] findings 1/3; entry [21] findings 1/2; entry [23] findings 1/2/3; entry [25] findings 1/2)

**A targeted regex cannot substitute for parsing GitHub Actions YAML** (job-level `env`,
flow/multiline syntax, anchors/aliases, reusable-action `with:` blocks all defeat a regex
reliably). **Selected: Node.js + [`js-yaml`](https://www.npmjs.com/package/js-yaml) `4.3.0`**
— corrected this revision from `4.1.0`, which entry [17] found is affected by both a moderate
prototype-pollution advisory (fixed in 4.1.1) and a high-severity quadratic merge-key CPU-exhaustion
advisory; `4.3.0` backports `maxTotalMergeKeys`, giving an explicit resource bound to configure
(below) on top of the algorithmic fixes already in `4.2.0`. Staying on the `4.x`/CommonJS interface
(rather than jumping to a newer major) avoids an unnecessary API-shape change; this is consistent
with `js-yaml`'s own toolchain choice, not Python + PyYAML, for which this repository's scripts have
no existing precedent. Loaded as:

```js
const yaml = require("js-yaml");
const doc = yaml.load(fileContents, { maxTotalMergeKeys: 100 });   // this repo's workflow YAML uses
                                                                     // no merge keys at all; 100 is
                                                                     // generous headroom, not a
                                                                     // legitimacy assumption
```

`scripts/check-sanitizer-secret-wiring.js`, run as a new static-validation step (part of IV.4's
coverage). Its own `package.json`/`package-lock.json` (pinning `js-yaml@4.3.0`) live under
`scripts/`, installed via the identical `npm ci --ignore-scripts` + `npm audit --audit-level=low`
discipline as the sanitizer's own dependency (entry [13] finding 4, applied consistently to both
Node-based tools in this design, not just one) — this script also parses branch-controlled,
potentially-adversarial input (workflow YAML a PR could modify) and deserves the same rigor.

**Exact local-action identity — `usesSanitizerAction`, precisely defined (entry [19] finding 3):**

```js
const SANITIZER_ACTION_PATH = "./.github/actions/sanitize-playwright-artifacts";
function usesSanitizerAction(step) {
  if (!step?.uses) return false;
  return step.uses.replace(/\/+$/, "") === SANITIZER_ACTION_PATH;   // exact match, not substring —
}                                                                     // a local action ref has no
                                                                       // @version suffix to strip
```

**Upload classification — a checked-in, exhaustively-diffed manifest, not a heuristic (entry [19]
finding 3).** `isPlaywrightShapedUpload` was referenced in every prior revision but never defined —
making A2.6/A-IG.5's coverage claim unenforceable. A path-substring heuristic (`"playwright-report"`/
`"test-results"` in the path) is also the wrong shape: it can silently misclassify a future upload
either direction with no signal that it happened. Fixed: `scripts/playwright-upload-manifest.json`,
a checked-in, human-reviewed file mapping every `actions/upload-artifact` site — by the **stable
triple** `{workflow, job, stepId}` — to `playwright: true | false`. Every existing upload-artifact
step across the 14 known sites is given an explicit `id:` (a small, low-risk addition to each
workflow, alongside this guard's introduction) so the triple is stable across unrelated edits to a
step's `name:` or ordering.

**Corrected this revision (entry [21] finding 2): the manifest is validated before it is trusted for
set comparison, not after.** `manifest.find()` silently returns only the *first* match for a
duplicated triple, `Set` silently collapses duplicate keys (hiding a discovery-side collision too),
and a missing `playwright` field reads as `undefined` — falsy, so it would previously have been
silently treated the same as `playwright: false` and exempted from sanitizer requirements. All of
this must fail closed *before* any set is built.

**Corrected again this revision (entry [23] finding 1): the workflow-path check was a weak blacklist
(only rejecting backslashes and empty strings), not a canonical form.** A fresh execution of it
accepted `../outside.yml`, `/.github/workflows/x.yml`, `C:/x.yml`,
`.github/workflows/a/../x.yml`, and `.github/workflows//x.yml` — none of these are canonical, and
the discovery loop separately never normalized a platform-native `\` path before use. **Fixed: one
strict allowlist pattern, not a blacklist of bad shapes to enumerate, used identically for both
sides of the comparison** — a manifest path must already be exactly this shape, and every
discovered path is constructed to be exactly this shape from the start, so no separate "normalize
then compare" step is needed on the discovery side at all:

```js
// One canonical form, one regex, actually invoked on both the manifest validator and workflow
// discovery — not just claimed shared. Deliberately an allowlist of the only acceptable shape, not
// a blacklist of '..'/drive-letter/double-slash variants to enumerate — a strict allowlist can't
// be bypassed by a shape nobody thought to blacklist.
//
// Corrected this revision (entry [25] finding 1): [^/]+ excluded only '/', not '\' — a fresh check
// showed '.github/workflows/x\y.yml' matched, since a backslash inside the filename segment isn't
// a forward slash. [^/\\]+ excludes both separator characters from that segment.
const WORKFLOW_PATH_PATTERN = /^\.github\/workflows\/[^/\\]+\.ya?ml$/;
function isCanonicalWorkflowPath(s) {
  return typeof s === "string" && WORKFLOW_PATH_PATTERN.test(s);
}

// Every entry [23] counter-example fails this pattern: '../outside.yml' and 'C:/x.yml' don't start
// with '.github/workflows/'; '/.github/workflows/x.yml' has a leading '/' the anchored '^\.' rejects;
// '.github/workflows/a/../x.yml' and '.github/workflows//x.yml' both contain a '/' where '[^/\\]+'
// (a single path segment, no further separators of either kind) requires there be none; and, as of
// this revision, '.github/workflows/x\y.yml' is rejected the same way for the same reason.

function listWorkflowFiles() {
  // Corrected this revision (entry [25] finding 1): Revision 9 claimed this was "canonical by
  // construction" but never actually called the validator — the shared function existed but was
  // wired to only one of the two sides it was supposed to guard. Every constructed key is now
  // explicitly passed through the identical isCanonicalWorkflowPath the manifest uses, so a mocked
  // fs.readdirSync result containing a hostile basename (e.g. 'x\y.yml') fails here rather than
  // being trusted on the assumption that construction alone was safe.
  const basenames = fs.readdirSync(".github/workflows").filter(f => /\.ya?ml$/.test(f));
  const result = [];
  for (const basename of basenames) {
    const key = `.github/workflows/${basename}`;
    if (!isCanonicalWorkflowPath(key)) {
      fail(`discovered workflow entry does not produce a canonical path: ${JSON.stringify(basename)}`);
      continue;
    }
    result.push(key);   // an ordinary 'x.yml' basename produces exactly '.github/workflows/x.yml'
  }
  return result;
}
```

**Extended this revision (entry [25] finding 2) with a per-upload `baseline` field** — see below for
why: `playwright: true` entries now require it, `playwright: false` entries must not have it.

```js
function validateManifestSchema(manifest) {
  if (!Array.isArray(manifest)) fail("manifest must be a top-level JSON array");
  const seen = new Set();
  for (const entry of manifest) {
    const keys = Object.keys(entry).sort().join(",");
    const isPlaywrightShape = keys === "baseline,job,playwright,stepId,workflow";
    const isNonPlaywrightShape = keys === "job,playwright,stepId,workflow";
    if (!isPlaywrightShape && !isNonPlaywrightShape) {
      fail(`manifest entry has unexpected/missing fields: ${JSON.stringify(entry)}`); continue;
    }
    if (!isCanonicalWorkflowPath(entry.workflow)) {
      fail(`manifest entry's workflow path is not canonical (.github/workflows/<file>.yml, no '..'/drive/backslash/double-slash): ${JSON.stringify(entry)}`);
    }
    if (typeof entry.job !== "string" || entry.job === "") fail(`manifest entry has an empty/invalid job id: ${JSON.stringify(entry)}`);
    if (typeof entry.stepId !== "string" || entry.stepId === "") fail(`manifest entry has an empty/invalid stepId: ${JSON.stringify(entry)}`);
    if (typeof entry.playwright !== "boolean") {
      fail(`manifest entry's 'playwright' field must be a literal boolean, got ${JSON.stringify(entry.playwright)}: ${JSON.stringify(entry)}`);
    }
    if (entry.playwright === true && entry.baseline !== "always" && entry.baseline !== "failure") {
      fail(`manifest entry classified playwright:true must have baseline exactly 'always' or 'failure': ${JSON.stringify(entry)}`);
    }
    if (entry.playwright === false && isPlaywrightShape) {
      fail(`manifest entry classified playwright:false must not carry a baseline field: ${JSON.stringify(entry)}`);
    }
    const key = `${entry.workflow}:${entry.job}:${entry.stepId}`;
    if (seen.has(key)) fail(`manifest contains a duplicate/conflicting entry for ${key}`);
    seen.add(key);
  }
}

function validateNoDuplicateDiscovered(discovered) {
  const seen = new Set();
  for (const d of discovered) {
    const key = `${d.workflow}:${d.job}:${d.stepId}`;
    if (seen.has(key)) fail(`discovered a duplicate upload-artifact step id within one job: ${key}`);
    seen.add(key);
  }
}

const manifest = JSON.parse(readFileSync("scripts/playwright-upload-manifest.json", "utf8"));
validateManifestSchema(manifest);                          // fail closed before anything below trusts it
const manifestKeys = new Set(manifest.map(e => `${e.workflow}:${e.job}:${e.stepId}`));

const discovered = [];
for (const workflowFile of listWorkflowFiles()) {
  const doc = yaml.load(readFileSync(workflowFile, "utf8"), { maxTotalMergeKeys: 100 });
  for (const [jobId, job] of Object.entries(doc.jobs ?? {})) {
    for (const step of job.steps ?? []) {
      if ((step.uses ?? "").startsWith("actions/upload-artifact@")) {
        if (!step.id) { fail(`${workflowFile}:${jobId}: upload-artifact step has no id:`); continue; }
        discovered.push({ workflow: workflowFile, job: jobId, stepId: step.id, step, jobObj: job });
      }
    }
  }
}
validateNoDuplicateDiscovered(discovered);                  // fail closed before deduplicating via Set

// Exhaustive discovered-vs-classified diff, both directions — the repository-derived inventory
// rule (A-IG.5) enforced structurally, not treating the 14/5/9 snapshot as permanently exhaustive.
const discoveredKeys = new Set(discovered.map(d => `${d.workflow}:${d.job}:${d.stepId}`));
for (const key of discoveredKeys) {
  if (!manifestKeys.has(key)) fail(`Upload site ${key} is not in the manifest — classify it before merging`);
}
for (const key of manifestKeys) {
  if (!discoveredKeys.has(key)) fail(`Manifest entry ${key} no longer matches any discovered upload — remove or update it`);
}

for (const { workflow, job: jobId, stepId, step, jobObj } of discovered) {
  const classified = manifest.find(e => e.workflow === workflow && e.job === jobId && e.stepId === stepId);
  if (!classified.playwright) continue;   // non-Playwright uploads are known, tracked, and exempt —
                                            // classified.playwright is now guaranteed a real boolean
  const steps = jobObj.steps;
  const i = steps.indexOf(step);
  const predecessor = steps[i - 1];
  if (!predecessor || !usesSanitizerAction(predecessor)) {
    fail(`${workflow}:${jobId}: Playwright upload '${stepId}' has no immediate sanitizer predecessor`);
    continue;
  }
  if (step.with?.path !== predecessor.with?.["staging-dir"]) {
    fail(`${workflow}:${jobId}: upload path is not the sanitizer's staging-dir`);
  }
  if (!isProvenConjunctiveGate(step.if, predecessor.id, classified.baseline)) {
    fail(`${workflow}:${jobId}: upload 'if:' is not exactly '${classified.baseline}() && steps.${predecessor.id}.outcome == \\'success\\''`);
  }
}
```

**Corrected this revision (entry [23] finding 3): substring presence does not prove the predicate is
conjunctive.** The prior regex only checked that the gate substring appeared *somewhere* in the
`if:` string. A fresh check against three plausible edits — `always() || steps.sanitize.outcome ==
'success'`, `!(steps.sanitize.outcome == 'success')`, and `steps.sanitize.outcome == 'success' ||
failure()` — showed the regex accepts all three, yet each can run the upload when sanitization did
*not* succeed (an `||` with an always-true/failure-triggered alternative, or an outright negation).
**Fixed: an exact-shape allowlist, not a blacklist of `||`/`!` variants to enumerate** — a
blacklist can always be evaded by a shape nobody thought to list; an allowlist of the only two
intended forms cannot, by construction, accept anything else.

**Corrected again this revision (entry [25] finding 2): an allowlist of *both* shapes for every
upload is not the same as proving the *correct* shape for that specific upload.** Revision 9's
`isProvenConjunctiveGate` took no upload identity or expected eligibility, so a
`synthetic-monitoring.yml` job's `always()`-baseline upload silently accepted a `failure()`-shaped
condition (or the reverse) — a real drift the gate exists to catch, undetected. Entry [23] itself
required the exact shape *assigned to that stable upload identity*, not "either of the two shapes
anywhere." **Fixed: the manifest is the single source of truth for each Playwright upload's frozen
baseline** (extended above with a `baseline: "always" | "failure"` field, required exactly when
`playwright: true`), and the oracle now accepts only the *one* shape that entry's `baseline`
specifies:

```js
function normalizeExpr(s) {
  return (s ?? "").replace(/\s+/g, " ").trim();
}
function isProvenConjunctiveGate(ifExpr, predecessorId, expectedBaseline) {
  const gate = `steps.${predecessorId}.outcome == 'success'`;
  const expected = normalizeExpr(`${expectedBaseline}() && ${gate}`);   // exactly one shape now,
  return normalizeExpr(ifExpr) === expected;                            // not an either-of-two set
}
```

Example manifest entries for the 5 real Playwright sites, showing both baselines in use:
```json
{ "workflow": ".github/workflows/synthetic-monitoring.yml", "job": "run-synthetic-tests", "stepId": "upload-report", "playwright": true, "baseline": "always" }
{ "workflow": ".github/workflows/synthetic-monitoring.yml", "job": "run-azure-synthetic-tests", "stepId": "upload-report", "playwright": true, "baseline": "always" }
{ "workflow": ".github/workflows/frontend-e2e-integration.yml", "job": "e2e", "stepId": "upload-playwright-report", "playwright": true, "baseline": "failure" }
{ "workflow": ".github/workflows/frontend-ci.yml", "job": "e2e-smoke", "stepId": "upload-playwright-report", "playwright": true, "baseline": "failure" }
{ "workflow": ".github/workflows/ci-verification.yml", "job": "docker-build-verify", "stepId": "upload-playwright-traces", "playwright": true, "baseline": "failure" }
```
(job/stepId values illustrative — `tasks.md` assigns the actual `id:`s introduced per entry [19]
finding 3; the `always`/`failure` split matches the two `synthetic-monitoring.yml` jobs versus the
other three sites established earlier in this document.)

Whitespace is normalized (collapsed/trimmed) before comparison so incidental formatting differences
don't cause a false rejection, but the logical shape must be exactly one of the two forms this
design's own generated YAML emits (`always() && steps.<id>.outcome == 'success'` for the two
`synthetic-monitoring.yml` jobs, `failure() && steps.<id>.outcome == 'success'` for the other three
— see the condition-flow YAML above, which already emits precisely this order). A real expression
parser proving logical equivalence would also satisfy this finding, per entry [23]; the exact-match
allowlist is the simpler bounded choice for the two shapes this design actually produces.

**Secret/mode relationship — non-self-certifying, and now covering the whole job (entry [19]
finding 1; entry [21] finding 1).** Entry [19] closed the sanitizer's-own-input self-reference.
Entry [21] found two things still wrong with that fix, both confirmed by executing the displayed
logic against mock jobs: (a) the independent scan only checked `job.env` plus non-sanitizer
`steps` — a secret placed at `job.container.credentials.password` (or `services`, `outputs`,
job-level `if`, or any other job-level field) was invisible to it, returning `independent: false`
when it should have been `true`; (b) the check only asked "is there *a* correctly-wired live
sanitizer somewhere in this job," so a job with one correct `live-secret` sanitizer *plus* a second,
unrelated `fallback-only` sanitizer passed — the extra unsafe sanitizer was masked rather than
flagged.

**Fix for (a): walk the whole job, not cherry-picked fields.** Build a shallow copy of the job with
`steps` replaced by only the non-sanitizer steps, and recursively walk that *entire* view — every
current and future job-level field is covered automatically, since only the specific steps needing
exclusion are removed, not a hand-picked allowlist of fields to check:

**Corrected this revision (entry [23] finding 2): the detection regex only matched uppercase dot
syntax.** A fresh execution against `secrets['E2E_TEST_USER_PASSWORD']` and
`secrets["E2E_TEST_USER_PASSWORD"]` returned `false` for both — GitHub's expression syntax
supports index dereference alongside dot syntax, and secret names are matched case-insensitively,
so a reference in either alternate form was invisible to the guard. Broadened to cover both syntax
forms, permitted internal whitespace, and case-insensitive names:

```js
const SECRET_EXPR = /secrets\s*(?:\.\s*E2E_TEST_USER_PASSWORD\b|\[\s*['"]E2E_TEST_USER_PASSWORD['"]\s*\])/i;
const EXACT_SECRET_EXPRESSION = "${{ secrets.E2E_TEST_USER_PASSWORD }}";

function independentJobView(job) {
  return { ...job, steps: (job.steps ?? []).filter(s => !usesSanitizerAction(s)) };
}

function referencesSecretIndependently(job) {
  const seen = new WeakSet();
  function walk(node) {
    if (node === null || typeof node !== "object") {
      return typeof node === "string" && SECRET_EXPR.test(node);
    }
    if (seen.has(node)) return false;
    seen.add(node);
    return Object.values(node).some(walk);
  }
  return walk(independentJobView(job));   // the whole job — container, services, outputs, job `if`,
}                                           // strategy, anything — minus only the sanitizer step(s)
```

**Fix for (b): enforce the design's actual cardinality — at most one sanitizer per job — instead of
an aggregate "does a correct one exist somewhere" check.** Every real site in this design (all 5
Playwright-upload sites, per A-IG.5) has exactly one sanitizer immediately preceding exactly one
upload; the guard now makes that the enforced shape rather than an unstated assumption, which also
closes the masking gap directly — a second sanitizer step of any kind now fails the job outright,
before its mode is even inspected:

```js
// The upload-wiring loop (predecessor/staging-path/if-gate) is the one already shown above; this
// secret/mode check is a separate pass over every parsed job — not only ones with an upload — since
// a job can reference the secret via a step with no Playwright upload in it at all:
for (const [jobId, job] of allParsedJobs) {
  const sanitizeSteps = (job.steps ?? []).filter(usesSanitizerAction);
  if (sanitizeSteps.length > 1) {
    fail(`${jobId}: job has ${sanitizeSteps.length} sanitizer steps; this design supports at most one per job`);
    continue;   // a masked/extra sanitizer is caught here, before any mode check runs
  }
  const sanitizer = sanitizeSteps[0];   // undefined if none — handled below
  const independent = referencesSecretIndependently(job);
  if (independent) {
    // Unchanged from the prior revision (already exact, per entry [23]'s "preserve" instruction):
    // requires the literal 'live-secret' mode and the exact action-input secret expression.
    const correctlyWired = sanitizer && sanitizer.with?.mode === "live-secret"
        && sanitizer.with?.["e2e-password"] === EXACT_SECRET_EXPRESSION;
    if (!correctlyWired) {
      fail(`${jobId}: job independently references the real secret but its sanitizer is missing, ` +
           `not 'live-secret', or does not use the exact e2e-password expression`);
    }
  } else if (sanitizer) {
    // Corrected this revision (entry [23] finding 2): the prior check only rejected mode ===
    // "live-secret" or a truthy e2e-password — a missing mode, a typo like "fallback-onyl", an
    // arbitrary "anything" mode, and an explicitly-present-but-empty e2e-password all passed. Now
    // requires the mode to be the exact string "fallback-only" and the e2e-password *property* to
    // be truly absent, not merely falsy — an explicit `e2e-password: ""` is a configuration error,
    // not silently equivalent to omitting the key.
    const hasE2ePasswordKey = sanitizer.with != null
        && Object.prototype.hasOwnProperty.call(sanitizer.with, "e2e-password");
    if (sanitizer.with?.mode !== "fallback-only" || hasE2ePasswordKey) {
      fail(`${jobId}: no independent secret usage, but the sanitizer is not exactly ` +
           `mode: fallback-only with e2e-password entirely absent`);
    }
  }
}
```

Because the upload-wiring loop already requires every Playwright-classified upload's immediate
predecessor to be *a* sanitizer step, and this loop now guarantees at most one sanitizer step exists
per job at all, the two loops together bind every upload to the single sanitizer whose correctness
this check verifies — there is no longer a code path where a second, differently-configured
sanitizer could apply to an upload it doesn't actually precede.

**Regression fixtures, entry [21]:** a secret referenced only via
`job.container.credentials.password` with no independent step-level reference, paired with a
correctly-wired live sanitizer (must now be caught as `independent: true` and pass) and, separately,
with a missing/fallback-only sanitizer (must fail); a job with one correctly-wired `live-secret`
sanitizer feeding one upload plus a second, unrelated `fallback-only` sanitizer feeding a different
upload in the same job — the complete two-upload/two-predecessor shape (must fail on the cardinality
check, before mode is even inspected); a manifest that is not a top-level array; a manifest entry
with a missing or non-boolean `playwright` field; a manifest entry with an unexpected extra field;
two manifest entries with the same `{workflow, job, stepId}` triple, including one deliberately
conflicting pair; two discovered upload-artifact steps in the same job resolving to the same
`stepId`.

**Regression fixtures, entry [23]:** every rejected counter-example from finding 1 —
`../outside.yml`, `/.github/workflows/x.yml`, `C:/x.yml`, `.github/workflows/a/../x.yml`, and
`.github/workflows//x.yml` as manifest entries (all must fail schema validation); a secret
reference via `secrets['E2E_TEST_USER_PASSWORD']` and via `secrets["E2E_TEST_USER_PASSWORD"]`
(both must now be detected as independent usage); a lowercase (`secrets.e2e_test_user_password`)
and mixed-case dot reference (both must be detected); a credential-free job with a sanitizer whose
mode is missing entirely, `"fallback-onyl"` (typo), or `"anything"` (all three must fail — none is
the exact string `fallback-only`); a credential-free job with a sanitizer whose `e2e-password` is
explicitly present but set to `""` (must fail — the property must be truly absent, not merely
falsy); the three entry [23] fail-open upload conditions — `always() || steps.sanitize.outcome ==
'success'`, `!(steps.sanitize.outcome == 'success')`, and `steps.sanitize.outcome == 'success' ||
failure()` — as negative fixtures (must all fail).

**Regression fixtures, entry [25]:** a mocked `fs.readdirSync` result containing the basename
`x\y.yml` — `.github/workflows/x\y.yml` must now fail `isCanonicalWorkflowPath` inside
`listWorkflowFiles()` itself (not merely in the manifest validator), proving the shared function is
actually invoked on the discovery side, not merely claimed to be; an ordinary `x.yml` basename must
still produce exactly `.github/workflows/x.yml`; a known `baseline: "always"` upload identity whose
actual `if:` uses the `failure()` shape (must fail); a known `baseline: "failure"` upload identity
whose actual `if:` uses the `always()` shape (must fail); the correct per-identity positive for
each of the two baselines (both must pass); a `playwright: true` manifest entry missing `baseline`
or with a value other than the literal strings `"always"`/`"failure"` (must fail schema
validation); a `playwright: false` manifest entry that carries a `baseline` field anyway (must
fail schema validation).

**All carried forward unchanged:** a workflow whose only secret reference is inside a `run:` script
block, paired with a correctly-wired sanitizer (pass); a newly discovered upload with no manifest
entry (fail); a stale manifest entry with no matching discovered upload (fail); a manifest entry
classified `playwright: false` with no sanitizer wiring present (pass — exempt by design).

#### GitHub Actions condition flow (unchanged shape from Revision 4, accepted)

*`always()` sites (`synthetic-monitoring.yml`, both jobs):*
```yaml
- name: Sanitize Playwright artifacts
  id: sanitize
  if: always()
  uses: ./.github/actions/sanitize-playwright-artifacts
  with: { source-dir: frontend/playwright-report, staging-dir: ${{ runner.temp }}/sanitized-playwright-report,
          mode: live-secret, e2e-password: ${{ secrets.E2E_TEST_USER_PASSWORD }} }

- name: Upload Report
  if: always() && steps.sanitize.outcome == 'success'
  uses: actions/upload-artifact@v4
  with: { name: playwright-report, path: ${{ runner.temp }}/sanitized-playwright-report, retention-days: 7 }
```

*`failure()` sites (`frontend-e2e-integration.yml`, `frontend-ci.yml`,
`ci-verification.yml`'s `playwright-traces` step):*
```yaml
- name: Sanitize Playwright artifacts
  id: sanitize
  if: failure()
  uses: ./.github/actions/sanitize-playwright-artifacts
  with: { source-dir: frontend/test-results, staging-dir: ${{ runner.temp }}/sanitized-output, mode: fallback-only }

- name: Upload Playwright report on failure
  if: failure() && steps.sanitize.outcome == 'success'
  uses: actions/upload-artifact@v4
  with: { path: ${{ runner.temp }}/sanitized-output, ... }
```

Both preserve the original eligibility condition and additionally require
`steps.sanitize.outcome == 'success'` on the upload step, which — per the corrected state machine
above — is only ever `'success'` after a fully clean, read-only-verified `staging-dir`.

---

## Track B Design — Rate-limit integration-test determinism

### Data shapes and orchestration (unchanged from Revision 4, accepted)

```java
@FunctionalInterface interface SecondProvider { String currentSecond() throws Exception; }
@FunctionalInterface interface KeyProvider { String freshKey(); }
@FunctionalInterface interface BurstRunner { RawAttempt run(String key) throws Exception; }

record RawAttempt(
    List<EntityExchangeResult<String>> burstResponses,
    EntityExchangeResult<String> firstExcessResponse,
    long downstreamDelta) {}

final class ProvenWindowRunner {
    private final SecondProvider secondProvider;
    private final KeyProvider keyProvider;
    private final BurstRunner burstRunner;

    ProvenWindowRunner(SecondProvider s, KeyProvider k, BurstRunner b) {
        this.secondProvider = s; this.keyProvider = k; this.burstRunner = b;
    }

    RawAttempt run(int maxAttempts, Duration softMaxElapsed) throws Exception {
        long start = System.nanoTime();
        int attempt = 0;
        while (attempt < maxAttempts
                && Duration.ofNanos(System.nanoTime() - start).compareTo(softMaxElapsed) < 0) {
            attempt++;
            String key = keyProvider.freshKey();
            String before = secondProvider.currentSecond();
            RawAttempt result = burstRunner.run(key);        // raw capture only — no assertions
            String after = secondProvider.currentSecond();   // elapsed re-checked here, after the
                                                               // operation, via the loop's own
                                                               // condition on its next iteration
            if (before.equals(after)) {
                return result;
            }
            // crossed a boundary — discarded here, never seen by any assertion
        }
        throw new AssertionError("No proven no-replenishment window after " + attempt
                + " attempts / " + Duration.ofNanos(System.nanoTime() - start).toMillis()
                + "ms elapsed");                                // no token/secret in the message
    }
}

BurstRunner productionBurstRunner = key -> {
    long baseline = downstreamRequestCount.get();
    List<EntityExchangeResult<String>> burst = new ArrayList<>();
    for (int i = 0; i < STANDARD_BURST; i++) {
        burst.add(webTestClient.get().uri(PORTFOLIO_PATH).header("Authorization", "Bearer " + key)
                .exchange().expectBody(String.class).returnResult());
    }
    EntityExchangeResult<String> excess = webTestClient.get().uri(PORTFOLIO_PATH)
            .header("Authorization", "Bearer " + key).exchange().expectBody(String.class).returnResult();
    return new RawAttempt(burst, excess, downstreamRequestCount.get() - baseline);
};
```

**The test method asserts only on the single returned, proven `RawAttempt`:**

```java
RawAttempt proven = new ProvenWindowRunner(this::redisTimeSeconds,
        () -> tokenFor("proven-window-" + System.nanoTime()), productionBurstRunner)
        .run(MAX_WINDOW_PROOF_ATTEMPTS, MAX_WINDOW_PROOF_SOFT_ELAPSED);

List<String> remaining = new ArrayList<>();
for (int i = 0; i < proven.burstResponses().size(); i++) {
    EntityExchangeResult<String> r = proven.burstResponses().get(i);
    assertThat(r.getStatus().value()).as("burst request %d proxied successfully", i + 1).isEqualTo(200);
    remaining.add(r.getResponseHeaders().getFirst("X-RateLimit-Remaining"));
}
assertThat(remaining).containsExactly("2", "1", "0");                                  // Req 7.2

EntityExchangeResult<String> excess = proven.firstExcessResponse();
assertThat(excess.getStatus().value()).isEqualTo(429);                                 // Req 7.3
assertThat(excess.getResponseHeaders().getFirst("Retry-After")).isEqualTo("1");
assertThat(excess.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
assertThat(excess.getResponseBody()).contains("\"error\":\"rate_limited\"").contains("\"retryAfterSeconds\":1");
assertThat(proven.downstreamDelta()).isEqualTo(STANDARD_BURST);
```

### Corrected: `WebTestClient` timeout placement, `redisTimeSeconds()` robustness, honest bound (finding 5)

**(a) `responseTimeout` is a `WebTestClient.Builder` method, not a per-exchange one — corrected.**
Revision 4 incorrectly implied it could be chained per-call. Spring's actual API (confirmed this
revision against the current Javadoc) exposes it only when *building* the client, exactly as the
existing precedent already does it
(`HttpTraceContextPropagationIT.java:105-108`). The fix is in `@BeforeEach setUp()`, not in
`BurstRunner`:

```java
@BeforeEach
void setUp() {
    webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(5))   // applies to every exchange from this client
            .build();
}
```

**(b) `redisTimeSeconds()`, fully defined — corrected.** The pseudocode in every prior revision
returned `getStdout()` unconditionally. Completed to fail red on a non-zero exec exit or
unparseable output, and to compare only the parsed seconds field:

```java
private String redisTimeSeconds() throws Exception {
    Container.ExecResult result = redis.execInContainer("redis-cli", "TIME");
    if (result.getExitCode() != 0) {
        throw new IllegalStateException(
                "redis-cli TIME exited " + result.getExitCode() + ": " + result.getStderr());
    }
    String[] lines = result.getStdout().trim().split("\n");
    if (lines.length < 1 || !lines[0].matches("\\d+")) {
        throw new IllegalStateException("redis-cli TIME returned unparseable output: " + result.getStdout());
    }
    return lines[0];   // seconds only — the microseconds line is not compared
}
```
Any failure here propagates as a thrown exception out of `ProvenWindowRunner.run()`, failing the
test red — never silently treated as "no crossing."

**(c) The elapsed-time bound, stated with only one honest characterization — corrected.** Prior
revisions called the `redis-cli TIME` container-exec call both "unbounded" and
"bounded-but-unenforced" in different places, then still claimed an approximate worst-case total —
an unbounded operation admits no such claim, and that inconsistency is retracted rather than
patched again. The corrected, single statement: **`WebTestClient` exchanges are bounded by the
explicit 5-second `responseTimeout` above. The `redis-cli` container-exec calls are not bounded by
any mechanism this design introduces** — Testcontainers' `execInContainer` has no per-call timeout
in its synchronous API, and this design does not build a cancelling wrapper around it. **The only
real hard backstop against a genuinely hung operation of either kind is the surrounding
`integrationTest` Gradle task's existing 20-minute deadline** (`build.gradle:128-131`), which kills
the whole test JVM if exceeded. `MAX_WINDOW_PROOF_ATTEMPTS = 30` and
`MAX_WINDOW_PROOF_SOFT_ELAPSED = Duration.ofSeconds(10)` remain exactly what they were always
honestly described as: a **soft "do not start another attempt" admission policy**, checked at the
top of every loop iteration (i.e., freshly re-evaluated after each attempt completes, not only once
at the start) — not a wall-clock guarantee on total execution time, and this document no longer
computes or implies one beyond the 20-minute Gradle backstop. Exhaustion fails the test red with a
message containing only the attempt count and elapsed milliseconds — never a token, key, or secret.

### Downstream oracle (unchanged from Revision 4, accepted)

```java
private static HttpServer portfolioStub;
private static int portfolioStubPort;
private static final AtomicLong downstreamRequestCount = new AtomicLong();

@DynamicPropertySource
static void redisAndRateLimitProperties(DynamicPropertyRegistry registry) throws IOException {
    startPortfolioStub();
    registry.add("app.routes.portfolio-url", () -> "http://127.0.0.1:" + portfolioStubPort);
    // ... existing redis/postgres/jwt/rate-limit registrations, unchanged
}

private static void startPortfolioStub() throws IOException {
    if (portfolioStub != null) return;
    portfolioStub = HttpServer.create(new InetSocketAddress(0), 0);
    portfolioStubPort = portfolioStub.getAddress().getPort();
    portfolioStub.createContext("/", exchange -> {
        downstreamRequestCount.incrementAndGet();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
    });
    portfolioStub.start();
}

@AfterAll
static void stopPortfolioStub() {
    if (portfolioStub != null) { portfolioStub.stop(0); portfolioStub = null; }
}
```
Mirrors `HttpTraceContextPropagationIT.java:62-94` exactly. Baseline/delta captured inside
`BurstRunner`, carried structurally in `RawAttempt.downstreamDelta`.
`GatewayRateLimitConfig`/`RedisRateLimiter` remain untouched (B2.4).

### IV.2's two negative oracles (unchanged, accepted)

(a) A dedicated unit test (no Spring, no Testcontainers) constructs `ProvenWindowRunner` with fake
`SecondProvider`/`KeyProvider`/`BurstRunner` collaborators, scripting a crossed-then-valid sequence,
and asserts the full discard/fresh-key/retry control flow — not just an equality predicate. (b)
Exact assertions (`containsExactly`, `isEqualTo(200)`/`isEqualTo(429)`, exact headers/body, the
downstream-delta) exercised once during implementation by deliberately perturbing a decrement,
status, proxying outcome, or downstream delta against a build that still reports a proven window.

---

## Traceability

### Track A

| Requirement | Design component | Verification |
|---|---|---|
| A2.1 (no secret, incl. nested archives) | Outcome-A/B state machine + structured & raw-byte scans | IV.1; full regression suite incl. canary |
| A2.2 (staging path only) | Copy step (symlink-rejecting → Outcome B); missing-source semantics | Workflow review; IV.1(c) |
| A2.3 (fail closed, exact wording) | Corrected state machine: Outcome B always aborts entire upload; pass 2 read-only | Fail-closed/hostile-archive/uninspectable fixtures |
| A2.4 (regression shapes) | Complete list above | One fixture per shape |
| A2.5 (safety over diagnostics) | Outcome B default | Fail-closed fixture |
| A2.6 (centralized) | One composite action; static guard enforces every upload is wired to it | Workflow diff review; static-guard CI step |
| A2.7 (non-goals) | Not a design component | N/A |
| A-IG.1–A-IG.5 | Completed | Evidence above |
| A-IG.6 | yauzl-first classification selected only after evidence existed | Document ordering |
| A-IG.7 (entry [33]/[35]/[37]/[39]/[41]/[43] follow-up) | Real HTML-reporter output re-examined at the exact locked `1.59.0` and the exact production path shape; Revision 11's universal raw-scan falsified (gzip); the self-certifying manifest (Revision 12) replaced with a canary-derived exhaustive-equality check plus a lockfile-bound `playwrightTestVersion`; scan-time paths validated by `toCanonicalTracePath`, raw manifest strings by a separate `validateManifestTracePath` that checks raw segments before resolution can collapse them (closing the `.`/`..`-aliasing gap Revision 14 missed); digest+decode+control-byte+sentinel merged into one streamed pass with an unconditional end-of-stream flush; Layer 1's `screenshots: false` wording corrected to be global, not selective; the non-executable symlink fixture replaced with an honest defense-in-depth description plus a property/table test (Revision 16) | Gzip fixture; `toCanonicalTracePath` (scan-time) and `validateManifestTracePath` (raw manifest) positive/negative/argument-order/aliasing fixtures; property/table equality-and-rejection test; incomplete-trailing-sequence fixture; version-binding fixtures; schema-validation fixtures; exhaustive-manifest-mismatch fixtures; single-pass-read fixture; real trace.zip-with-no-screenshots fixture; regression fixtures list |
| IV.1 | Complete algorithm + composite action | Dummy-sentinel pre/post-fix + upload/download scan |
| entry [13]/[15]/[17]/[19]/[21]/[23] secret transport & least privilege | `mode` split; guard derives independent secret usage from the whole job minus sanitizer steps; at-most-one-sanitizer-per-job cardinality; broadened secret-syntax detection; exact fallback-only enforcement | Static-guard CI step |
| entry [15] finding 1 (A2.3 state machine) | Outcome A vs. B, read-only pass 2 | Fixtures above |
| entry [9] finding 1 / entry [17] finding 1 (image exception; fail-open classifier fallback) | Post-rejection ZIP-signature gate; strict-UTF-8-decode-or-Outcome-B restored for both top-level files and ZIP entries (Revision 12 reasserts entry [9] after Revision 11 briefly relaxed it — see A-IG.7); authenticated allowlist is the only exception, never a blanket clean pass | Truncated-local-header fixture; gzip fixture; binary-ZIP-entry fixture; allowlist fixtures |
| entry [15] finding 2 / entry [17] finding 4 / entry [19] finding 2 (classifier + metadata + streamed scans) | yauzl-first decision; structured scan; streamed+budgeted+overlap-safe raw scan AND top-level-file fallback scan | Fixtures above, incl. cross-chunk and over-limit fixtures |
| entry [15] finding 3 (executable composite) | Complete 4-step sequence, action-local working-directory | Composite action review |
| entry [17] finding 2 (vulnerable parser pin) | `js-yaml@4.3.0`, `maxTotalMergeKeys`, `--audit-level=low` | `npm audit` in CI |
| entry [15] finding 4 / entry [17] finding 3 / entry [19] finding 1 / entry [21] finding 1 / entry [23] finding 2 (parser, guard scope, non-self-certifying + whole-job secret check, exact syntax/mode) | `js-yaml`; whole-job recursive scan excluding sanitizer steps; upload `if:`-gate check; sanitizer cardinality bound; broadened `SECRET_EXPR`; exact `fallback-only`/absent-`e2e-password` check | Static-guard CI step, incl. new fixtures |
| entry [19] finding 3 / entry [21] finding 2 / entry [23] finding 1 / entry [25] finding 1 (upload classification executable, fail-closed, canonical paths, actually shared validator) | Checked-in `playwright-upload-manifest.json`; schema/duplicate validation before exhaustive diff; single `isCanonicalWorkflowPath` allowlist (excludes `/` and `\`) invoked by both the manifest validator and `listWorkflowFiles()`; exact `usesSanitizerAction` | Static-guard CI step; schema, duplicate, canonical-path (incl. mocked-`readdirSync` backslash), and new-upload/stale-entry fixtures |
| entry [23] finding 3 / entry [25] finding 2 (upload-condition oracle, identity-bound) | `isProvenConjunctiveGate` exact-shape allowlist bound to each upload's manifest `baseline` field — only the one correct shape per identity | Fail-open, positive, and crossed-baseline condition-shape fixtures |

### Track B — **architecture gate clear (entries [17], [19], [21], [23]); table retained for traceability, no further design changes**

| Requirement | Design component | Verification |
|---|---|---|
| B2.1–B2.5 | `ProvenWindowRunner` + post-return assertions; test-only; sibling untouched | Integration test; diff review |
| B-IG.1–B-IG.6 | Accepted, carried forward unchanged | Evidence above |
| entry [13] finding 4/finding 6 (exact-200, structural delta) | `isEqualTo(200)`; `RawAttempt.downstreamDelta` | Integration test |
| entry [15] finding 5a (`responseTimeout` placement) | Moved to `WebTestClient.Builder` in `setUp()` | Code review |
| entry [15] finding 5b (`redisTimeSeconds()` robustness) | Exit-code + format validation, throws on failure | Code review; malformed-output fixture |
| entry [15] finding 5c (honest bound) | Single statement: HTTP bounded (5s), container-exec unbounded, 20-min Gradle task is the real backstop | Document review |
| IV.3 (`integrationTest`, non-zero count) | Unchanged task; new methods still `@Tag("integration")` | `tasks.md` command spec |
| IV.4 (CI surface coverage) | Includes the static secret-wiring guard | `tasks.md` |
| IV.5 (PR hygiene/branching) | Not a design component | `tasks.md`/PR review |

---

## Explicitly not decided here

- `scripts/check-sanitizer-secret-wiring.js`'s exact file layout (co-located `package.json` under
  `scripts/` vs. a shared one), and `playwright-upload-manifest.json`'s exact location alongside it
  — either satisfies the design above.
- Nothing security-relevant remains open: the sanitize/verify state machine, ZIP classification,
  the post-rejection fail-closed gate (now fully streamed), complete metadata and streamed raw-byte
  coverage, the composite action's executable step sequence, the static guard's parser/version/
  validation scope, and Track B's timeout mechanics (architecture-cleared) are all fully specified
  above.
