package com.wealth.gateway;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;

/** Resolves the actual WebFlux port per subscription, after RANDOM_PORT has bound it. */
@Component
public final class GatewayLoopbackTargetProvider {
    private final Environment environment;

    public GatewayLoopbackTargetProvider(Environment environment) {
        this.environment = environment;
    }

    public Mono<URI> eligibilityTarget() {
        return target("/api/portfolio");
    }

    public Mono<URI> resetTarget() {
        return target("/api/internal/portfolio/demo-reset");
    }

    private Mono<URI> target(String path) {
        return Mono.defer(() -> Mono.fromSupplier(() -> {
            String value = environment.getProperty("local.server.port");
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("local.server.port is unavailable");
            }
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new IllegalStateException("local.server.port is invalid");
            }
            return URI.create("http://localhost:" + port + path);
        }));
    }
}
