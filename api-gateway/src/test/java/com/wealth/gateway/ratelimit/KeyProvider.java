package com.wealth.gateway.ratelimit;

@FunctionalInterface
public interface KeyProvider {
    String freshKey();
}
