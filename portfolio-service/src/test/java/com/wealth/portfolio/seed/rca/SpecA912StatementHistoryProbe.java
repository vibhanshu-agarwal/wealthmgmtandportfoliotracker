package com.wealth.portfolio.seed.rca;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

/**
 * Manual, fail-closed Spec A 9.12 statement-history probe. Lives in the test source set so it is
 * excluded from the production artifact. Classification is reset-window-aware and visibility-aware;
 * retained counts are observations without execution timestamps.
 */
public final class SpecA912StatementHistoryProbe {

    /** Creation time of enable run 33150399420. Not accepted from environment input. */
    public static final Instant INCIDENT_START = Instant.parse("2026-08-28T07:08:59Z");

    public enum SetterShape {
        SET_DEFAULT_TRANSACTION_READ_ONLY,
        SET_SESSION_CHARACTERISTICS_READ_ONLY,
        SET_TRANSACTION_READ_ONLY,
        RESET_DEFAULT_TRANSACTION_READ_ONLY,
        ALTER_ROLE_DEFAULT_TRANSACTION_READ_ONLY,
        ALTER_DATABASE_DEFAULT_TRANSACTION_READ_ONLY,
        DISCARD_ALL
    }

    public enum Verdict {
        SETTER_SHAPE_PRESENT_IN_COVERING_STATS,
        SETTER_SHAPE_PRESENT_OUTSIDE_INCIDENT_COVERAGE,
        NO_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS,
        NO_CURRENT_ROLE_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS,
        STATS_WINDOW_NOT_COVERING_INCIDENT,
        STATS_EVICTION_PREVENTS_ABSENCE_CLAIM,
        UTILITY_TRACKING_DISABLED,
        STATEMENT_TRACKING_DISABLED,
        QUERY_ID_CALCULATION_DISABLED,
        STATEMENT_HISTORY_UNAVAILABLE,
        INCONCLUSIVE
    }

    public enum StatementTrack {
        TOP,
        ALL,
        NONE,
        UNKNOWN
    }

    public enum TrackUtility {
        ON,
        OFF,
        UNKNOWN
    }

    public enum ComputeQueryId {
        AUTO,
        ON,
        OFF,
        UNKNOWN
    }

    public enum NonClaim {
        HISTORICAL_TRACKING_CONFIGURATION_NOT_PROVEN,
        RETAINED_SHAPE_NOT_TEMPORALLY_BOUND_TO_INCIDENT,
        CURRENT_ROLE_VISIBILITY_ONLY,
        NORMALIZED_SET_CONFIG_TARGET_UNOBSERVABLE,
        BACKEND_PID_AND_ACTOR_UNBOUND
    }

    public record CapabilityEvidence(
            boolean extensionInstalled,
            boolean extensionAccessible,
            StatementTrack statementTrack,
            TrackUtility trackUtility,
            ComputeQueryId computeQueryId,
            boolean canReadAllStatementText) {
        public CapabilityEvidence {
            Objects.requireNonNull(statementTrack, "statementTrack");
            Objects.requireNonNull(trackUtility, "trackUtility");
            Objects.requireNonNull(computeQueryId, "computeQueryId");
        }
    }

    public record HistoryEvidence(Instant statsReset, long dealloc, Map<SetterShape, Long> shapeCounts) {
        public HistoryEvidence {
            shapeCounts = validatedShapeCounts(shapeCounts);
        }
    }

    public enum ConfigFailureReason {
        MISSING_REQUIRED,
        APPROVAL_NOT_TRUE,
        UNEXPECTED
    }

    public record LiveConfig(String pooledJdbcUrl, String username, String password) {
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

    private static final String ENV_APPROVED = "SPEC_A_912_HISTORY_PROBE_APPROVED";
    private static final String ENV_POOLED = "SPEC_A_912_POOLED_JDBC_URL";
    private static final String ENV_USERNAME = "SPEC_A_912_DB_USERNAME";
    private static final String ENV_PASSWORD = "SPEC_A_912_DB_PASSWORD";
    private static final List<String> REQUIRED_ENV =
            List.of(ENV_APPROVED, ENV_POOLED, ENV_USERNAME, ENV_PASSWORD);

