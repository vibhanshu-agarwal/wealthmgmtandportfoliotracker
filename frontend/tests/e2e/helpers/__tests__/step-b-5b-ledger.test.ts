// @vitest-environment node
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import contractJson from "../../../production-e2e/ledger-contract.json";
import {
  assertContractMatchesCode,
  COMMON_FIELDS,
  CONTRACT_SCHEMA,
  FRESHNESS_STATES,
  LEDGER_METHODS,
  LEDGER_PATHS,
  LEDGER_SOURCES,
  LEG_IDS,
  LEG_STATUSES,
  LedgerContractError,
  loadLedgerContract,
  MAX_INTEGER_FACT as CONTRACT_MAX_INTEGER_FACT,
  MUTATION_SKIPPED_REASONS,
  ORCHESTRATOR_EVENTS,
  parseLedgerContract,
  REASONS,
  SPEC_EVENTS,
  type LedgerContract,
  type LegId,
} from "../../../production-e2e/lib/contract";
import {
  createFsLedgerSink,
  failureReasonForStatus,
  LEDGER_FILE_NAME,
  LedgerSequenceError,
  LedgerValidationError,
  LedgerWriter,
  MAX_HTTP_ENTRIES,
  MAX_INTEGER_FACT,
  validateSpecLedgerLine,
  type FsLedgerApi,
  type LegEventInput,
} from "../../../production-e2e/lib/ledger";

const contract: LedgerContract = loadLedgerContract();
const FIXED_NOW = new Date("2026-09-18T12:34:56.789Z");

function memoryWriter(now: () => Date = () => FIXED_NOW) {
  const lines: string[] = [];
  const writer = new LedgerWriter({ contract, sink: { append: (line) => void lines.push(line) }, now });
  return { writer, lines, parsed: () => lines.map((line) => JSON.parse(line) as Record<string, unknown>) };
}

const passedL4: LegEventInput = {
  leg: "L4",
  status: "passed",
  reason: "OK",
  http: [{ method: "GET", path: "/api/assets", status: 200 }],
  facts: {
    catalogStatus: 200,
    noIfNoneMatch: true,
    etagPresent: true,
    assetsNonEmpty: true,
    rowsRendered: true,
    catalogParity: true,
    activeCount: 159,
  },
};

