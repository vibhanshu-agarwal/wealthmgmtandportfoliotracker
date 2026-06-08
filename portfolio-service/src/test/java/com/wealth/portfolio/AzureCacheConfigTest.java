package com.wealth.portfolio;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the {@code azure}-profile Caffeine cache-manager bean
 * (Task 7.1 / Req 6 AC3).
 *
 * <h2>Profile strategy</h2>
 * <p>The test activates only the {@code azure} profile (no {@code local}).
 * Running both {@code azure} and {@code local} simultaneously causes a
 * {@code NoUniqueBeanDefinitionException} because both {@code caffeineCacheManager}
 * (local) and {@code azureCaffeineCacheManager} (azure) become active.
 *
 * <p>{@code application-azure.yml} also sets {@code spring.cache.type=simple} which
 * would override the {@code CacheConfig} bean entirely.  {@code @TestPropertySource}
 * resets that key to {@code none} so the {@link CacheConfig} bean — not the auto-
 * configured simple manager — is the one under test.
 *
 * <p>The required infrastructure properties that {@code application-local.yml} would
 * normally supply (FX rates, Kafka deserializer, Kafka bootstrap) are provided here
 * via {@code @TestPropertySource} and {@code @DynamicPropertySource}.
 *
 * <p>Validates:
 * <ul>
 *   <li>Task 7.1: azure profile registers a Caffeine {@link CacheManager} (not a no-TTL
 *       simple manager).</li>
 *   <li>Task 7.2: cached entries are evictable and reflect updated data after eviction.</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("azure")
@TestPropertySource(properties = {
        // Override azure yml's spring.cache.type=simple so CacheConfig bean wins.
        "spring.cache.type=none",
        // Supply FX config that application-azure.yml doesn't provide (normally from local).
        "fx.base-currency=USD",
        // Suppress Kafka deserialization properties that require the local profile config.
        "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
        "spring.kafka.consumer.properties.spring.json.trusted.packages=com.wealth.market.events"
})
class AzureCacheConfigTest {

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

    // ── Task 7.1: azure profile registers an explicit-expiry CacheManager ────

    @Test
    void azureProfile_cacheManagerIsRegistered() {
        assertThat(cacheManager).isNotNull();
        assertThat(cacheManager)
                .as("azure profile must use Caffeine CacheManager, not a no-TTL simple manager")
                .isInstanceOf(CaffeineCacheManager.class);
    }

    @Test
    void azureProfile_portfolioAnalyticsCacheExists() {
        Cache cache = cacheManager.getCache("portfolio-analytics");
        assertThat(cache)
                .as("portfolio-analytics cache must be available under the azure profile")
                .isNotNull();
    }

    // ── Task 7.2: eviction reflects updated data ──────────────────────────────

    @Test
    void azureProfile_cacheEviction_reflectsUpdatedData() {
        Cache cache = cacheManager.getCache("portfolio-analytics");
        assertThat(cache).isNotNull();

        cache.put("test-key", "version-1");
        assertThat(cache.get("test-key")).isNotNull();
        assertThat(cache.get("test-key").get()).isEqualTo("version-1");

        cache.evict("test-key");
        assertThat(cache.get("test-key"))
                .as("after eviction the entry must not be present")
                .isNull();

        cache.put("test-key", "version-2");
        assertThat(cache.get("test-key").get())
                .as("after eviction and re-population must return updated value")
                .isEqualTo("version-2");

        cache.evict("test-key");
    }
}
