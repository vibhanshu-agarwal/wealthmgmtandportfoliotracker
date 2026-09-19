// @vitest-environment node
import { describe, expect, it } from "vitest";
import contractJson from "../../../production-e2e/ledger-contract.json";
import {
  LEG_IDS,
  LedgerContractError,
  loadLedgerContract,
  MAX_INTEGER_FACT,
  parseLedgerContract,
  passRuleHolds,
  type LedgerContract,
  type LegId,
  type PassRule,
} from "../../../production-e2e/lib/contract";
import { LedgerValidationError, LedgerWriter, type FactValue, type HttpEntry } from "../../../production-e2e/lib/ledger";

const contract: LedgerContract = loadLedgerContract();

function memoryWriter() {
  const lines: string[] = [];
  const writer = new LedgerWriter({
    contract,
    sink: { append: (line) => void lines.push(line) },
    now: () => new Date("2026-09-18T12:34:56.789Z"),
  });
  return { writer, lines };
}

/**
 * A fully passing fact set for every leg, transcribed from the pass conditions coded in
 * step-b-5b.spec.ts (runL0..runL9) and from the contract's leg facts. It is written out by hand on
 * purpose: generating it from `passFacts` would only prove the table agrees with itself, whereas this
 * proves the table does not reject what the spec itself emits when a leg passes.
 */
const CANONICAL_PASS_FACTS: Record<LegId, Record<string, FactValue>> = {
  // pass: !unauthorized401Seen && finalPathIsPortfolio && headingPortfolio && !redirectedToLogin
  L0: { finalPathIsPortfolio: true, headingPortfolio: true, redirectedToLogin: false, unauthorized401Seen: false },
  // pass: both controls visible and the last portfolio read was a 200 (resetEnabled is only recorded)
  L1: { editButtonVisible: true, resetButtonVisible: true, portfolioLoadStatus: 200, resetEnabled: true },
  // pass: summary 200, freshness state known, counts valid, strip visible (the state itself is only recorded)
  L2: { freshnessState: "FRESH", countsValid: true, stripVisible: true },
  // pass: dialog open and the "temporarily unavailable" notice absent
  L3: { dialogOpen: true, unavailableNoticeVisible: false },
  // pass: catalog 200, no If-None-Match, etag, non-empty assets, rows rendered, parity with the oracle
  L4: {
    catalogStatus: 200,
    noIfNoneMatch: true,
    etagPresent: true,
    assetsNonEmpty: true,
    rowsRendered: true,
    catalogParity: true,
    activeCount: 159,
  },
  // pass: exactly one presence request, 200, boolean body, no requestfailed, no CORS console error
  L5: {
    presenceRequestsSinceOpen: 1,
    presenceStatus: 200,
    anotherSessionActive: false,
    requestFailed: false,
    corsConsoleError: false,
  },
  // pass: attributed picker requests == predicted batches, all 200 and array-shaped, one non-null price
  L6: {
    priceRequestsAfterUncheck: 7,
    allStatus200: true,
    arrayShaped: true,
    nonNullPriceSeen: true,
    disjointFromPageBatches: true,
    predictedBatchCount: 7,
  },
  // pass: dialog closed and zero non-GET/OPTIONS page requests
  L7: { pageWriteRequestsBeforeMutation: 0 },
  // pass: exactly one PUT, 200, the six checks, and the gate reason NONE
  L8: {
    putCount: 1,
    putStatus: 200,
    expectedVersionMatchesObserved: true,
    bodyMatchesExpectedDraft: true,
    versionAdvanced: true,
    savedStatusVisible: true,
    independentReadVersionMatches: true,
    independentReadHoldingsMatch: true,
    mutationSkippedReason: "NONE",
  },
  // pass: exactly one PUT, 200, the six checks, and the gate reason NONE
  L9: {
    putCount: 1,
    putStatus: 200,
    noInternalKeyHeader: true,
    expectedVersionMatchesSaved: true,
    versionPlusOne: true,
    responseEqualsGolden: true,
    resetStatusVisible: true,
    independentReadEqualsGolden: true,
    mutationSkippedReason: "NONE",
  },
};

/**
 * The request evidence that goes with each leg's canonical facts, again written out by hand: L0, L3 and L7 record none,
 * L1 the settled portfolio reads, L2/L4/L5 their first-attempt reads, L6 the attributed price batches (7, as the count
 * fact says), and L8/L9 their single write. This file is about the pass-facts table; the http table has its own file.
 */
