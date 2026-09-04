#!/usr/bin/env python3
"""GC.5 source-governance guard + writer-inventory re-check (Task C), contract v2.

Rewritten against six P1 review findings plus three defects found while verifying them. The
governing shape is no longer "three checks that each return PASS/FAIL": it is a set of independent
obligations evaluated over ONE immutable tracked snapshot, each emitting findings keyed by
(path, subject_id, obligation), where a finding is one of:

  CONFIRMED_MATCH  - the change matched a forbidden path glob or forbidden content symbol. This is a
                     DETECTED MATCH, not an adjudicated violation; disposing of it is a human act.
  UNREVIEWED       - understood, but carries no reviewed disposition (or its disposition's
                     fingerprint no longer matches the code it was reviewed against).
  UNSUPPORTED      - the analyzer cannot model this construct. Scanning a file is not the same as
                     understanding its mutations, so coverage is recomputed independently on every
                     run and a human acceptance can never upgrade UNSUPPORTED to RESOLVED.
  MISSING_SUBJECT  - the policy classifies a subject that no longer exists at the cut.

All four block acceptance. None of them is a proven violation.

Why each defect forced a structural change rather than a patch:

  F1 mixed snapshots  - `--head` was diffed while writers were enumerated from `Path.rglob`, so a
      writer present in the selected commit vanished when its working-tree file was deleted and the
      run still returned PASS. Every read now comes from git blobs at ONE resolved commit. There is
      no working-tree fallback anywhere in this module. A missing git object is a blocking error and
      is NEVER silently replaced with a different comparison base.
  F2 enumeration      - `.save(` cannot match `saveAndFlush(`; `rglob("*.java")` never opened a .sql
      or .py file. Enumeration now runs over the tracked tree and covers Java mutation forms, SQL
      migrations and scripts. The tool states its own coverage boundary rather than implying
      exhaustiveness.
  F3 filename trust   - `known.get(path)` treated mere membership as classification, so flipping a
      diagnostic's `WHERE FALSE` to `WHERE TRUE` still passed and a pending disposition never
      blocked. Dispositions now bind to an operation fingerprint over resolved SQL, bound arguments,
      transaction boundary, dominating guards, required subsequent actions and the caller set.
  F4 quoted paths     - git quotes non-ASCII paths, appends a TAB after unquoted space-bearing paths
      in `+++` headers, turns a source line starting `++ ` into a real `+++ ` line (a content
      spoof), and `line.strip()` corrupted whitespace-bearing paths. Patch text is no longer parsed
      at all: `git diff --raw -z` supplies paths plus both blob ids, and content is read by blob id.
  F5 path policy      - `always_allowed_globs` was evaluated BEFORE `forbidden_paths`, and fnmatch's
      `*` crosses `/`, so a production `presence/PresenceSelfTest.java` was exempted by
      `**/*Test.java`. Forbidden now wins over allowed, globs use real globstar semantics, unknown
      paths surface as UNREVIEWED instead of an integer tally, and exceptions bind to the reviewed
      change.
  F6 content coverage - a five-extension allowlist decided what to scan. Scanning is now deny-by-
      default over an explicit, justified documentation exclusion list, and per-holding structural
      state is enumerated from source rather than from two hand-listed record types.

Contract v3 (envelopes, content-addressed records, renewal lifecycle, one validation path) is
documented at the "Shared, git-backed validation" section below. The post-consolidation review then
closed five more trust boundaries, each of which is a MEASURED false pass in this file's history:

  F7 evidence schema   - the validator required `sha256:`-prefixed digests while the accepted Task A/B
      producers emit bare hex, so REAL bundles failed and hand-written ones passed. Digests now go
      through the producers' own `normalize_sha256_digest`; every semantic field is required with its
      type; the run mode is threaded in (a LOCAL_DEV Task A bundle is never CANDIDATE input); and the
      staged JAR is re-hashed from the producer-recorded path while /app.jar is re-extracted from the
      recorded image ID -- a plausible digest string proves nothing about artifacts that do not exist.
  F8 record graph      - only the latest envelope record was validated, yet a claim may reference any
      record. Every record is now validated (memoised, cycle- and duplicate-revision-aware), the
      attestation is an exact partition of membership, `affected_claims` must name real claims and
      must include every claim whose Tier-0 fingerprint changed across the renewal, and identity
      covers every normative field (`reviewed_at`, `membership_digest` included).
  F9 SQL history       - the historical subject index rebuilt Java operations only, so a valid static
      SQL disposition read as "predates the code it approves". One `subject_index` -- the same
      extractors at the cut and at every reviewed commit -- now covers Java operations, entity
      setters and resolvable SQL subjects.
  F10 SQL-aware reads  - `queryForObject` was read-only BY NAME; PostgreSQL returns a result set for
      `DELETE ... RETURNING`. SQL-bearing calls now resolve and classify their statement: a read-only
      statement accounts for its receiver, DML/DDL (or a persistent mutating function) is a writer,
      and an unresolved statement is UNSUPPORTED. Preparation/chained execution that cannot be
      followed blocks as unsupported.
  F11 exceptions/cut   - content findings had no clearance route; a deletion exception accepted any
      commit where the path was absent (a pre-add commit included); CANDIDATE compared raw bytes on a
      CRLF checkout and never required HEAD to be the cut. Content exceptions now bind blob, symbol and
      non-goal scope; every exception's reviewed_commit must lie strictly inside (base, cut] and carry
      the reviewed post-image while the base carries the pre-image; CANDIDATE requires HEAD == cut and
      compares clean-filter-aware object ids (`git hash-object --path`).

Fails closed throughout, reusing b1_candidate_evidence.py's EvidenceError so callers see one
exception type across the whole evidence pipeline.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "scripts"))

from b1_candidate_evidence import (  # noqa: E402
    DEFAULT_POLICY_PATH,
    EvidenceError,
    is_clean,
    load_policy,
    normalize_sha256_digest,
    resolve_base_sha,
    sha256_file,
)
from verify_b1_candidate_image import docker_image_field, extract_file  # noqa: E402

#: Bumping any of these invalidates every stored fingerprint by construction, because all three are
#: inputs to the hashed pre-image. That is the intended mechanism for forcing re-review after the
#: analyzer's understanding changes -- never hand-edit a stored fingerprint.
#: java-conservative/3: SQL-bearing query calls (queryForObject, prepareStatement, createQuery, ...)
#: are classified by their resolved statement, so the operation set the analyzer understands changed.
CONTRACT_VERSION = "gc5-contract/3"
ANALYZER_VERSION = "java-conservative/3"
NORMALIZER_VERSION = "java-lexical/2"

#: Run modes. LOCAL_PREPARATION evaluates a committed cut with possibly-uncommitted tooling;
#: CANDIDATE requires the executing analyzer and policy to be the committed versions at the cut, the
#: checkout to BE the cut, and a clean tree.
LOCAL_PREPARATION = "LOCAL_PREPARATION"
CANDIDATE = "CANDIDATE"

ZERO_OID = "0" * 40

RESOLVED = "RESOLVED"
UNSUPPORTED = "UNSUPPORTED"

ACCEPTED = "ACCEPTED_REVIEWED"

CONFIRMED_MATCH = "CONFIRMED_MATCH"
UNREVIEWED = "UNREVIEWED"
MISSING_SUBJECT = "MISSING_SUBJECT"


# --------------------------------------------------------------------------------------
# Git layer -- bytes only, NUL-delimited, never parses patch text
# --------------------------------------------------------------------------------------


def _git_bytes(repo: Path, *args: str) -> bytes:
    """Raw stdout. Deliberately NOT text=True: on Windows that decodes via the ANSI codepage and
    mojibakes non-ASCII paths, and errors="replace" would silently corrupt them into U+FFFD."""
    proc = subprocess.run(["git", "-C", str(repo), *args], capture_output=True)
    if proc.returncode != 0:
        stderr = proc.stderr.decode("utf-8", errors="replace").strip()
        raise EvidenceError(f"git {' '.join(args)} failed: {stderr}")
    return proc.stdout


def resolve_commit(repo: Path, ref: str) -> str:
    """One immutable commit sha for a user-supplied ref.

    `^{commit}` is mandatory: an annotated tag resolves to the TAG object without it, and every
    later read would then be against an object that is not a commit. `--end-of-options` stops a ref
    beginning with `-` from being parsed as a flag."""
    out = _git_bytes(repo, "rev-parse", "--verify", "--end-of-options", ref + "^{commit}")
    sha = out.decode("ascii", errors="strict").strip()
    if len(sha) != 40:
        raise EvidenceError("could not resolve " + repr(ref) + " to a single commit (got " + repr(sha) + ")")
    return sha


def assert_commit_present(repo: Path, sha: str, what: str) -> None:
    """A shallow clone resolves HEAD but not the B1-base commit. That MUST be blocking: substituting
    a reachable base would silently compare against the wrong interval."""
    proc = subprocess.run(
        ["git", "-C", str(repo), "cat-file", "-e", sha + "^{commit}"], capture_output=True
    )
    if proc.returncode != 0:
        raise EvidenceError(
            what + " " + sha + " is not present in this repository. This is usually a shallow "
            "clone (actions/checkout defaults to fetch-depth: 1). Re-run with a full clone; this "
            "tool will not substitute a different comparison base."
        )


@dataclass(frozen=True)
class ChangeEntry:
    """One `git diff --raw` record. Both blob ids are kept: a deletion has no destination blob, so
    binding a reviewed exception to the destination alone cannot describe it."""

    path: str
    status: str  # A, M, D, T
    src_blob: str
    dst_blob: str

    @property
    def is_deletion(self) -> bool:
        return self.status == "D" or self.dst_blob == ZERO_OID

    @property
    def is_addition(self) -> bool:
        return self.status == "A" or self.src_blob == ZERO_OID


def changed_entries(repo: Path, base_sha: str, cut_sha: str) -> list[ChangeEntry]:
    """`--raw -z` gives path + BOTH blob ids, with no quoting and no patch text.

    `--no-renames` so a rename out of a forbidden zone still reports the source path. `--abbrev=40`
    forces full oids. Records are `:<srcmode> <dstmode> <srcsha> <dstsha> <status>` NUL `<path>` NUL,
    so no path needs unquoting and none is ever handed back to git as an argument."""
    out = _git_bytes(repo, "diff", "--raw", "-z", "--no-renames", "--abbrev=40", base_sha, cut_sha)
    fields = out.split(b"\x00")
    entries: list[ChangeEntry] = []
    i = 0
    while i < len(fields):
        meta = fields[i]
        if not meta.startswith(b":"):
            i += 1
            continue
        if i + 1 >= len(fields):
            raise EvidenceError("git diff --raw -z ended mid-record (metadata with no path)")
        parts = meta.decode("utf-8", errors="strict")[1:].split(" ")
        if len(parts) < 5:
            raise EvidenceError("unparsable git diff --raw record: " + repr(meta))
        src_blob, dst_blob, status = parts[2], parts[3], parts[4]
        path = fields[i + 1].decode("utf-8", errors="strict")
        entries.append(
            ChangeEntry(path=path, status=status[0], src_blob=src_blob, dst_blob=dst_blob)
        )
        i += 2
    return sorted(entries, key=lambda e: e.path)


def tree_blobs(repo: Path, commit_sha: str) -> dict[str, str]:
    """Every tracked path at the cut -> its blob id. NUL-delimited and unquoted, so this is the one
    authoritative file set; Path.rglob would additionally see untracked files and build output that
    do not exist at the cut, which is exactly finding 1."""
    out = _git_bytes(repo, "ls-tree", "-r", "-z", commit_sha)
    blobs: dict[str, str] = {}
    for record in out.split(b"\x00"):
        if not record:
            continue
        head, _, raw_path = record.partition(b"\t")
        bits = head.decode("utf-8", errors="strict").split(" ")
        if len(bits) < 3 or bits[1] != "blob":
            continue  # submodule (commit) or tree entry
        blobs[raw_path.decode("utf-8", errors="strict")] = bits[2]
    return blobs


class BlobReader:
    """Reads blobs by oid, with a cache. Never touches the working tree, so CRLF in the checkout
    (core.autocrlf=true here, and .gitattributes does not pin *.java) cannot make a digest computed
    on Windows differ from the same digest computed on a Linux runner."""

    def __init__(self, repo: Path) -> None:
        self.repo = repo
        self._cache: dict[str, bytes] = {}

    def raw(self, blob_sha: str) -> bytes:
        if blob_sha == ZERO_OID:
            return b""
        if blob_sha not in self._cache:
            self._cache[blob_sha] = _git_bytes(self.repo, "cat-file", "blob", blob_sha)
        return self._cache[blob_sha]

    def text(self, blob_sha: str) -> str | None:
        """Decoded source, or None when the blob is not UTF-8 text (binary). CR is stripped so a
        digest is line-ending independent."""
        data = self.raw(blob_sha)
        if b"\x00" in data:
            return None
        try:
            return data.decode("utf-8", errors="strict").replace("\r\n", "\n").replace("\r", "\n")
        except UnicodeDecodeError:
            return None


# --------------------------------------------------------------------------------------
# Java lexer -- rejects rather than partially fingerprints
# --------------------------------------------------------------------------------------


class LexError(EvidenceError):
    """Raised for any lexical form the normalizer cannot model. Callers turn this into an
    UNSUPPORTED finding; it must never degrade into a partial fingerprint."""


_UNICODE_ESCAPE = re.compile(r"(?<!\\)(\\\\)*\\(u+)([0-9a-fA-F]{0,4})")


def preprocess_unicode_escapes(src: str) -> str:
    """JLS 3.3: unicode escapes are translated BEFORE lexing, so `\\u000a` inside a line comment
    really does terminate that comment. Ignoring this would let a crafted comment hide code from the
    lexer. An eligible backslash is one preceded by an even number of backslashes, and may be
    followed by more than one `u`. A malformed escape is a compile error in Java, so it is a
    LexError here rather than a silent pass-through."""
    if "\\u" not in src:
        return src
    out: list[str] = []
    i = 0
    n = len(src)
    while i < n:
        ch = src[i]
        if ch != "\\":
            out.append(ch)
            i += 1
            continue
        # Count the run of backslashes; only an odd-position backslash is "eligible" (JLS 3.3).
        j = i
        while j < n and src[j] == "\\":
            j += 1
        run = j - i
        if j < n and src[j] == "u" and run % 2 == 1:
            out.append("\\" * (run - 1))
            while j < n and src[j] == "u":
                j += 1
            hexdigits = src[j : j + 4]
            if len(hexdigits) < 4 or any(c not in "0123456789abcdefABCDEF" for c in hexdigits):
                raise LexError("malformed Java unicode escape near offset " + str(i))
            out.append(chr(int(hexdigits, 16)))
            i = j + 4
        else:
            out.append("\\" * run)
            i = j
    return "".join(out)


@dataclass(frozen=True)
class Token:
    kind: str  # code | string | char | text_block
    text: str


_IDENT_RE = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")
_NUM_RE = re.compile(r"[0-9][0-9A-Za-z_.]*")


def lex_java(src: str) -> list[Token]:
    """Comments and whitespace are dropped. String, char and text-block literals are emitted
    VERBATIM, delimiters included -- the normalizer never reaches inside a literal, because the
    literal's bytes are precisely the evidence a disposition rests on (`WHERE FALSE`).

    Code is split into identifier, number and single-character punctuation tokens. Single-character
    punctuation cannot merge two adjacent tokens, so `a!=b` and `a != b` normalize identically while
    no distinct pair is ever collapsed into one token.

    Any unterminated literal or comment raises LexError."""
    src = preprocess_unicode_escapes(src)
    tokens: list[Token] = []
    i = 0
    n = len(src)
    while i < n:
        ch = src[i]
        if ch in " \t\n\f":
            i += 1
            continue
        if src.startswith("//", i):
            end = src.find("\n", i)
            i = n if end == -1 else end + 1
            continue
        if src.startswith("/*", i):
            end = src.find("*/", i + 2)
            if end == -1:
                raise LexError("unterminated block comment")
            i = end + 2
            continue
        if src.startswith('"""', i):
            end = i + 3
            while True:
                end = src.find('"""', end)
                if end == -1:
                    raise LexError("unterminated text block")
                backslashes = 0
                k = end - 1
                while k >= 0 and src[k] == "\\":
                    backslashes += 1
                    k -= 1
                if backslashes % 2 == 0:
                    break
                end += 3
            tokens.append(Token("text_block", src[i : end + 3]))
            i = end + 3
            continue
        if ch in '"\'':
            quote = ch
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == quote:
                    break
                if src[j] == "\n":
                    raise LexError("unterminated " + ("string" if quote == '"' else "char") + " literal")
                j += 1
            if j >= n:
                raise LexError("unterminated " + ("string" if quote == '"' else "char") + " literal")
            tokens.append(Token("string" if quote == '"' else "char", src[i : j + 1]))
            i = j + 1
            continue
        m = _IDENT_RE.match(src, i)
        if m:
            tokens.append(Token("code", m.group(0)))
            i = m.end()
            continue
        m = _NUM_RE.match(src, i)
        if m:
            tokens.append(Token("code", m.group(0)))
            i = m.end()
            continue
        tokens.append(Token("code", ch))
        i += 1
    return tokens


def normalize_tokens(tokens: list[Token]) -> str:
    return " ".join(t.text for t in tokens)


def digest(text: str) -> str:
    return "sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest()


def canonical_fingerprint(code_facts: dict) -> str:
    """The hash is taken over code-derived facts ONLY. Line numbers, blob ids, disposition status,
    rationale and reviewer all live in sibling records, so inserting a comment does not move the
    fingerprint and approving a subject does not invalidate the fingerprint being approved."""
    return digest(json.dumps(code_facts, sort_keys=True, separators=(",", ":"), ensure_ascii=True))


# --------------------------------------------------------------------------------------
# Java string-literal resolution -- exactly three supported shapes (S0/S1/S2)
# --------------------------------------------------------------------------------------


def _unescape_java_string(literal: str) -> str:
    body = literal[1:-1]
    out: list[str] = []
    i = 0
    while i < len(body):
        if body[i] == "\\" and i + 1 < len(body):
            nxt = body[i + 1]
            mapping = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "'": "'", "\\": "\\", "0": "\0"}
            if nxt in mapping:
                out.append(mapping[nxt])
                i += 2
                continue
            out.append(nxt)
            i += 2
            continue
        out.append(body[i])
        i += 1
    return "".join(out)


def _dedent_text_block(literal: str) -> str:
    """JLS 3.10.6 incidental-whitespace removal. A naive strip() would both mask real indentation
    changes and diverge from the value javac actually compiles."""
    body = literal[3:-3]
    if body.startswith("\n"):
        body = body[1:]
    elif body.startswith("\r\n"):
        body = body[2:]
    lines = body.split("\n")
    significant = [ln for ln in lines[:-1] if ln.strip()]
    closing = lines[-1]
    if not closing.strip():
        significant.append(closing)
    if significant:
        indent = min(len(ln) - len(ln.lstrip(" \t")) for ln in significant)
    else:
        indent = 0
    stripped = [ln[indent:].rstrip() if ln.strip() else "" for ln in lines]
    if not lines[-1].strip():
        stripped = stripped[:-1]
        return "\n".join(stripped) + "\n" if stripped else ""
    return "\n".join(stripped)


def literal_value(token: Token) -> str:
    if token.kind == "text_block":
        return _dedent_text_block(token.text)
    if token.kind == "string":
        return _unescape_java_string(token.text)
    raise LexError("not a string literal: " + token.kind)


def fold_literal_run(tokens: list[Token], start: int) -> tuple[str | None, int]:
    """Fold `"a" + "b" + "c"` into one value, per the review note that a `+`-concatenated local is a
    real shape in this repo (UserCredentialRepository insertCredential). Returns (value, next_index),
    or (None, start) when the run is not a pure literal concatenation -- a single non-literal operand
    makes the whole expression UNSUPPORTED rather than partially resolved."""
    if start >= len(tokens) or tokens[start].kind not in ("string", "text_block"):
        return None, start
    parts = [literal_value(tokens[start])]
    i = start + 1
    while i + 1 < len(tokens) and tokens[i].kind == "code" and tokens[i].text == "+":
        if tokens[i + 1].kind not in ("string", "text_block"):
            return None, start
        parts.append(literal_value(tokens[i + 1]))
        i += 2
    return "".join(parts), i


# --------------------------------------------------------------------------------------
# Java scope reconstruction -- brace counting over LEXED tokens
# --------------------------------------------------------------------------------------

_TYPE_KEYWORDS = {"class", "interface", "enum", "record"}


@dataclass
class Scope:
    kind: str  # type | method | block
    name: str
    depth: int


def java_contexts(tokens: list[Token]) -> list[tuple[str, str]]:
    """For each token index, the (enclosing_type, enclosing_method_signature) it sits in.

    Brace counting is only sound because it runs over LEXED tokens: braces inside strings, chars,
    text blocks and comments were already consumed by the lexer, so they cannot mis-nest the stack.
    Raw brace counting over source text does mis-nest, which is why this is not done on the text."""
    contexts: list[tuple[str, str]] = []
    stack: list[Scope] = []
    depth = 0
    head_start = 0
    for i, tok in enumerate(tokens):
        types = ".".join(s.name for s in stack if s.kind == "type")
        methods = [s.name for s in stack if s.kind == "method"]
        contexts.append((types, methods[-1] if methods else ""))
        if tok.kind != "code":
            continue
        if tok.text in (";", "}"):
            head_start = i + 1
        if tok.text == "{":
            head = [t for t in tokens[head_start:i] if t.kind == "code"]
            stack.append(_classify_head(head, depth))
            depth += 1
            head_start = i + 1
        elif tok.text == "}":
            depth = max(0, depth - 1)
            while stack and stack[-1].depth >= depth:
                stack.pop()
            head_start = i + 1
    return contexts


#: Constructs that also read as `... ( ... ) {` but are NOT declarations. Without this, a
#: try-with-resources block is classified as a method literally named `try`, and a lambda body is
#: classified as a method named after the paren before its arrow -- both corrupt every subject id
#: nested inside them, which is fatal for a scheme whose whole point is stable subject identity.
_CONTROL_KEYWORDS = {
    "try", "catch", "finally", "if", "else", "for", "while", "do", "switch",
    "synchronized", "return", "new", "case", "default",
}


def _classify_head(head: list[Token], depth: int) -> Scope:
    words = [t.text for t in head]
    for kw in _TYPE_KEYWORDS:
        for idx, w in enumerate(words):
            if w != kw:
                continue
            # `Session.class` is a class LITERAL, not a declaration. Reading it as one names a type
            # after the following token -- a stray `)` -- and corrupts every nested subject id.
            if idx > 0 and words[idx - 1] == ".":
                continue
            name = words[idx + 1] if idx + 1 < len(words) else "?"
            return Scope("type", name, depth)
    if words[-2:] == ["-", ">"]:
        return Scope("block", "", depth)  # lambda body
    if "(" in words:
        first_open = words.index("(")
        if first_open > 0 and words[first_open - 1] in _CONTROL_KEYWORDS:
            return Scope("block", "", depth)
    if "(" in words:
        open_idx = words.index("(")
        name = words[open_idx - 1] if open_idx > 0 else "?"
        close_idx = len(words) - 1 - words[::-1].index(")") if ")" in words else len(words)
        inner = words[open_idx + 1 : close_idx]
        arity = 0
        if [w for w in inner if w not in (",",)]:
            nest = 0
            arity = 1
            for w in inner:
                if w in ("(", "<"):
                    nest += 1
                elif w in (")", ">"):
                    nest -= 1
                elif w == "," and nest == 0:
                    arity += 1
        return Scope("method", name + "/" + str(arity), depth)
    return Scope("block", "", depth)


# --------------------------------------------------------------------------------------
# Java mutation forms
# --------------------------------------------------------------------------------------

#: Method names whose invocation mutates persistent state. Deliberately explicit rather than a
#: prefix rule: `.save(` cannot match `saveAndFlush(`, which is the exact defect in finding 2, and a
#: prefix rule would instead over-match `saveSettings` on an unrelated bean.
WRITE_METHODS = {
    "save", "saveAll", "saveAndFlush", "saveAllAndFlush",
    "delete", "deleteAll", "deleteById", "deleteAllById", "deleteByIdInBatch",
    "deleteAllInBatch", "deleteInBatch", "deleteAllByIdInBatch",
    "persist", "merge", "remove", "flush",
    "update", "batchUpdate", "execute", "executeUpdate", "executeBatch",
    "insert", "upsert", "doWork",
}

#: Collection mutators. Only reported when the receiver resolves to a mapped collection (a field
#: carrying @OneToMany/@ManyToMany, or its getter), because an unqualified `.clear()` on a local
#: HashMap is not a database write.
COLLECTION_MUTATORS = {"add", "addAll", "remove", "removeAll", "removeIf", "clear", "set", "put"}

#: `execute` and `doWork` are overloaded across two unrelated worlds: executing a SQL string,
#: and running a callback inside a transaction or session. `transactionTemplate.execute(status
#: -> ...)` is not a mutation site, and reporting it UNSUPPORTED would be a false coverage gap.
#: Any real mutation inside the callback is still caught on its own line, so skipping the
#: wrapper loses nothing.
CALLBACK_METHODS = {"execute", "doWork"}


def _mapped_receiver_name(tokens: list[Token], dot_index: int, mapped: set[str]) -> str | None:
    """True when the receiver of `.clear()` / `.add(..)` is a cascade-mapped collection.

    Matches both the bare field (`holdings.clear()`) and its accessor
    (`portfolio.getHoldings().clear()`), where the token before the dot is the closing paren of the
    getter call. An unqualified `.clear()` on a local HashMap is NOT a database write, so an
    unmatched receiver is skipped rather than reported."""
    prev = tokens[dot_index - 1] if dot_index > 0 else None
    if prev is None or prev.kind != "code":
        return None
    if prev.text in mapped:
        return prev.text
    if prev.text == ")":
        depth = 0
        j = dot_index - 1
        while j >= 0:
            t = tokens[j]
            if t.kind == "code" and t.text == ")":
                depth += 1
            elif t.kind == "code" and t.text == "(":
                depth -= 1
                if depth == 0:
                    name = tokens[j - 1] if j > 0 else None
                    if name and name.kind == "code" and name.text in mapped:
                        return name.text
                    return None
            j -= 1
    return None


