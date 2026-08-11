package com.wealth.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Regression test for a Critical bug found in review: {@link AuthRateLimitFilter}'s constructor
 * originally took an unqualified {@code RedisRateLimiter authRateLimiter} parameter. Under the
 * "prod" profile, {@link GatewayRateLimitConfig} declares THREE {@code RedisRateLimiter} beans —
 * {@code standardRateLimiter} (marked {@code @Primary} so
 * {@code RequestRateLimiterGatewayFilterFactory}'s factory-level default can resolve
 * unambiguously), {@code strictRateLimiter}, and {@code authRateLimiter}. Spring resolves the
 * {@code @Primary} candidate BEFORE matching by parameter name, so despite the parameter literally
 * being named {@code authRateLimiter}, the unqualified constructor silently received
 * {@code standardRateLimiter}'s instance ({@code replenishRate=10, burstCapacity=20,
 * requestedTokens=1}) instead of the mandated Auth_Bucket ({@code replenishRate=1,
 * burstCapacity=60, requestedTokens=12}, Req 6.3) — roughly 10x more permissive, defeating this
 * feature's brute-force-protection purpose, while the {@code Retry-After} header still looked
 * correct (it's computed independently from {@code @Value}-injected primitives, not from the
 * miswired bean). Fixed by adding {@code @Qualifier("authRateLimiter")} to the constructor
 * parameter.
 *
 * <p>This test reproduces the exact ambiguity condition — three {@code RedisRateLimiter} beans in
 * one context, with a same-typed {@code @Primary} competitor — and lets Spring autowire {@link
 * AuthRateLimitFilter}'s REAL constructor (not a hand-picked bean), so a future regression that
 * drops the {@code @Qualifier} annotation fails this test rather than silently reintroducing the
 * bug.
 *
 * <p>Redis-free, Testcontainers-free {@link ApplicationContextRunner} test (mirrors {@code
 * MissingRateLimitParameterFailStartupTest}'s technique: {@code RedisRateLimiter}'s construction
 * only requires a {@code ReactiveStringRedisTemplate} + the rate-limit Lua {@code RedisScript}
 * bean, never a live Redis connection), so this runs under the fast {@code test} task rather than
 * {@code integrationTest}.
 */
class AuthRateLimitFilterBeanWiringTest {

    /**
     * Mirrors the bean shape of {@link GatewayRateLimitConfig}'s three {@code prod}-profile
     * {@code RedisRateLimiter} beans, including the {@code @Primary} marker on
     * {@code standardRateLimiter} that is the root cause of the bug under test.
     */
    @Configuration
    static class ThreeLimiterBeansConfig {

        @Bean
        ReactiveStringRedisTemplate reactiveStringRedisTemplate() {
            return mock(ReactiveStringRedisTemplate.class);
        }

        @Bean(name = RedisRateLimiter.REDIS_SCRIPT_NAME)
        @SuppressWarnings("unchecked")
        RedisScript<List<Long>> redisRequestRateLimiterScript() {
            return mock(RedisScript.class);
        }

        @Bean
        @Primary
        RedisRateLimiter standardRateLimiter() {
            return new RedisRateLimiter(10, 20, 1);
        }

        @Bean
        RedisRateLimiter strictRateLimiter() {
            return new RedisRateLimiter(1, 30, 6);
        }

        @Bean
        RedisRateLimiter authRateLimiter() {
            return new RedisRateLimiter(1, 60, 12);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues(
                    "spring.profiles.active=prod",
                    "app.rate-limit.trust-xff-last-hop=false",
                    "app.rate-limit.auth.requested-tokens=12",
                    "app.rate-limit.auth.replenish-rate=1")
            .withUserConfiguration(ThreeLimiterBeansConfig.class, AuthRateLimitFilter.class);

    @Test
    void authRateLimitFilterIsWiredToTheAuthBucketBeanNotTheAtPrimaryStandardBean() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            RedisRateLimiter authBean = context.getBean("authRateLimiter", RedisRateLimiter.class);
            RedisRateLimiter standardBean = context.getBean("standardRateLimiter", RedisRateLimiter.class);
            RedisRateLimiter strictBean = context.getBean("strictRateLimiter", RedisRateLimiter.class);
            AuthRateLimitFilter filter = context.getBean(AuthRateLimitFilter.class);

            assertThat(filter.authRateLimiterForTesting())
                    .as("AuthRateLimitFilter must be wired to the authRateLimiter bean (Auth_Bucket: "
                            + "replenishRate=1, burstCapacity=60, requestedTokens=12), not the @Primary "
                            + "standardRateLimiter that an unqualified constructor parameter would silently "
                            + "resolve to")
                    .isSameAs(authBean)
                    .isNotSameAs(standardBean)
                    .isNotSameAs(strictBean);
        });
    }
}
