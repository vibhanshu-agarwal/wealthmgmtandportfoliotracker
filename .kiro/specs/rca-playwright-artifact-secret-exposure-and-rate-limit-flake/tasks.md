# Tasks Document

Revision 8. Built on the approved, frozen `bugfix.md` Revision 4
(`10bdb3d8bb4ae3b2b68628726f20143ccb52b8f1`) and the cleared `design.md` Revision 16
(`92c85b95c1fd0b8eda1a26acaf908cf5935bbaf1`) — both unchanged, not reopened. Addresses all three
narrow executable-oracle findings in checkpoint entry [53] against Revision 7
(`3fd399c707833edc34f6b08db919074c86039cef`) — B9's recorded-`BASE_SHA` line written as an
angle-bracket placeholder that bash actually parses as input redirection, not assignment (`bash -n`
exit 2); A15's `|| true` attached to the whole `git ls-files | grep` pipeline, so it silently
absorbed an upstream `git ls-files` failure, not just `grep`'s harmless zero-match exit; and B5
printing `PASS` for a run even when its own required `BUILD SUCCESSFUL in Xs` duration-evidence
extraction failed or came back empty. A6 is confirmed cleared and was not touched. Not a re-opening
of any accepted architecture or design decision. This document is planning only: drafting it
authorizes nothing beyond itself. No production/test/workflow code change, deployment, workflow
dispatch, push, Terraform change or apply, password rotation, production probe, or real-credential
use has been performed to produce it, and none is authorized until the owner approves all three SDD
artifacts as a set. **Revision 7's self-audit claimed the complete B5/B8/B9 blocks were `bash -n`
syntax-checked, but that check was run against a hand-retyped scratch copy, not the literal text in
the document — the exact defect entry [53] finding 1 caught.** This revision's verification
methodology is corrected accordingly: every fix below was checked by `sed`-extracting the precise
line range from the on-disk document itself (never retyped) before running `bash -n` or a functional
test against it, so what was verified is provably what is displayed. Several claims below were
independently reproduced this way, not taken on citation alone: B9's exact displayed
`BASE_SHA=<...>` line does `bash -n`-fail with exit 2 (confirmed before fixing, on the literal
line); the corrected `: "${BASE_SHA:?...}"` form is ordinary valid shell and fails closed when unset
(confirmed both ways); A15's old `|| true` swallows a genuine upstream `git ls-files` failure into a
false `PASS` (confirmed via an invalid flag), and the corrected form fails closed in that same case
while still passing clean and failing dirty; B5's old duration line lets a run print `PASS` with an
empty duration (confirmed), and the corrected form returns 1 for that run instead. All five
final blocks (A15, B5, B8's two blocks, B9), extracted verbatim by line range from the completed
Revision 8 document, `bash -n` clean.

## What changed since Revision 3

1. **Verification convention fixed (entry [33] finding 1):** the bash Gradle form now uses
   `./gradlew` (matching how `ci-verification.yml` actually invokes it on `ubuntu-latest`), not
   `.\gradlew.bat`; the PowerShell form keeps `.\gradlew.bat` for local Windows use. The Node form
   now rejects an absent or non-numeric `tests`/`fail` value explicitly (a missing `# fail` line
   previously fell through the numeric comparison and passed silently). The Node form's temp file is
   now a `mktemp`-generated, per-invocation path with explicit cleanup, not a shared, unscoped
   `/tmp/out.tap`.
2. **A2 extended:** Layer 1 now also carries `design.md`'s global `screenshots: false` change to
   `frontend/playwright.config.ts` (new since Revision 10), in addition to the unchanged
   traced-fetch swap. Entry [33] finding 2's durable test file is now named and scoped.
3. **The classifier tasks are rebuilt around `design.md` Revision 16's mechanism**, which did not
   exist when Revision 3 was written: new task A5 implements `isValidTraceSegment`,
   `toCanonicalTracePath`, `validateManifestTracePath`, and the authenticated
   `known-playwright-report-assets.json` allowlist with its single-pass digest/decode/control-byte/
   sentinel merge; A4 (structured scan) is corrected so a non-decodable ZIP entry makes the whole
   archive Outcome B, reasserting entry [9], not a per-entry raw-byte-scan pass.
4. **A6's resource oracle (was A5, entry [33] finding 3):** interval sampling replaced with a true
   peak-RSS measurement (`/usr/bin/time -v` around the spawned child, or an equivalent
   `resourceUsage().maxRSS`-based protocol) that cannot miss a transient peak between samples.
5. **A10's condition flow (was A9, entry [33] finding 4):** now explicitly sets the sanitizer step's
   own `if: always()`/`if: failure()` baseline condition, not only the upload step's conjunction.
6. **A13's canary (was A12) rebuilt (entry [33] finding 5's remaining, non-design-conflict parts):**
   no aggregate `generated-output/` root — `playwright.config.ts`'s `outputDir`/reporter
   `outputFolder` are direct siblings (`test-results/`, `playwright-report/`), matching production's
   real shape exactly; `staging-dir` is a unique, freshly created child of
   `process.env.RUNNER_TEMP || os.tmpdir()`, never a fixed `/tmp` path; wildcard-sensitive
   single-file checks replaced with count-aware ones; "source is dirty" and "staging is clean" are
   now two separate sanitize.js invocations (raw→staging1 proves dirty; staging1→staging2 proves
   clean), not one mutating diff asked to prove both; the canary now also runs the manifest
   exhaustive-equality and lockfile-version-binding check `design.md` added.
7. **A14's IV.1 verification (was A13, entry [33] finding 6):** the Playwright step now has an `id`
   and an explicit `steps.<id>.outcome == 'failure'` assertion; the complete expected output shape
   is asserted before each upload; detection is corrected to actually be non-mutating (see task);
   the real implementation order is stated explicitly (A13/A14 step 1 before A11; A14 step 2/cleanup
   after A11, before A12's exhaustive manifest check); the false "run logs don't expire" claim is
   removed — GitHub retains both logs and artifacts for a bounded period.
8. **B5/B8/B9 (entry [33] finding 7):** Revision 4 claimed this was already fixed in Revision 3 and
   carried it forward unchanged. Entry [47] found that claim was wrong — B5 still said "run
   repeatedly a bounded number" with no fixed count and still asked for an internal attempt count
   the frozen success path doesn't expose; B8/B9 still lacked the sibling class's exact command/
   count. Actually fixed in Revision 5 (see below), not merely re-asserted.
9. **Final traceability matrix rebuilt** to match the new task numbering and the complete Revision
   16 mechanism.

## What changed since Revision 4 (checkpoint entry [47], nine findings)

1. **File scope corrected:** `frontend/tests/e2e/helpers/__tests__/capture-suppression.test.ts` and
   `.github/actions/sanitize-playwright-artifacts/test/canary/sync-canary-playwright-version.js`
   added to Track A's exhaustive list (finding 1; finding 2's new file).
2. **A13's Playwright pin is now exact and self-verifying, not a caret range (finding 2):** a new
   `sync-canary-playwright-version.js` derives the canary's pin from `frontend/package-lock.json`
   (`--write`) and asserts, in CI before Playwright ever runs, that the canary's actually-installed
   `@playwright/test`/`playwright`/`playwright-core` versions all equal the frontend lock's current
   resolution (`--check`) — never a duplicated, independently-maintained version constant.
3. **The A5/A13 circular dependency broken** with an explicit, documented acyclic build order (new
   "Implementation order for A5/A13" section): the manifest tool and allowlist mechanism are built
   and tested against synthetic data first; the canary fixture project is built and run once with no
   dependency on a populated manifest; the manifest is generated from that one real run; only then
   does A13's full verify sequence run, now that both a working sanitizer and a populated manifest
   exist (finding 3).
4. **A13's local environment handling fixed (finding 4):** `TEMP_ROOT` is gone (it was assigned
   without `export`, making it invisible to child `node` processes — reproduced directly before
   fixing); the script now `export`s `GITHUB_WORKSPACE`/`RUNNER_TEMP` explicitly, preserving Actions'
   native values when present and establishing local equivalents (via `git rev-parse --show-toplevel`
   and a cleaned-up `mktemp -d`) otherwise, so `sanitize.js`'s own path validation has what it needs
   in both environments.
5. **A6's resource fixture retargeted (finding 5):** a 500 MB ZIP entry can legitimately be rejected
   from `yauzl`'s central-directory metadata alone, without ever touching the compressed stream —
   proving nothing about streaming. The peak-RSS proof now targets a 500 MB top-level non-ZIP file
   through the `TOP_LEVEL_FILE_BYTE_LIMIT` fallback path instead, which has no metadata shortcut
   available at all.
6. **Every multi-command bash sequence corrected for GitHub Actions' default fail-fast shell
   (finding 6):** `command; status=$?` never reaches the assignment when `command` fails under
   `bash -e` (reproduced directly). Every status capture in the Verification convention and in A13's
   canary script now uses `if command; then status=0; else status=$?; fi`; temp-file cleanup uses an
   `EXIT` trap instead of an inline `rm` that could itself be skipped.
7. **Track B's B5/B8/B9 actually fixed this time (finding 7):** B5 now runs the Gradle convention
   exactly 5 times (never open-ended "repeatedly"), and no longer claims to observe a per-run
   internal attempt count the frozen `RawAttempt`/`ProvenWindowRunner` success path doesn't expose —
   replaced with each run's external wall-clock duration, which requires no change to the frozen data
   shape. B8/B9 now name the exact, re-verified baselines (`ProductionRateLimitingIntegrationTest`: 8
   `@Test` methods; `RateLimitingIntegrationTest`: 5) and the exact commands for both, and B9 runs the
   sibling class explicitly rather than treating it as diff-only.
8. **Cross-package Java visibility specified (finding 8):** B1/B2 now state explicitly that
   `SecondProvider`/`KeyProvider`/`BurstRunner`/`RawAttempt`/`ProvenWindowRunner`'s consumed surface
   (interfaces, the record, the constructor, `run()`) and `RedisTimeParser.parse` must be `public` —
   the integration test that calls them lives in the parent `com.wealth.gateway` package, one level
   above the `com.wealth.gateway.ratelimit` subpackage these types live in by design; a
   package-private declaration would not compile across that boundary.
9. **A14's IV.1 evidence now recorded as durable facts in the PR description itself (finding 9),**
   not primarily as run URLs that lose their only evidentiary value once GitHub's retention period
   expires: commit SHA, run ID/URL, artifact name/shape, expected/observed canary outcome, and both
   detector results are all recorded as text.

## What changed since Revision 5 (checkpoint entry [49], five findings)

1. **A6's peak-RSS proof still had a metadata shortcut, this time via `fs.stat` (finding 1):**
   Revision 5 retargeted the fixture from a ZIP entry (rejectable from `yauzl`'s central-directory
   metadata alone) to a 500 MB top-level non-ZIP file — but a correct-looking implementation could
   still read `fs.statSync(path).size` and reject on that alone without ever opening the file.
   Reproduced directly: `fs.statSync(...).size` returns the size via a single syscall with no read
   of file content. Codex is right that black-box RSS/exit-code assertions can never discriminate a
   genuinely-streaming implementation from a stat-only shortcut, by construction — any correct
   size-triggering implementation, streaming or not, produces the same observable RSS/exit code. Task
   A6 now adds `test/instrument-fs.js`, a `--require`-preloaded module that monkey-patches
   `fs.createReadStream` (to count bytes actually delivered via `data` events) and
   `fs.readFileSync`/`fs.promises.readFile`/`fs.readFile` (to throw if the sanitizer calls any of
   them). A6's Verify step now asserts, in addition to the retained RSS/exit-code/message checks,
   that `createReadStreamCalled === true`, `wholeFileApiCalled === false`, and
   `totalStreamedBytes` is bounded to `(50 MB, 51 MB]` — closing the shortcut by white-box
   instrumentation rather than a black-box proxy that can't tell the difference.
2. **`RedisTimeParser`'s enclosing class was still package-private (finding 2):** Revision 5 made
   `parse` `public` but left the class itself without an access modifier — a public member on a
   package-private class is still inaccessible from `com.wealth.gateway`, one level above
   `com.wealth.gateway.ratelimit`. B2 now specifies `public final class RedisTimeParser` with a
   `private RedisTimeParser() {}` no-op constructor alongside the existing `public static String
   parse(int exitCode, String stdout)`.
3. **B5's 5-run policy had no aggregate deadline (finding 3):** an exact run count bounds how many
   times the suite runs, not how long all 5 runs together may take. B5's Verify section now wraps the
   loop in `timeout 900 bash -c '...'` (900s: comfortably above typical per-run cost, far below the
   5 × 20-minute worst case the existing single-task Gradle timeout already backstops) and states
   explicitly that a `timeout` exit (124) or any non-zero exit from the wrapped command is a failure,
   never a retry trigger.
4. **B8 could self-rebaseline after an accidental test deletion (finding 4):** Revision 5's "if the
   count differs, treat the newly-observed count as authoritative" would let an implementation that
   accidentally deletes a `@Test` method silently certify a lower baseline, defeating the regression
   oracle. B8 now derives `prod_baseline`/`sibling_baseline` immutably from git history before any
   Track B edit exists — `git show $(git merge-base main HEAD):<path> | grep -cE '^\s*@Test\s*$'`
   for each class — recorded once in the PR description; any mismatch against the live file is now
   unconditionally a failure requiring investigation, never a silent re-baseline. B9 references the
   same `$prod_baseline`/`$sibling_baseline` variables instead of the hardcoded 8/5 Revision 5 used.
5. **Generated artifacts were claimed ignored but never actually were, plus a stale Track B sentence
   (finding 5):** reproduced directly via `git check-ignore -v` (exit 1, no match) against
   `.github/actions/sanitize-playwright-artifacts/node_modules`, `scripts/node_modules`, and the
   canary's nested `test-results`/`playwright-report` paths — none were ignored by any existing
   `.gitignore`. Track A's file scope now adds two new scoped `.gitignore` files, following the
   repo's own established per-subproject convention (`frontend/.gitignore`'s `/node_modules`):
   `.github/actions/sanitize-playwright-artifacts/.gitignore` (`/node_modules`,
   `/test/canary/node_modules`, `/test/canary/test-results`, `/test/canary/playwright-report`) and
   `scripts/.gitignore` (`/node_modules`). A15's stop/go gate gained a
   `git status --porcelain --ignored=no` check asserting no unexpected untracked residue remains.
   Separately, Track B's file-scope section wrongly claimed the track was "unchanged from Revision 3"
   with entry [33] finding 7 "already fixed" — directly contradicted by Revision 5's own changelog
   above; corrected to acknowledge B1/B2/B5/B8/B9 were materially rewritten in Revisions 5-6.

## What changed since Revision 6 (checkpoint entry [51], four executability blockers)

1. **A6's white-box instrumentation had no staged-file identity, so it counted the frozen design's
   own copy step instead of only the classifier's read (finding 1).** Revision 6 accumulated
   `totalStreamedBytes` across *every* `fs.createReadStream` call in the child process, with no path
   check. `sanitize.js`'s pass 1 begins with `copy source-dir → staging-dir` (design.md line 315)
   *before* classification starts, and that copy legitimately streams the full ~500 MB source file —
   so a correct implementation would have failed the old `<= 51 MiB` assertion for a reason unrelated
   to classification. The whole-file-API guard had the same defect in reverse: an unstated "the
   fixture path" could never actually distinguish a read of the source file (irrelevant) from a read
   of the staged copy (exactly what A2.1/A2.3 forbid). Fixed: the preload now requires an
   `INSTRUMENT_FS_TARGET_PATH` env var — the fixture's deterministic post-copy staged path, computed
   by the test harness before the child spawns — and only intercepts `fs.createReadStream`/whole-file
   calls whose resolved path equals exactly that target; every other call, including the copy step's
   read of the source file, passes through unmodified and uncounted. `test/instrument-fs.js`'s own
   self-test now covers both a matching path (counted) and a non-matching, copy-step-standing-in path
   (not counted).
