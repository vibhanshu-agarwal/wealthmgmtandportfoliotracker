-- =============================================================================
-- V17: repair_archive + repair_audit, TIMESTAMP(3) preflight, then the lossy ALTER
--
-- Spec A task 6.1–6.3. Design Rev 10: the history ALTER MUST use
--   USING date_trunc('milliseconds', observed_at)
-- Postgres's default cast to TIMESTAMP(3) rounds; the preflight and the live
-- writer (truncatedTo(MILLIS)) truncate. Without the USING clause the preflight
-- key would not predict the ALTER's collisions, and converted rows would take a
-- different identity than live-written ones (D9).
-- =============================================================================

CREATE TABLE IF NOT EXISTS repair_archive
(
    id                 BIGSERIAL PRIMARY KEY,
    migration_version  VARCHAR(16)  NOT NULL,
    source_table       VARCHAR(64)  NOT NULL,
    reason             VARCHAR(32)  NOT NULL,
    natural_key        TEXT         NOT NULL,
    payload            JSONB        NOT NULL,
    archived_at        TIMESTAMP(3) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT repair_archive_reason_chk
        CHECK (reason IN ('LEGACY_SYNTHETIC', 'COLLISION_LOSER', 'BASIS_UNAVAILABLE')),
    CONSTRAINT repair_archive_idempotency_key
        UNIQUE (migration_version, source_table, natural_key)
);

CREATE TABLE IF NOT EXISTS repair_audit
(
    migration_version VARCHAR(16)  NOT NULL,
    portfolio_id      UUID         NOT NULL,
    asset_ticker      VARCHAR(20)  NOT NULL,
    action            VARCHAR(16)  NOT NULL,
    recorded_at       TIMESTAMP(3) NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (migration_version, portfolio_id, asset_ticker),
    CONSTRAINT repair_audit_action_chk
        CHECK (action IN ('CREATED', 'REPLACED', 'MERGED'))
);

CREATE OR REPLACE FUNCTION repair_archive_row(
    p_version      text,
    p_source_table text,
    p_reason       text,
    p_natural_key  text,
    p_payload      jsonb
) RETURNS void
    LANGUAGE plpgsql
    SET search_path = public
AS
$$
BEGIN
    INSERT INTO repair_archive (migration_version, source_table, reason, natural_key, payload)
    VALUES (p_version, p_source_table, p_reason, p_natural_key, p_payload)
    ON CONFLICT (migration_version, source_table, natural_key) DO NOTHING;
END;
$$;

-- Holdings: source → dest. Collision portfolios combine; source-only rows are renamed.
CREATE OR REPLACE FUNCTION repair_migrate_holdings(
    p_version text,
    p_source  text,
    p_dest    text
) RETURNS void
    LANGUAGE plpgsql
    SET search_path = public
AS
$$
DECLARE
    rec            RECORD;
    v_q_sum        numeric;
    v_merged_basis numeric;
