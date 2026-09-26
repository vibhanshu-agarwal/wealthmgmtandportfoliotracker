# Current demo operations and restart guidance

**Owner approval required before operational execution:** endpoint/browser traffic, account
creation or mutation, secret/cloud/database access, dispatch, deploy, repair, rollback and
cleanup need their applicable bounded approval. Documentation publication and merge also remain
separate decisions. This guide grants no new authority and revives no historical approval.

**Reconciliation date:** 2026-09-26 UTC. Source basis: `main@d515aa5b`, plus the accepted
backlog-audit documentation through `602f6bca`. This is a source/accepted-evidence guide, not a
fresh cloud inventory. Start with the [runbook index](README.md); use the
[demo preparation plan](../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md) as the status dashboard.
"Production" in resource/workflow names denotes this project's deployed **portfolio-demo
environment**, not an assumption of real customers or a commercial production SLA.

## 1. Accepted baseline and evidence limits

The dashboard records the #320 fixed build `brJCAsidY8FAIgtIzNtSo`, the final-build rehearsal,
targeted #3–#6 checks, and the post-#320 multi-user suite as distinct evidence. The suite verdict
is `PASS_WITH_EXPECTED_DEFECTS`, not clean PASS. Frontend identity was observed live; backend
identity rests on pinned deploy artifacts and the run-time GitHub later-deploy check, not a new
Azure revision read. Do not substitute an old runbook revision or `SERVICE_VERSION` label alone.

Technical evidence is complete within the accepted desktop-demo scope. No further full suite
or rehearsal is required merely to file documentation. A material new code/configuration or
deployment change requires reassessing the affected evidence before reusing acceptance.

The A4 and post-#320 temporary users were cleaned up; those roles are not reusable demo logins.
Do not invent replacement accounts or rerun old signup/cleanup scripts without new authority.
Cleanup query/count and login-proof limitations remain as recorded in the dashboard.

## 2. Demo startup and account safety

1. Obtain the privately retained reviewed operator script and `demo-warmup.ps1`, with their
   accepted versions/hashes. They are **not shipped by this guide**. If unavailable, stop and
   recover/review the kit; do not assume a workstation-specific historical path still exists.
2. Confirm the approved target/build/account and that no other session or E2E workflow uses
   the account during the walkthrough. Resolve credentials through approved private inputs;
   never copy passwords, JWTs or raw authenticated responses into published evidence.
3. Under the applicable live approval, run the reviewed warm-up. Wait for its `GO` and retain
   its default keep-alive throughout the demo (45 minutes after GO). Its readiness window is
   bounded; `NOT READY` or a build/account mismatch is a stop, not permission to raise replicas.
4. Before any edit, capture the signed-in baseline and satisfy the operator script's exact
   account/portfolio/holdings/version checks. A failed baseline stops before save. Undo the
   approved edit by the script's exact reverse operation and verify the end state; a restore
   mismatch needs a separate recovery decision, not an improvised reset.
5. Keep the known reset-control UX defect, post-logout token lifetime and chat fallback visible.
   Chat can be slow and can return a fallback or cached result. A source label without the raw
   response does not prove that a particular request called Azure OpenAI.

A prior warm-up is not perpetual readiness. The observed ~60-second cold price loads and the
2.7-second warm Edit Holdings appearance are historical comparisons, not present availability
guarantees. HTTP wakes can activate Kafka consumers/advance offsets; chat can invoke a paid
model and cache an answer. These sessions are not "read-only" merely because holdings are unchanged.

There is no arbitrary calendar deadline or fixed appointment imposed by this guide. For tests
requiring stable prices/FX, use a bounded window without overlapping data refresh. Terraform
source schedules the market Job at `0 8 * * *` (08:00 UTC); the portfolio FX cache eviction
uses `fx.refresh-cron` with a 06:00 default and no explicit annotation timezone. Confirm runtime
timezone and whether the service is running before treating it as an observed 06:00 UTC event.
Neither schedule is proof that today's refresh succeeded. Starting a Job is an operation separate
from Terraform provisioning and requires its own approval.

## 3. Deploy versus Terraform — do not interchange them

Source contracts: [deployment dispatcher](../../.github/workflows/deploy.yml),
[dispatch validator](../../.github/workflows/scripts/validate_deploy_dispatch.py),
[Terraform workflow](../../.github/workflows/terraform-azure.yml), and
[Terraform validator](../../infrastructure/terraform/azure/scripts/validate_dispatch.py).