describe("Step B 5b ledger-contract.json pin", () => {
  it("has exactly the pinned top-level keys and schema id", () => {
    expect(Object.keys(contractJson).sort()).toEqual(
      [
        "commonFields",
        "freshnessStates",
        "legFacts",
        "legs",
        "methods",
        "mutationSkippedReasons",
        "orchestratorEvents",
        "passFactRules",
        "passFacts",
        "passHttp",
        "passHttpRules",
        "paths",
        "reasons",
        "schema",
        "sources",
        "specEvents",
        "statuses",
      ].sort(),
    );
    expect(Object.keys(contractJson)).toHaveLength(17);
    expect(contractJson.schema).toBe("wave10-5b-ledger-contract-v3");
    expect(CONTRACT_SCHEMA).toBe("wave10-5b-ledger-contract-v3");
    expect(CONTRACT_SCHEMA).toBe(contractJson.schema);
  });

  it("rejects the previous schema ids, whose contracts had no passHttp (v2) or passFacts (v1) table", () => {
    const v2 = JSON.parse(JSON.stringify(contractJson)) as Record<string, unknown>;
    v2.schema = "wave10-5b-ledger-contract-v2";
    delete v2.passHttp;
    delete v2.passHttpRules;
    expect(() => loadLedgerContract(v2)).toThrow(LedgerContractError);
    // ...and the schema id alone is enough: v3 keys under the v2 id are refused too.
    const relabelled = JSON.parse(JSON.stringify(contractJson)) as Record<string, unknown>;
    relabelled.schema = "wave10-5b-ledger-contract-v2";
    expect(() => loadLedgerContract(relabelled)).toThrow(/schema id/);

    const v1 = JSON.parse(JSON.stringify(contractJson)) as Record<string, unknown>;
    v1.schema = "wave10-5b-ledger-contract-v1";
    delete v1.passFacts;
    delete v1.passFactRules;
    delete v1.passHttp;
    delete v1.passHttpRules;
    expect(() => loadLedgerContract(v1)).toThrow(LedgerContractError);
  });

  it("rejects a v3 contract that lacks either of the two http keys", () => {
    for (const key of ["passHttp", "passHttpRules"]) {
      const partial = JSON.parse(JSON.stringify(contractJson)) as Record<string, unknown>;
      delete partial[key];
      expect(() => loadLedgerContract(partial), key).toThrow(/top-level keys/);
    }
  });

  it("matches the vocabularies the verifier code was written against", () => {
    expect(contractJson.commonFields).toEqual(["seq", "tUtc", "src", "event"]);
    expect(contractJson.sources).toEqual(["spec", "orchestrator"]);
    expect(contractJson.specEvents).toEqual(["leg", "armed"]);
    expect(contractJson.orchestratorEvents).toEqual(["child_started", "child_done", "cleanup"]);
    // ...and the code-side constants the loader compares them with say the same, member for member.
    expect([...COMMON_FIELDS]).toEqual(["seq", "tUtc", "src", "event"]);
    expect([...LEDGER_SOURCES]).toEqual(["spec", "orchestrator"]);
    expect([...SPEC_EVENTS]).toEqual(["leg", "armed"]);
    expect([...ORCHESTRATOR_EVENTS]).toEqual(["child_started", "child_done", "cleanup"]);
    expect(contractJson.legs).toEqual([...LEG_IDS]);
    expect(contractJson.statuses).toEqual([...LEG_STATUSES]);
    expect(contractJson.reasons).toEqual([...REASONS]);
    expect(contractJson.methods).toEqual([...LEDGER_METHODS]);
    expect(contractJson.paths).toEqual([...LEDGER_PATHS]);
    expect(contractJson.mutationSkippedReasons).toEqual([...MUTATION_SKIPPED_REASONS]);
    expect(contractJson.freshnessStates).toEqual([...FRESHNESS_STATES]);
    expect(Object.keys(contractJson.legFacts)).toEqual([...LEG_IDS]);
    expect(Object.keys(contractJson.passFacts)).toEqual([...LEG_IDS]);
    expect(Object.keys(contractJson.passHttp)).toEqual([...LEG_IDS]);
  });

  it("pins each leg's fact keys and types", () => {
    expect(contractJson.legFacts.L0).toEqual({
      finalPathIsPortfolio: "boolean",
      headingPortfolio: "boolean",
      redirectedToLogin: "boolean",
      unauthorized401Seen: "boolean",
    });
    expect(Object.keys(contractJson.legFacts.L4)).toEqual([
      "catalogStatus",
      "noIfNoneMatch",
      "etagPresent",
      "assetsNonEmpty",
      "rowsRendered",
      "catalogParity",
      "activeCount",
    ]);
    expect(contractJson.legFacts.L5.anotherSessionActive).toBe("boolean|null");
    expect(contractJson.legFacts.L8.mutationSkippedReason).toBe("mutationSkippedReason");
    expect(contractJson.legFacts.L9.mutationSkippedReason).toBe("mutationSkippedReason");
    expect(contractJson.legFacts.L7).toEqual({ pageWriteRequestsBeforeMutation: "integer" });
  });
});

