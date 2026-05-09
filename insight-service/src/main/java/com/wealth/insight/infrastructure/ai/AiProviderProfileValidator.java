package com.wealth.insight.infrastructure.ai;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Set;

/**
 * P1 JVM-side enforcement: fails fast when both {@code bedrock} and {@code azure-ai}
 * Spring profiles are active simultaneously.
 *
 * <p>This configuration class carries no {@code @Profile} annotation — it is always
 * active regardless of which profiles are enabled. The {@link #validate()} method runs
 * at context startup (via {@link PostConstruct}) and throws {@link IllegalStateException}
 * if the active profile set contains both {@code bedrock} and {@code azure-ai}, surfacing
 * the misconfiguration before any AI adapter beans are wired.
 *
 * <p>Requirements: 1.1, 1.2
 */
@Configuration
public class AiProviderProfileValidator {

    private final Environment environment;

    public AiProviderProfileValidator(Environment environment) {
        this.environment = environment;
    }

    /**
     * Validates that at most one AI provider profile is active.
     *
     * @throws IllegalStateException when both {@code bedrock} and {@code azure-ai} are active
     */
    @PostConstruct
    public void validate() {
        Set<String> activeProfiles = Set.of(environment.getActiveProfiles());
        boolean bedrockActive = activeProfiles.contains("bedrock");
        boolean azureAiActive = activeProfiles.contains("azure-ai");

        if (bedrockActive && azureAiActive) {
            throw new IllegalStateException(
                    "Conflicting AI provider profiles detected: both 'bedrock' and 'azure-ai' are active. " +
                    "Exactly one AI provider profile must be active at a time. " +
                    "Remove one of the conflicting profiles from SPRING_PROFILES_ACTIVE.");
        }
    }
}
