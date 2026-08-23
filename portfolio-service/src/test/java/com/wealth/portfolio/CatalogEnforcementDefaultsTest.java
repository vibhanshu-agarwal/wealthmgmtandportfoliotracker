package com.wealth.portfolio;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogEnforcementDefaultsTest {

    /**
     * Artifact default flipped true at cutover checkpoint 9.8 (supported-asset-integrity Task 9).
     * Effective behaviour stays false until 9.9 via an explicit Terraform override; see
     * .kiro/specs/supported-asset-integrity/tasks.md Task 9.
     */
    @Test
    void packagedYamlDefaultsBothEnforcementGatesToTrue() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yaml).contains("reject-unsupported-events: true");
        assertThat(yaml).contains("enforce-holding-invariant: true");
    }
}
