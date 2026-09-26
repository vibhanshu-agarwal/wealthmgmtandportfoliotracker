package com.wealth.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Security regression for the removed portfolio advisor route ({@code GET /api/insights/{userId}/analyze}).
 *
 * <p>The route took its target user from the path and forwarded it to portfolio-service as
 * {@code X-User-Id}, never comparing it with the caller identity the gateway injects. Any
 * signed-in caller could read another user's derived analysis, and the 404 for a missing
 * portfolio disclosed whether one existed. So the proof is the outbound call itself: whoever
 * calls and whichever user the path names, nothing may reach portfolio-service. A recording stub
 * stands in for portfolio-service; a control call through {@link InsightService} proves the stub
 * sees a real forward, so the "nothing reached it" assertion cannot pass vacuously.
 *
 * <p>The requests go to insight-service directly, and nothing on this path reads {@code X-User-Id},
 * so the three caller classes exercise the same code. The loop records the intent that no caller,
 * including one with no identity or the read-only showcase, gets an exception; the 404 for all of
 * them is what makes a missing identity fail closed. Gateway authentication is not exercised here.
 *
 * <p>The structural test covers path variables whose name contains "user" and handler beans
 * holding an {@link InsightService} field. A handler that built its own portfolio-service client
 * would evade it; the guard targets accidental re-exposure, not a deliberate workaround.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.kafka.bootstrap-servers=localhost:0",
                "spring.kafka.listener.auto-startup=false",
                "spring.ai.model.chat=none",
                "spring.ai.openai.base-url=https://placeholder.openai.azure.com/",
                "spring.ai.openai.api-key=placeholder-key"
        }
)
@ActiveProfiles("default")
class AdvisorAnalyzeRemovalIT {

    private static final String SHOWCASE_USER = "00000000-0000-0000-0000-0000000d3110";
    private static final String E2E_USER = "00000000-0000-0000-0000-000000000e2e";
    private static final String CONTROL_USER = "control-user";

    /** Identity headers as the gateway forwards them for each caller class. */
    private static final Map<String, Map<String, String>> CALLERS = Map.of(
            "no forwarded identity", Map.of(),
            "ordinary signed-up account", Map.of("X-User-Id", UUID.randomUUID().toString()),
            "showcase account", Map.of("X-User-Id", SHOWCASE_USER));

    /** Users a caller might name in the path: a seeded writable user, a stranger, the showcase. */
    private static final List<String> TARGETS = List.of(E2E_USER, UUID.randomUUID().toString(), SHOWCASE_USER);

    private static final List<String> METHODS = List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");

    /** A path variable that names a user, e.g. {userId}, {user}, {ownerUserId}. */
    private static final Pattern USER_PATH_VARIABLE = Pattern.compile("\\{[^}]*user[^}]*}", Pattern.CASE_INSENSITIVE);

    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(TestContainerImages.REDIS).withExposedPorts(REDIS_PORT);

    static final PortfolioServiceStub portfolioService = PortfolioServiceStub.start();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
        registry.add("insight.portfolio-service.base-url", portfolioService::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        portfolioService.stop();
    }

    @LocalServerPort int port;

    @Autowired InsightService insightService;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void noCallerCanReachAnyUsersPortfolioThroughTheAdvisorPath() throws Exception {
        List<String> answered = new ArrayList<>();
        List<String> getNot404 = new ArrayList<>();
        for (var caller : CALLERS.entrySet()) {
            for (String target : TARGETS) {
                for (String method : METHODS) {
                    int status = send(method, "/api/insights/" + target + "/analyze", caller.getValue());
                    String call = caller.getKey() + " " + method + " /api/insights/" + target + "/analyze -> " + status;
                    if (status < 300) {
                        answered.add(call);
                    }
                    if ("GET".equals(method) && status != 404) {
                        getNot404.add(call);
                    }
                }
            }
        }

        // Control: the service call the route used must be visible to the stub.
        insightService.analyzePortfolio(CONTROL_USER);
        List<String> forwarded = portfolioService.forwardedUserIds();

        // Prerequisite: without it "nothing reached portfolio-service" would be vacuous.
        assertThat(forwarded).as("the control call must reach the portfolio stub").contains(CONTROL_USER);

        // Soft, so a regression reports every effect at once.
        assertSoftly(softly -> {
            softly.assertThat(answered).as("advisor requests that were answered").isEmpty();
            softly.assertThat(getNot404)
                    .as("GET must be a plain 404 for every caller and target")
                    .isEmpty();
            softly.assertThat(forwarded)
                    .as("X-User-Id values forwarded to portfolio-service besides the control call")
                    .containsExactly(CONTROL_USER);
        });
    }

    @Test
    void noInsightsHandlerHasAUserPathVariableOrHoldsThePortfolioForwardingService() {
        Map<RequestMappingInfo, HandlerMethod> insightMappings = new LinkedHashMap<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (info.getPatternValues().stream().anyMatch(p -> p.startsWith("/api/insights"))) {
                insightMappings.put(info, handler);
            }
        });

        assertThat(insightMappings).as("the public insight routes must still be mapped").isNotEmpty();

        assertSoftly(softly -> insightMappings.forEach((info, handler) -> {
            softly.assertThat(info.getPatternValues())
                    .as("%s must not take a user from the path; use the gateway-injected identity", info)
                    .noneMatch(p -> USER_PATH_VARIABLE.matcher(p).find());
            softly.assertThat(fieldsOf(handler.getBeanType()))
                    .as("%s must not hold InsightService, which forwards a caller-chosen X-User-Id", handler)
                    .noneMatch(f -> InsightService.class.isAssignableFrom(f.getType()));
        }));
    }

    private static List<Field> fieldsOf(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            fields.addAll(List.of(c.getDeclaredFields()));
        }
        return fields;
    }

    private int send(String method, String path, Map<String, String> headers) throws Exception {
        HttpRequest.BodyPublisher body = List.of("POST", "PUT", "PATCH").contains(method)
                ? HttpRequest.BodyPublishers.ofString("{}")
                : HttpRequest.BodyPublishers.noBody();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(method, body);
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /**
     * Records the {@code X-User-Id} of every {@code GET /api/portfolio} and answers with one
     * single-holding portfolio, so a forwarded call would succeed and be seen.
     */
    static final class PortfolioServiceStub {

        private final HttpServer server;
        private final List<String> forwardedUserIds = new CopyOnWriteArrayList<>();

        private PortfolioServiceStub(HttpServer server) {
            this.server = server;
        }

        static PortfolioServiceStub start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                PortfolioServiceStub stub = new PortfolioServiceStub(server);
                server.createContext("/api/portfolio", exchange -> {
                    String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
                    stub.forwardedUserIds.add(String.valueOf(userId));
                    byte[] body = ("[{\"id\":\"" + UUID.randomUUID() + "\",\"userId\":\"" + userId
                            + "\",\"createdAt\":\"2026-09-27T00:00:00Z\",\"holdings\":[{\"id\":\""
                            + UUID.randomUUID() + "\",\"assetTicker\":\"AAPL\",\"quantity\":10}]}]")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
                server.start();
                return stub;
            } catch (IOException e) {
                throw new IllegalStateException("could not start the portfolio-service stub", e);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<String> forwardedUserIds() {
            return List.copyOf(forwardedUserIds);
        }

        void stop() {
            server.stop(0);
        }
    }
}
