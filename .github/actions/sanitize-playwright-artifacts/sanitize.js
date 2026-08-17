"use strict";

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const yauzl = require("yauzl");

const TOP_LEVEL_FILE_BYTE_LIMIT = 50 * 1024 * 1024;
const PER_ENTRY_UNCOMPRESSED_LIMIT = 50 * 1024 * 1024;
const PER_ARCHIVE_UNCOMPRESSED_LIMIT = 200 * 1024 * 1024;
const GLOBAL_BYTE_BUDGET = 1024 * 1024 * 1024;
const STREAM_HIGH_WATER_MARK = 1024 * 1024;
const MAX_ZIP_NESTING = 5;
const MAX_ZIP_ENTRIES = 5000;
const MAX_COMPRESSION_RATIO = 100;

const KNOWN_NON_SECRET_LITERALS = [
  "local-dev-password-2026",
  "TestPassword123!",
  "e2e-test-password-2026",
];

const PLACEHOLDER_CONTENTS =
  "This Playwright artifact was withheld because it contained a configured secret sentinel.\n";

const ZIP_RECORD_SIGNATURES = [
  Buffer.from([0x50, 0x4b, 0x03, 0x04]),
  Buffer.from([0x50, 0x4b, 0x01, 0x02]),
  Buffer.from([0x50, 0x4b, 0x05, 0x06]),
  Buffer.from([0x50, 0x4b, 0x06, 0x06]),
  Buffer.from([0x50, 0x4b, 0x06, 0x07]),
  Buffer.from([0x50, 0x4b, 0x07, 0x08]),
];

class ControlByteViolation extends Error {
  constructor() {
    super("disallowed C0/DEL control byte");
    this.name = "ControlByteViolation";
  }
}

class UninspectableError extends Error {
  constructor(message) {
    super(message);
    this.name = "UninspectableError";
  }
}

function fail(msg) {
  console.error(`::error::${msg}`);
  process.exit(1);
}

function htmlEntityEncode(value) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function buildSentinelVariants(values) {
  const unique = new Set();
  for (const value of values) {
    if (!value) continue;
    unique.add(value);
    unique.add(JSON.stringify(value).slice(1, -1));
    unique.add(encodeURIComponent(value));
    unique.add(htmlEntityEncode(value));
  }
  return [...unique].map((v) => Buffer.from(v, "utf8"));
}

function longestVariantLength(variants) {
  return variants.reduce((max, buf) => Math.max(max, buf.length), 0);
}

function bufferContainsAny(haystack, needles) {
  for (const needle of needles) {
    if (needle.length > 0 && haystack.indexOf(needle) !== -1) {
      return true;
    }
  }
  return false;
}

function hasDisallowedControl(text) {
  for (let i = 0; i < text.length; i += 1) {
    const code = text.charCodeAt(i);
    if (code === 0x09 || code === 0x0a || code === 0x0d) continue;
    if (code <= 0x1f || code === 0x7f) return true;
  }
  return false;
}

function resolveAgainstWorkspace(p) {
  if (!p) {
    return p;
  }
  if (path.isAbsolute(p)) {
    return path.resolve(p);
  }
  const workspace = process.env.GITHUB_WORKSPACE || process.cwd();
  return path.resolve(workspace, p);
}

function copySourceToStaging(sourceDir, stagingDir) {
  fs.mkdirSync(stagingDir, { recursive: true });
  if (!fs.existsSync(sourceDir)) {
    return;
  }
  fs.cpSync(sourceDir, stagingDir, { recursive: true });
}

function defaultSentinelVariants() {
  return buildSentinelVariants(KNOWN_NON_SECRET_LITERALS);
}

function overlapTailOf(chunk, overlapLen) {
  if (overlapLen <= 0) return Buffer.alloc(0);
  if (chunk.length <= overlapLen) return Buffer.from(chunk);
  return Buffer.from(chunk.subarray(chunk.length - overlapLen));
}

async function tryOpenZip(filePath) {
  try {
    const zipfile = await yauzl.openPromise(filePath, {
      validateEntrySizes: true,
      strictFileNames: true,
      lazyEntries: true,
    });
    zipfile.close();
    return true;
  } catch {
    return false;
  }
}

/**
 * Content classifier: yauzl-first, then a single bounded streaming fallback.
 * Allowlist candidacy is wired in A5; A3 uses the non-candidate path only.
 */
