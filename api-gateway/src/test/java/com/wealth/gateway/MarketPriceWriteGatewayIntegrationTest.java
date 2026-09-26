package com.wealth.gateway;

import static com.wealth.gateway.DemoResetGatewayFixtures.USER_ID_HEADER;
import static com.wealth.gateway.DemoResetGatewayFixtures.readOnlyToken;
import static com.wealth.gateway.DemoResetGatewayFixtures.writableToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.gateway.DemoResetGatewayFixtures.Capture;
import com.wealth.gateway.DemoResetGatewayFixtures.RecordingPortfolioStub;
import com.wealth.gateway.DemoResetGatewayFixtures.RouteProbe;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Gateway half of the removed public price write, under the production route list.
 *
 * <p>Anonymous and showcase ({@code ro=true}) callers never reach market-data. An ordinary
 * signed-in account ({@code ro=false}) IS forwarded — the gateway is not the control for this
 * path — carrying only its own gateway-set {@code X-User-Id}. Market-data's
 * {@code MarketPriceWriteRemovalIT} replays exactly those forwarded identities and proves the
 * service refuses the write with no MongoDB or Kafka effect. Together they cover the chain.
 *
 * <p>Characterization, not the fix: this passes with or without the market-data change.
 *
 * <p>Run via: {@code ./gradlew :api-gateway:integrationTest}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
@Import(DemoResetGatewayFixtures.ProviderOverrides.class)
@ActiveProfiles({"prod", "azure"})
class MarketPriceWriteGatewayIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final String PRICE_WRITE_PATH = "/api/market/prices/AAPL";
    private static final String MARKET_ROUTE_ID = "market-data-service";

    /** Stands in for market-data; answers as the fixed service does for the removed route. */
    private static RecordingPortfolioStub marketStub;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(TestContainerImages.REDIS).withExposedPorts(REDIS_PORT);

    // application-prod.yml requires spring.datasource.* at startup; see
    // DemoResetProductionRoutingIntegrationTest for the same rationale.
    @Container
    @SuppressWarnings({"resource", "rawtypes"})
    static final org.testcontainers.postgresql.PostgreSQLContainer postgres =
            new org.testcontainers.postgresql.PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @DynamicPropertySource
    static void productionProperties(DynamicPropertyRegistry registry) throws IOException {
        if (marketStub == null) {
            marketStub = RecordingPortfolioStub.start();
        }
        registry.add("app.routes.market-data-url", marketStub::baseUrl);
        registry.add(
                "spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
        registry.add("spring.data.redis.timeout", () -> "3s");
        registry.add("spring.data.redis.connect-timeout", () -> "3s");

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);
    }

    @AfterAll
    static void stopStub() {
        if (marketStub != null) {
            marketStub.close();
            marketStub = null;
        }
    }

    @LocalServerPort int port;

    @Autowired RouteProbe routeProbe;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        marketStub.reset();
        marketStub.respondWith(404, "{\"error\":\"not_found\"}");
        routeProbe.reset();
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void anonymousPriceWriteIsRejectedBeforeMarketData() {
        webTestClient.post().uri(PRICE_WRITE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("1.00")
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(marketStub.callCount()).as("market-data must not be contacted").isZero();
    }

    @Test
    void showcasePriceWriteIsRejectedBeforeMarketData() {
        webTestClient.post().uri(PRICE_WRITE_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnlyToken(TestJwtFactory.DEMO_USER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("1.00")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(String.class).value(body -> assertThat(body).contains("read_only_account"));

        assertThat(marketStub.callCount()).as("market-data must not be contacted").isZero();
    }

    @Test
    void ordinaryAccountPriceWriteReachesMarketDataWithOnlyItsOwnIdentity() {
        String subject = UUID.randomUUID().toString();

        webTestClient.post().uri(PRICE_WRITE_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + writableToken(subject))
                .header(USER_ID_HEADER, TestJwtFactory.DEMO_USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("1.00")
                .exchange()
                .expectStatus().isNotFound();

        assertThat(routeProbe.onlyMatchedRouteId()).isEqualTo(MARKET_ROUTE_ID);
        Capture capture = marketStub.onlyCapture();
        assertThat(capture.method()).isEqualTo("POST");
        assertThat(capture.path()).isEqualTo(PRICE_WRITE_PATH);
        assertThat(capture.headers().get(USER_ID_HEADER))
                .as("the caller's spoofed identity is replaced by its own JWT subject")
                .containsExactly(subject);
    }
}
