package com.wealth.gateway;

import com.wealth.gateway.auth.LoginResponse;
import reactor.core.publisher.Mono;

import java.util.function.LongSupplier;

/** Sequential, bounded, per-login work; a failed optional reset never changes authentication. */
public final class DemoLoginResetOrchestrator {
    private final DemoLoginResetClient client;
    private final DemoLoginResetProperties properties;
    private final InternalApiKeyProvider keyProvider;
    private final CloudFrontOriginSecretProvider originProvider;
    private final DemoLoginResetDiagnostics diagnostics;
    private final LongSupplier nanoClock;
    private final CompletionHandler completionHandler;

    public DemoLoginResetOrchestrator(DemoLoginResetClient client, DemoLoginResetProperties properties,
                                     InternalApiKeyProvider keyProvider, CloudFrontOriginSecretProvider originProvider,
                                     DemoLoginResetDiagnostics diagnostics, LongSupplier nanoClock,
                                     CompletionHandler completionHandler) {
        this.client = client;
        this.properties = properties;
        this.keyProvider = keyProvider;
        this.originProvider = originProvider;
        this.diagnostics = diagnostics;
        this.nanoClock = nanoClock;
        this.completionHandler = completionHandler;
    }

    public Mono<Void> afterLogin(LoginResponse response) {
        return Mono.defer(() -> {
            if (!DemoLoginResetClient.DEMO_USER_ID.equals(response.userId())) return Mono.empty();
            var attempt = new DemoLoginResetDiagnostics.Attempt(keyProvider.isConfigured(), originProvider.isRequired(), nanoClock);
            return Mono.defer(() -> client.observeEligibility(response.token(), attempt)
                            .flatMap(observation -> {
                                attempt.observed(observation);
                                if (!observation.isIdleEligible()) return Mono.empty();
                                return Mono.defer(() -> client.reset(observation, attempt))
                                        .flatMap(result -> Mono.defer(() -> completionHandler.complete(result))
                                                .onErrorMap(OwnCodeFailure::new));
                            }))
                    .timeout(properties.overallTimeout(), Mono.error(new OverallDeadline()))
                    .onErrorResume(error -> {
                        diagnostics.skipped(attempt, error);
                        return Mono.empty();
                    });
        });
    }

    /** Gateway-owned completion boundary, still cancellable under the overall deadline. */
    @FunctionalInterface
    public interface CompletionHandler {
        Mono<Void> complete(DemoLoginResetClient.DemoLoginResetResult result);
    }

    static final class OverallDeadline extends RuntimeException { }

    /** Carries provenance across reactive boundaries without exposing the exception message. */
    static final class OwnCodeFailure extends RuntimeException {
        OwnCodeFailure(Throwable cause) { super(cause); }
    }
}
