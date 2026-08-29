package com.wealth.portfolio.seed.rca;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.portfolio.TestContainerImages;
import com.wealth.portfolio.seed.rca.SpecA912StatementHistoryProbe.CapabilityEvidence;
import com.wealth.portfolio.seed.rca.SpecA912StatementHistoryProbe.Classification;
import com.wealth.portfolio.seed.rca.SpecA912StatementHistoryProbe.ComputeQueryId;
import com.wealth.portfolio.seed.rca.SpecA912StatementHistoryProbe.HistoryEvidence;
import com.wealth.portfolio.seed.rca.SpecA912StatementHistoryProbe.NonClaim;
import com.wealth.portfolio.seed.rca.SpecA912StatementHistoryProbe.SetterShape;
import com.wealth.portfolio.seed.rca.SpecA912StatementHistoryProbe.StatementTrack;
import com.wealth.portfolio.seed.rca.SpecA912StatementHistoryProbe.TrackUtility;
import com.wealth.portfolio.seed.rca.SpecA912StatementHistoryProbe.Verdict;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Disposable PostgreSQL 18.4 proofs for Spec A 9.12 statement-history classification and
 * non-interference. Fixture mutations stay in helpers named {@code disposableOnly*}; the collector
 * remains read-only.
 */
@Tag("integration")
@Testcontainers
class SpecA912StatementHistoryProbeIT {

    private static final String WRITER = "fixture_writer";
    private static final String LIMITED = "fixture_limited";
    private static final String NONINHERIT = "fixture_noninherit";
    private static final String WRITER_PASSWORD = "writer_pass_canary";
    private static final String LIMITED_PASSWORD = "limited_pass_canary";
    private static final String NONINHERIT_PASSWORD = "noninherit_pass_canary";
    private static final Instant COVERING_RESET = Instant.parse("2026-08-27T00:00:00Z");

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass")
                    .withCommand(
                            "postgres",
                            "-c",
                            "shared_preload_libraries=pg_stat_statements",
                            "-c",
                            "pg_stat_statements.track=top",
                            "-c",
                            "pg_stat_statements.track_utility=on",
                            "-c",
                            "compute_query_id=auto");

    @BeforeAll
    static void disposableOnlyPrepareFixture() throws Exception {
        disposableOnlyCreateExtension();
        disposableOnlyCreateFixtureRoles();
        disposableOnlyCreateControlTable();
    }

    @Test
    void disposableSetterShapesProduceExactRetainedCounts() throws Exception {
        disposableOnlyResetStatements();
        disposableOnlyExecuteReadOnlyShapesOnce();

        Classification result = collectAs(postgres.getUsername(), postgres.getPassword());
        assertThat(result.canReadAllStatementText()).isTrue();
        assertThat(result.shapeCounts().get(SetterShape.SET_DEFAULT_TRANSACTION_READ_ONLY))
                .isEqualTo(1L);
        assertThat(result.shapeCounts().get(SetterShape.SET_SESSION_CHARACTERISTICS_READ_ONLY))
                .isEqualTo(1L);
        assertThat(result.shapeCounts().get(SetterShape.SET_TRANSACTION_READ_ONLY)).isEqualTo(1L);
        assertThat(result.shapeCounts().get(SetterShape.RESET_DEFAULT_TRANSACTION_READ_ONLY))
                .isEqualTo(1L);
        assertThat(result.shapeCounts().get(SetterShape.ALTER_ROLE_DEFAULT_TRANSACTION_READ_ONLY))
                .isEqualTo(1L);
        assertThat(result.shapeCounts().get(SetterShape.ALTER_DATABASE_DEFAULT_TRANSACTION_READ_ONLY))
                .isEqualTo(1L);
        assertThat(result.shapeCounts().get(SetterShape.DISCARD_ALL)).isEqualTo(1L);
        assertThat(result.verdict()).isEqualTo(Verdict.SETTER_SHAPE_PRESENT_OUTSIDE_INCIDENT_COVERAGE);
        assertThat(result.coveringIncident()).isFalse();
        assertRedacted(SpecA912StatementHistoryProbe.format(result));
    }

