package com.wealth.gateway;

import com.wealth.gateway.presence.DemoPresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full gateway-chain proof for demo session presence using signed JWTs and Testcontainers Redis.
 */
@Tag("integration")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class DemoPresenceIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final Instant FIXED_NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final String PRESENCE_PATH = "/api/presence/demo";
    private static final String ROUTED_PORTFOLIO_PATH = "/api/portfolio/holdings";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(TestContainerImages.REDIS).withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
        registry.add("spring.data.redis.timeout", () -> "3s");
        registry.add("spring.data.redis.connect-timeout", () -> "3s");
        registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);
        registry.add("app.demo-presence.ttl", () -> "150s");
    }

    @LocalServerPort
    int port;

    @Autowired
    ReactiveStringRedisTemplate redisTemplate;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        if (!redis.isRunning()) {
            redis.start();
        }
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        redisTemplate.delete(DemoPresenceService.PRESENCE_KEY).block(Duration.ofSeconds(5));
    }

    @Test
    @Order(1)
    void repeatedSameJtiCreatesOneMemberAndReturnsFalse() {
        String token = TestJwtFactory.demoUserToken(TestJwtFactory.TEST_JTI_A);

        expectPresence(token, false);
        expectPresence(token, false);

        Long cardinality = redisTemplate.opsForZSet()
                .size(DemoPresenceService.PRESENCE_KEY)
                .block();
        assertThat(cardinality).isEqualTo(1L);
    }

    @Test
    @Order(2)
    void twoDistinctDemoJtiValuesReturnTrueForBothAfterBothObserved() {
        String tokenA = TestJwtFactory.demoUserToken(TestJwtFactory.TEST_JTI_A);
        String tokenB = TestJwtFactory.demoUserToken(TestJwtFactory.TEST_JTI_B);

        expectPresence(tokenA, false);
        expectPresence(tokenB, true);
        expectPresence(tokenA, true);
        expectPresence(tokenB, true);
    }

    @Test
    @Order(3)
    void routedDemoTrafficWritesHashedMemberViaJwtAuthenticationFilter() {
        String token = TestJwtFactory.demoUserToken(TestJwtFactory.TEST_JTI_A);
        String expectedMember = DemoPresenceService.hashSessionKey(TestJwtFactory.TEST_JTI_A);

        expectRoutedPortfolio(token);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            Double score = redisTemplate.opsForZSet()
                    .score(DemoPresenceService.PRESENCE_KEY, expectedMember)
                    .block(Duration.ofSeconds(1));
            assertThat(score).isNotNull();
        });
    }

    @Test
    @Order(4)
    void routedLegacyNoJtiDemoTokenIsNotUnauthorizedAndLeavesRedisUntouched() {
        String token = TestJwtFactory.legacyNoJtiToken(DemoPresenceService.DEMO_USER_ID);

        expectRoutedPortfolio(token);

        assertThat(redisTemplate.hasKey(DemoPresenceService.PRESENCE_KEY).block()).isFalse();
    }

    @Test
    @Order(5)
    void routedLegacyBlankJtiDemoTokenIsNotUnauthorizedAndLeavesRedisUntouched() {
        String token = TestJwtFactory.legacyBlankJtiToken(DemoPresenceService.DEMO_USER_ID);

        expectRoutedPortfolio(token);

        assertThat(redisTemplate.hasKey(DemoPresenceService.PRESENCE_KEY).block()).isFalse();
    }

    @Test
    @Order(6)
    void nonDemoCallerReturnsFalseAndLeavesRedisUntouched() {
        String token = TestJwtFactory.mintWithJti(
                TestJwtFactory.SEED_USER_ID, Duration.ofHours(1), TestJwtFactory.TEST_JTI_A);

        expectPresence(token, false);

        assertThat(redisTemplate.hasKey(DemoPresenceService.PRESENCE_KEY).block()).isFalse();
    }

    @Test
    @Order(7)
    void legacyNoJtiDemoTokenReturnsFalseAndLeavesRedisUntouchedViaLocalEndpoint() {
        String token = TestJwtFactory.legacyNoJtiToken(DemoPresenceService.DEMO_USER_ID);

        expectPresence(token, false);

        assertThat(redisTemplate.hasKey(DemoPresenceService.PRESENCE_KEY).block()).isFalse();
    }

    @Test
    @Order(8)
    void legacyBlankJtiDemoTokenReturnsFalseAndLeavesRedisUntouchedViaLocalEndpoint() {
        String token = TestJwtFactory.legacyBlankJtiToken(DemoPresenceService.DEMO_USER_ID);

        expectPresence(token, false);

        assertThat(redisTemplate.hasKey(DemoPresenceService.PRESENCE_KEY).block()).isFalse();
    }

    @Test
    @Order(9)
    void boundaryEntryExactlyAtTtlRemainsWhileOlderEntryIsSwept() {
        long now = FIXED_NOW.getEpochSecond();
        String staleMember = DemoPresenceService.hashSessionKey("stale-session");
        String boundaryMember = DemoPresenceService.hashSessionKey("boundary-session");
        String callerMember = DemoPresenceService.hashSessionKey(TestJwtFactory.TEST_JTI_A);

        redisTemplate.opsForZSet()
                .add(DemoPresenceService.PRESENCE_KEY, staleMember, now - 151)
                .block();
        redisTemplate.opsForZSet()
                .add(DemoPresenceService.PRESENCE_KEY, boundaryMember, now - 150)
                .block();

        String token = TestJwtFactory.demoUserToken(TestJwtFactory.TEST_JTI_A);
        expectPresence(token, true);

        assertThat(redisTemplate.opsForZSet().score(DemoPresenceService.PRESENCE_KEY, staleMember).block())
                .isNull();
        assertThat(redisTemplate.opsForZSet().score(DemoPresenceService.PRESENCE_KEY, boundaryMember).block())
                .isNotNull();
        assertThat(redisTemplate.opsForZSet().score(DemoPresenceService.PRESENCE_KEY, callerMember).block())
                .isNotNull();
    }

    @Test
    @Order(10)
    void keyExpiryIsTtlPlusSlackAfterTouch() {
        String token = TestJwtFactory.demoUserToken(TestJwtFactory.TEST_JTI_A);

        expectPresence(token, false);

        Duration ttl = redisTemplate.getExpire(DemoPresenceService.PRESENCE_KEY).block();
        assertThat(ttl.getSeconds()).isBetween(175L, 180L);
    }

    @Test
    @Order(11)
    void routeIsGatewayLocalWithoutPortfolioUpstream() {
        String token = TestJwtFactory.demoUserToken(TestJwtFactory.TEST_JTI_A);

        webTestClient.get()
                .uri(PRESENCE_PATH)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.anotherSessionActive").isEqualTo(false);
    }

    @Test
    @Order(12)
    void unauthenticatedRequestIsRejected() {
        webTestClient.get()
                .uri(PRESENCE_PATH)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(13)
    void redisFailureReturnsFrozenFalseWithoutChangingAuthenticatedBehavior() {
        redis.stop();
        try {
            String token = TestJwtFactory.demoUserToken(TestJwtFactory.TEST_JTI_A);
            expectPresence(token, false);
        } finally {
            redis.start();
        }
    }

    private void expectPresence(String token, boolean anotherSessionActive) {
        webTestClient.get()
                .uri(PRESENCE_PATH)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.anotherSessionActive").isEqualTo(anotherSessionActive);
    }

    private void expectRoutedPortfolio(String token) {
        webTestClient.get()
                .uri(ROUTED_PORTFOLIO_PATH)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(401));
    }

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
