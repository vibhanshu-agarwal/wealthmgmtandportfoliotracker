# Backlog: `deployed_image_tags_json` validation proves ACR existence, not live deployed identity

**Status:** Open — found 2026-08-31
**Owner:** unassigned
**Tracked in:** Surfaced by the Spec A 9.14 live read-back
([`SPEC_A_9_14_REOPEN_INGRESS.md`](../../../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md)).

---

## What is wrong

The `deployed_image_tags_json` dispatch input for `.github/workflows/terraform-azure.yml` is
documented as:

> the full 40-character `SERVICE_VERSION` tag **currently deployed** for that service. Records live
> image identity for truthful planning.

The validation step (`Confirm deployed_image_tags_json resolves in ACR`) checks only that each tag
**resolves to exactly one manifest in ACR**. It never compares the tag against what is actually
deployed. A tag that exists in the registry but is not running passes the gate.

This is not hypothetical. Both the reviewed 9.14 remote-plan and the 9.14 apply were dispatched with
all four services declared as `9b2cf0d6…6900`, while live state was:

| Service | Declared | Actually deployed | |
|---|---|---|---|
| `api-gateway` | `9b2cf0d6…6900` | `63fc0584…6b76` | **untrue** |
| `portfolio-service` | `9b2cf0d6…6900` | `d5693e29…092a` | **untrue** |
| `market-data-service` | `9b2cf0d6…6900` | `9b2cf0d6…6900` | true |
| `insight-service` | `9b2cf0d6…6900` | `9b2cf0d6…6900` | true |

Two of four values were false and every gate passed, including the exact-scope guards.

## Blast radius on 9.14 — none, but only incidentally

The untrue values could not move the image, because
`modules/container-app/main.tf` carries
`lifecycle { ignore_changes = [ template[0].container[0].image ] }`. Terraform ignores the image
field entirely, so `TF_VAR_image_tags` did not drive the gateway's image in this plan. The 9.14
apply was correct and ingress-only.

That is luck of the configuration, not a property of the gate. Any future use of these values for a
purpose Terraform *does* act on would carry the untruth straight through.

## What to do — pick one

1. **Make it prove what it claims.** Extend the validation step to read live deployed identity
   (`az containerapp show … --query properties.template.containers[0].image`) and fail closed when a
   declared tag does not match. This makes the input's name and documentation honest.
2. **Or re-document it.** If ACR existence is the intended contract, rename the input (e.g.
   `acr_image_tags_json`) and rewrite the description to stop claiming live deployed identity, so no
   future reviewer credits it with assurance it does not provide.

Option 1 is preferable: the input's stated purpose is truthful planning, and downstream records cite
it as evidence of serving identity.

## Related

- [`service-version-image-drift`](../service-version-image-drift/README.md) — the same unverified
  live identity, seen from the env-var side.
- [`b5-image-equality-assurance-claim`](../b5-image-equality-assurance-claim/README.md) — the guard
  invariant that appears to cover this and does not.

## Non-claims

- Does **not** assert any past checkpoint reached a wrong live outcome. 9.14's outcome was correct.
- Does **not** authorize workflow dispatch, deployment, or Terraform apply.
