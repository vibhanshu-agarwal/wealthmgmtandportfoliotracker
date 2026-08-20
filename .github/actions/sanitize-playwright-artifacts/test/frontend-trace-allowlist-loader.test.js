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

let tmpRoot;
let manifestPath;
let lockfilePath;

function writeJson(p, value) {
  fs.writeFileSync(p, typeof value === "string" ? value : JSON.stringify(value));
}

function captureErrors() {
  const calls = [];
  return {
    reportError: (msg) => calls.push(msg),
    calls,
  };
}

// Load with the current on-disk manifest/lockfile at the temp paths.
function load(reportError) {
  return loadFrontendTraceResourceAllowlist({
    manifestPath,
    lockfilePath,
    reportError,
  });
}

describe("loadFrontendTraceResourceAllowlist — fail-closed validation (A-2.4)", () => {
  before(() => {
    tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "sanitize-loader-"));
  });

  after(() => {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  });

  beforeEach(() => {
    // Reset both files to the good baseline before each case mutates one.
    manifestPath = path.join(tmpRoot, "manifest.json");
    lockfilePath = path.join(tmpRoot, "lock.json");
    writeJson(manifestPath, GOOD_MANIFEST);
    writeJson(lockfilePath, GOOD_LOCKFILE);
  });

  it("baseline: the good pair loads exactly one entry (sanity, not a failure case)", () => {
    const { reportError, calls } = captureErrors();
    const map = load(reportError);
    assert.equal(map.size, 1);
    assert.equal(calls.length, 0);
  });

  function expectEmptyMapWithCategory(category) {
    const { reportError, calls } = captureErrors();
    const map = load(reportError);
    assert.equal(map.size, 0, "must authenticate nothing");
    assert.equal(calls.length, 1, "must log exactly one diagnostic");
    assert.match(calls[0], new RegExp(`^::error::${category}:`));
    // Never leak a secret literal in the diagnostic.
    assert.doesNotMatch(calls[0], /TestPassword123!|local-dev-password-2026/);
  }

  it("1. manifest missing or unreadable", () => {
    fs.rmSync(manifestPath);
    expectEmptyMapWithCategory("MANIFEST_MISSING_OR_UNREADABLE");
  });

  it("2. manifest content is not valid JSON", () => {
    writeJson(manifestPath, "{ not json");
    expectEmptyMapWithCategory("MANIFEST_MALFORMED_JSON");
  });

  it("3. manifest fails schema validation (assets not an array)", () => {
    writeJson(manifestPath, { ...GOOD_MANIFEST, assets: "nope" });
    expectEmptyMapWithCategory("MANIFEST_SCHEMA_INVALID");
  });

  it("3b. manifest fails schema validation (entry missing sha256)", () => {
    writeJson(manifestPath, {
      ...GOOD_MANIFEST,
      assets: [{ path: GOOD_MANIFEST.assets[0].path }],
    });
    expectEmptyMapWithCategory("MANIFEST_SCHEMA_INVALID");
  });

  it("4. boundToPackage field missing", () => {
    const m = { ...GOOD_MANIFEST };
    delete m.boundToPackage;
    writeJson(manifestPath, m);
    expectEmptyMapWithCategory("MANIFEST_MISSING_BOUND_PACKAGE");
  });

  it("5. boundToPackage present but not exactly 'next'", () => {
    writeJson(manifestPath, { ...GOOD_MANIFEST, boundToPackage: "nextjs" });
    expectEmptyMapWithCategory("MANIFEST_WRONG_BOUND_PACKAGE");
  });

  it("6. boundToVersion field missing", () => {
    const m = { ...GOOD_MANIFEST };
    delete m.boundToVersion;
    writeJson(manifestPath, m);
    expectEmptyMapWithCategory("MANIFEST_MISSING_BOUND_VERSION");
  });

  it("7. an entry's path fails per-segment validation (path traversal)", () => {
    writeJson(manifestPath, {
      ...GOOD_MANIFEST,
      assets: [{ path: "resources/../escape.woff2", sha256: GOOD_MANIFEST.assets[0].sha256 }],
    });
    expectEmptyMapWithCategory("MANIFEST_NONCANONICAL_PATH");
  });

  it("8. an entry's sha256 is not 64 lowercase hex", () => {
    writeJson(manifestPath, {
      ...GOOD_MANIFEST,
      assets: [{ path: GOOD_MANIFEST.assets[0].path, sha256: "ABC123" }],
    });
    expectEmptyMapWithCategory("MANIFEST_MALFORMED_DIGEST");
  });

  it("9. two entries share the same path", () => {
    const dup = GOOD_MANIFEST.assets[0];
    writeJson(manifestPath, { ...GOOD_MANIFEST, assets: [dup, { ...dup }] });
    expectEmptyMapWithCategory("MANIFEST_DUPLICATE_PATH");
  });

  it("10. lockfile missing or unreadable", () => {
    fs.rmSync(lockfilePath);
    expectEmptyMapWithCategory("LOCKFILE_MISSING_OR_UNREADABLE");
  });

  it("11. lockfile content is not valid JSON", () => {
    writeJson(lockfilePath, "{ not json");
    expectEmptyMapWithCategory("LOCKFILE_MALFORMED_JSON");
  });

  it("12. lockfile has no packages['node_modules/next'].version", () => {
    writeJson(lockfilePath, { lockfileVersion: 3, packages: {} });
    expectEmptyMapWithCategory("LOCKFILE_MISSING_NEXT_VERSION");
  });

  it("13. boundToVersion does not equal the lockfile's resolved next version", () => {
    writeJson(lockfilePath, {
      lockfileVersion: 3,
      packages: { "node_modules/next": { version: "16.2.4" } },
    });
    expectEmptyMapWithCategory("MANIFEST_VERSION_MISMATCH");
  });
});
