/**
 * Wave 10.2 Step B (exit criterion 5b) - per-leg execution harness and mutation gate.
 *
 * Every leg L0..L9 ends with exactly one final ledger event. A leg that throws is
 * recorded as failed (TIMEOUT for a Playwright TimeoutError, otherwise EXCEPTION)
 * and every later leg is recorded as skipped with SKIPPED_PRIOR_FAILURE, so the
 * test can always run to its end and the orchestrator can always run cleanup. The
 * error object itself is inspected only for its name; its message (which can quote
 * page content or a URL) is never read, let alone recorded.
 */
import { LEG_IDS } from "./contract";
import type { FailureReason, LegId, MutationSkippedReason, SkippedReason } from "./contract";
import { isRecord } from "./guards";
import { failureReasonForStatus, LedgerValidationError } from "./ledger";
import type { HttpEntry, LedgerWriterLike, LegFacts } from "./ledger";
import { isTimeoutError } from "./wait";

/** A response with one of these statuses on any leg is a failure (spec section 7); never retried. */
export const FORBIDDEN_LEG_STATUSES: ReadonlySet<number> = new Set([409, 429, 503, 504]);

/** The failure reason for the first forbidden status among a leg's http entries, if any. */
export function forbiddenStatusFailure(http: readonly HttpEntry[]): FailureReason | null {
  const offending = http.find((entry) => FORBIDDEN_LEG_STATUSES.has(entry.status));
  return offending === undefined ? null : failureReasonForStatus(offending.status);
}

/** What a leg reports. `reason: null` means the leg passed. */
export interface LegReport {
  readonly reason: FailureReason | null;
  readonly http: readonly HttpEntry[];
  readonly facts: LegFacts;
}

/** Accumulates a leg's checks; the first failure decides the reason code. */
export class LegCheck {
  private firstReason: FailureReason | null = null;

  fail(reason: FailureReason): void {
    if (this.firstReason === null) this.firstReason = reason;
  }

  failIf(condition: boolean, reason: FailureReason): void {
    if (condition) this.fail(reason);
  }

  get failed(): boolean {
    return this.firstReason !== null;
  }

  report(http: readonly HttpEntry[], facts: LegFacts): LegReport {
    return { reason: this.firstReason, http, facts };
  }
}

export function classifyError(error: unknown): FailureReason {
  if (isTimeoutError(error)) return "TIMEOUT";
  // expect() failures carry a matcherResult; anything else is an unexpected exception.
  if (isRecord(error) && "matcherResult" in error) return "ASSERTION_FAILED";
  return "EXCEPTION";
}

export function mutationSkippedReasonFor(reason: SkippedReason): Exclude<MutationSkippedReason, "NONE"> {
  switch (reason) {
    case "SKIPPED_PRIOR_FAILURE":
      return "LEG_FAILED";
    case "SKIPPED_VISITOR_PRESENT":
      return "VISITOR_PRESENT";
    case "SKIPPED_BASELINE_NOT_GOLDEN":
      return "BASELINE_NOT_GOLDEN";
  }
}

export interface MutationGateInput {
  /** Every one of L0..L7 passed. */
  readonly priorLegsPassed: boolean;
  /** STEP_B_5B_BASELINE_VERSION was supplied. */
  readonly baselineVersionSupplied: boolean;
  /** The page's own last portfolio read equals the golden holdings at the baseline version. */
  readonly pageMatchesBaseline: boolean;
  /** From L5; null when unknown. */
  readonly anotherSessionActive: boolean | null;
  readonly allowActivePresence: boolean;
}

export type MutationGateDecision =
  | { readonly run: true }
  | { readonly run: false; readonly legReason: SkippedReason };

/**
 * Mutate only if L0..L7 passed, the baseline is known and matches what the page
 * sees, and no other demo session is active (unless explicitly allowed). The first
 * failing condition names the skip reason. Absence of a visitor must be PROVEN
 * (`anotherSessionActive === false`); an unknown value is treated as a visitor.
 */
export function decideMutationGate(input: MutationGateInput): MutationGateDecision {
  if (!input.priorLegsPassed) return { run: false, legReason: "SKIPPED_PRIOR_FAILURE" };
  if (!input.baselineVersionSupplied || !input.pageMatchesBaseline) {
    return { run: false, legReason: "SKIPPED_BASELINE_NOT_GOLDEN" };
  }
  if (input.anotherSessionActive !== false && !input.allowActivePresence) {
    return { run: false, legReason: "SKIPPED_VISITOR_PRESENT" };
  }
  return { run: true };
}

