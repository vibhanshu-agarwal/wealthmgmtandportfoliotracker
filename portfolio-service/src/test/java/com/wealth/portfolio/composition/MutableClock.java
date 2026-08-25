package com.wealth.portfolio.composition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Controllable {@link Clock} for deterministic equal-/regressed-timestamp proofs. */
final class MutableClock extends Clock {

    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    MutableClock(Instant initial) {
        this(initial, ZoneId.of("UTC"));
    }

    MutableClock(Instant initial, ZoneId zone) {
        this.instant = new AtomicReference<>(Objects.requireNonNull(initial));
        this.zone = Objects.requireNonNull(zone);
    }

    void set(Instant next) {
        instant.set(Objects.requireNonNull(next));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant.get(), zone);
    }

    @Override
    public Instant instant() {
        return instant.get();
    }
}
