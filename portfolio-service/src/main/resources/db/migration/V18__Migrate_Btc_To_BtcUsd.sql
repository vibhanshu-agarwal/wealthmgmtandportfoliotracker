-- =============================================================================
-- V18: BTC → BTC-USD holdings; archive+delete synthetic BTC history and price
--
-- Spec A task 6.4. BTC history is V2 seed fabrications, not observations: copy
-- verbatim to repair_archive (LEGACY_SYNTHETIC) then delete. The orphaned BTC
-- current-price row is archived the same way and deleted; it is not copied onto
-- BTC-USD. Holding collisions use the frozen table in design §12.
-- =============================================================================

DO
$$
    BEGIN
        PERFORM repair_migrate_holdings('V18', 'BTC', 'BTC-USD');
    END
$$;

DO
$$
    DECLARE
        pre_count     int;
        archive_count int;
    BEGIN
        SELECT COUNT(*) INTO pre_count FROM market_price_history WHERE ticker = 'BTC';

        INSERT INTO repair_archive (migration_version, source_table, reason, natural_key, payload)
        SELECT 'V18',
               'market_price_history',
               'LEGACY_SYNTHETIC',
               id::text,
               to_jsonb(h.*)
        FROM market_price_history h
        WHERE ticker = 'BTC'
        ON CONFLICT (migration_version, source_table, natural_key) DO NOTHING;

        DELETE FROM market_price_history WHERE ticker = 'BTC';

        SELECT COUNT(*)
        INTO archive_count
        FROM repair_archive
        WHERE migration_version = 'V18'
          AND source_table = 'market_price_history'
          AND reason = 'LEGACY_SYNTHETIC';

        -- Re-execution: pre_count is 0 and the archive already holds the first-run rows.
        IF pre_count <> 0 AND archive_count <> pre_count THEN
            RAISE EXCEPTION 'V18_HISTORY_ARCHIVE_COUNT: pre=% archived=%', pre_count, archive_count;
        END IF;
    END
$$;

DO
$$
    BEGIN
        PERFORM repair_migrate_market_prices('V18', 'BTC', 'BTC-USD', 'LEGACY_SYNTHETIC', false);
    END
$$;