describe("Step B 5b contract loading", () => {
  const clone = (): Record<string, unknown> => JSON.parse(JSON.stringify(contractJson)) as Record<string, unknown>;

  it("loads the shipped contract", () => {
    expect(loadLedgerContract().legs).toEqual([...LEG_IDS]);
  });

  it("rejects an unknown top-level key, a missing key and a wrong schema id", () => {
    expect(() => parseLedgerContract({ ...clone(), extra: [] })).toThrow(LedgerContractError);
    const missing = clone();
    delete missing.reasons;
    expect(() => parseLedgerContract(missing)).toThrow(LedgerContractError);
    expect(() => parseLedgerContract({ ...clone(), schema: "other" })).toThrow(LedgerContractError);
    expect(() => parseLedgerContract(null)).toThrow(LedgerContractError);
    expect(() => parseLedgerContract([])).toThrow(LedgerContractError);
  });

  it("rejects a vocabulary that is not an array of strings and an unknown fact type", () => {
    expect(() => parseLedgerContract({ ...clone(), reasons: "OK" })).toThrow(LedgerContractError);
    expect(() => parseLedgerContract({ ...clone(), reasons: ["OK", 1] })).toThrow(LedgerContractError);
    expect(() => parseLedgerContract({ ...clone(), legFacts: { L0: { x: "string" } } })).toThrow(LedgerContractError);
  });

  it("fails closed when the contract drifts from the vocabulary the code was written for", () => {
    expect(() => loadLedgerContract({ ...clone(), reasons: [...REASONS, "NEW_REASON"] })).toThrow(/drifted/);
    // A path the code does not know about is drift; a path list too short for the passHttp table is refused as well.
    expect(() => loadLedgerContract({ ...clone(), paths: [...LEDGER_PATHS, "/api/extra"] })).toThrow(/drifted/);
    expect(() => loadLedgerContract({ ...clone(), paths: ["/api/portfolio"] })).toThrow(LedgerContractError);
    expect(() => loadLedgerContract({ ...clone(), legs: ["L0", "L1"] })).toThrow(/drifted/);
    const facts = { ...(clone().legFacts as Record<string, unknown>) };
    delete facts.L9;
    expect(() => loadLedgerContract({ ...clone(), legFacts: facts })).toThrow(LedgerContractError);
  });

  // The orchestrator pins these four lists exactly (members and order); so does this reader. Each case is a
  // contract edit the orchestrator refuses, so a reader that accepted it would let one side load a contract the
  // other refuses (round 3, finding 3).
  it.each<[string, string, unknown]>([
    ["commonFields", "loses tUtc", ["seq", "src", "event"]],
    ["commonFields", "gains a field", ["seq", "tUtc", "src", "event", "extra"]],
    ["commonFields", "is reordered", ["tUtc", "seq", "src", "event"]],
    ["commonFields", "is emptied", []],
    ["sources", "gains 'owner'", ["spec", "orchestrator", "owner"]],
    ["sources", "loses 'orchestrator'", ["spec"]],
    ["sources", "is reordered", ["orchestrator", "spec"]],
    ["specEvents", "gains 'cleanup'", ["leg", "armed", "cleanup"]],
    ["specEvents", "loses 'armed'", ["leg"]],
    ["specEvents", "is reordered", ["armed", "leg"]],
    ["orchestratorEvents", "loses 'cleanup'", ["child_started", "child_done"]],
    ["orchestratorEvents", "gains 'armed'", ["child_started", "child_done", "cleanup", "armed"]],
    ["orchestratorEvents", "is reordered", ["cleanup", "child_started", "child_done"]],
    ["orchestratorEvents", "renames an event", ["child_started", "child_finished", "cleanup"]],
  ])("refuses a contract whose %s %s", (list, _change, value) => {
    expect(() => loadLedgerContract({ ...clone(), [list]: value })).toThrow(
      new RegExp(`contract vocabulary '${list}' drifted from the verifier code`),
    );
  });

  it("accepts the shipped commonFields, sources, specEvents and orchestratorEvents (the positive pair of the cases above)", () => {
    const loaded = loadLedgerContract(clone());
    expect(loaded.commonFields).toEqual([...COMMON_FIELDS]);
    expect(loaded.sources).toEqual([...LEDGER_SOURCES]);
    expect(loaded.specEvents).toEqual([...SPEC_EVENTS]);
    expect(loaded.orchestratorEvents).toEqual([...ORCHESTRATOR_EVENTS]);
  });

  it("checks those four lists again on a contract object that did not come through the parser", () => {
    const shipped = loadLedgerContract();
    expect(() => assertContractMatchesCode(shipped)).not.toThrow();
    expect(() => assertContractMatchesCode({ ...shipped, commonFields: ["seq", "src", "event"] })).toThrow(/'commonFields' drifted/);
    expect(() => assertContractMatchesCode({ ...shipped, sources: [...shipped.sources, "owner"] })).toThrow(/'sources' drifted/);
    expect(() => assertContractMatchesCode({ ...shipped, specEvents: [...shipped.specEvents, "cleanup"] })).toThrow(/'specEvents' drifted/);
    expect(() => assertContractMatchesCode({ ...shipped, orchestratorEvents: ["child_started"] })).toThrow(/'orchestratorEvents' drifted/);
  });

  it("never echoes a member of a drifted list in the error", () => {
    let message = "";
    try {
      loadLedgerContract({ ...clone(), sources: ["spec", "orchestrator", "LEAKY-SOURCE"] });
    } catch (error) {
      message = (error as Error).message;
    }
    expect(message).toContain("'sources' drifted");
    expect(message).not.toContain("LEAKY");
  });

  it("writes every number in the shipped contract as a bare integer, the one thing this reader cannot refuse itself", () => {
    // JSON.parse reads `200.0` as the integer 200, so parseLedgerContract cannot tell it from `200`, while the
    // orchestrator's strict integer check refuses it. The shipped file is therefore pinned at the text level.
    const nonIntegerNumbers = (text: string): string[] =>
      text.replace(/"(?:[^"\\]|\\.)*"/g, '""').match(/-?\d+(?:\.\d+|[eE][+-]?\d+)/g) ?? [];
    expect(nonIntegerNumbers(readFileSync(path.resolve(__dirname, "../../../production-e2e/ledger-contract.json"), "utf8"))).toEqual([]);
    // Positive controls: the scan sees a fraction and an exponent outside a string, and ignores one inside a string.
    expect(nonIntegerNumbers('{"firstStatus": 200.0}')).toEqual(["200.0"]);
    expect(nonIntegerNumbers('{"activeCount": {"min": 1e2}}')).toEqual(["1e2"]);
    expect(nonIntegerNumbers('{"text": "version 1.0 of 2e3", "firstStatus": 200}')).toEqual([]);
  });
});

