# Review request — API Gateway custom-domain recovery (PR #191)

**Date:** 2026-08-31

**Prepared for:** Cursor (senior architecture/source reviewer)

**PR:** [#191 — `feat: api-gateway custom-domain recovery (source only)`](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/191)

**Current candidate head at update:** `113df12232d41b71ee420c2455a187cd75d5b8df`

**Review type:** source-only; no production execution and no merge authority

---

## 0. Assignment and authorization boundary

Independently review PR #191 against
[`CURSOR_KICKOFF_API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`](CURSOR_KICKOFF_API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md).
The implementation is intended to restore Terraform ownership of the API Gateway hostname while
reusing, but not importing or managing, the existing Azure-managed certificate.

This assignment authorizes read-only source inspection and local/offline verification only. It does
**not** authorize:

- resolving conflicts by editing or pushing the feature branch;
- approving or merging an unreviewable candidate;
- a remote Terraform plan or backend-state access;
- a Terraform apply or state import;
- an Azure hostname add, bind, or delete operation;
- certificate creation, replacement, import, deletion, or renewal;
- a G5 dispatch or any application/image rollout;
- closing the custom-domain backlog item; or
- checking B1 Task 5.7.

Return findings to the author. Do not implement fixes as part of this review.

## 1. Current candidate state — review may proceed

The initial intake state at `fd091360` has been superseded. The following candidate snapshot was
independently refreshed at `2026-08-31T08:29:12+05:30`:

| Check | Observed state |
|---|---|
| PR | Open, non-draft |
| Base | `main` |
| Head branch | `feat/api-gateway-custom-domain-recovery` |
| Head SHA | `113df12232d41b71ee420c2455a187cd75d5b8df` (4 commits) |
| Base SHA | `b4b4bd8165940a4b6d371cd1127bfc087d86ee0c` |
| GitHub mergeability | `MERGEABLE` |
| GitHub merge state | `BLOCKED` — pending checks/review, not a conflict signal |
| GitHub checks | registered; refresh the live rollup at review time—counts are intentionally not frozen here |
| Intentionally skipped live-state set | `Validate live-state dispatch`, `Terraform Azure (remote plan)`, `Terraform Azure (apply)` |
| Any other skipped/neutral check | not pre-approved; investigate and record its meaning before acceptance |
| PR body boundary | source-only; no remote plan/apply/bind/G5 |
| Master-plan declaration | `Master-plan impact: updated — B1, process` |

The branch-lineage and missing-CI gates recorded in the first version of this note are resolved. Do
**not** issue `NOT REVIEWABLE` or `REQUEST CHANGES` merely because the earlier snapshot showed
conflicts/no checks. `BLOCKED` must be interpreted with `mergeable: MERGEABLE` and the live check/review
rollup; it is not evidence of a remaining merge conflict.

Begin the substantive review against `113df122`. Before issuing the final verdict:

1. Refresh the head SHA, mergeability, review decision, and check rollup.
2. Require every applicable required check to finish successfully on that exact head. Only
   `Validate live-state dispatch`, `Terraform Azure (remote plan)`, and `Terraform Azure (apply)` may
   remain skipped by this source-only design; a skipped or neutral required source check is not green.
3. If the head changes, review the delta and rerun the affected verification before reissuing a verdict.
4. Record the reviewed base/head and live CI conclusions in the report.

The architecture review can proceed while checks run. Pending checks block final `ACCEPT SOURCE-ONLY`,
not the review itself.

## 2. Required reading and review comparison

Read these before judging the implementation:

1. The authoritative
   [`Cursor kickoff`](CURSOR_KICKOFF_API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md), including every load-bearing
   constraint and adversarial case.
2. PR file `docs/runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`.
3. [`ASSET_PICKER_E2E_MASTER_PLAN.md`](../plans/ASSET_PICKER_E2E_MASTER_PLAN.md), especially the B1/G5
   status and next authorization boundary.
4. [`SPEC_A_9_14_REOPEN_INGRESS.md`](../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md).
5. [`B1_G5_INGRESS_BLOCKER.md`](../runbooks/B1_G5_INGRESS_BLOCKER.md).
6. The open
   [`custom-domain backlog item`](../todos/backlog/api-gateway-custom-domain-binding/README.md).
7. The complete PR diff from current `main`, not only the latest commit.

At the refreshed head the PR changes 26 files, including the B1 task ledger. Review the workflow,
Terraform, guards, parsers, tests, ledger, and status documentation as one safety system. Do not approve
based only on the happy-path tests or the PR summary.

## 3. Review method and acceptance gates

### Gate A — lineage, scope, and status integrity

Require all of the following:

- The candidate contains current `main`, including PR #190's completed 9.14 evidence.
- GitHub no longer reports merge conflicts.
- The diff is limited to the source-only recovery mechanism, tests, and directly affected docs.
- The runbook remains prominently marked **NOT EXECUTED / SOURCE ONLY**.
- The backlog item remains open and `.kiro/specs/portfolio-composition-contract/tasks.md` keeps
  `- [ ] **5.7 G5 evidence.**` unchecked.
- The new PR #191 ledger text explicitly says the source PR does not satisfy 5.7 or unblock G5.
- No text claims TLS restoration, a successful live bind, G5 readiness, or Writer_Convergence.
- The PR body continues to declare `Master-plan impact: updated — B1, process`.
- No generated Terraform plans, credentials, raw app configuration, or secret-bearing artifacts are
  committed or uploaded.

Any lost PR #190 wording, premature completion claim, or scope expansion is a blocking finding.

### Gate B — Terraform ownership boundary

Review
[`main.tf`](../../infrastructure/terraform/azure/main.tf),
[`variables.tf`](../../infrastructure/terraform/azure/variables.tf), and the Container App module
[`outputs.tf`](../../infrastructure/terraform/azure/modules/container-app/outputs.tf).

Confirm:

- `module.api_gateway.app_id` comes from the child resource output; the root does not reconstruct an ID.
- The hostname is the repository constant `api.vibhanshu-ai-portfolio.dev`.
- Exactly one `azurerm_container_app_custom_domain` resource owns hostname presence.
- Resource presence requires both gateway ingress and the custom-domain flag.
- Neither certificate input is configured on the Terraform resource.
- Only the two asynchronously populated certificate fields are ignored.
- There is no managed/uploaded certificate resource, import block, AzAPI substitute, provisioner,
  `terraform_data`, `null_resource`, or hidden bind operation.
- The existing Azure-managed certificate remains outside Terraform's create/update/destroy graph.
- The provider behavior and schema assumptions still match the locked AzureRM version after the branch
  is updated.

Reject any design that can create, import, replace, or delete the existing managed certificate.

### Gate C — dispatch profiles and authorization containment

Review [`.github/workflows/terraform-azure.yml`](../../.github/workflows/terraform-azure.yml),
[`validate_dispatch.py`](../../infrastructure/terraform/azure/scripts/validate_dispatch.py), and their
tests.

Confirm:

- The only new profiles are `api-gateway-custom-domain-restore` and
  `api-gateway-custom-domain-remove`.
- Both are production-scoped, main-only, exact-SHA profiles and require existing image-identity inputs.
- Both reject seed-image and market-data-job recreation inputs.
- The domain flag is false only for remove and the two 9.14 ingress profiles; it is true otherwise.
- Every existing plan guard recognizes the two names without weakening its original scoped behavior.
- Unknown/typo profiles fail closed everywhere.
- Remote-plan and apply paths run the same custom-domain guard logic.
- No skipped job or skipped assertion can be mistaken for a successful reviewed plan.
- The production Environment gate remains intact for any future apply.

Pay special attention to expression evaluation, missing step outputs, shell quoting, and behavior under
`standard`, both domain profiles, and both 9.14 profiles.

### Gate D — read-only preflight parser

Review PR file
`infrastructure/terraform/azure/scripts/validate_api_gateway_custom_domain.py` and its tests. Require
pure, independently testable validation for restore, remove, and post-bind/read-back.

For restore, verify fail-closed enforcement of:

- exact gateway ingress shape and single 100% latest-revision traffic weight;
- initially absent custom-domain binding;
- exactly one named existing certificate with exact subject, `Succeeded` state, and CNAME validation;
- certificate ID derived only from Azure's sanitized projection, never dispatch input;
- exact DNS CNAME to the live ACA FQDN and exact `asuid.api` TXT verification ID; and
- capture of gateway ID, certificate ID, revision, and default FQDN for later assertions.

This must be a fresh live-state preflight in both the future remote-plan and apply jobs. Confirm the
workflow calls Azure immediately before planning/applying, selects the named certificate from the live
environment, and revalidates exact identity, subject, `Succeeded` state, and CNAME validation. The
2026-08-31 runbook snapshot is context only and must not satisfy this precondition. At the refreshed
head, the workflow visibly performs `az containerapp env certificate list`; review the parser and job
wiring to prove the result is load-bearing rather than merely logged.

For remove, require exactly the expected existing `SniEnabled` binding and certificate ID. An absent,
duplicated, differently named, or differently bound domain must stop.

Check that malformed JSON, nulls, wrong types, duplicate objects, case/whitespace tricks, missing fields,
unexpected list shapes, and secret-like values fail without leaking raw payloads into errors.

### Gate E — universal exact-scope Terraform-plan guard

Review PR file `infrastructure/terraform/azure/scripts/assert_api_gateway_custom_domain_plan.py` and
its adversarial tests.

The guard must run for every live-state profile and enforce:

- **Restore:** one create only, for the exact domain resource, hostname, and gateway ID; no explicit
  certificate ID or binding type; no gateway or unrelated change.
- **Remove:** one delete only, with exact before-side hostname and gateway ID; no gateway or unrelated
  change.
- **All other profiles:** no custom-domain action.
- **All profiles:** no managed/uploaded certificate action.
- No replacement action, extra instance, extra domain, malformed plan, unknown action, or profile typo.
- Ignored/computed certificate fields are not falsely presented as proof of the later CLI bind.

Manually inspect at least one passing and one failing fixture for each profile class. Ensure assertions
read the exact plan structures Terraform emits rather than merely matching hand-shaped fixtures.

### Gate F — preserve the 9.14 reversal contract

Confirm the existing 9.14 guard still accepts exactly one gateway ingress update and nothing else.

The required operational sequence must remain explicit:

1. future domain remove and its evidence;
2. separate future ingress close;
3. separate future ingress reopen;
4. separate future domain restore and bind.

Neither 9.14 profile may add, remove, update, bind, import, or delete a domain/certificate. Do not allow
the custom-domain work to rewrite the historical 9.14 outcome or strengthen the known non-falsifiable
B5 image-equality claim.

### Gate G — post-apply bind and live read-back design

This is the highest-risk workflow section even though it is not executed by this PR.

Confirm the future restore path:

- runs the bind only after an asserted exact Terraform plan and successful apply;
- requires the preflight-derived, non-empty certificate ID;
- always passes that exact ID using `--certificate`;
- has no implicit-certificate fallback, certificate creation, import, cleanup, or retry workaround;
- stops immediately if Terraform did not create the expected hostname or if bind fails; and
- never dispatches G5.

Confirm post-bind proof requires:

- one exact `SniEnabled` binding to the preflight certificate ID;
- the existing certificate remains `Succeeded` with exact subject;
- latest and latest-ready revision remain unchanged;
- ingress, port, transport, TLS-only mode, and traffic remain exact;
- both default and custom health endpoints return 200;
- the custom endpoint uses normal certificate verification; and
- certificate SAN/subject coverage and validity dates are actually verified, not inferred from HTTP
  status alone.

Confirm remove read-back proves the domain is absent while the certificate still exists.

Treat any command without an explicit certificate ID, any `curl -k`/`--insecure`, or any missing
revision/TLS assertion as blocking.

### Gate H — documentation truthfulness and future approvals

At `113df122`, the runbook includes two exact read-only Resource Graph queries and sanitized result
projections for the 9.5 loss event and surviving certificate. Independently review that evidence rather
than accepting it solely because it is present. Require:

- the exact read-only Resource Graph query, including resource and time/correlation scope;
- the minimal sanitized output proving hostname, binding type, and certificate ID changed to null under
  correlation `cf0fc22a-595b-9bba-143a-6749888b1998`;
- the minimal sanitized evidence that the named managed certificate
  `mc-wealth-prod-ac-api-vibhanshu-ai-5159` survived; and
- query date, subscription/resource-group context, and explicit statement that the evidence is
  historical—not a substitute for the future apply preflight.

The loss query must return exactly the three named custom-domain fields as one row per changed property;
each must show the stated previous value and `newValue: null` under the Terraform correlation. The
certificate query must return exactly one matching resource with the stated subject, `Succeeded` state,
and CNAME validation. If the provenance is absent, incomplete, over-broad, or inconsistent at the
reviewed head, raise a finding and request correction.

Resolve one non-blocking timing question: the certificate ID changed to null at
`2026-08-22T19:26:41.587Z`, while binding type and hostname changed to null at
`2026-08-22T19:37:00.225Z`, under the same correlation. Confirm whether this is one Terraform operation
whose ACA reconciliation completed in two phases or a sequence with different reverse-operation
implications. Require one precise runbook sentence that records the two timestamps without implying an
atomic clearing event. Treat it as a documentation clarification unless the evidence changes the
remove/restore sequencing or safety analysis; if it does, raise a substantive finding.

Verify the runbook, backlog, B1 blocker, 9.14 runbook, and master plan agree on these states:

- PR #191 is source-only and unmerged until it actually merges.
- A later source merge is still unapplied.
- Remote plan, apply, and explicit CLI bind require separate authorization and reviewed evidence.
- A successful structural plan proves syntax/resource-graph shape only.
- A live read-back evidence PR is required before closing the backlog item or resuming G5.
- No production action is implied by source review acceptance.

## 4. Independent verification — do not trust the PR checkboxes

Run these locally against the exact reviewed SHA:

```powershell
python infrastructure/terraform/azure/scripts/test_validate_dispatch.py -v
python infrastructure/terraform/azure/scripts/test_validate_api_gateway_custom_domain.py -v
python infrastructure/terraform/azure/scripts/test_assert_api_gateway_custom_domain_plan.py -v
python infrastructure/terraform/azure/scripts/test_assert_spec_a_9_14_plan.py -v
python -m unittest discover -s infrastructure/terraform/azure/scripts -p "test_*.py" -v
terraform -chdir=infrastructure/terraform/azure fmt -check -recursive
terraform -chdir=infrastructure/terraform/azure init -backend=false
terraform -chdir=infrastructure/terraform/azure validate
python scripts/tests/test_master_plan_status_propagation.py -v
git diff --check
```

`terraform init -backend=false` may download the locked provider but must not access the production
backend. Do not run a remote plan. Record exact commands, exit status, test counts, skips, and relevant
tool/provider versions. A skipped command is not green evidence.

Require applicable GitHub CI checks to complete on the exact reviewed head. The complete intentional
source-only skip set is exactly `Validate live-state dispatch`, `Terraform Azure (remote plan)`, and
`Terraform Azure (apply)`: these skips prove that no production path ran, but they do not replace tests.
Do not infer that any fourth skipped/neutral check is expected. Investigate it and record whether it is
required; if a required source check is absent, skipped, or neutral, report that as a gate requiring an
explicit owner decision rather than silently waiving it.

## 5. Required review output

Return one report with this structure:

```text
Verdict: NOT REVIEWABLE | REQUEST CHANGES | ACCEPT SOURCE-ONLY
Reviewed base SHA:
Reviewed head SHA:
Merge base:
GitHub merge state:
CI checks and conclusions:

Findings (highest severity first):
- [P0/P1/P2/P3] file:line — finding, impact, and required correction

Kickoff requirement matrix:
- Gate A lineage/status: PASS/FAIL — evidence
- Gate B Terraform ownership: PASS/FAIL — evidence
- Gate C dispatch containment: PASS/FAIL — evidence
- Gate D preflight: PASS/FAIL — evidence
- Gate E plan guard: PASS/FAIL — evidence
- Gate F 9.14 preservation: PASS/FAIL — evidence
- Gate G bind/read-back: PASS/FAIL — evidence
- Gate H evidence provenance/documentation: PASS/FAIL — evidence

Independent verification:
- command — result

Production actions performed: none
Authorization granted: source review only; no merge/plan/apply/bind/G5
Next required step:
```

Use `ACCEPT SOURCE-ONLY` only when every gate passes on one current, conflict-free candidate SHA and
required CI/local evidence is green. Acceptance means the source is ready for an owner-controlled merge;
it does not authorize the reviewer to merge it.

## 6. Stop and escalate

Stop with a blocking finding rather than broadening the solution if:

- current `main` cannot be integrated without changing the recovery design or historical evidence;
- AzureRM behavior differs from the reviewed 4.81 contract;
- Terraform wants to manage any certificate or change the gateway during restore/remove;
- the guard cannot prove exact resource scope;
- the workflow can bind without the exact existing certificate ID;
- the runbook's Resource Graph evidence is absent, incomplete, or inconsistent with the recorded query output;
- post-bind TLS/SAN/revision proof is incomplete;
- tests or CI are absent, skipped, stale, flaky, or run against a different SHA;
- resolving a finding would require production access, remote plan, state mutation, or Azure writes; or
- anyone asks to close the backlog item or resume G5 from source-only evidence.

The correct outcome is a precise finding and a new candidate review, not an inferred waiver.
