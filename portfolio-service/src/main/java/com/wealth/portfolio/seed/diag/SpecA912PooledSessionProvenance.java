package com.wealth.portfolio.seed.diag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure SQL classifier, session-default transition tracker, and sanitized event sink for Spec A
 * 9.12 pooled-session provenance. Never logs raw SQL or credentials.
 */
public final class SpecA912PooledSessionProvenance {

    public enum SetterKind {
        NONE,
        CONNECTION_SET_READ_ONLY_TRUE,
        CONNECTION_SET_READ_ONLY_FALSE,
        SESSION_DEFAULT_READ_ONLY,
        SESSION_DEFAULT_READ_WRITE,
        TRANSACTION_READ_ONLY,
        TRANSACTION_READ_WRITE,
        SET_CONFIG_SESSION_DEFAULT_READ_ONLY,
        SET_CONFIG_SESSION_DEFAULT_READ_WRITE,
        SET_CONFIG_LOCAL_DEFAULT_READ_ONLY,
        SET_CONFIG_LOCAL_DEFAULT_READ_WRITE,
        ALTER_ROLE_DEFAULT_READ_ONLY,
        ALTER_ROLE_DEFAULT_READ_WRITE,
        ALTER_DATABASE_DEFAULT_READ_ONLY,
        ALTER_DATABASE_DEFAULT_READ_WRITE,
        RESET_DEFAULT_READ_ONLY,
        RESET_ALL,
        DISCARD_ALL
    }

    public enum Transition {
        FIRST_OBSERVED_OFF,
        FIRST_OBSERVED_ON,
        UNCHANGED,
        ATTRIBUTED_OFF_TO_ON,
        UNATTRIBUTED_OFF_TO_ON,
        ON_TO_OFF
    }

    public record SessionSnapshot(
            long backendPid,
            boolean jdbcReadOnly,
            boolean autoCommit,
            String defaultTransactionReadOnly,
            String transactionReadOnly) {}

    public record ProvenanceEvent(
            String phase,
            long checkoutId,
            SessionSnapshot snapshot,
            Transition transition,
            SetterKind setterKind,
            String callPath,
            String sqlState) {}

    @FunctionalInterface
    public interface EventSink {
        void emit(ProvenanceEvent event);
    }

    private static final Logger log = LoggerFactory.getLogger(SpecA912PooledSessionProvenance.class);

    private static final Pattern SESSION_DEFAULT_READ_ONLY =
            Pattern.compile(
                    "(?is)^\\s*set\\s+session\\s+characteristics\\s+as\\s+transaction\\s+read\\s+only\\s*;?\\s*$");
    private static final Pattern SESSION_DEFAULT_READ_WRITE =
            Pattern.compile(
                    "(?is)^\\s*set\\s+session\\s+characteristics\\s+as\\s+transaction\\s+read\\s+write\\s*;?\\s*$");
    private static final Pattern SET_DEFAULT_ON =
            Pattern.compile(
                    "(?is)^\\s*set\\s+(?:session\\s+)?default_transaction_read_only\\s*(?:=|to)\\s*on\\s*;?\\s*$");
    private static final Pattern SET_DEFAULT_OFF =
            Pattern.compile(
                    "(?is)^\\s*set\\s+(?:session\\s+)?default_transaction_read_only\\s*(?:=|to)\\s*off\\s*;?\\s*$");
    private static final Pattern TRANSACTION_READ_ONLY =
            Pattern.compile("(?is)^\\s*set\\s+transaction\\s+read\\s+only\\s*;?\\s*$");
    private static final Pattern TRANSACTION_READ_WRITE =
            Pattern.compile("(?is)^\\s*set\\s+transaction\\s+read\\s+write\\s*;?\\s*$");
    private static final Pattern SET_CONFIG_SESSION_ON =
            Pattern.compile(
                    "(?is)^\\s*select\\s+set_config\\s*\\(\\s*'default_transaction_read_only'\\s*,\\s*'on'\\s*,\\s*false\\s*\\)\\s*;?\\s*$");
    private static final Pattern SET_CONFIG_SESSION_OFF =
            Pattern.compile(
                    "(?is)^\\s*select\\s+set_config\\s*\\(\\s*'default_transaction_read_only'\\s*,\\s*'off'\\s*,\\s*false\\s*\\)\\s*;?\\s*$");
    private static final Pattern SET_CONFIG_LOCAL_ON =
            Pattern.compile(
                    "(?is)^\\s*select\\s+set_config\\s*\\(\\s*'default_transaction_read_only'\\s*,\\s*'on'\\s*,\\s*true\\s*\\)\\s*;?\\s*$");
    private static final Pattern SET_CONFIG_LOCAL_OFF =
            Pattern.compile(
                    "(?is)^\\s*select\\s+set_config\\s*\\(\\s*'default_transaction_read_only'\\s*,\\s*'off'\\s*,\\s*true\\s*\\)\\s*;?\\s*$");
    private static final Pattern ALTER_ROLE_ON =
            Pattern.compile(
                    "(?is)^\\s*alter\\s+role\\s+\\S+\\s+set\\s+default_transaction_read_only\\s*=\\s*on\\s*;?\\s*$");
    private static final Pattern ALTER_ROLE_OFF =
            Pattern.compile(
                    "(?is)^\\s*alter\\s+role\\s+\\S+\\s+set\\s+default_transaction_read_only\\s*=\\s*off\\s*;?\\s*$");
    private static final Pattern ALTER_DATABASE_ON =
            Pattern.compile(
                    "(?is)^\\s*alter\\s+database\\s+\\S+\\s+set\\s+default_transaction_read_only\\s*=\\s*on\\s*;?\\s*$");
    private static final Pattern ALTER_DATABASE_OFF =
            Pattern.compile(
                    "(?is)^\\s*alter\\s+database\\s+\\S+\\s+set\\s+default_transaction_read_only\\s*=\\s*off\\s*;?\\s*$");
    private static final Pattern RESET_DEFAULT =
            Pattern.compile("(?is)^\\s*reset\\s+default_transaction_read_only\\s*;?\\s*$");
    private static final Pattern RESET_ALL_PATTERN = Pattern.compile("(?is)^\\s*reset\\s+all\\s*;?\\s*$");
    private static final Pattern DISCARD_ALL_PATTERN = Pattern.compile("(?is)^\\s*discard\\s+all\\s*;?\\s*$");

