#!/usr/bin/env python3
"""Three-source alignment guard for the B2 demo account identity.

The demo account UUID is stated in three independent places that no compiler or
foreign key can hold together:

  1. api-gateway   -- DemoResetAuthorizationFilter.DEMO_USER_ID  (who may reset)
  2. portfolio-service -- DemoPortfolioInitializer.DEMO_USER_ID  (whose portfolio is seeded)
  3. V15__Reconcile_Auth_Seed_Users.sql -- the `users` row for demo@wealthtracker.dev
     (who the account actually IS)

api-gateway must not depend on portfolio-service, so the two Java copies cannot
share a constant. Drift between them is silent and produces a demo reset that
authorizes one account and rewrites another's data.

This guard reads the ACTUAL literal out of each of the three sources -- never
three constants restated here, and never "the first UUID in the file" -- and
fails the build when they disagree. Standard library only, by design: it must
run in the required static-guard CI job without a JVM or a database.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

GATEWAY_FILTER = (
    REPO / "api-gateway/src/main/java/com/wealth/gateway/DemoResetAuthorizationFilter.java"
)
INITIALIZER = (
    REPO / "portfolio-service/src/main/java/com/wealth/portfolio/seed/DemoPortfolioInitializer.java"
)
V15 = REPO / "portfolio-service/src/main/resources/db/migration/V15__Reconcile_Auth_Seed_Users.sql"

CONSTANT_NAME = "DEMO_USER_ID"
DEMO_EMAIL = "demo@wealthtracker.dev"

UUID_RE = re.compile(r"^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")

# A DECLARATION of the constant -- `... String DEMO_USER_ID = "<value>"` -- never a
# usage such as `DEMO_USER_ID.equals(sub)`.
JAVA_DECLARATION_RE = re.compile(
    r"\bString\s+" + CONSTANT_NAME + r"\s*=\s*\"([^\"]*)\"",
)

# `users`, not `user_credentials` and not `users_archive`: the table name must be
# followed by optional whitespace and the column list.
USERS_INSERT_RE = re.compile(
    r"INSERT\s+INTO\s+users\s*\(([^)]*)\)\s*VALUES\s*\((.*?)\)\s*(?:ON\s+CONFLICT|;)",
    re.IGNORECASE | re.DOTALL,
)


class GuardError(Exception):
    """A source could not be read unambiguously, or the three sources disagree."""


def _read(path: Path, label: str) -> str:
    if not path.is_file():
        raise GuardError(f"{label}: missing required file: {path}")
    return path.read_text(encoding="utf-8")


def strip_java_comments(text: str) -> str:
    """Blanks out `//` and `/* */` comments, leaving string/char literals intact.

    Comment bodies are replaced by spaces rather than removed so that any offsets
    reported later still line up with the original file.
    """
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == '"' or ch == "'":
            quote = ch
            out.append(ch)
            i += 1
            while i < n:
                out.append(text[i])
                if text[i] == "\\" and i + 1 < n:
                    out.append(text[i + 1])
                    i += 2
                    continue
                if text[i] == quote:
                    i += 1
                    break
                i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            out.append("  ")
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def strip_sql_comments(text: str) -> str:
    """Blanks out `--` and `/* */` SQL comments, leaving single-quoted literals intact."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "'":
            out.append(ch)
            i += 1
            while i < n:
                out.append(text[i])
                if text[i] == "'":
                    # '' is an escaped quote inside the literal, not its end.
                    if i + 1 < n and text[i + 1] == "'":
                        out.append(text[i + 1])
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            continue
        if ch == "-" and i + 1 < n and text[i + 1] == "-":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            out.append("  ")
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def _split_top_level(values: str) -> list[str]:
    """Splits a VALUES tuple on commas that are not inside a single-quoted literal."""
    parts = []
    current = []
    in_quote = False
    i = 0
    n = len(values)
    while i < n:
        ch = values[i]
        if ch == "'":
            current.append(ch)
            if in_quote and i + 1 < n and values[i + 1] == "'":
                current.append(values[i + 1])
                i += 2
                continue
            in_quote = not in_quote
            i += 1
            continue
        if ch == "," and not in_quote:
            parts.append("".join(current).strip())
            current = []
            i += 1
            continue
        current.append(ch)
        i += 1
    parts.append("".join(current).strip())
    return parts


