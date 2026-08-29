package com.wealth.portfolio.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.AssetHoldingRepository;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.TestContainerImages;
import com.wealth.portfolio.composition.CompositionTuplePreparer;
import com.wealth.portfolio.composition.HoldingReplacementService;
import com.wealth.portfolio.composition.RawIntent;
import com.wealth.portfolio.seed.DemoProperties;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real HTTP + Spring + Postgres proof for demo reset (Task 4.4). Every component in the chain is
 * real; {@link DemoResetService} may be spied for call-count evidence only.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTracing(export = false)
@AutoConfigureTestRestTemplate
@Import(DemoResetIntegrationTest.W3cPropagationConfig.class)
@ActiveProfiles("local")
class DemoResetIntegrationTest {

    private static final String INTERNAL_KEY = "demo-reset-it-key";
    private static final String ANCHOR = "2020-01-01T00:00:00Z";
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + SPAN_ID + "-01";

    @Container
    @SuppressWarnings("resource")
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
        registry.add("app.internal.api-key", () -> INTERNAL_KEY);
        registry.add("app.demo.cost-basis-anchor", () -> ANCHOR);
        registry.add("management.tracing.propagation.type", () -> "w3c");
        registry.add("management.tracing.export.enabled", () -> "false");
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired JsonMapper jsonMapper;
    @Autowired HoldingReplacementService replacementService;
    @Autowired CompositionTuplePreparer compositionPreparer;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired AssetHoldingRepository assetHoldingRepository;
    @Autowired DemoProperties demoProperties;
    @Autowired JdbcTemplate jdbc;

    @MockitoSpyBean DemoResetService demoResetService;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger demoResetLogger;

