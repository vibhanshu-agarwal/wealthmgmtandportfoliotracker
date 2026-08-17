package com.wealth.gateway.ratelimit;

public final class RedisTimeParser {

    private RedisTimeParser() {}

    public static String parse(int exitCode, String stdout) {
        if (exitCode != 0) {
            throw new IllegalStateException("redis-cli TIME exited " + exitCode);
        }
        String firstLine = stdout.split("\n", 2)[0].trim();
        if (!firstLine.matches("^\\d+$")) {
            throw new IllegalStateException("malformed redis-cli TIME output: " + stdout);
        }
        return firstLine;
    }
}
