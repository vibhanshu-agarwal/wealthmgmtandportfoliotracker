package com.wealth.market;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Profile activation tests for {@link InfrastructureHealthLogger}.
 *
 * <p>Verifies that the component:
 * <ul>
 *   <li>Is NOT loaded under the {@code local} profile</li>
 *   <li>IS loaded under the {@code aws} profile</li>
 *   <li>IS loaded under the {@code azure} profile</li>
 * </ul>
 */
class InfrastructureHealthLoggerProfileTest {

    // -------------------------------------------------------------------------
    // Test 1: Component should NOT be loaded under 'local' profile
    // -------------------------------------------------------------------------
    @SpringBootTest
    @ActiveProfiles("local")
    static class LocalProfileTest {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        void shouldNotLoadUnderLocalProfile() {
            assertThatThrownBy(() -> applicationContext.getBean(InfrastructureHealthLogger.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class)
                    .hasMessageContaining("InfrastructureHealthLogger");
        }
    }

    // -------------------------------------------------------------------------
    // Test 2: Component SHOULD be loaded under 'aws' profile
    // -------------------------------------------------------------------------
    @SpringBootTest
    @ActiveProfiles("aws")
    static class AwsProfileTest {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        void shouldLoadUnderAwsProfile() {
            InfrastructureHealthLogger bean = applicationContext.getBean(InfrastructureHealthLogger.class);
            assertThat(bean).isNotNull();
        }
    }

    // -------------------------------------------------------------------------
    // Test 3: Component SHOULD be loaded under 'azure' profile
    // -------------------------------------------------------------------------
    @SpringBootTest
    @ActiveProfiles("azure")
    static class AzureProfileTest {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        void shouldLoadUnderAzureProfile() {
            InfrastructureHealthLogger bean = applicationContext.getBean(InfrastructureHealthLogger.class);
            assertThat(bean).isNotNull();
        }
    }
}
