# Backlog: Restore EventBridge Cache Warming

**Status:** Open — parked by the accepted AWS cost decision; audited 2026-09-26 UTC.
**Owner:** unassigned  
**Tracked in:** [RCA](../../../analysis/RCA_2026-04-26_warming-ui-cicd-failures.md)

---

## Current disposition — 2026-09-26 UTC

AWS remains a standby target, not a removed architecture. Warming remains deliberately disabled
under the [2026-04-30 cost decision](../../../changes/CHANGES_CACHE_WARMING_2026-04-30.md)
(`c3eb1f14`); `terraform.yml` intentionally omits `TF_VAR_enable_warming`. This is parked
future work, not a confirmed active EventBridge malfunction and not obsolete work to delete.

Several old source prerequisites are already fixed: null architecture validation, direct gateway
Function URL, EventBridge description, plan warming assertions, `pipefail`, and resource smoke
checks. Do not implement those repairs again. Reactivation still needs an owner decision,
healthy-init/CI evidence, current region/config and module-naming review, and bounded operational
validation. No AWS resources or initialization state were queried in this audit.

The April inventory and unchecked checklist below are retained as historical evidence, not a
current assertion that every listed source defect remains. Use this disposition when restarting.

## Historical status & decision

**Parked.** Warming infra is currently destroyed in AWS. We are accepting natural cold-start latency on the first request until application init bugs are resolved. Re-enabling warming while apps fail to init would flood logs and exhaust the 10-unit ap-south-1 concurrency pool with failing pings.

---

## Why This Is Parked, Not Abandoned

Warming is a latency improvement, not a correctness requirement. Reactivating it while the four Lambda services (portfolio, user, transaction, notification) have unresolved Spring Boot initialization failures would cause each warming ping to fail, burning all available concurrency and generating misleading noise in CloudWatch. Once Spring init is confirmed healthy across all services for at least one full CI cycle, the checklist below can be worked in order and warming can be safely re-enabled.

---

## Validated Current State of AWS

Audited 2026-04-26 against AWS account `844479804897`, region `ap-south-1`.

- All `wealth-warm*` EventBridge Rules, API Destinations, and Connections: **absent**
- IAM role `wealth-lambda-warming-scheduler`: **absent**
- SNS warming topic: **absent**
- Terraform remote state has **no `module.warming`** resources
- Local `terraform.tfvars` has `enable_warming = true` but is gitignored — does **not** affect CI

---

## Required Work Before Re-Enabling

Complete **in order**:

- [ ] **1. Fix `lambda_architecture` validation** in `infrastructure/terraform/variables.tf:282–285`  
  *(Separate Terraform-priority PR; tracked elsewhere. Until this lands, CI plan fails before any apply runs.)*

- [ ] **2. Fix the api-gateway warming URL** in `infrastructure/terraform/main.tf:148`  
  Currently: `"https://${module.networking.cloudfront_domain_name}/actuator/health"`  
  CloudFront's `default_cache_behavior` (see `modules/cdn/main.tf:108–109`) routes `/actuator/health` to `frontend-static-s3` → 403.  
  Replace with the direct API Gateway Lambda Function URL `/actuator/health` (verified 200) for parity with the other three targets.

- [ ] **3. Update stale comment** at `infrastructure/terraform/main.tf:127`  
  Change `"EventBridge Scheduler + SNS alarm"` → `"EventBridge Rules + API Destinations + SNS alarm"`.  
  The implementation in `modules/warming/main.tf` uses Rules + API Destinations; EventBridge Scheduler does not accept `arn:aws:events:…` API Destination ARNs.

- [ ] **4. Resolve `module "networking"` alias mismatch**  
  `main.tf` sources `./modules/cdn`; `modules/networking/` is an empty untracked directory.  
  Either rename to `module "cdn"` (with `terraform state mv`) or remove the empty directory.

- [ ] **5. Add warming variables to CI** — add to `.github/workflows/terraform.yml` `env:` block:
  ```yaml
  TF_VAR_enable_warming: "true"
  TF_VAR_warming_alarm_email: "vibhanshu.agarwal@gmail.com"
  ```

- [ ] **6. Confirm CI AWS_REGION is `ap-south-1`**  
  Note: `.env.secrets` sets `AWS_DEFAULT_REGION=us-east-1` for local CLI use, but CI does not source that file.

- [ ] **7. Extend `assert_plan.py`** with warming assertions when `enable_warming=true`  
  `infrastructure/terraform/scripts/assert_plan.py` currently has zero warming-related logic.

- [ ] **8. Add post-apply smoke check** verifying 4 EventBridge rules + 4 API destinations exist.

- [ ] **9. Add `set -o pipefail`** around `terraform apply | tee apply.txt` in the workflow so Terraform exit codes aren't masked by `tee`.

---

## Resolved: Which CI Run Destroyed Warming

**Run #76 was the first run that destroyed warming.** Terraform applies to AWS only on merges to `main`; run #76 was the earliest successful apply on main with `enable_warming=false`, making it the root destroyer. Candidates #80, #82, #84, #86 (2026-04-25) were subsequent applies that kept warming absent but were not the originating event.

---

## Re-Enablement Preconditions

- (a) All 4 services' Spring Boot init confirmed healthy via CloudWatch `REPORT` rows (no init errors)
- (b) Terraform CI green for ≥ 1 full cycle
- (c) All checklist items above merged

---

## Cross-References

- [RCA — validated audit](../../../analysis/RCA_2026-04-26_warming-ui-cicd-failures.md)
- [Original warming handoff (manual local apply)](../../../changes/CHANGES_CACHE_WARMING_2026-04-25.md)
- [Lambda stopgap execution plan — Phase 2 context](../../../architecture/lambda-stopgap-execution-plan.md)
