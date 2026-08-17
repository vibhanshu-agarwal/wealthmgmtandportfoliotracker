"use strict";

const { describe, it, before, after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const zlib = require("zlib");
const { createZip } = require("./helpers/zip");
const {
  classify,
  TOP_LEVEL_FILE_BYTE_LIMIT,
  STREAM_HIGH_WATER_MARK,
} = require("../sanitize.js");

const SENTINEL = "TestPassword123!";

let tmpRoot;
let stagingRoot;

function writeFile(rel, contents) {
  const full = path.join(stagingRoot, rel);
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, contents);
  return full;
}

describe("classify", () => {
  before(() => {
    tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "sanitize-classify-"));
    stagingRoot = path.join(tmpRoot, "staging");
    fs.mkdirSync(stagingRoot);
  });

  after(() => {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  });

  it("classifies a renamed valid ZIP as ZIP", async () => {
    const bytes = createZip([{ name: "ok.txt", data: "hello world\n" }]);
    const filePath = writeFile("renamed.txt", bytes);
    const result = await classify(filePath, stagingRoot);
    assert.equal(result.type, "ZIP");
  });

  it("treats a truncated local-file-header as UNINSPECTABLE, never TEXT", async () => {
    const filePath = writeFile(
      "truncated.bin",
      Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x61, 0x62, 0x63]),
    );
    const result = await classify(filePath, stagingRoot);
    assert.equal(result.type, "UNINSPECTABLE");
  });

  it("classifies ordinary UTF-8 containing ZIP-like bytes but no record signature as TEXT", async () => {
    const filePath = writeFile("note.md", "hello PK world and PKzip mention\n");
    const result = await classify(filePath, stagingRoot);
    assert.equal(result.type, "TEXT");
    assert.equal(result.matched, false);
  });

  it("treats gzip-compressed dummy sentinel as UNINSPECTABLE", async () => {
    const gz = zlib.gzipSync(Buffer.from(SENTINEL));
    assert.equal(gz.length, 36);
    const filePath = writeFile("hidden.gz", gz);
    const result = await classify(filePath, stagingRoot);
    assert.equal(result.type, "UNINSPECTABLE");
  });

  it("treats a mislabeled JPEG renamed .json as UNINSPECTABLE", async () => {
    const jpeg = Buffer.from([
      0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01,
      0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0xff, 0xd9,
    ]);
    const filePath = writeFile("photo.json", jpeg);
    const result = await classify(filePath, stagingRoot);
    assert.equal(result.type, "UNINSPECTABLE");
  });

  it("classifies a self-extracting ZIP with a prepended payload as ZIP", async () => {
    const prefix = Buffer.concat([
      Buffer.from("MZ"),
      Buffer.alloc(64, 0x41),
    ]);
    const bytes = createZip([{ name: "payload.txt", data: "sfx-ok\n" }], {
      prefix,
    });
    const filePath = writeFile("tool.exe", bytes);
    const result = await classify(filePath, stagingRoot);
    assert.equal(result.type, "ZIP");
  });

  it("aborts an over-TOP_LEVEL_FILE_BYTE_LIMIT UTF-8 file as UNINSPECTABLE", async () => {
    const filePath = path.join(stagingRoot, "huge.txt");
    const out = fs.createWriteStream(filePath);
    const chunk = Buffer.alloc(1024 * 1024, 0x61);
    const target = TOP_LEVEL_FILE_BYTE_LIMIT + 1024;
    await new Promise((resolve, reject) => {
      let written = 0;
      function writeMore() {
        while (written < target) {
          const ok = out.write(chunk);
          written += chunk.length;
          if (!ok) {
            out.once("drain", writeMore);
            return;
          }
        }
        out.end(resolve);
      }
      out.on("error", reject);
      writeMore();
    });
    const result = await classify(filePath, stagingRoot);
    assert.equal(result.type, "UNINSPECTABLE");
  });

  it("catches a sentinel split across the classifier chunk boundary", async () => {
    const splitAt = 4;
    const lead = Buffer.alloc(STREAM_HIGH_WATER_MARK - splitAt, 0x61);
    const contents = Buffer.concat([
      lead,
      Buffer.from(SENTINEL),
      Buffer.from("\n"),
    ]);
    const filePath = writeFile("split-sentinel.txt", contents);
    const result = await classify(filePath, stagingRoot);
    assert.equal(result.type, "TEXT");
    assert.equal(result.matched, true);
  });

  it("catches a ZIP-record signature split across the classifier chunk boundary", async () => {
    const splitAt = 2;
    const lead = Buffer.alloc(STREAM_HIGH_WATER_MARK - splitAt, 0x61);
    const contents = Buffer.concat([
      lead,
      Buffer.from([0x50, 0x4b, 0x03, 0x04]),
      Buffer.from("abc"),
    ]);
    const filePath = writeFile("split-sig.bin", contents);
    const result = await classify(filePath, stagingRoot);
    assert.equal(result.type, "UNINSPECTABLE");
  });
});
