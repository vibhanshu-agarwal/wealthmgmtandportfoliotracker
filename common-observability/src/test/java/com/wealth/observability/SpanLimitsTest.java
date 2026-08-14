package com.wealth.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Boot's {@code SdkTracerProvider} span limits take effect on exported
 * {@link SpanData}, not merely that YAML lists the task-4.4 numbers.
 */
class SpanLimitsTest {

    private static final int MAX_ATTRIBUTE_VALUE_LENGTH = 512;
    private static final int MAX_ATTRIBUTES = 64;
    private static final int OVERSIZED_VALUE_LENGTH = 600;
    private static final int OVERSIZED_ATTRIBUTE_COUNT = 80;
    private static final AttributeKey<String> OVERSIZED_VALUE_KEY = AttributeKey.stringKey("oversized.value");
    private static final List<String> SERVICE_APPLICATION_YMLS = List.of(
            "api-gateway/src/main/resources/application.yml",
            "portfolio-service/src/main/resources/application.yml",
            "market-data-service/src/main/resources/application.yml",
            "insight-service/src/main/resources/application.yml");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OpenTelemetrySdkAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class))
            .withUserConfiguration(CapturingSpanExporterConfig.class)
            .withPropertyValues(
                    "management.tracing.sampling.probability=1.0",
                    "management.opentelemetry.tracing.sampler=always_on",
                    "management.opentelemetry.tracing.limits.max-attribute-value-length=512",
                    "management.opentelemetry.tracing.limits.max-attributes=64",
                    "management.opentelemetry.tracing.limits.max-events=16",
                    "management.opentelemetry.tracing.limits.max-links=8");

    @Test
    void stringAttributeLongerThan512IsTruncatedOnExportedSpanData() {
        String oversized = "x".repeat(OVERSIZED_VALUE_LENGTH);
        assertThat(oversized).hasSizeGreaterThan(MAX_ATTRIBUTE_VALUE_LENGTH);

        contextRunner.run(context -> {
            SpanData spanData = exportOversizedSpan(context, span -> span.setAttribute(OVERSIZED_VALUE_KEY, oversized));

            String exported = spanData.getAttributes().get(OVERSIZED_VALUE_KEY);
            assertThat(exported).isNotNull();
            assertThat(exported.length()).isLessThanOrEqualTo(MAX_ATTRIBUTE_VALUE_LENGTH);
            assertThat(oversized).startsWith(exported);
        });
    }

    @Test
    void spanWithMoreThan64AttributesDropsExtrasOnExportedSpanData() {
        contextRunner.run(context -> {
            SpanData spanData = exportOversizedSpan(context, span -> {
                for (int i = 0; i < OVERSIZED_ATTRIBUTE_COUNT; i++) {
                    span.setAttribute("extra." + i, "v" + i);
                }
            });

            assertThat(spanData.getAttributes().size())
                    .isLessThan(OVERSIZED_ATTRIBUTE_COUNT)
                    .isLessThanOrEqualTo(MAX_ATTRIBUTES);
        });
    }

    @Test
    void serviceApplicationYmlFilesPinTask44SpanLimits() throws Exception {
        Path repoRoot = repositoryRoot();
        for (String relative : SERVICE_APPLICATION_YMLS) {
            Path yml = repoRoot.resolve(relative);
            assertThat(yml).as(relative).exists();
            String contents = Files.readString(yml);
            assertThat(contents).contains(
                    "max-attribute-value-length: 512",
                    "max-attributes: 64",
                    "max-events: 16",
                    "max-links: 8");
        }
    }

    private static SpanData exportOversizedSpan(
            AssertableApplicationContext context, Consumer<Span> setup) {
        assertThat(context).hasNotFailed();
        SdkTracerProvider provider = context.getBean(SdkTracerProvider.class);
        RecordingSpanExporter exporter = context.getBean(RecordingSpanExporter.class);

        Span span = provider.get("span-limits-test").spanBuilder("oversized").startSpan();
        setup.accept(span);
        span.end();

        CompletableResultCode flush = provider.forceFlush();
        assertThat(flush.join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
        assertThat(exporter.exported())
                .extracting(SpanData::getName)
                .contains("oversized");
        return exporter.exported().stream()
                .filter(data -> "oversized".equals(data.getName()))
                .findFirst()
                .orElseThrow();
    }

    private static Path repositoryRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (dir != null && !Files.isRegularFile(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root containing settings.gradle").isNotNull();
        return dir;
    }

    @Configuration(proxyBeanMethods = false)
    static class CapturingSpanExporterConfig {

        @Bean
        RecordingSpanExporter capturingSpanExporter() {
            return new RecordingSpanExporter();
        }
    }

    /**
     * Test-only recorder. {@code InMemorySpanExporter.shutdown()} clears its buffer.
     */
    static final class RecordingSpanExporter implements SpanExporter {

        private final List<SpanData> exported = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            exported.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        List<SpanData> exported() {
            return List.copyOf(exported);
        }
    }
}
