# Wave 10.2 Step B — exit criterion 5b production verifier: spec (rev 1, DRAFT for independent review)

> **AUTHORING ONLY.** Owner approval 2026-09-19: final review and PR preparation only; no production
> action ([decision record](../../evidence/b2-wave-10-2/WAVE_10_2_CONDITION_5_DECISION_2026-09-19.md)).
> **Not authorized:** running it against production, any live read, Step B, merge, workflow changes,
> flag or variable changes, deployment, CI wiring. No agent handles the demo password, the internal
> API key, or any token; every future production run is owner-operated. This spec is the single
> normative home for the verifier's *mechanism* (`tasks.md` change-control guardrail 2); `tasks.md`
> 10.2 (Step B exit criterion 5b) owns the *gate semantics*.

## 1. What it is

The verifier is the one executable owner of exit criterion 5b (`tasks.md` guardrail 1): named inputs,
deterministic exit codes, unconditional cleanup of the shared demo portfolio, and a machine-readable
evidence artifact. It proves, in a fresh real browser against the live public site, the five Wave 9
routes plus the manual-reset control, and states for each what level of evidence it has. It is **not**
credited with Step A, does not verify the Wave 8 login self-call, performs no deploy or flag action,
and does not resolve the Step B deploy path (decision record, "Constraints this decision leaves open").

## 2. Components

| Path | Role |
|---|---|
| `scripts/verify_step_b_5b.py` | **The executable owner.** Preconditions, one API login, browser child, independent reads, unconditional cleanup, bindings, verdict, evidence. Only place that holds the password |
| `frontend/tests/production-e2e/playwright.step-b-5b.config.ts` | Dedicated Playwright config: no `globalSetup`, no `webServer`, no `storageState`, trace/video/screenshot off |
| `frontend/tests/production-e2e/step-b-5b.spec.ts` | Browser legs L0–L9. Observes and appends to the ledger; **has no verdict authority** |
| `frontend/tests/production-e2e/lib/*.ts` | Pure logic: target allowlist, request classification, price attribution, ledger events |
| `scripts/tests/test_verify_step_b_5b.py`, `frontend/tests/e2e/helpers/__tests__/step-b-5b-*.test.ts` | Offline unit tests (no network, no browser) |
| `scripts/derive_demo_golden_state.py` | Reused unchanged: independent golden oracle (stdlib, subprocess `python -B`) |

The spec lives outside `frontend/tests/e2e` on purpose: the default config collects everything under
it and runs a seeding `globalSetup` that would touch production if `INTERNAL_API_KEY` or `.env.secrets`
were present.

## 3. Inputs

Targets are an exact allowlist: frontend `https://vibhanshu-ai-portfolio.dev`, API
`https://api.vibhanshu-ai-portfolio.dev`. Any other origin, scheme or port is exit 2. There is no
override flag; tests inject their own allowlist through a module-level seam.

| Input | Required | Meaning |
|---|---|---|
| `--evidence-output` | yes | Absolute local path outside the repository (UNC and device paths are refused); must not exist; parent must exist. Work files go to the sibling directory `<evidence-output>.work/` (created by the verifier, must not exist) |
| `--baseline-commit` | yes | 40-hex SHA; must equal `git rev-parse HEAD`; tracked files must be clean. Supplies the oracle's checkout state |
| `--deploy-completed-utc` | yes | Owner-supplied `...Z` time the flag-bearing deploy completed (`workflow.completedAt`). The run must start after it. Recorded as owner-attested |
| `--bound-seconds` | yes (no default) | The time bound named in the owner's Step B authorization, recorded verbatim: the artifact must be complete within this many seconds of deploy completion. A value below 300 is rejected as exit 2 so a typo cannot pass silently |
| `--pre-deploy-build-id` | yes | Next.js `buildId` the owner captured from the live site before the deploy |
| `--backend-attestation-pre` | yes | Owner-captured management-plane read of both services, taken after deploy completion and before this run (schema §6.2) |
| `--deploy-run-id`, `--source-head-sha` | yes | Owner-attested identifiers, recorded verbatim, never verified by the verifier |
| `--allow-active-presence` | no | Proceed to the mutating legs even if another demo session is active (recorded). Default: stop before any mutation |
| `--enable-internal-cleanup` | no | Prompt (masked) for the internal API key and use it only as owner-only break-glass cleanup (§5). Blank answer disables it |
| `--cleanup-only` | no | Reconcile a crashed run: observe, reset if needed, confirm golden, write a small non-gate cleanup artifact. Never a pass |
| `--operation-timeout` | no (30) | Seconds per API call other than the login, whose 225 s timeout is fixed; validated 1–120 |
| `--action-timeout-ms` | no (120000) | Per-action timeout of the browser legs, 1000–600000, passed as `STEP_B_5B_ACTION_TIMEOUT_MS`. A cold `market-data-service` can hold the page's price batches for close to the gateway's 55 s response timeout, so the default leaves margin. Values above 120000 raise per-action patience but never the child's total budget (test-level timeout 1,800 s, chosen so it fires before the orchestrator's kill) |

