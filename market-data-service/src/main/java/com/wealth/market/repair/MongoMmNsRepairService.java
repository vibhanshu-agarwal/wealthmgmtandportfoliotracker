package com.wealth.market.repair;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Fenced, topology-independent {@code MM.NS} → {@code M&amp;M.NS} repair. Document-level
 * predicates — not multi-document transactions — are the correctness mechanism.
 */
@Component
public class MongoMmNsRepairService {

    public static final String REPAIR_ID = "mm-ns-repair";
    public static final String SOURCE_TICKER = "MM.NS";
    public static final String DEST_TICKER = "M&M.NS";
    public static final String PRICES = "market_prices";
    public static final String LEASES = "repair_leases";
    public static final String ARCHIVES = "repair_archive";

    static final String[] TUPLE_FIELDS = {
        "currentPrice", "quoteCurrency", "updatedAt", "previousReferencePrice", "previousReferenceAt"
    };

    private static final String STATE_CLAIMED = "CLAIMED";
    private static final String STATE_MIGRATED = "MIGRATED";
    private static final String STATE_VERIFIED = "VERIFIED";
    private static final String STATE_COMPLETE = "COMPLETE";
    private static final String STATE_FAILED_CONFLICT = "FAILED_CONFLICT";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_COMMITTED = "COMMITTED";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";

    private final MongoTemplate mongoTemplate;
    private final Clock clock;
    private final Supplier<String> ownerFactory;
    private final Duration leaseTtl;

    @Autowired
    public MongoMmNsRepairService(MongoTemplate mongoTemplate) {
        this(mongoTemplate, Clock.systemUTC(), () -> UUID.randomUUID().toString(), Duration.ofSeconds(60));
    }

