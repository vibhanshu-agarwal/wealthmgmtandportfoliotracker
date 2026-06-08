-- =============================================================================
-- V12: Backfill market_price_history for all 160 canonical seed tickers
--
-- Generates 4 windowed historical points per ticker (T-3d, T-2d, T-1d, T-0d)
-- anchored at a FIXED DETERMINISTIC timestamp (2026-01-01 12:00:00 UTC) so that:
--   (a) rows have stable (ticker, observed_at) identity regardless of when the
--       migration runs, and
--   (b) ON CONFLICT DO NOTHING is effective after V13 creates the unique index.
--
-- Using now() would produce different observed_at values on every re-execution,
-- defeating the unique-index idempotency guarantee from V13.
--
-- Anchor: 2026-01-01 12:00:00 UTC (pre-dates first real daily refresh on Azure;
-- always in the past so analytics immediately has a usable reference point).
--
-- Idempotency: ON CONFLICT DO NOTHING.  Because observed_at is derived from fixed
-- literals, re-running this SQL at any wall-clock time produces the same
-- (ticker, observed_at) pairs and the unique index from V13 suppresses duplicates.
--
-- This migration does NOT rewrite V2 (which covers AAPL, TSLA, BTC legacy).
-- Data source: config/seed-tickers.json (160 canonical tickers)
-- =============================================================================

-- ── US_EQUITY (50 tickers, quoteCurrency=USD) ─────────────────────────────
WITH backfill_tickers(ticker, quote_currency, base_price) AS (VALUES
  ('AAPL',   'USD',  195.89),
  ('MSFT',   'USD',  415.50),
  ('GOOGL',  'USD',  175.20),
  ('AMZN',   'USD',  183.50),
  ('META',   'USD',  502.30),
  ('NVDA',   'USD',  880.10),
  ('TSLA',   'USD',  175.80),
  ('BRK-B',  'USD',  412.00),
  ('JPM',    'USD',  198.50),
  ('V',      'USD',  275.00),
  ('WMT',    'USD',   60.25),
  ('UNH',    'USD',  490.00),
  ('XOM',    'USD',  115.00),
  ('JNJ',    'USD',  155.40),
  ('PG',     'USD',  162.30),
  ('MA',     'USD',  470.20),
  ('HD',     'USD',  350.00),
  ('AVGO',   'USD', 1320.00),
  ('CVX',    'USD',  155.50),
  ('ABBV',   'USD',  172.00),
  ('LLY',    'USD',  750.00),
  ('MRK',    'USD',  125.40),
  ('KO',     'USD',   62.30),
  ('PEP',    'USD',  170.00),
  ('COST',   'USD',  735.50),
  ('ADBE',   'USD',  520.10),
  ('CRM',    'USD',  295.00),
  ('MCD',    'USD',  278.50),
  ('BAC',    'USD',   38.20),
  ('CSCO',   'USD',   48.10),
  ('PFE',    'USD',   27.30),
  ('TMO',    'USD',  550.00),
  ('ACN',    'USD',  340.50),
  ('ORCL',   'USD',  125.00),
  ('NKE',    'USD',   90.50),
  ('DIS',    'USD',  110.30),
  ('NFLX',   'USD',  620.00),
  ('INTC',   'USD',   35.80),
  ('AMD',    'USD',  165.00),
  ('QCOM',   'USD',  170.40),
  ('IBM',    'USD',  195.20),
  ('SBUX',   'USD',   92.00),
  ('GS',     'USD',  455.00),
  ('GE',     'USD',  162.50),
  ('BA',     'USD',  180.30),
  ('T',      'USD',   17.50),
  ('VZ',     'USD',   42.00),
  ('C',      'USD',   62.30),
  ('RTX',    'USD',  110.00),
  ('PYPL',   'USD',   65.00)
),
points(day_offset, anchor) AS (
    VALUES
        (0, TIMESTAMP '2026-01-01 12:00:00'),
        (1, TIMESTAMP '2025-12-31 12:00:00'),
        (2, TIMESTAMP '2025-12-30 12:00:00'),
        (3, TIMESTAMP '2025-12-29 12:00:00')
)
INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
SELECT
    bt.ticker,
    bt.quote_currency,
    ROUND((bt.base_price * (1.0 + (0.002 * p.day_offset)))::NUMERIC, 4),
    p.anchor
FROM backfill_tickers bt
CROSS JOIN points p
ON CONFLICT DO NOTHING;

