/**
 * Wave 10.2 Step B (exit criterion 5b) - typed view of the ledger contract.
 *
 * `../ledger-contract.json` is the machine-readable source of truth for every
 * vocabulary the browser child may write into the ledger (spec Appendix A). The
 * orchestrator loads the same file. This module parses it defensively and then
 * proves it still matches the literal unions below: if the JSON drifts, loading
 * throws and the verifier fails closed instead of running against a vocabulary
 * this code was not written for.
 *
 * Schema v2 added `passFacts`: for each leg, what its recorded facts must look like for a
 * `passed` status to be believed. It is parsed defensively here (a malformed table, an unknown
 * leg, a rule for a fact the leg does not have, or an unrecognised rule form all throw) and is
 * enforced by the ledger writer, so a leg whose own facts contradict its verdict can never reach
 * the ledger as passed. The orchestrator, which decides the verdict, enforces the same table.
 *
 * Schema v3 adds `passHttp`: what a passed leg's recorded `http` entries must look like (their method
 * and path, their number, and the status of the first or last one) so that a leg's facts can never
 * contradict its own request evidence. It is parsed with the same fail-closed discipline as `passFacts`
 * (an unknown leg, a missing leg, an unknown key, an entry rule outside the vocabulary, a count or status
 * fact that is not an integer fact of the leg, a position other than first/last, an empty `statusFacts` map,
 * `none` entries combined with any other key, or a status that is not an integer in 100..599 all throw) and
 * enforced by the ledger writer through `passHttpViolation`.
 *
 * The orchestrator loads the same file with the same rules, so the two readers accept and refuse the same
 * contracts. The one difference JavaScript cannot express: `JSON.parse` turns `200.0` into the integer 200, so
 * only the orchestrator can refuse a status written with a fraction; a test pins that the shipped file writes
 * none.
 *
 * Nothing here reads the environment; the JSON import is static data.
 */
import contractJson from "../ledger-contract.json";
import { hasExactKeys, hasOwn, isRecord, sameList } from "./guards";

export const CONTRACT_SCHEMA = "wave10-5b-ledger-contract-v3";

/**
 * Integer facts, integer pass literals and `min` bounds all live in 0..MAX_INTEGER_FACT. The orchestrator
 * refuses the same range, so a contract edit that only one reader would accept cannot load.
 */
export const MAX_INTEGER_FACT = 1_000_000_000;

/**
 * The ledger's structural vocabulary: the keys every line carries, who may write one, and which events each writer
 * may emit. The orchestrator pins the same four lists exactly (order included), so a contract edit that only one
 * reader would accept cannot load.
 */
export const COMMON_FIELDS = ["seq", "tUtc", "src", "event"] as const;
export const LEDGER_SOURCES = ["spec", "orchestrator"] as const;
export const SPEC_EVENTS = ["leg", "armed"] as const;
export const ORCHESTRATOR_EVENTS = ["child_started", "child_done", "cleanup"] as const;

export const LEG_IDS = ["L0", "L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8", "L9"] as const;
export type LegId = (typeof LEG_IDS)[number];

export const LEG_STATUSES = ["passed", "failed", "skipped"] as const;
export type LegStatus = (typeof LEG_STATUSES)[number];

export const REASONS = [
  "OK",
  "ASSERTION_FAILED",
  "TIMEOUT",
  "HTTP_STATUS_NOT_200",
  "RATE_LIMITED",
  "CONFLICT",
  "REQUEST_FAILED",
  "SKIPPED_PRIOR_FAILURE",
  "SKIPPED_VISITOR_PRESENT",
  "SKIPPED_BASELINE_NOT_GOLDEN",
  "EXCEPTION",
] as const;
export type Reason = (typeof REASONS)[number];

export const SKIPPED_REASONS = [
  "SKIPPED_PRIOR_FAILURE",
  "SKIPPED_VISITOR_PRESENT",
  "SKIPPED_BASELINE_NOT_GOLDEN",
] as const;
export type SkippedReason = (typeof SKIPPED_REASONS)[number];

/** A reason that may accompany a `failed` leg. */
export type FailureReason = Exclude<Reason, "OK" | SkippedReason>;

export const LEDGER_METHODS = ["GET", "PUT"] as const;
export type LedgerMethod = (typeof LEDGER_METHODS)[number];

export const LEDGER_PATHS = [
  "/api/portfolio",
  "/api/portfolio/summary",
  "/api/assets",
  "/api/presence/demo",
  "/api/market/prices",
  "/api/portfolio/holdings",
  "/api/portfolio/demo-reset",
] as const;
export type LedgerPath = (typeof LEDGER_PATHS)[number];

