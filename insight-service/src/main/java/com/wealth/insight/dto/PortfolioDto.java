package com.wealth.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight DTO representing a portfolio fetched from portfolio-service via REST.
 * Decoupled from the JPA entity in portfolio-service.
 *
 * <p>Uses {@code @JsonIgnoreProperties} to tolerate extra fields in the upstream
 * response without breaking deserialization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortfolioDto(UUID id, String userId, Instant createdAt, List<PortfolioHoldingDto> holdings) {}
