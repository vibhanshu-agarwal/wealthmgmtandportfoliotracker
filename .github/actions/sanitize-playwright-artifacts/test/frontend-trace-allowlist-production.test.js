"use strict";

const { describe, it, before, after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { createZip } = require("./helpers/zip");
const {
  structuredScan,
  loadFrontendTraceResourceAllowlist,
} = require("../sanitize.js");

// A-2.3 / A-1.5: the committed real-WOFF2 fixture, run through structuredScan
// with the REAL production manifest (loaded from known-frontend-trace-resources.json
// via loadFrontendTraceResourceAllowlist(), no injected paths), must sanitize
// cleanly. This is the fixed-code counterpart to the RED test in
// frontend-trace-allowlist.test.js -- it proves the actual production manifest,
// not a synthetic in-memory one, authenticates the actual bytes next/font
// serves. If either the manifest digest or the committed fixture ever drifts,
// this test fails.
const FIXTURE_FONT_PATH = path.join(__dirname, "fixtures", "geist-sample.woff2");
const FIXTURE_ENTRY_NAME =
  "resources/0e04bb6e7b54057d64c421b959c8c22774ae632d.woff2";

let tmpRoot;

function writeZip(name, bytes) {
  const full = path.join(tmpRoot, name);
  fs.writeFileSync(full, bytes);
  return full;
}

describe("structuredScan — real production manifest authenticates the committed fixture (A-2.3)", () => {
  before(() => {
    tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "sanitize-prod-manifest-"));
  });

  after(() => {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  });

  it("sanitizes the committed Geist fixture cleanly using the on-disk production manifest", async () => {
    const frontendTraceAllowlist = loadFrontendTraceResourceAllowlist();
    // Guard: if the manifest or lockfile drifted, the loader returns an empty
    // map and this test would pass vacuously via UNINSPECTABLE_ENTRY below.
    // Assert the real manifest actually loaded its two entries first.
    assert.equal(
      frontendTraceAllowlist.size,
      2,
      "production manifest must load 2 entries; empty means it failed validation (see loader diagnostics)",
    );

    const fontBytes = fs.readFileSync(FIXTURE_FONT_PATH);
    const bytes = createZip([{ name: FIXTURE_ENTRY_NAME, data: fontBytes }]);

    const result = await structuredScan(writeZip("prod.zip", bytes), {
      frontendTraceAllowlist,
    });

    assert.notEqual(result.outcome, "B");
  });
});