export const MUTATION_SKIPPED_REASONS = [
  "NONE",
  "LEG_FAILED",
  "VISITOR_PRESENT",
  "BASELINE_NOT_GOLDEN",
] as const;
export type MutationSkippedReason = (typeof MUTATION_SKIPPED_REASONS)[number];

export const FRESHNESS_STATES = ["FRESH", "STALE", "UNKNOWN", "MISSING", "ABSENT"] as const;
export type FreshnessState = (typeof FRESHNESS_STATES)[number];

export const FACT_TYPES = [
  "boolean",
  "integer",
  "boolean|null",
  "freshnessState",
  "mutationSkippedReason",
] as const;
export type FactType = (typeof FACT_TYPES)[number];

/** A literal a pass rule may compare a fact against. Never null: absence is a failure, not a value. */
export type PassLiteral = boolean | number | string;

/**
 * One parsed rule of `passFacts` (rule text: `passFactRules` in the contract).
 *
 * - `literal`: strict equality; booleans and integers are never coerced.
 * - `not`: the fact has the literal's own type and differs from it.
 * - `min`: the fact is an integer >= `min`.
 * - `boolean`: the fact is true or false, never null.
 * - `equalsFact`: the fact equals another fact of the same leg.
 */
export type PassRule =
  | { readonly kind: "literal"; readonly value: PassLiteral }
  | { readonly kind: "not"; readonly value: PassLiteral }
  | { readonly kind: "min"; readonly min: number }
  | { readonly kind: "boolean" }
  | { readonly kind: "equalsFact"; readonly fact: string };

/** Which recorded `http` entry a status fact is compared with. */
export type PassHttpPosition = "first" | "last";

/** What a passed leg's `http` list may contain: nothing, or requests of exactly one method and path. */
export type PassHttpEntries =
  | { readonly kind: "none" }
  | { readonly kind: "requests"; readonly method: string; readonly path: string };

/**
 * One parsed entry of `passHttp` (rule text: `passHttpRules` in the contract). A passed leg's recorded
 * `http` entries must satisfy every part that is present; failed and skipped legs are unconstrained.
 */
export interface PassHttpRule {
  readonly entries: PassHttpEntries;
  /** An integer fact that must equal the number of recorded http entries. */
  readonly countFact: string | null;
  /** Integer facts that must equal the status of the first or the last recorded http entry. */
  readonly statusFacts: Readonly<Record<string, PassHttpPosition>>;
  /** The first recorded entry's status must equal this. */
  readonly firstStatus: number | null;
  /** Every recorded entry's status must equal this. */
  readonly allStatus: number | null;
}

export interface LedgerContract {
  readonly schema: string;
  readonly commonFields: readonly string[];
  readonly sources: readonly string[];
  readonly specEvents: readonly string[];
  readonly orchestratorEvents: readonly string[];
  readonly legs: readonly string[];
  readonly statuses: readonly string[];
  readonly reasons: readonly string[];
  readonly methods: readonly string[];
  readonly paths: readonly string[];
  readonly mutationSkippedReasons: readonly string[];
  readonly freshnessStates: readonly string[];
  /** Human-readable statement of the rule forms; recorded so a reader of the JSON can audit `passFacts`. */
  readonly passFactRules: string;
  /** What a leg's facts must look like for `status: "passed"` to be believed. */
  readonly passFacts: Readonly<Record<string, Readonly<Record<string, PassRule>>>>;
  /** Human-readable statement of the http rule forms; recorded so a reader of the JSON can audit `passHttp`. */
  readonly passHttpRules: string;
  /** What a leg's http entries must look like for `status: "passed"` to be believed. */
  readonly passHttp: Readonly<Record<string, PassHttpRule>>;
  readonly legFacts: Readonly<Record<string, Readonly<Record<string, FactType>>>>;
}

export class LedgerContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "LedgerContractError";
  }
}

const TOP_LEVEL_KEYS = [
  "schema",
  "commonFields",
  "sources",
  "specEvents",
  "orchestratorEvents",
  "legs",
  "statuses",
  "reasons",
  "methods",
  "paths",
  "mutationSkippedReasons",
  "freshnessStates",
  "passFactRules",
  "passFacts",
  "passHttpRules",
  "passHttp",
  "legFacts",
] as const;

