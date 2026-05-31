package com.wealth.market.seed;

import com.wealth.market.AssetPrice;
import com.wealth.market.events.PriceUpdatedEvent;
import com.wealth.market.seed.MarketDataSeedService.SeedResult;
import com.wealth.market.seed.SeedTickerRegistry.SeedTicker;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preservation property test for {@link MarketDataSeedService#seed(String)} — Property 4
 * of the {@code chatbot-asset-coverage-fix} bugfix spec.
 *
 * <p><b>Bug condition / fix context:</b> Root Cause 1 of the bugfix is that the golden-state
 * seeder upserts the 160 registry tickers into MongoDB but publishes no {@code PriceUpdatedEvent}.
 * The forthcoming fix adds Kafka publication, which must be <em>additive</em>: it must not change
 * the MongoDB end state. This test pins that end state so the fix can be verified against it.
 *
 * <p><b>Observation-first methodology:</b> these tests are written against the UNFIXED seeder and
 * MUST PASS. They encode the observed baseline MongoDB behavior that the fix is required to
 * preserve (re-run unchanged as task 5.3 after the fix):
 * <ol>
 *   <li>A single {@code seed(userId)} upserts <b>exactly the 160 registry tickers</b> into
 *       {@code market_prices}, keyed by {@code _id = ticker}, each with
 *       {@code currentPrice = DeterministicPriceCalculator.compute(basePrice, ticker, userId)}.</li>
 *   <li>A pre-existing document <b>outside the registry set is left untouched</b> after seeding.</li>
 *   <li>Running {@code seed(userId)} twice yields <b>byte-identical {@code currentPrice}</b> per
 *       {@code (ticker, userId)} pair (value-level idempotency). The idempotency check is run as a
 *       property across multiple {@code userId} inputs.</li>
 * </ol>
 *
 * <p><b>Validates: Requirements 3.5, 3.7, 3.8</b> — the seeder continues to upsert exactly the 160
 * registry tickers, leaves non-registry documents untouched, and remains value-level idempotent;
 * event publication is additive and does not alter the MongoDB end state.
 *
 * <p>Tagged {@code integration} (Testcontainers MongoDB) — run via
 * {@code ./gradlew :market-data-service:integrationTest --tests "*MarketDataSeedServicePreservationIT*"}.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("local")
@Testcontainers
class MarketDataSeedServicePreservationIT {

    private static final String COLLECTION = "market_prices";
    private static final int EXPECTED_REGISTRY_TICKERS = 160;
    private static final String DEFAULT_USER_ID = "e2e-user";

    @Container
    @SuppressWarnings("resource")
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void integrationTestProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
        // Disable every startup path that writes to / publishes from MongoDB so the test
        // exercises ONLY MarketDataSeedService.seed(...) in isolation.
        registry.add("market.seed.enabled", () -> false);                // LocalMarketDataSeeder fixture
        registry.add("market-data.refresh.enabled", () -> false);        // scheduled refresh job
        registry.add("market-data.hydration.enabled", () -> false);      // startup Kafka hydration
        registry.add("market-data.baseline-seed.enabled", () -> false);  // baseline shell seeder
    }

    // The unfixed seeder never touches Kafka, but the context auto-configures a KafkaTemplate.
    // Mock it so no broker connection is attempted under any boot path.
    @MockitoBean
    @SuppressWarnings("unused")
    KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;

    @Autowired MarketDataSeedService seedService;
    @Autowired SeedTickerRegistry registry;
    @Autowired MongoTemplate mongoTemplate;

    @BeforeEach
    void clearCollection() {
        // Context (and therefore the Mongo container) is shared across test methods; start each
        // test from an empty market_prices collection for deterministic, isolated assertions.
        mongoTemplate.getCollection(COLLECTION).deleteMany(new Document());
    }

    // ------------------------------------------------------------------------------------------
    // Observation 1: a single seed(userId) upserts EXACTLY the 160 registry tickers with the
    // deterministic price written to currentPrice (req 3.5, 3.8).
    // ------------------------------------------------------------------------------------------
    @Test
    void seed_upsertsExactlyTheRegistryTickers_withDeterministicCurrentPrice() {
        assertThat(registry.all())
                .as("registry must load all 160 tickers from seed-tickers.json")
                .hasSize(EXPECTED_REGISTRY_TICKERS);

        SeedResult result = seedService.seed(DEFAULT_USER_ID);
        assertThat(result.pricesUpserted()).isEqualTo(EXPECTED_REGISTRY_TICKERS);

        Set<String> registryTickers = registry.all().stream()
                .map(SeedTicker::ticker).collect(Collectors.toSet());

        // Exactly 160 documents, and their _id set equals the registry ticker set.
        assertThat(mongoTemplate.getCollection(COLLECTION).countDocuments())
                .isEqualTo(EXPECTED_REGISTRY_TICKERS);
        assertThat(storedIds()).isEqualTo(registryTickers);

        // Each currentPrice equals DeterministicPriceCalculator.compute(basePrice, ticker, userId).
        for (SeedTicker t : registry.all()) {
            AssetPrice stored = mongoTemplate.findById(t.ticker(), AssetPrice.class, COLLECTION);
            assertThat(stored).as("document for %s", t.ticker()).isNotNull();
            BigDecimal expected =
                    DeterministicPriceCalculator.compute(t.basePrice(), t.ticker(), DEFAULT_USER_ID);
            assertThat(stored.getCurrentPrice())
                    .as("currentPrice for %s", t.ticker())
                    .isEqualByComparingTo(expected);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Observation 2: a pre-seeded document OUTSIDE the registry set is left untouched (req 3.5).
    // ------------------------------------------------------------------------------------------
    @Test
    void seed_leavesNonRegistryDocumentUntouched() {
        String sentinelId = "SENTINEL-NOT-IN-REGISTRY";
        Decimal128 sentinelPrice = new Decimal128(new BigDecimal("123.4567"));
        Document sentinel = new Document("_id", sentinelId)
                .append("currentPrice", sentinelPrice)
                .append("quoteCurrency", "XYZ")
                .append("marker", "untouched");
        mongoTemplate.getCollection(COLLECTION).insertOne(sentinel);

        seedService.seed(DEFAULT_USER_ID);

        Document after = mongoTemplate.getCollection(COLLECTION)
                .find(new Document("_id", sentinelId)).first();
        assertThat(after).as("non-registry document must still exist").isNotNull();
        assertThat(after.get("currentPrice"))
                .as("non-registry currentPrice unchanged").isEqualTo(sentinelPrice);
        assertThat(after.getString("quoteCurrency")).isEqualTo("XYZ");
        assertThat(after.getString("marker")).isEqualTo("untouched");

        // 160 registry docs + the single untouched sentinel.
        assertThat(mongoTemplate.getCollection(COLLECTION).countDocuments())
                .isEqualTo(EXPECTED_REGISTRY_TICKERS + 1L);
        assertThat(storedIds())
                .hasSize(EXPECTED_REGISTRY_TICKERS + 1)
                .contains(sentinelId);
    }

    // ------------------------------------------------------------------------------------------
    // Observation 3 (property): running seed(userId) twice yields byte-identical currentPrice per
    // (ticker, userId) pair — value-level idempotency across a range of userId inputs (req 3.5).
    // ------------------------------------------------------------------------------------------
    @ParameterizedTest(name = "value-level idempotency for userId=\"{0}\"")
    @ValueSource(strings = {
            "e2e-user",
            "00000000-0000-0000-0000-000000000e2e",
            "alice",
            "BOB-123",
            "user.42"
    })
    void seed_runTwice_yieldsByteIdenticalCurrentPricePerTicker(String userId) {
        seedService.seed(userId);
        Map<String, BigDecimal> firstRun = currentPricesByTicker();

        seedService.seed(userId);
        Map<String, BigDecimal> secondRun = currentPricesByTicker();

        // Idempotent at the document-count level: still exactly 160 registry docs.
        assertThat(mongoTemplate.getCollection(COLLECTION).countDocuments())
                .isEqualTo(EXPECTED_REGISTRY_TICKERS);

        // Idempotent at the value level: identical currentPrice for every ticker.
        assertThat(firstRun).hasSize(EXPECTED_REGISTRY_TICKERS);
        assertThat(secondRun.keySet()).isEqualTo(firstRun.keySet());
        firstRun.forEach((ticker, price) ->
                assertThat(secondRun.get(ticker))
                        .as("currentPrice for %s must be identical across runs", ticker)
                        .isEqualByComparingTo(price));
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private Set<String> storedIds() {
        Set<String> ids = new HashSet<>();
        for (Document d : mongoTemplate.getCollection(COLLECTION).find()) {
            ids.add(String.valueOf(d.get("_id")));
        }
        return ids;
    }

    private Map<String, BigDecimal> currentPricesByTicker() {
        Map<String, BigDecimal> prices = new HashMap<>();
        for (SeedTicker t : registry.all()) {
            AssetPrice stored = mongoTemplate.findById(t.ticker(), AssetPrice.class, COLLECTION);
            if (stored != null) {
                prices.put(t.ticker(), stored.getCurrentPrice());
            }
        }
        return prices;
    }
}