const CANONICAL_PASS_HTTP: Record<LegId, HttpEntry[]> = {
  L0: [],
  L1: [{ method: "GET", path: "/api/portfolio", status: 200 }],
  L2: [{ method: "GET", path: "/api/portfolio/summary", status: 200 }],
  L3: [],
  L4: [{ method: "GET", path: "/api/assets", status: 200 }],
  L5: [{ method: "GET", path: "/api/presence/demo", status: 200 }],
  L6: Array.from({ length: 7 }, () => ({ method: "GET", path: "/api/market/prices", status: 200 })),
  L7: [],
  L8: [{ method: "PUT", path: "/api/portfolio/holdings", status: 200 }],
  L9: [{ method: "PUT", path: "/api/portfolio/demo-reset", status: 200 }],
};

const EXPECTED_PASS_FACTS = {
  L0: { finalPathIsPortfolio: true, headingPortfolio: true, redirectedToLogin: false, unauthorized401Seen: false },
  L1: { editButtonVisible: true, resetButtonVisible: true, portfolioLoadStatus: 200 },
  L2: { freshnessState: { not: "ABSENT" }, countsValid: true, stripVisible: true },
  L3: { dialogOpen: true, unavailableNoticeVisible: false },
  L4: {
    catalogStatus: 200,
    noIfNoneMatch: true,
    etagPresent: true,
    assetsNonEmpty: true,
    rowsRendered: true,
    catalogParity: true,
    activeCount: { min: 1 },
  },
  L5: {
    presenceRequestsSinceOpen: 1,
    presenceStatus: 200,
    anotherSessionActive: { type: "boolean" },
    requestFailed: false,
    corsConsoleError: false,
  },
  L6: {
    priceRequestsAfterUncheck: { equalsFact: "predictedBatchCount" },
    allStatus200: true,
    arrayShaped: true,
    nonNullPriceSeen: true,
    disjointFromPageBatches: true,
    predictedBatchCount: { min: 1 },
  },
  L7: { pageWriteRequestsBeforeMutation: 0 },
  L8: {
    putCount: 1,
    putStatus: 200,
    expectedVersionMatchesObserved: true,
    bodyMatchesExpectedDraft: true,
    versionAdvanced: true,
    savedStatusVisible: true,
    independentReadVersionMatches: true,
    independentReadHoldingsMatch: true,
    mutationSkippedReason: "NONE",
  },
  L9: {
    putCount: 1,
    putStatus: 200,
    noInternalKeyHeader: true,
    expectedVersionMatchesSaved: true,
    versionPlusOne: true,
    responseEqualsGolden: true,
    resetStatusVisible: true,
    independentReadEqualsGolden: true,
    mutationSkippedReason: "NONE",
  },
};

type PassTable = Record<string, Record<string, unknown>>;

function withPassFacts(mutate: (table: PassTable) => void): Record<string, unknown> {
  const copy = JSON.parse(JSON.stringify(contractJson)) as Record<string, unknown>;
  mutate(copy.passFacts as PassTable);
  return copy;
}

/** Sets an OWN enumerable property, even one named like an Object.prototype member. */
function setOwn(target: object, key: string, value: unknown): void {
  Object.defineProperty(target, key, { value, enumerable: true, writable: true, configurable: true });
}

function withTopLevel(key: string, value: unknown): Record<string, unknown> {
  const copy = JSON.parse(JSON.stringify(contractJson)) as Record<string, unknown>;
  copy[key] = value;
  return copy;
}

describe("Step B 5b passFacts table pin", () => {
  it("is exactly the table the spec's pass conditions imply, so loosening a rule needs a deliberate test change", () => {
    expect(contractJson.passFacts).toEqual(EXPECTED_PASS_FACTS);
  });

  it("states the rule forms in prose and parses into one typed rule per entry", () => {
    expect(contract.passFactRules).toContain("strict equality");
    expect(contract.passFactRules).toContain("equalsFact");
    expect(Object.keys(contract.passFacts)).toEqual([...LEG_IDS]);
    expect(contract.passFacts.L6.priceRequestsAfterUncheck).toEqual({ kind: "equalsFact", fact: "predictedBatchCount" });
    expect(contract.passFacts.L4.activeCount).toEqual({ kind: "min", min: 1 });
    expect(contract.passFacts.L2.freshnessState).toEqual({ kind: "not", value: "ABSENT" });
    expect(contract.passFacts.L5.anotherSessionActive).toEqual({ kind: "boolean" });
    expect(contract.passFacts.L8.mutationSkippedReason).toEqual({ kind: "literal", value: "NONE" });
  });
});

