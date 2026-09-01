package com.wealth.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiKeyProviderTest {

    @Test
    void nullValueIsNotConfiguredAndReturnedUnchanged() {
        InternalApiKeyProvider provider = new InternalApiKeyProvider(null);

        assertThat(provider.value()).isNull();
        assertThat(provider.isConfigured()).isFalse();
    }

    @Test
    void emptyStringIsNotConfiguredAndReturnedUnchanged() {
        InternalApiKeyProvider provider = new InternalApiKeyProvider("");

        assertThat(provider.value()).isEmpty();
        assertThat(provider.isConfigured()).isFalse();
    }

    @Test
    void asciiWhitespaceIsNotConfiguredAndReturnedUnchanged() {
        InternalApiKeyProvider provider = new InternalApiKeyProvider("   \t\n");

        assertThat(provider.value()).isEqualTo("   \t\n");
        assertThat(provider.isConfigured()).isFalse();
    }

    @Test
    void unicodeWhitespaceIsNotConfiguredAndReturnedUnchanged() {
        InternalApiKeyProvider provider = new InternalApiKeyProvider("\u2003");

        assertThat(provider.value()).isEqualTo("\u2003");
        assertThat(provider.isConfigured()).isFalse();
    }

    @Test
    void nonBlankValueIsConfiguredAndReturnedUnchanged() {
        InternalApiKeyProvider provider = new InternalApiKeyProvider("smoke-test-value");

        assertThat(provider.value()).isEqualTo("smoke-test-value");
        assertThat(provider.isConfigured()).isTrue();
    }
}
