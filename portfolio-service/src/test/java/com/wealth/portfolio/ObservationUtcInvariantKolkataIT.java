package com.wealth.portfolio;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@link ObservationUtcInvariantScenarios} under the build's own test JVM zone
 * ({@code -Duser.timezone=Asia/Kolkata}, root build.gradle), UTC+05:30.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class ObservationUtcInvariantKolkataIT extends ObservationUtcInvariantScenarios {

    @Container
    @SuppressWarnings("rawtypes")
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

    @Override
    String expectedZone() {
        return "Asia/Kolkata";
    }
}