"Required" means required except with `--cleanup-only` (§10.9). The demo password comes only from a masked
interactive prompt (`getpass`), never argv or environment.
The demo email (`demo@wealthtracker.dev`) and `DEMO_USER_ID` (`00000000-0000-0000-0000-0000000d3110`)
are public constants. The post-run attestation is not an argument: the verifier prints its expected path
and waits for the owner (§6.2).

## 4. Procedure

Serialized; no leg is retried; no mutating request is ever retried.

**P0 Preconditions (exit 2 on any failure, nothing consumed).** Argument and allowlist checks; output
paths; git HEAD equals baseline and tree clean; oracle runs and yields the golden set and catalog
SHA-256; Node and the Playwright package with its CLI entry (`frontend/node_modules/@playwright/test/cli.js`) resolve
(`npm` and `npx` are never run); the full Chromium build the dedicated config selects (`channel: "chromium"`, so the
launch and the probe name the same binary) exists (`playwright-core`'s `chromium.executablePath()`); attestation-pre validates; `now` is after
`--deploy-completed-utc`.

**P1 Frontend binding, pre.** Fetch the site root three times at least 5 s apart (fresh, no cache).
Require an HTML response (SWA answers `200 index.html` for unknown paths, so status alone proves
nothing), extract the Next `buildId`, and record `buildId`, SHA-256 of the root HTML, and SHA-256 of the
sorted `/_next/static` asset names. The id is the top-level `b` key of the flight root row (`0:{...}`) in the page's
prerendered payload; this app's export has no `/_next/static/<id>/` directory, so that key is load-bearing, and a
future Next release that drops it makes P1 fail closed (exit 2). Fail if the id cannot be identified, differs between
fetches, or equals `--pre-deploy-build-id`.

**P2 Login (the only one).** `POST {api}/api/auth/login` with the demo credentials, timeout 225 s. A
login taking 165 s or more stops the run before any mutation (the login self-call's overall guard may
have fired). Register the returned JWT as a secret before use. The login registers a presence session
and may itself trigger the login-reset; every baseline is taken after it.

**P3 Baseline (read-only).** Identity-checked `GET /api/portfolio`: exactly one entry with
`userId == DEMO_USER_ID` (zero or several fail), integer `version >= 0`, string quantities. It must
equal the oracle golden set. If it does not, stop as `INCOMPLETE` with no mutation (restore with
`--cleanup-only`, then rerun).

**P3b Warm-up (read-only).** The page's controls render only after its price batches settle, and `market-data-service`
scales to zero, which the login and P3 do not warm. Before the child starts, the orchestrator issues up to 6
`GET /api/market/prices?tickers=<first golden ticker>` requests with the saved JWT, each with a 90 s timeout, within
a 360 s budget (after a failed probe it sleeps `min(10 s, remaining)` so a fast non-`200` cannot exhaust the probes
in seconds), and stops at the first `200` whose body is a JSON array. If none succeeds the run stops as `INCOMPLETE` (`WARMUP_FAILED`)
before any mutation. The artifact records `warmup: {probes, last_status, seconds}`. This is verifier traffic, never
browser evidence, and it precedes the `cleanup_armed` marker.

**P4 Browser child.** One Playwright run with an allowlisted environment (§7). Legs below.

**P5 Independent read and cleanup — runs whenever P4 was started, whatever P4 did (including a killed or
timed-out child).** State-based and idempotent (§5). A stop before P4 (a failed precondition, a login
that took 165 s or more, or a non-golden P3 baseline) performs no cleanup and no mutation; the owner
restores a non-golden portfolio with `--cleanup-only`.

**P6 Binding, post.** Fetch the root once more (same `buildId`); wait for the owner's post attestation and
require it to equal the pre attestation on every service revision and digest.

**P7 Verdict and evidence (§6).**

### 4.1 Browser legs

Every leg asserts on the page's own network traffic (`request`/`response` events armed before the action;
no route interception) and, where a UI consumer exists, on its rendered effect. API calls made by the
verifier itself never count as browser evidence.