    @BeforeEach
    void attachLogAppender() {
        demoResetLogger = (Logger) LoggerFactory.getLogger(DemoResetService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        demoResetLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        demoResetLogger.detachAppender(logAppender);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT"})
    void successfulResetThroughRealChain(String method) throws Exception {
        long observedVersion = seedNonGoldenFixture();
        UUID portfolioIdBefore =
                portfolioRepository.findByUserId(DemoResetService.DEMO_USER_ID).getFirst().getId();
        logAppender.list.clear();
        org.mockito.Mockito.clearInvocations(demoResetService);

        insertSentinelPriceRows();
        List<Map<String, Object>> pricesBefore = snapshotMarketPrices();
        List<Map<String, Object>> historyBefore = snapshotMarketPriceHistory();

        JsonNode oracle = loadOracle();
        ResponseEntity<String> response =
                invokeReset(method, observedVersion, headersWithTraceAndKey());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String rawBody = response.getBody();
        assertThat(rawBody).isNotNull();

        JsonNode root = jsonMapper.readTree(rawBody);
        assertThat(root.get("id").asText()).isEqualTo(portfolioIdBefore.toString());
        assertThat(root.get("version").asLong()).isEqualTo(observedVersion + 1L);
        assertThat(root.get("userId").asText()).isEqualTo(DemoResetService.DEMO_USER_ID);

        assertWireHoldingsMatchOracleRawJson(rawBody, oracle);

        assertPersistedMatchesOracle(oracle, portfolioIdBefore);
        assertPriceTablesUnchanged(pricesBefore, historyBefore);
        verify(demoResetService, times(1)).reset(observedVersion);
        assertSuccessLog(observedVersion + 1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT"})
    void staleVersionReturns409WithoutMutation(String method) throws Exception {
        long currentVersion = seedNonGoldenFixture();
        UUID portfolioId = portfolioRepository.findByUserId(DemoResetService.DEMO_USER_ID).getFirst().getId();
        List<AssetHolding> holdingsBefore =
                assetHoldingRepository.findByPortfolio(
                        portfolioRepository.findById(portfolioId).orElseThrow());
        logAppender.list.clear();
        org.mockito.Mockito.clearInvocations(demoResetService);

        insertSentinelPriceRows();
        List<Map<String, Object>> pricesBefore = snapshotMarketPrices();
        List<Map<String, Object>> historyBefore = snapshotMarketPriceHistory();

        ResponseEntity<String> response =
                invokeReset(method, currentVersion - 1L, headersWithTraceAndKey());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode error = jsonMapper.readTree(response.getBody());
        assertThat(error.get("error").asText()).isEqualTo("portfolio_version_conflict");
        assertThat(error.get("message").isTextual()).isTrue();
        assertThat(error.get("currentVersion").asLong()).isEqualTo(currentVersion);

        Portfolio reloaded = portfolioRepository.findByUserId(DemoResetService.DEMO_USER_ID).getFirst();
        assertThat(reloaded.getId()).isEqualTo(portfolioId);
        assertThat(reloaded.getVersion()).isEqualTo(currentVersion);
        assertThat(assetHoldingRepository.findByPortfolio(reloaded)).usingRecursiveComparison().isEqualTo(holdingsBefore);
        assertPriceTablesUnchanged(pricesBefore, historyBefore);
        verify(demoResetService, times(1)).reset(currentVersion - 1L);
        assertThat(logAppender.list).isEmpty();
    }

    private long seedNonGoldenFixture() {
        Portfolio portfolio =
                portfolioRepository.findByUserId(DemoResetService.DEMO_USER_ID).getFirst();
        long version = portfolio.getVersion();
        replacementService.replace(
                DemoResetService.DEMO_USER_ID,
                version,
                List.of(new RawIntent("AAPL", new BigDecimal("99.99990000"))),
                compositionPreparer);
        return portfolioRepository.findByUserId(DemoResetService.DEMO_USER_ID).getFirst().getVersion();
    }

    private ResponseEntity<String> invokeReset(
            String method, long expectedVersion, HttpHeaders headers) {
        return restTemplate.exchange(
                "/api/internal/portfolio/demo-reset",
                HttpMethod.valueOf(method),
                new HttpEntity<>(
                        "{\"expectedVersion\":" + expectedVersion + "}", headers),
                String.class);
    }

    private HttpHeaders headersWithTraceAndKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Api-Key", INTERNAL_KEY);
        headers.set("traceparent", TRACEPARENT);
        return headers;
    }

    private JsonNode loadOracle() throws Exception {
        Path repoRoot = Path.of("").toAbsolutePath();
        Path script = repoRoot.resolve("scripts/derive_demo_golden_state.py");
        if (!script.toFile().exists()) {
            repoRoot = repoRoot.getParent();
            script = repoRoot.resolve("scripts/derive_demo_golden_state.py");
        }
        Process process =
                new ProcessBuilder(
                                "python",
                                script.toString(),
                                "--catalog",
                                repoRoot.resolve("config/seed-tickers.json").toString(),
                                "--cost-basis-anchor",
                                demoProperties.costBasisAnchor().toString())
                        .directory(repoRoot.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor())
                .as("oracle script output:\n%s", output)
                .isZero();
        return jsonMapper.readTree(output);
    }

    private static Map<String, String> wireQuantitiesFromOracle(JsonNode oracle) {
        Map<String, String> map = new LinkedHashMap<>();
        for (JsonNode row : oracle.get("wireHoldings")) {
            map.put(row.get("assetTicker").asText(), row.get("quantity").asText());
        }
        return map;
    }

    private void assertWireHoldingsMatchOracleRawJson(String rawBody, JsonNode oracle) throws Exception {
        Map<String, String> expected = wireQuantitiesFromOracle(oracle);
        Set<String> expectedTickers = new LinkedHashSet<>(expected.keySet());

        JsonNode holdings = jsonMapper.readTree(rawBody).get("holdings");
        assertThat(holdings.isArray()).isTrue();
        assertThat(holdings).hasSize(expectedTickers.size());

        Set<String> actualTickers = new LinkedHashSet<>();
        for (JsonNode row : holdings) {
            String ticker = row.get("assetTicker").asText();
            JsonNode quantityNode = row.get("quantity");
            assertThat(quantityNode.isString())
                    .as("quantity for %s must be a JSON string token on the wire", ticker)
                    .isTrue();
            assertThat(expected).containsKey(ticker);
            assertThat(quantityNode.asText())
                    .as("wire quantity for %s must preserve oracle decimal text", ticker)
                    .isEqualTo(expected.get(ticker));
            actualTickers.add(ticker);
        }
        assertThat(actualTickers).isEqualTo(expectedTickers);
    }

    private void assertPersistedMatchesOracle(JsonNode oracle, UUID portfolioId) {
        Map<String, JsonNode> expectedByTicker = new LinkedHashMap<>();
        for (JsonNode row : oracle.get("persistedHoldings")) {
            expectedByTicker.put(row.get("assetTicker").asText(), row);
        }

        List<AssetHolding> actual =
                assetHoldingRepository
                        .findByPortfolio(portfolioRepository.findById(portfolioId).orElseThrow())
                        .stream()
                        .sorted(Comparator.comparing(AssetHolding::getAssetTicker))
                        .toList();

        assertThat(actual).hasSize(expectedByTicker.size());
        for (AssetHolding holding : actual) {
            JsonNode expected = expectedByTicker.get(holding.getAssetTicker());
            assertThat(expected).isNotNull();
            assertThat(holding.getQuantity().toPlainString())
                    .isEqualTo(expected.get("quantity").asText());
            assertThat(holding.getAvgCostBasis().toPlainString())
                    .isEqualTo(expected.get("avgCostBasis").asText());
            assertThat(holding.getCostBasisCurrency())
                    .isEqualTo(expected.get("costBasisCurrency").asText());
            assertThat(holding.getCostBasisSource())
                    .isEqualTo(expected.get("costBasisSource").asText());
            assertThat(holding.getCostBasisAsOf().toString())
                    .isEqualTo(expected.get("costBasisAsOf").asText());
        }
    }

    private void assertSuccessLog(long version) {
        assertThat(logAppender.list)
                .filteredOn(
                        event ->
                                event.getFormattedMessage()
                                        .equals("event=demo_reset_succeeded version=" + version))
                .hasSize(1);
        assertThat(logAppender.list)
                .filteredOn(
                        event ->
                                event.getFormattedMessage()
                                        .equals("event=demo_reset_succeeded version=" + version))
                .singleElement()
                .extracting(event -> event.getMDCPropertyMap().get("traceId"))
                .isEqualTo(TRACE_ID);
    }

    private void insertSentinelPriceRows() {
        jdbc.update(
                """
                INSERT INTO market_prices (ticker, current_price, updated_at)
                VALUES ('__SENTINEL__', 4242.4242, timestamp '2020-01-01 00:00:00')
                ON CONFLICT (ticker) DO NOTHING
                """);
        jdbc.update(
                """
                INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
                SELECT '__SENTINEL__', 'USD', 4242.4242, timestamp '2020-01-01 00:00:00'
                WHERE NOT EXISTS (
                    SELECT 1 FROM market_price_history WHERE ticker = '__SENTINEL__'
                )
                """);
    }

    private List<Map<String, Object>> snapshotMarketPrices() {
        return jdbc.queryForList("SELECT * FROM market_prices ORDER BY ticker");
    }

    private List<Map<String, Object>> snapshotMarketPriceHistory() {
        return jdbc.queryForList("SELECT * FROM market_price_history ORDER BY id");
    }

    private void assertPriceTablesUnchanged(
            List<Map<String, Object>> pricesBefore, List<Map<String, Object>> historyBefore) {
        assertThat(snapshotMarketPrices()).isEqualTo(pricesBefore);
        assertThat(snapshotMarketPriceHistory()).isEqualTo(historyBefore);
    }

    /**
     * Boot 4 gates the W3C {@link TextMapPropagator} on tracing export. Keep OTLP export off and
     * still allow incoming {@code traceparent} extraction under {@code @AutoConfigureTracing}.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class W3cPropagationConfig {

        @Bean
        TextMapPropagator w3cTextMapPropagator() {
            return W3CTraceContextPropagator.getInstance();
        }
    }
}
