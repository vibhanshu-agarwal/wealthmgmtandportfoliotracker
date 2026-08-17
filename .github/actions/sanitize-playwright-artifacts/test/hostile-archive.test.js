"use strict";

const { describe, it, before, after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const zlib = require("zlib");
const { spawnSync } = require("node:child_process");
const { createZip, extraField } = require("./helpers/zip");
const {
  structuredScan,
  rawArchiveScan,
  STREAM_HIGH_WATER_MARK,
} = require("../sanitize.js");

const SENTINEL = "TestPassword123!";
const TEXT = "ok\n";

let tmpRoot;

function writeZip(name, bytes) {
  const full = path.join(tmpRoot, name);
  fs.writeFileSync(full, bytes);
  return full;
}

describe("instrument-fs", () => {
  it("counts a matching createReadStream and ignores a non-matching path", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "instr-"));
    const target = path.join(dir, "T.bin");
    const other = path.join(dir, "other.bin");
    fs.writeFileSync(target, "abcdef");
    fs.writeFileSync(other, "xyzxyz");
    const prevTarget = process.env.INSTRUMENT_FS_TARGET_PATH;
    const prevOut = process.env.INSTRUMENT_FS_OUTPUT;
    process.env.INSTRUMENT_FS_TARGET_PATH = target;
    delete process.env.INSTRUMENT_FS_OUTPUT;
    delete require.cache[require.resolve("./instrument-fs.js")];
    const summary = require("./instrument-fs.js");
    const before = summary.totalStreamedBytes;
    fs.createReadStream(other).resume();
    assert.equal(summary.createReadStreamCalled, false);
    assert.equal(summary.totalStreamedBytes, before);
    const chunks = [];
    const s = fs.createReadStream(target);
    s.on("data", (c) => chunks.push(c));
    return new Promise((resolve, reject) => {
      s.on("error", reject);
      s.on("end", () => {
        assert.equal(summary.createReadStreamCalled, true);
        assert.ok(summary.totalStreamedBytes > before);
        assert.throws(() => fs.readFileSync(target));
        process.env.INSTRUMENT_FS_TARGET_PATH = prevTarget;
        process.env.INSTRUMENT_FS_OUTPUT = prevOut;
        fs.rmSync(dir, { recursive: true, force: true });
        resolve();
      });
    });
  });
});

describe("hostile archives", () => {
  before(() => {
    tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "sanitize-hostile-"));
  });
  after(() => {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  });

  it("detects a sentinel only in a local-file-header extra region", async () => {
    const localExtra = extraField(0x9901, SENTINEL);
    const bytes = createZip([
      { name: "ok.txt", data: TEXT, extra: Buffer.alloc(0), localExtra },
    ]);
    const filePath = writeZip("lfh.zip", bytes);
    const raw = await rawArchiveScan(filePath);
    assert.equal(raw.outcome, "A");
    const structured = await structuredScan(filePath);
    assert.equal(structured.outcome, "clean");
  });

  it("detects a sentinel split across a raw-scan chunk boundary", async () => {
    const splitAt = 4;
    const prefix = Buffer.alloc(STREAM_HIGH_WATER_MARK - splitAt, 0x41);
    const bytes = createZip([{ name: "ok.txt", data: TEXT }], { prefix });
    const sentinelBuf = Buffer.from(SENTINEL);
    const injected = Buffer.concat([
      bytes.subarray(0, STREAM_HIGH_WATER_MARK - splitAt),
      sentinelBuf,
      bytes.subarray(STREAM_HIGH_WATER_MARK - splitAt),
    ]);
    const filePath = writeZip("split-raw.zip", injected);
    const raw = await rawArchiveScan(filePath);
    assert.equal(raw.outcome, "A");
  });

  it("is Outcome B for a CRC-32 mismatch", async () => {
    const bytes = createZip([{ name: "ok.txt", data: TEXT }]);
    bytes[bytes.indexOf(Buffer.from("ok\n")) + 1] ^= 0xff;
    const result = await structuredScan(writeZip("crc.zip", bytes));
    assert.equal(result.outcome, "B");
  });

  it("is Outcome B for a path-traversal entry name", async () => {
    const bytes = createZip([{ name: "../evil.txt", data: TEXT }]);
    const result = await structuredScan(writeZip("trav.zip", bytes));
    assert.equal(result.outcome, "B");
  });

  it("is Outcome B for duplicate entry names", async () => {
    const bytes = createZip([
      { name: "ok.txt", data: TEXT },
      { name: "ok.txt", data: "other\n" },
    ]);
    const result = await structuredScan(writeZip("dup.zip", bytes));
    assert.equal(result.outcome, "B");
  });

  it("is Outcome B for a symlink entry", async () => {
    const bytes = createZip([
      {
        name: "link",
        data: "target",
        externalAttr: (0xa000 << 16) >>> 0,
        versionMadeBy: 3 << 8,
      },
    ]);
    const result = await structuredScan(writeZip("sym.zip", bytes));
    assert.equal(result.outcome, "B");
  });

  it("is Outcome B for >5 levels of nesting", async () => {
    let inner = createZip([{ name: "leaf.txt", data: TEXT }]);
    for (let i = 0; i < 6; i += 1) {
      inner = createZip([{ name: `n${i}.zip`, data: inner }]);
    }
    const result = await structuredScan(writeZip("deep.zip", inner));
    assert.equal(result.outcome, "B");
  });

  it("is Outcome B for >5000 entries", async () => {
    const entries = [];
    for (let i = 0; i < 5001; i += 1) {
      entries.push({ name: `f${i}.txt`, data: "x" });
    }
    const result = await structuredScan(writeZip("many.zip", createZip(entries)));
    assert.equal(result.outcome, "B");
  });

  it("is Outcome B for a per-entry uncompressed over-limit", async () => {
    const zeros = Buffer.alloc(51 * 1024 * 1024, 0);
    const bytes = createZip([{ name: "big.txt", data: zeros, deflate: true }]);
    const result = await structuredScan(writeZip("huge-entry.zip", bytes));
    assert.equal(result.outcome, "B");
  });

  it("is Outcome B for a >100:1 compression-ratio entry", async () => {
    const zeros = Buffer.alloc(2 * 1024 * 1024, 0);
    const bytes = createZip([{ name: "bomb.txt", data: zeros, deflate: true }]);
    const result = await structuredScan(writeZip("bomb.zip", bytes));
    assert.equal(result.outcome, "B");
  });

  it("is Outcome B for unsupported compression", async () => {
    const bytes = createZip([
      { name: "ok.txt", data: TEXT, compressionMethod: 99 },
    ]);
    const result = await structuredScan(writeZip("method.zip", bytes));
    assert.equal(result.outcome, "B");
  });

  it("is Outcome B for an encrypted ZIP", async () => {
    const bytes = createZip([{ name: "ok.txt", data: TEXT, flags: 0x40 }]);
    const result = await structuredScan(writeZip("enc.zip", bytes));
    assert.equal(result.outcome, "B");
  });
});
