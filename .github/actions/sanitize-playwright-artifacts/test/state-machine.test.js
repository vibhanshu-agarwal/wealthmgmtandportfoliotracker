"use strict";

const { describe, it } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawnSync } = require("node:child_process");
const { createZip } = require("./helpers/zip");
const { PLACEHOLDER_CONTENTS } = require("../sanitize.js");

const actionDir = path.resolve(__dirname, "..");
const SENTINEL = "TestPassword123!";

function makeDirs() {
  const workspace = fs.mkdtempSync(path.join(os.tmpdir(), "ws-"));
  const runnerTemp = fs.mkdtempSync(path.join(os.tmpdir(), "rt-"));
  const sourceDir = path.join(workspace, "src");
  fs.mkdirSync(sourceDir);
  const stagingDir = path.join(runnerTemp, "stage");
  return { workspace, runnerTemp, sourceDir, stagingDir };
}

function runSanitize({ workspace, runnerTemp, sourceDir, stagingDir, extraEnv = {} }) {
  return spawnSync(process.execPath, ["sanitize.js"], {
    cwd: actionDir,
    env: {
      ...process.env,
      SANITIZE_SOURCE_DIR: sourceDir,
      SANITIZE_STAGING_DIR: stagingDir,
      SANITIZE_MODE: "fallback-only",
      GITHUB_WORKSPACE: workspace,
      RUNNER_TEMP: runnerTemp,
      ...extraEnv,
    },
    encoding: "utf8",
  });
}

describe("state machine", () => {
  it("replaces a plain-text match (Outcome A)", () => {
    const dirs = makeDirs();
    fs.writeFileSync(path.join(dirs.sourceDir, "leak.txt"), `hello ${SENTINEL}\n`);
    const result = runSanitize(dirs);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(
      fs.readFileSync(path.join(dirs.stagingDir, "leak.txt"), "utf8"),
      PLACEHOLDER_CONTENTS,
    );
  });

  it("replaces a ZIP that contains a nested match (Outcome A)", () => {
    const dirs = makeDirs();
    fs.writeFileSync(
      path.join(dirs.sourceDir, "trace.zip"),
      createZip([{ name: "ok.txt", data: `x ${SENTINEL}` }]),
    );
    const result = runSanitize(dirs);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(
      fs.readFileSync(path.join(dirs.stagingDir, "trace.zip"), "utf8"),
      PLACEHOLDER_CONTENTS,
    );
  });

  it("is Outcome B for a match only inside an image inside a ZIP", () => {
    const dirs = makeDirs();
    const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xd9]);
    fs.writeFileSync(
      path.join(dirs.sourceDir, "trace.zip"),
      createZip([
        { name: "ok.txt", data: "clean\n" },
        { name: "shot.bin", data: Buffer.concat([jpeg, Buffer.from(SENTINEL)]) },
      ]),
    );
    const result = runSanitize(dirs);
    assert.notEqual(result.status, 0);
  });

  it("leaves a clean artifact unchanged", () => {
    const dirs = makeDirs();
    fs.writeFileSync(path.join(dirs.sourceDir, "ok.txt"), "nothing secret\n");
    const result = runSanitize(dirs);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(
      fs.readFileSync(path.join(dirs.stagingDir, "ok.txt"), "utf8"),
      "nothing secret\n",
    );
  });

  it("fails closed when pass 2 sees a residual match it cannot repair", async () => {
    const dirs = makeDirs();
    const dirty = path.join(dirs.stagingDir, "residual.txt");
    fs.mkdirSync(dirs.stagingDir);
    fs.writeFileSync(dirty, `still ${SENTINEL}\n`);
    const { handleFile, buildSentinelVariants, KNOWN_NON_SECRET_LITERALS } = require("../sanitize.js");
    const result = await handleFile(dirty, dirs.stagingDir, {
      sentinels: buildSentinelVariants(KNOWN_NON_SECRET_LITERALS),
      allowlist: new Map(),
      budget: { consumed: 0, limit: 1024 * 1024 * 1024 },
      mutating: false,
    });
    assert.equal(result.outcome, "A");
    assert.equal(fs.readFileSync(dirty, "utf8"), `still ${SENTINEL}\n`);
  });

  it("fails closed on uninspectable content (scanner fail-closed)", () => {
    const dirs = makeDirs();
    fs.writeFileSync(
      path.join(dirs.sourceDir, "bad.bin"),
      Buffer.from([0x00, 0xff, 0xfe, 0xfd]),
    );
    const result = runSanitize(dirs);
    assert.notEqual(result.status, 0);
  });

  it("creates an empty successful staging-dir when source-dir is missing", () => {
    const dirs = makeDirs();
    fs.rmSync(dirs.sourceDir, { recursive: true, force: true });
    const result = runSanitize(dirs);
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(fs.readdirSync(dirs.stagingDir), []);
  });

  it("is Outcome B when a symlink is encountered during the source copy", () => {
    const dirs = makeDirs();
    fs.writeFileSync(path.join(dirs.sourceDir, "ok.txt"), "hello\n");
    try {
      fs.symlinkSync("ok.txt", path.join(dirs.sourceDir, "link"));
    } catch (err) {
      assert.ok(err instanceof Error);
      return;
    }
    const result = runSanitize(dirs);
    assert.notEqual(result.status, 0);
  });
});
