package com.wealth.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 5 three-location sweep: one sentinel on one span, asserted absent from
 * span attributes, exception event attributes, and the span status description after
 * {@link SanitizingSpanExporter}. Direct export; no OTLP or Kafka broker.
 */
class RedactionThreeLocationSweepTest {

    private static final String SENTINEL = "sentinel-three-location-xyz";
    private static final AttributeKey<String> AUTHORIZATION = AttributeKey.stringKey("authorization");
    private static final AttributeKey<String> HTTP_METHOD = AttributeKey.stringKey("http.method");
    private static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");

    @Test
    void sentinelIsAbsentFromSpanAttributesExceptionEventAttributesAndStatusDescription() {
        SpanData original = record(span -> {
            span.setAttribute(AUTHORIZATION, SENTINEL);
            span.setAttribute(HTTP_METHOD, "GET");
            span.recordException(new RuntimeException(SENTINEL));
            span.setStatus(StatusCode.ERROR, SENTINEL);
        });
        assertThat(original.getAttributes().get(AUTHORIZATION)).isEqualTo(SENTINEL);
        assertThat(original.getEvents()).isNotEmpty();
        assertThat(stringValues(original.getEvents().getFirst().getAttributes()))
                .anyMatch(value -> value.contains(SENTINEL));
        assertThat(original.getStatus().getDescription()).isEqualTo(SENTINEL);

        SpanData sanitized = sanitize(original);

        assertThat(stringValues(sanitized.getAttributes()))
                .doesNotContain(SENTINEL)
                .noneMatch(value -> value.contains(SENTINEL));
        assertThat(sanitized.getAttributes().get(HTTP_METHOD)).isEqualTo("GET");

        assertThat(sanitized.getEvents()).isNotEmpty();
        for (EventData event : sanitized.getEvents()) {
            assertThat(stringValues(event.getAttributes()))
                    .doesNotContain(SENTINEL)
                    .noneMatch(value -> value.contains(SENTINEL));
        }
        assertThat(sanitized.getEvents().getFirst().getAttributes().get(EXCEPTION_MESSAGE))
                .isEqualTo("[REDACTED]");

        assertThat(sanitized.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sanitized.getStatus().getDescription())
                .isEqualTo("[REDACTED]")
                .doesNotContain(SENTINEL);
    }

    private static SpanData record(Consumer<Span> setup) {
        InMemorySpanExporter raw = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(raw))
                .build();
        try {
            Span span = provider.get("test").spanBuilder("operation").startSpan();
            setup.accept(span);
            span.end();
            return raw.getFinishedSpanItems().getFirst();
        } finally {
            provider.close();
        }
    }

    private static SpanData sanitize(SpanData original) {
        InMemorySpanExporter sink = InMemorySpanExporter.create();
        new SanitizingSpanExporter(sink).export(List.of(original));
        return sink.getFinishedSpanItems().getFirst();
    }

    private static List<String> stringValues(Attributes attributes) {
        List<String> values = new ArrayList<>();
        attributes.forEach((key, value) -> {
            if (value != null) {
                values.add(String.valueOf(value));
            }
        });
        return values;
    }
}
