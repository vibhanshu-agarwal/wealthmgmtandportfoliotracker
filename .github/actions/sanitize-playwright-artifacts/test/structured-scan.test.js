"use strict";

const { describe, it, before, after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { createZip, extraField, unicodePathExtra } = require("./helpers/zip");
const { structuredScan } = require("../sanitize.js");

const SENTINEL = "TestPassword123!";
const TEXT_BODY = "clean diagnostic text\n";
const JPEG = Buffer.from([
  0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0xff,
  0xd9,
]);

let tmpRoot;

function writeZip(name, bytes) {
  const full = path.join(tmpRoot, name);
  fs.writeFileSync(full, bytes);
  return full;
}

describe("structuredScan", () => {
  before(() => {
    tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "sanitize-struct-"));
  });

  after(() => {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  });

  it("detects a sentinel present only in ZipFile.comment", async () => {
    const bytes = createZip([{ name: "ok.txt", data: TEXT_BODY }], {
      comment: SENTINEL,
    });
    const result = await structuredScan(writeZip("comment.zip", bytes));
    assert.equal(result.outcome, "A");
  });

  it("detects a sentinel present only in extraFieldRaw", async () => {
    const extra = extraField(0x9901, SENTINEL);
    const bytes = createZip([
      { name: "ok.txt", data: TEXT_BODY, extra },
    ]);
    const result = await structuredScan(writeZip("extra.zip", bytes));
    assert.equal(result.outcome, "A");
  });

  it("detects a sentinel present only in the raw entry name when decoded form differs", async () => {
    const rawName = `${SENTINEL}.txt`;
    const extra = unicodePathExtra(rawName, "safe.txt");
    const bytes = createZip([
      { name: rawName, data: TEXT_BODY, extra },
    ]);
    const result = await structuredScan(writeZip("raw-name.zip", bytes));
    assert.equal(result.outcome, "A");
  });

  it("is Outcome B when a ZIP contains a binary entry that also carries a raw match", async () => {
    const jpegWithSentinel = Buffer.concat([JPEG, Buffer.from(SENTINEL)]);
    const bytes = createZip([
      { name: "ok.txt", data: TEXT_BODY },
      { name: "shot.bin", data: jpegWithSentinel },
    ]);
    const result = await structuredScan(writeZip("binary-match.zip", bytes));
    assert.equal(result.outcome, "B");
  });

  it("is Outcome B when a ZIP contains a binary entry with no detectable match", async () => {
    const bytes = createZip([
      { name: "ok.txt", data: TEXT_BODY },
      { name: "shot.bin", data: JPEG },
    ]);
    const result = await structuredScan(writeZip("binary-clean.zip", bytes));
    assert.equal(result.outcome, "B");
  });

  it("leaves a fully text-or-nested-ZIP archive with no match as-is", async () => {
    const inner = createZip([{ name: "inner.txt", data: "nested ok\n" }]);
    const bytes = createZip([
      { name: "ok.txt", data: TEXT_BODY },
      { name: "inner.zip", data: inner },
    ]);
    const result = await structuredScan(writeZip("nested-clean.zip", bytes));
    assert.equal(result.outcome, "clean");
  });
});
