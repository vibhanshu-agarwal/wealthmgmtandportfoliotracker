package com.wealth.portfolio;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;

import static com.wealth.portfolio.PortfolioConstants.X_USER_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests verifying that {@link Portfolio#getHoldings()} is correctly hydrated
 * after a JPA first-level cache eviction, and that the REST endpoint returns the holdings array.
 *
 * <p>Run via: {@code ./gradlew :portfolio-service:integrationTest --tests "com.wealth.portfolio.PortfolioHoldingsHydrationIT"}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class PortfolioHoldingsHydrationIT {

    private static final String TEST_USER_ID = "00000000-0000-0000-0000-000000000099";

    @Container
    @SuppressWarnings("resource")
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
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
    }

    @Autowired
    PortfolioRepository portfolioRepository;

    @Autowired
    AssetHoldingRepository assetHoldingRepository;

    @Autowired
    EntityManager entityManager;

    @Autowired
    PortfolioController portfolioController;

    @Autowired
    JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;

    // Tracks the portfolio ID created per test so @AfterEach can clean up
    private UUID seededPortfolioId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(portfolioController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // Ensure the test user exists (idempotent)
        jdbcTemplate.update("""
                INSERT INTO users (id, email, created_at)
                VALUES (?::uuid, 'hydration-test@local', now())
                ON CONFLICT DO NOTHING
                """, TEST_USER_ID);
    }

    @AfterEach
    void tearDown() {
        // Remove holdings + portfolio for the test user; cascade handles asset_holdings
        jdbcTemplate.update("DELETE FROM portfolios WHERE user_id = ?", TEST_USER_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?::uuid", TEST_USER_ID);
        seededPortfolioId = null;
    }

    // ── Test 1: Holdings are hydrated after first-level cache eviction ────────

    @Test
    @Transactional
    void holdingsAreHydratedAfterCacheEvict() {
        // Persist a portfolio for the test user
        Portfolio portfolio = portfolioRepository.save(new Portfolio(TEST_USER_ID));
        seededPortfolioId = portfolio.getId();

        // Add two holdings via the domain method and save
        portfolio.addHolding(new AssetHolding(portfolio, "AAPL", new BigDecimal("10.00")));
        portfolio.addHolding(new AssetHolding(portfolio, "BTC", new BigDecimal("0.5")));
        portfolioRepository.save(portfolio);

        // Evict the first-level (persistence context) cache
        entityManager.flush();
        entityManager.clear();

        // Reload from DB — holdings must be hydrated
        Portfolio reloaded = portfolioRepository.findById(seededPortfolioId).orElseThrow();
        assertThat(reloaded.getHoldings()).hasSize(2);
    }

    // ── Test 2: GET /api/portfolio returns holdings array with 2 elements ─────

    @Test
    void getPortfolioEndpointReturnsHoldingsArray() throws Exception {
        // Seed portfolio + holdings directly so we don't need a transaction wrapping MockMvc
        jdbcTemplate.update("""
                INSERT INTO portfolios (id, user_id, created_at)
                VALUES (?::uuid, ?, now())
                """, "00000000-0000-0000-0000-000000000098", TEST_USER_ID);

        seededPortfolioId = UUID.fromString("00000000-0000-0000-0000-000000000098");

        jdbcTemplate.update("""
                INSERT INTO asset_holdings (portfolio_id, asset_ticker, quantity)
                VALUES (?::uuid, 'AAPL', 10.0), (?::uuid, 'BTC', 0.5)
                """, seededPortfolioId.toString(), seededPortfolioId.toString());

        mockMvc.perform(get("/api/portfolio")
                        .header(X_USER_ID_HEADER, TEST_USER_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].holdings.length()").value(2));
    }
}
