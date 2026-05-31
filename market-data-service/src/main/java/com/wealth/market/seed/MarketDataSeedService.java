package com.wealth.market.seed;

import com.mongodb.bulk.BulkWriteResult;
import com.wealth.market.events.PriceUpdatedEvent;
import com.wealth.market.seed.SeedTickerRegistry.SeedTicker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Upserts the deterministic golden-state price set for the 160 registry tickers into the
 * MongoDB {@code market_prices} collection, then publishes a {@link PriceUpdatedEvent} for
 * each seeded ticker to the {@code market-prices} Kafka topic so that insight-service's Redis
 * cache is hydrated without requiring a service restart.
 *
 * <p>Documents outside the 160-ticker set are left untouched (design requirement P9).
 * Upsert semantics mean running the seeder twice is a no-op at the value level: the same
 * {@code (ticker, userId)} pair yields a byte-identical {@code currentPrice} by construction
 * of {@link DeterministicPriceCalculator}.
 */
@Service
public class MarketDataSeedService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataSeedService.class);
    private static final String COLLECTION = "market_prices";
    private static final String TOPIC = "market-prices";

    private final MongoTemplate mongoTemplate;
    private final SeedTickerRegistry registry;
    private final KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;

    public MarketDataSeedService(MongoTemplate mongoTemplate,
                                 SeedTickerRegistry registry,
                                 KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate) {
        this.mongoTemplate = mongoTemplate;
        this.registry = registry;
        this.kafkaTemplate = kafkaTemplate;
    }

    public record SeedResult(int pricesUpserted) {}

    public SeedResult seed(String userId) {
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, COLLECTION);
        Instant now = Instant.now();
        for (SeedTicker t : registry.all()) {
            Query q = new Query(Criteria.where("_id").is(t.ticker()));
            // Capture the computed price once so MongoDB and Kafka use the identical value.
            BigDecimal seededPrice = DeterministicPriceCalculator.compute(t.basePrice(), t.ticker(), userId);
            Update u = new Update()
                    .set("currentPrice", seededPrice)
                    .set("quoteCurrency", t.quoteCurrency())
                    .set("updatedAt", now);
            bulk.upsert(q, u);
            // Publish to Kafka so insight-service Redis is hydrated without a restart.
            kafkaTemplate.send(TOPIC, t.ticker(), new PriceUpdatedEvent(t.ticker(), seededPrice));
        }
        BulkWriteResult result = bulk.execute();

        int upserts = result.getUpserts().size();
        int modified = result.getModifiedCount();
        log.info("Golden-state market-data seed complete: userId={} upserts={} modified={} matched={} eventsPublished={}",
                userId, upserts, modified, result.getMatchedCount(), registry.all().size());
        return new SeedResult(registry.all().size());
    }
}
