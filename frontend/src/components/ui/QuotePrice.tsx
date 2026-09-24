import { formatQuotePrice, formatSignedQuotePrice, isKnownCurrency } from "@/lib/utils/format";

interface QuotePriceProps {
  value: number;
  /** ISO 4217 code the value is quoted in; null/undefined when the source did not say. */
  currency: string | null | undefined;
  signed?: boolean;
  className?: string;
}

/**
 * A per-unit market price in its own quote currency. When the currency is missing or not a
 * recognised ISO code the bare number is shown — never "$" — and the element says why.
 */
export function QuotePrice({ value, currency, signed = false, className }: QuotePriceProps) {
  const text = signed ? formatSignedQuotePrice(value, currency) : formatQuotePrice(value, currency);
  return (
    <span className={className} title={isKnownCurrency(currency) ? undefined : "Currency unavailable"}>
      {text}
    </span>
  );
}
