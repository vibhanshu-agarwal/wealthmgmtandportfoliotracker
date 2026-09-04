# B1 Task 7.6 / GC.5 coverage-closure + R3 preflight — return packet (revision 5, ACCEPTED with wording fixes)

**Status: bounded analyzer correction ACCEPTED by Codex's fourth review (§0d); a local publication
approval request is the next step, not yet made.** No owner decision has been requested by this
packet itself. R-C stays **NO-GO**; nothing here publishes, deploys, or authors a
governance/disposition record. Final guard suite: **246 tests, 0 failures, 585.7s**. The stale-count
test stays fixed at its actual mechanism (pinned to the fixed cut, not `HEAD`), not merely documented.
**The real-tree finding count moved from 506 to 507 this round, on purpose, per the third review's
explicit instruction not to preserve 506 by
suppressing the residual: see §0c.**

## 0. Response to the 2026-09-04 Codex review

Codex's independent reproduction (`gc5_coverage_review_probes.py`, 12 fixtures against both the
accepted baseline analyzer and mine) found four real correctness defects in revision 1 of this work.
I reproduced all twelve fixtures myself against my own worktree before touching anything, confirmed
every one, then fixed each at its root cause — not by narrowing the fix's scope back toward the
original bug, but by making each mechanism's soundness match what the return packet already claimed
for it. Re-running the same twelve fixtures afterward: all four defect fixtures now match the accepted
baseline's blocking behavior exactly, and all three genuinely-safe positive-control fixtures
(`helper_safe`, `script_diagnostic_safe`, `repository_short_annotation`) still clear correctly — no
regression on the mechanisms revision 1 got right.

| # | Finding | Root cause found | Fix |
|---|---|---|---|
| 1 | Helper propagation (S4) accepted an incomplete caller/value set | Qualified calls were silently *skipped* rather than either verified or treated as proof the caller set is incomplete; the propagated parameter was never checked for reassignment inside the helper itself; no visibility gate, so a `public` method's (unenumerable) external callers were never considered | `private`-only gate on the enclosing method; `_parameter_reassigned` scans the whole method body for any assignment to the propagated parameter and bails if found; `this.`-qualified calls are now counted as real callers (their argument must still resolve and be read-only); any OTHER qualifier makes the full caller set unprovable and bails the whole mechanism |
| 2 | Diagnostic (`raise`) exemption swallowed evaluated subexpressions | `ast.walk(arg)` recursed into ANY nested node, so a string argument to a call *inside* the raise's argument (`raise X(db.execute("DELETE ..."))`) got marked exempt even though that call is evaluated — and may execute SQL — before the exception is even constructed; an f-string's exemption covered its whole outer span, so an interpolated `{db.execute(...)}` call's argument was swallowed the same way | Replaced the recursive walk with a non-recursive check: only a `Constant` string that IS the whole argument, or a `JoinedStr`'s own literal `Constant` fragments (never a `{...}` interpolation's expression), is ever marked |
| 3 | Fully qualified `@Query`/`@Modifying`/`@Procedure` bypassed repository detection | The check compared only the token immediately after `@` to `("Query","Modifying","Procedure")`; `@org.springframework.data.jpa.repository.Modifying`'s first token is `org`, not `Modifying` | `_annotation_simple_name` walks the full dotted chain and compares its LAST segment, so a short and a fully qualified spelling resolve to the same identity |
| 4 | Mapped-owner map was a flat, name-keyed, last-write-wins merge | Two different `@Entity` classes declaring a same-named mapped field silently let whichever file was scanned last "win" the owner for that name repo-wide, misattributing one entity's mutation to the other; a LOCAL variable shadowing a real mapped field (same method, same name) was swept into the mapped-collection mechanism with no shadow check at all | `mapped_collection_names` now returns `name -> set of owner classes`; `resolve_receiver` uses the single candidate directly, or — when there are several — only the one matching the mutation's own `enclosing_type` (direct, sufficient proof); anything still ambiguous is `UNRESOLVED`, never guessed. A new `_locally_declared_names`/parameter check excludes a bare mapped-field reference from the mechanism entirely when a local variable or parameter of the same name is declared in that method, matching real Java scoping |

A fifth defect surfaced only while re-verifying fix #2 against the real repository, not by the
reviewer's fixtures: the offset math converting `ast` node positions to character offsets was wrong
whenever a line contained a non-ASCII character before the position in question — `ast.col_offset` is
a **UTF-8 byte offset**, not a character offset (a documented CPython quirk that diverges from
`tokenize`, which IS character-based). An em dash (`—`) in `check_b2_demo_identity.py`'s "ambiguous
demo identity" `GuardError` message shifted the exempt span by one character, silently un-exempting
that real GC5-0486 diagnostic after fix #2 landed. Found via the real-tree delta (a `writer-inventory`
`UNSUPPORTED` finding reappeared that revision 1 had correctly cleared), root-caused, and fixed by
decoding the UTF-8 byte-prefix back to text before combining it with the line's character offset.

Findings 5 (test-gate/packet-accounting) and 6 (R3 scope) are addressed in §3 and §5 below
respectively — the stale test is now fixed at its actual mechanism (pinned to the fixed cut, not
`HEAD`), not merely documented, and the packet's delta/removal arithmetic and R3 role-identity claim
are corrected to match what the evidence actually establishes.

## 0b. Response to the SECOND (2026-09-04) Codex review

Codex ran the twelve original probes again (confirmed passing) plus seven NEW adjacent probes
(`adjacent_probes.py`) against the revision-2 analyzer, and read the R3 catalog output directly,
verifying its hash. All seven adjacent probes reproduced independently before I touched anything.
Three of my revision-2 mechanisms had real remaining false negatives; the fourth topic (R3) needed a
wording correction, not a mechanism fix. Per the review's explicit preference, every fix below chooses
a **conservative blocking fallback** over building a more general prover — the real repository's
finding count did not move as a result (§4), because none of these are close, edge-case shapes that
happen to occur in the actual codebase; they are soundness gaps a synthetic adversarial fixture can
reach but this repository's real source does not.

| # | Finding | Root cause found | Fix |
|---|---|---|---|
| 1 | S4 still cleared an escaped or reassigned parameter | (a) `this::q` method references let a private helper escape as a value and be invoked indirectly (`c.accept(...)`), invisible to the same-name-call scan that "closes" the caller set; (b) `lex_java` never emits a single `+=` token (`sql += "x"` lexes as four separate tokens `sql`,`+`,`=`,string), so the reassignment guard's literal `"+="` check never matched anything | `_method_is_referenced` scans the whole unit for `Name :: method_name` (two separate `:` tokens) and bails S4 entirely if found; `_parameter_reassigned` now also recognises `param + =` (and other compound-operator shapes) as a reassignment, not only a bare `param =` |
| 2 | Method-wide shadowing suppressed real mapped-field mutations | `_locally_declared_names` collected every local name declared ANYWHERE in the method and blanket-suppressed every same-name bare mutation, with no qualifier check and no notion of declaration position/block scope — `this.holdings.clear()` satisfied the same "token before the dot equals the mapped name" test as a genuinely local `holdings.clear()`, and a local declared AFTER a field reference (or inside a block that had already closed) still wrongly shadowed it | Replaced with `_bare_reference_is_a_local`: a linear, brace-depth-tracking scan that only "activates" a local's shadow at the `;` ending its OWN declaration statement, and deactivates it when its OWN enclosing block closes — so position and nested scope are now real proof, not assumed. `this.field` is also now excluded from the shadow check entirely at the call site: `this.x` can only ever mean the field in Java, never a local or parameter |
| 3 | A whole diagnostic argument was still not proof of an inert constructor | Any bare `raise Name(...)` was exempted regardless of what `Name` actually does — a locally redefined class with a dangerous `__init__`, or `Name` shadowed by a plain function entirely, both satisfied the same "bare call, string argument" shape as the real, inert `GuardError` | New `_inert_exception_class_names`: proves a class inert ONLY from its own definition in the SAME file — exactly one base (`Exception`/`BaseException`, or another name already proven inert), no `__init__`/`__new__`/`__call__` override, and not also defined as a function or redefined as a second class anywhere in the module. `raise Name(...)` is exempted only when `Name` is in that proven set. **No exception-name allowlist**: a bare, unshadowed use of a genuine builtin like `RuntimeError` that is never locally defined in the file can no longer be proven inert by this file-local mechanism, so it is now a documented residual (stays blocking) rather than an exemption — exactly the "higher honest finding count" the review said was acceptable |