    @Test
    void nonReadOnlyTransactionStatementsDoNotIncrementReadOnlyShapes() throws Exception {
        disposableOnlyResetStatements();
        disposableOnlyExecuteNonMatchingTransactionStatements();

        Classification result = collectAs(postgres.getUsername(), postgres.getPassword());
        assertThat(result.shapeCounts().get(SetterShape.SET_SESSION_CHARACTERISTICS_READ_ONLY))
                .isZero();
        assertThat(result.shapeCounts().get(SetterShape.SET_TRANSACTION_READ_ONLY)).isZero();
        assertThat(result.shapeCounts().get(SetterShape.SET_DEFAULT_TRANSACTION_READ_ONLY)).isZero();
    }

    @Test
    void limitedAndNonInheritedRolesDoNotGainGlobalStatementVisibility() throws Exception {
        disposableOnlyResetStatements();
        disposableOnlyExecuteReadOnlyShapesOnce();

        Classification admin = collectAs(postgres.getUsername(), postgres.getPassword());
        Classification limited = collectAs(LIMITED, LIMITED_PASSWORD);
        Classification noninherit = collectAs(NONINHERIT, NONINHERIT_PASSWORD);

        assertThat(admin.canReadAllStatementText()).isTrue();
        assertThat(admin.shapeCounts().get(SetterShape.SET_DEFAULT_TRANSACTION_READ_ONLY))
                .isEqualTo(1L);
        assertThat(limited.canReadAllStatementText()).isFalse();
        assertThat(limited.shapeCounts().get(SetterShape.SET_DEFAULT_TRANSACTION_READ_ONLY))
                .isZero();
        assertThat(noninherit.canReadAllStatementText()).isFalse();
        assertThat(noninherit.shapeCounts().get(SetterShape.SET_DEFAULT_TRANSACTION_READ_ONLY))
                .isZero();

        Classification roleScoped =
                SpecA912StatementHistoryProbe.classify(
                        capabilityFrom(limited), coveringZeros());
        assertThat(roleScoped.verdict())
                .isEqualTo(Verdict.NO_CURRENT_ROLE_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS);
        Classification global =
                SpecA912StatementHistoryProbe.classify(capabilityFrom(admin), coveringZeros());
        assertThat(global.verdict())
                .isEqualTo(Verdict.NO_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS);
        assertRedacted(SpecA912StatementHistoryProbe.format(limited));
        assertRedacted(SpecA912StatementHistoryProbe.format(noninherit));
    }

    @Test
    void syntheticEvidenceCoversWindowEvictionTrackingAndHistoricalNonClaim() {
        CapabilityEvidence covering =
                new CapabilityEvidence(
                        true, true, StatementTrack.TOP, TrackUtility.ON, ComputeQueryId.AUTO, true);
        assertThat(
                        SpecA912StatementHistoryProbe.classify(
                                        covering,
                                        new HistoryEvidence(
                                                Instant.parse("2026-08-29T12:00:00Z"), 0L, zeros()))
                                .verdict())
                .isEqualTo(Verdict.STATS_WINDOW_NOT_COVERING_INCIDENT);
        assertThat(
                        SpecA912StatementHistoryProbe.classify(
                                        covering, new HistoryEvidence(COVERING_RESET, 9L, zeros()))
                                .verdict())
                .isEqualTo(Verdict.STATS_EVICTION_PREVENTS_ABSENCE_CLAIM);
        assertThat(
                        SpecA912StatementHistoryProbe.classify(
                                        new CapabilityEvidence(
                                                true,
                                                true,
                                                StatementTrack.NONE,
                                                TrackUtility.ON,
                                                ComputeQueryId.AUTO,
                                                true),
                                        new HistoryEvidence(COVERING_RESET, 0L, zeros()))
                                .verdict())
                .isEqualTo(Verdict.STATEMENT_TRACKING_DISABLED);
        assertThat(
                        SpecA912StatementHistoryProbe.classify(
                                        new CapabilityEvidence(
                                                true,
                                                true,
                                                StatementTrack.TOP,
                                                TrackUtility.ON,
                                                ComputeQueryId.OFF,
                                                true),
                                        new HistoryEvidence(COVERING_RESET, 0L, zeros()))
                                .verdict())
                .isEqualTo(Verdict.QUERY_ID_CALCULATION_DISABLED);
        EnumMap<SetterShape, Long> positive = zeros();
        positive.put(SetterShape.DISCARD_ALL, 2L);
        Classification presentWithDisabledTracking =
                SpecA912StatementHistoryProbe.classify(
                        new CapabilityEvidence(
                                true,
                                true,
                                StatementTrack.NONE,
                                TrackUtility.OFF,
                                ComputeQueryId.OFF,
                                true),
                        new HistoryEvidence(COVERING_RESET, 0L, positive));
        assertThat(presentWithDisabledTracking.verdict())
                .isEqualTo(Verdict.SETTER_SHAPE_PRESENT_IN_COVERING_STATS);
        assertThat(presentWithDisabledTracking.nonClaims())
                .contains(NonClaim.HISTORICAL_TRACKING_CONFIGURATION_NOT_PROVEN);
    }