-- ── NSE (50 tickers, quoteCurrency=INR) ──────────────────────────────────
WITH backfill_tickers(ticker, quote_currency, base_price) AS (VALUES
  ('RELIANCE.NS',   'INR',  2845.60),
  ('TCS.NS',        'INR',  3950.00),
  ('HDFCBANK.NS',   'INR',  1520.50),
  ('INFY.NS',       'INR',  1470.00),
  ('ICICIBANK.NS',  'INR',  1125.00),
  ('HINDUNILVR.NS', 'INR',  2280.00),
  ('BHARTIARTL.NS', 'INR',  1380.00),
  ('SBIN.NS',       'INR',   820.50),
  ('LT.NS',         'INR',  3650.00),
  ('ITC.NS',        'INR',   445.00),
  ('KOTAKBANK.NS',  'INR',  1750.00),
  ('BAJFINANCE.NS', 'INR',  7120.00),
  ('HCLTECH.NS',    'INR',  1560.00),
  ('ASIANPAINT.NS', 'INR',  2850.00),
  ('MARUTI.NS',     'INR', 12500.00),
  ('AXISBANK.NS',   'INR',  1120.00),
  ('TITAN.NS',      'INR',  3450.00),
  ('SUNPHARMA.NS',  'INR',  1620.00),
  ('WIPRO.NS',      'INR',   470.00),
  ('ULTRACEMCO.NS', 'INR', 10800.00),
  ('ADANIENT.NS',   'INR',  3120.00),
  ('NESTLEIND.NS',  'INR',  2550.00),
  ('NTPC.NS',       'INR',   365.00),
  ('POWERGRID.NS',  'INR',   290.00),
  ('JSWSTEEL.NS',   'INR',   920.00),
  ('TATAMOTORS.NS', 'INR',  1020.00),
  ('TATASTEEL.NS',  'INR',   165.00),
  ('ONGC.NS',       'INR',   275.00),
  ('COALINDIA.NS',  'INR',   445.00),
  ('BAJAJFINSV.NS', 'INR',  1650.00),
  ('INDUSINDBK.NS', 'INR',  1450.00),
  ('ADANIPORTS.NS', 'INR',  1390.00),
  ('GRASIM.NS',     'INR',  2460.00),
  ('MM.NS',         'INR',  2180.00),
  ('HINDALCO.NS',   'INR',   640.00),
  ('BPCL.NS',       'INR',   620.00),
  ('DRREDDY.NS',    'INR',  6200.00),
  ('CIPLA.NS',      'INR',  1520.00),
  ('DIVISLAB.NS',   'INR',  4350.00),
  ('TECHM.NS',      'INR',  1280.00),
  ('EICHERMOT.NS',  'INR',  4720.00),
  ('HEROMOTOCO.NS', 'INR',  5100.00),
  ('BRITANNIA.NS',  'INR',  4900.00),
  ('UPL.NS',        'INR',   520.00),
  ('APOLLOHOSP.NS', 'INR',  6450.00),
  ('BAJAJ-AUTO.NS', 'INR',  9200.00),
  ('SBILIFE.NS',    'INR',  1510.00),
  ('TATACONSUM.NS', 'INR',  1080.00),
  ('SHREECEM.NS',   'INR', 27500.00),
  ('HDFCLIFE.NS',   'INR',   620.00)
),
points(day_offset, anchor) AS (
    VALUES
        (0, TIMESTAMP '2026-01-01 12:00:00'),
        (1, TIMESTAMP '2025-12-31 12:00:00'),
        (2, TIMESTAMP '2025-12-30 12:00:00'),
        (3, TIMESTAMP '2025-12-29 12:00:00')
)
INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
SELECT
    bt.ticker,
    bt.quote_currency,
    ROUND((bt.base_price * (1.0 + (0.002 * p.day_offset)))::NUMERIC, 4),
    p.anchor
FROM backfill_tickers bt
CROSS JOIN points p
ON CONFLICT DO NOTHING;

