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
 *   <li>{@code local} / {@code default} profile — Caffeine in-memory cache, TTL 30 s.</li>
 *   <li>{@code aws} profile — Redis-backed Spring Cache, TTL 30 s.</li>
 *   <li>{@code azure} profile — Caffeine in-memory cache, TTL 30 s (Wave 2 addition).
 *       Azure Container Apps do not have a managed ElastiCache equivalent on the Free Tier;
 *       in-process Caffeine gives bounded staleness without adding a Redis dependency on
 *       Azure. The same TTL as local/aws ensures analytics refresh within 30 s of a price
 *       update, consistent with Requirement 6.</li>
 * </ul>
 *
 * <p>Every supported runtime profile ({@code local}, {@code aws}, {@code azure}) now has an
 * explicit-expiry cache manager bean, satisfying Requirement 6 AC3.
 *
 * <p>Switching between backends requires only a profile change — no code modifications.
 * Cache key for analytics is {@code "portfolio-analytics:{userId}"} (set via
 * {@code @Cacheable(key = "#userId")} on {@link PortfolioAnalyticsService#getAnalytics}).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final String ANALYTICS_CACHE = "portfolio-analytics";
    /** Used by {@link com.wealth.portfolio.fx.EcbFxRateProvider#fetchRateMap()} on aws/azure profiles. */
    private static final String FX_RATES_CACHE = "fx-rates";
    private static final long TTL_SECONDS = 30;

    @Bean
    @Profile({"local", "default"})
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(ANALYTICS_CACHE, FX_RATES_CACHE);
        manager.setCaffeine(
                Caffeine.newBuilder().expireAfterWrite(TTL_SECONDS, TimeUnit.SECONDS)
        );
        return manager;
    }

    /**
     * Azure-profile Caffeine cache manager (Wave 2 — Task 7.1).
     *
     * <p>Identical TTL and semantics to the local manager. Declared as a separate
     * {@code @Profile("azure")} bean so the azure profile no longer falls through
     * to a no-TTL simple cache, fixing the indefinite-retention bug (audit §3.6).
     */
    @Bean
    @Profile("azure")
    public CacheManager azureCaffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(ANALYTICS_CACHE, FX_RATES_CACHE);
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
