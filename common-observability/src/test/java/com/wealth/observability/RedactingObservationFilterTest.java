package com.wealth.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RedactingObservationFilterTest {

    private static final String CONCRETE_PORTFOLIO_PATH =
            "/api/portfolio/550e8400-e29b-41d4-a716-446655440000";

    private final RedactingObservationFilter filter = new RedactingObservationFilter();

    @Test
    void denySetKeysAreRemovedFromKeyValues() {
        Observation.Context context = new Observation.Context();
        context.addHighCardinalityKeyValue(KeyValue.of("url.query", "secret=1"));
        context.addHighCardinalityKeyValue(KeyValue.of("http.query", "secret=1"));
        context.addHighCardinalityKeyValue(KeyValue.of("query", "secret=1"));
        context.addHighCardinalityKeyValue(KeyValue.of("authorization", "Bearer abc"));
        context.addHighCardinalityKeyValue(KeyValue.of("http.request.header.authorization", "Bearer abc"));
        context.addHighCardinalityKeyValue(KeyValue.of("cookie", "sid=1"));
        context.addHighCardinalityKeyValue(KeyValue.of("password", "p"));
        context.addHighCardinalityKeyValue(KeyValue.of("password.hash", "h"));
        context.addHighCardinalityKeyValue(KeyValue.of("token", "t"));
        context.addHighCardinalityKeyValue(KeyValue.of("access_token", "t"));
        context.addHighCardinalityKeyValue(KeyValue.of("api-key", "k"));
        context.addHighCardinalityKeyValue(KeyValue.of("user.id", "u-1"));
        context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.prompt", "buy"));
        context.addHighCardinalityKeyValue(KeyValue.of("ai.completion", "sell"));
        context.addHighCardinalityKeyValue(KeyValue.of("portfolio.value", "1000"));
        context.addHighCardinalityKeyValue(KeyValue.of("holdings", "AAPL"));
        context.addLowCardinalityKeyValue(KeyValue.of("http.method", "GET"));
        context.addLowCardinalityKeyValue(KeyValue.of("http.route", "/api/portfolio/{id}"));

        filter.map(context);

        assertThat(allKeys(context))
                .doesNotContain(
                        "url.query",
                        "http.query",
                        "query",
                        "authorization",
                        "http.request.header.authorization",
                        "cookie",
                        "password",
                        "password.hash",
                        "token",
                        "access_token",
                        "api-key",
                        "user.id",
                        "gen_ai.prompt",
                        "ai.completion",
                        "portfolio.value",
                        "holdings")
                .contains("http.method", "http.route");
    }

    @Test
    void suffixDenySetKeysAreRemovedFromObservationContext() {
        Observation.Context context = new Observation.Context();
        context.addHighCardinalityKeyValue(KeyValue.of("http.url.query", "secret=1"));
        context.addHighCardinalityKeyValue(KeyValue.of("foo.access_token", "tok"));
        context.addLowCardinalityKeyValue(KeyValue.of("http.method", "GET"));

        filter.map(context);

        assertThat(allKeys(context))
                .doesNotContain("http.url.query", "foo.access_token")
                .contains("http.method");
    }

    @Test
    void denyQueryStripAndTemplateApplyOnTheSameContext() {
        Observation.Context context = new Observation.Context();
        context.addHighCardinalityKeyValue(KeyValue.of("http.url.query", "secret=1"));
        context.addHighCardinalityKeyValue(KeyValue.of("foo.access_token", "tok"));
        context.addHighCardinalityKeyValue(
                KeyValue.of("http.url", "https://example.com/api/portfolio?secret=token"));
        context.addLowCardinalityKeyValue(KeyValue.of("http.route", CONCRETE_PORTFOLIO_PATH));
        context.addLowCardinalityKeyValue(KeyValue.of("http.method", "GET"));

        filter.map(context);

        assertThat(allKeys(context))
                .doesNotContain("http.url.query", "foo.access_token")
                .contains("http.method", "http.route", "http.url");
        assertThat(value(context, "http.url")).isEqualTo("https://example.com/api/portfolio");
        assertThat(value(context, "http.route"))
                .isEqualTo("/api/portfolio/{id}")
                .isNotEqualTo(CONCRETE_PORTFOLIO_PATH);
        assertThat(value(context, "http.method")).isEqualTo("GET");
    }

    @Test
    void lowercasePathKeysAreTemplatedWhileMixedCaseAreNot() {
        Observation.Context context = new Observation.Context();
        context.addLowCardinalityKeyValue(KeyValue.of("http.route", CONCRETE_PORTFOLIO_PATH));
        context.addHighCardinalityKeyValue(KeyValue.of("url.path", CONCRETE_PORTFOLIO_PATH));
        context.addHighCardinalityKeyValue(KeyValue.of("http.target", CONCRETE_PORTFOLIO_PATH));
        context.addLowCardinalityKeyValue(KeyValue.of("HTTP.ROUTE", CONCRETE_PORTFOLIO_PATH));
        context.addHighCardinalityKeyValue(KeyValue.of("Http.Route", CONCRETE_PORTFOLIO_PATH));

        filter.map(context);

        assertThat(value(context, "http.route")).isEqualTo("/api/portfolio/{id}");
        assertThat(value(context, "url.path")).isEqualTo("/api/portfolio/{id}");
        assertThat(value(context, "http.target")).isEqualTo("/api/portfolio/{id}");
        assertThat(value(context, "HTTP.ROUTE")).isEqualTo(CONCRETE_PORTFOLIO_PATH);
        assertThat(value(context, "Http.Route")).isEqualTo(CONCRETE_PORTFOLIO_PATH);
    }

    @Test
    void queryStringsAreStrippedFromRetainedUrlShapedValues() {
        Observation.Context context = new Observation.Context();
        context.addHighCardinalityKeyValue(
                KeyValue.of("http.url", "https://example.com/api/portfolio?secret=token"));
        context.addHighCardinalityKeyValue(
                KeyValue.of("url.full", "https://example.com/api/holdings?api_key=abc"));
        context.addHighCardinalityKeyValue(KeyValue.of("note", "keep this? yes"));

        filter.map(context);

        assertThat(value(context, "http.url")).isEqualTo("https://example.com/api/portfolio");
        assertThat(value(context, "url.full")).isEqualTo("https://example.com/api/holdings");
        assertThat(value(context, "note")).isEqualTo("keep this? yes");
    }

    @Test
    void uuidPathIsRecordedAsRouteTemplateDistinctFromConcretePath() {
        Observation.Context context = new Observation.Context();
        context.addLowCardinalityKeyValue(KeyValue.of("http.route", CONCRETE_PORTFOLIO_PATH));
        context.addHighCardinalityKeyValue(KeyValue.of("url.path", CONCRETE_PORTFOLIO_PATH));
        context.addHighCardinalityKeyValue(KeyValue.of("http.target", CONCRETE_PORTFOLIO_PATH + "?secret=1"));

        filter.map(context);

        assertThat(value(context, "http.route")).isEqualTo("/api/portfolio/{id}");
        assertThat(value(context, "url.path")).isEqualTo("/api/portfolio/{id}");
        assertThat(value(context, "http.target")).isEqualTo("/api/portfolio/{id}");
        assertThat(value(context, "http.route")).isNotEqualTo(CONCRETE_PORTFOLIO_PATH);
    }

    @Test
    void httpMethodAndTraceIdAreRetained() {
        Observation.Context context = new Observation.Context();
        context.addLowCardinalityKeyValue(KeyValue.of("http.method", "POST"));
        context.addLowCardinalityKeyValue(KeyValue.of("trace.id", "4bf92f3577b34da6a3ce929d0e0e4736"));
        context.addLowCardinalityKeyValue(KeyValue.of("span.id", "00f067aa0ba902b7"));
        context.addLowCardinalityKeyValue(KeyValue.of("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
        context.addLowCardinalityKeyValue(KeyValue.of("http.status_code", "200"));
        context.addHighCardinalityKeyValue(KeyValue.of("authorization", "Bearer secret"));

        filter.map(context);

        assertThat(value(context, "http.method")).isEqualTo("POST");
        assertThat(value(context, "trace.id")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(value(context, "span.id")).isEqualTo("00f067aa0ba902b7");
        assertThat(value(context, "traceparent"))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(value(context, "http.status_code")).isEqualTo("200");
        assertThat(allKeys(context)).doesNotContain("authorization");
    }

    @Test
    void leavesSpringSuppliedRouteTemplateUnchanged() {
        Observation.Context context = new Observation.Context();
        context.addLowCardinalityKeyValue(KeyValue.of("http.route", "/api/portfolio/{id}"));

        filter.map(context);

        assertThat(value(context, "http.route")).isEqualTo("/api/portfolio/{id}");
    }

    private static Set<String> allKeys(Observation.Context context) {
        return Stream.concat(
                        context.getLowCardinalityKeyValues().stream(),
                        context.getHighCardinalityKeyValues().stream())
                .map(KeyValue::getKey)
                .collect(Collectors.toSet());
    }

    private static String value(Observation.Context context, String key) {
        return Stream.concat(
                        context.getLowCardinalityKeyValues().stream(),
                        context.getHighCardinalityKeyValues().stream())
                .filter(kv -> key.equals(kv.getKey()))
                .map(KeyValue::getValue)
                .findFirst()
                .orElse(null);
    }
}
