package com.wealth.gateway.trace;

import java.util.regex.Pattern;

/** W3C {@code traceparent} helpers for distributed-tracing propagation tests (Property 10a/10b). */
final class TraceparentTestSupport {

    private static final Pattern TRACEPARENT =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    private TraceparentTestSupport() {}

    static String traceId(String traceparent) {
        var matcher = TRACEPARENT.matcher(traceparent);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid traceparent: " + traceparent);
        }
        return matcher.group(1);
    }

    static String sampleTraceparent() {
        return "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    }
}