    private record SetterObservation(SetterKind kind, long checkoutId, String callPath) {}

    private final ConcurrentMap<Long, SessionSnapshot> lastByPid = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, SetterObservation> confirmedSetterByPid = new ConcurrentHashMap<>();
    private final EventSink sink;

    SpecA912PooledSessionProvenance(EventSink sink) {
        this.sink = sink;
    }

    public static SpecA912PooledSessionProvenance logging() {
        return new SpecA912PooledSessionProvenance(SpecA912PooledSessionProvenance::emitToLog);
    }

    public static SpecA912PooledSessionProvenance withSink(EventSink sink) {
        return new SpecA912PooledSessionProvenance(sink);
    }

    static SetterKind classifySetter(String sql) {
        if (sql == null || sql.isBlank()) {
            return SetterKind.NONE;
        }
        String normalized = sql.strip();
        if (SESSION_DEFAULT_READ_ONLY.matcher(normalized).matches()) {
            return SetterKind.SESSION_DEFAULT_READ_ONLY;
        }
        if (SESSION_DEFAULT_READ_WRITE.matcher(normalized).matches()) {
            return SetterKind.SESSION_DEFAULT_READ_WRITE;
        }
        if (SET_DEFAULT_ON.matcher(normalized).matches()) {
            return SetterKind.SESSION_DEFAULT_READ_ONLY;
        }
        if (SET_DEFAULT_OFF.matcher(normalized).matches()) {
            return SetterKind.SESSION_DEFAULT_READ_WRITE;
        }
        if (TRANSACTION_READ_ONLY.matcher(normalized).matches()) {
            return SetterKind.TRANSACTION_READ_ONLY;
        }
        if (TRANSACTION_READ_WRITE.matcher(normalized).matches()) {
            return SetterKind.TRANSACTION_READ_WRITE;
        }
        if (SET_CONFIG_SESSION_ON.matcher(normalized).matches()) {
            return SetterKind.SET_CONFIG_SESSION_DEFAULT_READ_ONLY;
        }
        if (SET_CONFIG_SESSION_OFF.matcher(normalized).matches()) {
            return SetterKind.SET_CONFIG_SESSION_DEFAULT_READ_WRITE;
        }
        if (SET_CONFIG_LOCAL_ON.matcher(normalized).matches()) {
            return SetterKind.SET_CONFIG_LOCAL_DEFAULT_READ_ONLY;
        }
        if (SET_CONFIG_LOCAL_OFF.matcher(normalized).matches()) {
            return SetterKind.SET_CONFIG_LOCAL_DEFAULT_READ_WRITE;
        }
        if (ALTER_ROLE_ON.matcher(normalized).matches()) {
            return SetterKind.ALTER_ROLE_DEFAULT_READ_ONLY;
        }
        if (ALTER_ROLE_OFF.matcher(normalized).matches()) {
            return SetterKind.ALTER_ROLE_DEFAULT_READ_WRITE;
        }
        if (ALTER_DATABASE_ON.matcher(normalized).matches()) {
            return SetterKind.ALTER_DATABASE_DEFAULT_READ_ONLY;
        }
        if (ALTER_DATABASE_OFF.matcher(normalized).matches()) {
            return SetterKind.ALTER_DATABASE_DEFAULT_READ_WRITE;
        }
        if (RESET_DEFAULT.matcher(normalized).matches()) {
            return SetterKind.RESET_DEFAULT_READ_ONLY;
        }
        if (RESET_ALL_PATTERN.matcher(normalized).matches()) {
            return SetterKind.RESET_ALL;
        }
        if (DISCARD_ALL_PATTERN.matcher(normalized).matches()) {
            return SetterKind.DISCARD_ALL;
        }
        return SetterKind.NONE;
    }

