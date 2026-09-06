import { selectPortfolioVersion } from "./portfolio-seed-version";

export type CompositionHolding = {
  ticker: string;
  quantity: string;
};

export type ObservedPortfolio = {
  id: string;
  userId: string;
  version: number;
  holdings: CompositionHolding[];
};

const DETERMINISTIC_HOLDING_SETS: readonly CompositionHolding[][] = [
  [
    { ticker: "AAPL", quantity: "17" },
    { ticker: "BTC-USD", quantity: "2" },
  ],
  [
    { ticker: "AAPL", quantity: "23" },
    { ticker: "BTC-USD", quantity: "3" },
  ],
  [
    { ticker: "AAPL", quantity: "29" },
    { ticker: "BTC-USD", quantity: "5" },
  ],
];

function normalizedHoldings(holdings: readonly CompositionHolding[]): CompositionHolding[] {
  const seen = new Set<string>();
  const normalized = holdings.map(({ ticker, quantity }) => {
    if (!ticker.trim() || !quantity.trim()) {
      throw new Error("[asset-picker-real] holdings must contain non-blank ticker and quantity strings");
    }
    if (seen.has(ticker)) {
      throw new Error(`[asset-picker-real] holdings contain duplicate ticker ${ticker}`);
    }
    seen.add(ticker);
    return { ticker, quantity };
  });
  return normalized.sort((left, right) => left.ticker.localeCompare(right.ticker));
}

function holdingsAreExactlyEqual(
  actual: readonly CompositionHolding[],
  expected: readonly CompositionHolding[],
): boolean {
  const normalizedActual = normalizedHoldings(actual);
  const normalizedExpected = normalizedHoldings(expected);
  return (
    normalizedActual.length === normalizedExpected.length &&
    normalizedActual.every(
      (holding, index) =>
        holding.ticker === normalizedExpected[index]?.ticker &&
        holding.quantity === normalizedExpected[index]?.quantity,
    )
  );
}

function exactHoldingsMessage(holdings: readonly CompositionHolding[]): string {
  return JSON.stringify(normalizedHoldings(holdings));
}

/** Selects exactly one identity-matched wire portfolio and validates its fields. */
export function selectExactPortfolio(payload: unknown, expectedUserId: string): ObservedPortfolio {
  const version = selectPortfolioVersion(payload, expectedUserId);
  const matches = (payload as Array<unknown>).filter(
    (item): item is Record<string, unknown> =>
      typeof item === "object" &&
      item !== null &&
      (item as Record<string, unknown>).userId === expectedUserId,
  );
  const portfolio = matches[0]!;
  if (typeof portfolio.id !== "string" || !portfolio.id.trim()) {
    throw new Error("[asset-picker-real] identity-matched portfolio must carry a non-blank id");
  }
  if (!Array.isArray(portfolio.holdings)) {
    throw new Error("[asset-picker-real] identity-matched portfolio holdings must be an array");
  }

  const holdings = portfolio.holdings.map((holding): CompositionHolding => {
    if (typeof holding !== "object" || holding === null) {
      throw new Error("[asset-picker-real] portfolio holding must be an object");
    }
    const record = holding as Record<string, unknown>;
    if (typeof record.assetTicker !== "string" || typeof record.quantity !== "string") {
      throw new Error(
        "[asset-picker-real] portfolio holding must carry string assetTicker and quantity fields",
      );
    }
    return { ticker: record.assetTicker, quantity: record.quantity };
  });

  return { id: portfolio.id, userId: expectedUserId, version, holdings: normalizedHoldings(holdings) };
}

/** A deterministic setup or picker save must change the optimistic-lock version. */
export function assertVersionAdvanced(label: string, previousVersion: number, nextVersion: number): void {
  if (!Number.isSafeInteger(nextVersion) || nextVersion <= previousVersion) {
    throw new Error(
      `[asset-picker-real] ${label} must strictly advance version: ${nextVersion} is not greater than ${previousVersion}`,
    );
  }
}

/** Confirms the post-save read has no omitted, extra, or quantity-mismatched holdings. */
export function assertExactPersistedHoldings(
  actual: readonly CompositionHolding[],
  expected: readonly CompositionHolding[],
): void {
  if (!holdingsAreExactlyEqual(actual, expected)) {
    throw new Error(
      `[asset-picker-real] persisted holdings must equal the edited draft exactly; actual=${exactHoldingsMessage(actual)} expected=${exactHoldingsMessage(expected)}`,
    );
  }
}

/** Avoids same-state direct setup writes, which do not advance the real version. */
export function chooseKnownDifferentHoldings(
  observed: readonly CompositionHolding[],
): CompositionHolding[] {
  const candidate = DETERMINISTIC_HOLDING_SETS.find(
    (set) => !holdingsAreExactlyEqual(observed, set),
  );
  if (!candidate) {
    throw new Error("[asset-picker-real] no deterministic setup set differs from observed holdings");
  }
  return candidate.map((holding) => ({ ...holding }));
}
