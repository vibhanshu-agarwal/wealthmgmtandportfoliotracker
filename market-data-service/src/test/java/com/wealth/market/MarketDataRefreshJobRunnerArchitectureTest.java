package com.wealth.market;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class MarketDataRefreshJobRunnerArchitectureTest {

    private static final JavaClasses MARKET_DATA_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.wealth.market");

    @Test
    void runnerDoesNotImportAzureSpecificSdkClasses() {
        noClasses()
                .that().haveSimpleName("MarketDataRefreshJobRunner")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.azure..", "com.microsoft.azure..")
                .check(MARKET_DATA_CLASSES);
    }
}
