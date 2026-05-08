package com.wealth.insight.infrastructure.ai;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property P1: Profile Mutual Exclusion (JVM-side).
 *
 * <p>Validates: Requirements 1.1, 1.2, 15.1
 *
 * <p>For any combination of profiles drawn from
 * {@code {local, prod, aws, azure, bedrock, azure-ai}}, the
 * {@link AiProviderProfileValidator} must:
 * <ul>
 *   <li>throw {@link IllegalStateException} naming both {@code bedrock} and {@code azure-ai}
 *       when both are present in the active set, and</li>
 *   <li>complete without error for all other combinations.</li>
 * </ul>
 *
 * <p>Uses {@link ApplicationContextRunner} for a lightweight context that loads only
 * {@link AiProviderProfileValidator} — no Redis, Kafka, or AI starters required.
 */
class AiProviderProfileValidatorPropertyTest {

    private static final String[] KNOWN_PROFILES =
            {"local", "prod", "aws", "azure", "bedrock", "azure-ai"};

    /**
     * P1: When both {@code bedrock} and {@code azure-ai} are active, startup must fail
     * with an {@link IllegalStateException} whose message names both conflicting profiles.
     *
     * <p>Validates: Requirements 1.1, 1.2, 15.1
     */
    @Property(tries = 200)
    void p1_bothBedrockAndAzureAi_startupFails(
            @ForAll("profileCombinations") Set<String> profiles) {

        // Only run the "must fail" assertion when both conflicting profiles are present
        org.junit.jupiter.api.Assumptions.assumeTrue(
                profiles.contains("bedrock") && profiles.contains("azure-ai"));

        String[] profileArray = profiles.toArray(new String[0]);
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profileArray);

        AiProviderProfileValidator validator = new AiProviderProfileValidator(env);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bedrock")
                .hasMessageContaining("azure-ai");
    }

    /**
     * P1: When at most one of {@code bedrock} / {@code azure-ai} is active, startup must succeed.
     *
     * <p>Validates: Requirements 1.1, 1.2, 15.1
     */
    @Property(tries = 200)
    void p1_atMostOneAiProvider_startupSucceeds(
            @ForAll("profileCombinations") Set<String> profiles) {

        // Only run the "must succeed" assertion when the conflict is absent
        boolean bothActive = profiles.contains("bedrock") && profiles.contains("azure-ai");
        org.junit.jupiter.api.Assumptions.assumeFalse(bothActive);

        String[] profileArray = profiles.toArray(new String[0]);
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profileArray);

        AiProviderProfileValidator validator = new AiProviderProfileValidator(env);

        // Must not throw
        validator.validate();
    }

    @Provide
    Arbitrary<Set<String>> profileCombinations() {
        return Arbitraries.of(KNOWN_PROFILES)
                .set()
                .ofMinSize(0)
                .ofMaxSize(KNOWN_PROFILES.length);
    }
}
