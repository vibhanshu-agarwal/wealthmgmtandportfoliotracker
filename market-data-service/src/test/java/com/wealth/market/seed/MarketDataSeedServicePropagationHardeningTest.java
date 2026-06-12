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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2 test hardening for {@link MarketDataSeedService} seed propagation — Finding 6
 * of the {@code chatbot-asset-coverage-fix} audit.
 *
 * <p>Strengthens the existing {@link MarketDataSeedServicePropagationPropertyTest} with:
 * <ol>
 *   <li><b>Exactly-once current publication:</b> asserts that each expected ticker produces exactly
 *       one current-price {@code PriceUpdatedEvent} plus one history observation, and no unexpected
 *       events are published.</li>
 *   <li><b>Failure path:</b> asserts that no Kafka sends occur when {@code bulk.execute()}
 *       throws, preventing Redis from being hydrated for documents that never persisted.</li>
 * </ol>
 *
 * <p>These tests complement (not replace) the existing property test. They use the same
 * reflective construction pattern so they remain valid across constructor changes.
 */
class MarketDataSeedServicePropagationHardeningTest {

    private static final String TOPIC = "market-prices";

    private static final List<SeedTicker> REGISTRY = loadRegistry();

    /** A single captured Kafka publication: topic, key, and event payload. */
    private record Captured(String topic, String key, PriceUpdatedEvent event) {}

    // ── Exactly-once publication ──────────────────────────────────────────────────────────

