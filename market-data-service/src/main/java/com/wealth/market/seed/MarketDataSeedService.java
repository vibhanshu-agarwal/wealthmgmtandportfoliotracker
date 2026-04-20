package com.wealth.market.seed;

import com.mongodb.bulk.BulkWriteResult;
import com.wealth.market.seed.SeedTickerRegistry.SeedTicker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Upserts the deterministic golden-state price set for the 160 registry tickers into the
 * MongoDB {@code market_prices} collection.
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

    private final MongoTemplate mongoTemplate;
    private final SeedTickerRegistry registry;

    public MarketDataSeedService(MongoTemplate mongoTemplate, SeedTickerRegistry registry) {
        this.mongoTemplate = mongoTemplate;
        this.registry = registry;
    }

    public record SeedResult(int pricesUpserted) {}

    public SeedResult seed(String userId) {
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, COLLECTION);
        Instant now = Instant.now();
        for (SeedTicker t : registry.all()) {
            Query q = new Query(Criteria.where("_id").is(t.ticker()));
            Update u = new Update()
                    .set("currentPrice", DeterministicPriceCalculator.compute(t.basePrice(), t.ticker(), userId))
                    .set("quoteCurrency", t.quoteCurrency())
                    .set("updatedAt", now);
            bulk.upsert(q, u);
        }
        BulkWriteResult result = bulk.execute();

        int upserts = result.getUpserts().size();
        int modified = result.getModifiedCount();
        log.info("Golden-state market-data seed complete: userId={} upserts={} modified={} matched={}",
                userId, upserts, modified, result.getMatchedCount());
        return new SeedResult(registry.all().size());
    }
}
