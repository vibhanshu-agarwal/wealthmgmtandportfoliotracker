# B1 Task 4.9 live decimal-fidelity proof — Claude kickoff

> **Operational assignment only.** This document authorizes local/static Phase A preparation
> after it is merged. It never authorizes a production request, mutation, deployment, configuration
> change, or B2 exposure. Phase B requires the exact separate owner authorization in Section 5.

**Prepared:** 2026-09-16
**Prepared by:** Codex, senior architect/reviewer
**Operator:** Claude
**Merged-prerequisite baseline:** `origin/main@42b4fb724fbda3f69a42ddd8db62857efeecda4b`
**Scope:** one fixed-E2E, complete-desired-set composition proof; mandatory restoration; and
sanitized evidence only.

## 1. Goal and non-negotiable boundaries

Produce evidence that the currently serving Azure production portfolio path accepts a quantity only
as a JSON decimal string and returns the exact stored value `"0.75000000"` as a JSON string,
preserving eight fractional digits through one authenticated write/read round trip.

Claude SHALL:

1. use an isolated, clean sibling worktree based on current `origin/main`;
2. perform only Phase A until the owner grants the Section 5 authorization;
3. use only the fixed E2E identity
   `00000000-0000-0000-0000-000000000e2e`, never the demo or an ordinary user;
4. return a sanitized evidence packet to Codex and stop; and
5. treat a local green result, documentation merge, GitHub approval, or HTTP `200` alone as
   neither production authority nor successful proof.

Claude SHALL NOT deploy, restart, scale, roll back, configure an Azure resource, alter a feature
flag or repository variable, query a database, retrieve a secret from Azure or GitHub, disclose
credentials/tokens/keys, or change tracked repository files.

The governing sources are the portfolio-composition contract Requirements 4.1–4.7 and design D6,
Task 4.9, the Asset Picker composition Requirement 8 / Tasks 2.6, 2.7, and 10.2, and
`docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`.

## 2. Required production assertions

The proof may be `GO` only when all of these are true:

1. an authenticated portfolio read selects exactly one fixed-E2E portfolio;
2. the sole proof request sends the selected quantity as the JSON string `"0.75000000"`, never
   a JSON number;
3. the one `PUT /api/portfolio/holdings` returns `200`, advances the version by exactly one,
   and returns that selected quantity as that exact JSON string;
4. a fresh authenticated read returns the same selected quantity as that exact JSON string and no
   quantity in exponent form; and
5. mandatory restoration is proved by the merged E2E complete wire-tuple verifier, not merely by
   an internal seed `200`.

Do not use a JSON parser that has already coerced number tokens as the token-type oracle. Raw
bodies may exist only in a short-lived protected local file while token type/value is evaluated;
delete them before returning. Evidence records only assertions, statuses, versions, revisions,
digests, and counts—never payloads or secrets.

## 3. Baseline, drift guard, and Phase A

Before any activity:

1. run `git fetch origin`, record `git rev-parse origin/main`, require this kickoff and
   `frontend/tests/e2e/helpers/e2e-golden-state.ts` to be present, and require a clean worktree;
2. record `git worktree list --porcelain`; do not alter or remove a worktree;
3. verify the serving contract classes remain present:
   `StrictDecimalStringDeserializer`, `ToPlainStringSerializer`,
   `PortfolioResponse`, and `CompositionController`;
4. verify `PortfolioResponse.HoldingResponse.quantity` uses the plain-string serializer and
   `CompositionHoldingsRequest.HoldingIntent.quantity` uses the strict string deserializer; and
5. compare current `origin/main` with the recorded baseline for `portfolio-service/`,
   `api-gateway/`, `common-dto/`, `frontend/src/types/`, `frontend/src/lib/api/`,
   `frontend/tests/e2e/`, `config/seed-tickers.json`,
   `scripts/derive_demo_golden_state.py`, `.github/workflows/deploy-azure.yml`, and
   `infrastructure/terraform/azure/`.

Any risk-path drift, missing/reworked contract, non-clean assigned worktree, ambiguous serving
revision, or unavailable verifier is a hard stop for Codex review.

Phase A is repository-only. It permits `git fetch origin` solely to establish the code baseline;
it permits no production-control-plane command, login, production request, or tracked-file edit.

Run and record:

```powershell
.\gradlew.bat :portfolio-service:test --tests '*StrictDecimalFidelityTest' --no-daemon
.\gradlew.bat :portfolio-service:integrationTest --tests '*DecimalFidelityIT' --no-daemon
```

Inspect, without running remotely, `frontend/tests/e2e/asset-picker.spec.ts`,
`frontend/tests/e2e/helpers/portfolio-seed-version.ts`, and
`frontend/tests/e2e/helpers/e2e-golden-state.ts`. Confirm complete-desired-set writes,
fixed-E2E identity selection, a fresh version read before each cleanup seed, terminal cleanup
`409` handling, and the exact final restoration verifier.

Prepare but do not execute a full desired-set request. It changes one existing active holding whose
current quantity is not `"0.75000000"`; every unselected `assetTicker`/quantity pair is
preserved exactly. Never add a position or issue a no-op.

Return the readiness packet in Section 5. Do not create a branch, commit, push, PR, or evidence
file.

## 4. E2E Golden-State restoration oracle

The required verifier is the merged
`assertE2eGoldenStateRestored` from
`frontend/tests/e2e/helpers/e2e-golden-state.ts`.

