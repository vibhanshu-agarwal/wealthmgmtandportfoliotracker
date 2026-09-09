package com.wealth.gateway;

import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.wealth.gateway.DemoLoginResetOrchestratorTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Real inbound HTTP observations with real AuthController/orchestrator/client, an outer transport
 * fixture only. Full gateway route traversal and real portfolio commits belong to Task 4. */
@SpringBootTest(classes = DemoLoginResetTracingIntegrationTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=task3-test", "spring.cloud.gateway.server.webflux.enabled=false",
        "management.tracing.export.enabled=false", "management.otlp.metrics.export.enabled=false",
        "management.health.redis.enabled=false"})
@AutoConfigureTracing(export = false)
class DemoLoginResetTracingIntegrationTest {
    @LocalServerPort int port;
    @Autowired AtomicReference<Fixture> scenario;
    @Autowired io.micrometer.tracing.propagation.Propagator propagator;
    final DemoLoginResetOrchestratorTest assertions = new DemoLoginResetOrchestratorTest();
    WebTestClient http;
    String expectedTrace;
    int traceSequence;

    @BeforeEach void setup() {
        assertions.appender.start();
        ((Logger) LoggerFactory.getLogger(DemoLoginResetDiagnostics.class)).addAppender(assertions.appender);
        http = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }
    @AfterEach void cleanup() {
        ((Logger) LoggerFactory.getLogger(DemoLoginResetDiagnostics.class)).detachAppender(assertions.appender);
    }

    @Test
    void tracingHarnessExtractsTheSuppliedW3cParent() {
        var span = propagator.extract("00-" + TRACE + "-1234567890abcdef-01",
                (String carrier, String key) -> key.equalsIgnoreCase("traceparent") ? carrier : null).start();
        try { assertThat(span.context().traceId()).isEqualTo(TRACE); }
        finally { span.end(); }
    }

    @Test
    void oversizedEligibilityBodyIsAResponseShapeFailureWithTheOriginalInboundTrace() {
        // This valid JSON exceeds the real String decoder's aggregation limit. If aggregation
        // were bypassed, its ordinary demo portfolio would otherwise be eligible for reset.
        String oversized = PORTFOLIO.replace("\"version\":71", "\"version\":71,\"metadata\":\""
                + "x".repeat(300_000) + "\"");
        Fixture f = new Fixture();
        f.eligibility = r -> Mono.just(response(200, oversized));
        assertUnprocessableEligibility(f);
    }

    @Test
    void eligibilityBodyPublisherFailuresCannotMasqueradeAsGatewayOrTimeoutDefects() {
        for (Exception failure : List.of(new java.io.IOException("private body failure"),
                new IllegalStateException("private body failure"),
                new java.util.concurrent.TimeoutException("private body failure"))) {
            Fixture f = new Fixture();
            f.eligibility = r -> Mono.just(org.springframework.web.reactive.function.client.ClientResponse
                    .create(org.springframework.http.HttpStatus.OK).header("Content-Type", "application/json")
                    .body(reactor.core.publisher.Flux.error(failure)).build());
            assertUnprocessableEligibility(f);
        }
    }

    private void assertUnprocessableEligibility(Fixture f) {
        request(f);
        var event = assertions.event("eligibility_shape_failure", "replica-token", expectedTrace);
        assertions.bothLegs(event, f, true, false, true, true);
        assertThat(f.requests).singleElement().satisfies(r -> {
            assertThat(r.method().name()).isEqualTo("GET");
            assertThat(r.url()).isEqualTo(GET);
        });
        assertThat(event).containsEntry("leg", "eligibility").containsEntry("httpStatus", 200)
                .containsEntry("timeoutScope", null).containsEntry("elapsedMillis", null)
                .containsEntry("attemptedTarget", null).containsEntry("overallTimeoutPhase", null)
                .containsEntry("exceptionClass", null);
        assertThat(assertions.appender.list.getFirst().getFormattedMessage()).doesNotContain("private body failure");
    }

    @Test
    void everyFailureFamilyKeepsTheInboundTraceAndOriginalHttpLoginResponse() {
        // The fixtures induce network outcomes; neither the orchestrator nor its diagnostics are mocked.
        for (String reason : List.of("eligibility_connection_failure", "reset_connection_failure",
                "eligibility_shape_failure", "reset_key_not_configured", "eligibility_timeout", "reset_timeout",
                "eligibility_construction", "reset_construction", "post_response_construction")) {
            Fixture f = new Fixture();
            f.perLeg = Duration.ofMillis(200);
            f.overall = Duration.ofSeconds(3);
            switch (reason) {
                case "eligibility_connection_failure" -> f.eligibility = r -> Mono.error(new ConnectException("private"));
                case "reset_connection_failure" -> f.reset = r -> Mono.error(new ConnectException("private"));
                case "eligibility_shape_failure" -> f.eligibility = r -> Mono.just(response(200, "[]"));
                case "reset_key_not_configured" -> f.key = "";
                case "eligibility_timeout" -> f.eligibility = r -> Mono.never();
                case "reset_timeout" -> f.reset = r -> Mono.never();
                case "eligibility_construction" -> when(f.targets.eligibilityTarget()).thenThrow(new IllegalStateException("private"));
                case "reset_construction" -> when(f.targets.resetTarget()).thenThrow(new IllegalStateException("private"));
                case "post_response_construction" -> f.completion = r -> { throw new IllegalStateException("private"); };
            }
            request(f);
            var event = assertions.event(reason.endsWith("construction") ? "gateway_orchestration_error" : reason,
                    "replica-token", expectedTrace);
            boolean get = !reason.equals("eligibility_construction");
            boolean post = reason.equals("reset_connection_failure") || reason.equals("reset_timeout")
                    || reason.equals("post_response_construction");
            assertions.bothLegs(event, f, get, post, !reason.equals("reset_key_not_configured"), true);
            assertThat(f.requests).hasSize(post ? 2 : get ? 1 : 0);
            assertThat(event).containsEntry("httpStatus", reason.equals("eligibility_shape_failure")
                    || reason.equals("post_response_construction") ? 200 : null);
            if (reason.endsWith("_timeout")) {
                assertThat(event).containsEntry("timeoutScope", "per-leg").containsEntry("elapsedMillis", 123L);
            }
            if (reason.endsWith("_timeout") || reason.endsWith("_connection_failure")) {
                assertThat(event).containsEntry("attemptedTarget", (post ? POST : GET).toString());
            }
        }
    }

