"use strict";

const { describe, it, before, after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("node:child_process");

const actionDir = path.resolve(__dirname, "..");
const instrumentPath = path.join(__dirname, "instrument-fs.js");
const FIFTY_MIB = 50 * 1024 * 1024;
const FIFTY_ONE_MIB = 51 * 1024 * 1024;
const FILE_SIZE = 500 * 1024 * 1024;
const CHUNK = 1024 * 1024;
const RSS_CEILING_BYTES = 150 * 1024 * 1024;

function writeBigFile(filePath, size) {
  return new Promise((resolve, reject) => {
    const out = fs.createWriteStream(filePath);
    let written = 0;
    const buf = Buffer.alloc(CHUNK, 0x61);
    function writeMore() {
      while (written < size) {
        const remaining = size - written;
        const slice = remaining >= CHUNK ? buf : buf.subarray(0, remaining);
        const ok = out.write(slice);
        written += slice.length;
        if (!ok) {
          out.once("drain", writeMore);
          return;
        }
      }
      out.end();
    }
    out.on("error", reject);
    out.on("finish", resolve);
    writeMore();
  });
}

function parseMaxRssKb(stderr) {
  const match = /Maximum resident set size \(kbytes\): (\d+)/.exec(stderr);
  if (!match) {
    throw new Error(`missing peak RSS line in stderr:\n${stderr}`);
  }
  return Number(match[1]);
}

function runInstrumentedSanitize({
  workspace,
  runnerTemp,
  sourceDir,
  stagingDir,
  stagedTargetPath,
  instrumentOutputPath,
}) {
  return new Promise((resolve, reject) => {
    const child = spawn(
      "/usr/bin/time",
      ["-v", process.execPath, "--require", instrumentPath, "sanitize.js"],
      {
        cwd: actionDir,
        env: {
          ...process.env,
          SANITIZE_SOURCE_DIR: sourceDir,
          SANITIZE_STAGING_DIR: stagingDir,
          SANITIZE_MODE: "fallback-only",
          GITHUB_WORKSPACE: workspace,
          RUNNER_TEMP: runnerTemp,
          INSTRUMENT_FS_OUTPUT: instrumentOutputPath,
          INSTRUMENT_FS_TARGET_PATH: stagedTargetPath,
        },
        stdio: ["ignore", "pipe", "pipe"],
      },
    );
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString("utf8");
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString("utf8");
    });
    child.on("error", reject);
    child.on("exit", (exitCode) => {
      let summary = {
        createReadStreamCalled: false,
        wholeFileApiCalled: false,
        totalStreamedBytes: 0,
      };
      if (fs.existsSync(instrumentOutputPath)) {
        summary = JSON.parse(fs.readFileSync(instrumentOutputPath, "utf8"));
      }
      resolve({
        exitCode,
        stdout,
        stderr,
        maxRssKb: parseMaxRssKb(stderr),
        createReadStreamCalled: summary.createReadStreamCalled,
        totalStreamedBytes: summary.totalStreamedBytes,
        wholeFileApiCalled: summary.wholeFileApiCalled,
      });
    });
  });
}

describe("hostile-archive resource bound", { timeout: 180000 }, () => {
  let workspace;
  let runnerTemp;
  let sourceDir;
  let stagingDir;
  let stagedTargetPath;
  let instrumentOutputPath;

  before(async () => {
    workspace = fs.mkdtempSync(path.join(os.tmpdir(), "ws-res-"));
    runnerTemp = fs.mkdtempSync(path.join(os.tmpdir(), "rt-res-"));
    sourceDir = path.join(workspace, "src");
    fs.mkdirSync(sourceDir);
    stagingDir = path.join(runnerTemp, "stage");
    stagedTargetPath = path.resolve(stagingDir, "big-toplevel.bin");
    instrumentOutputPath = path.join(runnerTemp, "instrument.json");
    await writeBigFile(path.join(sourceDir, "big-toplevel.bin"), FILE_SIZE);
  });

  after(() => {
    fs.rmSync(workspace, { recursive: true, force: true });
    fs.rmSync(runnerTemp, { recursive: true, force: true });
  });

  it("streams the staged over-limit file and stays under the RSS ceiling", async () => {
    const result = await runInstrumentedSanitize({
      workspace,
      runnerTemp,
      sourceDir,
      stagingDir,
      stagedTargetPath,
      instrumentOutputPath,
    });
    const combined = `${result.stdout}\n${result.stderr}`;
    assert.equal(result.createReadStreamCalled, true);
    assert.equal(result.wholeFileApiCalled, false);
    assert.ok(
      result.totalStreamedBytes > FIFTY_MIB,
      `streamed ${result.totalStreamedBytes} bytes, expected > ${FIFTY_MIB}`,
    );
    assert.ok(
      result.totalStreamedBytes <= FIFTY_ONE_MIB,
      `streamed ${result.totalStreamedBytes} bytes, expected <= ${FIFTY_ONE_MIB}`,
    );
    assert.notEqual(result.exitCode, 0);
    assert.match(combined, /TOP_LEVEL_FILE_BYTE_LIMIT/);
    assert.doesNotMatch(result.stderr, /JavaScript heap out of memory/);
    assert.ok(
      result.maxRssKb * 1024 < RSS_CEILING_BYTES,
      `peak RSS ${result.maxRssKb} kB exceeds 150 MiB`,
    );
  });
});