def _unquote(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value.startswith("'") and value.endswith("'"):
        return value[1:-1].replace("''", "'")
    return value


def extract_java_constant(text: str, label: str) -> str:
    """Reads the single `String DEMO_USER_ID = "..."` declaration, ignoring comments."""
    matches = JAVA_DECLARATION_RE.findall(strip_java_comments(text))
    if not matches:
        raise GuardError(
            f"{label}: no `String {CONSTANT_NAME} = \"...\"` declaration found "
            f"outside comments"
        )
    if len(matches) > 1:
        raise GuardError(
            f"{label}: ambiguous {CONSTANT_NAME} — {len(matches)} declarations found "
            f"({', '.join(sorted(set(matches)))}); exactly one is required"
        )
    return matches[0]


def extract_v15_demo_user_id(text: str, label: str) -> str:
    """Reads the id of the `users` row that INSERTs demo@wealthtracker.dev."""
    statements = USERS_INSERT_RE.findall(strip_sql_comments(text))
    if not statements:
        raise GuardError(f"{label}: no `INSERT INTO users (...) VALUES (...)` statement found")

    demo_rows = []
    for raw_columns, raw_values in statements:
        columns = [c.strip().lower() for c in _split_top_level(raw_columns)]
        values = [_unquote(v) for v in _split_top_level(raw_values)]
        if len(columns) != len(values):
            raise GuardError(
                f"{label}: an INSERT INTO users statement lists {len(columns)} columns "
                f"but {len(values)} values; cannot map id reliably"
            )
        row = dict(zip(columns, values))
        if row.get("email") == DEMO_EMAIL:
            demo_rows.append(row)

    if not demo_rows:
        raise GuardError(
            f"{label}: no INSERT INTO users row for {DEMO_EMAIL!r} "
            f"(found {len(statements)} users rows in total)"
        )
    if len(demo_rows) > 1:
        raise GuardError(
            f"{label}: ambiguous demo identity — {len(demo_rows)} INSERT INTO users rows "
            f"for {DEMO_EMAIL!r}; exactly one is required"
        )

    row = demo_rows[0]
    if "id" not in row:
        raise GuardError(
            f"{label}: the INSERT INTO users row for {DEMO_EMAIL!r} has no `id` column "
            f"(columns: {', '.join(row)})"
        )
    return row["id"]


def _require_uuid(value: str, label: str) -> str:
    if not UUID_RE.match(value):
        raise GuardError(f"{label}: {value!r} is not a UUID")
    return value


def run_guard(
    gateway_path: Path | None = None,
    initializer_path: Path | None = None,
    v15_path: Path | None = None,
) -> str:
    """Compares the three actual source values. Returns a summary, or raises GuardError."""
    gateway_path = Path(gateway_path) if gateway_path else GATEWAY_FILTER
    initializer_path = Path(initializer_path) if initializer_path else INITIALIZER
    v15_path = Path(v15_path) if v15_path else V15

    gateway_label = f"DemoResetAuthorizationFilter ({gateway_path.name})"
    initializer_label = f"DemoPortfolioInitializer ({initializer_path.name})"
    v15_label = f"V15 demo users row ({v15_path.name})"

    gateway_id = _require_uuid(
        extract_java_constant(_read(gateway_path, gateway_label), gateway_label), gateway_label
    )
    initializer_id = _require_uuid(
        extract_java_constant(_read(initializer_path, initializer_label), initializer_label),
        initializer_label,
    )
    v15_id = _require_uuid(
        extract_v15_demo_user_id(_read(v15_path, v15_label), v15_label), v15_label
    )

    found = {gateway_label: gateway_id, initializer_label: initializer_id, v15_label: v15_id}
    if len({gateway_id, initializer_id, v15_id}) != 1:
        detail = "\n".join(f"  {label}: {value}" for label, value in found.items())
        raise GuardError(
            "B2 demo identity sources disagree — the demo account UUID must be identical "
            "in all three:\n" + detail
        )

    detail = "\n".join(f"  {label}" for label in found)
    return f"B2 demo identity aligned at {gateway_id} across three sources:\n{detail}"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway-filter", type=Path, default=None)
    parser.add_argument("--initializer", type=Path, default=None)
    parser.add_argument("--v15", type=Path, default=None)
    args = parser.parse_args(argv)

    try:
        print(
            run_guard(
                gateway_path=args.gateway_filter,
                initializer_path=args.initializer,
                v15_path=args.v15,
            )
        )
    except GuardError as error:
        print(f"B2 demo identity guard FAILED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
