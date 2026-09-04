# B1 R-C candidate preparation — consolidated return packet

> ## OWNER APPROVAL CALLOUT — one decision is requested
>
> **Requested:** authorization to commit and publish the explicitly enumerated B1 R-C local
> preparation package on branch `claude/b1-r-c-candidate-preparation` and open its implementation
> pull request. The scope is **16 new files and 5 modified tracked files — 21 files total**, every
> one enumerated in §1, including the master-plan / B1-ledger reconciliation. The PR will carry
> exactly one `Master-plan impact: updated — B1` declaration.
>
> **If you approve:** the branch is pushed and a PR opened. Nothing else changes.
> **If you decline or defer:** the reviewed work stays local and complete; nothing is lost.
>
> **NOT requested, and NOT authorized by this packet:** merging that PR; the Task 7.3
> release-candidate build; ACR login, push, or the `--registry-digest` smoke run; deployment or
> workflow dispatch; any live-database or privilege operation (including R3's reachability check);
> authoring any envelope attestation, exception or disposition for the real repository; and any
> ledger/completion-box tick. Each of those needs its own explicit decision.
>
> **Readiness is unchanged: R-C remains NO-GO.** Two tooling checkpoints are accepted; that is not
> candidate readiness. See §5 and §8.

Date: 2026-09-04. Worktree `D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-claude`,
branch `claude/b1-r-c-candidate-preparation`, HEAD `ebb96f3a6a22046ff5f3d449efcb146990b57ec9`,
pinned B1-base `95fcb68dc7a47f99465354ec6d7b84137851389d`.

**Review status.** Codex accepted both tooling checkpoints on 2026-09-04: the **source-governance
guard** (round 11) and the **local HTTP smoke harness** (round 14). A round-15 review of this packet
found no implementation defect and requested no new application-test run; its three documentation
corrections — exact scope, run provenance, and an evidence index — are applied here. The
round-by-round technical record is
[the post-consolidation handoff](2026-09-03-b1-rc-taskc-post-consolidation-handoff.md).

---

## 1. Publication scope — every file, enumerated

Nothing is committed or pushed. The proposed package is **21 files**: 16 new, 5 modified tracked.
Hashes below are of the working-tree bytes at the time of writing; the companion inventory
`.candidate-artifacts/publication-package-inventory.json` (git-ignored, not part of the package) is
regenerated last and carries the final hash of every file **including this packet**, which cannot
state its own.

**New files (16).**

| # | Path | SHA-256 |
|---:|---|---|
| 1 | `scripts/b1_candidate_evidence.py` | `27006b2c0a7b803fedef07bab34d7cc9d6c36d353459e9ca544a1009be78feb1` |
| 2 | `scripts/verify_b1_candidate_image.py` | `915c46afc33308a0955c55ef9a09bb11c21536276096ff89bb172cc981fb60f4` |
| 3 | `scripts/check_b1_candidate_source.py` | `dde3d2158f75e477a1f4343d794a2b8046dce179fef65dee10581bee69cc9fb6` |
| 4 | `scripts/smoke_b1_candidate_image.py` | `0fe100cbb37d60738fa319e98740c7cb09f0da90969efd14f558281a87b43af9` |
| 5 | `scripts/b1-candidate-policy.json` | `78bf8596d90761791028549db54db48db0865f103f07dd8fb84ebde1fb0f29c0` |
| 6 | `scripts/tests/test_b1_candidate_evidence.py` | `b28a605dc9ceb994e2ef6eca953b0c5a6dbcc4687d2242ab6df1a8ac3f55fb51` |
| 7 | `scripts/tests/test_verify_b1_candidate_image.py` | `871743faebb1dd2a3c1965c31ea0980ed44915c67aab2f3d5e408c0dd57f3568` |
| 8 | `scripts/tests/test_check_b1_candidate_source.py` | `a14e4702abc38a666b0011c9a7bbc4062e3d7ee8de59dea343ba8d45e2219ae0` |
| 9 | `scripts/tests/test_smoke_b1_candidate_image.py` | `f4024662562231f2138e598ba31fb476c168682bad8c3feb0c0c55a59787120d` |
| 10 | `portfolio-service/Dockerfile.candidate` | `fa296025a4cb21d08e3f931c8cb0dc7b577fe8043f0aa204036d5d3fc8689554` |
| 11 | `docs/runbooks/B1_R_C_CANDIDATE_VERIFICATION.md` | `6a67b9e4dcc4a34f1281443ffdf55bc11a1ac2243f058c25ea362a07a87dbedb` |
| 12 | `docs/superpowers/plans/2026-09-03-b1-rc-candidate-session-handoff.md` | `7f31f4c77c7f72f0278ce5f5bbdf175bb770c3611920ace3d970d1e0febd5b4d` |
| 13 | `docs/superpowers/plans/2026-09-03-b1-rc-candidate-checkpoint-1-task-a-handoff.md` | `581b41588ed453893e8ff8b6743ee0cf91479746ccc6f1c4067feacd8a40d9b4` |
| 14 | `docs/superpowers/plans/2026-09-03-b1-rc-taskc-v3-consolidation-handoff.md` | `2f3fe426ab2c423139c1a393a30bef1140d91c1acd6cd4f8f46dc8ed8b26b4ed` |
| 15 | `docs/superpowers/plans/2026-09-03-b1-rc-taskc-post-consolidation-handoff.md` | `925942fc3edab463bf6fa5ae3d9fc988fd0df0a9a48a9dc22f02a146d0a43293` |
| 16 | `docs/superpowers/plans/2026-09-04-b1-rc-candidate-preparation-return-packet.md` | this file — see the companion inventory |

**Modified tracked files (5).** All are part of the implementation and are inside the approval
request, not merely mentioned in prose.

| # | Path | SHA-256 | Change |
|---:|---|---|---|
| 17 | `portfolio-service/build.gradle` | `beadd1ef59dff3f8f0523d9bdcd601c065eef0e001911d95eaa32577ad24b1e7` | the `candidateVerification` / `candidateManifestValidation` / `prepareCandidateArtifact` graph (+39 lines) |
| 18 | `.gitignore` | `3aed25fe02a06029dea3294ffa6a8c92da69ae0b7fd0329ef4b4dacf11f4d841` | ignore `.candidate-artifacts/` (+3 lines) |
| 19 | `.dockerignore` | `9e26c1636bbc867a05168e59002462b0d0fde0d457d788bd7df82d982f5a2e11` | comment only — records that the copy-only recipe is unaffected by the `**/build/` exclusion |
| 20 | `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md` | `de76c9603b14e17c43a6d91b138e8079e2addbe88e08f0e0245069b47d82a970` | governed status reconciliation, from Codex's patch (§7) |
| 21 | `.kiro/specs/portfolio-composition-contract/tasks.md` | `5c4b7f5b72208b5d141d76ef8c7eca1e5c393f1e92016d2d8698b1f7fe8d77bf` | governed status + R2 carrier table, from Codex's patch (§7) |

`git status --porcelain` shows exactly these 21 entries and nothing else. Evidence bundles under
`.candidate-artifacts/` are git-ignored by entry 18 and are **not** part of the package; they are
indexed in §4 as evidence only.

**Scoped commits: none yet.** The kickoff asks for the local diff before publication, so the work is
presented uncommitted. On approval it becomes one scoped commit per area — build/packaging, evidence
tooling, guard, smoke harness, runbook/docs — plus the governed reconciliation.

Out-of-scope areas were not touched: application Java, production configuration, applied migrations,
database privileges, frontend, gateway, presence/Redis behaviour, CI and deployment workflows.

## 2. R1–R5 resolution

| Finding | Outcome | Evidence |
|---|---|---|
| **R1** — no executable carrier for the candidate chain | **Resolved for the local graph, staging and packaging implementation.** `candidateVerification` aggregates `test` + `integrationTest` with `bootJar` ordered after both (`mustRunAfter`, so ordinary `bootJar` use is unaffected); `candidateManifestValidation` gates staging; `prepareCandidateArtifact` stages the verified archive. **The release-artifact and registry joins remain pending** — the carrier exists, but it has never been run in release mode. | `portfolio-service/build.gradle`; runbook §2; §4 |
| **R2** — two floor patterns match nothing in the tree | **Resolved and reconciled.** `*AssetDiscoveryContractTest` → `*AssetCatalogControllerTest`; `*PortfolioVersionReadTest` → **both** `*PortfolioControllerTest` and `*PortfolioServiceVersionMappingTest` (no single class carries both the controller and the service-mapping evidence). 10 conceptual suites → 11 required entries. Codex approved the requirement-text correction on 2026-09-04 and supplied the governed patch, now applied (§7). | `scripts/b1-candidate-policy.json:candidate_floor`; `tasks.md` floor table; per-class counts in §3 |
| **R3** — persistent repair SQL needs a disposition | **Deliberately UNRESOLVED and blocking.** `V17…:58-156 repair_migrate_holdings(text,text,text)` mutates holdings with no version CAS; V18/V19 invoke it historically; no `DROP FUNCTION`/`REVOKE` exists anywhere in the tracked migrations. The guard reports it as a blocking `unresolved` finding and the V18/V19 call sites surface as `sql:CALL` subjects. **A source-only review cannot close this**; it needs an owner-authorized live/operational check. No migration was amended and no schema or privilege change was made. | guard output §5; policy `unresolved[]` |
| **R4** — symbolic GC.5 base and no comparison policy | **Resolved.** Base pinned to `95fcb68d` (direct parent of the first B1 code commit `62237deb`), ancestry re-verified, cut-B3 explicitly rejected as a base. The policy states why interleaved non-B1 work in the interval is *evidence requiring disposition*, not an automatic exemption. | `scripts/b1-candidate-policy.json:b1_base_commit`; guard `target.base_sha` |
| **R5** — packaged-image smoke is new work | **Resolved for the local portion; the registry portion is owner-gated.** `scripts/smoke_b1_candidate_image.py` runs the image by immutable id on its shipped entrypoint against disposable PostgreSQL/Redis/Kafka and asserts the contract. The `--registry-digest` path is implemented and fixture-tested but **refuses to run without `--authorized-release-run`**; it has not been run. | §4; runbook §7 |

**Pending decisions, stated first:** (a) R3 needs an owner-authorized operational check; (b)
publication of this package (the callout above). R2's governed-document reconciliation is no longer
pending — Codex approved it and its patch is applied.

## 3. Test results and what each run actually covers

### 3.1 Tooling suites — 363 tests

This total is the aggregate of the recorded suite runs, each accepted in its own round. All four were
**also re-run together on 2026-09-04 at this final source snapshot**, each exiting 0: 39 (13.8 s),
34 (12.9 s), 217 (519.7 s) and 73 (48.0 s), including the real local-image smoke. The guard suite
requires `-X utf8` (non-ASCII SQL identifier fixtures) and builds local Docker fixtures; none of the
four performs a release-candidate build.

| Suite | Result |
|---|---|
| `test_b1_candidate_evidence.py` (Task A) | **39 passed** |
| `test_verify_b1_candidate_image.py` (Task B) | **34 passed** |
| `test_check_b1_candidate_source.py` (guard) | **217 passed**, zero skips |
| `test_smoke_b1_candidate_image.py` (Task C smoke) | **73 passed**, zero skips |
| **Total** | **363** |

**On RED evidence, precisely:** the reviewer's false-PASS probes and the regression controls derived
from them were each reproduced as a measured failure against the then-current analyzer or harness
before the fix, and now assert FAIL — that is what rounds 6–14 consist of, and those reproductions
are preserved in the reviewer's round artifacts (§4). That is a narrower claim than "every one of the
363 tests has a preserved RED log", which this packet does not assert.

### 3.2 Application suites — 760 tests, from the preserved 2026-09-03 graph

These totals come from the **preserved `LOCAL_DEV` graph run of 2026-09-03**, not from a fresh
verification of the final package:

- marker epoch `1788422649.3166454` = **2026-09-03T08:04:09.316645Z**, HEAD `ebb96f3a`, mode
  `LOCAL_DEV`, `clean: false` (the tree carried this uncommitted tooling), one invocation with
  `--rerun-tasks --no-build-cache`.
- `test` 53 classes / 552 tests; `integrationTest` 36 classes / 208 tests; **760 tests, 0 skipped,
  0 failures, 0 errors**; `graph_verification_status: PASS`, `problems: []`.

Later tooling, test and documentation edits postdate that run. It remains valid historical
development evidence for the application code, and it is **not** verification of the final
uncommitted package or of any proposed committed candidate. Re-running it is a separate, unrequested
action; the round-15 review explicitly did not ask for one.

What *was* re-checked on 2026-09-04: all **89** JUnit XML report files named in the manifest are
still present on disk and every one still hash-matches its recorded `report_sha256` — 89 present and
matching, 0 mismatched, 0 missing. The 760 count therefore remains traceable to the report files
that produced it.

**Per-floor non-skipped counts** (all 11 required entries present and non-empty):

| Task | Class | Non-skipped |
|---|---|---:|
| test | `LegacyWriterRetirementTest` | 2 |
| test | `AssetCatalogControllerTest` | 6 |
| test | `CompositionControllerTest` | 30 |
| test | `HoldingReplacementServiceTest` | 20 |
| test | `CompositionErrorContractTest` | 17 |
| test | `PortfolioControllerTest` | 6 |
| test | `PortfolioServiceVersionMappingTest` | 2 |
| integrationTest | `ConcurrentCompositionIT` | 12 |
| integrationTest | `DecimalFidelityIT` | 2 |
| integrationTest | `PortfolioSeedServiceIT` | 12 |
| integrationTest | `V20MigrationIT` | 3 |

**Discovery reconciliation:** 69 B1-added/modified `*Test.java`/`*IT.java` files under
portfolio-service since the pinned base, **zero unreconciled**, empty helper allowlist.

## 4. Evidence index

All paths are relative to the worktree root. Everything under `.candidate-artifacts/` is git-ignored
and is therefore **evidence, not deliverable**.

| Artifact | Path | SHA-256 | Run date / mode | What it evidences |
|---|---|---|---|---|
| Run marker | `.candidate-artifacts/run-start.marker` | `657c8d8098515097238cb78879ad6f23a262867488b97d143a74dd99453f6f0f` | 2026-09-03, `LOCAL_DEV` | epoch `1788422649.3166454`, HEAD `ebb96f3a`, `clean: false`, content digest |
| Task A graph evidence | `.candidate-artifacts/evidence.json` | `be00a2e1a82df52d725a51053dfa6244b297cb6349ca243a87fe8b3c5e2bb582` | 2026-09-03, `LOCAL_DEV` | 89-class manifest with per-class counts and per-report SHA-256; per-task totals; 69 discovery files; staging record; `PASS`, `problems: []` |
| Staged artifact | `.candidate-artifacts/portfolio-service.jar` | `4ee27c78c55da5c9edb34bbf926c3595249d82a42b9b95c369f125605c724dc6` | 2026-09-03 | the verified `bootJar` output, staged by `prepareCandidateArtifact` |
| Image build record | `.candidate-artifacts/image-build-record.json` | `50100bd4c446890e5dab84d1c887704e518c5e7def090337c0569bb863e9ea50` | 2026-09-03 15:02 | image **A** `sha256:983cf5d3…`, recipe `fa296025…`, base digest, `linux/amd64` |
| Task B image evidence | `.candidate-artifacts/image-evidence.json` | `624e5df5589a3fa82168803ed524821b9de59c7822eaab8ef279b5d5c4de58bc` | 2026-09-03 15:02, `LOCAL_PREPARATION` | image **A**'s extracted `/app.jar` equals the staged JAR; registry fields `null` |
| **Current** source-governance output | `.candidate-artifacts/source-governance-gc5-contract-3.json` | `5c2cbf3d2420c06941c2f65cdd564e171bff83704727238283944adf2910e369` | 2026-09-04, `LOCAL_PREPARATION` | `gc5-contract/3` / `java-conservative/3`, `BLOCKED`, `candidate_ready: false`, **504 findings**, four envelopes; records `analyzer_sha256` = file 3 in §1 |
| **Current** smoke bundle | `.candidate-artifacts/smoke-local-preparation-20260904.json` | `224777edb2a4029d3754b358d5415d003394fa9dc310fcd786add73a6a0bb895` | 2026-09-04, `LOCAL_PREPARATION` | image **B** `sha256:0337502e…`, run `bfa8ffa4e6da`, 29.8 s, five assertions PASS, `cleanup_verified: true`, `retained_resources: []`, registry fields `null` |
| Package inventory | `.candidate-artifacts/publication-package-inventory.json` | regenerated last | 2026-09-04 | final hash of all 21 package files, including this packet |

**Two stated limitations, rather than implied evidence:**

1. **The full Gradle console log of the 2026-09-03 graph run was not preserved.** It is not attached
   and this packet does not imply otherwise. The durable record of that run is the marker, the
   `evidence.json` manifest, and the 89 JUnit XML report files, which were re-verified against the
   manifest on 2026-09-04 (§3.2).
2. **`.candidate-artifacts/gc5-writer-evidence.json`** (`60e2df99a93fa126a6168c12941c225e410c439119448ce833c4ac5c87d2fe74`,
   2026-09-03) is a **legacy-format** bundle — its keys are `base_sha`/`head`/`path_guard`/
   `content_guard`/`writer_inventory`/`overall_status`, not the current contract's `target`/
   `findings`/`source_governance_status`. It **must not** stand in for the 504-finding inventory. It
   is preserved unchanged, and the current output was written to a **new** file rather than over it.

**Two development images, one JAR.** These are separate runs and are not one identity:

| | Image A | Image B |
|---|---|---|
| Id | `sha256:983cf5d322fde06ceef79757dca698bf7dae8e4ed42cfa06505f426b8d696ef6` | `sha256:0337502e94c0d66ada8741e8567ee8189ba23b6fbd615c511a831cd8b1924ff2` |
| Tag | `wealth-portfolio-service:candidate-local-dev` | `wealth-portfolio-service:candidate-local-dev-rebuilt-20260903` |
| Built | 2026-09-03, recorded 15:02 local | 2026-09-03T08:49:47Z (rebuilt for the round-5 evidence-binding proof) |
| In the daemon now | **no** — pruned; `docker image inspect` reports "No such image" | yes |
| Recorded by | `image-build-record.json`, `image-evidence.json` | `smoke-local-preparation-20260904.json` |

Both came from the same recipe `Dockerfile.candidate` (`fa296025…`) on the same immutable base
`mcr.microsoft.com/openjdk/jdk@sha256:e59e5d626eb216745bb1bb69a84adba78d7724a55e0132995dccb3483b10fac7`,
platform `linux/amd64`. The **JAR equality is recorded, not assumed**: image A's `image-evidence.json`
records `extracted_jar_sha256` = `4ee27c78…` = the staged JAR, and the 2026-09-04 smoke run was given
`--expect-jar-sha256 4ee27c78…` and recorded image B's extracted `/app.jar` as the same `4ee27c78…`
with `unverified_joins: null`. Same recipe, same base, same JAR; two image ids.

**Contract smoke detail** (image B, 2026-09-04, `LOCAL_PREPARATION`): shipped entrypoint
`["java","-jar","/app.jar"]`, prod port 8080. A1 startup; A2 `GET /api/assets` → 200 with a 160-asset
catalog and ETag; A3 nontrivial three-holding composition → 201, version 0 → 1; A4 replay of the
**same** `expectedVersion` → 409 `{"error":"portfolio_version_conflict", …,"currentVersion":1}`;
A4-db complete parent and holding rows unchanged and equal to what A3 reported writing. No leftover
containers, networks or working directories.

**Reviewer-held reproductions** (outside this repository, under
`C:/Users/pc/.codex/visualizations/2026/09/03/01a065fd-…/`): the round 6–14 probe scripts and their
result JSONs, plus the round-11 real-repository before/after/delta runs (`taskc-fix-real-before.json`,
`-after.json`, `-delta.json`). They are Codex's artifacts, named here for traceability; they are not
part of the package and are not copied into it.

**Registry and serving evidence is absent by design.** `registry_manifest_digest`,
`registry_manifest_platform` and every serving field are `null`, and `candidate_ready` is hard-coded
`false` in all three producers.

## 5. Source-governance output

Guard run 2026-09-04 at the cut: contract `gc5-contract/3`, analyzer `java-conservative/3`,
normalizer `java-lexical/2`, mode `LOCAL_PREPARATION`, base `95fcb68d` → cut `ebb96f3a`, 451 changed
paths over 1674 tracked files. **`source_governance_status: BLOCKED`, `candidate_ready: false`,
504 findings.** Full output at `.candidate-artifacts/source-governance-gc5-contract-3.json` (§4).

This is the correct and intended state — the findings are detected matters requiring human review,
not proven violations, and **no disposition, exception or attestation has been authored by this
tooling**.

| Obligation | Findings |
|---|---|
| content-governance | 139 CONFIRMED_MATCH |
| path-governance | 84 CONFIRMED_MATCH, 131 UNREVIEWED |
| writer-inventory | 26 UNRESOLVED, 69 UNREVIEWED, 1 UNSUPPORTED |
| persistence-usage | 33 UNSUPPORTED |
| per-holding-state | 9 UNREVIEWED |
| writer-coverage | 7 UNSUPPORTED |
| envelope | 4 UNREVIEWED (one per governed deployable) |
| unresolved | 1 UNRESOLVED — **R3** |
| **Total** | **504** — 223 CONFIRMED_MATCH, 213 UNREVIEWED, 41 UNSUPPORTED, 27 UNRESOLVED |

Deployables are derived from the tree, not the policy: api-gateway (69 members), insight-service (86),
market-data-service (77), portfolio-service (145). Each requires a reviewed envelope record and has
none, which is why each blocks.

**Writer inventory** is regenerated on every run from the whole tracked tree: Java operations
(including `saveAndFlush`, cascade collection mutations and managed-entity setters), SQL migration
subjects, executable `DO` blocks, script DML, and FK cascade targets. Its dispositions in the policy
are the reviewed seed, and each is re-validated against the code at the cut.

**R3 remains unresolved and blocking**, as R3 requires.

## 6. Runbook and the future release packet

[`docs/runbooks/B1_R_C_CANDIDATE_VERIFICATION.md`](../../runbooks/B1_R_C_CANDIDATE_VERIFICATION.md)
carries the executable local procedure (steps 1–7: marker, graph, evidence, guard, unit tests, image,
smoke) with exact commands, and a separately gated **Release procedure** that documents the
one-build/push/pull/attest sequence without making it executable. It states plainly that owner
authorization is required **before the release-candidate build itself**, not merely before the push,
and it keeps every registry and serving field marked incomplete.

## 7. Governed-document reconciliation — applied

Codex approved the R2 carrier correction on 2026-09-04 and supplied
`b1-rc-governed-status-reconciliation.patch` for the two governed documents. Under the coordination
permission recorded for this worktree, I applied it here rather than leave the package incomplete.
Verification of that application:

- Preimages matched Codex's recorded values exactly — master plan `e45003e6…`, `tasks.md`
  `4fda5797…`.
- `git apply --check` passed; `git apply` then succeeded.
- Both files are pure CRLF in this worktree and remained pure CRLF afterwards (666 and 1345 CRLF,
  zero bare LF), so no mixed line endings were introduced.
- All **91** checkbox lines in `tasks.md` are byte-identical before and after. **No completion box
  changed**, and none is proposed.

What the patch records: both tooling acceptances; the historical boundary of the 2026-09-03 graph;
R-C **NO-GO** with 504 findings, R3 unresolved and release/registry/serving evidence incomplete; that
7.3–7.11, GC.5, AM.1/AM.2 and Writer_Convergence take no completion-box change; that the kickoff's
docs-only approval granted no implementation or release authority; and the R2 floor-table replacement
— `*AssetCatalogControllerTest` for discovery, and both `*PortfolioControllerTest` and
`*PortfolioServiceVersionMappingTest` for version read, 11 required entries over 10 conceptual
suites, with `scripts/b1-candidate-policy.json` implementing rather than superseding the governed
requirement.

These are files 20 and 21 of the publication scope in §1.

## 8. What remains open

1. **R3** — owner-authorized operational/live check; blocks 7.6/G6.
2. **Real-repository dispositions and envelope records** — an owner/reviewer act, never tooling's.
3. **Task 7.3 release-candidate build**, ACR push, registry-digest resolution, and the
   `--registry-digest` smoke run — each separately authorized; 7.5a is incomplete until that run.
4. **7.7–7.11**, AM.1/AM.2 and Writer_Convergence — unchanged.
5. **CI wiring** — deliberately out of this bundle. Recorded for a later proposal: the guard needs
   `actions/checkout` with `fetch-depth: 0`; the current `static-guard` job checks out shallow, where
   the pinned base is absent and the guard correctly fails closed rather than substituting a base.
6. **Publication of this package** — the single decision in the callout at the top.
