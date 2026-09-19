/**
 * Wave 10.2 Step B (exit criterion 5b) - ledger events written by the browser child.
 *
 * Every line is validated against the closed vocabulary in ledger-contract.json
 * BEFORE it is appended: an unknown key, an unknown enum value, or a fact of the
 * wrong type throws `LedgerValidationError`, and the caller then records the leg
 * as failed with reason EXCEPTION instead of losing it. Validation errors carry
 * only fixed codes and key names, never a value: the ledger and everything that
 * feeds it must not be able to carry page text, tokens, tickers or bodies.
 *
 * A leg written as `passed` must also carry every fact of its leg (FACTS_INCOMPLETE_FOR_PASS)
 * and satisfy every rule of the contract's `passFacts` table (FACTS_CONTRADICT_PASS): the ledger
 * can never say "passed" next to facts that say otherwise. Its `http` entries must in turn agree with
 * its facts and with the contract's `passHttp` table (HTTP_CONTRADICT_PASS): the ledger can never say
 * "passed" next to request evidence that says otherwise (a PUT that answered 500 beside `putStatus: 200`,
 * no PUT at all beside `putCount: 1`, two PUTs beside `putCount: 1`, or the wrong path).
 * Failed and skipped legs are unconstrained.
 */
import { closeSync, fsyncSync, openSync, writeSync } from "node:fs";
import { MAX_INTEGER_FACT, passHttpViolation, passRuleHolds } from "./contract";
import type { FailureReason, LedgerContract, LegId, LegStatus, Reason } from "./contract";
import { hasOwn, hasExactKeys, isRecord } from "./guards";

export const LEDGER_FILE_NAME = "ledger.jsonl";

export interface HttpEntry {
  readonly method: string;
  readonly path: string;
  /** 0 when the request failed before a response existed. */
  readonly status: number;
}

export type FactValue = boolean | number | string | null;
export type LegFacts = Readonly<Record<string, FactValue>>;

export interface LegEventInput {
  readonly leg: LegId;
  readonly status: LegStatus;
  readonly reason: Reason;
  readonly http: readonly HttpEntry[];
  readonly facts: LegFacts;
}

export class LedgerValidationError extends Error {
  readonly code: string;

  constructor(code: string, detail?: string) {
    super(detail === undefined ? code : `${code}: ${detail}`);
    this.name = "LedgerValidationError";
    this.code = code;
  }
}

/** A programming error (the same leg reported twice), deliberately not a validation error. */
export class LedgerSequenceError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "LedgerSequenceError";
  }
}

const LEG_EVENT_KEYS = ["leg", "status", "reason", "http", "facts"] as const;
const HTTP_ENTRY_KEYS = ["method", "path", "status"] as const;
/** The orchestrator rejects a leg line with more http entries than this, so never write one. */
export const MAX_HTTP_ENTRIES = 200;
/** ...and integer facts above this bound (defined beside the contract, which bounds its pass literals by it too). */
export { MAX_INTEGER_FACT };
const ISO_MILLIS_Z = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;

export type StatusFailureReason = Extract<
  FailureReason,
  "REQUEST_FAILED" | "RATE_LIMITED" | "CONFLICT" | "HTTP_STATUS_NOT_200"
>;

/**
 * Maps an observed HTTP status to the closed failure vocabulary. 409, 429, 503 and
 * 504 are failures with the matching code; 503/504 have no dedicated code and fall
 * under HTTP_STATUS_NOT_200. A missing or zero status means no response arrived
 * (REQUEST_FAILED). A 200 is not a failure: a caller that still needs a failure
 * reason for it has some other check failing, which is ASSERTION_FAILED.
 */
export function failureReasonForStatus(status: number | null): StatusFailureReason | "ASSERTION_FAILED" {
  if (status === null || status === 0) return "REQUEST_FAILED";
  if (status === 200) return "ASSERTION_FAILED";
  if (status === 429) return "RATE_LIMITED";
  if (status === 409) return "CONFLICT";
  return "HTTP_STATUS_NOT_200";
}

function isValidHttpStatus(value: unknown): boolean {
  return typeof value === "number" && Number.isInteger(value) && (value === 0 || (value >= 100 && value <= 599));
}

