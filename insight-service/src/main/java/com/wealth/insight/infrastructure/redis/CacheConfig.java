package com.wealth.insight.infrastructure.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.Map;

/**
 * Redis-backed cache configuration for insight-service.
 *
 * <p>Two caches are defined:
 * <ul>
 *   <li>{@code sentiment} — Bedrock sentiment responses per ticker, TTL 60 minutes.
 *       Market sentiment doesn't change minute-to-minute; caching avoids redundant
 *       Bedrock API calls (~$0.0001 per call) for repeated ticker lookups.</li>
 *   <li>{@code portfolio-analysis} — Bedrock portfolio analysis per portfolio ID, TTL 30 minutes.
 *       Portfolio composition changes infrequently; caching avoids re-running the full
 *       LLM analysis on every dashboard refresh.</li>
 * </ul>
 *
 * <p>Redis unavailability is non-fatal: the {@link CacheErrorHandler} logs at WARN level
 * and returns normally, causing Spring to treat the error as a cache miss. The request
 * falls through to Bedrock without throwing a 500 error to the caller.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /** Sentiment cache: 60-minute TTL per ticker symbol (e.g. "AAPL"). */
    public static final String SENTIMENT_CACHE = "sentiment";

    /** Portfolio analysis cache: 30-minute TTL per portfolio ID. */
    public static final String PORTFOLIO_ANALYSIS_CACHE = "portfolio-analysis";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues();

        return RedisCacheManager.builder(factory)
                .withCacheConfiguration(
                        SENTIMENT_CACHE,
                        defaultConfig.entryTtl(Duration.ofMinutes(60)))
                .withCacheConfiguration(
                        PORTFOLIO_ANALYSIS_CACHE,
                        defaultConfig.entryTtl(Duration.ofMinutes(30)))
                .build();
    }

    /**
     * Resilient cache error handler — Redis failures are non-fatal.
     *
     * <p>When Redis is unavailable (e.g. connection refused, timeout), Spring's caching
     * abstraction calls these handlers instead of propagating the exception. Returning
     * normally from {@code handleCacheGetError} causes Spring to treat the result as a
     * cache miss and invoke the underlying method (Bedrock API call). This ensures the
     * service degrades gracefully rather than returning 500 errors to callers.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("[CACHE] GET failed — cache={} key={} — falling through to Bedrock: {}",
                        cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("[CACHE] PUT failed — cache={} key={} — result not cached: {}",
                        cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("[CACHE] EVICT failed — cache={} key={}: {}",
                        cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("[CACHE] CLEAR failed — cache={}: {}", cache.getName(), e.getMessage());
            }
        };
    }
}