    /**
     * For arbitrary non-empty subsets of the registry, each expected ticker produces
     * <b>exactly one current-price</b> {@code PriceUpdatedEvent} and one history observation,
     * and no unexpected events are published.
     *
     * <p>The existing property test uses {@code anySatisfy} which only checks that at least
     * one matching event exists. This test additionally asserts:
     * <ul>
     *   <li>Each ticker produces exactly one current-price event (no duplicate current sends).</li>
     *   <li>Total events = 2 × non-null-priced tickers (history + current).</li>
     *   <li>No events are published for tickers not in the expected set.</li>
     * </ul>
     */
    @Property(tries = 50)
    void p1_seedPublishesExactlyOneEventPerTicker_andNoExtras(
            @ForAll("seededSubsets") List<SeedTicker> subset,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 12) String userId) {

        List<Captured> published = new ArrayList<>();
        MarketDataSeedService service = newSeedService(subset, published, /* bulkThrows= */ false);

        service.seed(userId);

        List<SeedTicker> expectedTickers = subset.stream()
                .filter(t -> t.basePrice() != null)
                .toList();

        // Total count must equal two events per non-null-priced ticker (history + current).
        assertThat(published)
                .as("seed(\"%s\") must publish exactly %d event(s) for %d non-null-priced tickers, "
                        + "but published %d",
                        userId, expectedTickers.size() * 2, expectedTickers.size(), published.size())
                .hasSize(expectedTickers.size() * 2);

        // Each expected ticker appears exactly once with the current seeded price (latest observedAt).
        for (SeedTicker t : expectedTickers) {
            BigDecimal seededPrice =
                    DeterministicPriceCalculator.compute(t.basePrice(), t.ticker(), userId);
            List<Captured> tickerEvents = published.stream()
                    .filter(c -> c.key().equals(t.ticker()))
                    .toList();
            assertThat(tickerEvents).as("ticker %s event count", t.ticker()).hasSize(2);

            Instant latestObserved = tickerEvents.stream()
                    .map(c -> c.event().observedAt())
                    .max(Instant::compareTo)
                    .orElseThrow();
            Instant earliestObserved = tickerEvents.stream()
                    .map(c -> c.event().observedAt())
                    .min(Instant::compareTo)
                    .orElseThrow();
            assertThat(Duration.between(earliestObserved, latestObserved))
                    .as("ticker %s observation gap", t.ticker())
                    .isEqualTo(Duration.ofHours(25));

            long currentCount = tickerEvents.stream()
                    .filter(c -> c.event().observedAt().equals(latestObserved)
                            && c.event().newPrice().compareTo(seededPrice) == 0)
                    .count();
            assertThat(currentCount)
                    .as("ticker %s must appear exactly once with current price at latest observedAt, "
                            + "but appeared %d time(s)",
                            t.ticker(), currentCount)
                    .isEqualTo(1);

            BigDecimal historyPrice = DeterministicPriceCalculator.computeHistory(
                    seededPrice, t.ticker(), userId);
            long historyCount = tickerEvents.stream()
                    .filter(c -> c.event().observedAt().equals(earliestObserved)
                            && c.event().newPrice().compareTo(historyPrice) == 0)
                    .count();
            assertThat(historyCount)
                    .as("ticker %s must appear exactly once with history price at earliest observedAt, "
                            + "but appeared %d time(s)",
                            t.ticker(), historyCount)
                    .isEqualTo(1);
        }

        // No unexpected tickers in the published set.
        Set<String> expectedKeys = expectedTickers.stream()
                .map(SeedTicker::ticker)
                .collect(Collectors.toSet());
        published.forEach(c ->
                assertThat(expectedKeys)
                        .as("unexpected event published for ticker %s (not in expected set %s)",
                                c.key(), expectedKeys)
                        .contains(c.key()));
    }

    /**
     * Concrete exactly-once check for the full 160-ticker golden-state seed.
     */
    @Example
    void p1_fullGoldenStateSeed_publishesExactlyOneEventPerTicker() {
        List<Captured> published = new ArrayList<>();
        MarketDataSeedService service = newSeedService(REGISTRY, published, false);

        service.seed("e2e-user");

        long expectedCount = REGISTRY.stream().filter(t -> t.basePrice() != null).count();
        assertThat(published).hasSize((int) (expectedCount * 2));

        // Verify each ticker has exactly one current-price event at the latest observedAt.
        for (SeedTicker t : REGISTRY) {
            if (t.basePrice() == null) {
                continue;
            }
            BigDecimal seededPrice =
                    DeterministicPriceCalculator.compute(t.basePrice(), t.ticker(), "e2e-user");
            List<Captured> tickerEvents = published.stream()
                    .filter(c -> c.key().equals(t.ticker()))
                    .toList();
            Instant latestObserved = tickerEvents.stream()
                    .map(c -> c.event().observedAt())
                    .max(Instant::compareTo)
                    .orElseThrow();
            long currentCount = tickerEvents.stream()
                    .filter(c -> c.event().observedAt().equals(latestObserved)
                            && c.event().newPrice().compareTo(seededPrice) == 0)
                    .count();
            assertThat(currentCount)
                    .as("ticker %s must have exactly one current-price event", t.ticker())
                    .isEqualTo(1);
        }
    }

    // ── Failure path: no sends when bulk.execute() throws ────────────────────────────────

    /**
     * When {@code bulk.execute()} throws a {@link RuntimeException}, no Kafka events must be
     * published. This prevents Redis from being hydrated with prices for documents that were
     * never persisted to MongoDB.
     *
     * <p>This is the core ordering guarantee introduced by the Phase 1 fix: events are
     * collected in {@code pendingEvents} during the loop and published only after
     * {@code bulk.execute()} returns successfully.
     */
    @Example
    void p1_bulkExecuteThrows_noKafkaSendsOccur() {
        List<Captured> published = new ArrayList<>();
        MarketDataSeedService service = newSeedService(REGISTRY, published, /* bulkThrows= */ true);

        try {
            service.seed("e2e-user");
        } catch (RuntimeException ignored) {
            // Expected — bulk.execute() is stubbed to throw.
        }

        assertThat(published)
                .as("no Kafka events must be published when bulk.execute() throws — "
                        + "Redis must not be hydrated for documents that never persisted")
                .isEmpty();
    }

    /**
     * Property version: for any registry subset, if {@code bulk.execute()} throws,
     * zero events are published regardless of the subset size or userId.
     */
    @Property(tries = 20)
    void p1_bulkExecuteThrows_noKafkaSendsOccur_property(
            @ForAll("seededSubsets") List<SeedTicker> subset,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 12) String userId) {

        List<Captured> published = new ArrayList<>();
        MarketDataSeedService service = newSeedService(subset, published, /* bulkThrows= */ true);

        try {
            service.seed(userId);
        } catch (RuntimeException ignored) {
            // Expected.
        }

        assertThat(published)
                .as("no Kafka events must be published when bulk.execute() throws "
                        + "(subset size=%d, userId=%s)", subset.size(), userId)
                .isEmpty();
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

    // ── Construction helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static MarketDataSeedService newSeedService(List<SeedTicker> subset,
                                                        List<Captured> published,
                                                        boolean bulkThrows) {
        SeedTickerRegistry registry = mock(SeedTickerRegistry.class);
        when(registry.all()).thenReturn(subset);

        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        BulkOperations bulk = mock(BulkOperations.class);
        when(mongoTemplate.bulkOps(any(BulkOperations.BulkMode.class), anyString())).thenReturn(bulk);

        if (bulkThrows) {
            when(bulk.execute()).thenThrow(new RuntimeException("simulated MongoDB bulk failure"));
        } else {
            BulkWriteResult result = mock(BulkWriteResult.class);
            when(bulk.execute()).thenReturn(result);
            when(result.getUpserts()).thenReturn(List.of());
        }

        KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), any(PriceUpdatedEvent.class)))
                .thenAnswer(inv -> {
                    published.add(new Captured(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
                    return CompletableFuture.completedFuture(null);
                });

        return instantiate(mongoTemplate, registry, kafkaTemplate);
    }

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
        if (paramType.isAssignableFrom(MongoTemplate.class)) return mongoTemplate;
        if (paramType.isAssignableFrom(SeedTickerRegistry.class)) return registry;
        if (paramType.isAssignableFrom(KafkaTemplate.class)) return kafkaTemplate;
        throw new IllegalStateException("Unexpected constructor parameter: " + paramType);
    }

    private static List<SeedTicker> loadRegistry() {
        try {
            SeedTickerRegistry r = new SeedTickerRegistry();
            r.load();
            List<SeedTicker> all = r.all();
            if (!all.isEmpty()) return all;
        } catch (Exception ignored) { }
        return fallbackPool();
    }

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
