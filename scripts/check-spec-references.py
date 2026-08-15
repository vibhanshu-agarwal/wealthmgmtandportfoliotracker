#!/usr/bin/env python3
"""Validate internal cross-references in a Kiro spec document.

Two classes of defect are checked, and the second is the one that matters:

  1. DANGLING - a reference like `9.99` where Requirement 9 has no criterion 99.
     Cheap to catch, and rarely the real problem.

  2. WRONG-BUT-EXISTING - a reference that resolves to a criterion which is not the one
     the citing sentence means. Renumbering after an insertion produces these in bulk,
     and no structural check can see them, because every target exists. `--pairs` prints
     each reference beside the *text* of its target so the mismatch is visible.
     Seven such errors survived three review rounds of
     `supported-asset-integrity/requirements.md` before the paired view exposed them.

## Why token-shape filtering is forbidden here

The first version of this script excluded "decimals" with `\\d[\\d,]*\\.\\d{2}\\b` applied to
the matched token. That pattern matches `9.12` and `2.11` — i.e. every two-digit criterion
number — so the checker silently skipped 18 live references and reported a clean run, while
`9.8%` (one digit after the point) was admitted as a reference. It failed in precisely the
way it existed to detect.

The lesson is structural: a reference and a decimal are indistinguishable by token shape.
`9.12` is a valid criterion number *and* a valid money fragment. Only surrounding context
separates them, so exclusion is by context — a preceding currency symbol, a following
percent sign — never by the digits themselves. Multi-group money like `5,839.93` is already
excluded by the leading `\\b` plus the 1-2 digit integer part, which cannot align inside a
comma-grouped number; the self-test pins that behaviour so it cannot regress silently.

Run `--self-test` before trusting a clean result.

Usage:
    python scripts/check-spec-references.py <spec.md> [--pairs]
    python scripts/check-spec-references.py --self-test
"""
import argparse
import io
import re
import sys

# A reference is N.M, optionally with a sub-criterion letter (9.7c).
#
# The boundaries are hand-rolled, not `\b`. A trailing `\b` fails on `_Requirements: 4.2_`,
# because `_` is a word character — which silently dropped the LAST reference on every
# citation line. That was invisible until --coverage reported criteria as uncited which
# demonstrably were cited. Leading `(?<![\w.])` stops a match landing inside `4.81.0` or
# `5,839.93`; trailing `(?![\d])` stops `4.81` matching the head of `4.81.0`.
REF = re.compile(r"(?<![\w.])(\d{1,2})\.(\d{1,2})([a-z])?(?![\d])")

CURRENCY = "$₹£€¥"


def is_reference(line, match):
    """Context-based exclusion. Never inspects the digits themselves."""
    before = line[:match.start()]
    after = line[match.end():]
    if before and before[-1] in CURRENCY:
        return False                      # $9.12
    if after.startswith("%"):
        return False                      # 9.8%
    if before.rstrip().endswith(tuple(CURRENCY)):
        return False                      # $ 9.12
    if re.match(r"\.\d", after):
        return False                      # 4.81.0 — a dotted version, not N.M
    if re.search(r"\d\.$", before):
        return False                      # the ".81" of 4.81.0 seen on its own
    return True


def parse(path):
    """Returns (lines, reqs, bodies).

    `bodies` is keyed by (requirement, criterion, sub), where `sub` is '' for a top-level
    criterion and a letter for an indented lettered clause. Sub-clauses are parsed so that a
    reference like `9.7c` can be checked for existence — `9.7z` must fail even though 9.7
    exists — and so `--pairs` prints the clause itself rather than its parent.
    """
    lines = io.open(path, encoding="utf-8").read().split("\n")
    reqs, bodies, cur, last_n = {}, {}, None, None
    for line in lines:
        m = re.match(r"^### Requirement (\d+):", line)
        if m:
            cur, last_n = int(m.group(1)), None
            reqs[cur] = []
            continue
        if line.startswith("## "):
            cur = None
        if cur is None:
            continue
        m2 = re.match(r"^(\d+)\. (.*)", line)
        if m2:
            last_n = int(m2.group(1))
            reqs[cur].append(last_n)
            bodies[(cur, last_n, "")] = m2.group(2)
            continue
        m3 = re.match(r"^\s+([a-z])\. (.*)", line)
        if m3 and last_n is not None:
            bodies[(cur, last_n, m3.group(1))] = m3.group(2)
    return lines, reqs, bodies


