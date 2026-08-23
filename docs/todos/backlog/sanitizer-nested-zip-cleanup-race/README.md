# Backlog: Playwright artifact sanitizer — temp-dir cleanup races the recursive nested-zip scan

**Status:** Open — 2026-08-23
**Owner:** unassigned
**Tracked in:** Found investigating `sanitizer-canary`'s `FAILURE` on
[PR #136](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/136) (checkpoint
9.7 prep). PR #136 does not touch the sanitizer action at all — this is pre-existing and unrelated
to that PR's actual change.

---

## Status & Decision

**Open, not fixed.** Root-caused, not just observed as flaky. One-line fix identified but not
applied — flagged here rather than fixed inline since it's out of scope for the PR that surfaced it.

## What happened

CI run [32615305293](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32615305293)
(the "Run sanitizer Node test suite" step) failed one of 83 tests:

```
is UNINSPECTABLE_ENTRY for a manifest-matching entry nested one zip deeper (depth > 0)
Expected: 'UNINSPECTABLE_ENTRY'
Actual:   "ZIP_OPEN: ENOENT: no such file or directory, open '/tmp/sanitize-entry-lTUIq2/content'"
```

(`.github/actions/sanitize-playwright-artifacts/test/frontend-trace-allowlist-fail-closed.test.js:81`)

## Root cause (evidence, not speculation)

`.github/actions/sanitize-playwright-artifacts/sanitize.js:456-483`. The per-entry scan function
extracts each zip entry to a temp file, then — if the entry is itself a zip — recurses:

```js
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "sanitize-entry-"));
const tmpFile = path.join(tmpDir, "content");
try {
  ...
  if (await tryOpenZip(tmpFile)) {
    return structuredScan(tmpFile, { sentinels, depth: depth + 1, budget });   // <-- line 457
  }
  ...
} finally {
  fs.rmSync(tmpDir, { recursive: true, force: true });                        // <-- line 482
}
```

`structuredScan` is `async`. `return structuredScan(tmpFile, ...)` returns that call's **promise**
without awaiting it. In JS, returning an unawaited promise from inside a `try` still runs the
`finally` block immediately — it does not wait for the returned promise to settle. So `fs.rmSync`
deletes `tmpDir` (and `tmpFile` with it) while the recursive `structuredScan(tmpFile, ...)` call is
still pending on `yauzl.openPromise(filePath, ...)`, an async read of that same file. Whether the
open completes before or after the delete is a race — explaining why this is intermittent rather
than deterministic, and why it isn't "just flaky": it's a real, understood race, not noise.

**This is a production correctness bug, not just a test-suite bug.** Any real Playwright trace zip
containing a nested zip one level deep hits this exact code path — the fail-closed test only makes
the race visible by exercising it directly. A real invocation could sporadically throw a raw
`ZIP_OPEN: ENOENT` classification instead of correctly returning `UNINSPECTABLE_ENTRY`, on a
timing basis unrelated to the actual content being scanned.

## Fix (not yet applied)

Line 457: `return await structuredScan(tmpFile, { sentinels, depth: depth + 1, budget });` — await
the recursive call so `finally`'s cleanup only runs after it has fully read `tmpFile`, not during.

## Notes

- This is a second real defect found in this specific sanitizer's history — see
  [[project-evidence-oracle-mismatch-pattern]] (memory) instance 4, the screenshot/uninspectable
  conflict from 2026-08-20. Worth treating "sanitizer test failure" as a signal to investigate, not
  a default flake-and-retry, given the track record.
- Not blocking checkpoint 9.7 substantively (the repair Job's own PR doesn't touch this code), but
  it is a required CI check, so PR #136 stays legitimately blocked until this either gets fixed or
  the check is otherwise satisfied — not bypassed.