| Leg | Route | Action | Passes when |
|---|---|---|---|
| L0 | — | Inject `localStorage['wmpt.auth.session']` = JSON `{token, userId, email, name}` (init script) and open `/portfolio` | Final path is `/portfolio`, `h1` is "Portfolio", no redirect to `/login`, no 401 |
| L1 | controls | — | `Edit Holdings` and `Reset Demo Portfolio` (exact) buttons visible **and the page's last `GET /api/portfolio` was `200`** (controls drawn from cached data after a failed refetch do not count). If not visible, `portfolioLoadStatus` distinguishes a failed portfolio load from a flag that is off (§10.7) |
| L2 | 9.5 | — | `GET /api/portfolio/summary` `200` whose `assetPriceFreshness.state` is one of FRESH/STALE/UNKNOWN/MISSING with non-negative integer counts, **and** the "Prices as of" strip is visible. State is recorded, not required to be FRESH |
| L3 | — | Click `Edit Holdings` | Dialog "Edit Holdings" open and the "temporarily unavailable" notice absent (a rendered button can still be blocked) |
| L4 | 9.1 | (open) | `GET /api/assets` `200`, issued after the click with no `If-None-Match`, response has `etag`, body has `catalogVersion` and a non-empty `assets`; rows render; deployed ACTIVE tickers equal the oracle golden tickers (`catalogParity`) |
| L5 | 9.4 | (open) | Exactly one `GET /api/presence/demo` per opening, `200`, boolean `anotherSessionActive` (recorded, not required false); no `requestfailed`, no CORS console error. Assert the response, not the banner (the UI fails open) |
| L6 | 9.3 | Uncheck a held ticker T in the draft (draft-only): the alphabetically first held ticker whose removal yields disjoint batches; if none does, L6 fails | Picker batches predicted as `chunk25(sort(draft − T))` are disjoint from the page's own enrichment batches `chunk25(wire order)`, where *disjoint* means no predicted picker batch equals, as an ordered comma-joined `tickers` query value, any page enrichment batch. Picker requests are then attributed by that equality among requests issued after the uncheck; every one is `200` and array-shaped, their number equals the predicted batch count (a duplicated picker batch fails), and at least one price is non-null. The leg judges only the attributed picker requests: `priceRequestsAfterUncheck` is their count, and a page-side price request that fails is not attributed to route 9.3. Only booleans and counts recorded; the ticker lists are never recorded |
| L7 | — | Close the dialog without saving | Zero non-`GET`/`OPTIONS` API requests originated from the page so far |
| gate | | | **Mutate only if L0–L7 passed, P3 was golden, and (`anotherSessionActive` is false or `--allow-active-presence`).** Otherwise legs L8–L9 are `skipped`. The verdict is `INCOMPLETE` when `mutationSkippedReason` is `VISITOR_PRESENT` or `BASELINE_NOT_GOLDEN`, and `NON_GO` when it is `LEG_FAILED` (a failed read-only leg is a failure, not an incomplete run) |
| L8 | 9.2 | Reopen (fresh draft); set `AAPL` to golden quantity + 1 (8 dp string); `Review changes` shows "Changed — 1"; `Save changes` | Ledger `armed` written first. Exactly one `PUT /api/portfolio/holdings` with `expectedVersion` equal to the page's last observed version and the expected full draft; `200`; `version` strictly advances; UI "Holdings saved."; then an **independent out-of-page read** returns that version and exactly the expected draft |
| L9 | reset | Click `Reset Demo Portfolio` | Exactly one `PUT /api/portfolio/demo-reset` with `expectedVersion` equal to the saved version, no `X-Internal-Api-Key`, `200`, version + 1, response equals golden; UI "Demo portfolio reset."; independent read equals golden |

L2 and L4 judge the **first** response after their mark. The app itself retries a transient failure (summary once,
catalog twice), so a first-attempt non-`200` followed by a consumed `200` fails the leg: this is deliberately stricter
than the gate text and is fail-closed, and the owner decides whether to rerun before deciding on rollback. L1 records
and judges only *settled* portfolio reads (an in-flight refetch is not a load outcome). L8 and L9 wait until the
page's own portfolio and price reads have settled and been quiet for about 2 s before the Save and Reset clicks, so a
refetch burst is never in flight or freshly settled when the write is sent. This reduces, but does not remove, the
chance that the page's independent 60 s refetch timer shares a rate-limit second with the write.

Golden holds every ACTIVE asset, so nothing is addable from golden; the save leg is a single quantity
edit, which is a real change (an unchanged payload is a no-op that does not advance the version).

## 5. Independent cleanup (P5)

Runs in the orchestrator process, over the saved JWT, so it works if the browser died and does not depend on the
browser control under test.

1. Observe (identity-checked `GET`). If it equals golden: done, no mutation (`not_needed`).
2. Otherwise `PUT /api/portfolio/demo-reset` with the observed version. On `409`, re-observe (identity-checked) and
   retry, at most 3 attempts. Any status other than `200`/`409` stops the loop. Retrying here is the
   verifier's own bounded retry, not an application retry.
3. If still not golden and `--enable-internal-cleanup` supplied a key: one owner-only break-glass reset through the
   internal route. The key is never argv, environment or evidence.
4. Observe again; require exactly the golden set. Anything else is `unconfirmed` and forces verdict `NON_GO`
   (the decision record's "unconfirmed cleanup is a Step B failure"; the owner restores by hand or with
   `--cleanup-only`, and the next idle demo login also self-heals).

