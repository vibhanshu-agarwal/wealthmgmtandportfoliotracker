package com.wealth.observability;

import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.ConditionalOnEnabledTracingExport;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizingBatchSpanProcessorAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    SanitizingBatchSpanProcessorAutoConfiguration.class))
            .withUserConfiguration(OtlpGrpcSpanExporterConfig.class);

    @Test
    void batchSpanProcessorExistsWhenGrpcExporterPresentAndOtlpExportEnabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BatchSpanProcessor.class);
        });
    }

    @Test
    void sanitizingSpanExporterIsNotRegisteredAsASpanExporterBean() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(SpanExporter.class).values())
                    .noneMatch(SanitizingSpanExporter.class::isInstance);
            assertThat(context.getBean(BatchSpanProcessor.class).getSpanExporter())
                    .isInstanceOf(SanitizingSpanExporter.class);
        });
    }

    @Test
    void autoConfigurationIsOrderedAfterOtlpAndBeforeBootTracingWithOtlpGrpcGuards() {
        AutoConfiguration autoConfiguration =
                SanitizingBatchSpanProcessorAutoConfiguration.class.getAnnotation(AutoConfiguration.class);
        assertThat(autoConfiguration).isNotNull();
        assertThat(autoConfiguration.after()).containsExactly(OtlpTracingAutoConfiguration.class);
        assertThat(autoConfiguration.before()).containsExactly(OpenTelemetryTracingAutoConfiguration.class);

        ConditionalOnEnabledTracingExport export =
                SanitizingBatchSpanProcessorAutoConfiguration.class.getAnnotation(
                        ConditionalOnEnabledTracingExport.class);
        assertThat(export).isNotNull();
        assertThat(export.value()).isEqualTo("otlp");

        ConditionalOnBean onBean =
                SanitizingBatchSpanProcessorAutoConfiguration.class.getAnnotation(ConditionalOnBean.class);
        assertThat(onBean).isNotNull();
        assertThat(onBean.value()).containsExactly(OtlpGrpcSpanExporter.class);
    }

    @Test
    void reducedMaxQueueSizePropertyIsAccepted() {
        contextRunner
                .withPropertyValues("management.opentelemetry.tracing.export.max-queue-size=8")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BatchSpanProcessor.class);
                });
    }

    @Test
    void beanMethodsDoNotExposeSanitizingOrRawSpanExporter() {
        assertThat(SanitizingBatchSpanProcessorAutoConfiguration.class.getDeclaredMethods())
                .filteredOn(method -> method.isAnnotationPresent(Bean.class))
                .extracting(Method::getReturnType)
                .contains(BatchSpanProcessor.class)
                .doesNotContain(SpanExporter.class, SanitizingSpanExporter.class);
    }

    @Configuration(proxyBeanMethods = false)
    static class OtlpGrpcSpanExporterConfig {

        @Bean
        OtlpGrpcSpanExporter otlpGrpcSpanExporter() {
            return OtlpGrpcSpanExporter.builder().setEndpoint("http://127.0.0.1:4317").build();
        }
    }
}