    static SetterKind classifyConnectionSetReadOnly(boolean readOnly) {
        return readOnly ? SetterKind.CONNECTION_SET_READ_ONLY_TRUE : SetterKind.CONNECTION_SET_READ_ONLY_FALSE;
    }

    void observe(String phase, long checkoutId, SessionSnapshot snapshot) {
        observeInternal(phase, checkoutId, snapshot, false);
    }

    void recordSetterAttempt(
            String phase,
            long checkoutId,
            SessionSnapshot snapshot,
            SetterKind setterKind,
            String callPath) {
        safeEmit(
                new ProvenanceEvent(
                        phase,
                        checkoutId,
                        snapshot,
                        Transition.UNCHANGED,
                        setterKind,
                        callPath,
                        null));
    }

    void captureAndResolveSetter(
            String confirmPhase,
            String observePhase,
            long checkoutId,
            SessionSnapshot snapshot,
            SetterKind setterKind,
            String callPath) {
        long pid = snapshot.backendPid();
        try {
            if (setterKind != SetterKind.NONE) {
                confirmedSetterByPid.put(
                        pid, new SetterObservation(setterKind, checkoutId, callPath));
                safeEmit(
                        new ProvenanceEvent(
                                confirmPhase,
                                checkoutId,
                                snapshot,
                                Transition.UNCHANGED,
                                setterKind,
                                callPath,
                                null));
            }
            observeInternal(observePhase, checkoutId, snapshot, true);
        } finally {
            confirmedSetterByPid.remove(pid);
        }
    }

    static SetterKind lastSessionStateAffectingSetter(List<SetterKind> kinds) {
        SetterKind last = SetterKind.NONE;
        for (SetterKind kind : kinds) {
            if (affectsCurrentSessionState(kind)) {
                last = kind;
            }
        }
        return last;
    }

    static boolean affectsCurrentSessionState(SetterKind kind) {
        return kind == SetterKind.SESSION_DEFAULT_READ_ONLY
                || kind == SetterKind.SESSION_DEFAULT_READ_WRITE
                || kind == SetterKind.SET_CONFIG_SESSION_DEFAULT_READ_ONLY
                || kind == SetterKind.SET_CONFIG_SESSION_DEFAULT_READ_WRITE
                || kind == SetterKind.RESET_DEFAULT_READ_ONLY
                || kind == SetterKind.RESET_ALL
                || kind == SetterKind.DISCARD_ALL;
    }

    void emitObservationSkipped(String phase, long checkoutId, String reason) {
        log.warn(
                "event=spec_a912_pool_session_provenance_skipped phase={} checkoutId={} reason={}",
                phase,
                checkoutId,
                reason);
    }

    void emitObservationFailure(String phase, Class<?> cause, String sqlState) {
        log.warn(
                "event=spec_a912_pool_session_provenance_failed phase={} cause={} sqlState={}",
                phase,
                cause.getSimpleName(),
                sqlState == null ? "null" : sqlState);
    }

