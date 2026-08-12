package com.wealth.gateway;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * .kiro/specs/new-user-signup-profile, Requirement 2.6, 8.6, 8.7: the gateway has no Better Auth
 * types and defines no Flyway migration of its own.
 */
class BetterAuthArchitectureTest {

    private static final JavaClasses GATEWAY_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.wealth.gateway");

    @Test
    void noClassesDependOnBetterAuthTypes() {
        noClasses()
                .that().resideInAPackage("com.wealth.gateway..")
                .should().dependOnClassesThat().resideInAnyPackage("..betterauth..", "..better_auth..")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void gatewayHasNoFlywayMigrationDirectory() {
        java.io.File migrationDir = new java.io.File("src/main/resources/db/migration");
        org.assertj.core.api.Assertions.assertThat(migrationDir).doesNotExist();
    }
}
