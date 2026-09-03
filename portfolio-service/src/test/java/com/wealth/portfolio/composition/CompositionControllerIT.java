package com.wealth.portfolio.composition;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.catalog.LifecycleStatus;
import com.wealth.catalog.SupportedCatalog;
import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.TestContainerImages;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real HTTP + controller + adapter + replacement + preparer + Postgres proof for Tasks 7.1–7.2.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class CompositionControllerIT {

    private static final String PATH = "/api/portfolio/holdings";

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
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired JsonMapper jsonMapper;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired HoldingReplacementService replacementService;
    @Autowired CompositionTuplePreparer compositionPreparer;
    @Autowired SupportedCatalog catalog;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private String activeTicker;
    private String otherActiveTicker;
    private String deprecatedTicker;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        activeTicker = catalog.active().getFirst().ticker();
        otherActiveTicker = catalog.active().get(1).ticker();
        deprecatedTicker =
                catalog.all().stream()
                        .filter(e -> e.lifecycleStatus() == LifecycleStatus.DEPRECATED)
                        .map(e -> e.ticker())
                        .findFirst()
                        .orElseThrow();
        jdbcTemplate.update("DELETE FROM asset_holdings");
        jdbcTemplate.update("DELETE FROM portfolios");
    }

    @Test
    void existingMutationAdvancesVersionOnceAndMatchesPersistedSnapshot() throws Exception {
        String userId = UUID.randomUUID().toString();
        Instant asOf = Instant.parse("2025-06-01T00:00:00Z");
        UUID portfolioId =
                seedPortfolioWithTwoHoldings(userId, asOf);

        ResponseEntity<String> response =
                putHoldings(
                        userId,
                        """
                        {"expectedVersion":0,"holdings":[{"ticker":"%s","quantity":"9.00000000"}]}
                        """
                                .formatted(activeTicker));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = jsonMapper.readTree(response.getBody());
        assertThat(body.get("id").asText()).isEqualTo(portfolioId.toString());
        assertThat(body.get("userId").asText()).isEqualTo(userId);
        assertThat(body.get("version").asLong()).isEqualTo(1L);
        assertThat(body.get("holdings")).hasSize(1);
        assertThat(body.get("holdings").get(0).get("assetTicker").asText()).isEqualTo(activeTicker);
        assertThat(body.get("holdings").get(0).get("quantity").asText()).isEqualTo("9.00000000");
        assertThat(body.get("holdings").get(0).get("id").asText()).isNotBlank();

        Portfolio reloaded =
                tx.execute(
                        status -> {
                            Portfolio p = portfolioRepository.findByUserId(userId).getFirst();
                            p.getHoldings().size();
                            return p;
                        });
        assertThat(reloaded.getId()).isEqualTo(portfolioId);
        assertThat(reloaded.getVersion()).isEqualTo(1L);
        assertThat(reloaded.getHoldings()).hasSize(1);
        AssetHolding held = reloaded.getHoldings().getFirst();
        assertThat(held.getAssetTicker()).isEqualTo(activeTicker);
        assertThat(held.getQuantity()).isEqualByComparingTo("9.00000000");
        assertThat(held.getAvgCostBasis()).isEqualByComparingTo("123.4500");
        assertThat(held.getCostBasisSource()).isEqualTo("ADD_TIME");
        assertThat(held.getCostBasisAsOf()).isEqualTo(asOf);
        assertThat(held.getId().toString())
                .isEqualTo(body.get("holdings").get(0).get("id").asText());
    }

    @Test
    void firstCreationNonemptyReturns201VersionOne() throws Exception {
        String userId = UUID.randomUUID().toString();

        ResponseEntity<String> response =
                putHoldings(
                        userId,
                        """
                        {"expectedVersion":0,"holdings":[{"ticker":"%s","quantity":"1.50000000"}]}
                        """
                                .formatted(activeTicker));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = jsonMapper.readTree(response.getBody());
        assertThat(body.get("version").asLong()).isEqualTo(1L);
        assertThat(body.get("holdings")).hasSize(1);
        assertThat(body.get("holdings").get(0).get("quantity").asText()).isEqualTo("1.50000000");
        assertThat(portfolioRepository.findByUserId(userId).getFirst().getVersion()).isEqualTo(1L);
    }

    @Test
    void firstCreationEmptyReturns201VersionOne() throws Exception {
        String userId = UUID.randomUUID().toString();

        ResponseEntity<String> response =
                putHoldings(userId, "{\"expectedVersion\":0,\"holdings\":[]}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = jsonMapper.readTree(response.getBody());
        assertThat(body.get("version").asLong()).isEqualTo(1L);
        assertThat(body.get("holdings")).isEmpty();
        assertThat(portfolioRepository.findByUserId(userId)).hasSize(1);
    }

    @Test
    void existingEmptyNoOpReturns200Unchanged() throws Exception {
        String userId = UUID.randomUUID().toString();
        CompositionResult created =
                replacementService.replace(userId, 0L, List.of(), compositionPreparer);
        assertThat(created.created()).isTrue();

        Portfolio before = portfolioRepository.findByUserId(userId).getFirst();
        Instant updatedAt = before.getUpdatedAt();

        ResponseEntity<String> response =
                putHoldings(userId, "{\"expectedVersion\":1,\"holdings\":[]}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = jsonMapper.readTree(response.getBody());
        assertThat(body.get("version").asLong()).isEqualTo(1L);
        assertThat(body.get("holdings")).isEmpty();

        Portfolio after = portfolioRepository.findByUserId(userId).getFirst();
        assertThat(after.getVersion()).isEqualTo(1L);
        assertThat(after.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void exactNoOpPreservesVersionUpdatedAtDecimalAndHoldingIds() throws Exception {
        String userId = UUID.randomUUID().toString();
        CompositionResult seeded =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("0.75000000"))),
                        compositionPreparer);
        Portfolio before =
                tx.execute(
                        status -> {
                            Portfolio p = portfolioRepository.findByUserId(userId).getFirst();
                            p.getHoldings().size();
                            return p;
                        });
        UUID holdingId = before.getHoldings().getFirst().getId();
        Instant updatedAt = before.getUpdatedAt();

        ResponseEntity<String> response =
                putHoldings(
                        userId,
                        """
                        {"expectedVersion":1,"holdings":[{"ticker":"%s","quantity":"0.75000000"}]}
                        """
                                .formatted(activeTicker));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = jsonMapper.readTree(response.getBody());
        assertThat(body.get("version").asLong()).isEqualTo(seeded.version());
        assertThat(body.get("holdings").get(0).get("id").asText()).isEqualTo(holdingId.toString());
        assertThat(body.get("holdings").get(0).get("quantity").asText()).isEqualTo("0.75000000");

        Portfolio after =
                tx.execute(
                        status -> {
                            Portfolio p = portfolioRepository.findByUserId(userId).getFirst();
                            p.getHoldings().size();
                            return p;
                        });
        assertThat(after.getVersion()).isEqualTo(1L);
        assertThat(after.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(after.getHoldings().getFirst().getId()).isEqualTo(holdingId);
    }

    @Test
    void staleVersionReturns409WithoutMutation() throws Exception {
        String userId = UUID.randomUUID().toString();
        seedPortfolioWithOneHolding(userId, new BigDecimal("1.00000000"));
        Portfolio before = snapshot(userId);

        ResponseEntity<String> response =
                putHoldings(
                        userId,
                        """
                        {"expectedVersion":0,"holdings":[{"ticker":"%s","quantity":"1.00000000"}]}
                        """
                                .formatted(activeTicker));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode error = jsonMapper.readTree(response.getBody());
        assertThat(error.get("error").asText()).isEqualTo("portfolio_version_conflict");
        assertThat(error.get("currentVersion").asLong()).isEqualTo(before.getVersion());
        assertUnchanged(userId, before);
    }

    @Test
    void stalePlusSemanticallyInvalidStill409WithoutMutation() throws Exception {
        String userId = UUID.randomUUID().toString();
        seedPortfolioWithOneHolding(userId, new BigDecimal("1.00000000"));
        Portfolio before = snapshot(userId);

        ResponseEntity<String> response =
                putHoldings(
                        userId,
                        """
                        {"expectedVersion":0,"holdings":[
                          {"ticker":"%s","quantity":"0.00000000"},
                          {"ticker":"%s","quantity":"1.00000000"}
                        ]}
                        """
                                .formatted(activeTicker, activeTicker));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode error = jsonMapper.readTree(response.getBody());
        assertThat(error.get("error").asText()).isEqualTo("portfolio_version_conflict");
        assertThat(error.get("currentVersion").asLong()).isEqualTo(before.getVersion());
        assertUnchanged(userId, before);
    }

    @Test
    void currentVersionSemantic400LeavesStateUnchanged() throws Exception {
        String userId = UUID.randomUUID().toString();
        seedPortfolioWithOneHolding(userId, new BigDecimal("1.00000000"));
        Portfolio before = snapshot(userId);

        ResponseEntity<String> response =
                putHoldings(
                        userId,
                        """
                        {"expectedVersion":1,"holdings":[{"ticker":"%s","quantity":"0.00000000"}]}
                        """
                                .formatted(activeTicker));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode error = jsonMapper.readTree(response.getBody());
        assertThat(error.get("error").asText()).isEqualTo("quantity_out_of_domain");
        assertThat(error.get("tickers").get(0).asText()).isEqualTo(activeTicker);
        assertUnchanged(userId, before);
    }

    @Test
    void unsupportedAssets422LeavesStateUnchangedWithCompleteOffenders() throws Exception {
        String userId = UUID.randomUUID().toString();
        seedPortfolioWithOneHolding(userId, new BigDecimal("1.00000000"));
        Portfolio before = snapshot(userId);

        ResponseEntity<String> response =
                putHoldings(
                        userId,
                        """
                        {"expectedVersion":1,"holdings":[
                          {"ticker":"ZZZ_FAKE_1","quantity":"1.00000000"},
                          {"ticker":"ZZZ_FAKE_2","quantity":"2.00000000"}
                        ]}
                        """);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        JsonNode error = jsonMapper.readTree(response.getBody());
        assertThat(error.get("error").asText()).isEqualTo("unsupported_asset");
        assertThat(error.get("ticker").asText()).isEqualTo("ZZZ_FAKE_1");
        assertThat(error.get("tickers").get(0).asText()).isEqualTo("ZZZ_FAKE_1");
        assertThat(error.get("tickers").get(1).asText()).isEqualTo("ZZZ_FAKE_2");
        assertThat(error.get("catalogVersion").isTextual()).isTrue();
        assertUnchanged(userId, before);
    }

    @Test
    void lifecycleNotPermitted422LeavesStateUnchanged() throws Exception {
        String userId = UUID.randomUUID().toString();
        seedPortfolioWithOneHolding(userId, new BigDecimal("1.00000000"));
        Portfolio before = snapshot(userId);

        ResponseEntity<String> response =
                putHoldings(
                        userId,
                        """
                        {"expectedVersion":1,"holdings":[{"ticker":"%s","quantity":"1.00000000"}]}
                        """
                                .formatted(deprecatedTicker));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        JsonNode error = jsonMapper.readTree(response.getBody());
        assertThat(error.get("error").asText()).isEqualTo("lifecycle_not_permitted");
        assertThat(error.get("ticker").asText()).isEqualTo(deprecatedTicker);
        assertThat(error.get("tickers").get(0).asText()).isEqualTo(deprecatedTicker);
        assertUnchanged(userId, before);
    }

    @Test
    void missingAggregateNonzeroVersionReturns409WithoutBareAggregate() throws Exception {
        String userId = UUID.randomUUID().toString();

        ResponseEntity<String> response =
                putHoldings(
                        userId,
                        """
                        {"expectedVersion":4,"holdings":[{"ticker":"%s","quantity":"1.00000000"}]}
                        """
                                .formatted(activeTicker));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode error = jsonMapper.readTree(response.getBody());
        assertThat(error.get("error").asText()).isEqualTo("portfolio_version_conflict");
        assertThat(error.get("currentVersion").asLong()).isEqualTo(0L);
        assertThat(portfolioRepository.findByUserId(userId)).isEmpty();
    }

    @Test
    void spoofedBodyOrQueryIdentityCannotMutateOtherUser() throws Exception {
        String userA = UUID.randomUUID().toString();
        String userB = UUID.randomUUID().toString();
        seedPortfolioWithOneHolding(userA, new BigDecimal("1.00000000"));
        seedPortfolioWithOneHolding(userB, new BigDecimal("5.00000000"));
        Portfolio beforeB = snapshot(userB);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", userA);
        String body =
                """
                {"expectedVersion":1,"userId":"%s","portfolioId":"%s","holdings":[{"ticker":"%s","quantity":"9.00000000"}]}
                """
                        .formatted(userB, beforeB.getId(), activeTicker);

        ResponseEntity<String> response =
                restTemplate.exchange(
                        PATH + "?userId=" + userB + "&portfolioId=" + beforeB.getId(),
                        HttpMethod.PUT,
                        new HttpEntity<>(body, headers),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = jsonMapper.readTree(response.getBody());
        assertThat(root.get("userId").asText()).isEqualTo(userA);
        assertThat(root.get("holdings").get(0).get("quantity").asText()).isEqualTo("9.00000000");

        assertUnchanged(userB, beforeB);
        Portfolio afterA = snapshot(userA);
        assertThat(afterA.getVersion()).isEqualTo(2L);
        assertThat(afterA.getHoldings().getFirst().getQuantity()).isEqualByComparingTo("9.00000000");
    }

    private UUID seedPortfolioWithTwoHoldings(String userId, Instant asOf) {
        return tx.execute(
                status -> {
                    Portfolio p = portfolioRepository.saveAndFlush(new Portfolio(userId));
                    AssetHolding keep =
                            new AssetHolding(p, activeTicker, new BigDecimal("1.00000000"));
                    keep.setAvgCostBasis(new BigDecimal("123.4500"));
                    keep.setCostBasisCurrency("USD");
                    keep.setCostBasisSource("ADD_TIME");
                    keep.setCostBasisAsOf(asOf);
                    AssetHolding remove =
                            new AssetHolding(p, otherActiveTicker, new BigDecimal("2.00000000"));
                    p.addHolding(keep);
                    p.addHolding(remove);
                    portfolioRepository.saveAndFlush(p);
                    return p.getId();
                });
    }

    private void seedPortfolioWithOneHolding(String userId, BigDecimal quantity) {
        replacementService.replace(
                userId,
                0L,
                List.of(new RawIntent(activeTicker, quantity)),
                compositionPreparer);
    }

    private Portfolio snapshot(String userId) {
        return tx.execute(
                status -> {
                    Portfolio p = portfolioRepository.findByUserId(userId).getFirst();
                    p.getHoldings().size();
                    p.getHoldings()
                            .forEach(
                                    h -> {
                                        h.getQuantity();
                                        h.getAvgCostBasis();
                                    });
                    return p;
                });
    }

    private void assertUnchanged(String userId, Portfolio before) {
        Portfolio after = snapshot(userId);
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        Map<String, BigDecimal> beforeQty =
                before.getHoldings().stream()
                        .collect(
                                Collectors.toMap(
                                        AssetHolding::getAssetTicker, AssetHolding::getQuantity));
        Map<String, BigDecimal> afterQty =
                after.getHoldings().stream()
                        .collect(
                                Collectors.toMap(
                                        AssetHolding::getAssetTicker, AssetHolding::getQuantity));
        assertThat(afterQty.keySet()).isEqualTo(beforeQty.keySet());
        beforeQty.forEach(
                (ticker, qty) ->
                        assertThat(afterQty.get(ticker)).isEqualByComparingTo(qty));
        List<UUID> beforeIds =
                before.getHoldings().stream()
                        .sorted(Comparator.comparing(AssetHolding::getAssetTicker))
                        .map(AssetHolding::getId)
                        .toList();
        List<UUID> afterIds =
                after.getHoldings().stream()
                        .sorted(Comparator.comparing(AssetHolding::getAssetTicker))
                        .map(AssetHolding::getId)
                        .toList();
        assertThat(afterIds).isEqualTo(beforeIds);
    }

    private ResponseEntity<String> putHoldings(String userId, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", userId);
        return restTemplate.exchange(
                PATH, HttpMethod.PUT, new HttpEntity<>(jsonBody, headers), String.class);
    }
}
