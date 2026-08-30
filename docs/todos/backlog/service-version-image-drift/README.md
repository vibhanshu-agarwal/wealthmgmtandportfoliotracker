# Backlog: `SERVICE_VERSION` does not match the running image on `api-gateway` and `portfolio-service`

**Status:** Open — found 2026-08-31
**Owner:** unassigned
**Tracked in:** Surfaced by the Spec A 9.14 live read-back
([`SPEC_A_9_14_REOPEN_INGRESS.md`](../../../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md)). Pre-existing;
not caused by 9.14, which changed ingress only.

---

## What is wrong

Two of four services advertise a `SERVICE_VERSION` that is not the image they are running:

| Service | `SERVICE_VERSION` env | Running image tag | |
|---|---|---|---|
| `api-gateway` | `9b2cf0d6…6900` | `63fc0584…6b76` | **mismatch** |
| `portfolio-service` | `9b2cf0d6…6900` | `d5693e29…092a` | **mismatch** |
| `market-data-service` | `9b2cf0d6…6900` | `9b2cf0d6…6900` | match |
| `insight-service` | `9b2cf0d6…6900` | `9b2cf0d6…6900` | match |

The two tags on `api-gateway` are genuinely different images, not two tags on one manifest:
`9b2cf0d6…` is `sha256:ff80395e…`, `63fc0584…` is `sha256:79a3f253…`.

Note also that `portfolio-service`'s running tag is a 64-hex string, which does not match the
40-character `SERVICE_VERSION` format the dispatch contract documents.

## Root cause

The two fields have different owners and drift independently:

- `SERVICE_VERSION` is an env var, and Terraform manages it.
- The container image is explicitly **not** Terraform-managed. `modules/container-app/main.tf`
  carries `lifecycle { ignore_changes = [ template[0].container[0].image ] }`, with images updated
  out-of-band by `deploy-azure.yml` via `az containerapp update`.

So an image rollout that does not also update `SERVICE_VERSION` through Terraform leaves the env var
stale, and nothing reconciles them.

## Why it matters

`SERVICE_VERSION` is the version-bearing signal used for serving-identity claims across B1
(V20 / R-B2 / Writer_Convergence work). If it can disagree with the deployed artifact, any claim
resting on it is unsound — a service can report a version it is not running. This is the same
evidence-oracle failure mode catalogued elsewhere in this program: the measured signal drifted from
the claimed one.

## What to do

1. Decide the intended invariant: is `SERVICE_VERSION` meant to name the running image, or the
   Terraform-declared intent? They cannot be both while `ignore_changes` stands.
2. Reconcile the two live mismatches.
3. Add a guard that fails when `SERVICE_VERSION` diverges from the deployed image tag — a plan-time
   assertion cannot see this, so it likely belongs in a live read-back check.
4. Cross-reference [`deployed-image-tags-json-validation`](../deployed-image-tags-json-validation/README.md);
   both stem from live image identity being unverified.

## Non-claims

- Does **not** authorize an image rollout, deployment, or Terraform apply.
- Does **not** assert which of the two values is correct for either service.