describe("Step B 5b ledger writer", () => {
  it("writes a valid leg line with the common fields first, as one JSON line ending in a bare LF", () => {
    const { writer, lines, parsed } = memoryWriter();
    writer.writeLeg(passedL4);
    expect(lines).toHaveLength(1);
    expect(lines[0].endsWith("\n")).toBe(true);
    expect(lines[0].includes("\r")).toBe(false);
    expect(lines[0].indexOf("\n")).toBe(lines[0].length - 1);
    expect(Object.keys(parsed()[0])).toEqual(["seq", "tUtc", "src", "event", "leg", "status", "reason", "http", "facts"]);
    expect(parsed()[0]).toMatchObject({
      seq: 1,
      tUtc: "2026-09-18T12:34:56.789Z",
      src: "spec",
      event: "leg",
      leg: "L4",
      status: "passed",
      reason: "OK",
      http: [{ method: "GET", path: "/api/assets", status: 200 }],
    });
  });

  it("writes armed with only the common fields and numbers lines from 1", () => {
    const { writer, parsed } = memoryWriter();
    writer.writeArmed();
    writer.writeLeg(passedL4);
    expect(parsed()[0]).toEqual({ seq: 1, tUtc: "2026-09-18T12:34:56.789Z", src: "spec", event: "armed" });
    expect(parsed()[1].seq).toBe(2);
  });

  it("accepts failed and skipped legs with partial or empty facts", () => {
    const { writer, parsed } = memoryWriter();
    writer.writeLeg({ leg: "L1", status: "failed", reason: "ASSERTION_FAILED", http: [], facts: { editButtonVisible: false } });
    writer.writeLeg({ leg: "L2", status: "skipped", reason: "SKIPPED_PRIOR_FAILURE", http: [], facts: {} });
    writer.writeLeg({
      leg: "L8",
      status: "skipped",
      reason: "SKIPPED_VISITOR_PRESENT",
      http: [],
      facts: { mutationSkippedReason: "VISITOR_PRESENT" },
    });
    expect(parsed()).toHaveLength(3);
  });

  it("accepts a null anotherSessionActive and the ABSENT freshness state only on a failed leg", () => {
    const { writer } = memoryWriter();
    expect(() =>
      writer.writeLeg({
        leg: "L5",
        status: "failed",
        reason: "ASSERTION_FAILED",
        http: [{ method: "GET", path: "/api/presence/demo", status: 200 }],
        facts: {
          presenceRequestsSinceOpen: 1,
          presenceStatus: 200,
          anotherSessionActive: null,
          requestFailed: false,
          corsConsoleError: false,
        },
      }),
    ).not.toThrow();
    expect(() =>
      writer.writeLeg({
        leg: "L2",
        status: "failed",
        reason: "ASSERTION_FAILED",
        http: [],
        facts: { freshnessState: "ABSENT", countsValid: false, stripVisible: false },
      }),
    ).not.toThrow();
  });

  it("refuses a passed L5 whose anotherSessionActive is null, and a passed L2 whose freshness is ABSENT", () => {
    const { writer, lines } = memoryWriter();
    expect(() =>
      writer.writeLeg({
        leg: "L5",
        status: "passed",
        reason: "OK",
        http: [{ method: "GET", path: "/api/presence/demo", status: 200 }],
        facts: {
          presenceRequestsSinceOpen: 1,
          presenceStatus: 200,
          anotherSessionActive: null,
          requestFailed: false,
          corsConsoleError: false,
        },
      }),
    ).toThrow(/FACTS_CONTRADICT_PASS/);
    expect(() =>
      writer.writeLeg({
        leg: "L2",
        status: "passed",
        reason: "OK",
        http: [],
        facts: { freshnessState: "ABSENT", countsValid: true, stripVisible: true },
      }),
    ).toThrow(/FACTS_CONTRADICT_PASS/);
    expect(lines).toHaveLength(0);
  });

  it("does not advance seq or record the leg when validation fails", () => {
    const { writer, lines, parsed } = memoryWriter();
    expect(() => writer.writeLeg({ ...passedL4, reason: "NOT_A_REASON" as never })).toThrow(LedgerValidationError);
    expect(lines).toHaveLength(0);
    expect(writer.hasWrittenLeg("L4")).toBe(false);
    writer.writeLeg(passedL4);
    expect(parsed()[0].seq).toBe(1);
  });

  it("allows exactly one final event per leg and one armed event", () => {
    const { writer } = memoryWriter();
    writer.writeLeg(passedL4);
    expect(() => writer.writeLeg(passedL4)).toThrow(LedgerSequenceError);
    writer.writeArmed();
    expect(() => writer.writeArmed()).toThrow(LedgerSequenceError);
  });

  it("stamps each line from the injected clock", () => {
    const times = [new Date("2026-09-18T00:00:00.001Z"), new Date("2026-09-18T00:00:00.002Z")];
    const { writer, parsed } = memoryWriter(() => times.shift() as Date);
    writer.writeArmed();
    writer.writeLeg(passedL4);
    expect(parsed().map((line) => line.tUtc)).toEqual(["2026-09-18T00:00:00.001Z", "2026-09-18T00:00:00.002Z"]);
  });
});

