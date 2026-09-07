-- V17 introduced these helpers solely for the one-time V18/V19 data repairs.
-- Remove the persistent execution surface after those repairs and V20 have run.
-- Exact signatures avoid overload ambiguity; RESTRICT fails closed on an
-- unexpected dependency instead of removing another object transitively.

DROP FUNCTION IF EXISTS public.repair_migrate_holdings(text, text, text) RESTRICT;
DROP FUNCTION IF EXISTS public.repair_migrate_market_prices(text, text, text, text, boolean) RESTRICT;
DROP FUNCTION IF EXISTS public.repair_migrate_history(text, text, text) RESTRICT;
DROP FUNCTION IF EXISTS public.repair_archive_row(text, text, text, text, jsonb) RESTRICT;
