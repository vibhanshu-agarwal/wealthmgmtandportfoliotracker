package com.wealth.gateway;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Nonblocking transport only; the orchestrator owns fail-open and timeout classification. */
@Component
public final class DemoLoginResetClient {
    static final String DEMO_USER_ID = "00000000-0000-0000-0000-0000000d3110";
    private static final String ORIGIN_VERIFY_HEADER = "X-Origin-Verify";
    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final WebClient.Builder webClientBuilder;
    private final GatewayLoopbackTargetProvider loopbackTargetProvider;
    private final InternalApiKeyProvider internalApiKeyProvider;
    private final CloudFrontOriginSecretProvider originSecretProvider;
    private final DemoLoginResetProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public DemoLoginResetClient(WebClient.Builder webClientBuilder,
                                GatewayLoopbackTargetProvider loopbackTargetProvider,
                                InternalApiKeyProvider internalApiKeyProvider,
                                CloudFrontOriginSecretProvider originSecretProvider,
                                DemoLoginResetProperties properties,
                                Clock clock,
                                ObjectMapper objectMapper) {
        this.webClientBuilder = webClientBuilder;
        this.loopbackTargetProvider = loopbackTargetProvider;
        this.internalApiKeyProvider = internalApiKeyProvider;
        this.originSecretProvider = originSecretProvider;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public Mono<DemoLoginPortfolioObservation> observeEligibility(String bearerToken) {
        return Mono.defer(() -> loopbackTargetProvider.eligibilityTarget().flatMap(target -> {
            AtomicReference<Boolean> headerAttached = new AtomicReference<>(false);
            boolean originRequired = originSecretProvider.isRequired();
            return observedClient(headerAttached)
                    .get()
                    .uri(target)
                    .headers(headers -> {
                        headers.setBearerAuth(bearerToken);
                        if (originRequired) {
                            headers.set(ORIGIN_VERIFY_HEADER, originSecretProvider.value());
                        }
                    })
                    .exchangeToMono(response -> readEligiblePortfolio(response, target, originRequired,
                            headerAttached.get()))
                    .timeout(properties.eligibilityTimeout());
        }));
    }

    public Mono<DemoLoginResetResult> reset(DemoLoginPortfolioObservation observation) {
        return Mono.defer(() -> {
            if (!internalApiKeyProvider.isConfigured()) {
                return Mono.error(new ResetKeyNotConfiguredException());
            }
            return loopbackTargetProvider.resetTarget().flatMap(target -> observedClient(new AtomicReference<>(false))
                    .post()
                    .uri(target)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(INTERNAL_API_KEY_HEADER, internalApiKeyProvider.value())
                    .bodyValue(new ResetRequest(observation.version()))
                    .exchangeToMono(response -> requireSuccess(response, target)
                            .thenReturn(new DemoLoginResetResult(target, response.statusCode().value())))
                    .timeout(properties.resetTimeout()));
        });
    }

    private WebClient observedClient(AtomicReference<Boolean> headerAttached) {
        ExchangeFilterFunction observation = (request, next) -> {
            headerAttached.set(request.headers().getFirst(ORIGIN_VERIFY_HEADER) != null);
            return next.exchange(request);
        };
        return webClientBuilder.clone().filter(observation).build();
    }

    private Mono<DemoLoginPortfolioObservation> readEligiblePortfolio(ClientResponse response, URI target,
                                                                       boolean originRequired,
                                                                       boolean originAttached) {
        return requireSuccess(response, target)
                .then(response.bodyToMono(String.class)
                        .switchIfEmpty(Mono.error(new EligibilityShapeException(0)))
                        .flatMap(this::decodePortfolioArray))
                .flatMap(portfolios -> selectDemoPortfolio(portfolios, target, originRequired, originAttached));
    }

    private Mono<List<PortfolioPayload>> decodePortfolioArray(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isArray()) {
                return Mono.error(new EligibilityShapeException(0));
            }
            List<PortfolioPayload> portfolios = new java.util.ArrayList<>();
            for (JsonNode node : root) {
                if (!node.isObject()) {
                    return Mono.error(new EligibilityShapeException(0));
                }
                JsonNode userId = node.path("userId");
                if (!userId.isTextual()) {
                    return Mono.error(new EligibilityShapeException(0));
                }
                if (!DEMO_USER_ID.equals(userId.asText())) {
                    continue;
                }
                JsonNode id = node.path("id");
                JsonNode updatedAt = node.path("updatedAt");
                JsonNode version = node.path("version");
                if (!id.isTextual() || !updatedAt.isTextual() || !version.isIntegralNumber()
                        || !version.canConvertToLong()) {
                    return Mono.error(new EligibilityShapeException(0));
                }
                portfolios.add(new PortfolioPayload(UUID.fromString(id.asText()), userId.asText(),
                        Instant.parse(updatedAt.asText()), version.longValue()));
            }
            return Mono.just(portfolios);
        } catch (Exception exception) {
            return Mono.error(new EligibilityShapeException(0));
        }
    }

    private Mono<DemoLoginPortfolioObservation> selectDemoPortfolio(List<PortfolioPayload> portfolios, URI target,
                                                                      boolean originRequired, boolean originAttached) {
        List<PortfolioPayload> matches = portfolios.stream()
                .filter(portfolio -> DEMO_USER_ID.equals(portfolio.userId()))
                .toList();
        if (matches.size() != 1) {
            return Mono.error(new EligibilityShapeException(matches.size()));
        }
        PortfolioPayload match = matches.getFirst();
        if (match.id() == null || match.updatedAt() == null || match.version() == null || match.version() < 0) {
            return Mono.error(new EligibilityShapeException(matches.size()));
        }
        Duration age = Duration.between(match.updatedAt(), Instant.now(clock));
        return Mono.just(new DemoLoginPortfolioObservation(match.id(), match.userId(), match.updatedAt(), match.version(),
                age.compareTo(properties.idleThreshold()) > 0, target, originRequired, originAttached));
    }

    private static Mono<Void> requireSuccess(ClientResponse response, URI target) {
        if (response.statusCode().is2xxSuccessful()) {
            return Mono.empty();
        }
        return response.releaseBody().then(Mono.error(new HttpStatusFailure(response.statusCode(), target)));
    }

    private record PortfolioPayload(UUID id, String userId, Instant updatedAt, Long version) {
    }

    private record ResetRequest(long expectedVersion) {
    }

    public record DemoLoginResetResult(URI target, int status) {
    }

    public static final class HttpStatusFailure extends RuntimeException {
        private final HttpStatusCode status;
        private final URI target;

        HttpStatusFailure(HttpStatusCode status, URI target) {
            super("self-call returned " + status.value());
            this.status = status;
            this.target = target;
        }

        public HttpStatusCode status() {
            return status;
        }

        public URI target() {
            return target;
        }
    }

    public static final class EligibilityShapeException extends RuntimeException {
        private final int matchingPortfolios;

        EligibilityShapeException(int matchingPortfolios) {
            super("expected one demo portfolio");
            this.matchingPortfolios = matchingPortfolios;
        }

        public int matchingPortfolios() {
            return matchingPortfolios;
        }
    }

    public static final class ResetKeyNotConfiguredException extends RuntimeException {
        ResetKeyNotConfiguredException() {
            super("internal API key is not configured");
        }
    }
}
