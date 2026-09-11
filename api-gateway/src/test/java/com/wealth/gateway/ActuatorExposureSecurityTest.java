package com.wealth.gateway;

import com.wealth.gateway.auth.AuthenticationService;
import com.wealth.gateway.auth.SignupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;

/**
 * Pins the authoritative guard on actuator exposure.
 *
 * <p>The gateway is internet-facing. It previously ran with
 * {@code management.endpoints.web.exposure.include: "*"} and {@code /actuator/**} {@code permitAll()},
 * so {@code env}, {@code beans}, {@code configprops}, {@code mappings}, {@code threaddump},
 * {@code heapdump} and the Spring Cloud Gateway routes endpoint were readable by anyone on the
 * internet, and {@code loggers}, {@code refresh} and route insertion were writable by
 * unauthenticated POST.
 *
 * <p>Narrowing exposure fixes that, but exposure is environment-overridable: relaxed binding means
 * {@code MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=*} silently restores the hole. The durable guard
 * is therefore {@link SecurityConfig}, not the exposure list. This class forces exposure back to
 * {@code "*"} so every endpoint is actually registered, and asserts they are unreachable anyway.
 * That pins the guard against the full worst-case surface rather than against endpoints that
 * merely happen to be absent: with narrowed exposure a denial and a 404 are indistinguishable in
 * effect, and the test would no longer describe what it protects.
 *
 * <p>Both halves of the security decision are pinned:
 * <ul>
 *   <li>unauthenticated callers get exactly {@code 401}, not merely "not 200" — an unregistered
 *       endpoint or a handler that 500s must not satisfy the gate;</li>
 *   <li>authenticated callers get exactly {@code 403}. This is what makes {@code denyAll()}
 *       load-bearing rather than decorative: under {@code authenticated()} a signed-in demo user
 *       could read {@code env} and {@code mappings}, and every assertion here would still pass.</li>
 * </ul>
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

    /**
     * Highest-impact endpoints this application registers under wildcard exposure.
     * {@code heapdump} carries {@code AUTH_JWT_SECRET} and the datasource password;
     * {@code gateway/routes} is a proxy/SSRF primitive; {@code /actuator} is the index that
     * advertises the rest, and was the endpoint actually read from the public internet.
     */
    private static final List<String> DENIED_READS = List.of(
            "/actuator",
            "/actuator/env",
            "/actuator/beans",
            "/actuator/configprops",
            "/actuator/mappings",
            "/actuator/threaddump",
            "/actuator/heapdump",
            "/actuator/loggers",
            "/actuator/conditions",
            "/actuator/scheduledtasks",
            "/actuator/metrics",
            "/actuator/sbom",
            "/actuator/gateway/routes");

    @Test
    void sensitiveEndpoints_returnUnauthorized_whenAnonymous() {
        for (String path : DENIED_READS) {
            webTestClient.get()
                    .uri(path)
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    /**
     * The decision {@code denyAll()} encodes: even a validly authenticated caller is refused.
     * Relaxing to {@code authenticated()} must fail here.
     */
    @Test
    void sensitiveEndpoints_returnForbidden_whenAuthenticated() {
        String token = TestJwtFactory.validSeedUserToken();
        for (String path : DENIED_READS) {
            webTestClient.get()
                    .uri(path)
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectStatus().isForbidden();
        }
    }

    /**
     * {@code loggers}, {@code refresh} and Gateway route insertion mutate a running instance, so
     * the write verb is asserted separately from disclosure.
     */
    @Test
    void mutatingEndpoints_rejectPost_anonymousAndAuthenticated() {
        String token = TestJwtFactory.validSeedUserToken();

        webTestClient.post().uri("/actuator/loggers/com.wealth.gateway")
                .header("Content-Type", "application/json")
                .bodyValue("{\"configuredLevel\":\"DEBUG\"}")
                .exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri("/actuator/refresh")
                .exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri("/actuator/gateway/refresh")
                .exchange().expectStatus().isUnauthorized();

        webTestClient.post().uri("/actuator/loggers/com.wealth.gateway")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .bodyValue("{\"configuredLevel\":\"DEBUG\"}")
                .exchange().expectStatus().isForbidden();
        webTestClient.post().uri("/actuator/refresh")
                .header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isForbidden();
    }

    /**
     * The permit set was widened to the whole {@code /actuator/health} subtree because a real
     * consumer reads a group sub-path: {@code docker-compose.yml:248} healthchecks this gateway on
     * {@code /actuator/health/readiness}. Asserting only {@code /actuator/health} would leave that
     * widening untested.
     *
     * <p>{@code /actuator/health/liveness} is asserted defensively rather than because the gateway
     * has a liveness consumer. The repository's only {@code /liveness} reference is
     * {@code AWS_LWA_READINESS_CHECK_PATH} in {@code infrastructure/terraform/aws/modules/compute/main.tf:311},
     * which belongs to the {@code market_data} Lambda, not to this service.
     */
    @Test
    void healthSubtree_remainsPubliclyReadable() {
        for (String path : List.of(
                "/actuator/health",
                "/actuator/health/readiness",
                "/actuator/health/liveness")) {
            webTestClient.get()
                    .uri(path)
                    .exchange()
                    .expectStatus().isOk();
        }
    }
}
