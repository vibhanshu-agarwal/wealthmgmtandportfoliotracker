package com.wealth.portfolio.seed.rca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wealth.portfolio.TestContainerImages;
import com.wealth.portfolio.seed.diag.SpecA912PooledSessionProvenance;
import com.wealth.portfolio.seed.diag.SpecA912ProvenanceDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Deterministic Testcontainers proof that pooled-session provenance distinguishes
 * application-observed setters from below-wrapper poisoning on a single Hikari connection.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class SpecA912PooledSessionSetterProvenanceIT {

    private static final String POISON_SQL = "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY";
    private static final String RESET_SQL = "SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    private final List<SpecA912PooledSessionProvenance.ProvenanceEvent> events = new CopyOnWriteArrayList<>();
    private HikariDataSource rawHikari;
    private SpecA912ProvenanceDataSource wrapped;

    @BeforeEach
    void setUp() {
        events.clear();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        rawHikari = new HikariDataSource(config);
        wrapped =
                new SpecA912ProvenanceDataSource(
                        rawHikari, SpecA912PooledSessionProvenance.withSink(events::add));
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection connection = rawHikari.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(RESET_SQL);
        }
        if (rawHikari != null && !rawHikari.isClosed() && rawHikari.getHikariPoolMXBean() != null) {
            assertThat(rawHikari.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
        if (wrapped != null) {
            wrapped.close();
        }
        if (rawHikari != null && !rawHikari.isClosed()) {
            rawHikari.close();
        }
        if (rawHikari != null) {
            assertThat(rawHikari.isClosed()).isTrue();
        }
    }

    @Test
    void applicationObservedSetterIsAttributedOnSamePooledPid() throws Exception {
        long firstPid = borrowAndCloseOff();

        try (Connection connection = wrapped.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(POISON_SQL);
        }

        assertThat(events.stream().map(SpecA912PooledSessionProvenance.ProvenanceEvent::setterKind))
                .contains(SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_ONLY);

        SpecA912PooledSessionProvenance.ProvenanceEvent attributed =
                events.stream()
                        .filter(
                                event ->
                                        event.transition()
                                                == SpecA912PooledSessionProvenance.Transition
                                                        .ATTRIBUTED_OFF_TO_ON)
                        .findFirst()
                        .orElseThrow();

        assertThat(attributed.snapshot().backendPid()).isEqualTo(firstPid);

        try (Connection connection = wrapped.getConnection()) {
            SpecA912PooledSessionProvenance.SessionSnapshot snapshot = capture(connection);
            assertThat(snapshot.backendPid()).isEqualTo(firstPid);
            assertThat(snapshot.jdbcReadOnly()).isFalse();
            assertThat(snapshot.defaultTransactionReadOnly()).isEqualTo("on");
            assertThat(snapshot.transactionReadOnly()).isEqualTo("on");
        }
    }

    @Test
    void belowWrapperPoisonIsUnattributedOnSamePooledPid() throws Exception {
        long firstPid = borrowAndCloseOff();

        try (Connection connection = rawHikari.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(POISON_SQL);
        }

        events.clear();

        try (Connection connection = wrapped.getConnection()) {
            SpecA912PooledSessionProvenance.ProvenanceEvent transitionEvent =
                    events.stream()
                            .filter(
                                    event ->
                                            event.transition()
                                                    != SpecA912PooledSessionProvenance.Transition
                                                            .UNCHANGED)
                            .findFirst()
                            .orElseThrow();
            assertThat(transitionEvent.transition())
                    .isEqualTo(SpecA912PooledSessionProvenance.Transition.UNATTRIBUTED_OFF_TO_ON);
            assertThat(transitionEvent.snapshot().backendPid()).isEqualTo(firstPid);
            assertThat(events.stream().map(SpecA912PooledSessionProvenance.ProvenanceEvent::setterKind))
                    .doesNotContain(SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_ONLY);
        }
    }

    @Test
    void firstObservationOnPoisonedSessionIsClassifiedCorrectly() throws Exception {
        try (Connection connection = rawHikari.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(POISON_SQL);
        }

        try (Connection connection = wrapped.getConnection()) {
            SpecA912PooledSessionProvenance.ProvenanceEvent first =
                    events.stream()
                            .filter(
                                    event ->
                                            event.transition()
                                                    == SpecA912PooledSessionProvenance.Transition
                                                            .FIRST_OBSERVED_ON)
                            .findFirst()
                            .orElseThrow();
            assertThat(first.transition())
                    .isNotEqualTo(SpecA912PooledSessionProvenance.Transition.ATTRIBUTED_OFF_TO_ON);
            assertThat(first.snapshot().defaultTransactionReadOnly()).isEqualTo("on");
        }
    }

    @Test
    void observerDoesNotChangeResultsExceptionsOrLogSql() throws Exception {
        try (Connection connection = wrapped.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT 1")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }

        assertThatThrownBy(
                        () -> {
                            try (Connection connection = wrapped.getConnection();
                                    Statement statement = connection.createStatement()) {
                                statement.executeQuery("SELECT missing_column");
                            }
                        })
                .isInstanceOf(SQLException.class)
                .extracting(ex -> ((SQLException) ex).getSQLState())
                .isEqualTo("42703");

        events.forEach(
                event -> {
                    String formatted = SpecA912PooledSessionProvenance.formatEvent(event);
                    assertThat(formatted).doesNotContain(POISON_SQL);
                    assertThat(formatted).doesNotContain("missing_column");
                });
    }

    private long borrowAndCloseOff() throws SQLException {
        try (Connection connection = wrapped.getConnection()) {
            SpecA912PooledSessionProvenance.ProvenanceEvent first =
                    events.stream()
                            .filter(
                                    event ->
                                            event.transition()
                                                    == SpecA912PooledSessionProvenance.Transition
                                                            .FIRST_OBSERVED_OFF)
                            .findFirst()
                            .orElseThrow();
            return first.snapshot().backendPid();
        }
    }

    private static SpecA912PooledSessionProvenance.SessionSnapshot capture(Connection connection)
            throws SQLException {
        long pid;
        String defaultRo;
        String txRo;
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("SELECT pg_backend_pid()")) {
                rs.next();
                pid = rs.getLong(1);
            }
            try (ResultSet rs = statement.executeQuery("SHOW default_transaction_read_only")) {
                rs.next();
                defaultRo = rs.getString(1);
            }
            try (ResultSet rs = statement.executeQuery("SHOW transaction_read_only")) {
                rs.next();
                txRo = rs.getString(1);
            }
        }
        return new SpecA912PooledSessionProvenance.SessionSnapshot(
                pid, connection.isReadOnly(), connection.getAutoCommit(), defaultRo, txRo);
    }
}
