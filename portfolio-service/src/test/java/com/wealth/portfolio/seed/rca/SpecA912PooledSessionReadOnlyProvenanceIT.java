package com.wealth.portfolio.seed.rca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wealth.portfolio.AssetHoldingRepository;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.PortfolioService;
import com.wealth.portfolio.TestContainerImages;
import com.wealth.portfolio.seed.DemoPortfolioInitializer;
import com.wealth.portfolio.seed.PortfolioSeedService;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * Deterministic Testcontainers/Hikari/JPA matrix for Spec A 9.12 pooled read-only session
 * provenance. Uses a single-connection pool to prove JDBC flags vs PostgreSQL session GUCs and
 * whether ordinary Spring read-only paths poison the next transaction.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.demo.seed-on-startup=false"
        })
@ActiveProfiles("local")
class SpecA912PooledSessionReadOnlyProvenanceIT {

    private static final String DEMO_USER_ID = DemoPortfolioInitializer.DEMO_USER_ID;
    private static final String DML_PROBE_SQL = "DELETE FROM portfolios WHERE FALSE";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired AssetHoldingRepository assetHoldingRepository;
    @Autowired PortfolioSeedService seedService;
    @Autowired PortfolioService portfolioService;

    private record ProbeState(
            boolean springReadOnly,
            boolean jdbcReadOnly,
            String transactionReadOnly,
            String defaultTransactionReadOnly,
            int backendPid) {}

    private record FollowingTransactionEvidence(
            String path,
            String completionMode,
            int readOnlyBackendPid,
            int followingBackendPid,
            ProbeState followingState) {}

    @BeforeEach
    void shrinkPoolToSingleConnection() {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        hikari.setMaximumPoolSize(1);
        hikari.setMinimumIdle(1);
        if (hikari.getHikariPoolMXBean() != null) {
            hikari.getHikariPoolMXBean().softEvictConnections();
        }
    }