2. **B5's aggregate-deadline wrapper contained a literal, non-executable placeholder (finding 2).**
   `<the Gradle convention block for burstAllowedThenThrottledWithDecrement>` inside `timeout 900
   bash -c '...'` could not be pasted or run. Fixed with a complete `run_five_times()` shell function
   (exported via `export -f` so `timeout 900 bash -c 'run_five_times'` can invoke it without nesting
   the Verification convention's own single-quoted `awk` programs inside another layer of single
   quotes, which would otherwise terminate the outer string early): five independently checked
   invocations of the exact FQCN/method, each asserting the numeric XML gate, `tee`-captured
   `BUILD SUCCESSFUL in Xs` duration evidence, fail-fast `return 1` on the first failing run, no
   retry or sixth run. Both the syntax and the `export -f`/`timeout bash -c` mechanism itself
   (including the failure-propagation path) were tested live in an isolated shell before being
   accepted, not merely written by inspection. Labeled explicitly as CI-bash-only, since `export -f`
   and `timeout` have no PowerShell equivalent.
3. **A15's untracked-residue check exited 1 on the very clean state it exists to confirm, and wrongly
   exempted the by-then-tracked SDD directory (finding 3).** `git status --porcelain --ignored=no |
   awk '{print $2}' | grep -v ... | grep -v ...` relied on the pipeline's own exit code as its
   pass/fail signal, but a final `grep -v` that selects zero lines — precisely the clean-state
   outcome the check exists to confirm — itself exits 1, and that failure aborts the whole script
   under this document's `bash -e -o pipefail` convention; reproduced directly against this repo.
   Fixed: `git ls-files --others --exclude-standard` (untracked-only, honors `.gitignore`, replacing
   the broader and column-fragile `git status --porcelain`) piped through the same junk-file filter,
   with `|| true` on the capturing assignment to absorb `grep -v`'s harmless zero-match exit; the
   captured value's *emptiness* is then asserted explicitly. Live-tested against this repo in both
   the current non-clean state (correctly fails, listing the untracked SDD files) and a simulated
   clean state (correctly passes, exit 0, no output). The stale SDD-directory exception was removed
   outright — the Prerequisite section already commits that directory to `main` before any Track A
   branch exists, so it cannot legitimately appear here, and exempting it could mask genuine
   untracked residue reappearing inside that path.
