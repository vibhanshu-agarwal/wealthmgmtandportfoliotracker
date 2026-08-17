"use strict";

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const zlib = require("zlib");
const os = require("os");
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

function copyTreeNoSymlinks(src, dest) {
  let stat;
  try {
    stat = fs.lstatSync(src);
  } catch (err) {
    throw new UninspectableError(`COPY_STAT: ${err.message}`);
  }
  if (stat.isSymbolicLink()) {
    throw new UninspectableError("SYMLINK");
  }
  if (stat.isDirectory()) {
    fs.mkdirSync(dest, { recursive: true });
    for (const name of fs.readdirSync(src)) {
      copyTreeNoSymlinks(path.join(src, name), path.join(dest, name));
    }
    return;
  }
  if (stat.isFile()) {
    fs.copyFileSync(src, dest);
    return;
  }
  throw new UninspectableError("UNSUPPORTED_FILE_TYPE");
}

function copySourceToStaging(sourceDir, stagingDir) {
  fs.mkdirSync(stagingDir, { recursive: true });
  if (!fs.existsSync(sourceDir)) {
    return;
  }
  copyTreeNoSymlinks(sourceDir, stagingDir);
}

function defaultSentinelVariants() {
  return buildSentinelVariants(KNOWN_NON_SECRET_LITERALS);
}

function isValidTraceSegment(segment) {
  if (segment === "" || segment === "." || segment === "..") return false;
  if (segment.includes("\\")) return false;
  for (let i = 0; i < segment.length; i += 1) {
    if (segment.charCodeAt(i) <= 0x1f) return false;
  }
  return true;
}

function toCanonicalTracePath(filePath, stagingDirRoot) {
  const rel = path.relative(stagingDirRoot, filePath);
  if (rel === "" || path.isAbsolute(rel) || rel.startsWith("..")) return null;
  const normalized = rel.split(path.sep).join("/");
  const segments = normalized.split("/");
  for (const segment of segments) {
    if (!isValidTraceSegment(segment)) return null;
  }
  if (segments[0] !== "trace") return null;
  return normalized;
}

function validateManifestTracePath(raw, stagingDirRoot) {
  if (typeof raw !== "string" || raw === "") return null;
  if (raw.includes("\\") || path.isAbsolute(raw)) return null;
  for (let i = 0; i < raw.length; i += 1) {
    if (raw.charCodeAt(i) <= 0x1f) return null;
  }
  const rawSegments = raw.split("/");
  for (const segment of rawSegments) {
    if (!isValidTraceSegment(segment)) return null;
  }
  if (rawSegments[0] !== "trace") return null;
  const resolvedFilePath = path.resolve(stagingDirRoot, raw);
  const canonical = toCanonicalTracePath(resolvedFilePath, stagingDirRoot);
  if (canonical !== raw) return null;
  return raw;
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
  const sentinels = options.sentinels || defaultSentinelVariants();
  const overlapLen = Math.max(0, longestVariantLength(sentinels) - 1);
  const allowlist = options.allowlist || new Map();
  const budget = options.budget || { consumed: 0, limit: GLOBAL_BYTE_BUDGET };
  const canonicalPath = toCanonicalTracePath(
    path.resolve(filePath),
    path.resolve(stagingDirRoot),
  );
  const isAllowlistCandidate = canonicalPath !== null;

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
  const allowlistEntry = allowlist.get(canonicalPath);
  if (allowlistEntry && allowlistEntry.sha256 === digest) {
    return { type: "TEXT", matched, authenticated: true, digest };
  }
  if (!decodeOk) {
    return { type: "UNINSPECTABLE", matched: false, reason: "ALLOWLIST_MISS" };
  }
  return { type: "TEXT", matched, digest };
}

function valueMatchesSentinels(value, sentinels) {
  if (value == null || value === "") return false;
  const buf = Buffer.isBuffer(value) ? value : Buffer.from(String(value), "utf8");
  return bufferContainsAny(buf, sentinels);
}

function isZipSymlink(entry) {
  const madeByUnix = (entry.versionMadeBy >> 8) === 3;
  const fileType = (entry.externalFileAttributes >> 16) & 0xf000;
  return madeByUnix && fileType === 0xa000;
}