def declared_constraints(lines):
    """Criteria the tasks file declares as intentionally uncited, read from its
    `## Global Constraints` section. The document names its own exemptions; the tool enforces
    that nothing else slips through. A gap outside this list fails the run."""
    out, inside = set(), False
    for line in lines:
        if line.startswith("## Global Constraints"):
            inside = True
            continue
        if inside and line.startswith("## "):
            break
        if not inside:
            continue
        for chunk in re.findall(r"\*\*([0-9., –-]+)\*\*", line):
            for part in chunk.split(","):
                part = part.strip()
                rng = re.match(r"(\d+)\.(\d+)\s*[–-]\s*(\d+)\.(\d+)$", part)
                if rng:
                    a, b, _c, d = map(int, rng.groups())
                    out |= {f"{a}.{i}" for i in range(b, d + 1)}
                elif re.match(r"^\d+\.\d+$", part):
                    out.add(part)
    return out


def references(lines, reqs, only_requirements_lines=False):
    """Yield references. In a tasks file, task ids (`9.10`, `7.7.1`) are lexically identical to
    requirement references, so cross-file mode scans only the `_Requirements:` trailer lines that
    the Kiro convention reserves for citations."""
    for i, line in enumerate(lines, 1):
        if line.startswith(">"):          # historical revision notes, deliberately stale
            continue
        if only_requirements_lines and "_Requirements:" not in line:
            continue
        for m in REF.finditer(line):
            if not is_reference(line, m):
                continue
            r, a, sub = int(m.group(1)), int(m.group(2)), (m.group(3) or "")
            if r not in reqs:
                continue
            context = line[max(0, m.start() - 64):m.start()].strip()[-60:]
            yield i, r, a, sub, m.group(0), context


SELF_TEST = [
    # (line, token, should_be_treated_as_reference)
    ("the distinction drawn in Requirements 9.12 and 9.13", "9.12", True),
    ("the Seed_Only_Interface of Requirement 2.11",         "2.11", True),
    ("activation before 9.7c holds",                        "9.7c", True),
    ("or 9.8% of the reported total",                       "9.8",  False),
    ("a price of $9.12 per share",                          "9.12", False),
    ("overstatement of $5,839.93 against",                  None,   False),
    ("persisted BTC-USD at 62,988.42 after",                None,   False),
    ("BTC 0.75 units held",                                 "0.75", True),  # matched; filtered later by req range
    ("    - _Requirements: 4.1, 4.2_",                       "4.2",  True),   # trailing underscore
    ("the pinned AzureRM 4.81.0 provider block",            "4.81", False),
    ("Spring Boot 4.1 on Java 21",                          "4.1",  True),   # genuine two-part token
]


SUBCLAUSE_FIXTURE = """### Requirement 9: Fixture

1. First criterion.
2. Gated criterion, conditions follow.
   a. condition alpha.
   b. condition beta.
   c. condition gamma.
3. Refers to 9.2c and 9.2z.

## End
"""


COVERAGE_REQS = """### Requirement 1: Fixture

1. Cited criterion.
2. Intentionally uncited prohibition.
3. Second cited criterion.

## End
"""

COVERAGE_TASKS_OK = """## Global Constraints

- **1.2** - prohibition, reviewed not built.

## Tasks

- [ ] 1. Do a thing
  - _Requirements: 1.1, 1.3_
"""


