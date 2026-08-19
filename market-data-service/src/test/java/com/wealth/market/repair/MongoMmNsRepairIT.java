package com.wealth.market.repair;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.wealth.market.TestContainerImages;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@Testcontainers
class MongoMmNsRepairIT {

    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-01T00:00:00Z");

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer(TestContainerImages.MONGO);

    static MongoClient client;
    static MongoTemplate mongoTemplate;

    @BeforeAll
    static void connect() {
        client = MongoClients.create(mongo.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(client, "repair_it");
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    @BeforeEach
    void wipe() {
        mongoTemplate.getCollection(MongoMmNsRepairService.PRICES).deleteMany(new Document());
        mongoTemplate.getCollection(MongoMmNsRepairService.LEASES).deleteMany(new Document());
        mongoTemplate.getCollection(MongoMmNsRepairService.ARCHIVES).deleteMany(new Document());
    }

    @Test
    void crashAfterDestinationWrite_retryConvergesWithoutDuplicate() {
        insertPrice(MongoMmNsRepairService.SOURCE_TICKER, "100", T2, "90", T1);
        abortThenRetry("afterDestinationWrite");
        assertMigratedFromSource();
        assertThat(priceCount()).isEqualTo(1);
    }

    @Test
    void crashAfterSourceDeleteBeforeMigrated_absenceClassifiedAsSuccess() {
        insertPrice(MongoMmNsRepairService.SOURCE_TICKER, "100", T2, "90", T1);
        abortThenRetry("afterArchiveCommit");
        assertMigratedFromSource();
        Document lease = lease();
        assertThat(lease.getString("state")).isEqualTo("COMPLETE");
    }

    @Test
    void crashBetweenArchivePendingAndSourceDelete_reconciliationRetriesAndCommits() {
        insertPrice(MongoMmNsRepairService.SOURCE_TICKER, "100", T2, "90", T1);
        abortThenRetry("afterArchivePending");
        assertMigratedFromSource();
        assertThat(archives()).singleElement().extracting(d -> d.getString("status")).isEqualTo("COMMITTED");
    }

    @Test
    void crashAfterSourceDeleteBeforeArchiveCommitted_pendingPromotedNotReDeleted() {
        insertPrice(MongoMmNsRepairService.SOURCE_TICKER, "100", T2, "90", T1);
        abortThenRetry("afterSourceDelete");
        assertThat(price(MongoMmNsRepairService.SOURCE_TICKER)).isNull();
        assertThat(archives()).singleElement().extracting(d -> d.getString("status")).isEqualTo("COMMITTED");
        assertMigratedFromSource();
    }

    @Test
    void leaseExpiryMidRepair_staleRunnerWriteRejected() {
        insertPrice(MongoMmNsRepairService.SOURCE_TICKER, "100", T2, "90", T1);
        insertPrice(MongoMmNsRepairService.DEST_TICKER, "50", T1, "40", T1);
        MongoMmNsRepairService stale = service("stale");
        RepairHooks hooks = new RepairHooks();
        hooks.beforeDestinationMutate = () -> {
            mongoTemplate
                    .getCollection(MongoMmNsRepairService.LEASES)
                    .updateOne(
                            Filters.eq("_id", MongoMmNsRepairService.REPAIR_ID),
                            Updates.set("expiresAt", Date.from(Instant.parse("2000-01-01T00:00:00Z"))));
            try {
                service("fresh").run(RepairHooks.abortAfter("afterFence"));
            } catch (RepairAbortedException ignored) {
                // fresh generation holds the fence
            }
        };
        RepairResult result = stale.run(hooks);
        assertThat(result.outcome()).isEqualTo(RepairOutcome.LOST_FENCE);
        Document dest = price(MongoMmNsRepairService.DEST_TICKER);
        assertThat(dest).isNotNull();
        assertThat(asLong(dest.get("repairGeneration"))).isEqualTo(2L);
        assertThat(MongoMmNsRepairService.toTuple(dest).currentPrice()).isEqualByComparingTo("50");
    }

    @Test
    void concurrentFirstClaim_exactlyOneWinnerLoserClassified() throws Exception {
        insertPrice(MongoMmNsRepairService.SOURCE_TICKER, "100", T2, "90", T1);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<RepairResult> winner = new AtomicReference<>();
        AtomicReference<RepairResult> loser = new AtomicReference<>();

        RepairHooks hold = new RepairHooks();
        hold.afterClaim = () -> {
            claimed.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("release timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };

        Thread first = new Thread(() -> winner.set(service("one").run(hold)));
        first.start();
        assertThat(claimed.await(30, TimeUnit.SECONDS)).isTrue();

        loser.set(service("two").run());
        release.countDown();
        first.join(TimeUnit.SECONDS.toMillis(30));

        assertThat(winner.get().outcome()).isEqualTo(RepairOutcome.COMPLETE);
        assertThat(loser.get().outcome()).isEqualTo(RepairOutcome.FOREIGN_LEASE);
        assertThat(loser.get().exitCode()).isNotZero();
        assertMigratedFromSource();
    }

    @Test
    void sameGenerationRetryAgainstAlreadyFencedDocument_succeeds() {
        insertPrice(MongoMmNsRepairService.SOURCE_TICKER, "100", T2, "90", T1);
        RepairHooks hooks = new RepairHooks();
        hooks.afterClaim = () -> {
            long generation = asLong(lease().get("generation"));
            mongoTemplate
                    .getCollection(MongoMmNsRepairService.PRICES)
                    .updateOne(
                            Filters.eq("_id", MongoMmNsRepairService.SOURCE_TICKER),
                            Updates.set("repairGeneration", generation));
        };
        RepairResult result = service("owner").run(hooks);
        assertThat(result.outcome()).isEqualTo(RepairOutcome.COMPLETE);
        assertMigratedFromSource();
    }

    @Test
    void conflictingUpdatedAtPayloads_failedConflictIsTerminal() {
        insertPrice(MongoMmNsRepairService.SOURCE_TICKER, "100", T1, "90", T1);
        insertPrice(MongoMmNsRepairService.DEST_TICKER, "50", T1, "40", T1);
        RepairResult first = service("owner").run();
        assertThat(first.outcome()).isEqualTo(RepairOutcome.FAILED_CONFLICT);
        assertThat(lease().getString("state")).isEqualTo("FAILED_CONFLICT");

        RepairResult second = service("retry").run();
        assertThat(second.outcome()).isEqualTo(RepairOutcome.FAILED_CONFLICT);
        assertThat(second.exitCode()).isNotZero();
        assertThat(price(MongoMmNsRepairService.SOURCE_TICKER)).isNotNull();
        assertThat(price(MongoMmNsRepairService.DEST_TICKER)).isNotNull();
    }

    @Test
    void bothDocumentsExist_newerUpdatedAtWins() {
        insertPrice(MongoMmNsRepairService.SOURCE_TICKER, "100", T2, "90", T1);
        insertPrice(MongoMmNsRepairService.DEST_TICKER, "50", T1, "40", T1);
        assertThat(service("owner").run().outcome()).isEqualTo(RepairOutcome.COMPLETE);
        Document dest = price(MongoMmNsRepairService.DEST_TICKER);
        assertThat(MongoMmNsRepairService.toTuple(dest).currentPrice()).isEqualByComparingTo("100");
        assertThat(price(MongoMmNsRepairService.SOURCE_TICKER)).isNull();
    }

    @Test
    void bothDocumentsExist_knownUpdatedAtBeatsNull() {
        insertPrice(MongoMmNsRepairService.SOURCE_TICKER, "100", T2, "90", T1);
        insertPrice(MongoMmNsRepairService.DEST_TICKER, "50", null, "40", T1);
        assertThat(service("owner").run().outcome()).isEqualTo(RepairOutcome.COMPLETE);
        assertThat(MongoMmNsRepairService.toTuple(price(MongoMmNsRepairService.DEST_TICKER)).updatedAt()).isEqualTo(T2);
    }

    @Test
    void bothDocumentsExist_bothNullUpdatedAt_destinationRetained() {
        insertPrice(MongoMmNsRepairService.SOURCE_TICKER, "100", null, "90", T1);
        insertPrice(MongoMmNsRepairService.DEST_TICKER, "50", null, "40", T1);
        assertThat(service("owner").run().outcome()).isEqualTo(RepairOutcome.COMPLETE);
        Document dest = price(MongoMmNsRepairService.DEST_TICKER);
        assertThat(MongoMmNsRepairService.toTuple(dest).currentPrice()).isEqualByComparingTo("50");
        assertThat(price(MongoMmNsRepairService.SOURCE_TICKER)).isNull();
        assertThat(archives()).isNotEmpty();
    }

    @Test
    void bothDocumentsExist_sameUpdatedAtIdenticalValues_collapseIdempotently() {
        insertPrice(MongoMmNsRepairService.SOURCE_TICKER, "100", T1, "90", T1);
        insertPrice(MongoMmNsRepairService.DEST_TICKER, "100", T1, "90", T1);
        assertThat(service("owner").run().outcome()).isEqualTo(RepairOutcome.COMPLETE);
        assertThat(service("owner").run().outcome()).isEqualTo(RepairOutcome.ALREADY_COMPLETE);
        assertThat(priceCount()).isEqualTo(1);
        assertThat(MongoMmNsRepairService.toTuple(price(MongoMmNsRepairService.DEST_TICKER)).currentPrice())
                .isEqualByComparingTo("100");
    }

    @Test
    void fiveFieldTupleMovesAtomically_destinationNeverMixesSources() {
        insertPrice(MongoMmNsRepairService.SOURCE_TICKER, "100", T2, "90", T1);
        insertPrice(MongoMmNsRepairService.DEST_TICKER, "50", T1, "40", Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(service("owner").run().outcome()).isEqualTo(RepairOutcome.COMPLETE);
        PriceTuple dest = MongoMmNsRepairService.toTuple(price(MongoMmNsRepairService.DEST_TICKER));
        assertThat(dest.currentPrice()).isEqualByComparingTo("100");
        assertThat(dest.previousReferencePrice()).isEqualByComparingTo("90");
        assertThat(dest.previousReferenceAt()).isEqualTo(T1);
        assertThat(dest.updatedAt()).isEqualTo(T2);
        assertThat(dest.currentPrice()).isNotEqualByComparingTo("50");
        assertThat(dest.previousReferencePrice()).isNotEqualByComparingTo("40");
    }

    @Test
    void multiplePendingGenerations_highestCorroboratedWinsOthersSuperseded() {
        PriceTuple intended = new PriceTuple(new BigDecimal("100"), "INR", T2, new BigDecimal("90"), T1);
        PriceTuple stale = new PriceTuple(new BigDecimal("1"), "INR", T1, new BigDecimal("1"), T1);
        insertPrice(MongoMmNsRepairService.DEST_TICKER, "100", T2, "90", T1);
        insertPrice(MongoMmNsRepairService.SOURCE_TICKER, "100", T2, "90", T1);
        insertArchive(1L, stale, STATUS_PENDING);
        insertArchive(2L, intended, STATUS_PENDING);

        RepairResult result = service("owner").run();
        assertThat(result.outcome()).isEqualTo(RepairOutcome.COMPLETE);

        List<Document> rows = archives();
        Document gen1 = rows.stream().filter(d -> asLong(d.get("generation")) == 1L).findFirst().orElseThrow();
        Document gen2 = rows.stream().filter(d -> asLong(d.get("generation")) == 2L).findFirst().orElseThrow();
        assertThat(gen1.getString("status")).isEqualTo("SUPERSEDED");
        assertThat(gen2.getString("status")).isEqualTo("COMMITTED");
        assertThat(price(MongoMmNsRepairService.SOURCE_TICKER)).isNull();
    }

    @Test
    void destinationMissingExpectedTuple_deletionRefused() {
        insertPrice(MongoMmNsRepairService.SOURCE_TICKER, "100", T2, "90", T1);
        insertPrice(MongoMmNsRepairService.DEST_TICKER, "50", T1, "40", T1);
        PriceTuple claimed = new PriceTuple(new BigDecimal("100"), "INR", T2, new BigDecimal("90"), T1);
        insertArchive(1L, claimed, STATUS_PENDING);

        RepairResult result = service("owner").run();
        assertThat(result.outcome()).isEqualTo(RepairOutcome.FAILED_CONFLICT);
        assertThat(price(MongoMmNsRepairService.SOURCE_TICKER)).isNotNull();
        assertThat(MongoMmNsRepairService.toTuple(price(MongoMmNsRepairService.DEST_TICKER)).currentPrice())
                .isEqualByComparingTo("50");
    }

    private void abortThenRetry(String phase) {
        assertThatThrownBy(() -> service("owner").run(RepairHooks.abortAfter(phase)))
                .isInstanceOf(RepairAbortedException.class);
        mongoTemplate
                .getCollection(MongoMmNsRepairService.LEASES)
                .updateOne(
                        Filters.eq("_id", MongoMmNsRepairService.REPAIR_ID),
                        Updates.set("expiresAt", Date.from(Instant.parse("2000-01-01T00:00:00Z"))));
        RepairResult retry = service("retry").run();
        assertThat(retry.outcome()).isEqualTo(RepairOutcome.COMPLETE);
    }

    private void assertMigratedFromSource() {
        assertThat(price(MongoMmNsRepairService.SOURCE_TICKER)).isNull();
        Document dest = price(MongoMmNsRepairService.DEST_TICKER);
        assertThat(dest).isNotNull();
        PriceTuple tuple = MongoMmNsRepairService.toTuple(dest);
        assertThat(tuple.currentPrice()).isEqualByComparingTo("100");
        assertThat(tuple.previousReferencePrice()).isEqualByComparingTo("90");
        assertThat(tuple.updatedAt()).isEqualTo(T2);
        assertThat(dest.get("repairGeneration")).isNull();
    }

    private MongoMmNsRepairService service(String owner) {
        return new MongoMmNsRepairService(
                mongoTemplate, Clock.systemUTC(), () -> owner, Duration.ofMinutes(5));
    }

    private void insertPrice(String ticker, String price, Instant updatedAt, String prev, Instant prevAt) {
        Document document = new Document("_id", ticker)
                .append("currentPrice", new Decimal128(new BigDecimal(price)))
                .append("quoteCurrency", "INR")
                .append("updatedAt", updatedAt == null ? null : Date.from(updatedAt))
                .append("previousReferencePrice", new Decimal128(new BigDecimal(prev)))
                .append("previousReferenceAt", Date.from(prevAt));
        mongoTemplate.getCollection(MongoMmNsRepairService.PRICES).insertOne(document);
    }

    private void insertArchive(long generation, PriceTuple intended, String status) {
        Document source = MongoMmNsRepairService.tupleToBson(
                new PriceTuple(new BigDecimal("100"), "INR", T2, new BigDecimal("90"), T1));
        Document payload = new Document()
                .append("source", source)
                .append("destinationBefore", null)
                .append("intendedDestination", MongoMmNsRepairService.tupleToBson(intended));
        mongoTemplate
                .getCollection(MongoMmNsRepairService.ARCHIVES)
                .insertOne(new Document()
                        .append("repairId", MongoMmNsRepairService.REPAIR_ID)
                        .append("generation", generation)
                        .append("sourceCollection", MongoMmNsRepairService.PRICES)
                        .append("sourceId", MongoMmNsRepairService.SOURCE_TICKER)
                        .append("payload", payload)
                        .append("payloadHash", MongoMmNsRepairService.sha256(payload.toJson()))
                        .append("decision", "MIGRATE_SOURCE")
                        .append("status", status));
    }

    private Document price(String ticker) {
        return mongoTemplate.getCollection(MongoMmNsRepairService.PRICES).find(Filters.eq("_id", ticker)).first();
    }

    private long priceCount() {
        return mongoTemplate.getCollection(MongoMmNsRepairService.PRICES).countDocuments();
    }

    private Document lease() {
        return mongoTemplate
                .getCollection(MongoMmNsRepairService.LEASES)
                .find(Filters.eq("_id", MongoMmNsRepairService.REPAIR_ID))
                .first();
    }

    private List<Document> archives() {
        return mongoTemplate
                .getCollection(MongoMmNsRepairService.ARCHIVES)
                .find()
                .into(new ArrayList<>());
    }

    private static long asLong(Object value) {
        return MongoMmNsRepairService.asLong(value);
    }

    private static final String STATUS_PENDING = "PENDING";
}