function stringArray(raw: Record<string, unknown>, key: string): readonly string[] {
  const value = raw[key];
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string")) {
    throw new LedgerContractError(`contract key '${key}' must be an array of strings`);
  }
  return value as string[];
}

function isFactType(value: unknown): value is FactType {
  return typeof value === "string" && (FACT_TYPES as readonly string[]).includes(value);
}

interface Enums {
  readonly freshnessStates: readonly string[];
  readonly mutationSkippedReasons: readonly string[];
}

/** True when `value` is a legal literal for a fact of `factType` (so a rule can never be unsatisfiable by type). */
function literalFitsFactType(value: unknown, factType: FactType, enums: Enums): value is PassLiteral {
  switch (factType) {
    case "boolean":
    case "boolean|null":
      return typeof value === "boolean";
    case "integer":
      // Bounded like an integer fact itself: a literal above MAX_INTEGER_FACT could never be satisfied.
      return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && value <= MAX_INTEGER_FACT;
    case "freshnessState":
      return typeof value === "string" && enums.freshnessStates.includes(value);
    case "mutationSkippedReason":
      return typeof value === "string" && enums.mutationSkippedReasons.includes(value);
  }
}

/** Parses one rule; anything that is not exactly one of the documented forms throws (fail closed). */
function parsePassRule(
  where: string,
  raw: unknown,
  factKey: string,
  factTypes: Readonly<Record<string, FactType>>,
  enums: Enums,
): PassRule {
  const factType = factTypes[factKey];
  if (typeof raw === "boolean" || typeof raw === "number" || typeof raw === "string") {
    if (!literalFitsFactType(raw, factType, enums)) {
      throw new LedgerContractError(`${where} is a literal that does not fit the fact type`);
    }
    return { kind: "literal", value: raw };
  }
  if (!isRecord(raw)) throw new LedgerContractError(`${where} is not a valid pass rule`);

  const forms = Object.keys(raw);
  if (forms.length !== 1) throw new LedgerContractError(`${where} must use exactly one rule form`);
  const form = forms[0];
  const argument = raw[form];

  if (form === "not") {
    if (!literalFitsFactType(argument, factType, enums)) {
      throw new LedgerContractError(`${where} 'not' needs a literal that fits the fact type`);
    }
    return { kind: "not", value: argument };
  }
  if (form === "min") {
    if (
      factType !== "integer" ||
      typeof argument !== "number" ||
      !Number.isSafeInteger(argument) ||
      argument < 0 ||
      argument > MAX_INTEGER_FACT
    ) {
      throw new LedgerContractError(`${where} 'min' needs a non-negative integer, within the fact range, on an integer fact`);
    }
    return { kind: "min", min: argument };
  }
  if (form === "type") {
    if (argument !== "boolean" || (factType !== "boolean" && factType !== "boolean|null")) {
      throw new LedgerContractError(`${where} 'type' only supports \"boolean\" on a boolean fact`);
    }
    return { kind: "boolean" };
  }
  if (form === "equalsFact") {
    if (
      typeof argument !== "string" ||
      argument === factKey ||
      !hasOwn(factTypes, argument) ||
      factTypes[argument] !== factType
    ) {
      throw new LedgerContractError(`${where} 'equalsFact' must name a different fact of the same leg and type`);
    }
    return { kind: "equalsFact", fact: argument };
  }
  throw new LedgerContractError(`${where} uses an unknown rule form`);
}

/** Fails closed on a malformed table, an unknown leg, or a rule for a fact the leg does not have. */
function parsePassFacts(
  raw: unknown,
  legFacts: Readonly<Record<string, Readonly<Record<string, FactType>>>>,
  enums: Enums,
): Record<string, Record<string, PassRule>> {
  if (!isRecord(raw)) throw new LedgerContractError("contract key 'passFacts' must be an object");
  const parsed: Record<string, Record<string, PassRule>> = {};
  for (const [leg, rules] of Object.entries(raw)) {
    if (!hasOwn(legFacts, leg)) throw new LedgerContractError(`passFacts.${leg} is not a leg described by legFacts`);
    if (!isRecord(rules)) throw new LedgerContractError(`passFacts.${leg} must be an object`);
    const factTypes = legFacts[leg];
    const legRules: Record<string, PassRule> = {};
    for (const [factKey, rawRule] of Object.entries(rules)) {
      if (!hasOwn(factTypes, factKey)) {
        throw new LedgerContractError(`passFacts.${leg}.${factKey} is not a fact of ${leg}`);
      }
      legRules[factKey] = parsePassRule(`passFacts.${leg}.${factKey}`, rawRule, factKey, factTypes, enums);
    }
    parsed[leg] = legRules;
  }
  return parsed;
}

