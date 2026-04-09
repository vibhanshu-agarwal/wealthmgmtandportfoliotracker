-- V5: Add quote_currency to market_prices so each asset's pricing currency is stored.
-- Existing rows default to 'USD' (the legacy assumption), preserving backward compatibility.
ALTER TABLE market_prices
    ADD COLUMN IF NOT EXISTS quote_currency VARCHAR(10) NOT NULL DEFAULT 'USD';