`BaseException` (Ctrl+C, window close) must still run cleanup. The orchestrator's write-ahead ledger event `cleanup` with
result `cleanup_armed`, written before the browser child starts, leaves a machine-readable "cleanup owed" record if
the run is killed (§10.2); the child's own `{"event":"armed"}` is a separate line written immediately before Save.

## 6. Evidence

### 6.1 Ledger and artifact

The spec appends closed-vocabulary events to `<evidence-output>.work/ledger.jsonl` (`armed`, and one final `leg`
event per leg with `status` and `reason`, HTTP method + path template + status, booleans and counts; the artifact's
`reason_code` is copied from the ledger's `reason`, or is an orchestrator-assigned leg reason: `LEDGER_INVALID`,
`LEG_MISSING`, `FORBIDDEN_HTTP_STATUS`, `FACTS_INCOMPLETE`, `FACTS_CONTRADICT_PASS`, `HTTP_CONTRADICT_PASS`). It never records bodies, query values,
headers, tokens, holdings, tickers, or page text. The orchestrator derives the per-leg results from the ledger,
so a killed run still leaves partial, honest evidence.

The final artifact, schema `wave10-5b-evidence-v1`, is built only from an allowlist:

- `verdict` (`GO` | `NON_GO` | `INCOMPLETE`), `stop_reason` (code plus code-built text), start/end UTC,
  `deploy_completed_utc` (owner-attested), `bound_seconds`, `elapsed_since_deploy_seconds`, `within_bound`;
- `baseline_commit`, `tree_clean`, SHA-256 of the verifier script, oracle `catalogSha256` and holding count;
- `targets` (the two allowlisted origins), `login` (duration, within timeout);
- `binding.frontend`: observed `buildId`, differs-from-pre-deploy, stable-fetch count, root-HTML and asset-name
  hashes, and `claim: "observed served build; source commit not observable from the site"`;
- `binding.backend`: per service revision and digest, byte size and SHA-256 of both attestation files,
  `pre_equals_post`, and `claim: "owner-attested management-plane read"` (never "verified by the verifier");
  `owner_attested`: `deploy_run_id`, `source_head_sha`;
- `legs[]`: id, route, `evidence_level`, status, `reason_code`, HTTP entries, facts;
- `routes`: per route 9.1–9.5 and reset, its evidence level (§6.3);
- `cleanup`: armed, attempts, transport, result, golden confirmed;
- `gate_map`: `step_a_credited: false`, `run_result: "all_items_passed" | "not_all_items_passed"`.

The artifact asserts only what this run observed. Credit for exit criterion 5b is decided by the owner from this
artifact together with facts outside the verifier (that the script is the merged, ACCEPTed version; the owner-attested
deploy-completion time; the absence of any later redeploy or flag change); the artifact never states that 5b is
satisfied.

Writing is exclusive-create, UTF-8 without BOM, `\n` newlines, fsync, then the byte size and SHA-256 are printed. The
original stays out of the repository unchanged; only a sanitized record is imported later, as for Step A.

### 6.2 Attestation file (owner-produced, read only for these fields)

```json
{ "schema": "wave10-5b-backend-attestation-v1", "readAtUtc": "2026-01-01T00:00:00Z",
  "services": { "api-gateway": { "revision": "...", "digest": "sha256:..." },
                "portfolio-service": { "revision": "...", "digest": "sha256:..." } } }
```

Extra fields (subscription or workspace identifiers) are ignored and never recorded. `pre.readAtUtc` must be after
`--deploy-completed-utc` and before the run start; `post.readAtUtc` after the browser child ended.

### 6.3 Evidence levels stated in the artifact

| Route | Level | Not covered here (CI-only) |
|---|---|---|
| 9.1 catalog | production-browser | `304`/`If-None-Match` revalidation |
| 9.2 save | production-browser; independent read production-API-only | 409 conflict UI |
| 9.3 draft prices | production-browser | picker price fail-soft paths |
| 9.4 presence | production-browser (network status and boolean) | presence expiry, fail-open UI |
| 9.5 freshness | production-browser | STALE/UNKNOWN/MISSING variants |
| reset control | production-browser; independent read production-API-only | 409 conflict branch |

## 7. Verdict, exit codes, safety

**GO** iff all of: L0–L9 passed; P3 was golden; frontend binding (differs from pre-deploy, stable, controls rendered)
and backend binding (attestations valid, `pre_equals_post`) hold; the artifact is complete within
`--bound-seconds` of deploy completion; cleanup is `confirmed` or `not_needed`; the sanitizer scan is clean; the file
was written. Anything else is not GO. `INCOMPLETE` is a safe abort before any mutation (baseline not golden, visitor
present, a login of 165 s or more; §10.3); the gate treats it as "cannot finish", never a pass. **A pass on an earlier deploy never counts, and any
redeploy or flag change after a pass voids it** — the artifact binds the observed build and attested revisions so a
reader can check.