async function classify(filePath, stagingDirRoot, options = {}) {
  void stagingDirRoot;
  const sentinels = options.sentinels || defaultSentinelVariants();
  const overlapLen = Math.max(0, longestVariantLength(sentinels) - 1);
  const allowlist = options.allowlist || new Map();
  const budget = options.budget || { consumed: 0, limit: GLOBAL_BYTE_BUDGET };
  const isAllowlistCandidate =
    typeof options.isAllowlistCandidate === "function"
      ? options.isAllowlistCandidate(filePath, stagingDirRoot)
      : false;

  if (await tryOpenZip(filePath)) {
    return { type: "ZIP", matched: false };
  }

  const hasher = isAllowlistCandidate ? crypto.createHash("sha256") : null;
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let bytesRead = 0;
  let overlapTail = Buffer.alloc(0);
  let matched = false;
  let decodeOk = true;

  try {
    await new Promise((resolve, reject) => {
      const stream = fs.createReadStream(filePath, {
        highWaterMark: STREAM_HIGH_WATER_MARK,
      });
      stream.on("error", reject);
      stream.on("end", resolve);
      stream.on("data", (chunk) => {
        try {
          bytesRead += chunk.length;
          budget.consumed += chunk.length;
          if (
            bytesRead > TOP_LEVEL_FILE_BYTE_LIMIT ||
            budget.consumed > budget.limit
          ) {
            stream.destroy();
            reject(new UninspectableError("TOP_LEVEL_FILE_BYTE_LIMIT"));
            return;
          }
          if (hasher) hasher.update(chunk);

          const searchable = Buffer.concat([overlapTail, chunk]);
          if (bufferContainsAny(searchable, ZIP_RECORD_SIGNATURES)) {
            stream.destroy();
            reject(new UninspectableError("ZIP_RECORD_SIGNATURE"));
            return;
          }

          try {
            const decodedChunk = decoder.decode(chunk, { stream: true });
            if (hasDisallowedControl(decodedChunk)) {
              throw new ControlByteViolation();
            }
          } catch (err) {
            if (isAllowlistCandidate) {
              decodeOk = false;
            } else {
              stream.destroy();
              reject(new UninspectableError("DECODE_OR_CONTROL"));
              return;
            }
          }

          if (bufferContainsAny(searchable, sentinels)) {
            matched = true;
          }
          overlapTail = overlapTailOf(chunk, overlapLen);
        } catch (err) {
          stream.destroy();
          reject(err);
        }
      });
    });
  } catch (err) {
    if (err instanceof UninspectableError) {
      return { type: "UNINSPECTABLE", matched: false, reason: err.message };
    }
    return { type: "UNINSPECTABLE", matched: false, reason: "SCANNER_ERROR" };
  }

  try {
    const flushedTail = decoder.decode();
    if (hasDisallowedControl(flushedTail)) {
      throw new ControlByteViolation();
    }
  } catch {
    decodeOk = false;
  }

  if (!isAllowlistCandidate) {
    if (!decodeOk) {
      return { type: "UNINSPECTABLE", matched: false, reason: "DECODE_FLUSH" };
    }
    return { type: "TEXT", matched };
  }

  const digest = hasher.digest("hex");
  const canonicalPath =
    typeof options.canonicalPath === "string" ? options.canonicalPath : null;
  const allowlistEntry = canonicalPath ? allowlist.get(canonicalPath) : null;
  if (allowlistEntry && allowlistEntry.sha256 === digest) {
    return { type: "TEXT", matched, authenticated: true, digest };
  }
  if (!decodeOk) {
    return { type: "UNINSPECTABLE", matched: false, reason: "ALLOWLIST_MISS" };
  }
  return { type: "TEXT", matched, digest };
}

function main() {
  const sourceDir = resolveAgainstWorkspace(process.env.SANITIZE_SOURCE_DIR);
  const stagingDir = resolveAgainstWorkspace(process.env.SANITIZE_STAGING_DIR);
  void process.env.SANITIZE_MODE;
  void process.env.SANITIZE_E2E_PASSWORD;
  copySourceToStaging(sourceDir, stagingDir);
}

if (require.main === module) {
  main();
}

module.exports = {
  classify,
  TOP_LEVEL_FILE_BYTE_LIMIT,
  PER_ENTRY_UNCOMPRESSED_LIMIT,
  PER_ARCHIVE_UNCOMPRESSED_LIMIT,
  GLOBAL_BYTE_BUDGET,
  STREAM_HIGH_WATER_MARK,
  MAX_ZIP_NESTING,
  MAX_ZIP_ENTRIES,
  MAX_COMPRESSION_RATIO,
  KNOWN_NON_SECRET_LITERALS,
  PLACEHOLDER_CONTENTS,
  ZIP_RECORD_SIGNATURES,
  buildSentinelVariants,
  resolveAgainstWorkspace,
  copySourceToStaging,
  fail,
  UninspectableError,
  ControlByteViolation,
  main,
};
