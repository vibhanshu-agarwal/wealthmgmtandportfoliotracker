package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.advisor.AnalysisResult;
import com.wealth.insight.resolution.Intent;
import com.wealth.insight.resolution.LlmResolution;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.ai.converter.BeanOutputConverter;
import tools.jackson.databind.json.JsonMapper;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8.10 — Property 12: No Jackson 2 databind contamination of structured-output records.
 *
 * <p>Asserts {@link AnalysisResult} and {@link LlmResolution} round-trip through the Spring AI
 * 2.0 {@link BeanOutputConverter} (Jackson 3 schema path). General annotations remain on the
 * shared {@code com.fasterxml.jackson.annotation} jar per Jackson 3 migration rules.
 */
class StructuredOutputJackson3PropertyTest {

    private final BeanOutputConverter<AnalysisResult> analysisConverter =
            new BeanOutputConverter<>(AnalysisResult.class);
    private final BeanOutputConverter<LlmResolution> resolutionConverter =
            new BeanOutputConverter<>(LlmResolution.class);
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Property(tries = 100)
    void p12_analysisResult_roundTripsViaBeanOutputConverter(
            @ForAll("analysisResults") AnalysisResult original) throws Exception {
        assertNoJacksonDatabindAnnotations(AnalysisResult.class);

        String json = mapper.writeValueAsString(original);
        AnalysisResult parsed = analysisConverter.convert(json);

        assertThat(parsed.riskScore()).isEqualTo(original.riskScore());
        assertThat(parsed.concentrationWarnings()).isEqualTo(original.concentrationWarnings());
        assertThat(parsed.rebalancingSuggestions()).isEqualTo(original.rebalancingSuggestions());
        assertThat(analysisConverter.getJsonSchema()).contains("riskScore");
    }

    @Property(tries = 100)
    void p12_llmResolution_roundTripsViaBeanOutputConverter(
            @ForAll("llmResolutions") LlmResolution original) throws Exception {
        assertNoJacksonDatabindAnnotations(LlmResolution.class);
        assertThat(LlmResolution.class.getAnnotation(JsonIgnoreProperties.class)).isNotNull();

        String json = mapper.writeValueAsString(original);
        LlmResolution parsed = resolutionConverter.convert(json);

        assertThat(parsed.intent()).isEqualTo(original.intent());
        assertThat(parsed.entities()).isEqualTo(original.entities());
        assertThat(parsed.resolvedTickers()).isEqualTo(original.resolvedTickers());
        assertThat(parsed.candidateTickers()).isEqualTo(original.candidateTickers());
        assertThat(parsed.categoryFilter()).isEqualTo(original.categoryFilter());
        assertThat(parsed.clarificationReason()).isEqualTo(original.clarificationReason());
        assertThat(resolutionConverter.getJsonSchema()).contains("intent");
    }

    @Provide
    Arbitrary<AnalysisResult> analysisResults() {
        Arbitrary<List<String>> warnings = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                .list().ofMinSize(0).ofMaxSize(3);
        Arbitrary<List<String>> suggestions = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                .list().ofMinSize(0).ofMaxSize(3);
        return Arbitraries.integers().between(1, 100)
                .flatMap(score -> warnings.flatMap(w -> suggestions.map(s -> new AnalysisResult(score, w, s))));
    }

    @Provide
    Arbitrary<LlmResolution> llmResolutions() {
        Arbitrary<Intent> intents = Arbitraries.of(Intent.class);
        Arbitrary<List<String>> tickers = Arbitraries.of("AAPL", "MSFT", "BTC-USD", "RELIANCE.NS")
                .list().ofMinSize(0).ofMaxSize(3);
        Arbitrary<String> optionalText = Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(20);

        return Combinators.combine(intents, tickers, tickers, tickers, optionalText, optionalText)
                .as(LlmResolution::new);
    }

    private static void assertNoJacksonDatabindAnnotations(Class<?> type) {
        for (Annotation annotation : type.getAnnotations()) {
            assertThat(annotation.annotationType().getName())
                    .as("Record %s must not use Jackson 2 databind annotations", type.getSimpleName())
                    .doesNotStartWith("com.fasterxml.jackson.databind");
        }
        Arrays.stream(type.getRecordComponents())
                .flatMap(c -> Arrays.stream(c.getAnnotations()))
                .forEach(annotation -> assertThat(annotation.annotationType().getName())
                        .doesNotStartWith("com.fasterxml.jackson.databind"));
    }
}
