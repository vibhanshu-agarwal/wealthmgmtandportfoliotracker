package com.wealth.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Covers every resolution state {@link CloudFrontOriginSecretProvider} must distinguish: null,
 * empty, ASCII whitespace, Unicode whitespace accepted by {@link String#isBlank()}, and a real
 * value. Each case asserts both the exact raw value and {@code isRequired()} together, since a
 * provider that returns the right value but the wrong {@code isRequired()} (or vice versa) would
 * silently break every consumer that relies on the pairing.
 */
class CloudFrontOriginSecretProviderTest {

  @Test
  void nullValueIsNotRequired() {
    CloudFrontOriginSecretProvider provider = new CloudFrontOriginSecretProvider(null);

    assertThat(provider.value()).isNull();
    assertThat(provider.isRequired()).isFalse();
  }

  @Test
  void emptyStringIsNotRequired() {
    CloudFrontOriginSecretProvider provider = new CloudFrontOriginSecretProvider("");

    assertThat(provider.value()).isEqualTo("");
    assertThat(provider.isRequired()).isFalse();
  }

  @Test
  void asciiWhitespaceIsNotRequired() {
    CloudFrontOriginSecretProvider provider = new CloudFrontOriginSecretProvider("   ");

    assertThat(provider.value()).isEqualTo("   ");
    assertThat(provider.isRequired()).isFalse();
  }

  @Test
  void unicodeWhitespaceIsNotRequired() {
    // U+2003 EM SPACE is not ASCII but is recognized by String.isBlank() (Character.isWhitespace).
    String emSpace = "\u2003";

    CloudFrontOriginSecretProvider provider = new CloudFrontOriginSecretProvider(emSpace);

    assertThat(provider.value()).isEqualTo(emSpace);
    assertThat(provider.isRequired()).isFalse();
  }

  @Test
  void nonBlankValueIsRequiredAndReturnedUnchanged() {
    CloudFrontOriginSecretProvider provider =
        new CloudFrontOriginSecretProvider("test-origin-secret-fixture");

    assertThat(provider.value()).isEqualTo("test-origin-secret-fixture");
    assertThat(provider.isRequired()).isTrue();
  }
}
