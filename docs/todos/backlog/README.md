# Backlog status index

**Audited:** 2026-09-26 UTC against `main@d515aa5b295e1b780169358c712ae22ac71d8490`.
**Publication rule:** this audit is filed for the project freeze only when the PR carrying it
merges into `main`; an unmerged branch copy is a candidate. Push/PR and merge each require explicit
owner approval. This document grants neither. No code fix, workflow dispatch, secret access or
live operation occurred.

**Independent review:** Claude reviewed audit commit `196c9d0d` on 2026-09-26 UTC and accepted its
dispositions and evidence subject to one publication-wording fix, with two minor notes.
The follow-up addresses that wording, clarifies existing closure authority and simplifies
the E2E-audit scope note; Claude's review of `196c9d0d` does not itself review the follow-up commit.
Claude subsequently reviewed `196c9d0d..fe84a444` and confirmed that `fe84a444` clears the conditional acceptance.

**Source-review follow-up — 2026-09-26 UTC:** the E2E guide review against `main@8aa4035b`
adds three OPEN findings: [advisor authorization](portfolio-advisor-cross-user-authorization/README.md),
[public price-write authorization](public-market-price-write-authorization/README.md) and
[header-spoofing regression proof](gateway-user-header-spoofing-regression-proof/README.md).
The original 30-item audit is filed through #324 (`6f1e5700`); its independent acceptance does not
cover this later addition. Claude accepted the documentation corrections through `c88f076c`;
Claude also accepted local-remediation status at `0632dd07`; the later merge-status follow-up
requires its own review/publication under the rule above. Price-write removal at `83607f5d` is
merged through #327 (`9c733f6d`), not deployed/live-validated; its gateway test partially covers
the separate header-proof item. Neither item is closed.
No original disposition changes. Current totals are 33 directories: 8 fixed, 2 superseded, 23 open.

This is a restart inventory, not a new implementation plan. The original 30 item directories were checked
against current source, tests, Git history and accepted repository evidence. Their READMEs retain
the findings and historical investigation; no backlog item or evidence file was deleted.
The [dated TODO list](../TODOS_2026-04-07.md) is a separate inventory of inline follow-ups;
its current disposition section governs its historical bullets. Duplicate references are not
additional implementations to perform.

## Status rules and totals

| Status | Count | Meaning |
|---|---:|---|
| CLOSED — fixed/completed | 8 | The original work has delivered source or accepted historical completion evidence; closure is limited to that item's scope/cut. |
| CLOSED — superseded | 2 | The architecture/premise was retired. Do not implement the old fix plan. |
| OPEN | 23 | The original 20 residual items plus two source-confirmed authorization defects and one security regression-proof gap. Includes deferred, parked, mitigated and partially delivered items. |
| **Total directories** | **33** | Original audit of 30 plus three source-review additions. ROUND2/ROUND3 are historical documents inside one retired item, not additional items. |

Non-blocking, accepted-for-demo, not observed, and unverified are **not** synonyms for fixed.
Missing acceptance evidence leaves an item open with that precise residual; it does not mean
the delivered mechanism should be implemented again. Current cloud state was not queried.

## Closed — fixed/completed

| Item | Closure basis and retained boundary |
|---|---|
| [API Gateway custom-domain binding](api-gateway-custom-domain-binding/README.md) | Recovery PR #194, accepted three-caller evidence #197 and owner G5 close-out. This audit records the previously reserved backlog disposition; no new reachability proof. |
| [Docs-only CI fast path](ci-docs-only-fast-path/README.md) | PRs #198/#199, current fail-closed classifier/result matrix and 22 current classifier/wiring tests. Latest six-job probe limitation remains; no fresh branch-protection read. |
| [GC.5 governance review queue](gc5-governance-review-post-asset-picker-e2e/README.md) | Published Task 6 policy/checkpoint: zero findings and unverified coverage for bound release cut `8f1e8a36`. Not a new governance PASS for current main; VERSION-root gap stays open. |
| [Responsive dashboard sidebar](responsive-dashboard-sidebar/README.md) | PR #297; current responsive icon rail, accessible labels and tooltip. Does not close page-level narrow-width overflows. |
| [Sanitizer nested-zip cleanup race](sanitizer-nested-zip-cleanup-race/README.md) | `7f421e88` is on main; recursive scan is awaited before cleanup and regression remains. Historical validation, not a newly run sanitizer suite. |
| [Total-value hydration](total-value-e2e-hydration/README.md) | `c6fd8408` repaired missing standalone assets; frontend subsequently migrated to static export. Not whole-valuation verification. |
| [Total-value skeleton E2E](total-value-skeleton-e2e/README.md) | Same fixing commit and historical incident as hydration entry. Do not reimplement the obsolete NextAuth hypotheses. |
| [Overview / Market Data page implementation](ui-polish-overview-market-data/README.md) | `71273107`, completed ledger and current components/tests. Original placeholder pages are replaced; later currency/overflow gaps remain separate. |

## Closed — superseded/no longer relevant

| Item | Why no new implementation is needed |
|---|---|
| [Better Auth PostgreSQL in CI](ci-better-auth-postgres/README.md) | Better Auth retired, named files removed, V16 drops its tables, and static-smoke does not authenticate or use a database. |
| [Better Auth E2E remediation](e2e-betterauth-remediation/README.md) | Gateway auth replaced Better Auth/BFF; includes historical ROUND2/ROUND3. Uncalled helper cleanup remains separately tracked in the dated TODO list. |

## Open — exact remaining work

