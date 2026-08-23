# Review request: checkpoint 9.7 (Mongo repair) gate review — verify two findings, close two gaps

## Context (self-contained — no prior conversation assumed)

Repo: `wealthmgmtandportfoliotracker`. **This is a portfolio/demo project, not a production business** —
worth knowing up front, since it recalibrates how much operational weight a finding should carry: real
engineering rigor is the point (this spec's whole design is deliberately production-grade), but there
is no real money, real customers, or real financial-loss exposure behind the data involved.

Spec `supported-asset-integrity` ("Spec A") has an irreversible production cutover in progress
(`.kiro/specs/supported-asset-integrity/tasks.md`, Task 9). Checkpoints 9.1–9.6 are done and verified
against live state: catalog agreement across all three services, refresh producer narrowed, refresh Job
suspended, Kafka drained, gateway ingress closed, and — as of today — the Postgres repair (V17–V19)
executed and its `Post_Migration_Integrity_Assertion` independently re-verified against live Postgres
(all 6 postcondition queries re-run directly, zero violations; `repair_audit` 2 rows, `repair_archive`
51 rows reason `LEGACY_SYNTHETIC`).

**9.7 is next: R3b, the MongoDB repair (`MM.NS` → `M&M.NS`) — marked IRREVERSIBLE.** Its go-condition
(tasks.md line 468) is: `9.6 assertion passed; refresh still suspended`. Both hold, confirmed live.
**No go has been granted for 9.7.** A separate gate review was run first, covering five items: refresh
fence, Mongo backup evidence, repair-job artifact/digest, collision preflight, and terminal-state/
runbook procedure. Three came back clean:

- **Refresh fence**: confirmed live, `MARKET_DATA_JOB_RUNNER_ENABLED=false`, zero running executions.
- **Collision preflight**: queried Mongo directly. Source `MM.NS` exists; destination `M&M.NS` does
  **not** exist. No collision — this run takes the simple acquire→mutate→delete path, never touching
  the `FAILED_CONFLICT` collision-resolution logic. The `repair_archive` collection doesn't exist yet
  either (genuinely fresh, no prior partial attempt to reconcile).
- **Backup evidence**: closed. Atlas cluster is confirmed Free tier (screenshot from the console:
  "Backups ✗" on Free; only Flex/M10/M30 have any backup mechanism). Given the portfolio-project
  framing above, a tier upgrade was judged unnecessary. A manual full-fidelity export of the one
  collection this repair touches (`market_prices`, 161 docs) was taken directly — SHA-256 checksummed,
  restorable by drop+reinsert — as the accepted backup mechanism instead.

**Two items came back with real, unresolved findings. These are what need independent verification
and, if confirmed, a concrete proposed resolution — not just confirmation that a gap exists.**

## Finding 1 — repair-job artifact/digest: the Job doesn't exist yet, and no image contains its code

The repair Job's Terraform resource (`azurerm_container_app_job.market_data_repair`,
`infrastructure/terraform/azure/main.tf`, added by the unmerged branch
`feat/supported-asset-mongo-repair`, commit `54291e0`) references:

```hcl
image = (var.use_seed_image || var.market_data_repair_job_use_seed_image) ? "...helloworld:latest" : (
  "${azurerm_container_registry.main.login_server}/market-data-service:${var.image_tag}"
)
```

This is the **same mutable tag** the routine `market-data-service` app deploy uses — not a separately
built artifact, not pinned to a digest. Two things checked directly:

1. `az acr repository show-tags --name wealthprodacr --repository market-data-service --orderby
   time_desc --top 5` — the newest tag is `96a7e47...`, the commit that shipped the *Postgres* repair.
   **No image in ACR contains `MongoMmNsRepairService`, `CollisionPolicy`, or any of the other new
   classes this branch adds** — that code has never been built.
2. The `azurerm_container_app_job.market_data_repair` resource does not exist in Azure at all yet
   (confirmed: this branch was never merged, so `terraform apply` has never created it). This is a
   **first-time resource creation**, not an in-place update like checkpoints 9.3/9.5 were.

Working hypothesis for the correct sequence, not yet executed or verified end-to-end:

1. Merge `feat/supported-asset-mongo-repair` onto current `main` (it was rebase-tested clean in a
   throwaway local branch — cherry-picked without conflicts, compiled, and
   `:market-data-service:test :market-data-service:integrationTest` passed 118 unit / 37 integration,
   0 failures — but never pushed or merged).
