package com.wealth.gateway;

import com.wealth.gateway.auth.AuthenticationService;
import com.wealth.gateway.auth.SignupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the authoritative guard on actuator exposure.
 *
 * <p>The gateway is internet-facing. It previously ran with
 * {@code management.endpoints.web.exposure.include: "*"} and {@code /actuator/**} {@code permitAll()},
 * which published {@code env}, {@code beans}, {@code configprops}, {@code mappings},
 * {@code threaddump}, {@code conditions} and {@code scheduledtasks} to anyone on the internet, and
 * left {@code loggers} and {@code refresh} writable by unauthenticated POST.
 *
 * <p>Narrowing exposure fixes that, but exposure is an <em>environment-overridable</em> property:
 * setting {@code MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=*} silently restores the hole. The
 * durable guard is therefore {@link SecurityConfig}, not the exposure list.
 *
 * <p>This class deliberately forces exposure back to {@code "*"} so every endpoint is registered,
 * and asserts they are still unreachable. A test that ran with narrowed exposure would pass on a
 * 404 and prove nothing about the security layer — it would measure a different thing than it
 * claims to. Reverting the {@code denyAll()} in {@link SecurityConfig} must fail this class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("aws")
@TestPropertySource(properties = {
        "auth.jwt.secret=test-secret-for-integration-tests-min-32-chars",
        "management.health.redis.enabled=false",
        "management.tracing.export.enabled=false",
        "management.otlp.metrics.export.enabled=false",
        // Worst case on purpose: everything registered, so only security can deny it.
        "management.endpoints.web.exposure.include=*"
})
class ActuatorExposureSecurityTest {

    /**
     * Mirrors {@link GatewayBootContractTest}: these beans only exist where
     * {@code GatewayAuthDataConfig} activates, never under the "aws" profile, so they are mocked
     * purely to satisfy {@code AuthController}'s wiring.
     */
    @MockitoBean
    AuthenticationService authenticationService;

    @MockitoBean
    SignupService signupService;

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static final List<String> SENSITIVE_ENDPOINTS = List.of(
            "/actuator/env",
            "/actuator/beans",
            "/actuator/configprops",
            "/actuator/mappings",
            "/actuator/threaddump",
            "/actuator/loggers",
            "/actuator/conditions",
            "/actuator/scheduledtasks",
            "/actuator/metrics",
            "/actuator/sbom");

    @Test
    void sensitiveEndpoints_areDeniedEvenWhenFullyExposed() {
        for (String path : SENSITIVE_ENDPOINTS) {
            webTestClient.get()
                    .uri(path)
                    .exchange()
                    .expectStatus().value(status -> assertThat(status)
                            .as("%s is registered but must be denied at the security layer", path)
                            .isNotEqualTo(200));
        }
    }

    /**
     * {@code loggers} and {@code refresh} mutate a running instance. Read denial is asserted above;
     * this covers the write verb explicitly, since an accidental {@code permitAll()} would expose
     * runtime mutation rather than mere disclosure.
     */
    @Test
    void mutatingEndpoints_rejectUnauthenticatedPost() {
        webTestClient.post()
                .uri("/actuator/loggers/com.wealth.gateway")
                .header("Content-Type", "application/json")
                .bodyValue("{\"configuredLevel\":\"DEBUG\"}")
                .exchange()
                .expectStatus().value(status -> assertThat(status)
                        .as("/actuator/loggers must not accept unauthenticated POST")
                        .isNotIn(200, 204));

        webTestClient.post()
                .uri("/actuator/refresh")
                .exchange()
                .expectStatus().value(status -> assertThat(status)
                        .as("/actuator/refresh must not accept unauthenticated POST")
                        .isNotIn(200, 204));
    }

    /**
     * The fix must not break the one endpoint every consumer depends on: CI readiness polls,
     * synthetic monitoring, the Azure verification step and the B1 smoke harness all read it.
     */
    @Test
    void health_remainsPubliclyReadable() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }
}
