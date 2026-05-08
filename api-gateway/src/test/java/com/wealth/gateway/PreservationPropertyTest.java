package com.wealth.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preservation Property Tests — Properties 2a–2e.
 *
 * <p>These tests capture baseline behavior on UNFIXED code. They must PASS now
 * and continue to PASS after the fix, proving no regressions were introduced.
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6</b>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class PreservationPropertyTest {

    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(REDIS_PORT));
        registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);
    }

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient
                .bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ── Property 2a — Local Dev Secret Fallback ──────────────────────────────

    /**
     * Property 2a: The application-local.yml must contain the fallback expression
     * {@code ${AUTH_JWT_SECRET:local-dev-secret-change-me-min-32-chars}} for auth.jwt.secret.
     * This is a structural test — just verify the YAML content contains the expected pattern.
     *
     * <p><b>Validates: Requirements 3.1, 3.2</b>
     */
    @Test
    void applicationLocalYmlContainsSecretFallbackExpression() throws Exception {
        // Read the raw YAML file from the filesystem (not the classpath, which may
        // contain Spring-resolved values due to @DynamicPropertySource overrides).
        Path yamlPath = Path.of("src/main/resources/application-local.yml");
        assertThat(yamlPath).as("application-local.yml must exist on disk").exists();
        String content = Files.readString(yamlPath, StandardCharsets.UTF_8);
        assertThat(content)
                .as("application-local.yml must contain the fallback expression for auth.jwt.secret")
                .contains("${AUTH_JWT_SECRET:local-dev-secret-change-me-min-32-chars}");
    }

    // ── Property 2b — Unauthenticated Rejection ─────────────────────────────

    /**
     * Property 2b: Requests to /api/** without an Authorization header must return 401.
     *
     * <p><b>Validates: Requirements 3.3</b>
     */
    @ParameterizedTest(name = "unauthenticated request to {0} returns 401")
    @ValueSource(strings = {"/api/portfolio", "/api/market", "/api/insights"})
    void unauthenticatedRequestsAreRejected(String path) {
        webTestClient.get()
                .uri(path)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Property 2c — Actuator Permit All ───────────────────────────────────

    /**
     * Property 2c: Requests to /actuator/health without authentication must return 200.
     * This verifies the permitAll() rule for actuator endpoints.
     *
     * <p><b>Validates: Requirements 3.4</b>
     */
    @Test
    void actuatorHealthIsPermittedWithoutAuthentication() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    // ── Property 2c2 — Downstream Health Permit All ─────────────────────────

    /**
     * Property 2c2: Downstream health endpoints must not require authentication.
     * Synthetic monitoring uses them to warm all backend Lambdas before seeded E2E
     * calls; 401 would stop at the gateway and never invoke the downstream Lambda.
     *
     * <p>The response may be 502/503/504 (upstream not running in test) but must
     * NOT be 401 (which would mean the gateway is blocking it for auth).
     */
    @ParameterizedTest
    @ValueSource(strings = {"/api/portfolio/health", "/api/market/health", "/api/insights/health"})
    void downstreamHealthEndpointsArePermittedWithoutAuthentication(String path) {
        webTestClient.get()
                .uri(path)
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as(path + " must not require authentication (permitAll)")
                                .isNotEqualTo(401));
    }

    // ── Property 2d — AWS Profile Isolation ─────────────────────────────────

    /**
     * Property 2d: JwtDecoderConfig must have profile annotations ensuring
     * the current HS256 decoder is only active under local/aws profiles.
     *
     * <p><b>Validates: Requirements 3.5</b>
     */
    @Test
    void jwtDecoderConfigHasCorrectProfileAnnotations() throws Exception {
        Class<?> configClass = JwtDecoderConfig.class;

        Method hmacMethod = configClass.getDeclaredMethod("hmacJwtDecoder", String.class);
        Profile hmacProfile = hmacMethod.getAnnotation(Profile.class);
        assertThat(hmacProfile)
                .as("hmacJwtDecoder must be annotated with @Profile")
                .isNotNull();
        assertThat(hmacProfile.value())
                .as("hmacJwtDecoder must be scoped to local, aws, and azure profiles")
                .containsExactlyInAnyOrder("local", "aws", "azure");
    }

    // ── Property 2e — X-User-Id Stripping ───────────────────────────────────

    /**
     * Property 2e: A request with a valid JWT AND a spoofed X-User-Id header
     * must NOT be rejected (status != 401). The filter strips the spoofed header
     * and injects the real sub claim. Since there's no upstream, we get 502/503/504,
     * but the key assertion is that the gateway did not reject the request.
     *
     * <p><b>Validates: Requirements 3.6</b>
     */
    @Test
    void spoofedXUserIdHeaderWithValidJwtIsNotRejected() {
        String validToken = TestJwtFactory.validSeedUserToken();

        webTestClient.get()
                .uri("/api/portfolio")
                .header("Authorization", "Bearer " + validToken)
                .header("X-User-Id", "attacker-id")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("valid JWT with spoofed X-User-Id should not be rejected with 401")
                                .isNotEqualTo(401));
    }

    // ── Property P2 — JwtDecoder Presence ───────────────────────────────────

    /**
     * Property P2 (local profile): The existing @SpringBootTest context (active profile = local)
     * already boots with a Redis Testcontainer. Assert exactly one ReactiveJwtDecoder bean is
     * registered — reuses the running context rather than booting a second one.
     *
     * <p><b>Validates: Requirements 2.2, 2.4, 15.2</b>
     */
    @Test
    void p2_exactlyOneReactiveJwtDecoderRegisteredUnderLocalProfile(@Autowired ApplicationContext ctx) {
        assertThat(ctx.getBeansOfType(ReactiveJwtDecoder.class))
                .as("exactly one ReactiveJwtDecoder bean must be registered under the 'local' profile")
                .hasSize(1);
    }

    /**
     * Property P2 (aws profile): Uses ApplicationContextRunner (lightweight — no Redis, no full
     * Spring Boot context) to assert exactly one ReactiveJwtDecoder bean is registered when the
     * 'aws' profile is active. Loads only JwtDecoderConfig so the fixture stays minimal.
     *
     * <p>The oauth2ResourceServer().jwt() wiring in SecurityConfig resolves the decoder bean
     * during context refresh; a NoSuchBeanDefinitionException here would surface as a context
     * startup failure, which ApplicationContextRunner captures and this test would fail on.
     *
     * <p><b>Validates: Requirements 2.2, 2.4, 15.2</b>
     */
    @Test
    void p2_exactlyOneReactiveJwtDecoderRegisteredUnderAwsProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(JwtDecoderConfig.class)
                .withPropertyValues(
                        "spring.profiles.active=aws",
                        "auth.jwt.secret=" + TestJwtFactory.TEST_SECRET)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBeansOfType(ReactiveJwtDecoder.class))
                            .as("exactly one ReactiveJwtDecoder bean must be registered under the 'aws' profile")
                            .hasSize(1);
                });
    }

    /**
     * Property P2 (azure profile): Uses ApplicationContextRunner (lightweight — no Redis, no full
     * Spring Boot context) to assert exactly one ReactiveJwtDecoder bean is registered when the
     * 'azure' profile is active. This validates the G1 profile widening from task 2.1.
     *
     * <p>The oauth2ResourceServer().jwt() wiring in SecurityConfig resolves the decoder bean
     * during context refresh; a NoSuchBeanDefinitionException here would surface as a context
     * startup failure, which ApplicationContextRunner captures and this test would fail on.
     *
     * <p><b>Validates: Requirements 2.2, 2.4, 15.2</b>
     */
    @Test
    void p2_exactlyOneReactiveJwtDecoderRegisteredUnderAzureProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(JwtDecoderConfig.class)
                .withPropertyValues(
                        "spring.profiles.active=azure",
                        "auth.jwt.secret=" + TestJwtFactory.TEST_SECRET)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBeansOfType(ReactiveJwtDecoder.class))
                            .as("exactly one ReactiveJwtDecoder bean must be registered under the 'azure' profile")
                            .hasSize(1);
                });
    }
}
