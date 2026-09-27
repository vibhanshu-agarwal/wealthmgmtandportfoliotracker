package com.wealth.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ResolvableType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
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
 * portfolio disclosed whether one existed. The route was removed first; the service behind it,
 * which held the only portfolio-service client, was deleted afterwards.
 *
 * <p>The requests go to insight-service directly, and nothing on this path reads {@code X-User-Id},
 * so the three caller classes exercise the same code. The loop records the intent that no caller,
 * including one with no identity or the read-only showcase, gets an exception; the 404 for all of
 * them is what makes a missing identity fail closed. Gateway authentication is not exercised here.
 *
 * <p>The structural tests cover two ways the leak could come back: an insights path variable whose
 * name contains "user", and any insight-service bean holding an outbound HTTP client, which is
 * what forwarding a caller-chosen user to portfolio-service needs. The client check is deliberately
 * broad: a future legitimate client must be added to it knowingly, with its own authorization
 * argument. It inspects the declared fields of every instantiated singleton, unwrapping proxies and
 * generic wrappers. It cannot see a client built inside a method, a non-Spring HTTP library, a
 * prototype-scoped bean, or a bean that exists only under another profile (it runs under
 * {@code default}); the guard targets accidental re-exposure, not a deliberate workaround. A
 * test-registered control bean proves the walk finds a holder.
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
@Import(AdvisorAnalyzeRemovalIT.WalkControl.class)
class AdvisorAnalyzeRemovalIT {

    private static final String SHOWCASE_USER = "00000000-0000-0000-0000-0000000d3110";
    private static final String E2E_USER = "00000000-0000-0000-0000-000000000e2e";

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

    /**
     * Outbound HTTP client types. WebClient is matched by name because this servlet service does
     * not declare WebFlux; it counts whenever something puts it on the classpath.
     */
    private static final List<Class<?>> HTTP_CLIENT_TYPES = httpClientTypes();

    private static final String CONTROL_BEAN = "advisorRemovalControlClientHolder";

    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(TestContainerImages.REDIS).withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
    }

    @LocalServerPort int port;

    @Autowired ApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void noCallerCanReachAnyUsersAnalysisThroughTheAdvisorPath() throws Exception {
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

        // Soft, so a regression reports every effect at once.
        assertSoftly(softly -> {
            softly.assertThat(answered).as("advisor requests that were answered").isEmpty();
            softly.assertThat(getNot404)
                    .as("GET must be a plain 404 for every caller and target")
                    .isEmpty();
        });
    }

    @Test
    void noInsightsHandlerTakesAUserFromThePath() {
        Map<RequestMappingInfo, HandlerMethod> insightMappings = new LinkedHashMap<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (info.getPatternValues().stream().anyMatch(p -> p.startsWith("/api/insights"))) {
                insightMappings.put(info, handler);
            }
        });

        assertThat(insightMappings).as("the public insight routes must still be mapped").isNotEmpty();

        assertSoftly(softly -> insightMappings.forEach((info, handler) -> softly
                .assertThat(info.getPatternValues())
                .as("%s must not take a user from the path; use the gateway-injected identity", info)
                .noneMatch(p -> USER_PATH_VARIABLE.matcher(p).find())));
    }

    @Test
    void noInsightServiceBeanHoldsAnOutboundHttpClient() {
        // Walks the singleton instances, not bean definitions, so a JDK or CGLIB proxy is unwrapped
        // to its target class rather than skipped for not being a com.wealth type.
        Map<String, List<String>> holders = new LinkedHashMap<>();
        int inspected = 0;
        for (var bean : context.getBeansOfType(Object.class, false, true).entrySet()) {
            Class<?> target = AopProxyUtils.ultimateTargetClass(bean.getValue());
            if (!target.getName().startsWith("com.wealth.")) {
                continue;
            }
            inspected++;
            List<String> clients = httpClientFields(target);
            if (!clients.isEmpty()) {
                holders.put(bean.getKey(), clients);
            }
        }

        // Control: the test-registered holder must be found by the same walk, or an empty result
        // below proves nothing. It is the only holder allowed.
        assertThat(holders.remove(CONTROL_BEAN))
                .as("the walk must find the control bean's RestClient and Supplier<RestClient> fields")
                .hasSize(2);
        assertThat(inspected).as("insight-service beans inspected besides the control").isGreaterThan(1);
        assertThat(holders)
                .as("insight-service beans holding an outbound HTTP client; one could forward a "
                        + "caller-chosen X-User-Id to portfolio-service")
                .isEmpty();
    }

    private static List<String> httpClientFields(Class<?> type) {
        List<String> found = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (holdsHttpClient(ResolvableType.forField(field), 0)) {
                    found.add(c.getSimpleName() + "." + field.getName() + ": " + field.getGenericType().getTypeName());
                }
            }
        }
        return found;
    }

    /**
     * True for a client type, or for any type whose generics or array component carry one, such as
     * {@code ObjectProvider<RestClient>}, {@code Supplier<RestClient>} or {@code Map<String, RestClient>}.
     */
    private static boolean holdsHttpClient(ResolvableType type, int depth) {
        Class<?> raw = type.resolve();
        if (raw != null && HTTP_CLIENT_TYPES.stream().anyMatch(client -> client.isAssignableFrom(raw))) {
            return true;
        }
        if (depth >= 4) {
            return false;
        }
        if (type.isArray() && holdsHttpClient(type.getComponentType(), depth + 1)) {
            return true;
        }
        for (ResolvableType generic : type.getGenerics()) {
            if (holdsHttpClient(generic, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static List<Class<?>> httpClientTypes() {
        List<Class<?>> types = new ArrayList<>(List.of(
                RestClient.class, RestClient.Builder.class, RestTemplate.class, HttpClient.class));
        ClassLoader loader = AdvisorAnalyzeRemovalIT.class.getClassLoader();
        for (String name : List.of(
                "org.springframework.web.reactive.function.client.WebClient",
                "org.springframework.web.reactive.function.client.WebClient$Builder")) {
            if (ClassUtils.isPresent(name, loader)) {
                types.add(ClassUtils.resolveClassName(name, loader));
            }
        }
        return List.copyOf(types);
    }

    /** Shape of the deleted service: a bean holding a portfolio-service client, directly and wrapped. */
    @SuppressWarnings("unused")
    static final class ControlClientHolder {
        private RestClient portfolioClient;
        private Supplier<RestClient> lazyPortfolioClient;
    }

    /** Registers the control holder as a real bean, so the walk itself is exercised, not just the matcher. */
    @TestConfiguration(proxyBeanMethods = false)
    static class WalkControl {
        @Bean(CONTROL_BEAN)
        ControlClientHolder advisorRemovalControlClientHolder() {
            return new ControlClientHolder();
        }
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
}