describe("Step B 5b ledger validation rejects everything outside the vocabulary", () => {
  const base = () => ({
    seq: 1,
    tUtc: "2026-09-18T12:34:56.789Z",
    src: "spec",
    event: "leg",
    leg: "L4",
    status: "passed",
    reason: "OK",
    http: [{ method: "GET", path: "/api/assets", status: 200 }],
    facts: { ...passedL4.facts },
  });
  const rejected = (line: unknown, code: string) => {
    let error: unknown;
    try {
      validateSpecLedgerLine(contract, line);
    } catch (caught) {
      error = caught;
    }
    expect(error).toBeInstanceOf(LedgerValidationError);
    expect((error as LedgerValidationError).code).toBe(code);
  };

  it("accepts the baseline line", () => {
    expect(() => validateSpecLedgerLine(contract, base())).not.toThrow();
  });

  it.each([
    ["an unknown top-level key", { ...base(), note: "x" }, "UNKNOWN_KEY"],
    ["a missing common field", (() => { const line: Record<string, unknown> = base(); delete line.tUtc; return line; })(), "MISSING_KEY"],
    ["a missing leg field", (() => { const line: Record<string, unknown> = base(); delete line.facts; return line; })(), "MISSING_KEY"],
    ["an unknown leg id", { ...base(), leg: "L10" }, "UNKNOWN_LEG"],
    ["a lower-case leg id", { ...base(), leg: "l4" }, "UNKNOWN_LEG"],
    ["an unknown status", { ...base(), status: "pending" }, "UNKNOWN_STATUS"],
    ["an unknown reason", { ...base(), reason: "BOOM" }, "UNKNOWN_REASON"],
    ["an unknown event", { ...base(), event: "cleanup" }, "UNKNOWN_EVENT"],
    ["an orchestrator source", { ...base(), src: "orchestrator" }, "BAD_SOURCE"],
    ["a non-integer seq", { ...base(), seq: 1.5 }, "BAD_SEQ"],
    ["a zero seq", { ...base(), seq: 0 }, "BAD_SEQ"],
    ["a timestamp without milliseconds", { ...base(), tUtc: "2026-09-18T12:34:56Z" }, "BAD_TIMESTAMP"],
    ["a timestamp with an offset", { ...base(), tUtc: "2026-09-18T12:34:56.789+00:00" }, "BAD_TIMESTAMP"],
    ["passed with a failure reason", { ...base(), reason: "ASSERTION_FAILED" }, "STATUS_REASON_MISMATCH"],
    ["failed with reason OK", { ...base(), status: "failed", reason: "OK" }, "STATUS_REASON_MISMATCH"],
    ["failed with a skipped reason", { ...base(), status: "failed", reason: "SKIPPED_PRIOR_FAILURE" }, "STATUS_REASON_MISMATCH"],
    ["skipped with a non-skipped reason", { ...base(), status: "skipped", reason: "TIMEOUT" }, "STATUS_REASON_MISMATCH"],
    ["http that is not an array", { ...base(), http: {} }, "HTTP_NOT_ARRAY"],
    ["an http entry with an extra key", { ...base(), http: [{ method: "GET", path: "/api/assets", status: 200, note: "x" }] }, "HTTP_ENTRY_SHAPE"],
    ["an http entry that is missing a key", { ...base(), http: [{ method: "GET", path: "/api/assets" }] }, "HTTP_ENTRY_SHAPE"],
    ["a POST method", { ...base(), http: [{ method: "POST", path: "/api/assets", status: 200 }] }, "HTTP_UNKNOWN_METHOD"],
    ["an OPTIONS method", { ...base(), http: [{ method: "OPTIONS", path: "/api/assets", status: 200 }] }, "HTTP_UNKNOWN_METHOD"],
    ["a lower-case method", { ...base(), http: [{ method: "get", path: "/api/assets", status: 200 }] }, "HTTP_UNKNOWN_METHOD"],
    ["a path outside the list", { ...base(), http: [{ method: "GET", path: "/api/auth/login", status: 200 }] }, "HTTP_UNKNOWN_PATH"],
    ["a path with a query string", { ...base(), http: [{ method: "GET", path: "/api/assets?x=1", status: 200 }] }, "HTTP_UNKNOWN_PATH"],
    ["a full url instead of a template", { ...base(), http: [{ method: "GET", path: "https://api.vibhanshu-ai-portfolio.dev/api/assets", status: 200 }] }, "HTTP_UNKNOWN_PATH"],
    ["a string status", { ...base(), http: [{ method: "GET", path: "/api/assets", status: "200" }] }, "HTTP_BAD_STATUS"],
    ["a status out of range", { ...base(), http: [{ method: "GET", path: "/api/assets", status: 99 }] }, "HTTP_BAD_STATUS"],
    ["facts that are not an object", { ...base(), facts: [] }, "FACTS_NOT_OBJECT"],
    ["an unknown fact key", { ...base(), facts: { ...passedL4.facts, ticker: "AAPL" } }, "FACTS_UNKNOWN_KEY"],
    ["a fact key from another leg", { ...base(), facts: { ...passedL4.facts, putCount: 1 } }, "FACTS_UNKNOWN_KEY"],
    ["a string where a boolean belongs", { ...base(), facts: { ...passedL4.facts, etagPresent: "yes" } }, "FACTS_BAD_VALUE"],
    ["a float where an integer belongs", { ...base(), facts: { ...passedL4.facts, activeCount: 1.5 } }, "FACTS_BAD_VALUE"],
    ["a negative integer", { ...base(), facts: { ...passedL4.facts, activeCount: -1 } }, "FACTS_BAD_VALUE"],
    ["null where a plain boolean belongs", { ...base(), facts: { ...passedL4.facts, etagPresent: null } }, "FACTS_BAD_VALUE"],
    ["a passed leg missing a fact", { ...base(), facts: { catalogStatus: 200 } }, "FACTS_INCOMPLETE_FOR_PASS"],
    ["a passed leg with no facts at all", { ...base(), facts: {} }, "FACTS_INCOMPLETE_FOR_PASS"],
    ["a passed leg whose facts contradict it (parity false)", { ...base(), facts: { ...passedL4.facts, catalogParity: false } }, "FACTS_CONTRADICT_PASS"],
    ["a passed leg whose status fact is not 200", { ...base(), facts: { ...passedL4.facts, catalogStatus: 304 } }, "FACTS_CONTRADICT_PASS"],
    ["a passed leg with an empty active catalog", { ...base(), facts: { ...passedL4.facts, activeCount: 0 } }, "FACTS_CONTRADICT_PASS"],
    ["a passed leg with no http entry at all", { ...base(), http: [] }, "HTTP_CONTRADICT_PASS"],
    ["a passed leg whose first http status is not its status fact", { ...base(), http: [{ method: "GET", path: "/api/assets", status: 500 }] }, "HTTP_CONTRADICT_PASS"],
    ["a passed leg whose http is on another path", { ...base(), http: [{ method: "GET", path: "/api/portfolio", status: 200 }] }, "HTTP_CONTRADICT_PASS"],
  ])("rejects %s", (_label, line, code) => {
    rejected(line, code);
  });

  it("stays inside the orchestrator's limits: at most 200 http entries and integer facts up to 1e9", () => {
    // L4's http rule wants GET /api/assets entries, so a passed L4 can be filled to the limit with them.
    const entry = { method: "GET", path: "/api/assets", status: 200 };
    expect(() => validateSpecLedgerLine(contract, { ...base(), http: Array(MAX_HTTP_ENTRIES).fill(entry) })).not.toThrow();
    rejected({ ...base(), http: Array(MAX_HTTP_ENTRIES + 1).fill(entry) }, "HTTP_TOO_MANY_ENTRIES");
    expect(() =>
      validateSpecLedgerLine(contract, { ...base(), facts: { ...passedL4.facts, activeCount: MAX_INTEGER_FACT } }),
    ).not.toThrow();
    rejected({ ...base(), facts: { ...passedL4.facts, activeCount: MAX_INTEGER_FACT + 1 } }, "FACTS_BAD_VALUE");
  });

  it("shares one integer bound between the writer and the contract's pass literals", () => {
    expect(MAX_INTEGER_FACT).toBe(1_000_000_000);
    expect(CONTRACT_MAX_INTEGER_FACT).toBe(MAX_INTEGER_FACT);
  });

  it("rejects free text in enumerated facts", () => {
    const l2 = (freshnessState: unknown) => ({
      ...base(),
      leg: "L2",
      status: "failed",
      reason: "ASSERTION_FAILED",
      http: [],
      facts: { freshnessState },
    });
    expect(() => validateSpecLedgerLine(contract, l2("STALE"))).not.toThrow();
    rejected(l2("some page text"), "FACTS_BAD_VALUE");
    rejected(l2(""), "FACTS_BAD_VALUE");
    rejected(l2(7), "FACTS_BAD_VALUE");

    const l8 = (mutationSkippedReason: unknown) => ({
      ...base(),
      leg: "L8",
      status: "skipped",
      reason: "SKIPPED_VISITOR_PRESENT",
      http: [],
      facts: { mutationSkippedReason },
    });
    expect(() => validateSpecLedgerLine(contract, l8("VISITOR_PRESENT"))).not.toThrow();
    rejected(l8("visitor was here"), "FACTS_BAD_VALUE");
  });

  it("rejects an armed line with extra keys and a non-object line", () => {
    rejected({ seq: 1, tUtc: "2026-09-18T12:34:56.789Z", src: "spec", event: "armed", leg: "L8" }, "UNKNOWN_KEY");
    rejected("armed", "LINE_NOT_OBJECT");
    rejected(null, "LINE_NOT_OBJECT");
  });

  it("never echoes a rejected value in its message", () => {
    let message = "";
    try {
      validateSpecLedgerLine(contract, {
        ...base(),
        facts: { ...passedL4.facts, etagPresent: "Bearer eyJhbGciOiJIUzI1NiJ9.SECRET.SIG" },
      });
    } catch (error) {
      message = (error as Error).message;
    }
    expect(message).toContain("FACTS_BAD_VALUE");
    expect(message).not.toContain("Bearer");
    expect(message).not.toContain("SECRET");
  });
});

