"use strict";

const { describe, it, before, after, beforeEach } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { loadFrontendTraceResourceAllowlist } = require("../sanitize.js");

// A well-formed manifest and lockfile pair, from which each test derives a
// single deliberate malformation. The digest is a valid 64-hex string; the
// path is canonical; boundToVersion matches the lockfile's next version.
const GOOD_MANIFEST = {
  boundToPackage: "next",
  boundToVersion: "16.2.3",
  assets: [
    {
      path: "resources/0e04bb6e7b54057d64c421b959c8c22774ae632d.woff2",
      sha256: "5f3d6ad60f29d6cb708414ec6887163d63bf197377ef5417d2483ff31ace6c3b",
    },
  ],
};
const GOOD_LOCKFILE = {
  lockfileVersion: 3,
  packages: { "node_modules/next": { version: "16.2.3" } },
};

// A hostile marker combining every leakage vector at once: a secret sentinel,
// a forged GitHub Actions workflow-command annotation, a newline, and a C0
// control byte (0x01). If any diagnostic interpolated a raw manifest/lockfile
// field, this string would leak into the ::error:: annotation stream -- the
// exact class this sanitizer exists to prevent. Every "field that used to be
// logged" fixture carries it, and every case asserts it never appears.
const POISON = "TestPassword123!::error::forged\n";

function hasControlByte(s) {
  for (let i = 0; i < s.length; i += 1) {
    const c = s.charCodeAt(i);
    if (c <= 0x1f || c === 0x7f) return true;
  }
  return false;
}

let tmpRoot;
let manifestPath;
let lockfilePath;

function writeRaw(p, value) {
  fs.writeFileSync(p, typeof value === "string" ? value : JSON.stringify(value));
}

function captureErrors() {
  const calls = [];
  return {
    reportError: (msg) => calls.push(msg),
    calls,
  };
}

function load(reportError) {
  return loadFrontendTraceResourceAllowlist({
    manifestPath,
    lockfilePath,
    reportError,
  });
}

