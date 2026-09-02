package com.wealth.gateway;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Shared fixtures for the two profile-specific demo-reset routing tests
 * ({@link DemoResetRoutingIntegrationTest}, {@link DemoResetProductionRoutingIntegrationTest}).
 *
 * <p>Deliberately narrow: a recording downstream stub, a passive route-observing probe, provider
 * beans built from the REAL provider classes with test values, and JWT helpers. Nothing here
 * replaces a route definition or any filter under test — the suites exercise the real
 * {@code application.yml} / {@code application-prod.yml} route lists and the real global filter
 * chain, and only redirect the downstream destination and credentials.
 */
final class DemoResetGatewayFixtures {

    static final String RESET_PATH = "/api/portfolio/demo-reset";
    static final String INTERNAL_RESET_PATH = "/api/internal/portfolio/demo-reset";
    static final String HOLDINGS_PATH = "/api/portfolio/holdings";
    static final String RESET_ROUTE_ID = "demo-reset-manual";
    static final String PORTFOLIO_ROUTE_ID = "portfolio-service";

    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";
    static final String REPLICA_TOKEN_HEADER = "X-Gateway-Replica-Token";
    static final String USER_ID_HEADER = "X-User-Id";

    /** Fixed synthetic replica vector from Task 5.1b — hashes to {@link #EXPECTED_REPLICA_TOKEN}. */
    static final String REPLICA_NAME = "api-gateway--0000000-abcdefg";

    static final String EXPECTED_REPLICA_TOKEN = "95ca17821ade";

    static final String TEST_INTERNAL_KEY = "test-internal-key";

    /** Non-trivial body so "forwarded byte-for-byte" is an assertion with teeth. */
    static final String RESET_BODY = "{\"expectedVersion\":7,\"note\":\"unchanged bytes\"}";

    private DemoResetGatewayFixtures() {}

    static String demoToken() {
        return readOnlyToken(TestJwtFactory.DEMO_USER_ID);
    }

    static String readOnlyToken(String sub) {
        return TestJwtFactory.mint(
                sub, Duration.ofHours(1), TestJwtFactory.TEST_SECRET, Map.of("ro", true));
    }

    static String writableToken(String sub) {
        return TestJwtFactory.mint(
                sub, Duration.ofHours(1), TestJwtFactory.TEST_SECRET, Map.of("ro", false));
    }

    /**
     * Replaces the two provider {@code @Component}s with instances of the SAME production classes
     * built from test values, so the filter still calls the real {@code value()} /
     * {@code isConfigured()} / {@code replicaToken()} implementations — including
     * {@link ReplicaTokenFormula}, which is what makes {@link #EXPECTED_REPLICA_TOKEN} a real
     * derivation rather than a hard-coded string handed to the filter.
     *
     * <p>The internal key comes from a property so a nested context can exercise the unconfigured
     * case without mutating the process environment.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderOverrides {

        @Bean
        InternalApiKeyProvider internalApiKeyProvider(
                @Value("${test.internal-api-key:" + TEST_INTERNAL_KEY + "}") String internalApiKey) {
            return new InternalApiKeyProvider(internalApiKey);
        }

        @Bean
        ReplicaTokenProvider replicaTokenProvider() {
            return new ReplicaTokenProvider(REPLICA_NAME);
        }

        @Bean
        RouteProbe routeProbe() {
            return new RouteProbe();
        }
    }

    /**
     * Passive observer of the route Spring Cloud Gateway actually selected. Runs at the very front
     * of the global filter chain — after {@code RoutePredicateHandlerMapping} has resolved the
     * route, before any filter under test — records the winning route id and forwards unchanged.
     *
     * <p>This is how the suites prove {@code demo-reset-manual} really won, rather than inferring
     * it from a downstream side effect that a differently-matched route could also produce.
     */
    static final class RouteProbe implements GlobalFilter, Ordered {

        private final List<String> matchedRouteIds = new CopyOnWriteArrayList<>();

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
            matchedRouteIds.add(route == null ? "<none>" : route.getId());
            return chain.filter(exchange);
        }

        List<String> matchedRouteIds() {
            return List.copyOf(matchedRouteIds);
        }

        String onlyMatchedRouteId() {
            List<String> ids = matchedRouteIds();
            if (ids.size() != 1) {
                throw new AssertionError("expected exactly one routed request, got " + ids);
            }
            return ids.get(0);
        }

        void reset() {
            matchedRouteIds.clear();
        }
    }

    /** One captured downstream request: everything the reset contract makes claims about. */
    record Capture(String method, String path, HttpHeaders headers, byte[] body) {

        String bodyAsString() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /**
     * Recording downstream portfolio stub. Answers with a scriptable status/body/headers so the
     * suites can prove pass-through of a downstream conflict or failure, and duplicate-header
     * conflicts on the replica token, without a mocking framework.
     */
    static final class RecordingPortfolioStub implements AutoCloseable {

        private final HttpServer server;
        private final List<Capture> captures = new CopyOnWriteArrayList<>();

        private volatile int status = 200;
        private volatile byte[] responseBody = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        private volatile Map<String, List<String>> responseHeaders = Map.of();

        private RecordingPortfolioStub(HttpServer server) {
            this.server = server;
        }

        static RecordingPortfolioStub start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            RecordingPortfolioStub stub = new RecordingPortfolioStub(server);
            server.createContext("/", stub::handle);
            server.start();
            return stub;
        }

        int port() {
            return server.getAddress().getPort();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + port();
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] body = exchange.getRequestBody().readAllBytes();
            HttpHeaders headers = new HttpHeaders();
            exchange.getRequestHeaders().forEach(headers::addAll);
            captures.add(
                    new Capture(
                            exchange.getRequestMethod(),
                            exchange.getRequestURI().getPath(),
                            HttpHeaders.readOnlyHttpHeaders(headers),
                            body));

            responseHeaders.forEach(
                    (name, values) -> values.forEach(v -> exchange.getResponseHeaders().add(name, v)));
            byte[] payload = responseBody;
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }

        List<Capture> captures() {
            return List.copyOf(captures);
        }

        Capture onlyCapture() {
            List<Capture> all = captures();
            if (all.size() != 1) {
                throw new AssertionError("expected exactly one downstream call, got " + all.size());
            }
            return all.get(0);
        }

        int callCount() {
            return captures.size();
        }

        /** Restores the default 200 response and clears recorded traffic. */
        void reset() {
            captures.clear();
            status = 200;
            responseBody = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            responseHeaders = Map.of();
        }

        void respondWith(int status, String body) {
            this.status = status;
            this.responseBody = body.getBytes(StandardCharsets.UTF_8);
        }

        void addResponseHeader(String name, String... values) {
            Map<String, List<String>> merged = new LinkedHashMap<>(responseHeaders);
            List<String> existing = new ArrayList<>(merged.getOrDefault(name, List.of()));
            existing.addAll(List.of(values));
            merged.put(name, List.copyOf(existing));
            this.responseHeaders = Map.copyOf(merged);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
