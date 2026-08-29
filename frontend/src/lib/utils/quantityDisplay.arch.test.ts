/**
 * B2 GC.2 (executable assertion) — an architecture check that fails the build if
 * arithmetic is applied directly to a `quantity` field outside the explicitly named
 * display-value helper.
 *
 * GC.2 requires quantity to be a string end-to-end, never a parsed number, except at a
 * display boundary. `@/lib/utils/quantityDisplay` is that boundary and the only module
 * allowed to convert; every other module has to route through it.
 */
import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const SRC_ROOT = path.resolve(__dirname, "../..");

/** The one module allowed to convert a quantity string into a number. */
const DISPLAY_BOUNDARY = path.join("lib", "utils", "quantityDisplay.ts");

/**
 * Arithmetic applied directly to a `.quantity` member access, in either operand
 * position, plus direct numeric parsing of one.
 *
 * `\b` after `quantity` keeps `.quantityFidelityUnverified` out of the match.
 */
const FORBIDDEN_PATTERNS: ReadonlyArray<{ label: string; pattern: RegExp }> = [
  { label: "arithmetic on a quantity field", pattern: /\.quantity\b\s*[*/+%-](?!=)/ },
  { label: "quantity used as a right-hand operand", pattern: /[*/+%-]\s*[\w.]*\.quantity\b/ },
  { label: "direct numeric parse of a quantity field", pattern: /(?:Number|parseFloat|parseInt)\(\s*[\w.]*\.quantity\b/ },
];

function collectSourceFiles(dir: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      found.push(...collectSourceFiles(full));
      continue;
    }
    if (!/\.tsx?$/.test(entry.name)) continue;
    // Tests may exercise the boundary directly; they are not production call sites.
    if (/\.test\.tsx?$/.test(entry.name)) continue;
    found.push(full);
  }
  return found;
}

describe("GC.2 — quantity arithmetic is confined to the display boundary", () => {
  const sourceFiles = collectSourceFiles(SRC_ROOT).filter(
    (file) => !file.endsWith(DISPLAY_BOUNDARY),
  );

  it("scans a non-trivial set of source files", () => {
    // Guards against the check silently passing because it found nothing to read.
    expect(sourceFiles.length).toBeGreaterThan(20);
  });

  it("finds no direct arithmetic on a quantity field outside quantityDisplay", () => {
    const violations: string[] = [];

    for (const file of sourceFiles) {
      const lines = readFileSync(file, "utf-8").split(/\r?\n/);
      lines.forEach((line, index) => {
        for (const { label, pattern } of FORBIDDEN_PATTERNS) {
          if (pattern.test(line)) {
            violations.push(
              `${path.relative(SRC_ROOT, file)}:${index + 1} — ${label}: ${line.trim()}`,
            );
          }
        }
      });
    }

    expect(violations).toEqual([]);
  });

  it("detects a violation when one is introduced", () => {
    // Proves the patterns actually match, so a green run means "no violations found",
    // not "the regexes never match anything".
    const offending = [
      "const total = holding.quantity * price;",
      "const total = price * holding.quantity;",
      "const n = Number(holding.quantity);",
    ];
    for (const line of offending) {
      expect(FORBIDDEN_PATTERNS.some(({ pattern }) => pattern.test(line))).toBe(true);
    }
  });

  it("does not flag the provenance flag or plain field reads", () => {
    const benign = [
      "if (holding.quantityFidelityUnverified === true) return;",
      "const { quantity } = holding;",
      "quantity: h.quantity,",
      'if (sortKey === "quantity") return 0;',
    ];
    for (const line of benign) {
      expect(FORBIDDEN_PATTERNS.some(({ pattern }) => pattern.test(line))).toBe(false);
    }
  });
});
