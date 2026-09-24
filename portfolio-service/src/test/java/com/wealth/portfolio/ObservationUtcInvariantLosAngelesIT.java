package com.wealth.portfolio;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.TimeZone;

/**
 * {@link ObservationUtcInvariantScenarios} under a zone behind UTC (America/Los_Angeles), where
 * reading a UTC wall-clock column in the session zone moves it hours into the future.
 *
 * <p>The JVM default is switched when this class initialises — before its container starts and
 * before its own Spring context opens any JDBC connection — and restored afterwards. The base
 * class's precondition test proves the switch reached the JDBC session.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class ObservationUtcInvariantLosAngelesIT extends ObservationUtcInvariantScenarios {

    private static final TimeZone ORIGINAL_DEFAULT = TimeZone.getDefault();

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
    }

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
        // A distinct property keeps this context from being reused by any other test class.
        registry.add("test.jvm-zone", () -> "America/Los_Angeles");
    }

    @AfterAll
    static void restoreDefaultZone() {
        TimeZone.setDefault(ORIGINAL_DEFAULT);
    }

    @Override
    String expectedZone() {
        return "America/Los_Angeles";
    }
}