function validateHttpEntries(contract: LedgerContract, value: unknown): void {
  if (!Array.isArray(value)) throw new LedgerValidationError("HTTP_NOT_ARRAY");
  if (value.length > MAX_HTTP_ENTRIES) throw new LedgerValidationError("HTTP_TOO_MANY_ENTRIES");
  for (const entry of value) {
    if (!isRecord(entry) || !hasExactKeys(entry, HTTP_ENTRY_KEYS)) {
      throw new LedgerValidationError("HTTP_ENTRY_SHAPE");
    }
    if (typeof entry.method !== "string" || !contract.methods.includes(entry.method)) {
      throw new LedgerValidationError("HTTP_UNKNOWN_METHOD");
    }
    // Membership in the closed path list is what guarantees no query string survives.
    if (typeof entry.path !== "string" || !contract.paths.includes(entry.path)) {
      throw new LedgerValidationError("HTTP_UNKNOWN_PATH");
    }
    if (!isValidHttpStatus(entry.status)) throw new LedgerValidationError("HTTP_BAD_STATUS");
  }
}

function factMatches(contract: LedgerContract, type: string, value: unknown): boolean {
  switch (type) {
    case "boolean":
      return typeof value === "boolean";
    case "boolean|null":
      return typeof value === "boolean" || value === null;
    case "integer":
      return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && value <= MAX_INTEGER_FACT;
    case "freshnessState":
      return typeof value === "string" && contract.freshnessStates.includes(value);
    case "mutationSkippedReason":
      return typeof value === "string" && contract.mutationSkippedReasons.includes(value);
    default:
      return false;
  }
}

function validateFacts(contract: LedgerContract, leg: string, status: string, value: unknown): void {
  if (!isRecord(value)) throw new LedgerValidationError("FACTS_NOT_OBJECT");
  const allowed = contract.legFacts[leg];
  if (allowed === undefined) throw new LedgerValidationError("FACTS_UNKNOWN_LEG");
  for (const [key, fact] of Object.entries(value)) {
    if (!hasOwn(allowed, key)) throw new LedgerValidationError("FACTS_UNKNOWN_KEY", key);
    if (!factMatches(contract, allowed[key], fact)) {
      throw new LedgerValidationError("FACTS_BAD_VALUE", key);
    }
  }
  if (status === "passed") {
    for (const key of Object.keys(allowed)) {
      if (!hasOwn(value, key)) throw new LedgerValidationError("FACTS_INCOMPLETE_FOR_PASS", key);
    }
    // A leg without a pass table is never believed (the contract loader also refuses that contract).
    if (!hasOwn(contract.passFacts, leg)) throw new LedgerValidationError("FACTS_CONTRADICT_PASS");
    for (const [key, rule] of Object.entries(contract.passFacts[leg])) {
      if (!passRuleHolds(rule, value[key], value)) throw new LedgerValidationError("FACTS_CONTRADICT_PASS", key);
    }
  }
}

/**
 * A passed leg's http entries must satisfy its `passHttp` rule and agree with its own count and status
 * facts. Runs after `validateHttpEntries` and `validateFacts`, so both arguments are already well formed.
 * The error carries a fixed label and a fact name, never a status or a value.
 */
function validatePassHttp(
  contract: LedgerContract,
  leg: string,
  http: readonly HttpEntry[],
  facts: Readonly<Record<string, unknown>>,
): void {
  // A leg without an http table is never believed (the contract loader also refuses that contract).
  if (!hasOwn(contract.passHttp, leg)) throw new LedgerValidationError("HTTP_CONTRADICT_PASS");
  const violation = passHttpViolation(contract.passHttp[leg], http, facts);
  if (violation !== null) throw new LedgerValidationError("HTTP_CONTRADICT_PASS", violation);
}

function validateStatusReason(status: string, reason: string): void {
  const skipped = reason.startsWith("SKIPPED_");
  const consistent =
    (status === "passed" && reason === "OK") ||
    (status === "skipped" && skipped) ||
    (status === "failed" && reason !== "OK" && !skipped);
  if (!consistent) throw new LedgerValidationError("STATUS_REASON_MISMATCH");
}

