package com.wealth.portfolio.seed.rca;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.portfolio.TestContainerImages;
import com.wealth.portfolio.seed.rca.SpecA912ConnectionOriginProbe.AttemptEvidence;
import com.wealth.portfolio.seed.rca.SpecA912ConnectionOriginProbe.EndpointLabel;
import com.wealth.portfolio.seed.rca.SpecA912ConnectionOriginProbe.ProbeMatrix;
import com.wealth.portfolio.seed.rca.SpecA912ConnectionOriginProbe.SettingSource;
import com.wealth.portfolio.seed.rca.SpecA912ConnectionOriginProbe.Verdict;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Disposable PostgreSQL proofs for Spec A 9.12 connection-origin detection and non-interference.
 * Fixture mutations stay in this test class; the collector remains read-only.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class SpecA912ConnectionOriginProbeIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @BeforeEach
    void resetDefaults() throws Exception {
        resetRoleAndDatabaseDefaults();
    }

    @Test
    void baselineFreshConnectionsObserveOffOffAndNonPersistentSource() throws Exception {
        AttemptEvidence evidence = collectBaseline();
        assertThat(evidence.failed()).isFalse();
        assertThat(evidence.defaultTransactionReadOnly()).isEqualTo("off");
        assertThat(evidence.transactionReadOnly()).isEqualTo("off");
        assertThat(evidence.source())
                .isNotIn(
                        SettingSource.USER,
                        SettingSource.DATABASE,
                        SettingSource.DATABASE_USER,
                        SettingSource.CLIENT);
    }

    @Test
    void alterRoleDefaultProducesUserSourceOnFreshConnection() throws Exception {
        executeAdmin("ALTER ROLE wealth_user SET default_transaction_read_only = on");
        AttemptEvidence evidence = collectBaseline();
        assertThat(evidence.allOn()).isTrue();
        assertThat(evidence.source()).isEqualTo(SettingSource.USER);
    }

    @Test
    void alterDatabaseDefaultProducesDatabaseSourceOnFreshConnection() throws Exception {
        executeAdmin("ALTER ROLE wealth_user RESET default_transaction_read_only");
        executeAdmin("ALTER DATABASE portfolio_db SET default_transaction_read_only = on");
        AttemptEvidence evidence = collectBaseline();
        assertThat(evidence.allOn()).isTrue();
        assertThat(evidence.source()).isEqualTo(SettingSource.DATABASE);
    }

    @Test
    void alterRoleInDatabaseProducesDatabaseUserSource() throws Exception {
        executeAdmin("ALTER DATABASE portfolio_db RESET default_transaction_read_only");
        executeAdmin("ALTER ROLE wealth_user RESET default_transaction_read_only");
        executeAdmin(
                "ALTER ROLE wealth_user IN DATABASE portfolio_db SET default_transaction_read_only = on");
        AttemptEvidence evidence = collectBaseline();
        assertThat(evidence.allOn()).isTrue();
        assertThat(evidence.source()).isEqualTo(SettingSource.DATABASE_USER);
        assertThat(evidence.catalogDefaults()).isNotEmpty();
        String formatted =
                SpecA912ConnectionOriginProbe.format(
                        SpecA912ConnectionOriginProbe.requireCompleteMatrix(pad(evidence)),
                        Verdict.PERSISTENT_DEFAULT_PATH_EVIDENCED,
                        SettingSource.DATABASE_USER);
        assertThat(formatted)
                .contains("\"scope\":\"")
                .contains("\"value\":\"on\"")
                .doesNotContain("wealth_user")
                .doesNotContain("portfolio_db");
    }

    @Test
    void jdbcOptionsOnlyProducesClientSourceOnDisposableFixture() throws Exception {
        AttemptEvidence evidence = collectWithOptions("-cdefault_transaction_read_only=on");
        assertThat(evidence.allOn()).isTrue();
        assertThat(evidence.source()).isEqualTo(SettingSource.CLIENT);
    }

    @Test
    void baselineDirectPairedWithClientOptionClassifiesPooledPathDivergence() throws Exception {
        List<AttemptEvidence> attempts = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            attempts.add(
                    SpecA912ConnectionOriginProbe.collectOnce(
                            EndpointLabel.POOLED,
                            i,
                            clientOptionUrl("-cdefault_transaction_read_only=on"),
                            postgres.getUsername(),
                            postgres.getPassword()));
        }
        for (int i = 1; i <= 5; i++) {
            attempts.add(
                    SpecA912ConnectionOriginProbe.collectOnce(
                            EndpointLabel.DIRECT,
                            i,
                            postgres.getJdbcUrl(),
                            postgres.getUsername(),
                            postgres.getPassword()));
        }
        ProbeMatrix matrix = SpecA912ConnectionOriginProbe.requireCompleteMatrix(attempts);
        assertThat(SpecA912ConnectionOriginProbe.classify(matrix))
                .isEqualTo(Verdict.POOLED_PATH_DIVERGENCE_PROVEN);
        String formatted =
                SpecA912ConnectionOriginProbe.format(
                        matrix, Verdict.POOLED_PATH_DIVERGENCE_PROVEN, null);
        assertThat(formatted)
                .contains("POOLED_PATH_DIVERGENCE_PROVEN")
                .doesNotContain("SET ")
                .doesNotContain("ALTER ");
    }

    @Test
    void fiveFreshConnectionsCloseAndIndependentSelectStillWorks() throws Exception {
        List<Long> pids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            AttemptEvidence evidence =
                    SpecA912ConnectionOriginProbe.collectOnce(
                            EndpointLabel.POOLED,
                            i,
                            postgres.getJdbcUrl(),
                            postgres.getUsername(),
                            postgres.getPassword());
            assertThat(evidence.failed()).isFalse();
            pids.add(evidence.backendPid());
        }
        assertThat(pids).hasSize(5).doesNotContainNull();
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void collectorNeverChangesDefaultTransactionReadOnly() throws Exception {
        String before = readControlDefault();
        for (int i = 1; i <= 5; i++) {
            SpecA912ConnectionOriginProbe.collectOnce(
                    EndpointLabel.DIRECT,
                    i,
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword());
        }
        String after = readControlDefault();
        assertThat(after).isEqualTo(before).isEqualTo("off");
    }

    private static AttemptEvidence collectBaseline() {
        return SpecA912ConnectionOriginProbe.collectOnce(
                EndpointLabel.DIRECT,
                1,
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
    }

    private static AttemptEvidence collectWithOptions(String options) {
        return SpecA912ConnectionOriginProbe.collectOnce(
                EndpointLabel.DIRECT,
                1,
                clientOptionUrl(options),
                postgres.getUsername(),
                postgres.getPassword());
    }

    private static String clientOptionUrl(String options) {
        String base = postgres.getJdbcUrl();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator + "options=" + options;
    }

    private static void resetRoleAndDatabaseDefaults() throws Exception {
        executeAdmin("ALTER ROLE wealth_user RESET default_transaction_read_only");
        executeAdmin("ALTER DATABASE portfolio_db RESET default_transaction_read_only");
        executeAdmin("ALTER ROLE wealth_user IN DATABASE portfolio_db RESET default_transaction_read_only");
    }

    private static void executeAdmin(String sql) throws Exception {
        try (Connection connection = openWritableAdmin();
                Statement statement = connection.createStatement()) {
            statement.execute("SET default_transaction_read_only TO off");
            statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE");
            statement.execute(sql);
        }
    }

    private static String readControlDefault() throws Exception {
        try (Connection connection = openWritableAdmin();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SHOW default_transaction_read_only")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static Connection open() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", postgres.getUsername());
        properties.setProperty("password", postgres.getPassword());
        return DriverManager.getConnection(postgres.getJdbcUrl(), properties);
    }

    private static Connection openWritableAdmin() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", postgres.getUsername());
        properties.setProperty("password", postgres.getPassword());
        properties.setProperty("options", "-cdefault_transaction_read_only=off");
        return DriverManager.getConnection(postgres.getJdbcUrl(), properties);
    }

    private static List<AttemptEvidence> pad(AttemptEvidence seed) {
        List<AttemptEvidence> attempts = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            attempts.add(
                    new AttemptEvidence(
                            EndpointLabel.POOLED,
                            i,
                            seed.backendPid(),
                            seed.jdbcReadOnly(),
                            seed.autoCommit(),
                            seed.defaultTransactionReadOnly(),
                            seed.transactionReadOnly(),
                            seed.pgIsInRecovery(),
                            seed.setting(),
                            seed.resetVal(),
                            seed.source(),
                            seed.catalogDefaults(),
                            null,
                            null));
        }
        for (int i = 1; i <= 5; i++) {
            attempts.add(
                    new AttemptEvidence(
                            EndpointLabel.DIRECT,
                            i,
                            seed.backendPid(),
                            seed.jdbcReadOnly(),
                            seed.autoCommit(),
                            seed.defaultTransactionReadOnly(),
                            seed.transactionReadOnly(),
                            seed.pgIsInRecovery(),
                            seed.setting(),
                            seed.resetVal(),
                            seed.source(),
                            seed.catalogDefaults(),
                            null,
                            null));
        }
        return attempts;
    }
}
