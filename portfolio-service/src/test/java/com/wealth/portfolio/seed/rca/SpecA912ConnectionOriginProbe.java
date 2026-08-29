package com.wealth.portfolio.seed.rca;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

/**
 * Manual, fail-closed Spec A 9.12 connection-origin probe. Lives in the test source set so it is
 * excluded from the production artifact. Issues only allow-listed read-only metadata queries.
 */
public final class SpecA912ConnectionOriginProbe {

    public enum EndpointLabel {
        POOLED,
        DIRECT
    }

    public enum SettingSource {
        DEFAULT,
        USER,
        DATABASE,
        DATABASE_USER,
        CLIENT,
        OVERRIDE,
        CONFIGURATION_FILE,
        COMMAND_LINE,
        ENVIRONMENT_VARIABLE,
        GLOBAL,
        INTERACTIVE,
        TEST,
        SESSION,
        UNKNOWN
    }

    public enum CatalogDefaultScope {
        DATABASE,
        ROLE,
        DATABASE_ROLE
    }

    public enum Verdict {
        NOT_REPRODUCED_IN_MANUAL_MATRIX,
        POOLED_PATH_DIVERGENCE_PROVEN,
        PERSISTENT_DEFAULT_PATH_EVIDENCED,
        CLIENT_STARTUP_DEFAULT_OBSERVED,
        INCONCLUSIVE
    }

    public enum ConfigFailureReason {
        MISSING_REQUIRED,
        APPROVAL_NOT_TRUE,
        DUPLICATE_ENDPOINT_URLS
    }

    public record CatalogDefault(CatalogDefaultScope scope, String value) {
        public CatalogDefault {
            Objects.requireNonNull(scope, "scope");
            value = normalizeOnOff(value);
        }
    }

    public record AttemptEvidence(
            EndpointLabel endpoint,
            int attemptNumber,
            Long backendPid,
            Boolean jdbcReadOnly,
            Boolean autoCommit,
            String defaultTransactionReadOnly,
            String transactionReadOnly,
            Boolean pgIsInRecovery,
            String setting,
            String resetVal,
            SettingSource source,
            List<CatalogDefault> catalogDefaults,
            String exceptionClass,
            String sqlState) {

        public AttemptEvidence {
            Objects.requireNonNull(endpoint, "endpoint");
            if (attemptNumber < 1 || attemptNumber > 5) {
                throw new IllegalArgumentException("attempt out of range");
            }
            catalogDefaults =
                    catalogDefaults == null ? List.of() : List.copyOf(catalogDefaults);
            if (exceptionClass != null) {
                if (sqlState != null
                        && (sqlState.length() != 5
                                || !sqlState.chars().allMatch(Character::isLetterOrDigit))) {
                    throw new IllegalArgumentException("invalid sqlState");
                }
            } else {
                defaultTransactionReadOnly = normalizeOnOff(defaultTransactionReadOnly);
                transactionReadOnly = normalizeOnOff(transactionReadOnly);
                setting = normalizeOnOff(setting);
                resetVal = normalizeOnOff(resetVal);
                Objects.requireNonNull(source, "source");
                Objects.requireNonNull(backendPid, "backendPid");
                Objects.requireNonNull(jdbcReadOnly, "jdbcReadOnly");
                Objects.requireNonNull(autoCommit, "autoCommit");
                Objects.requireNonNull(pgIsInRecovery, "pgIsInRecovery");
            }
        }

        boolean failed() {
            return exceptionClass != null;
        }

        boolean allOn() {
            return !failed()
                    && "on".equals(defaultTransactionReadOnly)
                    && "on".equals(transactionReadOnly);
        }

        boolean allOff() {
            return !failed()
                    && "off".equals(defaultTransactionReadOnly)
                    && "off".equals(transactionReadOnly);
        }
    }

    public record ProbeMatrix(List<AttemptEvidence> attempts) {
        public ProbeMatrix {
            attempts = List.copyOf(attempts);
        }

        List<AttemptEvidence> forEndpoint(EndpointLabel endpoint) {
            return attempts.stream().filter(a -> a.endpoint() == endpoint).toList();
        }
    }

    public record LiveConfig(
            String pooledJdbcUrl, String directJdbcUrl, String username, String password) {
        @Override
        public String toString() {
            return "LiveConfig[REDACTED]";
        }
    }

