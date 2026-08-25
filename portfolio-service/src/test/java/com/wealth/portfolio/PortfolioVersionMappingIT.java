package com.wealth.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.ManyToOne;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * JPA mapping proof for B1 Wave 3 task 3.4: {@code version}/{@code updatedAt} and the
 * same-instant creation invariant.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class PortfolioVersionMappingIT {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(TestContainerImages.POSTGRES)
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

    @Autowired PortfolioRepository portfolioRepository;
    @Autowired EntityManager entityManager;

    @Test
    @Transactional
    void applicationCreatedPortfolioMapsVersionAndEqualTimestamps() {
        String userId = UUID.randomUUID().toString();

        Portfolio saved = portfolioRepository.save(new Portfolio(userId));
        assertThat(saved.getHoldings())
                .as("new Portfolio exposes the live mutable holdings collection")
                .isEmpty();
        saved.addHolding(new AssetHolding(saved, "AAPL", new BigDecimal("1.00")));
        assertThat(saved.getHoldings()).hasSize(1);

        entityManager.flush();
        entityManager.clear();

        Portfolio reloaded = portfolioRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getVersion()).isZero();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(reloaded.getCreatedAt()).isEqualTo(reloaded.getUpdatedAt());

        assertThat(reloaded.getHoldings())
                .as("reloaded Portfolio must hydrate the persisted live holdings collection")
                .hasSize(1)
                .extracting(AssetHolding::getAssetTicker)
                .containsExactly("AAPL");
        assertThat(reloaded.getUserId()).isEqualTo(userId);

        boolean hasManyToOne = Arrays.stream(Portfolio.class.getDeclaredFields())
                .anyMatch(PortfolioVersionMappingIT::isManyToOneAssociation);
        assertThat(hasManyToOne)
                .as("Portfolio must not introduce a cross-module @ManyToOne association")
                .isFalse();
    }

    private static boolean isManyToOneAssociation(Field field) {
        return field.getAnnotation(ManyToOne.class) != null;
    }
}
