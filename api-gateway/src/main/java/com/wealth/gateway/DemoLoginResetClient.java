package com.wealth.gateway;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
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
        return observeEligibility(bearerToken, null);
    }

    Mono<DemoLoginPortfolioObservation> observeEligibility(String bearerToken, DemoLoginResetDiagnostics.Attempt attempt) {
        return Mono.defer(() -> ownedTarget(false, attempt).flatMap(target -> {
            AtomicReference<Boolean> headerAttached = new AtomicReference<>(false);
            boolean originRequired = originSecretProvider.isRequired();
            return observedClient(headerAttached, attempt, false, null)
                    .get()
                    .uri(target)
                    .headers(headers -> {
                        headers.setBearerAuth(bearerToken);
                        if (originRequired) {
                            headers.set(ORIGIN_VERIFY_HEADER, originSecretProvider.value());
                        }
                    })
                    .exchangeToMono(response -> {
                        if (attempt != null) attempt.received(false, response.statusCode().value());
                        return readEligiblePortfolio(response, target, originRequired, headerAttached.get());
                    })
                    .timeout(properties.eligibilityTimeout());
        }));
    }

    public Mono<DemoLoginResetResult> reset(DemoLoginPortfolioObservation observation) {
        return reset(observation, null);
    }

    Mono<DemoLoginResetResult> reset(DemoLoginPortfolioObservation observation, DemoLoginResetDiagnostics.Attempt attempt) {
        return Mono.defer(() -> {
            if (!internalApiKeyProvider.isConfigured()) {
                return Mono.error(new ResetKeyNotConfiguredException());
            }
            return ownedTarget(true, attempt).flatMap(target -> observedClient(new AtomicReference<>(false),
                            attempt, true, observation.version())
                    .post()
                    .uri(target)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(INTERNAL_API_KEY_HEADER, internalApiKeyProvider.value())
                    .bodyValue(new ResetRequest(observation.version()))
                    .exchangeToMono(response -> {
                        if (attempt != null) attempt.received(true, response.statusCode().value());
                        return requireSuccess(response, target)
                                .then(response.releaseBody())
                                .thenReturn(new DemoLoginResetResult(target, response.statusCode().value()));
                    })
                    .timeout(properties.resetTimeout()));
        });
    }

    private Mono<URI> ownedTarget(boolean reset, DemoLoginResetDiagnostics.Attempt attempt) {
        return Mono.defer(() -> reset ? loopbackTargetProvider.resetTarget() : loopbackTargetProvider.eligibilityTarget())
                .onErrorMap(error -> attempt == null ? error : new DemoLoginResetOrchestrator.OwnCodeFailure(error));
    }

    private WebClient observedClient(AtomicReference<Boolean> headerAttached,
                                     DemoLoginResetDiagnostics.Attempt attempt, boolean reset, Long version) {
        ExchangeFilterFunction observation = (request, next) -> {
            headerAttached.set(request.headers().getFirst(ORIGIN_VERIFY_HEADER) != null);
            if (attempt != null) attempt.dispatched(reset, request, version);
            return next.exchange(request);
        };
        return webClientBuilder.clone().filter(observation).build();
    }

    private Mono<DemoLoginPortfolioObservation> readEligiblePortfolio(ClientResponse response, URI target,
                                                                       boolean originRequired,
                                                                       boolean originAttached) {
        return requireSuccess(response, target)
                // A received successful response whose body cannot be consumed is an
                // eligibility shape failure, regardless of the decoder/publisher exception type.
                // Keep this boundary inside the call deadline and outside our own idle handling.
                .then(Mono.defer(() -> response.bodyToMono(String.class)
                        .switchIfEmpty(Mono.error(new EligibilityShapeException(0)))
                        .flatMap(this::decodePortfolioArray))
                        .onErrorMap(error -> error instanceof EligibilityShapeException
                                ? error : new EligibilityShapeException(0)))
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

    private Mono<Void> requireSuccess(ClientResponse response, URI target) {
        if (response.statusCode().is2xxSuccessful()) {
            return Mono.empty();
        }
        if (response.statusCode().value() == 409) {
            return response.bodyToMono(String.class).defaultIfEmpty("").onErrorReturn("")
                    .flatMap(body -> Mono.error(new HttpStatusFailure(response.statusCode(), target, currentVersion(body))));
        }
        return response.releaseBody().then(Mono.error(new HttpStatusFailure(response.statusCode(), target, null)));
    }

    private Long currentVersion(String body) {
        try {
            JsonNode version = objectMapper.readTree(body).path("currentVersion");
            return version.isIntegralNumber() && version.canConvertToLong() ? version.longValue() : null;
        } catch (Exception ignored) {
            return null;
        }
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
        private final Long currentVersion;

        HttpStatusFailure(HttpStatusCode status, URI target, Long currentVersion) {
            super("self-call returned " + status.value());
            this.status = status;
            this.target = target;
            this.currentVersion = currentVersion;
        }

        public HttpStatusCode status() {
            return status;
        }

        public URI target() {
            return target;
        }

        public Long currentVersion() {
            return currentVersion;
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
