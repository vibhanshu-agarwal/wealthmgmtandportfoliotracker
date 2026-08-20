# Cursor Kickoff — Wave P P-B (prebuilt-digest deploy path)

**Date:** 2026-08-18
**Prepared for:** Cursor (implementation)
**Baseline:** `origin/main` @ `500a8c5` (P-A merged)
**Predecessor:** P-A complete, STOP/GO recorded GO — see `.kiro/specs/portfolio-composition-contract/tasks.md`

---

## 1. What you are building

**P-B only.** A prebuilt-digest deploy path in `deploy-azure.yml`: an input accepting `repository@sha256:...` and a skip-build branch that updates the `portfolio-service` Container App to that exact manifest digest, without building, pushing or retagging.

Task text is now on `main`: `.kiro/specs/portfolio-composition-contract/tasks.md`, section `### P-B — digest deployment`. Tasks P-B.1 through P-B.5.

Do not start Spec A or any B1 wave. Those are separate kickoffs.

## 2. Why it exists

B1's release model requires that **evidence describes the artifact that serves**. R-C builds a candidate, proves properties about it, and must then deploy *that exact artifact*. Today `deploy-azure.yml` rebuilds independently and deploys by tag, so the serving proof would describe a fresh rebuild rather than the attested candidate. P-B closes that.

The path is deliberately narrow — `portfolio-service` only. A generic form would accept `market-data-service`, whose Container App would take the supplied digest while `market-data-refresh-job` still moved by `${github.sha}` tag, breaking the exact-artifact invariant inside one logical deployment. A half-generic mode is worse than a narrow honest one.

## 3. The split — and it is better than P-A's

P-A had to merge with its risky half unproven. **P-B does not.** Most of the dangerous surface is provable before merge.

| Task | Provable | How |
|---|---|---|
| **P-B.1** implementation | pre-merge | code |
| **P-B.2** rejection rules | **pre-merge** | unit tests on the validator + structural test that validation precedes any update step |
| **P-B.4a** each rejection fails *before any update* | **pre-merge** | same |
| **P-B.4b** default full path still works with digest absent | post-merge | the merge deploy itself |
| **P-B.3** digest path actually works | post-merge | one dispatch against a purpose-built candidate |
| **P-B.5** STOP/GO | post-merge | closes after P-B.3 |

So P-B merges with the privileged path's *rejection* behaviour already proven, leaving only the happy path pending. Exploit that — do not defer P-B.2/P-B.4a to live runs.

**The gate order inverts again.** Azure OIDC is `ref:refs/heads/main` only; a dispatch from a feature branch fails with `AADSTS700213` before reaching Azure. This is the control working correctly. Do not request a feature-branch federated credential. Record the inversion in the PR body exactly as P-A did.

## 4. Verified anchors — `deploy-azure.yml` on `500a8c5`

**P-B.1's own task text cites line 145 for `docker build` and line 161 for `az containerapp update`. Those are pre-P-A line numbers and are now stale.** Current positions:

| What | Line | Note |
|---|---|---|
| `ACR_NAME: wealthprodacr` | 53 | the only registry that may be accepted |
| `- name: Log in to ACR` | 176 | |
| `- name: Build Docker image` / `docker build` | 179 / 181 | |
| `- name: Push Docker image` / `docker push` | 188 / 190 | **separate step from build — the skip branch must skip both** |
| `- name: Update Container App` / `az containerapp update` | 192 / 194 | |
| `- name: Update market-data-refresh Job image` | 199 | `if: matrix.service == 'market-data-service'` |
| `- name: Wait for revision to reach Succeeded state` | 218 | should still run in digest mode |

Image reference today is `$ACR_NAME.azurecr.io/<service>:${{ github.sha }}`, so **repository name equals service name**. A valid digest reference for this path is therefore exactly `wealthprodacr.azurecr.io/portfolio-service@sha256:<64 hex>`.

Re-verify these before editing; any intervening merge can move them.

## 5. P-B.2 — the rejection set

Reject **before any update** when:

