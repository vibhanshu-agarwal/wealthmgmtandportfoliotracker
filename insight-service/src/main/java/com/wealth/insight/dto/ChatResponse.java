package com.wealth.insight.dto;

/**
 * Response payload for the chat endpoint.
 *
 * @param response conversational plain-text wrapping the insight data
 */
public record ChatResponse(String response) {}