    static String boundedCallPath() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        List<String> entries = new ArrayList<>(8);
        boolean collecting = false;
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (!collecting) {
                if (className.contains("SpecA912PooledSessionProvenance")
                        || className.contains("SpecA912ProvenanceDataSource")
                        || className.startsWith("jdk.proxy")
                        || className.startsWith("java.lang.reflect")) {
                    continue;
                }
                collecting = true;
            }
            if (shouldExcludeFrame(className)) {
                continue;
            }
            entries.add(simpleClassName(className) + "#" + frame.getMethodName());
            if (entries.size() >= 8) {
                break;
            }
        }
        return String.join(" <- ", entries);
    }

    public static String formatEvent(ProvenanceEvent event) {
        SessionSnapshot snap = event.snapshot();
        return "event=spec_a912_pool_session_provenance"
                + " phase=" + event.phase()
                + " checkoutId=" + event.checkoutId()
                + " backendPid=" + (snap == null ? "null" : snap.backendPid())
                + " transition=" + event.transition()
                + " setterKind=" + event.setterKind()
                + " jdbcReadOnly=" + (snap == null ? "null" : snap.jdbcReadOnly())
                + " autoCommit=" + (snap == null ? "null" : snap.autoCommit())
                + " defaultTransactionReadOnly="
                + (snap == null ? "null" : snap.defaultTransactionReadOnly())
                + " transactionReadOnly=" + (snap == null ? "null" : snap.transactionReadOnly())
                + " callPath=" + sanitizeCallPath(event.callPath())
                + " sqlState=" + (event.sqlState() == null ? "null" : event.sqlState());
    }

    private void observeInternal(
            String phase, long checkoutId, SessionSnapshot snapshot, boolean afterSetter) {
        long pid = snapshot.backendPid();
        boolean currentOn = isDefaultOn(snapshot.defaultTransactionReadOnly());
        SessionSnapshot previous = lastByPid.get(pid);

        Transition transition;
        if (previous == null) {
            transition = currentOn ? Transition.FIRST_OBSERVED_ON : Transition.FIRST_OBSERVED_OFF;
            lastByPid.put(pid, snapshot);
            safeEmit(
                    new ProvenanceEvent(
                            phase,
                            checkoutId,
                            snapshot,
                            transition,
                            SetterKind.NONE,
                            boundedCallPath(),
                            null));
            if (afterSetter) {
                confirmedSetterByPid.remove(pid);
            }
            return;
        }

        boolean previousOn = isDefaultOn(previous.defaultTransactionReadOnly());
        if (previousOn == currentOn) {
            lastByPid.put(pid, snapshot);
            if (afterSetter) {
                confirmedSetterByPid.remove(pid);
            }
            return;
        }

        if (!previousOn && currentOn) {
            SetterObservation observation = confirmedSetterByPid.remove(pid);
            if (observation != null && attributesSessionDefault(observation.kind())) {
                transition = Transition.ATTRIBUTED_OFF_TO_ON;
            } else {
                transition = Transition.UNATTRIBUTED_OFF_TO_ON;
            }
        } else {
            transition = Transition.ON_TO_OFF;
            confirmedSetterByPid.remove(pid);
        }

        lastByPid.put(pid, snapshot);
        safeEmit(
                new ProvenanceEvent(
                        phase,
                        checkoutId,
                        snapshot,
                        transition,
                        SetterKind.NONE,
                        boundedCallPath(),
                        null));
        if (afterSetter) {
            confirmedSetterByPid.remove(pid);
        }
    }

    private void safeEmit(ProvenanceEvent event) {
        try {
            sink.emit(event);
        } catch (RuntimeException ex) {
            emitObservationFailure("sink", ex.getClass(), null);
        }
    }

    private static void emitToLog(ProvenanceEvent event) {
        log.info("{}", formatEvent(event));
    }

    private static boolean isDefaultOn(String guc) {
        return "on".equalsIgnoreCase(guc);
    }

    private static boolean attributesSessionDefault(SetterKind kind) {
        return kind == SetterKind.SESSION_DEFAULT_READ_ONLY
                || kind == SetterKind.SET_CONFIG_SESSION_DEFAULT_READ_ONLY;
    }

    private static boolean shouldExcludeFrame(String className) {
        return className.startsWith("java.lang.reflect")
                || className.startsWith("jdk.proxy")
                || className.startsWith("java.lang.Thread")
                || className.contains("SpecA912ProvenanceDataSource")
                || className.contains("SpecA912PooledSessionProvenance");
    }

    private static String simpleClassName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(lastDot + 1) : className;
    }

    private static String sanitizeCallPath(String callPath) {
        if (callPath == null || callPath.isBlank()) {
            return "null";
        }
        return ArraysStreamSafe(callPath);
    }

    private static String ArraysStreamSafe(String callPath) {
        return List.of(callPath.split(" <- ")).stream()
                .filter(entry -> !shouldExcludeFrame(entry.split("#")[0]))
                .limit(8)
                .collect(Collectors.joining(" <- "));
    }
}