- the selected service is not `portfolio-service`;
- the selection is not **exactly one** service (zero and multiple both fail);
- the ACR repository does not equal the selected service;
- the reference is a tag rather than immutable `sha256:` syntax;
- the manifest does not resolve in the expected ACR;
- a foreign registry or repository is named.

All but "manifest does not resolve" are pure string/shape validation and belong in a unit-tested module mirroring `resolve_deploy_selection.py`. The manifest-resolution check needs Azure, so structure the code so the cheap checks run first and the Azure lookup is last — that ordering is itself what P-B.4a asserts.

## 6. P-B.3 — the live proof, and where the digest comes from

**Build a dedicated candidate. Do not reuse an existing image.**

The assertion "the Container App resolves to the exact requested digest" is meaningless if the requested digest is already the serving one — it cannot distinguish "deployed it" from "it was already there".

Reusing the merge-deploy image seems to avoid that, since `portfolio-service` now serves its scoped-run digest. It does not: both runs built the same commit and pushed the same tag `portfolio-service:500a8c5`, so the scoped run overwrote it. The merge-deploy manifest survives only as an **untagged** manifest subject to ACR retention. A release gate anchored to a manifest nothing references is not repeatable.

Build the candidate server-side, outside the deploying workflow:

```bash
az acr build --registry wealthprodacr --image portfolio-service:pb3-candidate --file portfolio-service/Dockerfile.azure .
```

Then read back the digest:

```bash
az acr manifest show --name wealthprodacr.azurecr.io/portfolio-service:pb3-candidate --query digest -o tsv
```

Dispatch the digest deploy with `wealthprodacr.azurecr.io/portfolio-service@<digest>`.

If local Azure CLI auth is unavailable, add a minimal build-only `workflow_dispatch` job instead — but keep it out of the deploying run, which must show no build and no push.

### Assertions

- **No build or push step executed.** See §7 — this is the trap.
- The **selected** Container App resolves to the exact requested digest.
- The scoped-mode skips from P-A.2 still hold (`deploy-frontend`, `seed`, `verify` all `skipped`).

## 7. The hazard: proving a step did *not* run

P-A's review caught this twice, and P-B.3 walks straight back into it. **A skipped step reports as success, so a green run proves nothing.**

Use what worked for P-A.2: gate the build and push steps on the deploy mode, and assert their conclusions are explicitly `skipped` — recorded and compared, never inferred from an overall green. `assert-scoped-non-interference` is the existing pattern; extend it or add a sibling rather than inventing a third mechanism.

A digest run that silently rebuilt and happened to produce a working app would pass every other assertion in P-B.3. This one is the load-bearing check.

## 8. Sequence

1. Implement P-B.1 and P-B.2. Unit-test the validator and the ordering. → **P-B.2**, **P-B.4a**
2. Open the PR. Record the inverted gate order in the body, as P-A did.
3. Merge — fires a full production deploy and re-seeds the demo, since `deploy-azure.yml` is in `deploy.yml`'s `push: paths:` filter (line 31). Pick a quiet window. → **P-B.4b**
4. Build the candidate (§6). Capture the digest.
5. Dispatch the digest deploy from `main`. → **P-B.3**
6. Follow-up PR ticking P-B.1–P-B.5 and recording the STOP/GO with run IDs, matching P-A.5's format.

**P-B.5 abort:** revert P-B only. P-A survives; the release lane stays closed until a digest path exists.

## 9. Definition of done

- All P-B checkboxes ticked in `.kiro/specs/portfolio-composition-contract/tasks.md`, with the STOP/GO recorded and run IDs inline.
- New tests wired into `ci-verification.yml`'s `deploy-workflow-contract` job alongside the three P-A suites.
- Rejection tests cover **every** bullet in §5, each asserting failure occurs before any update.
- The digest run shows build and push conclusions explicitly `skipped`.

## 10. Out of scope

Generalising the digest path beyond `portfolio-service`; any Spec A work; `GET /api/assets`; any frontend change; any Flyway migration.

## 11. Escalate rather than decide

- Any anchor in §4 that no longer matches.
- Any pressure to add a feature-branch federated credential.
- Any rejection case that cannot be proven before an Azure call.
- Any need to generalise beyond `portfolio-service` to make a test pass.
