package com.wealth.insight.dto;

import com.wealth.insight.SentimentSource;

/**
 * Response payload for the chat endpoint.
 *
 * @param response        conversational plain-text wrapping the insight data
 * @param sentimentSource which implementation wrote the sentiment part of {@code response};
 *                        null when the response carries no sentiment text (rehearsal defect #5)
 */
public record ChatResponse(String response, SentimentSource sentimentSource) {

    /** A response with no sentiment text in it. */
    public ChatResponse(String response) {
        this(response, null);
    }
}
