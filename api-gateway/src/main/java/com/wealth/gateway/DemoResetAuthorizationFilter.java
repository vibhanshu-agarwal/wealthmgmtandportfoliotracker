package com.wealth.gateway;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.nio.charset.StandardCharsets;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Bridges the public manual demo-reset request onto the internal, API-key-gated reset endpoint
 * (B2 Tasks 5.1, 5.3a, 5.5).
 *
 * <p>Scope is deliberately a single exact pair — {@code PUT /api/portfolio/demo-reset}, matched on
 * the ORIGINAL request path, before the {@code demo-reset-manual} route's {@code RewritePath}
 * runs. Every other request leaves this filter exactly as it arrived: no internal key, no replica
 * token, no header stripping. This is an authorization gate for one endpoint, not a general
 * credential-exchange mechanism.
 *
 * <p>Authorization is deliberately double-keyed. The JWT subject must be the demo account, AND the
 * route Spring Cloud Gateway actually selected must be {@code demo-reset-manual}. The second check
 * reads {@link org.springframework.cloud.gateway.support.ServerWebExchangeUtils#GATEWAY_ROUTE_ATTR}
 * — the route that WON — never {@code GATEWAY_PREDICATE_ROUTE_ATTR}, which is set per candidate
 * during predicate evaluation and therefore reflects routes that were merely tried. Both checks
 * fail closed with the same opaque 403: a caller learns nothing about routing or configuration.
 *
 * <p>Ordered at {@link Ordered#HIGHEST_PRECEDENCE} + 4, immediately after
 * {@link ReadOnlyEnforcementFilter} (+3) — whose B2 exception for this exact method/path lets the
 * read-only demo account through in the first place — and after {@link JwtAuthenticationFilter}
 * (+2), so the validated principal is already on the exchange.
 */
@Component
public class DemoResetAuthorizationFilter implements GlobalFilter, Ordered {

    /**
     * The showcase demo account (V15__Reconcile_Auth_Seed_Users.sql). Deliberately duplicated
     * rather than imported: api-gateway must not depend on portfolio-service. {@code
     * scripts/check_b2_demo_identity.py} runs in the required static-guard CI job and fails the
     * build if this literal, portfolio-service's {@code DemoPortfolioInitializer.DEMO_USER_ID},
     * and V15's demo users row ever drift apart.
     */
    static final String DEMO_USER_ID = "00000000-0000-0000-0000-0000000d3110";

    /** Original (pre-RewritePath) public path this filter guards. */
    private static final String RESET_PATH = "/api/portfolio/demo-reset";

    /** Id of the route that must have won for a reset to be authorized. */
    private static final String RESET_ROUTE_ID = "demo-reset-manual";

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";
    private static final String REPLICA_TOKEN_HEADER = "X-Gateway-Replica-Token";
    private static final String USER_ID_HEADER = "X-User-Id";

    private static final byte[] FORBIDDEN_BODY =
            ("{\"error\":\"demo_reset_forbidden\","
                            + "\"message\":\"Only the demo account may reset the demo portfolio.\"}")
                    .getBytes(StandardCharsets.UTF_8);

    /**
     * Two fields, not one. Wave 8 diagnostics distinguish "the gateway itself has no internal key"
     * from an upstream 503, so this envelope must stay distinguishable from a passed-through
     * downstream failure — which is forwarded verbatim and never normalised into this shape.
     */
    private static final byte[] UNAVAILABLE_BODY =
            ("{\"error\":\"internal_api_key_not_configured\","
                            + "\"message\":\"The demo reset feature is temporarily unavailable.\"}")
                    .getBytes(StandardCharsets.UTF_8);

    private final InternalApiKeyProvider internalApiKeyProvider;
    private final ReplicaTokenProvider replicaTokenProvider;

    public DemoResetAuthorizationFilter(
            InternalApiKeyProvider internalApiKeyProvider,
            ReplicaTokenProvider replicaTokenProvider) {
        this.internalApiKeyProvider = internalApiKeyProvider;
        this.replicaTokenProvider = replicaTokenProvider;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 4;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!isManualResetRequest(exchange)) {
            return chain.filter(exchange);
        }

        // From here on every outcome is this filter's, so it owns the replica-token header on all
        // of them. Registered up front, and applied at commit rather than now, because
        // NettyRoutingFilter copies the downstream response's headers onto this response AFTER
        // this filter returns — an early set() would simply be appended to and lose.
        ownReplicaTokenHeader(exchange);

        if (!wonTheResetRoute(exchange)) {
            return writeJson(exchange, HttpStatus.FORBIDDEN, FORBIDDEN_BODY);
        }

        // Resolve authorization to a Boolean *before* branching into Mono<Void>. Composing the
        // continuations directly (switchIfEmpty/then around chain.filter(...)) would re-subscribe
        // the downstream chain, because Mono<Void> always completes empty — the same pitfall
        // JwtAuthenticationFilter and ReadOnlyEnforcementFilter already document.
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(token -> DEMO_USER_ID.equals(token.getToken().getSubject()))
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(
                        authorized -> {
                            if (!authorized) {
                                return writeJson(exchange, HttpStatus.FORBIDDEN, FORBIDDEN_BODY);
                            }
                            if (!internalApiKeyProvider.isConfigured()) {
                                return writeJson(
                                        exchange,
                                        HttpStatus.SERVICE_UNAVAILABLE,
                                        UNAVAILABLE_BODY);
                            }
                            return chain.filter(withInternalCredentials(exchange));
                        });
    }

    /** Exact method/path pair only — no prefix, no trailing-slash or child-path tolerance. */
    private static boolean isManualResetRequest(ServerWebExchange exchange) {
        return HttpMethod.PUT.equals(exchange.getRequest().getMethod())
                && RESET_PATH.equals(exchange.getRequest().getURI().getPath());
    }

    private static boolean wonTheResetRoute(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        return route != null && RESET_ROUTE_ID.equals(route.getId());
    }

    /**
     * Makes the gateway's replica token authoritative at response commit, replacing every value a
     * downstream response may have contributed. Always the provider's opaque token — the raw
     * replica name is never emitted, and the provider's empty sentinel is forwarded as-is rather
     * than being substituted for something else.
     */
    private void ownReplicaTokenHeader(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.beforeCommit(
                () -> {
                    response.getHeaders()
                            .set(REPLICA_TOKEN_HEADER, replicaTokenProvider.replicaToken());
                    return Mono.empty();
                });
    }

    /**
     * Swaps the end-user credential set for the single trusted internal one. Caller-supplied
     * {@code X-Internal-Api-Key} values are replaced (not appended to), so a client cannot smuggle
     * an extra candidate key past the downstream check, and the request body — including
     * {@code expectedVersion} — is left untouched.
     */
    private ServerWebExchange withInternalCredentials(ServerWebExchange exchange) {
        String internalApiKey = internalApiKeyProvider.value();
        return exchange.mutate()
                .request(
                        request ->
                                request.headers(
                                        headers -> {
                                            headers.remove(HttpHeaders.AUTHORIZATION);
                                            headers.remove(USER_ID_HEADER);
                                            headers.set(INTERNAL_API_KEY_HEADER, internalApiKey);
                                        }))
                .build();
    }

    private static Mono<Void> writeJson(
            ServerWebExchange exchange, HttpStatus status, byte[] body) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
