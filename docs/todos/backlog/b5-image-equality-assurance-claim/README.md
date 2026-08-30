# Backlog: B5 "image byte-for-byte unchanged" is structurally non-falsifiable

**Status:** Open — found 2026-08-31
**Owner:** unassigned
**Tracked in:** Surfaced while closing Spec A 9.14
([`SPEC_A_9_14_REOPEN_INGRESS.md`](../../../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md)); applies to the
9.14 acceptance contract in the
[review orientation note](../../../superpowers/plans/2026-08-30-spec-a-9.14-reopen-ingress-review-orientation.md).

---

## What is wrong

The Spec A 9.14 acceptance contract states:

> **B5** — Gateway image is byte-for-byte unchanged

and `assert_spec_a_9_14_plan.py` does implement the comparison, failing when the plan's `before` and
`after` container images differ.

That comparison can never fail. `modules/container-app/main.tf` carries:

```hcl
lifecycle {
  ignore_changes = [ template[0].container[0].image ]
}
```

Terraform therefore never proposes an image change, so `before` and `after` are equal **by
construction** for every plan, under every profile, regardless of drift. B5 is satisfied
tautologically. The same reasoning applies to the equivalent image checks in the 9.9 / 9.12 / 9.13
guards, which should be audited rather than assumed.

## Why it matters

B5 reads to a reviewer as protection against an image swap riding along with a scoped change. It
provides none. A reviewer who credits it — as happened during the 9.14 plan review — over-counts the
assurance the guard set delivers. The verdict was still correct, because B1's exact-scope rule and
B7's field compare do real work, but B5 contributed nothing.

This is the same evidence-oracle pattern catalogued elsewhere in this program: the check's measured
signal (plan-level before/after equality) drifted from its claimed signal (the deployed image is
unchanged).

## What to do — pick one

1. **Remove B5** from the acceptance contract and delete the check, on the grounds that image
   management is out of Terraform's scope by design. Cleanest, and honest.
2. **Reframe it** as a live-state assertion rather than a plan assertion: compare the deployed image
   before and after apply via `az containerapp show`. That measures what B5's wording claims.
3. At minimum, **annotate** the contract so future reviewers know B5 is a no-op invariant and do not
   count it toward coverage.

Whichever is chosen, audit the sibling guards for other invariants rendered vacuous by
`ignore_changes`, and check whether any prior checkpoint record cites B5-style image stability as
evidence.

## Related

- [`deployed-image-tags-json-validation`](../deployed-image-tags-json-validation/README.md)
- [`service-version-image-drift`](../service-version-image-drift/README.md)

## Non-claims

- Does **not** invalidate the 9.14 `ACCEPT` verdict or the applied outcome; both stand on the
  exact-scope and field-compare invariants, which are falsifiable and did real work.
- Does **not** authorize guard changes; this records the finding for a separate, reviewed change.
