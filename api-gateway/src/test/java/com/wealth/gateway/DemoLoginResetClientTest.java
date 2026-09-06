package com.wealth.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

class DemoLoginResetClientTest {

    @Test
    void eligibilitySelectsOnlyTheCompiledInDemoPortfolioAndPreservesItsObservation() {
        ExchangeFunction exchange = request -> Mono.just(jsonResponse("""
                [{"id":"00000000-0000-0000-0000-000000000001","userId":"other","createdAt":"2026-09-06T00:00:00Z","updatedAt":"2026-09-06T00:00:00Z","version":4,"holdings":[]},
                 {"id":"00000000-0000-0000-0000-000000000002","userId":"00000000-0000-0000-0000-0000000d3110","createdAt":"2026-09-06T00:00:00Z","updatedAt":"2026-09-06T00:00:00Z","version":7,"holdings":[]}]
                """));

        DemoLoginResetClient client = new DemoLoginResetClient(
                WebClient.builder().exchangeFunction(exchange), loopbackPort(18321),
                new InternalApiKeyProvider("internal"), new CloudFrontOriginSecretProvider("origin"),
                new DemoLoginResetProperties(Duration.ofMinutes(30), Duration.ofSeconds(2),
                        Duration.ofSeconds(2), Duration.ofSeconds(4)),
                Clock.fixed(Instant.parse("2026-09-06T00:31:00Z"), ZoneOffset.UTC));

        StepVerifier.create(client.observeEligibility("jwt"))
                .assertNext(observation -> {
                    assertThat(observation.userId()).isEqualTo("00000000-0000-0000-0000-0000000d3110");
                    assertThat(observation.version()).isEqualTo(7L);
                    assertThat(observation.updatedAt()).isEqualTo(Instant.parse("2026-09-06T00:00:00Z"));
                    assertThat(observation.isIdleEligible()).isTrue();
                    assertThat(observation.originVerifyRequired()).isTrue();
                    assertThat(observation.originVerifyAttached()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void eligibilityAtExactlyThirtyMinutesIsNotEligibleAndSendsTheFinalizedBearerAndOriginHeaders() {
        List<org.springframework.web.reactive.function.client.ClientRequest> requests = new ArrayList<>();
        ExchangeFunction exchange = request -> {
            requests.add(request);
            return Mono.just(jsonResponse("""
                    [{"id":"00000000-0000-0000-0000-000000000002","userId":"00000000-0000-0000-0000-0000000d3110","updatedAt":"2026-09-06T00:01:00Z","version":7}]
                    """));
        };
        DemoLoginResetClient client = client(exchange, "origin", "internal");

        StepVerifier.create(client.observeEligibility("minted-token"))
                .assertNext(observation -> assertThat(observation.isIdleEligible()).isFalse())
                .verifyComplete();

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method().name()).isEqualTo("GET");
            assertThat(request.url()).isEqualTo(URI.create("http://localhost:18321/api/portfolio"));
            assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer minted-token");
            assertThat(request.headers().getFirst("X-Origin-Verify")).isEqualTo("origin");
        });
    }

    @Test
    void eligibilityRejectsZeroOrMultipleDemoPortfoliosInsteadOfSelectingAnArbitraryRow() {
        ExchangeFunction exchange = request -> Mono.just(jsonResponse("[]"));
        DemoLoginResetClient client = client(exchange, "", "internal");

        StepVerifier.create(client.observeEligibility("jwt"))
                .expectError(DemoLoginResetClient.EligibilityShapeException.class)
                .verify();
    }

    @Test
    void eligibilityRejectsAMissingVersionInsteadOfSilentlyTreatingItAsZero() {
        ExchangeFunction exchange = request -> Mono.just(jsonResponse("""
                [{"id":"00000000-0000-0000-0000-000000000002","userId":"00000000-0000-0000-0000-0000000d3110","updatedAt":"2026-09-06T00:00:00Z"}]
                """));
        DemoLoginResetClient client = client(exchange, "", "internal");

        StepVerifier.create(client.observeEligibility("jwt"))
                .expectError(DemoLoginResetClient.EligibilityShapeException.class)
                .verify();
    }

    @Test
    void eligibilityRejectsMultipleDemoPortfoliosAndFutureUpdatedAtIsIneligible() {
        ExchangeFunction multiple = request -> Mono.just(jsonResponse("""
                [{"id":"00000000-0000-0000-0000-000000000001","userId":"00000000-0000-0000-0000-0000000d3110","updatedAt":"2026-09-06T00:00:00Z","version":1},
                 {"id":"00000000-0000-0000-0000-000000000002","userId":"00000000-0000-0000-0000-0000000d3110","updatedAt":"2026-09-06T00:00:00Z","version":2}]
                """));
        StepVerifier.create(client(multiple, "", "internal").observeEligibility("jwt"))
                .expectError(DemoLoginResetClient.EligibilityShapeException.class).verify();

        ExchangeFunction future = request -> Mono.just(jsonResponse("""
                [{"id":"00000000-0000-0000-0000-000000000002","userId":"00000000-0000-0000-0000-0000000d3110","updatedAt":"2026-09-06T00:32:00Z","version":1}]
                """));
        StepVerifier.create(client(future, "", "internal").observeEligibility("jwt"))
                .assertNext(observation -> {
                    assertThat(observation.isIdleEligible()).isFalse();
                    assertThat(observation.originVerifyRequired()).isFalse();
                    assertThat(observation.originVerifyAttached()).isFalse();
                }).verifyComplete();
    }

    @Test
    void everyNon2xxAndConnectionFailureRemainTransportErrorsForTheFailOpenLayer() {
        for (int status : List.of(302, 403, 429, 500)) {
            ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(org.springframework.http.HttpStatusCode.valueOf(status)).build());
            StepVerifier.create(client(exchange, "", "internal").observeEligibility("jwt"))
                    .expectError(DemoLoginResetClient.HttpStatusFailure.class).verify();
        }
        StepVerifier.create(client(request -> Mono.error(new java.io.IOException("down")), "", "internal")
                        .observeEligibility("jwt"))
                .expectError(java.io.IOException.class).verify();
    }