describe("Step B 5b pass rule forms", () => {
  const literal = (value: boolean | number | string): PassRule => ({ kind: "literal", value });

  it("a literal is strict equality: booleans, integers and strings are never coerced", () => {
    for (const wrong of [false, 1, "true", "1", null, undefined, {}, [], [true]]) {
      expect(passRuleHolds(literal(true), wrong, {}), String(wrong)).toBe(false);
    }
    expect(passRuleHolds(literal(true), true, {})).toBe(true);

    for (const wrong of [true, 0, "", "0", "false", null, undefined, []]) {
      expect(passRuleHolds(literal(false), wrong, {}), String(wrong)).toBe(false);
    }
    expect(passRuleHolds(literal(false), false, {})).toBe(true);

    for (const wrong of ["200", "200.0", true, 1, null, undefined, 199, 201, [200]]) {
      expect(passRuleHolds(literal(200), wrong, {}), String(wrong)).toBe(false);
    }
    expect(passRuleHolds(literal(200), 200, {})).toBe(true);

    for (const wrong of [false, "", "0", null, undefined, 1, []]) {
      expect(passRuleHolds(literal(0), wrong, {}), String(wrong)).toBe(false);
    }
    expect(passRuleHolds(literal(0), 0, {})).toBe(true);

    for (const wrong of ["none", "None", "NONE ", ["NONE"], null, undefined, 0]) {
      expect(passRuleHolds(literal("NONE"), wrong, {}), String(wrong)).toBe(false);
    }
    expect(passRuleHolds(literal("NONE"), "NONE", {})).toBe(true);
  });

  it("not: the value has the literal's own type and differs from it", () => {
    const notAbsent: PassRule = { kind: "not", value: "ABSENT" };
    expect(passRuleHolds(notAbsent, "ABSENT", {})).toBe(false);
    for (const ok of ["FRESH", "STALE", "UNKNOWN", "MISSING"]) expect(passRuleHolds(notAbsent, ok, {})).toBe(true);
    // A value of the wrong type never satisfies a rule, even though it differs.
    for (const wrong of [null, undefined, 0, 1, true, false, [], {}]) {
      expect(passRuleHolds(notAbsent, wrong, {}), String(wrong)).toBe(false);
    }
    const notZero: PassRule = { kind: "not", value: 0 };
    expect(passRuleHolds(notZero, 0, {})).toBe(false);
    expect(passRuleHolds(notZero, 3, {})).toBe(true);
    expect(passRuleHolds(notZero, "3", {})).toBe(false);
    expect(passRuleHolds(notZero, false, {})).toBe(false);
  });

  it("min: an integer at or above the bound, never a coerced or non-integer value", () => {
    const atLeastOne: PassRule = { kind: "min", min: 1 };
    expect(passRuleHolds(atLeastOne, 0, {})).toBe(false);
    expect(passRuleHolds(atLeastOne, 1, {})).toBe(true);
    expect(passRuleHolds(atLeastOne, 159, {})).toBe(true);
    for (const wrong of [1.5, 2.5, "2", "1", true, null, undefined, Number.NaN, Number.POSITIVE_INFINITY, [2], -1]) {
      expect(passRuleHolds(atLeastOne, wrong, {}), String(wrong)).toBe(false);
    }
  });

  it("boolean: true or false, never null and never a truthy or falsy stand-in", () => {
    const boolean: PassRule = { kind: "boolean" };
    expect(passRuleHolds(boolean, true, {})).toBe(true);
    expect(passRuleHolds(boolean, false, {})).toBe(true);
    for (const wrong of [null, undefined, 0, 1, "true", "false", "", [], {}]) {
      expect(passRuleHolds(boolean, wrong, {}), String(wrong)).toBe(false);
    }
  });

  it("equalsFact: strictly equal to another fact of the same leg, which must exist", () => {
    const equalsCount: PassRule = { kind: "equalsFact", fact: "predictedBatchCount" };
    expect(passRuleHolds(equalsCount, 7, { predictedBatchCount: 7 })).toBe(true);
    expect(passRuleHolds(equalsCount, 7, { predictedBatchCount: 8 })).toBe(false);
    expect(passRuleHolds(equalsCount, 7, { predictedBatchCount: "7" })).toBe(false);
    expect(passRuleHolds(equalsCount, 7, {})).toBe(false);
    expect(passRuleHolds(equalsCount, undefined, { predictedBatchCount: undefined })).toBe(false);
    expect(passRuleHolds(equalsCount, 7, Object.create({ predictedBatchCount: 7 }) as Record<string, unknown>)).toBe(false);
  });
});