-- ── CRYPTO (50 tickers, quoteCurrency=USD) ────────────────────────────────
-- Note: canonical symbol is BTC-USD (not legacy BTC used in V2)
WITH backfill_tickers(ticker, quote_currency, base_price) AS (VALUES
  ('BTC-USD',   'USD',  67432.00),
  ('ETH-USD',   'USD',   3420.00),
  ('BNB-USD',   'USD',    602.00),
  ('SOL-USD',   'USD',    185.50),
  ('XRP-USD',   'USD',      0.62),
  ('USDC-USD',  'USD',      1.00),
  ('ADA-USD',   'USD',      0.485),
  ('AVAX-USD',  'USD',     36.20),
  ('DOGE-USD',  'USD',      0.165),
  ('TRX-USD',   'USD',      0.118),
  ('DOT-USD',   'USD',      7.85),
  ('MATIC-USD', 'USD',      0.715),
  ('LINK-USD',  'USD',     18.50),
  ('SHIB-USD',  'USD',      0.000024),
  ('TON-USD',   'USD',      6.90),
  ('LTC-USD',   'USD',     85.30),
  ('BCH-USD',   'USD',    490.00),
  ('UNI-USD',   'USD',      8.25),
  ('ATOM-USD',  'USD',      8.80),
  ('XMR-USD',   'USD',    175.00),
  ('ETC-USD',   'USD',     27.00),
  ('HBAR-USD',  'USD',      0.090),
  ('XLM-USD',   'USD',      0.112),
  ('APT-USD',   'USD',     11.20),
  ('FIL-USD',   'USD',      6.10),
  ('INJ-USD',   'USD',     28.50),
  ('IMX-USD',   'USD',      2.10),
  ('ARB-USD',   'USD',      1.15),
  ('NEAR-USD',  'USD',      6.40),
  ('VET-USD',   'USD',      0.042),
  ('OP-USD',    'USD',      2.55),
  ('RUNE-USD',  'USD',      5.30),
  ('AAVE-USD',  'USD',     95.00),
  ('GRT-USD',   'USD',      0.285),
  ('ALGO-USD',  'USD',      0.195),
  ('SAND-USD',  'USD',      0.462),
  ('MANA-USD',  'USD',      0.450),
  ('AXS-USD',   'USD',      7.10),
  ('THETA-USD', 'USD',      1.85),
  ('FTM-USD',   'USD',      0.775),
  ('ICP-USD',   'USD',     12.80),
  ('CHZ-USD',   'USD',      0.115),
  ('GALA-USD',  'USD',      0.048),
  ('XTZ-USD',   'USD',      1.05),
  ('FLOW-USD',  'USD',      0.880),
  ('EGLD-USD',  'USD',     42.00),
  ('KAVA-USD',  'USD',      0.572),
  ('ROSE-USD',  'USD',      0.112),
  ('NEO-USD',   'USD',     14.80),
  ('ZEC-USD',   'USD',     24.50)
),
points(day_offset, anchor) AS (
    VALUES
        (0, TIMESTAMP '2026-01-01 12:00:00'),
        (1, TIMESTAMP '2025-12-31 12:00:00'),
        (2, TIMESTAMP '2025-12-30 12:00:00'),
        (3, TIMESTAMP '2025-12-29 12:00:00')
)
INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
SELECT
    bt.ticker,
    bt.quote_currency,
    ROUND((bt.base_price * (1.0 + (0.002 * p.day_offset)))::NUMERIC, 8),
    p.anchor
FROM backfill_tickers bt
CROSS JOIN points p
ON CONFLICT DO NOTHING;

-- ── FOREX (10 tickers, mixed quoteCurrencies) ────────────────────────────
WITH backfill_tickers(ticker, quote_currency, base_price) AS (VALUES
  ('EURUSD=X',  'USD',  1.0823),
  ('GBPUSD=X',  'USD',  1.2650),
  ('USDJPY=X',  'JPY',  154.20),
  ('USDINR=X',  'INR',   83.45),
  ('AUDUSD=X',  'USD',   0.6550),
  ('USDCAD=X',  'CAD',   1.3720),
  ('USDCHF=X',  'CHF',   0.9120),
  ('NZDUSD=X',  'USD',   0.5980),
  ('USDSGD=X',  'SGD',   1.3580),
  ('USDHKD=X',  'HKD',   7.8300)
),
points(day_offset, anchor) AS (
    VALUES
        (0, TIMESTAMP '2026-01-01 12:00:00'),
        (1, TIMESTAMP '2025-12-31 12:00:00'),
        (2, TIMESTAMP '2025-12-30 12:00:00'),
        (3, TIMESTAMP '2025-12-29 12:00:00')
)
INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
SELECT
    bt.ticker,
    bt.quote_currency,
    ROUND((bt.base_price * (1.0 + (0.001 * p.day_offset)))::NUMERIC, 6),
    p.anchor
FROM backfill_tickers bt
CROSS JOIN points p
ON CONFLICT DO NOTHING;
