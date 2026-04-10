package com.wealth.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Redis-backed distributed rate limiting.
 * <p>
 * Uses a real Redis instance via Testcontainers and a low-burst capacity (3)
 * to keep the test fast. The gateway routes to non-existent upstreams — we only
 * care about the rate-limiter response (429 vs. anything else), not the proxied
 * response body.
 * <p>
 * Run via: ./gradlew :api-gateway:integrationTest
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class RateLimitingIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final int TEST_BURST_CAPACITY = 3;
    private static final String XFF = "X-Forwarded-For";
    private static final String PORTFOLIO_PATH = "/api/portfolio/holdings";
    // All rate-limit tests must carry a valid JWT — Spring Security now guards /api/** routes.
    // We use a unique sub per test to avoid cross-test bucket interference.
    private static final String VALID_TOKEN = TestJwtFactory.validSeedUserToken();

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(REDIS_PORT));
        // Rate-limiter params (replenishRate:1, burstCapacity:3) are set in
        // src/test/resources/application-local.yml to avoid Spring Cloud Gateway
        // filter-name resolution issues with array-index property overrides.
    }

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext applicationContext;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient
                .bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    // -------------------------------------------------------------------------
    // 6.1 — Context loads with Testcontainers Redis
    // -------------------------------------------------------------------------

    @Test
    void contextLoadsWithRedis() {
        assertThat(redis.isRunning()).isTrue();
        // If the context failed to load, this test would not reach this line
    }

    // -------------------------------------------------------------------------
    // 6.2 — Requests within burst capacity are not throttled
    // -------------------------------------------------------------------------

    @Test
    void requestsWithinBurstAreAllowed() {
        for (int i = 0; i < TEST_BURST_CAPACITY; i++) {
            final int requestNum = i + 1;
            webTestClient.get()
                    .uri(PORTFOLIO_PATH)
                    .header("Authorization", "Bearer " + TestJwtFactory.mint("rate-limit-user-within", Duration.ofHours(1)))
                    .header(XFF, "10.10.10.10")
                    .exchange()
                    .expectStatus().value(status ->
                            assertThat(status).as("request %d should not be rate-limited", requestNum)
                                    .isNotEqualTo(429));
        }
    }

    // -------------------------------------------------------------------------
    // 6.3 — Requests exceeding burst capacity are throttled with 429
    // -------------------------------------------------------------------------

    @Test
    void requestsExceedingBurstAreThrottled() {
        List<Integer> statuses = new ArrayList<>();
        // Use a unique sub so this test has its own rate-limit bucket
        String token = TestJwtFactory.mint("rate-limit-user-throttle", Duration.ofHours(1));

        for (int i = 0; i < TEST_BURST_CAPACITY + 5; i++) {
            webTestClient.get()
                    .uri("/api/market/prices")
                    .header("Authorization", "Bearer " + token)
                    .header(XFF, "20.20.20.20")
                    .exchange()
                    .expectStatus().value(statuses::add);
        }

        assertThat(statuses).as("at least one request should be rate-limited (429)")
                .contains(429);
    }

    // -------------------------------------------------------------------------
    // 6.4 — Different IPs have independent token buckets
    // -------------------------------------------------------------------------

    @Test
    void differentIpsHaveIndependentBuckets() {
        String tokenA = TestJwtFactory.mint("rate-limit-user-ip-a", Duration.ofHours(1));
        String tokenB = TestJwtFactory.mint("rate-limit-user-ip-b", Duration.ofHours(1));

        // Exhaust user-A's bucket
        for (int i = 0; i < TEST_BURST_CAPACITY + 3; i++) {
            webTestClient.get()
                    .uri(PORTFOLIO_PATH)
                    .header("Authorization", "Bearer " + tokenA)
                    .header(XFF, "30.30.30.30")
                    .exchange();
        }

        // user-B should still be allowed (its bucket is independent)
        webTestClient.get()
                .uri(PORTFOLIO_PATH)
                .header("Authorization", "Bearer " + tokenB)
                .header(XFF, "40.40.40.40")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).as("user-B should not be rate-limited by user-A's exhausted bucket")
                                .isNotEqualTo(429));
    }

    // -------------------------------------------------------------------------
    // 6.5 — X-RateLimit-Remaining header is present on allowed responses
    // -------------------------------------------------------------------------

    @Test
    void rateLimitHeadersPresent() {
        webTestClient.get()
                .uri("/api/insight/summary")
                .header("Authorization", "Bearer " + TestJwtFactory.mint("rate-limit-user-headers", Duration.ofHours(1)))
                .header(XFF, "50.50.50.50")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(429))
                .expectHeader().exists("X-RateLimit-Remaining");
    }
}
