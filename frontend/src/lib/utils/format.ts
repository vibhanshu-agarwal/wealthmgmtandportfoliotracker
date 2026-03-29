/**
 * Shared formatting helpers for financial values.
 * All functions are pure and locale-aware.
 */

const USD = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const USD_COMPACT = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  notation: "compact",
  maximumFractionDigits: 2,
});

const PCT = new Intl.NumberFormat("en-US", {
  style: "percent",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
  signDisplay: "always",
});

const NUM = new Intl.NumberFormat("en-US", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 8,
});

/** "$1,234.56" */
export function formatCurrency(value: number): string {
  return USD.format(value);
}

/** "$1.23K" / "$4.56M" — for compact summary cards */
export function formatCurrencyCompact(value: number): string {
  return USD_COMPACT.format(value);
}

/** "+1.24%" / "-0.83%" — always shows sign */
export function formatPercent(value: number): string {
  return PCT.format(value / 100);
}

/** Generic number with up to 8 decimal places (for crypto quantities) */
export function formatQuantity(value: number): string {
  return NUM.format(value);
}

/** "+$1,234.56" / "-$234.00" — signed dollar amount */
export function formatSignedCurrency(value: number): string {
  const formatted = USD.format(Math.abs(value));
  return value >= 0 ? `+${formatted}` : `-${formatted}`;
}

/** "Mar 29, 2026" from an ISO-8601 string */
export function formatDate(isoString: string): string {
  return new Date(isoString).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

/** "Mar 29" — short form for chart axis labels */
export function formatDateShort(isoString: string): string {
  return new Date(isoString).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
  });
}
