/**
 * Exact value handling for oracles. Quantities are compared as normalized decimal
 * strings (the API returns "12.00000000" for "12"), never through floating point.
 * Displayed money is parsed from the rendered text, independently of the app's own
 * formatter, so a formatting bug cannot mirror itself into the expectation.
 */
export interface Holding {
  readonly ticker: string;
  readonly quantity: string;
}

const PLAIN_DECIMAL = /^\d+(\.\d+)?$/;

export function normalizeDecimal(value: string): string {
  if (!PLAIN_DECIMAL.test(value)) throw new Error(`not a plain non-negative decimal: ${JSON.stringify(value)}`);
  const [rawInteger, rawFraction = ""] = value.split(".");
  const integer = rawInteger.replace(/^0+(?=\d)/, "");
  const fraction = rawFraction.replace(/0+$/, "");
  return fraction ? `${integer}.${fraction}` : integer;
}

export function normalizeHoldings(holdings: readonly Holding[]): Holding[] {
  const seen = new Set<string>();
  return holdings
    .map(({ ticker, quantity }) => {
      if (seen.has(ticker)) throw new Error(`duplicate ticker ${ticker}`);
      seen.add(ticker);
      return { ticker, quantity: normalizeDecimal(quantity) };
    })
    .sort((a, b) => (a.ticker < b.ticker ? -1 : a.ticker > b.ticker ? 1 : 0));
}

export function holdingsEqual(actual: readonly Holding[], expected: readonly Holding[]): boolean {
  const a = normalizeHoldings(actual);
  const e = normalizeHoldings(expected);
  return a.length === e.length && a.every((h, i) => h.ticker === e[i].ticker && h.quantity === e[i].quantity);
}

const DISPLAYED_MONEY = /^([+-])?\$(\d{1,3}(,\d{3})*|\d+)(\.\d+)?$/;

/** Parses "$2,550.00", "-$1.00", "+$3.10"; returns null for the unavailable dash "—". */
export function parseDisplayedMoney(text: string): number | null {
  const trimmed = text.trim();
  if (trimmed === "—") return null;
  const match = DISPLAYED_MONEY.exec(trimmed);
  if (!match) throw new Error(`not a displayed USD amount: ${JSON.stringify(text)}`);
  const magnitude = Number(`${match[2].replace(/,/g, "")}${match[4] ?? ""}`);
  return match[1] === "-" ? -magnitude : magnitude;
}
