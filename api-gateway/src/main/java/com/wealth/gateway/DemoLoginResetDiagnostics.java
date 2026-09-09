package com.wealth.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Internal operational evidence only: never logs credentials, response bodies or exception messages. */
public final class DemoLoginResetDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(DemoLoginResetDiagnostics.class);
    private final ReplicaTokenProvider replicaTokenProvider;

    public DemoLoginResetDiagnostics(ReplicaTokenProvider replicaTokenProvider) {
        this.replicaTokenProvider = replicaTokenProvider;
    }

    void skipped(Attempt attempt, Throwable error) {
        Map<String, Object> fields = attempt.failure(error);
        fields.put("event", "demo_reset_self_call_skipped");
        fields.put("traceId", MDC.get("traceId"));
        fields.put("replicaToken", replicaTokenProvider.replicaToken());
        emit(fields);
    }

    void eligibilityCompleted(Attempt attempt, DemoLoginPortfolioObservation observation) {
        completed(attempt.observed(observation));
    }

    void resetCompleted(Attempt attempt) {
        completed(attempt.success(true));
    }

    private void completed(Map<String, Object> fields) {
        fields.put("event", "demo_reset_self_call_completed");
        fields.put("traceId", MDC.get("traceId"));
        fields.put("replicaToken", replicaTokenProvider.replicaToken());
        emit(fields);
    }

    private void emit(Map<String, Object> fields) {
        var event = log.atInfo();
        fields.forEach(event::addKeyValue);
        // The existing console pattern renders %msg, not %kvp. Keep the structured evidence
        // in the rendered message as well so Azure log consumers receive every field.
        event.log(fields.entrySet().stream().map(field -> field.getKey() + "="
                + ("".equals(field.getValue()) ? "\"\"" : String.valueOf(field.getValue())))
                .collect(java.util.stream.Collectors.joining(" ")));
    }

    /** One instance per subscription. Updates and timeout snapshots share one lock. */
    static final class Attempt {
        private final boolean keyConfigured;
        private final boolean originRequired;
        private final LongSupplier nanoClock;
        private final long overallStart;
        private String phase = "eligibility_pre_dispatch";
        private boolean eligibilityDispatched;
        private boolean resetDispatched;
        private Boolean originAttached;
        private Boolean keyAttached;
        private URI target;
        private long legStart;
        private Integer status;
        private Long observedVersion;
        private Long submittedVersion;
        private int resetCount;

        Attempt(boolean keyConfigured, boolean originRequired, LongSupplier nanoClock) {
            this.keyConfigured = keyConfigured;
            this.originRequired = originRequired;
            this.nanoClock = nanoClock;
            this.overallStart = nanoClock.getAsLong();
        }

        synchronized void dispatched(boolean reset, ClientRequest request, Long version) {
            legStart = nanoClock.getAsLong();
            target = request.url();
            status = null;
            if (reset) {
                resetDispatched = true;
                resetCount++;
                submittedVersion = version;
                keyAttached = request.headers().containsHeader("X-Internal-Api-Key");
                phase = "reset_in_flight";
            } else {
                eligibilityDispatched = true;
                originAttached = originRequired ? request.headers().containsHeader("X-Origin-Verify") : null;
                phase = "eligibility_in_flight";
            }
        }

        synchronized void received(boolean reset, int receivedStatus) {
            status = receivedStatus;
            if (reset) phase = "reset_post_response";
        }

        synchronized Map<String, Object> observed(DemoLoginPortfolioObservation observation) {
            Map<String, Object> fields = success(false);
            observedVersion = observation.version();
            phase = "between_legs";
            status = null;
            target = null;
            return fields;
        }

        synchronized Map<String, Object> success(boolean reset) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("leg", reset ? "reset" : "eligibility");
            fields.put("httpStatus", status);
            fields.put("elapsedMillis", (nanoClock.getAsLong() - legStart) / 1_000_000L);
            return fields;
        }

        synchronized Map<String, Object> failure(Throwable error) {
            boolean overall = error instanceof DemoLoginResetOrchestrator.OverallDeadline;
            String leg = overall ? "overall" : (phase.startsWith("eligibility_") ? "eligibility" : "reset");
            String reason;
            if (overall) reason = "overall_timeout";
            else if (error instanceof TimeoutException) reason = leg + "_timeout";
            else if (error instanceof DemoLoginResetClient.HttpStatusFailure) reason = leg + "_non_2xx_status";
            else if (error instanceof DemoLoginResetClient.EligibilityShapeException) reason = "eligibility_shape_failure";
            else if (error instanceof DemoLoginResetClient.ResetKeyNotConfiguredException) reason = "reset_key_not_configured";
            else if (error instanceof WebClientRequestException || error instanceof IOException) reason = leg + "_connection_failure";
            else reason = "gateway_orchestration_error";
            boolean timeout = overall || error instanceof TimeoutException;
            boolean connection = reason.endsWith("_connection_failure");
            boolean inFlight = phase.equals("eligibility_in_flight") || phase.equals("reset_in_flight");
            Integer actualStatus = timeout ? (overall && phase.equals("reset_post_response") ? status : null)
                    : connection ? null : status;
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("reason", reason);
            fields.put("leg", leg);
            fields.put("httpStatus", actualStatus);
            fields.put("timeoutScope", timeout ? (overall ? "overall" : "per-leg") : null);
            fields.put("elapsedMillis", timeout ? (nanoClock.getAsLong() - (overall ? overallStart : legStart)) / 1_000_000L : null);
            fields.put("attemptedTarget", (connection || (timeout && (!overall || inFlight))) && target != null ? target.toString() : null);
            fields.put("overallTimeoutPhase", overall ? phase : null);
            Throwable ownError = error instanceof DemoLoginResetOrchestrator.OwnCodeFailure ? error.getCause() : error;
            fields.put("exceptionClass", reason.equals("gateway_orchestration_error") ? ownError.getClass().getName() : null);
            fields.put("internalApiKeyConfigured", keyConfigured);
            fields.put("originVerifyRequired", originRequired);
            fields.put("eligibilityDispatchAttempted", eligibilityDispatched);
            fields.put("resetDispatchAttempted", resetDispatched);
            fields.put("internalApiKeyAttached", keyAttached);
            fields.put("originVerifyHeaderAttached", originAttached);
            if (error instanceof DemoLoginResetClient.HttpStatusFailure failure && leg.equals("reset") && failure.status().value() == 409) {
                fields.put("observedVersion", observedVersion);
                fields.put("submittedExpectedVersion", submittedVersion);
                fields.put("downstreamCurrentVersion", failure.currentVersion());
                fields.put("selfCallCount", resetCount);
            }
            return fields;
        }
    }
}
