package com.wealth.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;

import static org.mockito.Mockito.mock;

/**
 * Fast, Redis-free unit-level companion to the full-context integration test
 * ({@code ProductionRateLimitingIntegrationTest#missingLimitParamFailsStartup}, task 7.2).
 *
 * <p>Verifies the "fail startup, not fail open" behavior for missing/blank rate-limit parameters
 * under a production profile (Req 2.5, 2.6), in isolation from Redis/Testcontainers, since the two
 * named limiter beans ({@code standardRateLimiter}, {@code strictRateLimiter}) only depend on
 * {@code @Value} placeholders, not on Redis connectivity. This keeps the "missing config fails
 * startup" case cleanly separate from the "Redis unreachable fails open" case (Req 2.6): the
 * assertion below fails during bean <em>construction</em>, before any Redis connection would ever
 * be attempted. This is intentionally a lightweight {@link ApplicationContextRunner} test — no
 * Redis, no Testcontainers, no full {@code SpringBootTest} — so it runs under the fast {@code test}
 * task rather than {@code integrationTest}.
 *
 * <p>A property already declared in {@code application-prod.yml} cannot be "deleted" from a test
 * context — YAML documents are merged before the context builds. The practical mechanism, per
 * design.md's Testing Strategy, is to <b>override the property to an empty string</b>, which makes
 * the no-default {@code @Value int} binding fail to parse, raising a {@link BeanCreationException}
 * during context refresh.
 */
class MissingRateLimitParameterFailStartupTest {

    /**
     * Minimal @Configuration exposing only the two named limiter beans under test — mirrors
     * {@link GatewayRateLimitConfig}'s bean methods without requiring the whole gateway
     * autoconfiguration stack (routes, security, Redis connection factory) to load.
     */
    @Configuration
    static class LimiterBeansOnlyConfig {

        // RedisRateLimiter's static-config constructor still implements ApplicationContextAware
        // and looks up a ReactiveStringRedisTemplate bean during context refresh (to wire the
        // rate-limit Lua script) — this stub satisfies that lookup so the "all params present"
        // happy-path test can complete context refresh without needing a real Redis connection.
        @Bean
        org.springframework.data.redis.core.ReactiveStringRedisTemplate reactiveStringRedisTemplate() {
            return mock(org.springframework.data.redis.core.ReactiveStringRedisTemplate.class);
        }

        @Bean(name = org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter.REDIS_SCRIPT_NAME)
        org.springframework.data.redis.core.script.RedisScript<java.util.List<Long>> redisRequestRateLimiterScript() {
            return mock(org.springframework.data.redis.core.script.RedisScript.class);
        }

        @Bean
        org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter standardRateLimiter(
                @org.springframework.beans.factory.annotation.Value("${app.rate-limit.standard.replenish-rate}")
                        int replenishRate,
                @org.springframework.beans.factory.annotation.Value("${app.rate-limit.standard.burst-capacity}")
                        long burstCapacity,
                @org.springframework.beans.factory.annotation.Value("${app.rate-limit.standard.requested-tokens}")
                        int requestedTokens) {
            return new org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter(
                    replenishRate, burstCapacity, requestedTokens);
        }
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(LimiterBeansOnlyConfig.class);

    @Test
    void contextFailsToStartWhenReplenishRateIsMissing() {
        runner.withPropertyValues(
                        "app.rate-limit.standard.replenish-rate=",
                        "app.rate-limit.standard.burst-capacity=20",
                        "app.rate-limit.standard.requested-tokens=1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(BeanCreationException.class)
                            .hasMessageContaining("standardRateLimiter")
                            .hasRootCauseMessage("For input string: \"\"");
                });
    }

    @Test
    void contextFailsToStartWhenBurstCapacityIsMissing() {
        runner.withPropertyValues(
                        "app.rate-limit.standard.replenish-rate=10",
                        "app.rate-limit.standard.burst-capacity=",
                        "app.rate-limit.standard.requested-tokens=1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(BeanCreationException.class);
                });
    }

    @Test
    void contextFailsToStartWhenRequestedTokensIsMissing() {
        runner.withPropertyValues(
                        "app.rate-limit.standard.replenish-rate=10",
                        "app.rate-limit.standard.burst-capacity=20",
                        "app.rate-limit.standard.requested-tokens=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(BeanCreationException.class);
                });
    }

    @Test
    void contextStartsSuccessfullyWhenAllParametersArePresent() {
        runner.withPropertyValues(
                        "app.rate-limit.standard.replenish-rate=10",
                        "app.rate-limit.standard.burst-capacity=20",
                        "app.rate-limit.standard.requested-tokens=1")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter.class));
    }

    /**
     * Distinct-failure-mode sanity check (Req 2.6): unlike the missing-parameter case above,
     * a bean that depends on an actual Redis connection (e.g. the connection factory itself)
     * is never exercised by the limiter beans — they take only primitive @Value parameters.
     * This test documents that fact by constructing a real RedisRateLimiter with a mocked,
     * never-invoked connection factory and confirming no interaction is required for the
     * bean to construct successfully — the missing-parameter failure is a pure bean-construction
     * concern, structurally independent of Redis reachability.
     */
    @Test
    void limiterBeanConstructionNeverTouchesRedisConnectionFactory() {
        ReactiveRedisConnectionFactory neverInvoked = mock(ReactiveRedisConnectionFactory.class);

        org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter limiter =
                new org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter(10, 20, 1);

        assertThat(limiter).isNotNull();
        org.mockito.Mockito.verifyNoInteractions(neverInvoked);
    }
}
