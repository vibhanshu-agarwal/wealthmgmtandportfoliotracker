package com.wealth.portfolio.seed.rca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wealth.portfolio.seed.rca.SpecA912ConnectionOriginProbe.AttemptEvidence;
import com.wealth.portfolio.seed.rca.SpecA912ConnectionOriginProbe.CatalogDefault;
import com.wealth.portfolio.seed.rca.SpecA912ConnectionOriginProbe.CatalogDefaultScope;
import com.wealth.portfolio.seed.rca.SpecA912ConnectionOriginProbe.EndpointLabel;
import com.wealth.portfolio.seed.rca.SpecA912ConnectionOriginProbe.ProbeMatrix;
import com.wealth.portfolio.seed.rca.SpecA912ConnectionOriginProbe.SettingSource;
import com.wealth.portfolio.seed.rca.SpecA912ConnectionOriginProbe.Verdict;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SpecA912ConnectionOriginProbeTest {

    @ParameterizedTest
    @CsvSource({
        "default, DEFAULT",
        "user, USER",
        "database, DATABASE",
        "database user, DATABASE_USER",
        "client, CLIENT",
        "override, OVERRIDE",
        "configuration file, CONFIGURATION_FILE",
        "command line, COMMAND_LINE",
        "environment variable, ENVIRONMENT_VARIABLE",
        "global, GLOBAL",
        "interactive, INTERACTIVE",
        "test, TEST",
        "session, SESSION"
    })
    void parseSettingSourceAcceptsKnownPostgresqlValues(String raw, SettingSource expected) {
        assertThat(SpecA912ConnectionOriginProbe.parseSettingSource(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "neon", "ROOT_CAUSE", "unknown-source", "User"})
    void parseSettingSourceMapsUnknownToUnknownWithoutEchoing(String raw) {
        assertThat(SpecA912ConnectionOriginProbe.parseSettingSource(raw))
                .isEqualTo(SettingSource.UNKNOWN);
    }

    @ParameterizedTest
    @CsvSource({"on, on", "OFF, off", "On, on", "oFf, off"})
    void normalizeOnOffAcceptsCaseInsensitiveOnOffOnly(String raw, String expected) {
        assertThat(SpecA912ConnectionOriginProbe.normalizeOnOff(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "true", "1", "yes", "read only"})
    void normalizeOnOffRejectsNonOnOff(String raw) {
        assertThatThrownBy(() -> SpecA912ConnectionOriginProbe.normalizeOnOff(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("on/off required");
    }

    @Test
    void matrixRequiresExactlyFiveAttemptsPerEndpointInDeterministicOrder() {
        List<AttemptEvidence> attempts = new ArrayList<>();
        for (EndpointLabel endpoint : List.of(EndpointLabel.POOLED, EndpointLabel.DIRECT)) {
            for (int i = 1; i <= 5; i++) {
                attempts.add(offAttempt(endpoint, i, 1000L + i));
            }
        }
        ProbeMatrix matrix = SpecA912ConnectionOriginProbe.requireCompleteMatrix(attempts);
        assertThat(matrix.attempts()).hasSize(10);
        assertThat(matrix.attempts().get(0).endpoint()).isEqualTo(EndpointLabel.POOLED);
        assertThat(matrix.attempts().get(0).attemptNumber()).isEqualTo(1);
        assertThat(matrix.attempts().get(4).attemptNumber()).isEqualTo(5);
        assertThat(matrix.attempts().get(5).endpoint()).isEqualTo(EndpointLabel.DIRECT);
        assertThat(matrix.attempts().get(9).attemptNumber()).isEqualTo(5);
    }

    @Test
    void allOffOnBothPathsClassifiesNotReproduced() {
        assertThat(SpecA912ConnectionOriginProbe.classify(completeMatrix(false, false)))
                .isEqualTo(Verdict.NOT_REPRODUCED_IN_MANUAL_MATRIX);
    }

    @Test
    void directOffPooledAllOnClassifiesPooledPathDivergence() {
        assertThat(SpecA912ConnectionOriginProbe.classify(completeMatrix(true, false)))
                .isEqualTo(Verdict.POOLED_PATH_DIVERGENCE_PROVEN);
    }

    @ParameterizedTest
    @CsvSource({"DATABASE, database", "USER, user", "DATABASE_USER, database user"})
    void bothAllOnWithPersistentSourceClassifiesPersistentDefault(
            SettingSource source, String ignoredRaw) {
        ProbeMatrix matrix =
                completeMatrixWithSource(true, true, source, source);
        assertThat(SpecA912ConnectionOriginProbe.classify(matrix))
                .isEqualTo(Verdict.PERSISTENT_DEFAULT_PATH_EVIDENCED);
        assertThat(SpecA912ConnectionOriginProbe.persistentSource(matrix)).isEqualTo(source);
    }

    @Test
    void bothAllOnWithClientSourceClassifiesClientStartup() {
        ProbeMatrix matrix =
                completeMatrixWithSource(true, true, SettingSource.CLIENT, SettingSource.CLIENT);
        assertThat(SpecA912ConnectionOriginProbe.classify(matrix))
                .isEqualTo(Verdict.CLIENT_STARTUP_DEFAULT_OBSERVED);
    }

    @Test
    void mixedOnOffWithinEndpointIsInconclusive() {
        List<AttemptEvidence> attempts = new ArrayList<>();
        attempts.add(onAttempt(EndpointLabel.POOLED, 1, 1L, SettingSource.DEFAULT));
        for (int i = 2; i <= 5; i++) {
            attempts.add(offAttempt(EndpointLabel.POOLED, i, i));
        }
        for (int i = 1; i <= 5; i++) {
            attempts.add(offAttempt(EndpointLabel.DIRECT, i, 100L + i));
        }
        assertThat(SpecA912ConnectionOriginProbe.classify(
                        SpecA912ConnectionOriginProbe.requireCompleteMatrix(attempts)))
                .isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void failedAttemptClassifiesInconclusive() {
        List<AttemptEvidence> attempts = new ArrayList<>();
        attempts.add(failedAttempt(EndpointLabel.POOLED, 1, "SQLException", "08001"));
        for (int i = 2; i <= 5; i++) {
            attempts.add(offAttempt(EndpointLabel.POOLED, i, i));
        }
        for (int i = 1; i <= 5; i++) {
            attempts.add(offAttempt(EndpointLabel.DIRECT, i, 100L + i));
        }
        assertThat(SpecA912ConnectionOriginProbe.classify(
                        SpecA912ConnectionOriginProbe.requireCompleteMatrix(attempts)))
                .isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void unknownSourceClassifiesInconclusive() {
        ProbeMatrix matrix =
                completeMatrixWithSource(true, true, SettingSource.UNKNOWN, SettingSource.UNKNOWN);
        assertThat(SpecA912ConnectionOriginProbe.classify(matrix)).isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void incompleteEndpointClassifiesInconclusiveWithoutRequireComplete() {
        List<AttemptEvidence> attempts = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            attempts.add(offAttempt(EndpointLabel.POOLED, i, i));
        }
        assertThat(SpecA912ConnectionOriginProbe.classifyIncomplete(attempts))
                .isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void directOnWhilePooledOffIsInconclusive() {
        assertThat(SpecA912ConnectionOriginProbe.classify(completeMatrix(false, true)))
                .isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void formatterEmitsOnlyAllowListedKeysAndExcludesCanaries() {
        List<AttemptEvidence> attempts = new ArrayList<>();
        attempts.add(
                new AttemptEvidence(
                        EndpointLabel.POOLED,
                        1,
                        42L,
                        false,
                        true,
                        "on",
                        "on",
                        false,
                        "on",
                        "on",
                        SettingSource.USER,
                        List.of(new CatalogDefault(CatalogDefaultScope.ROLE, "on")),
                        null,
                        null));
        for (int i = 2; i <= 5; i++) {
            attempts.add(onAttempt(EndpointLabel.POOLED, i, i, SettingSource.USER));
        }
        for (int i = 1; i <= 5; i++) {
            attempts.add(onAttempt(EndpointLabel.DIRECT, i, 100L + i, SettingSource.USER));
        }
        ProbeMatrix matrix = SpecA912ConnectionOriginProbe.requireCompleteMatrix(attempts);
        String output =
                SpecA912ConnectionOriginProbe.format(
                        matrix, Verdict.PERSISTENT_DEFAULT_PATH_EVIDENCED, SettingSource.USER);

        assertThat(output)
                .contains("endpoint")
                .contains("attempt")
                .contains("backendPid")
                .contains("jdbcReadOnly")
                .contains("autoCommit")
                .contains("defaultTransactionReadOnly")
                .contains("transactionReadOnly")
                .contains("pgIsInRecovery")
                .contains("setting")
                .contains("resetVal")
                .contains("source")
                .contains("catalogDefaults")
                .contains("verdict")
                .contains("PERSISTENT_DEFAULT_PATH_EVIDENCED")
                .contains("persistentSource")
                .doesNotContain("jdbc:postgresql")
                .doesNotContain("neon.tech")
                .doesNotContain("secret-host.example")
                .doesNotContain("portfolio_db_canary")
                .doesNotContain("wealth_user_canary")
                .doesNotContain("p@ssw0rd-canary")
                .doesNotContain("SHOW default_transaction_read_only")
                .doesNotContain("super secret exception");
    }

    @Test
    void configurationFailureListsOnlyMissingEnvironmentVariableNames() {
        String output =
                SpecA912ConnectionOriginProbe.formatConfigurationFailure(
                        List.of(
                                "SPEC_A_912_LIVE_PROBE_APPROVED",
                                "SPEC_A_912_POOLED_JDBC_URL",
                                "SPEC_A_912_DIRECT_JDBC_URL",
                                "SPEC_A_912_DB_USERNAME",
                                "SPEC_A_912_DB_PASSWORD"),
                        SpecA912ConnectionOriginProbe.ConfigFailureReason.MISSING_REQUIRED);

        assertThat(output)
                .contains("SPEC_A_912_LIVE_PROBE_APPROVED")
                .contains("SPEC_A_912_POOLED_JDBC_URL")
                .contains("SPEC_A_912_DIRECT_JDBC_URL")
                .contains("SPEC_A_912_DB_USERNAME")
                .contains("SPEC_A_912_DB_PASSWORD")
                .contains("MISSING_REQUIRED")
                .doesNotContain("jdbc:")
                .doesNotContain("password=");
    }

    @Test
    void resultOrderIsStableAndOmitsObjectIdentity() {
        ProbeMatrix matrix = completeMatrix(false, false);
        String first =
                SpecA912ConnectionOriginProbe.format(
                        matrix, Verdict.NOT_REPRODUCED_IN_MANUAL_MATRIX, null);
        String second =
                SpecA912ConnectionOriginProbe.format(
                        matrix, Verdict.NOT_REPRODUCED_IN_MANUAL_MATRIX, null);
        assertThat(first).isEqualTo(second);
        assertThat(first).doesNotContain("@").doesNotContain("Exception");
    }

    @Test
    void configurationRequiresAllFiveEnvironmentVariablesAndExactApproval() {
        Map<String, String> env = new HashMap<>();
        assertThat(SpecA912ConnectionOriginProbe.resolveLiveConfig(env).failed()).isTrue();
        assertThat(SpecA912ConnectionOriginProbe.resolveLiveConfig(env).reason())
                .isEqualTo(SpecA912ConnectionOriginProbe.ConfigFailureReason.MISSING_REQUIRED);

        env.put("SPEC_A_912_LIVE_PROBE_APPROVED", "TRUE");
        env.put("SPEC_A_912_POOLED_JDBC_URL", "jdbc:a");
        env.put("SPEC_A_912_DIRECT_JDBC_URL", "jdbc:b");
        env.put("SPEC_A_912_DB_USERNAME", "u");
        env.put("SPEC_A_912_DB_PASSWORD", "p");
        assertThat(SpecA912ConnectionOriginProbe.resolveLiveConfig(env).reason())
                .isEqualTo(SpecA912ConnectionOriginProbe.ConfigFailureReason.APPROVAL_NOT_TRUE);

        env.put("SPEC_A_912_LIVE_PROBE_APPROVED", " true ");
        env.put("SPEC_A_912_DIRECT_JDBC_URL", "jdbc:a");
        assertThat(SpecA912ConnectionOriginProbe.resolveLiveConfig(env).reason())
                .isEqualTo(SpecA912ConnectionOriginProbe.ConfigFailureReason.DUPLICATE_ENDPOINT_URLS);

        env.put("SPEC_A_912_DIRECT_JDBC_URL", "jdbc:b");
        assertThat(SpecA912ConnectionOriginProbe.resolveLiveConfig(env).failed()).isFalse();
    }

    @Test
    void configurationFailureExposesOnlyFixedVariableNamesAndReasonEnums() {
        SpecA912ConnectionOriginProbe.LiveConfigResult result =
                SpecA912ConnectionOriginProbe.resolveLiveConfig(Map.of());
        String output =
                SpecA912ConnectionOriginProbe.formatConfigurationFailure(
                        result.missingVariableNames(), result.reason());
        assertThat(output)
                .contains("MISSING_REQUIRED")
                .contains("SPEC_A_912_LIVE_PROBE_APPROVED")
                .doesNotContain("jdbc:")
                .doesNotContain("=");
    }

    @Test
    void sessionSourceIsPreservedAndClassifiesInconclusiveWhenBothAllOn() {
        ProbeMatrix matrix =
                completeMatrixWithSource(true, true, SettingSource.SESSION, SettingSource.SESSION);
        assertThat(SpecA912ConnectionOriginProbe.classify(matrix)).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(matrix.attempts().get(0).source()).isEqualTo(SettingSource.SESSION);
        String formatted =
                SpecA912ConnectionOriginProbe.format(matrix, Verdict.INCONCLUSIVE, null);
        assertThat(formatted).contains("SESSION").doesNotContain("UNKNOWN");
    }

    @Test
    void allowListedStatementsAreExactAndNonMutating() {
        List<String> statements = SpecA912ConnectionOriginProbe.allowListedStatements();
        assertThat(statements).containsExactly(SpecA912ConnectionOriginProbe.COMPOSITE_SNAPSHOT_SQL);
        assertThat(SpecA912ConnectionOriginProbe.DATABASE_STATEMENTS_PER_ATTEMPT).isEqualTo(1);
        assertThat(statements).hasSize(1);
        for (String statement : statements) {
            String normalized = statement.replaceAll("(?s)/\\*.*?\\*/|--.*?(\\r?\\n|$)", " ").trim();
            assertThat(normalized.toUpperCase(Locale.ROOT))
                    .doesNotStartWith("SET ")
                    .doesNotStartWith("RESET ")
                    .doesNotStartWith("DISCARD ")
                    .doesNotStartWith("BEGIN ")
                    .doesNotStartWith("INSERT ")
                    .doesNotStartWith("UPDATE ")
                    .doesNotStartWith("DELETE ")
                    .doesNotStartWith("ALTER ")
                    .doesNotStartWith("SHOW ");
            assertThat(normalized.toUpperCase(Locale.ROOT))
                    .doesNotContain(" INSERT ")
                    .doesNotContain(" UPDATE ")
                    .doesNotContain(" DELETE ")
                    .doesNotContain(" ALTER ");
            assertThat(normalized.toUpperCase(Locale.ROOT).split("SELECT").length - 1)
                    .as("outer and nested SELECTs may exist, but only one allow-listed statement")
                    .isGreaterThanOrEqualTo(1);
        }
        assertThat(SpecA912ConnectionOriginProbe.ATTEMPTS_PER_ENDPOINT).isEqualTo(5);
    }

    @Test
    void structuralInvariantIsExactlyOneDatabaseStatementPerAttempt() {
        assertThat(SpecA912ConnectionOriginProbe.DATABASE_STATEMENTS_PER_ATTEMPT).isEqualTo(1);
        assertThat(SpecA912ConnectionOriginProbe.allowListedStatements()).hasSize(1);
        String sql = SpecA912ConnectionOriginProbe.allowListedStatements().get(0);
        assertThat(sql)
                .contains("pg_backend_pid()")
                .contains("pg_is_in_recovery()")
                .contains("current_setting('default_transaction_read_only')")
                .contains("current_setting('transaction_read_only')")
                .contains("pg_settings")
                .contains("pg_db_role_setting")
                .doesNotContain("SHOW ");
    }

    @Test
    void liveConfigAndResultToStringRedactSecrets() {
        SpecA912ConnectionOriginProbe.LiveConfig config =
                new SpecA912ConnectionOriginProbe.LiveConfig(
                        "jdbc:postgresql://secret-host.example/portfolio_db_canary",
                        "jdbc:postgresql://direct-host.example/portfolio_db_canary",
                        "wealth_user_canary",
                        "p@ssw0rd-canary");
        SpecA912ConnectionOriginProbe.LiveConfigResult success =
                new SpecA912ConnectionOriginProbe.LiveConfigResult(
                        false, null, List.of(), config);
        SpecA912ConnectionOriginProbe.LiveConfigResult failure =
                new SpecA912ConnectionOriginProbe.LiveConfigResult(
                        true,
                        SpecA912ConnectionOriginProbe.ConfigFailureReason.MISSING_REQUIRED,
                        List.of("SPEC_A_912_DB_PASSWORD"),
                        null);

        assertThat(config.toString())
                .doesNotContain("secret-host")
                .doesNotContain("direct-host")
                .doesNotContain("portfolio_db_canary")
                .doesNotContain("wealth_user_canary")
                .doesNotContain("p@ssw0rd-canary")
                .doesNotContain("jdbc:");
        assertThat(success.toString())
                .doesNotContain("secret-host")
                .doesNotContain("wealth_user_canary")
                .doesNotContain("p@ssw0rd-canary")
                .doesNotContain("jdbc:");
        assertThat(failure.toString())
                .contains("MISSING_REQUIRED")
                .contains("SPEC_A_912_DB_PASSWORD")
                .doesNotContain("p@ssw0rd");
    }

    @Test
    void collectorRejectsSameStringEndpointUrls() {
        Map<String, String> env = new HashMap<>();
        env.put("SPEC_A_912_LIVE_PROBE_APPROVED", "true");
        env.put("SPEC_A_912_POOLED_JDBC_URL", "jdbc:same");
        env.put("SPEC_A_912_DIRECT_JDBC_URL", "jdbc:same");
        env.put("SPEC_A_912_DB_USERNAME", "u");
        env.put("SPEC_A_912_DB_PASSWORD", "p");
        assertThat(SpecA912ConnectionOriginProbe.resolveLiveConfig(env).reason())
                .isEqualTo(SpecA912ConnectionOriginProbe.ConfigFailureReason.DUPLICATE_ENDPOINT_URLS);
    }

    @Test
    void errorFormattingIgnoresExceptionMessagesAndNestedCauses() {
        SQLException nested = new SQLException("nested secret host.example", "08001");
        SQLException outer = new SQLException("outer secret jdbc:postgresql://x", "08006", nested);
        AttemptEvidence evidence =
                SpecA912ConnectionOriginProbe.sanitizeFailure(
                        EndpointLabel.POOLED, 1, outer);
        assertThat(evidence.exceptionClass()).isEqualTo("SQLException");
        assertThat(evidence.sqlState()).isEqualTo("08006");
        String formatted =
                SpecA912ConnectionOriginProbe.format(
                        SpecA912ConnectionOriginProbe.requireCompleteMatrix(
                                padFailures(evidence)),
                        Verdict.INCONCLUSIVE,
                        null);
        assertThat(formatted)
                .doesNotContain("secret")
                .doesNotContain("host.example")
                .doesNotContain("jdbc:postgresql")
                .contains("SQLException")
                .contains("08006");
    }

    private static List<AttemptEvidence> padFailures(AttemptEvidence first) {
        List<AttemptEvidence> attempts = new ArrayList<>();
        attempts.add(first);
        for (int i = 2; i <= 5; i++) {
            attempts.add(offAttempt(EndpointLabel.POOLED, i, i));
        }
        for (int i = 1; i <= 5; i++) {
            attempts.add(offAttempt(EndpointLabel.DIRECT, i, 100L + i));
        }
        return attempts;
    }

    private static ProbeMatrix completeMatrix(boolean pooledOn, boolean directOn) {
        return completeMatrixWithSource(
                pooledOn,
                directOn,
                pooledOn ? SettingSource.DEFAULT : SettingSource.DEFAULT,
                directOn ? SettingSource.DEFAULT : SettingSource.DEFAULT);
    }

    private static ProbeMatrix completeMatrixWithSource(
            boolean pooledOn,
            boolean directOn,
            SettingSource pooledSource,
            SettingSource directSource) {
        List<AttemptEvidence> attempts = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            attempts.add(
                    pooledOn
                            ? onAttempt(EndpointLabel.POOLED, i, i, pooledSource)
                            : offAttempt(EndpointLabel.POOLED, i, i));
        }
        for (int i = 1; i <= 5; i++) {
            attempts.add(
                    directOn
                            ? onAttempt(EndpointLabel.DIRECT, i, 100L + i, directSource)
                            : offAttempt(EndpointLabel.DIRECT, i, 100L + i));
        }
        return SpecA912ConnectionOriginProbe.requireCompleteMatrix(attempts);
    }

    private static AttemptEvidence offAttempt(EndpointLabel endpoint, int attempt, long pid) {
        return new AttemptEvidence(
                endpoint,
                attempt,
                pid,
                false,
                true,
                "off",
                "off",
                false,
                "off",
                "off",
                SettingSource.DEFAULT,
                List.of(),
                null,
                null);
    }

    private static AttemptEvidence onAttempt(
            EndpointLabel endpoint, int attempt, long pid, SettingSource source) {
        return new AttemptEvidence(
                endpoint,
                attempt,
                pid,
                false,
                true,
                "on",
                "on",
                false,
                "on",
                "on",
                source,
                List.of(),
                null,
                null);
    }

    private static AttemptEvidence failedAttempt(
            EndpointLabel endpoint, int attempt, String exceptionClass, String sqlState) {
        return new AttemptEvidence(
                endpoint,
                attempt,
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
                exceptionClass,
                sqlState);
    }
}
