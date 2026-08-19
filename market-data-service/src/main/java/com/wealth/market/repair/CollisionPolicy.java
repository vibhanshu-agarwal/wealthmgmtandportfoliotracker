package com.wealth.market.repair;

import java.time.Instant;

/**
 * Collision outcomes for {@code MM.NS} vs {@code M&amp;M.NS} — same shape as Postgres V19,
 * keyed on {@code updatedAt}: known beats null; both null retains destination.
 */
public final class CollisionPolicy {

    public enum Kind {
        MIGRATE_SOURCE,
        RETAIN_DESTINATION,
        COLLAPSE_IDENTICAL,
        CONFLICT
    }

    public record Result(Kind kind, PriceTuple intended, PriceTuple discarded) {}

    private CollisionPolicy() {}

    public static Result decide(PriceTuple source, PriceTuple destination) {
        if (source == null && destination == null) {
            return new Result(Kind.CONFLICT, null, null);
        }
        if (source == null) {
            return new Result(Kind.RETAIN_DESTINATION, destination, null);
        }
        if (destination == null) {
            return new Result(Kind.MIGRATE_SOURCE, source, null);
        }

        Instant sourceAt = source.updatedAt();
        Instant destAt = destination.updatedAt();
        if (sourceAt == null && destAt == null) {
            return new Result(Kind.RETAIN_DESTINATION, destination, source);
        }
        if (sourceAt == null) {
            return new Result(Kind.RETAIN_DESTINATION, destination, source);
        }
        if (destAt == null) {
            return new Result(Kind.MIGRATE_SOURCE, source, destination);
        }
        int cmp = sourceAt.compareTo(destAt);
        if (cmp > 0) {
            return new Result(Kind.MIGRATE_SOURCE, source, destination);
        }
        if (cmp < 0) {
            return new Result(Kind.RETAIN_DESTINATION, destination, source);
        }
        if (source.sameValues(destination)) {
            return new Result(Kind.COLLAPSE_IDENTICAL, destination, source);
        }
        return new Result(Kind.CONFLICT, null, null);
    }
}
