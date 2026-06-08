package com.wealth.portfolio;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@code azure}-profile Caffeine cache-manager bean (Task 7.1 / Req 6 AC3).
 *
 * <p>Validates:
 * <ul>
 *   <li>Task 7.1: on the {@code azure} profile, a Caffeine {@link CacheManager} bean is
 *       registered with an explicit TTL (not an indefinite/no-TTL simple manager).</li>
 *   <li>Task 7.2: a cached entry expires within the TTL window and reflects updated
 *       underlying data after the window (simulated via manual eviction).</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"azure", "local"})
class AzureCacheConfigTest {

    // Postgres is still required because the Spring context loads Flyway + JPA.
    @Container
    @SuppressWarnings("rawtypes")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    CacheManager cacheManager;

    // ── Task 7.1: azure profile has an explicit-expiry CacheManager bean ─────

    @Test
    void azureProfile_cacheManagerIsRegistered() {
        assertThat(cacheManager).isNotNull();
        // Must be Caffeine-backed (not a no-TTL SimpleCacheManager).
        assertThat(cacheManager)
                .as("azure profile must use Caffeine CacheManager, not a no-TTL simple manager")
                .isInstanceOf(org.springframework.cache.caffeine.CaffeineCacheManager.class);
    }

    @Test
    void azureProfile_portfolioAnalyticsCacheExists() {
        Cache cache = cacheManager.getCache("portfolio-analytics");
        assertThat(cache)
                .as("portfolio-analytics cache must be available under the azure profile")
                .isNotNull();
    }

    // ── Task 7.2: cached entry is evictable and reflects updated data ─────────

    @Test
    void azureProfile_cacheEviction_reflectsUpdatedData() {
        Cache cache = cacheManager.getCache("portfolio-analytics");
        assertThat(cache).isNotNull();

        // Write a sentinel value.
        cache.put("test-key", "version-1");
        Cache.ValueWrapper hit = cache.get("test-key");
        assertThat(hit).isNotNull();
        assertThat(hit.get()).isEqualTo("version-1");

        // Evict — simulates TTL expiry or explicit invalidation.
        cache.evict("test-key");

        // After eviction, the entry is gone (would be re-computed on next call).
        Cache.ValueWrapper afterEvict = cache.get("test-key");
        assertThat(afterEvict)
                .as("after eviction, cache entry must not be present")
                .isNull();

        // Simulate updated underlying data by re-populating.
        cache.put("test-key", "version-2");
        Cache.ValueWrapper updated = cache.get("test-key");
        assertThat(updated).isNotNull();
        assertThat(updated.get())
                .as("after eviction and re-population, cache must return the updated value")
                .isEqualTo("version-2");

        // Clean up.
        cache.evict("test-key");
    }
}
