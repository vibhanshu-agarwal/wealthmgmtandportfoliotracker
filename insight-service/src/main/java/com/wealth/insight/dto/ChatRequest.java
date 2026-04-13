package com.wealth.insight.dto;

/**
 * Request payload for the chat endpoint.
 *
 * @param message the user's natural language question (required)
 * @param ticker  explicit ticker symbol (optional — overrides extraction from message)
 */
public record ChatRequest(String message, String ticker) {}