    @Test
    void collectorDoesNotMutateRoleDatabaseDefaultsOrControlTable() throws Exception {
        disposableOnlyResetRoleAndDatabaseDefaults();
        String beforeDefaults = snapshotDefaults();
        String beforeControl = snapshotControlTable();

        Classification result = collectAs(postgres.getUsername(), postgres.getPassword());
        assertThat(result).isNotNull();

        assertThat(snapshotDefaults()).isEqualTo(beforeDefaults);
        assertThat(snapshotControlTable()).isEqualTo(beforeControl);
        assertThat(independentSelectOne()).isEqualTo(1);
        assertRedacted(SpecA912StatementHistoryProbe.format(result));
    }

    private static Classification collectAs(String username, String password) throws Exception {
        try (Connection connection = open(username, password)) {
            return SpecA912StatementHistoryProbe.collect(connection);
        }
    }

    private static void disposableOnlyCreateExtension() throws Exception {
        executeAdmin("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
    }

    private static void disposableOnlyCreateFixtureRoles() throws Exception {
        executeAdmin("DROP ROLE IF EXISTS " + WRITER);
        executeAdmin("DROP ROLE IF EXISTS " + LIMITED);
        executeAdmin("DROP ROLE IF EXISTS " + NONINHERIT);
        executeAdmin("CREATE ROLE " + WRITER + " LOGIN PASSWORD '" + WRITER_PASSWORD + "'");
        executeAdmin("CREATE ROLE " + LIMITED + " LOGIN PASSWORD '" + LIMITED_PASSWORD + "'");
        executeAdmin("CREATE ROLE " + NONINHERIT + " LOGIN PASSWORD '" + NONINHERIT_PASSWORD + "'");
        executeAdmin("GRANT CONNECT ON DATABASE portfolio_db TO " + WRITER);
        executeAdmin("GRANT CONNECT ON DATABASE portfolio_db TO " + LIMITED);
        executeAdmin("GRANT CONNECT ON DATABASE portfolio_db TO " + NONINHERIT);
        executeAdmin("GRANT pg_read_all_stats TO " + NONINHERIT + " WITH INHERIT FALSE");
    }

    private static void disposableOnlyCreateControlTable() throws Exception {
        executeAdmin("DROP TABLE IF EXISTS rca_statement_history_control");
        executeAdmin("CREATE TABLE rca_statement_history_control (id int PRIMARY KEY, payload text)");
        executeAdmin("INSERT INTO rca_statement_history_control VALUES (1, 'control-sentinel')");
    }

    private static void disposableOnlyResetStatements() throws Exception {
        executeAdmin("SELECT pg_stat_statements_reset()");
    }

    private static void disposableOnlyResetRoleAndDatabaseDefaults() throws Exception {
        executeAdmin("ALTER ROLE " + WRITER + " RESET default_transaction_read_only");
        executeAdmin("ALTER ROLE wealth_user RESET default_transaction_read_only");
        executeAdmin("ALTER DATABASE portfolio_db RESET default_transaction_read_only");
    }

    private static void disposableOnlyExecuteReadOnlyShapesOnce() throws Exception {
        disposableOnlyResetRoleAndDatabaseDefaults();
        try (Connection connection = open(WRITER, WRITER_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("SET default_transaction_read_only = on");
            statement.execute("RESET default_transaction_read_only");
            statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
            statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE");
            statement.execute("BEGIN");
            statement.execute("SET TRANSACTION READ ONLY");
            statement.execute("ROLLBACK");
            statement.execute("DISCARD ALL");
        }
        executeAdmin("ALTER ROLE " + WRITER + " SET default_transaction_read_only = on");
        executeAdmin("ALTER ROLE " + WRITER + " RESET default_transaction_read_only");
        executeAdmin("ALTER DATABASE portfolio_db SET default_transaction_read_only = on");
        executeAdmin("ALTER DATABASE portfolio_db RESET default_transaction_read_only");
    }

    private static void disposableOnlyExecuteNonMatchingTransactionStatements() throws Exception {
        try (Connection connection = open(WRITER, WRITER_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE");
            statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            statement.execute("BEGIN");
            statement.execute("SET TRANSACTION READ WRITE");
            statement.execute("ROLLBACK");
            statement.execute("BEGIN");
            statement.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE");
            statement.execute("ROLLBACK");
            statement.execute("BEGIN");
            statement.execute("SET TRANSACTION DEFERRABLE");
            statement.execute("ROLLBACK");
            try {
                statement.execute("BEGIN");
                statement.execute("SET TRANSACTION SNAPSHOT '00000000-00000000-1'");
            } catch (Exception ignored) {
                statement.execute("ROLLBACK");
            }
        }
    }

    private static void executeAdmin(String sql) throws Exception {
        try (Connection connection = openWritableAdmin();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection openWritableAdmin() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", postgres.getUsername());
        properties.setProperty("password", postgres.getPassword());
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "15");
        properties.setProperty("options", "-cdefault_transaction_read_only=off");
        return DriverManager.getConnection(postgres.getJdbcUrl(), properties);
    }

    private static String snapshotDefaults() throws Exception {
        try (Connection connection = open(postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                """
                                SELECT COALESCE(md5(string_agg(
                                    setdatabase::text || ':' || setrole::text || ':' || array_to_string(setconfig, ','),
                                    '|' ORDER BY setdatabase, setrole)), '')
                                FROM pg_db_role_setting
                                WHERE array_to_string(setconfig, ',') LIKE '%default_transaction_read_only%'
                                """)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static String snapshotControlTable() throws Exception {
        try (Connection connection = open(postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT count(*), md5(string_agg(id::text || ':' || payload, '|' ORDER BY id)) "
                                        + "FROM rca_statement_history_control")) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1) + ":" + rs.getString(2);
        }
    }

    private static int independentSelectOne() throws Exception {
        try (Connection connection = open(postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT 1")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static Connection open(String username, String password) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "15");
        return DriverManager.getConnection(postgres.getJdbcUrl(), properties);
    }

    private static void assertRedacted(String formatted) {
        assertThat(formatted)
                .doesNotContain("jdbc:postgresql")
                .doesNotContain("neon.tech")
                .doesNotContain(WRITER)
                .doesNotContain(LIMITED)
                .doesNotContain(NONINHERIT)
                .doesNotContain("portfolio_db")
                .doesNotContain("wealth_user")
                .doesNotContain(WRITER_PASSWORD)
                .doesNotContain(LIMITED_PASSWORD)
                .doesNotContain("SET default_transaction_read_only")
                .doesNotContain("DISCARD ALL")
                .doesNotContain("ALTER ROLE")
                .doesNotContain("ALTER DATABASE");
    }

    private static CapabilityEvidence capabilityFrom(Classification classification) {
        return new CapabilityEvidence(
                classification.extensionInstalled(),
                classification.extensionAccessible(),
                classification.statementTrack(),
                classification.trackUtility(),
                classification.computeQueryId(),
                classification.canReadAllStatementText());
    }

    private static HistoryEvidence coveringZeros() {
        return new HistoryEvidence(COVERING_RESET, 0L, zeros());
    }

    private static EnumMap<SetterShape, Long> zeros() {
        EnumMap<SetterShape, Long> counts = new EnumMap<>(SetterShape.class);
        for (SetterShape shape : SetterShape.values()) {
            counts.put(shape, 0L);
        }
        return counts;
    }
}