/** Validates one complete spec-written ledger line (an object, before serialization). */
export function validateSpecLedgerLine(contract: LedgerContract, line: unknown): void {
  if (!isRecord(line)) throw new LedgerValidationError("LINE_NOT_OBJECT");

  const event = line.event;
  if (typeof event !== "string" || !contract.specEvents.includes(event)) {
    throw new LedgerValidationError("UNKNOWN_EVENT");
  }

  const allowed = new Set<string>([...contract.commonFields, ...(event === "leg" ? LEG_EVENT_KEYS : [])]);
  for (const key of Object.keys(line)) {
    if (!allowed.has(key)) throw new LedgerValidationError("UNKNOWN_KEY", key);
  }
  for (const key of allowed) {
    if (!hasOwn(line, key)) throw new LedgerValidationError("MISSING_KEY", key);
  }

  if (typeof line.seq !== "number" || !Number.isSafeInteger(line.seq) || line.seq < 1) {
    throw new LedgerValidationError("BAD_SEQ");
  }
  const tUtc = line.tUtc;
  if (typeof tUtc !== "string" || !ISO_MILLIS_Z.test(tUtc) || Number.isNaN(Date.parse(tUtc))) {
    throw new LedgerValidationError("BAD_TIMESTAMP");
  }
  if (line.src !== "spec" || !contract.sources.includes("spec")) {
    throw new LedgerValidationError("BAD_SOURCE");
  }

  if (event !== "leg") return;

  const { leg, status, reason } = line;
  if (typeof leg !== "string" || !contract.legs.includes(leg)) throw new LedgerValidationError("UNKNOWN_LEG");
  if (typeof status !== "string" || !contract.statuses.includes(status)) {
    throw new LedgerValidationError("UNKNOWN_STATUS");
  }
  if (typeof reason !== "string" || !contract.reasons.includes(reason)) {
    throw new LedgerValidationError("UNKNOWN_REASON");
  }
  validateStatusReason(status, reason);
  validateHttpEntries(contract, line.http);
  validateFacts(contract, leg, status, line.facts);
  // validateHttpEntries and validateFacts have proven both are well formed (an array of entries, an object).
  if (status === "passed") validatePassHttp(contract, leg, line.http as HttpEntry[], line.facts as Record<string, unknown>);
}

/** One place that appends a line to durable storage. Synchronous so `armed` is on disk before the click. */
export interface LedgerSink {
  append(line: string): void;
}

export interface FsLedgerApi {
  openSync(path: string, flags: string): number;
  writeSync(fd: number, data: string): number;
  fsyncSync(fd: number): void;
  closeSync(fd: number): void;
}

const nodeFsLedgerApi: FsLedgerApi = {
  openSync: (path, flags) => openSync(path, flags),
  writeSync: (fd, data) => writeSync(fd, data),
  fsyncSync: (fd) => fsyncSync(fd),
  closeSync: (fd) => closeSync(fd),
};

/** Append-only file sink: open for append, write, fsync, close, per line. */
export function createFsLedgerSink(filePath: string, fsApi: FsLedgerApi = nodeFsLedgerApi): LedgerSink {
  return {
    append(line: string): void {
      const fd = fsApi.openSync(filePath, "a");
      try {
        fsApi.writeSync(fd, line);
        fsApi.fsyncSync(fd);
      } finally {
        fsApi.closeSync(fd);
      }
    },
  };
}

export interface LedgerWriterOptions {
  readonly contract: LedgerContract;
  readonly sink: LedgerSink;
  readonly now?: () => Date;
}

export interface LedgerWriterLike {
  writeLeg(input: LegEventInput): void;
  writeArmed(): void;
}

/** Builds, validates and appends spec events. `seq` starts at 1 and only advances on success. */
export class LedgerWriter implements LedgerWriterLike {
  private readonly contract: LedgerContract;
  private readonly sink: LedgerSink;
  private readonly now: () => Date;
  private nextSeq = 1;
  private armed = false;
  private readonly legsWritten = new Set<string>();

  constructor(options: LedgerWriterOptions) {
    this.contract = options.contract;
    this.sink = options.sink;
    this.now = options.now ?? (() => new Date());
  }

  hasWrittenLeg(leg: LegId): boolean {
    return this.legsWritten.has(leg);
  }

  writeLeg(input: LegEventInput): void {
    if (this.legsWritten.has(input.leg)) {
      throw new LedgerSequenceError(`leg ${input.leg} already has a final event`);
    }
    const line = {
      seq: this.nextSeq,
      tUtc: this.now().toISOString(),
      src: "spec",
      event: "leg",
      leg: input.leg,
      status: input.status,
      reason: input.reason,
      http: input.http.map((entry) => ({ method: entry.method, path: entry.path, status: entry.status })),
      facts: { ...input.facts },
    };
    validateSpecLedgerLine(this.contract, line);
    this.sink.append(`${JSON.stringify(line)}\n`);
    this.nextSeq += 1;
    this.legsWritten.add(input.leg);
  }

  writeArmed(): void {
    if (this.armed) throw new LedgerSequenceError("armed was already written");
    const line = { seq: this.nextSeq, tUtc: this.now().toISOString(), src: "spec", event: "armed" };
    validateSpecLedgerLine(this.contract, line);
    this.sink.append(`${JSON.stringify(line)}\n`);
    this.nextSeq += 1;
    this.armed = true;
  }
}