    public record LiveConfigResult(
            boolean failed,
            ConfigFailureReason reason,
            List<String> missingVariableNames,
            LiveConfig config) {
        @Override
        public String toString() {
            return "LiveConfigResult[failed="
                    + failed
                    + ", reason="
                    + reason
                    + ", missing="
                    + missingVariableNames
                    + ", config="
                    + (config == null ? "null" : "LiveConfig[REDACTED]")
                    + "]";
        }
    }

    @FunctionalInterface
    interface SessionCollector {
        AttemptEvidence collect(
                EndpointLabel endpoint, int attemptNumber, String jdbcUrl, String username, String password);
    }

    static final int ATTEMPTS_PER_ENDPOINT = 5;
    /** One composite read-only SELECT per attempt keeps evidence on a single backend session. */
    static final int DATABASE_STATEMENTS_PER_ATTEMPT = 1;

    private static final String ENV_APPROVED = "SPEC_A_912_LIVE_PROBE_APPROVED";
    private static final String ENV_POOLED = "SPEC_A_912_POOLED_JDBC_URL";
    private static final String ENV_DIRECT = "SPEC_A_912_DIRECT_JDBC_URL";
    private static final String ENV_USERNAME = "SPEC_A_912_DB_USERNAME";
    private static final String ENV_PASSWORD = "SPEC_A_912_DB_PASSWORD";

    private static final List<String> REQUIRED_ENV =
            List.of(ENV_APPROVED, ENV_POOLED, ENV_DIRECT, ENV_USERNAME, ENV_PASSWORD);

    /**
     * Single-statement coherent snapshot. Required under Neon PgBouncer transaction pooling so PID,
     * GUC values, pg_settings source, and catalog defaults come from one backend allocation.
     */
    static final String COMPOSITE_SNAPSHOT_SQL =
            """
            SELECT
                pg_backend_pid() AS backend_pid,
                pg_is_in_recovery() AS pg_is_in_recovery,
                current_setting('default_transaction_read_only') AS default_transaction_read_only,
                current_setting('transaction_read_only') AS transaction_read_only,
                s.setting AS setting,
                s.reset_val AS reset_val,
                s.source AS source,
                catalog.scopes AS catalog_scopes,
                catalog.values AS catalog_values
            FROM pg_settings s
            CROSS JOIN LATERAL (
                SELECT
                    COALESCE(array_agg(scoped.scope ORDER BY scoped.scope), ARRAY[]::text[]) AS scopes,
                    COALESCE(array_agg(scoped.setting_value ORDER BY scoped.scope), ARRAY[]::text[]) AS values
                FROM (
                    WITH current_ids AS (
                        SELECT
                            (SELECT oid FROM pg_database WHERE datname = current_database()) AS database_oid,
                            (SELECT oid FROM pg_roles WHERE rolname = current_user) AS role_oid
                    )
                    SELECT
                        CASE
                            WHEN settings.setdatabase = current_ids.database_oid
                             AND settings.setrole = current_ids.role_oid THEN 'DATABASE_ROLE'
                            WHEN settings.setdatabase = current_ids.database_oid
                             AND settings.setrole = 0 THEN 'DATABASE'
                            WHEN settings.setdatabase = 0
                             AND settings.setrole = current_ids.role_oid THEN 'ROLE'
                        END AS scope,
                        split_part(entry.value, '=', 2) AS setting_value
                    FROM pg_db_role_setting settings
                    CROSS JOIN current_ids
                    CROSS JOIN LATERAL unnest(settings.setconfig) AS entry(value)
                    WHERE entry.value LIKE 'default_transaction_read_only=%'
                      AND (
                          (settings.setdatabase = current_ids.database_oid AND settings.setrole IN (0, current_ids.role_oid))
                          OR (settings.setdatabase = 0 AND settings.setrole = current_ids.role_oid)
                      )
                ) scoped
                WHERE scoped.scope IS NOT NULL
            ) catalog
            WHERE s.name = 'default_transaction_read_only'
            """
                    .stripIndent()
                    .trim();

    private static final List<String> ALLOW_LISTED_STATEMENTS = List.of(COMPOSITE_SNAPSHOT_SQL);

    private static final Map<String, SettingSource> KNOWN_SOURCES = knownSources();

    private SpecA912ConnectionOriginProbe() {}

    public static List<String> allowListedStatements() {
        return ALLOW_LISTED_STATEMENTS;
    }