All seven of the review's `adjacent_probes.py` fixtures now match the accepted baseline's blocking
behavior; the twelve original `gc5_coverage_review_probes.py` fixtures were re-run too and still match
(no regression from these three fixes). One nested-block fixture (`mapped_after_nested_scope`) shows
one finding after these fixes instead of the baseline's two by design, not as a residual gap: the
in-block local `.clear()` correctly resolves to an ordinary `List` (no finding at all, matching
`test_unmapped_local_collection_clear_is_not_a_subject`'s existing precedent), and the post-block
reference correctly resolves to the field (`RELEVANT`, blocking) — verified directly against the
`basis` evidence, not just the finding count.

**R3 wording (review finding, this round):** the review confirmed the catalog evidence itself
("substantive... support the risk finding for the inspected database and `neondb_owner` role") but
asked for the risk conclusion's wording to consistently say "the inspected credential set and
database," never implying the name-based Terraform/workflow wiring trail is a verified current
production connection. §5's "Interpretation" paragraph is rewritten to anchor the conclusion on the
inspected credential set alone, with the deployment-wiring trail kept as separate, explicitly weaker
context. No new query, reconnection, or secret access was needed or performed this round.

Durable regressions added this round (11 new tests, all passing — see §2/§3 for names): the exact
`this::q` escape and `+=` reassignment negatives (Finding 1); explicit-`this.`, before-declaration, and
after-block-close negatives for the mapped-field shadow fix (Finding 2), keeping the existing
genuinely-local and mapped-collision positives; the dangerous-`__init__` and function-shadowed-name
negatives, plus a new "unproven familiar name stays blocking" positive documenting the accepted
residual (Finding 3).

## 0c. Response to the THIRD (2026-09-04) Codex review

Codex re-ran the original twelve probes and the seven adjacent probes against the revision-3
analyzer (both sets confirmed passing, per the review's own text) and did not rerun the full suite or
whole real-tree analysis, judging one new counterexample class sufficient to decide acceptance. That
counterexample: `_inert_exception_class_names` (added in revision 2 to fix finding #3 of the second
review) proved a class's constructor "inert" only from FunctionDef nodes in its own definition — it
never accounted for a constructor assigned via lambda, the class name being rebound after definition,
a class decorator replacing the class, a parameter shadowing the class name at the raise site, the
`Exception` base name itself being rebound, or a post-definition `Name.__init__ = ...` attribute
write. All six are reproduced in `exception_binding_probes.py`, each showing the same shape: the
"inert" fixture's constructor executes `db.execute(message)` at raise time while the analyzer's
definition-based proof still calls it clearance-eligible.

I reproduced all seven of the review's `exception_binding_probes.py` fixtures myself (the positive
control plus the six negatives) against my own worktree before making any change, confirming every
one: the positive control correctly stayed exempt, and all six negatives incorrectly cleared to no
writer finding. The review's own recommendation — disable the mechanism rather than attempt a fourth
patch — is the one implemented, per its explicit reasoning that soundly ruling out every way a Python
name can be rebound or a class transformed is an open-ended metaprogramming/name-resolution problem,
not a bounded one.

**Fix:** removed `_inert_exception_class_names` and the entire `ast.Raise`-handling block from
`_python_non_executable_spans` (`scripts/check_b1_candidate_source.py`). The function now marks only
two proven-non-executable Python shapes — `#` comments (`tokenize`) and module/class/function
docstrings (`ast`, still using the UTF-8-byte-offset fix from revision 2) — never a `raise Name(...)`
argument, regardless of what `Name` is or appears to be.

Re-running `exception_binding_probes.py` against the fixed analyzer: all seven fixtures, including
the previously-clearing positive control, now show `after` matching `before` exactly (`writer-inventory
UNSUPPORTED`, `script:DELETE:1556622d`) — the mechanism no longer exists to clear any of them. Re-running
both `gc5_coverage_review_probes.py` (round 1, 12 fixtures) and `adjacent_probes.py` (round 2, 7
fixtures) confirms every other previously-fixed mechanism (S4 helper propagation, mapped-owner
disambiguation, qualified-annotation detection, shadow detection) is unaffected, and their own
`script_diagnostic_safe`/`script_custom_exception`/`script_builtin_shadow` fixtures now correctly stay
blocking too, consistent with the stricter behavior.

**GC5-0486 status change:** `check_b2_demo_identity.py`'s GuardError diagnostic, cleared in revision 2,
is now a **documented, retained coverage residual** (`writer-inventory`, `UNSUPPORTED`,
`script:INSERT:3575989d`) — not a clearance, and not hidden. This is the ONLY real-tree change this
round: a fresh fixed-cut CLI run shows every other `(obligation, path, subject_id)` key byte-identical
to revision 2's report, and this one row reappearing exactly as it existed in the original 540-finding
baseline. Total real-tree findings: **506 → 507**, honestly reported per the review's explicit
instruction not to preserve 506 by suppressing the residual or changing the source diagnostic — no
count-preservation was attempted (§4).

**Return requirements checklist (verbatim from the review's own list):**
1. Removed/disabled the unproved diagnostic clearance — done, without touching runtime scripts,
   policy, or any other mechanism.
2. Re-ran the previous nineteen probes (12 + 7) and the six new binding negatives (part of the seven
   `exception_binding_probes.py` fixtures), retaining the safe comment/docstring controls — all pass;
   the real diagnostic's expectation is updated honestly to "remains blocked" (§2/A2, §3, §4).
3. Ran focused durable tests (`UnenumeratedMutationTests`, `PostConsolidationSqlAwareReadTests`,
   `RealRepoSmokeTests`), then one final full guard suite and fixed-cut CLI — §3.
4. Final file hashes, actual test counts, evidence paths and explicit residuals — §4.

Durable regressions added this round (in `UnenumeratedMutationTests`): repurposed
`test_bounded_diagnostic_raise_literal_is_a_documented_residual` (was: asserted GC5-0486 cleared; now:
asserts it stays a single blocking `UNSUPPORTED` finding), repurposed
`test_docstring_with_a_non_ascii_character_still_exempts_correctly` (the UTF-8-byte-offset regression
moved from a raise fixture, the mechanism it originally exercised having been removed, to a docstring
fixture — the only remaining consumer of that offset arithmetic), and a new consolidated
`test_raise_argument_is_never_exempted_regardless_of_shape` covering the plain case, both nested-call
risks, a non-call/re-raised variable, a dotted/qualified exception constructor, and the six
binding/override/escape shapes the third review found — all eleven asserting blocking, none
asserting clearance. The now-redundant `test_raise_of_an_unproven_familiar_name_stays_blocking`,
`test_raise_constructor_with_a_dangerous_init_still_blocks`,
`test_raise_constructor_shadowed_by_a_local_function_still_blocks`,
`test_call_nested_inside_a_raise_argument_still_blocks`,
`test_call_nested_inside_an_fstring_interpolation_in_a_raise_still_blocks`, and
`test_raise_of_a_non_call_or_qualified_exception_stays_blocking` were consolidated into the one test
above rather than kept as separate near-duplicates now that they all assert the same universal
"never exempted" behavior.

## 0d. Fourth review: ACCEPTED, with two non-blocking wording corrections

Codex accepted the bounded analyzer correction: "No remaining blocking finding in the reviewed
correction." Codex independently re-ran all 26 reviewer probes (12 + 7 + 7, all matching expected
blocking/clearing behavior), ran `UnenumeratedMutationTests` + `PostConsolidationSqlAwareReadTests`
directly (64 tests, OK, 59.2s), ran the actual guard CLI at the fixed cut independently (507 findings:
251/223/26/7), and compared complete finding objects against both the previous 506-row report (one
added, zero removed, zero changed — GC5-0486) and the original 540-row report (3 added, 36 removed, 10
changed) — all matching this packet's own figures. The reviewed full 246-test guard-suite run was
noted as reported by Claude, not independently re-executed by the reviewer, and out of the reviewer's
own verification scope for this round.

Two non-blocking wording corrections, both verified before fixing (per
`superpowers:receiving-code-review`'s verify-before-implementing mandate) rather than applied on
trust:

1. **Fixture-count prose.** §2/A2 and §3 (then at lines 150/259) described
   `test_raise_argument_is_never_exempted_regardless_of_shape` as "thirteen" fixtures. Counted the
   actual tuple in `scripts/tests/test_check_b1_candidate_source.py` directly (via `ast`, walking the
   `for` loop's tuple literal): **11 elements**, not 13 — confirms the review's count. Corrected the
   prose in both places to "eleven" / "11 fixtures". Per the review's own instruction, no tests were
   added to reach the stale prose's number — the test file itself was never wrong, only this packet's
   description of it.
2. **Stale analyzer comment.** Confirmed `scripts/check_b1_candidate_source.py:2853` still read
   `"a match fully inside a proven comment/docstring/diagnostic span"` — a leftover from before
   revision 4 removed the diagnostic-raise exemption entirely (§0c). Corrected to
   `"comment/docstring"`, with `"a raise argument"` added to the following line's list of spans that
   are NOT exempt (previously implicit, now explicit, since a reader could otherwise wonder why
   `raise` isn't mentioned at all). This is a comment-only edit: `ast.parse` re-confirms the file still
   parses, and `UnenumeratedMutationTests` (20 tests) re-run clean, proving no executable line moved.

Per the review's explicit instruction, this comment-only edit produces a new file hash, kept distinct
from the hash Codex actually reviewed and accepted (§4): the reviewed executable-logic snapshot is
`d543127b...` (unchanged since revision 4); the final published snapshot, after this wording fix, is
`090aeb9f...`. The diff between them is exactly the one comment line above — nothing else.

**What remains open, per the review's own framing (not resolved by this checkpoint):** seven
`UNSUPPORTED` findings (six persistence-usage + the restored GC5-0486 script diagnostic), the
remaining unresolved receiver/routine effects, and all other governance/R3/registry-serving work in
§6. R-C stays **NO-GO**. Per the review's explicit "next" instruction, the next step is consolidating
the exact local package (this packet, preserved verification evidence, the residual coverage list) and
presenting an explicit publication approval request — not reopening the exception-constructor proof
problem, not suppressing GC5-0486, and not describing the remaining governance work as merely applying
dispositions. **No commit, push, PR, disposition, envelope record, live-database change, release
build, registry operation, or deployment has been performed** — this remains a local working-tree
correction awaiting the owner's explicit publication decision.

## 1. Scope executed

- **Worktree:** `D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-claude` (confirmed
  via `git rev-parse --show-toplevel` before any edit, per `AGENTS.md`).
- **Branch:** `claude/b1-gc5-coverage-closure`, created from HEAD `2fc14e2fc20e11ff96d7d21d8031f3dc89c8e511`
  (tree `ab7a2ec4e091fcbda561430f0c1e7ec8bfa82f4a`, identical to the fixed cut
  `9c3add3cd3a38ff94c2196b20e636eeb5bfa4315`'s tree — verified before editing).
- **No implementation commit was made** for this checkpoint (per the kickoff's own instruction); the
  working tree on this branch carries the changes described below.
- **Changed files** (matches the kickoff's expected edit set exactly):
  - `scripts/check_b1_candidate_source.py`
  - `scripts/tests/test_check_b1_candidate_source.py`
  - `docs/runbooks/B1_R_C_CANDIDATE_VERIFICATION.md`
  - this file (checkpoint handoff, now revision 2)
- **Untouched, preserved:** `scripts/b1-candidate-policy.json` (hash unchanged, see §4), all other
  producer scripts, Dockerfiles, Gradle/CI wiring, the master plan, the B1 ledger, and
  `docs/superpowers/plans/2026-09-04-b1-rc-pr222-session-handoff.md` (the pre-existing untracked file
  noted as present at last review).

## 2. Workstream A — 45-row reconciliation

Every row below is the original triage ID from `B1_GC5_COVERAGE_WORK_QUEUE.md`, measured against the
fixed cut `9c3add3cd3a38ff94c2196b20e636eeb5bfa4315` with the FINAL, post-review analyzer.

### A1 — mapped-collection effect defects (GC5-0444, GC5-0445)

**Root cause:** `resolve_receiver` retained an earlier `List -> memory` classification through
`store = store or STORE_POSTGRES` — `or` never overrides an already-truthy value, so a genuinely
cascade-mapped `Portfolio.holdings` kept reporting `UNRELATED` ("disjoint from the relational
tables"), which is false: `@OneToMany(cascade = ALL, orphanRemoval = true)` genuinely persists
add/clear through Hibernate.

**Fix (revision 1, unchanged by review):** `mapped_collection_names` returns `{field/getter name ->
owning class}` (the class the annotation was actually found on), not a bare name set.
**Fix (revision 2, review finding #4):** the value is now a SET of owning classes, not one — two
unrelated entities can register the same field/getter name. `resolve_receiver` uses the single
candidate directly when there is only one; when there are several, ONLY the mutation's own
`enclosing_type`, if it is among the candidates, disambiguates it; anything still ambiguous is
`UNRESOLVED`. A bare mapped-field reference is also now checked against locally-declared
names/parameters in the SAME method first (`_locally_declared_names`) — a local variable shadowing
the field is never swept in.

- **GC5-0444** `Portfolio::addHolding/1::holdings.add#0`: `UNRELATED` → `RELEVANT` ("touches
  portfolios"), still `UNREVIEWED` (no disposition exists) — corrected evidence, same blocking status.
- **GC5-0445** `Portfolio::replaceAllHoldings/1::holdings.clear#0`: same correction.
- **Untargeted delta (same mechanism, not separately queued):**
  `HoldingReplacementService::applyChildren/2::getHoldings.clear#0` moved `UNRESOLVED` → `UNREVIEWED`
  (`RELEVANT`) — the cross-file `portfolio.getHoldings().clear()` accessor call, covered by the exact
  same owner-proof fix.

Regressions: `test_mapped_collection_effect_is_relevant_not_a_fabricated_unrelated`,
`test_ownership_that_cannot_be_proved_stays_unresolved_not_forced_postgres`,
`test_renamed_mapped_field_is_recognised_by_annotation_not_by_the_name_holdings` (revision 1); added
this round: `test_mapped_owner_collision_disambiguated_by_the_mutations_enclosing_class`,
`test_mapped_owner_cross_file_collision_disambiguated_by_the_mutations_enclosing_class`,
`test_mapped_local_variable_shadowing_the_field_is_not_swept_in` — the exact three shapes Codex's
`mapped_collision`/`mapped_cross_file_collision`/`mapped_local_shadow` fixtures probed, now durable in
the actual suite.

### A2 — script data vs. execution (GC5-0484, GC5-0485, GC5-0486)

**Root cause:** `script_dml_subjects` scanned the raw joined source text with one regex, so this
analyzer's own `#`-comment SQL examples and a function docstring's prose mention of
"insert into portfolios" matched exactly like real executable SQL.

**Fix (revision 1):** `_python_non_executable_spans` (stdlib `tokenize` + `ast` only) marks three
PROVEN non-executable spans in a `.py` file: `#` comments, module/class/function docstrings, and a
string (or f-string) that is the whole/joined argument of a bare `raise Name(...)`.
**Fix (revision 2, review finding #2):** the marking is no longer a recursive `ast.walk` over the
raise argument — it only marks a `Constant` argument that IS the whole string, or a `JoinedStr`'s OWN
literal `Constant` fragments, never descending into a nested call or an interpolation's expression, so
`raise X(db.execute("DELETE ..."))` and `raise X(f"... {db.execute('DELETE ...')}")` both stay fully
visible to the ordinary scan.
**Fix (revision 2, offset bug found during re-verification):** `ast` positions are UTF-8 byte offsets,
not character offsets; a non-ASCII character earlier on the same line (an em dash, in the real
GC5-0486 case) silently misaligned the exempt span by one character after the finding-#2 fix landed.
Fixed by decoding the line's UTF-8 byte-prefix back to text before adding it to the line's character
offset.
**Fix (revision 3, THIRD review finding — see §0c):** the raise-argument exemption itself, including
revision 2's `_inert_exception_class_names` constructor-inertness proof, was removed entirely rather
than patched a fourth time — a definition-based proof cannot soundly rule out a lambda-assigned
`__init__`, class-name rebinding, a class decorator, parameter shadowing, base-name rebinding, or a
post-definition attribute write. `_python_non_executable_spans` now marks only comments and
docstrings; no `raise Name(...)` argument is ever exempted.

- **GC5-0484** (`INSERT INTO t` in a `#:` comment): no longer reported.
- **GC5-0485** (`insert into portfolios` in a function docstring): no longer reported.
- **GC5-0486** (`check_b2_demo_identity.py`'s `GuardError`, `script:INSERT:3575989d`): cleared in
  revision 2, **reinstated as a documented, retained residual in revision 3** — stays a blocking
  `writer-inventory` `UNSUPPORTED` finding, exactly as in the original 540-finding baseline. Human
  review can account for this exact diagnostic alongside the other retained coverage gaps in §2/A3 and
  §6; it is not a clearance and this checkpoint does not claim it is.

Regressions: 8 tests from revision 1 (comment/docstring exemptions, moved-to-sink and
variable-carrying-SQL negatives, malformed-script negative, non-Python negative, plus three
raise-specific tests since repurposed — see next line); revision 2 added three raise-specific tests,
all since repurposed in revision 3 (§0c) into
`test_bounded_diagnostic_raise_literal_is_a_documented_residual`,
`test_docstring_with_a_non_ascii_character_still_exempts_correctly`, and the consolidated
`test_raise_argument_is_never_exempted_regardless_of_shape` (11 fixtures, including the third review's
six binding/override/escape negatives).

### A3 — persistence usage, 33 rows

Reconciled into three buckets (33/33 accounted, IDs from the queue):

**Token/type false match (marker check switched from raw substring to exact code-token/annotation
match)** — 19 file-level rows cleared: GC5-0402 (`DataSource`/`JdbcTemplate` inside
`DataSourceAutoConfiguration`/`JdbcTemplateAutoConfiguration`), 0406, 0407 (`Session` inside
`JwtSessionIdentity`), 0410, 0413 (`JdbcTemplate` inside `NamedParameterJdbcTemplate`), 0417, 0418
(the exact cited example), 0419, 0420, 0422, 0423, 0424, 0429, 0439, 0440, 0441 (`Connection` inside
`RedisConnectionFactory`/`getConnection`, `JdbcTemplate` already resolved separately), 0446, 0461,
0483. Of these, four (0424, 0439, 0446, 0483) are the generic-repository `*Repository<` interface
files, cleared by BOTH the token-exact fix AND the repository-declaration recognition itself:
`AssetPriceRepository`/`AssetHoldingRepository`/`PortfolioRepository`/`UserRepository` carry no
`@Query`/`@Modifying`/`@Procedure` method (short OR fully qualified, per review finding #3) and no
`delete`/`removeBy`-prefixed derived method, so they are a recognised declaration-only Spring Data
shape (`_repository_markers_are_plain`) — a hand-written default/static method with a real body is
unaffected, since any write inside one is still caught by the ordinary per-method-body scan.

**Accounted invocation** — 8 rows: GC5-0412 (`SignupService`'s `TransactionTemplate`, used only via
the recognised `.execute(status -> ...)` callback wrapper), GC5-0469 (`target.close` — added `close`
as a recognised lifecycle call), GC5-0470/0471 (`class.getClassLoader` — `X.class` literals were
misparsed by `declared_types` as declaring a variable literally named `class`, inheriting whatever
type happened to be `setdefault`'d first for that bogus name; fixed by rejecting `_TYPE_KEYWORDS` as a
declared name), GC5-0472/0473 (`rawConnection.getAutoCommit` — added `getAutoCommit`), GC5-0474
(`delegate.isWrapperFor` — added `isWrapperFor`), GC5-0477 (`entityManager.isJoinedToTransaction` —
added `isJoinedToTransaction`).

19 + 8 = 27 of 33 cleared.

**Unresolved usage — retained, corrected evidence, no design-changing mechanism attempted (6 rows,
27 + 6 = 33):**
- GC5-0411 `GatewayAuthDataConfig`: `DataSource`/`NamedParameterJdbcTemplate`/`TransactionTemplate`
  are exact-token, not substring, matches — genuine `@Bean` factory-method declaration/construction
  with no query/write call in the file. Recognising "declaration-only inside a `@Configuration`
  class" categorically would need a broader mechanism (proving every occurrence across the file is a
  non-receiver position) that risks masking a real `@Bean`-time query; not built in this bounded pass.
- GC5-0447 `PostMigrationIntegrityAssertion`: the `JdbcTemplate` marker itself is a real,
  correctly-resolved-SQL usage now (see A4/GC5-0450) but its *receiver type* still cannot be proven —
  `jdbc` is a method PARAMETER, and `declared_types` only tracks field/local declarations, never
  parameters (a separate, larger, pre-existing gap outside A3's line-range and not attempted here).
- GC5-0458 `DemoPortfolioInitializer`: `Session`/`EntityManager`/`Statement`/`TransactionTemplate`
  cleared (the `Statement`/`PreparedStatement` operations resolve via A4/GC5-0460's `declared_types`
  and S3 fixes, and `TransactionTemplate` clears via callback accounting); `Connection` remains — the
  file only ever names it as the parameter type of a private helper (`pgAdvisoryXactLock(Connection
  connection)`), the same method-parameter gap as GC5-0447/GC5-0450.
- GC5-0468 `SpecA912ProvenanceDataSource`: `Session` cleared (false match); `Connection`/`DataSource`/
  `PreparedStatement` remain — each is a declared local/field with genuinely zero recognized-target
  method calls anywhere in the file (constructed, wrapped, delegated via reflection, never directly
  invoked as an operation) — the class's own Javadoc calls this an "acknowledged below-wrapper blind
  spot"; still correctly blocking.
- GC5-0475 `SpecA912ProvenanceDataSourcePostProcessor`: `Session` cleared; `DataSource` remains (same
  shape as 0468).
- GC5-0476 `SpecA912StartupTransactionDiagnostics`: `Session` cleared; `Connection`/`DataSource`
  remain — declared but, in this file, never the receiver of a recognised-or-unrecognised call
  directly (only passed to `TransactionSynchronizationManager.hasResource(dataSource)`, itself
  outside the scanned target set).

Regressions: `UnenumeratedMutationTests` gained the mapped-collection/script tests above; added this
round: `test_repository_custom_query_with_a_fully_qualified_annotation_still_blocks` (review finding
#3).

### A4 — the exact seven operation gaps

| ID | Mechanism built | Result |
|---|---|---|
| GC5-0432 `MarketDataSeedService::seed/1::bulk.execute#0` | `NON_SQL_BEARING_WRITE_TYPES = {"BulkOperations"}` exempts `execute()` from needing a SQL argument when the receiver is a locally-declared `BulkOperations`; `BulkOperations` added to `_DEFAULT_STORE_BY_TYPE` as `STORE_MONGO` | `UNSUPPORTED` → `UNRELATED` (`UNREVIEWED`, needs disposition — auto-clear inactive). Untargeted delta: the same file's `bulk.upsert#0` (`UNRESOLVED` → `UNREVIEWED`/`UNRELATED`) benefits from the same store fix. |
| GC5-0460 `DemoPortfolioInitializer::pgAdvisoryXactLock/1::ps.execute#0` | S3: `_prepared_statement_sql` resolves a no-arg `.execute()`/`.executeQuery()`/`.executeUpdate()` against the SQL its receiver was prepared with in the SAME method, when that receiver is declared exactly once via `= <receiver>.prepareStatement(SQL)`/`.prepareCall(SQL)` and the SQL itself resolves | `UNSUPPORTED` (writer-coverage) → `UNRESOLVED` (writer-inventory): `pg_advisory_xact_lock` is a genuinely unknown routine (not in `READ_ONLY_SQL_BUILTINS`), so it correctly stays blocking — now for the true reason (a lock, not resolvable read-only SQL) instead of "SQL argument not resolvable". |
| GC5-0450 `PostMigrationIntegrityAssertion::count/2::jdbc.queryForObject#0` | S4 ("finite literal helper-call propagation"): when a call's own argument is exactly the enclosing method's own `String` parameter, and the enclosing method is `private`, every call to it anywhere in the unit must supply a resolvable literal, the parameter must never be reassigned inside the method, and every resulting SQL must independently be read-only (review finding #1 hardened all of this beyond revision 1) | SQL now correctly resolves (all 6 `SELECT COUNT(*) ...` literals) and is read-only — but `jdbc` is a method PARAMETER (same `declared_types`-excludes-parameters gap as GC5-0447), so `receiver_type` still cannot be proven. Reclassified `UNSUPPORTED` (writer-coverage, false reason "SQL not resolvable") → `UNRESOLVED` (writer-inventory, true reason "receiver type not resolvable"). Still blocking; evidence corrected. |
| GC5-0479 `show/2::statement.executeQuery#0` | S4 generalised to `"SHOW " + setting` (one literal prefix concatenated with the method's own parameter); ALSO required fixing `declared_types`/`_statements`, which lost a try-with-resources header's first resource's type | Fully resolved, read-only, receiver `Statement` now correctly typed → **cleared** (read-only-accounted, no finding at all). |
| GC5-0480 `queryLong/2::statement.executeQuery#0` | Same S4 (bare parameter) + same `declared_types` fix | **Cleared.** |
| GC5-0481 `queryBoolean/2::statement.executeQuery#0` | Same | **Cleared.** |
| GC5-0482 `queryString/2::statement.executeQuery#0` | Same (two literal callers, `current_user`/`current_database()`, both in `READ_ONLY_SQL_BUILTINS`) | **Cleared.** |

Untargeted delta from the `declared_types` try-with-resources fix: `SpecA912StartupTransactionDiagnostics
::runDmlProbe/1::statement.executeUpdate#0` moved `UNRESOLVED` → `UNREVIEWED` (`RELEVANT`, touches
`portfolios`) — same file, same fix, a DML statement (`DELETE FROM portfolios WHERE FALSE`) that
previously had no resolvable receiver type at all.

A NEW, previously-hidden finding surfaced by the same `declared_types` fix:
`DemoPortfolioInitializer::pgAdvisoryXactLock/1::ps.setLong` (JDBC parameter-binding, never executes
SQL itself) — added `setLong` to `PERSISTENCE_METADATA_METHODS` to account for it; this is the ONE
row in this packet not present in the original 45-row queue, because it was invisible before the
`declared_types` fix.

Regressions: 4 tests from revision 1 (S3 positive/reassignment-negative/unresolvable-argument-negative
after being updated to match the intentional new behavior — see §3); added this round (review finding
#1): `test_helper_propagation_safe_private_single_caller_resolves`,
`test_helper_propagation_counts_a_this_qualified_caller_and_rejects_its_dml`,
`test_helper_propagation_rejects_an_unverifiable_qualified_caller`,
`test_helper_propagation_invalidated_by_parameter_reassignment_in_the_helper`,
`test_helper_propagation_requires_private_visibility`.

### A5 — documentation and retained decisions

- Corrected the stale `coverage.boundaries` report line claiming "a SELECT invoking a volatile
  function the tree does not define is NOT detected as a write (declared boundary)" — false: an
  unknown routine (volatile or not) keeps the statement's effect `UNRESOLVED`, blocking exactly like
  `RELEVANT`; it is never silently treated as harmless. Rewrote in place
  (`scripts/check_b1_candidate_source.py`, the `boundaries` list built in `run_all`).
- Added a concise runbook section (`docs/runbooks/B1_R_C_CANDIDATE_VERIFICATION.md`) describing the
  new S3/S4 resolution shapes, the mapped-collection owner-proof rule, the token/type-exact
  persistence-usage accounting, and the Mongo `BulkOperations` store mapping.
- Did **not** approve the remaining 26 unresolved effects or 69 unreviewed writer rows. Did not touch
  R3's policy entry, any of the three V18/V19 `sql:CALL` subjects, or any other unrelated obligation.

## 3. Verification record

Focused suites run throughout implementation to get fast feedback between edits, per the kickoff's
guidance to avoid the full expensive suite after every change; two full-suite runs in revision 1
(220 tests/1 failure, then 229 tests/1 failure after fixing two tests that pinned the exact old
behavior GC5-0460 asked me to change), a third full run (231 tests/1 failure) confirming only the
pre-existing stale-count test remained.

**Post-review (revision 2):** all twelve of Codex's reproduction fixtures re-run against the fixed
analyzer — all four defect fixtures now match the accepted baseline's blocking behavior, all three
safe-positive-control fixtures still clear correctly (§0 table). The targeted suites covering every
touched mechanism (`UnenumeratedMutationTests`, `PostConsolidationSqlAwareReadTests`,
`AddendumFixtureTests`, `RealRepoSmokeTests`) were re-run and pass, including 11 new tests added this
round for the four fixes (§2's per-workstream "Regressions" lines name them). **Final full-suite run:
`python -X utf8 -B -m unittest discover -s scripts/tests -p test_check_b1_candidate_source.py -v` —
243 tests, 0 failures, 560.1s.** Explicit skips: none observed in the run output.

**Post-second-review (revision 3):** all seven of Codex's `adjacent_probes.py` fixtures re-run against
the further-fixed analyzer — all seven now match the accepted baseline's blocking behavior, and all
twelve of the original `gc5_coverage_review_probes.py` fixtures were re-run alongside them and still
pass (§0b). `UnenumeratedMutationTests` (69 tests, including 11 new this round) and
`PostConsolidationSqlAwareReadTests` were re-run together and pass. A fresh fixed-cut CLI run
(§4) shows a verified zero-diff against revision 2's 506-finding report. **Final full-suite run this
round: `python -X utf8 -B -m unittest discover -s scripts/tests -p test_check_b1_candidate_source.py -v`
— 251 tests, 0 failures, 569.7s.** Explicit skips: none observed in any run this round.

**Post-third-review (revision 4):** all seven of `exception_binding_probes.py`'s fixtures (the
positive control plus the six binding/override/escape negatives) re-run against the analyzer with
`_inert_exception_class_names` and the raise-exemption mechanism removed — all seven now show `after`
matching `before` (blocking), including the previously-clearing positive control. Both
`gc5_coverage_review_probes.py` (12 fixtures) and `adjacent_probes.py` (7 fixtures) were re-run
alongside it and still match their expected blocking/clearing behavior (§0c) — no regression on any
mechanism the first two reviews accepted. `UnenumeratedMutationTests` (measured directly this round at
20 tests — six raise-specific tests consolidated into one, §0c) and `PostConsolidationSqlAwareReadTests`
(44 tests) were re-run and pass;
`RealRepoSmokeTests` (2 tests) re-run and passes against the corrected pinned counts (§3 below, §4). A
fresh fixed-cut CLI run (§4) shows a verified single-row diff against revision 3's 506-finding report:
GC5-0486 reappearing exactly as it existed in the original baseline, nothing else changed. **Final
full-suite run this round: `python -X utf8 -B -m unittest discover -s scripts/tests -p
test_check_b1_candidate_source.py -v` — 246 tests, 0 failures, 585.7s.** Explicit skips: none observed.

**The stale test is fixed, not merely documented (review finding #5, round 1).**
`RealRepoSmokeTests.test_real_repo_run_is_blocked_and_reproduces_the_stored_counts` asserted hardcoded
counts against a LIVE run over `HEAD` of this working branch, which drifts as ordinary commits land —
correctly flagged as inside this assignment's authorized edit set, not an acceptable "pre-existing and
therefore untouched" residual. Renamed to
`test_real_repo_run_at_the_fixed_cut_reproduces_the_pinned_counts` and repointed at the immutable fixed
cut `9c3add3cd3a38ff94c2196b20e636eeb5bfa4315` instead of `HEAD` — the SAME methodology used throughout
this packet's own before/after real-tree comparisons: the input tree can never change, so a count that
moves here can only mean a deliberate, reviewed change to the analyzer. Pinned values (verified against
the FINAL, post-third-review analyzer, §4): `changed_paths=468`, `content-governance
CONFIRMED_MATCH=167`, `path-governance CONFIRMED_MATCH=84`/`UNREVIEWED=137`, `persistence-usage
UNSUPPORTED=6`, `writer-inventory UNRESOLVED=25`/`UNREVIEWED=73`/**`UNSUPPORTED=1`** (GC5-0486, §0c —
this one value changed from revision 3's "no `UNSUPPORTED`" per the third review's fix), total
**`507`** (was `506` in revision 3). Verified passing on its own (`RealRepoSmokeTests`, 2 tests, both
`ok`).

- **Docker fixtures**: the guard suite's own end-to-end/Docker-backed tests ran as part of the full
  runs above; no ACR or release-candidate image work was touched. No additional Docker resources were
  created by this checkpoint beyond the guard suite's own — the `postgres:16-alpine` image pulled for
  the R3 preflight (Workstream B) is a normal public fixture pull, and its one-shot `docker run --rm`
  container leaves nothing behind to clean up.
- **`.candidate-artifacts/evidence.json` and `image-evidence.json`**: present and used read-only by
  the tests that need them; not modified, not regenerated.

## 4. Complete real-report delta (fixed cut, unchanged policy)

```
python -X utf8 -B scripts/check_b1_candidate_source.py --repo . \
  --policy scripts/b1-candidate-policy.json \
  --head 9c3add3cd3a38ff94c2196b20e636eeb5bfa4315 --mode LOCAL_PREPARATION --out after.json
```

- Analyzer SHA-256 **reviewed and accepted (revision 4, executable logic)**: `d543127bef8ba0b5afa133d1733061238cb3b8f1a4d0201b01309e24a9da7637`
- Analyzer SHA-256 **final, published (revision 5)**: `090aeb9f77076c6ac6d98a7e3bf80255e03f638613b690f272590c2b474a159e` — a comment-only edit
  over the reviewed revision-4 hash above (§0d): line 2853's comment still said
  `comment/docstring/diagnostic`, stale since revision 4 removed the diagnostic-raise exemption
  entirely. No executable line changed; re-verified by re-running `UnenumeratedMutationTests` (20
  tests, OK) and re-parsing the file (`ast.parse`, no errors) after the edit.
- Test file SHA-256 (final, unchanged by the wording fix — revision 4's hash below still applies):
  `0fdf4000a6f91daed2cd9fc734c982b1fb02222805c1f8453f4091130f9a26a3`
- Policy SHA-256 (unchanged throughout): `78bf8596d90761791028549db54db48db0865f103f07dd8fb84ebde1fb0f29c0`
- Actual checkout HEAD: `2fc14e2fc20e11ff96d7d21d8031f3dc89c8e511` (dirty: yes — this branch's
  uncommitted edits; the analyzed TREE is still the fixed, immutable committed cut)
- Fixed cut: `9c3add3cd3a38ff94c2196b20e636eeb5bfa4315`, mode `LOCAL_PREPARATION`
- Exit code: 1 (BLOCKED, structurally valid report) — expected, real repository still fails governance
- Before (original 540-finding baseline): 540 findings. Revision 2/3 (post-second-review): 506
  findings. **Revision 4 (post-third-review, this round): 507** findings
  (`CONFIRMED_MATCH: 251, UNREVIEWED: 223, UNRESOLVED: 26, UNSUPPORTED: 7`). This round's ONE fix
  (§0c — removing the raise-argument exemption) produced a **verified single-row diff** against
  revision 3's report: `writer-inventory` / `check_b2_demo_identity.py` / `script:INSERT:3575989d`
  reappears as `UNSUPPORTED`; every other `(obligation, path, subject_id)` key is byte-identical,
  kind and evidence both. This is the review's own explicitly required outcome, not an accident: the
  fix removes a mechanism that was clearing exactly one real-tree row, so exactly one row reverts to
  its original baseline state. The count was not preserved at 506 by suppressing the residual or
  altering the source diagnostic, per the review's explicit instruction.

**This comparison measures changed analyzer behavior over the OLD committed source only. It does not
evaluate this branch's own uncommitted analyzer/test/doc files as a new source cut** — no such claim
is made; `candidate_ready` stays `false`.

**Corrected delta arithmetic (review finding #5): 3 added + 37 removed + 10 changed, not "5 changed"
as revision 1 reported.** By `(obligation, path, subject_id)`, comparing kind, detail AND evidence
(revision 1's comparison omitted evidence, which is why it under-counted):

- **Added: 3** — the three A4 rows that RECLASSIFIED obligation (writer-coverage/`UNSUPPORTED` →
  writer-inventory/`UNRESOLVED` or `UNREVIEWED`): `bulk.execute#0`, `count/2::jdbc.queryForObject#0`,
  `pgAdvisoryXactLock/1::ps.execute#0`.
- **Removed: 37** — 27 persistence-usage removals (20 file-level false-match/repository-declaration
  clearances + 7 call-level metadata/wrapper clearances) + 3 script removals + 7 writer-coverage
  removals (the OLD identities of the 3 reclassified-and-added rows above, PLUS 4 more:
  GC5-0479/0480/0481/0482, which fully clear rather than reclassify). 27 + 3 + 7 = 37.
- **Changed (same identity, evidence updated): 10**, not 5:
  - **5 operation findings** (as revision 1 reported): GC5-0444, GC5-0445, the untargeted
    `HoldingReplacementService` mapped-collection delta, the untargeted `bulk.upsert#0` store fix, and
    the untargeted `runDmlProbe`/`statement.executeUpdate#0` `declared_types` fix. Of these, **3 change
    `kind`** (`UNRESOLVED` → `UNREVIEWED`: `bulk.upsert#0`, `HoldingReplacementService`, `runDmlProbe`)
    and **2 keep the same `kind`** (`UNREVIEWED` → `UNREVIEWED`: GC5-0444, GC5-0445) with only their
    `basis` evidence corrected from a fabricated `UNRELATED` reason to the true `RELEVANT` one.
  - **5 persistence-usage `file:` findings** revision 1 did not list, because their `kind`
    (`UNSUPPORTED`) and `detail` text are unchanged — only their `unaccounted_markers` evidence set
    shrank as individual markers cleared: `GatewayAuthDataConfig` (4→3 markers), `DemoPortfolioInitializer`
    (6→1), `SpecA912ProvenanceDataSource` (4→1), `SpecA912ProvenanceDataSourcePostProcessor` (2→1),
    `SpecA912StartupTransactionDiagnostics` (5→3). These five rows are exactly the "unresolved usage"
    residuals in §2/A3 — still blocking, evidence now accurate.

No row outside the 45-row queue (plus the 4 explicitly-labelled untargeted deltas and the 1 newly
surfaced `setLong` row, all explained in §2) changed kind or detail. R3's policy entry, all three
V18/V19 `sql:CALL` subjects, and every other governance obligation are byte-identical before and
after.

**Revision 4 (post-third-review) supersedes ONE row of the arithmetic above.** GC5-0486
(`script:INSERT:3575989d`) was one of the "3 script removals" inside the "Removed: 37" bucket; with
the raise-exemption mechanism removed (§0c), that row is no longer cleared relative to the original
540-finding baseline — it is IDENTICAL to baseline, so it drops out of the delta entirely rather than
moving to "changed". Corrected final arithmetic against the ORIGINAL 540-finding baseline: **3 added +
36 removed + 10 changed** (was 37 removed in revision 2/3). Net: 540 + 3 − 36 = 507, matching §4's
reported total exactly.

## 5. R3 factual report (Workstream B)

**Authorization:** the user explicitly confirmed proceeding with this read-only inspection in this
session, after the kickoff document's own embedded "already authorized, do not ask again" framing was
independently verified rather than trusted at face value.

- **Target:** Neon Postgres, database `neondb`, host `*.aws.neon.tech` (the single
  `POSTGRES_CONNECTION_STRING` present in `.env.secrets`; no separate dev/staging Postgres connection
  string exists anywhere in this repository). Source cut bound: `9c3add3cd3a38ff94c2196b20e636eeb5bfa4315`.
  Portfolio migration-subset digest bound:
  `sha256:b5b3e98ef08a886dea078ee7789eaddff043ee73158ca34e9ea1bf7302329446`.
- **Query file:** `B1_R3_READ_ONLY_PREFLIGHT.sql`, SHA-256
  `272b9bad4387f29da1c513e75a3d246d96859d11d0f1b7d7598441475c8cbd67` — verified byte-identical to the
  packet's recorded hash before executing; unmodified.
- **Session:** `docker run --rm postgres:16-alpine psql -X -v ON_ERROR_STOP=1 -P pager=off -f query.sql`,
  credentials passed via `--env-file` (never on the command line or in shell history), read from the
  existing `.env.secrets` (an established local file, not requested fresh). Operator: `pc`. Captured:
  2026-09-04T14:29:32Z (extraction) through 2026-09-04T14:32:52.264436+00 (server-observed
  `captured_at`). Exit code: 0.
- **Local evidence paths (private, gitignored scratch, not committed):**
  `C:\Users\pc\AppData\Local\Temp\claude\D--Projects-Development-Java-Spring-wealthmgmtandportfoliotracker-intellij\06891018-85ea-4939-8c8e-32683f22cf20\scratchpad\r3\r3_stdout.txt`
  (467 lines, SHA-256 `f31451cd8d0d5956af454f7f90adc5ec0cc29c447e9e9b7213a33ab3356c4b1d`) and
  `...\r3\r3_stderr.txt` (empty — clean run, no warnings/errors); the query file copy and its own
  hash sit alongside at `...\r3\B1_R3_READ_ONLY_PREFLIGHT.sql`.
- **Login/effective role of the INSPECTION session:** `neondb_owner` (both `session_user` and
  `current_user`). Server version 18.6.

**Deployment-identity evidence (review finding #6 — the missing link revision 1 asserted without
proof):** revision 1 said this "confirmed" the same role the deployed application authenticates as,
based only on `.env.secrets` existing locally with `CLOUD_PROVIDER=azure`. That does not establish it
by itself — a local file could be stale or unrelated to what's actually deployed. Tracing the actual
non-secret configuration wiring:

1. `.env.secrets.example`'s own header documents `scripts/sync-secrets.sh .env.secrets` as the
   established mechanism that uploads every `KEY=value` in that file to GitHub Actions repository
   secrets of the identical name (`gh secret set -f`).
2. `.github/workflows/terraform-azure.yml:157-159` sets
   `TF_VAR_postgres_connection_string = secrets.POSTGRES_CONNECTION_STRING`,
   `TF_VAR_postgres_username = secrets.SPRING_DATASOURCE_USERNAME`,
   `TF_VAR_postgres_password = secrets.SPRING_DATASOURCE_PASSWORD` — the identical secret names.
3. `infrastructure/terraform/azure/main.tf:302-358` (`module "portfolio_service"`, commented
   "manages portfolio holdings and valuations... persists to Neon PostgreSQL") injects
   `SPRING_DATASOURCE_URL/USERNAME/PASSWORD = var.postgres_connection_string/username/password` as
   `secret_env_vars` into the deployed Azure Container App that owns `asset_holdings`/`portfolios`.

This is a complete, non-secret, NAME-based chain from the locally inspected credentials to
portfolio-service's actual deployed runtime configuration, and it is the specific service whose domain
includes the tables and repair routines R3 is about. It does **not** independently confirm that the
GitHub secrets currently hold byte-identical VALUES to this local `.env.secrets` copy — I did not, and
cannot, read GitHub Actions secrets (write-only by design), and a value could in principle have drifted
out of sync since the last `sync-secrets.sh` run. But there is no alternate Postgres configuration
anywhere in this repository, and the naming, the file's own stated purpose, and the sync tooling are
all consistent with `.env.secrets` being the source of truth kept in step with production. Revision 1's
"confirmed ... by the same role the deployed application authenticates as" is corrected to: **this
credential set is the one portfolio-service's deployed configuration is wired, by name, to receive.**

**Observed facts** (unchanged from revision 1 — the catalog observations themselves were not disputed):

1. `repair_migrate_holdings(text, text, text)` exists at the queried cut, owned by `neondb_owner`,
   `SECURITY INVOKER` (`security_definer = false`) — it runs with the CALLER's privileges, not the
   definer's.
2. `neondb_owner` has `can_execute = true` on `repair_migrate_holdings`, both via its own grant row AND
   via `public_execute = true` (EXECUTE is granted to `PUBLIC`).
3. `neondb_owner` is a member of `neon_superuser` (`pg_auth_members`: `neon_superuser -> neondb_owner`),
   which itself carries `pg_read_all_data`/`pg_write_all_data`/`pg_maintain` and others.
4. `neondb_owner` has `table_select/insert/update/delete = true` on both `asset_holdings` and
   `portfolios` directly (it owns both tables; `relrowsecurity = false` on both — no row-level
   security in effect).
5. The only triggers on `asset_holdings`/`portfolios` are the four standard FK referential-integrity
   constraint triggers (`RI_FKey_*`); none inspects or enforces `portfolios.version`.
6. No other repair routine (`repair_migrate_market_prices`, `repair_migrate_history`,
   `repair_archive_row`) differs in this respect — all four share the same owner, `SECURITY INVOKER`,
   and the same PUBLIC EXECUTE grant.

**Interpretation:** this is a positive risk finding for the inspected credential set and database, not
an inconclusive one. Because the function is `SECURITY INVOKER` and the inspected credential set
(`neondb_owner`, on `neondb`) already independently holds full, unrestricted DML on
`asset_holdings`/`portfolios` with no intervening RLS or version-checking trigger, **`repair_migrate_holdings`
is capable of being called and would successfully mutate `asset_holdings` with no `portfolios.version`
CAS by anything that authenticates as `neondb_owner` on this database and can issue arbitrary SQL** (an
admin script, a future code path, an injection). This conclusion stands entirely on the catalog
evidence for the inspected credential set and database — it does not depend on, and must not be
read as confirming, that this is the current live production connection; §"Deployment-identity
evidence" above is a separate, weaker, name-based wiring trail offered only as context. Catalog
capability is also not an observed successful mutation for arbitrary arguments, and no application
source path in this or the earlier triage was found actually calling it — this narrows what's proven,
not what's at risk. R3 remains **unresolved in policy**, exactly as required — this packet records
observed facts and their proper scope only. No DROP/REVOKE, role change, migration, or operational
attestation was made or proposed to execute, and none is proposed here: per the review, changing the
effective privilege context (e.g. `SECURITY DEFINER`) can itself increase exposure, and revoking
`PUBLIC` alone would not remove the owner's own privileges — any remediation choice needs the
actor/environment established
first (this packet) and its own separate review, not a default suggested here.

## 6. Remaining governance work (not in this assignment's scope)

Path/content review (221 + 167 rows), per-holding baselines (9 rows), envelope partitions (4 rows),
the 69–73 remaining unreviewed writer rows, and 25–26 remaining unresolved effects are all still open
and were not touched, approved, or certified by this checkpoint. Candidate/registry/serving evidence,
Task A/B build records, and the HTTP smoke harness remain entirely out of this local-source-only pass.

## 7. Publication package

**The seven `UNSUPPORTED` findings this checkpoint leaves standing** (none clearable by an ordinary
disposition under the current contract — a design-changing mechanism, not a disposition, would be
needed for any of these, and none was attempted here):

| ID | Location | Why it stays UNSUPPORTED |
|---|---|---|
| GC5-0411 | `GatewayAuthDataConfig` | `DataSource`/`NamedParameterJdbcTemplate`/`TransactionTemplate` `@Bean` declarations with no in-file query/write call; proving "declaration-only inside `@Configuration`" categorically needs a broader mechanism than this bounded pass built (§2/A3) |
| GC5-0447 | `PostMigrationIntegrityAssertion` | `jdbc` is a method PARAMETER; `declared_types` tracks fields/locals only, never parameters (§2/A3) |
| GC5-0458 | `DemoPortfolioInitializer` | `Connection` named only as a private helper's parameter type — same parameter-type gap as GC5-0447 (§2/A3) |
| GC5-0468 | `SpecA912ProvenanceDataSource` | `Connection`/`DataSource`/`PreparedStatement` fields with zero recognized-target method calls in-file — the class's own Javadoc calls this an acknowledged below-wrapper blind spot (§2/A3) |
| GC5-0475 | `SpecA912ProvenanceDataSourcePostProcessor` | `DataSource` field, same shape as GC5-0468 (§2/A3) |
| GC5-0476 | `SpecA912StartupTransactionDiagnostics` | `Connection`/`DataSource` declared but never a direct call receiver in-file (§2/A3) |
| **GC5-0486** | `check_b2_demo_identity.py`, `script:INSERT:3575989d` | The raise-argument exemption that once cleared this was removed as fundamentally unsound (§0c/§0d) — retained as a documented residual, not a clearance |

Plus the 25 `writer-inventory UNRESOLVED` and 73 `writer-inventory UNREVIEWED` rows, the 137
`path-governance UNREVIEWED` rows, and the sections listed in §6 — none certified, approved, or
disposed by this checkpoint.

**Package contents (all local, none published):**
- `scripts/check_b1_candidate_source.py` — final analyzer, SHA-256 `090aeb9f77076c6ac6d98a7e3bf80255e03f638613b690f272590c2b474a159e`
- `scripts/tests/test_check_b1_candidate_source.py` — final tests, SHA-256 `0fdf4000a6f91daed2cd9fc734c982b1fb02222805c1f8453f4091130f9a26a3`
- `docs/runbooks/B1_R_C_CANDIDATE_VERIFICATION.md` — updated mechanism documentation
- This packet (§0/§0b/§0c/§0d record every review round's independent verification)
- R3 evidence (§5): query file hash, stdout/stderr hashes, role/grant facts — preserved at the
  scratchpad paths named in §5, not committed
- Reviewer's own probe scripts and comparison JSON (§0d's "Review artifacts" list), external to this
  repository, cross-checked against this packet's own figures and found to agree

**Explicit publication approval request:** this checkpoint is ready to be committed to a branch and
opened as a PR for further review, but **no commit, push, or PR has been created**. That is a
separate, explicit decision this packet does not make — per the review's own framing, "Publication
remains an owner decision." If you want this committed/pushed/opened as a PR, say so explicitly and
confirm the target branch; otherwise this stays a local, uncommitted working-tree correction.
