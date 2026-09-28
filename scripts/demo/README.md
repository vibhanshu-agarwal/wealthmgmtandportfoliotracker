# Preserved desktop-demo kit

**Operational approval required:** the warm-up and browser reader contact the deployed demo.
Running the offline tests below does not authorize sign-in, chat, edits, a live rehearsal,
cloud/secret access, deployment or cleanup. On 2026-09-27 UTC the owner approved publication
and a merge commit once all applicable CI checks are green, with no deployment or live operations.
The kit is filed in `main` through #335 at `66e5064f`; that publication does not authorize
live execution.

Use the [operator guide](../../docs/runbooks/DEMO_OPERATOR.md) and
[freeze/restart handoff](../../docs/runbooks/PROJECT_FREEZE_HANDOFF.md). Do not run a live
helper merely to check that it was copied.

## Contents and provenance

The five existing helpers were copied byte-for-byte from the privately reviewed 2026-09-23
A4/operator handoff on 2026-09-27 UTC. No runtime behavior was redesigned. Their source SHA-256
values below identify the preserved bytes; Git attributes retain LF checkout endings.

Unchanged helper comments use old names: the test's `test/mock-health-server.mjs` now means
`tests/mock-health-server.mjs`; the reader's "operator script, step 0" means
[DEMO_OPERATOR.md §3](../../docs/runbooks/DEMO_OPERATOR.md#3-step-0--hard-stop-before-any-edit).

| Repository path (relative to this folder) | SHA-256 |
|---|---|
| `demo-warmup.ps1` | `9589a873a46f7cf8897b901844d263eb6d22bb1c7c3e47fd47638d3a4c384cda` |
| `tests/demo-warmup.test.ps1` | `640365788af3a72c8f4e1ccf74f8611a58208ca6e7a1c650d33423324d65ca5e` |
| `tests/mock-health-server.mjs` | `abd5cbf62a9a959acfaa73e5c8071980ddcb466278c12fd0d368e7cae9ac48e5` |
| `rehearsal-read-portfolio.js` | `7f857ee6070a9b5354dd16f6d20c2b2abc1bf54f77ab420cc2e37bf565438fc3` |
| `verify-snapshot.py` | `6f760d03351f723f5c33ef30389169f6e298d0cebe7cc128962f64b7c5335bf4` |

The private operator draft (`c2aea2f017f8dbf5874eb8b89b6eec67eb58e6e7ed5af54a1cc36933b308f223`)
followed a rehearsed copy (`0a0005a7bccdd741ce9dc6ebea50ca939c39c877d993207e019fb8f5b8594d11`).
It is not copied as current instructions: its account wording, revision table and targeted-check
status had become stale. The new guide preserves the edit/reverse/verify sequence but reconciles
those facts. **Independent review cleared the corrections at `0ad791ae`; publication and
conditional green-CI merge are owner-approved, and the new guide has not been live-rehearsed.** Acceptance
of the private draft does not automatically accept this rewrite.

The fixtures contain two invented holdings only, not private account data. The seeded E2E
email in the reader is a public test identifier, not a credential. No passwords, tokens,
credential files or raw authenticated responses are included.

## Offline verification from the repository root

Dependencies: Windows PowerShell 5.1, Node.js on PATH, and Python 3 on PATH. The warm-up test
starts only a localhost mock and uses temporary GUID-named folders; it does not use the
warm-up's live API default. It deletes only its own temporary case folders.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo/tests/demo-warmup.test.ps1
node --check scripts/demo/tests/mock-health-server.mjs
node --check scripts/demo/rehearsal-read-portfolio.js
python -B -m unittest discover -s scripts/tests -p test_master_plan_status_propagation.py
python -B scripts/demo/verify-snapshot.py --diff scripts/demo/tests/fixtures/baseline.txt scripts/demo/tests/fixtures/baseline.txt
python -B scripts/demo/verify-snapshot.py --diff scripts/demo/tests/fixtures/baseline.txt scripts/demo/tests/fixtures/changed.txt
```

Expect the warm-up test to exit 0 with no FAIL lines; the identical fixture exits 0, and the
changed fixture exits **1** with `CHANGED AAPL: 1.000 -> 1.001`. That last failure is deliberate.
At this cut, the unittest command should report `Ran 33 tests`, `OK`, and exit 0. Run it from
a full Git checkout: a partial archive missing workflow files is not sufficient for this suite.
These fixture checks are limited CLI smoke checks, not comprehensive verifier coverage.
The JavaScript reader is syntax-checked only in this preservation task; it is not executed
against a browser or live session.
The named 33-test suite verifies `scripts/check_master_plan_status_propagation.py`; it is
distinct from the CI changed-path classifier, `scripts/classify_changed_paths.py`.

The [handoff](../../docs/runbooks/PROJECT_FREEZE_HANDOFF.md) records both the initial candidate
check and the later full clone of merged `main@59af233e`: 54 warm-up checks, bounded syntax and
fixture checks, 33 status-propagation tests and local restart-document paths. They are offline
evidence, not a fresh live rehearsal. Independent reviewer clearance at `0ad791ae` is recorded
separately; it does not broaden the offline verification scope.

## Runtime limits that matter

- Warm-up: anonymous health GETs to four paths, ten-minute readiness window, then 45-minute
  keep-alive after GO. Each path needs a 200 no more than 120 seconds old. A deadline wins over
  a late 200. Job shutdown can add a few seconds. Waking services can advance Kafka consumers.
- Reader: fixed to the seeded E2E identity and this demo's API, not a generic account tool.
  Run only inside the approved signed-in page. The token stays inside its closure.
- Numeric quantities require the browser's JSON reviver source-text support. Do not rely on
  the old comment's browser-version estimate; inspect `allQuantitiesExact` and the returned
  quantities. If false or malformed, stop before editing. Do not accept rounded numbers.
- The reader produces a canonical copy and live digest, **not a preserved live API response**.
  Save copies privately with LF and exactly one final newline. The verifier hashes without
  that final newline and compares exact quantity text.
- The verifier does not validate account identity, portfolio count or version; the operator
  checks those separately. IDENTICAL certifies only the compared ticker/quantity text.
- No live credentials or private snapshots are needed to run the offline commands.

Any generalization, new automation, CI integration or changed helper behavior belongs in a
separate implementation task with review; this migration is preservation only.