describe("Step B 5b contract loading of the pass table (fails closed)", () => {
  it("loads the shipped table and parses every entry", () => {
    for (const leg of LEG_IDS) {
      expect(Object.keys(contract.passFacts[leg]).length, leg).toBeGreaterThan(0);
      for (const key of Object.keys(contract.passFacts[leg])) {
        expect(Object.keys(contract.legFacts[leg]), `${leg}.${key}`).toContain(key);
      }
    }
  });

  it.each([
    ["passFacts that is null", () => withTopLevel("passFacts", null)],
    ["passFacts that is an array", () => withTopLevel("passFacts", [])],
    ["passFacts that is a string", () => withTopLevel("passFacts", "L0")],
    ["a missing passFacts key", () => {
      const copy = JSON.parse(JSON.stringify(contractJson)) as Record<string, unknown>;
      delete copy.passFacts;
      return copy;
    }],
    ["a missing passFactRules key", () => {
      const copy = JSON.parse(JSON.stringify(contractJson)) as Record<string, unknown>;
      delete copy.passFactRules;
      return copy;
    }],
    ["passFactRules that is not a string", () => withTopLevel("passFactRules", ["rules"])],
    ["passFactRules that is blank", () => withTopLevel("passFactRules", "  ")],
  ])("rejects %s", (_label, build) => {
    expect(() => parseLedgerContract(build())).toThrow(LedgerContractError);
  });

  it.each<[string, (table: PassTable) => void]>([
    ["a rule that is null", (t) => { t.L0.headingPortfolio = null; }],
    ["a rule that is an array", (t) => { t.L0.headingPortfolio = [true]; }],
    ["an empty rule object", (t) => { t.L0.headingPortfolio = {}; }],
    ["a rule with two forms", (t) => { t.L4.activeCount = { min: 1, not: 0 }; }],
    ["an unknown rule form", (t) => { t.L4.activeCount = { max: 5 }; }],
    ["an integer literal on a boolean fact", (t) => { t.L0.headingPortfolio = 1; }],
    ["a string literal on a boolean fact", (t) => { t.L0.headingPortfolio = "true"; }],
    ["a boolean literal on an integer fact", (t) => { t.L4.catalogStatus = true; }],
    ["a negative integer literal", (t) => { t.L4.catalogStatus = -1; }],
    ["a fractional integer literal", (t) => { t.L4.catalogStatus = 200.5; }],
    ["an enum literal outside its enum", (t) => { t.L8.mutationSkippedReason = "SOMETHING_ELSE"; }],
    ["an integer literal above the fact range", (t) => { t.L4.catalogStatus = MAX_INTEGER_FACT + 1; }],
    ["an integer literal of 2e9", (t) => { t.L4.catalogStatus = 2_000_000_000; }],
    ["an unsafe integer literal", (t) => { t.L4.catalogStatus = Number.MAX_SAFE_INTEGER + 2; }],
    ["not with an integer literal above the fact range", (t) => { t.L4.catalogStatus = { not: MAX_INTEGER_FACT + 1 }; }],
    ["a min above the fact range", (t) => { t.L4.activeCount = { min: MAX_INTEGER_FACT + 1 }; }],
    ["a min of 2e9", (t) => { t.L4.activeCount = { min: 2_000_000_000 }; }],
    ["not with a value of the wrong type", (t) => { t.L2.freshnessState = { not: 5 }; }],
    ["not with null", (t) => { t.L2.freshnessState = { not: null }; }],
    ["not with a value outside the enum", (t) => { t.L2.freshnessState = { not: "STALEISH" }; }],
    ["min on a boolean fact", (t) => { t.L0.headingPortfolio = { min: 1 }; }],
    ["a fractional min", (t) => { t.L4.activeCount = { min: 1.5 }; }],
    ["a negative min", (t) => { t.L4.activeCount = { min: -1 }; }],
    ["a string min", (t) => { t.L4.activeCount = { min: "1" }; }],
    ["a type other than boolean", (t) => { t.L5.anotherSessionActive = { type: "integer" }; }],
    ["type boolean on an integer fact", (t) => { t.L4.activeCount = { type: "boolean" }; }],
    ["equalsFact naming an unknown fact", (t) => { t.L6.priceRequestsAfterUncheck = { equalsFact: "nope" }; }],
    ["equalsFact naming itself", (t) => { t.L6.priceRequestsAfterUncheck = { equalsFact: "priceRequestsAfterUncheck" }; }],
    ["equalsFact naming a fact of another type", (t) => { t.L6.priceRequestsAfterUncheck = { equalsFact: "allStatus200" }; }],
    ["equalsFact naming an inherited property", (t) => { t.L6.priceRequestsAfterUncheck = { equalsFact: "constructor" }; }],
    ["equalsFact that is not a string", (t) => { t.L6.priceRequestsAfterUncheck = { equalsFact: 7 }; }],
    ["a rule for a fact the leg does not have", (t) => { t.L0.putCount = 1; }],
    ["a rule for a fact that belongs to another leg", (t) => { t.L7.putStatus = 200; }],
    ["a rule for an inherited property name", (t) => { setOwn(t.L0, "toString", true); }],
    ["a table for an unknown leg", (t) => { t.L10 = { x: true }; }],
    ["a table for an inherited property name", (t) => { setOwn(t, "constructor", { x: true }); }],
    ["a leg table that is not an object", (t) => { setOwn(t, "L3", true); }],
  ])("rejects %s", (_label, mutate) => {
    expect(() => parseLedgerContract(withPassFacts(mutate))).toThrow(LedgerContractError);
  });

  it("accepts integer literals and mins exactly at the fact range, the same range an integer fact itself has", () => {
    expect(MAX_INTEGER_FACT).toBe(1_000_000_000);
    const atLimit = withPassFacts((t) => {
      t.L4.catalogStatus = MAX_INTEGER_FACT;
      t.L4.activeCount = { min: MAX_INTEGER_FACT };
    });
    expect(() => loadLedgerContract(atLimit)).not.toThrow();
    const parsed = loadLedgerContract(atLimit);
    expect(parsed.passFacts.L4.catalogStatus).toEqual({ kind: "literal", value: MAX_INTEGER_FACT });
    expect(parsed.passFacts.L4.activeCount).toEqual({ kind: "min", min: MAX_INTEGER_FACT });
    // ...and the writer's own bound for a fact is the same number, so no legal fact could satisfy a rule above it.
    const { writer } = memoryWriter();
    expect(() =>
      writer.writeLeg({ leg: "L4", status: "failed", reason: "ASSERTION_FAILED", http: [], facts: { activeCount: MAX_INTEGER_FACT } }),
    ).not.toThrow();
    expect(() =>
      writer.writeLeg({ leg: "L5", status: "failed", reason: "ASSERTION_FAILED", http: [], facts: { presenceStatus: MAX_INTEGER_FACT + 1 } }),
    ).toThrow(/FACTS_BAD_VALUE/);
  });

  it("refuses a contract that leaves a leg without a pass table, or with an empty one", () => {
    expect(() => loadLedgerContract(withPassFacts((t) => { delete t.L9; }))).toThrow(/passFacts must describe exactly/);
    expect(() => loadLedgerContract(withPassFacts((t) => { t.L7 = {}; }))).toThrow(/at least one fact/);
  });

  it("never echoes a rule value in its message", () => {
    let message = "";
    try {
      parseLedgerContract(withPassFacts((t) => { t.L2.freshnessState = { not: "LEAKY-VALUE" }; }));
    } catch (error) {
      message = (error as Error).message;
    }
    expect(message).toContain("passFacts.L2.freshnessState");
    expect(message).not.toContain("LEAKY-VALUE");
  });
});

