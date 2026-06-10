/**
 * Task 9.9 — Vitest tests for format.ts nullable/unavailable helpers.
 *
 * Requirements validated:
 * - R8 AC3: null → "—", never $0.00 / 0.00%
 * - Property 2: No silent zero — absence is always "—"
 * - Task 9.1: formatCurrencyOrDash, formatRelativeAge, formatDateOrDash helpers
 */

import { describe, it, expect, vi, afterEach } from "vitest";
import {
  formatCurrency,
  formatPercent,
  formatSignedCurrency,
  formatSignedCurrencyOrDash,
  formatPercentOrDash,
  formatCurrencyOrDash,
  formatRelativeAge,
  formatDateOrDash,
  formatDate,
} from "./format";

// ── formatSignedCurrencyOrDash ────────────────────────────────────────────────

describe("formatSignedCurrencyOrDash", () => {
  it('returns "—" for null', () => {
    expect(formatSignedCurrencyOrDash(null)).toBe("—");
  });

  it('returns "—" for undefined', () => {
    expect(formatSignedCurrencyOrDash(undefined)).toBe("—");
  });

  it("returns formatted signed currency for positive value", () => {
    expect(formatSignedCurrencyOrDash(1234.56)).toBe("+$1,234.56");
  });

  it("returns formatted signed currency for negative value", () => {
    expect(formatSignedCurrencyOrDash(-567.89)).toBe("-$567.89");
  });

  it("returns formatted signed currency for zero", () => {
    expect(formatSignedCurrencyOrDash(0)).toBe("+$0.00");
  });

  it("never returns $0.00 for null (Property 2: No silent zero)", () => {
    expect(formatSignedCurrencyOrDash(null)).not.toBe("$0.00");
    expect(formatSignedCurrencyOrDash(null)).not.toBe("+$0.00");
  });
});

// ── formatPercentOrDash ───────────────────────────────────────────────────────

describe("formatPercentOrDash", () => {
  it('returns "—" for null', () => {
    expect(formatPercentOrDash(null)).toBe("—");
  });

  it('returns "—" for undefined', () => {
    expect(formatPercentOrDash(undefined)).toBe("—");
  });

  it("returns formatted percent for positive value", () => {
    expect(formatPercentOrDash(5.26)).toBe("+5.26%");
  });

  it("returns formatted percent for negative value", () => {
    expect(formatPercentOrDash(-2.14)).toBe("-2.14%");
  });

  it("returns formatted percent for zero", () => {
    expect(formatPercentOrDash(0)).toBe("+0.00%");
  });

  it("never returns +0.00% for null (Property 2: No silent zero)", () => {
    expect(formatPercentOrDash(null)).not.toBe("+0.00%");
    expect(formatPercentOrDash(null)).not.toBe("0.00%");
  });
});

// ── formatCurrencyOrDash ──────────────────────────────────────────────────────

describe("formatCurrencyOrDash", () => {
  it('returns "—" for null', () => {
    expect(formatCurrencyOrDash(null)).toBe("—");
  });

  it('returns "—" for undefined', () => {
    expect(formatCurrencyOrDash(undefined)).toBe("—");
  });

  it("returns formatted currency for a value", () => {
    expect(formatCurrencyOrDash(1000)).toBe("$1,000.00");
  });

  it("returns $0.00 for genuine zero — zero IS valid here", () => {
    expect(formatCurrencyOrDash(0)).toBe("$0.00");
  });

  it("never returns $0.00 for null (Property 2: No silent zero)", () => {
    expect(formatCurrencyOrDash(null)).not.toBe("$0.00");
  });
});

// ── formatRelativeAge ─────────────────────────────────────────────────────────

describe("formatRelativeAge", () => {
  it('returns "—" for null', () => {
    expect(formatRelativeAge(null)).toBe("—");
  });

  it('returns "—" for undefined', () => {
    expect(formatRelativeAge(undefined)).toBe("—");
  });

  it('returns "—" for invalid ISO string', () => {
    expect(formatRelativeAge("not-a-date")).toBe("—");
  });

  it('returns "just now" for a timestamp <1 min ago', () => {
    const ts = new Date(Date.now() - 30_000).toISOString();
    expect(formatRelativeAge(ts)).toBe("just now");
  });

  it('returns "N min ago" for a timestamp N minutes ago', () => {
    const ts = new Date(Date.now() - 5 * 60_000).toISOString();
    expect(formatRelativeAge(ts)).toBe("5 min ago");
  });

  it('returns "N hr ago" for a timestamp N hours ago', () => {
    const ts = new Date(Date.now() - 3 * 3_600_000).toISOString();
    expect(formatRelativeAge(ts)).toBe("3 hr ago");
  });

  it('returns "N days ago" for a timestamp N days ago', () => {
    const ts = new Date(Date.now() - 2 * 86_400_000).toISOString();
    expect(formatRelativeAge(ts)).toBe("2 days ago");
  });

  it('returns "1 day ago" (singular) for one day ago', () => {
    const ts = new Date(Date.now() - 86_400_000).toISOString();
    expect(formatRelativeAge(ts)).toBe("1 day ago");
  });

  it("returns a formatted date for timestamps >30 days ago", () => {
    const ts = new Date(Date.now() - 35 * 86_400_000).toISOString();
    const result = formatRelativeAge(ts);
    // Should be a month-day-year formatted date, not a relative string
    expect(result).not.toContain("ago");
    expect(result.length).toBeGreaterThan(4);
  });

  it("never returns 'now()' fabricated time for missing data (R8 AC4)", () => {
    // A null/undefined timestamp must never produce the current time as text
    const nowFormatted = new Date().toLocaleDateString("en-US", {
      month: "short",
      day: "numeric",
      year: "numeric",
    });
    expect(formatRelativeAge(null)).not.toBe(nowFormatted);
    expect(formatRelativeAge(null)).toBe("—");
  });
});

// ── formatDateOrDash ──────────────────────────────────────────────────────────

describe("formatDateOrDash", () => {
  it('returns "—" for null', () => {
    expect(formatDateOrDash(null)).toBe("—");
  });

  it('returns "—" for undefined', () => {
    expect(formatDateOrDash(undefined)).toBe("—");
  });

  it("returns formatted date for a valid ISO string", () => {
    expect(formatDateOrDash("2026-06-10T00:00:00Z")).toBe("Jun 10, 2026");
  });

  it("never fabricates current date for null (R8 AC4: no now() fabrication)", () => {
    const today = new Date().toLocaleDateString("en-US", {
      month: "short",
      day: "numeric",
      year: "numeric",
    });
    expect(formatDateOrDash(null)).not.toBe(today);
  });
});