/**
 * Does `value` satisfy `rule`? Strict throughout: nothing is coerced, and a value of the wrong
 * type never satisfies a rule. `facts` is the whole fact object of the same leg (for `equalsFact`).
 */
export function passRuleHolds(rule: PassRule, value: unknown, facts: Readonly<Record<string, unknown>>): boolean {
  switch (rule.kind) {
    case "literal":
      return value === rule.value;
    case "not":
      return typeof value === typeof rule.value && value !== rule.value;
    case "min":
      return typeof value === "number" && Number.isSafeInteger(value) && value >= rule.min;
    case "boolean":
      return typeof value === "boolean";
    case "equalsFact":
      return value !== undefined && hasOwn(facts, rule.fact) && value === facts[rule.fact];
  }
}

const PASS_HTTP_KEYS = ["entries", "countFact", "statusFacts", "firstStatus", "allStatus"] as const;

interface HttpVocabulary {
  readonly methods: readonly string[];
  readonly paths: readonly string[];
}

/** An http status a recorded entry can carry and a rule can demand: a real response, never 0 (no response). */
function isHttpStatusLiteral(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 100 && value <= 599;
}

function parsePassHttpEntries(where: string, raw: unknown, vocabulary: HttpVocabulary): PassHttpEntries {
  if (raw === "none") return { kind: "none" };
  if (!isRecord(raw) || !hasExactKeys(raw, ["method", "path"])) {
    throw new LedgerContractError(`${where} must be "none" or an object with exactly method and path`);
  }
  const { method, path } = raw;
  if (typeof method !== "string" || !vocabulary.methods.includes(method)) {
    throw new LedgerContractError(`${where}.method is not in the method vocabulary`);
  }
  if (typeof path !== "string" || !vocabulary.paths.includes(path)) {
    throw new LedgerContractError(`${where}.path is not in the path vocabulary`);
  }
  return { kind: "requests", method, path };
}

/** A fact a passHttp rule may compare with an http entry: it must exist in the leg and be an integer fact. */
function requireIntegerFact(where: string, key: unknown, factTypes: Readonly<Record<string, FactType>>): string {
  if (typeof key !== "string" || !hasOwn(factTypes, key) || factTypes[key] !== "integer") {
    throw new LedgerContractError(`${where} must name an integer fact of the leg`);
  }
  return key;
}

/** Parses one leg's http rule; anything that is not exactly the documented shape throws (fail closed). */
function parsePassHttpRule(
  where: string,
  raw: unknown,
  factTypes: Readonly<Record<string, FactType>>,
  vocabulary: HttpVocabulary,
): PassHttpRule {
  if (!isRecord(raw)) throw new LedgerContractError(`${where} must be an object`);
  for (const key of Object.keys(raw)) {
    if (!(PASS_HTTP_KEYS as readonly string[]).includes(key)) {
      throw new LedgerContractError(`${where} has a key outside the documented set`);
    }
  }
  if (!hasOwn(raw, "entries")) throw new LedgerContractError(`${where} must state 'entries'`);
  const entries = parsePassHttpEntries(`${where}.entries`, raw.entries, vocabulary);

  const countFact = hasOwn(raw, "countFact") ? requireIntegerFact(`${where}.countFact`, raw.countFact, factTypes) : null;

  const statusFacts: Record<string, PassHttpPosition> = {};
  if (hasOwn(raw, "statusFacts")) {
    const rawStatusFacts = raw.statusFacts;
    if (!isRecord(rawStatusFacts)) throw new LedgerContractError(`${where}.statusFacts must be an object`);
    // A key that is present but names nothing constrains nothing: it reads like a status rule and is not one.
    // The orchestrator refuses it too (passHttpRules: "statusFacts, when present, must be non-empty").
    if (Object.keys(rawStatusFacts).length === 0) {
      throw new LedgerContractError(`${where}.statusFacts must not be empty when present`);
    }
    for (const [fact, position] of Object.entries(rawStatusFacts)) {
      requireIntegerFact(`${where}.statusFacts.${fact}`, fact, factTypes);
      if (position !== "first" && position !== "last") {
        throw new LedgerContractError(`${where}.statusFacts.${fact} must be "first" or "last"`);
      }
      statusFacts[fact] = position;
    }
  }

  const readStatus = (key: "firstStatus" | "allStatus"): number | null => {
    if (!hasOwn(raw, key)) return null;
    const value = raw[key];
    if (!isHttpStatusLiteral(value)) throw new LedgerContractError(`${where}.${key} must be an integer http status`);
    return value;
  };
  const firstStatus = readStatus("firstStatus");
  const allStatus = readStatus("allStatus");

  // "none" means the list must be empty: there is no entry for a count-free status rule to look at, so any
  // further key is a table that could never be satisfied as written.
  if (entries.kind === "none" && Object.keys(raw).length !== 1) {
    throw new LedgerContractError(`${where} states 'none' entries and so cannot carry any other rule`);
  }
  return { entries, countFact, statusFacts, firstStatus, allStatus };
}

