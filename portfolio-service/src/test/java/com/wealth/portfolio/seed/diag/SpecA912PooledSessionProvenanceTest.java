package com.wealth.portfolio.seed.diag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpecA912PooledSessionProvenanceTest {

    private final List<SpecA912PooledSessionProvenance.ProvenanceEvent> events = new CopyOnWriteArrayList<>();
    private SpecA912PooledSessionProvenance provenance;

    @BeforeEach
    void setUp() {
        events.clear();
        provenance = SpecA912PooledSessionProvenance.withSink(events::add);
    }

    @Test
    void classifySetter_recognizesSessionAndTransactionSetters() {
        assertKind("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY", SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_ONLY);
        assertKind("set session characteristics as transaction read write;", SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_WRITE);
        assertKind("SET default_transaction_read_only = on", SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_ONLY);
        assertKind("SET SESSION default_transaction_read_only TO off", SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_WRITE);
        assertKind("SET TRANSACTION READ ONLY", SpecA912PooledSessionProvenance.SetterKind.TRANSACTION_READ_ONLY);
        assertKind("SET TRANSACTION READ WRITE", SpecA912PooledSessionProvenance.SetterKind.TRANSACTION_READ_WRITE);
    }

    @Test
    void classifySetter_recognizesSetConfigAlterResetAndDiscard() {
        assertKind(
                "SELECT set_config('default_transaction_read_only','on',false)",
                SpecA912PooledSessionProvenance.SetterKind.SET_CONFIG_SESSION_DEFAULT_READ_ONLY);
        assertKind(
                "SELECT set_config('default_transaction_read_only','off',false)",
                SpecA912PooledSessionProvenance.SetterKind.SET_CONFIG_SESSION_DEFAULT_READ_WRITE);
        assertKind(
                "SELECT set_config('default_transaction_read_only','on',true)",
                SpecA912PooledSessionProvenance.SetterKind.SET_CONFIG_LOCAL_DEFAULT_READ_ONLY);
        assertKind(
                "ALTER ROLE app_user SET default_transaction_read_only = on",
                SpecA912PooledSessionProvenance.SetterKind.ALTER_ROLE_DEFAULT_READ_ONLY);
        assertKind(
                "ALTER DATABASE app_db SET default_transaction_read_only = off",
                SpecA912PooledSessionProvenance.SetterKind.ALTER_DATABASE_DEFAULT_READ_WRITE);
        assertKind("RESET default_transaction_read_only", SpecA912PooledSessionProvenance.SetterKind.RESET_DEFAULT_READ_ONLY);
        assertKind("RESET ALL", SpecA912PooledSessionProvenance.SetterKind.RESET_ALL);
        assertKind("DISCARD ALL", SpecA912PooledSessionProvenance.SetterKind.DISCARD_ALL);
    }

    @Test
    void classifySetter_rejectsNonSetterStatements() {
        assertKind("SELECT 'SET default_transaction_read_only = on'", SpecA912PooledSessionProvenance.SetterKind.NONE);
        assertKind("UPDATE audit SET message='READ ONLY'", SpecA912PooledSessionProvenance.SetterKind.NONE);
    }

    @Test
    void observe_firstObservationClassifiesOffAndOn() {
        observeOff(1L, 10L);
        assertThat(lastTransition()).isEqualTo(SpecA912PooledSessionProvenance.Transition.FIRST_OBSERVED_OFF);

        events.clear();
        SpecA912PooledSessionProvenance fresh = SpecA912PooledSessionProvenance.withSink(events::add);
        fresh.observe("checkout", 2L, snapshot(20L, false, true, "on", "on"));
        assertThat(lastTransition()).isEqualTo(SpecA912PooledSessionProvenance.Transition.FIRST_OBSERVED_ON);
    }

    @Test
    void observe_unchangedDefaultIsSuppressed() {
        observeOff(1L, 10L);
        events.clear();
        provenance.observe("return", 1L, snapshot(10L, false, true, "off", "off"));
        assertThat(events).isEmpty();
    }

    @Test
    void captureAndResolveSetter_attributedOffToOnWhenSessionDefaultSetterResolves() {
        observeOff(1L, 10L);
        events.clear();

        captureSessionDefaultSetter(1L, 10L, snapshot(10L, false, true, "on", "on"));

        assertThat(events)
                .extracting(SpecA912PooledSessionProvenance.ProvenanceEvent::transition)
                .contains(SpecA912PooledSessionProvenance.Transition.ATTRIBUTED_OFF_TO_ON);
    }

    @Test
    void observe_unattributedOffToOnWhenNoSetterConfirmed() {
        observeOff(1L, 10L);
        events.clear();

        provenance.observe("return", 1L, snapshot(10L, false, true, "on", "on"));

        assertThat(lastTransition()).isEqualTo(SpecA912PooledSessionProvenance.Transition.UNATTRIBUTED_OFF_TO_ON);
    }

    @Test
    void captureAndResolveSetter_clearsPendingConfirmationAfterNoOpSetter() {
        observeOff(1L, 10L);
        events.clear();

        provenance.captureAndResolveSetter(
                "setter-confirmed",
                "post-setter",
                1L,
                snapshot(10L, false, true, "off", "off"),
                SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_ONLY,
                "Probe#execute");
        events.clear();

        provenance.observe("checkout", 2L, snapshot(10L, false, true, "on", "on"));

        assertThat(lastTransition()).isEqualTo(SpecA912PooledSessionProvenance.Transition.UNATTRIBUTED_OFF_TO_ON);
    }

    @Test
    void lastSessionStateAffectingSetter_prefersLatestSessionDefaultOverTransactionLocal() {
        List<SpecA912PooledSessionProvenance.SetterKind> batch =
                List.of(
                        SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_ONLY,
                        SpecA912PooledSessionProvenance.SetterKind.TRANSACTION_READ_ONLY);

        assertThat(SpecA912PooledSessionProvenance.lastSessionStateAffectingSetter(batch))
                .isEqualTo(SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_ONLY);
    }

    @Test
    void lastSessionStateAffectingSetter_prefersResetOverTrailingTransactionLocal() {
        List<SpecA912PooledSessionProvenance.SetterKind> batch =
                List.of(
                        SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_WRITE,
                        SpecA912PooledSessionProvenance.SetterKind.RESET_DEFAULT_READ_ONLY,
                        SpecA912PooledSessionProvenance.SetterKind.TRANSACTION_READ_ONLY);

        assertThat(SpecA912PooledSessionProvenance.lastSessionStateAffectingSetter(batch))
                .isEqualTo(SpecA912PooledSessionProvenance.SetterKind.RESET_DEFAULT_READ_ONLY);
    }

    @Test
    void lastSessionStateAffectingSetter_includesResetAllAndDiscardAll() {
        assertThat(
                        SpecA912PooledSessionProvenance.lastSessionStateAffectingSetter(
                                List.of(
                                        SpecA912PooledSessionProvenance.SetterKind.TRANSACTION_READ_ONLY,
                                        SpecA912PooledSessionProvenance.SetterKind.RESET_ALL)))
                .isEqualTo(SpecA912PooledSessionProvenance.SetterKind.RESET_ALL);
        assertThat(
                        SpecA912PooledSessionProvenance.lastSessionStateAffectingSetter(
                                List.of(SpecA912PooledSessionProvenance.SetterKind.DISCARD_ALL)))
                .isEqualTo(SpecA912PooledSessionProvenance.SetterKind.DISCARD_ALL);
    }

    @Test
    void lastSessionStateAffectingSetter_ignoresAlterRoleAndConnectionSetters() {
        List<SpecA912PooledSessionProvenance.SetterKind> batch =
                List.of(
                        SpecA912PooledSessionProvenance.SetterKind.ALTER_ROLE_DEFAULT_READ_ONLY,
                        SpecA912PooledSessionProvenance.SetterKind.CONNECTION_SET_READ_ONLY_TRUE,
                        SpecA912PooledSessionProvenance.SetterKind.TRANSACTION_READ_ONLY);

        assertThat(SpecA912PooledSessionProvenance.lastSessionStateAffectingSetter(batch))
                .isEqualTo(SpecA912PooledSessionProvenance.SetterKind.NONE);
    }

    @Test
    void recordSetterAttempt_allowsNullSnapshot() {
        provenance.recordSetterAttempt(
                "setter-attempt",
                1L,
                null,
                SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_ONLY,
                "Probe#execute");

        assertThat(events.getFirst().snapshot()).isNull();
    }

    @Test
    void observe_attemptWithoutConfirmDoesNotAttributeLaterTransition() {
        observeOff(1L, 10L);
        events.clear();

        provenance.recordSetterAttempt(
                "setter-attempt",
                1L,
                null,
                SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_ONLY,
                "Probe#execute");
        provenance.observe("return", 1L, snapshot(10L, false, true, "on", "on"));

        assertThat(lastTransition()).isEqualTo(SpecA912PooledSessionProvenance.Transition.UNATTRIBUTED_OFF_TO_ON);
    }

    @Test
    void captureAndResolveSetter_connectionSetReadOnlyDoesNotAttributeSessionDefaultTransition() {
        observeOff(1L, 10L);
        events.clear();

        provenance.captureAndResolveSetter(
                "setter-confirmed",
                "post-setter",
                1L,
                snapshot(10L, true, true, "on", "on"),
                SpecA912PooledSessionProvenance.SetterKind.CONNECTION_SET_READ_ONLY_TRUE,
                "Probe#setReadOnly");

        assertThat(lastTransition()).isEqualTo(SpecA912PooledSessionProvenance.Transition.UNATTRIBUTED_OFF_TO_ON);
    }

    @Test
    void captureAndResolveSetter_localSetConfigDoesNotAttributeSessionDefaultTransition() {
        observeOff(1L, 10L);
        events.clear();

        provenance.captureAndResolveSetter(
                "setter-confirmed",
                "post-setter",
                1L,
                snapshot(10L, false, true, "on", "on"),
                SpecA912PooledSessionProvenance.SetterKind.SET_CONFIG_LOCAL_DEFAULT_READ_ONLY,
                "Probe#execute");

        assertThat(lastTransition()).isEqualTo(SpecA912PooledSessionProvenance.Transition.UNATTRIBUTED_OFF_TO_ON);
    }

    @Test
    void captureAndResolveSetter_resetDoesNotAttributeOffToOn() {
        observeOff(1L, 10L);
        events.clear();

        provenance.captureAndResolveSetter(
                "setter-confirmed",
                "post-setter",
                1L,
                snapshot(10L, false, true, "on", "on"),
                SpecA912PooledSessionProvenance.SetterKind.RESET_DEFAULT_READ_ONLY,
                "Probe#executeBatch");

        assertThat(lastTransition()).isEqualTo(SpecA912PooledSessionProvenance.Transition.UNATTRIBUTED_OFF_TO_ON);
    }

    @Test
    void captureAndResolveSetter_discardAllDoesNotAttributeOffToOn() {
        observeOff(1L, 10L);
        events.clear();

        provenance.captureAndResolveSetter(
                "setter-confirmed",
                "post-setter",
                1L,
                snapshot(10L, false, true, "on", "on"),
                SpecA912PooledSessionProvenance.SetterKind.DISCARD_ALL,
                "Probe#executeBatch");

        assertThat(lastTransition()).isEqualTo(SpecA912PooledSessionProvenance.Transition.UNATTRIBUTED_OFF_TO_ON);
    }

    @Test
    void observe_onToOffTransition() {
        provenance.observe("checkout", 1L, snapshot(10L, false, true, "on", "on"));
        events.clear();

        provenance.observe("return", 1L, snapshot(10L, false, true, "off", "off"));

        assertThat(lastTransition()).isEqualTo(SpecA912PooledSessionProvenance.Transition.ON_TO_OFF);
    }

    @Test
    void captureAndResolveSetter_attributionIsIsolatedByPid() {
        observeOff(1L, 10L);
        events.clear();

        captureSessionDefaultSetter(1L, 10L, snapshot(10L, false, true, "on", "on"));
        events.clear();

        provenance.observe("checkout", 2L, snapshot(20L, false, true, "on", "on"));

        assertThat(lastTransition()).isEqualTo(SpecA912PooledSessionProvenance.Transition.FIRST_OBSERVED_ON);
    }

    @Test
    void throwingSinkDoesNotPreventObserve() {
        SpecA912PooledSessionProvenance throwing =
                SpecA912PooledSessionProvenance.withSink(
                        event -> {
                            throw new IllegalStateException("sink-boom");
                        });

        throwing.observe("checkout", 1L, snapshot(10L, false, true, "off", "off"));
    }

    @Test
    void formattedEventsContainNoRawSqlOrSecrets() {
        provenance.recordSetterAttempt(
                "setter-attempt",
                1L,
                null,
                SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_ONLY,
                "com.example.App#run");

        String line = SpecA912PooledSessionProvenance.formatEvent(events.getFirst());
        assertThat(line).startsWith("event=spec_a912_pool_session_provenance");
        assertThat(line).doesNotContain("SET SESSION");
        assertThat(line).doesNotContain("password");
        assertThat(line).doesNotContain("jdbc:");
        assertThat(line).doesNotContain("Bearer ");
    }

    @Test
    void boundedCallPath_excludesInternalFramesAndCapsEntries() {
        String path = SpecA912PooledSessionProvenance.boundedCallPath();
        assertThat(path).doesNotContain("java.lang.reflect");
        assertThat(path).doesNotContain("jdk.proxy");
        assertThat(path).doesNotContain("SpecA912ProvenanceDataSource");
        assertThat(path).doesNotContain("SpecA912PooledSessionProvenance");
        assertThat(path.split(" <- ")).hasSizeLessThanOrEqualTo(8);
    }

    private void captureSessionDefaultSetter(
            long checkoutId, long pid, SpecA912PooledSessionProvenance.SessionSnapshot postSetterSnapshot) {
        provenance.captureAndResolveSetter(
                "setter-confirmed",
                "post-setter",
                checkoutId,
                postSetterSnapshot,
                SpecA912PooledSessionProvenance.SetterKind.SESSION_DEFAULT_READ_ONLY,
                "Probe#execute");
    }

    private void observeOff(long checkoutId, long pid) {
        provenance.observe("checkout", checkoutId, snapshot(pid, false, true, "off", "off"));
    }

    private SpecA912PooledSessionProvenance.Transition lastTransition() {
        return events.getLast().transition();
    }

    private static void assertKind(String sql, SpecA912PooledSessionProvenance.SetterKind expected) {
        assertThat(SpecA912PooledSessionProvenance.classifySetter(sql)).isEqualTo(expected);
    }

    private static SpecA912PooledSessionProvenance.SessionSnapshot snapshot(
            long pid, boolean jdbcReadOnly, boolean autoCommit, String defaultRo, String txRo) {
        return new SpecA912PooledSessionProvenance.SessionSnapshot(
                pid, jdbcReadOnly, autoCommit, defaultRo, txRo);
    }
}
