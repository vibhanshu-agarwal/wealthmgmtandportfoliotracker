# Backlog: Extend diff-aware CI selection beyond docs-only changes

**Status:** Deferred pending the DAG de-serialization decision

**Priority:** 3 of 3 CI optimization items

**Owner:** Unassigned

**Implementation authorized:** No

---

## Problem

After the docs-only fast path, frontend-only and backend-only pull requests may still run jobs
unrelated to their effective change surface. Extending selective execution could reduce CI latency
and runner consumption, but the current workflow DAG and cross-module dependencies make naive path
categories unsafe.

## Dependency on item 1

The frontend-only value of this item depends on the critical-path de-serialization result:

- If item 1 is accepted, `pact-consumer -> docker-build-verify` may run independently of
  `unit-tests -> integration-tests`, creating a meaningful frontend-only optimization surface.
- If item 1 is rejected, `docker-build-verify` must still run for frontend changes and remains
  transitively dependent on `integration-tests`. Downward closure then prevents skipping the
  expensive backend chain, so frontend-only selection provides little or no material benefit.

A rejected item 1 therefore requires this item's value proposition and scope to be redesigned
before implementation. Do not assume that frontend-only savings still exist.

## Preconditions

Before designing this item:

- complete or reject the DAG de-serialization experiment;
- operate the docs-only classifier and aggregate gate successfully;
- re-audit the then-current workflow graph, required checks, branch protection, artifacts,
  commands, and transitive file dependencies;
- measure whether the remaining opportunity is material; and
- obtain independent design review and explicit owner authorization.

## Known cross-boundary dependencies to revalidate

The audit must include, but is not limited to:

- `docker-build-verify` building and serving frontend source;
- `frontend/package-lock.json` affecting `sanitizer-canary`;
- `docker-compose.yml` being parsed by backend tests;
- `config/seed-tickers.json` serving backend and frontend test consumers;
- `.github/actions/**`;
- root and per-module Gradle configuration;
- `.kiro/specs/**/tasks.md`;
- artifact flow from `pact-consumer` to `docker-build-verify`; and
- rename and deletion behavior.

This list is evidence, not an allowlist. Unknown paths must fail closed to the full suite.

## Design rules

1. Do not use workflow-level `paths:` filtering for required workflows.
2. Skip sets must be downward-closed over the actual `needs` DAG unless the DAG is deliberately
   restructured.
3. A skipped upstream job must never silently suppress a job the classifier declared necessary.
4. Only the final aggregate gate may use `always()`.
5. Preserve every existing required check until the aggregate has been introduced, required, and
   proven.
6. Keep push-to-`main` and manual dispatch on the full suite unless a separately approved design
   states otherwise.
7. Keep deployment and production authorization separate from CI classification.
8. Provide an owner-controlled kill switch that forces full execution.
9. Publish the classification, matched rules, unknown paths, and expected job set in the workflow
   summary.

## Future rollout

- Shadow classification first.
- Enable frontend-only selection in its own reviewed and soaked change, but only if item 1 leaves
  a material frontend-only optimization surface.
- Enable backend-only selection separately.
- Add mixed-change and deliberate negative tests before each widening.
- Measure latency and runner-minutes after every stage.
- Stop widening if observed dependencies contradict the model.

## Exit criteria

Selective execution must provide materially lower latency or runner usage while preserving
fail-closed behavior, required-check enforcement, and exact agreement between declared and observed
job execution.
