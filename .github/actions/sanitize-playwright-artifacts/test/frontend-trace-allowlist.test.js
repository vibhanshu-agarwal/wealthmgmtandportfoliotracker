"use strict";

const { describe, it, before, after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { createZip } = require("./helpers/zip");
const { structuredScan } = require("../sanitize.js");

// Real Geist Mono WOFF2 bytes extracted from a live trace.zip captured during
// investigation of the mocked-chaos-assertion-and-sanitizer-font-gap bugfix
// (see .kiro/specs/mocked-chaos-assertion-and-sanitizer-font-gap/). Byte-identical
// to what next/font/google actually serves, so its digest matches production
// allowlist entries -- a synthetic fixture could not provide that.
const FIXTURE_FONT_PATH = path.join(__dirname, "fixtures", "geist-sample.woff2");
const FIXTURE_ENTRY_NAME =
  "resources/0e04bb6e7b54057d64c421b959c8c22774ae632d.woff2";
const FIXTURE_FONT_SHA256 =
  "5f3d6ad60f29d6cb708414ec6887163d63bf197377ef5417d2483ff31ace6c3b";

let tmpRoot;

function writeZip(name, bytes) {
  const full = path.join(tmpRoot, name);
  fs.writeFileSync(full, bytes);
  return full;
}

describe("structuredScan — frontend trace-resource allowlist (Property 1, P-A.1)", () => {
  before(() => {
    tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "sanitize-frontend-allowlist-"));
  });

  after(() => {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  });

  it("authenticates a manifest-matched WOFF2 entry instead of returning UNINSPECTABLE_ENTRY", async () => {
    const fontBytes = fs.readFileSync(FIXTURE_FONT_PATH);
    const bytes = createZip([{ name: FIXTURE_ENTRY_NAME, data: fontBytes }]);

    // Same call, unchanged, before and after the fix (tasks 4.3-4.4): on unfixed
    // code structuredScan's options destructuring does not read
    // frontendTraceAllowlist at all, so this key is silently ignored and the
    // entry falls through to the existing UNINSPECTABLE_ENTRY path. After the
    // fix, the same call must authenticate the entry and return something
    // other than "B". A test that omits this key here and adds it only in a
    // later run would never actually prove the fix -- it would prove a
    // differently-shaped call, which is not the same claim.
    const frontendTraceAllowlist = new Map([
      [FIXTURE_ENTRY_NAME, { sha256: FIXTURE_FONT_SHA256 }],
    ]);

    const result = await structuredScan(writeZip("font.zip", bytes), {
      frontendTraceAllowlist,
    });

    assert.notEqual(result.outcome, "B");
  });
});
