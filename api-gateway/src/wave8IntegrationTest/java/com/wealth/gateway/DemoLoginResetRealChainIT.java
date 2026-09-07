package com.wealth.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.wealth.gateway.auth.AuthenticationService;
import com.wealth.gateway.auth.LoginResponse;
import com.wealth.gateway.auth.SignupService;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.AssetHoldingRepository;
import com.wealth.portfolio.PortfolioService;
import com.wealth.portfolio.composition.HoldingReplacementService;
import com.wealth.portfolio.composition.CompositionTuplePreparer;
import com.wealth.portfolio.composition.RawIntent;
import com.wealth.portfolio.demo.DemoResetService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real persistence is required: a response-status fixture cannot provide this evidence. */
@Tag("integration")
class DemoLoginResetRealChainIT {
    private static final String DEMO = "00000000-0000-0000-0000-0000000d3110";
    private static final String KEY = "wave8-local-test-key";
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("portfolio_db").withUsername("wealth_user").withPassword("wealth_pass");
    private static ConfigurableApplicationContext portfolio;
    private static ConfigurableApplicationContext gateway;
    private static volatile String scenario;
    private static final AtomicLong ticks = new AtomicLong();
    private static final List<Wire> wires = new CopyOnWriteArrayList<>();
    private final Capture success = new Capture();
    private final Capture skipped = new Capture();

    @BeforeAll static void startContexts() {
        postgres.start();
        portfolio = new SpringApplicationBuilder(PortfolioContext.class).web(WebApplicationType.SERVLET)
                .run("--spring.config.location=" + Path.of("../portfolio-service/src/main/resources/application.yml").toUri(),
                        "--spring.profiles.active=local", "--server.port=0",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(), "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.kafka.listener.auto-startup=false", "--app.internal.api-key=" + KEY,
                        "--spring.cloud.gateway.server.webflux.enabled=false", "--management.tracing.enabled=true",
                        "--management.tracing.export.enabled=false", "--management.otlp.metrics.export.enabled=false");
    }

    private static ConfigurableApplicationContext startGateway(String resetTimeout, String overallTimeout) {
        return new SpringApplicationBuilder(GatewayContext.class).web(WebApplicationType.REACTIVE)
                .run("--spring.config.location=" + Path.of("src/main/resources/application.yml").toUri(),
                        "--spring.profiles.active=wave8-chain", "--server.port=0", "--spring.main.allow-bean-definition-overriding=true",
                        "--app.routes.portfolio-url=http://127.0.0.1:" + port(portfolio),
                        "--app.demo-login-reset.eligibility-timeout=5s", "--app.demo-login-reset.reset-timeout=" + resetTimeout,
                        "--app.demo-login-reset.overall-timeout=" + overallTimeout, "--management.tracing.enabled=true",
                        "--management.tracing.export.enabled=false", "--management.otlp.metrics.export.enabled=false",
                        "--management.health.redis.enabled=false");
    }

    @AfterAll static void closeContexts() {
        if (gateway != null) gateway.close();
        if (portfolio != null) portfolio.close();
        postgres.stop();
    }

    void capture() {
        success.start(); skipped.start();
        logger(DemoResetService.class).addAppender(success);
        logger(DemoLoginResetDiagnostics.class).addAppender(skipped);
    }
    @AfterEach void detach() {
        logger(DemoResetService.class).detachAppender(success);
        logger(DemoLoginResetDiagnostics.class).detachAppender(skipped);
        if (gateway != null) { gateway.close(); gateway = null; }
    }

    @Test void committedResetResponseExceedsResetLegTimeout() throws Exception {
        Map<String, Object> event = runCase("reset_timeout");
        assertThat(event).containsEntry("reason", "reset_timeout").containsEntry("leg", "reset")
                .containsEntry("timeoutScope", "per-leg").containsEntry("elapsedMillis", 137L)
                .containsEntry("overallTimeoutPhase", null).containsEntry("httpStatus", null);
    }
    @Test void committedResetResponseExceedsOverallDeadlineInFlight() throws Exception {
        Map<String, Object> event = runCase("reset_in_flight");
        assertThat(event).containsEntry("reason", "overall_timeout").containsEntry("leg", "overall")
                .containsEntry("timeoutScope", "overall").containsEntry("elapsedMillis", 411L)
                .containsEntry("overallTimeoutPhase", "reset_in_flight").containsEntry("httpStatus", null);
    }
    @Test void committedResetResponsePrecedesOverallDeadlinePostResponse() throws Exception {
        Map<String, Object> event = runCase("reset_post_response");
        assertThat(event).containsEntry("reason", "overall_timeout").containsEntry("leg", "overall")
                .containsEntry("timeoutScope", "overall").containsEntry("elapsedMillis", 411L)
                .containsEntry("overallTimeoutPhase", "reset_post_response").containsEntry("httpStatus", 200)
                .containsEntry("attemptedTarget", null);
    }
    @Test void successfulResetPrecedesGatewaySuccessHandlerFailure() throws Exception {
        Map<String, Object> event = runCase("gateway_orchestration_error");
        assertThat(event).containsEntry("reason", "gateway_orchestration_error").containsEntry("leg", "reset")
                .containsEntry("timeoutScope", null).containsEntry("elapsedMillis", null)
                .containsEntry("overallTimeoutPhase", null).containsEntry("httpStatus", 200)
                .containsEntry("attemptedTarget", null).containsEntry("exceptionClass", IllegalStateException.class.getName());
    }

