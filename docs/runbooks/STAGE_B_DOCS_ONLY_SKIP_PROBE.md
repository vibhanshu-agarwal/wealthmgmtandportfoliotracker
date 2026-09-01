# Stage B docs-only skip probe

Temporary validation artefact for the docs-only CI fast path (PR #199).

This file exists only to produce a pull request whose entire diff matches the
documentation allowlist in `scripts/classify_changed_paths.py`:

- `docs/**`
- top-level `*.md`
- `.kiro/specs/**/*.md`

## What this probe must demonstrate

On the CI run for this PR:

1. `changes` publishes `docs_only=true`.
2. `unit-tests`, `integration-tests`, `pact-consumer` and `docker-build-verify`
   are all **skipped** — the last three by `needs`-propagation, not by
   conditions of their own.
3. `static-guard`, `sanitizer-canary` and `changes` still **run**. Guard jobs
   never skip.
4. `ci-required` is **green**, having confirmed that what the classifier
   declared matches what actually happened.

## What happens to this file

The probe PR is closed without merging once that evidence is captured. This
file must not reach `main`.
