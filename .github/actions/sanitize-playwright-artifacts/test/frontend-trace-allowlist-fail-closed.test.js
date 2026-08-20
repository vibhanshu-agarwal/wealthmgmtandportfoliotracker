"use strict";

const { describe, it, before, after } = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { createZip } = require("./helpers/zip");
const { structuredScan } = require("../sanitize.js");

// Property 3 (P-A.3): a binary entry that is not a manifest-matched, depth-0
// resource must still fail closed. The allowlist authenticates specific
// reviewed bytes at a specific depth -- never a content-type or extension
// exemption.
const REAL_FONT_PATH = path.join(__dirname, "fixtures", "geist-sample.woff2");
const REAL_ENTRY_NAME =
  "resources/0e04bb6e7b54057d64c421b959c8c22774ae632d.woff2";
const REAL_FONT_SHA256 =
  "5f3d6ad60f29d6cb708414ec6887163d63bf197377ef5417d2483ff31ace6c3b";

let tmpRoot;

function writeZip(name, bytes) {
  const full = path.join(tmpRoot, name);
  fs.writeFileSync(full, bytes);
  return full;
}

function sha256(buf) {
  return crypto.createHash("sha256").update(buf).digest("hex");
}

describe("structuredScan — font allowlist fails closed (Property 3, P-A.3)", () => {
  before(() => {
    tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "sanitize-fail-closed-"));
  });

  after(() => {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  });

  it("is UNINSPECTABLE_ENTRY for a manifest path with the WRONG bytes (digest mismatch)", async () => {
    // Real manifest path, but content mutated so the digest no longer matches.
    const realFontBytes = fs.readFileSync(REAL_FONT_PATH);
    const mutated = Buffer.concat([realFontBytes, Buffer.from([0x00])]);
    const bytes = createZip([{ name: REAL_ENTRY_NAME, data: mutated }]);

    // Allowlist still names the real digest; the entry's actual digest differs.
    const frontendTraceAllowlist = new Map([
      [REAL_ENTRY_NAME, { sha256: REAL_FONT_SHA256 }],
    ]);

    const result = await structuredScan(writeZip("wrong-bytes.zip", bytes), {
      frontendTraceAllowlist,
    });
    assert.equal(result.outcome, "B");
    assert.equal(result.reason, "UNINSPECTABLE_ENTRY");
  });

  it("is UNINSPECTABLE_ENTRY for a binary at a path NOT in the manifest", async () => {
    const realFontBytes = fs.readFileSync(REAL_FONT_PATH);
    const unknownName = "resources/not-in-manifest.woff2";
    const bytes = createZip([{ name: unknownName, data: realFontBytes }]);

    // Manifest is correct and non-empty, but does not name this path. Even
    // though the bytes themselves match a real font's digest, the path is
    // unknown, so it must fail closed -- authentication is path+digest, not
    // digest alone.
    const frontendTraceAllowlist = new Map([
      [REAL_ENTRY_NAME, { sha256: REAL_FONT_SHA256 }],
    ]);

    const result = await structuredScan(writeZip("unknown-path.zip", bytes), {
      frontendTraceAllowlist,
    });
    assert.equal(result.outcome, "B");
    assert.equal(result.reason, "UNINSPECTABLE_ENTRY");
  });

  it("is UNINSPECTABLE_ENTRY for a manifest-matching entry nested one zip deeper (depth > 0)", async () => {
    // The exact real font, at the exact real manifest path and digest -- but
    // wrapped inside an inner zip inside the outer trace zip, so it is
    // discovered at nesting depth 1. Authentication only applies at depth 0,
    // and the allowlist is structurally not forwarded into the recursive
    // scan, so this must fail closed regardless of digest.
    const realFontBytes = fs.readFileSync(REAL_FONT_PATH);
    const innerZip = createZip([{ name: REAL_ENTRY_NAME, data: realFontBytes }]);
    const outerZip = createZip([{ name: "inner.zip", data: innerZip }]);

    const frontendTraceAllowlist = new Map([
      [REAL_ENTRY_NAME, { sha256: REAL_FONT_SHA256 }],
    ]);

    const result = await structuredScan(writeZip("nested.zip", outerZip), {
      frontendTraceAllowlist,
    });
    assert.equal(result.outcome, "B");
    assert.equal(result.reason, "UNINSPECTABLE_ENTRY");
  });
});