describe("Step B 5b status to failure reason", () => {
  it.each([
    [429, "RATE_LIMITED"],
    [409, "CONFLICT"],
    [503, "HTTP_STATUS_NOT_200"],
    [504, "HTTP_STATUS_NOT_200"],
    [500, "HTTP_STATUS_NOT_200"],
    [401, "HTTP_STATUS_NOT_200"],
    [403, "HTTP_STATUS_NOT_200"],
    [304, "HTTP_STATUS_NOT_200"],
    [0, "REQUEST_FAILED"],
    [null, "REQUEST_FAILED"],
    [200, "ASSERTION_FAILED"],
  ])("maps status %s to %s", (status, reason) => {
    expect(failureReasonForStatus(status)).toBe(reason);
  });
});

describe("Step B 5b filesystem ledger sink", () => {
  it("opens for append, writes, fsyncs and closes, in that order, for every line", () => {
    const calls: string[] = [];
    const fsApi: FsLedgerApi = {
      openSync: (file, flags) => {
        calls.push(`open:${flags}:${file}`);
        return 7;
      },
      writeSync: (fd, data) => {
        calls.push(`write:${fd}:${data.length}`);
        return data.length;
      },
      fsyncSync: (fd) => void calls.push(`fsync:${fd}`),
      closeSync: (fd) => void calls.push(`close:${fd}`),
    };
    createFsLedgerSink("/work/ledger.jsonl", fsApi).append("line\n");
    expect(calls).toEqual(["open:a:/work/ledger.jsonl", "write:7:5", "fsync:7", "close:7"]);
  });

  it("closes the file even when the write fails", () => {
    const calls: string[] = [];
    const fsApi: FsLedgerApi = {
      openSync: () => 3,
      writeSync: () => {
        throw new Error("disk full");
      },
      fsyncSync: () => void calls.push("fsync"),
      closeSync: () => void calls.push("close"),
    };
    expect(() => createFsLedgerSink("/x", fsApi).append("line\n")).toThrow("disk full");
    expect(calls).toEqual(["close"]);
  });

  it("appends whole LF-terminated JSON lines to a real file without rewriting earlier ones", () => {
    const dir = mkdtempSync(path.join(tmpdir(), "step-b-5b-ledger-"));
    try {
      const file = path.join(dir, LEDGER_FILE_NAME);
      const writer = new LedgerWriter({ contract, sink: createFsLedgerSink(file), now: () => FIXED_NOW });
      writer.writeLeg(passedL4);
      const afterFirst = readFileSync(file, "utf8");
      writer.writeArmed();
      const text = readFileSync(file, "utf8");
      expect(text.startsWith(afterFirst)).toBe(true);
      expect(text.endsWith("\n")).toBe(true);
      expect(text.includes("\r")).toBe(false);
      const lines = text.split("\n").filter((line) => line !== "");
      expect(lines).toHaveLength(2);
      expect(JSON.parse(lines[1])).toMatchObject({ seq: 2, event: "armed" });
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe("Step B 5b leg ids are the whole set", () => {
  it("has exactly one facts description per leg", () => {
    const legs: readonly LegId[] = LEG_IDS;
    expect(legs).toHaveLength(10);
    expect(Object.keys(contract.legFacts)).toHaveLength(10);
  });
});