| Operation | Required preparation and boundary |
|---|---|
| Application deploy | Dispatch `deploy.yml` on `main` with the reviewed full `expected_main_sha` and an explicit mode. `scoped` needs the selected services; `digest` currently accepts the portfolio repository only; `frontend-only` accepts no services/digest. The dispatcher has a production approval gate. The Azure deploy files are reusable workflows, not direct dispatch entry points. |
| Structural Terraform plan | PR-triggered or `action=plan`; uses a local-backend override, still authenticates to Azure, and cannot preview the actual live-state delta. |
| Live-state Terraform preview | Separately authorized `action=remote-plan` on `main`; exact reviewed SHA, four-service `deployed_image_tags_json`, selected profile and any required portfolio digest. It reads the real backend; it does not apply. |
| Terraform apply | Separate authorized `action=apply` on `main`, same identity discipline and production Environment gate. It regenerates a plan and runs guards; it does not consume the earlier preview artifact. A merge alone does not apply infrastructure. |
| Refresh Job start | Separately authorized execution of the existing Azure Job, not a Terraform apply and not a replay of Spec A 9.10's historical controlled template. |

Prepare these inputs in the reviewed automation/packet from authorized source/artifact/cloud
reads; do not ask the owner to reconstruct old SHAs or copy one main SHA into every image tag.
`deployed_image_tags_json` must have exactly the four service keys with canonical lowercase
40-hex tags. Tags, resolved digests and live revision identity are distinct; stop if they disagree.
Never use `latest`, seed-image bootstrap, Job replacement, an arbitrary feature ref or a direct
environment overwrite to get around a failed gate. Scoped profiles require the recovery flags
false. A `standard` profile does not by itself prove a one-resource-only change.

## 4. Diagnostics, cost and repair

- Follow [observability](OBSERVABILITY.md) for bounded, authorized queries, manual allowance
  audits and kill-switch preparation. Source caps/budget are not a guaranteed bill ceiling;
  there is no automated audit here, and project inactivity does not prove zero Azure spend.
- Public source-approved health paths are `/actuator/health`, `/api/portfolio/health`,
  `/api/market/health` and `/api/insights/health`. Require the selected packet's readiness proof;
  `401`/`403`, empty query output or wake-only traces are not a successful downstream smoke test.
- Mongo repairs, Kafka offset resets, seed/reset operations, chart-history deletion, cache
  deletion and account cleanup are not routine diagnostics. Each needs a current reviewed scope,
  explicit targets, writer/consumer non-interference, preview/backup where applicable and verification.
- Preserve canonical keys/leases/archives and accepted evidence on failure. Do not reuse
  `MM.NS` repair state or historical accounts as a generic cleanup template.

## 5. Rollback and future candidate reliance

Historical R-A/R-B rollback commands are not today's safe recovery instructions. The
[Task 7.11 record](B1_R_C_TASK_7_11_WRITER_CONVERGENCE_FLOOR.md) records a minimum R-B3r floor
and warns that Single mode purged an old revision. A revision name cannot be reused merely
because its digest once served. Preserve that floor and reassess later migrations, API/UI contracts,
desired state and image availability before a separately approved rollback and serving proof.

Before a new B1 source-envelope reliance or `VERSION` change, resolve the
[VERSION membership gap](../todos/backlog/b1-candidate-envelope-product-version-root/README.md),
re-attest affected inputs and obtain independent review. Old GC.5/writer proofs are cut-bound;
they are not automatic acceptance for later source or newly reachable delete/writer paths.

## 6. Freeze and later restart

Before parking the project, file the reviewed roadmap/runbook/backlog reconciliation and a clear
handoff of private kit/evidence locations to the owner. Brainstorm the LinkedIn, resume, PPT and
video package before creating it. Choose maintenance responsibility for credentials, the manual
observability audit and any retained cloud costs; do not assume "frozen" suspends Jobs/resources.
No shutdown, rotation, schedule change, cleanup or recurring automation is authorized here.

On restart: read the dashboard and backlog first; refresh Git state and inspect source/workflow
drift; inventory retained private prerequisites without exposing values; obtain authority for any
needed live refresh. Classify historical acceptance versus current evidence before executing a
new packet. Do not recreate closed/superseded backlog work from old runbook prose.

Private A4 `pw-output/` retains its agreed after-demo cleanup disposition. The post-#320 run's
`pw-output/` has no deletion decision yet. Evidence/worktree cleanup requires inventory and
specific authority; neither this reconciliation nor a docs merge deletes anything.
