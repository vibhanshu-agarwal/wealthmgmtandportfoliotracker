-- =============================================================================
-- V2: Baseline seeded market data (50 points each for AAPL, TSLA, BTC/USD)
-- =============================================================================

CREATE TABLE IF NOT EXISTS market_price_history
(
    id             BIGSERIAL PRIMARY KEY,
    ticker         VARCHAR(20)    NOT NULL,
    quote_currency VARCHAR(10)    NOT NULL DEFAULT 'USD',
    price          NUMERIC(19, 4) NOT NULL,
    observed_at    TIMESTAMP      NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_market_price_history_ticker_observed_at
    ON market_price_history (ticker, observed_at);

WITH points AS (
    SELECT generate_series(1, 50) AS seq
)
INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
SELECT 'AAPL',
       'USD',
       (170.0000 + (seq * 0.8500))::NUMERIC(19, 4),
       now() - ((51 - seq) * INTERVAL '1 day')
FROM points
UNION ALL
SELECT 'TSLA',
       'USD',
       (220.0000 + (seq * 1.1200))::NUMERIC(19, 4),
       now() - ((51 - seq) * INTERVAL '1 day')
FROM points
UNION ALL
SELECT 'BTC',
       'USD',
       (62000.0000 + (seq * 175.5000))::NUMERIC(19, 4),
       now() - ((51 - seq) * INTERVAL '1 day')
FROM points;

INSERT INTO market_prices (ticker, current_price, updated_at)
VALUES ('AAPL', 212.5000, now()),
       ('TSLA', 276.0000, now()),
       ('BTC', 70775.0000, now())
ON CONFLICT (ticker) DO UPDATE
SET current_price = EXCLUDED.current_price,
    updated_at = EXCLUDED.updated_at;
