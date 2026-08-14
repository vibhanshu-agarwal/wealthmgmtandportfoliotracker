package com.wealth.market.trace;

import java.util.regex.Pattern;

/** W3C {@code traceparent} helpers for distributed-tracing producer wire tests (Property 2). */
final class TraceparentTestSupport {

    static final Pattern TRACEPARENT =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    private TraceparentTestSupport() {}

    static String traceId(String traceparent) {
        var matcher = TRACEPARENT.matcher(traceparent);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid traceparent: " + traceparent);
        }
        return matcher.group(1);
    }
}
