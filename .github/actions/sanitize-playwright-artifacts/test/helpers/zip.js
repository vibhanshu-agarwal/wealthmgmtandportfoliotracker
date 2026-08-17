"use strict";

const zlib = require("zlib");

function u16(n) {
  const b = Buffer.alloc(2);
  b.writeUInt16LE(n);
  return b;
}

function u32(n) {
  const b = Buffer.alloc(4);
  b.writeUInt32LE(n >>> 0);
  return b;
}

/**
 * Build a stored (or optionally deflated) ZIP buffer.
 * Central-directory offsets include any SFX `prefix`.
 */
function createZip(entries, { comment = "", prefix = Buffer.alloc(0) } = {}) {
  const prefixBuf = Buffer.isBuffer(prefix) ? prefix : Buffer.from(prefix);
  const locals = [];
  const centrals = [];
  let relativeOffset = 0;

  for (const entry of entries) {
    const name = Buffer.isBuffer(entry.name) ? entry.name : Buffer.from(entry.name);
    const data = Buffer.isBuffer(entry.data) ? entry.data : Buffer.from(entry.data);
    const extra = entry.extra ? Buffer.from(entry.extra) : Buffer.alloc(0);
    const fileComment = entry.comment ? Buffer.from(entry.comment) : Buffer.alloc(0);
    const crc = zlib.crc32(data);
    const compression = entry.deflate ? 8 : 0;
    const payload = entry.deflate ? zlib.deflateRawSync(data) : data;
    const flags = entry.flags || 0;

    const local = Buffer.concat([
      Buffer.from("PK\x03\x04", "binary"),
      u16(20),
      u16(flags),
      u16(compression),
      u16(0),
      u16(0),
      u32(crc),
      u32(payload.length),
      u32(data.length),
      u16(name.length),
      u16(extra.length),
      name,
      extra,
      payload,
    ]);
    locals.push(local);

    const central = Buffer.concat([
      Buffer.from("PK\x01\x02", "binary"),
      u16(20),
      u16(20),
      u16(flags),
      u16(compression),
      u16(0),
      u16(0),
      u32(crc),
      u32(payload.length),
      u32(data.length),
      u16(name.length),
      u16(extra.length),
      u16(fileComment.length),
      u16(0),
      u16(0),
      u32(entry.externalAttr || 0),
      u32(prefixBuf.length + relativeOffset),
      name,
      extra,
      fileComment,
    ]);
    centrals.push(central);
    relativeOffset += local.length;
  }

  const centralBuf = Buffer.concat(centrals);
  const commentBuf = Buffer.from(comment);
  const eocd = Buffer.concat([
    Buffer.from("PK\x05\x06", "binary"),
    u16(0),
    u16(0),
    u16(entries.length),
    u16(entries.length),
    u32(centralBuf.length),
    u32(prefixBuf.length + relativeOffset),
    u16(commentBuf.length),
    commentBuf,
  ]);
  return Buffer.concat([prefixBuf, ...locals, centralBuf, eocd]);
}

function extraField(id, data) {
  const payload = Buffer.isBuffer(data) ? data : Buffer.from(data);
  const header = Buffer.alloc(4);
  header.writeUInt16LE(id, 0);
  header.writeUInt16LE(payload.length, 2);
  return Buffer.concat([header, payload]);
}

function unicodePathExtra(rawName, unicodeName) {
  const raw = Buffer.isBuffer(rawName) ? rawName : Buffer.from(rawName);
  const data = Buffer.concat([
    Buffer.from([1]),
    u32(zlib.crc32(raw)),
    Buffer.from(unicodeName, "utf8"),
  ]);
  return extraField(0x7075, data);
}

module.exports = { createZip, extraField, unicodePathExtra };
