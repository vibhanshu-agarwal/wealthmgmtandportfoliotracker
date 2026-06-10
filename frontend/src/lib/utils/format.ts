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

/**
 * Renders a nullable signed dollar amount.
 * Returns "—" when the value is null/undefined (typed-unavailable, not $0.00).
 */
export function formatSignedCurrencyOrDash(value: number | null | undefined): string {
  if (value == null) return "—";
  return formatSignedCurrency(value);
}

/**
 * Renders a nullable percentage value.
 * Returns "—" when the value is null/undefined (typed-unavailable, not 0.00%).
 */
export function formatPercentOrDash(value: number | null | undefined): string {
  if (value == null) return "—";
  return formatPercent(value);
}

/**
 * Renders a nullable dollar amount (unsigned).
 * Returns "—" when the value is null/undefined (typed-unavailable, not $0.00).
 */
export function formatCurrencyOrDash(value: number | null | undefined): string {
  if (value == null) return "—";
  return formatCurrency(value);
}

/**
 * Renders an ISO-8601 timestamp as a relative age string ("5 min ago", "2 hr ago", "3 days ago").
 * Falls back to the formatted date when the string is null/undefined.
 */
export function formatRelativeAge(isoString: string | null | undefined): string {
  if (!isoString) return "—";
  const now = Date.now();
  const ts = new Date(isoString).getTime();
  if (Number.isNaN(ts)) return "—";
  const diffMs = now - ts;
  if (diffMs < 0) return formatDate(isoString); // future timestamp — just show date
  const diffMin = Math.floor(diffMs / 60_000);
  if (diffMin < 1) return "just now";
  if (diffMin < 60) return `${diffMin} min ago`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr} hr ago`;
  const diffDays = Math.floor(diffHr / 24);
  if (diffDays < 30) return `${diffDays} day${diffDays === 1 ? "" : "s"} ago`;
  return formatDate(isoString);
}

/**
 * Renders a nullable ISO-8601 date string.
 * Returns "—" when the value is null/undefined (typed-unavailable, not now()).
 */
export function formatDateOrDash(isoString: string | null | undefined): string {
  if (!isoString) return "—";
  return formatDate(isoString);
}