def _first_arg_is_callback(tokens: list[Token], open_paren: int) -> bool:
    depth = 0
    i = open_paren
    while i < len(tokens):
        t = tokens[i]
        if t.kind == "code" and t.text == "(":
            depth += 1
        elif t.kind == "code" and t.text == ")":
            depth -= 1
            if depth == 0:
                return False
        elif depth == 1 and t.kind == "code" and t.text in ("-", ":"):
            nxt = tokens[i + 1] if i + 1 < len(tokens) else None
            if nxt and nxt.kind == "code" and nxt.text in (">", ":"):
                return True
        i += 1
    return False

_DOLLAR_TAG = re.compile(r"\$[A-Za-z_]*\$")

SQL_VERB_RE = re.compile(
    r"\b(INSERT\s+INTO|UPDATE|DELETE\s+FROM|TRUNCATE|MERGE\s+INTO|CREATE\s+(?:OR\s+REPLACE\s+)?"
    r"(?:FUNCTION|PROCEDURE|TRIGGER)|DROP|GRANT|REVOKE|ALTER\s+TABLE)\b",
    re.IGNORECASE,
)

TABLE_RE = re.compile(
    r"\b(?:INSERT\s+INTO|UPDATE|DELETE\s+FROM|TRUNCATE|MERGE\s+INTO|ALTER\s+TABLE)\s+"
    r"(\"?[A-Za-z_][A-Za-z0-9_.\"]*)",
    re.IGNORECASE,
)

#: `DO UPDATE SET col = ...` makes a naive matcher report a table called "set"; quoted
#: identifiers like "ba_user" made it report no table at all.
_NON_TABLE_WORDS = {"set", "select", "only", "table", "if", "not", "exists"}


def normalize_sql(sql: str) -> str:
    """Collapse whitespace OUTSIDE literals only.

    `" ".join(sql.split())` reaches inside string literals, so `'a  b'` and `'a b'` produced an
    identical normalized form -- two different INSERT payloads sharing one digest, and an accepted
    disposition surviving a change to the data actually written. Single-quoted literals (including
    the `''` escape) and dollar-quoted bodies are emitted byte-for-byte."""
    out: list[str] = []
    i, n = 0, len(sql)
    pending_space = False
    while i < n:
        ch = sql[i]
        if ch.isspace():
            pending_space = bool(out)
            i += 1
            continue
        if pending_space:
            out.append(" ")
            pending_space = False
        if ch == "'":
            j = i + 1
            while j < n:
                if sql[j] == "'" and j + 1 < n and sql[j + 1] == "'":
                    j += 2
                    continue
                if sql[j] == "'":
                    break
                j += 1
            out.append(sql[i : j + 1])
            i = j + 1
            continue
        m = _DOLLAR_TAG.match(sql, i)
        if m:
            tag = m.group(0)
            j = sql.find(tag, m.end())
            if j == -1:
                out.append(sql[i:])
                break
            out.append(sql[i : j + len(tag)])
            i = j + len(tag)
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def _executable_verbs_and_tables(tokens: list[tuple[str, str]]) -> tuple[list[str], list[str]]:
    """DML/DDL verbs and their target tables from LEXED tokens only -- comment and literal text is
    data, never a verb or a target-table fact (F13). Verb spellings match the historical regex forms
    so SQL subject ids stay stable."""
    verbs: list[str] = []
    tables: set[str] = set()
    idents = [(i, t) for i, (k, t) in enumerate(tokens) if k == "ident"]
    pos_of = {i: n for n, (i, _t) in enumerate(idents)}

    def ident_at(n: int) -> str | None:
        return idents[n][1] if 0 <= n < len(idents) else None

    def table_after(token_index: int) -> None:
        j = token_index + 1
        # skip qualifiers such as ONLY / TABLE / IF EXISTS / IF NOT EXISTS
        while j < len(tokens) and tokens[j][0] == "ident" and tokens[j][1] in ("only", "table", "if", "not", "exists"):
            j += 1
        chain: list[str] = []
        while j < len(tokens) and tokens[j][0] in ("ident", "qident"):
            chain.append(tokens[j][1].lower())
            if j + 2 < len(tokens) and tokens[j + 1] == ("punct", ".") and tokens[j + 2][0] in ("ident", "qident"):
                j += 2
                continue
            break
        if chain and not (len(chain) == 1 and chain[0] in _NON_TABLE_WORDS):
            tables.add(".".join(chain))

    for n, (i, t) in enumerate(idents):
        nxt = ident_at(n + 1)
        if t == "insert" and nxt == "into":
            verbs.append("INSERT INTO")
            table_after(idents[n + 1][0])
        elif t == "update" and ident_at(n - 1) not in ("for", "on"):  # `FOR UPDATE` lock, `ON UPDATE` action
            verbs.append("UPDATE")
            table_after(i)
        elif t == "delete" and nxt == "from":
            verbs.append("DELETE FROM")
            table_after(idents[n + 1][0])
        elif t == "truncate":
            verbs.append("TRUNCATE")
            table_after(i)
        elif t == "merge" and nxt == "into":
            verbs.append("MERGE INTO")
            table_after(idents[n + 1][0])
        elif t == "alter" and nxt == "table":
            verbs.append("ALTER TABLE")
            table_after(idents[n + 1][0])
        elif t == "create":
            words = [ident_at(n + 1), ident_at(n + 2), ident_at(n + 3)]
            if words[0] == "or" and words[1] == "replace" and words[2] in ("function", "procedure", "trigger"):
                verbs.append("CREATE OR REPLACE " + words[2].upper())
            elif words[0] in ("function", "procedure", "trigger"):
                verbs.append("CREATE " + words[0].upper())
        elif t in ("drop", "grant", "revoke"):
            verbs.append(t.upper())
    return sorted(set(verbs)), sorted(tables)


def sql_facts(sql: str) -> dict:
    """Verb, target tables and predicate for a resolved SQL string, derived from the SAME lexical
    representation as the read-only assessment (F13): verbs and target tables come from executable
    tokens only, so `/* UPDATE market_prices */` or `'UPDATE market_prices'` is data, not a target.
    `sql` keeps the normalized text (literals included) because it is fingerprint identity -- a
    changed literal must still change the fingerprint. When the statement is outside the supported
    lexical subset, `lexical_error` carries the reason and verbs/tables are UNKNOWN (empty) -- callers
    must treat that as UNSUPPORTED coverage, never as "no writes". The predicate is retained verbatim
    because it is frequently the whole basis of a disposition -- `WHERE FALSE` is what makes the
    Spec A 9.12 startup probe a non-writer."""
    collapsed = normalize_sql(sql)
    where = ""
    m = re.search(r"\bWHERE\b(.*)$", collapsed, re.IGNORECASE)
    if m:
        where = "WHERE" + m.group(1)
    facts = {"sql": collapsed, "verbs": [], "target_tables": [], "predicate": where}
    try:
        tokens = _expand_procedural_bodies(lex_sql(sql))
    except SqlLexError as exc:
        facts["lexical_error"] = str(exc)
        return facts
    facts["verbs"], facts["target_tables"] = _executable_verbs_and_tables(tokens)
    return facts


@dataclass
class JavaSubject:
    subject_id: str
    obligation: str
    form: str
    coverage: str
    coverage_reason: str
    code_facts: dict
    line_hint: int


def _line_of(src: str, token_index: int, tokens: list[Token]) -> int:
    """Best-effort line hint. Informational only -- it lives in the snapshot record, never in the
    hashed pre-image, so it can drift without invalidating a disposition."""
    consumed = "".join(t.text for t in tokens[:token_index])
    return consumed.count("\n") + 1


def _resolve_string_arg(
    tokens: list[Token], call_open: int, static_finals: dict[str, str], locals_map: dict[str, str]
) -> tuple[str | None, str, str]:
    """Resolve the first argument of a call to a SQL string, honouring exactly three shapes.

    S0 literal (or `+`-folded literal run) passed directly.
    S1 same-file `static final String` constant.
    S2 method-local `String` assigned once from a constant string expression and never reassigned.

    Anything else -- a non-literal operand, a reassigned local, an unknown symbol -- is UNSUPPORTED.
    It is never approximated by the call text, because approving unresolved text would approve
    whatever that text later resolves to."""
    i = call_open + 1
    if i >= len(tokens):
        return None, UNSUPPORTED, "call has no arguments"
    value, nxt = fold_literal_run(tokens, i)
    if value is not None:
        return value, RESOLVED, "S0"
    tok = tokens[i]
    if tok.kind == "code" and _IDENT_RE.fullmatch(tok.text):
        if tok.text in locals_map:
            return locals_map[tok.text], RESOLVED, "S2"
        if tok.text in static_finals:
            return static_finals[tok.text], RESOLVED, "S1"
        return None, UNSUPPORTED, "argument symbol " + tok.text + " is not a resolvable constant"
    return None, UNSUPPORTED, "argument is not a constant string expression"


# --------------------------------------------------------------------------------------
# Globstar path matching -- real semantics, case-sensitive on every platform
# --------------------------------------------------------------------------------------


def _glob_to_regex(pattern: str) -> str:
    out = ["^"]
    i = 0
    n = len(pattern)
    while i < n:
        if pattern.startswith("**/", i):
            out.append("(?:.*/)?")
            i += 3
        elif pattern.startswith("**", i):
            out.append(".*")
            i += 2
        elif pattern[i] == "*":
            out.append("[^/]*")
            i += 1
        elif pattern[i] == "?":
            out.append("[^/]")
            i += 1
        else:
            out.append(re.escape(pattern[i]))
            i += 1
    out.append("$")
    return "".join(out)


_GLOB_CACHE: dict[str, re.Pattern] = {}


def _compiled(pattern: str) -> re.Pattern:
    if pattern not in _GLOB_CACHE:
        _GLOB_CACHE[pattern] = re.compile(_glob_to_regex(pattern))
    return _GLOB_CACHE[pattern]


def glob_match(path: str, pattern: str) -> bool:
    """Real globstar semantics, always case-sensitive.

    fnmatch was wrong twice over: its star crosses the path separator, which made the policy glob
    for test files identical to a bare suffix match at any depth (that is how a production presence
    class got exempted), and fnmatch is case- and separator-insensitive on Windows but neither on
    Linux, so the same policy silently classified differently per platform.

    A directory-shaped pattern also matches everything beneath it, preserving v1 intent."""
    if _compiled(pattern).match(path):
        return True
    if not pattern.endswith("*"):
        return bool(_compiled(pattern.rstrip("/") + "/**").match(path))
    return False


# --------------------------------------------------------------------------------------
# Findings -- keyed (path, subject_id, obligation), emitted independently
# --------------------------------------------------------------------------------------


@dataclass
class Finding:
    """One obligation on one subject. Findings are never collapsed or de-duplicated across
    obligations: a single file may simultaneously carry a CONFIRMED_MATCH on its path, an
    UNREVIEWED operation and an UNSUPPORTED one, and reporting only the most severe would hide
    two thirds of the review work."""

    path: str
    subject_id: str
    obligation: str
    kind: str
    detail: str
    evidence: dict = field(default_factory=dict)

    def key(self) -> tuple[str, str, str]:
        return (self.path, self.subject_id, self.obligation)

    def to_json(self) -> dict:
        return {
            "path": self.path,
            "subject_id": self.subject_id,
            "obligation": self.obligation,
            "kind": self.kind,
            "detail": self.detail,
            "evidence": self.evidence,
        }


# --------------------------------------------------------------------------------------
# Path guard
# --------------------------------------------------------------------------------------


def _exception_index(raw: list[dict]) -> dict[tuple[str, str], dict]:
    idx: dict[tuple[str, str], dict] = {}
    for e in raw:
        idx[(e["path"], e.get("obligation", "path-governance"))] = e
    return idx


_SHA_RE = re.compile(r"^[0-9a-f]{40}$")


def _blob_at(repo: Path, commit: str, path: str) -> str | None:
    proc = subprocess.run(["git", "-C", str(repo), "rev-parse", "--verify", "--end-of-options",
                           commit + ":" + path], capture_output=True)
    if proc.returncode != 0:
        return None
    return proc.stdout.decode("ascii", errors="strict").strip()


def _exception_provenance_problem(exc: dict, repo: Path | None, base_sha: str | None = None,
                                  cut_sha: str | None = None) -> str | None:
    """Path/content exceptions carry the same provenance and status rules as any claim: an explicit
    ACCEPTED status, a real reviewer, both blob ids, a change kind, and a reviewed_commit that EXISTS,
    lies STRICTLY inside the guard interval (a descendant of the B1-base and an ancestor of the cut)
    and PROVES the reviewed transition.

    The transition proof is `src_blob -> dst_blob` over `base..reviewed_commit` for this path: the
    pre-image is the blob at the base -- which is exactly what the interval diff reports as
    src_blob -- and the post-image (present as dst_blob, or ABSENT for a deletion) must be the tree
    state at reviewed_commit. A tree-state-only check was insufficient: a commit predating the
    file's addition also shows the path absent, yet it reviewed nothing. Requiring
    base < reviewed_commit <= cut closes that, and the pre-image check binds the reviewed diff to the
    very base the guard compares against. A change landed through a PR names the merge commit.

    A content exception additionally scopes the forbidden symbols and non-goals it clears; it never
    clears a symbol it does not name."""
    if exc.get("status") != ACCEPTED:
        return "exception status is " + repr(exc.get("status")) + "; only " + ACCEPTED + " clears a change"
    reviewer = exc.get("reviewer")
    if not isinstance(reviewer, str) or not reviewer.strip():
        return "reviewer must be a non-empty name"
    commit = exc.get("reviewed_commit")
    if not isinstance(commit, str) or not _SHA_RE.match(commit):
        return "reviewed_commit must be a full 40-character commit sha, got " + repr(commit)
    for key in ("src_blob", "dst_blob"):
        val = exc.get(key)
        if not isinstance(val, str) or not _SHA_RE.match(val):
            return key + " must be a full 40-character object id, got " + repr(val)
    if exc.get("change_kind") not in ("A", "M", "D", "T"):
        return "change_kind must be one of A/M/D/T, got " + repr(exc.get("change_kind"))
    if exc.get("obligation") == "content-governance":
        for key in ("symbols", "non_goals"):
            val = exc.get(key)
            if not isinstance(val, list) or not val or not all(isinstance(v, str) and v for v in val):
                return "content exception must scope " + key + " as a non-empty list of strings"
    if repo is None:
        return None
    if not commit_exists(repo, commit):
        return "reviewed_commit " + commit + " does not exist in this repository"
    if cut_sha is not None and not is_ancestor(repo, commit, cut_sha):
        return "reviewed_commit " + commit[:12] + " is not an ancestor of the cut"
    if base_sha is not None:
        if commit == base_sha or not is_ancestor(repo, base_sha, commit):
            return ("reviewed_commit " + commit[:12] + " does not lie strictly inside the guard interval "
                    "(base, cut]; a commit at or before the base reviewed none of this interval's changes")
        pre = _blob_at(repo, base_sha, exc["path"]) or ZERO_OID
        if pre != exc["src_blob"]:
            return ("pre-image at the B1-base is " + pre[:12] + ", not the reviewed src_blob "
                    + exc["src_blob"][:12] + "; the exception does not describe this interval's change")
    post = _blob_at(repo, commit, exc["path"]) or ZERO_OID
    if post != exc["dst_blob"]:
        if exc["dst_blob"] == ZERO_OID:
            return ("deletion exception: the path is still present at reviewed_commit " + commit[:12]
                    + "; that commit does not carry the reviewed deletion")
        return ("reviewed change is not present at reviewed_commit " + commit[:12]
                + "; the exception approves a change the commit does not contain")
    return None


def _exception_covers(exc: dict, entry: ChangeEntry) -> bool:
    """An exception authorises ONE reviewed change, not a path forever.

    It must name the change kind and BOTH blob ids. A destination blob alone cannot describe a
    deletion (there is no destination), and pinning only the destination would let any later edit
    inherit an approval that was granted for different content."""
    required = ("change_kind", "src_blob", "dst_blob", "reviewed_commit", "reviewer")
    if any(k not in exc for k in required):
        return False
    return (
        exc["change_kind"] == entry.status
        and exc["src_blob"] == entry.src_blob
        and exc["dst_blob"] == entry.dst_blob
    )


def _usable_exception(exceptions: dict, entry: ChangeEntry, obligation: str, repo: Path | None,
                      base_sha: str | None, cut_sha: str | None) -> tuple[dict | None, list[Finding]]:
    """The exception for (path, obligation) if it is provenance-valid AND covers this exact change;
    otherwise None, plus an independent `exception-provenance` finding when one exists but is
    unusable. Swallowing the path/content verdict because an exception was malformed would be exactly
    the precedence collapse this model exists to avoid: a broken exception must not hide what it
    failed to except."""
    exc = exceptions.get((entry.path, obligation))
    if not exc:
        return None, []
    problem = _exception_provenance_problem(exc, repo, base_sha, cut_sha)
    if problem:
        return None, [Finding(
            path=entry.path, subject_id="exception:", obligation="exception-provenance",
            kind=UNREVIEWED, detail="reviewed " + obligation + " exception is not usable: " + problem,
            evidence={"change_kind": entry.status, "src_blob": entry.src_blob, "dst_blob": entry.dst_blob})]
    if not _exception_covers(exc, entry):
        return None, []
    return exc, []


def path_guard(entries: list[ChangeEntry], gc5: dict, repo: Path | None = None,
               base_sha: str | None = None, cut_sha: str | None = None) -> list[Finding]:
    """Forbidden is evaluated BEFORE allowed.

    The v1 order let a broad test-name glob exempt a production presence class, and that precedence
    was never recorded as a decision anywhere in the policy. Paths matching neither list are no
    longer collapsed into a per-module integer: they surface individually as UNREVIEWED, because
    failing to recognise a path is not evidence that the change is in scope."""
    forbidden = gc5["forbidden_paths"]
    allowed = gc5["always_allowed_globs"]
    exceptions = _exception_index(gc5.get("reviewed_exceptions", []))
    findings: list[Finding] = []

    for e in entries:
        exc, exc_findings = _usable_exception(exceptions, e, "path-governance", repo, base_sha, cut_sha)
        findings.extend(exc_findings)
        exc_ok = exc is not None

        hits = [f for f in forbidden if glob_match(e.path, f["glob"])]
        if hits:
            if exc_ok:
                continue
            non_goals = sorted({f["non_goal"] for f in hits})
            findings.append(
                Finding(
                    path=e.path,
                    subject_id="file:",
                    obligation="path-governance",
                    kind=CONFIRMED_MATCH,
                    detail="changed path matches forbidden glob(s) for non-goal(s) " + ", ".join(non_goals),
                    evidence={
                        "matched_globs": [f["glob"] for f in hits],
                        "non_goals": non_goals,
                        "change_kind": e.status,
                        "src_blob": e.src_blob,
                        "dst_blob": e.dst_blob,
                    },
                )
            )
            continue
        if any(glob_match(e.path, g) for g in allowed):
            continue
        # An unknown path must have a disposition route that does not require broadening the
        # allowlist -- otherwise the only way to clear one of the 131 unknowns is to weaken the
        # default for every future path that happens to match the same glob.
        if exc_ok:
            continue
        findings.append(
            Finding(
                path=e.path,
                subject_id="file:",
                obligation="path-governance",
                kind=UNREVIEWED,
                detail="changed path matches neither the reviewed allowlist nor a forbidden glob",
                evidence={"change_kind": e.status, "src_blob": e.src_blob, "dst_blob": e.dst_blob},
            )
        )
    return findings


# --------------------------------------------------------------------------------------
# Content guard -- deny by default
# --------------------------------------------------------------------------------------


def content_guard(entries: list[ChangeEntry], reader: BlobReader, gc5: dict, repo: Path | None = None,
                  base_sha: str | None = None, cut_sha: str | None = None) -> list[Finding]:
    """Scans the destination blob of every changed, non-excluded file for forbidden symbols.

    Two deliberate departures from v1. Exclusion is now an explicit, justified deny-list rather than
    a five-extension allow-list, so a .lua Redis script or a .yml runtime config cannot pass merely
    by not being one of five recognised suffixes. And nothing is derived from patch text: the v1
    parser could be induced to drop a forbidden line, or to attribute it to a file the commit never
    touched, by file content beginning with a double plus.

    Hits are reported with `pre_existing` rather than filtered by it. v1 scanned added lines only,
    which meant a violation expressed as a REMOVAL was invisible; reporting the flag lets a reviewer
    see both without the tool silently discarding one.

    Clearance route (F11): a `content-governance` exception binds the exact change (kind + both blob
    ids), the reviewed commit, and the forbidden symbols / non-goals it clears. A hit is cleared only
    when its symbol AND its non-goal are both inside that scope; every other hit on the same file
    stays. Re-editing the file changes dst_blob and lapses the exception."""
    excluded = gc5.get("content_scan_excluded_globs", [])
    symbols = gc5["forbidden_content_symbols"]
    exceptions = _exception_index(gc5.get("reviewed_exceptions", []))
    findings: list[Finding] = []

    for e in entries:
        if e.is_deletion:
            continue
        if any(glob_match(e.path, g) for g in excluded):
            continue
        exc, exc_findings = _usable_exception(exceptions, e, "content-governance", repo, base_sha, cut_sha)
        findings.extend(exc_findings)
        cleared_symbols = set(exc["symbols"]) if exc else set()
        cleared_non_goals = set(exc["non_goals"]) if exc else set()
        text = reader.text(e.dst_blob)
        if text is None:
            findings.append(
                Finding(
                    path=e.path,
                    subject_id="file:",
                    obligation="content-governance",
                    kind=UNSUPPORTED,
                    detail="blob is not UTF-8 text; the symbol scan cannot run on it",
                    evidence={"dst_blob": e.dst_blob},
                )
            )
            continue
        before_lines = set((reader.text(e.src_blob) or "").splitlines()) if not e.is_addition else set()
        for lineno, line in enumerate(text.splitlines(), start=1):
            for entry in symbols:
                if entry["symbol"] not in line:
                    continue
                if entry["symbol"] in cleared_symbols and entry["non_goal"] in cleared_non_goals:
                    continue  # reviewed: this exact blob, this symbol, this non-goal
                findings.append(
                    Finding(
                        path=e.path,
                        subject_id="line:" + str(lineno) + ":" + entry["symbol"],
                        obligation="content-governance",
                        kind=CONFIRMED_MATCH,
                        detail="forbidden symbol for non-goal " + entry["non_goal"],
                        evidence={
                            "symbol": entry["symbol"],
                            "non_goal": entry["non_goal"],
                            "line": line.strip()[:400],
                            "pre_existing": line in before_lines,
                            "dst_blob": e.dst_blob,
                        },
                    )
                )
    return findings


# --------------------------------------------------------------------------------------
# Java subject extraction
# --------------------------------------------------------------------------------------


def _statements(tokens: list[Token], indices: list[int]) -> list[list[int]]:
    out: list[list[int]] = []
    cur: list[int] = []
    for i in indices:
        t = tokens[i]
        if t.kind == "code" and t.text in (";", "{", "}"):
            if cur:
                out.append(cur)
            cur = []
            continue
        cur.append(i)
    if cur:
        out.append(cur)
    return out


def _string_bindings(tokens: list[Token], indices: list[int], require_static_final: bool) -> dict[str, str]:
    """Names bound once to a constant string expression, and never rebound.

    A name assigned more than once, or assigned from anything that is not a literal (or a fold of
    `+`-joined literals), is DROPPED rather than approximated -- an unresolvable symbol must reach
    the caller as UNSUPPORTED, not as a half-known value."""
    values: dict[str, str] = {}
    rebound: set[str] = set()
    for stmt in _statements(tokens, indices):
        words = [tokens[i].text if tokens[i].kind == "code" else None for i in stmt]
        if "=" not in [w for w in words if w]:
            continue
        try:
            eq_pos = words.index("=")
        except ValueError:
            continue
        if eq_pos == 0:
            continue
        name = words[eq_pos - 1]
        if not name or not _IDENT_RE.fullmatch(name):
            continue
        head = [w for w in words[:eq_pos] if w]
        if require_static_final and not ("static" in head and "final" in head):
            continue
        if "String" not in head:
            if name in values:
                rebound.add(name)
            continue
        value, _ = fold_literal_run(tokens, stmt[eq_pos + 1]) if eq_pos + 1 < len(stmt) else (None, 0)
        if name in values:
            rebound.add(name)
            continue
        if value is None:
            rebound.add(name)
            continue
        values[name] = value
    for name in rebound:
        values.pop(name, None)
    return values


def _annotations_before(tokens: list[Token], start: int, limit: int = 60) -> list[str]:
    """Annotations immediately preceding a declaration, normalized WITH their arguments.

    Arguments matter: dropping `@Transactional(propagation = REQUIRES_NEW)` down to `@Transactional`
    would hide a change of transaction semantics behind an identical fingerprint."""
    out: list[str] = []
    i = start - 1
    seen = 0
    buf: list[str] = []
    depth = 0
    while i >= 0 and seen < limit:
        t = tokens[i]
        seen += 1
        if t.kind == "code" and t.text in (")",):
            depth += 1
        elif t.kind == "code" and t.text == "(":
            depth -= 1
        buf.append(t.text)
        if t.kind == "code" and t.text == "@" and depth == 0:
            out.append(" ".join(reversed(buf)))
            buf = []
        elif t.kind == "code" and t.text in (";", "}", "{") and depth == 0:
            break
        i -= 1
    return sorted(out)


