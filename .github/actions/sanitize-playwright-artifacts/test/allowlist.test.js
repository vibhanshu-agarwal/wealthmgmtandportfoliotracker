"use strict";

const { describe, it, before, after } = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const {
  classify,
  isValidTraceSegment,
  toCanonicalTracePath,
  validateManifestTracePath,
} = require("../sanitize.js");
const {
  generateManifest,
  verifyManifest,
  validateManifestSchema,
} = require("../known-assets-manifest-tool.js");

let tmpRoot;
let stagingRoot;

function writeFile(rel, contents) {
  const full = path.join(stagingRoot, rel);
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, contents);
  return full;
}

function sha256(buf) {
  return crypto.createHash("sha256").update(buf).digest("hex");
}

describe("isValidTraceSegment", () => {
  it("rejects empty, dot, dot-dot, backslash, NUL, and other C0 bytes", () => {
    assert.equal(isValidTraceSegment(""), false);
    assert.equal(isValidTraceSegment("."), false);
    assert.equal(isValidTraceSegment(".."), false);
    assert.equal(isValidTraceSegment("a\\b"), false);
    assert.equal(isValidTraceSegment("a\u0000b"), false);
    assert.equal(isValidTraceSegment("a\u0001b"), false);
  });

  it("accepts an ordinary filename segment", () => {
    assert.equal(isValidTraceSegment("codicon.DCmgc-ay.ttf"), true);
  });
});

describe("toCanonicalTracePath", () => {
  const root = path.resolve("/staging");

  it("resolves nested and flat trace paths to themselves", () => {
    assert.equal(
      toCanonicalTracePath(path.join(root, "trace", "codicon.DCmgc-ay.ttf"), root),
      "trace/codicon.DCmgc-ay.ttf",
    );
    assert.equal(
      toCanonicalTracePath(path.join(root, "trace", "assets", "x.js"), root),
      "trace/assets/x.js",
    );
  });

  it("returns null for every hostile or wrongly-rooted path", () => {
    assert.equal(
      toCanonicalTracePath(path.resolve("/staging/trace/../evil.bin"), root),
      null,
    );
    assert.equal(
      toCanonicalTracePath(
        path.join(root, "playwright-report", "trace", "codicon.DCmgc-ay.ttf"),
        root,
      ),
      null,
    );
    assert.equal(toCanonicalTracePath(path.resolve("/etc/passwd"), root), null);
    assert.equal(
      toCanonicalTracePath(
        path.resolve("/other-staging-dir/trace/x"),
        root,
      ),
      null,
    );
  });

  it("pins path.relative(stagingDirRoot, filePath) argument order", () => {
    const filePath = path.join(root, "trace", "x");
    assert.equal(toCanonicalTracePath(filePath, root), "trace/x");
    assert.notEqual(path.relative(filePath, root), "trace/x");
  });
});

describe("validateManifestTracePath", () => {
  const root = path.resolve("/staging");
  const accepted = ["trace/codicon.DCmgc-ay.ttf", "trace/assets/x.js"];
  const rejected = [
    "trace/./evil.bin",
    "trace/a/../evil.bin",
    "/trace/x",
    "trace\\x",
    "trace/\u0000x",
    "../trace/x",
    "/abs",
  ];

  it("rejects raw strings that alias a legitimate entry once resolved", () => {
    assert.equal(
      path.resolve(root, "trace/./evil.bin"),
      path.resolve(root, "trace/evil.bin"),
    );
    assert.equal(
      path.resolve(root, "trace/a/../evil.bin"),
      path.resolve(root, "trace/evil.bin"),
    );
    assert.equal(validateManifestTracePath("trace/./evil.bin", root), null);
    assert.equal(validateManifestTracePath("trace/a/../evil.bin", root), null);
  });

  it("returns the raw path for every accepted fixture (equality check holds)", () => {
    for (const raw of accepted) {
      assert.equal(validateManifestTracePath(raw, root), raw);
    }
  });

  it("returns null for every rejected raw path before relying on equality", () => {
    for (const raw of rejected) {
      assert.equal(validateManifestTracePath(raw, root), null);
    }
  });
});

