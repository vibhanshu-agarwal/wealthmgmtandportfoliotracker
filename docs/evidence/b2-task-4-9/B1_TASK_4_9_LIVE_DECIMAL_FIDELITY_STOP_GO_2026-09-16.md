# B1 Task 4.9 — Live decimal-fidelity proof

**Decision:** **GO — B1 Task 4.9 is complete.** This is the owner-operated live proof required by
B2 Wave 10.2 condition 2. It does not authorize B2 production exposure, a flag change, a build,
a deployment, or a Production E2E run.

## Authority and scope

The owner explicitly authorized Phase B and personally executed the unchanged, untracked operator
harness. The harness source attested by the owner has Git blob
`4b8d8361fed0ba37a4244e62805ee6706c2a8567`; its hash was independently rechecked after the run.
The proof used only the fixed E2E portfolio
`00000000-0000-0000-0000-000000000e2e`. No secret, password, token, Authorization value, request
body, or `.env.secrets` content is recorded here.

The evidence baseline was `main@cca7f0d926c0b6d13a249f5fccf537369983aa96`. The immediately
preceding drift guard was clean: `HEAD == origin/main`, with no relevant protected-path drift from
the merged golden-state verifier baseline `42b4fb724fbda3f69a42ddd8db62857efeecda4b`.

## Serving identity at the proof

| Service | Revision | Digest | Traffic and health |
|---|---|---|---|
| API gateway | `api-gateway--0000081` | `sha256:090ad3ba4b7ada20bca71781d4a1ce3ac81029b8a23a8a1d180dce2cff540d09` | Sole revision, 100%, Healthy |
| Portfolio service | `portfolio-service--0000096` | `sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126` | Sole revision, 100%, Healthy |

## Sanitized owner-run result

The owner-executed harness completed at `2026-09-16T17:25:46.240Z` with all of the following
sanitized observations:

| Check | Result |
|---|---|
| Fixed-E2E login and returned identity | HTTP `200`; identity matched the fixed E2E UUID |
| Initial portfolio version | `0` |
| Controlled composition write | One `PUT`, HTTP `200`, advancing to version `1` |
| Selected holding | `AAPL`; `159` holdings before and after the controlled write |
| Live decimal response and fresh readback | JSON string token; exact value `"0.75000000"`; no exponent representation |
| Composition/readback assertions | Desired-state equality and identity-checked HTTP `200` readback passed |
| Mandatory cleanup | Versioned seed restore HTTP `200`; no transport error; cleanup-conflict count `0` |
| Post-cleanup verifier | `assertE2eGoldenStateRestored` passed exact `159` wire tuples |
| Final state | Restored at version `2`; restoration confirmed |

The controlled mutation was enclosed in the harness's mandatory cleanup path. There was exactly
one proof `PUT`; the versioned seed restoration and independently-derived fixed-E2E wire-tuple
readback succeeded. No cleanup `409` occurred, so the result is eligible for `GO` rather than
`NON_GO_RESTORED`.

## Contract evidence and limitation

Phase-A local contract evidence on the same baseline remained green:

- `StrictDecimalFidelityTest`: 3/3 — rejects a JSON number, rejects exponent notation, and
  round-trips `"0.75000000"`.
- `DecimalFidelityIT`: 2/2 — PostgreSQL `NUMERIC(19,8)` through `ToPlainStringSerializer` is
  byte-identical as a JSON string.
- Production annotations are wired on `PortfolioResponse.java:44` and
  `CompositionHoldingsRequest.java:32`.

The live run proves that the serving path accepted the specified JSON string and returned the same
exact string on response and fresh readback. It did **not** submit separate live negative requests
using a JSON number or exponent notation; those rejection claims remain supported by the
source/local contract tests above, not by a live negative experiment.

## Gate effect

Together with the independently ACCEPTed [Task 2.7 historical containment audit](../b2-task-2-7/historical-containment-audit-20260910.md), this evidence satisfies **B2 Wave 10.2 condition 2**.
Task 2.6 numeric compatibility remains mandatory and non-blocking. The Task 2.7 audit's historical
limits also remain unchanged: it does not establish that a prior user-visible incompatible artifact
was impossible.

The separate Wave 10.2 gate remains closed. In particular, this proof neither completes Wave 9
Production E2E nor substitutes for the decision-time serving/configuration comparison, owner
exposure approval, flag-bearing frontend deployment, or post-deploy browser verification.
