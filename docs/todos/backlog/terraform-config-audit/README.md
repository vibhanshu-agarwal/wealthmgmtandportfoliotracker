# Backlog: Terraform Non-Secret Config Has No Index

**Status:** Open — 2026-08-15
**Owner:** unassigned
**Priority:** Low — nothing is broken; this is traceability debt
**Tracked in:** [PR #100](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/100)
(config-hygiene pass). Raised because the AWS→Azure migration plus several config and code
dedup passes could plausibly have left active or inactive config stranded in Terraform files.

---

## What was verified (2026-08-15)

The **secrets** path is clean, on evidence rather than assertion:

| check | result |
|---|---|
| `TF_VAR_*` assignments across `terraform.yml` + `terraform-azure.yml` | 44 |
| …fed from a `secrets.*` documented in `.env.secrets.example` | 43/43 secrets documented |
| …fed from workflow context (`github.sha`, `workflow_dispatch` inputs) | 3 — runtime values, correctly not config |
| …fed from a literal, a tfvars file, or `vars.*` | **none** |
| `vars.*` namespace, entire repo | one key, `CLOUD_PROVIDER`, documented |
| root-module variables with no default, left unsupplied | **zero** (23 aws, 15 azure) |

Reproduce by parsing `variable "x" { … }` blocks in each root module for a `default =`, then
matching the no-default set against `-var=` flags and `TF_VAR_*` env in the applying workflow.

An earlier claim in this cleanup — that no `terraform.tfvars` existed and Terraform was therefore
fully covered by `.env.secrets.example` — was wrong on the first half. `terraform.tfvars.example`
exists in **both** `infrastructure/terraform/aws` and `infrastructure/terraform/azure`; the check
that missed them globbed one directory too shallow.

## The actual gap

Non-secret Terraform config has no representation in `.env.secrets.example` and **cannot** have
one: it is HCL, not environment variables, so there is no key to document.

- **~42 root-module `variable` blocks carry defaults in `*.tf`** — 30 in `aws`, 12 in `azure`:
  regions, instance classes, replica counts, feature flags. Whether each is still *active* is
  unknown.
- **`aws/localstack.tfvars`** is a real committed tfvars consumed via `-var-file`, carrying its own
  `aws_region`, bucket names, `rds_instance_class`, `elasticache_node_type`, and stub credentials.
- **`{aws,azure}/terraform.tfvars.example`** document a further set for manual runs: `environment`,
  `location`, `openai_location`, `openai_deployment_capacity`, `image_tag`,
  `api_gateway_min_replicas`.
- **`backend-aws.hcl` / `backend-localstack.hcl`** hold backend config. `backend-azure.hcl` is
  written at runtime from the `AZURE_BACKEND_HCL` secret, which *is* documented.

**AWS is the standby path**, so some of its 30 defaults are plausibly dead. Not checked.

## What NOT to do

Do not copy HCL defaults into `.env.secrets.example` as env keys. Duplicating them recreates
exactly the drift the index exists to prevent, and a value that appears in two places without one
being authoritative is worse than a value that appears once somewhere else. The index now *points
at* these files; that is the intended end state for HCL config.

## Doing it properly

Diff declared variables against actual usage per module — a variable declared and defaulted but
never referenced by any resource is dead. `terraform plan` output or a `grep` for `var.<name>` per
module gets most of the way. Split by cloud, and treat AWS as the likelier source of dead entries.

---

## Related finding: `app_auth_*` is the E2E account, not the demo account

`TF_VAR_app_auth_email` / `_password` / `_user_id` / `_name` are fed from
`secrets.E2E_TEST_USER_*` in both `terraform.yml:29-32` and `terraform-azure.yml:70-73`, and
`app_auth_name` falls back to the literal `'Demo User'`.

Nothing in Terraform touches the recruiter demo account, whose authority is the bcrypt hash in
`V15__Reconcile_Auth_Seed_Users.sql`. But two accounts with overlapping naming is how the wrong one
gets rotated — someone reading `app_auth_password` reasonably assumes it is the demo login.

Suggested: rename to `e2e_auth_*` and drop the `'Demo User'` fallback. Renaming a Terraform
variable is a plan-visible change with no state migration, so this is cheap whenever the file is
open for another reason.