    @AfterEach
    void resetPooledSession() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE");
            assertThat(show(connection, "default_transaction_read_only")).isEqualTo("off");
        }
    }

    @Test
    void freshPooledSessionStartsReadWrite() {
        ProbeState state =
                new TransactionTemplate(transactionManager)
                        .execute(
                                status -> {
                                    assertThat(status.isNewTransaction()).isTrue();
                                    return captureCurrentTransaction();
                                });

        assertThat(state.springReadOnly()).isFalse();
        assertThat(state.jdbcReadOnly()).isFalse();
        assertThat(state.defaultTransactionReadOnly()).isEqualTo("off");
        assertThat(state.transactionReadOnly()).isEqualTo("off");
    }

    @Test
    void ordinarySpringReadOnlyTemplateDoesNotPoisonNextTransaction() {
        TransactionTemplate readOnlyTemplate = new TransactionTemplate(transactionManager);
        readOnlyTemplate.setReadOnly(true);
        readOnlyTemplate.executeWithoutResult(
                status -> {
                    try (Statement statement = transactionConnection().createStatement()) {
                        statement.executeQuery("SELECT 1");
                    } catch (SQLException ex) {
                        throw new IllegalStateException(ex);
                    }
                });

        ProbeState following =
                new TransactionTemplate(transactionManager)
                        .execute(
                                status -> {
                                    assertThat(status.isNewTransaction()).isTrue();
                                    return captureCurrentTransaction();
                                });

        assertThat(following.springReadOnly()).isFalse();
        assertThat(following.jdbcReadOnly()).isFalse();
        assertThat(following.defaultTransactionReadOnly()).isEqualTo("off");
        assertThat(following.transactionReadOnly()).isEqualTo("off");
    }

    @Test
    void nativeSessionPoisonReproducesReadOnlyMismatch() throws SQLException {
        int poisonedPid = poisonNativeSession();

        ProbeState state =
                new TransactionTemplate(transactionManager)
                        .execute(
                                status -> {
                                    assertThat(status.isNewTransaction()).isTrue();
                                    ProbeState captured = captureCurrentTransaction();
                                    assertThat(captured.backendPid()).isEqualTo(poisonedPid);
                                    return captured;
                                });

        assertThat(state.springReadOnly()).isFalse();
        assertThat(state.jdbcReadOnly()).isFalse();
        assertThat(state.defaultTransactionReadOnly()).isEqualTo("on");
        assertThat(state.transactionReadOnly()).isEqualTo("on");

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            try (Statement statement = transactionConnection().createStatement()) {
                                assertThatThrownBy(() -> statement.executeUpdate(DML_PROBE_SQL))
                                        .satisfies(
                                                ex -> {
                                                    if (ex instanceof SQLException sqlEx) {
                                                        assertThat(sqlEx.getSQLState()).isEqualTo("25006");
                                                    } else {
                                                        assertThat(ex.getMessage())
                                                                .contains("read-only transaction");
                                                    }
                                                });
                            } catch (SQLException ex) {
                                throw new IllegalStateException(ex);
                            }
                            status.setRollbackOnly();
                            assertThat(status.isRollbackOnly()).isTrue();
                        });
    }

    @Test
    void jdbcSetReadOnlyFalseDoesNotClearNativeGuc() throws SQLException {
        poisonNativeSession();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isReadOnly()).isFalse();
            connection.setReadOnly(false);
            assertThat(connection.isReadOnly()).isFalse();
            assertThat(show(connection, "default_transaction_read_only")).isEqualTo("on");
        }
    }

    @Test
    void readOnlyTransactionTemplatePreservesPidWithoutPoisoningNextTransaction() throws SQLException {
        FollowingTransactionEvidence evidence =
                captureFollowingAfterReadOnlyTemplate(
                        "commit",
                        status -> {
                            try (Statement statement = transactionConnection().createStatement()) {
                                statement.executeQuery("SELECT 1");
                            }
                        });

        assertThat(evidence.readOnlyBackendPid()).isEqualTo(evidence.followingBackendPid());
        assertFollowingTransactionWritable(evidence);
    }

    @Test
    void readOnlyServiceMethodPreservesPidWithoutPoisoningNextTransaction() throws SQLException {
        int pooledPidBeforeService;
        try (Connection connection = dataSource.getConnection()) {
            pooledPidBeforeService = queryInt(connection, "SELECT pg_backend_pid()");
        }

        portfolioService.getByUserId(DEMO_USER_ID);

        FollowingTransactionEvidence evidence =
                captureFollowingDefaultTransaction(
                        "portfolio-service-getByUserId", "commit", pooledPidBeforeService);

        assertThat(evidence.readOnlyBackendPid()).isEqualTo(pooledPidBeforeService);
        assertThat(evidence.readOnlyBackendPid()).isEqualTo(evidence.followingBackendPid());
        assertFollowingTransactionWritable(evidence);
    }

    @Test
    void readOnlyTemplateRollbackCompletionDoesNotPoisonNextTransaction() throws SQLException {
        FollowingTransactionEvidence evidence =
                captureFollowingAfterReadOnlyTemplate(
                        "rollback-only",
                        status -> {
                            try (Statement statement = transactionConnection().createStatement()) {
                                statement.executeQuery("SELECT 1");
                            }
                            status.setRollbackOnly();
                        });

        assertThat(evidence.readOnlyBackendPid()).isEqualTo(evidence.followingBackendPid());
        assertFollowingTransactionWritable(evidence);
    }

    @Test
    void readOnlyTemplateExceptionCompletionDoesNotPoisonNextTransaction() throws SQLException {
        TransactionTemplate readOnlyTemplate = new TransactionTemplate(transactionManager);
        readOnlyTemplate.setReadOnly(true);
        final int[] readOnlyPid = new int[1];
        try {
            readOnlyTemplate.executeWithoutResult(
                    status -> {
                        try (Statement statement = transactionConnection().createStatement()) {
                            statement.executeQuery("SELECT 1");
                            readOnlyPid[0] = queryInt(transactionConnection(), "SELECT pg_backend_pid()");
                        } catch (SQLException ex) {
                            throw new IllegalStateException(ex);
                        }
                        throw new IllegalStateException("rca-probe");
                    });
        } catch (IllegalStateException ex) {
            assertThat(ex.getMessage()).isEqualTo("rca-probe");
        }

        FollowingTransactionEvidence evidence =
                captureFollowingDefaultTransaction(
                        "transaction-template", "runtime-exception", readOnlyPid[0]);

        assertThat(evidence.readOnlyBackendPid()).isEqualTo(evidence.followingBackendPid());
        assertFollowingTransactionWritable(evidence);
    }

    @Test
    void initializerShapedSequenceRemainsWritableOnCleanSession() {
        InitializerProbeResult result = runInitializerShapedSequence(false);

        assertThat(result.state().springReadOnly()).isFalse();
        assertThat(result.state().jdbcReadOnly()).isFalse();
        assertThat(result.state().defaultTransactionReadOnly()).isEqualTo("off");
        assertThat(result.state().transactionReadOnly()).isEqualTo("off");
        assertThat(result.dmlProbeOutcome()).isEqualTo("PASS");
    }

    @Test
    void initializerShapedSequenceReproducesPoisonedFailure() throws SQLException {
        int poisonedPid = poisonNativeSession();
        InitializerProbeResult result = runInitializerShapedSequence(true);

        assertThat(result.state().backendPid()).isEqualTo(poisonedPid);
        assertThat(result.state().springReadOnly()).isFalse();
        assertThat(result.state().jdbcReadOnly()).isFalse();
        assertThat(result.state().defaultTransactionReadOnly()).isEqualTo("on");
        assertThat(result.state().transactionReadOnly()).isEqualTo("on");
        assertThat(result.dmlProbeOutcome()).isEqualTo("FAIL");
        assertThat(result.dmlProbeError()).contains("read-only transaction");
    }

    private record InitializerProbeResult(ProbeState state, String dmlProbeOutcome, String dmlProbeError) {}

    @FunctionalInterface
    private interface ReadOnlyWork {
        void run(org.springframework.transaction.TransactionStatus status) throws SQLException;
    }

    private FollowingTransactionEvidence captureFollowingAfterReadOnlyTemplate(
            String completionMode, ReadOnlyWork readOnlyWork) throws SQLException {
        TransactionTemplate readOnlyTemplate = new TransactionTemplate(transactionManager);
        readOnlyTemplate.setReadOnly(true);
        int readOnlyPid =
                readOnlyTemplate.execute(
                        status -> {
                            try {
                                readOnlyWork.run(status);
                                return queryInt(transactionConnection(), "SELECT pg_backend_pid()");
                            } catch (SQLException ex) {
                                throw new IllegalStateException(ex);
                            }
                        });

        ProbeState following =
                new TransactionTemplate(transactionManager)
                        .execute(status -> captureCurrentTransaction());

        return new FollowingTransactionEvidence(
                "transaction-template",
                completionMode,
                readOnlyPid,
                following.backendPid(),
                following);
    }

    private FollowingTransactionEvidence captureFollowingDefaultTransaction(
            String path, String completionMode, int readOnlyBackendPid) {
        ProbeState following =
                new TransactionTemplate(transactionManager)
                        .execute(status -> captureCurrentTransaction());

        return new FollowingTransactionEvidence(
                path, completionMode, readOnlyBackendPid, following.backendPid(), following);
    }

    private void assertFollowingTransactionWritable(FollowingTransactionEvidence evidence) {
        ProbeState following = evidence.followingState();
        assertThat(following.springReadOnly()).isFalse();
        assertThat(following.jdbcReadOnly()).isFalse();
        assertThat(following.defaultTransactionReadOnly()).isEqualTo("off");
        assertThat(following.transactionReadOnly()).isEqualTo("off");
    }

    private InitializerProbeResult runInitializerShapedSequence(boolean expectPoisoned) {
        final ProbeState[] captured = new ProbeState[1];
        final String[] dmlOutcome = new String[1];
        final String[] dmlError = new String[1];

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            entityManager
                                    .unwrap(Session.class)
                                    .doWork(
                                            connection -> {
                                                try (var ps =
                                                        connection.prepareStatement(
                                                                "SELECT pg_advisory_xact_lock(?)")) {
                                                    ps.setLong(1, DemoPortfolioInitializer.ADVISORY_LOCK_KEY);
                                                    ps.execute();
                                                }
                                            });

                            portfolioRepository.findByUserId(DEMO_USER_ID).stream()
                                    .findFirst()
                                    .ifPresent(assetHoldingRepository::findByPortfolio);
                            seedService.desiredHoldings(DEMO_USER_ID);

                            captured[0] = captureCurrentTransaction();

                            entityManager
                                    .unwrap(Session.class)
                                    .doWork(
                                            connection -> {
                                                try (Statement statement = connection.createStatement()) {
                                                    statement.executeUpdate(DML_PROBE_SQL);
                                                    dmlOutcome[0] = "PASS";
                                                    dmlError[0] = null;
                                                } catch (SQLException ex) {
                                                    dmlOutcome[0] = "FAIL";
                                                    dmlError[0] = ex.getMessage();
                                                }
                                            });

                            status.setRollbackOnly();
                        });

        if (expectPoisoned) {
            assertThat(dmlOutcome[0]).isEqualTo("FAIL");
        } else {
            assertThat(dmlOutcome[0]).isEqualTo("PASS");
        }

        return new InitializerProbeResult(captured[0], dmlOutcome[0], dmlError[0]);
    }

    private int poisonNativeSession() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            int pid = queryInt(connection, "SELECT pg_backend_pid()");
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
            }
            return pid;
        }
    }

    private ProbeState captureCurrentTransaction() {
        try {
            return capture(transactionConnection());
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ProbeState capture(Connection connection) throws SQLException {
        return new ProbeState(
                TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                connection.isReadOnly(),
                show(connection, "transaction_read_only"),
                show(connection, "default_transaction_read_only"),
                queryInt(connection, "SELECT pg_backend_pid()"));
    }

    private Connection transactionConnection() {
        return DataSourceUtils.getConnection(dataSource);
    }

    private static String show(Connection connection, String setting) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SHOW " + setting)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