    /**
     * Capability observation only. Current GUC values do not prove historical tracking
     * configuration.
     */
    static final String CAPABILITY_SQL =
            """
            SELECT
                EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements') AS extension_installed,
                COALESCE(current_setting('pg_stat_statements.track', true), '') AS statement_track,
                COALESCE(current_setting('pg_stat_statements.track_utility', true), '') AS track_utility,
                COALESCE(current_setting('compute_query_id', true), '') AS compute_query_id,
                (
                    (SELECT rolsuper FROM pg_roles WHERE rolname = current_user)
                    OR pg_has_role(current_user, 'pg_read_all_stats', 'usage')
                ) AS can_read_all_statement_text
            """
                    .stripIndent()
                    .trim();

    /**
     * Aggregate retained-shape counts. Blind spots: normalized {@code set_config($1,$2,$3)} cannot
     * safely reveal its target or value; statistics cannot bind a retained statement to backend PID
     * 19916 or an actor; aggregate rows have no per-execution timestamp and cannot bind a retained
     * call to the incident; current tracking GUCs do not prove their historical values; without
     * {@code can_read_all_statement_text}, a zero count covers only statements visible to the
     * current database role. Query text is used only in server-side filter predicates and is never
     * selected, logged, or returned.
     */
    static final String HISTORY_SQL =
            """
            WITH access AS (
                SELECT
                    oid AS current_user_oid,
                    rolsuper OR pg_has_role(current_user, 'pg_read_all_stats', 'usage')
                        AS can_read_all_statement_text
                FROM pg_roles
                WHERE rolname = current_user
            )
            SELECT
                info.stats_reset,
                info.dealloc,
                COALESCE(SUM(stats.calls) FILTER (
                    WHERE (access.can_read_all_statement_text OR stats.userid = access.current_user_oid)
                      AND stats.query ~* '^\\s*set\\s+(session\\s+)?default_transaction_read_only($|[[:space:]=])'
                ), 0)::bigint AS set_default_calls,
                COALESCE(SUM(stats.calls) FILTER (
                    WHERE (access.can_read_all_statement_text OR stats.userid = access.current_user_oid)
                      AND stats.query ~* '^\\s*set\\s+session\\s+characteristics\\s+as\\s+transaction[^;]*read[[:space:]]+only($|[[:space:],;])'
                ), 0)::bigint AS set_session_characteristics_calls,
                COALESCE(SUM(stats.calls) FILTER (
                    WHERE (access.can_read_all_statement_text OR stats.userid = access.current_user_oid)
                      AND stats.query ~* '^\\s*set\\s+transaction[^;]*read[[:space:]]+only($|[[:space:],;])'
                ), 0)::bigint AS set_transaction_calls,
                COALESCE(SUM(stats.calls) FILTER (
                    WHERE (access.can_read_all_statement_text OR stats.userid = access.current_user_oid)
                      AND stats.query ~* '^\\s*reset\\s+default_transaction_read_only($|[[:space:];])'
                ), 0)::bigint AS reset_default_calls,
                COALESCE(SUM(stats.calls) FILTER (
                    WHERE (access.can_read_all_statement_text OR stats.userid = access.current_user_oid)
                      AND stats.query ~* '^\\s*alter\\s+role\\s+.+\\s+set\\s+default_transaction_read_only($|[[:space:]=])'
                ), 0)::bigint AS alter_role_calls,
                COALESCE(SUM(stats.calls) FILTER (
                    WHERE (access.can_read_all_statement_text OR stats.userid = access.current_user_oid)
                      AND stats.query ~* '^\\s*alter\\s+database\\s+.+\\s+set\\s+default_transaction_read_only($|[[:space:]=])'
                ), 0)::bigint AS alter_database_calls,
                COALESCE(SUM(stats.calls) FILTER (
                    WHERE (access.can_read_all_statement_text OR stats.userid = access.current_user_oid)
                      AND stats.query ~* '^\\s*discard\\s+all($|[[:space:];])'
                ), 0)::bigint AS discard_all_calls
            FROM pg_stat_statements_info AS info
            CROSS JOIN access
            LEFT JOIN pg_stat_statements AS stats ON TRUE
            GROUP BY info.stats_reset, info.dealloc
            """
                    .stripIndent()
                    .trim();

    private static final List<String> ALLOW_LISTED_STATEMENTS = List.of(CAPABILITY_SQL, HISTORY_SQL);