It performs a no-cache authenticated `GET /api/portfolio`, selects exactly one fixed-E2E
portfolio, and asserts the complete active-catalog wire tuple set:
`{assetTicker, quantity}`. It rejects missing, extra, duplicate, or altered tuples and rejects
numeric, exponent-form, or non-scale-8 quantity representations.

The expected set comes from the unchanged Task 4.4a fixed-demo oracle through
`deriveExpectedE2eGoldenSet()`. This is intentional and source-backed: wire quantity is
`floorMod(ticker.hashCode(), 50) + 1`, so it is independent of user identity. The fixed-E2E
identity selects the portfolio being verified; it does not alter expected wire holdings. Cost basis
is identity-specific but off-wire, and is not claimed by this HTTP-only proof.

## 5. Mandatory readiness packet and authorization question

Return one compact packet headed `READY_FOR_OWNER_AUTHORIZATION` containing:

- current `origin/main` SHA and risk-path comparison;
- focused test commands, results, and counts;
- fixed-E2E identity confirmation;
- the planned API sequence: authenticated reads, at most one composition `PUT`, the
  version-bearing seed restore, and verifier-controlled final readback;
- local variable *names only*: `E2E_TEST_USER_EMAIL`, `E2E_TEST_USER_PASSWORD`, and
  `INTERNAL_API_KEY` (or the approved local alias);
- proposed public gateway and serving revision/digest, marked
  `TO_BE_READ_AFTER_AUTHORIZATION`; and
- this exact question:

> Authorize Claude to perform one bounded B1 Task 4.9 Azure production proof using only the fixed
> E2E account: authenticated reads, one complete-set `PUT /api/portfolio/holdings` changing one
> existing active holding to the JSON string `"0.75000000"`, and the version-bearing internal seed
> restore plus verifier-controlled final readback required to restore that E2E portfolio. No
> deployment, feature-flag change, secret retrieval, demo-account activity, or other production
> mutation is authorized.

Only an explicit affirmative answer to that question authorizes Phase B. A partial approval
authorizes only the named subset and cannot imply a write or restore.

## 6. Phase B — only after explicit owner authorization

### B1. Revalidate target and credentials

Immediately before the first remote call, re-run the drift guard. Read only the public gateway,
active portfolio-service revision, digest-qualified image, traffic allocation, and health. Require
one healthy revision receiving 100% traffic. Confirm required local variables are non-blank without
printing values, lengths, prefixes, hashes, or encoded forms. Authenticate as the fixed E2E account
and require that login subject; otherwise return `NON_GO` before a write.

### B2. One controlled decimal round trip

1. Read and identity-select exactly one E2E portfolio with a non-negative integer version and
   non-empty holdings.
2. Select an already-held active ticker whose quantity is not `"0.75000000"`; construct the
   complete desired set from that fresh read, replacing only that quantity.
3. Issue one `PUT /api/portfolio/holdings` with the freshly observed version. Do not retry a
   proof-write `409`, re-read a newer version for a second proof write, or change identity.
4. Require `200`, exactly one version advance, selected response token type `string` and exact
   value `"0.75000000"`, and returned desired-set equality.
5. Make a fresh authenticated read and require the same selected token/value and no exponent-form
   quantity.

### B3. Mandatory finally-path restoration

Immediately after a successful proof mutation, the probe MUST enter a `finally`-equivalent
cleanup path. A later assertion failure cannot skip cleanup.

1. Before each restoration attempt, make a new identity-checked `GET /api/portfolio` and use its
   current version; never reuse the proof-write version.
2. Call `POST /api/internal/portfolio/seed` with only
   `{ "expectedVersion": <fresh version> }` and the internal API key. It must target fixed E2E,
   never the demo account.
3. On seed `200`, invoke `assertE2eGoldenStateRestored` as the required final authenticated,
   no-cache readback. It must complete without exception. Record only the exact-set
   equality/count assertions and verifier result—not the payload.
4. On cleanup `409`, retry restoration at most two more times, each preceded by a new
   identity-checked read. If any cleanup `409` is observed but a later seed and verifier pass,
   return `NON_GO_RESTORED` with a nonzero cleanup-conflict count—never `GO`. If restoration
   cannot subsequently be verified, return `NON_GO_RESTORATION_UNPROVEN`. On any other seed
   status, stop further writes and preserve only sanitized failure data for owner direction.

If failure is conclusively before the composition write, do not restore. If failure occurs after a
successful write and restoration/verifier succeeds, return `NON_GO_RESTORED`. If restoration
cannot be verified, return `NON_GO_RESTORATION_UNPROVEN`; never claim the E2E state is clean.

## 7. Return packet and stop condition

Return `B1_4_9_LIVE_DECIMAL_FIDELITY_RESULT` to Codex with:

- `GO`, `NON_GO`, `NON_GO_RESTORED`, or `NON_GO_RESTORATION_UNPROVEN`;
- timestamp, exact main SHA, gateway/service revisions, image digests, traffic and health;
- local test and static-contract results;
- identity result; initial/result/final-restored versions; selected ticker; and holding counts;
- request/composition/readback/seed/verifier status assertions;
- separate string-token/value and no-exponent assertions; and
- E2E Golden-State verifier equality/count result plus cleanup-conflict count.

Do not return raw HTTP bodies, credentials, JWTs, keys, email/password values, or replayable
requests. Stop after the packet. Codex decides whether the evidence satisfies B2 Task 10.2 item 2
and whether any separate owner-governed exposure decision may be prepared.
