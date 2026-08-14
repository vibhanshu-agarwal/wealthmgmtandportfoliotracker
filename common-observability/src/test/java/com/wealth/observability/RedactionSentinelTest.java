package com.wealth.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 5 sentinel coverage: one unique token per Requirement 7 surface, asserted
 * absent from {@link SanitizingSpanExporter} output. Direct export; no OTLP or Kafka broker.
 */
class RedactionSentinelTest {

    private static final String SENTINEL_HTTP_QUERY = "sentinel-http-query-xyz";
    private static final String SENTINEL_EXCEPTION_MESSAGE = "sentinel-exception-message-xyz";
    private static final String SENTINEL_EXCEPTION_STACK = "sentinel-exception-stack-xyz";
    private static final String SENTINEL_KAFKA_HEADER = "sentinel-kafka-header-xyz";
    private static final String SENTINEL_CUSTOM_ATTR = "sentinel-custom-attr-xyz";

    private static final AttributeKey<String> HTTP_URL = AttributeKey.stringKey("http.url");
    private static final AttributeKey<String> URL_FULL = AttributeKey.stringKey("url.full");
    private static final AttributeKey<String> URL_QUERY = AttributeKey.stringKey("url.query");
    private static final AttributeKey<String> HTTP_URL_QUERY = AttributeKey.stringKey("http.url.query");
    private static final AttributeKey<String> HTTP_METHOD = AttributeKey.stringKey("http.method");
    private static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");
    private static final Set<String> URL_SHAPED_KEYS = Set.of("http.url", "url.full");

    @Test
    void httpUrlQuerySentinelIsAbsentFromSanitizedSpan() {
        SpanData original = record(span -> {
            span.setAttribute(HTTP_URL, "https://example.com/accounts?secret=" + SENTINEL_HTTP_QUERY);
            span.setAttribute(URL_FULL, "https://example.com/holdings?secret=" + SENTINEL_HTTP_QUERY);
            span.setAttribute(URL_QUERY, "secret=" + SENTINEL_HTTP_QUERY);
            span.setAttribute(HTTP_URL_QUERY, "secret=" + SENTINEL_HTTP_QUERY);
            span.setAttribute(HTTP_METHOD, "GET");
        });
        assertThat(original.getAttributes().get(URL_QUERY)).contains(SENTINEL_HTTP_QUERY);
        assertThat(original.getAttributes().get(HTTP_URL_QUERY)).contains(SENTINEL_HTTP_QUERY);

        SpanData sanitized = sanitize(original);

        assertThat(attributeKeys(sanitized))
                .doesNotContain("url.query", "http.url.query")
                .contains("http.method", "http.url", "url.full");
        assertThat(sanitized.getAttributes().get(HTTP_METHOD)).isEqualTo("GET");
        // Query-strip of http.url / url.full is ObservationFilter-only; the exporter drops deny-set keys.
        assertThat(stringValuesExcluding(sanitized.getAttributes(), URL_SHAPED_KEYS))
                .noneMatch(value -> value.contains(SENTINEL_HTTP_QUERY));
        assertThat(eventStringValues(sanitized))
                .noneMatch(value -> value.contains(SENTINEL_HTTP_QUERY));
    }

    @Test
    void exceptionMessageSentinelIsAbsentFromSanitizedSpan() {
        SpanData original = record(span -> span.recordException(new RuntimeException(SENTINEL_EXCEPTION_MESSAGE)));
        assertThat(original.getEvents()).isNotEmpty();
        assertThat(stringValues(original.getEvents().getFirst().getAttributes()))
                .anyMatch(value -> value.contains(SENTINEL_EXCEPTION_MESSAGE));

        SpanData sanitized = sanitize(original);

        assertThat(sanitized.getEvents()).isNotEmpty();
        EventData event = sanitized.getEvents().getFirst();
        assertThat(event.getAttributes().get(EXCEPTION_MESSAGE)).isEqualTo("[REDACTED]");
        assertThat(stringValues(event.getAttributes()))
                .doesNotContain(SENTINEL_EXCEPTION_MESSAGE)
                .noneMatch(value -> value.contains(SENTINEL_EXCEPTION_MESSAGE));
        assertThat(stringValues(sanitized.getAttributes()))
                .noneMatch(value -> value.contains(SENTINEL_EXCEPTION_MESSAGE));
    }

