"use strict";

const { describe, it, before, after } = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { createZip } = require("./helpers/zip");
const { structuredScan } = require("../sanitize.js");

// Property 2 (P-A.2): authentication must never suppress a sentinel match.
// This fixture is deliberately synthetic and test-only -- it must NOT be
// confused with, or added to, the two real production manifest entries.
const SENTINEL = "TestPassword123!";
const FIXTURE_ENTRY_NAME = "resources/test-sentinel-fixture.woff2";
const REAL_FONT_PATH = path.join(__dirname, "fixtures", "geist-sample.woff2");

let tmpRoot;

function writeZip(name, bytes) {
  const full = path.join(tmpRoot, name);
  fs.writeFileSync(full, bytes);
  return full;
}

describe("structuredScan — sentinel-vs-authentication ordering (Property 2, P-A.2)", () => {
  before(() => {
    tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "sanitize-sentinel-order-"));
  });

  after(() => {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  });

  it("returns A/MATCH, never clean, for an entry that is both authenticated and sentinel-matched", async () => {
    // Real font bytes (so the content is genuinely UTF-8-undecodable, matching
    // the real-world shape this property has to hold for) with a test sentinel
    // appended -- appending avoids needing to understand or preserve WOFF2's
    // internal layout.
    const realFontBytes = fs.readFileSync(REAL_FONT_PATH);
    const fixtureBytes = Buffer.concat([realFontBytes, Buffer.from(SENTINEL)]);
    const digest = crypto.createHash("sha256").update(fixtureBytes).digest("hex");

    const bytes = createZip([{ name: FIXTURE_ENTRY_NAME, data: fixtureBytes }]);

    // Test-local, in-memory allowlist only -- this entry is never written to
    // known-frontend-trace-resources.json. The production manifest holds only
    // the two real fonts, ever.
    const frontendTraceAllowlist = new Map([
      [FIXTURE_ENTRY_NAME, { sha256: digest }],
    ]);

    const result = await structuredScan(writeZip("sentinel-in-authenticated.zip", bytes), {
      frontendTraceAllowlist,
    });

    // structuredScan is the public seam: inspectZipEntry's internal
    // { outcome: "A", reason: "MATCH" } surfaces here as the archive-level
    // "ENTRY_MATCH". Assert the exact public result, not just outcome === "A",
    // so this test would catch a regression that returned "clean" (authentication
    // wrongly suppressing the sentinel) as sharply as one that returned "B".
    assert.equal(result.outcome, "A");
    assert.equal(result.reason, "ENTRY_MATCH");
  });
});
