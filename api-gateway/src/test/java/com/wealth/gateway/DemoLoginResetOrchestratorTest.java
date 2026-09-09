package com.wealth.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wealth.gateway.auth.LoginResponse;
import io.micrometer.context.ContextRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoLoginResetOrchestratorTest {
    static final String TRACE = "1234567890abcdef1234567890abcdef";
    static final URI GET = URI.create("http://localhost:18081/api/portfolio");
    static final URI POST = URI.create("http://localhost:18081/api/internal/portfolio/demo-reset");
    static final String PORTFOLIO = """
            [{"id":"00000000-0000-0000-0000-000000000002","userId":"00000000-0000-0000-0000-0000000d3110",
              "updatedAt":"2026-09-06T00:00:00Z","version":71}]
            """;
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void capture() {
        appender.start();
        ((Logger) LoggerFactory.getLogger(DemoLoginResetDiagnostics.class)).addAppender(appender);
        ContextRegistry.getInstance().registerThreadLocalAccessor("task3.trace", () -> MDC.get("traceId"),
                value -> MDC.put("traceId", value), () -> MDC.remove("traceId"));
        Hooks.enableAutomaticContextPropagation();
        MDC.put("traceId", TRACE);
    }

    @AfterEach
    void cleanup() {
        ((Logger) LoggerFactory.getLogger(DemoLoginResetDiagnostics.class)).detachAppender(appender);
        ContextRegistry.getInstance().removeThreadLocalAccessor("task3.trace");
        MDC.clear();
    }

    @Test
    void ordinaryUsersNeverDispatchAndRepeatedSubscriptionsDoNotShareAttemptState() {
        Fixture f = new Fixture();
        f.userId = "ordinary-user";
        StepVerifier.create(f.run()).verifyComplete();
        assertThat(f.requests).isEmpty();
        assertThat(appender.list).isEmpty();
        f.userId = DemoLoginResetClient.DEMO_USER_ID;
        Mono<Void> login = f.run();
        StepVerifier.create(login).verifyComplete();
        StepVerifier.create(login).verifyComplete();
        assertThat(f.requests).extracting(r -> r.method().name()).containsExactly("GET", "POST", "GET", "POST");
        assertThat(appender.list).isEmpty();
    }

    @Test
    void diagnosticsAreVisibleWithTheProductionMessageOnlyConsolePattern() {
        Fixture f = new Fixture();
        f.reset = r -> Mono.just(response(409, "{\"currentVersion\":97}"));
        StepVerifier.create(f.run()).verifyComplete();
        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("event=demo_reset_self_call_skipped", "reason=reset_non_2xx_status", "httpStatus=409",
                        "observedVersion=71", "submittedExpectedVersion=71", "downstreamCurrentVersion=97", "selfCallCount=1");
    }

    @Test
    void eligibleAndIneligibleObservationsHaveExactCallCardinalityAndNoSkip() {
        Fixture f = new Fixture();
        StepVerifier.create(f.run()).verifyComplete();
        assertThat(f.requests).extracting(r -> r.method().name()).containsExactly("GET", "POST");
        assertThat(appender.list).isEmpty();
        f = new Fixture();
        f.eligibility = request -> Mono.just(response(200, PORTFOLIO.replace("00:00:00", "00:01:00")));
        StepVerifier.create(f.run()).verifyComplete();
        assertThat(f.requests).extracting(r -> r.method().name()).containsExactly("GET");
        assertThat(appender.list).isEmpty();
    }

    @Test
    void allNon2xxStatusesOnEitherLegFailOpenWithExactStatusAndBothLegsEvidence() {
        for (boolean reset : List.of(false, true)) {
            for (int status = 300; status < 600; status++) {
                appender.list.clear();
                Fixture f = new Fixture();
                final int code = status;
                Function<ClientRequest, Mono<ClientResponse>> failure = r -> Mono.just(response(code,
                        "{\"currentVersion\":97}"));
                if (reset) f.reset = failure; else f.eligibility = failure;
                StepVerifier.create(f.run()).verifyComplete();
                Map<String, Object> event = event(reset ? "reset_non_2xx_status" : "eligibility_non_2xx_status");
                bothLegs(event, f, true, reset, true, true);
                assertThat(event).containsEntry("leg", reset ? "reset" : "eligibility")
                        .containsEntry("httpStatus", status).containsEntry("timeoutScope", null)
                        .containsEntry("elapsedMillis", null).containsEntry("attemptedTarget", null);
                assertThat(f.requests).hasSize(reset ? 2 : 1);
                if (reset && status == 409) {
                    assertThat(event).containsEntry("observedVersion", 71L)
                            .containsEntry("submittedExpectedVersion", 71L)
                            .containsEntry("downstreamCurrentVersion", 97L).containsEntry("selfCallCount", 1);
                }
            }
        }
    }

    @Test
    void malformedAndAmbiguousEligibilityNeverResetAndKeepReceivedStatus() {
        for (String body : List.of("[]", "{}", "not-json", "", PORTFOLIO.replace("71", "null"),
                "[" + PORTFOLIO.strip().substring(1, PORTFOLIO.strip().length() - 1) + ","
                        + PORTFOLIO.strip().substring(1, PORTFOLIO.strip().length() - 1) + "]")) {
            appender.list.clear();
            Fixture f = new Fixture();
            f.eligibility = r -> Mono.just(response(200, body));
            StepVerifier.create(f.run()).verifyComplete();
            Map<String, Object> event = event("eligibility_shape_failure");
            bothLegs(event, f, true, false, true, true);
            assertThat(event).containsEntry("httpStatus", 200).containsEntry("leg", "eligibility");
            assertThat(f.requests).hasSize(1);
        }
    }

    @Test
    void transportFailuresAreNeverAttributedToGatewayCodeAndIdentifyActualTarget() {
        for (boolean reset : List.of(false, true)) {
            appender.list.clear();
            Fixture f = new Fixture();
            Function<ClientRequest, Mono<ClientResponse>> failure = r -> Mono.error(new java.net.ConnectException("secret"));
            if (reset) f.reset = failure; else f.eligibility = failure;
            StepVerifier.create(f.run()).verifyComplete();
            Map<String, Object> event = event(reset ? "reset_connection_failure" : "eligibility_connection_failure");
            bothLegs(event, f, true, reset, true, true);
            assertThat(event).containsEntry("httpStatus", null)
                    .containsEntry("attemptedTarget", (reset ? POST : GET).toString());
            assertThat(event.toString()).doesNotContain("secret");
        }
    }

    @Test
    void perLegTimeoutsMeasureTheirDispatchBracketRatherThanConfiguredDuration() {
        for (boolean reset : List.of(false, true)) {
            appender.list.clear();
            Fixture f = new Fixture();
            if (reset) f.reset = r -> Mono.never(); else f.eligibility = r -> Mono.never();
            StepVerifier.withVirtualTime(f::run).thenAwait(Duration.ofSeconds(3)).verifyComplete();
            Map<String, Object> event = event(reset ? "reset_timeout" : "eligibility_timeout");
            bothLegs(event, f, true, reset, true, true);
            assertThat(event).containsEntry("timeoutScope", "per-leg").containsEntry("elapsedMillis", 123L)
                    .containsEntry("httpStatus", null).containsEntry("attemptedTarget", (reset ? POST : GET).toString());
        }
    }

    @Test
    void configuredButSuppressedHeadersAreReportedFromFinalRequestsForBothLegs() {
        for (boolean reset : List.of(false, true)) {
            appender.list.clear();
            Fixture f = new Fixture();
            f.suppressedHeader = reset ? "X-Internal-Api-Key" : "X-Origin-Verify";
            if (reset) f.reset = r -> Mono.just(response(403, ""));
            else f.eligibility = r -> Mono.just(response(403, ""));
            StepVerifier.create(f.run()).verifyComplete();
            Map<String, Object> event = event(reset ? "reset_non_2xx_status" : "eligibility_non_2xx_status");
            bothLegs(event, f, true, reset, true, true);
            assertThat(event).containsEntry(reset ? "internalApiKeyAttached" : "originVerifyHeaderAttached", false);
        }
    }

    @Test
    void blankResetKeyStopsBeforeDispatchButBlankOriginAllowsHealthyResetOrUnrelatedFailure() {
        Fixture f = new Fixture();
        f.key = " ";
        StepVerifier.create(f.run()).verifyComplete();
        bothLegs(event("reset_key_not_configured"), f, true, false, false, true);
        assertThat(event("reset_key_not_configured")).containsEntry("httpStatus", null);
        assertThat(f.requests).hasSize(1);
        appender.list.clear();
        f = new Fixture();
        f.origin = "";
        StepVerifier.create(f.run()).verifyComplete();
        assertThat(appender.list).isEmpty();
        assertThat(f.requests).hasSize(2);
        f = new Fixture();
        f.origin = "";
        f.replica = "";
        f.eligibility = r -> Mono.just(response(500, ""));
        StepVerifier.create(f.run()).verifyComplete();
        Map<String, Object> event = event("eligibility_non_2xx_status", "");
        bothLegs(event, f, true, false, true, false);
        assertThat(event).containsEntry("httpStatus", 500);
        assertThat(f.requests.getFirst().headers().containsHeader("X-Origin-Verify")).isFalse();
    }

    @Test
    void gatewayOwnedSeamErrorsAreClassifiedByProvenanceEvenWhenTheyLookLikeNetworkErrors() {
        for (String phase : List.of("eligibility", "reset", "post")) {
            for (Exception failure : List.of(new java.io.IOException("private"), new java.util.concurrent.TimeoutException("private"))) {
                appender.list.clear();
                Fixture f = new Fixture();
                if (phase.equals("eligibility")) when(f.targets.eligibilityTarget()).thenReturn(Mono.error(failure));
                if (phase.equals("reset")) when(f.targets.resetTarget()).thenReturn(Mono.error(failure));
                if (phase.equals("post")) f.completion = r -> Mono.error(failure);
                StepVerifier.create(f.run()).verifyComplete();
                var event = event("gateway_orchestration_error");
                bothLegs(event, f, !phase.equals("eligibility"), phase.equals("post"), true, true);
                assertThat(event).containsEntry("exceptionClass", failure.getClass().getName())
                        .containsEntry("timeoutScope", null).containsEntry("elapsedMillis", null)
                        .containsEntry("httpStatus", phase.equals("post") ? 200 : null);
            }
        }
    }

    @Test
    void synchronousTargetFailuresOnEachLegAndPostResponseHandlerRemainFailOpenAndSanitized() {
        for (String phase : List.of("eligibility", "reset", "post")) {
            appender.list.clear();
            Fixture f = new Fixture();
            if (phase.equals("eligibility")) when(f.targets.eligibilityTarget()).thenThrow(new IllegalStateException("secret"));
            if (phase.equals("reset")) when(f.targets.resetTarget()).thenThrow(new IllegalStateException("secret"));
            if (phase.equals("post")) f.completion = r -> { throw new IllegalStateException("secret"); };
            StepVerifier.create(f.run()).verifyComplete();
            Map<String, Object> event = event("gateway_orchestration_error");
            bothLegs(event, f, !phase.equals("eligibility"), phase.equals("post"), true, true);
            assertThat(event).containsEntry("leg", phase.equals("eligibility") ? "eligibility" : "reset")
                    .containsEntry("httpStatus", phase.equals("post") ? 200 : null)
                    .containsEntry("exceptionClass", "java.lang.IllegalStateException");
            assertThat(event.toString()).doesNotContain("secret");
        }
    }

    @Test
    void allOverallTimeoutPhasesHaveExactDispatchPairsTargetsStatusAndEntryBracket() {
        for (String phase : List.of("eligibility_pre_dispatch", "eligibility_in_flight", "between_legs",
                "reset_in_flight", "reset_post_response")) {
            appender.list.clear();
            Fixture f = new Fixture();
            f.overall = Duration.ofSeconds(1);
            if (phase.equals("eligibility_pre_dispatch")) when(f.targets.eligibilityTarget()).thenReturn(Mono.never());
            if (phase.equals("eligibility_in_flight")) f.eligibility = r -> Mono.never();
            if (phase.equals("between_legs")) when(f.targets.resetTarget()).thenReturn(Mono.never());
            if (phase.equals("reset_in_flight")) {
                f.eligibility = r -> Mono.delay(Duration.ofMillis(100)).thenReturn(response(200, PORTFOLIO));
                f.reset = r -> Mono.never();
            }
            if (phase.equals("reset_post_response")) f.completion = r -> Mono.never();
            StepVerifier.withVirtualTime(f::run).thenAwait(Duration.ofSeconds(2)).verifyComplete();
            Map<String, Object> event = event("overall_timeout");
            boolean get = !phase.equals("eligibility_pre_dispatch");
            boolean post = phase.startsWith("reset_");
            bothLegs(event, f, get, post, true, true);
            assertThat(event).containsEntry("leg", "overall").containsEntry("timeoutScope", "overall")
                    .containsEntry("overallTimeoutPhase", phase)
                    .containsEntry("httpStatus", phase.equals("reset_post_response") ? 200 : null)
                    .containsEntry("elapsedMillis", post ? 369L : get ? 246L : 123L)
                    .containsEntry("attemptedTarget", phase.equals("eligibility_in_flight") ? GET.toString()
                            : phase.equals("reset_in_flight") ? POST.toString() : null);
        }
    }

    @Test
    void cancellationBeforeDispatchPreventsLateRequestsAfterTargetIsReleasedAndWindowCloses() {
        for (boolean between : List.of(false, true)) {
            appender.list.clear();
            Fixture f = new Fixture();
            f.overall = Duration.ofSeconds(1);
            Sinks.One<URI> target = Sinks.one();
            if (between) when(f.targets.resetTarget()).thenReturn(target.asMono());
            else when(f.targets.eligibilityTarget()).thenReturn(target.asMono());
            StepVerifier.withVirtualTime(() -> f.run().then(Mono.delay(Duration.ofSeconds(3))))
                    .thenAwait(Duration.ofSeconds(2)).then(() -> target.tryEmitValue(between ? POST : GET))
                    .thenAwait(Duration.ofSeconds(3)).expectNext(0L).verifyComplete();
            assertThat(f.requests).hasSize(between ? 1 : 0);
            assertThat(f.requests).noneMatch(r -> r.method().name().equals("POST"));
            assertThat(event("overall_timeout")).containsEntry("resetDispatchAttempted", false);
        }
    }

    Map<String, Object> event(String reason) { return event(reason, "replica-token"); }
    Map<String, Object> event(String reason, String replica) {
        return event(reason, replica, TRACE);
    }
    Map<String, Object> event(String reason, String replica, String trace) {
        assertThat(appender.list).hasSize(1);
        Map<String, Object> fields = new LinkedHashMap<>();
        appender.list.getFirst().getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
        fields.forEach((key, value) -> assertThat(appender.list.getFirst().getFormattedMessage())
                .contains(key + "=" + ("".equals(value) ? "\"\"" : String.valueOf(value))));
        assertThat(fields).containsEntry("event", "demo_reset_self_call_skipped")
                .containsEntry("reason", reason).containsEntry("traceId", trace).containsEntry("replicaToken", replica);
        return fields;
    }
    void bothLegs(Map<String, Object> event, Fixture f, boolean get, boolean post, boolean key, boolean origin) {
        assertThat(event).containsEntry("eligibilityDispatchAttempted", get).containsEntry("resetDispatchAttempted", post)
                .containsEntry("internalApiKeyConfigured", key).containsEntry("originVerifyRequired", origin)
                .containsEntry("originVerifyHeaderAttached", get && origin
                        ? f.requests.getFirst().headers().containsHeader("X-Origin-Verify") : null)
                .containsEntry("internalApiKeyAttached", post
                        ? f.requests.getLast().headers().containsHeader("X-Internal-Api-Key") : null);
    }
    static ClientResponse response(int status, String body) {
        return ClientResponse.create(HttpStatusCode.valueOf(status)).header("Content-Type", "application/json").body(body).build();
    }
    static class Fixture {
        final List<ClientRequest> requests = new CopyOnWriteArrayList<>();
        final GatewayLoopbackTargetProvider targets = mock(GatewayLoopbackTargetProvider.class);
        Function<ClientRequest, Mono<ClientResponse>> eligibility = r -> Mono.just(response(200, PORTFOLIO));
        Function<ClientRequest, Mono<ClientResponse>> reset = r -> Mono.just(response(200, "{}"));
        Function<DemoLoginResetClient.DemoLoginResetResult, Mono<Void>> completion = r -> Mono.empty();
        String key = "key", origin = "origin", replica = "replica-token", suppressedHeader;
        String userId = DemoLoginResetClient.DEMO_USER_ID;
        Duration overall = Duration.ofSeconds(4);
        Duration perLeg = Duration.ofSeconds(2);
        ObservationRegistry observationRegistry = ObservationRegistry.NOOP;
        Fixture() {
            when(targets.eligibilityTarget()).thenReturn(Mono.just(GET));
            when(targets.resetTarget()).thenReturn(Mono.just(POST));
        }
        Mono<Void> run() {
            return login().doOnNext(result -> {
                assertThat(result.getStatusCode().value()).isEqualTo(200);
                assertThat(result.getBody()).isEqualTo(new LoginDtos.LoginResponse("jwt", userId, "demo", "Demo"));
            }).then().contextCapture();
        }
        Mono<org.springframework.http.ResponseEntity<Object>> login() {
            var authentication = mock(com.wealth.gateway.auth.AuthenticationService.class);
            var request = new LoginDtos.LoginRequest("demo", "pw");
            when(authentication.authenticate(request)).thenReturn(Mono.just(new LoginResponse("jwt", userId, "demo", "Demo")));
            return new AuthController(authentication, mock(com.wealth.gateway.auth.SignupService.class), orchestrator()).login(request);
        }
        DemoLoginResetOrchestrator orchestrator() {
            var properties = new DemoLoginResetProperties(Duration.ofMinutes(30), perLeg, perLeg, overall);
            WebClient.Builder builder = WebClient.builder().observationRegistry(observationRegistry).exchangeFunction(r -> {
                requests.add(r);
                return r.method().name().equals("GET") ? eligibility.apply(r) : reset.apply(r);
            });
            if (suppressedHeader != null) builder.filter((r, next) -> next.exchange(ClientRequest.from(r)
                    .headers(h -> h.remove(suppressedHeader)).build()));
            var internal = new InternalApiKeyProvider(key);
            var originProvider = new CloudFrontOriginSecretProvider(origin);
            var transport = new DemoLoginResetClient(builder, targets, internal, originProvider, properties,
                    Clock.fixed(Instant.parse("2026-09-06T00:31:00Z"), ZoneOffset.UTC), new ObjectMapper());
            var token = mock(ReplicaTokenProvider.class);
            when(token.replicaToken()).thenReturn(replica);
            AtomicLong nanos = new AtomicLong();
            return new DemoLoginResetOrchestrator(transport, properties, internal, originProvider,
                    new DemoLoginResetDiagnostics(token), () -> nanos.getAndAdd(123_000_000L), completion::apply);
        }
    }
}
