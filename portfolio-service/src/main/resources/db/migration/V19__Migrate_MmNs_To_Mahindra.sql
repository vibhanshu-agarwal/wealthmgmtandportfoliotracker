-- =============================================================================
-- V19: MM.NS → M&M.NS across holdings, current prices, and history
--
-- Spec A task 6.5. MM.NS history is real and migrates with continuity. Collision
-- rules are the same frozen table as V18. The Canonical_Manifest rename
-- MM.NS → M&M.NS ships in this same change (Requirement 4.8).
-- =============================================================================

DO
$$
    BEGIN
        PERFORM repair_migrate_holdings('V19', 'MM.NS', 'M&M.NS');
        PERFORM repair_migrate_market_prices('V19', 'MM.NS', 'M&M.NS', 'COLLISION_LOSER', true);
        PERFORM repair_migrate_history('V19', 'MM.NS', 'M&M.NS');
    END
$$;