    MongoMmNsRepairService(
            MongoTemplate mongoTemplate, Clock clock, Supplier<String> ownerFactory, Duration leaseTtl) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
        this.ownerFactory = ownerFactory;
        this.leaseTtl = leaseTtl;
    }

    public RepairResult run() {
        return run(new RepairHooks());
    }

    public RepairResult run(RepairHooks hooks) {
        ensureIndexes();
        String owner = ownerFactory.get();
        Claim claim;
        try {
            claim = claimLease(owner);
        } catch (RepairStop stop) {
            return new RepairResult(stop.outcome, stop.generation);
        }
        try {
            hooks.afterClaim.run();
            reconcile(claim, hooks);
            renew(claim, STATE_CLAIMED);
            migrate(claim, hooks);
            transition(claim, STATE_CLAIMED, STATE_MIGRATED);
            renew(claim, STATE_MIGRATED);
            verify(claim);
            transition(claim, STATE_MIGRATED, STATE_VERIFIED);
            renew(claim, STATE_VERIFIED);
            clearDestinationFence(claim);
            transition(claim, STATE_VERIFIED, STATE_COMPLETE);
            return new RepairResult(RepairOutcome.COMPLETE, claim.generation);
        } catch (RepairAbortedException e) {
            throw e;
        } catch (RepairStop stop) {
            if (stop.outcome == RepairOutcome.FAILED_CONFLICT) {
                persistConflict(claim);
            }
            return new RepairResult(stop.outcome, stop.generation);
        }
    }

    private void ensureIndexes() {
        MongoCollection<Document> archives = archives();
        archives.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("repairId"),
                        Indexes.ascending("generation"),
                        Indexes.ascending("sourceCollection"),
                        Indexes.ascending("sourceId")),
                new IndexOptions().unique(true).name("repair_archive_generation_unique"));
        archives.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("repairId"),
                        Indexes.ascending("sourceCollection"),
                        Indexes.ascending("sourceId")),
                new IndexOptions()
                        .unique(true)
                        .name("repair_archive_committed_unique")
                        .partialFilterExpression(Filters.eq("status", STATUS_COMMITTED)));
    }

    private Claim claimLease(String owner) {
        Instant now = clock.instant();
        Bson filter = Filters.and(
                Filters.eq("_id", REPAIR_ID),
                Filters.nin("state", STATE_COMPLETE, STATE_FAILED_CONFLICT),
                Filters.or(Filters.exists("expiresAt", false), Filters.lt("expiresAt", Date.from(now))));
        Bson update = Updates.combine(
                Updates.set("owner", owner),
                Updates.set("state", STATE_CLAIMED),
                Updates.set("expiresAt", Date.from(now.plus(leaseTtl))),
                Updates.inc("generation", 1L));
        try {
            Document after = leases().findOneAndUpdate(
                    filter,
                    update,
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
            if (after == null) {
                throw new RepairStop(RepairOutcome.UNVERIFIABLE, 0);
            }
            return new Claim(owner, asLong(after.get("generation")));
        } catch (RuntimeException e) {
            if (isDuplicateKey(e)) {
                return classifyDuplicate();
            }
            throw e;
        }
    }

    private Claim classifyDuplicate() {
        Document lease = leases().find(Filters.eq("_id", REPAIR_ID)).first();
        if (lease == null) {
            throw new RepairStop(RepairOutcome.UNVERIFIABLE, 0);
        }
        long generation = asLong(lease.get("generation"));
        String state = lease.getString("state");
        if (STATE_COMPLETE.equals(state)) {
            throw new RepairStop(RepairOutcome.ALREADY_COMPLETE, generation);
        }
        if (STATE_FAILED_CONFLICT.equals(state)) {
            throw new RepairStop(RepairOutcome.FAILED_CONFLICT, generation);
        }
        Instant expiresAt = toInstant(lease.get("expiresAt"));
        if (expiresAt != null && !expiresAt.isBefore(clock.instant())) {
            throw new RepairStop(RepairOutcome.FOREIGN_LEASE, generation);
        }
        throw new RepairStop(RepairOutcome.UNVERIFIABLE, generation);
    }

    private void reconcile(Claim claim, RepairHooks hooks) {
        List<Document> records = archives()
                .find(Filters.and(
                        Filters.eq("repairId", REPAIR_ID),
                        Filters.eq("sourceCollection", PRICES),
                        Filters.eq("sourceId", SOURCE_TICKER)))
                .into(new ArrayList<>());
        if (records.isEmpty()) {
            return;
        }

        Document dest = prices().find(Filters.eq("_id", DEST_TICKER)).first();
        Document source = prices().find(Filters.eq("_id", SOURCE_TICKER)).first();

        List<Document> committed = records.stream()
                .filter(r -> STATUS_COMMITTED.equals(r.getString("status")))
                .toList();
        if (committed.size() > 1) {
            throw new RepairStop(RepairOutcome.FAILED_CONFLICT, claim.generation);
        }
        if (committed.size() == 1) {
            Document record = committed.get(0);
            if (source == null && destCorroborates(dest, record)) {
                return;
            }
            throw new RepairStop(RepairOutcome.FAILED_CONFLICT, claim.generation);
        }

        List<Document> pending = records.stream()
                .filter(r -> STATUS_PENDING.equals(r.getString("status")))
                .sorted(Comparator.comparingLong((Document r) -> asLong(r.get("generation"))).reversed())
                .toList();
        if (pending.isEmpty()) {
            return;
        }

        Document winner = null;
        for (Document candidate : pending) {
            if (destCorroborates(dest, candidate)) {
                winner = candidate;
                break;
            }
        }
        if (winner == null) {
            throw new RepairStop(RepairOutcome.FAILED_CONFLICT, claim.generation);
        }

        for (Document candidate : pending) {
            if (!candidate.get("_id").equals(winner.get("_id"))) {
                archives().updateOne(
                        Filters.eq("_id", candidate.get("_id")),
                        Updates.set("status", STATUS_SUPERSEDED));
            }
        }

        dest = prices().find(Filters.eq("_id", DEST_TICKER)).first();
        if (!destCorroborates(dest, winner)) {
            throw new RepairStop(RepairOutcome.FAILED_CONFLICT, claim.generation);
        }
        source = prices().find(Filters.eq("_id", SOURCE_TICKER)).first();
        if (source != null) {
            Document expectedSource = payloadSection(winner, "source");
            acquireFence(SOURCE_TICKER, claim.generation);
            deleteSource(claim, expectedSource, intendedFrom(winner), hooks);
        }
        hooks.beforeArchiveCommit.run();
        archives().updateOne(
                Filters.and(Filters.eq("_id", winner.get("_id")), Filters.eq("status", STATUS_PENDING)),
                Updates.set("status", STATUS_COMMITTED));
    }

    private void migrate(Claim claim, RepairHooks hooks) {
        Document dest = acquireFence(DEST_TICKER, claim.generation);
        Document source = acquireFence(SOURCE_TICKER, claim.generation);
        hooks.afterFence.run();

        if (source == null) {
            Document currentDest = prices().find(Filters.eq("_id", DEST_TICKER)).first();
            if (currentDest != null) {
                return;
            }
            throw new RepairStop(RepairOutcome.UNVERIFIABLE, claim.generation);
        }

        CollisionPolicy.Result decision = CollisionPolicy.decide(toTuple(source), dest == null ? null : toTuple(dest));
        if (decision.kind() == CollisionPolicy.Kind.CONFLICT) {
            throw new RepairStop(RepairOutcome.FAILED_CONFLICT, claim.generation);
        }

        Document intended = tupleToBson(decision.intended());
        writeDestination(claim, dest, intended, hooks);

        Document payload = new Document()
                .append("source", copyTuple(source))
                .append("destinationBefore", dest == null ? null : copyTuple(dest))
                .append("intendedDestination", copyTuple(intended));
        writeArchivePending(claim, payload, decision.kind().name());
        hooks.afterArchivePending.run();
        deleteSource(claim, source, intended, hooks);
        hooks.beforeArchiveCommit.run();
        commitArchive(claim);
        hooks.afterArchiveCommit.run();
    }

    private void writeDestination(Claim claim, Document destBefore, Document intended, RepairHooks hooks) {
        if (destBefore == null) {
            Document insert = copyTuple(intended);
            insert.put("_id", DEST_TICKER);
            insert.put("repairGeneration", claim.generation);
            try {
                hooks.beforeDestinationMutate.run();
                prices().insertOne(insert);
                hooks.afterDestinationWrite.run();
                return;
            } catch (RuntimeException e) {
                if (!isDuplicateKey(e)) {
                    throw e;
                }
            }
        }
        Document current = prices().find(Filters.eq("_id", DEST_TICKER)).first();
        if (current != null
                && generationEquals(current, claim.generation)
                && tupleEquals(current, intended)) {
            hooks.afterDestinationWrite.run();
            return;
        }
        hooks.beforeDestinationMutate.run();
        Document expected = destBefore != null ? destBefore : current;
        UpdateResult result = prices().updateOne(
                mutationFilter(DEST_TICKER, claim.generation, expected),
                tupleSet(intended));
        if (result.getMatchedCount() > 0) {
            hooks.afterDestinationWrite.run();
            return;
        }
        classifyDestinationWrite(claim, expected, intended);
        hooks.afterDestinationWrite.run();
    }

    private void classifyDestinationWrite(Claim claim, Document capturedInput, Document intended) {
        Document now = prices().find(Filters.eq("_id", DEST_TICKER)).first();
        if (now == null) {
            throw new RepairStop(RepairOutcome.FAILED_CONFLICT, claim.generation);
        }
        Long generation = asLong(now.get("repairGeneration"));
        if (generation == null || generation != claim.generation) {
            throw new RepairStop(RepairOutcome.LOST_FENCE, claim.generation);
        }
        if (tupleEquals(now, intended)) {
            return;
        }
        if (tupleEquals(now, capturedInput)) {
            UpdateResult retry = prices().updateOne(
                    mutationFilter(DEST_TICKER, claim.generation, capturedInput),
                    tupleSet(intended));
            if (retry.getMatchedCount() == 0) {
                throw new RepairStop(RepairOutcome.FAILED_CONFLICT, claim.generation);
            }
            return;
        }
        throw new RepairStop(RepairOutcome.FAILED_CONFLICT, claim.generation);
    }

    private void deleteSource(Claim claim, Document expectedSource, Document intended, RepairHooks hooks) {
        DeleteResult deleted = prices().deleteOne(mutationFilter(SOURCE_TICKER, claim.generation, expectedSource));
        if (deleted.getDeletedCount() > 0) {
            hooks.afterSourceDelete.run();
            return;
        }
        classifySourceDelete(claim, expectedSource, intended);
        hooks.afterSourceDelete.run();
    }

    private void classifySourceDelete(Claim claim, Document capturedInput, Document intended) {
        Document source = prices().find(Filters.eq("_id", SOURCE_TICKER)).first();
        Document dest = prices().find(Filters.eq("_id", DEST_TICKER)).first();
        if (source == null) {
            if (destCorroboratesTuple(dest, intended)) {
                return;
            }
            throw new RepairStop(RepairOutcome.FAILED_CONFLICT, claim.generation);
        }
        Long generation = asLong(source.get("repairGeneration"));
        if (generation == null || generation != claim.generation) {
            throw new RepairStop(RepairOutcome.LOST_FENCE, claim.generation);
        }
        if (tupleEquals(source, capturedInput)) {
            DeleteResult retry = prices().deleteOne(mutationFilter(SOURCE_TICKER, claim.generation, capturedInput));
            if (retry.getDeletedCount() == 0) {
                throw new RepairStop(RepairOutcome.FAILED_CONFLICT, claim.generation);
            }
            return;
        }
        throw new RepairStop(RepairOutcome.FAILED_CONFLICT, claim.generation);
    }

    private void writeArchivePending(Claim claim, Document payload, String decision) {
        Document archive = new Document()
                .append("repairId", REPAIR_ID)
                .append("generation", claim.generation)
                .append("sourceCollection", PRICES)
                .append("sourceId", SOURCE_TICKER)
                .append("payload", payload)
                .append("payloadHash", sha256(payload.toJson()))
                .append("decision", decision)
                .append("status", STATUS_PENDING);
        try {
            archives().insertOne(archive);
        } catch (RuntimeException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
        }
    }

    private void commitArchive(Claim claim) {
        archives().updateOne(
                Filters.and(
                        Filters.eq("repairId", REPAIR_ID),
                        Filters.eq("generation", claim.generation),
                        Filters.eq("sourceCollection", PRICES),
                        Filters.eq("sourceId", SOURCE_TICKER),
                        Filters.eq("status", STATUS_PENDING)),
                Updates.set("status", STATUS_COMMITTED));
    }

    /**
     * Fence acquisition: absent-or-lower. Same-generation retry is {@code = G}, never a write
     * with absent-or-lower.
     */
    Document acquireFence(String ticker, long generation) {
        Bson acquire = Filters.and(
                Filters.eq("_id", ticker),
                Filters.or(
                        Filters.exists("repairGeneration", false),
                        Filters.lt("repairGeneration", generation)));
        Document after = prices().findOneAndUpdate(
                acquire,
                Updates.set("repairGeneration", generation),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (after != null) {
            return after;
        }
        Document existing = prices().find(Filters.eq("_id", ticker)).first();
        if (existing == null) {
            return null;
        }
        Long held = asLong(existing.get("repairGeneration"));
        if (held != null && held == generation) {
            return existing;
        }
        throw new RepairStop(RepairOutcome.LOST_FENCE, generation);
    }

    private void verify(Claim claim) {
        Document source = prices().find(Filters.eq("_id", SOURCE_TICKER)).first();
        Document dest = prices().find(Filters.eq("_id", DEST_TICKER)).first();
        if (source != null || dest == null) {
            throw new RepairStop(RepairOutcome.UNVERIFIABLE, claim.generation);
        }
    }

    private void clearDestinationFence(Claim claim) {
        prices().updateOne(
                Filters.and(Filters.eq("_id", DEST_TICKER), Filters.eq("repairGeneration", claim.generation)),
                Updates.unset("repairGeneration"));
    }

    private void transition(Claim claim, String from, String to) {
        Document updated = leases().findOneAndUpdate(
                leasePredicate(claim, from),
                Updates.combine(
                        Updates.set("state", to),
                        Updates.set("expiresAt", Date.from(clock.instant().plus(leaseTtl)))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) {
            throw new RepairStop(RepairOutcome.LOST_FENCE, claim.generation);
        }
    }

    private void renew(Claim claim, String expectedState) {
        Document updated = leases().findOneAndUpdate(
                leasePredicate(claim, expectedState),
                Updates.set("expiresAt", Date.from(clock.instant().plus(leaseTtl))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) {
            throw new RepairStop(RepairOutcome.LOST_FENCE, claim.generation);
        }
    }

    private Bson leasePredicate(Claim claim, String state) {
        return Filters.and(
                Filters.eq("_id", REPAIR_ID),
                Filters.eq("owner", claim.owner),
                Filters.eq("generation", claim.generation),
                Filters.eq("state", state));
    }

    private void persistConflict(Claim claim) {
        leases().updateOne(
                Filters.and(
                        Filters.eq("_id", REPAIR_ID),
                        Filters.eq("owner", claim.owner),
                        Filters.eq("generation", claim.generation)),
                Updates.set("state", STATE_FAILED_CONFLICT));
    }

    private Bson mutationFilter(String ticker, long generation, Document expected) {
        List<Bson> parts = new ArrayList<>();
        parts.add(Filters.eq("_id", ticker));
        parts.add(Filters.eq("repairGeneration", generation));
        Document tuple = expected == null ? new Document() : expected;
        for (String field : TUPLE_FIELDS) {
            parts.add(Filters.eq(field, tuple.get(field)));
        }
        return Filters.and(parts);
    }

    private static Bson tupleSet(Document intended) {
        List<Bson> sets = new ArrayList<>();
        for (String field : TUPLE_FIELDS) {
            sets.add(Updates.set(field, intended.get(field)));
        }
        return Updates.combine(sets);
    }

    private boolean destCorroborates(Document dest, Document archive) {
        return destCorroboratesTuple(dest, intendedFrom(archive));
    }

    private static boolean destCorroboratesTuple(Document dest, Document intended) {
        return dest != null && intended != null && tupleEquals(dest, intended);
    }

    private static Document intendedFrom(Document archive) {
        return payloadSection(archive, "intendedDestination");
    }

    private static Document payloadSection(Document archive, String key) {
        Object payload = archive.get("payload");
        if (!(payload instanceof Document payloadDoc)) {
            return null;
        }
        Object section = payloadDoc.get(key);
        return section instanceof Document document ? document : null;
    }

    static boolean tupleEquals(Document left, Document right) {
        if (left == null || right == null) {
            return false;
        }
        return toTuple(left).sameValues(toTuple(right));
    }

    static PriceTuple toTuple(Document document) {
        return new PriceTuple(
                toDecimal(document.get("currentPrice")),
                document.getString("quoteCurrency"),
                toInstant(document.get("updatedAt")),
                toDecimal(document.get("previousReferencePrice")),
                toInstant(document.get("previousReferenceAt")));
    }

    static Document tupleToBson(PriceTuple tuple) {
        Document document = new Document();
        document.put("currentPrice", tuple.currentPrice() == null ? null : new Decimal128(tuple.currentPrice()));
        document.put("quoteCurrency", tuple.quoteCurrency());
        document.put("updatedAt", tuple.updatedAt() == null ? null : Date.from(tuple.updatedAt()));
        document.put(
                "previousReferencePrice",
                tuple.previousReferencePrice() == null ? null : new Decimal128(tuple.previousReferencePrice()));
        document.put("previousReferenceAt", tuple.previousReferenceAt() == null ? null : Date.from(tuple.previousReferenceAt()));
        return document;
    }

    static Document copyTuple(Document source) {
        Document copy = new Document();
        for (String field : TUPLE_FIELDS) {
            copy.put(field, source.get(field));
        }
        return copy;
    }

    private static boolean generationEquals(Document document, long generation) {
        Long held = asLong(document.get("repairGeneration"));
        return held != null && held == generation;
    }

    static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Decimal128 decimal128) {
            return decimal128.bigDecimalValue();
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(value.toString());
    }

    static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        return Instant.parse(value.toString());
    }

    static long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    static boolean isDuplicateKey(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof MongoWriteException write
                    && write.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return true;
            }
            if (current instanceof com.mongodb.MongoCommandException command && command.getErrorCode() == 11000) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static String sha256(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private MongoCollection<Document> prices() {
        return mongoTemplate.getCollection(PRICES);
    }

    private MongoCollection<Document> leases() {
        return mongoTemplate.getCollection(LEASES);
    }

    private MongoCollection<Document> archives() {
        return mongoTemplate.getCollection(ARCHIVES);
    }

    record Claim(String owner, long generation) {}

    static final class RepairStop extends RuntimeException {
        final RepairOutcome outcome;
        final long generation;

        RepairStop(RepairOutcome outcome, long generation) {
            super(outcome.name());
            this.outcome = outcome;
            this.generation = generation;
        }
    }
}