/** Fails closed on a malformed table, an unknown leg, a missing leg, or a rule that names something the leg lacks. */
function parsePassHttp(
  raw: unknown,
  legFacts: Readonly<Record<string, Readonly<Record<string, FactType>>>>,
  vocabulary: HttpVocabulary,
): Record<string, PassHttpRule> {
  if (!isRecord(raw)) throw new LedgerContractError("contract key 'passHttp' must be an object");
  for (const leg of Object.keys(raw)) {
    if (!hasOwn(legFacts, leg)) throw new LedgerContractError(`passHttp.${leg} is not a leg described by legFacts`);
  }
  const parsed: Record<string, PassHttpRule> = {};
  for (const leg of Object.keys(legFacts)) {
    // A leg with no http table would be believed on its facts alone, so every described leg needs one.
    if (!hasOwn(raw, leg)) throw new LedgerContractError(`passHttp is missing ${leg}`);
    parsed[leg] = parsePassHttpRule(`passHttp.${leg}`, raw[leg], legFacts[leg], vocabulary);
  }
  return parsed;
}

/** The part of a recorded http entry a passHttp rule looks at. */
export interface PassHttpEntryLike {
  readonly method: string;
  readonly path: string;
  readonly status: number;
}

/**
 * Does a passed leg's `http` list agree with `rule` and with the leg's own facts? Returns null when it
 * does, else a fixed label naming the violated part (`entries`, `count:<fact>`, `status:<fact>`,
 * `firstStatus`, `allStatus`): only a label and a fact NAME, never a status or a value. Strict throughout:
 * facts are compared with `===`, so nothing is coerced.
 */
export function passHttpViolation(
  rule: PassHttpRule,
  http: readonly PassHttpEntryLike[],
  facts: Readonly<Record<string, unknown>>,
): string | null {
  if (rule.entries.kind === "none") {
    if (http.length !== 0) return "entries";
  } else {
    const { method, path } = rule.entries;
    if (http.length === 0 || http.some((entry) => entry.method !== method || entry.path !== path)) return "entries";
  }

  if (rule.countFact !== null && !(hasOwn(facts, rule.countFact) && facts[rule.countFact] === http.length)) {
    return `count:${rule.countFact}`;
  }

  for (const [fact, position] of Object.entries(rule.statusFacts)) {
    const entry = position === "first" ? http[0] : http[http.length - 1];
    if (entry === undefined || !hasOwn(facts, fact) || facts[fact] !== entry.status) return `status:${fact}`;
  }

  if (rule.firstStatus !== null && (http.length === 0 || http[0].status !== rule.firstStatus)) return "firstStatus";
  if (rule.allStatus !== null && (http.length === 0 || http.some((entry) => entry.status !== rule.allStatus))) {
    return "allStatus";
  }
  return null;
}