2. Trigger `deploy-azure.yml` scoped to `services=market-data-service` to build and push a new image
   tagged with that merge commit's SHA — the **first** artifact that actually contains the repair code.
3. Trigger `terraform-azure.yml` with `action=apply` to create the `market-data-repair-job` resource,
   pointing `var.image_tag` at that same commit SHA.
4. Only then manually start the Job execution.

**What to verify:**
- Is this sequence actually correct, or does something (e.g. `terraform-azure.yml`'s existing
  `market_data_repair_job_use_seed_image` variable, added by the same branch) change the picture —
  could/should step 3 run before step 2, using the seed-image escape hatch, then get rolled to the
  real image separately? Read `infrastructure/terraform/azure/variables.tf`'s
  `market_data_repair_job_use_seed_image` doc comment and `terraform-azure.yml`'s existing
  `use_seed_image`/`market_data_refresh_job_use_seed_image` handling (the refresh Job's own recovery
  path) for the established pattern this repo already uses for a Job's first provisioning.
- Given this Job runs **exactly once**, is a mutable tag actually an acceptable risk here, or should
  it be pinned to an immutable digest before it's ever triggered? `deploy-azure.yml` already supports
  a `prebuilt_digest` input for scoped `portfolio-service` deploys (used for checkpoint 9.6's own
  deploy) — does the same mechanism extend to `market-data-service`, or would digest-pinning this
  specific Job need its own, separate plumbing? If separate plumbing is needed, is it worth building
  for a one-shot Job in a portfolio project, or is verifying the exact tag/commit match by hand
  (recording the image digest actually pulled, immediately before triggering) a proportionate
  alternative?

## Finding 2 — no operator runbook exists for `FAILED_CONFLICT`, anywhere in the repo

Searched exhaustively: `.kiro/specs/supported-asset-integrity/design.md`,
`.kiro/specs/supported-asset-integrity/tasks.md`,
`docs/agent-instructions/CURSOR_KICKOFF_SPEC_A_TASK_7_MONGO_REPAIR.md`, `docs/runbooks/` (only
`AZURE_SECRETS_SETUP.md` and `OBSERVABILITY.md` exist there, neither relevant). Every one of these
states the same line, verbatim or near-verbatim: *"A conflict is terminal... Clearing it is an operator
action."* **None of them says what that action actually is.**

The design is otherwise extremely precise about the mechanism: `FAILED_CONFLICT` fires specifically
when two documents (source `MM.NS`, destination `M&M.NS`, or two `PENDING` archive candidates) carry
the **same `updatedAt` with conflicting field values** — i.e. two genuinely different observed prices
claiming the same instant, which the design correctly says cannot be resolved automatically (D13: "an
unresolvable conflict"). Given this run's collision preflight came back clean (destination doesn't
exist), this specific execution is very unlikely to ever reach `FAILED_CONFLICT` — but "unlikely" is
not "the runbook exists," and the design's own philosophy throughout this spec is that terminal states
need to be operable, not just correctly detected.

**What to verify and produce:** a concrete, minimal operator procedure for this repo's actual situation
— not a generic distributed-systems essay. At minimum it should cover: (a) how an operator would
actually *observe* a `FAILED_CONFLICT`, given the Job's own exit code and structured logs are the only
signals named anywhere (what's the exact log line / query to run against Log Analytics?); (b) what data
they'd need to look at to make the resolution decision (both conflicting documents' full field values,
presumably — from where?); (c) what "resolving" concretely means in Mongo terms — is it a manual
`updateOne`/`deleteOne` against the fenced documents, and if so what predicate keeps that manual
intervention from racing a still-running or since-restarted Job execution; (d) how the fence and lease
get cleared afterward so a subsequent Job run doesn't either re-trip the same conflict or silently skip
past it. If this genuinely doesn't exist anywhere, say so plainly and draft it — this is exactly the
kind of gap that shouldn't be improvised live against production data, however small the actual
portfolio-project stakes are.

## What NOT to re-litigate

Do not re-open: the backup-mechanism decision (closed, see above), the collision-preflight result
(clean, verified directly against live Mongo), or checkpoint 9.6 (closed, separately verified twice).
This review is scoped to findings 1 and 2 only. No go for 9.7 should be inferred from this review
landing — that remains a separate, explicit decision regardless of what this review finds.
