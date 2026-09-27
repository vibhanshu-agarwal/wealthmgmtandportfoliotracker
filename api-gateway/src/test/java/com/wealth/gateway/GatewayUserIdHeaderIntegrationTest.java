package com.wealth.gateway;

import static com.wealth.gateway.DemoResetGatewayFixtures.USER_ID_HEADER;
import static com.wealth.gateway.DemoResetGatewayFixtures.writableToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.gateway.DemoResetGatewayFixtures.Capture;
import com.wealth.gateway.DemoResetGatewayFixtures.RecordingPortfolioStub;
import com.wealth.gateway.DemoResetGatewayFixtures.RouteProbe;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves, from the downstream request itself, what {@code X-User-Id} the gateway forwards on
 * every GlobalFilter-routed path class under the production route list.
 *
 * <ul>
 *   <li><b>Protected routes</b> forward exactly one {@code X-User-Id}: the verified JWT subject,
 *       whatever the caller sent — nothing, one conflicting value, or duplicate lines in mixed
 *       case, one of them comma-joined.
 *   <li><b>Routed permit-all routes</b> (service health and {@code /api/internal/**}) forward no
 *       {@code X-User-Id} at all, with or without a valid bearer token, so the gateway neither
 *       passes the caller's value through nor injects one of its own.
 *   <li><b>Missing or invalid authentication</b> on a protected route is refused with 401 and no
 *       downstream call, including a correctly signed token with no usable {@code sub}, which only
 *       {@link JwtAuthenticationFilter} (not Spring Security) refuses.
 * </ul>
 *
 * <p>Every routed case also checks which route {@link RouteProbe} saw win, so a pass is evidence
 * about the GlobalFilter chain rather than about a controller. {@code /api/auth/**} and
 * {@code /actuator/health} are NOT routed: they are served by controllers and actuator, never
 * reach any GlobalFilter, and are checked separately at the end only to prove that.
 *
 * <p>Requests are written byte-for-byte over a raw socket so duplicate, differently-cased header
 * lines reach the gateway exactly as sent; a client-side {@code HttpHeaders} would collapse the
 * differently-cased names. The recording stubs merge header names case-insensitively, keeping
 * one value per line, so "one value" means one header line of any casing.
 *
 * <p>Protected routes are exercised with GET only. The filter does not branch on method, and
 * {@link MarketPriceWriteGatewayIntegrationTest} covers a protected POST.
 *
 * <p>Characterization, not a fix: this passes against the current filter. Its value is that the
 * filter regressions it names make it fail; see the change's mutation evidence.
 *
 * <p>Run via: {@code ./gradlew :api-gateway:integrationTest}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
@Import(DemoResetGatewayFixtures.ProviderOverrides.class)
@ActiveProfiles({"prod", "azure"})
class GatewayUserIdHeaderIntegrationTest {

    private static final int REDIS_PORT = 6379;

    /** Caller-supplied values; the scan below requires none of them anywhere downstream. */
    private static final String SPOOF_A = "spoof-a-" + UUID.randomUUID();
    private static final String SPOOF_B = "spoof-b-" + UUID.randomUUID();
    private static final String SPOOF_C = "spoof-c-" + UUID.randomUUID();
    /** A real account id — the showcase user — as the most plausible impersonation target. */
    private static final String SPOOF_REAL_ACCOUNT = TestJwtFactory.DEMO_USER_ID;

    private static RecordingPortfolioStub portfolioStub;
    private static RecordingPortfolioStub marketStub;
    private static RecordingPortfolioStub insightStub;

    /** Distinct client address per request so no test shares a rate-limit bucket with another. */
    private static final AtomicInteger CLIENT_SEQUENCE = new AtomicInteger();

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(TestContainerImages.REDIS).withExposedPorts(REDIS_PORT);

    // application-prod.yml requires spring.datasource.* at startup; see
    // DemoResetProductionRoutingIntegrationTest for the same rationale.
    @Container
    @SuppressWarnings({"resource", "rawtypes"})
    static final org.testcontainers.postgresql.PostgreSQLContainer postgres =
            new org.testcontainers.postgresql.PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @DynamicPropertySource
    static void productionProperties(DynamicPropertyRegistry registry) throws IOException {
        if (portfolioStub == null) {
            portfolioStub = RecordingPortfolioStub.start();
            marketStub = RecordingPortfolioStub.start();
            insightStub = RecordingPortfolioStub.start();
        }
        registry.add("app.routes.portfolio-url", portfolioStub::baseUrl);
        registry.add("app.routes.market-data-url", marketStub::baseUrl);
        registry.add("app.routes.insight-url", insightStub::baseUrl);
        registry.add(
                "spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
        registry.add("spring.data.redis.timeout", () -> "3s");
        registry.add("spring.data.redis.connect-timeout", () -> "3s");

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);
    }

    @AfterAll
    static void stopStubs() {
        for (RecordingPortfolioStub stub : List.of(portfolioStub, marketStub, insightStub)) {
            stub.close();
        }
        portfolioStub = null;
        marketStub = null;
        insightStub = null;
    }

    @LocalServerPort int port;

    @Autowired RouteProbe routeProbe;

    @BeforeEach
    void setUp() {
        for (RecordingPortfolioStub stub : List.of(portfolioStub, marketStub, insightStub)) {
            stub.reset();
        }
        routeProbe.reset();
    }

    // ── Route and caller-header matrices ────────────────────────────────────

    /** Which stub a route forwards to; resolved lazily because stubs start with the context. */
    enum Upstream {
        PORTFOLIO,
        MARKET,
        INSIGHT;

        RecordingPortfolioStub stub() {
            return switch (this) {
                case PORTFOLIO -> portfolioStub;
                case MARKET -> marketStub;
                case INSIGHT -> insightStub;
            };
        }
    }

    record RoutedPath(String path, String routeId, Upstream upstream) {
        @Override
        public String toString() {
            return path + " -> " + routeId;
        }
    }

    /** One JWT-protected path per user-facing production route. */
    static List<RoutedPath> protectedPaths() {
        return List.of(
                new RoutedPath("/api/portfolio/holdings", "portfolio-service", Upstream.PORTFOLIO),
                new RoutedPath("/api/assets/search", "asset-discovery", Upstream.PORTFOLIO),
                new RoutedPath("/api/market/prices", "market-data-service", Upstream.MARKET),
                new RoutedPath("/api/insights/market-summary", "insight-service", Upstream.INSIGHT),
                new RoutedPath("/api/chat/sessions", "insight-chat", Upstream.INSIGHT));
    }

    /**
     * Every permit-all path that a gateway route actually forwards. {@code /api/auth/**} and
     * {@code /actuator/health} are permit-all too, but no route matches them.
     */
    static List<RoutedPath> routedPermitAllPaths() {
        return List.of(
                new RoutedPath("/api/portfolio/health", "portfolio-service", Upstream.PORTFOLIO),
                new RoutedPath("/api/market/health", "market-data-service", Upstream.MARKET),
                new RoutedPath("/api/insights/health", "insight-service", Upstream.INSIGHT),
                new RoutedPath(
                        "/api/internal/portfolio/seed", "internal-portfolio-seed", Upstream.PORTFOLIO),
                new RoutedPath(
                        "/api/internal/market-data/seed", "internal-market-data-seed", Upstream.MARKET),
                new RoutedPath(
                        "/api/internal/insight/seed", "internal-insight-seed", Upstream.INSIGHT));
    }

    /** One literal request header line, sent exactly as written. */
    record HeaderLine(String name, String value) {}

    /** What the caller sends as its own identity, as literal request lines. */
    enum CallerIdentity {
        NONE(List.of()),
        ONE_CONFLICTING(List.of(new HeaderLine("X-User-Id", SPOOF_REAL_ACCOUNT))),
        DUPLICATES_MIXED_CASE(
                List.of(
                        new HeaderLine("X-User-Id", SPOOF_A),
                        new HeaderLine("x-user-id", SPOOF_REAL_ACCOUNT),
                        new HeaderLine("X-USER-ID", SPOOF_B + ", " + SPOOF_C)));

        final List<HeaderLine> headerLines;

        CallerIdentity(List<HeaderLine> headerLines) {
            this.headerLines = headerLines;
        }

        List<String> callerValues() {
            return headerLines.stream().map(HeaderLine::value).toList();
        }
    }

    static Stream<Arguments> protectedPathsTimesCallerIdentity() {
        return protectedPaths().stream()
                .flatMap(path -> Stream.of(CallerIdentity.values())
                        .map(identity -> Arguments.of(path, identity)));
    }

    static Stream<Arguments> permitAllPathsTimesCallerIdentity() {
        return routedPermitAllPaths().stream()
                .flatMap(path -> Stream.of(CallerIdentity.values())
                        .map(identity -> Arguments.of(path, identity)));
    }

    // ── Protected routes: exactly the verified subject ──────────────────────

    @ParameterizedTest(name = "{0} with caller identity {1}")
    @MethodSource("protectedPathsTimesCallerIdentity")
    void protectedRouteForwardsExactlyTheVerifiedSubject(RoutedPath route, CallerIdentity caller)
            throws IOException {
        String subject = UUID.randomUUID().toString();
        List<HeaderLine> lines = new ArrayList<>(caller.headerLines);
        lines.add(new HeaderLine("Authorization", "Bearer " + writableToken(subject)));

        int status = send(route.path(), lines);

        assertThat(status).as("stub answers 200 when reached").isEqualTo(200);
        assertThat(routeProbe.onlyMatchedRouteId()).isEqualTo(route.routeId());
        Capture capture = onlyDownstreamCall(route.upstream());
        assertThat(capture.path()).isEqualTo(route.path());
        assertThat(capture.headers().get(USER_ID_HEADER))
                .as("exactly one X-User-Id line, carrying the JWT subject")
                .containsExactly(subject);
        assertNoCallerValueForwarded(capture, caller);
    }

    // ── Routed permit-all: no identity forwarded or injected ────────────────

    @ParameterizedTest(name = "anonymous {0} with caller identity {1}")
    @MethodSource("permitAllPathsTimesCallerIdentity")
    void anonymousPermitAllRouteForwardsNoUserId(RoutedPath route, CallerIdentity caller)
            throws IOException {
        int status = send(route.path(), caller.headerLines);

        assertThat(status).isEqualTo(200);
        assertThat(routeProbe.onlyMatchedRouteId()).isEqualTo(route.routeId());
        Capture capture = onlyDownstreamCall(route.upstream());
        assertThat(capture.headers().get(USER_ID_HEADER)).as("no X-User-Id line at all").isNull();
        assertNoCallerValueForwarded(capture, caller);
    }

    /**
     * A valid token on a permit-all path still gives Spring Security a principal. The gateway must
     * not turn it into an {@code X-User-Id}: these paths are keyed on {@code X-Internal-Api-Key}
     * or are unauthenticated health checks, and a forwarded identity there has no verified meaning.
     */
    @ParameterizedTest(name = "authenticated {0} with caller identity {1}")
    @MethodSource("permitAllPathsTimesCallerIdentity")
    void authenticatedPermitAllRouteInjectsNoUserId(RoutedPath route, CallerIdentity caller)
            throws IOException {
        String subject = UUID.randomUUID().toString();
        List<HeaderLine> lines = new ArrayList<>(caller.headerLines);
        lines.add(new HeaderLine("Authorization", "Bearer " + writableToken(subject)));

        int status = send(route.path(), lines);

        assertThat(status).isEqualTo(200);
        assertThat(routeProbe.onlyMatchedRouteId()).isEqualTo(route.routeId());
        Capture capture = onlyDownstreamCall(route.upstream());
        assertThat(capture.headers().get(USER_ID_HEADER))
                .as("the principal's subject is not injected on a permit-all route")
                .isNull();
        assertNoCallerValueForwarded(capture, caller);
        assertNoHeaderValueEquals(capture, subject);
    }

    // ── Protected routes fail closed without a usable identity ──────────────

    enum RejectedCredential {
        NO_AUTHORIZATION,
        NOT_A_JWT,
        WRONG_SIGNING_KEY,
        EXPIRED,
        UNSIGNED_ALG_NONE,
        /** Correctly signed, so Spring Security accepts it; only the gateway filter refuses it. */
        SIGNED_WITHOUT_SUB,
        /** As above, with a whitespace-only subject. */
        SIGNED_WITH_BLANK_SUB;

        String authorizationValue(String subject) {
            return switch (this) {
                case NO_AUTHORIZATION -> null;
                case NOT_A_JWT -> "Bearer not-a-jwt";
                case WRONG_SIGNING_KEY -> "Bearer " + TestJwtFactory.mint(
                        subject, Duration.ofHours(1), "a-different-secret-that-is-also-32-chars-long");
                case EXPIRED -> "Bearer " + TestJwtFactory.mint(subject, Duration.ofMinutes(-5));
                case UNSIGNED_ALG_NONE -> "Bearer " + unsignedToken(subject);
                case SIGNED_WITHOUT_SUB -> "Bearer " + signedToken(null);
                case SIGNED_WITH_BLANK_SUB -> "Bearer " + signedToken("   ");
            };
        }
    }

    static Stream<Arguments> protectedPathsTimesRejectedCredential() {
        return protectedPaths().stream()
                .flatMap(path -> Stream.of(RejectedCredential.values())
                        .map(credential -> Arguments.of(path, credential)));
    }

    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("protectedPathsTimesRejectedCredential")
    void protectedRouteWithoutUsableIdentityNeverReachesDownstream(
            RoutedPath route, RejectedCredential credential) throws IOException {
        List<HeaderLine> lines = new ArrayList<>(CallerIdentity.DUPLICATES_MIXED_CASE.headerLines);
        String authorization = credential.authorizationValue(SPOOF_REAL_ACCOUNT);
        if (authorization != null) {
            lines.add(new HeaderLine("Authorization", authorization));
        }

        int status = send(route.path(), lines);

        assertThat(status).isEqualTo(401);
        assertNoDownstreamCall();
    }

    // ── Not GlobalFilter routes: controller and actuator handling ───────────

    /**
     * {@code /api/auth/**} is permit-all but is served by {@code AuthController}, so no route
     * matches and {@link JwtAuthenticationFilter}'s {@code /api/auth} branch never runs. This
     * pins that fact; it says nothing about the controller's own use of headers.
     *
     * <p>Blank credentials make {@code AuthController} answer its uniform 401 before any store
     * lookup, so the body proves the controller handled the request, not Spring Security.
     */
    @Test
    void authPathIsControllerHandledAndNeverRouted() throws IOException {
        List<HeaderLine> lines = new ArrayList<>(CallerIdentity.DUPLICATES_MIXED_CASE.headerLines);
        lines.add(new HeaderLine("Content-Type", "application/json"));

        String response = exchange("POST", "/api/auth/login", lines, "{}");

        assertThat(response)
                .as("AuthController's uniform auth error")
                .startsWith("HTTP/1.1 401 ")
                .contains("Invalid username or password.");
        assertThat(routeProbe.matchedRouteIds()).as("no gateway route matches /api/auth").isEmpty();
        assertNoDownstreamCall();
    }

    /** Actuator is served by its own handler mapping; see the note in JwtAuthenticationFilter. */
    @Test
    void actuatorHealthIsNeverRouted() throws IOException {
        int status = send("/actuator/health", CallerIdentity.DUPLICATES_MIXED_CASE.headerLines);

        assertThat(status).isEqualTo(200);
        assertThat(routeProbe.matchedRouteIds()).as("no gateway route matches actuator").isEmpty();
        assertNoDownstreamCall();
    }

    // ── Assertions ──────────────────────────────────────────────────────────

    private static Capture onlyDownstreamCall(Upstream expected) {
        for (Upstream other : Upstream.values()) {
            if (other != expected) {
                assertThat(other.stub().callCount())
                        .as("%s must not be contacted", other)
                        .isZero();
            }
        }
        return expected.stub().onlyCapture();
    }

    private static void assertNoDownstreamCall() {
        for (Upstream upstream : Upstream.values()) {
            assertThat(upstream.stub().callCount())
                    .as("%s must not be contacted", upstream)
                    .isZero();
        }
    }

    /**
     * No caller-supplied identity value may survive under ANY header name, so a regression that
     * moved it to another header (or joined it into one) still fails here.
     */
    private static void assertNoCallerValueForwarded(Capture capture, CallerIdentity caller) {
        List<String> forwardedValues = new ArrayList<>();
        capture.headers().forEach((name, values) -> values.forEach(
                value -> forwardedValues.add(name + ": " + value)));
        for (String callerValue : caller.callerValues()) {
            for (String part : callerValue.split(",\\s*")) {
                assertThat(forwardedValues)
                        .as("caller value %s must not be forwarded", part)
                        .noneMatch(line -> line.contains(part));
            }
        }
    }

    private static void assertNoHeaderValueEquals(Capture capture, String subject) {
        capture.headers().forEach((name, values) -> assertThat(values)
                .as("header %s must not carry the subject", name)
                .doesNotContain(subject));
    }

    // ── Raw HTTP client and token helpers ───────────────────────────────────

    /** Sends a GET and returns only the response status. */
    private int send(String path, List<HeaderLine> headerLines) throws IOException {
        String response = exchange("GET", path, headerLines, null);
        return Integer.parseInt(response.split(" ", 3)[1]);
    }

    /**
     * Writes one HTTP/1.1 request exactly as given and returns the raw response, read until the
     * gateway closes the connection. A fresh {@code X-Forwarded-For} gives each request its own
     * anonymous rate-limit bucket under {@code trust-xff-last-hop}; authenticated requests are
     * keyed on their unique subject.
     */
    private String exchange(String method, String path, List<HeaderLine> headerLines, String body)
            throws IOException {
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder request = new StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: localhost:").append(port).append("\r\n")
                .append("Connection: close\r\n")
                .append("X-Forwarded-For: ").append(nextClientAddress()).append("\r\n");
        for (HeaderLine line : headerLines) {
            request.append(line.name()).append(": ").append(line.value()).append("\r\n");
        }
        if (body != null) {
            request.append("Content-Length: ").append(payload.length).append("\r\n");
        }
        request.append("\r\n");

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(payload);
            out.flush();
            String response = new String(
                    socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(response).as("HTTP status line").startsWith("HTTP/1.1 ");
            return response;
        }
    }

    private static String nextClientAddress() {
        int n = CLIENT_SEQUENCE.incrementAndGet();
        return "198.51." + (n / 250) % 250 + "." + (n % 250 + 1);
    }

    private static String signedToken(String subjectOrNull) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofHours(1))))
                .claims(Map.of("ro", false));
        if (subjectOrNull != null) {
            builder.subject(subjectOrNull);
        }
        return builder
                .signWith(
                        Keys.hmacShaKeyFor(TestJwtFactory.TEST_SECRET.getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256)
                .compact();
    }

    private static String unsignedToken(String subject) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        long exp = Instant.now().plus(Duration.ofHours(1)).getEpochSecond();
        String header = encoder.encodeToString(
                "{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = encoder.encodeToString(
                ("{\"sub\":\"" + subject + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + claims + ".";
    }
}
