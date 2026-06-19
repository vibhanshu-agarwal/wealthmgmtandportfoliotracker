package com.wealth.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.market.events.PriceUpdatedEvent;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Property 2 (integration surface): idempotent projection/history preservation (Req 3.5).
 *
 * <p>Exercises {@code observedAt} values near millisecond boundaries to validate truncation-based
 * dedup in {@link MarketPriceProjectionService}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class PriceUpdatedEventPreservationPropertyIT {

    @Container
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

    @Autowired MarketPriceProjectionService projectionService;

    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.update("DELETE FROM market_price_history WHERE ticker LIKE 'PRES_%'");
        jdbcTemplate.update("DELETE FROM market_prices WHERE ticker LIKE 'PRES_%'");
    }

    @Test
    void p2_duplicateDelivery_nearMillisecondBoundary_isIdempotent() throws Exception {
        for (int subMillisOffsetNanos : new int[] {0, 1, 999_999, 500_000}) {
            String ticker = "PRES_MS_" + subMillisOffsetNanos;
            Instant observedAt =
                    Instant.parse("2026-06-08T10:15:30.123Z").plusNanos(subMillisOffsetNanos);
            Instant expectedKey = observedAt.truncatedTo(ChronoUnit.MILLIS);

            PriceUpdatedEvent event =
                    new PriceUpdatedEvent(ticker, new BigDecimal("123.45"), "USD", observedAt, null, null);

            projectionService.upsertLatestPrice(event);
            Thread.sleep(200);
            projectionService.upsertLatestPrice(event);
            Thread.sleep(200);

            Integer historyCount =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT COUNT(*)
                            FROM market_price_history
                            WHERE ticker = ?
                              AND observed_at = ?
                            """,
                            Integer.class,
                            ticker,
                            Timestamp.from(expectedKey));

            assertThat(historyCount)
                    .as("Duplicate delivery for %s at offset %d ns", ticker, subMillisOffsetNanos)
                    .isEqualTo(1);
        }
    }
}