def java_method_heads(tokens: list[Token]) -> dict[str, list[str]]:
    """Declaration-head tokens per method scope, keyed exactly like `java_contexts` groups them.

    `_annotations_before` walked backwards from a method's FIRST BODY token, whose predecessor is
    the opening brace -- on which it stopped immediately, so it returned an empty list for every
    method in the repository and no annotation was ever part of any fingerprint. The head is the
    right source: after the preceding `;`/`}`/`{`, the token run is
    `@ Transactional public Result replace ( ... )`, annotations included."""
    heads: dict[str, list[str]] = {}
    stack: list[Scope] = []
    depth = 0
    head_start = 0
    for i, tok in enumerate(tokens):
        if tok.kind != "code":
            continue
        if tok.text in (";", "}"):
            head_start = i + 1
        if tok.text == "{":
            head = [t for t in tokens[head_start:i] if t.kind == "code"]
            scope = _classify_head(head, depth)
            stack.append(scope)
            if scope.kind == "method":
                types = ".".join(sc.name for sc in stack if sc.kind == "type")
                heads[types + "::" + scope.name] = [t.text for t in head]
            depth += 1
            head_start = i + 1
        elif tok.text == "}":
            depth = max(0, depth - 1)
            while stack and stack[-1].depth >= depth:
                stack.pop()
            head_start = i + 1
    return heads


def annotations_from_head(head: list[str]) -> list[str]:
    """Annotations in a declaration head, WITH their arguments, so
    `@Transactional` -> `@Transactional(propagation = REQUIRES_NEW)` is a fingerprint change."""
    out: list[str] = []
    i = 0
    while i < len(head):
        if head[i] != "@":
            i += 1
            continue
        j = i + 1
        buf = ["@"]
        if j < len(head):
            buf.append(head[j])
            j += 1
        if j < len(head) and head[j] == "(":
            depth = 0
            while j < len(head):
                buf.append(head[j])
                if head[j] == "(":
                    depth += 1
                elif head[j] == ")":
                    depth -= 1
                    if depth == 0:
                        j += 1
                        break
                j += 1
        out.append(" ".join(buf))
        i = j
    return sorted(out)


def method_signature_digest(tokens: list[Token], idx: list[int], head: list[str] | None = None) -> str:
    """A method's digest INCLUDING its own annotations.

    Annotations sit outside the brace range, so a body-only digest cannot see
    `@Transactional` becoming `@Transactional(propagation = REQUIRES_NEW)`. For a caller that
    establishes the transaction boundary of a write nested one level down, that is exactly the
    change a disposition must not survive."""
    body = normalize_tokens([tokens[i] for i in idx])
    ann = ";".join(annotations_from_head(head or []))
    return digest(ann + "|" + body)


def build_caller_index(path: str, tokens: list[Token], by_method: dict[str, list[int]]) -> dict[str, dict[str, str]]:
    """callee simple name -> {qualified caller method -> its signature digest}.

    Name-based, so it can over-match an unrelated same-named method. That is the safe direction:
    over-matching invalidates a disposition that a human then re-confirms, whereas under-matching
    would let a newly introduced unguarded caller inherit an existing approval silently."""
    index: dict[str, dict[str, str]] = {}
    for key, idx in by_method.items():
        _typ, _, meth = key.partition("::")
        if not meth:
            continue
        sig = method_signature_digest(tokens, idx)
        for i in idx:
            t = tokens[i]
            if t.kind == "code" and _IDENT_RE.fullmatch(t.text):
                index.setdefault(t.text, {})[path + "::" + key] = sig
    return index


def dependency_closure(path: str, tokens: list[Token], by_method: dict[str, list[int]],
                       start_key: str, depth: int = 2) -> dict[str, str]:
    """Signature digests of every same-file method within `depth` call-graph hops of the operation,
    in BOTH directions.

    Depth 1 was not enough, twice over. The CAS write lives in `forceParentTransition`, but its
    transaction boundary is `@Transactional` on `replace()` -- two hops upstream, so removing the
    annotation left the fingerprint unchanged. And the startup probe's `NON_WRITER` disposition
    rests on the `enabled()` gate it calls, so making `enabled()` return true unconditionally also
    left the fingerprint unchanged. Callers reach the first, callees the second.

    Same-file only; cross-compilation-unit resolution is a declared coverage boundary."""
    by_simple: dict[str, list[str]] = {}
    for key in by_method:
        _t, _, meth = key.partition("::")
        if meth:
            by_simple.setdefault(meth.split("/")[0], []).append(key)

    heads = java_method_heads(tokens)
    sigs = {k: method_signature_digest(tokens, by_method[k], heads.get(k))
            for k in by_method if k.partition("::")[2]}

    calls: dict[str, set[str]] = {}
    for key, idx in by_method.items():
        if not key.partition("::")[2]:
            continue
        callees: set[str] = set()
        for i in idx:
            t = tokens[i]
            if t.kind == "code" and t.text in by_simple:
                callees.update(k for k in by_simple[t.text] if k != key)
        calls[key] = callees

    out: dict[str, str] = {}
    frontier = {start_key}
    seen = {start_key}
    for _ in range(depth):
        nxt: set[str] = set()
        for k in frontier:
            nxt.update(calls.get(k, set()))
            nxt.update(other for other, cs in calls.items() if k in cs)
        nxt -= seen
        for k in sorted(nxt):
            if k in sigs:
                out[path + "::" + k] = sigs[k]
        seen |= nxt
        frontier = nxt
    return out


#: Mutating a collection mapped with cascade/orphanRemoval issues INSERTs and DELETEs on flush with
#: no write-method call anywhere on the line. `COLLECTION_MUTATORS` was declared in v2 but never
#: consulted, so `portfolio.getHoldings().clear()` -- a full child-row delete -- produced no subject.
_MAPPED_ANNOTATIONS = ("OneToMany", "ManyToMany", "ElementCollection")


def mapped_collection_names(tokens: list[Token], contexts: list[tuple[str, str]]) -> set[str]:
    names: set[str] = set()
    for i, (typ, meth) in enumerate(contexts):
        if meth:
            continue
        tok = tokens[i]
        if tok.kind != "code" or tok.text != "@" or i + 1 >= len(tokens):
            continue
        if tokens[i + 1].text not in _MAPPED_ANNOTATIONS:
            continue
        j, depth, last = i + 2, 0, None
        while j < len(tokens):
            t = tokens[j]
            if t.kind == "code" and t.text == "(":
                depth += 1
            elif t.kind == "code" and t.text == ")":
                depth -= 1
            elif depth == 0 and t.kind == "code" and t.text in (";", "="):
                break
            elif depth == 0 and t.kind == "code" and _IDENT_RE.fullmatch(t.text):
                last = t.text
            j += 1
        if last:
            names.add(last)
            names.add("get" + last[0].upper() + last[1:])
    return names


#: SQL-bearing calls whose effect depends on the STATEMENT, never on the method name (F10).
#: `queryForObject` executes `DELETE ... RETURNING id` perfectly well -- PostgreSQL returns a result
#: set for a data-modifying statement with RETURNING -- so no name-based read-only list is sound.
#: Each such call resolves its SQL through the same S0/S1/S2 shapes as a write: a resolved read-only
#: statement accounts for its receiver and is not a writer; a resolved statement carrying DML/DDL
#: (or invoking a persistent mutating function) IS a writer and enters the inventory; an unresolved
#: statement is UNSUPPORTED. `prepareStatement("<DML>")` is treated as the write site; a chained
#: `ps.execute()` with no statement of its own stays UNSUPPORTED rather than being "followed".
SQL_QUERY_METHODS = {
    "query", "queryForObject", "queryForList", "queryForMap", "queryForRowSet", "queryForStream",
    "queryForInt", "queryForLong", "sql", "createQuery", "createNativeQuery", "prepareStatement",
    "prepareCall", "executeQuery",
}

#: JPQL-bearing calls: their targets are ENTITY names, mapped to tables through the entity index at
#: effect-classification time; an unmapped entity makes the effect UNRESOLVED.
JPQL_METHODS = {"createQuery"}

#: Read-only lead verbs (token-based, lower-cased).
_READ_ONLY_LEADS = frozenset({"select", "with", "values", "explain", "show"})

#: Bare identifier tokens whose presence anywhere outside literals/comments means the statement is
#: not a plain read: DML/DDL verbs, procedure/transaction/sequence/lock constructs. Token-based, so a
#: routine NAMED like one of these (`nextval_like`) is a different token and is judged as a routine.
NON_READ_TOKENS = frozenset({
    "insert", "update", "delete", "truncate", "merge", "create", "drop", "alter", "grant", "revoke",
    "call", "do", "perform", "into", "lock", "nextval", "setval", "refresh", "copy", "execute",
    "vacuum", "analyze", "analyse", "cluster", "reindex", "reassign", "comment", "notify", "listen",
    "unlisten", "discard", "prepare", "deallocate", "declare", "fetch", "move", "close", "begin",
    "commit", "rollback", "savepoint", "release", "set", "reset", "load", "checkpoint", "import",
    "security", "returning",
})

#: The ONLY routines a read-only statement may invoke: PostgreSQL built-ins with no side effects
#: (aggregates, scalar/date/string/JSON helpers, casts) plus the keywords that legitimately precede
#: a `(`. This is an explicit allowlist, not a denylist: a routine whose name is merely ABSENT from
#: the tracked migrations has unknown effects -- it may be a live-database object outside source
#: analysis -- and unknown effects never inherit purity. `SELECT external_mutator()` is therefore NOT
#: read-only even though no migration defines it.
READ_ONLY_SQL_BUILTINS = frozenset({
    "count", "sum", "min", "max", "avg", "bool_and", "bool_or", "every", "array_agg", "string_agg",
    "json_agg", "jsonb_agg", "json_object_agg", "jsonb_object_agg", "json_build_object",
    "jsonb_build_object", "coalesce", "nullif", "greatest", "least", "cast", "abs", "round", "floor",
    "ceil", "ceiling", "trunc", "mod", "power", "sqrt", "sign", "length", "char_length",
    "character_length", "lower", "upper", "trim", "ltrim", "rtrim", "btrim", "substring", "substr",
    "left", "right", "replace", "position", "strpos", "concat", "concat_ws", "split_part", "format",
    "to_char", "to_number", "to_date", "to_timestamp", "date_trunc", "date_part", "extract", "age",
    "now", "current_timestamp", "current_date", "current_time", "localtimestamp", "localtime",
    "clock_timestamp", "statement_timestamp", "transaction_timestamp", "timezone",
    "current_user", "current_schema", "current_database", "session_user", "version",
    "pg_typeof", "pg_backend_pid", "txid_current", "pg_current_xact_id", "pg_is_in_recovery",
    "row_number", "rank", "dense_rank", "lag", "lead", "first_value", "last_value", "nth_value",
    "exists", "any", "all", "some", "in", "not", "and", "or", "on", "as", "where", "from", "join",
    "select", "values", "with", "over", "partition", "filter", "within", "group", "order", "having",
    "limit", "offset", "union", "except", "intersect", "case", "when", "then", "else", "end", "is",
    "null", "true", "false", "distinct", "between", "like", "ilike", "similar", "using", "lateral",
    "unnest", "generate_series", "array", "row", "interval", "date", "time", "timestamp", "text",
    "varchar", "numeric", "decimal", "integer", "int", "bigint", "boolean", "uuid", "jsonb", "json",
    "by", "asc", "desc", "nulls", "first", "last",
    # keywords that legitimately precede `(` in DDL/DML shapes (not routines)
    "conflict", "primary", "key", "unique", "check", "constraint", "references", "foreign", "index",
    "table", "column", "type", "default", "returns", "language", "collate", "cascade", "restrict",
    "view", "function", "procedure", "trigger", "add", "rename", "to", "into", "set", "returning",
    "insert", "update", "delete", "merge", "coalesce",
    # side-effect-free value generators commonly used in DEFAULT clauses
    "gen_random_uuid", "uuid_generate_v4", "random", "md5",
})

#: The only qualifier under which an allowlisted built-in is still accepted as that built-in. Any
#: other schema qualifier names a routine in ANOTHER schema -- PostgreSQL restricts a qualified
#: lookup to exactly that schema -- so `custom_schema.lower(...)` is not the built-in `lower`.
READ_ONLY_BUILTIN_SCHEMAS = frozenset({"pg_catalog"})


class SqlLexError(ValueError):
    """SQL syntax outside the supported lexical subset. Never swallowed: the statement cannot be
    proven read-only and takes the blocking writer path."""


_SQL_BARE_IDENT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_$]*")
_SQL_NUMBER_RE = re.compile(r"(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?")
_SQL_DOLLAR_OPEN_RE = re.compile(r"\$([A-Za-z_][A-Za-z0-9_]*)?\$")
_SQL_PARAM_RE = re.compile(r"\$\d+")
_SQL_PUNCT = set("()[],;.:+-*/<>=~!@#%^&|`?{}\\")
_SQL_WS = " \t\r\n\f\v"


def _is_ident_char(ch: str) -> bool:
    """Any character PostgreSQL would keep INSIDE an identifier: ASCII/Unicode letters, digits,
    `_`, `$`. Used only to detect that a supported ASCII identifier CONTINUES into unsupported
    territory (`lower` followed by e-acute), so the prefix is never accepted on its own."""
    return ch.isalnum() or ch in "_$"


def lex_sql(sql: str) -> list[tuple[str, str]]:
    """Tokenize ORIGINAL (un-normalized) SQL into (kind, text) tokens -- kinds `ident` (bare,
    lower-cased), `qident` (double-quoted, exact), `literal`, `number`, `param`, `punct` -- dropping
    whitespace and comments. Contract:

      * Every input span belongs to exactly one token, literal, comment or whitespace run; anything
        else raises SqlLexError. Nothing is discarded or reinterpreted as harmless grouping.
      * A line comment ends at the ORIGINAL line boundary (LF or CR); this runs before any
        whitespace normalization, so a call on the next line survives.
      * Block comments nest, as PostgreSQL's do; an unterminated one raises.
      * Identifiers are atomic: a bare identifier is consumed to its full extent, and if it continues
        into a character outside the ASCII subset (`lower` + e-acute), or a non-ASCII character
        appears where a token may start (e-acute + `lower`, a CJK name), the whole statement is
        rejected -- a supported prefix never exposes an allowed suffix. `$` inside an identifier belongs to the identifier
        (`evil$tag$body$tag$` is one name); dollar quoting is recognized only at a token boundary.
      * Literal forms with their own escape rules (`E'..'`, `B'..'`, `X'..'`, `N'..'`, `U&'..'`,
        `U&"..."`) are outside the subset and raise rather than being half-parsed.
    """
    tokens: list[tuple[str, str]] = []
    i, n = 0, len(sql)
    while i < n:
        ch = sql[i]
        if ch in _SQL_WS:
            i += 1
            continue
        if sql.startswith("--", i):
            j = i + 2
            while j < n and sql[j] not in "\r\n":
                j += 1
            i = j
            continue
        if sql.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if sql.startswith("/*", j):
                    depth += 1
                    j += 2
                elif sql.startswith("*/", j):
                    depth -= 1
                    j += 2
                else:
                    j += 1
            if depth:
                raise SqlLexError("unterminated block comment")
            i = j
            continue
        if ch == "'":
            j = i + 1
            while j < n:
                if sql[j] == "'" and j + 1 < n and sql[j + 1] == "'":
                    j += 2
                    continue
                if sql[j] == "'":
                    break
                j += 1
            if j >= n:
                raise SqlLexError("unterminated string literal")
            tokens.append(("literal", sql[i : j + 1]))
            i = j + 1
            continue
        if ch == '"':
            j = i + 1
            while j < n:
                if sql[j] == '"' and j + 1 < n and sql[j + 1] == '"':
                    j += 2
                    continue
                if sql[j] == '"':
                    break
                j += 1
            if j >= n:
                raise SqlLexError("unterminated quoted identifier")
            body = sql[i + 1 : j].replace('""', '"')
            if not body:
                raise SqlLexError("zero-length quoted identifier")
            tokens.append(("qident", body))
            i = j + 1
            continue
        if ch == "$":
            m = _SQL_DOLLAR_OPEN_RE.match(sql, i)
            if m:
                tag = m.group(0)
                j = sql.find(tag, m.end())
                if j == -1:
                    raise SqlLexError("unterminated dollar-quoted string")
                tokens.append(("literal", sql[i : j + len(tag)]))
                i = j + len(tag)
                continue
            m = _SQL_PARAM_RE.match(sql, i)
            if m:
                tokens.append(("param", m.group(0)))
                i = m.end()
                continue
            raise SqlLexError("unsupported `$` token at offset " + str(i))
        m = _SQL_BARE_IDENT_RE.match(sql, i)
        if m:
            end = m.end()
            if end < n and _is_ident_char(sql[end]):
                raise SqlLexError("identifier " + repr(sql[i:end + 1]) + "... contains a character outside "
                                  "the supported ASCII identifier subset")
            text = m.group(0)
            nxt = sql[end] if end < n else ""
            if nxt == "'" and text.lower() in ("e", "b", "x", "n"):
                raise SqlLexError("prefixed string literal " + text + "'...' is outside the supported subset")
            if text.lower() == "u" and sql.startswith("&", end) and end + 1 < n and sql[end + 1] in "'\"":
                raise SqlLexError("unicode-escape literal U&... is outside the supported subset")
            tokens.append(("ident", text.lower()))
            i = end
            continue
        m = _SQL_NUMBER_RE.match(sql, i)
        if m and (ch.isdigit() or (ch == "." and i + 1 < n and sql[i + 1].isdigit())):
            end = m.end()
            if end < n and _is_ident_char(sql[end]):
                raise SqlLexError("trailing junk after numeric literal at offset " + str(i))
            tokens.append(("number", m.group(0)))
            i = end
            continue
        if ch in _SQL_PUNCT:
            tokens.append(("punct", ch))
            i += 1
            continue
        raise SqlLexError("unsupported character " + repr(ch) + " (U+%04X) at offset %d; only ASCII "
                          "identifiers, literals, comments and operators are in the supported subset"
                          % (ord(ch), i))
    return tokens


#: A name followed by `(` right after one of these keywords is a TABLE with a column list
#: (`INSERT INTO t (a, b)`, `CREATE TABLE t (...)`, `REFERENCES t (id)`), not a routine call.
#: Deliberately NOT here: FROM / JOIN, because `FROM external_mutator()` really is a call.
_TABLE_INTRO_KEYWORDS = frozenset({"into", "table", "references", "only"})


#: Procedural languages whose bodies this analyzer lexes as SQL/PL-pgSQL code. Anything else (`c`,
#: `plpython3u`, `plperl`, ...) is an executable body whose content is NOT SQL text and cannot be
#: enumerated -- it is rejected as unsupported, never treated as inert data.
SUPPORTED_BODY_LANGUAGES = frozenset({"plpgsql", "sql"})


def _literal_body_text(text: str) -> str:
    """The code inside a body literal: a dollar-quoted string minus its tags, or a standard
    single-quoted string with `''` un-escaped."""
    if text.startswith("$"):
        tag = text[: text.index("$", 1) + 1]
        return text[len(tag) : -len(tag)]
    return text[1:-1].replace("''", "'")


def _expand_procedural_bodies(tokens: list[tuple[str, str]]) -> list[tuple[str, str]]:
    """PROCEDURAL bodies are executable code, not data: their DML runs when the block or routine
    executes. Recognized executable contexts (F14):

      * a statement leading with `DO` -- its body is its FIRST string literal (dollar-quoted OR
        single-quoted), wherever it sits relative to the optional `LANGUAGE <lang>` clause
        (`DO 'code'`, `DO LANGUAGE plpgsql $$code$$`, `DO $$code$$ LANGUAGE plpgsql`); a DO block
        with no body literal is malformed and raises;
      * `CREATE [OR REPLACE] FUNCTION|PROCEDURE ... AS <literal>` -- the literal after `AS`, with
        `LANGUAGE` before or after it (`BEGIN ATOMIC ... END` bodies are already inline tokens).

    A `LANGUAGE` outside SUPPORTED_BODY_LANGUAGES (C, plpython, ...) raises: the body is executable
    but is not SQL text this analyzer can enumerate, so the statement is unsupported rather than
    quietly inert. Bodies are lexed recursively and spliced in place of the literal token. A dollar
    or single-quoted string anywhere else (`SELECT $$text$$`, `INSERT ... VALUES ('x')`) stays a data
    literal. SqlLexError from a body propagates: an unlexable body makes the whole statement
    unsupported."""
    idents = [t for k, t in tokens if k == "ident"]
    if not idents:
        return list(tokens)
    for n, t in enumerate(idents):
        if t == "language" and n + 1 < len(idents) and idents[n + 1] not in SUPPORTED_BODY_LANGUAGES:
            raise SqlLexError("procedural language " + repr(idents[n + 1]) + " is outside the supported "
                              "body subset; its executable body cannot be enumerated")
    is_do = idents[0] == "do"
    is_routine_def = idents[0] == "create" and any(t in ("function", "procedure") for t in idents[:4])
    out: list[tuple[str, str]] = []
    prev_ident: str | None = None
    body_taken = False
    for kind, text in tokens:
        is_body = False
        if kind == "literal":
            if is_do and not body_taken:
                is_body = True
            elif is_routine_def and prev_ident == "as":
                is_body = True
        if is_body:
            body_taken = True
            out.append(("punct", ";"))
            out.extend(_expand_procedural_bodies(lex_sql(_literal_body_text(text))))
            out.append(("punct", ";"))
        else:
            out.append((kind, text))
        if kind == "ident":
            prev_ident = text
        elif kind != "literal":
            prev_ident = None
    if is_do and not body_taken:
        raise SqlLexError("DO block carries no body literal; its executable content cannot be enumerated")
    return out


def sql_call_names(tokens: list[tuple[str, str]]) -> list[list[tuple[str, bool]]]:
    """Every routine call among lexed tokens as its COMPLETE name: a list of (segment, quoted) pairs,
    qualifiers included, in source order. A call is an identifier chain `ident (. ident)*` -- bare or
    double-quoted segments -- immediately followed by `(`. A `(` preceded by anything else (an
    operator, another `)`, a number, a literal) is grouping, not a call, and a chain introduced by a
    table keyword (`INSERT INTO t (`) is a table with a column list, not a call."""
    calls: list[list[tuple[str, bool]]] = []
    leading = [t for k, t in tokens[:6] if k == "ident"]
    create_index = bool(leading) and leading[0] == "create" and "index" in leading
    for idx, (kind, text) in enumerate(tokens):
        if kind != "punct" or text != "(":
            continue
        j = idx - 1
        chain: list[tuple[str, bool]] = []
        while j >= 0:
            k, t = tokens[j]
            if k == "qident":
                chain.append((t, True))
            elif k == "ident":
                chain.append((t, False))
            else:
                break
            if j - 2 >= 0 and tokens[j - 1] == ("punct", "."):
                j -= 2
                continue
            break
        if not chain:
            continue
        # Walk back over `IF NOT EXISTS` / `ONLY` to the introducing keyword. `ON` introduces a table
        # only in `CREATE INDEX ... ON t (cols)`; elsewhere (`JOIN t ON f(x)`) a call after ON is a call.
        k = j - 1
        while k >= 0 and tokens[k][0] == "ident" and tokens[k][1] in ("if", "not", "exists", "only"):
            k -= 1
        intro = tokens[k] if k >= 0 else None
        if intro is not None and intro[0] == "ident" and (
                intro[1] in _TABLE_INTRO_KEYWORDS or (intro[1] == "on" and create_index)):
            continue
        calls.append(list(reversed(chain)))
    return calls


def _call_display(chain: list[tuple[str, bool]]) -> str:
    return ".".join(('"' + s + '"') if q else s for s, q in chain) + "()"


def sql_read_only_problem(sql: str, mutating_functions: set[str] | None = None) -> str | None:
    """Why the ORIGINAL resolved statement is NOT read-only, or None when it is. Read-only requires:
    successful lexical analysis of the whole statement (`lex_sql`; unsupported syntax is a problem,
    never an empty call list); a read lead verb; no non-read token anywhere (DML/DDL verbs, RETURNING,
    procedure/sequence/lock/INTO constructs); and every invoked routine, identified by its COMPLETE
    name, inside the explicit supported read subset: an unqualified bare (unquoted) name in
    READ_ONLY_SQL_BUILTINS, or the same name qualified by `pg_catalog`. Everything else blocks: a
    routine the tree's migrations define, a routine nothing in source defines (`external_mutator()`),
    a quoted-identifier routine (`"external_mutator"()` -- even `"lower"()`), and a routine in any
    other schema (`custom_schema.lower()` is not the built-in `lower`). No general SQL parser,
    search-path or overload resolution is attempted; the supported subset is exactly what is listed."""
    return assess_sql(sql, mutating_functions)["read_only_problem"]


def _routine_problem(chain: list[tuple[str, bool]], mutating: set[str]) -> str | None:
    """Why a complete call name is NOT a supported side-effect-free built-in, or None."""
    shown = _call_display(chain)
    last, _q = chain[-1]
    if any(q for _s, q in chain):
        return (shown + " is a quoted-identifier routine name; quoted call forms are outside the "
                "supported read-only subset")
    if last in mutating:
        return shown + " is a persistent routine defined by the tree's migrations; its effects are not modeled here"
    if len(chain) > 1:
        qualifier = ".".join(s for s, _q in chain[:-1])
        if qualifier not in READ_ONLY_BUILTIN_SCHEMAS:
            return (shown + " is a routine in schema " + qualifier + "; a schema-qualified routine is not "
                    "the built-in of the same name and its effects cannot be established from source")
    if last not in READ_ONLY_SQL_BUILTINS:
        return (shown + " is a routine whose effects source analysis cannot establish; it is not in the "
                "supported read-only built-in set")
    return None


