package com.wealth.insight.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import static com.wealth.insight.infrastructure.redis.CacheConfig.PORTFOLIO_ANALYSIS_CACHE;

/**
 * Evicts the {@code portfolio-analysis::<portfolioId>} entry from the Redis cache so the
 * next Bedrock analysis reflects the freshly-seeded golden-state portfolio (design doc
 * \u00a7 6 / Requirement 5.2). The {@code sentiment} cache is deliberately left intact.
 */
@Service
public class InsightSeedService {

    private static final Logger log = LoggerFactory.getLogger(InsightSeedService.class);

    private final CacheManager cacheManager;

    public InsightSeedService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public record EvictResult(int cacheKeysEvicted) {}

    public EvictResult evict(String portfolioId) {
        Cache cache = cacheManager.getCache(PORTFOLIO_ANALYSIS_CACHE);
        if (cache == null) {
            log.warn("Cache '{}' not present in CacheManager \u2014 nothing to evict", PORTFOLIO_ANALYSIS_CACHE);
            return new EvictResult(0);
        }
        cache.evict(portfolioId);
        log.info("Evicted {}::{} from cache", PORTFOLIO_ANALYSIS_CACHE, portfolioId);
        return new EvictResult(1);
    }
}