    public record Classification(
            Verdict verdict,
            Instant incidentStart,
            Instant statsReset,
            long dealloc,
            boolean coveringIncident,
            boolean extensionInstalled,
            boolean extensionAccessible,
            StatementTrack statementTrack,
            TrackUtility trackUtility,
            ComputeQueryId computeQueryId,
            boolean canReadAllStatementText,
            Map<SetterShape, Long> shapeCounts,
            List<NonClaim> nonClaims) {
        public Classification {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(incidentStart, "incidentStart");
            dealloc = Math.max(0L, dealloc);
            if (!completeNonNegative(shapeCounts)) {
                verdict = Verdict.INCONCLUSIVE;
                shapeCounts = emptyCounts();
            } else {
                shapeCounts = immutableCounts(shapeCounts);
            }
            nonClaims = List.copyOf(nonClaims == null ? List.of() : nonClaims);
        }
    }

    private SpecA912StatementHistoryProbe() {}

    public static List<String> allowListedStatements() {
        return ALLOW_LISTED_STATEMENTS;
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
            return new LiveConfigResult(
                    true, ConfigFailureReason.MISSING_REQUIRED, List.copyOf(missing), null);
        }
        if (!"true".equals(env.get(ENV_APPROVED).trim())) {
            return new LiveConfigResult(true, ConfigFailureReason.APPROVAL_NOT_TRUE, List.of(), null);
        }
        return new LiveConfigResult(
                false,
                null,
                List.of(),
                new LiveConfig(env.get(ENV_POOLED), env.get(ENV_USERNAME), env.get(ENV_PASSWORD)));
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