def assess_sql(sql: str, mutating_functions: set[str] | None = None) -> dict:
    """The ONE structured SQL assessment consumed by operation extraction, effect classification and
    the read-only gate (F13). Returns a dict:

      coverage           RESOLVED, or UNSUPPORTED for unmodeled lexical forms or dynamic SQL -- an
                         UNSUPPORTED assessment must reach the final decision as UNSUPPORTED coverage,
                         never as a resolved write.
      executable_block   True for a DO statement, determined from tokens, never normalized source.
      dynamic_sql        True for an EXECUTE token in a procedural context; its effects are unmodeled.
      facts              `sql_facts` (verbs/tables from executable tokens; literals kept in `sql`).
      unknown_routines   complete names of every invoked routine that is not a supported built-in,
                         with reasons -- unknown effects, so the effect stays UNRESOLVED even when the
                         statement's direct targets are known (`DELETE ... WHERE external_mutator()`).
                         Assessed for writes too; a non-read lead is not a reason to stop looking.
      read_only          True only for a lexically supported statement that leads with a read verb,
                         carries no non-read token, and invokes no unknown routine.
      read_only_problem  the reason read_only is False (None when True).
    """
    if not isinstance(sql, str):
        raise TypeError("assess_sql takes the ORIGINAL SQL text, never normalized facts")
    facts = sql_facts(sql)
    result: dict = {"coverage": RESOLVED, "facts": facts, "unknown_routines": [],
                    "unknown_routine_reasons": [], "read_only": False, "read_only_problem": None,
                    "executable_block": False, "dynamic_sql": False}
    if "lexical_error" in facts:
        result["coverage"] = UNSUPPORTED
        result["read_only_problem"] = ("statement uses SQL syntax this analyzer does not model ("
                                       + facts["lexical_error"] + "); it cannot be proven read-only")
        facts["unknown_routines"] = None  # unknown by construction
        return result
    original_tokens = lex_sql(sql)
    result["executable_block"] = bool(original_tokens) and original_tokens[0] == ("ident", "do")
    tokens = _expand_procedural_bodies(original_tokens)
    # Comments and data literals are already distinct from executable tokens. Conservatively keep
    # dynamic execution unsupported in procedural contexts, including variable/dollar-string commands.
    # A statement that IS an EXECUTE counts as a procedural context too: `EXECUTE <prepared-stmt>` at
    # the top level of a migration runs a command this analyzer never saw, so its effects are equally
    # unmodeled and it must not fall through selection as an ordinary verb-less statement.
    procedural = result["executable_block"] or any(verb in {
        "CREATE FUNCTION", "CREATE OR REPLACE FUNCTION", "CREATE PROCEDURE", "CREATE OR REPLACE PROCEDURE"
    } for verb in facts["verbs"]) or (bool(original_tokens) and original_tokens[0] == ("ident", "execute"))
    result["dynamic_sql"] = procedural and ("ident", "execute") in tokens
    mutating = {f.lower() for f in (mutating_functions or ())}
    for chain in sql_call_names(tokens):
        problem = _routine_problem(chain, mutating)
        if problem:
            result["unknown_routines"].append(_call_display(chain))
            result["unknown_routine_reasons"].append(problem)
    facts["unknown_routines"] = list(result["unknown_routines"])
    if result["dynamic_sql"]:
        result["coverage"] = UNSUPPORTED
        result["read_only_problem"] = "dynamic SQL inside an executable body cannot be modelled statically"
        return result
    if not tokens:
        result["read_only_problem"] = "statement is empty"
        return result
    kind, lead = tokens[0]
    if kind != "ident" or lead not in _READ_ONLY_LEADS:
        result["read_only_problem"] = "statement does not lead with a read verb"
        return result
    for k, t in tokens:
        if k == "ident" and t in NON_READ_TOKENS:
            result["read_only_problem"] = "statement contains a non-read construct (" + t.upper() + ")"
            return result
    if facts["verbs"]:
        result["read_only_problem"] = "statement carries DML/DDL verb(s) " + ", ".join(facts["verbs"])
        return result
    if result["unknown_routine_reasons"]:
        result["read_only_problem"] = "statement invokes " + result["unknown_routine_reasons"][0]
        return result
    result["read_only"] = True
    return result


def sql_is_read_only(sql: str, mutating_functions: set[str] | None = None) -> bool:
    return assess_sql(sql, mutating_functions)["read_only"]


@dataclass
class Operation:
    subject_id: str
    path: str
    form: str
    receiver: str
    method_name: str
    enclosing_type: str
    enclosing_method: str
    line_hint: int
    coverage: str
    coverage_reason: str
    statement: dict | None
    shape: str | None
    dependencies: list[dict]
    #: "write" for every mutation form and for every SQL-bearing call whose statement is not proven
    #: read-only (including unresolved ones); "read" only for a RESOLVED read-only statement.
    access: str = "write"


def extract_operations(path: str, tokens: list[Token], contexts: list[tuple[str, str]],
                       mapped: set[str] | None = None,
                       mutating_functions: set[str] | None = None) -> tuple[list[Operation], dict[str, str], dict[str, list[int]]]:
    """Every write-like AND SQL-bearing call in one compilation unit, with its resolved statement
    when the shape is supported. Also returns the per-method token index map, used for method digests
    and callers."""
    by_method: dict[str, list[int]] = {}
    for i, (typ, meth) in enumerate(contexts):
        by_method.setdefault(typ + "::" + meth, []).append(i)

    class_level = [i for i, (_, meth) in enumerate(contexts) if meth == ""]
    static_finals = _string_bindings(tokens, class_level, require_static_final=True)

    # Repo-wide, not per-file: the @OneToMany lives on the entity (Portfolio), while the mutation
    # that triggers the cascade lives in a service (HoldingReplacementService.applyChildren calls
    # portfolio.getHoldings().clear()). A per-file set cannot see the accessor there at all.
    mapped = mapped_collection_names(tokens, contexts) if mapped is None else mapped
    targets = WRITE_METHODS | COLLECTION_MUTATORS | SQL_QUERY_METHODS

    ops: list[Operation] = []
    ordinals: dict[str, int] = {}
    for i, tok in enumerate(tokens):
        if tok.kind != "code" or tok.text != "." or i + 2 >= len(tokens):
            continue
        name_tok, open_tok = tokens[i + 1], tokens[i + 2]
        if name_tok.kind != "code" or name_tok.text not in targets:
            continue
        if open_tok.kind != "code" or open_tok.text != "(":
            continue
        if name_tok.text in CALLBACK_METHODS and _first_arg_is_callback(tokens, i + 2):
            continue
        receiver = tokens[i - 1].text if i > 0 and tokens[i - 1].kind == "code" else "?"
        typ, meth = contexts[i]
        if not meth:
            continue
        is_query = name_tok.text in SQL_QUERY_METHODS and name_tok.text not in WRITE_METHODS
        # `remove` is BOTH a JPA write (EntityManager.remove) and a collection mutator. Gating it
        # on a mapped receiver silently deleted every `.remove(` subject, a JPA entity delete
        # included. Only names that are EXCLUSIVELY collection mutators may be skipped when the
        # receiver is not a mapped collection.
        mapped_name = (_mapped_receiver_name(tokens, i, mapped)
                       if name_tok.text in COLLECTION_MUTATORS else None)
        if mapped_name is not None:
            receiver = mapped_name  # `).clear` is not a usable subject identity
        elif name_tok.text in COLLECTION_MUTATORS and name_tok.text not in WRITE_METHODS:
            continue
        locals_map = _string_bindings(tokens, by_method.get(typ + "::" + meth, []), require_static_final=False)
        value, coverage, reason = _resolve_string_arg(tokens, i + 2, static_finals, locals_map)

        is_collection = mapped_name is not None
        needs_sql = (not is_collection) and (is_query or name_tok.text in {
            "update", "execute", "executeUpdate", "batchUpdate", "insert"})
        deps: list[dict] = []
        statement = None
        shape = None
        access = "write"
        if value is not None:
            # ONE structured assessment over the ORIGINAL text (F13): a resolved constant string is
            # NOT the same as resolved SQL coverage. Its lexical failure and unknown-routine facts
            # travel inside `statement` to effect classification; nothing here flattens them.
            assessment = assess_sql(value, mutating_functions)
            statement = assessment["facts"]
            shape = reason
            if name_tok.text in JPQL_METHODS:
                statement["jpql"] = True
            if reason in ("S1", "S2"):
                deps.append({"symbol": tokens[i + 3].text, "shape": reason, "value": value})
            if assessment["coverage"] == UNSUPPORTED:
                cov, cov_reason = UNSUPPORTED, (
                    "constant SQL string resolved via " + reason + ", but " + assessment["read_only_problem"])
            elif is_query and assessment["read_only"]:
                access = "read"
                cov, cov_reason = RESOLVED, ("read-only statement resolved via " + reason
                                             + "; accounts for its receiver and is not a writer")
            else:
                cov, cov_reason = RESOLVED, "statement resolved via " + reason
                if assessment["unknown_routines"]:
                    cov_reason += ("; invokes routine(s) with effects source analysis cannot establish: "
                                   + ", ".join(assessment["unknown_routines"][:3]))
        elif needs_sql:
            cov = UNSUPPORTED
            cov_reason = reason if not is_query else (
                "SQL argument of a query-style call is not a resolvable constant (" + reason
                + "); a data-modifying statement such as DELETE ... RETURNING cannot be excluded")
        elif is_collection:
            # A cascade/orphanRemoval collection mutation issues its INSERTs and DELETEs at flush
            # time. The operation itself is fully identified; the ROW SET it writes is not
            # statically resolvable, which is a declared boundary rather than an unresolved symbol.
            cov, cov_reason = RESOLVED, (
                "cascade/orphanRemoval collection mutation; the written row set is determined at "
                "flush time and is not statically resolvable (declared boundary)")
        else:
            # An ORM call carries no SQL argument. Nothing about it is unresolved, so it is not
            # UNSUPPORTED -- but the target TABLE is not statically known, which is recorded as a
            # stated coverage boundary rather than silently implied.
            cov, cov_reason = RESOLVED, "ORM call; target table not statically resolvable (declared boundary)"

        base = "op:" + typ + "::" + meth + "::" + receiver + "." + name_tok.text
        ordinals[base] = ordinals.get(base, -1) + 1
        ops.append(
            Operation(
                subject_id=base + "#" + str(ordinals[base]),
                path=path,
                form=receiver + "." + name_tok.text,
                receiver=receiver,
                method_name=name_tok.text,
                enclosing_type=typ,
                enclosing_method=meth,
                line_hint=_line_of("", i, tokens),
                coverage=cov,
                coverage_reason=cov_reason,
                statement=statement,
                shape=shape,
                dependencies=deps,
                access=access,
            )
        )
    return ops, static_finals, by_method


def is_ancestor(repo: Path, maybe_ancestor: str, descendant: str) -> bool:
    return subprocess.run(
        ["git", "-C", str(repo), "merge-base", "--is-ancestor", maybe_ancestor, descendant],
        capture_output=True).returncode == 0


def operation_code_facts(
    op: Operation,
    tokens: list[Token],
    by_method: dict[str, list[int]],
    method_head: list[str],
    receiver_persistence_type: str | None,
    entity_mapping_digest_value: str | None,
) -> dict:
    """Tier 0, defined once (correction 6): the normalized operation statement, the enclosing-method
    digest, the fully resolved receiver persistence type, and the relevant entity-mapping digest.

    Closure and caller indexes are NOT here. They were doing a job the envelope does properly: a
    same-file depth-2 closure could never see a transaction boundary three hops up or a new caller
    that mentions neither the class nor the method, and pretending otherwise made the fingerprint
    look stronger than it was. They are emitted as `review_aids` alongside, and are not validity
    inputs.

    Location-free by construction: no line numbers, no blob ids, no disposition status, no reviewer.
    """
    idx = by_method.get(op.enclosing_type + "::" + op.enclosing_method, [])
    return {
        "contract_version": CONTRACT_VERSION,
        "analyzer_version": ANALYZER_VERSION,
        "normalizer_version": NORMALIZER_VERSION,
        "subject_id": op.subject_id,
        "form": op.form,
        "receiver": op.receiver,
        "method_name": op.method_name,
        "statement": op.statement,
        "shape": op.shape,
        "dependencies": sorted(op.dependencies, key=lambda d: d.get("symbol", "")),
        "enclosing_method_digest": method_signature_digest(tokens, idx, method_head),
        "receiver_persistence_type": receiver_persistence_type,
        "entity_mapping_digest": entity_mapping_digest_value,
    }


# --------------------------------------------------------------------------------------
# SQL subject extraction
# --------------------------------------------------------------------------------------

def split_sql_statements(src: str) -> list[tuple[int, str]]:
    """Split on semicolons OUTSIDE comments, string literals and dollar-quoted bodies.

    A naive split shreds this repo's V17/V18/V19 migrations into ~30 fragments, because their
    PL/pgSQL function bodies are full of internal semicolons."""
    out: list[tuple[int, str]] = []
    i = 0
    n = len(src)
    start = 0
    line = 1
    start_line = 1
    while i < n:
        ch = src[i]
        if ch == "\n":
            line += 1
            i += 1
            continue
        if src.startswith("--", i):
            j = src.find("\n", i)
            i = n if j == -1 else j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            if j == -1:
                break
            line += src.count("\n", i, j)
            i = j + 2
            continue
        if ch == "'":
            j = i + 1
            while j < n:
                if src[j] == "'" and j + 1 < n and src[j + 1] == "'":
                    j += 2
                    continue
                if src[j] == "'":
                    break
                j += 1
            line += src.count("\n", i, min(j, n))
            i = j + 1
            continue
        m = _DOLLAR_TAG.match(src, i)
        if m:
            tag = m.group(0)
            j = src.find(tag, m.end())
            if j == -1:
                break
            line += src.count("\n", i, j)
            i = j + len(tag)
            continue
        if ch == ";":
            text = src[start : i + 1].strip()
            if text:
                out.append((start_line, text))
            i += 1
            start = i
            start_line = line
            continue
        i += 1
    tail = src[start:].strip()
    if tail:
        out.append((start_line, tail))
    return out


def extract_sql_subjects(path: str, src: str) -> list[Finding]:
    """SQL was entirely invisible to v1: the writer scan opened only `*.java` files, and the content
    guard listed `.sql` while matching only presence symbols. Persistent, reusable objects (a
    function, a procedure, a trigger, an FK cascade) are separated from one-time historical DML,
    because only the former stays callable after the migration has run -- which is the whole shape
    of the R3 finding."""
    findings: list[Finding] = []
    for ordinal, (line_no, stmt) in enumerate(split_sql_statements(src)):
        collapsed = normalize_sql(stmt)
        # The SAME structured assessment as the Java path (F13/F14): ORIGINAL statement text, so
        # comment boundaries survive; unknown routines and lexical failures reach the subject.
        assessment = assess_sql(stmt)
        facts = assessment["facts"]
        lexical_error = facts.get("lexical_error")
        unknown_routines = assessment["unknown_routines"]
        persistent = re.search(
            r"CREATE\s+(?:OR\s+REPLACE\s+)?(?:FUNCTION|PROCEDURE|TRIGGER)|ON\s+DELETE\s+(?:CASCADE|SET\s+NULL|SET\s+DEFAULT)|ON\s+UPDATE\s+CASCADE",
            collapsed,
            re.IGNORECASE,
        )
        executable_block = assessment["executable_block"]
        unsupported = assessment["coverage"] == UNSUPPORTED
        # Unsupported execution must survive selection even without a direct DML verb or call.
        if not (facts["verbs"] or persistent or unsupported or unknown_routines or executable_block):
            continue
        # Digest-keyed, NOT ordinal-keyed: an ordinal silently retargets every later subject
        # when a statement is inserted above it, which is the "renumber into meaninglessness"
        # failure the review named. Editing a statement changes its id, surfacing as a
        # MISSING_SUBJECT/UNREVIEWED pair -- the same signal a moved Java operation produces.
        if lexical_error:
            label = "UNSUPPORTED"
        elif facts["verbs"]:
            label = ",".join(facts["verbs"])
        elif persistent:
            label = "PERSISTENT"
        elif unsupported:
            # Verb-less unmodeled execution (a dynamic DO block, a top-level EXECUTE). Verbs and
            # persistent objects keep their own labels first, so V17's dynamic functions retain
            # their existing `sql:CREATE OR REPLACE FUNCTION:` subject ids.
            label = "UNSUPPORTED"
        elif unknown_routines:
            label = "CALL"
        else:
            label = "DO"
        subject = "sql:" + label + ":" + digest(collapsed)[7:15] + "#" + str(ordinal % 1)
        if lexical_error:
            kind, detail = UNSUPPORTED, ("SQL syntax this analyzer does not model (" + lexical_error
                                         + "); the statement's writes cannot be enumerated")
        elif unsupported:
            kind, detail = UNSUPPORTED, assessment["read_only_problem"]
        elif unknown_routines and not facts["verbs"]:
            kind, detail = UNREVIEWED, ("statement invokes routine(s) whose effects source analysis cannot "
                                        "establish: " + ", ".join(unknown_routines[:3])
                                        + "; no reviewed disposition")
        elif executable_block and not facts["verbs"]:
            kind, detail = UNREVIEWED, "executable DO block with no reviewed disposition"
        else:
            kind, detail = UNREVIEWED, "SQL mutation site with no reviewed disposition"
        findings.append(
            Finding(
                path=path,
                subject_id=subject,
                obligation="writer-inventory",
                kind=kind,
                detail=detail,
                evidence={
                    "code_fingerprint": canonical_fingerprint({
                        "contract_version": CONTRACT_VERSION,
                        "analyzer_version": ANALYZER_VERSION,
                        "normalizer_version": NORMALIZER_VERSION,
                        "subject_id": subject,
                        "statement": facts,
                        "persistent_reusable": bool(persistent),
                    }),
                    "verbs": facts["verbs"],
                    "target_tables": facts["target_tables"],
                    "persistent_reusable": bool(persistent),
                    "line_hint": line_no,
                    "statement_digest": digest(collapsed),
                    "excerpt": collapsed[:300],
                },
            )
        )
    return findings


# --------------------------------------------------------------------------------------
# Per-holding structural state (non-goal 10.4)
# --------------------------------------------------------------------------------------


def declared_members(tokens: list[Token], contexts: list[tuple[str, str]], type_name: str) -> list[str] | None:
    """Component/field names of a record OR a class.

    v1 could only read `record X(...)` headers, so the reviewer's probe -- adding a field to the
    `AssetHolding` CLASS -- was undetectable by construction. Both shapes are handled here."""
    for i, tok in enumerate(tokens):
        if tok.kind != "code" or tok.text != "record" or i + 2 >= len(tokens):
            continue
        if tokens[i + 1].text != type_name or tokens[i + 2].text != "(":
            continue
        depth = 0
        names: list[str] = []
        last_ident = None
        j = i + 2
        while j < len(tokens):
            t = tokens[j]
            if t.kind == "code" and t.text in ("(", "<"):
                depth += 1
            elif t.kind == "code" and t.text in (")", ">"):
                depth -= 1
                if depth == 0:
                    if last_ident:
                        names.append(last_ident)
                    return names
            elif t.kind == "code" and t.text == "," and depth == 1:
                if last_ident:
                    names.append(last_ident)
                last_ident = None
            elif t.kind == "code" and _IDENT_RE.fullmatch(t.text):
                last_ident = t.text
            j += 1
        return names
    members: list[str] = []
    seen_type = False
    for i, (typ, meth) in enumerate(contexts):
        if meth or typ.split(".")[-1] != type_name:
            continue
        seen_type = True
    if not seen_type:
        return None
    idx = [i for i, (typ, meth) in enumerate(contexts) if not meth and typ.split(".")[-1] == type_name]
    for stmt in _statements(tokens, idx):
        words = _strip_annotations([tokens[i].text for i in stmt if tokens[i].kind == "code"])
        if not words or "(" in words or "=" in words:
            continue
        if len(words) < 2:
            continue
        if _IDENT_RE.fullmatch(words[-1]) and words[-1] not in _TYPE_KEYWORDS:
            members.append(words[-1])
    return members


def _strip_annotations(words: list[str]) -> list[str]:
    """Drop leading `@Name(...)` runs. Without this, every annotated JPA field is discarded
    because its annotation arguments contain `(` and `=`, so an @Entity class appeared to
    declare almost no fields at all."""
    out = list(words)
    while out and out[0] == "@":
        out = out[1:]
        if out:
            out = out[1:]
        if out and out[0] == "(":
            depth = 0
            for i, w in enumerate(out):
                if w == "(":
                    depth += 1
                elif w == ")":
                    depth -= 1
                    if depth == 0:
                        out = out[i + 1:]
                        break
            else:
                return []
    return out


def entity_setter_subjects(path: str, tokens: list[Token], contexts: list[tuple[str, str]],
                           entity_index: dict | None = None) -> list[Finding]:
    """Setter declarations on @Entity types, each carrying a Tier-0 fingerprint so its disposition
    goes through the same mandatory fingerprint gate as any other subject (item 3)."""
    findings: list[Finding] = []
    entity_types: set[str] = set()
    for i, tok in enumerate(tokens):
        if tok.kind == "code" and tok.text in _TYPE_KEYWORDS and i + 1 < len(tokens):
            if any("Entity" in a for a in _annotations_before(tokens, i)):
                entity_types.add(tokens[i + 1].text)
    if not entity_types:
        return findings
    by_method: dict[str, list[int]] = {}
    for i, (typ, meth) in enumerate(contexts):
        if meth:
            by_method.setdefault(typ + "::" + meth, []).append(i)
    heads = java_method_heads(tokens)
    seen: set[str] = set()
    for i, (typ, meth) in enumerate(contexts):
        if not meth or typ.split(".")[-1] not in entity_types:
            continue
        name = meth.split("/")[0]
        if not name.startswith("set") or len(name) < 4 or not name[3].isupper():
            continue
        subject = "set:" + typ + "::" + meth
        if subject in seen:
            continue
        seen.add(subject)
        key = typ + "::" + meth
        method_digest = method_signature_digest(tokens, by_method.get(key, []), heads.get(key))
        mapping = (entity_index or {}).get(typ.split(".")[-1], {}).get("mapping_digest")
        fp = digest("|".join([CONTRACT_VERSION, ANALYZER_VERSION, subject, method_digest, mapping or ""]))
        findings.append(Finding(
            path, subject, "writer-inventory", UNREVIEWED,
            "managed-entity setter: a call on a managed instance is an UPDATE at flush time",
            {"entity_type": typ, "method": meth, "code_fingerprint": fp,
             "boundary": "call sites are not enumerated; receiver managed-ness is not resolved"}))
    return findings


#: Extensions whose content can issue DML outside the JVM. v2 walked only .java and .sql, so a
#: committed Python DELETE produced no finding at all.
SCRIPT_EXTENSIONS = (".py", ".js", ".mjs", ".ts", ".sh", ".ps1", ".rb", ".lua")

#: A bare DML verb is far too weak outside SQL files (crypto .update(), a PowerShell 'update'
#: literal). A script detector must require the full CLAUSE shape -- a verb bound to a target.
SCRIPT_DML_RE = re.compile(
    r"""\b(?:
          INSERT\s+INTO\s+["'`]?[A-Za-z_][\w.]*
        | UPDATE\s+["'`]?[A-Za-z_][\w.]*\s+SET\b
        | DELETE\s+FROM\s+["'`]?[A-Za-z_][\w.]*
        | TRUNCATE\s+(?:TABLE\s+)?["'`]?[A-Za-z_][\w.]*
        | MERGE\s+INTO\s+["'`]?[A-Za-z_][\w.]*
        | DROP\s+(?:TABLE|FUNCTION|PROCEDURE|TRIGGER|INDEX)\s+["'`]?[A-Za-z_][\w.]*
        | (?:GRANT|REVOKE)\s+[A-Za-z, ]+\s+ON\s+["'`]?[A-Za-z_][\w.]*
    )""",
    re.IGNORECASE | re.VERBOSE,
)


def script_dml_subjects(path: str, src: str) -> list[Finding]:
    """Scripts that appear to issue DML.

    Statically deciding whether a script really executes a statement -- and against which database
    -- is beyond this analyzer, so these are reported UNSUPPORTED and BLOCK, rather than being
    silently absent as they were in v2, where a committed Python DELETE produced no finding at all.
    A read-only script is cleared by a reviewed disposition, never by the tool guessing."""
    findings: list[Finding] = []
    seen_spans: set[str] = set()
    # Scan the WHOLE file, not line by line: a triple-quoted statement whose verb and target sit on
    # different physical lines is invisible to a per-line scan. SCRIPT_DML_RE already permits a
    # whitespace run (newlines included) between clause tokens.
    for m in SCRIPT_DML_RE.finditer(src):
        clause = " ".join(m.group(0).split())
        if clause in seen_spans:
            continue
        seen_spans.add(clause)
        lineno = src.count("\n", 0, m.start()) + 1
        findings.append(Finding(
            path,
            "script:" + clause.split()[0].upper() + ":" + digest(clause)[7:15],
            "writer-inventory", UNSUPPORTED,
            "script appears to issue DML; static analysis cannot establish whether it executes",
            {"clause": clause, "line_hint": lineno, "excerpt": clause[:240]}))
    return findings