/** Structural parse only: unknown top-level keys, missing keys and bad shapes throw. */
export function parseLedgerContract(raw: unknown): LedgerContract {
  if (!isRecord(raw)) throw new LedgerContractError("contract must be a JSON object");

  const actualKeys = Object.keys(raw).sort();
  const expectedKeys = [...TOP_LEVEL_KEYS].sort();
  if (!sameList(actualKeys, expectedKeys)) {
    throw new LedgerContractError("contract top-level keys differ from the pinned set");
  }
  if (raw.schema !== CONTRACT_SCHEMA) {
    throw new LedgerContractError("contract schema id is not the pinned one");
  }

  const legFactsRaw = raw.legFacts;
  if (!isRecord(legFactsRaw)) throw new LedgerContractError("contract key 'legFacts' must be an object");
  const legFacts: Record<string, Record<string, FactType>> = {};
  for (const [leg, facts] of Object.entries(legFactsRaw)) {
    if (!isRecord(facts)) throw new LedgerContractError(`legFacts.${leg} must be an object`);
    const parsed: Record<string, FactType> = {};
    for (const [factKey, factType] of Object.entries(facts)) {
      if (!isFactType(factType)) {
        throw new LedgerContractError(`legFacts.${leg}.${factKey} has an unknown fact type`);
      }
      parsed[factKey] = factType;
    }
    legFacts[leg] = parsed;
  }

  const mutationSkippedReasons = stringArray(raw, "mutationSkippedReasons");
  const freshnessStates = stringArray(raw, "freshnessStates");
  const passFactRules = raw.passFactRules;
  if (typeof passFactRules !== "string" || passFactRules.trim() === "") {
    throw new LedgerContractError("contract key 'passFactRules' must be a non-empty string");
  }
  const passFacts = parsePassFacts(raw.passFacts, legFacts, { freshnessStates, mutationSkippedReasons });

  const passHttpRules = raw.passHttpRules;
  if (typeof passHttpRules !== "string" || passHttpRules.trim() === "") {
    throw new LedgerContractError("contract key 'passHttpRules' must be a non-empty string");
  }
  const methods = stringArray(raw, "methods");
  const paths = stringArray(raw, "paths");
  const passHttp = parsePassHttp(raw.passHttp, legFacts, { methods, paths });

  return {
    schema: CONTRACT_SCHEMA,
    commonFields: stringArray(raw, "commonFields"),
    sources: stringArray(raw, "sources"),
    specEvents: stringArray(raw, "specEvents"),
    orchestratorEvents: stringArray(raw, "orchestratorEvents"),
    legs: stringArray(raw, "legs"),
    statuses: stringArray(raw, "statuses"),
    reasons: stringArray(raw, "reasons"),
    methods,
    paths,
    mutationSkippedReasons,
    freshnessStates,
    passFactRules,
    passFacts,
    passHttpRules,
    passHttp,
    legFacts,
  };
}

/** Throws when the parsed contract no longer matches the vocabulary this code was written for. */
export function assertContractMatchesCode(contract: LedgerContract): void {
  const checks: Array<[string, readonly string[], readonly string[]]> = [
    ["commonFields", contract.commonFields, COMMON_FIELDS],
    ["sources", contract.sources, LEDGER_SOURCES],
    ["specEvents", contract.specEvents, SPEC_EVENTS],
    ["orchestratorEvents", contract.orchestratorEvents, ORCHESTRATOR_EVENTS],
    ["legs", contract.legs, LEG_IDS],
    ["statuses", contract.statuses, LEG_STATUSES],
    ["reasons", contract.reasons, REASONS],
    ["methods", contract.methods, LEDGER_METHODS],
    ["paths", contract.paths, LEDGER_PATHS],
    ["mutationSkippedReasons", contract.mutationSkippedReasons, MUTATION_SKIPPED_REASONS],
    ["freshnessStates", contract.freshnessStates, FRESHNESS_STATES],
  ];
  for (const [name, fromContract, fromCode] of checks) {
    if (!sameList(fromContract, fromCode)) {
      throw new LedgerContractError(`contract vocabulary '${name}' drifted from the verifier code`);
    }
  }
  if (!sameList(Object.keys(contract.legFacts).sort(), [...LEG_IDS].sort())) {
    throw new LedgerContractError("contract legFacts must describe exactly the leg ids L0..L9");
  }
  for (const leg of LEG_IDS) {
    if (!hasOwn(contract.legFacts, leg)) {
      throw new LedgerContractError(`contract legFacts is missing ${leg}`);
    }
  }
  // A leg with no pass table would be believed on its status alone, so every leg needs one
  // that constrains at least one fact.
  if (!sameList(Object.keys(contract.passFacts).sort(), [...LEG_IDS].sort())) {
    throw new LedgerContractError("contract passFacts must describe exactly the leg ids L0..L9");
  }
  for (const leg of LEG_IDS) {
    if (Object.keys(contract.passFacts[leg]).length === 0) {
      throw new LedgerContractError(`contract passFacts.${leg} must constrain at least one fact`);
    }
  }
  // Likewise a leg with no http table would be believed on its facts alone.
  if (!sameList(Object.keys(contract.passHttp).sort(), [...LEG_IDS].sort())) {
    throw new LedgerContractError("contract passHttp must describe exactly the leg ids L0..L9");
  }
}

/** Loads (and verifies) the contract. Called from the test body, never at import time. */
export function loadLedgerContract(raw: unknown = contractJson): LedgerContract {
  const contract = parseLedgerContract(raw);
  assertContractMatchesCode(contract);
  return contract;
}
