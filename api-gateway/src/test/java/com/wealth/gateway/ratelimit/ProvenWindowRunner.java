package com.wealth.gateway.ratelimit;

import java.time.Duration;

public final class ProvenWindowRunner {
    private final SecondProvider secondProvider;
    private final KeyProvider keyProvider;
    private final BurstRunner burstRunner;

    public ProvenWindowRunner(SecondProvider secondProvider, KeyProvider keyProvider, BurstRunner burstRunner) {
        this.secondProvider = secondProvider;
        this.keyProvider = keyProvider;
        this.burstRunner = burstRunner;
    }

    public RawAttempt run(int maxAttempts, Duration softMaxElapsed) throws Exception {
        long start = System.nanoTime();
        int attempt = 0;
        while (attempt < maxAttempts
                && Duration.ofNanos(System.nanoTime() - start).compareTo(softMaxElapsed) < 0) {
            attempt++;
            String key = keyProvider.freshKey();
            String before = secondProvider.currentSecond();
            RawAttempt result = burstRunner.run(key);
            String after = secondProvider.currentSecond();
            if (before.equals(after)) {
                return result;
            }
        }
        throw new AssertionError(
                "No proven no-replenishment window after "
                        + attempt
                        + " attempts / "
                        + Duration.ofNanos(System.nanoTime() - start).toMillis()
                        + "ms elapsed");
    }
}
