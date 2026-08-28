package com.wealth.portfolio.seed.diag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wealth.portfolio.TestContainerImages;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SpecA912ProvenanceDataSourceTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    private List<SpecA912PooledSessionProvenance.ProvenanceEvent> events;
    private SpecA912PooledSessionProvenance provenance;
    private HikariDataSource hikari;
    private SpecA912ProvenanceDataSource wrapped;

    @BeforeEach
    void setUp() {
        events = new CopyOnWriteArrayList<>();
        provenance = SpecA912PooledSessionProvenance.withSink(events::add);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        hikari = new HikariDataSource(config);
        wrapped = new SpecA912ProvenanceDataSource(hikari, provenance);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (wrapped != null) {
            wrapped.close();
        }
        if (hikari != null && !hikari.isClosed()) {
            hikari.close();
        }
    }

    @Test
    void postProcessorGateMatrix() {
        DataSource original = mock(DataSource.class);
        SpecA912ProvenanceDataSourcePostProcessor processor = new SpecA912ProvenanceDataSourcePostProcessor();

        assertSameBean(processor, original, false, false);
        assertSameBean(processor, original, false, true);
        assertSameBean(processor, original, true, true);

        Object wrappedBean = processorWithEnv(processor, true, false).postProcessAfterInitialization(original, "dataSource");
        assertThat(wrappedBean).isInstanceOf(SpecA912ProvenanceDataSource.class);
        assertThat(processorWithEnv(processor, true, false)
                        .postProcessAfterInitialization(wrappedBean, "dataSource"))
                .isSameAs(wrappedBean);
        assertThat(processorWithEnv(processor, true, false)
                        .postProcessAfterInitialization(original, "otherBean"))
                .isSameAs(original);
    }

    @Test
    void postProcessorBothFlagsRejectsWithoutWrapping() {
        DataSource original = mock(DataSource.class);
        SpecA912ProvenanceDataSourcePostProcessor processor = new SpecA912ProvenanceDataSourcePostProcessor();
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("APP_DEMO_TX_DIAGNOSTICS", "true");
        environment.setProperty("app.demo.seed-on-startup", "true");
        processor.setEnvironment(environment);

        Logger logger = (Logger) LoggerFactory.getLogger(SpecA912ProvenanceDataSourcePostProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(processor.postProcessAfterInitialization(original, "dataSource")).isSameAs(original);
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(msg -> msg.contains("event=spec_a912_pool_session_provenance_rejected"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void transparentConnectionBehavior() throws Exception {
        try (Connection connection = wrapped.getConnection();
                Statement statement = connection.createStatement();
                var rs = statement.executeQuery("SELECT 1")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(connection.unwrap(Connection.class)).isNotNull();
            assertThat(connection.isWrapperFor(Connection.class)).isTrue();
        }
    }

    @Test
    void delegateSqlExceptionPropagatesUnchanged() throws Exception {
        Connection broken = mock(Connection.class);
        when(broken.createStatement()).thenAnswer(invocation -> { throw new SQLException("boom", "XX000"); });
        SpecA912ProvenanceDataSource brokenSource =
                new SpecA912ProvenanceDataSource(new SingleConnectionDataSource(broken), provenance);

        assertThatThrownBy(() -> {
                    try (Connection connection = brokenSource.getConnection();
                            Statement statement = connection.createStatement()) {
                        statement.executeQuery("SELECT 1");
                    }
                })
                .isInstanceOf(SQLException.class)
                .extracting(ex -> ((SQLException) ex).getSQLState())
                .isEqualTo("XX000");
    }

    @Test
    void observerFailureDoesNotPreventWrappedConnection() throws Exception {
        Connection flaky = mock(Connection.class);
        when(flaky.createStatement()).thenThrow(new SQLException("snapshot-boom", "SNAP1"));
        when(flaky.isReadOnly()).thenReturn(false);
        when(flaky.getAutoCommit()).thenReturn(true);
        SpecA912ProvenanceDataSource flakySource =
                new SpecA912ProvenanceDataSource(new SingleConnectionDataSource(flaky), provenance);

        try (Connection connection = flakySource.getConnection()) {
            assertThat(connection).isNotNull();
        }
    }

    @Test
    void setReadOnlyProceedsWhenSnapshotAttemptFails() throws Exception {
        Connection flaky = mock(Connection.class);
        when(flaky.getAutoCommit()).thenReturn(true);
        when(flaky.isReadOnly()).thenReturn(false);
        when(flaky.createStatement()).thenAnswer(invocation -> { throw new SQLException("snapshot-boom", "SNAP1"); });
        org.mockito.Mockito.doAnswer(invocation -> null).when(flaky).setReadOnly(true);
        SpecA912ProvenanceDataSource flakySource =
                new SpecA912ProvenanceDataSource(new SingleConnectionDataSource(flaky), provenance);

        try (Connection connection = flakySource.getConnection()) {
            connection.setReadOnly(true);
        }

        verify(flaky).setReadOnly(true);
    }

    @Test
    void throwingSinkDoesNotBlockSetReadOnly() throws Exception {
        SpecA912PooledSessionProvenance throwingProvenance =
                SpecA912PooledSessionProvenance.withSink(event -> { throw new IllegalStateException("sink-boom"); });
        SpecA912ProvenanceDataSource throwingSource =
                new SpecA912ProvenanceDataSource(hikari, throwingProvenance);

        try (Connection connection = throwingSource.getConnection()) {
            connection.setReadOnly(true);
            assertThat(connection.isReadOnly()).isTrue();
        }
    }

    @Test
    void setterWithAutoCommitFalseExecutesZeroObserverSql() throws Exception {
        Connection delegate = mock(Connection.class);
        java.util.concurrent.atomic.AtomicInteger observerQueryCalls = new java.util.concurrent.atomic.AtomicInteger();
        when(delegate.getAutoCommit()).thenReturn(true, false);
        when(delegate.isReadOnly()).thenReturn(false);
        when(delegate.createStatement()).thenAnswer(
                invocation -> {
                    Statement statement = mock(Statement.class);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.next()).thenReturn(true);
                    when(rs.getLong(1)).thenReturn(42L);
                    when(rs.getString(1)).thenReturn("off");
                    when(statement.executeQuery(anyString())).thenAnswer(
                            query -> {
                                observerQueryCalls.incrementAndGet();
                                return rs;
                            });
                    when(statement.execute(anyString())).thenReturn(true);
                    return statement;
                });

        SpecA912ProvenanceDataSource source =
                new SpecA912ProvenanceDataSource(new SingleConnectionDataSource(delegate), provenance);

        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            int afterCheckout = observerQueryCalls.get();
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
            }
            assertThat(observerQueryCalls.get()).isEqualTo(afterCheckout);
        }
    }

    @Test
    void postSetterSnapshotFailureDoesNotLeaveStaleAttribution() throws Exception {
        Connection delegate = mock(Connection.class);
        Statement delegateStatement = mock(Statement.class);
        java.sql.ResultSet pidRs = mock(java.sql.ResultSet.class);
        java.sql.ResultSet gucRs = mock(java.sql.ResultSet.class);
        java.util.concurrent.atomic.AtomicInteger queryCount = new java.util.concurrent.atomic.AtomicInteger();
        when(delegate.getAutoCommit()).thenReturn(true);
        when(delegate.isReadOnly()).thenReturn(false);
        when(delegate.createStatement()).thenReturn(delegateStatement);
        when(delegateStatement.executeQuery(anyString())).thenAnswer(
                invocation -> {
                    if (queryCount.incrementAndGet() > 3) {
                        throw new SQLException("post-setter-snapshot-fail", "SNAP2");
                    }
                    String sql = invocation.getArgument(0);
                    if (sql.contains("pg_backend_pid")) {
                        when(pidRs.next()).thenReturn(true);
                        when(pidRs.getLong(1)).thenReturn(10L);
                        return pidRs;
                    }
                    when(gucRs.next()).thenReturn(true);
                    when(gucRs.getString(1)).thenReturn("off");
                    return gucRs;
                });
        when(delegateStatement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY"))
                .thenReturn(true);

        SpecA912ProvenanceDataSource source =
                new SpecA912ProvenanceDataSource(new SingleConnectionDataSource(delegate), provenance);

        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
        }

        events.clear();
        provenance.observe(
                "return",
                2L,
                new SpecA912PooledSessionProvenance.SessionSnapshot(10L, false, true, "on", "on"));

        assertThat(events.stream().map(SpecA912PooledSessionProvenance.ProvenanceEvent::transition))
                .contains(SpecA912PooledSessionProvenance.Transition.UNATTRIBUTED_OFF_TO_ON);
    }

    @Test
    void preparedAddBatchAndExecuteBatchConfirmsSetter() throws Exception {
        try (Connection connection = wrapped.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY")) {
            ps.addBatch();
            ps.executeBatch();
        }

        assertThat(events)
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::phase)
                .contains(
                        SpecA912ProvenanceDataSource.PHASE_SETTER_ATTEMPT,
                        SpecA912ProvenanceDataSource.PHASE_SETTER_CONFIRMED);
    }

    @Test
    void failedAddBatchDoesNotQueueSetter() throws Exception {
        Connection delegate = mock(Connection.class);
        Statement delegateStatement = mock(Statement.class);
        when(delegate.getAutoCommit()).thenReturn(true);
        when(delegate.isReadOnly()).thenReturn(false);
        when(delegate.createStatement()).thenReturn(delegateStatement);
        stubSnapshotQueries(delegateStatement);
        doThrow(new SQLException("add-batch-fail", "AB01"))
                .when(delegateStatement)
                .addBatch("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");

        SpecA912ProvenanceDataSource source =
                new SpecA912ProvenanceDataSource(new SingleConnectionDataSource(delegate), provenance);

        assertThatThrownBy(
                        () -> {
                            try (Connection connection = source.getConnection();
                                    Statement statement = connection.createStatement()) {
                                statement.addBatch("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
                            }
                        })
                .isInstanceOf(SQLException.class);

        assertThat(events)
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::phase)
                .doesNotContain(SpecA912ProvenanceDataSource.PHASE_SETTER_ATTEMPT);
    }

    @Test
    void failedClearBatchRetainsQueuedSetter() throws Exception {
        Connection delegate = mock(Connection.class);
        Statement delegateStatement = mock(Statement.class);
        when(delegate.getAutoCommit()).thenReturn(true);
        when(delegate.isReadOnly()).thenReturn(false);
        when(delegate.createStatement()).thenReturn(delegateStatement);
        stubSnapshotQueries(delegateStatement);
        org.mockito.Mockito.doNothing()
                .when(delegateStatement)
                .addBatch("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
        doThrow(new SQLException("clear-fail", "CB01")).when(delegateStatement).clearBatch();
        when(delegateStatement.executeBatch()).thenReturn(new int[] {0});

        SpecA912ProvenanceDataSource source =
                new SpecA912ProvenanceDataSource(new SingleConnectionDataSource(delegate), provenance);

        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            statement.addBatch("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
            assertThatThrownBy(statement::clearBatch).isInstanceOf(SQLException.class);
            events.clear();
            statement.executeBatch();
        }

        assertThat(events)
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::phase)
                .contains(SpecA912ProvenanceDataSource.PHASE_SETTER_CONFIRMED);
    }

    @Test
    void batchSessionDefaultSetterSurvivesTrailingTransactionLocalSetter() throws Exception {
        try (Connection connection = wrapped.getConnection();
                Statement statement = connection.createStatement()) {
            statement.addBatch("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
            statement.addBatch("SET TRANSACTION READ ONLY");
            statement.executeBatch();
        }

        assertThat(events)
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::setterKind)
                .contains(SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_ONLY);

        SpecA912PooledSessionProvenance.ProvenanceEvent confirmed =
                events.stream()
                        .filter(e -> SpecA912ProvenanceDataSource.PHASE_SETTER_CONFIRMED.equals(e.phase()))
                        .reduce((first, second) -> second)
                        .orElseThrow();
        assertThat(confirmed.setterKind())
                .isEqualTo(SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_ONLY);
    }

    @Test
    void executeBatchResolvesResetDefaultReadOnly() throws Exception {
        try (Connection connection = wrapped.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
        }
        events.clear();

        try (Connection connection = wrapped.getConnection();
                Statement statement = connection.createStatement()) {
            statement.addBatch("RESET default_transaction_read_only");
            statement.executeBatch();
        }

        assertThat(events)
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::phase)
                .contains(SpecA912ProvenanceDataSource.PHASE_SETTER_CONFIRMED);
        assertThat(events)
                .filteredOn(e -> SpecA912ProvenanceDataSource.PHASE_SETTER_CONFIRMED.equals(e.phase()))
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::setterKind)
                .contains(SpecA912PooledSessionProvenance.SetterKind.RESET_DEFAULT_READ_ONLY);
    }

    @Test
    void executeBatchResolvesResetAllOverTrailingTransactionLocal() throws Exception {
        try (Connection connection = wrapped.getConnection();
                Statement statement = connection.createStatement()) {
            statement.addBatch("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE");
            statement.addBatch("RESET ALL");
            statement.addBatch("SET TRANSACTION READ ONLY");
            statement.executeBatch();
        }

        SpecA912PooledSessionProvenance.ProvenanceEvent confirmed =
                events.stream()
                        .filter(e -> SpecA912ProvenanceDataSource.PHASE_SETTER_CONFIRMED.equals(e.phase()))
                        .reduce((first, second) -> second)
                        .orElseThrow();
        assertThat(confirmed.setterKind()).isEqualTo(SpecA912PooledSessionProvenance.SetterKind.RESET_ALL);
    }

    @Test
    void executeBatchResolvesDiscardAll() throws Exception {
        try (Connection connection = wrapped.getConnection();
                Statement statement = connection.createStatement()) {
            statement.addBatch("DISCARD ALL");
            statement.executeBatch();
        }

        assertThat(events)
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::phase)
                .contains(SpecA912ProvenanceDataSource.PHASE_SETTER_CONFIRMED);
        assertThat(events)
                .filteredOn(e -> SpecA912ProvenanceDataSource.PHASE_SETTER_CONFIRMED.equals(e.phase()))
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::setterKind)
                .contains(SpecA912PooledSessionProvenance.SetterKind.DISCARD_ALL);
    }

    private static void stubSnapshotQueries(Statement delegateStatement) throws SQLException {
        java.sql.ResultSet pidRs = mock(java.sql.ResultSet.class);
        java.sql.ResultSet gucRs = mock(java.sql.ResultSet.class);
        when(delegateStatement.executeQuery(anyString())).thenAnswer(
                invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("pg_backend_pid")) {
                        when(pidRs.next()).thenReturn(true);
                        when(pidRs.getLong(1)).thenReturn(42L);
                        return pidRs;
                    }
                    when(gucRs.next()).thenReturn(true);
                    when(gucRs.getString(1)).thenReturn("off");
                    return gucRs;
                });
    }

    @Test
    void addBatchWithoutExecuteDoesNotConfirmSetter() throws Exception {
        try (Connection connection = wrapped.getConnection();
                Statement statement = connection.createStatement()) {
            statement.addBatch("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
        }

        assertThat(events)
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::phase)
                .contains(SpecA912ProvenanceDataSource.PHASE_SETTER_ATTEMPT)
                .doesNotContain(SpecA912ProvenanceDataSource.PHASE_SETTER_CONFIRMED);
        assertThat(events.stream().filter(e -> SpecA912ProvenanceDataSource.PHASE_SETTER_ATTEMPT.equals(e.phase())))
                .allMatch(e -> e.snapshot() == null);
    }

    @Test
    void clearBatchDiscardsQueuedSetterBeforeExecution() throws Exception {
        try (Connection connection = wrapped.getConnection();
                Statement statement = connection.createStatement()) {
            statement.addBatch("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
            statement.clearBatch();
            statement.executeBatch();
        }

        assertThat(events)
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::phase)
                .doesNotContain(SpecA912ProvenanceDataSource.PHASE_SETTER_CONFIRMED);
    }

    @Test
    void executeBatchConfirmsQueuedSetter() throws Exception {
        try (Connection connection = wrapped.getConnection();
                Statement statement = connection.createStatement()) {
            statement.addBatch("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE");
            statement.addBatch("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
            statement.executeBatch();
        }

        assertThat(events)
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::phase)
                .contains(SpecA912ProvenanceDataSource.PHASE_SETTER_CONFIRMED);
    }

    @Test
    void autoCommitFalseSkipsSqlObservation() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(SpecA912PooledSessionProvenance.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (Connection connection = wrapped.getConnection()) {
            connection.setAutoCommit(false);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(msg -> msg.contains("event=spec_a912_pool_session_provenance_skipped")
                        && msg.contains("auto-commit-false"));
    }

    @Test
    void statementGetConnectionReturnsInstrumentedProxy() throws Exception {
        try (Connection connection = wrapped.getConnection();
                Statement statement = connection.createStatement()) {
            Connection fromStatement = statement.getConnection();
            assertThat(fromStatement).isSameAs(connection);
            assertThat(Proxy.isProxyClass(fromStatement.getClass())).isTrue();
        }
    }

    @Test
    void failedSetterSqlDoesNotConfirmAttribution() throws Exception {
        Connection delegate = mock(Connection.class);
        Statement delegateStatement = mock(Statement.class);
        java.sql.ResultSet pidRs = mock(java.sql.ResultSet.class);
        java.sql.ResultSet gucRs = mock(java.sql.ResultSet.class);
        when(delegate.getAutoCommit()).thenReturn(true);
        when(delegate.isReadOnly()).thenReturn(false);
        when(delegate.createStatement()).thenReturn(delegateStatement);
        when(delegateStatement.executeQuery(anyString())).thenAnswer(
                invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("pg_backend_pid")) {
                        when(pidRs.next()).thenReturn(true);
                        when(pidRs.getLong(1)).thenReturn(42L);
                        return pidRs;
                    }
                    when(gucRs.next()).thenReturn(true);
                    when(gucRs.getString(1)).thenReturn("off");
                    return gucRs;
                });
        when(delegateStatement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY"))
                .thenThrow(new SQLException("setter-fail", "XX001"));

        SpecA912ProvenanceDataSource source =
                new SpecA912ProvenanceDataSource(new SingleConnectionDataSource(delegate), provenance);

        assertThatThrownBy(
                        () -> {
                            try (Connection connection = source.getConnection();
                                    Statement statement = connection.createStatement()) {
                                statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
                            }
                        })
                .isInstanceOf(SQLException.class)
                .extracting(ex -> ((SQLException) ex).getSQLState())
                .isEqualTo("XX001");

        assertThat(events)
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::phase)
                .contains(SpecA912ProvenanceDataSource.PHASE_SETTER_ATTEMPT)
                .doesNotContain(SpecA912ProvenanceDataSource.PHASE_SETTER_CONFIRMED);
    }

    @Test
    void interceptsRecognizedSetterSql() throws Exception {
        try (Connection connection = wrapped.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
        }

        assertThat(events)
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::setterKind)
                .contains(SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_ONLY);
        assertThat(events)
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::phase)
                .contains(SpecA912ProvenanceDataSource.PHASE_SETTER_CONFIRMED);
    }

    @Test
    void interceptsConnectionSetReadOnly() throws Exception {
        try (Connection connection = wrapped.getConnection()) {
            connection.setReadOnly(true);
        }

        assertThat(events)
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::setterKind)
                .contains(SpecA912PooledSessionProvenance.SetterKind.CONNECTION_SET_READ_ONLY_TRUE);
    }

    @Test
    void interceptsPreparedStatementExecution() throws Exception {
        try (Connection connection = wrapped.getConnection();
                var ps =
                        connection.prepareStatement(
                                "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY")) {
            ps.execute();
        }

        assertThat(events)
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::setterKind)
                .contains(SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_ONLY);
    }

    @Test
    void closeClosesAutoCloseableDelegateOnce() throws Exception {
        AutoCloseableHikari delegate = mock(AutoCloseableHikari.class);
        when(delegate.getConnection()).thenReturn(hikari.getConnection());
        SpecA912ProvenanceDataSource closable =
                new SpecA912ProvenanceDataSource(delegate, provenance);

        closable.close();
        closable.close();
        verify(delegate).close();
    }

    @Test
    @Tag("integration")
    void disabledContextLeavesOriginalDataSource() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "test",
                                    java.util.Map.of(
                                            "APP_DEMO_TX_DIAGNOSTICS", "false",
                                            "app.demo.seed-on-startup", "false")));
            context.register(DiagnosticsDisabledConfig.class);
            context.refresh();

            DataSource dataSource = context.getBean(DataSource.class);
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            assertThat(dataSource).isNotInstanceOf(SpecA912ProvenanceDataSource.class);
        }
    }

    @Test
    @Tag("integration")
    void diagnosticsOnlyContextWrapsOnceAndUnwraps() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "test",
                                    java.util.Map.of(
                                            "APP_DEMO_TX_DIAGNOSTICS", "true",
                                            "app.demo.seed-on-startup", "false",
                                            "spring.datasource.hikari.maximum-pool-size", "1",
                                            "spring.datasource.hikari.minimum-idle", "1")));
            context.register(DiagnosticsEnabledConfig.class);
            context.refresh();

            DataSource dataSource = context.getBean(DataSource.class);
            assertThat(dataSource).isInstanceOf(SpecA912ProvenanceDataSource.class);
            SpecA912ProvenanceDataSource provenanceDataSource = (SpecA912ProvenanceDataSource) dataSource;
            assertThat(provenanceDataSource.unwrap(HikariDataSource.class)).isInstanceOf(HikariDataSource.class);
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement();
                    var rs = statement.executeQuery("SELECT 1")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    @Tag("integration")
    void bothFlagsContextLeavesDataSourceUnwrapped() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "test",
                                    java.util.Map.of(
                                            "APP_DEMO_TX_DIAGNOSTICS", "true",
                                            "app.demo.seed-on-startup", "true")));
            context.register(DiagnosticsDisabledConfig.class);
            context.refresh();

            DataSource dataSource = context.getBean(DataSource.class);
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            assertThat(dataSource).isNotInstanceOf(SpecA912ProvenanceDataSource.class);
        }
    }

    private static void assertSameBean(
            SpecA912ProvenanceDataSourcePostProcessor processor,
            DataSource original,
            boolean diagnostics,
            boolean demoSeed) {
        Object result =
                processorWithEnv(processor, diagnostics, demoSeed)
                        .postProcessAfterInitialization(original, "dataSource");
        assertThat(result).isSameAs(original);
    }

    private static SpecA912ProvenanceDataSourcePostProcessor processorWithEnv(
            SpecA912ProvenanceDataSourcePostProcessor processor, boolean diagnostics, boolean demoSeed) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("APP_DEMO_TX_DIAGNOSTICS", Boolean.toString(diagnostics));
        environment.setProperty("app.demo.seed-on-startup", Boolean.toString(demoSeed));
        processor.setEnvironment(environment);
        return processor;
    }

    private interface AutoCloseableHikari extends DataSource, AutoCloseable {}

    private static final class SingleConnectionDataSource extends org.springframework.jdbc.datasource.SingleConnectionDataSource {
        SingleConnectionDataSource(Connection connection) {
            super(connection, true);
        }
    }

    @Configuration
    static class DiagnosticsDisabledConfig {
        @Bean
        SpecA912ProvenanceDataSourcePostProcessor postProcessor() {
            return new SpecA912ProvenanceDataSourcePostProcessor();
        }

        @Bean(name = "dataSource")
        HikariDataSource dataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl());
            config.setUsername(postgres.getUsername());
            config.setPassword(postgres.getPassword());
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(1);
            return new HikariDataSource(config);
        }
    }

    @Configuration
    static class DiagnosticsEnabledConfig extends DiagnosticsDisabledConfig {}
}
