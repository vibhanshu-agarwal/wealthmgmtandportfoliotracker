package com.wealth.portfolio;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.wealth.portfolio.seed.PortfolioSeedService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static com.wealth.portfolio.PortfolioConstants.X_USER_ID_HEADER;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reproduces deploy-azure verify assertion (c): seeded E2E user + GET /api/portfolio/summary.
 * Uses the {@code azure} profile with {@code spring.cache.type=none} (production overlay).
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("azure")
@TestPropertySource(properties = {
        "spring.cache.type=none",
        "fx.base-currency=USD",
        "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
        "spring.kafka.consumer.properties.spring.json.trusted.packages=com.wealth.market.events"
})
class PortfolioSummaryAfterSeedIT {

    private static final String E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e";

    private static WireMockServer wireMock;

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/v6/latest/USD"))
                .willReturn(okJson("""
                        {
                          "rates": {
                            "USD": 1.0,
                            "EUR": 0.92,
                            "GBP": 0.79,
                            "JPY": 149.50,
                            "INR": 83.45,
                            "HKD": 7.83,
                            "CAD": 1.36,
                            "AUD": 1.53,
                            "CHF": 0.90
                          }
                        }
                        """)));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("fx.azure.rates-url",
                () -> "http://localhost:" + wireMock.port() + "/v6/latest/USD");
    }

    @Autowired PortfolioSeedService seedService;
    @Autowired PortfolioService portfolioService;
    @Autowired PortfolioSummaryController summaryController;

    @Test
    void seededE2eUser_summaryReturns200WithPositiveTotal() throws Exception {
        seedService.seed(E2E_USER_ID);

        var summary = portfolioService.getSummary(E2E_USER_ID);
        assertThat(summary.totalValue())
                .as("verify assertion (c): totalValue must be > 0 after golden-state seed")
                .isGreaterThan(java.math.BigDecimal.ZERO);
        assertThat(summary.totalHoldings()).isEqualTo(160);

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(summaryController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/portfolio/summary")
                        .header(X_USER_ID_HEADER, E2E_USER_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
