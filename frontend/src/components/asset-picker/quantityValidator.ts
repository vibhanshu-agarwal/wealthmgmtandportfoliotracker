/**
 * B2 Task 1.8 — the string-domain quantity validator.
 *
 * Enforces B1's Quantity_Domain client-side (required, strictly positive, at most 11
 * integer and 8 fractional digits, malformed decimal text rejected) so a doomed request
 * never reaches the server, and so the picker can block progression before submission.
 *
 * The value is validated as the exact string the input holds — never trimmed, coerced, or
 * round-tripped through a number — because the submit payload must be byte-identical to
 * what the user typed (GC.2), trailing zeros included.
 */


export type QuantityValidationResult =
  | { valid: true }
  | { valid: false; message: string };

export const QUANTITY_REQUIRED_MESSAGE = "Quantity is required.";
export const QUANTITY_MALFORMED_MESSAGE =
  "Enter a quantity as a plain decimal number, for example 12.5.";
export const QUANTITY_NOT_POSITIVE_MESSAGE = "Quantity must be greater than zero.";
export const QUANTITY_INTEGER_DIGITS_MESSAGE =
  "Quantity may have at most 11 digits before the decimal point.";
export const QUANTITY_FRACTION_DIGITS_MESSAGE =
  "Quantity may have at most 8 digits after the decimal point.";
export const QUANTITY_DEPRECATED_INCREASE_MESSAGE =
  "This asset is no longer offered. You can reduce or remove this holding, but not increase it.";

/** B1's Quantity_Domain upper bound is 99999999999.99999999. */
const MAX_INTEGER_DIGITS = 11;
const MAX_FRACTION_DIGITS = 8;

/**
 * Exact decimal-string comparison for two values already confirmed well-formed by
 * `validateDraftQuantity` (plain unsigned digits, optional single `.`).
 *
 * Deliberately NOT `compareQuantityStrings` from `@/lib/utils/quantityDisplay` —
 * that comparator converts through `Number()`, which is exactly precise enough for
 * display/sort but silently equates distinct values at the domain's own boundary:
 * `Number("99999999999.00000000") === Number("99999999999.00000001")` is `true`,
 * since 20 significant digits exceed IEEE-754 double precision. Using it here would
 * let a `Retained_Deprecated_Position`'s reduce-or-remove-only enforcement (GC.2's
 * own display-boundary spirit — no arithmetic conversion for an enforcement
 * decision) silently accept a real increase as "unchanged". This walks both digit
 * strings without ever parsing them into a number.
 */
function compareUnsignedDecimalStrings(a: string, b: string): number {
  const [aInt, aFrac = ""] = a.split(".");
  const [bInt, bFrac = ""] = b.split(".");

  const aIntTrimmed = aInt.replace(/^0+(?=\d)/, "");
  const bIntTrimmed = bInt.replace(/^0+(?=\d)/, "");
  if (aIntTrimmed.length !== bIntTrimmed.length) {
    return aIntTrimmed.length < bIntTrimmed.length ? -1 : 1;
  }
  if (aIntTrimmed !== bIntTrimmed) {
    return aIntTrimmed < bIntTrimmed ? -1 : 1;
  }

  const fracWidth = Math.max(aFrac.length, bFrac.length);
  const aFracPadded = aFrac.padEnd(fracWidth, "0");
  const bFracPadded = bFrac.padEnd(fracWidth, "0");
  if (aFracPadded === bFracPadded) return 0;
  return aFracPadded < bFracPadded ? -1 : 1;
}

/** A plain, unsigned decimal — no exponent, no separators, no surrounding whitespace. */
const PLAIN_DECIMAL = /^(\d+)(?:\.(\d+))?$/;

const invalid = (message: string): QuantityValidationResult => ({ valid: false, message });

/**
 * Validates a draft quantity against B1's Quantity_Domain.
 *
 * A negative value is reported as "not greater than zero" rather than "malformed", since
 * the shape is recognisable and the magnitude is what fails.
 */
export function validateDraftQuantity(rawValue: string): QuantityValidationResult {
  if (rawValue.trim() === "") return invalid(QUANTITY_REQUIRED_MESSAGE);

  const isNegative = rawValue.startsWith("-");
  const unsigned = isNegative ? rawValue.slice(1) : rawValue;

  const match = PLAIN_DECIMAL.exec(unsigned);
  if (!match) return invalid(QUANTITY_MALFORMED_MESSAGE);

  const [, integerDigits, fractionDigits = ""] = match;
  if (integerDigits.length > MAX_INTEGER_DIGITS) return invalid(QUANTITY_INTEGER_DIGITS_MESSAGE);
  if (fractionDigits.length > MAX_FRACTION_DIGITS) return invalid(QUANTITY_FRACTION_DIGITS_MESSAGE);

  const hasNonZeroDigit = /[1-9]/.test(integerDigits + fractionDigits);
  if (isNegative || !hasNonZeroDigit) return invalid(QUANTITY_NOT_POSITIVE_MESSAGE);

  return { valid: true };
}

/**
 * Validates a `Retained_Deprecated_Position`'s quantity: the domain rules above, plus
 * requirements.md 2.4's reduce-or-remove-only constraint.
 *
 * The comparison against the open-time ceiling runs through the GC.2 display boundary's
 * numeric comparator, so "10.00000000" is recognised as equal to "10" rather than greater.
 * This client-side check — not the input's `max` attribute, which is inert on a
 * text-mode `inputmode="decimal"` control — is the real enforcement.
 */
export function validateRetainedDeprecatedQuantity(
  rawValue: string,
  ceilingQuantity: string,
): QuantityValidationResult {
  const base = validateDraftQuantity(rawValue);
  if (!base.valid) return base;

  if (compareUnsignedDecimalStrings(rawValue, ceilingQuantity) > 0) {
    return invalid(QUANTITY_DEPRECATED_INCREASE_MESSAGE);
  }
  return { valid: true };
}
