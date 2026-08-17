package com.wealth.gateway.ratelimit;

@FunctionalInterface
public interface SecondProvider {
    String currentSecond() throws Exception;
}
