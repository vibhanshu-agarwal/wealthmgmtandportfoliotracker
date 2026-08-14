package com.wealth.observability;

import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Five-case activation matrix for {@link SanitizingBatchSpanProcessorAutoConfiguration}.
 * Loads Boot's OTLP + OpenTelemetry tracing auto-configurations (not a fake exporter)
 * so active cases prove we preempt Boot's {@code BatchSpanProcessor}.
 */
class SanitizingBatchSpanProcessorActivationMatrixTest {

    private static final String OTLP_ENDPOINT =
            "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4317/v1/traces";
    private static final String OTLP_GRPC =
            "management.opentelemetry.tracing.export.otlp.transport=grpc";
    private static final String REDUCED_QUEUE =
            "management.opentelemetry.tracing.export.max-queue-size=8";
    private static final String YML_EXPORT_PLACEHOLDER =
            "enabled: ${MANAGEMENT_TRACING_EXPORT_ENABLED:false}";
    private static final List<String> SERVICE_APPLICATION_YMLS = List.of(
            "api-gateway/src/main/resources/application.yml",
            "portfolio-service/src/main/resources/application.yml",
            "market-data-service/src/main/resources/application.yml",
            "insight-service/src/main/resources/application.yml");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OpenTelemetrySdkAutoConfiguration.class,
                    OtlpTracingAutoConfiguration.class,
                    SanitizingBatchSpanProcessorAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class));

    @Test
    void case1_noEnablementPropertiesWithEndpointAndGrpc_moduleActive() {
        contextRunner
                .withPropertyValues(OTLP_ENDPOINT, OTLP_GRPC, REDUCED_QUEUE)
                .run(context -> {
                    assertModuleActive(context);
                    assertThat(conditionMessages(context))
                            .anyMatch(message -> message.contains("tracing is enabled by default"));
                });
    }

    @Test
    void case2_otlpSpecificTrueGlobalFalse_moduleActive() {
        contextRunner
                .withPropertyValues(
                        "management.tracing.export.otlp.enabled=true",
                        "management.tracing.export.enabled=false",
                        OTLP_ENDPOINT,
                        OTLP_GRPC,
                        REDUCED_QUEUE)
                .run(this::assertModuleActive);
    }

    @Test
    void case3_otlpSpecificFalseGlobalTrue_moduleInactiveNoExporter() {
        contextRunner
                .withPropertyValues(
                        "management.tracing.export.otlp.enabled=false",
                        "management.tracing.export.enabled=true",
                        OTLP_ENDPOINT,
                        OTLP_GRPC)
                .run(context -> {
                    assertModuleInactive(context);
                    assertThat(context).doesNotHaveBean(OtlpGrpcSpanExporter.class);
                });
    }

    @Test
    void case4_enablementMatchesWithoutEndpointOrGrpcExporter_moduleInactive() {
        contextRunner.run(context -> {
            assertModuleInactive(context);
            assertThat(context).doesNotHaveBean(OtlpGrpcSpanExporter.class);
        });
    }

    @Test
    void case5_ymlPlaceholderResolvesToFalse_moduleInactiveNoExporter() throws Exception {
        assertServiceYmlFilesContainExportPlaceholder();
        assertThat(System.getenv("MANAGEMENT_TRACING_EXPORT_ENABLED")).isNull();
        assertThat(System.getProperty("MANAGEMENT_TRACING_EXPORT_ENABLED")).isNull();

        contextRunner
                .withPropertyValues(
                        "management.tracing.export.enabled=${MANAGEMENT_TRACING_EXPORT_ENABLED:false}",
                        OTLP_ENDPOINT,
                        OTLP_GRPC)
                .run(context -> {
                    assertThat(context.getEnvironment().getProperty("management.tracing.export.enabled"))
                            .isEqualTo("false");
                    assertModuleInactive(context);
                    assertThat(context).doesNotHaveBean(OtlpGrpcSpanExporter.class);
                });
    }

    private void assertModuleActive(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        assertThat(context).hasSingleBean(SanitizingBatchSpanProcessorAutoConfiguration.class);
        assertThat(context).hasSingleBean(BatchSpanProcessor.class);
        assertThat(context).hasSingleBean(OtlpGrpcSpanExporter.class);
        assertThat(context.getBeansOfType(SpanExporter.class).values())
                .noneMatch(SanitizingSpanExporter.class::isInstance)
                .anyMatch(OtlpGrpcSpanExporter.class::isInstance);
        BatchSpanProcessor processor = context.getBean(BatchSpanProcessor.class);
        assertThat(processor.getSpanExporter()).isInstanceOf(SanitizingSpanExporter.class);
        assertThat(processor).extracting("worker.maxQueueSize").isEqualTo(8L);
    }

    private void assertModuleInactive(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        assertThat(context).doesNotHaveBean(SanitizingBatchSpanProcessorAutoConfiguration.class);
        assertThat(context.getBeansOfType(SpanExporter.class).values())
                .noneMatch(SanitizingSpanExporter.class::isInstance);
        context.getBeansOfType(BatchSpanProcessor.class)
                .values()
                .forEach(processor -> assertThat(processor.getSpanExporter())
                        .isNotInstanceOf(SanitizingSpanExporter.class));
    }

    private static List<String> conditionMessages(AssertableApplicationContext context) {
        ConditionEvaluationReport report = ConditionEvaluationReport.get(
                (ConfigurableListableBeanFactory) context.getBeanFactory());
        return report.getConditionAndOutcomesBySource().values().stream()
                .flatMap(outcomes -> StreamSupport.stream(outcomes.spliterator(), false))
                .map(conditionAndOutcome -> conditionAndOutcome.getOutcome().getMessage())
                .filter(message -> message != null)
                .toList();
    }

    private static void assertServiceYmlFilesContainExportPlaceholder() throws Exception {
        Path repoRoot = repositoryRoot();
        for (String relative : SERVICE_APPLICATION_YMLS) {
            Path yml = repoRoot.resolve(relative);
            assertThat(yml).as(relative).exists();
            assertThat(Files.readString(yml)).contains(YML_EXPORT_PLACEHOLDER);
        }
    }

    private static Path repositoryRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (dir != null && !Files.isRegularFile(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root containing settings.gradle").isNotNull();
        return dir;
    }
}
