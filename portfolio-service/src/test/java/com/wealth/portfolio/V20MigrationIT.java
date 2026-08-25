package com.wealth.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wealth.portfolio.repair.PostgresRepairHarness;
import java.util.UUID;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Isolated V19→V20 proofs for B1 Wave 3 tasks 3.1–3.3.
 *
 * <p>Each scenario uses a fresh database so an intentionally failed migration cannot poison later
 * cases.
 */
@Tag("integration")
class V20MigrationIT {

    @BeforeAll
    static void startContainer() {
        PostgresRepairHarness.POSTGRES.start();
    }

    @Test
    void v19ToV20BackfillsUserWithoutPortfolioAndIsIdempotentOnRerun() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("19");

        UUID userId = UUID.randomUUID();
        session.jdbc()
                .update(
                        "INSERT INTO users (id, email, created_at) VALUES (?::uuid, ?, now())",
                        userId.toString(),
                        "v20-backfill-" + userId + "@example.com");

        assertThat(countPortfolios(session, userId)).isZero();

        session.migrateRemaining();

        assertThat(countPortfolios(session, userId)).isEqualTo(1);
        UUID backfilledPortfolioId = session.jdbc()
                .queryForObject(
                        "SELECT id FROM portfolios WHERE user_id = ?",
                        UUID.class,
                        userId.toString());
        assertThat(session.jdbc()
                        .queryForObject(
                                "SELECT version FROM portfolios WHERE id = ?",
                                Long.class,
                                backfilledPortfolioId))
                .isZero();
        assertThat(session.jdbc()
                        .queryForObject(
                                "SELECT created_at IS NOT NULL FROM portfolios WHERE id = ?",
                                Boolean.class,
                                backfilledPortfolioId))
                .isTrue();
        assertThat(session.jdbc()
                        .queryForObject(
                                "SELECT updated_at IS NOT NULL FROM portfolios WHERE id = ?",
                                Boolean.class,
                                backfilledPortfolioId))
                .isTrue();
        assertThat(session.jdbc()
                        .queryForObject(
                                """
                                SELECT count(*)
                                  FROM asset_holdings
                                 WHERE portfolio_id = ?
                                """,
                                Integer.class,
                                backfilledPortfolioId))
                .isZero();
        assertThat(session.jdbc()
                        .queryForObject(
                                "SELECT user_id FROM portfolios WHERE user_id = ?",
                                String.class,
                                userId.toString()))
                .isEqualTo(userId.toString());

        session.migrateRemaining();

        assertThat(countPortfolios(session, userId)).isEqualTo(1);
    }

    @Test
    void v20AddsDefaultsNamedUniquenessAndPositiveQuantityConstraint() {
        var session = PostgresRepairHarness.newSession();
        session.migrateRemaining();

        UUID userId = UUID.randomUUID();
        UUID firstPortfolioId = UUID.randomUUID();
        UUID secondPortfolioId = UUID.randomUUID();

        session.jdbc()
                .update(
                        "INSERT INTO portfolios (id, user_id) VALUES (?, ?)",
                        firstPortfolioId,
                        userId.toString());

        assertThat(session.jdbc()
                        .queryForObject(
                                "SELECT version FROM portfolios WHERE id = ?",
                                Long.class,
                                firstPortfolioId))
                .isZero();
        assertThat(session.jdbc()
                        .queryForObject(
                                "SELECT updated_at IS NOT NULL FROM portfolios WHERE id = ?",
                                Boolean.class,
                                firstPortfolioId))
                .isTrue();
        assertThat(session.jdbc()
                        .queryForObject(
                                """
                                SELECT count(*)
                                  FROM pg_constraint
                                 WHERE conrelid = 'portfolios'::regclass
                                   AND conname = 'uq_portfolios_user_id'
                                """,
                                Integer.class))
                .isEqualTo(1);

        assertThatThrownBy(() -> session.jdbc()
                        .update(
                                "INSERT INTO portfolios (id, user_id) VALUES (?, ?)",
                                secondPortfolioId,
                                userId.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(session.jdbc()
                        .queryForObject(
                                """
                                SELECT count(*)
                                  FROM pg_constraint
                                 WHERE conrelid = 'asset_holdings'::regclass
                                   AND conname = 'chk_asset_holdings_quantity_positive'
                                """,
                                Integer.class))
                .isEqualTo(1);
        assertThat(session.jdbc()
                        .queryForObject(
                                """
                                SELECT column_default
                                  FROM information_schema.columns
                                 WHERE table_schema = 'public'
                                   AND table_name = 'asset_holdings'
                                   AND column_name = 'quantity'
                                """,
                                String.class))
                .isNull();

        assertThatThrownBy(() -> session.jdbc()
                        .update(
                                """
                                INSERT INTO asset_holdings (portfolio_id, asset_ticker, quantity)
                                VALUES (?, 'V20_NONPOSITIVE', 0)
                                """,
                                firstPortfolioId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v20FailsRatherThanClampingWhenNonPositiveQuantityAlreadyExists() {
        var session = PostgresRepairHarness.newSession();
        session.migrateTo("19");

        UUID portfolioId = UUID.randomUUID();
        UUID holdingId = UUID.randomUUID();
        session.jdbc()
                .update(
                        "INSERT INTO portfolios (id, user_id, created_at) VALUES (?, ?, now())",
                        portfolioId,
                        UUID.randomUUID().toString());
        session.jdbc()
                .update(
                        """
                        INSERT INTO asset_holdings (id, portfolio_id, asset_ticker, quantity)
                        VALUES (?, ?, 'V20_PREEXISTING_ZERO', 0)
                        """,
                        holdingId,
                        portfolioId);

        assertThatThrownBy(session::migrateRemaining).isInstanceOf(FlywayException.class);

        Integer successfulV20 = session.jdbc()
                .queryForObject(
                        """
                        SELECT count(*)
                          FROM flyway_schema_history
                         WHERE version = '20' AND success = true
                        """,
                        Integer.class);
        assertThat(successfulV20).isZero();

        assertThat(session.jdbc()
                        .queryForObject(
                                "SELECT quantity FROM asset_holdings WHERE id = ?",
                                java.math.BigDecimal.class,
                                holdingId))
                .isEqualByComparingTo("0");
    }

    private static int countPortfolios(PostgresRepairHarness.Session session, UUID userId) {
        Integer count = session.jdbc()
                .queryForObject(
                        "SELECT count(*) FROM portfolios WHERE user_id = ?",
                        Integer.class,
                        userId.toString());
        return count == null ? 0 : count;
    }
}
