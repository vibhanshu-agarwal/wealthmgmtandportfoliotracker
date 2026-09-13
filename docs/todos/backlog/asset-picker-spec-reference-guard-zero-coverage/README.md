# Backlog: Asset Picker spec-reference guard reports zero coverage

**Status:** Open — pre-existing tooling signal gap confirmed while reviewing the Task 8.9
post-merge documentation candidate.
**Owner:** unassigned
**Tracked in:** [independent Fable review](../../../superpowers/plans/fable-review-task-8-9-postmerge-docs-20260913.md),
[round-2 Fable review](../../../superpowers/plans/fable-review-round2-task-8-9-postmerge-docs-20260913.md),
[`check-spec-references.py`](../../../../scripts/check-spec-references.py), and the Asset Picker
[`tasks.md`](../../../../.kiro/specs/asset-picker-composition/tasks.md) / [`requirements.md`](../../../../.kiro/specs/asset-picker-composition/requirements.md)
pair.

---

## What is wrong

Running `scripts/check-spec-references.py` against the Asset Picker `tasks.md` and
`requirements.md` with `--coverage` exits `0` while reporting:

```text
contiguous: 0 requirements {}
dangling: none (0 references checked, 0 with sub-clauses)
0/0 criteria cited by at least one task
```

The same vacuous result occurs on the baseline and the current documentation candidate. The
script's self-test passes, but this specification contributes a zero denominator, so the command
cannot detect missing requirement-to-task references here. Treating its exit `0` as meaningful B2
coverage evidence would be misleading.

## Related review-inventory finding

Convention counts must use one explicit, baseline-aware population. At `main@80b881b5`, the
tracked backlog baseline contains 21 `README.md` files: `Status:` appears in 21, `Owner:` in 13,
and `Tracked in:` in 12. Counting the candidate's two new READMEs produces 23/15/14 and lets the
files under review supply votes for their own convention. The conclusion here is unchanged — all
three labels are baseline conventions, with `Status:` universal and the other two majorities — but
future convention checks should be scripted and should report the baseline and candidate additions
separately.

## Acceptance criteria

- Establish a non-zero, explicitly defined coverage denominator for the Asset Picker specification,
  or make the guard fail closed when an in-scope specification yields `0/0`.
- Add a regression case proving the guard cannot pass vacuously for a substantial in-scope task
  ledger.
- Preserve the existing self-test coverage and validate the corrected command against both a
  conforming and deliberately under-referenced fixture.
- Document which requirement syntax the guard recognizes so future specification changes cannot
  silently remove all coverage signal.
- Script review-time convention inventories so they identify the baseline revision, exclude
  candidate additions from the baseline count, and report each numerator and denominator from the
  same file population. This review helper may be implemented separately from the spec-reference
  guard; it is consolidated here as a related false-signal problem.

## Non-claims

- This pre-existing gap is not evidence that the Task 8.9 documentation candidate is semantically
  correct or incorrect.
- It does not block Run A attempt 3 or authorize any Production action.
- It is not resolved by the unrelated 33-test master-plan status-propagation suite.
