package com.wealth.portfolio;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Profile-aware cache configuration.
 *
 * <ul>
 *   <li>{@code local} profile — Caffeine in-memory cache, TTL 30 s.</li>
 *   <li>{@code aws} profile — Redis-backed Spring Cache, TTL 30 s.</li>
 * </ul>
 *
 * <p>Switching between backends requires only a profile change — no code modifications.
 * Cache key for analytics is {@code "portfolio-analytics:{userId}"} (set via
 * {@code @Cacheable(key = "#userId")} on {@link PortfolioAnalyticsService#getAnalytics}).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final String CACHE_NAME = "portfolio-analytics";
    private static final long TTL_SECONDS = 30;

    @Bean
    @Profile("local")
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(CACHE_NAME);
        manager.setCaffeine(
                Caffeine.newBuilder().expireAfterWrite(TTL_SECONDS, TimeUnit.SECONDS)
        );
        return manager;
    }

    @Bean
    @Profile("aws")
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(TTL_SECONDS));
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
