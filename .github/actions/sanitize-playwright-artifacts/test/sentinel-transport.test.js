"use strict";

const { describe, it } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawnSync } = require("node:child_process");

const actionDir = path.resolve(__dirname, "..");

function makeDirs() {
  const workspace = fs.mkdtempSync(path.join(os.tmpdir(), "ws-"));
  const runnerTemp = fs.mkdtempSync(path.join(os.tmpdir(), "rt-"));
  const sourceDir = path.join(workspace, "src");
  fs.mkdirSync(sourceDir);
  fs.writeFileSync(path.join(sourceDir, "ok.txt"), "hello\n");
  const stagingDir = path.join(runnerTemp, "stage");
  return { workspace, runnerTemp, sourceDir, stagingDir };
}

function run({ mode, password, dirs }) {
  const env = {
    ...process.env,
    SANITIZE_SOURCE_DIR: dirs.sourceDir,
    SANITIZE_STAGING_DIR: dirs.stagingDir,
    SANITIZE_MODE: mode,
    GITHUB_WORKSPACE: dirs.workspace,
    RUNNER_TEMP: dirs.runnerTemp,
  };
  if (password !== undefined) env.SANITIZE_E2E_PASSWORD = password;
  else delete env.SANITIZE_E2E_PASSWORD;
  return spawnSync(process.execPath, ["sanitize.js"], {
    cwd: actionDir,
    env,
    encoding: "utf8",
  });
}

describe("sentinel transport", () => {
  it("accepts mode=live-secret with a non-empty e2e-password", () => {
    const result = run({
      mode: "live-secret",
      password: "dummy-not-real-credential",
      dirs: makeDirs(),
    });
    assert.equal(result.status, 0, result.stderr);
  });

  it("is Outcome B for mode=live-secret with an empty e2e-password", () => {
    const result = run({ mode: "live-secret", password: "", dirs: makeDirs() });
    assert.notEqual(result.status, 0);
  });

  it("accepts mode=fallback-only with e2e-password unset", () => {
    const result = run({ mode: "fallback-only", dirs: makeDirs() });
    assert.equal(result.status, 0, result.stderr);
  });

  it("is Outcome B for mode=fallback-only with e2e-password set", () => {
    const result = run({
      mode: "fallback-only",
      password: "should-not-be-set",
      dirs: makeDirs(),
    });
    assert.notEqual(result.status, 0);
  });

  it("is Outcome B for an unrecognized mode", () => {
    const result = run({ mode: "anything", dirs: makeDirs() });
    assert.notEqual(result.status, 0);
  });

  it("matches the URLSearchParams form of TestPassword123!", () => {
    const { buildSentinelVariants, PLACEHOLDER_CONTENTS } = require("../sanitize.js");
    const encoded = new URLSearchParams({ password: "TestPassword123!" }).toString();
    assert.match(encoded, /TestPassword123%21/);
    const variants = buildSentinelVariants(["TestPassword123!"]).map((buf) =>
      buf.toString("utf8"),
    );
    assert.ok(
      variants.includes("TestPassword123%21"),
      `variants=${JSON.stringify(variants)}`,
    );

    const dirs = makeDirs();
    fs.writeFileSync(path.join(dirs.sourceDir, "query.txt"), `${encoded}\n`);
    const result = run({ mode: "fallback-only", dirs });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(
      fs.readFileSync(path.join(dirs.stagingDir, "query.txt"), "utf8"),
      PLACEHOLDER_CONTENTS,
    );
  });
});