    @Test
    void exceptionStackTraceSentinelIsAbsentFromSanitizedSpan() {
        SpanData original = record(span -> span.recordException(new RuntimeException(SENTINEL_EXCEPTION_STACK)));
        assertThat(original.getEvents()).isNotEmpty();
        EventData rawEvent = original.getEvents().getFirst();
        assertThat(rawEvent.getAttributes().get(EXCEPTION_STACKTRACE)).contains(SENTINEL_EXCEPTION_STACK);
        assertThat(stringValues(rawEvent.getAttributes()))
                .anyMatch(value -> value.contains(SENTINEL_EXCEPTION_STACK));

        SpanData sanitized = sanitize(original);

        assertThat(sanitized.getEvents()).isNotEmpty();
        EventData event = sanitized.getEvents().getFirst();
        assertThat(event.getAttributes().get(EXCEPTION_STACKTRACE))
                .satisfiesAnyOf(
                        value -> assertThat(value).isNull(),
                        value -> assertThat(value).isEqualTo("[REDACTED]"));
        assertThat(stringValues(event.getAttributes()))
                .doesNotContain(SENTINEL_EXCEPTION_STACK)
                .noneMatch(value -> value.contains(SENTINEL_EXCEPTION_STACK));
        assertThat(stringValues(sanitized.getAttributes()))
                .noneMatch(value -> value.contains(SENTINEL_EXCEPTION_STACK));
    }

    @Test
    void kafkaHeaderSentinelIsAbsentFromSanitizedSpan() {
        SpanData original = record(span -> {
            span.setAttribute("messaging.header.authorization", SENTINEL_KAFKA_HEADER);
            span.setAttribute("kafka.header.authorization", SENTINEL_KAFKA_HEADER);
            span.setAttribute("messaging.kafka.message.header.authorization", SENTINEL_KAFKA_HEADER);
            span.setAttribute("messaging.destination.name", "prices");
        });
        assertThat(original.getAttributes().get(AttributeKey.stringKey("messaging.header.authorization")))
                .isEqualTo(SENTINEL_KAFKA_HEADER);
        assertThat(original.getAttributes().get(AttributeKey.stringKey("kafka.header.authorization")))
                .isEqualTo(SENTINEL_KAFKA_HEADER);

        SpanData sanitized = sanitize(original);

        assertThat(attributeKeys(sanitized))
                .doesNotContain(
                        "messaging.header.authorization",
                        "kafka.header.authorization",
                        "messaging.kafka.message.header.authorization")
                .contains("messaging.destination.name");
        assertThat(sanitized.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")))
                .isEqualTo("prices");
        assertThat(stringValues(sanitized.getAttributes()))
                .doesNotContain(SENTINEL_KAFKA_HEADER)
                .noneMatch(value -> value.contains(SENTINEL_KAFKA_HEADER));
        assertThat(eventStringValues(sanitized))
                .noneMatch(value -> value.contains(SENTINEL_KAFKA_HEADER));
    }

    @Test
    void customAttributeSentinelIsAbsentFromSanitizedSpan() {
        SpanData original = record(span -> {
            span.setAttribute("authorization", "Bearer " + SENTINEL_CUSTOM_ATTR);
            span.setAttribute("portfolio.value", SENTINEL_CUSTOM_ATTR);
            span.setAttribute("user.id", SENTINEL_CUSTOM_ATTR);
            span.setAttribute("gen_ai.prompt", SENTINEL_CUSTOM_ATTR);
            span.setAttribute("http.method", "GET");
        });
        assertThat(original.getAttributes().get(AttributeKey.stringKey("authorization")))
                .contains(SENTINEL_CUSTOM_ATTR);
        assertThat(original.getAttributes().get(AttributeKey.stringKey("portfolio.value")))
                .isEqualTo(SENTINEL_CUSTOM_ATTR);
        assertThat(original.getAttributes().get(AttributeKey.stringKey("user.id")))
                .isEqualTo(SENTINEL_CUSTOM_ATTR);
        assertThat(original.getAttributes().get(AttributeKey.stringKey("gen_ai.prompt")))
                .isEqualTo(SENTINEL_CUSTOM_ATTR);

        SpanData sanitized = sanitize(original);

        assertThat(attributeKeys(sanitized))
                .doesNotContain("authorization", "portfolio.value", "user.id", "gen_ai.prompt")
                .contains("http.method");
        assertThat(sanitized.getAttributes().get(HTTP_METHOD)).isEqualTo("GET");
        assertThat(stringValues(sanitized.getAttributes()))
                .doesNotContain(SENTINEL_CUSTOM_ATTR)
                .noneMatch(value -> value.contains(SENTINEL_CUSTOM_ATTR));
        assertThat(eventStringValues(sanitized))
                .noneMatch(value -> value.contains(SENTINEL_CUSTOM_ATTR));
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

    private static List<String> attributeKeys(SpanData span) {
        List<String> keys = new ArrayList<>();
        span.getAttributes().forEach((key, value) -> keys.add(key.getKey()));
        return keys;
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

    private static List<String> stringValuesExcluding(Attributes attributes, Set<String> excludedKeys) {
        List<String> values = new ArrayList<>();
        attributes.forEach((key, value) -> {
            if (value != null && !excludedKeys.contains(key.getKey())) {
                values.add(String.valueOf(value));
            }
        });
        return values;
    }

    private static List<String> eventStringValues(SpanData span) {
        List<String> values = new ArrayList<>();
        for (EventData event : span.getEvents()) {
            values.addAll(stringValues(event.getAttributes()));
        }
        return values;
    }
}