describe("loadFrontendTraceResourceAllowlist -- fail-closed validation (A-2.4)", () => {
  before(() => {
    tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "sanitize-loader-"));
  });

  after(() => {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  });

  beforeEach(() => {
    manifestPath = path.join(tmpRoot, "manifest.json");
    lockfilePath = path.join(tmpRoot, "lock.json");
    writeRaw(manifestPath, GOOD_MANIFEST);
    writeRaw(lockfilePath, GOOD_LOCKFILE);
  });

  it("baseline: the good pair loads exactly one entry (sanity, not a failure case)", () => {
    const { reportError, calls } = captureErrors();
    const map = load(reportError);
    assert.equal(map.size, 1);
    assert.equal(calls.length, 0);
  });

  // Every failing case must: authenticate nothing, log exactly one diagnostic,
  // and that diagnostic must EXACTLY equal the safe format `::error::CATEGORY`
  // or `::error::CATEGORY (asset index N)` -- nothing else. Exact-match is a
  // stronger guarantee than a substring check: if the whole string is one of
  // those two shapes, no raw field value could have leaked into it. The
  // explicit POISON / control-byte assertions are belt-and-suspenders on top,
  // and are non-vacuous because the fixtures below inject POISON into exactly
  // the fields the pre-fix code interpolated.
  function expectSafeDiagnostic(category, opts = {}) {
    const { reportError, calls } = captureErrors();
    const map = load(reportError);
    assert.equal(map.size, 0, "must authenticate nothing");
    assert.equal(calls.length, 1, "must log exactly one diagnostic");

    const expected =
      typeof opts.assetIndex === "number"
        ? `::error::${category} (asset index ${opts.assetIndex})`
        : `::error::${category}`;
    assert.equal(calls[0], expected, "diagnostic must be exactly the safe format");
    assert.ok(
      !calls[0].includes("TestPassword123!"),
      "diagnostic must not leak the sentinel",
    );
    assert.ok(
      !calls[0].includes("forged"),
      "diagnostic must not leak a forged annotation fragment",
    );
    assert.ok(
      !hasControlByte(calls[0]),
      "diagnostic must not contain a newline or any other control byte",
    );
  }

  it("1. manifest missing or unreadable", () => {
    fs.rmSync(manifestPath);
    expectSafeDiagnostic("MANIFEST_MISSING_OR_UNREADABLE");
  });

  it("2. manifest content is not valid JSON", () => {
    writeRaw(manifestPath, "{ not json");
    expectSafeDiagnostic("MANIFEST_MALFORMED_JSON");
  });

  it("3. manifest fails schema validation (assets not an array)", () => {
    writeRaw(manifestPath, { ...GOOD_MANIFEST, assets: "nope" });
    expectSafeDiagnostic("MANIFEST_SCHEMA_INVALID");
  });

  it("3b. manifest fails schema validation (entry missing sha256, at index 0)", () => {
    writeRaw(manifestPath, {
      ...GOOD_MANIFEST,
      assets: [{ path: GOOD_MANIFEST.assets[0].path }],
    });
    expectSafeDiagnostic("MANIFEST_SCHEMA_INVALID", { assetIndex: 0 });
  });

  it("4. boundToPackage field missing", () => {
    const m = { ...GOOD_MANIFEST };
    delete m.boundToPackage;
    writeRaw(manifestPath, m);
    expectSafeDiagnostic("MANIFEST_MISSING_BOUND_PACKAGE");
  });

  it("5. boundToPackage present but not 'next' -- POISON value must not leak", () => {
    writeRaw(manifestPath, { ...GOOD_MANIFEST, boundToPackage: POISON });
    expectSafeDiagnostic("MANIFEST_WRONG_BOUND_PACKAGE");
  });

  it("6. boundToVersion field missing", () => {
    const m = { ...GOOD_MANIFEST };
    delete m.boundToVersion;
    writeRaw(manifestPath, m);
    expectSafeDiagnostic("MANIFEST_MISSING_BOUND_VERSION");
  });

  it("7. entry path fails validation with control bytes -- POISON path must not leak", () => {
    // A path that fails isValidTraceSegment AND carries control bytes / a forged
    // annotation. In the pre-fix code this was logged verbatim.
    writeRaw(manifestPath, {
      ...GOOD_MANIFEST,
      assets: [{ path: `resources/../${POISON}.woff2`, sha256: GOOD_MANIFEST.assets[0].sha256 }],
    });
    expectSafeDiagnostic("MANIFEST_NONCANONICAL_PATH", { assetIndex: 0 });
  });

  it("8. entry sha256 is not 64 lowercase hex -- POISON digest must not leak", () => {
    writeRaw(manifestPath, {
      ...GOOD_MANIFEST,
      assets: [{ path: GOOD_MANIFEST.assets[0].path, sha256: POISON }],
    });
    expectSafeDiagnostic("MANIFEST_MALFORMED_DIGEST", { assetIndex: 0 });
  });

  it("9. two entries share the same path (reported at the duplicate's index 1)", () => {
    const dup = GOOD_MANIFEST.assets[0];
    writeRaw(manifestPath, { ...GOOD_MANIFEST, assets: [dup, { ...dup }] });
    expectSafeDiagnostic("MANIFEST_DUPLICATE_PATH", { assetIndex: 1 });
  });

  it("10. lockfile missing or unreadable", () => {
    fs.rmSync(lockfilePath);
    expectSafeDiagnostic("LOCKFILE_MISSING_OR_UNREADABLE");
  });

  it("11. lockfile content is not valid JSON", () => {
    writeRaw(lockfilePath, "{ not json");
    expectSafeDiagnostic("LOCKFILE_MALFORMED_JSON");
  });

  it("12. lockfile has no packages['node_modules/next'].version", () => {
    writeRaw(lockfilePath, { lockfileVersion: 3, packages: {} });
    expectSafeDiagnostic("LOCKFILE_MISSING_NEXT_VERSION");
  });

  it("13. version mismatch -- neither version string must leak", () => {
    // boundToVersion carries POISON (a non-empty string, so it passes the
    // missing-version gate and reaches the mismatch check); the lockfile
    // resolves a different, also-hostile value. Pre-fix code logged both.
    writeRaw(manifestPath, { ...GOOD_MANIFEST, boundToVersion: POISON });
    writeRaw(lockfilePath, {
      lockfileVersion: 3,
      packages: { "node_modules/next": { version: `${POISON}-other` } },
    });
    expectSafeDiagnostic("MANIFEST_VERSION_MISMATCH");
  });
});