Exit codes: `0` GO; `1` NON_GO or INCOMPLETE (evidence written); `2` precondition error before login (no evidence).
`--cleanup-only`: `0` confirmed golden, `1` unconfirmed, `2` precondition.

Transport: every request goes direct to the two allowlisted origins. The orchestrator's HTTP client ignores proxy
settings (`ProxyHandler({})`) and refuses redirects, and the browser is launched with `--no-proxy-server`, so an
environment or operating-system proxy can never carry the password, the token or the internal key through a host the
allowlist did not name. A network that requires a proxy makes the run fail closed. The child is started with
`node node_modules/@playwright/test/cli.js`, never through `npm` or `npx`, which contact the npm registry (update
notifier) and may auto-install when non-interactive.

Safety controls (each pinned by a test): the password stays in orchestrator memory and is never in any child
environment or argument; the browser child receives an allowlisted environment carrying only the token; `DEBUG` and
`PWDEBUG` are cleared; trace, video, screenshot and HAR are off; Playwright output goes to the work directory; the
final artifact passes a pattern scan (JWT shape, `Bearer`, `Authorization`, `password`, non-public emails, hosts
outside the allowlist, subscription-style GUIDs) and, if it trips, a scrubbed `NON_GO` stub with reason
`SANITIZER_TRIPPED` is written instead; a `429`, `409`, `503` or `504` on any leg is a failure; the
login-to-cleanup span must end within 2,700 s (the JWT lives 3,600 s; §10.5), and the child is killed at that deadline with
cleanup still running.

## 8. Test plan (authoring-time verification; no network, no browser, no production)

Python (`pytest`, injected seams for HTTP, clock, sleep, `getpass`, subprocess, git, oracle and root-HTML fetch):
argument validation and the origin allowlist; output-path rules (absolute, outside the repo, exclusive create, `\n`
newlines, no overwrite); git HEAD and clean-tree checks; login handling (timeout, the 165 s guard, token
registered as a secret); baseline not golden and visitor present both yielding `INCOMPLETE` with no mutation; ledger
parsing into legs; a verdict table proving each single failure (each leg, each binding rule, the time bound,
unconfirmed cleanup, a sanitizer trip, a child exit code other than 0 with an otherwise clean ledger) yields
non-GO; the cleanup state machine (no-op when golden, reset with `409` re-observation and the 3-attempt bound,
`unconfirmed`, and `KeyboardInterrupt` still running cleanup); pattern-scan positive controls (JWT shape,
`Bearer`, `Authorization`, `password`, non-public email, foreign host); the child environment allowlist (password
and internal key absent, token present, `DEBUG`/`PWDEBUG` cleared); exit codes; `--cleanup-only`; build-id
extraction from representative HTML including the SWA soft-404 case.

TypeScript (`vitest`, pure functions only, no environment reads at import time): target allowlist, request
classification and path templating (no query value survives), price-attribution arithmetic (`chunk25`, disjointness,
the failure when no ticker gives disjoint batches), ledger event construction against the closed vocabulary.

Static: `tsc --noEmit`, `eslint` on the new files, and `playwright test -c <config> --list` proving the dedicated
config loads and finds exactly the one spec (this launches no browser). Guards to keep green: the seed-path literal
guard (`scripts/check-b1-seed-version-callers.py`) must not see the seed route in any new file, and the demo-identity
three-source guard (`scripts/check_b2_demo_identity.py`).

## 9. Authoring status and what is deliberately not done

- Offline unit tests and static checks (§8) are the only verification performed at authoring time.
- **Not done, needs a separate owner decision:** a rehearsal against the disposable Compose stack (needs a guarded
  test-only origin override); wiring these tests into CI (a workflow change); a commit-level frontend binding
  (`NEXT_PUBLIC_BUILD_SHA` or `generateBuildId` from `GITHUB_SHA`, a source and workflow change).
- Open owner choices with the defaults implemented here: presence policy (default abort before mutation); internal-key
  break-glass (default off); frontend binding sufficiency (default: observed `buildId` plus owner-attested source
  SHA); the `--bound-seconds` value (named at Step B authorization, no default).
- Review path: independent review by a model other than the author, then documentation reconciliation per the interim
  review arrangement.

## 10. Clarifications adopted during authoring

Decided while implementing; each is fail-closed and pinned by a test. Reviewers should confirm or overrule each one.

1. **P1 failures are exit 2, no evidence.** The frontend binding runs before the login, so a `buildId` that cannot be
   read, is unstable, is not a Next.js page, or equals `--pre-deploy-build-id` is a precondition failure: nothing was
   consumed.
2. **Cleanup arming.** The orchestrator writes its own write-ahead ledger event `cleanup` with result `cleanup_armed`
   immediately before the browser child starts; P5 may mutate only once that marker exists. A stop before it
   (precondition, slow login, non-golden baseline) records `skipped_no_mutation` and resets nothing. The child's own
   `armed` event (before Save) is additional and does not gate cleanup. A hard kill of the orchestrator leaves a
   `cleanup_armed` event with no closing event; the owner recovers with `--cleanup-only`.
