package com.wealth.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.ExceptionEventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizingSpanExporterTest {

    private static final String SENTINEL_MESSAGE = "sentinel-exception-message-xyz";
    private static final String SENTINEL_STACK = "sentinel-exception-stack-xyz";
    private static final String SENTINEL_TOKEN = "Bearer sentinel-token-xyz";
    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");

    @Test
    void denySetKeyIsAbsentFromSanitizedSpanAttributes() {
        SpanData original = record(span -> {
            span.setAttribute("authorization", SENTINEL_TOKEN);
            span.setAttribute("user.id", "u-1");
            span.setAttribute("portfolio.value", "1000");
            span.setAttribute("http.method", "GET");
        });
        assertThat(original.getAttributes().get(AttributeKey.stringKey("authorization")))
                .isEqualTo(SENTINEL_TOKEN);

        SpanData sanitized = sanitize(original);

        assertThat(sanitized.getAttributes().asMap().keySet())
                .extracting(AttributeKey::getKey)
                .doesNotContain("authorization", "user.id", "portfolio.value")
                .contains("http.method");
        assertThat(sanitized.getAttributes().get(AttributeKey.stringKey("http.method"))).isEqualTo("GET");
        assertThat(stringValues(sanitized.getAttributes())).doesNotContain(SENTINEL_TOKEN);
    }

    @Test
    void exceptionEventDataIsReplacedWithPlainEventData() {
        SpanData original = record(span -> span.recordException(new IllegalStateException(SENTINEL_MESSAGE)));
        assertThat(original.getEvents()).isNotEmpty().allMatch(ExceptionEventData.class::isInstance);
        assertThat(((ExceptionEventData) original.getEvents().getFirst()).getException().getMessage())
                .isEqualTo(SENTINEL_MESSAGE);

        SpanData sanitized = sanitize(original);

        assertThat(sanitized.getEvents()).isNotEmpty();
        EventData replacement = sanitized.getEvents().getFirst();
        assertThat(replacement).isNotInstanceOf(ExceptionEventData.class);
        assertThat(replacement.getName()).isEqualTo("exception");
        assertThat(replacement.getAttributes().get(EXCEPTION_TYPE))
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(replacement.getAttributes().get(EXCEPTION_MESSAGE)).isEqualTo("[REDACTED]");
        assertThat(replacement.getAttributes().asMap().keySet())
                .extracting(AttributeKey::getKey)
                .doesNotContain("exception.stacktrace");
        assertThat(stringValues(replacement.getAttributes()))
                .doesNotContain(SENTINEL_MESSAGE)
                .noneMatch(value -> value.contains(SENTINEL_MESSAGE));
        assertThat(replacement.getClass().getMethods())
                .extracting(method -> method.getName())
                .doesNotContain("getException");
    }

    @Test
    void plainEventExceptionKeysAreRedacted() {
        SpanData original = record(span -> span.addEvent(
                "custom",
                Attributes.builder()
                        .put(EXCEPTION_TYPE, "java.lang.RuntimeException")
                        .put(EXCEPTION_MESSAGE, SENTINEL_MESSAGE)
                        .put(EXCEPTION_STACKTRACE, SENTINEL_STACK)
                        .put(AttributeKey.stringKey("http.method"), "POST")
                        .build()));
        EventData rawEvent = original.getEvents().getFirst();
        assertThat(rawEvent).isNotInstanceOf(ExceptionEventData.class);
        assertThat(rawEvent.getAttributes().get(EXCEPTION_MESSAGE)).isEqualTo(SENTINEL_MESSAGE);

        SpanData sanitized = sanitize(original);

        EventData event = sanitized.getEvents().getFirst();
        assertThat(event).isNotInstanceOf(ExceptionEventData.class);
        assertThat(event.getAttributes().get(EXCEPTION_TYPE)).isEqualTo("java.lang.RuntimeException");
        assertThat(event.getAttributes().get(EXCEPTION_MESSAGE)).isEqualTo("[REDACTED]");
        assertThat(event.getAttributes().get(EXCEPTION_STACKTRACE)).isEqualTo("[REDACTED]");
        assertThat(event.getAttributes().get(AttributeKey.stringKey("http.method"))).isEqualTo("POST");
        assertThat(stringValues(event.getAttributes()))
                .doesNotContain(SENTINEL_MESSAGE, SENTINEL_STACK);
    }

    @Test
    void statusDescriptionIsRedactedAndEmptyStatusStaysEmpty() {
        SpanData withDescription = record(span ->
                span.setStatus(StatusCode.ERROR, SENTINEL_MESSAGE));
        assertThat(withDescription.getStatus().getDescription()).isEqualTo(SENTINEL_MESSAGE);

        SpanData sanitized = sanitize(withDescription);

        assertThat(sanitized.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sanitized.getStatus().getDescription())
                .isEqualTo("[REDACTED]")
                .doesNotContain(SENTINEL_MESSAGE);

        SpanData unset = sanitize(record(span -> {
        }));
        assertThat(unset.getStatus().getDescription()).isEmpty();
    }

    @Test
    void exportFlushAndShutdownReachDelegate() {
        RecordingSpanExporter delegate = new RecordingSpanExporter();
        SanitizingSpanExporter exporter = new SanitizingSpanExporter(delegate);
        SpanData original = record(span -> span.setAttribute("http.method", "GET"));

        assertThat(exporter.export(List.of(original)).isSuccess()).isTrue();
        assertThat(delegate.exportCalled).isTrue();
        assertThat(delegate.exported).hasSize(1);
        assertThat(delegate.inner.getFinishedSpanItems()).hasSize(1);

        assertThat(exporter.flush().isSuccess()).isTrue();
        assertThat(delegate.flushCalled).isTrue();

        assertThat(exporter.shutdown().isSuccess()).isTrue();
        assertThat(delegate.shutdownCalled).isTrue();
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

    private static final class RecordingSpanExporter implements SpanExporter {
        private final InMemorySpanExporter inner = InMemorySpanExporter.create();
        private boolean exportCalled;
        private boolean flushCalled;
        private boolean shutdownCalled;
        private Collection<SpanData> exported = List.of();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            exportCalled = true;
            exported = List.copyOf(spans);
            return inner.export(spans);
        }

        @Override
        public CompletableResultCode flush() {
            flushCalled = true;
            return inner.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            shutdownCalled = true;
            return inner.shutdown();
        }
    }
}