    @Test
    void perLegTimeoutsAreBoundedByTheirSeparateApprovedDurations() {
        DemoLoginResetProperties shortTimeouts = new DemoLoginResetProperties(Duration.ofMinutes(30),
                Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofSeconds(4));
        DemoLoginResetClient client = new DemoLoginResetClient(WebClient.builder().exchangeFunction(request -> Mono.never()),
                loopbackPort(18321), new InternalApiKeyProvider("internal"), new CloudFrontOriginSecretProvider(""),
                shortTimeouts, Clock.fixed(Instant.parse("2026-09-06T00:31:00Z"), ZoneOffset.UTC));
        StepVerifier.create(client.observeEligibility("jwt")).expectError(TimeoutException.class).verify();
        StepVerifier.create(client.reset(new DemoLoginPortfolioObservation(java.util.UUID.randomUUID(),
                        DemoLoginResetClient.DEMO_USER_ID, Instant.EPOCH, 1, true,
                        URI.create("http://localhost:18321/api/portfolio"), false, false)))
                .expectError(TimeoutException.class).verify();
    }

    @Test
    void loopbackPortIsReadAtSubscriptionRatherThanAtClientConstruction() {
        Map<String, Object> properties = new java.util.HashMap<>();
        properties.put("local.server.port", "18321");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        List<org.springframework.web.reactive.function.client.ClientRequest> requests = new ArrayList<>();
        DemoLoginResetClient client = new DemoLoginResetClient(WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(jsonResponse("[]"));
        }), new GatewayLoopbackTargetProvider(environment), new InternalApiKeyProvider("internal"),
                new CloudFrontOriginSecretProvider(""), new DemoLoginResetProperties(Duration.ofMinutes(30),
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(4)), Clock.systemUTC());
        properties.put("local.server.port", "19432");
        StepVerifier.create(client.observeEligibility("jwt")).expectError(DemoLoginResetClient.EligibilityShapeException.class).verify();
        assertThat(requests).singleElement().extracting(org.springframework.web.reactive.function.client.ClientRequest::url)
                .isEqualTo(URI.create("http://localhost:19432/api/portfolio"));
    }

    @Test
    void resetPostsTheExactObservedVersionOnceAndNeverAttachesOriginVerification() {
        List<org.springframework.web.reactive.function.client.ClientRequest> requests = new ArrayList<>();
        ExchangeFunction exchange = request -> {
            requests.add(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        DemoLoginResetClient client = client(exchange, "origin", "internal");
        DemoLoginPortfolioObservation observation = new DemoLoginPortfolioObservation(
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "00000000-0000-0000-0000-0000000d3110", Instant.parse("2026-09-06T00:00:00Z"), 41L,
                true, URI.create("http://localhost:18321/api/portfolio"), true, true);

        StepVerifier.create(client.reset(observation))
                .assertNext(result -> assertThat(result.status()).isEqualTo(200))
                .verifyComplete();

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method().name()).isEqualTo("POST");
            assertThat(request.url()).isEqualTo(URI.create("http://localhost:18321/api/internal/portfolio/demo-reset"));
            assertThat(request.headers().getFirst("X-Internal-Api-Key")).isEqualTo("internal");
            assertThat(request.headers().getFirst("X-Origin-Verify")).isNull();
        });
    }

    @Test
    void resetSendsTheExactObservedVersionInOneOnWirePost() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        DisposableServer server = HttpServer.create().port(0).handle((request, response) ->
                request.receive().aggregate().asString().doOnNext(body -> {
                    calls.incrementAndGet();
                    requestBody.set(body);
                }).then(response.status(200).send())).bindNow();
        try {
            DemoLoginResetClient client = new DemoLoginResetClient(WebClient.builder(), loopbackPort(server.port()),
                    new InternalApiKeyProvider("internal"), new CloudFrontOriginSecretProvider(""),
                    new DemoLoginResetProperties(Duration.ofMinutes(30), Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(4)),
                    Clock.systemUTC());
            StepVerifier.create(client.reset(observation(41))).expectNextCount(1).verifyComplete();
            assertThat(calls).hasValue(1);
            assertThat(requestBody).hasValue("{\"expectedVersion\":41}");
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void resetDoesNotRetryNon2xxOrConnectionFailures() {
        for (int status : List.of(302, 403, 429, 500)) {
            AtomicInteger calls = new AtomicInteger();
            StepVerifier.create(client(request -> {
                        calls.incrementAndGet();
                        return Mono.just(ClientResponse.create(org.springframework.http.HttpStatusCode.valueOf(status)).build());
                    }, "", "internal").reset(observation(17)))
                    .expectError(DemoLoginResetClient.HttpStatusFailure.class).verify();
            assertThat(calls).hasValue(1);
        }
        AtomicInteger calls = new AtomicInteger();
        StepVerifier.create(client(request -> { calls.incrementAndGet(); return Mono.error(new java.io.IOException("down")); }, "", "internal").reset(observation(17)))
                .expectError(java.io.IOException.class).verify();
        assertThat(calls).hasValue(1);
    }

    @Test
    void eligibilityRejectsMalformedTimestampAndNegativeVersion() {
        StepVerifier.create(client(request -> Mono.just(jsonResponse("""
                [{"id":"00000000-0000-0000-0000-000000000002","userId":"00000000-0000-0000-0000-0000000d3110","updatedAt":"not-an-instant","version":1}]
                """)), "", "internal").observeEligibility("jwt")).expectError().verify();
        StepVerifier.create(client(request -> Mono.just(jsonResponse("""
                [{"id":"00000000-0000-0000-0000-000000000002","userId":"00000000-0000-0000-0000-0000000d3110","updatedAt":"2026-09-06T00:00:00Z","version":-1}]
                """)), "", "internal").observeEligibility("jwt"))
                .expectError(DemoLoginResetClient.EligibilityShapeException.class).verify();
    }

    @Test
    void eligibilityRejectsTopLevelObjectAndFractionalVersionInsteadOfCoercingEither() {
        StepVerifier.create(client(request -> Mono.just(jsonResponse("""
                {"id":"00000000-0000-0000-0000-000000000002","userId":"00000000-0000-0000-0000-0000000d3110","updatedAt":"2026-09-06T00:00:00Z","version":1}
                """)), "", "internal").observeEligibility("jwt")).expectError().verify();
        StepVerifier.create(client(request -> Mono.just(jsonResponse("""
                [{"id":"00000000-0000-0000-0000-000000000002","userId":"00000000-0000-0000-0000-0000000d3110","updatedAt":"2026-09-06T00:00:00Z","version":7.9}]
                """)), "", "internal").observeEligibility("jwt")).expectError().verify();
    }

    @Test
    void blankInternalKeyPreventsResetDispatch() {
        ExchangeFunction exchange = request -> Mono.error(new AssertionError("must not dispatch"));
        DemoLoginResetClient client = client(exchange, "", " ");
        DemoLoginPortfolioObservation observation = new DemoLoginPortfolioObservation(
                java.util.UUID.randomUUID(), DemoLoginResetClient.DEMO_USER_ID, Instant.EPOCH, 5,
                true, URI.create("http://localhost:18321/api/portfolio"), false, false);

        StepVerifier.create(client.reset(observation))
                .expectError(DemoLoginResetClient.ResetKeyNotConfiguredException.class)
                .verify();
    }

    private static DemoLoginResetClient client(ExchangeFunction exchange, String origin, String internal) {
        return new DemoLoginResetClient(
                WebClient.builder().exchangeFunction(exchange), loopbackPort(18321),
                new InternalApiKeyProvider(internal), new CloudFrontOriginSecretProvider(origin),
                new DemoLoginResetProperties(Duration.ofMinutes(30), Duration.ofSeconds(2),
                        Duration.ofSeconds(2), Duration.ofSeconds(4)),
                Clock.fixed(Instant.parse("2026-09-06T00:31:00Z"), ZoneOffset.UTC));
    }

    private static DemoLoginPortfolioObservation observation(long version) {
        return new DemoLoginPortfolioObservation(java.util.UUID.randomUUID(), DemoLoginResetClient.DEMO_USER_ID,
                Instant.EPOCH, version, true, URI.create("http://localhost:18321/api/portfolio"), false, false);
    }

    private static GatewayLoopbackTargetProvider loopbackPort(int port) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("local.server.port", port)));
        return new GatewayLoopbackTargetProvider(environment);
    }

    private static ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
