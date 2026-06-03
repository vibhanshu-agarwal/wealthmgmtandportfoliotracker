package com.wealth.market.seed;

import com.mongodb.bulk.BulkWriteResult;
import com.wealth.market.events.PriceUpdatedEvent;
import com.wealth.market.seed.SeedTickerRegistry.SeedTicker;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.StringLength;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property 1: Bug Condition (Seed) — Golden-state seeding propagates to Redis via Kafka.
 *
 * <p><b>Validates: Requirements 1.1, 1.2, 2.1, 2.2</b>
 *
 * <p>This is a <b>bug-condition exploration test</b> for Root Cause 1 of the
 * {@code chatbot-asset-coverage-fix} spec. It encodes the <i>expected</i> (fixed) behavior:
 * for every registry ticker that {@link MarketDataSeedService#seed(String)} upserts with a
 * non-null computed price, the seeder MUST publish exactly one
 * {@code PriceUpdatedEvent(ticker, seededPrice)} to the {@code market-prices} Kafka topic,
 * keyed by ticker, where {@code seededPrice = DeterministicPriceCalculator.compute(basePrice,
 * ticker, userId)} — the same value written to MongoDB.
 *
 * <p>Bug condition (from design):
 * <pre>
 * isBugCondition_Seed(X) = X.isGoldenStateSeed
 *                          AND X.upsertedTickerCount &gt; 0
 *                          AND eventsPublishedTo("market-prices", DURING X) is empty
 * </pre>
 *
 * <p><b>On the UNFIXED code this test is EXPECTED TO FAIL</b>: {@code MarketDataSeedService}
 * never injects or invokes a {@code KafkaTemplate}, so the captured template records zero
 * sends — confirming the bug (the seeder upserts documents but emits no events, so the
 * chatbot's Redis is never hydrated). When the fix is implemented, this same test passes.
 *
 * <p>The service is instantiated <b>reflectively</b> (selecting the constructor with the most
 * parameters and resolving each argument by type) so the test compiles and runs against both
 * the unfixed two-argument constructor {@code (MongoTemplate, SeedTickerRegistry)} and the
 * fixed constructor that additionally accepts a {@code KafkaTemplate}.
 */
class MarketDataSeedServicePropagationPropertyTest {

    private static final String TOPIC = "market-prices";

    /**
     * The canonical seeded registry, loaded from the classpath when present (the real 160
     * tickers), otherwise a representative fallback pool covering every registry symbol shape.
     */
    private static final List<SeedTicker> REGISTRY = loadRegistry();

    /** A single captured Kafka publication: topic, key, and event payload. */
    private record Captured(String topic, String key, PriceUpdatedEvent event) {}

    // ── Property: every non-null seeded ticker is published, keyed by ticker ──────────────

    /**
     * For arbitrary non-empty subsets/orderings of the seeded registry and an arbitrary
     * userId, invoking {@code seed(userId)} publishes one {@code PriceUpdatedEvent} per
     * upserted ticker with a non-null computed price, to {@code market-prices}, keyed by
     * ticker, with payload equal to {@code DeterministicPriceCalculator.compute(...)}.
     *
     * <p><b>Validates: Requirements 1.1, 1.2, 2.1, 2.2</b>
     */
    @Property(tries = 50)
    void p1_seedPublishesOneEventPerNonNullTicker(
            @ForAll("seededSubsets") List<SeedTicker> subset,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 12) String userId) {

        List<Captured> published = new ArrayList<>();
        MarketDataSeedService service = newSeedService(subset, published);

        service.seed(userId);

        // Expected behaviour (the fix): one event per upserted ticker with a non-null price.
        List<SeedTicker> expected = subset.stream()
                .filter(t -> t.basePrice() != null)
                .toList();

        for (SeedTicker t : expected) {
            BigDecimal seededPrice =
                    DeterministicPriceCalculator.compute(t.basePrice(), t.ticker(), userId);
            PriceUpdatedEvent expectedEvent = new PriceUpdatedEvent(t.ticker(), seededPrice);

            assertThat(published)
                    .as("seed(\"%s\") must publish PriceUpdatedEvent(%s, %s) to %s keyed by ticker, "
                                    + "but %d event(s) were published",
                            userId, t.ticker(), seededPrice, TOPIC, published.size())
                    .anySatisfy(c -> {
                        assertThat(c.topic()).isEqualTo(TOPIC);
                        assertThat(c.key()).isEqualTo(t.ticker());
                        assertThat(c.event()).isEqualTo(expectedEvent);
                    });
        }
    }

    /**
     * Concrete documented counterexample: a full golden-state seed for {@code "e2e-user"}
     * must publish one event per registry ticker. On the unfixed code this fails with zero
     * events published — the headline symptom: "seed('e2e-user') upserts N docs but publishes
     * 0 events to market-prices".
     *
     * <p><b>Validates: Requirements 1.1, 1.2, 2.1, 2.2</b>
     */
    @Example
    void p1_fullGoldenStateSeedPublishesEventForEveryTicker() {
        List<Captured> published = new ArrayList<>();
        MarketDataSeedService service = newSeedService(REGISTRY, published);

        service.seed("e2e-user");

        long expectedNonNull = REGISTRY.stream().filter(t -> t.basePrice() != null).count();

        assertThat(published)
                .as("golden-state seed('e2e-user') upserts %d registry tickers but published %d "
                                + "PriceUpdatedEvent(s) to %s",
                        REGISTRY.size(), published.size(), TOPIC)
                .hasSize((int) expectedNonNull);

        for (SeedTicker t : REGISTRY) {
            if (t.basePrice() == null) {
                continue;
            }
            BigDecimal seededPrice =
                    DeterministicPriceCalculator.compute(t.basePrice(), t.ticker(), "e2e-user");
            assertThat(published)
                    .as("missing PriceUpdatedEvent for seeded ticker %s", t.ticker())
                    .contains(new Captured(TOPIC, t.ticker(),
                            new PriceUpdatedEvent(t.ticker(), seededPrice)));
        }
    }

    // ── Generators ────────────────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<List<SeedTicker>> seededSubsets() {
        return Arbitraries.of(REGISTRY)
                .list()
                .uniqueElements()
                .ofMinSize(1)
                .ofMaxSize(Math.min(REGISTRY.size(), 25));
    }

    // ── Construction + Kafka capture wiring ─────────────────────────────────────────────────

    /**
     * Builds a {@link MarketDataSeedService} whose registry returns {@code subset}, whose
     * MongoDB bulk path is stubbed, and whose (reflectively supplied) {@link KafkaTemplate}
     * records every {@code send(topic, key, event)} into {@code published}.
     */
    @SuppressWarnings("unchecked")
    private static MarketDataSeedService newSeedService(List<SeedTicker> subset, List<Captured> published) {
        SeedTickerRegistry registry = mock(SeedTickerRegistry.class);
        when(registry.all()).thenReturn(subset);

        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        BulkOperations bulk = mock(BulkOperations.class);
        when(mongoTemplate.bulkOps(any(BulkOperations.BulkMode.class), anyString())).thenReturn(bulk);
        BulkWriteResult result = mock(BulkWriteResult.class);
        when(bulk.execute()).thenReturn(result);
        when(result.getUpserts()).thenReturn(List.of());

        KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), any(PriceUpdatedEvent.class)))
                .thenAnswer(inv -> {
                    published.add(new Captured(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
                    return CompletableFuture.completedFuture(null);
                });

        return instantiate(mongoTemplate, registry, kafkaTemplate);
    }

    /**
     * Reflectively constructs {@link MarketDataSeedService} using the constructor with the
     * most parameters, resolving each argument by assignable type. This keeps the test valid
     * for both the unfixed {@code (MongoTemplate, SeedTickerRegistry)} constructor and the
     * fixed constructor that also takes a {@code KafkaTemplate}.
     */
    private static MarketDataSeedService instantiate(MongoTemplate mongoTemplate,
                                                     SeedTickerRegistry registry,
                                                     KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate) {
        try {
            Constructor<?> ctor = Arrays.stream(MarketDataSeedService.class.getDeclaredConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow(() -> new IllegalStateException("MarketDataSeedService has no constructor"));
            ctor.setAccessible(true);
            Object[] args = Arrays.stream(ctor.getParameterTypes())
                    .map(pt -> resolveArg(pt, mongoTemplate, registry, kafkaTemplate))
                    .toArray();
            return (MarketDataSeedService) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate MarketDataSeedService", e);
        }
    }

    private static Object resolveArg(Class<?> paramType,
                                     MongoTemplate mongoTemplate,
                                     SeedTickerRegistry registry,
                                     KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate) {
        if (paramType.isAssignableFrom(MongoTemplate.class)) {
            return mongoTemplate;
        }
        if (paramType.isAssignableFrom(SeedTickerRegistry.class)) {
            return registry;
        }
        if (paramType.isAssignableFrom(KafkaTemplate.class)) {
            return kafkaTemplate;
        }
        throw new IllegalStateException("Unexpected MarketDataSeedService constructor parameter: " + paramType);
    }

    // ── Registry loading (real classpath registry, with representative fallback) ────────────

    private static List<SeedTicker> loadRegistry() {
        try {
            SeedTickerRegistry registry = new SeedTickerRegistry();
            registry.load();
            List<SeedTicker> all = registry.all();
            if (!all.isEmpty()) {
                return all;
            }
        } catch (Exception ignored) {
            // Resource missing or invalid in this test context — fall back to a representative pool.
        }
        return fallbackPool();
    }

    /**
     * A representative pool covering every registry symbol shape (US equity, NSE {@code .NS},
     * crypto {@code -USD}, forex {@code =X}) with non-null base prices. Used only when the
     * canonical {@code seed/seed-tickers.json} is not on the test classpath.
     */
    private static List<SeedTicker> fallbackPool() {
        return List.of(
                new SeedTicker("AAPL",        "US_EQUITY", "USD", new BigDecimal("190.50"),  null, null),
                new SeedTicker("MSFT",        "US_EQUITY", "USD", new BigDecimal("410.25"),  null, null),
                new SeedTicker("NVDA",        "US_EQUITY", "USD", new BigDecimal("875.10"),  null, null),
                new SeedTicker("RELIANCE.NS", "NSE",       "INR", new BigDecimal("2950.00"), null, null),
                new SeedTicker("TCS.NS",      "NSE",       "INR", new BigDecimal("3850.00"), null, null),
                new SeedTicker("BTC-USD",     "CRYPTO",    "USD", new BigDecimal("64250.00"),null, null),
                new SeedTicker("ROSE-USD",    "CRYPTO",    "USD", new BigDecimal("0.0850"),  null, null),
                new SeedTicker("ETH-USD",     "CRYPTO",    "USD", new BigDecimal("3450.00"), null, null),
                new SeedTicker("USDCHF=X",    "FOREX",     "CHF", new BigDecimal("0.9050"),  null, null),
                new SeedTicker("NZDUSD=X",    "FOREX",     "USD", new BigDecimal("0.6100"),  null, null)
        );
    }
}
