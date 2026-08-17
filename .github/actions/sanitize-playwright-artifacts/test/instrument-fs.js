"use strict";

const fs = require("fs");
const path = require("path");

const targetPath = process.env.INSTRUMENT_FS_TARGET_PATH;
if (!targetPath) {
  throw new Error("INSTRUMENT_FS_TARGET_PATH is required");
}
const resolvedTarget = path.resolve(targetPath);
const outputPath = process.env.INSTRUMENT_FS_OUTPUT;

const summary = {
  createReadStreamCalled: false,
  wholeFileApiCalled: false,
  totalStreamedBytes: 0,
};

function isTarget(p) {
  if (p == null) return false;
  try {
    return path.resolve(String(p)) === resolvedTarget;
  } catch {
    return false;
  }
}

const originalCreateReadStream = fs.createReadStream;
fs.createReadStream = function instrumentedCreateReadStream(p, options) {
  const stream = originalCreateReadStream.call(fs, p, options);
  if (isTarget(p)) {
    summary.createReadStreamCalled = true;
    stream.on("data", (chunk) => {
      summary.totalStreamedBytes += chunk.length;
    });
  }
  return stream;
};

function denyWholeFile(p) {
  if (isTarget(p)) {
    summary.wholeFileApiCalled = true;
    throw new Error("whole-file read of INSTRUMENT_FS_TARGET_PATH is forbidden");
  }
}

const originalReadFileSync = fs.readFileSync;
fs.readFileSync = function instrumentedReadFileSync(p, options) {
  denyWholeFile(p);
  return originalReadFileSync.call(fs, p, options);
};

const originalReadFile = fs.readFile;
fs.readFile = function instrumentedReadFile(p, options, cb) {
  if (typeof options === "function") {
    cb = options;
    options = undefined;
  }
  try {
    denyWholeFile(p);
  } catch (err) {
    if (typeof cb === "function") {
      process.nextTick(() => cb(err));
      return;
    }
    throw err;
  }
  return originalReadFile.call(fs, p, options, cb);
};

if (fs.promises && fs.promises.readFile) {
  const originalPromisesReadFile = fs.promises.readFile.bind(fs.promises);
  fs.promises.readFile = async function instrumentedPromisesReadFile(p, options) {
    denyWholeFile(p);
    return originalPromisesReadFile(p, options);
  };
}

process.on("exit", () => {
  if (!outputPath) return;
  fs.writeFileSync(outputPath, JSON.stringify(summary));
});

module.exports = summary;