BEGIN
    FOR rec IN
        SELECT s.portfolio_id,
               s.id                  AS src_id,
               d.id                  AS dest_id,
               s.quantity            AS src_qty,
               d.quantity            AS dest_qty,
               s.avg_cost_basis      AS src_basis,
               d.avg_cost_basis      AS dest_basis,
               s.cost_basis_currency AS src_ccy,
               d.cost_basis_currency AS dest_ccy,
               s.cost_basis_as_of    AS src_as_of,
               d.cost_basis_as_of    AS dest_as_of,
               to_jsonb(s.*)         AS src_payload,
               to_jsonb(d.*)         AS dest_payload
        FROM asset_holdings s
                 JOIN asset_holdings d
                      ON d.portfolio_id = s.portfolio_id
                          AND d.asset_ticker = p_dest
        WHERE s.asset_ticker = p_source
        LOOP
            v_q_sum := rec.src_qty + rec.dest_qty;
            IF v_q_sum <= 0 THEN
                RAISE EXCEPTION
                    'HOLDING_NONPOSITIVE_QUANTITY: portfolio_id=% source=% dest=% q1=% q2=%',
                    rec.portfolio_id, p_source, p_dest, rec.src_qty, rec.dest_qty;
            END IF;

            IF rec.src_basis IS NULL OR rec.dest_basis IS NULL THEN
                PERFORM repair_archive_row(
                        p_version, 'asset_holdings', 'BASIS_UNAVAILABLE',
                        rec.src_id::text, rec.src_payload);
                PERFORM repair_archive_row(
                        p_version, 'asset_holdings', 'BASIS_UNAVAILABLE',
                        rec.dest_id::text, rec.dest_payload);
                UPDATE asset_holdings
                SET quantity            = v_q_sum,
                    avg_cost_basis      = NULL,
                    cost_basis_currency = NULL,
                    cost_basis_source   = NULL,
                    cost_basis_as_of    = NULL
                WHERE id = rec.dest_id;
                DELETE FROM asset_holdings WHERE id = rec.src_id;
                INSERT INTO repair_audit (migration_version, portfolio_id, asset_ticker, action)
                VALUES (p_version, rec.portfolio_id, p_dest, 'MERGED')
                ON CONFLICT (migration_version, portfolio_id, asset_ticker) DO NOTHING;
                CONTINUE;
            END IF;

            IF rec.src_ccy IS DISTINCT FROM rec.dest_ccy THEN
                RAISE EXCEPTION
                    'HOLDING_CURRENCY_MISMATCH: portfolio_id=% source=% dest=% ccy1=% ccy2=%',
                    rec.portfolio_id, p_source, p_dest, rec.src_ccy, rec.dest_ccy;
            END IF;

            v_merged_basis :=
                    round((rec.src_qty * rec.src_basis + rec.dest_qty * rec.dest_basis) / v_q_sum, 4);

            PERFORM repair_archive_row(
                    p_version, 'asset_holdings', 'COLLISION_LOSER',
                    rec.src_id::text, rec.src_payload);
            UPDATE asset_holdings
            SET quantity            = v_q_sum,
                avg_cost_basis      = v_merged_basis,
                cost_basis_currency = rec.dest_ccy,
                cost_basis_source   = 'MERGED',
                cost_basis_as_of    = GREATEST(rec.src_as_of, rec.dest_as_of)
            WHERE id = rec.dest_id;
            DELETE FROM asset_holdings WHERE id = rec.src_id;
            INSERT INTO repair_audit (migration_version, portfolio_id, asset_ticker, action)
            VALUES (p_version, rec.portfolio_id, p_dest, 'MERGED')
            ON CONFLICT (migration_version, portfolio_id, asset_ticker) DO NOTHING;
        END LOOP;

    FOR rec IN
        SELECT h.id, h.portfolio_id
        FROM asset_holdings h
        WHERE h.asset_ticker = p_source
        LOOP
            UPDATE asset_holdings SET asset_ticker = p_dest WHERE id = rec.id;
            INSERT INTO repair_audit (migration_version, portfolio_id, asset_ticker, action)
            VALUES (p_version, rec.portfolio_id, p_dest, 'CREATED')
            ON CONFLICT (migration_version, portfolio_id, asset_ticker) DO NOTHING;
        END LOOP;
END;
$$;

-- ── Preflight: abort conflicting millisecond groups before the ALTER ──────────
DO
$$
    DECLARE
        v_ticker text;
        v_ts     timestamp;
    BEGIN
        SELECT g.ticker, g.ts_ms
        INTO v_ticker, v_ts
        FROM (SELECT ticker,
                     date_trunc('milliseconds', observed_at) AS ts_ms
              FROM market_price_history
              GROUP BY ticker, date_trunc('milliseconds', observed_at)
              HAVING MIN(price) IS DISTINCT FROM MAX(price)
                  OR MIN(quote_currency) IS DISTINCT FROM MAX(quote_currency)) g
        LIMIT 1;

        IF FOUND THEN
            RAISE EXCEPTION
                'V17_PRECISION_CONFLICT: ticker=% ts=% — conflicting payloads in one millisecond bucket; aborting before ALTER',
                v_ticker, v_ts;
        END IF;

        -- Identical-payload groups: lowest original id survives; rest archived.
        INSERT INTO repair_archive (migration_version, source_table, reason, natural_key, payload)
        SELECT 'V17',
               'market_price_history',
               'COLLISION_LOSER',
               h.id::text,
               to_jsonb(h.*)
        FROM market_price_history h
        WHERE EXISTS (SELECT 1
                      FROM market_price_history keep
                      WHERE keep.ticker = h.ticker
                        AND date_trunc('milliseconds', keep.observed_at) =
                            date_trunc('milliseconds', h.observed_at)
                        AND keep.price IS NOT DISTINCT FROM h.price
                        AND keep.quote_currency IS NOT DISTINCT FROM h.quote_currency
                        AND keep.id < h.id)
        ON CONFLICT (migration_version, source_table, natural_key) DO NOTHING;

        DELETE
        FROM market_price_history h
        WHERE EXISTS (SELECT 1
                      FROM market_price_history keep
                      WHERE keep.ticker = h.ticker
                        AND date_trunc('milliseconds', keep.observed_at) =
                            date_trunc('milliseconds', h.observed_at)
                        AND keep.price IS NOT DISTINCT FROM h.price
                        AND keep.quote_currency IS NOT DISTINCT FROM h.quote_currency
                        AND keep.id < h.id);
    END
$$;

ALTER TABLE market_prices
    ADD COLUMN IF NOT EXISTS observed_at TIMESTAMP(3) NULL;

ALTER TABLE market_price_history
    ALTER COLUMN observed_at TYPE TIMESTAMP(3)
        USING date_trunc('milliseconds', observed_at);

