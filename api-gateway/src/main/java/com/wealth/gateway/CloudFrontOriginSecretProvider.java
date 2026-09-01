package com.wealth.gateway;

import org.springframework.stereotype.Component;

/**
 * Resolves {@code CLOUDFRONT_ORIGIN_SECRET} exactly once and exposes it, unchanged, as the single
 * source consumers use to decide whether CloudFront origin verification is live. Consumers must
 * not read {@code System.getenv("CLOUDFRONT_ORIGIN_SECRET")} themselves.
 */
@Component
public final class CloudFrontOriginSecretProvider {

  private final String value;

  public CloudFrontOriginSecretProvider() {
    this(System.getenv("CLOUDFRONT_ORIGIN_SECRET"));
  }

  CloudFrontOriginSecretProvider(String value) {
    this.value = value;
  }

  String value() {
    return value;
  }

  boolean isRequired() {
    return value != null && !value.isBlank();
  }
}