async function inspectZipEntry(zipfile, entry, ctx) {
  const { sentinels, depth, budget, seenNames } = ctx;
  const nameKey = Buffer.isBuffer(entry.fileName)
    ? entry.fileName.toString("hex")
    : String(entry.fileName);
  if (seenNames.has(nameKey)) {
    return { outcome: "B", reason: "DUPLICATE_ENTRY" };
  }
  seenNames.add(nameKey);
  if (seenNames.size > MAX_ZIP_ENTRIES) {
    return { outcome: "B", reason: "ENTRY_COUNT" };
  }
  if (isZipSymlink(entry)) {
    return { outcome: "B", reason: "SYMLINK_ENTRY" };
  }

  const metaFields = [
    entry.fileName,
    entry.fileNameRaw,
    entry.fileComment,
    entry.fileCommentRaw,
    entry.extraFieldRaw,
  ];
  if (Array.isArray(entry.extraFields)) {
    for (const field of entry.extraFields) {
      metaFields.push(field.data);
    }
  }
  const metaMatch = metaFields.some((value) =>
    valueMatchesSentinels(value, sentinels),
  );

  if (entry.uncompressedSize > PER_ENTRY_UNCOMPRESSED_LIMIT) {
    return { outcome: "B", reason: "PER_ENTRY_LIMIT" };
  }

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "sanitize-entry-"));
  const tmpFile = path.join(tmpDir, "content");
  try {
    const readStream = await zipfile.openReadStreamPromise(entry);
    const writeStream = fs.createWriteStream(tmpFile);
    let uncompressed = 0;
    let crc = 0;
    let decodeOk = true;
    const decoder = new TextDecoder("utf-8", { fatal: true });
    let overlapTail = Buffer.alloc(0);
    const overlapLen = Math.max(0, longestVariantLength(sentinels) - 1);
    let contentMatch = false;

    await new Promise((resolve, reject) => {
      const failB = (reason) => {
        readStream.destroy();
        const err = new Error(reason);
        err.outcome = "B";
        err.reason = reason;
        reject(err);
      };
      readStream.on("error", reject);
      writeStream.on("error", reject);
      writeStream.on("finish", resolve);
      readStream.on("data", (chunk) => {
        uncompressed += chunk.length;
        budget.consumed += chunk.length;
        if (
          uncompressed > PER_ENTRY_UNCOMPRESSED_LIMIT ||
          budget.consumed > budget.limit
        ) {
          failB("ENTRY_SIZE");
          return;
        }
        crc = zlib.crc32(chunk, crc);
        const searchable = Buffer.concat([overlapTail, chunk]);
        if (bufferContainsAny(searchable, sentinels)) contentMatch = true;
        overlapTail = overlapTailOf(chunk, overlapLen);
        if (decodeOk) {
          try {
            const decoded = decoder.decode(chunk, { stream: true });
            if (hasDisallowedControl(decoded)) throw new ControlByteViolation();
          } catch {
            decodeOk = false;
          }
        }
        writeStream.write(chunk);
      });
      readStream.on("end", () => writeStream.end());
    });

    try {
      const flushed = decoder.decode();
      if (hasDisallowedControl(flushed)) decodeOk = false;
    } catch {
      decodeOk = false;
    }

    if (crc !== entry.crc32) {
      return { outcome: "B", reason: "CRC_MISMATCH" };
    }
    ctx.archiveUncompressed = (ctx.archiveUncompressed || 0) + uncompressed;
    if (ctx.archiveUncompressed > PER_ARCHIVE_UNCOMPRESSED_LIMIT) {
      return { outcome: "B", reason: "PER_ARCHIVE_LIMIT" };
    }
    if (entry.compressedSize > 0) {
      const ratio = uncompressed / entry.compressedSize;
      if (ratio > MAX_COMPRESSION_RATIO) {
        return { outcome: "B", reason: "COMPRESSION_RATIO" };
      }
    }

    if (await tryOpenZip(tmpFile)) {
      return structuredScan(tmpFile, { sentinels, depth: depth + 1, budget });
    }
    if (!decodeOk) {
      return { outcome: "B", reason: "UNINSPECTABLE_ENTRY" };
    }
    if (metaMatch || contentMatch) {
      return { outcome: "A", reason: "MATCH" };
    }
    return { outcome: "clean" };
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
}

