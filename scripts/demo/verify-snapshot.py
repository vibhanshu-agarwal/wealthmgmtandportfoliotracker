"""Verify a saved rehearsal holdings snapshot against the digest the page computed.

Usage:
  python verify-snapshot.py SNAPSHOT.txt EXPECTED_SHA256 [EXPECTED_COUNT]
  python verify-snapshot.py --diff BASELINE.txt AFTER.txt

The snapshot file holds the page's `canonical` string: one "TICKER=quantity" line per holding,
sorted, LF line endings, with a single trailing newline added when saving. The digest covers the
canonical text without that trailing newline, exactly as the page hashed it.

Exit codes: 0 verified / identical, 1 mismatch or differences, 2 usage or malformed file.
"""
import hashlib
import sys


def load(path):
    data = open(path, "rb").read()
    if b"\r" in data:
        print(f"{path}: contains CR characters; save with LF line endings", file=sys.stderr)
        sys.exit(2)
    if not data.endswith(b"\n") or data.endswith(b"\n\n"):
        print(f"{path}: must end with exactly one newline", file=sys.stderr)
        sys.exit(2)
    canonical = data[:-1]
    lines = canonical.decode("utf-8").split("\n")
    entries = {}
    for line in lines:
        ticker, sep, quantity = line.rpartition("=")
        if not sep or not ticker or not quantity:
            print(f"{path}: malformed line {line!r}", file=sys.stderr)
            sys.exit(2)
        if ticker in entries:
            print(f"{path}: duplicate ticker {ticker!r}", file=sys.stderr)
            sys.exit(2)
        entries[ticker] = quantity
    return canonical, lines, entries


def verify(path, expected_sha, expected_count=None):
    canonical, lines, entries = load(path)
    actual = hashlib.sha256(canonical).hexdigest()
    problems = []
    if actual != expected_sha.lower():
        problems.append(f"sha256 {actual} != expected {expected_sha}")
    if lines != sorted(lines, key=lambda l: l.rpartition("=")[0].encode("utf-16-be")):
        problems.append("lines are not in the page's ticker sort order")
    if expected_count is not None and len(entries) != expected_count:
        problems.append(f"{len(entries)} holdings != expected {expected_count}")
    for p in problems:
        print("MISMATCH:", p)
    if problems:
        return 1
    print(f"VERIFIED: {len(entries)} holdings, sha256 {actual}")
    return 0


def diff(before_path, after_path):
    _, _, before = load(before_path)
    _, _, after = load(after_path)
    out = []
    for t in sorted(set(before) | set(after)):
        if t not in after:
            out.append(f"REMOVED {t}={before[t]}")
        elif t not in before:
            out.append(f"ADDED   {t}={after[t]}")
        elif before[t] != after[t]:
            out.append(f"CHANGED {t}: {before[t]} -> {after[t]}")
    for line in out:
        print(line)
    print("IDENTICAL" if not out else f"{len(out)} difference(s)")
    return 1 if out else 0


if __name__ == "__main__":
    args = sys.argv[1:]
    if len(args) == 3 and args[0] == "--diff":
        sys.exit(diff(args[1], args[2]))
    if len(args) in (2, 3) and not args[0].startswith("--"):
        sys.exit(verify(args[0], args[1], int(args[2]) if len(args) == 3 else None))
    print(__doc__, file=sys.stderr)
    sys.exit(2)