describe("classify allowlist branch", () => {
  before(() => {
    tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "sanitize-allow-"));
    stagingRoot = path.join(tmpRoot, "staging");
    fs.mkdirSync(path.join(stagingRoot, "trace", "assets"), { recursive: true });
  });

  after(() => {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  });

  it("authenticates a binary trace asset whose digest matches", async () => {
    const blob = Buffer.from([0x00, 0x01, 0x02, 0xff, 0xfe, 0x00]);
    const filePath = writeFile("trace/font.ttf", blob);
    const allowlist = new Map([
      ["trace/font.ttf", { sha256: sha256(blob) }],
    ]);
    const result = await classify(filePath, stagingRoot, { allowlist });
    assert.notEqual(result.type, "UNINSPECTABLE");
    assert.equal(result.matched, false);
    assert.equal(result.authenticated, true);
  });

  it("is Outcome A when an authenticated asset contains a sentinel", async () => {
    const blob = Buffer.concat([
      Buffer.from([0x00, 0xff]),
      Buffer.from("TestPassword123!"),
    ]);
    const filePath = writeFile("trace/hit.bin", blob);
    const allowlist = new Map([
      ["trace/hit.bin", { sha256: sha256(blob) }],
    ]);
    const result = await classify(filePath, stagingRoot, { allowlist });
    assert.equal(result.matched, true);
    assert.equal(result.authenticated, true);
  });

  it("falls through to the UTF-8 gate when the digest is wrong", async () => {
    const blob = Buffer.from([0x00, 0x01, 0xff]);
    const filePath = writeFile("trace/tamper.ttf", blob);
    const allowlist = new Map([
      ["trace/tamper.ttf", { sha256: "ab".repeat(32) }],
    ]);
    const result = await classify(filePath, stagingRoot, { allowlist });
    assert.equal(result.type, "UNINSPECTABLE");
  });

  it("flushes the decoder for an incomplete trailing sequence on a non-matching candidate", async () => {
    const blob = Buffer.from("hello\xe2", "binary");
    const filePath = writeFile("trace/incomplete.txt", blob);
    const result = await classify(filePath, stagingRoot, { allowlist: new Map() });
    assert.equal(result.type, "UNINSPECTABLE");
  });

  it("opens exactly one read stream per allowlist-candidate file", async () => {
    const blob = Buffer.from("abc");
    const filePath = writeFile("trace/once.txt", blob);
    const allowlist = new Map([
      ["trace/once.txt", { sha256: sha256(blob) }],
    ]);
    const original = fs.createReadStream;
    let opens = 0;
    fs.createReadStream = function patched(p, opts) {
      if (path.resolve(p) === path.resolve(filePath)) opens += 1;
      return original.call(fs, p, opts);
    };
    try {
      await classify(filePath, stagingRoot, { allowlist });
      assert.equal(opens, 1);
    } finally {
      fs.createReadStream = original;
    }
  });
});

describe("known-assets-manifest-tool", () => {
  let reportDir;
  let lockfilePath;

  before(() => {
    reportDir = fs.mkdtempSync(path.join(os.tmpdir(), "sanitize-report-"));
    const trace = path.join(reportDir, "trace");
    fs.mkdirSync(path.join(trace, "assets"), { recursive: true });
    fs.writeFileSync(path.join(trace, "a.js"), "aaa");
    fs.writeFileSync(path.join(trace, "assets", "b.js"), "bbb");
    lockfilePath = path.join(reportDir, "package-lock.json");
    fs.writeFileSync(
      lockfilePath,
      JSON.stringify({
        packages: {
          "node_modules/@playwright/test": { version: "1.59.0" },
          "node_modules/playwright": { version: "1.59.0" },
          "node_modules/playwright-core": { version: "1.59.0" },
        },
      }),
    );
  });

  after(() => {
    fs.rmSync(reportDir, { recursive: true, force: true });
  });

  it("rejects non-canonical paths, malformed digests, and duplicate paths", () => {
    const root = path.resolve("/staging");
    assert.throws(() =>
      validateManifestSchema(
        {
          playwrightTestVersion: "1.59.0",
          assets: [{ path: "../trace/x", sha256: "ab".repeat(32) }],
        },
        root,
      ),
    );
    assert.throws(() =>
      validateManifestSchema(
        {
          playwrightTestVersion: "1.59.0",
          assets: [{ path: "trace/x", sha256: "ABCD" }],
        },
        root,
      ),
    );
    assert.throws(() =>
      validateManifestSchema(
        {
          playwrightTestVersion: "1.59.0",
          assets: [
            { path: "trace/x", sha256: "ab".repeat(32) },
            { path: "trace/x", sha256: "cd".repeat(32) },
          ],
        },
        root,
      ),
    );
  });

  it("generates and verifies exact set equality against synthetic output", () => {
    const manifestPath = path.join(reportDir, "manifest.json");
    generateManifest(reportDir, manifestPath, { lockfilePath });
    verifyManifest(reportDir, manifestPath, { lockfilePath });
  });

  it("fails verify when the manifest has an extra or omitted asset", () => {
    const manifestPath = path.join(reportDir, "manifest.json");
    generateManifest(reportDir, manifestPath, { lockfilePath });
    const extra = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
    extra.assets.push({ path: "trace/extra.js", sha256: "ab".repeat(32) });
    const extraPath = path.join(reportDir, "extra.json");
    fs.writeFileSync(extraPath, JSON.stringify(extra));
    assert.throws(() => verifyManifest(reportDir, extraPath, { lockfilePath }));

    const omitted = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
    omitted.assets = omitted.assets.slice(0, 1);
    const omittedPath = path.join(reportDir, "omitted.json");
    fs.writeFileSync(omittedPath, JSON.stringify(omitted));
    assert.throws(() => verifyManifest(reportDir, omittedPath, { lockfilePath }));
  });

  it("fails closed on playwrightTestVersion mismatch and package divergence", () => {
    const manifestPath = path.join(reportDir, "manifest.json");
    generateManifest(reportDir, manifestPath, { lockfilePath });
    const wrongVer = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
    wrongVer.playwrightTestVersion = "0.0.0";
    const wrongPath = path.join(reportDir, "wrong-ver.json");
    fs.writeFileSync(wrongPath, JSON.stringify(wrongVer));
    assert.throws(() => verifyManifest(reportDir, wrongPath, { lockfilePath }));

    const divergedLock = path.join(reportDir, "diverged-lock.json");
    fs.writeFileSync(
      divergedLock,
      JSON.stringify({
        packages: {
          "node_modules/@playwright/test": { version: "1.59.0" },
          "node_modules/playwright": { version: "1.58.0" },
          "node_modules/playwright-core": { version: "1.59.0" },
        },
      }),
    );
    assert.throws(() =>
      verifyManifest(reportDir, manifestPath, { lockfilePath: divergedLock }),
    );
  });
});
