package com.wealth.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRouteTemplatingObservationConventionTest {

    private static final String CONCRETE_PORTFOLIO_PATH =
            "/api/portfolio/550e8400-e29b-41d4-a716-446655440000";

    private final HttpRouteTemplatingObservationConvention convention =
            new HttpRouteTemplatingObservationConvention();

    @Test
    void uuidPathIsRecordedAsRouteTemplateDistinctFromConcretePath() {
        Observation.Context context = new Observation.Context();
        context.addLowCardinalityKeyValue(KeyValue.of("http.route", CONCRETE_PORTFOLIO_PATH));
        context.addHighCardinalityKeyValue(KeyValue.of("url.path", CONCRETE_PORTFOLIO_PATH));
        context.addHighCardinalityKeyValue(KeyValue.of("http.target", CONCRETE_PORTFOLIO_PATH));

        assertThat(convention.supportsContext(context)).isTrue();

        String route = value(convention.getLowCardinalityKeyValues(context), "http.route");
        String path = value(convention.getHighCardinalityKeyValues(context), "url.path");
        String target = value(convention.getHighCardinalityKeyValues(context), "http.target");

        assertThat(route).isEqualTo("/api/portfolio/{id}").isNotEqualTo(CONCRETE_PORTFOLIO_PATH);
        assertThat(path).isEqualTo("/api/portfolio/{id}").isNotEqualTo(CONCRETE_PORTFOLIO_PATH);
        assertThat(target).isEqualTo("/api/portfolio/{id}").isNotEqualTo(CONCRETE_PORTFOLIO_PATH);
    }

    @Test
    void numericPathSegmentsAreTemplated() {
        Observation.Context context = new Observation.Context();
        context.addLowCardinalityKeyValue(KeyValue.of("http.route", "/api/holdings/42/lots/7"));

        KeyValues values = convention.getLowCardinalityKeyValues(context);

        assertThat(value(values, "http.route")).isEqualTo("/api/holdings/{id}/lots/{id}");
    }

    @Test
    void templatesHttpTargetAfterDroppingQueryString() {
        Observation.Context context = new Observation.Context();
        context.addHighCardinalityKeyValue(
                KeyValue.of("http.target", CONCRETE_PORTFOLIO_PATH + "?secret=1"));

        assertThat(convention.supportsContext(context)).isTrue();
        assertThat(value(convention.getHighCardinalityKeyValues(context), "http.target"))
                .isEqualTo("/api/portfolio/{id}");
    }

    @Test
    void leavesSpringSuppliedRouteTemplateUnchanged() {
        Observation.Context context = new Observation.Context();
        context.addLowCardinalityKeyValue(KeyValue.of("http.route", "/api/portfolio/{id}"));

        assertThat(convention.supportsContext(context)).isFalse();
        assertThat(value(convention.getLowCardinalityKeyValues(context), "http.route"))
                .isEqualTo("/api/portfolio/{id}");
    }

    private static String value(KeyValues keyValues, String key) {
        return keyValues.stream()
                .filter(kv -> key.equals(kv.getKey()))
                .map(KeyValue::getValue)
                .findFirst()
                .orElse(null);
    }
}
