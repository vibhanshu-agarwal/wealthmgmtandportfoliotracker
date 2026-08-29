package com.wealth.portfolio.seed.rca;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.mockito.Mockito;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class SpecA912StatementHistoryProbeTest {

    private static final Instant INCIDENT = Instant.parse("2026-08-28T07:08:59Z");
    private static final Instant COVERING_RESET = Instant.parse("2026-08-27T00:00:00Z");
    private static final Instant OUTSIDE_RESET = Instant.parse("2026-08-29T00:00:00Z");

    private static final String[] FORBIDDEN_CLAIM_TOKENS = {
        "ROOT_CAUSE",
        "ACTOR_IDENTIFIED",
        "SETTER_PROVEN_FOR_PID",
        "NEON_PROVEN",
        "FIX_AUTHORIZED"
    };

    @Test
    void incidentStartIsTheFixedEnableRunCreationInstant() {
        assertThat(SpecA912StatementHistoryProbe.INCIDENT_START).isEqualTo(INCIDENT);
    }

    @Test
    void setterShapeAndVerdictEnumsMatchTheAuthorizedNames() {
        assertThat(SetterShape.values())
                .extracting(Enum::name)
                .containsExactly(
                        "SET_DEFAULT_TRANSACTION_READ_ONLY",
                        "SET_SESSION_CHARACTERISTICS_READ_ONLY",
                        "SET_TRANSACTION_READ_ONLY",
                        "RESET_DEFAULT_TRANSACTION_READ_ONLY",
                        "ALTER_ROLE_DEFAULT_TRANSACTION_READ_ONLY",
                        "ALTER_DATABASE_DEFAULT_TRANSACTION_READ_ONLY",
                        "DISCARD_ALL");
        assertThat(Verdict.values())
                .extracting(Enum::name)
                .containsExactly(
                        "SETTER_SHAPE_PRESENT_IN_COVERING_STATS",
                        "SETTER_SHAPE_PRESENT_OUTSIDE_INCIDENT_COVERAGE",
                        "NO_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS",
                        "NO_CURRENT_ROLE_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS",
                        "STATS_WINDOW_NOT_COVERING_INCIDENT",
                        "STATS_EVICTION_PREVENTS_ABSENCE_CLAIM",
                        "UTILITY_TRACKING_DISABLED",
                        "STATEMENT_TRACKING_DISABLED",
                        "QUERY_ID_CALCULATION_DISABLED",
                        "STATEMENT_HISTORY_UNAVAILABLE",
                        "INCONCLUSIVE");
    }

    @Test
    void enumsAndMessagesNeverClaimRootCauseActorOrRemedy() {
        String names =
                Arrays.stream(SetterShape.values()).map(Enum::name).collect(Collectors.joining())
                        + Arrays.stream(Verdict.values()).map(Enum::name).collect(Collectors.joining())
                        + Arrays.stream(NonClaim.values()).map(Enum::name).collect(Collectors.joining());
        for (String token : FORBIDDEN_CLAIM_TOKENS) {
            assertThat(names).doesNotContain(token);
        }
    }

    @Test
    void extensionUnavailableClassifiesStatementHistoryUnavailable() {
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        capability(false, StatementTrack.TOP, TrackUtility.ON, ComputeQueryId.AUTO, true),
                        null);
        assertThat(result.verdict()).isEqualTo(Verdict.STATEMENT_HISTORY_UNAVAILABLE);
    }

    @Test
    void inaccessibleExtensionClassifiesStatementHistoryUnavailable() {
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        new CapabilityEvidence(
                                true,
                                false,
                                StatementTrack.TOP,
                                TrackUtility.ON,
                                ComputeQueryId.AUTO,
                                true),
                        coveringHistory(0L, zeros()));
        assertThat(result.verdict()).isEqualTo(Verdict.STATEMENT_HISTORY_UNAVAILABLE);
    }

    @ParameterizedTest
    @EnumSource(SetterShape.class)
    void positiveCountWithCoveringResetClassifiesPresentInCoveringStats(SetterShape shape) {
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        coveringCapability(true), coveringHistory(0L, count(shape, 3L)));
        assertThat(result.verdict()).isEqualTo(Verdict.SETTER_SHAPE_PRESENT_IN_COVERING_STATS);
        assertThat(result.shapeCounts().get(shape)).isEqualTo(3L);
        assertThat(result.coveringIncident()).isTrue();
        assertThat(result.nonClaims())
                .contains(
                        NonClaim.RETAINED_SHAPE_NOT_TEMPORALLY_BOUND_TO_INCIDENT,
                        NonClaim.HISTORICAL_TRACKING_CONFIGURATION_NOT_PROVEN);
        assertThat(formatted(result))
                .doesNotContain("executed during")
                .doesNotContain("caused")
                .doesNotContain("bound to the incident");
    }

    @ParameterizedTest
    @EnumSource(SetterShape.class)
    void positiveCountWithResetAfterIncidentClassifiesPresentOutsideCoverage(SetterShape shape) {
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        coveringCapability(true),
                        new HistoryEvidence(OUTSIDE_RESET, 0L, count(shape, 1L)));
        assertThat(result.verdict()).isEqualTo(Verdict.SETTER_SHAPE_PRESENT_OUTSIDE_INCIDENT_COVERAGE);
        assertThat(result.coveringIncident()).isFalse();
        assertThat(result.nonClaims())
                .contains(NonClaim.RETAINED_SHAPE_NOT_TEMPORALLY_BOUND_TO_INCIDENT);
    }

    @Test
    void positiveCountTakesPrecedenceOverDisabledCurrentTracking() {
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        capability(true, StatementTrack.NONE, TrackUtility.OFF, ComputeQueryId.OFF, true),
                        coveringHistory(0L, count(SetterShape.SET_TRANSACTION_READ_ONLY, 2L)));
        assertThat(result.verdict()).isEqualTo(Verdict.SETTER_SHAPE_PRESENT_IN_COVERING_STATS);
        assertThat(result.nonClaims())
                .contains(NonClaim.HISTORICAL_TRACKING_CONFIGURATION_NOT_PROVEN);
    }

    @Test
    void allZeroCoveringVisibleStatsClassifyGlobalNegativeWithExplicitNonClaims() {
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        coveringCapability(true), coveringHistory(0L, zeros()));
        assertThat(result.verdict()).isEqualTo(Verdict.NO_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS);
        assertThat(result.shapeCounts().values()).containsOnly(0L);
        assertThat(result.nonClaims())
                .contains(
                        NonClaim.RETAINED_SHAPE_NOT_TEMPORALLY_BOUND_TO_INCIDENT,
                        NonClaim.HISTORICAL_TRACKING_CONFIGURATION_NOT_PROVEN)
                .doesNotContain(NonClaim.CURRENT_ROLE_VISIBILITY_ONLY);
        assertThat(formatted(result))
                .doesNotContain("executed during")
                .doesNotContain("caused")
                .doesNotContain("bound to the incident");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TOP", "ALL"})
    void topAndAllStatementTrackingAreAcceptedForANegative(String trackName) {
        StatementTrack track = StatementTrack.valueOf(trackName);
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        capability(true, track, TrackUtility.ON, ComputeQueryId.AUTO, true),
                        coveringHistory(0L, zeros()));
        assertThat(result.verdict()).isEqualTo(Verdict.NO_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS);
        assertThat(result.nonClaims())
                .contains(NonClaim.HISTORICAL_TRACKING_CONFIGURATION_NOT_PROVEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTO", "ON"})
    void autoAndOnQueryIdAreAcceptedForANegative(String queryIdName) {
        ComputeQueryId queryId = ComputeQueryId.valueOf(queryIdName);
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        capability(true, StatementTrack.TOP, TrackUtility.ON, queryId, true),
                        coveringHistory(0L, zeros()));
        assertThat(result.verdict()).isEqualTo(Verdict.NO_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS);
    }

    @Test
    void coveringZerosWithoutAllStatementVisibilityClassifyCurrentRoleNegative() {
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        coveringCapability(false), coveringHistory(0L, zeros()));
        assertThat(result.verdict())
                .isEqualTo(Verdict.NO_CURRENT_ROLE_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS);
        assertThat(result.nonClaims())
                .contains(
                        NonClaim.CURRENT_ROLE_VISIBILITY_ONLY,
                        NonClaim.RETAINED_SHAPE_NOT_TEMPORALLY_BOUND_TO_INCIDENT,
                        NonClaim.HISTORICAL_TRACKING_CONFIGURATION_NOT_PROVEN);
    }

    @Test
    void allZeroWithResetAfterIncidentClassifiesWindowNotCovering() {
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        coveringCapability(true),
                        new HistoryEvidence(OUTSIDE_RESET, 0L, zeros()));
        assertThat(result.verdict()).isEqualTo(Verdict.STATS_WINDOW_NOT_COVERING_INCIDENT);
    }

    @Test
    void allZeroWithDeallocationsClassifiesEvictionPreventsAbsence() {
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        coveringCapability(true), coveringHistory(4L, zeros()));
        assertThat(result.verdict()).isEqualTo(Verdict.STATS_EVICTION_PREVENTS_ABSENCE_CLAIM);
    }

    @Test
    void allZeroWithUtilityTrackingOffClassifiesUtilityTrackingDisabled() {
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        capability(true, StatementTrack.TOP, TrackUtility.OFF, ComputeQueryId.AUTO, true),
                        coveringHistory(0L, zeros()));
        assertThat(result.verdict()).isEqualTo(Verdict.UTILITY_TRACKING_DISABLED);
    }

    @ParameterizedTest
    @EnumSource(
            value = StatementTrack.class,
            names = {"NONE", "UNKNOWN"})
    void allZeroWithStatementTrackingNoneOrUnknownClassifiesStatementTrackingDisabled(
            StatementTrack track) {
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        capability(true, track, TrackUtility.ON, ComputeQueryId.AUTO, true),
                        coveringHistory(0L, zeros()));
        assertThat(result.verdict()).isEqualTo(Verdict.STATEMENT_TRACKING_DISABLED);
    }

    @ParameterizedTest
    @EnumSource(
            value = ComputeQueryId.class,
            names = {"OFF", "UNKNOWN"})
    void allZeroWithQueryIdOffOrUnknownClassifiesQueryIdCalculationDisabled(ComputeQueryId queryId) {
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        capability(true, StatementTrack.TOP, TrackUtility.ON, queryId, true),
                        coveringHistory(0L, zeros()));
        assertThat(result.verdict()).isEqualTo(Verdict.QUERY_ID_CALCULATION_DISABLED);
    }

    @Test
    void nullCapabilityIsInconclusive() {
        assertThat(SpecA912StatementHistoryProbe.classify(null, coveringHistory(0L, zeros())).verdict())
                .isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void missingHistoryWhenTrackingIsUsableIsInconclusive() {
        assertThat(SpecA912StatementHistoryProbe.classify(coveringCapability(true), null).verdict())
                .isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void negativeCountsAreInconclusiveAndFormatOmitsNegativeValues() {
        EnumMap<SetterShape, Long> counts = zeros();
        counts.put(SetterShape.DISCARD_ALL, -1L);
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        coveringCapability(true), coveringHistory(0L, counts));
        assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertSanitizedInconclusiveShapeCounts(formatted(result));
    }

    @Test
    void nullKeyShapeMapIsInconclusiveWithoutThrow() {
        Map<SetterShape, Long> bad = new HashMap<>();
        bad.put(null, 1L);
        for (SetterShape shape : SetterShape.values()) {
            bad.put(shape, 0L);
        }
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        coveringCapability(true), new HistoryEvidence(COVERING_RESET, 0L, bad));
        assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertSanitizedInconclusiveShapeCounts(formatted(result));
    }

    @Test
    void nullValueShapeMapIsInconclusiveWithoutThrow() {
        EnumMap<SetterShape, Long> counts = zeros();
        counts.put(SetterShape.DISCARD_ALL, null);
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        coveringCapability(true), new HistoryEvidence(COVERING_RESET, 0L, counts));
        assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertSanitizedInconclusiveShapeCounts(formatted(result));
    }

    @ParameterizedTest
    @EnumSource(SetterShape.class)
    void incompleteShapeMapFormatUsesSanitizedZerosOnly(SetterShape omitted) {
        EnumMap<SetterShape, Long> partial = zeros();
        partial.remove(omitted);
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        coveringCapability(true),
                        new HistoryEvidence(COVERING_RESET, 0L, partial));
        assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertSanitizedInconclusiveShapeCounts(formatted(result));
    }

    @Test
    void nullShapeCountsMapFormatUsesSanitizedZerosOnly() {
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        coveringCapability(true),
                        new HistoryEvidence(COVERING_RESET, 0L, null));
        assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertSanitizedInconclusiveShapeCounts(formatted(result));
    }

    @Test
    void nullStatsResetWithUsableTrackingIsInconclusive() {
        assertThat(
                        SpecA912StatementHistoryProbe.classify(
                                        coveringCapability(true),
                                        new HistoryEvidence(null, 0L, zeros()))
                                .verdict())
                .isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void nullShapeCountsMapIsInconclusive() {
        assertThat(
                        SpecA912StatementHistoryProbe.classify(
                                        coveringCapability(true),
                                        new HistoryEvidence(COVERING_RESET, 0L, null))
                                .verdict())
                .isEqualTo(Verdict.INCONCLUSIVE);
    }

    @ParameterizedTest
    @EnumSource(SetterShape.class)
    void incompleteShapeMapIsInconclusive(SetterShape omitted) {
        EnumMap<SetterShape, Long> partial = zeros();
        partial.remove(omitted);
        assertThat(
                        SpecA912StatementHistoryProbe.classify(
                                        coveringCapability(true),
                                        new HistoryEvidence(COVERING_RESET, 0L, partial))
                                .verdict())
                .isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void classifyFromEntriesRequiresEveryShapeExactlyOnce() {
        List<Map.Entry<SetterShape, Long>> partial =
                List.of(Map.entry(SetterShape.DISCARD_ALL, 0L));
        assertThat(
                        SpecA912StatementHistoryProbe.classifyFromEntries(
                                        coveringCapability(true), COVERING_RESET, 0L, partial)
                                .verdict())
                .isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void duplicatedShapeEntriesAreInconclusive() {
        EnumMap<SetterShape, Long> counts = zeros();
        List<Map.Entry<SetterShape, Long>> duplicated =
                List.of(
                        Map.entry(SetterShape.DISCARD_ALL, 1L),
                        Map.entry(SetterShape.DISCARD_ALL, 1L));
        assertThat(SpecA912StatementHistoryProbe.classifyFromEntries(
                        coveringCapability(true), COVERING_RESET, 0L, duplicated)
                        .verdict())
                .isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(counts).hasSize(SetterShape.values().length);
    }

    @Test
    void directConstructionWithAbsenceVerdictAndPartialCountsForcesInconclusive() {
        EnumMap<SetterShape, Long> partial = new EnumMap<>(SetterShape.class);
        partial.put(SetterShape.DISCARD_ALL, 0L);
        Classification classification = directClassification(
                Verdict.NO_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS, partial);
        assertThat(classification.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertSanitizedInconclusiveShapeCounts(formatted(classification));
    }

    @Test
    void directConstructionWithNegativeDeallocClampsToZero() {
        Classification classification =
                directClassification(
                        Verdict.NO_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS, zeros(), -9L);
        assertThat(classification.dealloc()).isZero();
        assertThat(formatted(classification)).contains("\"dealloc\":0");
    }

    @Test
    void directConstructionWithNullCountsForcesInconclusiveWithoutThrow() {
        Classification classification =
                directClassification(
                        Verdict.NO_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS, null);
        assertThat(classification.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertSanitizedInconclusiveShapeCounts(formatted(classification));
    }

    @Test
    void unexpectedFailureUsesFixedVocabularyOnly() {
        String output =
                SpecA912StatementHistoryProbe.formatConfigurationFailure(
                        List.of(), SpecA912StatementHistoryProbe.ConfigFailureReason.UNEXPECTED);
        assertThat(output)
                .contains("UNEXPECTED")
                .doesNotContain("exceptionClass")
                .doesNotContain("Exception");
    }

    @Test
    void formatterEmitsOnlyFixedKeysTimestampsBooleansCountsAndEnums() {
        Classification result =
                SpecA912StatementHistoryProbe.classify(
                        coveringCapability(true), coveringHistory(0L, zeros()));
        String output = formatted(result);
        assertThat(output)
                .contains("\"incidentStart\":\"2026-08-28T07:08:59Z\"")
                .contains("\"statsReset\":\"2026-08-27T00:00:00Z\"")
                .contains("\"coveringIncident\":true")
                .contains("\"dealloc\":0")
                .contains("\"extensionInstalled\":true")
                .contains("\"statementTrack\":\"TOP\"")
                .contains("\"trackUtility\":\"ON\"")
                .contains("\"computeQueryId\":\"AUTO\"")
                .contains("\"canReadAllStatementText\":true")
                .contains("\"verdict\":\"NO_RETAINED_SETTER_SHAPE_OBSERVED_IN_COVERING_STATS\"")
                .contains("\"SET_DEFAULT_TRANSACTION_READ_ONLY\":0")
                .contains("\"nonClaims\"");
        assertThat(output).doesNotContain("Exception").doesNotContain("@");
    }

    @Test
    void modelToStringAndFormattedOutputRedactSecretCanaries() {
        CapabilityEvidence capability = coveringCapability(true);
        HistoryEvidence history = coveringHistory(0L, zeros());
        Classification result = SpecA912StatementHistoryProbe.classify(capability, history);
        String combined =
                capability.toString()
                        + history.toString()
                        + result.toString()
                        + formatted(result)
                        + " jdbc:postgresql://secret-host.example/db wealth_user_canary p@ssw0rd-canary";
        String inspected =
                capability.toString() + history.toString() + result.toString() + formatted(result);
        assertThat(inspected)
                .doesNotContain("jdbc:postgresql")
                .doesNotContain("secret-host.example")
                .doesNotContain("wealth_user_canary")
                .doesNotContain("p@ssw0rd-canary")
                .doesNotContain("SET default_transaction_read_only")
                .doesNotContain("super secret exception")
                .doesNotContain("neon.tech");
        assertThat(combined).contains("jdbc:postgresql");
    }

    @Test
    void configurationRequiresExactApprovalAndFourEnvironmentVariables() {
        Map<String, String> env = new HashMap<>();
        SpecA912StatementHistoryProbe.LiveConfigResult missing =
                SpecA912StatementHistoryProbe.resolveLiveConfig(env);
        assertThat(missing.failed()).isTrue();
        assertThat(missing.reason())
                .isEqualTo(SpecA912StatementHistoryProbe.ConfigFailureReason.MISSING_REQUIRED);
        assertThat(missing.missingVariableNames())
                .containsExactly(
                        "SPEC_A_912_HISTORY_PROBE_APPROVED",
                        "SPEC_A_912_POOLED_JDBC_URL",
                        "SPEC_A_912_DB_USERNAME",
                        "SPEC_A_912_DB_PASSWORD");

        env.put("SPEC_A_912_HISTORY_PROBE_APPROVED", "TRUE");
        env.put("SPEC_A_912_POOLED_JDBC_URL", "jdbc:postgresql://secret-host.example/db");
        env.put("SPEC_A_912_DB_USERNAME", "wealth_user_canary");
        env.put("SPEC_A_912_DB_PASSWORD", "p@ssw0rd-canary");
        assertThat(SpecA912StatementHistoryProbe.resolveLiveConfig(env).reason())
                .isEqualTo(SpecA912StatementHistoryProbe.ConfigFailureReason.APPROVAL_NOT_TRUE);

        env.put("SPEC_A_912_HISTORY_PROBE_APPROVED", " true ");
        SpecA912StatementHistoryProbe.LiveConfigResult ok =
                SpecA912StatementHistoryProbe.resolveLiveConfig(env);
        assertThat(ok.failed()).isFalse();
        assertThat(ok.config()).isNotNull();
    }

    @Test
    void configurationFailureListsOnlyFixedVariableNamesAndReasonEnum() {
        SpecA912StatementHistoryProbe.LiveConfigResult result =
                SpecA912StatementHistoryProbe.resolveLiveConfig(Map.of());
        String output =
                SpecA912StatementHistoryProbe.formatConfigurationFailure(
                        result.missingVariableNames(), result.reason());
        assertThat(output)
                .contains("MISSING_REQUIRED")
                .contains("SPEC_A_912_HISTORY_PROBE_APPROVED")
                .contains("SPEC_A_912_POOLED_JDBC_URL")
                .contains("SPEC_A_912_DB_USERNAME")
                .contains("SPEC_A_912_DB_PASSWORD")
                .doesNotContain("jdbc:")
                .doesNotContain("password=");
    }

    @Test
    void runWithMissingEnvironmentDoesNotOpenJdbcAndPrintsOnlyFixedNames() throws Exception {
        StringBuilder out = new StringBuilder();
        int code = SpecA912StatementHistoryProbe.run(key -> null, out);
        assertThat(code).isEqualTo(2);
        assertThat(out.toString())
                .contains("MISSING_REQUIRED")
                .contains("SPEC_A_912_HISTORY_PROBE_APPROVED")
                .doesNotContain("jdbc:")
                .doesNotContain("Exception")
                .doesNotContain("at com.wealth");
    }

    @Test
    void liveConfigToStringRedactsUrlUsernameAndPassword() {
        SpecA912StatementHistoryProbe.LiveConfig config =
                new SpecA912StatementHistoryProbe.LiveConfig(
                        "jdbc:postgresql://secret-host.example/db",
                        "wealth_user_canary",
                        "p@ssw0rd-canary");
        assertThat(config.toString())
                .doesNotContain("secret-host")
                .doesNotContain("wealth_user_canary")
                .doesNotContain("p@ssw0rd-canary")
                .doesNotContain("jdbc:");
    }

    @Test
    void allowListedStatementsAreExactlyTheTwoFixedSelects() {
        List<String> statements = SpecA912StatementHistoryProbe.allowListedStatements();
        assertThat(statements)
                .containsExactly(
                        SpecA912StatementHistoryProbe.CAPABILITY_SQL,
                        SpecA912StatementHistoryProbe.HISTORY_SQL);
        assertThat(statements).hasSize(2);
        assertThat(SpecA912StatementHistoryProbe.CAPABILITY_SQL)
                .isEqualTo(
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
                                .trim());
        assertThat(SpecA912StatementHistoryProbe.HISTORY_SQL)
                .isEqualTo(
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
                                .trim());
    }

    @Test
    void neitherAllowListedStatementSelectsRawQueryTextOrMutates() {
        for (String statement : SpecA912StatementHistoryProbe.allowListedStatements()) {
            String normalized =
                    statement.replaceAll("(?s)/\\*.*?\\*/|--.*?(\\r?\\n|$)", " ").trim();
            String upper = normalized.toUpperCase(Locale.ROOT);
            assertThat(upper)
                    .doesNotStartWith("SET ")
                    .doesNotStartWith("RESET ")
                    .doesNotStartWith("DISCARD ")
                    .doesNotStartWith("INSERT ")
                    .doesNotStartWith("UPDATE ")
                    .doesNotStartWith("DELETE ")
                    .doesNotStartWith("ALTER ")
                    .doesNotContain(" INSERT ")
                    .doesNotContain(" UPDATE ")
                    .doesNotContain(" DELETE ")
                    .doesNotContain(" ALTER ")
                    .doesNotContain("STATS.QUERY AS")
                    .doesNotContain(" QUERY AS ");
        }
        assertThat(SpecA912StatementHistoryProbe.HISTORY_SQL).contains("stats.query ~*");
        assertThat(SpecA912StatementHistoryProbe.HISTORY_SQL)
                .doesNotContain("userid,")
                .doesNotContain("dbid");
    }

    @Test
    void capabilityFailurePreventsAggregateStatementAndRedactsExceptionText() throws Exception {
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString()))
                .thenThrow(new SQLException("super secret exception jdbc:postgresql://x", "42501"));

        Classification result = SpecA912StatementHistoryProbe.collect(connection);

        assertThat(result.verdict()).isEqualTo(Verdict.STATEMENT_HISTORY_UNAVAILABLE);
        Mockito.verify(statement, Mockito.times(1)).executeQuery(Mockito.anyString());
        Mockito.verify(statement)
                .executeQuery(SpecA912StatementHistoryProbe.CAPABILITY_SQL);
        assertThat(formatted(result))
                .doesNotContain("super secret exception")
                .doesNotContain("jdbc:postgresql")
                .doesNotContain("42501");
        Mockito.verify(connection, Mockito.never()).setReadOnly(Mockito.anyBoolean());
        Mockito.verify(connection, Mockito.never()).setAutoCommit(Mockito.anyBoolean());
        Mockito.verify(connection, Mockito.never()).commit();
        Mockito.verify(connection, Mockito.never()).rollback();
    }

    @Test
    void unknownTrackingStopsWithoutPreparingHistoryStatement() throws Exception {
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        java.sql.ResultSet capabilityRs = Mockito.mock(java.sql.ResultSet.class);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(SpecA912StatementHistoryProbe.CAPABILITY_SQL))
                .thenReturn(capabilityRs);
        Mockito.when(capabilityRs.next()).thenReturn(true);
        Mockito.when(capabilityRs.getBoolean("extension_installed")).thenReturn(true);
        Mockito.when(capabilityRs.getString("statement_track")).thenReturn("");
        Mockito.when(capabilityRs.getString("track_utility")).thenReturn("on");
        Mockito.when(capabilityRs.getString("compute_query_id")).thenReturn("auto");
        Mockito.when(capabilityRs.getBoolean("can_read_all_statement_text")).thenReturn(true);

        Classification result = SpecA912StatementHistoryProbe.collect(connection);

        assertThat(result.verdict()).isEqualTo(Verdict.STATEMENT_TRACKING_DISABLED);
        Mockito.verify(statement, Mockito.times(1)).executeQuery(Mockito.anyString());
    }

    @Test
    void collectorHasNoPublicMutationApi() {
        assertThat(SpecA912StatementHistoryProbe.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("setReadOnly", "setAutoCommit", "commit", "rollback");
    }

    private static String formatted(Classification result) {
        return SpecA912StatementHistoryProbe.format(result);
    }

    private static void assertSanitizedInconclusiveShapeCounts(String output) {
        assertThat(output).contains("\"verdict\":\"INCONCLUSIVE\"");
        for (SetterShape shape : SetterShape.values()) {
            assertThat(output).contains("\"" + shape.name() + "\":0");
            assertThat(output).doesNotContain("\"" + shape.name() + "\":null");
            assertThat(output).doesNotContain("\"" + shape.name() + "\":-");
        }
    }

    private static Classification directClassification(Verdict verdict, Map<SetterShape, Long> counts) {
        return directClassification(verdict, counts, 0L);
    }

    private static Classification directClassification(
            Verdict verdict, Map<SetterShape, Long> counts, long dealloc) {
        return new SpecA912StatementHistoryProbe.Classification(
                verdict,
                INCIDENT,
                COVERING_RESET,
                dealloc,
                true,
                true,
                true,
                StatementTrack.TOP,
                TrackUtility.ON,
                ComputeQueryId.AUTO,
                true,
                counts,
                List.of());
    }

    private static CapabilityEvidence coveringCapability(boolean canReadAll) {
        return capability(true, StatementTrack.TOP, TrackUtility.ON, ComputeQueryId.AUTO, canReadAll);
    }

    private static CapabilityEvidence capability(
            boolean extensionInstalled,
            StatementTrack statementTrack,
            TrackUtility trackUtility,
            ComputeQueryId computeQueryId,
            boolean canReadAllStatementText) {
        return new CapabilityEvidence(
                extensionInstalled,
                true,
                statementTrack,
                trackUtility,
                computeQueryId,
                canReadAllStatementText);
    }

    private static HistoryEvidence coveringHistory(long dealloc, EnumMap<SetterShape, Long> counts) {
        return new HistoryEvidence(COVERING_RESET, dealloc, counts);
    }

    private static EnumMap<SetterShape, Long> zeros() {
        EnumMap<SetterShape, Long> counts = new EnumMap<>(SetterShape.class);
        for (SetterShape shape : SetterShape.values()) {
            counts.put(shape, 0L);
        }
        return counts;
    }

    private static EnumMap<SetterShape, Long> count(SetterShape shape, long value) {
        EnumMap<SetterShape, Long> counts = zeros();
        counts.put(shape, value);
        return counts;
    }
}