def coverage_verdict(reqs_md, tasks_md):
    """Run the coverage rule over two in-memory documents; return True if it would pass."""
    import tempfile, os
    paths = []
    try:
        for text in (reqs_md, tasks_md):
            fd, path = tempfile.mkstemp(suffix=".md")
            os.close(fd)
            io.open(path, "w", encoding="utf-8").write(text)
            paths.append(path)
        _rl, reqs, _b = parse(paths[0])
        tlines = io.open(paths[1], encoding="utf-8").read().splitlines()
        cited = {(r, a) for _i, r, a, _s, _t, _c in references(tlines, reqs, True)}
        allowed = declared_constraints(tlines)
        uncited = {f"{r}.{a}" for r in reqs for a in reqs[r] if (r, a) not in cited}
        return uncited == allowed
    finally:
        for path in paths:
            os.unlink(path)


def self_test():
    ok = True
    print("SELF-TEST: reference detection")
    for line, token, expected in SELF_TEST:
        found = [m.group(0) for m in REF.finditer(line) if is_reference(line, m)]
        if token is None:
            passed = found == []
            detail = f"expected no match, got {found}"
        else:
            passed = (token in found) == expected
            detail = f"token {token!r} expected={'kept' if expected else 'excluded'}, found={found}"
        ok &= passed
        print(f"  {'PASS' if passed else 'FAIL'}  {line[:46]:<46} {detail}")

    # Sub-clause existence and exact pairing, against an in-memory fixture.
    print("\nSELF-TEST: sub-clause parsing")
    import tempfile
    import os
    fd, path = tempfile.mkstemp(suffix=".md")
    os.close(fd)
    io.open(path, "w", encoding="utf-8").write(SUBCLAUSE_FIXTURE)
    try:
        _lines, reqs, bodies = parse(path)
        cases = [
            ("9.2c exists",            (9, 2, "c") in bodies,                       True),
            ("9.2z does not exist",    (9, 2, "z") in bodies,                       False),
            ("9.2c pairs to gamma",    bodies.get((9, 2, "c"), "").startswith("condition gamma"), True),
            ("9.2 pairs to parent",    bodies.get((9, 2, ""), "").startswith("Gated criterion"),  True),
            ("sub-clauses not criteria", reqs[9] == [1, 2, 3],                      True),
        ]
        for label, actual, expected in cases:
            passed = actual == expected
            ok &= passed
            print(f"  {'PASS' if passed else 'FAIL'}  {label}")
    finally:
        os.unlink(path)

    # Coverage guard: it must fail in BOTH fault directions, not just one. An earlier version
    # only caught an undeclared gap; a criterion already listed as intentional could lose its
    # sole citation and still pass, because "uncited" and "declared" were never compared for
    # equality. The last two cases below are that hole.
    print("\nSELF-TEST: coverage guard symmetry")
    baseline = COVERAGE_TASKS_OK
    undeclared_gap = baseline.replace("- **1.2** - prohibition, reviewed not built.\n", "")
    stale_decl = baseline.replace("- **1.2** - prohibition, reviewed not built.",
                                  "- **1.2** - prohibition.\n- **1.3** - stale: actually cited.")
    lost_citation = baseline.replace("_Requirements: 1.1, 1.3_", "_Requirements: 1.1_")
    cov_cases = [
        ("baseline passes",         coverage_verdict(COVERAGE_REQS, baseline),       True),
        ("undeclared gap fails",    coverage_verdict(COVERAGE_REQS, undeclared_gap), False),
        ("stale declaration fails", coverage_verdict(COVERAGE_REQS, stale_decl),     False),
        ("declared criterion losing its sole citation fails",
                                    coverage_verdict(COVERAGE_REQS, lost_citation),  False),
    ]
    for label, actual, expected in cov_cases:
        passed = actual == expected
        ok &= passed
        print(f"  {'PASS' if passed else 'FAIL'}  {label}")

    print("\nself-test:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("spec", nargs="?")
    ap.add_argument("--pairs", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--coverage", action="store_true",
                    help="with --against: report requirements no task cites. Existence checks cannot "
                         "see a requirement that is simply never mentioned.")
    ap.add_argument("--against", metavar="REQUIREMENTS.md",
                    help="validate the spec's references against another file's requirements "
                         "(for tasks.md, which cites requirements it does not define)")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if not args.spec:
        ap.error("spec path required unless --self-test")

    lines, reqs, bodies = parse(args.spec)
    if args.against:
        _l, reqs, bodies = parse(args.against)          # authority lives in the other file
        print(f"validating {args.spec} against {args.against}")
    failed = False

    bad = {} if args.against else {k: v for k, v in reqs.items() if v != list(range(1, len(v) + 1))}
    if bad:
        failed = True
        for k, v in bad.items():
            print(f"NON-CONTIGUOUS  Requirement {k}: {v}")
    else:
        print(f"contiguous: {len(reqs)} requirements "
              f"{ {k: len(v) for k, v in sorted(reqs.items())} }")

    refs = list(references(lines, reqs, only_requirements_lines=bool(args.against)))
    dangling = []
    for i, r, a, sub, tok, _ in refs:
        if a not in reqs[r]:
            dangling.append((i, tok, f"Requirement {r} has no criterion {a}"))
        elif sub and (r, a, sub) not in bodies:
            dangling.append((i, tok, f"{r}.{a} has no sub-clause '{sub}'"))
    if dangling:
        failed = True
        for i, tok, why in dangling:
            print(f"DANGLING  line {i}: {tok} - {why}")
    else:
        subs = sum(1 for *_x, sub, _t, _c in [(r, a, sub, t, c) for _i, r, a, sub, t, c in refs] if sub)
        print(f"dangling: none ({len(refs)} references checked, {subs} with sub-clauses)")

    if args.pairs:
        print("\nREFERENCE -> TARGET  (does the target say what the citation claims?)")
        print("=" * 94)
        for i, r, a, sub, tok, context in refs:
            target = bodies.get((r, a, sub), "*** MISSING ***")[:62]
            print(f"L{i:<4} ...{context}  [{tok}]\n       -> {target}\n")

    if args.coverage:
        if not args.against:
            ap.error("--coverage requires --against")
        cited = {(r, a) for _i, r, a, _s, _t, _c in refs}
        allowed = declared_constraints(lines)
        print()
        print("COVERAGE  (requirements no task cites)")
        print("=" * 60)
        total = uncited = 0
        undeclared_all = []
        stale_all = []
        for r in sorted(reqs):
            missing = [a for a in reqs[r] if (r, a) not in cited]
            total += len(reqs[r])
            uncited += len(missing)
            undeclared = [a for a in missing if f"{r}.{a}" not in allowed]
            stale = [a for a in reqs[r] if (r, a) in cited and f"{r}.{a}" in allowed]
            mark = "OK " if not (missing or stale) else ("GAP" if (undeclared or stale) else "ok*")
            detail = ""
            if missing:
                detail = "  uncited: " + ", ".join(
                    f"{r}.{a}" + ("" if f"{r}.{a}" not in allowed else "*") for a in missing)
            if stale:
                detail += "  STALE-DECL: " + ", ".join(f"{r}.{a}" for a in stale)
            print(f"  {mark} R{r:<2} {len(reqs[r]) - len(missing):>2}/{len(reqs[r]):<2}{detail}")
            if undeclared:
                failed = True
                undeclared_all.extend(f"{r}.{a}" for a in undeclared)
            if stale:
                failed = True
                stale_all.extend(f"{r}.{a}" for a in stale)
        print()
        print(f"  {total - uncited}/{total} criteria cited by at least one task")
        print("  * = intentionally uncited, declared in the tasks file's Global Constraints")
        print("  The guard enforces EQUALITY: declared intentional gaps == actually uncited.")
        if undeclared_all:
            print()
            print(f"  COVERAGE FAILURE: {len(undeclared_all)} criteria are uncited and "
                  f"NOT declared as intentional: {', '.join(undeclared_all)}")
            print("  Either add a task that cites them, or declare them under Global Constraints.")
        if stale_all:
            print()
            print(f"  STALE DECLARATION: {len(stale_all)} criteria are declared as intentional gaps "
                  f"but ARE cited by a task: {', '.join(stale_all)}")
            print("  Remove them from Global Constraints. A stale declaration is what lets a")
            print("  criterion later lose its only citation and still pass.")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