    public static Classification collect(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        CapabilityEvidence capability;
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(CAPABILITY_SQL)) {
            if (!rs.next()) {
                return classify(unavailableCapability(), null);
            }
            capability = readCapability(rs);
        } catch (Exception ignored) {
            return classify(unavailableCapability(), null);
        }
        if (!capability.extensionInstalled()
                || !capability.extensionAccessible()
                || !trackingRecognized(capability)) {
            return classify(capability, null);
        }
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(HISTORY_SQL)) {
            if (!rs.next()) {
                return classify(capability, null);
            }
            return classify(capability, readHistory(rs));
        } catch (Exception ignored) {
            return classify(
                    new CapabilityEvidence(
                            capability.extensionInstalled(),
                            false,
                            capability.statementTrack(),
                            capability.trackUtility(),
                            capability.computeQueryId(),
                            capability.canReadAllStatementText()),
                    null);
        }
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
        Properties properties = new Properties();
        properties.setProperty("user", configResult.config().username());
        properties.setProperty("password", configResult.config().password());
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "15");
        try (Connection connection =
                DriverManager.getConnection(configResult.config().pooledJdbcUrl(), properties)) {
            Classification classification = collect(connection);
            out.append(format(classification));
            out.append('\n');
            return classification.verdict() == Verdict.INCONCLUSIVE
                            || classification.verdict() == Verdict.STATEMENT_HISTORY_UNAVAILABLE
                    ? 1
                    : 0;
        } catch (Exception ignored) {
            Classification unavailable = classify(unavailableCapability(), null);
            out.append(format(unavailable));
            out.append('\n');
            return 1;
        }
    }

    public static void main(String[] args) {
        try {
            int code = run(System::getenv, System.out);
            System.exit(code);
        } catch (Throwable unexpected) {
            System.out.println(formatConfigurationFailure(List.of(), ConfigFailureReason.UNEXPECTED));
            System.exit(2);
        }
    }

    public static Classification classify(CapabilityEvidence capability, HistoryEvidence history) {
        if (capability == null) {
            return inconclusive(null, history);
        }
        if (!capability.extensionInstalled() || !capability.extensionAccessible()) {
            return result(
                    Verdict.STATEMENT_HISTORY_UNAVAILABLE,
                    capability,
                    history,
                    standardNonClaims(false));
        }
        if (history == null) {
            Verdict disabled = trackingDisabledVerdict(capability);
            if (disabled != null) {
                return result(disabled, capability, null, standardNonClaims(false));
            }
            return inconclusive(capability, null);
        }
        if (history.dealloc() < 0 || !completeNonNegative(history.shapeCounts())) {
            return inconclusive(capability, history);
        }
        if (anyPositive(history.shapeCounts())) {
            if (history.statsReset() == null) {
                return inconclusive(capability, history);
            }
            Verdict verdict =
                    covering(history.statsReset())
                            ? Verdict.SETTER_SHAPE_PRESENT_IN_COVERING_STATS
                            : Verdict.SETTER_SHAPE_PRESENT_OUTSIDE_INCIDENT_COVERAGE;
            return result(verdict, capability, history, standardNonClaims(false));
        }
        Verdict trackingVerdict = trackingDisabledVerdict(capability);
        if (trackingVerdict != null) {
            return result(trackingVerdict, capability, history, standardNonClaims(false));
        }
        if (history.dealloc() > 0) {
            return result(
                    Verdict.STATS_EVICTION_PREVENTS_ABSENCE_CLAIM,
                    capability,
                    history,
                    standardNonClaims(false));
        }
        if (history.statsReset() == null) {
            return inconclusive(capability, history);
        }
        if (!covering(history.statsReset())) {
            return result(
                    Verdict.STATS_WINDOW_NOT_COVERING_INCIDENT,
                    capability,
                    history,
                    standardNonClaims(false));
        }
        boolean visible = capability.canReadAllStatementText();
        Verdict verdict =
                visible
                        ? Verdict.NO_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS
                        : Verdict.NO_CURRENT_ROLE_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS;
        return result(verdict, capability, history, standardNonClaims(!visible));
    }

    public static Classification classifyFromEntries(
            CapabilityEvidence capability,
            Instant statsReset,
            long dealloc,
            List<Map.Entry<SetterShape, Long>> entries) {
        if (entries == null) {
            return inconclusive(capability, null);
        }
        EnumSet<SetterShape> seen = EnumSet.noneOf(SetterShape.class);
        EnumMap<SetterShape, Long> counts = new EnumMap<>(SetterShape.class);
        for (SetterShape shape : SetterShape.values()) {
            counts.put(shape, 0L);
        }
        for (Map.Entry<SetterShape, Long> entry : entries) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                return inconclusive(capability, null);
            }
            if (!seen.add(entry.getKey())) {
                return inconclusive(capability, null);
            }
            counts.put(entry.getKey(), entry.getValue());
        }
        if (seen.size() != SetterShape.values().length) {
            return inconclusive(capability, null);
        }
        return classify(capability, new HistoryEvidence(statsReset, dealloc, counts));
    }

    public static String format(Classification classification) {
        Objects.requireNonNull(classification, "classification");
        StringBuilder out = new StringBuilder();
        out.append('{');
        appendQuoted(out, "incidentStart", classification.incidentStart().toString(), true);
        appendQuoted(
                out,
                "statsReset",
                classification.statsReset() == null ? null : classification.statsReset().toString(),
                true);
        appendRaw(out, "coveringIncident", Boolean.toString(classification.coveringIncident()));
        appendRaw(out, "dealloc", Long.toString(classification.dealloc()));
        appendRaw(out, "extensionInstalled", Boolean.toString(classification.extensionInstalled()));
        appendRaw(out, "extensionAccessible", Boolean.toString(classification.extensionAccessible()));
        appendQuoted(out, "statementTrack", name(classification.statementTrack()), true);
        appendQuoted(out, "trackUtility", name(classification.trackUtility()), true);
        appendQuoted(out, "computeQueryId", name(classification.computeQueryId()), true);
        appendRaw(
                out,
                "canReadAllStatementText",
                Boolean.toString(classification.canReadAllStatementText()));
        Map<SetterShape, Long> counts = outputShapeCounts(classification.shapeCounts());
        out.append(",\"shapeCounts\":{");
        boolean first = true;
        for (SetterShape shape : SetterShape.values()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(shape.name()).append("\":").append(counts.get(shape));
        }
        out.append('}');
        appendQuoted(out, "verdict", classification.verdict().name(), true);
        out.append(",\"nonClaims\":[");
        List<NonClaim> nonClaims = classification.nonClaims();
        for (int i = 0; i < nonClaims.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(nonClaims.get(i).name()).append('"');
        }
        out.append("]}");
        return out.toString();
    }

    private static Classification result(
            Verdict verdict,
            CapabilityEvidence capability,
            HistoryEvidence history,
            List<NonClaim> nonClaims) {
        Map<SetterShape, Long> counts =
                history == null ? emptyCounts() : outputShapeCounts(history.shapeCounts());
        Instant statsReset = history == null ? null : history.statsReset();
        long dealloc = history == null ? 0L : Math.max(0L, history.dealloc());
        return new Classification(
                verdict,
                INCIDENT_START,
                statsReset,
                dealloc,
                statsReset != null && covering(statsReset),
                capability.extensionInstalled(),
                capability.extensionAccessible(),
                capability.statementTrack(),
                capability.trackUtility(),
                capability.computeQueryId(),
                capability.canReadAllStatementText(),
                counts,
                nonClaims);
    }

    private static Classification inconclusive(CapabilityEvidence capability, HistoryEvidence history) {
        if (capability == null) {
            return new Classification(
                    Verdict.INCONCLUSIVE,
                    INCIDENT_START,
                    history == null ? null : history.statsReset(),
                    history == null ? 0L : Math.max(0L, history.dealloc()),
                    false,
                    false,
                    false,
                    StatementTrack.UNKNOWN,
                    TrackUtility.UNKNOWN,
                    ComputeQueryId.UNKNOWN,
                    false,
                    history == null ? emptyCounts() : outputShapeCounts(history.shapeCounts()),
                    List.of());
        }
        return result(Verdict.INCONCLUSIVE, capability, history, List.of());
    }

    private static boolean covering(Instant statsReset) {
        return !statsReset.isAfter(INCIDENT_START);
    }

    private static boolean trackingRecognized(CapabilityEvidence capability) {
        return capability.statementTrack() != StatementTrack.UNKNOWN
                && capability.trackUtility() != TrackUtility.UNKNOWN
                && capability.computeQueryId() != ComputeQueryId.UNKNOWN;
    }

    private static Verdict trackingDisabledVerdict(CapabilityEvidence capability) {
        if (capability.trackUtility() == TrackUtility.OFF
                || capability.trackUtility() == TrackUtility.UNKNOWN) {
            return Verdict.UTILITY_TRACKING_DISABLED;
        }
        if (capability.statementTrack() == StatementTrack.NONE
                || capability.statementTrack() == StatementTrack.UNKNOWN) {
            return Verdict.STATEMENT_TRACKING_DISABLED;
        }
        if (capability.computeQueryId() == ComputeQueryId.OFF
                || capability.computeQueryId() == ComputeQueryId.UNKNOWN) {
            return Verdict.QUERY_ID_CALCULATION_DISABLED;
        }
        return null;
    }

    private static boolean anyPositive(Map<SetterShape, Long> counts) {
        for (Long value : counts.values()) {
            if (value != null && value > 0L) {
                return true;
            }
        }
        return false;
    }

    private static boolean completeNonNegative(Map<SetterShape, Long> counts) {
        if (counts == null || counts.size() != SetterShape.values().length) {
            return false;
        }
        for (SetterShape shape : SetterShape.values()) {
            Long value = counts.get(shape);
            if (value == null || value < 0L) {
                return false;
            }
        }
        return true;
    }

    private static List<NonClaim> standardNonClaims(boolean currentRoleOnly) {
        List<NonClaim> nonClaims = new ArrayList<>();
        nonClaims.add(NonClaim.RETAINED_SHAPE_NOT_TEMPORALLY_BOUND_TO_INCIDENT);
        nonClaims.add(NonClaim.HISTORICAL_TRACKING_CONFIGURATION_NOT_PROVEN);
        nonClaims.add(NonClaim.NORMALIZED_SET_CONFIG_TARGET_UNOBSERVABLE);
        nonClaims.add(NonClaim.BACKEND_PID_AND_ACTOR_UNBOUND);
        if (currentRoleOnly) {
            nonClaims.add(NonClaim.CURRENT_ROLE_VISIBILITY_ONLY);
        }
        return List.copyOf(nonClaims);
    }

    private static Map<SetterShape, Long> emptyCounts() {
        EnumMap<SetterShape, Long> counts = new EnumMap<>(SetterShape.class);
        for (SetterShape shape : SetterShape.values()) {
            counts.put(shape, 0L);
        }
        return Collections.unmodifiableMap(counts);
    }

    private static Map<SetterShape, Long> validatedShapeCounts(Map<SetterShape, Long> shapeCounts) {
        if (shapeCounts == null || shapeCounts.size() != SetterShape.values().length) {
            return Map.of();
        }
        for (Map.Entry<SetterShape, Long> entry : shapeCounts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                return Map.of();
            }
        }
        EnumMap<SetterShape, Long> copy = new EnumMap<>(SetterShape.class);
        for (SetterShape shape : SetterShape.values()) {
            Long value = shapeCounts.get(shape);
            if (value == null || value < 0L) {
                return Map.of();
            }
            copy.put(shape, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<SetterShape, Long> outputShapeCounts(Map<SetterShape, Long> shapeCounts) {
        return completeNonNegative(shapeCounts) ? immutableCounts(shapeCounts) : emptyCounts();
    }

    private static Map<SetterShape, Long> immutableCounts(Map<SetterShape, Long> shapeCounts) {
        EnumMap<SetterShape, Long> copy = new EnumMap<>(SetterShape.class);
        for (SetterShape shape : SetterShape.values()) {
            copy.put(shape, shapeCounts.get(shape));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String name(Enum<?> value) {
        return value == null ? "UNKNOWN" : value.name();
    }

    private static void appendQuoted(StringBuilder out, String key, String value, boolean quote) {
        if (out.length() > 1) {
            out.append(',');
        }
        out.append('"').append(key).append("\":");
        if (value == null) {
            out.append("null");
            return;
        }
        if (quote) {
            out.append('"').append(value).append('"');
        } else {
            out.append(value);
        }
    }

    private static void appendRaw(StringBuilder out, String key, String value) {
        if (out.length() > 1) {
            out.append(',');
        }
        out.append('"').append(key).append("\":").append(value);
    }

    private static CapabilityEvidence unavailableCapability() {
        return new CapabilityEvidence(
                false,
                false,
                StatementTrack.UNKNOWN,
                TrackUtility.UNKNOWN,
                ComputeQueryId.UNKNOWN,
                false);
    }

    private static CapabilityEvidence readCapability(ResultSet rs) throws SQLException {
        return new CapabilityEvidence(
                rs.getBoolean("extension_installed"),
                true,
                parseStatementTrack(rs.getString("statement_track")),
                parseTrackUtility(rs.getString("track_utility")),
                parseComputeQueryId(rs.getString("compute_query_id")),
                rs.getBoolean("can_read_all_statement_text"));
    }

    private static HistoryEvidence readHistory(ResultSet rs) throws SQLException {
        Timestamp reset = rs.getTimestamp("stats_reset");
        Instant statsReset = reset == null ? null : reset.toInstant();
        EnumMap<SetterShape, Long> counts = new EnumMap<>(SetterShape.class);
        counts.put(SetterShape.SET_DEFAULT_TRANSACTION_READ_ONLY, rs.getLong("set_default_calls"));
        counts.put(
                SetterShape.SET_SESSION_CHARACTERISTICS_READ_ONLY,
                rs.getLong("set_session_characteristics_calls"));
        counts.put(SetterShape.SET_TRANSACTION_READ_ONLY, rs.getLong("set_transaction_calls"));
        counts.put(
                SetterShape.RESET_DEFAULT_TRANSACTION_READ_ONLY, rs.getLong("reset_default_calls"));
        counts.put(
                SetterShape.ALTER_ROLE_DEFAULT_TRANSACTION_READ_ONLY, rs.getLong("alter_role_calls"));
        counts.put(
                SetterShape.ALTER_DATABASE_DEFAULT_TRANSACTION_READ_ONLY,
                rs.getLong("alter_database_calls"));
        counts.put(SetterShape.DISCARD_ALL, rs.getLong("discard_all_calls"));
        return new HistoryEvidence(statsReset, rs.getLong("dealloc"), counts);
    }

    static StatementTrack parseStatementTrack(String raw) {
        if (raw == null || raw.isBlank()) {
            return StatementTrack.UNKNOWN;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "top" -> StatementTrack.TOP;
            case "all" -> StatementTrack.ALL;
            case "none" -> StatementTrack.NONE;
            default -> StatementTrack.UNKNOWN;
        };
    }

    static TrackUtility parseTrackUtility(String raw) {
        if (raw == null || raw.isBlank()) {
            return TrackUtility.UNKNOWN;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "on" -> TrackUtility.ON;
            case "off" -> TrackUtility.OFF;
            default -> TrackUtility.UNKNOWN;
        };
    }

    static ComputeQueryId parseComputeQueryId(String raw) {
        if (raw == null || raw.isBlank()) {
            return ComputeQueryId.UNKNOWN;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> ComputeQueryId.AUTO;
            case "on" -> ComputeQueryId.ON;
            case "off" -> ComputeQueryId.OFF;
            default -> ComputeQueryId.UNKNOWN;
        };
    }
}