export interface LegRunnerOptions {
  readonly writer: LedgerWriterLike;
  /**
   * Returns true when a run-wide safety violation has been observed (for example a
   * bearer token sent to a non-allowlisted origin). A leg that would otherwise pass
   * is then recorded as failed, and `finish()` evaluates it once more so a violation
   * that appears after the last leg was recorded still fails the run.
   */
  readonly guard?: () => boolean;
}

export class LegRunner {
  private readonly writer: LedgerWriterLike;
  private readonly guard: (() => boolean) | undefined;
  private failedFlag = false;
  private guardTrippedFlag = false;
  private readonly recorded = new Set<LegId>();

  constructor(options: LegRunnerOptions) {
    this.writer = options.writer;
    this.guard = options.guard;
  }

  /** True once any leg has been recorded as failed, or the run-wide guard tripped. */
  get failed(): boolean {
    return this.failedFlag;
  }

  /** True once the run-wide guard has been seen tripped, whether a leg was still running or not. */
  get guardTripped(): boolean {
    return this.guardTrippedFlag;
  }

  isRecorded(leg: LegId): boolean {
    return this.recorded.has(leg);
  }

  /** Runs one leg. Resolves true when it passed. A prior failure records the leg as skipped instead. */
  async run(leg: LegId, execute: () => Promise<LegReport>): Promise<boolean> {
    if (this.failedFlag) {
      this.skip(leg, "SKIPPED_PRIOR_FAILURE");
      return false;
    }

    let report: LegReport;
    try {
      report = await execute();
    } catch (error) {
      report = { reason: classifyError(error), http: [], facts: {} };
    }
    if (report.reason === null) {
      // A 409/429/503/504 among the leg's own requests fails it even if its other checks held.
      const forbidden = forbiddenStatusFailure(report.http);
      if (forbidden !== null) report = { ...report, reason: forbidden };
      else if (this.evaluateGuard()) report = { ...report, reason: "ASSERTION_FAILED" };
    }
    return this.record(leg, report);
  }

  /** Records a leg as failed without running it (for example a refused environment). */
  fail(leg: LegId, reason: FailureReason): void {
    this.record(leg, { reason, http: [], facts: {} });
  }

  /** Records a leg as skipped. L8/L9 carry the mutation gate reason as a fact. */
  skip(leg: LegId, reason: SkippedReason): void {
    const facts: LegFacts =
      leg === "L8" || leg === "L9" ? { mutationSkippedReason: mutationSkippedReasonFor(reason) } : {};
    this.writer.writeLeg({ leg, status: "skipped", reason, http: [], facts });
    this.recorded.add(leg);
  }

  /**
   * Guarantees exactly one final event per leg: anything not yet recorded becomes
   * failed (the first such leg, if nothing failed yet) or skipped. Then the run-wide
   * guard is evaluated once more: a bearer-carrying request to a non-allowlisted origin
   * emitted after the last leg was recorded cannot change an event already written, so it
   * marks the run failed instead, and the caller's non-zero exit makes the orchestrator
   * treat the run as NON_GO.
   */
  finish(): void {
    for (const leg of LEG_IDS) {
      if (this.recorded.has(leg)) continue;
      if (this.failedFlag) this.skip(leg, "SKIPPED_PRIOR_FAILURE");
      else this.record(leg, { reason: "EXCEPTION", http: [], facts: {} });
    }
    if (this.evaluateGuard()) this.failedFlag = true;
  }

  private evaluateGuard(): boolean {
    if (this.guard !== undefined && this.guard()) this.guardTrippedFlag = true;
    return this.guardTrippedFlag;
  }

  private record(leg: LegId, report: LegReport): boolean {
    const passed = report.reason === null;
    try {
      this.writer.writeLeg({
        leg,
        status: passed ? "passed" : "failed",
        reason: report.reason ?? "OK",
        http: report.http,
        facts: report.facts,
      });
    } catch (error) {
      if (!(error instanceof LedgerValidationError)) throw error;
      // The report broke the closed vocabulary, or a passed leg contradicted its own facts or its own request
      // evidence (FACTS_CONTRADICT_PASS, HTTP_CONTRADICT_PASS): keep the leg, drop its content.
      this.writer.writeLeg({ leg, status: "failed", reason: "EXCEPTION", http: [], facts: {} });
      this.recorded.add(leg);
      this.failedFlag = true;
      return false;
    }
    this.recorded.add(leg);
    if (!passed) this.failedFlag = true;
    return passed;
  }
}