    public static SettingSource parseSettingSource(String raw) {
        if (raw == null) {
            return SettingSource.UNKNOWN;
        }
        SettingSource mapped = KNOWN_SOURCES.get(raw);
        return mapped == null ? SettingSource.UNKNOWN : mapped;
    }

    public static String normalizeOnOff(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("on/off required");
        }
        String trimmed = raw.trim();
        if ("on".equalsIgnoreCase(trimmed)) {
            return "on";
        }
        if ("off".equalsIgnoreCase(trimmed)) {
            return "off";
        }
        throw new IllegalArgumentException("on/off required");
    }

    public static ProbeMatrix requireCompleteMatrix(List<AttemptEvidence> attempts) {
        Objects.requireNonNull(attempts, "attempts");
        if (attempts.size() != ATTEMPTS_PER_ENDPOINT * 2) {
            throw new IllegalArgumentException("incomplete matrix");
        }
        List<AttemptEvidence> ordered = new ArrayList<>(ATTEMPTS_PER_ENDPOINT * 2);
        for (EndpointLabel endpoint : List.of(EndpointLabel.POOLED, EndpointLabel.DIRECT)) {
            for (int i = 1; i <= ATTEMPTS_PER_ENDPOINT; i++) {
                final int attempt = i;
                AttemptEvidence match =
                        attempts.stream()
                                .filter(a -> a.endpoint() == endpoint && a.attemptNumber() == attempt)
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("incomplete matrix"));
                ordered.add(match);
            }
        }
        return new ProbeMatrix(ordered);
    }

    public static Verdict classifyIncomplete(List<AttemptEvidence> attempts) {
        return Verdict.INCONCLUSIVE;
    }

    public static Verdict classify(ProbeMatrix matrix) {
        List<AttemptEvidence> pooled = matrix.forEndpoint(EndpointLabel.POOLED);
        List<AttemptEvidence> direct = matrix.forEndpoint(EndpointLabel.DIRECT);
        if (pooled.size() != ATTEMPTS_PER_ENDPOINT || direct.size() != ATTEMPTS_PER_ENDPOINT) {
            return Verdict.INCONCLUSIVE;
        }
        if (hasFailureOrMixOrUnknown(pooled) || hasFailureOrMixOrUnknown(direct)) {
            return Verdict.INCONCLUSIVE;
        }

        boolean pooledOn = pooled.stream().allMatch(AttemptEvidence::allOn);
        boolean pooledOff = pooled.stream().allMatch(AttemptEvidence::allOff);
        boolean directOn = direct.stream().allMatch(AttemptEvidence::allOn);
        boolean directOff = direct.stream().allMatch(AttemptEvidence::allOff);

        if (pooledOff && directOff) {
            return Verdict.NOT_REPRODUCED_IN_MANUAL_MATRIX;
        }
        if (pooledOn && directOff) {
            return Verdict.POOLED_PATH_DIVERGENCE_PROVEN;
        }
        if (directOn && pooledOff) {
            return Verdict.INCONCLUSIVE;
        }
        if (pooledOn && directOn) {
            SettingSource directSource = uniformSource(direct);
            SettingSource pooledSource = uniformSource(pooled);
            if (directSource == SettingSource.UNKNOWN
                    || pooledSource == SettingSource.UNKNOWN
                    || directSource == null
                    || pooledSource == null) {
                return Verdict.INCONCLUSIVE;
            }
            if (isPersistent(directSource)) {
                return Verdict.PERSISTENT_DEFAULT_PATH_EVIDENCED;
            }
            if (directSource == SettingSource.CLIENT && pooledSource == SettingSource.CLIENT) {
                return Verdict.CLIENT_STARTUP_DEFAULT_OBSERVED;
            }
            return Verdict.INCONCLUSIVE;
        }
        return Verdict.INCONCLUSIVE;
    }

    public static SettingSource persistentSource(ProbeMatrix matrix) {
        if (classify(matrix) != Verdict.PERSISTENT_DEFAULT_PATH_EVIDENCED) {
            return null;
        }
        return uniformSource(matrix.forEndpoint(EndpointLabel.DIRECT));
    }

    public static LiveConfigResult resolveLiveConfig(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        List<String> missing = new ArrayList<>();
        for (String key : REQUIRED_ENV) {
            String value = env.get(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            return new LiveConfigResult(true, ConfigFailureReason.MISSING_REQUIRED, List.copyOf(missing), null);
        }
        String approved = env.get(ENV_APPROVED).trim();
        if (!"true".equals(approved)) {
            return new LiveConfigResult(true, ConfigFailureReason.APPROVAL_NOT_TRUE, List.of(), null);
        }
        String pooled = env.get(ENV_POOLED);
        String direct = env.get(ENV_DIRECT);
        if (pooled.equals(direct)) {
            return new LiveConfigResult(
                    true, ConfigFailureReason.DUPLICATE_ENDPOINT_URLS, List.of(), null);
        }
        return new LiveConfigResult(
                false,
                null,
                List.of(),
                new LiveConfig(pooled, direct, env.get(ENV_USERNAME), env.get(ENV_PASSWORD)));
    }

    public static AttemptEvidence sanitizeFailure(
            EndpointLabel endpoint, int attemptNumber, Throwable error) {
        String sqlState = null;
        if (error instanceof SQLException sqlException) {
            String candidate = sqlException.getSQLState();
            if (candidate != null
                    && candidate.length() == 5
                    && candidate.chars().allMatch(Character::isLetterOrDigit)) {
                sqlState = candidate;
            }
        }
        return new AttemptEvidence(
                endpoint,
                attemptNumber,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                error.getClass().getSimpleName(),
                sqlState);
    }

    public static List<AttemptEvidence> collectMatrix(LiveConfig config, SessionCollector collector) {
        List<AttemptEvidence> attempts = new ArrayList<>(ATTEMPTS_PER_ENDPOINT * 2);
        Map<EndpointLabel, String> urls = new LinkedHashMap<>();
        urls.put(EndpointLabel.POOLED, config.pooledJdbcUrl());
        urls.put(EndpointLabel.DIRECT, config.directJdbcUrl());
        for (Map.Entry<EndpointLabel, String> entry : urls.entrySet()) {
            for (int attempt = 1; attempt <= ATTEMPTS_PER_ENDPOINT; attempt++) {
                try {
                    attempts.add(
                            collector.collect(
                                    entry.getKey(),
                                    attempt,
                                    entry.getValue(),
                                    config.username(),
                                    config.password()));
                } catch (Exception ex) {
                    attempts.add(sanitizeFailure(entry.getKey(), attempt, ex));
                }
            }
        }
        return attempts;
    }

    public static AttemptEvidence collectOnce(
            EndpointLabel endpoint, int attemptNumber, String jdbcUrl, String username, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "15");

        try (Connection connection = DriverManager.getConnection(jdbcUrl, properties)) {
            boolean jdbcReadOnly = connection.isReadOnly();
            boolean autoCommit = connection.getAutoCommit();

            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(COMPOSITE_SNAPSHOT_SQL)) {
                if (!rs.next()) {
                    throw new SQLException("empty snapshot row", "02000");
                }
                long backendPid = rs.getLong("backend_pid");
                boolean pgIsInRecovery = rs.getBoolean("pg_is_in_recovery");
                String defaultTransactionReadOnly = rs.getString("default_transaction_read_only");
                String transactionReadOnly = rs.getString("transaction_read_only");
                String setting = rs.getString("setting");
                String resetVal = rs.getString("reset_val");
                SettingSource source = parseSettingSource(rs.getString("source"));
                List<CatalogDefault> catalogDefaults =
                        readCatalogDefaults(rs.getArray("catalog_scopes"), rs.getArray("catalog_values"));

                return new AttemptEvidence(
                        endpoint,
                        attemptNumber,
                        backendPid,
                        jdbcReadOnly,
                        autoCommit,
                        defaultTransactionReadOnly,
                        transactionReadOnly,
                        pgIsInRecovery,
                        setting,
                        resetVal,
                        source,
                        catalogDefaults,
                        null,
                        null);
            }
        } catch (Exception ex) {
            return sanitizeFailure(endpoint, attemptNumber, ex);
        }
    }

    public static String format(ProbeMatrix matrix, Verdict verdict, SettingSource persistentSource) {
        StringBuilder out = new StringBuilder();
        for (AttemptEvidence attempt : matrix.attempts()) {
            out.append(formatAttempt(attempt)).append('\n');
        }
        out.append("{\"verdict\":\"").append(verdict.name()).append('"');
        if (persistentSource != null) {
            out.append(",\"persistentSource\":\"").append(persistentSource.name()).append('"');
        }
        out.append('}');
        return out.toString();
    }

    public static String formatConfigurationFailure(
            List<String> missingVariableNames, ConfigFailureReason reason) {
        StringBuilder out = new StringBuilder();
        out.append("{\"configFailure\":\"").append(reason.name()).append("\",\"missing\":[");
        for (int i = 0; i < missingVariableNames.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(missingVariableNames.get(i)).append('"');
        }
        out.append("]}");
        return out.toString();
    }

    public static int run(Function<String, String> envLookup, Appendable out) throws Exception {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : REQUIRED_ENV) {
            String value = envLookup.apply(key);
            if (value != null) {
                env.put(key, value);
            }
        }
        LiveConfigResult configResult = resolveLiveConfig(env);
        if (configResult.failed()) {
            out.append(
                    formatConfigurationFailure(
                            configResult.missingVariableNames(), configResult.reason()));
            out.append('\n');
            return 2;
        }

        List<AttemptEvidence> attempts =
                collectMatrix(configResult.config(), SpecA912ConnectionOriginProbe::collectOnce);
        boolean anyFailure = attempts.stream().anyMatch(AttemptEvidence::failed);
        ProbeMatrix matrix;
        Verdict verdict;
        if (anyFailure || attempts.size() != ATTEMPTS_PER_ENDPOINT * 2) {
            verdict = Verdict.INCONCLUSIVE;
            try {
                matrix = requireCompleteMatrix(attempts);
            } catch (IllegalArgumentException ex) {
                out.append(formatIncomplete(attempts, verdict));
                out.append('\n');
                return 1;
            }
        } else {
            matrix = requireCompleteMatrix(attempts);
            verdict = classify(matrix);
        }
        SettingSource persistent =
                verdict == Verdict.PERSISTENT_DEFAULT_PATH_EVIDENCED
                        ? persistentSource(matrix)
                        : null;
        out.append(format(matrix, verdict, persistent));
        out.append('\n');
        return verdict == Verdict.INCONCLUSIVE || anyFailure ? 1 : 0;
    }

    public static void main(String[] args) {
        try {
            int code = run(System::getenv, System.out);
            System.exit(code);
        } catch (Throwable unexpected) {
            System.out.println(
                    "{\"configFailure\":\"UNEXPECTED\",\"exceptionClass\":\""
                            + unexpected.getClass().getSimpleName()
                            + "\"}");
            System.exit(2);
        }
    }

    private static List<CatalogDefault> readCatalogDefaults(
            java.sql.Array scopesArray, java.sql.Array valuesArray) throws SQLException {
        if (scopesArray == null || valuesArray == null) {
            return List.of();
        }
        String[] scopes = toStringArray(scopesArray.getArray());
        String[] values = toStringArray(valuesArray.getArray());
        if (scopes == null || values == null) {
            return List.of();
        }
        int length = Math.min(scopes.length, values.length);
        List<CatalogDefault> catalogDefaults = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            String scopeName = scopes[i];
            String settingValue = values[i];
            if (scopeName == null || settingValue == null) {
                continue;
            }
            CatalogDefaultScope scope =
                    switch (scopeName) {
                        case "DATABASE" -> CatalogDefaultScope.DATABASE;
                        case "ROLE" -> CatalogDefaultScope.ROLE;
                        case "DATABASE_ROLE" -> CatalogDefaultScope.DATABASE_ROLE;
                        default -> null;
                    };
            if (scope != null) {
                catalogDefaults.add(new CatalogDefault(scope, settingValue));
            }
        }
        return catalogDefaults;
    }

    private static String[] toStringArray(Object raw) {
        if (raw instanceof String[] strings) {
            return strings;
        }
        if (raw instanceof Object[] objects) {
            String[] strings = new String[objects.length];
            for (int i = 0; i < objects.length; i++) {
                strings[i] = objects[i] == null ? null : objects[i].toString();
            }
            return strings;
        }
        return null;
    }

    private static String formatIncomplete(List<AttemptEvidence> attempts, Verdict verdict) {
        StringBuilder out = new StringBuilder();
        for (AttemptEvidence attempt : attempts) {
            out.append(formatAttempt(attempt)).append('\n');
        }
        out.append("{\"verdict\":\"").append(verdict.name()).append("\"}");
        return out.toString();
    }

    private static String formatAttempt(AttemptEvidence attempt) {
        StringBuilder out = new StringBuilder();
        out.append('{');
        append(out, "endpoint", attempt.endpoint().name(), true);
        append(out, "attempt", Integer.toString(attempt.attemptNumber()), false);
        if (attempt.failed()) {
            append(out, "exceptionClass", attempt.exceptionClass(), true);
            if (attempt.sqlState() != null) {
                append(out, "sqlState", attempt.sqlState(), true);
            }
        } else {
            append(out, "backendPid", Long.toString(attempt.backendPid()), false);
            append(out, "jdbcReadOnly", Boolean.toString(attempt.jdbcReadOnly()), false);
            append(out, "autoCommit", Boolean.toString(attempt.autoCommit()), false);
            append(out, "defaultTransactionReadOnly", attempt.defaultTransactionReadOnly(), true);
            append(out, "transactionReadOnly", attempt.transactionReadOnly(), true);
            append(out, "pgIsInRecovery", Boolean.toString(attempt.pgIsInRecovery()), false);
            append(out, "setting", attempt.setting(), true);
            append(out, "resetVal", attempt.resetVal(), true);
            append(out, "source", attempt.source().name(), true);
            out.append(",\"catalogDefaults\":[");
            List<CatalogDefault> defaults = attempt.catalogDefaults();
            for (int i = 0; i < defaults.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                CatalogDefault catalogDefault = defaults.get(i);
                out.append("{\"scope\":\"")
                        .append(catalogDefault.scope().name())
                        .append("\",\"value\":\"")
                        .append(catalogDefault.value())
                        .append("\"}");
            }
            out.append(']');
        }
        out.append('}');
        return out.toString();
    }

    private static void append(StringBuilder out, String key, String value, boolean quote) {
        if (out.length() > 1) {
            out.append(',');
        }
        out.append('"').append(key).append("\":");
        if (quote) {
            out.append('"').append(value).append('"');
        } else {
            out.append(value);
        }
    }

    private static boolean hasFailureOrMixOrUnknown(List<AttemptEvidence> attempts) {
        if (attempts.stream().anyMatch(AttemptEvidence::failed)) {
            return true;
        }
        boolean anyOn = attempts.stream().anyMatch(AttemptEvidence::allOn);
        boolean anyOff = attempts.stream().anyMatch(AttemptEvidence::allOff);
        if (anyOn && anyOff) {
            return true;
        }
        if (!attempts.stream().allMatch(a -> a.allOn() || a.allOff())) {
            return true;
        }
        SettingSource source = uniformSource(attempts);
        return source == null || source == SettingSource.UNKNOWN;
    }

    private static SettingSource uniformSource(List<AttemptEvidence> attempts) {
        SettingSource first = null;
        for (AttemptEvidence attempt : attempts) {
            if (attempt.failed()) {
                return null;
            }
            if (first == null) {
                first = attempt.source();
            } else if (first != attempt.source()) {
                return null;
            }
        }
        return first;
    }

    private static boolean isPersistent(SettingSource source) {
        return source == SettingSource.DATABASE
                || source == SettingSource.USER
                || source == SettingSource.DATABASE_USER;
    }

    private static Map<String, SettingSource> knownSources() {
        EnumMap<SettingSource, String> labels = new EnumMap<>(SettingSource.class);
        labels.put(SettingSource.DEFAULT, "default");
        labels.put(SettingSource.USER, "user");
        labels.put(SettingSource.DATABASE, "database");
        labels.put(SettingSource.DATABASE_USER, "database user");
        labels.put(SettingSource.CLIENT, "client");
        labels.put(SettingSource.OVERRIDE, "override");
        labels.put(SettingSource.CONFIGURATION_FILE, "configuration file");
        labels.put(SettingSource.COMMAND_LINE, "command line");
        labels.put(SettingSource.ENVIRONMENT_VARIABLE, "environment variable");
        labels.put(SettingSource.GLOBAL, "global");
        labels.put(SettingSource.INTERACTIVE, "interactive");
        labels.put(SettingSource.TEST, "test");
        labels.put(SettingSource.SESSION, "session");
        Map<String, SettingSource> map = new java.util.HashMap<>();
        for (Map.Entry<SettingSource, String> entry : labels.entrySet()) {
            map.put(entry.getValue(), entry.getKey());
        }
        return Map.copyOf(map);
    }
}
