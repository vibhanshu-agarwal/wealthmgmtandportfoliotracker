package com.wealth.market.repair;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CollisionPolicyTest {

    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    void sourceOnly_migratesSource() {
        PriceTuple source = tuple("10", T1, "9", T1);
        CollisionPolicy.Result result = CollisionPolicy.decide(source, null);
        assertThat(result.kind()).isEqualTo(CollisionPolicy.Kind.MIGRATE_SOURCE);
        assertThat(result.intended().sameValues(source)).isTrue();
    }

    @Test
    void destOnly_retainsDestination() {
        PriceTuple dest = tuple("20", T1, "19", T1);
        CollisionPolicy.Result result = CollisionPolicy.decide(null, dest);
        assertThat(result.kind()).isEqualTo(CollisionPolicy.Kind.RETAIN_DESTINATION);
        assertThat(result.intended().sameValues(dest)).isTrue();
    }

    @Test
    void newerUpdatedAtWins() {
        PriceTuple source = tuple("10", T2, "9", T1);
        PriceTuple dest = tuple("20", T1, "19", T1);
        CollisionPolicy.Result result = CollisionPolicy.decide(source, dest);
        assertThat(result.kind()).isEqualTo(CollisionPolicy.Kind.MIGRATE_SOURCE);
        assertThat(result.intended().sameValues(source)).isTrue();
    }

    @Test
    void knownUpdatedAtBeatsNull() {
        PriceTuple source = tuple("10", T1, "9", T1);
        PriceTuple dest = tuple("20", null, "19", T1);
        CollisionPolicy.Result result = CollisionPolicy.decide(source, dest);
        assertThat(result.kind()).isEqualTo(CollisionPolicy.Kind.MIGRATE_SOURCE);

        CollisionPolicy.Result destKnown = CollisionPolicy.decide(dest, source);
        assertThat(destKnown.kind()).isEqualTo(CollisionPolicy.Kind.RETAIN_DESTINATION);
    }

    @Test
    void bothNullUpdatedAt_retainsDestination() {
        PriceTuple source = tuple("10", null, "9", T1);
        PriceTuple dest = tuple("20", null, "19", T1);
        CollisionPolicy.Result result = CollisionPolicy.decide(source, dest);
        assertThat(result.kind()).isEqualTo(CollisionPolicy.Kind.RETAIN_DESTINATION);
        assertThat(result.intended().sameValues(dest)).isTrue();
        assertThat(result.discarded().sameValues(source)).isTrue();
    }

    @Test
    void sameUpdatedAtIdenticalValues_collapse() {
        PriceTuple source = tuple("10", T1, "9", T1);
        PriceTuple dest = tuple("10.0", T1, "9.00", T1);
        CollisionPolicy.Result result = CollisionPolicy.decide(source, dest);
        assertThat(result.kind()).isEqualTo(CollisionPolicy.Kind.COLLAPSE_IDENTICAL);
        assertThat(result.intended().sameValues(dest)).isTrue();
    }

    @Test
    void sameUpdatedAtConflictingValues_conflict() {
        PriceTuple source = tuple("10", T1, "9", T1);
        PriceTuple dest = tuple("20", T1, "19", T1);
        CollisionPolicy.Result result = CollisionPolicy.decide(source, dest);
        assertThat(result.kind()).isEqualTo(CollisionPolicy.Kind.CONFLICT);
    }

    private static PriceTuple tuple(String price, Instant updatedAt, String prev, Instant prevAt) {
        return new PriceTuple(
                new BigDecimal(price),
                "INR",
                updatedAt,
                new BigDecimal(prev),
                prevAt);
    }
}
