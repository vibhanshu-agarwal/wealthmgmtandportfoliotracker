package com.wealth.gateway.ratelimit;

@FunctionalInterface
public interface BurstRunner {
    RawAttempt run(String key) throws Exception;
}
