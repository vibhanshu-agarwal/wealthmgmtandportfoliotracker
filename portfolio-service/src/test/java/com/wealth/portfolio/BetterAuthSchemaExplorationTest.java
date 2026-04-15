package com.wealth.portfolio;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug condition exploration test for the Better Auth sign-in fix.
 *
 * <p>These tests verify that the {@code ba_*} tables required by Better Auth
 * exist after Flyway migrations run. On UNFIXED code (V1–V7 only), these tests
 * are EXPECTED TO FAIL — failure confirms the bug (missing tables).
 *
 * <p>After the fix (V8 + V9 migrations added), these tests should PASS.
 *
 * <p><b>Validates: Requirements 1.1, 1.2, 1.3, 1.5</b>
 *
 * @see <a href="../../.kiro/specs/better-auth-signin-fix/bugfix.md">Bugfix Spec</a>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class BetterAuthSchemaExplorationTest {

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
        // Disable Kafka — not needed for schema exploration tests
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    // ── Test 1: Table existence ──────────────────────────────────────────────
    // After Flyway migrations run, all four ba_* tables must exist.

    @Test
    void allBetterAuthTablesShouldExist() {
        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name LIKE 'ba_%'
                ORDER BY table_name
                """,
                String.class);

        assertThat(tables)
                .as("All four Better Auth tables should exist after Flyway migrations")
                .containsExactlyInAnyOrder("ba_user", "ba_session", "ba_account", "ba_verification");
    }

    // ── Test 2: Schema correctness ───────────────────────────────────────────
    // ba_user must have the expected columns.

    @Test
    void baUserTableShouldHaveExpectedColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'ba_user'
                ORDER BY ordinal_position
                """,
                String.class);

        assertThat(columns)
                .as("ba_user table should have all required columns")
                .containsExactlyInAnyOrder("id", "name", "email", "emailVerified", "image", "createdAt", "updatedAt");
    }

    // ── Test 3: Index existence ──────────────────────────────────────────────
    // Better Auth expects specific indexes on ba_session, ba_account, ba_verification.

    @Test
    void betterAuthIndexesShouldExist() {
        List<String> indexes = jdbcTemplate.queryForList(
                """
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname IN ('ba_session_userId_idx', 'ba_account_userId_idx', 'ba_verification_identifier_idx')
                ORDER BY indexname
                """,
                String.class);

        assertThat(indexes)
                .as("All three Better Auth indexes should exist")
                .containsExactlyInAnyOrder(
                        "ba_session_userId_idx",
                        "ba_account_userId_idx",
                        "ba_verification_identifier_idx");
    }

    // ── Test 4: Foreign key constraints ──────────────────────────────────────
    // ba_session.userId and ba_account.userId must reference ba_user.id.

    @Test
    void foreignKeyConstraintsShouldExist() {
        List<String> fkConstraints = jdbcTemplate.queryForList(
                """
                SELECT tc.table_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.referential_constraints rc
                  ON tc.constraint_name = rc.constraint_name
                  AND tc.constraint_schema = rc.constraint_schema
                JOIN information_schema.table_constraints tc2
                  ON rc.unique_constraint_name = tc2.constraint_name
                  AND rc.unique_constraint_schema = tc2.constraint_schema
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND tc2.table_name = 'ba_user'
                  AND tc.table_name IN ('ba_session', 'ba_account')
                ORDER BY tc.table_name
                """,
                String.class);

        assertThat(fkConstraints)
                .as("ba_session and ba_account should have FK constraints referencing ba_user")
                .containsExactlyInAnyOrder("ba_session", "ba_account");
    }

    // ── Test 5: Dev user seed ────────────────────────────────────────────────
    // The dev user (dev@localhost.local) must be seeded after migrations.

    @Test
    void devUserShouldBeSeeded() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ba_user WHERE email = 'dev@localhost.local'",
                Integer.class);

        assertThat(count)
                .as("Dev user (dev@localhost.local) should exist in ba_user")
                .isEqualTo(1);
    }
}