def per_holding_state(tree: dict[str, str], reader: BlobReader, gc5: dict) -> list[Finding]:
    spec = gc5["per_holding_freshness_structural_check"]
    baseline = {t["file"] + "::" + t["record_name"]: t["baseline_fields"] for t in spec["types"]}
    scan_globs = spec.get("discovery_globs", ["portfolio-service/src/main/**/*.java"])
    name_re = re.compile(spec.get("type_name_pattern", r"Holding"))
    findings: list[Finding] = []
    discovered: set[str] = set()

    for path, blob in sorted(tree.items()):
        if not any(glob_match(path, g) for g in scan_globs):
            continue
        src = reader.text(blob)
        if src is None:
            continue
        try:
            tokens = lex_java(src)
        except LexError as exc:
            findings.append(Finding(path, "file:", "per-holding-state", UNSUPPORTED, str(exc)))
            continue
        contexts = java_contexts(tokens)
        for i, tok in enumerate(tokens):
            if tok.kind != "code" or tok.text not in _TYPE_KEYWORDS or i + 1 >= len(tokens):
                continue
            tname = tokens[i + 1].text
            if not name_re.search(tname):
                continue
            # A repository interface and a service class both match a name pattern without
            # carrying any per-holding state. Only records and @Entity classes do.
            if tok.text != "record" and not any(
                "Entity" in a for a in _annotations_before(tokens, i)
            ):
                continue
            key = path + "::" + tname
            discovered.add(key)
            members = declared_members(tokens, contexts, tname)
            if members is None:
                continue
            if key not in baseline:
                findings.append(
                    Finding(
                        path, "type:" + tname, "per-holding-state", UNREVIEWED,
                        "per-holding type is not in the reviewed structural baseline",
                        {"declared_members": members},
                    )
                )
                continue
            extra = [m for m in members if m not in baseline[key]]
            missing = [m for m in baseline[key] if m not in members]
            if extra or missing:
                findings.append(
                    Finding(
                        path, "type:" + tname, "per-holding-state", UNREVIEWED,
                        "declared members diverge from the reviewed baseline",
                        {"extra": extra, "missing": missing, "baseline": baseline[key]},
                    )
                )
    for key in baseline:
        if key not in discovered:
            p, _, tname = key.partition("::")
            findings.append(
                Finding(p, "type:" + tname, "per-holding-state", MISSING_SUBJECT,
                        "baseline type not found at the cut; the structural check cannot run unattended"))
    return findings


# --------------------------------------------------------------------------------------
# Writer inventory
# --------------------------------------------------------------------------------------


def _all_dispositions(policy: dict) -> dict[str, dict]:
    """Addendum schema `dispositions`, plus the legacy bucket lists, keyed path|subject_id."""
    idx: dict[str, dict] = {}
    for entry in policy.get("writer_inventory", {}).get("production_writers", []) + \
            policy.get("writer_inventory", {}).get("flagged_writers_outside_holding_replacement_service", []) + \
            policy.get("writer_inventory", {}).get("classified_non_writers", []) + \
            policy.get("writer_inventory", {}).get("sql_writers", []) + \
            policy.get("dispositions", []):
        if entry.get("subject_id") and entry.get("path"):
            idx[entry["path"] + "|" + entry["subject_id"]] = entry
    return idx


def resolve_receiver(op: "Operation", types: dict[str, str], entity_index: dict[str, dict],
                     store_by_type: dict[str, str]) -> tuple[str | None, str | None, dict | None, str | None]:
    """Receiver persistence type, store, resolved entity, and mapping digest for one operation.

    Shared by writer_inventory and the test harness so the two can never drift on how a receiver is
    typed -- the fingerprint is only as trustworthy as this being identical in both places."""
    receiver_type = types.get(op.receiver)
    if receiver_type is None and op.receiver in store_by_type:
        # A static utility receiver written by its class name (e.g. `MDC.remove(...)`) is its own
        # type; without this it reads as an unresolved receiver and needlessly governs its module.
        receiver_type = op.receiver
    store = store_by_type.get(receiver_type) if receiver_type else None
    if receiver_type and receiver_type in entity_index:
        store = STORE_POSTGRES
    entity = entity_index.get(receiver_type) if receiver_type else None
    if entity is None and op.statement:
        for _name, meta in entity_index.items():
            if meta.get("table") and meta["table"].lower() in set(op.statement["target_tables"]):
                entity = meta
                break
    mapping_digest = entity["mapping_digest"] if entity else None
    if op.form.startswith("holdings.") or op.form.startswith("getHoldings."):
        owner = entity_index.get("Portfolio")
        mapping_digest = owner["mapping_digest"] if owner else mapping_digest
        store = store or STORE_POSTGRES
        receiver_type = receiver_type or "Portfolio"
    return receiver_type, store, entity, mapping_digest


def _store_by_type(policy: dict) -> dict[str, str]:
    store_by_type = dict(_DEFAULT_STORE_BY_TYPE)
    for name in policy.get("non_persistence_receiver_types", []):
        store_by_type.setdefault(name, STORE_MEMORY)
    return store_by_type


_SQL_FUNCTION_DEF_RE = re.compile(
    r"CREATE\s+(?:OR\s+REPLACE\s+)?(?:FUNCTION|PROCEDURE)\s+(?:[\w\"]+\.)?[\"]?([A-Za-z_][\w]*)",
    re.IGNORECASE)


def sql_mutating_functions(tree: dict[str, str], reader: BlobReader, excluded: list[str]) -> set[str]:
    """Names of every persistent SQL function/procedure defined in the tree's .sql files. A SELECT
    that invokes one of them is not read-only (F10): `SELECT repair_migrate_holdings(...)` is exactly
    the R3 shape. Whether the function actually mutates is not decided here -- a persistent
    callable is conservatively treated as one."""
    names: set[str] = set()
    for path in sorted(tree):
        if not path.endswith(".sql") or any(glob_match(path, g) for g in excluded):
            continue
        src = reader.text(tree[path])
        if src is None:
            continue
        for _line, stmt in split_sql_statements(src):
            m = _SQL_FUNCTION_DEF_RE.search(normalize_sql(stmt))
            if m:
                names.add(m.group(1).lower())
    return names


@dataclass
class TreeAnalysis:
    """Everything a subject fingerprint depends on, computed ONCE per tree so the cut and every
    reviewed commit go through byte-identical inputs (F9)."""

    lexed: dict[str, tuple[list[Token], list[tuple[str, str]]]]
    lex_failures: dict[str, str]
    mapped: set[str]
    entity_index: dict[str, dict]
    store_by_type: dict[str, str]
    mutating_functions: set[str]
    excluded: list[str]


def analyze_tree(tree: dict[str, str], reader: BlobReader, policy: dict) -> TreeAnalysis:
    excluded = [e["glob"] for e in policy.get("writer_inventory", {}).get("excluded_from_recheck", [])]
    excluded += active_scan_exclusions(policy, tree)
    lexed: dict[str, tuple[list[Token], list[tuple[str, str]]]] = {}
    lex_failures: dict[str, str] = {}
    for path, blob in sorted(tree.items()):
        if not path.endswith(".java") or "/src/main/" not in path:
            continue
        if any(glob_match(path, g) for g in excluded):
            continue
        src = reader.text(blob)
        if src is None:
            continue
        try:
            tokens = lex_java(src)
        except LexError as exc:
            lex_failures[path] = str(exc)
            continue
        lexed[path] = (tokens, java_contexts(tokens))
    mapped: set[str] = set()
    for tokens, contexts in lexed.values():
        mapped |= mapped_collection_names(tokens, contexts)
    return TreeAnalysis(lexed=lexed, lex_failures=lex_failures, mapped=mapped,
                        entity_index=build_entity_index(lexed), store_by_type=_store_by_type(policy),
                        mutating_functions=sql_mutating_functions(tree, reader, excluded), excluded=excluded)


def unit_operations(path: str, tokens: list[Token], contexts: list[tuple[str, str]],
                    analysis: TreeAnalysis) -> tuple[list[tuple], dict[str, list[int]], dict[str, list[str]], dict[str, str]]:
    """THE one code path for a Java operation's identity and Tier-0 fingerprint: returns
    (op, receiver_type, store, entity, mapping_digest, fingerprint) per operation, plus the
    per-method index, declaration heads and declared types. writer_inventory uses it at the cut and
    `subject_index` uses it at every reviewed commit, so the two cannot drift."""
    ops, _finals, by_method = extract_operations(path, tokens, contexts, analysis.mapped, analysis.mutating_functions)
    heads = java_method_heads(tokens)
    types = declared_types(tokens, contexts)
    out: list[tuple] = []
    for op in ops:
        rt, store, entity, md = resolve_receiver(op, types, analysis.entity_index, analysis.store_by_type)
        facts = operation_code_facts(op, tokens, by_method,
                                     heads.get(op.enclosing_type + "::" + op.enclosing_method, []), rt, md)
        out.append((op, rt, store, entity, md, canonical_fingerprint(facts)))
    return out, by_method, heads, types


def subject_index(tree: dict[str, str], reader: BlobReader, policy: dict,
                  analysis: TreeAnalysis | None = None) -> dict[str, str]:
    """{path|subject_id: code_fingerprint} for every Java operation, entity setter and resolvable SQL
    subject in `tree` -- the historical subject index (F9). Built by the same extractors as the cut
    analysis, so a claim's subject at its reviewed_commit is produced by the same code that produces
    it at the cut. Unsupported (dynamic) SQL is indexed too, but it never validates by disposition."""
    analysis = analysis or analyze_tree(tree, reader, policy)
    out: dict[str, str] = {}
    for path, (tokens, contexts) in analysis.lexed.items():
        classified, _by_method, _heads, _types = unit_operations(path, tokens, contexts, analysis)
        for entry in classified:
            out[path + "|" + entry[0].subject_id] = entry[5]
        for f in entity_setter_subjects(path, tokens, contexts, analysis.entity_index):
            out[f.path + "|" + f.subject_id] = f.evidence.get("code_fingerprint")
    for path, blob in sorted(tree.items()):
        if not path.endswith(".sql") or any(glob_match(path, g) for g in analysis.excluded):
            continue
        src = reader.text(blob)
        if src is None:
            continue
        for f in extract_sql_subjects(path, src):
            out[f.path + "|" + f.subject_id] = f.evidence.get("code_fingerprint")
    return out


class HistoricalIndex:
    """Memoised `subject_index` per commit. Shared by claim validation (a claim must reconstruct at
    its own reviewed_commit) and renewal validation (which claims changed between two reviews)."""

    def __init__(self, repo: Path, reader: BlobReader, policy: dict) -> None:
        self.repo = repo
        self.reader = reader
        self.policy = policy
        self._cache: dict[str, dict[str, str]] = {}

    def at(self, commit: str) -> dict[str, str]:
        if commit not in self._cache:
            self._cache[commit] = subject_index(tree_blobs(self.repo, commit), self.reader, self.policy)
        return self._cache[commit]


def writer_inventory(tree: dict[str, str], reader: BlobReader, policy: dict,
                     envelopes: dict | None = None, repo: Path | None = None,
                     cut_sha: str = "", governed: set[str] | None = None,
                     records_by_id: dict | None = None,
                     history: "HistoricalIndex | None" = None) -> tuple[list[Finding], dict]:
    writer_policy = policy.get("writer_inventory", {})
    dispositions = _all_dispositions(policy)
    analysis = analyze_tree(tree, reader, policy)
    excluded = analysis.excluded
    relevant_tables = {t.lower() for t in policy.get("relevant_tables", ["asset_holdings", "portfolios"])}
    envelopes = envelopes or {}
    records_by_id = records_by_id or {}
    governed = governed if governed is not None else set()
    if history is None and repo is not None:
        history = HistoricalIndex(repo, reader, policy)
    # Correction 6: automatic effect clearance is a NAMED, separately-approved feature. It is active
    # only when its policy list is non-empty; while inactive, an UNRELATED write inside a governed
    # deployable does NOT auto-clear -- it follows the explicit review path like any other.
    auto_clear_active = bool(policy.get("effect_based_automatic_clearance"))
    effect_resolutions = {r["path"] + "|" + r["subject_id"]: r
                          for r in policy.get("effect_resolutions", []) if r.get("subject_id")}

    findings: list[Finding] = []
    unverified: list[dict] = []
    unrelated: list[dict] = []
    seen: set[str] = set()
    coverage = {"java_files": len(analysis.lexed) + len(analysis.lex_failures),
                "java_lex_failures": len(analysis.lex_failures), "operations": 0,
                "operations_unsupported": 0, "read_only_statements": 0,
                "sql_files": 0, "sql_subjects": 0, "effects": {},
                "mapped_collections": len(analysis.mapped),
                "persistent_sql_functions": sorted(analysis.mutating_functions)}

    def validate(record, obligation, subject_id, spath, fp):
        return validate_claim_record(record, obligation, subject_id, spath, fp, repo, cut_sha,
                                     envelopes, records_by_id, history)

    for path, problem in sorted(analysis.lex_failures.items()):
        findings.append(Finding(path, "file:", "writer-inventory", UNSUPPORTED,
                                "lexer rejected this file: " + problem))

    roots_union: list[str] = []
    for env in envelopes.values():
        roots_union.extend(env["roots"])
    cascades, unparsed_triggers = fk_cascade_map(tree, reader, roots_union or ["/"])
    coverage["fk_cascades"] = {k: sorted(v) for k, v in cascades.items()}

    for path, (tokens, contexts) in analysis.lexed.items():
        classified_raw, by_method, heads, types = unit_operations(path, tokens, contexts, analysis)
        src_text = " ".join(t.text for t in tokens)
        module = module_of(path)

        resolved_receivers: set[str] = set()
        classified = []
        for (op, receiver_type, store, entity, mapping_digest, fp) in classified_raw:
            if op.coverage == RESOLVED and store == STORE_POSTGRES and receiver_type:
                resolved_receivers.add(receiver_type)
            if read_only_accounted(op, receiver_type, store):
                # The ONE read-only decision (shared with governed_modules): a RESOLVED read-only
                # statement on a receiver RESOLVED to a relational store. It accounts for its
                # receiver (above) and is not a writer; recomputed on every run, never a stored claim.
                # A parsed SELECT on an unknown receiver does NOT qualify and falls through to effect
                # classification, where the unresolved receiver blocks like any other.
                coverage["read_only_statements"] += 1
                continue
            effect, basis = classify_effect(op, receiver_type, store, entity, cascades,
                                            relevant_tables, unparsed_triggers, analysis.entity_index)
            if op.access == "read":
                basis["read_only_statement_demoted"] = ("statement parses as read-only but its receiver "
                                                        "did not resolve to a relational store; JDBC read "
                                                        "semantics are not inherited from a method name")
            classified.append((op, receiver_type, store, entity, mapping_digest, effect, basis, fp))

        for f in persistence_usage_findings(path, tokens, contexts, src_text, resolved_receivers,
                                            types, analysis.store_by_type):
            seen.add(f.path + "|" + f.subject_id + "|persistence-usage")
            findings.append(f)

        for f in entity_setter_subjects(path, tokens, contexts, analysis.entity_index):
            key = f.path + "|" + f.subject_id
            seen.add(key)
            # Setters take the ONE validation path -- status-alone approval is gone, and the setter's
            # own Tier-0 fingerprint is validated (never skipped with None).
            disp = dispositions.get(key)
            if disp is None:
                findings.append(f)
                continue
            problem, kind = validate(disp, "writer-inventory", f.subject_id, f.path,
                                     f.evidence.get("code_fingerprint"))
            if problem:
                findings.append(Finding(f.path, f.subject_id, "writer-inventory", kind, problem))

        for (op, receiver_type, store, entity, mapping_digest, effect, basis, fp) in classified:
            coverage["operations"] += 1
            start_key = op.enclosing_type + "::" + op.enclosing_method
            key = path + "|" + op.subject_id
            seen.add(key)
            coverage["effects"][effect] = coverage["effects"].get(effect, 0) + 1
            review_aids = {"dependency_closure": dependency_closure(path, tokens, by_method, start_key),
                           "note": "review aids only; not validity inputs"}

            if op.coverage == UNSUPPORTED:
                coverage["operations_unsupported"] += 1
                findings.append(Finding(path, op.subject_id, "writer-coverage", UNSUPPORTED,
                                        op.coverage_reason, {"line_hint": op.line_hint}))
                continue

            # Effect resolution: an UNRESOLVED op can be reclassified ONLY through the full validation
            # path, and only with a resolved_effect value. "trust me" evidence no longer clears it.
            if effect == UNRESOLVED:
                resolution = effect_resolutions.get(key)
                if resolution is not None:
                    problem, kind = validate(resolution, "effect-resolution", op.subject_id, path, fp)
                    if problem:
                        findings.append(Finding(path, op.subject_id, "effect-resolution", kind,
                                                "effect resolution is invalid: " + problem, {"basis": basis}))
                        continue
                    if resolution.get("resolved_effect") not in (RELEVANT, UNRELATED):
                        findings.append(Finding(path, op.subject_id, "effect-resolution", UNRESOLVED,
                                                "effect resolution supplies no resolved_effect", {"basis": basis}))
                        continue
                    effect = resolution["resolved_effect"]
                    basis = {**basis, "resolved_by_review": resolution.get("reviewer")}
                else:
                    findings.append(Finding(path, op.subject_id, "writer-inventory", UNRESOLVED,
                                            "effect is UNRESOLVED and blocks like RELEVANT; a disposition "
                                            "cannot clear it without a validated effect resolution",
                                            {"basis": basis, "code_fingerprint": fp,
                                             "line_hint": op.line_hint, "review_aids": review_aids}))
                    continue

            if effect == UNRELATED:
                if auto_clear_active or module not in governed:
                    # Auto-clearance active (separately approved) OR the module is out of B1 scope:
                    # listed with basis, non-blocking, recomputed every run so it can never go stale.
                    unrelated.append({"path": path, "subject_id": op.subject_id, "form": op.form,
                                      "basis": basis, "line_hint": op.line_hint,
                                      "auto_cleared": auto_clear_active})
                    continue
                # Governed deployable, auto-clearance inactive: the table-scope classification is
                # review EVIDENCE, but the operation still needs an explicit, validated disposition.
                disp = dispositions.get(key)
                if disp is None:
                    findings.append(Finding(path, op.subject_id, "writer-inventory", UNREVIEWED,
                                            "UNRELATED by table scope, but automatic effect clearance is "
                                            "inactive; this write needs an explicit reviewed disposition",
                                            {"basis": basis, "code_fingerprint": fp, "line_hint": op.line_hint}))
                    continue
                problem, kind = validate(disp, "writer-inventory", op.subject_id, path, fp)
                if problem:
                    findings.append(Finding(path, op.subject_id, "writer-inventory", kind, problem,
                                            {"basis": basis, "line_hint": op.line_hint}))
                continue

            # RELEVANT
            disp = dispositions.get(key)
            if disp is None:
                findings.append(Finding(path, op.subject_id, "writer-inventory", UNREVIEWED,
                                        "RELEVANT mutation site carries no reviewed disposition",
                                        {"basis": basis, "code_fingerprint": fp, "line_hint": op.line_hint,
                                         "statement": op.statement, "review_aids": review_aids}))
                continue
            problem, kind = validate(disp, "writer-inventory", op.subject_id, path, fp)
            if problem:
                ev = {"basis": basis, "expected": disp.get("code_fingerprint"), "actual": fp,
                      "line_hint": op.line_hint}
                if kind == ENVELOPE_CHANGED:
                    env = envelopes.get(disp.get("envelope_id"), {})
                    delta = env.get("delta", {"added": [], "removed": [], "modified": []})
                    ev["delta"] = delta
                    ev["changed_paths"] = delta["added"] + delta["removed"] + delta["modified"]
                findings.append(Finding(path, op.subject_id, "writer-inventory", kind, problem, ev))

    # Scripts (whole-file, multiline-aware) -- UNSUPPORTED, blocking.
    for path, blob in sorted(tree.items()):
        if not path.endswith(SCRIPT_EXTENSIONS) or any(glob_match(path, g) for g in excluded):
            continue
        src = reader.text(blob)
        if src is None:
            continue
        for f in script_dml_subjects(path, src):
            seen.add(f.path + "|" + f.subject_id)
            findings.append(f)

    # SQL subjects -- one validation path, plus operational records for immutable persistent objects.
    op_records = {r["subject_ref"]["path"] + "|" + r["subject_ref"]["subject_id"]: r
                  for r in policy.get("operational_records", []) if r.get("subject_ref")}
    target_env = policy.get("operational_target_environment")
    for path, blob in sorted(tree.items()):
        if not path.endswith(".sql") or any(glob_match(path, g) for g in excluded):
            continue
        src = reader.text(blob)
        if src is None:
            continue
        coverage["sql_files"] += 1
        owning = _owning_envelope_for(path, envelopes)
        for f in extract_sql_subjects(path, src):
            coverage["sql_subjects"] += 1
            key = f.path + "|" + f.subject_id
            seen.add(key)
            if f.kind == UNSUPPORTED:
                rec = op_records.get(key)
                if rec is not None:
                    problem = validate_operational_record(rec, repo, cut_sha, owning)
                    if problem or (target_env and rec.get("environment_identity") != target_env):
                        detail = problem or ("operational record environment "
                                             + repr(rec.get("environment_identity")) + " is not the "
                                             "declared target " + repr(target_env))
                        findings.append(Finding(f.path, f.subject_id, "operational-record", UNRESOLVED,
                                                detail, {"subject": rec.get("subject_ref")}))
                    else:
                        unverified.append({"path": f.path, "subject_id": f.subject_id,
                                           "basis": "operational_record", "operator": rec["operator"]})
                    continue
                findings.append(f)
                continue
            disp = dispositions.get(key)
            if disp is None:
                findings.append(f)
                continue
            problem, kind = validate(disp, "writer-inventory", f.subject_id, f.path,
                                     f.evidence.get("code_fingerprint"))
            if problem:
                findings.append(Finding(f.path, f.subject_id, "writer-inventory", kind, problem))

    for key, disp in sorted(dispositions.items()):
        if key not in seen:
            p, _, subject = key.partition("|")
            findings.append(Finding(p, subject, "writer-inventory", MISSING_SUBJECT,
                                    "policy classifies a subject that does not exist at the cut"))

    for item in policy.get("unresolved", []):
        findings.append(Finding(item.get("file", "scripts/b1-candidate-policy.json"),
                                "unresolved:" + item.get("id", "?"), "unresolved", UNRESOLVED,
                                item.get("status", "unresolved"),
                                {"summary": item.get("summary", "")[:600]}))
    return findings, {**coverage, "unverified_coverage": unverified, "unrelated_inventory": unrelated}


def _owning_envelope_for(path: str, envelopes: dict) -> dict:
    """The envelope whose roots contain `path`. A migration's operational record binds to ITS
    deployable's migration subset, not to whichever envelope happens to be first."""
    for env in envelopes.values():
        for root in env.get("roots", []):
            if path == root or (root.endswith("/") and path.startswith(root)) or glob_match(path, root):
                return env
    return {}


# --------------------------------------------------------------------------------------
# Tier 1: deployable envelopes
# --------------------------------------------------------------------------------------

#: Build-graph files that belong to EVERY envelope. A change to any of them can change what is
#: packaged or how, so they are part of the deployable's source snapshot even though they contain
#: no application code.
_UNIVERSAL_ROOTS = (
    "build.gradle", "settings.gradle", "gradle.properties",
    "gradle/", "gradlew", "gradlew.bat", "buildSrc/", "build-logic/",
)

_PROJECT_DEP_RE = re.compile(r"""project\s*\(\s*['"]:([A-Za-z0-9_\-]+)['"]\s*\)""")
_ROOT_FILE_RE = re.compile(r"""rootProject\.file\s*\(\s*['"]([^'"]+)['"]\s*\)""")


def derive_envelope_roots(tree: dict[str, str], reader: BlobReader, module: str) -> list[str]:
    """Roots derived from the BUILD GRAPH, not from a hand-written list.

    A hand-written list is exactly how `config/seed-tickers.json` went missing: nothing in the
    module's source tree mentions it, but `portfolio-service/build.gradle` packages it through
    `processResources { from(rootProject.file('config/seed-tickers.json')) }`, so it ships inside
    the artifact. Project dependencies contribute BOTH their sources and their build files -- a
    dependency module's build file can change what that module puts on the classpath."""
    roots: set[str] = {module + "/src/main/", module + "/build.gradle"}
    roots.update(_UNIVERSAL_ROOTS)

    pending = [module]
    seen: set[str] = set()
    while pending:
        mod = pending.pop()
        if mod in seen:
            continue
        seen.add(mod)
        build_file = mod + "/build.gradle"
        blob = tree.get(build_file)
        if blob is None:
            continue
        text = reader.text(blob) or ""
        for dep in _PROJECT_DEP_RE.findall(text):
            roots.add(dep + "/src/main/")
            roots.add(dep + "/build.gradle")
            pending.append(dep)
        for extra in _ROOT_FILE_RE.findall(text):
            # A packaged root-level resource. Missing this is a silent hole in the envelope.
            roots.add(extra.lstrip("./"))
    return sorted(roots)


def envelope_membership(tree: dict[str, str], roots: list[str]) -> list[list[str]]:
    """Sorted (path, blob oid) for every tracked path under the declared roots, at the cut."""
    members: list[list[str]] = []
    for path in sorted(tree):
        for root in roots:
            if path == root or (root.endswith("/") and path.startswith(root)) or glob_match(path, root):
                members.append([path, tree[path]])
                break
    return members


def _digest_of_json(value) -> str:
    return digest(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True))


def envelope_digests(roots: list[str], membership: list[list[str]]) -> dict:
    """Roots and membership are hashed SEPARATELY and then together.

    Hashing membership alone would let a policy edit that NARROWS the roots validate itself: fewer
    roots means fewer members means a different-but-recomputable digest, with nothing recording that
    the boundary moved. `roots_digest` makes the declaration itself reviewable."""
    migrations = [m for m in membership if m[0].endswith(".sql")]
    return {
        "roots_digest": _digest_of_json(sorted(roots)),
        "membership_digest": _digest_of_json(membership),
        "envelope_digest": _digest_of_json({"roots": sorted(roots), "membership": membership}),
        "migration_subset_digest": _digest_of_json(migrations),
        "member_count": len(membership),
    }


