package com.wealth.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Lightweight DTO representing a single holding fetched from portfolio-service.
 * Tolerates extra fields (e.g. {@code id}) from the upstream response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortfolioHoldingDto(UUID id, String assetTicker, BigDecimal quantity) {}
