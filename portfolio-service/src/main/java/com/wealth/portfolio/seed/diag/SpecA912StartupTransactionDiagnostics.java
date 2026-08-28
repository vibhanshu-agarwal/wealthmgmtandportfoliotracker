package com.wealth.portfolio.seed.diag;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spec A 9.12 startup transaction RCA — gated live instrumentation only.
 *
 * <p>Active when {@code APP_DEMO_TX_DIAGNOSTICS=true}. Spring state is logged first; JDBC
 * capture runs only inside an active rollback probe transaction. Failures are logged and
 * never abort application startup.
 */
@Component
public class SpecA912StartupTransactionDiagnostics {

    public enum DmlProbeOutcome {
        PASS,
        FAIL,
        SKIPPED
    }

    static final String DML_PROBE_SQL = "DELETE FROM portfolios WHERE FALSE";

    private static final Logger log = LoggerFactory.getLogger(SpecA912StartupTransactionDiagnostics.class);

    private final EntityManager entityManager;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;

    public SpecA912StartupTransactionDiagnostics(
            EntityManager entityManager,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        this.entityManager = entityManager;
        this.transactionManager = transactionManager;
        this.dataSource = dataSource;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("APP_DEMO_TX_DIAGNOSTICS"));
    }

    public static boolean diagnosticsOnly(boolean seedOnStartup) {
        return enabled() && !seedOnStartup;
    }

    public static boolean rejectBothFlags(boolean seedOnStartup) {
        return enabled() && seedOnStartup;
    }

    public void captureSpring(String boundary, TransactionStatus status) {
        if (!enabled()) {
            return;
        }
        try {
            emit(baseSpringState(boundary, status));
        } catch (RuntimeException ex) {
            log.warn(
                    "event=spec_a912_tx_diag_spring_failed boundary={} cause={}: {}",
                    boundary,
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
        }
    }

    public void captureJdbcInTransaction(String boundary) {
        if (!enabled()) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("event=spec_a912_tx_diag_jdbc_skipped boundary={} reason=no-active-transaction", boundary);
            return;
        }
        try {
            Map<String, Object> payload = baseSpringState(boundary, null);
            entityManager.unwrap(Session.class).doWork(connection -> enrichJdbc(payload, connection));
            emit(payload);
        } catch (RuntimeException ex) {
            log.warn(
                    "event=spec_a912_tx_diag_jdbc_failed boundary={} cause={}: {}",
                    boundary,
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
        }
    }

    public DmlProbeOutcome runDmlProbe(String boundary) {
        if (!enabled()) {
            return DmlProbeOutcome.SKIPPED;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("event=spec_a912_tx_diag_dml_skipped boundary={} reason=no-active-transaction", boundary);
            return DmlProbeOutcome.SKIPPED;
        }
        try {
            Map<String, Object> payload = baseSpringState(boundary, null);
            DmlProbeOutcome outcome = DmlProbeOutcome.PASS;
            entityManager.unwrap(Session.class).doWork(connection -> {
                enrichJdbc(payload, connection);
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate(DML_PROBE_SQL);
                    payload.put("dmlProbeOutcome", "PASS");
                    payload.put("dmlProbeError", null);
                } catch (SQLException ex) {
                    payload.put("dmlProbeOutcome", "FAIL");
                    payload.put("dmlProbeError", ex.getMessage());
                }
            });
            if ("FAIL".equals(payload.get("dmlProbeOutcome"))) {
                outcome = DmlProbeOutcome.FAIL;
            }
            emit(payload);
            return outcome;
        } catch (RuntimeException ex) {
            log.warn(
                    "event=spec_a912_tx_diag_dml_failed boundary={} cause={}: {}",
                    boundary,
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
            return DmlProbeOutcome.SKIPPED;
        }
    }

    private Map<String, Object> baseSpringState(String boundary, TransactionStatus status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "spec_a912_tx_diag");
        payload.put("boundary", boundary);
        payload.put("actualTransactionActive", TransactionSynchronizationManager.isActualTransactionActive());
        payload.put("currentTransactionReadOnly", TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        payload.put("transactionName", TransactionSynchronizationManager.getCurrentTransactionName());
        payload.put("transactionManagerClass", transactionManager.getClass().getName());
        payload.put("entityManagerJoinedToTransaction", entityManager.isJoinedToTransaction());
        payload.put("dataSourceResourceBound", TransactionSynchronizationManager.hasResource(dataSource));
        if (status != null) {
            payload.put("statusNewTransaction", status.isNewTransaction());
            payload.put("statusRollbackOnly", status.isRollbackOnly());
            payload.put("statusCompleted", status.isCompleted());
        }
        return payload;
    }

    private static void enrichJdbc(Map<String, Object> payload, java.sql.Connection connection) throws SQLException {
        payload.put("jdbcConnectionReadOnly", connection.isReadOnly());
        payload.put("showTransactionReadOnly", show(connection, "transaction_read_only"));
        payload.put("showDefaultTransactionReadOnly", show(connection, "default_transaction_read_only"));
        payload.put("pgBackendPid", queryLong(connection, "SELECT pg_backend_pid()"));
        payload.put("pgIsInRecovery", queryBoolean(connection, "SELECT pg_is_in_recovery()"));
        payload.put("currentUser", queryString(connection, "SELECT current_user"));
        payload.put("currentDatabase", queryString(connection, "SELECT current_database()"));
    }

    private void emit(Map<String, Object> payload) {
        String line =
                payload.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(Collectors.joining(" "));
        log.info("{}", line);
    }

    private static String show(java.sql.Connection connection, String setting) throws SQLException {
        try (Statement statement = connection.createStatement();
                var rs = statement.executeQuery("SHOW " + setting)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Long queryLong(java.sql.Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Boolean queryBoolean(java.sql.Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static String queryString(java.sql.Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