def diff_envelope(before: list[list[str]], after: list[list[str]]) -> dict:
    b = {p: o for p, o in before}
    a = {p: o for p, o in after}
    return {
        "added": sorted(set(a) - set(b)),
        "removed": sorted(set(b) - set(a)),
        "modified": sorted(p for p in set(a) & set(b) if a[p] != b[p]),
    }


ENVELOPE_CHANGED = "ENVELOPE_CHANGED"
DISPOSITION_INVALID = "DISPOSITION_INVALID"
POLICY_INVALID = "POLICY_INVALID"
EVIDENCE_BINDING_MISMATCH = "EVIDENCE_BINDING_MISMATCH"


# --------------------------------------------------------------------------------------
# Shared, git-backed validation of every claim-bearing record (F1/F2/F3/F4)
# --------------------------------------------------------------------------------------
#
# One validation path. A disposition, a setter approval, a SQL clearance, an effect resolution, a
# governed path exception and an envelope record are all "someone reviewed X at commit R and asserts
# Y". They therefore share exactly one gate: the reviewer and status are real, the reviewed_commit
# EXISTS in git and is an ancestor of the cut, the reviewed snapshot RECOMPUTES from git to the
# recorded identity, and the subject still matches at the cut. Anything unproven blocks. Alternate
# routes (status-only setters, "trust me" effect overrides, provenance-skipping SQL) are the class of
# bug this consolidation removes.


def commit_exists(repo: Path, sha: str) -> bool:
    if not isinstance(sha, str) or not _SHA_RE.match(sha):
        return False
    return subprocess.run(["git", "-C", str(repo), "cat-file", "-e", sha + "^{commit}"],
                          capture_output=True).returncode == 0


def envelope_at_commit(repo: Path, reader: "BlobReader", module: str, commit: str) -> dict | None:
    """Recompute a deployable's envelope from the tree AT `commit`. None if the commit is absent.

    This is what makes an envelope record's provenance checkable: the record claims a digest was
    reviewed at R; we rebuild the envelope from R's actual tree and require it to match. A record
    whose reviewed_commit does not exist, or whose digests do not recompute, is not trusted."""
    if not commit_exists(repo, commit):
        return None
    try:
        tree = tree_blobs(repo, commit)
    except EvidenceError:
        return None
    roots = derive_envelope_roots(tree, reader, module)
    membership = envelope_membership(tree, roots)
    return {"roots": roots, "membership": membership, **envelope_digests(roots, membership)}


def _sorted_if_list(value):
    return sorted(value) if isinstance(value, list) and all(isinstance(v, str) for v in value) else value


def _canonical_record_payload(record: dict) -> dict:
    """The complete normative payload of an envelope record -- every claim-bearing field, typed and
    ordered. Identity derives from THIS, so any substantive review change (an attestation moving a
    member from unsupported to analyzed, a different reviewer or review date, a changed delta, a
    different predecessor) yields a different id and cannot masquerade as the same immutable revision.

    Non-normative, deliberately EXCLUDED: `roots` and `membership` (fully derivable from
    `reviewed_commit` + module through git, and pinned by `roots_digest`/`membership_digest`),
    `envelope_record_id` (the identity itself), and any `_`-prefixed field (run-time annotations).
    `revision` is carried RAW: a revision-0 record and a revision-1 record are different records."""
    att = record.get("attestation")
    delta = record.get("reviewed_delta")
    claims = record.get("affected_claims")
    return {
        "envelope_id": record.get("envelope_id"),
        "reviewed_commit": record.get("reviewed_commit"),
        "reviewer": record.get("reviewer"),
        "reviewed_at": record.get("reviewed_at"),
        "revision": record.get("revision"),
        "roots_digest": record.get("roots_digest"),
        "membership_digest": record.get("membership_digest"),
        "envelope_digest": record.get("envelope_digest"),
        "migration_subset_digest": record.get("migration_subset_digest"),
        "attestation": None if not isinstance(att, dict) else {
            k: _sorted_if_list(v) for k, v in sorted(att.items())},
        "previous_envelope_record_id": record.get("previous_envelope_record_id"),
        "reviewed_delta": None if not isinstance(delta, dict) else {
            k: _sorted_if_list(v) for k, v in sorted(delta.items())},
        "affected_claims": None if claims is None else (
            sorted(json.dumps(c, sort_keys=True) if isinstance(c, dict) else str(c) for c in claims)
            if isinstance(claims, list) else claims),
    }


def envelope_record_identity(record: dict) -> str:
    """Content-addressed identity over the COMPLETE normative payload (item 4). A record's id changes
    whenever any of its reviewed assertions change, so a disposition that references an id is bound to
    an exact, immutable review -- not merely to an envelope digest."""
    return digest(json.dumps(_canonical_record_payload(record), sort_keys=True, separators=(",", ":")))


def _attestation_problem(attestation, members: list[str]) -> str | None:
    """The attestation must be an EXACT PARTITION of the reviewed membership (F8): three lists,
    no duplicates, pairwise disjoint, union equal to the members, no extra paths. A covering union
    was not enough -- a member listed under both `analyzed` and `non_runtime` asserts two
    contradictory things about one file."""
    buckets = ("analyzed", "non_runtime", "unsupported")
    if not isinstance(attestation, dict):
        return ("envelope record lacks an attestation partitioning members into analyzed / "
                "non_runtime / unsupported")
    extra_keys = sorted(set(attestation) - set(buckets))
    missing_keys = [k for k in buckets if k not in attestation]
    if missing_keys or extra_keys:
        return ("attestation must have exactly the keys analyzed / non_runtime / unsupported"
                + (" (missing " + ", ".join(missing_keys) + ")" if missing_keys else "")
                + (" (unexpected " + ", ".join(extra_keys) + ")" if extra_keys else ""))
    seen: dict[str, str] = {}
    for k in buckets:
        vals = attestation[k]
        if not isinstance(vals, list) or not all(isinstance(v, str) for v in vals):
            return "attestation." + k + " must be a list of paths"
        if len(set(vals)) != len(vals):
            return "attestation." + k + " lists a member more than once"
        for v in vals:
            if v in seen:
                return ("attestation is not a partition: " + v + " appears under both " + seen[v]
                        + " and " + k)
            seen[v] = k
    member_set = set(members)
    unattested = sorted(member_set - set(seen))
    if unattested:
        return ("envelope attestation does not account for " + str(len(unattested)) + " member(s), "
                "e.g. " + ", ".join(unattested[:3]))
    foreign = sorted(set(seen) - member_set)
    if foreign:
        return ("envelope attestation names " + str(len(foreign)) + " path(s) outside the reviewed "
                "envelope, e.g. " + ", ".join(foreign[:3]))
    return None


def _path_in_roots(path: str, roots: list[str]) -> bool:
    return any(path == r or (r.endswith("/") and path.startswith(r)) or glob_match(path, r) for r in roots)


def _claim_key(entry) -> str | None:
    if isinstance(entry, dict) and isinstance(entry.get("path"), str) and isinstance(entry.get("subject_id"), str):
        return entry["path"] + "|" + entry["subject_id"]
    return None


def validate_envelope_records(records: list[dict], repo: Path | None, reader: "BlobReader | None",
                              module_by_envelope: dict[str, str], cut_sha: str,
                              history: "HistoricalIndex | None") -> dict[str, dict]:
    """Validate EVERY declared envelope record -- not only the latest -- and return records_by_id
    with each record annotated `_valid_problem` (None when valid).

    A claim may reference any record by id, and a renewal names its predecessor by id, so an
    unvalidated record is a hole a claim can be routed through (F8). Validation is memoised,
    rejects cycles and dangling predecessor links, and rejects two distinct records claiming the
    same revision of one envelope. Cut-relative checks (does the latest record still describe the
    cut?) are NOT here: a predecessor legitimately describes an older tree; `compute_envelopes`
    applies them to the latest record only."""
    records_by_id: dict[str, dict] = {}
    for rec in records:
        rid = envelope_record_identity(rec)
        records_by_id[rid] = {**rec, "envelope_record_id": rid}

    by_env_rev: dict[tuple, list[str]] = {}
    for rid, rec in records_by_id.items():
        by_env_rev.setdefault((rec.get("envelope_id"), rec.get("revision")), []).append(rid)
    dup_problem: dict[str, str] = {}
    for (eid, rev), ids in by_env_rev.items():
        if len(ids) > 1:
            for rid in ids:
                dup_problem[rid] = ("envelope " + str(eid) + " has " + str(len(ids)) + " distinct records "
                                    "claiming revision " + str(rev) + "; a revision must be unique")

    snaps: dict[tuple[str, str], dict | None] = {}

    def snap_for(module: str, commit: str) -> dict | None:
        key = (module, commit)
        if key not in snaps:
            snaps[key] = envelope_at_commit(repo, reader, module, commit) if repo is not None else None
        return snaps[key]

    results: dict[str, str | None] = {}

    def check(rid: str, visiting: frozenset) -> str | None:
        if rid in results:
            return results[rid]
        if rid in visiting:
            results[rid] = "envelope record chain is cyclic at " + rid[:19]
            return results[rid]
        results[rid] = "envelope record validation re-entered (cycle)"  # provisional, for cycles
        problem = dup_problem.get(rid) or _record_problem(rid, visiting | {rid})
        results[rid] = problem
        return problem

    def _record_problem(rid: str, visiting: frozenset) -> str | None:
        rec = records_by_id[rid]
        eid = rec.get("envelope_id")
        module = module_by_envelope.get(eid, eid or "")
        if not isinstance(rec.get("reviewer"), str) or not rec["reviewer"].strip():
            return "envelope record has no reviewer"
        if not isinstance(rec.get("reviewed_at"), str) or not rec["reviewed_at"].strip():
            return "envelope record has no reviewed_at (a normative field)"
        revision = rec.get("revision")
        if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
            return "envelope record revision must be a positive integer, got " + repr(revision)
        rc = rec.get("reviewed_commit")
        if repo is not None:
            if not commit_exists(repo, rc):
                return "envelope reviewed_commit " + repr(rc) + " does not exist in this repository"
            if not is_ancestor(repo, rc, cut_sha):
                return "envelope reviewed_commit is not an ancestor of the cut"
        elif not _SHA_RE.match(str(rc)):
            return "envelope reviewed_commit is not a full commit sha"

        snap = None
        if repo is not None:
            snap = snap_for(module, rc)
            if snap is None:
                return "envelope reviewed snapshot could not be recomputed from git"
            for key in ("roots_digest", "membership_digest", "envelope_digest", "migration_subset_digest"):
                if rec.get(key) != snap[key]:
                    return ("envelope record " + key + " does not match the snapshot recomputed at "
                            + rc[:12] + " (record claims a review the tree does not support)")
            members = [p for p, _ in snap["membership"]]
            att_problem = _attestation_problem(rec.get("attestation"), members)
            if att_problem:
                return att_problem
        else:
            att = rec.get("attestation")
            if not isinstance(att, dict) or not all(isinstance(att.get(k), list) for k in ("analyzed", "non_runtime", "unsupported")):
                return ("envelope record lacks an attestation partitioning members into analyzed / "
                        "non_runtime / unsupported")

        prev_id = rec.get("previous_envelope_record_id")
        if revision == 1:
            if prev_id:
                return "a first revision cannot name a previous_envelope_record_id"
            return None

        # Renewal lifecycle (item 1 of the consolidation review, hardened by F8).
        if not prev_id or prev_id not in records_by_id:
            return "renewal does not reference a valid previous_envelope_record_id"
        prev = records_by_id[prev_id]
        prev_problem = check(prev_id, visiting)
        if prev_problem:
            return "previous envelope record is invalid: " + prev_problem
        if prev.get("envelope_id") != eid:
            return "previous_envelope_record_id belongs to a different envelope"
        if not isinstance(prev.get("revision"), int) or prev["revision"] >= revision:
            return "renewal revision must exceed its predecessor's"
        delta = rec.get("reviewed_delta")
        if not isinstance(delta, dict):
            return "renewed envelope record carries no reviewed_delta object"
        claims = rec.get("affected_claims")
        if not isinstance(claims, list):
            return "renewed envelope record must name affected_claims (an explicit list, possibly empty)"
        if repo is None:
            return None
        prev_snap = snap_for(module, prev.get("reviewed_commit"))
        if prev_snap is None:
            return "previous record's reviewed snapshot could not be recomputed from git"
        actual = diff_envelope(prev_snap["membership"], snap["membership"])  # R_old -> R_new
        for k in ("added", "removed", "modified"):
            if sorted(delta.get(k, []) or []) != sorted(actual[k]):
                return ("renewed envelope reviewed_delta." + k + " does not match the actual "
                        "predecessor->this-review delta")
        if history is not None:
            # Claims have stable identities: path|subject_id. Every affected claim must be a real
            # subject at one of the two reviews, and every subject inside this envelope whose Tier-0
            # fingerprint differs between the two reviews must be listed.
            idx_old = history.at(prev["reviewed_commit"])
            idx_new = history.at(rc)
            roots = snap["roots"]
            keys = {k for k in set(idx_old) | set(idx_new) if _path_in_roots(k.partition("|")[0], roots)}
            changed = sorted(k for k in keys if idx_old.get(k) != idx_new.get(k))
            affected: set[str] = set()
            for entry in claims:
                key = _claim_key(entry)
                if key is None:
                    return "affected_claims entries must be objects naming path and subject_id"
                affected.add(key)
            unknown = sorted(affected - keys)
            if unknown:
                return ("affected_claims names " + str(len(unknown)) + " claim(s) that exist at neither "
                        "the predecessor review nor this review inside this envelope, e.g. " + unknown[0])
            omitted = [k for k in changed if k not in affected]
            if omitted:
                return ("affected_claims omits " + str(len(omitted)) + " claim(s) whose Tier-0 fingerprint "
                        "changed between the predecessor review and this review, e.g. " + omitted[0])
        return None

    for rid in list(records_by_id):
        check(rid, frozenset())
    for rid, problem in results.items():
        records_by_id[rid]["_valid_problem"] = problem
    return records_by_id


def validate_claim_record(record: dict, expected_obligation: str, subject_id: str, path: str,
                          code_fingerprint: str | None, repo: Path | None, cut_sha: str,
                          envelopes: dict, records_by_id: dict,
                          history: "HistoricalIndex | None" = None) -> tuple[str | None, str]:
    """The ONE gate for every claim-bearing record. A Tier-0 fingerprint is MANDATORY (never skipped
    by passing None), the referenced envelope must OWN the subject path, the referenced envelope
    RECORD must itself be valid, and the claim must reconstruct at its own reviewed_commit -- so a
    review cannot predate the code it approves."""
    if code_fingerprint is None:
        return "internal: a claim was validated without a Tier 0 fingerprint", DISPOSITION_INVALID
    if record.get("obligation") not in (None, expected_obligation) and expected_obligation != "any":
        return ("record obligation " + repr(record.get("obligation")) + " does not match "
                + expected_obligation), DISPOSITION_INVALID
    if record.get("path") != path or record.get("subject_id") != subject_id:
        return "record does not name this exact subject", DISPOSITION_INVALID
    if not isinstance(record.get("reviewer"), str) or not record["reviewer"].strip():
        return "record has no reviewer", DISPOSITION_INVALID
    if record.get("status") not in (ACCEPTED, "RESOLVED_RELEVANT", "RESOLVED_UNRELATED"):
        return "record status is " + repr(record.get("status")), UNREVIEWED
    rc = record.get("reviewed_commit")
    if repo is not None:
        if not commit_exists(repo, rc):
            return "reviewed_commit " + repr(rc) + " does not exist in this repository", DISPOSITION_INVALID
        if not is_ancestor(repo, rc, cut_sha):
            return "reviewed_commit is not an ancestor of the cut", DISPOSITION_INVALID
    elif not _SHA_RE.match(str(rc)):
        return "reviewed_commit is not a full commit sha", DISPOSITION_INVALID

    eid = record.get("envelope_id")
    if not eid or eid not in envelopes:
        return "record names no valid envelope_id", DISPOSITION_INVALID
    # The referenced envelope must OWN the subject path.
    if not _path_in_roots(path, envelopes[eid].get("roots", [])):
        return "subject path is not inside the referenced envelope", DISPOSITION_INVALID
    rec_id = record.get("envelope_record_id")
    if not rec_id or rec_id not in records_by_id:
        return "record does not reference an existing envelope revision (envelope_record_id)", DISPOSITION_INVALID
    ref_record = records_by_id[rec_id]
    if ref_record.get("_valid_problem"):
        return ("referenced envelope record is invalid: " + ref_record["_valid_problem"]), DISPOSITION_INVALID
    if ref_record.get("envelope_id") != eid:
        return "envelope_record_id belongs to a different envelope", DISPOSITION_INVALID
    if ref_record.get("envelope_digest") != envelopes[eid]["envelope_digest"]:
        return "referenced envelope revision no longer matches the cut", ENVELOPE_CHANGED

    if record.get("code_fingerprint") != code_fingerprint:
        return "Tier 0 fingerprint does not match the code at the cut", UNREVIEWED
    # The claim must have been reviewed against the code that existed at reviewed_commit.
    if repo is not None and history is not None:
        at_review = history.at(rc).get(path + "|" + subject_id)
        if at_review is None:
            return ("subject did not exist at reviewed_commit " + str(rc)[:12]
                    + "; the claim predates the code it approves"), DISPOSITION_INVALID
        if at_review != code_fingerprint:
            return ("subject at reviewed_commit differs from the cut; a renewal chain, not a stale "
                    "claim, must carry an approval forward"), UNREVIEWED
    return None, ""


def validate_operational_record(rec: dict, repo: Path | None, cut_sha: str,
                                envelope_for_migration: dict) -> str | None:
    """A live-database fact, validated substantively (F4). It binds a NON-SECRET environment
    identity, the exact query and result artifacts by hash, an operator, a real reviewed_commit, and
    the migration subset of the SUBJECT'S OWNING envelope -- so a record taken against the wrong
    database, before a new migration, or with a fabricated artifact cannot clear anything."""
    required = ("subject_ref", "environment_identity", "query_artifact", "result_artifact",
                "operator", "recorded_at", "reviewed_commit")
    missing = [k for k in required if not rec.get(k)]
    if missing:
        return "operational record is missing " + ", ".join(missing)
    if not isinstance(rec.get("operator"), str) or not rec["operator"].strip():
        return "operational record has no operator"
    if repo is not None and not commit_exists(repo, rec.get("reviewed_commit")):
        return "operational record reviewed_commit does not exist in this repository"
    for key in ("query_artifact", "result_artifact"):
        art = rec.get(key)
        if not isinstance(art, dict) or not art.get("path") or not str(art.get("sha256", "")).startswith("sha256:"):
            return key + " must name a path and its sha256"
        if repo is not None:
            fp = Path(repo) / art["path"]
            if not fp.is_file():
                return key + " file " + art["path"] + " is not present"
            actual = "sha256:" + hashlib.sha256(fp.read_bytes()).hexdigest()
            if actual != art["sha256"]:
                return key + " bytes do not match the recorded hash"
    expected = envelope_for_migration.get("migration_subset_digest")
    if rec.get("migration_subset_digest") != expected:
        return ("migration subset changed since the operational record was taken; the recorded fact "
                "no longer describes the migrations shipped in this deployable")
    return None


# --------------------------------------------------------------------------------------
# Deployable enumeration from the immutable tree (F5) -- policy omission cannot drop an obligation
# --------------------------------------------------------------------------------------


def module_of(path: str) -> str:
    return path.split("/", 1)[0]


def read_only_accounted(op: "Operation", receiver_type: str | None, store: str | None) -> bool:
    """THE shared read-only decision, taken AFTER receiver resolution. `Operation.access == "read"` is
    only the preliminary SQL-text fact; it becomes an accounted read-only usage only when the receiver
    resolved to a relational store (JdbcTemplate, NamedParameterJdbcTemplate, JdbcClient, EntityManager,
    Connection/Statement, ...). An unknown or undeclared receiver cannot inherit JDBC read semantics
    from a method name, and a receiver typed to a class in the tree follows the existing rule that its
    own implementation is scanned rather than becoming JDBC by assumption. writer_inventory,
    governed_modules and persistence accounting all consume this one predicate."""
    return (op.access == "read" and op.coverage == RESOLVED and store == STORE_POSTGRES
            and bool(receiver_type))


def governed_modules(tree: dict[str, str], reader: "BlobReader", policy: dict) -> set[str]:
    """Modules that B1 must govern, derived from the TREE and the effect model -- NOT from policy.

    A module is governed when it contains at least one operation whose effect is RELEVANT or
    UNRESOLVED (it touches, or might touch, the relevant tables). Deleting the policy `deployables`
    list therefore cannot suppress the obligation: portfolio-service (holdings/version writers) and
    api-gateway (the signup insert into portfolios) are discovered here regardless. A module whose
    every write is table-disjoint (e.g. market-data-service writing market_prices) is out of B1
    scope and is not pulled in.

    This is a light pre-pass: it classifies effects only and does not validate any disposition, so
    it has no dependency on the envelopes it helps decide."""
    relevant_tables = {t.lower() for t in policy.get("relevant_tables", ["asset_holdings", "portfolios"])}
    store_by_type = _store_by_type(policy)

    lexed: dict[str, tuple[list[Token], list[tuple[str, str]]] | None] = {}
    for path, blob in tree.items():
        if not path.endswith(".java") or "/src/main/" not in path:
            continue
        text = reader.text(blob)
        if text is None:
            continue
        try:
            toks = lex_java(text)
        except LexError:
            # A file the lexer rejects is a coverage gap, so its module is governed conservatively.
            lexed[path] = None
            continue
        lexed[path] = (toks, java_contexts(toks))

    entity_index = build_entity_index({p: v for p, v in lexed.items() if v})
    cascades, unparsed = fk_cascade_map(tree, reader, ["/"])
    mutating = sql_mutating_functions(tree, reader, [])
    governed: set[str] = set()
    for path, parsed in lexed.items():
        if parsed is None:
            governed.add(module_of(path))
            continue
        toks, contexts = parsed
        mapped = mapped_collection_names(toks, contexts)
        ops, _f, _bm = extract_operations(path, toks, contexts, mapped, mutating)
        types = declared_types(toks, contexts)
        for op in ops:
            rt, store, entity, _md = resolve_receiver(op, types, entity_index, store_by_type)
            if read_only_accounted(op, rt, store):
                continue
            effect, _basis = classify_effect(op, rt, store, entity, cascades, relevant_tables, unparsed,
                                             entity_index)
            if effect in (RELEVANT, UNRESOLVED):
                governed.add(module_of(path))
                break
    return governed


def compute_envelopes(tree: dict[str, str], reader: BlobReader, policy: dict,
                      governed: set[str] | None = None, cut_sha: str = "",
                      history: "HistoricalIndex | None" = None) -> tuple[dict, list[Finding], dict]:
    """Every governed deployable gets a computed envelope AND must carry a validated reviewed record.

    The deployable set is policy.deployables UNION the tree-derived governed modules, so deleting the
    policy list cannot suppress an obligation (F5). EVERY record is validated git-backed (F8) and
    identified content-addressably (F4); the latest record additionally must still describe the cut.
    Returns (computed_envelopes, findings, records_by_id) -- records carry `_valid_problem`."""
    governed = governed if governed is not None else set()
    findings: list[Finding] = []
    computed: dict[str, dict] = {}

    declared = {d["envelope_id"]: d for d in policy.get("deployables", []) if d.get("envelope_id")}
    all_ids = set(declared) | {m for m in governed}
    deployable_list = []
    for eid in sorted(all_ids):
        d = declared.get(eid, {"envelope_id": eid, "module": eid})
        deployable_list.append(d)
    module_by_envelope = {d["envelope_id"]: d.get("module", d["envelope_id"]) for d in deployable_list}
    # A record for an envelope the tree does not govern and the policy does not declare still gets
    # validated against its own declared id (module defaults to the id).
    for rec in policy.get("envelopes", []):
        if rec.get("envelope_id") and rec["envelope_id"] not in module_by_envelope:
            module_by_envelope[rec["envelope_id"]] = rec["envelope_id"]

    repo = reader.repo if reader else None
    eff_cut = cut_sha or (resolve_commit(repo, "HEAD") if repo is not None else "")
    if history is None and repo is not None:
        history = HistoricalIndex(repo, reader, policy)
    records_by_id = validate_envelope_records(policy.get("envelopes", []), repo, reader,
                                              module_by_envelope, eff_cut, history)

    def _rev(rec: dict) -> int:
        r = rec.get("revision")
        return r if isinstance(r, int) and not isinstance(r, bool) else -1

    latest: dict[str, dict] = {}
    for rec in records_by_id.values():
        eid = rec.get("envelope_id")
        if eid and (eid not in latest or _rev(rec) > _rev(latest[eid])):
            latest[eid] = rec

    for deployable in deployable_list:
        eid = deployable["envelope_id"]
        module = deployable.get("module", eid)
        derived = derive_envelope_roots(tree, reader, module)
        roots = sorted(set(deployable.get("roots"))) if deployable.get("roots") else derived
        missing_roots = [r for r in derived if r not in roots]
        if missing_roots:
            findings.append(Finding(
                module, "envelope:" + eid, "envelope", POLICY_INVALID,
                "declared envelope roots omit roots derived from the build graph; an envelope may "
                "not be narrowed without that narrowing itself being reviewed",
                {"missing_roots": missing_roots}))
            roots = sorted(set(roots) | set(derived))

        membership = envelope_membership(tree, roots)
        digs = envelope_digests(roots, membership)
        computed[eid] = {"envelope_id": eid, "module": module, "roots": roots,
                         "membership": membership, **digs}

        record = latest.get(eid)
        if record is None:
            findings.append(Finding(
                module, "envelope:" + eid, "envelope", UNREVIEWED,
                "deployable requires a reviewed envelope record and has none "
                + ("(discovered from the tree; policy omitted it)" if eid not in declared else ""),
                {"computed_envelope_digest": digs["envelope_digest"], "member_count": digs["member_count"]}))
            continue

        computed[eid]["delta"] = diff_envelope(record.get("membership", []), membership)
        problem = record.get("_valid_problem")
        if not problem:
            # R_new -> C must leave the approved envelope unchanged.
            if record.get("envelope_digest") != computed[eid]["envelope_digest"]:
                problem = "envelope membership or content changed since review"
            elif record.get("migration_subset_digest") != computed[eid]["migration_subset_digest"]:
                problem = "migration subset changed since review"
        if problem:
            findings.append(Finding(
                module, "envelope:" + eid, "envelope", ENVELOPE_CHANGED, problem,
                {"recorded": {k: record.get(k) for k in
                              ("revision", "roots_digest", "envelope_digest", "reviewed_commit", "reviewer")},
                 "computed": digs, "delta": computed[eid]["delta"]}))
            continue
        members = {p for p, _ in membership}
        for path in sorted(set(record["attestation"]["unsupported"]) & members):
            findings.append(Finding(
                path, "attestation:" + eid, "unverified-coverage", UNSUPPORTED,
                "envelope attestation labels this member unsupported; it blocks until analyzed, "
                "refactored, or covered by an operational record",
                {"envelope_id": eid}))

    # Every OTHER invalid record is reported too: a claim may reference it, and an invalid record
    # that is silently ignored is a hole rather than a finding.
    for rid, rec in records_by_id.items():
        eid = rec.get("envelope_id")
        if rec.get("_valid_problem") and latest.get(eid) is not rec:
            findings.append(Finding(
                module_by_envelope.get(eid, str(eid)), "envelope-record:" + rid[7:19], "envelope",
                DISPOSITION_INVALID,
                "envelope record (revision " + str(rec.get("revision")) + ") is invalid: " + rec["_valid_problem"],
                {"envelope_id": eid, "envelope_record_id": rid}))
    return computed, findings, records_by_id