-- Functions that read market_prices.observed_at are created after the column exists.
CREATE OR REPLACE FUNCTION repair_migrate_market_prices(
    p_version               text,
    p_source                text,
    p_dest                  text,
    p_archive_reason        text,
    p_move_when_dest_absent boolean
) RETURNS void
    LANGUAGE plpgsql
    SET search_path = public
AS
$$
DECLARE
    src         market_prices%ROWTYPE;
    dest        market_prices%ROWTYPE;
    src_payload jsonb;
BEGIN
    SELECT * INTO src FROM market_prices WHERE ticker = p_source;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    src_payload := to_jsonb(src);

    SELECT * INTO dest FROM market_prices WHERE ticker = p_dest;
    IF NOT FOUND THEN
        IF p_move_when_dest_absent THEN
            UPDATE market_prices SET ticker = p_dest WHERE ticker = p_source;
        ELSE
            PERFORM repair_archive_row(
                    p_version, 'market_prices', p_archive_reason, p_source, src_payload);
            DELETE FROM market_prices WHERE ticker = p_source;
        END IF;
        RETURN;
    END IF;

    -- Abort on equal known observed_at with a conflicting payload BEFORE any write,
    -- so both candidates survive for operator resolution (6.9.16).
    IF src.observed_at IS NOT NULL
        AND dest.observed_at IS NOT NULL
        AND src.observed_at = dest.observed_at
        AND (src.current_price IS DISTINCT FROM dest.current_price
            OR src.quote_currency IS DISTINCT FROM dest.quote_currency) THEN
        RAISE EXCEPTION
            'PRICE_PAYLOAD_CONFLICT: source=% dest=% observed_at=%',
            p_source, p_dest, src.observed_at;
    END IF;

    IF src.observed_at IS NOT NULL AND dest.observed_at IS NULL THEN
        UPDATE market_prices
        SET current_price  = src.current_price,
            quote_currency = src.quote_currency,
            observed_at    = src.observed_at,
            updated_at     = src.updated_at
        WHERE ticker = p_dest;
    ELSIF src.observed_at IS NOT NULL
        AND dest.observed_at IS NOT NULL
        AND src.observed_at > dest.observed_at THEN
        UPDATE market_prices
        SET current_price  = src.current_price,
            quote_currency = src.quote_currency,
            observed_at    = src.observed_at,
            updated_at     = src.updated_at
        WHERE ticker = p_dest;
    END IF;

    PERFORM repair_archive_row(
            p_version, 'market_prices', p_archive_reason, p_source, src_payload);
    DELETE FROM market_prices WHERE ticker = p_source;
END;
$$;

CREATE OR REPLACE FUNCTION repair_migrate_history(
    p_version text,
    p_source  text,
    p_dest    text
) RETURNS void
    LANGUAGE plpgsql
    SET search_path = public
AS
$$
DECLARE
    rec       RECORD;
    collapsed int := 0;
    pre_src   int;
    pre_dest  int;
    post_dest int;
BEGIN
    SELECT COUNT(*) INTO pre_src FROM market_price_history WHERE ticker = p_source;
    SELECT COUNT(*) INTO pre_dest FROM market_price_history WHERE ticker = p_dest;

    IF EXISTS (SELECT 1
               FROM market_price_history s
                        JOIN market_price_history d
                             ON d.ticker = p_dest
                                 AND d.observed_at = s.observed_at
               WHERE s.ticker = p_source
                 AND (s.price IS DISTINCT FROM d.price
                   OR s.quote_currency IS DISTINCT FROM d.quote_currency)) THEN
        RAISE EXCEPTION 'HISTORY_PAYLOAD_CONFLICT: source=% dest=%', p_source, p_dest;
    END IF;

    FOR rec IN
        SELECT s.id, to_jsonb(s.*) AS payload
        FROM market_price_history s
                 JOIN market_price_history d
                      ON d.ticker = p_dest AND d.observed_at = s.observed_at
        WHERE s.ticker = p_source
          AND s.price IS NOT DISTINCT FROM d.price
          AND s.quote_currency IS NOT DISTINCT FROM d.quote_currency
        LOOP
            PERFORM repair_archive_row(
                    p_version, 'market_price_history', 'COLLISION_LOSER',
                    rec.id::text, rec.payload);
            DELETE FROM market_price_history WHERE id = rec.id;
            collapsed := collapsed + 1;
        END LOOP;

    UPDATE market_price_history SET ticker = p_dest WHERE ticker = p_source;

    SELECT COUNT(*) INTO post_dest FROM market_price_history WHERE ticker = p_dest;
    IF post_dest <> (pre_src + pre_dest - collapsed) THEN
        RAISE EXCEPTION
            'HISTORY_COUNT_MISMATCH: expected=% actual=% collapsed=%',
            pre_src + pre_dest - collapsed, post_dest, collapsed;
    END IF;
END;
$$;
