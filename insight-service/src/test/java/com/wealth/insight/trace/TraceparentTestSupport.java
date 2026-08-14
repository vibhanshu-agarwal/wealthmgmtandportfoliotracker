package com.wealth.insight.trace;

import io.micrometer.tracing.Span;
import java.util.regex.Pattern;

/** W3C {@code traceparent} helpers for distributed-tracing propagation tests (Property 10b). */
final class TraceparentTestSupport {

    private static final Pattern TRACEPARENT =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    private TraceparentTestSupport() {}

    static String w3cTraceparent(Span span) {
        return "00-%s-%s-%02x"
                .formatted(
                        span.context().traceId(),
                        span.context().spanId(),
                        Boolean.TRUE.equals(span.context().sampled()) ? 0x01 : 0x00);
    }

    static String traceId(String traceparent) {
        return group(traceparent, 1);
    }

    static String spanId(String traceparent) {
        return group(traceparent, 2);
    }

    private static String group(String traceparent, int group) {
        var matcher = TRACEPARENT.matcher(traceparent);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid traceparent: " + traceparent);
        }
        return matcher.group(group);
    }
}