# --------------------------------------------------------------------------------------
# Policy validity: inactive features, exclusions, evidence binding
# --------------------------------------------------------------------------------------

#: Correction 10. These remain configured-but-empty and unapproved. A non-empty value is a policy
#: finding rather than a silently honoured behaviour, so enabling one is a visible act.
INACTIVE_POLICY_FEATURES = (
    "merge_grouping",
    "automatic_b1_scope_clearance",
    "effect_based_automatic_clearance",
)


def policy_validity(policy: dict, tree: dict[str, str]) -> list[Finding]:
    findings: list[Finding] = []
    p = "scripts/b1-candidate-policy.json"

    for feature in INACTIVE_POLICY_FEATURES:
        value = policy.get(feature)
        if value:
            findings.append(Finding(
                p, "policy:" + feature, "policy-validity", POLICY_INVALID,
                "feature " + feature + " is not approved and must remain empty; its configured "
                "value is ignored and this finding blocks",
                {"configured": value}))

    for exc in policy.get("scan_exclusions", []):
        glob = exc.get("glob", "")
        kind = exc.get("kind")
        if kind not in ("TEST_CORPUS", "POLICY_FILE"):
            findings.append(Finding(
                p, "exclusion:" + glob, "policy-validity", POLICY_INVALID,
                "scan_exclusions may name only test corpora and the policy file",
                {"kind": kind}))
            continue
        production = sorted(m for m in tree if glob_match(m, glob) and "/src/main/" in m)
        if production:
            findings.append(Finding(
                p, "exclusion:" + glob, "policy-validity", POLICY_INVALID,
                "exclusion glob names production paths; the glob is ignored and those paths are "
                "scanned. A production path is never excluded -- it needs a blob-bound "
                "unverified-coverage review instead",
                {"production_paths": production[:10], "count": len(production)}))
    return findings


def active_scan_exclusions(policy: dict, tree: dict[str, str]) -> list[str]:
    """Only exclusions that survived validation are honoured (fixture E17)."""
    out: list[str] = []
    for exc in policy.get("scan_exclusions", []):
        glob = exc.get("glob", "")
        if exc.get("kind") not in ("TEST_CORPUS", "POLICY_FILE"):
            continue
        if any(glob_match(m, glob) and "/src/main/" in m for m in tree):
            continue
        out.append(glob)
    return out


#: Which Task A run modes are eligible input for which Task C run mode (F7). Task A's producer labels
#: a clean, fully committed checkout CANDIDATE and a dirty one LOCAL_DEV; only the former can feed a
#: CANDIDATE source-governance run. Both remain usable for LOCAL_PREPARATION.
TASK_A_MODES_BY_RUN_MODE = {
    LOCAL_PREPARATION: ("LOCAL_DEV", "LOCAL_PREPARATION", "CANDIDATE"),
    CANDIDATE: ("CANDIDATE",),
}
#: Task B's producer labels every local packaging result LOCAL_PREPARATION by contract -- the runbook's
#: release procedure states the field keeps reading LOCAL_PREPARATION until push/registry evidence
#: exists. A CANDIDATE run therefore does not demand a different label; it demands verified
#: provenance, a digest-pinned runtime base, an explicit platform pair, and (when requested) the
#: release portion. The registry portion's absence is reported in candidate_ready_blocked_by.
TASK_B_LABELS = ("LOCAL_PREPARATION", "CANDIDATE")

#: The accepted producer records the runtime base as `docker image inspect --format {{.RepoDigests}}`
#: emits it: `<registry-or-repo>[:port]/<path>@sha256:<64 hex>`. Nothing else is a pinned base.
_PINNED_BASE_RE = re.compile(
    r"^[a-z0-9]+(?:[._-][a-z0-9]+)*(?::[0-9]+)?(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*@sha256:[0-9a-f]{64}$")

#: Fields of the producer's own build record (`verify_b1_candidate_image.write_build_record`).
BUILD_RECORD_FIELDS = ("tag", "image_id", "base_ref", "base_digest", "platform", "dockerfile_path",
                       "dockerfile_sha256")


def _resolve_recipe_path(value: str, repo: Path) -> Path:
    p = Path(value)
    return p if p.is_absolute() else Path(repo) / p


def task_b_build_record_problems(task_b: dict, record, repo: Path) -> list[str]:
    """Bind Task B's recipe/base/platform CLAIMS to the producer's own build record for the recorded
    image (F12). The record is written by the Task B producer at build time and names the image id,
    the digest-pinned base actually built against, the platform, and the Dockerfile path and its
    sha256. Task B JSON strings are unrelated text until they agree with that record AND the recipe
    bytes on disk still hash to the recorded value."""
    problems: list[str] = []
    if not isinstance(record, dict):
        return ["Task B build record is not a JSON object"]
    p = "Task B build record "
    for key in BUILD_RECORD_FIELDS:
        _need(record, key, str, problems, p)
    if problems:
        return problems
    if record["image_id"] != task_b.get("local_image_id"):
        problems.append(p + "image_id " + str(record["image_id"])[:19] + "... is not Task B's local_image_id; "
                        "the record describes a different image")
    if record["base_digest"] != task_b.get("runtime_base_digest"):
        problems.append(p + "base_digest does not match Task B's runtime_base_digest (recorded "
                        + repr(record["base_digest"])[:80] + ")")
    if record["platform"] != task_b.get("requested_platform"):
        problems.append(p + "platform " + repr(record["platform"]) + " does not match Task B's requested_platform")
    recipe_claim = task_b.get("recipe")
    recorded = _resolve_recipe_path(record["dockerfile_path"], repo)
    claimed = _resolve_recipe_path(recipe_claim, repo) if isinstance(recipe_claim, str) else None
    if not recorded.is_file():
        problems.append(p + "dockerfile_path " + str(recorded) + " is not present; the recipe cannot be re-verified")
    else:
        actual = sha256_file(recorded)
        if actual != record["dockerfile_sha256"]:
            problems.append(p + "dockerfile bytes now hash to " + actual[:12] + "..., not the recorded "
                            "dockerfile_sha256 " + str(record["dockerfile_sha256"])[:12] + "...; the recipe changed "
                            "after the image was built")
        try:
            same = claimed is not None and claimed.resolve() == recorded.resolve()
        except OSError:
            same = False
        if not same:
            problems.append(p + "dockerfile_path and Task B recipe " + repr(recipe_claim)[:100]
                            + " do not name the same file")
    return problems


def _need(container: dict, key: str, types, problems: list[str], prefix: str):
    """Require `key` to be present with an accepted type. Absence is a problem, never a default:
    `None == None` is how a missing platform pair used to compare equal."""
    if key not in container:
        problems.append(prefix + key + " is missing")
        return None
    value = container[key]
    if types is bool:
        ok = isinstance(value, bool)
    else:
        ok = isinstance(value, types) and not isinstance(value, bool)
    if not ok:
        wanted = types.__name__ if isinstance(types, type) else "/".join(t.__name__ for t in types)
        problems.append(prefix + key + " has type " + type(value).__name__ + ", expected " + wanted)
        return None
    return value


def _need_digest(container: dict, key: str, problems: list[str], prefix: str) -> str | None:
    raw = _need(container, key, str, problems, prefix)
    if raw is None:
        return None
    norm = normalize_sha256_digest(raw)
    if norm is None:
        problems.append(prefix + key + " is not a well-formed sha256 digest: " + repr(raw)[:80])
    return norm


def task_a_schema_problems(task_a, cut_sha: str, base_sha: str | None, mode: str) -> list[str]:
    """Semantic validation of a Task A bundle as the accepted producer emits it
    (`b1_candidate_evidence.run_evidence`). Every claim-bearing field is required with its type."""
    problems: list[str] = []
    if not isinstance(task_a, dict):
        return ["Task A bundle is not a JSON object"]
    p = "Task A "
    status = _need(task_a, "graph_verification_status", str, problems, p)
    if status is not None and status != "PASS":
        problems.append(p + "graph_verification_status is " + repr(status) + ", not PASS")
    probs = _need(task_a, "problems", list, problems, p)
    if probs:
        problems.append(p + "reports " + str(len(probs)) + " problem(s)")
    run = _need(task_a, "run", dict, problems, p) or {}
    head = _need(run, "head_sha", str, problems, p + "run.")
    if head is not None and head != cut_sha:
        problems.append(p + "run.head_sha " + repr(head) + " is not the cut")
    b1 = _need(run, "b1_base_sha", str, problems, p + "run.")
    if b1 is not None and base_sha is not None and b1 != base_sha:
        problems.append(p + "run.b1_base_sha " + repr(b1) + " is not the policy-pinned B1-base")
    a_mode = _need(run, "mode", str, problems, p + "run.")
    allowed = TASK_A_MODES_BY_RUN_MODE.get(mode, ())
    if a_mode is not None and a_mode not in allowed:
        problems.append(p + "run.mode " + repr(a_mode) + " is not eligible for a " + mode
                        + " run (accepted: " + ", ".join(allowed) + ")")
    stage = _need(task_a, "stage", dict, problems, p) or {}
    _need_digest(stage, "sha256", problems, p + "stage.")
    staged_path = _need(stage, "staged_path", str, problems, p + "stage.")
    if staged_path is not None and not staged_path.strip():
        problems.append(p + "stage.staged_path is empty")
    ready = _need(task_a, "candidate_ready", bool, problems, p)
    if ready:
        problems.append(p + "asserts candidate_ready true; the producer never does")
    return problems


def task_b_schema_problems(task_b, cut_sha: str, mode: str, release_portion: bool) -> list[str]:
    """Semantic validation of a Task B bundle as the accepted producer emits it
    (`verify_b1_candidate_image.verify_candidate_image`)."""
    problems: list[str] = []
    if not isinstance(task_b, dict):
        return ["Task B bundle is not a JSON object"]
    p = "Task B "
    label = _need(task_b, "label", str, problems, p)
    if label is not None and label not in TASK_B_LABELS:
        problems.append(p + "label " + repr(label) + " is not a known packaging label")
    prov = _need(task_b, "provenance", str, problems, p)
    if prov is not None and prov != "verified":
        problems.append(p + "provenance is " + repr(prov) + ", not 'verified'")
    _need_digest(task_b, "local_image_id", problems, p)
    platform = _need(task_b, "platform", str, problems, p)
    requested = _need(task_b, "requested_platform", str, problems, p)
    if platform is not None and not platform.strip():
        problems.append(p + "platform is empty")
    if requested is not None and not requested.strip():
        problems.append(p + "requested_platform is empty")
    if platform is not None and requested is not None and platform != requested:
        problems.append(p + "platform " + repr(platform) + " does not match requested_platform " + repr(requested))
    head = _need(task_b, "task_a_evidence_head_sha", str, problems, p)
    if head is not None and head != cut_sha:
        problems.append(p + "task_a_evidence_head_sha does not equal the cut")
    staged_path = _need(task_b, "staged_jar_path", str, problems, p)
    if staged_path is not None and not staged_path.strip():
        problems.append(p + "staged_jar_path is empty")
    staged = _need_digest(task_b, "staged_jar_sha256", problems, p)
    extracted = _need_digest(task_b, "extracted_jar_sha256", problems, p)
    equal = _need(task_b, "hashes_equal", bool, problems, p)
    if equal is False:
        problems.append(p + "hashes_equal is false")
    if staged is not None and extracted is not None and staged != extracted:
        problems.append(p + "staged/extracted JAR hashes are not equal")
    if mode == CANDIDATE:
        base_digest = _need(task_b, "runtime_base_digest", str, problems, p)
        if base_digest is not None and not _PINNED_BASE_RE.match(base_digest):
            problems.append(p + "runtime_base_digest " + repr(base_digest)[:100] + " is not an immutable "
                            "`repository@sha256:<64 hex>` reference; a candidate run requires a digest-pinned "
                            "runtime base (a floating tag, `scratch` or arbitrary text is not one)")
        recipe = _need(task_b, "recipe", str, problems, p)
        if recipe is not None and not recipe.strip():
            problems.append(p + "recipe is empty")
    if release_portion:
        reg = _need_digest(task_b, "registry_manifest_digest", problems, p)
        reg_plat = _need(task_b, "registry_manifest_platform", str, problems, p)
        if reg is not None and reg_plat is not None and platform is not None and reg_plat != platform:
            problems.append(p + "registry_manifest_platform does not match the image platform")
    return problems


def verify_evidence_artifacts(task_a: dict, task_b: dict) -> tuple[list[str], dict]:
    """Prove the artifacts the bundles describe EXIST and still carry the recorded bytes (F7):
    re-hash the staged JAR at the producer-recorded path and compare with BOTH bundles; inspect the
    immutable image ID, check its platform, re-extract /app.jar and hash it. A plausible digest string
    is not proof that an artifact exists. Docker failures are problems, never skips."""
    problems: list[str] = []
    info: dict = {}
    staged_a = Path(task_a["stage"]["staged_path"])
    staged_b = Path(task_b["staged_jar_path"])
    try:
        same_file = staged_a.resolve() == staged_b.resolve()
    except OSError:
        same_file = False
    if not same_file:
        problems.append("Task A stage.staged_path and Task B staged_jar_path name different files ("
                        + str(staged_a) + " vs " + str(staged_b) + ")")
    expected_a = normalize_sha256_digest(task_a["stage"]["sha256"])
    expected_b = normalize_sha256_digest(task_b["staged_jar_sha256"])
    if not staged_a.is_file():
        problems.append("staged JAR " + str(staged_a) + " recorded by Task A is not present; the evidence "
                        "cannot be re-verified against bytes")
    else:
        actual = "sha256:" + sha256_file(staged_a)
        info["staged_jar_sha256_now"] = actual
        if actual != expected_a:
            problems.append("staged JAR bytes now hash to " + actual[:19] + "..., not Task A's recorded "
                            "stage.sha256 " + str(expected_a)[:19] + "...; the artifact changed after evidence capture")
        if actual != expected_b:
            problems.append("staged JAR bytes now hash to " + actual[:19] + "..., not Task B's recorded "
                            "staged_jar_sha256 " + str(expected_b)[:19] + "...")
    image_id = task_b["local_image_id"]
    try:
        inspected = docker_image_field(image_id, "{{.Id}}")
        if inspected != image_id:
            problems.append("image " + image_id[:19] + "... inspects as " + inspected[:19] + "...")
        actual_platform = docker_image_field(image_id, "{{.Os}}/{{.Architecture}}")
        info["image_platform_now"] = actual_platform
        if actual_platform != task_b["platform"]:
            problems.append("image platform " + actual_platform + " does not match Task B's recorded platform "
                            + str(task_b["platform"]))
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "app.jar"
            extract_file(image_id, "/app.jar", out)
            extracted = "sha256:" + sha256_file(out)
            info["extracted_jar_sha256_now"] = extracted
            if extracted != expected_b:
                problems.append("/app.jar re-extracted from image " + image_id[:19] + "... hashes to "
                                + extracted[:19] + "..., not the recorded staged JAR digest "
                                + str(expected_b)[:19] + "...")
    except EvidenceError as exc:
        first = str(exc).strip().splitlines()[0] if str(exc).strip() else str(exc)
        problems.append("image " + str(image_id)[:19] + "... could not be verified: " + first[:300])
    return problems, info


def validate_run_input_evidence(run_input: dict | None, repo: Path, cut_sha: str,
                                run_input_path: Path | None = None, mode: str = LOCAL_PREPARATION,
                                base_sha: str | None = None) -> tuple[dict, list[Finding]]:
    """Validate Task A / Task B evidence supplied as a PER-RUN input: the producers' schema in full
    (`task_a_schema_problems` / `task_b_schema_problems`), the A<->B cross-binding, and the artifacts
    themselves (`verify_evidence_artifacts`), recording the ACTUAL sha256 of every input file and the
    manifest -- a durable binding to the exact bytes checked. Invalid evidence is a binding FAILURE,
    never summarized as accepted."""
    findings: list[Finding] = []
    summary = {"provided": bool(run_input), "mode": mode, "task_a": None, "task_b": None,
               "artifacts_verified": False, "artifact_checks": {},
               "input_hashes": {}, "run_input_manifest_sha256": None}
    if run_input_path is not None and Path(run_input_path).is_file():
        summary["run_input_manifest_sha256"] = "sha256:" + hashlib.sha256(
            Path(run_input_path).read_bytes()).hexdigest()
    if not run_input:
        return summary, findings

    def load(spec, task):
        f = Path(repo) / spec["evidence_file"]
        if not f.is_file():
            findings.append(Finding(spec.get("evidence_file", task), "evidence:" + task,
                                    "evidence-binding", EVIDENCE_BINDING_MISMATCH,
                                    task + " evidence file is not present at the declared path"))
            return None
        raw = f.read_bytes()
        actual = "sha256:" + hashlib.sha256(raw).hexdigest()
        summary["input_hashes"][spec["evidence_file"]] = actual  # durable, even if caller omitted it
        if spec.get("evidence_sha256") and normalize_sha256_digest(spec["evidence_sha256"]) != actual:
            findings.append(Finding(spec["evidence_file"], "evidence:" + task, "evidence-binding",
                                    EVIDENCE_BINDING_MISMATCH,
                                    task + " evidence file bytes do not match the recorded hash"))
            return None
        try:
            return json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            findings.append(Finding(spec["evidence_file"], "evidence:" + task, "evidence-binding",
                                    EVIDENCE_BINDING_MISMATCH,
                                    task + " evidence file is not valid JSON; it cannot be a bundle"))
            return None

    def fail(spec, task, msg):
        findings.append(Finding(spec["evidence_file"], "evidence:" + task, "evidence-binding",
                                EVIDENCE_BINDING_MISMATCH, msg))

    a_spec, b_spec = run_input.get("task_a"), run_input.get("task_b")
    a = load(a_spec, "task_a") if a_spec else None
    b = load(b_spec, "task_b") if b_spec else None

    a_ok = b_ok = False
    if a is not None:
        a_problems = task_a_schema_problems(a, cut_sha, base_sha, mode)
        for p in a_problems:
            fail(a_spec, "task_a", p)
        a_ok = not a_problems
    build_record = None
    if b is not None:
        b_problems = task_b_schema_problems(b, cut_sha, mode, bool(b_spec.get("release_portion")))
        if a_ok and not b_problems and (normalize_sha256_digest(b["staged_jar_sha256"])
                                        != normalize_sha256_digest(a["stage"]["sha256"])):
            b_problems.append("Task B staged_jar_sha256 does not match Task A stage.sha256")
        # Producer build record: REQUIRED in CANDIDATE (it is what binds recipe/base/platform to the
        # recorded image), validated whenever supplied.
        rec_spec = b_spec.get("build_record")
        if rec_spec is None and mode == CANDIDATE:
            b_problems.append("Task B build record (the producer's image-build-record.json) is required in "
                              "CANDIDATE mode to bind recipe, base and platform to the recorded image; "
                              "supply it as task_b.build_record in the run input")
        elif rec_spec is not None:
            rec_path = Path(repo) / rec_spec
            if not rec_path.is_file():
                b_problems.append("Task B build record is not present at " + str(rec_path))
            else:
                raw = rec_path.read_bytes()
                summary["input_hashes"][str(rec_spec)] = "sha256:" + hashlib.sha256(raw).hexdigest()
                try:
                    build_record = json.loads(raw.decode("utf-8"))
                except (ValueError, UnicodeDecodeError):
                    b_problems.append("Task B build record is not valid JSON")
                else:
                    if not b_problems:
                        b_problems.extend(task_b_build_record_problems(b, build_record, repo))
        for p in b_problems:
            fail(b_spec, "task_b", p)
        b_ok = not b_problems

    if a_ok and b_ok:
        art_problems, art_info = verify_evidence_artifacts(a, b)
        summary["artifact_checks"] = art_info
        for p in art_problems:
            findings.append(Finding(b_spec["evidence_file"], "evidence:artifacts", "evidence-binding",
                                    EVIDENCE_BINDING_MISMATCH, p))
        if not art_problems:
            summary["artifacts_verified"] = True
            summary["task_a"] = {"head_sha": a["run"]["head_sha"],
                                 "staged_jar_sha256": normalize_sha256_digest(a["stage"]["sha256"]),
                                 "staged_jar_path": a["stage"]["staged_path"],
                                 "mode": a["run"]["mode"]}
            summary["task_b"] = {"image_identity": b["local_image_id"],
                                 "platform": b["platform"],
                                 "label": b["label"],
                                 "runtime_base_digest": b.get("runtime_base_digest"),
                                 "recipe": b.get("recipe"),
                                 "build_record_bound": build_record is not None,
                                 "registry_manifest_digest": b.get("registry_manifest_digest")}
    return summary, findings


# --------------------------------------------------------------------------------------
# Operational records (Tier 2) and unverified coverage
# --------------------------------------------------------------------------------------


# --------------------------------------------------------------------------------------
# Tier 0 v3: receiver typing and entity mapping digests
# --------------------------------------------------------------------------------------

RELEVANT = "RELEVANT"
UNRELATED = "UNRELATED"
UNRESOLVED = "UNRESOLVED"

#: Stores a receiver can belong to. `transport` is deliberately NOT folded into `memory` or into a
#: generic "non-persistence" bucket: a KafkaTemplate send is a real outbound effect that happens to
#: miss the relational tables, and describing it as an in-memory collection would misstate what the
#: analyzer actually knows about it.
STORE_POSTGRES = "postgres"
STORE_MONGO = "mongo"
STORE_REDIS = "redis"
STORE_MEMORY = "memory"
STORE_TRANSPORT = "transport"

_DEFAULT_STORE_BY_TYPE = {
    "Map": STORE_MEMORY, "HashMap": STORE_MEMORY, "LinkedHashMap": STORE_MEMORY,
    "ConcurrentHashMap": STORE_MEMORY, "Set": STORE_MEMORY, "HashSet": STORE_MEMORY,
    "LinkedHashSet": STORE_MEMORY, "List": STORE_MEMORY, "ArrayList": STORE_MEMORY,
    "HttpHeaders": STORE_MEMORY, "MDC": STORE_MEMORY, "SpanExporter": STORE_MEMORY,
    "KafkaTemplate": STORE_TRANSPORT,
    "RedisTemplate": STORE_REDIS, "StringRedisTemplate": STORE_REDIS,
    "MongoTemplate": STORE_MONGO, "MongoRepository": STORE_MONGO,
    "JdbcTemplate": STORE_POSTGRES, "NamedParameterJdbcTemplate": STORE_POSTGRES,
    "JdbcClient": STORE_POSTGRES, "EntityManager": STORE_POSTGRES, "Session": STORE_POSTGRES,
    "Connection": STORE_POSTGRES, "Statement": STORE_POSTGRES, "PreparedStatement": STORE_POSTGRES,
    "DataSource": STORE_POSTGRES,
}

#: A file mentioning any of these is a persistence-capable unit. Deny by TYPE, never by method name:
#: a method-name list is what let `saveAndFlush` through, and it can never be complete.
PERSISTENCE_TYPE_MARKERS = (
    "JdbcTemplate", "NamedParameterJdbcTemplate", "JdbcClient", "EntityManager", "Session",
    "Connection", "Statement", "PreparedStatement", "DataSource", "DatabaseClient",
    "TransactionTemplate", "ScriptUtils", "@Query", "@Modifying", "@SQLDelete", "@SQLInsert",
    "@SQLUpdate", "@NamedNativeQuery",
)

_REPOSITORY_RE = re.compile(r"\b\w*Repository\s*<")