| Item | Current state / remaining work |
|---|---|
| [Spec-reference guard zero coverage](asset-picker-spec-reference-guard-zero-coverage/README.md) | Reproduced exit 0 with `0/0`; fix nonzero/parser coverage signal and regressions, plus baseline-aware review inventory. |
| [B1 VERSION envelope root](b1-candidate-envelope-product-version-root/README.md) | Root/policy sets omit `VERSION`; version-only change leaves digests unchanged. Fix and independently re-attest before next version/envelope change. |
| [B5 image-equality assurance](b5-image-equality-assurance-claim/README.md) | Plan equality still cannot establish live image equality under lifecycle ignore. Reframe/annotate and audit sibling claims; old accepted outcome stands. |
| [CI DAG de-serialization](ci-dag-critical-path-deserialization/README.md) | **Mechanism merged in #306.** Recover/disposition complete experiment acceptance, timing and failing-unit runner-cost evidence; do not redo graph changes. |
| [Broader diff-aware CI selection](ci-diff-aware-job-selection/README.md) | **Deferred.** Frontend/backend selection is absent; reconcile DAG acceptance, re-audit dependencies and justify/design widening. |
| [Deployed image-tag validation](deployed-image-tags-json-validation/README.md) | Registry existence is not common four-service live equality. Correct implementation or stated contract; gateway-specific attestation is not a general repair. |
| [Demo portfolio/ticker integrity](demo-portfolio-and-ticker-integrity/README.md) | **Partially resolved.** Independent 159-asset demo seed, BTC/MM repair, catalog packaging and freshness delivered. Tata successor/allocation remains an open product/data decision. |
| [Full E2E coverage audit](e2e-coverage-audit-post-asset-picker/README.md) | **Follow-on audit.** Inventory exclusions/vacuity and complete cross-suite acceptance matrix; demo suite is evidence input, not this audit's closure. |
| [EventBridge warming](eventbridge-not-working/README.md) | **Parked AWS standby work.** Several old source prerequisites fixed; reactivation remains a cost/operations decision with fresh prerequisites, not an active defect claim. |
| [Gateway user-header spoofing regression proof](gateway-user-header-spoofing-regression-proof/README.md) | One protected market-route forwarded-header assertion merged through #327 (`9c733f6d`); duplicate headers, permit-all paths and broader mutation proof remain open. No current bypass is claimed. |
| [Kafka consumer wake/scaling](kafka-consumers-have-no-scale-rule/README.md) | No Kafka scaler; consumers explicitly scale to zero. Decide idle-liveness policy and assess retention; session warm-up is not autonomous wake. |
| [Supported market-data provider](market-data-yahoo-unofficial-api/README.md) | **Mitigated.** Yahoo cookie/crumb still used; durable provider/coverage/rate-limit and alerting work remains. Symbol repair does not remove provider risk. |
| [Mocked-chaos 429 E2E](mocked-chaos-429-batch-assertion-redesign/README.md) | Still skipped; deliver controlled-fixture exact batching/no-extra-request and graceful-degradation coverage. |
| [Narrow-width overflow](responsive-dashboard-narrow-width-overflow/README.md) | **Accepted demo debt, not fixed.** Portfolio 320/375px and Overview 320px need future measured repair; desktop scope does not close them. |
| [Portfolio advisor cross-user authorization](portfolio-advisor-cross-user-authorization/README.md) | **Source-confirmed IDOR; owner disposition pending.** Path-selected user ID is forwarded without subject comparison. Azure URL/reachability is unverified; missing source wiring is not an authorization control. |
| [Public market-price write authorization](public-market-price-write-authorization/README.md) | **OPEN; reviewed removal merged through #327 (`9c733f6d`), not deployed/live-validated.** Deployment and serving validation remain outstanding; the bounded packet is accepted locally. Historical-data anomaly triage requires separate approval and cannot prove past non-use. |
| [Required deploy-workflow contract](required-deploy-workflow-contract/README.md) | Job remains advisory/unbounded fetch. Reviewed promotion, synchronized inventories and unskipped aggregate proof remain required. |
| [SERVICE_VERSION / image drift](service-version-image-drift/README.md) | Owners still differ. Define label invariant, reconcile and guard; old concrete tag pairs are historical, not current inventory. |
| [Wake-preflight hardening](task-8-9-wake-preflight-hardening/README.md) | Five retained source/comment findings plus unproven stub edge. Task 8.9 itself is accepted GO; do not reopen its serving gate. |
| [Windows az.cmd KQL transport](task-8-9-windows-az-cmd-multiline-query/README.md) | Multiline shim/type/outcome defects remain despite accepted evidence replay. Harden source and Windows regressions; immutable historical NON-GO stays. |
| [Merged-but-unapplied Terraform visibility](terraform-apply-not-automatic-on-merge/README.md) | Old incident repaired; manual apply intentional. Handoff callouts mitigate, but automated reminder/drift signal or explicit residual decision remains absent. No current unapplied delta asserted. |
| [Terraform config authority/use audit](terraform-config-audit/README.md) | Root scan is not full consumer audit. Trace HCL/backend/workflow use; two AWS names are candidates, not proven dead config. |
| [Actuator exposure follow-ups](unauthenticated-actuator-exposure/README.md) | Gateway source hardening **deployed**; locate/collect accepted live-negative proof, assess other services and disposition static guard. No current public-exposure assertion. |

## Demo limitations and freeze handoff

The [demo preparation plan](../../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md) remains the
owner's status dashboard. Its expected reset-control defect, post-logout token behavior,
empty-portfolio UX copy, valuation/cache findings, FTM limitation, non-USD evidence gaps and
unverified chat/FX/fallback edges are not closed by this inventory. Accepted cost-driven cold
starts and the unexplained earlier console anomaly also retain their qualified dispositions.
They are tracked there rather than inflated into duplicate directories here.

The roadmap/runbook reconciliation and media brainstorming/package remain separate freeze work.
No evidence/worktree/password-file cleanup is authorized or performed by this audit.