    private Map<String, Object> runCase(String nextScenario) throws Exception {
        var repositories = portfolio.getBean(PortfolioRepository.class);
        var before = repositories.findByUserId(DEMO).getFirst();
        portfolio.getBean(HoldingReplacementService.class).replace(DEMO, before.getVersion(),
                List.of(new RawIntent("AAPL", new BigDecimal("99.99990000"))), portfolio.getBean(CompositionTuplePreparer.class));
        portfolio.getBean(JdbcTemplate.class).update("UPDATE portfolios SET updated_at=timestamp '2020-01-01 00:00:00' WHERE user_id=?", DEMO);
        long observed = repositories.findByUserId(DEMO).getFirst().getVersion();
        scenario = nextScenario;
        ticks.set(0); wires.clear(); success.events.clear(); skipped.events.clear();
        String trace = UUID.randomUUID().toString().replace("-", "");
        gateway = nextScenario.equals("reset_timeout") ? startGateway("1s", "5s") : startGateway("5s", "2s");
        // Spring Boot reinitializes Logback on context startup. Attach only after both contexts exist.
        capture();
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port(gateway)).responseTimeout(Duration.ofSeconds(10)).build()
                .post().uri("/api/auth/login").header("traceparent", "00-" + trace + "-1234567890abcdef-01")
                .bodyValue(new LoginDtos.LoginRequest("demo@example.com", "password"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.token").isEqualTo("fresh-jwt").jsonPath("$.userId").isEqualTo(DEMO)
                .jsonPath("$.email").isEqualTo("demo@example.com").jsonPath("$.name").isEqualTo("Demo");
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(success.events).filteredOn(e -> trace.equals(e.getMDCPropertyMap().get("traceId"))).hasSize(1);
            assertThat(skipped.events).hasSize(1);
        });
        var after = repositories.findByUserId(DEMO).getFirst();
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getVersion()).isEqualTo(observed + 1);
        assertGolden(after.getId());
        assertThat(success.events).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage()).isEqualTo("event=demo_reset_succeeded version=" + (observed + 1));
            assertThat(e.getMDCPropertyMap()).containsEntry("traceId", trace);
        });
        assertThat(wires).hasSize(2);
        assertThat(wires.get(0).path()).isEqualTo("/api/portfolio");
        assertThat(wires.get(1).path()).isEqualTo("/api/internal/portfolio/demo-reset");
        assertThat(wires.get(1).key()).isEqualTo(KEY);
        for (Wire wire : wires) assertThat(wire.traceparent()).matches("00-" + trace + "-[0-9a-f]{16}-01");
        Map<String, Object> event = new LinkedHashMap<>();
        skipped.events.getFirst().getKeyValuePairs().forEach(pair -> event.put(pair.key, pair.value));
        assertThat(event).containsEntry("traceId", trace).containsEntry("event", "demo_reset_self_call_skipped")
                .containsEntry("internalApiKeyConfigured", true).containsEntry("originVerifyRequired", false)
                .containsEntry("eligibilityDispatchAttempted", true).containsEntry("resetDispatchAttempted", true)
                .containsEntry("internalApiKeyAttached", true).containsEntry("originVerifyHeaderAttached", null)
                .containsEntry("replicaToken", "");
        assertThat(wires.getFirst().originVerify()).as("eligibility X-Origin-Verify absent on wire").isNull();
        if (nextScenario.equals("reset_timeout") || nextScenario.equals("reset_in_flight")) {
            assertThat(event).containsEntry("attemptedTarget", "http://localhost:" + port(gateway) + "/api/internal/portfolio/demo-reset")
                    .containsEntry("exceptionClass", null);
            // Let the servlet decorator finish before the next case changes its scenario.
            await().atMost(Duration.ofSeconds(8)).until(() -> responseReleased);
        }
        return event;
    }

    private void assertGolden(UUID portfolioId) throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        var process = new ProcessBuilder("python", root.resolve("scripts/derive_demo_golden_state.py").toString(),
                "--catalog", root.resolve("config/seed-tickers.json").toString(), "--cost-basis-anchor", "2020-01-01T00:00:00Z")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        JsonNode oracle = new JsonMapper().readTree(output);
        var holdings = portfolio.getBean(AssetHoldingRepository.class).findByPortfolio(
                portfolio.getBean(PortfolioRepository.class).findById(portfolioId).orElseThrow());
        Map<String, JsonNode> expected = new HashMap<>();
        oracle.get("persistedHoldings").forEach(row -> expected.put(row.get("assetTicker").asText(), row));
        assertThat(holdings).hasSize(expected.size());
        for (var holding : holdings) {
            JsonNode row = expected.get(holding.getAssetTicker());
            assertThat(row).isNotNull();
            assertThat(holding.getQuantity().toPlainString()).isEqualTo(row.get("quantity").asText());
            assertThat(holding.getAvgCostBasis().toPlainString()).isEqualTo(row.get("avgCostBasis").asText());
            assertThat(holding.getCostBasisCurrency()).isEqualTo(row.get("costBasisCurrency").asText());
            assertThat(holding.getCostBasisSource()).isEqualTo(row.get("costBasisSource").asText());
            assertThat(holding.getCostBasisAsOf().toString()).isEqualTo(row.get("costBasisAsOf").asText());
        }
    }

    static volatile boolean responseReleased;
    static int port(ConfigurableApplicationContext context) { return ((WebServerApplicationContext) context).getWebServer().getPort(); }
    static Logger logger(Class<?> type) { return (Logger) LoggerFactory.getLogger(type); }
    record Wire(String path, String traceparent, String key, String originVerify) { }
    static class Capture extends AppenderBase<ILoggingEvent> {
        final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
        @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); events.add(event); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration")
    @ComponentScan(basePackages = {"com.wealth.portfolio", "com.wealth.user"})
    @ConfigurationPropertiesScan(basePackages = {"com.wealth.portfolio"})
    @EntityScan(basePackages = {"com.wealth.portfolio", "com.wealth.user"})
    @EnableJpaRepositories(basePackages = {"com.wealth.portfolio", "com.wealth.user"})
    static class PortfolioContext {
        @Bean io.opentelemetry.context.propagation.TextMapPropagator w3c() {
            return io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance();
        }
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }
        @Bean jakarta.servlet.Filter wireCapture() {
            return (request, response, chain) -> {
                var http = (jakarta.servlet.http.HttpServletRequest) request;
                wires.add(new Wire(http.getRequestURI(), http.getHeader("traceparent"),
                        http.getHeader("X-Internal-Api-Key"), http.getHeader("X-Origin-Verify")));
                chain.doFilter(request, response);
            };
        }
        @Bean static BeanPostProcessor responseDelay() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DemoResetService) && !(bean instanceof PortfolioService)) return bean;
                    var proxy = new ProxyFactory(bean);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        Object result = invocation.proceed(); // calls the real transactional proxy, including commit
                        if (invocation.getMethod().getName().equals("getByUserId")) Thread.sleep(30);
                        if (invocation.getMethod().getName().equals("reset")
                                && ("reset_timeout".equals(scenario) || "reset_in_flight".equals(scenario))) {
                            responseReleased = false;
                            try { Thread.sleep(4000); } finally { responseReleased = true; }
                        }
                        return result;
                    });
                    return proxy.getProxy();
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class,
            excludeName = {"org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration",
                    "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
    @Import({AuthController.class, DemoLoginResetConfiguration.class, GatewayLoopbackTargetProvider.class})
    static class GatewayContext {
        @Bean AuthenticationService authentication() {
            var authentication = mock(AuthenticationService.class);
            when(authentication.authenticate(any())).thenReturn(Mono.just(new LoginResponse("fresh-jwt", DEMO, "demo@example.com", "Demo")));
            return authentication;
        }
        @Bean SignupService signup() { return mock(SignupService.class); }
        @Bean InternalApiKeyProvider key() { return new InternalApiKeyProvider(KEY); }
        @Bean CloudFrontOriginSecretProvider origin() { return new CloudFrontOriginSecretProvider(""); }
        @Bean ReplicaTokenProvider replica() { return new ReplicaTokenProvider(""); }
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC); }
        @Bean LongSupplier demoLoginResetNanoClock() { return () -> ticks.getAndAdd(137_000_000L); }
        @Bean @Primary DemoLoginResetOrchestrator.CompletionHandler completion() {
            return result -> "reset_post_response".equals(scenario) ? Mono.never()
                    : "gateway_orchestration_error".equals(scenario) ? Mono.error(new IllegalStateException("test-handler-failure"))
                    : Mono.empty();
        }
        @Bean io.opentelemetry.context.propagation.TextMapPropagator w3c() {
            return io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance();
        }
        @Bean SecurityWebFilterChain security(ServerHttpSecurity http) {
            return http.csrf(ServerHttpSecurity.CsrfSpec::disable).authorizeExchange(a -> a.anyExchange().permitAll()).build();
        }
        @Bean org.springframework.cloud.gateway.filter.GlobalFilter authenticatedIdentityFixture() {
            return (exchange, chain) -> chain.filter(exchange.getRequest().getPath().value().equals("/api/portfolio")
                    ? exchange.mutate().request(request -> request.header("X-User-Id", DEMO)).build() : exchange);
        }
    }
}