def declared_types(tokens: list[Token], contexts: list[tuple[str, str]]) -> dict[str, str]:
    """identifier -> declared type name, from field and local declarations in one unit.

    Deliberately simple and conservative: an identifier this cannot type becomes UNRESOLVED, which
    blocks, rather than being assumed harmless."""
    types: dict[str, str] = {}
    for scope_indices in (
            [i for i, (_t, m) in enumerate(contexts) if not m],
            [i for i, (_t, m) in enumerate(contexts) if m]):
        for stmt in _statements(tokens, scope_indices):
            words = _strip_annotations([tokens[i].text for i in stmt if tokens[i].kind == "code"])
            words = [w for w in words if w not in ("private", "public", "protected", "final",
                                                   "static", "transient", "volatile")]
            if len(words) < 2:
                continue
            if "=" in words:
                words = words[:words.index("=")]
            if len(words) < 2 or "(" in words:
                continue
            name = words[-1]
            type_tokens = words[:-1]
            if not _IDENT_RE.fullmatch(name) or not type_tokens:
                continue
            # The base simple type is the identifier just before the generic `<`, else the last
            # segment of a possibly-dotted type path: `java.util.Map<..>` -> Map,
            # `NamedParameterJdbcTemplate` -> itself. Taking type_tokens[0] gave `java`.
            if "<" in type_tokens:
                head = type_tokens[:type_tokens.index("<")]
            else:
                head = type_tokens
            idents = [w for w in head if _IDENT_RE.fullmatch(w)]
            if not idents:
                continue
            base = idents[-1]
            if base in _TYPE_KEYWORDS or base in ("return", "new", "throw", "case", "this"):
                continue
            types.setdefault(name, base)
    return types


def entity_mapping_digest(tokens: list[Token], contexts: list[tuple[str, str]], type_name: str) -> str | None:
    """Normalised CLASS-LEVEL tokens of an entity: class annotations, field declarations with their
    annotations, and method heads. Method bodies are excluded.

    This is what makes `@OptimisticLock(excluded = true)` part of Tier 0. Removing that annotation
    changes whether mutating the mapped collection bumps `portfolios.version` at all, yet it lives
    in a different file from every write it governs, so no method-level digest can see it."""
    found = False
    parts: list[str] = []
    for i, (typ, meth) in enumerate(contexts):
        if typ.split(".")[-1] != type_name:
            continue
        found = True
        if meth:
            continue  # bodies excluded
        tok = tokens[i]
        if tok.kind == "code":
            parts.append(tok.text)
        else:
            parts.append(tok.text)
    if not found:
        return None
    heads = java_method_heads(tokens)
    for key in sorted(heads):
        if key.split("::")[0].split(".")[-1] == type_name:
            parts.append("HEAD:" + " ".join(heads[key]))
    return digest(" ".join(parts))


def build_entity_index(lexed: dict[str, tuple[list[Token], list[tuple[str, str]]]]) -> dict[str, dict]:
    """Entity simple name -> {path, mapping_digest, cascade_targets, custom_sql, resolvable}."""
    index: dict[str, dict] = {}
    for path, (tokens, contexts) in lexed.items():
        for i, tok in enumerate(tokens):
            if tok.kind != "code" or tok.text not in _TYPE_KEYWORDS or i + 1 >= len(tokens):
                continue
            anns = _annotations_before(tokens, i)
            if not any("Entity" in a for a in anns):
                continue
            name = tokens[i + 1].text
            md = entity_mapping_digest(tokens, contexts, name)
            text = " ".join(t.text for t in tokens)
            index[name] = {
                "path": path,
                "mapping_digest": md,
                "cascade": bool(re.search(r"cascade\s*=|orphanRemoval", text)),
                "custom_sql": sorted(set(re.findall(r"@(SQLDelete|SQLInsert|SQLUpdate|NamedNativeQuery)", text))),
                "table": (re.search(r"@\s*Table\s*\(\s*name\s*=\s*\"([^\"]+)\"", text).group(1)
                          if re.search(r"@\s*Table\s*\(\s*name\s*=\s*\"([^\"]+)\"", text) else None),
            }
    return index


def fk_cascade_map(tree: dict[str, str], reader: BlobReader, roots: list[str]) -> tuple[dict[str, set[str]], list[str]]:
    """`child REFERENCES parent ON DELETE CASCADE` -> {parent: {child}}, parsed from the migrations
    inside the envelope. MEASURED in this repo at V1__Initial_Schema.sql:22.

    Also returns the tables whose triggers or constraints could not be parsed, so an unparsed
    trigger body makes dependent operations UNRESOLVED rather than quietly UNRELATED."""
    cascades: dict[str, set[str]] = {}
    unparsed: list[str] = []
    for path in sorted(tree):
        if not path.endswith(".sql"):
            continue
        if not any(path.startswith(r) if r.endswith("/") else path == r for r in roots):
            continue
        src = reader.text(tree[path])
        if src is None:
            continue
        for stmt_line, stmt in split_sql_statements(src):
            flat = normalize_sql(stmt)
            m_tbl = re.search(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[\"]?([A-Za-z_][\w]*)",
                              flat, re.IGNORECASE)
            child = m_tbl.group(1).lower() if m_tbl else None
            for m in re.finditer(
                    r"REFERENCES\s+[\"]?([A-Za-z_][\w]*)[\"]?\s*(?:\([^)]*\))?\s*ON\s+DELETE\s+CASCADE",
                    flat, re.IGNORECASE):
                parent = m.group(1).lower()
                if child:
                    cascades.setdefault(parent, set()).add(child)
            if re.search(r"CREATE\s+(?:OR\s+REPLACE\s+)?TRIGGER", flat, re.IGNORECASE):
                body = re.search(r"EXECUTE\s+(?:PROCEDURE|FUNCTION)\s+([A-Za-z_][\w]*)", flat, re.IGNORECASE)
                if not body:
                    unparsed.append(path + ":" + str(stmt_line))
    return cascades, unparsed


def classify_effect(op: "Operation", receiver_type: str | None, store: str | None,
                    entity: dict | None, cascades: dict[str, set[str]],
                    relevant_tables: set[str], unparsed_triggers: list[str],
                    entity_index: dict[str, dict] | None = None) -> tuple[str, dict]:
    """Section 3, exactly: UNRELATED only when EVERY item is resolved and negative. Any unknown is
    UNRESOLVED, which blocks like RELEVANT."""
    basis: dict = {"receiver_type": receiver_type, "store": store}

    if op.coverage == UNSUPPORTED:
        basis["reason"] = "coverage is UNSUPPORTED; effect cannot be computed"
        return UNRESOLVED, basis
    if op.statement and op.statement.get("lexical_error"):
        # Belt and braces: an assessment that failed lexically can never present as resolved here.
        basis["reason"] = "statement is outside the supported SQL subset: " + op.statement["lexical_error"]
        return UNRESOLVED, basis
    if receiver_type is None or store is None:
        basis["reason"] = "receiver type could not be resolved in-file or in the tree"
        return UNRESOLVED, basis
    unknown = (op.statement or {}).get("unknown_routines") or []
    if unknown:
        # Known direct targets do not establish that ALL effects are disjoint: the routine may write
        # anywhere. Unknown effects keep the operation UNRESOLVED (F13).
        basis["reason"] = ("statement invokes routine(s) whose effects source analysis cannot establish: "
                           + ", ".join(unknown[:3]))
        basis["unknown_routines"] = unknown
        return UNRESOLVED, basis
    if store in (STORE_MONGO, STORE_REDIS, STORE_MEMORY, STORE_TRANSPORT):
        basis["reason"] = "receiver store is " + store + ", disjoint from the relational tables"
        return UNRELATED, basis

    direct = set(op.statement["target_tables"]) if op.statement else set()
    if op.statement and op.statement.get("jpql"):
        # JPQL names ENTITIES, not tables. Map each through the entity index; an entity the tree
        # does not declare (or one without a resolvable @Table) leaves the effect UNRESOLVED.
        by_lower = {name.lower(): meta for name, meta in (entity_index or {}).items()}
        mapped_tables: set[str] = set()
        unmapped: list[str] = []
        for t in direct:
            meta = by_lower.get(t.lower())
            if meta and meta.get("table"):
                mapped_tables.add(meta["table"].lower())
            else:
                unmapped.append(t)
        if unmapped:
            basis["reason"] = "JPQL entity name(s) could not be mapped to a table: " + ", ".join(sorted(unmapped))
            return UNRESOLVED, basis
        basis["jpql_entities"] = sorted(direct)
        direct = mapped_tables
    if not direct and entity is None:
        basis["reason"] = "no table set could be resolved for a postgres receiver"
        return UNRESOLVED, basis
    if entity is not None:
        if entity.get("custom_sql"):
            basis["reason"] = "entity carries custom SQL annotations that are not resolved"
            basis["custom_sql"] = entity["custom_sql"]
            return UNRESOLVED, basis
        if entity.get("table"):
            direct.add(entity["table"].lower())
    # ON DELETE CASCADE is triggered only by a DELETE (or TRUNCATE) of the PARENT row -- an INSERT
    # or UPDATE into `portfolios` never fires the cascade into `asset_holdings`. Propagating it to
    # every verb overstated the effect of the signup INSERT. Effects must be operation-specific
    # before they are used as reviewed claims.
    verbs = {v.upper() for v in (op.statement.get("verbs", []) if op.statement else [])}
    cascade_capable = any(v.startswith("DELETE") or v.startswith("TRUNCATE") for v in verbs)
    indirect: set[str] = set()
    if cascade_capable:
        for t in list(direct):
            indirect |= cascades.get(t, set())
    basis["cascade_considered"] = cascade_capable
    if unparsed_triggers and (direct & relevant_tables or indirect & relevant_tables):
        basis["reason"] = "a trigger body in the envelope could not be parsed"
        basis["unparsed_triggers"] = unparsed_triggers[:5]
        return UNRESOLVED, basis

    basis["direct_tables"] = sorted(direct)
    basis["indirect_tables"] = sorted(indirect)
    if (direct | indirect) & relevant_tables:
        basis["reason"] = "touches " + ", ".join(sorted((direct | indirect) & relevant_tables))
        return RELEVANT, basis
    if not direct and not indirect:
        basis["reason"] = "no resolved table set"
        return UNRESOLVED, basis
    basis["reason"] = "resolved table set is disjoint from relevant_tables"
    return UNRELATED, basis


#: Recognized read-only data-access methods on a postgres receiver that carry NO statement of their
#: own (repository finders, transaction plumbing, statement/connection acquisition). A call that is
#: neither a recognized write, nor one of these, nor a SQL-bearing call (which `extract_operations`
#: classifies by its statement) is an UNACCOUNTED usage and must surface as UNSUPPORTED -- a
#: resolved write on the same receiver type must never suppress it (item 5). `queryForObject` and
#: its siblings are deliberately NOT here any more (F10): their effect is decided by the SQL.
READ_ONLY_DATA_METHODS = {
    "getJdbcOperations", "getDataSource", "find", "findById", "findAll", "findFirst", "count",
    "existsById", "exists", "getOne", "getReferenceById", "unwrap", "getResultList",
    "getSingleResult", "refresh", "createStatement", "getConnection", "setAutoCommit", "commit",
    "rollback", "opsForValue", "opsForHash",
}

RECOGNIZED_RECEIVER_METHODS = (
    WRITE_METHODS | COLLECTION_MUTATORS | CALLBACK_METHODS | READ_ONLY_DATA_METHODS | SQL_QUERY_METHODS
)


def persistence_usage_findings(path: str, tokens: list[Token], contexts: list[tuple[str, str]],
                               src: str, resolved_receiver_types: set[str],
                               types: dict[str, str], store_by_type: dict[str, str]) -> list[Finding]:
    """Account for individual USAGES, not receiver types (item 5).

    Two complementary checks:
      * marker check -- a persistence type/annotation with no resolved operation of that type
        (e.g. a DatabaseClient reactive chain, an @Query repository) is unaccounted;
      * per-invocation check -- every call on a receiver typed to the postgres store must be a
        recognized write, a recognized read-only method, or it is reported UNSUPPORTED. A resolved
        `jdbcTemplate.update` no longer covers an unrecognized `jdbcTemplate.call` on the same bean."""
    findings: list[Finding] = []
    markers = sorted({m for m in PERSISTENCE_TYPE_MARKERS if m in src})
    if _REPOSITORY_RE.search(src):
        markers.append("*Repository<")
    unaccounted = [m for m in markers if m not in resolved_receiver_types]
    if unaccounted:
        findings.append(Finding(
            path, "file:", "persistence-usage", UNSUPPORTED,
            "persistence API usage is not accounted for by a recognised operation form",
            {"unaccounted_markers": unaccounted, "resolved_receivers": sorted(resolved_receiver_types)}))

    seen_calls: set[str] = set()
    for i, tok in enumerate(tokens):
        if tok.kind != "code" or tok.text != "." or i + 2 >= len(tokens):
            continue
        name_tok, open_tok = tokens[i + 1], tokens[i + 2]
        if name_tok.kind != "code" or open_tok.kind != "code" or open_tok.text != "(":
            continue
        receiver = tokens[i - 1].text if i > 0 and tokens[i - 1].kind == "code" else None
        if not receiver:
            continue
        rtype = types.get(receiver)
        if rtype is None and receiver in store_by_type:
            rtype = receiver
        if not rtype or store_by_type.get(rtype) != STORE_POSTGRES:
            continue
        if name_tok.text in RECOGNIZED_RECEIVER_METHODS:
            continue
        typ, meth = contexts[i]
        subject = "call:" + typ + "::" + (meth or "?") + "::" + receiver + "." + name_tok.text
        if subject in seen_calls:
            continue
        seen_calls.add(subject)
        findings.append(Finding(
            path, subject, "persistence-usage", UNSUPPORTED,
            "call on a persistence receiver is neither a recognised write nor a known read-only "
            "method; it is unaccounted and blocks",
            {"receiver": receiver, "receiver_type": rtype, "method": name_tok.text}))
    return findings


# --------------------------------------------------------------------------------------
# Orchestration
# --------------------------------------------------------------------------------------

PASS = "PASS"
BLOCKED = "BLOCKED"
#: Diagnostic substatus ONLY (correction 1). It never becomes an overall readiness value, always
#: exits nonzero, and always keeps candidate_ready false. Human review cannot waive unsupported
#: coverage into candidate readiness -- accepting the residue is an owner decision made elsewhere,
#: on the evidence this run prints, not a state this tool can enter.
PASS_EXCEPT_UNVERIFIED = "PASS_EXCEPT_UNVERIFIED"


def _sha256_path(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def worktree_object_id(repo: Path, rel_path: str, data: bytes) -> str:
    """The git object id `data` would have if committed at `rel_path` -- i.e. after the clean filter
    (core.autocrlf / .gitattributes eol) that git applies on the way in. Comparing raw working-tree
    bytes to blob bytes is wrong on this repository's Windows checkouts (core.autocrlf=true): the
    checkout carries CRLF while the committed blob is LF, so an unmodified analyzer "differed" from
    its committed self (F11). `git hash-object --path <rel> --stdin` applies exactly git's own
    normalisation for that path."""
    proc = subprocess.run(["git", "-C", str(repo), "hash-object", "--path", rel_path, "--stdin"],
                          input=data, capture_output=True)
    if proc.returncode != 0:
        raise EvidenceError("git hash-object failed for " + rel_path + ": "
                            + proc.stderr.decode("utf-8", errors="replace").strip())
    oid = proc.stdout.decode("ascii", errors="strict").strip()
    if not _SHA_RE.match(oid):
        raise EvidenceError("git hash-object returned an unexpected object id for " + rel_path)
    return oid


def run_all(repo: Path, policy: dict, base_sha_override: str | None, head: str = "HEAD",
            mode: str = LOCAL_PREPARATION, policy_path: Path | None = None,
            run_input: dict | None = None, run_input_path: Path | None = None) -> dict:
    base_sha = resolve_base_sha(policy, base_sha_override)
    cut_sha = resolve_commit(repo, head)
    assert_commit_present(repo, base_sha, "B1-base commit")
    assert_commit_present(repo, cut_sha, "cut commit")
    checkout_head = resolve_commit(repo, "HEAD")

    reader = BlobReader(repo)
    entries = changed_entries(repo, base_sha, cut_sha)
    tree = tree_blobs(repo, cut_sha)

    analyzer_path = Path(__file__).resolve()
    analyzer_rel = (str(analyzer_path.relative_to(repo)).replace("\\", "/")
                    if analyzer_path.is_relative_to(repo) else None)
    policy_rel = (str(policy_path.resolve().relative_to(repo)).replace("\\", "/")
                  if policy_path and policy_path.resolve().is_relative_to(repo) else None)

    findings: list[Finding] = []
    findings += policy_validity(policy, tree)

    # Deployables are discovered from the tree (F5): a governed module cannot be hidden by omitting
    # it from the policy list. Envelope validation is global and runs before any disposition (F3-4).
    # ONE historical subject index serves envelope renewals and claim validation alike (F9).
    history = HistoricalIndex(repo, reader, policy)
    governed = governed_modules(tree, reader, policy)
    envelopes, envelope_findings, records_by_id = compute_envelopes(tree, reader, policy, governed, cut_sha, history)
    findings += envelope_findings

    evidence_summary, evidence_findings = validate_run_input_evidence(
        run_input, repo, cut_sha, run_input_path, mode, base_sha)
    findings += evidence_findings

    findings += path_guard(entries, policy["gc5"], repo, base_sha, cut_sha)
    findings += content_guard(entries, reader, policy["gc5"], repo, base_sha, cut_sha)
    findings += per_holding_state(tree, reader, policy["gc5"])
    writer_findings, coverage = writer_inventory(tree, reader, policy, envelopes, repo, cut_sha,
                                                 governed, records_by_id, history)
    findings += writer_findings

    evaluator = {
        "mode": mode,
        "analyzer_path": analyzer_path.name,
        "analyzer_sha256": _sha256_path(analyzer_path),
        "policy_sha256": _sha256_path(policy_path) if policy_path else None,
        "analyzer_tracked_in_cut": bool(analyzer_rel and analyzer_rel in tree),
        "policy_tracked_in_cut": bool(policy_rel and policy_rel in tree),
        "checkout_head": checkout_head,
        "governed_modules": sorted(governed),
    }
    if mode == CANDIDATE:
        problems: list[str] = []
        if checkout_head != cut_sha:
            problems.append("the checkout HEAD " + checkout_head[:12] + " is not the cut " + cut_sha[:12]
                            + "; CANDIDATE evidence must be produced from the frozen cut itself, not "
                            "from a later checkout that happens to be clean")
        for label, rel, local in (("analyzer", analyzer_rel, analyzer_path),
                                  ("policy", policy_rel, policy_path)):
            if local is None:
                problems.append(label + " path was not supplied, so its identity cannot be verified")
                continue
            if not rel or rel not in tree:
                problems.append(label + " is not committed in the cut (" + str(local) + ")")
                continue
            # Clean-filter-aware identity: the object id the executing file WOULD have if committed
            # at its path, compared with the blob id the cut actually carries (F11).
            if worktree_object_id(repo, rel, Path(local).read_bytes()) != tree[rel]:
                problems.append(label + " differs from its committed version at the cut")
        if not is_clean(repo):
            problems.append("the working tree is not clean; CANDIDATE requires the agreed clean checkout")
        if problems:
            raise EvidenceError(
                "CANDIDATE mode requires the executing analyzer and policy to be exactly the reviewed "
                "versions committed in the cut " + cut_sha + ", evaluated from a clean checkout OF that "
                "cut: " + "; ".join(problems)
                + ". Commit the completed tooling first, check out the cut, or run in " + LOCAL_PREPARATION + " mode.")
        evaluator["identity_verified_against_cut"] = True

    unverified = coverage.get("unverified_coverage", [])
    unrelated = coverage.get("unrelated_inventory", [])
    by_kind: dict[str, int] = {}
    by_obligation: dict[str, dict[str, int]] = {}
    for f in findings:
        by_kind[f.kind] = by_kind.get(f.kind, 0) + 1
        by_obligation.setdefault(f.obligation, {})
        by_obligation[f.obligation][f.kind] = by_obligation[f.obligation].get(f.kind, 0) + 1

    # Source governance is the ONLY thing this tool evidences. PASS means every analysed obligation
    # cleared; unverified residue is a diagnostic substatus that still blocks. It is NOT release
    # readiness: this tool cannot establish the exact-digest HTTP smoke or Task B's registry portion,
    # so candidate_ready is ALWAYS false here (F1) and never inferred from an empty findings list.
    if not findings and not unverified:
        source_status, substatus = PASS, PASS
    elif not findings:
        source_status, substatus = BLOCKED, PASS_EXCEPT_UNVERIFIED
    else:
        source_status, substatus = BLOCKED, BLOCKED

    candidate_ready_blocked_by = [
        "source-governance is not PASS" if source_status != PASS else None,
        "exact-digest HTTP smoke harness is not implemented by this tool",
        "Task B registry/release portion is not evidenced by this tool",
        None if mode == CANDIDATE else "run mode is LOCAL_PREPARATION (never candidate-ready)",
        None if evidence_summary.get("task_a") and evidence_summary.get("task_b")
        else "Task A/B evidence was not supplied and validated (schema + artifacts) as a run input",
    ]
    candidate_ready_blocked_by = [x for x in candidate_ready_blocked_by if x]

    return {
        "contract": {"contract_version": CONTRACT_VERSION, "analyzer_version": ANALYZER_VERSION,
                     "normalizer_version": NORMALIZER_VERSION},
        "evaluator": evaluator,
        "evidence": evidence_summary,
        "target": {"base_sha": base_sha, "cut_sha": cut_sha,
                   "changed_paths": len(entries), "tracked_files_at_cut": len(tree)},
        "envelopes": {eid: {k: v for k, v in env.items() if k != "membership"}
                      for eid, env in envelopes.items()},
        "coverage": {
            **{k: v for k, v in coverage.items() if k not in ("unverified_coverage", "unrelated_inventory")},
            "boundaries": [
                "Receiver types are resolved from in-file/in-tree declarations only; an unresolvable "
                "receiver is UNRESOLVED and blocks.",
                "SQL-bearing calls are classified by their RESOLVED statement (S0/S1/S2); a read-only "
                "statement is one that leads with SELECT/WITH/VALUES/EXPLAIN/SHOW, carries no DML/DDL, "
                "no CALL/DO/PERFORM/INTO/LOCK/sequence construct, and invokes no persistent SQL function "
                "defined in the tree. A SELECT invoking a volatile function the tree does not define is "
                "NOT detected as a write (declared boundary).",
                "Effects are operation-specific: ON DELETE CASCADE indirect targets apply only to "
                "DELETE/TRUNCATE, not INSERT/UPDATE.",
                "Automatic effect clearance is inactive unless separately approved; UNRELATED writes "
                "in governed deployables still require explicit review.",
                "Exception provenance is proven over base..reviewed_commit for the path (pre-image at "
                "the base, post-image at the reviewed commit, reviewed_commit strictly inside the "
                "interval); a multi-hop history is reviewed cumulatively, not per hop.",
                "Detection counters are diagnostic only and are never evidence of coverage.",
            ],
        },
        "unverified_coverage": unverified,
        "unrelated_inventory": unrelated,
        "summary": {"total_findings": len(findings), "by_kind": by_kind, "by_obligation": by_obligation,
                    "unverified_coverage_count": len(unverified)},
        "findings": [f.to_json() for f in findings],
        "readiness_substatus": substatus,
        "source_governance_status": source_status,
        "overall_status": source_status,
        "candidate_ready": False,
        "candidate_ready_blocked_by": candidate_ready_blocked_by,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=str(REPO))
    parser.add_argument("--policy", default=str(DEFAULT_POLICY_PATH))
    parser.add_argument("--base-sha", default=None)
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--mode", default=LOCAL_PREPARATION, choices=[LOCAL_PREPARATION, CANDIDATE])
    parser.add_argument("--run-input", default=None,
                        help="path to a per-run evidence manifest (Task A/B); never committed in the policy")
    parser.add_argument("--out", default=None)
    args = parser.parse_args(argv)

    repo = Path(args.repo).resolve()
    policy_path = Path(args.policy)
    run_input_path = Path(args.run_input) if args.run_input else None

    try:
        policy = load_policy(policy_path)
        run_input = None
        if run_input_path is not None:
            try:
                run_input = json.loads(run_input_path.read_text(encoding="utf-8"))
            except (OSError, ValueError) as exc:
                raise EvidenceError("cannot read run-input manifest " + str(run_input_path) + ": " + str(exc)) from exc
            if not isinstance(run_input, dict):
                raise EvidenceError("run-input manifest " + str(run_input_path) + " must be a JSON object")
        result = run_all(repo, policy, args.base_sha, args.head, args.mode, policy_path, run_input,
                         run_input_path)
    except EvidenceError as exc:
        print("ERROR: " + str(exc), file=sys.stderr)
        return 1

    if args.out:
        Path(args.out).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print("wrote GC.5/writer-inventory evidence to " + args.out)
    s = result["summary"]
    print("GC.5 contract " + CONTRACT_VERSION + " [" + result["evaluator"]["mode"] + "] "
          + "base=" + result["target"]["base_sha"][:12] + " cut=" + result["target"]["cut_sha"][:12]
          + " checkout=" + result["evaluator"]["checkout_head"][:12])
    for eid, env in sorted(result["envelopes"].items()):
        print("  envelope " + eid + ": " + str(env["member_count"]) + " members, " + env["envelope_digest"][:22])
    print("  findings: " + str(s["total_findings"]) + " " + json.dumps(s["by_kind"], sort_keys=True))
    for obligation, kinds in sorted(s["by_obligation"].items()):
        print("    " + obligation + ": " + json.dumps(kinds, sort_keys=True))
    if result["unverified_coverage"]:
        print("  unverified coverage: " + str(len(result["unverified_coverage"])) + " item(s)")
    ev = result["evidence"]
    if ev.get("provided"):
        print("  evidence: task_a " + ("bound" if ev.get("task_a") else "NOT bound")
              + ", task_b " + ("bound" if ev.get("task_b") else "NOT bound")
              + ", artifacts " + ("verified" if ev.get("artifacts_verified") else "NOT verified"))
    print("  source governance: " + result["source_governance_status"]
          + " (substatus " + result["readiness_substatus"] + ")")
    print("  candidate_ready: false  blocked_by: " + "; ".join(result["candidate_ready_blocked_by"]))
    return 0 if result["source_governance_status"] == PASS else 1


if __name__ == "__main__":
    raise SystemExit(main())
