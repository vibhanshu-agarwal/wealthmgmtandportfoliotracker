package com.wealth.portfolio.repair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.portfolio.catalog.PostMigrationIntegrityAssertion;
import com.wealth.portfolio.catalog.PostMigrationIntegrityFailedException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class PostgresRepairMigrationIT {

    @BeforeAll
    static void startContainer() {
        PostgresRepairHarness.POSTGRES.start();
    }

    // ── 6.9.1 ────────────────────────────────────────────────────────────────

    @Test
    void precisionCollision_identicalPayloads_collapseLosersArchived() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("16");

        session.jdbc()
                .update(
                        """
                        INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
                        VALUES ('COLLIDE_ID', 'USD', 10.0000, '2026-06-01 12:00:00.123456'::timestamp),
                               ('COLLIDE_ID', 'USD', 10.0000, '2026-06-01 12:00:00.123789'::timestamp)
                        """);
        List<Long> ids =
                session.jdbc()
                        .queryForList(
                                "SELECT id FROM market_price_history WHERE ticker = 'COLLIDE_ID' ORDER BY id",
                                Long.class);
        assertThat(ids).hasSize(2);
        long survivorId = ids.get(0);
        long loserId = ids.get(1);

        session.migrateRemaining();

        List<Long> remaining =
                session.jdbc()
                        .queryForList(
                                "SELECT id FROM market_price_history WHERE ticker = 'COLLIDE_ID'",
                                Long.class);
        assertThat(remaining).containsExactly(survivorId);

        Map<String, Object> archive =
                session.jdbc()
                        .queryForMap(
                                """
                                SELECT natural_key, reason, source_table
                                FROM repair_archive
                                WHERE migration_version = 'V17' AND natural_key = ?
                                """,
                                Long.toString(loserId));
        assertThat(archive.get("reason")).isEqualTo("COLLISION_LOSER");
        assertThat(archive.get("source_table")).isEqualTo("market_price_history");
        assertThat(datetimePrecision(session, "market_price_history", "observed_at")).isEqualTo(3);
    }

    // ── 6.9.2 ────────────────────────────────────────────────────────────────

    @Test
    void precisionCollision_conflictingPayloads_abortsBeforeAlter() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("16");
        session.jdbc()
                .update(
                        """
                        INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
                        VALUES ('COLLIDE_BAD', 'USD', 10.0000, '2026-06-01 12:00:00.123456'::timestamp),
                               ('COLLIDE_BAD', 'USD', 11.0000, '2026-06-01 12:00:00.123789'::timestamp)
                        """);

        assertThat(catchRoot(session)).contains("V17_PRECISION_CONFLICT");

        assertThat(tableExists(session, "repair_archive")).isFalse();
        assertThat(datetimePrecision(session, "market_price_history", "observed_at")).isEqualTo(6);
        assertThat(count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'COLLIDE_BAD'"))
                .isEqualTo(2);
    }

    // ── 6.9.3 / 6.9.4 ────────────────────────────────────────────────────────

    @Test
    void btcHistoryArchivedVerbatim_andZeroOperationalRowsRemain() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("17");

        int preCount =
                count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'BTC'");
        assertThat(preCount).isGreaterThan(0);
        session.jdbc()
                .update(
                        """
                        CREATE TABLE pre_btc_history AS
                        SELECT * FROM market_price_history WHERE ticker = 'BTC'
                        """);

        session.migrateRemaining();

        int archiveCount =
                count(
                        session,
                        """
                        SELECT COUNT(*) FROM repair_archive
                        WHERE migration_version = 'V18'
                          AND source_table = 'market_price_history'
                          AND reason = 'LEGACY_SYNTHETIC'
                        """);
        assertThat(archiveCount).isEqualTo(preCount);
        assertThat(count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'BTC'"))
                .isZero();

        Integer matched =
                session.jdbc()
                        .queryForObject(
                                """
                                SELECT COUNT(*) FROM repair_archive a
                                JOIN pre_btc_history p ON a.natural_key = p.id::text
                                WHERE a.migration_version = 'V18'
                                  AND a.source_table = 'market_price_history'
                                  AND a.payload = to_jsonb(p.*)
                                """,
                                Integer.class);
        assertThat(matched).isEqualTo(preCount);

        Integer typedRoundTrip =
                session.jdbc()
                        .queryForObject(
                                """
                                SELECT COUNT(*) FROM repair_archive a
                                JOIN pre_btc_history p ON a.natural_key = p.id::text
                                WHERE a.migration_version = 'V18'
                                  AND a.source_table = 'market_price_history'
                                  AND (a.payload->>'id')::bigint = p.id
                                  AND a.payload->>'ticker' = p.ticker
                                  AND a.payload->>'quote_currency' = p.quote_currency
                                  AND (a.payload->>'price')::numeric = p.price
                                  AND (a.payload->>'observed_at')::timestamp = p.observed_at
                                """,
                                Integer.class);
        assertThat(typedRoundTrip).isEqualTo(preCount);

        int priceArchive =
                count(
                        session,
                        """
                        SELECT COUNT(*) FROM repair_archive
                        WHERE migration_version = 'V18'
                          AND source_table = 'market_prices'
                          AND reason = 'LEGACY_SYNTHETIC'
                          AND natural_key = 'BTC'
                        """);
        assertThat(priceArchive).isEqualTo(1);
        assertThat(count(session, "SELECT COUNT(*) FROM market_prices WHERE ticker = 'BTC'")).isZero();
    }

    // ── 6.9.5 ────────────────────────────────────────────────────────────────

    @Test
    void holdingCollision_bothSymbolsHeld_quantitiesCombinedWeightedBasis() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("16");
        UUID portfolioId = newPortfolio(session);
        Timestamp asOfEarlier = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        Timestamp asOfLater = Timestamp.from(Instant.parse("2026-06-01T00:00:00Z"));
        insertHolding(session, portfolioId, "BTC", "2", "100.0000", "USD", "SEED", asOfEarlier);
        insertHolding(session, portfolioId, "BTC-USD", "1", "400.0000", "USD", "ADD_TIME", asOfLater);

        session.migrateRemaining();

        Map<String, Object> row = holding(session, portfolioId, "BTC-USD");
        assertThat(count(session, holdingsCountSql(portfolioId, "BTC"))).isZero();
        assertThat(asDecimal(row.get("quantity"))).isEqualByComparingTo("3");
        assertThat(asDecimal(row.get("avg_cost_basis"))).isEqualByComparingTo("200.0000");
        assertThat(row.get("cost_basis_currency")).isEqualTo("USD");
        assertThat(row.get("cost_basis_source")).isEqualTo("MERGED");
        assertThat(((Timestamp) row.get("cost_basis_as_of")).toInstant())
                .isEqualTo(asOfLater.toInstant());
        assertThat(auditAction(session, "V18", portfolioId, "BTC-USD")).isEqualTo("MERGED");
    }

    // ── 6.9.6 ────────────────────────────────────────────────────────────────

    @Test
    void holdingCollision_currencyMismatch_aborts() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("17");
        UUID portfolioId = newPortfolio(session);
        insertHolding(session, portfolioId, "BTC", "1", "100.0000", "USD", "SEED", nowTs());
        insertHolding(session, portfolioId, "BTC-USD", "1", "100.0000", "INR", "SEED", nowTs());

        assertThat(catchRoot(session)).contains("HOLDING_CURRENCY_MISMATCH");
        assertThat(count(session, holdingsCountSql(portfolioId, "BTC"))).isEqualTo(1);
        assertThat(count(session, holdingsCountSql(portfolioId, "BTC-USD"))).isEqualTo(1);
    }

    // ── 6.9.7 ────────────────────────────────────────────────────────────────

    @Test
    void holdingCollision_eitherBasisNull_wholeTupleNullBothArchived() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("17");
        UUID portfolioId = newPortfolio(session);
        UUID srcId = insertHolding(session, portfolioId, "BTC", "2", "100.0000", "USD", "SEED", nowTs());
        UUID destId = insertHolding(session, portfolioId, "BTC-USD", "1", null, null, null, null);

        session.migrateRemaining();

        Map<String, Object> row = holding(session, portfolioId, "BTC-USD");
        assertThat(asDecimal(row.get("quantity"))).isEqualByComparingTo("3");
        assertThat(row.get("avg_cost_basis")).isNull();
        assertThat(row.get("cost_basis_currency")).isNull();
        assertThat(row.get("cost_basis_source")).isNull();
        assertThat(row.get("cost_basis_as_of")).isNull();
        assertThat(count(session, holdingsCountSql(portfolioId, "BTC"))).isZero();

        List<String> keys =
                session.jdbc()
                        .queryForList(
                                """
                                SELECT natural_key FROM repair_archive
                                WHERE migration_version = 'V18'
                                  AND source_table = 'asset_holdings'
                                  AND reason = 'BASIS_UNAVAILABLE'
                                ORDER BY natural_key
                                """,
                                String.class);
        assertThat(keys).containsExactlyInAnyOrder(srcId.toString(), destId.toString());
    }

    // ── 6.9.8 ────────────────────────────────────────────────────────────────

    @Test
    void holdingCollision_nonPositiveSum_aborts() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("17");
        UUID portfolioId = newPortfolio(session);
        insertHolding(session, portfolioId, "BTC", "1", "100.0000", "USD", "SEED", nowTs());
        insertHolding(session, portfolioId, "BTC-USD", "-1", "100.0000", "USD", "SEED", nowTs());

        assertThat(catchRoot(session)).contains("HOLDING_NONPOSITIVE_QUANTITY");
        assertThat(count(session, holdingsCountSql(portfolioId, "BTC"))).isEqualTo(1);
        assertThat(count(session, holdingsCountSql(portfolioId, "BTC-USD"))).isEqualTo(1);
    }

    // ── 6.9.9 ────────────────────────────────────────────────────────────────

    @Test
    void mmNsMigratedAcrossHoldingsPricesHistoryWithContinuity() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("17");
        UUID portfolioId = newPortfolio(session);
        insertHolding(session, portfolioId, "MM.NS", "10", "2180.0000", "INR", "SEED", nowTs());
        int preHistory =
                count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'MM.NS'");
        assertThat(preHistory).isGreaterThan(0);
        session.jdbc()
                .update(
                        """
                        INSERT INTO market_prices (ticker, current_price, quote_currency, updated_at, observed_at)
                        VALUES ('MM.NS', 2180.0000, 'INR', now(), '2026-06-01 12:00:00'::timestamp)
                        ON CONFLICT (ticker) DO UPDATE
                          SET current_price = EXCLUDED.current_price,
                              quote_currency = EXCLUDED.quote_currency,
                              observed_at = EXCLUDED.observed_at
                        """);

        session.migrateRemaining();

        assertThat(count(session, holdingsCountSql(portfolioId, "MM.NS"))).isZero();
        assertThat(count(session, holdingsCountSql(portfolioId, "M&M.NS"))).isEqualTo(1);
        assertThat(count(session, "SELECT COUNT(*) FROM market_prices WHERE ticker = 'MM.NS'")).isZero();
        assertThat(count(session, "SELECT COUNT(*) FROM market_prices WHERE ticker = 'M&M.NS'"))
                .isEqualTo(1);
        assertThat(count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'MM.NS'"))
                .isZero();
        assertThat(count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'M&M.NS'"))
                .isEqualTo(preHistory);
        assertThat(auditAction(session, "V19", portfolioId, "M&M.NS")).isEqualTo("CREATED");
    }

    // ── 6.9.10 ───────────────────────────────────────────────────────────────

    @Test
    void marketPricesCollision_newerObservedAtWins() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("17");
        replacePrice(session, "BTC", "10.0000", "USD", "2026-06-02T00:00:00Z");
        replacePrice(session, "BTC-USD", "20.0000", "USD", "2026-06-01T00:00:00Z");

        session.migrateRemaining();

        Map<String, Object> row = price(session, "BTC-USD");
        assertThat(asDecimal(row.get("current_price"))).isEqualByComparingTo("10.0000");
        assertThat(((Timestamp) row.get("observed_at")).toInstant())
                .isEqualTo(Instant.parse("2026-06-02T00:00:00Z"));
        assertThat(count(session, "SELECT COUNT(*) FROM market_prices WHERE ticker = 'BTC'")).isZero();
    }

    // ── 6.9.11 ───────────────────────────────────────────────────────────────

    @Test
    void marketPricesCollision_knownBeatsNull() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("17");
        replacePrice(session, "BTC", "10.0000", "USD", "2026-06-01T00:00:00Z");
        replacePrice(session, "BTC-USD", "20.0000", "USD", null);

        session.migrateRemaining();

        Map<String, Object> row = price(session, "BTC-USD");
        assertThat(asDecimal(row.get("current_price"))).isEqualByComparingTo("10.0000");
        assertThat(((Timestamp) row.get("observed_at")).toInstant())
                .isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }

    // ── 6.9.12 ───────────────────────────────────────────────────────────────

    @Test
    void marketPricesCollision_bothNull_destinationRetainedSourceArchived() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("17");
        replacePrice(session, "BTC", "10.0000", "USD", null);
        replacePrice(session, "BTC-USD", "20.0000", "USD", null);

        session.migrateRemaining();

        Map<String, Object> row = price(session, "BTC-USD");
        assertThat(asDecimal(row.get("current_price"))).isEqualByComparingTo("20.0000");
        assertThat(row.get("observed_at")).isNull();
        assertThat(
                        count(
                                session,
                                """
                                SELECT COUNT(*) FROM repair_archive
                                WHERE migration_version = 'V18'
                                  AND source_table = 'market_prices'
                                  AND natural_key = 'BTC'
                                  AND reason = 'LEGACY_SYNTHETIC'
                                """))
                .isEqualTo(1);
    }

    // ── 6.9.13 ───────────────────────────────────────────────────────────────

    @Test
    void historyCollision_identicalPayload_collapses() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("17");
        session.jdbc().update("DELETE FROM market_price_history WHERE ticker IN ('MM.NS', 'M&M.NS')");
        session.jdbc()
                .update(
                        """
                        INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
                        VALUES ('MM.NS', 'INR', 100.0000, '2026-06-01 12:00:00'::timestamp),
                               ('M&M.NS', 'INR', 100.0000, '2026-06-01 12:00:00'::timestamp)
                        """);

        session.migrateRemaining();

        assertThat(count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'MM.NS'"))
                .isZero();
        assertThat(count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'M&M.NS'"))
                .isEqualTo(1);
        assertThat(
                        count(
                                session,
                                """
                                SELECT COUNT(*) FROM repair_archive
                                WHERE migration_version = 'V19'
                                  AND source_table = 'market_price_history'
                                  AND reason = 'COLLISION_LOSER'
                                """))
                .isEqualTo(1);
    }

    // ── 6.9.14 ───────────────────────────────────────────────────────────────

    @Test
    void historyCollision_conflictingPayload_aborts() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("17");
        session.jdbc().update("DELETE FROM market_price_history WHERE ticker IN ('MM.NS', 'M&M.NS')");
        session.jdbc()
                .update(
                        """
                        INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
                        VALUES ('MM.NS', 'INR', 100.0000, '2026-06-01 12:00:00'::timestamp),
                               ('M&M.NS', 'INR', 200.0000, '2026-06-01 12:00:00'::timestamp)
                        """);

        assertThat(catchRoot(session)).contains("HISTORY_PAYLOAD_CONFLICT");
        assertThat(count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'MM.NS'"))
                .isEqualTo(1);
        assertThat(count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'M&M.NS'"))
                .isEqualTo(1);
    }

    // ── 6.9.15 ───────────────────────────────────────────────────────────────

    @Test
    void marketPricesCollision_equalKnownIdenticalPayload_idempotentCollapse() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("17");
        replacePrice(session, "BTC", "10.0000", "USD", "2026-06-01T00:00:00Z");
        replacePrice(session, "BTC-USD", "10.0000", "USD", "2026-06-01T00:00:00Z");

        session.migrateRemaining();

        Map<String, Object> row = price(session, "BTC-USD");
        assertThat(asDecimal(row.get("current_price"))).isEqualByComparingTo("10.0000");
        assertThat(count(session, "SELECT COUNT(*) FROM market_prices WHERE ticker = 'BTC'")).isZero();
    }

    // ── 6.9.16 ───────────────────────────────────────────────────────────────

    @Test
    void marketPricesCollision_equalKnownConflictingPayload_abortsWithoutAlteringEither() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("17");
        replacePrice(session, "BTC", "10.0000", "USD", "2026-06-01T00:00:00Z");
        replacePrice(session, "BTC-USD", "20.0000", "USD", "2026-06-01T00:00:00Z");

        assertThat(catchRoot(session)).contains("PRICE_PAYLOAD_CONFLICT");

        Map<String, Object> btc = price(session, "BTC");
        Map<String, Object> dest = price(session, "BTC-USD");
        assertThat(asDecimal(btc.get("current_price"))).isEqualByComparingTo("10.0000");
        assertThat(asDecimal(dest.get("current_price"))).isEqualByComparingTo("20.0000");
    }

    // ── 6.9.17 ───────────────────────────────────────────────────────────────

    @Test
    void integrityAssertion_failsMigrationCreatedDeprecated_passesPreExisting() {
        var session = PostgresRepairHarness.newSession();
        session.migrateRemaining();
        UUID portfolioId = newPortfolio(session);
        insertHolding(session, portfolioId, "TATAMOTORS.NS", "5", "400.0000", "INR", "SEED", nowTs());

        SupportedCatalog catalog = SupportedCatalog.load();
        PostMigrationIntegrityAssertion.assertSatisfied(session.jdbc(), catalog);

        session.jdbc()
                .update(
                        """
                        INSERT INTO repair_audit (migration_version, portfolio_id, asset_ticker, action)
                        VALUES ('V18', ?, 'TATAMOTORS.NS', 'CREATED')
                        """,
                        portfolioId);

        assertThatThrownBy(
                        () -> PostMigrationIntegrityAssertion.assertSatisfied(session.jdbc(), catalog))
                .isInstanceOf(PostMigrationIntegrityFailedException.class)
                .hasMessageContaining("TATAMOTORS.NS");
    }

    // ── 6.9.18 ───────────────────────────────────────────────────────────────

    @Test
    void tatamotorsHoldingIsByteUnchangedAfterAllMigrations() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("16");
        UUID portfolioId = newPortfolio(session);
        UUID holdingId =
                insertHolding(
                        session, portfolioId, "TATAMOTORS.NS", "7.25000000", "412.5000", "INR", "SEED", nowTs());
        Map<String, Object> before =
                session.jdbc()
                        .queryForMap("SELECT * FROM asset_holdings WHERE id = ?", holdingId);

        session.migrateRemaining();

        Map<String, Object> after =
                session.jdbc()
                        .queryForMap("SELECT * FROM asset_holdings WHERE id = ?", holdingId);
        assertThat(after).containsAllEntriesOf(before);
        assertThat(after.get("asset_ticker")).isEqualTo("TATAMOTORS.NS");
    }

    // ── 6.8 ──────────────────────────────────────────────────────────────────

    @Test
    void reExecutionOfRepairSqlIsIdempotent() {
        var session = PostgresRepairHarness.newSession();
        session.migrateRemaining();

        int archiveBefore = count(session, "SELECT COUNT(*) FROM repair_archive");
        int auditBefore = count(session, "SELECT COUNT(*) FROM repair_audit");
        int btcHistory = count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'BTC'");
        int mmHistory = count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'MM.NS'");
        int mahindraHistory =
                count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'M&M.NS'");

        session.remigrateRepairVersions();

        assertThat(count(session, "SELECT COUNT(*) FROM repair_archive")).isEqualTo(archiveBefore);
        assertThat(count(session, "SELECT COUNT(*) FROM repair_audit")).isEqualTo(auditBefore);
        assertThat(count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'BTC'"))
                .isEqualTo(btcHistory)
                .isZero();
        assertThat(count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'MM.NS'"))
                .isEqualTo(mmHistory)
                .isZero();
        assertThat(count(session, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'M&M.NS'"))
                .isEqualTo(mahindraHistory);
        assertThat(count(session, "SELECT COUNT(*) FROM asset_holdings WHERE asset_ticker = 'BTC'"))
                .isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String catchRoot(PostgresRepairHarness.Session session) {
        try {
            session.migrateRemaining();
            throw new AssertionError("expected migration to abort");
        } catch (RuntimeException e) {
            StringBuilder messages = new StringBuilder();
            Throwable cursor = e;
            while (cursor != null) {
                if (cursor.getMessage() != null) {
                    messages.append(cursor.getMessage()).append('\n');
                }
                cursor = cursor.getCause();
            }
            return messages.toString();
        }
    }

    private static UUID newPortfolio(PostgresRepairHarness.Session session) {
        UUID id = UUID.randomUUID();
        session.jdbc().update("INSERT INTO portfolios (id, user_id) VALUES (?, 'repair-it')", id);
        return id;
    }

    private static UUID insertHolding(
            PostgresRepairHarness.Session session,
            UUID portfolioId,
            String ticker,
            String quantity,
            String basis,
            String currency,
            String source,
            Timestamp asOf) {
        UUID id = UUID.randomUUID();
        session.jdbc()
                .update(
                        """
                        INSERT INTO asset_holdings
                            (id, portfolio_id, asset_ticker, quantity, avg_cost_basis,
                             cost_basis_currency, cost_basis_source, cost_basis_as_of)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        id,
                        portfolioId,
                        ticker,
                        new BigDecimal(quantity),
                        basis == null ? null : new BigDecimal(basis),
                        currency,
                        source,
                        asOf);
        return id;
    }

    private static void replacePrice(
            PostgresRepairHarness.Session session,
            String ticker,
            String price,
            String currency,
            String observedAtIso) {
        Timestamp observedAt = observedAtIso == null ? null : Timestamp.from(Instant.parse(observedAtIso));
        session.jdbc()
                .update(
                        """
                        INSERT INTO market_prices (ticker, current_price, quote_currency, updated_at, observed_at)
                        VALUES (?, ?::numeric, ?, now(), ?)
                        ON CONFLICT (ticker) DO UPDATE
                          SET current_price = EXCLUDED.current_price,
                              quote_currency = EXCLUDED.quote_currency,
                              updated_at = EXCLUDED.updated_at,
                              observed_at = EXCLUDED.observed_at
                        """,
                        ticker,
                        price,
                        currency,
                        observedAt);
    }

    private static Map<String, Object> holding(
            PostgresRepairHarness.Session session, UUID portfolioId, String ticker) {
        return session.jdbc()
                .queryForMap(
                        "SELECT * FROM asset_holdings WHERE portfolio_id = ? AND asset_ticker = ?",
                        portfolioId,
                        ticker);
    }

    private static Map<String, Object> price(PostgresRepairHarness.Session session, String ticker) {
        return session.jdbc().queryForMap("SELECT * FROM market_prices WHERE ticker = ?", ticker);
    }

    private static String auditAction(
            PostgresRepairHarness.Session session, String version, UUID portfolioId, String ticker) {
        return session.jdbc()
                .queryForObject(
                        """
                        SELECT action FROM repair_audit
                        WHERE migration_version = ? AND portfolio_id = ? AND asset_ticker = ?
                        """,
                        String.class,
                        version,
                        portfolioId,
                        ticker);
    }

    private static int count(PostgresRepairHarness.Session session, String sql) {
        Integer value = session.jdbc().queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static String holdingsCountSql(UUID portfolioId, String ticker) {
        return "SELECT COUNT(*) FROM asset_holdings WHERE portfolio_id = '"
                + portfolioId
                + "' AND asset_ticker = '"
                + ticker
                + "'";
    }

    private static boolean tableExists(PostgresRepairHarness.Session session, String table) {
        Integer n =
                session.jdbc()
                        .queryForObject(
                                """
                                SELECT COUNT(*) FROM information_schema.tables
                                WHERE table_schema = 'public' AND table_name = ?
                                """,
                                Integer.class,
                                table);
        return n != null && n == 1;
    }

    private static Integer datetimePrecision(
            PostgresRepairHarness.Session session, String table, String column) {
        return session.jdbc()
                .queryForObject(
                        """
                        SELECT datetime_precision FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                        """,
                        Integer.class,
                        table,
                        column);
    }

    private static BigDecimal asDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(value.toString());
    }

    private static Timestamp nowTs() {
        return Timestamp.from(Instant.parse("2026-06-01T00:00:00Z"));
    }
}