4. **B8's git-derived baseline variables never reached B9, which runs in a separate shell session
   (finding 4).** `BASE_SHA`/`prod_baseline`/`sibling_baseline` were set once in B8's shell; B9
   referenced the latter two without any mechanism to recreate them, so B9 as written could not
   actually be run standalone. Fixed: a single `verify_track_b_baseline()` function — taking only an
   FQCN and a source path, reading `BASE_SHA` from the environment — computes the expected count
   fresh from `git show "$BASE_SHA:<path>"` every time it runs (never from the live/edited file),
   runs the live Gradle convention, and compares them, failing on an unset/malformed base, a missing
   file, a zero or mismatched count, or any Gradle failure. B8 defines it, computes and prints
   `BASE_SHA` once (to be recorded in the PR description alongside the two resulting counts, exactly
   as Revision 6 already recorded the counts), and invokes it for both classes. B9 reproduces the
   *same* function definition in full — not a reference to B8's session — and invokes it with
   `BASE_SHA` set to the value recorded in the PR description, making the gate genuinely paste-
   runnable on its own. (This duplication was itself caught in self-review: an earlier draft of this
   fix had B9 merely comment-reference B8's function without reproducing it, which would have
   reintroduced finding 2's exact defect in a new location.)

## What changed since Revision 7 (checkpoint entry [53], three narrow executable-oracle findings)

1. **B9's recorded-`BASE_SHA` line was not actually executable shell (finding 1).** The displayed
   `BASE_SHA=<the literal value recorded in the PR description at B8 time>` is not an assignment at
   all: bash parses a bare `<` here as input redirection, so the line `bash -n`-fails with a syntax
   error (reproduced directly: exit 2, `syntax error near unexpected token 'newline'`). This directly
   contradicted Revision 7's own claim that the complete B9 block had been syntax-checked — the check
   had in fact been run against a hand-retyped scratch copy that used `$(git merge-base main HEAD)`
   in place of the placeholder, not the literal displayed line. Fixed with the standard bash
   fail-closed idiom: `: "${BASE_SHA:?export BASE_SHA to the exact value recorded by B8 before
   running B9}"` — itself ordinary, valid shell syntax, and it aborts with a clear message whenever
   `BASE_SHA` is unset or empty, confirmed both ways in isolation before being accepted.
2. **A15's `|| true` made the untracked-residue gate fail open on upstream tool errors, not just on
   `grep`'s harmless zero-match exit (finding 2).** It was attached to the entire
   `git ls-files | grep -v ...` pipeline, so a genuine `git ls-files` failure (reproduced with an
   invalid flag) was silently certified as "no unexpected residue" — exactly the failure mode a
   residue check exists to catch, now inverted into a false pass. Fixed by separating enumeration
   from filtering: `git ls-files --others --exclude-standard` is captured via the same
   `if command; then status=0; else status=$?; fi` idiom already used elsewhere in this document, and
   a non-zero status fails the gate immediately, before any filtering runs; the junk-file filter now
   uses `awk` (which exits 0 regardless of how many lines it selects, unlike `grep -v`) so no
   error-suppressing wrapper is needed on the filter step at all. Live-tested against this repo in
   all three states: clean (exit 0, no output), the current non-clean state (exit 1, lists the three
   untracked SDD files), and a simulated upstream enumeration failure via an invalid `git ls-files`
   flag (exit 1 with the enumeration-failure message — the exact case the old `|| true` swallowed).
   The stale traceability-matrix cell still naming the removed `git status --porcelain --ignored=no`
   oracle is also corrected.
3. **B5 could print `PASS` for a run even when its own required duration evidence was missing
   (finding 3).** `set -uo pipefail` alone didn't stop this: `grep`'s exit 1 on no match does
   propagate through the `| tail -1` pipe under `pipefail`, but nothing previously checked that
   status, so an empty `$duration` still reached the `echo "PASS ..."` line and the function never
   returned non-zero for it (reproduced directly: pipeline status 1, empty duration, function still
   exits 0). Fixed by checking both the extraction's own exit status and the non-emptiness of
   `$duration` explicitly; either failing prints a run-specific `FAIL` and `return`s 1, the same
   fail-fast path already used by the numeric XML gate. Confirmed both the present-duration (passes)
   and missing-duration (fails, returns 1) cases in isolation before being accepted.

**Verification methodology corrected for this round, in direct response to how finding 1 was
missed:** every fix above was checked by `sed`-extracting the exact line range from the completed,
on-disk Revision 8 document — never a retyped or paraphrased copy — before running `bash -n` or a
functional test. All five bash blocks in the document (A15's, B5's, B8's two blocks, B9's),
extracted this way, `bash -n` clean.

## Prerequisite (before either implementation branch is created)

The three approved SDD documents are currently uncommitted, on branch
`docs/rca-playwright-artifact-secret-exposure-and-rate-limit-flake`. Before Track A's
implementation branch is cut, they are committed to `main` via a docs-only commit/PR (no code
change, matches the existing `.kiro/specs/rca-login-403-and-access-denied/` precedent already on
`main`). Both implementation tracks branch from `main` *after* that merge.

## PR structure (frozen)

- **Two independent PRs. Track A (security) merges first.** Track B branches from the
  post-Track-A `main`, never from Track A's branch.
- **No task in this document combines Track A and Track B file changes.**
- **Final stop/go:** both PRs must merge and pass CI before the owner proceeds to Terraform Apply.
  Dispatching `deploy-azure.yml` for PR #101, and any Terraform operation, remain separately
  owner-authorized and are not gated by either Track A or Track B's own CI.
- **Branch protection.** Before either PR can be merged, the repository's required-status-checks
  configuration (an owner-side GitHub setting) must name all six `ci-verification.yml` jobs
  (`static-guard`, `unit-tests`, `integration-tests`, `pact-consumer`, `docker-build-verify`,
  `sanitizer-canary`) as required. This is an explicit pre-merge stop/go item in A15 and B9, not
  advisory prose — and is itself an owner action outside what `tasks.md` executes.

## Verification convention: durable, executable "did tests actually pass" checks

`build.gradle` documents its own hazard (`integrationTest`'s wiring comment: "Without this, the
task reports NO-SOURCE") — a filtered or misconfigured run can report `BUILD SUCCESSFUL` while
silently selecting zero tests. Two concrete, numeric, cross-platform check forms replace "confirm
via the report" throughout this document. Both clear/uniquely target the result location before
each run, check the runner's own exit code, and assert `count > 0 && failures == 0` — never merely
that a summary line is present.

**Gradle (`test`/`integrationTest`, filtered or not) — bash (CI form, matches how
`ci-verification.yml` actually invokes it on `ubuntu-latest`: `./gradlew`, not `.\gradlew.bat` —
entry [33] finding 1; status-capture corrected, entry [47] finding 6): GitHub Actions runs `bash`
steps under `-e` by default, so a plain `command; status=$?` sequence never reaches the assignment
when `command` fails — the whole script aborts at that line first, silently skipping the numeric
diagnostic. `if command; then status=0; else status=$?; fi` avoids `-e` firing at all, since the
command is now part of an `if` condition:**
```bash
rm -rf api-gateway/build/test-results/integrationTest
if ./gradlew :api-gateway:integrationTest --tests "<FQCN[.method]>" --rerun-tasks --no-daemon; then
  gradle_status=0
else
  gradle_status=$?
fi
count=$(grep -ho 'tests="[0-9]*"' api-gateway/build/test-results/integrationTest/TEST-*.xml 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END{print s+0}')
fails=$(grep -ho 'failures="[0-9]*"' api-gateway/build/test-results/integrationTest/TEST-*.xml 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END{print s+0}')
errs=$(grep -ho 'errors="[0-9]*"' api-gateway/build/test-results/integrationTest/TEST-*.xml 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END{print s+0}')
if [ "$gradle_status" -ne 0 ] || [ "$count" -eq 0 ] || [ "$fails" -ne 0 ] || [ "$errs" -ne 0 ]; then
  echo "FAIL: gradle=$gradle_status count=$count fails=$fails errs=$errs"; exit 1
fi
echo "PASS: $count tests, 0 failures/errors"
```

**Gradle — PowerShell (local Windows dev form):**
```powershell
Remove-Item -Recurse -Force api-gateway/build/test-results/integrationTest -ErrorAction SilentlyContinue
& .\gradlew.bat :api-gateway:integrationTest --tests "<FQCN[.method]>" --rerun-tasks --no-daemon
$gradleExit = $LASTEXITCODE
$count = 0; $fails = 0; $errs = 0
Get-ChildItem api-gateway/build/test-results/integrationTest -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
  [xml]$doc = Get-Content $_.FullName
  $count += [int]$doc.testsuite.tests
  $fails += [int]$doc.testsuite.failures
  $errs  += [int]$doc.testsuite.errors
}
if ($gradleExit -ne 0 -or $count -eq 0 -or $fails -ne 0 -or $errs -ne 0) {
  Write-Error "FAIL: gradle=$gradleExit count=$count fails=$fails errs=$errs"; exit 1
}
Write-Output "PASS: $count tests, 0 failures/errors"
```
(Substitute `build/test-results/test` and the plain `test` task for non-`integrationTest` runs. Use
the bash/`./gradlew` form in any task whose "Verify:" targets CI; use the PowerShell form for local
authoring/verification on this Windows workstation — both are given so either environment has an
exact, non-ambiguous command.)

**Node (`node --test`) — bash, corrected (entry [33] finding 1, entry [47] finding 6): rejects an
absent or non-numeric `tests`/`fail` value explicitly, not only a numeric zero; uses a
per-invocation `mktemp` file cleaned up via an `EXIT` trap (so cleanup runs on every exit path, not
only the two explicitly coded here); status capture uses the same `-e`-safe `if`/`else` form as the
Gradle command above:**
```bash
out_tap=$(mktemp)
trap 'rm -f "$out_tap"' EXIT
if node --test --test-reporter=tap <file-or-dir> > "$out_tap"; then
  node_status=0
else
  node_status=$?
fi
tests=$(grep -m1 '^# tests ' "$out_tap" | awk '{print $3}')
fail=$(grep -m1 '^# fail ' "$out_tap" | awk '{print $3}')
if [ "$node_status" -ne 0 ] \
   || ! printf '%s' "$tests" | grep -qE '^[0-9]+$' \
   || [ "$tests" -eq 0 ] \
   || ! printf '%s' "$fail" | grep -qE '^[0-9]+$' \
   || [ "$fail" -ne 0 ]; then
  echo "FAIL: node_status=$node_status tests=$tests fail=$fail"; exit 1
fi
echo "PASS: $tests tests, 0 failures"
```

**Node — PowerShell (corrected the same way):**
```powershell
$outTap = New-TemporaryFile
node --test --test-reporter=tap <file-or-dir> > $outTap.FullName
$nodeExit = $LASTEXITCODE
$testsLine = Select-String -Path $outTap.FullName -Pattern '^# tests (\d+)$' | Select-Object -First 1
$failLine  = Select-String -Path $outTap.FullName -Pattern '^# fail (\d+)$'  | Select-Object -First 1
Remove-Item $outTap.FullName -ErrorAction SilentlyContinue
if (-not $testsLine -or -not $failLine) {
  Write-Error "FAIL: node_exit=$nodeExit tests-line-present=$([bool]$testsLine) fail-line-present=$([bool]$failLine)"; exit 1
}
$tests = [int]$testsLine.Matches[0].Groups[1].Value
$fail  = [int]$failLine.Matches[0].Groups[1].Value
if ($nodeExit -ne 0 -or $tests -eq 0 -or $fail -ne 0) {
  Write-Error "FAIL: node_exit=$nodeExit tests=$tests fail=$fail"; exit 1
}
Write-Output "PASS: $tests tests, 0 failures"
```

Every "Verify:" step below that runs a `--tests`-filtered Gradle command or a `node --test` command
uses one of these four forms; they are not restated verbatim in every task. **The same `-e`-safe
status-capture discipline (`if command; then status=0; else status=$?; fi`, never bare
`command; status=$?`) and trap-based cleanup apply to every other multi-command bash sequence in
this document, not only these two shared forms — applied explicitly in A13's canary script below
(entry [47] finding 6).**

---

## Track A — Playwright artifact secret exposure (PR 1, merges first)

**Branch:** `fix/playwright-artifact-sanitizer` from `main` (post-prerequisite-merge).
**File scope for this track, exhaustively:**
- New: `.github/actions/sanitize-playwright-artifacts/{action.yml,sanitize.js,package.json,package-lock.json}`
  and its test directory (`test/*.test.js`, including `test/instrument-fs.js`, A6, entry [49]
  finding 1).
- New: `.github/actions/sanitize-playwright-artifacts/known-playwright-report-assets.json` (the
  authenticated package-asset allowlist manifest, A5) and
  `.github/actions/sanitize-playwright-artifacts/known-assets-manifest-tool.js` (`--generate`/
  `--verify` modes sharing one asset-walking implementation, A5/A13).
- New: `.github/actions/sanitize-playwright-artifacts/test/canary/` — `package.json`,
  `package-lock.json`, `playwright.config.ts`, `fixtures/canary.spec.ts`, `fixtures/local-server.ts`,
  `sync-canary-playwright-version.js` (`--write`/`--check` modes, A13, entry [47] finding 2).
  `test-results/`/`playwright-report/` are produced at run time, never committed.
- New: `.github/actions/sanitize-playwright-artifacts/.gitignore` — **actually ignores what
  Revision 5 only claimed was ignored (entry [49] finding 5: `git check-ignore` returned
  non-matching for every one of these paths against the repo's current, unmodified `.gitignore` —
  confirmed directly before writing this fix).** Contents, following this repo's own established
  per-Node-subproject convention (`frontend/.gitignore` already does exactly this for `frontend/`):
  ```
  /node_modules
  /test/canary/node_modules
  /test/canary/test-results
  /test/canary/playwright-report
  ```
- New: `scripts/.gitignore` — same convention, covering `scripts/node_modules/` (also confirmed
  currently un-ignored).
- New: `scripts/check-sanitizer-secret-wiring.js` plus its own `package.json`/`package-lock.json`
  and test directory.
- New: `scripts/playwright-upload-manifest.json`.
- Modified: `frontend/tests/e2e/helpers/browser-auth.ts`, `frontend/tests/e2e/helpers/api.ts` (A2 —
  the traced-call-to-`fetch()` swap only, each file's own error contract preserved).
- New: `frontend/tests/e2e/helpers/__tests__/capture-suppression.test.ts` (A2's durable behavior
  suite — entry [33] finding 2, entry [47] finding 1).
- Modified: `frontend/playwright.config.ts` — `use.trace` changes from `"retain-on-failure"` to
  `{ mode: "retain-on-failure", screenshots: false }` (A2, new since Revision 10).
- Modified: `.github/workflows/synthetic-monitoring.yml`, `frontend-e2e-integration.yml`,
  `frontend-ci.yml`, `ci-verification.yml`, `ci.yml`, `terraform.yml` — adding explicit `id:` to
  every existing `actions/upload-artifact` step (all 14, per A-IG.5) and, for the 5
  Playwright-output sites only, inserting the sanitize step (with its own `if:` baseline) and
  rewriting the upload step's `with.path`/`if:`.
- Modified: `.github/workflows/ci-verification.yml` — two new jobs (`static-guard`,
  `sanitizer-canary`) and one `needs:` change on the existing `unit-tests` job (A12).
- **Not part of the final diff:** a temporary `.github/workflows/_scratch-ivcheck.yml` used only
  during A14, deleted before the PR is opened — its absence is checked at A15.
- No other file is touched by this track.

### A1 — Composite action skeleton, dependency pin, install/audit wiring

**Design component:** "The composite action — complete, executable step sequence" (design.md
lines 766-819).
**Requirements:** A2.6, entry [15] finding 3, entry [13] finding 4, entry [17] finding 2.

Create `.github/actions/sanitize-playwright-artifacts/action.yml` reproducing the frozen sequence
exactly: `actions/setup-node@v4` (`node-version: "22"`) with **no** `working-directory`; then three
`shell: bash` steps (`npm ci --ignore-scripts`; `npm audit --omit=dev --audit-level=low`; the
sanitizer invocation), each with `working-directory: ${{ github.action_path }}`. Create
`package.json` declaring `yauzl@3.4.0` exactly, run `npm install` once locally to generate
`package-lock.json`, commit both. `sanitize.js` starts as a stub that reads
`SANITIZE_SOURCE_DIR`/`SANITIZE_STAGING_DIR`/`SANITIZE_MODE`/`SANITIZE_E2E_PASSWORD` from
`process.env` and exits 0 having copied `source-dir` to `staging-dir` verbatim.

**Verify:**
```
cd .github/actions/sanitize-playwright-artifacts && npm ci --ignore-scripts && npm audit --omit=dev --audit-level=low
```
Expected: exits 0, `yauzl@3.4.0` resolved in the lockfile, zero advisories at any severity. A
deliberately corrupted `package-lock.json` (one-off, reverted after) must make `npm ci` exit
non-zero. `grep -A2 "setup-node" action.yml` must show no `working-directory` key inside that
step's block; `grep -c working-directory action.yml` must equal exactly `3`.

### A2 — Layer 1: fetch swap plus global `screenshots: false`, each helper's own error contract preserved

**Design component:** "Layer 1 — capture suppression" including the Revision 12/13 `screenshots:
false` extension (design.md lines 236-266).
**Requirements:** entry [29] finding 1; entry [31] finding 2; entry [33] finding 2.

**Part A — the traced-fetch swap (unchanged from Revision 3).** Both
`frontend/tests/e2e/helpers/browser-auth.ts:26-28` and `frontend/tests/e2e/helpers/api.ts:14-19`
call the traced `request.post(...)` purely to establish a login session, each sending the
non-secret fallback literal `local-dev-password-2026`. **The two files' current error handling is
different and each must keep its own shape:**

- `browser-auth.ts`'s `installGatewaySessionInitScript` currently **throws** on a non-OK response
  (`if (!res.ok()) throw new Error(...)`). Replace `request.post(...)` with `fetch(...)`
  (`method: "POST"`, `headers: {"Content-Type":"application/json"}`, JSON-stringified body), change
  `res.ok()`/`res.status()` to native `res.ok`/`res.status`, keep the throw with equivalent
  diagnostics (`res.status`, `await res.text()`). The exported signature
  (`page: Page, request: APIRequestContext`) is **unchanged** — every existing spec file calls this
  function with both arguments, and `request`'s type is still needed for the parameter's type
  annotation even though the value is no longer read inside the function body.
- `api.ts`'s `resolveUserId` currently wraps the call in `try {...} catch {}` and **silently falls
  back to `"user-001"`** on either a network failure (caught) or a non-OK response (the `if
  (res.ok())` gate simply not matching, falling through to the bottom). Replace `request.post(...)`
  with `fetch(...)` the same way, preserving both fallback paths exactly. `resolveUserId` is **not
  exported** and its `request: APIRequestContext` parameter becomes genuinely unused once the login
  call no longer needs it — remove the parameter from `resolveUserId`'s signature and update its one
  internal call site inside `ensurePortfolioWithHoldings`. `ensurePortfolioWithHoldings`'s own
  exported signature is unchanged.
- Neither file's `import type { APIRequestContext } from "@playwright/test"` is removed — both
  files still use the type elsewhere.

**Part B — global `screenshots: false` (new, design.md's Revision 12/13 Layer 1 extension).**
`frontend/playwright.config.ts`'s `use.trace` changes from the shorthand `"retain-on-failure"` to
`{ mode: "retain-on-failure", screenshots: false }`. This is a **global** `use` change — it affects
every project, including the eight DOM-login tests that keep full trace/action/network/DOM-snapshot
capture; only the screenshot layer is removed, for every test, and design.md's own reasoning is why:
under the frozen classifier (A4/A5), a screenshot entry inside a trace ZIP makes the whole archive
Outcome B regardless of which test produced it, so global suppression is what makes any successful
trace upload possible at all, not a reduction in what any test can safely publish.

**Test-first (Part A, and entry [33] finding 2's durable test file — was named but never committed
in Revision 3):** create
`frontend/tests/e2e/helpers/__tests__/capture-suppression.test.ts` (Vitest, matching this repo's
existing frontend unit-test runner and `package.json`'s `test` script — not a one-off manual check)
with one case per outcome per helper, each using a local stub `http` server (the same throwaway-
server technique used in A-IG.1/A13): `browser-auth.ts` — a 200 response resolves normally; a 401
response is asserted to throw with the status/body in the message; a connection-refused target is
asserted to throw. `api.ts` — a 200-with-`userId` body resolves to that `userId`; a 401 response
resolves to `"user-001"` (no throw); a connection-refused target resolves to `"user-001"` (no
throw). Since `resolveUserId` is not exported, these cases exercise it indirectly through
`ensurePortfolioWithHoldings`'s exported surface (stubbing the downstream portfolio/holdings calls
too, only asserting on the resolved-user-id-dependent JWT `sub` claim) — the production helper API
is not widened solely to make this test possible.

**Verify:** `cd frontend && npx tsc --noEmit && npm run lint` — both must exit 0; if either flags the
now-unused-but-still-declared `request` parameter in `browser-auth.ts` (repository check:
`frontend/tsconfig.json` has neither `noUnusedParameters` nor `noUnusedLocals` set, and
`frontend/eslint.config.mjs` has no custom `no-unused-vars` override — confirmed by running the
commands, not assumed), resolve it with the narrowest fix that does not change the exported
signature. `cd frontend && npx vitest run tests/e2e/helpers/__tests__/capture-suppression.test.ts`
— exactly 6 test cases (3 per helper), all green; a non-zero-count check against Vitest's own JSON
reporter output (`--reporter=json`, asserting `numTotalTests > 0 && numFailedTests == 0`) is the
executable "did it actually run" signal, matching this document's verification convention in spirit
for a runner not covered by the Node/Gradle forms above. Run
`npx playwright test tests/e2e/golden-path.spec.ts --project=chromium` — must still pass. Then, as a
one-off (not committed), force a failure in a spec exercising `installGatewaySessionInitScript`/
`resolveUserId` and inspect the resulting `trace.zip`: the login POST's URL/body must be **absent**
from every `resources/<hash>.json` and both `*-trace.trace` JSONL logs, while the rest of that
spec's own DOM/API activity remains present; separately, confirm no `resources/page@*.jpeg` entry
exists anywhere in that same `trace.zip` (Part B's effect). Revert the temporary failure immediately
after.

### A3 — Content classifier core: yauzl-first decision, strict-UTF-8-gated fallback

**Design component:** "Content classification — deferred to the real parser" (design.md lines
351-419).
**Requirements:** A2.1, A2.3, A-IG.6, entry [17] finding 1, entry [19] finding 2.

Test-first. Add `.github/actions/sanitize-playwright-artifacts/test/classify.test.js` (`node
--test`) with, at minimum:
- a renamed valid ZIP (must classify ZIP);
- the entry [17] truncated-local-header fixture, `Buffer.from([0x50,0x4b,0x03,0x04,0x61,0x62,0x63])`
  (must be `UNINSPECTABLE`, never `TEXT`);
- ordinary UTF-8 text containing ZIP-like bytes mid-file but no full record signature (must be
  `TEXT`);
- **a gzip-compressed dummy sentinel** (`zlib.gzipSync(Buffer.from("TestPassword123!"))`, 36 bytes —
  must be `UNINSPECTABLE`, closing the exact fail-open A-IG.7 documented against Revision 11);
- a mislabeled binary that is not an allowlist match (e.g. a JPEG byte sequence renamed `.json`,
  must be `UNINSPECTABLE`);
- a self-extracting-style ZIP with a prepended non-ZIP payload (must be `ZIP`, via `yauzl`'s own
  EOCD scan);
- an over-`TOP_LEVEL_FILE_BYTE_LIMIT` (50 MB) ordinary UTF-8 file (must abort mid-stream as
  `UNINSPECTABLE`);
- a sentinel and a ZIP-record signature each deliberately split across the classifier's own chunk
  boundary (must still be caught via the overlap window).

All fixtures red before `classify(filePath, stagingDirRoot)` exists. Implement per design.md's
pseudocode (lines 455-530): `yauzl.open()` first; on rejection, the allowlist-candidacy check (A5
implements the allowlist branch itself — this task implements the non-candidate path, which is
simply "no allowlist lookup, strict UTF-8/control-byte/ZIP-signature/budget gate, else Outcome B");
the single bounded streaming pass (`fs.createReadStream`, 1 MiB high-water mark, sliding-window
overlap, incremental fatal `TextDecoder`, unconditional end-of-stream flush).

**Verify:** Node convention against `test/classify.test.js` — all fixtures green.

### A4 — Structured archive scan: entries must be inspectable, not just scanned

**Design component:** "Complete metadata and raw-byte coverage" + "scanning entry content is not
the same as proving it inspectable" (design.md lines 578-618).
**Requirements:** A2.1, entry [17] finding 3, entry [9] finding 1 (reasserted).

Test-first: fixtures for a sentinel present only in `ZipFile.comment`; only in an entry's
`extraFieldRaw`; only in a raw (undecoded) entry name where the decoded form differs — each on an
otherwise fully-text/inspectable archive. **Then the entry [9]-reasserting cases, corrected from
Revision 3's now-outdated version:** a ZIP containing one binary (non-UTF-8-decodable), non-
allowlisted entry alongside otherwise-clean text entries — both the case where that binary entry
happens to carry a raw-byte-detectable match, and the case where it carries **no** detectable match
at all — **both must be Outcome B, the whole archive**, never a clean pass inferred from "the raw
scan found nothing." A ZIP where every entry is either strictly-valid UTF-8 text or a nested valid
ZIP (recursed into, same rule, up to the 5-level cap) and no match is found anywhere — must leave the
whole archive as-is.

Implement the structured scan (all four sentinel variants over every field in design.md's "Exposed"
column) *and* the per-entry inspectability gate: an entry must decompress to strictly-valid UTF-8
text, or itself be a nested valid ZIP, before the "no match found → leave as-is" outcome is
available to it; otherwise the containing archive is Outcome B regardless of scan results.

**Verify:** Node convention against `test/structured-scan.test.js`.

### Implementation order for A5/A13 (breaks the circular dependency — entry [47] finding 3)

Read literally, A5 (build the allowlist mechanism, then generate the manifest by running "A13's
canary") and A13 (run the canary, which requires A5's tool/manifest/sanitizer to verify) each depend
on the other having already finished — no valid starting point. The **acyclic** build order, which
both tasks below follow explicitly:

1. **A5, phase 1:** implement `isValidTraceSegment`, `toCanonicalTracePath`, `validateManifestTracePath`,
   `classify()`'s allowlist branch, and `known-assets-manifest-tool.js`'s `--generate`/`--verify`
   logic — all tested against **synthetic fixture data** (arbitrary byte content, hand-constructed
   manifest entries with matching digests computed the same way the tool computes them). None of
   this requires a real Playwright run or a populated `known-playwright-report-assets.json` to exist
   yet.
2. **A13, phase 1:** create the canary fixture *project* (`package.json`, the version-sync step,
   `playwright.config.ts`, `fixtures/canary.spec.ts`, `fixtures/local-server.ts`) and run it once,
   producing real `test-results/`/`playwright-report/` output. This requires A1's composite action
   scaffold to exist (for `sanitize.js`'s location, referenced by relative path) but requires
   **nothing** from A5 beyond the plain filesystem — no manifest, no tool invocation yet.
3. **A5, phase 2:** run `known-assets-manifest-tool.js --generate` against A13 phase 1's real,
   just-produced `playwright-report/` output, once, producing and committing
   `known-playwright-report-assets.json`. Complete A5's remaining fixtures that need this **real**
   manifest to exist (the two real `1.59.0` assets presented with/without a correct allowlist entry,
   the exhaustive-equality mismatch fixtures).
4. **A13, phase 2:** with A3–A9 (the full sanitizer) and A5 (the populated, committed manifest) both
   complete, run A13's full dirty/clean/manifest-`--verify` sequence.

A5's own task text below describes its phases 1 and 2; A13's describes its phases 1 and 2
separately (labeled "Part 1"/"Part 2" there for clarity, interleaved with A5's own phases as shown
in the four-step order above). Neither task's own internal fixtures depend on the other task having
finished — only the *documented build order* is sequential, and it has one valid starting point.

### A5 — Authenticated package-asset allowlist: canonical paths, manifest, single-pass merge

**Design component:** "Two functions for two different input representations" +
`toCanonicalTracePath`/`validateManifestTracePath`/`classify()`'s allowlist branch + "The
authenticated package-asset allowlist" (design.md lines 383-570) — new since Revision 3's baseline.
**Requirements:** entry [37] findings 1–3; entry [39] findings 1–3; entry [41]; entry [43]; entry
[47] finding 3 (acyclic ordering with A13).

This is the largest new piece of Track A since Revision 3. Test-first, each bullet its own `node
--test` case in `.github/actions/sanitize-playwright-artifacts/test/allowlist.test.js` before
assembly:

- `isValidTraceSegment`: rejects `""`, `"."`, `".."`, any segment containing `\`, a NUL byte, or any
  other C0 control byte; accepts an ordinary filename segment.
- `toCanonicalTracePath(filePath, stagingDirRoot)` (scan-time, resolved filesystem paths):
  positives — `trace/codicon.DCmgc-ay.ttf`, a nested `trace/assets/x.js`, both resolve to
  themselves; negatives — `trace/../evil.bin`, `playwright-report/trace/codicon.DCmgc-ay.ttf` (a
  wrongly-rooted aggregate shape), an absolute path, a literal backslash segment, a sibling-root
  escape (`../other-staging-dir/trace/x`) — every negative returns `null`; a dedicated
  argument-order fixture pins `path.relative(stagingDirRoot, filePath)` (not the reverse) by
  asserting `stagingDirRoot=/staging, filePath=/staging/trace/x` resolves to `"trace/x"`.
- `validateManifestTracePath(raw, stagingDirRoot)` (raw manifest strings): the exact two strings
  that alias a legitimate entry once resolved — `trace/./evil.bin` and `trace/a/../evil.bin` — must
  both return `null`, rejected on their **raw segments** before any `path.resolve` call could
  collapse either into `trace/evil.bin` (assert this aliasing directly:
  `path.resolve(stagingDirRoot, "trace/./evil.bin") === path.resolve(stagingDirRoot,
  "trace/evil.bin")`, proving the risk is real, then assert `validateManifestTracePath` still
  returns `null` for the raw string); also a raw absolute path, a raw backslash segment, a raw
  NUL/control byte. **Property/table test (not a symlink-based mismatch fixture — that branch is
  provably unreachable given `path.resolve`/`path.relative`'s purely lexical behavior, entry [43]):**
  for every accepted raw path, assert `validateManifestTracePath(raw, root) === raw`; for every
  rejected raw path, assert `null` is returned before the equality check is ever reached.
- `classify()`'s allowlist branch, single-pass (design.md lines 456-530): a genuine
  `trace/`-relative candidate whose digest matches a fixture allowlist entry — clean (scanned, not
  found) or Outcome A (scanned, found) as appropriate, **regardless of whether its content
  independently decodes as UTF-8** (a fixture `.ttf`-shaped binary blob authenticated this way must
  pass); the same candidate with a wrong digest (tampering/drift simulation) — falls through to the
  ordinary UTF-8-gated rule; **incomplete-trailing-sequence fixture:** a canonical `trace/
  incomplete.txt` with no matching manifest digest, content ending in a lone `0xE2` byte — must be
  Outcome B (the decoder is flushed unconditionally, for every candidate, not only non-candidates —
  the exact gap entry [39] found); a single-pass-read fixture: an instrumented test double (a
  counting wrapper around `fs.createReadStream`) confirms `classify()` opens exactly one read stream
  per allowlist-candidate file, never a separate digest pass and a separate scan pass.

Implement `known-assets-manifest-tool.js` with `--generate <playwright-report-dir> <manifest-path>`
(walks the real, just-generated `playwright-report/trace/` directory, computes `{path, sha256}` for
every file relative to `playwright-report/`, writes `{ playwrightTestVersion, assets: [...] }`) and
`--verify <playwright-report-dir> <manifest-path>` (re-derives the same set and requires **exact**
equality against the checked-in manifest — no missing, extra, or digest-mismatched entry — plus
requires `manifest.playwrightTestVersion` to equal `frontend/package-lock.json`'s locked
`@playwright/test` resolution **and** requires `playwright`/`playwright-core` to resolve to that
identical version). Schema-validate the manifest before either mode trusts it: `{
playwrightTestVersion: string, assets: [{path, sha256}] }`, every `path` passing
`validateManifestTracePath`, every `sha256` exactly 64 lowercase hex characters, no duplicate paths,
no unexpected fields.

**Generate the initial manifest as "A5, phase 2"** (per the "Implementation order for A5/A13" note
above) — after A13 phase 1 has produced one real canary run, run `node known-assets-manifest-tool.js
--generate` against its `playwright-report/` output and commit the result. At the exact locked
`1.59.0`, this must produce exactly 16 assets, matching A-IG.7 Part 4's real digests for the 3 that
require the allowlist (`trace/codicon.DCmgc-ay.ttf`, `trace/assets/defaultSettingsView-GTWI-W_B.js`,
`trace/uiMode.Vipi55dB.js` — filenames are hash-suffixed per Playwright build and must be read from
the actual locally-generated output, not assumed stable across a future bump).

**Manifest schema/exhaustive-equality fixtures:** a manifest entry with a non-canonical path
(`../trace/x`, a backslash, a leading slash, outside `trace/`), a malformed digest (wrong length,
uppercase, non-hex), a duplicate path — each fails schema validation before any lookup; a
`--verify` run against a manifest with one entry added beyond what the real producer ships, and,
separately, one real producer asset omitted — both fail the exact-equality check; a `--verify` run
with `playwrightTestVersion` not matching the lockfile, and, separately, a simulated
`playwright`/`playwright-core` divergence from `@playwright/test` — both fail closed.

**Verify:** Node convention against `test/allowlist.test.js`. `node known-assets-manifest-tool.js
--verify <path-to-a-fresh-local-canary-run>/playwright-report known-playwright-report-assets.json`
exits 0 against the checked-in manifest (proving it matches the real, current, locked-version
output at authoring time — this same invocation becomes A13's CI check).

### A6 — Raw whole-archive scan, hostile-archive limits, and a true peak-memory resource proof

**Design component:** "Hostile-archive limits" + "Frozen numeric thresholds" table (design.md lines
728-757) + the raw-scan streaming correction (lines 620-641).
**Requirements:** A2.1, A2.3, entry [15] finding 3, entry [17] finding 4, entry [29] finding 7,
entry [31] finding 3, entry [33] finding 3, entry [47] finding 5, entry [49] finding 1,
entry [51] finding 1.

Test-first: a sentinel present only in a hand-crafted Local File Header or Data Descriptor region;
a sentinel deliberately split across a raw-scan chunk boundary; an over-per-entry-limit (50 MB)
entry, aborted mid-stream; an over-per-archive-limit (200 MB) archive; global-budget exhaustion via
many small archives in one sanitize invocation; a >100:1 compression-ratio entry; a CRC-32 mismatch;
>5 levels of nesting; >5,000 entries; a path-traversal entry name; duplicate entry names; a symlink
entry inside a ZIP; a symlink encountered during the initial `source-dir` → `staging-dir` copy;
unsupported-compression and encrypted ZIPs. Every one of these is Outcome B (whole-upload abort).

Implement: `zlib.crc32()` incremental streaming check against `entry.crc32`; the raw-archive
`fs.createReadStream` scan sharing the same budget object as A3/A4/A5; all hostile-archive rules,
each wired to abort the entire sanitize step.

**Resource oracle, corrected to a true peak-memory measurement (entry [33] finding 3, replacing
Revision 3's interval-sampling design).** Interval sampling (a `setInterval` poller reading
`/proc/<pid>/status` every 100 ms) can miss a short-lived allocation-then-collapse between samples —
waiting for the child's `'exit'` event only guarantees the poller stopped at the right time, not
that it caught the true peak. **Fixed:** use a kernel-level maximum-RSS measurement that cannot miss
a transient peak, spawning the sanitizer child under GNU `/usr/bin/time -v` (present on
`ubuntu-latest`; the composite action only ever runs there, per A1's `action.yml`) and parsing its
`Maximum resident set size (kbytes): N` line — a single kernel-reported number covering the whole
child lifetime, not a sampled approximation.

**Fixture target corrected once — a ZIP entry does not discriminate streaming from a metadata
shortcut (entry [47] finding 5).** `yauzl` exposes an entry's declared uncompressed size from the
central directory *before* any decompression begins; a ZIP-entry fixture cannot tell a genuinely
streaming implementation apart from a metadata-only rejection. **The resource proof instead targets
the top-level-file fallback path** (`classify()`'s post-`yauzl.open()`-rejection branch, A3).

**Corrected a second time — `fs.stat`/`fs.statSync` is itself a metadata shortcut for a plain file
too (entry [49] finding 1).** Revision 5 claimed "a plain file's size is knowable only by actually
reading it" — false, confirmed by direct execution: `fs.statSync(path).size` returns a file's exact
byte length via a single syscall, without ever opening or reading its content. An implementation may
legitimately (or accidentally) `stat` the candidate before ever calling `createReadStream`, reject it
from that alone, and produce the identical exit code, message, and low RSS the black-box test
already asserts — proving nothing about whether the *actual* over-limit-content path streams. A
black-box RSS/exit-code assertion cannot close this gap by construction: **any** correct
size-limit-triggering implementation, streaming or not, produces the same outwardly-observable
result. Closing it requires a **white-box** assertion that the classifier's own stream dependency was
actually exercised, not just that the file was correctly rejected.

**Fixed: instrument `fs.createReadStream`/whole-file-read APIs via a `--require` preload, scoped to
one exact staged-file path so the frozen design's own `source-dir → staging-dir` copy stream can
never be conflated with — or substituted for — the classifier's own read (entry [51] finding 1).**
Revision 6's preload counted bytes across *every* `fs.createReadStream` call in the child process,
with no path check at all. `sanitize.js`'s pass 1 begins with `copy source-dir → staging-dir`
(design.md line 315) *before* classification ever starts, and that copy legitimately streams the
full ~500 MB source file — so an implementation that copies correctly and *then* classifies
correctly would still have failed the old, unscoped `<= 51 MiB` assertion, for a reason that has
nothing to do with classification. Symmetrically, a whole-file-API guard keyed to an unstated
"the fixture path" could never actually distinguish `readFile(sourceFile)` (irrelevant — happens, if
at all, during the copy, not the scan) from `readFile(stagedCopy)` (exactly what A2.1/A2.3 forbid).

**Fixed:** the preload now requires an `INSTRUMENT_FS_TARGET_PATH` environment variable — one
absolute, already-`path.resolve`d path — and only intercepts calls whose argument resolves to
exactly that path:
- `fs.createReadStream`: only a call whose resolved path `=== INSTRUMENT_FS_TARGET_PATH` is wrapped
  to accumulate `totalStreamedBytes` from its `'data'` events and set `createReadStreamCalled =
  true`. Every other call — including the copy step's read of the *source* file, which resolves
  under `source-dir`, a different path entirely — passes through completely unmodified and
  uncounted.
- `fs.readFileSync`/`fs.promises.readFile`/`fs.readFile`: only a call whose resolved path
  `=== INSTRUMENT_FS_TARGET_PATH` throws. Any whole-file read of some unrelated file elsewhere in
  the sanitizer's own logic (e.g. a small config or manifest file) is left alone — the guard targets
  exactly the file under test, nothing else.
`fs.statSync` itself remains deliberately unblocked (the sanitizer's path-validation logic
legitimately needs `fs.stat`-family calls for other reasons, e.g. symlink checks); the target-path
scoping above is what makes the byte-count assertion meaningful regardless.

Because design.md's copy step preserves `source-dir`'s relative structure verbatim when producing
`staging-dir` (it is a 1:1 copy, not a transform), a single top-level fixture file named
`big-toplevel.bin` placed at the root of `source-dir` is deterministically staged at
`path.join(stagingDirRoot, "big-toplevel.bin")` — computable by the test harness before the child
ever spawns, with no dependency on the copy step's internal implementation.

**`test/instrument-fs.js`'s own unit-level self-test now covers both a matching and a
non-matching path, not only the happy path (entry [51] finding 1's explicit requirement):**
(a) with `INSTRUMENT_FS_TARGET_PATH` set to a temp file `T`, an in-process `fs.createReadStream(T)`
read is counted (`totalStreamedBytes` increases, `createReadStreamCalled` becomes `true`); (b) with
`INSTRUMENT_FS_TARGET_PATH` still set to `T`, an in-process `fs.createReadStream(otherFile)` read of
a *different* temp file — standing in for the copy step's read of `source-dir` — leaves
`totalStreamedBytes === 0` and `createReadStreamCalled === false` for that call. Both cases are
asserted before the instrumentation is trusted inside the resource test.

**Generate the 500 MB fixture itself in bounded chunks** (the test harness writing it via
`fs.createWriteStream` and repeated smaller buffer writes in a loop, never `Buffer.alloc(500_000_000)`
in one shot) — the fixture-creation step is bound by the same "don't buffer whole files" discipline
being tested, not exempt from it.

Add `.github/actions/sanitize-playwright-artifacts/test/hostile-archive-resource.test.js`
(`node --test`, runs as the **outer** process and must itself always exit 0):
1. Provide the exact `GITHUB_WORKSPACE`/`RUNNER_TEMP` fixture environment the path contract
   requires so the sanitizer's own path validation doesn't reject the fixture before either check is
   exercised. Write the 500 MB fixture as `path.join(sourceDir, "big-toplevel.bin")` (a single
   top-level file, per the paragraph above), so its deterministic post-copy staged location is
   `path.join(stagingDir, "big-toplevel.bin")`.
2. Compute `stagedTargetPath = path.resolve(stagingDir, "big-toplevel.bin")` before spawning — this
   is the one path the preload is allowed to intercept. `child_process.spawn("/usr/bin/time", ["-v",
   "node", "--require", "<path>/instrument-fs.js", "sanitize.js"], { cwd: actionDir, env: { ...baseEnv,
   INSTRUMENT_FS_OUTPUT: instrumentOutputPath, INSTRUMENT_FS_TARGET_PATH: stagedTargetPath },
   stdio: ["ignore", "pipe", "pipe"] })` against the 500 MB top-level file as `source-dir`.
3. On the child's `'exit'` event: parse `stderr` for `Maximum resident set size (kbytes): (\d+)`;
   read and parse the `instrumentOutputPath` JSON summary; resolve a `Promise` carrying
   `{ exitCode, stdout, stderr, maxRssKb, createReadStreamCalled, totalStreamedBytes,
   wholeFileApiCalled }`.
4. Assert, in the outer test — **the white-box assertions are what actually prove streaming; the
   RSS assertion corroborates them, it does not stand alone:**
   - `createReadStreamCalled === true` (the metadata-only escape, if taken, would leave this
     `false` — this is the assertion that directly closes entry [49] finding 1);
   - `wholeFileApiCalled === false`;
   - `totalStreamedBytes > 50 * 1024 * 1024` (must have read past `TOP_LEVEL_FILE_BYTE_LIMIT` to
     correctly detect the overage) **and** `totalStreamedBytes <= 51 * 1024 * 1024` (no more than
     one 1 MiB chunk — the classifier's own `highWaterMark` — beyond the limit before aborting).
     **This bound describes bytes read from the one classified staged file only** — because both
     the counter and the guard above are scoped to `INSTRUMENT_FS_TARGET_PATH`, the copy step's read
     of the ~500 MB *source* file (a different, unintercepted path) cannot inflate this number, and
     an implementation that reads the staged file whole cannot evade the guard by using a different
     path (entry [51] finding 1);
   - `exitCode !== 0`; `stdout + stderr` contains the sanitizer's specific `TOP_LEVEL_FILE_BYTE_LIMIT`
     message; `stderr` does **not** contain `JavaScript heap out of memory`; `maxRssKb * 1024 < 150 *
     1024 * 1024` (150 MB ceiling, corroborating evidence: a whole-buffer read of the true 500 MB
     content would clearly exceed this).

**Verify:** Node convention for `test/hostile-archive.test.js` (main fixture set, including the
still-valid ZIP-entry limit fixtures, and `test/instrument-fs.js`'s own unit-level self-test —
confirming it correctly records a deliberate small in-process `createReadStream` call and correctly
throws on a deliberate `readFileSync` call, before it is trusted inside the resource test). Node
convention for `test/hostile-archive-resource.test.js` — the **outer** suite itself must show
`tests>0, fail=0`; inside that green run, the white-box stream-byte-count assertions (not RSS alone)
are what prove the streaming property.

### A7 — Two-pass sanitize/verify state machine (Outcome A/B, read-only pass 2)

**Design component:** "State machine — corrected to match frozen A2.3 exactly" (design.md lines
268-349).
**Requirements:** A2.2, A2.3, A2.5, A-IG.6, entry [15] finding 1.

Test-first: a plain-text match (Outcome A); a match nested inside an otherwise-valid ZIP (Outcome
A, whole archive replaced); a match present only inside an image inside a ZIP (Outcome B — per A4,
an uninspectable entry, matched or not, is never Outcome A); a clean artifact (nothing changed); the
post-sanitize pass finding a residual match (Outcome B); a scanner internal error (Outcome B); a
missing `source-dir` (empty, successful `staging-dir`).

Implement `sanitize.js`'s top-level orchestration: path validation (`source-dir` canonicalizes
inside `$GITHUB_WORKSPACE`; `staging-dir` is a fresh non-symlink child of `$RUNNER_TEMP`); the copy
step; pass 1 (mutating, calling A3–A6's classifier/scanners); pass 2 (identical logic, read-only,
including A5's allowlist branch).

**Verify:** Node convention against `test/state-machine.test.js`, plus a full `node sanitize.js` run
against each fixture directory, asserting exit code and, for Outcome A cases, `staging-dir`'s
content byte-for-byte.

### A8 — Sentinel transport: mode split, action-owned literals, exact e2e-password handling

**Design component:** "Sentinel transport — action-owned, mode-split" (design.md lines 830-859).
**Requirements:** entry [15] finding 1, entry [15] finding 2.

Test-first, dummy values only: `mode=live-secret` with a non-empty `e2e-password` (accepted);
`mode=live-secret` with an empty `e2e-password` (Outcome B); `mode=fallback-only` with
`e2e-password` unset (accepted, sentinel list is exactly `KNOWN_NON_SECRET_LITERALS`);
`mode=fallback-only` with `e2e-password` set anyway (Outcome B); an unrecognized `mode` (Outcome B).
Implement exactly per design.md, including the fixed `KNOWN_NON_SECRET_LITERALS` array
(`"local-dev-password-2026"`, `"TestPassword123!"`, `"e2e-test-password-2026"`).

**Verify:** Node convention against `test/sentinel-transport.test.js`. Confirm by direct source
inspection (`grep -rn "SANITIZE_E2E_PASSWORD\|e2e-password"
.github/actions/sanitize-playwright-artifacts/`) that the value never appears interpolated into a
shell string, `$GITHUB_OUTPUT`/`$GITHUB_STEP_SUMMARY`, a filename, or a placeholder message.

### A9 — Static guard script

**Design component:** "Independent static guard" (design.md lines 861-1244).
**Requirements:** A2.6, A-IG.5, entry [15] finding 4, entry [17] finding 3, entry [19] findings 1/3,
entry [21] findings 1/2, entry [23] findings 1/2/3, entry [25] findings 1/2.

Create `scripts/check-sanitizer-secret-wiring.js` with its own `package.json` pinning
`js-yaml@4.3.0` and lockfile, installed the same way as A1. Test-first, each bullet its own `node
--test` case before the end-to-end script is assembled:

- `isCanonicalWorkflowPath`: accepts `.github/workflows/x.yml`/`.yaml`; rejects `../outside.yml`,
  `/.github/workflows/x.yml`, `C:/x.yml`, `.github/workflows/a/../x.yml`,
  `.github/workflows//x.yml`, and `.github/workflows/x\y.yml`.
- `listWorkflowFiles()`: a mocked `fs.readdirSync` returning `["x.yml"]` produces exactly
  `[".github/workflows/x.yml"]`; a mocked basename `x\y.yml` is rejected.
- `validateManifestSchema`: rejects a non-array top-level value; a `playwright:true` entry missing
  `baseline` or with a value other than `"always"`/`"failure"`; a `playwright:false` entry carrying
  `baseline`; a missing/non-boolean `playwright`; an unexpected extra field; duplicate
  `{workflow,job,stepId}` keys (including a conflicting-value pair).
- `validateNoDuplicateDiscovered`: two discovered steps in one job resolving to the same `stepId`.
- `usesSanitizerAction`: exact match against `./.github/actions/sanitize-playwright-artifacts`
  (with/without trailing slash); rejects a similarly-named but different action reference.
- `referencesSecretIndependently`: detects dot and index syntax (case-insensitive, both quote
  styles, permitted whitespace); detects a reference at `job.container.credentials.password`,
  `job.services`, `job.outputs`, or job-level `if`; returns `false` when the *only* reference is
  inside a sanitizer step's own `with:`.
- Cardinality + mode check: 2 sanitizer steps in one job fails outright; an independent reference
  with a missing/wrong-mode/wrong-expression sanitizer fails; a credential-free job with a
  malformed-mode sanitizer fails; a credential-free job whose sanitizer has an explicitly-empty
  `e2e-password: ""` fails.
- `isProvenConjunctiveGate`: accepts only the exact conjunction matching the classified upload's
  `baseline`; rejects the crossed case both directions; rejects all three entry [23] fail-open
  expressions.
- Upload-inventory diff: a newly discovered step with no manifest entry fails; a stale manifest
  entry with no matching discovered step fails; a `playwright:false` entry with no wiring passes; an
  upload whose predecessor/path are correct but whose `if:` omits the gate fails.

**Verify:** `cd scripts && npm ci --ignore-scripts && npm audit --omit=dev --audit-level=low`, then
the Node convention against the assembled test suite. Then `node check-sanitizer-secret-wiring.js`
against the real, modified (A10) workflow tree — expected green.

### A10 — Wire the 5 real call sites, including each sanitizer's own baseline condition

**Design component:** "GitHub Actions condition flow" (design.md lines 1250-1284) — the exact YAML
frozen there.
**Requirements:** A2.1–A2.6 (end-to-end), A1.5 (bugfix.md); entry [33] finding 4.

For each of the 5 confirmed Playwright-upload sites: add `id: sanitize` to a new step immediately
preceding the existing upload step; **set that sanitize step's own `if:` to its site's baseline
condition explicitly** — `if: always()` for the two `synthetic-monitoring.yml` jobs, `if: failure()`
for the other three — reproducing design.md's frozen YAML exactly, not only the upload step's
conjunction (Revision 3's gap: with the sanitizer step left at its default implicit `success()`
condition, it would be skipped in precisely the failed-test cases the three `failure()`-baseline
uploads exist to capture, silently removing every safe failure artifact); set the sanitize step's
`mode`/`e2e-password` (`live-secret` + the real secret for the two `synthetic-monitoring.yml` jobs
only, `fallback-only` for the other three); point the upload step's `path:` at the sanitize step's
`staging-dir`; set the upload step's `if:` to the exact conjunctive form matching that site's
baseline. For the remaining 9 non-Playwright sites, add only an explicit `id:`.

**Verify:** `js-yaml`'s `load()` against every changed file confirms it still parses. Manual diff
review confirms exactly 5 sites gained a sanitize step and exactly 14 total sites gained an `id:`.
An executable structural assertion (a small one-off script, or A9's own guard run against the
modified tree) confirms every one of the 5 sanitize steps' own `if:` literally equals
`always()`/`failure()` per its site's baseline — not merely that the upload step's conjunction
mentions it.

### A11 — Checked-in upload manifest

**Design component:** "Upload classification — a checked-in, exhaustively-diffed manifest" (design.md
lines 900-910).
**Requirements:** A2.6, A-IG.5, entry [19] finding 3, entry [25] finding 2.

Create `scripts/playwright-upload-manifest.json` with exactly 14 entries, derived by re-running
`rg -c "uses: actions/upload-artifact" .github/workflows/*.yml` against the then-current tree and
cross-referencing each site's `id:` from A10. 5 entries carry `playwright: true` and a `baseline`
matching that site's actual `if:` shape; 9 entries carry `playwright: false`, no `baseline`.

**Verify:** `node scripts/check-sanitizer-secret-wiring.js` reports zero discovered-vs-manifest
differences. Delete one manifest entry as a one-off check — the guard must fail before restoring
it.

### A12 — Wire the static guard and canary into CI as first-class, blocking jobs

**Design component:** IV.4 ("workflow/static validation of changed `.yml` files"; "Playwright
sentinel tests (Track A)").
**Requirements:** IV.4; entry [29] finding 2; entry [31] finding 7.

`ci-verification.yml`'s existing job graph is strictly serial: `unit-tests` (no `needs`) →
`integration-tests` (`needs: unit-tests`) → `pact-consumer` (`needs: integration-tests`) →
`docker-build-verify` (`needs: pact-consumer`). Add two new jobs:

- **`static-guard`** (no `needs`): checkout, `actions/setup-node@v4` (`node-version: "22"`), then
  `cd scripts && npm ci --ignore-scripts && npm audit --omit=dev --audit-level=low && node
  check-sanitizer-secret-wiring.js`.
- **`sanitizer-canary`** (`needs: static-guard`): checkout, `actions/setup-node@v4`
  (`node-version: "22"`), then the full A13 canary sequence, including A5's
  `known-assets-manifest-tool.js --verify` step.

Change `unit-tests` to add `needs: static-guard`, threading the guard into the existing chain.

**Correctness proof for this wiring — narrowed to the durable local fixture.** A12's own claim is
only that the `needs:` graph is structurally wired to block, not a fresh CI-observed red run: the
actual proof that the guard *logic* correctly fails on a broken manifest is A9's own committed
negative fixtures (already exercised on every `node --test` run, local and in CI, per the
`static-guard` job above); A12 adds only a **static** review of the job YAML: confirm via
`grep -A1 "unit-tests:" ci-verification.yml` that `needs: static-guard` is present, and confirm the
full chain (`static-guard → unit-tests → integration-tests → pact-consumer → docker-build-verify`)
has no gap by reading the `needs:` key of each job in sequence.

**Verify:** the static YAML review above. On the PR itself (informational, not a new claim): confirm
`static-guard` appears as a job and runs before `unit-tests` in the Actions UI.

### A13 — Generated-Playwright canary: production-shaped roots, RUNNER_TEMP staging, separate dirty/clean proofs, manifest verification

**Design component:** "Regression fixtures... the generated-Playwright canary" (design.md lines
719-726) + "Independently authenticated, not self-certifying" (lines 555-570).
**Requirements:** A2.1, IV.4; entry [29] finding 1; entry [31] finding 4; entry [33] finding 5
(remaining parts, after the screenshot/uninspectable conflict itself was resolved by `design.md`
Revisions 11–16); entry [47] findings 2, 3, 4, 6.

**Part 1 (per the "Implementation order for A5/A13" note above)** — create the fixture project and
produce one real run:

Add `.github/actions/sanitize-playwright-artifacts/test/canary/`:
- `package.json` — **no hardcoded version pin (entry [47] finding 2).** A caret range
  (`^1.54.2`, Revision 4's mistake) or a hardcoded exact version duplicated in a second place can
  each silently drift from `frontend/package-lock.json`'s actual locked resolution — precisely the
  drift the manifest-authentication mechanism exists to catch. Instead:
  `sync-canary-playwright-version.js` (`--write` mode) reads `frontend/package-lock.json`'s resolved
  `@playwright/test` version and writes that **exact** string (no `^`/`~`) into the canary's own
  `package.json` dependency, so the canary's pin is always *derived* from the one source of truth,
  never independently maintained. Run `--write`, then `npm install` once locally to regenerate
  `package-lock.json`, and commit both `package.json` and the lockfile.
- `playwright.config.ts`: mirrors `frontend/playwright.config.ts`'s
  `trace: { mode: "retain-on-failure", screenshots: false }` and HTML reporter, with **no aggregate
  wrapper directory** — `outputDir: path.resolve(__dirname, "test-results")` and reporter
  `outputFolder: path.resolve(__dirname, "playwright-report")` are **direct siblings** under the
  canary's own root, matching real production's shape exactly.
- `fixtures/canary.spec.ts`: one intentionally-failing test reproducing A-IG.1's setup (a `file://`
  DOM page with an `input[type="password"]` filled, plus one `APIRequestContext.post()` call to a
  throwaway local `http` server started/stopped via `test.beforeAll`/`afterAll`, defined in
  `fixtures/local-server.ts`) — both the DOM fill and the POST body carry the sanitizer's own known
  non-secret fallback literal `"TestPassword123!"`. Ends with `expect(false).toBe(true)`.

`sync-canary-playwright-version.js`'s **`--check` mode** (run in CI, on every PR, after `npm ci`,
*before* Playwright ever runs) reads the canary's **actually-installed** `node_modules/@playwright/
test`, `playwright`, and `playwright-core` package versions and asserts all three exactly equal
`frontend/package-lock.json`'s current locked resolution, failing closed on any mismatch — the
executable pre-flight guarantee entry [47] finding 2 requires, independent of whether the committed
pin/lockfile happen to still be correct.

**Part 2 (once A3–A9's sanitizer and A5's populated, committed manifest both exist)** — the full
dirty/clean/manifest-verify sequence:

**Two separate sanitizer invocations, matching the two real production root shapes — never an
aggregate.** After the canary run produces `test-results/` and `playwright-report/` as siblings,
invoke `sanitize.js` **twice**: once with `SANITIZE_SOURCE_DIR=<canary>/playwright-report` (matching
the four real `playwright-report/`-uploading sites), once with `SANITIZE_SOURCE_DIR=<canary>/test-results`
(matching `ci-verification.yml`'s site). Each invocation's `SANITIZE_STAGING_DIR` is a **unique,
freshly created child of `$RUNNER_TEMP`** (`fs.mkdtempSync`), never a fixed, reusable path like
`/tmp/canary-staging`.

**"Source is dirty" and "staging is clean" are two separate proofs, not one diff inferring both:**
- **Dirty:** run 1 — `source-dir` = the raw, unsanitized `playwright-report/` (or `test-results/`),
  `staging-dir` = fresh temp child, `mode: fallback-only`. Expect: `sanitize_status == 0` (a clean
  sanitize run, Outcome A fired) **and** the resulting `staging-dir` differs from `source-dir` (the
  sentinel-bearing file was replaced) — this is the "source contained the sentinel and the real
  scanner found it" proof.
- **Clean:** run 2 — `source-dir` = run 1's **already-sanitized** `staging-dir`, a **second** fresh
  temp `staging-dir`, same `mode`. Expect: `sanitize_status == 0` **and** the resulting staging tree
  is **byte-identical** to run 2's source (nothing further found/withheld) — this is the "the
  sanitized output is actually clean, re-verified through the real pipeline a second time" proof,
  using the same production code path twice rather than inferring it from run 1's own diff.

Both runs, for both roots (`playwright-report/` and `test-results/`), so four sanitize invocations
total per canary execution.

**Count-aware assertions, not wildcard-sensitive single-file checks:** replace any
`test -f dir/*.zip`-style glob test with an explicit count check (e.g., `find playwright-report/data
-maxdepth 1 -name '*.zip' | wc -l`, asserted `-eq 1`) so an unexpected zero- or multiple-file result
is a loud failure, not silently ignored shell-glob behavior.

**Manifest verification, as part of this same canary run (A5's tool, entry [37] finding 1):** `node
../../known-assets-manifest-tool.js --verify playwright-report known-playwright-report-assets.json`
— must exit 0, proving the checked-in allowlist manifest is exactly (not approximately) what the
real, currently-locked `@playwright/test` version produces, and that `playwrightTestVersion` is
correctly bound to the lockfile.

**Every outcome above is an explicit, asserted expectation — never a silently swallowed exit code,
and every status capture survives GitHub Actions' default fail-fast `bash -e` (entry [47] finding
6):** the script below uses `if command; then status=0; else status=$?; fi` for every command whose
exit code is inspected on the next line — never a bare `command; status=$?`, which `-e` would abort
before reaching.

**`GITHUB_WORKSPACE`/`RUNNER_TEMP` explicitly established, never assumed (entry [47] finding 4):**
`sanitize.js`'s own path validation requires both to be set (`source-dir` must canonicalize inside
`$GITHUB_WORKSPACE`; `staging-dir` must be a fresh child of `$RUNNER_TEMP`). Real Actions runners set
both natively; a local run does not. The script below preserves either environment's native values
if already present, and **exports** explicit fallbacks otherwise — the exact bug entry [47] found
(`TEMP_ROOT=...` without `export` is invisible to a child `node` process; confirmed directly:
`node -e "console.log(process.env.TEMP_ROOT)"` printed `undefined` after an unexported assignment)
is fixed by exporting every environment variable a child process needs to read, and by using
`$RUNNER_TEMP` directly rather than introducing a second, easily-desynced `TEMP_ROOT` alias.

**Verify:**
```bash
cd .github/actions/sanitize-playwright-artifacts/test/canary

export GITHUB_WORKSPACE="${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"
created_runner_temp=0
if [ -z "${RUNNER_TEMP:-}" ]; then
  export RUNNER_TEMP="$(mktemp -d)"
  created_runner_temp=1
fi
cleanup() {
  if [ "$created_runner_temp" -eq 1 ]; then rm -rf "$RUNNER_TEMP"; fi
}
trap cleanup EXIT

rm -rf test-results playwright-report
if npm ci --ignore-scripts; then npm_status=0; else npm_status=$?; fi
[ "$npm_status" -eq 0 ] || { echo "FAIL: npm ci failed"; exit 1; }

if node sync-canary-playwright-version.js --check; then sync_status=0; else sync_status=$?; fi
[ "$sync_status" -eq 0 ] || { echo "FAIL: canary Playwright version diverges from frontend/package-lock.json"; exit 1; }

if npx playwright install --with-deps chromium; then install_status=0; else install_status=$?; fi
[ "$install_status" -eq 0 ] || { echo "FAIL: playwright install failed"; exit 1; }

if npx playwright test; then playwright_status=0; else playwright_status=$?; fi
if [ "$playwright_status" -eq 0 ]; then echo "FAIL: canary spec unexpectedly passed"; exit 1; fi
test -d test-results && test -d playwright-report \
  || { echo "FAIL: expected output directories missing"; exit 1; }
zip_count=$(find playwright-report/data -maxdepth 1 -name '*.zip' | wc -l)
[ "$zip_count" -eq 1 ] || { echo "FAIL: expected exactly 1 nested report zip, found $zip_count"; exit 1; }

for root in playwright-report test-results; do
  staging1=$(node -e "const fs=require('fs'),path=require('path');console.log(fs.mkdtempSync(path.join(process.env.RUNNER_TEMP,'canary-staging-')))")
  if SANITIZE_SOURCE_DIR="$(pwd)/$root" SANITIZE_STAGING_DIR="$staging1" SANITIZE_MODE=fallback-only \
      node ../../sanitize.js; then s1=0; else s1=$?; fi
  [ "$s1" -eq 0 ] || { echo "FAIL: dirty-proof sanitize aborted for $root"; exit 1; }
  diff -rq "$root" "$staging1" > /dev/null && { echo "FAIL: $root staging identical to source — sentinel not detected"; exit 1; }

  staging2=$(node -e "const fs=require('fs'),path=require('path');console.log(fs.mkdtempSync(path.join(process.env.RUNNER_TEMP,'canary-staging-')))")
  if SANITIZE_SOURCE_DIR="$staging1" SANITIZE_STAGING_DIR="$staging2" SANITIZE_MODE=fallback-only \
      node ../../sanitize.js; then s2=0; else s2=$?; fi
  [ "$s2" -eq 0 ] || { echo "FAIL: clean-proof re-sanitize aborted for $root"; exit 1; }
  diff -rq "$staging1" "$staging2" > /dev/null || { echo "FAIL: re-sanitized $root output not byte-identical — not actually clean"; exit 1; }
done

if node ../../known-assets-manifest-tool.js --verify playwright-report ../../known-playwright-report-assets.json; then
  manifest_status=0
else
  manifest_status=$?
fi
[ "$manifest_status" -eq 0 ] || { echo "FAIL: manifest does not match real producer output"; exit 1; }

echo "PASS: both roots proven dirty-then-clean; manifest verified"
```
Wired into CI as the `sanitizer-canary` job (A12) so this whole sequence re-runs on every PR.

### A14 — IV.1: actual `upload-artifact`/`download-artifact` pair, correctly ordered and asserted

**Requirements:** IV.1(a), IV.1(b), IV.1(c); entry [29] finding 3; entry [31] finding 5; entry [33]
finding 6; entry [47] finding 9.

**`workflow_dispatch` cannot be used here.** GitHub's own documentation states plainly: "To trigger
the `workflow_dispatch` event, your workflow must be in the default branch" — a workflow file that
exists only on the Track A feature branch cannot receive that event. `push`, by contrast, fires for
any workflow file present on the branch at the moment of the push, with no default-branch
requirement.

**Real implementation order (entry [33] finding 6 — Revision 3 presented A1–A13 as a sequence that
could not actually produce its own pre-A9(then) commit):** A13's canary (composite action, allowlist
mechanism, manifest) must exist first, since this task's pre-fix step needs a working, un-wired
canary to generate real content from. This task's **Step 1** is performed from a commit where A1–A9
and A13 exist but **A10 has not yet landed** (no real workflow wired to the composite action yet).
This task's **Step 2** is performed once A10 has landed. This task's **Step 3** (cleanup) happens
after Step 2, before A11's exhaustive manifest check is finalized (so the temporary workflow's own
upload-artifact step, if ever accidentally left in, would be caught by A11 as unclassified rather
than silently ignored).

**Step 1 — pre-fix leak (IV.1(a)).** Add one temporary workflow,
`.github/workflows/_scratch-ivcheck.yml`:
```yaml
on:
  push:
    branches: ["fix/playwright-artifact-sanitizer"]
jobs:
  produce:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: "22" }
      - id: canary
        working-directory: .github/actions/sanitize-playwright-artifacts/test/canary
        run: npm ci --ignore-scripts && npx playwright install --with-deps chromium
      - id: run-canary
        working-directory: .github/actions/sanitize-playwright-artifacts/test/canary
        run: npx playwright test
        continue-on-error: true   # the canary spec always fails intentionally — the JOB must
                                   # still succeed so the upload step below actually runs
      - name: Assert canary failed as expected
        run: |
          if [ "${{ steps.run-canary.outcome }}" != "failure" ]; then
            echo "::error::canary unexpectedly did not fail — pre-fix leak cannot be demonstrated"
            exit 1
          fi
      - name: Assert expected output shape before upload
        working-directory: .github/actions/sanitize-playwright-artifacts/test/canary
        run: |
          test -d playwright-report/trace || { echo "::error::missing trace/ output"; exit 1; }
          zip_count=$(find playwright-report/data -maxdepth 1 -name '*.zip' | wc -l)
          [ "$zip_count" -eq 1 ] || { echo "::error::expected 1 nested zip, found $zip_count"; exit 1; }
      - uses: actions/upload-artifact@v4
        with:
          name: ivcheck-prefix
          path: .github/actions/sanitize-playwright-artifacts/test/canary/playwright-report
  detect:
    needs: produce
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: "22" }
      - uses: actions/download-artifact@v4
        with: { name: ivcheck-prefix, path: downloaded }
      - working-directory: .github/actions/sanitize-playwright-artifacts
        run: npm ci --ignore-scripts
      - name: Non-mutating detection via a scratch staging dir (never the downloaded tree itself)
        working-directory: .github/actions/sanitize-playwright-artifacts
        run: |
          SANITIZE_SOURCE_DIR="${{ github.workspace }}/downloaded" \
          SANITIZE_STAGING_DIR="${{ runner.temp }}/ivcheck-staging" \
          SANITIZE_MODE=fallback-only node sanitize.js
          if diff -rq "${{ github.workspace }}/downloaded" "${{ runner.temp }}/ivcheck-staging" > /dev/null; then
            echo "::error::expected the pre-fix artifact to contain the sentinel — none detected"
            exit 1
          fi
          echo "pre-fix leak confirmed: sentinel detected and would have been withheld"
```
The downloaded tree itself is never mutated — `sanitize.js` only ever writes to its own
`staging-dir`, and `detect` diffs the downloaded original against that fresh output, so the
detection step is read-only with respect to the artifact under test, closing entry [33] finding 6's
"a mutating sanitizer/diff is not the requested detector" concern precisely: the *artifact* is never
mutated; only a scratch copy is produced and compared. Committing and pushing this file (an
operational, GitHub-visible action requiring the user's own explicit push at implementation time;
not performed now) triggers it automatically. Expected/recorded evidence: `detect`'s scan finds the
sentinel present in the raw, never-sanitized downloaded artifact — the leak reaches real,
downloadable artifact storage absent the fix.

**Step 2 — post-fix (IV.1(b)) and round trip (IV.1(c)), after A10 lands.** Update `produce`'s job to
route through the composite action (`mode: fallback-only`) *before* the upload step, exactly as the
5 real sites now do, uploading the sanitized `staging-dir` instead of the raw `playwright-report`.
Push again (same branch-scoped trigger). `detect` downloads and runs the identical non-mutating
scan. Expected/recorded evidence: **no difference** between the downloaded tree and a fresh
`sanitize.js` pass over it — nothing further to withhold, meaning the artifact GitHub actually
stored and served already contains no sentinel. This is IV.1(b) and IV.1(c) together.

**Step 3 — cleanup.** Delete `_scratch-ivcheck.yml` before the PR is opened for review, before A11's
final exhaustive manifest check is treated as passing. **The PR description records the durable
facts directly, as text — not only run URLs (entry [47] finding 9: GitHub retains both logs and
artifacts only for a bounded period, so a URL alone is not durable evidence once retention
expires).** For both the pre-fix and post-fix pushes, record in the PR description: the branch
commit SHA the push ran against; the workflow run ID and its Actions-tab URL (supporting evidence
while it remains live, not the sole record); the uploaded artifact's name and root shape
(`playwright-report`); the expected and observed canary-step outcome (`failure`); the pre-fix
detector result (sentinel `found`) and the post-fix detector result (`absent`/byte-identical); and
explicit confirmation that `_scratch-ivcheck.yml` was removed before the PR was opened. If the file
were accidentally left in at PR time, A11's manifest-diff check would itself catch its
`upload-artifact` step as unclassified.

This task requires pushing to a feature branch, which triggers real GitHub Actions runs; it is
documented precisely here so execution is unambiguous once the owner authorizes implementation. It
is not performed now, and drafting this instruction does not itself push or dispatch anything.

### A15 — Track A PR stop/go gate

**Requirements:** IV.3, IV.4, IV.5, bugfix.md Recorded Decisions; entry [29] finding 4; entry [31]
finding 7; entry [33] finding 1 (verification-convention forms, applied here); entry [49] finding 5;
entry [51] finding 3; entry [53] finding 2.

- `git diff --stat main...HEAD` matches exactly this track's file scope (no Track B file touched,
  and `_scratch-ivcheck.yml` from A14 is absent).
- `git diff --check` is clean; neither `.env.secrets` nor `CHECKPOINT.md` nor the two pre-existing
  junk files (`=2.8`, `=25,`) appear in the diff.
- **No unexpected untracked generated residue (entry [49] finding 5 — a tracked-diff check alone
  cannot see this; the check itself corrected per entry [51] finding 3 and, further, per entry [53]
  finding 2 — Revision 7's `|| true` was attached to the *entire* `git ls-files | grep` pipeline, so
  it silently absorbed a failure in `git ls-files` itself, not just `grep`'s harmless zero-match
  exit; a controlled upstream failure (Codex's reproduction: exit 42) was certified as "no unexpected
  residue," reproduced directly here too with an invalid `git ls-files` flag):**
  ```bash
  if raw_untracked=$(git ls-files --others --exclude-standard); then
    ls_status=0
  else
    ls_status=$?
  fi
  if [ "$ls_status" -ne 0 ]; then
    echo "FAIL: git ls-files --others --exclude-standard failed (exit $ls_status)"
    exit 1
  fi
  untracked_unexpected=$(printf '%s\n' "$raw_untracked" | awk '!/^(=2\.8|=25,)$/')
  if [ -n "$untracked_unexpected" ]; then
    echo "FAIL: unexpected untracked residue:"
    echo "$untracked_unexpected"
    exit 1
  fi
  echo "PASS: no unexpected untracked residue"
  ```
  `git ls-files --others --exclude-standard` lists untracked files only, honoring `.gitignore` —
  replacing `git status --porcelain`, which also reports staged/modified/deleted *tracked* changes
  the `git diff --stat`/`git diff --check` bullets above already cover, and whose column-2 parsing
  via `awk '{print $2}'` doesn't handle renames or quoted filenames. **The enumeration step and the
  filter step are now separate, each checked on its own terms:** `git ls-files` is captured via the
  `if command; then status=0; else status=$?; fi` idiom already established in the Verification
  convention, and its exit status is asserted non-zero-is-failure *before* any filtering happens —
  no blanket `|| true` can swallow an upstream tool error. The junk-file filter uses `awk`, not
  `grep -v` — `awk` exits 0 whether it selects zero, one, or many lines (it only fails on an actual
  script/runtime error), so no error-suppression wrapper is needed on the filter step at all; the
  *emptiness* of its output is what's asserted, not its exit code. Live-tested against this repo in
  all three states Codex's review named: the current non-clean state (correctly fails, lists the
  three untracked SDD files, exit 1); a simulated clean state via a `:(exclude)` pathspec (correctly
  passes, exit 0, no output); and a simulated upstream enumeration failure via an invalid `git
  ls-files` flag (correctly fails with the enumeration-failure message, exit 1 — this is exactly the
  case the old `|| true` silently passed). Only the two pre-existing junk files (`=2.8`, `=25,`) are
  excepted — **no SDD-directory exception**: by the time this Track A branch exists, the Prerequisite
  section above has already committed `.kiro/specs/.../` to `main`, so it is tracked and cannot
  appear here; exempting it was stale and would have masked genuine untracked residue reappearing
  inside that path.
- Every fixture from A1–A13 passes in one final combined run (Node convention across both
  `.github/actions/sanitize-playwright-artifacts/` and `scripts/`, plus the A13 canary sequence and
  A2's Vitest suite) — every one of these test invocations, including A6's resource test, exits 0
  overall.
- **IV.3, applied to this track too (unconditional on both tracks):** run the Gradle convention
  (bash/`./gradlew` form, matching CI) for `:api-gateway:integrationTest --no-daemon` (full,
  unfiltered suite) — must report a non-zero total test count and zero failures/errors. This track
  touches no `api-gateway` source; this is a pure regression guard.
- **IV.4's container-build-verification signal:** confirm `ci-verification.yml`'s existing
  `docker-build-verify` job (runs `docker compose build`, triggers on every PR via the unscoped
  `pull_request` trigger) is green on this PR.
- The whole `ci-verification.yml` pipeline (`static-guard` → `unit-tests` → `integration-tests` →
  `pact-consumer` → `docker-build-verify`, plus `sanitizer-canary`) is green.
- **Branch protection:** confirm all six jobs above are configured as required status checks (an
  owner-side setting; see "PR structure" above) before merge is permitted.

Codex review requested before merge.

---

## Track B — Rate-limit integration-test determinism (PR 2, branches from post-Track-A `main`)

**Branch:** `fix/rate-limit-proven-window` from `main`, taken *after* Track A merges — never from
Track A's own branch.
**File scope for this track, exhaustively:**
- New: `api-gateway/src/test/java/com/wealth/gateway/ratelimit/{SecondProvider,KeyProvider,
  BurstRunner,RawAttempt,ProvenWindowRunner,RedisTimeParser}.java`.
- New: `api-gateway/src/test/java/com/wealth/gateway/ratelimit/{ProvenWindowRunnerTest,
  RedisTimeParserTest}.java`.
- Modified: `api-gateway/src/test/java/com/wealth/gateway/ProductionRateLimitingIntegrationTest.java`
  only (the `setUp()` builder, the downstream stub fields/lifecycle, and
  `burstAllowedThenThrottledWithDecrement` itself).
- No other file is touched by this track. **(Corrected, entry [49] finding 5: this file scope is
  unchanged from Revision 3, but the tasks themselves are not — B1/B2/B5/B8/B9 were materially
  rewritten in Revisions 5 and 6 to actually close entry [33] finding 7 and entry [47]/[49]'s
  Track B findings, which Revision 4 had incorrectly claimed were already fixed.)**

### B1 — Extract the injectable seams

**Design component:** "Data shapes and orchestration" (design.md lines 1290-1345).
**Requirements:** B2.1, B2.2, entry [13] finding 6, entry [13] finding 8; entry [47] finding 8.

Create the five framework-free classes exactly per design.md's pseudocode:
`SecondProvider`/`KeyProvider`/`BurstRunner` functional interfaces (`SecondProvider.currentSecond()
throws Exception` returns `String`); `RawAttempt` record (`burstResponses`, `firstExcessResponse`,
`downstreamDelta`); `ProvenWindowRunner` with the dual attempt/elapsed-bound loop.

**Cross-package visibility, made explicit (entry [47] finding 8).** Design.md's pseudocode shows
these types without an explicit access modifier, which is a conceptual sketch, not a literal
visibility spec. `ProductionRateLimitingIntegrationTest` lives in `com.wealth.gateway` (confirmed:
`package com.wealth.gateway;` at the top of the current file); every type introduced here lives in
the distinct `com.wealth.gateway.ratelimit` subpackage (by design, so B6's unit test stays free of
the integration test's heavier dependencies). A package-private declaration would make these types
literally inaccessible from the parent-package integration test — a compile error, not merely a
style choice. **Declare exactly:** `SecondProvider`, `KeyProvider`, `BurstRunner` as `public`
functional interfaces; `RawAttempt` as a `public record`; `ProvenWindowRunner` as a `public final
class` with a `public` constructor and a `public RawAttempt run(int, Duration)` method (the fields
`secondProvider`/`keyProvider`/`burstRunner` stay `private`, as design.md already shows — only the
cross-package-consumed surface is widened, nothing internal).

**Verify:** `./gradlew.bat :api-gateway:compileTestJava` — compiles (B6 exercises it; B2/B5's
compile checks below exercise the actual cross-package construction/invocation from
`ProductionRateLimitingIntegrationTest`).

### B2 — `RedisTimeParser`: exit-code/format validation, type-compatible with the frozen interface

**Requirements:** entry [15] finding 5b; entry [29] finding 7; entry [31] finding 6; entry [47]
finding 8; entry [49] finding 2.

**The class itself must be `public`, not only the method (entry [49] finding 2 — a real gap in
Revision 5: `public static` on a method inside an otherwise package-private class is still
inaccessible cross-package, since the class name itself can't be referenced from outside its
package).** Declare **`public final class RedisTimeParser`** with a narrow `private
RedisTimeParser() {}` no-op constructor (the conventional idiom for a class holding only a static
method, preventing instantiation without needing any visibility wider than that) and extract the
exit-code/format-validation logic into a pure, **`public static`** method — **`public static String
RedisTimeParser.parse(int exitCode, String stdout)`** (returning the normalized first line as a
`String`, never a `long` — the frozen `SecondProvider.currentSecond(): String` and `private String
redisTimeSeconds()` (design.md lines 1293, 1394) are not reopened by this extraction) — in a new
`api-gateway/src/test/java/com/wealth/gateway/ratelimit/RedisTimeParser.java`. `parse` throws
`IllegalStateException("redis-cli TIME exited " + exitCode)` when `exitCode != 0`; throws
`IllegalStateException("malformed redis-cli TIME output: " + stdout)` when the first line does not
match `^\d+$` after trimming; otherwise returns that trimmed first line unchanged. Test-first:
`RedisTimeParserTest.java` (plain JUnit, no Spring/Testcontainers) with canned inputs covering
exit-code-1, exit-code-0-with-empty-stdout, exit-code-0-with-non-numeric-stdout,
exit-code-0-with-trailing-whitespace-numeric-stdout (must still parse), and the happy path.
`redisTimeSeconds()` in `ProductionRateLimitingIntegrationTest.java` becomes: `Container.ExecResult
result = redis.execInContainer("redis-cli", "TIME"); return RedisTimeParser.parse
(result.getExitCode(), result.getStdout());` — matching the exact `ExecResult.getExitCode()`/
`getStdout()` API already used at `SlimImageHealthIT.java:76-77`.

**Verify:** Gradle convention for `RedisTimeParserTest`. Additionally, after B1/B5 wire
`this::redisTimeSeconds` into `ProvenWindowRunner`'s constructor, run
`./gradlew.bat :api-gateway:compileTestJava` and confirm it compiles — a direct, executable check
that the method reference actually satisfies `SecondProvider`'s `String`-returning contract.

### B3 — Downstream request-counting stub

**Design component:** "Downstream oracle" (design.md lines 1428-1462).
**Requirements:** entry [13] finding 4, entry [13] finding 6.

Add `portfolioStub`/`portfolioStubPort`/`downstreamRequestCount` fields and
`startPortfolioStub()`/`stopPortfolioStub()` to `ProductionRateLimitingIntegrationTest.java`,
mirroring `HttpTraceContextPropagationIT.java:62-94` exactly. Override `app.routes.portfolio-url`
to the stub's port.

**Verify:** Gradle convention for
`ProductionRateLimitingIntegrationTest.contextLoadsWithRedisUnderProdAzureProfile`.

### B4 — Fix `responseTimeout` placement

**Requirements:** entry [15] finding 5a.

Move the 5-second response timeout onto the `WebTestClient.Builder` in `setUp()`
(`.responseTimeout(Duration.ofSeconds(5))`), exactly as `HttpTraceContextPropagationIT.java:105-108`
already does it.

**Verify:** `./gradlew.bat :api-gateway:compileTestJava` compiles; the existing `failOpenWhenRedisDown`
test still passes unmodified.

### B5 — Rewrite `burstAllowedThenThrottledWithDecrement`, pinning the already-captured counterexample

**Design component:** "The test method asserts only on the single returned, proven `RawAttempt`"
(design.md lines 1347-1368).
**Requirements:** B2.1, B2.3, entry [15] finding 5c, entry [17] finding 4/finding 6; entry [29]
finding 5; entry [47] finding 7; entry [49] finding 3; entry [51] finding 2; entry [53] finding 3.

Replace the method body with `ProvenWindowRunner` construction (`this::redisTimeSeconds`, a fresh
`tokenFor(...)` per attempt, the production `BurstRunner` lambda), `MAX_WINDOW_PROOF_ATTEMPTS = 30`,
`MAX_WINDOW_PROOF_SOFT_ELAPSED = Duration.ofSeconds(10)`, then the exact post-return assertions:
`isEqualTo(200)` per burst response, `containsExactly("2","1","0")` on remaining, `isEqualTo(429)`
on the first-excess status, exact `Retry-After`/content-type/body assertions,
`isEqualTo(STANDARD_BURST)` on `downstreamDelta`. No `Thread.sleep`, no `@RepeatedTest`, no
assertion weakening.

**Retained base evidence.** `bugfix.md`'s already-captured B1.2 counterexample — command
`.\gradlew.bat :api-gateway:integrationTest --tests
"com.wealth.gateway.ProductionRateLimitingIntegrationTest.burstAllowedThenThrottledWithDecrement"
--rerun-tasks --no-daemon"`, run against `main` at `d7b5b8d` on 2026-08-16T16:55, failing at line
193 with `["2","2","1"]` vs. expected `["2","1","0"]` (XML at
`api-gateway/build/test-results/integrationTest/TEST-com.wealth.gateway.
ProductionRateLimitingIntegrationTest.xml`) — **is** the retained unmodified-base counterexample
required by IV.2; cite it directly rather than reproducing it. An optional additional
characterization run, if performed, is bounded to a fixed small attempt count and its outcome
recorded honestly either way; implementation progress is never contingent on naturally reproducing
another failure.

**Verify — an exact, bounded stress policy, not an open-ended "run repeatedly" (entry [47] finding
7).** Run the Gradle convention for `burstAllowedThenThrottledWithDecrement` **exactly 5 times**, one
invocation each, no retry-until-green: any of the 5 must independently pass (the convention's own
`count > 0 && failures == 0` gate) or the task is not done — this is not a "keep trying until it
works" loop, it is 5 independent confidence-building executions of an already-implemented, already-
correct method. **Do not claim the per-run internal attempt count** — design.md's frozen
`ProvenWindowRunner.run()` never exposes the winning attempt number on its success path (`attempt`
is a loop-local variable that only reaches an observer via the *exhaustion* message, never via
`RawAttempt`'s frozen fields); asking to "document the observed attempt count" for a successful run
was asking for something the accepted data shape does not provide, and Revision 4 restated but never
actually removed that claim. **In its place**, a non-invasive external proxy that requires no change
to the frozen `RawAttempt`/`ProvenWindowRunner` shape: record each of the 5 runs' wall-clock
duration, as reported by Gradle's own build output (`BUILD SUCCESSFUL in Xs`) — a rough, external
indicator of how quickly a valid window was found, never a claim about the internal loop.

**An explicit aggregate deadline for the whole 5-run loop (entry [49] finding 3 — "exactly 5" bounds
the count but Revision 5 never bounded the total wall time the 5 runs together could take), now a
complete, paste-runnable CI-bash command rather than a placeholder (entry [51] finding 2 — the prior
`<the Gradle convention block for ...>` line could not be executed as written).** Define the loop as
a shell function and export it, so `timeout` can wrap it without nesting the Verification
convention's own single-quoted `awk` programs inside another layer of single quotes (which would
otherwise terminate the outer `bash -c '...'` string early):
```bash
run_five_times() {
  set -uo pipefail
  for i in 1 2 3 4 5; do
    echo "=== burstAllowedThenThrottledWithDecrement run $i/5 ==="
    rm -rf api-gateway/build/test-results/integrationTest
    if ./gradlew :api-gateway:integrationTest \
      --tests "com.wealth.gateway.ProductionRateLimitingIntegrationTest.burstAllowedThenThrottledWithDecrement" \
      --rerun-tasks --no-daemon | tee "/tmp/burst-run-$i.log"; then
      gradle_status=0
    else
      gradle_status=$?
    fi
    count=$(grep -ho 'tests="[0-9]*"' api-gateway/build/test-results/integrationTest/TEST-*.xml 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END{print s+0}')
    fails=$(grep -ho 'failures="[0-9]*"' api-gateway/build/test-results/integrationTest/TEST-*.xml 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END{print s+0}')
    errs=$(grep -ho 'errors="[0-9]*"' api-gateway/build/test-results/integrationTest/TEST-*.xml 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END{print s+0}')
    if [ "$gradle_status" -ne 0 ] || [ "$count" -eq 0 ] || [ "$fails" -ne 0 ] || [ "$errs" -ne 0 ]; then
      echo "FAIL run $i: gradle=$gradle_status count=$count fails=$fails errs=$errs"
      return 1
    fi
    if ! duration=$(grep -o 'BUILD SUCCESSFUL in [0-9a-z ]*' "/tmp/burst-run-$i.log" | tail -1) || [ -z "$duration" ]; then
      echo "FAIL run $i: missing BUILD SUCCESSFUL duration evidence in /tmp/burst-run-$i.log"
      return 1
    fi
    echo "PASS run $i: $count tests, 0 failures/errors, $duration"
  done
  echo "All 5 runs passed"
}
export -f run_five_times
timeout 900 bash -c 'run_five_times'
```
Five independently checked invocations, each asserting the exact numeric XML gate (`count > 0 &&
fails == 0 && errs == 0`) the Verification convention defines, each with the exact FQCN/method under
test; the first run to fail its own gate `return`s 1 immediately (fail-fast — never continues toward
a 6th run or a retry); `tee` captures each run's own `BUILD SUCCESSFUL in Xs` line as duration
evidence without altering `./gradlew`'s exit code (`pipefail` makes the `if` see Gradle's own status,
not `tee`'s). **The duration-evidence extraction is itself now a gate, not a diagnostic aside (entry
[53] finding 3 — `set -uo pipefail` alone did not stop a run from passing with missing duration
evidence: `grep`'s exit 1 on no match propagates through the pipe under `pipefail`, but nothing
previously checked that status, so the run went on to print `PASS` with an empty `$duration` and the
function never returned non-zero for it, reproduced directly). Both the extraction's own exit status
and the non-emptiness of `$duration` are checked explicitly; either failing prints a run-specific
`FAIL` and `return`s 1** — the same fail-fast path used by the numeric XML gate above, never a silent
`PASS` with absent evidence. **This exact 5-run/`timeout` form is CI-bash-only** (`export -f` and
`timeout` are not native PowerShell); local Windows verification of the same test uses a single
invocation of the plain PowerShell Gradle-convention form from the Verification convention section
above, not this
wrapper.

**900 s is a deliberately chosen, justified bound, not an arbitrary one:** each individual run's own
internal proof typically completes in well under a minute (`MAX_WINDOW_PROOF_SOFT_ELAPSED = 10 s` is
itself only a soft per-attempt admission policy, and the dominant per-invocation cost is JVM/
Testcontainers startup, not the window-proof loop itself), so 5 successful runs are expected to
finish comfortably inside 15 minutes; the bound is also deliberately far below `5 ×` the single
task's own 20-minute Gradle deadline (100 minutes), which would be so loose it could never actually
fire as a backstop. **`timeout`'s exit code 124 (or any non-zero exit from the wrapped loop) is a
verification FAILURE, exactly like any other Gradle-convention failure — never a signal to retry,
extend the bound, or run a 6th time.** The per-run `BUILD SUCCESSFUL in Xs` durations remain
diagnostic only; the `timeout` wrapper is the actual enforced bound.

### B6 — Unit test for the full discard/fresh-key/retry control flow (IV.2(a))

**Requirements:** B2.2, entry [15] finding "IV.2(a) tests only a predicate," entry [13] finding 8.

Create `ProvenWindowRunnerTest.java` (plain JUnit, no `@Tag("integration")`, no Spring, no
Testcontainers). Fake `SecondProvider` returning a scripted `["100","101","100","100"]` sequence
across two attempts; fake `KeyProvider` recording every issued key; fake `BurstRunner` recording
which key it was invoked with and returning a distinguishable dummy `RawAttempt` per call. Assert:
exactly two fresh keys issued; the two invocations used two different keys; `run()`'s return value
equals attempt 2's dummy result.

**Verify:** Gradle convention for `ProvenWindowRunnerTest` — exactly 1 test method ran.

### B7 — IV.2(b): a real behavioral perturbation through a test seam

**Requirements:** IV.2(b); entry [29] finding 6.

Introduce a package-private test-only seam that can mutate the already-proven `RawAttempt` *after*
`ProvenWindowRunner.run()` returns but *before* the assertions execute, leaving
`MAX_WINDOW_PROOF_ATTEMPTS`, `MAX_WINDOW_PROOF_SOFT_ELAPSED`, and every assertion literal untouched.
Run one perturbation at a time (wrong decrement value, wrong first-excess status, wrong
`downstreamDelta`) and confirm each fails with the specific assertion mismatch (never a
`ProvenWindowRunner` exhaustion failure). Revert the seam and every perturbed run immediately after
capturing this evidence — never left enabled in the committed test.

**Verify:** each of the three perturbed runs fails with its specific `AssertionError` message —
never a "no proven window found" message.

### B8 — Confirm the untouched surface stays untouched

**Requirements:** B3.2, B3.3, B2.5; entry [47] finding 7; entry [49] finding 4; entry [51]
finding 4.

**Baseline counts are derived from git history, immutably, before any B1–B7 edit exists — never
"whatever the current file says" (entry [49] finding 4: Revision 5's "treat the newly-observed count
as authoritative" escape would let an implementation that accidentally deletes an `@Test` method
silently rebaseline itself, defeating the whole point of a regression oracle).**

**One reusable function, invoked identically by both B8 (now) and B9 (later, in a separate shell
session) — not two independently-maintained re-derivations, and not a reliance on shell variables
surviving across sessions (entry [51] finding 4: Revision 6's `prod_baseline`/`sibling_baseline`
were set once in B8's shell and never recreated for B9, so B9 as written could not actually be run).
The only external input either invocation needs is `BASE_SHA` — a plain recorded fact, not a live
variable:**
```bash
verify_track_b_baseline() {
  local fqcn="$1" path="$2"
  if [ -z "${BASE_SHA:-}" ]; then
    echo "FAIL: BASE_SHA is unset — set it to the recorded branch-point SHA before running"
    return 1
  fi
  local expected
  if ! expected=$(git show "${BASE_SHA}:${path}" | grep -cE '^\s*@Test\s*$'); then
    echo "FAIL: could not read $path at $BASE_SHA"
    return 1
  fi
  if [ "$expected" -eq 0 ]; then
    echo "FAIL: baseline for $path at $BASE_SHA is 0 — investigate before proceeding, never accept"
    return 1
  fi
  rm -rf api-gateway/build/test-results/integrationTest
  if ./gradlew :api-gateway:integrationTest --tests "$fqcn" --rerun-tasks --no-daemon; then
    gradle_status=0
  else
    gradle_status=$?
  fi
  count=$(grep -ho 'tests="[0-9]*"' api-gateway/build/test-results/integrationTest/TEST-*.xml 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END{print s+0}')
  fails=$(grep -ho 'failures="[0-9]*"' api-gateway/build/test-results/integrationTest/TEST-*.xml 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END{print s+0}')
  errs=$(grep -ho 'errors="[0-9]*"' api-gateway/build/test-results/integrationTest/TEST-*.xml 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END{print s+0}')
  if [ "$gradle_status" -ne 0 ] || [ "$count" -ne "$expected" ] || [ "$fails" -ne 0 ] || [ "$errs" -ne 0 ]; then
    echo "FAIL: $fqcn — gradle=$gradle_status expected=$expected actual_count=$count fails=$fails errs=$errs"
    return 1
  fi
  echo "PASS: $fqcn — $count tests match recorded baseline $expected, 0 failures/errors"
}
```
`expected` always comes from `git show "$BASE_SHA:$path"` — the historical file at the recorded
branch point — **never** from the live, possibly-edited file; the regex matches a line that, after
trimming whitespace, is *exactly* `@Test` (`@Testcontainers` does not match, closing the same
false-positive `grep -c "@Test"` risk found while re-verifying Revision 5's own claimed counts). The
function fails on an unset/malformed `BASE_SHA`, a missing file at that SHA, a zero baseline, a
count mismatch in either direction, or any Gradle-reported failure/error — there is no path back to
"accept the newly observed count."

**B8 itself, run once at the start of Track B implementation:**
```bash
BASE_SHA=$(git merge-base main HEAD)
echo "BASE_SHA=$BASE_SHA"
verify_track_b_baseline "com.wealth.gateway.ProductionRateLimitingIntegrationTest" \
  "api-gateway/src/test/java/com/wealth/gateway/ProductionRateLimitingIntegrationTest.java"
verify_track_b_baseline "com.wealth.gateway.RateLimitingIntegrationTest" \
  "api-gateway/src/test/java/com/wealth/gateway/RateLimitingIntegrationTest.java"
```
**Record the literal `BASE_SHA` value this prints — not just the two resulting counts — in the PR
description once, at branch-creation time; treat all three as fixed for the rest of the PR's
lifetime.** At the commit this SDD was written against, the two baselines evaluate to `8` and `5`,
matching the values already independently confirmed. A mismatch in either direction is always a
failure requiring investigation, never silently accepted as a new baseline. If `main` genuinely
changed these classes before this branch was cut (a legitimate, if unlikely, scenario), that is a
deliberate, documented plan/PR update made by a human reviewing the diff — never an automatic
consequence of the implementation's own edited state matching a lower count.

### B9 — Track B PR stop/go gate

**Requirements:** IV.3, IV.4, IV.5, bugfix.md Recorded Decisions; entry [29] finding 4; entry [31]
finding 7; entry [47] finding 7; entry [49] finding 4; entry [51] finding 4; entry [53] finding 1.

- **Reuse B8's `verify_track_b_baseline` function verbatim (same definition, not re-derived) —
  reproduced in full below so this gate is paste-runnable on its own, in its own shell session,
  using only the `BASE_SHA` recorded in the PR description at B8 time (entry [51] finding 4: B9 does
  not inherit any shell variable from B8's session, which no longer exists by the time this gate
  runs — B8's session-scoped `BASE_SHA=$(git merge-base main HEAD)` is not repeated here on
  purpose, since B9 must bind to the *recorded* branch point, not whatever `git merge-base` would
  compute if re-run today). The recorded-SHA input is now an executable fail-closed prerequisite,
  not an angle-bracket stand-in (entry [53] finding 1 — `BASE_SHA=<the literal value ...>` is not an
  assignment at all: bash parses a bare `<` here as input redirection, so the line `bash -n`-fails
  with a syntax error, reproduced directly: exit 2, "syntax error near unexpected token 'newline'").
  `: "${BASE_SHA:?...}"` is the standard bash idiom for this — a no-op that aborts with the given
  message whenever `BASE_SHA` is unset or empty, and is itself ordinary, valid shell syntax:**
  ```bash
  verify_track_b_baseline() {
    local fqcn="$1" path="$2"
    if [ -z "${BASE_SHA:-}" ]; then
      echo "FAIL: BASE_SHA is unset — set it to the recorded branch-point SHA before running"
      return 1
    fi
    local expected
    if ! expected=$(git show "${BASE_SHA}:${path}" | grep -cE '^\s*@Test\s*$'); then
      echo "FAIL: could not read $path at $BASE_SHA"
      return 1
    fi
    if [ "$expected" -eq 0 ]; then
      echo "FAIL: baseline for $path at $BASE_SHA is 0 — investigate before proceeding, never accept"
      return 1
    fi
    rm -rf api-gateway/build/test-results/integrationTest
    if ./gradlew :api-gateway:integrationTest --tests "$fqcn" --rerun-tasks --no-daemon; then
      gradle_status=0
    else
      gradle_status=$?
    fi
    count=$(grep -ho 'tests="[0-9]*"' api-gateway/build/test-results/integrationTest/TEST-*.xml 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END{print s+0}')
    fails=$(grep -ho 'failures="[0-9]*"' api-gateway/build/test-results/integrationTest/TEST-*.xml 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END{print s+0}')
    errs=$(grep -ho 'errors="[0-9]*"' api-gateway/build/test-results/integrationTest/TEST-*.xml 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END{print s+0}')
    if [ "$gradle_status" -ne 0 ] || [ "$count" -ne "$expected" ] || [ "$fails" -ne 0 ] || [ "$errs" -ne 0 ]; then
      echo "FAIL: $fqcn — gradle=$gradle_status expected=$expected actual_count=$count fails=$fails errs=$errs"
      return 1
    fi
    echo "PASS: $fqcn — $count tests match recorded baseline $expected, 0 failures/errors"
  }
  : "${BASE_SHA:?export BASE_SHA to the exact value recorded by B8 before running B9}"
  verify_track_b_baseline "com.wealth.gateway.ProductionRateLimitingIntegrationTest" \
    "api-gateway/src/test/java/com/wealth/gateway/ProductionRateLimitingIntegrationTest.java"
  verify_track_b_baseline "com.wealth.gateway.RateLimitingIntegrationTest" \
    "api-gateway/src/test/java/com/wealth/gateway/RateLimitingIntegrationTest.java"
  ```
  Both invocations must `PASS` (count exactly matches the recorded baseline, zero failures/errors) —
  **B8's sibling run, carried into this gate explicitly, not merely a diff-only exclusion.**
  Separately, run the Gradle convention for `com.wealth.gateway.ratelimit.*` (B2/B6's tests, which
  have no pre-existing baseline to match — this is a plain non-zero-count, zero-failure check, not a
  baseline comparison). A count mismatch against either recorded baseline is a stop/go failure, not
  a value to reconcile by accepting the lower number.
- `git diff --stat main...HEAD` matches exactly this track's file scope (confirming this branch was
  cut from the already-merged post-Track-A `main`). `git diff --check` clean. No production source
  file (`GatewayRateLimitConfig.java`, any `application-*.yml`) appears in the diff.
- **`ci-verification.yml` has no path filter on any job.** `static-guard` and `sanitizer-canary`
  (added by Track A, already on `main` by the time this branch is cut) **will rerun on this PR too**
  and must be green — they are not exempted by this track touching only `api-gateway` test sources.
  Confirm the whole six-job pipeline is green, not only the four-job Track-B-relevant subset.
- **IV.4's container-build-verification signal:** confirm `docker-build-verify` is green — this
  track touches no Dockerfile, so this is a regression guard proving the test-source-only change
  didn't affect the built images.
- **Branch protection:** confirm the same six required status checks named in A15 are configured
  (this is one shared owner-side setting covering both PRs, not a per-PR action, but is verified
  again here since it is a hard pre-merge condition for this PR too).

Codex review requested before merge.

---

## Final traceability matrix

Every `bugfix.md`/`design.md` obligation, mapped to the task(s) that implement and verify it.

### Track A

| Requirement | Task(s) | Verification |
|---|---|---|
| A2.1 | A3, A4, A5, A6, A7, A13 | Fixture suites in each task; canary end-to-end run |
| A2.2 | A7 | Path-validation + missing-source fixtures |
| A2.3 | A3, A4, A6, A7 | Uninspectable/hostile/fail-closed fixtures |
| A2.4 | A3–A7 (regression list) | One fixture per named shape |
| A2.5 | A7 | Fail-closed default fixture |
| A2.6 | A1, A9, A10, A11, A12 | Composite action review; static-guard CI job |
| A2.7 | Not a task — organizational | N/A |
| A-IG.1–A-IG.4 | Already completed during design; A3/A4 fixtures re-derive as executable tests; A13 re-runs the actual A-IG.1 fixture shape as a standing regression | `node --test` |
| A-IG.5 | A11 (manifest derivation) | `rg` re-run; guard's exhaustive diff |
| A-IG.6 | A3–A11 | This document's ordering |
| A-IG.7 (entries [33]/[35]/[37]/[39]/[41]/[43]) | Authenticated allowlist, canonical path validation, `screenshots: false`, single-pass merge, exhaustive manifest equality — all implemented in A2/A5/A13 | A5's fixture suite; A13's canary; manifest-tool `--verify` |
| Layer 1 capture suppression (design.md lines 236-266) | A2 | Trace-content-absence check; per-helper fixtures; no-screenshot-entry check |
| Generated-Playwright canary (design.md lines 719-726) | A13 | End-to-end real-Playwright-output scan via `sanitize.js` itself, both production root shapes |
| IV.1 | A14 | Actual `upload-artifact`/`download-artifact` pair via branch-scoped `push`, non-mutating detection, correct implementation order |
| entry [13]–[25] static-guard findings | A8, A9 | A9's fixture list (exhaustive per-finding) |
| entry [29] finding 2 (valid GH Actions step placement) | A1, A12 | Composite-action grep check; CI job-graph review |
| entry [29] finding 7 / entry [31] finding 3 / entry [33] finding 3 (resource oracle) | A6 | `/usr/bin/time -v` true peak-RSS proof |
| entry [31] finding 1 / entry [33] finding 1 (durable, correct-shell, non-fail-open count checks) | A3–A13 (all Node-convention verifies); Verification convention itself | Corrected bash/PowerShell/Node forms |
| entry [31] finding 2 (per-helper error contract) | A2 | Per-helper fixtures |
| entry [33] finding 2 (durable A2 test file) | A2 | `capture-suppression.test.ts`, named and committed |
| entry [31] finding 4 / entry [33] finding 5 (canary lockfile/isolation/path-contract/dirty-clean separation) | A13 | Lockfile commit; sibling-root isolation; `RUNNER_TEMP` staging; separate dirty/clean sanitize runs |
| entry [31] finding 5 (`push`, not `workflow_dispatch`) | A14 | GitHub docs citation; branch-scoped trigger |
| entry [33] finding 4 (sanitizer's own predecessor condition) | A10 | Structural `if:` assertion on each sanitize step |
| entry [33] finding 6 (IV.1 assertions, ordering, non-mutating detector, logs-retention correction) | A14 | Explicit `id`/outcome assertion; stated real order; scratch-copy diff; corrected evidence claim |
| entry [47] finding 1 (file-scope omission) | A2 (scope list) | Exhaustive file-scope review |
| entry [47] finding 2 (exact, self-verifying Playwright pin) | A13 | `sync-canary-playwright-version.js --write`/`--check` |
| entry [47] finding 3 (A5/A13 acyclic build order) | A5, A13 | "Implementation order for A5/A13" section |
| entry [47] finding 4 (`RUNNER_TEMP`/`GITHUB_WORKSPACE` exported, not assumed) | A13 | Exported env vars; reproduced unexported-variable failure before fixing |
| entry [47] finding 5 (resource oracle discriminates streaming) | A6 | Top-level-file fixture (no metadata shortcut) |
| entry [47] finding 6 (fail-fast-shell-safe status capture) | Verification convention; A13 | `if`/`else` status capture; `EXIT` trap cleanup |
| entry [47] finding 9 (durable IV.1 PR evidence) | A14 | PR-description fact checklist |
| entry [49] finding 1 (RSS oracle discriminates streaming from `fs.stat` shortcut) | A6 | `test/instrument-fs.js` white-box stream instrumentation; `createReadStreamCalled`/`wholeFileApiCalled`/`totalStreamedBytes` assertions |
| entry [49] finding 5 (generated artifacts actually gitignored; no unexpected untracked residue) | A15 (also file scope) | Two new scoped `.gitignore` files; `git ls-files --others --exclude-standard`-based check (entry [51]/[53]) |
| entry [51] finding 1 (instrumentation scoped to the one staged file, not aggregate process I/O) | A6 | `INSTRUMENT_FS_TARGET_PATH`-scoped `createReadStream`/whole-file-API interception; two-path self-test |
| entry [51] finding 3 (untracked-residue check executable in the clean state; SDD exception removed) | A15 | `git ls-files --others --exclude-standard`-based check; live-tested clean/non-clean |
| entry [53] finding 2 (untracked-residue check fails closed on upstream enumeration errors) | A15 | Separate `git ls-files` status check (no `\|\| true`); `awk`-based empty-safe filter; live-tested all three states |

### Track B

| Requirement | Task(s) | Verification |
|---|---|---|
| B2.1 | B1, B5 | Integration test; IV.2(b) |
| B2.2 | B1, B6 | IV.2(a) unit test |
| B2.3 | B5 | Code review — no sleep/repeat/weakening |
| B2.4 | B8 | Diff review — no production file touched |
| B2.5 | B8 | Diff review — sibling untouched |
| B-IG.1–B-IG.6 | Already completed during design | N/A — no re-run |
| entry [13] finding 4/6 | B3, B5 | Exact-200 + structural delta assertions |
| entry [15] finding 5a/5b/5c | B2, B4 | Code review; `RedisTimeParserTest` fixtures |
| entry [29] finding 5 (retained counterexample) | B5 | Pinned `d7b5b8d` evidence citation |
| entry [29] finding 6 (real IV.2(b) perturbation) | B7 | Test-seam-based post-capture mutation |
| entry [29] finding 7 / entry [31] finding 6 (durable, type-compatible parser) | B2 | `RedisTimeParserTest`; compile check on `this::redisTimeSeconds` |
| IV.2(a) | B6 | Dedicated unit test |
| IV.2(b) | B7 | Perturbation-through-seam fixture |
| IV.3 | **A15**, B2, B3, B6, B8, B9 (Gradle-convention count check throughout, both tracks) | Verification convention |
| IV.4 | A12, A15, B9 (full CI pipeline + static guard + canary + container build) | CI job graph |
| IV.5 | A15, B9 | Diff-scope + junk-file checks |
| entry [31] finding 7 (rerun/branch-protection coverage) | A15, B9 | Explicit six-job/branch-protection check on both PRs |
| entry [47] finding 7 (bounded stress policy, real baselines, explicit sibling run) | B5, B8, B9 | Exact 5-run policy; 8/5 method-count baselines; sibling run in B9 |
| entry [47] finding 8 (cross-package visibility) | B1, B2 | Explicit `public` declarations; cross-package compile checks |
| entry [49] finding 2 (`RedisTimeParser` class-level visibility) | B2 | `public final class RedisTimeParser` with `private` no-op constructor |
| entry [49] finding 3 (5-run stress policy has an aggregate deadline) | B5 | `timeout 900` wrapper; timeout/non-zero exit treated as failure, never retried |
| entry [49] finding 4 (immutable, git-derived regression baselines) | B8, B9 | `git merge-base`/`git show`-derived `prod_baseline`/`sibling_baseline`; mismatch is always a failure |
| entry [51] finding 2 (B5's placeholder replaced with a complete, paste-runnable command) | B5 | `run_five_times()` function; `export -f`/`timeout bash -c` pattern live-tested |
| entry [51] finding 4 (baseline variables survive to B9 without a shared shell session) | B8, B9 | `verify_track_b_baseline()` function reproduced verbatim in both; bound only to the recorded `BASE_SHA` |
| entry [53] finding 1 (B9's recorded-SHA input is executable, fail-closed shell, not a redirection-parsed placeholder) | B9 | `: "${BASE_SHA:?...}"` idiom; exact displayed line `bash -n`-verified (not a paraphrase) |
| entry [53] finding 3 (B5 fails a run when its own required duration evidence is missing) | B5 | Explicit success+non-empty check on the duration extraction; `return 1` on either failure |

---

## Owner approval gate

`tasks.md` Revision 8 is complete. No task in it has been executed. Per `bugfix.md`'s Recorded
Decisions and every checkpoint entry through [53]: **implementation may not begin until the owner
approves all three SDD artifacts — `bugfix.md` Revision 4, `design.md` Revision 16, and this
document — as a set.** Codex's traceability/executability/PR-boundary/stop-go review of this
document is the next step; owner approval follows that review, not before it.
