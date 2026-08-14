package com.wealth.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.ExceptionEventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 5 fourth invariant: every event on sanitized {@link SpanData} is not
 * {@link ExceptionEventData}, and {@code getException()} is unreachable (defense-in-depth).
 * Direct export; no OTLP or Kafka broker.
 */
class ExceptionEventRedactionInvariantTest {

    private static final String SENTINEL = "sentinel-exception-invariant-xyz";
    private static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");

    @Test
    void recordExceptionPathReplacesExceptionEventDataAndGetExceptionIsUnreachable() {
        SpanData original = record(span -> {
            span.addEvent("checkpoint");
            span.recordException(new RuntimeException(SENTINEL));
        });
        assertThat(original.getEvents()).hasSize(2);
        assertThat(original.getEvents().get(0)).isNotInstanceOf(ExceptionEventData.class);
        assertThat(original.getEvents().get(1)).isInstanceOf(ExceptionEventData.class);
        assertThat(((ExceptionEventData) original.getEvents().get(1)).getException().getMessage())
                .isEqualTo(SENTINEL);

        SpanData sanitized = sanitize(original);

        assertThat(sanitized.getEvents()).hasSize(2);
        assertFourthInvariantOnEveryEvent(sanitized);
    }

    @Test
    void plainExceptionEventPathIsNotExceptionEventDataAndGetExceptionIsUnreachable() {
        SpanData original = record(span -> {
            span.addEvent("checkpoint");
            span.addEvent(
                    "exception",
                    Attributes.of(
                            EXCEPTION_MESSAGE, SENTINEL,
                            EXCEPTION_STACKTRACE, SENTINEL));
        });
        assertThat(original.getEvents()).hasSize(2).allMatch(event -> !(event instanceof ExceptionEventData));
        assertThat(original.getEvents().get(1).getAttributes().get(EXCEPTION_MESSAGE)).isEqualTo(SENTINEL);

        SpanData sanitized = sanitize(original);

        assertThat(sanitized.getEvents()).hasSize(2);
        assertFourthInvariantOnEveryEvent(sanitized);
    }

    private static void assertFourthInvariantOnEveryEvent(SpanData sanitized) {
        assertThat(sanitized.getEvents()).isNotEmpty();
        for (EventData event : sanitized.getEvents()) {
            assertThat(event)
                    .as("sanitized event '%s' must not remain ExceptionEventData", event.getName())
                    .isNotInstanceOf(ExceptionEventData.class);
            assertGetExceptionUnreachable(event);
        }
    }

    private static void assertGetExceptionUnreachable(EventData event) {
        Optional<Method> getException = findGetException(event.getClass());
        if (getException.isEmpty()) {
            return;
        }
        try {
            Object result = getException.get().invoke(event);
            assertThat(result)
                    .as("getException() on sanitized event '%s' must not return a Throwable", event.getName())
                    .isNull();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Invocation failed — getException() is unreachable.
        }
    }

    private static Optional<Method> findGetException(Class<?> type) {
        for (Method method : type.getMethods()) {
            if ("getException".equals(method.getName()) && method.getParameterCount() == 0) {
                return Optional.of(method);
            }
        }
        return Optional.empty();
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
}