async function structuredScan(filePath, options = {}) {
  const sentinels = options.sentinels || defaultSentinelVariants();
  const depth = options.depth || 0;
  const budget = options.budget || { consumed: 0, limit: GLOBAL_BYTE_BUDGET };
  if (depth > MAX_ZIP_NESTING) {
    return { outcome: "B", reason: "NESTING" };
  }

  let zipfile;
  try {
    zipfile = await yauzl.openPromise(filePath, {
      validateEntrySizes: true,
      strictFileNames: true,
      lazyEntries: true,
    });
  } catch (err) {
    return { outcome: "B", reason: `ZIP_OPEN: ${err.message}` };
  }

  try {
    if (valueMatchesSentinels(zipfile.comment, sentinels)) {
      return { outcome: "A", reason: "ARCHIVE_COMMENT" };
    }

    const seenNames = new Set();
    let matched = false;
    const entryCtx = { sentinels, depth, budget, seenNames, archiveUncompressed: 0 };
    await new Promise((resolve, reject) => {
      zipfile.on("error", reject);
      zipfile.on("end", resolve);
      zipfile.on("entry", (entry) => {
        inspectZipEntry(zipfile, entry, entryCtx)
          .then((entryResult) => {
            if (entryResult.outcome === "B") {
              const err = new Error(entryResult.reason);
              err.outcome = "B";
              err.reason = entryResult.reason;
              reject(err);
              return;
            }
            if (entryResult.outcome === "A") matched = true;
            zipfile.readEntry();
          })
          .catch(reject);
      });
      zipfile.readEntry();
    });
    if (matched) return { outcome: "A", reason: "ENTRY_MATCH" };
    return { outcome: "clean" };
  } catch (err) {
    if (err && err.outcome === "B") {
      return { outcome: "B", reason: err.reason || err.message };
    }
    return { outcome: "B", reason: `SCANNER_ERROR: ${err && err.message}` };
  } finally {
    try {
      zipfile.close();
    } catch {
      /* ignore */
    }
  }
}

function resolveSentinelValues(mode, e2ePassword) {
  const password = e2ePassword || "";
  if (mode === "live-secret") {
    if (password.trim() === "") {
      throw new UninspectableError("mode=live-secret requires a non-empty e2e-password");
    }
  } else if (mode === "fallback-only") {
    if (password.trim() !== "") {
      throw new UninspectableError("mode=fallback-only must not receive e2e-password");
    }
  } else {
    throw new UninspectableError(
      `mode must be 'live-secret' or 'fallback-only', got: ${mode}`,
    );
  }
  const values = [
    ...(password ? [password] : []),
    ...KNOWN_NON_SECRET_LITERALS,
  ];
  return buildSentinelVariants(values);
}

function loadAllowlistMap() {
  const manifestPath = path.join(__dirname, "known-playwright-report-assets.json");
  if (!fs.existsSync(manifestPath)) return new Map();
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  const map = new Map();
  if (Array.isArray(manifest.assets)) {
    for (const asset of manifest.assets) {
      map.set(asset.path, { sha256: asset.sha256 });
    }
  }
  return map;
}

function walkFiles(root) {
  const files = [];
  function rec(dir) {
    for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, ent.name);
      if (ent.isSymbolicLink()) {
        throw new UninspectableError("SYMLINK");
      }
      if (ent.isDirectory()) rec(full);
      else if (ent.isFile()) files.push(full);
      else throw new UninspectableError("UNSUPPORTED_FILE_TYPE");
    }
  }
  rec(root);
  return files;
}

function replaceWithPlaceholder(filePath) {
  fs.unlinkSync(filePath);
  fs.writeFileSync(filePath, PLACEHOLDER_CONTENTS);
}

async function rawArchiveScan(filePath, options = {}) {
  const sentinels = options.sentinels || defaultSentinelVariants();
  const budget = options.budget || { consumed: 0, limit: GLOBAL_BYTE_BUDGET };
  const overlapLen = Math.max(0, longestVariantLength(sentinels) - 1);
  let bytesRead = 0;
  let overlapTail = Buffer.alloc(0);
  let matched = false;
  try {
    await new Promise((resolve, reject) => {
      const stream = fs.createReadStream(filePath, {
        highWaterMark: STREAM_HIGH_WATER_MARK,
      });
      stream.on("error", reject);
      stream.on("end", resolve);
      stream.on("data", (chunk) => {
        bytesRead += chunk.length;
        budget.consumed += chunk.length;
        if (
          bytesRead > PER_ARCHIVE_UNCOMPRESSED_LIMIT ||
          budget.consumed > budget.limit
        ) {
          stream.destroy();
          const err = new UninspectableError("RAW_ARCHIVE_BUDGET");
          err.outcome = "B";
          reject(err);
          return;
        }
        const searchable = Buffer.concat([overlapTail, chunk]);
        if (bufferContainsAny(searchable, sentinels)) matched = true;
        overlapTail = overlapTailOf(chunk, overlapLen);
      });
    });
  } catch (err) {
    return { outcome: "B", reason: err.reason || err.message };
  }
  return matched ? { outcome: "A", reason: "RAW_ARCHIVE_MATCH" } : { outcome: "clean" };
}

