package com.wealth.gateway;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Set;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;

/**
 * Characterizes the standard request-level observations produced by the injected,
 * observation-enabled {@code WebClient.Builder}
 * ({@link DemoLoginResetConfiguration#demoLoginResetWebClientBuilder}). These observations stop
 * when the response is obtained, before asynchronous body processing necessarily completes, so
 * complete successful-leg timing is recorded separately by {@link DemoLoginResetDiagnostics}.
 */
class DemoLoginResetObservationTest {

    private static final String NAME = "http.client.requests";

    private static TestObservationRegistry runBothLegs() {
        var registry = TestObservationRegistry.create();
        var f = new DemoLoginResetOrchestratorTest.Fixture();
        f.observationRegistry = registry;
        StepVerifier.create(f.run()).verifyComplete();
        return registry;
    }

    @Test
    void bothSuccessfulLegsRecordOneClientObservationEach() {
        assertThat(runBothLegs()).hasNumberOfObservationsWithNameEqualTo(NAME, 2);
    }

    /**
     * A started-but-never-stopped observation records no request duration at all. This pins that
     * the standard client observation closes without claiming that it brackets downstream body
     * decoding or release.
     */
    @Test
    void clientObservationIsStartedAndStopped() {
        assertThat(runBothLegs())
                .hasObservationWithNameEqualTo(NAME)
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .doesNotHaveError();
    }

    @Test
    void theTwoLegsAreDistinguishableByMethodInTheRecordedEvidence() {
        var registry = runBothLegs();
        // The eligibility read is the GET and the reset write is the POST, so per-leg durations
        // are separable without any additional tag of our own.
        assertThat(registry).hasAnObservationWithAKeyValue("method", "GET");
        assertThat(registry).hasAnObservationWithAKeyValue("method", "POST");
    }

    /**
     * Records what the tags actually are rather than assuming them, and asserts the safety
     * obligation the diagnostics contract places on emitted evidence: no credential, raw target, or
     * identifier may appear in a tag. Both the key names and the tag values are checked, so a
     * future convention change that starts emitting a sensitive tag fails here.
     */
    @Test
    void tagsAreTheExpectedSafeSetAndCarryNoCredentialOrIdentifier() {
        var registry = runBothLegs();
        assertThat(registry).hasHandledContextsThatSatisfy(contexts -> {
            org.assertj.core.api.Assertions.assertThat(contexts).hasSize(2);
            Set<String> forbidden = Set.of("authorization", "x-internal-api-key", "x-origin-verify",
                    "token", "userId", "portfolioId", "url", "target");
            for (var context : contexts) {
                List<String> keys = context.getLowCardinalityKeyValues().stream()
                        .map(KeyValue::getKey).toList();
                org.assertj.core.api.Assertions.assertThat(keys).doesNotContainAnyElementsOf(forbidden);
                for (KeyValue keyValue : context.getLowCardinalityKeyValues()) {
                    org.assertj.core.api.Assertions.assertThat(keyValue.getValue())
                            .as("tag %s must not carry the bearer token", keyValue.getKey())
                            .doesNotContain("jwt");
                }
            }
        });
    }

    /**
     * Pins what URI attribution actually is, rather than assuming it. The client dispatches
     * absolute {@code URI} objects, not URI templates, so Spring registers no template and both
     * legs report {@code uri=none}. That is precisely why the separability claim above is made on
     * {@code method} and must not be made on {@code uri}. If a future change introduces templated
     * URIs this fails, and the documented claim can be strengthened deliberately.
     */
    @Test
    void uriIsNotAttributedSoMethodRemainsTheSeparatingTag() {
        var registry = runBothLegs();
        assertThat(registry).hasHandledContextsThatSatisfy(contexts -> {
            for (var context : contexts) {
                org.assertj.core.api.Assertions.assertThat(context.getLowCardinalityKeyValues())
                        .extracting(KeyValue::getKey, KeyValue::getValue)
                        .contains(org.assertj.core.groups.Tuple.tuple("uri", "none"));
            }
        });
    }

    /**
     * Pins the exact low-cardinality key set. This is the safety assertion: any future convention
     * change that begins emitting a credential, raw target, or identifier tag fails here rather
     * than silently shipping it into production evidence.
     */
    @Test
    void theLowCardinalityKeySetIsExactlyTheKnownSafeSet() {
        var registry = runBothLegs();
        assertThat(registry).hasHandledContextsThatSatisfy(contexts -> {
            for (var context : contexts) {
                org.assertj.core.api.Assertions.assertThat(
                                context.getLowCardinalityKeyValues().stream().map(KeyValue::getKey))
                        .containsExactlyInAnyOrder(
                                "client.name", "exception", "method", "outcome", "status", "uri");
            }
        });
    }
}
