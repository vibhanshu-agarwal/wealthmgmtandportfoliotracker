package com.wealth.portfolio.seed.diag;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Wrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Diagnostics-only DataSource wrapper that observes pooled-session read-only provenance without
 * changing JDBC behavior.
 *
 * <p>Explicit vendor {@code unwrap} to the delegate or vendor connection remains an acknowledged
 * below-wrapper blind spot: JDBC calls made through an unwrapped handle are not instrumented.
 */
public final class SpecA912ProvenanceDataSource extends DelegatingDataSource implements AutoCloseable {

    static final String PHASE_SETTER_ATTEMPT = "setter-attempt";
    static final String PHASE_SETTER_CONFIRMED = "setter-confirmed";
    static final String PHASE_POST_SETTER = "post-setter";

    private final SpecA912PooledSessionProvenance provenance;
    private final AtomicLong checkoutIds = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SpecA912ProvenanceDataSource(DataSource delegate, SpecA912PooledSessionProvenance provenance) {
        super(delegate);
        this.provenance = provenance;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection(), checkoutIds.incrementAndGet());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password), checkoutIds.incrementAndGet());
    }

    private Connection wrap(Connection delegate, long checkoutId) throws SQLException {
        SpecA912JdbcProxies.captureSnapshot(provenance, "checkout", checkoutId, delegate);
        return SpecA912JdbcProxies.connection(delegate, checkoutId, provenance);
    }

    @Override
    public void close() throws Exception {
        if (closed.compareAndSet(false, true) && getTargetDataSource() instanceof AutoCloseable target) {
            target.close();
        }
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        if (iface.isInstance(this) || iface.isInstance(getTargetDataSource())) {
            return true;
        }
        DataSource target = getTargetDataSource();
        if (target instanceof Wrapper wrapper) {
            return wrapper.isWrapperFor(iface);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        DataSource target = getTargetDataSource();
        if (iface.isInstance(target)) {
            return iface.cast(target);
        }
        if (target instanceof Wrapper wrapper) {
            return wrapper.unwrap(iface);
        }
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    static final class SpecA912JdbcProxies {

        private SpecA912JdbcProxies() {}

        static Connection connection(
                Connection delegate, long checkoutId, SpecA912PooledSessionProvenance provenance) {
            ConnectionHandler handler = new ConnectionHandler(delegate, checkoutId, provenance);
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            handler);
        }

        static Statement statement(
                Statement delegate,
                Connection rawConnection,
                Connection connectionProxy,
                long checkoutId,
                SpecA912PooledSessionProvenance provenance,
                String sqlHint) {
            Class<?>[] interfaces =
                    delegate instanceof CallableStatement
                            ? new Class<?>[] {CallableStatement.class}
                            : delegate instanceof PreparedStatement
                                    ? new Class<?>[] {PreparedStatement.class}
                                    : new Class<?>[] {Statement.class};
            return (Statement)
                    Proxy.newProxyInstance(
                            Statement.class.getClassLoader(),
                            interfaces,
                            new StatementHandler(
                                    delegate,
                                    rawConnection,
                                    connectionProxy,
                                    checkoutId,
                                    provenance,
                                    sqlHint));
        }

        static void captureSnapshot(
                SpecA912PooledSessionProvenance provenance,
                String phase,
                long checkoutId,
                Connection rawConnection) {
            try {
                if (!rawConnection.getAutoCommit()) {
                    provenance.emitObservationSkipped(phase, checkoutId, "auto-commit-false");
                    return;
                }
                SpecA912PooledSessionProvenance.SessionSnapshot snapshot = snapshot(rawConnection);
                provenance.observe(phase, checkoutId, snapshot);
            } catch (SQLException ex) {
                provenance.emitObservationFailure(phase, ex.getClass(), ex.getSQLState());
            } catch (RuntimeException ex) {
                provenance.emitObservationFailure(phase, ex.getClass(), null);
            }
        }

        static void captureAndResolveSetter(
                SpecA912PooledSessionProvenance provenance,
                long checkoutId,
                Connection rawConnection,
                SpecA912PooledSessionProvenance.SetterKind setterKind,
                String callPath) {
            try {
                if (!rawConnection.getAutoCommit()) {
                    provenance.emitObservationSkipped(PHASE_POST_SETTER, checkoutId, "auto-commit-false");
                    return;
                }
                SpecA912PooledSessionProvenance.SessionSnapshot snapshot = snapshot(rawConnection);
                provenance.captureAndResolveSetter(
                        PHASE_SETTER_CONFIRMED,
                        PHASE_POST_SETTER,
                        checkoutId,
                        snapshot,
                        setterKind,
                        callPath);
            } catch (SQLException ex) {
                provenance.emitObservationFailure(PHASE_POST_SETTER, ex.getClass(), ex.getSQLState());
            } catch (RuntimeException ex) {
                provenance.emitObservationFailure(PHASE_POST_SETTER, ex.getClass(), null);
            }
        }

        private static SpecA912PooledSessionProvenance.SessionSnapshot snapshot(Connection connection)
                throws SQLException {
            boolean autoCommit = connection.getAutoCommit();
            boolean jdbcReadOnly = connection.isReadOnly();
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
                    pid, jdbcReadOnly, autoCommit, defaultRo, txRo);
        }

        private static Object invokeDelegate(Object delegate, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }

        private static void safeRecordAttemptNoSql(
                SpecA912PooledSessionProvenance provenance,
                long checkoutId,
                SpecA912PooledSessionProvenance.SetterKind kind,
                String callPath) {
            try {
                provenance.recordSetterAttempt(PHASE_SETTER_ATTEMPT, checkoutId, null, kind, callPath);
            } catch (RuntimeException ex) {
                provenance.emitObservationFailure(PHASE_SETTER_ATTEMPT, ex.getClass(), null);
            }
        }

        private static final class ConnectionHandler implements InvocationHandler {
            private final Connection delegate;
            private final long checkoutId;
            private final SpecA912PooledSessionProvenance provenance;
            private Connection connectionProxy;

            ConnectionHandler(Connection delegate, long checkoutId, SpecA912PooledSessionProvenance provenance) {
                this.delegate = delegate;
                this.checkoutId = checkoutId;
                this.provenance = provenance;
            }

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (connectionProxy == null) {
                    connectionProxy = (Connection) proxy;
                }
                String name = method.getName();
                if ("close".equals(name) && (args == null || args.length == 0)) {
                    captureSnapshot(provenance, "return", checkoutId, delegate);
                    return invokeDelegate(delegate, method, args);
                }
                if ("unwrap".equals(name) && args != null && args.length == 1) {
                    Class<?> iface = (Class<?>) args[0];
                    if (iface.isInstance(delegate)) {
                        return delegate;
                    }
                    if (delegate.isWrapperFor(iface)) {
                        return delegate.unwrap(iface);
                    }
                }
                if ("isWrapperFor".equals(name) && args != null && args.length == 1) {
                    Class<?> iface = (Class<?>) args[0];
                    return iface.isInstance(delegate) || delegate.isWrapperFor(iface);
                }
                if ("setReadOnly".equals(name) && args != null && args.length == 1 && args[0] instanceof Boolean ro) {
                    return interceptSetReadOnly(method, ro);
                }
                if ("createStatement".equals(name)) {
                    Statement statement = (Statement) invokeDelegate(delegate, method, args);
                    return statement(
                            statement, delegate, connectionProxy, checkoutId, provenance, null);
                }
                if ("prepareStatement".equals(name) && args != null && args.length >= 1 && args[0] instanceof String sql) {
                    PreparedStatement statement =
                            (PreparedStatement) invokeDelegate(delegate, method, args);
                    return statement(statement, delegate, connectionProxy, checkoutId, provenance, sql);
                }
                if ("prepareCall".equals(name) && args != null && args.length >= 1 && args[0] instanceof String sql) {
                    CallableStatement statement =
                            (CallableStatement) invokeDelegate(delegate, method, args);
                    return statement(statement, delegate, connectionProxy, checkoutId, provenance, sql);
                }
                return invokeDelegate(delegate, method, args);
            }

            private Object interceptSetReadOnly(Method method, boolean readOnly) throws Throwable {
                SpecA912PooledSessionProvenance.SetterKind kind =
                        SpecA912PooledSessionProvenance.classifyConnectionSetReadOnly(readOnly);
                String callPath = SpecA912PooledSessionProvenance.boundedCallPath();
                safeRecordAttemptNoSql(provenance, checkoutId, kind, callPath);
                Object result = invokeDelegate(delegate, method, new Object[] {readOnly});
                captureAndResolveSetter(provenance, checkoutId, delegate, kind, callPath);
                return result;
            }
        }

        private static final class StatementHandler implements InvocationHandler {
            private final Statement delegate;
            private final Connection rawConnection;
            private final Connection connectionProxy;
            private final long checkoutId;
            private final SpecA912PooledSessionProvenance provenance;
            private final String sqlHint;
            private final List<SpecA912PooledSessionProvenance.SetterKind> queuedSetters = new ArrayList<>();

            StatementHandler(
                    Statement delegate,
                    Connection rawConnection,
                    Connection connectionProxy,
                    long checkoutId,
                    SpecA912PooledSessionProvenance provenance,
                    String sqlHint) {
                this.delegate = delegate;
                this.rawConnection = rawConnection;
                this.connectionProxy = connectionProxy;
                this.checkoutId = checkoutId;
                this.provenance = provenance;
                this.sqlHint = sqlHint;
            }

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("getConnection".equals(name) && (args == null || args.length == 0)) {
                    return connectionProxy;
                }
                if ("clearBatch".equals(name)) {
                    Object result = invokeDelegate(delegate, method, args);
                    queuedSetters.clear();
                    return result;
                }
                if ("addBatch".equals(name)) {
                    return interceptAddBatch(method, args);
                }
                if (isBatchExecute(name, args)) {
                    return interceptBatchExecute(method, args);
                }
                if (isDirectSqlExecution(name, args)) {
                    String sql = sqlFromExecution(name, args);
                    return interceptDirectSql(method, args, sql);
                }
                return invokeDelegate(delegate, method, args);
            }

            private Object interceptAddBatch(Method method, Object[] args) throws Throwable {
                String sql = sqlFromBatchArgs(args);
                if (sql == null) {
                    return invokeDelegate(delegate, method, args);
                }
                SpecA912PooledSessionProvenance.SetterKind kind =
                        SpecA912PooledSessionProvenance.classifySetter(sql);
                Object result = invokeDelegate(delegate, method, args);
                if (kind != SpecA912PooledSessionProvenance.SetterKind.NONE) {
                    queuedSetters.add(kind);
                    safeRecordAttemptNoSql(
                            provenance,
                            checkoutId,
                            kind,
                            SpecA912PooledSessionProvenance.boundedCallPath());
                }
                return result;
            }

            private Object interceptBatchExecute(Method method, Object[] args) throws Throwable {
                List<SpecA912PooledSessionProvenance.SetterKind> batch = List.copyOf(queuedSetters);
                Object result = invokeDelegate(delegate, method, args);
                SpecA912PooledSessionProvenance.SetterKind attributionKind =
                        SpecA912PooledSessionProvenance.lastSessionStateAffectingSetter(batch);
                if (attributionKind != SpecA912PooledSessionProvenance.SetterKind.NONE) {
                    captureAndResolveSetter(
                            provenance,
                            checkoutId,
                            rawConnection,
                            attributionKind,
                            SpecA912PooledSessionProvenance.boundedCallPath());
                }
                queuedSetters.clear();
                return result;
            }

            private Object interceptDirectSql(Method method, Object[] args, String sql) throws Throwable {
                SpecA912PooledSessionProvenance.SetterKind kind =
                        SpecA912PooledSessionProvenance.classifySetter(sql);
                String callPath = SpecA912PooledSessionProvenance.boundedCallPath();
                if (kind != SpecA912PooledSessionProvenance.SetterKind.NONE) {
                    safeRecordAttemptNoSql(provenance, checkoutId, kind, callPath);
                }
                Object result = invokeDelegate(delegate, method, args);
                if (kind != SpecA912PooledSessionProvenance.SetterKind.NONE) {
                    captureAndResolveSetter(provenance, checkoutId, rawConnection, kind, callPath);
                }
                return result;
            }

            private String sqlFromBatchArgs(Object[] args) {
                if (args != null && args.length >= 1 && args[0] instanceof String sql) {
                    return sql;
                }
                return sqlHint;
            }

            private static boolean isBatchExecute(String name, Object[] args) {
                return ("executeBatch".equals(name) || "executeLargeBatch".equals(name))
                        && (args == null || args.length == 0);
            }

            private static boolean isDirectSqlExecution(String name, Object[] args) {
                if (name.startsWith("execute") && args != null && args.length >= 1 && args[0] instanceof String) {
                    return true;
                }
                return switch (name) {
                    case "execute", "executeUpdate", "executeQuery", "executeLargeUpdate" ->
                            args == null || args.length == 0;
                    default -> false;
                };
            }

            private String sqlFromExecution(String name, Object[] args) {
                if (args != null && args.length >= 1 && args[0] instanceof String sql) {
                    return sql;
                }
                return sqlHint;
            }
        }
    }
}
