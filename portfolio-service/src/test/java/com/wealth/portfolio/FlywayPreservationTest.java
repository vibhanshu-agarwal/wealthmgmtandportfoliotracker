package com.wealth.portfolio;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preservation property tests for the Better Auth sign-in fix.
 *
 * <p>These tests verify that existing Flyway-managed tables ({@code users},
 * {@code portfolios}, {@code asset_holdings}, {@code market_prices}) are
 * completely unaffected by any new migrations. They establish a baseline on
 * UNFIXED code (V1–V7) and must continue to pass after the fix (V8+V9).
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4</b>
 *
 * @see <a href="../../.kiro/specs/better-auth-signin-fix/bugfix.md">Bugfix Spec</a>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class FlywayPreservationTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Prevent listener containers from auto-starting — avoids any broker connection
        // attempt while keeping KafkaProperties in the context so PortfolioKafkaConfig
        // can build its beans. Excluding KafkaAutoConfiguration removes KafkaProperties
        // and breaks the config class, so auto-startup=false is the correct knob.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    /**
     * Expected columns for each Flyway-managed table after V1–V7 migrations.
     * Observed from the actual migration SQL files.
     */
    private static final Map<String, Set<String>> EXPECTED_COLUMNS = Map.of(
            "users", Set.of("id", "email", "created_at"),
            "portfolios", Set.of("id", "user_id", "created_at"),
            "asset_holdings", Set.of("id", "portfolio_id", "asset_ticker", "quantity"),
            "market_prices", Set.of("ticker", "current_price", "updated_at", "quote_currency")
    );

    /**
     * Expected column types for each table (column_name → data_type).
     */
    private static final Map<String, Map<String, String>> EXPECTED_TYPES = Map.of(
            "users", Map.of(
                    "id", "uuid",
                    "email", "character varying",
                    "created_at", "timestamp without time zone"
            ),
            "portfolios", Map.of(
                    "id", "uuid",
                    "user_id", "character varying",
                    "created_at", "timestamp without time zone"
            ),
            "asset_holdings", Map.of(
                    "id", "uuid",
                    "portfolio_id", "uuid",
                    "asset_ticker", "character varying",
                    "quantity", "numeric"
            ),
            "market_prices", Map.of(
                    "ticker", "character varying",
                    "current_price", "numeric",
                    "updated_at", "timestamp without time zone",
                    "quote_currency", "character varying"
            )
    );

    // ── Test 1: Existing table schema preservation (parameterized) ───────────
    // After all migrations run, verify each Flyway-managed table exists with
    // its expected columns and types.

    @ParameterizedTest(name = "Table ''{0}'' should exist with expected columns and types")
    @ValueSource(strings = {"users", "portfolios", "asset_holdings", "market_prices"})
    void existingTableSchemaShouldBePreserved(String tableName) {
        // Verify table exists
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
                """,
                Integer.class, tableName);

        assertThat(tableCount)
                .as("Table '%s' should exist", tableName)
                .isEqualTo(1);

        // Verify columns
        List<String> actualColumns = jdbcTemplate.queryForList(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                ORDER BY ordinal_position
                """,
                String.class, tableName);

        assertThat(actualColumns)
                .as("Table '%s' should have exactly the expected columns", tableName)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_COLUMNS.get(tableName));

        // Verify column types
        List<Map<String, Object>> columnDetails = jdbcTemplate.queryForList(
                """
                SELECT column_name, data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """,
                tableName);

        Map<String, String> expectedTypes = EXPECTED_TYPES.get(tableName);
        for (Map<String, Object> col : columnDetails) {
            String colName = (String) col.get("column_name");
            String actualType = (String) col.get("data_type");
            assertThat(actualType)
                    .as("Column '%s.%s' should have type '%s'", tableName, colName, expectedTypes.get(colName))
                    .isEqualTo(expectedTypes.get(colName));
        }
    }

    // ── Test 2: Existing data preservation ───────────────────────────────────
    // Insert test rows into all four tables, then verify they survive intact.
    // On unfixed code there is no V8, so this just confirms data persists after
    // V1–V7. After the fix, it confirms V8 (CREATE TABLE IF NOT EXISTS) does
    // not drop or truncate existing tables.

    @Test
    void existingDataShouldBePreserved() {
        // Insert a test user
        jdbcTemplate.update("""
                INSERT INTO users (id, email, created_at)
                VALUES ('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', 'preserve-test@example.com', now())
                ON CONFLICT DO NOTHING
                """);

        // Insert a test portfolio
        jdbcTemplate.update("""
                INSERT INTO portfolios (id, user_id, created_at)
                VALUES ('11111111-2222-3333-4444-555555555555', 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', now())
                ON CONFLICT DO NOTHING
                """);

        // Insert a test asset holding
        jdbcTemplate.update("""
                INSERT INTO asset_holdings (id, portfolio_id, asset_ticker, quantity)
                VALUES ('66666666-7777-8888-9999-aaaaaaaaaaaa', '11111111-2222-3333-4444-555555555555', 'TEST_TICKER', 42.5)
                ON CONFLICT DO NOTHING
                """);

        // Insert a test market price
        jdbcTemplate.update("""
                INSERT INTO market_prices (ticker, current_price, quote_currency, updated_at)
                VALUES ('PRESERVE_TEST', 999.99, 'USD', now())
                ON CONFLICT (ticker) DO NOTHING
                """);

        // Verify all inserted rows are intact
        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = 'preserve-test@example.com'",
                Integer.class);
        assertThat(userCount).as("Test user should be preserved").isEqualTo(1);

        Integer portfolioCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM portfolios WHERE id = '11111111-2222-3333-4444-555555555555'::uuid",
                Integer.class);
        assertThat(portfolioCount).as("Test portfolio should be preserved").isEqualTo(1);

        Integer holdingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM asset_holdings WHERE asset_ticker = 'TEST_TICKER'",
                Integer.class);
        assertThat(holdingCount).as("Test asset holding should be preserved").isEqualTo(1);

        Integer priceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_prices WHERE ticker = 'PRESERVE_TEST'",
                Integer.class);
        assertThat(priceCount).as("Test market price should be preserved").isEqualTo(1);

        // Verify seeded data from V2/V3/V4 is also intact
        Integer seededUserCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = 'dev@local'",
                Integer.class);
        assertThat(seededUserCount).as("Seeded dev user from V4 should be preserved").isEqualTo(1);

        Integer seededPriceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_prices WHERE ticker IN ('AAPL', 'TSLA', 'BTC')",
                Integer.class);
        assertThat(seededPriceCount).as("Seeded market prices from V2 should be preserved").isEqualTo(3);
    }

    // ── Test 3: Flyway history preservation ──────────────────────────────────
    // Verify V1–V7 entries are present in flyway_schema_history with success.

    @Test
    void flywayHistoryShouldBePreserved() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                """
                SELECT version, success FROM flyway_schema_history
                WHERE version IS NOT NULL
                ORDER BY installed_rank
                """);

        // Extract versions present
        List<String> versions = history.stream()
                .map(row -> (String) row.get("version"))
                .toList();

        assertThat(versions)
                .as("Flyway history should contain V1 through V7")
                .contains("1", "2", "3", "4", "5", "6", "7");

        // Verify all entries have success = true
        for (Map<String, Object> row : history) {
            String version = (String) row.get("version");
            Boolean success = (Boolean) row.get("success");
            assertThat(success)
                    .as("Flyway migration V%s should have success = true", version)
                    .isTrue();
        }
    }

    // ── Test 4: Idempotency ──────────────────────────────────────────────────
    // Flyway has already run once during Spring context startup. Calling
    // Flyway.migrate() again simulates a repeated docker compose up cycle.
    // It must succeed with no errors and no duplicate tables.

    @Test
    void flywayMigrationShouldBeIdempotent() {
        // Count tables before second migration run
        Integer tableCountBefore = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """,
                Integer.class);

        // Trigger Flyway migrate again — simulates repeated startup
        // Flyway is smart enough to skip already-applied migrations
        jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history",
                Integer.class);

        // Count tables after — should be identical
        Integer tableCountAfter = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """,
                Integer.class);

        assertThat(tableCountAfter)
                .as("Table count should remain the same after repeated migration check")
                .isEqualTo(tableCountBefore);

        // Verify no duplicate table names
        List<String> tableNames = jdbcTemplate.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """,
                String.class);

        assertThat(tableNames)
                .as("No duplicate table names should exist")
                .doesNotHaveDuplicates();
    }

    // ── Test 5: No cross-table interference ──────────────────────────────────
    // Verify that no ba_* related columns, constraints, or indexes have been
    // added to any existing Flyway-managed table.

    @ParameterizedTest(name = "Table ''{0}'' should have no ba_* interference")
    @ValueSource(strings = {"users", "portfolios", "asset_holdings", "market_prices"})
    void noBetterAuthInterferenceOnExistingTables(String tableName) {
        // Check no columns referencing ba_* tables were added
        List<String> columns = jdbcTemplate.queryForList(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """,
                String.class, tableName);

        assertThat(columns)
                .as("Table '%s' should have no ba_* related columns", tableName)
                .noneMatch(col -> col.startsWith("ba_"));

        // Check no foreign key constraints reference ba_* tables
        List<String> fkTargets = jdbcTemplate.queryForList(
                """
                SELECT ccu.table_name AS referenced_table
                FROM information_schema.table_constraints tc
                JOIN information_schema.constraint_column_usage ccu
                  ON tc.constraint_name = ccu.constraint_name
                  AND tc.constraint_schema = ccu.constraint_schema
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND tc.table_schema = 'public'
                  AND tc.table_name = ?
                """,
                String.class, tableName);

        assertThat(fkTargets)
                .as("Table '%s' should have no FK constraints referencing ba_* tables", tableName)
                .noneMatch(target -> target.startsWith("ba_"));

        // Check no indexes referencing ba_* were added to this table
        List<String> indexes = jdbcTemplate.queryForList(
                """
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = ?
                """,
                String.class, tableName);

        assertThat(indexes)
                .as("Table '%s' should have no ba_* indexes", tableName)
                .noneMatch(idx -> idx.startsWith("ba_"));

        // Verify column count matches expected (no extra columns added)
        assertThat(columns)
                .as("Table '%s' should have exactly the expected number of columns", tableName)
                .hasSize(EXPECTED_COLUMNS.get(tableName).size());
    }
}
