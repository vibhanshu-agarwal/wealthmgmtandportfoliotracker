package com.wealth.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway boot/contract gate (Task 10.3 / Property 2).
 *
 * <p>F2 safety mechanism: {@code api-gateway} must start cleanly on Boot 4.1 +
 * Spring Cloud {@code 2025.1.2} with routing, JWT validation, and rate-limit wiring intact.
 * Redis-backed {@code RequestRateLimiter} behaviour is covered in {@link RateLimitingIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("aws")
@TestPropertySource(properties = {
        "auth.jwt.secret=test-secret-for-integration-tests-min-32-chars",
        "app.auth.email=dev@localhost.local",
        "app.auth.password=password",
        "app.auth.user-id=user-001",
        "app.auth.name=Development User",
        "management.health.redis.enabled=false",
        "management.tracing.export.enabled=false",
        "management.otlp.metrics.export.enabled=false"
})
class GatewayBootContractTest {

    private static final Set<String> EXPECTED_ROUTE_IDS = Set.of(
            "portfolio-service",
            "market-data-service",
            "insight-service",
            "insight-chat",
            "internal-portfolio-seed",
            "internal-market-data-seed",
            "internal-insight-seed");

    @LocalServerPort
    int port;

    @Autowired
    RouteDefinitionLocator routeDefinitionLocator;

    @Autowired
    ReactiveJwtDecoder reactiveJwtDecoder;

    @Autowired
    KeyResolver userOrIpKeyResolver;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    void contextLoads_withRoutingJwtAndRateLimitBeans() {
        assertThat(reactiveJwtDecoder).isNotNull();
        assertThat(userOrIpKeyResolver).isNotNull();

        List<String> routeIds = routeDefinitionLocator.getRouteDefinitions()
                .map(RouteDefinition::getId)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(routeIds).containsAll(EXPECTED_ROUTE_IDS);
    }

    @Test
    void localProfile_declaresRedisBackedRequestRateLimiter() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("application-local.yml")) {
            assertThat(in).isNotNull();
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml).contains("RequestRateLimiter");
            assertThat(yaml).contains("userOrIpKeyResolver");
        }
    }

    @Test
    void actuatorHealth_isUpWithoutAuthentication() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void protectedRoute_withoutJwt_returnsUnauthorized() {
        webTestClient.get()
                .uri("/api/portfolio/summary")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedRoute_withValidJwt_isNotUnauthorized() {
        String token = TestJwtFactory.validSeedUserToken();
        webTestClient.get()
                .uri("/api/portfolio/summary")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(401));
    }

    @Test
    void authLogin_issuesTokenViaGatewayController() {
        webTestClient.post()
                .uri("/api/auth/login")
                .bodyValue("""
                        {"email":"dev@localhost.local","password":"password"}
                        """)
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.token").isNotEmpty();
    }
}