async function handleFile(filePath, stagingDir, ctx) {
  const { sentinels, allowlist, budget, mutating } = ctx;
  const classified = await classify(filePath, stagingDir, {
    sentinels,
    allowlist,
    budget,
  });
  if (classified.type === "UNINSPECTABLE") {
    return { outcome: "B", reason: classified.reason || "UNINSPECTABLE" };
  }
  if (classified.type === "TEXT") {
    if (classified.matched) {
      if (mutating) replaceWithPlaceholder(filePath);
      return { outcome: "A", reason: "TEXT_MATCH" };
    }
    return { outcome: "clean" };
  }

  const zipResult = await structuredScan(filePath, { sentinels, budget });
  if (zipResult.outcome === "B") return zipResult;
  const rawResult = await rawArchiveScan(filePath, { sentinels, budget });
  if (rawResult.outcome === "B") return rawResult;
  if (zipResult.outcome === "A" || rawResult.outcome === "A") {
    if (mutating) replaceWithPlaceholder(filePath);
    return { outcome: "A", reason: zipResult.reason || rawResult.reason };
  }
  return { outcome: "clean" };
}

function assertSourceInsideWorkspace(sourceDir) {
  const workspace = process.env.GITHUB_WORKSPACE
    ? path.resolve(process.env.GITHUB_WORKSPACE)
    : "";
  if (!workspace) {
    throw new UninspectableError("GITHUB_WORKSPACE is required");
  }
  const resolved = path.resolve(sourceDir);
  const rel = path.relative(workspace, resolved);
  if (rel.startsWith("..") || path.isAbsolute(rel)) {
    throw new UninspectableError("source-dir must canonicalize inside GITHUB_WORKSPACE");
  }
}

function assertStagingIsFreshTempChild(stagingDir) {
  const tempRoot = process.env.RUNNER_TEMP
    ? path.resolve(process.env.RUNNER_TEMP)
    : "";
  if (!tempRoot) {
    throw new UninspectableError("RUNNER_TEMP is required");
  }
  if (fs.lstatSync(tempRoot).isSymbolicLink()) {
    throw new UninspectableError("RUNNER_TEMP must not be a symlink");
  }
  const resolved = path.resolve(stagingDir);
  if (fs.existsSync(resolved)) {
    throw new UninspectableError("staging-dir must be a fresh child of RUNNER_TEMP");
  }
  if (path.resolve(path.dirname(resolved)) !== tempRoot) {
    throw new UninspectableError("staging-dir must be a fresh non-symlink child of RUNNER_TEMP");
  }
}

async function runSanitizeFromEnv() {
  const mode = process.env.SANITIZE_MODE;
  const e2ePassword = process.env.SANITIZE_E2E_PASSWORD || "";
  const sentinels = resolveSentinelValues(mode, e2ePassword);
  const sourceDir = resolveAgainstWorkspace(process.env.SANITIZE_SOURCE_DIR);
  const stagingDir = path.resolve(process.env.SANITIZE_STAGING_DIR);
  assertSourceInsideWorkspace(sourceDir);
  assertStagingIsFreshTempChild(stagingDir);

  const budget = { consumed: 0, limit: GLOBAL_BYTE_BUDGET };
  const allowlist = loadAllowlistMap();
  const sourceMissing = !fs.existsSync(sourceDir);
  copySourceToStaging(sourceDir, stagingDir);

  const ctx = { sentinels, allowlist, budget, mutating: true };
  if (!sourceMissing) {
    for (const filePath of walkFiles(stagingDir)) {
      const result = await handleFile(filePath, stagingDir, ctx);
      if (result.outcome === "B") {
        throw new UninspectableError(result.reason || "OUTCOME_B");
      }
    }
  }

  ctx.mutating = false;
  for (const filePath of walkFiles(stagingDir)) {
    const result = await handleFile(filePath, stagingDir, ctx);
    if (result.outcome !== "clean") {
      throw new UninspectableError(
        `pass 2 ${result.outcome}: ${result.reason || "residual"}`,
      );
    }
  }
}

async function main() {
  try {
    await runSanitizeFromEnv();
  } catch (err) {
    fail(err.message);
  }
}

if (require.main === module) {
  main();
}

module.exports = {
  classify,
  structuredScan,
  rawArchiveScan,
  runSanitizeFromEnv,
  resolveSentinelValues,
  handleFile,
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
  isValidTraceSegment,
  toCanonicalTracePath,
  validateManifestTracePath,
  main,
};