describe("Step B 5b writer enforces the pass table for every leg", () => {
  it("has a hand-written canonical passing fact set with exactly the keys of each leg", () => {
    expect(Object.keys(CANONICAL_PASS_FACTS)).toEqual([...LEG_IDS]);
    for (const leg of LEG_IDS) {
      expect(Object.keys(CANONICAL_PASS_FACTS[leg]).sort(), leg).toEqual(Object.keys(contract.legFacts[leg]).sort());
    }
  });

  it.each([...LEG_IDS])("%s: the canonical fully-passing fact set is accepted by the writer", (leg) => {
    const { writer, lines } = memoryWriter();
    expect(() =>
      writer.writeLeg({ leg, status: "passed", reason: "OK", http: CANONICAL_PASS_HTTP[leg], facts: CANONICAL_PASS_FACTS[leg] }),
    ).not.toThrow();
    expect(lines).toHaveLength(1);
    expect(JSON.parse(lines[0])).toMatchObject({ leg, status: "passed", reason: "OK", facts: CANONICAL_PASS_FACTS[leg] });
  });

  it.each([...LEG_IDS])("%s: the canonical set satisfies every rule of the table, and every rule is exercised", (leg) => {
    const rules = Object.entries(contract.passFacts[leg]);
    expect(rules.length).toBeGreaterThan(0);
    for (const [key, rule] of rules) {
      expect(passRuleHolds(rule, CANONICAL_PASS_FACTS[leg][key], CANONICAL_PASS_FACTS[leg]), `${leg}.${key}`).toBe(true);
    }
  });

  it("accepts values the spec only records and does not require (state, presence, reset button, catalog size)", () => {
    const accepted: Array<[LegId, Record<string, FactValue>, HttpEntry[]]> = [
      ["L1", { resetEnabled: false }, CANONICAL_PASS_HTTP.L1],
      ["L2", { freshnessState: "STALE" }, CANONICAL_PASS_HTTP.L2],
      ["L2", { freshnessState: "UNKNOWN" }, CANONICAL_PASS_HTTP.L2],
      ["L2", { freshnessState: "MISSING" }, CANONICAL_PASS_HTTP.L2],
      ["L4", { activeCount: 1 }, CANONICAL_PASS_HTTP.L4],
      ["L5", { anotherSessionActive: true }, CANONICAL_PASS_HTTP.L5],
      // A count of 1 records exactly one attributed price request.
      ["L6", { priceRequestsAfterUncheck: 1, predictedBatchCount: 1 }, CANONICAL_PASS_HTTP.L6.slice(0, 1)],
    ];
    for (const [leg, override, http] of accepted) {
      const { writer } = memoryWriter();
      expect(() =>
        writer.writeLeg({ leg, status: "passed", reason: "OK", http, facts: { ...CANONICAL_PASS_FACTS[leg], ...override } }),
      ).not.toThrow();
    }
  });

  /** A value that violates `rule`, several where more than one boundary exists. */
  function violationsOf(rule: PassRule, facts: Record<string, FactValue>): FactValue[] {
    switch (rule.kind) {
      case "literal":
        if (typeof rule.value === "boolean") return [!rule.value];
        if (typeof rule.value === "number") return rule.value > 0 ? [rule.value + 1, rule.value - 1] : [rule.value + 1];
        // The only string literal in the table is the mutation gate reason, so another member of its enum.
        return contract.mutationSkippedReasons.filter((other) => other !== rule.value).slice(0, 1);
      case "not":
        return [rule.value];
      case "min":
        return rule.min > 0 ? [rule.min - 1] : [];
      case "boolean":
        return [null];
      case "equalsFact":
        return [(facts[rule.fact] as number) + 1];
    }
  }

  /**
   * The canonical facts with one value replaced. Facts that must EQUAL the replaced one (equalsFact) move with
   * it, so that only the rule under test is violated and the writer's first named key is that rule's key.
   */
  function violatedFacts(leg: LegId, key: string, value: FactValue): Record<string, FactValue> {
    const facts: Record<string, FactValue> = { ...CANONICAL_PASS_FACTS[leg], [key]: value };
    for (const [otherKey, rule] of Object.entries(contract.passFacts[leg])) {
      if (rule.kind === "equalsFact" && rule.fact === key) facts[otherKey] = value;
    }
    return facts;
  }

  const cases = LEG_IDS.flatMap((leg) =>
    Object.entries(contract.passFacts[leg]).flatMap(([key, rule]) =>
      violationsOf(rule, CANONICAL_PASS_FACTS[leg]).map((value) => ({ leg, key, value })),
    ),
  );

  it("builds at least one violating value for every rule of the table", () => {
    const ruleCount = LEG_IDS.reduce((sum, leg) => sum + Object.keys(contract.passFacts[leg]).length, 0);
    const covered = new Set(cases.map((entry) => `${entry.leg}.${entry.key}`));
    expect(covered.size).toBe(ruleCount);
    expect(ruleCount).toBe(49);
  });

  it.each(cases.map((entry) => [`${entry.leg}.${entry.key} = ${String(entry.value)}`, entry] as const))(
    "rejects a passed leg with a single violated fact: %s",
    (_label, { leg, key, value }) => {
      const { writer, lines } = memoryWriter();
      const facts = violatedFacts(leg, key, value);
      let error: unknown;
      try {
        writer.writeLeg({ leg, status: "passed", reason: "OK", http: [], facts });
      } catch (caught) {
        error = caught;
      }
      expect(error).toBeInstanceOf(LedgerValidationError);
      expect((error as LedgerValidationError).code).toBe("FACTS_CONTRADICT_PASS");
      // Only the code and the fact NAME travel: never the value.
      expect((error as Error).message).toBe(`FACTS_CONTRADICT_PASS: ${key}`);
      expect(lines).toHaveLength(0);
      expect(writer.hasWrittenLeg(leg)).toBe(false);
    },
  );

  it.each(cases.map((entry) => [`${entry.leg}.${entry.key} = ${String(entry.value)}`, entry] as const))(
    "leaves the same facts unconstrained on a failed leg: %s",
    (_label, { leg, key, value }) => {
      const { writer, lines } = memoryWriter();
      const facts = violatedFacts(leg, key, value);
      expect(() =>
        writer.writeLeg({ leg, status: "failed", reason: "ASSERTION_FAILED", http: [], facts }),
      ).not.toThrow();
      expect(lines).toHaveLength(1);
    },
  );

  it("fails on either side of an equalsFact rule", () => {
    const { writer } = memoryWriter();
    const base = CANONICAL_PASS_FACTS.L6;
    expect(() =>
      writer.writeLeg({ leg: "L6", status: "passed", reason: "OK", http: [], facts: { ...base, predictedBatchCount: 8 } }),
    ).toThrow(/FACTS_CONTRADICT_PASS: priceRequestsAfterUncheck/);
    expect(() =>
      writer.writeLeg({ leg: "L6", status: "passed", reason: "OK", http: [], facts: { ...base, priceRequestsAfterUncheck: 8 } }),
    ).toThrow(/FACTS_CONTRADICT_PASS: priceRequestsAfterUncheck/);
  });

  it("never believes a passed leg the contract has no pass table for, even if the loader's own check is bypassed", () => {
    // parseLedgerContract alone (no code-vocabulary assertion) accepts a table that omits L7.
    const withoutL7 = parseLedgerContract(withPassFacts((table) => { delete table.L7; }));
    const writer = new LedgerWriter({ contract: withoutL7, sink: { append: () => undefined } });
    expect(() =>
      writer.writeLeg({ leg: "L7", status: "passed", reason: "OK", http: [], facts: CANONICAL_PASS_FACTS.L7 }),
    ).toThrow(/FACTS_CONTRADICT_PASS/);
    // Legs that do have a table are unaffected, and an unconstrained failed L7 is still recorded.
    expect(() =>
      writer.writeLeg({ leg: "L0", status: "passed", reason: "OK", http: [], facts: CANONICAL_PASS_FACTS.L0 }),
    ).not.toThrow();
    expect(() =>
      writer.writeLeg({ leg: "L7", status: "failed", reason: "ASSERTION_FAILED", http: [], facts: {} }),
    ).not.toThrow();
  });

  it("keeps the completeness check ahead of the contradiction check", () => {
    const { writer } = memoryWriter();
    const incomplete: Record<string, FactValue> = { ...CANONICAL_PASS_FACTS.L4 };
    delete incomplete.catalogParity;
    expect(() =>
      writer.writeLeg({ leg: "L4", status: "passed", reason: "OK", http: [], facts: { ...incomplete, etagPresent: false } }),
    ).toThrow(/FACTS_INCOMPLETE_FOR_PASS: catalogParity/);
  });
});