    @Test
    void every4xxAnd5xxOnBothLegsHasOneCorrectlyCorrelatedRenderedEventAndNoBrowserError() {
        for (boolean reset : List.of(false, true)) {
            for (int status = 400; status < 600; status++) {
                Fixture f = new Fixture();
                final int code = status;
                if (reset) f.reset = r -> Mono.just(response(code, "{\"currentVersion\":97}"));
                else f.eligibility = r -> Mono.just(response(code, ""));
                request(f);
                var event = assertions.event(reset ? "reset_non_2xx_status" : "eligibility_non_2xx_status",
                        "replica-token", expectedTrace);
                assertions.bothLegs(event, f, true, reset, true, true);
                assertThat(event).containsEntry("httpStatus", code);
                assertThat(f.requests).hasSize(reset ? 2 : 1);
            }
        }
    }

    @Test
    void everyOverallPhasePreservesTheInboundTraceAcrossTheDeadlineScheduler() {
        for (String phase : List.of("eligibility_pre_dispatch", "eligibility_in_flight", "between_legs",
                "reset_in_flight", "reset_post_response")) {
            Fixture f = new Fixture();
            f.overall = Duration.ofMillis(200);
            f.perLeg = Duration.ofSeconds(2);
            if (phase.equals("eligibility_pre_dispatch")) when(f.targets.eligibilityTarget()).thenReturn(Mono.never());
            if (phase.equals("eligibility_in_flight")) f.eligibility = r -> Mono.never();
            if (phase.equals("between_legs")) when(f.targets.resetTarget()).thenReturn(Mono.never());
            if (phase.equals("reset_in_flight")) f.reset = r -> Mono.never();
            if (phase.equals("reset_post_response")) f.completion = r -> Mono.never();
            request(f);
            var event = assertions.event("overall_timeout", "replica-token", expectedTrace);
            assertions.bothLegs(event, f, !phase.equals("eligibility_pre_dispatch"), phase.startsWith("reset_"), true, true);
            long elapsedMillis = switch (phase) {
                case "eligibility_pre_dispatch" -> 123L;
                case "eligibility_in_flight" -> 246L;
                case "between_legs" -> 369L;
                case "reset_in_flight" -> 492L;
                case "reset_post_response" -> 615L;
                default -> throw new IllegalStateException("unexpected phase " + phase);
            };
            assertThat(event).containsEntry("overallTimeoutPhase", phase).containsEntry("timeoutScope", "overall")
                    .containsEntry("elapsedMillis", elapsedMillis)
                    .containsEntry("httpStatus", phase.equals("reset_post_response") ? 200 : null)
                    .containsEntry("attemptedTarget", phase.equals("eligibility_in_flight") ? GET.toString()
                            : phase.equals("reset_in_flight") ? POST.toString() : null);
            assertThat(f.requests).hasSize(phase.startsWith("reset_") ? 2 : phase.equals("eligibility_pre_dispatch") ? 0 : 1);
        }
    }

    private void request(Fixture f) {
        assertions.appender.list.clear();
        scenario.set(f);
        expectedTrace = String.format("%032x", ++traceSequence);
        http.post().uri("/api/auth/login").header("traceparent", "00-" + expectedTrace + "-1234567890abcdef-01")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.token").isEqualTo("jwt").jsonPath("$.userId").isEqualTo(DemoLoginResetClient.DEMO_USER_ID)
                .jsonPath("$.email").isEqualTo("demo").jsonPath("$.name").isEqualTo("Demo");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class,
            excludeName = "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration")
    static class Config {
        // Boot 4.1 ties its default propagator to export. Keep exports disabled and supply
        // only the standard test propagator, matching the repository's portfolio tracing ITs.
        @Bean io.opentelemetry.context.propagation.TextMapPropagator testW3cPropagator() {
            return io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance();
        }
        @Bean AtomicReference<Fixture> scenario() { return new AtomicReference<>(); }
        @Bean RequestHarness requestHarness(AtomicReference<Fixture> scenario) { return new RequestHarness(scenario); }
        @Bean SecurityWebFilterChain security(ServerHttpSecurity http) {
            return http.csrf(ServerHttpSecurity.CsrfSpec::disable).authorizeExchange(a -> a.anyExchange().permitAll()).build();
        }
    }
    @RestController
    static class RequestHarness {
        private final AtomicReference<Fixture> scenario;
        RequestHarness(AtomicReference<Fixture> scenario) { this.scenario = scenario; }
        @PostMapping("/api/auth/login")
        Mono<org.springframework.http.ResponseEntity<Object>> login() {
            // Fixture configuration varies per request; the invoked handler and all optional-reset
            // code are production instances. No manual trace/MDC/ContextView bridge is installed.
            return scenario.get().login();
        }
    }
}