3. **`INCOMPLETE`** is exactly `BASELINE_NOT_GOLDEN`, `VISITOR_PRESENT`, `WARMUP_FAILED` and `LOGIN_TOO_SLOW` (a login
   of 165 s or more). Every other stop is `NON_GO`, and a hard failure alongside an INCOMPLETE-class stop makes the verdict
   `NON_GO`. A browser-side gate skip for a non-golden page state after L0–L7 passed (`MUTATION_SKIPPED_UNEXPECTEDLY`)
   is `NON_GO`, because the P3 baseline was golden.
4. **Post attestation.** Expected at `<evidence-output>.work/backend-attestation-post.json`. The prompt is a blocking
   input, so the time bound is enforced by the verdict afterwards. When the run can no longer be GO the owner is not
   asked and the post state is recorded `skipped`.
5. **2,700 s deadline.** Measured from the start of the login call (the JWT's age; it lives 3,600 s) to the end of
   cleanup, so the owner's typing at the password prompt and the blocking post-attestation wait do not count; the
   `--bound-seconds` criterion still runs on the wall clock. At the deadline the child is killed and cleanup follows.
   A run that hits it is non-GO.
6. **Ledger strictness beyond Appendix A.** `seq` is consecutive per writer from 1; `status` and `reason` agree
   (`passed` iff `OK`, `skipped` iff `SKIPPED_*`, otherwise `failed`); **a passed leg carries every fact key of its
   leg and satisfies every rule of `passFacts[leg]` in `ledger-contract.json`, enforced by the orchestrator (the
   verdict authority) and again by the TypeScript writer; a passed leg whose facts are incomplete or contradict it is
   downgraded to failed (reasons `FACTS_INCOMPLETE`, `FACTS_CONTRADICT_PASS`, and `HTTP_CONTRADICT_PASS` when its `http` entries contradict its facts per `passHttp`)**; a skipped L8/L9 reason agrees with
   `mutationSkippedReason`, and a passed L8/L9 carries only `NONE`; a leg is
   downgraded to failed if any recorded HTTP status, or integer `*Status` fact, is 409, 429, 503 or 504 (reasons
   `CONFLICT`, `RATE_LIMITED`, and `HTTP_STATUS_NOT_200` for 503/504). At most 200 `http` entries per leg and integer
   facts up to 10^9.
7. **L1 and reason codes.** There are no dedicated codes for `portfolio_load_failed` and `flag_off`: a non-200 last
   portfolio read maps to `HTTP_STATUS_NOT_200` / `RATE_LIMITED` / `REQUEST_FAILED` and an absent control with a healthy
   read to `ASSERTION_FAILED`; `portfolioLoadStatus` carries the status. A refused start (bad environment or golden
   file) is L0 `ASSERTION_FAILED` with L1–L9 `SKIPPED_PRIOR_FAILURE`.
8. **Browser gate and scoping.** L8/L9 run only if the page's own last identity-checked portfolio read equals golden at
   exactly `STEP_B_5B_BASELINE_VERSION`, and `anotherSessionActive` is exactly `false` (null or unknown counts as a
   visitor) unless `--allow-active-presence`. L5's "no `requestfailed`, no CORS console error" is scoped to API-origin
   requests and to console errors after the picker opened. L6 also requires the checked-row count to equal the held
   count, and disjointness must hold against every good page portfolio read.
9. **`--cleanup-only`** requires only `--evidence-output` and `--baseline-commit`, performs its own login even if it
   takes 165 s or more, and writes `wave10-5b-cleanup-v1`, never a pass.
10. **The work directory is sensitive.** Browser child stdout/stderr go to `<work>/child.log` and Playwright output to
    `<work>/pw-output`; neither is scanned nor part of the artifact. Never import the work directory into the
    repository; delete it after the run. Child-tree kill is best effort (`taskkill /T /F` on Windows), so orphaned
    browser processes are possible; the child starts in its own process group, and a child not seen dead after the kill
    is recorded `kill_unconfirmed`, a hard `NON_GO` (`CHILD_KILL_UNCONFIRMED`) that also downgrades any "clean" cleanup
    to unconfirmed.
11. **Extra artifact fields.** Beyond §6.1 the artifact carries `baseline`, `presence`, `child`, `warmup`, `run_seconds`,
    `session_seconds`, `cleanup.break_glass_used`, `cleanup.detail_code`,
    `deploy_completed_utc_source: "owner_attested"`, `stop_reason.failed_criteria` and `stop_reason.exception_types`
    (class names only, never messages); the top-level key set is pinned by a test. `--deploy-run-id` and
    `--source-head-sha` must match `[A-Za-z0-9._-]{1,128}`. The exact-secret scan keeps only the length and SHA-256
    fingerprint of the password and break-glass key, and skips secrets shorter than 6 characters.
12. **Break-glass shape is unverified.** The optional internal cleanup assumes `PUT /api/internal/portfolio/demo-reset`
    with an `expectedVersion` body and an `X-Internal-Api-Key` header, taken from research notes; it has never been
    exercised, by design, and may be wrong.
13. **Two names for one downgrade.** The orchestrator's leg reasons `FACTS_INCOMPLETE` / `FACTS_CONTRADICT_PASS` /
    `HTTP_CONTRADICT_PASS` and the TypeScript writer's error codes `FACTS_INCOMPLETE_FOR_PASS` / `FACTS_CONTRADICT_PASS` /
    `HTTP_CONTRADICT_PASS` describe the same conditions in
    two places: the writer refuses to record such a leg as passed (it is recorded failed `EXCEPTION`), and the
    orchestrator independently refuses to believe one. A `{"not": v}` rule means "differs from `v` and has `v`'s type";
    a `passFacts` table naming an unknown leg or fact, an unrecognised rule form, a type-mismatched literal or an
    `equalsFact` across types is a contract error, not ignored.
14. **L6 ledger entries.** Only the attributed picker price requests appear in L6's `http` list; a page-side price request
    (enrichment refetch) never does, so it can neither fail nor pass route 9.3.
15. **SIGINT shielding.** SIGINT is deferred from the moment the child is confirmed dead or given up on (inside the child
    phase, before `child_done` is written) until cleanup finishes. The owner's
    blocking post-attestation wait and the evidence write are deliberately not shielded, so the owner can still
    interrupt the wait and get evidence written.
16. **Scanner internals.** The exact-secret scan keeps, in memory only, the length, SHA-256 and a CRC-32 prefilter of the
    raw, JSON-escaped, percent-encoded and base64 forms of the password and break-glass key; the CRC-32 is never written.
    A normalized copy of the artifact text (percent-decoded, JSON-unescaped, whitespace removed) is also scanned for
    credential-shaped classes and exact secrets. The `basic` credential class is detected in raw text only (a whitespace-free
    collapsed copy would false-trip on glued words), and an owner secret that itself contains whitespace is also
    registered in whitespace-stripped form so the collapsed copy can find it.

## Appendix A — ledger contract (browser child → orchestrator)

`<evidence-output>.work/ledger.jsonl`: UTF-8, one JSON object per line, appended (never rewritten). Every line has
`seq` (integer, starting at 1 per writer), `tUtc` (ISO-8601 `Z`, milliseconds), `src` (`"spec"` or
`"orchestrator"`) and `event`. Unknown keys, or values outside the vocabularies below, make the orchestrator
treat that line as a failed leg (fail closed).

Events written by the spec:

```json
{"event":"leg","leg":"L4","status":"passed","reason":"OK",
 "http":[{"method":"GET","path":"/api/assets","status":200}],
 "facts":{"catalogStatus":200,"noIfNoneMatch":true,"etagPresent":true,"assetsNonEmpty":true,"rowsRendered":true,"catalogParity":true,"activeCount":159}}
{"event":"armed"}
```

- Exactly one final `leg` event per leg id `L0`…`L9`; a second `leg` event for the same leg is invalid and poisons
  that leg (`DUPLICATE_LEG`). Duplicate keys inside one JSON line, a second `armed` line (`DUPLICATE_ARMED`), and any `src: spec` line after the
  orchestrator's `child_done`, are invalid. Integer facts are `0…10^9`; an `http` status is `0` or `100…599`. A leg that never
  reports is `missing`, which is a failure.
- `armed` is written immediately before clicking `Save changes` (L8): after L7's final event and before L8's.
- Orchestrator events: `child_started`, `child_done` (with `exit`), `cleanup` (attempt, transport, result).

The machine-readable source of truth for every vocabulary, fact key and **pass rule** below is
`frontend/tests/production-e2e/ledger-contract.json` (schema `wave10-5b-ledger-contract-v3`); both the spec and the
orchestrator load it and a test on each side pins it. The tables here are informational. `passFacts` states what a leg's
facts must look like for `status: "passed"` to be believed; the orchestrator, not the browser child, decides. `passHttp`
does the same for the leg's `http` entries: a passed leg's recorded requests must have the leg's method and path and
agree with its count and status facts, so a fact can never contradict the leg's own request evidence.

Closed vocabularies. `status`: `passed` | `failed` | `skipped`. `reason`: `OK`, `ASSERTION_FAILED`, `TIMEOUT`,
`HTTP_STATUS_NOT_200`, `RATE_LIMITED`, `CONFLICT`, `REQUEST_FAILED`, `SKIPPED_PRIOR_FAILURE`,
`SKIPPED_VISITOR_PRESENT`, `SKIPPED_BASELINE_NOT_GOLDEN`, `EXCEPTION`. `method`: `GET` | `PUT`. `path` (templates,
never with a query string): `/api/portfolio`, `/api/portfolio/summary`, `/api/assets`, `/api/presence/demo`,
`/api/market/prices`, `/api/portfolio/holdings`, `/api/portfolio/demo-reset`. `status` in `http` is an integer
(`0` when the request failed).

`facts` keys (values are booleans, integers or the listed enums; nothing else):

| Leg | Keys |
|---|---|
| L0 | `finalPathIsPortfolio`, `headingPortfolio`, `redirectedToLogin`, `unauthorized401Seen` |
| L1 | `editButtonVisible`, `resetButtonVisible`, `portfolioLoadStatus`, `resetEnabled` |
| L2 | `freshnessState` (`FRESH`\|`STALE`\|`UNKNOWN`\|`MISSING`\|`ABSENT`), `countsValid`, `stripVisible` |
| L3 | `dialogOpen`, `unavailableNoticeVisible` |
| L4 | `catalogStatus`, `noIfNoneMatch`, `etagPresent`, `assetsNonEmpty`, `rowsRendered`, `catalogParity`, `activeCount` |
| L5 | `presenceRequestsSinceOpen`, `presenceStatus`, `anotherSessionActive` (boolean or `null`), `requestFailed`, `corsConsoleError` |
| L6 | `priceRequestsAfterUncheck`, `allStatus200`, `arrayShaped`, `nonNullPriceSeen`, `disjointFromPageBatches`, `predictedBatchCount` |
| L7 | `pageWriteRequestsBeforeMutation` |
| L8 | `putCount`, `putStatus`, `expectedVersionMatchesObserved`, `bodyMatchesExpectedDraft`, `versionAdvanced`, `savedStatusVisible`, `independentReadVersionMatches`, `independentReadHoldingsMatch` |
| L9 | `putCount`, `putStatus`, `noInternalKeyHeader`, `expectedVersionMatchesSaved`, `versionPlusOne`, `responseEqualsGolden`, `resetStatusVisible`, `independentReadEqualsGolden` |
| gate | `mutationSkippedReason` (`NONE`\|`LEG_FAILED`\|`VISITOR_PRESENT`\|`BASELINE_NOT_GOLDEN`) on L8 and L9 |

## Appendix B — orchestrator → browser child contract

Invocation: `node node_modules/@playwright/test/cli.js test -c tests/production-e2e/playwright.step-b-5b.config.ts`
with the working directory set to `frontend/` (no `npm`/`npx`, see §7 Transport). Reporter `null`; output directory `<work>/pw-output`; one worker, no retries, no `webServer`,
no `globalSetup`, no `storageState`. The child exit code is **not** authoritative: the verdict comes from the ledger,
and any non-zero exit with an otherwise clean ledger is `NON_GO`.

Environment (allowlist; nothing else is inherited except the OS variables Node and Playwright need to start, such as
`PATH`, `SYSTEMROOT`, `TEMP`, `TMP`, `USERPROFILE`, `HOME`, `APPDATA`, `LOCALAPPDATA`, and
`PLAYWRIGHT_BROWSERS_PATH` when set):

| Variable | Value |
|---|---|
| `STEP_B_5B_FRONTEND_URL`, `STEP_B_5B_API_URL` | the two allowlisted origins |
| `STEP_B_5B_TOKEN` | the demo JWT from the single login (never the password, never the internal key) |
| `STEP_B_5B_EMAIL`, `STEP_B_5B_USER_ID` | public constants |
| `STEP_B_5B_WORK_DIR`, `STEP_B_5B_GOLDEN_FILE` | absolute paths; the ledger is `<work>/ledger.jsonl` |
| `STEP_B_5B_BASELINE_VERSION` | integer version read in P3 |
| `STEP_B_5B_ALLOW_ACTIVE_PRESENCE` | `"1"` or `"0"` |
| `STEP_B_5B_ACTION_TIMEOUT_MS` | integer; always set by the orchestrator from `--action-timeout-ms` (default 120000); the child's own fallback (60000) applies only when the variable is absent, such as under `playwright --list` |

The spec reads its configuration only inside the test body (never at import time) and refuses to start unless both
origins match the allowlist.

Golden file (`schema: "wave10-5b-golden-v1"`), written by the orchestrator from the oracle (the example is
illustrative; every value comes from the oracle, for instance `AAPL` derives quantity `37.00000000`):

```json
{"schema":"wave10-5b-golden-v1","catalogSha256":"<HEX>",
 "holdings":[{"assetTicker":"AAPL","quantity":"37.00000000"}],
 "activeTickers":["AAPL"]}
```

`holdings` are the oracle's `wireHoldings`, sorted by `assetTicker` (ASCII order); quantities are 8-decimal strings.
The spec's independent out-of-page reads use the token over HTTP (not the page) and compare sorted
`(assetTicker, quantity)` pairs as strings.
